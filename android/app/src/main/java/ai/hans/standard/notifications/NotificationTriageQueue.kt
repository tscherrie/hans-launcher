package ai.hans.standard.notifications

import ai.hans.standard.phone.notifications.NotificationEventKind
import ai.hans.standard.phone.notifications.NotificationInboxEvent
import ai.hans.standard.phone.notifications.facts.NotificationArchiveCaptureToken
import ai.hans.standard.phone.notifications.facts.NotificationFactBatch
import ai.hans.standard.phone.notifications.facts.NotificationFactCommitResult
import ai.hans.standard.phone.notifications.facts.NotificationFactIds
import ai.hans.standard.phone.notifications.facts.NotificationFactPrivacyScope
import ai.hans.standard.phone.notifications.facts.NotificationFactRepository
import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable handoff from Android's notification listener to a future *restricted* triage worker.
 *
 * This class deliberately has no dependency on the interactive Codex host. Queued notification
 * text stays untrusted until an isolated executor turns it into a typed, bounded suggestion.
 */
internal class NotificationTriageQueue(
    private val storage: NotificationTriageStorage,
    private val exclusionPolicy: HansNotificationExclusionPolicy,
    private val clock: () -> Long = System::currentTimeMillis,
    private val factArchive: NotificationFactRepository? = null,
) {
    /** Strict non-mutating readiness proof used before reconciliation can publish READY. */
    fun health(): NotificationTriageQueueHealth = transaction {
        when (val read = storage.readStatus()) {
            is NotificationTriageStorageRead.Available -> {
                if (!read.state.isCanonicalPersistedState()) {
                    NotificationTriageQueueHealth.Unavailable
                } else {
                    NotificationTriageQueueHealth.Available(
                        recordCount = read.state.records.size,
                        factOutboxCount = read.state.factOutbox.size,
                        factOutboxCapacityDrops = read.state.factOutboxCapacityDrops,
                    )
                }
            }
            NotificationTriageStorageRead.Unavailable -> NotificationTriageQueueHealth.Unavailable
        }
    }

    /**
     * Clears only verifiably readable transient records. An unknown v4 document may contain
     * durable claims and cannot be replaced by a raw-inbox recovery. Only an explicit all-data
     * privacy intent, followed by [purgeFactOutbox], may authorize that destructive repair.
     */
    fun repairToVerifiedEmpty(): Boolean = clearAll()

    fun ingest(event: NotificationInboxEvent): NotificationIngressResult = transaction {
        if (event.sequence <= 0) return NotificationIngressResult.RejectedInvalidSequence
        if (exclusionPolicy.excludes(event)) {
            return NotificationIngressResult.ExcludedOwnNotification(event.snapshot.packageName)
        }
        if (event.kind == NotificationEventKind.REMOVED) {
            return NotificationIngressResult.IgnoredEventKind(event.kind)
        }

        val now = clock().coerceAtLeast(0)
        val state = storage.read().normalize().expireStaleBacklog(now)
        state.records.firstOrNull { it.envelope.sourceSequence == event.sequence }?.let {
            return NotificationIngressResult.Duplicate(it.toReceipt())
        }

        // A newer version of the same Android notification invalidates work that has not crossed
        // the user boundary yet. Any stale in-flight model result then fails its cleared lease.
        val inFlightModelSuperseded = state.records.any { record ->
            record.envelope.packageName == event.snapshot.packageName &&
                record.envelope.androidKey == event.snapshot.androidKey &&
                record.state == NotificationDeliveryState.RESTRICTED_TRIAGE_IN_PROGRESS
        }
        var pruned = state
            .supersedeOutstanding(event, now)
            .pruneTerminalRecords()
        if (pruned.pendingCount() >= NotificationTriageBounds.MAX_PENDING) {
            // Preserve freshness under a sustained burst. Only the oldest unclaimed model item
            // may yield; in-flight work and already validated user suggestions are never evicted.
            pruned = pruned.makeRoomForFreshNotification(now).pruneTerminalRecords()
        }
        if (pruned.pendingCount() >= NotificationTriageBounds.MAX_PENDING ||
            pruned.records.size >= NotificationTriageBounds.MAX_RECORDS
        ) {
            storage.write(pruned)
            return NotificationIngressResult.RejectedAtCapacity
        }

        val record = StoredNotificationTriageRecord(
            id = UUID.randomUUID().toString(),
            envelope = event.toUntrustedEnvelope(),
            state = NotificationDeliveryState.PENDING_RESTRICTED_TRIAGE,
            queuedAtEpochMillis = now,
            updatedAtEpochMillis = now,
            triageAttempts = 0,
            claimToken = null,
            claimExpiresAtEpochMillis = null,
            suggestion = null,
            dismissalReason = null,
            userDeliveryAttempts = 0,
            userDeliveryClaimToken = null,
            userDeliveryClaimExpiresAtEpochMillis = null,
            deliveredAtEpochMillis = null,
        )
        val updated = pruned.copy(records = pruned.records + record)
        storage.write(updated)
        return NotificationIngressResult.Queued(
            receipt = record.toReceipt(),
            inFlightModelSuperseded = inFlightModelSuperseded,
        )
    }

    /**
     * Clears transient notification history, not selected durable claims. A retention trim or
     * inbox recovery must not accidentally become a long-term forget operation. Explicit privacy
     * mutations use [purgeFactOutbox] as well, behind the archive's durable generation fence.
     */
    fun clearAll(): Boolean = transaction {
        runCatching {
            val previous = (storage.readStatus() as? NotificationTriageStorageRead.Available)?.state
                ?: return@runCatching false
            val expected = previous.copy(records = emptyList())
            storage.write(expected)
            (storage.readStatus() as? NotificationTriageStorageRead.Available)?.state == expected
        }.getOrDefault(false)
    }

    /** A verified outbox purge is a required part of every explicit archive forget receipt. */
    fun purgeFactOutbox(scope: NotificationFactPrivacyScope): Boolean = transaction {
        runCatching {
            val state = (storage.readStatus() as? NotificationTriageStorageRead.Available)?.state
            if (state == null) {
                if (scope != NotificationFactPrivacyScope.All) return@runCatching false
                // The caller already fenced an explicitly requested all-data forget in SQLite.
                // No source-scoped/retention operation is allowed to take this repair path.
                val empty = NotificationTriageQueueState(emptyList())
                storage.write(empty)
                return@runCatching (storage.readStatus() as? NotificationTriageStorageRead.Available)
                    ?.state == empty
            }
            val expected = state.copy(factOutbox = state.factOutbox.filterNot { entry ->
                val batch = entry.batch
                when (scope) {
                    NotificationFactPrivacyScope.All -> true
                    is NotificationFactPrivacyScope.Package -> batch.packageName == scope.packageName
                    is NotificationFactPrivacyScope.Source -> batch.packageName == scope.packageName &&
                        batch.sourceRef == scope.sourceRef
                    is NotificationFactPrivacyScope.Fact -> batch.validatedCandidates.any {
                        NotificationFactIds.forCandidate(batch.packageName, batch.sourceRef, it) ==
                            scope.factId
                    }
                }
            })
            // Drop the whole matching batch, never change payload under an existing batch ID.
            if (expected != state) storage.write(expected)
            (storage.readStatus() as? NotificationTriageStorageRead.Available)?.state == expected
        }.getOrDefault(false)
    }

    /** One local commit, no model invocation and no user-facing output. */
    fun drainNextFactOutbox(): NotificationFactOutboxDrainResult = transaction {
        val archive = factArchive ?: return NotificationFactOutboxDrainResult.IDLE
        val now = clock().coerceAtLeast(0)
        val state = storage.read().normalize()
        // A slow failed commit can make an earlier list entry eligible again while later
        // entries have never been attempted. Prefer the oldest due deadline; equal deadlines
        // retain their durable list order without adding another persistence field.
        val entry = state.factOutbox.asSequence()
            .filter { it.nextAttemptAtEpochMillis <= now }
            .minByOrNull { it.nextAttemptAtEpochMillis }
            ?: return NotificationFactOutboxDrainResult.IDLE
        val result = runCatching { archive.commit(entry.batch) }.getOrNull()
        val acknowledged = when (result) {
            is NotificationFactCommitResult.Stored,
            is NotificationFactCommitResult.Replay,
            -> true
            is NotificationFactCommitResult.Rejected ->
                result.reason != NotificationFactCommitResult.Reason.ID_CONFLICT
            else -> false
        }
        val updated = if (acknowledged) {
            state.copy(factOutbox = state.factOutbox.filterNot { it.batch.batchId == entry.batch.batchId })
        } else {
            val attempts = (entry.attempts + 1).coerceAtMost(NotificationFactOutboxCodec.MAX_ATTEMPTS)
            val delay = NotificationTriageRetryPolicy.delayMillis(attempts)
            val failedAt = clock().coerceAtLeast(now)
            val deferred = entry.copy(
                attempts = attempts,
                nextAttemptAtEpochMillis = failedAt.coerceAtMost(Long.MAX_VALUE - delay) + delay,
            )
            state.copy(factOutbox = state.factOutbox.map {
                if (it.batch.batchId == entry.batch.batchId) deferred else it
            })
        }
        // A crash here replays the same immutable batch into SQLite; commit is idempotent.
        storage.write(updated)
        return if (acknowledged) NotificationFactOutboxDrainResult.PROGRESSED
        else NotificationFactOutboxDrainResult.DEFERRED
    }

    fun nextFactOutboxRecoveryAtEpochMillis(): Long? = transaction {
        if (factArchive == null) return null
        storage.read().factOutbox.minOfOrNull { it.nextAttemptAtEpochMillis }
    }

    /** Purges records whose package is excluded by the current effective privacy policy. */
    fun purgeExcluded(): Int = transaction {
        val state = (storage.readStatus() as? NotificationTriageStorageRead.Available)
            ?.state
            ?.normalize()
            ?: error("notification_triage_storage_unavailable")
        val kept = state.records.filterNot {
            exclusionPolicy.excludesPackage(it.envelope.packageName)
        }
        val removed = state.records.size - kept.size
        val expected = state.copy(records = kept).normalize()
        if (removed > 0) storage.write(expected)
        val verified = (storage.readStatus() as? NotificationTriageStorageRead.Available)
            ?.state
            ?.normalize()
            ?: error("notification_triage_storage_unavailable_after_privacy_purge")
        check(verified == expected) { "notification_triage_privacy_purge_verification_failed" }
        return removed
    }

    /**
     * Invalidates every not-yet-delivered version of one Android notification. This includes
     * model and user-delivery leases, so a result racing a system removal can no longer surface.
     */
    fun cancelOutstanding(
        packageName: String,
        androidKey: String,
    ): NotificationCancellationResult = transaction {
        if (packageName.isBlank() || androidKey.isBlank()) {
            return NotificationCancellationResult(0, false)
        }
        val now = clock().coerceAtLeast(0)
        val state = storage.read().normalize().expireStaleBacklog(now)
        var cancelled = 0
        var inFlightModelCancelled = false
        val updated = state.copy(records = state.records.map { record ->
            if (
                record.envelope.packageName == packageName &&
                record.envelope.androidKey == androidKey &&
                record.state in CANCELLABLE_STATES
            ) {
                cancelled += 1
                inFlightModelCancelled = inFlightModelCancelled ||
                    record.state == NotificationDeliveryState.RESTRICTED_TRIAGE_IN_PROGRESS
                record.dismissed(
                    now = now,
                    reason = NotificationDismissalReason.NOTIFICATION_REMOVED,
                )
            } else {
                record
            }
        })
        storage.write(updated)
        return NotificationCancellationResult(cancelled, inFlightModelCancelled)
    }

    /**
     * Claims at most one item. The returned content must only be given to
     * [RestrictedNotificationTriageExecutor], never to an interactive user turn.
     */
    fun claimNextRestrictedTriage(): RestrictedTriageWorkItem? = transaction {
        val now = clock().coerceAtLeast(0)
        val state = storage.read().normalize().withoutCurrentlyExcludedRecords()
            .expireStaleBacklog(now)
            .recoverExpiredClaims(now)
            .pruneTriageBudget(now)
        if (state.recentTriageClaimEpochMillis.size >=
            NotificationTriageBounds.MAX_TRIAGE_CALLS_PER_BUDGET_WINDOW
        ) {
            storage.write(state)
            return null
        }
        val candidates = state.records.withIndex().filter { (_, record) ->
            record.state == NotificationDeliveryState.PENDING_RESTRICTED_TRIAGE &&
                record.restrictedTriageEligibleAt() <= now
        }
        val selected = if (state.nextRestrictedClaimPrefersNewest) {
            candidates.maxWithOrNull(
                compareBy<IndexedValue<StoredNotificationTriageRecord>> {
                    it.value.envelope.sourceSequence
                }.thenBy { it.value.queuedAtEpochMillis },
            )
        } else {
            candidates.minWithOrNull(
                compareBy<IndexedValue<StoredNotificationTriageRecord>> {
                    it.value.envelope.sourceSequence
                }.thenBy { it.value.queuedAtEpochMillis },
            )
        }
        val index = selected?.index ?: -1
        if (index < 0) {
            storage.write(state)
            return null
        }

        val current = state.records[index]
        val token = UUID.randomUUID().toString()
        val claimed = current.copy(
            state = NotificationDeliveryState.RESTRICTED_TRIAGE_IN_PROGRESS,
            updatedAtEpochMillis = now,
            triageAttempts = current.triageAttempts + 1,
            claimToken = token,
            claimExpiresAtEpochMillis = now + NotificationTriageBounds.CLAIM_LEASE_MILLIS,
            factCaptureToken = runCatching { factArchive?.captureToken(current.envelope.packageName) }
                .getOrNull(),
        )
        val updated = state.replaceAt(index, claimed).copy(
            nextRestrictedClaimPrefersNewest = !state.nextRestrictedClaimPrefersNewest,
            recentTriageClaimEpochMillis = state.recentTriageClaimEpochMillis + now,
        )
        storage.write(updated)
        return RestrictedTriageWorkItem(
            receipt = claimed.toReceipt(),
            claimToken = token,
            notification = claimed.envelope,
        )
    }

    /** Returns a preempted lease without consuming a model-failure attempt. */
    fun releaseRestrictedTriage(id: String, claimToken: String): TriageCompletionResult =
        transaction {
        val now = clock().coerceAtLeast(0)
        val state = storage.read().normalize().expireStaleBacklog(now)
        val index = state.records.indexOfFirst { it.id == id }
        if (index < 0) {
            storage.write(state)
            return TriageCompletionResult.MissingOrExpiredLease
        }
        val current = state.records[index]
        if (
            current.state != NotificationDeliveryState.RESTRICTED_TRIAGE_IN_PROGRESS ||
            current.claimToken != claimToken
        ) {
            storage.write(state)
            return TriageCompletionResult.MissingOrExpiredLease
        }
        val released = current.copy(
            state = NotificationDeliveryState.PENDING_RESTRICTED_TRIAGE,
            updatedAtEpochMillis = now,
            triageAttempts = (current.triageAttempts - 1).coerceAtLeast(0),
            claimToken = null,
            claimExpiresAtEpochMillis = null,
            factCaptureToken = null,
        )
        storage.write(state.replaceAt(index, released))
        return TriageCompletionResult.Accepted(released.toReceipt())
    }

    /**
     * Applies a result from the isolated executor. A stale lease cannot alter a receipt.
     */
    fun completeRestrictedTriage(
        id: String,
        claimToken: String,
        decision: RestrictedTriageDecision,
    ): TriageCompletionResult = transaction {
        val now = clock().coerceAtLeast(0)
        val state = storage.read().normalize()
            .expireStaleBacklog(now)
            .recoverExpiredClaims(now)
        val index = state.records.indexOfFirst { it.id == id }
        if (index < 0) {
            storage.write(state)
            return TriageCompletionResult.MissingOrExpiredLease
        }
        val current = state.records[index]
        if (current.state != NotificationDeliveryState.RESTRICTED_TRIAGE_IN_PROGRESS ||
            current.claimToken != claimToken ||
            current.claimExpiresAtEpochMillis == null || current.claimExpiresAtEpochMillis <= now
        ) {
            storage.write(state)
            return TriageCompletionResult.MissingOrExpiredLease
        }

        val completed = when (decision) {
            is RestrictedTriageDecision.NotRelevant -> current.copy(
                state = NotificationDeliveryState.DISMISSED_BY_TRIAGE,
                updatedAtEpochMillis = now,
                claimToken = null,
                claimExpiresAtEpochMillis = null,
                suggestion = null,
                dismissalReason = decision.reason,
                factCaptureToken = null,
            )
            is RestrictedTriageDecision.SuggestUser -> {
                val safeSuggestion = decision.suggestion.normalizedOrNull()
                    ?: return TriageCompletionResult.InvalidDecision
                current.copy(
                    state = NotificationDeliveryState.SUGGESTED_TO_USER,
                    updatedAtEpochMillis = now,
                    claimToken = null,
                    claimExpiresAtEpochMillis = null,
                    suggestion = safeSuggestion,
                    dismissalReason = null,
                    userDeliveryAttempts = 0,
                    userDeliveryClaimToken = null,
                    userDeliveryClaimExpiresAtEpochMillis = null,
                    deliveredAtEpochMillis = null,
                    factCaptureToken = null,
                )
            }
        }
        var updated = state.replaceAt(index, completed)
        current.validatedFactBatch(decision)?.let { batch ->
            updated = if (updated.factOutbox.size < NotificationFactOutboxCodec.MAX_ITEMS) {
                updated.copy(factOutbox = updated.factOutbox + StoredNotificationFactOutbox(batch))
            } else {
                // Bounded disk use must not suppress an otherwise valid urgent notification.
                // This durable, content-free loss counter is exposed as degraded archive health.
                updated.copy(factOutboxCapacityDrops =
                    updated.factOutboxCapacityDrops.coerceAtMost(Long.MAX_VALUE - 1) + 1)
            }
        }
        storage.write(updated)
        return TriageCompletionResult.Accepted(completed.toReceipt())
    }

    /** Records a transient isolated-worker error without exposing notification text in errors. */
    fun failRestrictedTriage(id: String, claimToken: String): TriageCompletionResult = transaction {
        val now = clock().coerceAtLeast(0)
        val state = storage.read().normalize()
            .expireStaleBacklog(now)
            .recoverExpiredClaims(now)
        val index = state.records.indexOfFirst { it.id == id }
        if (index < 0) {
            storage.write(state)
            return TriageCompletionResult.MissingOrExpiredLease
        }
        val current = state.records[index]
        if (current.state != NotificationDeliveryState.RESTRICTED_TRIAGE_IN_PROGRESS ||
            current.claimToken != claimToken
        ) {
            storage.write(state)
            return TriageCompletionResult.MissingOrExpiredLease
        }
        val retryable = current.triageAttempts < NotificationTriageBounds.MAX_TRIAGE_ATTEMPTS
        val updated = current.copy(
            state = if (retryable) {
                NotificationDeliveryState.PENDING_RESTRICTED_TRIAGE
            } else {
                NotificationDeliveryState.TRIAGE_FAILED
            },
            updatedAtEpochMillis = now,
            claimToken = null,
            claimExpiresAtEpochMillis = null,
            factCaptureToken = null,
        )
        storage.write(state.replaceAt(index, updated))
        return TriageCompletionResult.Accepted(updated.toReceipt())
    }

    /**
     * Claims one already validated suggestion for the narrow chat/TTS boundary. The original
     * notification text is deliberately unavailable from this method.
     */
    fun claimNextUserDelivery(): UserFacingNotificationDeliveryLease? = transaction {
        val now = clock().coerceAtLeast(0)
        val state = storage.read().normalize().withoutCurrentlyExcludedRecords()
            .expireStaleBacklog(now)
            .recoverExpiredClaims(now)
        val index = state.records.indexOfFirst {
            it.state == NotificationDeliveryState.SUGGESTED_TO_USER && it.suggestion != null
        }
        if (index < 0) {
            storage.write(state)
            return null
        }

        val current = state.records[index]
        val suggestion = current.suggestion ?: return null
        val token = UUID.randomUUID().toString()
        val claimed = current.copy(
            state = NotificationDeliveryState.USER_DELIVERY_IN_PROGRESS,
            updatedAtEpochMillis = now,
            userDeliveryAttempts = current.userDeliveryAttempts + 1,
            userDeliveryClaimToken = token,
            userDeliveryClaimExpiresAtEpochMillis =
                now + NotificationTriageBounds.CLAIM_LEASE_MILLIS,
        )
        storage.write(state.replaceAt(index, claimed))
        return UserFacingNotificationDeliveryLease(
            delivery = claimed.toUserFacingDelivery(suggestion),
            claimToken = token,
        )
    }

    fun commitUserDeliveryForActivation(
        id: String,
        claimToken: String,
    ): UserDeliveryCompletionResult = transaction {
        val now = clock().coerceAtLeast(0)
        val state = storage.read().normalize()
            .expireStaleBacklog(now)
            .recoverExpiredClaims(now)
        val index = state.records.indexOfFirst { it.id == id }
        if (index < 0) {
            storage.write(state)
            return UserDeliveryCompletionResult.MissingOrExpiredLease
        }
        val current = state.records[index]
        if (!current.hasLiveUserDeliveryLease(claimToken, now)) {
            storage.write(state)
            return UserDeliveryCompletionResult.MissingOrExpiredLease
        }
        val committed = current.copy(
            state = NotificationDeliveryState.DELIVERY_COMMITTED_PENDING_ACTIVATION,
            updatedAtEpochMillis = now,
            userDeliveryClaimToken = null,
            userDeliveryClaimExpiresAtEpochMillis = null,
            deliveredAtEpochMillis = null,
        )
        storage.write(state.replaceAt(index, committed))
        return UserDeliveryCompletionResult.Accepted(committed.toReceipt())
    }

    /** Returns one private, durably staged suggestion that still needs idempotent activation. */
    fun nextCommittedUserDelivery(): UserFacingNotificationDelivery? = transaction {
        val now = clock().coerceAtLeast(0)
        val state = storage.read().normalize().withoutCurrentlyExcludedRecords()
            .expireStaleBacklog(now)
            .recoverExpiredClaims(now)
        val current = state.records.firstOrNull {
            it.state == NotificationDeliveryState.DELIVERY_COMMITTED_PENDING_ACTIVATION &&
                it.suggestion != null
        }
        storage.write(state)
        val suggestion = current?.suggestion ?: return null
        return current.toUserFacingDelivery(suggestion)
    }

    /**
     * Activation is retried from durable state after process death. The record becomes terminal
     * only after the sink confirms that the exact idempotency key is active (or already active).
     */
    fun activateCommittedSuggestion(
        delivery: UserFacingNotificationDelivery,
        sink: UserFacingNotificationSuggestionSink,
    ): UserDeliveryCompletionResult = transaction {
        val now = clock().coerceAtLeast(0)
        val state = storage.read().normalize()
            .expireStaleBacklog(now)
            .recoverExpiredClaims(now)
        val index = state.records.indexOfFirst { it.id == delivery.receiptId }
        if (index < 0) {
            storage.write(state)
            return UserDeliveryCompletionResult.MissingOrExpiredLease
        }
        val current = state.records[index]
        if (current.state != NotificationDeliveryState.DELIVERY_COMMITTED_PENDING_ACTIVATION ||
            current.suggestion == null ||
            current.toUserFacingDelivery(current.suggestion) != delivery
        ) {
            storage.write(state)
            return UserDeliveryCompletionResult.MissingOrExpiredLease
        }
        val activation = runCatching { sink.activate(delivery) }
            .getOrDefault(UserFacingNotificationActivationDisposition.RETRY)
        if (activation == UserFacingNotificationActivationDisposition.RETRY) {
            storage.write(state)
            return UserDeliveryCompletionResult.Accepted(current.toReceipt())
        }
        val terminal = when (activation) {
            UserFacingNotificationActivationDisposition.ACTIVE -> current.copy(
                state = NotificationDeliveryState.DELIVERED_TO_USER,
                updatedAtEpochMillis = now,
                deliveredAtEpochMillis = now,
            )
            UserFacingNotificationActivationDisposition.SUPPRESSED -> current.dismissed(
                now = now,
                reason = NotificationDismissalReason.DELIVERY_RETENTION_EXPIRED,
            )
            UserFacingNotificationActivationDisposition.RETRY -> error("handled above")
        }
        // This terminal write is the authority for releasing the Center's activation receipt.
        // A crash before the following ack deliberately leaves only a bounded, non-surfacing
        // receipt in the Center; retry/finalization is therefore safe in either order.
        storage.write(state.replaceAt(index, terminal))
        runCatching { sink.finalizeActivation(delivery, activation) }
        return UserDeliveryCompletionResult.Accepted(terminal.toReceipt())
    }

    /**
     * Staging, durable lease completion, and activation are serialized with update/removal intake.
     * A stale lease therefore cannot publish chat/TTS, and a failed queue write compensates the
     * staged local record before releasing this queue monitor.
     */
    fun deliverClaimedSuggestion(
        lease: UserFacingNotificationDeliveryLease,
        sink: UserFacingNotificationSuggestionSink,
    ): UserDeliveryCompletionResult = transaction {
        val now = clock().coerceAtLeast(0)
        val state = storage.read().normalize()
            .expireStaleBacklog(now)
            .recoverExpiredClaims(now)
        val index = state.records.indexOfFirst { it.id == lease.delivery.receiptId }
        if (index < 0) {
            storage.write(state)
            return UserDeliveryCompletionResult.MissingOrExpiredLease
        }
        val current = state.records[index]
        if (!current.hasLiveUserDeliveryLease(lease.claimToken, now)) {
            storage.write(state)
            return UserDeliveryCompletionResult.MissingOrExpiredLease
        }
        val disposition = runCatching { sink.deliver(lease.delivery) }
            .getOrDefault(UserFacingDeliveryDisposition.RETRY)
        if (disposition == UserFacingDeliveryDisposition.RETRY) {
            val retryable = current.userDeliveryAttempts <
                NotificationTriageBounds.MAX_USER_DELIVERY_ATTEMPTS
            val updated = current.copy(
                state = if (retryable) {
                    NotificationDeliveryState.SUGGESTED_TO_USER
                } else {
                    NotificationDeliveryState.USER_DELIVERY_FAILED
                },
                updatedAtEpochMillis = now,
                userDeliveryClaimToken = null,
                userDeliveryClaimExpiresAtEpochMillis = null,
            )
            storage.write(state.replaceAt(index, updated))
            return UserDeliveryCompletionResult.Accepted(updated.toReceipt())
        }

        val committed = current.copy(
            state = NotificationDeliveryState.DELIVERY_COMMITTED_PENDING_ACTIVATION,
            updatedAtEpochMillis = now,
            userDeliveryClaimToken = null,
            userDeliveryClaimExpiresAtEpochMillis = null,
            deliveredAtEpochMillis = null,
        )
        try {
            storage.write(state.replaceAt(index, committed))
        } catch (failure: Exception) {
            runCatching { sink.revoke(lease.delivery) }
            throw failure
        }
        return activateCommittedSuggestion(lease.delivery, sink)
    }

    fun failUserDelivery(
        id: String,
        claimToken: String,
    ): UserDeliveryCompletionResult = transaction {
        val now = clock().coerceAtLeast(0)
        val state = storage.read().normalize()
            .expireStaleBacklog(now)
            .recoverExpiredClaims(now)
        val index = state.records.indexOfFirst { it.id == id }
        if (index < 0) {
            storage.write(state)
            return UserDeliveryCompletionResult.MissingOrExpiredLease
        }
        val current = state.records[index]
        if (!current.hasLiveUserDeliveryLease(claimToken, now)) {
            storage.write(state)
            return UserDeliveryCompletionResult.MissingOrExpiredLease
        }
        val retryable =
            current.userDeliveryAttempts < NotificationTriageBounds.MAX_USER_DELIVERY_ATTEMPTS
        val updated = current.copy(
            state = if (retryable) {
                NotificationDeliveryState.SUGGESTED_TO_USER
            } else {
                NotificationDeliveryState.USER_DELIVERY_FAILED
            },
            updatedAtEpochMillis = now,
            userDeliveryClaimToken = null,
            userDeliveryClaimExpiresAtEpochMillis = null,
        )
        storage.write(state.replaceAt(index, updated))
        return UserDeliveryCompletionResult.Accepted(updated.toReceipt())
    }

    /** Receipts intentionally omit untrusted notification body fields. */
    fun receipts(): List<NotificationDeliveryReceipt> = transaction {
        storage.read().normalize()
            .records
            .sortedBy { it.queuedAtEpochMillis }
            .map(StoredNotificationTriageRecord::toReceipt)
    }

    /** The launcher may render only the typed suggestions produced by completed restricted triage. */
    fun userSuggestions(): List<NotificationDeliveryReceipt> = transaction {
        receipts().filter {
            it.state in USER_VISIBLE_SUGGESTION_STATES && it.suggestion != null
        }
    }

    private fun NotificationTriageQueueState.withoutCurrentlyExcludedRecords():
        NotificationTriageQueueState {
        val kept = records.filterNot {
            exclusionPolicy.excludesPackage(it.envelope.packageName)
        }
        val filtered = if (kept.size == records.size) this else copy(records = kept)
        if (filtered !== this) storage.write(filtered)
        return filtered
    }

    /** Earliest durable lease recovery time, or null when no work is leased. */
    fun nextLeaseRecoveryAtEpochMillis(): Long? = transaction {
        storage.read().normalize().records
            .asSequence()
            .flatMap { record ->
                sequenceOf(
                    record.claimExpiresAtEpochMillis,
                    record.userDeliveryClaimExpiresAtEpochMillis,
                ).filterNotNull()
            }
            .minOrNull()
    }

    /** Earliest debounce or rolling-budget boundary that can make model work eligible. */
    fun nextRestrictedTriageEligibilityAtEpochMillis(): Long? = transaction {
        val now = clock().coerceAtLeast(0)
        val state = storage.read().normalize().expireStaleBacklog(now).pruneTriageBudget(now)
        val pending = state.records.filter {
            it.state == NotificationDeliveryState.PENDING_RESTRICTED_TRIAGE
        }
        if (pending.isEmpty()) return null
        val debounceAt = pending.minOf(StoredNotificationTriageRecord::restrictedTriageEligibleAt)
        val budgetAt = if (
            state.recentTriageClaimEpochMillis.size >=
            NotificationTriageBounds.MAX_TRIAGE_CALLS_PER_BUDGET_WINDOW
        ) {
            state.recentTriageClaimEpochMillis.minOrNull()
                ?.plus(NotificationTriageBounds.GLOBAL_TRIAGE_BUDGET_WINDOW_MILLIS)
        } else {
            null
        }
        return maxOf(debounceAt, budgetAt ?: 0L).coerceAtLeast(now)
    }

    private inline fun <T> transaction(block: () -> T): T =
        synchronized(NotificationTriageTransactionCoordinator.lock) { block() }

    private fun NotificationInboxEvent.toUntrustedEnvelope(): UntrustedNotificationEnvelope =
        UntrustedNotificationEnvelope(
            sourceSequence = sequence,
            kind = kind,
            observedAtEpochMillis = observedAtEpochMillis.coerceAtLeast(0),
            packageName = NotificationTriageBounds.boundedText(
                snapshot.packageName,
                NotificationTriageBounds.MAX_PACKAGE_BYTES,
            ),
            androidKey = NotificationTriageBounds.boundedText(
                snapshot.androidKey,
                NotificationTriageBounds.MAX_ANDROID_KEY_BYTES,
            ),
            title = NotificationTriageBounds.boundedText(
                snapshot.title,
                NotificationTriageBounds.MAX_TITLE_BYTES,
            ),
            text = NotificationTriageBounds.boundedText(
                snapshot.text,
                NotificationTriageBounds.MAX_TEXT_BYTES,
            ),
            subtext = NotificationTriageBounds.boundedText(
                snapshot.subtext,
                NotificationTriageBounds.MAX_SUBTEXT_BYTES,
            ),
            category = NotificationTriageBounds.boundedText(snapshot.category, 128),
            channelId = NotificationTriageBounds.boundedText(snapshot.channelId, 256),
            ongoing = snapshot.ongoing,
            clearable = snapshot.clearable,
        )

    private fun UserFacingNotificationSuggestion.normalizedOrNull(): UserFacingNotificationSuggestion? {
        val summary = NotificationTriageBounds.boundedText(
            summary,
            NotificationTriageBounds.MAX_SUGGESTION_BYTES,
        )
        return summary.takeIf(String::isNotBlank)?.let { copy(summary = it) }
    }

    private fun StoredNotificationTriageRecord.validatedFactBatch(
        decision: RestrictedTriageDecision,
    ): NotificationFactBatch? {
        val captured = factCaptureToken ?: return null
        if (decision.memoryCandidates.isEmpty()) return null
        val archive = factArchive ?: return null
        // Never issue a fresh privacy token for an answer from a pre-forget model call.
        if (runCatching { archive.captureToken(envelope.packageName) }.getOrNull() != captured) return null
        val validated = RestrictedNotificationTriagePlanCodec.revalidateMemoryCandidates(
            decision.memoryCandidates, envelope,
        )
        if (validated.isEmpty()) return null
        val batch = runCatching {
            NotificationFactBatch(
                batchId = id,
                token = captured,
                packageName = envelope.packageName,
                sourceRef = "source_$id",
                sourceRevision = envelope.sourceSequence,
                sequence = envelope.sourceSequence,
                observedAtEpochMillis = envelope.observedAtEpochMillis,
                extractorVersion = "hans-notification-facts-v1",
                validatedCandidates = validated,
            )
        }.getOrNull()
        // Fact/source tombstones are finer than package exclusion. This read-only check avoids
        // revoking unrelated, already validated batches when the user forgets one source.
        return batch?.takeIf { runCatching { archive.canStage(it) }.getOrDefault(false) }
    }

    private fun StoredNotificationTriageRecord.toUserFacingDelivery(
        safeSuggestion: UserFacingNotificationSuggestion,
    ): UserFacingNotificationDelivery = UserFacingNotificationDelivery(
        receiptId = id,
        idempotencyKey = id,
        suggestion = safeSuggestion,
        supersessionKey = NotificationSupersessionKey.forSource(
            envelope.packageName,
            envelope.androidKey,
        ),
        activationExpiresAtEpochMillis = queuedAtEpochMillis
            .coerceAtMost(Long.MAX_VALUE - NotificationTriageBounds.MAX_BACKLOG_AGE_MILLIS) +
            NotificationTriageBounds.MAX_BACKLOG_AGE_MILLIS,
        sourceReceivedAtEpochMillis = queuedAtEpochMillis,
    )

    private companion object {
        val USER_VISIBLE_SUGGESTION_STATES = setOf(
            NotificationDeliveryState.SUGGESTED_TO_USER,
            NotificationDeliveryState.USER_DELIVERY_IN_PROGRESS,
            NotificationDeliveryState.DELIVERY_COMMITTED_PENDING_ACTIVATION,
            NotificationDeliveryState.DELIVERED_TO_USER,
        )
        val CANCELLABLE_STATES = setOf(
            NotificationDeliveryState.PENDING_RESTRICTED_TRIAGE,
            NotificationDeliveryState.RESTRICTED_TRIAGE_IN_PROGRESS,
            NotificationDeliveryState.SUGGESTED_TO_USER,
            NotificationDeliveryState.USER_DELIVERY_IN_PROGRESS,
            NotificationDeliveryState.DELIVERY_COMMITTED_PENDING_ACTIVATION,
        )
    }
}

