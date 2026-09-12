package ai.hans.standard.codex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReducerTest {
    @Test
    fun accountLoginCompletionAndUpdateProduceUiSafeState() {
        val reducer = CodexSessionReducer()
        reducer.apply(
            success(
                method = AppServerMethod.ACCOUNT_LOGIN_START,
                result = DeviceCodeLoginResult(
                    loginId = "login-1",
                    userCode = "ABCD-EFGH",
                    verificationUrl = "https://auth.openai.test/device",
                ),
            ),
        )
        assertEquals(AccountPhase.LOGIN_PENDING, reducer.snapshot().account.phase)

        reducer.apply(
            delivery(
                sequence = 0,
                event = ServerEvent.AccountLoginCompleted(
                    success = true,
                    loginId = "login-1",
                    error = null,
                ),
            ),
        )
        val signedIn = reducer.apply(
            delivery(
                sequence = 1,
                event = ServerEvent.AccountUpdated(
                    authMode = AccountAuthMode.CHATGPT,
                    planType = AccountPlanType.PRO,
                ),
            ),
        ).snapshot.account

        assertEquals(AccountPhase.SIGNED_IN, signedIn.phase)
        assertEquals(AccountAuthMode.CHATGPT, signedIn.authMode)
        assertEquals(AccountPlanType.PRO, signedIn.planType)
        assertNull(signedIn.pendingLoginId)
    }

    @Test
    fun identicalLegitimateDeltasSurviveButExactCursorDuplicatesDoNot() {
        val reducer = CodexSessionReducer()
        val first = ServerEvent.AgentMessageDelta(
            threadId = "thread-1",
            turnId = "turn-1",
            itemId = "message-1",
            delta = "ha",
        )
        assertEquals(
            DeliveryDisposition.APPLIED,
            reducer.apply(delivery(0, first)).disposition,
        )
        assertEquals(
            DeliveryDisposition.APPLIED,
            reducer.apply(delivery(1, first)).disposition,
        )
        assertEquals("haha", reducer.message("thread-1").text)

        val duplicateCursorWithDifferentPayload = first.copy(delta = " MUST_NOT_APPEAR")
        assertEquals(
            DeliveryDisposition.DUPLICATE_IGNORED,
            reducer.apply(delivery(1, duplicateCursorWithDifferentPayload)).disposition,
        )
        assertEquals("haha", reducer.message("thread-1").text)

        assertEquals(
            DeliveryDisposition.STALE_REJECTED,
            reducer.apply(delivery(0, first)).disposition,
        )
        assertEquals("haha", reducer.message("thread-1").text)
    }

    @Test
    fun completedItemIsAuthoritativeAcrossReorderedStartAndLateDelta() {
        val reducer = CodexSessionReducer()
        reducer.apply(
            delivery(
                0,
                ServerEvent.ItemCompleted(
                    threadId = "thread-1",
                    turnId = "turn-1",
                    item = StreamItem.AgentMessage(
                        id = "message-1",
                        text = "Authoritative final text",
                        phase = AgentMessagePhase.FINAL_ANSWER,
                    ),
                    completedAtMillis = 200,
                ),
            ),
        )
        reducer.apply(
            delivery(
                1,
                ServerEvent.ItemStarted(
                    threadId = "thread-1",
                    turnId = "turn-1",
                    item = StreamItem.AgentMessage(
                        id = "message-1",
                        text = "stale",
                        phase = AgentMessagePhase.COMMENTARY,
                    ),
                    startedAtMillis = 100,
                ),
            ),
        )
        reducer.apply(
            delivery(
                2,
                ServerEvent.AgentMessageDelta(
                    threadId = "thread-1",
                    turnId = "turn-1",
                    itemId = "message-1",
                    delta = " stale delta",
                ),
            ),
        )

        val message = reducer.message("thread-1")
        assertEquals("Authoritative final text", message.text)
        assertEquals(AgentMessagePhase.FINAL_ANSWER, message.phase)
        assertTrue(message.complete)
    }

    @Test
    fun terminalTurnAndToolCannotBeRolledBackByDelayedStart() {
        val reducer = CodexSessionReducer()
        reducer.apply(
            delivery(
                0,
                ServerEvent.TurnCompleted(
                    "thread-1",
                    TurnSnapshot("turn-1", TurnStatus.COMPLETED, null, 1, 2),
                ),
            ),
        )
        reducer.apply(
            delivery(
                1,
                ServerEvent.TurnStarted(
                    "thread-1",
                    TurnSnapshot("turn-1", TurnStatus.IN_PROGRESS, null, 1, null),
                ),
            ),
        )
        reducer.apply(
            delivery(
                2,
                ServerEvent.ItemCompleted(
                    threadId = "thread-1",
                    turnId = "turn-1",
                    item = StreamItem.Command(
                        id = "command-1",
                        command = "echo done",
                        status = ToolStatus.COMPLETED,
                        aggregatedOutput = "done",
                    ),
                    completedAtMillis = 2,
                ),
            ),
        )
        reducer.apply(
            delivery(
                3,
                ServerEvent.ItemStarted(
                    threadId = "thread-1",
                    turnId = "turn-1",
                    item = StreamItem.Command(
                        id = "command-1",
                        command = "echo stale",
                        status = ToolStatus.IN_PROGRESS,
                        aggregatedOutput = null,
                    ),
                    startedAtMillis = 1,
                ),
            ),
        )

        val thread = reducer.thread("thread-1")
        assertNull(thread.currentTurn)
        assertEquals(ThreadRuntimeStatus.IDLE, thread.runtimeStatus.status)
        val tool = thread.tools.single()
        assertEquals(ToolStatus.COMPLETED, tool.status)
        assertEquals("echo done", tool.label)
        assertEquals("done", tool.output)
        assertTrue(tool.complete)
    }

    @Test
    fun newGenerationRequiresAccountAndCurrentThreadRehydrationBeforeReplay() {
        val reducer = CodexSessionReducer()
        reducer.apply(
            success(
                method = AppServerMethod.THREAD_START,
                result = ThreadStartResult(
                    threadId = "thread-1",
                    effectiveModel = "gpt-5.6-luna",
                    effectiveEffort = null,
                    effectiveServiceTier = null,
                    requestedOptions = DispatchOptions.DEFAULT,
                ),
            ),
        )
        reducer.apply(
            delivery(
                sequence = 0,
                generation = 1,
                event = ServerEvent.AgentMessageDelta(
                    "thread-1",
                    "turn-1",
                    "message-1",
                    "old",
                ),
            ),
        )

        val newGenerationEvent = delivery(
            sequence = 0,
            generation = 2,
            event = ServerEvent.AgentMessageDelta(
                "thread-1",
                "turn-2",
                "message-2",
                "new",
            ),
        )
        val pending = reducer.apply(newGenerationEvent)
        assertEquals(DeliveryDisposition.REHYDRATION_REQUIRED, pending.disposition)
        assertTrue(pending.snapshot.delivery.rehydrationRequired)
        assertEquals("old", reducer.message("thread-1").text)

        assertThrows(CrossCorrelationException::class.java) {
            reducer.confirmRehydratedGeneration(2)
        }
        reducer.apply(
            success(
                method = AppServerMethod.ACCOUNT_READ,
                result = AccountReadResult(
                    account = AccountIdentity.ChatGpt("person@example.test", "pro"),
                    requiresOpenAiAuth = true,
                ),
            ),
        )
        assertThrows(CrossCorrelationException::class.java) {
            reducer.confirmRehydratedGeneration(2)
        }
        reducer.apply(
            success(
                method = AppServerMethod.THREAD_RESUME,
                result = ThreadResumeResult(
                    thread = ThreadSummary(
                        id = "thread-1",
                        name = "Hans",
                        preview = "Recovered",
                        createdAtSeconds = 1,
                        updatedAtSeconds = 2,
                        status = ThreadStatusSnapshot(ThreadRuntimeStatus.IDLE),
                    ),
                    effectiveModel = "gpt-5.6-luna",
                    effectiveEffort = ReasoningEffort.MAX,
                    effectiveServiceTier = null,
                    initialTurnReceipts = emptyList(),
                    turnsBackwardsCursor = null,
                    itemsBackwardsCursor = null,
                ),
            ),
        )
        val hydrated = reducer.confirmRehydratedGeneration(2)
        assertFalse(hydrated.delivery.rehydrationRequired)
        assertEquals(2L, hydrated.delivery.generation)

        val replayed = reducer.apply(newGenerationEvent)
        assertEquals(DeliveryDisposition.APPLIED, replayed.disposition)
        assertEquals("new", reducer.thread("thread-1").messages.last().text)
    }

    @Test
    fun skippedRuntimeCallbacksDoNotLookLikeMissingServerEvents() {
        val reducer = CodexSessionReducer()
        // Runtime callback sequences 2 (READY) and 3 (account response) do not
        // enter the notification reducer. The next notification is still safe.
        reducer.apply(
            delivery(
                1,
                ServerEvent.AgentMessageDelta("thread-1", "turn-1", "message-1", "safe"),
            ),
        )
        reducer.apply(
            success(
                method = AppServerMethod.ACCOUNT_READ,
                result = AccountReadResult(null, requiresOpenAiAuth = true),
            ),
        )
        val afterReadyAndResponse = reducer.apply(
            delivery(
                4,
                ServerEvent.AgentMessageDelta("thread-1", "turn-1", "message-1", " continuation"),
            ),
        )

        assertEquals(DeliveryDisposition.APPLIED, afterReadyAndResponse.disposition)
        assertFalse(afterReadyAndResponse.snapshot.delivery.rehydrationRequired)
        assertEquals("safe continuation", reducer.message("thread-1").text)
    }

    private fun delivery(
        sequence: Long,
        event: ServerEvent,
        generation: Long = 1,
    ): DeliveredServerEvent = DeliveredServerEvent(DeliveryCursor(generation, sequence), event)

    private fun success(
        method: AppServerMethod,
        result: AppServerResult,
    ): CorrelatedResponse.Success = CorrelatedResponse.Success(
        id = RequestId.Number(100),
        method = method,
        result = result,
    )

    private fun CodexSessionReducer.thread(id: String): ThreadUiSnapshot =
        snapshot().threads.single { it.threadId == id }

    private fun CodexSessionReducer.message(threadId: String): MessageUiSnapshot =
        thread(threadId).messages.single()
}
