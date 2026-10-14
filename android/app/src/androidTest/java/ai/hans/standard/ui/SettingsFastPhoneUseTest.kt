package ai.hans.standard.ui

import ai.hans.standard.settings.fastphone.FastPhoneCredentialStatus
import ai.hans.standard.settings.fastphone.FastPhoneServiceStatus
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsFastPhoneUseTest {
    @get:Rule val compose = createComposeRule()

    @Test fun optInNeverChangesOptimisticallyAndOfficialKeyBillingActionsAreSeparateFromVoice() {
        val selected = mutableListOf<Boolean>()
        var configure = 0
        var keys = 0
        var billing = 0
        var forget = 0
        val state = mutableStateOf(SettingsUiState())
        compose.setGermanContent {
            MaterialTheme {
                SettingsContent(state.value, callbacks().copy(
                    onFastPhoneUseEnabledChanged = selected::add,
                    onConfigureFastPhoneApiKey = { configure++ },
                    onOpenOpenAiApiKeyPage = { keys++ },
                    onOpenOpenAiBillingPage = { billing++ },
                    onClearFastPhoneRoutines = { forget++ },
                ), group = SettingsGroup.RUNTIME)
            }
        }
        compose.onNodeWithTag("fast_phone_use_enabled").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("fast_phone_use_enabled").assertIsOff()
        assertEquals(listOf(true), selected)
        compose.runOnIdle { state.value = state.value.copy(fastPhoneUse = FastPhoneUseUiState(true)) }
        compose.onNodeWithTag("fast_phone_use_enabled").assertIsOn()
        compose.onNodeWithTag("fast_phone_use_status").performScrollTo().assertTextEquals(
            "Eingeschaltet, aber ein OpenAI-API-Key fehlt noch.")
        compose.onNodeWithTag("configure_fast_phone_api_key").performScrollTo().performClick()
        compose.onNodeWithTag("fast_phone_create_api_key").performScrollTo().performClick()
        compose.onNodeWithTag("fast_phone_open_billing").performScrollTo().performClick()
        compose.onNodeWithTag("clear_fast_phone_routines").performScrollTo().performClick()
        assertEquals(1, configure); assertEquals(1, keys); assertEquals(1, billing)
        assertEquals(1, forget)
        compose.onNodeWithTag("configure_speech_credential").assertDoesNotExist()
    }

    @Test fun savedKeyIsNotPresentedAsVerifiedAndQuotaFailureProvidesBillingAction() {
        var remove = 0
        val state = mutableStateOf(SettingsUiState(fastPhoneUse = FastPhoneUseUiState(
            true, FastPhoneCredentialStatus.AVAILABLE, FastPhoneServiceStatus.NOT_VERIFIED,
        )))
        compose.setGermanContent {
            MaterialTheme { SettingsContent(state.value, callbacks().copy(
                onRemoveFastPhoneApiKey = { remove++ },
            ), group = SettingsGroup.RUNTIME) }
        }
        compose.onNodeWithTag("fast_phone_use_status").performScrollTo().assertTextEquals(
            "Key gespeichert. Der API-Zugang wird beim ersten Bildschirmauftrag geprüft.")
        compose.runOnIdle { state.value = state.value.copy(fastPhoneUse = state.value.fastPhoneUse.copy(
            serviceStatus = FastPhoneServiceStatus.OUT_OF_CREDITS)) }
        compose.onNodeWithTag("fast_phone_use_status").performScrollTo().assertTextEquals(
            "OpenAI-API-Guthaben oder Ausgabenlimit erreicht. API-Abrechnung prüfen, dann Fast Phone Use aus- und einschalten.")
        compose.onNodeWithTag("fast_phone_open_billing").performScrollTo().assertExists()
        compose.onNodeWithTag("remove_fast_phone_api_key").performScrollTo().performClick()
        assertEquals(1, remove)
    }

    private fun callbacks() = SettingsUiCallbacks(onBack = {}, onStartGettingToKnow = {},
        onModelSelected = {}, onReasoningEffortSelected = {}, onVoiceSelected = {},
        onSpeechRateSelected = {}, onReadAloudModeSelected = {}, onPreviewVoice = {},
        onStartActionKeySetup = {}, onStartModelToggleKeySetup = {}, onCancelActionKeySetup = {},
        onClearActionKey = {}, onClearModelToggleKey = {}, onCapabilityAccessRequested = {})
}