/**
 * One process-wide read/modify/write boundary for the durable triage document. Multiple queue
 * instances exist briefly during listener teardown and privacy fallback; instance-local monitors
 * cannot prevent a stale writer from overwriting a completed privacy clear.
 */
internal object NotificationTriageTransactionCoordinator {
    val lock: Any = Any()
}

internal interface NotificationTriageStorage {
    fun read(): NotificationTriageQueueState
    fun readStatus(): NotificationTriageStorageRead =
        NotificationTriageStorageRead.Available(read())
    fun write(state: NotificationTriageQueueState)
}

internal sealed interface NotificationTriageStorageRead {
    data class Available(val state: NotificationTriageQueueState) : NotificationTriageStorageRead
    data object Unavailable : NotificationTriageStorageRead
}

internal sealed interface NotificationTriageQueueHealth {
    data class Available(
        val recordCount: Int,
        val factOutboxCount: Int = 0,
        val factOutboxCapacityDrops: Long = 0,
    ) : NotificationTriageQueueHealth {
        init {
            require(recordCount >= 0)
            require(factOutboxCount >= 0 && factOutboxCapacityDrops >= 0)
        }
    }

    data object Unavailable : NotificationTriageQueueHealth
}

internal enum class NotificationFactOutboxDrainResult { IDLE, PROGRESSED, DEFERRED }

