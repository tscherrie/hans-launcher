package ai.hans.standard.work.remote

import ai.hans.standard.work.WorkNetworkEndpointPolicy
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.CertificatePinner
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal class RemoteWorkFailure(
    val code: String,
) : IllegalStateException(code) {
    init {
        require(SAFE_FAILURE_CODE.matches(code))
    }
}

internal data class RemoteWorkHttpRequest(
    val method: String,
    val path: String,
    val body: ByteArray = ByteArray(0),
    val contentType: String = "application/json",
    val timeoutMillis: Long = DEFAULT_REMOTE_WORK_TIMEOUT_MILLIS,
    val maxResponseBytes: Long = DEFAULT_REMOTE_WORK_RESPONSE_BYTES,
) {
    init {
        require(method in setOf("GET", "POST", "PUT"))
        require(path.matches(SAFE_REMOTE_PATH) && ".." !in path)
        require(body.size <= MAX_REMOTE_REQUEST_BYTES)
        require(contentType in setOf("application/json", "application/octet-stream"))
        require(timeoutMillis in 1_000L..MAX_REMOTE_TIMEOUT_MILLIS)
        require(maxResponseBytes in 0L..RemoteWorkLimits.MAX_OUTPUT_BYTES)
        if (method == "GET") require(body.isEmpty())
    }

    companion object {
        const val MAX_REMOTE_REQUEST_BYTES = 4 * 1024 * 1024
        const val MAX_REMOTE_TIMEOUT_MILLIS = 120_000L
        const val DEFAULT_REMOTE_WORK_TIMEOUT_MILLIS = 60_000L
        const val DEFAULT_REMOTE_WORK_RESPONSE_BYTES = 1024L * 1024L
    }
}

internal interface RemoteWorkHttpResponse : Closeable {
    val statusCode: Int
    val contentType: String
    val declaredContentLength: Long?
    val body: InputStream
}

internal interface RemoteWorkHttpCall : Closeable {
    fun execute(): RemoteWorkHttpResponse
    fun cancel()
}

internal fun interface RemoteWorkHttpCallFactory {
    fun create(
        identity: RemoteWorkerConnectionIdentity,
        request: RemoteWorkHttpRequest,
    ): RemoteWorkHttpCall
}

/**
 * HTTPS transport with an exact leaf-SPKI pin and per-request Android-Keystore signature.
 * Redirects and private/metadata-network resolution are rejected. Neither the endpoint nor key
 * alias is copied into a response, exception message, or persisted protocol envelope.
 */
