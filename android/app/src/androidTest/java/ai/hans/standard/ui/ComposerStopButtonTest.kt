package ai.hans.standard.ui

import ai.hans.standard.phone.display.DisplayMotionMode
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class ComposerStopButtonTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var inputModeManager: InputModeManager

    @Test
    fun stopAppearsForRuntimeWorkAndWaitsForConfirmedInterruptibleTurn() {
        val state = mutableStateOf(ChatUiState())
        show({ state.value })
        compose.onNodeWithTag(STOP).assertDoesNotExist()
        compose.runOnIdle {
            state.value = state.value.copy(isWorking = true,
                workInterrupt = WorkInterruptUiState(visible = true))
        }
        compose.onNodeWithTag(STOP).assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(workInterrupt = available()) }
        compose.onNodeWithTag(STOP).assertIsEnabled()
            .assertContentDescriptionEquals("Codex-Aufgabe stoppen")
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.runOnIdle {
            state.value = state.value.copy(isWorking = false, workInterrupt = WorkInterruptUiState())
        }
        compose.onNodeWithTag(STOP).assertDoesNotExist()
    }

    @Test
    fun sameFrameDoubleClickCallsInterruptExactlyOnceAndDoesNotPretendWorkStopped() {
        var stops = 0
        show({ ChatUiState(isWorking = true, workInterrupt = available()) }, callbacks().copy(
            onInterruptWork = { stops++; true },
        ))
        val click = checkNotNull(compose.onNodeWithTag(STOP).fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action)
        compose.runOnUiThread { click(); click() }
        compose.onNodeWithTag(STOP).assertIsNotEnabled()
            .assertContentDescriptionEquals("Unterbrechen angefordert")
        compose.onNodeWithTag("working_indicator").assertExists()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test
    fun pendingAckKeepsStopDisabledAndARejectedRequestMakesRetryAvailable() {
        var stops = 0
        val interrupt = mutableStateOf(available())
        show({ ChatUiState(isWorking = true, workInterrupt = interrupt.value) }, callbacks().copy(
            onInterruptWork = { stops++; true },
        ))
        compose.onNodeWithTag(STOP).performClick()
        compose.runOnIdle { interrupt.value = WorkInterruptUiState(true, false, true, 1) }
        compose.onNodeWithTag(STOP).assertIsNotEnabled()
        compose.runOnIdle { interrupt.value = available().copy(revision = 1) }
        compose.onNodeWithTag(STOP).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, stops) }
    }

    @Test
    fun rejectionCoalescedBeforePendingFrameDoesNotLeaveTheButtonLatched() {
        var stops = 0
        val interrupt = mutableStateOf(available())
        show({ ChatUiState(isWorking = true, workInterrupt = interrupt.value) }, callbacks().copy(
            onInterruptWork = {
                stops++
                // Models a synchronous remote rejection before PENDING gets its own frame.
                interrupt.value = interrupt.value.copy(revision = interrupt.value.revision + 1)
                true
            },
        ))
        compose.onNodeWithTag(STOP).performClick()
        compose.onNodeWithTag(STOP).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, stops) }
    }

    @Test
    fun localRejectionDoesNotDisableRetryOrEraseTheComposerDraftAndAttachments() {
        var stops = 0
        val draft = mutableStateOf("Nachtrag")
        show({ ChatUiState(isWorking = true, workInterrupt = available(), composer = ComposerUiState(
            text = draft.value, attachments = listOf(ComposerAttachmentUiModel("a", "Bild")),
        )) }, callbacks().copy(
            onInterruptWork = { stops++; false },
            onComposerChanged = { draft.value = it },
        ))
        compose.onNodeWithTag(STOP).performClick()
        compose.onNodeWithTag(STOP).assertIsEnabled()
        compose.onNodeWithTag("composer").assertIsEnabled().assertTextEquals("Nachtrag")
        compose.onNodeWithText("Bild").assertExists()
        compose.onNodeWithTag("composer").performTextInput(" plus")
        compose.runOnIdle { assertEquals(1, stops); assertEquals("Nachtrag plus", draft.value) }
    }

    @Test
    fun focusedStopHandlesSpaceAndEnterWithoutTypingIntoTheComposer() {
        var stops = 0
        val draft = mutableStateOf("Nachtrag")
        show({ ChatUiState(isWorking = true, workInterrupt = available(),
            composer = ComposerUiState(text = draft.value)) }, callbacks().copy(
            onInterruptWork = { stops++; false },
            onComposerChanged = { draft.value = it },
        ))
        focusStopWithKeyboard()
        compose.onNodeWithTag(STOP).performKeyInput { pressKey(Key.Spacebar) }
        compose.onNodeWithTag(STOP).assertIsFocused()
        compose.onNodeWithTag("composer").assertTextEquals("Nachtrag")
        compose.runOnIdle { assertEquals(1, stops); assertEquals("Nachtrag", draft.value) }
        compose.onNodeWithTag(STOP).performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(2, stops); assertEquals("Nachtrag", draft.value) }
    }

    @Test
    fun keyboardStopDoesNotHangUpLiveOrDiscardAnOngoingDictation() {
        var stops = 0
        var hangups = 0
        var discards = 0
        show({ ChatUiState(
            isWorking = true, workInterrupt = available(),
            liveVoiceStatus = LiveVoiceUiStatus.LISTENING,
            dictationStatus = DictationUiStatus.LISTENING,
            dictationPreview = "Unfertiger Sprachtext",
        ) }, callbacks().copy(
            onInterruptWork = { stops++; true },
            onStopLiveVoice = { hangups++ },
            onDiscardPendingDictation = { discards++ },
        ))
        focusStopWithKeyboard()
        compose.onNodeWithTag(STOP).performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithTag(STOP).assertIsNotEnabled()
        compose.onNodeWithTag("composer").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, stops)
            assertEquals(0, hangups)
            assertEquals(0, discards)
        }
    }

    private fun show(state: () -> ChatUiState, callbacks: ChatUiCallbacks = callbacks()) {
        val minimized = mutableStateOf(true)
        compose.setGermanContent {
            inputModeManager = LocalInputModeManager.current
            MaterialTheme {
                ChatScreen(state = state(), callbacks = callbacks,
                    displayMotionMode = DisplayMotionMode.E_INK,
                    liveCallMinimizedState = minimized)
            }
        }
    }

    private fun focusStopWithKeyboard() {
        // Buttons intentionally do not accept keyboard focus in Touch mode. Match the
        // input-mode transition of a real hardware keyboard before injecting activation.
        compose.runOnIdle { assertTrue(inputModeManager.requestInputMode(InputMode.Keyboard)) }
        compose.onNodeWithTag(STOP).requestFocus().assertIsFocused()
    }

    private fun callbacks() = ChatUiCallbacks(
        onComposerChanged = {}, onSend = {}, onChooseMedia = {}, onRemoveAttachment = {},
        onOpenApps = {}, onOpenPlugins = {}, onOpenSettings = {}, onToggleLiveVoice = {},
    )

    private fun available() = WorkInterruptUiState(visible = true, enabled = true)

    private companion object { const val STOP = "interrupt_codex_work" }
}
