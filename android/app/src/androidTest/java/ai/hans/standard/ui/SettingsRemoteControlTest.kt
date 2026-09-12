package ai.hans.standard.ui

import ai.hans.standard.remotecontrol.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsRemoteControlTest {
    @get:Rule val compose = createComposeRule()

    @Test fun openingIsReadOnlyAndConsentRequiresItsExplicitDialog() {
        var opens = 0
        var enables = 0
        val state = mutableStateOf(SettingsUiState(remoteControl = ready()))
        show({ state.value }, callbacks().copy(onRemoteControlSettingsOpened = { opens++ }, onRemoteControlEnable = { enables++ }))
        openGroup()
        compose.runOnIdle { assertEquals(1, opens); assertEquals(0, enables) }
        compose.onNodeWithTag("remote_control_enable").performScrollTo().performClick()
        compose.onNodeWithText(REMOTE_CONTROL_CONSENT).assertExists()
        compose.runOnIdle { assertEquals(0, enables) }
        compose.onNodeWithTag("remote_control_confirm_enable").performClick()
        compose.runOnIdle {
            assertEquals(1, enables)
            state.value = state.value.copy(remoteControl = ready().copy(
                localConsentGranted = true, pendingOperation = RemoteControlOperation.ENABLE))
        }
        compose.onNodeWithTag("remote_control_status").assertTextContains("Nicht verbunden", substring = true)
        compose.onNodeWithTag("remote_control_enable").assertIsNotEnabled()
        compose.onNodeWithTag("remote_control_disable").assertIsEnabled()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun disabledRuntimeDoesNotOfferConsentButCanWithdrawAmbiguousLocalAccess() {
        var disables = 0
        show({ SettingsUiState(remoteControl = ready().copy(runtimeReady = false,
            statusConfirmedForCurrentRuntime = false, localConsentGranted = true, issue = RemoteControlIssue.TIMED_OUT)) },
            callbacks().copy(onRemoteControlDisable = { disables++ }))
        openGroup()
        compose.onNodeWithTag("remote_control_enable").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("remote_control_disable").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, disables) }
    }

    @Test fun pairingCodeDisappearsWhenCoordinatorClearsItsTransientSnapshot() {
        val state = mutableStateOf(SettingsUiState(remoteControl = ready(RemoteControlStatus.CONNECTED).copy(
            localConsentGranted = true,
            pairing = RemoteControlPairing("secret-not-persisted", "TEST-CODE", "environment-test", 4_000_000_000L))))
        show({ state.value })
        openGroup()
        compose.onNodeWithTag("remote_control_pairing_code").performScrollTo().assertTextContains("TEST-CODE")
        compose.runOnIdle { state.value = state.value.copy(remoteControl = state.value.remoteControl.copy(
            pairing = null, issue = RemoteControlIssue.PAIRING_EXPIRED)) }
        compose.onNodeWithTag("remote_control_pairing_code").assertDoesNotExist()
    }

    @Test fun revokeRequiresConfirmationAndDoesNotOptimisticallyRemoveTheClient() {
        var revoked: String? = null
        val state = SettingsUiState(remoteControl = ready(RemoteControlStatus.CONNECTED).copy(
            clients = listOf(RemoteControlClient("client-test", "Mein Mac", "private-type", "private-model",
                "private-platform", "private-os", "private-version", 42L)), clientsComplete = true))
        show({ state }, callbacks().copy(onRemoteControlRevoke = { revoked = it }))
        openGroup()
        compose.onNodeWithTag("remote_control_revoke_0").performScrollTo().performClick()
        compose.runOnIdle { assertNull(revoked) }
        compose.onNodeWithTag("remote_control_confirm_revoke").performClick()
        compose.runOnIdle { assertEquals("client-test", revoked) }
        compose.onNodeWithText("Mein Mac").assertExists()
        compose.onNodeWithText("private-model").assertDoesNotExist()
        compose.onNodeWithText("private-os").assertDoesNotExist()
    }

    @Test fun existingThreadIsIdentifiedWithoutOfferingNewPhoneToolTasks() {
        show({ SettingsUiState(remoteControl = ready(), remoteControlThreadId = "thread-test",
            remoteControlThreadName = "Mein Hans-Gespräch") })
        openGroup()
        compose.onNodeWithTag("remote_control_thread_id").performScrollTo().assertTextContains("thread-test")
        compose.onNodeWithTag("remote_control_thread_name").assertTextContains("Mein Hans-Gespräch")
        compose.onNodeWithText("Neue Aufgaben, die du am Desktop anlegst, erhalten in dieser Version noch keine Telefonwerkzeuge.")
            .assertExists()
    }

    private fun show(state: () -> SettingsUiState, actions: SettingsUiCallbacks = callbacks()) {
        compose.setContent { MaterialTheme { SettingsScreen(state(), actions) } }
    }
    private fun openGroup() = compose.onNodeWithTag("settings_group_remote_control").performScrollTo().performClick()
    private fun ready(status: RemoteControlStatus = RemoteControlStatus.DISABLED) = RemoteControlSnapshot(
        generation = 1, runtimeReady = true, capability = RemoteControlCapability.SUPPORTED,
        connection = RemoteControlConnection(status, "installation-test", "Hans", "environment-test"),
        statusConfirmedForCurrentRuntime = true,
    )
    private fun callbacks() = SettingsUiCallbacks(
        onBack = {}, onStartGettingToKnow = {}, onModelSelected = {}, onReasoningEffortSelected = {},
        onVoiceSelected = {}, onSpeechRateSelected = {}, onReadAloudModeSelected = {}, onPreviewVoice = {},
        onStartActionKeySetup = {}, onStartModelToggleKeySetup = {}, onCancelActionKeySetup = {},
        onClearActionKey = {}, onClearModelToggleKey = {}, onCapabilityAccessRequested = {},
    )
}
