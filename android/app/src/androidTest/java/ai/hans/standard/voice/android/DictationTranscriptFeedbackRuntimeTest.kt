package ai.hans.standard.voice.android

import ai.hans.standard.voice.RecordingFailure
import ai.hans.standard.voice.RecordingId
import ai.hans.standard.voice.RecordingState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Real process-local start publication, before any native session or microphone exists. */
class DictationTranscriptFeedbackRuntimeTest {
    @Before fun prepare() = HansDictationRuntime.resetIdle()
    @After fun release() = HansDictationRuntime.resetIdle()

    @Test fun acceptedPressShowsWaitingBeforeTheAudioBarrierAndNativeConnection() {
        HansDictationRuntime.publish(RecordingState.AwaitingAudioFocus(RecordingId(701), 0))
        assertEquals(DictationUiPhase.PREPARING, HansDictationRuntime.snapshotUi().phase)
        assertTrue(HansDictationRuntime.snapshotUi().awaitingFirstUserTranscript)
        assertEquals("", HansDictationRuntime.snapshotUi().provisionalTranscript)
    }

    @Test fun whitespaceAndStaleRecordingCannotRetireTheCurrentWait() {
        val id = RecordingId(702)
        HansDictationRuntime.publish(RecordingState.AwaitingAudioFocus(id, 0))
        HansDictationRuntime.observeNativeUserTranscript(id, " \n\t")
        HansDictationRuntime.observeNativeUserTranscript(RecordingId(701), "Old text")
        HansDictationRuntime.confirmNativeTranscriptAwaiting(RecordingId(701), false)
        assertTrue(HansDictationRuntime.snapshotUi().awaitingFirstUserTranscript)
        HansDictationRuntime.observeNativeUserTranscript(id, "First words")
        assertFalse(HansDictationRuntime.snapshotUi().awaitingFirstUserTranscript)
        assertEquals("", HansDictationRuntime.snapshotUi().provisionalTranscript)
    }

    @Test fun listeningMuteAndNativeReplayNeverRearmACompletedWait() {
        val id = RecordingId(703)
        HansDictationRuntime.publish(RecordingState.AwaitingAudioFocus(id, 0))
        HansDictationRuntime.publish(RecordingState.Recording(id, 1, 120_001, 0, 0))
        assertTrue(HansDictationRuntime.snapshotUi().awaitingFirstUserTranscript)
        HansDictationRuntime.observeNativeUserTranscript(id, "Hello")
        HansDictationRuntime.confirmInputMuted(id, true)
        HansDictationRuntime.confirmNativeTranscriptAwaiting(id, true)
        HansDictationRuntime.publish(RecordingState.Recording(id, 2, 120_002, 0, 0))
        assertFalse(HansDictationRuntime.snapshotUi().awaitingFirstUserTranscript)
        assertTrue(HansDictationRuntime.snapshotUi().inputMuted)
    }

    @Test fun failureAndSuccessfulCloseRetireDotsAndTheNextPressStartsFresh() {
        val first = RecordingId(704)
        HansDictationRuntime.publish(RecordingState.AwaitingAudioFocus(first, 0))
        HansDictationRuntime.publish(RecordingState.Failed(first, RecordingFailure.CODEX_LIVE_FAILED))
        assertFalse(HansDictationRuntime.snapshotUi().awaitingFirstUserTranscript)
        val next = RecordingId(705)
        HansDictationRuntime.publish(RecordingState.AwaitingAudioFocus(next, 0))
        assertTrue(HansDictationRuntime.snapshotUi().awaitingFirstUserTranscript)
        HansDictationRuntime.completeNativeSession(next)
        assertFalse(HansDictationRuntime.snapshotUi().awaitingFirstUserTranscript)
    }

    @Test fun retainedClosingOwnerDoesNotPromiseAnotherTranscript() {
        val id = RecordingId(706)
        HansDictationRuntime.publish(RecordingState.AwaitingAudioFocus(id, 0))
        HansDictationRuntime.confirmNativeTranscriptAwaiting(id, false)
        assertEquals(DictationUiPhase.PREPARING, HansDictationRuntime.snapshotUi().phase)
        assertFalse(HansDictationRuntime.snapshotUi().awaitingFirstUserTranscript)
    }

    @Test fun legacyPartialAlsoReplacesWaitingWithoutPersistingNativeInput() {
        val id = RecordingId(707)
        HansDictationRuntime.publish(RecordingState.AwaitingAudioFocus(id, 0))
        HansDictationRuntime.publish(RecordingState.Recording(id, 1, 120_001, 0, 0))
        HansDictationRuntime.publishPartial(id, "First legacy partial")
        assertFalse(HansDictationRuntime.snapshotUi().awaitingFirstUserTranscript)
        assertEquals("First legacy partial", HansDictationRuntime.snapshotUi().provisionalTranscript)
    }
}
