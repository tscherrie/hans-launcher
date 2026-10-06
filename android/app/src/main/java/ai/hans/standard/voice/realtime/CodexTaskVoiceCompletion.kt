package ai.hans.standard.voice.realtime

import java.util.concurrent.TimeUnit

/** A serial, lease-fenced main-thread lifecycle receipt, not a polled UI idle flag. */
data class CodexTaskVoiceWorkState(
    val revision: Long,
    val activeTurnId: String? = null,
    val pendingDispatch: Boolean = false,
    val terminal: CodexTaskVoiceTerminal? = null,
)

data class CodexTaskVoiceTerminal(val turnId: String, val outcome: CodexTaskVoiceWorkOutcome)
enum class CodexTaskVoiceWorkOutcome { COMPLETED, INTERRUPTED, FAILED }

/**
 * Single-use, content-free WORK completion policy for task-scoped Live Voice.
 *
 * The owner serializes lifecycle calls and fences every receipt by the native lease, runtime
 * generation, account and main thread. Native handoff admission does not expose an exact turn
 * ID, so this policy conservatively tracks the main-work envelope observed around a handoff.
 * A pre-existing turn alone cannot authorize completion; a handoff may steer that active turn.
 * A terminal already observed before the handoff cannot be reassigned to that later request.
 *
 * [Decision.Finished] means only that the tracked Codex work reached its matching terminal
 * outcome and no observed work/dispatch remains. It is NOT speech, audio-drain or playback
 * completion. The owner immediately cuts off new input and separately owns any bounded output
 * tail. Audio, text, silence, mute and their absence never authorize, delay or revoke this
 * decision. In particular there is no speech-finish timeout in this policy.
 *
 * No scheduler, microphone, model request or I/O is owned here. Schedule [nextDeadlineNanos]
 * as a one-shot wakeup for no handoff or missing work receipts. A known running task has no
 * arbitrary duration limit. Dispose this instance with its lease; never reuse it.
 */
