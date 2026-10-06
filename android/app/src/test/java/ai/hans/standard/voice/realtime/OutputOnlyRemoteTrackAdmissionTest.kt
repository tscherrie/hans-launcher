package ai.hans.standard.voice.realtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputOnlyRemoteTrackAdmissionTest {
    private class Track(var enabled: Boolean = true)

    @Test fun allNewTracksAreMutedSynchronouslyBeforeQueuedAdmission() {
        val tracks = listOf(Track(), Track())
        var queued = 0
        OutputOnlyRemoteTrackAdmission.receive({ tracks }, { it.enabled = false; true },
            enqueue = { observed ->
                assertTrue(observed.all { !it.enabled })
                queued += observed.size
            }, onFailure = { error("unexpected failure") })
        assertEquals(2, queued)
        assertTrue(tracks.all { !it.enabled })
    }

    @Test fun terminalOrRejectedQueueCannotSkipSynchronousMute() {
        listOf(true, false).forEach { terminal ->
            val track = Track()
            var queued = false
            OutputOnlyRemoteTrackAdmission.receive({ listOf(track) }, { it.enabled = false; true },
                enqueue = { if (!terminal) queued = true }, onFailure = { error("unexpected failure") })
            assertFalse(track.enabled)
            assertEquals(!terminal, queued)
        }
    }

    @Test fun failedMuteStillMutesOtherTracksAndNeverAdmitsAny() {
        val tracks = listOf(Track(), Track(), Track())
        var attempted = 0
        var failed = 0
        OutputOnlyRemoteTrackAdmission.receive({ tracks }, {
            attempted++
            if (it === tracks[1]) false else { it.enabled = false; true }
        }, enqueue = { error("unsafe tracks admitted") }, onFailure = { failed++ })
        assertEquals(3, attempted)
        assertEquals(1, failed)
        assertFalse(tracks.first().enabled)
        assertFalse(tracks.last().enabled)
    }

    @Test fun nativeReadOrMuteExceptionFailsClosedWithoutQueueing() {
        var failed = 0
        OutputOnlyRemoteTrackAdmission.receive<Track>({ throw IllegalStateException() }, { true },
            enqueue = { error("unread tracks admitted") }, onFailure = { failed++ })
        OutputOnlyRemoteTrackAdmission.receive({ listOf(Track()) }, { throw IllegalStateException() },
            enqueue = { error("unmuted tracks admitted") }, onFailure = { failed++ })
        assertEquals(2, failed)
    }

    @Test fun allRtcTrackCallbacksUseAdmissionBeforeControlDispatch() {
        val source = sequenceOf(
            File("src/main/java/ai/hans/standard/voice/realtime/AndroidWebRtcRealtimeTransport.kt"),
            File("android/app/src/main/java/ai/hans/standard/voice/realtime/AndroidWebRtcRealtimeTransport.kt"),
        ).first(File::isFile).readText()
        listOf("onAddStream", "onAddTrack", "onTrack").forEach { callback ->
            val declaration = source.substringAfter("override fun $callback(").substringBefore("\n        }")
            assertTrue(declaration.contains("= admitRemoteAudio {"))
        }
        val admission = source.substringAfter("private fun admitRemoteAudio(").substringBefore("private fun offerObserver()")
        assertTrue(admission.contains("mediaMode != LiveVoiceMediaMode.OUTPUT_ONLY"))
        assertTrue(admission.contains("dispatchControl { tracks().forEach(::enableRemoteAudio) }"))
        assertTrue(admission.contains("LiveVoiceLocalTrackState.apply(false, track::setEnabled, track::enabled)"))
        assertTrue(admission.contains("peerConnection?.setAudioPlayout(false)"))
        assertFalse(admission.contains("terminal.get()"))
        assertFalse(admission.contains("outputOnlyPlayback"))
        assertFalse(admission.contains("control.call"))
    }
}
