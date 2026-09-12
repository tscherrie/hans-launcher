package ai.hans.standard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StandardEditionBoundaryAndroidTest {
    @Test
    fun installedStandardPackageDeclaresNoPrivilegedExtensionContract() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS,
        )

        assertTrue(
            packageInfo.requestedPermissions.orEmpty().none { permission ->
                permission.startsWith("ai.hans.root")
            },
        )
        assertTrue(
            runCatching { Class.forName("ai.hans.standard.root.RootAccessRuntime") }.isFailure,
        )
        assertTrue(
            runCatching { Class.forName("ai.hans.root.HansRootExtensionService") }.isFailure,
        )
        val developerInstructions = context.assets.open("hans/developer-instructions-standard.md")
            .bufferedReader()
            .use { it.readText() }
        val liveInstructions = context.assets.open("hans/live-voice-instructions.md")
            .bufferedReader()
            .use { it.readText() }
        assertFalse(developerInstructions.contains("Root Extension"))
        assertFalse(developerInstructions.contains("android_root"))
        assertFalse(liveInstructions.contains("Root Extension"))
        assertFalse(liveInstructions.contains("android_root"))
        listOf("/system/bin/su", "/system/xbin/su", "su -c").forEach { marker ->
            assertFalse(developerInstructions.contains(marker))
            assertFalse(liveInstructions.contains(marker))
        }
    }
}
