package ai.hans.standard.voice.realtime

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiLiveSessionProviderTest {
    private val setup = LiveSessionSetup(LiveVoiceSessionConfig(), "Du bist Hans.")
    private val fakeKey = "test-only-not-a-real-api-key"
    private val validAnswer = """{"session":{"id":"live_123"},"transport":{"type":"webrtc","sdp":"answer"}}"""

    @Test
    fun createsExactlyOneJsonSessionAndOnlyReturnsAnswerToTransport() {
        val requests = AtomicInteger()
        val captured = AtomicReference<Request>()
        val provider = provider { chain ->
            requests.incrementAndGet()
            captured.set(chain.request())
            response(chain.request(), 201, validAnswer)
        }
        val callback = ResultCallback()
        provider.create(setup, "offer", callback)
        callback.await()
        assertEquals(1, requests.get())
        assertEquals(LiveSessionAnswer("live_123", "answer"), callback.answer)
        assertNull(callback.failure)
        val request = captured.get()
        assertEquals("https://api.openai.com/v1/live/sessions", request.url.toString())
        assertEquals("POST", request.method)
        assertEquals("Bearer $fakeKey", request.header("Authorization"))
        assertEquals("application/json", request.header("Accept"))
        assertTrue(request.body!!.isOneShot())
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val payload = buffer.readUtf8()
        assertFalse(payload.contains(fakeKey))
        assertEquals("gpt-live-1", JSONObject(payload).getJSONObject("session").getString("model"))
        assertEquals("offer", JSONObject(payload).getJSONObject("transport").getString("sdp"))
        assertFalse(callback.answer.toString().contains(fakeKey))
    }

    @Test
    fun missingOrUnreadableKeyFailsWithoutNetworkOrSecretContent() {
        val requests = AtomicInteger()
        val client = client { chain ->
            requests.incrementAndGet()
            response(chain.request(), 201, validAnswer)
        }
        val sources = listOf(
            LiveVoiceStandardKeySource { null },
            LiveVoiceStandardKeySource { "too short" },
            LiveVoiceStandardKeySource { throw IllegalStateException("private source detail") },
        )
        sources.forEach { source ->
            val callback = ResultCallback()
            OpenAiLiveSessionProvider(source, client).create(setup, "offer", callback)
            callback.await()
            assertEquals("live_standard_key_unavailable", callback.failure!!.code)
            assertFalse(callback.failure.toString().contains("private source detail"))
        }
        assertEquals(0, requests.get())
    }

    @Test
    fun malformedSetupFailsBeforeLoadingKeyOrStartingNetwork() {
        val keysLoaded = AtomicInteger()
        val requests = AtomicInteger()
        val provider = OpenAiLiveSessionProvider(
            LiveVoiceStandardKeySource { keysLoaded.incrementAndGet(); fakeKey },
            client { chain -> requests.incrementAndGet(); response(chain.request(), 201, validAnswer) },
        )
        val callback = ResultCallback()
        provider.create(setup, "", callback)
        callback.await()
        assertEquals("live_session_request_invalid", callback.failure!!.code)
        assertEquals(0, keysLoaded.get())
        assertEquals(0, requests.get())
    }

    @Test
    fun errorBodiesAreSafelyClassifiedWithoutAutomaticCreationRetry() {
        val scenarios = listOf(
            Triple(401, "invalid_api_key", "live_authentication_failed"),
            Triple(403, "", "live_permission_denied"),
            Triple(429, "insufficient_quota", "live_quota_exhausted"),
            Triple(429, "credit_balance_exhausted", "live_quota_exhausted"),
            Triple(429, "organization_spend_limit_exceeded", "live_spending_limit_reached"),
            Triple(429, "project_spend_limit_exceeded", "live_project_spending_limit_reached"),
            Triple(429, "rate_limit_exceeded", "live_rate_limited"),
            Triple(503, "", "live_http_server_error"),
            Triple(302, "", "live_unexpected_redirect"),
        )
        scenarios.forEach { (status, code, expected) ->
            val attempts = AtomicInteger()
            val provider = provider { chain ->
                attempts.incrementAndGet()
                response(chain.request(), status,
                    JSONObject().put("error", JSONObject().put("code", code)
                        .put("message", "private-error-detail")).toString())
                    .newBuilder().header("Retry-After", "0").header("Location", "https://example.invalid").build()
            }
            val callback = ResultCallback()
            provider.create(setup, "offer", callback)
            callback.await()
            assertEquals(expected, callback.failure!!.code)
            assertFalse(callback.failure!!.retryable)
            assertFalse(callback.failure.toString().contains("private-error-detail"))
            assertEquals(1, attempts.get())
        }
    }

    @Test
    fun unknownTransportOutcomeIsNotRetryableAndNeverExposesExceptionText() {
        val attempts = AtomicInteger()
        val provider = provider {
            attempts.incrementAndGet()
            throw IOException("private network detail")
        }
        val callback = ResultCallback()
        provider.create(setup, "offer", callback)
        callback.await()
        assertEquals("live_session_creation_unconfirmed", callback.failure!!.code)
        assertFalse(callback.failure!!.retryable)
        assertFalse(callback.failure.toString().contains("private network detail"))
        assertEquals(1, attempts.get())
    }

    @Test
    fun invalidOrOversizedSuccessBodyDoesNotProveStartupOrPermitBlindRetry() {
        for (body in listOf("not-json", "{}", " ".repeat(OpenAiLiveProtocol.MAX_EVENT_BYTES + 1))) {
            val callback = ResultCallback()
            provider { chain -> response(chain.request(), 201, body) }.create(setup, "offer", callback)
            callback.await()
            assertNull(callback.answer)
            assertEquals("live_session_creation_unconfirmed", callback.failure!!.code)
            assertFalse(callback.failure!!.retryable)
        }
    }

    @Test
    fun cancelSuppressesLateSuccessAndErrorCallbacks() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val provider = provider { chain ->
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            exited.countDown()
            response(chain.request(), 201, validAnswer)
        }
        val callback = ResultCallback()
        val cancellation = provider.create(setup, "offer", callback)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        cancellation.cancel()
        cancellation.cancel()
        release.countDown()
        assertTrue(exited.await(5, TimeUnit.SECONDS))
        assertFalse(callback.completed.await(250, TimeUnit.MILLISECONDS))
        assertNull(callback.answer)
        assertNull(callback.failure)
    }

    private fun provider(interceptor: Interceptor): OpenAiLiveSessionProvider =
        OpenAiLiveSessionProvider(LiveVoiceStandardKeySource { fakeKey }, client(interceptor))

    private fun client(interceptor: Interceptor): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(interceptor).build()

    private fun response(request: Request, status: Int, body: String): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(status).message("test response")
            .body(body.toResponseBody("application/json".toMediaType())).build()

    private class ResultCallback : LiveSessionProvider.Callback {
        val completed = CountDownLatch(1)
        @Volatile var answer: LiveSessionAnswer? = null
        @Volatile var failure: LiveVoiceFailure? = null

        override fun onCreated(answer: LiveSessionAnswer) {
            this.answer = answer
            completed.countDown()
        }

        override fun onFailure(failure: LiveVoiceFailure) {
            this.failure = failure
            completed.countDown()
        }

        fun await() { assertTrue("Provider did not complete", completed.await(5, TimeUnit.SECONDS)) }
    }
}
