package ai.hans.standard.voice.realtime

import ai.hans.standard.integration.CodexRealtimeCall
import ai.hans.standard.integration.CodexRealtimeCallbacks
import ai.hans.standard.integration.CodexRealtimeGateway
import ai.hans.standard.integration.CodexRealtimeIssue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.PriorityQueue
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic gateway/media only. No microphone, network, credentials or external app actions. */
@RunWith(AndroidJUnit4::class)
class CodexLiveVoiceSessionAndroidTest {
    @Test fun delayedPositiveDisposalAndNativeDrainPermitRestartButOldReceiptsCannotReleaseNewOwner() {
        for (mediaFirst in listOf(true, false)) Fixture().use { f ->
            f.start()
            f.gateway.callbacks.onStarted()
            f.media.listener.onOpen()
            f.drain()
            assertEquals(LiveVoicePhase.LISTENING, f.session.snapshot.phase)
            val oldNative = f.gateway.callbacks
            val oldMedia = f.media
            oldMedia.disposalConfirmed = false
            f.gateway.confirmClose = false
            f.session.stop()
            f.drain()
            assertEquals(LiveVoicePhase.CONFIGURING, f.session.snapshot.phase)
            assertEquals("codex_task_voice_audio_close_unconfirmed", f.session.snapshot.lastFailureCode)
            oldMedia.listener.onClosed(null)
            oldNative.onClosed()
            f.session.start()
            f.drain()
            assertEquals(1, f.gateway.starts.get())
            if (mediaFirst) oldMedia.confirmDisposal() else oldNative.onCloseConfirmed()
            f.drain()
            assertEquals(LiveVoicePhase.CONFIGURING, f.session.snapshot.phase)
            if (mediaFirst) oldNative.onCloseConfirmed() else oldMedia.confirmDisposal()
            f.drain()
            assertEquals(LiveVoicePhase.STOPPED, f.session.snapshot.phase)
            assertEquals(null, f.session.snapshot.lastFailureCode)

            f.gateway.confirmClose = true
            f.session.start()
            f.drain()
            assertEquals(2, f.gateway.starts.get())
            f.gateway.callbacks.onStarted()
            f.media.listener.onOpen()
            f.drain()
            oldMedia.listener.onMediaDisposed()
            oldNative.onCloseConfirmed()
            oldNative.onClosed()
            f.drain()
            assertEquals(LiveVoicePhase.LISTENING, f.session.snapshot.phase)
            assertFalse(f.media.closed)
            assertEquals(1, f.gateway.stops.get())
        }
    }

    @Test fun pendingDictationMuteSurvivesNativeStartupWithoutAFirstMicrophoneFrame() =
        Fixture(LiveVoiceEntryPoint.DICTATION).use { f ->
            assertTrue(f.session.setInputMuted(true))
            f.start()
            assertTrue(f.media.muted)
            f.gateway.callbacks.onStarted()
            f.media.listener.onOpen()
            f.drain()
            assertEquals(LiveVoicePhase.LISTENING, f.session.snapshot.phase)
            assertTrue(f.session.snapshot.inputMuted)
            assertEquals(0, f.gateway.stops.get())
            assertTrue(f.session.setInputMuted(false))
            f.drain()
            assertFalse(f.media.muted)
            assertFalse(f.session.snapshot.inputMuted)
        }

    @Test fun mutedDictationCutsInputAtTaskTerminalAndDrainsOutputInEitherAnswerReceiptOrder() {
        for (answerFirst in listOf(true, false)) Fixture(LiveVoiceEntryPoint.DICTATION).use { f ->
            f.activateDictation()
            f.gateway.callbacks.onHandoff()
            f.gateway.callbacks.onWorkState(CodexTaskVoiceWorkState(1, "turn"))
            f.drain()
            assertTrue(f.session.setInputMuted(true))
            f.drain()
            if (answerFirst) {
                f.gateway.callbacks.onItemStarted("answer", "assistant")
                f.drain()
            }
            f.gateway.callbacks.onWorkState(CodexTaskVoiceWorkState(2,
                terminal = CodexTaskVoiceTerminal("turn", CodexTaskVoiceWorkOutcome.COMPLETED)))
            f.drain()
            if (!answerFirst) {
                f.gateway.callbacks.onItemStarted("answer", "assistant")
                f.drain()
            }
            assertEquals(1, f.media.inputFinishes)
            assertTrue(f.media.inputFinished)
            assertFalse(f.session.setInputMuted(false))
            f.audio(LiveVoiceAudioDirection.OUTPUT, true, 1_200)
            f.audio(LiveVoiceAudioDirection.OUTPUT, true, 1_900)
            f.tick(2_649)
            assertEquals(0, f.gateway.stops.get())
            f.tick(2_650)
            assertEquals(LiveVoicePhase.STOPPED, f.session.snapshot.phase)
            assertTrue(f.media.closed)
            assertEquals(1, f.gateway.stops.get())
            assertTrue(f.media.sent.isEmpty())
        }
    }

