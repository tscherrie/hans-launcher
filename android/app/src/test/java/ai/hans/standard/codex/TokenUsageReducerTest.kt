package ai.hans.standard.codex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenUsageReducerTest {
    @Test
    fun missingUsageStaysUnknownAndMetricsCannotCreateOrSelectThreads() {
        val reducer = CodexSessionReducer()
        reducer.apply(delivery(0, event()))
        assertTrue(reducer.snapshot().threads.isEmpty())
        assertNull(reducer.snapshot().currentThreadId)
        start(reducer)
        assertNull(usage(reducer))
        reducer.apply(delivery(1, event(threadId = "foreign-thread")))
        reducer.apply(delivery(2, event(turnId = "unknown-turn")))
        assertEquals(1, reducer.snapshot().threads.size)
        assertNull(usage(reducer))
    }

    @Test
    fun cumulativeUpdatesReplaceRatherThanSumAndEqualPayloadIsDeduplicated() {
        val reducer = CodexSessionReducer()
        start(reducer)
        val first = event()
        reducer.apply(delivery(0, first))
        reducer.apply(delivery(1, first.copy()))
        assertSame(first.tokenUsage, usage(reducer))
        val next = first.copy(tokenUsage = usage(last = 2, total = 110))
        reducer.apply(delivery(2, next))
        assertEquals(2L, usage(reducer)?.last?.inputTokens)
        assertEquals(110L, usage(reducer)?.total?.inputTokens)
        assertEquals(
            DeliveryDisposition.DUPLICATE_IGNORED,
            reducer.apply(delivery(2, first)).disposition,
        )
        assertEquals(next.tokenUsage, usage(reducer))
    }

    @Test
    fun anyBackwardCumulativeCounterIsRejectedEvenWithLargerTotalTokens() {
        val reducer = CodexSessionReducer()
        start(reducer)
        val first = event().copy(tokenUsage = usage().let {
            it.copy(total = it.total.copy(cacheWriteInputTokens = 100))
        })
        reducer.apply(delivery(0, first))
        val previous = first.tokenUsage.total
        val backwards = listOf(
            previous.copy(inputTokens = 99, totalTokens = 101),
            previous.copy(cachedInputTokens = 99, totalTokens = 101),
            previous.copy(outputTokens = 99, totalTokens = 101),
            previous.copy(reasoningOutputTokens = 99, totalTokens = 101),
            previous.copy(totalTokens = 99),
            previous.copy(cacheWriteInputTokens = 99, totalTokens = 101),
        )
        backwards.forEachIndexed { index, counters ->
            reducer.apply(delivery(index + 1L, first.copy(tokenUsage = first.tokenUsage.copy(total = counters))))
            assertSame(first.tokenUsage, usage(reducer))
        }
    }

    @Test
    fun latestTurnCanReceiveFinalUsageButOlderAndForeignTurnsCannotOverwriteIt() {
        val reducer = CodexSessionReducer()
        start(reducer)
        reducer.apply(delivery(0, event()))
        reducer.apply(delivery(1, ServerEvent.TurnCompleted("thread-1", completed("turn-1"))))
        val final = event().copy(tokenUsage = usage(total = 110))
        reducer.apply(delivery(2, final))
        assertEquals(final.tokenUsage, usage(reducer))
        start(reducer, turnId = "turn-2")
        reducer.apply(delivery(3, event().copy(tokenUsage = usage(total = 500))))
        reducer.apply(delivery(4, event(turnId = "unknown-turn")))
        reducer.apply(delivery(5, ServerEvent.TurnCompleted("thread-1", completed("unknown-old-turn"))))
        assertEquals(final.tokenUsage, usage(reducer))
        val current = event(turnId = "turn-2").copy(tokenUsage = usage(last = 1, total = 120))
        reducer.apply(delivery(6, current))
        assertEquals(current.tokenUsage, usage(reducer))
    }

    @Test
    fun correlatedStartResponseCanEstablishUsageAfterAnEarlyCompletionWithoutRevivingTurn() {
        val reducer = CodexSessionReducer()
        reducer.apply(delivery(0, ServerEvent.TurnCompleted("thread-1", completed("turn-1"))))
        start(reducer)
        reducer.apply(delivery(1, event()))
        assertEquals(event().tokenUsage, usage(reducer))
        assertNull(reducer.snapshot().threads.single().currentTurn)
    }

    @Test
    fun explicitRuntimeChangeClearsUsageBeforeRehydrationHasAnyResponse() {
        val reducer = CodexSessionReducer()
        start(reducer)
        reducer.apply(delivery(0, event()))
        reducer.clearTokenUsageForRuntimeChange()
        assertNull(reducer.currentThreadTokenUsage())
        assertNull(usage(reducer))
        reducer.apply(delivery(1, event()))
        assertNull(usage(reducer))
    }

    @Test
    fun switchingSelectedThreadRetainsAtMostOneSnapshot() {
        val reducer = CodexSessionReducer()
        start(reducer)
        reducer.apply(delivery(0, event()))
        start(reducer, threadId = "thread-2", turnId = "turn-2")
        assertTrue(reducer.snapshot().threads.all { it.tokenUsage == null })
        reducer.apply(delivery(1, event()))
        assertTrue(reducer.snapshot().threads.all { it.tokenUsage == null })
        val selected = event(threadId = "thread-2", turnId = "turn-2")
        reducer.apply(delivery(2, selected))
        assertEquals(1, reducer.snapshot().threads.count { it.tokenUsage != null })
        assertEquals(selected.tokenUsage, usage(reducer))
    }

    @Test
    fun runtimeGenerationChangeClearsCountersAndRequiresFreshTurnCorrelation() {
        val reducer = CodexSessionReducer()
        start(reducer)
        reducer.apply(delivery(0, event()))
        assertEquals(
            DeliveryDisposition.REHYDRATION_REQUIRED,
            reducer.apply(delivery(0, event(), generation = 2)).disposition,
        )
        assertNull(usage(reducer))
        assertEquals(
            DeliveryDisposition.STALE_REJECTED,
            reducer.apply(delivery(1, event(), generation = 1)).disposition,
        )
        reducer.apply(success(AppServerMethod.ACCOUNT_READ, AccountReadResult(AccountIdentity.ApiKey, true)))
        reducer.apply(success(AppServerMethod.THREAD_RESUME, resumedThread()))
        reducer.confirmRehydratedGeneration(2)
        reducer.apply(delivery(0, event(), generation = 2))
        assertNull(usage(reducer))
        start(reducer, turnId = "turn-2")
        val fresh = event(turnId = "turn-2").copy(tokenUsage = usage(total = 1))
        reducer.apply(delivery(1, fresh, generation = 2))
        assertEquals(fresh.tokenUsage, usage(reducer))
    }

    @Test
    fun logoutAndSignedOutNotificationsOrReadsClearUsage() {
        repeat(3) { logoutPath ->
            val reducer = CodexSessionReducer()
            start(reducer)
            reducer.apply(delivery(0, event()))
            when (logoutPath) {
                0 -> reducer.apply(success(AppServerMethod.ACCOUNT_LOGOUT, AccountLogoutResult))
                1 -> reducer.apply(delivery(1, ServerEvent.AccountUpdated(null, null)))
                else -> reducer.apply(success(AppServerMethod.ACCOUNT_READ, AccountReadResult(null, true)))
            }
            assertNull(usage(reducer))
            reducer.apply(delivery(2, event()))
            assertNull(usage(reducer))
        }
    }

    private fun start(reducer: CodexSessionReducer, threadId: String = "thread-1", turnId: String = "turn-1") {
        reducer.apply(success(
            AppServerMethod.TURN_START,
            TurnStartResult(threadId, turnId, TurnStatus.IN_PROGRESS, DispatchOptions.DEFAULT),
        ))
    }

    private fun event(threadId: String = "thread-1", turnId: String = "turn-1") =
        ServerEvent.ThreadTokenUsageUpdated(threadId, turnId, usage())

    private fun usage(last: Long = 10, total: Long = 100) = ThreadTokenUsageSnapshot(
        TokenUsageBreakdown(last, last, last, last, last),
        TokenUsageBreakdown(total, total, total, total, total),
        400_000,
    )

    private fun usage(reducer: CodexSessionReducer): ThreadTokenUsageSnapshot? = reducer.snapshot().let { state ->
        state.threads.single { it.threadId == state.currentThreadId }.tokenUsage
    }

    private fun completed(turnId: String) = TurnSnapshot(turnId, TurnStatus.COMPLETED, null, 1, 2)

    private fun delivery(sequence: Long, event: ServerEvent, generation: Long = 1) =
        DeliveredServerEvent(DeliveryCursor(generation, sequence), event)

    private fun success(method: AppServerMethod, result: AppServerResult) =
        CorrelatedResponse.Success(RequestId.Number(100), method, result)

    private fun resumedThread() = ThreadResumeResult(
        thread = ThreadSummary("thread-1", null, "", 1, 2, ThreadStatusSnapshot(ThreadRuntimeStatus.IDLE)),
        effectiveModel = "gpt-5.6-luna",
        effectiveEffort = null,
        effectiveServiceTier = null,
        initialTurnReceipts = emptyList(),
        turnsBackwardsCursor = null,
        itemsBackwardsCursor = null,
    )
}
