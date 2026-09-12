package ai.hans.standard.voice.realtime

import ai.hans.standard.voice.realtime.LiveVoiceResponseArbiter.Purpose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveVoiceResponseArbiterTest {
    @Test
    fun activeResponseDefersAndCoalescesCreatesUntilMatchingDone() {
        val arbiter = LiveVoiceResponseArbiter()

        assertEquals(Purpose.TASK_PROGRESS, arbiter.request(Purpose.TASK_PROGRESS)?.purpose)
        arbiter.responseStarted("progress-response")
        assertNull(arbiter.request(Purpose.TASK_PROGRESS))
        assertNull(arbiter.request(Purpose.TASK_RESULT))

        assertEquals(
            LiveVoiceResponseArbiter.FinishDisposition.Ignored,
            arbiter.responseFinished("different-response"),
        )
        assertEquals(
            Purpose.TASK_RESULT,
            matched(arbiter.responseFinished("progress-response")).next?.purpose,
        )
        assertEquals(
            LiveVoiceResponseArbiter.FinishDisposition.Ignored,
            arbiter.responseFinished("progress-response"),
        )
        assertNull(arbiter.request(Purpose.TASK_PROGRESS))
        arbiter.responseStarted("task-result-response")
        assertEquals(
            Purpose.TASK_PROGRESS,
            matched(arbiter.responseFinished("task-result-response")).next?.purpose,
        )
    }

    @Test
    fun activeResponseSchedulingConflictRequeuesCreateExactlyOnce() {
        val arbiter = LiveVoiceResponseArbiter()

        val command = arbiter.request(Purpose.TASK_RESULT)!!
        assertTrue(arbiter.responseSchedulingConflict(command.eventId))
        assertNull(arbiter.request(Purpose.TASK_PROGRESS))
        arbiter.responseStarted("server-vad-response")

        assertEquals(
            Purpose.TASK_RESULT,
            matched(arbiter.responseFinished("server-vad-response")).next?.purpose,
        )
        assertEquals(
            LiveVoiceResponseArbiter.FinishDisposition.Ignored,
            arbiter.responseFinished("server-vad-response"),
        )
    }

    @Test
    fun responseCreatedBeforeConflictStillRecoversSubmittedTaskResult() {
        val arbiter = LiveVoiceResponseArbiter()
        val command = arbiter.request(Purpose.TASK_RESULT)!!

        arbiter.responseStarted("server-vad-response")
        assertTrue(arbiter.responseSchedulingConflict(command.eventId))

        assertEquals(
            Purpose.TASK_RESULT,
            matched(arbiter.responseFinished("server-vad-response")).next?.purpose,
        )
    }

    @Test
    fun staleDoneAfterNextResponseStartsIsIgnoredWithoutDrainingOrClearingIt() {
        val arbiter = LiveVoiceResponseArbiter()
        arbiter.request(Purpose.TASK_PROGRESS)
        arbiter.responseStarted("response-one")
        arbiter.request(Purpose.TASK_RESULT)
        val second = matched(arbiter.responseFinished("response-one")).next!!
        assertEquals(Purpose.TASK_RESULT, second.purpose)
        arbiter.responseStarted("response-two")

        assertEquals(
            LiveVoiceResponseArbiter.FinishDisposition.Ignored,
            arbiter.responseFinished("response-one"),
        )
        assertNull(arbiter.request(Purpose.TASK_PROGRESS))
        assertEquals(
            Purpose.TASK_PROGRESS,
            matched(arbiter.responseFinished("response-two")).next?.purpose,
        )
    }

    @Test
    fun spokenUserTurnWinsDeferredProgressAndUsesExactlyOneCreate() {
        val arbiter = LiveVoiceResponseArbiter()
        val active = arbiter.request(Purpose.TASK_RESULT)!!
        arbiter.responseStarted("task-result")

        assertNull(arbiter.request(Purpose.TASK_PROGRESS))
        assertNull(arbiter.request(Purpose.USER_TURN))

        val finished = matched(arbiter.responseFinished("task-result"))
        assertEquals(active, finished.completed)
        assertEquals(Purpose.USER_TURN, finished.next?.purpose)
        assertTrue(finished.next?.eventId?.startsWith("hans-response-") == true)
    }

    private fun matched(
        disposition: LiveVoiceResponseArbiter.FinishDisposition,
    ): LiveVoiceResponseArbiter.FinishDisposition.Matched {
        assertTrue(disposition is LiveVoiceResponseArbiter.FinishDisposition.Matched)
        return disposition as LiveVoiceResponseArbiter.FinishDisposition.Matched
    }
}
