package ai.hans.standard.ui

import ai.hans.standard.phone.display.DisplayMotionMode
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Synthetic UI events only: no recording, network connection, or real message dispatch. */
class DictationTranscriptPlaceholderUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun connectingBubbleIsImmediatelyReplacedByTheFirstUserTranscript() {
        val sent = mutableListOf<String>()
        val edits = mutableListOf<String>()
        val state = mutableStateOf(ChatUiState(
            composer = ComposerUiState("Keyboard draft", enabled = false),
            dictationStatus = DictationUiStatus.PREPARING,
            dictationAwaitingFirstTranscript = true,
        ))
        compose.setGermanContent {
            MaterialTheme { ChatScreen(state.value, callbacks(sent::add, edits::add),
                displayMotionMode = DisplayMotionMode.E_INK) }
        }
        compose.onNodeWithTag("dictation_transcript_waiting_dots").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("message_dictation-awaiting-transcript").assertIsDisplayed()
        compose.onNodeWithContentDescription("Dein Transkript folgt nach Aufnahmeende").assertIsDisplayed()
        compose.onAllNodesWithText("Du").assertCountEquals(1)
        compose.onNodeWithTag("dictation_preview").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(dictationStatus = DictationUiStatus.LISTENING) }
        compose.onNodeWithTag("dictation_transcript_waiting_dots").assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(
                dictationAwaitingFirstTranscript = false,
                messages = listOf(ChatMessageUiModel("actual-live-user", ChatMessageAuthor.USER,
                    "Open WhatsApp", complete = false)),
                timelineRevision = 1,
            )
        }
        compose.onNodeWithTag("dictation_transcript_waiting_dots").assertDoesNotExist()
        compose.onNodeWithTag("message_dictation-awaiting-transcript").assertDoesNotExist()
        compose.onNodeWithTag("message_actual-live-user").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Du").assertCountEquals(1)
        compose.runOnIdle {
            assertTrue(sent.isEmpty())
            assertTrue(edits.isEmpty())
            assertEquals("Keyboard draft", state.value.composer.text)
        }
    }

    @Test fun newSessionHasDotsDespiteOlderMessagesAndFailureRemovesThem() {
        val state = mutableStateOf(ChatUiState(
            messages = listOf(ChatMessageUiModel("old-input", ChatMessageAuthor.USER, "Earlier request")),
            dictationStatus = DictationUiStatus.PREPARING,
            dictationAwaitingFirstTranscript = true,
        ))
        compose.setGermanContent {
            MaterialTheme { ChatScreen(state.value, callbacks(), displayMotionMode = DisplayMotionMode.E_INK) }
        }
        compose.onNodeWithTag("dictation_transcript_waiting_dots").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(dictationStatus = DictationUiStatus.FAILED) }
        compose.onNodeWithTag("dictation_transcript_waiting_dots").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(dictationStatus = null) }
        compose.onNodeWithTag("dictation_transcript_waiting_dots").assertDoesNotExist()
        compose.onNodeWithTag("message_old-input").performScrollTo().assertIsDisplayed()
    }

    @Test fun previewReplacesDotsWithoutASecondUserBubble() {
        val state = mutableStateOf(ChatUiState(dictationStatus = DictationUiStatus.LISTENING,
            dictationAwaitingFirstTranscript = true))
        compose.setGermanContent {
            MaterialTheme { ChatScreen(state.value, callbacks(), displayMotionMode = DisplayMotionMode.E_INK) }
        }
        compose.onNodeWithTag("dictation_transcript_waiting_dots").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(dictationPreview = "First words") }
        compose.onNodeWithTag("dictation_transcript_waiting_dots").assertDoesNotExist()
        compose.onNodeWithTag("dictation_preview").performScrollTo().assertTextEquals("First words")
        compose.onAllNodesWithText("Du").assertCountEquals(1)
    }

    @Test fun acceptedDictationShowsDotsBeforeReplacingAFailedPhoneGetsANativeConnection() {
        val state = mutableStateOf(ChatUiState(liveVoiceStatus = LiveVoiceUiStatus.FAILED))
        compose.setGermanContent {
            MaterialTheme { ChatScreen(state.value, callbacks(), displayMotionMode = DisplayMotionMode.E_INK) }
        }
        compose.onNodeWithTag("dictation_transcript_waiting_dots").assertDoesNotExist()
        compose.runOnIdle {
            state.value = state.value.copy(dictationStatus = DictationUiStatus.PREPARING,
                dictationAwaitingFirstTranscript = true,
                liveVoiceStatus = state.value.liveVoiceStatus.afterDictationPublication(DictationUiStatus.PREPARING))
        }
        compose.onNodeWithTag("dictation_transcript_waiting_dots").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("live_call_avatar").assertDoesNotExist()
    }

    @Test fun recreatedActivityPhoneFailureReplayKeepsDotsButActivePhoneReplayKeepsTheCall() {
        val state = mutableStateOf(ChatUiState(dictationStatus = DictationUiStatus.PREPARING,
            dictationAwaitingFirstTranscript = true))
        compose.setGermanContent {
            MaterialTheme { ChatScreen(state.value, callbacks(), displayMotionMode = DisplayMotionMode.E_INK) }
        }
        compose.onNodeWithTag("dictation_transcript_waiting_dots").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(liveVoiceStatus =
                LiveVoiceUiStatus.FAILED.afterDictationPublication(state.value.dictationStatus))
        }
        compose.onNodeWithTag("dictation_transcript_waiting_dots").assertIsDisplayed()
        compose.onNodeWithTag("live_call_avatar").assertDoesNotExist()
        compose.runOnIdle {
            state.value = state.value.copy(liveVoiceStatus =
                LiveVoiceUiStatus.LISTENING.afterDictationPublication(state.value.dictationStatus))
        }
        compose.onNodeWithTag("dictation_transcript_waiting_dots").assertDoesNotExist()
        compose.onNodeWithTag("live_call_avatar").assertIsDisplayed()
    }

    @Test fun placeholderPixelsRemainStaticWithNoAnimationClock() {
        val mode = mutableStateOf(DisplayMotionMode.E_INK)
        compose.setGermanContent {
            MaterialTheme { ChatScreen(ChatUiState(dictationStatus = DictationUiStatus.PREPARING,
                dictationAwaitingFirstTranscript = true), callbacks(), displayMotionMode = mode.value) }
        }
        compose.onNodeWithTag("dictation_transcript_waiting_dots").performScrollTo()
        compose.mainClock.autoAdvance = false
        listOf(DisplayMotionMode.E_INK, DisplayMotionMode.STANDARD).forEach { display ->
            compose.runOnIdle { mode.value = display }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val before = dotPixels()
            compose.mainClock.advanceTimeBy(2_000)
            compose.waitForIdle()
            assertArrayEquals(before, dotPixels())
        }
        compose.mainClock.autoAdvance = true
    }

    private fun dotPixels(): IntArray {
        val pixels = compose.onNodeWithTag("dictation_transcript_waiting_dots").captureToImage().toPixelMap()
        return IntArray(pixels.width * pixels.height) { index ->
            pixels[index % pixels.width, index / pixels.width].toArgb()
        }
    }

    private fun callbacks(onSend: (String) -> Unit = {}, onEdit: (String) -> Unit = {}) = ChatUiCallbacks(
        onComposerChanged = onEdit, onSend = onSend, onChooseMedia = {}, onRemoveAttachment = {},
        onOpenApps = {}, onOpenPlugins = {}, onOpenSettings = {}, onToggleLiveVoice = {},
    )
}
