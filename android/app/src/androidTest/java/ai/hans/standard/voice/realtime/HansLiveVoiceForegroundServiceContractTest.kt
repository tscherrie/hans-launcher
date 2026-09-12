package ai.hans.standard.voice.realtime

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HansLiveVoiceForegroundServiceContractTest {
    @Test
    fun serviceIsPrivateUnboundAndDeclaresBothAudioForegroundTypes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = ComponentName(context, HansLiveVoiceForegroundService::class.java)
        @Suppress("DEPRECATION")
        val info = context.packageManager.getServiceInfo(component, 0)

        assertFalse(info.exported)
        assertEquals(0, info.flags and ServiceInfo.FLAG_STOP_WITH_TASK)
        assertTrue(
            info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0,
        )
        assertTrue(
            info.foregroundServiceType and
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK != 0,
        )
        assertNull(HansLiveVoiceForegroundService().onBind(Intent()))
    }

    @Test
    fun manifestDeclaresNormalWakeLockPermissionForCapabilityProbedProximityBlanking() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )

        assertTrue(Manifest.permission.WAKE_LOCK in packageInfo.requestedPermissions.orEmpty())
    }

    @Test
    fun startAndStopIntentsAreExplicitAndPackageScoped() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val start = HansLiveVoiceForegroundService.intent(
            context,
            AndroidLiveVoiceRuntime.ACTION_START,
        )
        val stop = HansLiveVoiceForegroundService.intent(
            context,
            AndroidLiveVoiceRuntime.ACTION_STOP,
        )

        assertEquals(context.packageName, start.component?.packageName)
        assertEquals(HansLiveVoiceForegroundService::class.java.name, start.component?.className)
        assertEquals(AndroidLiveVoiceRuntime.ACTION_START, start.action)
        assertEquals(AndroidLiveVoiceRuntime.ACTION_STOP, stop.action)
    }
}
