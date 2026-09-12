package ai.hans.standard.mcp

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolCancellationDisposition
import ai.hans.standard.codex.DynamicToolExecutionGate
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import ai.hans.standard.codex.MAX_DYNAMIC_TOOL_ARGUMENT_BYTES
import ai.hans.standard.codex.MAX_DYNAMIC_TOOL_OUTPUT_TEXT_BYTES
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executor
import org.json.JSONObject

/**
 * Reconstructs callable Remote MCP executors exclusively from two matching FINALIZED stores.
 * Catalog reads and session construction perform no discovery request. When a Codex call reaches
 * an exposed tool, the exact execution session must freshly rediscover and prove the finalized
 * catalog before that one tool call may cross its effect boundary.
 */
internal class RemoteMcpFinalizedDynamicToolSource(
    private val catalogs: RemoteMcpDiscoveryCatalogStore,
    private val activations: RemoteMcpSessionRegistry,
    private val backgroundExecutor: Executor,
    private val currentPolicyRevision: () -> Long = { STATIC_POLICY_REVISION },
) {
    fun snapshot(): List<DynamicToolExecutor> = snapshotWithPolicyRevision().executors

    /**
     * Samples the durable policy revision exactly once, then binds both the advertised router
     * token and every executor guard to that same value. Keeping these values in one immutable
     * result prevents a policy publication racing between two otherwise independent reads.
     */
    fun snapshotWithPolicyRevision(): RemoteMcpFinalizedDynamicToolSnapshot {
        val policyRevision = policyRevision()
        val executors = catalogs.finalizedCatalogs()
            .filter(RemoteMcpFinalizedDiscoveryCatalog::routeEligible)
            .mapNotNull { catalog ->
                runCatching {
                    val definition = activations.finalizedDefinition(catalog.identity)
                    check(definition.identity == catalog.identity) {
                        "Remote MCP finalized identity changed"
                    }
                    val session = activations.finalizedSession(catalog.identity)
                    val delegate = RemoteMcpDynamicToolExecutor(
                        definition = definition,
                        session = session,
                        discovery = catalog.discovery,
                        backgroundExecutor = backgroundExecutor,
                    )
                    FinalizedDiscoveryGuardExecutor(
                        expectedDefinition = definition,
                        expectedCatalog = catalog,
                        expectedPolicyRevision = policyRevision,
                        catalogs = catalogs,
                        activations = activations,
                        session = session,
                        delegate = delegate,
                        backgroundExecutor = backgroundExecutor,
                        currentPolicyRevision = currentPolicyRevision,
                    )
                }.getOrNull()
            }
        return RemoteMcpFinalizedDynamicToolSnapshot(
            revisionToken = policyRevisionToken(policyRevision),
            executors = executors,
        )
    }

    /**
     * Private router-revision seam. A policy publication can use it to force a new immutable App
     * Server tool snapshot even when the visible tool schemas did not change.
     */
    fun policyRevisionToken(): String = policyRevisionToken(policyRevision())

    private fun policyRevisionToken(revision: Long): String = "remote-mcp-policy-v1:$revision"

    private fun policyRevision(): Long = runCatching(currentPolicyRevision)
        .getOrElse { throw RemoteMcpFailure("mcp_tool_policy_store_unavailable") }
        .also { revision ->
            if (revision < 0L) throw RemoteMcpFailure("mcp_tool_policy_revision_invalid")
        }

    private companion object {
        const val STATIC_POLICY_REVISION = 0L
    }
}

internal data class RemoteMcpFinalizedDynamicToolSnapshot(
    val revisionToken: String,
    val executors: List<DynamicToolExecutor>,
)

/**
 * Keeps passive contract reconstruction network-free while making execution fail closed. The
 * stored catalog is sufficient to advertise the immutable App Server schema, but never sufficient
 * to call a remote tool: the same session used by the delegate first performs bounded discovery.
 */
