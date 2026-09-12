package ai.hans.standard.automations.androidgateway

import ai.hans.standard.automations.AutomationHeartbeat
import ai.hans.standard.automations.CodexAutomationExecutionOutcome
import ai.hans.standard.codex.CodexServiceTier
import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolCancellationDisposition
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolFunctionSpec
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import ai.hans.standard.codex.ReasoningEffort
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AutomationCodexGatewayTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun threadBoundDispatchRequiresExactReadyThreadAndCorrelatedTerminalSuccess() {
        val adapter = FakeExistingAdapter(
            snapshot = ExistingThreadAutomationSnapshot("thread-a", ExistingThreadPhase.READY),
            states = ArrayDeque(
                listOf(
                    ExistingThreadExecutionState.Running,
                    ExistingThreadExecutionState.Succeeded,
                ),
            ),
        )
        val beats = AtomicInteger()
        val gateway = AndroidAutomationCodexGateway(
            existingThread = adapter,
            independent = IndependentAutomationCodexRunner { _, _, _ ->
                CodexAutomationExecutionOutcome.Succeeded
            },
            timeouts = timeouts(),
        )

        assertEquals(
            CodexAutomationExecutionOutcome.Succeeded,
            gateway.executeInExistingThread(
                "thread-a",
                "private prompt",
                "idempotency-key-0001",
                AutomationHeartbeat { beats.incrementAndGet() > 0 },
            ),
        )
        assertEquals(1, adapter.dispatches)
        assertEquals("idempotency-key-0001", adapter.lastIdempotencyKey)
        assertTrue(beats.get() >= 2)
    }

    @Test
    fun threadBoundMismatchAndBusyFailBeforeDispatch() {
        val mismatch = FakeExistingAdapter(
            ExistingThreadAutomationSnapshot("different", ExistingThreadPhase.READY),
        )
        val gateway = gateway(mismatch)
        assertEquals(
            CodexAutomationExecutionOutcome.PermanentFailure("codex_thread_unavailable"),
            gateway.executeInExistingThread(
                "wanted",
                "prompt",
                "idempotency-key-0002",
                AutomationHeartbeat { true },
            ),
        )
        assertEquals(0, mismatch.dispatches)

        val busy = FakeExistingAdapter(
            ExistingThreadAutomationSnapshot("wanted", ExistingThreadPhase.BUSY),
        )
        assertEquals(
            CodexAutomationExecutionOutcome.RetryableFailure("codex_thread_busy"),
            gateway(busy).executeInExistingThread(
                "wanted",
                "prompt",
                "idempotency-key-0003",
                AutomationHeartbeat { true },
            ),
        )
        assertEquals(0, busy.dispatches)
    }

    @Test
    fun acceptedTerminalFailureFailsClosedEvenWhenRuntimeCallsItRetryable() {
        val adapter = FakeExistingAdapter(
            snapshot = ExistingThreadAutomationSnapshot("thread-a", ExistingThreadPhase.READY),
            states = ArrayDeque(
                listOf(ExistingThreadExecutionState.Failed(retryable = true)),
            ),
        )

        assertEquals(
            CodexAutomationExecutionOutcome.PermanentFailure(
                "codex_turn_failed_after_dispatch",
            ),
            gateway(adapter).executeInExistingThread(
                "thread-a",
                "perform one potentially effectful task",
                "idempotency-key-failed-after-accept",
                AutomationHeartbeat { true },
            ),
        )
        assertEquals(1, adapter.dispatches)
    }

    @Test
    fun acceptedCorrelationLossFailsClosedInsteadOfRequestingRedispatch() {
        val adapter = FakeExistingAdapter(
            snapshot = ExistingThreadAutomationSnapshot("thread-a", ExistingThreadPhase.READY),
            states = ArrayDeque(listOf(ExistingThreadExecutionState.CorrelationLost)),
        )

        assertEquals(
            CodexAutomationExecutionOutcome.PermanentFailure(
                "codex_turn_correlation_lost_after_dispatch",
            ),
            gateway(adapter).executeInExistingThread(
                "thread-a",
                "perform one potentially effectful task",
                "idempotency-key-lost-after-accept",
                AutomationHeartbeat { true },
            ),
        )
        assertEquals(1, adapter.dispatches)
    }

    @Test
    fun acceptedThreadCancellationWakesWaiterAndFailsAmbiguousWithoutRedispatch() {
        val heartbeat = TestCancellationHeartbeat()
        val adapter = FakeExistingAdapter(
            snapshot = ExistingThreadAutomationSnapshot("thread-a", ExistingThreadPhase.READY),
            onAwait = heartbeat::cancel,
        )

        assertEquals(
            CodexAutomationExecutionOutcome.PermanentFailure(
                "codex_turn_outcome_ambiguous",
            ),
            gateway(adapter).executeInExistingThread(
                "thread-a",
                "perform one potentially effectful task",
                "idempotency-key-cancel-after-accept",
                heartbeat,
            ),
        )
        assertEquals(1, adapter.dispatches)
        assertEquals(1, adapter.waiterWakeups)
    }

    @Test
    fun provenPreDispatchRejectionRemainsRetryable() {
        val heartbeat = TestFenceHeartbeat()
        val adapter = FakeExistingAdapter(
            snapshot = ExistingThreadAutomationSnapshot("thread-a", ExistingThreadPhase.READY),
            dispatchResult = ExistingThreadDispatchResult.Rejected(
                code = "codex_dispatch_failed",
                retryable = true,
            ),
        )

        assertEquals(
            CodexAutomationExecutionOutcome.RetryableFailure("codex_dispatch_failed"),
            gateway(adapter).executeInExistingThread(
                "thread-a",
                "task rejected before App Server acceptance",
                "idempotency-key-pre-dispatch",
                heartbeat,
            ),
        )
        assertEquals(1, adapter.dispatches)
        assertEquals(1, heartbeat.marks)
        assertEquals(1, heartbeat.clears)
        assertFalse(heartbeat.marked)
    }

    @Test
    fun transportOutcomeAmbiguousIsTerminalAndRetainsDispatchFence() {
        val heartbeat = TestFenceHeartbeat()
        val adapter = FakeExistingAdapter(
            snapshot = ExistingThreadAutomationSnapshot("thread-a", ExistingThreadPhase.READY),
            dispatchResult = ExistingThreadDispatchResult.OutcomeAmbiguous(
                "codex_turn_outcome_ambiguous",
            ),
        )

        assertEquals(
            CodexAutomationExecutionOutcome.PermanentFailure(
                "codex_turn_outcome_ambiguous",
            ),
            gateway(adapter).executeInExistingThread(
                "thread-a",
                "transport may have delivered this task",
                "idempotency-key-transport-ambiguous",
                heartbeat,
            ),
        )
        assertEquals(1, adapter.dispatches)
        assertEquals(1, heartbeat.marks)
        assertEquals(0, heartbeat.clears)
        assertTrue(heartbeat.marked)
    }

    @Test
    fun threadBoundFenceIsPersistedBeforeHostDispatchCanReachDispatchSerial() {
        val heartbeat = TestFenceHeartbeat()
        val adapter = FakeExistingAdapter(
            snapshot = ExistingThreadAutomationSnapshot("thread-a", ExistingThreadPhase.READY),
            states = ArrayDeque(listOf(ExistingThreadExecutionState.Succeeded)),
            onDispatch = { assertTrue(heartbeat.marked) },
        )

        assertEquals(
            CodexAutomationExecutionOutcome.Succeeded,
            gateway(adapter).executeInExistingThread(
                "thread-a",
                "perform one effectful task",
                "idempotency-key-fence-before-host",
                heartbeat,
            ),
        )
        assertEquals(1, heartbeat.marks)
        assertEquals(0, heartbeat.clears)
    }

    @Test
    fun threadBoundUnknownDispatchOutcomeRetainsFenceForRecoveryReview() {
        val heartbeat = TestFenceHeartbeat()
        val adapter = FakeExistingAdapter(
            snapshot = ExistingThreadAutomationSnapshot("thread-a", ExistingThreadPhase.READY),
            onDispatch = { error("process died after host dispatch entered") },
        )

        assertEquals(
            CodexAutomationExecutionOutcome.PermanentFailure("codex_turn_outcome_ambiguous"),
            gateway(adapter).executeInExistingThread(
                "thread-a",
                "perform one effectful task",
                "idempotency-key-thread-unknown",
                heartbeat,
            ),
        )
        assertTrue(heartbeat.marked)
        assertEquals(1, heartbeat.marks)
        assertEquals(0, heartbeat.clears)
    }

    @Test
    fun independentCorrelatedTurnStartRejectionClearsFenceAndCanRetry() {
        val frames = terminalFrames(includeCompletion = false).apply {
            removeLast()
            addLast(
                OneShotAppServerInbound.Frame(
                    JSONObject()
                        .put("id", 4)
                        .put(
                            "error",
                            JSONObject().put("code", -32_000).put("message", "rejected"),
                        )
                        .toString(),
                ),
            )
        }
        val fixture = fixture(frames)
        val heartbeat = TestFenceHeartbeat()
        fixture.session.sendHook = { frame ->
            if (JSONObject(frame).optString("method") == "turn/start") {
                assertTrue(heartbeat.marked)
            }
        }

        assertEquals(
            CodexAutomationExecutionOutcome.RetryableFailure("codex_request_failed"),
            fixture.runner.execute(
                "private prompt",
                "idempotency-key-independent-rejected",
                heartbeat,
            ),
        )
        assertEquals(1, heartbeat.marks)
        assertEquals(1, heartbeat.clears)
        assertFalse(heartbeat.marked)
    }

    @Test
    fun independentTransportFailureAfterTurnStartBoundaryRetainsFence() {
        val fixture = fixture(terminalFrames(includeCompletion = false))
        val heartbeat = TestFenceHeartbeat()
        fixture.session.sendHook = { frame ->
            if (JSONObject(frame).optString("method") == "turn/start") {
                assertTrue(heartbeat.marked)
                error("transport outcome unknown")
            }
        }

        assertEquals(
            CodexAutomationExecutionOutcome.PermanentFailure("codex_turn_outcome_ambiguous"),
            fixture.runner.execute(
                "private prompt",
                "idempotency-key-independent-unknown",
                heartbeat,
            ),
        )
        assertTrue(heartbeat.marked)
        assertEquals(1, heartbeat.marks)
        assertEquals(0, heartbeat.clears)
    }

    @Test
    fun independentSuccessUsesSelectedModelEffortAndAlwaysCleansUp() {
        val fixture = fixture(terminalFrames())

        assertEquals(
            CodexAutomationExecutionOutcome.Succeeded,
            fixture.runner.execute("private prompt", "idempotency-key-0010", AutomationHeartbeat { true }),
        )
        assertTrue(fixture.session.closed)
        val thread = fixture.session.sent.map(::JSONObject)
            .first { it.optString("method") == "thread/start" }
            .getJSONObject("params")
        assertEquals("gpt-5.6-luna", thread.getString("model"))
        assertEquals(CodexServiceTier.STANDARD, thread.getString("serviceTier"))
        assertEquals("danger-full-access", thread.getString("sandbox"))
        assertFalse(thread.getBoolean("ephemeral"))
        val turn = fixture.session.sent.map(::JSONObject)
            .first { it.optString("method") == "turn/start" }
            .getJSONObject("params")
        assertEquals("max", turn.getString("effort"))
        assertEquals(CodexServiceTier.STANDARD, turn.getString("serviceTier"))
        assertEquals("idempotency-key-0010", turn.getString("clientUserMessageId"))
    }

    @Test
    fun malformedAndOversizedFramesFailClosedAndCleanUp() {
        listOf(
            OneShotAppServerInbound.Frame("not-json") to
                CodexAutomationExecutionOutcome.PermanentFailure("codex_protocol_incompatible"),
            OneShotAppServerInbound.Oversized to
                CodexAutomationExecutionOutcome.PermanentFailure("codex_frame_oversized"),
        ).forEach { (badFrame, expected) ->
            val frames = terminalFrames(includeCompletion = false).apply { addLast(badFrame) }
            val fixture = fixture(frames)
            assertEquals(
                expected,
                fixture.runner.execute(
                    "private prompt",
                    "idempotency-key-0011",
                    AutomationHeartbeat { true },
                ),
            )
            assertTrue(fixture.session.closed)
        }
    }

    @Test
    fun independentTimeoutIsBoundedAndCleansUp() {
        val clock = SteppingClock(TimeUnit.MILLISECONDS.toNanos(2))
        val fixture = fixture(
            frames = terminalFrames(includeCompletion = false),
            clock = clock,
            timeouts = AutomationGatewayTimeouts(
                threadBoundTimeoutMillis = 20,
                independentTimeoutMillis = 20,
                heartbeatIntervalMillis = 2,
                toolTimeoutMillis = 10,
            ),
        )

        assertEquals(
            CodexAutomationExecutionOutcome.PermanentFailure(
                "codex_turn_outcome_ambiguous",
            ),
            fixture.runner.execute(
                "private prompt",
                "idempotency-key-0012",
                AutomationHeartbeat { true },
            ),
        )
        assertTrue(fixture.session.closed)
    }

    @Test
    fun heartbeatLossAfterTurnDispatchFailsAmbiguousAndCleansUp() {
        val fixture = fixture(terminalFrames(includeCompletion = false))
        val beats = AtomicInteger()

        assertEquals(
            CodexAutomationExecutionOutcome.PermanentFailure(
                "codex_turn_outcome_ambiguous",
            ),
            fixture.runner.execute(
                "private prompt",
                "idempotency-key-0013",
                AutomationHeartbeat { beats.incrementAndGet() < 8 },
            ),
        )
        assertTrue(beats.get() >= 8)
        assertTrue(fixture.session.closed)
    }

    @Test
    fun jobCancellationDestroysIndependentSessionAndFailsClosedAfterTurnDispatch() {
        val fixture = fixture(terminalFrames(includeCompletion = false))
        val heartbeat = TestCancellationHeartbeat()
        fixture.session.pollHook = {
            if (
                fixture.session.sent.any {
                    JSONObject(it).optString("method") == "turn/start"
                }
            ) {
                heartbeat.cancel()
            }
        }

        assertEquals(
            CodexAutomationExecutionOutcome.PermanentFailure(
                "codex_turn_outcome_ambiguous",
            ),
            fixture.runner.execute(
                "private prompt",
                "idempotency-key-job-cancel",
                heartbeat,
            ),
        )
        assertTrue(fixture.session.cancellationRequested)
        assertTrue(fixture.session.closed)
        assertEquals(1, heartbeat.registeredListenerCount)
    }

    @Test
    fun dynamicToolIsExecutedOnceAndDuplicateRequestGetsNoSecondResponse() {
        val tool = RecordingToolExecutor()
        val frames = terminalFrames(includeCompletion = false).apply {
            addLast(OneShotAppServerInbound.Frame(toolCall(99)))
            addLast(OneShotAppServerInbound.Frame(toolCall(99)))
            addLast(OneShotAppServerInbound.Frame(turnCompleted()))
        }
        val fixture = fixture(frames, toolExecutor = tool)

        assertEquals(
            CodexAutomationExecutionOutcome.Succeeded,
            fixture.runner.execute(
                "private prompt",
                "idempotency-key-0014",
                AutomationHeartbeat { true },
            ),
        )
        assertEquals(1, tool.executions)
        val toolResponses = fixture.session.sent.map(::JSONObject)
            .filter { it.optLong("id", -1) == 99L && it.has("result") }
        assertEquals(1, toolResponses.size)
        assertTrue(toolResponses.single().getJSONObject("result").getBoolean("success"))
        assertTrue(fixture.session.closed)
    }

    @Test
    fun cancellationAfterDynamicToolEffectStartsIsTerminalAmbiguousAndNeverResponds() {
        val heartbeat = TestCancellationHeartbeat()
        val tool = EffectThenCancelToolExecutor(heartbeat)
        val frames = terminalFrames(includeCompletion = false).apply {
            addLast(OneShotAppServerInbound.Frame(toolCall(100)))
        }
        val fixture = fixture(frames, toolExecutor = tool)

        assertEquals(
            CodexAutomationExecutionOutcome.PermanentFailure("codex_turn_outcome_ambiguous"),
            fixture.runner.execute(
                "perform one effectful tool call",
                "idempotency-key-tool-cancel-after-effect",
                heartbeat,
            ),
        )
        assertEquals(1, tool.effects)
        assertEquals(1, tool.handleCancellations)
        assertTrue(fixture.session.cancellationRequested)
        assertFalse(
            fixture.session.sent.map(::JSONObject)
                .any { it.optLong("id", -1) == 100L && it.has("result") },
        )
    }

    @Test
    fun dynamicToolTimeoutAfterPossibleEffectIsTerminalInsteadOfReturningRetryableToolFailure() {
        val tool = EffectfulNeverCompletingToolExecutor()
        val frames = terminalFrames(includeCompletion = false).apply {
            addLast(OneShotAppServerInbound.Frame(toolCall(101)))
        }
        val fixture = fixture(
            frames = frames,
            toolExecutor = tool,
            clock = SteppingClock(TimeUnit.MILLISECONDS.toNanos(2)),
            timeouts = AutomationGatewayTimeouts(
                threadBoundTimeoutMillis = 20,
                independentTimeoutMillis = 40,
                heartbeatIntervalMillis = 2,
                toolTimeoutMillis = 8,
            ),
        )

        assertEquals(
            CodexAutomationExecutionOutcome.PermanentFailure("dynamic_tool_outcome_ambiguous"),
            fixture.runner.execute(
                "perform one effectful tool call",
                "idempotency-key-tool-timeout-after-effect",
                AutomationHeartbeat { true },
            ),
        )
        assertTrue(tool.handleCancellations >= 1)
        assertFalse(
            fixture.session.sent.map(::JSONObject)
                .any { it.optLong("id", -1) == 101L && it.has("result") },
        )
    }

    @Test
    fun unavailableLoginIsRetryableAndDoesNotLeakOrOpenSession() {
        var opens = 0
        val runner = runner(
            factory = OneShotAppServerSessionFactory {
                opens += 1
                throw AutomationGatewayFailure("codex_login_required", retryable = true)
            },
        )

        assertEquals(
            CodexAutomationExecutionOutcome.RetryableFailure("codex_login_required"),
            runner.execute(
                "private prompt",
                "idempotency-key-0015",
                AutomationHeartbeat { true },
            ),
        )
        assertEquals(1, opens)
    }

    @Test
    fun independentRunsConfirmSelectedLowAndUltraBeforeDispatchRatherThanInheritingMax() {
        listOf(
            IndependentCodexSelection("gpt-5.6-luna", ReasoningEffort.LOW),
            IndependentCodexSelection("gpt-5.6-sol", ReasoningEffort.ULTRA),
        ).forEach { selection ->
            val fixture = fixture(terminalFrames(selection = selection), selection = selection)
            assertEquals(
                CodexAutomationExecutionOutcome.Succeeded,
                fixture.runner.execute(
                    "user-requested test task", "idempotency-key-selected-effort",
                    AutomationHeartbeat { true },
                ),
            )
            val requests = fixture.session.sent.map(::JSONObject)
            val thread = requests.single { it.optString("method") == "thread/start" }
                .getJSONObject("params")
            assertEquals(selection.model, thread.getString("model"))
            assertEquals(
                selection.effort.wireValue,
                thread.getJSONObject("config").getString("model_reasoning_effort"),
            )
            val turn = requests.single { it.optString("method") == "turn/start" }
                .getJSONObject("params")
            assertEquals(selection.effort.wireValue, turn.getString("effort"))
            assertEquals("friendly", turn.getString("personality"))
            assertEquals("concise", turn.getString("summary"))
            assertTrue(fixture.session.closed)
        }
    }

    @Test
    fun accountReadWithoutAuthenticatedAccountFailsClosedAndCleansProcess() {
        val frames = ArrayDeque<OneShotAppServerInbound>().apply {
            addLast(OneShotAppServerInbound.Frame(initialize()))
            addLast(
                OneShotAppServerInbound.Frame(
                    JSONObject()
                        .put("id", 2)
                        .put(
                            "result",
                            JSONObject()
                                .put("account", JSONObject.NULL)
                                .put("requiresOpenaiAuth", true),
                        )
                        .toString(),
                ),
            )
        }
        val fixture = fixture(frames)

        assertEquals(
            CodexAutomationExecutionOutcome.RetryableFailure("codex_login_required"),
            fixture.runner.execute(
                "private prompt",
                "idempotency-key-0016",
                AutomationHeartbeat { true },
            ),
        )
        assertTrue(fixture.session.closed)
        assertFalse(
            fixture.session.sent.map(::JSONObject)
                .any { it.optString("method") == "thread/start" },
        )
    }

    private fun fixture(
        frames: ArrayDeque<OneShotAppServerInbound>,
        toolExecutor: DynamicToolExecutor? = null,
        clock: MonotonicNanoClock = MonotonicNanoClock.SYSTEM,
        timeouts: AutomationGatewayTimeouts = timeouts(),
        selection: IndependentCodexSelection = IndependentCodexSelection(
            "gpt-5.6-luna", ReasoningEffort.MAX,
        ),
    ): Fixture {
        val home = temporary.newFolder("home-${System.nanoTime()}")
        val workspace = temporary.newFolder("workspace-${System.nanoTime()}")
        val session = FakeOneShotSession(home, workspace, frames)
        return Fixture(
            runner = runner(
                factory = OneShotAppServerSessionFactory { session },
                toolExecutor = toolExecutor,
                clock = clock,
                timeouts = timeouts,
                selection = selection,
            ),
            session = session,
        )
    }

    private fun runner(
        factory: OneShotAppServerSessionFactory,
        toolExecutor: DynamicToolExecutor? = null,
        clock: MonotonicNanoClock = MonotonicNanoClock.SYSTEM,
        timeouts: AutomationGatewayTimeouts = timeouts(),
        selection: IndependentCodexSelection = IndependentCodexSelection(
            "gpt-5.6-luna", ReasoningEffort.MAX,
        ),
    ) = OneShotIndependentAutomationCodexRunner(
        sessionFactory = factory,
        selectionSource = IndependentCodexSelectionSource { selection },
        dynamicTools = toolExecutor,
        developerInstructions = AutomationDeveloperInstructionsSource { "Be safe." },
        timeouts = timeouts,
        clock = clock,
        clientVersion = "1.0-test",
    )

    private fun gateway(adapter: ExistingThreadAutomationAdapter) = AndroidAutomationCodexGateway(
        existingThread = adapter,
        independent = IndependentAutomationCodexRunner { _, _, _ ->
            CodexAutomationExecutionOutcome.Succeeded
        },
        timeouts = timeouts(),
    )

    private fun terminalFrames(
        includeCompletion: Boolean = true,
        selection: IndependentCodexSelection = IndependentCodexSelection(
            "gpt-5.6-luna", ReasoningEffort.MAX,
        ),
    ) =
        ArrayDeque<OneShotAppServerInbound>().apply {
            addLast(OneShotAppServerInbound.Frame(initialize()))
            addLast(OneShotAppServerInbound.Frame(account()))
            addLast(OneShotAppServerInbound.Frame(threadStarted(selection)))
            addLast(OneShotAppServerInbound.Frame(turnStarted()))
            if (includeCompletion) addLast(OneShotAppServerInbound.Frame(turnCompleted()))
        }

    private fun initialize() = JSONObject()
        .put("id", 1)
        .put(
            "result",
            JSONObject()
                .put("userAgent", "codex-test")
                .put("codexHome", currentHomePath())
                .put("platformFamily", "unix")
                .put("platformOs", "linux"),
        )
        .toString()

    /* Replaced per fixture so the protocol identity matches its expected persistent home. */
    private var activeHomePath: String = "/tmp/not-set"

    private fun currentHomePath(): String = activeHomePath

    private fun account() = JSONObject()
        .put("id", 2)
        .put(
            "result",
            JSONObject()
                .put(
                    "account",
                    JSONObject()
                        .put("type", "chatgpt")
                        .put("email", "person@example.test")
                        .put("planType", "pro"),
                )
                .put("requiresOpenaiAuth", true),
        )
        .toString()

    private fun threadStarted(selection: IndependentCodexSelection) = JSONObject()
        .put("id", 3)
        .put(
            "result",
            JSONObject()
                .put("thread", JSONObject().put("id", "thread-independent"))
                .put("model", selection.model)
                .put("serviceTier", selection.serviceTier)
                .put("reasoningEffort", selection.effort.wireValue),
        )
        .toString()

    private fun turnStarted() = JSONObject()
        .put("id", 4)
        .put(
            "result",
            JSONObject().put(
                "turn",
                JSONObject().put("id", "turn-independent").put("status", "inProgress"),
            ),
        )
        .toString()

    private fun turnCompleted() = JSONObject()
        .put("method", "turn/completed")
        .put(
            "params",
            JSONObject()
                .put("threadId", "thread-independent")
                .put(
                    "turn",
                    JSONObject()
                        .put("id", "turn-independent")
                        .put("items", org.json.JSONArray())
                        .put("status", "completed"),
                ),
        )
        .toString()

    private fun toolCall(id: Long) = JSONObject()
        .put("id", id)
        .put("method", "item/tool/call")
        .put(
            "params",
            JSONObject()
                .put("threadId", "thread-independent")
                .put("turnId", "turn-independent")
                .put("callId", "call-1")
                .put("namespace", "test_phone")
                .put("tool", "read_battery")
                .put("arguments", JSONObject()),
        )
        .toString()

    private fun timeouts() = AutomationGatewayTimeouts(
        threadBoundTimeoutMillis = 2_000,
        independentTimeoutMillis = 2_000,
        heartbeatIntervalMillis = 10,
        toolTimeoutMillis = 500,
    )

    private inner class FakeOneShotSession(
        override val expectedCodexHome: File,
        override val workspaceDirectory: File,
        private val frames: ArrayDeque<OneShotAppServerInbound>,
    ) : OneShotAppServerSession {
        val sent = mutableListOf<String>()
        var closed = false
        var cancellationRequested = false
        var pollHook: (() -> Unit)? = null
        var sendHook: ((String) -> Unit)? = null

        init {
            activeHomePath = expectedCodexHome.absolutePath
            // Frames are created before this session; repair only the known initialize response.
            val first = frames.pollFirst()
            if (first is OneShotAppServerInbound.Frame) {
                val json = JSONObject(first.raw)
                if (json.optLong("id", -1) == 1L) {
                    json.getJSONObject("result").put("codexHome", activeHomePath)
                    frames.addFirst(OneShotAppServerInbound.Frame(json.toString()))
                } else {
                    frames.addFirst(first)
                }
            }
        }

        override fun send(frame: String) {
            sendHook?.invoke(frame)
            sent += frame
        }

        override fun poll(maximumWaitMillis: Long): OneShotAppServerInbound? {
            pollHook?.invoke()
            return frames.pollFirst()
        }

        override fun requestCancellation() {
            cancellationRequested = true
            frames.clear()
            frames.addLast(OneShotAppServerInbound.Eof)
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeExistingAdapter(
        private val snapshot: ExistingThreadAutomationSnapshot,
        private val states: ArrayDeque<ExistingThreadExecutionState> = ArrayDeque(),
        private val dispatchResult: ExistingThreadDispatchResult =
            ExistingThreadDispatchResult.Accepted("correlation-1"),
        private val onAwait: (() -> Unit)? = null,
        private val onDispatch: (() -> Unit)? = null,
    ) : ExistingThreadAutomationAdapter {
        var dispatches = 0
        var lastIdempotencyKey: String? = null
        var waiterWakeups = 0

        override fun snapshot(): ExistingThreadAutomationSnapshot = snapshot

        override fun dispatchIfReady(
            expectedThreadId: String,
            instruction: String,
            idempotencyKey: String,
        ): ExistingThreadDispatchResult {
            onDispatch?.invoke()
            dispatches += 1
            lastIdempotencyKey = idempotencyKey
            return dispatchResult
        }

        override fun awaitExecutionState(
            correlationId: String,
            maximumWaitMillis: Long,
        ): ExistingThreadExecutionState {
            onAwait?.invoke()
            return states.pollFirst() ?: ExistingThreadExecutionState.Pending
        }

        override fun wakeAwaiter(correlationId: String) {
            waiterWakeups += 1
        }
    }

    private class TestCancellationHeartbeat : AutomationHeartbeat {
        private val listeners = linkedSetOf<() -> Unit>()
        private var cancelled = false
        var registeredListenerCount = 0
            private set

        override fun beat(): Boolean = !cancelled

        override fun onCancellation(listener: () -> Unit): AutoCloseable {
            registeredListenerCount += 1
            if (cancelled) {
                listener()
                return AutoCloseable {}
            }
            listeners += listener
            return AutoCloseable { listeners.remove(listener) }
        }

        fun cancel() {
            if (cancelled) return
            cancelled = true
            listeners.toList().forEach { it() }
            listeners.clear()
        }
    }

    private class TestFenceHeartbeat : AutomationHeartbeat {
        var marked = false
            private set
        var marks = 0
            private set
        var clears = 0
            private set

        override fun beat(): Boolean = true

        override fun markExternalDispatchStarted(): Boolean {
            marks += 1
            marked = true
            return true
        }

        override fun clearExternalDispatchAfterProvenRejection(): Boolean {
            clears += 1
            marked = false
            return true
        }
    }

    private class RecordingToolExecutor : DynamicToolExecutor {
        var executions = 0

        override val specs = listOf(
            DynamicToolNamespaceSpec(
                name = "test_phone",
                description = "Test phone tools",
                tools = listOf(
                    DynamicToolFunctionSpec(
                        name = "read_battery",
                        description = "Read a test battery",
                        inputSchemaJson = """{"type":"object","additionalProperties":false}""",
                    ),
                ),
            ),
        )

        override fun execute(
            call: DynamicToolCallParams,
            completion: (DynamicToolExecutionResult) -> Unit,
        ) {
            executions += 1
            completion(
                DynamicToolExecutionResult(
                    JSONObject().put("status", "ok").put("percent", 90).toString(),
                    success = true,
                ),
            )
            completion(
                DynamicToolExecutionResult(
                    JSONObject().put("status", "late_duplicate").toString(),
                    success = true,
                ),
            )
        }

        override fun failureResult(
            call: DynamicToolCallParams,
            code: String,
        ) = DynamicToolExecutionResult(
            JSONObject().put("status", "failed").put("errorCode", code).toString(),
            success = false,
        )
    }

    private inner class EffectThenCancelToolExecutor(
        private val heartbeat: TestCancellationHeartbeat,
    ) : DynamicToolExecutor {
        var effects = 0
        var handleCancellations = 0

        override val specs = RecordingToolExecutor().specs

        override fun execute(
            call: DynamicToolCallParams,
            completion: (DynamicToolExecutionResult) -> Unit,
        ) = error("cancellable path required")

        override fun executeCancellable(
            call: DynamicToolCallParams,
            cancellation: DynamicToolCancellation,
            completion: (DynamicToolExecutionResult) -> Unit,
        ): DynamicToolExecutionHandle {
            effects += 1
            heartbeat.cancel()
            return effectfulHandle { handleCancellations += 1 }
        }

        override fun failureResult(
            call: DynamicToolCallParams,
            code: String,
        ) = DynamicToolExecutionResult(
            JSONObject().put("status", "failed").put("errorCode", code).toString(),
            success = false,
        )
    }

    private inner class EffectfulNeverCompletingToolExecutor : DynamicToolExecutor {
        var handleCancellations = 0

        override val specs = RecordingToolExecutor().specs

        override fun execute(
            call: DynamicToolCallParams,
            completion: (DynamicToolExecutionResult) -> Unit,
        ) = error("cancellable path required")

        override fun executeCancellable(
            call: DynamicToolCallParams,
            cancellation: DynamicToolCancellation,
            completion: (DynamicToolExecutionResult) -> Unit,
        ): DynamicToolExecutionHandle = effectfulHandle { handleCancellations += 1 }

        override fun failureResult(
            call: DynamicToolCallParams,
            code: String,
        ) = DynamicToolExecutionResult(
            JSONObject().put("status", "failed").put("errorCode", code).toString(),
            success = false,
        )
    }

    private fun effectfulHandle(onCancel: () -> Unit) = object : DynamicToolExecutionHandle {
        private val cancelled = AtomicBoolean(false)

        override fun cancel(): DynamicToolCancellationDisposition {
            if (cancelled.compareAndSet(false, true)) onCancel()
            return DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED
        }
    }

    private class SteppingClock(private val step: Long) : MonotonicNanoClock {
        private var now = 0L
        override fun nowNanos(): Long {
            now += step
            return now
        }
    }

    private data class Fixture(
        val runner: OneShotIndependentAutomationCodexRunner,
        val session: FakeOneShotSession,
    )
}
