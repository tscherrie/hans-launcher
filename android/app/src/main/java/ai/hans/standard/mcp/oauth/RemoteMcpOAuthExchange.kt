package ai.hans.standard.mcp.oauth

import ai.hans.standard.mcp.AndroidKeystoreRemoteMcpOAuthVault
import ai.hans.standard.mcp.RemoteMcpOAuthCredentialIdentity
import ai.hans.standard.mcp.RemoteMcpOAuthMutationResult
import java.net.URI
import java.nio.charset.StandardCharsets
import org.json.JSONObject

internal sealed interface RemoteMcpOAuthCallback {
    val state: String
    val issuer: String?

    data class Authorized(
        override val state: String,
        override val issuer: String?,
        val code: String,
    ) : RemoteMcpOAuthCallback {
        override fun toString(): String = "RemoteMcpOAuthCallback.Authorized(redacted)"
    }

    data class Rejected(override val state: String, override val issuer: String?) :
        RemoteMcpOAuthCallback
}

internal object RemoteMcpOAuthCallbackParser {
    fun parse(raw: URI): RemoteMcpOAuthCallback {
        val custom = raw.scheme == REMOTE_MCP_OAUTH_CALLBACK_SCHEME &&
            raw.host == REMOTE_MCP_OAUTH_CALLBACK_HOST
        val appLink = raw.scheme == "https" && !raw.host.isNullOrBlank()
        require(custom || appLink)
        require(raw.path == REMOTE_MCP_OAUTH_CALLBACK_PATH)
        require(raw.port == -1 && raw.rawUserInfo == null && raw.rawFragment == null)
        val values = linkedMapOf<String, String>()
        val query = raw.rawQuery ?: throw IllegalArgumentException("mcp_oauth_callback_query_missing")
        require(query.toByteArray(StandardCharsets.UTF_8).size <= MAX_CALLBACK_BYTES)
        query.split('&').forEach { pair ->
            val separator = pair.indexOf('=')
            require(separator > 0) { "mcp_oauth_callback_parameter_invalid" }
            val key = percentDecode(pair.substring(0, separator))
            val value = percentDecode(pair.substring(separator + 1))
            require(key in ALLOWED_PARAMETERS) { "mcp_oauth_callback_parameter_unknown" }
            require(values.putIfAbsent(key, value) == null) {
                "mcp_oauth_callback_parameter_duplicate"
            }
        }
        val state = values["state"] ?: throw IllegalArgumentException("mcp_oauth_state_missing")
        require(state.length in 80..256)
        val code = values["code"]
        val error = values["error"]
        val issuer = values["iss"]?.also(::requireOAuthHttpsUri)
        require((code != null) xor (error != null)) { "mcp_oauth_callback_result_ambiguous" }
        if (error != null) {
            require(error.matches(Regex("[A-Za-z0-9._~-]{1,128}")))
            return RemoteMcpOAuthCallback.Rejected(state, issuer)
        }
        require(code!!.length in 1..16 * 1024 && code.none(Char::isISOControl))
        return RemoteMcpOAuthCallback.Authorized(state, issuer, code)
    }

    private fun percentDecode(raw: String): String {
        require(raw.length <= 24 * 1024)
        val output = ByteArray(raw.length)
        var outputIndex = 0
        var index = 0
        while (index < raw.length) {
            val current = raw[index]
            when {
                current == '%' -> {
                    require(index + 2 < raw.length)
                    val high = raw[index + 1].digitToIntOrNull(16) ?: error("bad escape")
                    val low = raw[index + 2].digitToIntOrNull(16) ?: error("bad escape")
                    output[outputIndex++] = ((high shl 4) or low).toByte()
                    index += 3
                }
                current.code in 0x21..0x7e && current != '+' -> {
                    output[outputIndex++] = current.code.toByte()
                    index += 1
                }
                current == '+' -> {
                    output[outputIndex++] = ' '.code.toByte()
                    index += 1
                }
                else -> throw IllegalArgumentException("mcp_oauth_callback_encoding_invalid")
            }
        }
        return try {
            output.copyOf(outputIndex).toString(StandardCharsets.UTF_8).also { decoded ->
                require(decoded.none(Char::isISOControl))
            }
        } finally {
            output.fill(0)
        }
    }

    private const val MAX_CALLBACK_BYTES = 32 * 1024
    private val ALLOWED_PARAMETERS = setOf(
        "code", "state", "iss", "error", "error_description", "error_uri",
    )
}

internal class RemoteMcpOAuthTokenSet(
    accessToken: CharArray,
    refreshToken: CharArray?,
    val expiresAtEpochMillis: Long?,
) {
    val accessToken: CharArray = accessToken.copyOf()
    val refreshToken: CharArray? = refreshToken?.copyOf()

    fun erase() {
        accessToken.fill('\u0000')
        refreshToken?.fill('\u0000')
    }

    override fun toString(): String = "RemoteMcpOAuthTokenSet(redacted)"
}

