package ai.hans.standard.mcp.oauth

import ai.hans.standard.work.WorkNetworkEndpointPolicy
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal data class RemoteMcpOAuthHttpResponse(
    val statusCode: Int,
    val contentType: String,
    val body: ByteArray,
)

internal interface RemoteMcpOAuthHttpClient {
    fun get(uri: URI, timeoutMillis: Long, maxResponseBytes: Int): RemoteMcpOAuthHttpResponse
    fun postForm(
        uri: URI,
        fields: List<Pair<String, String>>,
        timeoutMillis: Long,
        maxResponseBytes: Int,
    ): RemoteMcpOAuthHttpResponse

    fun postJson(
        uri: URI,
        body: ByteArray,
        timeoutMillis: Long,
        maxResponseBytes: Int,
    ): RemoteMcpOAuthHttpResponse
}

/** Redirect-free and public-network-only OAuth transport. */
internal class OkHttpRemoteMcpOAuthHttpClient(
    baseClient: OkHttpClient = OkHttpClient(),
) : RemoteMcpOAuthHttpClient {
    private val template = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(PublicOAuthDns)
        .build()

    override fun get(
        uri: URI,
        timeoutMillis: Long,
        maxResponseBytes: Int,
    ): RemoteMcpOAuthHttpResponse = execute(
        Request.Builder()
            .url(requireOAuthHttpsUri(uri.toASCIIString()).toASCIIString())
            .header("Accept", "application/json")
            .get()
            .build(),
        timeoutMillis,
        maxResponseBytes,
    )

    override fun postForm(
        uri: URI,
        fields: List<Pair<String, String>>,
        timeoutMillis: Long,
        maxResponseBytes: Int,
    ): RemoteMcpOAuthHttpResponse {
        require(fields.size in 1..32)
        val body = fields.joinToString("&") { (name, value) ->
            require(name.matches(Regex("[A-Za-z0-9._~-]{1,64}")))
            require(value.length <= 16 * 1024 && value.none(Char::isISOControl))
            "${name.formEncode()}=${value.formEncode()}"
        }
        return execute(
            Request.Builder()
                .url(requireOAuthHttpsUri(uri.toASCIIString()).toASCIIString())
                .header("Accept", "application/json")
                .post(body.toRequestBody(FORM_CONTENT_TYPE))
                .build(),
            timeoutMillis,
            maxResponseBytes,
        )
    }

    override fun postJson(
        uri: URI,
        body: ByteArray,
        timeoutMillis: Long,
        maxResponseBytes: Int,
    ): RemoteMcpOAuthHttpResponse {
        require(body.size in 2..64 * 1024)
        return execute(
            Request.Builder()
                .url(requireOAuthHttpsUri(uri.toASCIIString()).toASCIIString())
                .header("Accept", "application/json")
                .post(body.toRequestBody(JSON_CONTENT_TYPE))
                .build(),
            timeoutMillis,
            maxResponseBytes,
        )
    }

    private fun execute(
        request: Request,
        timeoutMillis: Long,
        maxResponseBytes: Int,
    ): RemoteMcpOAuthHttpResponse {
        require(timeoutMillis in 1_000L..30_000L)
        require(maxResponseBytes in 1_024..128 * 1024)
        val client = template.newBuilder()
            .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .connectTimeout(minOf(timeoutMillis, 10_000L), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .build()
        return client.newCall(request).execute().use { response ->
            require(!response.isRedirect) { "mcp_oauth_redirect_rejected" }
            val contentType = response.body?.contentType()?.toString().orEmpty()
            val declared = response.body?.contentLength()?.takeIf { it >= 0 }
            require(declared == null || declared <= maxResponseBytes) {
                "mcp_oauth_response_too_large"
            }
            val body = response.body?.byteStream()?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(output.size() + read <= maxResponseBytes) {
                        "mcp_oauth_response_too_large"
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            RemoteMcpOAuthHttpResponse(response.code, contentType, body)
        }
    }

    private object PublicOAuthDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val normalized = hostname.trimEnd('.').lowercase(Locale.ROOT)
            requireOAuthHttpsUri("https://$normalized/")
            return Dns.SYSTEM.lookup(hostname).also { addresses ->
                require(addresses.isNotEmpty()) { "mcp_oauth_host_unresolved" }
                addresses.forEach(::requireRemoteMcpOAuthAddressAllowed)
            }
        }
    }

    private companion object {
        val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded".toMediaType()
        val JSON_CONTENT_TYPE = "application/json".toMediaType()
    }
}

internal fun requireRemoteMcpOAuthAddressAllowed(address: InetAddress) {
    WorkNetworkEndpointPolicy.requireAddressAllowed(address, false)
}

