package ai.hans.standard.voice.realtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled PCM and transcripts only: does not open a microphone, call or network connection. */
@RunWith(AndroidJUnit4::class)
class LiveAutoHangupAndroidTest {
    @Test fun farewellNeedsBothIntentAndFreshPlaybackQuiet() {
        val controller = LiveAutoHangupController()
        val id = requireNotNull(controller.arm("Tschüss!", 0L))
        controller.assistantTranscript("Tschüss!")
        fun activity(direction: LiveVoiceAudioDirection, active: Boolean, millis: Long) {
            val time = millis * 1_000_000L
            controller.activity(LiveVoiceAudioActivity(direction, active, time), time)
        }
        activity(LiveVoiceAudioDirection.INPUT, false, 300)
        activity(LiveVoiceAudioDirection.OUTPUT, true, 400)
        activity(LiveVoiceAudioDirection.OUTPUT, false, 700)
        assertFalse(controller.mayClose(id, "Tschüss!", 1_900_000_000L))
        activity(LiveVoiceAudioDirection.INPUT, false, 1_900)
        activity(LiveVoiceAudioDirection.OUTPUT, false, 1_900)
        assertTrue(controller.mayClose(id, "Tschüss!", 1_900_000_000L))
        assertFalse(controller.mayClose(id, "Tschüss, aber noch eine Frage", 1_900_000_000L))
    }

    @Test fun thanksAndQuotedFarewellDoNotArm() {
        val controller = LiveAutoHangupController()
        assertNull(controller.arm("Danke!", 0L))
        assertNull(controller.arm("Er sagte Tschüss", 0L))
        assertNull(controller.arm("Bitte nicht auflegen", 0L))
        assertNotNull(controller.arm("Bitte leg auf", 0L))
    }

    @Test fun freshUserSpeechCancelsPendingFarewell() {
        val controller = LiveAutoHangupController()
        val id = requireNotNull(controller.arm("Tschüss!", 0L))
        controller.assistantTranscript("Tschüss!")
        controller.activity(LiveVoiceAudioActivity(LiveVoiceAudioDirection.INPUT, false, 300L), 300L)
        controller.activity(LiveVoiceAudioActivity(LiveVoiceAudioDirection.INPUT, true, 400L), 400L)
        assertNull(controller.candidateId)
        assertFalse(controller.mayClose(id, "Tschüss!", 2_000_000_000L))
    }

    @Test fun publicAudioCallbacksExistWithoutStartingCapture() {
        val builder = JavaAudioDeviceModule.Builder::class.java
        assertNotNull(builder.getMethod("setPlaybackSamplesReadyCallback",
            JavaAudioDeviceModule.PlaybackSamplesReadyCallback::class.java))
        assertNotNull(builder.getMethod("setSamplesReadyCallback",
            JavaAudioDeviceModule.SamplesReadyCallback::class.java))
    }
}