    @Test fun installedNativePersonaDelegatesPlainFarewellWithoutExtraHangupCommand() {
        val asset = InstrumentationRegistry.getInstrumentation().targetContext.assets
            .open("hans/codex-live-voice-instructions.md").bufferedReader().use { it.readText() }
        Fixture(instructions = asset).use { f ->
            f.start()
            val prompt = f.media.setup.instructions.replace(Regex("\\s+"), " ")
            listOf("A clear farewell alone is sufficient", "Tschüss", "Mach's gut", "Bis später",
                "Bye", "See you", "hans_voice.end_call({})", "or confirmation",
                "If the user continues", "quoted or hypothetical", "never ask for a session ID",
            ).forEach { assertTrue("Missing installed farewell contract: $it", prompt.contains(it)) }
            assertFalse(prompt.contains("Do not delegate a plain goodbye"))
            assertFalse(prompt.contains("farewells and speaking controls do not need a new task"))
            assertEquals(0, f.media.captureStarts)
        }
    }

    @Test fun nativeReadyAndMediaReadyAreBothRequiredForMicrophone() = Fixture().use { f ->
        f.start()
        f.gateway.callbacks.onStarted()
        f.drain()
        assertEquals(0, f.media.captureStarts)
        f.media.listener.onOpen()
        f.drain()
        assertEquals(LiveVoicePhase.LISTENING, f.session.snapshot.phase)
        assertEquals(1, f.media.captureStarts)
        assertFalse(f.media.muted)
        assertTrue(f.media.sent.isEmpty())
    }

    @Test fun stopClosesNativeLeaseAndIgnoresLateStartWithoutOpeningMicrophone() = Fixture().use { f ->
        f.start()
        f.session.stop()
        f.drain()
        f.gateway.callbacks.onStarted()
        f.gateway.callbacks.onTranscript("user", "do not execute this transcript twice", true)
        f.media.listener.onOpen()
        f.drain()
        assertEquals(LiveVoicePhase.STOPPED, f.session.snapshot.phase)
        assertEquals(0, f.media.captureStarts)
        assertEquals(1, f.gateway.stops.get())
        assertTrue(f.transcripts.isEmpty())
    }

    @Test fun nativeAccountFailureIsVisibleAndDoesNotFallBackToAnApiKey() = Fixture().use { f ->
        f.start()
        f.gateway.callbacks.onError(CodexRealtimeIssue.USAGE_LIMIT)
        f.drain()
        assertEquals(LiveVoicePhase.FAILED, f.session.snapshot.phase)
        assertEquals("codex_live_usage_limit", f.session.snapshot.lastFailureCode)
        assertEquals(0, f.media.captureStarts)
        assertEquals(1, f.gateway.starts.get())
        assertEquals(1, f.gateway.stops.get())
    }

    @Test fun nativeTranscriptIsDisplayOnlyAndPeerTranscriptIsIgnored() = Fixture().use { f ->
        f.start()
        f.gateway.callbacks.onStarted()
        f.media.listener.onOpen()
        f.drain()
        f.gateway.callbacks.onTranscript("user", "Open the app", true)
        f.media.listener.onEvent("""{"type":"delegation.created","item":{"id":"duplicate"}}""")
        f.session.refreshContext()
        f.drain()
        assertEquals(listOf("Open the app"), f.transcripts.toList())
        assertEquals(1, f.gateway.starts.get())
        assertTrue(f.media.sent.isEmpty())
    }

