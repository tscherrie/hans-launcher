package ai.hans.standard.voice.realtime

import ai.hans.standard.integration.CodexRealtimeCall
import ai.hans.standard.integration.CodexRealtimeCallbacks
import ai.hans.standard.integration.CodexRealtimeGateway
import ai.hans.standard.integration.CodexRealtimeIssue
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexLiveVoiceSessionTest {
    @Test fun positiveLateMediaDisposalEndsClosingAndAllowsANormalNewStart() = Fixture().use { fixture ->
        val media = fixture.active()
        media.disposalConfirmed = false
        fixture.session.stop()
        fixture.drain()
        assertEquals(LiveVoicePhase.CONFIGURING, fixture.session.snapshot.phase)
        assertEquals("codex_task_voice_audio_close_unconfirmed", fixture.session.snapshot.lastFailureCode)
        assertFalse(fixture.session.setInputMuted(false))
        fixture.session.start()
        fixture.drain()
        assertEquals(1, fixture.gateway.callbacks.size)

        media.confirmDisposal()
        fixture.drain()
        assertEquals(LiveVoicePhase.STOPPED, fixture.session.snapshot.phase)
        assertEquals(null, fixture.session.snapshot.lastFailureCode)
        assertEquals(1, fixture.phases.count { it == LiveVoicePhase.STOPPED })
        val next = fixture.active()
        assertEquals(2, fixture.gateway.callbacks.size)
        assertEquals(0, next.closes)
    }

    @Test fun lateClosingNeedsBothPositiveReceiptsAndOldGenerationCannotReleaseNewOwner() {
        listOf(true, false).forEach { mediaFirst -> Fixture().use { fixture ->
            val media = fixture.active()
            val native = fixture.gateway.callbacks.single()
            fixture.gateway.autoConfirmClose = false
            media.disposalConfirmed = false
            fixture.session.stop()
            fixture.drain()
            assertEquals(LiveVoicePhase.CONFIGURING, fixture.session.snapshot.phase)
            native.onClosed() // Connection closed is not the native queue's drain barrier.
            media.listener.onClosed(null) // Nor is this proof of physical media disposal.
            fixture.drain()
            assertEquals(LiveVoicePhase.CONFIGURING, fixture.session.snapshot.phase)
            if (mediaFirst) media.confirmDisposal() else native.onCloseConfirmed()
            fixture.drain()
            assertEquals(LiveVoicePhase.CONFIGURING, fixture.session.snapshot.phase)
            if (mediaFirst) native.onCloseConfirmed() else media.confirmDisposal()
            fixture.drain()
            assertEquals(LiveVoicePhase.STOPPED, fixture.session.snapshot.phase)
            assertEquals(1, fixture.phases.count { it == LiveVoicePhase.STOPPED })

            fixture.gateway.autoConfirmClose = true
            val next = fixture.active()
            val nextGeneration = fixture.session.snapshot.generation
            media.listener.onMediaDisposed()
            native.onCloseConfirmed()
            native.onClosed()
            fixture.drain()
            assertEquals(LiveVoicePhase.LISTENING, fixture.session.snapshot.phase)
            assertEquals(nextGeneration, fixture.session.snapshot.generation)
            assertEquals(0, next.closes)
        } }
    }

    @Test fun failedPhysicalCleanupStaysFailClosedAndRepeatedStopDoesNotRepeatCleanup() = Fixture().use { fixture ->
        val media = fixture.active()
        media.disposalConfirmed = false
        fixture.session.stop()
        fixture.drain()
        fixture.session.stop()
        fixture.session.close()
        fixture.drain()
        media.listener.onClosed(null)
        fixture.gateway.callbacks.single().onCloseConfirmed()
        fixture.drain()
        assertEquals(LiveVoicePhase.CONFIGURING, fixture.session.snapshot.phase)
        assertEquals("codex_task_voice_audio_close_unconfirmed", fixture.session.snapshot.lastFailureCode)
        assertEquals(1, media.closes)
        assertEquals(1, fixture.gateway.stops.get())
        assertFalse(fixture.session.setInputMuted(false))
        fixture.session.start()
        fixture.drain()
        assertEquals(1, fixture.gateway.callbacks.size)
    }

    @Test fun confirmedMediaAloneDoesNotOptimisticallyReleaseUnconfirmedNativeDrain() = Fixture().use { fixture ->
        fixture.active()
        fixture.gateway.autoConfirmClose = false
        fixture.session.stop()
        fixture.drain()
        assertEquals(LiveVoicePhase.CONFIGURING, fixture.session.snapshot.phase)
        assertEquals("codex_task_voice_native_close_unconfirmed", fixture.session.snapshot.lastFailureCode)
        fixture.session.start()
        fixture.drain()
        assertEquals(1, fixture.gateway.callbacks.size)
        fixture.gateway.callbacks.single().onCloseConfirmed()
        fixture.drain()
        assertEquals(LiveVoicePhase.STOPPED, fixture.session.snapshot.phase)
        fixture.gateway.autoConfirmClose = true
        fixture.active()
        assertEquals(2, fixture.gateway.callbacks.size)
    }

    @Test fun lateDisposalClearsOnlyTemporaryClosingDiagnosticAndPreservesOriginalFailure() = Fixture().use { fixture ->
        val media = fixture.active()
        media.disposalConfirmed = false
        fixture.gateway.callbacks.single().onError(CodexRealtimeIssue.USAGE_LIMIT)
        fixture.drain()
        assertEquals(LiveVoicePhase.CONFIGURING, fixture.session.snapshot.phase)
        assertEquals("codex_task_voice_audio_close_unconfirmed", fixture.session.snapshot.lastFailureCode)
        media.confirmDisposal()
        fixture.drain()
        assertEquals(LiveVoicePhase.FAILED, fixture.session.snapshot.phase)
        assertEquals("codex_live_usage_limit", fixture.session.snapshot.lastFailureCode)
        assertEquals(1, fixture.gateway.stops.get())
        assertEquals(1, media.closes)
    }

    @Test fun terminalCloseCannotBeOvertakenByAnAlreadyEnteredUnmute() {
        listOf("stop", "close", "native-close").forEach { terminal -> Fixture().use { fixture ->
            val media = fixture.active()
            assertTrue(fixture.session.setInputMuted(true))
            fixture.drain()
            val unmuteEntered = CountDownLatch(1)
            val releaseUnmute = CountDownLatch(1)
            val terminalEntered = CountDownLatch(1)
            val terminalReturned = CountDownLatch(1)
            val workers = Executors.newFixedThreadPool(2)
            media.beforeMute = { value -> if (!value) {
                unmuteEntered.countDown()
                check(releaseUnmute.await(2, TimeUnit.SECONDS))
            } }
            try {
                val unmute = workers.submit<Boolean> { fixture.session.setInputMuted(false) }
                assertTrue(unmuteEntered.await(2, TimeUnit.SECONDS))
                val end = workers.submit {
                    terminalEntered.countDown()
                    when (terminal) {
                        "stop" -> fixture.session.stop()
                        "close" -> fixture.session.close()
                        else -> { fixture.gateway.callbacks.single().onClosed(); fixture.drain() }
                    }
                    terminalReturned.countDown()
                }
                assertTrue(terminalEntered.await(2, TimeUnit.SECONDS))
                // Teardown must serialize behind the in-flight setter, not mute and then let
                // that setter re-enable capture after the terminal request has already won.
                assertFalse(terminal, terminalReturned.await(100, TimeUnit.MILLISECONDS))
                releaseUnmute.countDown()
                unmute.get(2, TimeUnit.SECONDS)
                end.get(2, TimeUnit.SECONDS)
                fixture.drain()
                assertEquals(LiveVoicePhase.STOPPED, fixture.session.snapshot.phase)
                assertTrue(terminal, media.userMuted)
                assertTrue(media.muteRequests.last())
                assertFalse(fixture.session.setInputMuted(false))
            } finally {
                releaseUnmute.countDown()
                media.beforeMute = null
                workers.shutdownNow()
            }
        } }
    }

    @Test fun secondTapDuringInitialContextOrMediaFactoryIsAcceptedBeforeAnyCaptureExists() {
        listOf(false, true).forEach { blockFactory ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val block = { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
            Fixture(entryPoint = LiveVoiceEntryPoint.DICTATION,
                beforeContext = if (blockFactory) ({}) else block,
                beforeMedia = if (blockFactory) block else ({})).use { fixture ->
                try {
                    fixture.session.start()
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    assertEquals(LiveVoicePhase.IDLE, fixture.session.snapshot.phase)
                    assertTrue(fixture.session.setInputMuted(true))
                    assertTrue(fixture.phases.isEmpty()) // No false IDLE ownership-release receipt.
                } finally { release.countDown() }
                await { fixture.gateway.callbacks.size == 1 }
                fixture.drain()
                val media = fixture.media.single()
                assertEquals(listOf(true), media.muteRequests.toList())
                assertTrue(media.userMuted)
                assertTrue(fixture.session.snapshot.inputMuted)
                fixture.gateway.callbacks.single().onStarted()
                media.listener.onOpen()
                await { fixture.session.snapshot.phase == LiveVoicePhase.LISTENING }
                assertTrue(media.userMuted)
                assertEquals(0, fixture.gateway.stops.get())
            }
        }
    }

    @Test fun muteCanBeLatchedBeforeTransportWithoutLosingItDuringStart() =
        Fixture(entryPoint = LiveVoiceEntryPoint.DICTATION).use { fixture ->
            assertTrue(fixture.session.setInputMuted(true))
            val media = fixture.start()
            assertTrue(media.userMuted)
            assertTrue(fixture.session.snapshot.inputMuted)
            fixture.gateway.callbacks.single().onStarted()
            media.listener.onOpen() // No physical input frame is required for an already-muted start.
            await { fixture.session.snapshot.phase == LiveVoicePhase.LISTENING }
            assertEquals(0, fixture.gateway.stops.get())
            assertTrue(fixture.session.setInputMuted(false))
            fixture.drain()
            assertFalse(media.userMuted)
            assertFalse(fixture.session.snapshot.inputMuted)
            fixture.session.stop()
            fixture.drain()
            assertFalse(fixture.session.setInputMuted(false))
        }

    @Test fun actualCodexVoiceAssetAndNativeRulesRequireNoExtraCommandAfterFarewell() = Fixture(
        instructions = File("src/main/assets/hans/codex-live-voice-instructions.md").readText(),
    ).use { fixture ->
        fixture.start()
        val prompt = fixture.gateway.prompts.single().replace(Regex("\\s+"), " ")
        listOf("A clear farewell alone is sufficient", "Tschüss", "Mach's gut", "Bis später",
            "Bye", "See you", "hans_voice.end_call({})", "or confirmation",
            "If the user continues", "quoted or hypothetical", "never ask for a session ID",
        ).forEach { assertTrue("Missing farewell contract: $it", prompt.contains(it)) }
        assertFalse(prompt.contains("Do not delegate a plain goodbye"))
        assertFalse(prompt.contains("farewells and speaking controls do not need a new task"))
        assertEquals(1, fixture.gateway.callbacks.size)
    }

    @Test fun hangupTargetIsLocalMetadataAndNeverRequiresAnIdentifierInTheVoicePrompt() = Fixture().use { fixture ->
        fixture.start()
        assertEquals(fixture.session.voiceSessionId, fixture.gateway.callbacks.single().voiceControlSessionId)
        val prompt = fixture.gateway.prompts.single()
        assertFalse(prompt.contains(fixture.session.voiceSessionId))
        assertFalse(prompt.contains("voice_session_id="))
        assertTrue(prompt.contains("Do not ask the user for"))
    }

    @Test fun stopCancelsQueuedStartBeforeAnyNativeCallOrMediaSetup() = Fixture().use { fixture ->
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.scheduler.execute { held.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
        assertTrue(held.await(2, TimeUnit.SECONDS))
        fixture.session.start()
        fixture.session.stop()
        release.countDown()
        fixture.drain()
        assertTrue(fixture.media.isEmpty())
        assertTrue(fixture.gateway.callbacks.isEmpty())
        assertEquals(LiveVoicePhase.STOPPED, fixture.session.snapshot.phase)
    }

    @Test fun microphoneNeedsBothNativeStartedAndConnectedMediaInEitherOrder() {
        listOf(true, false).forEach { nativeFirst -> Fixture().use { fixture ->
            val media = fixture.start()
            val callbacks = fixture.gateway.callbacks.single()
            if (nativeFirst) callbacks.onStarted() else media.listener.onOpen()
            fixture.drain()
            assertEquals(0, media.captureStarts)
            assertEquals(null, fixture.session.snapshot.voiceSelection)
            if (nativeFirst) media.listener.onOpen() else callbacks.onStarted()
            await { fixture.session.snapshot.phase == LiveVoicePhase.LISTENING }
            assertEquals(1, media.captureStarts)
            callbacks.onStarted()
            media.listener.onOpen()
            fixture.drain()
            assertEquals(1, media.captureStarts)
        } }
    }

    @Test fun nativeErrorClosesBothPathsAndNeverRecreatesCall() = Fixture().use { fixture ->
        val media = fixture.start()
        fixture.gateway.callbacks.single().onError(CodexRealtimeIssue.USAGE_LIMIT)
        await { fixture.session.snapshot.phase == LiveVoicePhase.FAILED }
        assertEquals("codex_live_usage_limit", fixture.session.snapshot.lastFailureCode)
        assertEquals(1, media.closes)
        assertEquals(1, fixture.gateway.stops.get())
        assertEquals(1, fixture.gateway.callbacks.size)
        assertEquals(0, media.captureStarts)
    }

    @Test fun stopBeforeReadinessDoesNotOpenMicAndLateCallbacksCannotResurrectCall() = Fixture().use { fixture ->
        val media = fixture.start()
        fixture.session.stop()
        await { fixture.session.snapshot.phase == LiveVoicePhase.STOPPED }
        val old = fixture.gateway.callbacks.single()
        old.onStarted()
        old.onRemoteSdp("v=late")
        old.onTranscript("user", "old command", true)
        media.listener.onOpen()
        fixture.drain()
        assertEquals(0, media.captureStarts)
        assertTrue(fixture.transcripts.isEmpty())
        assertEquals(1, fixture.gateway.stops.get())
        assertEquals(LiveVoicePhase.STOPPED, fixture.session.snapshot.phase)
    }

    @Test fun nativeTranscriptsAreDisplayedExactlyOnceAndDataChannelCannotReplayThem() = Fixture().use { fixture ->
        val media = fixture.active()
        val callbacks = fixture.gateway.callbacks.single()
        callbacks.onTranscript("user", "Open ", false)
        callbacks.onTranscript("user", "the app", false)
        callbacks.onTranscript("user", "Open the app.", true)
        callbacks.onTranscript("assistant", "Done", false)
        callbacks.onTranscript("assistant", "Done.", true)
        media.listener.onEvent("""{"type":"delegation.created","item":{"id":"do-again"}}""")
        media.listener.onEvent("""{"type":"input_transcript.added","item":{"text":"Open the app."}}""")
        fixture.session.refreshContext()
        fixture.drain()
        assertEquals(listOf("user:Open :false", "user:Open the app:false", "user:Open the app.:true",
            "assistant:Done:false", "assistant:Done.:true"), fixture.transcripts.toList())
        assertEquals(1, fixture.responses.get())
        assertTrue(media.sent.isEmpty())
        assertEquals(1, fixture.gateway.callbacks.size)
        assertEquals(0, fixture.gateway.interrupts.get())
    }

    @Test fun nativeEmptyFinalClearsPartialAndHangupNeverPromotesPartialToFinal() = Fixture().use { fixture ->
        fixture.active()
        val native = fixture.gateway.callbacks.single()
        native.onTranscript("user", "discarded", false)
        native.onTranscript("user", "", true)
        native.onTranscript("assistant", "discarded", false)
        native.onTranscript("assistant", "", true)
        native.onTranscript("user", "unfinished", false)
        fixture.drain()
        fixture.session.stop()
        fixture.drain()
        assertEquals(listOf("user:discarded:false", "user::true", "assistant:discarded:false",
            "assistant::true", "user:unfinished:false"), fixture.transcripts.toList())
    }

    @Test fun transcriptFeedbackTracksCurrentSpeakerAndLateOtherFinalCannotEraseBargeIn() = Fixture().use { fixture ->
        fixture.active()
        val native = fixture.gateway.callbacks.single()
        native.onTranscript("assistant", "The answer", false)
        fixture.drain()
        assertEquals(LiveVoicePhase.HANS_SPEAKING, fixture.session.snapshot.phase)
        native.onTranscript("user", "Wait", false)
        fixture.drain()
        assertEquals(LiveVoicePhase.USER_SPEAKING, fixture.session.snapshot.phase)
        native.onTranscript("assistant", "The answer.", true)
        fixture.drain()
        assertEquals(LiveVoicePhase.USER_SPEAKING, fixture.session.snapshot.phase)
        native.onTranscript("user", "Wait.", true)
        fixture.drain()
        assertEquals(LiveVoicePhase.LISTENING, fixture.session.snapshot.phase)

        native.onTranscript("user", "One more", false)
        native.onTranscript("assistant", "Of course", false)
        native.onTranscript("user", "One more.", true)
        fixture.drain()
        assertEquals(LiveVoicePhase.HANS_SPEAKING, fixture.session.snapshot.phase)
        native.onTranscript("assistant", "", true)
        fixture.drain()
        assertEquals(LiveVoicePhase.LISTENING, fixture.session.snapshot.phase)
    }

    @Test fun startupMuteSurvivesReadinessAndDoesNotChangeAgentWork() = Fixture().use { fixture ->
        val media = fixture.start()
        assertTrue(fixture.session.setInputMuted(true))
        fixture.gateway.callbacks.single().onStarted()
        media.listener.onOpen()
        await { fixture.session.snapshot.phase == LiveVoicePhase.LISTENING }
        assertTrue(media.userMuted)
        assertTrue(fixture.session.snapshot.inputMuted)
        fixture.session.interruptHans()
        fixture.drain()
        assertEquals(0, fixture.gateway.interrupts.get())
        assertTrue(media.sent.isEmpty())
    }

    @Test fun reconnectIsOnlyExplicitAndOldGenerationCannotAffectNewCall() = Fixture().use { fixture ->
        val oldMedia = fixture.active()
        val oldNative = fixture.gateway.callbacks.single()
        oldMedia.listener.onClosed(LiveVoiceFailure("realtime_connection_lost", true))
        await { fixture.session.snapshot.phase == LiveVoicePhase.FAILED }
        assertEquals(1, fixture.gateway.callbacks.size)
        val next = fixture.active()
        oldNative.onClosed()
        oldNative.onError(CodexRealtimeIssue.AUTHENTICATION)
        oldMedia.listener.onClosed(LiveVoiceFailure("realtime_connection_lost", true))
        fixture.drain()
        assertEquals(LiveVoicePhase.LISTENING, fixture.session.snapshot.phase)
        assertEquals(0, next.closes)
        assertEquals(2, fixture.gateway.callbacks.size)
    }

    @Test fun connectTimeoutStopsUncertainCreationWithoutOpeningMicOrRetrying() = Fixture().use { fixture ->
        val media = fixture.start()
        await(2_500) { fixture.session.snapshot.phase == LiveVoicePhase.FAILED }
        assertEquals("codex_live_connect_timeout", fixture.session.snapshot.lastFailureCode)
        assertEquals(0, media.captureStarts)
        assertEquals(1, fixture.gateway.stops.get())
        assertEquals(1, fixture.gateway.callbacks.size)
    }

    @Test fun finalFarewellNeedsFreshMicQuietAndHeardOutputBeforeHangup() = Fixture().use { fixture ->
        val media = fixture.active()
        val native = fixture.gateway.callbacks.single()
        native.onTranscript("user", "Goodbye!", false)
        fixture.drain()
        assertFalse(media.monitoring)
        native.onTranscript("user", "Goodbye!", true)
        fixture.drain()
        assertTrue(media.monitoring)
        native.onTranscript("assistant", "Bye!", true)
        fixture.drain()
        assertEquals(LiveVoicePhase.LISTENING, fixture.session.snapshot.phase)
        fixture.audio(media, LiveVoiceAudioDirection.INPUT, false, 300)
        fixture.audio(media, LiveVoiceAudioDirection.OUTPUT, true, 400)
        fixture.audio(media, LiveVoiceAudioDirection.OUTPUT, false, 700)
        fixture.audio(media, LiveVoiceAudioDirection.INPUT, false, 1_900)
        fixture.audio(media, LiveVoiceAudioDirection.OUTPUT, false, 1_900)
        await { fixture.session.snapshot.phase == LiveVoicePhase.STOPPED }
        assertEquals(1, fixture.gateway.stops.get())
        assertEquals(0, fixture.gateway.interrupts.get())
    }

    @Test fun userContinuationRetractsFarewellAndThanksNeverArmsIt() = Fixture().use { fixture ->
        val media = fixture.active()
        val native = fixture.gateway.callbacks.single()
        native.onTranscript("user", "Goodbye!", true)
        fixture.drain()
        assertTrue(media.monitoring)
        native.onTranscript("user", "Wait, one more question", false)
        fixture.drain()
        assertFalse(media.monitoring)
        native.onTranscript("user", "Thanks", true)
        fixture.drain()
        assertFalse(media.monitoring)
        assertEquals(0, fixture.gateway.stops.get())
    }

    @Test fun contextFailureIsVisibleBeforeCreatingAnyCall() {
        val scheduler = ScheduledThreadPoolExecutor(1)
        val session = CodexLiveVoiceSession(Gateway(), { error("must not create media") },
            LiveVoiceInstructionsProvider { error("unavailable") }, scheduler = scheduler)
        try {
            session.start()
            await { session.snapshot.phase == LiveVoicePhase.FAILED }
            assertEquals("codex_live_context_invalid", session.snapshot.lastFailureCode)
        } finally { session.close(); scheduler.shutdownNow() }
    }

    private class Fixture(val instructions: String = "Native Hans instructions.",
        entryPoint: LiveVoiceEntryPoint = LiveVoiceEntryPoint.PHONE,
        beforeContext: () -> Unit = {}, beforeMedia: () -> Unit = {}) : AutoCloseable {
        val scheduler = ScheduledThreadPoolExecutor(1)
        val gateway = Gateway()
        val media = CopyOnWriteArrayList<Media>()
        val transcripts = CopyOnWriteArrayList<String>()
        val phases = CopyOnWriteArrayList<LiveVoicePhase>()
        val responses = AtomicInteger()
        val clock = AtomicLong(TimeUnit.SECONDS.toNanos(100))
        val session = CodexLiveVoiceSession(gateway, { provider -> beforeMedia(); Media(provider).also(media::add) },
            LiveVoiceInstructionsProvider { beforeContext(); instructions }, object : LiveVoiceObserver {
                override fun onSnapshot(snapshot: LiveVoiceSnapshot) { phases += snapshot.phase }
                override fun onUserTranscript(text: String, isFinal: Boolean) { transcripts += "user:$text:$isFinal" }
                override fun onHansTranscript(text: String, isFinal: Boolean) { transcripts += "assistant:$text:$isFinal" }
                override fun onHansResponseReady(event: LiveVoiceResponseReady) { responses.incrementAndGet() }
            }, config = LiveVoiceSessionConfig(model = CodexLiveVoiceSession.MODEL, voice = "cove", connectTimeoutMillis = 1_000),
            scheduler = scheduler, nanoTime = clock::get, entryPoint = entryPoint,
            mediaCloseTimeoutMillis = 0, nativeCloseTimeoutMillis = 0)
        fun start(): Media {
            val expected = media.size + 1
            session.start()
            await { media.size == expected && gateway.callbacks.size == expected }
            return media.last()
        }
        fun active(): Media = start().also {
            gateway.callbacks.last().onStarted()
            it.listener.onOpen()
            await { session.snapshot.phase == LiveVoicePhase.LISTENING }
        }
        fun drain() { scheduler.submit {}.get(2, TimeUnit.SECONDS) }
        fun audio(media: Media, direction: LiveVoiceAudioDirection, active: Boolean, millis: Long) {
            val now = TimeUnit.SECONDS.toNanos(100) + TimeUnit.MILLISECONDS.toNanos(millis)
            clock.set(now)
            media.listener.onAudioActivity(LiveVoiceAudioActivity(direction, active, now))
            drain()
        }
        override fun close() { session.close(); drain(); scheduler.shutdownNow() }
    }

    private class Gateway : CodexRealtimeGateway {
        val callbacks = CopyOnWriteArrayList<CodexRealtimeCallbacks>()
        val prompts = CopyOnWriteArrayList<String>()
        val stops = AtomicInteger()
        val interrupts = AtomicInteger()
        @Volatile var autoConfirmClose = true
        override fun start(offerSdp: String, prompt: String, voice: String?, callbacks: CodexRealtimeCallbacks): CodexRealtimeCall {
            this.prompts += prompt
            this.callbacks += callbacks
            callbacks.onRemoteSdp("v=answer")
            return CodexRealtimeCall {
                stops.incrementAndGet()
                if (autoConfirmClose) callbacks.onCloseConfirmed()
                callbacks.onClosed()
            }
        }
        override fun interruptCurrentTurn(): Boolean { interrupts.incrementAndGet(); return true }
    }

    private class Media(private val provider: LiveSessionProvider) : LiveVoiceTransport {
        lateinit var listener: LiveVoiceTransport.Listener
        var cancellation = LiveVoiceCancellation.NONE
        @Volatile var captureStarts = 0
        @Volatile var closes = 0
        @Volatile var userMuted = false
        @Volatile var monitoring = false
        @Volatile var beforeMute: ((Boolean) -> Unit)? = null
        @Volatile var disposalConfirmed = true
        private var disposalNotified = false
        val sent = CopyOnWriteArrayList<String>()
        val muteRequests = CopyOnWriteArrayList<Boolean>()
        override fun connect(setup: LiveSessionSetup, listener: LiveVoiceTransport.Listener) {
            this.listener = listener
            cancellation = provider.create(setup, "v=offer", object : LiveSessionProvider.Callback {
                override fun onCreated(answer: LiveSessionAnswer) = Unit
                override fun onFailure(failure: LiveVoiceFailure) { listener.onClosed(failure) }
            })
        }
        override fun connect(credential: RealtimeEphemeralCredential, listener: LiveVoiceTransport.Listener) = error("no credential path")
        override fun confirmSessionStarted(): Boolean { captureStarts++; return true }
        override fun sendUtf8(event: String): Boolean { sent += event; return true }
        override fun setInputAudioEnabled(enabled: Boolean): Boolean = true
        override fun setUserInputMuted(muted: Boolean): Boolean {
            beforeMute?.invoke(muted)
            muteRequests += muted
            userMuted = muted
            return true
        }
        override fun clearOutputAudio(): Boolean = false
        override fun setAudioActivityMonitoringEnabled(enabled: Boolean): Boolean { monitoring = enabled; return true }
        override fun close() {
            if (closes != 0) return
            closes++
            cancellation.cancel()
            if (disposalConfirmed) confirmDisposal()
        }
        override fun closeAndAwait(timeoutMillis: Long): Boolean { close(); return disposalConfirmed }
        fun confirmDisposal() {
            disposalConfirmed = true
            if (!disposalNotified) {
                disposalNotified = true
                listener.onMediaDisposed()
            }
        }
    }

    companion object {
        private fun await(timeoutMillis: Long = 2_000, predicate: () -> Boolean) {
            val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (!predicate() && System.nanoTime() < end) Thread.sleep(5)
            assertTrue("Timed out waiting for synthetic session", predicate())
        }
    }
}
