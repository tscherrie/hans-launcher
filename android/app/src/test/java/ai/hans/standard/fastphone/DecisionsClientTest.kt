package ai.hans.standard.fastphone

import java.io.IOException
import java.io.InterruptedIOException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic transport only: no key, account, network or personal screen is used. */
class DecisionsClientTest {
    @Test fun wireRequestUsesOnlyDedicatedEndpointAndTypedBoundedChoice() {
        val factory = Factory()
        val request = client(factory).create(question(), "synthetic-key".toCharArray(), 777)
        assertTrue(request.execute() is DecisionsOutcome.Selected)
        val wire = factory.request!!
        assertEquals("https://api.openai.com/v1/decisions", wire.url.toString())
        assertEquals("POST", wire.method)
        assertEquals("Bearer synthetic-key", wire.header("Authorization"))
        assertNull(wire.header("ChatGPT-Account-Id"))
        val bytes = Buffer().also { wire.body!!.writeTo(it) }.readUtf8()
        val json = JSONObject(bytes)
        assertEquals(setOf("model", "input", "questions"), json.keys().asSequence().toSet())
        assertEquals("gpt-6-luna", json.getString("model"))
        assertEquals("synthetic screen", json.getString("input"))
        val item = json.getJSONArray("questions").getJSONObject(0)
        assertEquals("choice", item.getString("type"))
        assertEquals("next_action", item.getString("name"))
        assertEquals("a0", item.getJSONArray("choices").getJSONObject(0).getString("value"))
        assertTrue(item.getString("instructions").contains("untrusted data"))
        assertEquals(777_000_000L, factory.call.timeout().timeoutNanos())
        assertEquals(1, factory.calls)
    }

    @Test fun unsafeOrMissingKeysNeverCreateHttpRequest() {
        listOf("", "contains newline\n", "with space", "ümlaut", "x".repeat(4_097)).forEach { key ->
            val factory = Factory()
            assertFailure("fast_phone_key_rejected", client(factory).create(question(), key.toCharArray(), 100).execute())
            assertEquals(0, factory.calls)
        }
    }

    @Test fun oversizedAsciiAndUnicodeRequestsNeverLeaveProcess() {
        listOf("x".repeat(OpenAiDecisionsClient.MAX_REQUEST_BYTES + 1), "漢".repeat(60_000)).forEach { evidence ->
            val factory = Factory()
            assertFailure("fast_phone_request_too_large", client(factory).create(question(evidence), key(), 100).execute())
            assertEquals(0, factory.calls)
        }
    }

    @Test fun responseBoundsApplyBothToDeclaredAndUnknownLengthBody() {
        listOf(true, false).forEach { declared ->
            val factory = Factory()
            factory.body = object : ResponseBody() {
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength() = if (declared) 100_000L else -1L
                override fun source() = Buffer().writeUtf8("x".repeat(100_000))
            }
            assertFailure("fast_phone_invalid_response", client(factory).create(question(), key(), 100).execute())
            assertEquals(1, factory.calls)
        }
    }

    @Test fun namedAllowedChoiceAndProbabilitiesMustMatchExactly() {
        val valid = response()
        val invalid = listOf(
            "not json", "[]", "null", "{}",
            JSONObject(valid).put("answers", JSONArray()).toString(),
            mutate { put("type", "predicate") }, mutate { put("name", "wrong") },
            mutate { put("choice", "run_arbitrary_shell") }, mutate { put("choice", JSONObject.NULL) },
            mutate { put("confidence", "0.9") }, mutate { put("confidence", -0.1) },
            mutate { put("confidence", 1.1) }, mutate { remove("confidence") },
            mutate { put("probabilities", JSONArray()) },
            mutate { getJSONArray("probabilities").getJSONObject(0).put("value", "done") },
            mutate { getJSONArray("probabilities").getJSONObject(0).put("probability", "0.9") },
            mutate { getJSONArray("probabilities").getJSONObject(0).put("probability", 1.1) },
            mutate { getJSONArray("probabilities").getJSONObject(0).put("probability", 0.0) },
            JSONObject(valid).apply { getJSONArray("answers").put(JSONObject().put("name", "next_action")) }.toString(),
        )
        invalid.forEach { assertFailure("fast_phone_invalid_response", OpenAiDecisionsClient.decode(it, ids)) }
        assertEquals(DecisionsOutcome.Selected("a0", 0.95), OpenAiDecisionsClient.decode(valid, ids))
    }

