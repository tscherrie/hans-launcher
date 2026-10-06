package ai.hans.standard.ui

import ai.hans.standard.remotecontrol.*
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.res.Configuration
import android.os.Build
import java.util.Locale
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import ai.hans.standard.localization.AndroidHansTextResolver
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
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
    private val germanContext get() = InstrumentationRegistry.getInstrumentation().targetContext.let { base ->
        base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.GERMAN) })
    }
    private val REMOTE_CONTROL_CONSENT get() = remoteControlConsent(AndroidHansTextResolver(germanContext))

    @get:Rule val compose = createComposeRule()

    @Test fun retainedComponentIsReadOnlyAndConsentRequiresItsExplicitDialog() {
        var opens = 0
        var enables = 0
        val state = mutableStateOf(SettingsUiState(remoteControl = ready()))
        show({ state.value }, callbacks().copy(onRemoteControlSettingsOpened = { opens++ }, onRemoteControlEnable = { enables++ }))
        compose.runOnIdle { assertEquals(0, opens); assertEquals(0, enables) }
        compose.onNodeWithTag("remote_control_enable").performScrollTo().performClick()
        compose.onNodeWithText(REMOTE_CONTROL_CONSENT).assertExists()
        compose.runOnIdle { assertEquals(0, enables) }
        compose.onNodeWithTag("remote_control_confirm_enable").performClick()
        compose.runOnIdle {
            assertEquals(1, enables)
            state.value = state.value.copy(remoteControl = ready().copy(
                localConsentGranted = true, pendingOperation = RemoteControlOperation.ENABLE))
        }
        compose.onNodeWithTag("remote_control_status").assertTextContains("Hintergrundzugriff: Aus")
        compose.onNodeWithTag("remote_control_enable").assertIsNotEnabled()
        compose.onNodeWithTag("remote_control_disable").assertIsEnabled()
        compose.runOnIdle { assertEquals(0, opens) }
    }

    @Test fun disabledRuntimeDoesNotOfferConsentButCanWithdrawAmbiguousLocalAccess() {
        var disables = 0
        show({ SettingsUiState(remoteControl = ready().copy(runtimeReady = false,
            statusConfirmedForCurrentRuntime = false, localConsentGranted = true, issue = RemoteControlIssue.TIMED_OUT)) },
            callbacks().copy(onRemoteControlDisable = { disables++ }))
        compose.onNodeWithTag("remote_control_enable").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("remote_control_disable").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, disables) }
    }

    @Test fun pairingCodeDisappearsWhenCoordinatorClearsItsTransientSnapshot() {
        val state = mutableStateOf(SettingsUiState(remoteControl = ready(RemoteControlStatus.CONNECTED).copy(
            localConsentGranted = true,
            pairing = RemoteControlPairing("secret-not-persisted", "TEST-CODE", "environment-test", 4_000_000_000L))))
        show({ state.value })
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
        compose.onNodeWithTag("remote_control_revoke_0").performScrollTo().performClick()
        compose.runOnIdle { assertNull(revoked) }
        compose.onNodeWithTag("remote_control_confirm_revoke").performClick()
        compose.runOnIdle { assertEquals("client-test", revoked) }
        compose.onNodeWithText("Mein Mac").assertExists()
        compose.onNodeWithText("private-model").assertDoesNotExist()
        compose.onNodeWithText("private-os").assertDoesNotExist()
    }

    @Test fun existingThreadAndNewDesktopToolsAreIdentifiedWithoutStartingATask() {
        show({ SettingsUiState(remoteControl = ready(), remoteControlThreadId = "thread-test",
            remoteControlThreadName = "Mein Hans-Gespräch", remotePhoneToolsAvailable = true) })
        compose.onNodeWithTag("remote_control_thread_id").performScrollTo().assertTextContains("thread-test")
        compose.onNodeWithTag("remote_control_thread_name").assertTextContains("Mein Hans-Gespräch")
        compose.onNodeWithTag("remote_control_new_task_tools").performScrollTo()
            .assertTextContains("Neue Desktop-Aufgaben können dieselben Telefonwerkzeuge wie Hans nutzen – mit deinen Freigaben.")
    }

    @Test fun unavailableRemoteToolBridgeDoesNotClaimNewTaskAccess() {
        show({ SettingsUiState(remoteControl = ready(), remotePhoneToolsAvailable = false) })
        compose.onNodeWithTag("remote_control_new_task_tools").performScrollTo()
            .assertTextContains("Telefonwerkzeuge für neue Desktop-Aufgaben sind noch nicht bereit. Hans-Laufzeit neu starten und erneut prüfen.")
    }

    @Test fun pairingCopiesOnlyOnTapAndUsesSensitiveClipboardOnAndroid13AndLater() {
        show({ SettingsUiState(remoteControl = ready(RemoteControlStatus.CONNECTED).copy(
            localConsentGranted = true,
            pairing = RemoteControlPairing("token-not-for-copy", "TEST-CODE", "environment-test", 4_000_000_000L))) })
        val clipboard = germanContext.getSystemService(ClipboardManager::class.java)
        compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("test", "unchanged")) }
        compose.onNodeWithTag("remote_control_pairing_code").performScrollTo()
        compose.runOnIdle { assertEquals("unchanged", clipboard.primaryClip?.getItemAt(0)?.text?.toString()) }
        compose.onNodeWithTag("remote_control_pairing_code").performClick()
        compose.runOnIdle {
            val clip = checkNotNull(clipboard.primaryClip)
            assertEquals("TEST-CODE", clip.getItemAt(0).text.toString())
            assertEquals("Kopplungscode kopieren", clip.description.label.toString())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                assertTrue(clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true)
            }
        }
    }

    @Test fun conversationIdCopiesOnTapWithoutStartingOrChangingAConversation() {
        var remoteOpens = 0
        show({ SettingsUiState(remoteControl = ready(), remoteControlThreadId = "thread-test") },
            callbacks().copy(onRemoteControlSettingsOpened = { remoteOpens++ }))
        compose.onNodeWithTag("remote_control_thread_id").performScrollTo().performClick()
        compose.runOnIdle {
            val clipboard = germanContext.getSystemService(ClipboardManager::class.java)
            assertEquals("thread-test", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            assertEquals(0, remoteOpens)
        }
    }

    @Test fun productSettingsHideDesktopAccessInNavigationAndPermissionsEvenWithStaleConnectedState() {
        var remoteCallbacks = 0
        var outgoingWorkerOpens = 0
        val state = SettingsUiState(remoteControl = ready(RemoteControlStatus.CONNECTED).copy(
            localConsentGranted = true,
            pairing = RemoteControlPairing("never-rendered", "HIDDEN-CODE", "environment-test", 4_000_000_000L)))
        compose.setGermanContent {
            MaterialTheme {
                SettingsScreen(state, countedRemoteCallbacks { remoteCallbacks++ }.copy(
                    onRemoteWorkerSettingsOpened = { outgoingWorkerOpens++ }))
            }
        }
        compose.onNodeWithTag("settings_group_remote_control").assertDoesNotExist()
        compose.onNodeWithTag("settings_group_maintenance").assertExists()
        compose.onNodeWithTag("settings_group_permissions").performScrollTo().performClick()
        listOf("kind", "consent", "status", "enable", "disable", "confirm_enable").forEach { suffix ->
            compose.onNodeWithTag("permissions_remote_control_$suffix").assertDoesNotExist()
        }
        compose.onNodeWithTag("persistent_android_consent_notice").assertExists()
        compose.onNodeWithTag("remote_control_settings").assertDoesNotExist()
        compose.onNodeWithText("HIDDEN-CODE").assertDoesNotExist()
        compose.onNodeWithTag("navigate_back").performClick()
        compose.onNodeWithTag("settings_group_maintenance").performScrollTo().performClick()
        compose.onNodeWithTag("remote_worker_state").assertExists()
        compose.runOnIdle {
            assertEquals(0, remoteCallbacks)
            assertEquals(1, outgoingWorkerOpens)
        }
    }

    @Test fun staleDesktopDestinationCannotComposeControlsOrTriggerSettingsOpen() {
        var remoteCallbacks = 0
        val actions = countedRemoteCallbacks { remoteCallbacks++ }
        compose.setGermanContent {
            MaterialTheme {
                // Direct composition covers a stale destination even before navigation normalization.
                SettingsContent(SettingsUiState(remoteControl = ready()), actions, SettingsGroup.REMOTE_CONTROL)
            }
        }
        compose.onNodeWithTag("settings_content").assertDoesNotExist()
        compose.onNodeWithTag("remote_control_settings").assertDoesNotExist()
        compose.onNodeWithTag("remote_control_enable").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, remoteCallbacks) }
    }

    @Test fun chatSidebarDoesNotExposeDesktopAccessOrInvokeItsCallbacks() {
        var remoteCallbacks = 0
        compose.setGermanContent {
            MaterialTheme {
                ChatScreen(ChatUiState(), ChatUiCallbacks(
                    onComposerChanged = {}, onSend = {}, onChooseMedia = {}, onRemoveAttachment = {},
                    onOpenApps = {}, onOpenPlugins = {}, onOpenSettings = {}, onToggleLiveVoice = {},
                ), sidebarSettings = SettingsUiState(remoteControl = ready()),
                    sidebarCallbacks = countedRemoteCallbacks { remoteCallbacks++ })
            }
        }
        compose.onNodeWithTag("open_chat_navigation").performClick()
        compose.onNodeWithTag("settings_group_remote_control").assertDoesNotExist()
        compose.onNodeWithTag("settings_group_maintenance").assertExists()
        compose.onNodeWithTag("settings_group_permissions").performScrollTo().performClick()
        compose.onNodeWithTag("permissions_remote_control_enable").assertDoesNotExist()
        compose.onNodeWithTag("permissions_remote_control_kind").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, remoteCallbacks) }
    }

    @Test fun preparedProjectPathIsCopyableAndAbsentWhenNotPrepared() {
        val state = mutableStateOf(SettingsUiState(remoteControl = ready()))
        show({ state.value })
        compose.onNodeWithTag("remote_control_project_path").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(remoteControlProjectPath = "/test/codex-workspace") }
        compose.onNodeWithTag("remote_control_project_path").performScrollTo().performClick()
        compose.runOnIdle {
            val clipboard = germanContext.getSystemService(ClipboardManager::class.java)
            assertEquals("/test/codex-workspace", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
    }

    /** Retained component contracts are tested directly; this is not a reachable product destination. */
    private fun show(state: () -> SettingsUiState, actions: SettingsUiCallbacks = callbacks()) {
        val context = germanContext
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration) {
                MaterialTheme {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        RemoteControlSettings(state(), actions)
                    }
                }
            }
        }
    }
    private fun countedRemoteCallbacks(onCall: () -> Unit) = callbacks().copy(
        onRemoteControlSettingsOpened = onCall, onRemoteControlEnable = onCall,
        onRemoteControlDisable = onCall, onRemoteControlPair = onCall,
        onRemoteControlRefresh = onCall, onRemoteControlRefreshClients = onCall,
        onRemoteControlLoadMoreClients = onCall, onRemoteControlRevoke = { onCall() },
        onRemoteControlCheckPairing = onCall,
    )
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
