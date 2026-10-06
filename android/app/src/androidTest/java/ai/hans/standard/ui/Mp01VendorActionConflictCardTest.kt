package ai.hans.standard.ui

import ai.hans.standard.phone.keys.ActionKeyTrigger
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test

class Mp01VendorActionConflictCardTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun compatibleStockShortPressExplainsToggleAndOffersNoFalseConfirmation() {
        compose.setGermanContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        actionKey = ActionKeyUiState(
                            configured = true,
                            dictationTrigger = ActionKeyTrigger.PRESS,
                            mp01VendorConflict = Mp01VendorActionConflictUiState(
                                detected = true,
                                settingsActivityAvailable = true,
                                replacementConfirmed = true,
                                shortcutKinds = setOf(Mp01VendorShortcutUiKind.DICTATION),
                                kind = Mp01VendorActionConflictUiKind.STOCK_SYSTEM_POLICY,
                                stockPressToggleCompatible = true,
                            ),
                        ),
                    ),
                    callbacks = callbacks(),
                )
            }
        }

        compose.onNodeWithTag("settings_group_input").performScrollTo().performClick()

        compose.onNodeWithTag("mp01_vendor_action_instructions")
            .assertTextContains("Kurz drücken, nicht halten", substring = true)
            .assertTextContains("Langes Drücken gehört dem MP01-System", substring = true)
            .assertTextContains(
                "Eine kurze E-Ink-Auffrischung ist normal",
                substring = true,
            )
        compose.onNodeWithTag("action_key_status")
            .assertTextContains("erneut drücken beendet und sendet", substring = true)
        compose.onNodeWithTag("confirm_mp01_vendor_action_cleared").assertDoesNotExist()
        compose.onNodeWithTag("open_mp01_vendor_settings").assertDoesNotExist()
    }

    @Test
    fun incompatibleStockHoldExplainsStandardBoundaryAndStaysBlocked() {
        compose.setGermanContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        actionKey = ActionKeyUiState(
                            dictationTrigger = ActionKeyTrigger.HOLD_TO_TALK,
                            mp01VendorConflict = Mp01VendorActionConflictUiState(
                                detected = true,
                                replacementConfirmed = true,
                                shortcutKinds = setOf(Mp01VendorShortcutUiKind.DICTATION),
                                kind = Mp01VendorActionConflictUiKind.STOCK_SYSTEM_POLICY,
                                stockPressToggleCompatible = false,
                            ),
                        ),
                    ),
                    callbacks = callbacks(),
                )
            }
        }

        compose.onNodeWithTag("settings_group_input").performScrollTo().performClick()

        compose.onNodeWithTag("mp01_vendor_action_instructions")
            .assertTextContains(
                "Hans Standard kann diese Belegung nicht übernehmen",
                substring = true,
            )
            .assertTextContains("Wähle eine andere Taste", substring = true)
        compose.onNodeWithTag("action_key_status")
            .assertTextContains(
                "für Hans noch nicht verfügbar",
                substring = true,
            )
        compose.onNodeWithTag("confirm_mp01_vendor_action_cleared").assertDoesNotExist()
    }

    @Test
    fun permanentStandardSettingsExposeNoDiscardedPrivilegedControls() {
        compose.setGermanContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        actionKey = ActionKeyUiState(
                            dictationTrigger = ActionKeyTrigger.HOLD_TO_TALK,
                            mp01VendorConflict = Mp01VendorActionConflictUiState(
                                detected = true,
                                replacementConfirmed = true,
                                shortcutKinds = setOf(Mp01VendorShortcutUiKind.DICTATION),
                                kind = Mp01VendorActionConflictUiKind.STOCK_SYSTEM_POLICY,
                                stockPressToggleCompatible = false,
                            ),
                        ),
                    ),
                    callbacks = callbacks(),
                )
            }
        }

        compose.onNodeWithTag("settings_group_input").performScrollTo().performClick()

        compose.onNodeWithTag("edition_extension_status").assertDoesNotExist()
        compose.onNodeWithTag("enable_edition_extension_control").assertDoesNotExist()
        compose.onNodeWithTag("disable_edition_extension_control").assertDoesNotExist()
        compose.onNodeWithTag("mp01_vendor_action_instructions")
            .assertTextContains("Wähle eine andere Taste", substring = true)
        compose.onNodeWithTag("action_key_status")
            .assertTextContains("wähle eine andere Taste", substring = true)
        compose.onNodeWithText("Root", substring = true).assertDoesNotExist()
    }

    @Test
    fun legacyAccessibilityCollisionRetainsExplicitNeutralizationFlow() {
        compose.setGermanContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        actionKey = ActionKeyUiState(
                            mp01VendorConflict = Mp01VendorActionConflictUiState(
                                detected = true,
                                settingsActivityAvailable = true,
                                replacementConfirmed = false,
                                shortcutKinds = setOf(Mp01VendorShortcutUiKind.DICTATION),
                                kind = Mp01VendorActionConflictUiKind.LEGACY_ACCESSIBILITY,
                            ),
                        ),
                    ),
                    callbacks = callbacks(),
                )
            }
        }

        compose.onNodeWithTag("settings_group_input").performScrollTo().performClick()

        compose.onNodeWithTag("mp01_vendor_action_instructions")
            .assertTextContains("alle sechs Aktionen auf „Nothing“", substring = true)
        compose.onNodeWithTag("open_mp01_vendor_settings").assertExists()
        compose.onNodeWithTag("confirm_mp01_vendor_action_cleared").assertExists()
    }

    private fun callbacks() = SettingsUiCallbacks(
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
    )
}
