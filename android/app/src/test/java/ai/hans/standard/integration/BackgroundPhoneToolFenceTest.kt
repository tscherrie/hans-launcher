package ai.hans.standard.integration

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolCancellationDisposition
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolFunctionSpec
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import ai.hans.standard.remotecontrol.CrossTurnPhoneToolFence
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPhoneToolFenceTest {
    @Test
    fun backgroundLeaseCancellationDoesNotReleaseTheSharedPhoneUntilPhysicalQuiescence() {
        val delegate = PhysicallyPendingExecutor()
        val snapshot = RevisionedDynamicToolSnapshot.create(
            listOf(DynamicToolContributor(delegate, DynamicToolPlacement.BACKGROUND_ALLOWED)),
        )
        val lease = RevisionedDynamicToolRouter(snapshot).acquire()
        var invalidations = 0
        var physicalSettlements = 0
        val fence = CrossTurnPhoneToolFence(
            invalidateRetainedUi = { invalidations++ },
            onQuiescent = { physicalSettlements++ },
        )
        val main = fence.wrap(lease)
        val background = fence.wrap(lease.backgroundExecutor())
        val backgroundResults = mutableListOf<DynamicToolExecutionResult>()
        val handle = background.executeCancellable(
            call("background", "run_steps"),
            DynamicToolCancellation.NONE,
            backgroundResults::add,
        )
        val pendingBackground = delegate.invocations.single()
        assertTrue(lease.hasInFlightCalls())
        assertTrue(fence.hasActiveWork)
        assertEquals(1, invalidations)

        // Neither another automation nor the main conversation may interleave a phone action.
        assertBusy(background, call("other-background", "run_steps"))
        assertBusy(main, call("main", "inspect"))
        assertEquals(1, delegate.invocations.size)
        assertEquals(1, invalidations)

        assertEquals(
            DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED,
            handle.cancel(),
        )
        assertEquals(1, pendingBackground.cancelCount)
        // The real revision guard logically finishes on cancel. That is NOT a physical receipt.
        assertFalse(lease.hasInFlightCalls())
        assertTrue(fence.hasActiveWork)
        assertEquals(0, physicalSettlements)
        assertBusy(main, call("main", "inspect"))

        pendingBackground.reportResult()
        assertTrue(backgroundResults.isEmpty())
        assertTrue(fence.hasActiveWork)
        assertBusy(main, call("main", "inspect"))
        assertEquals(1, delegate.invocations.size)

        pendingBackground.settlePhysically()
        assertFalse(fence.hasActiveWork)
        assertEquals(1, physicalSettlements)
        val mainResults = mutableListOf<DynamicToolExecutionResult>()
        main.execute(call("main", "inspect"), mainResults::add)
        assertEquals(2, delegate.invocations.size)
        assertEquals(2, invalidations)
        assertTrue(lease.hasInFlightCalls())
        assertTrue(fence.hasActiveWork)

        // Normal result delivery also cannot release the slot ahead of physical completion.
        val pendingMain = delegate.invocations.last()
        pendingMain.reportResult()
        assertEquals(listOf(OK), mainResults)
        assertFalse(lease.hasInFlightCalls())
        assertTrue(fence.hasActiveWork)
        assertBusy(background, call("background", "run_steps"))
        assertEquals(2, delegate.invocations.size)
        pendingMain.settlePhysically()
        assertFalse(fence.hasActiveWork)
        assertEquals(2, physicalSettlements)
        lease.close()
    }

    private class PhysicallyPendingExecutor : DynamicToolExecutor {
        val invocations = mutableListOf<Invocation>()
        override val specs = listOf(
            DynamicToolNamespaceSpec(
                name = "android_ui",
                description = "Test phone tools",
                tools = listOf("run_steps", "inspect").map { name ->
                    DynamicToolFunctionSpec(
                        name = name,
                        description = "Test phone operation",
                        inputSchemaJson = """{"type":"object","additionalProperties":false}""",
                    )
                },
            ),
        )

        override fun execute(
            call: DynamicToolCallParams,
            completion: (DynamicToolExecutionResult) -> Unit,
        ) {
            executeCancellable(call, DynamicToolCancellation.NONE, completion)
        }

        override fun executeCancellable(
            call: DynamicToolCallParams,
            cancellation: DynamicToolCancellation,
            completion: (DynamicToolExecutionResult) -> Unit,
        ): DynamicToolExecutionHandle = Invocation(completion).also(invocations::add)

        override fun failureResult(call: DynamicToolCallParams, code: String) =
            DynamicToolExecutionResult(JSONObject().put("errorCode", code).toString(), false)
    }

    private class Invocation(
        private val completion: (DynamicToolExecutionResult) -> Unit,
    ) : DynamicToolExecutionHandle {
        private val listeners = mutableListOf<() -> Unit>()
        private var settled = false
        var cancelCount = 0
            private set

        override fun cancel(): DynamicToolCancellationDisposition {
            cancelCount++
            return DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED
        }

        override fun onQuiescent(listener: () -> Unit): Boolean {
            if (settled) listener() else listeners += listener
            return true
        }

        fun reportResult() = completion(OK)

        fun settlePhysically() {
            if (settled) return
            settled = true
            listeners.toList().also { listeners.clear() }.forEach { it() }
        }
    }

    private companion object {
        val OK = DynamicToolExecutionResult("""{"status":"ok"}""", true)

        fun call(thread: String, tool: String) =
            DynamicToolCallParams(thread, "turn", "call-$thread", "android_ui", tool, "{}")

        fun assertBusy(executor: DynamicToolExecutor, call: DynamicToolCallParams) {
            val results = mutableListOf<DynamicToolExecutionResult>()
            executor.execute(call, results::add)
            val result = results.single()
            assertFalse(result.success)
            assertEquals("phone_tools_busy", JSONObject(result.contentText).getString("errorCode"))
        }
    }
}