    @Test fun transcriptFeedbackKeepsUserBargeInWhileAssistantFinalArrives() = Fixture().use { f ->
        f.start()
        f.gateway.callbacks.onStarted()
        f.media.listener.onOpen()
        f.drain()
        f.gateway.callbacks.onTranscript("assistant", "Answer", false)
        f.drain()
        assertEquals(LiveVoicePhase.HANS_SPEAKING, f.session.snapshot.phase)
        f.gateway.callbacks.onTranscript("user", "Wait", false)
        f.gateway.callbacks.onTranscript("assistant", "Answer.", true)
        f.drain()
        assertEquals(LiveVoicePhase.USER_SPEAKING, f.session.snapshot.phase)
        f.gateway.callbacks.onTranscript("user", "Wait.", true)
        f.drain()
        assertEquals(LiveVoicePhase.LISTENING, f.session.snapshot.phase)
    }

    @Test fun dictationRequiresFirstPhysicalFrameInAdditionToNativeAndMediaReadiness() =
        Fixture(LiveVoiceEntryPoint.DICTATION).use { f ->
            f.start()
            f.gateway.callbacks.onStarted()
            f.media.listener.onOpen()
            f.drain()
            assertEquals(0, f.media.captureStarts)
            assertEquals(LiveVoiceEntryPoint.DICTATION, f.session.snapshot.entryPoint)
            assertTrue(f.media.monitoring)
            f.media.listener.onInputCaptureStarted()
            f.drain()
            assertEquals(1, f.media.captureStarts)
            assertEquals(LiveVoicePhase.LISTENING, f.session.snapshot.phase)
            f.media.listener.onInputCaptureStarted()
            f.gateway.callbacks.onStarted()
            f.media.listener.onOpen()
            f.drain()
            assertEquals(1, f.media.captureStarts)
        }

    @Test fun dictationShowsEarlyPhysicalListeningWithoutTelephoneGreetingOrEarlyTransmission() =
        Fixture(LiveVoiceEntryPoint.DICTATION).use { f ->
            f.start()
            assertTrue(f.media.setup.instructions.contains("Do not greet"))
            assertTrue(f.media.setup.instructions.contains("chat dictation entry"))
            f.media.listener.onInputCaptureStarted()
            f.drain()
            assertEquals(LiveVoicePhase.LISTENING, f.session.snapshot.phase)
            assertEquals(0, f.media.captureStarts)
            f.gateway.callbacks.onStarted()
            f.drain()
            assertEquals(0, f.media.captureStarts)
            f.media.listener.onOpen()
            f.drain()
            assertEquals(1, f.media.captureStarts)
        }

    @Test fun dictationNativeTranscriptRemainsDisplayOnlyWithNoSeparateRequest() =
        Fixture(LiveVoiceEntryPoint.DICTATION).use { f ->
            f.activateDictation()
            f.gateway.callbacks.onTranscript("user", "Open ", false)
            f.gateway.callbacks.onTranscript("user", "WhatsApp", false)
            f.gateway.callbacks.onTranscript("user", "Open WhatsApp", true)
            f.media.listener.onEvent("""{"type":"delegation.created","item":{"id":"duplicate"}}""")
            f.media.listener.onEvent("""{"type":"input_transcript.added","item":{"text":"Open WhatsApp"}}""")
            f.session.refreshContext()
            f.drain()
            assertEquals(listOf("Open ", "Open WhatsApp", "Open WhatsApp"), f.transcripts.toList())
            assertEquals(1, f.gateway.starts.get())
            assertEquals(0, f.gateway.stops.get())
            assertTrue(f.media.sent.isEmpty())
        }

    @Test fun dictationWithoutAnyHandoffClosesAfterSixtySecondsWithoutResubmitting() =
        Fixture(LiveVoiceEntryPoint.DICTATION).use { f ->
            f.activateDictation()
            f.tick(59_999)
            assertEquals(0, f.gateway.stops.get())
            f.tick(60_000)
            assertEquals(LiveVoicePhase.STOPPED, f.session.snapshot.phase)
            assertTrue(f.media.closed)
            assertTrue(f.media.muted)
            assertEquals(1, f.media.closeReceipts)
            assertEquals(1, f.gateway.starts.get())
            assertEquals(1, f.gateway.stops.get())
            assertTrue(f.media.sent.isEmpty())
        }

