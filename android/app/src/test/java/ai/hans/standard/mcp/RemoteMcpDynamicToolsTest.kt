package ai.hans.standard.mcp

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.plugins.runtime.RemoteMcpRequirement
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMcpDynamicToolsTest {
    @Test
    fun remoteNamesReceiveStableSafeAliasesAndOnlyProvenToolsAreExposed() {
        val transport = QueueTransport(
            ArrayDeque(
                listOf(
                    response(
                        1,
                        JSONObject()
                            .put("supportedVersions", JSONArray().put(RemoteMcpProtocol.MODERN_PROTOCOL_VERSION))
                            .put("capabilities", JSONObject().put("tools", JSONObject()))
                            .put("ttlMs", 0)
                            .put("cacheScope", "private")
                            .put("resultType", "complete"),
                    ),
                    response(
                        2,
                        JSONObject()
                            .put(
                                "tools",
                                JSONArray()
                                    .put(tool("tasks/list"))
                                    .put(tool("not-allowed")),
                            )
                            .put("nextCursor", JSONObject.NULL)
                            .put("ttlMs", 0)
                            .put("cacheScope", "private")
                            .put("resultType", "complete"),
                    ),
                    response(
                        3,
                        JSONObject()
                            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "ok")))
                            .put("isError", false)
                            .put("resultType", "complete"),
                    ),
                ),
            ),
        )
        val requirement = RemoteMcpRequirement(
            "tasks",
            "https://example.com/mcp",
            null,
            setOf("tasks/list"),
            true,
            10_000,
            128 * 1024,
        )
        val definition = RemoteMcpActivationDefinition(
            RemoteMcpActivationIdentity(
                "tasks-plugin",
                requirement.id,
                RemoteMcpConfigurationIdentity.digest(requirement),
            ),
            requirement,
        )
        val policies = RemoteMcpToolPolicyRegistry(
            listOf(
                RemoteMcpToolPolicy.approved(
                    definition.identity,
                    discoveredTool("tasks/list"),
                    RemoteMcpToolEffect.READ_ONLY,
                ),
            ),
        )
        val session = RemoteMcpSession(
            definition,
            transport,
            NoVault,
            policies,
            RemoteMcpPassiveStatusRegistry(),
        )
        val discovery = session.discover()
        val executor = RemoteMcpDynamicToolExecutor(definition, session, discovery, ExecutorInline)
        val binding = executor.bindingSnapshot().single()
        assertTrue(binding.alias.matches(Regex("[A-Za-z0-9_-]+")))
        assertEquals("tasks/list", binding.remoteName)
        assertEquals(1, executor.specs.single().tools.size)

        val result = AtomicReference<ai.hans.standard.codex.DynamicToolExecutionResult?>()
        executor.execute(
            DynamicToolCallParams(
                "thread-1",
                "turn-1",
                "call-1",
                executor.specs.single().name,
                binding.alias,
                "{}",
            ),
            result::set,
        )
        val completed = requireNotNull(result.get())
        assertNotNull(completed)
        assertTrue(completed.success)
        assertTrue(JSONObject(completed.contentText).getBoolean("postconditionVerified"))
    }

    @Test
    fun sameServerIdInDifferentPluginsGetsDifferentNamespaces() {
        val requirement = RemoteMcpRequirement(
            "tasks",
            "https://example.com/mcp",
            null,
            setOf("tasks/list"),
            true,
            10_000,
            128 * 1024,
        )
        val digest = RemoteMcpConfigurationIdentity.digest(requirement)
        val first = RemoteMcpActivationIdentity("first-plugin", "tasks", digest)
        val second = RemoteMcpActivationIdentity("second-plugin", "tasks", digest)
        val stale = first.copy(configurationDigest = "a".repeat(64))

        assertTrue(RemoteMcpDynamicToolExecutor.namespaceFor(first) !=
            RemoteMcpDynamicToolExecutor.namespaceFor(second))
        assertTrue(RemoteMcpDynamicToolExecutor.namespaceFor(first) !=
            RemoteMcpDynamicToolExecutor.namespaceFor(stale))
    }

    private fun tool(name: String) = JSONObject()
        .put("name", name)
        .put("description", "Tool")
        .put("inputSchema", JSONObject().put("type", "object"))

    private fun discoveredTool(name: String) = RemoteMcpTool(
        name = name,
        title = null,
        description = "Tool",
        inputSchemaJson = """{"type":"object"}""",
        outputSchemaJson = null,
    )

    private fun response(id: Long, result: JSONObject) = RemoteMcpHttpResponse(
        200,
        "application/json",
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
            .toString().toByteArray(),
        null,
    )

    private class QueueTransport(
        private val responses: ArrayDeque<RemoteMcpHttpResponse>,
    ) : RemoteMcpHttpCallFactory {
        override fun create(request: RemoteMcpHttpRequest, bearerToken: String?) =
            object : RemoteMcpHttpCall {
                override fun execute() = responses.removeFirst()
                override fun cancel() = Unit
                override fun close() = Unit
            }
    }

    private object NoVault : RemoteMcpOAuthVault {
        override fun state(identity: RemoteMcpOAuthCredentialIdentity) =
            OAuthCredentialState.MISSING

        override fun <T> withBearerToken(
            identity: RemoteMcpOAuthCredentialIdentity,
            block: (String) -> T,
        ): T = error("no credential")
    }

    private object ExecutorInline : java.util.concurrent.Executor {
        override fun execute(command: Runnable) = command.run()
    }
}
