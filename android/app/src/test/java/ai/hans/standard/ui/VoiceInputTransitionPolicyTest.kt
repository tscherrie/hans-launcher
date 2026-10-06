package ai.hans.standard.ui

import ai.hans.standard.R
import ai.hans.standard.localization.TestResourceTextResolver
import ai.hans.standard.phone.keys.ActionKeyCommand
import ai.hans.standard.voice.android.DictationUiPhase
import ai.hans.standard.voice.realtime.LiveVoicePhase
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class VoiceInputTransitionPolicyTest {
    @Test fun finalizationRetainsVoiceOwnershipAfterThePhysicalMicrophoneHasStopped() {
        assertTrue(VoiceInputTransitionPolicy.dictationOwnsVoice(DictationUiPhase.FINALIZING))
        assertFalse(VoiceInputTransitionPolicy.dictationCanStopCapture(DictationUiPhase.FINALIZING))
    }

    @Test fun nativeAndTranscriptCompletionBothReleaseTheVoiceOwner() {
        val owners = setOf(DictationUiPhase.PREPARING, DictationUiPhase.LISTENING, DictationUiPhase.FINALIZING)
        DictationUiPhase.entries.forEach { phase ->
            assertEquals(phase.name, phase in owners, VoiceInputTransitionPolicy.dictationOwnsVoice(phase))
        }
        listOf(DictationUiPhase.NATIVE_COMPLETED, DictationUiPhase.SENT, DictationUiPhase.WAITING_TO_SEND)
            .forEach { assertFalse(VoiceInputTransitionPolicy.dictationOwnsVoice(it)) }
    }

    @Test fun everyRepeatedCaptureCommandDuringFinalizationOnlyShowsProcessing() {
        listOf(ActionKeyCommand.StartDictation, ActionKeyCommand.ToggleDictation, ActionKeyCommand.StopDictation)
            .forEach { command -> repeat(5) {
                assertEquals(VoiceInputCommandAction.SHOW_PROCESSING, decide(command, DictationUiPhase.FINALIZING))
            } }
    }

    @Test fun releaseOrSecondToggleCancelsPendingSwitchInsteadOfStartingAfterKeyUp() {
        listOf(ActionKeyCommand.StopDictation, ActionKeyCommand.ToggleDictation).forEach { command ->
            assertEquals(VoiceInputCommandAction.CANCEL_PENDING_START, decide(command, DictationUiPhase.IDLE, true))
        }
        assertEquals(VoiceInputCommandAction.NONE, decide(ActionKeyCommand.StartDictation, DictationUiPhase.IDLE, true))
    }

    @Test fun liveStartupRequestOwnsCaptureEvenBeforeFirstNonIdleSnapshot() {
        LiveVoicePhase.entries.forEach { assertTrue(VoiceInputTransitionPolicy.liveOwnsVoice(it, true)) }
    }

    @Test fun terminalLiveSnapshotAloneDoesNotReleaseTheTransport() {
        listOf(LiveVoicePhase.IDLE, LiveVoicePhase.STOPPED, LiveVoicePhase.FAILED).forEach {
            assertTrue(VoiceInputTransitionPolicy.liveOwnsVoice(it, true))
            assertFalse(VoiceInputTransitionPolicy.liveOwnsVoice(it, false))
        }
        assertTrue(VoiceInputTransitionPolicy.liveOwnsVoice(LiveVoicePhase.LISTENING, false))
    }

    @Test fun batchToggleStopsAndDuplicateStartCannotCreateAnotherRecording() {
        listOf(DictationUiPhase.PREPARING, DictationUiPhase.LISTENING).forEach {
            assertEquals(VoiceInputCommandAction.STOP_CAPTURE, decide(ActionKeyCommand.ToggleDictation, it))
            assertEquals(VoiceInputCommandAction.STOP_CAPTURE, decide(ActionKeyCommand.StopDictation, it))
            assertEquals(VoiceInputCommandAction.NONE, decide(ActionKeyCommand.StartDictation, it))
        }
    }

    @Test fun explicitlySelectedContinuousVoiceKeepsItsMuteOnToggleContract() {
        listOf(DictationUiPhase.PREPARING, DictationUiPhase.LISTENING).forEach {
            assertEquals(VoiceInputCommandAction.TOGGLE_INPUT_MUTED,
                VoiceInputTransitionPolicy.command(ActionKeyCommand.ToggleDictation, it, false, true))
        }
    }

    @Test fun terminalDictationAllowsRequestedStartButStopNeverStartsAnything() {
        DictationUiPhase.entries.filterNot(VoiceInputTransitionPolicy::dictationOwnsVoice).forEach {
            assertEquals(VoiceInputCommandAction.REQUEST_START, decide(ActionKeyCommand.StartDictation, it))
            assertEquals(VoiceInputCommandAction.REQUEST_START, decide(ActionKeyCommand.ToggleDictation, it))
            assertEquals(VoiceInputCommandAction.NONE, decide(ActionKeyCommand.StopDictation, it))
        }
    }

    @Test fun modelShortcutNeverCancelsOrTakesOverAnyVoiceTransition() {
        DictationUiPhase.entries.forEach { phase -> listOf(true, false).forEach { pending ->
            assertEquals(VoiceInputCommandAction.TOGGLE_MODEL, decide(ActionKeyCommand.ToggleLunaMaxSolUltra, phase, pending))
        } }
    }

    @Test fun launcherUsesEffectiveReleaseGatesAndNeverDefersAToggle() {
        val source = sequenceOf(File("src/main/java/ai/hans/standard/LauncherActivity.kt"),
            File("android/app/src/main/java/ai/hans/standard/LauncherActivity.kt")).first(File::isFile).readText()
        val snapshots = source.substringAfter("private fun acceptDictationSnapshot(")
            .substringBefore("private fun acceptLiveVoiceSnapshot(")
        assertTrue(snapshots.contains("!VoiceInputTransitionPolicy.dictationOwnsVoice(phase)"))
        assertTrue(snapshots.contains("!VoiceInputTransitionPolicy.dictationOwnsVoice(HansDictationRuntime.snapshotUi().phase)"))
        assertTrue(snapshots.contains("if (pendingLiveVoiceStart && released)"))
        val command = source.substringAfter("private fun executeGrantedDictationCommand(")
            .substringBefore("private fun resolveSelection(")
        assertTrue(command.contains("if (handleNonStartingVoiceCommand(command)) return"))
        assertTrue(command.contains("pendingDictationAfterLiveVoice = ActionKeyCommand.StartDictation"))
        assertFalse(command.contains("HansDictationService.toggle(this)"))
        assertTrue(command.contains("AndroidLiveVoiceRuntime.isCaptureRequestedOrActive()"))
        assertTrue(command.contains("VoiceInputCommandAction.TOGGLE_INPUT_MUTED"))
        assertTrue(command.contains("AndroidLiveVoiceRuntime.toggleDictationInputMuted()"))
        assertTrue(command.contains("CodexDictationIntegration.continuousDictation"))
        val drain = command.substringAfter("private fun drainPendingDictationAfterLiveVoice()")
            .substringBefore("private fun handleNonStartingVoiceCommand(")
        assertTrue(drain.contains("if (isLiveVoiceActive()) return"))
        assertTrue(drain.indexOf("pendingDictationAfterLiveVoice = null") < drain.indexOf("executeGrantedDictationCommand(command)"))
        assertTrue(source.contains("liveVoiceCaptureObserver?.cancel()"))
    }

    @Test fun processingNoticeDescribesPendingWorkWithoutInvitingASecondRecording() {
        assertEquals("Dein Diktat wird noch verarbeitet. Bitte kurz warten.",
            TestResourceTextResolver(Locale.GERMAN).text(R.string.integration_dictation_still_processing))
        assertEquals("Dictation is still processing. Please wait.",
            TestResourceTextResolver(Locale.ENGLISH).text(R.string.integration_dictation_still_processing))
    }

    private fun decide(command: ActionKeyCommand, phase: DictationUiPhase, pending: Boolean = false) =
        VoiceInputTransitionPolicy.command(command, phase, pending)
}
