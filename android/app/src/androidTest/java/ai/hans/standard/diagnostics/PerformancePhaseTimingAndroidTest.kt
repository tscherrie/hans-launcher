package ai.hans.standard.diagnostics

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolExecutionResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Fake-clock lifecycle/tool events only; no UI, network or real phone actions. */
@RunWith(AndroidJUnit4::class)
class PerformancePhaseTimingAndroidTest {
    @Test
    fun lifecycleClockAndGapPhaseBreakdownRoundTrip() {
        var now = 9_000_000_000_000L
        val recorder = active { now }
        recorder.recordEvent(PerformanceEvent.TURN_STARTED)
        now += 5_000
        val first = recorder.begin(call(), true)
        now += 5_000
        recorder.finish(first, "success", SUCCESS)
        now += 10_000
        recorder.observePhase(PerformancePhase.IDLE_BETWEEN_TURNS)
        now += 100_000
        recorder.observePhase(PerformancePhase.DISPATCH_PENDING)
        recorder.recordEvent(PerformanceEvent.TURN_START_SEND)
        now += 50_000
        recorder.observePhase(PerformancePhase.TURN_ACTIVE)
        now += 50_000
        recorder.finish(recorder.begin(call(), true), "success", SUCCESS)

        val json = tools(recorder)
        val samples = json.getJSONArray("samples")
        assertEquals(5L, samples.getJSONObject(0).getLong("startedMicros"))
        assertEquals(10L, samples.getJSONObject(0).getLong("endedMicros"))
        val second = samples.getJSONObject(1)
        assertEquals("turn_active", second.getString("phaseAtStart"))
        assertEquals(210L, second.getLong("gapBeforeMicros"))
        val gap = second.getJSONObject("gapBeforePhasesMicros")
        assertEquals(100L, gap.getLong("idle_between_turns"))
        assertEquals(50L, gap.getLong("dispatch_pending"))
        assertEquals(60L, gap.getLong("turn_active"))
        assertEquals(0L, gap.getLong("waiting_for_user"))
        assertEquals(220L, json.getLong("phaseMeasuredThroughMicros"))
        assertTrue(json.getString("phaseScope").contains("not_cause_attribution"))
        assertTrue(json.getString("phaseTotalsScope").contains("overlaps_tool_durations"))
        assertFalse(json.toString().contains("9000000000000"))
    }

    @Test
    fun cancelledWorkAndOverlapNeverExposePhaseGapBeforeCompletion() {
        var now = 0L
        val recorder = active { now }
        val cancelled = recorder.begin(call())
        now = 10_000
        val overlapping = recorder.begin(call())
        now = 20_000
        recorder.finish(overlapping, "success", SUCCESS)
        now = 30_000
        recorder.finish(cancelled, "cancelled_before_effect")
        now = 40_000
        recorder.observePhase(PerformancePhase.WAITING_FOR_USER)
        recorder.finish(recorder.begin(call()), "success", SUCCESS)
        var samples = tools(recorder).getJSONArray("samples")
        assertEquals("overlap", samples.getJSONObject(0).getString("gapBeforeState"))
        assertFalse(samples.getJSONObject(0).has("gapBeforePhasesMicros"))
        assertEquals("cancelled_call_completion_unknown", samples.getJSONObject(2).getString("gapBeforeState"))
        assertFalse(samples.getJSONObject(2).has("gapBeforePhasesMicros"))
        now = 50_000
        recorder.observeQuiescence(cancelled)
        now = 90_000
        recorder.finish(recorder.begin(call()), "success", SUCCESS)
        samples = tools(recorder).getJSONArray("samples")
        assertEquals(40L, samples.getJSONObject(3).getJSONObject("gapBeforePhasesMicros")
            .getLong("waiting_for_user"))
        assertEquals(0L, tools(recorder).getLong("unresolvedCancellationCalls"))
    }

