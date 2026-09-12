package ai.hans.standard.work

import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.util.Locale

data class WorkHttpRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
    val allowPrivateNetwork: Boolean,
) {
    init {
        require(method in ALLOWED_METHODS) { "Unsupported HTTP method" }
        require(url.toByteArray(Charsets.UTF_8).size in 1..MAX_URL_BYTES) { "Invalid HTTP URL length" }
        require(headers.size <= MAX_HEADERS) { "Too many HTTP headers" }
        headers.forEach { (name, value) ->
            require(HEADER_NAME.matches(name)) { "Unsafe HTTP header name" }
            require(name.lowercase(Locale.ROOT) !in FORBIDDEN_REQUEST_HEADERS) {
                "HTTP header is controlled by Hans"
            }
            require(value.toByteArray(Charsets.UTF_8).size <= MAX_HEADER_VALUE_BYTES) {
                "HTTP header value is too large"
            }
            require(value.none { it == '\r' || it == '\n' || it == '\u0000' }) {
                "Unsafe HTTP header value"
            }
        }
        require((body?.size ?: 0) <= MAX_REQUEST_BODY_BYTES) { "HTTP request body is too large" }
    }

    companion object {
        val ALLOWED_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
        const val MAX_URL_BYTES = 8 * 1024
        const val MAX_HEADERS = 32
        const val MAX_HEADER_VALUE_BYTES = 8 * 1024
        const val MAX_REQUEST_BODY_BYTES = 2 * 1024 * 1024
        private val HEADER_NAME = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}")
        private val FORBIDDEN_REQUEST_HEADERS = setOf(
            "connection",
            "content-length",
            "host",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
        )
    }
}

data class WorkHttpResponseMetadata(
    val statusCode: Int,
    val finalUrl: String,
    val headers: Map<String, List<String>>,
    val contentType: String?,
    val declaredContentLength: Long?,
) {
    init {
        require(statusCode in 100..599)
        require(finalUrl.toByteArray(Charsets.UTF_8).size in 1..WorkHttpRequest.MAX_URL_BYTES)
        require(headers.size <= 128)
        require(declaredContentLength == null || declaredContentLength >= 0L)
    }
}

interface WorkHttpResponse : Closeable {
    val metadata: WorkHttpResponseMetadata
    val body: InputStream
}

interface WorkHttpCall {
    fun execute(): WorkHttpResponse
    fun cancel()
}

fun interface WorkHttpCallFactory {
    fun create(request: WorkHttpRequest): WorkHttpCall
}

fun interface PrivateNetworkAuthorization {
    /** Called only after strict JSON decoding. The raw HTTP request has not started yet. */
    fun isAuthorized(threadId: String, turnId: String, callId: String, url: String): Boolean

    companion object {
        val DENY = PrivateNetworkAuthorization { _, _, _, _ -> false }
        val ALLOW = PrivateNetworkAuthorization { _, _, _, _ -> true }
    }
}

/**
 * Host and resolved-address policy for generic HTTP tools.
 *
 * Public access rejects loopback, link-local, RFC1918/ULA and Android-emulator host aliases.
 * Private access is a separate user-authorized capability, but cloud metadata endpoints remain
 * blocked in either mode because untrusted documents must never turn the tool into a credential
 * oracle.
 */
object WorkNetworkEndpointPolicy {
    private val ALWAYS_BLOCKED_HOSTS = setOf(
        "metadata.google.internal",
        "metadata",
    )
    private val ALWAYS_BLOCKED_ADDRESSES = setOf(
        "169.254.169.254",
        "100.100.100.200",
    )

    fun validateUri(raw: String): URI {
        val uri = URI(raw)
        require(uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https")) {
            "Only HTTP(S) URLs are supported"
        }
        require(uri.rawUserInfo == null) { "Credentials are not allowed in URLs" }
        require(uri.fragment == null) { "URL fragments are not sent to servers" }
        val host = uri.host?.trimEnd('.')?.lowercase(Locale.ROOT)
        require(!host.isNullOrBlank()) { "HTTP URL has no host" }
        require(host !in ALWAYS_BLOCKED_HOSTS) { "Metadata endpoints are blocked" }
        require(uri.port in -1..65535 && uri.port != 0) { "Invalid HTTP port" }
        return uri
    }

    fun requireAddressAllowed(address: InetAddress, allowPrivateNetwork: Boolean) {
        val literal = address.hostAddress?.substringBefore('%') ?: ""
        require(literal !in ALWAYS_BLOCKED_ADDRESSES) { "Metadata endpoints are blocked" }
        require(!address.isAnyLocalAddress) { "Unspecified network addresses are blocked" }
        if (!allowPrivateNetwork) {
            require(
                !address.isLoopbackAddress &&
                    !address.isLinkLocalAddress &&
                    !address.isSiteLocalAddress &&
                    !address.isMulticastAddress &&
                    !address.isUniqueLocalIpv6(),
            ) { "Private network access requires explicit authorization" }
        }
    }

    private fun InetAddress.isUniqueLocalIpv6(): Boolean {
        val bytes = address
        return bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
    }
}

