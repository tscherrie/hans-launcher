package ai.hans.standard.codex

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatedDynamicToolExecutorTest {
    @Test
    fun deniedTurnNeverReachesSensitiveDelegate() {
        val delegate = RecordingExecutor()
        val gated = GatedDynamicToolExecutor(
            delegate = delegate,
            denialCode = "background_automation_tool_forbidden",
            isAllowed = { false },
        )
        var completed: DynamicToolExecutionResult? = null

        gated.execute(call()) { completed = it }

        assertEquals(0, delegate.executionCount)
        assertFalse(checkNotNull(completed).success)
        assertEquals(
            "background_automation_tool_forbidden",
            JSONObject(checkNotNull(completed).contentText).getString("errorCode"),
        )
    }

    @Test
    fun allowedTurnExecutesDelegateExactlyOnce() {
        val delegate = RecordingExecutor()
        val gated = GatedDynamicToolExecutor(
            delegate = delegate,
            denialCode = "background_automation_tool_forbidden",
            isAllowed = { true },
        )
        var completed: DynamicToolExecutionResult? = null

        gated.execute(call()) { completed = it }

        assertEquals(1, delegate.executionCount)
        assertTrue(checkNotNull(completed).success)
    }

    @Test
    fun allowedCancellableTurnForwardsSignalAndHandleWithoutFallingBackToLegacyExecute() {
        val delegate = RecordingExecutor()
        val gated = GatedDynamicToolExecutor(
            delegate = delegate,
            denialCode = "background_automation_tool_forbidden",
            isAllowed = { true },
        )
        val cancellation = DynamicToolCancellation { false }

        val handle = gated.executeCancellable(call(), cancellation) {}

        assertEquals(0, delegate.executionCount)
        assertEquals(1, delegate.cancellableExecutionCount)
        assertTrue(delegate.lastCancellation === cancellation)
        assertTrue(handle === delegate.cancellableHandle)
    }

    @Test
    fun compositeForwardsCancellableExecutionToSelectedNamespaceDelegate() {
        val delegate = RecordingExecutor()
        val composite = CompositeDynamicToolExecutor(listOf(delegate))
        val cancellation = DynamicToolCancellation { false }

        val handle = composite.executeCancellable(call(), cancellation) {}

        assertEquals(0, delegate.executionCount)
        assertEquals(1, delegate.cancellableExecutionCount)
        assertTrue(delegate.lastCancellation === cancellation)
        assertTrue(handle === delegate.cancellableHandle)
    }

    @Test
    fun cancellationAfterCompletionClaimButBeforeCallbackEntrySuppressesCallback() {
        val secondCancellationCheckEntered = CountDownLatch(1)
        val releaseSecondCancellationCheck = CountDownLatch(1)
        val checks = AtomicInteger(0)
        val callbacks = AtomicInteger(0)
        val gate = DynamicToolExecutionGate(
            parentCancellation = DynamicToolCancellation {
                if (checks.incrementAndGet() == 2) {
                    secondCancellationCheckEntered.countDown()
                    releaseSecondCancellationCheck.await(1, TimeUnit.SECONDS)
                }
                false
            },
            completion = { callbacks.incrementAndGet() },
        )
        val worker = Thread(
            { gate.complete(DynamicToolExecutionResult("{\"status\":\"ok\"}", true)) },
            "dynamic-tool-completion-race-test",
        ).apply { isDaemon = true }

        worker.start()
        try {
            assertTrue(secondCancellationCheckEntered.await(1, TimeUnit.SECONDS))
            assertEquals(
                DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT,
                gate.cancel(),
            )
        } finally {
            releaseSecondCancellationCheck.countDown()
            worker.join(1_000)
        }

        assertFalse(worker.isAlive)
        assertEquals(0, callbacks.get())
    }

    private fun call() = DynamicToolCallParams(
        threadId = "thread-1",
        turnId = "turn-1",
        callId = "call-1",
        namespace = "interactive_test",
        tool = "sensitive_action",
        argumentsJson = "{}",
    )

    private class RecordingExecutor : DynamicToolExecutor {
        var executionCount = 0
        var cancellableExecutionCount = 0
        var lastCancellation: DynamicToolCancellation? = null
        val cancellableHandle = object : DynamicToolExecutionHandle {
            override fun cancel() =
                DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT
        }
        override val specs = listOf(
            DynamicToolNamespaceSpec(
                name = "interactive_test",
                description = "Sensitive test namespace.",
                tools = listOf(
                    DynamicToolFunctionSpec(
                        name = "sensitive_action",
                        description = "Test tool.",
                        inputSchemaJson = "{\"type\":\"object\"}",
                    ),
                ),
            ),
        )

        override fun execute(
            call: DynamicToolCallParams,
            completion: (DynamicToolExecutionResult) -> Unit,
        ) {
            executionCount += 1
            completion(DynamicToolExecutionResult("{\"status\":\"ok\"}", true))
        }

        override fun executeCancellable(
            call: DynamicToolCallParams,
            cancellation: DynamicToolCancellation,
            completion: (DynamicToolExecutionResult) -> Unit,
        ): DynamicToolExecutionHandle {
            cancellableExecutionCount += 1
            lastCancellation = cancellation
            completion(DynamicToolExecutionResult("{\"status\":\"ok\"}", true))
            return cancellableHandle
        }

        override fun failureResult(
            call: DynamicToolCallParams,
            code: String,
        ) = DynamicToolExecutionResult(
            JSONObject().put("errorCode", code).toString(),
            false,
        )
    }
}
