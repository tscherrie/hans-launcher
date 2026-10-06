package ai.hans.standard.diagnostics

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolExecutionResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic events and a fake monotonic clock only; no real tools, UI, files or network. */
@RunWith(AndroidJUnit4::class)
class ToolPerformanceTimingAndroidTest {
    @Test
    fun relativeClockAndGapRoundTripThroughPlatformJson() {
        var now = 9_000_000_000_000L
        val recorder = activeRecorder { now }
        val first = recorder.begin(call(), true)
        now += 25_000_000
        recorder.finish(first, "success", result())
        now += 4_500_000_000
        val second = recorder.begin(call(), true)
        now += 2_000_000
        recorder.finish(second, "success", result())
        val tools = tools(recorder)
        val samples = tools.getJSONArray("samples")
        assertEquals(0L, samples.getJSONObject(0).getLong("startedMicros"))
        assertEquals(25_000L, samples.getJSONObject(0).getLong("endedMicros"))
        val last = samples.getJSONObject(1)
        assertEquals(4_525_000L, last.getLong("startedMicros"))
        assertEquals(4_527_000L, last.getLong("endedMicros"))
        assertEquals(2_000L, last.getLong("durationMicros"))
        assertEquals(4_500_000L, last.getLong("gapBeforeMicros"))
        assertEquals("measured", last.getString("gapBeforeState"))
        assertTrue(tools.getString("gapScope").contains("not_cause_attribution"))
        assertFalse(tools.toString().contains("9000000000000"))
    }

    @Test
    fun overlappingCompletionsDoNotClaimIdleGaps() {
        var now = 0L
        val recorder = activeRecorder { now }
        val first = recorder.begin(call(), true)
        now = 10_000
        val second = recorder.begin(call(), true)
        now = 20_000
        recorder.finish(second, "success", result())
        now = 30_000
        val third = recorder.begin(call(), true)
        now = 40_000
        recorder.finish(first, "success", result())
        now = 50_000
        recorder.finish(third, "success", result())
        now = 70_000
        recorder.finish(recorder.begin(call(), true), "success", result())
        val samples = tools(recorder).getJSONArray("samples")
        assertEquals(2L, samples.getJSONObject(0).getLong("sequence"))
        assertEquals(1L, samples.getJSONObject(1).getLong("sequence"))
        for (index in listOf(0, 2)) {
            assertEquals("overlap", samples.getJSONObject(index).getString("gapBeforeState"))
            assertFalse(samples.getJSONObject(index).has("gapBeforeMicros"))
        }
        assertEquals(20L, samples.getJSONObject(3).getLong("gapBeforeMicros"))
        assertEquals(0L, tools(recorder).getLong("activeCalls"))
    }

    @Test
    fun contextResetAndCancellationIsolateEpoch() {
        var now = 0L
        val recorder = activeRecorder { now }
        val cancelled = recorder.begin(call(), true)
        now = 10_000
        recorder.finish(cancelled, "cancel_requested_effect_may_have_started")
        now = 20_000
        recorder.finish(recorder.begin(call(), true), "success", result())
        assertEquals("cancelled_call_completion_unknown",
            tools(recorder).getJSONArray("samples").getJSONObject(1).getString("gapBeforeState"))
        now = 30_000
        recorder.observeQuiescence(cancelled)
        assertEquals(0L, tools(recorder).getLong("unresolvedCancellationCalls"))
        now = 40_000
        recorder.finish(recorder.begin(call(), true), "success", result())
        assertEquals(10L, tools(recorder).getJSONArray("samples").getJSONObject(2).getLong("gapBeforeMicros"))
        val stale = recorder.begin(call(), true)
        recorder.observeContext(2, "synthetic-thread")
        assertFalse(tools(recorder).getBoolean("enabled"))
        assertTrue(recorder.start())
        now = 1_000_000
        recorder.finish(stale, "success", result())
        recorder.observeQuiescence(cancelled)
        recorder.finish(recorder.begin(call(), true), "success", result())
        val samples = tools(recorder).getJSONArray("samples")
        assertEquals(1, samples.length())
        assertEquals(0L, samples.getJSONObject(0).getLong("startedMicros"))
        assertEquals("first_call", samples.getJSONObject(0).getString("gapBeforeState"))

        val beforeEffect = recorder.begin(call(), true)
        now += 10_000
        recorder.finish(beforeEffect, "cancelled_before_effect")
        now += 10_000
        recorder.finish(recorder.begin(call(), true), "success", result())
        assertEquals("cancelled_call_completion_unknown",
            tools(recorder).getJSONArray("samples").getJSONObject(2).getString("gapBeforeState"))
        assertEquals(1L, tools(recorder).getLong("unresolvedCancellationCalls"))
        now += 10_000
        recorder.observeQuiescence(beforeEffect)
        now += 20_000
        recorder.finish(recorder.begin(call(), true), "success", result())
        assertEquals(20L, tools(recorder).getJSONArray("samples").getJSONObject(3).getLong("gapBeforeMicros"))
    }

    @Test
    fun boundedSamplesRemainContentFreeAndSnapshotDoesNotPollClock() {
        var reads = 0
        val recorder = ToolPerformanceRecorder({ (++reads * 1_000).toLong() }, capacity = 2)
        recorder.observeContext(1, "synthetic-thread")
        recorder.start()
        repeat(4) { recorder.finish(recorder.begin(call(), true), "success", result()) }
        val before = reads
        repeat(10) { tools(recorder) }
        assertEquals(before, reads)
        val encoded = PerformanceDiagnostics.encode(null, recorder.snapshot())
        for (privateValue in listOf("synthetic-thread", "private-turn", "private-call", "private argument", "秘密🙂")) {
            assertFalse(encoded.contains(privateValue))
        }
        assertEquals(2L, tools(recorder).getLong("discardedSamples"))
        assertEquals(2, tools(recorder).getJSONArray("samples").length())
        assertEquals(result().contentText.toByteArray(Charsets.UTF_8).size,
            tools(recorder).getJSONArray("samples").getJSONObject(0).getInt("resultTextUtf8Bytes"))
    }

    private fun activeRecorder(clock: () -> Long) = ToolPerformanceRecorder(clock).apply {
        observeContext(1, "synthetic-thread")
        start()
    }

    private fun tools(recorder: ToolPerformanceRecorder): JSONObject =
        JSONObject(PerformanceDiagnostics.encode(null, recorder.snapshot())).getJSONObject("tools")

    private fun call() = DynamicToolCallParams(
        "synthetic-thread", "private-turn", "private-call", "android_ui", "inspect_ui",
        "{\"text\":\"private argument\"}",
    )

    private fun result() = DynamicToolExecutionResult("{\"text\":\"秘密🙂\"}", true)
}
