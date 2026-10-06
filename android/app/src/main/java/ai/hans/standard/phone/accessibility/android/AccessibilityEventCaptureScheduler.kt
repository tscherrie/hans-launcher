package ai.hans.standard.phone.accessibility.android

/**
 * Main-looper-confined, one-shot event refresh. A fresh capture can cover a queued event,
 * but a capture that failed or predates a newer event must not suppress that refresh.
 * No timer runs when there is no event; failed captures do not create a retry loop.
 */
internal class AccessibilityEventCaptureScheduler(
    private val postDelayed: (Runnable) -> Boolean,
    private val removeCallbacks: (Runnable) -> Unit,
    private val capture: () -> Unit,
) {
    private var eventRevision = Any()
    private var pending: Runnable? = null

    fun onEvent() {
        eventRevision = Any()
        if (pending != null) return
        val callback = object : Runnable {
            override fun run() {
                // A removed callback from a prior connection must not consume new work.
                if (pending !== this) return
                pending = null
                capture()
            }
        }
        pending = callback
        if (!postDelayed(callback) && pending === callback) pending = null
    }

    /** Take this immediately before reading fresh platform state, never from a cached frame. */
    fun captureStarted(): Any = eventRevision

    /** Call only after the fresh frame was successfully published. */
    fun capturePublished(coveredEvents: Any) {
        if (coveredEvents === eventRevision) cancelPending()
    }

    fun cancel() {
        eventRevision = Any()
        cancelPending()
    }

    private fun cancelPending() {
        val callback = pending ?: return
        pending = null
        removeCallbacks(callback)
    }
}
