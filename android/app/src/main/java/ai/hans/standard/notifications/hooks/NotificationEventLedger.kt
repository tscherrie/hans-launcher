package ai.hans.standard.notifications.hooks

import ai.hans.standard.phone.notifications.NotificationEventKind
import ai.hans.standard.phone.notifications.NotificationInboxEvent
import ai.hans.standard.notifications.sanitizeRestrictedNotificationText
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Transport bookkeeping and technical update coalescing only; never decides relevance. */
internal class NotificationEventLedger(
    private val storage: NotificationEventStorage,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    @Volatile private var lastError: String? = null

    /** Install-time migration: existing inbox/outbox contents must not launch historical tasks. */
    @Synchronized fun activateAfter(maxExistingSequence: Long, sourceEvent: (Long) -> NotificationInboxEvent? = { null }): Boolean {
        if (maxExistingSequence < 0) return failure("activation_sequence_invalid")
        val state = read() ?: return false
        if (state.activationSequence != null) {
            val compacted = NotificationAgentUpdateIdentity.compactReady(state, sourceEvent)
            return if (compacted == state) true else write(compacted)
        }
        return write(state.copy(activationSequence = maxExistingSequence,
            lastObservedSequence = maxExistingSequence, generation = newId()))
    }

    /** True only after a verified durable commit (or an already committed exact source sequence). */
    @Synchronized fun accept(event: NotificationInboxEvent): Boolean {
        val state = read() ?: return false
        if (state.activationSequence == null) return failure("activation_required")
        if (event.sequence <= 0 || event.observedAtEpochMillis < 0) return failure("source_event_invalid")
        val existing = state.records.firstOrNull { it.sequence == event.sequence }
        if (existing != null) {
            val payload = NotificationExternalEventPayload.encode(existing.eventId, event,
                includeStatusMetadata = existing.payloadJson.isEmpty() || JSONObject(existing.payloadJson).has("ongoing"))
            // Old receipts retain their exact hash after payload erasure. Match the original
            // supported encoding as well, never infer acceptance from sequence alone.
            return if (notificationEventSha256(payload) == existing.payloadSha256 || existing.payloadJson.isEmpty() &&
                notificationEventSha256(NotificationExternalEventPayload.encode(existing.eventId, event,
                    includeStatusMetadata = false)) == existing.payloadSha256) true
            else failure("source_sequence_conflict")
        }
        // This is the source transaction cursor, not content/relevance deduplication. The listener
        // drains its SQLite outbox in ascending order and ACKs only after this durable commit.
        if (event.sequence <= state.lastObservedSequence) return true
        if (event.kind == NotificationEventKind.REMOVED) {
            return write(inactivate(state, event.snapshot.packageName, event.snapshot.androidKey)
                .copy(lastObservedSequence = event.sequence))
        }
        val eventId = notificationEventSha256("${state.generation}:${event.sequence}")
        val payload = NotificationExternalEventPayload.encode(eventId, event)
        if (payload.toByteArray(Charsets.UTF_8).size > NotificationEventLimits.MAX_PAYLOAD_BYTES)
            return failure("event_payload_bounds")
        val identity = NotificationAgentUpdateIdentity.fromEvent(event)
        val prior = state.records.filter { it.packageName == event.snapshot.packageName &&
            it.androidKey == event.snapshot.androidKey }.maxByOrNull { it.sequence }
        val sameUpdate = event.kind == NotificationEventKind.UPDATED && prior?.sourceActive == true &&
            identity != null && identity == (prior.updateFingerprint ?: NotificationAgentUpdateIdentity.fromPayload(prior.payloadJson))
        if (sameUpdate && prior!!.phase != NotificationEventPhase.READY) {
            return write(state.copy(lastObservedSequence = event.sequence,
                technicalUpdateCount = NotificationAgentUpdateIdentity.increment(state.technicalUpdateCount)))
        }
        var retained = state.records.filter { record ->
            record.settledAtEpochMillis == null || clock().coerceAtLeast(0) < record.settledAtEpochMillis ||
                clock().coerceAtLeast(0) - record.settledAtEpochMillis <= NotificationEventLimits.TOMBSTONE_MILLIS
        }
        if (sameUpdate && prior!!.correlation == null) retained = retained.filterNot { it.eventId == prior.eventId }
        if (retained.size >= NotificationEventLimits.MAX_RECORDS) {
            // The durable source cursor already prevents replay after settlement. Under capacity
            // pressure old terminal tombstones may compact immediately; live/uncertain events
            // must NEVER be sacrificed to make room for another notification.
            val compactable = retained.filter { it.settledAtEpochMillis != null }
                .sortedWith(compareBy<NotificationEventRecord> { it.settledAtEpochMillis }.thenBy { it.sequence })
                .take(retained.size - NotificationEventLimits.MAX_RECORDS + 1).map { it.eventId }.toSet()
            retained = retained.filter { it.eventId !in compactable }
        }
        if (retained.size >= NotificationEventLimits.MAX_RECORDS) return failure("ledger_full")
        val record = NotificationEventRecord(event.sequence, eventId, event.snapshot.packageName,
            event.snapshot.androidKey, payload, notificationEventSha256(payload), NotificationEventPhase.READY,
            sourceActive = true, updateFingerprint = identity)
        return write(state.copy(lastObservedSequence = event.sequence, records = retained + record,
            technicalUpdateCount = if (sameUpdate) NotificationAgentUpdateIdentity.increment(state.technicalUpdateCount)
                else state.technicalUpdateCount))
    }

    @Synchronized fun pending(): List<NotificationEventRecord> = read()?.records
        ?.filter { it.phase == NotificationEventPhase.READY && it.sourceActive }.orEmpty()

    @Synchronized fun unsettled(): List<NotificationEventRecord> = read()?.records
        ?.filter { it.phase in UNSETTLED }.orEmpty()

    /** Claim identity is durable before ANY transport call, including an asynchronous one. */
    @Synchronized fun claim(eventId: String, expectedThreadId: String): NotificationEventRecord? {
        if (!validCorrelation(expectedThreadId)) { failure("thread_identity_invalid"); return null }
        val state = read() ?: return null
        val record = state.records.firstOrNull { it.eventId == eventId && it.sourceActive &&
            it.phase == NotificationEventPhase.READY } ?: return null
        val claimed = record.copy(phase = NotificationEventPhase.CLAIMED,
            correlation = NotificationEventCorrelation(expectedThreadId, newId()))
        return if (write(state.replacing(claimed))) claimed else null
    }

    @Synchronized fun mayTransmit(claim: NotificationEventRecord): Boolean = read()?.records?.any {
        it.eventId == claim.eventId && it.phase == NotificationEventPhase.CLAIMED && it.sourceActive &&
            it.payloadSha256 == claim.payloadSha256 && it.correlation == claim.correlation
    } == true

    /** Fresh source/privacy lease only. The host independently requires the real native ACK. */
    @Synchronized fun hasLiveReportSource(eventId: String, threadId: String, packageName: String,
        androidKey: String): Boolean = read()?.records?.any {
        it.eventId == eventId && it.sourceActive && it.packageName == packageName && it.androidKey == androidKey &&
            it.correlation?.threadId == threadId && it.phase !in setOf(NotificationEventPhase.READY,
                NotificationEventPhase.CANCELLED)
    } == true

    /** Called only after the Host's actual native ACK/caller proof, under the privacy boundary. */
    @Synchronized fun commitReport(eventId: String, threadId: String, turnId: String,
        report: NotificationEventReport): NotificationEventReport? {
        val state = read() ?: return null
        val record = state.records.firstOrNull { it.eventId == eventId && it.sourceActive &&
            it.correlation?.threadId == threadId && (it.correlation.turnId == null || it.correlation.turnId == turnId) }
            ?: return null
        record.report?.let { existing ->
            return if (existing.textSha256 == report.textSha256) existing else null
        }
        val committed = record.copy(report = report, correlation = record.correlation!!.copy(turnId = turnId),
            phase = if (record.phase in UNSETTLED) NotificationEventPhase.ACCEPTED else record.phase, payloadJson = "")
        return if (write(state.replacing(committed))) report else null
    }

    @Synchronized fun reportFor(eventId: String, threadId: String, turnId: String): NotificationEventReport? =
        read()?.records?.firstOrNull { it.eventId == eventId && it.sourceActive &&
            it.correlation?.threadId == threadId && it.correlation.turnId == turnId }?.report

    @Synchronized fun reportSourceFor(eventId: String, threadId: String, turnId: String): Pair<String, String>? =
        read()?.records?.firstOrNull { it.eventId == eventId && it.sourceActive &&
            it.correlation?.threadId == threadId && it.correlation.turnId == turnId }?.let { it.packageName to it.androidKey }

    @Synchronized fun reports(threadId: String?, allowedPackage: (String) -> Boolean = { true }): List<NotificationEventReport> = read()?.records
        ?.filter { it.sourceActive && it.correlation?.threadId == threadId && allowedPackage(it.packageName) }
        ?.mapNotNull { it.report }.orEmpty()

    /** Only a positive not-transmitted result permits a future, real-event retry. */
    @Synchronized fun releaseUnsent(claim: NotificationEventRecord): Boolean {
        val state = read() ?: return false
        val record = state.exactClaim(claim) ?: return false
        if (record.phase != NotificationEventPhase.CLAIMED) return false
        return write(state.replacing(record.copy(phase = if (record.sourceActive) NotificationEventPhase.READY
            else NotificationEventPhase.CANCELLED, correlation = null,
            settledAtEpochMillis = if (record.sourceActive) null else clock().coerceAtLeast(0))))
    }

    @Synchronized fun markSubmitted(claim: NotificationEventRecord): Boolean = transitionClaim(claim) {
        if (it.phase == NotificationEventPhase.CLAIMED) it.copy(phase = NotificationEventPhase.SUBMITTED) else it
    }

    @Synchronized fun markUncertain(claim: NotificationEventRecord): Boolean = transitionClaim(claim) {
        if (it.phase in setOf(NotificationEventPhase.CLAIMED, NotificationEventPhase.SUBMITTED))
            it.copy(phase = NotificationEventPhase.UNCERTAIN) else it
    }

    /** Actual runtime receipt, never inferred from a successful socket/frame write. */
    @Synchronized fun accepted(claim: NotificationEventRecord, threadId: String, turnId: String): Boolean {
        if (threadId != claim.correlation?.threadId || !validCorrelation(turnId))
            return failure("acceptance_correlation_mismatch")
        return transitionClaim(claim, allowSettledReceipt = true) { record ->
            if ((record.correlation?.turnId != null && record.correlation.turnId != turnId) ||
                (record.phase in setOf(NotificationEventPhase.COMPLETED, NotificationEventPhase.FAILED) &&
                    record.correlation?.turnId != turnId)) {
                failure("acceptance_correlation_conflict"); null
            } else if (record.phase in setOf(NotificationEventPhase.COMPLETED, NotificationEventPhase.FAILED)) {
                record // a late ACK cannot undo already proven terminal settlement
            } else record.copy(phase = NotificationEventPhase.ACCEPTED,
                correlation = record.correlation!!.copy(turnId = turnId), payloadJson = "")
        }
    }

    /** Positive raw persisted external-tool receipt: exact event, payload, thread and turn only. */
    @Synchronized fun recoverAccepted(eventId: String, payloadSha256: String, threadId: String, turnId: String): Boolean {
        val state = read() ?: return false
        val record = state.records.firstOrNull { it.eventId == eventId && it.phase in UNSETTLED } ?: return false
        if (record.payloadSha256 != payloadSha256 || record.correlation?.threadId != threadId)
            return failure("recovery_correlation_mismatch")
        return accepted(record, threadId, turnId)
    }

    @Synchronized fun settle(eventId: String, threadId: String, turnId: String, success: Boolean): Boolean {
        val state = read() ?: return false
        val record = state.records.firstOrNull { it.eventId == eventId && it.phase == NotificationEventPhase.ACCEPTED }
            ?: return false
        if (record.correlation?.threadId != threadId || record.correlation.turnId != turnId)
            return failure("terminal_correlation_mismatch")
        return write(state.replacing(record.copy(phase = if (success) NotificationEventPhase.COMPLETED
            else NotificationEventPhase.FAILED, payloadJson = "", settledAtEpochMillis = clock().coerceAtLeast(0))))
    }

    @Synchronized fun remove(packageName: String, androidKey: String): Boolean =
        read()?.let { write(inactivate(it, packageName, androidKey)) } ?: false

    /** Privacy boundary callers acquire the process-wide privacy lock BEFORE the ledger lock. */
    @Synchronized fun purgeExcluded(allowedPackage: (String) -> Boolean): Boolean = read()?.let { state ->
        write(state.copy(records = state.records.filter { allowedPackage(it.packageName) }))
    } ?: false

    /** Retain the source cursor: privacy clearing cannot turn old outbox rows into new tasks. */
    @Synchronized fun clearPrivateData(): Boolean = read()?.let { state ->
        write(state.copy(records = emptyList(), generation = newId()))
    } ?: false

    @Synchronized fun status(waitReason: String? = null): NotificationEventStatus {
        val state = read() ?: return NotificationEventStatus(false, false, 0, 0, 0, waitReason, lastError)
        return NotificationEventStatus(true, state.activationSequence != null,
            state.records.count { it.phase == NotificationEventPhase.READY && it.sourceActive },
            state.records.count { it.phase in UNSETTLED },
            state.records.count { it.phase == NotificationEventPhase.UNCERTAIN || it.phase == NotificationEventPhase.CLAIMED },
            waitReason, lastError, technicalUpdateCount = state.technicalUpdateCount)
    }

    internal fun reportFailure(code: String) { lastError = code.takeIf { it.matches(Regex("[a-z_]{1,64}")) } ?: "coordinator_failed" }

    private fun inactivate(state: NotificationEventState, packageName: String, androidKey: String): NotificationEventState =
        state.copy(records = state.records.map { record ->
            if (record.packageName != packageName || record.androidKey != androidKey) record
            else record.copy(sourceActive = false,
                report = null,
                phase = if (record.phase == NotificationEventPhase.READY) NotificationEventPhase.CANCELLED else record.phase,
                payloadJson = if (record.phase == NotificationEventPhase.READY) "" else record.payloadJson,
                settledAtEpochMillis = if (record.phase == NotificationEventPhase.READY) clock().coerceAtLeast(0)
                    else record.settledAtEpochMillis)
        })

    private fun transitionClaim(claim: NotificationEventRecord, allowSettledReceipt: Boolean = false,
        change: (NotificationEventRecord) -> NotificationEventRecord?): Boolean {
        val state = read() ?: return false
        val record = state.exactClaim(claim) ?: return false
        if (record.phase !in UNSETTLED && !(allowSettledReceipt &&
                record.phase in setOf(NotificationEventPhase.COMPLETED, NotificationEventPhase.FAILED))) return false
        val updated = change(record) ?: return false
        return if (updated == record) true else write(state.replacing(updated))
    }

    private fun read(): NotificationEventState? = runCatching { storage.read() }.getOrNull().also {
        if (it == null) lastError = "storage_unavailable"
    }
    private fun write(state: NotificationEventState): Boolean = runCatching {
        storage.write(state)
        check(storage.read() == state)
    }.fold({ lastError = null; true }, { failure("storage_commit_failed") })
    private fun failure(code: String): Boolean { lastError = code; return false }

    private companion object {
        val UNSETTLED = setOf(NotificationEventPhase.CLAIMED, NotificationEventPhase.SUBMITTED,
            NotificationEventPhase.ACCEPTED, NotificationEventPhase.UNCERTAIN)
    }
}

