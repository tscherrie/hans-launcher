package ai.hans.standard.mcp.oauth

import ai.hans.standard.mcp.AndroidKeystoreRemoteMcpOAuthVault
import ai.hans.standard.mcp.RemoteMcpActivationIdentity
import ai.hans.standard.mcp.RemoteMcpOAuthCredentialIdentity
import ai.hans.standard.mcp.RemoteMcpOAuthMutationResult
import ai.hans.standard.mcp.RemoteMcpTool
import ai.hans.standard.mcp.RemoteMcpToolPolicyRegistry
import ai.hans.standard.plugins.runtime.OAuthCredentialHandle
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMcpOAuthConnectionFlowTest {
    @Test
    fun successfulOAuthStoresCredentialButRequiresExplicitInstallRetryAndNoToolPolicy() {
        val fixture = Fixture(responseIssuerAdvertised = false)
        val policies = RemoteMcpToolPolicyRegistry(emptyList())
        val activation = fixture.spec.identity.let {
            RemoteMcpActivationIdentity(it.pluginId, it.serverId, it.configurationDigest)
        }
        val tool = RemoteMcpTool("tasks/list", null, null, "{}", null)
        assertNull(policies.resolve(activation, tool))

        val started = fixture.flow.start(fixture.spec) as
            RemoteMcpOAuthStartResult.BrowserRequired
        val authorizationQuery = query(started.authorizationUri)
        assertEquals("S256", authorizationQuery["code_challenge_method"])
        assertEquals("mcp:read", authorizationQuery["scope"])
        fixture.queueToken("stored-access", "stored-refresh")

        val completed = fixture.flow.complete(
            fixture.callback(authorizationQuery.getValue("state"), issuer = null),
        ) as RemoteMcpOAuthCompletionResult.Connected

        assertTrue(completed.explicitInstallRetryRequired)
        assertEquals("stored-access", fixture.vault.withBearerToken(fixture.spec.identity) { it })
        assertNull(policies.resolve(activation, tool))
        assertEquals(
            REMOTE_MCP_OAUTH_CUSTOM_SCHEME_CALLBACK_URI,
            fixture.http.lastFormFields!!.toMap().getValue("redirect_uri"),
        )
        assertEquals(
            canonicalOAuthResource(fixture.spec.resourceEndpoint),
            fixture.http.lastFormFields!!.toMap().getValue("resource"),
        )
        assertEquals(
            authorizationQuery.getValue("code_challenge"),
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                java.security.MessageDigest.getInstance("SHA-256").digest(
                    fixture.http.lastFormFields!!.toMap().getValue("code_verifier")
                        .toByteArray(StandardCharsets.US_ASCII),
                ),
            ),
        )
    }

    @Test
    fun launcherResultExtrasAreConsumedOnceAndNeverContainAnInstallInstruction() {
        val extras = linkedMapOf(
            EXTRA_REMOTE_MCP_OAUTH_STATUS to "connected_retry_required",
            EXTRA_REMOTE_MCP_OAUTH_PLUGIN_ID to "tasks-plugin",
            EXTRA_REMOTE_MCP_OAUTH_SERVER_ID to "tasks",
        )
        val source = RemoteMcpOAuthResultExtraSource { name -> extras.remove(name) }

        val first = RemoteMcpOAuthResultConsumer.consume(source)

        assertEquals(
            RemoteMcpOAuthSafeResult.Connected("tasks-plugin", "tasks"),
            first,
        )
        assertTrue(extras.isEmpty())
        assertNull(RemoteMcpOAuthResultConsumer.consume(source))
        assertFalse(first.toString().contains("install", ignoreCase = true))
    }

    @Test
    fun advertisedIssuerIsByteExactAndMismatchConsumesCallbackBeforeTokenExchange() {
        val fixture = Fixture(responseIssuerAdvertised = true)
        val started = fixture.flow.start(fixture.spec) as
            RemoteMcpOAuthStartResult.BrowserRequired
        val state = query(started.authorizationUri).getValue("state")

        val result = fixture.flow.complete(
            fixture.callback(state, issuer = "https://AS.example/"),
        ) as RemoteMcpOAuthCompletionResult.Failed

        assertEquals(RemoteMcpOAuthConnectionFailure.CALLBACK_REJECTED, result.failure)
        assertFalse(fixture.http.requests.any { it.first == "FORM" })
        val replay = fixture.flow.complete(fixture.callback(state, issuer = ISSUER)) as
            RemoteMcpOAuthCompletionResult.Failed
        assertEquals(RemoteMcpOAuthConnectionFailure.CALLBACK_REJECTED, replay.failure)
    }

    @Test
    fun callbackOnDifferentOriginFailsClosedBeforeTokenExchange() {
        val fixture = Fixture(responseIssuerAdvertised = false)
        val started = fixture.flow.start(fixture.spec) as
            RemoteMcpOAuthStartResult.BrowserRequired
        val state = query(started.authorizationUri).getValue("state")
        val wrongOrigin = URI(
            "https://other.example$REMOTE_MCP_OAUTH_CALLBACK_PATH?code=code&state=$state",
        )

        val result = fixture.flow.complete(wrongOrigin) as RemoteMcpOAuthCompletionResult.Failed

        assertEquals(RemoteMcpOAuthConnectionFailure.CALLBACK_REJECTED, result.failure)
        assertFalse(fixture.http.requests.any { it.first == "FORM" })
    }

    @Test
    fun expiredCredentialRotationUsesExactGenerationCas() {
        val fixture = Fixture(responseIssuerAdvertised = false, initialNow = NOW - 10_000)
        assertEquals(
            RemoteMcpOAuthMutationResult.STORED,
            fixture.vault.store(
                fixture.spec.identity,
                "expired-access".toCharArray(),
                "existing-refresh".toCharArray(),
                NOW - 5_000,
            ),
        )
        fixture.now = NOW
        val started = fixture.flow.start(fixture.spec) as
            RemoteMcpOAuthStartResult.BrowserRequired
        val state = query(started.authorizationUri).getValue("state")

        assertEquals(
            RemoteMcpOAuthMutationResult.ROTATED,
            fixture.vault.refresh(
                fixture.spec.identity,
                expectedGeneration = 1,
                accessToken = "concurrent-winner".toCharArray(),
                refreshToken = null,
                expiresAtEpochMillis = NOW + 120_000,
            ),
        )
        fixture.queueToken("stale-callback-token", "stale-callback-refresh")

        val result = fixture.flow.complete(fixture.callback(state, issuer = null)) as
            RemoteMcpOAuthCompletionResult.Failed

        assertEquals(RemoteMcpOAuthConnectionFailure.CREDENTIAL_CHANGED, result.failure)
        assertEquals(
            "concurrent-winner",
            fixture.vault.withBearerToken(fixture.spec.identity) { it },
        )
        val replay = fixture.flow.complete(fixture.callback(state, issuer = null)) as
            RemoteMcpOAuthCompletionResult.Failed
        assertEquals(RemoteMcpOAuthConnectionFailure.CALLBACK_REJECTED, replay.failure)
    }

    @Test
    fun sameOpaqueHandleWithChangedConfigurationCannotStartReauthorization() {
        val fixture = Fixture(responseIssuerAdvertised = false)
        assertEquals(
            RemoteMcpOAuthMutationResult.STORED,
            fixture.vault.store(
                fixture.spec.identity,
                "bound-access".toCharArray(),
                null,
                NOW + 120_000,
            ),
        )
        val changed = RemoteMcpOAuthConnectionSpec(
            identity = RemoteMcpOAuthCredentialIdentity(
                pluginId = fixture.spec.identity.pluginId,
                serverId = fixture.spec.identity.serverId,
                configurationDigest = "9".repeat(64),
                handle = fixture.spec.identity.handle,
            ),
            resourceEndpoint = fixture.spec.resourceEndpoint,
            clientOptions = fixture.spec.clientOptions,
        )

        val result = fixture.flow.start(changed) as RemoteMcpOAuthStartResult.Failed

        assertEquals(RemoteMcpOAuthConnectionFailure.CREDENTIAL_STORE_UNAVAILABLE, result.failure)
        assertTrue(fixture.http.requests.isEmpty())
        assertEquals("bound-access", fixture.vault.withBearerToken(fixture.spec.identity) { it })
    }

    private class Fixture(
        responseIssuerAdvertised: Boolean,
        initialNow: Long = NOW,
    ) {
        val root: File = kotlin.io.path.createTempDirectory("hans-oauth-flow-test").toFile()
        val cipher = TestOAuthCipher()
        var now: Long = initialNow
        val vault = AndroidKeystoreRemoteMcpOAuthVault(root, cipher) { now }
        val http = ScriptedOAuthHttpClient()
        val spec = RemoteMcpOAuthConnectionSpec(
            identity = RemoteMcpOAuthCredentialIdentity(
                pluginId = "tasks-plugin",
                serverId = "tasks",
                configurationDigest = "1".repeat(64),
                handle = OAuthCredentialHandle("2".repeat(64)),
            ),
            resourceEndpoint = "https://mcp.example/v1",
            clientOptions = RemoteMcpOAuthClientOptions(
                preRegistered = listOf(
                    RemoteMcpOAuthPreRegisteredClient(
                        ISSUER,
                        "public-client",
                        RemoteMcpOAuthRedirectRoute(
                            REMOTE_MCP_OAUTH_CUSTOM_SCHEME_CALLBACK_URI,
                            RemoteMcpOAuthRedirectKind.PRIVATE_USE_SCHEME_FALLBACK,
                        ),
                    ),
                ),
            ),
        )
        private val challenges = RemoteMcpOAuthChallengeStore(
            root,
            cipher,
            { now },
            RemoteMcpOAuthRandomSource { size -> ByteArray(size) { 7 } },
        )
        val flow: RemoteMcpOAuthConnectionFlow

        init {
            http.getResponses += RemoteMcpOAuthProtocolTest.json(
                200,
                """{"resource":"https://mcp.example/v1","authorization_servers":["$ISSUER"],"scopes_supported":["mcp:read"]}""",
            )
            http.getResponses += RemoteMcpOAuthProtocolTest.json(
                200,
                RemoteMcpOAuthProtocolTest.authorizationMetadataJson(
                    responseIssuer = responseIssuerAdvertised,
                ),
            )
            flow = RemoteMcpOAuthConnectionFlow(
                vault = vault,
                challenges = challenges,
                discovery = RemoteMcpOAuthMetadataDiscovery(http),
                clients = RemoteMcpOAuthClientRegistrationProvider(
                    http,
                    RemoteMcpOAuthDynamicRegistrationStore(root, cipher),
                ) { now },
                exchange = RemoteMcpOAuthTokenExchange(http) { now },
                committer = RemoteMcpOAuthCredentialCommitter(vault),
                nowEpochMillis = { now },
                random = RemoteMcpOAuthRandomSource { size -> ByteArray(size) { 3 } },
            )
        }

        fun queueToken(access: String, refresh: String?) {
            val refreshJson = refresh?.let { ",\"refresh_token\":\"$it\"" }.orEmpty()
            http.formResponses += RemoteMcpOAuthProtocolTest.json(
                200,
                """{"token_type":"Bearer","access_token":"$access"$refreshJson,"expires_in":3600,"scope":"mcp:read"}""",
            )
        }

        fun callback(state: String, issuer: String?): URI {
            val iss = issuer?.let {
                "&iss=" + java.net.URLEncoder.encode(it, StandardCharsets.UTF_8)
                    .replace("+", "%20")
            }.orEmpty()
            return URI("$REMOTE_MCP_OAUTH_CUSTOM_SCHEME_CALLBACK_URI?code=code&state=$state$iss")
        }
    }

    private fun query(uri: URI): Map<String, String> = uri.rawQuery.split('&').associate { part ->
        val (key, value) = part.split('=', limit = 2)
        URLDecoder.decode(key, StandardCharsets.UTF_8) to
            URLDecoder.decode(value, StandardCharsets.UTF_8)
    }

    private companion object {
        const val ISSUER = "https://as.example/"
        const val NOW = 1_800_000_000_000L
    }
}
