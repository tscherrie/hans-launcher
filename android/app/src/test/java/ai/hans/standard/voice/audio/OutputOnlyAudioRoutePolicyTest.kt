package ai.hans.standard.voice.audio

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputOnlyAudioRoutePolicyTest {
    private val all = setOf(SpeechAudioRoute.SPEAKER, SpeechAudioRoute.EARPIECE, SpeechAudioRoute.EXTERNAL)

    @Test fun existingExternalAudioWinsOverImplicitOrSpeakerOutput() {
        listOf(null, SpeechAudioRoute.SPEAKER, SpeechAudioRoute.EXTERNAL).forEach { request ->
            assertEquals(SpeechAudioRoute.EXTERNAL, OutputOnlyAudioRoutePolicy.select(
                request, externalPresent = true, available = all))
        }
        assertNull(OutputOnlyAudioRoutePolicy.select(null,
            externalPresent = true, available = setOf(SpeechAudioRoute.SPEAKER)))
    }

    @Test fun explicitPrivatePreferenceNeverFallsBackToSpeaker() {
        assertEquals(SpeechAudioRoute.EARPIECE, OutputOnlyAudioRoutePolicy.select(
            SpeechAudioRoute.EARPIECE, false, all))
        assertNull(OutputOnlyAudioRoutePolicy.select(SpeechAudioRoute.EARPIECE,
            false, setOf(SpeechAudioRoute.SPEAKER)))
        assertNull(OutputOnlyAudioRoutePolicy.select(SpeechAudioRoute.EARPIECE,
            true, all))
        assertNull(OutputOnlyAudioRoutePolicy.select(SpeechAudioRoute.EXTERNAL, false,
            setOf(SpeechAudioRoute.SPEAKER)))
        assertNull(OutputOnlyAudioRoutePolicy.select(SpeechAudioRoute.UNKNOWN, false, all))
    }

    @Test fun coldReadAloudUsesSpeakerEvenWhenIdleCommunicationDefaultWouldBeEarpiece() {
        val cold = SpeechAudioRouteState()
        assertNull(OutputOnlyAudioRoutePolicy.capturePreference(cold, false))
        assertEquals(SpeechAudioRoute.SPEAKER, OutputOnlyAudioRoutePolicy.select(
            OutputOnlyAudioRoutePolicy.capturePreference(cold, false), false, all))
        // Inactive stale state is not an explicit personal routing preference either.
        assertNull(OutputOnlyAudioRoutePolicy.capturePreference(cold.copy(effective = SpeechAudioRoute.EARPIECE), false))
        assertNull(OutputOnlyAudioRoutePolicy.select(null, false, setOf(SpeechAudioRoute.EARPIECE)))
    }

    @Test fun captureBeforeLegacyStopPreservesPrivateAndExternalPreference() {
        listOf(SpeechAudioRoute.EARPIECE, SpeechAudioRoute.EXTERNAL).forEach { privateRoute ->
            val active = SpeechAudioRouteState(active = true, effective = privateRoute)
            val captured = OutputOnlyAudioRoutePolicy.capturePreference(active, privateRoute == SpeechAudioRoute.EXTERNAL)
            assertEquals(privateRoute, captured)
            // Legacy removal clears the current controller state, but not this captured value.
            assertNull(OutputOnlyAudioRoutePolicy.capturePreference(SpeechAudioRouteState(), false))
            assertNull(OutputOnlyAudioRoutePolicy.select(captured, false, setOf(SpeechAudioRoute.SPEAKER)))
        }
        assertEquals(SpeechAudioRoute.EXTERNAL, OutputOnlyAudioRoutePolicy.capturePreference(SpeechAudioRouteState(), true))
        assertEquals(SpeechAudioRoute.EARPIECE, OutputOnlyAudioRoutePolicy.capturePreference(
            SpeechAudioRouteState(requested = SpeechAudioRoute.EARPIECE), true))
    }

    @Test fun exactDeviceIdentityAndCommunicationModeAreRequiredThroughoutPlayback() {
        val headset = OutputOnlyAudioRouteTarget(12, 7, SpeechAudioRoute.EXTERNAL)
        assertTrue(OutputOnlyAudioRoutePolicy.matches(headset, headset, true, true))
        assertFalse(OutputOnlyAudioRoutePolicy.matches(headset, headset.copy(deviceId = 13), true, true))
        assertFalse(OutputOnlyAudioRoutePolicy.matches(headset, headset.copy(deviceType = 8), true, true))
        assertFalse(OutputOnlyAudioRoutePolicy.matches(headset, null, true, true))
        assertFalse(OutputOnlyAudioRoutePolicy.matches(headset, headset, false, true))
        val speaker = OutputOnlyAudioRouteTarget(2, 2, SpeechAudioRoute.SPEAKER)
        assertTrue(OutputOnlyAudioRoutePolicy.matches(speaker, speaker, true, false))
        assertFalse(OutputOnlyAudioRoutePolicy.matches(speaker, speaker, true, true))
    }

    @Test fun outputOnlyAttachmentRegistersEventDrivenGuardsAndBoundedExplicitSwitchAck() {
        val source = sequenceOf(
            File("src/main/java/ai/hans/standard/voice/audio/AndroidSpeechAudioRouteController.kt"),
            File("android/app/src/main/java/ai/hans/standard/voice/audio/AndroidSpeechAudioRouteController.kt"),
        ).first(File::isFile).readText()
        val output = source.substringAfter("internal fun attachRequiredOutputOnlyCommunication(")
            .substringBefore("fun attachRequiredLiveCommunication(")
        assertFalse(output.contains("request(SpeechAudioRoute.SPEAKER)"))
        assertTrue(output.contains("it.id == target.deviceId && it.type == target.deviceType"))
        assertTrue(output.contains("audio.addOnCommunicationDeviceChangedListener"))
        assertTrue(output.contains("audio.addOnModeChangedListener"))
        assertTrue(output.contains("audio.registerAudioDeviceCallback"))
        assertTrue(output.contains("outputOnlyPreferenceOwner === owner"))
        assertTrue(output.contains("registration.close()"))
        assertTrue(output.contains("if (!failed && route.pending)"))
        assertTrue(output.contains("handler.postDelayed(it, ROUTE_ACK_TIMEOUT_MS)"))
        assertTrue(output.contains("routeTimeout?.let(handler::removeCallbacks)"))
        assertTrue(output.contains("beforeRouteChange()"))
        assertTrue(output.contains("override val retainsPlaybackPreference: Boolean get() = true"))
    }

    @Test fun switchOnlyConfirmsExactDeviceAndRejectsOverlappingRequests() {
        val speaker = OutputOnlyAudioRouteTarget(2, 2, SpeechAudioRoute.SPEAKER)
        val earpiece = OutputOnlyAudioRouteTarget(1, 1, SpeechAudioRoute.EARPIECE)
        val route = OutputOnlyAudioRouteSwitch(speaker)
        assertFalse(route.request(earpiece))
        assertEquals(OutputOnlyAudioRouteSwitch.Result.CONFIRMED, route.observe(speaker, true, false))
        assertTrue(route.request(earpiece))
        assertTrue(route.pending)
        assertFalse(route.request(speaker))
        assertEquals(OutputOnlyAudioRouteSwitch.Result.PENDING, route.observe(speaker, true, false))
        assertEquals(OutputOnlyAudioRouteSwitch.Result.CONFIRMED, route.observe(earpiece, true, false))
        assertFalse(route.pending)
        assertEquals(OutputOnlyAudioRouteSwitch.Result.LOST, route.observe(speaker, true, false))
        assertFalse(route.request(speaker))
        assertEquals(OutputOnlyAudioRouteSwitch.Result.LOST, route.observe(earpiece, true, false))
    }

    @Test fun headphoneAttachmentAndWrongDeviceDuringSwitchFailClosed() {
        val speaker = OutputOnlyAudioRouteTarget(2, 2, SpeechAudioRoute.SPEAKER)
        val earpiece = OutputOnlyAudioRouteTarget(1, 1, SpeechAudioRoute.EARPIECE)
        val route = OutputOnlyAudioRouteSwitch(speaker)
        route.observe(speaker, true, false)
        route.request(earpiece)
        assertEquals(OutputOnlyAudioRouteSwitch.Result.LOST, route.observe(earpiece.copy(deviceId = 10), true, false))
        val external = OutputOnlyAudioRouteSwitch(speaker)
        external.observe(speaker, true, false)
        assertEquals(OutputOnlyAudioRouteSwitch.Result.LOST, external.observe(speaker, true, true))
    }
}
