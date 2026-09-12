package ai.hans.standard.diagnostics

import ai.hans.standard.voice.realtime.LiveVoiceCaptureActivityObserver
import ai.hans.standard.voice.realtime.LiveVoiceCaptureActivityState
import ai.hans.standard.voice.realtime.LiveVoiceCaptureStartBarrier
import ai.hans.standard.voice.realtime.LiveVoiceServiceCommandResult
import ai.hans.standard.voice.realtime.requestLiveVoiceStartWithCaptureActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MigrationLiveVoiceReadinessTest {
    @Test
    fun startRequestWindowIsPublishedEvenBeforeARealtimePhaseExists() {
        val activity = LiveVoiceCaptureActivityState()
        val observed = mutableListOf<Boolean>()
        val registration = activity.addObserver(
            LiveVoiceCaptureActivityObserver { active -> observed += active },
        )

        val result = requestLiveVoiceStartWithCaptureActivity(
            captureActivity = activity,
            captureStartBarrier = LiveVoiceCaptureStartBarrier { false },
            requestStart = { error("capture barrier must fail first") },
        )

        assertEquals(LiveVoiceServiceCommandResult.AUDIO_OUTPUT_NOT_STOPPED, result)
        assertEquals(listOf(false, true, false), observed)
        assertFalse(activity.isRequestedOrActive())
        registration.cancel()
    }

    @Test
    fun unchangedSignalsAreDeduplicatedAndCancellationStopsPublication() {
        val activity = LiveVoiceCaptureActivityState()
        val observed = mutableListOf<Boolean>()
        val registration = activity.addObserver { observed += it }

        activity.publish(true)
        activity.publish(true)
        registration.cancel()
        activity.publish(false)

        assertEquals(listOf(false, true), observed)
    }
}
