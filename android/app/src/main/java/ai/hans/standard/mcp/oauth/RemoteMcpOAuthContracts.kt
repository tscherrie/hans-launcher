package ai.hans.standard.mcp.oauth

import ai.hans.standard.R
import androidx.annotation.StringRes

import ai.hans.standard.mcp.RemoteMcpActivationIdentity
import ai.hans.standard.mcp.RemoteMcpConnectionReason
import ai.hans.standard.mcp.RemoteMcpConnectionRequest
import ai.hans.standard.mcp.RemoteMcpConfigurationIdentity
import ai.hans.standard.mcp.RemoteMcpOAuthCredentialIdentity
import ai.hans.standard.mcp.RemoteMcpOAuthCredentialStatus
import ai.hans.standard.mcp.RemoteMcpOAuthVaultStatusState
import ai.hans.standard.plugins.runtime.RemoteMcpRequirement
import ai.hans.standard.work.WorkNetworkEndpointPolicy
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale

internal const val REMOTE_MCP_OAUTH_CALLBACK_SCHEME = "hans"
internal const val REMOTE_MCP_OAUTH_CALLBACK_HOST = "oauth"
internal const val REMOTE_MCP_OAUTH_CALLBACK_PATH = "/remote-mcp/callback"
internal const val REMOTE_MCP_OAUTH_CUSTOM_SCHEME_CALLBACK_URI =
    "$REMOTE_MCP_OAUTH_CALLBACK_SCHEME://$REMOTE_MCP_OAUTH_CALLBACK_HOST$REMOTE_MCP_OAUTH_CALLBACK_PATH"

internal enum class RemoteMcpOAuthRedirectKind {
    /** A domain-owned Android App Link with certificate-bound assetlinks.json. */
    VERIFIED_HTTPS_APP_LINK,

    /** RFC 8252 private-use scheme, allowed only for an explicitly compatible registration. */
    PRIVATE_USE_SCHEME_FALLBACK,
}

internal data class RemoteMcpOAuthRedirectRoute(
    val uri: String,
    val kind: RemoteMcpOAuthRedirectKind,
) {
    init {
        val parsed = URI(uri)
        when (kind) {
            RemoteMcpOAuthRedirectKind.VERIFIED_HTTPS_APP_LINK -> {
                requireOAuthHttpsUri(uri)
                require(parsed.rawQuery == null && parsed.rawFragment == null)
            }
            RemoteMcpOAuthRedirectKind.PRIVATE_USE_SCHEME_FALLBACK -> require(
                uri == REMOTE_MCP_OAUTH_CUSTOM_SCHEME_CALLBACK_URI,
            ) { "Unregistered private-use OAuth callback" }
        }
    }
}

internal data class RemoteMcpOAuthPreRegisteredClient(
    val issuer: String,
    val clientId: String,
    val redirect: RemoteMcpOAuthRedirectRoute,
) {
    init {
        requireOAuthHttpsUri(issuer)
        requirePublicClientId(clientId)
    }
}

/** Acquisition hints, in strict priority order: pre-registration, CIMD, then DCR fallback. */
internal class RemoteMcpOAuthClientOptions(
    val preRegistered: List<RemoteMcpOAuthPreRegisteredClient> = emptyList(),
    val cimdClientId: String? = null,
    val cimdRedirect: RemoteMcpOAuthRedirectRoute? = null,
    val dcrRedirect: RemoteMcpOAuthRedirectRoute? = null,
    val allowDeprecatedDynamicRegistrationFallback: Boolean = false,
    val authorizationServerIssuerHint: String? = null,
) {
    init {
        require(preRegistered.size <= 16)
        require(preRegistered.map { canonicalOAuthIssuer(it.issuer) }.distinct().size ==
            preRegistered.size) { "Duplicate pre-registered OAuth issuer" }
        if (cimdClientId != null) {
            requireOAuthHttpsUri(cimdClientId)
            requireNotNull(cimdRedirect)
        } else {
            require(cimdRedirect == null)
        }
        if (allowDeprecatedDynamicRegistrationFallback) requireNotNull(dcrRedirect)
        authorizationServerIssuerHint?.let(::requireOAuthHttpsUri)
    }

    override fun toString(): String = "RemoteMcpOAuthClientOptions(redacted)"
}

internal enum class RemoteMcpOAuthClientRegistrationKind { PRE_REGISTERED, CIMD, DCR }

/** Resolved public-client registration. It never contains a client secret. */
internal class RemoteMcpOAuthResolvedClient(
    val clientId: String,
    val redirect: RemoteMcpOAuthRedirectRoute,
    val kind: RemoteMcpOAuthClientRegistrationKind,
    val registrationGeneration: Long?,
) {
    init {
        requirePublicClientId(clientId)
        registrationGeneration?.let { require(it > 0L && kind == RemoteMcpOAuthClientRegistrationKind.DCR) }
    }

    override fun toString(): String = "RemoteMcpOAuthResolvedClient(redacted)"
}

