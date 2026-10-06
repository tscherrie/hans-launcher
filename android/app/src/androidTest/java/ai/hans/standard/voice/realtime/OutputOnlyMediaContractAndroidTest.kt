package ai.hans.standard.voice.realtime

import ai.hans.standard.voice.audio.OutputOnlyAudioRoutePolicy
import ai.hans.standard.voice.audio.OutputOnlyAudioRouteTarget
import ai.hans.standard.voice.audio.SpeechAudioRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic contract only: no microphone, speaker, native peer or network connection is opened. */
@RunWith(AndroidJUnit4::class)
class OutputOnlyMediaContractAndroidTest {
    @Test fun outputOnlyCannotCaptureInAnyStartupMuteOrTerminalState() {
        val mode = LiveVoiceMediaMode.OUTPUT_ONLY
        assertFalse(mode.physicalCapture)
        assertFalse(mode.playsConnectionTones)
        assertTrue(mode.receivesOnly)
        listOf(false, true).forEach { started ->
            listOf(false, true).forEach { muted ->
                listOf(false, true).forEach { ended ->
                    assertFalse(mode.permitsCapture(started, muted, ended))
                    assertEquals(started && !ended, mode.permitsOutput(started, ended))
                }
            }
        }
    }

    @Test fun nativeBoundaryKeepsCaptureOffThroughReadyAndStop() {
        var recording = true
        var output = false
        val changes = mutableListOf<Boolean>()
        fun apply(started: Boolean, ended: Boolean) = LiveVoiceReceiveOnlyMediaState.apply(
            LiveVoiceMediaMode.OUTPUT_ONLY, started, ended, localInputAbsent = true,
            setRecording = { recording = it; changes += it },
            setPlayout = { output = it },
        )
        assertTrue(apply(started = false, ended = false))
        assertFalse(recording)
        assertFalse(output)
        assertTrue(apply(started = true, ended = false))
        assertFalse(recording)
        assertTrue(output)
        assertTrue(apply(started = true, ended = true))
        assertFalse(recording)
        assertFalse(output)
        assertTrue(changes.all { !it })
    }

    @Test fun unexpectedLocalInputFailsClosedAndBufferedModeStaysSilent() {
        var output = true
        assertFalse(LiveVoiceReceiveOnlyMediaState.apply(LiveVoiceMediaMode.OUTPUT_ONLY,
            started = true, ended = false, localInputAbsent = false,
            setRecording = { assertFalse(it) }, setPlayout = { output = it }))
        assertFalse(output)
        assertTrue(LiveVoiceReceiveOnlyMediaState.apply(LiveVoiceMediaMode.BUFFERED_DICTATION_SILENT,
            started = true, ended = false, localInputAbsent = true,
            setRecording = { assertFalse(it) }, setPlayout = { output = it }))
        assertFalse(output)
    }

    @Test fun externalAndPrivateRouteNeverFallBackToSpeaker() {
        assertNull(OutputOnlyAudioRoutePolicy.select(null, true,
            setOf(SpeechAudioRoute.SPEAKER)))
        assertNull(OutputOnlyAudioRoutePolicy.select(SpeechAudioRoute.EARPIECE,
            false, setOf(SpeechAudioRoute.SPEAKER)))
        val headset = OutputOnlyAudioRouteTarget(9, 7, SpeechAudioRoute.EXTERNAL)
        assertTrue(OutputOnlyAudioRoutePolicy.matches(headset, headset, true, true))
        assertFalse(OutputOnlyAudioRoutePolicy.matches(headset, headset.copy(deviceId = 10), true, true))
        assertFalse(OutputOnlyAudioRoutePolicy.matches(headset,
            OutputOnlyAudioRouteTarget(2, 2, SpeechAudioRoute.SPEAKER), true, false))
    }

    @Test fun firstReadAloudDefaultsToSpeakerAndPrivateSelectionSurvivesEndpointRemoval() {
        val all = setOf(SpeechAudioRoute.SPEAKER, SpeechAudioRoute.EARPIECE, SpeechAudioRoute.EXTERNAL)
        assertEquals(SpeechAudioRoute.SPEAKER, OutputOnlyAudioRoutePolicy.select(null, false, all))
        val captured = OutputOnlyAudioRoutePolicy.capturePreference(
            ai.hans.standard.voice.audio.SpeechAudioRouteState(active = true, effective = SpeechAudioRoute.EARPIECE), false)
        assertEquals(SpeechAudioRoute.EARPIECE, OutputOnlyAudioRoutePolicy.select(captured, false, all))
        assertNull(OutputOnlyAudioRoutePolicy.select(captured, false, setOf(SpeechAudioRoute.SPEAKER)))
    }

    @Test fun explicitRouteSwitchMutesUntilBothRouteAndSessionAreReady() {
        val gate = OutputOnlyAudioPlaybackGate()
        var enabled = true
        assertTrue(gate.attach("output", { enabled = it; true }, { enabled }))
        assertFalse(enabled)
        gate.setReady(true)
        assertFalse(enabled)
        gate.setRouteConfirmed(true)
        assertTrue(enabled)
        gate.setRouteConfirmed(false)
        assertFalse(enabled)
        gate.setReady(true)
        assertFalse(enabled)
        gate.setRouteConfirmed(true)
        assertTrue(enabled)
        assertTrue(gate.close())
        gate.setRouteConfirmed(true)
        assertFalse(enabled)
    }

    @Test fun rtcCallbackMutesNewTracksEvenWhenTerminalQueueDiscardsAdmission() {
        var enabled = true
        var queued = false
        OutputOnlyRemoteTrackAdmission.receive({ listOf("new-native-track") }, mute = {
            enabled = false
            true
        }, enqueue = {
            assertFalse(enabled)
            // Simulates dispatchControl dropping the task after terminal became true.
            queued = false
        }, onFailure = { error("unexpected failure") })
        assertFalse(enabled)
        assertFalse(queued)
    }
}
