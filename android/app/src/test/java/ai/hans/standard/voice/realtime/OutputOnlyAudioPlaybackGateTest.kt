package ai.hans.standard.voice.realtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputOnlyAudioPlaybackGateTest {
    private class Track {
        var enabled = true
        var rejectEnable = false
        val states = mutableListOf<Boolean>()
        fun set(value: Boolean): Boolean {
            states += value
            if (value && rejectEnable) return false
            enabled = value
            return true
        }
    }

    @Test fun sessionReadinessCannotOverridePendingRouteAndLateTracksAreMuted() {
        val gate = OutputOnlyAudioPlaybackGate()
        val first = Track()
        assertTrue(gate.attach("first", first::set) { first.enabled })
        gate.setReady(true)
        assertFalse(first.enabled)
        gate.setRouteConfirmed(true)
        assertTrue(first.enabled)
        assertTrue(gate.setRouteConfirmed(false))
        assertFalse(first.enabled)
        val second = Track()
        assertTrue(gate.attach("second", second::set) { second.enabled })
        assertFalse(second.enabled)
        gate.setReady(true)
        assertFalse(first.enabled)
        assertFalse(second.enabled)
        gate.setRouteConfirmed(true)
        assertTrue(first.enabled)
        assertTrue(second.enabled)
    }

    @Test fun routeConfirmationBeforeSessionReadyDoesNotPlayAudio() {
        val gate = OutputOnlyAudioPlaybackGate()
        gate.setRouteConfirmed(true)
        val track = Track()
        gate.attach("output", track::set) { track.enabled }
        assertFalse(track.enabled)
        gate.setReady(true)
        assertTrue(track.enabled)
        gate.setReady(false)
        assertFalse(track.enabled)
    }

    @Test fun failedTrackMutesEveryOtherTrackAndCannotBeReenabled() {
        val gate = OutputOnlyAudioPlaybackGate()
        val first = Track()
        val second = Track().apply { rejectEnable = true }
        gate.attach("first", first::set) { first.enabled }
        gate.attach("second", second::set) { second.enabled }
        gate.setReady(true)
        assertFalse(gate.setRouteConfirmed(true))
        assertFalse(first.enabled)
        assertFalse(second.enabled)
        gate.setReady(true)
        gate.setRouteConfirmed(true)
        assertFalse(first.enabled)
        assertFalse(second.enabled)
    }

    @Test fun terminalGateNeverReenablesOldOrLateTrack() {
        val gate = OutputOnlyAudioPlaybackGate()
        val first = Track()
        gate.attach("first", first::set) { first.enabled }
        gate.setReady(true)
        gate.setRouteConfirmed(true)
        assertTrue(gate.close())
        gate.setRouteConfirmed(true)
        gate.setReady(true)
        assertFalse(first.enabled)
        val late = Track()
        assertFalse(gate.attach("late", late::set) { late.enabled })
        assertFalse(late.enabled)
    }
}