    @Test fun dictationWithMatchedRunningWorkHasNoSixtySecondCutoff() =
        Fixture(LiveVoiceEntryPoint.DICTATION).use { f ->
            f.activateDictation()
            f.gateway.callbacks.onWorkState(CodexTaskVoiceWorkState(1, "synthetic-running-turn"))
            f.gateway.callbacks.onHandoff()
            f.drain()
            f.audio(LiveVoiceAudioDirection.INPUT, false, 61_000)
            assertEquals(0, f.gateway.stops.get())
            assertFalse(f.media.closed)
            assertEquals(LiveVoicePhase.LISTENING, f.session.snapshot.phase)
            assertTrue(f.media.sent.isEmpty())
        }

    @Test fun dictationTerminalCutsInputImmediatelyAndTimerClosesWithNoMoreEvents() =
        Fixture(LiveVoiceEntryPoint.DICTATION).use { f ->
            f.activateDictation()
            f.beginWork()
            f.completeWork()
            assertEquals(1, f.media.inputFinishes)
            assertTrue(f.media.inputFinished)
            assertTrue(f.media.muted)
            assertTrue(f.session.snapshot.inputMuted)
            assertFalse(f.session.setInputMuted(false))
            assertFalse(f.session.setInputMuted(true))
            assertFalse(f.media.closed)
            assertEquals(0, f.gateway.stops.get())
            // A genuine scheduled callback fires. No transcript, audio or work event wakes it.
            f.tick(1_499)
            assertEquals(0, f.gateway.stops.get())
            f.tick(1_500)
            assertEquals(LiveVoicePhase.STOPPED, f.session.snapshot.phase)
            assertTrue(f.media.closed)
            assertEquals(1, f.media.closeReceipts)
            assertEquals(1, f.gateway.stops.get())
            assertTrue(f.media.sent.isEmpty())
            val records = requireNotNull(TaskVoiceLifecycleDiagnostics.snapshot()).records
            val completion = records.single { it.type == TaskVoiceLifecycleDiagnostics.Event.COMPLETION_MATCHED }
            val inputClosed = records.single { it.details.reason == TaskVoiceLifecycleDiagnostics.Reason.INPUT_CLOSED }
            val tailStarted = records.single { it.type == TaskVoiceLifecycleDiagnostics.Event.TAIL_STARTED }
            val tailClosed = records.single { it.type == TaskVoiceLifecycleDiagnostics.Event.TAIL_CLOSED }
            val sessionClosed = records.single { it.type == TaskVoiceLifecycleDiagnostics.Event.SESSION_CLOSED }
            assertTrue(completion.sequence < inputClosed.sequence)
            assertTrue(inputClosed.sequence < tailStarted.sequence)
            assertTrue(tailStarted.sequence < tailClosed.sequence)
            assertTrue(tailClosed.sequence < sessionClosed.sequence)
            assertEquals(TaskVoiceLifecycleDiagnostics.Reason.TAIL_NO_OUTPUT, tailClosed.details.reason)
        }

    @Test fun dictationWithoutOutputOnlySupportClosesImmediatelyAndDoesNotKeepListening() =
        Fixture(LiveVoiceEntryPoint.DICTATION).use { f ->
            f.activateDictation()
            f.media.canFinishInput = false
            f.beginWork()
            f.completeWork()
            assertEquals(1, f.media.inputFinishes)
            assertEquals(1, f.gateway.stops.get())
            assertTrue(f.media.closed)
            assertEquals(1, f.media.closeReceipts)
            assertFalse(f.session.setInputMuted(false))
            assertEquals(LiveVoicePhase.STOPPED, f.session.snapshot.phase)
        }

