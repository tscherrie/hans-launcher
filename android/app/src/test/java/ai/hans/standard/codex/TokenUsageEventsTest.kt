package ai.hans.standard.codex

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TokenUsageEventsTest {
    @Test
    fun decodesDistinctLastAndCumulativeCountersWithoutRetainingExtraPayload() {
        val params = params()
        params.getJSONObject("tokenUsage").apply {
            put("modelContextWindow", 400_000L)
            getJSONObject("total").put("inputTokens", Long.MAX_VALUE)
            getJSONObject("total").put("cacheWriteInputTokens", 11L)
            put("futureContent", "MUST_NOT_RETAIN")
        }
        val event = decode(params)
        assertEquals("thread/tokenUsage/updated", event.method)
        assertEquals("thread-1", event.threadId)
        assertEquals("turn-1", event.turnId)
        assertEquals(10L, event.tokenUsage.last.inputTokens)
        assertEquals(Long.MAX_VALUE, event.tokenUsage.total.inputTokens)
        assertEquals(11L, event.tokenUsage.total.cacheWriteInputTokens)
        assertEquals(400_000L, event.tokenUsage.modelContextWindow)
        assertFalse(event.toString().contains("MUST_NOT_RETAIN"))
    }

    @Test
    fun missingOptionalCacheCounterAndUnavailableWindowRemainUnknown() {
        assertNull(decode(params()).tokenUsage.last.cacheWriteInputTokens)
        assertNull(decode(params()).tokenUsage.modelContextWindow)
        for (window in listOf(JSONObject.NULL, 0L, -1L)) {
            val params = params()
            params.getJSONObject("tokenUsage").put("modelContextWindow", window)
            assertNull(decode(params).tokenUsage.modelContextWindow)
        }
    }

    @Test
    fun everyRequiredCounterRejectsMissingNullNegativeAndCoercedValues() {
        for (part in listOf("last", "total")) {
            for (field in requiredCounters) {
                val missing = params()
                missing.getJSONObject("tokenUsage").getJSONObject(part).remove(field)
                assertThrows(MalformedEnvelopeException::class.java) { decode(missing) }
                for (literal in listOf("null", "-1", "\"12\"", "true", "1.5", "1.0", "1e0", "9223372036854775808", "{}", "[]")) {
                    assertMalformedNumber(part, field, literal)
                }
            }
        }
    }

    @Test
    fun optionalNumbersStillRejectWrongTypesAndOverflow() {
        for (literal in listOf("null", "-1", "\"12\"", "false", "1.5", "9223372036854775808")) {
            assertMalformedNumber("last", "cacheWriteInputTokens", literal)
        }
        for (literal in listOf("\"12\"", "true", "1.0", "9223372036854775808")) {
            val params = params()
            params.getJSONObject("tokenUsage").put("modelContextWindow", "REPLACE_VALUE")
            assertThrows(MalformedEnvelopeException::class.java) {
                AppServerEventDecoder.decode(envelope(params).replace("\"REPLACE_VALUE\"", literal))
            }
        }
    }

    @Test
    fun correlationAndNestedObjectsAreRequired() {
        for (field in listOf("threadId", "turnId", "tokenUsage")) {
            val params = params().apply { remove(field) }
            assertThrows(MalformedEnvelopeException::class.java) { decode(params) }
        }
        for (field in listOf("last", "total")) {
            val params = params()
            params.getJSONObject("tokenUsage").remove(field)
            assertThrows(MalformedEnvelopeException::class.java) { decode(params) }
        }
    }

    private fun assertMalformedNumber(part: String, field: String, literal: String) {
        val params = params()
        params.getJSONObject("tokenUsage").getJSONObject(part).put(field, "REPLACE_VALUE")
        assertThrows(MalformedEnvelopeException::class.java) {
            AppServerEventDecoder.decode(envelope(params).replace("\"REPLACE_VALUE\"", literal))
        }
    }

    private fun decode(params: JSONObject): ServerEvent.ThreadTokenUsageUpdated =
        AppServerEventDecoder.decode(envelope(params)) as ServerEvent.ThreadTokenUsageUpdated

    private fun envelope(params: JSONObject): String = JSONObject()
        .put("method", "thread/tokenUsage/updated").put("params", params).toString()

    private fun params(): JSONObject = JSONObject()
        .put("threadId", "thread-1")
        .put("turnId", "turn-1")
        .put("tokenUsage", JSONObject().put("last", counters(10)).put("total", counters(100)))

    private fun counters(value: Long): JSONObject = JSONObject().apply {
        requiredCounters.forEach { put(it, value) }
    }

    private val requiredCounters = listOf(
        "inputTokens", "cachedInputTokens", "outputTokens", "reasoningOutputTokens", "totalTokens",
    )
}
