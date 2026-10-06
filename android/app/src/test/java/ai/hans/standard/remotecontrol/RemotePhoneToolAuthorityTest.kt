package ai.hans.standard.remotecontrol

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolCancellationDisposition
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemotePhoneToolAuthorityTest {
    @Test fun absentConsentUnknownTurnsLocalThreadAndWrongGenerationCannotDispatch() {
        val h = Harness(allowed = false)
        h.start()
        assertError("remote_phone_tools_not_authorized", h.execute().single())
        h.allow()
        h.start(thread = "local", turn = "local-turn")
        h.start(thread = "other", turn = "wrong-generation", generation = 6)
        for (call in listOf(params(turn = "never-observed"), params(thread = "local", turn = "local-turn"),
            params(thread = "other", turn = "wrong-generation"))) {
            assertError("remote_phone_tools_not_authorized", h.execute(call).single())
        }
        assertTrue(h.executor.pending.isEmpty())
        assertFalse(h.authority.hasActiveWork)
    }

    @Test fun newlyObservedForeignTurnUsesExactExecutorAndUnmodifiedArguments() {
        val h = Harness()
        h.start()
        val call = params(arguments = "{\"userText\":\"hello\",\"threadId\":\"not-authority\"}")
        val results = h.execute(call)
        assertEquals(call, h.executor.pending.single().params)
        assertEquals(listOf("remote" to "turn-1"), h.authority.activeTurns)
        assertTrue(h.authority.hasActiveWork)
        h.executor.finish(0)
        assertEquals(listOf(SUCCESS), results)
        // The model may still be working even though its last phone tool finished.
        assertTrue(h.authority.hasActiveWork)
        h.complete()
        assertFalse(h.authority.hasActiveWork)
    }

    @Test fun startsSeenBeforeConsentCannotBeReplayedAfterConsent() {
        val h = Harness(allowed = false)
        h.start()
        h.allow()
        h.start()
        assertError("remote_phone_tools_not_authorized", h.execute().single())
        h.start(turn = "after-consent")
        assertTrue(h.execute(params(turn = "after-consent")).isEmpty())
        assertEquals(1, h.executor.pending.size)
    }

    @Test fun duplicateCallDuringExecutionAndChangedIdentityNeverDispatchAgain() {
        val h = Harness()
        h.start()
        h.execute()
        assertError("call_in_progress", h.execute().single())
        for (changed in listOf(params(arguments = "{\"different\":true}"),
            params().copy(tool = "other"), params().copy(namespace = "other"))) {
            assertError("call_identity_conflict", h.execute(changed).single())
        }
        assertEquals(1, h.executor.pending.size)
    }

    @Test fun completedCallReturnsCachedExactReplyAndRejectsChangedArguments() {
        val h = Harness()
        h.start()
        val first = h.execute()
        h.executor.finish(0)
        assertEquals(first, h.execute())
        assertError("call_identity_conflict", h.execute(params(arguments = "{\"x\":1}")).single())
        assertEquals(1, h.executor.pending.size)
    }

    @Test fun parallelTurnsHaveIndependentCancellationAndCallIdentity() {
        val h = Harness()
        h.start()
        h.start(thread = "second", turn = "turn-2")
        val first = h.execute()
        val second = h.execute(params(thread = "second", turn = "turn-2"))
        h.complete()
        assertError("cancelled", first.single())
        assertTrue(h.executor.pending[0].cancellation.isCancellationRequested())
        assertFalse(h.executor.pending[1].cancellation.isCancellationRequested())
        assertEquals(listOf("second" to "turn-2"), h.authority.activeTurns)
        h.executor.finish(1)
        assertEquals(listOf(SUCCESS), second)
    }

    @Test fun revocationImmediatelyCancelsLateRepliesAndDoesNotRearmOldTurns() {
        val h = Harness()
        h.start()
        val results = h.execute()
        h.allow(false)
        assertError("cancelled", results.single())
        assertTrue(results.single().contentText.contains("effect may already have occurred"))
        assertEquals(1, h.executor.pending[0].cancels)
        h.executor.finish(0)
        assertEquals(1, results.size)
        h.allow()
        h.start()
        assertError("remote_phone_tools_not_authorized", h.execute().single())
        h.start(turn = "new-turn")
        h.execute(params(turn = "new-turn"))
        assertEquals(2, h.executor.pending.size)
    }

    @Test fun runtimeLossTombstonesGenerationAndOnlyNewerRuntimeCanAuthorize() {
        val h = Harness()
        h.start()
        val results = h.execute()
        h.authority.updateState(RemotePhoneToolRuntime(null, false, "local"))
        assertError("cancelled", results.single())
        h.allow(generation = 7)
        h.start(turn = "late-start")
        assertError("remote_phone_tools_not_authorized", h.execute(params(turn = "late-start")).single())
        h.allow(generation = 8)
        h.start(generation = 8)
        h.execute()
        // A stale state callback must not revoke a newer runtime.
        h.allow(false, generation = 7)
        assertFalse(h.executor.pending.last().cancellation.isCancellationRequested())
        assertEquals(2, h.executor.pending.size)
    }

    @Test fun notificationRestrictionAndLocalThreadChangesRevokeImmutableLeases() {
        val h = Harness()
        h.start()
        val results = h.execute()
        val restricted = mutableSetOf("turn-1")
        h.authority.updateState(RemotePhoneToolRuntime(7, true, "local", restricted))
        restricted.clear()
        assertError("cancelled", results.single())
        h.start()
        assertError("remote_phone_tools_not_authorized", h.execute().single())
        h.start(turn = "unrestricted")
        val next = h.execute(params(turn = "unrestricted"))
        h.authority.updateState(RemotePhoneToolRuntime(7, true, "remote"))
        assertError("cancelled", next.single())
        h.allow()
        h.start(turn = "unrestricted")
        assertError("remote_phone_tools_not_authorized", h.execute(params(turn = "unrestricted")).single())
    }

    @Test fun cancelAllTombstonesCurrentTurnsAndCloseCannotBeReopened() {
        val h = Harness()
        h.start()
        val results = h.execute()
        h.authority.cancelAll()
        assertError("cancelled", results.single())
        h.start()
        assertError("remote_phone_tools_not_authorized", h.execute().single())
        h.start(turn = "new")
        h.execute(params(turn = "new"))
        h.authority.close()
        h.authority.close()
        h.allow(generation = 8)
        h.start(generation = 8, turn = "after-close")
        assertError("remote_phone_tools_not_authorized", h.execute(params(turn = "after-close")).single())
        assertFalse(h.authority.hasActiveWork)
    }

    @Test fun parentCancellationIsRecheckedAtEffectBoundariesAndFailsClosedOnSignalError() {
        val h = Harness()
        h.start()
        val cancelled = AtomicBoolean(false)
        val results = h.execute(cancellation = DynamicToolCancellation(cancelled::get))
        assertFalse(h.executor.pending[0].cancellation.isCancellationRequested())
        cancelled.set(true)
        assertTrue(h.executor.pending[0].cancellation.isCancellationRequested())
        h.executor.finish(0)
        assertError("cancelled", results.single())
        assertError("cancelled", h.execute(params(call = "bad-signal"),
            DynamicToolCancellation { error("unavailable") }).single())
        assertEquals(1, h.executor.pending.size)
    }

    @Test fun handleReturnedAfterRevocationIsCancelledAndCallbackFiresOnce() {
        val h = Harness()
        h.start()
        h.executor.afterDispatch = { h.allow(false) }
        val results = h.execute()
        assertError("cancelled", results.single())
        assertEquals(1, h.executor.pending.single().cancels)
        h.executor.finish(0)
        assertEquals(1, results.size)
    }

    @Test fun brokenExecutorDoubleCallbackAndThrowCannotDeliverTwice() {
        val h = Harness()
        h.start()
        h.executor.afterDispatch = {
            h.executor.finish(0)
            h.executor.finish(0)
            error("dispatch threw after completion")
        }
        val results = h.execute()
        assertEquals(listOf(SUCCESS), results)
        assertEquals(listOf(SUCCESS), h.execute())
        val throwing = Harness()
        throwing.start()
        throwing.executor.afterDispatch = { error("private diagnostic") }
        val failure = throwing.execute().single()
        assertError("execution_result_unknown", failure)
        assertFalse(failure.contentText.contains("private diagnostic"))
    }

    @Test fun liveTurnAndConcurrentCallLimitsDoNotEvictActiveWork() {
        val h = Harness()
        repeat(33) { h.start(thread = "thread-$it", turn = "turn-$it") }
        assertEquals(32, h.authority.activeTurns.size)
        assertError("remote_phone_tools_not_authorized",
            h.execute(params(thread = "thread-32", turn = "turn-32")).single())
        repeat(8) { h.execute(params(thread = "thread-$it", turn = "turn-$it")) }
        val ninth = params(thread = "thread-8", turn = "turn-8")
        assertError("remote_phone_tools_busy", h.execute(ninth).single())
        assertEquals(8, h.executor.pending.size)
        h.executor.finish(0)
        assertTrue(h.execute(ninth).isEmpty())
        assertEquals(9, h.executor.pending.size)
        assertTrue(h.authority.activeTurns.contains("thread-0" to "turn-0"))
    }

    @Test fun perTurnCallLimitRetainsOriginalReplayProtection() {
        val h = Harness()
        h.start()
        repeat(256) { index ->
            h.execute(params(call = "call-$index"))
            h.executor.finish(index)
        }
        assertError("turn_call_limit", h.execute(params(call = "overflow")).single())
        assertEquals(listOf(SUCCESS), h.execute(params(call = "call-0")))
        assertError("call_identity_conflict", h.execute(params(call = "call-0", arguments = "{\"x\":1}")).single())
        assertEquals(256, h.executor.pending.size)
    }

    @Test fun oversizedCacheReplyIsDeliveredOnceButReplayNeverReexecutesIt() {
        val h = Harness()
        h.start()
        val results = h.execute()
        val large = DynamicToolExecutionResult(JSONObject().put("text", "x".repeat(300_000)).toString(), true)
        h.executor.finish(0, large)
        assertEquals(listOf(large), results)
        assertError("call_result_not_cached", h.execute().single())
        assertEquals(1, h.executor.pending.size)
    }

    @Test fun exhaustedTurnTombstonesFailClosedUntilNewGeneration() {
        val h = Harness()
        repeat(4_096) { index ->
            h.start(turn = "past-$index")
            h.complete(turn = "past-$index")
        }
        h.start(turn = "over-limit")
        assertError("remote_phone_tools_not_authorized", h.execute(params(turn = "over-limit")).single())
        h.start(turn = "past-0")
        assertFalse(h.authority.hasActiveWork)
        h.allow(generation = 8)
        h.start(generation = 8)
        assertTrue(h.execute().isEmpty())
        assertEquals(1, h.executor.pending.size)
    }

    @Test fun malformedEventsFinishedStartsAndThreadClosureNeverAuthorizeNewCalls() {
        val h = Harness()
        for (raw in listOf("not json", "x".repeat(1_048_577),
            event("turn/started", "remote", "turn-1", "completed"),
            JSONObject(event("turn/started", "remote", "turn-1")).put("id", "request").toString(),
            "{\"method\":\"turn/started\",\"params\":{\"threadId\":\"remote\",\"turnId\":\"turn-1\"}}")) {
            h.authority.onEvent(7, raw)
        }
        assertError("remote_phone_tools_not_authorized", h.execute().single())
        h.complete()
        h.start()
        assertError("remote_phone_tools_not_authorized", h.execute().single())
        h.start(turn = "live")
        val results = h.execute(params(turn = "live"))
        val largeCompletion = JSONObject(event("turn/completed", "remote", "live", "completed"))
        largeCompletion.getJSONObject("params").getJSONObject("turn").put("detail", "x".repeat(1_100_000))
        h.authority.onEvent(7, largeCompletion.toString())
        assertError("cancelled", results.single())
        h.start(turn = "before-archive")
        val archived = h.execute(params(turn = "before-archive"))
        h.authority.onEvent(7, event("thread/archived", "remote"))
        assertError("cancelled", archived.single())
        h.start(turn = "late-after-archive")
        assertError("remote_phone_tools_not_authorized", h.execute(params(turn = "late-after-archive")).single())
    }

    @Test fun executorAndCompletionCallbacksNeverRunUnderAuthorityMonitor() {
        val h = Harness()
        h.start()
        var executorOutsideMonitor = false
        var callbackOutsideMonitor = false
        h.executor.afterDispatch = {
            executorOutsideMonitor = otherThreadCanRun { h.authority.activeTurns }
        }
        h.authority.execute(params(), DynamicToolCancellation.NONE) {
            callbackOutsideMonitor = otherThreadCanRun { h.authority.updateState(RemotePhoneToolRuntime(7, true, "local")) }
        }
        h.executor.finish(0)
        assertTrue(executorOutsideMonitor)
        assertTrue(callbackOutsideMonitor)
        var signalOutsideMonitor = false
        h.execute(params(call = "signal"), DynamicToolCancellation {
            signalOutsideMonitor = otherThreadCanRun { h.authority.activeTurns }
            false
        })
        assertTrue(signalOutsideMonitor)
    }

    @Test fun callerCancellationIsIdempotentConservativeAndThrowingCallbackIsContained() {
        val h = Harness()
        h.start()
        var delivered = 0
        val handle = h.authority.execute(params(), DynamicToolCancellation.NONE) {
            delivered++
            error("consumer disconnected")
        }
        assertEquals(DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED, handle.cancel())
        assertEquals(DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED, handle.cancel())
        h.executor.finish(0)
        assertEquals(1, delivered)
        assertError("cancelled", h.execute().single())
        val denied = h.authority.execute(params(turn = "unknown"), DynamicToolCancellation.NONE) {}
        assertEquals(DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT, denied.cancel())
    }

    private class Harness(allowed: Boolean = true) {
        val executor = RecordingExecutor()
        val authority = RemotePhoneToolAuthority(executor)
        init { allow(allowed) }
        fun allow(value: Boolean = true, generation: Long = 7) =
            authority.updateState(RemotePhoneToolRuntime(generation, value, "local"))
        fun start(thread: String = "remote", turn: String = "turn-1", generation: Long = 7) =
            authority.onEvent(generation, event("turn/started", thread, turn))
        fun complete(thread: String = "remote", turn: String = "turn-1") =
            authority.onEvent(7, event("turn/completed", thread, turn, "completed"))
        fun execute(call: DynamicToolCallParams = params(), cancellation: DynamicToolCancellation = DynamicToolCancellation.NONE): MutableList<DynamicToolExecutionResult> =
            mutableListOf<DynamicToolExecutionResult>().also { results -> authority.execute(call, cancellation, results::add) }
    }

    private class RecordingExecutor : DynamicToolExecutor {
        override val specs = emptyList<DynamicToolNamespaceSpec>()
        val pending = mutableListOf<Pending>()
        var afterDispatch: (() -> Unit)? = null
        override fun execute(call: DynamicToolCallParams, completion: (DynamicToolExecutionResult) -> Unit) =
            error("Must use cancellable executor")
        override fun executeCancellable(call: DynamicToolCallParams, cancellation: DynamicToolCancellation,
            completion: (DynamicToolExecutionResult) -> Unit): DynamicToolExecutionHandle {
            val next = Pending(call, cancellation, completion)
            pending += next
            afterDispatch?.invoke()
            return object : DynamicToolExecutionHandle {
                override fun cancel(): DynamicToolCancellationDisposition {
                    next.cancels++
                    return DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED
                }
            }
        }
        override fun failureResult(call: DynamicToolCallParams, code: String) =
            DynamicToolExecutionResult(JSONObject().put("code", code).toString(), false)
        fun finish(index: Int, result: DynamicToolExecutionResult = SUCCESS) = pending[index].completion(result)
    }

    private class Pending(val params: DynamicToolCallParams, val cancellation: DynamicToolCancellation,
        val completion: (DynamicToolExecutionResult) -> Unit) {
        var cancels = 0
    }

    private companion object {
        val SUCCESS = DynamicToolExecutionResult("{\"ok\":true}", true)
        fun params(thread: String = "remote", turn: String = "turn-1", call: String = "call-1", arguments: String = "{}") =
            DynamicToolCallParams(thread, turn, call, "android", "read_screen", arguments)
        fun event(method: String, thread: String, turn: String? = null, status: String = "inProgress") =
            JSONObject().put("method", method).put("params", JSONObject().put("threadId", thread).apply {
                turn?.let { put("turn", JSONObject().put("id", it).put("status", status)) }
            }).toString()
        fun assertError(code: String, result: DynamicToolExecutionResult) {
            assertFalse(result.success)
            assertEquals(code, JSONObject(result.contentText).getJSONObject("error").getString("code"))
        }
        fun otherThreadCanRun(block: () -> Unit): Boolean {
            val completed = CountDownLatch(1)
            val worker = Thread { try { block() } finally { completed.countDown() } }
            worker.isDaemon = true
            worker.start()
            return completed.await(2, TimeUnit.SECONDS)
        }
    }
}