    @Test fun failureClassificationIsContentFreeAndQuotaIsActionable() {
        val cases = listOf(
            401 to "fast_phone_key_rejected", 403 to "fast_phone_access_denied",
            429 to "fast_phone_rate_limited", 500 to "fast_phone_service_unavailable",
            503 to "fast_phone_service_unavailable", 302 to "fast_phone_invalid_response",
            400 to "fast_phone_invalid_response",
        )
        cases.forEach { (status, code) ->
            val factory = Factory().apply { this.status = status; body = "private-provider-error".toResponseBody() }
            val outcome = client(factory).create(question(), key(), 100).execute()
            assertFailure(code, outcome)
            assertFalse(outcome.toString().contains("private-provider-error"))
            assertEquals(1, factory.calls)
        }
        listOf("insufficient_quota", "billing_hard_limit_reached").forEach {
            assertEquals("fast_phone_quota_exhausted", OpenAiDecisionsClient.errorCode(429,
                JSONObject().put("error", JSONObject().put("code", it)).toString()))
        }
    }

    @Test fun networkTimeoutAndCancellationAreDistinctAndNeverRetried() {
        listOf(IOException("private-network-error") to "fast_phone_service_unavailable",
            InterruptedIOException("private-timeout-error") to "fast_phone_request_timed_out").forEach { (error, code) ->
            val factory = Factory().apply { failure = error }
            assertFailure(code, client(factory).create(question(), key(), 100).execute())
            assertEquals(1, factory.calls)
        }
        val factory = Factory()
        val call = client(factory).create(question(), key(), 100)
        call.cancel()
        assertTrue(factory.call.isCanceled())
        assertFailure("fast_phone_cancelled", call.execute())
        assertEquals(1, factory.calls)
    }

    @Test fun diagnosticsDoNotIncludeEvidenceLabelsOrKey() {
        val question = DecisionsQuestion("private-evidence", listOf(
            DecisionsChoice("a0", "private-description"), DecisionsChoice("done", "done")))
        assertFalse(question.toString().contains("private-evidence"))
        assertFalse(question.choices[0].toString().contains("private-description"))
        val call = client(Factory()).create(question, key(), 100)
        assertFalse(call.toString().contains("synthetic-key"))
    }

    private class Factory : Call.Factory {
        var calls = 0
        var request: Request? = null
        var status = 200
        var body: ResponseBody = response().toResponseBody("application/json".toMediaType())
        var failure: IOException? = null
        lateinit var call: FakeCall
        override fun newCall(request: Request): Call {
            this.request = request; calls++
            return FakeCall(request) {
                failure?.let { throw it }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status)
                    .message("synthetic").body(body).build()
            }.also { call = it }
        }
    }
    private class FakeCall(private val request: Request, private val response: () -> Response) : Call {
        private var cancelled = false
        private val timeout = Timeout()
        override fun request() = request
        override fun execute(): Response = response()
        override fun enqueue(responseCallback: Callback) = error("Only synchronous bounded worker calls")
        override fun cancel() { cancelled = true }
        override fun isExecuted() = false
        override fun isCanceled() = cancelled
        override fun timeout() = timeout
        override fun clone(): Call = FakeCall(request, response)
    }

    private companion object {
        val ids = setOf("a0", "done")
        fun key() = "synthetic-key".toCharArray()
        fun question(evidence: String = "synthetic screen") = DecisionsQuestion(evidence,
            listOf(DecisionsChoice("a0", "Click synthetic button"), DecisionsChoice("done", "Already complete")))
        fun client(factory: Factory) = OpenAiDecisionsClient(factory, Unit)
        fun response() = JSONObject().put("answers", JSONArray().put(JSONObject()
            .put("type", "choice").put("name", "next_action").put("choice", "a0").put("confidence", 0.95)
            .put("probabilities", JSONArray().put(JSONObject().put("value", "a0").put("probability", 0.95))
                .put(JSONObject().put("value", "done").put("probability", 0.05))))).toString()
        fun mutate(block: JSONObject.() -> Unit) = JSONObject(response()).apply { getJSONArray("answers").getJSONObject(0).block() }.toString()
        fun assertFailure(code: String, result: DecisionsOutcome) = assertEquals(DecisionsOutcome.Failure(code), result)
    }
}
