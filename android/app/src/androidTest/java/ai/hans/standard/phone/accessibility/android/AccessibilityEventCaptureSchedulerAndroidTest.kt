package ai.hans.standard.phone.accessibility.android

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real main-looper queue coverage; never binds Accessibility or acts on another app. */
@RunWith(AndroidJUnit4::class)
class AccessibilityEventCaptureSchedulerAndroidTest {
    @Test
    fun successfulFreshCaptureRemovesOldHandlerCallbackAndLaterEventStillRuns() {
        withHarness { harness ->
            onMain {
                harness.scheduler.onEvent()
                val oldCallback = checkNotNull(harness.lastPosted)
                assertTrue(harness.handler.hasCallbacks(oldCallback))
                harness.scheduler.capturePublished(harness.scheduler.captureStarted())
                assertFalse(harness.handler.hasCallbacks(oldCallback))
                oldCallback.run()
                assertEquals(0, harness.captures.get())

                harness.scheduler.onEvent()
                assertTrue(harness.handler.hasCallbacks(checkNotNull(harness.lastPosted)))
            }
            assertTrue(harness.completed.await(2, TimeUnit.SECONDS))
            assertEquals(1, harness.captures.get())
        }
    }

    @Test
    fun failedFreshCaptureKeepsPendingHandlerRefresh() {
        withHarness { harness ->
            onMain {
                harness.scheduler.onEvent()
                harness.scheduler.captureStarted()
                // Simulate failure: no frame was published, so no acknowledgment is allowed.
                assertTrue(harness.handler.hasCallbacks(checkNotNull(harness.lastPosted)))
            }
            assertTrue(harness.completed.await(2, TimeUnit.SECONDS))
            assertEquals(1, harness.captures.get())
        }
    }

    @Test
    fun eventNewerThanCaptureStartKeepsHandlerRefresh() {
        withHarness { harness ->
            onMain {
                harness.scheduler.onEvent()
                val capture = harness.scheduler.captureStarted()
                harness.scheduler.onEvent()
                harness.scheduler.capturePublished(capture)
                assertTrue(harness.handler.hasCallbacks(checkNotNull(harness.lastPosted)))
            }
            assertTrue(harness.completed.await(2, TimeUnit.SECONDS))
            assertEquals(1, harness.captures.get())
        }
    }

    @Test
    fun disconnectCancelsHandlerWorkAndStaleCallbackCannotConsumeReconnectedEvent() {
        withHarness { harness ->
            onMain {
                harness.scheduler.onEvent()
                val oldCallback = checkNotNull(harness.lastPosted)
                val oldCapture = harness.scheduler.captureStarted()
                harness.scheduler.cancel()
                assertFalse(harness.handler.hasCallbacks(oldCallback))
                harness.scheduler.onEvent()
                harness.scheduler.capturePublished(oldCapture)
                oldCallback.run()

                assertEquals(0, harness.captures.get())
                assertTrue(harness.handler.hasCallbacks(checkNotNull(harness.lastPosted)))
            }
            assertTrue(harness.completed.await(2, TimeUnit.SECONDS))
            assertEquals(1, harness.captures.get())
        }
    }

    private fun withHarness(test: (Harness) -> Unit) {
        val harness = Harness()
        try {
            test(harness)
        } finally {
            onMain { harness.scheduler.cancel() }
        }
    }

    private fun onMain(action: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private class Harness {
        val handler = Handler(Looper.getMainLooper())
        val captures = AtomicInteger()
        val completed = CountDownLatch(1)
        var lastPosted: Runnable? = null
        val scheduler = AccessibilityEventCaptureScheduler(
            postDelayed = { callback ->
                check(Looper.myLooper() == Looper.getMainLooper())
                lastPosted = callback
                handler.postDelayed(callback, 100L)
            },
            removeCallbacks = handler::removeCallbacks,
            capture = {
                check(Looper.myLooper() == Looper.getMainLooper())
                captures.incrementAndGet()
                completed.countDown()
            },
        )
    }
}
