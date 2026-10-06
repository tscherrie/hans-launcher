package ai.hans.standard

import ai.hans.standard.localization.AndroidHansTextResolver
import ai.hans.standard.localization.HansTextResolver
import ai.hans.standard.localization.rememberHansTextResolver
import ai.hans.standard.localization.HansLocaleAwareActivity

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.app.AlarmManager
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import ai.hans.standard.codex.CodexInput
import ai.hans.standard.codex.ReasoningEffort
import ai.hans.standard.automations.androidui.AndroidAutomationApprovalDialog
import ai.hans.standard.automations.AutomationCancellationResult
import ai.hans.standard.automations.AutomationConfirmationPolicy
import ai.hans.standard.automations.AutomationDefinition
import ai.hans.standard.automations.AutomationDefinitionDraft
import ai.hans.standard.automations.AutomationDefinitionMutationResult
import ai.hans.standard.automations.AutomationId
import ai.hans.standard.automations.AutomationManualEnqueueResult
import ai.hans.standard.automations.AutomationManualRequestId
import ai.hans.standard.automations.AutomationRuntimeOwner
import ai.hans.standard.automations.AndroidAutomationUnlockRecovery
import ai.hans.standard.backup.AndroidHansBackupDocumentIo
import ai.hans.standard.backup.HansBackupProcessState
import ai.hans.standard.backup.backupImportResultMessage
import ai.hans.standard.backup.backupRecoveryScreen
import ai.hans.standard.backup.HansBackupCoordinator
import ai.hans.standard.backup.HansBackupDocumentCodec
import ai.hans.standard.backup.HansBackupException
import ai.hans.standard.backup.HansBackupImportPreview
import ai.hans.standard.integration.AndroidCodexSessionHost
import ai.hans.standard.integration.AccountRefreshAction
import ai.hans.standard.integration.BundledSetupBootstrapStatus
import ai.hans.standard.integration.HandlerAccountRefreshScheduler
import ai.hans.standard.integration.PeriodicAccountRefreshController
import ai.hans.standard.integration.AppPrivateCodexSessionStore
import ai.hans.standard.integration.CodexClientObserver
import ai.hans.standard.integration.CodexClientSnapshot
import ai.hans.standard.integration.ClientSessionPhase
import ai.hans.standard.integration.CodexDispatchAttemptResult
import ai.hans.standard.integration.DispatchSelection
import ai.hans.standard.integration.OutboundMessageStatus
import ai.hans.standard.integration.OutboundUserMessageUi
import ai.hans.standard.integration.latestVisibleChatTimelineId
import ai.hans.standard.integration.resolveDispatchSelection
import ai.hans.standard.integration.ValidatedNotificationAnnouncement
import ai.hans.standard.integration.ValidatedNotificationAnnouncementObserver
import ai.hans.standard.media.AndroidMediaImportPipeline
import ai.hans.standard.media.AndroidCameraCaptureCoordinator
import ai.hans.standard.media.AtomicFileMediaTurnLeaseStorage
import ai.hans.standard.media.CameraCapturePreparation
import ai.hans.standard.media.CameraCaptureKind
import ai.hans.standard.media.CameraCaptureSelectionPolicy
import ai.hans.standard.media.CapturedCameraPhoto
import ai.hans.standard.media.CapturedCameraVideo
import ai.hans.standard.media.FullResolutionCameraContract
import ai.hans.standard.media.FullResolutionVideoContract
import ai.hans.standard.media.MediaImportException
import ai.hans.standard.media.MediaImportFailureCode
import ai.hans.standard.media.MediaImportResult
import ai.hans.standard.media.MediaTurnLeaseKey
import ai.hans.standard.media.MediaTurnLeaseTracker
import ai.hans.standard.media.OpenAiVideoAudioTranscriptionGateway
import ai.hans.standard.media.VideoAudioTranscriptionCallback
import ai.hans.standard.media.VideoAudioTranscriptionCancellation
import ai.hans.standard.media.toTranscriptionRequest
import ai.hans.standard.mcp.oauth.AndroidRemoteMcpOAuthBrowser
import ai.hans.standard.mcp.oauth.AndroidRemoteMcpOAuthFlowFactory
import ai.hans.standard.mcp.oauth.RemoteMcpOAuthConnectionFailure
import ai.hans.standard.mcp.oauth.RemoteMcpOAuthResultConsumer
import ai.hans.standard.mcp.oauth.RemoteMcpOAuthResultExtraSource
import ai.hans.standard.mcp.oauth.RemoteMcpOAuthSafeResult
import ai.hans.standard.mcp.oauth.RemoteMcpOAuthStartResult
import ai.hans.standard.plugins.PluginHandle
import ai.hans.standard.plugins.PluginRemoteMcpPolicyReviewResult
import ai.hans.standard.plugins.PluginRemoteMcpPolicyReviewSubmission
import ai.hans.standard.phone.capabilities.AndroidCapabilityEnvironment
import ai.hans.standard.phone.capabilities.AndroidPublicCapabilityAdapter
import ai.hans.standard.phone.capabilities.AndroidAdapterResult
import ai.hans.standard.phone.capabilities.AndroidSpecialAccess
import ai.hans.standard.phone.capabilities.LaunchProfileType
import ai.hans.standard.phone.capabilities.LauncherProfileEvent
import ai.hans.standard.phone.capabilities.LauncherProfileEventReceiver
import ai.hans.standard.phone.capabilities.LauncherUserConfigCallbackFactory
import ai.hans.standard.phone.capabilities.PrivateSpaceAvailability
import ai.hans.standard.phone.capabilities.PrivateSpaceQuietModeOutcome
import ai.hans.standard.phone.capabilities.PrivateSpaceSnapshot
import ai.hans.standard.phone.capabilities.SharedPreferencesPrivateSpaceContainerVisibilityStore
import ai.hans.standard.phone.capabilities.SettingsDestination
import ai.hans.standard.phone.consent.PersistentAndroidConsentCatalog
import ai.hans.standard.phone.consent.PersistentAndroidConsentDescriptor
import ai.hans.standard.phone.consent.PersistentAndroidConsentScope
import ai.hans.standard.phone.keys.ActionKeyCaptureRequest
import ai.hans.standard.phone.keys.ActionKeyCaptureState
import ai.hans.standard.phone.keys.ActionKeyCaptureStateMachine
import ai.hans.standard.phone.keys.ActionKeyCommand
import ai.hans.standard.phone.keys.ActionKeyDispatchResult
import ai.hans.standard.phone.keys.ActionKeyDispatcher
import ai.hans.standard.phone.keys.ActionKeyIgnoreReason
import ai.hans.standard.phone.keys.ActionKeyMapping
import ai.hans.standard.phone.keys.ActionKeyMappingPreferencesStore
import ai.hans.standard.phone.keys.forTaskVoiceControls
import ai.hans.standard.phone.keys.ActionKeyMappingSet
import ai.hans.standard.phone.keys.ActionKeyTrigger
import ai.hans.standard.phone.keys.AndroidMp01VendorActionRemediation
import ai.hans.standard.phone.keys.AndroidKeyEventObserver
import ai.hans.standard.phone.keys.CaptureEventResult
import ai.hans.standard.phone.keys.CaptureSessionId
import ai.hans.standard.phone.keys.HansModelPreset
import ai.hans.standard.phone.keys.HansModelPresetToggle
import ai.hans.standard.phone.keys.ForegroundModelKeyRouter
import ai.hans.standard.ui.hansModelShortcutKeys
import ai.hans.standard.phone.keys.GlobalActionKeyAccessibilityReceipt
import ai.hans.standard.phone.keys.GlobalActionKeyAccessibilityReceiptCenter
import ai.hans.standard.phone.keys.GlobalActionKeyCaptureGate
import ai.hans.standard.phone.keys.GlobalActionKeyCaptureLease
import ai.hans.standard.phone.keys.KeySemanticAction
import ai.hans.standard.phone.keys.HansDictationTileService
import ai.hans.standard.phone.keys.Mp01VendorActionConflictResolver
import ai.hans.standard.phone.keys.Mp01VendorActionConflictKind
import ai.hans.standard.phone.keys.Mp01VendorActionGateResult
import ai.hans.standard.phone.keys.Mp01VendorActionOverrideConfirmationStore
import ai.hans.standard.phone.keys.Mp01ActionMappingSetFingerprint
import ai.hans.standard.phone.keys.SoftwareHoldGestureEffect
import ai.hans.standard.phone.keys.SoftwareHoldGestureId
import ai.hans.standard.phone.keys.SoftwareHoldToTalkGestureController
import ai.hans.standard.phone.publicapi.AndroidPublicPhoneConfirmationDialog
import ai.hans.standard.phone.tools.AndroidDynamicToolConfirmationDialog
import ai.hans.standard.settings.HansSettings
import ai.hans.standard.settings.ReadAloudMode
import ai.hans.standard.settings.SharedPreferencesHansSettingsStore
import ai.hans.standard.phone.display.DisplayMotionMode
import ai.hans.standard.phone.display.DisplayMotionPreferencesStore
import ai.hans.standard.setup.HansSetupInputChoice
import ai.hans.standard.setup.HansSetupOptionalCapability
import ai.hans.standard.setup.HansSetupOperationToken
import ai.hans.standard.setup.HansSetupRuntime
import ai.hans.standard.setup.setupAccessibilityDescription
import ai.hans.standard.setup.HansSetupStep
import ai.hans.standard.setup.HansSetupStepRecord
import ai.hans.standard.setup.HansSetupDictationEvidence
import ai.hans.standard.setup.AtomicFileHansSetupHandoffStorage
import ai.hans.standard.setup.HansSetupHandoffAction
import ai.hans.standard.setup.HansSetupHandoffCoordinator
import ai.hans.standard.setup.HansSetupHandoffDispatchGate
import ai.hans.standard.setup.HansSetupHandoffEnvironment
import ai.hans.standard.setup.HansSetupHandoffOutboundStatus
import ai.hans.standard.setup.HansSetupHandoffRecord
import ai.hans.standard.setup.HansSetupHandoffSignalCenter
import ai.hans.standard.setup.SetupUiCommand
import ai.hans.standard.setup.SetupUiCommandHandler
import ai.hans.standard.setup.SetupUiCommandLease
import ai.hans.standard.setup.SetupUiCommandResult
import ai.hans.standard.setup.RestrictedSettingsCapability
import ai.hans.standard.setup.RestrictedSettingsRecoveryCopy
import ai.hans.standard.setup.RestrictedSettingsRecoveryEffect
import ai.hans.standard.setup.RestrictedSettingsRecoveryPolicy
import ai.hans.standard.setup.RestrictedSettingsRecoveryState
import ai.hans.standard.setup.RestrictedSettingsRecoveryStage
import ai.hans.standard.setup.RestrictedSettingsRecoveryTransition
import ai.hans.standard.ui.AuthGateUiCallbacks
import ai.hans.standard.ui.AuthGateRecoveryPolicy
import ai.hans.standard.ui.AutomationsUiCallbacks
import ai.hans.standard.ui.AppUiModel
import ai.hans.standard.ui.AppProfileUiModel
import ai.hans.standard.ui.AppsUiCallbacks
import ai.hans.standard.ui.ChatUiCallbacks
import ai.hans.standard.ui.ChatMessageAuthor
import ai.hans.standard.ui.ChatMessageUiModel
import ai.hans.standard.ui.CapabilityAccessUiId
import ai.hans.standard.ui.CapabilityAccessUiModel
import ai.hans.standard.ui.ConfirmedSttGlossaryUiState
import ai.hans.standard.ui.HansApp
import ai.hans.standard.ui.HansClientUiProjector
import ai.hans.standard.ui.HansDestination
import ai.hans.standard.ui.HansLocalUiState
import ai.hans.standard.ui.HansPendingAttachment
import ai.hans.standard.ui.HansTheme
import ai.hans.standard.ui.HansUiCallbacks
import ai.hans.standard.ui.LiveVoiceUiStatus
import ai.hans.standard.ui.afterDictationPublication
import ai.hans.standard.ui.Mp01VendorActionConflictUiState
import ai.hans.standard.ui.validatedHttpsPluginLinkOrNull
import ai.hans.standard.ui.Mp01VendorActionConflictUiKind
import ai.hans.standard.ui.Mp01VendorShortcutUiKind
import ai.hans.standard.ui.PendingComposerDispatch
import ai.hans.standard.ui.PersistentAndroidConsentUiModel
import ai.hans.standard.ui.PluginsUiCallbacks
import ai.hans.standard.ui.ReadAloudUiMode
import ai.hans.standard.ui.RemoteWorkerAdapterUiDraft
import ai.hans.standard.ui.RemoteWorkerConfigurationUiDraft
import ai.hans.standard.ui.RemoteWorkerSettingsUiState
import ai.hans.standard.ui.RemoteWorkerSettingsUiStatus
import ai.hans.standard.ui.SettingsUiCallbacks
import ai.hans.standard.ui.WorkbenchUiCallbacks
import ai.hans.standard.ui.WORKBENCH_INVENTORY_LIMIT
import ai.hans.standard.ui.projectWorkbenchInventory
import ai.hans.standard.ui.privateSpaceUiState
import ai.hans.standard.ui.personalAppInventoryOnly
import ai.hans.standard.ui.projectAutomations
import ai.hans.standard.ui.SpeechCredentialUiStatus
import ai.hans.standard.ui.AndroidSpeechCredentialDialog
import ai.hans.standard.ui.authoritativeIdleMediaThreadIds
import ai.hans.standard.ui.SpeechCredentialDialogOutcome
import ai.hans.standard.ui.DictationUiStatus
import ai.hans.standard.ui.VoiceInputTransitionPolicy
import ai.hans.standard.ui.VoiceInputCommandAction
import ai.hans.standard.ui.ComposerDispatchReconciliation
import ai.hans.standard.ui.ComposerDraftSnapshot
import ai.hans.standard.ui.reconcileComposerDispatch
import ai.hans.standard.ui.blockOfflineComposerSubmission
import ai.hans.standard.update.CodexUpdateLaunchResult
import ai.hans.standard.update.CodexUpdateLauncher
import ai.hans.standard.voice.android.DictationRuntimeObserver
import ai.hans.standard.voice.android.DictationRuntimeSnapshot
import ai.hans.standard.voice.android.DictationUiPhase
import ai.hans.standard.network.InternetSnapshot
import ai.hans.standard.voice.RecordingFailure
import ai.hans.standard.voice.android.HansDictationRuntime
import ai.hans.standard.voice.android.HansDictationService
import ai.hans.standard.voice.stt.android.AtomicFileConfirmedSttGlossary
import ai.hans.standard.voice.stt.android.SttLatencyPreferenceStore
import ai.hans.standard.voice.stt.SttTranscriptionDelay
import ai.hans.standard.voice.stt.android.ConfirmedSttGlossaryPolicy
import ai.hans.standard.voice.tts.android.SpeechCredentialStatus
import ai.hans.standard.voice.realtime.AndroidLiveVoiceRuntime
import ai.hans.standard.voice.realtime.LiveVoiceCancellation
import ai.hans.standard.voice.realtime.LiveVoiceFailure
import ai.hans.standard.voice.realtime.LiveVoiceObserver
import ai.hans.standard.voice.realtime.LiveVoicePhase
import ai.hans.standard.voice.realtime.LiveVoiceServiceCommandResult
import ai.hans.standard.voice.realtime.LiveVoiceSnapshot
import ai.hans.standard.voice.feedback.HansSpeechFailureRuntime
import ai.hans.standard.voice.feedback.SpeechServiceFailureObserver
import ai.hans.standard.voice.feedback.openAiSpeechFailureMessage
import ai.hans.standard.work.remote.RemoteWorkAdapterApproval
import ai.hans.standard.work.remote.RemoteWorkAdapterId
import ai.hans.standard.work.remote.RemoteWorkerConfiguration
import ai.hans.standard.work.remote.RemoteWorkerRuntimeStatus
import ai.hans.standard.work.remote.RemoteWorkerRuntimeUpdateResult
import ai.hans.standard.work.remote.remoteWorkerConnectionIdentity
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

/**
 * Hans screen. The Activity owns only UI drafts and visible
 * confirmations; the process-wide [AndroidCodexSessionHost] owns Codex.
 */
class LauncherActivity : HansLocaleAwareActivity() {
    private fun tr(resourceId: Int, vararg args: Any): String =
        AndroidHansTextResolver(this).text(resourceId, *args)

    override fun dump(
        prefix: String,
        fd: java.io.FileDescriptor?,
        writer: java.io.PrintWriter,
        args: Array<out String>?,
    ) {
        val command = args?.singleOrNull()
        if (command == "--hans-notification-hooks") {
            // Content-free, passive DUMP probe: no host bootstrap, history request or retry.
            val status = (application as? HansApplication)?.passiveNotificationEventStatus()
            val payload = org.json.JSONObject()
                .put("initialized", status != null)
                .put("available", status?.available ?: false)
                .put("activated", status?.activated ?: false)
                .put("readyCount", status?.readyCount ?: 0)
                .put("unsettledCount", status?.unsettledCount ?: 0)
                .put("uncertainCount", status?.uncertainCount ?: 0)
                .put("technicalUpdateCount", status?.technicalUpdateCount ?: 0)
                .put("waitReason", status?.waitReason ?: org.json.JSONObject.NULL)
                .put("failureCode", status?.failureCode ?: org.json.JSONObject.NULL)
                .put("outcomes", status?.outcomes?.let {
                    ai.hans.standard.notifications.hooks.notificationHookOutcomeDiagnosticJson(it)
                } ?: org.json.JSONObject.NULL)
            writer.println(prefix + "HANS_NOTIFICATION_HOOKS " + payload)
            return
        }
        if (command == "--hans-voice") {
            // Existing DUMP-authorized, on-demand read only. No transcript, thread identity,
            // account data, SDP, audio, polling, store initialization or runtime action.
            val realtime = if (::sessionHost.isInitialized) sessionHost.realtimeDiagnostics() else null
            writer.println(prefix + "HANS_VOICE " + encodeHansVoiceDiagnostics(
                realtime = realtime,
                dictationPhase = HansDictationRuntime.snapshotUi().phase,
                livePhase = AndroidLiveVoiceRuntime.snapshot().phase,
                captureRequestedOrActive = AndroidLiveVoiceRuntime.isCaptureRequestedOrActive(),
            ))
            return
        }
        if (command == "--hans-remote-control") {
            val snapshot = if (::sessionHost.isInitialized) sessionHost.snapshot() else null
            writer.println(prefix + "HANS_REMOTE_CONTROL " +
                ai.hans.standard.diagnostics.RemoteControlDiagnostics.encode(snapshot?.remoteControl,
                    ai.hans.standard.phone.lifecycle.android.HansActiveWorkOwner.foregroundSnapshot()))
            return
        }
        if (command == "--hans-models") {
            // Passive snapshot only, through Android's existing DUMP-authorized path.
            val snapshot = if (::sessionHost.isInitialized) sessionHost.snapshot() else null
            writer.println(prefix + "HANS_MODELS " +
                ai.hans.standard.diagnostics.ModelSelectionDiagnostics.encode(snapshot))
            return
        }
        if (command == "--hans-tool-failures") {
            // Passive loaded event metadata only: no history fetch, model turn, or tool replay.
            val snapshot = if (::sessionHost.isInitialized) sessionHost.snapshot() else null
            writer.println(prefix + "HANS_TOOL_FAILURES " +
                ai.hans.standard.diagnostics.ToolHistoryDiagnostics.encode(snapshot?.session))
            return
        }
        if (command == "--hans-model-key") {
            val mapping = if (::actionKeyStore.isInitialized) {
                runCatching { actionKeyStore.read().mappings.singleOrNull { it.mappingId == MODEL_TOGGLE_MAPPING_ID } }
                    .getOrNull()
            } else null
            writer.println(prefix + "HANS_MODEL_KEY " +
                ai.hans.standard.diagnostics.ModelShortcutDiagnostics.encode(mapping))
            return
        }
        if (command in setOf("--hans-performance", "--hans-performance-start", "--hans-performance-stop")) {
            // ActivityManager's existing DUMP-authorized path; no new exported endpoint,
            // store initialization, network request, microphone, or UI action.
            val payload = if (::sessionHost.isInitialized) {
                sessionHost.performanceDiagnostics(checkNotNull(command))
            } else ai.hans.standard.diagnostics.PerformanceDiagnostics.encode(null, null)
            writer.println(prefix + "HANS_PERFORMANCE " + payload)
            return
        }
        super.dump(prefix, fd, writer, args)
    }

