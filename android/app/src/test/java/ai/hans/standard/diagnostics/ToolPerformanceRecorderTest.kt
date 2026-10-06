package ai.hans.standard.diagnostics

import ai.hans.standard.codex.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolPerformanceRecorderTest {
    @Test
    fun disabledMeansNoClockReadsOrRecordsAndStartRequiresKnownContext() {
        var reads = 0
        val recorder = ToolPerformanceRecorder({ reads++; 0L })
        assertFalse(recorder.start())
        assertNull(recorder.begin(call()))
        recorder.observeContext(1, "thread-private")
        assertNull(recorder.begin(call()))
        assertEquals(0, reads)
        assertEquals(0L, recorder.snapshot()["startedCalls"])
    }

    @Test
    fun exactBytesAndElapsedTimeAreRecordedWithoutSensitiveContent() {
        var now = 100L
        val recorder = activeRecorder { now }
        val attempt = recorder.begin(call(), declaredTool = true)
        now += 1_234_567
        val result = DynamicToolExecutionResult("{\"text\":\"秘密🙂\"}", true)
        recorder.finish(attempt, "success", result)
        val record = samples(recorder).single()
        assertEquals(0L, record["startedMicros"])
        assertEquals(1_234L, record["endedMicros"])
        assertEquals(1_234L, record["durationMicros"])
        assertEquals("first_call", record["gapBeforeState"])
        assertFalse(record.containsKey("gapBeforeMicros"))
        assertEquals(result.contentText.toByteArray(Charsets.UTF_8).size, record["resultTextUtf8Bytes"])
        assertEquals("android_ui.inspect_ui", record["tool"])
        val json = MigrationCanonicalJson.encode(recorder.snapshot())
        listOf("秘密", "thread-private", "turn-private", "call-private", "secret-argument").forEach {
            assertFalse(json.contains(it))
        }
    }

    @Test
    fun sequentialGapsAreRelativeAndDoNotClaimModelTime() {
        var now = 8_000_000_000_000L
        val recorder = activeRecorder { now }
        val first = recorder.begin(call())
        now += 5_000_000
        recorder.finish(first, "success", result)
        now += 9_000_000_000
        val second = recorder.begin(call())
        now += 200_000
        recorder.finish(second, "success", result)
        val sample = samples(recorder).last()
        assertEquals(9_005_000L, sample["startedMicros"])
        assertEquals(9_005_200L, sample["endedMicros"])
        assertEquals(200L, sample["durationMicros"])
        assertEquals(9_000_000L, sample["gapBeforeMicros"])
        assertEquals(0L, sample["activeCallsAtStart"])
        assertEquals("measured", sample["gapBeforeState"])
        assertTrue((recorder.snapshot()["gapScope"] as String).contains("not_cause_attribution"))
        val encoded = MigrationCanonicalJson.encode(recorder.snapshot())
        assertFalse(encoded.contains("8000000000000"))
    }

    @Test
    fun overlapAndOutOfOrderCompletionDoNotBecomeArtificialIdleGaps() {
        var now = 0L
        val recorder = activeRecorder { now }
        val first = recorder.begin(call())
        now = 10_000
        val second = recorder.begin(call())
        now = 20_000
        recorder.finish(second, "success")
        now = 30_000
        val third = recorder.begin(call())
        now = 40_000
        recorder.finish(first, "success")
        now = 50_000
        recorder.finish(third, "success")
        now = 60_000
        val fourth = recorder.begin(call())
        now = 70_000
        recorder.finish(fourth, "success")
        val records = samples(recorder)
        assertEquals(listOf(2L, 1L, 3L, 4L), records.map { it["sequence"] })
        for (sample in records.filter { it["sequence"] in listOf(2L, 3L) }) {
            assertEquals("overlap", sample["gapBeforeState"])
            assertEquals(1L, sample["activeCallsAtStart"])
            assertFalse(sample.containsKey("gapBeforeMicros"))
        }
        assertEquals(10L, records.last()["gapBeforeMicros"])
        assertEquals(0L, recorder.snapshot()["activeCalls"])
    }

    @Test
    fun pendingCallsRemainVisibleWithoutClockReadsOrIdlePolling() {
        var reads = 0
        val recorder = activeRecorder { reads++; 5_000L }
        recorder.begin(call())
        repeat(20) {
            assertEquals(1L, recorder.snapshot()["activeCalls"])
            assertEquals(0L, recorder.snapshot()["finishedCalls"])
        }
        assertEquals(1, reads)
    }

    @Test
    fun cancellationMayStillRunSoGapIsUnknownUntilARealResultIsObserved() {
        var now = 0L
        val recorder = activeRecorder { now }
        val cancelled = recorder.begin(call())
        now = 10_000
        recorder.finish(cancelled, "cancel_requested_effect_may_have_started")
        recorder.finish(cancelled, "cancel_requested_effect_may_have_started")
        assertEquals(1L, recorder.snapshot()["unresolvedCancellationCalls"])
        now = 20_000
        val next = recorder.begin(call())
        now = 30_000
        recorder.finish(next, "success", result)
        assertEquals("cancelled_call_completion_unknown", samples(recorder).last()["gapBeforeState"])
        assertFalse(samples(recorder).last().containsKey("gapBeforeMicros"))
        now = 40_000
        recorder.finish(cancelled, "success", result)
        recorder.finish(cancelled, "success", result)
        assertEquals(0L, recorder.snapshot()["unresolvedCancellationCalls"])
        assertEquals(2L, recorder.snapshot()["finishedCalls"])
        assertEquals(10L, samples(recorder).first()["endedMicros"])
        assertEquals(40L, samples(recorder).first()["completionObservedMicros"])
        now = 70_000
        recorder.finish(recorder.begin(call()), "success", result)
        assertEquals(30L, samples(recorder).last()["gapBeforeMicros"])
    }

    @Test
    fun cancellationBeforeExternalEffectStillRequiresACompletionReceipt() {
        var now = 0L
        val recorder = activeRecorder { now }
        val cancelled = recorder.begin(call())
        now = 10_000
        recorder.finish(cancelled, "cancelled_before_effect")
        now = 20_000
        recorder.finish(recorder.begin(call()), "success", result)
        assertEquals(1L, recorder.snapshot()["unresolvedCancellationCalls"])
        assertEquals("cancelled_call_completion_unknown", samples(recorder).last()["gapBeforeState"])
        assertFalse(samples(recorder).last().containsKey("gapBeforeMicros"))
        now = 30_000
        recorder.observeQuiescence(cancelled)
        now = 50_000
        recorder.finish(recorder.begin(call()), "success", result)
        assertEquals(0L, recorder.snapshot()["unresolvedCancellationCalls"])
        assertEquals("measured", samples(recorder).last()["gapBeforeState"])
        assertEquals(20L, samples(recorder).last()["gapBeforeMicros"])
    }

    @Test
    fun allOverlappingCancelledOperationsMustSettleBeforeAnIdleGapIsMeasured() {
        var now = 0L
        val recorder = activeRecorder { now }
        val first = recorder.begin(call())
        val second = recorder.begin(call())
        now = 10_000
        recorder.finish(first, "cancel_requested_effect_may_have_started")
        recorder.finish(second, "cancel_requested_effect_may_have_started")
        assertEquals(2L, recorder.snapshot()["unresolvedCancellationCalls"])
        now = 20_000
        recorder.observeQuiescence(second)
        now = 30_000
        recorder.finish(recorder.begin(call()), "success", result)
        assertEquals("cancelled_call_completion_unknown", samples(recorder).last()["gapBeforeState"])
        now = 40_000
        recorder.finish(first, "success", result)
        now = 70_000
        recorder.finish(recorder.begin(call()), "success", result)
        assertEquals("measured", samples(recorder).last()["gapBeforeState"])
        assertEquals(30L, samples(recorder).last()["gapBeforeMicros"])
        assertEquals(0L, recorder.snapshot()["unresolvedCancellationCalls"])
    }

    @Test
    fun evictionKeepsGapContinuityAndResetDropsAllOldTimingAndCancellationState() {
        var now = 0L
        val recorder = ToolPerformanceRecorder({ now }, capacity = 1)
        recorder.observeContext(1, "thread-private")
        recorder.start()
        val old = recorder.begin(call())
        now = 10_000
        recorder.finish(old, "cancel_requested_effect_may_have_started")
        now = 20_000
        recorder.finish(recorder.begin(call()), "success", result)
        now = 30_000
        recorder.finish(old, "success", result)
        assertEquals(2L, samples(recorder).single()["sequence"])
        assertFalse(samples(recorder).single().containsKey("completionObservedMicros"))
        now = 50_000
        recorder.finish(recorder.begin(call()), "success", result)
        assertEquals(20L, samples(recorder).single()["gapBeforeMicros"])
        assertEquals(2L, recorder.snapshot()["discardedSamples"])
        val pending = recorder.begin(call())
        recorder.observeContext(2, "thread-private")
        recorder.start()
        now = 1_000_000
        recorder.finish(pending, "success", result)
        recorder.finish(recorder.begin(call()), "success", result)
        val first = samples(recorder).single()
        assertEquals(1L, first["sequence"])
        assertEquals(0L, first["startedMicros"])
        assertEquals("first_call", first["gapBeforeState"])
        assertEquals(0L, recorder.snapshot()["unresolvedCancellationCalls"])
    }

    @Test
    fun boundedRetentionDuplicateAndOldEpochCallbacksCannotPolluteLaterRecording() {
        val recorder = ToolPerformanceRecorder({ 10L }, capacity = 2)
        recorder.observeContext(1, "thread-private")
        assertTrue(recorder.start())
        val old = recorder.begin(call())
        repeat(4) {
            val attempt = recorder.begin(call())
            recorder.finish(attempt, "success")
            recorder.finish(attempt, "failure")
        }
        assertEquals(2, samples(recorder).size)
        assertEquals(4L, recorder.snapshot()["finishedCalls"])
        assertEquals(2L, recorder.snapshot()["discardedSamples"])
        recorder.start()
        recorder.finish(old, "success")
        assertTrue(samples(recorder).isEmpty())
        val pending = recorder.begin(call())
        recorder.observeContext(2, "thread-private")
        recorder.finish(pending, "success")
        assertEquals(false, recorder.snapshot()["enabled"])
        assertEquals(0L, recorder.snapshot()["startedCalls"])
    }

    @Test
    fun signOutThreadSwitchStopAndForeignCallsAreIsolated() {
        val recorder = activeRecorder()
        assertNull(recorder.begin(call().copy(threadId = "foreign")))
        recorder.observeContext(1, "other-thread")
        assertEquals(false, recorder.snapshot()["enabled"])
        recorder.observeContext(null, null)
        assertFalse(recorder.start())
        recorder.observeContext(2, "thread-private")
        recorder.start()
        val pending = recorder.begin(call())
        recorder.stop()
        recorder.finish(pending, "success")
        assertTrue(samples(recorder).isEmpty())
    }

    @Test
    fun realWorkspaceArtifactWorkAndFilesNamespacesRetainTheirDeclaredOperationNames() {
        val recorder = activeRecorder()
        for ((namespace, tool) in listOf(
            "hans_workspace" to "list",
            "hans_artifact" to "from_workspace",
            "hans_work" to "http_request",
            "hans_files" to "save",
        )) {
            recorder.finish(recorder.begin(call().copy(namespace = namespace, tool = tool), true), "success")
        }
        assertEquals(listOf("hans_workspace.list", "hans_artifact.from_workspace", "hans_work.http_request", "hans_files.save"),
            samples(recorder).map { it["tool"] })
    }

    @Test
    fun sharedFileArgumentsAndUndeclaredNamesAreNotStored() {
        val recorder = activeRecorder()
        val privateCall = call().copy(namespace = "hans_files", tool = "save",
            argumentsJson = "{\"path\":\"/storage/emulated/0/Download/private-name.txt\",\"text\":\"private content\"}")
        recorder.finish(recorder.begin(privateCall, true), "success")
        recorder.finish(recorder.begin(privateCall.copy(tool = "private_file_name"), false), "failure")
        assertEquals(listOf("hans_files.save", "other"), samples(recorder).map { it["tool"] })
        val payload = MigrationCanonicalJson.encode(recorder.snapshot())
        assertFalse(payload.contains("private"))
        assertFalse(payload.contains("Download"))
    }

    @Test
    fun undeclaredAndPluginNamesAreNeverRecordedVerbatim() {
        val recorder = activeRecorder()
        recorder.finish(recorder.begin(call().copy(tool = "private_name")), "failure")
        recorder.finish(recorder.begin(call().copy(namespace = "personal_plugin"), true), "failure")
        assertEquals(listOf("other", "other"), samples(recorder).map { it["tool"] })
        assertFalse(MigrationCanonicalJson.encode(recorder.snapshot()).contains("private_name"))
    }

    @Test
    fun allocationFreeUtf8CountMatchesAllUtf16UnitsAndMixedPairs() {
        for (code in 0..0xffff) {
            val value = code.toChar().toString()
            assertEquals(value.toByteArray(Charsets.UTF_8).size, ToolPerformanceRecorder.utf8Length(value))
        }
        listOf("aü界🙂", "\uD800\uDC00\uDC00\uD800", "\uD800x\uDC00", "", "\n\"\\")
            .forEach { assertEquals(it.toByteArray(Charsets.UTF_8).size, ToolPerformanceRecorder.utf8Length(it)) }
    }

    @Test
    fun wrapperPreservesCatalogResultsDuplicateCallbacksAndFailureProjection() {
        val delegate = FakeExecutor()
        val recorder = activeRecorder()
        val measured = MeasuredDynamicToolExecutor(delegate, recorder)
        assertSame(delegate.specs, measured.specs)
        val received = mutableListOf<DynamicToolExecutionResult>()
        measured.execute(call(), received::add)
        delegate.callback(result)
        delegate.callback(result)
        assertEquals(2, received.size)
        assertSame(result, received[0])
        assertEquals(1, samples(recorder).size)
        assertSame(result, measured.failureResult(call(), "failed"))
    }

    @Test
    fun wrapperPreservesCancellationDispositionAndMarksAmbiguity() {
        val delegate = FakeExecutor()
        val recorder = activeRecorder()
        var completions = 0
        val cancellation = DynamicToolCancellation { false }
        val handle = MeasuredDynamicToolExecutor(delegate, recorder)
            .executeCancellable(call(), cancellation) { completions++ }
        assertSame(cancellation, delegate.cancellation)
        repeat(2) {
            assertEquals(DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED, handle.cancel())
        }
        delegate.callback(result)
        assertEquals(1, completions)
        assertEquals(2, delegate.cancels)
        assertEquals(1, delegate.quiescenceRegistrations)
        assertEquals("cancel_requested_effect_may_have_started", samples(recorder).single()["outcome"])
    }

    @Test
    fun cancellationQuiescenceClosesUnknownGapEvenWithoutAResultCallback() {
        var now = 0L
        val delegate = FakeExecutor()
        val recorder = activeRecorder { now }
        val measured = MeasuredDynamicToolExecutor(delegate, recorder)
        val handle = measured.executeCancellable(call(), DynamicToolCancellation.NONE) {}
        now = 10_000
        handle.cancel()
        assertEquals(1L, recorder.snapshot()["unresolvedCancellationCalls"])
        now = 40_000
        checkNotNull(delegate.quiescentListener).invoke()
        checkNotNull(delegate.quiescentListener).invoke()
        assertEquals(0L, recorder.snapshot()["unresolvedCancellationCalls"])
        assertEquals(40L, samples(recorder).single()["quiescentMicros"])
        now = 70_000
        measured.execute(call()) {}
        delegate.callback(result)
        assertEquals(30L, samples(recorder).last()["gapBeforeMicros"])
    }

    @Test
    fun unsupportedQuiescenceDoesNotInventCompletionOrAffectCancellation() {
        val delegate = FakeExecutor().apply { quiescenceSupported = false }
        val recorder = activeRecorder()
        val handle = MeasuredDynamicToolExecutor(delegate, recorder)
            .executeCancellable(call(), DynamicToolCancellation.NONE) {}
        assertEquals(DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED, handle.cancel())
        recorder.finish(recorder.begin(call()), "success", result)
        assertEquals(1L, recorder.snapshot()["unresolvedCancellationCalls"])
        assertEquals("cancelled_call_completion_unknown", samples(recorder).last()["gapBeforeState"])
    }

    @Test
    fun beforeEffectCancellationAlsoRegistersAndWaitsForPhysicalQuiescence() {
        var now = 0L
        val delegate = FakeExecutor().apply {
            cancellationDisposition = DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT
        }
        val recorder = activeRecorder { now }
        val measured = MeasuredDynamicToolExecutor(delegate, recorder)
        val handle = measured.executeCancellable(call(), DynamicToolCancellation.NONE) {}
        now = 10_000
        assertEquals(DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT, handle.cancel())
        assertEquals(1, delegate.quiescenceRegistrations)
        assertEquals(1L, recorder.snapshot()["unresolvedCancellationCalls"])
        now = 30_000
        checkNotNull(delegate.quiescentListener).invoke()
        now = 70_000
        measured.execute(call()) {}
        delegate.callback(result)
        assertEquals(0L, recorder.snapshot()["unresolvedCancellationCalls"])
        assertEquals(40L, samples(recorder).last()["gapBeforeMicros"])
    }

    @Test
    fun throwingOptionalQuiescenceRemainsFailOpen() {
        val delegate = FakeExecutor().apply { quiescenceFailure = IllegalStateException("optional receipt failed") }
        val recorder = activeRecorder()
        val handle = MeasuredDynamicToolExecutor(delegate, recorder)
            .executeCancellable(call(), DynamicToolCancellation.NONE) {}
        assertEquals(DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED, handle.cancel())
        assertEquals(1L, recorder.snapshot()["unresolvedCancellationCalls"])
    }

    @Test
    fun synchronousQuiescenceAfterCancelSettlesExactlyOnce() {
        val delegate = FakeExecutor().apply { quiescenceImmediate = true }
        val recorder = activeRecorder()
        val handle = MeasuredDynamicToolExecutor(delegate, recorder)
            .executeCancellable(call(), DynamicToolCancellation.NONE) {}
        repeat(2) { handle.cancel() }
        assertEquals(0L, recorder.snapshot()["unresolvedCancellationCalls"])
        assertEquals(1, delegate.quiescenceRegistrations)
        assertEquals(0L, samples(recorder).single()["quiescentMicros"])
    }

    @Test
    fun disabledRecordingAddsNoClockReadOrCancellationListener() {
        var reads = 0
        val recorder = ToolPerformanceRecorder({ reads++; 0L })
        val delegate = FakeExecutor()
        val handle = MeasuredDynamicToolExecutor(delegate, recorder)
            .executeCancellable(call(), DynamicToolCancellation.NONE) {}
        handle.cancel()
        assertEquals(0, reads)
        assertEquals(0, delegate.quiescenceRegistrations)
        assertTrue(samples(recorder).isEmpty())
    }

    @Test
    fun measurementForwardsQuiescenceWithoutTreatingCancellationAsCompletion() {
        val delegate = FakeExecutor()
        val handle = MeasuredDynamicToolExecutor(delegate, activeRecorder())
            .executeCancellable(call(), DynamicToolCancellation.NONE) {}
        var quiescent = false
        assertTrue(handle.onQuiescent { quiescent = true })
        handle.cancel()
        assertFalse(quiescent)
        checkNotNull(delegate.quiescentListener).invoke()
        assertTrue(quiescent)
    }

    @Test
    fun delegateAndCompletionExceptionsKeepTheirIdentityAndMetricsFailOpen() {
        val failure = IllegalStateException("private failure")
        val delegate = FakeExecutor().apply { thrown = failure }
        val recorder = activeRecorder()
        try { MeasuredDynamicToolExecutor(delegate, recorder).execute(call()) {}; fail("Expected failure") }
        catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertEquals("exception", samples(recorder).single()["outcome"])
        val brokenClock = activeRecorder { throw IllegalArgumentException("clock") }
        val working = FakeExecutor()
        var completed = false
        MeasuredDynamicToolExecutor(working, brokenClock).execute(call()) { completed = true }
        working.callback(result)
        assertTrue(completed)
        val callbackFailure = IllegalArgumentException("callback")
        MeasuredDynamicToolExecutor(working, recorder).execute(call()) { throw callbackFailure }
        try { working.callback(result); fail("Expected callback failure") }
        catch (actual: IllegalArgumentException) { assertSame(callbackFailure, actual) }
    }

    @Test
    fun optionalMetadataCannotBreakConstructionWhenDelegateCatalogThrows() {
        val failure = IllegalStateException("catalog unavailable")
        val delegate = object : DynamicToolExecutor {
            override val specs: List<DynamicToolNamespaceSpec> get() = throw failure
            override fun execute(call: DynamicToolCallParams, completion: (DynamicToolExecutionResult) -> Unit) = completion(result)
            override fun failureResult(call: DynamicToolCallParams, code: String) = result
        }
        val measured = MeasuredDynamicToolExecutor(delegate, activeRecorder())
        var completed = false
        measured.execute(call()) { completed = true }
        assertTrue(completed)
        try { measured.specs; fail("Delegated catalog failure must stay visible") }
        catch (actual: IllegalStateException) { assertSame(failure, actual) }
    }

    @Test
    fun absentTokensAreUnknownRatherThanZero() {
        val json = JSONObject(PerformanceDiagnostics.encode(null, null))
        assertTrue(json.isNull("tokenUsage"))
        assertTrue(json.isNull("tools"))
        assertEquals("process_memory_only", json.getString("retention"))
    }

    private class FakeExecutor : DynamicToolExecutor {
        override val specs = listOf(DynamicToolNamespaceSpec("android_ui", "UI", listOf(
            DynamicToolFunctionSpec("inspect_ui", "Inspect", "{\"type\":\"object\"}"),
        )))
        lateinit var callback: (DynamicToolExecutionResult) -> Unit
        var thrown: Exception? = null
        var cancellation: DynamicToolCancellation? = null
        var cancellationDisposition = DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED
        var cancels = 0
        var quiescentListener: (() -> Unit)? = null
        var quiescenceSupported = true
        var quiescenceRegistrations = 0
        var quiescenceFailure: Exception? = null
        var quiescenceImmediate = false
        override fun execute(call: DynamicToolCallParams, completion: (DynamicToolExecutionResult) -> Unit) {
            thrown?.let { throw it }
            callback = completion
        }
        override fun executeCancellable(call: DynamicToolCallParams, cancellation: DynamicToolCancellation,
            completion: (DynamicToolExecutionResult) -> Unit): DynamicToolExecutionHandle {
            this.cancellation = cancellation
            execute(call, completion)
            return object : DynamicToolExecutionHandle {
                override fun onQuiescent(listener: () -> Unit): Boolean {
                    quiescenceRegistrations++
                    quiescenceFailure?.let { throw it }
                    if (!quiescenceSupported) return false
                    if (quiescenceImmediate) { listener(); return true }
                    val previous = quiescentListener
                    quiescentListener = { previous?.invoke(); listener() }
                    return true
                }

                override fun cancel(): DynamicToolCancellationDisposition {
                    cancels++
                    return cancellationDisposition
                }
            }
        }
        override fun failureResult(call: DynamicToolCallParams, code: String) = result
    }

    @Suppress("UNCHECKED_CAST")
    private fun samples(recorder: ToolPerformanceRecorder) = recorder.snapshot()["samples"] as List<Map<String, Any>>
    private fun activeRecorder(clock: () -> Long = { 0L }) = ToolPerformanceRecorder(clock).apply {
        observeContext(1, "thread-private")
        start()
    }
    private fun call() = DynamicToolCallParams("thread-private", "turn-private", "call-private",
        "android_ui", "inspect_ui", "{\"text\":\"secret-argument\"}")
    private companion object { val result = DynamicToolExecutionResult("{}", true) }
}
