package ai.hans.standard.voice.realtime

import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutedLiveConnectionToneTest {
    @Test fun speakerIsExplicitlyBoundBeforePlaybackAndOnlyActualRouteUnmutes() {
        val fixture = Fixture()
        fixture.output.actual = EARPIECE
        fixture.tone.start()
        assertEquals(SPEAKER, fixture.output.preferred)
        assertTrue(fixture.output.mutedState)
        assertEquals(0, fixture.output.verifications)
        assertTrue(fixture.output.events.indexOf("bind") < fixture.output.events.indexOf("play"))
        fixture.output.actual = SPEAKER
        fixture.output.changed()
        assertFalse(fixture.output.mutedState)
        assertEquals(1, fixture.output.verifications)
        assertEquals(0, fixture.timers.activeCount)
    }

    @Test fun selectedEarpieceAndSpeakerSwitchesMuteRebindAndAwaitTheirActualOutput() {
        val fixture = Fixture()
        fixture.output.actual = SPEAKER
        fixture.tone.start()
        for (route in listOf(EARPIECE, SPEAKER)) {
            val before = fixture.output.verifications
            fixture.output.desired = route
            fixture.output.changed()
            assertTrue(fixture.output.mutedState)
            assertEquals(route, fixture.output.preferred)
            assertEquals(before, fixture.output.verifications)
            fixture.output.actual = route
            fixture.output.changed()
            assertFalse(fixture.output.mutedState)
            assertEquals(before + 1, fixture.output.verifications)
        }
        assertEquals(1, fixture.output.plays)
    }

    @Test fun equalDeviceTypeWithoutEqualIdentityIsNotRouteProof() {
        val fixture = Fixture()
        fixture.output.actual = SPEAKER.copy(deviceId = 99)
        fixture.tone.start()
        assertTrue(fixture.output.mutedState)
        assertEquals(0, fixture.output.verifications)
    }

    @Test fun unselectedOrUnsupportedCommunicationOutputSuppressesTheOptionalTone() {
        val fixture = Fixture()
        fixture.output.desired = null
        fixture.tone.start()
        assertEquals(0, fixture.output.plays)
        assertEquals(1, fixture.output.closes)
        assertTrue(fixture.output.mutedState)
    }

    @Test fun repeatedMismatchEventsUseOneDeadlineAndThenReleaseWithoutUnmuting() {
        val fixture = Fixture()
        fixture.tone.start()
        repeat(10) { fixture.output.changed() }
        assertEquals(1, fixture.timers.tasks.size)
        fixture.timers.tasks.single().fire()
        assertEquals(1, fixture.output.closes)
        assertEquals(0, fixture.output.verifications)
        assertTrue(fixture.output.mutedState)
        assertEquals(0, fixture.timers.activeCount)
    }

    @Test fun effectivePlaybackAtDeadlineCanConfirmADelayedAndroidCallback() {
        val fixture = Fixture()
        fixture.tone.start()
        fixture.output.actual = SPEAKER
        fixture.timers.tasks.single().fire()
        assertFalse(fixture.output.mutedState)
        assertEquals(1, fixture.output.verifications)
        assertEquals(0, fixture.output.closes)
    }

    @Test fun actualRouteLossImmediatelyMutesAndCanRecoverOnItsCallback() {
        val fixture = Fixture()
        fixture.output.actual = SPEAKER
        fixture.tone.start()
        fixture.output.actual = EARPIECE
        fixture.output.changed()
        assertTrue(fixture.output.mutedState)
        val staleDeadline = fixture.timers.tasks.single()
        fixture.output.actual = SPEAKER
        fixture.output.changed()
        staleDeadline.fire()
        assertFalse(fixture.output.mutedState)
        assertEquals(2, fixture.output.verifications)
        assertEquals(0, fixture.output.closes)
    }

    @Test fun oldDeadlineThatFirstObservesANewSelectionGivesThatRouteItsOwnBoundedWindow() {
        val fixture = Fixture()
        fixture.tone.start()
        val oldDeadline = fixture.timers.tasks.single()
        fixture.output.desired = EARPIECE
        oldDeadline.fire()
        assertEquals(EARPIECE, fixture.output.preferred)
        assertEquals(0, fixture.output.closes)
        assertTrue(fixture.output.mutedState)
        assertEquals(2, fixture.timers.tasks.size)
        assertEquals(1, fixture.timers.activeCount)
        oldDeadline.fire()
        assertEquals(2, fixture.timers.tasks.size)
        fixture.timers.tasks.last().fire()
        assertEquals(1, fixture.output.closes)
    }

    @Test fun closedBeforeFirstStartNeverAllocatesPlayback() {
        val fixture = Fixture()
        fixture.tone.close()
        fixture.tone.start()
        assertEquals(0, fixture.creations)
        assertEquals(0, fixture.output.plays)
        assertEquals(0, fixture.timers.tasks.size)
    }

    @Test fun repeatedStartAndCloseAreIdempotentAndLateCallbacksCannotResurrectPlayback() {
        val fixture = Fixture()
        fixture.tone.start()
        fixture.tone.start()
        assertEquals(1, fixture.creations)
        assertEquals(1, fixture.output.plays)
        val lateCallback = fixture.output.callback!!
        val lateDeadline = fixture.timers.tasks.single()
        fixture.tone.close()
        fixture.tone.close()
        fixture.tone.start()
        fixture.output.actual = SPEAKER
        lateCallback()
        lateDeadline.fire()
        assertEquals(1, fixture.output.closes)
        assertEquals(1, fixture.output.unregistrations)
        assertEquals(0, fixture.output.verifications)
        assertTrue(fixture.output.mutedState)
        assertEquals(0, fixture.timers.activeCount)
    }

    @Test fun playbackFailuresAreContainedAndAlwaysAttemptTerminalCleanup() {
        for (failure in listOf("mute", "observe", "selected", "bind", "play", "actual", "unmute", "verified")) {
            val fixture = Fixture()
            fixture.output.actual = SPEAKER
            fixture.output.failOn = failure
            fixture.tone.start()
            fixture.tone.close()
            assertEquals("Cleanup for $failure", 1, fixture.output.closes)
            assertTrue("Muted after $failure", fixture.output.mutedState)
        }
    }

    @Test fun failedBindingNeverStartsPlayback() {
        val fixture = Fixture()
        fixture.output.acceptBinding = false
        fixture.tone.start()
        assertEquals(0, fixture.output.plays)
        assertEquals(1, fixture.output.closes)
    }

    @Test fun failedCreationOrTimeoutSchedulingCannotEscapeTheOptionalFeedbackOwner() {
        RoutedLiveConnectionTone({ error("no audio") }, { error("unused") }).start()
        val output = Playback()
        RoutedLiveConnectionTone({ output }, { error("no handler") }).start()
        assertEquals(1, output.closes)
        assertTrue(output.mutedState)
    }

    @Test fun failingToneCleanupStillAllowsTheExistingCaptureGateToConnect() {
        val fixture = Fixture()
        fixture.output.failOn = "close"
        val recording = mutableListOf<Boolean>()
        val gate = LiveConnectionAudioGate(fixture.tone) { recording += it }
        gate.connecting()
        gate.connected()
        gate.close()
        assertEquals(listOf(false, true, false), recording)
        assertEquals(1, fixture.output.closes)
    }

    @Test fun ringbackPcmHasBoundedToneAndSilentCadenceAndSupportsCompletelySilentTests() {
        val samples = LiveConnectionRingbackPcm.create(0.35)
        assertEquals(LiveConnectionRingbackPcm.LOOP_FRAMES, samples.size)
        assertTrue(samples.take(LiveConnectionRingbackPcm.TONE_FRAMES).any { it.toInt() != 0 })
        assertTrue(samples.drop(LiveConnectionRingbackPcm.TONE_FRAMES).all { it.toInt() == 0 })
        assertEquals(0, samples.first().toInt())
        assertEquals(0, samples[LiveConnectionRingbackPcm.TONE_FRAMES - 1].toInt())
        assertTrue(samples.all { abs(it.toInt()) <= Short.MAX_VALUE * 0.35 })
        assertTrue(LiveConnectionRingbackPcm.create(0.0).all { it.toInt() == 0 })
    }

    @Test fun androidAdapterUsesActualTrackRoutingAndDoesNotOwnModeFocusOrCommunicationPreference() {
        val source = sequenceOf(
            File("src/main/java/ai/hans/standard/voice/realtime/AndroidLiveConnectionTone.kt"),
            File("android/app/src/main/java/ai/hans/standard/voice/realtime/AndroidLiveConnectionTone.kt"),
        ).first(File::isFile).readText()
        assertTrue(source.contains("USAGE_VOICE_COMMUNICATION"))
        assertTrue(source.contains("CONTENT_TYPE_SONIFICATION"))
        assertTrue(source.contains("track.setPreferredDevice(selected)"))
        assertTrue(source.contains("track.routedDevice"))
        assertTrue(source.contains("removeOnCommunicationDeviceChangedListener"))
        assertTrue(source.contains("removeOnRoutingChangedListener"))
        assertFalse(source.contains("ToneGenerator"))
        assertFalse(source.contains("setCommunicationDevice("))
        assertFalse(source.contains("requestAudioFocus("))
        assertFalse(source.contains("manager.mode ="))
    }

    private class Fixture {
        val output = Playback()
        val timers = Timers()
        var creations = 0
        val tone = RoutedLiveConnectionTone({ creations++; output }, timers::schedule)
    }

    private class Playback : LiveConnectionTonePlayback {
        var desired: LiveConnectionToneRoute? = SPEAKER
        var actual: LiveConnectionToneRoute? = null
        var preferred: LiveConnectionToneRoute? = null
        var mutedState = true
        var acceptBinding = true
        var failOn: String? = null
        var callback: (() -> Unit)? = null
        var plays = 0
        var closes = 0
        var unregistrations = 0
        var verifications = 0
        val events = mutableListOf<String>()
        private fun event(name: String) {
            events += name
            check(failOn != name) { "failed $name" }
        }
        override fun selectedRoute(): LiveConnectionToneRoute? { event("selected"); return desired }
        override fun routedRoute(): LiveConnectionToneRoute? { event("actual"); return actual }
        override fun bindRoute(route: LiveConnectionToneRoute): Boolean {
            event("bind"); preferred = route; return acceptBinding
        }
        override fun setMuted(muted: Boolean) { event(if (muted) "mute" else "unmute"); mutedState = muted }
        override fun observeRoutes(onChanged: () -> Unit) { callback = onChanged; event("observe") }
        override fun play() { event("play"); plays++ }
        override fun onRouteVerified() { event("verified"); verifications++ }
        fun changed() { callback?.invoke() }
        override fun close() {
            closes++
            if (callback != null) unregistrations++
            callback = null
            event("close")
        }
    }

    private class Timers {
        val tasks = mutableListOf<Task>()
        val activeCount get() = tasks.count { !it.cancelled }
        fun schedule(callback: () -> Unit): AutoCloseable = Task(callback).also { tasks += it }
        class Task(private val callback: () -> Unit) : AutoCloseable {
            var cancelled = false
            override fun close() { cancelled = true }
            fun fire() = callback() // Also exercise callbacks queued before cancellation.
        }
    }

    private companion object {
        val SPEAKER = LiveConnectionToneRoute(1, 2)
        val EARPIECE = LiveConnectionToneRoute(2, 1)
    }
}
