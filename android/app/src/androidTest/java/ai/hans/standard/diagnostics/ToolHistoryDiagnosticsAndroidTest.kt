package ai.hans.standard.diagnostics

import ai.hans.standard.codex.ToolStatus
import ai.hans.standard.codex.ToolUiSnapshot
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Android JSON plus the exact passive encoder. No account, network, audio or other apps. */
class ToolHistoryDiagnosticsAndroidTest {
    @Test fun exportIsBoundedAndDoesNotContainToolContent() {
        val secret = "PRIVATE-NOT-FOR-DUMP"
        val tool = ToolUiSnapshot(secret, secret, "dynamicToolCall", secret,
            ToolStatus.FAILED, secret, true,
            ToolFailureDiagnostic.fromCodes(secret, secret))
        val encoded = ToolHistoryDiagnostics.encodeTools(List(100) { tool }, true)
        assertFalse(encoded.contains(secret))
        val result = JSONObject(encoded)
        assertEquals(76, result.getInt("omitted"))
        assertEquals(24, result.getJSONArray("failures").length())
        assertEquals("unknown", result.getJSONArray("failures").getJSONObject(0).getString("tool"))
        assertTrue(encoded.length < 8_192)
    }

    @Test fun noSessionIsNotTreatedAsCompleteHistoricalEvidence() {
        val result = JSONObject(ToolHistoryDiagnostics.encode(null))
        assertFalse(result.getBoolean("snapshotAvailable"))
        assertEquals(0, result.getJSONArray("failures").length())
        assertTrue(result.getString("historyMeaning").contains("not_complete_persisted_history"))
    }
}
