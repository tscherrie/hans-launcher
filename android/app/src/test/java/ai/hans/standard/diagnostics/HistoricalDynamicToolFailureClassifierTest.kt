package ai.hans.standard.diagnostics

import ai.hans.standard.codex.AppServerEventDecoder
import ai.hans.standard.codex.CodexSessionReducer
import ai.hans.standard.codex.DeliveredServerEvent
import ai.hans.standard.codex.DeliveryCursor
import ai.hans.standard.codex.ServerEvent
import ai.hans.standard.codex.StreamItem
import ai.hans.standard.codex.ToolStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HistoricalDynamicToolFailureClassifierTest {
    @Test fun sharedNativeHistoryValidatorRequiresExplicitBoundedLargerBudgets() {
        val nested = "{\"n\":" + "[".repeat(14) + "0" + "]".repeat(14) + "}"
        assertFalse(runCatching { StrictDiagnosticJson(nested).accepts() }.getOrDefault(false))
        assertTrue(StrictDiagnosticJson(nested, maxDepth = 32, maxValues = 32_768).accepts())
        assertFalse(runCatching {
            StrictDiagnosticJson("{\"n\":[0,1,2,3]}", maxValues = 4).accepts()
        }.getOrDefault(false))
        assertThrows(IllegalArgumentException::class.java) { StrictDiagnosticJson("{}", maxDepth = 33) }
        assertThrows(IllegalArgumentException::class.java) { StrictDiagnosticJson("{}", maxDepth = 0) }
        assertThrows(IllegalArgumentException::class.java) { StrictDiagnosticJson("{}", maxValues = 32_769) }
        assertThrows(IllegalArgumentException::class.java) { StrictDiagnosticJson("{}", maxValues = 0) }
    }

    @Test fun exactCodeAndDetailAllowlistsArePreservedWithoutPayload() {
        ToolFailureCode.entries.filter { it != ToolFailureCode.UNKNOWN }.forEach { code ->
            assertEquals(code, classify("""{"errorCode":"${code.wire}","private":"SECRET"}""")!!.code)
        }
        ToolFailureDetail.entries.filter { it != ToolFailureDetail.UNKNOWN }.forEach { detail ->
            val result = classify("""{"errorCode":"semantic_fallback_proof_required","detailCode":"${detail.wire}"}""")!!
            assertEquals(detail, result.detail)
            assertFalse(result.toString().contains("SECRET"))
        }
    }

    @Test fun failedLegacyAndCompletedFalseAreFailuresButSuccessfulAndRunningItemsAreNot() {
        val raw = """{"code":"node_set_text_unsupported"}"""
        val legacy = item(raw).apply { remove("success") }
        assertEquals(ToolFailureCode.NODE_SET_TEXT_UNSUPPORTED, classify(legacy)!!.code)
        assertEquals(ToolFailureCode.NODE_SET_TEXT_UNSUPPORTED,
            classify(item(raw).put("status", "completed"))!!.code)
        assertNull(classify(item(raw).put("status", "completed").put("success", true)))
        assertNull(classify(item(raw).put("status", "completed").put("success", "false")))
        assertNull(classify(item(raw).put("status", "inProgress")))
        assertNull(classify(item(raw).put("type", "mcpToolCall")))
        assertEquals(unknown, classify(item(raw).put("success", true)))
        assertEquals(unknown, classify(item(raw).put("success", "false")))
    }

    @Test fun malformedMissingUnknownAndConflictingFieldsAreUnclassified() {
        listOf(
            "", "SECRET", "{}", "null", "[]", "{\"code\":false}",
            """{"errorCode":"SECRET"}""",
            """{"code":"node_not_editable_SECRET"}""",
            """{"code":"node_not_editable","errorCode":"node_not_found"}""",
            """{"code":"node_not_editable","detailCode":17}""",
            """{"code":"node_not_editable","detailCode":"no_active_root","postcondition":{"detailCode":"projection_failed"}}""",
        ).forEach { assertEquals(it, unknown, classify(it)) }
        assertEquals(unknown, classify(item("{}").apply { remove("contentItems") }))
        assertEquals(unknown, classify(item("{}").put("contentItems", "SECRET")))
        assertEquals(ToolFailureDiagnostic(ToolFailureCode.NODE_NOT_EDITABLE, ToolFailureDetail.UNKNOWN),
            classify("""{"code":"node_not_editable","detailCode":"SECRET"}"""))
    }

    @Test fun sourceEnvelopeLocationsArePreciseNeverSearchedRecursively() {
        val expected = ToolFailureDiagnostic(ToolFailureCode.POSTCONDITION_NOT_VERIFIED,
            ToolFailureDetail.ADAPTER_OPERATION_FAILED)
        val receipt = """{"errorCode":"postcondition_not_verified","postcondition":{"detailCode":"adapter_operation_failed","before":{"SECRET":"SECRET"}}}"""
        assertEquals(expected, classify(receipt))
        assertEquals(expected, classify("""{"status":"failed","failure":$receipt,"steps":[{"result":{"errorCode":"node_not_found"}}]}"""))
        assertEquals(unknown, classify("""{"arguments":{"errorCode":"node_not_found"}}"""))
        assertEquals(unknown, classify("""{"steps":[{"result":{"errorCode":"node_not_found"}}]}"""))
        assertEquals(unknown, classify("""{"message":"{\"errorCode\":\"node_not_found\"}"}"""))
        assertEquals(ToolFailureDiagnostic(ToolFailureCode.SEMANTIC_FALLBACK_PROOF_REQUIRED),
            classify("""{"errorCode":"semantic_fallback_proof_required"}"""))
    }

    @Test fun imageAndAudioDataNeverBecomeDiagnosticsAndExtraTextIsAmbiguous() {
        val parts = item("""{"code":"visual_capture_platform_rejected"}""")
            .getJSONArray("contentItems")
        parts.put(JSONObject().put("type", "inputImage").put("imageUrl", "SECRET"))
        parts.put(JSONObject().put("type", "inputAudio").put("audioUrl", "SECRET"))
        val value = item("{}").put("contentItems", parts)
        assertEquals(ToolFailureCode.VISUAL_CAPTURE_PLATFORM_REJECTED, classify(value)!!.code)
        parts.put(JSONObject().put("type", "inputText").put("text", "{\"code\":\"node_not_found\"}"))
        assertEquals(unknown, classify(value))
        assertEquals(unknown, classify(item("{}").put("contentItems", JSONArray()
            .put(JSONObject().put("type", "inputImage").put("text", "{\"code\":\"node_not_found\"}")))))
    }

    @Test fun strictJsonRejectsAndroidLeniencyAndDeepNestingBeforeObjectAllocation() {
        listOf(
            """{"code":"node_not_found"} trailing""",
            """{"code":"node_not_found","code":"node_not_found"}""",
            """{"code":"node_not_found","co\u0064e":"node_not_found"}""",
            """{'code':'node_not_found'}""",
            """{code:"node_not_found"}""",
            """{"code":"node_not_found",}""",
            """{/*comment*/"code":"node_not_found"}""",
            """{"code":"node_not_found","value":NaN}""",
            """{"code":"node_not_found","value":01}""",
            """{"code":"node_not_found","value":[1,]}""",
            "{\"code\":\"node_not_found\",\"value\":" + "[".repeat(2000) + "0" + "]".repeat(2000) + "}",
        ).forEach { assertEquals(unknown, classify(it)) }
        assertEquals(ToolFailureCode.NODE_NOT_FOUND,
            classify("""{"co\u0064e":"node_not_found","text":"escaped \"quote\", \\ and \n","n":-1.2e+3}""")!!.code)
    }

    @Test fun boundedTextPartsAndNodeCountsDegradeWithoutThrowing() {
        val base = "{\"code\":\"node_not_found\",\"text\":\"" + "x".repeat(16_384) + "\"}"
        assertEquals(unknown, classify(base))
        assertEquals(unknown, classify("""{"code":"node_not_found","text":"${"界".repeat(6000)}"}"""))
        assertEquals(unknown, classify("""{"code":"node_not_found","n":[${List(2050) { "0" }.joinToString(",") }]}"""))
        val parts = JSONArray().put(JSONObject().put("type", "inputText").put("text", "{\"code\":\"node_not_found\"}"))
        repeat(8) { parts.put(JSONObject().put("type", "inputImage")) }
        assertEquals(unknown, classify(item("{}").put("contentItems", parts)))
        assertEquals(unknown, classify("""{"failure":{"failure":{"failure":{"failure":{"code":"node_not_found"}}}}}"""))
    }

    @Test fun liveDecoderReducerKeepsOnlyTypedFailureAndDoesNotExposeOutputOrRollBackStatus() {
        val raw = item("""{"errorCode":"node_not_editable","privateMessage":"SECRET_TEXT"}""")
            .put("arguments", JSONObject().put("message", "SECRET_ARGUMENT"))
        val completed = decode(raw)
        val tool = completed.item as StreamItem.DynamicTool
        assertFalse(tool.toString().contains("SECRET"))
        val reducer = CodexSessionReducer()
        reducer.apply(DeliveredServerEvent(DeliveryCursor(1, 0), completed))
        var result = reducer.snapshot().threads.single().tools.single()
        assertEquals(ToolStatus.FAILED, result.status)
        assertTrue(result.complete)
        assertNull(result.output)
        assertEquals(ToolFailureCode.NODE_NOT_EDITABLE, result.failureDiagnostic!!.code)
        assertFalse(result.toString().contains("SECRET"))
        reducer.apply(DeliveredServerEvent(DeliveryCursor(1, 1), ServerEvent.ItemStarted(
            "thread", "turn", StreamItem.DynamicTool("tool", "set_text", ToolStatus.IN_PROGRESS), 0)))
        result = reducer.snapshot().threads.single().tools.single()
        assertEquals(ToolStatus.FAILED, result.status)
        assertEquals(ToolFailureCode.NODE_NOT_EDITABLE, result.failureDiagnostic!!.code)
    }

    @Test fun completedFalsePreservesNativeStatusAndMalformedMetadataNeverBreaksDecoder() {
        val reducer = CodexSessionReducer()
        val decoded = decode(item("SECRET").put("status", "completed"))
        reducer.apply(DeliveredServerEvent(DeliveryCursor(1, 0), decoded))
        val result = reducer.snapshot().threads.single().tools.single()
        assertEquals(ToolStatus.COMPLETED, result.status)
        assertEquals(unknown, result.failureDiagnostic)
        assertNull(result.output)
    }

    private fun decode(item: JSONObject): ServerEvent.ItemCompleted = AppServerEventDecoder.decode(
        JSONObject().put("method", "item/completed").put("params", JSONObject()
            .put("threadId", "thread").put("turnId", "turn").put("completedAtMs", 1)
            .put("item", item)).toString()) as ServerEvent.ItemCompleted

    private fun classify(raw: String) = classify(item(raw))
    private fun classify(item: JSONObject) = HistoricalDynamicToolFailureClassifier.classify(item)
    private fun item(raw: String) = JSONObject().put("type", "dynamicToolCall").put("id", "tool")
        .put("tool", "set_text").put("arguments", JSONObject()).put("status", "failed").put("success", false)
        .put("contentItems", JSONArray().put(JSONObject().put("type", "inputText").put("text", raw)))
    private val unknown = ToolFailureDiagnostic(ToolFailureCode.UNKNOWN)
}
