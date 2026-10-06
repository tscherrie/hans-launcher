package ai.hans.standard.ui

import ai.hans.standard.phone.keys.ActionKeyCommand
import ai.hans.standard.voice.android.DictationUiPhase
import ai.hans.standard.voice.realtime.LiveVoicePhase
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Offline transitions only: no microphone, connection, permission change or real task. */
@RunWith(AndroidJUnit4::class)
class VoiceInputTransitionAndroidTest {
    @Test fun finalizingNeverReleasesVoiceOrStartsAnotherRecording() {
        assertTrue(VoiceInputTransitionPolicy.dictationOwnsVoice(DictationUiPhase.FINALIZING))
        listOf(ActionKeyCommand.StartDictation, ActionKeyCommand.StopDictation, ActionKeyCommand.ToggleDictation).forEach {
            assertEquals(VoiceInputCommandAction.SHOW_PROCESSING,
                VoiceInputTransitionPolicy.command(it, DictationUiPhase.FINALIZING, false))
        }
    }

    @Test fun bothCompletionModesReleaseTheOwnerWithoutWaitingForTaskCompletion() {
        listOf(DictationUiPhase.SENT, DictationUiPhase.NATIVE_COMPLETED, DictationUiPhase.WAITING_TO_SEND)
            .forEach { assertFalse(VoiceInputTransitionPolicy.dictationOwnsVoice(it)) }
    }

    @Test fun pendingSwitchIsCancelledByReleaseAndNeedsBothLiveReleaseSignals() {
        assertEquals(VoiceInputCommandAction.CANCEL_PENDING_START,
            VoiceInputTransitionPolicy.command(ActionKeyCommand.StopDictation, DictationUiPhase.IDLE, true))
        assertTrue(VoiceInputTransitionPolicy.liveOwnsVoice(LiveVoicePhase.IDLE, true))
        assertTrue(VoiceInputTransitionPolicy.liveOwnsVoice(LiveVoicePhase.STOPPED, true))
        assertFalse(VoiceInputTransitionPolicy.liveOwnsVoice(LiveVoicePhase.STOPPED, false))
    }
}
