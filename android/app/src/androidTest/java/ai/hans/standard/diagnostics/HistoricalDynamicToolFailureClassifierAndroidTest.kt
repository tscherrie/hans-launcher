package ai.hans.standard.diagnostics

import ai.hans.standard.codex.AppServerEventDecoder
import ai.hans.standard.codex.CodexSessionReducer
import ai.hans.standard.codex.DeliveredServerEvent
import ai.hans.standard.codex.DeliveryCursor
import ai.hans.standard.codex.ToolStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Packaged Android JSON contract only. No phone actions, credentials, microphone or network. */
class HistoricalDynamicToolFailureClassifierAndroidTest {
    @Test fun fixedCategoriesAndNestedFailureReceiptWorkOnAndroidJson() {
        ToolFailureCode.entries.forEach { code ->
            assertEquals(code, classify("""{"errorCode":"${code.wire}"}""")!!.code)
        }
        assertEquals(ToolFailureDiagnostic(ToolFailureCode.SEMANTIC_FALLBACK_PROOF_REQUIRED,
            ToolFailureDetail.PROOF_CURRENT_SNAPSHOT_MISMATCH), classify(
            """{"failure":{"errorCode":"semantic_fallback_proof_required","detailCode":"proof_current_snapshot_mismatch"}}"""))
        assertEquals(ToolFailureDiagnostic(ToolFailureCode.POSTCONDITION_NOT_VERIFIED,
            ToolFailureDetail.ADAPTER_OPERATION_FAILED), classify(
            """{"errorCode":"postcondition_not_verified","postcondition":{"detailCode":"adapter_operation_failed"}}"""))
    }

    @Test fun malformedPrivateDeepDuplicateAndOversizedTextNeverEscapeOrThrow() {
        listOf(
            "SECRET", """{"code":"SECRET"}""", """{"arguments":{"code":"node_not_found"}}""",
            """{"code":"node_not_found"} SECRET""", """{'code':'node_not_found'}""",
            """{"code":"node_not_found","co\u0064e":"node_not_found"}""",
            "{\"code\":\"node_not_found\",\"n\":" + "[".repeat(2000) + "0" + "]".repeat(2000) + "}",
            """{"code":"node_not_found","private":"${"界".repeat(6000)}"}""",
        ).forEach { raw ->
            val result = classify(raw)
            assertEquals(ToolFailureDiagnostic(ToolFailureCode.UNKNOWN), result)
            assertFalse(result.toString().contains("SECRET"))
        }
    }

    @Test fun nativeCompletedFalseIsDiagnosticFailureWithoutChangingUiStatusOrOutput() {
        val item = item("""{"errorCode":"node_set_text_unsupported","message":"SECRET"}""")
            .put("status", "completed")
        val event = AppServerEventDecoder.decode(JSONObject().put("method", "item/completed")
            .put("params", JSONObject().put("threadId", "test-thread").put("turnId", "test-turn")
                .put("completedAtMs", 1).put("item", item)).toString())
        val snapshot = CodexSessionReducer().apply(DeliveredServerEvent(DeliveryCursor(1, 0), event))
            .snapshot.threads.single().tools.single()
        assertEquals(ToolStatus.COMPLETED, snapshot.status)
        assertEquals(ToolFailureCode.NODE_SET_TEXT_UNSUPPORTED, snapshot.failureDiagnostic!!.code)
        assertNull(snapshot.output)
        assertFalse(snapshot.toString().contains("SECRET"))
        assertNull(HistoricalDynamicToolFailureClassifier.classify(item.put("success", true)))
        assertNull(HistoricalDynamicToolFailureClassifier.classify(item.put("status", "inProgress")))
    }

    private fun classify(raw: String) = HistoricalDynamicToolFailureClassifier.classify(item(raw))
    private fun item(raw: String) = JSONObject().put("type", "dynamicToolCall").put("id", "test-tool")
        .put("tool", "set_text").put("arguments", JSONObject()).put("status", "failed").put("success", false)
        .put("contentItems", JSONArray().put(JSONObject().put("type", "inputText").put("text", raw)))
}
