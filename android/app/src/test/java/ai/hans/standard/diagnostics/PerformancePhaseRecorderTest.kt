package ai.hans.standard.diagnostics

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolExecutionResult
import org.junit.Assert.*
import org.junit.Test

class PerformancePhaseRecorderTest {
    @Test
    fun disabledPhaseTrackingAndStartingRecordingDoNotReadClock() {
        val clock = Clock()
        val recorder = ToolPerformanceRecorder(clock::read)
        recorder.observeContext(1, PRIVATE_THREAD)
        PerformancePhase.entries.forEach { recorder.observePhase(it) }
        recorder.observePhase(PerformancePhase.WAITING_FOR_USER)
        PerformanceEvent.entries.forEach { recorder.recordEvent(it) }
        repeat(10) { recorder.snapshot() }
        assertTrue(recorder.start())
        assertEquals(0, clock.reads)
        assertEquals("waiting_for_user", recorder.snapshot()["currentPhase"])
        assertEquals(false, recorder.snapshot()["phaseClockStarted"])
        assertTrue(lifecycle(recorder).isEmpty())

        recorder.recordEvent(PerformanceEvent.USER_WAIT_STARTED)
        assertEquals(1, clock.reads)
        assertEquals(listOf("recording_phase_seed", "user_wait_started"), lifecycle(recorder).map { it["event"] })
        assertTrue(lifecycle(recorder).all { it["phase"] == "waiting_for_user" && it["atMicros"] == 0L })
    }

    @Test
    fun lifecycleAndToolIntervalsShareOneRelativeClock() {
        val clock = Clock(9_000_000_000_000L)
        val recorder = active(clock, PerformancePhase.DISPATCH_PENDING)
        recorder.recordEvent(PerformanceEvent.DISPATCH_ACCEPTED)
        clock.advanceMicros(2_000)
        recorder.observePhase(PerformancePhase.TURN_ACTIVE)
        recorder.recordEvent(PerformanceEvent.TURN_STARTED)
        clock.advanceMicros(5_000)
        val tool = recorder.begin(call(), true)
        clock.advanceMicros(100)
        recorder.finish(tool, "success", SUCCESS)

        val sample = samples(recorder).single()
        assertEquals(7_000L, sample["startedMicros"])
        assertEquals(7_100L, sample["endedMicros"])
        assertEquals(100L, sample["durationMicros"])
        assertEquals("turn_active", sample["phaseAtStart"])
        assertEquals("first_call", sample["gapBeforeState"])
        assertFalse(sample.containsKey("gapBeforePhasesMicros"))
        assertEquals(2_000L, totals(recorder)["dispatch_pending"])
        assertEquals(5_100L, totals(recorder)["turn_active"])
        assertEquals(7_100L, recorder.snapshot()["phaseMeasuredThroughMicros"])
        assertFalse(MigrationCanonicalJson.encode(recorder.snapshot()).contains("9000000000000"))
    }

    @Test
    fun measuredGapSeparatesIdleDispatchAndActiveWithoutClaimingNetworkOrModelTime() {
        val clock = Clock()
        val recorder = active(clock, PerformancePhase.TURN_ACTIVE)
        val first = recorder.begin(call(), true)
        clock.atMicros(10)
        recorder.finish(first, "success", SUCCESS)
        clock.atMicros(20)
        recorder.observePhase(PerformancePhase.IDLE_BETWEEN_TURNS)
        clock.atMicros(120)
        recorder.observePhase(PerformancePhase.DISPATCH_PENDING)
        clock.atMicros(170)
        recorder.observePhase(PerformancePhase.TURN_ACTIVE)
        clock.atMicros(220)
        val second = recorder.begin(call(), true)
        clock.atMicros(250)
        recorder.finish(second, "success", SUCCESS)

        val last = samples(recorder).last()
        assertEquals(210L, last["gapBeforeMicros"])
        val gap = gapPhases(last)
        assertEquals(100L, gap["idle_between_turns"])
        assertEquals(50L, gap["dispatch_pending"])
        assertEquals(60L, gap["turn_active"])
        assertEquals(0L, gap["waiting_for_user"])
        assertEquals(210L, gap.values.sum())
        assertEquals(100L, totals(recorder)["turn_active"])
        assertTrue((recorder.snapshot()["phaseScope"] as String).contains("not_cause_attribution"))
        assertTrue((recorder.snapshot()["phaseTotalsScope"] as String).contains("overlaps_tool_durations"))
    }

