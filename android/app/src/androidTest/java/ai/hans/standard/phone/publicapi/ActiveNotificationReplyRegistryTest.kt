package ai.hans.standard.phone.publicapi

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActiveNotificationReplyRegistryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun remoteInputReplyUsesOpaqueMemoryOnlyHandleAndExactAction() {
        val actionName = "${context.packageName}.TEST_REPLY.${System.nanoTime()}"
        val received = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                received.set(
                    intent?.let(RemoteInput::getResultsFromIntent)
                        ?.getCharSequence("reply_text")
                        ?.toString(),
                )
                latch.countDown()
            }
        }
        register(receiver, IntentFilter(actionName))
        try {
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                7319,
                Intent(actionName).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            val remoteInput = RemoteInput.Builder("reply_text")
                .setAllowFreeFormInput(true)
                .build()
            val action = Notification.Action.Builder(0, "Reply", pendingIntent)
                .addRemoteInput(remoteInput)
                .build()
            val notification = Notification.Builder(context, "test-channel")
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("Alice")
                .setContentText("Untrusted notification text")
                .setActions(action)
                .build()
            val statusBarNotification = StatusBarNotification(
                "org.example.messaging",
                "org.example.messaging",
                17,
                "tag",
                Process.myUid(),
                Process.myPid(),
                0,
                notification,
                Process.myUserHandle(),
                System.currentTimeMillis(),
            )
            val registry = ActiveNotificationReplyRegistry.forTest(ByteArray(32) { 7 })
            registry.onListenerConnected(listOf(statusBarNotification))

            val available = registry.list(10) as PublicPhonePlatformResult.Success
            assertEquals(1, available.value.size)
            val item = available.value.single()
            assertEquals("org.example.messaging", item.sourcePackage)
            assertEquals(24, item.replyToken.length)
            assertFalse(item.replyToken.contains(statusBarNotification.key))

            val result = registry.reply(context, item.replyToken, 0, "Bin unterwegs")
            assertTrue(result is PublicPhonePlatformResult.Success)
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertEquals("Bin unterwegs", received.get())
            val receipt = (result as PublicPhonePlatformResult.Success).value
            assertTrue(receipt.accepted)
            assertFalse(receipt.completionObserved)
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    @Test
    fun disconnectedListenerFailsClosedAndDropsPendingIntentHandles() {
        val registry = ActiveNotificationReplyRegistry.forTest(ByteArray(32) { 3 })
        registry.onListenerDisconnected()

        val list = registry.list(10)
        val reply = registry.reply(context, "abcdefghijklmnopqrstuvwx", 0, "Hello")

        assertEquals(
            "notification_listener_not_connected",
            (list as PublicPhonePlatformResult.Failure).code,
        )
        assertEquals(
            "notification_listener_not_connected",
            (reply as PublicPhonePlatformResult.Failure).code,
        )
    }

    private fun register(receiver: BroadcastReceiver, filter: IntentFilter) {
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}
