package ai.hans.standard.diagnostics

import ai.hans.standard.codex.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolHistoryDiagnosticsTest {
    @Test fun onlySelectedThreadIsExportedWithoutItsPrivateIdentityOrPreview() {
        fun thread(id: String, name: String) = ThreadUiSnapshot(
            id, "PRIVATE-PREVIEW", "PRIVATE-PREVIEW", ThreadStatusSnapshot(ThreadRuntimeStatus.IDLE),
            null, null, emptyList(), listOf(tool(name)),
        )
        val session = SessionUiSnapshot(
            AccountUiSnapshot(AccountPhase.SIGNED_IN, null, null, null, null, null),
            "PRIVATE-SELECTED", listOf(thread("PRIVATE-OTHER", "click_ui"), thread("PRIVATE-SELECTED", "inspect_ui")),
            DeliveryUiSnapshot(1, 1, null, false),
        )
        val encoded = ToolHistoryDiagnostics.encode(session)
        assertFalse(encoded.contains("PRIVATE"))
        val failures = JSONObject(encoded).getJSONArray("failures")
        assertEquals(1, failures.length())
        assertEquals("inspect_ui", failures.getJSONObject(0).getString("tool"))
        assertFalse(JSONObject(ToolHistoryDiagnostics.encode(session.copy(currentThreadId = null)))
            .getBoolean("snapshotAvailable"))
    }

    @Test fun absentSessionDoesNotClaimHistoryAvailable() {
        val json = JSONObject(ToolHistoryDiagnostics.encode(null))
        assertFalse(json.getBoolean("snapshotAvailable"))
        assertEquals(0, json.getJSONArray("failures").length())
        assertTrue(json.getString("historyMeaning").contains("not_complete_persisted_history"))
    }

    @Test fun onlyFixedNamesAndTypedCodesAreExported() {
        val secret = "private-content-token-NEVER-EXPORT"
        val input = tool("inspect_visual_ui").copy(itemId = secret, turnId = secret, output = secret)
        val encoded = ToolHistoryDiagnostics.encodeTools(listOf(input, tool(secret)), true)
        assertFalse(encoded.contains(secret))
        val entries = JSONObject(encoded).getJSONArray("failures")
        assertEquals("inspect_visual_ui", entries.getJSONObject(0).getString("tool"))
        assertEquals("unknown", entries.getJSONObject(1).getString("tool"))
        assertEquals("semantic_fallback_proof_required", entries.getJSONObject(0).getString("code"))
    }

    @Test fun retainsOnlyRecentBoundedFailures() {
        val json = JSONObject(ToolHistoryDiagnostics.encodeTools(List(100) { tool("inspect_ui") }, true))
        assertEquals(100, json.getInt("failedToolCount"))
        assertEquals(76, json.getInt("omitted"))
        assertEquals(24, json.getJSONArray("failures").length())
        assertTrue(json.toString().length < 8_192)
    }

    @Test fun excludesSuccessfulRunningAndOtherToolTypes() {
        val result = JSONObject(ToolHistoryDiagnostics.encodeTools(listOf(
            tool("inspect_ui").copy(status = ToolStatus.COMPLETED, failureDiagnostic = null),
            tool("inspect_ui").copy(complete = false),
            tool("inspect_ui").copy(type = "commandExecution"),
            tool("inspect_ui").copy(failureDiagnostic = null),
        ), true))
        assertEquals(1, result.getJSONArray("failures").length())
        assertEquals("unknown", result.getJSONArray("failures").getJSONObject(0).getString("code"))
    }

    private fun tool(name: String) = ToolUiSnapshot(
        itemId = "item", turnId = "turn", type = "dynamicToolCall", label = name,
        status = ToolStatus.FAILED, output = null, complete = true,
        failureDiagnostic = ToolFailureDiagnostic(ToolFailureCode.SEMANTIC_FALLBACK_PROOF_REQUIRED),
    )
}