    private lateinit var settingsStore: SharedPreferencesHansSettingsStore
    private lateinit var displayMotionStore: DisplayMotionPreferencesStore
    private lateinit var confirmedSttGlossaryStore: AtomicFileConfirmedSttGlossary
    private lateinit var sttLatencyPreferenceStore: SttLatencyPreferenceStore
    private lateinit var sessionStore: AppPrivateCodexSessionStore
    private val mediaExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hans-private-media").apply { isDaemon = true }
    }
    private val appCatalogExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hans-app-catalog").apply { isDaemon = true }
    }
    private val backupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hans-backup").apply { isDaemon = true }
    }
    private val remoteMcpOAuthExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hans-remote-mcp-oauth-start").apply { isDaemon = true }
    }
    private val remoteMcpPolicyExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hans-remote-mcp-policy-review").apply { isDaemon = true }
    }
    private val remoteWorkerPersistenceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hans-remote-worker-persistence").apply { isDaemon = true }
    }
    private val automationUiExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hans-automation-ui").apply { isDaemon = true }
    }

    private lateinit var sessionHost: AndroidCodexSessionHost
    private lateinit var periodicAccountRefresh: PeriodicAccountRefreshController
    private lateinit var setupRuntime: HansSetupRuntime
    private lateinit var setupHandoffCoordinator: HansSetupHandoffCoordinator
    private var setupHandoffSignalRegistration: Closeable? = null
    private var setupHandoffDriveInProgress = false
    private var setupHandoffDriveRequested = false
    private val surfacedSetupHandoffRecoveries = mutableSetOf<String>()
    private lateinit var automationRuntime: AutomationRuntimeOwner
    private var automationActivityRegistration: AutoCloseable? = null
    private var automationRefreshGeneration = 0L
    private lateinit var backupCoordinator: HansBackupCoordinator
    private lateinit var backupDocumentIo: AndroidHansBackupDocumentIo
    private var backupRecoveryOnlyUi = false
    private var setupUiRegistration: Closeable? = null
    private var pendingSetupConversationMessageId: String? = null
    private var pendingRemoteMcpOAuthResult: RemoteMcpOAuthSafeResult? = null
    private var activeSetupKeyCaptureToken: HansSetupOperationToken? = null
    private var activeSetupDictationTestToken: HansSetupOperationToken? = null
    private var setupVoiceTestDialog: AlertDialog? = null
    private var setupVoiceTestDialogToken: HansSetupOperationToken? = null
    private var setupVoiceStopDialog: AlertDialog? = null
    private var setupAccessibilityTestDialog: AlertDialog? = null
    private var setupEverydayAccessDialog: AlertDialog? = null
    private var setupEverydayAccessDialogToken: HansSetupOperationToken? = null
    private var notificationLinkMetadataDialog: AlertDialog? = null
    private var notificationLinkMetadataDialogToken: HansSetupOperationToken? = null
    private var cameraCaptureChoiceDialog: AlertDialog? = null
    private var setupAccessibilityPostconditionCheck: Runnable? = null
    private var speechCredentialDialog: AndroidSpeechCredentialDialog? = null
    private var pendingSetupPermissionInteraction: PendingSetupPermissionInteraction? = null
    private var pendingSetupCameraCapture: PendingSetupCameraCapture? = null
    private var pendingSetupQuickSettingsTile: PendingSetupQuickSettingsTile? = null
    private var activeSetupSpeechCredentialToken: HansSetupOperationToken? = null
    private var restrictedSettingsRecovery: RestrictedSettingsRecoveryState? = null
    private var restrictedSettingsRecoveryDialog: AlertDialog? = null
    private lateinit var mediaPipeline: AndroidMediaImportPipeline
    private lateinit var mediaTurnLeaseTracker: MediaTurnLeaseTracker
    private lateinit var cameraCaptureCoordinator: AndroidCameraCaptureCoordinator
    private lateinit var videoTranscriptionGateway: OpenAiVideoAudioTranscriptionGateway
    private val videoTranscriptions = ConcurrentHashMap<String, VideoAudioTranscriptionCancellation>()
    private var pendingComposerDispatch: PendingComposerDispatch? = null
    private var clientObserver: CodexClientObserver? = null
    private var clientSnapshot by androidx.compose.runtime.mutableStateOf<CodexClientSnapshot?>(null)
    private var localUi by androidx.compose.runtime.mutableStateOf(HansLocalUiState())
    private var workbenchRefreshGeneration = 0L
    private var notificationFactArchiveStatus by androidx.compose.runtime.mutableStateOf(
        ai.hans.standard.ui.NotificationFactArchiveStatus(),
    )
    private val notificationFactStatusGeneration = ai.hans.standard.ui.NotificationFactStatusGeneration()
    private var whatsAppAgentChannelState by androidx.compose.runtime.mutableStateOf(
        ai.hans.standard.ui.WhatsAppAgentChannelUiState(),
    )
    private var whatsAppAgentChannelSubscription: Closeable? = null
    private var whatsAppAgentChannelDialog: AlertDialog? = null
    private var whatsAppAgentChannelDialogGeneration = 0L

    private var settings by androidx.compose.runtime.mutableStateOf(HansSettings())
    private var displayMotionMode by androidx.compose.runtime.mutableStateOf(DisplayMotionMode.AUTOMATIC)
    private var lastDiagnosticState: String? = null
    private lateinit var dynamicToolConfirmation: AndroidDynamicToolConfirmationDialog
    private var dynamicToolConfirmationRegistration: Closeable? = null
    private var publicPhoneConfirmation: AndroidPublicPhoneConfirmationDialog? = null
    private var publicPhoneConfirmationRegistration: Closeable? = null
    private var automationApproval: AndroidAutomationApprovalDialog? = null
    private var automationApprovalRegistration: Closeable? = null
    private lateinit var publicCapabilityAdapter: AndroidPublicCapabilityAdapter
    private lateinit var privateSpaceVisibilityStore:
        SharedPreferencesPrivateSpaceContainerVisibilityStore
    private var appCatalogRefreshRequested = false
    private var privateSpaceRefreshInProgress = false
    private var privateSpaceRefreshRequested = false
    private var pendingPrivateSpaceLockedState: Boolean? = null
    private var launcherUiStarted = false
    private var launcherAppsCallbackRegistered = false
    private var launcherUserConfigCallback: LauncherApps.Callback? = null
    private var launcherProfileReceiverRegistered = false
    private val launcherProfileEventReceiver = LauncherProfileEventReceiver(::onLauncherProfileEvent)
    private val launcherAppsCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) =
            onLauncherCatalogChanged()

        override fun onPackageAdded(packageName: String, user: UserHandle) =
            onLauncherCatalogChanged()

        override fun onPackageChanged(packageName: String, user: UserHandle) =
            onLauncherCatalogChanged()

        override fun onPackagesAvailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) = onLauncherCatalogChanged()

        override fun onPackagesUnavailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) = onLauncherCatalogChanged(profileBecameUnavailable = true)
    }
    private lateinit var actionKeyStore: ActionKeyMappingPreferencesStore
    private lateinit var actionKeyDispatcher: ActionKeyDispatcher
    private val foregroundModelKeyRouter = ForegroundModelKeyRouter()
    private lateinit var mp01VendorActionRemediation: AndroidMp01VendorActionRemediation
    private lateinit var mp01VendorActionConfirmation:
        Mp01VendorActionOverrideConfirmationStore
    private var currentMp01VendorConflictMappingSetFingerprint: String? = null
    private val actionKeyCapture = ActionKeyCaptureStateMachine()
    private val actionKeyCaptureHandler = Handler(Looper.getMainLooper())
    private var activeActionKeyCapture: CaptureSessionId? = null
    private var actionKeyCaptureLease: GlobalActionKeyCaptureLease? = null
    private var actionKeyCaptureTimeout: Runnable? = null
    private var actionKeyAccessibilityReceiptRegistration: Closeable? = null
    private var nextActionKeyCaptureId = 0L
    private var pendingMicrophoneCommand: ActionKeyCommand? = null
    private val softwareHoldToTalk = SoftwareHoldToTalkGestureController()
    private var pendingSoftwareHoldPermission: SoftwareHoldGestureId? = null
    private var pendingLiveVoiceStart = false
    private var pendingDictationAfterLiveVoice: ActionKeyCommand? = null
    private var dictationObserver: DictationRuntimeObserver? = null
    private var speechServiceFailureObserver: SpeechServiceFailureObserver? = null
    private var liveVoiceObserver: LiveVoiceCancellation? = null
    private var liveVoiceCaptureObserver: LiveVoiceCancellation? = null
    private var speechAudioRouteSubscription: AutoCloseable? = null
    private var internetSubscription: Closeable? = null
    private var pendingDictationSubscription: Closeable? = null
    private var notificationAnnouncementObserver: ValidatedNotificationAnnouncementObserver? = null
    private var notificationReportSubscription: java.io.Closeable? = null
    private var activeLiveUserMessageId: String? = null
    private var activeLiveHansMessageId: String? = null
    private var nextLiveVoiceMessageId = 0L
    private var nextLocalTimelineArrivalOrder = 0L

    private val cameraCaptureLauncher = registerForActivityResult(
        FullResolutionCameraContract(),
    ) { captured ->
        if (backupRecoveryOnlyUi || !HansBackupProcessState.maintenance.isRecoveryReady) {
            return@registerForActivityResult
        }
        val photo = cameraCaptureCoordinator.acceptResult(captured)
        val directSetupCapture = pendingSetupCameraCapture
        if (directSetupCapture != null) {
            pendingSetupCameraCapture = null
            val completion = directSetupCapture.completion
            if (completion == null || !directSetupCapture.lease.isActive()) {
                photo?.let(cameraCaptureCoordinator::completeImport)
            } else if (photo == null) {
                completion(
                    SetupUiCommandResult.Rejected(
                        if (captured) {
                            "camera_capture_fresh_result_missing"
                        } else {
                            "camera_capture_cancelled"
                        },
                    ),
                )
                if (captured) {
                    showShortMessage(tr(R.string.integration_the_test_photo_did_not_contain_a_new_capture_23580a3))
                }
            } else {
                cameraCaptureCoordinator.completeImport(photo)
                directSetupCapture.lease.tryComplete(
                    SetupUiCommandResult.Accepted(liveVerificationAccepted = true),
                )
            }
        } else when {
            photo != null -> {
                activeSetupDictationTestToken
                    ?.takeIf { it.step == HansSetupStep.CAMERA_HOLD_LIVE_TEST }
                    ?.let { token ->
                        val state = setupRuntime.acceptCameraCapture(token)
                        if (state.record(token.step).terminal) {
                            activeSetupDictationTestToken = null
                        } else {
                            showNextCameraSetupAction(token)
                        }
                    }
                importCapturedPhoto(photo)
            }
            captured -> showShortMessage(tr(R.string.integration_the_photo_could_not_be_imported_safely_fab9423))
        }
    }

    private val videoCaptureLauncher = registerForActivityResult(
        FullResolutionVideoContract(),
    ) { captured ->
        if (backupRecoveryOnlyUi || !HansBackupProcessState.maintenance.isRecoveryReady) {
            return@registerForActivityResult
        }
        val video = cameraCaptureCoordinator.acceptVideoResult(captured)
        when {
            video != null -> importCapturedVideo(video)
            captured -> showShortMessage(tr(R.string.integration_the_video_could_not_be_imported_safely_aee3f43))
        }
    }

    private val microphonePermission = registerForActivityResult(RequestPermission()) { granted ->
        if (backupRecoveryOnlyUi || !HansBackupProcessState.maintenance.isRecoveryReady) {
            return@registerForActivityResult
        }
        val softwareGesture = pendingSoftwareHoldPermission
        pendingSoftwareHoldPermission = null
        val command = pendingMicrophoneCommand
        pendingMicrophoneCommand = null
        val startLive = pendingLiveVoiceStart
        pendingLiveVoiceStart = false
        refreshCapabilityAccess()
        val setupHandled = completePendingSetupPermission(
            CapabilityAccessUiId.MICROPHONE,
            granted,
        )
        if (softwareGesture != null) {
            executeSoftwareHoldEffects(
                softwareHoldToTalk.onMicrophonePermissionResult(
                    gestureId = softwareGesture,
                    granted = granted,
                    recordingAlreadyActive = isDictationRecordingActive(),
                ),
            )
        }
        if (!granted && !setupHandled) {
            showShortMessage(tr(R.string.integration_hans_needs_microphone_access_for_voice_features_7deb99b))
        } else if (granted && softwareGesture == null && command != null) {
            executeGrantedDictationCommand(command)
        } else if (granted && softwareGesture == null && startLive) {
            startLiveVoiceAfterDictationStops()
        }
    }

    private val notificationPermission = registerForActivityResult(RequestPermission()) { granted ->
        if (backupRecoveryOnlyUi || !HansBackupProcessState.maintenance.isRecoveryReady) {
            return@registerForActivityResult
        }
        refreshCapabilityAccess()
        val setupHandled = completePendingSetupPermission(
            CapabilityAccessUiId.APP_NOTIFICATIONS,
            granted,
        )
        if (!granted && !setupHandled) {
            showShortMessage(
                tr(R.string.integration_without_hans_notifications_ongoing_voice_and_agent_task_7ac563d),
            )
        }
    }

    private val publicPhonePermissions = registerForActivityResult(
        RequestMultiplePermissions(),
    ) { grants ->
        if (backupRecoveryOnlyUi || !HansBackupProcessState.maintenance.isRecoveryReady) {
            return@registerForActivityResult
        }
        refreshCapabilityAccess()
        val setupHandled = pendingSetupPermissionInteraction
            ?.capability
            ?.takeIf { it in SETUP_MULTI_PERMISSION_CAPABILITIES }
            ?.let { capability ->
                completePendingSetupPermission(
                    capability,
                    isCapabilityAccessEffective(capability),
                )
            }
            ?: false
        if (!setupHandled && grants.isNotEmpty() && grants.values.none { it }) {
            showShortMessage(
                tr(R.string.integration_phone_access_was_not_granted_you_can_set_it_up_again_la_2945b3d),
            )
        }
    }

    private val backupCreateDocument = registerForActivityResult(
        CreateDocument(HansBackupDocumentCodec.MIME_TYPE),
    ) { uri ->
        if (backupRecoveryOnlyUi || !HansBackupProcessState.maintenance.isRecoveryReady) {
            return@registerForActivityResult
        }
        uri?.let(::exportBackupTo)
    }

    private val backupOpenDocument = registerForActivityResult(OpenDocument()) { uri ->
        if (backupRecoveryOnlyUi || !HansBackupProcessState.maintenance.isRecoveryReady) {
            return@registerForActivityResult
        }
        uri?.let(::prepareBackupImportFrom)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hansApplication = application as HansApplication
        if (hansApplication.backupRecoveryRequired || !HansBackupProcessState.maintenance.isRecoveryReady) {
            backupRecoveryOnlyUi = true
            showBackupRecoveryScreen(hansApplication)
            return
        }
        pendingSetupConversationMessageId = SetupConversationPendingReceiptContract.restore(
            savedInstanceState?.getString(SetupConversationPendingReceiptContract.STATE_KEY),
        )
        restrictedSettingsRecovery = savedInstanceState?.let { state ->
            RestrictedSettingsRecoveryState.restore(
                apiLevel = state.getInt(RESTRICTED_SETTINGS_API_STATE_KEY, -1),
                capabilityName = state.getString(RESTRICTED_SETTINGS_CAPABILITY_STATE_KEY),
                stepName = state.getString(RESTRICTED_SETTINGS_STEP_STATE_KEY),
                generation = state.getLong(RESTRICTED_SETTINGS_GENERATION_STATE_KEY, -1L),
                nonce = state.getString(RESTRICTED_SETTINGS_NONCE_STATE_KEY),
                stageName = state.getString(RESTRICTED_SETTINGS_STAGE_STATE_KEY),
            )
        }
        setupRuntime = hansApplication.setupRuntime
        setupHandoffCoordinator = HansSetupHandoffCoordinator(
            AtomicFileHansSetupHandoffStorage(this),
        )
        actionKeyAccessibilityReceiptRegistration =
            GlobalActionKeyAccessibilityReceiptCenter.observe { receipt ->
                runOnUiThread { acceptGlobalActionKeyReceipt(receipt) }
            }
        automationRuntime = hansApplication.automationRuntime
        automationActivityRegistration = automationRuntime.addActivityObserver { activity ->
            if (activity.activeAutomationCount == 0) {
                runOnUiThread {
                    if (
                        !isFinishing && !isDestroyed &&
                        localUi.requestedDestination == HansDestination.AUTOMATIONS
                    ) {
                        refreshAutomations()
                    }
                }
            }
        }
        // Application already recovered any journal before installing the automation owner.
        settingsStore = SharedPreferencesHansSettingsStore(this)
        displayMotionStore = DisplayMotionPreferencesStore(this)
        confirmedSttGlossaryStore = AtomicFileConfirmedSttGlossary(this)
        sttLatencyPreferenceStore = SttLatencyPreferenceStore(this)
        localUi = localUi.copy(sttLatency = localUi.sttLatency.copy(
            preferred = sttLatencyPreferenceStore.read(),
        ))
        refreshConfirmedSttGlossary()
        privateSpaceVisibilityStore =
            SharedPreferencesPrivateSpaceContainerVisibilityStore(this)
        localUi = localUi.copy(
            privateSpace = localUi.privateSpace.copy(
                containerVisible = privateSpaceVisibilityStore.isVisible(),
            ),
            revision = localUi.revision + 1,
        )
        sessionStore = AppPrivateCodexSessionStore(this)
        backupDocumentIo = AndroidHansBackupDocumentIo(contentResolver)
        backupCoordinator = hansApplication.backupCoordinator
        sessionHost = hansApplication.sessionHost
        internetSubscription = hansApplication.internetConnectivity.observe { snapshot ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) acceptInternetSnapshot(snapshot)
            }
        }
        pendingDictationSubscription = sessionHost.observePendingDictations { pending ->
            runOnUiThread {
                if (
                    !isFinishing && !isDestroyed &&
                    (localUi.pendingDictations != pending.drafts ||
                        localUi.pendingDictationStorageUnavailable != pending.storageUnavailable)
                ) {
                    localUi = localUi.copy(
                        pendingDictations = pending.drafts,
                        pendingDictationStorageUnavailable = pending.storageUnavailable,
                        revision = localUi.revision + 1,
                    )
                }
            }
        }
        setupHandoffSignalRegistration = HansSetupHandoffSignalCenter.observe {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    localUi = localUi.copy(
                        requestedDestination = HansDestination.CHAT,
                        revision = localUi.revision + 1,
                    )
                    sessionHost.snapshot()?.let(::driveInstallerSetupHandoff)
                }
            }
        }
        periodicAccountRefresh = PeriodicAccountRefreshController(
            scheduler = HandlerAccountRefreshScheduler(Handler(Looper.getMainLooper())),
            action = AccountRefreshAction(sessionHost::refreshAccount),
        )
        mediaPipeline = AndroidMediaImportPipeline.forWorkspace(this, sessionStore.workspacePath)
        mediaTurnLeaseTracker = MediaTurnLeaseTracker(AtomicFileMediaTurnLeaseStorage(this))
        cameraCaptureCoordinator = AndroidCameraCaptureCoordinator(applicationContext)
        videoTranscriptionGateway = OpenAiVideoAudioTranscriptionGateway(
            sessionHost.speechCredentialStore,
        )
        speechCredentialDialog = AndroidSpeechCredentialDialog(this) { credential ->
            runCatching {
                sessionHost.saveSpeechCredential(credential)
                sessionHost.speechCredentialStatus() == SpeechCredentialStatus.AVAILABLE
            }.getOrDefault(false)
        }
        mediaExecutor.execute {
            cameraCaptureCoordinator.cleanupAbandonedCaptures()
            mediaPipeline.cleanupAbandonedImports()
        }
        settings = settingsStore.read()
        displayMotionMode = displayMotionStore.read()
        localUi = localUi.copy(speechCredentialStatus = currentSpeechCredentialUiStatus())
        actionKeyStore = ActionKeyMappingPreferencesStore(this)
        mp01VendorActionRemediation = AndroidMp01VendorActionRemediation(this)
        mp01VendorActionConfirmation = Mp01VendorActionOverrideConfirmationStore(this)
        val actionMappings = actionKeyStore.read()
        // Legacy input preferences are retained on disk. Runtime projection uses
        // press-to-start/mute and a camera-only button without rewriting them.
        // Stored MP01 mappings remain visible in Settings, but are not handed
        // to either dispatcher until the user confirms Minimal's parallel
        // action was neutralized on this exact device/vendor build.
        actionKeyDispatcher = ActionKeyDispatcher(ActionKeyMappingSet.empty())
        refreshMp01VendorActionConflict(actionMappings)
        publicCapabilityAdapter = AndroidPublicCapabilityAdapter(this)
        refreshPrivateSpaceState()
        refreshCapabilityAccess()
        refreshPersistentAndroidConsents()
        dynamicToolConfirmation = AndroidDynamicToolConfirmationDialog(
            activity = this,
            consentStore = hansApplication.persistentAndroidConsentStore,
            onConsentChanged = { runOnUiThread(::refreshPersistentAndroidConsents) },
        )
        dynamicToolConfirmationRegistration =
            sessionHost.attachConfirmationProvider(dynamicToolConfirmation)
        publicPhoneConfirmation = AndroidPublicPhoneConfirmationDialog(
            activity = this,
            consentStore = hansApplication.persistentAndroidConsentStore,
            onConsentChanged = { runOnUiThread(::refreshPersistentAndroidConsents) },
        ).also { dialog ->
            publicPhoneConfirmationRegistration =
                hansApplication.publicPhoneConfirmationRouter.attach(dialog)
        }
        automationApproval = AndroidAutomationApprovalDialog(this).also { dialog ->
            automationApprovalRegistration = hansApplication.automationApprovalRouter.attach(dialog)
        }
        setupUiRegistration = setupRuntime.attachUi(
            object : SetupUiCommandHandler {
                override fun handle(
                    command: SetupUiCommand,
                    lease: SetupUiCommandLease,
                    completion: (SetupUiCommandResult) -> Unit,
                ) {
                    handleSetupUiCommand(command, lease, completion)
                }

                override fun cancel(command: SetupUiCommand) {
                    cancelSetupUiCommand(command)
                }
            },
        )
        setupRuntime.observeProfileConfirmed(sessionHost.hasConfirmedUserProfile())
        when (sessionHost.speechCredentialStatus()) {
            SpeechCredentialStatus.AVAILABLE ->
                setupRuntime.observeSpeechCredentialAvailability(available = true)
            SpeechCredentialStatus.MISSING ->
                setupRuntime.observeSpeechCredentialAvailability(available = false)
            SpeechCredentialStatus.TEMPORARILY_UNAVAILABLE -> Unit
        }

        val observer = CodexClientObserver { snapshot ->
            runOnUiThread { acceptClientSnapshot(snapshot) }
        }
        clientObserver = observer
        sessionHost.addObserver(observer)

        speechAudioRouteSubscription = hansApplication.speechAudioRoutes.observe { route ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed && route != localUi.speechAudioRoute) {
                    if (route.failure != null && route.failure != localUi.speechAudioRoute.failure) {
                        showShortMessage(tr(R.string.integration_the_requested_audio_output_could_not_be_confirmed_90f71d9))
                    }
                    localUi = localUi.copy(speechAudioRoute = route, revision = localUi.revision + 1)
                }
            }
        }

        val announcementObserver = ValidatedNotificationAnnouncementObserver { announcements ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) acceptValidatedNotifications(announcements)
            }
        }
        notificationAnnouncementObserver = announcementObserver
        hansApplication.notificationAnnouncementCenter.addObserver(announcementObserver)
        notificationReportSubscription = sessionHost.observeNotificationReports { reports ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) acceptNotificationReports(reports)
            }
        }

        val voiceObserver = DictationRuntimeObserver { snapshot ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    acceptDictationSnapshot(snapshot)
                    when (snapshot.failure) {
                        RecordingFailure.NETWORK_UNAVAILABLE -> showOfflineSubmissionNotice(
                            tr(R.string.integration_voice_recording_requires_an_internet_connection_and_was_09d6bbb),
                        )
                        RecordingFailure.TRANSCRIPTION_FAILED -> showConnectionFailure(
                            tr(R.string.integration_transcription_interrupted_check_your_internet_connectio_78cdc85),
                        )
                        RecordingFailure.TRANSCRIPTION_CONFIGURATION_UNCONFIRMED -> showConnectionFailure(
                            tr(R.string.integration_openai_did_not_confirm_the_requested_transcription_late_81e3c7c),
                        )
                        RecordingFailure.SPEECH_CREDENTIAL_MISSING -> showConnectionFailure(
                            openAiSpeechFailureMessage("credential_unavailable", AndroidHansTextResolver(this)),
                        )
                        RecordingFailure.SPEECH_CREDENTIAL_TEMPORARILY_UNAVAILABLE ->
                            showConnectionFailure(
                                tr(R.string.integration_the_openai_voice_credential_exists_but_is_currently_una_e07fdec),
                            )
                        RecordingFailure.OPENAI_AUTHENTICATION_FAILED -> showConnectionFailure(
                            openAiSpeechFailureMessage("authentication_failed", AndroidHansTextResolver(this)),
                        )
                        RecordingFailure.OPENAI_PERMISSION_DENIED -> showConnectionFailure(
                            openAiSpeechFailureMessage("permission_denied", AndroidHansTextResolver(this)),
                        )
                        // Published once by the provider-owning service, including before any
                        // audio callback. Replaying this snapshot must not undo an explicit dismiss.
                        RecordingFailure.OPENAI_QUOTA_EXHAUSTED,
                        RecordingFailure.OPENAI_SPENDING_LIMIT_REACHED,
                        RecordingFailure.OPENAI_PROJECT_SPENDING_LIMIT_REACHED -> Unit
                        RecordingFailure.OPENAI_RATE_LIMITED -> showConnectionFailure(
                            openAiSpeechFailureMessage("rate_limited", AndroidHansTextResolver(this)),
                        )
                        else -> Unit
                    }
                }
            }
        }
        dictationObserver = voiceObserver
        HansDictationRuntime.addObserver(voiceObserver)

        val speechFailureObserver = SpeechServiceFailureObserver { failure ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed && failure.revision >= localUi.speechFailure.revision) {
                    localUi = localUi.copy(speechFailure = failure, revision = localUi.revision + 1)
                }
            }
        }
        speechServiceFailureObserver = speechFailureObserver
        HansSpeechFailureRuntime.addObserver(speechFailureObserver)

        liveVoiceObserver = AndroidLiveVoiceRuntime.addObserver(
            object : LiveVoiceObserver {
                override fun onSnapshot(snapshot: LiveVoiceSnapshot) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) acceptLiveVoiceSnapshot(snapshot)
                    }
                }

                override fun onUserTranscript(text: String, isFinal: Boolean) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            acceptLiveVoiceTranscript(ChatMessageAuthor.USER, text, isFinal)
                        }
                    }
                }

                override fun onHansTranscript(text: String, isFinal: Boolean) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            acceptLiveVoiceTranscript(ChatMessageAuthor.HANS, text, isFinal)
                        }
                    }
                }

                override fun onTranscriptRevision(event: ai.hans.standard.voice.realtime.LiveVoiceTranscriptRevision) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            val author = when (event.author) {
                                ai.hans.standard.voice.realtime.LiveVoiceTranscriptAuthor.USER -> ChatMessageAuthor.USER
                                ai.hans.standard.voice.realtime.LiveVoiceTranscriptAuthor.HANS -> ChatMessageAuthor.HANS
                            }
                            acceptLiveVoiceTranscript(author, event.text, event.isFinal, event)
                        }
                    }
                }

                override fun onFailure(failure: LiveVoiceFailure) {
                    // The process-owned runtime reports failures even when no Activity exists.
                    // Its replayable speechFailureObserver above owns the visible notice.
                }
            },
        )
        liveVoiceCaptureObserver = AndroidLiveVoiceRuntime.addCaptureActivityObserver { active ->
            if (!active) runOnUiThread {
                if (!isFinishing && !isDestroyed) drainPendingDictationAfterLiveVoice()
            }
        }

        whatsAppAgentChannelSubscription = hansApplication.whatsAppAgentChannel.observeStatus { status ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    whatsAppAgentChannelState = ai.hans.standard.ui.WhatsAppAgentChannelUiState(
                        available = status.available,
                        enabled = status.binding != null,
                        pendingCount = status.readyCount,
                        uncertainCount = status.uncertainCount,
                        lookupRequiredCount = status.lookupRequiredCount,
                    )
                }
            }
        }

        setContent {
            HansTheme {
                HansApp(
                    state = HansClientUiProjector.project(clientSnapshot, localUi, settings, text = rememberHansTextResolver()).let { projected ->
                        projected.copy(
                            settings = projected.settings.copy(
                                displayMotionMode = displayMotionMode,
                                notificationFactArchive = notificationFactArchiveStatus,
                                whatsAppAgentChannel = whatsAppAgentChannelState,
                                phoneActionPolicy = ai.hans.standard.phone.consent.HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS,
                            ),
                        )
                    },
                    callbacks = callbacks(),
                    modifier = Modifier.hansModelShortcutKeys(::routeModelKeyBeforeIme),
                )
            }
        }
        consumeRemoteMcpOAuthResult(intent)
        drivePendingRemoteMcpOAuthResult()
        sessionHost.snapshot()?.let(::driveInstallerSetupHandoff)
    }

    private fun refreshNotificationFactArchiveStatus() {
        if (localUi.requestedDestination != HansDestination.SETTINGS || isDestroyed) return
        val generation = notificationFactStatusGeneration.begin()
        notificationFactArchiveStatus = ai.hans.standard.ui.NotificationFactArchiveStatus(loading = true)
        val appContext = applicationContext
        val accepted = runCatching {
            appCatalogExecutor.execute {
                val health = runCatching {
                    ai.hans.standard.phone.notifications.facts.AndroidNotificationFactArchiveFactory
                        .create(appContext).use { it.health() }
                }.getOrNull()
                val queue = runCatching {
                    ai.hans.standard.notifications.NotificationTriageQueue(
                        storage = ai.hans.standard.notifications.AtomicFileNotificationTriageStorage(appContext),
                        exclusionPolicy = ai.hans.standard.notifications.HansNotificationExclusionPolicy(
                            ownPackageNames = setOf(appContext.packageName),
                        ),
                    ).health()
                }.getOrNull()
                val snapshot = ai.hans.standard.ui.NotificationFactArchiveStatus.from(health, queue)
                runOnUiThread {
                    if (!isDestroyed && notificationFactStatusGeneration.accepts(generation) &&
                        localUi.requestedDestination == HansDestination.SETTINGS) {
                        notificationFactArchiveStatus = snapshot
                    }
                }
            }
        }.isSuccess
        if (!accepted && notificationFactStatusGeneration.accepts(generation)) {
            notificationFactArchiveStatus = ai.hans.standard.ui.NotificationFactArchiveStatus()
        }
    }

    private fun configureWhatsAppAgentChannel() {
        val generation = ++whatsAppAgentChannelDialogGeneration
        val coordinator = (application as HansApplication).whatsAppAgentChannel
        appCatalogExecutor.execute {
            val candidates = coordinator.channel.listEnrollmentCandidates()
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != whatsAppAgentChannelDialogGeneration ||
                    !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                ) return@runOnUiThread
                whatsAppAgentChannelDialog?.dismiss()
                val builder = AlertDialog.Builder(this)
                    .setTitle(tr(R.string.agent_channel_title))
                    .setNegativeButton(tr(R.string.agent_channel_cancel), null)
                if (candidates.isEmpty()) {
                    builder.setMessage(tr(R.string.agent_channel_no_candidates))
                } else {
                    builder.setTitle(tr(R.string.agent_channel_configure))
                        .setItems(candidates.map {
                            it.source.displayTitle.ifBlank { tr(R.string.agent_channel_unknown_title) }
                        }.toTypedArray()) { _, which ->
                            val candidate = candidates.getOrNull(which) ?: return@setItems
                            val title = candidate.source.displayTitle.ifBlank {
                                tr(R.string.agent_channel_unknown_title)
                            }
                            whatsAppAgentChannelDialog = AlertDialog.Builder(this)
                                .setTitle(tr(R.string.agent_channel_confirm_title))
                                .setMessage(tr(R.string.agent_channel_confirm_body, title))
                                .setNegativeButton(tr(R.string.agent_channel_cancel), null)
                                .setPositiveButton(tr(R.string.agent_channel_confirm)) { _, _ ->
                                    appCatalogExecutor.execute {
                                        val permitted = getSystemService(android.app.KeyguardManager::class.java)
                                            ?.isDeviceLocked == false &&
                                            ai.hans.standard.phone.notifications.NotificationPrivacyRepository(applicationContext)
                                                .captureDecision("com.whatsapp") ==
                                                ai.hans.standard.phone.notifications.NotificationCaptureDecision.Allowed
                                        val confirmed = permitted && coordinator.channel.confirmSelfChat(candidate.id)
                                        coordinator.requestDrain()
                                        if (!confirmed) runOnUiThread {
                                            if (!isFinishing && !isDestroyed) showShortMessage(tr(R.string.agent_channel_confirm_failed))
                                        }
                                    }
                                }.show()
                        }
                }
                whatsAppAgentChannelDialog = builder.show()
            }
        }
    }

    private fun refreshConfirmedSttGlossary(notice: String = "") {
        val terms = runCatching(confirmedSttGlossaryStore::readConfirmedTerms)
            .getOrElse {
                localUi = localUi.copy(
                    confirmedSttGlossary = localUi.confirmedSttGlossary.copy(
                        notice = notice.ifBlank {
                            tr(R.string.integration_the_confirmed_terms_could_not_be_read_safely_08ce4b0)
                        },
                    ),
                    revision = localUi.revision + 1,
                )
                return
            }
        localUi = localUi.copy(
            confirmedSttGlossary = ConfirmedSttGlossaryUiState(
                terms = terms,
                notice = notice,
            ),
            revision = localUi.revision + 1,
        )
    }

    private fun saveConfirmedSttGlossary(editorText: String) {
        val submittedLines = editorText.lineSequence()
            .filter(String::isNotBlank)
            .toList()
        val normalized = ConfirmedSttGlossaryPolicy.parseEditorText(editorText)
        val saved = runCatching {
            confirmedSttGlossaryStore.replaceConfirmedTerms(
                terms = normalized,
                explicitUserConfirmation = true,
            )
            confirmedSttGlossaryStore.readConfirmedTerms()
        }.getOrElse {
            refreshConfirmedSttGlossary(
                tr(R.string.integration_the_terms_could_not_be_saved_safely_the_previous_versio_917b4ab),
            )
            return
        }
        val wasNormalized = submittedLines != saved
        localUi = localUi.copy(
            confirmedSttGlossary = ConfirmedSttGlossaryUiState(
                terms = saved,
                notice = if (wasNormalized) {
                    tr(R.string.integration_saved_whitespace_duplicates_and_limits_were_safely_norm_9e35860)
                } else {
                    tr(R.string.integration_saved_the_terms_apply_from_the_next_recording_b9b143d)
                },
            ),
            revision = localUi.revision + 1,
        )
    }

    override fun refreshLocalizedPresentation(newConfig: android.content.res.Configuration) {
        if (backupRecoveryOnlyUi || !::sessionHost.isInitialized) return
        // Locale/layout-direction changes stay in this Activity: preserve the complete current
        // composer, attachment handles, pending send/steer state and active voice/session owners.
        // Compose receives Android's configuration event; only cached presentation is refreshed.
        localUi = localUi.copy(revision = localUi.revision + 1)
        refreshCapabilityAccess()
        refreshPersistentAndroidConsents()
        refreshPrivateSpaceState()
        when (localUi.requestedDestination) {
            HansDestination.APPS -> refreshAppDrawer()
            HansDestination.AUTOMATIONS -> refreshAutomations()
            HansDestination.WORKBENCH -> refreshWorkbench()
            HansDestination.SETTINGS -> refreshNotificationFactArchiveStatus()
            else -> Unit
        }
    }

    override fun onResume() {
        super.onResume()
        if (backupRecoveryOnlyUi) return
        if (!HansBackupProcessState.maintenance.isRecoveryReady) {
            recreate()
            return
        }
        AndroidAutomationUnlockRecovery.onLauncherResumed(this)
        acceptInternetSnapshot((application as HansApplication).internetConnectivity.snapshot())
        localUi = localUi.copy(
            speechCredentialStatus = currentSpeechCredentialUiStatus(),
            revision = localUi.revision + 1,
        )
        refreshCapabilityAccess()
        refreshPersistentAndroidConsents()
        refreshPrivateSpaceState()
        refreshMp01VendorActionConflict(actionKeyStore.read())
        setupRuntime.refreshFreshEvidence()
        continueRestrictedSettingsRecoveryAfterReturn()
        setupRuntime.observeProfileConfirmed(sessionHost.hasConfirmedUserProfile())
        // The immediate lifecycle check also confirms a completed device-code
        // login; subsequent bounded checks catch expiry during ordinary use.
        periodicAccountRefresh.start()
        sessionHost.retryDeferredAnnouncements()
        if (localUi.requestedDestination == HansDestination.APPS) refreshAppDrawer()
        if (localUi.requestedDestination == HansDestination.AUTOMATIONS) refreshAutomations()
        if (localUi.requestedDestination == HansDestination.WORKBENCH) refreshWorkbench()
        if (localUi.requestedDestination == HansDestination.SETTINGS) refreshNotificationFactArchiveStatus()
    }

    override fun onStart() {
        super.onStart()
        if (backupRecoveryOnlyUi) return
        if (!HansBackupProcessState.maintenance.isRecoveryReady) {
            recreate()
            return
        }
        launcherUiStarted = true
        if (!launcherProfileReceiverRegistered) {
            runCatching {
                ContextCompat.registerReceiver(
                    this,
                    launcherProfileEventReceiver,
                    LauncherProfileEventReceiver.intentFilter(),
                    ContextCompat.RECEIVER_EXPORTED,
                )
            }.onSuccess { launcherProfileReceiverRegistered = true }
        }
        val launcherApps = getSystemService(LauncherApps::class.java)
        if (!launcherAppsCallbackRegistered && launcherApps != null) {
            runCatching {
                launcherApps.registerCallback(
                    launcherAppsCallback,
                    Handler(Looper.getMainLooper()),
                )
            }.onSuccess { launcherAppsCallbackRegistered = true }
        }
        if (
            Build.VERSION.SDK_INT >= 36 &&
            launcherApps != null &&
            launcherUserConfigCallback == null
        ) {
            runCatching {
                requireNotNull(LauncherUserConfigCallbackFactory.createIfSupported {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            refreshPrivateSpaceState()
                            if (localUi.requestedDestination == HansDestination.APPS) {
                                refreshAppDrawer()
                            }
                        }
                    }
                }).also { callback ->
                    launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
                }
            }.onSuccess { callback -> launcherUserConfigCallback = callback }
        }
    }

    override fun onStop() {
        whatsAppAgentChannelDialogGeneration += 1
        whatsAppAgentChannelDialog?.dismiss()
        whatsAppAgentChannelDialog = null
        notificationFactStatusGeneration.invalidate()
        if (backupRecoveryOnlyUi) {
            super.onStop()
            return
        }
        launcherUiStarted = false
        purgeNonPersonalAppInventory()
        if (launcherProfileReceiverRegistered) {
            runCatching { unregisterReceiver(launcherProfileEventReceiver) }
            launcherProfileReceiverRegistered = false
        }
        if (launcherAppsCallbackRegistered) {
            runCatching {
                getSystemService(LauncherApps::class.java)
                    ?.unregisterCallback(launcherAppsCallback)
            }
            launcherAppsCallbackRegistered = false
        }
        launcherUserConfigCallback?.let { callback ->
            runCatching {
                getSystemService(LauncherApps::class.java)?.unregisterCallback(callback)
            }
        }
        launcherUserConfigCallback = null
        super.onStop()
    }

    override fun onPause() {
        if (backupRecoveryOnlyUi) {
            super.onPause()
            return
        }
        periodicAccountRefresh.stop()
        foregroundModelKeyRouter.clearActivePresses()
        executeSoftwareHoldEffects(softwareHoldToTalk.onFocusLost())
        pendingSoftwareHoldPermission = null
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (backupRecoveryOnlyUi || !HansBackupProcessState.maintenance.isRecoveryReady) return
        if (!hasFocus) {
            foregroundModelKeyRouter.clearActivePresses()
            executeSoftwareHoldEffects(softwareHoldToTalk.onFocusLost())
            pendingSoftwareHoldPermission = null
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (backupRecoveryOnlyUi) return
        if (consumeRemoteMcpOAuthResult(intent)) {
            drivePendingRemoteMcpOAuthResult()
            return
        }
        localUi = localUi.copy(
            requestedDestination = HansDestination.CHAT,
            revision = localUi.revision + 1,
        )
    }

    /**
     * This sees only keys Android has delivered to Hans. It deliberately does
     * not install a global hook, remap the system keys, or make any device
     * assumption; the user-created [ActionKeyMapping] decides what is handled.
     */
    // ComponentActivity intentionally owns the dispatch bridge, but a launcher
    // must observe the delivered event before a focused Compose child can
    // consume it in order to capture a user-selected physical action key. This
    // remains ordinary foreground Android input; it is neither a global hook
    // nor a hidden/private platform API.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (backupRecoveryOnlyUi || !HansBackupProcessState.maintenance.isRecoveryReady) {
            return super.dispatchKeyEvent(event)
        }
        val observed = AndroidKeyEventObserver.observe(event) ?: return super.dispatchKeyEvent(event)
        val captureSession = activeActionKeyCapture
        if (captureSession != null) {
            // Accessibility normally owns capture. Foreground dispatch is the
            // public-API fallback while its dynamic filter flag is taking
            // effect or when Accessibility has not been enabled yet.
            GlobalActionKeyCaptureGate.deliver(observed)
            return true
        }
        if (Mp01VendorActionConflictResolver.recognizesPhysicalEvent(observed)) {
            // Public-API TOCTOU guard: the vendor service can be toggled while
            // Hans remains foreground. Re-probe before this physical event is
            // ever allowed to reach the cached foreground dispatcher.
            refreshMp01VendorActionConflict(actionKeyStore.read())
        }
        if (routeForegroundModelKey(observed)) return true
        return when (val result = actionKeyDispatcher.onDeliveredEvent(observed)) {
            is ActionKeyDispatchResult.Command -> {
                executeActionKeyCommand(result.command)
                true
            }
            is ActionKeyDispatchResult.AwaitingRelease -> true
            is ActionKeyDispatchResult.Ignored -> {
                if (result.reason == ActionKeyIgnoreReason.STOCK_MP01_PRESS_TOO_LONG) {
                    // Hans consumed the matching DOWN. Never leak an orphaned
                    // long-release half-event into a focused child.
                    true
                } else {
                    super.dispatchKeyEvent(event)
                }
            }
        }
    }

    private fun routeModelKeyBeforeIme(event: KeyEvent): Boolean {
        if (backupRecoveryOnlyUi || !HansBackupProcessState.maintenance.isRecoveryReady ||
            !hasWindowFocus() || activeActionKeyCapture != null
        ) return false
        val observed = AndroidKeyEventObserver.observe(event) ?: return false
        if (Mp01VendorActionConflictResolver.recognizesPhysicalEvent(observed)) {
            refreshMp01VendorActionConflict(actionKeyStore.read())
        }
        return routeForegroundModelKey(observed)
    }

    private fun routeForegroundModelKey(event: ai.hans.standard.phone.keys.ObservableAndroidKeyEvent): Boolean {
        val result = foregroundModelKeyRouter.onDeliveredEvent(event) ?: return false
        if (result is ActionKeyDispatchResult.Command) executeActionKeyCommand(result.command)
        // Consume the complete owned stream, including repeats and debounced releases,
        // so a configured SYM press cannot also change the stock IME's symbol layer.
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        SetupConversationPendingReceiptContract.save(pendingSetupConversationMessageId)?.let {
            outState.putString(SetupConversationPendingReceiptContract.STATE_KEY, it)
        }
        restrictedSettingsRecovery?.let { recovery ->
            outState.putInt(RESTRICTED_SETTINGS_API_STATE_KEY, recovery.apiLevel)
            outState.putString(
                RESTRICTED_SETTINGS_CAPABILITY_STATE_KEY,
                recovery.capability.name,
            )
            outState.putString(RESTRICTED_SETTINGS_STEP_STATE_KEY, recovery.token.step.name)
            outState.putLong(
                RESTRICTED_SETTINGS_GENERATION_STATE_KEY,
                recovery.token.generation,
            )
            outState.putString(RESTRICTED_SETTINGS_NONCE_STATE_KEY, recovery.token.nonce)
            outState.putString(RESTRICTED_SETTINGS_STAGE_STATE_KEY, recovery.stage.name)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        notificationFactStatusGeneration.invalidate()
        if (backupRecoveryOnlyUi) {
            mediaExecutor.shutdownNow()
            appCatalogExecutor.shutdownNow()
            backupExecutor.shutdownNow()
            remoteMcpOAuthExecutor.shutdownNow()
            remoteMcpPolicyExecutor.shutdownNow()
            remoteWorkerPersistenceExecutor.shutdownNow()
            automationUiExecutor.shutdownNow()
            super.onDestroy()
            return
        }
        actionKeyCaptureTimeout?.let(actionKeyCaptureHandler::removeCallbacks)
        actionKeyCaptureTimeout = null
        activeActionKeyCapture?.let(actionKeyCapture::cancel)
        activeActionKeyCapture = null
        closeActionKeyCaptureLease()
        actionKeyAccessibilityReceiptRegistration?.close()
        actionKeyAccessibilityReceiptRegistration = null
        setupHandoffSignalRegistration?.close()
        setupHandoffSignalRegistration = null
        clientObserver?.let(sessionHost::removeObserver)
        clientObserver = null
        dictationObserver?.let(HansDictationRuntime::removeObserver)
        dictationObserver = null
        speechServiceFailureObserver?.let(HansSpeechFailureRuntime::removeObserver)
        speechServiceFailureObserver = null
        liveVoiceObserver?.cancel()
        liveVoiceObserver = null
        liveVoiceCaptureObserver?.cancel()
        liveVoiceCaptureObserver = null
        pendingDictationAfterLiveVoice = null
        pendingLiveVoiceStart = false
        speechAudioRouteSubscription?.close()
        speechAudioRouteSubscription = null
        internetSubscription?.close()
        internetSubscription = null
        pendingDictationSubscription?.close()
        pendingDictationSubscription = null
        notificationAnnouncementObserver?.let {
            (application as HansApplication).notificationAnnouncementCenter.removeObserver(it)
        }
        notificationAnnouncementObserver = null
        notificationReportSubscription?.close()
        notificationReportSubscription = null
        whatsAppAgentChannelSubscription?.close()
        whatsAppAgentChannelSubscription = null
        whatsAppAgentChannelDialogGeneration += 1
        whatsAppAgentChannelDialog?.dismiss()
        whatsAppAgentChannelDialog = null
        dynamicToolConfirmationRegistration?.close()
        dynamicToolConfirmationRegistration = null
        dynamicToolConfirmation.close()
        publicPhoneConfirmationRegistration?.close()
        publicPhoneConfirmationRegistration = null
        publicPhoneConfirmation?.close()
        publicPhoneConfirmation = null
        automationApprovalRegistration?.close()
        automationApprovalRegistration = null
        automationApproval?.close()
        automationApproval = null
        automationActivityRegistration?.close()
        automationActivityRegistration = null
        setupUiRegistration?.close()
        setupUiRegistration = null
        pendingSetupConversationMessageId = null
        runCatching {
            pendingSetupPermissionInteraction
                ?.completion
                ?.invoke(SetupUiCommandResult.UserInteractionRequired)
        }
        pendingSetupPermissionInteraction = null
        runCatching {
            pendingSetupCameraCapture
                ?.completion
                ?.invoke(SetupUiCommandResult.UserInteractionRequired)
        }
        pendingSetupCameraCapture = null
        runCatching {
            pendingSetupQuickSettingsTile
                ?.completion
                ?.invoke(SetupUiCommandResult.UserInteractionRequired)
        }
        pendingSetupQuickSettingsTile = null
        activeSetupKeyCaptureToken?.let { token ->
            runCatching { setupRuntime.abandonKeyCaptureForActivityRecreation(token) }
        }
        activeSetupKeyCaptureToken = null
        activeSetupDictationTestToken?.let { token ->
            runCatching { setupRuntime.abandonDictationLiveTestForActivityRecreation(token) }
        }
        activeSetupDictationTestToken = null
        setupVoiceTestDialog?.dismiss()
        setupVoiceTestDialog = null
        setupVoiceTestDialogToken = null
        setupVoiceStopDialog?.dismiss()
        setupVoiceStopDialog = null
        setupAccessibilityPostconditionCheck?.let(actionKeyCaptureHandler::removeCallbacks)
        setupAccessibilityPostconditionCheck = null
        setupAccessibilityTestDialog?.dismiss()
        setupAccessibilityTestDialog = null
        setupEverydayAccessDialog?.dismiss()
        setupEverydayAccessDialog = null
        setupEverydayAccessDialogToken = null
        notificationLinkMetadataDialog?.dismiss()
        notificationLinkMetadataDialog = null
        notificationLinkMetadataDialogToken = null
        restrictedSettingsRecoveryDialog?.dismiss()
        restrictedSettingsRecoveryDialog = null
        cameraCaptureChoiceDialog?.dismiss()
        cameraCaptureChoiceDialog = null
        speechCredentialDialog?.close()
        speechCredentialDialog = null
        activeSetupSpeechCredentialToken = null
        periodicAccountRefresh.close()
        videoTranscriptions.values.forEach(VideoAudioTranscriptionCancellation::cancel)
        videoTranscriptions.clear()
        videoTranscriptionGateway.close()
        if (::backupCoordinator.isInitialized) backupCoordinator.cancelImport()
        mediaExecutor.shutdownNow()
        appCatalogExecutor.shutdownNow()
        backupExecutor.shutdownNow()
        remoteMcpOAuthExecutor.shutdownNow()
        remoteMcpPolicyExecutor.shutdownNow()
        remoteWorkerPersistenceExecutor.shutdownNow()
        automationUiExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun acceptClientSnapshot(snapshot: CodexClientSnapshot) {
        if (!HansBackupProcessState.maintenance.isRecoveryReady) {
            if (!isFinishing && !isDestroyed) recreate()
            return
        }
        val diagnosticState = buildString {
            append(snapshot.runtimePhase.name)
            append('/')
            append(snapshot.sessionPhase.name)
            append('/')
            append(snapshot.problem?.code?.name ?: "OK")
            append(" generation=")
            append(snapshot.generation ?: 0)
        }
        if (diagnosticState != lastDiagnosticState) {
            lastDiagnosticState = diagnosticState
            Log.i(DIAGNOSTIC_TAG, diagnosticState)
        }
        clientSnapshot = snapshot
        reconcilePendingSetupConversation(snapshot)
        // Stored preferences seed confirmedSelection before any fresh runtime proof exists.
        snapshot.migrationReadiness.effectiveSelection?.let { effective ->
            setupRuntime.observeEffectiveSelection(
                model = effective.model,
                reasoningEffort = effective.effort.wireValue,
            )
        }
        setupRuntime.observeProfileConfirmed(sessionHost.hasConfirmedUserProfile())
        reconcilePendingComposer(snapshot)
        scheduleTerminalMediaCleanup(snapshot)
        settings = settingsStore.read()
        drivePendingRemoteMcpOAuthResult()
        driveInstallerSetupHandoff(snapshot)
    }

    private fun acceptInternetSnapshot(snapshot: InternetSnapshot) {
        if (snapshot.revision < localUi.internet.revision || snapshot == localUi.internet) return
        localUi = localUi.copy(internet = snapshot, revision = localUi.revision + 1)
        // Never retry a user message, restart a task, or clear a transport error on reconnect.
    }

    private fun showConnectionFailure(message: String) {
        localUi = localUi.copy(connectionFailureMessage = message, revision = localUi.revision + 1)
    }

    private fun showOfflineSubmissionNotice(
        message: String = tr(R.string.integration_not_sent_your_text_and_attachments_remain_in_the_messag_4bd0977),
    ): Boolean {
        val current = (application as HansApplication).internetConnectivity.snapshot()
        acceptInternetSnapshot(current)
        localUi = blockOfflineComposerSubmission(localUi, current, message) ?: return false
        return true
    }

    private fun acceptDictationSnapshot(snapshot: DictationRuntimeSnapshot) {
        val phase = snapshot.phase
        val setupToken = activeSetupDictationTestToken
        val setupEvidence = when (phase) {
            DictationUiPhase.LISTENING -> HansSetupDictationEvidence.LISTENING
            DictationUiPhase.SENT -> HansSetupDictationEvidence.SENT
            DictationUiPhase.NATIVE_COMPLETED -> HansSetupDictationEvidence.NATIVE_LIVE_COMPLETED
            DictationUiPhase.FAILED -> HansSetupDictationEvidence.FAILED
            else -> null
        }
        if (setupToken != null && setupEvidence != null) {
            val state = setupRuntime.acceptDictationEvidence(setupToken, setupEvidence)
            if (
                state.record(setupToken.step).terminal ||
                state.currentStep != setupToken.step ||
                setupEvidence == HansSetupDictationEvidence.FAILED
            ) {
                activeSetupDictationTestToken = null
            } else if (
                setupToken.step == HansSetupStep.CAMERA_HOLD_LIVE_TEST &&
                setupEvidence in setOf(
                    HansSetupDictationEvidence.LISTENING,
                    HansSetupDictationEvidence.SENT,
                    HansSetupDictationEvidence.NATIVE_LIVE_COMPLETED,
                    HansSetupDictationEvidence.FAILED,
                )
            ) {
                showNextCameraSetupAction(setupToken)
            }
        }
        if (
            phase == DictationUiPhase.LISTENING &&
            setupToken?.step == HansSetupStep.VOICE_DICTATION_TEST
        ) {
            offerSetupDictationStop()
        }
        // Both terminal success states are intentionally not rendered. SENT confirms the
        // legacy transcript dispatch; NATIVE_COMPLETED only proves a correlated Voice reply.
        // Actual Codex work is represented by its own authoritative chat timeline.
        val nextStatus = when (phase) {
            DictationUiPhase.IDLE,
            DictationUiPhase.SENT,
            DictationUiPhase.NATIVE_COMPLETED,
            -> null
            DictationUiPhase.PREPARING -> DictationUiStatus.PREPARING
            DictationUiPhase.LISTENING -> DictationUiStatus.LISTENING
            DictationUiPhase.FINALIZING -> DictationUiStatus.FINALIZING
            DictationUiPhase.WAITING_TO_SEND -> DictationUiStatus.WAITING_TO_SEND
            DictationUiPhase.FAILED -> DictationUiStatus.FAILED
        }
        val nextLiveStatus = localUi.liveVoiceStatus.afterDictationPublication(nextStatus)
        if (localUi.dictationStatus != nextStatus ||
            localUi.liveVoiceStatus != nextLiveStatus ||
            localUi.dictationInputMuted != snapshot.inputMuted ||
            localUi.dictationPreview != snapshot.provisionalTranscript ||
            localUi.dictationAwaitingFirstTranscript != snapshot.awaitingFirstUserTranscript ||
            localUi.sttLatency.confirmedActive != snapshot.confirmedTranscriptionDelay
        ) {
            localUi = localUi.copy(
                dictationStatus = nextStatus,
                liveVoiceStatus = nextLiveStatus,
                dictationInputMuted = snapshot.inputMuted,
                dictationPreview = snapshot.provisionalTranscript,
                dictationAwaitingFirstTranscript = snapshot.awaitingFirstUserTranscript,
                sttLatency = localUi.sttLatency.copy(confirmedActive = snapshot.confirmedTranscriptionDelay),
                revision = localUi.revision + 1,
            )
        }
        // FINALIZING still owns the native Voice connection and buffered input. Neither
        // a gesture release nor a request to start regular Live may treat it as terminal.
        val released = !VoiceInputTransitionPolicy.dictationOwnsVoice(phase) &&
            !VoiceInputTransitionPolicy.dictationOwnsVoice(HansDictationRuntime.snapshotUi().phase)
        if (released) {
            softwareHoldToTalk.onOwnedRecordingEnded()
        }
        if (pendingLiveVoiceStart && released) {
            pendingLiveVoiceStart = false
            startLiveVoiceNow()
        }
    }

    private fun acceptLiveVoiceSnapshot(snapshot: LiveVoiceSnapshot) {
        if (snapshot.phase in setOf(LiveVoicePhase.CONNECTING, LiveVoicePhase.STOPPED, LiveVoicePhase.FAILED)) {
            // A missing transcript-final must not let the next session overwrite an old bubble.
            activeLiveUserMessageId = null
            activeLiveHansMessageId = null
        }
        if (snapshot.entryPoint == ai.hans.standard.voice.realtime.LiveVoiceEntryPoint.DICTATION) {
            // Shared media never opens the telephone presentation for dictation.
            if (localUi.liveVoiceStatus != null) {
                localUi = localUi.copy(liveVoiceStatus = null, revision = localUi.revision + 1)
            }
            return
        }
        val nextStatus = when (snapshot.phase) {
            LiveVoicePhase.IDLE,
            LiveVoicePhase.STOPPED,
            -> null
            LiveVoicePhase.CONNECTING,
            LiveVoicePhase.CONFIGURING,
            -> LiveVoiceUiStatus.CONNECTING
            LiveVoicePhase.LISTENING -> LiveVoiceUiStatus.LISTENING
            LiveVoicePhase.USER_SPEAKING -> LiveVoiceUiStatus.USER_SPEAKING
            LiveVoicePhase.HANS_SPEAKING -> LiveVoiceUiStatus.HANS_SPEAKING
            LiveVoicePhase.WAITING_FOR_TASK -> LiveVoiceUiStatus.WAITING_FOR_TASK
            LiveVoicePhase.RECONNECTING -> LiveVoiceUiStatus.RECONNECTING
            // Observer registration can replay a dead PHONE snapshot after the current
            // dictation's AwaitingAudioFocus. It must not restore the obsolete call surface.
            LiveVoicePhase.FAILED -> LiveVoiceUiStatus.FAILED.afterDictationPublication(localUi.dictationStatus)
        }
        if (
            localUi.liveVoiceStatus != nextStatus ||
            localUi.liveVoiceInputMuted != snapshot.inputMuted ||
            localUi.liveVoiceVoiceSelection != snapshot.voiceSelection
        ) {
            localUi = localUi.copy(
                liveVoiceStatus = nextStatus,
                liveVoiceInputMuted = snapshot.inputMuted,
                liveVoiceVoiceSelection = snapshot.voiceSelection,
                revision = localUi.revision + 1,
            )
        }
        drainPendingDictationAfterLiveVoice()
    }

    private fun acceptLiveVoiceTranscript(
        author: ChatMessageAuthor,
        rawText: String,
        isFinal: Boolean,
        identity: ai.hans.standard.voice.realtime.LiveVoiceTranscriptRevision? = null,
    ) {
        // The native assistant renderer owns Markdown and link labels. Keep the destination
        // in this UI-only transcript instead of removing it before the user can tap the link.
        // Actual Live audio remains the Realtime transport's output, not a second TTS pass.
        val text = rawText.trim().take(MAX_LIVE_TRANSCRIPT_CHARACTERS)
        val existingId = when (author) {
            ChatMessageAuthor.USER -> activeLiveUserMessageId
            ChatMessageAuthor.HANS -> activeLiveHansMessageId
            ChatMessageAuthor.SYSTEM -> null
        }
        if (text.isBlank()) {
            if (isFinal) {
                val discarded = ai.hans.standard.ui.LiveVoiceTranscriptUiUpdates.discardIncomplete(
                    localUi.liveVoiceMessages, identity?.displayId ?: existingId, author)
                if (discarded != localUi.liveVoiceMessages) {
                    localUi = localUi.copy(liveVoiceMessages = discarded, revision = localUi.revision + 1)
                }
                setActiveLiveMessageId(author, null)
            }
            return
        }
        val id = identity?.displayId ?: existingId ?: "live-${++nextLiveVoiceMessageId}-${author.name.lowercase()}"
        val existing = localUi.liveVoiceMessages.firstOrNull { it.id == id }
        val message = ChatMessageUiModel(
            id = id,
            author = author,
            text = text,
            revision = (existing?.revision ?: -1L) + 1L,
            localTimelineAnchorId = existing?.localTimelineAnchorId
                ?: clientSnapshot?.timeline?.latestVisibleChatTimelineId(),
            localArrivalOrder = existing?.localArrivalOrder ?: nextLocalTimelineArrivalOrder(),
            complete = isFinal,
            liveVoiceTranscript = identity,
        )
        val updated = localUi.liveVoiceMessages
            .filterNot { it.id == id }
            .plus(message)
            .takeLast(MAX_LIVE_TRANSCRIPT_MESSAGES)
        localUi = localUi.copy(
            liveVoiceMessages = updated,
            revision = localUi.revision + 1,
        )
        setActiveLiveMessageId(author, if (isFinal) null else id)
    }

    private fun acceptValidatedNotifications(
        announcements: List<ValidatedNotificationAnnouncement>,
    ) {
        val existingById = localUi.notificationMessages.associateBy(ChatMessageUiModel::id)
        val projected = announcements.map { announcement ->
            val existing = existingById[announcement.id]
            ChatMessageUiModel(
                id = announcement.id,
                author = ChatMessageAuthor.SYSTEM,
                text = ai.hans.standard.integration.AssistantOutputSanitizer.sanitize(announcement.summary),
                revision = if (announcement.spokenAtEpochMillis == null) 0 else 1,
                localTimelineAnchorId = announcement.timelineAnchorId,
                localArrivalOrder = existing?.localArrivalOrder
                    ?: nextLocalTimelineArrivalOrder(),
            )
        }
        if (projected != localUi.notificationMessages) {
            localUi = localUi.copy(
                notificationMessages = projected,
                revision = localUi.revision + 1,
            )
        }
    }

    private fun acceptNotificationReports(reports: List<ai.hans.standard.integration.NativeNotificationReportCard>) {
        val existing = localUi.notificationReportMessages.associateBy(ChatMessageUiModel::id)
        val projected = reports.map { report -> ChatMessageUiModel(
            id = report.id, author = ChatMessageAuthor.HANS,
            text = ai.hans.standard.integration.AssistantOutputSanitizer.sanitize(report.text),
            revision = 0, complete = true, localTimelineAnchorId = report.timelineAnchorId,
            localArrivalOrder = existing[report.id]?.localArrivalOrder ?: nextLocalTimelineArrivalOrder(),
        ) }
        val threadId = reports.firstOrNull()?.threadId
        if (projected != localUi.notificationReportMessages || threadId != localUi.notificationReportThreadId) {
            localUi = localUi.copy(notificationReportMessages = projected, notificationReportThreadId = threadId,
                revision = localUi.revision + 1)
        }
    }

    private fun setActiveLiveMessageId(author: ChatMessageAuthor, id: String?) {
        when (author) {
            ChatMessageAuthor.USER -> activeLiveUserMessageId = id
            ChatMessageAuthor.HANS -> activeLiveHansMessageId = id
            ChatMessageAuthor.SYSTEM -> Unit
        }
    }

    private fun nextLocalTimelineArrivalOrder(): Long {
        nextLocalTimelineArrivalOrder += 1
        return nextLocalTimelineArrivalOrder
    }

    private fun toggleLiveVoice() {
        if (pendingLiveVoiceStart && !isLiveVoiceActive()) {
            pendingLiveVoiceStart = false
        } else if (isLiveVoiceActive()) {
            pendingLiveVoiceStart = false
            pendingDictationAfterLiveVoice = null
            AndroidLiveVoiceRuntime.stop(this)
        } else if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startLiveVoiceAfterDictationStops()
        } else {
            pendingLiveVoiceStart = true
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startLiveVoiceAfterDictationStops() {
        val phase = HansDictationRuntime.snapshotUi().phase
        if (VoiceInputTransitionPolicy.dictationOwnsVoice(phase)) {
            pendingLiveVoiceStart = true
            if (VoiceInputTransitionPolicy.dictationCanStopCapture(phase)) {
                HansDictationService.stop(this)
            } else showDictationProcessingNotice()
        } else {
            startLiveVoiceNow()
        }
    }

    private fun startLiveVoiceNow() {
        when (AndroidLiveVoiceRuntime.start(this)) {
            LiveVoiceServiceCommandResult.REQUESTED -> Unit
            LiveVoiceServiceCommandResult.NETWORK_UNAVAILABLE -> showConnectionFailure(
                tr(R.string.integration_live_voice_requires_an_internet_connection_check_your_c_b342afc),
            )
            LiveVoiceServiceCommandResult.MICROPHONE_PERMISSION_MISSING -> {
                pendingLiveVoiceStart = true
                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            LiveVoiceServiceCommandResult.NOT_CONFIGURED ->
                showShortMessage(tr(R.string.integration_live_voice_is_still_being_prepared_5485290))
            LiveVoiceServiceCommandResult.AUDIO_OUTPUT_NOT_STOPPED,
            LiveVoiceServiceCommandResult.START_NOT_ALLOWED,
            LiveVoiceServiceCommandResult.SECURITY_FAILURE,
            -> showShortMessage(tr(R.string.integration_live_voice_could_not_be_started_2b104f9))
        }
    }

    private fun startActionKeySetup() = startActionKeySetup(
        action = KeySemanticAction.DICTATION,
        mappingId = PRIMARY_DICTATION_MAPPING_ID,
    )

    private fun startModelToggleKeySetup() = startActionKeySetup(
        action = KeySemanticAction.TOGGLE_LUNA_MAX_SOL_ULTRA,
        mappingId = MODEL_TOGGLE_MAPPING_ID,
    )

    private fun startActionKeySetup(
        action: KeySemanticAction,
        mappingId: String,
    ) {
        if (activeActionKeyCapture != null) return
        foregroundModelKeyRouter.clearActivePresses()
        val now = SystemClock.uptimeMillis()
        val sessionId = CaptureSessionId(++nextActionKeyCaptureId)
        actionKeyCapture.begin(
            ActionKeyCaptureRequest(
                sessionId = sessionId,
                mappingId = mappingId,
                action = action,
                trigger = ActionKeyTrigger.PRESS,
                startedAtMillis = now,
                expiresAtMillis = now + ACTION_KEY_CAPTURE_WINDOW_MILLIS,
            ),
        )
        activeActionKeyCapture = sessionId
        actionKeyCaptureLease = runCatching {
            GlobalActionKeyCaptureGate.acquire(sessionId) { deliveredSessionId, event ->
                runOnUiThread {
                    if (activeActionKeyCapture == deliveredSessionId) {
                        acceptActionKeyCapture(deliveredSessionId, event)
                    }
                }
            }
        }.getOrElse {
            actionKeyCapture.cancel(sessionId)
            activeActionKeyCapture = null
            localUi = localUi.copy(
                actionKeyCapturing = false,
                capturingModelToggleKey = false,
                actionKeyNotice = tr(R.string.integration_another_key_setup_is_still_active_6ddace5),
                revision = localUi.revision + 1,
            )
            return
        }
        actionKeyCaptureTimeout?.let(actionKeyCaptureHandler::removeCallbacks)
        actionKeyCaptureTimeout = Runnable {
            val result = actionKeyCapture.onTimeout(sessionId, SystemClock.uptimeMillis())
            val state = (result as? CaptureEventResult.StateChanged)?.state
            if (state is ActionKeyCaptureState.TimedOut) {
                activeActionKeyCapture = null
                actionKeyCaptureTimeout = null
                closeActionKeyCaptureLease(sessionId)
                localUi = localUi.copy(
                    actionKeyCapturing = false,
                    capturingModelToggleKey = false,
                    actionKeyNotice = tr(R.string.integration_setup_timed_out_63b25b0),
                    revision = localUi.revision + 1,
                )
                acceptActiveSetupKeyCapture(
                    success = false,
                    blocked = false,
                    detailCode = "key_capture_timed_out",
                )
            }
        }.also { timeout ->
            actionKeyCaptureHandler.postDelayed(timeout, ACTION_KEY_CAPTURE_WINDOW_MILLIS)
        }
        localUi = localUi.copy(
            actionKeyCapturing = action == KeySemanticAction.DICTATION,
            capturingModelToggleKey = action == KeySemanticAction.TOGGLE_LUNA_MAX_SOL_ULTRA,
            actionKeyNotice = tr(R.string.integration_waiting_for_a_key_1d70d03),
            revision = localUi.revision + 1,
        )
    }

    private fun cancelActionKeySetup() {
        activeActionKeyCapture?.let(actionKeyCapture::cancel)
        activeActionKeyCapture = null
        actionKeyCaptureTimeout?.let(actionKeyCaptureHandler::removeCallbacks)
        actionKeyCaptureTimeout = null
        closeActionKeyCaptureLease()
        localUi = localUi.copy(
            actionKeyCapturing = false,
            capturingModelToggleKey = false,
            actionKeyNotice = tr(R.string.integration_setup_cancelled_4d9b02b),
            revision = localUi.revision + 1,
        )
        acceptActiveSetupKeyCapture(
            success = false,
            blocked = false,
            detailCode = "key_capture_cancelled",
        )
    }

    private fun acceptActiveSetupKeyCapture(
        success: Boolean,
        blocked: Boolean,
        detailCode: String,
    ) {
        val token = activeSetupKeyCaptureToken ?: return
        setupRuntime.acceptKeyCapture(token, success, blocked, detailCode)
        activeSetupKeyCaptureToken = null
    }

    private fun clearActionKey() {
        actionKeyStore.remove(PRIMARY_DICTATION_MAPPING_ID)
        val mappings = actionKeyStore.read()
        refreshMp01VendorActionConflict(mappings)
        localUi = localUi.copy(
            actionKeyCapturing = false,
            actionKeyNotice = tr(R.string.integration_action_key_removed_7951221),
            revision = localUi.revision + 1,
        )
    }

    private fun clearModelToggleKey() {
        actionKeyStore.remove(MODEL_TOGGLE_MAPPING_ID)
        val mappings = actionKeyStore.read()
        refreshMp01VendorActionConflict(mappings)
        localUi = localUi.copy(
            capturingModelToggleKey = false,
            actionKeyNotice = tr(R.string.integration_toggle_key_removed_78b38bd),
            revision = localUi.revision + 1,
        )
    }

    private fun selectDictationKeyTrigger(trigger: ActionKeyTrigger) {
        // Old persisted enum values remain decodable, but cannot reactivate hold-to-stop.
        if (trigger != ActionKeyTrigger.PRESS) return
        if (activeActionKeyCapture != null) cancelActionKeySetup()
        val before = actionKeyStore.read()
        val existing = before.mappings.firstOrNull {
            it.mappingId == PRIMARY_DICTATION_MAPPING_ID
        }
        val updatedMapping = existing?.copy(trigger = trigger)
        val persisted = runCatching {
            if (updatedMapping != null) actionKeyStore.save(updatedMapping)
            settingsStore.saveInputControls(
                dictationKeyTrigger = trigger,
                cameraHoldToTalkEnabled = settings.cameraHoldToTalkEnabled,
            )
        }
        persisted.onSuccess { updatedSettings ->
            settings = updatedSettings
            refreshMp01VendorActionConflict(actionKeyStore.read())
            localUi = localUi.copy(
                actionKeyNotice = when (trigger) {
                    ActionKeyTrigger.PRESS ->
                        tr(R.string.integration_toggle_selected_press_once_to_start_then_again_to_send_a048224)
                    ActionKeyTrigger.HOLD_TO_TALK ->
                        tr(R.string.integration_hold_selected_the_stock_mp01_system_may_block_this_gest_d2a0f36)
                },
                revision = localUi.revision + 1,
            )
        }.onFailure {
            if (existing != null) {
                runCatching { actionKeyStore.save(existing) }
            }
            refreshMp01VendorActionConflict(actionKeyStore.read())
            showShortMessage(tr(R.string.integration_the_dictation_key_gesture_could_not_be_saved_41cece3))
        }
    }

    private fun setCameraHoldToTalkEnabled(enabled: Boolean) {
        if (!enabled) {
            executeSoftwareHoldEffects(softwareHoldToTalk.onFocusLost())
            pendingSoftwareHoldPermission = null
        }
        settings = runCatching {
            settingsStore.saveInputControls(
                dictationKeyTrigger = settings.dictationKeyTrigger,
                cameraHoldToTalkEnabled = enabled,
            )
        }.getOrElse {
            showShortMessage(tr(R.string.integration_the_camera_dictation_gesture_could_not_be_saved_34ca0f5))
            settings
        }
    }

    private fun selectDisplayMotionMode(mode: DisplayMotionMode) {
        displayMotionMode = runCatching {
            displayMotionStore.save(mode)
        }.getOrElse {
            showShortMessage(tr(R.string.integration_the_display_setting_could_not_be_saved_bc9c5c5))
            displayMotionMode
        }
    }

    private fun refreshMp01VendorActionConflict(
        mappings: ActionKeyMappingSet,
    ): Mp01VendorActionGateResult {
        val evidence = mp01VendorActionRemediation.probe()
        val voiceMappings = mappings.forTaskVoiceControls()
        var gate = Mp01VendorActionConflictResolver.gate(
            mappings = voiceMappings,
            vendorEvidence = evidence,
            confirmation = mp01VendorActionConfirmation.confirmation(),
        )
        runCatching {
            mp01VendorActionConfirmation.synchronize(
                gate.conflict?.mappings.orEmpty(),
                evidence,
            )
        }
            .onSuccess {
                gate = Mp01VendorActionConflictResolver.gate(
                    mappings = voiceMappings,
                    vendorEvidence = evidence,
                    confirmation = mp01VendorActionConfirmation.confirmation(),
                )
            }
        val conflict = gate.conflict
        replaceActionKeyMappings(gate.effectiveMappings)
        currentMp01VendorConflictMappingSetFingerprint = conflict?.mappings?.let(
            Mp01ActionMappingSetFingerprint::of,
        )
        localUi = localUi.copy(
            actionKeyAssigned = mappings.hasAction(KeySemanticAction.DICTATION),
            actionKeyConfigured = gate.effectiveMappings.hasAction(KeySemanticAction.DICTATION),
            modelToggleKeyConfigured = gate.effectiveMappings.hasAction(
                KeySemanticAction.TOGGLE_LUNA_MAX_SOL_ULTRA,
            ),
            mp01VendorActionConflict = Mp01VendorActionConflictUiState(
                detected = conflict != null,
                settingsActivityAvailable = conflict?.settingsActivityLaunchable == true,
                replacementConfirmed = conflict?.replacementConfirmed == true,
                kind = when (conflict?.kind) {
                    Mp01VendorActionConflictKind.STOCK_SYSTEM_POLICY ->
                        Mp01VendorActionConflictUiKind.STOCK_SYSTEM_POLICY
                    Mp01VendorActionConflictKind.LEGACY_ACCESSIBILITY,
                    null,
                    -> Mp01VendorActionConflictUiKind.LEGACY_ACCESSIBILITY
                },
                stockPressToggleCompatible =
                    conflict?.stockPressToggleCompatible == true,
                shortcutKinds = conflict?.mappings.orEmpty().mapTo(linkedSetOf()) { mapping ->
                    when (mapping.action) {
                        KeySemanticAction.DICTATION -> Mp01VendorShortcutUiKind.DICTATION
                        KeySemanticAction.TOGGLE_LUNA_MAX_SOL_ULTRA ->
                            Mp01VendorShortcutUiKind.MODEL_TOGGLE
                    }
                },
            ),
            revision = localUi.revision + 1,
        )
        return gate
    }

    private fun openMp01VendorSettings() {
        if (!mp01VendorActionRemediation.openSettings()) {
            showShortMessage(tr(R.string.integration_the_original_minimal_key_setting_is_unavailable_46bb2b0))
        }
    }

    private fun confirmMp01VendorActionCleared() {
        val mappings = actionKeyStore.read()
        val evidence = mp01VendorActionRemediation.probe()
        val conflict = Mp01VendorActionConflictResolver.resolve(
            mappings = mappings.forTaskVoiceControls(),
            vendorEvidence = evidence,
            confirmation = mp01VendorActionConfirmation.confirmation(),
        )
        val conflictFingerprint = conflict?.mappings?.let(Mp01ActionMappingSetFingerprint::of)
        if (
            conflict == null ||
            conflictFingerprint != currentMp01VendorConflictMappingSetFingerprint
        ) {
            showShortMessage(tr(R.string.integration_no_minimal_key_conflict_was_detected_for_this_action_ke_39c9b8b))
            refreshMp01VendorActionConflict(mappings)
            return
        }
        if (!conflict.userConfirmationAllowed) {
            showShortMessage(
                tr(R.string.integration_this_stock_system_key_mapping_cannot_be_confirmed_use_a_1ad064f),
            )
            refreshMp01VendorActionConflict(mappings)
            return
        }
        runCatching { mp01VendorActionConfirmation.confirm(conflict.mappings, evidence) }
            .onSuccess {
                refreshMp01VendorActionConflict(mappings)
                showShortMessage(tr(R.string.integration_the_minimal_key_mapping_is_confirmed_as_disabled_9e6f42a))
            }
            .onFailure {
                showShortMessage(tr(R.string.integration_the_confirmation_could_not_be_saved_fea7881))
            }
    }

    private fun acceptActionKeyCapture(
        sessionId: CaptureSessionId,
        event: ai.hans.standard.phone.keys.ObservableAndroidKeyEvent,
    ) {
        val result = actionKeyCapture.onDeliveredEvent(sessionId, event)
        val state = (result as? CaptureEventResult.StateChanged)?.state ?: return
        if (state !is ActionKeyCaptureState.Capturing) {
            actionKeyCaptureTimeout?.let(actionKeyCaptureHandler::removeCallbacks)
            actionKeyCaptureTimeout = null
        }
        when (state) {
            is ActionKeyCaptureState.Capturing -> Unit
            is ActionKeyCaptureState.Completed -> {
                val mapping = state.mapping
                runCatching {
                    actionKeyStore.save(mapping)
                    val mappings = actionKeyStore.read()
                    refreshMp01VendorActionConflict(mappings)
                }.onSuccess { gate ->
                    val blockedByVendor = gate.conflict?.let { conflict ->
                        conflict.mappings.any { it.mappingId == mapping.mappingId } &&
                            conflict.replacementRequired
                    } == true
                    localUi = localUi.copy(
                        actionKeyCapturing = false,
                        capturingModelToggleKey = false,
                        actionKeyNotice = when {
                            blockedByVendor &&
                                gate.conflict?.kind ==
                                Mp01VendorActionConflictKind.STOCK_SYSTEM_POLICY ->
                                tr(R.string.integration_key_saved_but_holding_is_reserved_on_stock_mp01_configu_84877bb)
                            blockedByVendor ->
                                tr(R.string.integration_key_saved_but_blocked_until_the_minimal_mapping_is_conf_94421a6)
                            gate.conflict?.stockPressToggleCompatible == true &&
                                mapping.action == KeySemanticAction.DICTATION ->
                                tr(R.string.integration_mp01_toggle_configured_press_briefly_to_start_then_pres_cc7d6ac)
                            mapping.action == KeySemanticAction.DICTATION ->
                                tr(R.string.integration_action_key_configured_b04a61c)
                            else -> tr(R.string.integration_toggle_key_configured_34f7cb4)
                        },
                        revision = localUi.revision + 1,
                    )
                    if (mapping.action == KeySemanticAction.DICTATION) {
                        acceptActiveSetupKeyCapture(
                            success = !blockedByVendor,
                            blocked = blockedByVendor,
                            detailCode = if (blockedByVendor) {
                                "key_mapping_blocked_by_vendor"
                            } else {
                                "key_mapping_captured"
                            },
                        )
                    }
                }.onFailure {
                    localUi = localUi.copy(
                        actionKeyCapturing = false,
                        capturingModelToggleKey = false,
                        actionKeyNotice = tr(R.string.integration_the_action_key_could_not_be_saved_6da3714),
                        revision = localUi.revision + 1,
                    )
                    if (mapping.action == KeySemanticAction.DICTATION) {
                        acceptActiveSetupKeyCapture(
                            success = false,
                            blocked = false,
                            detailCode = "key_mapping_persistence_failed",
                        )
                    }
                }
                activeActionKeyCapture = null
                closeActionKeyCaptureLease(sessionId)
            }
            is ActionKeyCaptureState.Rejected -> {
                activeActionKeyCapture = null
                closeActionKeyCaptureLease(sessionId)
                localUi = localUi.copy(
                    actionKeyCapturing = false,
                    capturingModelToggleKey = false,
                    actionKeyNotice = tr(R.string.integration_hans_cannot_use_this_key_as_an_action_key_63c6efc),
                    revision = localUi.revision + 1,
                )
                acceptActiveSetupKeyCapture(
                    success = false,
                    blocked = false,
                    detailCode = "key_capture_rejected",
                )
            }
            is ActionKeyCaptureState.TimedOut -> {
                activeActionKeyCapture = null
                closeActionKeyCaptureLease(sessionId)
                localUi = localUi.copy(
                    actionKeyCapturing = false,
                    capturingModelToggleKey = false,
                    actionKeyNotice = tr(R.string.integration_setup_timed_out_63b25b0),
                    revision = localUi.revision + 1,
                )
                acceptActiveSetupKeyCapture(
                    success = false,
                    blocked = false,
                    detailCode = "key_capture_timed_out",
                )
            }
            is ActionKeyCaptureState.Cancelled,
            ActionKeyCaptureState.Idle,
            -> Unit
        }
    }

    private fun closeActionKeyCaptureLease(sessionId: CaptureSessionId? = null) {
        val lease = actionKeyCaptureLease ?: return
        if (sessionId != null && lease.sessionId != sessionId) return
        actionKeyCaptureLease = null
        lease.close()
    }

    private fun acceptGlobalActionKeyReceipt(receipt: GlobalActionKeyAccessibilityReceipt) {
        if (receipt.mappingId != PRIMARY_DICTATION_MAPPING_ID) return
        activeSetupDictationTestToken
            ?.takeIf { it.step == HansSetupStep.HARDWARE_LIVE_TEST }
            ?.let { setupRuntime.acceptAccessibilityHardwareKeyCommand(it, receipt.command) }
    }

    private fun executeActionKeyCommand(command: ActionKeyCommand) {
        if (!handleNonStartingVoiceCommand(command)) requestMicrophoneThen(ActionKeyCommand.StartDictation)
    }

    private fun replaceActionKeyMappings(mappings: ActionKeyMappingSet) {
        foregroundModelKeyRouter.replaceMappings(mappings)
        val nonModelMappings = ActionKeyMappingSet.of(mappings.mappings.filter {
            it.action != KeySemanticAction.TOGGLE_LUNA_MAX_SOL_ULTRA
        })
        actionKeyDispatcher.replaceMappings(nonModelMappings).forEach { cleanup ->
            executeActionKeyCommand(cleanup.command)
        }
    }

    private fun onCameraGestureDown(nowMillis: Long): SoftwareHoldGestureId? {
        if (!settings.cameraHoldToTalkEnabled) return null
        return softwareHoldToTalk.onPointerDown(nowMillis)
    }

    private fun onCameraGestureLongPress(
        gestureId: SoftwareHoldGestureId,
        nowMillis: Long,
    ) {
        if (!settings.cameraHoldToTalkEnabled) {
            executeSoftwareHoldEffects(
                softwareHoldToTalk.onPointerCancel(gestureId, nowMillis),
            )
            return
        }
        executeSoftwareHoldEffects(
            softwareHoldToTalk.onLongPress(
                gestureId = gestureId,
                nowMillis = nowMillis,
                recordingAlreadyActive = isDictationRecordingActive(),
                microphonePermissionGranted = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED,
            ),
        )
    }

    private fun onCameraGestureUp(
        gestureId: SoftwareHoldGestureId,
        nowMillis: Long,
    ) {
        executeSoftwareHoldEffects(softwareHoldToTalk.onPointerUp(gestureId, nowMillis))
    }

    private fun onCameraGestureCancel(
        gestureId: SoftwareHoldGestureId,
        nowMillis: Long,
    ) {
        executeSoftwareHoldEffects(softwareHoldToTalk.onPointerCancel(gestureId, nowMillis))
    }

    private fun executeSoftwareHoldEffects(effects: List<SoftwareHoldGestureEffect>) {
        effects.forEach { effect ->
            when (effect) {
                SoftwareHoldGestureEffect.CapturePhoto -> showCameraCaptureChoice()
                is SoftwareHoldGestureEffect.RequestMicrophonePermission -> {
                    // Never stack this gesture behind another permission-owned command.
                    if (pendingMicrophoneCommand != null || pendingLiveVoiceStart) {
                        softwareHoldToTalk.onPointerCancel(
                            effect.gestureId,
                            SystemClock.uptimeMillis(),
                        )
                    } else {
                        pendingSoftwareHoldPermission = effect.gestureId
                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                is SoftwareHoldGestureEffect.StartOwnedDictation -> {
                    if (isDictationRecordingActive() || isLiveVoiceActive()) {
                        // The gesture never takes over another audio owner.
                        softwareHoldToTalk.onOwnedRecordingEnded()
                    } else {
                        activeSetupDictationTestToken
                            ?.takeIf { it.step == HansSetupStep.CAMERA_HOLD_LIVE_TEST }
                            ?.let { setupRuntime.acceptCameraDictation(it, started = true) }
                        HansDictationService.start(this)
                    }
                }
                is SoftwareHoldGestureEffect.StopOwnedDictation -> {
                    activeSetupDictationTestToken
                        ?.takeIf { it.step == HansSetupStep.CAMERA_HOLD_LIVE_TEST }
                        ?.let { setupRuntime.acceptCameraDictation(it, started = false) }
                    HansDictationService.stop(this)
                }
            }
        }
    }

    private fun isDictationRecordingActive(): Boolean =
        VoiceInputTransitionPolicy.dictationOwnsVoice(HansDictationRuntime.snapshotUi().phase)

    private fun requestMicrophoneThen(command: ActionKeyCommand) {
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            executeGrantedDictationCommand(command)
        } else {
            pendingMicrophoneCommand = command
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun executeGrantedDictationCommand(command: ActionKeyCommand) {
        if (handleNonStartingVoiceCommand(command)) return
        pendingLiveVoiceStart = false
        if (isLiveVoiceActive()) {
            // Store an explicit START, never a deferred toggle which could stop a newer owner.
            pendingDictationAfterLiveVoice = ActionKeyCommand.StartDictation
            AndroidLiveVoiceRuntime.stop(this)
            drainPendingDictationAfterLiveVoice()
        } else {
            HansDictationService.start(this)
        }
    }

    private fun drainPendingDictationAfterLiveVoice() {
        if (isLiveVoiceActive()) return
        val command = pendingDictationAfterLiveVoice ?: return
        pendingDictationAfterLiveVoice = null
        executeGrantedDictationCommand(command)
    }

    /** Returns false only for a new start that still needs the permission/effective-owner gates. */
    private fun handleNonStartingVoiceCommand(command: ActionKeyCommand): Boolean {
        when (VoiceInputTransitionPolicy.command(command, HansDictationRuntime.snapshotUi().phase,
            pendingDictationAfterLiveVoice != null || pendingMicrophoneCommand != null,
            ai.hans.standard.voice.android.CodexDictationIntegration.continuousDictation)) {
            VoiceInputCommandAction.REQUEST_START -> return false
            VoiceInputCommandAction.STOP_CAPTURE -> HansDictationService.stop(this)
            VoiceInputCommandAction.TOGGLE_INPUT_MUTED -> {
                if (!AndroidLiveVoiceRuntime.toggleDictationInputMuted()) {
                    showShortMessage(tr(R.string.integration_the_microphone_could_not_be_switched_c024fee))
                }
            }
            VoiceInputCommandAction.SHOW_PROCESSING -> showDictationProcessingNotice()
            VoiceInputCommandAction.CANCEL_PENDING_START -> {
                pendingDictationAfterLiveVoice = null
                pendingMicrophoneCommand = null
            }
            VoiceInputCommandAction.TOGGLE_MODEL -> toggleModelPreset()
            VoiceInputCommandAction.NONE -> Unit
        }
        return true
    }

    private fun showDictationProcessingNotice() {
        showShortMessage(tr(R.string.integration_dictation_still_processing))
    }

    private fun isLiveVoiceActive(): Boolean = AndroidLiveVoiceRuntime.entryPoint() ==
        ai.hans.standard.voice.realtime.LiveVoiceEntryPoint.PHONE && VoiceInputTransitionPolicy.liveOwnsVoice(
        AndroidLiveVoiceRuntime.snapshot().phase,
        AndroidLiveVoiceRuntime.isCaptureRequestedOrActive(),
    )

    private fun resolveSelection(
        requestedModel: String,
        requestedEffort: String,
        requestedServiceTier: String?,
    ): DispatchSelection? = runCatching {
        resolveDispatchSelection(
            models = clientSnapshot?.models.orEmpty(),
            requestedModel = requestedModel,
            requestedEffort = ReasoningEffort.of(requestedEffort),
            requestedServiceTier = requestedServiceTier,
        )
    }.getOrNull()

    private fun selectionForNextDispatch(): DispatchSelection? {
        val snapshot = sessionHost.snapshot() ?: clientSnapshot
        val confirmed = snapshot?.confirmedSelection ?: DispatchSelection.from(settings)
        return resolveSelection(
            requestedModel = confirmed.model,
            requestedEffort = confirmed.effort.wireValue,
            requestedServiceTier = confirmed.serviceTier,
        )
    }

    private fun selectionForSettingsChange(): DispatchSelection? {
        val snapshot = sessionHost.snapshot() ?: clientSnapshot
        return snapshot?.pendingSettingsSelection ?: selectionForNextDispatch()
    }

    private fun requestSelection(
        requestedModel: String,
        requestedEffort: String,
        requestedServiceTier: String?,
        requireExactEffort: Boolean = false,
        requireExactServiceTier: Boolean = false,
    ): Boolean {
        val resolved = resolveSelection(
            requestedModel,
            requestedEffort,
            requestedServiceTier,
        ) ?: return false
        if (
            resolved.model != requestedModel ||
            (requireExactEffort && resolved.effort.wireValue != requestedEffort) ||
            (requireExactServiceTier && resolved.serviceTier != requestedServiceTier)
        ) {
            return false
        }
        // The process host owns the request and its confirmation, so Activity recreation
        // cannot discard a choice and UI never substitutes a local draft for server proof.
        return sessionHost.updateSelection(resolved)
    }

    private fun toggleModelPreset() {
        val currentSelection = selectionForSettingsChange() ?: run {
            showShortMessage(tr(R.string.integration_available_models_have_not_loaded_yet_eb9f090))
            return
        }
        val current = if (
            currentSelection.model == HansModelPreset.ASTRA_ULTRA.model &&
            currentSelection.effort.wireValue == HansModelPreset.ASTRA_ULTRA.effort
        ) {
            HansModelPreset.ASTRA_ULTRA
        } else {
            HansModelPreset.LUNA_MAX
        }
        val next = HansModelPresetToggle.next(current)
        val target = ai.hans.standard.ui.resolveModelShortcutPreset(next, clientSnapshot?.models.orEmpty())
        if (target == null || !requestSelection(target.model, target.effort, currentSelection.serviceTier,
                requireExactEffort = true)) {
            showShortMessage(tr(R.string.integration_1_is_currently_unavailable_3baca69, next.name.replace('_', ' ')))
            return
        }
        showShortMessage(tr(R.string.integration_model_change_requested_3ddd47a))
    }

    /**
     * One local-only read triggered by opening the dedicated group or by an operation result.
     * Neither this method nor the UI projector probes, starts or publishes a remote worker.
     */
    private fun refreshRemoteWorkerSettings(notice: String = "") {
        val hansApplication = application as HansApplication
        val snapshot = runCatching {
            var runtime = hansApplication.passiveRemoteWorkerRuntimeState()
            var editable = hansApplication.passiveRemoteWorkerEditableConfiguration()
            repeat(2) {
                if (runtime.revision != editable.revision) {
                    runtime = hansApplication.passiveRemoteWorkerRuntimeState()
                    editable = hansApplication.passiveRemoteWorkerEditableConfiguration()
                }
            }
            require(runtime.revision == editable.revision) {
                "Remote worker local projections changed while being read"
            }
            RemoteWorkerSettingsUiState(
                revision = editable.revision,
                loaded = true,
                configured = editable.configured,
                requestedEnabled = runtime.requestedEnabled,
                effective = runtime.effective,
                status = runtime.status.toSettingsUiStatus(),
                workerId = editable.workerId.orEmpty(),
                httpsOrigin = editable.endpoint.orEmpty(),
                serverSpkiSha256 = editable.serverSpkiSha256.orEmpty(),
                approvedAdapters = editable.approvedAdapters.map { adapter ->
                    RemoteWorkerAdapterUiDraft(
                        id = adapter.id.value,
                        version = adapter.version,
                    )
                },
                operationInProgress = false,
                notice = notice,
            )
        }.getOrElse {
            RemoteWorkerSettingsUiState(
                loaded = true,
                status = RemoteWorkerSettingsUiStatus.STORAGE_CORRUPT,
                notice = notice.ifBlank {
                    tr(R.string.integration_the_local_configuration_could_not_be_read_safely_35294cb)
                },
            )
        }
        localUi = localUi.copy(
            remoteWorker = snapshot,
            revision = localUi.revision + 1,
        )
    }

    private fun saveRemoteWorkerConfiguration(draft: RemoteWorkerConfigurationUiDraft) {
        val current = localUi.remoteWorker
        if (!current.loaded || current.operationInProgress || !draft.canSave) return
        val replacement = runCatching {
            RemoteWorkerConfiguration(
                identity = remoteWorkerConnectionIdentity(
                    workerId = draft.workerId.trim(),
                    endpoint = draft.httpsOrigin.trim(),
                    serverSpkiSha256 = draft.serverSpkiSha256.trim().lowercase(),
                ),
                approvedAdapters = draft.adapters
                    .filterNot { it.id.isBlank() && it.version.isBlank() }
                    .map { adapter ->
                        RemoteWorkAdapterApproval(
                            id = RemoteWorkAdapterId(adapter.id.trim()),
                            version = adapter.version.trim(),
                        )
                    },
                enabled = draft.requestedEnabled,
            )
        }.getOrElse {
            refreshRemoteWorkerSettings(
                tr(R.string.integration_the_configuration_is_invalid_check_the_address_pin_adap_68323e7),
            )
            return
        }
        localUi = localUi.copy(
            remoteWorker = current.copy(operationInProgress = true, notice = ""),
            revision = localUi.revision + 1,
        )
        try {
            remoteWorkerPersistenceExecutor.execute {
                val result = runCatching {
                    (application as HansApplication).saveRemoteWorkerConfiguration(
                        expectedRevision = current.revision,
                        replacement = replacement,
                    )
                }.getOrDefault(RemoteWorkerRuntimeUpdateResult.PersistenceFailed)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        refreshRemoteWorkerAfterUpdate(result, RemoteWorkerUpdateAction.SAVE)
                    }
                }
            }
        } catch (_: RuntimeException) {
            refreshRemoteWorkerAfterUpdate(
                RemoteWorkerRuntimeUpdateResult.PersistenceFailed,
                RemoteWorkerUpdateAction.SAVE,
            )
        }
    }

    private fun activateRemoteWorkerExplicitly() {
        val current = localUi.remoteWorker
        if (
            !current.loaded || !current.configured || !current.requestedEnabled ||
            current.effective || current.operationInProgress
        ) {
            return
        }
        localUi = localUi.copy(
            remoteWorker = current.copy(
                operationInProgress = true,
                notice = tr(R.string.integration_checking_the_connection_7121601),
            ),
            revision = localUi.revision + 1,
        )
        (application as HansApplication).activateRemoteWorkerExplicitly(
            expectedRevision = current.revision,
        ) { result ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    refreshRemoteWorkerAfterUpdate(result, RemoteWorkerUpdateAction.ACTIVATE)
                }
            }
        }
    }

    private fun refreshRemoteWorkerAfterUpdate(
        result: RemoteWorkerRuntimeUpdateResult,
        action: RemoteWorkerUpdateAction,
    ) {
        val notice = when (result) {
            is RemoteWorkerRuntimeUpdateResult.Saved -> when (action) {
                RemoteWorkerUpdateAction.SAVE -> if (result.state.requestedEnabled) {
                    tr(R.string.integration_saved_the_connection_is_not_active_yet_verify_and_enabl_0d6b77e)
                } else {
                    tr(R.string.integration_saved_remote_work_is_off_7a4ce34)
                }
                RemoteWorkerUpdateAction.ACTIVATE -> if (result.state.effective) {
                    tr(R.string.integration_connection_verified_the_work_computer_is_now_active_for_28f46ef)
                } else {
                    tr(R.string.integration_the_connection_was_not_confirmed_as_active_0f3e6e3)
                }
            }
            RemoteWorkerRuntimeUpdateResult.RevisionMismatch ->
                tr(R.string.integration_the_configuration_changed_in_the_meantime_the_current_v_4b814b0)
            RemoteWorkerRuntimeUpdateResult.Corrupt ->
                tr(R.string.integration_the_local_configuration_could_not_be_read_or_changed_sa_256dfbf)
            RemoteWorkerRuntimeUpdateResult.PersistenceFailed ->
                tr(R.string.integration_the_configuration_could_not_be_saved_safely_the_previou_47df4f1)
            RemoteWorkerRuntimeUpdateResult.ActivationFailed ->
                tr(R.string.integration_the_connection_could_not_be_verified_nothing_was_enable_e4ca838)
            RemoteWorkerRuntimeUpdateResult.PublicationFailed ->
                tr(R.string.integration_codex_could_not_activate_the_work_computer_the_confirme_5a8e9f2)
        }
        refreshRemoteWorkerSettings(notice)
    }

    private fun RemoteWorkerRuntimeStatus.toSettingsUiStatus(): RemoteWorkerSettingsUiStatus =
        when (this) {
            RemoteWorkerRuntimeStatus.UNCONFIGURED -> RemoteWorkerSettingsUiStatus.UNCONFIGURED
            RemoteWorkerRuntimeStatus.DISABLED -> RemoteWorkerSettingsUiStatus.DISABLED
            RemoteWorkerRuntimeStatus.NEEDS_ACTIVATION ->
                RemoteWorkerSettingsUiStatus.NEEDS_ACTIVATION
            RemoteWorkerRuntimeStatus.EFFECTIVE -> RemoteWorkerSettingsUiStatus.EFFECTIVE
            RemoteWorkerRuntimeStatus.STORAGE_CORRUPT ->
                RemoteWorkerSettingsUiStatus.STORAGE_CORRUPT
        }

    private enum class RemoteWorkerUpdateAction {
        SAVE,
        ACTIVATE,
    }

    private fun callbacks(): HansUiCallbacks = HansUiCallbacks(
        authGate = AuthGateUiCallbacks(
            onStartChatGptLogin = {
                if (!showOfflineSubmissionNotice(tr(R.string.integration_signing_in_to_chatgpt_requires_an_internet_connection_b8bf282)) && !sessionHost.loginWithDeviceCode()) {
                    showShortMessage(tr(R.string.integration_the_codex_runtime_is_not_ready_yet_91f881e))
                }
            },
            onOpenVerificationPage = ::openVerificationPage,
            onCopyUserCode = ::copyUserCode,
            onCancel = { sessionHost.restart() },
            onStartNewConversation = {
                if (!sessionHost.startNewConversationAfterRecoveryFailure()) {
                    showShortMessage(tr(R.string.recovery_new_conversation_unavailable))
                }
            },
            onRetry = {
                if (!showOfflineSubmissionNotice(tr(R.string.integration_reconnecting_requires_an_internet_connection_25d6af1))) {
                    AuthGateRecoveryPolicy.retry(
                        client = sessionHost.snapshot(),
                        loginWithDeviceCode = sessionHost::loginWithDeviceCode,
                        restartSession = sessionHost::restart,
                    )
                }
            },
        ),
        chat = ChatUiCallbacks(
            onComposerChanged = { text ->
                if (pendingComposerDispatch == null) {
                    localUi = localUi.copy(text = text, revision = localUi.revision + 1)
                }
            },
            onSend = ::sendComposer,
            onInterruptWork = sessionHost::interrupt,
            readComposerDraft = {
                ComposerDraftSnapshot(
                    text = localUi.text,
                    dispatchPending = pendingComposerDispatch != null,
                )
            },
            onChooseMedia = ::showCameraCaptureChoice,
            onRemoveAttachment = ::removeAttachment,
            onOpenApps = {
                localUi = localUi.copy(
                    requestedDestination = HansDestination.APPS,
                    appQuery = "",
                    revision = localUi.revision + 1,
                )
                refreshPrivateSpaceState()
                refreshAppDrawer()
            },
            onOpenPlugins = {
                sessionHost.refreshPlugins(forceRefetch = true)
                localUi = localUi.copy(
                    requestedDestination = HansDestination.PLUGINS,
                    selectedPluginHandle = null,
                    revision = localUi.revision + 1,
                )
            },
            onOpenAutomations = {
                localUi = localUi.copy(
                    requestedDestination = HansDestination.AUTOMATIONS,
                    revision = localUi.revision + 1,
                )
                refreshAutomations()
            },
            onOpenSettings = {
                localUi = localUi.copy(
                    requestedDestination = HansDestination.SETTINGS,
                    revision = localUi.revision + 1,
                )
                refreshPrivateSpaceState()
                refreshNotificationFactArchiveStatus()
            },
            onOpenWorkbench = {
                localUi = localUi.copy(
                    requestedDestination = HansDestination.WORKBENCH,
                    revision = localUi.revision + 1,
                )
                refreshWorkbench()
            },
            onToggleLiveVoice = ::toggleLiveVoice,
            onToggleDictation = { executeActionKeyCommand(ActionKeyCommand.ToggleDictation) },
            onStartLiveVoice = { if (!isLiveVoiceActive()) toggleLiveVoice() },
            onStopLiveVoice = {
                pendingLiveVoiceStart = false
                AndroidLiveVoiceRuntime.stop(this)
            },
            onLiveVoiceInputMutedChanged = { muted ->
                if (!AndroidLiveVoiceRuntime.setInputMuted(muted)) {
                    showShortMessage(tr(R.string.integration_the_microphone_could_not_be_switched_c024fee))
                }
            },
            onReadAssistantMessageAloud = { text ->
                if (!sessionHost.replayVisibleAssistantMessage(text)) {
                    showShortMessage(
                        if (sessionHost.hasCodexSpeechAccess()) {
                            tr(R.string.integration_read_aloud_is_unavailable_during_recording_or_for_this__d1c49a0)
                        } else {
                            tr(R.string.codex_live_sign_in_required)
                        },
                    )
                }
            },
            onSpeechAudioRouteRequested = { route ->
                (application as HansApplication).speechAudioRoutes.request(route)
            },
            onOpenSpeechFailureHelp = { target -> openFixedOpenAiPage(target.url) },
            onDismissSpeechFailure = { revision -> HansSpeechFailureRuntime.dismiss(revision) },
            onOpenInternetSettings = {
                runCatching {
                    startActivity(Intent(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
                }.onFailure {
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)) }
                        .onFailure { showShortMessage(tr(R.string.integration_internet_settings_could_not_be_opened_12c8e76)) }
                }
            },
            onRetryPendingDictation = { id ->
                if (!showOfflineSubmissionNotice(tr(R.string.integration_not_sent_the_dictated_text_remains_saved_as_a_draft_f41370f)) && !sessionHost.retryPendingDictation(id)) {
                    showConnectionFailure(tr(R.string.integration_dictated_text_has_not_been_sent_yet_the_draft_is_saved__2e01682))
                }
            },
            onDiscardPendingDictation = sessionHost::discardPendingDictation,
        ),
        apps = AppsUiCallbacks(
            onBack = ::returnToChat,
            onQueryChanged = { query ->
                localUi = localUi.copy(
                    appQuery = query.take(MAX_APP_QUERY_CHARACTERS),
                    revision = localUi.revision + 1,
                )
            },
            onRefresh = ::refreshAppDrawer,
            onLaunch = { packageName, profileId ->
                launchFromAppDrawer(packageName, profileId)
            },
            onPrivateSpaceVisibilityChanged = ::setPrivateSpaceContainerVisible,
            onPrivateSpaceLockChanged = ::requestPrivateSpaceLocked,
            onOpenPrivateSpaceSettings = ::openPrivateSpaceSettings,
        ),
        plugins = PluginsUiCallbacks(
            onBack = ::returnToChat,
            onListSelected = { kind ->
                localUi = localUi.copy(
                    selectedPluginList = kind,
                    selectedPluginHandle = null,
                    revision = localUi.revision + 1,
                )
            },
            onOpenInstalled = { rawHandle ->
                val handle = rawHandle.toPluginHandleOrNull()
                if (handle == null || sessionHost.readPlugin(handle) == null) {
                    showShortMessage(tr(R.string.integration_plugin_details_could_not_be_loaded_36460f3))
                } else {
                    localUi = localUi.copy(
                        selectedPluginHandle = handle,
                        revision = localUi.revision + 1,
                    )
                }
            },
            onInstallAvailable = { rawHandle ->
                val handle = rawHandle.toPluginHandleOrNull()
                if (handle == null || sessionHost.installPlugin(handle) == null) {
                    showShortMessage(tr(R.string.integration_the_plugin_could_not_be_installed_yet_a5d9e5d))
                }
            },
            onCloseDetails = {
                localUi = localUi.copy(
                    selectedPluginHandle = null,
                    revision = localUi.revision + 1,
                )
            },
            onOpenPluginLink = ::openPluginLink,
            onPluginSkillEnabledChanged = { rawHandle, skillName, enabled ->
                val handle = rawHandle.toPluginHandleOrNull()
                if (
                    handle == null ||
                    handle != localUi.selectedPluginHandle ||
                    sessionHost.configurePluginSkill(handle, skillName, enabled) == null
                ) {
                    showShortMessage(tr(R.string.integration_the_skill_change_could_not_be_started_cc2d4a1))
                }
            },
            onRefreshMarketplaces = {
                if (sessionHost.refreshMarketplaces() == null) {
                    showShortMessage(tr(R.string.integration_marketplaces_could_not_be_refreshed_831e673))
                }
            },
            onUninstallPlugin = { rawHandle ->
                val handle = rawHandle.toPluginHandleOrNull()
                if (
                    handle == null ||
                    handle != localUi.selectedPluginHandle ||
                    sessionHost.uninstallPlugin(handle) == null
                ) {
                    showShortMessage(tr(R.string.integration_uninstallation_could_not_be_started_3f63d34))
                }
            },
            onConnectRemoteMcp = ::startRemoteMcpOAuth,
            onReviewRemoteMcpPolicy = ::reviewRemoteMcpPolicy,
            onRetryRemoteMcpInstall = { pluginId, serverId ->
                if (sessionHost.retryRemoteMcpPluginInstall(pluginId, serverId) == null) {
                    showShortMessage(
                        tr(R.string.integration_plugin_installation_could_not_be_restarted_fee7df2),
                    )
                }
            },
        ),
        automations = AutomationsUiCallbacks(
            onBack = ::returnToChat,
            onRefresh = ::refreshAutomations,
            onCreateInChat = ::startAutomationCreationInChat,
            onEditInChat = ::startAutomationEditInChat,
            onEnabledChanged = ::setAutomationEnabled,
            onUnattendedChanged = ::setAutomationUnattended,
            onRunNow = ::runAutomationNow,
            onDelete = ::deleteAutomation,
        ),
        settings = SettingsUiCallbacks(
            onOpenWorkbench = {
                localUi = localUi.copy(requestedDestination = HansDestination.WORKBENCH,
                    revision = localUi.revision + 1)
                refreshWorkbench()
            },
            onRefreshNotificationFactArchive = ::refreshNotificationFactArchiveStatus,
            onConfigureWhatsAppAgentChannel = ::configureWhatsAppAgentChannel,
            onDisableWhatsAppAgentChannel = {
                appCatalogExecutor.execute {
                    (application as HansApplication).whatsAppAgentChannel.revoke()
                }
            },
            onConfirmedSttGlossarySaved = ::saveConfirmedSttGlossary,
            onSttLatencyChanged = { delay ->
                val saved = runCatching { sttLatencyPreferenceStore.save(delay) }.getOrNull()
                localUi = localUi.copy(
                    sttLatency = localUi.sttLatency.copy(
                        preferred = saved ?: localUi.sttLatency.preferred,
                        notice = if (saved != null) tr(R.string.integration_saved_for_the_next_recording_the_current_recording_is_u_1fe9734)
                            else tr(R.string.integration_not_saved_the_previous_selection_is_preserved_217bb0a),
                    ),
                    revision = localUi.revision + 1,
                )
            },
            onRemoteWorkerSettingsOpened = { refreshRemoteWorkerSettings() },
            onModelSettingsOpened = { sessionHost.refreshModels() },
            onRemoteControlSettingsOpened = { sessionHost.remoteControlSettingsOpened() },
            onRemoteControlEnable = {
                if (!sessionHost.remoteControlEnable(this@LauncherActivity)) {
                    showShortMessage(tr(R.string.integration_remote_access_could_not_be_started_please_check_its_sta_ae9521a))
                }
            },
            onRemoteControlDisable = { sessionHost.remoteControlDisable() },
            onRemoteControlPair = { sessionHost.remoteControlPair() },
            onRemoteControlRefresh = { sessionHost.remoteControlRefresh() },
            onRemoteControlRefreshClients = { sessionHost.remoteControlRefreshClients() },
            onRemoteControlLoadMoreClients = { sessionHost.remoteControlLoadMoreClients() },
            onRemoteControlRevoke = { clientId -> sessionHost.remoteControlRevoke(clientId) },
            onRemoteControlCheckPairing = { sessionHost.remoteControlCheckPairing() },
            onSaveRemoteWorkerConfiguration = ::saveRemoteWorkerConfiguration,
            onActivateRemoteWorker = ::activateRemoteWorkerExplicitly,
            onUpdateCodex = ::openCodexUpdate,
            onBack = ::returnToChat,
            onStartGettingToKnow = ::startGettingToKnowInterview,
            onStartOrResumeSetup = ::startOrResumeSetupConversation,
            onModelSelected = { model ->
                if (model in HansSettings.SUPPORTED_MODELS) {
                    val current = selectionForSettingsChange()
                    if (
                        current == null ||
                        !requestSelection(model, current.effort.wireValue, current.serviceTier)
                    ) {
                        showShortMessage(tr(R.string.integration_this_model_is_currently_unavailable_3c141fa))
                    }
                }
            },
            onReasoningEffortSelected = { effort ->
                if (effort in HansSettings.SUPPORTED_REASONING_EFFORTS) {
                    val current = selectionForSettingsChange()
                    if (
                        current == null ||
                        !requestSelection(
                            current.model,
                            effort,
                            current.serviceTier,
                            requireExactEffort = true,
                        )
                    ) {
                        showShortMessage(tr(R.string.integration_this_reasoning_effort_is_not_supported_by_the_model_a0d5f7b))
                    }
                }
            },
            onFastModeChanged = { enabled ->
                val current = selectionForSettingsChange()
                val requestedTier = if (enabled) {
                    HansSettings.FAST_SERVICE_TIER
                } else {
                    HansSettings.DEFAULT_SERVICE_TIER
                }
                if (
                    current == null ||
                    !requestSelection(
                        current.model,
                        current.effort.wireValue,
                        requestedTier,
                        requireExactEffort = true,
                        requireExactServiceTier = true,
                    )
                ) {
                    showShortMessage(tr(R.string.integration_fast_mode_is_currently_unavailable_for_this_model_eac0de8))
                }
            },
            onVoiceSelected = { voice -> saveVoice(voice = voice) },
            onLiveVoiceSelected = ::saveLiveVoice,
            onSpeechRateSelected = { rate -> saveVoice(speechRate = rate) },
            onReadAloudModeSelected = { mode ->
                saveVoice(
                    readAloudMode = when (mode) {
                        ReadAloudUiMode.FINAL_ONLY -> ReadAloudMode.FINAL_ONLY
                        ReadAloudUiMode.ALL_MESSAGES ->
                            ReadAloudMode.ALL_VISIBLE_ASSISTANT_MESSAGES
                    },
                )
            },
            onPreviewVoice = {
                if (!sessionHost.previewSpeech()) {
                    showShortMessage(
                        if (sessionHost.hasCodexSpeechAccess()) {
                            tr(R.string.integration_read_aloud_is_unavailable_during_recording_or_for_this__d1c49a0)
                        } else {
                            tr(R.string.codex_live_sign_in_required)
                        },
                    )
                }
            },
            onStartActionKeySetup = ::startActionKeySetup,
            onStartModelToggleKeySetup = ::startModelToggleKeySetup,
            onCancelActionKeySetup = ::cancelActionKeySetup,
            onClearActionKey = ::clearActionKey,
            onClearModelToggleKey = ::clearModelToggleKey,
            onOpenMp01VendorSettings = ::openMp01VendorSettings,
            onConfirmMp01VendorActionCleared = ::confirmMp01VendorActionCleared,
            onCapabilityAccessRequested = ::requestCapabilityAccess,
            onEverydayAccessBundleRequested = {
                showEverydayAccessConfirmation { active ->
                    showShortMessage(
                        if (active) {
                            tr(R.string.integration_everyday_access_is_active_bc0daba)
                        } else {
                            tr(R.string.integration_everyday_access_was_not_enabled_12429a6)
                        },
                    )
                }
            },
            onNotificationLinkMetadataConsentRequested =
                ::showNotificationLinkMetadataConsent,
            onPersistentAndroidConsentRevoked = ::revokePersistentAndroidConsent,
            onAllPersistentAndroidConsentsRevoked = ::revokeAllPersistentAndroidConsents,
            onPrivateSpaceVisibilityChanged = ::setPrivateSpaceContainerVisible,
            onPrivateSpaceLockChanged = ::requestPrivateSpaceLocked,
            onOpenPrivateSpaceSettings = ::openPrivateSpaceSettings,
            onDictationTriggerSelected = ::selectDictationKeyTrigger,
            onDisplayMotionModeSelected = ::selectDisplayMotionMode,
            onConfigureSpeechCredential = { showSpeechCredentialEntry() },
            onRemoveSpeechCredential = ::confirmSpeechCredentialRemoval,
            onOpenOpenAiApiKeyPage = { openFixedOpenAiPage(OPENAI_API_KEYS_URL) },
            onOpenOpenAiBillingPage = { openFixedOpenAiPage(OPENAI_BILLING_URL) },
            onExportBackup = {
                backupCreateDocument.launch("hans-backup${HansBackupDocumentCodec.FILE_EXTENSION}")
            },
            onImportBackup = {
                backupOpenDocument.launch(
                    arrayOf(
                        HansBackupDocumentCodec.MIME_TYPE,
                        "application/json",
                        "application/octet-stream",
                    ),
                )
            },
        ),
        workbench = WorkbenchUiCallbacks(
            onBack = ::returnToChat,
            onRefresh = ::refreshWorkbench,
        ),
    )

    private fun reviewRemoteMcpPolicy(submission: PluginRemoteMcpPolicyReviewSubmission) {
        if (localUi.remoteMcpPolicyReviewPending) return
        localUi = localUi.copy(
            requestedDestination = HansDestination.PLUGINS,
            remoteMcpPolicyReviewPending = true,
            revision = localUi.revision + 1,
        )
        val app = application as HansApplication
        val scheduled = runCatching {
            remoteMcpPolicyExecutor.execute {
                val result = runCatching { app.approveRemoteMcpPolicyReview(submission) }
                    .getOrDefault(PluginRemoteMcpPolicyReviewResult.UNAVAILABLE)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    localUi = localUi.copy(
                        remoteMcpPolicyReviewPending = false,
                        revision = localUi.revision + 1,
                    )
                    showShortMessage(
                        when (result) {
                            PluginRemoteMcpPolicyReviewResult.APPROVED_RETRY_REQUIRED ->
                                tr(R.string.integration_access_settings_saved_restart_installation_separately_n_5f56a80)
                            PluginRemoteMcpPolicyReviewResult.STALE_REVIEW ->
                                tr(R.string.integration_the_tool_list_has_changed_please_review_it_again_ec55705)
                            PluginRemoteMcpPolicyReviewResult.INVALID_DECISIONS ->
                                tr(R.string.integration_the_selection_was_incomplete_or_no_longer_valid_ca0fb2d)
                            PluginRemoteMcpPolicyReviewResult.UNAVAILABLE ->
                                tr(R.string.integration_tool_access_could_not_be_checked_safely_right_now_d60cc42)
                        },
                    )
                }
            }
        }.isSuccess
        if (!scheduled) {
            localUi = localUi.copy(
                remoteMcpPolicyReviewPending = false,
                revision = localUi.revision + 1,
            )
            showShortMessage(tr(R.string.integration_the_tool_access_check_could_not_be_started_de34098))
        }
    }

    private fun showBackupRecoveryScreen(hansApplication: HansApplication) {
        setContentView(
            backupRecoveryScreen(
                context = this,
                onRetry = { button ->
                    button.isEnabled = false
                    backupExecutor.execute {
                        val recovered = hansApplication.retryBackupRecovery()
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            if (recovered) {
                                hansApplication.startProductRuntimeAfterBackupRecovery()
                                recreate()
                            } else {
                                button.isEnabled = true
                                showShortMessage(tr(R.string.integration_recovery_cannot_be_performed_safely_yet_nothing_was_dis_e3230a6))
                            }
                        }
                    }
                },
                onAndroidSettings = {
                    startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                },
                onHomeSettings = {
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS)) }
                        .onFailure { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
                },
            ),
        )
    }

    private fun exportBackupTo(uri: Uri) {
        backupExecutor.execute {
            val outcome = runCatching {
                val bytes = backupCoordinator.exportDocument()
                try {
                    backupDocumentIo.write(uri, bytes)
                } finally {
                    bytes.fill(0)
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (outcome.isSuccess) {
                    showShortMessage(tr(R.string.integration_backup_saved_f8494b5))
                } else {
                    showShortMessage(backupFailureMessage(outcome.exceptionOrNull()))
                }
            }
        }
    }

    private fun prepareBackupImportFrom(uri: Uri) {
        backupExecutor.execute {
            val outcome = runCatching {
                val bytes = backupDocumentIo.read(uri)
                try {
                    backupCoordinator.prepareImport(bytes)
                } finally {
                    bytes.fill(0)
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                outcome.fold(
                    onSuccess = ::showBackupImportPreview,
                    onFailure = { failure ->
                        showShortMessage(backupFailureMessage(failure))
                    },
                )
            }
        }
    }

    private fun showBackupImportPreview(preview: HansBackupImportPreview) {
        val details = buildList {
            add(if (preview.settingsChanged) tr(R.string.integration_settings_will_change_6063d9d) else tr(R.string.integration_settings_are_unchanged_930bd64))
            add(
                if (preview.confirmedProfileWillChange) {
                    tr(R.string.integration_the_confirmed_personal_profile_will_be_replaced_845defb)
                } else {
                    tr(R.string.integration_the_confirmed_personal_profile_is_unchanged_aceeef0)
                },
            )
            add(
                tr(R.string.integration_automations_1_new_2961e70, preview.automationsAdded) +
                    tr(R.string.integration_1_replaced_2_removed_006b2d9, preview.automationsReplaced, preview.automationsRemoved) +
                    tr(R.string.integration_run_history_and_approval_receipts_are_not_imported_exis_7290225),
            )
            add(
                tr(R.string.integration_plugin_references_1_71cabbb, preview.pluginReferencesRequested) +
                    tr(R.string.integration_skill_selections_1_66bf005, preview.skillChoicesRequested),
            )
            addAll(preview.warnings.map { it.render(AndroidHansTextResolver(this@LauncherActivity)) })
        }.joinToString("\n\n")
        val dialog = AlertDialog.Builder(this)
            .setTitle(tr(R.string.integration_import_backup_a2b829b))
            .setMessage(details)
            .setNegativeButton(tr(R.string.integration_cancel_f7ff117)) { _, _ -> backupCoordinator.cancelImport() }
            .setPositiveButton(tr(R.string.integration_import_b0b045d)) { _, _ ->
                confirmBackupImport(preview)
            }
            .create()
        dialog.setOnCancelListener { backupCoordinator.cancelImport() }
        dialog.show()
    }

    private fun confirmBackupImport(preview: HansBackupImportPreview) {
        backupExecutor.execute {
            val outcome = runCatching {
                backupCoordinator.confirmImport(
                    preview.confirmationToken,
                    explicitUserConfirmation = true,
                )
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                outcome.fold(
                    onSuccess = { result ->
                        settings = settingsStore.read()
                        setupRuntime.observeProfileConfirmed(sessionHost.hasConfirmedUserProfile())
                        automationRuntime.scheduleChanged()
                        sessionHost.refreshPlugins(forceRefetch = true)
                        val staged = if (
                            result.requestedModel != null &&
                            result.requestedReasoningEffort != null
                        ) {
                            requestSelection(
                                result.requestedModel,
                                result.requestedReasoningEffort,
                                result.requestedServiceTier,
                                requireExactEffort = true,
                                requireExactServiceTier = true,
                            )
                        } else {
                            true
                        }
                        showShortMessage(backupImportResultMessage(result, staged, AndroidHansTextResolver(this)))
                    },
                    onFailure = { failure ->
                        showShortMessage(backupFailureMessage(failure))
                        if (!HansBackupProcessState.maintenance.isRecoveryReady) recreate()
                    },
                )
            }
        }
    }

    private fun backupFailureMessage(failure: Throwable?): String = when (
        (failure as? HansBackupException)?.errorCode
    ) {
        "backup_document_oversize" -> tr(R.string.integration_the_backup_file_is_too_large_827457f)
        "backup_integrity_mismatch" -> tr(R.string.integration_the_backup_file_was_changed_or_is_damaged_f6fcd46)
        "backup_version" -> tr(R.string.integration_this_backup_version_is_not_supported_yet_071d897)
        "backup_plugin_catalog_unavailable" ->
            tr(R.string.integration_the_plugin_catalog_is_still_loading_please_try_again_sh_05fa036)
        "backup_confirmation_stale",
        "backup_state_changed",
        -> tr(R.string.integration_hans_changed_in_the_meantime_please_select_the_backup_a_d3362fc)
        "backup_forbidden_material" ->
            tr(R.string.integration_the_backup_contains_sign_in_credentials_or_a_private_de_39bb6dd)
        "backup_runtime_busy",
        "backup_automation_work_unsettled",
        -> tr(R.string.integration_some_automations_are_still_running_pending_or_unresolve_6e789be)
        "backup_import_rollback_pending" ->
            tr(R.string.integration_recovery_is_still_pending_automations_remain_blocked_th_d7fa48f)
        else -> tr(R.string.integration_the_backup_could_not_be_processed_6479e7f)
    }

    private fun startGettingToKnowInterview() {
        val selection = selectionForNextDispatch() ?: run {
            showShortMessage(tr(R.string.integration_this_model_configuration_is_unavailable_84bea70))
            return
        }
        val accepted = sessionHost.dispatch(
            input = listOf(
                CodexInput.Text(
                    tr(R.string.integration_i_would_like_to_start_my_getting_to_know_you_conversati_9377324),
                ),
            ),
            selection = selection,
            readResponseAloud = true,
        )
        if (accepted == null) {
            showShortMessage(tr(R.string.integration_the_getting_to_know_you_conversation_cannot_start_yet_4d06cc6))
            return
        }
        localUi = localUi.copy(
            requestedDestination = HansDestination.CHAT,
            revision = localUi.revision + 1,
        )
    }

    /**
     * Advances only in response to a process-host snapshot or an in-process durable-state signal.
     * There is no timer or readiness polling: login, runtime and setup-plugin transitions wake
     * this path through the existing process-wide observer.
     */
    private fun driveInstallerSetupHandoff(snapshot: CodexClientSnapshot) {
        if (setupHandoffDriveInProgress) {
            setupHandoffDriveRequested = true
            return
        }
        setupHandoffDriveInProgress = true
        var currentSnapshot = snapshot
        try {
            do {
                setupHandoffDriveRequested = false
                val setupState = runCatching { setupRuntime.snapshot() }.getOrElse { failure ->
                    Log.e(SETUP_HANDOFF_TAG, "Could not read setup handoff state", failure)
                    return
                }
                // Capture live prerequisites BEFORE nextAction reserves a dispatch. A READY
                // snapshot can outlive its bootstrap/client, and a null second probe must not
                // leave a never-sent reservation stuck forever in this process.
                val selection = selectionForNextDispatch()
                val setupSkill = sessionHost.bundledSetupSkillInput()
                val action = runCatching {
                    setupHandoffCoordinator.nextAction { record ->
                        HansSetupHandoffEnvironment(
                            dispatchReady =
                                HansSetupHandoffDispatchGate.allowsNewTurn(
                                    runtimePhase = currentSnapshot.runtimePhase,
                                    sessionPhase = currentSnapshot.sessionPhase,
                                    bootstrapStatus =
                                        currentSnapshot.bundledSetupBootstrapStatus,
                                    selectionAvailable = selection != null,
                                    setupSkillAvailable = setupSkill != null,
                                ),
                            outboundStatus = currentSnapshot.outboundTimeline
                                .firstOrNull {
                                    it.clientUserMessageId == record.clientUserMessageId
                                }
                                ?.status
                                .toSetupHandoffStatus(),
                            setupComplete = setupState.complete,
                        )
                    }
                }.getOrElse { failure ->
                    Log.e(SETUP_HANDOFF_TAG, "Could not reconcile setup handoff", failure)
                    return
                }
                when (action) {
                    HansSetupHandoffAction.None -> Unit
                    is HansSetupHandoffAction.Dispatch -> {
                        val prompt = SetupConversationPrompt.forState(setupState.complete, AndroidHansTextResolver(this))
                        val attempt = runCatching {
                            if (selection == null || setupSkill == null) {
                                CodexDispatchAttemptResult.RejectedBeforeTransport
                            } else {
                                sessionHost.dispatchSetupHandoff(
                                    input = prompt.toCodexInput(setupSkill),
                                    selection = selection,
                                    readResponseAloud = true,
                                    clientUserMessageId = action.record.clientUserMessageId,
                                )
                            }
                        }.getOrElse { failure ->
                            Log.e(SETUP_HANDOFF_TAG, "Setup handoff dispatch was ambiguous", failure)
                            runCatching {
                                setupHandoffCoordinator.markDispatchAmbiguous(action.record)
                            }.getOrNull()?.let(::surfaceSetupHandoffRecovery)
                            return
                        }
                        val recorded = runCatching {
                            setupHandoffCoordinator.recordDispatchResult(action.record, attempt)
                        }.getOrElse { failure ->
                            Log.e(SETUP_HANDOFF_TAG, "Could not persist setup dispatch result", failure)
                            return
                        }
                        if (!recorded && attempt != CodexDispatchAttemptResult.RejectedBeforeTransport) {
                            runCatching {
                                setupHandoffCoordinator.markDispatchAmbiguous(action.record)
                            }.getOrNull()?.let(::surfaceSetupHandoffRecovery)
                            return
                        }
                        if (
                            attempt !is CodexDispatchAttemptResult.Accepted ||
                            attempt.clientUserMessageId != action.record.clientUserMessageId
                        ) return
                        currentSnapshot = sessionHost.snapshot() ?: currentSnapshot
                        setupHandoffDriveRequested = true
                    }
                    is HansSetupHandoffAction.StartLocalSetup -> {
                        val started = runCatching { setupRuntime.startOrResume() }
                            .onFailure { failure ->
                                Log.e(SETUP_HANDOFF_TAG, "Could not persist on-device setup start", failure)
                            }
                            .isSuccess
                        if (started) {
                            val sealed = runCatching {
                                setupHandoffCoordinator.markSetupStarted(action.record)
                            }.getOrElse { failure ->
                                Log.e(SETUP_HANDOFF_TAG, "Could not seal setup handoff receipt", failure)
                                false
                            }
                            if (sealed) {
                                AndroidLiveVoiceRuntime.refreshContext()
                                setupHandoffDriveRequested = true
                            }
                        }
                    }
                    is HansSetupHandoffAction.SkipAlreadyComplete -> {
                        setupHandoffDriveRequested = true
                    }
                    is HansSetupHandoffAction.RecoveryRequired -> {
                        surfaceSetupHandoffRecovery(action.record)
                    }
                }
                if (setupHandoffDriveRequested) {
                    currentSnapshot = sessionHost.snapshot() ?: currentSnapshot
                }
            } while (setupHandoffDriveRequested)
        } finally {
            setupHandoffDriveInProgress = false
        }
    }

    private fun surfaceSetupHandoffRecovery(record: HansSetupHandoffRecord) {
        if (!surfacedSetupHandoffRecoveries.add(record.command.handoffId)) return
        showShortMessage(
            tr(R.string.integration_automatic_setup_needs_repair_through_the_installer_7d3cb8b),
        )
    }

    private fun OutboundMessageStatus?.toSetupHandoffStatus(): HansSetupHandoffOutboundStatus =
        when (this) {
            null -> HansSetupHandoffOutboundStatus.ABSENT
            OutboundMessageStatus.PENDING -> HansSetupHandoffOutboundStatus.PENDING
            OutboundMessageStatus.SENT -> HansSetupHandoffOutboundStatus.SENT
            OutboundMessageStatus.FAILED -> HansSetupHandoffOutboundStatus.FAILED
        }

    private fun startOrResumeSetupConversation() {
        if (pendingSetupConversationMessageId != null) {
            showShortMessage(tr(R.string.integration_setup_is_already_starting_bbfa8ec))
            return
        }
        // Reading the state is intentionally non-mutating. A failed App Server dispatch must not
        // make setup look started when no setup conversation was actually created.
        val state = runCatching { setupRuntime.snapshot() }.getOrElse {
            showShortMessage(tr(R.string.integration_setup_could_not_be_loaded_f684c84))
            return
        }
        when ((sessionHost.snapshot() ?: clientSnapshot)?.bundledSetupBootstrapStatus) {
            BundledSetupBootstrapStatus.READY -> Unit
            BundledSetupBootstrapStatus.PREPARING -> {
                showShortMessage(tr(R.string.integration_setup_is_still_being_prepared_try_again_shortly_2b7aa7c))
                return
            }
            BundledSetupBootstrapStatus.FAILED -> {
                if (sessionHost.retryBundledSetupBootstrap()) {
                    showShortMessage(tr(R.string.integration_the_setup_plugin_is_being_prepared_again_try_again_shor_52b9e22))
                } else {
                    showShortMessage(tr(R.string.integration_the_setup_plugin_could_not_be_prepared_again_yet_b7a6ba3))
                }
                return
            }
            BundledSetupBootstrapStatus.DISABLED,
            null,
            -> {
                showShortMessage(tr(R.string.integration_the_bundled_setup_plugin_is_unavailable_e9e4daf))
                return
            }
        }
        val selection = selectionForNextDispatch() ?: run {
            showShortMessage(tr(R.string.integration_this_model_configuration_is_unavailable_84bea70))
            return
        }
        val setupSkill = sessionHost.bundledSetupSkillInput() ?: run {
            showShortMessage(tr(R.string.integration_the_setup_skill_is_not_effectively_available_yet_e9be0c6))
            return
        }
        val prompt = SetupConversationPrompt.forState(state.complete, AndroidHansTextResolver(this))
        val accepted = sessionHost.dispatch(
            input = prompt.toCodexInput(setupSkill),
            selection = selection,
            readResponseAloud = true,
            clientUserMessageId = "hans-setup-${UUID.randomUUID()}",
        )
        if (accepted == null) {
            showShortMessage(tr(R.string.integration_setup_cannot_start_yet_5299c8f))
            return
        }
        pendingSetupConversationMessageId = accepted
        localUi = localUi.copy(
            requestedDestination = HansDestination.CHAT,
            revision = localUi.revision + 1,
        )
        // Covers a correlated SENT response delivered before dispatch() returned and before the
        // pending setup receipt was registered in this Activity.
        sessionHost.snapshot()?.let(::reconcilePendingSetupConversation)
    }

    private fun reconcilePendingSetupConversation(snapshot: CodexClientSnapshot) {
        val messageId = pendingSetupConversationMessageId ?: return
        val outcome = reconcileSetupConversationReceipt(
            messageId = messageId,
            outbound = snapshot.outboundTimeline,
            persist = setupRuntime::startOrResume,
        )
        if (!outcome.keepsPending) pendingSetupConversationMessageId = null
        when (outcome) {
            SetupConversationReceiptOutcome.WAITING -> Unit
            SetupConversationReceiptOutcome.REJECTED -> {
                showShortMessage(tr(R.string.integration_setup_could_not_be_started_on_the_server_1659461))
            }
            SetupConversationReceiptOutcome.PERSIST_FAILED -> showShortMessage(
                tr(R.string.integration_the_conversation_started_but_setup_progress_could_not_b_9100410),
            )
            SetupConversationReceiptOutcome.PERSISTED -> {
                // An already-running Realtime session must see this proven workflow before the
                // user's next answer; the session deduplicates this update and stays connected.
                AndroidLiveVoiceRuntime.refreshContext()
            }
        }
    }

    private fun handleSetupUiCommand(
        command: SetupUiCommand,
        lease: SetupUiCommandLease,
        completion: (SetupUiCommandResult) -> Unit,
    ) {
        runOnUiThread {
            if (!lease.isActive()) return@runOnUiThread
            if (isFinishing || isDestroyed) {
                completion(SetupUiCommandResult.UserInteractionRequired)
                return@runOnUiThread
            }
            // Reject retired operations even if an older agent context still has a token.
            // Optional practice uses the ordinary user-initiated voice controls instead.
            if (command is SetupUiCommand.ArmLiveTest && command.token.step in setOf(
                    HansSetupStep.VOICE_DICTATION_TEST,
                    HansSetupStep.HARDWARE_LIVE_TEST,
                    HansSetupStep.CAMERA_HOLD_LIVE_TEST,
                )
            ) {
                completion(SetupUiCommandResult.Rejected("setup_voice_test_retired"))
                return@runOnUiThread
            }
            if (
                command is SetupUiCommand.ArmLiveTest &&
                command.token.step == HansSetupStep.CAMERA_CAPTURE_TEST
            ) {
                beginDirectSetupCameraCapture(command.token, lease, completion)
                return@runOnUiThread
            }
            if (command is SetupUiCommand.OpenSettings) {
                if (command.token.step == HansSetupStep.SPEECH_CREDENTIAL_ACCESS) {
                    completion(SetupUiCommandResult.Rejected("setup_api_key_step_retired"))
                    return@runOnUiThread
                }
                if (
                    command.token.step == HansSetupStep.OPTIONAL_CAPABILITIES &&
                    command.optionalCapability == HansSetupOptionalCapability.EVERYDAY_ACCESS
                ) {
                    showEverydayAccessConfirmation(command.token) { active ->
                        completion(
                            SetupUiCommandResult.Accepted(
                                settingsOpened = true,
                                liveVerificationAccepted = active,
                            ),
                        )
                    }
                    return@runOnUiThread
                }
                if (
                    command.token.step == HansSetupStep.OPTIONAL_CAPABILITIES &&
                    command.optionalCapability ==
                    HansSetupOptionalCapability.NOTIFICATION_LINK_METADATA
                ) {
                    showNotificationLinkMetadataConsent(command.token) { active ->
                        completion(
                            SetupUiCommandResult.Accepted(
                                settingsOpened = true,
                                liveVerificationAccepted = active,
                            ),
                        )
                    }
                    return@runOnUiThread
                }
                val capability = setupCapabilityFor(command)
                if (capability == null) {
                    completion(SetupUiCommandResult.Rejected("setup_step_has_no_settings_ui"))
                } else {
                    requestSetupCapabilityAccess(command.token, capability, completion)
                }
                return@runOnUiThread
            }
            runCatching {
                when (command) {
                    is SetupUiCommand.OpenSettings ->
                        SetupUiCommandResult.Rejected("setup_ui_command_already_dispatched")
                    is SetupUiCommand.BeginKeyCapture -> {
                        if (activeActionKeyCapture != null) {
                            return@runCatching SetupUiCommandResult.Rejected(
                                "key_capture_already_active",
                            )
                        }
                        when (command.inputChoice) {
                            HansSetupInputChoice.HARDWARE_TOGGLE ->
                                selectDictationKeyTrigger(ActionKeyTrigger.PRESS)
                            HansSetupInputChoice.HARDWARE_HOLD ->
                                return@runCatching SetupUiCommandResult.Rejected(
                                    "setup_hold_gesture_retired",
                                )
                            HansSetupInputChoice.NO_HARDWARE_KEY ->
                                return@runCatching SetupUiCommandResult.Rejected(
                                    "setup_hardware_key_skipped",
                                )
                        }
                        activeSetupKeyCaptureToken = command.token
                        startActionKeySetup()
                        if (activeActionKeyCapture != null) {
                            SetupUiCommandResult.Accepted()
                        } else {
                            activeSetupKeyCaptureToken = null
                            SetupUiCommandResult.Rejected("key_capture_not_started")
                        }
                    }
                    is SetupUiCommand.ApplyCameraHoldChoice -> {
                        SetupUiCommandResult.Rejected("setup_camera_hold_retired")
                    }
                    is SetupUiCommand.ApplyModelSelection -> {
                        if (
                            command.model !in HansSettings.SUPPORTED_MODELS ||
                            command.reasoningEffort !in HansSettings.SUPPORTED_REASONING_EFFORTS ||
                            !requestSelection(
                                command.model,
                                command.reasoningEffort,
                                selectionForNextDispatch()?.serviceTier,
                                requireExactEffort = true,
                            )
                        ) {
                            SetupUiCommandResult.Rejected("unsupported_setup_selection")
                        } else {
                            SetupUiCommandResult.Accepted()
                        }
                    }
                    is SetupUiCommand.ArmLiveTest -> armSetupLiveTest(command)
                }
            }.getOrElse {
                SetupUiCommandResult.Rejected("setup_ui_command_failed")
            }.let(completion)
        }
    }

    private fun cancelSetupUiCommand(command: SetupUiCommand) {
        runOnUiThread {
            when (command) {
                is SetupUiCommand.OpenSettings -> when {
                    command.token.step == HansSetupStep.SPEECH_CREDENTIAL_ACCESS &&
                        activeSetupSpeechCredentialToken == command.token -> {
                        activeSetupSpeechCredentialToken = null
                        speechCredentialDialog?.close()
                    }
                    command.token.step == HansSetupStep.OPTIONAL_CAPABILITIES &&
                        command.optionalCapability == HansSetupOptionalCapability.EVERYDAY_ACCESS &&
                        setupEverydayAccessDialogToken == command.token -> {
                        setupEverydayAccessDialog?.dismiss()
                        setupEverydayAccessDialog = null
                        setupEverydayAccessDialogToken = null
                    }
                    command.token.step == HansSetupStep.OPTIONAL_CAPABILITIES &&
                        command.optionalCapability ==
                        HansSetupOptionalCapability.NOTIFICATION_LINK_METADATA &&
                        notificationLinkMetadataDialogToken == command.token -> {
                        notificationLinkMetadataDialog?.dismiss()
                        notificationLinkMetadataDialog = null
                        notificationLinkMetadataDialogToken = null
                    }
                    else -> if (setupCapabilityFor(command) != null) {
                        pendingSetupPermissionInteraction
                            ?.takeIf { it.token == command.token }
                            ?.let { pending ->
                                // Keep a tombstone until Android returns the untagged result. This
                                // prevents that late result from completing a newer request.
                                pendingSetupPermissionInteraction = pending.copy(completion = null)
                            }
                        if (pendingSetupQuickSettingsTile?.token == command.token) {
                            pendingSetupQuickSettingsTile = pendingSetupQuickSettingsTile?.copy(
                                completion = null,
                            )
                        }
                    }
                }
                is SetupUiCommand.ArmLiveTest -> when (command.token.step) {
                    HansSetupStep.CAMERA_CAPTURE_TEST -> {
                        pendingSetupCameraCapture
                            ?.takeIf { it.token == command.token }
                            ?.let { pending ->
                                pendingSetupCameraCapture = pending.copy(completion = null)
                                cameraCaptureCoordinator.acceptResult(captured = false)
                            }
                    }
                    HansSetupStep.VOICE_DICTATION_TEST -> {
                        if (activeSetupDictationTestToken == command.token) {
                            activeSetupDictationTestToken = null
                            HansDictationService.stop(this)
                        }
                        if (setupVoiceTestDialogToken == command.token) {
                            setupVoiceTestDialog?.dismiss()
                            setupVoiceTestDialog = null
                            setupVoiceTestDialogToken = null
                        }
                    }
                    else -> Unit
                }
                is SetupUiCommand.BeginKeyCapture,
                is SetupUiCommand.ApplyCameraHoldChoice,
                is SetupUiCommand.ApplyModelSelection,
                -> Unit
            }
        }
    }

    private fun armSetupLiveTest(
        command: SetupUiCommand.ArmLiveTest,
    ): SetupUiCommandResult {
        activeSetupDictationTestToken = null
        return when (command.token.step) {
            HansSetupStep.HARDWARE_LIVE_TEST -> if (isDictationRecordingActive()) {
                SetupUiCommandResult.Rejected("dictation_already_active")
            } else {
                activeSetupDictationTestToken = command.token
                showShortMessage(
                    tr(R.string.integration_use_the_configured_key_now_as_you_intend_to_use_it_late_fa39eb6),
                )
                SetupUiCommandResult.Accepted()
            }
            HansSetupStep.CAMERA_HOLD_LIVE_TEST -> if (
                settings.cameraHoldToTalkEnabled && !isDictationRecordingActive()
            ) {
                activeSetupDictationTestToken = command.token
                showNextCameraSetupAction(command.token)
                SetupUiCommandResult.Accepted()
            } else {
                SetupUiCommandResult.Rejected(
                    if (settings.cameraHoldToTalkEnabled) {
                        "dictation_already_active"
                    } else {
                        "camera_hold_not_effective"
                    },
                )
            }
            HansSetupStep.NOTIFICATION_LIVE_TEST -> {
                if (
                    Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    // APP_NOTIFICATIONS_CONSENT is a separate earlier setup decision. Never
                    // surprise the user with a permission sheet from a later live-test command.
                    SetupUiCommandResult.Rejected("app_notification_permission_missing")
                } else if (setupRuntime.postNotificationLiveTest(command.token.nonce)) {
                    showShortMessage(
                        tr(R.string.integration_hans_sent_a_harmless_test_notification_and_is_checking__d076cd6),
                    )
                    SetupUiCommandResult.Accepted()
                } else {
                    SetupUiCommandResult.Rejected("notification_test_post_failed")
                }
            }
            HansSetupStep.ACCESSIBILITY_LIVE_TEST -> {
                beginSetupAccessibilityLiveTest(command.token)
            }
            HansSetupStep.VOICE_DICTATION_TEST ->
                SetupUiCommandResult.Rejected("voice_test_requires_dialog")
            HansSetupStep.CAMERA_CAPTURE_TEST ->
                SetupUiCommandResult.Rejected("camera_test_requires_activity_result")
            else -> SetupUiCommandResult.Rejected("setup_not_live_test_step")
        }
    }

    private fun beginDirectSetupCameraCapture(
        token: HansSetupOperationToken,
        lease: SetupUiCommandLease,
        completion: (SetupUiCommandResult) -> Unit,
    ) {
        if (!lease.isActive()) return
        if (pendingSetupCameraCapture != null) {
            completion(SetupUiCommandResult.Rejected("camera_capture_already_active"))
            return
        }
        when (val preparation = cameraCaptureCoordinator.prepareCapture()) {
            CameraCapturePreparation.CameraUnavailable ->
                completion(SetupUiCommandResult.Rejected("camera_capture_unavailable"))
            CameraCapturePreparation.StorageUnavailable ->
                completion(SetupUiCommandResult.Rejected("camera_capture_storage_unavailable"))
            is CameraCapturePreparation.Ready -> {
                if (!lease.isActive()) {
                    cameraCaptureCoordinator.abandon(preparation)
                    return
                }
                pendingSetupCameraCapture = PendingSetupCameraCapture(token, lease, completion)
                try {
                    cameraCaptureLauncher.launch(preparation.outputUri)
                } catch (_: ActivityNotFoundException) {
                    pendingSetupCameraCapture = null
                    cameraCaptureCoordinator.abandon(preparation)
                    completion(SetupUiCommandResult.Rejected("camera_capture_unavailable"))
                } catch (_: SecurityException) {
                    pendingSetupCameraCapture = null
                    cameraCaptureCoordinator.abandon(preparation)
                    completion(SetupUiCommandResult.Rejected("camera_capture_security_rejection"))
                } catch (_: IllegalArgumentException) {
                    pendingSetupCameraCapture = null
                    cameraCaptureCoordinator.abandon(preparation)
                    completion(SetupUiCommandResult.Rejected("camera_capture_launch_failed"))
                }
            }
        }
    }

    private fun showNextCameraSetupAction(token: HansSetupOperationToken) {
        if (token.step != HansSetupStep.CAMERA_HOLD_LIVE_TEST) return
        val state = setupRuntime.snapshot()
        val record = state.record(HansSetupStep.CAMERA_HOLD_LIVE_TEST)
        if (record.terminal || state.currentStep != HansSetupStep.CAMERA_HOLD_LIVE_TEST) return
        showShortMessage(SetupLiveTestCopy.cameraAction(record, AndroidHansTextResolver(this)))
    }

    private fun beginSetupAccessibilityLiveTest(
        token: HansSetupOperationToken,
    ): SetupUiCommandResult {
        if (setupAccessibilityTestDialog?.isShowing == true) {
            return SetupUiCommandResult.Rejected("accessibility_test_already_active")
        }
        if (!setupRuntime.armLiveTest(token.step, token.nonce)) {
            return SetupUiCommandResult.Rejected("accessibility_test_arm_failed")
        }
        val receiptCopy = setupRuntime.accessibilityTestCopy(token.nonce)
            ?: return SetupUiCommandResult.Rejected("accessibility_test_copy_unavailable")
        val dialog = AlertDialog.Builder(this)
            .setTitle(tr(R.string.integration_test_app_control_d029d32))
            .setMessage(tr(R.string.integration_tap_check_test_area_once_now_bb57b4a))
            .setPositiveButton(tr(R.string.integration_check_test_area_c84182e), null)
            .setNegativeButton(tr(R.string.integration_later_c47401b)) { current, _ -> current.dismiss() }
            .create()
        setupAccessibilityTestDialog = dialog
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            setupAccessibilityPostconditionCheck?.let(actionKeyCaptureHandler::removeCallbacks)
            setupAccessibilityPostconditionCheck = null
            if (setupAccessibilityTestDialog === dialog) setupAccessibilityTestDialog = null
        }
        dialog.setOnShowListener {
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.contentDescription = receiptCopy.targetDescription
            button.setOnClickListener {
                if (!setupRuntime.recordAccessibilityTargetAction(token.nonce)) {
                    dialog.setMessage(
                        tr(R.string.integration_hans_could_not_read_this_test_area_unambiguously_yet_7bc338b) +
                            tr(R.string.integration_wait_briefly_then_tap_check_test_area_again_f4a5895),
                    )
                    return@setOnClickListener
                }
                dialog.setMessage(receiptCopy.postconditionText)
                button.isEnabled = false
                scheduleAccessibilityPostconditionCheck(dialog, token, attempt = 0)
            }
        }
        dialog.show()
        return SetupUiCommandResult.Accepted()
    }

    private fun scheduleAccessibilityPostconditionCheck(
        dialog: AlertDialog,
        token: HansSetupOperationToken,
        attempt: Int,
    ) {
        setupAccessibilityPostconditionCheck?.let(actionKeyCaptureHandler::removeCallbacks)
        val check = Runnable {
            setupAccessibilityPostconditionCheck = null
            if (setupAccessibilityTestDialog !== dialog || !dialog.isShowing) return@Runnable
            if (setupRuntime.recordAccessibilityPostcondition(token.nonce)) {
                showShortMessage(tr(R.string.integration_app_control_detected_the_exact_test_action_f4588b2))
                dialog.dismiss()
            } else if (attempt + 1 < ACCESSIBILITY_POSTCONDITION_MAX_ATTEMPTS) {
                scheduleAccessibilityPostconditionCheck(dialog, token, attempt + 1)
            } else {
                dialog.setMessage(
                    tr(R.string.integration_the_evidence_was_not_unambiguous_yet_a80ac58) +
                        tr(R.string.integration_please_tap_check_test_area_again_42309f9),
                )
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
            }
        }
        setupAccessibilityPostconditionCheck = check
        actionKeyCaptureHandler.postDelayed(check, ACCESSIBILITY_POSTCONDITION_RETRY_MILLIS)
    }

    private fun beginSetupVoiceAndDictationTest(
        token: HansSetupOperationToken,
        lease: SetupUiCommandLease,
        completion: (SetupUiCommandResult) -> Unit,
    ) {
        if (!lease.isActive()) return
        if (setupVoiceTestDialog?.isShowing == true) {
            completion(SetupUiCommandResult.Rejected("voice_test_already_active"))
            return
        }
        activeSetupDictationTestToken = null
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            completion(SetupUiCommandResult.Rejected("microphone_permission_missing"))
            return
        }
        if (!sessionHost.previewSpeech()) {
            completion(SetupUiCommandResult.Rejected("voice_preview_unavailable"))
            return
        }
        if (!lease.isActive()) return
        val dialog = AlertDialog.Builder(this)
            .setTitle(tr(R.string.integration_test_speech_output_815e01f))
            .setMessage(SetupLiveTestCopy.voicePreviewAction(AndroidHansTextResolver(this)))
            .setNegativeButton(tr(R.string.integration_later_c47401b)) { _, _ ->
                completion(SetupUiCommandResult.Rejected("voice_test_deferred"))
            }
            .setPositiveButton(tr(R.string.integration_continue_1e14bdf)) { _, _ ->
                if (!lease.tryComplete(
                        SetupUiCommandResult.Accepted(liveVerificationAccepted = true),
                    )
                ) {
                    return@setPositiveButton
                }
                activeSetupDictationTestToken = token
                HansDictationService.start(this)
            }
            .create()
        setupVoiceTestDialog = dialog
        setupVoiceTestDialogToken = token
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener {
            completion(SetupUiCommandResult.Rejected("voice_test_deferred"))
        }
        dialog.setOnDismissListener {
            if (setupVoiceTestDialog === dialog) {
                setupVoiceTestDialog = null
                setupVoiceTestDialogToken = null
            }
        }
        dialog.show()
    }

    private fun offerSetupDictationStop() {
        if (setupVoiceStopDialog?.isShowing == true) return
        setupVoiceStopDialog = AlertDialog.Builder(this)
            .setTitle(tr(R.string.integration_hans_is_listening_3d4eedb))
            .setMessage(SetupLiveTestCopy.voiceDictationAction(AndroidHansTextResolver(this)))
            .setPositiveButton(tr(R.string.integration_done_speaking_a0f923f)) { _, _ ->
                HansDictationService.stop(this)
            }
            .create()
            .also { dialog ->
                dialog.setOnDismissListener { setupVoiceStopDialog = null }
                dialog.show()
            }
    }

    private fun sendComposer(displayText: String) {
        if (pendingComposerDispatch != null) return
        if (showOfflineSubmissionNotice()) return
        val snapshot = sessionHost.snapshot() ?: clientSnapshot
        if (snapshot?.pendingSettingsSelection != null && snapshot.sessionPhase != ClientSessionPhase.BUSY) {
            // Nothing was transmitted: keep typing possible and retain the draft, without
            // turning a short settings handshake into an ambiguous-delivery warning.
            showShortMessage(tr(R.string.integration_the_model_selection_is_still_being_confirmed_please_sen_663a0ad))
            return
        }
        val attachments = localUi.attachments
        val attachmentContext = attachments
            .map(HansPendingAttachment::contextText)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString("\n\n")
        val input = buildList {
            val combinedText = listOf(displayText.trim(), attachmentContext)
                .filter(String::isNotBlank)
                .joinToString("\n\n")
            if (combinedText.isNotBlank()) add(CodexInput.Text(combinedText))
            attachments.forEach { add(CodexInput.LocalImage(it.absolutePath)) }
        }
        if (input.isEmpty()) return

        val selection = selectionForNextDispatch() ?: run {
            showShortMessage(tr(R.string.integration_this_model_configuration_is_unavailable_84bea70))
            return
        }
        val messageId = "composer-${UUID.randomUUID()}"
        val pending = PendingComposerDispatch(
            messageId = messageId,
            draftText = displayText,
            attachments = attachments,
        )
        pendingComposerDispatch = pending
        localUi = localUi.copy(
            pendingComposerMessageId = messageId,
            connectionFailureMessage = "",
            revision = localUi.revision + 1,
        )
        val acceptedMessageId = sessionHost.dispatch(
            input,
            selection,
            readResponseAloud = false,
            clientUserMessageId = messageId,
        )
        if (acceptedMessageId != messageId) {
            pendingComposerDispatch = null
            localUi = localUi.copy(
                pendingComposerMessageId = null,
                connectionFailureMessage = tr(R.string.integration_the_message_could_not_be_confirmed_the_draft_is_preserv_873e84b),
                revision = localUi.revision + 1,
            )
            return
        }
        // Covers a SENT result delivered before dispatch() returns and before UI registration.
        sessionHost.snapshot()?.let(::reconcilePendingComposer)
    }

    private fun reconcilePendingComposer(snapshot: CodexClientSnapshot) {
        val pending = pendingComposerDispatch ?: return
        when (val result = reconcileComposerDispatch(localUi, pending, snapshot.outboundTimeline)) {
            ComposerDispatchReconciliation.Waiting -> Unit
            is ComposerDispatchReconciliation.Rejected -> {
                localUi = result.local
                pendingComposerDispatch = null
            }
            is ComposerDispatchReconciliation.Committed -> {
                localUi = result.local
                pendingComposerDispatch = null
                if (result.consumedImportIds.isNotEmpty()) {
                    val alreadyTerminalTurns = snapshot.terminalTurns
                        .mapTo(mutableSetOf()) { MediaTurnLeaseKey(it.threadId, it.turnId) }
                    val alreadyIdleThreadIds = authoritativeIdleMediaThreadIds(
                        runtimePhase = snapshot.runtimePhase,
                        sessionPhase = snapshot.sessionPhase,
                        rehydrationRequired = snapshot.session.delivery.rehydrationRequired,
                        currentThreadId = snapshot.session.currentThreadId,
                        threads = snapshot.session.threads,
                    )
                    mediaExecutor.execute {
                        val persisted = mediaTurnLeaseTracker.register(
                            threadId = result.threadId,
                            turnId = result.turnId,
                            importIds = result.consumedImportIds,
                        )
                        if (!persisted) {
                            Log.w(
                                DIAGNOSTIC_TAG,
                                "Media lease recovery receipt could not be persisted; " +
                                    "the import remains available to the active turn.",
                            )
                        }
                        if (alreadyTerminalTurns.isNotEmpty() || alreadyIdleThreadIds.isNotEmpty()) {
                            mediaTurnLeaseTracker.releaseTerminalTurns(
                                terminalTurns = alreadyTerminalTurns,
                                terminalThreadIds = alreadyIdleThreadIds,
                                deleteAndConfirmAbsent = mediaPipeline::deleteAndConfirmAbsent,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun scheduleTerminalMediaCleanup(snapshot: CodexClientSnapshot) {
        val terminalTurns = snapshot.terminalTurns
            .mapTo(mutableSetOf()) { MediaTurnLeaseKey(it.threadId, it.turnId) }
        val idleThreadIds = authoritativeIdleMediaThreadIds(
            runtimePhase = snapshot.runtimePhase,
            sessionPhase = snapshot.sessionPhase,
            rehydrationRequired = snapshot.session.delivery.rehydrationRequired,
            currentThreadId = snapshot.session.currentThreadId,
            threads = snapshot.session.threads,
        )
        if (terminalTurns.isEmpty() && idleThreadIds.isEmpty()) return
        mediaExecutor.execute {
            mediaTurnLeaseTracker.releaseTerminalTurns(
                terminalTurns = terminalTurns,
                terminalThreadIds = idleThreadIds,
                deleteAndConfirmAbsent = mediaPipeline::deleteAndConfirmAbsent,
            )
        }
    }

    private fun refreshPrivateSpaceState() {
        if (!::publicCapabilityAdapter.isInitialized) return
        if (privateSpaceRefreshInProgress) {
            privateSpaceRefreshRequested = true
            return
        }
        privateSpaceRefreshInProgress = true
        privateSpaceRefreshRequested = false
        appCatalogExecutor.execute {
            val result = publicCapabilityAdapter.privateSpaceSnapshot()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                privateSpaceRefreshInProgress = false
                when (result) {
                    is AndroidAdapterResult.Success -> {
                        val snapshot = result.value
                        val requestedLocked = pendingPrivateSpaceLockedState
                        val requestConfirmed = requestedLocked != null &&
                            snapshot.profile?.locked == requestedLocked
                        if (
                            snapshot.availability != PrivateSpaceAvailability.AVAILABLE ||
                            requestConfirmed
                        ) {
                            pendingPrivateSpaceLockedState = null
                        }
                        val operationPending = pendingPrivateSpaceLockedState != null
                        localUi = localUi.copy(
                            privateSpace = privateSpaceUiState(
                                snapshot = snapshot,
                                containerVisible = privateSpaceVisibilityStore.isVisible(),
                                operationInProgress = operationPending,
                                notice = if (operationPending) {
                                    localUi.privateSpace.notice
                                } else {
                                    ""
                                },
                            ),
                            revision = localUi.revision + 1,
                        )
                        if (
                            snapshot.profile?.locked != false ||
                            !localUi.privateSpace.containerVisible
                        ) {
                            purgePrivateAppInventory()
                        }
                    }
                    is AndroidAdapterResult.Failure -> {
                        pendingPrivateSpaceLockedState = null
                        localUi = localUi.copy(
                            privateSpace = PrivateSpaceSnapshot(
                                PrivateSpaceAvailability.UNSUPPORTED_PLATFORM,
                            ).let { snapshot ->
                                privateSpaceUiState(
                                    snapshot = snapshot,
                                    containerVisible = privateSpaceVisibilityStore.isVisible(),
                                    notice = tr(R.string.integration_private_space_could_not_be_checked_cdc9db8),
                                )
                            },
                            revision = localUi.revision + 1,
                        )
                        purgePrivateAppInventory()
                    }
                }
                if (privateSpaceRefreshRequested) refreshPrivateSpaceState()
            }
        }
    }

    private fun setPrivateSpaceContainerVisible(visible: Boolean) {
        if (!privateSpaceVisibilityStore.setVisible(visible)) {
            showShortMessage(tr(R.string.integration_the_private_space_setting_was_not_saved_bfaa305))
            return
        }
        localUi = localUi.copy(
            privateSpace = localUi.privateSpace.copy(
                containerVisible = visible,
                notice = "",
            ),
            revision = localUi.revision + 1,
        )
        if (!visible) {
            purgePrivateAppInventory()
            showShortMessage(tr(R.string.integration_private_space_is_hidden_you_can_find_it_in_settings_0d2eccc))
        } else {
            refreshPrivateSpaceState()
            if (localUi.requestedDestination == HansDestination.APPS) refreshAppDrawer()
        }
    }

    private fun requestPrivateSpaceLocked(profileId: String, locked: Boolean) {
        if (
            localUi.privateSpace.profileId != profileId ||
            !localUi.privateSpace.canChangeLock
        ) {
            showShortMessage(tr(R.string.integration_private_space_is_no_longer_available_in_this_state_2da743e))
            refreshPrivateSpaceState()
            return
        }
        pendingPrivateSpaceLockedState = locked
        localUi = localUi.copy(
            privateSpace = localUi.privateSpace.copy(
                operationInProgress = true,
                notice = "",
            ),
            revision = localUi.revision + 1,
        )
        appCatalogExecutor.execute {
            val result = publicCapabilityAdapter.requestPrivateSpaceLocked(profileId, locked)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is AndroidAdapterResult.Success -> {
                        val outcome = result.value.outcome
                        val snapshot = result.value.snapshot
                        val keepPending = outcome == PrivateSpaceQuietModeOutcome.PENDING
                        if (!keepPending) pendingPrivateSpaceLockedState = null
                        val notice = when (outcome) {
                            PrivateSpaceQuietModeOutcome.CONFIRMED -> ""
                            PrivateSpaceQuietModeOutcome.PENDING ->
                                tr(R.string.integration_android_is_updating_private_space_96d7bdd)
                            PrivateSpaceQuietModeOutcome.AUTHENTICATION_REQUIRED ->
                                tr(R.string.integration_confirm_unlocking_in_the_android_dialog_be5d25e)
                            PrivateSpaceQuietModeOutcome.REJECTED ->
                                tr(R.string.integration_android_did_not_apply_the_change_c2a1797)
                        }
                        localUi = localUi.copy(
                            privateSpace = privateSpaceUiState(
                                snapshot = snapshot,
                                containerVisible = privateSpaceVisibilityStore.isVisible(),
                                operationInProgress = keepPending,
                                notice = notice,
                            ),
                            revision = localUi.revision + 1,
                        )
                        if (snapshot.profile?.locked != false) purgePrivateAppInventory()
                    }
                    is AndroidAdapterResult.Failure -> {
                        pendingPrivateSpaceLockedState = null
                        localUi = localUi.copy(
                            privateSpace = localUi.privateSpace.copy(
                                operationInProgress = false,
                                notice = tr(R.string.integration_android_could_not_change_private_space_fe32111),
                            ),
                            revision = localUi.revision + 1,
                        )
                    }
                }
                if (localUi.requestedDestination == HansDestination.APPS) refreshAppDrawer()
            }
        }
    }

    private fun openPrivateSpaceSettings() {
        when (publicCapabilityAdapter.openPrivateSpaceSettings()) {
            is AndroidAdapterResult.Success -> Unit
            is AndroidAdapterResult.Failure ->
                showShortMessage(tr(R.string.integration_android_settings_for_private_space_are_unavailable_423d0cd))
        }
    }

    private fun onLauncherProfileEvent(event: LauncherProfileEvent) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (event.requiresImmediateInventoryPurge) purgeNonPersonalAppInventory()
            refreshPrivateSpaceState()
            if (localUi.requestedDestination == HansDestination.APPS) refreshAppDrawer()
        }
    }

    private fun returnToChat() {
        val personal = personalAppInventoryOnly(localUi.apps, localUi.appProfiles)
        localUi = localUi.copy(
            requestedDestination = HansDestination.CHAT,
            selectedPluginHandle = null,
            apps = personal.apps,
            appProfiles = personal.profiles,
            revision = localUi.revision + 1,
        )
    }

    /** One bounded SQLite snapshot per explicit UI/runtime event; never a timer or idle poll. */
    private fun refreshAutomations(notice: String = "", errorMessage: String = "") {
        if (localUi.requestedDestination != HansDestination.AUTOMATIONS || isDestroyed) return
        val generation = ++automationRefreshGeneration
        localUi = localUi.copy(
            automations = localUi.automations.copy(
                loading = true,
                notice = notice,
                errorMessage = errorMessage,
            ),
            revision = localUi.revision + 1,
        )
        val accepted = runCatching {
            automationUiExecutor.execute {
                val projection = runCatching {
                    projectAutomations(
                        snapshot = automationRuntime.storage.snapshot(),
                        systemZone = java.time.ZoneId.systemDefault(),
                        text = AndroidHansTextResolver(this),
                    )
                }
                runOnUiThread {
                    if (
                        isDestroyed || generation != automationRefreshGeneration ||
                        localUi.requestedDestination != HansDestination.AUTOMATIONS
                    ) return@runOnUiThread
                    localUi = localUi.copy(
                        automations = projection.fold(
                            onSuccess = {
                                it.copy(
                                    loading = false,
                                    operationAutomationId = localUi.automations.operationAutomationId,
                                    notice = notice,
                                    errorMessage = errorMessage,
                                )
                            },
                            onFailure = {
                                localUi.automations.copy(
                                    loading = false,
                                    errorMessage = tr(R.string.integration_automations_could_not_be_read_safely_3324cfb),
                                )
                            },
                        ),
                        revision = localUi.revision + 1,
                    )
                }
            }
        }.isSuccess
        if (!accepted && generation == automationRefreshGeneration) {
            localUi = localUi.copy(
                automations = localUi.automations.copy(
                    loading = false,
                    errorMessage = tr(R.string.integration_automations_could_not_be_checked_right_now_d68d08c),
                ),
                revision = localUi.revision + 1,
            )
        }
    }

    private fun startAutomationCreationInChat() {
        val existingDraft = localUi.text
        localUi = localUi.copy(
            requestedDestination = HansDestination.CHAT,
            text = existingDraft.ifBlank { tr(R.string.integration_create_a_new_automation_a6313f9) },
            revision = localUi.revision + 1,
        )
        if (existingDraft.isNotBlank()) {
            showShortMessage(tr(R.string.integration_your_existing_draft_is_preserved_eb46331))
        }
    }

    private fun startAutomationEditInChat(rawId: String, revision: Long) {
        val projected = localUi.automations.items.firstOrNull {
            it.id == rawId && it.revision == revision
        }
        if (projected == null) {
            refreshAutomations(errorMessage = tr(R.string.integration_the_automation_changed_in_the_meantime_the_current_vers_d5cd63a))
            return
        }
        val existingDraft = localUi.text
        val editPrompt =
            tr(R.string.integration_change_the_existing_automation_1_e45bc0d, projected.id) +
                tr(R.string.integration_current_revision_1_requested_change_8b74b81, projected.revision)
        localUi = localUi.copy(
            requestedDestination = HansDestination.CHAT,
            text = existingDraft.ifBlank { editPrompt },
            revision = localUi.revision + 1,
        )
        if (existingDraft.isNotBlank()) {
            showShortMessage(tr(R.string.integration_your_existing_draft_is_preserved_eb46331))
        }
    }

    private fun setAutomationEnabled(rawId: String, revision: Long, enabled: Boolean) {
        runAutomationUiMutation(rawId) {
            val id = AutomationId(rawId)
            val current = automationRuntime.storage.definition(id)
                ?: return@runAutomationUiMutation AutomationUiOperationResult.error(
                    tr(R.string.integration_this_automation_no_longer_exists_229c630),
                )
            if (current.revision != revision) {
                return@runAutomationUiMutation AutomationUiOperationResult.error(
                    tr(R.string.integration_the_automation_changed_in_the_meantime_the_current_vers_d5cd63a),
                )
            }
            if (current.enabled == enabled) {
                return@runAutomationUiMutation AutomationUiOperationResult.success(
                    if (enabled) tr(R.string.integration_automation_is_active_6f37060) else tr(R.string.integration_automation_is_paused_df4ac3a),
                )
            }
            val applied = if (enabled) {
                automationRuntime.definitions.replace(
                    id,
                    revision,
                    current.toAutomationDraft(enabled = true),
                ) is AutomationDefinitionMutationResult.Applied
            } else {
                automationRuntime.definitions.cancel(id, revision) is
                    AutomationCancellationResult.Cancelled
            }
            if (!applied) {
                AutomationUiOperationResult.error(tr(R.string.integration_the_change_was_not_saved_ab3f8a2))
            } else {
                automationScheduleMutationResult(
                    acceptedMessage = if (enabled) {
                        tr(R.string.integration_automation_enabled_23bd25f)
                    } else {
                        tr(R.string.integration_automation_paused_a34143d)
                    },
                )
            }
        }
    }

    private fun setAutomationUnattended(rawId: String, revision: Long, unattended: Boolean) {
        runAutomationUiMutation(rawId) {
            val id = AutomationId(rawId)
            val current = automationRuntime.storage.definition(id)
                ?: return@runAutomationUiMutation AutomationUiOperationResult.error(
                    tr(R.string.integration_this_automation_no_longer_exists_229c630),
                )
            if (current.revision != revision) {
                return@runAutomationUiMutation AutomationUiOperationResult.error(
                    tr(R.string.integration_the_automation_changed_in_the_meantime_the_current_vers_d5cd63a),
                )
            }
            val policy = if (unattended) {
                AutomationConfirmationPolicy.CAPABILITY_POLICY
            } else {
                AutomationConfirmationPolicy.EVERY_RUN
            }
            if (current.requirements.confirmationPolicy == policy) {
                return@runAutomationUiMutation AutomationUiOperationResult.success(
                    if (unattended) tr(R.string.integration_unattended_execution_is_enabled_ca82236)
                    else tr(R.string.integration_every_run_requires_confirmation_d86a0e3),
                )
            }
            val result = automationRuntime.definitions.replace(
                id,
                revision,
                current.toAutomationDraft(
                    requirements = current.requirements.copy(confirmationPolicy = policy),
                ),
            )
            if (result !is AutomationDefinitionMutationResult.Applied) {
                AutomationUiOperationResult.error(tr(R.string.integration_the_execution_rule_was_not_saved_d8d6f70))
            } else {
                automationScheduleMutationResult(
                    acceptedMessage = if (unattended) {
                        tr(R.string.integration_unattended_execution_enabled_phone_tools_retain_their_o_b2f95f7)
                    } else {
                        tr(R.string.integration_confirmation_before_every_run_enabled_d032c00)
                    },
                )
            }
        }
    }

    private fun runAutomationNow(rawId: String, revision: Long) {
        runAutomationUiMutation(rawId) {
            val requestMaterial = "$rawId:$revision:${UUID.randomUUID()}"
            val requestId = AutomationManualRequestId(
                MessageDigest.getInstance("SHA-256")
                    .digest(requestMaterial.toByteArray(Charsets.UTF_8))
                    .joinToString("") { byte ->
                        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                    },
            )
            when (
                val result = automationRuntime.enqueueUserApprovedRunNow(
                    AutomationId(rawId),
                    revision,
                    requestId,
                )
            ) {
                is AutomationManualEnqueueResult.Enqueued -> AutomationUiOperationResult.success(
                    tr(R.string.integration_execution_is_scheduled_43e1baa),
                )
                is AutomationManualEnqueueResult.Duplicate -> AutomationUiOperationResult.success(
                    tr(R.string.integration_this_execution_was_already_scheduled_5bdeb25),
                )
                is AutomationManualEnqueueResult.DispatchRejected -> AutomationUiOperationResult.error(
                    tr(R.string.integration_the_execution_is_saved_but_could_not_be_started_yet_d30bf04),
                )
                AutomationManualEnqueueResult.DefinitionDisabled -> AutomationUiOperationResult.error(
                    tr(R.string.integration_enable_the_automation_first_a20ed23),
                )
                AutomationManualEnqueueResult.DefinitionNotFound -> AutomationUiOperationResult.error(
                    tr(R.string.integration_this_automation_no_longer_exists_229c630),
                )
                AutomationManualEnqueueResult.RevisionConflict -> AutomationUiOperationResult.error(
                    tr(R.string.integration_the_automation_changed_in_the_meantime_the_current_vers_d5cd63a),
                )
                AutomationManualEnqueueResult.RequestConflict -> AutomationUiOperationResult.error(
                    tr(R.string.integration_execution_could_not_be_scheduled_unambiguously_bb9c4bc),
                )
            }
        }
    }

    private fun automationScheduleMutationResult(
        acceptedMessage: String,
    ): AutomationUiOperationResult = when (automationRuntime.scheduleChanged()) {
        ai.hans.standard.automations.AutomationAdapterResult.Accepted ->
            AutomationUiOperationResult.success(acceptedMessage)
        is ai.hans.standard.automations.AutomationAdapterResult.Rejected ->
            AutomationUiOperationResult(
                successMessage = tr(R.string.integration_change_saved_5cb1866),
                errorMessage =
                    tr(R.string.integration_android_could_not_schedule_the_retry_yet_hans_will_try__1fc0932),
            )
    }

    private fun deleteAutomation(rawId: String, revision: Long) {
        runAutomationUiMutation(rawId) {
            val id = AutomationId(rawId)
            val current = automationRuntime.storage.definition(id)
                ?: return@runAutomationUiMutation AutomationUiOperationResult.error(
                    tr(R.string.integration_this_automation_no_longer_exists_229c630),
                )
            if (current.revision != revision) {
                return@runAutomationUiMutation AutomationUiOperationResult.error(
                    tr(R.string.integration_the_automation_changed_in_the_meantime_the_current_vers_d5cd63a),
                )
            }
            val revisionToDelete = if (current.enabled) {
                when (val cancelled = automationRuntime.definitions.cancel(id, revision)) {
                    is AutomationCancellationResult.Cancelled -> cancelled.newRevision
                    else -> return@runAutomationUiMutation AutomationUiOperationResult.error(
                        tr(R.string.integration_the_automation_could_not_be_disabled_safely_b1a4e84),
                    )
                }
            } else {
                revision
            }
            if (!automationRuntime.storage.removeDefinition(id, revisionToDelete)) {
                AutomationUiOperationResult.error(
                    tr(R.string.integration_the_automation_still_has_an_active_operation_and_could__dd76bfa),
                )
            } else {
                automationRuntime.scheduleChanged()
                AutomationUiOperationResult.success(tr(R.string.integration_automation_deleted_9a53dcc))
            }
        }
    }

    private fun runAutomationUiMutation(
        automationId: String,
        operation: () -> AutomationUiOperationResult,
    ) {
        if (localUi.automations.operationAutomationId != null || isDestroyed) return
        localUi = localUi.copy(
            automations = localUi.automations.copy(
                operationAutomationId = automationId,
                errorMessage = "",
                notice = "",
            ),
            revision = localUi.revision + 1,
        )
        val accepted = runCatching {
            automationUiExecutor.execute {
                val result = runCatching(operation).getOrElse {
                    AutomationUiOperationResult.error(tr(R.string.integration_the_change_could_not_be_performed_safely_edfefc7))
                }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    localUi = localUi.copy(
                        automations = localUi.automations.copy(operationAutomationId = null),
                        revision = localUi.revision + 1,
                    )
                    refreshAutomations(
                        notice = result.successMessage,
                        errorMessage = result.errorMessage,
                    )
                }
            }
        }.isSuccess
        if (!accepted) {
            localUi = localUi.copy(
                automations = localUi.automations.copy(
                    operationAutomationId = null,
                    errorMessage = tr(R.string.integration_the_change_could_not_be_started_right_now_2a49164),
                ),
                revision = localUi.revision + 1,
            )
        }
    }

    private fun AutomationDefinition.toAutomationDraft(
        enabled: Boolean = this.enabled,
        requirements: ai.hans.standard.automations.AutomationRequirements = this.requirements,
    ): AutomationDefinitionDraft = AutomationDefinitionDraft(
        enabled = enabled,
        schedule = schedule,
        missedRunPolicy = missedRunPolicy,
        retryPolicy = retryPolicy,
        target = target,
        instruction = instruction,
        requirements = requirements,
        timingPolicy = timingPolicy,
    )

    private data class AutomationUiOperationResult(
        val successMessage: String,
        val errorMessage: String,
    ) {
        companion object {
            fun success(message: String) = AutomationUiOperationResult(message, "")
            fun error(message: String) = AutomationUiOperationResult("", message)
        }
    }

    /**
     * One bounded read per explicit event. There is deliberately no timer, observer or idle
     * watcher: E-Ink stays still, and opening this screen never binds the Python worker.
     */
    private fun refreshWorkbench() {
        if (localUi.requestedDestination != HansDestination.WORKBENCH || isDestroyed) return
        val generation = ++workbenchRefreshGeneration
        localUi = localUi.copy(
            workbench = localUi.workbench.copy(loading = true, errorMessage = ""),
            revision = localUi.revision + 1,
        )
        val hansApplication = application as HansApplication
        val accepted = runCatching {
            appCatalogExecutor.execute {
                val outcome = runCatching {
                    projectWorkbenchInventory(
                        workspaces = hansApplication.workspaceStore.listSnapshots(
                            limit = WORKBENCH_INVENTORY_LIMIT + 1,
                        ),
                        artifacts = hansApplication.artifactStore.listMetadata(
                            limit = WORKBENCH_INVENTORY_LIMIT + 1,
                        ),
                        python = hansApplication.passiveInitializedPythonRuntimeSnapshot(),
                        text = AndroidHansTextResolver(this),
                    )
                }
                runOnUiThread {
                    if (
                        isDestroyed ||
                        localUi.requestedDestination != HansDestination.WORKBENCH ||
                        generation != workbenchRefreshGeneration
                    ) {
                        return@runOnUiThread
                    }
                    localUi = localUi.copy(
                        workbench = outcome.getOrElse {
                            localUi.workbench.copy(
                                loading = false,
                                errorMessage =
                                    tr(R.string.integration_local_workspaces_could_not_be_checked_completely_20f740e),
                            )
                        }.copy(loading = false),
                        revision = localUi.revision + 1,
                    )
                }
            }
        }.isSuccess
        if (!accepted && generation == workbenchRefreshGeneration) {
            localUi = localUi.copy(
                workbench = localUi.workbench.copy(
                    loading = false,
                    errorMessage = tr(R.string.integration_the_workbench_cannot_be_refreshed_right_now_9ea698b),
                ),
                revision = localUi.revision + 1,
            )
        }
    }

    private fun refreshAppDrawer() {
        if (localUi.appsLoading) {
            appCatalogRefreshRequested = true
            return
        }
        appCatalogRefreshRequested = false
        localUi = localUi.copy(
            appsLoading = true,
            appsErrorMessage = "",
            revision = localUi.revision + 1,
        )
        appCatalogExecutor.execute {
            val result = publicCapabilityAdapter.listLaunchableAppCatalog()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (!launcherUiStarted || localUi.requestedDestination != HansDestination.APPS) {
                    localUi = localUi.copy(
                        appsLoading = false,
                        revision = localUi.revision + 1,
                    )
                    purgeNonPersonalAppInventory()
                    return@runOnUiThread
                }
                localUi = when (result) {
                    is AndroidAdapterResult.Success -> localUi.copy(
                        apps = result.value.apps
                            .filterNot { it.packageName == packageName }
                            .filterNot {
                                it.profileType == LaunchProfileType.PRIVATE &&
                                    !privateSpaceVisibilityStore.isVisible()
                            }
                            .map { app ->
                                AppUiModel(
                                    packageName = app.packageName,
                                    componentName = app.componentName,
                                    label = app.label,
                                    profileId = app.profileId,
                                    profileType = app.profileType,
                                )
                            },
                        appProfiles = result.value.profiles.map { profile ->
                            AppProfileUiModel(
                                profileId = profile.profileId,
                                type = profile.type,
                                locked = profile.locked,
                            )
                        },
                        appsLoading = false,
                        appsErrorMessage = "",
                        revision = localUi.revision + 1,
                    )
                    is AndroidAdapterResult.Failure -> localUi.copy(
                        appsLoading = false,
                        appsErrorMessage = tr(R.string.integration_the_app_list_could_not_be_loaded_893b944),
                        revision = localUi.revision + 1,
                    )
                }
                if (appCatalogRefreshRequested) refreshAppDrawer()
            }
        }
    }

    private fun launchFromAppDrawer(packageName: String, profileId: String) {
        // Returning Home after an app launch must reopen Apps as a fresh lookup rather
        // than retaining the query that was used to launch the previous app.
        if (localUi.appQuery.isNotEmpty()) {
            localUi = localUi.copy(
                appQuery = "",
                revision = localUi.revision + 1,
            )
        }
        appCatalogExecutor.execute {
            val result = publicCapabilityAdapter.launchAppInProfile(packageName, profileId)
            if (result is AndroidAdapterResult.Failure) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        showShortMessage(tr(R.string.integration_the_app_could_not_be_opened_2bac6c6))
                    }
                }
            }
        }
    }

    private fun onLauncherCatalogChanged(profileBecameUnavailable: Boolean = false) {
        if (localUi.requestedDestination != HansDestination.APPS) return
        runOnUiThread {
            if (profileBecameUnavailable) purgeNonPersonalAppInventory()
            refreshAppDrawer()
        }
    }

    private fun purgeNonPersonalAppInventory() {
        val retained = personalAppInventoryOnly(localUi.apps, localUi.appProfiles)
        if (
            retained.apps.size == localUi.apps.size &&
            retained.profiles.size == localUi.appProfiles.size
        ) {
            return
        }
        localUi = localUi.copy(
            apps = retained.apps,
            appProfiles = retained.profiles,
            revision = localUi.revision + 1,
        )
    }

    private fun purgePrivateAppInventory() {
        val retainedApps = localUi.apps.filter { it.profileType != LaunchProfileType.PRIVATE }
        if (retainedApps.size == localUi.apps.size) return
        localUi = localUi.copy(
            apps = retainedApps,
            revision = localUi.revision + 1,
        )
    }

    private fun openVerificationPage(rawUrl: String) {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank()) {
            showShortMessage(tr(R.string.integration_the_chatgpt_sign_in_page_is_invalid_e4a66b0))
            return
        }
        val opened = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }.isSuccess
        if (!opened) showShortMessage(tr(R.string.integration_no_browser_is_available_8a69d0e))
    }

    private fun openPluginLink(rawUrl: String) {
        val safeUrl = validatedHttpsPluginLinkOrNull(rawUrl)
        if (safeUrl == null) {
            showShortMessage(tr(R.string.integration_the_plugin_link_is_invalid_2dc8ebf))
            return
        }
        val opened = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)))
        }.isSuccess
        if (!opened) showShortMessage(tr(R.string.integration_no_browser_is_available_8a69d0e))
    }

    private fun openFixedOpenAiPage(rawUrl: String) {
        val uri = Uri.parse(rawUrl)
        val valid = uri.scheme == "https" && uri.host == "platform.openai.com"
        if (!valid) {
            showShortMessage(tr(R.string.integration_the_configured_openai_address_is_invalid_7e7cb27))
            return
        }
        val opened = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }.isSuccess
        if (!opened) showShortMessage(tr(R.string.integration_no_browser_is_available_8a69d0e))
    }

    private fun openCodexUpdate() {
        val result = CodexUpdateLauncher(this, BuildConfig.HANS_UPDATE_URL).launch()
        val message = when (result) {
            CodexUpdateLaunchResult.BUNDLED_WITH_HANS ->
                tr(R.string.integration_codex_is_part_of_hans_and_is_updated_with_a_signed_hans_dd5b3d7)
            CodexUpdateLaunchResult.INVALID_CONFIGURATION ->
                tr(R.string.integration_the_configured_update_address_is_invalid_9de8374)
            CodexUpdateLaunchResult.OFFLINE ->
                tr(R.string.integration_hans_needs_an_internet_connection_to_open_the_update_pa_2fb00a9)
            CodexUpdateLaunchResult.NO_BROWSER ->
                tr(R.string.integration_no_browser_is_available_for_the_update_page_83e916f)
            CodexUpdateLaunchResult.OPENED ->
                tr(R.string.integration_the_hans_update_page_was_opened_d4cb068)
            CodexUpdateLaunchResult.OPEN_FAILED ->
                tr(R.string.integration_the_hans_update_page_could_not_be_opened_e6ca80a)
        }
        showShortMessage(message)
    }

    private fun startRemoteMcpOAuth(pluginId: String, serverId: String) {
        localUi = localUi.copy(
            requestedDestination = HansDestination.PLUGINS,
            revision = localUi.revision + 1,
        )
        val app = application as HansApplication
        val scheduled = runCatching {
            remoteMcpOAuthExecutor.execute {
                val result = app.resolveRemoteMcpOAuthConnectionSpec(pluginId, serverId)?.let { spec ->
                    AndroidRemoteMcpOAuthFlowFactory.create(applicationContext).start(spec)
                } ?: RemoteMcpOAuthStartResult.Failed(
                    pluginId,
                    serverId,
                    RemoteMcpOAuthConnectionFailure.DISCOVERY_REJECTED,
                )
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    when (result) {
                        is RemoteMcpOAuthStartResult.BrowserRequired -> {
                            if (!AndroidRemoteMcpOAuthBrowser.launch(this, result)) {
                                showShortMessage(
                                    remoteMcpOAuthFailureMessage(
                                        RemoteMcpOAuthConnectionFailure.BROWSER_UNAVAILABLE,
                                    ),
                                )
                            }
                        }
                        is RemoteMcpOAuthStartResult.AlreadyConnected -> {
                            pendingRemoteMcpOAuthResult = RemoteMcpOAuthSafeResult.Connected(
                                result.pluginId,
                                result.serverId,
                            )
                            drivePendingRemoteMcpOAuthResult()
                        }
                        is RemoteMcpOAuthStartResult.Failed -> {
                            pendingRemoteMcpOAuthResult = RemoteMcpOAuthSafeResult.Failed(
                                result.pluginId,
                                result.serverId,
                                result.failure,
                            )
                            drivePendingRemoteMcpOAuthResult()
                        }
                    }
                }
            }
        }.isSuccess
        if (!scheduled) {
            showShortMessage(tr(R.string.integration_the_connection_could_not_be_started_right_now_867dcb9))
        }
    }

    /** Removes all three explicit extras before interpreting them, making delivery one-shot. */
    private fun consumeRemoteMcpOAuthResult(resultIntent: Intent?): Boolean {
        resultIntent ?: return false
        val result = RemoteMcpOAuthResultConsumer.consume(
            RemoteMcpOAuthResultExtraSource { name ->
                resultIntent.getStringExtra(name).also { resultIntent.removeExtra(name) }
            },
        ) ?: return false
        pendingRemoteMcpOAuthResult = result
        localUi = localUi.copy(
            requestedDestination = HansDestination.PLUGINS,
            revision = localUi.revision + 1,
        )
        return true
    }

    /** Callback success updates only the Retry affordance. Installation still needs a user tap. */
    private fun drivePendingRemoteMcpOAuthResult() {
        when (val result = pendingRemoteMcpOAuthResult ?: return) {
            is RemoteMcpOAuthSafeResult.Connected -> {
                if (sessionHost.markRemoteMcpOAuthConnected(result.pluginId, result.serverId)) {
                    pendingRemoteMcpOAuthResult = null
                }
            }
            is RemoteMcpOAuthSafeResult.Failed -> {
                pendingRemoteMcpOAuthResult = null
                showShortMessage(remoteMcpOAuthFailureMessage(result.failure))
            }
        }
    }

    private fun remoteMcpOAuthFailureMessage(failure: RemoteMcpOAuthConnectionFailure): String =
        when (failure) {
            RemoteMcpOAuthConnectionFailure.CONNECTION_ALREADY_ACTIVE ->
                tr(R.string.integration_the_connection_is_already_being_completed_in_a_browser_acb8913)
            RemoteMcpOAuthConnectionFailure.CREDENTIAL_STORE_UNAVAILABLE ->
                tr(R.string.integration_secure_credential_storage_is_currently_unavailable_fb2dbcf)
            RemoteMcpOAuthConnectionFailure.DISCOVERY_REJECTED ->
                tr(R.string.integration_the_service_could_not_be_verified_safely_for_sign_in_f43aedc)
            RemoteMcpOAuthConnectionFailure.BROWSER_UNAVAILABLE ->
                tr(R.string.integration_no_browser_was_found_for_sign_in_10137bb)
            RemoteMcpOAuthConnectionFailure.CALLBACK_REJECTED,
            RemoteMcpOAuthConnectionFailure.CALLBACK_EXPIRED,
            RemoteMcpOAuthConnectionFailure.CALLBACK_REPLAYED,
            RemoteMcpOAuthConnectionFailure.AUTHORIZATION_REJECTED,
            RemoteMcpOAuthConnectionFailure.TOKEN_EXCHANGE_FAILED,
            RemoteMcpOAuthConnectionFailure.CREDENTIAL_CHANGED,
            RemoteMcpOAuthConnectionFailure.LOCAL_STATE_UNAVAILABLE,
            -> tr(R.string.integration_the_connection_was_not_confirmed_tap_connect_to_try_aga_5ea469a)
        }

    private fun copyUserCode(code: String) {
        if (code.isBlank()) return
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(tr(R.string.integration_chatgpt_code_0996108), code))
        localUi = localUi.copy(
            supportingMessage = tr(R.string.integration_code_copied_5d79d19),
            revision = localUi.revision + 1,
        )
    }

    private fun saveVoice(
        voice: String = settings.voice,
        speechRate: Float = settings.speechRate,
        readAloudMode: ReadAloudMode = settings.readAloudMode,
    ) {
        settings = runCatching {
            settingsStore.saveVoice(voice, speechRate, readAloudMode)
        }.getOrElse {
            showShortMessage(tr(R.string.integration_the_voice_setting_could_not_be_saved_4bd6e3c))
            settings
        }
    }

    private fun saveLiveVoice(voice: String) {
        settings = runCatching {
            settingsStore.saveCodexLiveVoice(voice)
        }.getOrElse {
            showShortMessage(tr(R.string.integration_the_live_voice_could_not_be_saved_a8f43ef))
            settings
        }
    }

    private fun refreshCapabilityAccess() {
        val environment = AndroidCapabilityEnvironment(this)
        val hasMicrophone = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        val hasAppNotifications = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        val hasExactAlarmAccess = runCatching {
            getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        }.getOrDefault(false)
        fun granted(permission: String): Boolean = ContextCompat.checkSelfPermission(
            this,
            permission,
        ) == PackageManager.PERMISSION_GRANTED
        val hasContacts = granted(Manifest.permission.READ_CONTACTS)
        val hasCalendar = granted(Manifest.permission.READ_CALENDAR) &&
            granted(Manifest.permission.WRITE_CALENDAR)
        val hasLocation = granted(Manifest.permission.ACCESS_COARSE_LOCATION) ||
            granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val hasPhotosAndVideos = when {
            Build.VERSION.SDK_INT >= 34 ->
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ||
                    granted(Manifest.permission.READ_MEDIA_IMAGES) &&
                    granted(Manifest.permission.READ_MEDIA_VIDEO)
            Build.VERSION.SDK_INT >= 33 ->
                granted(Manifest.permission.READ_MEDIA_IMAGES) &&
                    granted(Manifest.permission.READ_MEDIA_VIDEO)
            else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val hasAudioMedia = if (Build.VERSION.SDK_INT >= 33) {
            granted(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val next = listOf(
            CapabilityAccessUiModel(
                id = CapabilityAccessUiId.ALL_FILES,
                title = tr(R.string.integration_file_access_all_files_1511f74),
                description = tr(R.string.integration_load_save_and_manage_files_in_phone_storage_android_per_83a1ef7),
                granted = environment.hasSpecialAccess(AndroidSpecialAccess.ALL_FILES),
            ),
            CapabilityAccessUiModel(
                id = CapabilityAccessUiId.HOME_APP,
                title = tr(R.string.integration_hans_as_home_screen_a9ebbba),
                description = tr(R.string.integration_makes_chat_your_home_screen_the_regular_app_list_remain_881de5b),
                granted = environment.hasSpecialAccess(AndroidSpecialAccess.HOME_ROLE),
            ),
            CapabilityAccessUiModel(
                id = CapabilityAccessUiId.MICROPHONE,
                title = tr(R.string.integration_microphone_303d5cc),
                description = tr(R.string.integration_for_dictation_and_live_voice_80266ac),
                granted = hasMicrophone,
            ),
            CapabilityAccessUiModel(
                id = CapabilityAccessUiId.APP_NOTIFICATIONS,
                title = tr(R.string.integration_hans_notifications_7730f5f),
                description = tr(R.string.integration_makes_ongoing_recording_speech_and_longer_background_wo_e0d0ee7),
                granted = hasAppNotifications,
            ),
            CapabilityAccessUiModel(
                id = CapabilityAccessUiId.NOTIFICATION_ACCESS,
                title = tr(R.string.integration_notification_access_3e8e550),
                description = tr(R.string.integration_allowed_notifications_that_are_not_excluded_are_stored__8f3e625),
                granted = environment.hasSpecialAccess(
                    AndroidSpecialAccess.NOTIFICATION_LISTENER,
                ),
            ),
            CapabilityAccessUiModel(
                id = CapabilityAccessUiId.ACCESSIBILITY,
                title = tr(R.string.integration_app_control_83a211e),
                description = setupAccessibilityDescription(setupRuntime.actionPolicy, AndroidHansTextResolver(this)),
                granted = environment.hasSpecialAccess(
                    AndroidSpecialAccess.ACCESSIBILITY_SERVICE,
                ),
            ),
            CapabilityAccessUiModel(
                id = CapabilityAccessUiId.CONTACTS,
                title = tr(R.string.integration_contacts_bdcd0d6),
                description = tr(R.string.integration_allows_hans_to_find_names_and_contact_details_only_for__f05b902),
                granted = hasContacts,
            ),
            CapabilityAccessUiModel(
                id = CapabilityAccessUiId.CALENDAR,
                title = tr(R.string.integration_read_and_add_calendar_events_5613f6f),
                description = tr(R.string.integration_with_android_calendar_permission_events_can_be_read_and_99fbf29),
                granted = hasCalendar,
            ),
            CapabilityAccessUiModel(
                id = CapabilityAccessUiId.LOCATION,
                title = tr(R.string.integration_location_ecf34cc),
                description = tr(R.string.integration_gives_hans_the_approximate_or_precise_device_location_f_5a00f42),
                granted = hasLocation,
            ),
            CapabilityAccessUiModel(
                id = CapabilityAccessUiId.PHOTOS_AND_VIDEOS,
                title = tr(R.string.integration_photos_and_videos_a2039d3),
                description = tr(R.string.integration_allows_searching_permitted_media_newer_android_versions_e411efd),
                granted = hasPhotosAndVideos,
            ),
            CapabilityAccessUiModel(
                id = CapabilityAccessUiId.AUDIO_MEDIA,
                title = tr(R.string.integration_audio_files_e8523ca),
                description = tr(R.string.integration_allows_hans_to_list_existing_audio_files_at_your_explic_191dfc7),
                granted = hasAudioMedia,
            ),
            CapabilityAccessUiModel(
                id = CapabilityAccessUiId.QUICK_SETTINGS_TILE,
                title = tr(R.string.integration_dictation_tile_alternative_24e2d86),
                description = tr(R.string.integration_starts_dictation_from_a_quick_settings_tile_when_androi_9a15438),
                // Android exposes no reliable read API for whether a user later removed a tile.
                // Keep this setup action available and let requestAddTileService deduplicate it.
                granted = false,
            ),
            CapabilityAccessUiModel(
                id = CapabilityAccessUiId.EXACT_ALARMS,
                title = tr(R.string.integration_exact_alarm_automations_7a51cf7),
                description = tr(R.string.integration_optional_only_for_explicitly_time_critical_alarms_or_ca_5b98acd),
                granted = hasExactAlarmAccess,
            ),
        )
        if (localUi.capabilityAccess != next) {
            localUi = localUi.copy(
                capabilityAccess = next,
                revision = localUi.revision + 1,
            )
        }
    }

    private fun refreshPersistentAndroidConsents() {
        val store = (application as HansApplication).persistentAndroidConsentStore
        val next = store.active()
            .sortedBy(PersistentAndroidConsentDescriptor::localDisplayKey)
            .map(::persistentAndroidConsentUiModel)
        val bundleActive = store.hasEverydayBundle()
        if (
            localUi.persistentAndroidConsents != next ||
            localUi.everydayAccessBundleActive != bundleActive
        ) {
            localUi = localUi.copy(
                everydayAccessBundleActive = bundleActive,
                persistentAndroidConsents = next,
                revision = localUi.revision + 1,
            )
        }
    }

    private fun persistentAndroidConsentUiModel(
        descriptor: PersistentAndroidConsentDescriptor,
    ): PersistentAndroidConsentUiModel {
        val title = when (descriptor.scope) {
            PersistentAndroidConsentScope.INSTALLED_APPS_READ -> tr(R.string.integration_read_installed_apps_ae8f2e4)
            PersistentAndroidConsentScope.OPEN_APP -> tr(R.string.integration_open_apps_visibly_f19c46e)
            PersistentAndroidConsentScope.OPEN_SAFE_NAVIGATION ->
                tr(R.string.integration_open_safe_links_and_navigation_43533e2)
            PersistentAndroidConsentScope.OPEN_SETTINGS_PAGE ->
                tr(R.string.integration_open_wi_fi_and_bluetooth_settings_3030d9b)
            PersistentAndroidConsentScope.READ_LOCATION -> tr(R.string.integration_read_location_501a45e)
            PersistentAndroidConsentScope.READ_CONTACTS -> tr(R.string.integration_read_contacts_84ab646)
            PersistentAndroidConsentScope.READ_CALENDAR -> tr(R.string.integration_read_calendar_73aa1cd)
            PersistentAndroidConsentScope.READ_MEDIA -> tr(R.string.integration_read_media_catalog_11f36da)
            PersistentAndroidConsentScope.READ_SENSORS -> tr(R.string.integration_read_sensors_3a37a54)
            PersistentAndroidConsentScope.READ_REPLYABLE_NOTIFICATIONS ->
                tr(R.string.integration_read_replyable_notifications_510ed60)
            PersistentAndroidConsentScope.NOTIFICATION_LINK_METADATA ->
                tr(R.string.integration_public_link_previews_for_important_notifications_d3f52db)
            PersistentAndroidConsentScope.OPEN_CAMERA -> tr(R.string.integration_open_camera_visibly_ae0696e)
            PersistentAndroidConsentScope.PREPARE_CALENDAR_EVENT ->
                tr(R.string.integration_open_calendar_draft_visibly_4019d53)
        }
        val description = when (descriptor.scope) {
            PersistentAndroidConsentScope.INSTALLED_APPS_READ ->
                tr(R.string.integration_hans_may_check_which_apps_are_installed_and_launchable__d3be57f)
            PersistentAndroidConsentScope.NOTIFICATION_LINK_METADATA ->
                tr(R.string.integration_hans_may_fetch_safe_public_https_pages_for_their_title__1d4c400)
            else -> tr(R.string.integration_applies_to_this_hans_action_category_until_you_revoke_i_fe4111c)
        }
        return PersistentAndroidConsentUiModel(descriptor, title, description)
    }

    private fun revokePersistentAndroidConsent(
        descriptor: PersistentAndroidConsentDescriptor,
    ) {
        val revoked = (application as HansApplication).persistentAndroidConsentStore.revoke(descriptor)
        ai.hans.standard.notifications.NotificationTriageIntegration
            .preemptForEnrichmentAuthorityChange()
        refreshPersistentAndroidConsents()
        showShortMessage(
            if (revoked) {
                tr(R.string.integration_persistent_permission_revoked_bd44093)
            } else {
                tr(R.string.integration_the_persistent_permission_could_not_be_revoked_safely_432091a)
            },
        )
    }

    private fun revokeAllPersistentAndroidConsents() {
        val revoked = (application as HansApplication).persistentAndroidConsentStore.revokeAll()
        ai.hans.standard.notifications.NotificationTriageIntegration
            .preemptForEnrichmentAuthorityChange()
        refreshPersistentAndroidConsents()
        showShortMessage(
            if (revoked) {
                tr(R.string.integration_all_persistent_permissions_were_revoked_ee188be)
            } else {
                tr(R.string.integration_persistent_permissions_could_not_be_revoked_safely_736acf7)
            },
        )
    }

    private fun showEverydayAccessConfirmation(
        token: HansSetupOperationToken? = null,
        onResult: (Boolean) -> Unit,
    ) {
        if (
            isFinishing ||
            isDestroyed ||
            !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        ) {
            onResult(false)
            return
        }
        if (setupEverydayAccessDialog?.isShowing == true) {
            onResult(false)
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(tr(R.string.integration_enable_everyday_access_for_hans_ce0aacf))
            .setMessage(
                tr(R.string.integration_hans_will_be_allowed_to_repeatedly_read_installed_apps__091b08f),
            )
            .setNegativeButton(tr(R.string.integration_cancel_f7ff117)) { _, _ -> onResult(false) }
            .setOnCancelListener { onResult(false) }
            .setPositiveButton(tr(R.string.integration_enable_cfc6942)) { _, _ ->
                val store = (application as HansApplication).persistentAndroidConsentStore
                runCatching {
                    mediaExecutor.execute {
                        val active = runCatching {
                            store.grantAll(PersistentAndroidConsentCatalog.EVERYDAY_DESCRIPTORS) &&
                                store.hasEverydayBundle()
                        }.getOrDefault(false)
                        runOnUiThread {
                            refreshPersistentAndroidConsents()
                            onResult(active)
                        }
                    }
                }.onFailure { onResult(false) }
            }
            .create()
        setupEverydayAccessDialog = dialog
        setupEverydayAccessDialogToken = token
        dialog.setOnDismissListener {
            if (setupEverydayAccessDialog === dialog) {
                setupEverydayAccessDialog = null
                setupEverydayAccessDialogToken = null
            }
        }
        dialog.show()
    }

    private fun showNotificationLinkMetadataConsent(
        token: HansSetupOperationToken? = null,
        onResult: (Boolean) -> Unit = {},
    ) {
        if (
            isFinishing ||
            isDestroyed ||
            !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) ||
            notificationLinkMetadataDialog?.isShowing == true
        ) {
            onResult(false)
            return
        }
        val descriptor = PersistentAndroidConsentDescriptor.category(
            PersistentAndroidConsentScope.NOTIFICATION_LINK_METADATA,
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle(tr(R.string.integration_enable_link_previews_for_important_notifications_b352dc8))
            .setMessage(
                tr(R.string.integration_if_an_allowed_notification_appears_clearly_important_an_8d93dbc),
            )
            .setNegativeButton(tr(R.string.integration_keep_disabled_07135fa)) { _, _ -> onResult(false) }
            .setOnCancelListener { onResult(false) }
            .setPositiveButton(tr(R.string.integration_explicitly_enable_9fe822c)) { _, _ ->
                runCatching {
                    mediaExecutor.execute {
                        val granted = (application as HansApplication)
                            .persistentAndroidConsentStore
                            .grant(descriptor)
                        runOnUiThread {
                            refreshPersistentAndroidConsents()
                            showShortMessage(
                                if (granted) tr(R.string.integration_link_previews_are_active_02ad08b)
                                else tr(R.string.integration_link_previews_could_not_be_enabled_safely_18dfb24),
                            )
                            onResult(granted)
                        }
                    }
                }.onFailure { onResult(false) }
            }
            .create()
        notificationLinkMetadataDialog = dialog
        notificationLinkMetadataDialogToken = token
        dialog.setOnDismissListener {
            if (notificationLinkMetadataDialog === dialog) {
                notificationLinkMetadataDialog = null
                notificationLinkMetadataDialogToken = null
            }
        }
        dialog.show()
    }

    private fun requestCapabilityAccess(id: CapabilityAccessUiId) {
        when (id) {
            CapabilityAccessUiId.ALL_FILES -> {
                if (!openCapabilitySettings(SettingsDestination.ALL_FILES)) {
                    showShortMessage(tr(R.string.integration_android_file_access_settings_could_not_be_opened_56ac6b2))
                }
            }
            CapabilityAccessUiId.MICROPHONE -> {
                pendingMicrophoneCommand = null
                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            CapabilityAccessUiId.APP_NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    refreshCapabilityAccess()
                }
            }
            CapabilityAccessUiId.HOME_APP -> openCapabilitySettings(SettingsDestination.HOME_APP)
            CapabilityAccessUiId.NOTIFICATION_ACCESS ->
                openCapabilitySettings(SettingsDestination.NOTIFICATION_LISTENER)
            CapabilityAccessUiId.ACCESSIBILITY ->
                openCapabilitySettings(SettingsDestination.ACCESSIBILITY)
            CapabilityAccessUiId.CONTACTS -> requestPublicPhonePermissions(
                arrayOf(Manifest.permission.READ_CONTACTS),
            )
            CapabilityAccessUiId.CALENDAR -> requestPublicPhonePermissions(
                arrayOf(
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR,
                ),
            )
            CapabilityAccessUiId.LOCATION -> requestPublicPhonePermissions(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ),
            )
            CapabilityAccessUiId.PHOTOS_AND_VIDEOS -> requestPublicPhonePermissions(
                when {
                    Build.VERSION.SDK_INT >= 34 -> arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                    )
                    Build.VERSION.SDK_INT >= 33 -> arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO,
                    )
                    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                },
            )
            CapabilityAccessUiId.AUDIO_MEDIA -> requestPublicPhonePermissions(
                if (Build.VERSION.SDK_INT >= 33) {
                    arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
                } else {
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                },
            )
            CapabilityAccessUiId.QUICK_SETTINGS_TILE -> requestDictationQuickSettingsTile()
            CapabilityAccessUiId.EXACT_ALARMS -> requestExactAlarmAccess()
        }
    }

    private fun setupCapabilityFor(
        command: SetupUiCommand.OpenSettings,
    ): CapabilityAccessUiId? {
        if (command.token.step == HansSetupStep.OPTIONAL_CAPABILITIES) {
            return when (command.optionalCapability ?: return null) {
                HansSetupOptionalCapability.EVERYDAY_ACCESS -> null
                HansSetupOptionalCapability.ALL_FILES -> CapabilityAccessUiId.ALL_FILES
                HansSetupOptionalCapability.NOTIFICATION_LINK_METADATA -> null
                HansSetupOptionalCapability.CONTACTS -> CapabilityAccessUiId.CONTACTS
                HansSetupOptionalCapability.CALENDAR -> CapabilityAccessUiId.CALENDAR
                HansSetupOptionalCapability.LOCATION -> CapabilityAccessUiId.LOCATION
                HansSetupOptionalCapability.PHOTOS_VIDEOS ->
                    CapabilityAccessUiId.PHOTOS_AND_VIDEOS
                HansSetupOptionalCapability.AUDIO_MEDIA -> CapabilityAccessUiId.AUDIO_MEDIA
                HansSetupOptionalCapability.EXACT_ALARMS -> CapabilityAccessUiId.EXACT_ALARMS
                HansSetupOptionalCapability.QUICK_SETTINGS_TILE ->
                    CapabilityAccessUiId.QUICK_SETTINGS_TILE
            }
        }
        if (command.optionalCapability != null) return null
        return when (command.token.step) {
            HansSetupStep.APP_NOTIFICATIONS_ACCESS -> CapabilityAccessUiId.APP_NOTIFICATIONS
            HansSetupStep.NOTIFICATION_ACCESS -> CapabilityAccessUiId.NOTIFICATION_ACCESS
            HansSetupStep.ACCESSIBILITY_ACCESS -> CapabilityAccessUiId.ACCESSIBILITY
            HansSetupStep.HOME_ROLE -> CapabilityAccessUiId.HOME_APP
            HansSetupStep.MICROPHONE_ACCESS -> CapabilityAccessUiId.MICROPHONE
            else -> null
        }
    }

    /**
     * Dispatches only the one Android interaction explicitly enabled in conversation.
     * Runtime-permission callbacks return a fresh effective-state receipt asynchronously.
     */
    private fun requestSetupCapabilityAccess(
        token: HansSetupOperationToken,
        id: CapabilityAccessUiId,
        completion: (SetupUiCommandResult) -> Unit,
    ) {
        if (id in SETUP_RUNTIME_PERMISSION_CAPABILITIES) {
            if (pendingSetupPermissionInteraction != null) {
                completion(SetupUiCommandResult.Rejected("setup_permission_request_already_active"))
                return
            }
            if (isCapabilityAccessEffective(id)) {
                refreshCapabilityAccess()
                completion(
                    SetupUiCommandResult.Accepted(
                        settingsOpened = false,
                        liveVerificationAccepted = true,
                    ),
                )
                return
            }
            val permissions = setupRuntimePermissions(id)
            if (permissions.isEmpty()) {
                completion(SetupUiCommandResult.Rejected("setup_permission_not_supported"))
                return
            }
            pendingSetupPermissionInteraction = PendingSetupPermissionInteraction(
                token,
                id,
                completion,
            )
            val launched = runCatching {
                when (id) {
                    CapabilityAccessUiId.MICROPHONE -> {
                        pendingMicrophoneCommand = null
                        microphonePermission.launch(permissions.single())
                    }
                    CapabilityAccessUiId.APP_NOTIFICATIONS ->
                        notificationPermission.launch(permissions.single())
                    else -> publicPhonePermissions.launch(permissions)
                }
            }.isSuccess
            if (!launched) {
                pendingSetupPermissionInteraction = null
                completion(SetupUiCommandResult.Rejected("setup_permission_ui_not_opened"))
            }
            return
        }

        when (id) {
            CapabilityAccessUiId.HOME_APP -> completeSettingsDispatch(
                openCapabilitySettings(SettingsDestination.HOME_APP),
                completion,
            )
            CapabilityAccessUiId.ALL_FILES -> completeSettingsDispatch(
                openCapabilitySettings(SettingsDestination.ALL_FILES), completion,
            )
            CapabilityAccessUiId.NOTIFICATION_ACCESS -> requestRestrictedSettingsSetupAccess(
                token = token,
                capability = RestrictedSettingsCapability.NOTIFICATION_LISTENER,
                completion = completion,
            )
            CapabilityAccessUiId.ACCESSIBILITY -> requestRestrictedSettingsSetupAccess(
                token = token,
                capability = RestrictedSettingsCapability.ACCESSIBILITY,
                completion = completion,
            )
            CapabilityAccessUiId.EXACT_ALARMS -> {
                if (isCapabilityAccessEffective(id)) {
                    completion(
                        SetupUiCommandResult.Accepted(
                            settingsOpened = false,
                            liveVerificationAccepted = true,
                        ),
                    )
                } else {
                    completeSettingsDispatch(
                        openCapabilitySettings(SettingsDestination.EXACT_ALARM),
                        completion,
                    )
                }
            }
            CapabilityAccessUiId.QUICK_SETTINGS_TILE ->
                requestDictationQuickSettingsTile(token, completion)
            else -> completion(SetupUiCommandResult.Rejected("setup_capability_route_missing"))
        }
    }

    private fun completeSettingsDispatch(
        opened: Boolean,
        completion: (SetupUiCommandResult) -> Unit,
    ) {
        completion(
            if (opened) {
                SetupUiCommandResult.Accepted(settingsOpened = true)
            } else {
                SetupUiCommandResult.Rejected("settings_ui_not_opened")
            },
        )
    }

    private fun requestRestrictedSettingsSetupAccess(
        token: HansSetupOperationToken,
        capability: RestrictedSettingsCapability,
        completion: (SetupUiCommandResult) -> Unit,
    ) {
        val destination = capability.settingsDestination()
        // Android 12 has no sideloaded-app Restricted Settings recovery contract. Preserve the
        // existing single public Settings route byte-for-byte at the behavioral boundary.
        if (Build.VERSION.SDK_INT < RestrictedSettingsRecoveryPolicy.MIN_RECOVERY_API) {
            completeSettingsDispatch(openCapabilitySettings(destination), completion)
            return
        }
        if (restrictedSettingsRecovery != null) {
            completion(
                SetupUiCommandResult.Rejected(
                    "restricted_settings_recovery_already_active",
                ),
            )
            return
        }
        if (isCapabilityAccessEffective(capability.capabilityAccessId())) {
            refreshCapabilityAccess()
            completion(
                SetupUiCommandResult.Accepted(
                    settingsOpened = false,
                    liveVerificationAccepted = true,
                ),
            )
            return
        }

        val recovery = RestrictedSettingsRecoveryPolicy.begin(
            apiLevel = Build.VERSION.SDK_INT,
            capability = capability,
            token = token,
        )
        restrictedSettingsRecovery = recovery
        if (openCapabilitySettings(destination)) {
            completion(SetupUiCommandResult.Accepted(settingsOpened = true))
        } else {
            restrictedSettingsRecovery = null
            completion(SetupUiCommandResult.Rejected("settings_ui_not_opened"))
        }
    }

    /** Event-driven only: each external Settings return advances at most one bounded phase. */
    private fun continueRestrictedSettingsRecoveryAfterReturn() {
        val recovery = restrictedSettingsRecovery ?: return
        applyRestrictedSettingsRecoveryTransition(
            RestrictedSettingsRecoveryPolicy.onActivityReturned(
                recovery,
                effectiveAccess = isCapabilityAccessEffective(
                    recovery.capability.capabilityAccessId(),
                ),
            ),
        )
    }

    private fun applyRestrictedSettingsRecoveryTransition(
        transition: RestrictedSettingsRecoveryTransition,
    ) {
        restrictedSettingsRecovery = transition.state
        when (val effect = transition.effect) {
            RestrictedSettingsRecoveryEffect.None,
            RestrictedSettingsRecoveryEffect.LegacyAndroidUnchanged,
            -> Unit

            is RestrictedSettingsRecoveryEffect.Verified -> {
                val setupRecorded = runCatching {
                    setupRuntime.acceptRestrictedSettingsRecovery(effect.token)
                }.isSuccess
                refreshCapabilityAccess()
                showShortMessage(
                    if (setupRecorded) {
                        RestrictedSettingsRecoveryCopy.verified(effect.capability, AndroidHansTextResolver(this))
                    } else {
                        tr(R.string.integration_access_is_active_hans_is_checking_the_current_setup_ste_9df67f8)
                    },
                )
            }

            is RestrictedSettingsRecoveryEffect.ShowExplanation ->
                showRestrictedSettingsExplanation(effect.capability)

            is RestrictedSettingsRecoveryEffect.OpenAppDetails -> {
                val opened = openCapabilitySettings(SettingsDestination.APP_DETAILS)
                if (!opened) restrictedSettingsRecovery?.let { recovery ->
                    applyRestrictedSettingsRecoveryTransition(
                        RestrictedSettingsRecoveryPolicy.settingsDispatchFailed(recovery),
                    )
                }
            }

            is RestrictedSettingsRecoveryEffect.OpenCapabilitySettings -> {
                val opened = openCapabilitySettings(effect.capability.settingsDestination())
                if (!opened) restrictedSettingsRecovery?.let { recovery ->
                    applyRestrictedSettingsRecoveryTransition(
                        RestrictedSettingsRecoveryPolicy.settingsDispatchFailed(recovery),
                    )
                }
            }

            is RestrictedSettingsRecoveryEffect.ShowManualRequired -> {
                if (restrictedSettingsRecoveryDialog?.isShowing != true) {
                    runCatching {
                        setupRuntime.markRestrictedSettingsManualRequired(effect.token)
                    }
                    showRestrictedSettingsManualRequired(effect.capability)
                }
            }

            is RestrictedSettingsRecoveryEffect.NotNow -> {
                val recorded = runCatching {
                    setupRuntime.recordRestrictedSettingsNotNow(effect.step)
                }.isSuccess
                showShortMessage(
                    if (recorded) {
                        tr(R.string.integration_skipped_for_now_you_can_set_up_access_later_28d033e)
                    } else {
                        tr(R.string.integration_the_setup_step_changed_and_was_not_skipped_ee2ea83)
                    },
                )
            }
        }
    }

    private fun showRestrictedSettingsExplanation(
        capability: RestrictedSettingsCapability,
    ) {
        if (restrictedSettingsRecoveryDialog?.isShowing == true) return
        val recovery = restrictedSettingsRecovery
            ?.takeIf {
                it.capability == capability &&
                    it.stage == RestrictedSettingsRecoveryStage.EXPLANATION_REQUIRED
            }
            ?: return
        var handled = false
        val dialog = AlertDialog.Builder(this)
            .setTitle(tr(R.string.integration_android_may_be_blocking_access_0bf9d05))
            .setMessage(RestrictedSettingsRecoveryCopy.explanation(capability, AndroidHansTextResolver(this)))
            .setNegativeButton(RestrictedSettingsRecoveryCopy.notNowLabel(AndroidHansTextResolver(this))) { _, _ ->
                if (!handled) {
                    handled = true
                    applyRestrictedSettingsRecoveryTransition(
                        RestrictedSettingsRecoveryPolicy.notNow(recovery),
                    )
                }
            }
            .setPositiveButton(RestrictedSettingsRecoveryCopy.positiveLabel(AndroidHansTextResolver(this))) { _, _ ->
                if (!handled) {
                    handled = true
                    applyRestrictedSettingsRecoveryTransition(
                        RestrictedSettingsRecoveryPolicy.openAppDetails(recovery),
                    )
                }
            }
            .setOnCancelListener {
                if (!handled) {
                    handled = true
                    applyRestrictedSettingsRecoveryTransition(
                        RestrictedSettingsRecoveryPolicy.notNow(recovery),
                    )
                }
            }
            .create()
        restrictedSettingsRecoveryDialog = dialog
        dialog.setOnDismissListener {
            if (restrictedSettingsRecoveryDialog === dialog) {
                restrictedSettingsRecoveryDialog = null
            }
        }
        dialog.show()
    }

    private fun showRestrictedSettingsManualRequired(
        capability: RestrictedSettingsCapability,
    ) {
        if (restrictedSettingsRecoveryDialog?.isShowing == true) return
        val recovery = restrictedSettingsRecovery
            ?.takeIf {
                it.capability == capability &&
                    it.stage == RestrictedSettingsRecoveryStage.MANUAL_REQUIRED
            }
            ?: return
        var handled = false
        val dialog = AlertDialog.Builder(this)
            .setTitle(tr(R.string.integration_access_is_not_active_ca9115c))
            .setMessage(RestrictedSettingsRecoveryCopy.manualRequired(capability, AndroidHansTextResolver(this)))
            .setNegativeButton(RestrictedSettingsRecoveryCopy.notNowLabel(AndroidHansTextResolver(this))) { _, _ ->
                if (!handled) {
                    handled = true
                    applyRestrictedSettingsRecoveryTransition(
                        RestrictedSettingsRecoveryPolicy.notNow(recovery),
                    )
                }
            }
            .setOnCancelListener {
                if (!handled) {
                    handled = true
                    applyRestrictedSettingsRecoveryTransition(
                        RestrictedSettingsRecoveryPolicy.notNow(recovery),
                    )
                }
            }
            .create()
        restrictedSettingsRecoveryDialog = dialog
        dialog.setOnDismissListener {
            if (restrictedSettingsRecoveryDialog === dialog) {
                restrictedSettingsRecoveryDialog = null
            }
        }
        dialog.show()
    }

    private fun RestrictedSettingsCapability.capabilityAccessId(): CapabilityAccessUiId =
        when (this) {
            RestrictedSettingsCapability.ACCESSIBILITY -> CapabilityAccessUiId.ACCESSIBILITY
            RestrictedSettingsCapability.NOTIFICATION_LISTENER ->
                CapabilityAccessUiId.NOTIFICATION_ACCESS
        }

    private fun RestrictedSettingsCapability.settingsDestination(): SettingsDestination =
        when (this) {
            RestrictedSettingsCapability.ACCESSIBILITY -> SettingsDestination.ACCESSIBILITY
            RestrictedSettingsCapability.NOTIFICATION_LISTENER ->
                SettingsDestination.NOTIFICATION_LISTENER
        }

    private fun completePendingSetupPermission(
        capability: CapabilityAccessUiId,
        effective: Boolean,
    ): Boolean {
        val pending = pendingSetupPermissionInteraction
            ?.takeIf { it.capability == capability }
            ?: return false
        pendingSetupPermissionInteraction = null
        pending.completion?.invoke(
            SetupUiCommandResult.Accepted(
                settingsOpened = true,
                liveVerificationAccepted = effective,
            ),
        )
        return true
    }

    private fun setupRuntimePermissions(id: CapabilityAccessUiId): Array<String> =
        SetupPlatformPermissionContract.permissionsFor(id, Build.VERSION.SDK_INT).toTypedArray()

    private fun isCapabilityAccessEffective(id: CapabilityAccessUiId): Boolean {
        fun granted(permission: String): Boolean = ContextCompat.checkSelfPermission(
            this,
            permission,
        ) == PackageManager.PERMISSION_GRANTED
        return when (id) {
            CapabilityAccessUiId.ALL_FILES -> AndroidCapabilityEnvironment(this)
                .hasSpecialAccess(AndroidSpecialAccess.ALL_FILES)
            CapabilityAccessUiId.MICROPHONE -> granted(Manifest.permission.RECORD_AUDIO)
            CapabilityAccessUiId.APP_NOTIFICATIONS ->
                Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)
            CapabilityAccessUiId.CONTACTS -> granted(Manifest.permission.READ_CONTACTS)
            CapabilityAccessUiId.CALENDAR ->
                granted(Manifest.permission.READ_CALENDAR) &&
                    granted(Manifest.permission.WRITE_CALENDAR)
            CapabilityAccessUiId.LOCATION ->
                granted(Manifest.permission.ACCESS_COARSE_LOCATION) ||
                    granted(Manifest.permission.ACCESS_FINE_LOCATION)
            CapabilityAccessUiId.PHOTOS_AND_VIDEOS -> when {
                Build.VERSION.SDK_INT >= 34 ->
                    granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ||
                        granted(Manifest.permission.READ_MEDIA_IMAGES) &&
                        granted(Manifest.permission.READ_MEDIA_VIDEO)
                Build.VERSION.SDK_INT >= 33 ->
                    granted(Manifest.permission.READ_MEDIA_IMAGES) &&
                        granted(Manifest.permission.READ_MEDIA_VIDEO)
                else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            CapabilityAccessUiId.AUDIO_MEDIA -> if (Build.VERSION.SDK_INT >= 33) {
                granted(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                granted(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            CapabilityAccessUiId.EXACT_ALARMS -> runCatching {
                getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
            }.getOrDefault(false)
            CapabilityAccessUiId.NOTIFICATION_ACCESS -> AndroidCapabilityEnvironment(this)
                .hasSpecialAccess(AndroidSpecialAccess.NOTIFICATION_LISTENER)
            CapabilityAccessUiId.ACCESSIBILITY -> AndroidCapabilityEnvironment(this)
                .hasSpecialAccess(AndroidSpecialAccess.ACCESSIBILITY_SERVICE)
            else -> false
        }
    }

    private fun requestPublicPhonePermissions(permissions: Array<String>) {
        val missing = permissions.filterTo(mutableListOf()) { permission ->
            ContextCompat.checkSelfPermission(this, permission) !=
                PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            refreshCapabilityAccess()
        } else {
            publicPhonePermissions.launch(missing.toTypedArray())
        }
    }

    private fun requestExactAlarmAccess() {
        if (!openCapabilitySettings(SettingsDestination.EXACT_ALARM)) {
            showShortMessage(tr(R.string.integration_the_exact_alarm_setting_is_unavailable_002549f))
        }
    }

    private fun requestDictationQuickSettingsTile(
        setupToken: HansSetupOperationToken? = null,
        setupCompletion: ((SetupUiCommandResult) -> Unit)? = null,
    ) {
        if (Build.VERSION.SDK_INT < 33) {
            showShortMessage(tr(R.string.integration_open_the_tile_editor_and_add_hans_dictation_da38beb))
            setupCompletion?.invoke(
                SetupQuickSettingsTileContract.receipt(Build.VERSION.SDK_INT, null),
            )
            return
        }
        val manager = getSystemService(StatusBarManager::class.java)
        if (manager == null) {
            showShortMessage(tr(R.string.integration_the_tile_editor_is_unavailable_on_this_phone_e5b24c0))
            setupCompletion?.invoke(
                SetupUiCommandResult.Rejected("quick_settings_tile_api_unavailable"),
            )
            return
        }
        if (setupCompletion != null && pendingSetupQuickSettingsTile != null) {
            setupCompletion(
                SetupUiCommandResult.Rejected("quick_settings_tile_request_already_active"),
            )
            return
        }
        val setupRequest = setupCompletion?.let { completion ->
            PendingSetupQuickSettingsTile(
                token = checkNotNull(setupToken),
                completion = completion,
            ).also { pendingSetupQuickSettingsTile = it }
        }
        val launched = runCatching {
            manager.requestAddTileService(
                ComponentName(this, HansDictationTileService::class.java),
                getString(R.string.dictation_tile_label),
                Icon.createWithResource(this, R.drawable.ic_hans_dictation_tile),
                mainExecutor,
            ) { result ->
                val message = when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                        tr(R.string.integration_hans_dictation_was_added_to_quick_settings_2431b1b)
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                        tr(R.string.integration_hans_dictation_is_already_in_quick_settings_ce59ac4)
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED ->
                        tr(R.string.integration_the_dictation_tile_was_not_added_8dfd376)
                    else -> tr(R.string.integration_the_tile_editor_completed_the_request_b6cb7e7)
                }
                showShortMessage(message)
                val pendingCompletion = if (setupRequest != null) {
                    pendingSetupQuickSettingsTile
                        ?.takeIf { it === setupRequest }
                        ?.also { pendingSetupQuickSettingsTile = null }
                        ?.completion
                } else {
                    null
                }
                pendingCompletion?.invoke(
                    SetupQuickSettingsTileContract.receipt(Build.VERSION.SDK_INT, result),
                )
            }
        }.isSuccess
        if (!launched) {
            if (pendingSetupQuickSettingsTile === setupRequest) {
                pendingSetupQuickSettingsTile = null
            }
            showShortMessage(tr(R.string.integration_the_tile_editor_could_not_be_opened_e426734))
            setupCompletion?.invoke(
                SetupUiCommandResult.Rejected("quick_settings_tile_request_failed"),
            )
        }
    }

    private fun openCapabilitySettings(destination: SettingsDestination): Boolean {
        val result = publicCapabilityAdapter.openSettings(
            destination = destination,
            packageName = if (
                destination in setOf(
                    SettingsDestination.APP_DETAILS,
                    SettingsDestination.NOTIFICATION_LISTENER,
                    SettingsDestination.ACCESSIBILITY,
                    SettingsDestination.EXACT_ALARM,
                )
            ) {
                packageName
            } else {
                null
            },
        )
        if (result is AndroidAdapterResult.Failure) {
            showShortMessage(tr(R.string.integration_the_appropriate_android_setting_could_not_be_opened_84dbf86))
            return false
        }
        return true
    }

    private fun launchCameraCapture() {
        when (val preparation = cameraCaptureCoordinator.prepareCapture()) {
            CameraCapturePreparation.CameraUnavailable -> {
                showShortMessage(tr(R.string.integration_no_camera_app_is_available_on_this_phone_aa4106a))
            }
            CameraCapturePreparation.StorageUnavailable -> {
                showShortMessage(tr(R.string.integration_the_photo_could_not_be_prepared_safely_4bf235a))
            }
            is CameraCapturePreparation.Ready -> {
                try {
                    cameraCaptureLauncher.launch(preparation.outputUri)
                } catch (_: ActivityNotFoundException) {
                    cameraCaptureCoordinator.abandon(preparation)
                    showShortMessage(tr(R.string.integration_no_camera_app_is_available_on_this_phone_aa4106a))
                } catch (_: SecurityException) {
                    cameraCaptureCoordinator.abandon(preparation)
                    showShortMessage(tr(R.string.integration_the_camera_could_not_be_opened_safely_1578ad8))
                } catch (_: IllegalArgumentException) {
                    cameraCaptureCoordinator.abandon(preparation)
                    showShortMessage(tr(R.string.integration_the_camera_could_not_be_opened_safely_1578ad8))
                }
            }
        }
    }

    private fun showCameraCaptureChoice() {
        if (cameraCaptureChoiceDialog?.isShowing == true) return
        val choices = CameraCaptureSelectionPolicy.choices
        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(this)
            .setTitle(tr(R.string.integration_camera_32edeec))
            .setItems(choices.map { it.label }.toTypedArray()) { _, index ->
                when (choices.getOrNull(index)?.kind) {
                    CameraCaptureKind.PHOTO -> launchCameraCapture()
                    CameraCaptureKind.VIDEO -> launchVideoCapture()
                    null -> Unit
                }
            }
            .setNegativeButton(tr(R.string.integration_cancel_f7ff117), null)
            .create()
        dialog.setOnDismissListener {
            if (cameraCaptureChoiceDialog === dialog) cameraCaptureChoiceDialog = null
        }
        cameraCaptureChoiceDialog = dialog
        dialog.show()
    }

    private fun launchVideoCapture() {
        when (val preparation = cameraCaptureCoordinator.prepareVideoCapture()) {
            CameraCapturePreparation.CameraUnavailable -> {
                showShortMessage(tr(R.string.integration_no_video_camera_app_is_available_on_this_phone_4f5a51c))
            }
            CameraCapturePreparation.StorageUnavailable -> {
                showShortMessage(tr(R.string.integration_the_video_could_not_be_prepared_safely_cc2f494))
            }
            is CameraCapturePreparation.Ready -> {
                try {
                    videoCaptureLauncher.launch(preparation.outputUri)
                } catch (_: ActivityNotFoundException) {
                    cameraCaptureCoordinator.abandon(preparation)
                    showShortMessage(tr(R.string.integration_no_video_camera_app_is_available_on_this_phone_4f5a51c))
                } catch (_: SecurityException) {
                    cameraCaptureCoordinator.abandon(preparation)
                    showShortMessage(tr(R.string.integration_the_video_camera_could_not_be_opened_safely_f6eb845))
                } catch (_: IllegalArgumentException) {
                    cameraCaptureCoordinator.abandon(preparation)
                    showShortMessage(tr(R.string.integration_the_video_camera_could_not_be_opened_safely_f6eb845))
                }
            }
        }
    }

    private fun importCapturedPhoto(photo: CapturedCameraPhoto) {
        importPrivateMedia(photo.contentUri, expectedKind = ai.hans.standard.media.MediaKind.IMAGE) {
            cameraCaptureCoordinator.completeImport(photo)
        }
    }

    private fun importCapturedVideo(video: CapturedCameraVideo) {
        importPrivateMedia(video.contentUri, expectedKind = ai.hans.standard.media.MediaKind.VIDEO) {
            cameraCaptureCoordinator.completeImport(video)
        }
    }

    private fun importPrivateMedia(
        uri: Uri,
        expectedKind: ai.hans.standard.media.MediaKind? = null,
        afterImport: () -> Unit = {},
    ) {
        showShortMessage(tr(R.string.integration_preparing_media_safely_01e2fab))
        mediaExecutor.execute {
            val result = runCatching { mediaPipeline.import(uri, expectedKind) }
            runCatching(afterImport)
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    result.getOrNull()?.let { imported ->
                        mediaExecutor.execute { mediaPipeline.delete(imported.importId) }
                    }
                    return@runOnUiThread
                }
                result.onSuccess(::acceptImportedMedia).onFailure { failure ->
                    showShortMessage(mediaFailureMessage(failure))
                }
            }
        }
    }

    private fun acceptImportedMedia(imported: MediaImportResult) {
        when (imported) {
            is MediaImportResult.Image -> {
                localUi = localUi.copy(
                    attachments = localUi.attachments + HansPendingAttachment(
                        id = imported.importId,
                        label = imported.displayLabel,
                        absolutePath = imported.image.absolutePath,
                        importId = imported.importId,
                    ),
                    revision = localUi.revision + 1,
                )
            }
            is MediaImportResult.Video -> acceptImportedVideo(imported)
        }
    }

    private fun acceptImportedVideo(video: MediaImportResult.Video) {
        val request = video.toTranscriptionRequest()
        if (request == null) {
            addImportedVideo(video, transcript = null)
            return
        }
        showShortMessage(tr(R.string.integration_transcribing_the_video_audio_track_3c545cd))
        val cancellation = videoTranscriptionGateway.submit(
            request,
            object : VideoAudioTranscriptionCallback {
                override fun onCompleted(transcript: String) {
                    videoTranscriptions.remove(video.importId)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) {
                            mediaExecutor.execute { mediaPipeline.delete(video.importId) }
                        } else {
                            addImportedVideo(video, transcript)
                        }
                    }
                }

                override fun onFailed(retryable: Boolean) {
                    videoTranscriptions.remove(video.importId)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) {
                            mediaExecutor.execute { mediaPipeline.delete(video.importId) }
                        } else {
                            addImportedVideo(video, transcript = null)
                            showShortMessage(tr(R.string.integration_the_audio_track_could_not_be_read_video_frames_were_add_b2712eb))
                        }
                    }
                }
            },
        )
        videoTranscriptions[video.importId] = cancellation
    }

    private fun addImportedVideo(video: MediaImportResult.Video, transcript: String?) {
        val metadata = buildString {
            append(tr(R.string.integration_attached_video_28f274f))
            append(video.displayLabel.take(MAX_ATTACHMENT_LABEL))
            append(tr(R.string.integration_duration_3079e9d))
            append(video.metadata.durationMillis / 1_000)
            append(tr(R.string.integration_seconds_99435fc))
            append(video.metadata.width)
            append('×')
            append(video.metadata.height)
            append(tr(R.string.integration_pixels_the_following_images_are_representative_frames_f_11f648e))
            transcript?.trim()?.takeIf(String::isNotBlank)?.let {
                append("\nTranskript der Tonspur:\n")
                append(it.take(MAX_VIDEO_TRANSCRIPT_CHARACTERS))
            }
        }
        val attachments = video.representativeFrames.mapIndexed { index, frame ->
            HansPendingAttachment(
                id = "${video.importId}:frame:$index",
                label = tr(R.string.integration_1_frame_2_9c1d2b6, video.displayLabel.take(MAX_ATTACHMENT_LABEL), index + 1),
                absolutePath = frame.image.absolutePath,
                importId = video.importId,
                contextText = if (index == 0) metadata else "",
            )
        }
        localUi = localUi.copy(
            attachments = localUi.attachments + attachments,
            revision = localUi.revision + 1,
        )
    }

    private fun removeAttachment(id: String) {
        if (pendingComposerDispatch != null) return
        val removed = localUi.attachments.firstOrNull { it.id == id }
        val importId = removed?.importId
        if (importId != null) {
            videoTranscriptions.remove(importId)?.cancel()
            mediaExecutor.execute { mediaPipeline.delete(importId) }
        }
        localUi = localUi.copy(
            attachments = localUi.attachments.filterNot {
                it.id == id || importId != null && it.importId == importId
            },
            revision = localUi.revision + 1,
        )
    }

    private fun mediaFailureMessage(failure: Throwable): String = when (
        (failure as? MediaImportException)?.code
    ) {
        MediaImportFailureCode.DECLARED_SIZE_EXCEEDED,
        MediaImportFailureCode.READ_LIMIT_EXCEEDED,
        -> tr(R.string.integration_the_selected_media_is_too_large_e025317)
        MediaImportFailureCode.PIXEL_LIMIT_EXCEEDED -> tr(R.string.integration_the_media_resolution_is_too_large_67d819b)
        MediaImportFailureCode.DURATION_LIMIT_EXCEEDED -> tr(R.string.integration_the_video_is_longer_than_15_minutes_3818d7f)
        MediaImportFailureCode.UNSUPPORTED_DECLARED_TYPE -> tr(R.string.integration_this_media_format_is_not_supported_9800ef3)
        MediaImportFailureCode.INVALID_IMAGE,
        MediaImportFailureCode.INVALID_VIDEO,
        -> tr(R.string.integration_the_media_could_not_be_read_safely_9b9f4e5)
        MediaImportFailureCode.CONTENT_UNAVAILABLE,
        MediaImportFailureCode.PRIVATE_STORAGE_FAILURE,
        null,
        -> tr(R.string.integration_the_media_could_not_be_added_safely_021486d)
    }

    private fun showShortMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showSpeechCredentialEntry(
        setupToken: HansSetupOperationToken? = null,
        setupCompletion: ((SetupUiCommandResult) -> Unit)? = null,
    ) {
        val dialog = speechCredentialDialog
        if (dialog == null) {
            setupCompletion?.invoke(
                SetupUiCommandResult.Rejected("speech_credential_surface_unavailable"),
            )
            return
        }
        if (setupToken != null) activeSetupSpeechCredentialToken = setupToken
        val shown = dialog.show { outcome ->
            if (activeSetupSpeechCredentialToken == setupToken) {
                activeSetupSpeechCredentialToken = null
            }
            when (outcome) {
                SpeechCredentialDialogOutcome.SAVED -> {
                    localUi = localUi.copy(
                        speechCredentialStatus = currentSpeechCredentialUiStatus(),
                        revision = localUi.revision + 1,
                    )
                    if (setupCompletion == null) {
                        setupRuntime.observeSpeechCredentialAvailability(available = true)
                        showShortMessage(tr(R.string.integration_openai_voice_access_is_securely_configured_1121b5c))
                    } else {
                        setupCompletion(
                            SetupUiCommandResult.Accepted(
                                settingsOpened = true,
                                liveVerificationAccepted = true,
                            ),
                        )
                    }
                }
                SpeechCredentialDialogOutcome.CANCELLED -> setupCompletion?.invoke(
                    SetupUiCommandResult.Rejected("speech_credential_entry_cancelled"),
                )
            }
        }
        if (!shown) {
            if (activeSetupSpeechCredentialToken == setupToken) {
                activeSetupSpeechCredentialToken = null
            }
            setupCompletion?.invoke(
                SetupUiCommandResult.Rejected("speech_credential_surface_already_open"),
            ) ?: showShortMessage(tr(R.string.integration_secure_key_entry_is_already_open_425b734))
        }
    }

    private fun confirmSpeechCredentialRemoval() {
        AlertDialog.Builder(this)
            .setTitle(tr(R.string.integration_remove_voice_access_b75997a))
            .setMessage(
                tr(R.string.integration_the_locally_encrypted_openai_api_key_will_be_deleted_fr_02924da) +
                    tr(R.string.integration_your_chatgpt_and_codex_sign_ins_are_preserved_830c874),
            )
            .setNegativeButton(tr(R.string.integration_cancel_f7ff117), null)
            .setPositiveButton(tr(R.string.integration_remove_3828375)) { _, _ ->
                sessionHost.clearSpeechCredential()
                setupRuntime.observeSpeechCredentialAvailability(available = false)
                localUi = localUi.copy(
                    speechCredentialStatus = currentSpeechCredentialUiStatus(),
                    revision = localUi.revision + 1,
                )
                showShortMessage(tr(R.string.integration_openai_voice_access_was_removed_14ce584))
            }
            .show()
    }

    private fun currentSpeechCredentialUiStatus(): SpeechCredentialUiStatus = when (
        sessionHost.speechCredentialStatus()
    ) {
        SpeechCredentialStatus.MISSING -> SpeechCredentialUiStatus.MISSING
        SpeechCredentialStatus.AVAILABLE -> SpeechCredentialUiStatus.AVAILABLE
        SpeechCredentialStatus.TEMPORARILY_UNAVAILABLE ->
            SpeechCredentialUiStatus.TEMPORARILY_UNAVAILABLE
    }

    private fun String.toPluginHandleOrNull(): PluginHandle? =
        runCatching { PluginHandle(this) }.getOrNull()

    private companion object {
        const val MAX_ATTACHMENT_LABEL = 96
        const val MAX_VIDEO_TRANSCRIPT_CHARACTERS = 64 * 1_024
        const val MAX_APP_QUERY_CHARACTERS = 128
        const val MAX_LIVE_TRANSCRIPT_CHARACTERS = 32_000
        const val MAX_LIVE_TRANSCRIPT_MESSAGES = 100
        const val PRIMARY_DICTATION_MAPPING_ID = "primary-dictation"
        const val MODEL_TOGGLE_MAPPING_ID = "model-preset-toggle"
        const val ACTION_KEY_CAPTURE_WINDOW_MILLIS = 90_000L
        const val ACCESSIBILITY_POSTCONDITION_RETRY_MILLIS = 250L
        const val ACCESSIBILITY_POSTCONDITION_MAX_ATTEMPTS = 4
        const val DIAGNOSTIC_TAG = "HansSessionState"
        const val SETUP_HANDOFF_TAG = "HansSetupHandoff"
        const val OPENAI_API_KEYS_URL = "https://platform.openai.com/api-keys"
        const val OPENAI_BILLING_URL =
            "https://platform.openai.com/settings/organization/billing"
        const val RESTRICTED_SETTINGS_API_STATE_KEY =
            "hans.restricted_settings.api"
        const val RESTRICTED_SETTINGS_CAPABILITY_STATE_KEY =
            "hans.restricted_settings.capability"
        const val RESTRICTED_SETTINGS_STEP_STATE_KEY =
            "hans.restricted_settings.step"
        const val RESTRICTED_SETTINGS_GENERATION_STATE_KEY =
            "hans.restricted_settings.generation"
        const val RESTRICTED_SETTINGS_NONCE_STATE_KEY =
            "hans.restricted_settings.nonce"
        const val RESTRICTED_SETTINGS_STAGE_STATE_KEY =
            "hans.restricted_settings.stage"
        val SETUP_MULTI_PERMISSION_CAPABILITIES = setOf(
            CapabilityAccessUiId.CONTACTS,
            CapabilityAccessUiId.CALENDAR,
            CapabilityAccessUiId.LOCATION,
            CapabilityAccessUiId.PHOTOS_AND_VIDEOS,
            CapabilityAccessUiId.AUDIO_MEDIA,
        )
        val SETUP_RUNTIME_PERMISSION_CAPABILITIES =
            SETUP_MULTI_PERMISSION_CAPABILITIES + setOf(
                CapabilityAccessUiId.MICROPHONE,
                CapabilityAccessUiId.APP_NOTIFICATIONS,
            )
    }
}