    @Test
    fun disabledAndContextResetFenceEventsWithoutClockReads() {
        var reads = 0
        val recorder = ToolPerformanceRecorder({ reads++; 0L })
        recorder.observeContext(1, "synthetic-thread")
        recorder.observePhase(PerformancePhase.WAITING_FOR_USER)
        recorder.recordEvent(PerformanceEvent.USER_WAIT_STARTED)
        recorder.start()
        assertEquals(0, reads)
        assertEquals("waiting_for_user", tools(recorder).getString("currentPhase"))
        val oldContext = recorder.contextToken()
        val oldCall = recorder.begin(call())
        recorder.observeContext(2, "new-private-thread")
        val before = reads
        recorder.observePhase(PerformancePhase.TURN_ACTIVE, oldContext)
        recorder.recordEvent(PerformanceEvent.TURN_STARTED, oldContext)
        recorder.finish(oldCall, "success", SUCCESS)
        repeat(10) { tools(recorder) }
        assertEquals(before, reads)
        assertFalse(tools(recorder).getBoolean("enabled"))
        assertEquals("unknown", tools(recorder).getString("currentPhase"))
        assertEquals(0, tools(recorder).getJSONArray("lifecycle").length())
        recorder.start()
        recorder.recordEvent(PerformanceEvent.TURN_COMPLETED, oldContext)
        assertEquals(before, reads)
    }

    @Test
    fun boundedLifecycleAndTypedFailuresStayPrivateInPlatformJson() {
        var now = 0L
        var reads = 0
        val recorder = ToolPerformanceRecorder({ reads++; now }, capacity = 2, lifecycleCapacity = 2)
        recorder.observeContext(1, "synthetic-thread")
        recorder.observePhase(PerformancePhase.TURN_ACTIVE)
        recorder.start()
        repeat(6) {
            now += 1_000
            recorder.recordEvent(PerformanceEvent.SERVER_ITEM_COMPLETED)
        }
        recorder.finish(recorder.begin(call(), true), "failure", DynamicToolExecutionResult(
            "{\"errorCode\":\"private-untrusted-value\"}", false,
            failureDiagnostic = ToolFailureDiagnostic(
                ToolFailureCode.SEMANTIC_FALLBACK_PROOF_REQUIRED,
                ToolFailureDetail.PROOF_CURRENT_SNAPSHOT_MISMATCH,
            ),
        ))
        recorder.finish(recorder.begin(call(), true), "failure", DynamicToolExecutionResult(
            "{\"errorCode\":\"private-untrusted-value\"}", false,
        ))
        val before = reads
        repeat(10) { tools(recorder) }
        assertEquals(before, reads)
        val json = tools(recorder)
        assertEquals(2, json.getJSONArray("lifecycle").length())
        assertEquals(5L, json.getLong("discardedLifecycleEvents"))
        val samples = json.getJSONArray("samples")
        assertEquals("semantic_fallback_proof_required", samples.getJSONObject(0).getString("failureCode"))
        assertEquals("proof_current_snapshot_mismatch", samples.getJSONObject(0).getString("failureDetail"))
        assertEquals("unknown", samples.getJSONObject(1).getString("failureCode"))
        for (privateValue in listOf("synthetic-thread", "private-turn", "private-call", "private-argument", "private-untrusted-value")) {
            assertFalse(json.toString().contains(privateValue))
        }
    }

    private fun active(clock: () -> Long) = ToolPerformanceRecorder(clock).apply {
        observeContext(1, "synthetic-thread")
        observePhase(PerformancePhase.TURN_ACTIVE)
        start()
    }

    private fun tools(recorder: ToolPerformanceRecorder) =
        JSONObject(PerformanceDiagnostics.encode(null, recorder.snapshot())).getJSONObject("tools")

    private fun call() = DynamicToolCallParams("synthetic-thread", "private-turn", "private-call",
        "android_ui", "inspect_ui", "{\"text\":\"private-argument\"}")

    companion object { private val SUCCESS = DynamicToolExecutionResult("{}", true) }
}