internal data class RemoteMcpOAuthAuthorizationMetadata(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val registrationEndpoint: String?,
    val scopesSupported: Set<String>,
    val authorizationResponseIssuerRequired: Boolean,
) {
    init {
        requireOAuthHttpsUri(issuer)
        requireOAuthHttpsUri(authorizationEndpoint)
        requireOAuthHttpsUri(tokenEndpoint)
        registrationEndpoint?.let(::requireOAuthHttpsUri)
        require(scopesSupported.size <= 128)
    }

    override fun toString(): String = "RemoteMcpOAuthAuthorizationMetadata(redacted)"
}

internal data class RemoteMcpOAuthDiscoveryResult(
    val canonicalResource: String,
    val resourceScopes: Set<String>,
    val authorization: RemoteMcpOAuthAuthorizationMetadata,
)

internal class RemoteMcpOAuthMetadataDiscovery(
    private val http: RemoteMcpOAuthHttpClient,
) {
    fun discover(spec: RemoteMcpOAuthConnectionSpec): RemoteMcpOAuthDiscoveryResult {
        val resource = discoverProtectedResource(spec.resourceEndpoint)
        val issuer = spec.clientOptions.authorizationServerIssuerHint ?: resource.issuer
        require(canonicalOAuthIssuer(issuer) == canonicalOAuthIssuer(resource.issuer)) {
            "mcp_oauth_issuer_hint_mismatch"
        }
        return RemoteMcpOAuthDiscoveryResult(
            canonicalResource = resource.resource,
            resourceScopes = resource.scopes,
            authorization = discoverAuthorizationServer(issuer),
        )
    }

    private fun discoverProtectedResource(resource: String): ProtectedResourceMetadata {
        val resourceUri = requireOAuthHttpsUri(resource)
        val path = resourceUri.rawPath.orEmpty().takeIf { it != "/" }.orEmpty()
        val metadataUri = URI(
            resourceUri.scheme,
            null,
            resourceUri.host,
            resourceUri.port,
            "/.well-known/oauth-protected-resource$path",
            null,
            null,
        )
        val response = http.get(metadataUri, DISCOVERY_TIMEOUT_MILLIS, MAX_DISCOVERY_BYTES)
        requireJsonSuccess(response, "mcp_oauth_resource_metadata_rejected")
        val value = response.jsonObject()
        val advertisedResource = value.getString("resource")
        require(canonicalOAuthResource(advertisedResource) == canonicalOAuthResource(resource)) {
            "mcp_oauth_resource_identity_mismatch"
        }
        val servers = value.optJSONArray("authorization_servers")
            ?: throw IllegalArgumentException("mcp_oauth_authorization_server_missing")
        require(servers.length() == 1) { "mcp_oauth_authorization_server_ambiguous" }
        val scopes = value.optJSONArray("scopes_supported")?.strings().orEmpty()
        scopes.forEach(::requireOAuthScope)
        return ProtectedResourceMetadata(
            resource = canonicalOAuthResource(advertisedResource),
            issuer = servers.getString(0).also(::requireOAuthHttpsUri),
            scopes = scopes,
        )
    }

    private fun discoverAuthorizationServer(issuerRaw: String): RemoteMcpOAuthAuthorizationMetadata {
        val issuer = requireOAuthHttpsUri(issuerRaw)
        val candidates = authorizationMetadataCandidates(issuer)
        var document: JSONObject? = null
        candidates.forEach { candidate ->
            if (document != null) return@forEach
            val response = http.get(candidate, DISCOVERY_TIMEOUT_MILLIS, MAX_DISCOVERY_BYTES)
            if (response.statusCode == 404) return@forEach
            requireJsonSuccess(response, "mcp_oauth_authorization_metadata_rejected")
            document = response.jsonObject()
        }
        val metadata = requireNotNull(document) { "mcp_oauth_authorization_metadata_missing" }
        require(canonicalOAuthIssuer(metadata.getString("issuer")) ==
            canonicalOAuthIssuer(issuer.toASCIIString())) {
            "mcp_oauth_issuer_mismatch"
        }
        requireStringArrayContains(metadata, "code_challenge_methods_supported", "S256")
        metadata.optJSONArray("response_types_supported")?.let { values ->
            require(values.strings().contains("code")) { "mcp_oauth_code_response_unsupported" }
        }
        metadata.optJSONArray("grant_types_supported")?.let { values ->
            require(values.strings().contains("authorization_code")) {
                "mcp_oauth_authorization_code_unsupported"
            }
        }
        metadata.optJSONArray("token_endpoint_auth_methods_supported")?.let { values ->
            require(values.strings().contains("none")) { "mcp_oauth_public_client_unsupported" }
        }
        val responseIssuerAdvertised = metadata
            .opt("authorization_response_iss_parameter_supported")
            ?.let { advertised ->
                require(advertised is Boolean) {
                    "mcp_oauth_authorization_response_issuer_metadata_invalid"
                }
                advertised
            } ?: false
        val scopesSupported = metadata.optJSONArray("scopes_supported")?.strings().orEmpty()
        scopesSupported.forEach(::requireOAuthScope)
        return RemoteMcpOAuthAuthorizationMetadata(
            issuer = issuer.toASCIIString(),
            authorizationEndpoint = metadata.getString("authorization_endpoint").also {
                requireOAuthHttpsUri(it)
            },
            tokenEndpoint = metadata.getString("token_endpoint").also(::requireOAuthHttpsUri),
            registrationEndpoint = metadata.optString("registration_endpoint")
                .takeIf(String::isNotBlank)
                ?.also(::requireOAuthHttpsUri),
            scopesSupported = scopesSupported,
            authorizationResponseIssuerRequired = responseIssuerAdvertised,
        )
    }

    private fun authorizationMetadataCandidates(issuer: URI): List<URI> {
        val issuerPath = issuer.rawPath.orEmpty().trimEnd('/')
        val rfc8414 = URI(
            issuer.scheme,
            null,
            issuer.host,
            issuer.port,
            "/.well-known/oauth-authorization-server$issuerPath",
            null,
            null,
        )
        val oidc = URI(
            issuer.scheme,
            null,
            issuer.host,
            issuer.port,
            "$issuerPath/.well-known/openid-configuration".ifEmpty {
                "/.well-known/openid-configuration"
            },
            null,
            null,
        )
        return listOf(rfc8414, oidc).distinct()
    }

    private companion object {
        const val DISCOVERY_TIMEOUT_MILLIS = 15_000L
        const val MAX_DISCOVERY_BYTES = 64 * 1024
    }
}

