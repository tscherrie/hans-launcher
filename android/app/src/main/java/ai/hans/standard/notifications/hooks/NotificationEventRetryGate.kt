package ai.hans.standard.notifications.hooks

/** Receipt chatter is not a retry signal; actual accepted intake frees a real native slot. */
internal class NotificationEventRetryGate {
    private var definitelyUnsentId: String? = null
    @Synchronized fun mayAttempt(eventId: String): Boolean = definitelyUnsentId != eventId
    @Synchronized fun rejectedBeforeTransport(eventId: String) { definitelyUnsentId = eventId }
    @Synchronized fun externalWake() { definitelyUnsentId = null }
    @Synchronized fun runtimeReceiptAccepted() { definitelyUnsentId = null }
}