/** Exact, immutable connection target. All security-relevant MCP options are digest-bound. */
internal class RemoteMcpOAuthConnectionSpec(
    val identity: RemoteMcpOAuthCredentialIdentity,
    val resourceEndpoint: String,
    val clientOptions: RemoteMcpOAuthClientOptions,
    challengeScopes: Set<String>? = null,
) {
    /** Scope from WWW-Authenticate, which takes precedence over resource metadata. */
    val challengeScopes: Set<String>? = challengeScopes?.toSortedSet()

    init {
        requireOAuthHttpsUri(resourceEndpoint)
        this.challengeScopes?.let { scopes ->
            require(scopes.size <= 32)
            scopes.forEach(::requireOAuthScopeValue)
        }
    }

    override fun toString(): String =
        "RemoteMcpOAuthConnectionSpec(pluginId=${identity.pluginId}, serverId=${identity.serverId})"

    companion object {
        fun exact(
            request: RemoteMcpConnectionRequest,
            requirement: RemoteMcpRequirement,
            clientOptions: RemoteMcpOAuthClientOptions,
            challengeScopes: Set<String>? = null,
        ): RemoteMcpOAuthConnectionSpec {
            require(request.serverId == requirement.id) { "Remote MCP server correlation changed" }
            val handle = requireNotNull(requirement.oauthHandle) {
                "Remote MCP connection has no OAuth handle"
            }
            val activation = RemoteMcpActivationIdentity(
                pluginId = request.pluginId,
                serverId = requirement.id,
                configurationDigest = RemoteMcpConfigurationIdentity.digest(requirement),
            )
            return RemoteMcpOAuthConnectionSpec(
                identity = RemoteMcpOAuthCredentialIdentity(
                    pluginId = activation.pluginId,
                    serverId = activation.serverId,
                    configurationDigest = activation.configurationDigest,
                    handle = handle,
                ),
                resourceEndpoint = requirement.endpoint,
                clientOptions = clientOptions,
                challengeScopes = challengeScopes,
            )
        }
    }
}

/**
 * Signed application-code seam for future provider pre-registration or CIMD configuration.
 * Plugin manifests cannot populate it. Unknown providers use the explicit RFC 8252 native-app
 * callback plus the deprecated DCR compatibility fallback required by today's generic clients.
 */
internal class SignedRemoteMcpOAuthClientOptionsProvider(
    signedRegistrations: Map<Pair<String, String>, RemoteMcpOAuthClientOptions> = emptyMap(),
) {
    private val registrations = signedRegistrations.toMap()

    fun options(pluginId: String, serverId: String): RemoteMcpOAuthClientOptions =
        registrations[pluginId to serverId] ?: RemoteMcpOAuthClientOptions(
            dcrRedirect = RemoteMcpOAuthRedirectRoute(
                REMOTE_MCP_OAUTH_CUSTOM_SCHEME_CALLBACK_URI,
                RemoteMcpOAuthRedirectKind.PRIVATE_USE_SCHEME_FALLBACK,
            ),
            allowDeprecatedDynamicRegistrationFallback = true,
        )
}

internal enum class RemoteMcpOAuthConnectionFailure {
    CONNECTION_ALREADY_ACTIVE,
    CREDENTIAL_STORE_UNAVAILABLE,
    DISCOVERY_REJECTED,
    BROWSER_UNAVAILABLE,
    CALLBACK_REJECTED,
    CALLBACK_EXPIRED,
    CALLBACK_REPLAYED,
    AUTHORIZATION_REJECTED,
    TOKEN_EXCHANGE_FAILED,
    CREDENTIAL_CHANGED,
    LOCAL_STATE_UNAVAILABLE,
}

internal sealed interface RemoteMcpOAuthStartResult {
    data class BrowserRequired(
        val pluginId: String,
        val serverId: String,
        internal val authorizationUri: URI,
    ) : RemoteMcpOAuthStartResult {
        override fun toString(): String =
            "RemoteMcpOAuthStartResult.BrowserRequired(pluginId=$pluginId, serverId=$serverId)"
    }

    data class AlreadyConnected(val pluginId: String, val serverId: String) :
        RemoteMcpOAuthStartResult

    data class Failed(
        val pluginId: String,
        val serverId: String,
        val failure: RemoteMcpOAuthConnectionFailure,
    ) : RemoteMcpOAuthStartResult
}

internal sealed interface RemoteMcpOAuthCompletionResult {
    data class Connected(
        val pluginId: String,
        val serverId: String,
        /** Installation is never resumed implicitly with stale UI/source evidence. */
        val explicitInstallRetryRequired: Boolean = true,
    ) : RemoteMcpOAuthCompletionResult

    data class Failed(
        val pluginId: String?,
        val serverId: String?,
        val failure: RemoteMcpOAuthConnectionFailure,
    ) : RemoteMcpOAuthCompletionResult
}

/** Safe UI projection. It contains no endpoint, issuer, handle, state or credential detail. */
internal data class RemoteMcpOAuthUiAction(
    val pluginId: String,
    val serverId: String,
    @StringRes val titleResource: Int,
    @StringRes val messageResource: Int,
    @StringRes val actionLabelResource: Int,
)

