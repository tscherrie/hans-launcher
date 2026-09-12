package ai.hans.standard.integration

import ai.hans.standard.notifications.NotificationUrgency
import ai.hans.standard.notifications.NotificationTriageBounds
import ai.hans.standard.notifications.NotificationValidatedDeliveryAuthority
import ai.hans.standard.notifications.UserFacingNotificationDelivery
import ai.hans.standard.notifications.UserFacingNotificationActivationDisposition
import ai.hans.standard.phone.notifications.AtomicNotificationPrivacyPurgeFence
import ai.hans.standard.phone.notifications.NotificationPrivacyPurgeFence
import ai.hans.standard.phone.notifications.NotificationPrivacyRepository
import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArraySet
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_TIMELINE_ID_CHARACTERS = 512
private const val MAX_SUMMARY_CHARACTERS = 768
private const val MAX_CONTEXT_RESERVATION_ID_CHARACTERS = 256
private val VALIDATED_SUPERSESSION_KEY = Regex("source:[0-9a-f]{64}")
private val VALIDATED_ANNOUNCEMENT_ID = Regex("notification:[0-9a-f]{64}")
private const val EXPIRED_CONTEXT_RESERVATION_SUMMARY = "Expired notification reservation."
private const val EXPIRED_ACTIVATION_RECEIPT_SUMMARY = "Expired notification activation receipt."

enum class ValidatedNotificationActivationTombstone {
    ACTIVE,
    SUPPRESSED,
}

/** Only restricted-triage output is stored here; original notification text never crosses in. */
data class ValidatedNotificationAnnouncement(
    val id: String,
    val summary: String,
    val urgency: NotificationUrgency,
    val createdAtEpochMillis: Long,
    /** Irreversible source grouping key; raw package/Android key never enters this store. */
    val supersessionKey: String = "",
    /** Last visible Codex timeline item when this local summary arrived. */
    val timelineAnchorId: String? = null,
    /** Null until the complete announcement has actually finished playing. */
    val spokenAtEpochMillis: Long? = null,
    /** Null until the validated summary has been attached to an accepted interactive turn. */
    val contextInjectedAtEpochMillis: Long? = null,
    /**
     * Exact outbound client message which owns this context until a correlated SENT/FAILED
     * receipt arrives. Persisting this before dispatch makes process/runtime loss fail closed.
     */
    val contextReservationId: String? = null,
    /**
     * Android removed/superseded this output while its outbound context receipt was ambiguous.
     * Keep only the durable correlation; UI, TTS and future context reads must remain silent.
     */
    val outputRevoked: Boolean = false,
    /** Queue-owned staging fence. Staged records are private and never reach UI/context/TTS. */
    val activationPending: Boolean = false,
    /**
     * True from durable stage until the Queue confirms its matching terminal write. This receipt
     * is kept independently from the configured notification-content retention window.
     */
    val activationReceiptPending: Boolean = false,
    /** Exact Queue-owned expiry for an unacknowledged activation receipt. */
    val activationExpiresAtEpochMillis: Long? = null,
    /** Content-erased terminal state; never eligible for UI, context, or speech. */
    val activationTombstone: ValidatedNotificationActivationTombstone? = null,
    /** Durable privacy epoch; records from an older clear/exclusion epoch are always invisible. */
    val privacyGeneration: Long = -1L,
    /** Original local Queue intake time; null identifies legacy/unproven intake provenance. */
    val sourceReceivedAtEpochMillis: Long? = null,
    /** Speech was intentionally skipped; this is not a successful playback receipt. */
    val speechSuppressed: Boolean = false,
)

fun interface ValidatedNotificationAnnouncementObserver {
    fun onAnnouncementsChanged(items: List<ValidatedNotificationAnnouncement>)
}

internal sealed interface NotificationAnnouncementRetentionRead {
    data class Available(val maxAgeHours: Int) : NotificationAnnouncementRetentionRead
    data object Unavailable : NotificationAnnouncementRetentionRead
    /** Test-only/default seam for callers that do not own Android privacy policy state. */
    data object Unbounded : NotificationAnnouncementRetentionRead
}

