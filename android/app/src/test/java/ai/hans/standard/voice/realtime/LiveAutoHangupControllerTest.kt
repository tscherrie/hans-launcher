package ai.hans.standard.voice.realtime

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAutoHangupControllerTest {
    private val controller = LiveAutoHangupController()
    private val origin = TimeUnit.SECONDS.toNanos(100)
    private fun time(ms: Long) = origin + TimeUnit.MILLISECONDS.toNanos(ms)
    private fun input(ms: Long, active: Boolean = false, receivedMs: Long = ms) =
        controller.activity(LiveVoiceAudioActivity(LiveVoiceAudioDirection.INPUT, active, time(ms)), time(receivedMs))
    private fun output(ms: Long, active: Boolean, receivedMs: Long = ms) =
        controller.activity(LiveVoiceAudioActivity(LiveVoiceAudioDirection.OUTPUT, active, time(ms)), time(receivedMs))
    private fun arm(text: String = "Tschüss!") = requireNotNull(controller.arm(text, origin))
    private fun farewell() = controller.assistantTranscript("Tschüss!")
    private fun playback() {
        input(100)
        output(200, true)
        output(600, false)
        input(1_800)
        output(1_800, false)
    }

    @Test fun exactOwnFarewellAssistantFarewellAndFreshRealQuietClose() {
        val id = arm()
        farewell()
        playback()
        assertTrue(controller.mayClose(id, "Tschüss!", time(1_800)))
    }

    @Test fun quotesThanksTaskCompletionAndEmbeddedGoodbyeNeverArm() {
        listOf("Danke", "Die Aufgabe ist fertig", "Er sagte Tschüss", "\"Tschüss\"", "Tschüss, aber warte",
            "Sag bitte Tschüss", "Wenn ich Tschüss sage, leg auf", "", " ").forEach {
            assertNull(it, controller.arm(it, origin))
        }
        listOf("Bitte leg auf", "Beende den Anruf", "Auf Wiedersehen!", "Okay, danke, tschüss Hans").forEach {
            assertNotNull(it, controller.arm(it, origin))
        }
    }

    @Test fun transcriptsAndElapsedTimeNeverSubstituteForAudio() {
        val id = arm()
        farewell()
        assertFalse(controller.mayClose(id, "Tschüss!", time(10_000)))
        input(10_000)
        output(10_000, false)
        assertFalse(controller.mayClose(id, "Tschüss!", time(10_000)))
    }

    @Test fun outputMustBecomeActiveBeforeQuiet() {
        val id = arm()
        farewell()
        input(100)
        output(100, false)
        input(2_000)
        output(2_000, false)
        assertNull(controller.quietDeadlineNanos)
        assertFalse(controller.mayClose(id, "Tschüss!", time(2_000)))
    }

    @Test fun missingAssistantFarewellOrContinuationFailsOpen() {
        val id = arm()
        playback()
        assertFalse(controller.mayClose(id, "Tschüss!", time(1_800)))
        controller.assistantTranscript("Tschüss, aber hier ist noch ein Ergebnis.")
        assertFalse(controller.mayClose(id, "Tschüss!", time(1_800)))
    }

    @Test fun quietTimerAloneAndStaleInputOrOutputProofNeverClose() {
        val id = arm()
        farewell()
        input(100)
        output(200, true)
        output(600, false)
        input(1_800)
        assertFalse(controller.mayClose(id, "Tschüss!", time(1_800)))
        output(1_800, false)
        assertTrue(controller.mayClose(id, "Tschüss!", time(1_800)))
        assertFalse(controller.mayClose(id, "Tschüss!", time(2_301)))
        output(2_301, false)
        assertFalse(controller.mayClose(id, "Tschüss!", time(2_301)))
        input(2_301)
        assertTrue(controller.mayClose(id, "Tschüss!", time(2_301)))
    }

    @Test fun initialInputQuietProofIsRequired() {
        val id = arm()
        farewell()
        output(200, true)
        output(600, false)
        output(1_800, false)
        assertFalse(controller.mayClose(id, "Tschüss!", time(1_800)))
    }

    @Test fun newInputSpeechCancelsEvenAfterAllOtherProof() {
        val id = arm()
        farewell()
        playback()
        input(1_801, true)
        assertNull(controller.candidateId)
        assertFalse(controller.mayClose(id, "Tschüss!", time(1_801)))
    }

    @Test fun ownAsrContinuationCannotCloseAndExactRearmNeedsEntirelyNewProof() {
        val old = arm()
        farewell()
        playback()
        assertFalse(controller.mayClose(old, "Tschüss! Warte noch.", time(1_800)))
        val newer = requireNotNull(controller.arm("Tschüss!", time(2_000)))
        assertFalse(controller.mayClose(newer, "Tschüss!", time(2_000)))
        assertFalse(controller.mayClose(old, "Tschüss!", time(2_000)))
    }

    @Test fun oldWindowFutureAndOutOfOrderCallbacksCannotSupplyProof() {
        val id = requireNotNull(controller.arm("Tschüss!", time(1_000)))
        farewell()
        output(200, true, 1_001)
        output(1_100, true, 1_001)
        input(1_100)
        output(1_101, false)
        output(1_050, true, 1_101)
        input(2_500)
        output(2_500, false)
        assertNull(controller.quietDeadlineNanos)
        assertFalse(controller.mayClose(id, "Tschüss!", time(2_500)))
    }

    @Test fun resumedOutputRestartsGrace() {
        val id = arm()
        farewell()
        playback()
        output(1_802, true)
        assertNull(controller.quietDeadlineNanos)
        output(1_900, false)
        assertEquals(time(3_100), controller.quietDeadlineNanos)
        input(3_099)
        output(3_099, false)
        assertFalse(controller.mayClose(id, "Tschüss!", time(3_099)))
        input(3_100)
        output(3_100, false)
        assertTrue(controller.mayClose(id, "Tschüss!", time(3_100)))
    }

    @Test fun delayedActiveInputOrOutputInvalidatesCandidateInsteadOfMaskingSpeech() {
        for (direction in LiveVoiceAudioDirection.entries) {
            val id = arm()
            farewell()
            playback()
            controller.activity(LiveVoiceAudioActivity(direction, true, time(1_700)), time(1_801))
            assertNull(controller.candidateId)
            assertFalse(controller.mayClose(id, "Tschüss!", time(1_801)))
        }
    }

    @Test fun preFarewellAudioCannotBeBorrowedWhenFarewellTranscriptArrivesLate() {
        val id = arm()
        playback()
        controller.assistantTranscript("Tschüss!", time(1_900))
        assertFalse(controller.mayClose(id, "Tschüss!", time(1_900)))
        input(3_200)
        output(3_200, false)
        assertFalse(controller.mayClose(id, "Tschüss!", time(3_200)))
        output(3_300, true)
        output(3_500, false)
        input(4_700)
        output(4_700, false)
        assertTrue(controller.mayClose(id, "Tschüss!", time(4_700)))
    }

    @Test fun unreliableAudioInvalidatesCandidateWithoutTreatingGapAsSilence() {
        val id = arm()
        farewell()
        playback()
        controller.activity(LiveVoiceAudioActivity(LiveVoiceAudioDirection.INPUT, false, time(1_801),
            reliable = false), time(1_801))
        assertNull(controller.candidateId)
        assertFalse(controller.mayClose(id, "Tschüss!", time(1_801)))
    }

    @Test fun maxAgeAndExplicitCancelFailOpenEvenWithLateValidProof() {
        val id = arm()
        farewell()
        playback()
        input(20_000)
        output(20_000, false)
        assertFalse(controller.mayClose(id, "Tschüss!", time(20_000)))
        controller.cancel()
        assertNull(controller.candidateId)
        assertNull(controller.expiresAtNanos)
        assertNull(controller.quietDeadlineNanos)
    }
}