private data class PendingSetupPermissionInteraction(
    val token: HansSetupOperationToken,
    val capability: CapabilityAccessUiId,
    val completion: ((SetupUiCommandResult) -> Unit)?,
)

private data class PendingSetupCameraCapture(
    val token: HansSetupOperationToken,
    val lease: SetupUiCommandLease,
    val completion: ((SetupUiCommandResult) -> Unit)?,
)

private data class PendingSetupQuickSettingsTile(
    val token: HansSetupOperationToken,
    val completion: ((SetupUiCommandResult) -> Unit)?,
)

/** Pure API-level policy used by the Activity and JVM regression tests. */
internal object SetupPlatformPermissionContract {
    fun permissionsFor(id: CapabilityAccessUiId, apiLevel: Int): List<String> = when (id) {
        CapabilityAccessUiId.MICROPHONE -> listOf(Manifest.permission.RECORD_AUDIO)
        CapabilityAccessUiId.APP_NOTIFICATIONS -> if (apiLevel >= 33) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }
        CapabilityAccessUiId.CONTACTS -> listOf(Manifest.permission.READ_CONTACTS)
        CapabilityAccessUiId.CALENDAR -> listOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
        )
        CapabilityAccessUiId.LOCATION -> listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        CapabilityAccessUiId.PHOTOS_AND_VIDEOS -> when {
            apiLevel >= 34 -> listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            apiLevel >= 33 -> listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            )
            else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        CapabilityAccessUiId.AUDIO_MEDIA -> if (apiLevel >= 33) {
            listOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        else -> emptyList()
    }
}

