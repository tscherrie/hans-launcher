package ai.hans.standard.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsSpeechCredentialTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun missingCredentialOffersLocalConfigurationWithoutRemoval() {
        var configure = 0
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        speechCredentialStatus = SpeechCredentialUiStatus.MISSING,
                    ),
                    callbacks = callbacks(onConfigure = { configure += 1 }),
                )
            }
        }

        compose.onNodeWithTag("settings_group_maintenance").performScrollTo().performClick()

        compose.onNodeWithTag("configure_speech_credential")
            .performScrollTo()
            .performClick()
        compose.onNodeWithText("Sprachzugang einrichten").assertExists()
        compose.onNodeWithTag("remove_speech_credential").assertDoesNotExist()
        assertEquals(1, configure)
    }

    @Test
    fun configuredCredentialCanBeReplacedOrRemovedWithoutRevealingIt() {
        var configure = 0
        var remove = 0
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        speechCredentialStatus = SpeechCredentialUiStatus.AVAILABLE,
                    ),
                    callbacks = callbacks(
                        onConfigure = { configure += 1 },
                        onRemove = { remove += 1 },
                    ),
                )
            }
        }

        compose.onNodeWithTag("settings_group_maintenance").performScrollTo().performClick()

        compose.onNodeWithText("OpenAI-Sprachzugang ist sicher eingerichtet.")
            .performScrollTo()
            .assertExists()
        compose.onNodeWithTag("configure_speech_credential")
            .performScrollTo()
            .performClick()
        compose.onNodeWithText("Sprachzugang ersetzen").assertExists()
        compose.onNodeWithTag("remove_speech_credential")
            .performScrollTo()
            .performClick()
        assertEquals(1, configure)
        assertEquals(1, remove)
    }

    @Test
    fun speechAccessExplainsApiSeparationAndOpensOfficialRemediationPages() {
        var apiKeys = 0
        var billing = 0
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        speechCredentialStatus = SpeechCredentialUiStatus.AVAILABLE,
                    ),
                    callbacks = callbacks(
                        onOpenApiKeys = { apiKeys += 1 },
                        onOpenBilling = { billing += 1 },
                    ),
                )
            }
        }

        compose.onNodeWithTag("settings_group_maintenance").performScrollTo().performClick()
        compose.onNodeWithTag("speech_credential_help").performScrollTo().assertExists()
        compose.onNodeWithTag("open_openai_api_key_page").performScrollTo().performClick()
        compose.onNodeWithTag("open_openai_billing_page").performScrollTo().performClick()
        assertEquals(1, apiKeys)
        assertEquals(1, billing)
    }

    private fun callbacks(
        onConfigure: () -> Unit = {},
        onRemove: () -> Unit = {},
        onOpenApiKeys: () -> Unit = {},
        onOpenBilling: () -> Unit = {},
    ) = SettingsUiCallbacks(
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
        onConfigureSpeechCredential = onConfigure,
        onRemoveSpeechCredential = onRemove,
        onOpenOpenAiApiKeyPage = onOpenApiKeys,
        onOpenOpenAiBillingPage = onOpenBilling,
    )
}
