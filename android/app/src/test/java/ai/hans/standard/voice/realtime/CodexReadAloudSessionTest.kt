package ai.hans.standard.voice.realtime

import ai.hans.standard.integration.CodexRealtimeCall
import ai.hans.standard.integration.CodexRealtimeCallbacks
import ai.hans.standard.integration.CodexRealtimeGateway
import ai.hans.standard.integration.CodexRealtimeIssue
import ai.hans.standard.integration.CodexRealtimeOptions
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic, microphone-free lifecycle tests. No provider requests or audible output. */
class CodexReadAloudSessionTest {
    @Test fun revokedGuardCancelsQueuedPreparationWithoutConstructingNativeOrMedia() = Fixture().use { f ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var allowed = true
        f.scheduler.execute { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertTrue(f.session.prepareGuarded { allowed })
        allowed = false
        release.countDown()
        f.awaitPhase(CodexReadAloudSession.Phase.STOPPED)
        assertTrue(f.session.snapshot.releaseConfirmed)
        assertFalse(f.session.snapshot.completedNaturally)
        assertTrue(f.transports.isEmpty())
        assertTrue(f.gateway.callbacks.isEmpty())
    }

    @Test fun guardRevokedDuringMediaPreparationPreventsNativeStart() {
        var allowed = true
        Fixture(afterMediaConnect = { allowed = false }).use { f ->
            assertTrue(f.session.prepareGuarded { allowed })
            f.awaitPhase(CodexReadAloudSession.Phase.STOPPED)
            assertTrue(f.gateway.callbacks.isEmpty())
            assertTrue(f.gateway.calls.isEmpty())
            assertTrue(f.session.snapshot.releaseConfirmed)
            assertFalse(f.session.snapshot.completedNaturally)
        }
    }

    @Test fun guardRevokedBeforeAppendNeverSpeaksAndNeverCountsAsNaturalCompletion() = Fixture().use { f ->
        f.start()
        var allowed = true
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.scheduler.execute { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertTrue(f.session.speakGuarded("Private notification") { allowed })
        allowed = false
        release.countDown()
        f.awaitPhase(CodexReadAloudSession.Phase.STOPPED)
        assertTrue(f.gateway.call.payloads.isEmpty())
        assertFalse(f.session.snapshot.completedNaturally)
    }

    @Test fun observedOutputInterruptedByOwnerIsNotNaturalEvenAfterPositiveRelease() = Fixture().use { f ->
        f.start()
        assertTrue(f.session.speak("Notification"))
        f.drain()
        f.audio(true)
        assertTrue(f.session.snapshot.outputObserved)
        assertTrue(f.session.stopAndAwait(1_000))
        assertTrue(f.session.snapshot.releaseConfirmed)
        assertFalse(f.session.snapshot.completedNaturally)
    }

    @Test fun latePositiveMediaAndNativeReceiptsReleaseFailedBarrierWithoutClaimingSpoken() {
        listOf(true, false).forEach { mediaFirst -> Fixture(closeTimeoutMillis = 60).use { f ->
            f.start()
            assertTrue(f.session.speak("Notification"))
            f.drain()
            f.audio(true)
            f.gateway.call.confirmClose = false
            f.media.disposed = false
            f.session.stop()
            f.awaitPhase(CodexReadAloudSession.Phase.FAILED)
            assertFalse(f.session.snapshot.releaseConfirmed)
            if (mediaFirst) f.media.confirmDisposal() else f.gateway.callbacks.single().onCloseConfirmed()
            f.drain()
            assertFalse(f.session.snapshot.releaseConfirmed)
            if (mediaFirst) f.gateway.callbacks.single().onCloseConfirmed() else f.media.confirmDisposal()
            f.drain()
            assertTrue(f.session.snapshot.releaseConfirmed)
            assertEquals(CodexReadAloudSession.Phase.FAILED, f.session.snapshot.phase)
            assertFalse(f.session.snapshot.completedNaturally)
            assertTrue(f.session.stopAndAwait(50))
            val releases = f.snapshots.count { it.releaseConfirmed }
            f.media.listener.onMediaDisposed()
            f.gateway.callbacks.single().onCloseConfirmed()
            f.drain()
            assertEquals(releases, f.snapshots.count { it.releaseConfirmed })
        } }
    }

    @Test fun onePayloadWaitsForNativeSdpMediaAndOutputConfirmationInEveryOrder() {
        listOf(listOf(0, 1, 2), listOf(2, 0, 1), listOf(1, 2, 0)).forEach { order -> Fixture().use { f ->
            assertTrue(f.session.speak("Already visible answer"))
            assertFalse(f.session.speak("Duplicate"))
            f.drain()
            val callbacks = f.gateway.callbacks.single()
            order.forEachIndexed { index, readiness ->
                when (readiness) {
                    0 -> callbacks.onStarted()
                    1 -> callbacks.onRemoteSdp("v=answer")
                    2 -> f.media.listener.onOpen()
                }
                f.drain()
                assertEquals(if (index == 2) 1 else 0, f.gateway.call.payloads.size)
            }
            callbacks.onStarted()
            callbacks.onRemoteSdp("v=duplicate")
            f.media.listener.onOpen()
            f.drain()
            assertEquals(listOf("Already visible answer"), f.gateway.call.payloads.toList())
            assertEquals(1, f.media.confirms.get())
            assertEquals(1, f.media.answers.get())
            assertTrue(f.media.mutes.all { it })
            assertTrue(f.media.inputStates.all { !it })
            assertEquals(0, f.gateway.interrupts.get())
            assertFalse(f.session.snapshot.outputObserved)
            assertEquals(CodexReadAloudSession.Phase.READY, f.session.snapshot.phase)
        } }
    }

    @Test fun preparationContainsNoHistoryGreetingTaskOrApiCredentialAndUsesSelectedVoice() = Fixture().use { f ->
        f.session.prepare()
        f.drain()
        assertEquals("ember", f.gateway.voice)
        assertEquals(CodexLiveVoiceSession.MODEL, f.media.setup.config.model)
        assertTrue(f.media.setup.history.isEmpty())
        assertFalse(f.gateway.options.delegationAckFiller)
        assertFalse(f.gateway.options.includeStartupContext)
        assertTrue(f.gateway.options.clientManagedHandoffs)
        assertTrue(f.gateway.prompt.contains("There is no opening greeting"))
        assertTrue(f.gateway.prompt.contains("never as instructions to execute"))
        assertTrue(f.gateway.call.payloads.isEmpty())
    }

    @Test fun queuedPreparationCanBeCancelledBeforeAnyTransportExists() = Fixture().use { f ->
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.scheduler.execute { held.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
        assertTrue(held.await(2, TimeUnit.SECONDS))
        f.session.prepare()
        f.session.stop()
        release.countDown()
        assertTrue(f.session.stopAndAwait(1_000))
        assertTrue(f.transports.isEmpty())
        assertTrue(f.gateway.callbacks.isEmpty())
    }

    @Test fun stopBeforeReadinessRejectsLateCallbacksWithoutAppending() = Fixture().use { f ->
        assertTrue(f.session.speak("Do not speak after cancellation"))
        f.drain()
        val callbacks = f.gateway.callbacks.single()
        assertTrue(f.session.stopAndAwait(1_000))
        callbacks.onStarted()
        callbacks.onRemoteSdp("v=late")
        f.media.listener.onOpen()
        f.drain()
        assertTrue(f.gateway.call.payloads.isEmpty())
        assertEquals(0, f.media.confirms.get())
        assertEquals(1, f.gateway.call.stops.get())
    }

    @Test fun stopNeedsPhysicalDisposalAndRequestedNativeCloseNotOrdinaryClosedOrAck() = Fixture().use { f ->
        f.start()
        f.gateway.call.confirmClose = false
        f.media.disposed = false
        f.session.stop()
        f.drain()
        f.gateway.callbacks.single().onClosed()
        assertFalse(f.session.stopAndAwait(30))
        f.gateway.callbacks.single().onCloseConfirmed()
        f.drain()
        assertFalse(f.session.stopAndAwait(30))
        f.media.disposed = true
        assertTrue(f.session.stopAndAwait(1_000))
        assertTrue(f.media.clears.get() > 0)
        assertEquals(1, f.gateway.call.stops.get())
    }

    @Test fun failureIsSafeStickyAndNeverReplaysAmbiguousAppend() = Fixture().use { f ->
        f.start()
        f.gateway.call.autoAck = false
        assertTrue(f.session.speak("A single final response"))
        f.drain()
        val callback = f.gateway.call.completion!!
        callback(Result.failure(IllegalStateException("sensitive upstream material")))
        f.awaitPhase(CodexReadAloudSession.Phase.FAILED)
        callback(Result.success(Unit))
        f.drain()
        assertEquals("codex_read_aloud_append_failed", f.session.snapshot.failureCode)
        assertFalse(f.session.speak("Do not retry"))
        f.session.prepare()
        f.drain()
        assertEquals(1, f.gateway.callbacks.size)
        assertEquals(1, f.gateway.call.payloads.size)
        assertTrue(f.session.stopAndAwait(1_000))
    }

    @Test fun appendRefusalFailsWithoutRetryAndAckDoesNotProveOutput() = Fixture().use { f ->
        f.start()
        f.gateway.call.acceptAppend = false
        assertTrue(f.session.speak("Answer"))
        f.awaitPhase(CodexReadAloudSession.Phase.FAILED)
        assertEquals("codex_read_aloud_append_unconfirmed", f.session.snapshot.failureCode)
        assertFalse(f.session.snapshot.outputObserved)
        assertEquals(1, f.gateway.call.payloads.size)
    }

    @Test fun normalPreparationExpiryCanBeFollowedByFreshSpeechAndOldEventsCannotAffectIt() =
        Fixture(prepareExpiryMillis = 70).use { f ->
            f.session.prepare()
            f.drain()
            val oldCallbacks = f.gateway.callbacks.single()
            val oldMedia = f.media
            f.awaitPhase(CodexReadAloudSession.Phase.STOPPED)
            assertTrue(f.session.speak("A fresh final answer"))
            f.drain()
            oldCallbacks.onCloseConfirmed()
            oldCallbacks.onError(CodexRealtimeIssue.AUTHENTICATION)
            oldMedia.listener.onClosed(LiveVoiceFailure("old_media_failure", false))
            oldMedia.listener.onMediaDisposed()
            f.ready()
            assertEquals(CodexReadAloudSession.Phase.READY, f.session.snapshot.phase)
            assertFalse(f.session.snapshot.releaseConfirmed)
            assertEquals(listOf("A fresh final answer"), f.gateway.call.payloads.toList())
            assertEquals(2, f.gateway.callbacks.size)
        }

    @Test fun confirmOutputFailureCannotAppendOrEnableInput() = Fixture().use { f ->
        assertTrue(f.session.speak("Answer"))
        f.drain()
        f.media.confirmResult = false
        f.ready()
        f.awaitPhase(CodexReadAloudSession.Phase.FAILED)
        assertTrue(f.gateway.call.payloads.isEmpty())
        assertTrue(f.media.inputStates.all { !it })
    }

    @Test fun nativeErrorAndMissingAudioHaveBoundedSafeFailures() {
        Fixture().use { f ->
            f.start()
            f.gateway.callbacks.single().onError(CodexRealtimeIssue.USAGE_LIMIT)
            f.awaitPhase(CodexReadAloudSession.Phase.FAILED)
            assertEquals("codex_read_aloud_usage_limit", f.session.snapshot.failureCode)
        }
        Fixture(firstOutputTimeoutMillis = 65).use { f ->
            f.start()
            assertTrue(f.session.speak("Text ACK alone is not audio"))
            f.awaitPhase(CodexReadAloudSession.Phase.FAILED)
            assertEquals("codex_read_aloud_output_timeout", f.session.snapshot.failureCode)
            assertFalse(f.session.snapshot.outputObserved)
        }
    }

    @Test fun onlyExplicitStartRejectionCanReleaseMissingNativeLease() = Fixture().use { f ->
        f.gateway.returnNull = true
        f.session.prepare()
        f.drain()
        f.awaitPhase(CodexReadAloudSession.Phase.FAILED)
        assertFalse(f.session.stopAndAwait(30))
        f.gateway.callbacks.single().onStartRejected()
        assertTrue(f.session.stopAndAwait(1_000))
        assertFalse(f.session.speak("No automatic retry after rejected startup"))
    }

    @Test fun lateRejectionAfterStartedCannotPretendAdmittedNativeLeaseWasAbsent() = Fixture().use { f ->
        f.start()
        f.gateway.call.confirmClose = false
        f.gateway.callbacks.single().onStartRejected()
        f.awaitPhase(CodexReadAloudSession.Phase.FAILED)
        assertFalse(f.session.stopAndAwait(30))
        f.gateway.callbacks.single().onCloseConfirmed()
        assertTrue(f.session.stopAndAwait(1_000))
    }

    @Test fun cancelledPreparedCallUsesNoLeaseReceiptWithoutReportingFailure() = Fixture().use { f ->
        f.session.prepare()
        f.drain()
        f.gateway.call.rejectOnStop = true
        assertTrue(f.session.stopAndAwait(1_000))
        assertEquals(CodexReadAloudSession.Phase.STOPPED, f.session.snapshot.phase)
        assertEquals(null, f.session.snapshot.failureCode)
        assertTrue(f.session.speak("Fresh answer after normal cancellation"))
    }

    @Test fun synchronousNoLeaseRejectionPreservesQuotaClassification() = Fixture().use { f ->
        f.gateway.startIssue = CodexRealtimeIssue.USAGE_LIMIT
        f.session.prepare()
        f.awaitPhase(CodexReadAloudSession.Phase.FAILED)
        assertEquals("codex_read_aloud_usage_limit", f.session.snapshot.failureCode)
        assertTrue(f.session.stopAndAwait(1_000))
    }

    @Test fun assistantFinalNeedsObservedOutputAndContinuousReliableQuiet() = Fixture().use { f ->
        f.start()
        assertTrue(f.session.speak("Answer"))
        f.drain()
        f.gateway.callbacks.single().onTranscript("assistant", "Answer", true)
        f.audio(false)
        f.drain()
        assertFalse(f.session.snapshot.outputObserved)
        f.audio(true)
        f.drain()
        assertEquals(CodexReadAloudSession.Phase.SPEAKING, f.session.snapshot.phase)
        f.audio(false)
        f.awaitPhase(CodexReadAloudSession.Phase.STOPPED)
        assertTrue(f.session.snapshot.outputObserved)
        assertTrue(f.session.snapshot.releaseConfirmed)
        assertTrue(f.session.snapshot.completedNaturally)
        assertEquals(1, f.gateway.call.stops.get())
    }

    @Test fun absentAssistantFinalUsesLongerQuietTailAndNewOutputRestartsIt() = Fixture().use { f ->
        f.start()
        assertTrue(f.session.speak("Answer"))
        f.drain()
        f.audio(true)
        f.audio(false)
        Thread.sleep(45)
        assertEquals(CodexReadAloudSession.Phase.SPEAKING, f.session.snapshot.phase)
        f.audio(true)
        f.audio(false)
        f.awaitPhase(CodexReadAloudSession.Phase.STOPPED)
    }

    @Test fun unreliableActivityCannotCountAsSilenceAndInputCannotProveOutput() = Fixture().use { f ->
        f.start()
        assertTrue(f.session.speak("Answer"))
        f.drain()
        f.media.listener.onAudioActivity(LiveVoiceAudioActivity(LiveVoiceAudioDirection.INPUT, true, 1))
        f.drain()
        assertFalse(f.session.snapshot.outputObserved)
        f.audio(true)
        f.audio(false, reliable = false)
        f.gateway.callbacks.single().onTranscript("assistant", "Answer", true)
        f.drain()
        Thread.sleep(100)
        assertEquals(CodexReadAloudSession.Phase.SPEAKING, f.session.snapshot.phase)
        f.audio(false)
        f.awaitPhase(CodexReadAloudSession.Phase.STOPPED)
    }

    @Test fun connectionAndWholeSpeechHaveIndependentTimeouts() {
        Fixture(connectTimeoutMillis = 55).use { f ->
            f.session.prepare()
            f.awaitPhase(CodexReadAloudSession.Phase.FAILED)
            assertEquals("codex_read_aloud_connect_timeout", f.session.snapshot.failureCode)
        }
        Fixture(totalTimeoutMillis = 90).use { f ->
            f.start()
            assertTrue(f.session.speak("Never-ending output"))
            f.drain()
            f.audio(true)
            f.awaitPhase(CodexReadAloudSession.Phase.FAILED)
            assertEquals("codex_read_aloud_total_timeout", f.session.snapshot.failureCode)
        }
    }

    @Test fun invalidTextAndClosedSessionNeverAllocateMedia() = Fixture().use { f ->
        assertFalse(f.session.speak(" "))
        assertFalse(f.session.speak("x".repeat(CodexReadAloudSession.MAX_TEXT_CHARACTERS + 1)))
        f.session.close()
        assertFalse(f.session.speak("After close"))
        f.session.prepare()
        f.drain()
        assertTrue(f.transports.isEmpty())
    }

    private class Fixture(
        connectTimeoutMillis: Long = 2_000,
        prepareExpiryMillis: Long = 2_000,
        firstOutputTimeoutMillis: Long = 2_000,
        totalTimeoutMillis: Long = 3_000,
        closeTimeoutMillis: Long = 1_000,
        afterMediaConnect: () -> Unit = {},
    ) : AutoCloseable {
        val scheduler = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
        val gateway = Gateway()
        val transports = CopyOnWriteArrayList<Media>()
        val snapshots = CopyOnWriteArrayList<CodexReadAloudSession.Snapshot>()
        val media get() = transports.last()
        val session = CodexReadAloudSession(gateway,
            transportFactory = { provider -> Media(provider, afterMediaConnect).also(transports::add) },
            voiceSelectionProvider = LiveVoiceVoiceSelectionProvider { CodexLiveVoiceVoiceResolver.resolve("ember") },
            observer = CodexReadAloudSession.Observer { snapshots += it },
            config = CodexReadAloudSession.Config(connectTimeoutMillis, prepareExpiryMillis,
                firstOutputTimeoutMillis, totalTimeoutMillis, closeTimeoutMillis = closeTimeoutMillis,
                finalQuietMillis = 30, fallbackQuietMillis = 130, activityGapMillis = 500),
            scheduler = scheduler)
        fun drain() { repeat(6) { scheduler.submit {}.get(2, TimeUnit.SECONDS) } }
        fun start() { session.prepare(); drain(); ready() }
        fun ready() {
            gateway.callbacks.last().onStarted()
            gateway.callbacks.last().onRemoteSdp("v=answer")
            media.listener.onOpen()
            drain()
        }
        fun audio(active: Boolean, reliable: Boolean = true) {
            media.listener.onAudioActivity(LiveVoiceAudioActivity(LiveVoiceAudioDirection.OUTPUT,
                active, System.nanoTime(), reliable))
            drain()
        }
        fun awaitPhase(phase: CodexReadAloudSession.Phase) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (session.snapshot.phase != phase && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(phase, session.snapshot.phase)
            drain()
        }
        override fun close() {
            gateway.calls.forEach { it.confirmClose = true }
            transports.forEach { it.disposed = true }
            gateway.callbacks.forEach { it.onCloseConfirmed() }
            session.stopAndAwait(1_000)
            session.close()
            drain()
            scheduler.shutdownNow()
        }
    }

    private class Gateway : CodexRealtimeGateway {
        val callbacks = CopyOnWriteArrayList<CodexRealtimeCallbacks>()
        val calls = CopyOnWriteArrayList<Call>()
        val call get() = calls.last()
        val interrupts = AtomicInteger()
        var voice: String? = null
        var prompt = ""
        var options = CodexRealtimeOptions()
        var returnNull = false
        var startIssue: CodexRealtimeIssue? = null
        override fun start(offerSdp: String, prompt: String, voice: String?,
            callbacks: CodexRealtimeCallbacks): CodexRealtimeCall = error("must specify reader options")
        override fun start(offerSdp: String, prompt: String, voice: String?, options: CodexRealtimeOptions,
            callbacks: CodexRealtimeCallbacks): CodexRealtimeCall? {
            this.callbacks.add(callbacks)
            this.voice = voice
            this.prompt = prompt
            this.options = options
            startIssue?.let {
                callbacks.onStartRejected()
                callbacks.onError(it)
                return null
            }
            return Call(callbacks).also(calls::add).takeUnless { returnNull }
        }
        override fun interruptCurrentTurn(): Boolean { interrupts.incrementAndGet(); return true }
    }
    private class Call(val callbacks: CodexRealtimeCallbacks) : CodexRealtimeCall {
        val stops = AtomicInteger()
        val payloads = CopyOnWriteArrayList<String>()
        var confirmClose = true
        var autoAck = true
        var acceptAppend = true
        var rejectOnStop = false
        var completion: ((Result<Unit>) -> Unit)? = null
        override fun stop() {
            stops.incrementAndGet()
            if (rejectOnStop) callbacks.onStartRejected()
            else if (confirmClose) callbacks.onCloseConfirmed()
        }
        override fun appendSpeech(text: String, callback: (Result<Unit>) -> Unit): Boolean {
            payloads.add(text)
            completion = callback
            if (acceptAppend && autoAck) callback(Result.success(Unit))
            return acceptAppend
        }
    }
    private class Media(val provider: LiveSessionProvider, val afterConnect: () -> Unit = {}) : LiveVoiceTransport {
        lateinit var listener: LiveVoiceTransport.Listener
        lateinit var setup: LiveSessionSetup
        val mutes = CopyOnWriteArrayList<Boolean>()
        val inputStates = CopyOnWriteArrayList<Boolean>()
        val confirms = AtomicInteger()
        val answers = AtomicInteger()
        val clears = AtomicInteger()
        @Volatile var disposed = true
        var confirmResult = true
        override fun connect(setup: LiveSessionSetup, listener: LiveVoiceTransport.Listener) {
            this.setup = setup
            this.listener = listener
            provider.create(setup, "v=offer", object : LiveSessionProvider.Callback {
                override fun onCreated(answer: LiveSessionAnswer) { answers.incrementAndGet() }
                override fun onFailure(failure: LiveVoiceFailure) { listener.onClosed(failure) }
            })
            afterConnect()
        }
        override fun connect(credential: RealtimeEphemeralCredential, listener: LiveVoiceTransport.Listener) =
            error("credentials must not enter reader")
        override fun sendUtf8(event: String): Boolean = error("no client-side protocol or task dispatch")
        override fun setInputAudioEnabled(enabled: Boolean): Boolean { inputStates.add(enabled); return !enabled }
        override fun setUserInputMuted(muted: Boolean): Boolean { mutes.add(muted); return muted }
        override fun confirmSessionStarted(): Boolean { confirms.incrementAndGet(); return confirmResult }
        override fun setAudioActivityMonitoringEnabled(enabled: Boolean) = true
        override fun clearOutputAudio(): Boolean { clears.incrementAndGet(); return true }
        override fun close() = Unit
        override fun closeAndAwait(timeoutMillis: Long): Boolean = disposed
        fun confirmDisposal() { disposed = true; listener.onMediaDisposed() }
    }
}
