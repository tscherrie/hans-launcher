package ai.hans.standard.voice.openai

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.json.JSONException
import org.json.JSONObject

/** Stable, content-free classification of a failed OpenAI API request. */
data class OpenAiApiFailure(
    val code: String,
    val retryable: Boolean,
)

/**
 * Reads only bounded error metadata and discards every provider message.
 *
 * OpenAI uses HTTP 429 for both temporary rate limits and account/project
 * spending limits. The safe `error.code` / `error.type` fields are therefore
 * required to tell the user whether retrying can help. Raw response text is
 * never returned from this boundary or written to logs.
 */
object OpenAiApiFailureClassifier {
    const val AUTHENTICATION_FAILED = "authentication_failed"
    const val PERMISSION_DENIED = "permission_denied"
    const val QUOTA_EXHAUSTED = "quota_exhausted"
    const val SPENDING_LIMIT_REACHED = "spending_limit_reached"
    const val PROJECT_SPENDING_LIMIT_REACHED = "project_spending_limit_reached"
    const val RATE_LIMITED = "rate_limited"
    const val HTTP_TIMEOUT = "http_timeout"
    const val HTTP_SERVER_ERROR = "http_server_error"
    const val HTTP_CLIENT_ERROR = "http_client_error"
    const val UNEXPECTED_REDIRECT = "unexpected_redirect"
    const val NETWORK_UNAVAILABLE = "network_unavailable"
    const val NETWORK_TIMEOUT = "network_timeout"
    const val TLS_FAILED = "tls_failed"
    const val TRANSPORT_FAILED = "transport_failed"

    const val MAX_ERROR_BODY_BYTES = 16 * 1_024

    private val quotaCodes = setOf(
        "insufficient_quota",
        "credit_balance_exhausted",
    )

    private val spendingLimitCodes = setOf(
        "organization_usage_limit_exceeded",
        "organization_spend_limit_exceeded",
        // Older responses used these names; accepting them is harmless and
        // keeps the UI accurate for cached/gateway-projected API errors.
        "billing_hard_limit_reached",
        "usage_limit_reached",
    )

    private val authenticationCodes = setOf(
        "invalid_api_key",
        "invalid_authentication",
        "authentication_error",
    )

    fun classifyHttp(status: Int, boundedErrorBody: String? = null): OpenAiApiFailure {
        val metadata = parseMetadata(boundedErrorBody)
        classifyMetadata(metadata.code, metadata.type)?.let { return it }
        return when (status) {
            401 -> OpenAiApiFailure(AUTHENTICATION_FAILED, retryable = false)
            403 -> OpenAiApiFailure(PERMISSION_DENIED, retryable = false)
            408, 425 -> OpenAiApiFailure(HTTP_TIMEOUT, retryable = true)
            429 -> OpenAiApiFailure(RATE_LIMITED, retryable = true)
            in 500..599 -> OpenAiApiFailure(HTTP_SERVER_ERROR, retryable = true)
            in 300..399 -> OpenAiApiFailure(UNEXPECTED_REDIRECT, retryable = false)
            else -> OpenAiApiFailure(HTTP_CLIENT_ERROR, retryable = false)
        }
    }

    /** Classifies a structured Realtime error event that has no HTTP status. */
    fun classifyServerError(code: String?, type: String? = null): OpenAiApiFailure {
        val normalizedCode = normalize(code)
        val normalizedType = normalize(type)
        return classifyMetadata(normalizedCode, normalizedType)
            ?: OpenAiApiFailure(HTTP_CLIENT_ERROR, retryable = false)
    }

    private fun classifyMetadata(code: String, type: String): OpenAiApiFailure? =
        // A specific code (for example project_spend_limit_exceeded) overrides the generic
        // insufficient_quota type. This determines the correct, fixed remediation page.
        classifyKnownCode(code) ?: classifyKnownCode(type)

    private fun classifyKnownCode(code: String): OpenAiApiFailure? = when {
        code == "project_spend_limit_exceeded" -> OpenAiApiFailure(PROJECT_SPENDING_LIMIT_REACHED, false)
        code in spendingLimitCodes -> OpenAiApiFailure(SPENDING_LIMIT_REACHED, false)
        code in quotaCodes -> OpenAiApiFailure(QUOTA_EXHAUSTED, false)
        code in authenticationCodes -> OpenAiApiFailure(AUTHENTICATION_FAILED, false)
        code == "permission_denied" -> OpenAiApiFailure(PERMISSION_DENIED, false)
        code in setOf("rate_limit_exceeded", "rate_limit_error", "slow_down") ->
            OpenAiApiFailure(RATE_LIMITED, true)
        code in setOf("server_error", "service_unavailable") -> OpenAiApiFailure(HTTP_SERVER_ERROR, true)
        else -> null
    }

    /** Classifies only exception types; provider text and exception messages never escape. */
    fun classifyTransport(failure: Throwable): OpenAiApiFailure {
        var current: Throwable? = failure
        repeat(MAX_CAUSE_DEPTH) {
            when (current) {
                is UnknownHostException,
                is ConnectException -> return OpenAiApiFailure(NETWORK_UNAVAILABLE, retryable = true)
                is SocketTimeoutException ->
                    return OpenAiApiFailure(NETWORK_TIMEOUT, retryable = true)
                is SSLException -> return OpenAiApiFailure(TLS_FAILED, retryable = true)
            }
            val next = current?.cause
            if (next == null || next === current) return@repeat
            current = next
        }
        return OpenAiApiFailure(TRANSPORT_FAILED, retryable = true)
    }

    /** Returns null when the body is missing, unreadable or larger than the fixed bound. */
    fun readBoundedErrorBody(input: InputStream?): String? {
        if (input == null) return null
        return try {
            val output = ByteArrayOutputStream(minOf(MAX_ERROR_BODY_BYTES, 4_096))
            val buffer = ByteArray(2_048)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_ERROR_BODY_BYTES) return null
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun parseMetadata(raw: String?): ErrorMetadata {
        if (raw.isNullOrBlank() || raw.length > MAX_ERROR_BODY_BYTES) return ErrorMetadata()
        return try {
            val error = JSONObject(raw).optJSONObject("error") ?: return ErrorMetadata()
            ErrorMetadata(
                code = normalize(error.optString("code")),
                type = normalize(error.optString("type")),
            )
        } catch (_: JSONException) {
            ErrorMetadata()
        } catch (_: RuntimeException) {
            ErrorMetadata()
        }
    }

    private fun normalize(value: String?): String = value
        ?.lowercase()
        ?.replace(Regex("[^a-z0-9_]+"), "_")
        ?.trim('_')
        ?.take(64)
        .orEmpty()

    private data class ErrorMetadata(
        val code: String = "",
        val type: String = "",
    )

    private const val MAX_CAUSE_DEPTH = 8
}
