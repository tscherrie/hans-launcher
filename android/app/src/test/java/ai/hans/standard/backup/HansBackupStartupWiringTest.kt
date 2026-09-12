package ai.hans.standard.backup

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source wiring complements the executable transaction/startup tests, not a physical UI claim. */
class HansBackupStartupWiringTest {
    @Test
    fun applicationRecoversBeforeInstallingTheOwnerAndBackupReadsOnlyTheCachedCatalogue() {
        val source = source("HansApplication.kt")
        val startup = source.substringAfter("override fun onCreate()").substringBefore("/** Explicit UI retry")
        assertTrue(startup.contains("startRuntimeAfterBackupRecovery(gateway, ::startProductRuntimeAfterBackupRecovery)"))
        assertFalse(startup.contains("HansAutomationRuntime.install"))
        val gateway = source.substringAfter("private val backupGateway by lazy")
            .substringBefore("val backupCoordinator by lazy")
        assertTrue(gateway.contains("latestBackupPluginSnapshot.get()"))
        assertTrue(source.contains("latestBackupPluginSnapshot.set(snapshot.plugins)"))
        assertFalse(gateway.contains("passiveInitializedSessionHostSnapshot"))
        assertFalse(gateway.contains("sessionHost."))
        assertFalse(gateway.contains("automationRuntime"))
    }

    @Test
    fun recoveryOnlyLauncherBranchesBeforeAnySessionOrSetupConstructionAndGuardsLifecycle() {
        val source = source("LauncherActivity.kt")
        val startup = source.substringAfter("override fun onCreate(savedInstanceState: Bundle?)")
            .substringBefore("override fun onResume()")
        val fallback = startup.indexOf("showBackupRecoveryScreen(hansApplication)")
        assertTrue(fallback >= 0)
        assertTrue(fallback < startup.indexOf("setupRuntime = hansApplication.setupRuntime"))
        assertTrue(fallback < startup.indexOf("sessionHost = hansApplication.sessionHost"))
        listOf("onResume", "onStart", "onStop", "onPause", "onWindowFocusChanged", "onNewIntent", "onDestroy", "dispatchKeyEvent")
            .forEach { method ->
                val section = source.substringAfter("override fun $method(").substringBefore("\n    override fun ")
                assertTrue("$method must handle the recovery-only launcher", section.contains("backupRecoveryOnlyUi"))
            }
    }

    @Test
    fun savedActivityResultsAreIgnoredBeforeAccessingUninitializedRecoveryOnlyFields() {
        val source = source("LauncherActivity.kt")
        val guard = "if (backupRecoveryOnlyUi || !HansBackupProcessState.maintenance.isRecoveryReady)"
        mapOf(
            "cameraCaptureLauncher" to "cameraCaptureCoordinator.acceptResult",
            "videoCaptureLauncher" to "cameraCaptureCoordinator.acceptVideoResult",
            "microphonePermission" to "pendingSoftwareHoldPermission",
            "notificationPermission" to "refreshCapabilityAccess()",
            "publicPhonePermissions" to "refreshCapabilityAccess()",
            "backupCreateDocument" to "uri?.let(::exportBackupTo)",
            "backupOpenDocument" to "uri?.let(::prepareBackupImportFrom)",
        ).forEach { (name, firstNormalAccess) ->
            val callback = source.substringAfter("private val $name = registerForActivityResult(")
                .substringBefore("\n    private val ")
            val guardIndex = callback.indexOf(guard)
            assertTrue("$name must guard restored results", guardIndex >= 0)
            assertTrue("$name must guard before accessing ordinary activity state",
                guardIndex < callback.indexOf(firstNormalAccess))
            assertTrue(callback.substringAfter(guard).substringBefore("}")
                .contains("return@registerForActivityResult"))
        }
    }

    @Test
    fun recoveryFallbackNeverBootsCodexAndDoesNotDiscardTheJournal() {
        val source = source("backup/AndroidBackupRecoveryScreen.kt")
        assertFalse(source.contains("sessionHost"))
        assertFalse(source.contains("automationRuntime"))
        assertFalse(source.contains(".delete("))
        assertFalse(source.contains("postDelayed"))
        assertTrue(source.contains("onRetry"))
        assertTrue(source.contains("onAndroidSettings"))
        assertTrue(source.contains("onHomeSettings"))
    }

    private fun source(name: String): String = listOf(
        File("src/main/java/ai/hans/standard/$name"),
        File("android/app/src/main/java/ai/hans/standard/$name"),
    ).first(File::isFile).readText()
}