class ValidatedNotificationAnnouncementCenter internal constructor(
    private val storage: ValidatedAnnouncementStorage,
    private val privacyGenerationStore: NotificationPrivacyGenerationStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val privacyFence: NotificationAnnouncementPrivacyFence =
        InMemoryNotificationAnnouncementPrivacyFence(),
    private val deliveryFence: NotificationPrivacyPurgeFence =
        InMemoryCleanNotificationPrivacyPurgeFence(),
    private val authoritativeDeliveryReady: () -> Boolean = { true },
    private val retentionRead: () -> NotificationAnnouncementRetentionRead = {
        NotificationAnnouncementRetentionRead.Unbounded
    },
    /** Production always fences pre-session arrivals; null is a legacy-behavior test seam. */
    initialSpeechSuppressionCutoffEpochMillis: Long? = null,
) {
    constructor(
        context: Context,
        clock: () -> Long = System::currentTimeMillis,
        fileName: String = FILE_NAME,
        deliveryFenceFileName: String = AtomicNotificationPrivacyPurgeFence.FILE_NAME,
    ) : this(
        storage = AtomicValidatedAnnouncementStorage(
            File(context.applicationContext.noBackupFilesDir, fileName),
        ),
        privacyGenerationStore = AtomicNotificationPrivacyGenerationStore(
            File(context.applicationContext.noBackupFilesDir, "$fileName.privacy-generation"),
        ),
        clock = clock,
        privacyFence = AtomicNotificationAnnouncementPrivacyFence(
            File(context.applicationContext.noBackupFilesDir, "$fileName.privacy-fence"),
        ),
        deliveryFence = AtomicNotificationPrivacyPurgeFence(
            context.applicationContext,
            deliveryFenceFileName,
        ),
        authoritativeDeliveryReady = NotificationValidatedDeliveryAuthority::isReady,
        retentionRead = {
            val status = NotificationPrivacyRepository(context.applicationContext).status()
            if (status.policyAvailable) {
                NotificationAnnouncementRetentionRead.Available(status.retention.maxAgeHours)
            } else {
                NotificationAnnouncementRetentionRead.Unavailable
            }
        },
        initialSpeechSuppressionCutoffEpochMillis = clock().coerceAtLeast(0L),
    )

    private val observers = CopyOnWriteArraySet<ValidatedNotificationAnnouncementObserver>()
    /** Process-local fail-closed fence when a privacy/revocation disk write cannot complete. */
    private val suppressedIds = linkedSetOf<String>()
    private var speechSuppressionCutoffEpochMillis =
        initialSpeechSuppressionCutoffEpochMillis?.coerceAtLeast(0L)
    /** Speech-only fail-closed fallback; never hides visible summaries or notification memory. */
    private val speechSuppressedIds = linkedSetOf<String>()

    init {
        // Missing/corrupt fence state is privacy-blocked. A new install or a prior interrupted
        // deletion becomes usable only after old records are durably emptied first.
        ensurePrivacyFenceReady()
        initialSpeechSuppressionCutoffEpochMillis?.let { suppressSpeechThrough(it) }
    }

    @Synchronized
    fun accept(
        delivery: UserFacingNotificationDelivery,
        timelineAnchorId: String? = null,
    ): Boolean {
        if (!stage(delivery, timelineAnchorId)) return false
        val disposition = activateResult(delivery.idempotencyKey)
        if (disposition != UserFacingNotificationActivationDisposition.ACTIVE) return false
        return finalizeActivation(delivery.idempotencyKey, disposition)
    }

    /**
     * Durably stages a restricted, validated suggestion without publishing it. Queue delivery
     * activates the exact idempotency key only after its lease completion is durable.
     */
    @Synchronized
    fun stage(
        delivery: UserFacingNotificationDelivery,
        timelineAnchorId: String? = null,
    ): Boolean {
        if (
            !authoritativeDeliveryReady() ||
            !ensurePrivacyFenceReady() ||
            deliveryFence.isRequired()
        ) return false
        val privacyGeneration = privacyGenerationStore.current() ?: return false
        val id = stableId(delivery.idempotencyKey)
        val current = retainedRecordsOrQuarantine() ?: return false
        current.firstOrNull {
            it.id == id && it.privacyGeneration == privacyGeneration
        }?.let { existing ->
            // A failed queue commit may retry the same still-staged id. An activated item that was
            // suppressed by a privacy/update tombstone is never revived by a duplicate delivery.
            if (existing.activationPending && existing.activationTombstone == null) {
                suppressedIds.remove(id)
            }
            return true
        }
        val summary = delivery.suggestion.summary
            .replace(Regex("[\\t\\r\\n ]+"), " ")
            .trim()
            .take(MAX_SUMMARY_CHARACTERS)
        if (summary.isBlank()) return false
        val safeSupersessionKey = delivery.supersessionKey
            .takeIf(VALIDATED_SUPERSESSION_KEY::matches)
            .orEmpty()
        val safeAnchor = timelineAnchorId
            ?.takeIf { it.isNotBlank() && it.length <= MAX_TIMELINE_ID_CHARACTERS }
            ?.takeUnless { it.any(Char::isISOControl) }
        val activationExpiresAt = delivery.activationExpiresAtEpochMillis
            .takeIf { it >= 0L }
            ?: return false
        val retained = current
            .asSequence()
            .filter { it.privacyGeneration == privacyGeneration }
            .mapNotNull { existing ->
                if (existing.id == id) return@mapNotNull null
                val superseded = safeSupersessionKey.isNotBlank() &&
                    existing.supersessionKey == safeSupersessionKey &&
                    (existing.spokenAtEpochMillis == null ||
                        existing.contextInjectedAtEpochMillis == null)
                when {
                    !superseded -> existing
                    existing.contextReservationId != null -> existing.copy(outputRevoked = true)
                    existing.activationReceiptPending -> existing.toActivationTombstone()
                    else -> null
                }
            }
            .toList()
        val receivedAt = delivery.sourceReceivedAtEpochMillis?.takeIf { it >= 0L }
        val stagedAt = clock().coerceAtLeast(0L)
        val addition = ValidatedNotificationAnnouncement(
            id = id,
            summary = summary,
            urgency = delivery.suggestion.urgency,
            createdAtEpochMillis = stagedAt,
            supersessionKey = safeSupersessionKey,
            timelineAnchorId = safeAnchor,
            activationPending = true,
            activationReceiptPending = true,
            activationExpiresAtEpochMillis = activationExpiresAt,
            privacyGeneration = privacyGeneration,
            sourceReceivedAtEpochMillis = receivedAt,
            speechSuppressed = receivedAt != delivery.sourceReceivedAtEpochMillis ||
                shouldSuppressSpeech(receivedAt, stagedAt),
        )
        val requiredEvictions = (retained.size + 1 - MAX_ANNOUNCEMENTS).coerceAtLeast(0)
        val evictableIds = retained.asSequence()
            .filter {
                (it.spokenAtEpochMillis != null || it.speechSuppressed) &&
                    it.contextReservationId == null &&
                    !it.activationReceiptPending
            }
            .take(requiredEvictions)
            .mapTo(linkedSetOf(), ValidatedNotificationAnnouncement::id)
        // Never discard a live private item merely to make room. Backpressure is safer than loss.
        if (evictableIds.size != requiredEvictions) return false
        val next = retained.filterNot { it.id in evictableIds } + addition
        if (!storage.write(next)) return false
        // A failed revoke advanced the durable privacy generation and left only a process-local
        // tombstone. A newly persisted record in the fresh generation is a distinct authorized
        // retry and may become visible after its exact queue activation.
        suppressedIds.remove(id)
        // A staged replacement may have removed a formerly visible stale summary. Publish only
        // that removal; the new staged content itself remains private until activate().
        publish(visible(next))
        return true
    }

    @Synchronized
    fun activate(idempotencyKey: String): Boolean =
        activateResult(idempotencyKey) == UserFacingNotificationActivationDisposition.ACTIVE

    @Synchronized
    internal fun activateResult(
        idempotencyKey: String,
    ): UserFacingNotificationActivationDisposition {
        if (
            !authoritativeDeliveryReady() ||
            !ensurePrivacyFenceReady() ||
            deliveryFence.isRequired()
        ) {
            return UserFacingNotificationActivationDisposition.RETRY
        }
        val privacyGeneration = privacyGenerationStore.current()
            ?: return UserFacingNotificationActivationDisposition.RETRY
        val id = stableId(idempotencyKey)
        val current = retainedRecordsOrQuarantine()
            ?: return UserFacingNotificationActivationDisposition.RETRY
        val index = current.indexOfFirst {
            it.id == id && it.privacyGeneration == privacyGeneration
        }
        if (index < 0) return UserFacingNotificationActivationDisposition.RETRY
        val existing = current[index]
        when (existing.activationTombstone) {
            ValidatedNotificationActivationTombstone.ACTIVE ->
                return UserFacingNotificationActivationDisposition.ACTIVE
            ValidatedNotificationActivationTombstone.SUPPRESSED ->
                return UserFacingNotificationActivationDisposition.SUPPRESSED
            null -> Unit
        }
        if (!existing.activationPending) {
            return UserFacingNotificationActivationDisposition.ACTIVE
        }
        val next = current.toMutableList().also { items ->
            items[index] = items[index].copy(activationPending = false)
        }
        if (!storage.write(next)) return UserFacingNotificationActivationDisposition.RETRY
        publish(visible(next))
        return UserFacingNotificationActivationDisposition.ACTIVE
    }

    /**
     * Acknowledges only an already-durable Queue terminal transition. Active content remains in
     * normal retention; a content-erased receipt is physically removed. Missing is idempotently
     * safe because privacy clear or a prior finalization may already have destroyed it.
     */
    @Synchronized
    internal fun finalizeActivation(
        idempotencyKey: String,
        disposition: UserFacingNotificationActivationDisposition,
    ): Boolean {
        if (disposition == UserFacingNotificationActivationDisposition.RETRY) return false
        if (!ensurePrivacyFenceReady()) return false
        val generation = privacyGenerationStore.current() ?: return false
        val id = stableId(idempotencyKey)
        val current = retainedRecordsOrQuarantine() ?: return false
        val index = current.indexOfFirst {
            it.id == id && it.privacyGeneration == generation
        }
        if (index < 0) return true
        val item = current[index]
        val matches = when (disposition) {
            UserFacingNotificationActivationDisposition.ACTIVE ->
                item.activationTombstone != ValidatedNotificationActivationTombstone.SUPPRESSED &&
                    !item.activationPending
            UserFacingNotificationActivationDisposition.SUPPRESSED ->
                item.activationTombstone == ValidatedNotificationActivationTombstone.SUPPRESSED
            UserFacingNotificationActivationDisposition.RETRY -> false
        }
        if (!matches) return false
        if (!item.activationReceiptPending) return true
        val next = if (item.activationTombstone != null) {
            current.toMutableList().also { it.removeAt(index) }
        } else {
            current.toMutableList().also { items ->
                items[index] = item.copy(
                    activationReceiptPending = false,
                    activationExpiresAtEpochMillis = null,
                )
            }
        }
        if (!storage.write(next)) return false
        // Receipt acknowledgement changes no user-visible projection. Publishing here would
        // replay every still-visible announcement to observers after the Queue terminal write.
        return true
    }

    @Synchronized
    fun snapshot(): List<ValidatedNotificationAnnouncement> = visible(currentRecords())

    /** Internal privacy/supersession operations must also see not-yet-activated records. */
    @Synchronized
    internal fun allRecords(): List<ValidatedNotificationAnnouncement> = currentRecords()

    @Synchronized
    fun pendingSpeech(): List<ValidatedNotificationAnnouncement> {
        val now = clock().coerceAtLeast(0L)
        return currentRecords().filter {
            // A backward clock correction or damaged/future provenance must never age into a
            // playable backlog later. Stage/startup persist this flag; this is the read barrier.
            if (shouldSuppressSpeech(it.sourceReceivedAtEpochMillis, now)) {
                speechSuppressedIds += it.id
            }
            !it.activationPending &&
                it.activationTombstone == null &&
                !it.outputRevoked &&
                it.id !in suppressedIds &&
                it.id !in speechSuppressedIds &&
                !it.speechSuppressed &&
                it.spokenAtEpochMillis == null
        }
    }

    /**
     * Permanently skips speech for arrivals through this local intake boundary, including work
     * which is still being triaged and will only be staged later. UI, context, facts and actual
     * playback receipts are untouched. Callers must stop/revoke physical audio separately.
     *
     * The in-process barrier advances before disk I/O. Failure remains silent immediately; a
     * production Center recreation establishes its own fresh-session barrier before any speech.
     * No observer callback or Queue operation is invoked while holding the Center monitor.
     */
    @Synchronized
    fun suppressSpeechThrough(cutoffEpochMillis: Long): Boolean {
        val cutoff = cutoffEpochMillis.coerceAtLeast(0L)
        speechSuppressionCutoffEpochMillis =
            maxOf(speechSuppressionCutoffEpochMillis ?: cutoff, cutoff)
        val current = runCatching {
            (storage.readStatus() as? ValidatedAnnouncementStorageRead.Available)?.items
        }.getOrNull() ?: return false
        // Only records still physically present can need a fallback ID. The receipt-time
        // watermark independently prevents restaging an older, already-evicted delivery.
        speechSuppressedIds.retainAll(current.mapTo(hashSetOf()) { it.id })
        val now = clock().coerceAtLeast(0L)
        val next = current.map { item ->
            if (item.spokenAtEpochMillis == null &&
                (item.id in speechSuppressedIds ||
                    shouldSuppressSpeech(item.sourceReceivedAtEpochMillis, now))
            ) {
                speechSuppressedIds += item.id
                item.copy(speechSuppressed = true)
            } else {
                item
            }
        }
        return runCatching {
            (next == current || storage.write(next)) &&
                (storage.readStatus() as? ValidatedAnnouncementStorageRead.Available)?.items == next
        }.getOrDefault(false)
    }

    private fun shouldSuppressSpeech(receivedAt: Long?, now: Long): Boolean = when {
        receivedAt == null -> speechSuppressionCutoffEpochMillis != null
        receivedAt < 0L || receivedAt > now -> true
        else -> speechSuppressionCutoffEpochMillis?.let { receivedAt <= it } == true
    }

    @Synchronized
    fun pendingContext(
        limit: Int = MAX_CONTEXT_ITEMS_PER_TURN,
        excludingIds: Set<String> = emptySet(),
    ): List<ValidatedNotificationAnnouncement> {
        require(limit in 1..MAX_CONTEXT_ITEMS_PER_TURN)
        return currentRecords()
            .asSequence()
            .filter {
                !it.activationPending &&
                    it.activationTombstone == null &&
                    !it.outputRevoked &&
                    it.id !in suppressedIds &&
                    it.id !in excludingIds &&
                    it.contextReservationId == null &&
                    it.contextInjectedAtEpochMillis == null
            }
            .take(limit)
            .toList()
    }

    /** Removes only not-yet-spoken/not-yet-injected stale updates; completed history is immutable. */
    @Synchronized
    fun removePendingBySupersessionKey(supersessionKey: String): Boolean {
        if (!VALIDATED_SUPERSESSION_KEY.matches(supersessionKey)) return false
        if (deliveryFence.isRequired()) return false
        val current = currentRecordsForMutation() ?: return false
        val next = current.mapNotNull { item ->
            val revoked = item.supersessionKey == supersessionKey &&
                (item.spokenAtEpochMillis == null ||
                    item.contextInjectedAtEpochMillis == null)
            when {
                !revoked -> item
                item.contextReservationId != null -> item.copy(outputRevoked = true)
                item.activationReceiptPending -> item.toActivationTombstone()
                else -> null
            }
        }
        if (next == current) return true
        val removedIds = current.asSequence().map(ValidatedNotificationAnnouncement::id).toSet() -
            next.asSequence().map(ValidatedNotificationAnnouncement::id).toSet()
        suppress(removedIds)
        if (!beginDurablePrivacyMutation()) {
            publish(emptyList())
            return false
        }
        val persisted = storage.write(next)
        val completed = persisted && privacyFence.markClean() && !privacyFence.isRequired()
        publish(if (completed) visible(next) else emptyList())
        return completed
    }

    @Synchronized
    fun revokePending(idempotencyKey: String): Boolean {
        if (deliveryFence.isRequired()) return false
        val id = stableId(idempotencyKey)
        val current = currentRecordsForMutation() ?: return false
        val next = current.mapNotNull { item ->
            val revoked = item.id == id &&
                (item.spokenAtEpochMillis == null || item.contextInjectedAtEpochMillis == null)
            when {
                !revoked -> item
                item.contextReservationId != null -> item.copy(outputRevoked = true)
                else -> null
            }
        }
        if (next == current) return true
        suppress(setOf(id))
        if (!beginDurablePrivacyMutation()) {
            publish(emptyList())
            return false
        }
        val persisted = storage.write(next)
        val completed = persisted && privacyFence.markClean() && !privacyFence.isRequired()
        if (completed) {
            // Queue-commit rollback is retryable; a later durable delivery of the same receipt may
            // stage again after this exact physical deletion succeeded.
            suppressedIds.remove(id)
        }
        publish(if (completed) visible(next) else emptyList())
        return completed
    }

    @Synchronized
    fun clearAll(): Boolean {
        val current = currentRecordsForMutation().orEmpty()
        suppress(current.mapTo(linkedSetOf()) { it.id })
        if (!beginDurablePrivacyMutation()) {
            publish(emptyList())
            return false
        }
        val persisted = storage.write(emptyList())
        val verifiedEmpty =
            (storage.readStatus() as? ValidatedAnnouncementStorageRead.Available)
                ?.items
                ?.isEmpty() == true
        if (!persisted || !verifiedEmpty) {
            publish(emptyList())
            return false
        }
        val generationAdvanced = privacyGenerationStore.resetAfterDestructiveClear() != null
        val completed = generationAdvanced && privacyFence.markClean() &&
            !privacyFence.isRequired()
        publish(emptyList())
        return completed
    }

    /**
     * Completion, rather than queue admission, is persisted. A process death or playback failure
     * therefore leaves the announcement available for a later safe retry.
     */
    @Synchronized
    fun markSpoken(id: String): Boolean {
        if (
            !authoritativeDeliveryReady() ||
            !ensurePrivacyFenceReady() ||
            deliveryFence.isRequired()
        ) return false
        val current = retainedRecordsOrQuarantine() ?: return false
        val generation = privacyGenerationStore.current() ?: return false
        val index = current.indexOfFirst { it.id == id && it.privacyGeneration == generation }
        if (index < 0) return false
        if (
            current[index].activationPending ||
            current[index].activationTombstone != null ||
            current[index].outputRevoked
        ) return false
        if (current[index].spokenAtEpochMillis != null) return true
        val next = current.toMutableList().also { items ->
            items[index] = items[index].copy(spokenAtEpochMillis = clock().coerceAtLeast(0))
        }
        if (!storage.write(next)) {
            // Audio has already crossed the irreversible user boundary. Never leave the old
            // pending-speech record replayable after a failed completion write. A durable local
            // fence makes every announcement invisible now and destroys the stale store on reopen.
            suppress(setOf(id))
            beginDurablePrivacyMutation()
            publish(emptyList())
            return false
        }
        publish(visible(next))
        return true
    }

    /**
     * Marks only a batch whose exact outbound message received a correlated SENT/turn receipt.
     * If dispatch is rejected or transport remains ambiguous, the summaries stay pending.
     */
    @Synchronized
    fun markContextInjected(ids: Set<String>, reservationId: String? = null): Boolean {
        if (ids.isEmpty()) return true
        if (
            !authoritativeDeliveryReady() ||
            !ensurePrivacyFenceReady() ||
            deliveryFence.isRequired()
        ) return false
        val current = retainedRecordsOrQuarantine() ?: return false
        val generation = privacyGenerationStore.current() ?: return false
        if (!ids.all { id ->
                current.any {
                    it.id == id &&
                        it.privacyGeneration == generation &&
                        !it.activationPending &&
                        it.activationTombstone == null &&
                        it.contextReservationId == reservationId
                }
            }
        ) return false
        val injectedAt = clock().coerceAtLeast(0)
        val next = current.mapNotNull { item ->
            if (
                item.privacyGeneration == generation &&
                item.id in ids &&
                !item.activationPending &&
                item.activationTombstone == null &&
                item.contextInjectedAtEpochMillis == null
            ) {
                if (item.outputRevoked) {
                    if (item.activationReceiptPending) {
                        item.copy(contextReservationId = null).toActivationTombstone()
                    } else {
                        null
                    }
                } else {
                    item.copy(
                        contextInjectedAtEpochMillis = injectedAt,
                        contextReservationId = null,
                    )
                }
            } else {
                item
            }
        }
        if (!storage.write(next)) {
            // SENT is already irreversible. Never leave the same summary durably reinjectable if
            // its completion receipt cannot be recorded: suppress now and require destructive
            // recovery on this process or the next one.
            suppress(ids)
            beginDurablePrivacyMutation()
            publish(emptyList())
            return false
        }
        publish(visible(next))
        return true
    }

    /**
     * Reserves the exact batch durably before any App Server frame can leave the process. A stale
     * reservation after an ambiguous crash intentionally remains fail closed and can be resolved
     * only by a correlated terminal receipt or an explicit privacy clear.
     */
    @Synchronized
    fun reserveContext(ids: Set<String>, reservationId: String): Boolean {
        if (ids.isEmpty()) return true
        if (!validContextReservationId(reservationId)) return false
        if (
            !authoritativeDeliveryReady() ||
            !ensurePrivacyFenceReady() ||
            deliveryFence.isRequired()
        ) return false
        val current = retainedRecordsOrQuarantine() ?: return false
        val generation = privacyGenerationStore.current() ?: return false
        if (!ids.all { id ->
                current.any { item ->
                        item.id == id &&
                        item.privacyGeneration == generation &&
                        !item.activationPending &&
                        item.activationTombstone == null &&
                        !item.outputRevoked &&
                        item.contextInjectedAtEpochMillis == null &&
                        (item.contextReservationId == null ||
                            item.contextReservationId == reservationId)
                }
            }
        ) return false
        val next = current.map { item ->
            if (item.privacyGeneration == generation && item.id in ids) {
                item.copy(contextReservationId = reservationId)
            } else {
                item
            }
        }
        if (!storage.write(next)) return false
        publish(visible(next))
        return true
    }

    /** Releases only a definitively FAILED dispatch; ambiguity deliberately keeps the reservation. */
    @Synchronized
    fun releaseContextReservation(ids: Set<String>, reservationId: String): Boolean {
        if (ids.isEmpty()) return true
        if (!validContextReservationId(reservationId)) return false
        if (
            !authoritativeDeliveryReady() ||
            !ensurePrivacyFenceReady() ||
            deliveryFence.isRequired()
        ) return false
        val current = retainedRecordsOrQuarantine() ?: return false
        val generation = privacyGenerationStore.current() ?: return false
        if (!ids.all { id ->
                current.any {
                    it.id == id &&
                        it.privacyGeneration == generation &&
                        it.contextReservationId == reservationId
                }
            }
        ) return false
        val next = current.mapNotNull { item ->
            if (
                item.privacyGeneration == generation &&
                item.id in ids &&
                item.contextReservationId == reservationId
            ) {
                if (item.outputRevoked) {
                    if (item.activationReceiptPending) {
                        item.copy(contextReservationId = null).toActivationTombstone()
                    } else {
                        null
                    }
                } else {
                    item.copy(contextReservationId = null)
                }
            } else {
                item
            }
        }
        if (!storage.write(next)) return false
        publish(visible(next))
        return true
    }

    @Synchronized
    fun contextReservations(): Map<String, Set<String>> = currentRecords()
        .asSequence()
        .filter {
                !it.activationPending &&
                it.contextInjectedAtEpochMillis == null &&
                it.contextReservationId != null
        }
        .groupBy { requireNotNull(it.contextReservationId) }
        .mapValues { (_, items) -> items.mapTo(linkedSetOf(), ValidatedNotificationAnnouncement::id) }

    fun addObserver(observer: ValidatedNotificationAnnouncementObserver) {
        observers += observer
        runCatching { observer.onAnnouncementsChanged(snapshot()) }
    }

    fun removeObserver(observer: ValidatedNotificationAnnouncementObserver) {
        observers -= observer
    }

    private fun publish(items: List<ValidatedNotificationAnnouncement>) {
        observers.forEach { observer ->
            runCatching { observer.onAnnouncementsChanged(items) }
        }
    }

    private fun currentRecords(): List<ValidatedNotificationAnnouncement> {
        if (
            !authoritativeDeliveryReady() ||
            deliveryFence.isRequired() ||
            !ensurePrivacyFenceReady()
        ) return emptyList()
        val generation = privacyGenerationStore.current() ?: return emptyList()
        return retainedRecordsOrQuarantine().orEmpty()
            .filter { it.privacyGeneration == generation }
    }

    /**
     * Trusted snapshot/outbox revocation remains available while user delivery is quarantined.
     * This path never publishes data; it exists solely to prove a durable stale-record deletion
     * before the listener may reopen the authoritative delivery gate.
     */
    private fun currentRecordsForMutation(): List<ValidatedNotificationAnnouncement>? {
        if (!ensurePrivacyFenceReady()) return null
        val generation = privacyGenerationStore.current() ?: return null
        return availableRecordsOrQuarantine()
            ?.filter { it.privacyGeneration == generation }
    }

    private fun visible(items: List<ValidatedNotificationAnnouncement>): List<ValidatedNotificationAnnouncement> {
        if (
            !authoritativeDeliveryReady() ||
            deliveryFence.isRequired() ||
            privacyFence.isRequired()
        ) return emptyList()
        val generation = privacyGenerationStore.current() ?: return emptyList()
        return items.filter {
            it.privacyGeneration == generation &&
                !it.activationPending &&
                it.activationTombstone == null &&
                !it.outputRevoked &&
                it.id !in suppressedIds
        }
    }

    private fun suppress(ids: Set<String>) {
        suppressedIds += ids
        while (suppressedIds.size > MAX_SUPPRESSED_IDS) {
            suppressedIds.firstOrNull()?.let(suppressedIds::remove) ?: break
        }
    }

    private fun beginDurablePrivacyMutation(): Boolean {
        val marked = privacyFence.markRequired()
        return marked && privacyFence.isRequired()
    }

    /**
     * Recovery always destroys old summaries before a durable local fence can become clean again.
     * A malformed announcement document or privacy generation also raises the shared delivery
     * fence. That second fence deliberately remains REQUIRED until the listener coordinator has
     * cleared the durable triage queue and this center together; a committed queue receipt can
     * therefore never spin forever trying to activate a stage that disappeared during recovery.
     */
    private fun ensurePrivacyFenceReady(): Boolean {
        val initialRead = storage.readStatus()
        val generationReadable = privacyGenerationStore.current() != null
        val durableStateUnavailable =
            initialRead is ValidatedAnnouncementStorageRead.Unavailable || !generationReadable
        val localRecoveryRequired = privacyFence.isRequired()
        if (durableStateUnavailable || localRecoveryRequired) {
            val localMarked = localRecoveryRequired || privacyFence.markRequired()
            val deliveryMarked = deliveryFence.markRequired()
            publish(emptyList())
            if (
                !localMarked ||
                !deliveryMarked ||
                !privacyFence.isRequired() ||
                !deliveryFence.isRequired()
            ) {
                return false
            }
        }
        if (!privacyFence.isRequired()) return true
        val knownRecords = (initialRead as? ValidatedAnnouncementStorageRead.Available)
            ?.items
            .orEmpty()
        suppress(knownRecords.mapTo(linkedSetOf()) { it.id })
        val storageCleared = storage.write(emptyList())
        val storageVerifiedEmpty =
            (storage.readStatus() as? ValidatedAnnouncementStorageRead.Available)
                ?.items
                ?.isEmpty() == true
        if (!storageCleared || !storageVerifiedEmpty) return false
        val generationAdvanced = privacyGenerationStore.resetAfterDestructiveClear() != null
        if (!generationAdvanced) return false
        val clean = privacyFence.markClean() && !privacyFence.isRequired()
        if (clean) suppressedIds.clear()
        return clean
    }

    private fun availableRecordsOrQuarantine(): List<ValidatedNotificationAnnouncement>? {
        return when (val read = storage.readStatus()) {
            is ValidatedAnnouncementStorageRead.Available -> read.items
            ValidatedAnnouncementStorageRead.Unavailable -> {
                privacyFence.markRequired()
                deliveryFence.markRequired()
                publish(emptyList())
                null
            }
        }
    }

    /**
     * Center records are a derived copy of inbox content and must obey the same maximum age even
     * when Android delivers no later notification that would wake the inbox retention pass. Every
     * UI, speech and context read crosses this durable gate. An unreadable policy hides all output
     * behind both privacy fences; an expired record is physically removed before any retained
     * record is returned.
     */
    private fun retainedRecordsOrQuarantine(): List<ValidatedNotificationAnnouncement>? {
        val current = availableRecordsOrQuarantine() ?: return null
        val maxAgeMillis = when (val retention = retentionRead()) {
            NotificationAnnouncementRetentionRead.Unbounded -> null
            NotificationAnnouncementRetentionRead.Unavailable -> {
                privacyFence.markRequired()
                deliveryFence.markRequired()
                suppress(current.mapTo(linkedSetOf()) { it.id })
                publish(emptyList())
                return null
            }
            is NotificationAnnouncementRetentionRead.Available -> {
                if (retention.maxAgeHours <= 0) {
                    privacyFence.markRequired()
                    deliveryFence.markRequired()
                    suppress(current.mapTo(linkedSetOf()) { it.id })
                    publish(emptyList())
                    return null
                }
                TimeUnit.HOURS.toMillis(retention.maxAgeHours.toLong())
            }
        }
        val now = clock().coerceAtLeast(0L)
        val oldestAllowed = maxAgeMillis?.let { (now - it).coerceAtLeast(0L) }
        val retained = current.mapNotNull { item ->
            val receiptExpired = item.activationReceiptPending &&
                item.activationExpiresAtEpochMillis?.let { now >= it } == true
            val afterReceiptExpiry = when {
                !receiptExpired -> item
                item.activationPending || item.activationTombstone != null -> null
                else -> item.copy(
                    activationReceiptPending = false,
                    activationExpiresAtEpochMillis = null,
                )
            } ?: return@mapNotNull null
            when {
                oldestAllowed == null ||
                    afterReceiptExpiry.createdAtEpochMillis >= oldestAllowed -> afterReceiptExpiry
                afterReceiptExpiry.contextReservationId != null -> afterReceiptExpiry.copy(
                    summary = EXPIRED_CONTEXT_RESERVATION_SUMMARY,
                    urgency = NotificationUrgency.LOW,
                    supersessionKey = "",
                    timelineAnchorId = null,
                    spokenAtEpochMillis = null,
                    outputRevoked = true,
                )
                afterReceiptExpiry.activationReceiptPending ->
                    afterReceiptExpiry.toActivationTombstone()
                else -> null
            }
        }
        if (retained == current) return current

        val changedIds = current.asSequence()
            .filter { old ->
                val replacement = retained.firstOrNull { it.id == old.id }
                replacement == null ||
                    replacement.activationTombstone != null ||
                    replacement.outputRevoked
            }
            .mapTo(linkedSetOf(), ValidatedNotificationAnnouncement::id)
        suppress(changedIds)
        if (!beginDurablePrivacyMutation()) {
            publish(emptyList())
            return null
        }
        val persisted = storage.write(retained)
        val verified = (storage.readStatus() as? ValidatedAnnouncementStorageRead.Available)
            ?.items == retained
        val completed = persisted && verified && privacyFence.markClean() &&
            !privacyFence.isRequired()
        when {
            !completed -> publish(emptyList())
            changedIds.isNotEmpty() -> publish(visible(retained))
        }
        return retained.takeIf { completed }
    }

    private fun ValidatedNotificationAnnouncement.toActivationTombstone():
        ValidatedNotificationAnnouncement {
        val disposition = activationTombstone ?: if (activationPending) {
            ValidatedNotificationActivationTombstone.SUPPRESSED
        } else {
            ValidatedNotificationActivationTombstone.ACTIVE
        }
        return copy(
            summary = EXPIRED_ACTIVATION_RECEIPT_SUMMARY,
            urgency = NotificationUrgency.LOW,
            supersessionKey = "",
            timelineAnchorId = null,
            spokenAtEpochMillis = null,
            contextInjectedAtEpochMillis = null,
            contextReservationId = null,
            outputRevoked = false,
            activationPending =
                disposition == ValidatedNotificationActivationTombstone.SUPPRESSED,
            activationReceiptPending = true,
            activationTombstone = disposition,
        )
    }

    private fun stableId(idempotencyKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(idempotencyKey.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "notification:$digest"
    }

    private fun validContextReservationId(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= MAX_CONTEXT_RESERVATION_ID_CHARACTERS &&
            value.none(Char::isISOControl)

    private companion object {
        const val FILE_NAME = "validated-notification-announcements.json"
        const val MAX_ANNOUNCEMENTS = 100
        const val MAX_SUPPRESSED_IDS = 200
        const val MAX_CONTEXT_ITEMS_PER_TURN = 8
    }
}

internal interface ValidatedAnnouncementStorage {
    fun read(): List<ValidatedNotificationAnnouncement>
    fun readStatus(): ValidatedAnnouncementStorageRead =
        ValidatedAnnouncementStorageRead.Available(read())
    fun write(items: List<ValidatedNotificationAnnouncement>): Boolean
}

internal sealed interface ValidatedAnnouncementStorageRead {
    data class Available(
        val items: List<ValidatedNotificationAnnouncement>,
    ) : ValidatedAnnouncementStorageRead

    data object Unavailable : ValidatedAnnouncementStorageRead
}

internal interface NotificationAnnouncementPrivacyFence {
    /** Missing/corrupt state is required in the durable implementation. */
    fun isRequired(): Boolean
    fun markRequired(): Boolean
    fun markClean(): Boolean
}

private class InMemoryNotificationAnnouncementPrivacyFence :
    NotificationAnnouncementPrivacyFence {
    private var required = false
    override fun isRequired(): Boolean = required
    override fun markRequired(): Boolean {
        required = true
        return true
    }
    override fun markClean(): Boolean {
        required = false
        return true
    }
}

private class InMemoryCleanNotificationPrivacyPurgeFence : NotificationPrivacyPurgeFence {
    private var required = false
    override fun isRequired(): Boolean = required
    override fun markRequired(): Boolean {
        required = true
        return true
    }
    override fun markClean(): Boolean {
        required = false
        return true
    }
}

private class AtomicNotificationAnnouncementPrivacyFence(file: File) :
    NotificationAnnouncementPrivacyFence {
    private val stateFile = file
    private val atomic = AtomicFile(file)

    @Synchronized
    override fun isRequired(): Boolean {
        if (!stateFile.isFile && !File("${stateFile.path}.bak").isFile) return true
        return runCatching { atomic.readFully().toString(StandardCharsets.UTF_8) }.getOrNull() !=
            CLEAN_STATE
    }

    @Synchronized
    override fun markRequired(): Boolean {
        val written = writeAndVerify(REQUIRED_STATE) { isRequired() }
        if (!written) {
            // Missing/corrupt is REQUIRED on reopen; never leave a stale clean marker after a
            // failed transition into the deletion fence.
            runCatching { atomic.baseFile.delete() }
            runCatching { File("${atomic.baseFile.path}.bak").delete() }
            runCatching { File("${atomic.baseFile.path}.new").delete() }
        }
        return written
    }

    @Synchronized
    override fun markClean(): Boolean = writeAndVerify(CLEAN_STATE) { !isRequired() }

    private fun writeAndVerify(value: String, verify: () -> Boolean): Boolean {
        val output = runCatching { atomic.startWrite() }.getOrElse { return false }
        val written = try {
            output.write(value.toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
            atomic.finishWrite(output)
            true
        } catch (_: Exception) {
            atomic.failWrite(output)
            false
        }
        return written && verify()
    }

    private companion object {
        const val CLEAN_STATE = "clean-v1"
        const val REQUIRED_STATE = "required-v1"
    }
}

private class AtomicValidatedAnnouncementStorage(
    private val stateFile: File,
) : ValidatedAnnouncementStorage {
    private val atomic = AtomicFile(stateFile)

    @Synchronized
    override fun read(): List<ValidatedNotificationAnnouncement> =
        (readStatus() as? ValidatedAnnouncementStorageRead.Available)?.items.orEmpty()

    @Synchronized
    override fun readStatus(): ValidatedAnnouncementStorageRead {
        if (!stateFile.isFile && !File("${stateFile.path}.bak").isFile) {
            return ValidatedAnnouncementStorageRead.Unavailable
        }
        return runCatching {
            val bytes = atomic.readFully()
            require(bytes.size <= MAX_FILE_BYTES)
            val array = JSONArray(String(bytes, StandardCharsets.UTF_8))
            require(array.length() <= MAX_RECORDS)
            buildList {
                repeat(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    val keys = item.keys().asSequence().toSet()
                    val legacyReceiptSchema = keys == PRE_ACTIVATION_RECEIPT_KEYS
                    val legacySpeechSchema = keys == PRE_SPEECH_SUPPRESSION_KEYS ||
                        legacyReceiptSchema
                    require(keys == CANONICAL_KEYS || legacySpeechSchema)
                    val id = exactString(item, "id").also {
                        require(VALIDATED_ANNOUNCEMENT_ID.matches(it))
                    }
                    val summary = exactString(item, "summary").also {
                        require(validSummary(it))
                    }
                    val createdAt = exactNonNegativeLong(item, "createdAt")
                    val supersessionKey = exactString(item, "supersessionKey").also {
                        require(it.isEmpty() || VALIDATED_SUPERSESSION_KEY.matches(it))
                    }
                    val timelineAnchorId = exactNullableString(item, "timelineAnchorId")
                        ?.also { require(validTimelineAnchor(it)) }
                    val spokenAt = exactNullableNonNegativeLong(item, "spokenAt")
                    val sourceReceivedAt = if (legacySpeechSchema) null else
                        exactNullableNonNegativeLong(item, "sourceReceivedAt")
                    val speechSuppressed = if (legacySpeechSchema) spokenAt == null else
                        exactBoolean(item, "speechSuppressed")
                    val contextInjectedAt =
                        exactNullableNonNegativeLong(item, "contextInjectedAt")
                    val contextReservationId =
                        exactNullableString(item, "contextReservationId")
                            ?.also { require(validReservationId(it)) }
                    val outputRevoked = exactBoolean(item, "outputRevoked")
                    val activationPending = exactBoolean(item, "activationPending")
                    val activationReceiptPending = if (legacyReceiptSchema) {
                        true
                    } else {
                        exactBoolean(item, "activationReceiptPending")
                    }
                    val activationExpiresAt = if (legacyReceiptSchema) {
                        createdAt.saturatingQueueHorizon()
                    } else {
                        exactNullableNonNegativeLong(item, "activationExpiresAt")
                    }
                    val activationTombstone = if (legacyReceiptSchema) {
                        null
                    } else {
                        exactNullableString(item, "activationTombstone")?.let {
                            ValidatedNotificationActivationTombstone.valueOf(it)
                        }
                    }
                    val privacyGeneration = exactPositiveLong(item, "privacyGeneration")
                    require(contextInjectedAt == null || contextReservationId == null)
                    require(!outputRevoked || contextReservationId != null)
                    require(activationReceiptPending == (activationExpiresAt != null))
                    require(!activationPending || activationReceiptPending)
                    if (activationPending) {
                        require(
                            spokenAt == null &&
                                contextInjectedAt == null &&
                                contextReservationId == null
                        )
                    }
                    add(
                        ValidatedNotificationAnnouncement(
                            id = id,
                            summary = summary,
                            urgency = NotificationUrgency.valueOf(
                                exactString(item, "urgency"),
                            ),
                            createdAtEpochMillis = createdAt,
                            supersessionKey = supersessionKey,
                            timelineAnchorId = timelineAnchorId,
                            spokenAtEpochMillis = spokenAt,
                            contextInjectedAtEpochMillis = contextInjectedAt,
                            contextReservationId = contextReservationId,
                            outputRevoked = outputRevoked,
                            activationPending = activationPending,
                            activationReceiptPending = activationReceiptPending,
                            activationExpiresAtEpochMillis = activationExpiresAt,
                            activationTombstone = activationTombstone,
                            privacyGeneration = privacyGeneration,
                            sourceReceivedAtEpochMillis = sourceReceivedAt,
                            speechSuppressed = speechSuppressed,
                        ).also(::requireCanonical),
                    )
                }
            }.also { items ->
                require(items.map(ValidatedNotificationAnnouncement::id).toSet().size == items.size)
            }.let { items -> ValidatedAnnouncementStorageRead.Available(items) }
        }.getOrDefault(ValidatedAnnouncementStorageRead.Unavailable)
    }

    @Synchronized
    override fun write(items: List<ValidatedNotificationAnnouncement>): Boolean = runCatching {
        require(items.size <= MAX_RECORDS)
        require(items.map(ValidatedNotificationAnnouncement::id).toSet().size == items.size)
        items.forEach(::requireCanonical)
        val bytes = JSONArray().also { array ->
            items.forEach { item ->
                array.put(
                    JSONObject()
                        .put("id", item.id)
                        .put("summary", item.summary)
                        .put("urgency", item.urgency.name)
                        .put("createdAt", item.createdAtEpochMillis)
                        .put("supersessionKey", item.supersessionKey)
                        .put("timelineAnchorId", item.timelineAnchorId ?: JSONObject.NULL)
                        .put("spokenAt", item.spokenAtEpochMillis ?: JSONObject.NULL)
                        .put("sourceReceivedAt", item.sourceReceivedAtEpochMillis ?: JSONObject.NULL)
                        .put("speechSuppressed", item.speechSuppressed)
                        .put(
                            "contextInjectedAt",
                            item.contextInjectedAtEpochMillis ?: JSONObject.NULL,
                        )
                        .put(
                            "contextReservationId",
                            item.contextReservationId ?: JSONObject.NULL,
                        )
                        .put("outputRevoked", item.outputRevoked)
                        .put("activationPending", item.activationPending)
                        .put("activationReceiptPending", item.activationReceiptPending)
                        .put(
                            "activationExpiresAt",
                            item.activationExpiresAtEpochMillis ?: JSONObject.NULL,
                        )
                        .put(
                            "activationTombstone",
                            item.activationTombstone?.name ?: JSONObject.NULL,
                        )
                        .put("privacyGeneration", item.privacyGeneration),
                )
            }
        }.toString().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
        true
    }.getOrDefault(false)

    private fun requireCanonical(item: ValidatedNotificationAnnouncement) {
        require(VALIDATED_ANNOUNCEMENT_ID.matches(item.id))
        require(validSummary(item.summary))
        require(item.createdAtEpochMillis >= 0L)
        require(
            item.supersessionKey.isEmpty() ||
                VALIDATED_SUPERSESSION_KEY.matches(item.supersessionKey)
        )
        item.timelineAnchorId?.let { require(validTimelineAnchor(it)) }
        item.spokenAtEpochMillis?.let { require(it >= 0L) }
        item.sourceReceivedAtEpochMillis?.let { require(it >= 0L) }
        item.contextInjectedAtEpochMillis?.let { require(it >= 0L) }
        item.contextReservationId?.let { require(validReservationId(it)) }
        item.activationExpiresAtEpochMillis?.let { require(it >= 0L) }
        require(item.contextInjectedAtEpochMillis == null || item.contextReservationId == null)
        require(!item.outputRevoked || item.contextReservationId != null)
        require(
            item.activationReceiptPending == (item.activationExpiresAtEpochMillis != null),
        )
        require(!item.activationPending || item.activationReceiptPending)
        require(item.privacyGeneration > 0L)
        if (item.activationPending) {
            require(
                item.spokenAtEpochMillis == null &&
                    item.contextInjectedAtEpochMillis == null &&
                item.contextReservationId == null
            )
        }
        item.activationTombstone?.let { tombstone ->
            require(item.activationReceiptPending)
            require(item.summary == EXPIRED_ACTIVATION_RECEIPT_SUMMARY)
            require(item.urgency == NotificationUrgency.LOW)
            require(item.supersessionKey.isEmpty())
            require(item.timelineAnchorId == null)
            require(item.spokenAtEpochMillis == null)
            require(item.contextInjectedAtEpochMillis == null)
            require(item.contextReservationId == null)
            require(!item.outputRevoked)
            require(
                item.activationPending ==
                    (tombstone == ValidatedNotificationActivationTombstone.SUPPRESSED),
            )
        }
    }

    private fun exactString(item: JSONObject, key: String): String =
        (item.get(key) as? String) ?: error("$key must be a string")

    private fun exactNullableString(item: JSONObject, key: String): String? {
        val value = item.get(key)
        return when (value) {
            JSONObject.NULL -> null
            is String -> value
            else -> error("$key must be a string or null")
        }
    }

    private fun exactBoolean(item: JSONObject, key: String): Boolean =
        (item.get(key) as? Boolean) ?: error("$key must be a boolean")

    private fun exactNonNegativeLong(item: JSONObject, key: String): Long =
        exactIntegralLong(item, key).also { require(it >= 0L) }

    private fun exactPositiveLong(item: JSONObject, key: String): Long =
        exactIntegralLong(item, key).also { require(it > 0L) }

    private fun exactNullableNonNegativeLong(item: JSONObject, key: String): Long? {
        if (item.get(key) === JSONObject.NULL) return null
        return exactNonNegativeLong(item, key)
    }

    private fun exactIntegralLong(item: JSONObject, key: String): Long {
        val value = item.get(key)
        require(value is Number && value !is Float && value !is Double)
        return value.toLong()
    }

    private fun validSummary(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= MAX_SUMMARY_CHARACTERS &&
            value.none(Char::isISOControl)

    private fun validTimelineAnchor(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= MAX_TIMELINE_ID_CHARACTERS &&
            value.none(Char::isISOControl)

    private fun validReservationId(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= MAX_CONTEXT_RESERVATION_ID_CHARACTERS &&
            value.none(Char::isISOControl)

    private fun Long.saturatingQueueHorizon(): Long =
        coerceAtMost(Long.MAX_VALUE - NotificationTriageBounds.MAX_BACKLOG_AGE_MILLIS) +
            NotificationTriageBounds.MAX_BACKLOG_AGE_MILLIS

    private companion object {
        const val MAX_RECORDS = 100
        const val MAX_FILE_BYTES = 256 * 1_024
        val CANONICAL_KEYS = setOf(
            "id",
            "summary",
            "urgency",
            "createdAt",
            "timelineAnchorId",
            "spokenAt",
            "contextInjectedAt",
            "contextReservationId",
            "outputRevoked",
            "supersessionKey",
            "activationPending",
            "activationReceiptPending",
            "activationExpiresAt",
            "activationTombstone",
            "privacyGeneration",
            "sourceReceivedAt",
            "speechSuppressed",
        )
        val PRE_SPEECH_SUPPRESSION_KEYS = CANONICAL_KEYS - setOf(
            "sourceReceivedAt",
            "speechSuppressed",
        )
        val PRE_ACTIVATION_RECEIPT_KEYS = PRE_SPEECH_SUPPRESSION_KEYS - setOf(
            "activationReceiptPending",
            "activationExpiresAt",
            "activationTombstone",
        )
    }
}

internal interface NotificationPrivacyGenerationStore {
    /** Null means missing/unreadable/corrupt and therefore requires a coordinated purge. */
    fun current(): Long?
    /** Atomically advances and verifies the durable privacy barrier. */
    fun advance(): Long?
    /** Valid only after announcement storage was durably cleared and verified empty. */
    fun resetAfterDestructiveClear(): Long? = advance()
}

internal class AtomicNotificationPrivacyGenerationStore(
    private val stateFile: File,
) :
    NotificationPrivacyGenerationStore {
    private val atomic = AtomicFile(stateFile)

    @Synchronized
    override fun current(): Long? {
        if (!stateFile.isFile && !File("${stateFile.path}.bak").isFile) return null
        val bytes = runCatching { atomic.readFully() }.getOrElse { return null }
        if (bytes.isEmpty() || bytes.size > MAX_GENERATION_BYTES) return null
        return bytes.toString(StandardCharsets.UTF_8).toLongOrNull()?.takeIf { it > 0 }
    }

    @Synchronized
    override fun advance(): Long? {
        val current = current() ?: return null
        if (current == Long.MAX_VALUE) return null
        return writeAndVerify(current + 1)
    }

    @Synchronized
    override fun resetAfterDestructiveClear(): Long? {
        val next = current()?.takeIf { it < Long.MAX_VALUE }?.plus(1) ?: 1L
        return writeAndVerify(next)
    }

    private fun writeAndVerify(next: Long): Long? {
        val output = runCatching { atomic.startWrite() }.getOrElse { return null }
        return try {
            output.write(next.toString().toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
            atomic.finishWrite(output)
            next.takeIf { current() == next }
        } catch (_: Exception) {
            atomic.failWrite(output)
            null
        }
    }

    private companion object {
        const val MAX_GENERATION_BYTES = 32
    }
}
