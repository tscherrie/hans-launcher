package ai.hans.standard.voice.realtime

import ai.hans.standard.voice.audio.AndroidSpeechAudioRouteController
import ai.hans.standard.voice.audio.SpeechAudioRoute
import ai.hans.standard.voice.audio.SpeechAudioRouteController
import ai.hans.standard.voice.audio.SpeechAudioRouteRequestResult
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real public AudioTrack routing with entirely zero PCM. No microphone, service, credentials or network. */
@RunWith(AndroidJUnit4::class)
class AndroidLiveConnectionToneInstrumentedTest {
    @Test
    fun zeroAmplitudeRingbackUsesVerifiedCommunicationRouteAndReleasesItsTrack() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = checkNotNull(context.getSystemService(AudioManager::class.java))
        val previousMode = manager.mode
        val previousDevice = manager.communicationDevice
        assertEquals("Run only on an owned idle test device, never inside another call",
            AudioManager.MODE_NORMAL, previousMode)
        assertTrue("The owner API34 fixture must provide a built-in communication speaker",
            manager.availableCommunicationDevices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER })
        val routes = AndroidSpeechAudioRouteController(context)
        val routeLost = CountDownLatch(1)
        val observations = CopyOnWriteArrayList<RouteObservation>()
        val awaiting = AtomicReference<RouteAwaiter?>()
        var registration: AutoCloseable? = null
        var tone: AndroidLiveConnectionTone? = null
        try {
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            registration = routes.attachRequiredLiveCommunication { routeLost.countDown() }
            assertEquals(SpeechAudioRoute.SPEAKER, routes.snapshot().effective)
            assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, manager.communicationDevice?.type)
            val active = AndroidLiveConnectionTone(
                audioManager = manager,
                sampleAmplitude = 0.0,
                onRouteVerified = { track ->
                    val evidence = RouteObservation(
                        track = track,
                        preferredId = track.preferredDevice?.id,
                        preferredType = track.preferredDevice?.type,
                        routedId = track.routedDevice?.id,
                        routedType = track.routedDevice?.type,
                        selectedId = manager.communicationDevice?.id,
                        selectedType = manager.communicationDevice?.type,
                        playState = track.playState,
                    )
                    observations += evidence
                    awaiting.get()?.accept(evidence)
                },
            ).also { tone = it }
            val speaker = awaitToneRoute(awaiting, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { active.start() }
            assertVerified(speaker, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            val originalTrack = speaker.track
            assertEquals(AudioTrack.STATE_INITIALIZED, originalTrack.state)
            assertEquals(1, observations.size)

            repeat(3) { active.start() }
            instrumentation.waitForIdleSync()
            assertEquals("Repeated start must neither allocate another player nor report another route", 1,
                observations.size)
            assertSame(originalTrack, observations.single().track)
            assertEquals(AudioTrack.STATE_INITIALIZED, originalTrack.state)
            assertEquals(AudioTrack.PLAYSTATE_PLAYING, originalTrack.playState)
            assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, originalTrack.preferredDevice?.type)
            assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, originalTrack.routedDevice?.type)

            val hasEarpiece = SpeechAudioRoute.EARPIECE in routes.snapshot().available
            if (hasEarpiece) {
                val earpiece = awaitToneRoute(awaiting, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                    awaitEffectiveRoute(routes, SpeechAudioRoute.EARPIECE)
                }
                assertVerified(earpiece, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
                assertSame("One existing player follows an explicit supported route change", originalTrack,
                    earpiece.track)
                val returned = awaitToneRoute(awaiting, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    awaitEffectiveRoute(routes, SpeechAudioRoute.SPEAKER)
                }
                assertVerified(returned, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                assertSame(originalTrack, returned.track)
                assertEquals("Initial speaker, optional earpiece, and explicit return are each confirmed once", 3,
                    observations.size)
            }
            // Absence of an earpiece only omits that optional branch, never this speaker/AudioTrack regression.
            assertEquals("A supported route change must not report a lost Live route", 1L, routeLost.count)
            active.close()
            instrumentation.waitForIdleSync()
            assertEquals("Close must synchronously stop and release the actual track", AudioTrack.STATE_UNINITIALIZED,
                originalTrack.state)
            assertEquals("The optional tone must not release the call's communication routing",
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, manager.communicationDevice?.type)
            assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
            val callbacksAtClose = observations.size

            if (hasEarpiece) awaitEffectiveRoute(routes, SpeechAudioRoute.EARPIECE)
            instrumentation.waitForIdleSync()
            active.close()
            instrumentation.waitForIdleSync()
            assertEquals("Closed-player route listeners and queued callbacks must remain silent", callbacksAtClose,
                observations.size)
            assertEquals(AudioTrack.STATE_UNINITIALIZED, originalTrack.state)
        } finally {
            try {
                tone?.close()
            } finally {
                try {
                    registration?.close()
                } finally {
                    restoreManager(manager, previousMode, previousDevice)
                }
            }
        }
        assertFalse(routes.snapshot().active)
        assertEquals(previousMode, manager.mode)
        assertEquals(previousDevice?.id, manager.communicationDevice?.id)
    }

    private fun awaitToneRoute(
        awaiting: AtomicReference<RouteAwaiter?>,
        deviceType: Int,
        action: () -> Unit,
    ): RouteObservation {
        val requested = RouteAwaiter(deviceType)
        awaiting.set(requested)
        try {
            action()
            assertTrue("The playing AudioTrack did not confirm its actual selected route",
                requested.confirmed.await(5, TimeUnit.SECONDS))
            return checkNotNull(requested.observed.get())
        } finally {
            awaiting.compareAndSet(requested, null)
        }
    }

    private fun awaitEffectiveRoute(routes: SpeechAudioRouteController, desired: SpeechAudioRoute) {
        val confirmed = CountDownLatch(1)
        val subscription = routes.observe { state ->
            if (state.active && state.effective == desired) confirmed.countDown()
        }
        try {
            assertEquals(SpeechAudioRouteRequestResult.ACCEPTED, routes.request(desired))
            assertTrue("Android did not confirm the requested communication route",
                confirmed.await(5, TimeUnit.SECONDS))
            assertEquals(desired, routes.snapshot().effective)
        } finally {
            subscription.close()
        }
    }

    private fun assertVerified(observed: RouteObservation, type: Int) {
        assertEquals(type, observed.preferredType)
        assertEquals(type, observed.routedType)
        assertEquals(type, observed.selectedType)
        assertEquals(observed.selectedId, observed.preferredId)
        assertEquals(observed.selectedId, observed.routedId)
        assertEquals(AudioTrack.PLAYSTATE_PLAYING, observed.playState)
    }

    private fun restoreManager(manager: AudioManager, mode: Int, device: AudioDeviceInfo?) {
        manager.mode = mode
        val restored = CountDownLatch(1)
        val listener = AudioManager.OnCommunicationDeviceChangedListener { observed ->
            if (observed?.id == device?.id) restored.countDown()
        }
        manager.addOnCommunicationDeviceChangedListener(Executor { it.run() }, listener)
        try {
            if (device == null) {
                manager.clearCommunicationDevice()
            } else {
                assertTrue("The prior public communication route could not be restored",
                    manager.setCommunicationDevice(device))
            }
            if (manager.communicationDevice?.id == device?.id) restored.countDown()
            assertTrue("The prior effective communication device did not return",
                restored.await(5, TimeUnit.SECONDS))
            assertEquals(mode, manager.mode)
            assertEquals(device?.id, manager.communicationDevice?.id)
        } finally {
            manager.removeOnCommunicationDeviceChangedListener(listener)
        }
    }

    private data class RouteObservation(
        val track: AudioTrack,
        val preferredId: Int?,
        val preferredType: Int?,
        val routedId: Int?,
        val routedType: Int?,
        val selectedId: Int?,
        val selectedType: Int?,
        val playState: Int,
    )

    private class RouteAwaiter(private val deviceType: Int) {
        val confirmed = CountDownLatch(1)
        val observed = AtomicReference<RouteObservation?>()
        fun accept(value: RouteObservation) {
            if (value.preferredType == deviceType && value.routedType == deviceType &&
                value.selectedType == deviceType && value.preferredId == value.routedId &&
                value.routedId == value.selectedId
            ) {
                observed.set(value)
                confirmed.countDown()
            }
        }
    }
}
