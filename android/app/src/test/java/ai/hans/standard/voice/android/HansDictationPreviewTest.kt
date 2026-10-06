package ai.hans.standard.voice.android

import ai.hans.standard.voice.RecordingFailure
import ai.hans.standard.voice.RecordingId
import ai.hans.standard.voice.RecordingState
import ai.hans.standard.voice.RecordingStopReason
import ai.hans.standard.voice.stt.SttTranscriptionDelay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HansDictationPreviewTest {
    private val first = RecordingId(1)
    private val second = RecordingId(2)

    @Before
    fun before() = HansDictationRuntime.resetIdle()

    @After
    fun after() = HansDictationRuntime.resetIdle()

    @Test
    fun muteKeepsVoiceOwnershipAndPreviewAndResetsOnlyForTheMatchingSession() {
        HansDictationRuntime.publish(RecordingState.AwaitingAudioFocus(first, 0))
        HansDictationRuntime.confirmInputMuted(first, true)
        assertTrue(HansDictationRuntime.snapshotUi().inputMuted)
        listen(first)
        HansDictationRuntime.publishPartial(first, "Mein Auftrag")
        val lifecycle = HansDictationRuntime.snapshot()
        assertTrue(HansDictationRuntime.snapshotUi().inputMuted)
        HansDictationRuntime.confirmInputMuted(second, false)
        assertTrue(HansDictationRuntime.snapshotUi().inputMuted)
        HansDictationRuntime.confirmInputMuted(first, false)
        assertFalse(HansDictationRuntime.snapshotUi().inputMuted)
        assertEquals(lifecycle, HansDictationRuntime.snapshot())
        assertEquals("Mein Auftrag", HansDictationRuntime.snapshotUi().provisionalTranscript)
        HansDictationRuntime.confirmInputMuted(first, true)
        HansDictationRuntime.completeNativeSession(first)
        HansDictationRuntime.confirmInputMuted(first, true)
        assertFalse(HansDictationRuntime.snapshotUi().inputMuted)
        assertFalse(HansDictationRuntime.snapshot().recordingActive)
        listen(second)
        assertFalse(HansDictationRuntime.snapshotUi().inputMuted)
    }

    @Test
    fun partialsReplaceThePreviewWithoutChangingRecordingLifecycleOrConfirmingDelay() {
        listen(first)
        val lifecycle = HansDictationRuntime.snapshot()
        HansDictationRuntime.publishPartial(first, "Bitte den Entwurf")
        HansDictationRuntime.publishPartial(first, "Bitte den Entwurf korrigieren")

        val state = HansDictationRuntime.snapshotUi()
        assertEquals("Bitte den Entwurf korrigieren", state.provisionalTranscript)
        assertEquals(DictationUiPhase.LISTENING, state.phase)
        assertEquals(first, state.activeRecordingId)
        assertEquals(lifecycle, HansDictationRuntime.snapshot())
        assertNull(state.confirmedTranscriptionDelay)
    }

    @Test
    fun blankDuplicateAndWrongRecordingPartialsDoNotPublishAnotherSnapshot() {
        listen(first)
        val observed = mutableListOf<DictationRuntimeSnapshot>()
        val observer = DictationRuntimeObserver { observed += it }
        HansDictationRuntime.addObserver(observer)
        try {
            HansDictationRuntime.publishPartial(first, "Ein Zwischenstand")
            val accepted = HansDictationRuntime.snapshotUi()
            HansDictationRuntime.publishPartial(first, "Ein Zwischenstand")
            HansDictationRuntime.publishPartial(first, " \n\t")
            HansDictationRuntime.publishPartial(second, "Fremde Aufnahme")

            assertEquals(accepted, HansDictationRuntime.snapshotUi())
            assertEquals(2, observed.size) // Initial snapshot plus one genuine update.
        } finally {
            HansDictationRuntime.removeObserver(observer)
        }
    }

    @Test
    fun previewIsRejectedBeforeCaptureAndAfterReset() {
        HansDictationRuntime.publish(RecordingState.AwaitingAudioFocus(first, 0))
        val preparing = HansDictationRuntime.snapshotUi()
        HansDictationRuntime.publishPartial(first, "Noch kein Mikrofon")
        assertEquals(preparing, HansDictationRuntime.snapshotUi())

        listen(first)
        HansDictationRuntime.publishPartial(first, "Kurz sichtbar")
        HansDictationRuntime.confirmTranscriptionDelay(first, SttTranscriptionDelay.LOW)
        HansDictationRuntime.resetIdle()
        val idle = HansDictationRuntime.snapshotUi()
        HansDictationRuntime.publishPartial(first, "Verspäteter Text")
        HansDictationRuntime.confirmTranscriptionDelay(first, SttTranscriptionDelay.MINIMAL)

        assertEquals(idle, HansDictationRuntime.snapshotUi())
        assertEquals("", idle.provisionalTranscript)
        assertNull(idle.confirmedTranscriptionDelay)
        assertFalse(HansDictationRuntime.snapshot().recordingActive)
    }

    @Test
    fun sameRecordingKeepsPreviewAndConfirmedDelayWhileFinalizing() {
        listen(first)
        HansDictationRuntime.publishPartial(first, "Bis zum Ende")
        HansDictationRuntime.confirmTranscriptionDelay(first, SttTranscriptionDelay.MINIMAL)
        HansDictationRuntime.publish(RecordingState.Stopping(first, RecordingStopReason.USER, 2, 1))
        assertEquals("Bis zum Ende", HansDictationRuntime.snapshotUi().provisionalTranscript)
        HansDictationRuntime.publish(
            RecordingState.Finalizing(first, RecordingStopReason.USER, 2, 0, true),
        )
        HansDictationRuntime.publishPartial(first, "Bis zum Ende weiterarbeiten")

        val state = HansDictationRuntime.snapshotUi()
        assertEquals(DictationUiPhase.FINALIZING, state.phase)
        assertEquals("Bis zum Ende weiterarbeiten", state.provisionalTranscript)
        assertEquals(SttTranscriptionDelay.MINIMAL, state.confirmedTranscriptionDelay)
    }

    @Test
    fun everyTerminalStateClearsPreviewAndRejectsLateTextAndDelayConfirmation() {
        val terminals = listOf(
            RecordingState.Idle,
            RecordingState.Completed(first, RecordingStopReason.USER),
            RecordingState.Failed(first, RecordingFailure.NETWORK_UNAVAILABLE),
        )
        terminals.forEach { terminal ->
            listen(first)
            HansDictationRuntime.publishPartial(first, "Nicht endgültiger Text")
            HansDictationRuntime.confirmTranscriptionDelay(first, SttTranscriptionDelay.LOW)
            HansDictationRuntime.publish(terminal)
            val cleared = HansDictationRuntime.snapshotUi()
            HansDictationRuntime.publishPartial(first, "Verspäteter Text")
            HansDictationRuntime.confirmTranscriptionDelay(first, SttTranscriptionDelay.MINIMAL)

            assertEquals("", cleared.provisionalTranscript)
            assertNull(cleared.confirmedTranscriptionDelay)
            assertEquals(cleared, HansDictationRuntime.snapshotUi())
            assertFalse(HansDictationRuntime.snapshot().recordingActive)
        }
    }

    @Test
    fun newRecordingCannotInheritOrReceiveThePreviousRecordingsPreviewOrConfirmation() {
        listen(first)
        HansDictationRuntime.publishPartial(first, "Alter Zwischenstand")
        HansDictationRuntime.confirmTranscriptionDelay(first, SttTranscriptionDelay.MINIMAL)
        HansDictationRuntime.publish(RecordingState.AwaitingAudioFocus(second, 20))
        assertEquals("", HansDictationRuntime.snapshotUi().provisionalTranscript)
        assertNull(HansDictationRuntime.snapshotUi().confirmedTranscriptionDelay)
        listen(second)
        val newRecording = HansDictationRuntime.snapshotUi()
        HansDictationRuntime.publishPartial(first, "Verspätete alte Antwort")
        HansDictationRuntime.confirmTranscriptionDelay(first, SttTranscriptionDelay.LOW)
        assertEquals(newRecording, HansDictationRuntime.snapshotUi())

        HansDictationRuntime.publishPartial(second, "Neuer Zwischenstand")
        assertEquals("Neuer Zwischenstand", HansDictationRuntime.snapshotUi().provisionalTranscript)
    }

    @Test
    fun boundedPreviewKeepsTheNewestTextAndDeduplicatesDifferentDiscardedPrefixes() {
        listen(first)
        val suffix = "x".repeat(HansDictationRuntime.MAX_PREVIEW_CHARACTERS)
        HansDictationRuntime.publishPartial(first, "Alte Einleitung $suffix")
        val bounded = HansDictationRuntime.snapshotUi()
        assertEquals(suffix, bounded.provisionalTranscript)
        assertEquals(4_000, bounded.provisionalTranscript.length)

        HansDictationRuntime.publishPartial(first, "Andere unsichtbare Einleitung $suffix")
        assertEquals(bounded, HansDictationRuntime.snapshotUi())
    }

    @Test
    fun previewTruncationNeverStartsWithHalfOfASupplementaryUnicodeCharacter() {
        listen(first)
        val suffix = "x".repeat(HansDictationRuntime.MAX_PREVIEW_CHARACTERS - 1)
        HansDictationRuntime.publishPartial(first, "\uD83D\uDE80$suffix")
        assertEquals(suffix, HansDictationRuntime.snapshotUi().provisionalTranscript)
        assertFalse(HansDictationRuntime.snapshotUi().provisionalTranscript.first().isLowSurrogate())

        val completePair = "\uD83D\uDE80" + "y".repeat(HansDictationRuntime.MAX_PREVIEW_CHARACTERS - 2)
        HansDictationRuntime.publishPartial(first, "discarded$completePair")
        assertEquals(completePair, HansDictationRuntime.snapshotUi().provisionalTranscript)
    }

    @Test
    fun confirmedDelayRequiresTheMatchingActiveRecordingAndDuplicateAcksDoNotPublish() {
        HansDictationRuntime.confirmTranscriptionDelay(first, SttTranscriptionDelay.MINIMAL)
        assertNull(HansDictationRuntime.snapshotUi().confirmedTranscriptionDelay)
        HansDictationRuntime.publish(RecordingState.AwaitingAudioFocus(first, 0))
        HansDictationRuntime.confirmTranscriptionDelay(second, SttTranscriptionDelay.MINIMAL)
        assertNull(HansDictationRuntime.snapshotUi().confirmedTranscriptionDelay)

        // Network warm-up may be acknowledged before the microphone is released.
        HansDictationRuntime.confirmTranscriptionDelay(first, SttTranscriptionDelay.LOW)
        val acknowledged = HansDictationRuntime.snapshotUi()
        HansDictationRuntime.confirmTranscriptionDelay(first, SttTranscriptionDelay.LOW)
        assertEquals(acknowledged, HansDictationRuntime.snapshotUi())
        listen(first)
        assertEquals(SttTranscriptionDelay.LOW, HansDictationRuntime.snapshotUi().confirmedTranscriptionDelay)
        assertTrue(HansDictationRuntime.snapshot().recordingActive)
    }

    private fun listen(id: RecordingId) {
        HansDictationRuntime.publish(RecordingState.Recording(id, 0, 60_000, 0, 0))
    }
}
