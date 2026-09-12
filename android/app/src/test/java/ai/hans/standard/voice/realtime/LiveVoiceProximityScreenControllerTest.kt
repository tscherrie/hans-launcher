package ai.hans.standard.voice.realtime

import ai.hans.standard.voice.audio.SpeechAudioRoute
import ai.hans.standard.voice.audio.SpeechAudioRouteState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveVoiceProximityScreenControllerTest {
    @Test
    fun leaseExistsOnlyForActiveCallPhasesAndIsReleasedOnStop() {
        val lease = FakeProximityScreenLease()
        val controller = LiveVoiceProximityScreenController.forTest(lease)

        controller.onAudioRoute(earpiece())
        controller.onSnapshot(LiveVoiceSnapshot(phase = LiveVoicePhase.IDLE))
        controller.onSnapshot(LiveVoiceSnapshot(phase = LiveVoicePhase.CONNECTING))
        controller.onSnapshot(LiveVoiceSnapshot(phase = LiveVoicePhase.LISTENING))

        assertTrue(controller.isHeldForTest())
        assertEquals(1, lease.acquireCount)
        assertEquals(0, lease.releaseCount)

        controller.onSnapshot(LiveVoiceSnapshot(phase = LiveVoicePhase.STOPPED))

        assertFalse(controller.isHeldForTest())
        assertEquals(1, lease.releaseCount)
    }

    @Test
    fun failureAndCloseBothReleaseWithoutDoubleRelease() {
        val lease = FakeProximityScreenLease()
        val controller = LiveVoiceProximityScreenController.forTest(lease)
        controller.onAudioRoute(earpiece())
        controller.onSnapshot(LiveVoiceSnapshot(phase = LiveVoicePhase.USER_SPEAKING))

        controller.release()
        controller.close()

        assertFalse(controller.isHeldForTest())
        assertEquals(1, lease.releaseCount)
    }

    @Test
    fun unsupportedOrFailedAcquisitionGracefullyFallsBackAndCanRetry() {
        val lease = FakeProximityScreenLease(failFirstAcquire = true)
        val controller = LiveVoiceProximityScreenController.forTest(lease)

        controller.onAudioRoute(earpiece())
        controller.onSnapshot(LiveVoiceSnapshot(phase = LiveVoicePhase.CONNECTING))
        assertFalse(controller.isHeldForTest())
        controller.onSnapshot(LiveVoiceSnapshot(phase = LiveVoicePhase.LISTENING))
        assertTrue(controller.isHeldForTest())
        assertEquals(2, lease.acquireCount)

        val unsupported = LiveVoiceProximityScreenController.forTest(null)
        unsupported.onSnapshot(LiveVoiceSnapshot(phase = LiveVoicePhase.LISTENING))
        assertFalse(unsupported.isHeldForTest())
        unsupported.close()
    }

    @Test
    fun exceptionalReleaseIsRetriedDuringDestroyCleanup() {
        val lease = FakeProximityScreenLease(failFirstRelease = true)
        val controller = LiveVoiceProximityScreenController.forTest(lease)
        controller.onAudioRoute(earpiece())
        controller.onSnapshot(LiveVoiceSnapshot(phase = LiveVoicePhase.LISTENING))

        controller.release()
        assertTrue(controller.isHeldForTest())
        controller.close()

        assertFalse(controller.isHeldForTest())
        assertEquals(2, lease.releaseCount)
    }

    @Test
    fun speakerDefaultAndUnconfirmedEarpiecePreferenceNeverBlankTheDisplay() {
        val lease = FakeProximityScreenLease()
        val controller = LiveVoiceProximityScreenController.forTest(lease)
        controller.onSnapshot(LiveVoiceSnapshot(phase = LiveVoicePhase.CONNECTING))
        controller.onAudioRoute(SpeechAudioRouteState(active = true,
            effective = SpeechAudioRoute.SPEAKER, requested = SpeechAudioRoute.EARPIECE))
        controller.onSnapshot(LiveVoiceSnapshot(phase = LiveVoicePhase.LISTENING))
        assertFalse(controller.isHeldForTest())
        assertEquals(0, lease.acquireCount)
        controller.onAudioRoute(earpiece())
        assertTrue(controller.isHeldForTest())
        controller.onAudioRoute(SpeechAudioRouteState(active = true, effective = SpeechAudioRoute.SPEAKER))
        assertFalse(controller.isHeldForTest())
        assertEquals(1, lease.releaseCount)
    }

    @Test
    fun externalUnknownInactiveAndPostFailureRoutesDoNotRetainProximityLease() {
        for (route in listOf(SpeechAudioRoute.EXTERNAL, SpeechAudioRoute.UNKNOWN)) {
            val controller = LiveVoiceProximityScreenController.forTest(FakeProximityScreenLease())
            controller.onSnapshot(LiveVoiceSnapshot(phase = LiveVoicePhase.LISTENING))
            controller.onAudioRoute(earpiece())
            controller.onAudioRoute(SpeechAudioRouteState(active = true, effective = route))
            assertFalse(controller.isHeldForTest())
            controller.onAudioRoute(earpiece())
            assertTrue(controller.isHeldForTest())
            controller.onAudioRoute(earpiece().copy(active = false))
            assertFalse(controller.isHeldForTest())
            controller.release()
            controller.onAudioRoute(earpiece())
            assertFalse(controller.isHeldForTest())
        }
    }

    private fun earpiece() = SpeechAudioRouteState(active = true, effective = SpeechAudioRoute.EARPIECE)

    private class FakeProximityScreenLease(
        private val failFirstAcquire: Boolean = false,
        private val failFirstRelease: Boolean = false,
    ) : ProximityScreenLease {
        var acquireCount = 0
        var releaseCount = 0

        override fun acquire(): Boolean {
            acquireCount += 1
            return !(failFirstAcquire && acquireCount == 1)
        }

        override fun release() {
            releaseCount += 1
            if (failFirstRelease && releaseCount == 1) error("simulated_release_failure")
        }
    }
}
