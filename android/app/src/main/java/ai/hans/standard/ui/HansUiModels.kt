package ai.hans.standard.ui

import ai.hans.standard.R
import ai.hans.standard.localization.HansTextResolver
import androidx.annotation.StringRes
import java.text.NumberFormat
import java.util.Locale
import ai.hans.standard.phone.display.DisplayMotionMode
import ai.hans.standard.phone.consent.PersistentAndroidConsentDescriptor
import ai.hans.standard.phone.consent.HansPhoneActionPolicy
import ai.hans.standard.phone.capabilities.LaunchProfileType
import ai.hans.standard.phone.capabilities.PERSONAL_LAUNCH_PROFILE_ID
import ai.hans.standard.phone.capabilities.PrivateSpaceAvailability
import ai.hans.standard.phone.capabilities.PrivateSpaceSnapshot
import ai.hans.standard.phone.keys.ActionKeyTrigger
import ai.hans.standard.phone.keys.SoftwareHoldGestureId
import ai.hans.standard.plugins.PluginConnectionActionKind
import ai.hans.standard.plugins.PluginRemoteMcpPolicyReviewSnapshot
import ai.hans.standard.plugins.PluginRemoteMcpPolicyReviewSubmission
import ai.hans.standard.voice.audio.SpeechAudioRoute
import ai.hans.standard.voice.audio.SpeechAudioRouteState
import ai.hans.standard.voice.realtime.LiveVoiceVoiceSelection
import java.net.URI

/**
 * Pure, immutable UI contracts. The Android/runtime integration owns these values and must
 * replace them only after the requested operation has actually been accepted.
 */
enum class HansDestination {
    AUTH_GATE,
    CHAT,
    APPS,
    PLUGINS,
    AUTOMATIONS,
    WORKBENCH,
    SETTINGS,
}

enum class AuthGateStage {
    CHECKING,
    SIGNED_OUT,
    DEVICE_CODE_AWAITING,
    COMPLETING,
    ERROR,
}

data class AuthGateUiState(
    val stage: AuthGateStage = AuthGateStage.CHECKING,
    val userCode: String = "",
    val verificationUri: String = "",
    val errorMessage: String = "",
    val errorTitle: String = "",
    /** True only while the current runtime snapshot still confirms an authenticated account. */
    val sessionRecovery: Boolean = false,
    /** Optional runtime-owned information such as a copy confirmation or expiry guidance. */
    val supportingMessage: String = "",
    val internetNotice: String = "",
    /** Explicit recovery option; never substitutes for Retry or authentication. */
    val canStartNewConversation: Boolean = false,
) {
    val hasCompleteDeviceCode: Boolean
        get() = userCode.isNotBlank() && verificationUri.isNotBlank()
}

data class AuthGateUiCallbacks(
    val onStartChatGptLogin: () -> Unit,
    val onOpenVerificationPage: (String) -> Unit,
    val onCopyUserCode: (String) -> Unit,
    val onCancel: () -> Unit,
    val onRetry: () -> Unit,
    val onStartNewConversation: () -> Unit = {},
)

enum class RuntimeUiStatus {
    CONNECTING,
    ONLINE,
    OFFLINE,
    LOGIN_REQUIRED;

    /** The chat header remains silent when ready and shows only 💤 otherwise. */
    val isReady: Boolean
        get() = this == ONLINE
}

enum class ChatMessageAuthor {
    USER,
    HANS,
    SYSTEM,
}

data class ChatMessageUiModel(
    val id: String,
    val author: ChatMessageAuthor,
    val text: String,
    /** Increment when streamed content changes without replacing the message id. */
    val revision: Long = 0,
    /** Local-only messages are inserted immediately after this durable Codex timeline id. */
    val localTimelineAnchorId: String? = null,
    /** Stable ordering for multiple local messages sharing the same timeline anchor. */
    val localArrivalOrder: Long = 0,
    /** Markdown/link suffixes may still be arriving until the server completes this message. */
    val complete: Boolean = true,
    /** Exact typed native Voice source; never inferred from text or timeline placement. */
    val liveVoiceTranscript: ai.hans.standard.voice.realtime.LiveVoiceTranscriptRevision? = null,
)

data class ComposerAttachmentUiModel(
    val id: String,
    val label: String,
)

data class ComposerUiState(
    val text: String = "",
    val enabled: Boolean = true,
    val attachments: List<ComposerAttachmentUiModel> = emptyList(),
) {
    val canSend: Boolean
        get() = enabled && (text.isNotBlank() || attachments.isNotEmpty())
}

data class WorkInterruptUiState(
    val visible: Boolean = false,
    val enabled: Boolean = false,
    val pending: Boolean = false,
    val revision: Long = 0,
)

