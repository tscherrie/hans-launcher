package ai.hans.standard.plugins

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginRuntimeCompatibilityServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun standardCodexPluginIsNotForcedIntoHansRuntimeMetadata() {
        val fixture = fixture()

        assertTrue(
            fixture.service.evaluateForInstall(
                fixture.record,
                dependenciesResolved = false,
                resolvedEntrypointIds = emptySet(),
                availableCapabilityIds = emptySet(),
            ).maySendAppServerInstall(),
        )
    }

    @Test
    fun declaredRuntimeWaitsForDependenciesAndThenUsesEffectiveProbes() {
        val fixture = fixture(
            PluginRuntimeRequirements(
                pluginId = "sample",
                runtimes = listOf(
                    PluginRuntimeRequirement(
                        id = "python",
                        kind = PluginRuntimeKind.EMBEDDED_PYTHON,
                        placement = PluginRuntimePlacement.LOCAL,
                    ),
                ),
                entrypoints = listOf(
                    PluginEntrypointRequirement(
                        id = "main",
                        runtimeRequirementId = "python",
                        kind = PluginEntrypointKind.PYTHON_CALLABLE,
                        target = "main:run",
                    ),
                ),
                capabilities = listOf(PluginCapabilityRequirement("hans_workspace.read_text")),
            ),
        )

        assertFalse(
            fixture.service.evaluateForInstall(
                fixture.record,
                dependenciesResolved = false,
                resolvedEntrypointIds = emptySet(),
                availableCapabilityIds = emptySet(),
            ).maySendAppServerInstall(),
        )
        assertTrue(
            fixture.service.evaluateForInstall(
                fixture.record,
                dependenciesResolved = true,
                resolvedEntrypointIds = setOf("main"),
                availableCapabilityIds = setOf("hans_workspace.read_text"),
            ).maySendAppServerInstall(),
        )
    }

    @Test
    fun nodeRequirementIsRejectedEvenThoughCodeModeIsReady() {
        val fixture = fixture(
            PluginRuntimeRequirements(
                pluginId = "sample",
                runtimes = listOf(
                    PluginRuntimeRequirement(
                        id = "node",
                        kind = PluginRuntimeKind.NODE_JS,
                        placement = PluginRuntimePlacement.LOCAL,
                    ),
                ),
                entrypoints = emptyList(),
                capabilities = emptyList(),
            ),
        )

        assertFalse(
            fixture.service.evaluateForInstall(
                fixture.record,
                dependenciesResolved = true,
                resolvedEntrypointIds = emptySet(),
                availableCapabilityIds = emptySet(),
            ).maySendAppServerInstall(),
        )
    }

    private fun fixture(requirements: PluginRuntimeRequirements? = null): Fixture {
        val root = temporaryFolder.newFolder()
        val source = File(root, "sample").apply { mkdir() }
        requirements?.let {
            File(source, PluginRuntimeManifestLoader.MANIFEST_NAME)
                .writeBytes(PluginRuntimeRequirementsCodec.encode(it))
        }
        val card = PluginCard(
            handle = PluginHandle("1".repeat(64)),
            pluginId = "sample",
            name = "sample",
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
        val record = PluginWireRecord(
            locator = PluginLocator("sample", "sample", "local", "/private/marketplace.json"),
            card = card,
            availableVersion = "1.0",
            localVersion = null,
            localSourcePath = source.absolutePath,
        )
        val ready = PluginRuntimeEvidenceSource {
            PluginRuntimeEvidence(
                "1.0",
                PluginRuntimeAbi.ANDROID_ARM64_V8A,
                PluginRuntimeReadiness.READY,
            )
        }
        val registry = PluginRuntimeProbeRegistry(
            StandardAndroidRuntimeProbeRegistrations.create(
                localAbi = PluginRuntimeAbi.ANDROID_ARM64_V8A,
                codeMode = ready,
                python = ready,
                git = ready,
                http = ready,
                androidCapabilities = ready,
            ),
        )
        return Fixture(
            PluginRuntimeCompatibilityService(PluginRuntimeManifestLoader(listOf(root)), registry),
            record,
        )
    }

    private data class Fixture(
        val service: PluginRuntimeCompatibilityService,
        val record: PluginWireRecord,
    )
}