internal object SetupQuickSettingsTileContract {
    fun receipt(apiLevel: Int, platformResult: Int?): SetupUiCommandResult {
        if (apiLevel < 33) {
            return SetupUiCommandResult.Rejected("quick_settings_tile_manual_add_required")
        }
        if (platformResult == null) {
            return SetupUiCommandResult.Rejected("quick_settings_tile_request_failed")
        }
        val added = platformResult == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
            platformResult == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
        return SetupUiCommandResult.Accepted(
            settingsOpened = true,
            liveVerificationAccepted = added,
        )
    }
}

internal data class SetupConversationPrompt(
    val visibleText: String,
    val internalRoutingContext: String,
) {
    companion object {
        fun forState(complete: Boolean, text: HansTextResolver): SetupConversationPrompt = SetupConversationPrompt(
            visibleText = if (complete) {
                text.text(R.string.integration_setup_recheck)
            } else {
                text.text(R.string.integration_setup_start)
            },
            internalRoutingContext = buildString {
                append("Internal Hans routing context, not user text to repeat: ")
                append("Use skill ${'$'}hans-setup:setup-hans-device and first call ")
                append("hans_setup.get_setup_state. ")
                if (complete) append("Check revoked access freshly. ")
                append("Do not expose step, status or tool codes. Request exactly one next ")
                append("user action. If the current step is the start, ask naturally whether ")
                append("the user would like to start setup now. The answer comes through chat ")
                append("or voice, not a special screen button. Follow the user's actual ")
                append("language; use the effective interface language only when no user ")
                append("language is established. On agreement store exactly ")
                append("{step:\"intro\",choice:\"begin\"}; do not invent a synonym.")
            },
        )
    }
}

