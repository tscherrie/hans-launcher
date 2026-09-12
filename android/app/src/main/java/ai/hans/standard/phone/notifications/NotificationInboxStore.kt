package ai.hans.standard.phone.notifications

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import ai.hans.standard.notifications.AtomicFileNotificationTriageStorage
import ai.hans.standard.notifications.HansNotificationExclusionPolicy
import ai.hans.standard.notifications.NotificationTriageQueue
import ai.hans.standard.notifications.NotificationTriageIntegration
import ai.hans.standard.notifications.NotificationTriagePrivacyIntegration
import ai.hans.standard.notifications.NotificationTriageTransactionCoordinator
import ai.hans.standard.phone.notifications.facts.AndroidNotificationFactPrivacyPort
import ai.hans.standard.phone.notifications.facts.NotificationFactPrivacyBeginResult
import ai.hans.standard.phone.notifications.facts.NotificationFactPrivacyIntent
import ai.hans.standard.phone.notifications.facts.NotificationFactPrivacyPort
import ai.hans.standard.phone.notifications.facts.NotificationFactPrivacyRequest
import ai.hans.standard.phone.notifications.facts.NotificationFactPrivacyScope
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.TimeUnit

class NotificationInboxStore internal constructor(
    context: Context,
    retentionLimit: Int? = null,
    databaseName: String = DEFAULT_DATABASE_NAME,
    private val privacyRepository: NotificationPrivacyRepository = NotificationPrivacyRepository(context),
    private val triageDataPurger: NotificationTriageDataPurger =
        AndroidNotificationTriageDataPurger(context.applicationContext, privacyRepository),
    private val privacyPurgeFence: NotificationPrivacyPurgeFence =
        AtomicNotificationPrivacyPurgeFence(context.applicationContext),
    private val factPrivacy: NotificationFactPrivacyPort = AndroidNotificationFactPrivacyPort(
        context.applicationContext, privacyRepository, privacyPurgeFence,
    ),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Deterministic test seam immediately after the authoritative Allowed read. */
    private val afterAllowedCaptureDecision: () -> Unit = {},
    /** Deterministic test seam while reconciliation holds the shared privacy boundary. */
    private val afterReconcilePolicyCheck: () -> Unit = {},
    /** Deterministic seam after the durable fence, before a retention-deleting transaction. */
    private val beforeRetentionMutation: () -> Unit = {},
    /** Deterministic test seam after the canonical process-wide query boundary is acquired. */
    private val afterQueryPrivacyBoundaryAcquired: () -> Unit = {},
) : Closeable, NotificationInboxQuerySource, NotificationInboxManagementSource {
    private val fixedRetentionLimit = retentionLimit?.coerceIn(1, LEGACY_MAX_RETENTION)
    private val helper = NotificationInboxOpenHelper(
        context = context.applicationContext,
        databaseName = databaseName,
    )

    /** Must be called off the main thread. */
    fun accept(
        signal: NotificationSignal,
        queueForRestrictedTriage: Boolean = true,
    ): NotificationWriteResult =
        synchronized(NotificationPrivacyMutationCoordinator.lock) {
        if (privacyPurgeFence.isRequired()) {
            check(clearHistory().triageQueueCleared) {
                "notification_privacy_purge_retry_incomplete"
            }
        }
        val decision = privacyRepository.captureDecision(signal.packageName)
        if (decision != NotificationCaptureDecision.Allowed) {
            if (decision == NotificationCaptureDecision.PolicyUnavailable) {
                check(clearHistory().triageQueueCleared) {
                    "notification_privacy_clear_incomplete"
                }
            }
            return NotificationWriteResult.ExcludedByPrivacy(decision)
        }
        afterAllowedCaptureDecision()
        val database = helper.writableDatabase
        val retentionNow = clock().coerceAtLeast(0)
        val retentionPurgeRequired = wouldPrune(
            database = database,
            nowEpochMillis = retentionNow,
            prospectiveAdditionalEvents = 1,
            prospectiveObservedAtEpochMillis = signal.observedAtEpochMillis,
        )
        if (retentionPurgeRequired) {
            check(beginGlobalPrivacyPurge()) { "notification_privacy_fence_unavailable" }
            beforeRetentionMutation()
        }
        val outcome = database.transaction {
            val result = applySignal(database, signal, queueForRestrictedTriage)
            var removedByRetention = 0
            if (result is NotificationWriteResult.Stored) {
                purgeExcluded(database)
            }
            if (retentionPurgeRequired || result is NotificationWriteResult.Stored) {
                removedByRetention = prune(database, retentionNow)
            }
            val retainedResult = if (
                result is NotificationWriteResult.Stored &&
                !containsEventSequence(database, result.sequence)
            ) {
                NotificationWriteResult.PrunedByRetention
            } else {
                result
            }
            AcceptanceOutcome(retainedResult, removedByRetention)
        }
        if (retentionPurgeRequired || outcome.removedByRetention > 0) {
            // The validated center is deliberately source-minimal and cannot map an announcement
            // back to an inbox sequence. Conservatively clear Queue + Center whenever SQLite
            // retention removes anything. The durable fence prevents a concurrent model/delivery
            // lease from escaping between the three stores.
            check(triageDataPurger.clearAll()) {
                "notification_retention_triage_purge_incomplete"
            }
            check(finishGlobalPrivacyPurge()) {
                "notification_privacy_fence_commit_failed"
            }
        }
        outcome.writeResult
    }

    /**
     * Marks previously active keys that are absent from the system's active-notification snapshot
     * as removed. This repairs state after listener or process restarts.
     */
    fun reconcileActiveKeys(
        activeAndroidKeys: Set<String>,
        observedAtEpochMillis: Long,
    ): List<NotificationReconciledRemoval> =
        synchronized(NotificationPrivacyMutationCoordinator.lock) {
        if (privacyPurgeFence.isRequired()) {
            check(clearHistory().triageQueueCleared) {
                "notification_privacy_purge_retry_incomplete"
            }
        }
        if (!privacyRepository.status().policyAvailable) {
            check(clearHistory().triageQueueCleared) {
                "notification_privacy_clear_incomplete"
            }
            return emptyList()
        }
        afterReconcilePolicyCheck()
        val database = helper.writableDatabase
        val stale = mutableListOf<Pair<String, String>>()
        database.query(
            TABLE_STATE,
            arrayOf(COL_PACKAGE, COL_ANDROID_KEY),
            "$COL_ACTIVE = 1",
            null,
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val packageName = cursor.getString(0)
                val androidKey = cursor.getString(1)
                if (androidKey !in activeAndroidKeys) stale += packageName to androidKey
            }
        }
        val retentionNow = clock().coerceAtLeast(0)
        val retentionPurgeRequired = wouldPrune(
            database = database,
            nowEpochMillis = retentionNow,
            prospectiveAdditionalEvents = stale.size,
            prospectiveObservedAtEpochMillis = observedAtEpochMillis,
        )
        if (retentionPurgeRequired) {
            check(beginGlobalPrivacyPurge()) { "notification_privacy_fence_unavailable" }
            beforeRetentionMutation()
        }
        val outcome = database.transaction {
            val removals = stale.map { (packageName, androidKey) ->
                packageName to androidKey
            }.mapNotNull { (packageName, androidKey) ->
                when (privacyRepository.captureDecision(packageName)) {
                    NotificationCaptureDecision.Allowed -> NotificationNormalizer.removed(
                        packageName = packageName,
                        androidKey = androidKey,
                        observedAtEpochMillis = observedAtEpochMillis,
                        reason = REMOVAL_REASON_RECONCILED,
                    )
                    NotificationCaptureDecision.PolicyUnavailable ->
                        error("notification_capture_policy_became_unavailable")
                    NotificationCaptureDecision.ProtectedPackage,
                    NotificationCaptureDecision.UserExcludedPackage,
                    -> null
                }
            }
            val results = removals.map { signal ->
                NotificationReconciledRemoval(
                    signal = signal,
                    writeResult = applySignal(database, signal, queueForRestrictedTriage = true),
                )
            }
            val removedByRetention = if (retentionPurgeRequired) {
                prune(database, retentionNow)
            } else {
                0
            }
            ReconcileOutcome(results, removedByRetention)
        }
        if (retentionPurgeRequired || outcome.removedByRetention > 0) {
            if (outcome.removedByRetention > 0) {
                finalizeSensitiveDeletion(database, compact = false)
            }
            check(triageDataPurger.clearAll()) {
                "notification_retention_triage_purge_incomplete"
            }
            check(finishGlobalPrivacyPurge()) {
                "notification_privacy_fence_commit_failed"
            }
        }
        outcome.removals
    }

    /**
     * Durable SQLite outbox for the crash boundary between Inbox commit and Queue/Center update.
     * Rows are replayed in source sequence order while the authoritative snapshot gate is closed.
     */
    internal fun pendingTriageOutbox(limit: Int = MAX_TRIAGE_OUTBOX_BATCH): List<NotificationInboxEvent> =
        synchronized(NotificationPrivacyMutationCoordinator.lock) {
            if (privacyPurgeFence.isRequired()) return@synchronized emptyList()
            if (!privacyRepository.status().policyAvailable) return@synchronized emptyList()
            helper.readableDatabase.rawQuery(
                """
                SELECT $EVENT_COLUMNS
                FROM $TABLE_TRIAGE_OUTBOX outbox
                INNER JOIN $TABLE_EVENTS events
                    ON events.$COL_SEQUENCE = outbox.$COL_OUTBOX_SEQUENCE
                ORDER BY events.$COL_SEQUENCE ASC
                LIMIT ?
                """.trimIndent(),
                arrayOf(limit.coerceIn(1, MAX_TRIAGE_OUTBOX_BATCH).toString()),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.readEvent())
                }
            }
        }

    internal fun acknowledgeTriageOutbox(sequence: Long): Boolean =
        synchronized(NotificationPrivacyMutationCoordinator.lock) {
            if (sequence <= 0) return@synchronized false
            val database = helper.writableDatabase
            database.delete(
                TABLE_TRIAGE_OUTBOX,
                "$COL_OUTBOX_SEQUENCE = ?",
                arrayOf(sequence.toString()),
            )
            database.rawQuery(
                "SELECT 1 FROM $TABLE_TRIAGE_OUTBOX WHERE $COL_OUTBOX_SEQUENCE = ? LIMIT 1",
                arrayOf(sequence.toString()),
            ).use { cursor -> !cursor.moveToFirst() }
        }

    /** Must be called off the main thread. Results are ordered by durable receipt sequence. */
    override fun queryPage(
        afterSequenceExclusive: Long,
        limit: Int,
    ): NotificationPage = synchronized(NotificationPrivacyMutationCoordinator.lock) {
        afterQueryPrivacyBoundaryAcquired()
        queryPageLocked(afterSequenceExclusive, limit)
    }

    private fun queryPageLocked(
        afterSequenceExclusive: Long,
        limit: Int,
    ): NotificationPage {
        val safeAfter = afterSequenceExclusive.coerceAtLeast(0)
        val safeLimit = limit.coerceIn(1, NotificationLimits.MAX_PAGE_SIZE)
        if (privacyPurgeFence.isRequired()) {
            return NotificationPage(emptyList(), safeAfter, hasMore = false)
        }
        if (!privacyRepository.status().policyAvailable) {
            check(clearHistory().triageQueueCleared) {
                "notification_privacy_clear_incomplete"
            }
            return NotificationPage(emptyList(), safeAfter, hasMore = false)
        }

        val database = helper.writableDatabase
        database.transaction { purgeExcluded(this) }
        val now = clock().coerceAtLeast(0)
        if (
            wouldPrune(
                database = database,
                nowEpochMillis = now,
                prospectiveAdditionalEvents = 0,
            )
        ) {
            check(beginGlobalPrivacyPurge()) { "notification_privacy_fence_unavailable" }
            val removed = database.transaction { prune(this, now) }
            if (removed > 0) finalizeSensitiveDeletion(database, compact = false)
            check(triageDataPurger.clearAll()) {
                "notification_retention_triage_purge_incomplete"
            }
            check(finishGlobalPrivacyPurge()) {
                "notification_privacy_fence_commit_failed"
            }
        }

        val visible = mutableListOf<NotificationInboxEvent>()
        var cursorAfter = safeAfter
        var scanned = 0
        var sourceHasMore = false
        while (visible.size <= safeLimit && scanned < MAX_QUERY_SCAN_EVENTS) {
            val chunk = mutableListOf<NotificationInboxEvent>()
            helper.readableDatabase.rawQuery(
                """
                SELECT $EVENT_COLUMNS
                FROM $TABLE_EVENTS
                WHERE $COL_SEQUENCE > ?
                ORDER BY $COL_SEQUENCE ASC
                LIMIT ?
                """.trimIndent(),
                arrayOf(cursorAfter.toString(), QUERY_CHUNK_SIZE.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) chunk += cursor.readEvent()
            }
            if (chunk.isEmpty()) {
                sourceHasMore = false
                break
            }
            scanned += chunk.size
            cursorAfter = chunk.last().sequence
            visible += chunk.filter {
                privacyRepository.captureDecision(it.snapshot.packageName) ==
                    NotificationCaptureDecision.Allowed
            }
            sourceHasMore = chunk.size == QUERY_CHUNK_SIZE
            if (!sourceHasMore) break
        }
        if (scanned >= MAX_QUERY_SCAN_EVENTS && sourceHasMore) sourceHasMore = true

        val events = visible.take(safeLimit)
        val hasMore = visible.size > safeLimit || sourceHasMore
        val nextCursor = when {
            visible.size > safeLimit -> events.last().sequence
            cursorAfter > safeAfter -> cursorAfter
            events.isNotEmpty() -> events.last().sequence
            else -> safeAfter
        }
        return NotificationPage(
            events = events,
            nextAfterSequenceExclusive = nextCursor,
            hasMore = hasMore,
        )
    }

    /** Must be called off the main thread. */
    override fun queryDigest(
        afterSequenceExclusive: Long,
        maxEvents: Int,
        maxUtf8Bytes: Int,
    ): NotificationDigest {
        val safeEventLimit = maxEvents.coerceIn(1, NotificationLimits.MAX_DIGEST_EVENTS)
        val page = queryPage(
            afterSequenceExclusive = afterSequenceExclusive,
            limit = safeEventLimit,
        )
        return NotificationDigestBuilder.build(
            events = page.events,
            afterSequenceExclusive = afterSequenceExclusive,
            maxEvents = safeEventLimit,
            maxUtf8Bytes = maxUtf8Bytes,
            sourceHasMore = page.hasMore,
        )
    }

    override fun close() {
        helper.close()
    }

    override fun privacyStatus(): NotificationPrivacyStatus =
        synchronized(NotificationPrivacyMutationCoordinator.lock) { privacyRepository.status() }

    override fun excludePackage(packageName: String): NotificationPrivacyMutation =
        synchronized(NotificationPrivacyMutationCoordinator.lock) {
            val safePackage = requireMutableNotificationPackage(packageName)
            var removed = prepareRawPrivacyMutation()
            // Policy is durable before archive begin. A crash in between stays behind the raw
            // fence; recovery/include/import reconcile policy exclusions without relying on inbox rows.
            val settings = privacyRepository.excludePackage(safePackage)
            removed += applyFactPrivacyIntent(
                beginFactPrivacyMutation(NotificationFactPrivacyScope.Package(safePackage)),
            )
            finishFactCoordinatedRawPurge()
            NotificationPrivacyMutation(settings, removed)
        }

    override fun includePackage(packageName: String): NotificationPrivacyMutation =
        synchronized(NotificationPrivacyMutationCoordinator.lock) {
            val safePackage = requireMutableNotificationPackage(packageName)
            val before = privacyRepository.status()
            var removed = prepareRawPrivacyMutation()
            if (safePackage in before.userExcludedPackages) {
                // Never reopen a previously excluded package before its archive/outbox purge.
                // This also closes a process-death gap after policy persistence but before begin.
                removed += applyFactPrivacyIntent(
                    beginFactPrivacyMutation(NotificationFactPrivacyScope.Package(safePackage)),
                )
            }
            val settings = privacyRepository.includePackage(safePackage)
            finishFactCoordinatedRawPurge()
            NotificationPrivacyMutation(settings, removed)
        }

    override fun setRetention(maxEvents: Int, maxAgeHours: Int): NotificationPrivacyMutation {
        return synchronized(NotificationPrivacyMutationCoordinator.lock) {
            check(beginGlobalPrivacyPurge()) { "notification_privacy_fence_unavailable" }
            val settings = privacyRepository.setRetention(maxEvents, maxAgeHours)
            val removed = helper.writableDatabase.transaction { prune(this) }
            if (removed > 0) finalizeSensitiveDeletion(helper.writableDatabase, compact = false)
            check(triageDataPurger.clearAll()) {
                "notification_retention_triage_purge_incomplete"
            }
            check(finishGlobalPrivacyPurge()) {
                "notification_privacy_fence_commit_failed"
            }
            NotificationPrivacyMutation(settings, removed)
        }
    }

    /** Existing transient recovery contract. Never begins or acknowledges an archive forget. */
    override fun clearHistory(): NotificationHistoryClearResult =
        synchronized(NotificationPrivacyMutationCoordinator.lock) {
            if (!beginGlobalPrivacyPurge()) {
                return NotificationHistoryClearResult(removedInboxEvents = 0, triageQueueCleared = false)
            }
            val result = clearTransientInboxAndTriage()
            result.copy(triageQueueCleared = result.triageQueueCleared && finishGlobalPrivacyPurge())
        }

    /** Explicit owner-requested all-data clear; native Codex memory/chats/profile are not involved. */
    override fun clearAllNotificationData(): NotificationAllDataClearResult =
        synchronized(NotificationPrivacyMutationCoordinator.lock) {
            check(beginGlobalPrivacyPurge()) { "notification_privacy_fence_unavailable" }
            val pending = factPrivacy.pending()
            // An unknown Queue-v4 document may contain durable candidates. Only this already
            // authorized All intent permits replacing it; ordinary raw recovery must not go first.
            val allIntent = pending.firstOrNull { it.scope == NotificationFactPrivacyScope.All }
                ?: beginFactPrivacyMutation(NotificationFactPrivacyScope.All)
            var removed = applyFactPrivacyIntent(allIntent)
            pending.filterNot { it == allIntent }.forEach { removed += applyFactPrivacyIntent(it) }
            finishFactCoordinatedRawPurge()
            NotificationAllDataClearResult(
                removedInboxEvents = removed,
                triageQueueCleared = true,
                factArchiveCleared = true,
                factCandidatesCleared = true,
                privacyMutationAcknowledged = true,
            )
        }

    /**
     * Startup/privacy retry hook. An existing raw fence retains its ordinary transient repair.
     * A Fact/Source-only retry does not create a raw fence or delete raw inbox history.
     * Unknown policy never becomes an implicit all-data forget.
     */
    internal fun recoverFactPrivacyMutations(): Boolean =
        synchronized(NotificationPrivacyMutationCoordinator.lock) {
            runCatching {
                val rawRecoveryRequired = privacyPurgeFence.isRequired()
                val pending = factPrivacy.pending()
                val hasRawIntent = pending.any {
                    it.scope == NotificationFactPrivacyScope.All ||
                        it.scope is NotificationFactPrivacyScope.Package
                }
                if (!rawRecoveryRequired && !hasRawIntent) {
                    pending.forEach { applyFactPrivacyIntent(it) }
                    check(factPrivacy.pending().isEmpty()) {
                        "notification_fact_privacy_recovery_incomplete"
                    }
                    return@runCatching privacyRepository.status().policyAvailable
                }

                check(beginGlobalPrivacyPurge()) { "notification_privacy_fence_unavailable" }
                val allIntents = pending.filter { it.scope == NotificationFactPrivacyScope.All }
                allIntents.forEach { applyFactPrivacyIntent(it) }
                if (rawRecoveryRequired && allIntents.isEmpty()) {
                    check(clearTransientInboxAndTriage().triageQueueCleared) {
                        "notification_privacy_purge_retry_incomplete"
                    }
                }
                pending.filterNot { it.scope == NotificationFactPrivacyScope.All }
                    .forEach { applyFactPrivacyIntent(it) }
                val status = privacyRepository.status()
                check(status.policyAvailable) { "notification_privacy_policy_unavailable" }
                val alreadyReconciledPackages = pending.mapNotNull {
                    (it.scope as? NotificationFactPrivacyScope.Package)?.packageName
                }.toSet()
                synchronizeExcludedFactPrivacy(
                    status.userExcludedPackages - alreadyReconciledPackages,
                )
                finishFactCoordinatedRawPurge()
                true
            }.getOrDefault(false)
        }

    override fun exportPrivacySettings(): String =
        synchronized(NotificationPrivacyMutationCoordinator.lock) {
            privacyRepository.exportRecoveryDocument()
        }

    override fun importPrivacySettings(document: String): NotificationPrivacyMutation =
        synchronized(NotificationPrivacyMutationCoordinator.lock) {
            // Validate before a new archive mutation. The repository repeats validation and
            // durably verifies the actual policy write.
            val recovered = NotificationPrivacyRecoveryCodec.decode(document)
            val before = privacyRepository.status()
            require(recovered.excludedPackages.none {
                privacyRepository.captureDecision(it) == NotificationCaptureDecision.ProtectedPackage
            }) { "recovery_contains_protected_package" }

            var removed = prepareRawPrivacyMutation()
            // Replacing policy can re-include old packages. Finish their previous purge first,
            // including the crash gap where no archive intent was created yet.
            removed += synchronizeExcludedFactPrivacy(before.userExcludedPackages)
            val settings = privacyRepository.importRecoveryDocument(document)
            // Independent of the retention branch: archive-only packages need the same purge.
            removed += synchronizeExcludedFactPrivacy(
                settings.excludedPackages - before.userExcludedPackages,
            )
            var removedByRetention = 0
            val removedRaw = helper.writableDatabase.transaction {
                val excluded = purgeExcluded(this)
                removedByRetention = prune(this)
                excluded + removedByRetention
            }
            removed += removedRaw
            if (removedRaw > 0) finalizeSensitiveDeletion(helper.writableDatabase, compact = false)
            val retentionTightened =
                settings.retention.maxEvents < before.retention.maxEvents ||
                    settings.retention.maxAgeHours < before.retention.maxAgeHours
            if (retentionTightened || removedByRetention > 0) {
                check(triageDataPurger.clearAll()) {
                    "notification_retention_triage_purge_incomplete"
                }
            } else {
                purgeExcludedTriage()
            }
            finishFactCoordinatedRawPurge()
            NotificationPrivacyMutation(settings, removed)
        }

    /** Shared raw-only work; archive state and validated Queue-v4 candidates stay untouched. */
    private fun clearTransientInboxAndTriage(): NotificationHistoryClearResult {
        val database = helper.writableDatabase
        var removedDatabaseRows = 0
        val removed = database.transaction {
            delete(TABLE_TRIAGE_OUTBOX, null, null)
            val eventCount = delete(TABLE_EVENTS, null, null)
            val stateCount = delete(TABLE_STATE, null, null)
            removedDatabaseRows = eventCount + stateCount
            eventCount
        }
        check(database.rawQuery(
            "SELECT (SELECT COUNT(*) FROM $TABLE_EVENTS) + " +
                "(SELECT COUNT(*) FROM $TABLE_STATE) + " +
                "(SELECT COUNT(*) FROM $TABLE_TRIAGE_OUTBOX)",
            null,
        ).use { it.moveToFirst() && it.getLong(0) == 0L }) {
            "notification_history_raw_purge_verification_failed"
        }
        if (removedDatabaseRows > 0) finalizeSensitiveDeletion(database, compact = true)
        return NotificationHistoryClearResult(removed, triageDataPurger.clearAll())
    }

    /** Leaves the fence REQUIRED until the enclosing owner-requested operation finishes. */
    private fun prepareRawPrivacyMutation(): Int {
        val rawRecoveryRequired = privacyPurgeFence.isRequired()
        check(beginGlobalPrivacyPurge()) { "notification_privacy_fence_unavailable" }
        var removed = 0
        val pending = factPrivacy.pending()
        val allIntents = pending.filter { it.scope == NotificationFactPrivacyScope.All }
        allIntents.forEach { removed += applyFactPrivacyIntent(it) }
        if (rawRecoveryRequired && allIntents.isEmpty()) {
            val result = clearTransientInboxAndTriage()
            check(result.triageQueueCleared) { "notification_privacy_purge_retry_incomplete" }
            removed += result.removedInboxEvents
        }
        pending.filterNot { it.scope == NotificationFactPrivacyScope.All }
            .forEach { removed += applyFactPrivacyIntent(it) }
        if (rawRecoveryRequired) {
            val status = privacyRepository.status()
            if (status.policyAvailable) {
                val alreadyReconciledPackages = pending.mapNotNull {
                    (it.scope as? NotificationFactPrivacyScope.Package)?.packageName
                }.toSet()
                removed += synchronizeExcludedFactPrivacy(
                    status.userExcludedPackages - alreadyReconciledPackages,
                )
            }
        }
        return removed
    }

    private fun requireMutableNotificationPackage(packageName: String): String {
        val safePackage = packageName.trim()
        check(privacyRepository.status().policyAvailable) { "notification_privacy_policy_unavailable" }
        when (privacyRepository.captureDecision(safePackage)) {
            NotificationCaptureDecision.Allowed,
            NotificationCaptureDecision.UserExcludedPackage,
            -> return safePackage
            NotificationCaptureDecision.ProtectedPackage ->
                error("protected_notification_package_cannot_be_changed")
            NotificationCaptureDecision.PolicyUnavailable ->
                error("invalid_notification_package")
        }
    }

    private fun synchronizeExcludedFactPrivacy(packages: Set<String>): Int =
        packages.sorted().sumOf { packageName ->
            applyFactPrivacyIntent(
                beginFactPrivacyMutation(NotificationFactPrivacyScope.Package(packageName)),
            )
        }

    private fun beginFactPrivacyMutation(scope: NotificationFactPrivacyScope): NotificationFactPrivacyIntent {
        val request = NotificationFactPrivacyRequest(UUID.randomUUID().toString(), scope)
        val result = factPrivacy.begin(request)
        val intent = when (result) {
            is NotificationFactPrivacyBeginResult.Pending -> result.intent
            is NotificationFactPrivacyBeginResult.Completed -> result.intent
            else -> error("notification_fact_privacy_begin_unavailable")
        }
        check(intent.mutationId == request.mutationId && intent.scope == scope) {
            "notification_fact_privacy_receipt_mismatch"
        }
        return intent
    }

    /** The archive/index deletion already committed before this method is reached. */
    private fun applyFactPrivacyIntent(intent: NotificationFactPrivacyIntent): Int {
        val allDataForget = intent.scope == NotificationFactPrivacyScope.All
        if (allDataForget) {
            // A strictly verified All purge can repair an unreadable Queue-v4 document. Raw
            // clearAll() intentionally cannot: an unreadable outbox is not an empty outbox.
            check(factPrivacy.purgeCandidateOutbox(intent)) {
                "notification_fact_candidate_purge_incomplete"
            }
        }
        val removed = when (val scope = intent.scope) {
            NotificationFactPrivacyScope.All -> {
                val result = clearTransientInboxAndTriage()
                check(result.triageQueueCleared) { "notification_privacy_purge_retry_incomplete" }
                result.removedInboxEvents
            }
            is NotificationFactPrivacyScope.Package -> {
                val database = helper.writableDatabase
                val count = database.transaction { purgePackage(this, scope.packageName) }
                check(database.rawQuery(
                    "SELECT 1 FROM $TABLE_EVENTS WHERE $COL_PACKAGE = ? UNION ALL " +
                        "SELECT 1 FROM $TABLE_STATE WHERE $COL_PACKAGE = ? LIMIT 1",
                    arrayOf(scope.packageName, scope.packageName),
                ).use { !it.moveToFirst() }) { "notification_package_raw_purge_verification_failed" }
                if (count > 0) finalizeSensitiveDeletion(database, compact = false)
                when (privacyRepository.captureDecision(scope.packageName)) {
                    NotificationCaptureDecision.UserExcludedPackage,
                    NotificationCaptureDecision.ProtectedPackage,
                    -> purgeExcludedTriage()
                    else -> check(triageDataPurger.clearAll()) {
                        "notification_package_triage_purge_incomplete"
                    }
                }
                count
            }
            // These scopes promise archive + matching validated candidates, not raw inbox/chats.
            is NotificationFactPrivacyScope.Source,
            is NotificationFactPrivacyScope.Fact,
            -> 0
        }
        if (!allDataForget) {
            check(factPrivacy.purgeCandidateOutbox(intent)) {
                "notification_fact_candidate_purge_incomplete"
            }
        }
        check(factPrivacy.ack(intent)) { "notification_fact_privacy_ack_incomplete" }
        check(factPrivacy.pending().none {
            it.storeEpoch == intent.storeEpoch && it.mutationId == intent.mutationId
        }) { "notification_fact_privacy_ack_verification_failed" }
        return removed
    }

    private fun finishFactCoordinatedRawPurge() {
        check(factPrivacy.pending().isEmpty()) { "notification_fact_privacy_recovery_incomplete" }
        check(finishGlobalPrivacyPurge()) { "notification_privacy_fence_commit_failed" }
    }

    private fun purgeExcludedTriage() {
        check(triageDataPurger.purgeExcluded()) {
            "notification_triage_privacy_purge_incomplete"
        }
    }

    private fun beginGlobalPrivacyPurge(): Boolean =
        privacyPurgeFence.markRequired() && privacyPurgeFence.isRequired()

    private fun finishGlobalPrivacyPurge(): Boolean =
        privacyPurgeFence.markClean() && !privacyPurgeFence.isRequired()

    private fun applySignal(
        database: SQLiteDatabase,
        signal: NotificationSignal,
        queueForRestrictedTriage: Boolean,
    ): NotificationWriteResult {
        val previous = readState(database, signal.androidKey)
        return when (val reduction = NotificationReducer.reduce(previous, signal)) {
            is NotificationReduction.IgnoreDuplicate -> NotificationWriteResult.Duplicate(
                reduction.lastSequence,
            )
            is NotificationReduction.Append -> {
                val sequence = database.insertOrThrow(
                    TABLE_EVENTS,
                    null,
                    reduction.event.toContentValues(),
                )
                check(sequence > 0) { "SQLite did not allocate a notification sequence" }
                val stateValues = reduction.event.snapshot.toContentValues().apply {
                    put(COL_ACTIVE, if (reduction.active) 1 else 0)
                    put(COL_FINGERPRINT, reduction.fingerprint)
                    put(COL_LAST_SEQUENCE, sequence)
                }
                database.insertWithOnConflict(
                    TABLE_STATE,
                    null,
                    stateValues,
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
                if (queueForRestrictedTriage) {
                    database.insertOrThrow(
                        TABLE_TRIAGE_OUTBOX,
                        null,
                        ContentValues().apply { put(COL_OUTBOX_SEQUENCE, sequence) },
                    )
                }
                NotificationWriteResult.Stored(sequence, reduction.event.kind)
            }
        }
    }

    private fun readState(
        database: SQLiteDatabase,
        androidKey: String,
    ): StoredNotificationState? = database.query(
        TABLE_STATE,
        STATE_QUERY_COLUMNS,
        "$COL_ANDROID_KEY = ?",
        arrayOf(androidKey),
        null,
        null,
        null,
        "1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val snapshot = cursor.readSnapshot(startIndex = 0)
        StoredNotificationState(
            snapshot = snapshot,
            active = cursor.getInt(SNAPSHOT_COLUMN_COUNT) != 0,
            fingerprint = cursor.getString(SNAPSHOT_COLUMN_COUNT + 1),
            lastSequence = cursor.getLong(SNAPSHOT_COLUMN_COUNT + 2),
        )
    }

    private fun containsEventSequence(database: SQLiteDatabase, sequence: Long): Boolean =
        database.rawQuery(
            "SELECT 1 FROM $TABLE_EVENTS WHERE $COL_SEQUENCE = ? LIMIT 1",
            arrayOf(sequence.toString()),
        ).use(Cursor::moveToFirst)

    private fun prune(
        database: SQLiteDatabase,
        nowEpochMillis: Long = clock().coerceAtLeast(0),
    ): Int {
        val retention = effectiveRetention()
        var removed = 0
        retention.maxAgeHours?.let { maxAgeHours ->
            val oldestAllowed = nowEpochMillis.coerceAtLeast(0) -
                TimeUnit.HOURS.toMillis(maxAgeHours.toLong())
            removed += database.delete(
                TABLE_EVENTS,
                "$COL_OBSERVED_AT < ?",
                arrayOf(oldestAllowed.toString()),
            )
        }
        val cutoff = database.rawQuery(
            """
            SELECT $COL_SEQUENCE
            FROM $TABLE_EVENTS
            ORDER BY $COL_SEQUENCE DESC
            LIMIT 1 OFFSET ?
            """.trimIndent(),
            arrayOf(retention.maxEvents.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }

        if (cutoff != null) {
            removed += database.delete(
                TABLE_EVENTS,
                "$COL_SEQUENCE <= ?",
                arrayOf(cutoff.toString()),
            )
        }
        database.delete(
            TABLE_STATE,
            "$COL_LAST_SEQUENCE NOT IN (SELECT $COL_SEQUENCE FROM $TABLE_EVENTS)",
            null,
        )
        deleteOrphanedTriageOutbox(database)
        return removed
    }

    /** Conservative preflight so the global fence is visible before any retention deletion. */
    private fun wouldPrune(
        database: SQLiteDatabase,
        nowEpochMillis: Long,
        prospectiveAdditionalEvents: Int,
        prospectiveObservedAtEpochMillis: Long? = null,
    ): Boolean {
        val retention = effectiveRetention()
        val countAtCapacity = database.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_EVENTS",
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use false
            val prospectiveCount = cursor.getLong(0) + prospectiveAdditionalEvents.coerceAtLeast(0)
            prospectiveCount > retention.maxEvents
        }
        if (countAtCapacity) return true
        val maxAgeHours = retention.maxAgeHours ?: return false
        val oldestAllowed = nowEpochMillis.coerceAtLeast(0) -
            TimeUnit.HOURS.toMillis(maxAgeHours.toLong())
        if (
            prospectiveAdditionalEvents > 0 &&
            prospectiveObservedAtEpochMillis != null &&
            prospectiveObservedAtEpochMillis < oldestAllowed
        ) return true
        return database.rawQuery(
            "SELECT 1 FROM $TABLE_EVENTS WHERE $COL_OBSERVED_AT < ? LIMIT 1",
            arrayOf(oldestAllowed.toString()),
        ).use(Cursor::moveToFirst)
    }

    private fun effectiveRetention(): EffectiveRetention {
        val fixed = fixedRetentionLimit
        if (fixed != null) {
            return EffectiveRetention(
                maxEvents = fixed,
                maxAgeHours = null,
            )
        }
        return privacyRepository.status().takeIf { it.policyAvailable }?.retention?.let {
            EffectiveRetention(it.maxEvents, it.maxAgeHours)
        } ?: EffectiveRetention(
                maxEvents = NotificationPrivacyBounds.MIN_MAX_EVENTS,
                maxAgeHours = NotificationPrivacyBounds.MIN_MAX_AGE_HOURS,
            )
    }

    private data class EffectiveRetention(
        val maxEvents: Int,
        val maxAgeHours: Int?,
    )

    private data class AcceptanceOutcome(
        val writeResult: NotificationWriteResult,
        val removedByRetention: Int,
    )

    private data class ReconcileOutcome(
        val removals: List<NotificationReconciledRemoval>,
        val removedByRetention: Int,
    )

    private fun purgeExcluded(database: SQLiteDatabase): Int {
        val packages = mutableSetOf<String>()
        database.query(
            TABLE_STATE,
            arrayOf(COL_PACKAGE),
            null,
            null,
            COL_PACKAGE,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val packageName = cursor.getString(0)
                if (privacyRepository.captureDecision(packageName) != NotificationCaptureDecision.Allowed) {
                    packages += packageName
                }
            }
        }
        return packages.sumOf { purgePackage(database, it) }
    }

    private fun purgePackage(database: SQLiteDatabase, packageName: String): Int {
        val removed = database.delete(
            TABLE_EVENTS,
            "$COL_PACKAGE = ?",
            arrayOf(packageName),
        )
        database.delete(
            TABLE_STATE,
            "$COL_PACKAGE = ?",
            arrayOf(packageName),
        )
        deleteOrphanedTriageOutbox(database)
        return removed
    }

    private fun deleteOrphanedTriageOutbox(database: SQLiteDatabase) {
        database.delete(
            TABLE_TRIAGE_OUTBOX,
            "$COL_OUTBOX_SEQUENCE NOT IN (SELECT $COL_SEQUENCE FROM $TABLE_EVENTS)",
            null,
        )
    }

    /** Best-effort removal of stale journal frames after a user-visible privacy deletion. */
    private fun finalizeSensitiveDeletion(database: SQLiteDatabase, compact: Boolean) {
        runCatching {
            database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                if (cursor.moveToFirst()) {
                    // A non-zero first column means another connection is busy. The logical
                    // deletion is already committed; a later privacy mutation retries cleanup.
                    cursor.getInt(0)
                }
            }
        }
        if (compact) runCatching { database.execSQL("VACUUM") }
    }

    private fun NotificationEventDraft.toContentValues(): ContentValues =
        snapshot.toContentValues().apply {
            put(COL_KIND, kind.name)
            put(COL_OBSERVED_AT, observedAtEpochMillis)
            if (removalReason == null) putNull(COL_REMOVAL_REASON) else put(COL_REMOVAL_REASON, removalReason)
        }

    private fun NotificationSnapshot.toContentValues(): ContentValues = ContentValues().apply {
        put(COL_PACKAGE, packageName)
        put(COL_ANDROID_KEY, androidKey)
        put(COL_POST_TIME, postTimeEpochMillis)
        put(COL_NOTIFICATION_WHEN, notificationWhenEpochMillis)
        put(COL_TITLE, title)
        put(COL_TEXT, text)
        put(COL_SUBTEXT, subtext)
        put(COL_CATEGORY, category)
        put(COL_CHANNEL, channelId)
        put(COL_ONGOING, if (ongoing) 1 else 0)
        put(COL_CLEARABLE, if (clearable) 1 else 0)
        put(COL_ACTIONS_JSON, NotificationActionJsonCodec.encode(actions))
    }

    private fun Cursor.readEvent(): NotificationInboxEvent = NotificationInboxEvent(
        sequence = getLong(0),
        kind = NotificationEventKind.valueOf(getString(1)),
        observedAtEpochMillis = getLong(2),
        removalReason = if (isNull(3)) null else getInt(3),
        snapshot = readSnapshot(startIndex = 4),
    )

    private fun Cursor.readSnapshot(startIndex: Int): NotificationSnapshot = NotificationSnapshot(
        packageName = getString(startIndex),
        androidKey = getString(startIndex + 1),
        postTimeEpochMillis = getLong(startIndex + 2),
        notificationWhenEpochMillis = getLong(startIndex + 3),
        title = getString(startIndex + 4),
        text = getString(startIndex + 5),
        subtext = getString(startIndex + 6),
        category = getString(startIndex + 7),
        channelId = getString(startIndex + 8),
        ongoing = getInt(startIndex + 9) != 0,
        clearable = getInt(startIndex + 10) != 0,
        actions = NotificationActionJsonCodec.decode(getString(startIndex + 11)),
    )

    companion object {
        const val DEFAULT_DATABASE_NAME = "notification_inbox.db"
        const val REMOVAL_REASON_RECONCILED = -1_000
        private const val LEGACY_MAX_RETENTION = 100_000
        private const val QUERY_CHUNK_SIZE = 256
        private const val MAX_QUERY_SCAN_EVENTS = 10_000

        private const val TABLE_EVENTS = "notification_events"
        private const val TABLE_STATE = "notification_state"
        private const val TABLE_TRIAGE_OUTBOX = "notification_triage_outbox"

        private const val COL_SEQUENCE = "sequence"
        private const val COL_KIND = "event_kind"
        private const val COL_OBSERVED_AT = "observed_at"
        private const val COL_REMOVAL_REASON = "removal_reason"
        private const val COL_PACKAGE = "package_name"
        private const val COL_ANDROID_KEY = "android_key"
        private const val COL_POST_TIME = "post_time"
        private const val COL_NOTIFICATION_WHEN = "notification_when"
        private const val COL_TITLE = "title"
        private const val COL_TEXT = "text"
        private const val COL_SUBTEXT = "subtext"
        private const val COL_CATEGORY = "category"
        private const val COL_CHANNEL = "channel_id"
        private const val COL_ONGOING = "ongoing"
        private const val COL_CLEARABLE = "clearable"
        private const val COL_ACTIONS_JSON = "actions_json"
        private const val COL_ACTIVE = "active"
        private const val COL_FINGERPRINT = "fingerprint"
        private const val COL_LAST_SEQUENCE = "last_sequence"
        private const val COL_OUTBOX_SEQUENCE = "event_sequence"
        private const val MAX_TRIAGE_OUTBOX_BATCH = 256

        private const val SNAPSHOT_COLUMNS = "$COL_PACKAGE, $COL_ANDROID_KEY, $COL_POST_TIME, " +
            "$COL_NOTIFICATION_WHEN, $COL_TITLE, $COL_TEXT, $COL_SUBTEXT, $COL_CATEGORY, " +
            "$COL_CHANNEL, $COL_ONGOING, $COL_CLEARABLE, $COL_ACTIONS_JSON"
        private const val SNAPSHOT_COLUMN_COUNT = 12
        private const val EVENT_COLUMNS = "$COL_SEQUENCE, $COL_KIND, $COL_OBSERVED_AT, " +
            "$COL_REMOVAL_REASON, $SNAPSHOT_COLUMNS"
        private val STATE_QUERY_COLUMNS = arrayOf(
            COL_PACKAGE,
            COL_ANDROID_KEY,
            COL_POST_TIME,
            COL_NOTIFICATION_WHEN,
            COL_TITLE,
            COL_TEXT,
            COL_SUBTEXT,
            COL_CATEGORY,
            COL_CHANNEL,
            COL_ONGOING,
            COL_CLEARABLE,
            COL_ACTIONS_JSON,
            COL_ACTIVE,
            COL_FINGERPRINT,
            COL_LAST_SEQUENCE,
        )
    }

    private class NotificationInboxOpenHelper(
        context: Context,
        databaseName: String,
    ) : SQLiteOpenHelper(context, databaseName, null, SCHEMA_VERSION) {
        override fun onConfigure(database: SQLiteDatabase) {
            super.onConfigure(database)
            // This PRAGMA returns a row on Android's SQLite build and therefore
            // must use the query path; execSQL is rejected on stock Android 14.
            database.rawQuery("PRAGMA secure_delete=ON", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getInt(0) == 1) {
                    "Could not enable secure deletion for the notification inbox"
                }
            }
        }

        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE $TABLE_EVENTS (
                    $COL_SEQUENCE INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_KIND TEXT NOT NULL,
                    $COL_OBSERVED_AT INTEGER NOT NULL,
                    $COL_REMOVAL_REASON INTEGER,
                    $COL_PACKAGE TEXT NOT NULL,
                    $COL_ANDROID_KEY TEXT NOT NULL,
                    $COL_POST_TIME INTEGER NOT NULL,
                    $COL_NOTIFICATION_WHEN INTEGER NOT NULL,
                    $COL_TITLE TEXT NOT NULL,
                    $COL_TEXT TEXT NOT NULL,
                    $COL_SUBTEXT TEXT NOT NULL,
                    $COL_CATEGORY TEXT NOT NULL,
                    $COL_CHANNEL TEXT NOT NULL,
                    $COL_ONGOING INTEGER NOT NULL,
                    $COL_CLEARABLE INTEGER NOT NULL,
                    $COL_ACTIONS_JSON TEXT NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE INDEX notification_events_key_sequence " +
                    "ON $TABLE_EVENTS ($COL_ANDROID_KEY, $COL_SEQUENCE)",
            )
            database.execSQL(
                """
                CREATE TABLE $TABLE_STATE (
                    $COL_ANDROID_KEY TEXT PRIMARY KEY NOT NULL,
                    $COL_PACKAGE TEXT NOT NULL,
                    $COL_POST_TIME INTEGER NOT NULL,
                    $COL_NOTIFICATION_WHEN INTEGER NOT NULL,
                    $COL_TITLE TEXT NOT NULL,
                    $COL_TEXT TEXT NOT NULL,
                    $COL_SUBTEXT TEXT NOT NULL,
                    $COL_CATEGORY TEXT NOT NULL,
                    $COL_CHANNEL TEXT NOT NULL,
                    $COL_ONGOING INTEGER NOT NULL,
                    $COL_CLEARABLE INTEGER NOT NULL,
                    $COL_ACTIONS_JSON TEXT NOT NULL,
                    $COL_ACTIVE INTEGER NOT NULL,
                    $COL_FINGERPRINT TEXT NOT NULL,
                    $COL_LAST_SEQUENCE INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE INDEX notification_state_active_sequence " +
                    "ON $TABLE_STATE ($COL_ACTIVE, $COL_LAST_SEQUENCE)",
            )
            createTriageOutboxTable(database)
        }

        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion == 1 && newVersion == 2) {
                createTriageOutboxTable(database)
                return
            }
            throw SQLiteException("Notification inbox migration missing: $oldVersion -> $newVersion")
        }

        override fun onDowngrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            throw SQLiteException(
                "Notification inbox downgrade unsupported: $oldVersion -> $newVersion",
            )
        }

        companion object {
            private const val SCHEMA_VERSION = 2

            private fun createTriageOutboxTable(database: SQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE $TABLE_TRIAGE_OUTBOX (
                        $COL_OUTBOX_SEQUENCE INTEGER PRIMARY KEY NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}

interface NotificationTriageDataPurger {
    fun clearAll(): Boolean
    fun purgeExcluded(): Boolean
}

/** Linearizes capture writes with privacy-policy mutations across every store instance. */
internal object NotificationPrivacyMutationCoordinator {
    val lock: Any = NotificationTriageTransactionCoordinator.lock
}

private class AndroidNotificationTriageDataPurger(
    private val appContext: Context,
    private val privacyRepository: NotificationPrivacyRepository,
) : NotificationTriageDataPurger {
    override fun clearAll(): Boolean {
        if (NotificationTriagePrivacyIntegration.clearAll()) return true
        val queueCleared = fallbackQueue().clearAll()
        val centerCleared = NotificationTriageIntegration.clearValidatedSuggestions()
        return queueCleared && centerCleared
    }

    override fun purgeExcluded(): Boolean {
        if (NotificationTriagePrivacyIntegration.purgeExcluded()) return true
        val queuePurged = runCatching { fallbackQueue().purgeExcluded() }.isSuccess
        // The validated store is intentionally package-blind; conservatively invalidate every
        // pending summary and require that durable privacy generation to advance successfully.
        val centerCleared = NotificationTriageIntegration.clearValidatedSuggestions()
        return queuePurged && centerCleared
    }

    private fun fallbackQueue(): NotificationTriageQueue = NotificationTriageQueue(
        storage = AtomicFileNotificationTriageStorage(appContext),
        exclusionPolicy = HansNotificationExclusionPolicy(
            ownPackageNames = setOf(appContext.packageName),
            additionalExclusion = { packageName ->
                privacyRepository.captureDecision(packageName) != NotificationCaptureDecision.Allowed
            },
        ),
    )
}

data class NotificationReconciledRemoval(
    val signal: NotificationSignal.Removed,
    val writeResult: NotificationWriteResult,
)
