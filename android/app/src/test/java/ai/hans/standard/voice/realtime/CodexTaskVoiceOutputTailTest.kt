package ai.hans.standard.voice.realtime

import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CodexTaskVoiceOutputTailTest {
    @Test fun inertUntilTheOwnerConfirmsTaskCompletion() {
        val tail = CodexTaskVoiceOutputTail()
        assertNull(tail.nextDeadlineNanos)
        tail.observe(audio(1_000, true), ms(1_000))
        assertNull(tail.evaluate(ms(30_000)))
        assertNull(tail.nextDeadlineNanos)
    }

    @Test fun absentAudioClosesNormallyAfterBriefGraceWithoutWaitingForProof() {
        val tail = started()
        assertEquals(ms(1_500), tail.nextDeadlineNanos)
        assertNull(tail.evaluate(ms(1_499)))
        assertEquals(CodexTaskVoiceOutputTail.CloseReason.NO_OUTPUT, tail.evaluate(ms(1_500)))
        assertNull(tail.nextDeadlineNanos)
    }

    @Test fun lateTimerKeepsTheOriginalCloseReasonInsteadOfClaimingContinuousOutput() {
        val tail = started()
        assertEquals(CodexTaskVoiceOutputTail.CloseReason.NO_OUTPUT, tail.evaluate(ms(30_000)))
    }

    @Test fun delayedFinalReplyCanFinishThenCloseAfterQuiet() {
        val tail = started()
        tail.observe(audio(1_400, true), ms(1_400))
        assertEquals(ms(2_150), tail.nextDeadlineNanos)
        tail.observe(audio(2_000, true), ms(2_000))
        tail.observe(audio(2_100, false), ms(2_100))
        assertEquals(ms(2_750), tail.nextDeadlineNanos)
        assertNull(tail.evaluate(ms(2_749)))
        assertEquals(CodexTaskVoiceOutputTail.CloseReason.OUTPUT_QUIET_OR_GAP,
            tail.evaluate(ms(2_750)))
    }

    @Test fun disappearanceOfPcmAfterSpeechClosesWithoutAQuietReceipt() {
        val tail = started()
        tail.observe(audio(1_400, true), ms(1_400))
        assertEquals(CodexTaskVoiceOutputTail.CloseReason.OUTPUT_QUIET_OR_GAP,
            tail.evaluate(ms(2_150)))
    }

    @Test fun unreliableOutputAndBackgroundInputCannotExtendTheTail() {
        val tail = started()
        tail.observe(audio(1_000, true, reliable = false), ms(1_000))
        tail.observe(audio(1_400, true, direction = LiveVoiceAudioDirection.INPUT), ms(1_400))
        assertEquals(ms(1_500), tail.nextDeadlineNanos)
        assertEquals(CodexTaskVoiceOutputTail.CloseReason.NO_OUTPUT, tail.evaluate(ms(1_500)))
    }

    @Test fun unreliablePacketsAfterValidSpeechDoNotKeepOutputAlive() {
        val tail = started()
        tail.observe(audio(1_400, true), ms(1_400))
        tail.observe(audio(1_900, true, reliable = false), ms(1_900))
        tail.observe(audio(2_100, false, reliable = false), ms(2_100))
        assertEquals(ms(2_150), tail.nextDeadlineNanos)
        assertEquals(CodexTaskVoiceOutputTail.CloseReason.OUTPUT_QUIET_OR_GAP,
            tail.evaluate(ms(2_150)))
    }

    @Test fun continuousSpeechStillClosesAtTheAbsoluteBound() {
        val tail = started()
        for (time in 500L..19_500L step 500L) {
            tail.observe(audio(time, true), ms(time))
            assertNull(tail.evaluate(ms(time)))
        }
        assertEquals(ms(20_000), tail.nextDeadlineNanos)
        assertEquals(CodexTaskVoiceOutputTail.CloseReason.MAXIMUM_TAIL,
            tail.evaluate(ms(20_000)))
    }

    @Test fun staleDuplicatedFutureAndPreStartSamplesNeverExtendGrace() {
        val tail = CodexTaskVoiceOutputTail().apply { start(ms(1_000)) }
        tail.observe(audio(999, true), ms(1_001))
        tail.observe(audio(3_000, true), ms(1_100))
        tail.observe(audio(1_800, false), ms(1_800))
        tail.observe(audio(1_800, true), ms(1_900))
        tail.observe(audio(1_700, true), ms(2_000))
        assertEquals(ms(2_500), tail.nextDeadlineNanos)
        assertEquals(CodexTaskVoiceOutputTail.CloseReason.NO_OUTPUT, tail.evaluate(ms(2_500)))
    }

    @Test fun reversedClockCannotAdmitSpeechOrMoveTheDeadline() {
        val tail = started()
        assertNull(tail.evaluate(ms(1_000)))
        tail.observe(audio(900, true), ms(900))
        assertNull(tail.evaluate(ms(500)))
        assertEquals(ms(1_500), tail.nextDeadlineNanos)
        assertEquals(CodexTaskVoiceOutputTail.CloseReason.NO_OUTPUT, tail.evaluate(ms(1_500)))
    }

    @Test fun repeatedTerminalAndLateAudioCannotResurrectOrExtendSession() {
        val tail = started()
        tail.start(ms(1_400))
        assertEquals(ms(1_500), tail.nextDeadlineNanos)
        // A late audio callback itself makes overdue closure observable without reviving it.
        tail.observe(audio(1_600, true), ms(1_600))
        val result = tail.evaluate(ms(1_600))
        assertEquals(CodexTaskVoiceOutputTail.CloseReason.NO_OUTPUT, result)
        tail.start(ms(30_000))
        tail.observe(audio(30_100, true), ms(30_100))
        assertEquals(result, tail.evaluate(ms(30_200)))
        assertEquals(result, tail.evaluate(ms(100)))
        assertNull(tail.nextDeadlineNanos)
    }

    @Test fun graceIsNotShortenedByAnEarlyQuietFrame() {
        val tail = started()
        tail.observe(audio(100, true), ms(100))
        tail.observe(audio(200, false), ms(200))
        assertEquals(ms(1_500), tail.nextDeadlineNanos)
        assertNull(tail.evaluate(ms(1_499)))
        assertEquals(CodexTaskVoiceOutputTail.CloseReason.OUTPUT_QUIET_OR_GAP,
            tail.evaluate(ms(1_500)))
    }

    private fun started() = CodexTaskVoiceOutputTail().apply { start(0) }
    private fun ms(value: Long) = TimeUnit.MILLISECONDS.toNanos(value)
    private fun audio(atMillis: Long, active: Boolean, reliable: Boolean = true,
        direction: LiveVoiceAudioDirection = LiveVoiceAudioDirection.OUTPUT) =
        LiveVoiceAudioActivity(direction, active, ms(atMillis), reliable)
}
