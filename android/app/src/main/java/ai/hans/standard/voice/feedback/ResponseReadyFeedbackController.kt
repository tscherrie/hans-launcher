package ai.hans.standard.voice.feedback

import ai.hans.standard.codex.TurnStatus
import ai.hans.standard.integration.ClientRuntimePhase
import ai.hans.standard.integration.ClientSessionPhase
import ai.hans.standard.integration.ClientTimelineRole
import ai.hans.standard.integration.CodexClientSnapshot
import ai.hans.standard.integration.OutboundMessageStatus
import ai.hans.standard.setup.HansSetupOutputSanitizer
import ai.hans.standard.text.AssistantMarkdown
import ai.hans.standard.voice.realtime.LiveVoicePhase
import ai.hans.standard.voice.realtime.LiveVoiceResponseReady
import ai.hans.standard.voice.realtime.LiveVoiceSnapshot
import java.util.concurrent.Executor

/** A best-effort pulse. Implementations must check the current device/user policy themselves. */
fun interface ResponseReadyHaptics {
    fun pulse()
}

/**
 * Process-scoped, content-free answer-start feedback; never bind this to a classifier session.
 *
 * A pulse means that new response text is available, not that audio has been heard. A recovered
 * Codex snapshot establishes a silent baseline. Live events already carry exact new-response
 * correlation, so the first real Live answer is not discarded as a baseline.
 */