/** A setup turn is never dispatched without the exact proven plugin skill selector. */
internal fun SetupConversationPrompt.toCodexInput(
    skill: CodexInput.Skill,
): List<CodexInput> = listOf(
    CodexInput.Text(visibleText),
    skill,
    CodexInput.UntrustedContext(internalRoutingContext),
)

internal object SetupLiveTestCopy {
    fun voicePreviewAction(text: HansTextResolver) = text.text(R.string.integration_setup_preview)
    fun voiceDictationAction(text: HansTextResolver) = text.text(R.string.integration_setup_dictate)

    fun cameraAction(record: HansSetupStepRecord, text: HansTextResolver): String = when {
        record.detailCode == "camera_dictation_sent_waiting_capture" ->
            text.text(R.string.integration_setup_photo)
        record.detailCode == "camera_recording_listening_observed" ->
            text.text(R.string.integration_setup_hold_test)
        record.auxiliaryEvidenceObserved ->
            text.text(R.string.integration_setup_camera_hold)
        else ->
            text.text(R.string.integration_setup_photo)
    }
}

internal enum class SetupConversationDispatchAcceptance {
    WAITING,
    ACCEPTED,
    REJECTED,
}

internal enum class SetupConversationReceiptOutcome(
    val keepsPending: Boolean,
) {
    WAITING(keepsPending = true),
    REJECTED(keepsPending = false),
    PERSIST_FAILED(keepsPending = true),
    PERSISTED(keepsPending = false),
}

