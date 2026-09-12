package ai.hans.standard.voice.realtime

import ai.hans.standard.voice.audio.SpeechAudioRoute
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class LiveVoiceSpeakerGainTest {
    @Test fun onlyFreshlyConfirmedBuiltInSpeakerCanBeBoosted() {
        for (route in SpeechAudioRoute.entries) {
            assertEquals(if (route == SpeechAudioRoute.SPEAKER) 2.0 else 1.0,
                LiveVoiceSpeakerGainPolicy.desired(route, true), 0.0)
            assertEquals(1.0, LiveVoiceSpeakerGainPolicy.desired(route, false), 0.0)
        }
    }

    @Test fun unknownRouteStartsNeutralThenConfirmedSpeakerDoublesGain() {
        LiveVoiceSpeakerGain({ true }).use { gain ->
            val track = Track()
            assertTrue(gain.attach("remote", track))
            assertEquals(1.0, track.value, 0.0)
            assertTrue(gain.routeConfirmed(SpeechAudioRoute.SPEAKER))
            assertEquals(2.0, track.value, 0.0)
        }
    }

    @Test fun speakerConfirmedBeforeTrackArrivalAppliesToNewTrack() {
        LiveVoiceSpeakerGain({ true }).use { gain ->
            gain.routeConfirmed(SpeechAudioRoute.SPEAKER)
            val track = Track()
            assertTrue(gain.attach("remote", track))
            assertEquals(2.0, track.value, 0.0)
        }
    }

    @Test fun explicitSwitchIsNeutralBeforeCallerMayChangeAndroidRoute() {
        LiveVoiceSpeakerGain({ true }).use { gain ->
            val first = Track()
            val second = Track()
            gain.routeConfirmed(SpeechAudioRoute.SPEAKER)
            gain.attach("first", first)
            gain.attach("second", second)
            assertTrue(gain.beforeRouteChange())
            assertEquals(1.0, first.value, 0.0)
            assertEquals(1.0, second.value, 0.0)
            // A track arriving during the switch cannot use the old speaker proof.
            val during = Track()
            gain.attach("during", during)
            assertEquals(1.0, during.value, 0.0)
            gain.routeConfirmed(SpeechAudioRoute.EARPIECE)
            assertEquals(1.0, first.value, 0.0)
        }
    }

    @Test fun externalOrUnknownRouteImmediatelyReturnsToNeutral() {
        LiveVoiceSpeakerGain({ true }).use { gain ->
            val track = Track()
            gain.attach("remote", track)
            for (route in listOf(SpeechAudioRoute.EXTERNAL, SpeechAudioRoute.UNKNOWN, SpeechAudioRoute.EARPIECE)) {
                gain.routeConfirmed(SpeechAudioRoute.SPEAKER)
                assertTrue(gain.routeConfirmed(route))
                assertEquals(1.0, track.value, 0.0)
            }
        }
    }

    @Test fun effectiveRouteChangingInsideNativeGainWriteCannotRetainBoost() {
        var speaker = true
        val track = Track().apply { afterSet = { if (it == 2.0) speaker = false } }
        LiveVoiceSpeakerGain({ speaker }).use { gain ->
            gain.routeConfirmed(SpeechAudioRoute.SPEAKER)
            assertTrue(gain.attach("remote", track))
            assertEquals(listOf(2.0, 1.0), track.writes)
            assertEquals(1.0, track.value, 0.0)
        }
    }

    @Test fun unsupportedBoostFallsBackOnlyToVerifiedNeutral() {
        val track = Track().apply { acceptBoost = false }
        val proof = mutableListOf<Pair<Double, Double>>()
        LiveVoiceSpeakerGain({ true }, onVerified = { _, requested, actual -> proof += requested to actual }).use { gain ->
            gain.routeConfirmed(SpeechAudioRoute.SPEAKER)
            assertTrue(gain.attach("remote", track))
            assertEquals(1.0, track.value, 0.0)
            assertEquals(2.0 to 1.0, proof.last())
            assertFalse(track.disabled)
        }
    }

    @Test fun focusLossOverridesSpeakerRouteCallbacksUntilFocusAndCommunicationModeReturn() {
        var communicationSpeaker = true
        LiveVoiceSpeakerGain({ communicationSpeaker }).use { gain ->
            val track = Track()
            gain.routeConfirmed(SpeechAudioRoute.SPEAKER)
            gain.attach("remote", track)
            assertEquals(2.0, track.value, 0.0)
            assertTrue(gain.audioFocusChanged(false))
            assertEquals(1.0, track.value, 0.0)
            gain.routeConfirmed(SpeechAudioRoute.SPEAKER)
            assertEquals(1.0, track.value, 0.0)
            communicationSpeaker = false // MODE_IN_CALL with the same physical speaker.
            gain.audioFocusChanged(true)
            assertEquals(1.0, track.value, 0.0)
            gain.routeConfirmed(SpeechAudioRoute.SPEAKER)
            assertEquals(1.0, track.value, 0.0)
            communicationSpeaker = true // Public mode callback reproves the effective route.
            gain.routeConfirmed(SpeechAudioRoute.SPEAKER)
            assertEquals(2.0, track.value, 0.0)
        }
    }

    @Test fun focusLossWithUnverifiableNeutralGainDisablesTheTrackAndReportsFailure() {
        var failures = 0
        LiveVoiceSpeakerGain({ true }, onUnsafeGain = { failures++ }).use { gain ->
            val track = Track()
            gain.routeConfirmed(SpeechAudioRoute.SPEAKER)
            gain.attach("remote", track)
            track.rejectNeutral = true
            assertFalse(gain.audioFocusChanged(false))
            assertTrue(track.disabled)
            assertEquals(1, failures)
        }
    }

    @Test fun initialFocusAcknowledgementCannotUndoANewerFocusLossCallback() {
        LiveVoiceSpeakerGain({ true }).use { gain ->
            val track = Track()
            gain.routeConfirmed(SpeechAudioRoute.SPEAKER)
            gain.attach("remote", track)
            gain.beginAudioFocusRequest()
            assertEquals(1.0, track.value, 0.0)
            gain.audioFocusChanged(false)
            gain.confirmInitialAudioFocusGrant()
            assertEquals(1.0, track.value, 0.0)
            gain.audioFocusChanged(true)
            assertEquals(2.0, track.value, 0.0)
        }
    }

    @Test fun failedNeutralReadbackBlocksSwitchAndDisablesUncertainTrack() {
        var failures = 0
        val track = Track()
        LiveVoiceSpeakerGain({ true }, onUnsafeGain = { failures++ }).use { gain ->
            gain.routeConfirmed(SpeechAudioRoute.SPEAKER)
            gain.attach("remote", track)
            track.rejectNeutral = true
            assertFalse(gain.beforeRouteChange())
            assertTrue(track.disabled)
            assertEquals(1, failures)
        }
    }

    @Test fun invalidNativeReadbackNeverCountsAsVerifiedGain() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            val track = Track().apply { forcedRead = invalid }
            LiveVoiceSpeakerGain({ true }).use { gain ->
                gain.routeConfirmed(SpeechAudioRoute.SPEAKER)
                assertFalse(gain.attach("remote", track))
                assertTrue(track.disabled)
            }
        }
    }

    @Test fun closeNeutralizesBeforeRemoteObjectsAreReleasedAndRejectsStaleRouteCallbacks() {
        val track = Track()
        val gain = LiveVoiceSpeakerGain({ true })
        gain.routeConfirmed(SpeechAudioRoute.SPEAKER)
        gain.attach("remote", track)
        gain.close()
        assertEquals(1.0, track.value, 0.0)
        val calls = track.writes.toList()
        assertFalse(gain.routeConfirmed(SpeechAudioRoute.SPEAKER))
        assertFalse(gain.attach("late", Track()))
        gain.close()
        assertEquals(calls, track.writes)
    }

    @Test fun reconnectUsesANewNeutralLeaseInsteadOfPreviousSpeakerProof() {
        LiveVoiceSpeakerGain({ true }).use { old ->
            old.routeConfirmed(SpeechAudioRoute.SPEAKER)
            old.attach("remote", Track())
        }
        LiveVoiceSpeakerGain({ true }).use { replacement ->
            val track = Track()
            replacement.attach("remote", track)
            assertEquals(1.0, track.value, 0.0)
        }
    }

    @Test fun routingCallerDoesNotNeedTheBlockedTransportControlWorker() {
        val gain = LiveVoiceSpeakerGain({ true })
        val transportControl = LiveVoiceTransportControl()
        val transportWaiting = CountDownLatch(1)
        val releaseTransport = CountDownLatch(1)
        transportControl.execute {
            transportWaiting.countDown()
            releaseTransport.await(3, TimeUnit.SECONDS)
        }
        assertTrue(transportWaiting.await(1, TimeUnit.SECONDS))
        val done = CountDownLatch(1)
        var applied = false
        val routeThread = Thread {
            applied = gain.routeConfirmed(SpeechAudioRoute.SPEAKER) && gain.beforeRouteChange()
            done.countDown()
        }
        routeThread.start()
        try {
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertTrue(applied)
        } finally {
            releaseTransport.countDown()
            transportControl.close()
            gain.close()
            routeThread.join(2_000)
        }
    }

    private class Track : LiveVoiceSpeakerGainTrack {
        var value = 1.0
        val writes = mutableListOf<Double>()
        var disabled = false
        var acceptBoost = true
        var rejectNeutral = false
        var forcedRead: Double? = null
        var afterSet: (Double) -> Unit = {}
        override fun setGain(value: Double) {
            writes += value
            if ((value != 2.0 || acceptBoost) && (value != 1.0 || !rejectNeutral)) this.value = value
            afterSet(value)
        }
        override fun readGain(): Double = forcedRead ?: value
        override fun disable() { disabled = true }
    }
}
