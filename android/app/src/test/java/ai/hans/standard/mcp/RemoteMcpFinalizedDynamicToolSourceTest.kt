package ai.hans.standard.mcp

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.plugins.runtime.RemoteMcpRequirement
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteMcpFinalizedDynamicToolSourceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun passiveSnapshotDoesNoNetworkAndFinalizedAllowedToolIsCallableAfterFreshProof() {
        val expected = tool(description = "Finalized task reader")
        val transport = TrackingTransport(expected)
        val fixture = fixture(transport, policies = listOf(expected))
        val source = fixture.source()

        val executor = source.snapshot().single()
        assertEquals(0, transport.networkCalls.get())
        assertEquals(1, executor.specs.single().tools.size)
        assertEquals("remote-mcp-policy-v1:7", source.policyRevisionToken())
        assertEquals(0, transport.networkCalls.get())

        val result = execute(executor)

        assertTrue(result.success)
        assertEquals("ok", JSONObject(result.contentText)
            .getJSONObject("remoteResult")
            .getJSONArray("content")
            .getJSONObject(0)
            .getString("text"))
        assertEquals(3, transport.networkCalls.get())
        assertEquals(1, transport.toolCalls.get())
    }

    @Test
    fun currentPolicyRevisionChangeRevokesFinalizedExecutorBeforeNetwork() {
        val expected = tool(description = "Finalized task reader")
        val transport = TrackingTransport(expected)
        val policyRevision = AtomicLong(7L)
        val fixture = fixture(transport, policies = listOf(expected), policyRevision = policyRevision)
        val executor = fixture.source().snapshot().single()

        policyRevision.incrementAndGet()
        val result = execute(executor)

        assertFalse(result.success)
        assertEquals(
            "mcp_tool_policy_revision_changed",
            JSONObject(result.contentText).getString("errorCode"),
        )
        assertEquals(0, transport.networkCalls.get())
        assertEquals(0, transport.toolCalls.get())
    }

    @Test
    fun atomicSnapshotSamplesPolicyRevisionOnceForTokenAndExecutorGuard() {
        val expected = tool(description = "Finalized task reader")
        val transport = TrackingTransport(expected)
        val revisionReads = AtomicInteger(0)
        val fixture = fixture(transport, policies = listOf(expected))
        val source = RemoteMcpFinalizedDynamicToolSource(
            catalogs = fixture.catalogs,
            activations = fixture.activations,
            backgroundExecutor = InlineExecutor,
            currentPolicyRevision = {
                revisionReads.incrementAndGet()
                41L
            },
        )

        val snapshot = source.snapshotWithPolicyRevision()

        assertEquals(1, revisionReads.get())
        assertEquals("remote-mcp-policy-v1:41", snapshot.revisionToken)
        assertEquals(1, snapshot.executors.size)
    }

    @Test
    fun policyRemovalDuringFreshDiscoveryFailsBeforeToolCall() {
        val expected = tool(description = "Finalized task reader")
        val transport = TrackingTransport(expected)
        val fixture = fixture(transport, policies = emptyList())
        val executor = fixture.source().snapshot().single()

        val result = execute(executor)

        assertFalse(result.success)
        assertEquals(
            "mcp_finalized_policy_changed",
            JSONObject(result.contentText).getString("errorCode"),
        )
        assertEquals(2, transport.networkCalls.get())
        assertEquals(0, transport.toolCalls.get())
    }

    @Test
    fun remoteCatalogMetadataDriftFailsBeforeToolCallEvenWhenNewMetadataHasPolicy() {
        val finalized = tool(description = "Finalized task reader")
        val changed = tool(description = "Changed task reader")
        val transport = TrackingTransport(changed)
        val fixture = fixture(transport, policies = listOf(changed), finalizedTool = finalized)
        val executor = fixture.source().snapshot().single()

        val result = execute(executor)

        assertFalse(result.success)
        assertEquals(
            "mcp_finalized_catalog_changed",
            JSONObject(result.contentText).getString("errorCode"),
        )
        assertEquals(2, transport.networkCalls.get())
        assertEquals(0, transport.toolCalls.get())
    }

    private fun fixture(
        transport: TrackingTransport,
        policies: List<RemoteMcpTool>,
        policyRevision: AtomicLong = AtomicLong(7L),
        finalizedTool: RemoteMcpTool = tool(description = "Finalized task reader"),
    ): Fixture {
        val requirement = RemoteMcpRequirement(
            id = "tasks",
            endpoint = "https://mcp.example.com/v1",
            oauthHandle = null,
            allowedTools = setOf(finalizedTool.name),
            required = true,
            requestTimeoutMillis = 10_000,
            maxResponseBytes = 128 * 1024,
        )
        val definition = RemoteMcpActivationDefinition(
            identity = RemoteMcpActivationIdentity(
                pluginId = "tasks-plugin",
                serverId = requirement.id,
                configurationDigest = RemoteMcpConfigurationIdentity.digest(requirement),
            ),
            requirement = requirement,
        )
        val activations = RemoteMcpSessionRegistry(
            temporaryFolder.newFolder("activation-${nextFolder.incrementAndGet()}"),
        ) { active ->
            RemoteMcpSession(
                definition = active,
                http = transport,
                oauth = NoCredentials,
                toolPolicies = RemoteMcpToolPolicyRegistry(
                    policies.map { candidate ->
                        RemoteMcpToolPolicy.approved(
                            active.identity,
                            candidate,
                            RemoteMcpToolEffect.READ_ONLY,
                        )
                    },
                ),
                passiveStatuses = RemoteMcpPassiveStatusRegistry(),
            )
        }
        val activation = activations.prepare(definition)
        activations.commit(activation)
        activations.finalize(activation)

        val discovery = RemoteMcpDiscoveryReceipt(
            tools = listOf(finalizedTool),
            allowedToolNames = setOf(finalizedTool.name),
        )
        val catalogs = RemoteMcpDiscoveryCatalogStore(
            temporaryFolder.newFolder("catalog-${nextFolder.incrementAndGet()}"),
        )
        val catalog = catalogs.prepare(
            RemoteMcpDiscoveryCatalogDefinition.ready(
                activation = activation,
                identity = definition.identity,
                discovery = discovery,
            ),
        )
        catalogs.commit(catalog)
        catalogs.finalize(catalog)
        return Fixture(catalogs, activations, policyRevision)
    }

    private fun execute(executor: ai.hans.standard.codex.DynamicToolExecutor): DynamicToolExecutionResult {
        val namespace = executor.specs.single()
        val completed = AtomicReference<DynamicToolExecutionResult?>()
        executor.execute(
            DynamicToolCallParams(
                threadId = "thread-1",
                turnId = "turn-1",
                callId = "call-1",
                namespace = namespace.name,
                tool = namespace.tools.single().name,
                argumentsJson = "{}",
            ),
            completed::set,
        )
        return requireNotNull(completed.get())
    }

    private data class Fixture(
        val catalogs: RemoteMcpDiscoveryCatalogStore,
        val activations: RemoteMcpSessionRegistry,
        val policyRevision: AtomicLong,
    ) {
        fun source() = RemoteMcpFinalizedDynamicToolSource(
            catalogs = catalogs,
            activations = activations,
            backgroundExecutor = InlineExecutor,
            currentPolicyRevision = policyRevision::get,
        )
    }

    private class TrackingTransport(
        private val discoveredTool: RemoteMcpTool,
    ) : RemoteMcpHttpCallFactory {
        val networkCalls = AtomicInteger(0)
        val toolCalls = AtomicInteger(0)

        override fun create(
            request: RemoteMcpHttpRequest,
            bearerToken: String?,
        ): RemoteMcpHttpCall = object : RemoteMcpHttpCall {
            override fun execute(): RemoteMcpHttpResponse {
                networkCalls.incrementAndGet()
                val envelope = JSONObject(request.body.toString(Charsets.UTF_8))
                return when (envelope.getString("method")) {
                    "server/discover" -> response(
                        envelope.getLong("id"),
                        JSONObject()
                            .put(
                                "supportedVersions",
                                JSONArray().put(RemoteMcpProtocol.MODERN_PROTOCOL_VERSION),
                            )
                            .put("capabilities", JSONObject().put("tools", JSONObject()))
                            .put("ttlMs", 0)
                            .put("cacheScope", "private")
                            .put("resultType", "complete"),
                    )
                    "initialize" -> response(
                        envelope.getLong("id"),
                        JSONObject()
                            .put("protocolVersion", RemoteMcpProtocol.PROTOCOL_VERSION)
                            .put("capabilities", JSONObject().put("tools", JSONObject()))
                            .put("serverInfo", JSONObject().put("name", "test").put("version", "1")),
                    )
                    "notifications/initialized" ->
                        RemoteMcpHttpResponse(202, "", ByteArray(0), SESSION_ID)
                    "tools/list" -> response(
                        envelope.getLong("id"),
                        JSONObject()
                            .put("tools", JSONArray().put(toolJson(discoveredTool)))
                            .put("nextCursor", JSONObject.NULL)
                            .put("ttlMs", 0)
                            .put("cacheScope", "private")
                            .put("resultType", "complete"),
                    )
                    "tools/call" -> {
                        toolCalls.incrementAndGet()
                        response(
                            envelope.getLong("id"),
                            JSONObject()
                                .put(
                                    "content",
                                    JSONArray().put(
                                        JSONObject().put("type", "text").put("text", "ok"),
                                    ),
                                )
                                .put("isError", false)
                                .put("resultType", "complete"),
                        )
                    }
                    else -> error("unexpected method")
                }
            }

            override fun cancel() = Unit
            override fun close() = Unit
        }

        private fun response(id: Long, result: JSONObject) = RemoteMcpHttpResponse(
            statusCode = 200,
            contentType = "application/json",
            body = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("result", result)
                .toString()
                .toByteArray(),
            sessionId = null,
        )

        private fun toolJson(value: RemoteMcpTool) = JSONObject()
            .put("name", value.name)
            .put("description", value.description)
            .put("inputSchema", JSONObject(value.inputSchemaJson))

        private companion object {
            const val SESSION_ID = "session-1"
        }
    }

    private object NoCredentials : RemoteMcpOAuthVault {
        override fun state(identity: RemoteMcpOAuthCredentialIdentity) =
            OAuthCredentialState.MISSING

        override fun <T> withBearerToken(
            identity: RemoteMcpOAuthCredentialIdentity,
            block: (String) -> T,
        ): T = error("no credential")
    }

    private object InlineExecutor : Executor {
        override fun execute(command: Runnable) = command.run()
    }

    private companion object {
        val nextFolder = AtomicInteger(0)

        fun tool(description: String) = RemoteMcpTool(
            name = "tasks/list",
            title = null,
            description = description,
            inputSchemaJson = """{"type":"object"}""",
            outputSchemaJson = null,
        )
    }
}
