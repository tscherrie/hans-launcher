package ai.hans.standard.phone.accessibility.android

import ai.hans.standard.phone.accessibility.AccessibilitySessionId
import ai.hans.standard.phone.accessibility.AccessibilitySnapshotId
import ai.hans.standard.phone.accessibility.AccessibilityWindowId
import ai.hans.standard.phone.accessibility.BoundedSemanticUiSnapshotFactory
import ai.hans.standard.phone.accessibility.RawSemanticUiNode
import ai.hans.standard.phone.accessibility.RawSemanticUiSnapshot
import ai.hans.standard.phone.accessibility.SemanticUiRole
import ai.hans.standard.phone.accessibility.SemanticUiSnapshot
import ai.hans.standard.phone.accessibility.UiBounds
import ai.hans.standard.phone.accessibility.UiSnapshotCorrelation
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real looper/clock coverage with immutable synthetic frames, no Accessibility or app action. */
@RunWith(AndroidJUnit4::class)
class EventDrivenSemanticSnapshotWaiterAndroidTest {
    @Test
    fun mainLooperPublicationResolvesWaitWithoutSecondCapture() {
        withHarness { harness ->
            val examined = CountDownLatch(1)
            val task = harness.start { examined.countDown(); text(it) == "target" }
            assertTrue(examined.await(2, TimeUnit.SECONDS))
            val target = frame(3, "target")
            onMain { harness.publish(target) }
            assertSame(target, task.get(2, TimeUnit.SECONDS))
            assertEquals(1, harness.captures.get())
        }
    }

    @Test
    fun predicateCanRoundTripToMainLooperWithoutPublicationDeadlock() {
        withHarness { harness ->
            val first = AtomicBoolean(true)
            val target = frame(3, "target")
            val task = harness.start {
                if (first.getAndSet(false)) {
                    // If predicate held the publication monitor this would deadlock.
                    val publication = FutureTask { harness.publish(target) }
                    check(harness.handler.post(publication))
                    publication.get(1, TimeUnit.SECONDS)
                    false
                } else text(it) == "target"
            }
            assertSame(target, task.get(2, TimeUnit.SECONDS))
            assertEquals(1, harness.captures.get())
        }
    }

    @Test
    fun cancellationAndUnavailableStateWakeWithoutAnotherCapture() {
        withHarness { harness ->
            val examined = CountDownLatch(1)
            val task = harness.start { examined.countDown(); false }
            assertTrue(examined.await(2, TimeUnit.SECONDS))
            harness.cancelAndWake()
            assertNull(task.get(2, TimeUnit.SECONDS))
            assertEquals(1, harness.captures.get())
        }
        withHarness { harness ->
            val examined = CountDownLatch(1)
            val task = harness.start { examined.countDown(); false }
            assertTrue(examined.await(2, TimeUnit.SECONDS))
            onMain {
                harness.available.set(false)
                harness.publish(frame(3, "target"))
            }
            assertNull(task.get(2, TimeUnit.SECONDS))
            assertEquals(1, harness.captures.get())
        }
    }

    @Test
    fun timeoutHasNoIdleCapturePollingAndCancellationBeforeStartSkipsCapture() {
        withHarness { harness ->
            val examined = CountDownLatch(1)
            val predicates = AtomicInteger()
            val task = harness.start {
                predicates.incrementAndGet(); examined.countDown(); false
            }
            assertTrue(examined.await(2, TimeUnit.SECONDS))
            // Capture also publishes a wake. After that notification is consumed, idle work
            // must remain asleep until the single deadline, without repeatedly probing state.
            assertFalse(CountDownLatch(1).await(100, TimeUnit.MILLISECONDS))
            val reads = harness.clockReads.get()
            val seen = predicates.get()
            assertFalse(CountDownLatch(1).await(50, TimeUnit.MILLISECONDS))
            assertEquals(reads, harness.clockReads.get())
            assertEquals(seen, predicates.get())
            harness.cancelAndWake()
            assertNull(task.get(2, TimeUnit.SECONDS))
            assertEquals(1, harness.captures.get())
        }
        withHarness { harness ->
            assertNull(harness.start(timeoutMillis = 150) { false }.get(2, TimeUnit.SECONDS))
            assertEquals(1, harness.captures.get())
        }
        withHarness { harness ->
            harness.cancelAndWake()
            assertNull(harness.start { true }.get(2, TimeUnit.SECONDS))
            assertEquals(0, harness.captures.get())
        }
    }