class ResponseReadyFeedbackController(
    private val haptics: ResponseReadyHaptics,
    private val executor: Executor = Executor { it.run() },
    private val maxTrackedResponses: Int = DEFAULT_MAX_TRACKED_RESPONSES,
) : AutoCloseable {
    private val lock = Any()
    private val codexTurns = LinkedHashSet<CodexAnswer>()
    private val liveResponses = LinkedHashSet<LiveVoiceResponseReady>()
    private val pending = LinkedHashSet<Pulse>()
    private var closed = false
    private var codexGeneration: Long? = null
    private var codexThread: String? = null
    private var codexNeedsBaseline = true
    private var codexOrderFloor = Long.MIN_VALUE
    private var currentCodexAnswer: CodexAnswer? = null
    private var currentCodexAnswerConsumed = false
    private var liveSnapshot = LiveVoiceSnapshot()

    init {
        require(maxTrackedResponses in 1..4_096)
    }

    /** Only the interactive, user-visible Codex session may feed this observer. */
    fun acceptCodex(
        snapshot: CodexClientSnapshot,
        enabled: Boolean = true,
        userFacing: Boolean = true,
        liveVoiceOwnsOutput: Boolean = false,
    ) {
        val pulse = synchronized(lock) {
            if (closed) return
            if (!enabled) pending.clear()
            val generation = snapshot.generation
            if (generation != null && codexGeneration?.let { generation < it } == true) return
            val threadId = snapshot.session.currentThreadId
            val usable = generation != null && threadId != null &&
                snapshot.runtimePhase == ClientRuntimePhase.READY &&
                snapshot.sessionPhase in READY_CODEX_PHASES &&
                !snapshot.session.delivery.rehydrationRequired
            if (!usable) {
                codexNeedsBaseline = true
                pending.removeAll { it.source == Source.CODEX }
                return
            }
            val thread = snapshot.session.threads.firstOrNull { it.threadId == threadId }
                ?: return
            val current = thread.currentTurn
            if (codexNeedsBaseline || generation != codexGeneration || threadId != codexThread) {
                pending.removeAll { it.source == Source.CODEX }
                codexGeneration = generation
                codexThread = threadId
                codexNeedsBaseline = false
                codexOrderFloor = snapshot.timeline.maxOfOrNull { it.order } ?: Long.MIN_VALUE
                snapshot.timeline.filter { it.role == ClientTimelineRole.HANS }.forEach { item ->
                    item.turnId?.let { remember(codexTurns, CodexAnswer(threadId!!, it)) }
                }
                snapshot.terminalTurns.filter { it.threadId == threadId }.forEach {
                    remember(codexTurns, CodexAnswer(threadId!!, it.turnId))
                }
                snapshot.outboundTimeline.filter { it.threadId == threadId }.forEach { outbound ->
                    outbound.turnId?.let { remember(codexTurns, CodexAnswer(threadId!!, it)) }
                }
                currentCodexAnswer = current?.let { CodexAnswer(threadId!!, it.turnId) }
                currentCodexAnswer?.let { remember(codexTurns, it) }
                currentCodexAnswerConsumed = currentCodexAnswer != null
                return
            }
            val terminals = snapshot.terminalTurns.filter { it.threadId == threadId }
            terminals.filter { it.status != TurnStatus.COMPLETED }.forEach { terminal ->
                val cancelled = CodexAnswer(threadId!!, terminal.turnId)
                remember(codexTurns, cancelled)
                pending.removeAll { it.answer == cancelled }
                if (currentCodexAnswer == cancelled) currentCodexAnswerConsumed = true
            }
            // The actual reducer clears currentTurn on completion. Exact terminal/outbound
            // receipts keep a non-streamed final answer eligible even if observers coalesce
            // the in-progress snapshots. Neither text nor timestamps invent a turn identity.
            val terminalAnswer = terminals.asReversed().firstOrNull {
                it.status == TurnStatus.COMPLETED && CodexAnswer(threadId!!, it.turnId) !in codexTurns
            }?.turnId
            val sentAnswer = snapshot.outboundTimeline.asReversed().firstOrNull {
                it.threadId == threadId && it.status == OutboundMessageStatus.SENT &&
                    it.turnId != null && CodexAnswer(threadId!!, it.turnId) !in codexTurns
            }?.turnId
            val answer = (current?.turnId ?: terminalAnswer ?: sentAnswer)
                ?.let { CodexAnswer(threadId!!, it) }
                ?: currentCodexAnswer
            if (answer == null) return
            if (answer != currentCodexAnswer) {
                currentCodexAnswer = answer
                currentCodexAnswerConsumed = answer in codexTurns
            }
            if (current?.status == TurnStatus.INTERRUPTED || current?.status == TurnStatus.FAILED) {
                remember(codexTurns, answer)
                currentCodexAnswerConsumed = true
                pending.removeAll { it.answer == answer }
                return
            }
            if (currentCodexAnswerConsumed) return
            val firstText = snapshot.timeline.asSequence()
                .filter { it.role == ClientTimelineRole.HANS && it.turnId == answer.turnId }
                .filter { item ->
                    AssistantMarkdown.parse(
                        HansSetupOutputSanitizer.sanitizeAssistantText(
                            item.text,
                            item.turnId != null && item.turnId in snapshot.setupTurnIds,
                        ),
                        complete = item.complete,
                    ).plainText.isNotBlank()
                }
                .minByOrNull { it.order } ?: return
            // Timeline order is assigned monotonically by CodexSessionController. This also
            // prevents an evicted old turn from being replayed when history reappears.
            currentCodexAnswerConsumed = true
            remember(codexTurns, answer)
            val isNew = firstText.order > codexOrderFloor
            codexOrderFloor = maxOf(codexOrderFloor, firstText.order)
            if (!isNew || !enabled || !userFacing || liveVoiceOwnsOutput) return
            enqueueLocked(Source.CODEX, answer)
        }
        submit(pulse)
    }

    /** Call from the process-wide Live observer, including connecting/stopped boundaries. */
    fun onLiveSnapshot(snapshot: LiveVoiceSnapshot) = synchronized(lock) {
        if (closed) return@synchronized
        if (snapshot.generation != liveSnapshot.generation || snapshot.phase !in ACTIVE_LIVE_PHASES) {
            pending.removeAll { it.source == Source.LIVE }
        }
        liveSnapshot = snapshot
    }

    fun acceptLive(event: LiveVoiceResponseReady, enabled: Boolean = true) {
        val pulse = synchronized(lock) {
            if (closed) return
            if (!enabled) pending.clear()
            if (event.generation != liveSnapshot.generation ||
                liveSnapshot.phase !in ACTIVE_LIVE_PHASES || event in liveResponses
            ) return
            remember(liveResponses, event)
            if (!enabled) return
            enqueueLocked(Source.LIVE, null)
        }
        submit(pulse)
    }

    /** Revokes queued work; a pulse whose platform call already started cannot be rolled back. */
    fun cancelPending() = synchronized(lock) { pending.clear() }

    override fun close() = synchronized(lock) {
        closed = true
        pending.clear()
        codexTurns.clear()
        liveResponses.clear()
    }

    private fun enqueueLocked(source: Source, answer: CodexAnswer?): Pulse? {
        if (pending.size >= maxTrackedResponses) return null
        return Pulse(source, answer).also(pending::add)
    }

    private fun submit(pulse: Pulse?) {
        if (pulse == null) return
        try {
            executor.execute {
                // Claim before calling outside our monitor: platform callbacks cannot invert a
                // session/notification lock, and cancellation still revokes queued work exactly.
                val claimed = synchronized(lock) { !closed && pending.remove(pulse) }
                if (claimed) {
                    try {
                        haptics.pulse()
                    } catch (_: RuntimeException) {
                        // Haptics must never fail an answer, media session, or notification.
                    }
                }
            }
        } catch (_: RuntimeException) {
            synchronized(lock) { pending.remove(pulse) }
        }
    }

    private fun <T> remember(values: LinkedHashSet<T>, value: T) {
        values.add(value)
        while (values.size > maxTrackedResponses) values.remove(values.first())
    }

    private data class CodexAnswer(val threadId: String, val turnId: String)
    private enum class Source { CODEX, LIVE }
    private class Pulse(val source: Source, val answer: CodexAnswer?)

    companion object {
        const val DEFAULT_MAX_TRACKED_RESPONSES = 256
        private val READY_CODEX_PHASES = setOf(ClientSessionPhase.READY, ClientSessionPhase.BUSY)
        private val ACTIVE_LIVE_PHASES = setOf(
            LiveVoicePhase.LISTENING,
            LiveVoicePhase.USER_SPEAKING,
            LiveVoicePhase.HANS_SPEAKING,
            LiveVoicePhase.WAITING_FOR_TASK,
        )
    }
}
