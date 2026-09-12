package ai.hans.standard.mcp

import ai.hans.standard.plugins.runtime.OAuthCredentialHandle
import ai.hans.standard.plugins.runtime.RemoteMcpRequirement
import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteMcpSessionRegistryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun prepareCommitAndProcessRestartExposeNothingUntilExactFinalize() {
        val definition = definition("a".repeat(64))
        val firstFactory = ScriptedSessionFactory()
        val registry = RemoteMcpSessionRegistry(temporaryFolder.root, firstFactory)
        val receipt = registry.prepare(definition)

        assertEquals(RemoteMcpActivationPhase.PREPARED, registry.pendingRecovery().single().phase)
        assertNotFinalized(registry, definition.identity)
        assertEquals(0, firstFactory.openCount.get())
        registry.commit(receipt)
        assertEquals(RemoteMcpActivationPhase.COMMITTED, registry.pendingRecovery().single().phase)
        assertNotFinalized(registry, definition.identity)
        assertEquals(0, firstFactory.openCount.get())

        val restartedFactory = ScriptedSessionFactory()
        val restarted = RemoteMcpSessionRegistry(temporaryFolder.root, restartedFactory)
        val recovery = restarted.pendingRecovery().single()
        val recoveredReceipt = restarted.receiptForRecovery(recovery)
        assertEquals(0, restartedFactory.openCount.get())
        restarted.finalize(recoveredReceipt)
        assertTrue(restarted.pendingRecovery().isEmpty())
        assertEquals(0, restartedFactory.openCount.get())

        val discovery = restarted.discover(definition.identity)
        assertEquals(setOf("tasks/list"), discovery.allowedToolNames)
        assertEquals(1, restartedFactory.openCount.get())
        val status = restarted.status()
        assertTrue(status.available)
        assertEquals(1, status.finalizedActivationCount)
        assertTrue(status.activations.single().sessionOpen)
        assertFalse(status.toString().contains(definition.identity.configurationDigest))
        assertFalse(status.toString().contains(definition.requirement.endpoint))
        assertFalse(status.toString().contains(definition.requirement.oauthHandle.toString()))

        restarted.close()
        val secondRestart = RemoteMcpSessionRegistry(
            temporaryFolder.root,
            ScriptedSessionFactory(),
        )
        assertEquals(1, secondRestart.status().finalizedActivationCount)
        assertFalse(secondRestart.status().activations.single().sessionOpen)
    }

    @Test
    fun committedRollbackCancellationCloseAndExactDeactivationAreFailClosed() {
        val definition = definition("b".repeat(64))
        val factory = ScriptedSessionFactory()
        val registry = RemoteMcpSessionRegistry(temporaryFolder.root, factory)
        val rolledBack = registry.prepare(definition)
        registry.commit(rolledBack)
        assertTrue(registry.rollback(rolledBack))
        assertFalse(registry.rollback(rolledBack))
        assertNotFinalized(registry, definition.identity)

        val activation = registry.prepare(definition)
        registry.commit(activation)
        registry.finalize(activation)
        val cancelled = assertThrows(RemoteMcpFailure::class.java) {
            registry.discover(definition.identity, RemoteMcpCancellation { true })
        }
        assertEquals("mcp_call_cancelled", cancelled.code)
        assertTrue(registry.closeSession(definition.identity))
        assertFalse(registry.closeSession(definition.identity))

        val stale = definition.identity.copy(configurationDigest = "c".repeat(64))
        val staleFailure = assertThrows(RemoteMcpFailure::class.java) {
            registry.finalizedSession(stale)
        }
        assertEquals("mcp_activation_stale_identity", staleFailure.code)
        assertEquals(RemoteMcpDeactivationResult.STALE_IDENTITY, registry.deactivate(stale))
        assertEquals(
            RemoteMcpDeactivationResult.STALE_IDENTITY,
            registry.deactivate(definition.identity, "d".repeat(64)),
        )
        assertEquals(1, registry.status().finalizedActivationCount)
        assertEquals(
            RemoteMcpDeactivationResult.DEACTIVATED,
            registry.deactivate(definition.identity, definition.metadataDigest),
        )
        assertEquals(RemoteMcpDeactivationResult.MISSING, registry.deactivate(definition.identity))
        assertEquals(
            0,
            RemoteMcpSessionRegistry(temporaryFolder.root, ScriptedSessionFactory())
                .status().finalizedActivationCount,
        )
    }

    @Test
    fun activeIdentityCannotBeDeactivatedWhileReplacementTransactionIsPending() {
        val first = definition("d".repeat(64))
        val registry = RemoteMcpSessionRegistry(temporaryFolder.root, ScriptedSessionFactory())
        registry.prepare(first).also {
            registry.commit(it)
            registry.finalize(it)
        }
        val replacement = definition("e".repeat(64), endpoint = "https://mcp.example.com/v2")
        val pending = registry.prepare(replacement)

        assertEquals(
            RemoteMcpDeactivationResult.TRANSACTION_PENDING,
            registry.deactivate(first.identity),
        )
        assertTrue(registry.rollback(pending))
        assertEquals(
            RemoteMcpDeactivationResult.DEACTIVATED,
            registry.deactivate(first.identity),
        )
    }

    @Test
    fun sameServerIdCanBeFinalizedIndependentlyForTwoPlugins() {
        val first = definition("1".repeat(64), pluginId = "first-plugin")
        val second = definition("2".repeat(64), pluginId = "second-plugin")
        val factory = ScriptedSessionFactory()
        val registry = RemoteMcpSessionRegistry(temporaryFolder.root, factory)
        listOf(first, second).forEach { definition ->
            registry.prepare(definition).also { receipt ->
                registry.commit(receipt)
                registry.finalize(receipt)
            }
        }

        assertEquals(2, registry.status().finalizedActivationCount)
        assertEquals(
            setOf("first-plugin", "second-plugin"),
            registry.status().activations.mapTo(linkedSetOf(), RemoteMcpActivationStatus::pluginId),
        )
        assertTrue(registry.finalizedSession(first.identity) !== registry.finalizedSession(second.identity))
    }

    @Test
    fun policyRefreshCloseIsANoOpWhenExactProcessSessionWasNeverFinalizedOrOpened() {
        val definition = definition("9".repeat(64))
        val registry = RemoteMcpSessionRegistry(temporaryFolder.root, ScriptedSessionFactory())

        assertFalse(registry.closeSessionIfPresent(definition.identity))
        val pending = registry.prepare(definition)
        assertFalse(registry.closeSessionIfPresent(definition.identity))
        assertTrue(registry.rollback(pending))

        registry.prepare(definition).also {
            registry.commit(it)
            registry.finalize(it)
        }
        assertFalse(registry.closeSessionIfPresent(definition.identity))
        registry.finalizedSession(definition.identity)
        assertTrue(registry.closeSessionIfPresent(definition.identity))
        assertFalse(registry.closeSessionIfPresent(definition.identity))
    }

    @Test
    fun corruptOversizedAndSymlinkedRegistryFailClosedAcrossRecreation() {
        val file = File(temporaryFolder.root, "session-registry-v1.json")
        file.writeText("{not-json")
        var registry = RemoteMcpSessionRegistry(temporaryFolder.root, ScriptedSessionFactory())
        assertFalse(registry.status().available)
        assertThrows(RemoteMcpFailure::class.java) { registry.prepare(definition("f".repeat(64))) }

        file.writeBytes(ByteArray(1024 * 1024 + 1))
        registry = RemoteMcpSessionRegistry(temporaryFolder.root, ScriptedSessionFactory())
        assertFalse(registry.status().available)

        assertTrue(file.delete())
        val outside = temporaryFolder.newFile("outside-registry.json").apply { writeText("{}") }
        Files.createSymbolicLink(file.toPath(), outside.toPath())
        registry = RemoteMcpSessionRegistry(temporaryFolder.root, ScriptedSessionFactory())
        assertFalse(registry.status().available)
        assertTrue(registry.pendingRecovery().isEmpty())
    }

    private fun assertNotFinalized(
        registry: RemoteMcpSessionRegistry,
        identity: RemoteMcpActivationIdentity,
    ) {
        val failure = assertThrows(RemoteMcpFailure::class.java) {
            registry.finalizedSession(identity)
        }
        assertEquals("mcp_activation_not_finalized", failure.code)
    }

    private fun definition(
        handle: String,
        endpoint: String = "https://mcp.example.com/v1",
        pluginId: String = "tasks-plugin",
    ): RemoteMcpActivationDefinition {
        val requirement = RemoteMcpRequirement(
            id = "tasks",
            endpoint = endpoint,
            oauthHandle = OAuthCredentialHandle(handle),
            allowedTools = setOf("tasks/list"),
            required = true,
            requestTimeoutMillis = 10_000,
            maxResponseBytes = 128 * 1024,
        )
        return RemoteMcpActivationDefinition(
            identity = RemoteMcpActivationIdentity(
                pluginId = pluginId,
                serverId = requirement.id,
                configurationDigest = RemoteMcpConfigurationIdentity.digest(requirement),
            ),
            requirement = requirement,
        )
    }

    private class ScriptedSessionFactory : RemoteMcpSessionFactory {
        val openCount = AtomicInteger(0)

        override fun open(definition: RemoteMcpActivationDefinition): RemoteMcpSession {
            openCount.incrementAndGet()
            val transport = ScriptedTransport(
                ArrayDeque(
                    listOf(
                        jsonResponse(1, modernDiscoverResult(), null),
                        jsonResponse(
                            2,
                            toolsResult("tasks/list")
                                .put("ttlMs", 0)
                                .put("cacheScope", "private")
                                .put("resultType", "complete"),
                            null,
                        ),
                    ),
                ),
            )
            return RemoteMcpSession(
                definition = definition,
                http = transport,
                oauth = AvailableCredentials,
                toolPolicies = RemoteMcpToolPolicyRegistry(
                    listOf(
                        RemoteMcpToolPolicy.approved(
                            definition.identity,
                            RemoteMcpTool(
                                name = "tasks/list",
                                title = null,
                                description = "Tool tasks/list",
                                inputSchemaJson = """{"type":"object"}""",
                                outputSchemaJson = null,
                            ),
                            RemoteMcpToolEffect.READ_ONLY,
                        ),
                    ),
                ),
                passiveStatuses = RemoteMcpPassiveStatusRegistry(),
            )
        }
    }

    private class ScriptedTransport(
        private val responses: ArrayDeque<RemoteMcpHttpResponse>,
    ) : RemoteMcpHttpCallFactory {
        override fun create(request: RemoteMcpHttpRequest, bearerToken: String?): RemoteMcpHttpCall =
            object : RemoteMcpHttpCall {
                override fun execute(): RemoteMcpHttpResponse = responses.removeFirst()
                override fun cancel() = Unit
                override fun close() = Unit
            }
    }

    private object AvailableCredentials : RemoteMcpOAuthVault {
        override fun state(identity: RemoteMcpOAuthCredentialIdentity) =
            OAuthCredentialState.AVAILABLE

        override fun <T> withBearerToken(
            identity: RemoteMcpOAuthCredentialIdentity,
            block: (String) -> T,
        ): T = block("test-only-token")
    }

    private companion object {
        fun initializeResult() = JSONObject()
            .put("protocolVersion", RemoteMcpProtocol.PROTOCOL_VERSION)
            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
            .put("serverInfo", JSONObject().put("name", "test").put("version", "1"))

        fun modernDiscoverResult() = JSONObject()
            .put("resultType", "complete")
            .put(
                "supportedVersions",
                JSONArray().put(RemoteMcpProtocol.MODERN_PROTOCOL_VERSION),
            )
            .put("capabilities", JSONObject().put("tools", JSONObject()))
            .put("ttlMs", 0)
            .put("cacheScope", "private")

        fun toolsResult(vararg names: String) = JSONObject()
            .put(
                "tools",
                JSONArray(names.map { name ->
                    JSONObject()
                        .put("name", name)
                        .put("description", "Tool $name")
                        .put("inputSchema", JSONObject().put("type", "object"))
                }),
            )
            .put("nextCursor", JSONObject.NULL)

        fun jsonResponse(id: Long, result: JSONObject, sessionId: String?) =
            RemoteMcpHttpResponse(
                200,
                "application/json",
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", id)
                    .put("result", result)
                    .toString()
                    .toByteArray(),
                sessionId,
            )
    }
}
