package ai.hans.standard.mcp.oauth

import ai.hans.standard.mcp.AndroidKeystoreRemoteMcpOAuthVault
import ai.hans.standard.mcp.RemoteMcpOAuthVaultStatusState
import ai.hans.standard.plugins.requireBoundedToken
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URI

internal class RemoteMcpOAuthConnectionFlow(
    private val vault: AndroidKeystoreRemoteMcpOAuthVault,
    private val challenges: RemoteMcpOAuthChallengeStore,
    private val discovery: RemoteMcpOAuthMetadataDiscovery,
    private val clients: RemoteMcpOAuthClientRegistrationProvider,
    private val exchange: RemoteMcpOAuthTokenExchange,
    private val committer: RemoteMcpOAuthCredentialCommitter,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val random: RemoteMcpOAuthRandomSource = SecureRemoteMcpOAuthRandomSource,
) {
    /** Blocking discovery. The caller must invoke this off Android's main thread. */
    fun start(spec: RemoteMcpOAuthConnectionSpec): RemoteMcpOAuthStartResult {
        val status = runCatching { vault.status(spec.identity) }.getOrElse {
            return failed(spec, RemoteMcpOAuthConnectionFailure.CREDENTIAL_STORE_UNAVAILABLE)
        }
        if (status.state == RemoteMcpOAuthVaultStatusState.AVAILABLE) {
            return RemoteMcpOAuthStartResult.AlreadyConnected(
                spec.identity.pluginId,
                spec.identity.serverId,
            )
        }
        if (status.state in setOf(
                RemoteMcpOAuthVaultStatusState.STALE_IDENTITY,
                RemoteMcpOAuthVaultStatusState.UNAVAILABLE,
            )
        ) {
            return failed(spec, RemoteMcpOAuthConnectionFailure.CREDENTIAL_STORE_UNAVAILABLE)
        }
        val discovered = runCatching { discovery.discover(spec) }.getOrElse {
            return failed(spec, RemoteMcpOAuthConnectionFailure.DISCOVERY_REJECTED)
        }
        val client = runCatching { clients.resolve(spec, discovered) }.getOrElse {
            return failed(spec, RemoteMcpOAuthConnectionFailure.DISCOVERY_REJECTED)
        }
        val pkce = RemoteMcpPkce.create(random)
        val now = nowEpochMillis()
        val challenge = RemoteMcpOAuthPendingChallenge(
            spec = spec,
            client = client.client,
            scopes = client.scopes,
            issuer = discovered.authorization.issuer,
            authorizationEndpoint = discovered.authorization.authorizationEndpoint,
            tokenEndpoint = discovered.authorization.tokenEndpoint,
            state = pkce.state,
            nonce = pkce.nonce,
            verifier = pkce.verifier,
            createdAtEpochMillis = now,
            expiresAtEpochMillis = Math.addExact(now, CHALLENGE_TTL_MILLIS),
            expectedCredentialGeneration = status.expectedRotationGeneration(),
            authorizationResponseIssuerRequired =
                discovered.authorization.authorizationResponseIssuerRequired,
        )
        if (!challenges.put(challenge)) {
            return failed(spec, RemoteMcpOAuthConnectionFailure.CONNECTION_ALREADY_ACTIVE)
        }
        return RemoteMcpOAuthStartResult.BrowserRequired(
            pluginId = spec.identity.pluginId,
            serverId = spec.identity.serverId,
            authorizationUri = buildRemoteMcpAuthorizationUri(
                discovered.authorization,
                pkce,
                spec,
                client.client,
                client.scopes,
            ),
        )
    }

    /** Blocking token exchange. A claimed code is consumed exactly once, including on failure. */
    fun complete(callbackUri: URI): RemoteMcpOAuthCompletionResult {
        val callback = runCatching { RemoteMcpOAuthCallbackParser.parse(callbackUri) }.getOrElse {
            return RemoteMcpOAuthCompletionResult.Failed(
                null,
                null,
                RemoteMcpOAuthConnectionFailure.CALLBACK_REJECTED,
            )
        }
        val claim = when (val claimed = challenges.claim(callback.state)) {
            is RemoteMcpOAuthChallengeClaimResult.Claimed -> claimed.claim
            RemoteMcpOAuthChallengeClaimResult.Expired -> return callbackFailed(
                RemoteMcpOAuthConnectionFailure.CALLBACK_EXPIRED,
            )
            RemoteMcpOAuthChallengeClaimResult.Replayed -> return callbackFailed(
                RemoteMcpOAuthConnectionFailure.CALLBACK_REPLAYED,
            )
            RemoteMcpOAuthChallengeClaimResult.Missing,
            RemoteMcpOAuthChallengeClaimResult.Mismatch,
            -> return callbackFailed(RemoteMcpOAuthConnectionFailure.CALLBACK_REJECTED)
            RemoteMcpOAuthChallengeClaimResult.Unavailable -> return callbackFailed(
                RemoteMcpOAuthConnectionFailure.LOCAL_STATE_UNAVAILABLE,
            )
        }
        val identity = claim.challenge.spec.identity
        if (callbackBaseUri(callbackUri) != callbackBaseUri(URI(claim.challenge.client.redirect.uri))) {
            challenges.finish(claim)
            return RemoteMcpOAuthCompletionResult.Failed(
                identity.pluginId,
                identity.serverId,
                RemoteMcpOAuthConnectionFailure.CALLBACK_REJECTED,
            )
        }
        if (!remoteMcpOAuthCallbackIssuerMatches(
                advertised = claim.challenge.authorizationResponseIssuerRequired,
                expectedIssuer = claim.challenge.issuer,
                callbackIssuer = callback.issuer,
            )
        ) {
            challenges.finish(claim)
            return RemoteMcpOAuthCompletionResult.Failed(
                identity.pluginId,
                identity.serverId,
                RemoteMcpOAuthConnectionFailure.CALLBACK_REJECTED,
            )
        }
        if (callback is RemoteMcpOAuthCallback.Rejected) {
            challenges.finish(claim)
            return RemoteMcpOAuthCompletionResult.Failed(
                identity.pluginId,
                identity.serverId,
                RemoteMcpOAuthConnectionFailure.AUTHORIZATION_REJECTED,
            )
        }
        val tokens = runCatching {
            exchange.exchange(claim.challenge, (callback as RemoteMcpOAuthCallback.Authorized).code)
        }.getOrElse {
            challenges.finish(claim)
            return RemoteMcpOAuthCompletionResult.Failed(
                identity.pluginId,
                identity.serverId,
                RemoteMcpOAuthConnectionFailure.TOKEN_EXCHANGE_FAILED,
            )
        }
        return try {
            val committed = runCatching {
                committer.commit(
                    identity,
                    claim.challenge.expectedCredentialGeneration,
                    tokens,
                )
            }.getOrDefault(false)
            if (!committed) {
                RemoteMcpOAuthCompletionResult.Failed(
                    identity.pluginId,
                    identity.serverId,
                    RemoteMcpOAuthConnectionFailure.CREDENTIAL_CHANGED,
                )
            } else if (!challenges.finish(claim)) {
                // Credential commit succeeded but local callback cleanup is ambiguous. The exact
                // vault identity remains safe; reject this callback rather than retrying a code.
                RemoteMcpOAuthCompletionResult.Failed(
                    identity.pluginId,
                    identity.serverId,
                    RemoteMcpOAuthConnectionFailure.LOCAL_STATE_UNAVAILABLE,
                )
            } else {
                RemoteMcpOAuthCompletionResult.Connected(
                    pluginId = identity.pluginId,
                    serverId = identity.serverId,
                    explicitInstallRetryRequired = true,
                )
            }
        } finally {
            tokens.erase()
            // Failure paths consume the authorization code and challenge as well.
            runCatching { challenges.finish(claim) }
        }
    }

    private fun failed(
        spec: RemoteMcpOAuthConnectionSpec,
        failure: RemoteMcpOAuthConnectionFailure,
    ) = RemoteMcpOAuthStartResult.Failed(
        pluginId = spec.identity.pluginId,
        serverId = spec.identity.serverId,
        failure = failure,
    )

    private fun callbackFailed(
        failure: RemoteMcpOAuthConnectionFailure,
    ) = RemoteMcpOAuthCompletionResult.Failed(null, null, failure)

    private companion object {
        const val CHALLENGE_TTL_MILLIS = 10L * 60L * 1_000L
    }
}

