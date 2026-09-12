package ai.hans.standard.mcp

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteMcpPolicyReviewCoordinatorTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun explicitApprovalPreservesUnrelatedPolicyAndOnlyRefreshesRuntimeBoundaries() {
        val directory = temporaryFolder.newFolder("approve")
        val store = store(directory)
        val unrelatedIdentity = identity("calendar-plugin", "calendar", 'b')
        val unrelatedTool = tool("calendar/list", "Calendar")
        store.compareAndSetForActivation(
            0,
            unrelatedIdentity,
            listOf(
                RemoteMcpToolPolicyApproval(
                    unrelatedIdentity,
                    unrelatedTool,
                    RemoteMcpToolEffect.READ_ONLY,
                ),
            ),
        )
        val identity = identity()
        val reviewedTool = tool(
            name = "tasks/list",
            description = "Tasks",
            annotations = """{"readOnlyHint":false,"destructiveHint":true}""",
        )
        val catalog = catalog(identity, reviewedTool)
        val request = catalog.request(store.snapshot().revision)
        val callbacks = Callbacks()
        val coordinator = callbacks.coordinator(store)

        val updated = coordinator.approve(
            request = request,
            currentCatalog = catalog,
            decisions = listOf(
                RemoteMcpPolicyReviewDecision(
                    toolName = reviewedTool.name,
                    toolMetadataDigest = remoteMcpToolMetadataDigest(reviewedTool),
                    // The remote hint says otherwise; the explicit human choice is authoritative.
                    effect = RemoteMcpToolEffect.READ_ONLY,
                ),
            ),
        )

        assertEquals(2L, updated.revision)
        assertEquals(
            setOf(unrelatedIdentity, identity),
            updated.policies.mapTo(linkedSetOf()) { it.activationIdentity },
        )
        assertEquals(RemoteMcpToolEffect.READ_ONLY, updated.policies.single {
            it.activationIdentity == identity
        }.effect)
        callbacks.assertExactlyOneRefresh(identity)
    }

    @Test
    fun staleRevisionAndMetadataDriftFailBeforeAnyWriteOrRuntimeCallback() {
        val staleDirectory = temporaryFolder.newFolder("stale")
        val staleStore = store(staleDirectory)
        val identity = identity()
        val original = tool("tasks/list", "Original")
        val originalCatalog = catalog(identity, original)
        val staleRequest = originalCatalog.request(0)
        staleStore.compareAndSetForActivation(
            0,
            identity("other-plugin", "other", 'd'),
            emptyList(),
        )
        val staleCallbacks = Callbacks()
        val staleFailure = runCatching {
            staleCallbacks.coordinator(staleStore).approve(
                staleRequest,
                originalCatalog,
                listOf(decision(original)),
            )
        }.exceptionOrNull()
        assertFailureCode("mcp_tool_policy_revision_stale", staleFailure)
        assertEquals(1L, staleStore.snapshot().revision)
        staleCallbacks.assertNoRefresh()

        val driftDirectory = temporaryFolder.newFolder("drift")
        val driftStore = store(driftDirectory)
        val driftCallbacks = Callbacks()
        val changed = tool("tasks/list", "Changed")
        assertNotEquals(
            remoteMcpToolMetadataDigest(original),
            remoteMcpToolMetadataDigest(changed),
        )
        val driftFailure = runCatching {
            driftCallbacks.coordinator(driftStore).approve(
                originalCatalog.request(0),
                catalog(identity, changed),
                listOf(decision(original)),
            )
        }.exceptionOrNull()
        assertFailureCode("mcp_policy_review_catalog_changed", driftFailure)
        assertEquals(0L, driftStore.snapshot().revision)
        driftCallbacks.assertNoRefresh()
    }

    @Test
    fun exactRevocationRemovesOnlyReviewedActivationAndDoesNotRetryInstall() {
        val directory = temporaryFolder.newFolder("revoke")
        val store = store(directory)
        val identity = identity()
        val reviewedTool = tool("tasks/list", "Approved")
        val unrelatedIdentity = identity("notes-plugin", "notes", 'c')
        val unrelatedTool = tool("notes/list", "Notes")
        var snapshot = store.compareAndSetForActivation(
            0,
            identity,
            listOf(
                RemoteMcpToolPolicyApproval(
                    identity,
                    reviewedTool,
                    RemoteMcpToolEffect.READ_ONLY,
                ),
            ),
        )
        snapshot = store.compareAndSetForActivation(
            snapshot.revision,
            unrelatedIdentity,
            listOf(
                RemoteMcpToolPolicyApproval(
                    unrelatedIdentity,
                    unrelatedTool,
                    RemoteMcpToolEffect.READ_ONLY,
                ),
            ),
        )
        val catalog = catalog(identity, reviewedTool)
        val callbacks = Callbacks()

        val revoked = callbacks.coordinator(store).revoke(
            catalog.request(snapshot.revision),
            catalog,
        )

        assertEquals(3L, revoked.revision)
        assertEquals(listOf(unrelatedIdentity), revoked.policies.map { it.activationIdentity })
        callbacks.assertExactlyOneRefresh(identity)
        // There is intentionally no installer/retry callback in the coordinator contract.
    }

    @Test
    fun mutatingDecisionRequiresVerifierFromSignedInAppRegistryEvenWhenHintsClaimReadOnly() {
        val directory = temporaryFolder.newFolder("mutating")
        val identity = identity()
        val reviewedTool = tool(
            "tasks/create",
            "Create",
            """{"readOnlyHint":true,"destructiveHint":false}""",
        )
        val catalog = catalog(identity, reviewedTool)
        val unsignedStore = store(directory)
        val callbacks = Callbacks()

        val failure = runCatching {
            callbacks.coordinator(unsignedStore).approve(
                catalog.request(0),
                catalog,
                listOf(
                    RemoteMcpPolicyReviewDecision(
                        reviewedTool.name,
                        remoteMcpToolMetadataDigest(reviewedTool),
                        RemoteMcpToolEffect.MUTATING,
                        "tasks.create.v1",
                    ),
                ),
            )
        }.exceptionOrNull()

        assertFailureCode("mcp_tool_policy_verifier_untrusted", failure)
        assertEquals(0L, unsignedStore.snapshot().revision)
        callbacks.assertNoRefresh()
    }

    private fun catalog(
        identity: RemoteMcpActivationIdentity,
        vararg tools: RemoteMcpTool,
    ) = RemoteMcpPolicyReviewCatalog.fromDiscovery(
        activationIdentity = identity,
        allowedToolNames = tools.mapTo(linkedSetOf(), RemoteMcpTool::name),
        discovery = RemoteMcpDiscoveryReceipt(
            tools = tools.toList(),
            allowedToolNames = emptySet(),
        ),
    )

    private fun decision(tool: RemoteMcpTool) = RemoteMcpPolicyReviewDecision(
        toolName = tool.name,
        toolMetadataDigest = remoteMcpToolMetadataDigest(tool),
        effect = RemoteMcpToolEffect.READ_ONLY,
    )

    private fun store(directory: File) = RemoteMcpToolPolicyStore(
        directory,
        RemoteMcpSignedVerifierRegistry.EMPTY,
    )

    private fun identity(
        pluginId: String = "tasks-plugin",
        serverId: String = "tasks",
        digestCharacter: Char = 'a',
    ) = RemoteMcpActivationIdentity(
        pluginId = pluginId,
        serverId = serverId,
        configurationDigest = digestCharacter.toString().repeat(64),
    )

    private fun tool(
        name: String,
        description: String,
        annotations: String? = null,
    ) = RemoteMcpTool(
        name = name,
        title = description,
        description = description,
        inputSchemaJson = """{"type":"object","properties":{}}""",
        outputSchemaJson = null,
        annotationsJson = annotations,
    )

    private fun assertFailureCode(expected: String, failure: Throwable?) {
        assertTrue(failure is RemoteMcpFailure)
        assertEquals(expected, (failure as RemoteMcpFailure).code)
    }

    private class Callbacks {
        private val reloads = AtomicInteger()
        private val closes = AtomicInteger()
        private val refreshes = AtomicInteger()
        private var closedIdentity: RemoteMcpActivationIdentity? = null
        private var refreshedIdentity: RemoteMcpActivationIdentity? = null

        fun coordinator(store: RemoteMcpToolPolicyStore) = RemoteMcpPolicyReviewCoordinator(
            store = store,
            reloadPolicyOwner = RemoteMcpPolicyOwnerReloadCallback { reloads.incrementAndGet() },
            closeExactSession = RemoteMcpExactSessionCloseCallback { identity ->
                closes.incrementAndGet()
                closedIdentity = identity
            },
            refreshContract = RemoteMcpContractRefreshCallback { identity ->
                refreshes.incrementAndGet()
                refreshedIdentity = identity
            },
        )

        fun assertExactlyOneRefresh(identity: RemoteMcpActivationIdentity) {
            assertEquals(1, reloads.get())
            assertEquals(1, closes.get())
            assertEquals(1, refreshes.get())
            assertEquals(identity, closedIdentity)
            assertEquals(identity, refreshedIdentity)
        }

        fun assertNoRefresh() {
            assertEquals(0, reloads.get())
            assertEquals(0, closes.get())
            assertEquals(0, refreshes.get())
            assertFalse(closedIdentity != null)
            assertFalse(refreshedIdentity != null)
        }
    }
}
