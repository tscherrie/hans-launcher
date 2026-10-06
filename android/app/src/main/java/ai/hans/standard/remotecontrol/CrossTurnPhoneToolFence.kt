package ai.hans.standard.remotecontrol

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolCancellationDisposition
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import org.json.JSONObject

/**
 * Host-owned single-flight boundary shared by every local/remote revision lease. It has no queue:
 * a second real call is rejected until the first is physically finished. Between calls the model
 * owns nothing. Switching thread/turn discards retained UI authority before any next tool runs,
 * including public API tools which can change the app or composer without Accessibility.
 */
internal class CrossTurnPhoneToolFence(
    private val invalidateRetainedUi: () -> Unit,
    private val onQuiescent: () -> Unit,
) {
    constructor(invalidateRetainedUi: () -> Unit) : this(invalidateRetainedUi, {})
    private val monitor = Any()
    private var active: Invocation? = null
    private var lastOwner: Owner? = null

    val hasActiveWork: Boolean get() = synchronized(monitor) { active != null }

    fun wrap(delegate: DynamicToolExecutor): DynamicToolExecutor = object : DynamicToolExecutor {
        override val specs: List<DynamicToolNamespaceSpec> get() = delegate.specs

        override fun execute(call: DynamicToolCallParams, completion: (DynamicToolExecutionResult) -> Unit) {
            executeCancellable(call, DynamicToolCancellation.NONE, completion)
        }

        override fun executeCancellable(call: DynamicToolCallParams, cancellation: DynamicToolCancellation,
            completion: (DynamicToolExecutionResult) -> Unit): DynamicToolExecutionHandle =
            dispatch(delegate, call, cancellation, completion)

        override fun failureResult(call: DynamicToolCallParams, code: String) = delegate.failureResult(call, code)
    }

    private fun dispatch(delegate: DynamicToolExecutor, call: DynamicToolCallParams,
        cancellation: DynamicToolCancellation, completion: (DynamicToolExecutionResult) -> Unit): DynamicToolExecutionHandle {
        if (cancelled(cancellation)) {
            deliver(completion, failure("phone_tool_cancelled"))
            return NO_EFFECT
        }
        val invocation = synchronized(monitor) {
            if (active != null) null else Invocation(Owner(call.threadId, call.turnId), cancellation, completion)
                .also { active = it }
        }
        if (invocation == null) {
            deliver(completion, failure("phone_tools_busy"))
            return NO_EFFECT
        }
        val handle = object : DynamicToolExecutionHandle {
            override fun cancel(): DynamicToolCancellationDisposition = cancel(invocation)
            override fun onQuiescent(listener: () -> Unit): Boolean = observeQuiescence(invocation, listener)
        }
        val changeOwner = synchronized(monitor) { lastOwner != invocation.owner }
        try {
            if (changeOwner) invalidateRetainedUi()
        } catch (_: Exception) {
            synchronized(monitor) { lastOwner = null }
            complete(invocation, failure("phone_ui_invalidation_failed"))
            quiescent(invocation)
            return handle
        }
        val mayEnter = synchronized(monitor) {
            lastOwner = invocation.owner
            if (invocation.cancelRequested) false else {
                invocation.entered = true
                true
            }
        }
        if (!mayEnter || cancelled(cancellation)) {
            complete(invocation, failure("phone_tool_cancelled"))
            quiescent(invocation)
            return handle
        }
        try {
            val actual = delegate.executeCancellable(call, DynamicToolCancellation {
                cancelled(cancellation) || synchronized(monitor) { invocation.cancelRequested }
            }) { complete(invocation, it) }
            synchronized(monitor) {
                invocation.handle = actual
                invocation.dispatchReturned = true
            }
            // The outer returned handle is the only physical receipt. Inner gate completions
            // cannot release this slot before outer post-observation/retention work has ended.
            val supported = runCatching { actual.onQuiescent { quiescent(invocation) } }.getOrDefault(false)
            synchronized(monitor) {
                invocation.quiescenceRegistrationDone = true
                invocation.supportsQuiescence = supported
            }
            releaseAfterLegacyCompletion(invocation)
            if (synchronized(monitor) { invocation.cancelRequested } || cancelled(cancellation)) cancel(invocation)
        } catch (_: Exception) {
            synchronized(monitor) {
                invocation.dispatchReturned = true
                invocation.quiescenceRegistrationDone = true
            }
            val alreadyCompleted = synchronized(monitor) { invocation.callbackFinished }
            if (alreadyCompleted) quiescent(invocation) else {
                // A throwing executor may already have started work. No invented completion
                // receipt and no automatic retry: retain the slot until its actual callback.
                val shouldReport = synchronized(monitor) {
                    if (invocation.resultReported) false else { invocation.resultReported = true; true }
                }
                if (shouldReport) deliver(completion, failure("phone_tool_execution_result_unknown"))
            }
        }
        return handle
    }

    private fun complete(invocation: Invocation, result: DynamicToolExecutionResult) {
        val parentCancelled = cancelled(invocation.parentCancellation)
        val report = synchronized(monitor) {
            if (invocation.callbackStarted) return
            invocation.callbackStarted = true
            if (invocation.resultReported || invocation.cancelRequested || parentCancelled) false else {
                invocation.resultReported = true
                true
            }
        }
        try {
            if (report) deliver(invocation.completion, result)
        } finally {
            synchronized(monitor) { invocation.callbackFinished = true }
            releaseAfterLegacyCompletion(invocation)
        }
    }

    private fun releaseAfterLegacyCompletion(invocation: Invocation) {
        val proven = synchronized(monitor) {
            invocation.dispatchReturned && invocation.quiescenceRegistrationDone &&
                !invocation.supportsQuiescence && invocation.callbackFinished
        }
        if (proven) quiescent(invocation)
    }

    private fun cancel(invocation: Invocation): DynamicToolCancellationDisposition {
        val (actual, disposition) = synchronized(monitor) {
            invocation.cancelRequested = true
            val result = invocation.cancelDisposition ?: if (invocation.entered) {
                DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED
            } else DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT
            invocation.cancelDisposition = result
            val target = invocation.handle.takeUnless { invocation.cancelForwarded }
            if (target != null) invocation.cancelForwarded = true
            target to result
        }
        if (actual != null) {
            val provenBeforeEffect = runCatching { actual.cancel() }.getOrNull() ==
                DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT
            val lacksPhysicalReceipt = synchronized(monitor) {
                invocation.quiescenceRegistrationDone && !invocation.supportsQuiescence
            }
            if (provenBeforeEffect && lacksPhysicalReceipt) quiescent(invocation)
        }
        // Before dispatch, the setup path releases only after invalidation itself has returned.
        // MAY_HAVE_STARTED never releases here; only an outer physical receipt may do so.
        return disposition
    }

    private fun quiescent(invocation: Invocation) {
        val (listeners, becameInactive) = synchronized(monitor) {
            if (invocation.quiescent) return
            invocation.quiescent = true
            val clearedActive = active === invocation
            if (clearedActive) active = null
            invocation.listeners.toList().also { invocation.listeners.clear() } to clearedActive
        }
        listeners.forEach { runCatching(it) }
        if (becameInactive) runCatching(onQuiescent)
    }

    private fun observeQuiescence(invocation: Invocation, listener: () -> Unit): Boolean {
        val invokeNow = synchronized(monitor) {
            if (invocation.quiescent) true else {
                if (invocation.listeners.size >= 8) return false
                invocation.listeners += listener
                false
            }
        }
        if (invokeNow) runCatching(listener)
        return true
    }

    private data class Owner(val threadId: String, val turnId: String)
    private class Invocation(val owner: Owner, val parentCancellation: DynamicToolCancellation,
        val completion: (DynamicToolExecutionResult) -> Unit) {
        var entered = false
        var dispatchReturned = false
        var callbackStarted = false
        var callbackFinished = false
        var resultReported = false
        var cancelRequested = false
        var cancelForwarded = false
        var cancelDisposition: DynamicToolCancellationDisposition? = null
        var handle: DynamicToolExecutionHandle? = null
        var quiescenceRegistrationDone = false
        var supportsQuiescence = false
        var quiescent = false
        val listeners = mutableListOf<() -> Unit>()
    }

    private companion object {
        val NO_EFFECT = object : DynamicToolExecutionHandle {
            override fun cancel() = DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT
            override fun onQuiescent(listener: () -> Unit): Boolean { runCatching(listener); return true }
        }
        fun cancelled(signal: DynamicToolCancellation) = runCatching(signal::isCancellationRequested).getOrDefault(true)
        fun deliver(completion: (DynamicToolExecutionResult) -> Unit, result: DynamicToolExecutionResult) {
            runCatching { completion(result) }
        }
        fun failure(code: String) = DynamicToolExecutionResult(JSONObject().put("status", "failed")
            .put("errorCode", code).put("message", when (code) {
                "phone_tools_busy" -> "Another phone tool is still finishing. No action was queued."
                "phone_tool_execution_result_unknown" -> "The previous action may still be running; do not repeat it."
                else -> "Phone tool was not executed."
            }).toString(), false)
    }
}
