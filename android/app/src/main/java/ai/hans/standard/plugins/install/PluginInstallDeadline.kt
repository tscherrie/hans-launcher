package ai.hans.standard.plugins.install

import java.io.Closeable
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal fun interface PluginInstallScheduledCancellation {
    fun cancel(): Boolean
}

/** Injectable scheduler; implementations enqueue and return immediately. */
internal fun interface PluginInstallDeadlineScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): PluginInstallScheduledCancellation
}

internal class ScheduledExecutorPluginInstallDeadlineScheduler(
    private val executor: ScheduledExecutorService,
) : PluginInstallDeadlineScheduler {
    override fun schedule(
        delayMillis: Long,
        action: () -> Unit,
    ): PluginInstallScheduledCancellation {
        require(delayMillis >= 0L) { "Invalid plugin install deadline delay" }
        val future = executor.schedule(Runnable { action() }, delayMillis, TimeUnit.MILLISECONDS)
        return PluginInstallScheduledCancellation { future.cancel(false) }
    }
}

/**
 * Creates one cancellable lease around an absolute monotonic deadline without sleeping or blocking
 * the caller. Cancellation and expiry race through one atomic terminal transition.
 */
internal class PluginInstallDeadline(
    private val scheduler: PluginInstallDeadlineScheduler,
    private val elapsedRealtimeMillis: () -> Long,
) {
    fun schedule(
        operationId: String,
        leaseId: String,
        deadlineAtElapsedRealtimeMillis: Long,
        onExpired: (PluginInstallDeadlineIdentity) -> Unit,
    ): PluginInstallDeadlineLease {
        PluginInstallJournalBounds.requireOpaqueId(operationId, "operation")
        PluginInstallJournalBounds.requireOpaqueId(leaseId, "lease")
        require(deadlineAtElapsedRealtimeMillis >= 0L) { "Invalid plugin install deadline" }
        val now = elapsedRealtimeMillis()
        require(now >= 0L) { "Invalid monotonic clock" }
        val remaining = if (deadlineAtElapsedRealtimeMillis <= now) {
            0L
        } else {
            deadlineAtElapsedRealtimeMillis - now
        }
        require(remaining <= MAX_FUTURE_MILLIS) { "Plugin install deadline is too far away" }
        val identity = PluginInstallDeadlineIdentity(
            operationId = operationId,
            leaseId = leaseId,
            deadlineAtElapsedRealtimeMillis = deadlineAtElapsedRealtimeMillis,
        )
        val lease = PluginInstallDeadlineLease(identity, onExpired)
        val cancellation = scheduler.schedule(remaining, lease::expireFromScheduler)
        lease.attach(cancellation)
        return lease
    }

    internal companion object {
        const val MAX_FUTURE_MILLIS = 30L * 60L * 1_000L
    }
}

internal data class PluginInstallDeadlineIdentity(
    val operationId: String,
    val leaseId: String,
    val deadlineAtElapsedRealtimeMillis: Long,
)

internal class PluginInstallDeadlineLease internal constructor(
    val identity: PluginInstallDeadlineIdentity,
    private val onExpired: (PluginInstallDeadlineIdentity) -> Unit,
) : Closeable {
    private val state = AtomicInteger(ACTIVE)
    private val scheduled = AtomicReference<PluginInstallScheduledCancellation?>()

    val isExpired: Boolean
        get() = state.get() == EXPIRED

    val isCancelled: Boolean
        get() = state.get() == CANCELLED

    /** Returns true only to the caller that won the active-to-cancelled transition. */
    fun cancel(): Boolean {
        if (!state.compareAndSet(ACTIVE, CANCELLED)) return false
        scheduled.get()?.cancel()
        return true
    }

    override fun close() {
        cancel()
    }

    internal fun attach(cancellation: PluginInstallScheduledCancellation) {
        check(scheduled.compareAndSet(null, cancellation)) { "Plugin deadline task already attached" }
        if (state.get() != ACTIVE) cancellation.cancel()
    }

    internal fun expireFromScheduler() {
        if (!state.compareAndSet(ACTIVE, EXPIRED)) return
        onExpired(identity)
    }

    private companion object {
        const val ACTIVE = 0
        const val CANCELLED = 1
        const val EXPIRED = 2
    }
}
