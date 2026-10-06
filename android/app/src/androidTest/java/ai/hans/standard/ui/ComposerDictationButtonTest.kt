package ai.hans.standard.ui

import ai.hans.standard.phone.display.DisplayMotionMode
import ai.hans.standard.voice.audio.SpeechAudioRoute
import ai.hans.standard.voice.audio.SpeechAudioRouteState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Presentation only: no microphone, account, service, network or actual Codex task. */
@OptIn(ExperimentalTestApi::class)
class ComposerDictationButtonTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var inputModeManager: InputModeManager

    @Test fun oneTapUsesOnlyTheDictationCallbackAndKeepsTheChatVisible() {
        val calls = Calls()
        show({ ChatUiState(cameraHoldToTalkEnabled = true) }, calls.callbacks())
        compose.onNodeWithTag(DICTATION).assertIsDisplayed().assertIsEnabled()
            .assertIsNotSelected().assertContentDescriptionEquals("Diktat starten")
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("chat_timeline").assertIsDisplayed()
        compose.onNodeWithTag("live_call_screen").assertDoesNotExist()
        compose.runOnIdle { calls.assertOnlyDictation(1) }
    }

    @Test fun releasingStartingTapDoesNothingAndSecondTapFinalizesWithoutLeavingTheChat() {
        val calls = Calls()
        val state = mutableStateOf(ChatUiState())
        show({ state.value }, calls.callbacks().copy(onToggleDictation = {
            calls.dictation++
            state.value = if (state.value.dictationStatus == null) {
                state.value.copy(dictationStatus = DictationUiStatus.LISTENING)
            } else state.value.copy(dictationStatus = DictationUiStatus.FINALIZING)
        }))
        compose.onNodeWithTag(DICTATION).performTouchInput { down(center) }
        compose.runOnIdle { calls.assertOnlyDictation(0) }
        compose.onNodeWithTag(DICTATION).performTouchInput { advanceEventTime(80); up() }
        compose.onNodeWithTag(DICTATION).assertIsSelected()
            .assertContentDescriptionEquals("Aufnahme beenden")
        compose.onNodeWithTag("composer").assertDoesNotExist()
        compose.onNodeWithTag("choose_media").assertDoesNotExist()
        compose.onNodeWithTag("voice_task_composer").assertIsDisplayed()
        compose.runOnIdle { calls.assertOnlyDictation(1) }
        compose.onNodeWithTag("live_call_screen").assertDoesNotExist()
        compose.onNodeWithTag("live_call_bar").assertDoesNotExist()
        compose.onNodeWithTag(DICTATION).performClick()
        compose.onNodeWithTag(DICTATION).assertIsNotEnabled()
            .assertContentDescriptionEquals("Wird transkribiert")
        compose.onNodeWithTag("voice_task_microphone_state").assertTextEquals("Mikrofon aus")
        compose.onNodeWithTag("composer").assertDoesNotExist()
        compose.runOnIdle { calls.assertOnlyDictation(2) }
        compose.onNodeWithTag(DICTATION).performClick()
        compose.runOnIdle { calls.assertOnlyDictation(2) }
        compose.onNodeWithTag("live_call_screen").assertDoesNotExist()
    }

    @Test fun preparingAndListeningCanBothBeStoppedWhileComposerIsLockedAndCodexWorks() {
        val calls = Calls()
        val state = mutableStateOf(ChatUiState(
            composer = ComposerUiState(enabled = false),
            dictationStatus = DictationUiStatus.PREPARING,
            isWorking = true,
            workInterrupt = WorkInterruptUiState(visible = true, enabled = true),
        ))
        show({ state.value }, calls.callbacks())
        listOf(DictationUiStatus.PREPARING, DictationUiStatus.LISTENING).forEach { status ->
            compose.runOnIdle { state.value = state.value.copy(dictationStatus = status) }
            compose.onNodeWithTag("composer").assertDoesNotExist()
            compose.onNodeWithTag("voice_task_status").assertTextEquals("Hans arbeitet")
            compose.onNodeWithTag("interrupt_codex_work").assertIsEnabled()
            compose.onNodeWithTag(DICTATION).assertIsEnabled().assertIsSelected()
                .assertContentDescriptionEquals("Aufnahme beenden").performClick()
        }
        compose.runOnIdle { calls.assertOnlyDictation(2) }
    }

    @Test fun newDictationRemainsAvailableWhileCodexWorksAndTheTextFieldIsDisabled() {
        val calls = Calls()
        show({ ChatUiState(
            composer = ComposerUiState(enabled = false),
            isWorking = true,
            workInterrupt = WorkInterruptUiState(visible = true, enabled = true),
        ) }, calls.callbacks())
        compose.onNodeWithTag("composer").assertIsNotEnabled()
        compose.onNodeWithTag(DICTATION).assertIsEnabled().performClick()
        compose.runOnIdle { calls.assertOnlyDictation(1) }
    }

    @Test fun phoneModeHasNoDictationButtonEvenWhenTheCallIsMinimizedOrFailed() {
        val calls = Calls()
        val state = mutableStateOf(ChatUiState(liveVoiceStatus = LiveVoiceUiStatus.LISTENING,
            actionKeyConfigured = true))
        show({ state.value }, calls.callbacks())
        compose.onNodeWithTag("live_call_bar").assertIsDisplayed()
        compose.onNodeWithTag("live_call_bar_mute").assertIsEnabled()
        compose.onNodeWithTag("live_call_bar_hang_up").assertIsEnabled()
        compose.onNodeWithTag("composer").assertIsDisplayed()
        compose.onNodeWithTag(DICTATION).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(liveVoiceStatus = LiveVoiceUiStatus.FAILED) }
        compose.onNodeWithTag(DICTATION).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(liveVoiceStatus = null) }
        compose.onNodeWithTag(DICTATION).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(actionKeyConfigured = false) }
        compose.onNodeWithTag(DICTATION).assertIsEnabled()
        compose.runOnIdle { calls.assertOnlyDictation(0) }
    }

    @Test fun preparingDoesNotClaimThatPhysicalCaptureAlreadyStarted() {
        val state = mutableStateOf(ChatUiState(dictationStatus = DictationUiStatus.PREPARING))
        show({ state.value })
        compose.onNodeWithTag("voice_task_microphone_state").assertTextEquals("Mikrofon wird vorbereitet")
        compose.onNodeWithTag(DICTATION).assertContentDescriptionEquals("Aufnahme beenden")
        compose.runOnIdle { state.value = state.value.copy(dictationStatus = DictationUiStatus.LISTENING) }
        compose.onNodeWithTag("voice_task_microphone_state").assertTextEquals("Mikrofon an")
    }

    @Test fun confirmedStartupMuteIsVisibleAndDoesNotExposeTheTelephoneScreen() {
        val state = mutableStateOf(ChatUiState(dictationStatus = DictationUiStatus.PREPARING,
            dictationInputMuted = true))
        show({ state.value })
        compose.onNodeWithTag(DICTATION).assertIsEnabled().assertIsSelected()
            .assertContentDescriptionEquals("Aufnahme beenden")
        compose.onNodeWithTag("voice_task_microphone_state").assertTextEquals("Mikrofon aus")
        compose.onNodeWithTag("live_call_screen").assertDoesNotExist()
        compose.onNodeWithText("Aufnahme läuft").assertDoesNotExist()
        compose.runOnIdle { state.value = ChatUiState() }
        compose.onNodeWithTag(DICTATION).assertIsNotSelected()
            .assertContentDescriptionEquals("Diktat starten")
    }

    @Test fun finalizingCannotStartAnotherCaptureAndBecomesAvailableAfterRelease() {
        val calls = Calls()
        val state = mutableStateOf(ChatUiState(dictationStatus = DictationUiStatus.FINALIZING))
        show({ state.value }, calls.callbacks())
        compose.onNodeWithTag(DICTATION).assertIsNotEnabled()
        compose.onNodeWithTag("voice_task_microphone_state").assertTextEquals("Mikrofon aus")
        compose.runOnIdle { state.value = state.value.copy(dictationStatus = null) }
        compose.onNodeWithTag(DICTATION).assertIsEnabled().performClick()
        compose.runOnIdle { calls.assertOnlyDictation(1) }
    }

    @Test fun keyboardActivationDoesNotTypeIntoTheDraftAndCameraRemainsSeparate() {
        val calls = Calls()
        show({ ChatUiState(composer = ComposerUiState(text = "Entwurf")) }, calls.callbacks())
        compose.runOnIdle { assertTrue(inputModeManager.requestInputMode(InputMode.Keyboard)) }
        compose.onNodeWithTag(DICTATION).requestFocus().assertIsFocused()
        compose.onNodeWithTag(DICTATION).performKeyInput { pressKey(Key.Spacebar) }
        compose.onNodeWithTag(DICTATION).performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithTag("composer").assertTextEquals("Entwurf")
        compose.runOnIdle { calls.assertOnlyDictation(2) }
        compose.onNodeWithTag("choose_media").performClick()
        compose.runOnIdle { assertEquals(1, calls.camera); assertEquals(2, calls.dictation) }
    }

    @Test fun assignedPhysicalKeyHidesTheMicrophoneForEveryTaskStateButNeverHidesStop() {
        val calls = Calls()
        val state = mutableStateOf(ChatUiState(actionKeyConfigured = true,
            isWorking = true, workInterrupt = WorkInterruptUiState(visible = true, enabled = true)))
        show({ state.value }, calls.callbacks())
        listOf(null, DictationUiStatus.PREPARING, DictationUiStatus.LISTENING,
            DictationUiStatus.FINALIZING, DictationUiStatus.FAILED).forEach { status ->
            listOf(false, true).forEach { muted ->
                compose.runOnIdle {
                    state.value = state.value.copy(dictationStatus = status, dictationInputMuted = muted)
                }
                compose.onNodeWithTag(DICTATION).assertDoesNotExist()
                compose.onNodeWithTag("interrupt_codex_work").assertIsEnabled()
                if (status in listOf(DictationUiStatus.PREPARING, DictationUiStatus.LISTENING,
                        DictationUiStatus.FINALIZING)) {
                    compose.onNodeWithTag("voice_task_microphone_state").assertIsDisplayed()
                    compose.onNodeWithTag("voice_task_composer").assertIsDisplayed()
                    compose.onNodeWithTag("composer").assertDoesNotExist()
                    compose.onNodeWithTag("choose_media").assertDoesNotExist()
                }
            }
        }
        compose.onNodeWithTag("interrupt_codex_work").performClick()
        compose.runOnIdle {
            assertEquals(1, calls.interrupt)
            assertEquals(0, calls.dictation)
            assertEquals(0, calls.phone)
        }
    }

    @Test fun changingPhysicalKeyAssignmentUpdatesOnlyTheButtonAndKeepsMicrophoneState() {
        val calls = Calls()
        val state = mutableStateOf(ChatUiState(dictationStatus = DictationUiStatus.LISTENING,
            dictationInputMuted = true, actionKeyConfigured = false))
        show({ state.value }, calls.callbacks())
        compose.onNodeWithTag(DICTATION).assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(actionKeyConfigured = true) }
        compose.onNodeWithTag(DICTATION).assertDoesNotExist()
        compose.onNodeWithTag("voice_task_microphone_state").assertTextEquals("Mikrofon aus")
        compose.runOnIdle { state.value = state.value.copy(actionKeyConfigured = false) }
        compose.onNodeWithTag(DICTATION).assertIsEnabled()
            .assertContentDescriptionEquals("Aufnahme beenden")
        compose.runOnIdle { calls.assertOnlyDictation(0) }
    }

    @Test fun activeTaskRoutesAudioInsideItsPanelEvenWithAPhysicalKeyAndKeepsHeaderQuiet() {
        val requested = mutableListOf<SpeechAudioRoute>()
        show({ ChatUiState(actionKeyConfigured = true, dictationStatus = DictationUiStatus.LISTENING,
            speechAudioRoute = SpeechAudioRouteState(active = true,
                available = setOf(SpeechAudioRoute.SPEAKER, SpeechAudioRoute.EARPIECE),
                effective = SpeechAudioRoute.SPEAKER)) },
            Calls().callbacks().copy(onSpeechAudioRouteRequested = requested::add))
        compose.onNodeWithTag("speech_audio_route")
            .assert(hasAnyAncestor(hasTestTag("voice_task_composer")))
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("speech_route_earpiece").performClick()
        compose.onNodeWithTag("speech_audio_route")
            .assertContentDescriptionEquals("Tonausgabe: Lautsprecher. Ändern")
        compose.onNodeWithTag("hans_title").assertIsDisplayed()
        compose.onNodeWithTag("open_chat_navigation").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(SpeechAudioRoute.EARPIECE), requested) }
    }

    @Test fun microphoneStaysToTheRightOfTheIndependentWorkStop() {
        show({ ChatUiState(dictationStatus = DictationUiStatus.LISTENING, isWorking = true,
            workInterrupt = WorkInterruptUiState(visible = true, enabled = true)) })
        val mic = compose.onNodeWithTag(DICTATION).fetchSemanticsNode().boundsInRoot
        val stop = compose.onNodeWithTag("interrupt_codex_work").fetchSemanticsNode().boundsInRoot
        assertTrue(mic.left >= stop.right)
        compose.onNodeWithTag("working_indicator").assertDoesNotExist()
        compose.onNodeWithTag("voice_task_status").assertTextEquals("Hans arbeitet")
    }

    @Test fun emptyChatHintDescribesOnlyTheConfiguredVoiceEntry() {
        val state = mutableStateOf(ChatUiState())
        show({ state.value })
        compose.onNodeWithTag("empty_chat_input_hint")
            .assertTextEquals("Schreib los oder tippe aufs Mikrofon.")
        compose.onNodeWithTag(DICTATION).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(actionKeyConfigured = true) }
        compose.onNodeWithTag("empty_chat_input_hint")
            .assertTextEquals("Schreib los oder drücke die Aktionstaste.")
        compose.onNodeWithTag(DICTATION).assertDoesNotExist()
    }

    private fun show(state: () -> ChatUiState, callbacks: ChatUiCallbacks = Calls().callbacks()) {
        val minimized = mutableStateOf(true)
        compose.setGermanContent {
            inputModeManager = LocalInputModeManager.current
            MaterialTheme {
                ChatScreen(state(), callbacks, displayMotionMode = DisplayMotionMode.E_INK,
                    liveCallMinimizedState = minimized)
            }
        }
    }

    private class Calls {
        var dictation = 0
        var phone = 0
        var camera = 0
        var send = 0
        var interrupt = 0
        fun callbacks() = ChatUiCallbacks(
            onComposerChanged = {}, onSend = { send++ }, onChooseMedia = { camera++ },
            onRemoveAttachment = {}, onOpenApps = {}, onOpenPlugins = {}, onOpenSettings = {},
            onToggleLiveVoice = { phone++ }, onToggleDictation = { dictation++ },
            onStartLiveVoice = { phone++ }, onStopLiveVoice = { phone++ },
            onCameraGestureDown = { camera++; null },
            onCameraGestureLongPress = { _, _ -> camera++ },
            onCameraGestureUp = { _, _ -> camera++ },
            onCameraGestureCancel = { _, _ -> camera++ },
            onInterruptWork = { interrupt++; true },
        )
        fun assertOnlyDictation(expected: Int) {
            assertEquals(expected, dictation)
            assertEquals(0, phone)
            assertEquals(0, camera)
            assertEquals(0, send)
            assertEquals(0, interrupt)
        }
    }

    private companion object { const val DICTATION = "toggle_dictation" }
}
