package ai.hans.standard.voice.android

import ai.hans.standard.voice.RecordingId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationCommandRetirementTest {
    @Test fun lateOldDeliveryCannotRetireANewerQueuedStart() {
        val fence = DictationCommandRetirement()
        val first = RecordingId(1)
        val next = RecordingId(2)
        fence.started(first, 10) { true }
        fence.terminal(first)
        // The second START is accepted while its coordinator work is still queued behind
        // old delivery. Its Android command ID, not the lagging UI snapshot, owns the service.
        fence.started(next, 11) { true }
        assertTrue(fence.claimDelivery(first))
        val oldRetirementCommand = fence.takeRetirementStartId(first)
        assertEquals(10, oldRetirementCommand)
        assertEquals(next, fence.activeRecordingId())
        assertFalse("Android must ignore retirement of the older startId", oldRetirementCommand == 11)
        fence.terminal(next)
        assertEquals(11, fence.takeRetirementStartId(next))
    }

    @Test fun lateOldStopCannotBorrowTheNewRecordingCommandId() {
        val fence = DictationCommandRetirement()
        val first = RecordingId(3)
        val next = RecordingId(4)
        fence.started(first, 20) { true }
        fence.terminal(first)
        fence.started(next, 21) { true }
        assertFalse(fence.associateCommand(first, 22))
        assertEquals(20, fence.takeRetirementStartId(first))
        assertTrue(fence.associateCommand(next, 23))
        fence.terminal(next)
        assertEquals(23, fence.takeRetirementStartId(next))
    }

    @Test fun stopAndIgnoredFinalizingToggleExtendOnlyTheirOwnRetirementFence() {
        val fence = DictationCommandRetirement()
        val id = RecordingId(5)
        fence.started(id, 30) { true }
        assertTrue(fence.associateCommand(id, 31)) // STOP
        assertTrue(fence.associateCommand(id, 32)) // FINALIZING toggle: command ownership only
        assertNull(fence.takeRetirementStartId(id))
        assertEquals(id, fence.activeRecordingId())
        fence.terminal(id)
        assertFalse(fence.associateCommand(id, 33))
        assertEquals(32, fence.takeRetirementStartId(id))
        assertNull(fence.takeRetirementStartId(id))
    }

    @Test fun earlyAsyncFailureCanBeBoundAfterItsTerminalPublication() {
        val fence = DictationCommandRetirement()
        val id = RecordingId(6)
        fence.terminal(id)
        assertNull(fence.takeRetirementStartId(id))
        fence.started(id, 40) { true }
        assertNull(fence.activeRecordingId())
        assertEquals(40, fence.takeRetirementStartId(id))
    }

    @Test fun originalDeliveryLeaseCannotBecomeTheNewAccountOrContextLease() {
        val fence = DictationCommandRetirement()
        var originalContextValid = true
        val first = RecordingId(7)
        fence.started(first, 50) { originalContextValid }
        fence.terminal(first)
        originalContextValid = false
        val next = RecordingId(8)
        fence.started(next, 51) { true }
        assertFalse(checkNotNull(fence.admission(first)).invoke())
        assertTrue(checkNotNull(fence.admission(next)).invoke())
        assertEquals(next, fence.activeRecordingId())
    }

    @Test fun duplicateDeliveryOrRetiredCallbacksNeverSubmitTwice() {
        val fence = DictationCommandRetirement()
        val id = RecordingId(9)
        fence.started(id, 60) { true }
        fence.terminal(id)
        assertTrue(fence.claimDelivery(id))
        assertFalse(fence.claimDelivery(id))
        assertEquals(60, fence.takeRetirementStartId(id))
        assertFalse(fence.claimDelivery(id))
        assertNull(fence.admission(id))
    }

    @Test fun forgottenOldCallbacksAreBoundedButTheActiveRecordingIsRetained() {
        val fence = DictationCommandRetirement(maximumEntries = 3)
        val active = RecordingId(100)
        fence.started(active, 70) { true }
        repeat(20) { index -> fence.terminal(RecordingId(index.toLong() + 200)) }
        assertEquals(3, fence.entryCount())
        assertEquals(active, fence.activeRecordingId())
        assertTrue(checkNotNull(fence.admission(active)).invoke())
        fence.clear()
        assertEquals(0, fence.entryCount())
        assertNull(fence.activeRecordingId())
        assertNull(fence.takeRetirementStartId(active))
    }

    @Test fun cancelledStartupIdleRetiresItsOwnTargetNotANewerQueuedRecording() {
        val fence = DictationCommandRetirement()
        val cancelled = RecordingId(10)
        val next = RecordingId(11)
        fence.started(cancelled, 80) { true }
        fence.associateCommand(cancelled, 81)
        assertTrue(fence.expectStartupCancellation(cancelled))
        // Simulate delayed Idle evidence after another command has acquired the newer slot.
        fence.started(next, 82) { true }
        assertEquals(cancelled, fence.takeIdleStartupCancellation())
        assertEquals(81, fence.takeRetirementStartId(cancelled))
        assertEquals(next, fence.activeRecordingId())
        assertNull(fence.takeIdleStartupCancellation())
    }

    @Test fun completionBeforeCaptureNeedsRetirementWithoutATranscriptCallback() {
        val fence = DictationCommandRetirement()
        val id = RecordingId(12)
        fence.started(id, 90) { true }
        assertTrue(fence.expectStartupCancellation(id))
        assertTrue(fence.completedWithoutTranscript(id))
        fence.terminal(id)
        assertNull(fence.takeIdleStartupCancellation())
        assertEquals(90, fence.takeRetirementStartId(id))
        assertNull(fence.activeRecordingId())
    }

    @Test fun successfulFinalizationClearsStartupMarkerAndRetainsOriginalDeliveryLease() {
        val fence = DictationCommandRetirement()
        val id = RecordingId(13)
        fence.started(id, 100) { true }
        assertTrue(fence.expectStartupCancellation(id))
        fence.finalizing(id)
        assertFalse(fence.completedWithoutTranscript(id))
        assertFalse(fence.expectStartupCancellation(id))
        assertNull(fence.takeIdleStartupCancellation())
        fence.terminal(id)
        assertTrue(checkNotNull(fence.admission(id)).invoke())
        assertTrue(fence.claimDelivery(id))
        assertEquals(100, fence.takeRetirementStartId(id))
    }

    @Test fun failedStartupClearsOnlyItsOwnCancellationMarker() {
        val fence = DictationCommandRetirement()
        val first = RecordingId(14)
        val next = RecordingId(15)
        fence.started(first, 110) { true }
        fence.expectStartupCancellation(first)
        fence.started(next, 111) { true }
        fence.expectStartupCancellation(next)
        fence.terminal(first)
        assertEquals(next, fence.takeIdleStartupCancellation())
        assertEquals(110, fence.takeRetirementStartId(first))
        assertEquals(111, fence.takeRetirementStartId(next))
    }
}
