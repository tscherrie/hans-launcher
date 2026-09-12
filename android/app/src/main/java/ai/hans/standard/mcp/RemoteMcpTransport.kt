package ai.hans.standard.mcp

import ai.hans.standard.work.WorkNetworkEndpointPolicy
import java.io.Closeable
import java.net.InetAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal enum class OAuthCredentialState {
    AVAILABLE,
    MISSING,
    EXPIRED,
    STALE_IDENTITY,
    UNAVAILABLE,
}

/** Raw credentials never leave the callback and must not be returned by an implementation. */
internal interface RemoteMcpOAuthVault {
    /** A credential is usable only for the exact finalized plugin/server/configuration identity. */
    fun state(identity: RemoteMcpOAuthCredentialIdentity): OAuthCredentialState

    fun <T> withBearerToken(
        identity: RemoteMcpOAuthCredentialIdentity,
        block: (String) -> T,
    ): T
}

internal data class RemoteMcpHttpRequest(
    val endpoint: String,
    val body: ByteArray,
    val timeoutMillis: Long,
    val maxResponseBytes: Int,
    val sessionId: String?,
    val protocolVersion: String?,
    val method: String? = null,
    val name: String? = null,
    val parameterHeaders: Map<String, String> = emptyMap(),
) {
    init {
        requireHttpsEndpoint(endpoint)
        require(body.size in 1..(2 * 1024 * 1024))
        require(timeoutMillis in 1_000L..120_000L)
        require(maxResponseBytes in 1_024..(4 * 1024 * 1024))
        sessionId?.let { require(it.matches(SAFE_SESSION_ID)) }
        protocolVersion?.let { require(it.matches(SAFE_PROTOCOL_VERSION)) }
        method?.let { require(it.matches(SAFE_METHOD)) }
        name?.let { requireBoundedMcpHeaderSource(it) }
        require(parameterHeaders.size <= 64)
        require(
            parameterHeaders.keys.map { it.lowercase(Locale.ROOT) }.distinct().size ==
                parameterHeaders.size,
        ) {
            "mcp_parameter_header_duplicate"
        }
        parameterHeaders.forEach { (headerName, value) ->
            require(headerName.matches(SAFE_PARAMETER_HEADER_NAME))
            requireBoundedMcpHeaderSource(value, allowEmpty = true)
        }
        if (protocolVersion == RemoteMcpProtocol.MODERN_PROTOCOL_VERSION) {
            require(sessionId == null) { "mcp_modern_session_forbidden" }
            require(method != null) { "mcp_modern_method_header_required" }
            require((method == "tools/call") == (name != null)) {
                "mcp_modern_name_header_mismatch"
            }
            validateModernMcpBody(body, method, name)
        } else {
            require(method == null && name == null && parameterHeaders.isEmpty()) {
                "mcp_legacy_routing_headers_forbidden"
            }
        }
    }

    override fun toString(): String =
        "RemoteMcpHttpRequest(endpoint=redacted, body=redacted, timeoutMillis=$timeoutMillis, " +
            "maxResponseBytes=$maxResponseBytes, sessionId=redacted, protocolVersion=$protocolVersion, " +
            "method=redacted, name=redacted, parameterHeaders=redacted)"
}

internal data class RemoteMcpHttpResponse(
    val statusCode: Int,
    val contentType: String,
    val body: ByteArray,
    val sessionId: String?,
) {
    init {
        require(statusCode in 100..599)
        require(contentType.length <= 255 && contentType.none(Char::isISOControl))
        sessionId?.let { require(it.matches(SAFE_SESSION_ID)) }
    }

    override fun toString(): String =
        "RemoteMcpHttpResponse(statusCode=$statusCode, contentType=redacted, body=redacted, " +
            "sessionId=redacted)"
}

internal interface RemoteMcpHttpCall : Closeable {
    fun execute(): RemoteMcpHttpResponse
    fun cancel()
}

internal fun interface RemoteMcpHttpCallFactory {
    /** Authorization is passed separately so it can never enter request toString()/data logs. */
    fun create(request: RemoteMcpHttpRequest, bearerToken: String?): RemoteMcpHttpCall
}

/**
 * HTTPS-only, public-network-only MCP transport. Redirects are deliberately rejected, which
 * prevents bearer credentials from being replayed to another origin. DNS is validated on every
 * lookup, including rebinding attempts, using the same endpoint policy as Hans work HTTP.
 */
