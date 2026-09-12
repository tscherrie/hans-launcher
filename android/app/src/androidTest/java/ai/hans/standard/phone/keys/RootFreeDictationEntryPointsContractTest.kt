package ai.hans.standard.phone.keys

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.quicksettings.TileService
import ai.hans.standard.LauncherActivity
import ai.hans.standard.phone.platform.AndroidPlatformCompatibilityProbeActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootFreeDictationEntryPointsContractTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun quickSettingsTileIsSystemBoundAndTranslucentEntryIsAppPrivate() {
        val packageManager = context.packageManager
        val tileComponent = ComponentName(context, HansDictationTileService::class.java)
        val tileInfo = packageManager.getServiceInfo(tileComponent, PackageManager.GET_META_DATA)
        assertTrue(tileInfo.exported)
        assertEquals(Manifest.permission.BIND_QUICK_SETTINGS_TILE, tileInfo.permission)
        val tileMatches = packageManager.queryIntentServices(
            Intent(TileService.ACTION_QS_TILE).setPackage(context.packageName),
            0,
        )
        assertTrue(
            tileMatches.any {
                ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) == tileComponent
            },
        )

        val activityInfo = packageManager.getActivityInfo(
            ComponentName(context, RootFreeDictationEntryActivity::class.java),
            0,
        )
        assertFalse(activityInfo.exported)
        assertTrue(activityInfo.flags and ActivityInfo.FLAG_NO_HISTORY != 0)
        assertTrue(activityInfo.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activityInfo.screenOrientation)
        val launcherInfo = packageManager.getActivityInfo(
            ComponentName(context, LauncherActivity::class.java),
            0,
        )
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, launcherInfo.screenOrientation)
    }

    @Test
    fun translucentEntryActuallyLaunchesFromLandscapeWithoutAnOrientationConflict() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = context.applicationContext as Application
        val bridgeCreated = CountDownLatch(1)
        val launchFailure = AtomicReference<Throwable?>()
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity is RootFreeDictationEntryActivity) bridgeCreated.countDown()
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(callbacks) }
        try {
            ActivityScenario.launch(AndroidPlatformCompatibilityProbeActivity::class.java).use { scenario ->
                awaitLandscape(scenario)
                scenario.onActivity { host ->
                    assertEquals(
                        Configuration.ORIENTATION_LANDSCAPE,
                        host.resources.configuration.orientation,
                    )
                    try {
                        // A missing token makes the private bridge finish immediately without
                        // touching microphone permission, while still exercising Activity launch.
                        host.startActivity(Intent(host, RootFreeDictationEntryActivity::class.java))
                    } catch (failure: Throwable) {
                        launchFailure.set(failure)
                    }
                }

                assertTrue(
                    "The translucent dictation bridge never reached onCreate from landscape",
                    bridgeCreated.await(5, TimeUnit.SECONDS),
                )
                instrumentation.waitForIdleSync()
                assertNull(launchFailure.get())
                scenario.onActivity { host ->
                    assertFalse(host.isFinishing)
                    assertEquals(
                        Configuration.ORIENTATION_LANDSCAPE,
                        host.resources.configuration.orientation,
                    )
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                application.unregisterActivityLifecycleCallbacks(callbacks)
            }
        }
    }

    private fun awaitLandscape(
        scenario: ActivityScenario<AndroidPlatformCompatibilityProbeActivity>,
    ) {
        val landscape = CountDownLatch(1)
        scenario.onActivity { activity ->
            if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                landscape.countDown()
            }
        }
        assertTrue("Landscape compatibility host did not rotate", landscape.await(5, TimeUnit.SECONDS))
    }
}
