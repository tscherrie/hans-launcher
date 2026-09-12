package ai.hans.standard.codex

import org.json.JSONObject

/** Routes disjoint App Server namespaces without coupling their privilege domains. */
class CompositeDynamicToolExecutor(
    private val delegates: List<DynamicToolExecutor>,
) : DynamicToolExecutor {
    private val byNamespace: Map<String, DynamicToolExecutor>

    init {
        require(delegates.isNotEmpty())
        val pairs = delegates.flatMap { delegate ->
            delegate.specs.map { spec -> spec.name to delegate }
        }
        require(pairs.map { it.first }.toSet().size == pairs.size) {
            "Duplicate dynamic tool namespace"
        }
        byNamespace = pairs.toMap()
    }

    override val specs: List<DynamicToolNamespaceSpec> = delegates.flatMap { it.specs }

    override fun execute(
        call: DynamicToolCallParams,
        completion: (DynamicToolExecutionResult) -> Unit,
    ) {
        val delegate = call.namespace?.let(byNamespace::get)
        if (delegate == null) {
            completion(failureResult(call, "unknown_dynamic_tool_namespace"))
            return
        }
        delegate.execute(call, completion)
    }

    override fun executeCancellable(
        call: DynamicToolCallParams,
        cancellation: DynamicToolCancellation,
        completion: (DynamicToolExecutionResult) -> Unit,
    ): DynamicToolExecutionHandle {
        val delegate = call.namespace?.let(byNamespace::get)
        if (delegate == null) {
            val gate = DynamicToolExecutionGate(cancellation, completion)
            gate.complete(failureResult(call, "unknown_dynamic_tool_namespace"))
            return gate
        }
        return delegate.executeCancellable(call, cancellation, completion)
    }

    override fun failureResult(
        call: DynamicToolCallParams,
        code: String,
    ): DynamicToolExecutionResult = call.namespace?.let(byNamespace::get)
        ?.failureResult(call, code)
        ?: DynamicToolExecutionResult(
            contentText = JSONObject()
                .put("status", "failed")
                .put("errorCode", code.takeIf { it.matches(SAFE_CODE) } ?: "dynamic_tool_failed")
                .toString(),
            success = false,
        )

    private companion object {
        val SAFE_CODE = Regex("[a-z0-9_.:-]{1,96}")
    }
}
