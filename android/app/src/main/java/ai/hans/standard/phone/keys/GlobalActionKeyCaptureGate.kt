package ai.hans.standard.phone.keys

import java.io.Closeable
import java.util.LinkedHashSet

/**
 * Receives the exact framework-delivered stream for one capture session.
 */
fun interface GlobalActionKeyCaptureSink {
    fun onDeliveredEvent(
        sessionId: CaptureSessionId,
        event: ObservableAndroidKeyEvent,
    )
}

/**
 * Process-scoped, exclusive ownership of Accessibility key capture.
 *
 * Closing a stale lease can never end a newer session. The lease deliberately
 * contains no Android component reference; the launcher owns and closes it as
 * part of its lifecycle.
 */
interface GlobalActionKeyCaptureLease : Closeable {
    val sessionId: CaptureSessionId
    fun isActive(): Boolean
}

data class GlobalActionKeyCaptureSnapshot(
    val sessionId: CaptureSessionId?,
    val revision: Long,
) {
    val active: Boolean get() = sessionId != null
}

/**
 * Process-local hand-off between the launcher capture screen and Accessibility.
 *
 * There is exactly one sink. While its lease is active, the Accessibility
 * controller requests key filtering even when the mapping database is empty,
 * pauses ordinary mappings, and forwards every delivered key event here.
 */
object GlobalActionKeyCaptureGate {
    private val lock = Any()
    private val observers = LinkedHashSet<ObserverRegistration>()
    private var nextLeaseId = 0L
    private var revision = 0L
    private var activeCapture: ActiveCapture? = null

    fun snapshot(): GlobalActionKeyCaptureSnapshot = synchronized(lock) {
        currentSnapshot()
    }

    fun acquire(
        sessionId: CaptureSessionId,
        sink: GlobalActionKeyCaptureSink,
    ): GlobalActionKeyCaptureLease {
        val capture: ActiveCapture
        val listeners: List<(GlobalActionKeyCaptureSnapshot) -> Unit>
        synchronized(lock) {
            check(activeCapture == null) { "An action-key capture lease is already active" }
            nextLeaseId = if (nextLeaseId == Long.MAX_VALUE) 1L else nextLeaseId + 1L
            capture = ActiveCapture(nextLeaseId, sessionId, sink)
            activeCapture = capture
            revision += 1
            listeners = observers.map(ObserverRegistration::observer)
        }
        notifyObservers(listeners, snapshot())
        return Lease(capture.leaseId, sessionId)
    }

    /** Returns true whenever an active lease owned the event, even if its sink failed. */
    fun deliver(event: ObservableAndroidKeyEvent): Boolean {
        val capture = synchronized(lock) { activeCapture } ?: return false
        runCatching { capture.sink.onDeliveredEvent(capture.sessionId, event) }
        return true
    }

    fun observe(observer: (GlobalActionKeyCaptureSnapshot) -> Unit): Closeable {
        val initial: GlobalActionKeyCaptureSnapshot
        val registration = ObserverRegistration(observer)
        synchronized(lock) {
            observers += registration
            initial = currentSnapshot()
        }
        runCatching { observer(initial) }
        return Closeable {
            synchronized(lock) { observers.remove(registration) }
        }
    }

    private fun release(leaseId: Long) {
        val listeners: List<(GlobalActionKeyCaptureSnapshot) -> Unit>
        synchronized(lock) {
            if (activeCapture?.leaseId != leaseId) return
            activeCapture = null
            revision += 1
            listeners = observers.map(ObserverRegistration::observer)
        }
        notifyObservers(listeners, snapshot())
    }

    private fun isActive(leaseId: Long): Boolean = synchronized(lock) {
        activeCapture?.leaseId == leaseId
    }

    private fun notifyObservers(
        listeners: List<(GlobalActionKeyCaptureSnapshot) -> Unit>,
        snapshot: GlobalActionKeyCaptureSnapshot,
    ) {
        listeners.forEach { observer -> runCatching { observer(snapshot) } }
    }

    private fun currentSnapshot() = GlobalActionKeyCaptureSnapshot(
        sessionId = activeCapture?.sessionId,
        revision = revision,
    )

    private data class ActiveCapture(
        val leaseId: Long,
        val sessionId: CaptureSessionId,
        val sink: GlobalActionKeyCaptureSink,
    )

    private class ObserverRegistration(
        val observer: (GlobalActionKeyCaptureSnapshot) -> Unit,
    )

    private class Lease(
        private val leaseId: Long,
        override val sessionId: CaptureSessionId,
    ) : GlobalActionKeyCaptureLease {
        override fun isActive(): Boolean = GlobalActionKeyCaptureGate.isActive(leaseId)

        override fun close() {
            GlobalActionKeyCaptureGate.release(leaseId)
        }
    }
}
