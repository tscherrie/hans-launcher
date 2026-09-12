package ai.hans.standard.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WorkbenchScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun verifiedInventoriesAndPassiveRuntimeStateAreVisible() {
        val workspaceHandle = "a".repeat(64)
        val artifactHandle = "art_${"b".repeat(64)}"
        show(
            WorkbenchUiState(
                workspaces = listOf(
                    WorkbenchWorkspaceUiModel(workspaceHandle, fileCount = 3, byteCount = 2_048),
                ),
                artifacts = listOf(
                    WorkbenchArtifactUiModel(
                        handle = artifactHandle,
                        displayName = "Notizen.txt",
                        mimeType = "text/plain",
                        byteCount = 1_024,
                        originLabel = "Python",
                        workspaceHandle = workspaceHandle,
                    ),
                ),
                python = WorkbenchPythonUiState(
                    initialized = false,
                    phaseLabel = "Noch nicht gestartet",
                ),
            ),
        )

        compose.onNodeWithTag("workspace_$workspaceHandle").assertExists()
        compose.onNodeWithTag("artifact_$artifactHandle").assertExists()
        compose.onNodeWithText("Handle: $workspaceHandle").assertExists()
        compose.onNodeWithText("Handle: $artifactHandle").assertExists()
        compose.onNodeWithText("3 Dateien · 2,0 KB").assertExists()
        compose.onNodeWithText("text/plain · 1,0 KB").assertExists()
        compose.onNodeWithText("Die Laufzeit wurde durch diese Ansicht nicht gestartet.")
            .assertExists()
        compose.onNodeWithTag("workbench_inventory")
            .performScrollToNode(hasTestTag("workbench_git_header"))
        compose.onNodeWithTag("workbench_git_header")
            .assertTextContains("Git")
            .assertTextContains("Lokale Git-Werkzeuge verfügbar")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        compose.onNodeWithText(
            "Der Repository-Bestand wird noch nicht passiv erfasst. Diese Ansicht behauptet daher nicht, dass keine Repositories vorhanden sind.",
        ).assertExists()
    }

    @Test
    fun loadingErrorEmptyAndExplicitCallbacksAreRenderedWithoutPolling() {
        var backs = 0
        var refreshes = 0
        val state = mutableStateOf(
            WorkbenchUiState(
                loading = true,
                errorMessage = "Prüfung fehlgeschlagen.",
            ),
        )
        val callbacks = WorkbenchUiCallbacks(
            onBack = { backs++ },
            onRefresh = { refreshes++ },
        )
        compose.setContent {
            MaterialTheme {
                WorkbenchScreen(state = state.value, callbacks = callbacks)
            }
        }

        compose.onNodeWithTag("workbench_loading").assertExists()
        compose.onNodeWithTag("workbench_error").assertExists()
        compose.onNodeWithTag("refresh_workbench").assertIsNotEnabled()
        compose.onNodeWithTag("navigate_back").performClick()
        compose.runOnIdle {
            assertEquals(1, backs)
            assertEquals(0, refreshes)
        }

        compose.runOnIdle { state.value = WorkbenchUiState() }
        compose.onNodeWithTag("workbench_workspaces_empty").assertExists()
        compose.onNodeWithTag("workbench_artifacts_empty").assertExists()
        compose.onNodeWithTag("refresh_workbench").performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test
    fun stoppedWorkerIsExplainedAsAutomaticOnDemandStartup() {
        show(
            WorkbenchUiState(
                python = WorkbenchPythonUiState(
                    initialized = true,
                    phaseLabel = "Bereit bei Bedarf",
                    startsOnDemand = true,
                ),
            ),
        )

        compose.onNodeWithTag("workbench_python_header")
            .assertTextContains("Bereit bei Bedarf")
        compose.onNodeWithText(
            "Wird bei der nächsten Python-Aufgabe automatisch gestartet und geprüft.",
        ).assertExists()
        compose.onNodeWithText("Laufzeit nicht bereit").assertDoesNotExist()
    }

    private fun show(
        state: WorkbenchUiState,
        callbacks: WorkbenchUiCallbacks = WorkbenchUiCallbacks(),
    ) {
        compose.setContent {
            MaterialTheme {
                WorkbenchScreen(state = state, callbacks = callbacks)
            }
        }
    }
}