data class ChatUiState(
    val messages: List<ChatMessageUiModel> = emptyList(),
    val composer: ComposerUiState = ComposerUiState(),
    val runtimeStatus: RuntimeUiStatus = RuntimeUiStatus.CONNECTING,
    val isWorking: Boolean = false,
    val workInterrupt: WorkInterruptUiState = WorkInterruptUiState(),
    val internetNotice: String = "",
    val connectionFailureMessage: String = "",
    /** A failed voice request remains actionable independently of Codex/network notices. */
    val speechFailure: SpeechFailureUiState? = null,
    val pendingDictations: List<ai.hans.standard.voice.PendingDictation> = emptyList(),
    /** A static event-driven recording indication; null deliberately means idle. */
    val dictationStatus: DictationUiStatus? = null,
    /** Confirmed microphone mute, independent of the separate telephone UI. */
    val dictationInputMuted: Boolean = false,
    /** A stored physical voice key replaces the screen microphone, even if temporarily gated. */
    val actionKeyConfigured: Boolean = false,
    /** Incomplete local-only text; displayed as a card, never added to the persisted timeline or composer. */
    val dictationPreview: String = "",
    /** Current short-Live session has not produced its first own transcript yet. UI-only. */
    val dictationAwaitingFirstTranscript: Boolean = false,
    /** Runtime-owned status; only an active Live session may animate its tiny header dot. */
    val liveVoiceStatus: LiveVoiceUiStatus? = null,
    /** Confirmed WebRTC capture state. It is never changed optimistically by the call UI. */
    val liveVoiceInputMuted: Boolean = false,
    /** Requested and effective voice for this call; substitutions must remain visible. */
    val liveVoiceVoiceSelection: LiveVoiceVoiceSelection? = null,
    /** Output routing is reported by Android, never inferred from a successful request. */
    val speechAudioRoute: SpeechAudioRouteState = SpeechAudioRouteState(),
    /** Legacy snapshot field only; the camera control no longer starts voice input. */
    val cameraHoldToTalkEnabled: Boolean = false,
    /**
     * Increment for every timeline event, including streamed updates to the current message.
     * The screen uses it to keep the newest content visible without polling or timers.
     */
    val timelineRevision: Long = 0,
)

enum class LiveVoiceUiStatus(@StringRes val labelResource: Int) {
    CONNECTING(R.string.presentation_live_connecting),
    LISTENING(R.string.presentation_live_listening),
    USER_SPEAKING(R.string.presentation_live_user_speaking),
    HANS_SPEAKING(R.string.presentation_live_hans_speaking),
    WAITING_FOR_TASK(R.string.presentation_live_waiting_for_task),
    RECONNECTING(R.string.presentation_live_reconnecting),
    FAILED(R.string.presentation_live_failed);

    val isActive: Boolean
        get() = this != FAILED
}

enum class DictationUiStatus(@StringRes val labelResource: Int) {
    PREPARING(R.string.presentation_dictation_preparing),
    LISTENING(R.string.presentation_dictation_listening),
    FINALIZING(R.string.presentation_dictation_finalizing),
    WAITING_TO_SEND(R.string.presentation_dictation_waiting_to_send),
    FAILED(R.string.presentation_dictation_failed),
}

/** Presentation only: reuse the regular user card without creating or sending a message. */
internal fun ChatUiState.dictationPreviewMessage(): ChatMessageUiModel? =
    if (
        dictationPreview.isNotBlank() &&
        (dictationStatus == DictationUiStatus.LISTENING || dictationStatus == DictationUiStatus.FINALIZING)
    ) {
        ChatMessageUiModel(
            id = "dictation-preview",
            author = ChatMessageAuthor.USER,
            text = dictationPreview,
            complete = false,
        )
    } else {
        null
    }

/** No synthetic message is persisted or dispatched; old chat history does not own this flag. */
internal fun ChatUiState.showDictationTranscriptPlaceholder(): Boolean =
    dictationAwaitingFirstTranscript && dictationPreview.isBlank() && liveVoiceStatus == null &&
        dictationStatus in setOf(
            DictationUiStatus.PREPARING,
            DictationUiStatus.LISTENING,
            DictationUiStatus.FINALIZING,
        )

/** A newly accepted dictation replaces a dead call's error surface, never an active call. */
internal fun LiveVoiceUiStatus?.afterDictationPublication(status: DictationUiStatus?): LiveVoiceUiStatus? =
    if (this == LiveVoiceUiStatus.FAILED && status in setOf(
            DictationUiStatus.PREPARING, DictationUiStatus.LISTENING, DictationUiStatus.FINALIZING,
        )) null else this

/** A synchronous view of the owning draft and its existing in-flight dispatch gate. */
data class ComposerDraftSnapshot(
    val text: String,
    val dispatchPending: Boolean,
)

data class ChatUiCallbacks(
    val onComposerChanged: (String) -> Unit,
    val onSend: (String) -> Unit,
    val onChooseMedia: () -> Unit,
    val onRemoveAttachment: (String) -> Unit,
    val onOpenApps: () -> Unit,
    val onOpenPlugins: () -> Unit,
    val onOpenAutomations: () -> Unit = {},
    val onOpenSettings: () -> Unit,
    val onToggleLiveVoice: () -> Unit,
    /** A chat-local dictation toggle, independent of the header's telephone control. */
    val onToggleDictation: () -> Unit = {},
    val onCameraGestureDown: (Long) -> SoftwareHoldGestureId? = { null },
    val onCameraGestureLongPress: (SoftwareHoldGestureId, Long) -> Unit = { _, _ -> },
    val onCameraGestureUp: (SoftwareHoldGestureId, Long) -> Unit = { _, _ -> },
    val onCameraGestureCancel: (SoftwareHoldGestureId, Long) -> Unit = { _, _ -> },
    val onOpenInternetSettings: () -> Unit = {},
    val onOpenSpeechFailureHelp: (ai.hans.standard.voice.feedback.OpenAiSpeechRemediation) -> Unit = {},
    val onDismissSpeechFailure: (Long) -> Unit = {},
    val onRetryPendingDictation: (String) -> Unit = {},
    val onDiscardPendingDictation: (String) -> Unit = {},
    /**
     * The stateful owner supplies this read-only handshake so a synchronously confirmed send,
     * a still-pending send, and a rejected send remain distinguishable before recomposition.
     * Presentation-only callers may omit it; then only their next state projection reconciles
     * the draft, and the editor never assumes that onSend itself confirmed delivery.
     */
    val readComposerDraft: (() -> ComposerDraftSnapshot)? = null,
    val onSpeechAudioRouteRequested: (SpeechAudioRoute) -> Unit = {},
    /** Explicit commands prevent a delayed confirmation from toggling an already started call. */
    val onStartLiveVoice: (() -> Unit)? = null,
    val onStopLiveVoice: (() -> Unit)? = null,
    /** Requests a local WebRTC-track mute; the UI changes only after runtime confirmation. */
    val onLiveVoiceInputMutedChanged: (Boolean) -> Unit = {},
    /** Replays exactly one currently visible Hans response through the process-owned TTS path. */
    val onReadAssistantMessageAloud: (String) -> Unit = {},
    /** Opens the bounded, explicitly refreshed local work inventory. */
    val onOpenWorkbench: () -> Unit = {},
    /** Requests the actual Codex turn interrupt. True means requested, never confirmed stopped. */
    val onInterruptWork: () -> Boolean = { false },
)

