package ai.hans.standard.plugins

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginRuntimeManifestLoaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun validPrivateLocalManifestIsCorrelatedAndDecoded() {
        val root = temporaryFolder.newFolder("private")
        val plugin = File(root, "sample").apply { mkdir() }
        val requirements = instructionRequirements("sample-plugin")
        File(plugin, PluginRuntimeManifestLoader.MANIFEST_NAME)
            .writeBytes(PluginRuntimeRequirementsCodec.encode(requirements))
        val result = PluginRuntimeManifestLoader(listOf(root)).load(
            record("sample-plugin", plugin),
        )

        assertTrue(result is PluginRuntimeManifestResult.Declared)
        assertEquals(
            requirements,
            (result as PluginRuntimeManifestResult.Declared).requirements,
        )
    }

    @Test
    fun absentManifestKeepsOrdinaryCodexPluginInstructionOnlyPathUntouched() {
        val root = temporaryFolder.newFolder("private")
        val plugin = File(root, "sample").apply { mkdir() }

        assertEquals(
            PluginRuntimeManifestResult.NotDeclared,
            PluginRuntimeManifestLoader(listOf(root)).load(record("sample-plugin", plugin)),
        )
    }

    @Test
    fun malformedMismatchAndBoundaryEscapeAreRejected() {
        val root = temporaryFolder.newFolder("private")
        val outside = temporaryFolder.newFolder("outside")
        val plugin = File(root, "sample").apply { mkdir() }
        File(plugin, PluginRuntimeManifestLoader.MANIFEST_NAME)
            .writeBytes(PluginRuntimeRequirementsCodec.encode(instructionRequirements("other")))

        assertTrue(
            PluginRuntimeManifestLoader(listOf(root)).load(record("sample-plugin", plugin))
                is PluginRuntimeManifestResult.Rejected,
        )
        assertTrue(
            PluginRuntimeManifestLoader(listOf(root)).load(record("sample-plugin", outside))
                is PluginRuntimeManifestResult.Rejected,
        )
    }

    @Test
    fun remotePluginsCannotSmuggleLocalRuntimeMetadata() {
        val root = temporaryFolder.newFolder("private")
        val card = card("sample-plugin").copy(sourceKind = PluginSourceKind.GIT)
        val result = PluginRuntimeManifestLoader(listOf(root)).load(
            PluginWireRecord(locator("sample-plugin"), card, null, null, root.absolutePath),
        )

        assertEquals(PluginRuntimeManifestResult.NotDeclared, result)
    }

    private fun instructionRequirements(pluginId: String) = PluginRuntimeRequirements(
        pluginId = pluginId,
        runtimes = listOf(
            PluginRuntimeRequirement(
                id = "instructions",
                kind = PluginRuntimeKind.INSTRUCTION_ONLY,
                placement = PluginRuntimePlacement.LOCAL,
            ),
        ),
        entrypoints = emptyList(),
        capabilities = emptyList(),
    )

    private fun record(pluginId: String, source: File) = PluginWireRecord(
        locator = locator(pluginId),
        card = card(pluginId),
        availableVersion = "1.0",
        localVersion = null,
        localSourcePath = source.absolutePath,
    )

    private fun locator(pluginId: String) = PluginLocator(
        pluginId = pluginId,
        pluginName = pluginId,
        marketplaceName = "local",
        marketplacePath = "/private/marketplace.json",
    )

    private fun card(pluginId: String) = PluginCard(
        handle = PluginHandle("0".repeat(64)),
        pluginId = pluginId,
        name = pluginId,
        displayName = null,
        shortDescription = null,
        marketplaceDisplayName = "Local",
        installed = false,
        enabled = true,
        availability = PluginAvailability.AVAILABLE,
        disabledReason = null,
        installPolicy = PluginInstallPolicy.AVAILABLE,
        authPolicy = PluginAuthPolicy.ON_USE,
        sourceKind = PluginSourceKind.LOCAL,
        logoUrl = null,
        logoDarkUrl = null,
        capabilities = emptyList(),
        featured = false,
    )
}
