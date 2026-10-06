package ai.hans.standard.ui

import ai.hans.standard.phone.keys.ActionKeyCommand
import ai.hans.standard.voice.android.DictationUiPhase
import ai.hans.standard.voice.realtime.LiveVoicePhase

/** Pure decisions only. The Activity must re-read effective owners before dispatching a start. */
internal object VoiceInputTransitionPolicy {
    fun dictationOwnsVoice(phase: DictationUiPhase): Boolean = when (phase) {
        DictationUiPhase.PREPARING, DictationUiPhase.LISTENING, DictationUiPhase.FINALIZING -> true
        DictationUiPhase.IDLE, DictationUiPhase.WAITING_TO_SEND, DictationUiPhase.SENT,
        DictationUiPhase.NATIVE_COMPLETED, DictationUiPhase.FAILED -> false
    }

    fun dictationCanStopCapture(phase: DictationUiPhase): Boolean =
        phase == DictationUiPhase.PREPARING || phase == DictationUiPhase.LISTENING

    fun liveOwnsVoice(phase: LiveVoicePhase, captureRequestedOrActive: Boolean): Boolean =
        captureRequestedOrActive || phase !in setOf(LiveVoicePhase.IDLE, LiveVoicePhase.STOPPED, LiveVoicePhase.FAILED)

    fun command(
        command: ActionKeyCommand,
        dictationPhase: DictationUiPhase,
        pendingDictationStart: Boolean,
        continuousDictation: Boolean = false,
    ): VoiceInputCommandAction = when {
        command == ActionKeyCommand.ToggleLunaMaxSolUltra -> VoiceInputCommandAction.TOGGLE_MODEL
        pendingDictationStart && command != ActionKeyCommand.StartDictation -> VoiceInputCommandAction.CANCEL_PENDING_START
        pendingDictationStart -> VoiceInputCommandAction.NONE
        dictationPhase == DictationUiPhase.FINALIZING -> VoiceInputCommandAction.SHOW_PROCESSING
        command == ActionKeyCommand.StopDictation -> if (dictationCanStopCapture(dictationPhase)) {
            VoiceInputCommandAction.STOP_CAPTURE
        } else VoiceInputCommandAction.NONE
        dictationCanStopCapture(dictationPhase) -> if (command == ActionKeyCommand.ToggleDictation) {
            if (continuousDictation) VoiceInputCommandAction.TOGGLE_INPUT_MUTED else VoiceInputCommandAction.STOP_CAPTURE
        } else VoiceInputCommandAction.NONE
        else -> VoiceInputCommandAction.REQUEST_START
    }
}

internal enum class VoiceInputCommandAction {
    REQUEST_START, STOP_CAPTURE, TOGGLE_INPUT_MUTED, SHOW_PROCESSING, CANCEL_PENDING_START, TOGGLE_MODEL, NONE,
}
