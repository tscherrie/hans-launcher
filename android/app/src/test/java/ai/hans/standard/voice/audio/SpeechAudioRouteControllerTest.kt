package ai.hans.standard.voice.audio

import org.junit.Assert.*
import org.junit.Test

class SpeechAudioRouteControllerTest {
    private class Endpoint : SpeechAudioRouteEndpoint {
        override val retainsPlaybackPreference = true
        var devices = setOf(SpeechAudioRoute.SPEAKER, SpeechAudioRoute.EARPIECE)
        var routed = SpeechAudioRoute.SPEAKER
        var external = false
        var call = false
        var accepted = true
        var requests = mutableListOf<SpeechAudioRoute>()
        var closes = 0
        var callback: (() -> Unit)? = null
        override fun available() = devices
        override fun effective() = routed
        override fun externalDevicePresent() = external
        override fun callActive() = call
        override fun request(route: SpeechAudioRoute): Boolean { requests += route; return accepted }
        override fun observeChanged(callback: () -> Unit): AutoCloseable {
            this.callback = callback
            return AutoCloseable { this.callback = null }
        }
        override fun close() { closes++ }
    }

    @Test fun preferenceAcceptanceDoesNotInventEffectiveRoute() {
        val controller = SpeechAudioRouteController()
        val endpoint = Endpoint()
        controller.attach(endpoint)
        assertEquals(SpeechAudioRouteRequestResult.ACCEPTED, controller.request(SpeechAudioRoute.EARPIECE))
        assertEquals(SpeechAudioRoute.EARPIECE, controller.snapshot().requested)
        assertEquals(SpeechAudioRoute.SPEAKER, controller.snapshot().effective)
        endpoint.routed = SpeechAudioRoute.EARPIECE
        endpoint.callback!!()
        assertEquals(SpeechAudioRoute.EARPIECE, controller.snapshot().effective)
    }

    @Test fun unavailableEarpieceNeverFallsBackToSpeakerRequest() {
        val controller = SpeechAudioRouteController()
        val endpoint = Endpoint().apply { devices = setOf(SpeechAudioRoute.SPEAKER) }
        controller.attach(endpoint)
        assertEquals(SpeechAudioRouteRequestResult.UNAVAILABLE, controller.request(SpeechAudioRoute.EARPIECE))
        assertTrue(endpoint.requests.isEmpty())
    }

    @Test fun failedRequestKeepsActualRouteAndDoesNotRetry() {
        val controller = SpeechAudioRouteController()
        val endpoint = Endpoint().apply { accepted = false }
        controller.attach(endpoint)
        assertEquals(SpeechAudioRouteRequestResult.FAILED, controller.request(SpeechAudioRoute.EARPIECE))
        endpoint.callback!!()
        assertEquals(listOf(SpeechAudioRoute.EARPIECE), endpoint.requests)
        assertNull(controller.snapshot().requested)
        assertEquals(SpeechAudioRoute.SPEAKER, controller.snapshot().effective)
    }

    @Test fun headsetIsNeverAutomaticallyDisplacedButExplicitChoiceIsAllowed() {
        val controller = SpeechAudioRouteController()
        val endpoint = Endpoint().apply { external = true; routed = SpeechAudioRoute.EXTERNAL }
        controller.attach(endpoint)
        endpoint.callback!!()
        assertTrue(endpoint.requests.isEmpty())
        assertEquals(SpeechAudioRoute.EXTERNAL, controller.snapshot().effective)
        assertEquals(SpeechAudioRouteRequestResult.ACCEPTED, controller.request(SpeechAudioRoute.SPEAKER))
        assertEquals(listOf(SpeechAudioRoute.SPEAKER), endpoint.requests)
    }

    @Test fun callOwnershipDriftBlocksChanges() {
        val controller = SpeechAudioRouteController()
        val endpoint = Endpoint().apply { call = true }
        controller.attach(endpoint)
        assertTrue(controller.snapshot().available.isEmpty())
        assertEquals(SpeechAudioRouteRequestResult.CALL_ACTIVE, controller.request(SpeechAudioRoute.EARPIECE))
        assertTrue(endpoint.requests.isEmpty())
    }

    @Test fun releaseIsExactAndIdempotentLateCallbackCannotResurrectOutput() {
        val controller = SpeechAudioRouteController()
        val endpoint = Endpoint()
        val registration = controller.attach(endpoint)
        val oldCallback = endpoint.callback!!
        registration.close()
        registration.close()
        oldCallback()
        assertFalse(controller.snapshot().active)
        assertEquals(1, endpoint.closes)
        assertEquals(SpeechAudioRouteRequestResult.UNAVAILABLE, controller.request(SpeechAudioRoute.EARPIECE))
    }

