package ai.hans.standard.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsRemoteWorkerTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun openingAdvancedWorkIsPassiveUntilTheSeparateActivationTap() {
        var opens = 0
        var saves = 0
        var activations = 0
        show(
            remoteWorker = configuredState(),
            callbacks = callbacks().copy(
                onRemoteWorkerSettingsOpened = { opens++ },
                onSaveRemoteWorkerConfiguration = { saves++ },
                onActivateRemoteWorker = { activations++ },
            ),
        )

        compose.onNodeWithTag("settings_group_maintenance").performScrollTo().performClick()
        compose.onNodeWithTag("remote_worker_id").assertDoesNotExist()
        compose.onNodeWithTag("remote_worker_configuration_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("remote_worker_state").assertExists()
        compose.onNodeWithTag("remote_worker_effective_state")
            .assertTextContains("Gewünscht: Ein", substring = true)
            .assertTextContains("Wirksam: Nein", substring = true)
        compose.runOnIdle {
            assertEquals(1, opens)
            assertEquals(0, saves)
            assertEquals(0, activations)
        }

        compose.onNodeWithTag("activate_remote_worker")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle {
            assertEquals(0, saves)
            assertEquals(1, activations)
        }
    }

    @Test
    fun savingEnabledRequestNeverImplicitlyActivates() {
        var saved: RemoteWorkerConfigurationUiDraft? = null
        var activations = 0
        show(
            remoteWorker = configuredState().copy(
                requestedEnabled = false,
                status = RemoteWorkerSettingsUiStatus.DISABLED,
            ),
            callbacks = callbacks().copy(
                onSaveRemoteWorkerConfiguration = { saved = it },
                onActivateRemoteWorker = { activations++ },
            ),
        )
        compose.onNodeWithTag("settings_group_maintenance").performScrollTo().performClick()
        compose.onNodeWithTag("remote_worker_id").assertDoesNotExist()
        compose.onNodeWithTag("remote_worker_configuration_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("remote_worker_requested_enabled").performScrollTo().performClick()
        compose.onNodeWithTag("save_remote_worker")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        compose.runOnIdle {
            assertEquals("gx10-1", saved?.workerId)
            assertEquals("https://worker.example/", saved?.httpsOrigin)
            assertEquals("build.gradle", saved?.adapters?.single()?.id)
            assertEquals("1.2.3", saved?.adapters?.single()?.version)
            assertEquals(true, saved?.requestedEnabled)
            assertEquals(0, activations)
        }
    }

    @Test
    fun effectiveStateDoesNotOfferAnotherActivation() {
        show(
            remoteWorker = configuredState().copy(
                effective = true,
                status = RemoteWorkerSettingsUiStatus.EFFECTIVE,
            ),
        )
        compose.onNodeWithTag("settings_group_maintenance").performScrollTo().performClick()
        compose.onNodeWithTag("remote_worker_id").assertDoesNotExist()
        compose.onNodeWithTag("remote_worker_configuration_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("remote_worker_effective_state")
            .assertTextContains("Wirksam: Ja", substring = true)
        compose.onNodeWithTag("activate_remote_worker").performScrollTo()
            .assertExists()
        // The visible effective state is authoritative; the action must no longer be enabled.
        compose.onNodeWithTag("activate_remote_worker").assertIsNotEnabled()
    }

    private fun show(
        remoteWorker: RemoteWorkerSettingsUiState,
        callbacks: SettingsUiCallbacks = callbacks(),
    ) {
        compose.setGermanContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(remoteWorker = remoteWorker),
                    callbacks = callbacks,
                )
            }
        }
    }

    private fun configuredState() = RemoteWorkerSettingsUiState(
        revision = 4L,
        loaded = true,
        configured = true,
        requestedEnabled = true,
        effective = false,
        status = RemoteWorkerSettingsUiStatus.NEEDS_ACTIVATION,
        workerId = "gx10-1",
        httpsOrigin = "https://worker.example/",
        serverSpkiSha256 = "a".repeat(64),
        approvedAdapters = listOf(RemoteWorkerAdapterUiDraft("build.gradle", "1.2.3")),
    )

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
