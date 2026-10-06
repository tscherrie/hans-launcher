package ai.hans.standard.integration

import ai.hans.standard.voice.realtime.*
import ai.hans.standard.voice.tts.CodexReadAloudDelivery
import ai.hans.standard.voice.tts.CodexReadAloudDeliveryPhase
import ai.hans.standard.voice.tts.CodexReadAloudSessionPort
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real coordinator-to-consumer callbacks; no microphone, account, native runtime or network. */
class RealtimeCloseReuseIntegrationTest {
    @Test fun spontaneousCloseRetainsLeaseUntilRequestedReceiptThenProviderCanStartAgain() {
        for (reason in listOf("transport_closed", "error", null)) {
            val wire = Wire()
            val events = Events()
            val first = wire.provider(events)
            wire.ready()
            wire.closed(reason)
            assertEquals(reason, 1, events.closed)
            assertEquals(reason, 1, wire.requests("thread/realtime/stop").size)
            assertEquals(CodexRealtimeState.DRAINING, wire.diagnostics().state)
            assertFalse(first.closeAndAwait(0))

            val blocked = Answer()
            wire.provider(answer = blocked)
            assertEquals("codex_live_already_active", blocked.failures.single().code)
            assertEquals(1, wire.requests("thread/realtime/start").size)
            wire.ackStop()
            assertFalse(first.closeAndAwait(0)) // A queued stop is not completed shutdown.
            wire.closed(reason) // Duplicate spontaneous close cannot duplicate callback/stop.
            assertEquals(1, wire.requests("thread/realtime/stop").size)
            assertEquals(1, events.closed)
            wire.closed("requested")
            assertTrue(first.closeAndAwait(0))
            assertEquals(1, events.closed)
            assertEquals(CodexRealtimeState.CLOSED, wire.diagnostics().state)

            val secondEvents = Events()
            val second = wire.provider(secondEvents)
            wire.ready()
            assertEquals(1, secondEvents.started)
            first.close() // An old consumer cannot cancel the next lease.
            wire.ack(wire.requests("thread/realtime/stop").single())
            assertEquals(CodexRealtimeState.ACTIVE, wire.diagnostics().state)
            assertEquals(1, wire.requests("thread/realtime/stop").size)
            second.close()
            wire.closed("requested")
            assertTrue(second.closeAndAwait(0))
            assertTrue(wire.sent.all { it.getString("method") in setOf("thread/realtime/start", "thread/realtime/stop") })
        }
    }

    @Test fun lateRequestedReceiptAfterDrainTimeoutReleasesOriginalConsumerWithoutRetry() {
        val wire = Wire()
        val first = wire.provider()
        wire.ready()
        wire.closed("transport_closed")
        wire.ackStop()
        wire.expireDrain()
        assertEquals(CodexRealtimeState.RECOVERY_REQUIRED, wire.diagnostics().state)
        assertFalse(first.closeAndAwait(0))
        val rejected = Answer()
        wire.provider(answer = rejected)
        assertEquals("codex_live_recovery_required", rejected.failures.single().code)
        assertEquals(1, wire.requests("thread/realtime/start").size)
        wire.closed("requested")
        assertTrue(first.closeAndAwait(0))
        wire.provider()
        assertEquals(2, wire.requests("thread/realtime/start").size)
    }

    @Test fun requestedCloseForOlderLeaseCannotCloseNextUnstoppedProvider() {
        val wire = Wire()
        val first = wire.provider()
        wire.ready()
        first.close()
        wire.closed("requested")
        assertTrue(first.closeAndAwait(0))
        val events = Events()
        val second = wire.provider(events)
        wire.ready()
        wire.closed("requested") // Thread-only duplicate: B has not requested any stop.
        assertEquals(CodexRealtimeState.ACTIVE, wire.diagnostics().state)
        assertEquals(0, events.closed)
        second.close()
        wire.closed("requested")
        assertTrue(second.closeAndAwait(0))
    }

    @Test fun readerDeliveryReleasesOnlyAfterPhysicalAndRequestedNativeCloseThenAllowsDictation() {
        for (advanceEpoch in listOf(false, true)) ReaderFixture().use { f ->
            f.prepare()
            f.wire.ready()
            f.drainReader()
            f.media.listener.onOpen()
            f.drainReader()
            f.wire.closed("transport_closed")
            f.drainReader()
            assertEquals(1, f.wire.requests("thread/realtime/stop").size)
            assertFalse(f.delivery.stopOutputAndAwait(60))
            f.wire.ackStop()
            assertFalse(f.delivery.stopOutputAndAwait(60))
            if (advanceEpoch) f.delivery.beginTurn(2) // Starting task voice revokes reader output.
            f.wire.closed("requested")
            f.drainReader()
            assertFalse(f.delivery.stopOutputAndAwait(60)) // Physical disposal still unproven.
            f.media.disposed = true
            assertTrue(f.delivery.stopOutputAndAwait(2_000))
            val events = Events()
            val next = f.wire.provider(events)
            f.wire.ready()
            assertEquals(1, events.started)
            assertEquals(2, f.wire.requests("thread/realtime/start").size)
            next.close()
            f.wire.closed("requested")
            assertTrue(next.closeAndAwait(0))
        }
    }

    private class Wire : CodexRealtimeGateway {
        val sent = CopyOnWriteArrayList<JSONObject>()
        private var sequence = 0
        private val drainTasks = mutableListOf<() -> Unit>()
        private val coordinator = CodexRealtimeCoordinator(
            send = { _, text -> sent += JSONObject(text); true },
            newId = { "test-${++sequence}" },
            scheduleDrainDeadline = { task ->
                drainTasks += task
                object : SetupDispatchDeadline { override fun cancel() = Unit }
            },
        )

