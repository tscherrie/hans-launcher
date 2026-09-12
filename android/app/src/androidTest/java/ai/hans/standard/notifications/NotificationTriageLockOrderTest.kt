package ai.hans.standard.notifications

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android main-Looper regressions; no device grants, personal data or model calls. */
@RunWith(AndroidJUnit4::class)
class NotificationTriageLockOrderTest {
    @Test
    fun mainLooperCanPreemptWhileIdleRecoveryIsReadingTheQueue() {
        val recoveryEntered = CountDownLatch(1)
        val releaseRecovery = CountDownLatch(1)
        val mainPreempted = CountDownLatch(1)
        val storage = MemoryStorage { read ->
            // Empty queue + unattached sink: read one is claim, read two is lease recovery.
            if (read == 2) {
                recoveryEntered.countDown()
                check(releaseRecovery.await(5, TimeUnit.SECONDS))
            }
        }
        val runtime = NotificationTriageRuntime(
            queue(storage), RestrictedNotificationTriageExecutor { error("empty queue") },
            unattachedSink(),
        )
        try {
            runtime.requestDrain()
            assertTrue(recoveryEntered.await(2, TimeUnit.SECONDS))
            assertTrue(Handler(Looper.getMainLooper()).post {
                runtime.preemptForInteraction()
                mainPreempted.countDown()
            })
            assertTrue(
                "Main-Looper send/preemption waited for the background queue read",
                mainPreempted.await(2, TimeUnit.SECONDS),
            )
        } finally {
            // Even the pre-fix implementation must release its blocked main Looper on failure.
            releaseRecovery.countDown()
            runtime.close()
        }
    }

    @Test
    fun mainLooperCanPreemptWhileTheFinalPermitReadsThePrivacyGate() {
        val permitReads = AtomicInteger(0)
        val privacyReadEntered = CountDownLatch(1)
        val releasePrivacyRead = CountDownLatch(1)
        val mainPreempted = CountDownLatch(1)
        val runtime = NotificationTriageRuntime(
            queue(MemoryStorage()),
            RestrictedNotificationTriageExecutor { error("interaction is busy") },
            unattachedSink(),
            processingPermit = {
                if (permitReads.incrementAndGet() == 2) {
                    synchronized(NotificationTriageTransactionCoordinator.lock) {
                        privacyReadEntered.countDown()
                        check(releasePrivacyRead.await(5, TimeUnit.SECONDS))
                    }
                }
                false
            },
        )
        try {
            runtime.requestDrain()
            assertTrue(privacyReadEntered.await(2, TimeUnit.SECONDS))
            assertTrue(Handler(Looper.getMainLooper()).post {
                runtime.preemptForInteraction()
                mainPreempted.countDown()
            })
            assertTrue(
                "Main-Looper send/preemption waited for the background privacy gate",
                mainPreempted.await(2, TimeUnit.SECONDS),
            )
        } finally {
            releasePrivacyRead.countDown()
            runtime.close()
        }
    }

    private fun queue(storage: NotificationTriageStorage) = NotificationTriageQueue(
        storage, HansNotificationExclusionPolicy(setOf("ai.hans.standard")), clock = { 1_000L },
    )

    private fun unattachedSink() = object : UserFacingNotificationSuggestionSink {
        override fun isReady(): Boolean = false
        override fun deliver(delivery: UserFacingNotificationDelivery): UserFacingDeliveryDisposition =
            error("sink is unattached")
    }

    private class MemoryStorage(
        private val onRead: (Int) -> Unit = {},
    ) : NotificationTriageStorage {
        private var state = NotificationTriageQueueState(emptyList())
        private var reads = 0
        override fun read(): NotificationTriageQueueState {
            onRead(++reads)
            return state
        }
        override fun write(state: NotificationTriageQueueState) { this.state = state }
    }
}
