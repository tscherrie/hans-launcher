package ai.hans.standard.phone.notifications

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationSetupLiveTestRoutingAndroidTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = 10_000L
    private val tracker = NotificationSetupLiveTestReceiptTracker.forTest(
        elapsedRealtimeMillis = { now },
        receiptTtlMillis = 1_000L,
    )
    private val nonce = "setup_nonce_android_123456"

    @Test
    fun correctNonceProducesReceiptWithoutInboxTriageModelOrRecallRoute() {
        val downstreamCalls = AtomicInteger()
        assertTrue(tracker.arm(nonce))

        val intercepted = routePostedNotificationAroundSetupProbe(
            notification = notification(nonce),
            ownPackageName = context.packageName,
            receiptTracker = tracker,
            ordinaryIntake = { downstreamCalls.incrementAndGet() },
        )

        assertTrue(intercepted)
        assertEquals(0, downstreamCalls.get())
        assertTrue(tracker.consumeVerified(nonce))
    }

    @Test
    fun wrongAndExpiredNonceRemainPrivateButCannotProduceReceipt() {
        val downstreamCalls = AtomicInteger()
        assertTrue(tracker.arm(nonce))
        assertTrue(
            routePostedNotificationAroundSetupProbe(
                notification = notification("setup_nonce_wrong_654321"),
                ownPackageName = context.packageName,
                receiptTracker = tracker,
                ordinaryIntake = { downstreamCalls.incrementAndGet() },
            ),
        )
        assertFalse(tracker.consumeVerified(nonce))
        assertEquals(0, downstreamCalls.get())

        assertTrue(tracker.arm(nonce))
        now += 1_001L
        assertTrue(
            routePostedNotificationAroundSetupProbe(
                notification = notification(nonce),
                ownPackageName = context.packageName,
                receiptTracker = tracker,
                ordinaryIntake = { downstreamCalls.incrementAndGet() },
            ),
        )
        assertFalse(tracker.consumeVerified(nonce))
        assertEquals(0, downstreamCalls.get())
    }

    @Test
    fun removalIsAlsoConsumedBeforeAnyDurableOrModelRoute() {
        val downstreamCalls = AtomicInteger()

        assertTrue(
            routeRemovedNotificationAroundSetupProbe(
                notification = notification(nonce),
                ownPackageName = context.packageName,
                receiptTracker = tracker,
                ordinaryIntake = { downstreamCalls.incrementAndGet() },
            ),
        )
        assertEquals(0, downstreamCalls.get())
    }

    @Test
    fun otherPackagesStayOnOrdinaryProtectedIntakePath() {
        val downstreamCalls = AtomicInteger()

        assertFalse(
            routePostedNotificationAroundSetupProbe(
                notification = notification(nonce, packageName = "org.example.messaging"),
                ownPackageName = context.packageName,
                receiptTracker = tracker,
                ordinaryIntake = { downstreamCalls.incrementAndGet() },
            ),
        )
        assertEquals(1, downstreamCalls.get())
    }

    private fun notification(
        receiptNonce: String,
        packageName: String = context.packageName,
    ): StatusBarNotification {
        val notification = Notification.Builder(
            context,
            NotificationSetupLiveTestReceiptTracker.NOTIFICATION_CHANNEL_ID,
        )
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Hans Einrichtungstest")
            .setContentText("Private setup probe")
            .addExtras(
                Bundle().apply {
                    putString(
                        NotificationSetupLiveTestReceiptTracker.EXTRA_OPERATION_NONCE,
                        receiptNonce,
                    )
                },
            )
            .build()
        return StatusBarNotification(
            packageName,
            packageName,
            NotificationSetupLiveTestReceiptTracker.NOTIFICATION_ID,
            null,
            Process.myUid(),
            Process.myPid(),
            0,
            notification,
            Process.myUserHandle(),
            System.currentTimeMillis(),
        )
    }
}
