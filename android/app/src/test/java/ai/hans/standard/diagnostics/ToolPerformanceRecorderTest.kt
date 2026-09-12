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
        assertEquals(1_234L, record["durationMicros"])
        assertEquals(result.contentText.toByteArray(Charsets.UTF_8).size, record["resultTextUtf8Bytes"])
        assertEquals("android_ui.inspect_ui", record["tool"])
        val json = MigrationCanonicalJson.encode(recorder.snapshot())
        listOf("秘密", "thread-private", "turn-private", "call-private", "secret-argument").forEach {
            assertFalse(json.contains(it))
        }
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
        assertEquals("cancel_requested_effect_may_have_started", samples(recorder).single()["outcome"])
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
        var cancels = 0
        override fun execute(call: DynamicToolCallParams, completion: (DynamicToolExecutionResult) -> Unit) {
            thrown?.let { throw it }
            callback = completion
        }
        override fun executeCancellable(call: DynamicToolCallParams, cancellation: DynamicToolCancellation,
            completion: (DynamicToolExecutionResult) -> Unit): DynamicToolExecutionHandle {
            this.cancellation = cancellation
            execute(call, completion)
            return object : DynamicToolExecutionHandle {
                override fun cancel(): DynamicToolCancellationDisposition {
                    cancels++
                    return DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED
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