internal class RemoteMcpOAuthTokenExchange(
    private val http: RemoteMcpOAuthHttpClient,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    fun exchange(
        challenge: RemoteMcpOAuthPendingChallenge,
        authorizationCode: String,
    ): RemoteMcpOAuthTokenSet {
        require(authorizationCode.length in 1..16 * 1024)
        val response = http.postForm(
            uri = requireOAuthHttpsUri(challenge.tokenEndpoint),
            fields = listOf(
                "grant_type" to "authorization_code",
                "code" to authorizationCode,
                "redirect_uri" to challenge.client.redirect.uri,
                "client_id" to challenge.client.clientId,
                "code_verifier" to challenge.verifier,
                "resource" to challenge.spec.resourceEndpoint,
            ),
            timeoutMillis = TOKEN_TIMEOUT_MILLIS,
            maxResponseBytes = MAX_TOKEN_RESPONSE_BYTES,
        )
        require(response.statusCode == 200) { "mcp_oauth_token_exchange_rejected" }
        require(response.contentType.startsWith("application/json", ignoreCase = true)) {
            "mcp_oauth_token_content_type_rejected"
        }
        val body = response.body
        try {
            val value = JSONObject(body.toString(StandardCharsets.UTF_8))
            require(value.optString("token_type").equals("Bearer", ignoreCase = true)) {
                "mcp_oauth_token_type_rejected"
            }
            // This flow is OAuth-only. Accepting an unverified OIDC ID token would weaken nonce
            // validation, so an unexpected identity token fails closed.
            require(!value.has("id_token")) { "mcp_oauth_unverified_id_token_rejected" }
            val access = tokenChars(value.getString("access_token"), MAX_ACCESS_TOKEN_CHARS)
            val refresh = value.optString("refresh_token").takeIf(String::isNotBlank)?.let {
                tokenChars(it, MAX_REFRESH_TOKEN_CHARS)
            }
            try {
                val expiresAt = value.opt("expires_in")?.let { raw ->
                    require(raw is Number) { "mcp_oauth_expiry_invalid" }
                    val seconds = raw.toLong()
                    require(seconds in 1L..MAX_EXPIRY_SECONDS) { "mcp_oauth_expiry_invalid" }
                    Math.addExact(nowEpochMillis(), Math.multiplyExact(seconds, 1_000L))
                }
                value.optString("scope").takeIf(String::isNotBlank)?.let { granted ->
                    val grantedScopes = granted.split(' ').filter(String::isNotBlank).toSet()
                    require(challenge.scopes.isEmpty() || grantedScopes.containsAll(challenge.scopes)) {
                        "mcp_oauth_scope_downgraded"
                    }
                }
                return RemoteMcpOAuthTokenSet(access, refresh, expiresAt)
            } finally {
                access.fill('\u0000')
                refresh?.fill('\u0000')
            }
        } finally {
            body.fill(0)
        }
    }

    private fun tokenChars(raw: String, maximum: Int): CharArray {
        require(raw.length in 1..maximum && raw.all { it.code in 0x21..0x7e }) {
            "mcp_oauth_token_invalid"
        }
        return raw.toCharArray()
    }

    private companion object {
        const val TOKEN_TIMEOUT_MILLIS = 20_000L
        const val MAX_TOKEN_RESPONSE_BYTES = 64 * 1024
        const val MAX_ACCESS_TOKEN_CHARS = 16 * 1024
        const val MAX_REFRESH_TOKEN_CHARS = 32 * 1024
        const val MAX_EXPIRY_SECONDS = 365L * 24L * 60L * 60L
    }
}

/** Exact AndroidKeystore write/rotation. Reauthorization can never overwrite changed identity. */
internal class RemoteMcpOAuthCredentialCommitter(
    private val vault: AndroidKeystoreRemoteMcpOAuthVault,
) {
    fun commit(
        identity: RemoteMcpOAuthCredentialIdentity,
        expectedGeneration: Long?,
        tokens: RemoteMcpOAuthTokenSet,
    ): Boolean {
        val result = if (expectedGeneration == null) {
            vault.store(
                identity,
                tokens.accessToken,
                tokens.refreshToken,
                tokens.expiresAtEpochMillis,
            )
        } else {
            vault.refresh(
                identity = identity,
                expectedGeneration = expectedGeneration,
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                expiresAtEpochMillis = tokens.expiresAtEpochMillis,
            )
        }
        return result in setOf(
            RemoteMcpOAuthMutationResult.STORED,
            RemoteMcpOAuthMutationResult.ROTATED,
        )
    }
}