    @Test fun captureIsNotOwnedOrRestartedAndNewPlaybackDoesNotReplayPreference() {
        val controller = SpeechAudioRouteController()
        val endpoint = Endpoint()
        val first = controller.attach(endpoint)
        controller.request(SpeechAudioRoute.EARPIECE)
        first.close()
        val next = Endpoint()
        controller.attach(next)
        assertTrue(next.requests.isEmpty())
        assertNull(controller.snapshot().requested)
        // Endpoint contract contains playback routing only, no capture/mute/start/stop operation.
        // Kotlin's JVM-default compatibility accessor is synthetic, not an additional source API.
        assertEquals(setOf("available", "effective", "externalDevicePresent", "callActive", "request", "observeChanged", "getRetainsPlaybackPreference"),
            SpeechAudioRouteEndpoint::class.java.declaredMethods.filterNot { it.isSynthetic }.map { it.name }.toSet())
    }

    @Test fun queuedObserverReadsLatestStateAndUnsubscribeCancelsInitialDelivery() {
        val queue = mutableListOf<() -> Unit>()
        val controller = SpeechAudioRouteController { queue += it }
        val values = mutableListOf<SpeechAudioRouteState>()
        val listener = controller.observe { values += it }
        val registration = controller.attach(Endpoint())
        registration.close()
        queue.toList().forEach { it() }
        assertTrue(values.all { !it.active })
        values.clear()
        listener.close()
        queue.toList().forEach { it() }
        assertTrue(values.isEmpty())
    }

    @Test fun simultaneousPlaybackNeverPretendsOneRouteControlsBoth() {
        val controller = SpeechAudioRouteController()
        val a = Endpoint()
        val b = Endpoint()
        controller.attach(a)
        controller.attach(b)
        assertTrue(controller.snapshot().active)
        assertTrue(controller.snapshot().available.isEmpty())
        assertEquals(SpeechAudioRouteRequestResult.UNAVAILABLE, controller.request(SpeechAudioRoute.EARPIECE))
        assertTrue(a.requests.isEmpty() && b.requests.isEmpty())
    }

    @Test fun privateChoiceSurvivesSentenceBoundaryButStopClearsIt() {
        val controller = SpeechAudioRouteController()
        val playback = controller.beginPlayback()
        val first = controller.attach(Endpoint())
        controller.request(SpeechAudioRoute.EARPIECE)
        first.close()
        val second = Endpoint()
        val next = controller.attach(second)
        assertEquals(listOf(SpeechAudioRoute.EARPIECE), second.requests)
        assertEquals(SpeechAudioRoute.SPEAKER, controller.snapshot().effective)
        next.close()
        playback.close()
        val another = Endpoint()
        controller.attach(another)
        assertTrue(another.requests.isEmpty())
    }

    @Test fun rejectedPrivateContinuationAbortsRatherThanStartingOnSpeaker() {
        val controller = SpeechAudioRouteController()
        controller.beginPlayback()
        val first = controller.attach(Endpoint())
        controller.request(SpeechAudioRoute.EARPIECE)
        first.close()
        val rejected = Endpoint().apply { accepted = false }
        assertThrows(IllegalStateException::class.java) { controller.attach(rejected) }
        assertEquals(listOf(SpeechAudioRoute.EARPIECE), rejected.requests)
        assertEquals(1, rejected.closes)
        assertFalse(controller.snapshot().active)
    }

    @Test fun headsetArrivalNeverTriggersAutomaticBuiltinReplay() {
        val controller = SpeechAudioRouteController()
        controller.beginPlayback()
        val first = controller.attach(Endpoint())
        controller.request(SpeechAudioRoute.EARPIECE)
        first.close()
        val external = Endpoint().apply { this.external = true }
        assertThrows(IllegalStateException::class.java) { controller.attach(external) }
        assertTrue(external.requests.isEmpty())
    }

    @Test fun observerFailureDoesNotBlockOtherObserversOrRelease() {
        val controller = SpeechAudioRouteController()
        controller.observe { error("observer failure") }
        val received = mutableListOf<SpeechAudioRouteState>()
        controller.observe { received += it }
        val registration = controller.attach(Endpoint())
        registration.close()
        assertTrue(received.any { it.active })
        assertFalse(received.last().active)
    }
}