internal data class NotificationTriageQueueState(
    val records: List<StoredNotificationTriageRecord>,
    /** Alternation makes a fresh arrival visible quickly without starving bounded old backlog. */
    val nextRestrictedClaimPrefersNewest: Boolean = true,
    /** Rolling global budget; persisted so process recreation cannot reset a notification flood. */
    val recentTriageClaimEpochMillis: List<Long> = emptyList(),
    val factOutbox: List<StoredNotificationFactOutbox> = emptyList(),
    val factOutboxCapacityDrops: Long = 0,
) {
    fun normalize(): NotificationTriageQueueState = copy(
        records = records
            .asSequence()
            .filter {
                it.id.isUuid() &&
                    it.envelope.sourceSequence > 0 &&
                    it.envelope.kind != NotificationEventKind.REMOVED
            }
            .distinctBy { it.envelope.sourceSequence }
            .map { record -> if (record.state.isTerminal()) record.redacted() else record }
            .toList(),
        recentTriageClaimEpochMillis = recentTriageClaimEpochMillis
            .filter { it >= 0 }
            .sorted()
            .takeLast(NotificationTriageBounds.MAX_TRIAGE_CALLS_PER_BUDGET_WINDOW),
    ).let { normalized ->
        if (normalized.records.size <= NotificationTriageBounds.MAX_RECORDS) normalized
        else normalized.copy(records = normalized.records.takeLast(NotificationTriageBounds.MAX_RECORDS))
    }

    fun pendingCount(): Int = records.count {
        it.state == NotificationDeliveryState.PENDING_RESTRICTED_TRIAGE ||
            it.state == NotificationDeliveryState.RESTRICTED_TRIAGE_IN_PROGRESS ||
            it.state == NotificationDeliveryState.SUGGESTED_TO_USER ||
            it.state == NotificationDeliveryState.USER_DELIVERY_IN_PROGRESS ||
            it.state == NotificationDeliveryState.DELIVERY_COMMITTED_PENDING_ACTIVATION
    }

    fun expireStaleBacklog(now: Long): NotificationTriageQueueState = copy(
        records = records.map { record ->
            if (
                !record.state.isTerminal() &&
                now >= record.queuedAtEpochMillis &&
                now - record.queuedAtEpochMillis >= NotificationTriageBounds.MAX_BACKLOG_AGE_MILLIS
            ) {
                record.terminalized(now)
            } else {
                record
            }
        },
    )

    fun pruneTriageBudget(now: Long): NotificationTriageQueueState = copy(
        recentTriageClaimEpochMillis = recentTriageClaimEpochMillis.filter { claimedAt ->
            claimedAt <= now &&
                now - claimedAt < NotificationTriageBounds.GLOBAL_TRIAGE_BUDGET_WINDOW_MILLIS
        },
    )

    /** Version-one queues predate initial-connection baselining and may contain old inbox state. */
    fun migrateLegacyBacklog(now: Long): NotificationTriageQueueState = copy(
        records = records.map { record ->
            if (record.state.isTerminal()) record else record.terminalized(now)
        },
    )

    fun makeRoomForFreshNotification(now: Long): NotificationTriageQueueState {
        val victim = records
            .asSequence()
            .filter { it.state == NotificationDeliveryState.PENDING_RESTRICTED_TRIAGE }
            .minWithOrNull(
                compareBy<StoredNotificationTriageRecord> { it.queuedAtEpochMillis }
                    .thenBy { it.envelope.sourceSequence },
            ) ?: return this
        return copy(records = records.map { record ->
            if (record.id == victim.id) record.terminalized(now) else record
        })
    }

    fun supersedeOutstanding(
        event: NotificationInboxEvent,
        now: Long,
    ): NotificationTriageQueueState {
        val packageName = event.snapshot.packageName
        val androidKey = event.snapshot.androidKey
        if (packageName.isBlank() || androidKey.isBlank()) return this
        return copy(records = records.map { record ->
            if (
                record.envelope.packageName == packageName &&
                record.envelope.androidKey == androidKey &&
                record.state in SUPERSEDABLE_STATES
            ) {
                record.dismissed(now, NotificationDismissalReason.DUPLICATE_OR_SUPERSEDED)
            } else {
                record
            }
        })
    }

    fun recoverExpiredClaims(now: Long): NotificationTriageQueueState = copy(records = records.map {
        if (it.state == NotificationDeliveryState.RESTRICTED_TRIAGE_IN_PROGRESS &&
            (it.claimExpiresAtEpochMillis == null || it.claimExpiresAtEpochMillis <= now)
        ) {
            if (it.triageAttempts >= NotificationTriageBounds.MAX_TRIAGE_ATTEMPTS) {
                it.copy(
                    state = NotificationDeliveryState.TRIAGE_FAILED,
                    updatedAtEpochMillis = now,
                    claimToken = null,
                    claimExpiresAtEpochMillis = null,
                    factCaptureToken = null,
                )
            } else {
                it.copy(
                    state = NotificationDeliveryState.PENDING_RESTRICTED_TRIAGE,
                    updatedAtEpochMillis = now,
                    claimToken = null,
                    claimExpiresAtEpochMillis = null,
                    factCaptureToken = null,
                )
            }
        } else if (
            it.state == NotificationDeliveryState.USER_DELIVERY_IN_PROGRESS &&
            (it.userDeliveryClaimExpiresAtEpochMillis == null ||
                it.userDeliveryClaimExpiresAtEpochMillis <= now)
        ) {
            if (it.userDeliveryAttempts >= NotificationTriageBounds.MAX_USER_DELIVERY_ATTEMPTS) {
                it.copy(
                    state = NotificationDeliveryState.USER_DELIVERY_FAILED,
                    updatedAtEpochMillis = now,
                    userDeliveryClaimToken = null,
                    userDeliveryClaimExpiresAtEpochMillis = null,
                )
            } else {
                it.copy(
                    state = NotificationDeliveryState.SUGGESTED_TO_USER,
                    updatedAtEpochMillis = now,
                    userDeliveryClaimToken = null,
                    userDeliveryClaimExpiresAtEpochMillis = null,
                )
            }
        } else {
            it
        }
    })

    /** Drops oldest terminal history first; live records are retained by this compaction step. */
    fun pruneTerminalRecords(): NotificationTriageQueueState {
        if (records.size < NotificationTriageBounds.MAX_RECORDS) return this
        val terminal = records.filter { it.state.isTerminal() }
            .sortedBy { it.updatedAtEpochMillis }
        val needed = records.size - NotificationTriageBounds.MAX_RECORDS + 1
        val removeIds = terminal.take(needed).mapTo(mutableSetOf()) { it.id }
        return copy(records = records.filterNot { it.id in removeIds })
    }

    fun replaceAt(index: Int, value: StoredNotificationTriageRecord): NotificationTriageQueueState {
        val updated = records.toMutableList()
        updated[index] = value
        return copy(records = updated)
    }

    private companion object {
        val SUPERSEDABLE_STATES = setOf(
            NotificationDeliveryState.PENDING_RESTRICTED_TRIAGE,
            NotificationDeliveryState.RESTRICTED_TRIAGE_IN_PROGRESS,
            NotificationDeliveryState.SUGGESTED_TO_USER,
            NotificationDeliveryState.USER_DELIVERY_IN_PROGRESS,
            NotificationDeliveryState.DELIVERY_COMMITTED_PENDING_ACTIVATION,
        )
    }
}

