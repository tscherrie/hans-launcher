package ai.hans.standard.voice.android

import ai.hans.standard.voice.RecordingId
import ai.hans.standard.voice.RecordingState
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Non-billable contract checks; never opens a microphone, account, or native runtime. */
@RunWith(AndroidJUnit4::class)
class CodexDictationIntegrationAndroidTest {
    @Before fun before() = HansDictationRuntime.resetIdle()
    @After fun after() = HansDictationRuntime.resetIdle()

    @Test fun nativeCompletionIsNotALocalSendQueueOrWorkCompletion() {
        val recording = RecordingId(81)
        val phases = mutableListOf<DictationUiPhase>()
        val observer = DictationRuntimeObserver { phases += it.phase }
        HansDictationRuntime.addObserver(observer)
        try {
            HansDictationRuntime.publish(RecordingState.AwaitingAudioFocus(recording, 0))
            HansDictationRuntime.publish(RecordingState.Recording(recording, 1, 120_001, 0, 0))
            HansDictationRuntime.publishPartial(recording, "Synthetic preview")
            HansDictationRuntime.completeNativeSession(recording)
            assertEquals(DictationUiPhase.NATIVE_COMPLETED, HansDictationRuntime.snapshotUi().phase)
            assertEquals("", HansDictationRuntime.snapshotUi().provisionalTranscript)
            assertFalse(HansDictationRuntime.snapshot().recordingActive)
            assertFalse(phases.contains(DictationUiPhase.SENT))
            assertFalse(phases.contains(DictationUiPhase.WAITING_TO_SEND))
        } finally { HansDictationRuntime.removeObserver(observer) }
    }

    @Test fun boundedCaptureAndSpellingHintsNeedNoApiCredential() {
        assertTrue(CodexDictationIntegration.enabled)
        assertFalse(CodexDictationIntegration.continuousDictation)
        assertEquals(24_000, CodexDictationIntegration.recordingConfig.audioFormat.sampleRateHz)
        assertEquals(500L, CodexDictationIntegration.recordingConfig.chunkDurationMillis)
        assertEquals(120_000L, CodexDictationIntegration.recordingConfig.maximumDurationMillis)
        assertTrue(CodexDictationIntegration.instructions(listOf("Jeremias")).contains("[\"Jeremias\"]"))
    }

    @Test fun muteKeepsNativeDictationActiveAndCannotChangeAnotherRecording() {
        val recording = RecordingId(83)
        HansDictationRuntime.publish(RecordingState.AwaitingAudioFocus(recording, 0))
        HansDictationRuntime.confirmInputMuted(recording, true)
        assertTrue(HansDictationRuntime.snapshotUi().inputMuted)
        HansDictationRuntime.publish(RecordingState.Recording(recording, 1, 120_001, 0, 0))
        assertTrue(HansDictationRuntime.snapshotUi().inputMuted)
        assertTrue(HansDictationRuntime.snapshot().recordingActive)
        HansDictationRuntime.confirmInputMuted(RecordingId(82), false)
        assertTrue(HansDictationRuntime.snapshotUi().inputMuted)
        HansDictationRuntime.confirmInputMuted(recording, false)
        assertFalse(HansDictationRuntime.snapshotUi().inputMuted)
        assertTrue(HansDictationRuntime.snapshot().recordingActive)
        HansDictationRuntime.confirmInputMuted(recording, true)
        HansDictationRuntime.completeNativeSession(recording)
        assertFalse(HansDictationRuntime.snapshotUi().inputMuted)
        assertFalse(HansDictationRuntime.snapshot().recordingActive)
    }
}
