package ai.hans.standard.plugins.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginInstallDeadlineTest {
    @Test
    fun schedulerIsNonBlockingAndExactLeaseExpiresOnce() {
        val scheduler = FakeScheduler()
        val expired = mutableListOf<PluginInstallDeadlineIdentity>()
        val deadline = PluginInstallDeadline(scheduler) { 1_000L }

        val lease = deadline.schedule("operation-1", "lease-1", 1_250L, expired::add)

        assertEquals(250L, scheduler.delayMillis)
        assertTrue(expired.isEmpty())
        scheduler.run()
        scheduler.run()
        assertTrue(lease.isExpired)
        assertFalse(lease.isCancelled)
        assertEquals(listOf(lease.identity), expired)
        assertFalse(lease.cancel())
    }

    @Test
    fun cancellationWinsRaceAndCancelsQueuedWorkWithoutCallingDeadlineBody() {
        val scheduler = FakeScheduler()
        var expirationCount = 0
        val lease = PluginInstallDeadline(scheduler) { 5_000L }.schedule(
            operationId = "operation-1",
            leaseId = "lease-1",
            deadlineAtElapsedRealtimeMillis = 6_000L,
        ) { expirationCount += 1 }

        assertTrue(lease.cancel())
        assertTrue(lease.isCancelled)
        assertTrue(scheduler.cancelled)
        scheduler.run()
        assertEquals(0, expirationCount)
        assertFalse(lease.cancel())
    }

    @Test
    fun alreadyExpiredDeadlineQueuesImmediateWorkInsteadOfBlockingCaller() {
        val scheduler = FakeScheduler()
        var expired = false

        PluginInstallDeadline(scheduler) { 2_000L }.schedule(
            "operation-1",
            "lease-1",
            1_000L,
        ) { expired = true }

        assertEquals(0L, scheduler.delayMillis)
        assertFalse(expired)
        scheduler.run()
        assertTrue(expired)
    }

    @Test
    fun synchronousSchedulerRaceStillExpiresExactlyOnceAndCancelsReturnedTask() {
        var expired = 0
        var taskCancelled = false
        val scheduler = PluginInstallDeadlineScheduler { _, action ->
            action()
            PluginInstallScheduledCancellation {
                taskCancelled = true
                true
            }
        }

        val lease = PluginInstallDeadline(scheduler) { 0L }.schedule(
            "operation-1",
            "lease-1",
            0L,
        ) { expired += 1 }

        assertEquals(1, expired)
        assertTrue(lease.isExpired)
        assertTrue(taskCancelled)
    }

    @Test
    fun deadlineAndIdentityAreStrictlyBounded() {
        val deadline = PluginInstallDeadline(FakeScheduler()) { 1_000L }
        assertThrows(IllegalArgumentException::class.java) {
            deadline.schedule("bad operation", "lease-1", 2_000L) {}
        }
        assertThrows(IllegalArgumentException::class.java) {
            deadline.schedule(
                "operation-1",
                "lease-1",
                1_000L + PluginInstallDeadline.MAX_FUTURE_MILLIS + 1L,
            ) {}
        }
    }

    private class FakeScheduler : PluginInstallDeadlineScheduler {
        var delayMillis: Long? = null
        var action: (() -> Unit)? = null
        var cancelled = false

        override fun schedule(
            delayMillis: Long,
            action: () -> Unit,
        ): PluginInstallScheduledCancellation {
            check(this.action == null)
            this.delayMillis = delayMillis
            this.action = action
            return PluginInstallScheduledCancellation {
                cancelled = true
                true
            }
        }

        fun run() {
            action?.invoke()
        }
    }
}
