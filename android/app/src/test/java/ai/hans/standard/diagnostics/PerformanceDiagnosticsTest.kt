package ai.hans.standard.diagnostics

import ai.hans.standard.codex.*
import ai.hans.standard.integration.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PerformanceDiagnosticsTest {
    @Test
    fun onlySelectedAuthoritativeCountersAppearAndNoPrivateIdentifiersOrText() {
        val state = client()
        val json = JSONObject(PerformanceDiagnostics.encode(state, null))
        val tokens = json.getJSONObject("tokenUsage")
        assertEquals(100L, tokens.getJSONObject("last").getLong("inputTokens"))
        assertEquals(80L, tokens.getJSONObject("last").getLong("cachedInputTokens"))
        assertEquals(1_000L, tokens.getJSONObject("total").getLong("inputTokens"))
        assertEquals(400_000L, tokens.getLong("modelContextWindow"))
        assertTrue(tokens.getJSONObject("last").isNull("cacheWriteInputTokens"))
        listOf("thread-private", "foreign-private", "sensitive preview", "message-private", "secret text")
            .forEach { assertFalse(json.toString().contains(it)) }
    }

    @Test
    fun missingGenerationSignOutOrMissingSelectedThreadCannotExposeOldCounters() {
        val state = client()
        val variants = listOf(
            state.copy(generation = null),
            state.copy(session = state.session.copy(currentThreadId = "not-loaded")),
            state.copy(session = state.session.copy(account = state.session.account.copy(phase = AccountPhase.SIGNED_OUT))),
            state.copy(session = state.session.copy(threads = emptyList())),
        )
        variants.forEach { assertTrue(JSONObject(PerformanceDiagnostics.encode(it, null)).isNull("tokenUsage")) }
        assertEquals(null to null, PerformanceDiagnostics.context(variants[2]))
    }

    @Test
    fun boundedMaximumSamplesAndCountersHaveSmallContentFreePayload() {
        val recorder = ToolPerformanceRecorder(capacity = 128)
        recorder.observeContext(1, "thread-private")
        recorder.start()
        repeat(256) {
            val call = DynamicToolCallParams("thread-private", "turn", "call", "android_ui", "x".repeat(64), "{}")
            recorder.finish(recorder.begin(call, true), "cancel_requested_effect_may_have_started")
        }
        val payload = PerformanceDiagnostics.encode(client(), recorder.snapshot())
        assertTrue(payload.toByteArray(Charsets.UTF_8).size < 48 * 1_024)
        assertEquals(128, JSONObject(payload).getJSONObject("tools").getJSONArray("samples").length())
    }

    private fun client() = CodexClientSnapshot(
        runtimePhase = ClientRuntimePhase.READY,
        sessionPhase = ClientSessionPhase.READY,
        generation = 1,
        session = SessionUiSnapshot(
            account = AccountUiSnapshot(AccountPhase.SIGNED_IN, null, null, null, null, null),
            currentThreadId = "thread-private",
            threads = listOf(thread("thread-private", 100), thread("foreign-private", 999)),
            delivery = DeliveryUiSnapshot(1, 1, null, false),
        ),
        models = emptyList(), deviceCodeLogin = null, outboundTimeline = emptyList(), timeline = emptyList(),
        pendingSelection = null,
        confirmedSelection = DispatchSelection(DispatchOptions.DEFAULT.model, DispatchOptions.DEFAULT.effort),
        problem = null,
    )

    private fun thread(id: String, input: Long) = ThreadUiSnapshot(
        threadId = id, name = "sensitive preview", preview = "sensitive preview",
        runtimeStatus = ThreadStatusSnapshot(ThreadRuntimeStatus.IDLE), effectiveOptions = null,
        currentTurn = null,
        messages = listOf(MessageUiSnapshot("message-private", "turn", "secret text", null, true)),
        tools = emptyList(),
        tokenUsage = ThreadTokenUsageSnapshot(
            last = TokenUsageBreakdown(input, 80, 20, 5, input + 20),
            total = TokenUsageBreakdown(1_000, 800, 200, 50, 1_200),
            modelContextWindow = 400_000,
        ),
    )
}
