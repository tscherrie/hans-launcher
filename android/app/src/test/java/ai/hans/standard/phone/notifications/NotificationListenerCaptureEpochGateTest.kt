package ai.hans.standard.phone.notifications

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationListenerCaptureEpochGateTest {
    @Test
    fun teardownUsesPrivacyBeforeQueueAndCannotDeadlockWithOutboxReplay() {
        val privacyLock = Any()
        val queueLock = Any()
        val replayHasPrivacy = CountDownLatch(1)
        val allowReplayToQueue = CountDownLatch(1)
        val replayFinished = CountDownLatch(1)
        val teardownFinished = CountDownLatch(1)
        var invalidated = false

        val replay = Thread {
            synchronized(privacyLock) {
                replayHasPrivacy.countDown()
                check(allowReplayToQueue.await(2, TimeUnit.SECONDS))
                synchronized(queueLock) { /* replay transaction */ }
            }
            replayFinished.countDown()
        }
        replay.start()
        assertTrue(replayHasPrivacy.await(2, TimeUnit.SECONDS))

        val teardown = Thread {
            runNotificationListenerTeardownBarrier(
                privacyLock = privacyLock,
                queueLock = queueLock,
                markDestroyingAndInvalidate = { invalidated = true },
            )
            teardownFinished.countDown()
        }
        teardown.start()
        assertFalse(teardownFinished.await(100, TimeUnit.MILLISECONDS))

        allowReplayToQueue.countDown()
        assertTrue(replayFinished.await(2, TimeUnit.SECONDS))
        assertTrue(teardownFinished.await(2, TimeUnit.SECONDS))
        replay.join(2_000)
        teardown.join(2_000)
        assertTrue(invalidated)
        assertFalse(replay.isAlive)
        assertFalse(teardown.isAlive)
    }

    @Test
    fun delayedOldCallbackCannotWriteAfterTeardownAndPrivacyClear() {
        val privacyLock = Any()
        val gate = NotificationListenerCaptureEpochGate(privacyLock)
        val oldLease = gate.beginEpoch()
        val callbackBlocked = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val callbackFinished = CountDownLatch(1)
        val durableInbox = mutableListOf("preexisting")

        val callback = Thread {
            callbackBlocked.countDown()
            check(releaseCallback.await(2, TimeUnit.SECONDS))
            gate.mutateIfCurrent(oldLease) { durableInbox.add("late-notification") }
            callbackFinished.countDown()
        }
        callback.start()
        assertTrue(callbackBlocked.await(2, TimeUnit.SECONDS))

        gate.invalidateEpoch()
        synchronized(privacyLock) { durableInbox.clear() }
        releaseCallback.countDown()

        assertTrue(callbackFinished.await(2, TimeUnit.SECONDS))
        callback.join(2_000)
        assertTrue(durableInbox.isEmpty())
    }

    @Test
    fun newListenerEpochNeverReactivatesAnOldCallbackLease() {
        val gate = NotificationListenerCaptureEpochGate(Any())
        val oldLease = gate.beginEpoch()
        gate.invalidateEpoch()
        val newLease = gate.beginEpoch()
        var writes = 0

        assertEquals(null, gate.mutateIfCurrent(oldLease) { ++writes })
        assertFalse(gate.isCurrent(oldLease))
        assertTrue(gate.isCurrent(newLease))
        assertEquals(1, gate.mutateIfCurrent(newLease) { ++writes })
        assertEquals(1, writes)
    }

    @Test
    fun delayedExcludedCallbackCannotRemoveSameKeyFromNewReplyRegistryEpoch() {
        val gate = NotificationListenerCaptureEpochGate(Any())
        val oldLease = gate.beginEpoch()
        val oldCallbackChecked = CountDownLatch(1)
        val releaseOldCallback = CountDownLatch(1)
        val oldCallbackFinished = CountDownLatch(1)
        val registry = linkedSetOf("same-key")

        val oldCallback = Thread {
            assertTrue(gate.isCurrent(oldLease))
            oldCallbackChecked.countDown()
            check(releaseOldCallback.await(2, TimeUnit.SECONDS))
            gate.mutateIfCurrent(oldLease) { registry.remove("same-key") }
            oldCallbackFinished.countDown()
        }
        oldCallback.start()
        assertTrue(oldCallbackChecked.await(2, TimeUnit.SECONDS))

        gate.invalidateEpoch()
        val newLease = gate.beginEpoch()
        gate.mutateIfCurrent(newLease) {
            registry.clear()
            registry += "same-key"
        }
        releaseOldCallback.countDown()

        assertTrue(oldCallbackFinished.await(2, TimeUnit.SECONDS))
        oldCallback.join(2_000)
        assertEquals(setOf("same-key"), registry)
    }
}
