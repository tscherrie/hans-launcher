package ai.hans.standard.phone.accessibility.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityEventCaptureSchedulerTest {
    @Test
    fun successfulFreshPublicationConsumesOnlyAlreadyCoveredEventRefresh() {
        val harness = Harness()
        harness.scheduler.onEvent()
        harness.scheduler.onEvent()
        val oldCallback = harness.queued.single()
        val capture = harness.scheduler.captureStarted()

        harness.scheduler.capturePublished(capture)

        assertTrue(harness.queued.isEmpty())
        assertEquals(listOf(oldCallback), harness.removed)
        oldCallback.run() // A canceled callback cannot supersede the fresh screenshot's frame.
        assertEquals(0, harness.captures)
    }

    @Test
    fun failedFreshCapturePreservesPendingRefreshAndDoesNotCreateRetryLoop() {
        val harness = Harness()
        harness.scheduler.onEvent()
        harness.scheduler.captureStarted()
        // Failure intentionally has no publication acknowledgment.
        assertEquals(1, harness.queued.size)
        assertTrue(harness.removed.isEmpty())

        harness.runNext()

        assertEquals(1, harness.captures)
        assertTrue(harness.queued.isEmpty())
    }

    @Test
    fun eventArrivingAfterCaptureStartsIsNotCoveredByThatPublication() {
        val harness = Harness()
        harness.scheduler.onEvent()
        val capture = harness.scheduler.captureStarted()
        harness.scheduler.onEvent()

        harness.scheduler.capturePublished(capture)

        assertEquals(1, harness.queued.size)
        assertTrue(harness.removed.isEmpty())
        harness.runNext()
        assertEquals(1, harness.captures)
    }

    @Test
    fun laterEventAfterSuccessfulPublicationSchedulesNormally() {
        val harness = Harness()
        harness.scheduler.onEvent()
        harness.scheduler.capturePublished(harness.scheduler.captureStarted())
        harness.scheduler.onEvent()

        assertEquals(1, harness.queued.size)
        harness.runNext()
        assertEquals(1, harness.captures)
        assertTrue(harness.queued.isEmpty())
    }

    @Test
    fun eventDuringScheduledCaptureRetainsItsOwnFollowup() {
        val harness = Harness()
        harness.duringCapture = {
            val capture = harness.scheduler.captureStarted()
            harness.scheduler.onEvent()
            harness.scheduler.capturePublished(capture)
        }
        harness.scheduler.onEvent()

        harness.runNext()

        assertEquals(1, harness.captures)
        assertEquals(1, harness.queued.size)
        harness.duringCapture = {}
        harness.runNext()
        assertEquals(2, harness.captures)
        assertTrue(harness.queued.isEmpty())
    }

    @Test
    fun disconnectInvalidatesOldCaptureAndOldCallbackWithoutConsumingNewConnectionEvent() {
        val harness = Harness()
        harness.scheduler.onEvent()
        val oldCallback = harness.queued.single()
        val oldCapture = harness.scheduler.captureStarted()
        harness.scheduler.cancel()
        assertTrue(harness.queued.isEmpty())
        harness.scheduler.onEvent()

        harness.scheduler.capturePublished(oldCapture)
        oldCallback.run()

        assertEquals(0, harness.captures)
        assertEquals(1, harness.queued.size)
        harness.runNext()
        assertEquals(1, harness.captures)
    }

    @Test
    fun rejectedHandlerPostAllowsTheNextEventToScheduleAgain() {
        val harness = Harness()
        harness.acceptPosts = false
        harness.scheduler.onEvent()
        assertTrue(harness.queued.isEmpty())
        harness.acceptPosts = true
        harness.scheduler.onEvent()

        harness.runNext()

        assertEquals(1, harness.captures)
        assertTrue(harness.queued.isEmpty())
    }

    @Test
    fun noEventsMeansNoScheduledCaptureAndCancelIsIdempotent() {
        val harness = Harness()
        harness.scheduler.capturePublished(harness.scheduler.captureStarted())
        harness.scheduler.cancel()
        harness.scheduler.cancel()
        assertTrue(harness.queued.isEmpty())
        assertTrue(harness.removed.isEmpty())
        assertEquals(0, harness.captures)
    }

    @Test
    fun serviceAcknowledgesOnlyFreshPublishedFramesAndKeepsStrictPostActionBarrier() {
        val service = sequenceOf(
            File("src/main/java/ai/hans/standard/phone/accessibility/android/HansAccessibilityService.kt"),
            File("android/app/src/main/java/ai/hans/standard/phone/accessibility/android/HansAccessibilityService.kt"),
        ).first(File::isFile).readText()
        val capture = service.substringAfter("private fun captureAndPublishOnMain(): SemanticUiSnapshot? {")
            .substringBefore("private fun resolveActiveApplicationRoot()")
        val started = capture.indexOf("eventCaptureScheduler.captureStarted()")
        val read = capture.indexOf("resolveActiveApplicationRoot()")
        val published = capture.indexOf("if (!published)")
        val acknowledged = capture.indexOf("eventCaptureScheduler.capturePublished(coveredEvents)")
        assertTrue(started >= 0 && read > started && published > read && acknowledged > published)
        assertTrue(capture.contains("check(Looper.myLooper() == Looper.getMainLooper())"))
        assertEquals(1, Regex("eventCaptureScheduler.capturePublished").findAll(service).count())
        assertFalse(service.substringAfter("private fun clearSnapshot(")
            .substringBefore("private fun notifySnapshotChanged()")
            .contains("eventCaptureScheduler"))

        // Coalescing a pre-action refresh must never make that frame a post-action result.
        val afterAction = service.substringAfter("override fun snapshotAfterAction(")
            .substringBefore("override fun resolveTargetAfterAction(")
        assertTrue(afterAction.contains("current.correlation.snapshotId.value > baseline.value"))
        assertTrue(afterAction.contains("captured.correlation.snapshotId.value <= baseline.value"))
        assertTrue(afterAction.contains("current.correlation.sessionId == before.sessionId"))
        assertTrue(afterAction.contains("captured.correlation.sessionId != before.sessionId"))
        assertTrue(afterAction.contains("snapshotMonitor.wait(remaining)"))
        assertTrue(afterAction.contains("callOnMain { captureAndPublishOnMain() }"))
        assertTrue(service.contains("const val EVENT_REFRESH_WAIT_MILLIS = 500L"))
        assertTrue(service.substringAfter("private fun tearDownSession()")
            .substringBefore("private fun setActionKeyFilteringEnabled(")
            .contains("eventCaptureScheduler.cancel()"))
    }

    private class Harness {
        val queued = mutableListOf<Runnable>()
        val removed = mutableListOf<Runnable>()
        var captures = 0
        var acceptPosts = true
        var duringCapture: () -> Unit = {}
        val scheduler = AccessibilityEventCaptureScheduler(
            postDelayed = { callback ->
                if (acceptPosts) queued.add(callback)
                acceptPosts
            },
            removeCallbacks = { callback ->
                removed.add(callback)
                queued.remove(callback)
                Unit
            },
            capture = { captures++; duringCapture() },
        )

        fun runNext() = queued.removeAt(0).run()
    }
}
