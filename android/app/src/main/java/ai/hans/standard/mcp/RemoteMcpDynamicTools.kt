package ai.hans.standard.mcp

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolCancellationDisposition
import ai.hans.standard.codex.DynamicToolExecutionGate
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolFunctionSpec
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import ai.hans.standard.codex.MAX_DYNAMIC_TOOL_ARGUMENT_BYTES
import ai.hans.standard.codex.MAX_DYNAMIC_TOOL_OUTPUT_TEXT_BYTES
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executor
import org.json.JSONObject

internal data class RemoteMcpDynamicToolBinding(
    val alias: String,
    val remoteName: String,
)

/**
 * App Server dynamic-tool surface for one already discovered Remote MCP session. Construction is
 * impossible until tools/list proof exists and every exposed tool has a trusted effect policy.
 */
internal class RemoteMcpDynamicToolExecutor(
    private val definition: RemoteMcpActivationDefinition,
    private val session: RemoteMcpSession,
    discovery: RemoteMcpDiscoveryReceipt,
    private val backgroundExecutor: Executor,
) : DynamicToolExecutor {
    private val identity = definition.identity
    private val requirement = definition.requirement
    private val namespaceName = namespaceFor(identity)
    private val bindings = createBindings(
        discovery.tools.filter { it.name in discovery.allowedToolNames },
    )
    private val toolsByRemote = discovery.tools.associateBy(RemoteMcpTool::name)
    private val remoteByAlias = bindings.associate { it.alias to it.remoteName }

    init {
        require(session.activationIdentity == identity) { "Remote MCP executor identity changed" }
        require(bindings.isNotEmpty()) { "Remote MCP has no proven, allowed tools" }
        require(bindings.size <= 64) { "Remote MCP dynamic namespace is too large" }
    }

    override val specs: List<DynamicToolNamespaceSpec> = listOf(
        DynamicToolNamespaceSpec(
            name = namespaceName,
            description = "Verified HTTPS Remote MCP tools for ${requirement.id}.",
            tools = bindings.map { binding ->
                val remote = requireNotNull(toolsByRemote[binding.remoteName])
                DynamicToolFunctionSpec(
                    name = binding.alias,
                    description = remote.description?.take(4_000)
                        ?: "Call the verified remote MCP tool ${remote.name}.",
                    inputSchemaJson = remote.inputSchemaJson,
                )
            },
        ),
    )

    override fun execute(
        call: DynamicToolCallParams,
        completion: (DynamicToolExecutionResult) -> Unit,
    ) {
        executeCancellable(call, DynamicToolCancellation.NONE, completion)
    }

    override fun executeCancellable(
        call: DynamicToolCallParams,
        cancellation: DynamicToolCancellation,
        completion: (DynamicToolExecutionResult) -> Unit,
    ): DynamicToolExecutionHandle {
        val gate = DynamicToolExecutionGate(cancellation, completion)
        val invocation = session.beginInvocation()
        val scheduled = gate.schedule(backgroundExecutor) {
            val result = runCatching { executeSafely(call, gate, invocation) }
                .getOrElse { failureResult(call, it.safeCode()) }
            gate.complete(result)
        }
        if (!scheduled) {
            session.cancel(invocation)
            gate.complete(failureResult(call, "mcp_executor_rejected"))
        }
        return object : DynamicToolExecutionHandle {
            override fun cancel(): DynamicToolCancellationDisposition {
                session.cancel(invocation)
                return gate.cancel()
            }
        }
    }

    override fun failureResult(
        call: DynamicToolCallParams,
        code: String,
    ): DynamicToolExecutionResult = DynamicToolExecutionResult(
        JSONObject()
            .put("status", "failed")
            .put("errorCode", code.takeIf(SAFE_CODE::matches) ?: "mcp_tool_failed")
            .toString(),
        success = false,
    )

    private fun executeSafely(
        call: DynamicToolCallParams,
        gate: DynamicToolExecutionGate,
        invocation: RemoteMcpInvocation,
    ): DynamicToolExecutionResult {
        require(call.namespace == namespaceName) { "mcp_namespace_unknown" }
        val remoteName = remoteByAlias[call.tool] ?: error("mcp_tool_unknown")
        require(call.argumentsJson.toByteArray(StandardCharsets.UTF_8).size <= MAX_DYNAMIC_TOOL_ARGUMENT_BYTES)
        JSONObject(call.argumentsJson)
        if (!gate.markExternalEffectStarted()) return failureResult(call, "mcp_call_cancelled")
        val receipt = session.call(
            invocation,
            remoteName,
            call.argumentsJson,
            RemoteMcpCancellation(gate::isCancellationRequested),
        )
        if (!receipt.success) {
            return failureResult(call, receipt.errorCode ?: "mcp_tool_failed")
        }
        val result = JSONObject()
            .put("status", "ok")
            .put("postconditionVerified", true)
            .put("remoteResult", JSONObject(requireNotNull(receipt.resultJson)))
            .toString()
        require(result.toByteArray(StandardCharsets.UTF_8).size <= MAX_DYNAMIC_TOOL_OUTPUT_TEXT_BYTES) {
            "mcp_dynamic_output_too_large"
        }
        return DynamicToolExecutionResult(result, success = true)
    }

    internal fun bindingSnapshot(): List<RemoteMcpDynamicToolBinding> = bindings.toList()

    private fun Throwable.safeCode(): String = when (this) {
        is RemoteMcpFailure -> code
        else -> message?.takeIf(SAFE_CODE::matches) ?: "mcp_tool_failed"
    }

    internal companion object {
        val SAFE_DYNAMIC_NAME = Regex("[A-Za-z0-9_-]{1,128}")
        val SAFE_CODE = Regex("[a-z0-9_.:-]{1,96}")

        fun namespaceFor(identity: RemoteMcpActivationIdentity): String {
            val logicalPrefix = "${identity.pluginId}_${identity.serverId}"
                .replace(Regex("[^A-Za-z0-9_-]"), "_")
                .take(36)
            val exactIdentity = listOf(
                identity.pluginId,
                identity.serverId,
                identity.configurationDigest,
            ).joinToString("\u0000")
            return "hans_mcp_${logicalPrefix}_${digest(exactIdentity).take(12)}".take(64)
        }

        fun createBindings(tools: List<RemoteMcpTool>): List<RemoteMcpDynamicToolBinding> {
            val aliases = linkedSetOf<String>()
            return tools.sortedBy(RemoteMcpTool::name).map { tool ->
                val natural = tool.name.takeIf(SAFE_DYNAMIC_NAME::matches)
                var alias = natural ?: "mcp_${digest(tool.name).take(24)}"
                if (!aliases.add(alias)) {
                    alias = "mcp_${digest("alias:${tool.name}").take(32)}"
                    require(aliases.add(alias)) { "MCP dynamic tool alias collision" }
                }
                RemoteMcpDynamicToolBinding(alias, tool.name)
            }
        }

        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