/**
 * Current persistence is canonical, not a recovery format. Any transformation here could
 * otherwise silently discard a committed delivery or reinterpret an active lease as empty work.
 */
private fun NotificationTriageQueueState.isCanonicalPersistedState(): Boolean =
    normalize() == this &&
        records.map(StoredNotificationTriageRecord::id).distinct().size == records.size &&
        records.all(StoredNotificationTriageRecord::hasConsistentPersistedState) &&
        factOutbox.size <= NotificationFactOutboxCodec.MAX_ITEMS &&
        factOutbox.map { it.batch.batchId }.distinct().size == factOutbox.size &&
        factOutboxCapacityDrops >= 0

private fun StoredNotificationTriageRecord.hasConsistentPersistedState(): Boolean {
    if (factCaptureToken != null && state != NotificationDeliveryState.RESTRICTED_TRIAGE_IN_PROGRESS) {
        return false
    }
    if (envelope.observedAtEpochMillis < 0) return false
    if (
        NotificationTriageBounds.boundedText(
            envelope.packageName,
            NotificationTriageBounds.MAX_PACKAGE_BYTES,
        ) != envelope.packageName ||
        NotificationTriageBounds.boundedText(
            envelope.androidKey,
            NotificationTriageBounds.MAX_ANDROID_KEY_BYTES,
        ) != envelope.androidKey ||
        NotificationTriageBounds.boundedText(
            envelope.title,
            NotificationTriageBounds.MAX_TITLE_BYTES,
        ) != envelope.title ||
        NotificationTriageBounds.boundedText(
            envelope.text,
            NotificationTriageBounds.MAX_TEXT_BYTES,
        ) != envelope.text ||
        NotificationTriageBounds.boundedText(
            envelope.subtext,
            NotificationTriageBounds.MAX_SUBTEXT_BYTES,
        ) != envelope.subtext ||
        NotificationTriageBounds.boundedText(envelope.category, 128) != envelope.category ||
        NotificationTriageBounds.boundedText(envelope.channelId, 256) != envelope.channelId
    ) {
        return false
    }
    if (queuedAtEpochMillis < 0 || updatedAtEpochMillis < 0) return false
    if (triageAttempts !in 0..NotificationTriageBounds.MAX_TRIAGE_ATTEMPTS) return false
    if (userDeliveryAttempts !in 0..NotificationTriageBounds.MAX_USER_DELIVERY_ATTEMPTS) return false
    if (claimToken != null && !claimToken.isUuid()) return false
    if (userDeliveryClaimToken != null && !userDeliveryClaimToken.isUuid()) return false
    if ((claimToken == null) != (claimExpiresAtEpochMillis == null)) return false
    if ((userDeliveryClaimToken == null) != (userDeliveryClaimExpiresAtEpochMillis == null)) {
        return false
    }
    if (claimExpiresAtEpochMillis != null && claimExpiresAtEpochMillis < 0) return false
    if (userDeliveryClaimExpiresAtEpochMillis != null && userDeliveryClaimExpiresAtEpochMillis < 0) {
        return false
    }
    if (deliveredAtEpochMillis != null && deliveredAtEpochMillis < 0) return false
    if (suggestion != null) {
        if (suggestion.summary.isBlank()) return false
        if (
            NotificationTriageBounds.boundedText(
                suggestion.summary,
                NotificationTriageBounds.MAX_SUGGESTION_BYTES,
            ) != suggestion.summary
        ) {
            return false
        }
    }

    val hasRestrictedLease = claimToken != null
    val hasUserDeliveryLease = userDeliveryClaimToken != null
    val hasSuggestion = suggestion != null
    val hasDismissal = dismissalReason != null
    return when (state) {
        NotificationDeliveryState.PENDING_RESTRICTED_TRIAGE ->
            !hasRestrictedLease && !hasUserDeliveryLease && !hasSuggestion &&
                !hasDismissal && deliveredAtEpochMillis == null
        NotificationDeliveryState.RESTRICTED_TRIAGE_IN_PROGRESS ->
            hasRestrictedLease && triageAttempts > 0 && !hasUserDeliveryLease &&
                !hasSuggestion && !hasDismissal && deliveredAtEpochMillis == null
        NotificationDeliveryState.DISMISSED_BY_TRIAGE ->
            !hasRestrictedLease && !hasUserDeliveryLease && !hasSuggestion &&
                hasDismissal && deliveredAtEpochMillis == null
        NotificationDeliveryState.TRIAGE_FAILED ->
            !hasRestrictedLease && !hasUserDeliveryLease && !hasSuggestion &&
                !hasDismissal && deliveredAtEpochMillis == null
        NotificationDeliveryState.SUGGESTED_TO_USER ->
            !hasRestrictedLease && !hasUserDeliveryLease && hasSuggestion &&
                !hasDismissal && deliveredAtEpochMillis == null
        NotificationDeliveryState.USER_DELIVERY_IN_PROGRESS ->
            !hasRestrictedLease && hasUserDeliveryLease && userDeliveryAttempts > 0 &&
                hasSuggestion && !hasDismissal && deliveredAtEpochMillis == null
        NotificationDeliveryState.DELIVERY_COMMITTED_PENDING_ACTIVATION ->
            !hasRestrictedLease && !hasUserDeliveryLease && userDeliveryAttempts > 0 &&
                hasSuggestion && !hasDismissal && deliveredAtEpochMillis == null
        NotificationDeliveryState.DELIVERED_TO_USER ->
            !hasRestrictedLease && !hasUserDeliveryLease && userDeliveryAttempts > 0 &&
                hasSuggestion && !hasDismissal && deliveredAtEpochMillis != null
        NotificationDeliveryState.USER_DELIVERY_FAILED ->
            !hasRestrictedLease && !hasUserDeliveryLease && hasSuggestion &&
                !hasDismissal && deliveredAtEpochMillis == null
    }
}

