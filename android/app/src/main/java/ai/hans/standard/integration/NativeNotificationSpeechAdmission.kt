package ai.hans.standard.integration

import ai.hans.standard.codex.AgentMessagePhase
import ai.hans.standard.notifications.hooks.NotificationHookOutcome
import ai.hans.standard.notifications.hooks.NotificationHookOutcomeSnapshot
import ai.hans.standard.voice.tts.TtsPlaybackEvent
import java.security.MessageDigest
import java.util.UUID

internal data class NativeNotificationReportCard(
    val id: String, val threadId: String, val text: String, val timelineAnchorId: String?,
)

/**
 * Source-bound notices selected explicitly by main Hans. Native ACK proves intake, never
 * relevance: sharing a turn cannot give an ordinary/main FINAL new speech authority.
 * This private monitor never calls a host/controller, Android service, I/O or audio boundary.
 */
internal class NativeNotificationSpeechAdmission(private val newId: () -> String = { UUID.randomUUID().toString() }) {
    internal data class Source(val packageName: String, val androidKey: String)
    internal class Token internal constructor(internal val ordinal: Long)
    internal sealed interface ReportResult {
        data class Presented(val card: NativeNotificationReportCard, val speechAllowed: Boolean, val replay: Boolean) : ReportResult
        data class Rejected(val code: String) : ReportResult
    }
    private data class Entry(
        val token: Token, val eventId: String, val source: Source, val expectedThread: String,
        val clientEpoch: Long, val baselineOrder: Long, val observedAt: Long, val initialGate: String?,
        var turnId: String? = null, var workOrdinal: Long? = null, var accepted: Boolean = false,
        var claimSettled: Boolean = false, var turnTerminal: Boolean = false, var finalVisible: Boolean = false,
        var sourceRevoked: String? = null, var speechSuppressed: Boolean = initialGate != null,
        var speechGate: String = initialGate ?: "awaiting_native_ack", var speechRevoked: String? = null,
        var report: NativeNotificationReportCard? = null, var reportHash: String? = null,
        var reportPresented: Boolean = false,
        var reportEpoch: Long? = null, var outputOutstanding: Boolean = false,
        var submissionRecorded: Boolean = false,
        var playbackPhase: String? = null, var playbackFailure: String? = null,
    )
    private val entries = linkedMapOf<Token, Entry>()
    private val outputs = linkedMapOf<String, Token>()
    private var nextOrdinal = 0L
    private var nextWorkOrdinal = 0L
    private var acceptedCount = 0L
    private var finalVisibleCount = 0L
    private var reportPresentedCount = 0L
    private var speechQueuedCount = 0L
    private var capacityBlockedCount = 0L
    private val ackSignal = Object()
    @Volatile private var ackRevision = 0L

    @Synchronized fun begin(eventId: String, source: Source, expectedThread: String,
        clientEpoch: Long, baselineOrder: Long, observedAt: Long, initialGate: String?): Token? {
        while (entries.size >= MAX_ENTRIES) {
            val retired = entries.entries.firstOrNull { !it.value.outputOutstanding &&
                (it.value.turnTerminal || it.value.sourceRevoked != null ||
                    (it.value.claimSettled && !it.value.accepted)) } ?: run { capacityBlockedCount++; return null }
            entries.remove(retired.key)
            outputs.entries.removeAll { it.value === retired.key }
        }
        val token = Token(++nextOrdinal)
        entries[token] = Entry(token, eventId, source, expectedThread, clientEpoch, baselineOrder, observedAt, initialGate)
        return token
    }

    /** Successful frames, source prefixes and model text are deliberately not ACK proof. */
    @Synchronized fun receipt(token: Token?, receipt: NativeNotificationDispatchReceipt) {
        val entry = entries[token] ?: return
        signalAck()
        when (receipt) {
            is NativeNotificationDispatchReceipt.Accepted -> {
                if (receipt.threadId != entry.expectedThread || receipt.turnId.isBlank()) {
                    entry.claimSettled = true; entry.speechGate = "ack_correlation_mismatch"; return
                }
                if (entry.accepted) return
                entry.accepted = true; entry.claimSettled = true; entry.turnId = receipt.turnId
                entry.workOrdinal = entries.values.firstOrNull { it !== entry &&
                    it.expectedThread == receipt.threadId && it.turnId == receipt.turnId }?.workOrdinal ?: ++nextWorkOrdinal
                acceptedCount++
                if (entry.sourceRevoked == null && entry.initialGate == null) entry.speechGate = "awaiting_explicit_report"
            }
            NativeNotificationDispatchReceipt.RejectedByRuntime -> {
                entry.claimSettled = true; entry.speechGate = "native_rejected"
            }
            NativeNotificationDispatchReceipt.OutcomeAmbiguous -> {
                entry.claimSettled = true; entry.speechGate = "native_outcome_uncertain"
            }
        }
    }

    @Synchronized fun unsent(token: Token?) {
        entries[token]?.let { it.claimSettled = true; it.speechGate = "not_transmitted" }
        signalAck()
    }

