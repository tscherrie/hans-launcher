package ai.hans.standard.phone.lifecycle.android

import ai.hans.standard.BuildConfig
import ai.hans.standard.R
import ai.hans.standard.phone.lifecycle.HansActiveWorkForegroundEvent
import ai.hans.standard.phone.lifecycle.HansActiveWorkForegroundObserver
import ai.hans.standard.phone.lifecycle.HansActiveWorkForegroundSnapshot
import ai.hans.standard.phone.lifecycle.HansActiveWorkReason
import ai.hans.standard.phone.lifecycle.HansActiveWorkUpdateStatus
import ai.hans.standard.ui.acceptance.ChatScreenIdleProbeActivity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real FGS lifecycle on disposable emulators only; no account, RPC, microphone or grants. */
@RunWith(AndroidJUnit4::class)
class RemoteControlForegroundLifecycleTest {
    @Test fun visibleRemoteOwnerPromotesAndNotificationStopRetainsProtectionUntilAcknowledgedRelease() {
        assertTrue("Only the debug APK on a disposable Android emulator may run this lifecycle test",
            BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertTrue(Build.VERSION.SDK_INT in 31..36)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val remote = HansActiveWorkReason.REMOTE_CONTROL
        val initial = HansActiveWorkOwner.foregroundSnapshot()
        assertTrue("A pre-existing active-work owner is not this test's property",
            HansActiveWorkOwner.activeReasons(context).isEmpty())
        assertTrue(initial.protectedReasons.isEmpty())
        assertNull("A pre-existing remote stop token must not become this test's proof", existingStop(context))

        val callbacks = ArrayBlockingQueue<HansActiveWorkForegroundSnapshot>(32)
        val callbackFault = AtomicBoolean(false)
        val subscription = HansActiveWorkOwner.addForegroundObserver(context, HansActiveWorkForegroundObserver {
            if (Looper.myLooper() != Looper.getMainLooper() || !callbacks.offer(it)) callbackFault.set(true)
        })
        var scenario: ActivityScenario<ChatScreenIdleProbeActivity>? = null
        var acquired = false
        var released = false
        try {
            // This existing debug host renders only immutable fake chat state. Unlike the real
            // LauncherActivity it never requests the lazy Codex host, a login or remote RPC.
            val intent = Intent(context, ChatScreenIdleProbeActivity::class.java)
                .putExtra(ChatScreenIdleProbeActivity.EXTRA_RUN_ID, UUID.randomUUID().toString())
            val activeScenario = ActivityScenario.launch<ChatScreenIdleProbeActivity>(intent)
            scenario = activeScenario
            var revision = 0L
            activeScenario.onActivity { activity ->
                assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
                val update = HansActiveWorkOwner.acquireRemoteControlFromVisibleActivity(activity)
                assertEquals(HansActiveWorkUpdateStatus.APPLIED, update.status)
                acquired = true
                revision = update.revision
                assertEquals(setOf(remote), update.activeReasons)
            }
            val protected = awaitSnapshot(callbacks, callbackFault) {
                it.sequence > initial.sequence && it.revision == revision &&
                    it.event == HansActiveWorkForegroundEvent.PROTECTED && it.protectedReasons == setOf(remote)
            }
            // PROTECTED is emitted only after the real Service.startForeground returned and
            // the service reconciled this exact owner revision. This is not a dispatch receipt.
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                HansActiveWorkService.foregroundServiceTypes(protected.protectedReasons))

            val stop = existingStop(context)
            assertNotNull("Real service promotion must create the notification stop PendingIntent", stop)
            checkNotNull(stop)
            assertEquals(context.packageName, stop.creatorPackage)
            assertTrue(stop.isBroadcast)
            assertTrue(stop.isImmutable)
            val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
            if (HansActiveWorkOwner.notificationVisibility(context) ==
                HansActiveWorkNotificationVisibility.DRAWER_AND_TASK_MANAGER) {
                val notification = awaitNotification(manager, present = true)
                assertNotNull(notification)
                checkNotNull(notification)
                assertTrue(notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
                assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
                val action = notification.actions.single()
                assertEquals(context.getString(R.string.remote_active_work_stop), action.title.toString())
                assertEquals(stop, action.actionIntent)
            } else {
                // Android 13+ permits FGS with notifications denied; the drawer is not a
                // reliable evidence surface. Never grant POST_NOTIFICATIONS for this test.
                assertTrue(Build.VERSION.SDK_INT >= 33)
                assertEquals(HansActiveWorkNotificationVisibility.TASK_MANAGER_ONLY,
                    HansActiveWorkOwner.notificationVisibility(context))
            }

            stop.send()
            val requested = awaitSnapshot(callbacks, callbackFault) {
                it.remoteStopRequestSequence == protected.remoteStopRequestSequence + 1
            }
            assertEquals(HansActiveWorkForegroundEvent.PROTECTED, requested.event)
            assertEquals(revision, requested.revision)
            assertEquals(setOf(remote), requested.protectedReasons)
            assertEquals(setOf(remote), HansActiveWorkOwner.activeReasons(context))
            instrumentation.waitForIdleSync()
            assertEquals("The stop request alone must not discard protection while RPC disable is pending",
                setOf(remote), HansActiveWorkOwner.foregroundSnapshot().protectedReasons)

            // Simulate only the acknowledged-RPC ownership release. No actual remote runtime
            // is enabled by this test and the private stop receiver does not clear it itself.
            activeScenario.onActivity { activity ->
                assertEquals(HansActiveWorkUpdateStatus.APPLIED,
                    HansActiveWorkOwner.setReason(activity, remote, false).status)
                released = true
            }
            awaitSnapshot(callbacks, callbackFault) {
                it.sequence > requested.sequence && it.event == HansActiveWorkForegroundEvent.STOPPED &&
                    it.protectedReasons.isEmpty()
            }
            assertTrue(HansActiveWorkOwner.activeReasons(context).isEmpty())
            assertNull(awaitNotification(manager, present = false))
            assertFalse(callbackFault.get())
        } finally {
            try {
                if (acquired && !released) {
                    instrumentation.runOnMainSync { HansActiveWorkOwner.setReason(context, remote, false) }
                }
            } finally {
                try { scenario?.close() } finally { subscription.close() }
            }
        }
    }

    private fun existingStop(context: Context): PendingIntent? = PendingIntent.getBroadcast(
        context, 4102, HansActiveWorkRemoteStopReceiver.intent(context),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun awaitSnapshot(
        callbacks: ArrayBlockingQueue<HansActiveWorkForegroundSnapshot>,
        callbackFault: AtomicBoolean,
        expected: (HansActiveWorkForegroundSnapshot) -> Boolean,
    ): HansActiveWorkForegroundSnapshot {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            assertFalse("Foreground callbacks must remain bounded and main-thread dispatched", callbackFault.get())
            val remaining = deadline - System.nanoTime()
            assertTrue("Timed out waiting for an actual foreground-service acknowledgement", remaining > 0)
            val current = callbacks.poll(remaining.coerceAtLeast(1), TimeUnit.NANOSECONDS)
                ?: throw AssertionError("No foreground-service acknowledgement before the deadline")
            if (current.event == HansActiveWorkForegroundEvent.PROMOTION_REJECTED ||
                current.event == HansActiveWorkForegroundEvent.TIMED_OUT) {
                throw AssertionError("Android rejected or expired actual foreground protection")
            }
            if (expected(current)) return current
        }
    }

    /** Bounded active-test readback only, never production/idle polling or a notification grant. */
    private fun awaitNotification(manager: NotificationManager, present: Boolean): Notification? {
        val deadline = SystemClock.elapsedRealtime() + 3_000
        do {
            val current = manager.activeNotifications.singleOrNull { it.id == HansActiveWorkService.NOTIFICATION_ID }
                ?.notification
            if ((current != null) == present) return current
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Android did not confirm the expected foreground notification presence: $present")
    }
}
