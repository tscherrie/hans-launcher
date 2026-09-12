package ai.hans.standard.mcp.oauth

import ai.hans.standard.mcp.AndroidKeystoreRemoteMcpOAuthCipher
import ai.hans.standard.mcp.RemoteMcpOAuthCipher
import ai.hans.standard.mcp.RemoteMcpOAuthCipherEnvelope
import ai.hans.standard.mcp.RemoteMcpPrivateStateFile
import ai.hans.standard.mcp.RemoteMcpPrivateStateLock
import android.content.Context
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

internal data class RemoteMcpOAuthClientResolution(
    val client: RemoteMcpOAuthResolvedClient,
    val scopes: Set<String>,
)

internal class RemoteMcpOAuthClientRegistrationProvider(
    private val http: RemoteMcpOAuthHttpClient,
    private val registrations: RemoteMcpOAuthDynamicRegistrationStore,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    fun resolve(
        spec: RemoteMcpOAuthConnectionSpec,
        discovery: RemoteMcpOAuthDiscoveryResult,
    ): RemoteMcpOAuthClientResolution {
        val issuer = canonicalOAuthIssuer(discovery.authorization.issuer)
        val scopes = selectScopes(spec, discovery)

        spec.clientOptions.preRegistered.singleOrNull {
            canonicalOAuthIssuer(it.issuer) == issuer
        }?.let { registered ->
            return RemoteMcpOAuthClientResolution(
                RemoteMcpOAuthResolvedClient(
                    registered.clientId,
                    registered.redirect,
                    RemoteMcpOAuthClientRegistrationKind.PRE_REGISTERED,
                    null,
                ),
                scopes,
            )
        }

        val cimdClientId = spec.clientOptions.cimdClientId
        val cimdRedirect = spec.clientOptions.cimdRedirect
        if (cimdClientId != null && cimdRedirect != null) {
            verifyCimd(cimdClientId, cimdRedirect)
            return RemoteMcpOAuthClientResolution(
                RemoteMcpOAuthResolvedClient(
                    cimdClientId,
                    cimdRedirect,
                    RemoteMcpOAuthClientRegistrationKind.CIMD,
                    null,
                ),
                scopes,
            )
        }

        require(spec.clientOptions.allowDeprecatedDynamicRegistrationFallback) {
            "mcp_oauth_client_registration_missing"
        }
        val redirect = requireNotNull(spec.clientOptions.dcrRedirect)
        val endpoint = requireNotNull(discovery.authorization.registrationEndpoint) {
            "mcp_oauth_dynamic_registration_unavailable"
        }
        val identity = RemoteMcpOAuthDynamicRegistrationIdentity(
            issuer = issuer,
            redirectUri = redirect.uri,
            registrationEndpointDigest = sha256(endpoint),
        )
        registrations.find(identity, nowEpochMillis())?.let { stored ->
            return RemoteMcpOAuthClientResolution(
                RemoteMcpOAuthResolvedClient(
                    stored.clientId,
                    redirect,
                    RemoteMcpOAuthClientRegistrationKind.DCR,
                    stored.generation,
                ),
                scopes,
            )
        }
        val registered = dynamicallyRegister(endpoint, identity)
        val stored = registrations.storeIfAbsent(registered)
        require(stored != null) { "mcp_oauth_dynamic_registration_cas_failed" }
        return RemoteMcpOAuthClientResolution(
            RemoteMcpOAuthResolvedClient(
                stored.clientId,
                redirect,
                RemoteMcpOAuthClientRegistrationKind.DCR,
                stored.generation,
            ),
            scopes,
        )
    }

    private fun verifyCimd(clientId: String, redirect: RemoteMcpOAuthRedirectRoute) {
        require(redirect.kind == RemoteMcpOAuthRedirectKind.VERIFIED_HTTPS_APP_LINK) {
            "mcp_oauth_cimd_requires_verified_app_link"
        }
        val response = http.get(requireOAuthHttpsUri(clientId), 15_000L, 64 * 1024)
        require(response.statusCode == 200 &&
            response.contentType.startsWith("application/json", ignoreCase = true)) {
            "mcp_oauth_cimd_rejected"
        }
        val body = response.body
        try {
            val value = JSONObject(body.toString(StandardCharsets.UTF_8))
            require(value.optString("client_id") == clientId) { "mcp_oauth_cimd_identity_mismatch" }
            require(value.getJSONArray("redirect_uris").strings().contains(redirect.uri)) {
                "mcp_oauth_cimd_redirect_mismatch"
            }
            require(value.optString("token_endpoint_auth_method", "none") == "none") {
                "mcp_oauth_cimd_confidential_client_rejected"
            }
            value.optJSONArray("grant_types")?.let {
                require(it.strings().contains("authorization_code"))
            }
            value.optJSONArray("response_types")?.let { require(it.strings().contains("code")) }
        } finally {
            body.fill(0)
        }
    }

    private fun dynamicallyRegister(
        endpoint: String,
        identity: RemoteMcpOAuthDynamicRegistrationIdentity,
    ): RemoteMcpOAuthDynamicRegistration {
        val requestBytes = JSONObject()
            .put("client_name", "Hans")
            .put("application_type", "native")
            .put("redirect_uris", JSONArray().put(identity.redirectUri))
            .put("token_endpoint_auth_method", "none")
            .put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
            .put("response_types", JSONArray().put("code"))
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val response = try {
            http.postJson(requireOAuthHttpsUri(endpoint), requestBytes, 20_000L, 64 * 1024)
        } finally {
            requestBytes.fill(0)
        }
        require(response.statusCode in setOf(200, 201) &&
            response.contentType.startsWith("application/json", ignoreCase = true)) {
            "mcp_oauth_dynamic_registration_rejected"
        }
        val body = response.body
        try {
            val value = JSONObject(body.toString(StandardCharsets.UTF_8))
            require(!value.has("client_secret")) { "mcp_oauth_confidential_client_rejected" }
            val clientId = value.getString("client_id")
            require(clientId.length in 1..2_048 && clientId.none(Char::isISOControl))
            val expiresAt = value.opt("client_id_expires_at")?.let { raw ->
                require(raw is Number)
                raw.toLong().takeIf { it > 0L }?.let { seconds -> Math.multiplyExact(seconds, 1_000L) }
            }
            require(expiresAt == null || expiresAt > nowEpochMillis()) {
                "mcp_oauth_dynamic_registration_expired"
            }
            return RemoteMcpOAuthDynamicRegistration(identity, clientId, 1L, expiresAt)
        } finally {
            body.fill(0)
        }
    }

    private fun selectScopes(
        spec: RemoteMcpOAuthConnectionSpec,
        discovery: RemoteMcpOAuthDiscoveryResult,
    ): Set<String> {
        val selected = spec.challengeScopes ?: discovery.resourceScopes
        require(selected.size <= 32) { "mcp_oauth_scope_set_unsupported" }
        return selected.toSortedSet()
    }
}

