package ai.hans.standard.ui

import ai.hans.standard.phone.display.DisplayMotionMode
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import org.junit.Rule
import org.junit.Test

/** Synthetic visible history only: no device input, account, notifications or runtime. */
class ChatHistoryScrollRetentionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unrelatedAggregateRevisionDoesNotPullOlderHistoryBackToTheTail() {
        val state = mutableStateOf(ChatUiState(messages = history()))
        compose.setGermanContent {
            MaterialTheme { ChatScreen(state.value, callbacks(), displayMotionMode = DisplayMotionMode.E_INK) }
        }
        compose.onNodeWithTag("message_history-29").assertIsDisplayed()
        compose.onNodeWithTag("chat_timeline").performScrollToIndex(0)
        compose.onNodeWithTag("message_history-0").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(timelineRevision = 101) }
        compose.onNodeWithTag("message_history-0").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(timelineRevision = 102) }
        compose.onNodeWithTag("message_history-0").assertIsDisplayed()
    }

    @Test fun aNewVisibleReplyStillReceivesTheNormalTailFeedbackWithoutAggregateRevision() {
        val state = mutableStateOf(ChatUiState(messages = history()))
        compose.setGermanContent { MaterialTheme { ChatScreen(state.value, callbacks()) } }
        compose.onNodeWithTag("chat_timeline").performScrollToIndex(0)
        compose.onNodeWithTag("message_history-0").assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(messages = state.value.messages +
                ChatMessageUiModel("actual-new-answer", ChatMessageAuthor.HANS, "New visible answer"))
        }
        compose.onNodeWithTag("message_actual-new-answer").assertIsDisplayed()
    }

    @Test fun anEqualLengthFinalCardReplacementRemainsVisibleWithoutChangingMessageCount() {
        val state = mutableStateOf(ChatUiState(messages = history().dropLast(1) +
            ChatMessageUiModel("spoken-tail", ChatMessageAuthor.HANS, "Last")))
        compose.setGermanContent { MaterialTheme { ChatScreen(state.value, callbacks()) } }
        compose.onNodeWithTag("chat_timeline").performScrollToIndex(0)
        compose.onNodeWithTag("message_history-0").assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(messages = state.value.messages.dropLast(1) +
                ChatMessageUiModel("native-final-tail", ChatMessageAuthor.HANS, "Next"))
        }
        compose.onNodeWithTag("message_native-final-tail").assertIsDisplayed()
        compose.onNodeWithTag("message_spoken-tail").assertDoesNotExist()
    }

    private fun history() = (0 until 30).map {
        ChatMessageUiModel("history-$it", ChatMessageAuthor.HANS,
            "Older reply $it\nFirst detail\nSecond detail\nThird detail")
    }

    private fun callbacks() = ChatUiCallbacks(
        onComposerChanged = {}, onSend = {}, onChooseMedia = {}, onRemoveAttachment = {},
        onOpenApps = {}, onOpenPlugins = {}, onOpenSettings = {}, onToggleLiveVoice = {},
    )
}
