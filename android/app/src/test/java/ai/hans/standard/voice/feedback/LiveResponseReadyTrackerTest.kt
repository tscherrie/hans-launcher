package ai.hans.standard.voice.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LiveResponseReadyTrackerTest {
    @Test
    fun createdAloneDoesNotEmitButFirstMatchingDeltaDoesIncludingFirstSessionAnswer() {
        val tracker = LiveResponseReadyTracker("session-one")
        tracker.started(1, "response-one")
        assertNull(tracker.text(1, "other", "Hallo"))
        assertNull(tracker.text(1, null, "Hallo"))
        assertNull(tracker.text(1, "response-one", "  "))
        val first = tracker.text(1, "response-one", "Hallo")
        assertNotNull(first)
        assertEquals("session-one", first!!.sessionInstanceId)
        assertEquals(1L, first.generation)
        assertEquals("response-one", first.responseId)
        assertNull(tracker.text(1, "response-one", "Wie geht es dir?"))
        tracker.started(1, "response-one")
        assertNull(tracker.text(1, "response-one", "Hallo noch einmal"))
    }

    @Test
    fun finalOnlyTranscriptIsValidButToolOnlyAndTerminalReplayAreSilent() {
        val tracker = LiveResponseReadyTracker("session-one")
        tracker.started(1, "tool-only")
        tracker.finished(1, "tool-only")
        assertNull(tracker.text(1, "tool-only", "Nachgereichter Text"))
        tracker.started(1, "final-answer")
        assertNotNull(tracker.text(1, "final-answer", "Fertig."))
        tracker.finished(1, "final-answer")
        assertNull(tracker.text(1, "final-answer", "Fertig."))
    }

    @Test
    fun explicitCancelAndTransportBoundaryRejectLateOrReplayedOutput() {
        val tracker = LiveResponseReadyTracker("session-one")
        tracker.started(1, "cancelled")
        tracker.cancelActive()
        assertNull(tracker.text(1, "cancelled", "Zu spät"))
        tracker.started(2, "cancelled")
        assertNull(tracker.text(2, "cancelled", "Replay"))
        tracker.started(2, "new")
        assertNull(tracker.text(1, "new", "Veraltete Verbindung"))
        assertNotNull(tracker.text(2, "new", "Neue Antwort"))
    }

    @Test
    fun supersededResponseAndUncorrelatedOrInvalidIdsDoNotStartFeedback() {
        val tracker = LiveResponseReadyTracker("session-one")
        tracker.started(1, "old")
        tracker.started(1, "new")
        assertNull(tracker.text(1, "old", "Alt"))
        assertNotNull(tracker.text(1, "new", "Neu"))
        tracker.started(1, "old")
        assertNull(tracker.text(1, "old", "Replay"))
        listOf(null, "", "bad\nresponse", "r".repeat(513)).forEach { invalid ->
            tracker.started(2, invalid)
            assertNull(tracker.text(2, invalid, "Hallo"))
        }
    }

    @Test
    fun terminalWithoutResponseIdStillClosesTheMatchedActiveResponse() {
        val tracker = LiveResponseReadyTracker("session-one")
        tracker.started(1, "one")
        tracker.finished(1, null)
        assertNull(tracker.text(1, "one", "Spät"))
        tracker.started(1, "two")
        assertNotNull(tracker.text(1, "two", "Aktuell"))
    }
}