internal data class RemoteMcpOAuthDynamicRegistrationIdentity(
    val issuer: String,
    val redirectUri: String,
    val registrationEndpointDigest: String,
) {
    init {
        require(canonicalOAuthIssuer(issuer) == issuer)
        RemoteMcpOAuthRedirectRoute(
            redirectUri,
            if (redirectUri.startsWith("https://")) {
                RemoteMcpOAuthRedirectKind.VERIFIED_HTTPS_APP_LINK
            } else {
                RemoteMcpOAuthRedirectKind.PRIVATE_USE_SCHEME_FALLBACK
            },
        )
        require(registrationEndpointDigest.matches(Regex("[0-9a-f]{64}")))
    }
}

internal data class RemoteMcpOAuthDynamicRegistration(
    val identity: RemoteMcpOAuthDynamicRegistrationIdentity,
    val clientId: String,
    val generation: Long,
    val expiresAtEpochMillis: Long?,
) {
    init {
        require(clientId.length in 1..2_048 && clientId.none(Char::isISOControl))
        require(generation > 0L)
        expiresAtEpochMillis?.let { require(it > 0L) }
    }
}

/** Encrypted DCR identity store with exact issuer/redirect/endpoint identity and CAS generations. */
internal class RemoteMcpOAuthDynamicRegistrationStore internal constructor(
    directory: File,
    private val cipher: RemoteMcpOAuthCipher,
) {
    constructor(context: Context) : this(
        File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME),
        AndroidKeystoreRemoteMcpOAuthCipher(KEY_ALIAS),
    )

    private val file = RemoteMcpPrivateStateFile(directory, FILE_NAME, 256 * 1024)

    fun find(
        identity: RemoteMcpOAuthDynamicRegistrationIdentity,
        nowEpochMillis: Long,
    ): RemoteMcpOAuthDynamicRegistration? = synchronized(RemoteMcpPrivateStateLock.process) {
        read().singleOrNull { it.identity == identity }
            ?.takeIf { it.expiresAtEpochMillis == null || it.expiresAtEpochMillis > nowEpochMillis }
    }

    /** Returns the exact stored value; null means another identity won the compare-and-set. */
    fun storeIfAbsent(
        registration: RemoteMcpOAuthDynamicRegistration,
    ): RemoteMcpOAuthDynamicRegistration? = synchronized(RemoteMcpPrivateStateLock.process) {
        val current = read()
        current.singleOrNull { it.identity == registration.identity }?.let { return@synchronized it }
        if (current.any {
                it.identity.issuer == registration.identity.issuer &&
                    it.identity.redirectUri == registration.identity.redirectUri
            }
        ) {
            return@synchronized null
        }
        require(current.size < 64)
        write(current + registration)
        registration
    }

    fun rotate(
        replacement: RemoteMcpOAuthDynamicRegistration,
        expectedGeneration: Long,
    ): Boolean = synchronized(RemoteMcpPrivateStateLock.process) {
        val current = read()
        val index = current.indexOfFirst { it.identity == replacement.identity }
        if (index < 0 || current[index].generation != expectedGeneration ||
            replacement.generation != expectedGeneration + 1L
        ) {
            return@synchronized false
        }
        write(current.toMutableList().also { it[index] = replacement })
        true
    }

    private fun read(): List<RemoteMcpOAuthDynamicRegistration> {
        val bytes = file.readOrNull() ?: return emptyList()
        return try {
            val envelopeJson = JSONObject(bytes.toString(StandardCharsets.UTF_8))
            require(envelopeJson.keys().asSequence().toSet() == setOf("iv", "ciphertext"))
            val envelope = RemoteMcpOAuthCipherEnvelope(
                Base64.getDecoder().decode(envelopeJson.getString("iv")),
                Base64.getDecoder().decode(envelopeJson.getString("ciphertext")),
            )
            try {
                val clear = cipher.decrypt(envelope, AAD)
                try {
                    RegistrationCodec.decode(clear.toString(StandardCharsets.UTF_8))
                } finally {
                    clear.fill(0)
                }
            } finally {
                envelope.erase()
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun write(registrations: List<RemoteMcpOAuthDynamicRegistration>) {
        val clear = RegistrationCodec.encode(registrations).toByteArray(StandardCharsets.UTF_8)
        try {
            val envelope = cipher.encrypt(clear, AAD)
            try {
                val bytes = JSONObject()
                    .put("iv", Base64.getEncoder().encodeToString(envelope.iv))
                    .put("ciphertext", Base64.getEncoder().encodeToString(envelope.ciphertext))
                    .toString()
                    .toByteArray(StandardCharsets.UTF_8)
                try {
                    file.write(bytes)
                } finally {
                    bytes.fill(0)
                }
            } finally {
                envelope.erase()
            }
        } finally {
            clear.fill(0)
        }
    }

    private companion object {
        const val DIRECTORY_NAME = "remote-mcp/oauth-registration"
        const val FILE_NAME = "clients-v1.json"
        const val KEY_ALIAS = "ai.hans.standard.remote-mcp-oauth-registration.v1"
        val AAD = "hans.remote-mcp-oauth-registration.v1".toByteArray(StandardCharsets.US_ASCII)
    }
}

private object RegistrationCodec {
    fun encode(values: List<RemoteMcpOAuthDynamicRegistration>): String = JSONObject()
        .put("schema", "hans.remote-mcp-oauth-clients")
        .put("version", 1)
        .put("clients", JSONArray(values.sortedBy { it.identity.issuer }.map(::encodeOne)))
        .toString()

    fun decode(raw: String): List<RemoteMcpOAuthDynamicRegistration> {
        val root = JSONObject(raw)
        require(root.keys().asSequence().toSet() == setOf("schema", "version", "clients"))
        require(root.getString("schema") == "hans.remote-mcp-oauth-clients" &&
            root.getInt("version") == 1)
        val array = root.getJSONArray("clients")
        require(array.length() <= 64)
        val values = (0 until array.length()).map { decodeOne(array.getJSONObject(it)) }
        require(values.map { it.identity }.distinct().size == values.size)
        return values
    }

    private fun encodeOne(value: RemoteMcpOAuthDynamicRegistration): JSONObject = JSONObject()
        .put("issuer", value.identity.issuer)
        .put("redirectUri", value.identity.redirectUri)
        .put("registrationEndpointDigest", value.identity.registrationEndpointDigest)
        .put("clientId", value.clientId)
        .put("generation", value.generation)
        .put("expiresAtEpochMillis", value.expiresAtEpochMillis ?: JSONObject.NULL)

    private fun decodeOne(value: JSONObject): RemoteMcpOAuthDynamicRegistration {
        require(value.keys().asSequence().toSet() == setOf(
            "issuer", "redirectUri", "registrationEndpointDigest", "clientId", "generation",
            "expiresAtEpochMillis",
        ))
        return RemoteMcpOAuthDynamicRegistration(
            RemoteMcpOAuthDynamicRegistrationIdentity(
                value.getString("issuer"),
                value.getString("redirectUri"),
                value.getString("registrationEndpointDigest"),
            ),
            value.getString("clientId"),
            value.getLong("generation"),
            if (value.isNull("expiresAtEpochMillis")) null else value.getLong("expiresAtEpochMillis"),
        )
    }
}

private fun JSONArray.strings(): Set<String> {
    require(length() in 1..128)
    return (0 until length()).map(::getString).toSet()
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
