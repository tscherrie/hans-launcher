package ai.hans.standard.phone.lifecycle.android

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.hans.standard.R
import ai.hans.standard.phone.lifecycle.HansActiveWorkForegroundObserver
import ai.hans.standard.phone.lifecycle.HansActiveWorkReason
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HansActiveWorkManifestContractTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    @Suppress("DEPRECATION")
    fun foregroundOwnerIsPrivateInMainProcessAndDeclaresOnlyItsDeclaredTypes() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, HansActiveWorkService::class.java),
            0,
        )

        assertFalse(info.exported)
        assertEquals(context.packageName, info.processName)
        assertNull(info.permission)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            info.foregroundServiceType,
        )
    }

    @Test
    @Suppress("DEPRECATION")
    fun appDeclaresBaseTypeSpecificAndNotificationPermissions() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()

        assertTrue(Manifest.permission.FOREGROUND_SERVICE in permissions)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC in permissions)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK in permissions)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE in permissions)
        assertTrue(Manifest.permission.CHANGE_NETWORK_STATE in permissions)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in permissions)
    }

    @Test
    fun reasonMappingUsesExactForegroundServiceTypeSet() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            HansActiveWorkService.foregroundServiceTypes(
                setOf(HansActiveWorkReason.CODEX_ACTIVE),
            ),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            HansActiveWorkService.foregroundServiceTypes(
                setOf(HansActiveWorkReason.SPEECH_ACTIVE),
            ),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            HansActiveWorkService.foregroundServiceTypes(
                setOf(HansActiveWorkReason.REMOTE_CONTROL),
            ),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            HansActiveWorkService.foregroundServiceTypes(HansActiveWorkReason.entries.toSet()),
        )
    }

    @Test
    fun notificationCopyIsGenericAndContainsNoUserContent() {
        assertEquals("Hans ist aktiv", context.getString(R.string.active_work_notification_title))
        listOf(
            R.string.active_work_codex,
            R.string.active_work_speech,
            R.string.active_work_codex_and_speech,
            R.string.remote_active_work_title,
            R.string.remote_active_work_description,
            R.string.remote_active_work_stop,
        ).forEach { resource ->
            val text = context.getString(resource)
            assertTrue(text.isNotBlank())
            assertFalse(text.contains("http", ignoreCase = true))
            assertFalse(text.contains("@"))
        }
        // This notification also covers preflight, before the App Server proves enable.
        assertEquals("Desktop-Fernzugriff", context.getString(R.string.remote_active_work_title))
        assertFalse(context.getString(R.string.remote_active_work_description).contains("ist freigegeben"))
        assertEquals("Fernzugriff beenden", context.getString(R.string.remote_active_work_stop))
    }

    @Test
    @Suppress("DEPRECATION")
    fun remoteStopReceiverIsPrivateAndIntentIsExplicit() {
        val component = ComponentName(context, HansActiveWorkRemoteStopReceiver::class.java)
        val info = context.packageManager.getReceiverInfo(component, 0)
        assertFalse(info.exported)
        assertEquals(context.packageName, info.processName)
        val intent = HansActiveWorkRemoteStopReceiver.intent(context)
        assertEquals(component, intent.component)
        assertEquals(HansActiveWorkRemoteStopReceiver.ACTION_STOP_REMOTE, intent.action)
        assertNull(intent.data)
        assertNull(intent.extras)
    }

    @Test
    @Suppress("DEPRECATION")
    fun remotePrerequisitesAreNormalPermissionsWithoutRuntimeGrant() {
        val permissions = buildList {
            add(Manifest.permission.CHANGE_NETWORK_STATE)
            if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE)
        }
        permissions.forEach { permission ->
            val info = context.packageManager.getPermissionInfo(permission, 0)
            assertEquals(PermissionInfo.PROTECTION_NORMAL, info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE)
            assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(permission))
        }
    }

    @Test
    fun foregroundObserverReplaysOnMainThreadAndCanBeRemoved() {
        val replayed = CountDownLatch(1)
        val onMain = AtomicBoolean(false)
        val subscription = HansActiveWorkOwner.addForegroundObserver(context, HansActiveWorkForegroundObserver {
            onMain.set(Looper.myLooper() == Looper.getMainLooper())
            replayed.countDown()
        })
        try {
            assertTrue(replayed.await(5, TimeUnit.SECONDS))
            assertTrue(onMain.get())
        } finally {
            subscription.close()
        }

        val lateDeliveries = AtomicInteger(0)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            HansActiveWorkOwner.addForegroundObserver(context, HansActiveWorkForegroundObserver {
                lateDeliveries.incrementAndGet()
            }).close()
        }
        instrumentation.waitForIdleSync()
        assertEquals(0, lateDeliveries.get())
    }
}
