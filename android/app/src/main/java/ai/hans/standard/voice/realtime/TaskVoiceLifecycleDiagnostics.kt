package ai.hans.standard.voice.realtime

import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * Process-memory postmortem of the latest task-voice run. Never accepts content or identifiers.
 *
 * Producers record lifecycle/audio-state TRANSITIONS, not PCM callbacks or repeated polls.
 * Times are local event-arrival times, not claims about server delivery or speaker drain.
 * Nothing here owns a timer, observer, logger, file, network connection or audio device.
 */
object TaskVoiceLifecycleDiagnostics {
    enum class Event {
        RUN_STARTED, NATIVE_READY, MEDIA_READY, INPUT_MUTE_CHANGED,
        HANDOFF, WORK_STATE, ASSISTANT_RESPONSE_STARTED, COMPLETION_MATCHED, TAIL_STARTED,
        OUTPUT_ACTIVE, OUTPUT_QUIET, OUTPUT_UNRELIABLE, TAIL_CLOSED,
        CLOSE_REQUESTED, SESSION_CLOSED, SESSION_FAILED,
    }

    enum class Reason {
        COMPLETED, INTERRUPTED, FAILED, NO_HANDOFF_TIMEOUT, WORK_RECEIPT_TIMEOUT,
        FINISH_TIMEOUT, WORK_RECEIPT_INVALID, ANSWER_RECEIPT_INVALID, INPUT_DELAY_INVALID,
        RECEIPT_CAPACITY, AUDIO_CLOSE_UNCONFIRMED, NATIVE_CLOSE_UNCONFIRMED,
        TAIL_NO_OUTPUT, TAIL_OUTPUT_QUIET_OR_GAP, TAIL_MAXIMUM, INPUT_CLOSED, INPUT_CLOSE_UNCONFIRMED,
        USER_STOP, CONTEXT_CHANGED, RUNTIME_FAILED, OTHER_FAILURE,
    }

    data class Details(
        val activeWork: Boolean? = null,
        val pendingDispatch: Boolean? = null,
        val muted: Boolean? = null,
        val workOutcome: CodexTaskVoiceWorkOutcome? = null,
        val reason: Reason? = null,
        val handoffCount: Int? = null,
        val inputDelayMillis: Long? = null,
        val mediaClosed: Boolean? = null,
        val nativeClosed: Boolean? = null,
    )

    data class Record(val sequence: Long, val elapsedMillis: Long, val type: Event, val details: Details)

    data class Snapshot(
        /** Local counter only, never a Codex session, turn, thread or account identifier. */
        val run: Long,
        val eventCount: Long,
        val droppedCount: Long,
        val records: List<Record>,
    )

    private val recorder = Recorder()

    fun beginRun(): Long = recorder.beginRun()
    fun event(run: Long, type: Event, details: Details = Details()): Boolean =
        recorder.event(run, type, details)
    fun snapshot(): Snapshot? = recorder.snapshot()

    /** Separate deterministic clock/state for tests; production has exactly one bounded ring. */
    internal class Recorder(private val nanoTime: () -> Long = System::nanoTime) {
        private val lock = Any()
        private var currentRun = 0L
        private var startedAtNanos = 0L
        private var lastElapsedMillis = 0L
        private var eventCount = 0L
        private var droppedCount = 0L
        private val records = ArrayDeque<Record>()

        fun beginRun(): Long = synchronized(lock) {
            check(currentRun != Long.MAX_VALUE) { "task_voice_diagnostic_run_capacity" }
            currentRun += 1
            startedAtNanos = nanoTime()
            lastElapsedMillis = 0
            eventCount = 0
            droppedCount = 0
            records.clear()
            append(Event.RUN_STARTED, Details(), 0)
            currentRun
        }

        fun event(run: Long, type: Event, details: Details = Details()): Boolean = synchronized(lock) {
            if (run <= 0 || run != currentRun || type == Event.RUN_STARTED) return@synchronized false
            val duration = (nanoTime() - startedAtNanos).coerceAtLeast(0)
            val elapsed = maxOf(lastElapsedMillis, TimeUnit.NANOSECONDS.toMillis(duration))
            append(type, details.copy(
                handoffCount = details.handoffCount?.coerceIn(0, MAX_COUNTER),
                inputDelayMillis = details.inputDelayMillis?.coerceIn(0, MAX_INPUT_DELAY_MILLIS),
            ), elapsed)
            true
        }

        fun snapshot(): Snapshot? = synchronized(lock) {
            if (currentRun == 0L) null else Snapshot(currentRun, eventCount, droppedCount, records.toList())
        }

        private fun append(type: Event, details: Details, elapsed: Long) {
            if (records.size == MAX_RECORDS) {
                records.removeFirst()
                if (droppedCount < Long.MAX_VALUE) droppedCount += 1
            }
            if (eventCount < Long.MAX_VALUE) eventCount += 1
            lastElapsedMillis = elapsed
            records.addLast(Record(eventCount, elapsed, type, details))
        }
    }

    const val MAX_RECORDS = 96
    private const val MAX_COUNTER = 1_000_000_000
    private const val MAX_INPUT_DELAY_MILLIS = 120_000L
}
