package ai.hans.standard.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The continuous voice path must not advertise glossary settings it does not consume. */
class SettingsSttGlossaryTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun savedGlossaryIsRetainedWithoutOfferingAnIneffectiveEditor() {
        val terms = listOf("Grenzebach", "Skill Me Now")
        val state = SettingsUiState(confirmedSttGlossary = ConfirmedSttGlossaryUiState(terms = terms))
        val saves = mutableListOf<String>()
        show(state, saves::add)

        listOf("confirmed_stt_glossary_status", "confirmed_stt_glossary_editor",
            "edit_confirmed_stt_glossary", "save_confirmed_stt_glossary",
            "stt_delay_low", "stt_delay_minimal").forEach { tag ->
            compose.onNodeWithTag(tag).assertDoesNotExist()
        }
        compose.onNodeWithTag("voice_task_overview").assertExists()
        compose.onNodeWithTag("codex_dictation_access").assertDoesNotExist()
        compose.onNodeWithTag("voice_usage_help_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("codex_dictation_access").assertExists()
        compose.runOnIdle {
            assertEquals(terms, state.confirmedSttGlossary.terms)
            assertTrue(saves.isEmpty())
        }
    }

    @Test
    fun anEmptyGlossaryDoesNotOfferAnAddButtonOrMutateStorage() {
        val saves = mutableListOf<String>()
        show(SettingsUiState(), saves::add)
        compose.onNodeWithTag("edit_confirmed_stt_glossary").assertDoesNotExist()
        compose.onNodeWithTag("dictation_microphone_access").assertExists()
        compose.runOnIdle { assertTrue(saves.isEmpty()) }
    }

    private fun show(state: SettingsUiState, onSave: (String) -> Unit) {
        compose.setGermanContent {
            MaterialTheme { SettingsScreen(state, callbacks(onSave)) }
        }
        compose.onNodeWithTag("settings_group_speech").performScrollTo().performClick()
    }

    private fun callbacks(onSave: (String) -> Unit) = SettingsUiCallbacks(
        onBack = {}, onStartGettingToKnow = {}, onModelSelected = {},
        onReasoningEffortSelected = {}, onVoiceSelected = {}, onSpeechRateSelected = {},
        onReadAloudModeSelected = {}, onPreviewVoice = {}, onStartActionKeySetup = {},
        onStartModelToggleKeySetup = {}, onCancelActionKeySetup = {}, onClearActionKey = {},
        onClearModelToggleKey = {}, onCapabilityAccessRequested = {}, onConfirmedSttGlossarySaved = onSave,
    )
}
