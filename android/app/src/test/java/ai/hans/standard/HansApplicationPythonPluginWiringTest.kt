package ai.hans.standard

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the product path: component tests are insufficient if Hans never wires them together. */
class HansApplicationPythonPluginWiringTest {
    @Test
    fun productUsesResolverAwareDescriptorsAndTransactionalPluginInstallation() {
        val source = source()

        assertTrue(source.contains("ResolverAwarePythonEnvironmentArchiveProvider("))
        assertTrue(source.contains("environmentArchiveProvider = pythonArchiveProvider"))
        assertTrue(source.contains("PythonResolverEngine("))
        assertTrue(source.contains("worker = IsolatedPythonResolverWorker(pythonRuntime, pythonResolverArchives)"))
        assertTrue(source.contains("catalog = SignedNativeFirstPythonPackageCatalog("))
        assertTrue(source.contains("nativeCatalog = pythonNativePackageCatalog"))
        assertTrue(source.contains("purePythonFallback = PyPiSimpleJsonCatalog(pythonResolverHttp)"))
        assertTrue(source.contains("SecurePyPiWheelDownloader(pythonResolverHttp)"))
        assertTrue(source.contains("entrypoints = PythonRuntimePluginEntrypointProber(pythonRuntime)"))
        assertTrue(source.contains("PythonPluginEntrypointRegistry("))
        assertTrue(source.contains("\"python/plugin-entrypoints/registry-v1.json\""))
        assertTrue(source.contains("entrypointRegistry = pythonPluginEntrypointRegistry"))
        assertTrue(source.contains("pluginEntrypointResolver = pythonPluginEntrypointRegistry"))
        assertTrue(source.contains("CompositePluginRuntimeDependencyPreparer("))
        assertTrue(source.contains("CompositePluginRuntimeDependencyRecoveryProvider("))
        assertTrue(source.contains("dependencies = compositePluginDependencyPreparer"))
        assertTrue(source.contains("runtimeRemoval = pythonPluginRuntimeDependencyPreparer"))
        assertTrue(source.contains("pluginInstallTransactions = pluginInstallTransactions"))
        assertTrue(source.contains("pluginPreparationExecutor = pluginPreparationExecutor"))
        assertTrue(source.contains("pluginSurfaceEvidenceStager = pluginSurfaceEvidenceStager"))
        assertTrue(source.contains("RemoteMcpPluginRuntimeDependencyPreparer("))
        assertTrue(source.contains("RemoteMcpFinalizedDynamicToolSource("))
    }

    @Test
    fun runtimePluginSourcesRemainInsideDedicatedAppPrivateRoots() {
        val source = source()
        val roots = source.substringAfter("private val privatePluginSourceRoots by lazy")
            .substringBefore("private val pluginInstallTransactions by lazy")

        assertTrue(roots.contains("BundledSetupPluginContract.marketplaceRootForFilesDirectory(filesDir)"))
        assertTrue(roots.contains("java.io.File(noBackupFilesDir, \"codex/home/plugins\")"))
        assertTrue(roots.contains("java.io.File(noBackupFilesDir, \"hans-plugin-sources\")"))
        assertTrue(roots.contains("!java.nio.file.Files.isSymbolicLink"))
        assertTrue(roots.contains("directory.canonicalFile"))
    }

    @Test
    fun browserAndDocumentAdaptersAreAvailableToCodexAndIsolatedPython() {
        val source = source()

        assertTrue(source.contains("BrowserDynamicToolExecutor("))
        assertTrue(source.contains("DocumentArtifactAdapter(artifactStore)"))
        assertTrue(source.contains("DocumentDynamicToolExecutor("))
        assertTrue(source.windowed("browserDynamicTools".length)
            .count { it == "browserDynamicTools" } >= 3)
        assertTrue(source.windowed("documentDynamicTools".length)
            .count { it == "documentDynamicTools" } >= 3)
    }

    private fun source(): String = listOf(
        File("src/main/java/ai/hans/standard/HansApplication.kt"),
        File("android/app/src/main/java/ai/hans/standard/HansApplication.kt"),
    ).first(File::isFile).readText()
}