internal object RemoteMcpOAuthUiActions {
    fun connect(request: RemoteMcpConnectionRequest): RemoteMcpOAuthUiAction =
        RemoteMcpOAuthUiAction(
            pluginId = request.pluginId,
            serverId = request.serverId,
            titleResource = R.string.presentation_mcp_connection_required,
            messageResource = when (request.reason) {
                RemoteMcpConnectionReason.MISSING ->
                    R.string.presentation_mcp_connect_missing
                RemoteMcpConnectionReason.EXPIRED ->
                    R.string.presentation_mcp_connect_expired
                RemoteMcpConnectionReason.STALE_IDENTITY ->
                    R.string.presentation_mcp_connect_stale
                RemoteMcpConnectionReason.CREDENTIAL_STORE_UNAVAILABLE ->
                    R.string.presentation_mcp_connect_store_unavailable
            },
            actionLabelResource = R.string.presentation_mcp_connect_action,
        )

    fun retryInstall(pluginId: String, serverId: String): RemoteMcpOAuthUiAction =
        RemoteMcpOAuthUiAction(
            pluginId = pluginId,
            serverId = serverId,
            titleResource = R.string.presentation_mcp_connected_title,
            messageResource = R.string.presentation_mcp_connected_message,
            actionLabelResource = R.string.presentation_plugin_retry_install,
        )
}

internal fun interface RemoteMcpOAuthRandomSource {
    fun bytes(size: Int): ByteArray
}

internal object SecureRemoteMcpOAuthRandomSource : RemoteMcpOAuthRandomSource {
    private val random = SecureRandom()
    override fun bytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
}

internal data class RemoteMcpPkceMaterial(
    val state: String,
    val nonce: String,
    val verifier: String,
    val challenge: String,
) {
    override fun toString(): String = "RemoteMcpPkceMaterial(redacted)"
}

internal object RemoteMcpPkce {
    fun create(random: RemoteMcpOAuthRandomSource = SecureRemoteMcpOAuthRandomSource):
        RemoteMcpPkceMaterial {
        val nonceBytes = random.bytes(32)
        val stateRandom = random.bytes(32)
        val verifierBytes = random.bytes(64)
        try {
            val nonce = nonceBytes.base64Url()
            val nonceBinding = sha256(nonce.toByteArray(StandardCharsets.US_ASCII)).base64Url()
            val state = "${stateRandom.base64Url()}.$nonceBinding"
            val verifier = verifierBytes.base64Url()
            val challenge = sha256(verifier.toByteArray(StandardCharsets.US_ASCII)).base64Url()
            require(verifier.length in 43..128)
            return RemoteMcpPkceMaterial(state, nonce, verifier, challenge)
        } finally {
            nonceBytes.fill(0)
            stateRandom.fill(0)
            verifierBytes.fill(0)
        }
    }
}

internal fun requireOAuthHttpsUri(raw: String): URI {
    val uri = WorkNetworkEndpointPolicy.validateUri(raw)
    require(uri.scheme.equals("https", ignoreCase = true)) { "OAuth requires HTTPS" }
    return uri
}

internal fun canonicalOAuthIssuer(raw: String): String {
    val uri = requireOAuthHttpsUri(raw).normalize()
    val port = when {
        uri.port == 443 -> -1
        else -> uri.port
    }
    val path = uri.rawPath.orEmpty().ifBlank { "/" }.trimEnd('/').ifEmpty { "/" }
    return URI("https", null, uri.host.lowercase(Locale.ROOT), port, path, uri.rawQuery, null)
        .toASCIIString()
}

internal fun canonicalOAuthResource(raw: String): String {
    val uri = requireOAuthHttpsUri(raw).normalize()
    val port = if (uri.port == 443) -1 else uri.port
    val path = uri.rawPath.orEmpty().ifBlank { "/" }
    return URI("https", null, uri.host.lowercase(Locale.ROOT), port, path, uri.rawQuery, null)
        .toASCIIString()
}

private fun requirePublicClientId(clientId: String) {
    require(clientId.length in 1..2_048 && clientId.none(Char::isISOControl)) {
        "Invalid Remote MCP OAuth client id"
    }
}

internal fun requireOAuthScopeValue(scope: String) {
    require(scope.matches(Regex("[A-Za-z0-9._~:/-]{1,128}"))) {
        "Invalid Remote MCP OAuth scope"
    }
    require(scope != "openid") { "OIDC identity scopes are not supported" }
}

internal fun RemoteMcpOAuthCredentialStatus.expectedRotationGeneration(): Long? = when (state) {
    RemoteMcpOAuthVaultStatusState.MISSING -> null
    RemoteMcpOAuthVaultStatusState.AVAILABLE,
    RemoteMcpOAuthVaultStatusState.EXPIRED,
    -> requireNotNull(generation)
    RemoteMcpOAuthVaultStatusState.STALE_IDENTITY,
    RemoteMcpOAuthVaultStatusState.UNAVAILABLE,
    -> error("Credential is not safely replaceable")
}

private fun sha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

private fun ByteArray.base64Url(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(this)
