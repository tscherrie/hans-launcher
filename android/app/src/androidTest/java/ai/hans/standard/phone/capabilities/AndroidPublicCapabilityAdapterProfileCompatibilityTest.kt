package ai.hans.standard.phone.capabilities

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidPublicCapabilityAdapterProfileCompatibilityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun currentUserCatalogRemainsAvailableOnPrePrivateSpaceAndroid() {
        val result = AndroidPublicCapabilityAdapter(context).listLaunchableAppCatalog()
        assertTrue(result is AndroidAdapterResult.Success)
        val catalog = (result as AndroidAdapterResult.Success).value
        val personal = catalog.profiles.single {
            it.profileId == PERSONAL_LAUNCH_PROFILE_ID
        }

        assertEquals(LaunchProfileType.PERSONAL, personal.type)
        assertFalse(personal.locked)
        if (Build.VERSION.SDK_INT < 35) {
            assertTrue(catalog.apps.all { it.profileType != LaunchProfileType.PRIVATE })
        }
    }

    @Test
    fun personalLaunchUsesPackageManagersCanonicalFrontDoor() {
        val expected = requireNotNull(
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.component,
        )

        val result = AndroidPublicCapabilityAdapter(context).launchAppInProfile(
            packageName = context.packageName,
            profileId = PERSONAL_LAUNCH_PROFILE_ID,
        )

        assertTrue(result is AndroidAdapterResult.Success)
        val observation = (result as AndroidAdapterResult.Success).value
        assertEquals(expected.packageName, observation.targetPackage)
        assertEquals(expected.flattenToString(), observation.targetComponent)
    }

    @Test
    fun manifestRequestsNormalHiddenProfileAccessWithoutRuntimeGrantFlow() {
        val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS,
            )
        }

        assertTrue(
            packageInfo.requestedPermissions.orEmpty()
                .contains("android.permission.ACCESS_HIDDEN_PROFILES"),
        )
    }
}
