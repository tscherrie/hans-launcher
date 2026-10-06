package ai.hans.standard.codex

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FreshThreadMaterializationProtocolTest {
    @Test fun requestsOnlyTheFreshIdentityWithoutModelInputOrHistoryModeOverride() {
        val request = AppServerRequests.materializeFreshThread(RequestId.Number(1), "fresh")
        val wire = JSONObject(request.json)
        assertEquals("thread/read", wire.getString("method"))
        assertEquals(setOf("threadId", "includeTurns"), wire.getJSONObject("params").keys().asSequence().toSet())
        assertEquals("fresh", wire.getJSONObject("params").getString("threadId"))
        assertTrue(wire.getJSONObject("params").getBoolean("includeTurns"))
        assertFalse(wire.has("input"))
        assertEquals(ThreadMaterializeResult("fresh"), decode(receipt()))
    }

    @Test fun rejectsDifferentIdentityAndNonFreshRuntimeState() {
        val changes: List<(JSONObject) -> Unit> = listOf(
            { it.put("id", "other") },
            { it.put("ephemeral", true) },
            { it.remove("ephemeral"); Unit },
            { it.put("historyMode", "legacy") },
            { it.remove("historyMode"); Unit },
            { it.put("turns", JSONArray().put(JSONObject().put("id", "unexpected"))) },
            { it.remove("turns"); Unit },
            { it.put("status", JSONObject().put("type", "active")) },
            { it.put("status", JSONObject().put("type", "notLoaded")) },
            { it.put("path", JSONObject.NULL) },
            { it.put("path", "relative.jsonl") },
        )
        changes.forEach { change ->
            val payload = receipt().also { change(it.getJSONObject("thread")) }
            assertThrows(IllegalArgumentException::class.java) { decode(payload) }
        }
    }

    @Test fun fullHistoryCannotAccidentallyEnterTheBootstrapReceipt() {
        val payload = receipt()
        payload.getJSONObject("thread").put("unexpectedMetadata", "x".repeat(ProtocolLimits.MAX_FRESH_THREAD_RECEIPT_BYTES))
        assertThrows(FrameLimitException::class.java) { decode(payload) }
    }

    @Test fun receiptDoesNotSelectAThreadOrHydrateHistoryInTheReducer() {
        val reducer = CodexSessionReducer()
        reducer.apply(CorrelatedResponse.Success(RequestId.Number(1), AppServerMethod.THREAD_READ,
            ThreadMaterializeResult("fresh")))
        assertEquals(null, reducer.snapshot().currentThreadId)
        assertTrue(reducer.snapshot().threads.isEmpty())
    }

    private fun decode(result: JSONObject): AppServerResult {
        val correlator = ResponseCorrelator()
        correlator.register(AppServerRequests.materializeFreshThread(RequestId.Number(1), "fresh"))
        return (correlator.accept(JSONObject().put("id", 1).put("result", result).toString())
            as CorrelatedResponse.Success).result
    }

    private fun receipt(): JSONObject = JSONObject().put("thread", JSONObject()
        .put("id", "fresh").put("ephemeral", false).put("historyMode", "paginated")
        .put("status", JSONObject().put("type", "idle"))
        .put("path", "/private/sessions/fresh.jsonl").put("turns", JSONArray()))
}
