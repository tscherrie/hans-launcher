package ai.hans.standard.voice.openai

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiApiFailureClassifierTest {
    @Test
    fun distinguishesTemporaryRateLimitFromEveryDocumentedQuotaCode() {
        assertEquals(
            OpenAiApiFailure(OpenAiApiFailureClassifier.RATE_LIMITED, true),
            OpenAiApiFailureClassifier.classifyHttp(429, error("rate_limit_exceeded")),
        )

        listOf(
            "credit_balance_exhausted",
            "insufficient_quota",
        ).forEach { code ->
            assertEquals(
                OpenAiApiFailure(OpenAiApiFailureClassifier.QUOTA_EXHAUSTED, false),
                OpenAiApiFailureClassifier.classifyHttp(429, error(code)),
            )
        }
        assertEquals(
            OpenAiApiFailure(OpenAiApiFailureClassifier.QUOTA_EXHAUSTED, false),
            OpenAiApiFailureClassifier.classifyHttp(
                429,
                """{"error":{"code":null,"type":"insufficient_quota","message":"ignored"}}""",
            ),
        )
    }

    @Test
    fun specificSpendingCodeOverridesGenericInsufficientQuotaTypeForHttpAndLive() {
        mapOf(
            "organization_usage_limit_exceeded" to OpenAiApiFailureClassifier.SPENDING_LIMIT_REACHED,
            "organization_spend_limit_exceeded" to OpenAiApiFailureClassifier.SPENDING_LIMIT_REACHED,
            "billing_hard_limit_reached" to OpenAiApiFailureClassifier.SPENDING_LIMIT_REACHED,
            "project_spend_limit_exceeded" to OpenAiApiFailureClassifier.PROJECT_SPENDING_LIMIT_REACHED,
        ).forEach { (code, expected) ->
            val body = """{"error":{"code":"$code","type":"insufficient_quota","message":"sk-secret"}}"""
            assertEquals(OpenAiApiFailure(expected, false), OpenAiApiFailureClassifier.classifyHttp(429, body))
            assertEquals(OpenAiApiFailure(expected, false), OpenAiApiFailureClassifier.classifyServerError(code, "insufficient_quota"))
        }
    }

    @Test
    fun slowDownAndRateLimitTypeAreNotBillingFailuresAndMessagesAreNeverInterpreted() {
        listOf("slow_down", "rate_limit_exceeded").forEach { code ->
            assertEquals(OpenAiApiFailure("rate_limited", true), OpenAiApiFailureClassifier.classifyServerError(code, "rate_limit_error"))
        }
        assertEquals("rate_limited", OpenAiApiFailureClassifier.classifyServerError(null, "rate_limit_error").code)
        assertEquals("rate_limited", OpenAiApiFailureClassifier.classifyHttp(429,
            """{"error":{"message":"run out of credits sk-secret","code":null}}""").code)
        assertEquals("authentication_failed", OpenAiApiFailureClassifier.classifyHttp(401).code)
    }

    @Test
    fun classifiesAuthenticationPermissionTimeoutAndServerFailures() {
        assertEquals("authentication_failed", OpenAiApiFailureClassifier.classifyHttp(401).code)
        assertEquals("permission_denied", OpenAiApiFailureClassifier.classifyHttp(403).code)
        assertTrue(OpenAiApiFailureClassifier.classifyHttp(408).retryable)
        assertTrue(OpenAiApiFailureClassifier.classifyHttp(503).retryable)
        assertFalse(OpenAiApiFailureClassifier.classifyHttp(422).retryable)
    }

    @Test
    fun malformedSecretBearingBodyNeverEscapesAndOversizeBodyIsRejected() {
        val secret = "sk-secret-never-returned"
        val read = OpenAiApiFailureClassifier.readBoundedErrorBody(
            ByteArrayInputStream("not-json:$secret".toByteArray()),
        )
        assertEquals(
            OpenAiApiFailureClassifier.HTTP_CLIENT_ERROR,
            OpenAiApiFailureClassifier.classifyHttp(422, read).code,
        )

        val oversized = object : InputStream() {
            var remaining = OpenAiApiFailureClassifier.MAX_ERROR_BODY_BYTES + 1
            override fun read(): Int = if (remaining-- > 0) 'x'.code else -1
        }
        assertNull(OpenAiApiFailureClassifier.readBoundedErrorBody(oversized))
    }

    @Test
    fun structuredRealtimeErrorsUseTheSameQuotaSemantics() {
        assertEquals(
            OpenAiApiFailureClassifier.QUOTA_EXHAUSTED,
            OpenAiApiFailureClassifier.classifyServerError("credit_balance_exhausted").code,
        )
        assertEquals(
            OpenAiApiFailureClassifier.RATE_LIMITED,
            OpenAiApiFailureClassifier.classifyServerError("rate_limit_exceeded").code,
        )
    }

    @Test
    fun transportFailuresAreClassifiedWithoutLeakingExceptionMessages() {
        assertEquals(
            OpenAiApiFailureClassifier.NETWORK_UNAVAILABLE,
            OpenAiApiFailureClassifier.classifyTransport(
                UnknownHostException("secret-host-name"),
            ).code,
        )
        assertEquals(
            OpenAiApiFailureClassifier.NETWORK_UNAVAILABLE,
            OpenAiApiFailureClassifier.classifyTransport(
                IllegalStateException("wrapper", ConnectException("secret-address")),
            ).code,
        )
        assertEquals(
            OpenAiApiFailureClassifier.NETWORK_TIMEOUT,
            OpenAiApiFailureClassifier.classifyTransport(SocketTimeoutException("secret")).code,
        )
        assertEquals(
            OpenAiApiFailureClassifier.TLS_FAILED,
            OpenAiApiFailureClassifier.classifyTransport(SSLHandshakeException("secret")).code,
        )
        assertEquals(
            OpenAiApiFailureClassifier.TRANSPORT_FAILED,
            OpenAiApiFailureClassifier.classifyTransport(IllegalStateException("secret")).code,
        )
    }

    private fun error(code: String): String =
        """{"error":{"code":"$code","type":"api_error","message":"ignored"}}"""
}