    /** Source lookup only; Host additionally verifies fresh durable privacy/source state. */
    @Synchronized fun sourceForReport(eventId: String, threadId: String, turnId: String, clientEpoch: Long): Source? =
        entries.values.firstOrNull { matches(it, eventId, threadId, turnId, clientEpoch) }?.source

    /** A tool may beat the transport ACK. Wait only on this pure signal, never on host/privacy. */
    fun awaitSourceForReport(eventId: String, threadId: String, turnId: String,
        clientEpoch: Long, timeoutMillis: Long): Source? {
        require(timeoutMillis in 1..3_000)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (true) {
            val revision = ackRevision
            sourceForReport(eventId, threadId, turnId, clientEpoch)?.let { return it }
            val pending = synchronized(this) { entries.values.any { it.eventId == eventId &&
                it.expectedThread == threadId && it.clientEpoch == clientEpoch &&
                !it.claimSettled && it.sourceRevoked == null } }
            if (!pending) return null
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return null
            synchronized(ackSignal) {
                // No acquisition of the admission/host monitor while holding this signal.
                if (ackRevision == revision) ackSignal.wait((remaining / 1_000_000).coerceAtLeast(1))
            }
        }
    }

    @Synchronized fun report(eventId: String, threadId: String, turnId: String, clientEpoch: Long,
        text: String, anchorId: String?, speechEpoch: Long, gate: String?, reportId: String? = null): ReportResult {
        val entry = entries.values.firstOrNull { matches(it, eventId, threadId, turnId, clientEpoch) }
            ?: return ReportResult.Rejected("notification_report_no_live_receipt")
        val hash = sha256(text)
        entry.report?.let { existing ->
            return if (entry.reportHash == hash) ReportResult.Presented(existing, false, true)
            else ReportResult.Rejected("notification_report_conflict")
        }
        val card = NativeNotificationReportCard(reportId ?: "hans-notice-" + newId(), threadId, text, anchorId)
        entry.report = card; entry.reportHash = hash; entry.reportEpoch = speechEpoch; entry.reportPresented = true
        reportPresentedCount++
        val allowed = !entry.speechSuppressed && gate == null && entry.sourceRevoked == null
        if (gate != null) suppress(entry, gate)
        if (allowed) entry.speechGate = "explicit_report_allowed"
        // Even denied reports keep a guard: no late callback may turn them into generic speech.
        outputs[card.id] = entry.token
        if (allowed) {
            entry.outputOutstanding = true; entry.playbackPhase = "ADMITTED"
        }
        return ReportResult.Presented(card, allowed, false)
    }

    @Synchronized fun cards(threadId: String?): List<NativeNotificationReportCard> = entries.values
        .filter { it.sourceRevoked == null && it.expectedThread == threadId }.mapNotNull { it.report }

    /** FINAL is diagnostic evidence only, not a report or a relevance/speech permission. */
    @Synchronized fun observe(threadId: String?, clientEpoch: Long, timeline: List<ClientTimelineItem>,
        terminalTurns: List<ClientTerminalTurn>, gate: String?, visible: (ClientTimelineItem) -> Boolean) {
        val finalsByTurn = timeline.filter { it.role == ClientTimelineRole.HANS &&
            it.agentPhase == AgentMessagePhase.FINAL_ANSWER && it.complete }.groupBy { it.turnId }
        entries.values.forEach { entry ->
            if (entry.clientEpoch != clientEpoch || entry.expectedThread != threadId) {
                if (entry.sourceRevoked == null) entry.sourceRevoked = "session_changed"
                suppress(entry, "session_changed")
                return@forEach
            }
            if (!entry.accepted) return@forEach
            val visibleFinal = finalsByTurn[entry.turnId].orEmpty().any { it.order > entry.baselineOrder && visible(it) }
            if (visibleFinal && !entry.finalVisible) { entry.finalVisible = true; finalVisibleCount++ }
            entry.turnTerminal = entry.turnTerminal || terminalTurns.any { it.threadId == threadId && it.turnId == entry.turnId }
            if (gate != null) suppress(entry, gate)
            if (entry.report == null && entry.initialGate == null && entry.sourceRevoked == null && !entry.speechSuppressed)
                entry.speechGate = if (entry.turnTerminal) "terminal_without_explicit_report" else "awaiting_explicit_report"
        }
    }

    @Synchronized fun outputAllowed(messageId: String, speechEpoch: Long): Boolean {
        val token = outputs[messageId] ?: return !messageId.startsWith("hans-notice-")
        return entries[token]?.let { it.accepted && it.sourceRevoked == null && !it.speechSuppressed &&
            it.report?.id == messageId && it.reportEpoch == speechEpoch } == true
    }
    @Synchronized fun outputIsNotificationOwned(messageId: String?): Boolean = messageId?.startsWith("hans-notice-") == true
    @Synchronized fun outputSource(messageId: String): Source? = outputs[messageId]?.let(entries::get)
        ?.takeIf { it.accepted && it.sourceRevoked == null }?.source
    @Synchronized fun revokeSource(source: Source): Set<String> = revoke("source_removed") { it.source == source }
    @Synchronized fun revokeAll(reason: String): Set<String> = revoke(reason) { true }
    @Synchronized fun suppressThrough(cutoff: Long): Set<String> {
        val ids = linkedSetOf<String>()
        entries.values.filter { it.observedAt <= cutoff }.forEach { entry ->
            suppress(entry, "mute_backlog_suppressed"); entry.report?.id?.let(ids::add)
        }
        return ids
    }