internal interface NotificationEventStorage {
    /** Null is unavailable/corrupt, NOT a new empty ledger. */
    fun read(): NotificationEventState?
    fun write(state: NotificationEventState)
}

internal data class NotificationEventState(
    val activationSequence: Long? = null,
    val lastObservedSequence: Long = 0,
    val generation: String = "",
    val records: List<NotificationEventRecord> = emptyList(),
    val technicalUpdateCount: Long = 0,
) {
    fun replacing(record: NotificationEventRecord) = copy(records = records.map { if (it.eventId == record.eventId) record else it })
    fun exactClaim(claim: NotificationEventRecord): NotificationEventRecord? = records.firstOrNull {
        it.eventId == claim.eventId && it.payloadSha256 == claim.payloadSha256 &&
            it.correlation?.threadId == claim.correlation?.threadId && it.correlation?.attemptId == claim.correlation?.attemptId
    }
}

internal enum class NotificationEventPhase { READY, CLAIMED, SUBMITTED, ACCEPTED, UNCERTAIN, COMPLETED, FAILED, CANCELLED }
internal data class NotificationEventCorrelation(val threadId: String, val attemptId: String, val turnId: String? = null)
internal data class NotificationEventRecord(
    val sequence: Long,
    val eventId: String,
    val packageName: String,
    val androidKey: String,
    val payloadJson: String,
    val payloadSha256: String,
    val phase: NotificationEventPhase,
    val sourceActive: Boolean,
    val correlation: NotificationEventCorrelation? = null,
    val settledAtEpochMillis: Long? = null,
    val report: NotificationEventReport? = null,
    val updateFingerprint: String? = null,
)
internal data class NotificationEventReport(val id: String, val text: String, val textSha256: String,
    val timelineAnchorId: String?)

