package ai.hans.standard.integration

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock

/**
 * A cancellation wake is retained until the correlation's next wait, unlike Condition.signalAll.
 * Every method is called with the host's correlation lock held, so checking the wake and beginning
 * the Condition wait are atomic with respect to cancellation and correlation removal.
 *
 * The wake only returns control to the gateway's ownership check; it never cancels a Codex turn or
 * changes its execution state. It is consumed once so a later wait cannot busy-loop on an old wake.
 */
internal class AutomationCorrelationWaitSignal(
    private val lock: ReentrantLock,
    private val changed: Condition = lock.newCondition(),
) {
    private val cancellationWakes = mutableSetOf<String>()

    /** The caller only supplies IDs still present in its correlation map. */
    fun signalCancellation(correlationId: String) {
        checkLockHeld()
        cancellationWakes.add(correlationId)
        changed.signalAll()
    }

    /** Ordinary state notifications are not cancellation and do not create a retained wake. */
    fun signalStateChange() {
        checkLockHeld()
        changed.signalAll()
    }

    @Throws(InterruptedException::class)
    fun awaitChange(correlationId: String, maximumWaitMillis: Long) {
        checkLockHeld()
        require(maximumWaitMillis > 0)
        if (cancellationWakes.remove(correlationId)) return
        try {
            // await releases the host lock for the full blocking interval.
            changed.await(maximumWaitMillis, TimeUnit.MILLISECONDS)
        } finally {
            // Consume cancellation that arrived while this waiter was actually sleeping too.
            cancellationWakes.remove(correlationId)
        }
    }

    /** Called atomically with removing a correlation, including a proven pre-dispatch rejection. */
    fun forget(correlationId: String) {
        checkLockHeld()
        cancellationWakes.remove(correlationId)
    }

    private fun checkLockHeld() {
        check(lock.isHeldByCurrentThread) { "The automation correlation lock must be held" }
    }
}
