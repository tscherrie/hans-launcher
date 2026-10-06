package ai.hans.standard.voice.realtime

import java.util.concurrent.TimeUnit

/**
 * Irreversible, bounded output-only tail AFTER matching Codex work has ended.
 *
 * The owner must close microphone admission before [start]. This policy neither keeps a
 * microphone open nor requires PCM proof to finish. Output observations may briefly preserve
 * an already-playing final answer; missing, gapped or unreliable observations never block
 * ordinary closure. It cannot decide task completion, admit work, or affect telephone mode.
 * Calls must be serialized by the owning session. No content, identifiers, I/O or timers live here.
 */
class CodexTaskVoiceOutputTail {
    enum class CloseReason {
        NO_OUTPUT,
        OUTPUT_QUIET_OR_GAP,
        MAXIMUM_TAIL,
    }

    private var startedAtNanos: Long? = null
    private var lastNowNanos: Long? = null
    private var lastOutputObservedAtNanos: Long? = null
    private var lastAudibleOutputAtNanos: Long? = null
    private var result: CloseReason? = null

    /** Repeated terminal receipts cannot restart the grace or extend the absolute limit. */
    fun start(nowNanos: Long) {
        if (startedAtNanos != null) return
        startedAtNanos = nowNanos
        lastNowNanos = nowNanos
    }

    /** A one-shot wakeup deadline, never a requirement to poll for audio. */
    val nextDeadlineNanos: Long?
        get() {
            val started = startedAtNanos ?: return null
            if (result != null) return null
            val graceEnd = deadline(started, INITIAL_GRACE_NANOS)
            val outputEnd = lastAudibleOutputAtNanos?.let { deadline(it, OUTPUT_QUIET_NANOS) }
                ?: graceEnd
            return minOf(maxOf(graceEnd, outputEnd), deadline(started, MAXIMUM_TAIL_NANOS))
        }

    /** Only fresh, reliable audible OUTPUT can extend the finite tail. Silence need not arrive. */
    fun observe(activity: LiveVoiceAudioActivity, nowNanos: Long) {
        val started = startedAtNanos ?: return
        if (result != null || !advanceClock(nowNanos)) return
        // An overdue timer/event cannot resurrect a tail that should already have ended.
        if (evaluateAt(nowNanos) != null) return
        if (activity.direction != LiveVoiceAudioDirection.OUTPUT) return
        val observed = activity.observedAtNanos
        if (observed < started || observed > nowNanos) return
        if (lastOutputObservedAtNanos?.let { observed <= it } == true) return
        lastOutputObservedAtNanos = observed
        if (activity.reliable && activity.speechActive) lastAudibleOutputAtNanos = observed
    }

    /** Sticky ordinary closure, including when no output observation ever arrives. */
    fun evaluate(nowNanos: Long): CloseReason? {
        result?.let { return it }
        if (startedAtNanos == null || !advanceClock(nowNanos)) return null
        return evaluateAt(nowNanos)
    }

    private fun evaluateAt(nowNanos: Long): CloseReason? {
        val started = startedAtNanos ?: return null
        val due = nextDeadlineNanos ?: return result
        if (nowNanos < due) return null
        result = when {
            due == deadline(started, MAXIMUM_TAIL_NANOS) -> CloseReason.MAXIMUM_TAIL
            lastAudibleOutputAtNanos == null -> CloseReason.NO_OUTPUT
            else -> CloseReason.OUTPUT_QUIET_OR_GAP
        }
        return result
    }

    private fun advanceClock(nowNanos: Long): Boolean {
        if (lastNowNanos?.let { nowNanos < it } == true) return false
        lastNowNanos = nowNanos
        return true
    }

    private fun deadline(start: Long, duration: Long): Long =
        if (start > Long.MAX_VALUE - duration) Long.MAX_VALUE else start + duration

    companion object {
        val INITIAL_GRACE_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(1_500)
        val OUTPUT_QUIET_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(750)
        val MAXIMUM_TAIL_NANOS: Long = TimeUnit.SECONDS.toNanos(20)
    }
}