private class FinalizedDiscoveryGuardExecutor(
    private val expectedDefinition: RemoteMcpActivationDefinition,
    private val expectedCatalog: RemoteMcpFinalizedDiscoveryCatalog,
    private val expectedPolicyRevision: Long,
    private val catalogs: RemoteMcpDiscoveryCatalogStore,
    private val activations: RemoteMcpSessionRegistry,
    private val session: RemoteMcpSession,
    private val delegate: RemoteMcpDynamicToolExecutor,
    private val backgroundExecutor: Executor,
    private val currentPolicyRevision: () -> Long,
) : DynamicToolExecutor {
    override val specs: List<DynamicToolNamespaceSpec> = delegate.specs
    private val remoteByAlias = delegate.bindingSnapshot().associate { it.alias to it.remoteName }
    private val executionLock = Any()

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
            val result = runCatching { executeExactlyOnce(call, gate, invocation) }
                .getOrElse { failureResult(call, it.safeMcpCode()) }
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
    ): DynamicToolExecutionResult = delegate.failureResult(call, code)

    /** Serializes discovery proof and tools/call so another rediscovery cannot replace it. */
    private fun executeExactlyOnce(
        call: DynamicToolCallParams,
        gate: DynamicToolExecutionGate,
        invocation: RemoteMcpInvocation,
    ): DynamicToolExecutionResult = synchronized(executionLock) {
        require(call.namespace == specs.single().name) { "mcp_namespace_unknown" }
        val remoteName = remoteByAlias[call.tool] ?: error("mcp_tool_unknown")
        require(call.argumentsJson.toByteArray(StandardCharsets.UTF_8).size <=
            MAX_DYNAMIC_TOOL_ARGUMENT_BYTES)
        JSONObject(call.argumentsJson)
        if (!gate.markExternalEffectStarted()) {
            return@synchronized failureResult(call, "mcp_call_cancelled")
        }
        requireExactFinalizedState()
        val fresh = session.discover(
            invocation,
            RemoteMcpCancellation(gate::isCancellationRequested),
        )
        requireExactDiscovery(fresh)
        // Close the discovery network race: uninstall, replacement or policy revocation between
        // tools/list and routing must fail before the one and only tools/call request.
        requireExactFinalizedState()
        val receipt = session.call(
            invocation,
            remoteName,
            call.argumentsJson,
            RemoteMcpCancellation(gate::isCancellationRequested),
        )
        if (!receipt.success) {
            return@synchronized failureResult(
                call,
                receipt.errorCode ?: "mcp_tool_failed",
            )
        }
        val result = JSONObject()
            .put("status", "ok")
            .put("postconditionVerified", true)
            .put("remoteResult", JSONObject(requireNotNull(receipt.resultJson)))
            .toString()
        require(result.toByteArray(StandardCharsets.UTF_8).size <=
            MAX_DYNAMIC_TOOL_OUTPUT_TEXT_BYTES) {
            "mcp_dynamic_output_too_large"
        }
        DynamicToolExecutionResult(result, success = true)
    }

    private fun requireExactFinalizedState() {
        val currentDefinition = activations.finalizedDefinition(expectedCatalog.identity)
        if (currentDefinition.identity != expectedDefinition.identity ||
            currentDefinition.metadataDigest != expectedDefinition.metadataDigest
        ) {
            throw RemoteMcpFailure("mcp_finalized_activation_changed")
        }
        val currentCatalog = catalogs.finalizedCatalog(expectedCatalog.identity)
        if (currentCatalog.identity != expectedCatalog.identity ||
            currentCatalog.routeState != RemoteMcpCatalogRouteState.READY ||
            currentCatalog.catalogDigest != expectedCatalog.catalogDigest ||
            currentCatalog.metadataDigest != expectedCatalog.metadataDigest ||
            currentCatalog.discovery != expectedCatalog.discovery
        ) {
            throw RemoteMcpFailure("mcp_finalized_catalog_changed")
        }
        val revision = runCatching(currentPolicyRevision)
            .getOrElse { throw RemoteMcpFailure("mcp_tool_policy_store_unavailable") }
        if (revision != expectedPolicyRevision) {
            throw RemoteMcpFailure("mcp_tool_policy_revision_changed")
        }
    }

    private fun requireExactDiscovery(fresh: RemoteMcpDiscoveryReceipt) {
        val expectedNames = expectedCatalog.discovery.allowedToolNames
        if (fresh.allowedToolNames != expectedNames) {
            throw RemoteMcpFailure("mcp_finalized_policy_changed")
        }
        val freshByName = fresh.tools.associateBy(RemoteMcpTool::name)
        if (freshByName.size != fresh.tools.size) {
            throw RemoteMcpFailure("mcp_finalized_catalog_changed")
        }
        val freshlyAllowed = expectedNames.map { name ->
            freshByName[name] ?: throw RemoteMcpFailure("mcp_finalized_catalog_changed")
        }.sortedBy(RemoteMcpTool::name)
        val finalizedAllowed = expectedCatalog.discovery.tools.sortedBy(RemoteMcpTool::name)
        if (freshlyAllowed != finalizedAllowed) {
            throw RemoteMcpFailure("mcp_finalized_catalog_changed")
        }
    }

    private fun Throwable.safeMcpCode(): String = when (this) {
        is RemoteMcpFailure -> code
        else -> message?.takeIf(RemoteMcpDynamicToolExecutor.SAFE_CODE::matches)
            ?: "mcp_finalized_discovery_failed"
    }
}