        override fun start(offerSdp: String, prompt: String, voice: String?, callbacks: CodexRealtimeCallbacks) =
            start(offerSdp, prompt, voice, CodexRealtimeOptions(), callbacks)

        @Synchronized override fun start(offerSdp: String, prompt: String, voice: String?,
            options: CodexRealtimeOptions, callbacks: CodexRealtimeCallbacks): CodexRealtimeCall? {
            val lease = coordinator.start(1, "thread", offerSdp, prompt, voice, callbacks, options) ?: return null
            return CodexRealtimeCall { synchronized(this) { coordinator.stop(lease) } }
        }

        override fun interruptCurrentTurn(): Boolean = error("No work interruption is allowed")
        @Synchronized fun diagnostics() = coordinator.diagnostics()
        @Synchronized fun expireDrain() = drainTasks.last().invoke()
        fun requests(method: String) = sent.filter { it.getString("method") == method }
        @Synchronized fun ack(request: JSONObject) {
            coordinator.onFrame(1, JSONObject().put("id", request.getString("id")).put("result", JSONObject()).toString())
        }
        fun ackStop() = ack(requests("thread/realtime/stop").last())
        @Synchronized fun event(method: String, params: JSONObject = JSONObject()) {
            assertTrue(coordinator.onFrame(1, JSONObject().put("method", "thread/realtime/$method")
                .put("params", params.put("threadId", "thread")).toString()))
        }
        fun closed(reason: String?) = event("closed", JSONObject().put("reason", reason ?: JSONObject.NULL))
        fun ready() {
            val request = requests("thread/realtime/start").last()
            ack(request)
            event("started", JSONObject().put("version", "v3").put("realtimeSessionId",
                request.getJSONObject("params").getString("realtimeSessionId")))
            event("sdp", JSONObject().put("sdp", "v=0\r\nanswer"))
        }
        fun provider(events: Events = Events(), answer: Answer = Answer()) =
            CodexLiveSessionProvider(this, events).also {
                it.create(LiveSessionSetup(LiveVoiceSessionConfig(CodexLiveVoiceSession.MODEL, "cove"),
                    "Native instructions"), "v=0\r\noffer", answer)
            }
    }

    private class Events : CodexLiveSessionProvider.Events {
        var started = 0
        var closed = 0
        override fun onStarted() { started++ }
        override fun onTranscript(role: String, text: String, isFinal: Boolean) = Unit
        override fun onClosed() { closed++ }
    }

    private class Answer : LiveSessionProvider.Callback {
        val failures = mutableListOf<LiveVoiceFailure>()
        override fun onCreated(answer: LiveSessionAnswer) = Unit
        override fun onFailure(failure: LiveVoiceFailure) { failures += failure }
    }

    private class ReaderFixture : AutoCloseable {
        val wire = Wire()
        private val readerWorker = Executors.newSingleThreadScheduledExecutor()
        private val deliveryWorker = Executors.newSingleThreadExecutor()
        lateinit var media: Media
        lateinit var reader: CodexReadAloudSession
        val delivery = CodexReadAloudDelivery(sessionFactory = { _, observer ->
            reader = CodexReadAloudSession(wire,
                transportFactory = { Media(it).also { value -> media = value } },
                observer = CodexReadAloudSession.Observer { observer(CodexReadAloudDeliveryPhase.valueOf(it.phase.name)) },
                scheduler = readerWorker)
            object : CodexReadAloudSessionPort {
                override fun prepare() = reader.prepare()
                override fun speak(text: String) = reader.speak(text)
                override fun stop() = reader.stop()
                override fun stopAndAwait(timeoutMillis: Long) = reader.stopAndAwait(timeoutMillis)
                override fun close() = reader.close()
            }
        }, serialExecutor = deliveryWorker, stopBarrierMillis = 20)

        fun prepare() {
            delivery.beginTurn(1)
            delivery.configure(true, false, 1)
            delivery.prepare(1)
            deliveryWorker.submit {}.get(2, TimeUnit.SECONDS)
            drainReader()
        }
        fun drainReader() { repeat(6) { readerWorker.submit {}.get(2, TimeUnit.SECONDS) } }
        override fun close() {
            if (::media.isInitialized) media.disposed = true
            if (::reader.isInitialized) {
                reader.stop()
                drainReader()
                if (wire.requests("thread/realtime/stop").isNotEmpty()) wire.closed("requested")
                reader.stopAndAwait(1_000)
                reader.close()
                drainReader()
            }
            delivery.close()
            deliveryWorker.submit {}.get(2, TimeUnit.SECONDS)
            readerWorker.shutdownNow()
            deliveryWorker.shutdownNow()
        }
    }

    private class Media(private val provider: LiveSessionProvider) : LiveVoiceTransport {
        lateinit var listener: LiveVoiceTransport.Listener
        @Volatile var disposed = false
        override fun connect(setup: LiveSessionSetup, listener: LiveVoiceTransport.Listener) {
            this.listener = listener
            provider.create(setup, "v=0\r\noffer", Answer())
        }
        override fun connect(credential: RealtimeEphemeralCredential, listener: LiveVoiceTransport.Listener) =
            error("No credentials or network are allowed")
        override fun sendUtf8(event: String): Boolean = error("No second dispatch")
        override fun setInputAudioEnabled(enabled: Boolean) = !enabled
        override fun setUserInputMuted(muted: Boolean) = muted
        override fun confirmSessionStarted() = true
        override fun setAudioActivityMonitoringEnabled(enabled: Boolean) = true
        override fun clearOutputAudio() = true
        override fun close() = Unit
        override fun closeAndAwait(timeoutMillis: Long) = disposed
    }
}
