package ai.hans.standard.mcp

import ai.hans.standard.plugins.runtime.OAuthCredentialHandle
import ai.hans.standard.plugins.runtime.RemoteMcpRequirement
import java.io.File
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidKeystoreRemoteMcpOAuthVaultTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun tokenRotationRemovalAndProcessRecreationNeverProjectPlaintext() {
        val cipher = TestAesGcmCipher()
        val identity = identity("a".repeat(64))
        val vault = vault(cipher)

        assertEquals(
            RemoteMcpOAuthMutationResult.STORED,
            vault.store(
                identity,
                "access-one-secret".toCharArray(),
                "refresh-one-secret".toCharArray(),
                NOW + 60_000,
            ),
        )
        assertEquals(OAuthCredentialState.AVAILABLE, vault.state(identity))
        assertEquals("access-one-secret", vault.withBearerToken(identity) { it })
        val stateText = File(temporaryFolder.root, "oauth-vault-v1.json").readText()
        assertFalse(stateText.contains("access-one-secret"))
        assertFalse(stateText.contains("refresh-one-secret"))

        val recreated = vault(cipher)
        assertEquals("access-one-secret", recreated.withBearerToken(identity) { it })
        assertEquals(
            RemoteMcpOAuthMutationResult.STALE_GENERATION,
            recreated.refresh(
                identity,
                99,
                "ignored-new-token".toCharArray(),
                null,
                NOW + 120_000,
            ),
        )
        assertEquals(
            RemoteMcpOAuthMutationResult.ROTATED,
            recreated.refresh(
                identity,
                1,
                "access-two-secret".toCharArray(),
                null,
                NOW + 120_000,
            ),
        )
        assertEquals("access-two-secret", recreated.withBearerToken(identity) { it })
        val status = recreated.status(identity)
        assertEquals(RemoteMcpOAuthVaultStatusState.AVAILABLE, status.state)
        assertEquals(2L, status.generation)
        assertFalse(status.toString().contains(identity.handle.value))
        assertFalse(status.toString().contains(identity.configurationDigest))
        assertFalse(status.toString().contains("secret"))

        assertEquals(
            RemoteMcpOAuthMutationResult.REMOVED,
            recreated.remove(identity, expectedGeneration = 2),
        )
        assertEquals(OAuthCredentialState.MISSING, recreated.state(identity))
        assertEquals(RemoteMcpOAuthVaultStatusState.MISSING, recreated.status(identity).state)
        assertEquals(1, cipher.deletedKeys)
    }

    @Test
    fun expiryAndStaleIdentityFailClosedWithoutDestroyingExactCredential() {
        var clock = NOW
        val cipher = TestAesGcmCipher()
        val identity = identity("b".repeat(64))
        val vault = AndroidKeystoreRemoteMcpOAuthVault(temporaryFolder.root, cipher) { clock }
        vault.store(identity, "expiring-access-token".toCharArray(), null, NOW + 10)

        clock += 11
        assertEquals(OAuthCredentialState.EXPIRED, vault.state(identity))
        val expired = assertThrows(RemoteMcpFailure::class.java) {
            vault.withBearerToken(identity) { it }
        }
        assertEquals("mcp_oauth_expired", expired.code)

        val stale = identity.copy(configurationDigest = "c".repeat(64))
        assertEquals(OAuthCredentialState.STALE_IDENTITY, vault.state(stale))
        val staleRead = assertThrows(RemoteMcpFailure::class.java) {
            vault.withBearerToken(stale) { it }
        }
        assertEquals("mcp_oauth_stale_identity", staleRead.code)
        assertEquals(RemoteMcpOAuthVaultStatusState.STALE_IDENTITY, vault.status(stale).state)
        assertEquals(
            RemoteMcpOAuthMutationResult.STALE_IDENTITY,
            vault.remove(stale, expectedGeneration = 1),
        )
        assertEquals(OAuthCredentialState.EXPIRED, vault.state(identity))
    }

    @Test
    fun sameOpaqueHandleCannotBeUsedByAnotherPluginOrServer() {
        val cipher = TestAesGcmCipher()
        val identity = identity("f".repeat(64))
        val vault = vault(cipher)
        vault.store(identity, "plugin-scoped-secret".toCharArray(), null, NOW + 60_000)

        val foreignPlugin = identity.copy(pluginId = "foreign-plugin")
        val foreignServer = identity.copy(serverId = "foreign-server")
        listOf(foreignPlugin, foreignServer).forEach { foreign ->
            assertEquals(OAuthCredentialState.STALE_IDENTITY, vault.state(foreign))
            val failure = assertThrows(RemoteMcpFailure::class.java) {
                vault.withBearerToken(foreign) { it }
            }
            assertEquals("mcp_oauth_stale_identity", failure.code)
        }

        assertEquals("plugin-scoped-secret", vault.withBearerToken(identity) { it })
    }

    @Test
    fun corruptOversizedAndSymlinkedVaultsAreNeverTreatedAsCredentials() {
        val cipher = TestAesGcmCipher()
        val identity = identity("d".repeat(64))
        val vault = vault(cipher)
        vault.store(identity, "tamper-evident-access".toCharArray(), null, NOW + 60_000)

        val file = File(temporaryFolder.root, "oauth-vault-v1.json")
        val root = JSONObject(file.readText())
        val credential = root.getJSONArray("credentials").getJSONObject(0)
        val ciphertext = credential.getString("ciphertext")
        val replacement = (if (ciphertext.first() == 'A') 'B' else 'A') + ciphertext.drop(1)
        credential.put("ciphertext", replacement)
        file.writeText(root.toString())
        assertEquals(RemoteMcpOAuthVaultStatusState.UNAVAILABLE, vault.status(identity).state)
        assertEquals(OAuthCredentialState.UNAVAILABLE, vault.state(identity))
        val tamperFailure = assertThrows(RemoteMcpFailure::class.java) {
            vault.withBearerToken(identity) { it }
        }
        assertEquals("mcp_oauth_vault_unavailable", tamperFailure.code)

        file.writeBytes(ByteArray(512 * 1024 + 1))
        assertEquals(RemoteMcpOAuthVaultStatusState.UNAVAILABLE, vault.status(identity).state)

        assertTrue(file.delete())
        val outside = temporaryFolder.newFile("outside-vault.json").apply { writeText("{}") }
        Files.createSymbolicLink(file.toPath(), outside.toPath())
        assertEquals(RemoteMcpOAuthVaultStatusState.UNAVAILABLE, vault.status(identity).state)
        assertEquals(OAuthCredentialState.UNAVAILABLE, vault.state(identity))
    }

    private fun vault(cipher: TestAesGcmCipher) =
        AndroidKeystoreRemoteMcpOAuthVault(temporaryFolder.root, cipher) { NOW }

    private fun identity(handle: String): RemoteMcpOAuthCredentialIdentity {
        val requirement = RemoteMcpRequirement(
            id = "tasks",
            endpoint = "https://mcp.example.com/v1",
            oauthHandle = OAuthCredentialHandle(handle),
            allowedTools = setOf("tasks/list"),
            required = true,
            requestTimeoutMillis = 10_000,
            maxResponseBytes = 128 * 1024,
        )
        return RemoteMcpOAuthCredentialIdentity(
            pluginId = "tasks-plugin",
            serverId = requirement.id,
            configurationDigest = RemoteMcpConfigurationIdentity.digest(requirement),
            handle = requireNotNull(requirement.oauthHandle),
        )
    }

    private class TestAesGcmCipher : RemoteMcpOAuthCipher {
        private var key: SecretKey? = generateKey()
        var deletedKeys = 0
            private set

        override fun encrypt(
            plaintext: ByteArray,
            authenticatedMetadata: ByteArray,
        ): RemoteMcpOAuthCipherEnvelope {
            val activeKey = key ?: generateKey().also { key = it }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, activeKey)
            cipher.updateAAD(authenticatedMetadata)
            return RemoteMcpOAuthCipherEnvelope(cipher.iv, cipher.doFinal(plaintext))
        }

        override fun decrypt(
            envelope: RemoteMcpOAuthCipherEnvelope,
            authenticatedMetadata: ByteArray,
        ): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                requireNotNull(key),
                GCMParameterSpec(128, envelope.iv),
            )
            cipher.updateAAD(authenticatedMetadata)
            return cipher.doFinal(envelope.ciphertext)
        }

        override fun deleteKey() {
            deletedKeys += 1
            key = null
        }

        private fun generateKey(): SecretKey =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
