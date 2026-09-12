package ai.hans.standard.codex

/**
 * Keeps a sensitive dynamic-tool namespace visible while refusing calls from
 * a disallowed turn before the delegate can observe arguments or perform work.
 */
class GatedDynamicToolExecutor(
    private val delegate: DynamicToolExecutor,
    private val denialCode: String,
    private val isAllowed: (DynamicToolCallParams) -> Boolean,
) : DynamicToolExecutor {
    override val specs: List<DynamicToolNamespaceSpec> = delegate.specs

    override fun execute(
        call: DynamicToolCallParams,
        completion: (DynamicToolExecutionResult) -> Unit,
    ) {
        val allowed = runCatching { isAllowed(call) }.getOrDefault(false)
        if (!allowed) {
            runCatching { completion(delegate.failureResult(call, denialCode)) }
            return
        }
        delegate.execute(call, completion)
    }

    override fun executeCancellable(
        call: DynamicToolCallParams,
        cancellation: DynamicToolCancellation,
        completion: (DynamicToolExecutionResult) -> Unit,
    ): DynamicToolExecutionHandle {
        val gate = DynamicToolExecutionGate(cancellation, completion)
        if (gate.isCancellationRequested()) return gate
        val allowed = runCatching { isAllowed(call) }.getOrDefault(false)
        if (!allowed) {
            gate.complete(delegate.failureResult(call, denialCode))
            return gate
        }
        return delegate.executeCancellable(call, cancellation, completion)
    }

    override fun failureResult(
        call: DynamicToolCallParams,
        code: String,
    ): DynamicToolExecutionResult = delegate.failureResult(call, code)
}
