package ai.hans.standard.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeToolHistoryTest {
    private val thread = "00000000-0000-0000-0000-000000000001"

    @Test fun normalizesPinnedNativeDurableItemNotTransientEvent() {
        val entry = NativeToolHistoryParser.failure(record(), thread)!!
        assertEquals("inspect_visual_ui", entry.tool)
        assertEquals(ToolFailureCode.SEMANTIC_FALLBACK_PROOF_REQUIRED, entry.failure.code)
        assertNull(NativeToolHistoryParser.failure(JSONObject(record()).apply {
            getJSONObject("payload").put("type", "dynamic_tool_call_response")
        }.toString(), thread))
    }

    @Test fun checksMetadataAndHistoryModeWithoutGuessingLegacy() {
        val meta = JSONObject().put("type", "session_meta").put("payload", JSONObject()
            .put("id", thread).put("history_mode", "paginated"))
        assertEquals(true, NativeToolHistoryParser.metadata(meta.toString(), thread))
        assertNull(NativeToolHistoryParser.metadata(meta.toString(), "another-thread"))
        meta.getJSONObject("payload").remove("history_mode")
        assertEquals(false, NativeToolHistoryParser.metadata(meta.toString(), thread))
        assertNull(NativeToolHistoryParser.metadata(record(), thread))
    }

    @Test fun doesNotAttributeAnotherThreadOrSuccessfulItem() {
        assertNull(NativeToolHistoryParser.failure(record(), "another-thread"))
        val success = JSONObject(record()).apply { getJSONObject("payload").getJSONObject("item")
            .put("status", "completed").put("success", true) }
        assertNull(NativeToolHistoryParser.failure(success.toString(), thread))
    }

    @Test fun neverExportsArgumentsMediaIdentifiersOrArbitraryCodesAndNames() {
        val secret = "PRIVATE-TEXT-TOKEN-DO-NOT-EXPORT"
        val json = JSONObject(record()).apply {
            getJSONObject("payload").getJSONObject("item")
                .put("tool", secret).put("id", secret).put("arguments", JSONObject().put("text", secret))
                .put("error", secret)
                .put("content_items", JSONArray().put(JSONObject().put("type", "inputText")
                    .put("text", JSONObject().put("errorCode", secret).toString()))
                    .put(JSONObject().put("type", "inputImage").put("imageUrl", secret)))
        }
        val entry = NativeToolHistoryParser.failure(json.toString(), thread)!!
        val encoded = NativeToolHistoryResult(NativeToolHistoryStatus.PARTIAL, listOf(entry), 1).encode()
        assertFalse(encoded.contains(secret))
        assertFalse(encoded.contains(thread))
        assertEquals(ToolFailureCode.UNKNOWN, entry.failure.code)
        assertEquals("unknown", entry.tool)
    }

    @Test fun malformedDuplicateDeepAndOversizeRecordsFailBoundedly() {
        listOf("{", "{\"type\":\"session_meta\",\"type\":\"event_msg\"}",
            "{\"a\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}",
            " ".repeat(NativeToolHistoryParser.MAX_LINE_BYTES + 1)).forEach {
            assertNull(NativeToolHistoryParser.metadata(it, thread))
            assertTrue(runCatching { NativeToolHistoryParser.failure(it, thread) }.isFailure)
        }
    }

    @Test fun outputExplainsBoundedPersistenceAndCountsOmittedFailures() {
        val entry = NativeToolHistoryParser.failure(record(), thread)!!
        val output = JSONObject(NativeToolHistoryResult(NativeToolHistoryStatus.PARTIAL,
            List(24) { entry }, 30, skippedLines = 2, tailOnly = true).encode())
        assertEquals(6, output.getInt("omitted"))
        assertEquals(24, output.getJSONArray("failures").length())
        assertTrue(output.getBoolean("tailOnly"))
        assertTrue(output.getString("historyMeaning").contains("not_live_or_inherited"))
    }

    private fun record(): String = JSONObject().put("type", "event_msg").put("payload", JSONObject()
        .put("type", "item_completed").put("thread_id", thread).put("turn_id", "private-turn")
        .put("item", JSONObject().put("type", "DynamicToolCall").put("tool", "inspect_visual_ui")
            .put("status", "failed").put("success", false)
            .put("content_items", JSONArray().put(JSONObject().put("type", "inputText")
                .put("text", "{\"errorCode\":\"semantic_fallback_proof_required\"}"))))).toString()
}