    @Test fun lateFinalsDuplicatesAndHandoffsCannotReopenDictationOrExtendItsTail() =
        Fixture(LiveVoiceEntryPoint.DICTATION).use { f ->
            f.activateDictation()
            f.beginWork()
            f.completeWork()
            f.tick(1_200)
            repeat(3) {
                f.gateway.callbacks.onItemStarted("same-answer", "assistant")
                f.gateway.callbacks.onTranscript("assistant", "late answer", true)
                f.gateway.callbacks.onTranscript("user", "delayed original input", false)
                f.gateway.callbacks.onTranscript("user", "delayed original input", true)
                f.gateway.callbacks.onHandoff()
            }
            f.gateway.callbacks.onWorkState(CodexTaskVoiceWorkState(3, "late-turn"))
            f.gateway.callbacks.onStarted()
            f.media.listener.onOpen()
            f.media.listener.onInputCaptureStarted()
            f.drain()
            assertEquals(1, f.media.captureStarts)
            assertEquals(1, f.media.inputFinishes)
            assertTrue(f.session.snapshot.inputMuted)
            assertFalse(f.session.setInputMuted(false))
            f.tick(1_499)
            assertEquals(0, f.gateway.stops.get())
            f.tick(1_500)
            assertEquals(1, f.gateway.stops.get())
            assertEquals(LiveVoicePhase.STOPPED, f.session.snapshot.phase)
        }

    @Test fun unrelatedTerminalDoesNotCloseAndAllObservedWorkMustSettleBeforeInputCutoff() =
        Fixture(LiveVoiceEntryPoint.DICTATION).use { f ->
            f.activateDictation()
            f.beginWork()
            f.gateway.callbacks.onWorkState(CodexTaskVoiceWorkState(2, "turn", terminal =
                CodexTaskVoiceTerminal("unrelated", CodexTaskVoiceWorkOutcome.COMPLETED)))
            f.drain()
            assertEquals(0, f.media.inputFinishes)
            f.gateway.callbacks.onHandoff()
            f.gateway.callbacks.onWorkState(CodexTaskVoiceWorkState(3, "second"))
            f.gateway.callbacks.onWorkState(CodexTaskVoiceWorkState(4, "second", terminal =
                CodexTaskVoiceTerminal("turn", CodexTaskVoiceWorkOutcome.COMPLETED)))
            f.drain()
            assertEquals(0, f.media.inputFinishes)
            f.gateway.callbacks.onWorkState(CodexTaskVoiceWorkState(5, pendingDispatch = true,
                terminal = CodexTaskVoiceTerminal("second", CodexTaskVoiceWorkOutcome.COMPLETED)))
            f.drain()
            assertEquals(0, f.media.inputFinishes)
            f.gateway.callbacks.onWorkState(CodexTaskVoiceWorkState(6))
            f.drain()
            assertEquals(1, f.media.inputFinishes)
            f.tick(1_500)
            assertEquals(1, f.gateway.stops.get())
        }

    @Test fun telephoneDoesNotInheritTaskDictationAutoCloseOrIrreversibleMute() = Fixture().use { f ->
        f.start()
        f.gateway.callbacks.onStarted()
        f.media.listener.onOpen()
        f.drain()
        f.beginWork()
        f.completeWork()
        f.tick(61_000)
        assertEquals(0, f.media.inputFinishes)
        assertEquals(0, f.gateway.stops.get())
        assertFalse(f.media.closed)
        assertTrue(f.session.setInputMuted(true))
        assertTrue(f.session.setInputMuted(false))
    }

    @Test fun dictationIgnoresStaleCallbacksAfterVerifiedClose() =
        Fixture(LiveVoiceEntryPoint.DICTATION).use { f ->
            f.start()
            f.media.listener.onInputCaptureStarted()
            f.drain()
            f.session.stop()
            f.drain()
            f.gateway.callbacks.onStarted()
            f.gateway.callbacks.onTranscript("user", "stale command", true)
            f.gateway.callbacks.onHandoff()
            f.media.listener.onOpen()
            f.media.listener.onInputCaptureStarted()
            f.drain()
            assertEquals(LiveVoicePhase.STOPPED, f.session.snapshot.phase)
            assertEquals(0, f.media.captureStarts)
            assertTrue(f.transcripts.isEmpty())
            assertEquals(1, f.gateway.starts.get())
            assertEquals(1, f.gateway.stops.get())
        }

