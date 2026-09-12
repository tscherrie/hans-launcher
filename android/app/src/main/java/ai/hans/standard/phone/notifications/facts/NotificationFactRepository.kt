package ai.hans.standard.phone.notifications.facts

import java.io.Closeable

/**
 * Host-only durable store contract. Captured tokens are never refreshed on a late model result.
 * Correct/privacy calls need an independently authorized owner request; model-provided flags are
 * not that authority. No operation imports into or deletes from native Codex memory or chats.
 */
interface NotificationFactRepository : Closeable {
    /** Null means unavailable, privacy recovery pending, or this package is not permitted. */
    fun captureToken(packageName: String): NotificationArchiveCaptureToken?

    /**
     * Read-only privacy check immediately before the host persists a validated candidate outbox.
     * Requires the ORIGINAL capture token, current external privacy and no scoped tombstones.
     * Does not create an archive, reserve capacity, write receipts or authorize a model request.
     * The caller holds the shared mutation monitor through this check and the queue transaction.
     */
    fun canStage(batch: NotificationFactBatch): Boolean

    fun commit(batch: NotificationFactBatch): NotificationFactCommitResult

    /** Throws NotificationFactArchiveUnavailableException instead of returning a false empty page. */
    fun query(query: NotificationFactQuery): NotificationFactQueryResult

    fun correct(correction: NotificationFactCorrection): NotificationFactCorrectionResult

    /**
     * One SQLite transaction persists intent + generation/tombstones + archive/index deletion.
     * ALL/PACKAGE advance the corresponding generation. FACT/SOURCE retain package generations
     * and persist scoped tombstones: canStage/commit reject forgotten candidates while preserving
     * previously validated unrelated batches. The caller still purges the agreed queue/raw scope.
     */
    fun beginPrivacy(request: NotificationFactPrivacyRequest): NotificationFactPrivacyBeginResult

    /** These content-free intents remain accessible while normal archive access is blocked. */
    fun pendingIntents(): List<NotificationFactPrivacyIntent>

    /**
     * Only the trusted privacy coordinator calls this AFTER verifying the external queue/raw purge.
     * The exact durable intent must match. False is not a completed multi-store forget receipt.
     */
    fun acknowledgePrivacy(intent: NotificationFactPrivacyIntent): Boolean

    /** No notification content, source refs, query terms or credentials. */
    fun health(): NotificationFactArchiveHealth
}