internal class OkHttpRemoteMcpCallFactory(
    baseClient: OkHttpClient = OkHttpClient(),
) : RemoteMcpHttpCallFactory {
    private val template = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(PublicMcpDns)
        .build()

    override fun create(
        request: RemoteMcpHttpRequest,
        bearerToken: String?,
    ): RemoteMcpHttpCall {
        requireHttpsEndpoint(request.endpoint)
        val client = template.newBuilder()
            .callTimeout(request.timeoutMillis, TimeUnit.MILLISECONDS)
            .connectTimeout(minOf(request.timeoutMillis, 20_000L), TimeUnit.MILLISECONDS)
            .readTimeout(request.timeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(request.timeoutMillis, TimeUnit.MILLISECONDS)
            .build()
        val httpRequest = Request.Builder()
            .url(URI(request.endpoint).toASCIIString())
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .apply {
                request.sessionId?.let { header("Mcp-Session-Id", it) }
                request.protocolVersion?.let { header("MCP-Protocol-Version", it) }
                request.method?.let { header("Mcp-Method", it) }
                request.name?.let { header("Mcp-Name", encodeMcpHeaderValue(it)) }
                request.parameterHeaders.forEach { (name, value) ->
                    header("Mcp-Param-$name", encodeMcpHeaderValue(value))
                }
                bearerToken?.let {
                    require(it.isNotBlank() && it.length <= 16 * 1024 && it.none(Char::isISOControl))
                    header("Authorization", "Bearer $it")
                }
            }
            .post(request.body.toRequestBody("application/json".toMediaType()))
            .build()
        return OkHttpRemoteMcpCall(client.newCall(httpRequest), request.maxResponseBytes)
    }

    private class OkHttpRemoteMcpCall(
        private val call: okhttp3.Call,
        private val maxResponseBytes: Int,
    ) : RemoteMcpHttpCall {
        @Volatile private var closed = false

        override fun execute(): RemoteMcpHttpResponse {
            check(!closed) { "mcp_call_closed" }
            val response = call.execute()
            response.use {
                require(!it.isRedirect) { "mcp_redirect_rejected" }
                val declaredLength = it.body?.contentLength()?.takeIf { length -> length >= 0L }
                require(declaredLength == null || declaredLength <= maxResponseBytes) {
                    "mcp_response_too_large"
                }
                val contentType = it.body?.contentType()?.toString().orEmpty()
                val bodyMayBeEmpty = it.code in setOf(202, 204, 400) &&
                    (it.body == null || declaredLength == 0L)
                require(
                    bodyMayBeEmpty ||
                        contentType.startsWith("application/json", ignoreCase = true) ||
                        contentType.startsWith("text/event-stream", ignoreCase = true),
                ) { "mcp_content_type_unsupported" }
                val body = it.body?.byteStream()?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        require(output.size() + read <= maxResponseBytes) { "mcp_response_too_large" }
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                } ?: ByteArray(0)
                return RemoteMcpHttpResponse(
                    statusCode = it.code,
                    contentType = contentType,
                    body = body,
                    sessionId = it.header("Mcp-Session-Id"),
                )
            }
        }

        override fun cancel() = call.cancel()

        override fun close() {
            closed = true
            if (!call.isExecuted()) call.cancel()
        }
    }

    private object PublicMcpDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val normalized = hostname.trimEnd('.').lowercase(Locale.ROOT)
            requireHttpsEndpoint("https://$normalized/")
            return Dns.SYSTEM.lookup(hostname).also { addresses ->
                require(addresses.isNotEmpty()) { "mcp_host_unresolved" }
                addresses.forEach { WorkNetworkEndpointPolicy.requireAddressAllowed(it, false) }
            }
        }
    }
}

internal fun requireHttpsEndpoint(raw: String): URI {
    val uri = WorkNetworkEndpointPolicy.validateUri(raw)
    require(uri.scheme.equals("https", ignoreCase = true)) { "mcp_https_required" }
    return uri
}

private val SAFE_SESSION_ID = Regex("[A-Za-z0-9._~+/=-]{1,512}")
private val SAFE_PROTOCOL_VERSION = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
private val SAFE_METHOD = Regex("[a-z][a-z0-9._/-]{0,127}")
private val SAFE_PARAMETER_HEADER_NAME = Regex("[!#\$%&'*+.^_`|~0-9A-Za-z-]+")
private val BASE64_SENTINEL = Regex("^=\\?base64\\?.*\\?=$")

private fun requireBoundedMcpHeaderSource(value: String, allowEmpty: Boolean = false) {
    val size = value.toByteArray(StandardCharsets.UTF_8).size
    require(size <= 8_192 && (allowEmpty || size > 0)) {
        "mcp_header_value_size_invalid"
    }
    require('\u0000' !in value) { "mcp_header_value_invalid" }
}

/** Exact 2026-07-28 Base64-sentinel encoding for non-plain-ASCII routing values. */
private fun encodeMcpHeaderValue(value: String): String {
    requireBoundedMcpHeaderSource(value, allowEmpty = true)
    val plainAscii = value.isNotEmpty() &&
        value.first() != ' ' && value.first() != '\t' &&
        value.last() != ' ' && value.last() != '\t' &&
        value.all { character -> character == '\t' || character.code in 0x20..0x7e } &&
        !BASE64_SENTINEL.matches(value)
    if (plainAscii) return value
    val encoded = Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    return "=?base64?$encoded?="
}

private fun validateModernMcpBody(body: ByteArray, method: String, name: String?) {
    val root = JSONObject(body.toString(StandardCharsets.UTF_8))
    require(root.optString("jsonrpc") == "2.0" && root.optString("method") == method) {
        "mcp_modern_method_body_mismatch"
    }
    val params = root.getJSONObject("params")
    if (name != null) {
        require(params.optString("name") == name) { "mcp_modern_name_body_mismatch" }
    }
    val metadata = params.getJSONObject("_meta")
    require(
        metadata.optString("io.modelcontextprotocol/protocolVersion") ==
            RemoteMcpProtocol.MODERN_PROTOCOL_VERSION
    ) { "mcp_modern_protocol_body_mismatch" }
    require(metadata.opt("io.modelcontextprotocol/clientInfo") is JSONObject) {
        "mcp_modern_client_info_missing"
    }
    require(metadata.opt("io.modelcontextprotocol/clientCapabilities") is JSONObject) {
        "mcp_modern_client_capabilities_missing"
    }
}
