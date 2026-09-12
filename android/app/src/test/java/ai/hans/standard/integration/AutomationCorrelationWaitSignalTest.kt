package ai.hans.standard.integration

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationCorrelationWaitSignalTest {
    @Test
    fun cancellationBeforeRealConditionAwaitIsNotLost() {
        WaitFixture().use { fixture ->
            fixture.lock.withLock {
                assertFalse(fixture.lock.hasWaiters(fixture.condition))
                fixture.signal.signalCancellation("run-1")
            }

            // There is deliberately no waiter when cancellation signals the real Condition.
            // A bare signalAll here leaves this later 30-second wait asleep until its timeout.
            fixture.beginWait("run-1").get(COMPLETION_SECONDS, TimeUnit.SECONDS)
        }
    }

    @Test
    fun cancellationDuringRealConditionAwaitWakesAndIsConsumed() {
        WaitFixture().use { fixture ->
            val waiting = fixture.beginWait("run-1")
            fixture.lock.withLock {
                fixture.assertWaiting(waiting)
                fixture.signal.signalCancellation("run-1")
            }
            waiting.get(COMPLETION_SECONDS, TimeUnit.SECONDS)

            // The old cancellation must not force every subsequent wait to return immediately.
            val later = fixture.beginWait("run-1")
            fixture.lock.withLock {
                fixture.assertWaiting(later)
                fixture.signal.signalStateChange()
            }
            later.get(COMPLETION_SECONDS, TimeUnit.SECONDS)
        }
    }

    @Test
    fun retainedCancellationOnlyBelongsToItsCorrelation() {
        WaitFixture().use { fixture ->
            fixture.lock.withLock { fixture.signal.signalCancellation("cancelled-run") }

            val other = fixture.beginWait("other-run")
            fixture.lock.withLock {
                fixture.assertWaiting(other)
                fixture.signal.signalStateChange()
            }
            other.get(COMPLETION_SECONDS, TimeUnit.SECONDS)

            // Waiting on another correlation must not consume this one's retained cancellation.
            fixture.beginWait("cancelled-run").get(COMPLETION_SECONDS, TimeUnit.SECONDS)
        }
    }

    @Test
    fun ordinaryStateSignalBeforeAwaitDoesNotBecomeStickyCancellation() {
        WaitFixture().use { fixture ->
            fixture.lock.withLock { fixture.signal.signalStateChange() }

            val waiting = fixture.beginWait("run-1")
            fixture.lock.withLock {
                fixture.assertWaiting(waiting)
                fixture.signal.signalStateChange()
            }
            waiting.get(COMPLETION_SECONDS, TimeUnit.SECONDS)
        }
    }

    @Test
    fun repeatedCancellationCoalescesIntoOneWakeInsteadOfBusyLooping() {
        WaitFixture().use { fixture ->
            fixture.lock.withLock {
                fixture.signal.signalCancellation("run-1")
                fixture.signal.signalCancellation("run-1")
            }
            fixture.beginWait("run-1").get(COMPLETION_SECONDS, TimeUnit.SECONDS)

            val later = fixture.beginWait("run-1")
            fixture.lock.withLock {
                fixture.assertWaiting(later)
                fixture.signal.signalStateChange()
            }
            later.get(COMPLETION_SECONDS, TimeUnit.SECONDS)
        }
    }

    @Test
    fun removingCorrelationForgetsCancellationBeforeItsIdIsReused() {
        WaitFixture().use { fixture ->
            fixture.lock.withLock {
                fixture.signal.signalCancellation("run-1")
                fixture.signal.forget("run-1")
            }

            val replacement = fixture.beginWait("run-1")
            fixture.lock.withLock {
                fixture.assertWaiting(replacement)
                fixture.signal.signalStateChange()
            }
            replacement.get(COMPLETION_SECONDS, TimeUnit.SECONDS)
        }
    }

    @Test
    fun everyOperationRequiresTheSameCorrelationLock() {
        val signal = AutomationCorrelationWaitSignal(ReentrantLock())
        assertThrows(IllegalStateException::class.java) { signal.signalCancellation("run-1") }
        assertThrows(IllegalStateException::class.java) { signal.signalStateChange() }
        assertThrows(IllegalStateException::class.java) { signal.awaitChange("run-1", 1L) }
        assertThrows(IllegalStateException::class.java) { signal.forget("run-1") }
    }

    private class WaitFixture : AutoCloseable {
        val lock = ReentrantLock()
        val condition = lock.newCondition()
        val signal = AutomationCorrelationWaitSignal(lock, condition)
        private val worker = Executors.newSingleThreadExecutor()

        fun beginWait(correlationId: String): Future<*> {
            val entering = CountDownLatch(1)
            val future = worker.submit {
                lock.withLock {
                    entering.countDown()
                    signal.awaitChange(correlationId, 30_000L)
                }
            }
            assertTrue("Waiter did not start", entering.await(COMPLETION_SECONDS, TimeUnit.SECONDS))
            return future
        }

        /**
         * beginWait counts down while holding this lock. Acquiring it here means the worker has
         * reached Condition.await (and released the lock), or returned too early. Inspect the real
         * Condition queue to distinguish those outcomes without sleeps or an injected onAwait.
         */
        fun assertWaiting(future: Future<*>) {
            check(lock.isHeldByCurrentThread)
            assertFalse("Wait unexpectedly completed", future.isDone)
            assertTrue("Worker never entered Condition.await", lock.hasWaiters(condition))
        }

        override fun close() {
            worker.shutdownNow()
            assertTrue("Waiter did not stop", worker.awaitTermination(COMPLETION_SECONDS, TimeUnit.SECONDS))
        }
    }

    private companion object {
        const val COMPLETION_SECONDS = 5L
    }
}