internal fun buildRemoteMcpAuthorizationUri(
    metadata: RemoteMcpOAuthAuthorizationMetadata,
    challenge: RemoteMcpPkceMaterial,
    spec: RemoteMcpOAuthConnectionSpec,
    client: RemoteMcpOAuthResolvedClient,
    scopes: Set<String>,
): URI {
    val endpoint = requireOAuthHttpsUri(metadata.authorizationEndpoint)
    val fields = buildList {
        addAll(listOf(
        "response_type" to "code",
        "client_id" to client.clientId,
        "redirect_uri" to client.redirect.uri,
        "state" to challenge.state,
        "nonce" to challenge.nonce,
        "code_challenge" to challenge.challenge,
        "code_challenge_method" to "S256",
        "resource" to canonicalOAuthResource(spec.resourceEndpoint),
        ))
        if (scopes.isNotEmpty()) add("scope" to scopes.sorted().joinToString(" "))
    }
    val appended = fields.joinToString("&") { (key, value) ->
        "${key.formEncode()}=${value.formEncode()}"
    }
    val query = listOfNotNull(endpoint.rawQuery?.takeIf(String::isNotBlank), appended)
        .joinToString("&")
    // [query] is already application/x-www-form-urlencoded. Passing it to a component-wise URI
    // constructor would escape every '%' a second time (for example `%3A` -> `%253A`), changing
    // both redirect_uri and scope at the authorization server.
    require(endpoint.rawFragment == null)
    val endpointWithoutQuery = endpoint.toASCIIString().substringBefore('?')
    return URI.create("$endpointWithoutQuery?$query")
}

private data class ProtectedResourceMetadata(
    val resource: String,
    val issuer: String,
    val scopes: Set<String>,
)

private fun requireJsonSuccess(response: RemoteMcpOAuthHttpResponse, code: String) {
    require(response.statusCode == 200) { code }
    require(response.contentType.startsWith("application/json", ignoreCase = true)) {
        "mcp_oauth_content_type_rejected"
    }
}

private fun RemoteMcpOAuthHttpResponse.jsonObject(): JSONObject {
    require(body.isNotEmpty())
    return try {
        JSONObject(body.toString(StandardCharsets.UTF_8))
    } finally {
        body.fill(0)
    }
}

private fun requireStringArrayContains(value: JSONObject, key: String, required: String) {
    val values = value.optJSONArray(key)
        ?: throw IllegalArgumentException("mcp_oauth_metadata_capability_missing")
    require(values.strings().contains(required)) { "mcp_oauth_metadata_capability_missing" }
}

private fun JSONArray.strings(): Set<String> {
    require(length() in 1..64)
    return (0 until length()).map(::getString).toSet()
}

private fun requireOAuthScope(scope: String) {
    require(scope.matches(Regex("[A-Za-z0-9._~:/-]{1,128}"))) {
        "mcp_oauth_scope_invalid"
    }
    require(scope != "openid") { "mcp_oauth_oidc_scope_unsupported" }
}

private fun String.formEncode(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")
