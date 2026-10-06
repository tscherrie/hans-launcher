package ai.hans.standard.notifications.hooks

/** Durable data mutation is not proof that an already admitted audio output physically stopped. */
internal object NotificationEventPrivacyBarrier {
    fun apply(mutateLedger: () -> Boolean, confirmOutputStop: () -> Boolean, reportFailure: (String) -> Unit): Boolean {
        val committed = mutateLedger()
        // The ledger synchronized method has returned before any bounded physical-audio wait.
        // Stop is attempted even after a failed data commit; privacy callers retain their fence.
        val stopped = runCatching { confirmOutputStop() }.getOrDefault(false)
        if (!stopped) reportFailure("speech_revocation_unconfirmed")
        return committed && stopped
    }
}
