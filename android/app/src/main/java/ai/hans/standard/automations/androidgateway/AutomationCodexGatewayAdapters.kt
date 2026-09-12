package ai.hans.standard.automations.androidgateway

import ai.hans.standard.automations.AutomationCodexGateway
import ai.hans.standard.automations.AutomationHeartbeat
import ai.hans.standard.automations.CodexAutomationExecutionOutcome
import java.io.Closeable
import java.util.concurrent.TimeUnit

/**
 * Atomic view of the interactive Codex session used by thread-bound automations.
 *
 * The host adapter must derive this from the same lock that protects dispatch. In particular,
 * [ExistingThreadPhase.READY] means that the named thread is the current thread and has no active
 * turn. A UI snapshot followed by an unrelated dispatch is not an acceptable implementation.
 */
data class ExistingThreadAutomationSnapshot(
    val currentThreadId: String?,
    val phase: ExistingThreadPhase,
)

enum class ExistingThreadPhase {
    READY,
    BUSY,
    AUTH_REQUIRED,
    STARTING,
    FAILED,
    UNAVAILABLE,
}

sealed interface ExistingThreadDispatchResult {
    /** Opaque correlation allocated by the host; it must identify only this accepted turn. */
    data class Accepted(val correlationId: String) : ExistingThreadDispatchResult {
        init {
            require(correlationId.isNotBlank() && correlationId.length <= 256)
            require(correlationId.none(Char::isISOControl))
        }
    }

    data class Rejected(
        val code: String,
        val retryable: Boolean,
    ) : ExistingThreadDispatchResult {
        init {
            require(code.matches(SAFE_CODE))
        }
    }

    /** The request entered transport, but the caller cannot prove whether Codex accepted it. */
    data class OutcomeAmbiguous(val code: String) : ExistingThreadDispatchResult {
        init {
            require(code.matches(SAFE_CODE))
        }
    }
}

sealed interface ExistingThreadExecutionState {
    data object Pending : ExistingThreadExecutionState
    data object Running : ExistingThreadExecutionState
    data object Succeeded : ExistingThreadExecutionState
    data class Failed(val retryable: Boolean) : ExistingThreadExecutionState
    data object CorrelationLost : ExistingThreadExecutionState
}

/**
 * Narrow host seam. Implementations must atomically re-check the expected thread and READY state
 * inside [dispatchIfReady], then bind [ExistingThreadDispatchResult.Accepted.correlationId] to the
 * resulting turn's authoritative terminal event. Neither method may log [instruction].
 */
interface ExistingThreadAutomationAdapter {
    fun snapshot(): ExistingThreadAutomationSnapshot

    fun dispatchIfReady(
        expectedThreadId: String,
        instruction: String,
        idempotencyKey: String,
    ): ExistingThreadDispatchResult

    /**
     * Waits for this correlation only. It may return early after a state change and must never
     * reinterpret another turn becoming READY as successful completion.
     */
    fun awaitExecutionState(
        correlationId: String,
        maximumWaitMillis: Long,
    ): ExistingThreadExecutionState

    /** Wakes a bounded waiter after JobScheduler stops the owning cycle. */
    fun wakeAwaiter(correlationId: String) = Unit
}

/** Defers host construction until an actual thread-bound automation executes. */
class LazyExistingThreadAutomationAdapter(
    private val provider: () -> ExistingThreadAutomationAdapter,
) : ExistingThreadAutomationAdapter {
    private fun current(): ExistingThreadAutomationAdapter = provider()

    override fun snapshot(): ExistingThreadAutomationSnapshot = current().snapshot()

    override fun dispatchIfReady(
        expectedThreadId: String,
        instruction: String,
        idempotencyKey: String,
    ): ExistingThreadDispatchResult = current().dispatchIfReady(
        expectedThreadId,
        instruction,
        idempotencyKey,
    )

    override fun awaitExecutionState(
        correlationId: String,
        maximumWaitMillis: Long,
    ): ExistingThreadExecutionState = current().awaitExecutionState(
        correlationId,
        maximumWaitMillis,
    )

    override fun wakeAwaiter(correlationId: String) {
        current().wakeAwaiter(correlationId)
    }
}

