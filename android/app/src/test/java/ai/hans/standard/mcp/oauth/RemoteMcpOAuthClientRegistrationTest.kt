package ai.hans.standard.mcp.oauth

import ai.hans.standard.mcp.RemoteMcpOAuthCredentialIdentity
import ai.hans.standard.plugins.runtime.OAuthCredentialHandle
import java.io.File
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMcpOAuthClientRegistrationTest {
    @Test
    fun preRegisteredClientWinsWithoutCimdOrDcrNetworkTraffic() {
        val http = ScriptedOAuthHttpClient()
        val provider = provider(http)
        val options = RemoteMcpOAuthClientOptions(
            preRegistered = listOf(
                RemoteMcpOAuthPreRegisteredClient(ISSUER, "pre-client", customRedirect()),
            ),
            cimdClientId = "https://client.example/hans.json",
            cimdRedirect = appLink(),
            dcrRedirect = appLink(),
            allowDeprecatedDynamicRegistrationFallback = true,
        )
        val result = provider.resolve(spec(options), discovery(resourceScopes = setOf("mcp:read")))

        assertEquals(RemoteMcpOAuthClientRegistrationKind.PRE_REGISTERED, result.client.kind)
        assertEquals("pre-client", result.client.clientId)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun issuerMismatchSkipsPreRegistrationAndUsesVerifiedCimd() {
        val http = ScriptedOAuthHttpClient().apply {
            getResponses += RemoteMcpOAuthProtocolTest.json(
                200,
                """{
                    "client_id":"https://client.example/hans.json",
                    "redirect_uris":["https://hans.example/remote-mcp/callback"],
                    "token_endpoint_auth_method":"none",
                    "grant_types":["authorization_code"],
                    "response_types":["code"]
                }""",
            )
        }
        val options = RemoteMcpOAuthClientOptions(
            preRegistered = listOf(
                RemoteMcpOAuthPreRegisteredClient(
                    "https://different.example/",
                    "wrong-client",
                    customRedirect(),
                ),
            ),
            cimdClientId = "https://client.example/hans.json",
            cimdRedirect = appLink(),
        )
        val result = provider(http).resolve(spec(options), discovery())

        assertEquals(RemoteMcpOAuthClientRegistrationKind.CIMD, result.client.kind)
        assertEquals("https://client.example/hans.json", result.client.clientId)
        assertEquals(listOf("GET"), http.requests.map { it.first })
    }

    @Test
    fun cimdRejectsPrivateSchemeAndDocumentRedirectMismatch() {
        assertThrows(IllegalArgumentException::class.java) {
            provider(ScriptedOAuthHttpClient()).resolve(
                spec(
                    RemoteMcpOAuthClientOptions(
                        cimdClientId = "https://client.example/hans.json",
                        cimdRedirect = customRedirect(),
                    ),
                ),
                discovery(),
            )
        }

        val http = ScriptedOAuthHttpClient().apply {
            getResponses += RemoteMcpOAuthProtocolTest.json(
                200,
                """{"client_id":"https://client.example/hans.json","redirect_uris":["https://other.example/callback"],"token_endpoint_auth_method":"none"}""",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider(http).resolve(
                spec(
                    RemoteMcpOAuthClientOptions(
                        cimdClientId = "https://client.example/hans.json",
                        cimdRedirect = appLink(),
                    ),
                ),
                discovery(),
            )
        }
    }

    @Test
    fun dcrIsExplicitFallbackSendsNativeRegistrationAndPersistsResult() {
        val root = tempRoot()
        val store = RemoteMcpOAuthDynamicRegistrationStore(root, TestOAuthCipher())
        val http = ScriptedOAuthHttpClient().apply {
            jsonResponses += RemoteMcpOAuthProtocolTest.json(
                201,
                """{"client_id":"dynamic-client","client_id_issued_at":1800000000}""",
            )
        }
        val provider = RemoteMcpOAuthClientRegistrationProvider(http, store) { NOW }
        val options = RemoteMcpOAuthClientOptions(
            dcrRedirect = customRedirect(),
            allowDeprecatedDynamicRegistrationFallback = true,
        )
        val first = provider.resolve(spec(options), discovery())
        val document = JSONObject(requireNotNull(http.lastJsonBody))

        assertEquals(RemoteMcpOAuthClientRegistrationKind.DCR, first.client.kind)
        assertEquals("native", document.getString("application_type"))
        assertEquals("none", document.getString("token_endpoint_auth_method"))
        assertEquals(
            REMOTE_MCP_OAUTH_CUSTOM_SCHEME_CALLBACK_URI,
            document.getJSONArray("redirect_uris").getString(0),
        )
        val second = RemoteMcpOAuthClientRegistrationProvider(
            ScriptedOAuthHttpClient(),
            RemoteMcpOAuthDynamicRegistrationStore(root, TestOAuthCipher()),
        ) { NOW }.resolve(spec(options), discovery())
        assertEquals("dynamic-client", second.client.clientId)
        assertEquals(1L, second.client.registrationGeneration)
    }

    @Test
    fun dcrIsNeverUsedWithoutExplicitDeprecatedFallbackOptIn() {
        assertThrows(IllegalArgumentException::class.java) {
            provider(ScriptedOAuthHttpClient()).resolve(
                spec(RemoteMcpOAuthClientOptions(dcrRedirect = customRedirect())),
                discovery(),
            )
        }
    }

    @Test
    fun wwwAuthenticateScopeWinsResourceMetadataAndEmptyScopesStayEmpty() {
        val options = RemoteMcpOAuthClientOptions(
            preRegistered = listOf(
                RemoteMcpOAuthPreRegisteredClient(ISSUER, "pre-client", customRedirect()),
            ),
        )
        val challenged = provider(ScriptedOAuthHttpClient()).resolve(
            spec(options, setOf("challenge:scope")),
            discovery(resourceScopes = setOf("resource:scope")),
        )
        assertEquals(setOf("challenge:scope"), challenged.scopes)

        val empty = provider(ScriptedOAuthHttpClient()).resolve(
            spec(options),
            discovery(resourceScopes = emptySet()),
        )
        assertTrue(empty.scopes.isEmpty())
    }

    @Test
    fun authorizationServerScopeCatalogIsNotIntersectedWithResourceScopes() {
        val options = RemoteMcpOAuthClientOptions(
            preRegistered = listOf(
                RemoteMcpOAuthPreRegisteredClient(ISSUER, "pre-client", customRedirect()),
            ),
        )
        val resolved = provider(ScriptedOAuthHttpClient()).resolve(
            spec(options),
            discovery(
                resourceScopes = setOf("resource:scope"),
                authorizationScopes = setOf("different:scope"),
            ),
        )
        assertEquals(setOf("resource:scope"), resolved.scopes)
    }

    private fun provider(http: ScriptedOAuthHttpClient) =
        RemoteMcpOAuthClientRegistrationProvider(
            http,
            RemoteMcpOAuthDynamicRegistrationStore(tempRoot(), TestOAuthCipher()),
        ) { NOW }

    private fun spec(
        options: RemoteMcpOAuthClientOptions,
        scopes: Set<String>? = null,
    ) = RemoteMcpOAuthConnectionSpec(
        identity = RemoteMcpOAuthCredentialIdentity(
            "tasks-plugin",
            "tasks",
            "1".repeat(64),
            OAuthCredentialHandle("2".repeat(64)),
        ),
        resourceEndpoint = "https://mcp.example/v1",
        clientOptions = options,
        challengeScopes = scopes,
    )

    private fun discovery(
        resourceScopes: Set<String> = setOf("mcp:read"),
        authorizationScopes: Set<String> = setOf("mcp:read"),
    ) = RemoteMcpOAuthDiscoveryResult(
        canonicalResource = "https://mcp.example/v1",
        resourceScopes = resourceScopes,
        authorization = RemoteMcpOAuthAuthorizationMetadata(
            issuer = ISSUER,
            authorizationEndpoint = "https://as.example/authorize",
            tokenEndpoint = "https://as.example/token",
            registrationEndpoint = "https://as.example/register",
            scopesSupported = authorizationScopes,
            authorizationResponseIssuerRequired = true,
        ),
    )

    private fun customRedirect() = RemoteMcpOAuthRedirectRoute(
        REMOTE_MCP_OAUTH_CUSTOM_SCHEME_CALLBACK_URI,
        RemoteMcpOAuthRedirectKind.PRIVATE_USE_SCHEME_FALLBACK,
    )

    private fun appLink() = RemoteMcpOAuthRedirectRoute(
        "https://hans.example/remote-mcp/callback",
        RemoteMcpOAuthRedirectKind.VERIFIED_HTTPS_APP_LINK,
    )

    private fun tempRoot(): File = kotlin.io.path.createTempDirectory("hans-oauth-client-test").toFile()

    private companion object {
        const val ISSUER = "https://as.example/"
        const val NOW = 1_800_000_000_000L
    }
}
