package ai.hans.standard.voice.audio

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidLiveVoicePrivateRouteContractTest {
    @Test
    fun liveWebRtcRequiresVerifiedSpeakerButKeepsPrivateEarpieceVerificationForTts() {
        val transport = source(
            "voice/realtime/AndroidWebRtcRealtimeTransport.kt",
        )
        val routing = source(
            "voice/audio/AndroidSpeechAudioRouteController.kt",
        )

        assertTrue(transport.contains("attachRequiredLiveCommunication"))
        assertTrue(transport.contains("MODE_IN_COMMUNICATION"))
        assertTrue(transport.contains("communicationModeRequest?.close()"))
        // Cleanup must release our mode lease even if telephony now owns the effective mode.
        // The separate speaker-gain probe legitimately requires MODE_IN_COMMUNICATION.
        val restoreAudio = transport.substringAfter("private fun restoreCommunicationAudio()")
            .substringBefore("companion object")
        assertFalse(restoreAudio.contains("manager.mode == AudioManager.MODE_IN_COMMUNICATION"))
        assertFalse(transport.contains("runCatching { audioRoutes?.attachCommunication()"))
        assertTrue(routing.contains("AudioDeviceInfo.TYPE_BUILTIN_EARPIECE"))
        assertTrue(routing.contains("endpoint.verifyPrivateRoute()"))
        assertTrue(routing.contains("request(SpeechAudioRoute.SPEAKER)"))
        assertTrue(routing.contains("endpoint.verifyLiveRoute()"))
        assertTrue(routing.contains("liveRouteArmed"))
        assertTrue(routing.contains("realtime_speaker_route_not_effective"))
        assertTrue(routing.contains("if (!liveCommunication) relinquishForExternalDevice()"))
        assertTrue(routing.contains("liveRouteTimeout?.let(handler::removeCallbacks)"))
    }

    @Test fun liveGainIsReadBackNeutralBeforeExplicitRouteMutationAndBeforePeerDisposal() {
        val transport = source("voice/realtime/AndroidWebRtcRealtimeTransport.kt")
        val routing = source("voice/audio/AndroidSpeechAudioRouteController.kt")
        assertTrue(transport.contains("beforeRouteChange = speakerGain::beforeRouteChange"))
        assertTrue(transport.contains("onConfirmedRoute = speakerGain::routeConfirmed"))
        assertTrue(transport.contains("override fun readGain(): Double = track.getVolume()"))
        assertTrue(routing.indexOf("!beforeLiveRouteChange()") < routing.indexOf("manager?.setCommunicationDevice(device)"))
        assertTrue(transport.indexOf("speakerGain.close()") < transport.indexOf("peerConnection?.close()"))
        assertTrue(transport.contains(".setSamplesReadyCallback"))
        assertTrue(transport.contains(".setPlaybackSamplesReadyCallback"))
        assertFalse(transport.contains("setStreamVolume("))
    }

    private fun source(relative: String): String = sequenceOf(
        File("src/main/java/ai/hans/standard/$relative"),
        File("android/app/src/main/java/ai/hans/standard/$relative"),
    ).first(File::isFile).readText()
}
