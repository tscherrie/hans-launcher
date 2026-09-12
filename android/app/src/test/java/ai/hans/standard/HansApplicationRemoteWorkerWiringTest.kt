package ai.hans.standard

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Product-path guard: component tests alone do not prove that Hans publishes the remote route. */
class HansApplicationRemoteWorkerWiringTest {
    @Test
    fun productUsesEncryptedAuthenticatedRemoteWorkerComposition() {
        val source = source()
        val runtime = remoteRuntimeSource()

        assertTrue(source.contains("RemoteWorkerProductionRuntime.create("))
        assertTrue(source.contains("context = this"))
        assertTrue(source.contains("workspaceStore = workspaceStore"))
        assertTrue(source.contains("artifactStore = artifactStore"))
        assertTrue(source.contains("onPublicationChanged = ::publishRemoteWorkerToolContract"))
        assertTrue(source.contains("activateRemoteWorkerExplicitly("))
        assertTrue(source.contains("remoteWorkerRuntime.activateExplicitly("))
        assertTrue(runtime.contains("AppPrivateRemoteWorkerConfigurationStore(context)"))
        assertTrue(runtime.contains("AndroidKeystoreRemoteWorkAuthenticator()"))
        assertTrue(runtime.contains("OkHttpPinnedRemoteWorkerTransport("))
        assertTrue(runtime.contains("RemoteWorkJournal("))
        assertTrue(runtime.contains("context.applicationContext.noBackupFilesDir"))
    }

    @Test
    fun activeRouteIsInteractiveOnlyAndBindsPrivateRevisionToken() {
        val source = source()
        val contributors = source.substringAfter("private fun finalizedPluginToolContributors()")
            .substringBefore("private fun refreshFinalizedPluginToolContract()")

        assertTrue(contributors.contains("remoteWorkerRuntimeDelegate.isInitialized()"))
        assertTrue(contributors.contains("remoteWorkerRuntime.activePublication()"))
        assertTrue(contributors.contains("executor = active.contribution.executor"))
        assertTrue(contributors.contains("revisionToken = active.revisionToken"))
        assertTrue(contributors.contains("toRemoteWorkerPublicationDisposition()"))
    }

    private fun source(): String = listOf(
        File("src/main/java/ai/hans/standard/HansApplication.kt"),
        File("android/app/src/main/java/ai/hans/standard/HansApplication.kt"),
    ).first(File::isFile).readText()

    private fun remoteRuntimeSource(): String = listOf(
        File("src/main/java/ai/hans/standard/work/remote/RemoteWorkerProductionRuntime.kt"),
        File(
            "android/app/src/main/java/ai/hans/standard/work/remote/" +
                "RemoteWorkerProductionRuntime.kt",
        ),
    ).first(File::isFile).readText()
}
