package ai.hans.standard.voice.android

import ai.hans.standard.voice.RecordingId
import ai.hans.standard.voice.RecordingState
import ai.hans.standard.voice.RecordingStopReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class HansDictationDeliveryFeedbackTest {
    @Before
    fun before() = HansDictationRuntime.resetIdle()

    @After
    fun after() = HansDictationRuntime.resetIdle()

    @Test
    fun completedTranscriptionIsNotSentUntilCorrelatedReceipt() {
        HansDictationRuntime.publish(RecordingState.Completed(RecordingId(1), RecordingStopReason.USER))
        assertEquals(DictationUiPhase.WAITING_TO_SEND, HansDictationRuntime.snapshotUi().phase)
        HansDictationRuntime.awaitDelivery(RecordingId(1), "draft-one")
        HansDictationRuntime.confirmDelivery("unrelated")
        assertEquals(DictationUiPhase.WAITING_TO_SEND, HansDictationRuntime.snapshotUi().phase)
        HansDictationRuntime.confirmDelivery("draft-one")
        assertEquals(DictationUiPhase.SENT, HansDictationRuntime.snapshotUi().phase)
    }

    @Test
    fun receiptForPreviousDictationCannotInterruptSecondRecording() {
        HansDictationRuntime.publish(RecordingState.Completed(RecordingId(1), RecordingStopReason.USER))
        HansDictationRuntime.awaitDelivery(RecordingId(1), "draft-one")
        HansDictationRuntime.publish(RecordingState.AwaitingAudioFocus(RecordingId(2), 1))
        HansDictationRuntime.confirmDelivery("draft-one")
        assertEquals(DictationUiPhase.PREPARING, HansDictationRuntime.snapshotUi().phase)
        assertEquals(RecordingId(2), HansDictationRuntime.snapshotUi().activeRecordingId)
    }
}
