package ai.hans.standard.plugins.uninstall

import ai.hans.standard.mcp.RemoteMcpDiscoveryCatalogStore
import ai.hans.standard.mcp.RemoteMcpSessionRegistry
import ai.hans.standard.plugins.runtime.PluginSurfaceActivationStore
import ai.hans.standard.runtime.python.PythonPluginEntrypointActivation
import ai.hans.standard.runtime.python.PythonPluginEntrypointDeclaration
import ai.hans.standard.runtime.python.PythonPluginEntrypointFinalizedSnapshot
import ai.hans.standard.runtime.python.PythonPluginEntrypointRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CompositePluginRuntimeUninstallManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun exactPythonDescriptorIsRemovedAndCrashReplayAcceptsOnlyItsAbsence() {
        val fixture = fixture()
        fixture.finalizePython()
        val descriptor = (fixture.manager.snapshot(PLUGIN_ID) as
            PluginRuntimeUninstallSnapshot.Exact).descriptor

        assertEquals(
            PluginRuntimeUninstallDeactivationResult.EXACT,
            fixture.manager.deactivateExact(descriptor, allowAlreadyAbsent = false),
        )
        assertEquals(
            PythonPluginEntrypointFinalizedSnapshot.Absent,
            fixture.python.finalizedSnapshot(PLUGIN_ID),
        )
        assertEquals(
            PluginRuntimeUninstallDeactivationResult.EXACT,
            fixture.manager.deactivateExact(descriptor, allowAlreadyAbsent = true),
        )
    }

    @Test
    fun stalePythonMetadataFailsClosedWithoutRemovingFinalizedEntrypoint() {
        val fixture = fixture()
        fixture.finalizePython()
        val exact = (fixture.manager.snapshot(PLUGIN_ID) as
            PluginRuntimeUninstallSnapshot.Exact).descriptor
        val component = exact.components.single()
        val stale = PluginRuntimeUninstallDescriptor.canonical(
            PLUGIN_ID,
            listOf(component.copy(stateSha256 = "f".repeat(64))),
        )

        assertEquals(
            PluginRuntimeUninstallDeactivationResult.CHANGED,
            fixture.manager.deactivateExact(stale, allowAlreadyAbsent = false),
        )
        assertTrue(
            fixture.python.finalizedSnapshot(PLUGIN_ID) is
                PythonPluginEntrypointFinalizedSnapshot.Present,
        )
    }

    @Test
    fun pendingPythonActivationMakesUninstallSnapshotUnusable() {
        val fixture = fixture()
        fixture.python.prepareActivation(activation())

        assertEquals(PluginRuntimeUninstallSnapshot.Changed, fixture.manager.snapshot(PLUGIN_ID))
    }

    private fun fixture(): Fixture {
        val root = temporaryFolder.newFolder("fixture-${System.nanoTime()}")
        val python = PythonPluginEntrypointRegistry(File(root, "python/registry.json"))
        val remote = RemoteMcpSessionRegistry(File(root, "mcp-activations")) {
            error("Uninstall snapshot must not open a Remote MCP session")
        }
        return Fixture(
            python = python,
            manager = CompositePluginRuntimeUninstallManager(
                python = python,
                surfaces = PluginSurfaceActivationStore(File(root, "surfaces")),
                remoteCatalogs = RemoteMcpDiscoveryCatalogStore(File(root, "mcp-catalogs")),
                remoteActivations = remote,
            ),
        )
    }

    private data class Fixture(
        val python: PythonPluginEntrypointRegistry,
        val manager: CompositePluginRuntimeUninstallManager,
    ) {
        fun finalizePython() {
            val activation = activation()
            val receipt = python.prepareActivation(activation)
            python.commitActivation(receipt, ENVIRONMENT, SOURCE)
            python.finalizeActivation(receipt)
        }
    }

    private companion object {
        const val PLUGIN_ID = "garage"
        val ENVIRONMENT = "a".repeat(64)
        val SOURCE = "b".repeat(64)

        fun activation() = PythonPluginEntrypointActivation(
            pluginId = PLUGIN_ID,
            environmentDigest = ENVIRONMENT,
            sourceSha256 = SOURCE,
            declarations = listOf(
                PythonPluginEntrypointDeclaration(
                    entrypointId = "open-door",
                    relativePath = "garage/actions.py",
                    function = "run",
                    sourceSha256 = SOURCE,
                ),
            ),
            provenEntrypointIds = setOf("open-door"),
        )
    }
}