internal data class StoredNotificationTriageRecord(
    val id: String,
    val envelope: UntrustedNotificationEnvelope,
    val state: NotificationDeliveryState,
    val queuedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val triageAttempts: Int,
    val claimToken: String?,
    val claimExpiresAtEpochMillis: Long?,
    val suggestion: UserFacingNotificationSuggestion?,
    val dismissalReason: NotificationDismissalReason?,
    val userDeliveryAttempts: Int = 0,
    val userDeliveryClaimToken: String? = null,
    val userDeliveryClaimExpiresAtEpochMillis: Long? = null,
    val deliveredAtEpochMillis: Long? = null,
    val factCaptureToken: NotificationArchiveCaptureToken? = null,
) {
    fun toReceipt(): NotificationDeliveryReceipt = NotificationDeliveryReceipt(
        id = id,
        sourceSequence = envelope.sourceSequence,
        packageName = envelope.packageName,
        state = state,
        queuedAtEpochMillis = queuedAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        triageAttempts = triageAttempts,
        suggestion = suggestion,
        dismissalReason = dismissalReason,
        userDeliveryAttempts = userDeliveryAttempts,
        deliveredAtEpochMillis = deliveredAtEpochMillis,
    )

    fun hasLiveUserDeliveryLease(token: String, now: Long): Boolean =
        state == NotificationDeliveryState.USER_DELIVERY_IN_PROGRESS &&
            userDeliveryClaimToken == token &&
            userDeliveryClaimExpiresAtEpochMillis != null &&
            userDeliveryClaimExpiresAtEpochMillis > now

    fun terminalized(now: Long): StoredNotificationTriageRecord = copy(
        state = if (
            state == NotificationDeliveryState.SUGGESTED_TO_USER ||
            state == NotificationDeliveryState.USER_DELIVERY_IN_PROGRESS ||
            state == NotificationDeliveryState.DELIVERY_COMMITTED_PENDING_ACTIVATION
        ) {
            NotificationDeliveryState.USER_DELIVERY_FAILED
        } else {
            NotificationDeliveryState.TRIAGE_FAILED
        },
        updatedAtEpochMillis = now,
        claimToken = null,
        claimExpiresAtEpochMillis = null,
        factCaptureToken = null,
        userDeliveryClaimToken = null,
        userDeliveryClaimExpiresAtEpochMillis = null,
    ).redacted()

    fun dismissed(
        now: Long,
        reason: NotificationDismissalReason,
    ): StoredNotificationTriageRecord = copy(
        state = NotificationDeliveryState.DISMISSED_BY_TRIAGE,
        updatedAtEpochMillis = now,
        claimToken = null,
        claimExpiresAtEpochMillis = null,
        factCaptureToken = null,
        suggestion = null,
        dismissalReason = reason,
        userDeliveryClaimToken = null,
        userDeliveryClaimExpiresAtEpochMillis = null,
    ).redacted()

    fun restrictedTriageEligibleAt(): Long =
        envelope.observedAtEpochMillis
            .coerceAtMost(Long.MAX_VALUE - NotificationTriageBounds.PER_KEY_DEBOUNCE_MILLIS) +
            NotificationTriageBounds.PER_KEY_DEBOUNCE_MILLIS

    fun redacted(): StoredNotificationTriageRecord = copy(
        envelope = envelope.copy(
            androidKey = "",
            title = "",
            text = "",
            subtext = "",
            category = "",
            channelId = "",
            ongoing = false,
            clearable = false,
        ),
        claimToken = null,
        claimExpiresAtEpochMillis = null,
        factCaptureToken = null,
        userDeliveryClaimToken = null,
        userDeliveryClaimExpiresAtEpochMillis = null,
    )
}

