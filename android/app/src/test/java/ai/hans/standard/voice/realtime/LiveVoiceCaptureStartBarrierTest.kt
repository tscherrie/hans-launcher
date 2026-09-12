package ai.hans.standard.voice.realtime

import ai.hans.standard.voice.tts.throwingPhysicalPlayerCaptureBarrier
import ai.hans.standard.network.InternetStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LiveVoiceCaptureStartBarrierTest {
    @Test
    fun offlineLiveVoiceNeverRequestsCaptureOrAudioFocus() {
        val activity = LiveVoiceCaptureActivityState()
        var starts = 0
        var barriers = 0
        val result = requestLiveVoiceStartWithCaptureActivity(
            captureActivity = activity,
            captureStartBarrier = LiveVoiceCaptureStartBarrier { barriers += 1; true },
            internetStatus = InternetStatus.OFFLINE,
            requestStart = { starts += 1; LiveVoiceServiceCommandResult.REQUESTED },
        )
        assertEquals(LiveVoiceServiceCommandResult.NETWORK_UNAVAILABLE, result)
        assertEquals(0, starts)
        assertEquals(0, barriers)
        assertFalse(activity.isRequestedOrActive())
    }

    @Test
    fun unvalidatedVpnDoesNotPermanentlyLockOutExplicitLiveVoiceStart() {
        val result = requestLiveVoiceStartWithCaptureActivity(
            captureActivity = LiveVoiceCaptureActivityState(),
            captureStartBarrier = LiveVoiceCaptureStartBarrier { true },
            internetStatus = InternetStatus.LIMITED,
            requestStart = { LiveVoiceServiceCommandResult.REQUESTED },
        )
        assertEquals(LiveVoiceServiceCommandResult.REQUESTED, result)
    }

    @Test
    fun acknowledgedPhysicalStopCompletesBeforeLiveCaptureRequest() {
        val order = mutableListOf<String>()

        val result = requestLiveVoiceStartAfterAudioBarrier(
            captureStartBarrier = LiveVoiceCaptureStartBarrier {
                order += "physical-stop-acknowledged"
                true
            },
            requestStart = {
                order += "live-capture-requested"
                LiveVoiceServiceCommandResult.REQUESTED
            },
        )

        assertEquals(LiveVoiceServiceCommandResult.REQUESTED, result)
        assertEquals(
            listOf("physical-stop-acknowledged", "live-capture-requested"),
            order,
        )
    }

    @Test
    fun timeoutOrBarrierErrorFailsClosedWithoutRequestingLiveCapture() {
        var starts = 0
        val requestStart = {
            starts += 1
            LiveVoiceServiceCommandResult.REQUESTED
        }

        val timeout = requestLiveVoiceStartAfterAudioBarrier(
            captureStartBarrier = LiveVoiceCaptureStartBarrier { false },
            requestStart = requestStart,
        )
        val error = requestLiveVoiceStartAfterAudioBarrier(
            captureStartBarrier = LiveVoiceCaptureStartBarrier {
                error("TTS stop dispatcher unavailable")
            },
            requestStart = requestStart,
        )

        assertEquals(LiveVoiceServiceCommandResult.AUDIO_OUTPUT_NOT_STOPPED, timeout)
        assertEquals(LiveVoiceServiceCommandResult.AUDIO_OUTPUT_NOT_STOPPED, error)
        assertEquals(0, starts)
        assertFalse(timeout == LiveVoiceServiceCommandResult.REQUESTED)
    }

    @Test
    fun throwingPhysicalTtsPlayerPreventsLiveVoiceCaptureRequest() {
        var starts = 0
        val result = requestLiveVoiceStartAfterAudioBarrier(
            captureStartBarrier = LiveVoiceCaptureStartBarrier(
                throwingPhysicalPlayerCaptureBarrier(),
            ),
            requestStart = {
                starts += 1
                LiveVoiceServiceCommandResult.REQUESTED
            },
        )

        assertEquals(LiveVoiceServiceCommandResult.AUDIO_OUTPUT_NOT_STOPPED, result)
        assertEquals(0, starts)
    }
}
