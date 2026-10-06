package ai.hans.standard.remotecontrol

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolCancellationDisposition
import ai.hans.standard.codex.DynamicToolExecutionGate
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CrossTurnPhoneToolFenceTest {
    @Test fun actualQuiescenceNotifiesHostOnceOutsideFenceMonitor() {
        var changes = 0
        var callbackCouldReadFence = false
        lateinit var fence: CrossTurnPhoneToolFence
        fence = CrossTurnPhoneToolFence({}, {
            changes++
            val done = CountDownLatch(1)
            Thread { fence.hasActiveWork; done.countDown() }.start()
            callbackCouldReadFence = done.await(2, TimeUnit.SECONDS)
        })
        val pending = PendingExecutor()
        val handle = fence.wrap(pending).executeCancellable(call(), DynamicToolCancellation.NONE) {}
        handle.cancel()
        assertEquals(0, changes)
        pending.gate.complete(OK)
        pending.gate.complete(OK)
        assertEquals(1, changes)
        assertTrue(callbackCouldReadFence)
    }

    @Test fun separateLeasedExecutorsShareOneSlotWithNoQueue() {
        val fence = CrossTurnPhoneToolFence {}
        val first = QueuedExecutor()
        val second = QueuedExecutor()
        fence.wrap(first).execute(call()) {}
        val denied = resultOf(fence.wrap(second), call(thread = "B"))
        assertCode("phone_tools_busy", denied.single())
        assertTrue(second.jobs.isEmpty())
        assertTrue(fence.hasActiveWork)
        first.jobs.single().run()
        assertFalse(fence.hasActiveWork)
        assertTrue(resultOf(fence.wrap(second), call(thread = "B")).isEmpty())
        assertEquals(1, second.jobs.size)
    }

    @Test fun sameTurnRetainsEvidenceButThreadAndTurnSwitchesInvalidate() {
        var invalidations = 0
        val fence = CrossTurnPhoneToolFence { invalidations++ }
        val wrapped = fence.wrap(SynchronousExecutor())
        repeat(2) { resultOf(wrapped, call(id = "a-$it")) }
        assertEquals(1, invalidations)
        resultOf(wrapped, call(turn = "next-turn"))
        resultOf(wrapped, call(thread = "B", turn = "next-turn"))
        assertEquals(3, invalidations)
    }

    @Test fun publicChatChangeInvalidatesOldSendAndVisualReceiptBeforeReturningToA() {
        var retainedFrame = false
        var visualReceipt = false
        var sent = 0
        var currentRecipient = "A"
        val fence = CrossTurnPhoneToolFence { retainedFrame = false; visualReceipt = false }
        val executor = object : SynchronousExecutor() {
            override fun execute(call: DynamicToolCallParams, completion: (DynamicToolExecutionResult) -> Unit) {
                when (call.tool) {
                    "inspect" -> { retainedFrame = true; visualReceipt = true; completion(OK) }
                    "open_chat" -> { currentRecipient = "B"; completion(OK) }
                    "send", "fallback" -> {
                        val valid = if (call.tool == "send") retainedFrame else visualReceipt
                        if (valid) sent++
                        completion(DynamicToolExecutionResult("{\"retained\":$valid}", valid))
                    }
                }
            }
        }
        val wrapped = fence.wrap(executor)
        resultOf(wrapped, call(tool = "inspect"))
        resultOf(wrapped, call(thread = "B", tool = "open_chat"))
        assertFalse(resultOf(wrapped, call(tool = "send")).single().success)
        assertFalse(resultOf(wrapped, call(tool = "fallback")).single().success)
        assertEquals("B", currentRecipient)
        assertEquals(0, sent)
    }

    @Test fun cancellingQueuedWorkFreesSlotAndNeverRunsTheCancelledTask() {
        val fence = CrossTurnPhoneToolFence {}
        val queued = QueuedExecutor()
        val wrapped = fence.wrap(queued)
        val handle = wrapped.executeCancellable(call(), DynamicToolCancellation.NONE) {}
        handle.cancel()
        assertFalse(fence.hasActiveWork)
        resultOf(wrapped, call(thread = "B"))
        assertEquals(2, queued.jobs.size)
        queued.jobs[0].run()
        assertEquals(0, queued.executed)
        queued.jobs[1].run()
        assertEquals(1, queued.executed)
    }

    @Test fun runningReadOnlyRetentionAfterBeforeEffectCancelMustFinishBeforeNextOwner() {
        var retainedByA = false
        val fence = CrossTurnPhoneToolFence { retainedByA = false }
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val done = CountDownLatch(1)
        val queued = QueuedExecutor(beforeCompletion = {
            entered.countDown()
            finish.await(2, TimeUnit.SECONDS)
            retainedByA = true
        })
        val wrapped = fence.wrap(queued)
        val handle = wrapped.executeCancellable(call(), DynamicToolCancellation.NONE) {}
        Thread { queued.jobs[0].run(); done.countDown() }.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        handle.cancel()
        assertCode("phone_tools_busy", resultOf(wrapped, call(thread = "B")).single())
        finish.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertTrue(retainedByA)
        resultOf(fence.wrap(SynchronousExecutor()), call(thread = "B"))
        assertFalse(retainedByA)
    }

    @Test fun pendingConfirmationMayCancelKeepsSlotUntilRealCompletion() {
        val fence = CrossTurnPhoneToolFence {}
        val pending = PendingExecutor()
        val wrapped = fence.wrap(pending)
        var results = 0
        val handle = wrapped.executeCancellable(call(), DynamicToolCancellation.NONE) { results++ }
        assertEquals(DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED, handle.cancel())
        assertCode("phone_tools_busy", resultOf(wrapped, call(thread = "B")).single())
        pending.gate.complete(OK)
        assertEquals(0, results)
        assertFalse(fence.hasActiveWork)
    }

    @Test fun callbackBeforeHandleAndReentrantCallbackKeepSlotUntilDelegateTailFinishes() {
        val fence = CrossTurnPhoneToolFence {}
        val observed = mutableListOf<DynamicToolExecutionResult>()
        lateinit var wrapped: DynamicToolExecutor
        val executor = object : SynchronousExecutor() {
            override fun execute(call: DynamicToolCallParams, completion: (DynamicToolExecutionResult) -> Unit) {
                completion(OK)
                observed += resultOf(wrapped, call(thread = "B", id = "tail"))
            }
        }
        wrapped = fence.wrap(executor)
        wrapped.execute(call()) { observed += resultOf(wrapped, call(thread = "B", id = "callback")) }
        assertEquals(2, observed.size)
        observed.forEach { assertCode("phone_tools_busy", it) }
        assertFalse(fence.hasActiveWork)
    }

    @Test fun handlePublishedAfterParentCancellationIsCancelledAndQuiescenceObserved() {
        val fence = CrossTurnPhoneToolFence {}
        val cancelled = AtomicBoolean()
        val pending = PendingExecutor(afterGate = { cancelled.set(true) })
        val wrapped = fence.wrap(pending)
        wrapped.executeCancellable(call(), DynamicToolCancellation(cancelled::get)) {}
        assertTrue(pending.gate.isCancellationRequested())
        assertTrue(fence.hasActiveWork)
        pending.gate.complete(OK)
        assertFalse(fence.hasActiveWork)
    }

    @Test fun nestedGateCannotReleaseOuterPendingReceipt() {
        val fence = CrossTurnPhoneToolFence {}
        val pending = PendingExecutor()
        fence.wrap(pending).execute(call()) {}
        val inner = DynamicToolExecutionGate(DynamicToolCancellation.NONE) {}
        inner.complete(OK)
        assertTrue(fence.hasActiveWork)
        pending.gate.complete(OK)
        assertFalse(fence.hasActiveWork)
    }

    @Test fun invalidationFailureNeverDispatchesAndRetriesInvalidationNextTime() {
        var tries = 0
        val fence = CrossTurnPhoneToolFence { if (++tries == 1) error("unavailable") }
        val actual = SynchronousExecutor()
        val wrapped = fence.wrap(actual)
        assertCode("phone_ui_invalidation_failed", resultOf(wrapped, call()).single())
        assertFalse(fence.hasActiveWork)
        assertTrue(resultOf(wrapped, call()).single().success)
        assertEquals(2, tries)
    }

    @Test fun unknownQuiescenceRemainsBusyUntilActualLegacyCompletion() {
        val fence = CrossTurnPhoneToolFence {}
        lateinit var callback: (DynamicToolExecutionResult) -> Unit
        val executor = object : SynchronousExecutor() {
            override fun executeCancellable(call: DynamicToolCallParams, cancellation: DynamicToolCancellation,
                completion: (DynamicToolExecutionResult) -> Unit): DynamicToolExecutionHandle {
                callback = completion
                return object : DynamicToolExecutionHandle {
                    override fun cancel() = DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED
                }
            }
        }
        val handle = fence.wrap(executor).executeCancellable(call(), DynamicToolCancellation.NONE) {}
        handle.cancel()
        assertTrue(fence.hasActiveWork)
        callback(OK)
        assertFalse(fence.hasActiveWork)
    }

    @Test fun duplicateCallbacksAndThrowingConsumerNeverDoubleReleaseAnotherInvocation() {
        val fence = CrossTurnPhoneToolFence {}
        val pending = PendingExecutor()
        val wrapped = fence.wrap(pending)
        wrapped.execute(call()) { error("consumer disconnected") }
        val first = pending.gate
        first.complete(OK)
        wrapped.execute(call(thread = "B")) {}
        first.complete(OK)
        assertTrue(fence.hasActiveWork)
        pending.gate.complete(OK)
        assertFalse(fence.hasActiveWork)
    }

    private open class SynchronousExecutor : DynamicToolExecutor {
        override val specs = emptyList<DynamicToolNamespaceSpec>()
        override fun execute(call: DynamicToolCallParams, completion: (DynamicToolExecutionResult) -> Unit) = completion(OK)
        override fun failureResult(call: DynamicToolCallParams, code: String) = DynamicToolExecutionResult("{\"errorCode\":\"$code\"}", false)
    }
    private class QueuedExecutor(private val beforeCompletion: () -> Unit = {}) : SynchronousExecutor() {
        val jobs = mutableListOf<Runnable>()
        var executed = 0
        override fun executeCancellable(call: DynamicToolCallParams, cancellation: DynamicToolCancellation,
            completion: (DynamicToolExecutionResult) -> Unit): DynamicToolExecutionHandle {
            val gate = DynamicToolExecutionGate(cancellation, completion)
            gate.schedule(Executor(jobs::add)) { executed++; beforeCompletion(); gate.complete(OK) }
            return gate
        }
    }
    private class PendingExecutor(private val afterGate: () -> Unit = {}) : SynchronousExecutor() {
        lateinit var gate: DynamicToolExecutionGate
        override fun executeCancellable(call: DynamicToolCallParams, cancellation: DynamicToolCancellation,
            completion: (DynamicToolExecutionResult) -> Unit): DynamicToolExecutionHandle {
            gate = DynamicToolExecutionGate(cancellation, completion)
            gate.markExternalEffectStarted()
            afterGate()
            return gate
        }
    }
    private companion object {
        val OK = DynamicToolExecutionResult("{\"ok\":true}", true)
        fun call(thread: String = "A", turn: String = "turn", id: String = "call", tool: String = "inspect") =
            DynamicToolCallParams(thread, turn, id, "android_ui", tool, "{}")
        fun resultOf(executor: DynamicToolExecutor, call: DynamicToolCallParams): List<DynamicToolExecutionResult> =
            mutableListOf<DynamicToolExecutionResult>().also { results -> executor.execute(call, results::add) }
        fun assertCode(code: String, result: DynamicToolExecutionResult) {
            assertFalse(result.success)
            assertEquals(code, JSONObject(result.contentText).getString("errorCode"))
        }
    }
}
