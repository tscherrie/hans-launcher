package ai.hans.standard.voice.realtime

import ai.hans.standard.voice.audio.SpeechAudioRoute
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import livekit.org.webrtc.AudioTrack
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs without recording, opening a Live session, or changing any system volume/route. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31, maxSdkVersion = 36)
class LiveVoiceSpeakerGainAndroidTest {
    @Test fun pinnedPublicGainApiAndWorkerLeaseAreAvailableOnSupportedAndroid() {
        assertEquals(Void.TYPE, AudioManager::class.java.getMethod("addOnModeChangedListener",
            java.util.concurrent.Executor::class.java, AudioManager.OnModeChangedListener::class.java).returnType)
        assertEquals(Void.TYPE, AudioManager::class.java.getMethod("removeOnModeChangedListener",
            AudioManager.OnModeChangedListener::class.java).returnType)
        assertEquals(Void.TYPE, AudioTrack::class.java.getMethod("setVolume", Double::class.javaPrimitiveType).returnType)
        assertEquals(Double::class.javaPrimitiveType, AudioTrack::class.java.getMethod("getVolume").returnType)
        var currentGain = 1.0
        var disabled = false
        LiveVoiceSpeakerGain({ true }).use { gain ->
            assertTrue(gain.routeConfirmed(SpeechAudioRoute.SPEAKER))
            assertTrue(gain.attach("isolated-test", object : LiveVoiceSpeakerGainTrack {
                override fun setGain(value: Double) { currentGain = value }
                override fun readGain(): Double = currentGain
                override fun disable() { disabled = true }
            }))
            assertEquals(2.0, currentGain, 0.0)
            assertTrue(gain.audioFocusChanged(false))
            gain.routeConfirmed(SpeechAudioRoute.SPEAKER)
            assertEquals(1.0, currentGain, 0.0)
            assertTrue(gain.audioFocusChanged(true))
            assertEquals(2.0, currentGain, 0.0)
            assertTrue(gain.beforeRouteChange())
            assertEquals(1.0, currentGain, 0.0)
            gain.routeConfirmed(SpeechAudioRoute.EARPIECE)
            assertEquals(1.0, currentGain, 0.0)
            assertFalse(disabled)
        }
        assertEquals(1.0, currentGain, 0.0)
    }
}
