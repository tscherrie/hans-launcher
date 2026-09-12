package ai.hans.standard.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercises the real chat card, not only the standalone Markdown component. */
class AssistantMessageFormattingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun assistantMarkupIsRenderedButTheUsersOwnTextStaysLiteral() {
        val raw = "**Wichtig:** *heute*."
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    ChatUiState(
                        messages = listOf(
                            ChatMessageUiModel("user", ChatMessageAuthor.USER, raw),
                            ChatMessageUiModel("answer", ChatMessageAuthor.HANS, raw),
                        ),
                        composer = ComposerUiState(enabled = false),
                    ),
                    callbacks(),
                )
            }
        }

        compose.onNodeWithTag("message_user").assertExists()
        compose.onNodeWithText(raw).assertExists()
        compose.onNodeWithTag("message_answer").assertExists()
        compose.onNodeWithText("Wichtig: heute.").assertExists()
    }

    @Test
    fun tappingTheReadableLinkNameOpensItsExactDestinationOnlyOnce() {
        val opened = mutableListOf<String>()
        val destination = "https://maps.example/route?destination=airport"
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { opened += uri }
            }) {
                MaterialTheme {
                    ChatScreen(
                        ChatUiState(
                            messages = listOf(
                                ChatMessageUiModel(
                                    "answer", ChatMessageAuthor.HANS,
                                    "Hier: [Google Maps]($destination).",
                                ),
                            ),
                            composer = ComposerUiState(enabled = false),
                        ),
                        callbacks(),
                    )
                }
            }
        }

        compose.onNodeWithText(destination, substring = true).assertDoesNotExist()
        val message = compose.onNodeWithText("Hier: Google Maps.")
        val layouts = mutableListOf<TextLayoutResult>()
        message.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            assertTrue(action(layouts))
        }
        compose.runOnIdle { assertTrue(opened.isEmpty()) }
        // Hit the rendered word itself: no semantic callback shortcut and no real web access.
        val linkPosition = layouts.single().getBoundingBox("Hier: ".length + 2).center
        message.performTouchInput { click(linkPosition) }
        compose.runOnIdle { assertEquals(listOf(destination), opened) }
    }

    @Test
    fun aCompletedRevisionRendersTheLinkWithoutLeavingFormattingMarkers() {
        val message = mutableStateOf(
            ChatMessageUiModel("answer", ChatMessageAuthor.HANS, "**Erster Satz.**", complete = false),
        )
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    ChatUiState(
                        messages = listOf(message.value),
                        composer = ComposerUiState(enabled = false),
                        timelineRevision = message.value.revision,
                    ),
                    callbacks(),
                )
            }
        }

        compose.onNodeWithText("Erster Satz.").assertExists()
        compose.runOnIdle {
            message.value = message.value.copy(
                text = "**Erster Satz.** [Die Route](https://maps.example/a).",
                revision = 1,
                complete = true,
            )
        }
        compose.onNodeWithTag("message_answer").assertExists()
        compose.onNodeWithText("Erster Satz. Die Route.").assertExists()
        compose.onNodeWithText("https://maps.example/a", substring = true).assertDoesNotExist()
        compose.onNodeWithText("**", substring = true).assertDoesNotExist()
    }

    private fun callbacks() = ChatUiCallbacks(
        onComposerChanged = {},
        onSend = {},
        onChooseMedia = {},
        onRemoveAttachment = {},
        onOpenApps = {},
        onOpenPlugins = {},
        onOpenSettings = {},
        onToggleLiveVoice = {},
    )
}
