package ai.hans.standard.ui

import androidx.compose.material3.MaterialTheme
import ai.hans.standard.phone.display.DisplayMotionMode
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsSpeechCredentialTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun missingApiCredentialDoesNotBlockLiveVoicePreviewOrOfferKeySetup() {
        var configure = 0
        var previews = 0
        compose.setGermanContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        speechCredentialStatus = SpeechCredentialUiStatus.MISSING,
                        liveVoices = listOf(VoiceUiOption("cove", "cove")),
                        selectedLiveVoiceId = "cove",
                    ),
                    callbacks = callbacks(onConfigure = { configure += 1 }).copy(onPreviewVoice = { previews++ }),
                )
            }
        }

        compose.onNodeWithTag("settings_group_speech").performScrollTo().performClick()
        compose.onNodeWithTag("configure_speech_credential").assertDoesNotExist()
        compose.onNodeWithTag("advanced_read_aloud_toggle").assertDoesNotExist()
        compose.onNodeWithTag("open_voice_chooser").performScrollTo().performClick()
        compose.onNodeWithTag("preview_voice").assertIsEnabled().performClick()
        compose.onNodeWithTag("remove_speech_credential").assertDoesNotExist()
        assertEquals(0, configure)
        assertEquals(1, previews)
    }

    @Test
    fun existingCredentialRemainsUntouchedAndIsNotExposedAsAReadAloudRequirement() {
        var configure = 0
        var remove = 0
        compose.setGermanContent {
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

        compose.onNodeWithTag("settings_group_speech").performScrollTo().performClick()
        compose.onNodeWithTag("configure_speech_credential").assertDoesNotExist()
        compose.onNodeWithTag("advanced_read_aloud_toggle").assertDoesNotExist()
        compose.onNodeWithTag("speech_credential_status").assertDoesNotExist()
        compose.onNodeWithTag("remove_speech_credential").assertDoesNotExist()
        compose.onNodeWithTag("open_openai_api_key_page").assertDoesNotExist()
        compose.onNodeWithTag("open_openai_billing_page").assertDoesNotExist()
        assertEquals(0, configure)
        assertEquals(0, remove)
    }

    @Test
    fun keyfreeReadAloudExplainsSharedLiveVoiceInEnglishFallbackAndGermanOnEink() {
        var apiKeys = 0
        var billing = 0
        val language = mutableStateOf("en-US")
        compose.setContent {
            val base = LocalContext.current
            val configuration = LocalConfiguration.current
            val localized = remember(configuration, language.value) {
                Configuration(configuration).apply { setLocales(LocaleList.forLanguageTags(language.value)) }
            }
            val context = remember(base, localized) { base.createConfigurationContext(localized) }
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides localized) {
                MaterialTheme {
                    SettingsContent(
                        state = SettingsUiState(displayMotionMode = DisplayMotionMode.E_INK),
                        callbacks = callbacks(onOpenApiKeys = { apiKeys++ }, onOpenBilling = { billing++ }),
                        group = SettingsGroup.SPEECH,
                    )
                }
            }
        }
        listOf("en-US", "fr-FR", "de-DE").forEach { locale ->
            compose.runOnIdle { language.value = locale }
            compose.onNodeWithTag("read_aloud_live_voice_help").performScrollTo().assertTextEquals(
                if (locale == "de-DE") "Liest fertige Textantworten mit derselben Stimme vor. Antworten während eines Telefonats bleiben davon unberührt."
                else "Reads completed text replies aloud, using the same voice. This does not change replies during a phone call.")
            compose.onNodeWithTag("open_openai_api_key_page").assertDoesNotExist()
            compose.onNodeWithTag("open_openai_billing_page").assertDoesNotExist()
        }
        assertEquals(0, apiKeys)
        assertEquals(0, billing)
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
