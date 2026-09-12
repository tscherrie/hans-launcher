package ai.hans.standard.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsSetupEntryTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun settingsExposesOneConversationStartOrResumeAction() {
        var starts = 0
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(),
                    callbacks = SettingsUiCallbacks(
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
                        onStartOrResumeSetup = { starts += 1 },
                    ),
                )
            }
        }

        compose.onNodeWithTag("settings_group_personal").performScrollTo().performClick()

        compose.onNodeWithTag("start_or_resume_setup")
            .performScrollTo()
            .assertTextEquals("Setup starten")
            .performClick()
        assertEquals(1, starts)
    }

    @Test
    fun cameraDictationSettingAlwaysDescribesThePhotoVideoChoiceAccurately() {
        val holdEnabled = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(cameraHoldToTalkEnabled = holdEnabled.value),
                    callbacks = SettingsUiCallbacks(
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
                        onStartOrResumeSetup = {},
                    ),
                )
            }
        }

        compose.onNodeWithTag("settings_group_input").performScrollTo().performClick()

        compose.onNodeWithTag("camera_hold_to_talk_status")
            .performScrollTo()
            .assertTextEquals(
                "Aktiv: kurz tippen öffnet die Auswahl für Foto oder Video. Gedrückt halten startet ein Diktat; Loslassen beendet und sendet. Nur im sichtbaren Hans Launcher verfügbar.",
            )

        compose.runOnIdle { holdEnabled.value = false }
        compose.waitForIdle()
        compose.onNodeWithTag("camera_hold_to_talk_status")
            .performScrollTo()
            .assertTextEquals(
                "Aus: Das Kamerasymbol öffnet die Auswahl für Foto oder Video; Halten löst kein Diktat aus.",
            )
    }
}