data class AppUiModel(
    val packageName: String,
    val componentName: String,
    val label: String,
    val profileId: String = PERSONAL_LAUNCH_PROFILE_ID,
    val profileType: LaunchProfileType = LaunchProfileType.PERSONAL,
)

data class AppProfileUiModel(
    val profileId: String,
    val type: LaunchProfileType,
    val locked: Boolean,
)

internal data class PersonalAppInventory(
    val apps: List<AppUiModel>,
    val profiles: List<AppProfileUiModel>,
)

/** Immediate fail-closed state transition used before any asynchronous platform reread. */
internal fun personalAppInventoryOnly(
    apps: List<AppUiModel>,
    profiles: List<AppProfileUiModel>,
): PersonalAppInventory = PersonalAppInventory(
    apps = apps.filter {
        it.profileType == LaunchProfileType.PERSONAL &&
            it.profileId == PERSONAL_LAUNCH_PROFILE_ID
    },
    profiles = profiles.filter {
        it.type == LaunchProfileType.PERSONAL &&
            it.profileId == PERSONAL_LAUNCH_PROFILE_ID
    },
)

data class PrivateSpaceUiState(
    val availability: PrivateSpaceAvailability =
        PrivateSpaceAvailability.UNSUPPORTED_PLATFORM,
    /** Process-local opaque profile handle; never an Android user id or serial. */
    val profileId: String? = null,
    val locked: Boolean = true,
    /** Persistent user preference. False removes the entire container from Apps and search. */
    val containerVisible: Boolean = true,
    /** Android 16 can independently hide the locked entrypoint from every launcher surface. */
    val entrypointHiddenWhenLocked: Boolean = false,
    val settingsAvailable: Boolean = false,
    /** Prevents repeat requests while Android is changing quiet mode asynchronously. */
    val operationInProgress: Boolean = false,
    val notice: String = "",
) {
    val profilePresent: Boolean
        get() = availability == PrivateSpaceAvailability.AVAILABLE && profileId != null

    val canChangeLock: Boolean
        get() = profilePresent && !operationInProgress

    val effectiveContainerVisible: Boolean
        get() = containerVisible && !(locked && entrypointHiddenWhenLocked)

    val isRelevantToSettings: Boolean
        get() = availability != PrivateSpaceAvailability.UNSUPPORTED_PLATFORM
}

internal fun privateSpaceUiState(
    snapshot: PrivateSpaceSnapshot,
    containerVisible: Boolean,
    operationInProgress: Boolean = false,
    notice: String = "",
): PrivateSpaceUiState = PrivateSpaceUiState(
    availability = snapshot.availability,
    profileId = snapshot.profile?.profileId,
    locked = snapshot.profile?.locked ?: true,
    containerVisible = containerVisible,
    entrypointHiddenWhenLocked = snapshot.entrypointHiddenWhenLocked,
    settingsAvailable = snapshot.settingsAvailable,
    operationInProgress = operationInProgress,
    notice = notice,
)

data class AppsUiState(
    val apps: List<AppUiModel> = emptyList(),
    val profiles: List<AppProfileUiModel> = listOf(
        AppProfileUiModel(
            profileId = PERSONAL_LAUNCH_PROFILE_ID,
            type = LaunchProfileType.PERSONAL,
            locked = false,
        ),
    ),
    val query: String = "",
    val loading: Boolean = false,
    val errorMessage: String = "",
    val privateSpace: PrivateSpaceUiState = PrivateSpaceUiState(),
)

data class AppsUiCallbacks(
    val onBack: () -> Unit,
    val onQueryChanged: (String) -> Unit,
    val onRefresh: () -> Unit,
    val onLaunch: (String, String) -> Unit,
    val onPrivateSpaceVisibilityChanged: (Boolean) -> Unit = {},
    val onPrivateSpaceLockChanged: (String, Boolean) -> Unit = { _, _ -> },
    val onOpenPrivateSpaceSettings: () -> Unit = {},
)

enum class PluginListKind {
    INSTALLED,
    AVAILABLE,
}

data class PluginUiModel(
    val id: String,
    val name: String,
    val description: String = "",
    val statusLabel: String = "",
    val actionEnabled: Boolean = true,
)

data class PluginAppUiModel(
    val id: String,
    val name: String,
    val description: String = "",
    /** Strictly validated HTTPS URL, or null when no safe connection link exists. */
    val connectionUrl: String? = null,
)

