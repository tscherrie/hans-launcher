package ai.hans.standard.voice.realtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Quiet Android regression coverage: no microphone, playback, network, credentials or real work. */
@RunWith(AndroidJUnit4::class)
class LiveApiResultQueueInstrumentedTest {
    @Test
    fun completedResultPreemptsQuietContextWithoutBlockingTheNextLiveDelegation() {
        val transport = FakeTransport("priority")
        Fixture(listOf(transport)).use { fixture ->
            fixture.start(transport)
            fixture.accept(transport, "completed-task")
            transport.autoAcknowledge = false
            val beforeRefresh = transport.appends().size
            val context = "Updated local conversation facts. ".repeat(1_250)
            val contextChunks = OpenAiLiveProtocol.contentChunks(CONTEXT_PREFIX + context)
            assertTrue("Exercise a substantial serialized context backlog", contextChunks.size > 50)
            fixture.context.set(LiveVoiceSessionContext(context))
            fixture.session.refreshContext()
            fixture.barrier()
            val firstContext = transport.appends()[beforeRefresh]
            assertEquals("Routine context is quiet, not a behavioral interrupt", "session.thinking.append",
                firstContext.getString("type"))

            val result = "Synthetic verified result: " + "The requested item is present. ".repeat(28)
            val resultChunks = OpenAiLiveProtocol.contentChunks(result)
            assertTrue(resultChunks.size > 1)
            fixture.tasks.complete("completed-task", result)
            fixture.barrier()
            assertEquals(0, fixture.session.snapshot.pendingTaskCount)

            transport.acknowledge(firstContext, clientEventId = "wrong-client-event")
            transport.acknowledge(firstContext, type = "session.commentary.appended")
            transport.acknowledge(firstContext, type = "session.instructions.appended")
            fixture.barrier()
            assertEquals("Wrong ID or receipt kind must not advance the in-flight append",
                beforeRefresh + 1, transport.appends().size)

            transport.acknowledge(firstContext)
            fixture.barrier()
            for (index in resultChunks.indices) {
                val next = transport.appends().last()
                assertEquals("Completed output must precede the remaining context",
                    "session.commentary.append", next.getString("type"))
                assertEquals(resultChunks[index], next.getString("content"))
                assertEquals("completed-task", next.getString("delegation_id"))
                assertEquals(beforeRefresh + 2 + index, transport.appends().size)
                assertEquals("No context or result receipt is itself a new user request", 1, fixture.tasks.requests.size)

                if (index == 0) {
                    transport.acknowledge(next, clientEventId = "wrong-result-event")
                    fixture.barrier()
                    assertEquals(beforeRefresh + 2, transport.appends().size)
                }
                transport.acknowledge(next)
                fixture.barrier()
            }

            for (index in 1 until contextChunks.size) {
                val next = transport.appends().last()
                assertEquals("Context immediately after a result must not interrupt speech",
                    "session.thinking.append", next.getString("type"))
                assertEquals(contextChunks[index], next.getString("content"))
                assertEquals("Context alone must not create another request", 1, fixture.tasks.requests.size)
                if (index == contextChunks.lastIndex) {
                    transport.input("Check the updated context.", 600.0, 1_200.0)
                    transport.delegate("context-dependent-task", 1_200.0)
                    fixture.barrier()
                    assertEquals("A clear Live delegation proceeds without silence or the last context receipt",
                        2, fixture.tasks.requests.size)
                }
                transport.acknowledge(next)
                fixture.barrier()
            }
            await { fixture.tasks.requests.size == 2 }
            assertEquals("context-dependent-task", fixture.tasks.requests.last().callId)
            assertEquals(beforeRefresh + contextChunks.size + resultChunks.size, transport.appends().size)
            assertEquals("Only the initial greeting may use instructions in this call", 1,
                transport.appends().count { it.getString("type") == "session.instructions.append" })
            assertFalse("Neither a delegation nor its result may start the welcome again",
                transport.appends().drop(beforeRefresh).any { it.getString("content").contains("Ja, hallo?") ||
                    it.getString("content").contains("Begin the conversation") })
            assertEquals(1, fixture.connectionCount.get())
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun hangupDropsACompletedResultQueuedBehindContextAndIgnoresOldReceiptsInNextCall() {
        val first = FakeTransport("old-call")
        val second = FakeTransport("new-call")
        Fixture(listOf(first, second)).use { fixture ->
            fixture.start(first)
            fixture.accept(first, "old-task")
            first.autoAcknowledge = false
            fixture.context.set(LiveVoiceSessionContext("Synthetic updated context. ".repeat(60)))
            fixture.session.refreshContext()
            fixture.barrier()
            val inFlight = first.appends().last()
            val oldResult = "Private synthetic result belonging only to the old call."
            fixture.tasks.complete("old-task", oldResult)
            fixture.barrier()
            assertEquals(0, fixture.session.snapshot.pendingTaskCount)
            assertFalse(first.appends().any { it.optString("content").contains(oldResult) })

            fixture.session.stop()
            await { fixture.session.snapshot.phase == LiveVoicePhase.STOPPED }
            fixture.start(second)
            first.acknowledge(inFlight)
            fixture.tasks.complete("old-task", oldResult)
            fixture.barrier()

            assertEquals("A fresh call may send only its greeting and welcome here", 2, second.appends().size)
            assertFalse(second.appends().any { it.optString("content").contains(oldResult) })
            assertEquals(1, fixture.tasks.requests.size)
            assertEquals(2, fixture.connectionCount.get())
            assertEquals(LiveVoicePhase.LISTENING, fixture.session.snapshot.phase)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    private class Fixture(private val transports: List<FakeTransport>) : AutoCloseable {
        private val scheduler = Executors.newSingleThreadScheduledExecutor()
        val context = AtomicReference(LiveVoiceSessionContext("Synthetic initial context."))
        val tasks = FakeExecutor()
        val connectionCount = AtomicInteger()
        val failures = CopyOnWriteArrayList<LiveVoiceFailure>()
        val session = LiveApiVoiceSession(
            transportFactory = LiveVoiceTransportFactory { transports[connectionCount.getAndIncrement()] },
            taskExecutor = tasks,
            instructionsProvider = object : LiveVoiceInstructionsProvider {
                override fun buildInstructions(): String = context.get().instructions
                override fun buildSessionContext(): LiveVoiceSessionContext = context.get()
            },
            observer = object : LiveVoiceObserver {
                override fun onFailure(failure: LiveVoiceFailure) { failures += failure }
            },
            config = LiveVoiceSessionConfig(maximumReconnectAttempts = 0),
            scheduler = scheduler,
        )

        fun start(transport: FakeTransport) {
            session.start()
            await {
                transport.appends().any {
                    it.getString("type") == "session.commentary.append" &&
                        it.getString("event_id") in transport.acknowledgedIds
                }
            }
            barrier()
        }

        fun accept(transport: FakeTransport, id: String) {
            transport.input("Check the synthetic item.", 0.0, 500.0)
            transport.delegate(id, 500.0)
            await { tasks.requests.any { it.callId == id } }
        }

        fun barrier() { scheduler.submit {}.get(5, TimeUnit.SECONDS) }

        override fun close() {
            try {
                session.close()
                await { session.snapshot.phase == LiveVoicePhase.STOPPED }
            } finally {
                scheduler.shutdownNow()
                assertTrue("Test scheduler must terminate", scheduler.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    private class FakeExecutor : LiveVoiceTaskExecutor {
        val requests = CopyOnWriteArrayList<LiveVoiceTaskRequest>()
        private val listeners = ConcurrentHashMap<String, LiveVoiceTaskExecutor.Listener>()

        override fun execute(request: LiveVoiceTaskRequest, listener: LiveVoiceTaskExecutor.Listener): LiveVoiceTaskHandle {
            listeners[request.callId] = listener
            requests += request
            return LiveVoiceTaskHandle.NONE
        }

        fun complete(id: String, text: String) = requireNotNull(listeners[id]).onCompleted(LiveVoiceTaskResult(text))
    }

    private class FakeTransport(private val name: String) : LiveVoiceTransport {
        private val sent = CopyOnWriteArrayList<String>()
        private val sequence = AtomicInteger()
        val acknowledgedIds = CopyOnWriteArrayList<String>()
        @Volatile var autoAcknowledge = true
        @Volatile private var listener: LiveVoiceTransport.Listener? = null

        override fun connect(setup: LiveSessionSetup, listener: LiveVoiceTransport.Listener) {
            this.listener = listener
            listener.onOpen()
            emit(JSONObject().put("type", "session.started")
                .put("session", JSONObject().put("id", "synthetic-$name").put("model", OpenAiLiveProtocol.MODEL)
                    .put("audio", JSONObject().put("output", JSONObject().put("voice", setup.config.voice)))))
        }

        override fun connect(credential: RealtimeEphemeralCredential, listener: LiveVoiceTransport.Listener) =
            throw AssertionError("Quiet test must not request a credential transport")

        override fun confirmSessionStarted() = true
        override fun setInputAudioEnabled(enabled: Boolean) = true
        override fun setUserInputMuted(muted: Boolean) = true
        override fun clearOutputAudio() = true
        override fun close() = Unit

        override fun sendUtf8(event: String): Boolean {
            sent += event
            val message = JSONObject(event)
            if (message.getString("type").endsWith(".append") && autoAcknowledge) acknowledge(message)
            if (message.getString("type") == "session.close") {
                emit(JSONObject().put("type", "session.closed")
                    .put("session", JSONObject().put("id", "synthetic-$name"))
                    .put("reason", "client_requested").put("usage", JSONObject().put("seconds", 0.0)))
            }
            return true
        }

        fun appends(): List<JSONObject> = sent.map(::JSONObject).filter { it.getString("type").endsWith(".append") }

        fun acknowledge(
            message: JSONObject,
            clientEventId: String = message.getString("event_id"),
            type: String = message.getString("type") + "ed",
        ) {
            emit(JSONObject().put("type", type).put("client_event_id", clientEventId)
                .put("start_ms", 0.0).put("end_ms", 0.0))
            acknowledgedIds += clientEventId
        }

        fun input(text: String, start: Double, end: Double) = emit(JSONObject()
            .put("type", "session.input_transcript.delta").put("delta", text)
            .put("start_ms", start).put("end_ms", end))

        fun delegate(id: String, offset: Double) = emit(JSONObject()
            .put("type", "session.delegation.created").put("offset_ms", offset)
            .put("delegation", JSONObject().put("id", id).put("type", "delegation").put("target", "client")))

        private fun emit(event: JSONObject) = requireNotNull(listener).onEvent(
            event.put("event_id", "synthetic-$name-${sequence.incrementAndGet()}").toString(),
        )
    }

    private companion object {
        const val CONTEXT_PREFIX = "Current authoritative application context; replace stale workflow facts.\n"

        fun await(predicate: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!predicate() && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue("Expected synthetic session condition within five seconds", predicate())
        }
    }
}
