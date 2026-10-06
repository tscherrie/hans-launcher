package ai.hans.standard.phone.accessibility.android

import ai.hans.standard.phone.accessibility.SemanticUiSnapshot

/**
 * One fresh observation followed only by published-state notifications, never idle polling.
 * Snapshot predicates and platform probes run outside [monitor]: they may need the main looper,
 * which must remain free to publish the next Accessibility frame and signal this waiter.
 */
internal class EventDrivenSemanticSnapshotWaiter(
    private val freshSnapshot: (Long) -> SemanticUiSnapshot?,
    private val currentSnapshot: () -> SemanticUiSnapshot?,
    private val elapsedRealtimeMillis: () -> Long,
) {
    private val monitor = Object()
    private var revision = Any()

    fun wake() {
        synchronized(monitor) {
            revision = Any()
            monitor.notifyAll()
        }
    }

    fun await(
        timeoutMillis: Long,
        cancelled: () -> Boolean,
        available: () -> Boolean,
        predicate: (SemanticUiSnapshot) -> Boolean,
    ): SemanticUiSnapshot? {
        if (timeoutMillis !in 1..MAX_WAIT_MILLIS || Thread.currentThread().isInterrupted) return null
        val deadline = elapsedRealtimeMillis() + timeoutMillis
        if (!canContinue(cancelled, available)) return null
        // Observe the revision before capture so a publication/cancel during capture cannot be
        // lost just before entering wait. The initial capture itself may also notify us.
        var seenRevision = synchronized(monitor) { revision }
        val previousCorrelation = runCatching(currentSnapshot).getOrElse { return null }?.correlation
        val remainingForCapture = deadline - elapsedRealtimeMillis()
        if (remainingForCapture <= 0) return null
        var candidate = runCatching { freshSnapshot(remainingForCapture) }.getOrNull()
        while (true) {
            if (!canContinue(cancelled, available) || elapsedRealtimeMillis() >= deadline) return null
            val observed = candidate
            if (observed != null && observed.correlation != previousCorrelation &&
                runCatching { predicate(observed) }.getOrElse { return null }
            ) {
                if (!canContinue(cancelled, available) || elapsedRealtimeMillis() >= deadline) return null
                return observed
            }
            synchronized(monitor) {
                // No user predicate, Android availability probe, capture or cancellation probe
                // is allowed under this monitor. A cancel must call wake() after setting its flag.
                if (revision === seenRevision) {
                    val remaining = deadline - elapsedRealtimeMillis()
                    if (remaining <= 0) return null
                    try {
                        monitor.wait(remaining)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                }
                seenRevision = revision
            }
            candidate = runCatching(currentSnapshot).getOrNull()
        }
    }

    private fun canContinue(cancelled: () -> Boolean, available: () -> Boolean): Boolean =
        !Thread.currentThread().isInterrupted &&
            !runCatching(cancelled).getOrDefault(true) && runCatching(available).getOrDefault(false)

    companion object { const val MAX_WAIT_MILLIS = 5_000L }
}