data class PluginSkillUiModel(
    /** Exact App Server selector; the user can never edit this value. */
    val name: String,
    val displayName: String,
    val description: String = "",
    /** Confirmed App Server state. Never changed optimistically by the UI. */
    val enabled: Boolean,
)

data class PluginDetailUiModel(
    val id: String,
    val name: String,
    val description: String = "",
    val marketplaceName: String = "",
    val apps: List<PluginAppUiModel> = emptyList(),
    val skills: List<PluginSkillUiModel> = emptyList(),
    val hookCount: Int = 0,
    val mcpServerCount: Int = 0,
    val scheduledTaskCount: Int = 0,
    /** Strictly validated HTTPS URL, or null when the runtime supplied no safe share link. */
    val shareUrl: String? = null,
    val skillChangePending: Boolean = false,
    val uninstallPending: Boolean = false,
    val uninstallConfirmationPending: Boolean = false,
    val operationErrorMessage: String = "",
)

/** Safe display-only OAuth/install continuation. It contains no opaque handle or endpoint. */
data class PluginConnectionActionUiModel(
    val pluginId: String,
    val serverId: String,
    val kind: PluginConnectionActionKind,
    val title: String,
    val message: String,
    val actionLabel: String,
    /** Exact, secret-free review identity. Null for OAuth and retry actions. */
    val policyReview: PluginRemoteMcpPolicyReviewSnapshot? = null,
)

data class PluginsUiState(
    val selectedList: PluginListKind = PluginListKind.INSTALLED,
    val installed: List<PluginUiModel> = emptyList(),
    val available: List<PluginUiModel> = emptyList(),
    val operationPluginId: String? = null,
    /** Serializes detail reads at the UI boundary to avoid stale cross-selection responses. */
    val pluginReadPending: Boolean = false,
    /** Non-null only while the exact locally selected installed handle still exists. */
    val selectedPluginId: String? = null,
    /** Confirmed plugin/read payload correlated to [selectedPluginId]. */
    val selectedPlugin: PluginDetailUiModel? = null,
    val selectedPluginLoading: Boolean = false,
    val selectedPluginErrorMessage: String = "",
    val marketplaceRefreshing: Boolean = false,
    val marketplaceMessage: String = "",
    val connectionAction: PluginConnectionActionUiModel? = null,
    val remoteMcpPolicyReviewPending: Boolean = false,
)

data class PluginsUiCallbacks(
    val onBack: () -> Unit,
    val onListSelected: (PluginListKind) -> Unit,
    val onOpenInstalled: (String) -> Unit,
    val onInstallAvailable: (String) -> Unit,
    val onCloseDetails: () -> Unit = {},
    val onOpenPluginLink: (String) -> Unit = {},
    val onPluginSkillEnabledChanged: (String, String, Boolean) -> Unit = { _, _, _ -> },
    val onRefreshMarketplaces: () -> Unit = {},
    val onUninstallPlugin: (String) -> Unit = {},
    val onConnectRemoteMcp: (String, String) -> Unit = { _, _ -> },
    val onReviewRemoteMcpPolicy: (PluginRemoteMcpPolicyReviewSubmission) -> Unit = {},
    val onRetryRemoteMcpInstall: (String, String) -> Unit = { _, _ -> },
)

data class WorkbenchWorkspaceUiModel(
    /** Opaque, content-addressed handle. This is deliberately not a filesystem path. */
    val handle: String,
    val fileCount: Int,
    val byteCount: Long,
)

data class WorkbenchArtifactUiModel(
    /** Opaque artifact handle. */
    val handle: String,
    val displayName: String,
    val mimeType: String,
    val byteCount: Long,
    val originLabel: String,
    val workspaceHandle: String? = null,
)

data class WorkbenchPythonUiState(
    /** False means that merely opening the Workbench did not start or bind the worker. */
    val initialized: Boolean = false,
    val phaseLabel: String = "",
    val ready: Boolean = false,
    /** The isolated worker is intentionally absent and will be started by the next task. */
    val startsOnDemand: Boolean = false,
    val pythonVersion: String = "",
    val detail: String = "",
)

data class WorkbenchUiState(
    val workspaces: List<WorkbenchWorkspaceUiModel> = emptyList(),
    val artifacts: List<WorkbenchArtifactUiModel> = emptyList(),
    val python: WorkbenchPythonUiState = WorkbenchPythonUiState(),
    val loading: Boolean = false,
    val errorMessage: String = "",
    /** True means the bounded inventory contains additional entries not rendered here. */
    val workspacesTruncated: Boolean = false,
    val artifactsTruncated: Boolean = false,
)

data class WorkbenchUiCallbacks(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
)

enum class AutomationMissedRunUiMode(@StringRes val labelResource: Int) {
    SKIP(R.string.presentation_missed_skip),
    RUN_LATEST(R.string.presentation_missed_latest),
    CATCH_UP(R.string.presentation_missed_catch_up),
}

data class AutomationRunHistoryUiModel(
    val headline: String,
    val scheduledLabel: String,
    val failureLabel: String? = null,
)

data class AutomationUiModel(
    val id: String,
    val revision: Long,
    val instructionPreview: String,
    val scheduleLabel: String,
    val enabled: Boolean,
    /** Confirmed persisted policy; never an optimistic switch value. */
    val unattended: Boolean,
    val requiresUnlockedDevice: Boolean,
    val missedRunMode: AutomationMissedRunUiMode,
    val timingLabel: String,
    val nextRunLabel: String,
    val lastRunLabel: String,
    val lastFailureLabel: String? = null,
    val pendingCount: Int,
    /** Newest-first, persisted run evidence. Kept bounded by the projector. */
    val history: List<AutomationRunHistoryUiModel> = emptyList(),
    val historyTruncated: Boolean = false,
)

