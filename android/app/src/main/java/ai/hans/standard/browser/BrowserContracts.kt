package ai.hans.standard.browser

import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.work.OkHttpWorkHttpCallFactory
import ai.hans.standard.work.WorkHttpCall
import ai.hans.standard.work.WorkHttpCallFactory
import ai.hans.standard.work.WorkHttpRequest
import java.net.URI

/**
 * Marker contract for an HTTP transport which never reads or writes a browser cookie jar,
 * authenticated profile, WebView storage, or Android account state.
 *
 * The production implementation creates its own default OkHttp work transport. Tests and other
 * embedders may provide a replacement, but the browser executor still supplies no Cookie or
 * Authorization headers and applies the shared [ai.hans.standard.work.WorkNetworkEndpointPolicy].
 */
fun interface StatelessBrowserHttpCallFactory {
    fun create(request: WorkHttpRequest): WorkHttpCall
}

class IsolatedWorkBrowserHttpCallFactory private constructor(
    private val delegate: WorkHttpCallFactory,
) : StatelessBrowserHttpCallFactory {
    constructor() : this(OkHttpWorkHttpCallFactory())

    override fun create(request: WorkHttpRequest): WorkHttpCall = delegate.create(request)

    internal companion object {
        fun forTest(delegate: WorkHttpCallFactory) = IsolatedWorkBrowserHttpCallFactory(delegate)
    }
}

/** Exact readiness proof required before a visible browser fallback can be advertised. */
data class VisibleBrowserReadiness(
    val available: Boolean,
    val isolatedEphemeralProfile: Boolean,
    val publicHttpPolicyEnforced: Boolean,
    val freshUrlPostconditions: Boolean,
) {
    val usable: Boolean
        get() = available &&
            isolatedEphemeralProfile &&
            publicHttpPolicyEnforced &&
            freshUrlPostconditions

    companion object {
        val UNAVAILABLE = VisibleBrowserReadiness(
            available = false,
            isolatedEphemeralProfile = false,
            publicHttpPolicyEnforced = false,
            freshUrlPostconditions = false,
        )

        val AVAILABLE_ISOLATED = VisibleBrowserReadiness(
            available = true,
            isolatedEphemeralProfile = true,
            publicHttpPolicyEnforced = true,
            freshUrlPostconditions = true,
        )
    }
}

data class VisibleBrowserObservation(
    val sequence: Long,
    val observedUrl: String,
) {
    init {
        require(sequence >= 0L) { "Invalid browser observation sequence" }
        require(observedUrl.toByteArray(Charsets.UTF_8).size in 1..WorkHttpRequest.MAX_URL_BYTES) {
            "Invalid browser observation URL"
        }
    }
}

data class VisibleBrowserNavigationRequest(
    val operationNonce: String,
    val requestedUrl: String,
) {
    init {
        require(OPERATION_NONCE.matches(operationNonce)) { "Invalid browser operation nonce" }
        require(requestedUrl.toByteArray(Charsets.UTF_8).size in 1..WorkHttpRequest.MAX_URL_BYTES) {
            "Invalid browser request URL"
        }
    }

    private companion object {
        val OPERATION_NONCE = Regex("br_[0-9a-f]{64}")
    }
}

enum class VisibleBrowserPostconditionStatus {
    VERIFIED,
    FAILED,
    AMBIGUOUS,
}

/**
 * Receipt for one visible UI mutation. A successful executor result requires an exact nonce,
 * a later observation, a URL-bound final state, and [VisibleBrowserPostconditionStatus.VERIFIED].
 */
data class VisibleBrowserNavigationReceipt(
    val operationNonce: String,
    val requestedUrl: String,
    val actionStarted: Boolean,
    val finalUrl: String?,
    val before: VisibleBrowserObservation?,
    val after: VisibleBrowserObservation?,
    val postcondition: VisibleBrowserPostconditionStatus,
) {
    fun proves(request: VisibleBrowserNavigationRequest): Boolean {
        if (!actionStarted || postcondition != VisibleBrowserPostconditionStatus.VERIFIED) return false
        if (operationNonce != request.operationNonce || requestedUrl != request.requestedUrl) return false
        val baseline = before ?: return false
        val observed = after ?: return false
        val final = finalUrl ?: return false
        if (observed.sequence <= baseline.sequence || observed.observedUrl != final) return false
        return runCatching {
            val finalUri = BrowserUrlPolicy.requirePublicHttpUrl(final)
            finalUri.toASCIIString() == final
        }.getOrDefault(false)
    }
}

/**
 * Adapter point for a future visible Android browser/Accessibility implementation.
 *
 * Implementations must use a Hans-owned ephemeral profile, must not import/export cookies or
 * profile state, must enforce the same public HTTP endpoint policy as the direct transport, and
 * must observe a fresh URL after the UI action. Merely opening an Intent is not sufficient.
 */
interface VisibleBrowserFallback {
    fun readiness(): VisibleBrowserReadiness

    fun navigate(
        request: VisibleBrowserNavigationRequest,
        cancellation: DynamicToolCancellation,
    ): VisibleBrowserNavigationReceipt

    companion object {
        val UNAVAILABLE = object : VisibleBrowserFallback {
            override fun readiness(): VisibleBrowserReadiness = VisibleBrowserReadiness.UNAVAILABLE

            override fun navigate(
                request: VisibleBrowserNavigationRequest,
                cancellation: DynamicToolCancellation,
            ): VisibleBrowserNavigationReceipt = error("Visible browser fallback is unavailable")
        }
    }
}

internal object BrowserUrlPolicy {
    fun requirePublicHttpUrl(raw: String): URI {
        val uri = ai.hans.standard.work.WorkNetworkEndpointPolicy.validateUri(raw)
        // Canonical ASCII serialization is used in receipts so equivalent Unicode/escaped forms
        // cannot create an ambiguous postcondition comparison.
        val ascii = uri.toASCIIString()
        require(ascii.toByteArray(Charsets.UTF_8).size <= WorkHttpRequest.MAX_URL_BYTES) {
            "Browser URL is too long"
        }
        return URI(ascii)
    }
}
