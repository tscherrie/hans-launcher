package ai.hans.standard.plugins.install

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositePluginDependencyRecoveryTest {
    @Test
    fun compositeDescriptorIsCanonicalSecretFreeAndRoundTrips() {
        val descriptor = PluginDependencyRecoveryDescriptor.composite(
            listOf(
                remote("z-server", "mcp-z"),
                surface(),
                python(),
                remote("a-server", "mcp-a"),
            ),
        )
        assertEquals(
            listOf("python", "surface", "a-server", "z-server"),
            descriptor.components.map(PluginDependencyRecoveryComponent::componentId),
        )

        val encoded = PluginInstallJournalCodec.encode(listOf(entry(descriptor)))
        assertEquals(listOf(entry(descriptor)), PluginInstallJournalCodec.decode(encoded))

        val dependency = JSONObject(encoded)
            .getJSONArray("entries")
            .getJSONObject(0)
            .getJSONObject("dependencyRecovery")
        val dependencyDocument = dependency.toString()
        assertFalse(dependencyDocument.contains("endpoint", ignoreCase = true))
        assertFalse(dependencyDocument.contains("token", ignoreCase = true))
        assertFalse(dependencyDocument.contains("oauth", ignoreCase = true))
        assertFalse(dependencyDocument.contains("path", ignoreCase = true))
        assertFalse(dependencyDocument.contains("schema", ignoreCase = true))
        assertEquals(setOf("kind", "components"), dependency.keys().asSequence().toSet())
        repeat(dependency.getJSONArray("components").length()) { index ->
            assertEquals(
                setOf(
                    "kind",
                    "componentId",
                    "transactionId",
                    "stateDigest",
                    "secondaryTransactionId",
                    "secondaryStateDigest",
                ),
                dependency.getJSONArray("components").getJSONObject(index)
                    .keys().asSequence().toSet(),
            )
        }
    }

    @Test
    fun legacyPythonSchemaDecodesWithoutMigration() {
        val legacy = pythonDescriptor()
        val encoded = PluginInstallJournalCodec.encode(listOf(entry(legacy)))
        val dependency = JSONObject(encoded)
            .getJSONArray("entries")
            .getJSONObject(0)
            .getJSONObject("dependencyRecovery")
        assertEquals(
            setOf(
                "kind",
                "environmentTransactionId",
                "environmentDigest",
                "entrypointTransactionId",
                "entrypointMetadataDigest",
            ),
            dependency.keys().asSequence().toSet(),
        )
        assertEquals(legacy, PluginInstallJournalCodec.decode(encoded).single().dependencyRecovery)
        assertEquals(listOf(python()), legacy.asComponents())
    }

    @Test
    fun componentBoundsDuplicatesAndNonCanonicalDocumentsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            PluginDependencyRecoveryDescriptor.composite(
                (0..PluginInstallJournalBounds.MAX_DEPENDENCY_COMPONENTS).map { index ->
                    remote("mcp-$index", "tx-$index")
                },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginDependencyRecoveryDescriptor.composite(
                (0..PluginInstallJournalBounds.MAX_REMOTE_MCP_DEPENDENCY_COMPONENTS).map { index ->
                    remote("server-$index", "server-tx-$index")
                },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginDependencyRecoveryDescriptor.composite(listOf(surface(), surface()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginDependencyRecoveryDescriptor(
                kind = PluginDependencyRecoveryKind.COMPOSITE_V1,
                components = listOf(remote("later", "mcp-2"), python()),
            )
        }

        val valid = PluginInstallJournalCodec.encode(
            listOf(entry(PluginDependencyRecoveryDescriptor.composite(listOf(python(), surface())))),
        )
        val injectedSecret = JSONObject(valid).also { root ->
            root.getJSONArray("entries").getJSONObject(0)
                .getJSONObject("dependencyRecovery")
                .getJSONArray("components").getJSONObject(0)
                .put("endpoint", "https://example.invalid")
        }.toString()
        assertThrows(IllegalArgumentException::class.java) {
            PluginInstallJournalCodec.decode(injectedSecret)
        }
    }

    @Test
    fun componentIdentityShapesAreStrictlyBounded() {
        assertThrows(IllegalArgumentException::class.java) {
            remote("x".repeat(PluginInstallJournalBounds.MAX_ID_CHARS + 1), "tx")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginDependencyRecoveryComponent(
                kind = PluginDependencyRecoveryComponentKind.REMOTE_MCP_V1,
                componentId = "server",
                transactionId = "tx",
                stateDigest = "not-a-digest",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginDependencyRecoveryComponent(
                kind = PluginDependencyRecoveryComponentKind.PLUGIN_SURFACE_V1,
                componentId = "renamed-surface",
                transactionId = "surface-tx",
                stateDigest = "b".repeat(64),
            )
        }
        assertTrue(
            PluginDependencyRecoveryDescriptor.composite(listOf(remote("server", "tx")))
                .components.single().secondaryTransactionId == null,
        )
    }

    private fun pythonDescriptor() = PluginDependencyRecoveryDescriptor(
        kind = PluginDependencyRecoveryKind.PYTHON_ENVIRONMENT_V1,
        environmentTransactionId = "python-environment",
        environmentDigest = "a".repeat(64),
        entrypointTransactionId = "python-entrypoints",
        entrypointMetadataDigest = "b".repeat(64),
    )

    private fun python() = pythonDescriptor().asComponents().single()

    private fun surface() = PluginDependencyRecoveryComponent(
        kind = PluginDependencyRecoveryComponentKind.PLUGIN_SURFACE_V1,
        componentId = PluginDependencyRecoveryComponent.PLUGIN_SURFACE_COMPONENT_ID,
        transactionId = "surface-transaction",
        stateDigest = "c".repeat(64),
    )

    private fun remote(serverId: String, transactionId: String) =
        PluginDependencyRecoveryComponent(
            kind = PluginDependencyRecoveryComponentKind.REMOTE_MCP_V1,
            componentId = serverId,
            transactionId = transactionId,
            stateDigest = "d".repeat(64),
        )

    private fun entry(descriptor: PluginDependencyRecoveryDescriptor) =
        PluginInstallJournalEntry(
            operationId = "operation-composite",
            leaseId = "lease-composite",
            revision = 1,
            ownerProcessEpoch = "process-composite",
            phase = PluginInstallJournalPhase.LOCAL_PREPARED,
            identity = PluginInstallIdentity(
                pluginId = "garage",
                pluginHandleSha256 = "e".repeat(64),
                pluginName = "garage-plugin",
                marketplaceName = "personal",
                marketplacePath = null,
                expectedInstalledVersion = "1.0.0",
                canonicalSourceRoot = "/data/user/0/ai.hans.standard/plugins/garage",
                sourceSha256 = "f".repeat(64),
            ),
            installAttempt = PluginInstallAttempt("attempt-composite", 1),
            dependencyRecovery = descriptor,
            timestamps = PluginInstallJournalTimestamps(
                createdAtWallEpochMillis = 1,
                updatedAtWallEpochMillis = 1,
                createdAtElapsedRealtimeMillis = 1,
                updatedAtElapsedRealtimeMillis = 1,
            ),
        )
}