/**
 * Event-driven snapshot of the persistent automation store. Opening or mutating this screen
 * requests one bounded refresh; the composable itself never polls.
 */
data class AutomationsUiState(
    val items: List<AutomationUiModel> = emptyList(),
    val loading: Boolean = false,
    val operationAutomationId: String? = null,
    val errorMessage: String = "",
    val notice: String = "",
)

data class AutomationsUiCallbacks(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onCreateInChat: () -> Unit = {},
    val onEditInChat: (String, Long) -> Unit = { _, _ -> },
    val onEnabledChanged: (String, Long, Boolean) -> Unit = { _, _, _ -> },
    val onUnattendedChanged: (String, Long, Boolean) -> Unit = { _, _, _ -> },
    val onRunNow: (String, Long) -> Unit = { _, _ -> },
    val onDelete: (String, Long) -> Unit = { _, _ -> },
)

data class ModelUiOption(
    val id: String,
    val label: String,
) {
    companion object {
        val HANS_MODELS: List<ModelUiOption> = listOf(
            ModelUiOption(id = "gpt-6-luna", label = "Luna 6"),
            ModelUiOption(id = "gpt-5.6-terra", label = "Terra 5.6"),
            ModelUiOption(id = "gpt-6.1-sol", label = "Sol 6.1"),
            ModelUiOption(id = "gpt-6-astra", label = "Astra 6"),
            ModelUiOption(id = "gpt-5.6-luna", label = "Luna 5.6"),
            ModelUiOption(id = "gpt-5.6-sol", label = "Sol 5.6"),
            ModelUiOption(id = "gpt-6-sol", label = "Sol 6"),
        )
    }
}

enum class ReasoningEffortUiOption(
    val id: String,
    @StringRes val labelResource: Int,
) {
    LOW("low", R.string.presentation_effort_low),
    MEDIUM("medium", R.string.presentation_effort_medium),
    HIGH("high", R.string.presentation_effort_high),
    XHIGH("xhigh", R.string.presentation_effort_xhigh),
    MAX("max", R.string.presentation_effort_max),
    ULTRA("ultra", R.string.presentation_effort_ultra),
}

data class VoiceUiOption(
    val id: String,
    val label: String,
)

enum class ReadAloudUiMode(@StringRes val labelResource: Int) {
    FINAL_ONLY(R.string.presentation_read_aloud_final),
    ALL_MESSAGES(R.string.presentation_read_aloud_all),
}

enum class SpeechCredentialUiStatus {
    MISSING,
    AVAILABLE,
    TEMPORARILY_UNAVAILABLE,
}

enum class CapabilityAccessUiId {
    ALL_FILES,
    HOME_APP,
    MICROPHONE,
    APP_NOTIFICATIONS,
    NOTIFICATION_ACCESS,
    ACCESSIBILITY,
    CONTACTS,
    CALENDAR,
    LOCATION,
    PHOTOS_AND_VIDEOS,
    AUDIO_MEDIA,
    QUICK_SETTINGS_TILE,
    EXACT_ALARMS,
}

data class CapabilityAccessUiModel(
    val id: CapabilityAccessUiId,
    val title: String,
    val description: String,
    val granted: Boolean,
)

data class PersistentAndroidConsentUiModel(
    /** Trusted app-local descriptor; visible copy is never parsed back into authority. */
    val descriptor: PersistentAndroidConsentDescriptor,
    val title: String,
    val description: String,
)

enum class RemoteWorkerSettingsUiStatus {
    NOT_LOADED,
    UNCONFIGURED,
    DISABLED,
    NEEDS_ACTIVATION,
    EFFECTIVE,
    STORAGE_CORRUPT,
}

data class RemoteWorkerAdapterUiDraft(
    val id: String = "",
    val version: String = "",
)

/**
 * Public connection material entered by the user. The Android device-key alias is deliberately
 * absent: it is derived and owned by the Keystore-backed runtime rather than the UI.
 */
data class RemoteWorkerConfigurationUiDraft(
    val workerId: String = "",
    val httpsOrigin: String = "",
    val serverSpkiSha256: String = "",
    val adapters: List<RemoteWorkerAdapterUiDraft> = listOf(RemoteWorkerAdapterUiDraft()),
    val requestedEnabled: Boolean = false,
) {
    fun validationMessage(text: HansTextResolver): String? =
        validateRemoteWorkerConfigurationDraft(this, text)

    val canSave: Boolean
        get() = remoteWorkerValidationResource(this) == null

    override fun toString(): String =
        "RemoteWorkerConfigurationUiDraft(workerId=$workerId, " +
            "requestedEnabled=$requestedEnabled, adapterCount=${adapters.size}, " +
            "connectionMetadata=<redacted>)"
}