fun interface MonotonicNanoClock {
    fun nowNanos(): Long

    companion object {
        val SYSTEM = MonotonicNanoClock(System::nanoTime)
    }
}

data class AutomationGatewayTimeouts(
    val threadBoundTimeoutMillis: Long = TimeUnit.MINUTES.toMillis(30),
    val independentTimeoutMillis: Long = TimeUnit.MINUTES.toMillis(30),
    val heartbeatIntervalMillis: Long = TimeUnit.SECONDS.toMillis(15),
    val toolTimeoutMillis: Long = TimeUnit.MINUTES.toMillis(5),
) {
    init {
        require(threadBoundTimeoutMillis > 0)
        require(independentTimeoutMillis > 0)
        require(heartbeatIntervalMillis > 0)
        require(toolTimeoutMillis > 0)
        require(heartbeatIntervalMillis <= threadBoundTimeoutMillis)
        require(heartbeatIntervalMillis <= independentTimeoutMillis)
        require(toolTimeoutMillis <= independentTimeoutMillis)
    }
}

fun interface IndependentAutomationCodexRunner {
    fun execute(
        instruction: String,
        idempotencyKey: String,
        heartbeat: AutomationHeartbeat,
    ): CodexAutomationExecutionOutcome
}

/** Production gateway shared by the scheduler's thread-bound and independent execution modes. */
class AndroidAutomationCodexGateway(
    private val existingThread: ExistingThreadAutomationAdapter,
    private val independent: IndependentAutomationCodexRunner,
    private val timeouts: AutomationGatewayTimeouts = AutomationGatewayTimeouts(),
    private val clock: MonotonicNanoClock = MonotonicNanoClock.SYSTEM,
) : AutomationCodexGateway, Closeable {
    @Volatile
    private var closed = false

    override fun executeInExistingThread(
        threadId: String,
        instruction: String,
        idempotencyKey: String,
        heartbeat: AutomationHeartbeat,
    ): CodexAutomationExecutionOutcome {
        if (closed) return retryable("codex_gateway_closed")
        // A lazy adapter may initialize/bind the process-wide Host while resolving snapshot().
        if (!safeHeartbeat(heartbeat)) return retryable("automation_ownership_lost")
        val initial = runCatching(existingThread::snapshot).getOrElse {
            return retryable("codex_thread_probe_failed")
        }
        if (initial.currentThreadId != threadId) {
            return permanent("codex_thread_unavailable")
        }
        when (initial.phase) {
            ExistingThreadPhase.READY -> Unit
            ExistingThreadPhase.BUSY -> return retryable("codex_thread_busy")
            ExistingThreadPhase.AUTH_REQUIRED -> return retryable("codex_login_required")
            ExistingThreadPhase.STARTING -> return retryable("codex_runtime_starting")
            ExistingThreadPhase.FAILED -> return retryable("codex_runtime_failed")
            ExistingThreadPhase.UNAVAILABLE -> return retryable("codex_runtime_unavailable")
        }
        // Persist the no-redispatch fence before entering the host. dispatchIfReady eventually
        // calls dispatchSerial, so marking after its return leaves a process-death window in which
        // an accepted turn could be repeated after lease recovery.
        if (!safeHeartbeat(heartbeat)) return retryable("automation_ownership_lost")
        if (!safeMarkExternalDispatchStarted(heartbeat)) {
            return retryable("automation_ownership_lost")
        }
        val dispatched = try {
            existingThread.dispatchIfReady(threadId, instruction, idempotencyKey)
        } catch (_: Exception) {
            return permanent("codex_turn_outcome_ambiguous")
        }
        val correlation = when (dispatched) {
            is ExistingThreadDispatchResult.Accepted -> dispatched.correlationId
            is ExistingThreadDispatchResult.Rejected -> {
                if (!safeClearExternalDispatchAfterProvenRejection(heartbeat)) {
                    return permanent("codex_turn_outcome_ambiguous")
                }
                return if (dispatched.retryable) {
                    retryable(dispatched.code)
                } else {
                    permanent(dispatched.code)
                }
            }
            is ExistingThreadDispatchResult.OutcomeAmbiguous ->
                return permanent(dispatched.code)
        }

        val cancellation = heartbeat.onCancellation {
            runCatching { existingThread.wakeAwaiter(correlation) }
        }
        try {
            val deadline = saturatedAdd(
                clock.nowNanos(),
                TimeUnit.MILLISECONDS.toNanos(timeouts.threadBoundTimeoutMillis),
            )
            while (!closed) {
                val remainingMillis = remainingMillis(deadline)
                    ?: return permanent("codex_turn_outcome_ambiguous")
                val waitMillis = heartbeat.boundWaitMillis(
                    minOf(remainingMillis, timeouts.heartbeatIntervalMillis),
                )
                val state = try {
                    existingThread.awaitExecutionState(correlation, waitMillis)
                } catch (_: Exception) {
                    return permanent("codex_turn_outcome_ambiguous")
                }
                if (deadline - clock.nowNanos() <= 0) {
                    return permanent("codex_turn_outcome_ambiguous")
                }
                when (state) {
                    ExistingThreadExecutionState.Succeeded ->
                        return CodexAutomationExecutionOutcome.Succeeded
                    // dispatchIfReady already accepted the turn. Even a retryable App Server
                    // failure is ambiguous with respect to external tool side effects, so the
                    // automation must require explicit review rather than dispatching it again.
                    is ExistingThreadExecutionState.Failed ->
                        return permanent("codex_turn_failed_after_dispatch")
                    ExistingThreadExecutionState.CorrelationLost ->
                        return permanent("codex_turn_correlation_lost_after_dispatch")
                    ExistingThreadExecutionState.Pending,
                    ExistingThreadExecutionState.Running,
                    -> Unit
                }
                if (!safeHeartbeat(heartbeat)) {
                    return permanent("codex_turn_outcome_ambiguous")
                }
            }
            return permanent("codex_turn_outcome_ambiguous")
        } finally {
            runCatching(cancellation::close)
        }
    }

    override fun executeInNewThread(
        instruction: String,
        idempotencyKey: String,
        heartbeat: AutomationHeartbeat,
    ): CodexAutomationExecutionOutcome {
        if (closed) return retryable("codex_gateway_closed")
        return try {
            independent.execute(instruction, idempotencyKey, heartbeat)
        } catch (_: Exception) {
            retryable("codex_independent_failed")
        }
    }

    override fun close() {
        closed = true
        (independent as? Closeable)?.let { runCatching(it::close) }
    }

    private fun remainingMillis(deadline: Long): Long? {
        val remaining = deadline - clock.nowNanos()
        if (remaining <= 0) return null
        return maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining))
    }

    private companion object {
        fun safeHeartbeat(heartbeat: AutomationHeartbeat): Boolean =
            runCatching(heartbeat::beat).getOrDefault(false)

        fun safeMarkExternalDispatchStarted(heartbeat: AutomationHeartbeat): Boolean =
            runCatching(heartbeat::markExternalDispatchStarted).getOrDefault(false)

        fun safeClearExternalDispatchAfterProvenRejection(
            heartbeat: AutomationHeartbeat,
        ): Boolean = runCatching(heartbeat::clearExternalDispatchAfterProvenRejection)
            .getOrDefault(false)

        fun saturatedAdd(left: Long, right: Long): Long =
            if (right > 0 && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

        fun retryable(code: String) = CodexAutomationExecutionOutcome.RetryableFailure(code)

        fun permanent(code: String) = CodexAutomationExecutionOutcome.PermanentFailure(code)
    }
}

private val SAFE_CODE = Regex("[a-z][a-z0-9_]{2,95}")