    @Test
    fun subMicrosecondPhaseTransitionsPreserveGapAndTotalsAtNonzeroClockOrigin() {
        val origin = 9_123_456_789_123L
        val clock = Clock(origin)
        val recorder = active(clock, PerformancePhase.TURN_ACTIVE)
        val first = recorder.begin(call())
        clock.nanos = origin + 400
        recorder.finish(first, "success", SUCCESS)
        clock.nanos = origin + 700
        recorder.observePhase(PerformancePhase.IDLE_BETWEEN_TURNS)
        clock.nanos = origin + 1_100
        recorder.observePhase(PerformancePhase.DISPATCH_PENDING)
        clock.nanos = origin + 1_850
        recorder.observePhase(PerformancePhase.TURN_ACTIVE)
        clock.nanos = origin + 2_499
        val second = recorder.begin(call())
        clock.nanos = origin + 3_900
        recorder.finish(second, "success", SUCCESS)

        val last = samples(recorder).last()
        val gap = gapPhases(last)
        assertEquals(2L, last["gapBeforeMicros"])
        assertEquals(last["gapBeforeMicros"], gap.values.sum())
        assertEquals(1L, gap["idle_between_turns"])
        assertEquals(0L, gap["dispatch_pending"])
        assertEquals(1L, gap["turn_active"])
        assertEquals(3L, recorder.snapshot()["phaseMeasuredThroughMicros"])
        assertEquals(recorder.snapshot()["phaseMeasuredThroughMicros"], totals(recorder).values.sum())
    }

    @Test
    fun explicitUserWaitIsDistinctFromIdleAndUnknownState() {
        val clock = Clock()
        val recorder = active(clock, PerformancePhase.UNKNOWN)
        recorder.finish(recorder.begin(call()), "success", SUCCESS)
        clock.atMicros(10)
        recorder.observePhase(PerformancePhase.WAITING_FOR_USER)
        recorder.recordEvent(PerformanceEvent.USER_WAIT_STARTED)
        clock.atMicros(50)
        recorder.observePhase(PerformancePhase.TURN_ACTIVE)
        recorder.recordEvent(PerformanceEvent.USER_WAIT_ENDED)
        clock.atMicros(60)
        recorder.finish(recorder.begin(call()), "success", SUCCESS)

        val gap = gapPhases(samples(recorder).last())
        assertEquals(10L, gap["unknown"])
        assertEquals(40L, gap["waiting_for_user"])
        assertEquals(10L, gap["turn_active"])
        assertEquals(0L, gap["idle_between_turns"])
    }

    @Test
    fun overlappingCallsDoNotExportPhaseBreakdownAsAnIdleGap() {
        val clock = Clock()
        val recorder = active(clock, PerformancePhase.TURN_ACTIVE)
        val first = recorder.begin(call())
        clock.atMicros(10)
        val second = recorder.begin(call())
        clock.atMicros(20)
        recorder.finish(second, "success", SUCCESS)
        clock.atMicros(30)
        recorder.observePhase(PerformancePhase.WAITING_FOR_USER)
        clock.atMicros(40)
        recorder.finish(first, "success", SUCCESS)
        clock.atMicros(50)
        recorder.finish(recorder.begin(call()), "success", SUCCESS)

        val overlapping = samples(recorder).first()
        assertEquals("overlap", overlapping["gapBeforeState"])
        assertFalse(overlapping.containsKey("gapBeforePhasesMicros"))
        assertEquals(10L, gapPhases(samples(recorder).last())["waiting_for_user"])
        assertEquals(50L, totals(recorder).values.sum())
    }

    @Test
    fun cancelledWorkMustSettleBeforePhaseGapBeginsEvenAfterItsSampleWasEvicted() {
        for (outcome in listOf("cancelled_before_effect", "cancel_requested_effect_may_have_started")) {
            val clock = Clock()
            val recorder = ToolPerformanceRecorder(clock::read, capacity = 1, lifecycleCapacity = 2)
            recorder.observeContext(1, PRIVATE_THREAD)
            recorder.observePhase(PerformancePhase.TURN_ACTIVE)
            recorder.start()
            val cancelled = recorder.begin(call())
            clock.atMicros(10)
            recorder.finish(cancelled, outcome)
            clock.atMicros(20)
            recorder.observePhase(PerformancePhase.IDLE_BETWEEN_TURNS)
            clock.atMicros(30)
            recorder.finish(recorder.begin(call()), "success", SUCCESS)
            assertEquals("cancelled_call_completion_unknown", samples(recorder).single()["gapBeforeState"])
            assertFalse(samples(recorder).single().containsKey("gapBeforePhasesMicros"))
            clock.atMicros(40)
            recorder.observeQuiescence(cancelled)
            clock.atMicros(60)
            recorder.observePhase(PerformancePhase.DISPATCH_PENDING)
            clock.atMicros(90)
            recorder.finish(recorder.begin(call()), "success", SUCCESS)

            val last = samples(recorder).single()
            assertEquals(50L, last["gapBeforeMicros"])
            assertEquals(20L, gapPhases(last)["idle_between_turns"])
            assertEquals(30L, gapPhases(last)["dispatch_pending"])
            assertEquals(0L, recorder.snapshot()["unresolvedCancellationCalls"])
        }
    }