    @Test fun startupPcmPrefixSurvivesReadinessAndCloseZeroesSubsequentFrames() {
        val buffer = StartupPcmBuffer()
        fun frame(value: Byte) = ByteBuffer.wrap(ByteArray(StartupPcmBuffer.FRAME_BYTES) { value })
        fun render(bytes: ByteBuffer, timestamp: Long) = buffer.render(bytes,
            StartupPcmBuffer.PCM_16_BIT, 1, StartupPcmBuffer.SAMPLE_RATE_HZ,
            StartupPcmBuffer.FRAME_BYTES, timestamp)
        val first = frame(9)
        render(first, 10)
        assertTrue(first.array().all { it == 0.toByte() })
        val second = frame(11)
        render(second, 20)
        assertTrue(second.array().all { it == 0.toByte() })
        assertEquals(20L, buffer.queuedMillis)
        buffer.setTransmissionEnabled(true)
        repeat(StartupPcmBuffer.ACTIVATION_FRAMES) { render(frame(0), 30L + it) }
        val replay = frame(0)
        assertEquals(10L, render(replay, 40))
        assertArrayEquals(ByteArray(StartupPcmBuffer.FRAME_BYTES) { 9 }, replay.array())
        assertEquals(20L, render(replay, 50))
        assertArrayEquals(ByteArray(StartupPcmBuffer.FRAME_BYTES) { 11 }, replay.array())
        buffer.close()
        assertEquals(0L, buffer.queuedMillis)
        val closed = frame(12)
        render(closed, 60)
        assertTrue(closed.array().all { it == 0.toByte() })
        assertFalse(buffer.setTransmissionEnabled(true))
    }

    private class Fixture(entryPoint: LiveVoiceEntryPoint = LiveVoiceEntryPoint.PHONE,
        instructions: String = "Synthetic native context") : AutoCloseable {
        val gateway = Gateway()
        val scheduler = VirtualScheduler()
        lateinit var media: Media
        val transcripts = CopyOnWriteArrayList<String>()
        val session = CodexLiveVoiceSession(gateway, { provider -> Media(provider).also { media = it } },
            LiveVoiceInstructionsProvider { instructions }, object : LiveVoiceObserver {
                override fun onUserTranscript(text: String, isFinal: Boolean) { transcripts += text }
            }, scheduler = scheduler, nanoTime = { scheduler.nowNanos }, entryPoint = entryPoint,
            activatedAtNanos = scheduler.nowNanos, mediaCloseTimeoutMillis = 0, nativeCloseTimeoutMillis = 0)
        fun start() { session.start(); drain(); assertEquals(1, gateway.starts.get()) }
        fun activateDictation() {
            start()
            gateway.callbacks.onStarted()
            media.listener.onOpen()
            media.listener.onInputCaptureStarted()
            drain()
            assertEquals(1, media.captureStarts)
        }
        fun beginWork() {
            gateway.callbacks.onHandoff()
            gateway.callbacks.onWorkState(CodexTaskVoiceWorkState(1, "turn"))
            drain()
        }
        fun completeWork() {
            gateway.callbacks.onWorkState(CodexTaskVoiceWorkState(2,
                terminal = CodexTaskVoiceTerminal("turn", CodexTaskVoiceWorkOutcome.COMPLETED)))
            drain()
        }
        fun tick(millis: Long) = scheduler.advanceTo(millis)
        fun audio(direction: LiveVoiceAudioDirection, active: Boolean, millis: Long) {
            tick(millis)
            media.listener.onAudioActivity(LiveVoiceAudioActivity(direction, active, scheduler.nowNanos))
            drain()
        }
        fun drain() = scheduler.runCurrent()
        override fun close() { session.close(); drain(); scheduler.shutdownNow() }
    }

    private class Gateway : CodexRealtimeGateway {
        lateinit var callbacks: CodexRealtimeCallbacks
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        var confirmClose = true
        override fun start(offerSdp: String, prompt: String, voice: String?, callbacks: CodexRealtimeCallbacks): CodexRealtimeCall {
            this.callbacks = callbacks
            starts.incrementAndGet()
            callbacks.onRemoteSdp("v=synthetic-answer")
            val stopped = AtomicBoolean(false)
            return CodexRealtimeCall {
                if (stopped.compareAndSet(false, true)) {
                    stops.incrementAndGet()
                    if (confirmClose) callbacks.onCloseConfirmed()
                    callbacks.onClosed()
                }
            }
        }
        override fun interruptCurrentTurn(): Boolean = error("Voice controls must not replay or cancel a task")
    }

