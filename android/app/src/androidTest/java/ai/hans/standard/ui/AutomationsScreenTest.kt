package ai.hans.standard.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AutomationsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun persistedPoliciesAreVisibleAndEveryManagementActionIsExplicit() {
        val actions = mutableListOf<String>()
        val item = AutomationUiModel(
            id = "morning_briefing",
            revision = 7,
            instructionPreview = "Fasse meinen Morgen zusammen.",
            scheduleLabel = "Täglich · ab 30.08.2026, 09:00 · Telefon-Zeitzone",
            enabled = true,
            unattended = false,
            requiresUnlockedDevice = false,
            missedRunMode = AutomationMissedRunUiMode.RUN_LATEST,
            timingLabel = "Zuverlässig, Android darf den Zeitpunkt leicht bündeln",
            nextRunLabel = "Nächster Termin wird vom Android-Planer berechnet",
            lastRunLabel = "Noch kein Lauf",
            pendingCount = 0,
            history = listOf(
                AutomationRunHistoryUiModel(
                    headline = "Erfolgreich · 30.08.2026, 09:02",
                    scheduledLabel = "Termin 30.08.2026, 09:00",
                ),
            ),
        )
        compose.setContent {
            MaterialTheme {
                AutomationsScreen(
                    state = AutomationsUiState(items = listOf(item)),
                    callbacks = AutomationsUiCallbacks(
                        onBack = { actions += "back" },
                        onRefresh = { actions += "refresh" },
                        onCreateInChat = { actions += "create" },
                        onEditInChat = { id, revision -> actions += "edit:$id:$revision" },
                        onEnabledChanged = { id, revision, enabled ->
                            actions += "enabled:$id:$revision:$enabled"
                        },
                        onUnattendedChanged = { id, revision, unattended ->
                            actions += "unattended:$id:$revision:$unattended"
                        },
                        onRunNow = { id, revision -> actions += "run:$id:$revision" },
                        onDelete = { id, revision -> actions += "delete:$id:$revision" },
                    ),
                )
            }
        }

        compose.onNodeWithTag("automation_enabled_morning_briefing").assertIsOn()
        compose.onNodeWithTag("automation_unattended_morning_briefing")
            .performScrollTo()
            .assertIsOff()
            .performClick()
        compose.onNodeWithTag("edit_automation_morning_briefing")
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag("history_automation_morning_briefing")
            .performScrollTo()
            .performClick()
        compose.onNodeWithText("Erfolgreich · 30.08.2026, 09:02").assertExists()
        compose.onNodeWithText("Termin 30.08.2026, 09:00").assertExists()
        compose.onNodeWithTag("close_automation_history").performClick()
        compose.onNodeWithTag("run_automation_morning_briefing")
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag("delete_automation_morning_briefing").performClick()
        compose.onNodeWithText("Automation löschen?").assertExists()
        compose.onNodeWithTag("confirm_delete_automation").performClick()
        compose.onNodeWithTag("create_automation_in_chat").performClick()

        compose.runOnIdle {
            assertEquals(
                listOf(
                    "unattended:morning_briefing:7:true",
                    "edit:morning_briefing:7",
                    "run:morning_briefing:7",
                    "delete:morning_briefing:7",
                    "create",
                ),
                actions,
            )
        }
    }

    @Test
    fun emptyAndErrorStatesAreHonestAndRefreshRemainsEventDriven() {
        var refreshes = 0
        compose.setContent {
            MaterialTheme {
                AutomationsScreen(
                    state = AutomationsUiState(errorMessage = "Speicher nicht lesbar."),
                    callbacks = AutomationsUiCallbacks(onRefresh = { refreshes++ }),
                )
            }
        }

        compose.onNodeWithTag("automations_empty").assertDoesNotExist()
        compose.onNodeWithTag("automation_error").assertExists()
        compose.onNodeWithTag("refresh_automations").performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test
    fun onePersistedMutationDisablesEveryOtherMutationSurface() {
        val first = automationItem("first")
        val second = automationItem("second")
        compose.setContent {
            MaterialTheme {
                AutomationsScreen(
                    state = AutomationsUiState(
                        items = listOf(first, second),
                        operationAutomationId = first.id,
                    ),
                    callbacks = AutomationsUiCallbacks(),
                )
            }
        }

        compose.onNodeWithTag("create_automation_in_chat").assertIsNotEnabled()
        compose.onNodeWithTag("refresh_automations").assertIsNotEnabled()
        compose.onNodeWithTag("automation_enabled_second")
            .performScrollTo()
            .assertIsNotEnabled()
        compose.onNodeWithTag("run_automation_second")
            .performScrollTo()
            .assertIsNotEnabled()
        compose.onNodeWithTag("edit_automation_second").assertIsNotEnabled()
        compose.onNodeWithTag("history_automation_second").assertIsNotEnabled()
        compose.onNodeWithTag("delete_automation_second").assertIsNotEnabled()
    }

    private fun automationItem(id: String) = AutomationUiModel(
        id = id,
        revision = 1,
        instructionPreview = "Prüfe den Zustand.",
        scheduleLabel = "Täglich",
        enabled = true,
        unattended = true,
        requiresUnlockedDevice = false,
        missedRunMode = AutomationMissedRunUiMode.RUN_LATEST,
        timingLabel = "Zuverlässig",
        nextRunLabel = "Vorgemerkt",
        lastRunLabel = "Noch kein Lauf",
        pendingCount = 0,
    )
}