internal fun remoteMcpOAuthCallbackIssuerMatches(
    advertised: Boolean,
    expectedIssuer: String,
    callbackIssuer: String?,
): Boolean = when {
    advertised && callbackIssuer == null -> false
    callbackIssuer != null && callbackIssuer != expectedIssuer -> false
    else -> true
}

private fun callbackBaseUri(uri: URI): String = URI(
    uri.scheme.lowercase(),
    null,
    uri.host.lowercase(),
    uri.port,
    uri.path,
    null,
    null,
).toASCIIString()

internal object AndroidRemoteMcpOAuthFlowFactory {
    fun create(context: Context): RemoteMcpOAuthConnectionFlow {
        val vault = AndroidKeystoreRemoteMcpOAuthVault(context)
        val http = OkHttpRemoteMcpOAuthHttpClient()
        return RemoteMcpOAuthConnectionFlow(
            vault = vault,
            challenges = RemoteMcpOAuthChallengeStore(context),
            discovery = RemoteMcpOAuthMetadataDiscovery(http),
            clients = RemoteMcpOAuthClientRegistrationProvider(
                http,
                RemoteMcpOAuthDynamicRegistrationStore(context),
            ),
            exchange = RemoteMcpOAuthTokenExchange(http),
            committer = RemoteMcpOAuthCredentialCommitter(vault),
        )
    }
}