/** Passive Settings projection. Merely rendering it must never probe or activate the worker. */
data class RemoteWorkerSettingsUiState(
    val revision: Long = 0L,
    val loaded: Boolean = false,
    val configured: Boolean = false,
    val requestedEnabled: Boolean = false,
    val effective: Boolean = false,
    val status: RemoteWorkerSettingsUiStatus = RemoteWorkerSettingsUiStatus.NOT_LOADED,
    val workerId: String = "",
    val httpsOrigin: String = "",
    val serverSpkiSha256: String = "",
    val approvedAdapters: List<RemoteWorkerAdapterUiDraft> = emptyList(),
    val operationInProgress: Boolean = false,
    val notice: String = "",
) {
    val formDraft: RemoteWorkerConfigurationUiDraft
        get() = RemoteWorkerConfigurationUiDraft(
            workerId = workerId,
            httpsOrigin = httpsOrigin,
            serverSpkiSha256 = serverSpkiSha256,
            adapters = approvedAdapters.ifEmpty { listOf(RemoteWorkerAdapterUiDraft()) },
            requestedEnabled = requestedEnabled,
        )

    override fun toString(): String =
        "RemoteWorkerSettingsUiState(revision=$revision, loaded=$loaded, " +
            "configured=$configured, requestedEnabled=$requestedEnabled, " +
            "effective=$effective, status=$status, adapterCount=${approvedAdapters.size}, " +
            "connectionMetadata=<redacted>)"
}

internal fun validateRemoteWorkerConfigurationDraft(
    draft: RemoteWorkerConfigurationUiDraft,
    text: HansTextResolver,
): String? = remoteWorkerValidationResource(draft)?.let { resourceId ->
    if (resourceId == R.string.presentation_worker_adapter_limit) {
        text.text(resourceId, REMOTE_WORKER_UI_MAX_ADAPTERS)
    } else {
        text.text(resourceId)
    }
}

@StringRes
private fun remoteWorkerValidationResource(draft: RemoteWorkerConfigurationUiDraft): Int? {
    val workerId = draft.workerId.trim()
    if (!REMOTE_WORKER_UI_ID.matches(workerId) || ".." in workerId) {
        return R.string.presentation_worker_id_invalid
    }
    val origin = runCatching { URI(draft.httpsOrigin.trim()) }.getOrNull()
    if (
        origin == null ||
        origin.scheme != "https" ||
        origin.host.isNullOrBlank() ||
        origin.rawUserInfo != null ||
        origin.query != null ||
        origin.fragment != null ||
        !(origin.path.isNullOrEmpty() || origin.path == "/")
    ) {
        return R.string.presentation_worker_origin_invalid
    }
    if (!REMOTE_WORKER_UI_SHA_256.matches(draft.serverSpkiSha256.trim())) {
        return R.string.presentation_worker_pin_invalid
    }
    if (draft.adapters.size > REMOTE_WORKER_UI_MAX_ADAPTERS) {
        return R.string.presentation_worker_adapter_limit
    }
    val nonEmptyAdapters = draft.adapters.filterNot {
        it.id.isBlank() && it.version.isBlank()
    }
    if (nonEmptyAdapters.any { adapter ->
            val id = adapter.id.trim()
            val version = adapter.version.trim()
            !REMOTE_WORKER_UI_ADAPTER.matches(id) || ".." in id ||
                id.substringBefore('.') in REMOTE_WORKER_UI_LOCAL_ADAPTER_ROOTS ||
                !REMOTE_WORKER_UI_ADAPTER_VERSION.matches(version)
        }
    ) {
        return R.string.presentation_worker_adapter_invalid
    }
    if (nonEmptyAdapters.map { it.id.trim() }.distinct().size != nonEmptyAdapters.size) {
        return R.string.presentation_worker_adapter_duplicate
    }
    if (draft.requestedEnabled && nonEmptyAdapters.isEmpty()) {
        return R.string.presentation_worker_adapter_required
    }
    return null
}

private val REMOTE_WORKER_UI_ID = Regex("[A-Za-z0-9._:-]{1,128}")
private val REMOTE_WORKER_UI_ADAPTER = Regex("[a-z][a-z0-9._:-]{0,127}")
private val REMOTE_WORKER_UI_ADAPTER_VERSION = Regex("[0-9]+(?:\\.[0-9]+){0,3}")
private val REMOTE_WORKER_UI_SHA_256 = Regex("[0-9a-fA-F]{64}")
/** Defense-in-depth mirror; the runtime remains authoritative and rejects these again. */
private val REMOTE_WORKER_UI_LOCAL_ADAPTER_ROOTS = setOf(
    "accessibility",
    "android",
    "bluetooth",
    "camera",
    "contacts",
    "device",
    "intent",
    "location",
    "mobile",
    "nfc",
    "notifications",
    "phone",
    "sensors",
    "sms",
    "telephony",
    "wifi",
)
private const val REMOTE_WORKER_UI_MAX_ADAPTERS = 128

/** Build identity plus the App Server's confirmed effective state; never optimistic UI state. */
data class CodexUpdateUiState(
    val bundledRuntimeVersion: String = "",
    val runtimeReady: Boolean = false,
    /** True only when a fixed HTTPS update page was sealed into this APK at build time. */
    val updateUrlConfigured: Boolean = false,
)

