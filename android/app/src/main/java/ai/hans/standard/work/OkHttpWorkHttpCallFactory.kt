package ai.hans.standard.work

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class OkHttpWorkHttpCallFactory(
    baseClient: OkHttpClient = OkHttpClient(),
) : WorkHttpCallFactory {
    private val template = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(90, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun create(request: WorkHttpRequest): WorkHttpCall {
        WorkNetworkEndpointPolicy.validateUri(request.url)
        return RedirectingCall(template, request)
    }

    private class RedirectingCall(
        private val template: OkHttpClient,
        private val initial: WorkHttpRequest,
    ) : WorkHttpCall {
        @Volatile private var active: okhttp3.Call? = null
        @Volatile private var cancelled = false

        override fun execute(): WorkHttpResponse {
            var url = initial.url
            var method = initial.method
            var body = initial.body
            repeat(MAX_REDIRECTS + 1) { redirectIndex ->
                check(!cancelled) { "HTTP request cancelled" }
                val uri = WorkNetworkEndpointPolicy.validateUri(url)
                val client = template.newBuilder()
                    .dns(PolicyDns(initial.allowPrivateNetwork))
                    .build()
                val requestBuilder = Request.Builder().url(uri.toASCIIString())
                initial.headers.forEach { (name, value) -> requestBuilder.header(name, value) }
                val mediaType = initial.headers.entries
                    .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
                    ?.value
                    ?.toMediaTypeOrNull()
                val requestBody = when {
                    method in setOf("POST", "PUT", "PATCH") -> (body ?: ByteArray(0)).toRequestBody(mediaType)
                    method == "DELETE" && body != null -> body.toRequestBody(mediaType)
                    else -> null
                }
                requestBuilder.method(method, requestBody)
                val call = client.newCall(requestBuilder.build())
                active = call
                if (cancelled) call.cancel()
                val response = call.execute()
                active = null
                if (!response.isRedirect) return OkHttpResponseLease(response)
                if (redirectIndex == MAX_REDIRECTS) {
                    response.close()
                    error("HTTP redirect limit exceeded")
                }
                val statusCode = response.code
                val location = response.header("Location")
                val next = location?.let { response.request.url.resolve(it) }
                response.close()
                require(next != null) { "HTTP redirect has no valid Location" }
                url = next.toString()
                if (statusCode in setOf(301, 302, 303) && method !in setOf("GET", "HEAD")) {
                    method = "GET"
                    body = null
                }
            }
            error("HTTP redirect state is invalid")
        }

        override fun cancel() {
            cancelled = true
            active?.cancel()
        }
    }

    private class PolicyDns(
        private val allowPrivateNetwork: Boolean,
    ) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val normalized = hostname.trimEnd('.').lowercase(Locale.ROOT)
            WorkNetworkEndpointPolicy.validateUri("https://$normalized/")
            return Dns.SYSTEM.lookup(hostname).also { addresses ->
                require(addresses.isNotEmpty()) { "HTTP host did not resolve" }
                addresses.forEach { address ->
                    WorkNetworkEndpointPolicy.requireAddressAllowed(address, allowPrivateNetwork)
                }
            }
        }
    }

    private class OkHttpResponseLease(
        private val response: Response,
    ) : WorkHttpResponse {
        override val metadata = WorkHttpResponseMetadata(
            statusCode = response.code,
            finalUrl = response.request.url.toString(),
            headers = response.headers.toMultimap().entries
                .take(128)
                .associate { (name, values) ->
                    name.lowercase(Locale.ROOT).take(128) to values.take(16).map { it.take(8 * 1024) }
                },
            contentType = response.body?.contentType()?.toString()?.take(255),
            declaredContentLength = response.body?.contentLength()?.takeIf { it >= 0L },
        )
        override val body: InputStream = response.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))

        override fun close() {
            response.close()
        }
    }

    private companion object {
        const val MAX_REDIRECTS = 5
    }
}