class CodexTaskVoiceCompletion(
    private val activatedAtNanos: Long,
    private val config: Config = Config(),
) {
    data class Config(
        val noHandoffTimeoutNanos: Long = TimeUnit.SECONDS.toNanos(60),
        val admissionTimeoutNanos: Long = TimeUnit.SECONDS.toNanos(45),
    ) {
        init {
            require(noHandoffTimeoutNanos > 0 && admissionTimeoutNanos > 0)
        }
    }

    sealed interface Decision {
        /** WORK only, not playback completion. Interrupted/failed work is never called success. */
        data class Finished(val workOutcome: CodexTaskVoiceWorkOutcome) : Decision
        data object NoHandoffTimeout : Decision
        /** Missing or invalid lifecycle evidence; never a successful work/playback receipt. */
        data class Failed(val code: String) : Decision
    }

    private var lastNowNanos = activatedAtNanos
    private var state: CodexTaskVoiceWorkState? = null
    private var handoffObserved = false
    private var admissionDeadline: Long? = null
    private val outstandingTurns = LinkedHashSet<String>()
    private val terminalTurns = LinkedHashSet<String>()
    private var lastTerminal: CodexTaskVoiceTerminal? = null
    private var decided: Decision? = null

    val hasHandoff: Boolean get() = handoffObserved

    /** One-shot lifecycle deadlines only; no PCM polling or audio-finish deadline. */
    val nextDeadlineNanos: Long?
        get() = when {
            decided != null -> null
            !handoffObserved -> deadline(activatedAtNanos, config.noHandoffTimeoutNanos)
            else -> admissionDeadline
        }

    fun onWorkState(next: CodexTaskVoiceWorkState, nowNanos: Long) {
        if (!acceptTime(nowNanos)) return
        if (next.revision < 0 || (state?.revision?.let { next.revision <= it } == true)) return
        if (!validId(next.activeTurnId) || !validId(next.terminal?.turnId)) {
            fail("codex_task_voice_work_receipt_invalid")
            return
        }
        state = next
        if (!handoffObserved) return
        next.activeTurnId?.let { turnId ->
            if (turnId !in terminalTurns) {
                if (!trackTurn(turnId)) return
                admissionDeadline = null
            }
        }
        next.terminal?.let { terminal ->
            if (outstandingTurns.remove(terminal.turnId)) {
                terminalTurns += terminal.turnId
                lastTerminal = terminal
                admissionDeadline = null
            }
        }
        if (next.activeTurnId == null) {
            if ((next.pendingDispatch || outstandingTurns.isNotEmpty()) && admissionDeadline == null) {
                // A pending dispatch with no admitted active turn needs a bounded receipt
                // just like an idle projection missing its terminal. Repeated projections
                // do not extend this deadline; genuine active work above clears it.
                admissionDeadline = deadline(nowNanos, config.admissionTimeoutNanos)
            } else if (!next.pendingDispatch && outstandingTurns.isEmpty() && lastTerminal != null) {
                // The pending dispatch settled without admitting additional work. This may
                // complete the matching envelope, but never reuse a pre-handoff terminal.
                admissionDeadline = null
            }
        }
        if (workIsIdle()) {
            lastTerminal?.let { decide(Decision.Finished(it.outcome)) }
        }
    }

    /** Called only for a deduplicated native handoff receipt belonging to this lease. */
    fun onHandoff(nowNanos: Long) {
        if (!acceptTime(nowNanos)) return
        if (!handoffObserved && nowNanos >= deadline(activatedAtNanos, config.noHandoffTimeoutNanos)) {
            decide(Decision.NoHandoffTimeout)
            return
        }
        handoffObserved = true
        // A new handoff while waiting for a pending dispatch cannot inherit an older terminal.
        // Once a decision exists, acceptTime above makes it irrevocable instead.
        lastTerminal = null
        val active = state?.activeTurnId?.takeUnless { it in terminalTurns }
        if (active == null) {
            admissionDeadline = deadline(nowNanos, config.admissionTimeoutNanos)
        } else {
            if (!trackTurn(active)) return
            admissionDeadline = null
        }
    }

    /**
     * Compatibility with the media owner while it separates work from its output tail.
     * These events deliberately have NO effect, including no lifecycle-clock advancement.
     * Neither malformed, duplicate, delayed nor absent media receipts can reopen finished work.
     */
    @Suppress("UNUSED_PARAMETER")
    fun onUserInput(nowNanos: Long) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun onAssistantResponseStarted(responseId: String, nowNanos: Long) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun onAudioActivity(activity: LiveVoiceAudioActivity, nowNanos: Long) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun onInputMuted(confirmed: Boolean, now: Long) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun updateInputDelayNanos(delayNanos: Long, nowNanos: Long) = Unit

    /** Sticky decision; a matching terminal sets Finished immediately in [onWorkState]. */
    fun evaluate(nowNanos: Long): Decision? {
        decided?.let { return it }
        if (!acceptTime(nowNanos)) return null
        if (!handoffObserved && nowNanos >= deadline(activatedAtNanos, config.noHandoffTimeoutNanos)) {
            return decide(Decision.NoHandoffTimeout)
        }
        if (admissionDeadline?.let { nowNanos >= it } == true) {
            return fail("codex_task_voice_work_receipt_timeout")
        }
        return null
    }

    private fun workIsIdle(): Boolean = handoffObserved && outstandingTurns.isEmpty() &&
        state?.activeTurnId == null && state?.pendingDispatch == false && admissionDeadline == null

    private fun trackTurn(turnId: String): Boolean {
        if (turnId in outstandingTurns) return true
        if (outstandingTurns.size + terminalTurns.size >= MAX_RECEIPTS) {
            fail("codex_task_voice_receipt_capacity")
            return false
        }
        outstandingTurns += turnId
        return true
    }

    private fun acceptTime(nowNanos: Long): Boolean {
        if (decided != null || nowNanos < lastNowNanos) return false
        lastNowNanos = nowNanos
        return true
    }

    private fun fail(code: String): Decision = decide(Decision.Failed(code))
    private fun decide(value: Decision): Decision { decided = value; return value }

    private fun validId(value: String?): Boolean = value == null ||
        (value.isNotEmpty() && value.length <= 256 && value.none(Char::isISOControl))

    companion object {
        private const val MAX_RECEIPTS = 256
        private fun deadline(origin: Long, duration: Long): Long =
            if (origin > Long.MAX_VALUE - duration) Long.MAX_VALUE else origin + duration
    }
}
