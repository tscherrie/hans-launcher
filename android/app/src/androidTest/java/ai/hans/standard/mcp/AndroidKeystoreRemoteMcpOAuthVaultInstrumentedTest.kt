package ai.hans.standard.mcp

import android.content.Context
import ai.hans.standard.plugins.runtime.OAuthCredentialHandle
import ai.hans.standard.plugins.runtime.RemoteMcpRequirement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreRemoteMcpOAuthVaultInstrumentedTest {
    @Test
    fun nonExportableKeystoreEnvelopeSurvivesVaultRecreationAndRotates() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.noBackupFilesDir, "remote-mcp-oauth-instrumented-test")
        directory.deleteRecursively()
        check(directory.mkdirs())
        val cipher = AndroidKeystoreRemoteMcpOAuthCipher(TEST_KEY_ALIAS)
        runCatching(cipher::deleteKey)
        val requirement = RemoteMcpRequirement(
            id = "tasks",
            endpoint = "https://mcp.example.com/v1",
            oauthHandle = OAuthCredentialHandle("9".repeat(64)),
            allowedTools = setOf("tasks/list"),
            required = true,
            requestTimeoutMillis = 10_000,
            maxResponseBytes = 128 * 1024,
        )
        val identity = RemoteMcpOAuthCredentialIdentity(
            pluginId = "tasks-plugin",
            serverId = requirement.id,
            configurationDigest = RemoteMcpConfigurationIdentity.digest(requirement),
            handle = requireNotNull(requirement.oauthHandle),
        )
        try {
            val first = AndroidKeystoreRemoteMcpOAuthVault(directory, cipher) { NOW }
            assertEquals(
                RemoteMcpOAuthMutationResult.STORED,
                first.store(
                    identity,
                    "android-keystore-access-one".toCharArray(),
                    "android-keystore-refresh".toCharArray(),
                    NOW + 60_000,
                ),
            )
            val recreated = AndroidKeystoreRemoteMcpOAuthVault(
                directory,
                AndroidKeystoreRemoteMcpOAuthCipher(TEST_KEY_ALIAS),
            ) { NOW }
            assertEquals(
                "android-keystore-access-one",
                recreated.withBearerToken(identity) { it },
            )
            assertEquals(
                RemoteMcpOAuthMutationResult.ROTATED,
                recreated.refresh(
                    identity,
                    expectedGeneration = 1,
                    accessToken = "android-keystore-access-two".toCharArray(),
                    refreshToken = null,
                    expiresAtEpochMillis = NOW + 120_000,
                ),
            )
            assertEquals(
                "android-keystore-access-two",
                recreated.withBearerToken(identity) { it },
            )
            assertEquals(
                RemoteMcpOAuthMutationResult.REMOVED,
                recreated.remove(identity, expectedGeneration = 2),
            )
            assertEquals(OAuthCredentialState.MISSING, recreated.state(identity))
        } finally {
            runCatching(cipher::deleteKey)
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val TEST_KEY_ALIAS = "ai.hans.standard.remote-mcp-oauth.instrumented-test"
        const val NOW = 1_800_000_000_000L
    }
}
