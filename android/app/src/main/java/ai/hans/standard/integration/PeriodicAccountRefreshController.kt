package ai.hans.standard.integration

import android.os.Handler
import java.io.Closeable

fun interface AccountRefreshAction {
    /** Starts or coalesces an authoritative App Server account/read refresh. */
    fun refreshAccount(): Boolean
}

fun interface ScheduledAccountRefresh : Closeable {
    override fun close()
}

fun interface AccountRefreshScheduler {
    fun schedule(delayMillis: Long, task: () -> Unit): ScheduledAccountRefresh
}

/**
 * Lifecycle-scoped account refresh without a polling thread or visible timer.
 *
 * The first check is posted immediately when the launcher becomes active. Every attempt schedules
 * exactly one later check, including transport rejection, so a temporarily offline Runtime cannot
 * produce a busy retry loop. Stopping invalidates already-running callbacks before they may rearm.
 */
class PeriodicAccountRefreshController(
    private val scheduler: AccountRefreshScheduler,
    private val action: AccountRefreshAction,
    private val refreshIntervalMillis: Long = DEFAULT_REFRESH_INTERVAL_MILLIS,
) : Closeable {
    init {
        require(refreshIntervalMillis in MIN_REFRESH_INTERVAL_MILLIS..MAX_REFRESH_INTERVAL_MILLIS)
    }

    private val lock = Any()
    private var generation = 0L
    private var active = false
    private var pending: ScheduledAccountRefresh? = null

    fun start() {
        val token = synchronized(lock) {
            if (active) return
            active = true
            nextGenerationLocked()
        }
        schedule(token, delayMillis = 0)
    }

    fun stop() {
        val cancelled = synchronized(lock) {
            if (!active && pending == null) return
            active = false
            nextGenerationLocked()
            pending.also { pending = null }
        }
        cancelled?.close()
    }

    override fun close() = stop()

    private fun schedule(token: Long, delayMillis: Long) {
        val scheduled = scheduler.schedule(delayMillis) { run(token) }
        val superseded = synchronized(lock) {
            if (!active || generation != token) {
                true
            } else {
                pending = scheduled
                false
            }
        }
        if (superseded) scheduled.close()
    }

    private fun run(token: Long) {
        synchronized(lock) {
            if (!active || generation != token) return
            pending = null
        }
        runCatching(action::refreshAccount)
        synchronized(lock) {
            if (!active || generation != token) return
        }
        schedule(token, refreshIntervalMillis)
    }

    private fun nextGenerationLocked(): Long {
        check(generation < Long.MAX_VALUE) { "Account refresh generation exhausted" }
        generation += 1
        return generation
    }

    companion object {
        const val DEFAULT_REFRESH_INTERVAL_MILLIS = 15 * 60 * 1_000L
        private const val MIN_REFRESH_INTERVAL_MILLIS = 60_000L
        private const val MAX_REFRESH_INTERVAL_MILLIS = 24 * 60 * 60 * 1_000L
    }
}

class HandlerAccountRefreshScheduler(
    private val handler: Handler,
) : AccountRefreshScheduler {
    override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledAccountRefresh {
        require(delayMillis >= 0)
        val runnable = Runnable(task)
        if (!handler.postDelayed(runnable, delayMillis)) {
            return ScheduledAccountRefresh {}
        }
        return ScheduledAccountRefresh { handler.removeCallbacks(runnable) }
    }
}
