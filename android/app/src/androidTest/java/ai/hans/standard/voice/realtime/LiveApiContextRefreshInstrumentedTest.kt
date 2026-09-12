package ai.hans.standard.voice.realtime

import ai.hans.standard.setup.HansSetupClock
import ai.hans.standard.setup.HansSetupDocument
import ai.hans.standard.setup.HansSetupRepository
import ai.hans.standard.setup.HansSetupStep
import ai.hans.standard.setup.HansSetupStepRecord
import ai.hans.standard.setup.HansSetupStepStatus
import ai.hans.standard.setup.HansSetupStorage
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real local reducers on Android; synthetic storage/transport, with no audio, credentials or network. */
@RunWith(AndroidJUnit4::class)
class LiveApiContextRefreshInstrumentedTest {
    @Test
    fun repeatedUnchangedRepositoryConfirmationsDoNotAppendContextOrRepeatWelcome() {
        val now = AtomicLong(10_000)
        val storage = MemoryStorage(pendingConfirmationDocument())
        val repository = HansSetupRepository(storage, clock = HansSetupClock(now::get))
        repository.observeEffectiveSelection(SYNTHETIC_MODEL, SYNTHETIC_EFFORT)
        val confirmed = repository.observeProfileConfirmed(true)
        assertTrue("The synthetic setup is genuinely complete before the call", confirmed.complete)
        val writes = storage.writes.get()
        val provider = BoundedLiveVoiceInstructionsProvider(
            baseInstructionsProvider = { STARTUP_PERSONA },
            snapshotProvider = { null },
            capabilitySummaryProvider = { "Synthetic fixture: no Android capability is granted." },
            setupWorkflowProvider = {
                val setup = repository.read()
                val status = setup.record(setup.currentStep).status
                LiveVoiceSetupWorkflowContext(
                    revision = setup.revision,
                    started = setup.started,
                    complete = setup.complete,
                    currentStep = setup.currentStep.name.lowercase(Locale.ROOT),
                    currentStatus = status.name.lowercase(Locale.ROOT),
                    awaitingUser = status == HansSetupStepStatus.AWAITING_USER,
                    conversationIdentity = "synthetic-local-conversation",
                )
            },
            confirmedProfileSummaryProvider = {
                "Fictional local test profile.".takeIf { repository.read().profileConfirmed }
            },
        )
        val initialContext = provider.buildSessionContext()
        assertTrue(initialContext.instructions.contains(STARTUP_PERSONA))
        assertFalse(initialContext.refreshInstructions.contains(STARTUP_PERSONA))
        Fixture(provider).use { fixture ->
            fixture.start()
            val initialAppends = fixture.transport.appends().map(JSONObject::toString)
            assertEquals("One greeting append and one welcome commentary", 2, initialAppends.size)
            assertEquals(1, fixture.transport.welcomes().size)

            repeat(100) {
                now.addAndGet(17)
                repository.observeProfileConfirmed(true)
                repository.observeEffectiveSelection(SYNTHETIC_MODEL, SYNTHETIC_EFFORT)
                fixture.session.refreshContext()
                fixture.barrier()
            }

            assertEquals("Repeated effective observations must not churn the repository", confirmed, repository.read())
            assertEquals("An unchanged confirmation must not write storage", writes, storage.writes.get())
            assertEquals("The real provider must preserve the full deduplication identity", initialContext,
                provider.buildSessionContext())
            assertEquals("An advancing clock is not a reason to send context or another welcome",
                initialAppends, fixture.transport.appends().map(JSONObject::toString))
            assertEquals(1, fixture.transport.welcomes().size)
            assertEquals(1, fixture.connections.get())
            assertTrue(fixture.tasks.requests.isEmpty())
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun changingContextStormKeepsLatestQuietUpdateAndPrioritizesResultWithoutBlockingDelegation() {
        val facts = AtomicReference("Synthetic initial facts.")
        val provider = BoundedLiveVoiceInstructionsProvider(
            baseInstructionsProvider = { STARTUP_PERSONA },
            snapshotProvider = { null },
            capabilitySummaryProvider = facts::get,
            confirmedProfileSummaryProvider = { "Fictional local test profile." },
        )
        Fixture(provider).use { fixture ->
            fixture.start()
            fixture.accept("completed-before-context-drains")
            val transport = fixture.transport
            transport.autoAcknowledge = false
            val before = transport.appends().size
            facts.set("Synthetic stale revision zero. ".repeat(100))
            fixture.session.refreshContext()
            fixture.barrier()
            val inFlight = transport.appends().last()
            assertEquals(before + 1, transport.appends().size)
            assertEquals("Routine facts must use the quiet channel", "session.thinking.append",
                inFlight.getString("type"))
            assertFalse(inFlight.getString("content").contains(STARTUP_PERSONA))
            fixture.tasks.progress("completed-before-context-drains", PRESERVED_PROGRESS)
            fixture.session.interruptHans()
            fixture.barrier()

            repeat(100) { revision ->
                facts.set("Synthetic changed revision ${revision + 1}. ".repeat(100))
                fixture.session.refreshContext()
                // Every distinct context is observed; this is not merely a race that reads the last value 100 times.
                fixture.barrier()
            }
            val latestContext = provider.buildSessionContext()
            assertFalse("The real bounded provider must omit startup persona from a refresh",
                latestContext.refreshInstructions.contains(STARTUP_PERSONA))
            val latestChunks = OpenAiLiveProtocol.contentChunks(CONTEXT_PREFIX + latestContext.refreshInstructions)
            assertTrue("Exercise a multi-part authoritative context", latestChunks.size > 1)
            val result = "Synthetic verified result: the requested item exists. ".repeat(20)
            val resultChunks = OpenAiLiveProtocol.contentChunks(result)
            assertTrue(resultChunks.size > 1)
            fixture.tasks.complete("completed-before-context-drains", result)
            fixture.barrier()
            assertEquals(0, fixture.session.snapshot.pendingTaskCount)

            transport.acknowledge(inFlight, clientEventId = "wrong-context-event")
            transport.acknowledge(inFlight, type = "session.commentary.appended")
            transport.acknowledge(inFlight, type = "session.instructions.appended")
            fixture.barrier()
            assertEquals("Neither a storm nor an unmatched receipt may replace the sent append", before + 1,
                transport.appends().size)
            transport.acknowledge(inFlight)
            fixture.barrier()

            val expected = resultChunks.map { "session.commentary.append" to it } + listOf(
                "session.thinking.append" to PRESERVED_PROGRESS,
                "session.instructions.append" to "Stop speaking now and listen to the user.",
            ) + latestChunks.map { "session.thinking.append" to it }
            expected.forEachIndexed { index, (kind, text) ->
                val message = transport.appends().last()
                assertEquals("Only priority result, preserved controls and latest context may remain", kind,
                    message.getString("type"))
                assertEquals(text, message.getString("content"))
                assertEquals(before + 2 + index, transport.appends().size)
                assertFalse("A context storm must not reintroduce the persona", text.contains(STARTUP_PERSONA))
                assertEquals("No automatic delegation is justified by context or a result", 1,
                    fixture.tasks.requests.size)
                if (index < resultChunks.size) {
                    assertEquals("completed-before-context-drains", message.getString("delegation_id"))
                }
                if (index == expected.lastIndex) {
                    transport.input("Use only the newest synthetic facts.", 600.0, 1_200.0)
                    transport.delegate("requires-latest-context", 1_200.0)
                    transport.acknowledge(inFlight)
                    transport.acknowledge(message, clientEventId = "wrong-latest-final-event")
                    transport.acknowledge(message, type = "session.commentary.appended")
                    transport.acknowledge(message, type = "session.instructions.appended")
                    fixture.barrier()
                    assertEquals("Live's next delegation must not wait for background context receipts", 2,
                        fixture.tasks.requests.size)
                    assertEquals(before + 2 + index, transport.appends().size)
                    assertEquals("A delayed context receipt must not reopen the call", 1, fixture.connections.get())
                }
                transport.acknowledge(message)
                fixture.barrier()
            }
            await { fixture.tasks.requests.size == 2 }
            assertEquals("requires-latest-context", fixture.tasks.requests.last().callId)
            assertEquals("A hundred context updates are bounded by one in-flight and one newest update",
                before + 1 + expected.size, transport.appends().size)
            assertEquals(1, transport.welcomes().size)
            assertEquals(1, fixture.connections.get())
            assertTrue(transport.inputEnabled)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    private class Fixture(provider: LiveVoiceInstructionsProvider) : AutoCloseable {
        private val scheduler = Executors.newSingleThreadScheduledExecutor()
        val transport = FakeTransport()
        val tasks = FakeExecutor()
        val connections = AtomicInteger()
        val failures = CopyOnWriteArrayList<LiveVoiceFailure>()
        val session = LiveApiVoiceSession(
            transportFactory = LiveVoiceTransportFactory {
                check(connections.incrementAndGet() == 1) { "Synthetic call unexpectedly reconnected" }
                transport
            },
            taskExecutor = tasks,
            instructionsProvider = provider,
            observer = object : LiveVoiceObserver {
                override fun onFailure(failure: LiveVoiceFailure) { failures += failure }
            },
            config = LiveVoiceSessionConfig(maximumReconnectAttempts = 0, configureTimeoutMillis = 1_000),
            scheduler = scheduler,
        )

        fun start() {
            session.start()
            await { transport.welcomes().any { it.getString("event_id") in transport.acknowledgedIds } }
            barrier()
        }

        fun accept(id: String) {
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
                assertTrue("The synthetic scheduler must terminate", scheduler.awaitTermination(5, TimeUnit.SECONDS))
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

        fun progress(id: String, summary: String) =
            requireNotNull(listeners[id]).onProgress(LiveVoiceTaskProgress(summary))

        fun complete(id: String, text: String) = requireNotNull(listeners[id]).onCompleted(LiveVoiceTaskResult(text))
    }

    private class FakeTransport : LiveVoiceTransport {
        private val sent = CopyOnWriteArrayList<String>()
        private val sequence = AtomicInteger()
        val acknowledgedIds = CopyOnWriteArrayList<String>()
        @Volatile var autoAcknowledge = true
        @Volatile var inputEnabled = true
            private set
        @Volatile private var listener: LiveVoiceTransport.Listener? = null

        override fun connect(setup: LiveSessionSetup, listener: LiveVoiceTransport.Listener) {
            this.listener = listener
            assertTrue(setup.instructions.contains(STARTUP_PERSONA))
            listener.onOpen()
            emit(JSONObject().put("type", "session.started")
                .put("session", JSONObject().put("id", "synthetic-context-refresh").put("model", OpenAiLiveProtocol.MODEL)
                    .put("audio", JSONObject().put("output", JSONObject().put("voice", setup.config.voice)))))
        }

        override fun connect(credential: RealtimeEphemeralCredential, listener: LiveVoiceTransport.Listener) =
            throw AssertionError("Quiet test must not request a credential transport")

        override fun confirmSessionStarted() = true
        override fun setInputAudioEnabled(enabled: Boolean): Boolean { inputEnabled = enabled; return true }
        override fun setUserInputMuted(muted: Boolean) = true
        override fun clearOutputAudio() = true
        override fun close() = Unit

        override fun sendUtf8(event: String): Boolean {
            sent += event
            val message = JSONObject(event)
            if (message.getString("type").endsWith(".append") && autoAcknowledge) acknowledge(message)
            if (message.getString("type") == "session.close") {
                emit(JSONObject().put("type", "session.closed")
                    .put("session", JSONObject().put("id", "synthetic-context-refresh"))
                    .put("reason", "client_requested").put("usage", JSONObject().put("seconds", 0.0)))
            }
            return true
        }

        fun appends(): List<JSONObject> = sent.map(::JSONObject).filter { it.getString("type").endsWith(".append") }

        fun welcomes(): List<JSONObject> = appends().filter {
            it.getString("type") == "session.commentary.append" &&
                it.getString("content").contains("Begin the conversation now")
        }

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
            event.put("event_id", "synthetic-context-${sequence.incrementAndGet()}").toString(),
        )
    }

    private class MemoryStorage(@Volatile private var value: HansSetupDocument) : HansSetupStorage {
        val writes = AtomicInteger()
        override fun read(): HansSetupDocument = value
        override fun write(document: HansSetupDocument) { value = document; writes.incrementAndGet() }
    }

    private companion object {
        const val CONTEXT_PREFIX = "Current authoritative application context; replace stale workflow facts.\n"
        const val STARTUP_PERSONA = "SYNTHETIC_STARTUP_PERSONA: Begin this genuinely new call with a greeting."
        const val PRESERVED_PROGRESS = "Previously queued synthetic progress remains valid."
        const val SYNTHETIC_MODEL = "gpt-5.6-luna"
        const val SYNTHETIC_EFFORT = "max"

        fun pendingConfirmationDocument() = HansSetupDocument(
            started = true,
            requestedModel = SYNTHETIC_MODEL,
            requestedReasoningEffort = SYNTHETIC_EFFORT,
            steps = HansSetupStep.entries.filterNot { it == HansSetupStep.COMPLETE }.associateWith { step ->
                when (step) {
                    HansSetupStep.MODEL_REASONING -> HansSetupStepRecord(
                        status = HansSetupStepStatus.VERIFYING,
                        detailCode = "awaiting_app_server_selection_proof",
                    )
                    HansSetupStep.PERSONAL_PROFILE -> HansSetupStepRecord(
                        status = HansSetupStepStatus.AWAITING_USER,
                        detailCode = "profile_confirmation_missing",
                    )
                    else -> HansSetupStepRecord(
                        status = HansSetupStepStatus.VERIFIED,
                        detailCode = if (step == HansSetupStep.HARDWARE_LIVE_TEST) {
                            "hardware_accessibility_start_stop_sent_verified"
                        } else "synthetic_prerequisite_verified",
                    )
                }
            },
        )

        fun await(predicate: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!predicate() && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue("Expected synthetic session condition within five seconds", predicate())
        }
    }
}