internal class AtomicFileNotificationTriageStorage(
    context: Context,
    fileName: String = FILE_NAME,
) : NotificationTriageStorage {
    private val stateFile = File(context.applicationContext.noBackupFilesDir, fileName)
    private val file = AtomicFile(stateFile)
    private val initializationFile = File(
        context.applicationContext.noBackupFilesDir,
        "$fileName.initialized",
    )
    private val initializationAtomic = AtomicFile(initializationFile)

    @Synchronized
    override fun read(): NotificationTriageQueueState =
        (readStatus() as? NotificationTriageStorageRead.Available)?.state
            ?: error("notification_triage_storage_unavailable")

    @Synchronized
    override fun readStatus(): NotificationTriageStorageRead {
        if (!stateFile.isFile && !File("${stateFile.path}.bak").isFile) {
            return when (initializationStatus()) {
                TriageInitializationStatus.MISSING -> initializeEmptyLocked()
                TriageInitializationStatus.SEALED,
                TriageInitializationStatus.UNAVAILABLE,
                -> NotificationTriageStorageRead.Unavailable
            }
        }
        val bytes = runCatching { file.readFully() }
            .getOrElse { return NotificationTriageStorageRead.Unavailable }
        if (bytes.isEmpty() || bytes.size > NotificationTriageBounds.MAX_FILE_BYTES) {
            return NotificationTriageStorageRead.Unavailable
        }
        val decodedDocument = runCatching {
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            decode(text)
        }
            .getOrElse { return NotificationTriageStorageRead.Unavailable }
        val decoded = migrateDecodedDocument(decodedDocument)
            ?: return NotificationTriageStorageRead.Unavailable
        // Earlier formats can contain notification bodies in terminal receipts. Rewrite the
        // normalized, redacted state immediately instead of waiting for an unrelated event.
        if (decodedDocument.version in 1..3 && runCatching { write(decoded) }.isFailure) {
            return NotificationTriageStorageRead.Unavailable
        }
        if (!sealInitializationLocked()) return NotificationTriageStorageRead.Unavailable
        return NotificationTriageStorageRead.Available(decoded)
    }

    @Synchronized
    override fun write(state: NotificationTriageQueueState) {
        val canonical = state.normalize()
        require(canonical.isCanonicalPersistedState()) {
            "notification_triage_noncanonical_state"
        }
        val bytes = encode(canonical).toByteArray(Charsets.UTF_8)
        require(bytes.size <= NotificationTriageBounds.MAX_FILE_BYTES) { "notification_triage_storage_limit" }
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
        check(sealInitializationLocked()) { "notification_triage_initialization_seal_failed" }
    }

    private fun initializeEmptyLocked(): NotificationTriageStorageRead = runCatching {
        val empty = NotificationTriageQueueState(emptyList())
        write(empty)
        NotificationTriageStorageRead.Available(empty)
    }.getOrDefault(NotificationTriageStorageRead.Unavailable)

    private fun initializationStatus(): TriageInitializationStatus {
        if (
            !initializationFile.isFile &&
            !File("${initializationFile.path}.bak").isFile
        ) {
            return TriageInitializationStatus.MISSING
        }
        val value = runCatching { initializationAtomic.readFully().toString(Charsets.UTF_8) }
            .getOrNull()
        return if (value == INITIALIZED_STATE) {
            TriageInitializationStatus.SEALED
        } else {
            TriageInitializationStatus.UNAVAILABLE
        }
    }

    private fun sealInitializationLocked(): Boolean {
        if (initializationStatus() == TriageInitializationStatus.SEALED) return true
        val output = runCatching { initializationAtomic.startWrite() }.getOrElse { return false }
        return try {
            output.write(INITIALIZED_STATE.toByteArray(Charsets.UTF_8))
            output.fd.sync()
            initializationAtomic.finishWrite(output)
            initializationStatus() == TriageInitializationStatus.SEALED
        } catch (_: Exception) {
            initializationAtomic.failWrite(output)
            false
        }
    }

    private enum class TriageInitializationStatus {
        MISSING,
        SEALED,
        UNAVAILABLE,
    }

    private fun encode(state: NotificationTriageQueueState): String = JSONObject()
        .put("version", 4)
        .put("nextClaimPrefersNewest", state.nextRestrictedClaimPrefersNewest)
        .put(
            "recentTriageClaims",
            JSONArray().apply { state.recentTriageClaimEpochMillis.forEach(::put) },
        )
        .put("records", JSONArray().apply { state.records.forEach { put(it.toJson()) } })
        .put("factOutbox", JSONArray().apply { state.factOutbox.forEach {
            put(NotificationFactOutboxCodec.encode(it))
        } })
        .put("factOutboxCapacityDrops", state.factOutboxCapacityDrops)
        .toString()

    private fun decode(text: String): DecodedTriageDocument {
        require(isStrictNotificationJsonObject(text)) { "invalid_notification_queue_json" }
        val root = JSONObject(text)
        val version = root.requireInteger("version")
        require(version in 1..4)
        if (version >= 3) {
            require(root.keysSet() == if (version == 3) V3_ROOT_FIELDS else V4_ROOT_FIELDS)
            require(root.get("nextClaimPrefersNewest") is Boolean)
            require(root.get("recentTriageClaims") is JSONArray)
        }
        val factOutbox = if (version == 4) {
            val array = root.get("factOutbox")
            require(array is JSONArray && array.length() <= NotificationFactOutboxCodec.MAX_ITEMS)
            List(array.length()) { index ->
                val entry = array.get(index)
                require(entry is JSONObject)
                NotificationFactOutboxCodec.decode(entry)
            }
        } else emptyList()
        val array = root.getJSONArray("records")
        require(array.length() <= NotificationTriageBounds.MAX_RECORDS)
        val recentClaims = root.optionalArray("recentTriageClaims")
        if (recentClaims != null) {
            require(recentClaims.length() <= NotificationTriageBounds.MAX_TRIAGE_CALLS_PER_BUDGET_WINDOW)
        }
        return DecodedTriageDocument(
            version = version,
            state = NotificationTriageQueueState(
                records = buildList {
                    for (index in 0 until array.length()) {
                        add(array.getJSONObject(index).toRecord(version))
                    }
                },
                nextRestrictedClaimPrefersNewest = if (root.has("nextClaimPrefersNewest")) {
                    root.requireBoolean("nextClaimPrefersNewest")
                } else {
                    true
                },
                recentTriageClaimEpochMillis = buildList {
                    if (recentClaims != null) {
                        for (index in 0 until recentClaims.length()) {
                            val value = recentClaims.get(index)
                            require(
                                value is Byte || value is Short || value is Int || value is Long,
                            )
                            add((value as Number).toLong())
                        }
                    }
                },
                factOutbox = factOutbox,
                factOutboxCapacityDrops = if (version == 4) {
                    root.requireNonNegativeLong("factOutboxCapacityDrops")
                } else 0L,
            ),
        )
    }

    private fun migrateDecodedDocument(document: DecodedTriageDocument): NotificationTriageQueueState? {
        val raw = document.state
        val normalized = raw.normalize()
        return when (document.version) {
            3, 4 -> raw.takeIf(NotificationTriageQueueState::isCanonicalPersistedState)
            2 -> normalized
                .takeIf { raw.normalizationPreservesEveryReceipt(it) }
                ?.takeIf(NotificationTriageQueueState::isCanonicalPersistedState)
            1 -> normalized
                .takeIf { raw.normalizationPreservesEveryReceipt(it) }
                ?.migrateLegacyBacklog(System.currentTimeMillis().coerceAtLeast(0))
                ?.normalize()
                ?.takeIf(NotificationTriageQueueState::isCanonicalPersistedState)
            else -> null
        }
    }

    private fun NotificationTriageQueueState.normalizationPreservesEveryReceipt(
        normalized: NotificationTriageQueueState,
    ): Boolean = records.map { it.id to it.envelope.sourceSequence } ==
        normalized.records.map { it.id to it.envelope.sourceSequence } &&
        recentTriageClaimEpochMillis.size == normalized.recentTriageClaimEpochMillis.size

    private data class DecodedTriageDocument(
        val version: Int,
        val state: NotificationTriageQueueState,
    )

    private fun StoredNotificationTriageRecord.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("sourceSequence", envelope.sourceSequence)
        .put("kind", envelope.kind.name)
        .put("observedAt", envelope.observedAtEpochMillis)
        .put("packageName", envelope.packageName)
        .put("androidKey", envelope.androidKey)
        .put("title", envelope.title)
        .put("text", envelope.text)
        .put("subtext", envelope.subtext)
        .put("category", envelope.category)
        .put("channelId", envelope.channelId)
        .put("ongoing", envelope.ongoing)
        .put("clearable", envelope.clearable)
        .put("state", state.name)
        .put("queuedAt", queuedAtEpochMillis)
        .put("updatedAt", updatedAtEpochMillis)
        .put("attempts", triageAttempts)
        .put("claimToken", claimToken)
        .put("claimExpiresAt", claimExpiresAtEpochMillis)
        .put("suggestion", suggestion?.toJson())
        .put("dismissal", dismissalReason?.name)
        .put("deliveryAttempts", userDeliveryAttempts)
        .put("deliveryClaimToken", userDeliveryClaimToken)
        .put("deliveryClaimExpiresAt", userDeliveryClaimExpiresAtEpochMillis)
        .put("deliveredAt", deliveredAtEpochMillis)
        .put("factCaptureToken", factCaptureToken?.let(NotificationFactOutboxCodec::encodeToken))

    private fun JSONObject.toRecord(version: Int): StoredNotificationTriageRecord {
        if (version >= 3) {
            val keys = keysSet()
            require(keys.containsAll(V3_REQUIRED_RECORD_FIELDS))
            val optional = if (version == 3) V3_OPTIONAL_RECORD_FIELDS else V4_OPTIONAL_RECORD_FIELDS
            require(keys.all((V3_REQUIRED_RECORD_FIELDS + optional)::contains))
            // The canonical writer omits nullable values. Explicit JSON null would be rewritten
            // as absence and is therefore not a canonical version-three representation.
            optional.forEach { field ->
                if (has(field)) require(!isNull(field))
            }
        }
        return StoredNotificationTriageRecord(
            id = requireBoundedString("id", 64),
            envelope = UntrustedNotificationEnvelope(
                sourceSequence = requireLong("sourceSequence"),
                kind = NotificationEventKind.valueOf(requireString("kind")),
                observedAtEpochMillis = requireNonNegativeLong("observedAt"),
                packageName = requireBoundedString(
                    "packageName", NotificationTriageBounds.MAX_PACKAGE_BYTES,
                ),
                androidKey = requireBoundedString(
                    "androidKey", NotificationTriageBounds.MAX_ANDROID_KEY_BYTES,
                ),
                title = requireBoundedString("title", NotificationTriageBounds.MAX_TITLE_BYTES),
                text = requireBoundedString("text", NotificationTriageBounds.MAX_TEXT_BYTES),
                subtext = requireBoundedString(
                    "subtext", NotificationTriageBounds.MAX_SUBTEXT_BYTES,
                ),
                category = requireBoundedString("category", 128),
                channelId = requireBoundedString("channelId", 256),
                ongoing = requireBoolean("ongoing"),
                clearable = requireBoolean("clearable"),
            ),
            state = NotificationDeliveryState.valueOf(requireString("state")),
            queuedAtEpochMillis = requireNonNegativeLong("queuedAt"),
            updatedAtEpochMillis = requireNonNegativeLong("updatedAt"),
            triageAttempts = requireInteger("attempts").also {
                require(it in 0..NotificationTriageBounds.MAX_TRIAGE_ATTEMPTS)
            },
            claimToken = optionalString("claimToken"),
            claimExpiresAtEpochMillis = optionalNonNegativeLong("claimExpiresAt"),
            suggestion = optionalObject("suggestion")?.let {
                if (version >= 3) require(it.keysSet() == V3_SUGGESTION_FIELDS)
                UserFacingNotificationSuggestion(
                    summary = it.requireBoundedString(
                        "summary", NotificationTriageBounds.MAX_SUGGESTION_BYTES,
                    ),
                    urgency = NotificationUrgency.valueOf(it.requireString("urgency")),
                )
            },
            dismissalReason = optionalString("dismissal")
                ?.let(NotificationDismissalReason::valueOf),
            userDeliveryAttempts = if (has("deliveryAttempts")) {
                requireInteger("deliveryAttempts").also {
                    require(it in 0..NotificationTriageBounds.MAX_USER_DELIVERY_ATTEMPTS)
                }
            } else {
                0
            },
            userDeliveryClaimToken = optionalString("deliveryClaimToken"),
            userDeliveryClaimExpiresAtEpochMillis =
                optionalNonNegativeLong("deliveryClaimExpiresAt"),
            deliveredAtEpochMillis = optionalNonNegativeLong("deliveredAt"),
            factCaptureToken = if (version >= 4) {
                optionalObject("factCaptureToken")?.let(NotificationFactOutboxCodec::decodeToken)
            } else null,
        )
    }

    private fun JSONObject.requireString(name: String): String {
        val raw = get(name)
        require(raw is String)
        return raw
    }

    private fun JSONObject.requireBoundedString(name: String, byteLimit: Int): String {
        val raw = requireString(name)
        require(NotificationTriageBounds.boundedText(raw, byteLimit) == raw)
        return raw
    }

    private fun JSONObject.optionalString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        val raw = get(name)
        require(raw is String)
        return raw
    }

    private fun JSONObject.optionalObject(name: String): JSONObject? {
        if (!has(name) || isNull(name)) return null
        val raw = get(name)
        require(raw is JSONObject)
        return raw
    }

    private fun JSONObject.optionalArray(name: String): JSONArray? {
        if (!has(name) || isNull(name)) return null
        val raw = get(name)
        require(raw is JSONArray)
        return raw
    }

    private fun JSONObject.requireBoolean(name: String): Boolean {
        val raw = get(name)
        require(raw is Boolean)
        return raw
    }

    private fun JSONObject.requireLong(name: String): Long {
        val raw = get(name)
        require(raw is Byte || raw is Short || raw is Int || raw is Long)
        return (raw as Number).toLong()
    }

    private fun JSONObject.requireNonNegativeLong(name: String): Long =
        requireLong(name).also { require(it >= 0) }

    private fun JSONObject.optionalNonNegativeLong(name: String): Long? {
        if (!has(name) || isNull(name)) return null
        return requireNonNegativeLong(name)
    }

    private fun JSONObject.requireInteger(name: String): Int {
        val value = requireLong(name)
        require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
        return value.toInt()
    }

    private fun JSONObject.keysSet(): Set<String> = keys().asSequence().toSet()

    private fun UserFacingNotificationSuggestion.toJson(): JSONObject = JSONObject()
        .put("summary", summary)
        .put("urgency", urgency.name)

    companion object {
        private const val FILE_NAME = "notification-triage-v1.json"
        private const val INITIALIZED_STATE = "initialized-v1"
        private val V3_ROOT_FIELDS = setOf(
            "version", "nextClaimPrefersNewest", "recentTriageClaims", "records",
        )
        private val V4_ROOT_FIELDS = V3_ROOT_FIELDS + setOf("factOutbox", "factOutboxCapacityDrops")
        private val V3_REQUIRED_RECORD_FIELDS = setOf(
            "id", "sourceSequence", "kind", "observedAt", "packageName", "androidKey", "title",
            "text", "subtext", "category", "channelId", "ongoing", "clearable", "state",
            "queuedAt", "updatedAt", "attempts", "deliveryAttempts",
        )
        private val V3_OPTIONAL_RECORD_FIELDS = setOf(
            "claimToken", "claimExpiresAt", "suggestion", "dismissal", "deliveryClaimToken",
            "deliveryClaimExpiresAt", "deliveredAt",
        )
        private val V4_OPTIONAL_RECORD_FIELDS = V3_OPTIONAL_RECORD_FIELDS + "factCaptureToken"
        private val V3_ALLOWED_RECORD_FIELDS =
            V3_REQUIRED_RECORD_FIELDS + V3_OPTIONAL_RECORD_FIELDS
        private val V3_SUGGESTION_FIELDS = setOf("summary", "urgency")
    }
}

private fun String.isUuid(): Boolean = UUID_PATTERN.matches(this)

private fun NotificationDeliveryState.isTerminal(): Boolean = when (this) {
    NotificationDeliveryState.DELIVERED_TO_USER,
    NotificationDeliveryState.DISMISSED_BY_TRIAGE,
    NotificationDeliveryState.TRIAGE_FAILED,
    NotificationDeliveryState.USER_DELIVERY_FAILED,
    -> true
    NotificationDeliveryState.PENDING_RESTRICTED_TRIAGE,
    NotificationDeliveryState.RESTRICTED_TRIAGE_IN_PROGRESS,
    NotificationDeliveryState.SUGGESTED_TO_USER,
    NotificationDeliveryState.USER_DELIVERY_IN_PROGRESS,
    NotificationDeliveryState.DELIVERY_COMMITTED_PENDING_ACTIVATION,
    -> false
}

private val UUID_PATTERN = Regex(
    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
)
