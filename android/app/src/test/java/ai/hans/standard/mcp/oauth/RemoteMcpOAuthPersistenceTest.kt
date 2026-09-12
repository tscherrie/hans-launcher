package ai.hans.standard.mcp.oauth

import ai.hans.standard.mcp.RemoteMcpOAuthCipher
import ai.hans.standard.mcp.RemoteMcpOAuthCipherEnvelope
import ai.hans.standard.mcp.RemoteMcpOAuthCredentialIdentity
import ai.hans.standard.plugins.runtime.OAuthCredentialHandle
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMcpOAuthPersistenceTest {
    @Test
    fun pendingChallengeSurvivesRecreationAndCanBeClaimedOnlyOnce() {
        val root = tempRoot()
        val cipher = TestOAuthCipher()
        val first = store(root, cipher, NOW)
        val challenge = challenge(NOW)
        assertTrue(first.put(challenge))

        val recreated = store(root, cipher, NOW + 1)
        val claimed = recreated.claim(challenge.state) as RemoteMcpOAuthChallengeClaimResult.Claimed
        assertEquals(challenge.spec.identity, claimed.claim.challenge.spec.identity)
        assertEquals(challenge.spec.resourceEndpoint, claimed.claim.challenge.spec.resourceEndpoint)
        assertEquals(challenge.client.clientId, claimed.claim.challenge.client.clientId)
        assertEquals(challenge.client.redirect, claimed.claim.challenge.client.redirect)
        assertEquals(challenge.issuer, claimed.claim.challenge.issuer)
        assertEquals(challenge.authorizationResponseIssuerRequired,
            claimed.claim.challenge.authorizationResponseIssuerRequired)
        val afterClaimRecreation = store(root, cipher, NOW + 2)
        assertEquals(
            RemoteMcpOAuthChallengeClaimResult.Replayed,
            afterClaimRecreation.claim(challenge.state),
        )
        assertTrue(afterClaimRecreation.finish(claimed.claim))
        assertEquals(
            RemoteMcpOAuthChallengeClaimResult.Missing,
            store(root, cipher, NOW + 3).claim(challenge.state),
        )
    }

    @Test
    fun mismatchDoesNotConsumeChallengeAndExpiredChallengeFailsClosed() {
        val root = tempRoot()
        val cipher = TestOAuthCipher()
        val challenge = challenge(NOW)
        assertTrue(store(root, cipher, NOW).put(challenge))
        assertEquals(
            RemoteMcpOAuthChallengeClaimResult.Mismatch,
            store(root, cipher, NOW + 1).claim("x".repeat(43) + "." + "y".repeat(43)),
        )
        assertTrue(store(root, cipher, NOW + 2).claim(challenge.state) is
            RemoteMcpOAuthChallengeClaimResult.Claimed)

        val expiredRoot = tempRoot()
        assertTrue(store(expiredRoot, cipher, NOW).put(challenge))
        assertEquals(
            RemoteMcpOAuthChallengeClaimResult.Expired,
            store(expiredRoot, cipher, challenge.expiresAtEpochMillis).claim(challenge.state),
        )
    }

    @Test
    fun tamperedChallengeCiphertextIsUnavailableAndNeverClaimed() {
        val root = tempRoot()
        val cipher = TestOAuthCipher()
        val challenge = challenge(NOW)
        assertTrue(store(root, cipher, NOW).put(challenge))
        val file = File(root, "pending-v1.json")
        val json = JSONObject(file.readText())
        val ciphertext = json.getString("ciphertext")
        json.put("ciphertext", ciphertext.dropLast(2) + "AA")
        file.writeText(json.toString())

        assertEquals(
            RemoteMcpOAuthChallengeClaimResult.Unavailable,
            store(root, cipher, NOW + 1).claim(challenge.state),
        )
    }

    @Test
    fun dynamicRegistrationPersistsAndRotationRequiresExactGenerationCas() {
        val root = tempRoot()
        val cipher = TestOAuthCipher()
        val identity = registrationIdentity("a".repeat(64))
        val initial = RemoteMcpOAuthDynamicRegistration(identity, "client-one", 1, null)
        val first = RemoteMcpOAuthDynamicRegistrationStore(root, cipher)
        assertEquals(initial, first.storeIfAbsent(initial))

        val recreated = RemoteMcpOAuthDynamicRegistrationStore(root, cipher)
        assertEquals(initial, recreated.find(identity, NOW))
        assertFalse(
            recreated.rotate(
                RemoteMcpOAuthDynamicRegistration(identity, "client-two", 2, null),
                expectedGeneration = 2,
            ),
        )
        assertTrue(
            recreated.rotate(
                RemoteMcpOAuthDynamicRegistration(identity, "client-two", 2, null),
                expectedGeneration = 1,
            ),
        )
        assertEquals("client-two", recreated.find(identity, NOW)?.clientId)
        assertEquals(2L, recreated.find(identity, NOW)?.generation)
    }

    @Test
    fun changedRegistrationEndpointCannotOverwriteIssuerRedirectIdentity() {
        val root = tempRoot()
        val store = RemoteMcpOAuthDynamicRegistrationStore(root, TestOAuthCipher())
        val first = RemoteMcpOAuthDynamicRegistration(
            registrationIdentity("a".repeat(64)),
            "client-one",
            1,
            null,
        )
        assertNotNull(store.storeIfAbsent(first))
        val changed = RemoteMcpOAuthDynamicRegistration(
            registrationIdentity("b".repeat(64)),
            "client-two",
            1,
            null,
        )
        assertNull(store.storeIfAbsent(changed))
        assertNull(store.find(changed.identity, NOW))
    }

    @Test
    fun tamperedRegistrationStoreFailsClosed() {
        val root = tempRoot()
        val store = RemoteMcpOAuthDynamicRegistrationStore(root, TestOAuthCipher())
        val value = RemoteMcpOAuthDynamicRegistration(
            registrationIdentity("a".repeat(64)),
            "client-one",
            1,
            null,
        )
        store.storeIfAbsent(value)
        val file = File(root, "clients-v1.json")
        val json = JSONObject(file.readText())
        json.put("ciphertext", json.getString("ciphertext").dropLast(3) + "AAA")
        file.writeText(json.toString())
        assertThrows(Exception::class.java) { store.find(value.identity, NOW) }
    }

    private fun store(root: File, cipher: RemoteMcpOAuthCipher, now: Long) =
        RemoteMcpOAuthChallengeStore(
            root,
            cipher,
            { now },
            RemoteMcpOAuthRandomSource { size -> ByteArray(size) { 7 } },
        )

    private fun challenge(now: Long): RemoteMcpOAuthPendingChallenge =
        RemoteMcpOAuthPendingChallenge(
            spec = RemoteMcpOAuthConnectionSpec(
                identity = RemoteMcpOAuthCredentialIdentity(
                    "tasks-plugin",
                    "tasks",
                    "1".repeat(64),
                    OAuthCredentialHandle("2".repeat(64)),
                ),
                resourceEndpoint = "https://mcp.example/v1",
                clientOptions = RemoteMcpOAuthClientOptions(),
            ),
            client = RemoteMcpOAuthResolvedClient(
                "public-client",
                RemoteMcpOAuthRedirectRoute(
                    REMOTE_MCP_OAUTH_CUSTOM_SCHEME_CALLBACK_URI,
                    RemoteMcpOAuthRedirectKind.PRIVATE_USE_SCHEME_FALLBACK,
                ),
                RemoteMcpOAuthClientRegistrationKind.PRE_REGISTERED,
                null,
            ),
            scopes = setOf("mcp:read"),
            issuer = "https://as.example/",
            authorizationEndpoint = "https://as.example/authorize",
            tokenEndpoint = "https://as.example/token",
            state = "a".repeat(43) + "." + "b".repeat(43),
            nonce = "c".repeat(43),
            verifier = "d".repeat(86),
            createdAtEpochMillis = now,
            expiresAtEpochMillis = now + 60_000,
            expectedCredentialGeneration = null,
            authorizationResponseIssuerRequired = true,
        )

    private fun registrationIdentity(digest: String) =
        RemoteMcpOAuthDynamicRegistrationIdentity(
            issuer = "https://as.example/",
            redirectUri = REMOTE_MCP_OAUTH_CUSTOM_SCHEME_CALLBACK_URI,
            registrationEndpointDigest = digest,
        )

    private fun tempRoot(): File = kotlin.io.path.createTempDirectory("hans-oauth-test").toFile()

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}

internal class TestOAuthCipher : RemoteMcpOAuthCipher {
    private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val counter = AtomicInteger(1)

    override fun encrypt(
        plaintext: ByteArray,
        authenticatedMetadata: ByteArray,
    ): RemoteMcpOAuthCipherEnvelope {
        val iv = ByteArray(12)
        val value = counter.getAndIncrement()
        iv[8] = (value ushr 24).toByte()
        iv[9] = (value ushr 16).toByte()
        iv[10] = (value ushr 8).toByte()
        iv[11] = value.toByte()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(authenticatedMetadata)
        return RemoteMcpOAuthCipherEnvelope(iv, cipher.doFinal(plaintext))
    }

    override fun decrypt(
        envelope: RemoteMcpOAuthCipherEnvelope,
        authenticatedMetadata: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.iv))
        cipher.updateAAD(authenticatedMetadata)
        return cipher.doFinal(envelope.ciphertext)
    }

    override fun deleteKey() = Unit
}