internal class OkHttpPinnedRemoteWorkCallFactory(
    private val authenticator: RemoteWorkRequestAuthenticator,
    baseClient: OkHttpClient = OkHttpClient(),
) : RemoteWorkHttpCallFactory {
    private val template = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(PublicRemoteWorkDns)
        .build()

    override fun create(
        identity: RemoteWorkerConnectionIdentity,
        request: RemoteWorkHttpRequest,
    ): RemoteWorkHttpCall {
        val origin = URI(identity.endpoint)
        val host = requireNotNull(origin.host).lowercase(Locale.ROOT)
        val pin = Base64.getEncoder().encodeToString(identity.serverSpkiSha256.hexBytes())
        val proof = authenticator.authenticate(identity, request.method, request.path, request.body)
        val publicSpki = authenticator.publicKeySpki(identity).also {
            require(sha256(it) == proof.publicKeySpkiSha256) {
                "Remote worker signing identity changed"
            }
        }
        val client = template.newBuilder()
            .certificatePinner(
                CertificatePinner.Builder()
                    .add(host, "sha256/$pin")
                    .build(),
            )
            .callTimeout(request.timeoutMillis, TimeUnit.MILLISECONDS)
            .connectTimeout(minOf(20_000L, request.timeoutMillis), TimeUnit.MILLISECONDS)
            .readTimeout(request.timeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(request.timeoutMillis, TimeUnit.MILLISECONDS)
            .build()
        val url = identity.endpoint.removeSuffix("/") + request.path
        val requestBody = when (request.method) {
            "GET" -> null
            else -> request.body.toRequestBody(request.contentType.toMediaType())
        }
        val httpRequest = Request.Builder()
            .url(url)
            .header("Accept", "application/json, application/octet-stream")
            .header("X-Hans-Remote-Protocol", REMOTE_WORK_PROTOCOL_VERSION.toString())
            .header("X-Hans-Configuration", proof.configurationDigest)
            .header("X-Hans-Client-Key", proof.publicKeySpkiSha256)
            .header("X-Hans-Client-SPKI", Base64.getUrlEncoder().withoutPadding().encodeToString(publicSpki))
            .header("X-Hans-Timestamp-Millis", proof.timestampEpochMillis.toString())
            .header("X-Hans-Nonce", proof.nonce)
            .header("X-Hans-Body-SHA256", proof.bodySha256)
            .header("X-Hans-Signature", proof.signatureBase64Url)
            .method(request.method, requestBody)
            .build()
        return OkHttpCall(client.newCall(httpRequest), request.maxResponseBytes)
    }

    private class OkHttpCall(
        private val call: okhttp3.Call,
        private val maxResponseBytes: Long,
    ) : RemoteWorkHttpCall {
        @Volatile private var closed = false

        override fun execute(): RemoteWorkHttpResponse {
            check(!closed) { "remote_work_call_closed" }
            val response = try {
                call.execute()
            } catch (_: Exception) {
                throw RemoteWorkFailure("remote_work_transport_failed")
            }
            if (response.isRedirect) {
                response.close()
                throw RemoteWorkFailure("remote_work_redirect_rejected")
            }
            val length = response.body?.contentLength()?.takeIf { it >= 0L }
            if (length != null && length > maxResponseBytes) {
                response.close()
                throw RemoteWorkFailure("remote_work_response_too_large")
            }
            return ResponseLease(response, maxResponseBytes)
        }

        override fun cancel() = call.cancel()

        override fun close() {
            closed = true
            if (!call.isExecuted()) call.cancel()
        }
    }

    private class ResponseLease(
        private val response: Response,
        private val maximumBytes: Long,
    ) : RemoteWorkHttpResponse {
        override val statusCode: Int = response.code
        override val contentType: String = response.body?.contentType()?.toString().orEmpty().take(255)
        override val declaredContentLength: Long? = response.body?.contentLength()?.takeIf { it >= 0L }
        override val body: InputStream = response.body?.byteStream()?.let {
            BoundedRemoteInputStream(it, maximumBytes)
        } ?: ByteArrayInputStream(ByteArray(0))

        override fun close() = response.close()
    }

    private object PublicRemoteWorkDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val normalized = hostname.trimEnd('.').lowercase(Locale.ROOT)
            requireHttpsRemoteOrigin("https://$normalized/")
            return Dns.SYSTEM.lookup(hostname).also { addresses ->
                require(addresses.isNotEmpty()) { "remote_work_host_unresolved" }
                addresses.forEach { WorkNetworkEndpointPolicy.requireAddressAllowed(it, false) }
            }
        }
    }
}

private class BoundedRemoteInputStream(
    private val delegate: InputStream,
    private val maximumBytes: Long,
) : InputStream() {
    private var consumed = 0L

    override fun read(): Int {
        val value = delegate.read()
        if (value >= 0) account(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = delegate.read(buffer, offset, length)
        if (count > 0) account(count)
        return count
    }

    override fun close() = delegate.close()

    private fun account(count: Int) {
        consumed = Math.addExact(consumed, count.toLong())
        if (consumed > maximumBytes) throw RemoteWorkFailure("remote_work_response_too_large")
    }
}

private fun requireHttpsRemoteOrigin(raw: String): URI {
    val uri = WorkNetworkEndpointPolicy.validateUri(raw)
    require(uri.scheme.equals("https", ignoreCase = true)) { "remote_work_https_required" }
    return uri
}

private fun String.hexBytes(): ByteArray {
    require(SHA_256.matches(this))
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

private val SAFE_REMOTE_PATH = Regex("/[A-Za-z0-9._~!$&'()*+,;=:@%/-]{1,2047}")
private val SAFE_FAILURE_CODE = Regex("[a-z][a-z0-9_]{1,63}")
