package ai.hans.standard.integration

import ai.hans.standard.codex.ApprovalPolicy
import ai.hans.standard.codex.AgentMessagePhase
import ai.hans.standard.codex.CodexModel
import ai.hans.standard.codex.DispatchOptions
import ai.hans.standard.codex.DispatchSandbox
import ai.hans.standard.codex.ProtocolLimits
import ai.hans.standard.codex.ReasoningEffort
import ai.hans.standard.codex.SessionUiSnapshot
import ai.hans.standard.codex.TurnStatus
import ai.hans.standard.plugins.PluginDomainSnapshot
import ai.hans.standard.settings.HansSettings
import ai.hans.standard.runtime.CodexAssistantProfile
import java.util.UUID

enum class ClientRuntimePhase {
    STOPPED,
    STARTING,
    READY,
    RESTARTING,
    FAILED,
}

enum class ClientSessionPhase {
    IDLE,
    BOOTSTRAPPING,
    AUTH_REQUIRED,
    LOGIN_PENDING,
    RECOVERING_THREAD,
    READY,
    BUSY,
    FAILED,
}

enum class BundledSetupBootstrapStatus {
    DISABLED,
    PREPARING,
    READY,
    FAILED,
}

enum class ClientProblemCode {
    PROTOCOL_VERSION,
    TRANSPORT_ORDER,
    TRANSPORT_REJECTED,
    RUNTIME_FAILED,
    MALFORMED_SERVER_FRAME,
    AUTHENTICATION,
    MODEL_CATALOG,
    SELECTION_UPDATE,
    THREAD_RECOVERY,
    DISPATCH_REJECTED,
    DISPATCH_AMBIGUOUS,
    LOCAL_PERSISTENCE,
}

data class CodexClientProblem(
    val code: ClientProblemCode,
    val retryable: Boolean,
)

data class DeviceCodeLoginUi(
    val userCode: String,
    val verificationUrl: String,
)

data class DispatchSelection(
    val model: String,
    val effort: ReasoningEffort,
    val serviceTier: String = HansSettings.DEFAULT_SERVICE_TIER,
) {
    init {
        require(model in HansSettings.SUPPORTED_MODELS) { "Unsupported Hans model" }
        require(effort.wireValue in HansSettings.SUPPORTED_REASONING_EFFORTS) {
            "Unsupported Hans reasoning effort"
        }
        require(serviceTier in HansSettings.SUPPORTED_SERVICE_TIERS) {
            "Unsupported Hans service tier"
        }
    }

    internal fun toOptions(workspacePath: String): DispatchOptions = DispatchOptions(
        model = model,
        effort = effort,
        serviceTier = serviceTier,
        approvalPolicy = ApprovalPolicy.NEVER,
        sandbox = DispatchSandbox.DANGER_FULL_ACCESS,
        cwd = workspacePath,
        personality = CodexAssistantProfile.personality,
        reasoningSummary = CodexAssistantProfile.reasoningSummary,
    )

    companion object {
        fun from(settings: HansSettings): DispatchSelection = DispatchSelection(
            model = settings.model,
            effort = ReasoningEffort.of(settings.reasoningEffort),
            serviceTier = settings.serviceTier,
        )
    }
}

/**
 * Resolves a user/runtime preference against the current authoritative model/list snapshot.
 *
 * The requested effort is retained when the selected model supports it. Otherwise the model's
 * advertised default is used, followed by Hans's stable effort order as a defensive fallback.
 * A missing requested model falls back to the advertised default Hans model, then to Hans's
 * stable model order. Hidden and otherwise unusable models never become implicit selections.
 */
internal fun resolveDispatchSelection(
    models: List<CodexModel>,
    requestedModel: String,
    requestedEffort: ReasoningEffort,
    requestedServiceTier: String? = null,
): DispatchSelection? {
    val modelRank = HansSettings.MODEL_ORDER.withIndex().associate { it.value to it.index }
    val usableModels = models
        .asSequence()
        .filterNot(CodexModel::hidden)
        .filter { it.wireModel in HansSettings.SUPPORTED_MODELS }
        .filter { model ->
            model.supportedEfforts.any {
                it.wireValue in HansSettings.SUPPORTED_REASONING_EFFORTS
            }
        }
        .sortedBy { modelRank[it.wireModel] ?: Int.MAX_VALUE }
        .toList()
    val model = usableModels.firstOrNull { it.wireModel == requestedModel }
        ?: usableModels.firstOrNull(CodexModel::isDefault)
        ?: usableModels.firstOrNull()
        ?: return null
    val supported = model.supportedEfforts.filterTo(linkedSetOf()) {
        it.wireValue in HansSettings.SUPPORTED_REASONING_EFFORTS
    }
    val effort = requestedEffort.takeIf(supported::contains)
        ?: model.defaultEffort.takeIf(supported::contains)
        ?: HansSettings.REASONING_EFFORT_ORDER
            .asSequence()
            .map(ReasoningEffort::of)
            .firstOrNull(supported::contains)
        ?: return null
    val requestedTier = requestedServiceTier ?: HansSettings.DEFAULT_SERVICE_TIER
    val serviceTier = when {
        requestedTier == HansSettings.DEFAULT_SERVICE_TIER -> requestedTier
        requestedTier in HansSettings.SUPPORTED_SERVICE_TIERS &&
            model.serviceTiers.any { it.id == requestedTier } -> requestedTier
        else -> HansSettings.DEFAULT_SERVICE_TIER
    }
    return DispatchSelection(model.wireModel, effort, serviceTier)
}

