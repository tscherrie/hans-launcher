package ai.hans.standard.voice.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import ai.hans.standard.voice.realtime.LiveVoiceSpeakerGain
import ai.hans.standard.voice.realtime.LiveVoiceSpeakerGainTrack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Public routing APIs only, without microphone capture or network calls. Run on APIs 31–36. */
@RunWith(AndroidJUnit4::class)
class AndroidLiveVoiceCommunicationRouteTest {
    @Test fun speakerDefaultAndSupportedExplicitEarpieceSwitchAreActuallyConfirmedByAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = checkNotNull(context.getSystemService(AudioManager::class.java))
        assumeTrue("Another call owns communication audio.", manager.mode == AudioManager.MODE_NORMAL)
        assumeTrue("This device has no built-in communication speaker.",
            manager.availableCommunicationDevices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER })
        val routes = AndroidSpeechAudioRouteController(context)
        var registration: AutoCloseable? = null
        val routeLost = CountDownLatch(1)
        val previousMode = manager.mode
        var effectiveGain = 1.0
        val gain = LiveVoiceSpeakerGain({
            manager.mode == AudioManager.MODE_IN_COMMUNICATION &&
                manager.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        })
        gain.attach("route-test-no-audio", object : LiveVoiceSpeakerGainTrack {
            override fun setGain(value: Double) { effectiveGain = value }
            override fun readGain(): Double = effectiveGain
            override fun disable() = Unit
        })
        try {
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            registration = routes.attachRequiredLiveCommunication(
                beforeRouteChange = {
                    gain.beforeRouteChange().also {
                        assertEquals("Gain must be neutral before Android changes the route", 1.0, effectiveGain, 0.0)
                    }
                },
                onConfirmedRoute = gain::routeConfirmed,
            ) { routeLost.countDown() }
            assertEquals(SpeechAudioRoute.SPEAKER, routes.snapshot().effective)
            assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, manager.communicationDevice?.type)
            assertEquals(2.0, effectiveGain, 0.0)
            if (SpeechAudioRoute.EARPIECE in routes.snapshot().available) {
                awaitEffectiveRoute(routes, SpeechAudioRoute.EARPIECE)
                assertEquals(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, manager.communicationDevice?.type)
                assertEquals(1.0, effectiveGain, 0.0)
                awaitEffectiveRoute(routes, SpeechAudioRoute.SPEAKER)
                assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, manager.communicationDevice?.type)
                assertEquals(2.0, effectiveGain, 0.0)
            }
            assertEquals("An explicit supported route switch must not end the Live call.", 1L, routeLost.count)
        } finally {
            gain.close()
            registration?.close()
            manager.mode = previousMode
        }
        assertFalse(routes.snapshot().active)
    }

    private fun awaitEffectiveRoute(routes: SpeechAudioRouteController, desired: SpeechAudioRoute) {
        val confirmed = CountDownLatch(1)
        val subscription = routes.observe { state ->
            if (state.active && state.effective == desired) confirmed.countDown()
        }
        try {
            assertEquals(SpeechAudioRouteRequestResult.ACCEPTED, routes.request(desired))
            assertTrue("Android did not confirm the selected route.", confirmed.await(2, TimeUnit.SECONDS))
            assertEquals(desired, routes.snapshot().effective)
        } finally {
            subscription.close()
        }
    }
}
