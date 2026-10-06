package ai.hans.standard.ui

import ai.hans.standard.phone.keys.ActionKeyTrigger
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VoiceSettingsCleanupUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun speechHelpSeparatesBatchDictationFromAnOngoingCall() {
        show(SettingsGroup.SPEECH)
        compose.onNodeWithTag("codex_dictation_access").assertDoesNotExist()
        compose.onNodeWithTag("voice_usage_help_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("codex_dictation_access")
            .assertTextContains("sofort sprechen", substring = true)
            .assertTextContains("Ohne eingerichtete Taste", substring = true)
            .assertTextContains("Erneut drücken beendet die Aufnahme", substring = true)
            .assertTextContains("sendet den fertigen Text", substring = true)
        compose.onNodeWithTag("voice_task_idle_help")
            .assertTextContains("zwei Minuten", substring = true)
            .assertTextContains("keine Live-Sprachverbindung", substring = true)
        compose.onNodeWithTag("voice_call_help")
            .assertTextContains("Oben auf Hans tippen", substring = true)
            .assertTextContains("Verabschiedung", substring = true)
    }

    @Test fun compactSpeechOverviewFitsSmallLargeFontViewportAndHelpHasNoSideEffects() {
        var effects = 0
        compose.setGermanContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.3f)) {
                MaterialTheme {
                    Box(Modifier.width(360.dp).height(460.dp)) {
                        SettingsContent(
                            state = SettingsUiState(
                                liveVoices = listOf(VoiceUiOption("cove", "Cove")),
                                selectedLiveVoiceId = "cove",
                            ),
                            callbacks = callbacks().copy(
                                onPreviewVoice = { effects++ },
                                onLiveVoiceSelected = { effects++ },
                                onReadAloudModeSelected = { effects++ },
                                onCapabilityAccessRequested = { effects++ },
                                onStartActionKeySetup = { effects++ },
                            ),
                            group = SettingsGroup.SPEECH,
                        )
                    }
                }
            }
        }
        // No scroll is permitted before these assertions: actual controls lead the page.
        compose.onNodeWithTag("open_voice_chooser").assertIsDisplayed().assertTextEquals("Hans’ Stimme: Cove")
        compose.onNodeWithTag("voice_task_overview").assertIsDisplayed()
        compose.onNodeWithTag("voice_call_overview").assertIsDisplayed()
        compose.onNodeWithTag("voice_usage_help_toggle").assertIsDisplayed().assertTextEquals("So funktioniert’s")
        listOf("codex_dictation_access", "voice_call_help", "voice_task_idle_help", "codex_live_access").forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
        compose.onNodeWithTag("preview_voice").assertDoesNotExist()
        compose.onNodeWithTag("voice_usage_help_toggle").performClick()
        listOf("codex_dictation_access", "voice_call_help", "voice_task_idle_help", "codex_live_access").forEach {
            compose.onNodeWithTag(it).assertExists()
        }
        compose.onNodeWithTag("voice_usage_help_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("codex_dictation_access").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, effects) }
    }

    @Test fun legacyHoldMappingHasOnlyTheUniformShortPressHelpAndNoModeSwitch() {
        var triggerWrites = 0
        show(
            SettingsGroup.INPUT,
            SettingsUiState(actionKey = ActionKeyUiState(
                assigned = true, configured = true, dictationTrigger = ActionKeyTrigger.HOLD_TO_TALK,
            )),
            callbacks().copy(onDictationTriggerSelected = { triggerWrites++ }),
        )
        compose.onNodeWithTag("dictation_trigger_hold").assertDoesNotExist()
        compose.onNodeWithTag("dictation_trigger_toggle").assertDoesNotExist()
        compose.onNodeWithTag("action_key_status").assertTextContains("Kurz drücken", substring = true)
            .assertTextContains("ausgeblendet", substring = true)
        compose.onNodeWithTag("clear_action_key").assertExists()
        compose.runOnIdle { assertEquals(0, triggerWrites) }
    }

    @Test fun assignedButUnavailableKeyKeepsChangeAndRemoveWithoutClaimingItWorks() {
        var changes = 0
        var removes = 0
        show(
            SettingsGroup.INPUT,
            SettingsUiState(actionKey = ActionKeyUiState(assigned = true, configured = false)),
            callbacks().copy(onStartActionKeySetup = { changes++ }, onClearActionKey = { removes++ }),
        )
        compose.onNodeWithTag("action_key_status")
            .assertTextContains("momentan nicht verfügbar", substring = true)
            .assertTextContains("Berechtigungen", substring = true)
        compose.onNodeWithTag("setup_action_key").performScrollTo().performClick()
        compose.onNodeWithTag("clear_action_key").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, changes)
            assertEquals(1, removes)
        }
    }

    @Test fun unassignedKeyExplainsTheOnScreenMicrophone() {
        show(SettingsGroup.INPUT)
        compose.onNodeWithTag("action_key_status")
            .assertTextContains("Mikrofon unten rechts", substring = true)
        compose.onNodeWithTag("clear_action_key").assertDoesNotExist()
        compose.onNodeWithTag("setup_action_key").assertExists()
    }

    @Test fun displayHelpIsUserExpandedWithoutChangingTheEffectiveDisplaySetting() {
        var writes = 0
        show(SettingsGroup.INPUT, callbacks = callbacks().copy(onDisplayMotionModeSelected = { writes++ }))
        compose.onNodeWithTag("display_motion_help").assertDoesNotExist()
        compose.onNodeWithTag("display_motion_effective").assertExists()
        compose.onNodeWithTag("display_motion_help_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("display_motion_help").assertExists()
        compose.onNodeWithTag("display_motion_help_toggle").performClick()
        compose.onNodeWithTag("display_motion_help").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, writes) }
    }

    private fun show(
        group: SettingsGroup,
        state: SettingsUiState = SettingsUiState(),
        callbacks: SettingsUiCallbacks = callbacks(),
    ) {
        compose.setGermanContent {
            MaterialTheme { SettingsContent(state, callbacks, group) }
        }
    }

    private fun callbacks() = SettingsUiCallbacks(
        onBack = {}, onStartGettingToKnow = {}, onModelSelected = {},
        onReasoningEffortSelected = {}, onVoiceSelected = {}, onSpeechRateSelected = {},
        onReadAloudModeSelected = {}, onPreviewVoice = {}, onStartActionKeySetup = {},
        onStartModelToggleKeySetup = {}, onCancelActionKeySetup = {}, onClearActionKey = {},
        onClearModelToggleKey = {}, onCapabilityAccessRequested = {},
    )
}