data class SettingsUiState(
    val models: List<ModelUiOption> = ModelUiOption.HANS_MODELS,
    /** Efforts advertised by model/list for the current pending/confirmed model only. */
    val reasoningEfforts: List<ReasoningEffortUiOption> = emptyList(),
    /** Null means that the runtime has not confirmed a model yet. */
    val selectedModelId: String? = null,
    /** Null means that the runtime has not confirmed an effort yet. */
    val selectedReasoningEffortId: String? = null,
    /** Fast is offered only when model/list advertises the priority service tier. */
    val fastModeAvailable: Boolean = false,
    /** Reflects only the last App Server-confirmed dispatch, never a staged click. */
    val fastModeEnabled: Boolean = false,
    val voices: List<VoiceUiOption> = emptyList(),
    val selectedVoiceId: String? = null,
    val liveVoices: List<VoiceUiOption> = emptyList(),
    /** Stored preference for the next call; changing it never reconfigures a running call. */
    val selectedLiveVoiceId: String? = null,
    /** Server-confirmed voice of the current call only, never inferred from a preference. */
    val activeLiveVoiceId: String? = null,
    /** Any task or phone voice session owns audio, even before its voice is confirmed. */
    val voiceSessionActive: Boolean = false,
    val speechRate: Float = 1.25f,
    val readAloudMode: ReadAloudUiMode = ReadAloudUiMode.ALL_MESSAGES,
    val speechCredentialStatus: SpeechCredentialUiStatus = SpeechCredentialUiStatus.MISSING,
    val cameraHoldToTalkEnabled: Boolean = false,
    val actionKey: ActionKeyUiState = ActionKeyUiState(),
    /** Device-local preference confirmed by its store, not an optimistic clicked value. */
    val displayMotionMode: DisplayMotionMode = DisplayMotionMode.AUTOMATIC,
    val capabilityAccess: List<CapabilityAccessUiModel> = emptyList(),
    val everydayAccessBundleActive: Boolean = false,
    val phoneActionPolicy: HansPhoneActionPolicy = HansPhoneActionPolicy.CONFIRM_ACTIONS,
    val persistentAndroidConsents: List<PersistentAndroidConsentUiModel> = emptyList(),
    val privateSpace: PrivateSpaceUiState = PrivateSpaceUiState(),
    val runtimeNotice: String = "",
    val notificationFactArchive: NotificationFactArchiveStatus = NotificationFactArchiveStatus(),
    val whatsAppAgentChannel: WhatsAppAgentChannelUiState = WhatsAppAgentChannelUiState(),
    val confirmedSttGlossary: ConfirmedSttGlossaryUiState = ConfirmedSttGlossaryUiState(),
    val sttLatency: SttLatencyUiState = SttLatencyUiState(),
    val remoteWorker: RemoteWorkerSettingsUiState = RemoteWorkerSettingsUiState(),
    val codexUpdate: CodexUpdateUiState = CodexUpdateUiState(),
    /** Process-memory-only incoming Desktop permission and RPC-confirmed state. */
    val remoteControl: ai.hans.standard.remotecontrol.RemoteControlSnapshot =
        ai.hans.standard.remotecontrol.RemoteControlSnapshot(),
    val remoteControlThreadId: String? = null,
    val remoteControlThreadName: String? = null,
    val remoteControlProjectPath: String? = null,
    val remotePhoneToolsAvailable: Boolean = false,
) {
    fun speechRateLabel(text: HansTextResolver): String = formatSpeechRate(speechRate, text.locale)
}

/** Confirmed app-private spellings only; the editor draft never enters effective UI state. */
data class SttLatencyUiState(
    val preferred: ai.hans.standard.voice.stt.SttTranscriptionDelay =
        ai.hans.standard.voice.stt.SttTranscriptionDelay.LOW,
    val confirmedActive: ai.hans.standard.voice.stt.SttTranscriptionDelay? = null,
    val notice: String = "",
)

data class ConfirmedSttGlossaryUiState(
    val terms: List<String> = emptyList(),
    val notice: String = "",
) {
    val editorText: String
        get() = terms.joinToString("\n")
}

data class ActionKeyUiState(
    val configured: Boolean = false,
    /** Stored assignment; may be temporarily unavailable without losing edit/remove controls. */
    val assigned: Boolean = false,
    val modelToggleConfigured: Boolean = false,
    val capturing: Boolean = false,
    val capturingModelToggle: Boolean = false,
    val notice: String = "",
    val dictationTrigger: ActionKeyTrigger = ActionKeyTrigger.PRESS,
    val mp01VendorConflict: Mp01VendorActionConflictUiState =
        Mp01VendorActionConflictUiState(),
) {
    /** The mapping can be stored while deliberately withheld from dispatch. */
    val dictationMappingStored: Boolean
        get() = assigned || configured ||
            Mp01VendorShortcutUiKind.DICTATION in mp01VendorConflict.shortcutKinds

    val modelToggleMappingStored: Boolean
        get() = modelToggleConfigured ||
            Mp01VendorShortcutUiKind.MODEL_TOGGLE in mp01VendorConflict.shortcutKinds
}

enum class Mp01VendorShortcutUiKind {
    DICTATION,
    MODEL_TOGGLE,
}

enum class Mp01VendorActionConflictUiKind {
    /** Older Minimal firmware where the user can neutralize an Accessibility shortcut. */
    LEGACY_ACCESSIBILITY,

    /** Stock firmware consumes AREFRESH in Android system policy before Accessibility. */
    STOCK_SYSTEM_POLICY,
}

data class Mp01VendorActionConflictUiState(
    val detected: Boolean = false,
    val settingsActivityAvailable: Boolean = false,
    val replacementConfirmed: Boolean = false,
    val shortcutKinds: Set<Mp01VendorShortcutUiKind> = emptySet(),
    val kind: Mp01VendorActionConflictUiKind =
        Mp01VendorActionConflictUiKind.LEGACY_ACCESSIBILITY,
    /** Stock system policy still owns hold; only a short-press toggle can coexist safely. */
    val stockPressToggleCompatible: Boolean = false,
) {
    val userRemediable: Boolean
        get() = detected && kind == Mp01VendorActionConflictUiKind.LEGACY_ACCESSIBILITY

    val replacementRequired: Boolean
        get() = detected && when (kind) {
            Mp01VendorActionConflictUiKind.LEGACY_ACCESSIBILITY -> !replacementConfirmed
            Mp01VendorActionConflictUiKind.STOCK_SYSTEM_POLICY -> !stockPressToggleCompatible
        }
}

