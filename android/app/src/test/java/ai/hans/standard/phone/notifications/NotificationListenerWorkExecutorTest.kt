package ai.hans.standard.phone.notifications

import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationListenerWorkExecutorTest {
    @Test
    fun callbackFloodIsBoundedAndRejectedInsteadOfRunningOnCaller() {
        val executor = NotificationListenerWorkExecutor.create()
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.execute {
            running.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        assertTrue(running.await(2, TimeUnit.SECONDS))

        repeat(NotificationListenerWorkExecutor.MAX_QUEUED_CALLBACKS) {
            executor.execute { }
        }
        assertEquals(
            NotificationListenerWorkExecutor.MAX_QUEUED_CALLBACKS,
            executor.queue.size,
        )
        assertTrue(runCatching { executor.execute { } }.exceptionOrNull() is RejectedExecutionException)
        assertEquals(1L, release.count)

        release.countDown()
        executor.shutdownNow()
    }

    @Test
    fun overflowCoalescesOneLatestStateReconciliationAfterQueueDrains() {
        val executor = NotificationListenerWorkExecutor.create()
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val recovered = CountDownLatch(1)
        val recoveries = AtomicInteger(0)
        executor.execute {
            running.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        assertTrue(running.await(2, TimeUnit.SECONDS))
        repeat(NotificationListenerWorkExecutor.MAX_QUEUED_CALLBACKS) {
            executor.execute { }
        }

        repeat(16) {
            assertTrue(
                !executor.executeOrRequestReconciliation(
                    task = { error("overflowed callback must not run") },
                    reconciliation = {
                        recoveries.incrementAndGet()
                        recovered.countDown()
                    },
                ),
            )
        }
        assertEquals(0, recoveries.get())
        release.countDown()

        assertTrue(recovered.await(5, TimeUnit.SECONDS))
        assertEquals(1, recoveries.get())
        executor.shutdownNow()
    }

    @Test
    fun rejectedRemovalQuarantinesDeliveryImmediatelyBeforeSnapshotRecoveryRuns() {
        val gate = NotificationAuthoritativeSnapshotGate().also { it.markReady() }
        var preempted = false

        quarantineOnRejectedNotificationCallback(
            accepted = false,
            gate = gate,
            preempt = { preempted = true },
        )

        assertFalse(gate.isReady())
        assertTrue(preempted)
    }
}
