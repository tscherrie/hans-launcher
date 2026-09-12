package ai.hans.standard.phone.notifications.facts

/**
 * Host-only bridge between the inbox privacy coordinator, the fact repository and Queue-v4.
 *
 * Callers hold NotificationPrivacyMutationCoordinator.lock. A successful begin has already
 * durably deleted the scoped archive/index entries and recorded an intent plus generation.
 * Neither ordinary inbox retention nor transient queue repair may call begin.
 *
 * There is deliberately no permissive/no-op production implementation. The Android adapter
 * must observe the real policy/fence and strictly verify the durable Queue-v4 mutation.
 */
internal interface NotificationFactPrivacyPort {
    fun begin(request: NotificationFactPrivacyRequest): NotificationFactPrivacyBeginResult

    /** Throws when durable state cannot be read; an unavailable store is not an empty list. */
    fun pending(): List<NotificationFactPrivacyIntent>

    /**
     * Only after begin confirmed the archive mutation. The full intent retains resolved
     * provenance for a Fact scope; the adapter must not widen that scope to an all-data purge.
     */
    fun purgeCandidateOutbox(intent: NotificationFactPrivacyIntent): Boolean

    /** Only after the caller verified the agreed raw-inbox/triage and candidate-outbox purge. */
    fun ack(intent: NotificationFactPrivacyIntent): Boolean
}