/** Public diagnostic shape deliberately has NO source, message, key, thread or event identifiers. */
internal data class NotificationEventStatus(val available: Boolean, val activated: Boolean,
    val readyCount: Int, val unsettledCount: Int, val uncertainCount: Int,
    val waitReason: String? = null, val failureCode: String? = null,
    val outcomes: NotificationHookOutcomeSnapshot = NotificationHookOutcomeSnapshot(),
    val technicalUpdateCount: Long = 0)

internal object NotificationEventLimits {
    const val MAX_RECORDS = 256
    const val MAX_PAYLOAD_BYTES = 65_536
    const val MAX_STORAGE_BYTES = 20 * 1_048_576
    const val TOMBSTONE_MILLIS = 48L * 60 * 60 * 1_000
}

internal fun notificationEventSha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
internal fun validCorrelation(value: String): Boolean = value.isNotBlank() && value.length <= 256 && !value.any(Char::isISOControl)

/** Tool-output data, not user/developer instructions. The host supplies the authority envelope. */
internal object NotificationExternalEventPayload {
    fun encode(eventId: String, event: NotificationInboxEvent, includeStatusMetadata: Boolean = true): String {
        val s = event.snapshot
        // Redaction is a data-safety boundary, not a relevance filter. Even a wholly redacted
        // event reaches Hans as metadata; no source key, actionable URL or action label is exposed.
        val safe = sanitizeRestrictedNotificationText(s.title, s.text, s.subtext)
        val category = s.category.takeIf { it in SAFE_CATEGORIES }.orEmpty()
        val payload = JSONObject().put("schema", "hans.notification.external-event.v1").put("eventId", eventId)
            .put("schemaVersion", 1).put("sequence", event.sequence).put("kind", event.kind.name)
            .put("observedAtEpochMillis", event.observedAtEpochMillis).put("packageName", s.packageName)
            .put("sourceActive", true).put("title", safe?.title.orEmpty()).put("text", safe?.text.orEmpty())
            .put("subtext", safe?.subtext.orEmpty()).put("category", category)
            .put("contentUnavailable", safe == null).put("redactionApplied", safe == null || safe.redactionApplied)
            .put("actions", JSONArray())
        if (includeStatusMetadata) payload.put("ongoing", s.ongoing).put("clearable", s.clearable)
            .put("sourceId", notificationEventSha256("${s.packageName}:${s.androidKey}"))
            .put("channelId", notificationEventSha256(s.channelId))
        return payload.toString()
    }

    private val SAFE_CATEGORIES = setOf("alarm", "call", "email", "err", "event", "msg", "progress",
        "promo", "recommendation", "reminder", "service", "social", "status", "sys", "transport", "workout", "navigation", "missed_call", "stopwatch", "location_sharing")
}