internal fun supportedReasoningEfforts(
    models: List<CodexModel>,
    model: String,
): List<ReasoningEffort> {
    val advertised = models.firstOrNull {
        !it.hidden && it.wireModel == model && it.wireModel in HansSettings.SUPPORTED_MODELS
    } ?: return emptyList()
    return HansSettings.REASONING_EFFORT_ORDER.map(ReasoningEffort::of)
        .filter(advertised.supportedEfforts::contains)
}

enum class OutboundMessageStatus {
    PENDING,
    SENT,
    FAILED,
}

data class OutboundUserMessageUi(
    val clientUserMessageId: String,
    val threadId: String,
    val displayText: String,
    val status: OutboundMessageStatus,
    val retryable: Boolean,
    /** Filled only after the correlated turn/start or turn/steer response is accepted. */
    val turnId: String? = null,
)

/** Bounded, content-free receipt used to correlate background automation completion exactly. */
data class ClientTerminalTurn(
    val threadId: String,
    val turnId: String,
    val status: TurnStatus,
) {
    init {
        require(status != TurnStatus.IN_PROGRESS)
    }
}

enum class ClientTimelineRole {
    USER,
    HANS,
    SYSTEM,
    TOOL,
}

enum class ClientTimelineStatus {
    PENDING,
    SENT,
    FAILED,
    STREAMING,
    COMPLETE,
    IN_PROGRESS,
    DECLINED,
}

data class ClientTimelineItem(
    val id: String,
    val role: ClientTimelineRole,
    val text: String,
    val order: Long,
    val revision: Long,
    val complete: Boolean,
    val status: ClientTimelineStatus,
    /** Present only for Hans messages; keeps final-only TTS semantically exact. */
    val agentPhase: AgentMessagePhase? = null,
    /** Exact App Server turn correlation; never inferred from visible text. */
    val turnId: String? = null,
)

/** Must stay aligned with the launcher's chat projection when anchoring local-only messages. */
internal fun List<ClientTimelineItem>.latestVisibleChatTimelineId(): String? = asSequence()
    .filter { it.text.isNotBlank() || it.role == ClientTimelineRole.TOOL }
    .maxByOrNull(ClientTimelineItem::order)
    ?.id

data class CodexClientSnapshot(
    val runtimePhase: ClientRuntimePhase,
    val sessionPhase: ClientSessionPhase,
    val generation: Long?,
    val session: SessionUiSnapshot,
    val models: List<CodexModel>,
    val deviceCodeLogin: DeviceCodeLoginUi?,
    val outboundTimeline: List<OutboundUserMessageUi>,
    val timeline: List<ClientTimelineItem>,
    val pendingSelection: DispatchSelection?,
    val confirmedSelection: DispatchSelection,
    val problem: CodexClientProblem?,
    val plugins: PluginDomainSnapshot = PluginDomainSnapshot.EMPTY,
    /** Count only; raw tool names, arguments and results never enter UI state. */
    val pendingDynamicToolCalls: Int = 0,
    /** Bounded terminal receipts contain identifiers/status only, never prompts or outputs. */
    val terminalTurns: List<ClientTerminalTurn> = emptyList(),
    /** Turns proven to have invoked Hans setup/profile tools. Contains identifiers only. */
    val setupTurnIds: Set<String> = emptySet(),
    /** Proven App Server availability of the bundled setup plugin and its enabled skill. */
    val bundledSetupBootstrapStatus: BundledSetupBootstrapStatus =
        BundledSetupBootstrapStatus.DISABLED,
    /** Content-free receipts used only by the publisher-key migration diagnostic. */
    val migrationReadiness: CodexMigrationReadiness = CodexMigrationReadiness(),
    /** Latest requested next-turn settings, not yet acknowledged by the App Server. */
    val pendingSettingsSelection: DispatchSelection? = null,
    /** Optional incoming Desktop access; pairing material is process-memory only. */
    val remoteControl: ai.hans.standard.remotecontrol.RemoteControlSnapshot =
        ai.hans.standard.remotecontrol.RemoteControlSnapshot(),
)

data class CodexMigrationReadiness(
    val accountReadComplete: Boolean = false,
    val threadResumeConfirmed: Boolean = false,
    val memoryModeEnabledAck: Boolean = false,
    val activeTurn: Boolean = false,
    /** Runtime-proven selection; an active turn retains its own model despite next-turn updates. */
    val effectiveSelection: DispatchSelection? = null,
)

internal fun migrationTurnOrToolWorkActive(
    activeTurnPresent: Boolean,
    unknownActiveTurnPresent: Boolean,
    pendingDispatchPresent: Boolean,
    pendingDynamicToolCallCount: Int,
): Boolean {
    require(pendingDynamicToolCallCount >= 0)
    return activeTurnPresent ||
        unknownActiveTurnPresent ||
        pendingDispatchPresent ||
        pendingDynamicToolCallCount > 0
}

fun interface CodexClientObserver {
    fun onSnapshot(snapshot: CodexClientSnapshot)
}

fun interface ClientMessageIdFactory {
    fun nextId(): String

    companion object {
        val UUIDS = ClientMessageIdFactory { "hans-${UUID.randomUUID()}" }
    }
}

fun interface DeveloperInstructionsProvider {
    fun currentInstructions(): String?

    companion object {
        val EMPTY = DeveloperInstructionsProvider { null }
    }
}

internal fun boundedTimelineText(value: String): String =
    if (value.length <= ProtocolLimits.MAX_UI_MESSAGE_CHARS) {
        value
    } else {
        value.take(ProtocolLimits.MAX_UI_MESSAGE_CHARS - 1) + "…"
    }
