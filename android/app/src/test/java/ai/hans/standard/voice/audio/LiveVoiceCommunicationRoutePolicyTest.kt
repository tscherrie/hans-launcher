package ai.hans.standard.voice.audio

import ai.hans.standard.voice.audio.LiveVoiceCommunicationRoutePolicy.Result
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveVoiceCommunicationRoutePolicyTest {
    @Test fun newCallRequiresActualSpeakerAndNeverInventsAConfirmation() {
        val policy = LiveVoiceCommunicationRoutePolicy()
        assertEquals(SpeechAudioRoute.SPEAKER, policy.requested)
        assertEquals(Result.PENDING, policy.routeChanged(SpeechAudioRoute.EARPIECE))
        assertEquals(Result.PENDING, policy.routeChanged(SpeechAudioRoute.UNKNOWN))
        assertEquals(Result.CONFIRMED, policy.routeChanged(SpeechAudioRoute.SPEAKER))
    }

    @Test fun explicitSwitchCanAwaitAndroidWithoutTreatingPreviousRouteAsLoss() {
        val policy = LiveVoiceCommunicationRoutePolicy()
        policy.routeChanged(SpeechAudioRoute.SPEAKER)
        policy.requestAccepted(SpeechAudioRoute.EARPIECE)
        assertEquals(Result.PENDING, policy.routeChanged(SpeechAudioRoute.SPEAKER))
        assertEquals(Result.CONFIRMED, policy.routeChanged(SpeechAudioRoute.EARPIECE))
        policy.requestAccepted(SpeechAudioRoute.SPEAKER)
        assertEquals(Result.PENDING, policy.routeChanged(SpeechAudioRoute.EARPIECE))
        assertEquals(Result.CONFIRMED, policy.routeChanged(SpeechAudioRoute.SPEAKER))
    }

    @Test fun acceptedButIneffectiveSwitchFailsOnBoundedDeadline() {
        val policy = LiveVoiceCommunicationRoutePolicy()
        policy.routeChanged(SpeechAudioRoute.SPEAKER)
        policy.requestAccepted(SpeechAudioRoute.EARPIECE)
        assertEquals(Result.LOST, policy.acknowledgementExpired(SpeechAudioRoute.SPEAKER))
    }

    @Test fun effectiveRouteAtDeadlineIsConfirmedEvenWhenCallbackWasDelayed() {
        val policy = LiveVoiceCommunicationRoutePolicy()
        policy.requestAccepted(SpeechAudioRoute.EARPIECE)
        assertEquals(Result.CONFIRMED, policy.acknowledgementExpired(SpeechAudioRoute.EARPIECE))
        assertEquals(Result.LOST, policy.routeChanged(SpeechAudioRoute.SPEAKER))
    }

    @Test fun privateRouteLossAndExternalTakeoverCannotSilentlyPlayOnAnotherDevice() {
        for (unexpected in listOf(SpeechAudioRoute.SPEAKER, SpeechAudioRoute.EXTERNAL, SpeechAudioRoute.UNKNOWN)) {
            val policy = LiveVoiceCommunicationRoutePolicy()
            policy.requestAccepted(SpeechAudioRoute.EARPIECE)
            policy.routeChanged(SpeechAudioRoute.EARPIECE)
            assertEquals(Result.LOST, policy.routeChanged(unexpected))
        }
    }

    @Test fun failedPlatformRequestDoesNotChangePreviouslyConfirmedChoice() {
        val policy = LiveVoiceCommunicationRoutePolicy()
        policy.routeChanged(SpeechAudioRoute.SPEAKER)
        // Android's rejected request never calls requestAccepted.
        assertEquals(SpeechAudioRoute.SPEAKER, policy.requested)
        assertEquals(Result.CONFIRMED, policy.routeChanged(SpeechAudioRoute.SPEAKER))
    }
}