    @Test
    fun contextResetClearsPhasesDisablesRecordingAndFencesLateEventsAndCalls() {
        val clock = Clock()
        val recorder = active(clock, PerformancePhase.TURN_ACTIVE)
        val oldToken = recorder.contextToken()
        val oldTool = recorder.begin(call())
        recorder.recordEvent(PerformanceEvent.TURN_STARTED, oldToken)
        recorder.observeContext(2, "new-private-thread")
        val reads = clock.reads
        recorder.observePhase(PerformancePhase.WAITING_FOR_USER, oldToken)
        recorder.recordEvent(PerformanceEvent.USER_WAIT_STARTED, oldToken)
        recorder.finish(oldTool, "success", SUCCESS)
        assertEquals(reads, clock.reads)
        assertEquals(false, recorder.snapshot()["enabled"])
        assertEquals("unknown", recorder.snapshot()["currentPhase"])
        assertTrue(lifecycle(recorder).isEmpty())
        assertTrue(samples(recorder).isEmpty())
        assertEquals(0L, totals(recorder).values.sum())

        assertTrue(recorder.start())
        recorder.recordEvent(PerformanceEvent.TURN_COMPLETED, oldToken)
        recorder.observePhase(PerformancePhase.IDLE_BETWEEN_TURNS, oldToken)
        recorder.finish(oldTool, "failure", SUCCESS)
        assertEquals(reads, clock.reads)
        recorder.observePhase(PerformancePhase.DISPATCH_PENDING, recorder.contextToken())
        assertEquals("dispatch_pending", recorder.snapshot()["currentPhase"])
        assertTrue(lifecycle(recorder).all { it["atMicros"] == 0L })
    }

    @Test
    fun recordingRestartPreservesLatestPhaseButNotPriorDurationsOrCalls() {
        val clock = Clock()
        val recorder = active(clock, PerformancePhase.TURN_ACTIVE)
        val old = recorder.begin(call())
        clock.atMicros(100)
        recorder.observePhase(PerformancePhase.WAITING_FOR_USER)
        recorder.stop()
        val reads = clock.reads
        recorder.observePhase(PerformancePhase.IDLE_BETWEEN_TURNS)
        recorder.recordEvent(PerformanceEvent.TURN_COMPLETED)
        recorder.start()
        recorder.finish(old, "success", SUCCESS)
        assertEquals(reads, clock.reads)
        assertEquals("idle_between_turns", recorder.snapshot()["currentPhase"])
        recorder.recordEvent(PerformanceEvent.DISPATCH_ACCEPTED)
        assertEquals(0L, recorder.snapshot()["phaseMeasuredThroughMicros"])
        assertEquals(0L, totals(recorder).values.sum())
        assertTrue(lifecycle(recorder).all { it["phase"] == "idle_between_turns" })
    }

    @Test
    fun lifecycleRingEvictionDoesNotLoseCumulativePhaseGapAccounting() {
        val clock = Clock()
        val recorder = ToolPerformanceRecorder(clock::read, lifecycleCapacity = 2)
        recorder.observeContext(1, PRIVATE_THREAD)
        recorder.observePhase(PerformancePhase.TURN_ACTIVE)
        recorder.start()
        recorder.finish(recorder.begin(call()), "success", SUCCESS)
        repeat(10) {
            clock.advanceMicros(1)
            recorder.recordEvent(PerformanceEvent.SERVER_ITEM_STARTED)
        }
        recorder.finish(recorder.begin(call()), "success", SUCCESS)
        assertEquals(2, lifecycle(recorder).size)
        assertEquals(11L, recorder.snapshot()["recordedLifecycleEvents"])
        assertEquals(9L, recorder.snapshot()["discardedLifecycleEvents"])
        assertEquals(10L, gapPhases(samples(recorder).last())["turn_active"])
        val reads = clock.reads
        repeat(100) { recorder.snapshot(); recorder.observePhase(PerformancePhase.TURN_ACTIVE) }
        assertEquals(reads, clock.reads)
        assertThrows(IllegalArgumentException::class.java) { ToolPerformanceRecorder(lifecycleCapacity = 257) }
        assertThrows(IllegalArgumentException::class.java) { ToolPerformanceRecorder(lifecycleCapacity = 0) }
    }

