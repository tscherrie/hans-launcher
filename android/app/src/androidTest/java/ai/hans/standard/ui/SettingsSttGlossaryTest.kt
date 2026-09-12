package ai.hans.standard.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import ai.hans.standard.voice.stt.SttTranscriptionPromptBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsSttGlossaryTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun confirmedTermsAreEditedOnlyBehindThePersonalSettingsGroupAndExplicitSave() {
        val saves = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        confirmedSttGlossary = ConfirmedSttGlossaryUiState(
                            terms = listOf("Grenzebach", "Skill Me Now"),
                        ),
                    ),
                    callbacks = callbacks { saves += it },
                )
            }
        }

        compose.onNodeWithTag("confirmed_stt_glossary_status").assertDoesNotExist()
        compose.onNodeWithTag("settings_group_personal").performScrollTo().performClick()
        compose.onNodeWithTag("confirmed_stt_glossary_status")
            .performScrollTo()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.Text,
                    listOf(
                        AnnotatedString(
                            "2 von ${SttTranscriptionPromptBuilder.MAX_GLOSSARY_TERMS} Begriffen gespeichert",
                        ),
                    ),
                ),
            )
        compose.onNodeWithTag("edit_confirmed_stt_glossary")
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag("confirmed_stt_glossary_editor")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString("Grenzebach\nSkill Me Now"),
                ),
            )
            .performTextReplacement(" Gamsbart \nGAMSbART")

        assertTrue(saves.isEmpty())
        compose.onNodeWithTag("save_confirmed_stt_glossary")
            .performScrollTo()
            .performClick()

        assertEquals(listOf(" Gamsbart \nGAMSbART"), saves)
    }

    @Test
    fun cancellingAnEditDoesNotCrossTheConfirmationBoundary() {
        val saves = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(),
                    callbacks = callbacks { saves += it },
                )
            }
        }

        compose.onNodeWithTag("settings_group_personal").performScrollTo().performClick()
        compose.onNodeWithTag("edit_confirmed_stt_glossary").performScrollTo().performClick()
        compose.onNodeWithTag("confirmed_stt_glossary_editor").performTextReplacement("Jeremias")
        compose.onNodeWithTag("cancel_confirmed_stt_glossary")
            .performScrollTo()
            .performClick()

        assertTrue(saves.isEmpty())
        compose.onNodeWithTag("confirmed_stt_glossary_editor").assertDoesNotExist()

        // Reopening starts from durable state, not the discarded draft.
        compose.onNodeWithTag("edit_confirmed_stt_glossary")
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag("confirmed_stt_glossary_editor")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString(""),
                ),
            )
    }

    private fun callbacks(onSave: (String) -> Unit) = SettingsUiCallbacks(
        onBack = {},
        onStartGettingToKnow = {},
        onModelSelected = {},
        onReasoningEffortSelected = {},
        onVoiceSelected = {},
        onSpeechRateSelected = {},
        onReadAloudModeSelected = {},
        onPreviewVoice = {},
        onStartActionKeySetup = {},
        onStartModelToggleKeySetup = {},
        onCancelActionKeySetup = {},
        onClearActionKey = {},
        onClearModelToggleKey = {},
        onCapabilityAccessRequested = {},
        onConfirmedSttGlossarySaved = onSave,
    )
}
