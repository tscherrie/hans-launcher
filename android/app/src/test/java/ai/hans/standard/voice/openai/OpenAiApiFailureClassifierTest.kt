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
            "organization_usage_limit_exceeded",
            "organization_spend_limit_exceeded",
            "project_spend_limit_exceeded",
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