    @Test
    fun exportedPhaseMapsAreIndependentSnapshots() {
        val clock = Clock()
        val recorder = active(clock, PerformancePhase.TURN_ACTIVE)
        recorder.finish(recorder.begin(call()), "success", SUCCESS)
        clock.atMicros(20)
        recorder.finish(recorder.begin(call()), "success", SUCCESS)
        @Suppress("UNCHECKED_CAST")
        val external = gapPhases(samples(recorder).last()) as MutableMap<String, Long>
        external["turn_active"] = 9_999
        assertEquals(20L, gapPhases(samples(recorder).last())["turn_active"])
    }

    @Test
    fun onlyTypedDiagnosticCodesAndFixedLifecycleLabelsAreRetained() {
        val clock = Clock()
        val recorder = active(clock, PerformancePhase.UNKNOWN)
        PerformanceEvent.entries.forEach { recorder.recordEvent(it) }
        val malicious = "private-looking-error-code"
        recorder.finish(recorder.begin(call(), true), "failure", DynamicToolExecutionResult(
            "{\"errorCode\":\"$malicious\",\"detailCode\":\"do-not-copy\"}", false,
            failureDiagnostic = ToolFailureDiagnostic.fromCodes(malicious, "do-not-copy"),
        ))
        assertEquals("unknown", samples(recorder).single()["failureCode"])
        assertEquals("unknown", samples(recorder).single()["failureDetail"])
        recorder.finish(recorder.begin(call(), true), "failure", DynamicToolExecutionResult(
            "{\"errorCode\":\"$malicious\"}", false,
            failureDiagnostic = ToolFailureDiagnostic(
                ToolFailureCode.SEMANTIC_FALLBACK_PROOF_REQUIRED,
                ToolFailureDetail.PROOF_CURRENT_SNAPSHOT_MISMATCH,
            ),
        ))
        assertEquals("semantic_fallback_proof_required", samples(recorder).last()["failureCode"])
        assertEquals("proof_current_snapshot_mismatch", samples(recorder).last()["failureDetail"])
        recorder.finish(recorder.begin(call(), true), "failure", DynamicToolExecutionResult(
            "{\"errorCode\":\"stale_snapshot\"}", false,
        ))
        assertEquals("unknown", samples(recorder).last()["failureCode"])
        val encoded = MigrationCanonicalJson.encode(recorder.snapshot())
        for (secret in listOf(PRIVATE_THREAD, "private-turn", "private-call", "private-argument", malicious, "do-not-copy")) {
            assertFalse(encoded.contains(secret))
        }
        assertTrue(lifecycle(recorder).all { it["event"] in PerformanceEvent.entries.map(PerformanceEvent::wire) })
        assertEquals(setOf("sequence", "atMicros", "event", "phase"), lifecycle(recorder).first().keys)
    }

    private class Clock(var nanos: Long = 0) {
        var reads = 0
        fun read(): Long { reads++; return nanos }
        fun atMicros(value: Long) { nanos = value * 1_000 }
        fun advanceMicros(value: Long) { nanos += value * 1_000 }
    }

    private fun active(clock: Clock, phase: PerformancePhase) = ToolPerformanceRecorder(clock::read).apply {
        observeContext(1, PRIVATE_THREAD)
        observePhase(phase)
        start()
    }

    @Suppress("UNCHECKED_CAST")
    private fun samples(recorder: ToolPerformanceRecorder) = recorder.snapshot()["samples"] as List<Map<String, Any>>
    @Suppress("UNCHECKED_CAST")
    private fun lifecycle(recorder: ToolPerformanceRecorder) = recorder.snapshot()["lifecycle"] as List<Map<String, Any>>
    @Suppress("UNCHECKED_CAST")
    private fun totals(recorder: ToolPerformanceRecorder) = recorder.snapshot()["phaseTotalsMicros"] as Map<String, Long>
    @Suppress("UNCHECKED_CAST")
    private fun gapPhases(sample: Map<String, Any>) = sample["gapBeforePhasesMicros"] as Map<String, Long>

    private fun call() = DynamicToolCallParams(PRIVATE_THREAD, "private-turn", "private-call",
        "android_ui", "inspect_ui", "{\"text\":\"private-argument\"}")

    companion object {
        private const val PRIVATE_THREAD = "private-thread"
        private val SUCCESS = DynamicToolExecutionResult("{}", true)
    }
}
