package ai.hans.standard.plugins

import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRuntimeProbeRegistryTest {
    @Test
    fun codeModeAndNodeAreNeverConflated() {
        val registry = standardRegistry(
            codeMode = ready("0.115.0"),
            python = unavailable(),
        )

        val probes = registry.snapshot()
        val codeMode = probes.single { it.kind == PluginRuntimeKind.CODE_MODE_JAVASCRIPT }
        val node = probes.single { it.kind == PluginRuntimeKind.NODE_JS }

        assertEquals(PluginRuntimeReadiness.READY, codeMode.readiness)
        assertEquals("0.115.0", codeMode.version.toString())
        assertEquals(PluginRuntimeReadiness.UNAVAILABLE, node.readiness)
        assertEquals(null, node.version)
    }

    @Test
    fun scannerReportsNodeUnavailableEvenWhenV8IsReady() {
        val runtimes = standardRegistry(codeMode = ready("0.115.0"), python = unavailable())
            .snapshot()
        val requirements = PluginRuntimeRequirements(
            pluginId = "node-plugin",
            runtimes = listOf(
                PluginRuntimeRequirement(
                    id = "node",
                    kind = PluginRuntimeKind.NODE_JS,
                    placement = PluginRuntimePlacement.LOCAL,
                ),
            ),
            entrypoints = emptyList(),
            capabilities = emptyList(),
        )

        val result = PluginCompatibilityScanner().scan(
            requirements,
            PluginCompatibilityObservation(
                installed = true,
                dependenciesResolved = true,
                probesComplete = true,
                runtimes = runtimes,
            ),
        )

        assertEquals(PluginCompatibilityLifecycle.INCOMPATIBLE, result.lifecycle)
        assertEquals(
            listOf("blocking|runtime_unavailable|node|kind=node_js,placement=local"),
            result.diagnosticTexts,
        )
    }

    @Test
    fun evidenceIsPassiveAndReflectsOnlyPublishedEffectiveState() {
        val state = AtomicReference(unavailable())
        var reads = 0
        val source = PluginRuntimeEvidenceSource {
            reads += 1
            state.get().current()
        }
        val registry = PluginRuntimeProbeRegistry(
            listOf(
                PluginRuntimeProbeRegistration(
                    PluginRuntimeKind.EMBEDDED_PYTHON,
                    PluginRuntimeLocation.LOCAL,
                    source,
                ),
            ),
        )

        assertEquals(PluginRuntimeReadiness.UNAVAILABLE, registry.snapshot().single().readiness)
        state.set(ready("3.14.7"))
        assertEquals(PluginRuntimeReadiness.READY, registry.snapshot().single().readiness)
        assertEquals(2, reads)
    }

    @Test
    fun duplicateOwnersAndReadyWithoutVersionAreRejected() {
        val registration = PluginRuntimeProbeRegistration(
            PluginRuntimeKind.GIT,
            PluginRuntimeLocation.LOCAL,
            ready("7.7.1"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PluginRuntimeProbeRegistry(listOf(registration, registration))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginRuntimeEvidence(
                version = null,
                abi = PluginRuntimeAbi.PLATFORM_INDEPENDENT,
                readiness = PluginRuntimeReadiness.READY,
            )
        }
    }

    @Test
    fun remoteHttpMcpIsAdvertisedOnlyWhenAConcreteOwnerIsConfigured() {
        val absent = standardRegistry(ready("0.115.0"), ready("3.14.7")).snapshot()
        assertTrue(absent.none { it.kind == PluginRuntimeKind.MCP_REMOTE_HTTP })

        val configured = PluginRuntimeProbeRegistry(
            StandardAndroidRuntimeProbeRegistrations.create(
                localAbi = PluginRuntimeAbi.ANDROID_ARM64_V8A,
                codeMode = ready("0.115.0"),
                python = ready("3.14.7"),
                git = ready("7.7.1"),
                http = ready("1.0"),
                androidCapabilities = ready("1.0"),
                remoteHttpMcp = ready("1.0", PluginRuntimeAbi.PLATFORM_INDEPENDENT),
            ),
        ).snapshot()
        val remote = configured.single { it.kind == PluginRuntimeKind.MCP_REMOTE_HTTP }
        assertEquals(PluginRuntimeLocation.REMOTE, remote.location)
        assertEquals(PluginRuntimeReadiness.READY, remote.readiness)
    }

    private fun standardRegistry(
        codeMode: PluginRuntimeEvidenceSource,
        python: PluginRuntimeEvidenceSource,
    ) = PluginRuntimeProbeRegistry(
        StandardAndroidRuntimeProbeRegistrations.create(
            localAbi = PluginRuntimeAbi.ANDROID_ARM64_V8A,
            codeMode = codeMode,
            python = python,
            git = ready("7.7.1"),
            http = ready("1.0"),
            androidCapabilities = ready("1.0"),
        ),
    )

    private fun ready(
        version: String,
        abi: PluginRuntimeAbi = PluginRuntimeAbi.ANDROID_ARM64_V8A,
    ) = PluginRuntimeEvidenceSource {
        PluginRuntimeEvidence(version, abi, PluginRuntimeReadiness.READY)
    }

    private fun unavailable(
        abi: PluginRuntimeAbi = PluginRuntimeAbi.ANDROID_ARM64_V8A,
    ) = PluginRuntimeProbeRegistry.unavailable(abi)
}