/** Saved-state codec for the only setup receipt that may survive Activity recreation. */
internal object SetupConversationPendingReceiptContract {
    const val STATE_KEY = "hans.pending_setup_conversation_receipt.v1"
    private const val MESSAGE_PREFIX = "hans-setup-"
    private const val MAX_MESSAGE_ID_CHARS = 256

    fun save(messageId: String?): String? = validated(messageId)

    fun restore(savedValue: String?): String? = validated(savedValue)

    private fun validated(value: String?): String? = value?.takeIf {
        it.startsWith(MESSAGE_PREFIX) &&
            it.length in (MESSAGE_PREFIX.length + 1)..MAX_MESSAGE_ID_CHARS &&
            it.none(Char::isISOControl)
    }
}

/** A SENT outbound owns a correlated turn/start or turn/steer server response. */
internal fun setupConversationDispatchAcceptance(
    messageId: String,
    outbound: List<OutboundUserMessageUi>,
): SetupConversationDispatchAcceptance = when (
    outbound.firstOrNull { it.clientUserMessageId == messageId }?.status
) {
    OutboundMessageStatus.SENT -> SetupConversationDispatchAcceptance.ACCEPTED
    OutboundMessageStatus.FAILED -> SetupConversationDispatchAcceptance.REJECTED
    OutboundMessageStatus.PENDING -> SetupConversationDispatchAcceptance.WAITING
    null -> SetupConversationDispatchAcceptance.REJECTED
}