    @Synchronized fun playback(messageId: String?, phase: String, failureCode: String?, released: Boolean) {
        if (released) entries.values.filter { it.playbackPhase in setOf("FAILED", "DROPPED", "COMPLETED") }
            .forEach { it.outputOutstanding = false }
        val entry = messageId?.let(outputs::get)?.let(entries::get) ?: return
        entry.playbackPhase = phase.takeIf { it in PLAYBACK_PHASES }
        entry.playbackFailure = failureCode?.takeIf { it.matches(Regex("[a-z0-9_]{1,80}")) }
    }
    @Synchronized fun deliveryEvent(event: TtsPlaybackEvent) {
        val id = when (event) {
            is TtsPlaybackEvent.MessageCompleted -> event.messageId.value
            is TtsPlaybackEvent.MessageDropped -> event.messageId.value
            is TtsPlaybackEvent.MessageFailed -> event.messageId.value
            else -> return
        }
        val entry = outputs[id]?.let(entries::get) ?: return
        entry.playbackPhase = when (event) {
            is TtsPlaybackEvent.MessageCompleted -> "COMPLETED"
            is TtsPlaybackEvent.MessageDropped -> "DROPPED"
            else -> "FAILED"
        }
        if (event is TtsPlaybackEvent.MessageCompleted) entry.outputOutstanding = false
    }
    @Synchronized fun confirmOutputsReleased(messageIds: Set<String>) {
        messageIds.mapNotNull(outputs::get).forEach { entries[it]?.outputOutstanding = false }
    }
    /** Count only a real nonblocking Delivery submit, not the preceding permission decision. */
    @Synchronized fun reportSubmissionResult(messageId: String, queued: Boolean) {
        val entry = outputs[messageId]?.let(entries::get) ?: return
        if (entry.submissionRecorded || entry.report == null) return
        entry.submissionRecorded = true
        if (queued) {
            if (entry.playbackPhase == "ADMITTED") entry.playbackPhase = "QUEUED"
            speechQueuedCount++
        }
        else {
            entry.playbackPhase = "DROPPED"; entry.outputOutstanding = false
            suppress(entry, "output_admission_not_queued")
        }
    }
    @Synchronized fun hasOutstandingSpeech(speechEpoch: Long): Boolean = entries.values.any {
        it.reportEpoch == speechEpoch && it.outputOutstanding
    }
    @Synchronized fun snapshot(): NotificationHookOutcomeSnapshot = NotificationHookOutcomeSnapshot(
        acceptedCount = acceptedCount, visibleFinalCount = finalVisibleCount, reportPresentedCount = reportPresentedCount,
        speechQueuedCount = speechQueuedCount, speechCapacityBlockedCount = capacityBlockedCount,
        recent = entries.values.toList().takeLast(MAX_DIAGNOSTIC_ROWS).map { NotificationHookOutcome(
            eventOrdinal = it.token.ordinal, workOrdinal = it.workOrdinal, accepted = it.accepted,
            claimSettled = it.claimSettled, turnTerminal = it.turnTerminal, finalVisible = it.finalVisible,
            reportPresented = it.reportPresented, speechGate = it.speechGate, revocationReason = it.sourceRevoked ?: it.speechRevoked,
            playbackPhase = it.playbackPhase, playbackFailure = it.playbackFailure,
        ) },
    )
    private fun matches(entry: Entry, eventId: String, threadId: String, turnId: String, clientEpoch: Long): Boolean =
        entry.eventId == eventId && entry.accepted && entry.sourceRevoked == null &&
            entry.expectedThread == threadId && entry.turnId == turnId && entry.clientEpoch == clientEpoch
    private fun suppress(entry: Entry, reason: String) {
        if (entry.speechSuppressed) return
        entry.speechSuppressed = true; entry.speechGate = reason; entry.speechRevoked = reason
    }
    private fun revoke(reason: String, matching: (Entry) -> Boolean): Set<String> {
        val ids = linkedSetOf<String>()
        entries.values.filter(matching).forEach { entry ->
            if (entry.sourceRevoked == null) entry.sourceRevoked = reason
            suppress(entry, reason); entry.claimSettled = true
            entry.report?.id?.let(ids::add)
            entry.report = null // private source clearing must not retain the local notice text
        }
        signalAck()
        return ids
    }
    private fun signalAck() { ackRevision++; synchronized(ackSignal) { ackSignal.notifyAll() } }
    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private companion object {
        const val MAX_ENTRIES = 256
        const val MAX_DIAGNOSTIC_ROWS = 48
        val PLAYBACK_PHASES = setOf("IDLE", "CONNECTING", "READY", "SPEAKING", "STOPPING", "STOPPED", "FAILED", "QUEUED")
    }
}
