package ai.hans.standard.phone.keys

import java.io.Closeable
import java.util.LinkedHashSet

/** Proof that Android delivered a configured shortcut through Accessibility. */
data class GlobalActionKeyAccessibilityReceipt(
    val mappingId: String,
    val command: ActionKeyCommand,
    val event: ObservableAndroidKeyEvent,
)

/**
 * Process-local event channel used by setup to distinguish a real global
 * Accessibility receipt from the launcher's ordinary foreground key dispatch.
 * Receipts are never replayed: setup must arm its observer before asking the
 * user to demonstrate the shortcut.
 */
object GlobalActionKeyAccessibilityReceiptCenter {
    private val lock = Any()
    private val observers = LinkedHashSet<ObserverRegistration>()

    fun observe(observer: (GlobalActionKeyAccessibilityReceipt) -> Unit): Closeable {
        val registration = ObserverRegistration(observer)
        synchronized(lock) { observers += registration }
        return Closeable {
            synchronized(lock) { observers.remove(registration) }
        }
    }

    internal fun publish(receipt: GlobalActionKeyAccessibilityReceipt) {
        val listeners = synchronized(lock) { observers.map(ObserverRegistration::observer) }
        listeners.forEach { observer -> runCatching { observer(receipt) } }
    }

    private class ObserverRegistration(
        val observer: (GlobalActionKeyAccessibilityReceipt) -> Unit,
    )
}
