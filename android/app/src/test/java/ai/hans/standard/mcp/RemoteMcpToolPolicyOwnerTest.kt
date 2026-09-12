package ai.hans.standard.mcp

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteMcpToolPolicyOwnerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun coldLoadResolvesOnlyExactDurablePolicyAndProjectsRevision() {
        val directory = temporaryFolder.newFolder("cold")
        val store = store(directory)
        val approved = tool("private original metadata")
        store.compareAndSet(
            0,
            listOf(
                RemoteMcpToolPolicyApproval(
                    identity(),
                    approved,
                    RemoteMcpToolEffect.READ_ONLY,
                ),
            ),
        )

        val owner = RemoteMcpToolPolicyOwner(store, RemoteMcpSignedVerifierRegistry.EMPTY)

        assertEquals(RemoteMcpToolEffect.READ_ONLY, owner.resolve(identity(), approved)?.effect)
        assertNull(owner.resolve(identity(), tool("changed metadata")))
        assertEquals(1L, owner.currentRevisionOrThrow())
        assertEquals(
            RemoteMcpToolPolicyOwnerSnapshot(true, true, 1L, 1),
            owner.passiveSnapshot(),
        )
    }

    @Test
    fun externalCasRequiresExplicitReloadAndSupportsGrantThenRevoke() {
        val directory = temporaryFolder.newFolder("reload")
        val writer = store(directory)
        val owner = RemoteMcpToolPolicyOwner(
            store(directory),
            RemoteMcpSignedVerifierRegistry.EMPTY,
        )
        val approved = tool("grant")

        writer.compareAndSet(
            0,
            listOf(
                RemoteMcpToolPolicyApproval(
                    identity(),
                    approved,
                    RemoteMcpToolEffect.READ_ONLY,
                ),
            ),
        )
        assertNull(owner.resolve(identity(), approved))
        assertEquals(RemoteMcpToolPolicyOwnerSnapshot(true, false, 1L, 0), owner.passiveSnapshot())
        assertFailureCode("mcp_tool_policy_revision_changed") {
            owner.currentRevisionOrThrow()
        }

        assertEquals(RemoteMcpToolPolicyOwnerSnapshot(true, true, 1L, 1), owner.reloadFromStore())
        assertEquals(RemoteMcpToolEffect.READ_ONLY, owner.resolve(identity(), approved)?.effect)

        writer.compareAndSet(1, emptyList())
        assertNull(owner.resolve(identity(), approved))
        assertEquals(RemoteMcpToolPolicyOwnerSnapshot(true, false, 2L, 0), owner.passiveSnapshot())
        assertEquals(RemoteMcpToolPolicyOwnerSnapshot(true, true, 2L, 0), owner.reloadFromStore())
        assertNull(owner.resolve(identity(), approved))
        assertEquals(2L, owner.currentRevisionOrThrow())
    }

    @Test
    fun concurrentReadersSeeOnlyWholeImmutableGrantOrFailClosedState() {
        val directory = temporaryFolder.newFolder("concurrent")
        val writer = store(directory)
        val owner = RemoteMcpToolPolicyOwner(
            store(directory),
            RemoteMcpSignedVerifierRegistry.EMPTY,
        )
        val approved = tool("concurrent")
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val pool = Executors.newFixedThreadPool(5)
        val readers = (0 until 4).map {
            pool.submit {
                start.await()
                repeat(80) {
                    runCatching {
                        val resolved = owner.resolve(identity(), approved)
                        check(resolved == null || resolved.effect == RemoteMcpToolEffect.READ_ONLY)
                        val passive = owner.passiveSnapshot()
                        check(!passive.current || passive.storeAvailable)
                        check(passive.current || passive.approvedPolicyCount == 0)
                    }.onFailure { failure.compareAndSet(null, it) }
                }
            }
        }
        val writerTask = pool.submit {
            start.await()
            var revision = 0L
            repeat(12) { index ->
                val approvals = if (index % 2 == 0) {
                    listOf(
                        RemoteMcpToolPolicyApproval(
                            identity(),
                            approved,
                            RemoteMcpToolEffect.READ_ONLY,
                        ),
                    )
                } else {
                    emptyList()
                }
                writer.compareAndSet(revision, approvals)
                revision++
                owner.reloadFromStore()
            }
        }
        start.countDown()
        try {
            readers.forEach { it.get(10, TimeUnit.SECONDS) }
            writerTask.get(10, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }

        assertNull(failure.get())
        assertEquals(12L, owner.currentRevisionOrThrow())
        assertNull(owner.resolve(identity(), approved))
    }

    @Test
    fun corruptionAfterColdLoadImmediatelyClearsEffectivePolicy() {
        val directory = temporaryFolder.newFolder("corrupt")
        val store = store(directory)
        val approved = tool("before corruption")
        store.compareAndSet(
            0,
            listOf(
                RemoteMcpToolPolicyApproval(
                    identity(),
                    approved,
                    RemoteMcpToolEffect.READ_ONLY,
                ),
            ),
        )
        val owner = RemoteMcpToolPolicyOwner(store, RemoteMcpSignedVerifierRegistry.EMPTY)
        assertEquals(RemoteMcpToolEffect.READ_ONLY, owner.resolve(identity(), approved)?.effect)

        File(directory, "trusted-tool-policies-v1.json").writeText("{corrupt")

        assertNull(owner.resolve(identity(), approved))
        assertEquals(
            RemoteMcpToolPolicyOwnerSnapshot(false, false, 0L, 0),
            owner.passiveSnapshot(),
        )
        assertFailureCode("mcp_tool_policy_store_unavailable") {
            owner.currentRevisionOrThrow()
        }
        assertEquals(RemoteMcpToolPolicyOwnerSnapshot(false, false, 0L, 0), owner.reloadFromStore())
    }

    @Test
    fun passiveProjectionAndOwnerStringRevealNoIdentityToolOrMetadata() {
        val directory = temporaryFolder.newFolder("redacted")
        val store = store(directory)
        val privateTool = tool("highly-private-description-marker")
        store.compareAndSet(
            0,
            listOf(
                RemoteMcpToolPolicyApproval(
                    identity(),
                    privateTool,
                    RemoteMcpToolEffect.READ_ONLY,
                ),
            ),
        )
        val owner = RemoteMcpToolPolicyOwner(store, RemoteMcpSignedVerifierRegistry.EMPTY)
        val rendered = owner.passiveSnapshot().toString() + owner.toString()

        assertFalse(rendered.contains("tasks-plugin"))
        assertFalse(rendered.contains("tasks/list"))
        assertFalse(rendered.contains("highly-private"))
        assertFalse(rendered.contains(identity().configurationDigest))
        assertFalse(rendered.contains("mcp.example.com"))
        assertTrue(rendered.contains("approvedPolicyCount=1"))
    }

    private fun assertFailureCode(expected: String, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue(failure is RemoteMcpFailure)
        assertEquals(expected, (failure as RemoteMcpFailure).code)
    }

    private fun store(directory: File) = RemoteMcpToolPolicyStore(
        directory,
        RemoteMcpSignedVerifierRegistry.EMPTY,
    )

    private fun identity() = RemoteMcpActivationIdentity(
        pluginId = "tasks-plugin",
        serverId = "tasks",
        configurationDigest = "a".repeat(64),
    )

    private fun tool(description: String) = RemoteMcpTool(
        name = "tasks/list",
        title = "Private tool title",
        description = description,
        inputSchemaJson = """{"type":"object","description":"private schema"}""",
        outputSchemaJson = """{"type":"object"}""",
        metaJson = """{"tenant":"private tenant"}""",
    )
}