data class SettingsUiCallbacks(
    val onBack: () -> Unit,
    val onStartGettingToKnow: () -> Unit,
    val onOpenWorkbench: () -> Unit = {},
    val onModelSelected: (String) -> Unit,
    val onReasoningEffortSelected: (String) -> Unit,
    val onFastModeChanged: (Boolean) -> Unit = {},
    val onVoiceSelected: (String) -> Unit,
    val onLiveVoiceSelected: (String) -> Unit = {},
    val onSpeechRateSelected: (Float) -> Unit,
    val onReadAloudModeSelected: (ReadAloudUiMode) -> Unit,
    val onPreviewVoice: () -> Unit,
    val onStartActionKeySetup: () -> Unit,
    val onStartModelToggleKeySetup: () -> Unit,
    val onCancelActionKeySetup: () -> Unit,
    val onClearActionKey: () -> Unit,
    val onClearModelToggleKey: () -> Unit,
    val onOpenMp01VendorSettings: () -> Unit = {},
    val onConfirmMp01VendorActionCleared: () -> Unit = {},
    val onCapabilityAccessRequested: (CapabilityAccessUiId) -> Unit,
    val onEverydayAccessBundleRequested: () -> Unit = {},
    val onNotificationLinkMetadataConsentRequested: () -> Unit = {},
    val onPersistentAndroidConsentRevoked: (PersistentAndroidConsentDescriptor) -> Unit = {},
    val onAllPersistentAndroidConsentsRevoked: () -> Unit = {},
    val onPrivateSpaceVisibilityChanged: (Boolean) -> Unit = {},
    val onPrivateSpaceLockChanged: (String, Boolean) -> Unit = { _, _ -> },
    val onOpenPrivateSpaceSettings: () -> Unit = {},
    val onDictationTriggerSelected: (ActionKeyTrigger) -> Unit = {},
    val onCameraHoldToTalkChanged: (Boolean) -> Unit = {},
    val onDisplayMotionModeSelected: (DisplayMotionMode) -> Unit = {},
    val onStartOrResumeSetup: () -> Unit = {},
    val onConfigureSpeechCredential: () -> Unit = {},
    val onRemoveSpeechCredential: () -> Unit = {},
    /** Opens the fixed official OpenAI API-key page; no user-controlled URL is accepted. */
    val onOpenOpenAiApiKeyPage: () -> Unit = {},
    /** Opens the fixed official OpenAI billing overview for quota remediation. */
    val onOpenOpenAiBillingPage: () -> Unit = {},
    val onExportBackup: () -> Unit = {},
    val onImportBackup: () -> Unit = {},
    val onRefreshNotificationFactArchive: () -> Unit = {},
    val onConfigureWhatsAppAgentChannel: () -> Unit = {},
    val onDisableWhatsAppAgentChannel: () -> Unit = {},
    val onConfirmedSttGlossarySaved: (String) -> Unit = {},
    val onSttLatencyChanged: (ai.hans.standard.voice.stt.SttTranscriptionDelay) -> Unit = {},
    /** Local-only refresh. It must never probe, publish or contact the configured worker. */
    val onRemoteWorkerSettingsOpened: () -> Unit = {},
    /** Explicit group-open event: refresh the authoritative model catalogue, never poll at idle. */
    val onModelSettingsOpened: () -> Unit = {},
    val onSaveRemoteWorkerConfiguration: (RemoteWorkerConfigurationUiDraft) -> Unit = {},
    /** The only Settings action allowed to cross the remote-worker network boundary. */
    val onActivateRemoteWorker: () -> Unit = {},
    /** Opens only the fixed, build-time Hans update surface; it never installs by itself. */
    val onUpdateCodex: () -> Unit = {},
    val onRemoteControlSettingsOpened: () -> Unit = {},
    val onRemoteControlEnable: () -> Unit = {},
    val onRemoteControlDisable: () -> Unit = {},
    val onRemoteControlPair: () -> Unit = {},
    val onRemoteControlRefresh: () -> Unit = {},
    val onRemoteControlRefreshClients: () -> Unit = {},
    val onRemoteControlLoadMoreClients: () -> Unit = {},
    val onRemoteControlRevoke: (String) -> Unit = {},
    val onRemoteControlCheckPairing: () -> Unit = {},
)

data class HansUiState(
    /** Secure default: callers must explicitly reveal chat after a successful account check. */
    val destination: HansDestination = HansDestination.AUTH_GATE,
    val authGate: AuthGateUiState = AuthGateUiState(),
    val chat: ChatUiState = ChatUiState(),
    val apps: AppsUiState = AppsUiState(),
    val plugins: PluginsUiState = PluginsUiState(),
    val automations: AutomationsUiState = AutomationsUiState(),
    val settings: SettingsUiState = SettingsUiState(),
    val workbench: WorkbenchUiState = WorkbenchUiState(),
)

data class HansUiCallbacks(
    val authGate: AuthGateUiCallbacks,
    val chat: ChatUiCallbacks,
    val apps: AppsUiCallbacks,
    val plugins: PluginsUiCallbacks,
    val automations: AutomationsUiCallbacks = AutomationsUiCallbacks(),
    val settings: SettingsUiCallbacks,
    val workbench: WorkbenchUiCallbacks = WorkbenchUiCallbacks(),
)

internal val SpeechRateOptions: List<Float> = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

internal fun formatSpeechRate(rate: Float, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 2
        isGroupingUsed = false
    }.format(rate.toDouble()) + "×"
