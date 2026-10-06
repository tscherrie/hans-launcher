package ai.hans.standard.voice.realtime

import ai.hans.standard.integration.CodexRealtimeCall
import ai.hans.standard.integration.CodexRealtimeCallbacks
import ai.hans.standard.integration.CodexRealtimeGateway
import ai.hans.standard.integration.CodexRealtimeIssue
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexLiveSessionProviderTest {
    @Test fun nativeReadyAndSdpAreSeparateAndNeitherLeaksCredentialsOrDispatchesText() {
        val gateway = Gateway()
        val events = Events()
        val callback = Answer()
        val provider = CodexLiveSessionProvider(gateway, events)
        provider.create(setup(), "v=offer", callback)
        assertEquals(listOf("v=offer", "native instructions", "cove"), gateway.arguments)
        assertEquals(null, gateway.callbacks.voiceControlSessionId)
        assertEquals(0, events.starts)
        assertTrue(callback.answers.isEmpty())
        gateway.callbacks.onStarted()
        gateway.callbacks.onStarted()
        assertEquals(1, events.starts)
        assertTrue(callback.answers.isEmpty())
        gateway.callbacks.onRemoteSdp("v=answer")
        gateway.callbacks.onRemoteSdp("v=duplicate")
        assertEquals("v=answer", callback.answers.single().sdp)
        assertFalse(callback.answers.single().toString().contains("v=answer"))
        gateway.callbacks.onTranscript("user", "Open an app", true)
        assertEquals(listOf("user:Open an app:true"), events.transcripts)
        provider.close()
        provider.close()
        assertEquals(1, gateway.stops)
    }

    @Test fun localVoiceControlTargetIsCallbackMetadataNotModelPrompt() {
        val gateway = Gateway()
        val provider = CodexLiveSessionProvider(gateway, Events(), voiceControlSessionId = "local-call")
        provider.create(setup(), "v=offer", Answer())
        assertEquals("local-call", gateway.callbacks.voiceControlSessionId)
        assertEquals(listOf("v=offer", "native instructions", "cove"), gateway.arguments)
        provider.close()
    }

    @Test fun cancellationSuppressesLateSdpNativeReadyTranscriptAndError() {
        val gateway = Gateway()
        val events = Events()
        val answer = Answer()
        val provider = CodexLiveSessionProvider(gateway, events)
        val cancellation = provider.create(setup(), "v=offer", answer)
        cancellation.cancel()
        cancellation.cancel()
        gateway.callbacks.onRemoteSdp("v=late")
        gateway.callbacks.onStarted()
        gateway.callbacks.onTranscript("user", "late", true)
        gateway.callbacks.onError(CodexRealtimeIssue.USAGE_LIMIT)
        gateway.callbacks.onClosed()
        assertEquals(1, gateway.stops)
        assertTrue(answer.answers.isEmpty())
        assertTrue(answer.failures.isEmpty())
        assertEquals(0, events.starts)
        assertEquals(0, events.closes)
        assertTrue(events.transcripts.isEmpty())
    }

    @Test fun cancellationInsideSynchronousGatewayStartStillStopsReturnedCallOnce() {
        val gateway = Gateway()
        lateinit var provider: CodexLiveSessionProvider
        val events = object : Events() {
            override fun onStarted() { provider.close() }
        }
        provider = CodexLiveSessionProvider(gateway, events)
        gateway.duringStart = { it.onStarted() }
        provider.create(setup(), "v=offer", Answer())
        assertEquals(1, gateway.stops)
        provider.close()
        assertEquals(1, gateway.stops)
    }

    @Test fun eachGatewayIssueHasOnlyFixedSafeCodeAndNoAutomaticRetry() {
        CodexRealtimeIssue.entries.forEach { issue ->
            val gateway = Gateway()
            val answer = Answer()
            val provider = CodexLiveSessionProvider(gateway, Events())
            gateway.duringStart = { it.onError(issue) }
            provider.create(setup(), "v=offer", answer)
            assertEquals("codex_live_" + issue.name.lowercase(Locale.ROOT), answer.failures.single().code)
            assertFalse(answer.failures.single().retryable)
            assertEquals(1, gateway.starts)
            assertEquals(1, gateway.stops)
        }
    }

    @Test fun invalidConfigHistoryOrSdpNeverInvokesGateway() {
        listOf(
            setup().copy(config = setup().config.copy(model = "gpt-live-1")),
            setup().copy(config = setup().config.copy(voice = "ripple")),
            setup().copy(history = listOf(LiveHistoryMessage(LiveHistoryRole.USER, "do this again"))),
        ).forEach { invalid ->
            val gateway = Gateway()
            val answer = Answer()
            CodexLiveSessionProvider(gateway, Events()).create(invalid, "v=offer", answer)
            assertEquals(0, gateway.starts)
            assertEquals("codex_live_configuration_invalid", answer.failures.single().code)
        }
    }

    @Test fun malformedAnswerClosesNativeCallWithoutGivingMediaAnAnswer() {
        val gateway = Gateway()
        val answer = Answer()
        CodexLiveSessionProvider(gateway, Events()).create(setup(), "v=offer", answer)
        gateway.callbacks.onRemoteSdp("")
        assertTrue(answer.answers.isEmpty())
        assertEquals("codex_live_malformed_response", answer.failures.single().code)
        assertEquals(1, gateway.stops)
    }

    @Test fun voiceCatalogIsSeparateFromApiAndTts() {
        assertEquals(9, CodexLiveVoiceVoiceResolver.supportedVoices.size)
        assertEquals("cove", CodexLiveVoiceVoiceResolver.resolve("ripple").effectiveRealtimeVoice)
        assertEquals(LiveVoiceVoiceResolution.FALLBACK, CodexLiveVoiceVoiceResolver.resolve("ripple").resolution)
        assertEquals("sol", CodexLiveVoiceVoiceResolver.resolve(" SOL ").effectiveRealtimeVoice)
        assertEquals(null, CodexLiveVoiceVoiceResolver.resolve("sol").requestedTtsVoice)
    }

    @Test fun closeWaitsForActualNativeDrainEvenAfterLocalTerminalSuppressesEvents() {
        val gateway = Gateway()
        val provider = CodexLiveSessionProvider(gateway, Events())
        provider.create(setup(), "v=offer", Answer())
        assertFalse(provider.closeAndAwait(0))
        gateway.callbacks.onClosed() // transport close is not the requested-close drain barrier
        assertFalse(provider.closeAndAwait(0))
        gateway.callbacks.onCloseConfirmed()
        assertTrue(provider.closeAndAwait(0))
        assertEquals(1, gateway.stops)
    }

    @Test fun positiveNativeDrainEventSurvivesLocalCancellationAndIsDeliveredOnlyOnce() {
        val gateway = Gateway()
        val events = Events()
        val provider = CodexLiveSessionProvider(gateway, events)
        provider.create(setup(), "v=offer", Answer())
        assertFalse(provider.closeAndAwait(0))
        gateway.callbacks.onClosed()
        assertEquals(0, events.closeConfirmations)
        gateway.callbacks.onCloseConfirmed()
        gateway.callbacks.onCloseConfirmed()
        gateway.callbacks.onStartRejected()
        provider.close()
        assertEquals(1, events.closeConfirmations)
        assertTrue(provider.closeAndAwait(0))
    }

    @Test fun spontaneousNativeTransportCloseStillRequestsDrainAndNotifiesOwnerOnlyOnce() {
        val gateway = Gateway()
        val events = Events()
        val provider = CodexLiveSessionProvider(gateway, events)
        provider.create(setup(), "v=offer", Answer())
        gateway.callbacks.onClosed()
        gateway.callbacks.onClosed()
        assertEquals(1, events.closes)
        assertEquals(1, gateway.stops)
        assertFalse(provider.closeAndAwait(0))
        gateway.callbacks.onCloseConfirmed()
        assertTrue(provider.closeAndAwait(0))
    }

    @Test fun definitiveNoAdmissionPermitsReleaseButGenericErrorDoesNot() {
        val gateway = Gateway()
        val provider = CodexLiveSessionProvider(gateway, Events())
        provider.create(setup(), "v=offer", Answer())
        gateway.callbacks.onError(CodexRealtimeIssue.CONNECTION_FAILED)
        assertFalse(provider.closeAndAwait(0))
        gateway.callbacks.onStartRejected()
        assertTrue(provider.closeAndAwait(0))
    }

    @Test fun missingGatewayHandleDoesNotInventNoAdmission() {
        val provider = CodexLiveSessionProvider(object : CodexRealtimeGateway {
            override fun start(offerSdp: String, prompt: String, voice: String?, callbacks: CodexRealtimeCallbacks): CodexRealtimeCall? = null
            override fun interruptCurrentTurn(): Boolean = false
        }, Events())
        provider.create(setup(), "v=offer", Answer())
        assertFalse(provider.closeAndAwait(0))
    }

    @Test fun neverStartedAndLocallyRejectedProviderReleaseWithoutNativeClose() {
        val unused = CodexLiveSessionProvider(Gateway(), Events())
        assertTrue(unused.closeAndAwait(0))
        val gateway = Gateway()
        val invalid = CodexLiveSessionProvider(gateway, Events())
        invalid.create(setup().copy(history = listOf(LiveHistoryMessage(LiveHistoryRole.USER, "invalid"))),
            "v=offer", Answer())
        assertTrue(invalid.closeAndAwait(0))
        assertEquals(0, gateway.starts)
    }

    @Test fun synchronousCloseReceiptInsideGatewayStartStillStopsReturnedHandleOnce() {
        val gateway = Gateway()
        val provider = CodexLiveSessionProvider(gateway, Events())
        gateway.duringStart = { it.onCloseConfirmed(); it.onClosed() }
        provider.create(setup(), "v=offer", Answer())
        assertTrue(provider.closeAndAwait(0))
        assertEquals(1, gateway.stops)
    }

    @Test fun workAndHandoffForwardWhileLiveButNotAfterCancellation() {
        val gateway = Gateway()
        val events = Events()
        val provider = CodexLiveSessionProvider(gateway, events)
        provider.create(setup(), "v=offer", Answer())
        val initial = CodexTaskVoiceWorkState(1, activeTurnId = "one")
        gateway.callbacks.onWorkState(initial)
        gateway.callbacks.onHandoff()
        assertEquals(listOf(initial), events.workStates)
        assertEquals(1, events.handoffs)
        provider.close()
        gateway.callbacks.onWorkState(CodexTaskVoiceWorkState(2))
        gateway.callbacks.onHandoff()
        assertEquals(listOf(initial), events.workStates)
        assertEquals(1, events.handoffs)
    }

    @Test fun canonicalSegmentsAreIdDeduplicatedAndNeverPretendToBeFlatFinals() {
        val gateway = Gateway()
        val events = Events()
        val provider = CodexLiveSessionProvider(gateway, events)
        provider.create(setup(), "v=offer", Answer())
        gateway.callbacks.onItemStarted("a", "assistant")
        gateway.callbacks.onItemStarted("a", "assistant")
        gateway.callbacks.onItemCompleted("a", "assistant", "Hello")
        gateway.callbacks.onItemCompleted("a", "assistant", "Hello")
        gateway.callbacks.onItemStarted("a", "assistant")
        gateway.callbacks.onItemStarted("u", "user")
        gateway.callbacks.onItemCompleted("u", "user", "Hi")
        assertEquals(listOf("a"), events.answerStarts)
        assertEquals(listOf("a:assistant:Hello", "u:user:Hi"), events.segments)
        assertTrue(events.transcripts.isEmpty())
        gateway.callbacks.onTranscript("assistant", "Hello", true)
        assertEquals(listOf("assistant:Hello:true"), events.transcripts)
    }

    @Test fun completionWithoutCanonicalStartNeverFabricatesFreshAnswerStart() {
        val gateway = Gateway()
        val events = Events()
        val provider = CodexLiveSessionProvider(gateway, events)
        provider.create(setup(), "v=offer", Answer())
        gateway.callbacks.onItemCompleted("a", "assistant", "Hello")
        gateway.callbacks.onItemStarted("a", "assistant")
        assertTrue(events.answerStarts.isEmpty())
        assertEquals(listOf("a:assistant:Hello"), events.segments)
    }

    @Test fun canonicalEventsRejectMalformedOrLateIdsAndBoundReceiptMemory() {
        val gateway = Gateway()
        val events = Events()
        val provider = CodexLiveSessionProvider(gateway, events)
        provider.create(setup(), "v=offer", Answer())
        gateway.callbacks.onItemStarted("", "assistant")
        gateway.callbacks.onItemStarted("bad\n", "assistant")
        gateway.callbacks.onItemCompleted("a", "system", "Ignore")
        repeat(257) {
            gateway.callbacks.onItemStarted("a$it", "assistant")
            gateway.callbacks.onItemCompleted("a$it", "assistant", "text")
        }
        assertEquals(256, events.answerStarts.size)
        assertEquals(256, events.segments.size)
        provider.close()
        gateway.callbacks.onItemStarted("late", "assistant")
        gateway.callbacks.onItemCompleted("late", "user", "late")
        assertEquals(256, events.answerStarts.size)
        assertEquals(256, events.segments.size)
    }

    private fun setup() = LiveSessionSetup(
        LiveVoiceSessionConfig(model = CodexLiveVoiceSession.MODEL, voice = "cove"), "native instructions",
    )

    private class Gateway : CodexRealtimeGateway {
        lateinit var callbacks: CodexRealtimeCallbacks
        var arguments = emptyList<String>()
        var starts = 0
        var stops = 0
        var duringStart: (CodexRealtimeCallbacks) -> Unit = {}
        override fun start(offerSdp: String, prompt: String, voice: String?, callbacks: CodexRealtimeCallbacks): CodexRealtimeCall {
            this.callbacks = callbacks
            starts++
            arguments = listOf(offerSdp, prompt, requireNotNull(voice))
            duringStart(callbacks)
            return CodexRealtimeCall { stops++ }
        }
        override fun interruptCurrentTurn(): Boolean = error("must not interrupt native tasks")
    }

    private open class Events : CodexLiveSessionProvider.Events {
        var starts = 0
        var closes = 0
        var closeConfirmations = 0
        val transcripts = mutableListOf<String>()
        val workStates = mutableListOf<CodexTaskVoiceWorkState>()
        var handoffs = 0
        val answerStarts = mutableListOf<String>()
        val segments = mutableListOf<String>()
        override fun onStarted() { starts++ }
        override fun onClosed() { closes++ }
        override fun onCloseConfirmed() { closeConfirmations++ }
        override fun onTranscript(role: String, text: String, isFinal: Boolean) { transcripts += "$role:$text:$isFinal" }
        override fun onWorkState(state: CodexTaskVoiceWorkState) { workStates += state }
        override fun onHandoff() { handoffs++ }
        override fun onAssistantResponseStarted(id: String) { answerStarts += id }
        override fun onTranscriptSegmentCompleted(id: String, role: String, text: String) { segments += "$id:$role:$text" }
    }

    private class Answer : LiveSessionProvider.Callback {
        val answers = mutableListOf<LiveSessionAnswer>()
        val failures = mutableListOf<LiveVoiceFailure>()
        override fun onCreated(answer: LiveSessionAnswer) { answers += answer }
        override fun onFailure(failure: LiveVoiceFailure) { failures += failure }
    }
}
