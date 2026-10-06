package ai.hans.standard.phone.accessibility.android

import ai.hans.standard.phone.accessibility.SemanticUiSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class EventDrivenSemanticSnapshotWaiterTest {
    @Test
    fun freshTargetReturnsImmediatelyWithoutWaitingForAnotherEvent() {
        val harness = Harness()
        harness.fresh.set(frame(2, "target"))
        assertSame(harness.fresh.get(), harness.await { text(it) == "target" })
        assertEquals(1, harness.captures.get())
    }

    @Test
    fun targetArrivingAfterInitialWrongScreenWakesWaitAndCapturesOnlyOnce() {
        val harness = Harness()
        val firstRead = CountDownLatch(1)
        val task = harness.start {
            firstRead.countDown()
            text(it) == "target"
        }
        try {
            assertTrue(firstRead.await(2, TimeUnit.SECONDS))
            val target = frame(3, "target")
            harness.current.set(target)
            harness.waiter.wake()
            assertSame(target, task.get(2, TimeUnit.SECONDS))
            assertEquals(1, harness.captures.get())
        } finally { harness.cancelAndWake() }
    }

    @Test
    fun cancellationBeforeWaitAndCancellationWakeReturnWithoutActionOrPolling() {
        val before = Harness()
        before.cancelled.set(true)
        assertNull(before.await { true })
        assertEquals(0, before.captures.get())

        val during = Harness()
        val examined = CountDownLatch(1)
        val task = during.start { examined.countDown(); false }
        assertTrue(examined.await(2, TimeUnit.SECONDS))
        during.cancelAndWake()
        assertNull(task.get(2, TimeUnit.SECONDS))
        assertEquals(1, during.captures.get())
    }

    @Test
    fun unavailableAndSessionReplacementFailEvenIfNewFrameMatches() {
        val before = Harness()
        before.available.set(false)
        assertNull(before.await { true })
        assertEquals(0, before.captures.get())

        val during = Harness()
        val examined = CountDownLatch(1)
        val task = during.start { examined.countDown(); false }
        assertTrue(examined.await(2, TimeUnit.SECONDS))
        during.available.set(false) // Same gate also covers replaced service session.
        during.current.set(frame(3, "target"))
        during.waiter.wake()
        assertNull(task.get(2, TimeUnit.SECONDS))
    }

    @Test
    fun spuriousNotificationsDoNotAcceptCachedPreWaitStateOrExtendDeadline() {
        val harness = Harness()
        val stale = frame(1, "target")
        harness.current.set(stale)
        harness.fresh.set(null)
        val predicateCalls = AtomicInteger()
        val captureFinished = CountDownLatch(1)
        harness.afterCapture = { captureFinished.countDown() }
        val task = harness.start { predicateCalls.incrementAndGet(); true }
        try {
            assertTrue(captureFinished.await(2, TimeUnit.SECONDS))
            harness.waiter.wake()
            harness.now.set(5_000)
            harness.waiter.wake()
            assertNull(task.get(2, TimeUnit.SECONDS))
            assertEquals(0, predicateCalls.get())
            assertEquals(1, harness.captures.get())
        } finally { harness.cancelAndWake() }
    }

    @Test
    fun deadlineStartsBeforeFreshCaptureAndTimeoutNeverRecapturesOrRetries() {
        val harness = Harness()
        harness.fresh.set(frame(2, "target"))
        harness.afterCapture = { harness.now.set(5_000) }
        assertNull(harness.await { true })
        assertEquals(1, harness.captures.get())
        assertEquals(5_000L, harness.captureBudget.get())
        assertNull(harness.waiter.await(0, { false }, { true }) { true })
        assertNull(harness.waiter.await(5_001, { false }, { true }) { true })
        assertEquals(1, harness.captures.get())
    }

    @Test
    fun noEventsMeansNoRepeatedClockReadsCapturesOrPredicates() {
        val harness = Harness()
        val examined = CountDownLatch(1)
        val calls = AtomicInteger()
        val task = harness.start { calls.incrementAndGet(); examined.countDown(); false }
        assertTrue(examined.await(2, TimeUnit.SECONDS))
        // Wait for the worker to enter its single condition-variable deadline wait.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (harness.thread.get()?.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertEquals(Thread.State.TIMED_WAITING, harness.thread.get()?.state)
        val reads = harness.clockReads.get()
        assertFalse(CountDownLatch(1).await(75, TimeUnit.MILLISECONDS))
        assertEquals(reads, harness.clockReads.get())
        assertEquals(1, harness.captures.get())
        assertEquals(1, calls.get())
        harness.cancelAndWake()
        assertNull(task.get(2, TimeUnit.SECONDS))
    }

    @Test
    fun predicateRunsOutsideMonitorAndEventBeforeWaitIsNotLost() {
        val harness = Harness()
        val target = frame(3, "target")
        val first = AtomicBoolean(true)
        val result = harness.await {
            if (first.getAndSet(false)) {
                val published = CountDownLatch(1)
                Thread {
                    harness.current.set(target)
                    harness.waiter.wake()
                    published.countDown()
                }.start()
                assertTrue("Publishing must not block behind predicate", published.await(2, TimeUnit.SECONDS))
                false
            } else text(it) == "target"
        }
        assertSame(target, result)
    }

    @Test
    fun cancellationTriggeredByPredicateCannotReturnAnAcceptedSnapshot() {
        val harness = Harness()
        assertNull(harness.await { harness.cancelAndWake(); true })
    }

    @Test
    fun throwingPredicateAndProbesFailClosedWithoutRetry() {
        val harness = Harness()
        assertNull(harness.await { error("untrusted predicate") })
        assertNull(harness.waiter.await(5_000, { error("cancel probe") }, { true }) { true })
        assertNull(harness.waiter.await(5_000, { false }, { error("availability probe") }) { true })
        assertEquals(1, harness.captures.get())
    }

    @Test
    fun interruptionWakesWaitAndRestoresInterruptFlag() {
        val harness = Harness()
        val examined = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val done = CountDownLatch(1)
        val thread = Thread {
            assertNull(harness.await { examined.countDown(); false })
            interrupted.set(Thread.currentThread().isInterrupted)
            done.countDown()
        }
        thread.start()
        assertTrue(examined.await(2, TimeUnit.SECONDS))
        thread.interrupt()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertTrue(interrupted.get())
    }

    private class Harness {
        val current = AtomicReference<SemanticUiSnapshot?>(frame(1, "old"))
        val fresh = AtomicReference<SemanticUiSnapshot?>(frame(2, "loading"))
        val available = AtomicBoolean(true)
        val cancelled = AtomicBoolean(false)
        val now = AtomicLong(0)
        val captures = AtomicInteger()
        val clockReads = AtomicInteger()
        val captureBudget = AtomicLong()
        val thread = AtomicReference<Thread?>()
        var afterCapture: () -> Unit = {}
        val waiter = EventDrivenSemanticSnapshotWaiter(
            freshSnapshot = { remaining ->
                captures.incrementAndGet()
                captureBudget.set(remaining)
                fresh.get().also { if (it != null) current.set(it); afterCapture() }
            },
            currentSnapshot = current::get,
            elapsedRealtimeMillis = { clockReads.incrementAndGet(); now.get() },
        )

        fun await(predicate: (SemanticUiSnapshot) -> Boolean) =
            waiter.await(5_000, cancelled::get, available::get, predicate)

        fun start(predicate: (SemanticUiSnapshot) -> Boolean): FutureTask<SemanticUiSnapshot?> =
            FutureTask { await(predicate) }.also { task ->
                Thread(task).apply { isDaemon = true; thread.set(this); start() }
            }

        fun cancelAndWake() { cancelled.set(true); waiter.wake() }
    }

    companion object {
        private fun frame(id: Long, text: String) = semanticSnapshot(
            correlation = testCorrelation(snapshot = id), roots = listOf(rawNode(text = text)),
        )
        private fun text(snapshot: SemanticUiSnapshot) = snapshot.nodes.single().text?.value
    }
}