    @Test
    fun spuriousMainLooperWakeCannotAcceptCachedPreWaitFrame() {
        withHarness { harness ->
            harness.current.set(frame(1, "target"))
            harness.fresh.set(null)
            val predicates = AtomicInteger()
            val task = harness.start(timeoutMillis = 150) { predicates.incrementAndGet(); true }
            assertTrue(harness.captured.await(2, TimeUnit.SECONDS))
            onMain { harness.waiter.wake() }
            assertNull(task.get(2, TimeUnit.SECONDS))
            assertEquals(0, predicates.get())
            assertEquals(1, harness.captures.get())
        }
    }

    private fun withHarness(block: (Harness) -> Unit) {
        val harness = Harness()
        try { block(harness) } finally { harness.cancelAndWake() }
    }

    private class Harness {
        val handler = Handler(Looper.getMainLooper())
        val current = AtomicReference<SemanticUiSnapshot?>(frame(1, "old"))
        val fresh = AtomicReference<SemanticUiSnapshot?>(frame(2, "loading"))
        val available = AtomicBoolean(true)
        val cancelled = AtomicBoolean(false)
        val captures = AtomicInteger()
        val clockReads = AtomicInteger()
        val captured = CountDownLatch(1)
        val waiter: EventDrivenSemanticSnapshotWaiter = EventDrivenSemanticSnapshotWaiter(
            freshSnapshot = { remaining ->
                val capture = FutureTask<SemanticUiSnapshot?> {
                    captures.incrementAndGet()
                    fresh.get().also { frame ->
                        if (frame != null) publish(frame)
                        captured.countDown()
                    }
                }
                check(handler.post(capture))
                try { capture.get(remaining, TimeUnit.MILLISECONDS) } finally { capture.cancel(false) }
            },
            currentSnapshot = current::get,
            elapsedRealtimeMillis = { clockReads.incrementAndGet(); SystemClock.elapsedRealtime() },
        )

        fun publish(snapshot: SemanticUiSnapshot) {
            check(Looper.myLooper() == Looper.getMainLooper())
            current.set(snapshot)
            waiter.wake()
        }

        fun start(
            timeoutMillis: Long = 5_000,
            predicate: (SemanticUiSnapshot) -> Boolean,
        ): FutureTask<SemanticUiSnapshot?> = FutureTask {
            waiter.await(timeoutMillis, cancelled::get, available::get, predicate)
        }.also { Thread(it, "semantic-wait-test").apply { isDaemon = true; start() } }

        fun cancelAndWake() { cancelled.set(true); waiter.wake() }
    }

    companion object {
        private fun onMain(action: () -> Unit) =
            InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

        private fun frame(id: Long, text: String): SemanticUiSnapshot =
            BoundedSemanticUiSnapshotFactory().build(RawSemanticUiSnapshot(
                UiSnapshotCorrelation(
                    AccessibilitySessionId("semantic-wait-instrumented"),
                    AccessibilityWindowId(1), AccessibilitySnapshotId(id),
                ),
                UiBounds(0, 0, 400, 800), id,
                listOf(RawSemanticUiNode(
                    packageName = "synthetic.example", text = text,
                    role = SemanticUiRole.TEXT, bounds = UiBounds(10, 10, 110, 60),
                )),
            ))

        private fun text(snapshot: SemanticUiSnapshot) = snapshot.nodes.single().text?.value
    }
}