    private class Media(private val provider: LiveSessionProvider) : LiveVoiceTransport {
        lateinit var listener: LiveVoiceTransport.Listener
        lateinit var setup: LiveSessionSetup
        private var cancellation = LiveVoiceCancellation.NONE
        var captureStarts = 0
        var muted = false
        var monitoring = false
        var closed = false
        var closeReceipts = 0
        var inputFinishes = 0
        var inputFinished = false
        var canFinishInput = true
        var disposalConfirmed = true
        val sent = mutableListOf<String>()
        override fun connect(setup: LiveSessionSetup, listener: LiveVoiceTransport.Listener) {
            this.listener = listener
            this.setup = setup
            cancellation = provider.create(setup, "v=synthetic-offer", object : LiveSessionProvider.Callback {
                override fun onCreated(answer: LiveSessionAnswer) = Unit
                override fun onFailure(failure: LiveVoiceFailure) { listener.onClosed(failure) }
            })
        }
        override fun connect(credential: RealtimeEphemeralCredential, listener: LiveVoiceTransport.Listener) = error("API key path forbidden")
        override fun confirmSessionStarted(): Boolean { captureStarts++; return true }
        override fun sendUtf8(event: String): Boolean { sent += event; return true }
        override fun setInputAudioEnabled(enabled: Boolean): Boolean = !enabled || !inputFinished
        override fun setUserInputMuted(muted: Boolean): Boolean {
            if (!muted && inputFinished) return false
            this.muted = muted
            return true
        }
        override fun finishInputForOutputTail(): Boolean {
            inputFinishes++
            if (!canFinishInput) return false
            inputFinished = true
            muted = true
            return true
        }
        override fun clearOutputAudio(): Boolean = false
        override fun setAudioActivityMonitoringEnabled(enabled: Boolean): Boolean { monitoring = enabled; return true }
        override fun close() { closed = true; cancellation.cancel() }
        override fun closeAndAwait(timeoutMillis: Long): Boolean { closeReceipts++; close(); return disposalConfirmed }
        fun confirmDisposal() { disposalConfirmed = true; listener.onMediaDisposed() }
    }

    /** Runs actual one-shot callbacks, without wall-clock sleeps or synthetic media ticks. */
    private class VirtualScheduler : AbstractExecutorService(), ScheduledExecutorService {
        var nowNanos = ORIGIN
            private set
        private var nextOrder = 0L
        private var stopped = false
        private val queue = PriorityQueue<Task<*>>(compareBy({ it.dueNanos }, { it.order }))
        fun runCurrent() = advanceTo(TimeUnit.NANOSECONDS.toMillis(nowNanos - ORIGIN))
        fun advanceTo(millis: Long) {
            val target = ORIGIN + TimeUnit.MILLISECONDS.toNanos(millis)
            require(target >= nowNanos)
            var executed = 0
            while (queue.peek()?.let { it.dueNanos <= target } == true) {
                check(executed++ < 100_000) { "Session scheduler did not settle" }
                val task = queue.remove()
                nowNanos = task.dueNanos
                if (!task.isCancelled) {
                    task.run()
                    if (!task.isCancelled) task.get()
                }
            }
            nowNanos = target
        }
        override fun execute(command: Runnable) { schedule(command, 0, TimeUnit.NANOSECONDS) }
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
            schedule(Callable { command.run(); Unit }, delay, unit)
        override fun <V> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> {
            if (stopped) throw RejectedExecutionException("Session scheduler closed")
            return Task(nowNanos + unit.toNanos(delay.coerceAtLeast(0)), nextOrder++, callable).also(queue::add)
        }
        override fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit): ScheduledFuture<*> =
            throw UnsupportedOperationException("Task Voice must not poll")
        override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
            throw UnsupportedOperationException("Task Voice must not poll")
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true; queue.forEach { it.cancel(false) }; queue.clear()
            return mutableListOf()
        }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && queue.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
        private inner class Task<V>(val dueNanos: Long, val order: Long, callable: Callable<V>) :
            FutureTask<V>(callable), ScheduledFuture<V> {
            override fun getDelay(unit: TimeUnit) = unit.convert(dueNanos - nowNanos, TimeUnit.NANOSECONDS)
            override fun compareTo(other: Delayed) = getDelay(TimeUnit.NANOSECONDS).compareTo(other.getDelay(TimeUnit.NANOSECONDS))
        }
        companion object { val ORIGIN = TimeUnit.SECONDS.toNanos(100) }
    }
}