/** Persists only after an exact SENT receipt and retains failed writes for snapshot-driven retry. */
internal fun reconcileSetupConversationReceipt(
    messageId: String,
    outbound: List<OutboundUserMessageUi>,
    persist: () -> Unit,
): SetupConversationReceiptOutcome = when (
    setupConversationDispatchAcceptance(messageId, outbound)
) {
    SetupConversationDispatchAcceptance.WAITING -> SetupConversationReceiptOutcome.WAITING
    SetupConversationDispatchAcceptance.REJECTED -> SetupConversationReceiptOutcome.REJECTED
    SetupConversationDispatchAcceptance.ACCEPTED -> if (runCatching(persist).isSuccess) {
        SetupConversationReceiptOutcome.PERSISTED
    } else {
        SetupConversationReceiptOutcome.PERSIST_FAILED
    }
}

private fun ActionKeyMappingSet.hasAction(action: KeySemanticAction): Boolean =
    mappings.any { it.action == action }

/** Fixed allowlist. Full runtime/voice snapshots must never be serialized into this diagnostic. */
internal fun encodeHansVoiceDiagnostics(
    realtime: ai.hans.standard.integration.CodexRealtimeDiagnostics?,
    dictationPhase: DictationUiPhase,
    livePhase: LiveVoicePhase,
    captureRequestedOrActive: Boolean,
    taskLifecycle: ai.hans.standard.voice.realtime.TaskVoiceLifecycleDiagnostics.Snapshot? =
        ai.hans.standard.voice.realtime.TaskVoiceLifecycleDiagnostics.snapshot(),
    batchLifecycle: ai.hans.standard.voice.stt.BatchTranscriptionDiagnostics.Snapshot? =
        ai.hans.standard.voice.stt.BatchTranscriptionDiagnostics.snapshot(),
): String = org.json.JSONObject()
    .put("schema", 1)
    .put("codexBound", realtime != null)
    .put("state", realtime?.state?.name ?: org.json.JSONObject.NULL)
    .put("started", realtime?.started ?: org.json.JSONObject.NULL)
    .put("stopAck", realtime?.stopAcknowledged ?: org.json.JSONObject.NULL)
    .put("handoffCount", realtime?.handoffCount ?: org.json.JSONObject.NULL)
    .put("userFinalCount", realtime?.userFinalCount ?: org.json.JSONObject.NULL)
    .put("userDeltaCount", realtime?.userDeltaCount ?: org.json.JSONObject.NULL)
    .put("userDeltaCharacters", realtime?.userDeltaCharacters ?: org.json.JSONObject.NULL)
    .put("assistantFinalCount", realtime?.assistantFinalCount ?: org.json.JSONObject.NULL)
    .put("lastPhase", realtime?.lastNativePhase?.name ?: org.json.JSONObject.NULL)
    .put("error", realtime?.lastError?.name ?: org.json.JSONObject.NULL)
    .put("dictationPhase", dictationPhase.name)
    .put("livePhase", livePhase.name)
    .put("captureRequestedOrActive", captureRequestedOrActive)
    .put("taskLifecycle", taskLifecycle?.let { trace ->
        org.json.JSONObject()
            .put("schema", 1)
            .put("run", trace.run)
            .put("eventCount", trace.eventCount)
            .put("droppedCount", trace.droppedCount)
            .put("records", org.json.JSONArray().apply {
                trace.records.forEach { record ->
                    val details = record.details
                    put(org.json.JSONObject()
                        .put("sequence", record.sequence)
                        .put("elapsedMillis", record.elapsedMillis)
                        .put("type", record.type.name)
                        .put("activeWork", details.activeWork ?: org.json.JSONObject.NULL)
                        .put("pendingDispatch", details.pendingDispatch ?: org.json.JSONObject.NULL)
                        .put("muted", details.muted ?: org.json.JSONObject.NULL)
                        .put("workOutcome", details.workOutcome?.name ?: org.json.JSONObject.NULL)
                        .put("reason", details.reason?.name ?: org.json.JSONObject.NULL)
                        .put("handoffCount", details.handoffCount ?: org.json.JSONObject.NULL)
                        .put("inputDelayMillis", details.inputDelayMillis ?: org.json.JSONObject.NULL)
                        .put("mediaClosed", details.mediaClosed ?: org.json.JSONObject.NULL)
                        .put("nativeClosed", details.nativeClosed ?: org.json.JSONObject.NULL))
                }
            })
    } ?: org.json.JSONObject.NULL)
    .put("batchLifecycle", batchLifecycle?.let { trace ->
        org.json.JSONObject().put("run", trace.run)
            .put("records", org.json.JSONArray().apply {
                trace.records.forEach { record ->
                    put(org.json.JSONObject().put("type", record.type.name)
                        .put("elapsedMillis", record.elapsedMillis)
                        .put("audioBytes", record.audioBytes ?: org.json.JSONObject.NULL))
                }
            })
    } ?: org.json.JSONObject.NULL)
    .toString()
