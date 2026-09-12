package ai.hans.standard.mcp.oauth

import ai.hans.standard.mcp.RemoteMcpOAuthCredentialIdentity
import ai.hans.standard.plugins.runtime.OAuthCredentialHandle
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMcpOAuthProtocolTest {
    @Test
    fun signedClientOptionsProviderUsesExplicitRfc8252DcrFallbackForUnknownProvider() {
        val options = SignedRemoteMcpOAuthClientOptionsProvider().options(
            "tasks-plugin",
            "tasks",
        )

        assertTrue(options.allowDeprecatedDynamicRegistrationFallback)
        assertEquals(
            REMOTE_MCP_OAUTH_CUSTOM_SCHEME_CALLBACK_URI,
            options.dcrRedirect?.uri,
        )
        assertEquals(
            RemoteMcpOAuthRedirectKind.PRIVATE_USE_SCHEME_FALLBACK,
            options.dcrRedirect?.kind,
        )
        assertTrue(options.preRegistered.isEmpty())
        assertEquals("RemoteMcpOAuthClientOptions(redacted)", options.toString())
    }

    @Test
    fun pkceUsesS256AndBindsIndependentCryptographicNonceIntoState() {
        var marker = 0
        val material = RemoteMcpPkce.create(RemoteMcpOAuthRandomSource { size ->
            ByteArray(size) { (marker++ and 0xff).toByte() }
        })

        assertTrue(material.verifier.length in 43..128)
        assertEquals(
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(material.verifier.toByteArray(StandardCharsets.US_ASCII)),
            ),
            material.challenge,
        )
        assertTrue(material.state.contains('.'))
        assertNotEquals(material.nonce, material.state.substringBefore('.'))
        assertFalse(material.toString().contains(material.verifier))
    }

    @Test
    fun rfc9207AdvertisedIssuerMustBePresentAndExact() {
        assertTrue(remoteMcpOAuthCallbackIssuerMatches(true, ISSUER, ISSUER))
        assertFalse(remoteMcpOAuthCallbackIssuerMatches(true, ISSUER, null))
    }

    @Test
    fun rfc9207UnadvertisedIssuerMayBeAbsentButIfPresentMustBeExact() {
        assertTrue(remoteMcpOAuthCallbackIssuerMatches(false, ISSUER, null))
        assertTrue(remoteMcpOAuthCallbackIssuerMatches(false, ISSUER, ISSUER))
        assertFalse(remoteMcpOAuthCallbackIssuerMatches(false, ISSUER, "https://other.example/"))
    }

    @Test
    fun rfc9207IssuerComparisonDoesNotNormalizeEquivalentStrings() {
        assertFalse(remoteMcpOAuthCallbackIssuerMatches(true, "https://as.example", "https://as.example/"))
        assertFalse(remoteMcpOAuthCallbackIssuerMatches(true, "https://AS.example/", "https://as.example/"))
        assertFalse(remoteMcpOAuthCallbackIssuerMatches(false, "https://as.example:443/", "https://as.example/"))
    }

    @Test
    fun callbackParserAllowsIssButRejectsDuplicatesUnknownsAndMixedResults() {
        val state = "a".repeat(43) + "." + "b".repeat(43)
        val parsed = RemoteMcpOAuthCallbackParser.parse(
            URI("hans://oauth/remote-mcp/callback?code=ok&state=$state&iss=https%3A%2F%2Fas.example%2F"),
        ) as RemoteMcpOAuthCallback.Authorized
        assertEquals("https://as.example/", parsed.issuer)

        assertThrows(IllegalArgumentException::class.java) {
            RemoteMcpOAuthCallbackParser.parse(
                URI("hans://oauth/remote-mcp/callback?code=a&state=$state&state=$state"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteMcpOAuthCallbackParser.parse(
                URI("hans://oauth/remote-mcp/callback?code=a&state=$state&token=secret"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteMcpOAuthCallbackParser.parse(
                URI("hans://oauth/remote-mcp/callback?code=a&error=no&state=$state"),
            )
        }
    }

    @Test
    fun authorizationRequestOmitsScopeWhenResourceAndChallengeDoNotDefineOne() {
        val spec = spec(RemoteMcpOAuthClientOptions())
        val client = resolvedClient()
        val uri = buildRemoteMcpAuthorizationUri(metadata(false), pkce(), spec, client, emptySet())
        val query = query(uri)

        assertFalse("scope" in query)
        assertEquals("S256", query["code_challenge_method"])
        assertEquals(client.redirect.uri, query["redirect_uri"])
        assertEquals(canonicalOAuthResource(spec.resourceEndpoint), query["resource"])
    }

    @Test
    fun authorizationRequestUsesSelectedChallengeScopeAndNeverClientManifestScope() {
        val spec = spec(RemoteMcpOAuthClientOptions(), setOf("mcp:write"))
        val uri = buildRemoteMcpAuthorizationUri(
            metadata(true),
            pkce(),
            spec,
            resolvedClient(),
            setOf("mcp:write"),
        )
        assertEquals("mcp:write", query(uri)["scope"])
    }

    @Test
    fun publicNetworkPolicyRejectsLoopbackPrivateLinkLocalAndMetadataAddresses() {
        listOf("127.0.0.1", "10.0.0.1", "192.168.1.1", "169.254.169.254", "::1")
            .forEach { literal ->
                assertThrows(IllegalArgumentException::class.java) {
                    requireRemoteMcpOAuthAddressAllowed(InetAddress.getByName(literal))
                }
            }
    }

    @Test
    fun metadataDiscoveryRejectsRedirectIssuerMismatchAndMissingIssuerResponseSupportIsAllowed() {
        val http = ScriptedOAuthHttpClient().apply {
            getResponses += json(
                200,
                """{"resource":"https://mcp.example/v1","authorization_servers":["https://as.example/"],"scopes_supported":[]}""",
            )
            getResponses += RemoteMcpOAuthHttpResponse(302, "text/html", ByteArray(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteMcpOAuthMetadataDiscovery(http).discover(spec(RemoteMcpOAuthClientOptions()))
        }

        val mismatch = ScriptedOAuthHttpClient().apply {
            getResponses += json(
                200,
                """{"resource":"https://mcp.example/v1","authorization_servers":["https://as.example/"]}""",
            )
            getResponses += json(
                200,
                authorizationMetadataJson(issuer = "https://other.example/", responseIssuer = false),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteMcpOAuthMetadataDiscovery(mismatch).discover(spec(RemoteMcpOAuthClientOptions()))
        }
    }

    @Test
    fun metadataDiscoveryBindsCanonicalResourceAndCarriesOptionalIssuerFlag() {
        val http = ScriptedOAuthHttpClient().apply {
            getResponses += json(
                200,
                """{"resource":"https://mcp.example:443/a/../v1","authorization_servers":["https://as.example/"],"scopes_supported":["mcp:read"]}""",
            )
            getResponses += json(200, authorizationMetadataJson(responseIssuer = false))
        }
        val result = RemoteMcpOAuthMetadataDiscovery(http).discover(
            RemoteMcpOAuthConnectionSpec(
                identity(),
                "https://mcp.example/v1",
                RemoteMcpOAuthClientOptions(),
            ),
        )
        assertEquals("https://mcp.example/v1", result.canonicalResource)
        assertEquals(setOf("mcp:read"), result.resourceScopes)
        assertFalse(result.authorization.authorizationResponseIssuerRequired)
    }

    private fun spec(
        options: RemoteMcpOAuthClientOptions,
        scopes: Set<String>? = null,
    ) = RemoteMcpOAuthConnectionSpec(
        identity = identity(),
        resourceEndpoint = "https://mcp.example/v1",
        clientOptions = options,
        challengeScopes = scopes,
    )

    private fun identity() = RemoteMcpOAuthCredentialIdentity(
        pluginId = "tasks-plugin",
        serverId = "tasks",
        configurationDigest = "1".repeat(64),
        handle = OAuthCredentialHandle("2".repeat(64)),
    )

    private fun resolvedClient() = RemoteMcpOAuthResolvedClient(
        "public-client",
        RemoteMcpOAuthRedirectRoute(
            REMOTE_MCP_OAUTH_CUSTOM_SCHEME_CALLBACK_URI,
            RemoteMcpOAuthRedirectKind.PRIVATE_USE_SCHEME_FALLBACK,
        ),
        RemoteMcpOAuthClientRegistrationKind.PRE_REGISTERED,
        null,
    )

    private fun metadata(responseIssuer: Boolean) = RemoteMcpOAuthAuthorizationMetadata(
        issuer = ISSUER,
        authorizationEndpoint = "https://as.example/authorize",
        tokenEndpoint = "https://as.example/token",
        registrationEndpoint = "https://as.example/register",
        scopesSupported = setOf("unrelated"),
        authorizationResponseIssuerRequired = responseIssuer,
    )

    private fun pkce() = RemoteMcpPkceMaterial(
        state = "a".repeat(43) + "." + "b".repeat(43),
        nonce = "c".repeat(43),
        verifier = "d".repeat(86),
        challenge = "e".repeat(43),
    )

    private fun query(uri: URI): Map<String, String> = uri.rawQuery.split('&').associate { part ->
        val (key, value) = part.split('=', limit = 2)
        URLDecoder.decode(key, StandardCharsets.UTF_8) to
            URLDecoder.decode(value, StandardCharsets.UTF_8)
    }

    companion object {
        const val ISSUER = "https://as.example/"

        fun authorizationMetadataJson(
            issuer: String = ISSUER,
            responseIssuer: Boolean,
        ): String = """{
            "issuer":"$issuer",
            "authorization_endpoint":"https://as.example/authorize",
            "token_endpoint":"https://as.example/token",
            "registration_endpoint":"https://as.example/register",
            "code_challenge_methods_supported":["S256"],
            "response_types_supported":["code"],
            "grant_types_supported":["authorization_code","refresh_token"],
            "token_endpoint_auth_methods_supported":["none"],
            "authorization_response_iss_parameter_supported":$responseIssuer,
            "scopes_supported":["mcp:read","mcp:write"]
        }"""

        internal fun json(status: Int, body: String) = RemoteMcpOAuthHttpResponse(
            status,
            "application/json",
            body.toByteArray(StandardCharsets.UTF_8),
        )
    }
}

internal class ScriptedOAuthHttpClient : RemoteMcpOAuthHttpClient {
    val getResponses = ArrayDeque<RemoteMcpOAuthHttpResponse>()
    val formResponses = ArrayDeque<RemoteMcpOAuthHttpResponse>()
    val jsonResponses = ArrayDeque<RemoteMcpOAuthHttpResponse>()
    val requests = mutableListOf<Pair<String, String>>()
    var lastFormFields: List<Pair<String, String>>? = null
    var lastJsonBody: String? = null

    override fun get(uri: URI, timeoutMillis: Long, maxResponseBytes: Int):
        RemoteMcpOAuthHttpResponse {
        requests += "GET" to uri.toASCIIString()
        return getResponses.removeFirst()
    }

    override fun postForm(
        uri: URI,
        fields: List<Pair<String, String>>,
        timeoutMillis: Long,
        maxResponseBytes: Int,
    ): RemoteMcpOAuthHttpResponse {
        requests += "FORM" to uri.toASCIIString()
        lastFormFields = fields.toList()
        return formResponses.removeFirst()
    }

    override fun postJson(
        uri: URI,
        body: ByteArray,
        timeoutMillis: Long,
        maxResponseBytes: Int,
    ): RemoteMcpOAuthHttpResponse {
        requests += "JSON" to uri.toASCIIString()
        lastJsonBody = body.toString(StandardCharsets.UTF_8)
        return jsonResponses.removeFirst()
    }
}