internal object AndroidRemoteMcpOAuthBrowser {
    fun launch(activity: Activity, required: RemoteMcpOAuthStartResult.BrowserRequired): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(required.authorizationUri.toASCIIString()))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
        return try {
            if (intent.resolveActivity(activity.packageManager) == null) return false
            activity.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

internal const val EXTRA_REMOTE_MCP_OAUTH_STATUS =
    "ai.hans.standard.extra.REMOTE_MCP_OAUTH_STATUS"
internal const val EXTRA_REMOTE_MCP_OAUTH_PLUGIN_ID =
    "ai.hans.standard.extra.REMOTE_MCP_OAUTH_PLUGIN_ID"
internal const val EXTRA_REMOTE_MCP_OAUTH_SERVER_ID =
    "ai.hans.standard.extra.REMOTE_MCP_OAUTH_SERVER_ID"

internal fun interface RemoteMcpOAuthResultExtraSource {
    /** Returns and consumes one string value. */
    fun take(name: String): String?
}

internal sealed interface RemoteMcpOAuthSafeResult {
    data class Connected(val pluginId: String, val serverId: String) :
        RemoteMcpOAuthSafeResult

    data class Failed(
        val pluginId: String?,
        val serverId: String?,
        val failure: RemoteMcpOAuthConnectionFailure,
    ) : RemoteMcpOAuthSafeResult
}

/** One-shot decoder for the explicit LauncherActivity result extras. */
internal object RemoteMcpOAuthResultConsumer {
    fun consume(source: RemoteMcpOAuthResultExtraSource): RemoteMcpOAuthSafeResult? {
        val status = source.take(EXTRA_REMOTE_MCP_OAUTH_STATUS)
        val pluginId = safeResultId(source.take(EXTRA_REMOTE_MCP_OAUTH_PLUGIN_ID))
        val serverId = safeResultId(source.take(EXTRA_REMOTE_MCP_OAUTH_SERVER_ID))
        return when {
            status == "connected_retry_required" && pluginId != null && serverId != null ->
                RemoteMcpOAuthSafeResult.Connected(pluginId, serverId)
            status?.startsWith("failed_") == true -> {
                val failure = RemoteMcpOAuthConnectionFailure.entries.singleOrNull {
                    it.name.equals(status.removePrefix("failed_"), ignoreCase = true)
                } ?: return null
                RemoteMcpOAuthSafeResult.Failed(pluginId, serverId, failure)
            }
            else -> null
        }
    }

    private fun safeResultId(value: String?): String? = value?.let { candidate ->
        runCatching { requireBoundedToken(candidate, "Remote MCP OAuth result") }
            .map { candidate }
            .getOrNull()
    }
}

internal fun RemoteMcpOAuthCompletionResult.safeResultIntent(context: Context): Intent {
    val intent = Intent().setClassName(context, "ai.hans.standard.LauncherActivity")
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    when (this) {
        is RemoteMcpOAuthCompletionResult.Connected -> intent
            .putExtra(EXTRA_REMOTE_MCP_OAUTH_STATUS, "connected_retry_required")
            .putExtra(EXTRA_REMOTE_MCP_OAUTH_PLUGIN_ID, pluginId)
            .putExtra(EXTRA_REMOTE_MCP_OAUTH_SERVER_ID, serverId)
        is RemoteMcpOAuthCompletionResult.Failed -> intent
            .putExtra(EXTRA_REMOTE_MCP_OAUTH_STATUS, "failed_${failure.name.lowercase()}")
            .apply {
                pluginId?.let { putExtra(EXTRA_REMOTE_MCP_OAUTH_PLUGIN_ID, it) }
                serverId?.let { putExtra(EXTRA_REMOTE_MCP_OAUTH_SERVER_ID, it) }
            }
    }
    return intent
}
