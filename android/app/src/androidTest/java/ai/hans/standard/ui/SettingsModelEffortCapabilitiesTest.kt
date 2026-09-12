package ai.hans.standard.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsModelEffortCapabilitiesTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun standaloneSettingsRefreshCatalogOnRuntimeGroupOpenNotComposition() {
        var refreshes = 0
        val state = mutableStateOf(SettingsUiState())
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = state.value,
                    callbacks = callbacks().copy(onModelSettingsOpened = { refreshes++ }),
                )
            }
        }
        compose.runOnIdle { assertEquals(0, refreshes) }
        compose.onNodeWithTag("settings_group_permissions").performScrollTo().performClick()
        compose.onNodeWithTag("navigate_back").performClick()
        compose.runOnIdle { assertEquals(0, refreshes) }

        compose.onNodeWithTag("settings_group_runtime").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, refreshes)
            state.value = state.value.copy(runtimeNotice = "Katalog aktualisiert")
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, refreshes) }
        compose.onNodeWithTag("navigate_back").performClick()
        compose.onNodeWithTag("settings_group_runtime").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, refreshes) }
    }

    @Test
    fun immediateModelAndEffortAcknowledgementsUpdateMarkersWithoutSendingAMessage() {
        val selected = mutableListOf<String>()
        val state = mutableStateOf(SettingsUiState(
            models = ModelUiOption.HANS_MODELS,
            reasoningEfforts = listOf(ReasoningEffortUiOption.MEDIUM, ReasoningEffortUiOption.HIGH),
            selectedModelId = "gpt-5.6-luna",
            selectedReasoningEffortId = "medium",
        ))
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = state.value,
                    callbacks = callbacks(
                        onModel = {
                            selected += it
                            state.value = state.value.copy(runtimeNotice = "Astra · Mittel wird bestätigt …")
                        },
                        onEffort = {
                            selected += it
                            state.value = state.value.copy(runtimeNotice = "Astra · Hoch wird bestätigt …")
                        },
                    ),
                )
            }
        }
        compose.onNodeWithTag("settings_group_runtime").performScrollTo().performClick()
        compose.onNodeWithTag("model_gpt-6-astra").performClick()
        compose.onNodeWithTag("runtime_notice").assertIsDisplayed()
        compose.onNodeWithTag("model_gpt-6-astra").assertIsNotSelected()
        compose.runOnIdle {
            state.value = state.value.copy(selectedModelId = "gpt-6-astra", runtimeNotice = "")
        }
        compose.onNodeWithTag("model_gpt-6-astra").assertIsSelected()
        compose.onNodeWithTag("model_gpt-5.6-luna").assertIsNotSelected()
        compose.onNodeWithTag("effort_high").performScrollTo().performClick()
        compose.onNodeWithTag("effort_high").assertIsNotSelected()
        compose.runOnIdle {
            state.value = state.value.copy(selectedReasoningEffortId = "high", runtimeNotice = "")
        }
        compose.onNodeWithTag("effort_high").assertIsSelected()
        compose.onNodeWithTag("effort_medium").assertIsNotSelected()
        compose.onNodeWithTag("runtime_notice").assertDoesNotExist()
        assertEquals(listOf("gpt-6-astra", "high"), selected)
    }

    @Test
    fun astraCanBeRequestedWithoutOptimisticallyChangingConfirmedSelection() {
        val selected = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        models = ModelUiOption.HANS_MODELS,
                        selectedModelId = "gpt-5.6-luna",
                    ),
                    callbacks = callbacks(onModel = selected::add),
                )
            }
        }

        compose.onNodeWithTag("settings_group_runtime").performScrollTo().performClick()
        compose.onNodeWithTag("model_gpt-6-astra").performScrollTo().assertTextEquals("Astra").performClick()
        assertEquals(listOf("gpt-6-astra"), selected)
        compose.onNodeWithTag("model_gpt-6-astra").assertIsNotSelected()
        compose.onNodeWithTag("model_gpt-5.6-luna").assertIsSelected()
    }

    @Test
    fun onlyAdvertisedEffortsAreRenderedAndSelectable() {
        val selected = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        models = ModelUiOption.HANS_MODELS,
                        reasoningEfforts = listOf(
                            ReasoningEffortUiOption.HIGH,
                            ReasoningEffortUiOption.MAX,
                        ),
                        selectedModelId = "gpt-5.6-terra",
                        selectedReasoningEffortId = "high",
                    ),
                    callbacks = callbacks(onEffort = selected::add),
                )
            }
        }

        compose.onNodeWithTag("settings_group_runtime").performScrollTo().performClick()

        compose.onNodeWithTag("effort_high").performScrollTo().assertExists().performClick()
        compose.onNodeWithTag("effort_max").performScrollTo().assertExists().performClick()
        compose.onNodeWithTag("effort_low").assertDoesNotExist()
        compose.onNodeWithTag("effort_medium").assertDoesNotExist()
        compose.onNodeWithTag("effort_xhigh").assertDoesNotExist()
        compose.onNodeWithTag("effort_ultra").assertDoesNotExist()
        assertEquals(listOf("high", "max"), selected)
    }

    @Test
    fun advertisedFastModeRendersStandardAndFastChoices() {
        val selected = mutableListOf<Boolean>()
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        fastModeAvailable = true,
                        fastModeEnabled = false,
                    ),
                    callbacks = callbacks(onFastMode = selected::add),
                )
            }
        }

        compose.onNodeWithTag("settings_group_runtime").performScrollTo().performClick()

        compose.onNodeWithTag("service_tier_standard")
            .performScrollTo()
            .assertExists()
            .performClick()
        compose.onNodeWithTag("service_tier_fast")
            .performScrollTo()
            .assertExists()
            .performClick()
        assertEquals(listOf(false, true), selected)
    }

    @Test
    fun codexUpdateShowsBundledVersionConfirmedStateAndSingleAction() {
        var updateRequests = 0
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        codexUpdate = CodexUpdateUiState(
                            bundledRuntimeVersion = "9.8.7-test",
                            runtimeReady = true,
                            updateUrlConfigured = false,
                        ),
                    ),
                    callbacks = callbacks(onUpdate = { updateRequests += 1 }),
                )
            }
        }

        compose.onNodeWithTag("settings_group_runtime").performScrollTo().performClick()
        compose.onNodeWithTag("codex_runtime_version")
            .performScrollTo()
            .assertTextEquals("Installiert: Codex 9.8.7-test")
        compose.onNodeWithTag("codex_runtime_state")
            .performScrollTo()
            .assertTextEquals("Die eingebettete Codex-Runtime ist von Hans als bereit bestätigt.")
        compose.onNodeWithTag("codex_update_delivery")
            .performScrollTo()
            .assertTextEquals(
                "Codex ist fest in der signierten Hans-App enthalten und wird zusammen mit Hans aktualisiert. Für diesen internen Build ist noch keine Aktualisierungsseite hinterlegt.",
            )
        compose.onNodeWithTag("codex_update")
            .performScrollTo()
            .assertTextEquals("Codex aktualisieren")
            .performClick()
        assertEquals(1, updateRequests)
    }

    @Test
    fun configuredCodexUpdateExplainsTheSignedHansDeliveryAndFiresOnce() {
        var updateRequests = 0
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        codexUpdate = CodexUpdateUiState(
                            bundledRuntimeVersion = "9.8.7-test",
                            runtimeReady = true,
                            updateUrlConfigured = true,
                        ),
                    ),
                    callbacks = callbacks(onUpdate = { updateRequests += 1 }),
                )
            }
        }

        compose.onNodeWithTag("settings_group_runtime").performScrollTo().performClick()
        compose.onNodeWithTag("codex_update_delivery")
            .performScrollTo()
            .assertTextEquals(
                "Die Aktualisierungsseite bietet die signierte Hans-Version mit der enthaltenen Codex-Runtime an. Android bestätigt die Installation.",
            )
        compose.onNodeWithTag("codex_update").performClick()
        assertEquals(1, updateRequests)
    }

    private fun callbacks(
        onModel: (String) -> Unit = {},
        onEffort: (String) -> Unit = {},
        onFastMode: (Boolean) -> Unit = {},
        onUpdate: () -> Unit = {},
    ) = SettingsUiCallbacks(
        onBack = {},
        onStartGettingToKnow = {},
        onModelSelected = onModel,
        onReasoningEffortSelected = onEffort,
        onFastModeChanged = onFastMode,
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
        onConfigureSpeechCredential = {},
        onRemoveSpeechCredential = {},
        onUpdateCodex = onUpdate,
    )
}
