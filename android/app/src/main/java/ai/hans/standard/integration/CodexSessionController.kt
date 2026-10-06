package ai.hans.standard.integration

import ai.hans.standard.BuildConfig
import ai.hans.standard.codex.AccountLogoutResult
import ai.hans.standard.codex.AccountReadResult
import ai.hans.standard.codex.AccountIdentity
import ai.hans.standard.codex.ActiveTurn
import ai.hans.standard.codex.AppServerEventDecoder
import ai.hans.standard.codex.AppServerMethod
import ai.hans.standard.codex.AppServerRequests
import ai.hans.standard.codex.AppServerResult
import ai.hans.standard.codex.CodexInput
import ai.hans.standard.codex.CodexSessionReducer
import ai.hans.standard.codex.CorrelatedResponse
import ai.hans.standard.codex.CrossCorrelationException
import ai.hans.standard.codex.DeliveredServerEvent
import ai.hans.standard.codex.DeliveryCursor
import ai.hans.standard.codex.DeliveryDisposition
import ai.hans.standard.codex.DeviceCodeLoginResult
import ai.hans.standard.codex.DispatchOptions
import ai.hans.standard.codex.DispatchPlan
import ai.hans.standard.codex.DispatchPolicy
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolProtocol
import ai.hans.standard.codex.DynamicToolServerCall
import ai.hans.standard.codex.EncodedRequest
import ai.hans.standard.codex.ExtensionAppServerResult
import ai.hans.standard.codex.FrameLimitException
import ai.hans.standard.codex.JsonContract
import ai.hans.standard.codex.ModelCatalog
import ai.hans.standard.codex.ModelListAccumulator
import ai.hans.standard.codex.ModelListResult
import ai.hans.standard.codex.ProtocolException
import ai.hans.standard.codex.ProtocolLimits
import ai.hans.standard.codex.RequestId
import ai.hans.standard.codex.RequestIdSequence
import ai.hans.standard.codex.RecoveredConversationItem
import ai.hans.standard.codex.RemoteError
import ai.hans.standard.codex.ResponseCorrelator
import ai.hans.standard.codex.ServerEvent
import ai.hans.standard.codex.ServerRequestDecodeResult
import ai.hans.standard.codex.ServerRequestId
import ai.hans.standard.codex.SkillsListResult
import ai.hans.standard.codex.ThreadResumeResult
import ai.hans.standard.codex.ThreadMemoryModeSetResult
import ai.hans.standard.codex.ThreadMaterializeResult
import ai.hans.standard.codex.ThreadStartResult
import ai.hans.standard.codex.ThreadSettingsUpdateResult
import ai.hans.standard.codex.TurnInterruptResult
import ai.hans.standard.codex.TurnStartResult
import ai.hans.standard.codex.NotificationToolOutputTurnResult
import ai.hans.standard.codex.TurnStatus
import ai.hans.standard.codex.TurnSteerResult
import ai.hans.standard.plugins.AppListPageWireResult
import ai.hans.standard.plugins.MarketplaceAddWireResult
import ai.hans.standard.plugins.MarketplaceUpgradeWireResult
import ai.hans.standard.plugins.PluginAppServerRequests
import ai.hans.standard.plugins.PluginCatalogReducer
import ai.hans.standard.plugins.PluginConnectionActionKind
import ai.hans.standard.plugins.PluginHandle
import ai.hans.standard.plugins.PluginInstallWireResult
import ai.hans.standard.plugins.PluginInstallRuntimePreparation
import ai.hans.standard.plugins.PluginInstallTransactionCoordinator
import ai.hans.standard.plugins.PluginListWireResult
import ai.hans.standard.plugins.PluginOperationFailure
import ai.hans.standard.plugins.PluginOperationKind
import ai.hans.standard.plugins.PluginReadWireResult
import ai.hans.standard.plugins.PluginRemoteMcpOAuthTarget
import ai.hans.standard.plugins.PluginRemoteMcpPolicyReviewSnapshot
import ai.hans.standard.plugins.PluginRemoteMcpPolicyReviewTarget
import ai.hans.standard.plugins.PluginSourceKind
import ai.hans.standard.plugins.PluginUninstallWireResult
import ai.hans.standard.plugins.PluginWireRecord
import ai.hans.standard.plugins.SkillConfigWriteWireResult
import ai.hans.standard.plugins.flattenSkills
import ai.hans.standard.plugins.install.PluginInstallDeadline
import ai.hans.standard.plugins.install.PluginInstallDeadlineIdentity
import ai.hans.standard.plugins.install.PluginInstallDeadlineLease
import ai.hans.standard.plugins.install.PluginInstallRecoveryClaim
import ai.hans.standard.plugins.install.PluginInstallRecoveryExecution
import ai.hans.standard.plugins.runtime.PluginSurfaceDeclarationProbe
import ai.hans.standard.plugins.runtime.PluginSurfaceDeclarationReceipt
import ai.hans.standard.plugins.runtime.PluginSurfaceEvidenceStageResult
import ai.hans.standard.plugins.runtime.PluginSurfaceEvidenceStager
import ai.hans.standard.plugins.uninstall.PluginUninstallExecution
import ai.hans.standard.plugins.uninstall.PluginUninstallPreparation
import ai.hans.standard.plugins.uninstall.PluginUninstallRecoveryClaim
import ai.hans.standard.plugins.uninstall.PluginUninstallTransactionCoordinator
import ai.hans.standard.runtime.AppServerSessionContract
import ai.hans.standard.runtime.BundledSetupPluginContract
import ai.hans.standard.settings.HansSettings
import ai.hans.standard.settings.HansSettingsStore
import ai.hans.standard.diagnostics.PerformanceEvent
import ai.hans.standard.diagnostics.PerformancePhase
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

fun interface SetupDispatchDeadline {
    fun cancel()
}

fun interface SetupDispatchDeadlineScheduler {
    fun schedule(delayMillis: Long, task: () -> Unit): SetupDispatchDeadline
}

private object ProcessSetupDispatchDeadlineScheduler : SetupDispatchDeadlineScheduler {
    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "hans-setup-dispatch-deadline").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
    }

    override fun schedule(delayMillis: Long, task: () -> Unit): SetupDispatchDeadline {
        val future = executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
        return SetupDispatchDeadline { future.cancel(false) }
    }
}

/**
 * Synchronous boundary receipt for one user-turn dispatch.
 *
 * [RejectedBeforeTransport] is the only result which proves that no App Server request could have
 * left this process. A Binder/send exception is deliberately [TransportOutcomeAmbiguous]: the
 * remote process may have accepted the frame before the local transport reported failure.
 */
sealed interface CodexDispatchAttemptResult {
    data class Accepted(val clientUserMessageId: String) : CodexDispatchAttemptResult
    data object RejectedBeforeTransport : CodexDispatchAttemptResult
    data object TransportOutcomeAmbiguous : CodexDispatchAttemptResult

    val acceptedMessageId: String?
        get() = (this as? Accepted)?.clientUserMessageId
}

class BundledSetupPluginBootstrap private constructor(
    val marketplaceRoot: String?,
    val expectedPluginVersion: String?,
) {
    val enabled: Boolean
        get() = marketplaceRoot != null && expectedPluginVersion != null

    companion object {
        fun forWorkspace(workspacePath: String): BundledSetupPluginBootstrap =
            BundledSetupPluginBootstrap(
                BundledSetupPluginContract.marketplaceRootForWorkspace(workspacePath).absolutePath,
                BuildConfig.BUNDLED_SETUP_PLUGIN_VERSION,
            )

        fun disabled(): BundledSetupPluginBootstrap = BundledSetupPluginBootstrap(null, null)
    }
}

/**
 * Main-process orchestration for one persistent App Server session. It never
 * logs raw frames, auth material, prompts, responses, or runtime details.
 */
internal class CodexSessionController(
    private val transport: SessionRuntimeTransport,
    private val sessionStore: CodexSessionStore,
    private val visibleInputReceipts: VisibleInputReceiptStore = VisibleInputReceiptStore.NONE,
    private val settingsStore: HansSettingsStore,
    private val clientMessageIds: ClientMessageIdFactory = ClientMessageIdFactory.UUIDS,
    private val developerInstructions: DeveloperInstructionsProvider =
        DeveloperInstructionsProvider.EMPTY,
    private val dynamicToolExecutor: DynamicToolExecutor? = null,
    private val protocolDiagnostics: (String) -> Unit = {},
    private val bundledSetupPlugin: BundledSetupPluginBootstrap =
        BundledSetupPluginBootstrap.forWorkspace(sessionStore.workspacePath),
    private val setupDispatchDeadlineScheduler: SetupDispatchDeadlineScheduler =
        ProcessSetupDispatchDeadlineScheduler,
    private val setupDispatchTimeoutMillis: Long = DEFAULT_SETUP_DISPATCH_TIMEOUT_MILLIS,
    private val selectionUpdateDeadlineScheduler: SetupDispatchDeadlineScheduler =
        ProcessSetupDispatchDeadlineScheduler,
    private val selectionUpdateTimeoutMillis: Long = 15_000L,
    private val remoteControlDeadlineScheduler: SetupDispatchDeadlineScheduler =
        ProcessSetupDispatchDeadlineScheduler,
    private val pluginInstallTransactions: PluginInstallTransactionCoordinator? = null,
    private val pluginUninstallTransactions: PluginUninstallTransactionCoordinator? = null,
    private val pluginSurfaceEvidenceStager: PluginSurfaceEvidenceStager? = null,
    private val pluginPreparationExecutor: Executor = Executor { command -> command.run() },
    private val pluginInstallDeadline: PluginInstallDeadline? = null,
    private val pluginInstallDeadlineNowMillis: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val pluginInstallTimeoutMillis: Long = DEFAULT_PLUGIN_INSTALL_TIMEOUT_MILLIS,
    private val workInterruptDeadlineScheduler: SetupDispatchDeadlineScheduler =
        ProcessSetupDispatchDeadlineScheduler,
    private val workInterruptTimeoutMillis: Long = 10_000L,
    private val phoneToolsMcpConfigured: Boolean = false,
    private val performanceObserver: PerformanceSessionObserver = PerformanceSessionObserver.NONE,
    private val realtimeDeadlineScheduler: SetupDispatchDeadlineScheduler = ProcessSetupDispatchDeadlineScheduler,
    private val realtimeAudioDeadlineScheduler: SetupDispatchDeadlineScheduler = ProcessSetupDispatchDeadlineScheduler,
    private val realtimeDrainDeadlineScheduler: SetupDispatchDeadlineScheduler = ProcessSetupDispatchDeadlineScheduler,
    private val desktopRemoteAccessEnabled: Boolean = ai.hans.standard.remotecontrol.DesktopRemoteAccessPolicy.enabled,
    private val notificationExternalDeadlineScheduler: SetupDispatchDeadlineScheduler = ProcessSetupDispatchDeadlineScheduler,
    private val notificationExternalTimeoutMillis: Long = 20_000L,
) : RuntimeSessionListener {
    // Diagnostic-only state. Never exported, persisted, or used to authorize work.
    private var performanceObservedTurnId: String? = null
    private var performanceLastTerminalTurnId: String? = null
    private val performanceTerminalTurnIds = LinkedHashSet<String>()
    private var performanceContextThreadId: String? = null
    private var performanceUserWaitReported = false
    private var performanceAssistantOutputObserved = false
    private var replayingPerformanceEvents = false
    private val requestIds = RequestIdSequence()
    private val retiredFreshBootstrapRequestIds = LinkedHashSet<RequestId>()
    private val realtime = CodexRealtimeCoordinator(send = { epoch, wire ->
        if (epoch != generation || runtimePhase != ClientRuntimePhase.READY) false
        else runCatching {
            transport.sendFrame(epoch, wire.toByteArray(StandardCharsets.UTF_8))
            true
        }.getOrDefault(false)
    }, scheduleAudioDeadline = { task ->
        realtimeAudioDeadlineScheduler.schedule(10_000L) { synchronized(this) { task() } }
    }, scheduleDrainDeadline = { task ->
        realtimeDrainDeadlineScheduler.schedule(10_000L) { synchronized(this) { task() } }
    }, onDrainRecoveryRequired = { notifyObservers() }, localHandoffTurn = { epoch, threadId ->
        (activeTurn?.turnId ?: unknownActiveTurnId)?.takeIf { turnId ->
            epoch == generation && voiceControlTurnIsLocal(threadId, turnId)
        }
    })
    private var realtimeDeadline: SetupDispatchDeadline? = null
    private var preparingRealtime: PreparedRealtime? = null
    private val realtimeTurnAccounts = LinkedHashMap<String, ai.hans.standard.codex.AccountIdentity>()
    private var realtimeOriginAccount: ai.hans.standard.codex.AccountIdentity? = null
    private val voiceControlTurnProofs = LinkedHashMap<String, VoiceControlTurnProof>()
    private var voiceControlProofCapacityExceeded = false
    private var realtimeDispatchWaiter: RealtimeDispatchWaiter? = null
    private var lastRealtimeFrameSequence = 0L
    private val remoteControl = ai.hans.standard.remotecontrol.RemoteControlCoordinator(
        sendRequest = { request ->
            val epoch = generation
            if (epoch == null || runtimePhase != ClientRuntimePhase.READY) false
            else runCatching {
                transport.sendFrame(epoch, request.wireJson.toByteArray(StandardCharsets.UTF_8))
                true
            }.getOrDefault(false)
        },
    )
    private var remoteControlDeadline: SetupDispatchDeadline? = null
    private var remoteControlDeadlineAt: Long? = null
    private var remotePolicyProbedGeneration: Long? = null
    private var remotePolicyDisableAttempted = false
    private val remoteControlledTurnIds = LinkedHashSet<String>()
    private var remoteLogoutRequiresRuntimeRestart = false
    private var nextOperationId = 1L
    private var correlator = ResponseCorrelator()
    private val requestPurposes = LinkedHashMap<RequestId, RequestPurpose>()
    private val pluginInstallDeadlines = LinkedHashMap<String, PluginInstallDeadlineLease>()
    /** Serializes durable recovery mutations with generation cancellation, never UI projection. */
    private val pluginRecoveryExecutionLock = Any()
    private val reducer = CodexSessionReducer()
    private val pluginReducer = PluginCatalogReducer()
    private val observers = LinkedHashSet<CodexClientObserver>()
    private val outbound = LinkedHashMap<String, MutableOutbound>()
    private val terminalTurnIds = LinkedHashSet<String>()
    private val terminalTurns = LinkedHashMap<String, ClientTerminalTurn>()
    private val bufferedEvents = ArrayDeque<BufferedEvent>()
    private val timelineMetadata = LinkedHashMap<String, TimelineMetadata>()
    private var recoveredTimeline: List<RecoveredTimelineEntry> = emptyList()
    private var recoveredAgentChannelHistory: AgentChannelRecoveredHistory? = null
    private var nativeNotificationHistory: NativeNotificationExternalHistory? = null
    private val nativeNotificationTurnIds = LinkedHashSet<String>()
    private val nativeNotificationUnprovenTurnIds = LinkedHashSet<String>()
    private val pendingNativeNotifications = LinkedHashMap<RequestId, RequestPurpose.NotificationToolOutput>()
    private val retiredNativeNotificationRequests = LinkedHashSet<RequestId>()
    private val nativeNotificationDeadlines = LinkedHashMap<RequestId, SetupDispatchDeadline>()
    private var agentChannelHistoryRevision = 0L
    private val pendingDynamicCalls = LinkedHashMap<ServerRequestId, PendingDynamicCall>()
    private val pendingDynamicCallIds = LinkedHashMap<DynamicCallIdentity, ServerRequestId>()
    private val completedDynamicResults =
        LinkedHashMap<DynamicCallIdentity, DynamicToolExecutionResult>()
    private val completedDynamicRequestIds = LinkedHashSet<ServerRequestId>()
    private val dynamicToolTurnAuthorizationGate = DynamicToolTurnAuthorizationGate()
    private val remotePhoneTools = dynamicToolExecutor?.let {
        ai.hans.standard.remotecontrol.RemotePhoneToolAuthority(it)
    }
    private val remoteInterruptRequests = LinkedHashSet<String>()
    private var nextRemoteInterrupt = 1L
    private val setupTurnIds = LinkedHashSet<String>()

    private var runtimePhase = ClientRuntimePhase.STOPPED
    private var sessionPhase = ClientSessionPhase.IDLE
    private var generation: Long? = null
    // A failed controller/request is not proof that the RuntimeService lost its process.
    // Only runtime lifecycle receipts determine whether restart can address that generation.
    private var runtimeSessionActive = false
    private var bootstrappedGeneration: Long? = null
    private var problem: CodexClientProblem? = null
    private var modelPages = ModelListAccumulator()
    private var catalog: ModelCatalog? = null
    private var modelCatalogRefreshAfterAccountRead = false
    private var modelCatalogRefreshQueued = false
    private var modelCatalogSettingsRefreshInFlight = false
    private var lastConfirmedAccountSignedIn: Boolean? = null
    private var accountReadComplete = false
    private var signedIn = false
    private var resumedThreadId: String? = null
    private var memoryModeEnabledThreadId: String? = null
    private var threadBootstrapRequested = false
    private var threadReady = false
    private var rehydrating = false
    private var bufferedEventBytes = 0
    // A bounded in-memory receipt, never reconstructed from mutable thread defaults.
    private var retainedActiveTurn: ActiveTurn? = null
    private var activeTurn: ActiveTurn? = null
        set(value) {
            field = value
            if (value != null) retainedActiveTurn = value
        }
    private var unknownActiveTurnId: String? = null
    /** One current resume receipt proves identity, but not the active turn's model/options. */
    private var identityOnlyActiveTurn: Pair<String, String>? = null
    private var locallyStoppingDynamicTurn: Pair<String, String>? = null
    private var workInterruptRevision = 0L
    private val workInterruptDeadlines = LinkedHashMap<RequestId, SetupDispatchDeadline>()
    private val retiredWorkInterruptRequestIds = LinkedHashSet<RequestId>()
    private var localRuntimeStopRequested = false
    private var migrationEffectiveSelection: DispatchSelection? = null
    private var pendingDispatch: PendingDispatch? = null
    private var deviceCodeLogin: DeviceCodeLoginUi? = null
    private var pendingSelection: DispatchSelection? = null
    private var confirmedSelection = readConfirmedSelection()
    private var pendingSettingsUpdate: PendingSettingsUpdate? = null
    private var queuedSettingsSelection: DispatchSelection? = null
    private var settingsContextEpoch = 0L
    private val retiredSettingsRequestIds = LinkedHashSet<RequestId>()
    private var provenThreadSettingsSelection: DispatchSelection? = null
    private var lastThreadSettingsEventSequence = 0L
    private var lastReceivedFrameSequence = 0L
    /** A confirmed new default must not change an already-running turn's steer options. */
    private var settingsDefaultActiveTurn: Pair<String, String>? = null
    private var restartInFlight = false
    private var automaticRestartCount = 0
    private var nextTimelineOrder = 1L
    private var nextPluginOperationId = 1L
    private var pluginBootstrappedGeneration: Long? = null
    private var pluginRefreshQueued = false
    private var appRefreshQueued = false
    private var skillRefreshQueued = false
    private var bundledSetupBootstrapPhase = BundledSetupBootstrapPhase.IDLE
    private var bundledSetupProvenPluginHandle: PluginHandle? = null
    private var bundledSetupRetryQueued = false
    private var pendingSetupDispatchDeadline: PendingSetupDispatchDeadline? = null
    private var pluginRecoveryEpoch: PluginRecoveryEpoch? = null
    private var pluginRecoveryBatch: PendingPluginRecoveryBatch? = null
    private var pluginRecoveryGateInProgress = false
    private var pluginMutationsBlockedByRecovery =
        pluginInstallTransactions != null || pluginUninstallTransactions != null
    private var pluginUninstallRecoveryEpoch: PluginUninstallRecoveryEpoch? = null
    private var pluginUninstallRecoveryBatch: PendingPluginUninstallRecoveryBatch? = null

    init {
        require(sessionStore.workspacePath.startsWith('/')) {
            "Codex workspace must be an absolute app-private path"
        }
        require(setupDispatchTimeoutMillis in 1..MAX_SETUP_DISPATCH_TIMEOUT_MILLIS) {
            "Setup dispatch timeout must be positive and bounded"
        }
    }

    @Synchronized
    fun addObserver(observer: CodexClientObserver) {
        observers.add(observer)
        observer.onSnapshot(snapshotLocked())
    }

    @Synchronized
    fun removeObserver(observer: CodexClientObserver) {
        observers.remove(observer)
    }

    @Synchronized
    fun snapshot(): CodexClientSnapshot = snapshotLocked()

    /** Passive restart-recovery receipts only; never requests history or starts a model turn. */
    @Synchronized
    fun agentChannelRecoveredHistory(): AgentChannelRecoveredHistory? {
        if (!signedIn || runtimePhase != ClientRuntimePhase.READY || !threadReady || rehydrating) return null
        return recoveredAgentChannelHistory?.takeIf { it.threadId == reducer.snapshot().currentThreadId }
    }

    /** Positive content-free receipts only; absence from bounded history never proves unsent. */
    @Synchronized
    fun notificationExternalHistory(): NativeNotificationExternalHistory? {
        if (!signedIn || runtimePhase != ClientRuntimePhase.READY || !threadReady || rehydrating) return null
        return nativeNotificationHistory?.takeIf { it.threadId == reducer.snapshot().currentThreadId }
    }

    @Synchronized
    fun refreshNotificationExternalHistory(expectedThreadId: String): Boolean {
        if (!signedIn || runtimePhase != ClientRuntimePhase.READY || !threadReady || rehydrating ||
            reducer.snapshot().currentThreadId != expectedThreadId ||
            requestPurposes.values.any { it is RequestPurpose.NotificationHistory }) return false
        val request = AppServerRequests.notificationExternalHistory(requestIds.next(), expectedThreadId,
            ai.hans.standard.codex.ExtensionResultDecoder { result ->
                NativeNotificationHistoryPage(NativeNotificationExternalHistoryDecoder.page(expectedThreadId, result))
            })
        return transmitAttempt(request, RequestPurpose.NotificationHistory(expectedThreadId),
            restartOnAmbiguity = false) == RequestTransmissionResult.SENT
    }

    /** No outbound user item, selection change, visible-input receipt, or steer is created. */
    @Synchronized
    fun dispatchNotificationEvent(
        message: NativeNotificationExternalMessage,
        expectedThreadId: String,
        beforeTransport: () -> Boolean,
        onReceipt: (NativeNotificationDispatchReceipt) -> Unit,
    ): NativeNotificationDispatchResult {
        if (runtimePhase != ClientRuntimePhase.READY || !signedIn || !threadReady || rehydrating ||
            sessionPhase !in setOf(ClientSessionPhase.READY, ClientSessionPhase.BUSY) ||
            pendingDispatch != null || pendingNativeNotifications.isNotEmpty() || localRuntimeStopRequested ||
            locallyStoppingDynamicTurn?.let {
                it.first == expectedThreadId && it.second == (activeTurn?.turnId ?: unknownActiveTurnId)
            } == true ||
            (activeTurn?.turnId ?: unknownActiveTurnId)?.let { it in remoteControlledTurnIds } == true ||
            reducer.snapshot().currentThreadId != expectedThreadId ||
            (pendingSettingsUpdate != null && activeTurn == null && unknownActiveTurnId == null)) {
            return NativeNotificationDispatchResult.RejectedBeforeTransport
        }
        val id = requestIds.next()
        val request = AppServerRequests.notificationToolOutputTurnStart(id, expectedThreadId, message.payloadJson)
        // The source owner durably pins its lease and event identity only after encoding/preflight.
        if (!runCatching(beforeTransport).getOrElse {
                protocolDiagnostics("notification_event_source_seal_failed")
                false
            }) return NativeNotificationDispatchResult.RejectedBeforeTransport
        val purpose = RequestPurpose.NotificationToolOutput(expectedThreadId, message.eventId,
            message.payloadSha256, onReceipt)
        pendingNativeNotifications[id] = purpose
        val transmission = transmitAttempt(request, purpose, restartOnAmbiguity = false)
        if (transmission == RequestTransmissionResult.REJECTED_BEFORE_TRANSPORT) {
            pendingNativeNotifications.remove(id)
        }
        // Ambiguous sends keep correlation for a possible late actual native acceptance.
        notifyObservers()
        return when (transmission) {
            RequestTransmissionResult.SENT -> NativeNotificationDispatchResult.Submitted(expectedThreadId, message.eventId)
            RequestTransmissionResult.REJECTED_BEFORE_TRANSPORT -> NativeNotificationDispatchResult.RejectedBeforeTransport
            RequestTransmissionResult.OUTCOME_AMBIGUOUS -> NativeNotificationDispatchResult.TransportOutcomeAmbiguous
        }
    }

    /** Explicit local Settings actions only; no model tool can enable incoming access. */
    @Synchronized
    fun remoteControlSettingsOpened(): Boolean {
        val epoch = generation ?: return false
        if (runtimePhase != ClientRuntimePhase.READY || !signedIn) return false
        val changedGeneration = remoteControl.snapshot.generation != epoch
        if (changedGeneration) remoteControl.onRuntimeReady(epoch) else remoteControl.refreshStatus()
        remoteControlChanged()
        return true
    }

    @Synchronized
    fun remoteControlEnable(): Boolean = remoteControlAction {
        if (!desktopRemoteAccessEnabled || preparingRealtime != null || realtime.lease != null || realtimeTurnAccounts.isNotEmpty()) false
        else remoteControl.enableFromLocalUserConsent()
    }

    @Synchronized
    fun remoteControlDisable(): Boolean {
        interruptRemotePhoneTurns()
        // Withdrawal stops already observed remote work as well as preventing later phone calls.
        val turnId = activeTurn?.turnId ?: unknownActiveTurnId
        if (turnId != null && turnId in remoteControlledTurnIds) interrupt()
        return remoteControlAction { remoteControl.disableFromLocalUserAction() }
    }

    @Synchronized
    fun remoteControlPair(): Boolean = remoteControlAction {
        desktopRemoteAccessEnabled && remoteControl.startPairingFromLocalUserAction()
    }

    @Synchronized
    fun remoteControlRefresh(): Boolean = remoteControlSettingsOpened()

    @Synchronized
    fun remoteControlRefreshClients(): Boolean = remoteControlAction { remoteControl.refreshClients() }

    @Synchronized
    fun remoteControlLoadMoreClients(): Boolean = remoteControlAction { remoteControl.loadMoreClients() }

    @Synchronized
    fun remoteControlRevoke(clientId: String): Boolean {
        interruptRemotePhoneTurns()
        // The protocol does not identify the originating desktop on turn/started.
        val turnId = activeTurn?.turnId ?: unknownActiveTurnId
        if (turnId != null && turnId in remoteControlledTurnIds) interrupt()
        return remoteControlAction { remoteControl.revokeClientFromLocalUserAction(clientId) }
    }

    @Synchronized
    fun remoteControlCheckPairing(): Boolean = remoteControlAction { remoteControl.refreshPairingStatus() }

    private fun updateRemotePhoneToolAuthority() {
        remotePhoneTools?.updateState(ai.hans.standard.remotecontrol.RemotePhoneToolRuntime(
            generation = generation,
            allowed = desktopRemoteAccessEnabled && signedIn && runtimePhase == ClientRuntimePhase.READY &&
                remoteControl.snapshot.mayUsePhoneToolsRemotely && !localRuntimeStopRequested &&
                !dynamicToolTurnAuthorizationGate.blocksRemoteTools,
            localThreadId = reducer.snapshot().currentThreadId,
            notificationRestrictedTurnIds = dynamicToolTurnAuthorizationGate.restrictedTurnIds,
        ))
    }

    /** HTTP is only a transport; the same immutable executor and runtime authority own effects. */
    fun executeRemotePhoneTool(
        params: ai.hans.standard.codex.DynamicToolCallParams,
        cancellation: DynamicToolCancellation,
        onResult: (DynamicToolExecutionResult) -> Unit,
    ): DynamicToolExecutionHandle {
        val authority = synchronized(this) {
            updateRemotePhoneToolAuthority()
            remotePhoneTools?.takeUnless { dynamicToolTurnAuthorizationGate.blocks(params) }
        }
        if (authority == null) {
            onResult(DynamicToolExecutionResult("{\"error\":\"remote_phone_tools_not_authorized\"}", false))
            return object : DynamicToolExecutionHandle {
                override fun cancel() = ai.hans.standard.codex.DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT
            }
        }
        return authority.execute(params, DynamicToolCancellation {
            cancellation.isCancellationRequested() || dynamicToolTurnAuthorizationGate.blocks(params)
        }, onResult)
    }

    @Synchronized fun closeRemotePhoneTools() { remotePhoneTools?.close() }

    private fun interruptRemotePhoneTurns() {
        val turns = remotePhoneTools?.activeTurns.orEmpty()
        remotePhoneTools?.cancelAll()
        val epoch = generation ?: return
        turns.forEach { (threadId, turnId) ->
            val id = "hans:remote-interrupt:${nextRemoteInterrupt++}"
            if (remoteInterruptRequests.size >= 64) return@forEach
            remoteInterruptRequests += id
            runCatching { transport.sendFrame(epoch, JSONObject().put("id", id)
                .put("method", "turn/interrupt")
                .put("params", JSONObject().put("threadId", threadId).put("turnId", turnId))
                .toString().toByteArray(StandardCharsets.UTF_8)) }
        }
    }

    private inline fun remoteControlAction(action: () -> Boolean): Boolean {
        if (runtimePhase != ClientRuntimePhase.READY || !signedIn) return false
        val accepted = action()
        remoteControlChanged()
        return accepted
    }

    private fun remoteControlChanged() {
        enforceDesktopRemotePolicy()
        continuePreparedRealtime()
        if (!desktopRelayIsQuiescent()) {
            realtime.revokeOrigin()
            realtimeOriginAccount = null
            if (realtime.lease != null) invalidateRealtime()
        }
        updateRemotePhoneToolAuthority()
        val deadline = remoteControl.snapshot.nextDeadlineAtMillis
        if (deadline != remoteControlDeadlineAt) {
            remoteControlDeadline?.cancel()
            remoteControlDeadline = null
            remoteControlDeadlineAt = deadline
            if (deadline != null) {
                val epoch = generation
                remoteControlDeadline = remoteControlDeadlineScheduler.schedule(
                    (deadline - System.currentTimeMillis()).coerceAtLeast(1),
                ) {
                    synchronized(this) {
                        if (generation == epoch && remoteControlDeadlineAt == deadline) {
                            remoteControlDeadlineAt = null
                            remoteControlDeadline = null
                            remoteControl.onDeadline()
                            remoteControlChanged()
                        }
                    }
                }
            }
        }
        notifyObservers()
    }

    private fun invalidateRemoteControl(clearRemoteTurnIds: Boolean = true) {
        // Runtime lifecycle callers revoke local voice as well. Ordinary relay disable must
        // not borrow this path to disconnect a locally initiated voice call.
        remotePhoneTools?.cancelAll()
        remoteInterruptRequests.clear()
        remoteControlDeadline?.cancel()
        remoteControlDeadline = null
        remoteControlDeadlineAt = null
        remoteControl.onRuntimeUnavailable()
        // A later authenticated lifetime needs a fresh receipt even if the native process
        // generation stayed the same across logout. Ordinary account refreshes do not reset it.
        remotePolicyProbedGeneration = null
        remotePolicyDisableAttempted = false
        updateRemotePhoneToolAuthority()
        if (clearRemoteTurnIds) remoteControlledTurnIds.clear()
        remoteLogoutRequiresRuntimeRestart = false
    }

    private fun probeDesktopRemotePolicyOnce() {
        val epoch = generation ?: return
        if (desktopRemoteAccessEnabled || !signedIn || runtimePhase != ClientRuntimePhase.READY ||
            remotePolicyProbedGeneration == epoch) return
        remotePolicyProbedGeneration = epoch
        remoteControlSettingsOpened()
    }

    private fun enforceDesktopRemotePolicy() {
        if (desktopRemoteAccessEnabled || !signedIn || runtimePhase != ClientRuntimePhase.READY) return
        val remote = remoteControl.snapshot
        if (remote.generation != generation || !remote.statusConfirmedForCurrentRuntime) return
        if (remote.isDisabledConfirmed && remote.pendingOperation == null) {
            remotePolicyDisableAttempted = false
            return
        }
        if (remote.pendingOperation != null || remotePolicyDisableAttempted) return
        // Native startup normally already proves disabled. Contrary effective state is
        // explicitly revoked once; failures/timeouts retain the denial without retry loops.
        remotePolicyDisableAttempted = true
        remoteControlDisable()
    }

    /** Returns true when spontaneous authentication loss has started native relay teardown. */
    private fun revokeRemoteControlOnAuthenticationLoss(): Boolean {
        val remote = remoteControl.snapshot
        if (!remote.runtimeReady) return false // Ordinary signed-out bootstrap must not restart.
        if (requestPurposes.values.any { it is RequestPurpose.Logout }) {
            // Explicit logout already withdrew consent before transmitting its requests. Preserve
            // both correlations until logout ACK proves completion; do not interrupt that write.
            return false
        }
        if (!remote.isDisabledConfirmed ||
            remote.pendingOperation == ai.hans.standard.remotecontrol.RemoteControlOperation.ENABLE
        ) {
            // Restart invalidates admission and pending tool dispatches before asking the existing
            // native supervisor to close the old process. No app stop, data reset or logout write.
            controlledRestart(CodexClientProblem(ClientProblemCode.AUTHENTICATION, retryable = true),
                automatic = false)
            return true
        }
        // A proved-off relay needs no process restart, but old external turns must stay labelled
        // so late phone calls cannot become ordinary local calls after admission is withdrawn.
        invalidateRemoteControl(clearRemoteTurnIds = false)
        return false
    }

    @Synchronized
    fun start() {
        if (runtimePhase == ClientRuntimePhase.STARTING ||
            runtimePhase == ClientRuntimePhase.READY ||
            runtimePhase == ClientRuntimePhase.RESTARTING
        ) {
            return
        }
        if (transport.protocolVersion != AppServerSessionContract.PROTOCOL_VERSION) {
            failPermanently(ClientProblemCode.PROTOCOL_VERSION, retryable = false)
            return
        }
        automaticRestartCount = 0
        restartInFlight = false
        runtimePhase = ClientRuntimePhase.STARTING
        sessionPhase = ClientSessionPhase.BOOTSTRAPPING
        problem = null
        notifyObservers()
        try {
            transport.start(nextOperation(), this)
        } catch (_: Exception) {
            failPermanently(ClientProblemCode.RUNTIME_FAILED, retryable = true)
        }
    }

    @Synchronized
    fun restart() {
        automaticRestartCount = 0
        controlledRestart(
            CodexClientProblem(ClientProblemCode.RUNTIME_FAILED, retryable = true),
            automatic = false,
        )
    }

    @Synchronized
    fun startRealtime(offerSdp: String, prompt: String, voice: String?, callbacks: CodexRealtimeCallbacks): CodexRealtimeCall? =
        startRealtime(offerSdp, prompt, voice, CodexRealtimeOptions(), callbacks)

    @Synchronized
    fun realtimeDiagnostics(): CodexRealtimeDiagnostics = realtime.diagnostics()

    /** Resolves only an earlier local native-handoff binding. The Host additionally rejects
     * automation before this lookup and again at the actual effect boundary. No model text or
     * current-session fallback can confer authority to end a newer call. */
    @Synchronized
    fun voiceControlSessionIdFor(call: DynamicToolCallParams): String? {
        val epoch = generation ?: return null
        if (!voiceControlTurnIsLocal(call.threadId, call.turnId)) return null
        return realtime.voiceControlSessionIdFor(epoch, call.threadId, call.turnId)
    }

    private fun voiceControlTurnIsLocal(threadId: String, turnId: String): Boolean {
        val proof = voiceControlTurnProofs[turnId] ?: return false
        if (voiceControlProofCapacityExceeded || !signedIn || rehydrating || !threadReady ||
            runtimePhase != ClientRuntimePhase.READY || localRuntimeStopRequested ||
            proof.generation != generation || proof.threadId != threadId ||
            proof.account != reducer.snapshot().account.identity ||
            reducer.snapshot().currentThreadId != threadId ||
            (activeTurn?.turnId ?: unknownActiveTurnId) != turnId ||
            turnId in terminalTurnIds || turnId in remoteControlledTurnIds || turnId in setupTurnIds ||
            locallyStoppingDynamicTurn == threadId to turnId || !desktopRelayIsQuiescent()) return false
        return !dynamicToolTurnAuthorizationGate.blocks(
            DynamicToolCallParams(threadId, turnId, "local-voice-authority", "hans_voice", "end_call", "{}"))
    }

    private fun rememberLocalVoiceControlTurn(threadId: String, turnId: String) {
        val epoch = generation ?: return
        val account = reducer.snapshot().account.identity as? ai.hans.standard.codex.AccountIdentity.ChatGpt ?: return
        if (!signedIn || rehydrating || turnId in terminalTurnIds || voiceControlProofCapacityExceeded) return
        if (turnId in voiceControlTurnProofs) return
        if (voiceControlTurnProofs.size >= 128) {
            // Do not evict an active first-owner proof and later reconstruct it under a new call.
            voiceControlProofCapacityExceeded = true
            return
        }
        voiceControlTurnProofs[turnId] = VoiceControlTurnProof(epoch, threadId, account)
    }

    private data class VoiceControlTurnProof(val generation: Long, val threadId: String,
        val account: ai.hans.standard.codex.AccountIdentity.ChatGpt)

    @Synchronized
    fun startRealtime(offerSdp: String, prompt: String, voice: String?, options: CodexRealtimeOptions,
        callbacks: CodexRealtimeCallbacks): CodexRealtimeCall? {
        val threadId = reducer.snapshot().currentThreadId
        val epoch = generation
        val issue = when {
            !signedIn || reducer.snapshot().account.identity !is ai.hans.standard.codex.AccountIdentity.ChatGpt ->
                CodexRealtimeIssue.CHATGPT_LOGIN_REQUIRED
            epoch == null || runtimePhase != ClientRuntimePhase.READY || !threadReady || rehydrating ||
                threadId == null || sessionPhase !in setOf(ClientSessionPhase.READY, ClientSessionPhase.BUSY) ->
                CodexRealtimeIssue.SESSION_NOT_READY
            realtime.requiresRecovery -> CodexRealtimeIssue.RECOVERY_REQUIRED
            preparingRealtime != null || realtime.lease != null -> CodexRealtimeIssue.ALREADY_ACTIVE
            else -> null
        }
        if (issue != null) {
            runCatching { callbacks.onStartRejected() }
            runCatching { callbacks.onError(issue) }
            return null
        }
        val pending = PreparedRealtime(checkNotNull(epoch), checkNotNull(threadId), offerSdp, prompt, voice,
            callbacks, options, checkNotNull(reducer.snapshot().account.identity))
        preparingRealtime = pending
        realtimeDeadline?.cancel()
        realtimeDeadline = realtimeDeadlineScheduler.schedule(45_000L) {
            synchronized(this) {
                val ownsDeadline = preparingRealtime === pending ||
                    (pending.lease != null && pending.lease == realtime.lease)
                if (generation != epoch || !ownsDeadline) return@synchronized
                if (preparingRealtime === pending) failPreparedRealtime(pending, CodexRealtimeIssue.TIMED_OUT)
                else pending.lease?.let { if (generation == epoch) realtime.timeout(it) }
                realtimeDeadline = null
            }
        }
        if (remoteControl.snapshot.generation != epoch || !remoteControl.snapshot.statusConfirmedForCurrentRuntime) {
            // Read-only capability/state probe, never silently disable or enable desktop access.
            remoteControlSettingsOpened()
        } else continuePreparedRealtime()
        return object : CodexRealtimeCall {
            override fun stop() {
                synchronized(this@CodexSessionController) {
                    if (preparingRealtime === pending) {
                        preparingRealtime = null
                        // Cancelled while only the local remote-status preflight existed.
                        // No native start frame has left; release the reader's native barrier.
                        runCatching { pending.callbacks.onStartRejected() }
                    }
                    pending.lease?.let(realtime::stop)
                    if (preparingRealtime == null && !realtime.hasPendingStart) {
                        realtimeDeadline?.cancel()
                        realtimeDeadline = null
                    }
                }
            }
            override fun appendAudio(base64: String, sampleRateHz: Int, callback: (Result<Unit>) -> Unit): Boolean =
                synchronized(this@CodexSessionController) {
                    val lease = pending.lease ?: return@synchronized false
                    if (pending.generation != generation || pending.threadId != reducer.snapshot().currentThreadId ||
                        !signedIn || !desktopRelayIsQuiescent()) return@synchronized false
                    realtime.appendAudio(lease, pending.generation, pending.threadId, base64, sampleRateHz, callback)
                }
            override fun finishUnroutedDictation(text: String, callback: (Result<Unit>) -> Unit): Boolean =
                synchronized(this@CodexSessionController) {
                    this@CodexSessionController.finishUnroutedDictation(pending, text, callback)
                }
            override fun appendSpeech(text: String, callback: (Result<Unit>) -> Unit): Boolean =
                synchronized(this@CodexSessionController) {
                    val lease = pending.lease ?: return@synchronized false
                    if (pending.generation != generation || pending.threadId != reducer.snapshot().currentThreadId ||
                        pending.account != reducer.snapshot().account.identity || !signedIn ||
                        !desktopRelayIsQuiescent()) return@synchronized false
                    realtime.appendSpeech(lease, pending.generation, pending.threadId, text, callback)
                }
        }
    }

    private fun finishUnroutedDictation(pending: PreparedRealtime, text: String,
        callback: (Result<Unit>) -> Unit): Boolean {
        val lease = pending.lease ?: return false
        if (text.isBlank() || text.length > 32_000 || pending.generation != generation ||
            pending.threadId != reducer.snapshot().currentThreadId || !signedIn ||
            pending.account != reducer.snapshot().account.identity || !desktopRelayIsQuiescent() ||
            realtimeDispatchWaiter != null || runtimePhase != ClientRuntimePhase.READY ||
            !realtime.claimUnroutedDictation(lease, pending.generation, pending.threadId)) return false
        val waiter = RealtimeDispatchWaiter(clientMessageIds.nextId(), pending.generation, callback)
        realtimeDispatchWaiter = waiter
        waiter.deadline = runCatching {
            realtimeDrainDeadlineScheduler.schedule(10_000L) {
                synchronized(this) {
                    settleRealtimeDispatch(waiter.messageId, Result.failure(CodexRealtimeFailure(CodexRealtimeIssue.TIMED_OUT)))
                }
            }
        }.getOrElse {
            settleRealtimeDispatch(waiter.messageId, Result.failure(CodexRealtimeFailure(CodexRealtimeIssue.CONNECTION_FAILED)))
            return true
        }
        if (realtimeDispatchWaiter !== waiter) { waiter.deadline?.cancel(); return true }
        // Keep an existing turn's proven preferences: dictation must never interrupt work
        // merely because the user's next-turn selection has changed in the meantime.
        val selection = activeTurn?.effectiveOptions?.let {
            DispatchSelection(it.model, it.effort, it.serviceTier)
        }
        val result = runCatching {
            dispatchAttempt(listOf(CodexInput.Text(text)), selection, waiter.messageId,
                allowInterruptForSelection = false, restartOnTransportAmbiguity = false)
        }.getOrNull()
        if (result !is CodexDispatchAttemptResult.Accepted) {
            settleRealtimeDispatch(waiter.messageId,
                Result.failure(CodexRealtimeFailure(CodexRealtimeIssue.CONNECTION_FAILED)))
        }
        return true
    }

    private fun settleRealtimeDispatch(messageId: String, result: Result<Unit>) {
        val waiter = realtimeDispatchWaiter?.takeIf { it.messageId == messageId } ?: return
        realtimeDispatchWaiter = null
        waiter.deadline?.cancel()
        val effective = if (waiter.generation == generation) result
            else Result.failure(CodexRealtimeFailure(CodexRealtimeIssue.SESSION_CHANGED))
        runCatching { waiter.callback(effective) }
    }

    private class RealtimeDispatchWaiter(val messageId: String, val generation: Long,
        val callback: (Result<Unit>) -> Unit) {
        var deadline: SetupDispatchDeadline? = null
    }

    private fun desktopRelayIsQuiescent(): Boolean = remoteControl.snapshot.let {
        it.generation == generation && it.runtimeReady && it.isDisabledConfirmed &&
            it.pendingOperation != ai.hans.standard.remotecontrol.RemoteControlOperation.ENABLE &&
            remoteControlledTurnIds.isEmpty() && remotePhoneTools?.hasActiveWork != true
    }

    private fun continuePreparedRealtime() {
        val pending = preparingRealtime ?: return
        if (pending.generation != generation || pending.threadId != reducer.snapshot().currentThreadId || !signedIn) {
            failPreparedRealtime(pending, CodexRealtimeIssue.SESSION_CHANGED)
            return
        }
        if (remoteControl.snapshot.pendingOperation != null) return
        if (!desktopRelayIsQuiescent()) {
            failPreparedRealtime(pending, CodexRealtimeIssue.REMOTE_ACCESS_ACTIVE)
            return
        }
        preparingRealtime = null
        realtimeOriginAccount = reducer.snapshot().account.identity
        pending.lease = realtime.start(pending.generation, pending.threadId, pending.offerSdp, pending.prompt,
            pending.voice, pending.callbacks, pending.options)
        if (pending.lease != null) realtime.workState(pending.generation, pending.threadId,
            activeTurn?.turnId ?: unknownActiveTurnId, pendingDispatch != null)
        if (pending.lease == null) {
            realtimeDeadline?.cancel()
            realtimeDeadline = null
        }
    }

    private fun failPreparedRealtime(pending: PreparedRealtime, issue: CodexRealtimeIssue) {
        if (preparingRealtime !== pending) return
        preparingRealtime = null
        realtimeDeadline?.cancel()
        realtimeDeadline = null
        runCatching { pending.callbacks.onStartRejected() }
        runCatching { pending.callbacks.onError(issue) }
    }

    private class PreparedRealtime(
        val generation: Long, val threadId: String, val offerSdp: String, val prompt: String,
        val voice: String?, val callbacks: CodexRealtimeCallbacks, val options: CodexRealtimeOptions,
        val account: ai.hans.standard.codex.AccountIdentity,
        var lease: Long? = null,
    )

    private fun invalidateRealtime() {
        realtimeOriginAccount = null
        voiceControlTurnProofs.clear()
        realtimeDispatchWaiter?.let {
            settleRealtimeDispatch(it.messageId, Result.failure(CodexRealtimeFailure(CodexRealtimeIssue.SESSION_CHANGED)))
        }
        preparingRealtime?.let { failPreparedRealtime(it, CodexRealtimeIssue.SESSION_CHANGED) }
        realtimeDeadline?.cancel()
        realtimeDeadline = null
        realtime.invalidate()
    }

    @Synchronized
    fun stop() {
        invalidateNativeNotificationRequests()
        invalidateRealtime()
        invalidateRemoteControl()
        invalidateSelectionContext(invalidateActiveProof = true)
        localRuntimeStopRequested = true
        val stoppingThreadId = reducer.snapshot().currentThreadId
        val stoppingTurnId = activeTurn?.turnId ?: unknownActiveTurnId
        if (stoppingThreadId != null && stoppingTurnId != null) {
            locallyStoppingDynamicTurn = stoppingThreadId to stoppingTurnId
        }
        abandonPendingDynamicCalls(clearRequestIds = false)
        val activeGeneration = generation?.takeIf { runtimeSessionActive } ?: run {
            restartInFlight = false
            runtimePhase = ClientRuntimePhase.STOPPED
            sessionPhase = ClientSessionPhase.IDLE
            notifyObservers()
            return
        }
        markPendingDispatchFailed(retryable = false)
        abortRuntimePluginTransactions()
        pluginReducer.onSessionLost()
        restartInFlight = false
        try {
            transport.stop(nextOperation(), activeGeneration)
        } catch (_: Exception) {
            failPermanently(ClientProblemCode.RUNTIME_FAILED, retryable = true)
        }
    }

    @Synchronized
    fun refreshAccount(): Boolean {
        if (runtimePhase != ClientRuntimePhase.READY) return false
        if (requestPurposes.values.any { it is RequestPurpose.AccountRead }) return true
        return transmit(
            AppServerRequests.accountRead(requestIds.next(), refreshToken = true),
            RequestPurpose.AccountRead,
        )
    }

    /** Explicit settings-open event only; never a timer or a model-selection write. */
    @Synchronized
    fun refreshModels(): Boolean {
        if (
            runtimePhase != ClientRuntimePhase.READY || !signedIn || !accountReadComplete ||
            sessionPhase !in setOf(ClientSessionPhase.READY, ClientSessionPhase.BUSY)
        ) return false
        // A pending authenticated catalogue request already satisfies this observation request.
        // Do not reset an accumulator halfway through its paginated replacement.
        if (modelCatalogRefreshQueued || requestPurposes.values.any { it is RequestPurpose.ModelList }) {
            return true
        }
        modelPages = ModelListAccumulator()
        modelCatalogSettingsRefreshInFlight = true
        // Preserve the last complete same-auth catalogue while loading. Dispatch still validates
        // the selected model/effort and only server acceptance can confirm a preference change.
        return requestModelPage(null).also { sent ->
            if (!sent) modelCatalogSettingsRefreshInFlight = false
        }
    }

    /** Apply next-turn defaults without creating a turn; success is ACK plus effective event. */
    @Synchronized
    fun updateSelection(selection: DispatchSelection): Boolean {
        if (!canUpdateSelection() || pendingDispatch != null) return false
        val currentCatalog = catalog ?: return false
        if (currentCatalog.models.none { !it.hidden && it.wireModel == selection.model }) return false
        if (runCatching { currentCatalog.requireSupported(selection.toOptions(sessionStore.workspacePath)) }.isFailure) {
            return false
        }
        val pending = pendingSettingsUpdate
        if (pending != null) {
            queuedSettingsSelection = selection.takeUnless { it == pending.selection }
            if (problem?.code == ClientProblemCode.SELECTION_UPDATE) problem = null
            notifyObservers()
            return true
        }
        return startSelectionUpdate(selection)
    }

    private fun canUpdateSelection(): Boolean = runtimePhase == ClientRuntimePhase.READY &&
        signedIn && accountReadComplete && threadReady && !rehydrating &&
        sessionPhase in setOf(ClientSessionPhase.READY, ClientSessionPhase.BUSY) &&
        (unknownActiveTurnId == null || hasSteerableResumedIdentity()) && reducer.snapshot().currentThreadId?.let {
            memoryModeEnabledThreadId == it
        } == true

    private fun hasSteerableResumedIdentity(): Boolean = unknownActiveTurnId?.let { turnId ->
        val identity = reducer.snapshot().currentThreadId to turnId
        identityOnlyActiveTurn == identity && locallyStoppingDynamicTurn != identity
    } == true

    private fun startSelectionUpdate(selection: DispatchSelection): Boolean {
        if (!canUpdateSelection()) return false
        val available = catalog ?: return false
        if (available.models.none { !it.hidden && it.wireModel == selection.model } ||
            runCatching { available.requireSupported(selection.toOptions(sessionStore.workspacePath)) }.isFailure
        ) return false
        val threadId = reducer.snapshot().currentThreadId ?: return false
        val activeGeneration = generation ?: return false
        val id = requestIds.next()
        val request = AppServerRequests.threadSettingsUpdate(
            id = id, threadId = threadId, model = selection.model, effort = selection.effort,
            serviceTier = selection.serviceTier.takeUnless { it == HansSettings.DEFAULT_SERVICE_TIER },
        )
        val pending = PendingSettingsUpdate(
            requestId = id, generation = activeGeneration, contextEpoch = settingsContextEpoch,
            threadId = threadId, selection = selection,
            eventSequenceFloor = lastReceivedFrameSequence,
        )
        // Native no-op settings writes ACK without emitting another settings event. A prior
        // authoritative proof in this exact thread/generation can satisfy that half; persisted
        // preferences alone never do. A later contradictory event clears this seeded proof.
        pending.observedSelection = provenThreadSettingsSelection?.takeIf { it == selection }
        // Sending a different write makes any earlier default uncertain until newer effective
        // evidence arrives. A timeout must not let a later retry misclassify that old value as
        // an already-effective no-op merely because the intermediate event was lost.
        if (provenThreadSettingsSelection != selection) provenThreadSettingsSelection = null
        pendingSettingsUpdate = pending
        queuedSettingsSelection = null
        if (problem?.code == ClientProblemCode.SELECTION_UPDATE) problem = null
        pending.deadline = selectionUpdateDeadlineScheduler.schedule(selectionUpdateTimeoutMillis) {
            synchronized(this) {
                if (pendingSettingsUpdate === pending) {
                    invalidateSelectionUpdate()
                    setProblem(ClientProblemCode.SELECTION_UPDATE, retryable = true)
                }
            }
        }
        val sent = transmit(request, RequestPurpose.SettingsUpdate(id, pending.contextEpoch))
        if (!sent && pendingSettingsUpdate === pending) invalidateSelectionUpdate()
        notifyObservers()
        return sent
    }

    private fun settingsUpdateContextMatches(pending: PendingSettingsUpdate): Boolean =
        pending.generation == generation && pending.contextEpoch == settingsContextEpoch &&
            pending.threadId == reducer.snapshot().currentThreadId && canUpdateSelection()

    private fun acceptSettingsUpdateEvent(eventSequence: Long, event: ServerEvent.ThreadSettingsUpdated) {
        if (!canUpdateSelection() || event.threadId != reducer.snapshot().currentThreadId ||
            eventSequence <= lastThreadSettingsEventSequence
        ) return
        lastThreadSettingsEventSequence = eventSequence
        val observed = event.effort?.let { effort ->
            runCatching {
                DispatchSelection(event.model, effort, event.serviceTier ?: HansSettings.DEFAULT_SERVICE_TIER)
            }.getOrNull()
        }
        provenThreadSettingsSelection = observed
        val pending = pendingSettingsUpdate ?: return
        if (!settingsUpdateContextMatches(pending) || eventSequence <= pending.eventSequenceFloor) return
        pending.eventSequenceFloor = eventSequence
        // Keep the most recent same-thread evidence. A later mismatch invalidates an earlier
        // matching event while the ACK is outstanding; unrelated thread events prove nothing.
        pending.observedSelection = observed
        completeSelectionUpdateIfProven(pending)
    }

    private fun completeSelectionUpdateIfProven(pending: PendingSettingsUpdate) {
        if (pendingSettingsUpdate !== pending || !settingsUpdateContextMatches(pending) ||
            !pending.acknowledged || pending.observedSelection != pending.selection
        ) return
        pending.deadline?.cancel()
        pendingSettingsUpdate = null
        retireSettingsRequest(pending.requestId)
        val latest = queuedSettingsSelection
        queuedSettingsSelection = null
        confirmedSelection = pending.selection
        provenThreadSettingsSelection = pending.selection
        if (activeTurn == null && unknownActiveTurnId == null) {
            migrationEffectiveSelection = pending.selection
        } else {
            settingsDefaultActiveTurn = activeTurn?.let { it.threadId to it.turnId }
                ?: identityOnlyActiveTurn
        }
        try {
            settingsStore.saveConfirmedDispatch(
                pending.selection.model, pending.selection.effort.wireValue, pending.selection.serviceTier,
            )
        } catch (_: Exception) {
            setProblem(ClientProblemCode.LOCAL_PERSISTENCE, retryable = true)
        }
        if (latest != null && latest != pending.selection) {
            if (!startSelectionUpdate(latest)) setProblem(ClientProblemCode.SELECTION_UPDATE, retryable = true)
        }
    }

    private fun retireSettingsRequest(id: RequestId) {
        correlator.discard(id)
        requestPurposes.remove(id)
        retiredSettingsRequestIds.add(id)
        while (retiredSettingsRequestIds.size > 64) retiredSettingsRequestIds.remove(retiredSettingsRequestIds.first())
    }

    private fun invalidateSelectionUpdate() {
        settingsContextEpoch++
        pendingSettingsUpdate?.let { pending ->
            pending.deadline?.cancel()
            retireSettingsRequest(pending.requestId)
        }
        pendingSettingsUpdate = null
        queuedSettingsSelection = null
    }

    private fun invalidateSelectionContext(invalidateActiveProof: Boolean = false) {
        invalidateSelectionUpdate()
        settingsDefaultActiveTurn = null
        provenThreadSettingsSelection = null
        lastThreadSettingsEventSequence = 0L
        if (invalidateActiveProof) {
            retainedActiveTurn = null
            identityOnlyActiveTurn = null
        }
    }

    @Synchronized
    fun refreshPlugins(forceRefetch: Boolean = true): String? {
        if (!canUsePluginDomain() || pluginRecoveryGateInProgress) return null
        val operationId = nextPluginOperationId()
        if (!pluginReducer.beginOperation(operationId, PluginOperationKind.REFRESH_PLUGINS)) {
            return null
        }
        return sendPluginRequest(
            operationId,
            RequestPurpose.PluginList(operationId),
            PluginAppServerRequests.pluginList(
                id = requestIds.next(),
                workspacePath = sessionStore.workspacePath,
                forceRefetch = forceRefetch,
            ),
        )
    }

    @Synchronized
    fun readPlugin(handle: PluginHandle): String? {
        if (!canUsePluginDomain() || pluginRecoveryGateInProgress) return null
        val locator = pluginReducer.resolve(handle) ?: return null
        val operationId = nextPluginOperationId()
        if (!pluginReducer.beginOperation(
                operationId,
                PluginOperationKind.READ_PLUGIN,
                handle,
            )
        ) {
            return null
        }
        return sendPluginRequest(
            operationId,
            RequestPurpose.PluginRead(operationId),
            PluginAppServerRequests.pluginRead(requestIds.next(), locator),
        )
    }

    @Synchronized
    fun installPlugin(handle: PluginHandle): String? {
        if (!canUsePluginDomain() || pluginMutationsBlockedByRecovery) return null
        val locator = pluginReducer.resolve(handle) ?: return null
        val runtimeRecord = pluginReducer.resolveRuntimeRecord(handle) ?: return null
        val operationId = nextPluginOperationId()
        if (!pluginReducer.beginOperation(
                operationId,
                PluginOperationKind.INSTALL_PLUGIN,
                handle,
            )
        ) {
            return null
        }
        val transactions = pluginInstallTransactions
        if (transactions != null) {
            if (!schedulePluginInstallDeadline(operationId)) {
                if (pluginReducer.isPending(operationId, PluginOperationKind.INSTALL_PLUGIN)) {
                    pluginReducer.fail(
                        operationId,
                        PluginOperationFailure.BUSY,
                        retryable = true,
                    )
                }
                notifyObservers()
                return operationId
            }
            val surfaceProbe = pluginSurfaceEvidenceStager?.let { stager ->
                runtimeRecord.localSourcePath?.let { sourcePath ->
                    runCatching {
                        stager.requiresFreshEvidence(
                            pluginId = runtimeRecord.card.pluginId,
                            sourceRoot = File(sourcePath),
                        )
                    }.getOrElse {
                        PluginSurfaceDeclarationProbe.Rejected("surface_declaration_probe_failed")
                    }
                }
            } ?: PluginSurfaceDeclarationProbe.NotDeclared
            when (surfaceProbe) {
                PluginSurfaceDeclarationProbe.NotDeclared ->
                    schedulePluginRuntimePreparation(operationId, locator, runtimeRecord)
                is PluginSurfaceDeclarationProbe.Rejected -> {
                    cancelPluginInstallDeadline(operationId)
                    pluginReducer.fail(
                        operationId,
                        PluginOperationFailure.LOCAL_INCOMPATIBLE,
                        retryable = true,
                    )
                    notifyObservers()
                }
                is PluginSurfaceDeclarationProbe.Declared -> {
                    val sent = transmit(
                        PluginAppServerRequests.pluginRead(requestIds.next(), locator),
                        RequestPurpose.PluginRuntimeSurfaceRead(
                            operationId = operationId,
                            locator = locator,
                            runtimeRecord = runtimeRecord,
                            declaration = surfaceProbe.receipt,
                        ),
                    )
                    if (!sent) {
                        cancelPluginInstallDeadline(operationId)
                        pluginReducer.fail(
                            operationId,
                            PluginOperationFailure.TRANSPORT_AMBIGUOUS,
                            retryable = false,
                        )
                    }
                    notifyObservers()
                }
            }
            return operationId
        }
        return sendPluginRequest(
            operationId,
            RequestPurpose.PluginInstall(operationId),
            PluginAppServerRequests.pluginInstall(
                id = requestIds.next(),
                locator = locator,
                installAttemptId = operationId,
            ),
        )
    }

    /** Exact private target for an explicit OAuth action; never part of a public snapshot. */
    @Synchronized
    internal fun resolveRemoteMcpOAuthTarget(
        pluginId: String,
        serverId: String,
    ): PluginRemoteMcpOAuthTarget? = pluginReducer.resolveRemoteMcpOAuthTarget(
        pluginId,
        serverId,
        PluginConnectionActionKind.CONNECT_REMOTE_MCP,
    )

    /** OAuth completion only changes the safe action. It never resumes plugin installation. */
    @Synchronized
    internal fun markRemoteMcpOAuthConnected(pluginId: String, serverId: String): Boolean =
        pluginReducer.markRemoteMcpConnected(pluginId, serverId).also { changed ->
            if (changed) notifyObservers()
        }

    /** Exact private review target while the same one-shot action remains visible. */
    @Synchronized
    internal fun resolveRemoteMcpPolicyReviewTarget(
        pluginId: String,
        serverId: String,
        expected: PluginRemoteMcpPolicyReviewSnapshot,
    ): PluginRemoteMcpPolicyReviewTarget? = pluginReducer.resolveRemoteMcpPolicyReviewTarget(
        pluginId,
        serverId,
        expected,
    )

    /** Successful policy CAS only exposes Retry; it never resumes the stopped install attempt. */
    @Synchronized
    internal fun markRemoteMcpPolicyReviewed(
        target: PluginRemoteMcpPolicyReviewTarget,
    ): Boolean = pluginReducer.markRemoteMcpPolicyReviewed(
        request = target.request,
        sourceSha256 = target.sourceSha256,
    ).also { changed ->
        if (changed) notifyObservers()
    }

    /** A user click creates a completely new install attempt and re-runs every precondition. */
    @Synchronized
    internal fun retryRemoteMcpPluginInstall(pluginId: String, serverId: String): String? {
        val handle = pluginReducer.resolveRemoteMcpRetryTarget(pluginId, serverId) ?: return null
        return installPlugin(handle)
    }

    @Synchronized
    fun uninstallPlugin(handle: PluginHandle): String? {
        if (!canUsePluginDomain() || pluginMutationsBlockedByRecovery) return null
        val runtimeRecord = pluginReducer.resolveRuntimeRecord(handle) ?: return null
        val transactions = pluginUninstallTransactions ?: return null
        val operationId = nextPluginOperationId()
        if (!pluginReducer.beginOperation(
                operationId,
                PluginOperationKind.UNINSTALL_PLUGIN,
                handle,
            )
        ) {
            return null
        }
        // Reserve the sole full-catalog proof before the remote side effect. A concurrent refresh
        // can therefore never strand an accepted uninstall without its required absence proof.
        val proofOperationId = nextPluginOperationId()
        if (!pluginReducer.beginOperation(
                proofOperationId,
                PluginOperationKind.REFRESH_PLUGINS,
            )
        ) {
            pluginReducer.fail(operationId, PluginOperationFailure.BUSY, retryable = true)
            notifyObservers()
            return operationId
        }
        val expectedGeneration = generation
        val invalidatesBundledSetup = handle == bundledSetupProvenPluginHandle
        val scheduled = runCatching {
            pluginPreparationExecutor.execute {
                val preparation = runCatching {
                    transactions.prepare(operationId, runtimeRecord).also { prepared ->
                        if (prepared is PluginUninstallPreparation.Prepared) {
                            transactions.markRemoteUninstallIntent(operationId)
                        }
                    }
                }.getOrElse {
                    PluginUninstallPreparation.Rejected("uninstall_prepare_failed")
                }
                finishPluginUninstallPreparation(
                    operationId = operationId,
                    proofOperationId = proofOperationId,
                    pluginId = runtimeRecord.card.pluginId,
                    invalidatesBundledSetup = invalidatesBundledSetup,
                    expectedGeneration = expectedGeneration,
                    preparation = preparation,
                )
            }
        }.isSuccess
        if (!scheduled) {
            failPluginUninstallOperations(
                operationId,
                proofOperationId,
                PluginOperationFailure.BUSY,
                retryable = true,
            )
        }
        notifyObservers()
        return operationId
    }

    @Synchronized
    fun configurePluginSkill(
        handle: PluginHandle,
        skillName: String,
        enabled: Boolean,
    ): String? {
        if (!canUsePluginDomain() || pluginMutationsBlockedByRecovery) return null
        val skillPath = pluginReducer.resolveSkillPath(handle, skillName) ?: return null
        val operationId = nextPluginOperationId()
        if (!pluginReducer.beginOperation(
                operationId,
                PluginOperationKind.CONFIGURE_SKILL,
                handle,
            )
        ) {
            return null
        }
        return sendPluginRequest(
            operationId,
            RequestPurpose.PluginSkillConfig(
                operationId = operationId,
                handle = handle,
                skillName = skillName,
                skillPath = skillPath,
            ),
            PluginAppServerRequests.skillConfigWrite(
                id = requestIds.next(),
                path = skillPath,
                enabled = enabled,
            ),
        )
    }

    @Synchronized
    fun refreshMarketplaces(marketplaceName: String? = null): String? {
        if (!canUsePluginDomain() || pluginMutationsBlockedByRecovery) return null
        val operationId = nextPluginOperationId()
        if (!pluginReducer.beginOperation(
                operationId,
                PluginOperationKind.REFRESH_MARKETPLACE,
            )
        ) {
            return null
        }
        val request = try {
            PluginAppServerRequests.marketplaceUpgrade(requestIds.next(), marketplaceName)
        } catch (_: Exception) {
            pluginReducer.fail(
                operationId,
                PluginOperationFailure.TARGET_NOT_FOUND,
                retryable = false,
            )
            notifyObservers()
            return operationId
        }
        return sendPluginRequest(
            operationId,
            RequestPurpose.MarketplaceUpgrade(operationId),
            request,
        )
    }

    @Synchronized
    fun refreshApps(forceRefetch: Boolean = true): String? {
        if (!canUsePluginDomain() || pluginRecoveryGateInProgress) return null
        val operationId = nextPluginOperationId()
        if (!pluginReducer.beginOperation(operationId, PluginOperationKind.REFRESH_APPS)) {
            return null
        }
        return sendAppListPage(operationId, cursor = null, forceRefetch = forceRefetch)
    }

    @Synchronized
    fun refreshSkills(forceReload: Boolean = true): String? {
        if (!canUsePluginDomain() || pluginRecoveryGateInProgress) return null
        val operationId = nextPluginOperationId()
        if (!pluginReducer.beginOperation(operationId, PluginOperationKind.REFRESH_SKILLS)) {
            return null
        }
        return sendPluginRequest(
            operationId,
            RequestPurpose.PluginSkills(operationId),
            AppServerRequests.skillsList(
                id = requestIds.next(),
                workingDirectories = listOf(sessionStore.workspacePath),
                forceReload = forceReload,
            ),
        )
    }

    /**
     * Retries only the optional bundled setup capability. The authenticated
     * App Server session and its active thread remain intact.
     */
    @Synchronized
    fun retryBundledSetupBootstrap(): Boolean {
        if (
            !bundledSetupPlugin.enabled || !canUsePluginDomain() ||
            pluginMutationsBlockedByRecovery
        ) return false
        return when (bundledSetupBootstrapStatus()) {
            BundledSetupBootstrapStatus.DISABLED -> false
            BundledSetupBootstrapStatus.READY,
            BundledSetupBootstrapStatus.PREPARING,
            -> true
            BundledSetupBootstrapStatus.FAILED -> {
                bundledSetupBootstrapPhase = BundledSetupBootstrapPhase.IDLE
                bundledSetupProvenPluginHandle = null
                bundledSetupRetryQueued = true
                maybeStartQueuedBundledSetupRetry()
                notifyObservers()
                true
            }
        }
    }

    /** Returns only the exact skill selector proven by the current App Server session. */
    @Synchronized
    fun bundledSetupSkillInput(): CodexInput.Skill? {
        if (bundledSetupBootstrapStatus() != BundledSetupBootstrapStatus.READY) return null
        val skill = pluginReducer.snapshot().skills.singleOrNull {
            it.name == BundledSetupPluginContract.QUALIFIED_SKILL_NAME && it.enabled
        } ?: return null
        val path = skill.path ?: return null
        return CodexInput.Skill(name = skill.name, absolutePath = path)
    }

    @Synchronized
    fun loginWithDeviceCode(): Boolean {
        if (runtimePhase != ClientRuntimePhase.READY) return false
        if (requestPurposes.values.any { it is RequestPurpose.LoginStart }) return true
        invalidateSelectionContext(invalidateActiveProof = true)
        // An explicit retry after terminal bootstrap failure may start recovery again.
        // Never clear the guard for an in-flight start/resume/memory request.
        if (sessionPhase == ClientSessionPhase.FAILED && !threadReady &&
            requestPurposes.values.none {
                it is RequestPurpose.ThreadStart || it is RequestPurpose.ThreadResume ||
                    it is RequestPurpose.ThreadMemoryEnable || it is RequestPurpose.ThreadMaterialize
            }
        ) {
            threadBootstrapRequested = false
        }
        sessionPhase = ClientSessionPhase.LOGIN_PENDING
        problem = null
        val sent = transmit(
            AppServerRequests.deviceCodeLogin(requestIds.next()),
            RequestPurpose.LoginStart,
        )
        notifyObservers()
        return sent
    }

    @Synchronized
    fun logout(): Boolean {
        if (runtimePhase != ClientRuntimePhase.READY) return false
        invalidateNativeNotificationRequests()
        invalidateRealtime()
        if (requestPurposes.values.any { it is RequestPurpose.Logout }) return true
        remoteLogoutRequiresRuntimeRestart = remoteControl.snapshot.runtimeReady &&
            (!remoteControl.snapshot.isDisabledConfirmed ||
                remoteControl.snapshot.pendingOperation == ai.hans.standard.remotecontrol.RemoteControlOperation.ENABLE)
        if (remoteControl.snapshot.runtimeReady) remoteControlDisable()
        invalidateSelectionContext(invalidateActiveProof = true)
        return transmit(
            AppServerRequests.accountLogout(requestIds.next()),
            RequestPurpose.Logout,
        )
    }

    /** Returns the stable local message id, or null when rejected locally. */
    fun dispatch(
        input: List<CodexInput>,
        selection: DispatchSelection? = null,
        clientUserMessageId: String? = null,
        dynamicToolTurnPolicy: DynamicToolTurnPolicy = DynamicToolTurnPolicy.ALLOW,
    ): String? = dispatchAttempt(
        input,
        selection,
        clientUserMessageId,
        dynamicToolTurnPolicy,
    ).acceptedMessageId

    /** Returns a provenance-preserving receipt for safety-critical automation callers. */
    @Synchronized
    fun dispatchAttempt(
        input: List<CodexInput>,
        selection: DispatchSelection? = null,
        clientUserMessageId: String? = null,
        dynamicToolTurnPolicy: DynamicToolTurnPolicy = DynamicToolTurnPolicy.ALLOW,
        allowInterruptForSelection: Boolean = true,
        restartOnTransportAmbiguity: Boolean = true,
        expectedThreadId: String? = null,
    ): CodexDispatchAttemptResult {
        if (
            runtimePhase != ClientRuntimePhase.READY ||
            sessionPhase !in setOf(ClientSessionPhase.READY, ClientSessionPhase.BUSY) ||
            pendingDispatch != null
        ) {
            setProblem(ClientProblemCode.DISPATCH_REJECTED, retryable = true)
            return CodexDispatchAttemptResult.RejectedBeforeTransport
        }
        val steerResumedIdentity = hasSteerableResumedIdentity()
        if (unknownActiveTurnId != null && !steerResumedIdentity) {
            setProblem(ClientProblemCode.THREAD_RECOVERY, retryable = true)
            return CodexDispatchAttemptResult.RejectedBeforeTransport
        }
        val threadId = reducer.snapshot().currentThreadId ?: run {
            setProblem(ClientProblemCode.THREAD_RECOVERY, retryable = true)
            return CodexDispatchAttemptResult.RejectedBeforeTransport
        }
        // Safety-critical callers seal their destination before transport. Validate it under
        // the same controller lock as outbound creation and send, not against a Host snapshot.
        if (expectedThreadId != null && threadId != expectedThreadId) {
            return CodexDispatchAttemptResult.RejectedBeforeTransport
        }
        val currentCatalog = catalog ?: run {
            setProblem(ClientProblemCode.MODEL_CATALOG, retryable = true)
            return CodexDispatchAttemptResult.RejectedBeforeTransport
        }
        val requestedSelection = selection ?: readConfirmedSelection()
        val active = activeTurn
        val activeSelection = active?.effectiveOptions?.let {
            runCatching { DispatchSelection(it.model, it.effort, it.serviceTier) }.getOrNull()
        }
        val keepsActiveTurnDefaults = active != null && activeSelection != null &&
            (pendingSettingsUpdate != null || settingsDefaultActiveTurn == (active.threadId to active.turnId)) &&
            requestedSelection in listOf(
                confirmedSelection, activeSelection, queuedSettingsSelection, pendingSettingsUpdate?.selection,
            )
        if (pendingSettingsUpdate != null && !keepsActiveTurnDefaults && !steerResumedIdentity) {
            // No new turn may race an unconfirmed settings write. Existing BUSY turns can still
            // receive dictated/manual steering with their proven, unchanged effective options.
            return CodexDispatchAttemptResult.RejectedBeforeTransport
        }
        val desiredSelection = if (keepsActiveTurnDefaults) checkNotNull(activeSelection) else requestedSelection
        val options = if (steerResumedIdentity) null
            else if (keepsActiveTurnDefaults) checkNotNull(active).effectiveOptions
            else desiredSelection.toOptions(sessionStore.workspacePath)
        try {
            options?.let(currentCatalog::requireSupported)
        } catch (_: Exception) {
            setProblem(ClientProblemCode.MODEL_CATALOG, retryable = true)
            return CodexDispatchAttemptResult.RejectedBeforeTransport
        }
        val messageId = clientUserMessageId ?: clientMessageIds.nextId()
        if (!isValidClientMessageId(messageId)) {
            setProblem(ClientProblemCode.DISPATCH_REJECTED, retryable = false)
            return CodexDispatchAttemptResult.RejectedBeforeTransport
        }
        if (
            messageId.startsWith(SETUP_CLIENT_MESSAGE_PREFIX) &&
            bundledSetupBootstrapStatus() != BundledSetupBootstrapStatus.READY
        ) {
            // This check shares dispatch's controller lock with outbound creation and transport,
            // so an Activity snapshot race or another caller cannot bypass bootstrap proof.
            setProblem(ClientProblemCode.DISPATCH_REJECTED, retryable = true)
            return CodexDispatchAttemptResult.RejectedBeforeTransport
        }
        if (messageId in outbound) {
            setProblem(ClientProblemCode.DISPATCH_REJECTED, retryable = false)
            return CodexDispatchAttemptResult.RejectedBeforeTransport
        }
        val displayText = boundedTimelineText(
            input.filterIsInstance<CodexInput.Text>()
                .joinToString("\n") { it.text }
                .ifBlank { "Attachment" },
        )
        val pending = PendingDispatch(
            clientMessageId = messageId,
            input = input.toList(),
            selection = desiredSelection,
            options = options,
            confirmsDefaultSelection = !keepsActiveTurnDefaults && !steerResumedIdentity,
        )
        outbound[messageId] = MutableOutbound(messageId, threadId, displayText)
        ensureTimelineOrder(timelineId(ClientTimelineRole.USER, messageId))
        trimOutbound()
        pendingDispatch = pending
        pendingSelection = desiredSelection.takeUnless { steerResumedIdentity }
        problem = null
        performanceEvent(PerformanceEvent.DISPATCH_ACCEPTED)

        val plan = try {
            if (steerResumedIdentity) DispatchPlan.Steer(
                AppServerRequests.turnSteerKnownIdentity(
                    id = requestIds.next(),
                    threadId = threadId,
                    expectedTurnId = checkNotNull(unknownActiveTurnId),
                    input = input,
                    clientUserMessageId = messageId,
                ),
            ) else DispatchPolicy.plan(
                id = requestIds.next(),
                threadId = threadId,
                activeTurn = activeTurn,
                input = input,
                desiredOptions = checkNotNull(options),
                catalog = currentCatalog,
                clientUserMessageId = messageId,
            )
        } catch (_: Exception) {
            markPendingDispatchFailed(retryable = true)
            setProblem(ClientProblemCode.DISPATCH_REJECTED, retryable = true)
            return CodexDispatchAttemptResult.RejectedBeforeTransport
        }
        if (plan is DispatchPlan.RequiresNewTurn && !allowInterruptForSelection) {
            markPendingDispatchFailed(retryable = false)
            return CodexDispatchAttemptResult.RejectedBeforeTransport
        }
        dynamicToolTurnAuthorizationGate.prepareDispatch(messageId, dynamicToolTurnPolicy)
        VisibleInputReceipt.fromInputs(threadId, messageId, input)?.let { receipt ->
            runCatching { visibleInputReceipts.record(receipt) }
                .onFailure {
                    protocolDiagnostics("Visible input receipt could not be persisted")
                }
        }
        val transmission = when (plan) {
            is DispatchPlan.StartTurn -> transmitAttempt(
                plan.request,
                RequestPurpose.TurnStart(messageId, desiredSelection, !keepsActiveTurnDefaults),
                restartOnAmbiguity = restartOnTransportAmbiguity,
            )
            is DispatchPlan.Steer -> transmitAttempt(
                plan.request,
                RequestPurpose.TurnSteer(messageId, desiredSelection,
                    !keepsActiveTurnDefaults && !steerResumedIdentity),
                restartOnAmbiguity = restartOnTransportAmbiguity,
            )
            is DispatchPlan.RequiresNewTurn -> {
                val active = checkNotNull(activeTurn)
                if (stopDynamicToolTurnLocally(active.threadId, active.turnId)) {
                    transmitAttempt(
                        AppServerRequests.turnInterrupt(
                            id = requestIds.next(),
                            threadId = active.threadId,
                            turnId = active.turnId,
                        ),
                        RequestPurpose.BoundaryInterrupt(
                            messageId = messageId,
                            interruptedTurnId = active.turnId,
                        ),
                    )
                } else {
                    RequestTransmissionResult.OUTCOME_AMBIGUOUS
                }
            }
        }
        if (
            transmission == RequestTransmissionResult.SENT &&
            messageId.startsWith(SETUP_CLIENT_MESSAGE_PREFIX) &&
            plan is DispatchPlan.Steer
        ) {
            // A steer reuses the already-known turn. Correlate it immediately so an item event
            // emitted before the steer response cannot expose setup implementation details.
            (activeTurn?.turnId ?: unknownActiveTurnId)?.let(::rememberSetupTurn)
        }
        val retainAmbiguousCorrelation = transmission == RequestTransmissionResult.OUTCOME_AMBIGUOUS &&
            !restartOnTransportAmbiguity
        if (transmission != RequestTransmissionResult.SENT && pendingDispatch?.clientMessageId == messageId &&
            !retainAmbiguousCorrelation) {
            markPendingDispatchFailed(
                retryable = true,
                releaseDynamicToolAuthority =
                    transmission == RequestTransmissionResult.REJECTED_BEFORE_TRANSPORT,
            )
            setProblem(ClientProblemCode.DISPATCH_REJECTED, retryable = true)
        }
        if (retainAmbiguousCorrelation) {
            // This request may already have started/steered work. Preserve its correlation so
            // a subsequent turn event cannot be relabelled as Desktop work. Do not resubmit;
            // a missing receipt remains unresolved until an explicit runtime recovery.
            setProblem(ClientProblemCode.DISPATCH_AMBIGUOUS, retryable = false)
        }
        if (transmission == RequestTransmissionResult.REJECTED_BEFORE_TRANSPORT) {
            removeVisibleInputReceipt(threadId, messageId)
        }
        notifyObservers()
        // A locally allocated message id is not an acceptance receipt. In particular,
        // Binder/runtime transport can reject the frame synchronously after we projected the
        // optimistic outbound item. Propagate that failure so the launcher retains the user's
        // composer draft and pending notification context for an explicit retry.
        return when (transmission) {
            RequestTransmissionResult.SENT -> CodexDispatchAttemptResult.Accepted(messageId)
            RequestTransmissionResult.REJECTED_BEFORE_TRANSPORT ->
                CodexDispatchAttemptResult.RejectedBeforeTransport
            RequestTransmissionResult.OUTCOME_AMBIGUOUS ->
                CodexDispatchAttemptResult.TransportOutcomeAmbiguous
        }
    }

    @Synchronized
    fun interrupt(): Boolean {
        val identity = interruptibleTurnIdentity() ?: return false
        if (pendingInterruptFor(identity)) return true
        workInterruptRevision += 1
        if (!stopDynamicToolTurnLocally(identity.first, identity.second)) return false
        val sent = transmit(
            AppServerRequests.turnInterrupt(
                id = requestIds.next(),
                threadId = identity.first,
                turnId = identity.second,
            ),
            RequestPurpose.UserInterrupt(identity.first, identity.second),
        )
        notifyObservers()
        return sent
    }

    private fun interruptibleTurnIdentity(): Pair<String, String>? =
        activeTurn?.let { it.threadId to it.turnId }
            ?: identityOnlyActiveTurn?.takeIf { hasSteerableResumedIdentity() }
            // Local cancellation deliberately revokes the resumed turn's steer/tool proof.
            // Keep a retry of that exact, still-current stop possible after a rejected ACK;
            // this must not restore permission to steer or execute its tools.
            ?: locallyStoppingDynamicTurn?.takeIf {
                it.first == reducer.snapshot().currentThreadId && it.second == unknownActiveTurnId
            }

    private fun pendingInterruptFor(identity: Pair<String, String>): Boolean =
        requestPurposes.values.any {
            it is RequestPurpose.UserInterrupt && it.threadId == identity.first && it.turnId == identity.second
        }

    private fun retireWorkInterrupt(requestId: RequestId) {
        workInterruptDeadlines.remove(requestId)?.cancel()
        correlator.discard(requestId)
        requestPurposes.remove(requestId)
        retiredWorkInterruptRequestIds.add(requestId)
        while (retiredWorkInterruptRequestIds.size > 64) {
            retiredWorkInterruptRequestIds.remove(retiredWorkInterruptRequestIds.first())
        }
    }

    private fun retireWorkInterruptsFor(threadId: String, turnId: String) {
        requestPurposes.entries.filter {
            val purpose = it.value
            purpose is RequestPurpose.UserInterrupt && purpose.threadId == threadId && purpose.turnId == turnId
        }.map { it.key }.forEach(::retireWorkInterrupt)
    }

    private fun cancelWorkInterruptDeadlines() {
        workInterruptDeadlines.values.forEach { it.cancel() }
        workInterruptDeadlines.clear()
    }

    /** Only an in-flight interrupt owns this one-shot timer; idle projection never schedules. */
    private fun armWorkInterruptDeadline(
        requestId: RequestId,
        purpose: RequestPurpose,
        expectedGeneration: Long,
    ) {
        if (purpose !is RequestPurpose.UserInterrupt) return
        workInterruptDeadlines[requestId] = workInterruptDeadlineScheduler.schedule(workInterruptTimeoutMillis) {
            synchronized(this) {
                if (generation != expectedGeneration || requestPurposes[requestId] != purpose) return@synchronized
                retireWorkInterrupt(requestId)
                if (runtimePhase == ClientRuntimePhase.READY &&
                    interruptibleTurnIdentity() == purpose.threadId to purpose.turnId
                ) {
                    // No ACK is not success: retain the active turn and its local tool revocation.
                    // The user may retry this exact turn; a late reply to the old request is inert.
                    setProblem(ClientProblemCode.INTERRUPT_REJECTED, retryable = true)
                } else {
                    notifyObservers()
                }
            }
        }
    }

    @Synchronized
    override fun onSessionState(
        operationId: Long,
        generation: Long,
        eventSequence: Long,
        state: Int,
    ) {
        if (state in setOf(AppServerSessionContract.STATE_STOPPING, AppServerSessionContract.STATE_STOPPED,
                AppServerSessionContract.STATE_EXITED, AppServerSessionContract.STATE_FAILED)) {
            invalidateNativeNotificationRequests()
        }
        when (state) {
            AppServerSessionContract.STATE_STARTING -> {
                runtimeSessionActive = generation > 0
                if (generation > 0 && generation != this.generation) {
                    prepareGeneration(generation)
                }
                runtimePhase = if (restartInFlight) {
                    ClientRuntimePhase.RESTARTING
                } else {
                    ClientRuntimePhase.STARTING
                }
                sessionPhase = ClientSessionPhase.BOOTSTRAPPING
            }
            AppServerSessionContract.STATE_READY -> handleReady(generation)
            AppServerSessionContract.STATE_STOPPING -> {
                invalidateRealtime()
                cancelWorkInterruptDeadlines()
                invalidateRemoteControl()
                invalidateSelectionContext()
                localRuntimeStopRequested = true
                abandonPendingDynamicCalls(clearRequestIds = false)
                abortRuntimePluginTransactions()
                pluginReducer.onSessionLost()
                runtimePhase = if (restartInFlight) {
                    ClientRuntimePhase.RESTARTING
                } else {
                    ClientRuntimePhase.STOPPED
                }
            }
            AppServerSessionContract.STATE_STOPPED -> {
                invalidateRealtime()
                cancelWorkInterruptDeadlines()
                invalidateRemoteControl()
                invalidateSelectionContext()
                runtimeSessionActive = false
                localRuntimeStopRequested = true
                abandonPendingDynamicCalls(clearRequestIds = false)
                abortRuntimePluginTransactions()
                pluginReducer.onSessionLost()
                if (!restartInFlight) {
                    runtimePhase = ClientRuntimePhase.STOPPED
                    sessionPhase = ClientSessionPhase.IDLE
                }
            }
            AppServerSessionContract.STATE_EXITED,
            AppServerSessionContract.STATE_FAILED,
            -> {
                runtimeSessionActive = false
                failPermanently(ClientProblemCode.RUNTIME_FAILED, retryable = true)
            }
            else -> failPermanently(ClientProblemCode.RUNTIME_FAILED, retryable = false)
        }
        notifyObservers()
    }

    override fun onServerFrame(
        generation: Long,
        eventSequence: Long,
        frame: ByteArray,
    ) {
        val dispatch = synchronized(this) {
            handleServerFrameLocked(generation, eventSequence, frame)
        }
        if (dispatch != null) dispatchDynamicToolOutsideMonitor(dispatch)
    }

    private fun handleServerFrameLocked(
        generation: Long,
        eventSequence: Long,
        frame: ByteArray,
    ): DynamicToolDispatch? {
        if (generation != this.generation) {
            // Frames from a superseded generation can arrive after a bounded
            // setup-dispatch deadline initiated a restart. They are stale
            // receipts, not a new transport-order failure.
            return null
        }
        lastReceivedFrameSequence = maxOf(lastReceivedFrameSequence, eventSequence)
        val raw = try {
            frame.toString(StandardCharsets.UTF_8)
        } catch (error: Exception) {
            protocolDiagnostics(protocolFailureSummary("", frame.size, error))
            controlledRestart(
                CodexClientProblem(ClientProblemCode.MALFORMED_SERVER_FRAME, retryable = true),
            )
            return null
        }
        if (runtimePhase != ClientRuntimePhase.READY) {
            // Optional usage can race a normal stop/restart; it must never restart chat itself.
            if (restartInFlight || isTokenUsageNotification(raw)) return null
            controlledRestart(
                CodexClientProblem(ClientProblemCode.TRANSPORT_ORDER, retryable = true),
            )
            return null
        }
        // This optional domain owns its redacted parser/correlation. A missing or rejected
        // remote API must not break authenticated chat or leak pairing secrets to diagnostics.
        val realtimeOwnedFrame = runCatching {
            val envelope = JSONObject(raw)
            envelope.optString("method").startsWith("thread/realtime/") ||
                (!envelope.has("method") && envelope.optString("id").startsWith("hans_realtime_"))
        }.getOrDefault(false)
        if (realtimeOwnedFrame && eventSequence <= lastRealtimeFrameSequence) return null
        if (realtime.onFrame(generation, raw)) {
            lastRealtimeFrameSequence = eventSequence
            if (!realtime.hasPendingStart) {
                realtimeDeadline?.cancel()
                realtimeDeadline = null
            }
            return null
        }
        if (remoteControl.onRpcResponse(generation, raw) ||
            remoteControl.onStatusNotification(generation, raw)
        ) {
            remoteControlChanged()
            return null
        }
        var dispatch: DynamicToolDispatch? = null
        var notifyForFrame = true
        var optionalUsageFrame = false
        try {
            val envelope = JsonContract.parseObject(raw, ProtocolLimits.MAX_INBOUND_FRAME_BYTES)
            if (!envelope.has("method") && envelope.optString("id") in remoteInterruptRequests) {
                remoteInterruptRequests.remove(envelope.optString("id"))
                return null
            }
            if (!envelope.has("id") && envelope.has("method")) {
                updateRemotePhoneToolAuthority()
                val remoteWasActive = remotePhoneTools?.hasActiveWork == true
                remotePhoneTools?.onEvent(generation, raw)
                val params = envelope.optJSONObject("params")
                val eventThreadId = params?.optString("threadId")?.takeIf(String::isNotBlank)
                    ?: params?.optJSONObject("thread")?.optString("id")?.takeIf(String::isNotBlank)
                // A second desktop task must never replace or pollute the phone's open chat.
                // Its authoritative lifecycle is consumed above, independently of the UI reducer.
                val localThread = reducer.snapshot().currentThreadId
                if (eventThreadId != null && eventThreadId != localThread &&
                    (localThread != null || !rehydrating)) {
                    if (remoteWasActive != (remotePhoneTools?.hasActiveWork == true)) notifyObservers()
                    return null
                }
            }
            optionalUsageFrame = !envelope.has("id") &&
                envelope.opt("method") == "thread/tokenUsage/updated"
            val serverRequest = if (envelope.has("id") && envelope.has("method")) {
                DynamicToolProtocol.decodeServerRequest(raw)
            } else {
                ServerRequestDecodeResult.NotServerRequest
            }
            when (serverRequest) {
                is ServerRequestDecodeResult.DynamicToolCall -> {
                    dispatch = acceptDynamicToolCall(serverRequest.call)
                }
                is ServerRequestDecodeResult.Unsupported -> sendDynamicToolError(
                    serverRequest.requestId,
                    code = -32601,
                    message = "method_not_supported",
                )
                is ServerRequestDecodeResult.Malformed -> serverRequest.requestId?.let {
                    sendDynamicToolError(
                        it,
                        code = -32602,
                        message = "invalid_dynamic_tool_request",
                    )
                }
                ServerRequestDecodeResult.NotServerRequest -> when {
                    envelope.has("id") -> handleResponse(raw, envelope)
                    envelope.has("method") -> notifyForFrame = handleEvent(
                        eventSequence = eventSequence,
                        raw = raw,
                        frameBytes = frame.size,
                    )
                    else -> throw ProtocolException("Server frame is neither response nor event")
                }
            }
        } catch (error: Exception) {
            if (isTokenUsageNotification(raw)) {
                // Measurement is optional: no payload/error text, restart, UI change or request.
                protocolDiagnostics("Ignored malformed optional event:thread/tokenUsage/updated")
                return null
            }
            protocolDiagnostics(protocolFailureSummary(raw, frame.size, error))
            if (
                isolateMalformedOptionalResponse(raw) ||
                isolateMalformedOptionalEvent(raw)
            ) {
                maybeStartQueuedBundledSetupRetry()
                notifyObservers()
                return null
            }
            controlledRestart(
                CodexClientProblem(ClientProblemCode.MALFORMED_SERVER_FRAME, retryable = true),
            )
            return null
        }
        if (notifyForFrame) {
            if (!optionalUsageFrame) maybeStartQueuedBundledSetupRetry()
            notifyObservers()
        }
        return dispatch
    }

    private fun isTokenUsageNotification(raw: String): Boolean {
        val envelope = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        return !envelope.has("id") && envelope.opt("method") == "thread/tokenUsage/updated"
    }

    /**
     * Deliberately excludes frame bodies, prompts, responses and auth data.
     * The summary is sufficient to identify which typed protocol boundary
     * rejected an otherwise transport-valid frame on a real device.
     */
    private fun protocolFailureSummary(
        raw: String,
        frameBytes: Int,
        error: Exception,
    ): String {
        val envelope = runCatching { JSONObject(raw) }.getOrNull()
        val method = envelope?.optString("method")?.takeIf(String::isNotBlank)
        val requestId = envelope?.let { candidate ->
            runCatching { responseRequestId(candidate) }.getOrNull()
        }
        val purpose = requestId?.let(requestPurposes::get)?.javaClass?.simpleName
        val boundary = when {
            method != null -> "event:$method"
            purpose != null -> "response:$purpose"
            requestId != null -> "response:unmatched"
            else -> "unknown"
        }
        val detail = error.message.orEmpty()
            .filterNot(Char::isISOControl)
            .take(MAX_PROTOCOL_DIAGNOSTIC_DETAIL_CHARS)
        return buildString {
            append("Rejected ")
            append(boundary)
            append(" frame bytes=")
            append(frameBytes)
            append(" cause=")
            append(error.javaClass.simpleName)
            if (detail.isNotBlank()) {
                append(" detail=")
                append(detail)
            }
        }
    }

    /**
     * The outer JSON contract can reject an optional capability response
     * before [handleResponse] gets a chance to apply its domain isolation.
     * Recover only the bounded top-level request id from the already
     * transport-limited frame, then fail that optional operation without
     * invalidating authenticated chat state.
     */
    private fun isolateMalformedOptionalResponse(raw: String): Boolean {
        val envelope = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        if (envelope.has("method")) return false
        val requestId = runCatching { responseRequestId(envelope) }.getOrNull() ?: return false
        val purpose = requestPurposes[requestId] ?: return false
        if (!purpose.isPluginPurpose()) return false
        correlator.discard(requestId)
        requestPurposes.remove(requestId)
        failMalformedPluginPurpose(purpose)
        return true
    }

    private fun isolateMalformedOptionalEvent(raw: String): Boolean {
        val envelope = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        if (envelope.has("id")) return false
        return when (envelope.optString("method")) {
            "thread/settings/updated" -> {
                if (pendingSettingsUpdate != null) {
                    invalidateSelectionUpdate()
                    setProblem(ClientProblemCode.SELECTION_UPDATE, retryable = true)
                }
                true
            }
            "app/list/updated" -> {
                noteAppsChanged()
                true
            }
            "skills/changed" -> {
                noteSkillsChanged()
                true
            }
            else -> false
        }
    }

    private fun noteSkillsChanged() {
        if (rehydrating) {
            skillRefreshQueued = true
            pluginRefreshQueued = true
            return
        }
        if (refreshSkills(forceReload = true) == null) {
            skillRefreshQueued = true
        }
        // Plugin creation/materialization commonly changes its skill files first.
        if (refreshPlugins(forceRefetch = false) == null) {
            pluginRefreshQueued = true
        }
    }

    private fun noteAppsChanged() {
        if (rehydrating) return
        // Codex 0.149 emits this hint from inside app/list before returning
        // the authoritative paginated response. Never enqueue a follow-up in
        // that case or the notification would create a refresh loop.
        if (requestPurposes.values.any { it is RequestPurpose.PluginApps }) return
        refreshApps(forceRefetch = false)
    }

    private fun acceptDynamicToolCall(call: DynamicToolServerCall): DynamicToolDispatch? {
        if (call.requestId in completedDynamicRequestIds) return null
        val executor = dynamicToolExecutor
        if (executor == null) {
            sendDynamicToolError(call.requestId, -32601, "method_not_supported")
            return null
        }
        val identity = DynamicCallIdentity.from(call.params)
        completedDynamicResults[identity]?.let { prior ->
            sendDynamicToolResult(call.requestId, prior)
            return null
        }
        pendingDynamicCalls[call.requestId]?.let { prior ->
            if (prior.identity == identity && prior.params == call.params) return null
            abandonPendingDynamicCall(prior)
            sendDynamicToolError(call.requestId, -32602, "invalid_dynamic_tool_request")
            return null
        }
        if (pendingDynamicCallIds.containsKey(identity)) {
            sendDynamicToolError(call.requestId, -32602, "invalid_dynamic_tool_request")
            return null
        }
        if (pendingDynamicCalls.size >= MAX_PENDING_DYNAMIC_CALLS) {
            sendDynamicToolError(call.requestId, -32000, "dynamic_tool_capacity_exceeded")
            return null
        }
        val activeGeneration = generation
        val currentThreadId = reducer.snapshot().currentThreadId
        val currentTurnId = activeTurn?.turnId ?: unknownActiveTurnId
        val voiceAccount = realtimeTurnAccounts[call.params.turnId]
        if (voiceAccount != null && (!signedIn || reducer.snapshot().account.identity != voiceAccount || !desktopRelayIsQuiescent())) {
            sendDynamicToolError(call.requestId, -32001, "realtime_account_context_changed")
            return null
        }
        if (call.params.turnId in nativeNotificationUnprovenTurnIds ||
            (call.params.turnId in remoteControlledTurnIds &&
                (!desktopRemoteAccessEnabled || !signedIn || !remoteControl.snapshot.mayUsePhoneToolsRemotely))) {
            sendDynamicToolError(call.requestId, -32001, "remote_control_consent_not_active")
            return null
        }
        if (
            activeGeneration == null ||
            call.params.threadId != currentThreadId ||
            call.params.turnId != currentTurnId
        ) {
            sendDynamicToolError(call.requestId, -32602, "invalid_dynamic_tool_request")
            return null
        }
        if (dynamicToolTurnAuthorizationGate.blocks(call.params)) {
            sendDynamicToolError(
                call.requestId,
                -32001,
                "dynamic_tool_blocked_untrusted_notification_context",
            )
            return null
        }
        if (localRuntimeStopRequested ||
            locallyStoppingDynamicTurn == (call.params.threadId to call.params.turnId)
        ) {
            sendDynamicToolError(call.requestId, -32602, "invalid_dynamic_tool_request")
            return null
        }
        val pending = PendingDynamicCall(
            requestId = call.requestId,
            generation = activeGeneration,
            identity = identity,
            params = call.params,
        )
        pendingDynamicCalls[pending.requestId] = pending
        pendingDynamicCallIds[pending.identity] = pending.requestId
        if (call.params.namespace in SETUP_DYNAMIC_NAMESPACES) {
            rememberSetupTurn(call.params.turnId)
        }
        return DynamicToolDispatch(pending, executor)
    }

    private fun dispatchDynamicToolOutsideMonitor(dispatch: DynamicToolDispatch) {
        if (dispatch.pending.isCancellationRequested()) return
        try {
            val handle = dispatch.executor.executeCancellable(
                dispatch.pending.params,
                dispatch.pending,
            ) { result ->
                completeDynamicTool(dispatch.pending, result)
            }
            val cancelLateHandle = synchronized(this) {
                if (dispatch.pending.isCancellationRequested()) {
                    true
                } else {
                    // Synchronous completion may already have removed this exact Pending.
                    if (pendingDynamicCalls[dispatch.pending.requestId] === dispatch.pending) {
                        dispatch.pending.executionHandle = handle
                    }
                    false
                }
            }
            if (cancelLateHandle) runCatching { handle.cancel() }
        } catch (_: Exception) {
            // A delegate may enqueue work and then throw before publishing its handle.
            // The signal still fences that queued work even when there is no handle to cancel.
            dispatch.pending.requestCancellation()
            val failure = runCatching {
                dispatch.executor.failureResult(
                    dispatch.pending.params,
                    "executor_exception",
                )
            }.getOrNull()
            if (failure == null) {
                synchronized(this) {
                    val pending = pendingDynamicCalls[dispatch.pending.requestId]
                    if (pending == dispatch.pending) {
                        abandonPendingDynamicCall(pending)
                        sendDynamicToolError(
                            pending.requestId,
                            -32603,
                            "dynamic_tool_execution_failed",
                        )
                        notifyObservers()
                    }
                }
            } else {
                completeDynamicTool(dispatch.pending, failure)
            }
        }
    }

    private fun completeDynamicTool(
        expected: PendingDynamicCall,
        result: DynamicToolExecutionResult,
    ) {
        synchronized(this) {
            val pending = pendingDynamicCalls[expected.requestId] ?: return
            if (pending != expected) return
            val currentTurnId = activeTurn?.turnId ?: unknownActiveTurnId
            if (
                generation != pending.generation ||
                reducer.snapshot().currentThreadId != pending.identity.threadId ||
                currentTurnId != pending.identity.turnId
            ) {
                abandonPendingDynamicCall(pending)
                notifyObservers()
                return
            }
            removePendingDynamicCall(pending)
            completedDynamicResults[pending.identity] = result
            trimCompletedDynamicResults()
            performanceEvent(PerformanceEvent.TOOL_RESULT_SEND)
            sendDynamicToolResult(pending.requestId, result)
            notifyObservers()
        }
    }

    private fun sendDynamicToolResult(
        requestId: ServerRequestId,
        result: DynamicToolExecutionResult,
    ) {
        if (!rememberDynamicRequestCompleted(requestId)) return
        sendDynamicToolFrame(DynamicToolProtocol.response(requestId, result))
    }

    private fun sendDynamicToolError(
        requestId: ServerRequestId,
        code: Int,
        message: String,
    ) {
        if (!rememberDynamicRequestCompleted(requestId)) return
        sendDynamicToolFrame(DynamicToolProtocol.error(requestId, code, message))
    }

    private fun sendDynamicToolFrame(raw: String) {
        val activeGeneration = generation ?: return
        try {
            transport.sendFrame(activeGeneration, raw.toByteArray(StandardCharsets.UTF_8))
        } catch (_: Exception) {
            controlledRestart(
                CodexClientProblem(ClientProblemCode.DISPATCH_AMBIGUOUS, retryable = false),
            )
        }
    }

    private fun rememberDynamicRequestCompleted(requestId: ServerRequestId): Boolean {
        if (!completedDynamicRequestIds.add(requestId)) return false
        while (completedDynamicRequestIds.size > MAX_COMPLETED_DYNAMIC_REQUESTS) {
            completedDynamicRequestIds.remove(completedDynamicRequestIds.first())
        }
        return true
    }

    private fun removePendingDynamicCall(pending: PendingDynamicCall) {
        pendingDynamicCalls.remove(pending.requestId)
        pendingDynamicCallIds.remove(pending.identity, pending.requestId)
        pending.executionHandle = null
    }

    private fun abandonPendingDynamicCall(pending: PendingDynamicCall) {
        pending.requestCancellation()
        val handle = pending.executionHandle
        removePendingDynamicCall(pending)
        // The handle is non-blocking. In-flight effects may already have started; cancelling
        // suppresses future entry points/results, never claims to roll those effects back.
        runCatching { handle?.cancel() }
    }

    private fun abandonPendingDynamicCalls(clearRequestIds: Boolean) {
        val abandoned = pendingDynamicCalls.values.toList()
        abandoned.forEach(PendingDynamicCall::requestCancellation)
        abandoned.forEach(::abandonPendingDynamicCall)
        if (clearRequestIds) completedDynamicRequestIds.clear()
    }

    private fun abandonPendingDynamicTurn(threadId: String?, turnId: String) {
        if (threadId == null) return
        pendingDynamicCalls.values
            .filter { it.identity.threadId == threadId && it.identity.turnId == turnId }
            .toList()
            .also { calls -> calls.forEach(PendingDynamicCall::requestCancellation) }
            .forEach(::abandonPendingDynamicCall)
    }

    /** Local stop intent revokes tools now; only the remote ACK ends the active turn. */
    private fun stopDynamicToolTurnLocally(threadId: String, turnId: String): Boolean {
        val expectedGeneration = generation
        locallyStoppingDynamicTurn = threadId to turnId
        if (retainedActiveTurn?.let { it.threadId to it.turnId } == locallyStoppingDynamicTurn) {
            retainedActiveTurn = null
        }
        if (identityOnlyActiveTurn == locallyStoppingDynamicTurn) identityOnlyActiveTurn = null
        val abandoned = pendingDynamicCalls.values.filter {
            it.identity.threadId == threadId && it.identity.turnId == turnId
        }
        abandoned.forEach(PendingDynamicCall::requestCancellation)
        abandoned.forEach(::abandonPendingDynamicCall)
        abandoned.forEach { pending ->
            if (generation == pending.generation && runtimePhase == ClientRuntimePhase.READY) {
                // Resolve the tool RPC even if the remote interrupt is later rejected. This
                // says neither that the remote turn ended nor that an effect was rolled back.
                sendDynamicToolError(pending.requestId, -32603, "dynamic_tool_execution_failed")
            }
        }
        // Sending a tool error may itself discover transport loss and begin recovery.
        // Do not enqueue another request on that superseded or restarting transport.
        return expectedGeneration != null && generation == expectedGeneration &&
            runtimePhase == ClientRuntimePhase.READY
    }

    private fun trimCompletedDynamicResults() {
        while (completedDynamicResults.size > MAX_COMPLETED_DYNAMIC_RESULTS) {
            completedDynamicResults.remove(completedDynamicResults.keys.first())
        }
    }

    @Synchronized
    override fun onTransportNotice(
        generation: Long,
        eventSequence: Long,
        code: Int,
        relatedSequence: Long,
    ) {
        if (generation != this.generation) return
        val codeValue = if (code == AppServerSessionContract.NOTICE_STALE_GENERATION) {
            ClientProblemCode.TRANSPORT_ORDER
        } else {
            ClientProblemCode.TRANSPORT_REJECTED
        }
        controlledRestart(CodexClientProblem(codeValue, retryable = true))
    }

    @Synchronized
    override fun onTransportProtocolFailure(failure: TransportProtocolFailure) {
        controlledRestart(
            CodexClientProblem(ClientProblemCode.TRANSPORT_ORDER, retryable = true),
        )
    }

    private fun handleReady(incomingGeneration: Long) {
        if (incomingGeneration <= 0) {
            failPermanently(ClientProblemCode.RUNTIME_FAILED, retryable = true)
            return
        }
        runtimeSessionActive = true
        if (generation != incomingGeneration) prepareGeneration(incomingGeneration)
        if (bootstrappedGeneration == incomingGeneration) {
            runtimePhase = ClientRuntimePhase.READY
            return
        }
        bootstrappedGeneration = incomingGeneration
        restartInFlight = false
        runtimePhase = ClientRuntimePhase.READY
        sessionPhase = ClientSessionPhase.BOOTSTRAPPING
        problem = null
        rehydrating = true
        accountReadComplete = false
        signedIn = false
        resumedThreadId = null
        memoryModeEnabledThreadId = null
        threadBootstrapRequested = false
        threadReady = false
        modelPages = ModelListAccumulator()
        catalog = null
        modelCatalogRefreshAfterAccountRead = false
        modelCatalogRefreshQueued = false
        modelCatalogSettingsRefreshInFlight = false
        lastConfirmedAccountSignedIn = null
        if (!refreshAccount()) return
        requestModelPage(null)
    }

    private fun prepareGeneration(newGeneration: Long) {
        invalidateNativeNotificationRequests()
        nativeNotificationHistory = null
        nativeNotificationTurnIds.clear()
        nativeNotificationUnprovenTurnIds.clear()
        retiredNativeNotificationRequests.clear()
        realtimeDispatchWaiter?.let {
            settleRealtimeDispatch(it.messageId, Result.failure(CodexRealtimeFailure(CodexRealtimeIssue.SESSION_CHANGED)))
        }
        realtimeOriginAccount = null
        realtimeDeadline?.cancel()
        realtimeDeadline = null
        realtime.resetGeneration()
        preparingRealtime?.let { failPreparedRealtime(it, CodexRealtimeIssue.SESSION_CHANGED) }
        realtimeTurnAccounts.clear()
        voiceControlTurnProofs.clear()
        voiceControlProofCapacityExceeded = false
        lastRealtimeFrameSequence = 0L
        remotePolicyProbedGeneration = null
        remotePolicyDisableAttempted = false
        cancelWorkInterruptDeadlines()
        retiredWorkInterruptRequestIds.clear()
        invalidateRemoteControl()
        if (generation != null && newGeneration <= generation!!) {
            throw CrossCorrelationException("Runtime generation did not advance")
        }
        invalidateSelectionContext()
        retiredSettingsRequestIds.clear()
        lastReceivedFrameSequence = 0L
        retiredFreshBootstrapRequestIds.clear()
        cancelPluginRecoveryEpoch()
        if (generation != null) {
            abortRuntimePluginTransactions()
            pluginReducer.onSessionLost()
        }
        markPendingDispatchFailed(retryable = false)
        abandonPendingDynamicCalls(clearRequestIds = true)
        dynamicToolTurnAuthorizationGate.resetGeneration()
        reducer.clearTokenUsageForRuntimeChange()
        generation = newGeneration
        performanceObservedTurnId = null
        performanceLastTerminalTurnId = null
        performanceTerminalTurnIds.clear()
        performanceContextThreadId = null
        performanceUserWaitReported = false
        performanceAssistantOutputObserved = false
        localRuntimeStopRequested = false
        correlator = ResponseCorrelator()
        requestPurposes.clear()
        bufferedEvents.clear()
        bufferedEventBytes = 0
        rehydrating = true
        bootstrappedGeneration = null
        activeTurn = null
        unknownActiveTurnId = null
        identityOnlyActiveTurn = null
        // A lost interrupt ACK must not revive the same locally-stopped turn after resume.
        // The marker is thread/turn scoped, so a genuinely new turn is unaffected.
        resumedThreadId = null
        memoryModeEnabledThreadId = null
        migrationEffectiveSelection = null
        terminalTurnIds.clear()
        terminalTurns.clear()
        recoveredAgentChannelHistory = null
        agentChannelHistoryRevision += 1
        setupTurnIds.clear()
        deviceCodeLogin = null
        pluginBootstrappedGeneration = null
        pluginRefreshQueued = false
        appRefreshQueued = false
        skillRefreshQueued = false
        bundledSetupBootstrapPhase = BundledSetupBootstrapPhase.IDLE
        bundledSetupProvenPluginHandle = null
        bundledSetupRetryQueued = false
        pluginRecoveryGateInProgress = false
        pluginMutationsBlockedByRecovery =
            pluginInstallTransactions != null || pluginUninstallTransactions != null
        cancelSetupDispatchDeadline()
    }

    private fun handleResponse(raw: String, envelope: JSONObject) {
        val anticipatedId = responseRequestId(envelope)
        if (anticipatedId in retiredNativeNotificationRequests) return
        if (anticipatedId in retiredFreshBootstrapRequestIds) return
        if (anticipatedId in retiredSettingsRequestIds || anticipatedId in retiredWorkInterruptRequestIds) return
        val anticipatedPurpose = anticipatedId?.let(requestPurposes::get)
        try {
            val response = correlator.accept(raw)
            val purpose = requestPurposes.remove(response.id)
                ?: throw CrossCorrelationException("Response has no controller purpose")
            if (purpose.isFreshThreadBootstrap()) retireFreshBootstrapRequest(response.id)
            if (purpose is RequestPurpose.UserInterrupt) retireWorkInterrupt(response.id)
            nativeNotificationDeadlines.remove(response.id)?.cancel()
            cancelSetupDispatchDeadline(response.id)
            if (purpose == RequestPurpose.AccountRead && response is CorrelatedResponse.Success &&
                (response.result as AccountReadResult).account != reducer.snapshot().account.identity
            ) {
                invalidateRealtime()
                invalidateSelectionContext(invalidateActiveProof = true)
            }
            reducer.apply(response)
            when (response) {
                is CorrelatedResponse.Success -> handleSuccess(purpose, response.result)
                is CorrelatedResponse.Failure -> handleFailure(purpose, response.error)
            }
        } catch (error: Exception) {
            if (anticipatedPurpose is RequestPurpose.NotificationToolOutput ||
                anticipatedPurpose is RequestPurpose.NotificationHistory) {
                anticipatedId?.let {
                    correlator.discard(it); requestPurposes.remove(it)
                    nativeNotificationDeadlines.remove(it)?.cancel()
                    retireNativeNotificationRequest(it)
                }
                if (anticipatedPurpose is RequestPurpose.NotificationToolOutput) {
                    protocolDiagnostics("notification_event_receipt_ambiguous")
                    settleNativeNotification(anticipatedPurpose, NativeNotificationDispatchReceipt.OutcomeAmbiguous)
                } else protocolDiagnostics("notification_event_history_invalid")
                return
            }
            if (anticipatedPurpose?.isFreshThreadBootstrap() == true) {
                anticipatedId?.let {
                    retireFreshBootstrapRequest(it)
                    correlator.discard(it)
                    requestPurposes.remove(it)
                }
                recordThreadBootstrapFailure(anticipatedPurpose,
                    BootstrapFailureStage.INVALID_RECEIPT, local = error)
                failSession(ClientProblemCode.THREAD_RECOVERY, retryable = true)
                return
            }
            if (anticipatedPurpose is RequestPurpose.SettingsUpdate) {
                retireSettingsRequest(anticipatedPurpose.requestId)
                if (pendingSettingsUpdate?.requestId == anticipatedPurpose.requestId) {
                    invalidateSelectionUpdate()
                    setProblem(ClientProblemCode.SELECTION_UPDATE, retryable = true)
                }
                return
            }
            if (anticipatedPurpose?.isPluginPurpose() == true) {
                anticipatedId?.let {
                    correlator.discard(it)
                    requestPurposes.remove(it)
                }
                if (anticipatedPurpose is RequestPurpose.BundledMarketplaceAdd) {
                    protocolDiagnostics("Bundled setup marketplace/add returned malformed data")
                    requestBundledSetupPluginList(expectInstalled = false)
                } else {
                    failMalformedPluginPurpose(anticipatedPurpose)
                    if (anticipatedPurpose.isBundledSetupPurpose()) {
                        failBundledSetupBootstrap("Bundled setup plugin proof was malformed")
                    }
                }
                // Plugins/apps/skills are optional capability domains. A
                // schema drift or bad marketplace record must not tear down a
                // healthy authenticated chat session.
                return
            }
            throw error
        }
    }

    private fun retireFreshBootstrapRequest(id: RequestId) {
        retiredFreshBootstrapRequestIds.add(id)
        while (retiredFreshBootstrapRequestIds.size > ProtocolLimits.MAX_PENDING_REQUESTS) {
            retiredFreshBootstrapRequestIds.remove(retiredFreshBootstrapRequestIds.first())
        }
    }

    private fun RequestPurpose.isFreshThreadBootstrap(): Boolean =
        this is RequestPurpose.ThreadStart || this is RequestPurpose.ThreadMaterialize ||
            (this is RequestPurpose.ThreadMemoryEnable && freshProof != null)

    private fun handleSuccess(purpose: RequestPurpose, result: AppServerResult) {
        when (purpose) {
            is RequestPurpose.NotificationToolOutput -> {
                val accepted = extensionPayload<NotificationToolOutputTurnResult>(result)
                check(accepted.threadId == purpose.threadId)
                if (accepted.turnId !in remoteControlledTurnIds) {
                    nativeNotificationTurnIds.add(accepted.turnId)
                    nativeNotificationUnprovenTurnIds.remove(accepted.turnId)
                }
                while (nativeNotificationTurnIds.size > 128) {
                    nativeNotificationTurnIds.remove(nativeNotificationTurnIds.first())
                }
                recordNativeNotificationReceipt(NativeNotificationExternalReceipt(purpose.eventId,
                    purpose.payloadSha256, accepted.threadId, accepted.turnId))
                if (accepted.status == TurnStatus.IN_PROGRESS && accepted.turnId !in terminalTurnIds) {
                    if (activeTurn == null && unknownActiveTurnId in setOf(null, accepted.turnId)) {
                        // Omitted overrides preserve native thread defaults but prove identity only.
                        activeTurn = null
                        unknownActiveTurnId = accepted.turnId
                        identityOnlyActiveTurn = accepted.threadId to accepted.turnId
                    }
                } else if (accepted.status != TurnStatus.IN_PROGRESS) {
                    rememberTerminalTurn(accepted.threadId, accepted.turnId, accepted.status)
                    if (activeTurn?.turnId == accepted.turnId) activeTurn = null
                    if (unknownActiveTurnId == accepted.turnId) unknownActiveTurnId = null
                }
                updateSessionPhase()
                settleNativeNotification(purpose, NativeNotificationDispatchReceipt.Accepted(
                    accepted.threadId, accepted.turnId))
            }
            is RequestPurpose.NotificationHistory -> {
                val page = extensionPayload<NativeNotificationHistoryPage>(result)
                if (reducer.snapshot().currentThreadId != purpose.threadId) return
                if (page.history == null) {
                    protocolDiagnostics("notification_event_history_invalid")
                } else {
                    val previous = nativeNotificationHistory?.takeIf { it.threadId == purpose.threadId }
                    nativeNotificationHistory = page.history.copy(
                        receipts = (previous?.receipts.orEmpty() + page.history.receipts).distinct().takeLast(256),
                        turnStatuses = (previous?.turnStatuses.orEmpty() + page.history.turnStatuses).entries.toList()
                            .takeLast(256).associate { it.key to it.value },
                    )
                }
            }
            is RequestPurpose.SettingsUpdate -> {
                check(result === ThreadSettingsUpdateResult)
                retireSettingsRequest(purpose.requestId)
                pendingSettingsUpdate?.takeIf {
                    it.requestId == purpose.requestId && it.contextEpoch == purpose.contextEpoch
                }?.let {
                    it.acknowledged = true
                    completeSelectionUpdateIfProven(it)
                }
            }
            RequestPurpose.AccountRead -> {
                val account = result as AccountReadResult
                accountReadComplete = true
                signedIn = account.account != null
                val refreshModels = signedIn &&
                    (modelCatalogRefreshAfterAccountRead || lastConfirmedAccountSignedIn == false)
                lastConfirmedAccountSignedIn = signedIn
                modelCatalogRefreshAfterAccountRead = false
                if (signedIn) {
                    deviceCodeLogin = null
                    probeDesktopRemotePolicyOnce()
                    if (refreshModels) refreshModelCatalogAfterAuthentication()
                    maybeBootstrapThread()
                } else {
                    recoveredTimeline = emptyList()
                    sessionPhase = ClientSessionPhase.AUTH_REQUIRED
                    if (revokeRemoteControlOnAuthenticationLoss()) return
                    finishRehydrationIfReady()
                }
            }
            RequestPurpose.LoginStart -> {
                val login = result as DeviceCodeLoginResult
                deviceCodeLogin = DeviceCodeLoginUi(login.userCode, login.verificationUrl)
                sessionPhase = ClientSessionPhase.LOGIN_PENDING
            }
            RequestPurpose.Logout -> {
                check(result === AccountLogoutResult)
                signedIn = false
                lastConfirmedAccountSignedIn = false
                modelCatalogRefreshAfterAccountRead = false
                deviceCodeLogin = null
                recoveredTimeline = emptyList()
                sessionPhase = ClientSessionPhase.AUTH_REQUIRED
                abandonPendingDynamicCalls(clearRequestIds = false)
                abortRuntimePluginTransactions()
                pluginReducer.onSessionLost()
                invalidateBundledSetupBootstrap()
                if (remoteLogoutRequiresRuntimeRestart) {
                    // Logout ACK proves credentials were removed. Restarting just the embedded
                    // server closes any relay whose disable result raced the logout response.
                    controlledRestart(CodexClientProblem(ClientProblemCode.RUNTIME_FAILED, retryable = true),
                        automatic = false)
                }
            }
            RequestPurpose.ModelList -> {
                if (modelCatalogRefreshQueued) {
                    startQueuedModelCatalogRefresh()
                    return
                }
                val page = result as ModelListResult
                val accumulatedCatalog = modelPages.append(page)
                val cursor = modelPages.nextCursor()
                if (cursor == null) {
                    catalog = accumulatedCatalog
                    if (modelCatalogSettingsRefreshInFlight) {
                        modelCatalogSettingsRefreshInFlight = false
                        if (problem?.code == ClientProblemCode.MODEL_CATALOG) problem = null
                    }
                    maybeBootstrapThread()
                } else {
                    requestModelPage(cursor)
                }
            }
            is RequestPurpose.ThreadResume -> {
                invalidateSelectionContext()
                val resumed = result as ThreadResumeResult
                // Raw protocol IDs, not filtered visible text, establish restart correlation.
                recoveredAgentChannelHistory = agentChannelRecoveredHistoryFromResume(resumed)
                agentChannelHistoryRevision += 1
                resumedThreadId = resumed.thread.id
                recoveredTimeline = hydrateRecoveredTimeline(
                    threadId = resumed.thread.id,
                    items = resumed.recoveredItems,
                )
                // The requested page is descending (newest first), while the bounded
                // receipt map evicts from the front. Normalize to oldest -> newest.
                resumed.initialTurnReceipts.asReversed().forEach { receipt ->
                    if (receipt.status != TurnStatus.IN_PROGRESS) {
                        rememberTerminalTurn(
                            threadId = resumed.thread.id,
                            turnId = receipt.turnId,
                            status = receipt.status,
                        )
                    }
                }
                val activeReceipts = resumed.initialTurnReceipts.filter {
                    it.status == TurnStatus.IN_PROGRESS
                }
                activeTurn = null
                unknownActiveTurnId = null
                identityOnlyActiveTurn = null
                if (activeReceipts.size == 1) {
                    val recoveredTurnId = activeReceipts.single().turnId
                    val recoveredProof = retainedActiveTurn?.takeIf {
                        it.threadId == resumed.thread.id && it.turnId == recoveredTurnId
                    }
                    if (
                        recoveredProof != null &&
                        locallyStoppingDynamicTurn != resumed.thread.id to recoveredTurnId
                    ) {
                        activeTurn = recoveredProof
                        settingsDefaultActiveTurn = resumed.thread.id to recoveredTurnId
                    } else {
                        // Resume proves current identity, not immutable turn options. A single
                        // unstopped receipt can safely accept ID-only steer; it cannot prove a
                        // model change or borrow the thread's newly changed defaults.
                        unknownActiveTurnId = recoveredTurnId
                        if (locallyStoppingDynamicTurn != resumed.thread.id to recoveredTurnId) {
                            identityOnlyActiveTurn = resumed.thread.id to recoveredTurnId
                        }
                    }
                } else if (activeReceipts.size > 1) {
                    // One thread cannot safely expose more than one steerable active turn. Keep
                    // dispatch blocked until authoritative live events resolve the ambiguity.
                    unknownActiveTurnId = activeReceipts.first().turnId
                }
                provenThreadSettingsSelection = resumed.effectiveEffort?.let { effort ->
                    runCatching {
                        DispatchSelection(
                            resumed.effectiveModel,
                            effort,
                            resumed.effectiveServiceTier ?: HansSettings.DEFAULT_SERVICE_TIER,
                        )
                    }.getOrNull()
                }
                retainedActiveTurn = activeTurn
                migrationEffectiveSelection = when {
                    activeTurn != null -> activeTurn?.effectiveOptions?.let {
                        DispatchSelection(it.model, it.effort, it.serviceTier)
                    }
                    unknownActiveTurnId != null -> null
                    else -> provenThreadSettingsSelection
                }
                lastThreadSettingsEventSequence = lastReceivedFrameSequence
                requestPersonalThreadMemoryEnable(resumed.thread.id)
            }
            is RequestPurpose.ThreadStart -> {
                invalidateSelectionContext(invalidateActiveProof = true)
                val started = result as ThreadStartResult
                if (!freshBootstrapContextMatches(purpose.proof, started.threadId)) {
                    failSession(ClientProblemCode.THREAD_RECOVERY, retryable = true)
                    return
                }
                resumedThreadId = null
                recoveredTimeline = emptyList()
                recoveredAgentChannelHistory = null
                agentChannelHistoryRevision += 1
                migrationEffectiveSelection = null
                provenThreadSettingsSelection = started.effectiveEffort?.let { effort ->
                    runCatching {
                        DispatchSelection(started.effectiveModel, effort,
                            started.effectiveServiceTier ?: HansSettings.DEFAULT_SERVICE_TIER)
                    }.getOrNull()
                }
                lastThreadSettingsEventSequence = lastReceivedFrameSequence
                requestPersonalThreadMemoryEnable(started.threadId, purpose.proof)
            }
            is RequestPurpose.ThreadMemoryEnable -> {
                check(result === ThreadMemoryModeSetResult)
                if (reducer.snapshot().currentThreadId != purpose.threadId) {
                    throw CrossCorrelationException(
                        "thread/memoryMode/set confirmed a non-current thread",
                    )
                }
                memoryModeEnabledThreadId = purpose.threadId
                if (purpose.freshProof != null) {
                    if (!freshBootstrapContextMatches(purpose.freshProof, purpose.threadId)) {
                        failSession(ClientProblemCode.THREAD_RECOVERY, retryable = true)
                        return
                    }
                    transmitThreadBootstrap(RequestPurpose.ThreadMaterialize(purpose.threadId, purpose.freshProof)) {
                        AppServerRequests.materializeFreshThread(requestIds.next(), purpose.threadId)
                    }
                    return
                }
                threadReady = true
                finishRehydrationIfReady()
            }
            is RequestPurpose.ThreadMaterialize -> {
                val receipt = result as ThreadMaterializeResult
                if (receipt.threadId != purpose.threadId ||
                    !freshBootstrapContextMatches(purpose.proof, purpose.threadId) ||
                    memoryModeEnabledThreadId != purpose.threadId ||
                    bufferedEvents.any { buffered -> when (val event = buffered.event) {
                        is ServerEvent.TurnStarted -> event.threadId == purpose.threadId
                        is ServerEvent.TurnCompleted -> event.threadId == purpose.threadId
                        else -> false
                    } }
                ) {
                    throw CrossCorrelationException("Fresh thread materialization lost its bootstrap context")
                }
                // Never publish an unmaterialized ID, nor overwrite a pointer changed by a
                // separate local recovery action while this storage barrier was in flight.
                try {
                    check(sessionStore.readThreadId() == null) { "Selected thread changed during bootstrap" }
                    sessionStore.saveThreadId(receipt.threadId)
                } catch (_: Exception) {
                    failSession(ClientProblemCode.LOCAL_PERSISTENCE, retryable = true)
                    return
                }
                threadReady = true
                finishRehydrationIfReady()
            }
            is RequestPurpose.TurnStart -> {
                val started = result as TurnStartResult
                migrationEffectiveSelection = runCatching {
                    DispatchSelection(
                        model = started.effectiveOptions.model,
                        effort = started.effectiveOptions.effort,
                        serviceTier = started.effectiveOptions.serviceTier,
                    )
                }.getOrNull()
                provenThreadSettingsSelection = migrationEffectiveSelection
                lastThreadSettingsEventSequence = lastReceivedFrameSequence
                if (started.status == TurnStatus.IN_PROGRESS && started.turnId !in terminalTurnIds) {
                    activeTurn = started.asActiveTurn()
                } else if (started.status != TurnStatus.IN_PROGRESS) {
                    rememberTerminalTurn(started.threadId, started.turnId, started.status)
                }
                unknownActiveTurnId = null
                identityOnlyActiveTurn = null
                if (started.status == TurnStatus.IN_PROGRESS && started.turnId !in terminalTurnIds) {
                    performanceTurnStarted(started.turnId)
                } else if (started.status != TurnStatus.IN_PROGRESS) {
                    performanceTurnCompleted(started.turnId)
                }
                markDispatchSent(purpose.messageId, purpose.selection, started.turnId, purpose.confirmsDefaultSelection)
                if (started.status != TurnStatus.IN_PROGRESS) {
                    dynamicToolTurnAuthorizationGate.completeTurn(started.threadId, started.turnId)
                }
            }
            is RequestPurpose.TurnSteer -> {
                val steered = result as TurnSteerResult
                migrationEffectiveSelection = steered.effectiveOptions?.let { options -> runCatching {
                    DispatchSelection(
                        model = options.model,
                        effort = options.effort,
                        serviceTier = options.serviceTier,
                    )
                }.getOrNull() }
                if (!purpose.confirmsDefaultSelection && steered.turnId in terminalTurnIds) {
                    migrationEffectiveSelection = provenThreadSettingsSelection ?: confirmedSelection
                }
                if (steered.turnId !in terminalTurnIds && steered.effectiveOptions != null) {
                    activeTurn = ActiveTurn(
                        steered.threadId,
                        steered.turnId,
                        steered.effectiveOptions,
                    )
                    unknownActiveTurnId = null
                    identityOnlyActiveTurn = null
                }
                markDispatchSent(purpose.messageId, purpose.selection, steered.turnId, purpose.confirmsDefaultSelection)
            }
            is RequestPurpose.BoundaryInterrupt -> {
                result as TurnInterruptResult
                abandonPendingDynamicTurn(
                    reducer.snapshot().currentThreadId,
                    purpose.interruptedTurnId,
                )
                if (activeTurn?.turnId == purpose.interruptedTurnId) activeTurn = null
                unknownActiveTurnId = null
                performanceTurnCompleted(purpose.interruptedTurnId)
                startPendingDispatchAfterBoundary(purpose.messageId)
            }
            is RequestPurpose.UserInterrupt -> {
                result as TurnInterruptResult
                abandonPendingDynamicTurn(purpose.threadId, purpose.turnId)
                if (activeTurn?.turnId == purpose.turnId) activeTurn = null
                if (unknownActiveTurnId == purpose.turnId) unknownActiveTurnId = null
                if (identityOnlyActiveTurn?.second == purpose.turnId) identityOnlyActiveTurn = null
                performanceTurnCompleted(purpose.turnId)
                if (retainedActiveTurn?.turnId == purpose.turnId) retainedActiveTurn = null
                migrationEffectiveSelection = provenThreadSettingsSelection
                if (problem?.code == ClientProblemCode.INTERRUPT_REJECTED) problem = null
                updateSessionPhase()
            }
            RequestPurpose.BundledMarketplaceAdd -> {
                val added = extensionPayload<MarketplaceAddWireResult>(result)
                val expectedRoot = checkNotNull(bundledSetupPlugin.marketplaceRoot)
                val rootMatches = runCatching {
                    File(added.installedRoot).canonicalFile == File(expectedRoot).canonicalFile
                }.getOrDefault(false)
                if (
                    added.marketplaceName != BundledSetupPluginContract.MARKETPLACE_NAME ||
                    !rootMatches
                ) {
                    protocolDiagnostics("Bundled setup marketplace/add proof did not match")
                }
                requestBundledSetupPluginList(expectInstalled = false)
            }
            is RequestPurpose.BundledPluginList -> {
                val listed = extensionPayload<PluginListWireResult>(result)
                pluginReducer.applyPluginList(purpose.operationId, listed)
                continueBundledSetupAfterPluginList(listed, purpose.expectInstalled)
            }
            is RequestPurpose.BundledPluginInstall -> {
                pluginReducer.applyPluginInstall(
                    purpose.operationId,
                    extensionPayload<PluginInstallWireResult>(result),
                )
                requestBundledSetupPluginList(expectInstalled = true)
            }
            is RequestPurpose.BundledPluginSkills -> {
                val flattened = flattenSkills(result as SkillsListResult)
                pluginReducer.applySkills(purpose.operationId, flattened)
                if (flattened.any {
                        it.name == BundledSetupPluginContract.QUALIFIED_SKILL_NAME && it.enabled
                    } && bundledSetupProvenPluginHandle != null
                ) {
                    bundledSetupBootstrapPhase = BundledSetupBootstrapPhase.READY
                    refreshApps(forceRefetch = false)
                } else {
                    failBundledSetupBootstrap("Bundled setup skill was not proven enabled")
                }
            }
            is RequestPurpose.PluginList -> {
                pluginReducer.applyPluginList(
                    purpose.operationId,
                    extensionPayload(result),
                )
                if (pluginRefreshQueued) {
                    pluginRefreshQueued = false
                    refreshPlugins(forceRefetch = true)
                }
            }
            is RequestPurpose.PluginRead -> {
                pluginReducer.applyPluginRead(
                    purpose.operationId,
                    extensionPayload(result),
                )
            }
            is RequestPurpose.PluginInstall -> {
                cancelPluginInstallDeadline(purpose.operationId)
                pluginReducer.applyPluginInstall(
                    purpose.operationId,
                    extensionPayload<PluginInstallWireResult>(result),
                )
                refreshCapabilitiesAfterMutation()
            }
            is RequestPurpose.PluginRuntimeSurfaceRead -> {
                schedulePluginRuntimeSurfaceEvidence(
                    purpose = purpose,
                    read = extensionPayload<PluginReadWireResult>(result),
                )
            }
            is RequestPurpose.PluginRuntimeInstall -> {
                pluginReducer.acceptRuntimePluginInstall(
                    purpose.operationId,
                    extensionPayload<PluginInstallWireResult>(result),
                )
                val accepted = runCatching {
                    pluginInstallTransactions?.markAppServerAccepted(purpose.operationId)
                    pluginInstallTransactions != null
                }.getOrDefault(false)
                if (accepted) {
                    requestRuntimePluginInstallProof(purpose.operationId)
                } else {
                    failRuntimePluginInstall(
                        purpose.operationId,
                        PluginOperationFailure.MALFORMED_RESPONSE,
                        retryable = false,
                    )
                }
            }
            is RequestPurpose.PluginRuntimeInstallProof -> {
                val listed = extensionPayload<PluginListWireResult>(result)
                scheduleRuntimePluginCommit(purpose, listed)
            }
            is RequestPurpose.PluginInstallRecoveryList -> {
                handlePluginRecoveryList(
                    purpose = purpose,
                    listed = extensionPayload<PluginListWireResult>(result),
                )
            }
            is RequestPurpose.PluginInstallRecoveryUninstall -> {
                check(
                    extensionPayload<PluginUninstallWireResult>(result) ===
                        PluginUninstallWireResult,
                )
                handlePluginRecoveryUninstallAccepted(purpose)
            }
            is RequestPurpose.PluginInstallRecoveryAbsenceProof -> {
                handlePluginRecoveryAbsenceProof(
                    purpose = purpose,
                    listed = extensionPayload<PluginListWireResult>(result),
                )
            }
            is RequestPurpose.PluginUninstall -> {
                check(
                    extensionPayload<PluginUninstallWireResult>(result) ===
                        PluginUninstallWireResult,
                )
                schedulePluginUninstallAcceptance(purpose)
            }
            is RequestPurpose.PluginUninstallProof -> {
                schedulePluginUninstallProof(
                    purpose,
                    extensionPayload<PluginListWireResult>(result),
                )
            }
            is RequestPurpose.PluginUninstallRecoveryList -> {
                handlePluginUninstallRecoveryList(
                    purpose,
                    extensionPayload<PluginListWireResult>(result),
                )
            }
            is RequestPurpose.PluginSkillConfig -> {
                pluginReducer.applySkillConfig(
                    operationId = purpose.operationId,
                    handle = purpose.handle,
                    skillName = purpose.skillName,
                    skillPath = purpose.skillPath,
                    result = extensionPayload<SkillConfigWriteWireResult>(result),
                )
                refreshSkills(forceReload = true)
            }
            is RequestPurpose.MarketplaceUpgrade -> {
                pluginReducer.applyMarketplaceUpgrade(
                    purpose.operationId,
                    extensionPayload<MarketplaceUpgradeWireResult>(result),
                )
                if (refreshPlugins(forceRefetch = true) == null) {
                    pluginRefreshQueued = true
                }
            }
            is RequestPurpose.PluginApps -> {
                val page = extensionPayload<AppListPageWireResult>(result)
                val nextCursor = pluginReducer.applyAppPage(purpose.operationId, page)
                if (nextCursor != null) {
                    sendAppListPage(
                        purpose.operationId,
                        cursor = nextCursor,
                        forceRefetch = false,
                    )
                } else if (appRefreshQueued) {
                    appRefreshQueued = false
                    refreshApps(forceRefetch = true)
                }
            }
            is RequestPurpose.PluginSkills -> {
                pluginReducer.applySkills(
                    purpose.operationId,
                    flattenSkills(result as SkillsListResult),
                )
                if (skillRefreshQueued) {
                    skillRefreshQueued = false
                    refreshSkills(forceReload = true)
                }
            }
        }
    }

    private fun handleFailure(purpose: RequestPurpose, error: RemoteError) {
        when (purpose) {
            is RequestPurpose.NotificationToolOutput -> {
                protocolDiagnostics("notification_event_runtime_rejected")
                settleNativeNotification(purpose, NativeNotificationDispatchReceipt.RejectedByRuntime)
            }
            is RequestPurpose.NotificationHistory -> protocolDiagnostics("notification_event_history_rejected")
            is RequestPurpose.SettingsUpdate -> {
                retireSettingsRequest(purpose.requestId)
                if (pendingSettingsUpdate?.requestId == purpose.requestId) {
                    invalidateSelectionUpdate()
                    setProblem(ClientProblemCode.SELECTION_UPDATE, retryable = true)
                }
            }
            is RequestPurpose.ThreadResume -> {
                // No pinned permanent-absence error contract exists here. Even a message
                // containing "not found" can reflect transient storage/discovery failure.
                // Preserve the durable thread pointer AND its input provenance for retry.
                recordThreadBootstrapFailure(purpose, BootstrapFailureStage.REMOTE_REJECTION, error)
                failSession(ClientProblemCode.THREAD_RECOVERY, retryable = true)
            }
            is RequestPurpose.ThreadMemoryEnable,
            is RequestPurpose.ThreadMaterialize,
            -> {
                recordThreadBootstrapFailure(purpose, BootstrapFailureStage.REMOTE_REJECTION, error)
                failSession(ClientProblemCode.THREAD_RECOVERY, retryable = true)
            }
            is RequestPurpose.BoundaryInterrupt -> {
                if (activeTurn == null && unknownActiveTurnId == null) {
                    startPendingDispatchAfterBoundary(purpose.messageId)
                } else {
                    removeVisibleInputReceiptForMessage(purpose.messageId)
                    markPendingDispatchFailed(retryable = true)
                    setProblem(ClientProblemCode.DISPATCH_REJECTED, retryable = true)
                }
            }
            is RequestPurpose.TurnStart,
            is RequestPurpose.TurnSteer,
            -> {
                val messageId = when (purpose) {
                    is RequestPurpose.TurnStart -> purpose.messageId
                    is RequestPurpose.TurnSteer -> purpose.messageId
                    else -> error("unreachable")
                }
                removeVisibleInputReceiptForMessage(messageId)
                markPendingDispatchFailed(retryable = true)
                setProblem(ClientProblemCode.DISPATCH_REJECTED, retryable = true)
            }
            RequestPurpose.AccountRead,
            RequestPurpose.LoginStart,
            RequestPurpose.Logout,
            -> failSession(ClientProblemCode.AUTHENTICATION, retryable = true)
            RequestPurpose.ModelList -> {
                if (modelCatalogRefreshQueued) {
                    startQueuedModelCatalogRefresh()
                } else if (modelCatalogSettingsRefreshInFlight && catalog != null && signedIn) {
                    // A rejected read-only refresh must not fail an otherwise healthy chat or
                    // discard its last complete catalogue. A later explicit open may retry it.
                    modelCatalogSettingsRefreshInFlight = false
                    setProblem(ClientProblemCode.MODEL_CATALOG, retryable = true)
                } else {
                    failSession(ClientProblemCode.MODEL_CATALOG, retryable = true)
                }
            }
            is RequestPurpose.ThreadStart -> {
                recordThreadBootstrapFailure(purpose, BootstrapFailureStage.REMOTE_REJECTION, error)
                failSession(ClientProblemCode.THREAD_RECOVERY, retryable = true)
            }
            is RequestPurpose.UserInterrupt -> setProblem(
                ClientProblemCode.INTERRUPT_REJECTED,
                retryable = true,
            )
            RequestPurpose.BundledMarketplaceAdd -> {
                protocolDiagnostics(
                    "Bundled setup marketplace/add was rejected; trying direct discovery",
                )
                requestBundledSetupPluginList(expectInstalled = false)
            }
            is RequestPurpose.BundledPluginList,
            is RequestPurpose.BundledPluginInstall,
            is RequestPurpose.BundledPluginSkills,
            -> {
                pluginReducer.fail(
                    operationId = purpose.pluginOperationId(),
                    failure = PluginOperationFailure.REMOTE_REJECTED,
                    retryable = true,
                )
                failBundledSetupBootstrap("Bundled setup plugin request was rejected")
            }
            is RequestPurpose.PluginInstallRecoveryList ->
                failPluginRecoveryBatch(purpose.batchId, "recovery_list_rejected")
            is RequestPurpose.PluginInstallRecoveryUninstall ->
                failPluginRecoveryCompensation(
                    operationId = purpose.operationId,
                    reason = "recovery_uninstall_rejected",
                )
            is RequestPurpose.PluginInstallRecoveryAbsenceProof ->
                failPluginRecoveryCompensation(
                    operationId = purpose.operationId,
                    reason = "recovery_absence_proof_rejected",
                )
            is RequestPurpose.PluginUninstallRecoveryList ->
                failPluginUninstallRecoveryBatch(
                    purpose.batchId,
                    "uninstall_recovery_list_rejected",
                )
            is RequestPurpose.PluginUninstall -> failPluginUninstallRequest(
                operationId = purpose.operationId,
                proofOperationId = purpose.proofOperationId,
                failure = PluginOperationFailure.REMOTE_REJECTED,
                retryable = true,
                blockMutations = true,
            )
            is RequestPurpose.PluginUninstallProof -> failPluginUninstallRequest(
                operationId = purpose.uninstallOperationId,
                proofOperationId = purpose.proofOperationId,
                failure = PluginOperationFailure.REMOTE_REJECTED,
                retryable = true,
                blockMutations = true,
            )
            is RequestPurpose.PluginRuntimeSurfaceRead -> {
                cancelPluginInstallDeadline(purpose.operationId)
                pluginReducer.fail(
                    purpose.operationId,
                    PluginOperationFailure.REMOTE_REJECTED,
                    retryable = true,
                )
            }
            is RequestPurpose.PluginList,
            is RequestPurpose.PluginRead,
            is RequestPurpose.PluginSkillConfig,
            is RequestPurpose.MarketplaceUpgrade,
            is RequestPurpose.PluginApps,
            is RequestPurpose.PluginSkills,
            -> pluginReducer.fail(
                operationId = purpose.pluginOperationId(),
                failure = PluginOperationFailure.REMOTE_REJECTED,
                retryable = true,
            )
            is RequestPurpose.PluginInstall -> {
                cancelPluginInstallDeadline(purpose.operationId)
                pluginReducer.fail(
                    operationId = purpose.operationId,
                    failure = PluginOperationFailure.REMOTE_REJECTED,
                    retryable = true,
                )
            }
            is RequestPurpose.PluginRuntimeInstall -> failRuntimePluginInstall(
                purpose.operationId,
                PluginOperationFailure.REMOTE_REJECTED,
                retryable = true,
            )
            is RequestPurpose.PluginRuntimeInstallProof -> {
                pluginReducer.fail(
                    purpose.proofOperationId,
                    PluginOperationFailure.REMOTE_REJECTED,
                    retryable = true,
                )
                failRuntimePluginInstall(
                    purpose.installOperationId,
                    PluginOperationFailure.REMOTE_REJECTED,
                    retryable = true,
                )
            }
        }
    }

    private fun handleEvent(eventSequence: Long, raw: String, frameBytes: Int): Boolean {
        // Observe only a native item envelope, never assistant/tool text imitating JSON.
        val envelope = JSONObject(raw)
        if (envelope.opt("method") in setOf("item/started", "item/completed")) {
            val params = envelope.optJSONObject("params")
            val threadId = params?.opt("threadId") as? String
            val turnId = params?.opt("turnId") as? String
            val item = params?.optJSONObject("item")
            if (threadId != null && threadId == reducer.snapshot().currentThreadId && turnId != null && item != null) {
                NativeNotificationExternalHistoryDecoder.item(threadId, turnId, item)?.let(::recordNativeNotificationReceipt)
            }
        }
        val decoded = AppServerEventDecoder.decode(raw)
        if (decoded is ServerEvent.ThreadTokenUsageUpdated) {
            // Startup measurements are superseded by the next correlated update. They may not
            // crowd out core replay events or establish ownership while state is being restored.
            if (rehydrating) return false
            val previous = reducer.currentThreadTokenUsage()
            reducer.apply(
                DeliveredServerEvent(
                    DeliveryCursor(checkNotNull(generation), eventSequence),
                    decoded,
                ),
            )
            return previous != reducer.currentThreadTokenUsage()
        }
        if (decoded is ServerEvent.Raw && decoded.method != "app/list/updated") return false
        if (rehydrating && decoded is ServerEvent.AccountUpdated && decoded.authMode == null &&
            remoteControl.snapshot.runtimeReady &&
            (!remoteControl.snapshot.isDisabledConfirmed ||
                remoteControl.snapshot.pendingOperation == ai.hans.standard.remotecontrol.RemoteControlOperation.ENABLE) &&
            requestPurposes.values.none { it is RequestPurpose.Logout }
        ) {
            // Optional remote access can be enabled while the thread is still bootstrapping.
            // Authentication revocation cannot wait in the timeline replay buffer in that case.
            applyEvent(eventSequence, decoded)
            return true
        }
        if (rehydrating && decoded is ServerEvent.AccountLoginCompleted) {
            // Authentication completion must unblock account/read even when a previous
            // thread bootstrap failed. Buffering it until thread readiness is circular.
            // Keep timeline replay gated; the correlated account/read remains the proof.
            val pendingLogin = reducer.snapshot().account.pendingLoginId ?: return false
            if (decoded.loginId != null && decoded.loginId != pendingLogin) return false
            applyEvent(eventSequence, decoded)
            return true
        }
        if (rehydrating) {
            if (decoded === ServerEvent.SkillsChanged) {
                // A startup burst is only an invalidation hint. Coalesce it.
                noteSkillsChanged()
                return true
            }
            if (decoded is ServerEvent.Raw) {
                // Unknown notifications are ignored by applyEvent. The sole
                // Raw notification we consume, app/list/updated, is only a
                // cache hint and is superseded by the explicit paginated
                // app/list bootstrap after rehydration. Never let a multi-MB
                // optional directory consume or overflow the core replay
                // buffer while account/thread state is being restored.
                if (decoded.method == "app/list/updated") {
                    protocolDiagnostics(
                        "Ignored optional event:app/list/updated during rehydration " +
                            "paramsBytes=" +
                            decoded.boundedParamsJson
                                .toByteArray(StandardCharsets.UTF_8)
                                .size,
                    )
                }
                return false
            }
            if (
                bufferedEvents.size >= MAX_BUFFERED_EVENTS ||
                bufferedEventBytes > MAX_BUFFERED_EVENT_BYTES - frameBytes
            ) {
                throw FrameLimitException("Rehydration event buffer exceeded")
            }
            bufferedEvents.addLast(BufferedEvent(eventSequence, decoded, frameBytes))
            bufferedEventBytes += frameBytes
            return true
        }
        applyEvent(eventSequence, decoded)
        return true
    }

    private fun applyEvent(eventSequence: Long, event: ServerEvent) {
        val activeGeneration = checkNotNull(generation)
        val previousThreadId = reducer.snapshot().currentThreadId
        val eventThreadId = when (event) {
            is ServerEvent.ThreadStarted -> event.thread.id
            is ServerEvent.ThreadStatusChanged -> event.threadId
            is ServerEvent.ThreadSettingsUpdated -> event.threadId
            is ServerEvent.ThreadTokenUsageUpdated -> event.threadId
            is ServerEvent.TurnStarted -> event.threadId
            is ServerEvent.TurnCompleted -> event.threadId
            is ServerEvent.ItemStarted -> event.threadId
            is ServerEvent.ItemCompleted -> event.threadId
            is ServerEvent.AgentMessageDelta -> event.threadId
            is ServerEvent.CommandOutputDelta -> event.threadId
            else -> null
        }
        // Recheck buffered events after restoration: they may have arrived before the local
        // thread was known. Foreign lifecycle already went to its independent authority.
        if (eventThreadId != null && eventThreadId != previousThreadId) return
        val previousAuthMode = reducer.snapshot().account.authMode
        val pendingLoginId = reducer.snapshot().account.pendingLoginId
        if (event is ServerEvent.AccountLoginCompleted &&
            (pendingLoginId == null || (event.loginId != null && event.loginId != pendingLoginId))
        ) return
        when (event) {
            is ServerEvent.AgentMessageDelta -> ensureTimelineOrder(
                timelineId(ClientTimelineRole.HANS, event.itemId),
            )
            is ServerEvent.CommandOutputDelta -> ensureTimelineOrder(
                timelineId(ClientTimelineRole.TOOL, event.itemId),
            )
            is ServerEvent.ItemStarted -> ensureTimelineOrder(
                timelineId(
                    if (event.item is ai.hans.standard.codex.StreamItem.AgentMessage) {
                        ClientTimelineRole.HANS
                    } else {
                        ClientTimelineRole.TOOL
                    },
                    event.item.id,
                ),
            )
            is ServerEvent.ItemCompleted -> ensureTimelineOrder(
                timelineId(
                    if (event.item is ai.hans.standard.codex.StreamItem.AgentMessage) {
                        ClientTimelineRole.HANS
                    } else {
                        ClientTimelineRole.TOOL
                    },
                    event.item.id,
                ),
            )
            else -> Unit
        }
        val beforePerformanceThread = if (performanceObserver !== PerformanceSessionObserver.NONE &&
            (event is ServerEvent.ItemStarted || event is ServerEvent.ItemCompleted)
        ) {
            reducer.snapshot().threads.singleOrNull { it.threadId == previousThreadId }
        } else null
        val performanceReduction = reducer.apply(
            DeliveredServerEvent(
                cursor = DeliveryCursor(activeGeneration, eventSequence),
                event = event,
            ),
        )
        val performanceEventApplied = performanceReduction.disposition == DeliveryDisposition.APPLIED &&
            !replayingPerformanceEvents && !rehydrating
        if (performanceEventApplied) {
            when (event) {
                is ServerEvent.TurnStarted -> {
                    val projected = performanceReduction.snapshot.threads
                        .singleOrNull { it.threadId == previousThreadId }?.currentTurn
                    if (projected?.turnId == event.turn.id && projected.status == TurnStatus.IN_PROGRESS) {
                        performanceTurnStarted(event.turn.id)
                    }
                }
                is ServerEvent.TurnCompleted -> if (event.turn.status != TurnStatus.IN_PROGRESS) {
                    performanceTurnCompleted(event.turn.id)
                }
                is ServerEvent.ThreadStatusChanged -> {
                    performanceUserWaitReported = event.status.activeFlags.isNotEmpty() &&
                        event.status.status == ai.hans.standard.codex.ThreadRuntimeStatus.ACTIVE
                }
                else -> Unit
            }
            val itemId = when (event) {
                is ServerEvent.ItemStarted -> event.item.id
                is ServerEvent.ItemCompleted -> event.item.id
                else -> null
            }
            val itemTurnId = when (event) {
                is ServerEvent.ItemStarted -> event.turnId
                is ServerEvent.ItemCompleted -> event.turnId
                else -> null
            }
            if (itemId != null && itemTurnId == performanceObservedTurnId
            ) {
                val before = beforePerformanceThread?.tools?.singleOrNull { it.itemId == itemId }
                val after = performanceReduction.snapshot.threads
                    .singleOrNull { it.threadId == previousThreadId }?.tools
                    ?.singleOrNull { it.itemId == itemId }
                if (event is ServerEvent.ItemStarted && before == null && after?.complete == false) {
                    performanceEvent(PerformanceEvent.SERVER_ITEM_STARTED)
                } else if (event is ServerEvent.ItemCompleted && before?.complete == false && after?.complete == true) {
                    performanceEvent(PerformanceEvent.SERVER_ITEM_COMPLETED)
                }
            }
            val outputTurn = when (event) {
                is ServerEvent.AgentMessageDelta -> event.turnId.takeIf { event.delta.isNotEmpty() }
                is ServerEvent.ItemStarted -> event.turnId.takeIf {
                    (event.item as? ai.hans.standard.codex.StreamItem.AgentMessage)?.text?.isNotEmpty() == true
                }
                is ServerEvent.ItemCompleted -> event.turnId.takeIf {
                    (event.item as? ai.hans.standard.codex.StreamItem.AgentMessage)?.text?.isNotEmpty() == true
                }
                else -> null
            }
            if (!performanceAssistantOutputObserved && outputTurn != null &&
                outputTurn == performanceObservedTurnId
            ) {
                performanceAssistantOutputObserved = true
                performanceEvent(PerformanceEvent.FIRST_ASSISTANT_OUTPUT)
            }
        }
        if (previousThreadId != reducer.snapshot().currentThreadId) {
            invalidateRealtime()
            invalidateSelectionContext(invalidateActiveProof = true)
        }
        when (event) {
            is ServerEvent.ThreadSettingsUpdated -> acceptSettingsUpdateEvent(eventSequence, event)
            is ServerEvent.AccountLoginCompleted -> {
                invalidateSelectionContext(invalidateActiveProof = true)
                if (event.success) {
                    deviceCodeLogin = null
                    modelCatalogRefreshAfterAccountRead = true
                    accountReadComplete = false
                    refreshAccount()
                } else {
                    sessionPhase = ClientSessionPhase.AUTH_REQUIRED
                    setProblem(ClientProblemCode.AUTHENTICATION, retryable = true)
                }
            }
            is ServerEvent.AccountUpdated -> {
                if (event.authMode != previousAuthMode || (event.authMode != null) != signedIn) {
                    invalidateRealtime()
                    invalidateSelectionContext(invalidateActiveProof = true)
                }
                val wasSignedIn = signedIn
                signedIn = event.authMode != null
                val authenticationChanged = signedIn != wasSignedIn
                val confirmsPendingLogin = signedIn && pendingLoginId != null
                if (signedIn && (authenticationChanged || confirmsPendingLogin)) {
                    modelCatalogRefreshAfterAccountRead = true
                    accountReadComplete = false
                }
                if (!signedIn) {
                    sessionPhase = ClientSessionPhase.AUTH_REQUIRED
                    deviceCodeLogin = null
                    invalidateBundledSetupBootstrap()
                    if (revokeRemoteControlOnAuthenticationLoss()) return
                }
                // account/read may itself cause account/updated after a token
                // refresh. Only a real authentication-state transition needs
                // another read; otherwise this becomes a feedback loop.
                if (authenticationChanged || !accountReadComplete) refreshAccount()
            }
            is ServerEvent.TurnStarted -> {
                // A stale event must not bind a new pending dispatch (or setup receipt) to a
                // finished turn. The reducer separately preserves its terminal projection.
                if (event.turn.id in terminalTurnIds) return
                if (locallyStoppingDynamicTurn == (event.threadId to event.turn.id)) {
                    activeTurn = null
                    identityOnlyActiveTurn = null
                    unknownActiveTurnId = event.turn.id
                    migrationEffectiveSelection = null
                    updateSessionPhase()
                    return
                }
                val pending = pendingDispatch
                val nativeNotificationOrigin = event.turn.id in nativeNotificationTurnIds
                if (pending == null && activeTurn == null &&
                    event.threadId == reducer.snapshot().currentThreadId &&
                    identityOnlyActiveTurn != event.threadId to event.turn.id
                ) {
                    val localVoiceTurn = performanceEventApplied && signedIn && realtimeOriginAccount != null &&
                        realtimeOriginAccount == reducer.snapshot().account.identity &&
                        desktopRelayIsQuiescent() &&
                        realtime.claimTurnOrigin(generation, event.threadId, event.turn.id)
                    if (localVoiceTurn) {
                        rememberLocalVoiceControlTurn(event.threadId, event.turn.id)
                        reducer.snapshot().account.identity?.let { realtimeTurnAccounts[event.turn.id] = it }
                        while (realtimeTurnAccounts.size > 128) realtimeTurnAccounts.remove(realtimeTurnAccounts.keys.first())
                    } else if (!nativeNotificationOrigin) {
                        if (pendingNativeNotifications.values.any { it.threadId == event.threadId }) {
                            // Shared thread/timing is not origin proof. No tools before an exact receipt.
                            nativeNotificationUnprovenTurnIds.add(event.turn.id)
                        } else remoteControlledTurnIds.add(event.turn.id)
                    }
                    // The event proves identity, not model/effort. Local speech may steer this
                    // same observed turn without inventing effective dispatch preferences.
                    if (localVoiceTurn || nativeNotificationOrigin || remoteControl.snapshot.mayUsePhoneToolsRemotely) {
                        identityOnlyActiveTurn = event.threadId to event.turn.id
                    }
                    while (remoteControlledTurnIds.size > 128) {
                        remoteControlledTurnIds.remove(remoteControlledTurnIds.first())
                    }
                }
                pending?.let {
                    dynamicToolTurnAuthorizationGate.bindDispatch(
                        it.clientMessageId,
                        event.threadId,
                        event.turn.id,
                    )
                    if (performanceEventApplied && !it.clientMessageId.startsWith(SETUP_CLIENT_MESSAGE_PREFIX)) {
                        rememberLocalVoiceControlTurn(event.threadId, event.turn.id)
                    }
                }
                if (pending?.clientMessageId?.startsWith(SETUP_CLIENT_MESSAGE_PREFIX) == true) {
                    // App Server may emit turn/started and the first command item before replying
                    // to turn/start. This event is an authoritative acceptance receipt: settle the
                    // pending setup send now so neither the UI nor its deadline waits forever.
                    markDispatchSent(
                        messageId = pending.clientMessageId,
                        selection = pending.selection,
                        turnId = event.turn.id,
                        confirmsDefaultSelection = pending.confirmsDefaultSelection,
                    )
                }
                if (event.turn.status == TurnStatus.IN_PROGRESS) {
                    // turn/started can arrive after the correlated turn/start response, or be
                    // repeated while a steer is in flight. Never downgrade the proven options
                    // for that exact turn to unknown (or to an unrelated pending selection).
                    val confirmedActive = activeTurn?.takeIf {
                        it.threadId == event.threadId && it.turnId == event.turn.id
                    }
                    val options = confirmedActive?.effectiveOptions ?: pending?.options
                    if (options == null) {
                        activeTurn = null
                        unknownActiveTurnId = event.turn.id
                        if (identityOnlyActiveTurn != event.threadId to event.turn.id) {
                            identityOnlyActiveTurn = null
                        }
                        migrationEffectiveSelection = null
                    } else {
                        activeTurn = ActiveTurn(event.threadId, event.turn.id, options)
                        unknownActiveTurnId = null
                        identityOnlyActiveTurn = null
                    }
                }
                updateSessionPhase()
            }
            is ServerEvent.TurnCompleted -> {
                realtime.completeVoiceTurn(generation, event.threadId, event.turn.id)
                voiceControlTurnProofs.remove(event.turn.id)
                realtimeTurnAccounts.remove(event.turn.id)
                remoteControlledTurnIds.remove(event.turn.id)
                if (unknownActiveTurnId == event.turn.id) {
                    migrationEffectiveSelection = provenThreadSettingsSelection
                }
                if (settingsDefaultActiveTurn == (event.threadId to event.turn.id)) {
                    settingsDefaultActiveTurn = null
                    migrationEffectiveSelection = confirmedSelection
                }
                dynamicToolTurnAuthorizationGate.completeTurn(event.threadId, event.turn.id)
                rememberTerminalTurn(event.threadId, event.turn.id, event.turn.status)
                abandonPendingDynamicTurn(event.threadId, event.turn.id)
                if (activeTurn?.turnId == event.turn.id) activeTurn = null
                if (unknownActiveTurnId == event.turn.id) unknownActiveTurnId = null
                updateSessionPhase()
                // A turn may have installed or generated a plugin/skill through shell or a
                // marketplace helper rather than Hans's own plugin/install button. Force a
                // bounded post-turn refresh so the launcher reflects that change automatically.
                refreshCapabilitiesAfterMutation()
                if (performanceEventApplied) realtime.workState(generation, event.threadId,
                    activeTurn?.turnId ?: unknownActiveTurnId, pendingDispatch != null,
                    ai.hans.standard.voice.realtime.CodexTaskVoiceTerminal(event.turn.id,
                        when (event.turn.status) {
                            TurnStatus.COMPLETED -> ai.hans.standard.voice.realtime.CodexTaskVoiceWorkOutcome.COMPLETED
                            TurnStatus.INTERRUPTED -> ai.hans.standard.voice.realtime.CodexTaskVoiceWorkOutcome.INTERRUPTED
                            else -> ai.hans.standard.voice.realtime.CodexTaskVoiceWorkOutcome.FAILED
                        }))
            }
            ServerEvent.SkillsChanged -> {
                noteSkillsChanged()
            }
            is ServerEvent.Raw -> if (event.method == "app/list/updated") noteAppsChanged()
            else -> Unit
        }
    }

    private fun maybeBootstrapThread() {
        if (!accountReadComplete || !signedIn || catalog == null) return
        if (threadReady) {
            if (sessionPhase == ClientSessionPhase.LOGIN_PENDING) {
                sessionPhase = ClientSessionPhase.READY
                updateSessionPhase()
            }
            generation?.let(::bootstrapPluginDomain)
            return
        }
        if (threadBootstrapRequested) {
            // Successful same-account reauthentication restores the existing bootstrap's
            // phase, not its request. In particular, do not create a second fresh thread.
            // A changed account cannot adopt the old fresh-thread proof.
            if (sessionPhase == ClientSessionPhase.LOGIN_PENDING && requestPurposes.values.any { purpose ->
                when (purpose) {
                    is RequestPurpose.ThreadStart -> freshBootstrapAccountMatches(purpose.proof)
                    is RequestPurpose.ThreadMaterialize -> freshBootstrapAccountMatches(purpose.proof)
                    is RequestPurpose.ThreadMemoryEnable -> purpose.freshProof?.let(::freshBootstrapAccountMatches) ?: true
                    is RequestPurpose.ThreadResume -> true
                    else -> false
                }
            }) {
                sessionPhase = ClientSessionPhase.RECOVERING_THREAD
            }
            return
        }
        threadBootstrapRequested = true
        sessionPhase = ClientSessionPhase.RECOVERING_THREAD
        val storedThread = try {
            sessionStore.readThreadId()
        } catch (_: Exception) {
            failSession(ClientProblemCode.LOCAL_PERSISTENCE, retryable = true)
            return
        }
        val instructions = try {
            developerInstructions.currentInstructions()
        } catch (_: Exception) {
            failSession(ClientProblemCode.LOCAL_PERSISTENCE, retryable = true)
            return
        }
        if (storedThread == null) {
            requestThreadStart(instructions)
        } else {
            transmitThreadBootstrap(RequestPurpose.ThreadResume(storedThread)) {
                AppServerRequests.threadResume(
                    id = requestIds.next(),
                    threadId = storedThread,
                    excludeTurns = true,
                    developerInstructions = instructions,
                    disablePhoneToolsMcp = phoneToolsMcpConfigured,
                )
            }
        }
    }

    private fun requestThreadStart(preloadedInstructions: String? = null) {
        val selected = readConfirmedSelection()
        val options = selected.toOptions(sessionStore.workspacePath)
        val currentCatalog = catalog
        if (currentCatalog == null) {
            failSession(ClientProblemCode.MODEL_CATALOG, retryable = true)
            return
        }
        try {
            currentCatalog.requireSupported(options)
        } catch (_: Exception) {
            failSession(ClientProblemCode.MODEL_CATALOG, retryable = true)
            return
        }
        val instructions = preloadedInstructions ?: try {
            developerInstructions.currentInstructions()
        } catch (_: Exception) {
            failSession(ClientProblemCode.LOCAL_PERSISTENCE, retryable = true)
            return
        }
        val proof = FreshThreadBootstrapProof(
            generation = generation ?: return,
            account = reducer.snapshot().account.identity ?: return,
        )
        transmitThreadBootstrap(RequestPurpose.ThreadStart(proof)) {
            AppServerRequests.threadStart(
                id = requestIds.next(),
                options = options,
                developerInstructions = instructions,
                dynamicTools = dynamicToolExecutor?.specs ?: emptyList(),
                disablePhoneToolsMcp = phoneToolsMcpConfigured,
            )
        }
    }

    /**
     * A process-level feature flag only changes the default for new Codex threads. The pinned
     * runtime intentionally preserves an existing thread's stored memory mode on resume, so an
     * acknowledged per-thread update is the in-place migration receipt for upgraded Hans users.
     */
    private fun requestPersonalThreadMemoryEnable(
        threadId: String,
        freshProof: FreshThreadBootstrapProof? = null,
    ) {
        sessionPhase = ClientSessionPhase.RECOVERING_THREAD
        transmitThreadBootstrap(RequestPurpose.ThreadMemoryEnable(threadId, freshProof)) {
            AppServerRequests.threadMemoryModeSetEnabled(
                id = requestIds.next(),
                threadId = threadId,
            )
        }
    }

    private data class FreshThreadBootstrapProof(val generation: Long, val account: AccountIdentity)

    private fun freshBootstrapAccountMatches(proof: FreshThreadBootstrapProof): Boolean =
        proof.generation == generation && proof.account == reducer.snapshot().account.identity &&
            signedIn && accountReadComplete

    private fun freshBootstrapContextMatches(proof: FreshThreadBootstrapProof, threadId: String): Boolean =
        freshBootstrapAccountMatches(proof) && runtimePhase == ClientRuntimePhase.READY &&
            sessionPhase == ClientSessionPhase.RECOVERING_THREAD && !threadReady &&
            reducer.snapshot().currentThreadId == threadId

    /** Keep local request rejection separate from remote rejection and ambiguous transport. */
    private fun transmitThreadBootstrap(
        purpose: RequestPurpose,
        encode: () -> EncodedRequest,
    ) {
        val request = try {
            encode()
        } catch (error: Exception) {
            recordThreadBootstrapFailure(purpose, BootstrapFailureStage.LOCAL_ENCODING, local = error)
            failSession(ClientProblemCode.THREAD_RECOVERY, retryable = true)
            return
        }
        try {
            val freshBootstrap = purpose.isFreshThreadBootstrap()
            val sent = transmitAttempt(request, purpose, restartOnAmbiguity = !freshBootstrap)
            if (sent == RequestTransmissionResult.REJECTED_BEFORE_TRANSPORT) {
                recordThreadBootstrapFailure(purpose, BootstrapFailureStage.TRANSPORT_UNAVAILABLE)
                failSession(ClientProblemCode.THREAD_RECOVERY, retryable = true)
            } else if (freshBootstrap && sent == RequestTransmissionResult.OUTCOME_AMBIGUOUS) {
                // Do not automatically create another thread after an ambiguous fresh bootstrap.
                failSession(ClientProblemCode.THREAD_RECOVERY, retryable = true)
            }
        } catch (error: Exception) {
            recordThreadBootstrapFailure(purpose, BootstrapFailureStage.LOCAL_REGISTRATION, local = error)
            failSession(ClientProblemCode.THREAD_RECOVERY, retryable = true)
        }
    }

    /** Fixed vocabulary only: never include messages, exception class names, IDs or error data. */
    private fun recordThreadBootstrapFailure(
        purpose: RequestPurpose,
        stage: BootstrapFailureStage,
        remote: RemoteError? = null,
        local: Exception? = null,
    ) {
        val boundary = when (purpose) {
            is RequestPurpose.ThreadStart -> "thread_start"
            is RequestPurpose.ThreadResume -> "thread_resume"
            is RequestPurpose.ThreadMemoryEnable -> "thread_memory_enable"
            is RequestPurpose.ThreadMaterialize -> "thread_materialize"
            else -> return
        }
        val reason = when (local) {
            is FrameLimitException -> "frame_limit"
            is CrossCorrelationException -> "cross_correlation"
            is IllegalArgumentException -> when (local.message) {
                "Too many dynamic tool namespaces" -> "dynamic_namespace_limit"
                "Too many dynamic tool functions" -> "dynamic_function_limit"
                "Duplicate dynamic tool namespace" -> "dynamic_namespace_duplicate"
                else -> "invalid_request"
            }
            is IllegalStateException -> "invalid_state"
            else -> ThreadBootstrapRemoteFailureClassifier.classify(
                remote,
                expectedThreadId = when (purpose) {
                    is RequestPurpose.ThreadResume -> purpose.threadId
                    is RequestPurpose.ThreadMemoryEnable -> purpose.threadId
                    is RequestPurpose.ThreadMaterialize -> purpose.threadId
                    else -> null
                },
            ).diagnosticName
        }
        protocolDiagnostics(buildString {
            append("Thread bootstrap failure boundary=").append(boundary)
            append(" stage=").append(stage.diagnosticName)
            append(" reason=").append(reason)
            remote?.let { append(" rpc_code=").append(it.code) }
        })
    }

    private enum class BootstrapFailureStage(val diagnosticName: String) {
        LOCAL_ENCODING("local_encoding"),
        LOCAL_REGISTRATION("local_registration"),
        TRANSPORT_UNAVAILABLE("transport_unavailable"),
        TRANSPORT_OUTCOME_AMBIGUOUS("transport_outcome_ambiguous"),
        REMOTE_REJECTION("remote_rejection"),
        INVALID_RECEIPT("invalid_receipt"),
    }

    private fun requestModelPage(cursor: String?): Boolean =
        transmit(
            AppServerRequests.modelList(requestIds.next(), cursor = cursor, includeHidden = true),
            RequestPurpose.ModelList,
        )

    private fun refreshModelCatalogAfterAuthentication() {
        // A pre-login catalog can be complete yet describe another authentication state.
        // Finish any old in-flight page without publishing it, then restart at page one.
        modelPages = ModelListAccumulator()
        catalog = null
        modelCatalogSettingsRefreshInFlight = false
        modelCatalogRefreshQueued = true
        startQueuedModelCatalogRefresh()
    }

    private fun startQueuedModelCatalogRefresh() {
        if (!modelCatalogRefreshQueued || !accountReadComplete || !signedIn) return
        if (requestPurposes.values.any { it is RequestPurpose.ModelList }) return
        modelCatalogRefreshQueued = false
        requestModelPage(null)
    }

    /**
     * Startup recovery is a hard mutation gate. Durable claims are acquired and reconciled on the
     * preparation executor; the controller monitor only sequences App Server proof requests.
     */
    private fun startPluginRecoveryBeforeBootstrap(activeGeneration: Long) {
        val transactions = pluginInstallTransactions
        if (transactions == null) {
            startPluginUninstallRecoveryBeforeBootstrap(
                activeGeneration = activeGeneration,
                installRecoveryBlocked = false,
            )
            return
        }
        if (pluginBootstrappedGeneration == activeGeneration || pluginRecoveryGateInProgress) return

        val epoch = PluginRecoveryEpoch(
            generation = activeGeneration,
            batchId = nextPluginRecoveryId("batch"),
        )
        pluginRecoveryEpoch = epoch
        pluginRecoveryBatch = null
        pluginRecoveryGateInProgress = true
        pluginMutationsBlockedByRecovery = true
        val scheduled = runCatching {
            pluginPreparationExecutor.execute {
                val outcome = synchronized(pluginRecoveryExecutionLock) {
                    if (epoch.cancelled) {
                        PluginRecoveryClaimOutcome.Cancelled
                    } else {
                        runCatching(transactions::claimRecoveryBatch).fold(
                            onSuccess = PluginRecoveryClaimOutcome::Loaded,
                            onFailure = { PluginRecoveryClaimOutcome.Failed },
                        )
                    }
                }
                finishPluginRecoveryClaimLoad(epoch, outcome)
            }
        }.isSuccess
        if (!scheduled) {
            protocolDiagnostics("Plugin install recovery claim could not be scheduled")
            finishPluginRecoveryGate(epoch, blocked = true)
        }
    }

    @Synchronized
    private fun finishPluginRecoveryClaimLoad(
        epoch: PluginRecoveryEpoch,
        outcome: PluginRecoveryClaimOutcome,
    ) {
        if (!isCurrentPluginRecoveryEpoch(epoch)) return
        when (outcome) {
            PluginRecoveryClaimOutcome.Cancelled -> return
            PluginRecoveryClaimOutcome.Failed -> {
                protocolDiagnostics("Plugin install recovery journal is unavailable")
                finishPluginRecoveryGate(epoch, blocked = true)
            }
            is PluginRecoveryClaimOutcome.Loaded -> {
                if (outcome.claims.isEmpty()) {
                    finishPluginRecoveryGate(epoch, blocked = false)
                    return
                }
                val claimsByOperation = outcome.claims.associateBy { it.operationId }
                if (
                    claimsByOperation.size != outcome.claims.size ||
                    outcome.claims.any { it.operationId.isBlank() || it.pluginId.isBlank() }
                ) {
                    protocolDiagnostics("Plugin install recovery claims were malformed")
                    finishPluginRecoveryGate(epoch, blocked = true)
                    return
                }
                val proofOperationId = nextPluginRecoveryId("initial-proof")
                pluginRecoveryBatch = PendingPluginRecoveryBatch(
                    epoch = epoch,
                    claims = outcome.claims,
                    expectedProofOperationId = proofOperationId,
                    stage = PluginRecoveryStage.AWAITING_INITIAL_LIST,
                )
                transmit(
                    PluginAppServerRequests.pluginList(
                        id = requestIds.next(),
                        workspacePath = sessionStore.workspacePath,
                        forceRefetch = true,
                    ),
                    RequestPurpose.PluginInstallRecoveryList(
                        batchId = epoch.batchId,
                        proofOperationId = proofOperationId,
                    ),
                )
            }
        }
    }

    private fun handlePluginRecoveryList(
        purpose: RequestPurpose.PluginInstallRecoveryList,
        listed: PluginListWireResult,
    ) {
        val batch = pluginRecoveryBatch ?: return
        if (
            !isCurrentPluginRecoveryEpoch(batch.epoch) ||
            batch.epoch.batchId != purpose.batchId ||
            batch.stage != PluginRecoveryStage.AWAITING_INITIAL_LIST ||
            batch.expectedProofOperationId != purpose.proofOperationId
        ) {
            failPluginRecoveryBatch(purpose.batchId, "recovery_list_correlation_changed")
            return
        }
        batch.stage = PluginRecoveryStage.EVALUATING_INITIAL_LIST
        batch.expectedProofOperationId = null
        val transactions = checkNotNull(pluginInstallTransactions)
        val epoch = batch.epoch
        val scheduled = runCatching {
            pluginPreparationExecutor.execute {
                val outcome = synchronized(pluginRecoveryExecutionLock) {
                    if (epoch.cancelled) {
                        PluginRecoveryEvaluationOutcome.Cancelled
                    } else {
                        PluginRecoveryEvaluationOutcome.Completed(
                            batch.claims.map { claim ->
                                PluginRecoveryEvaluation(
                                    claim = claim,
                                    execution = runCatching {
                                        transactions.recoverClaim(claim.operationId, listed)
                                    }.getOrNull(),
                                )
                            },
                        )
                    }
                }
                finishPluginRecoveryInitialEvaluation(epoch, outcome)
            }
        }.isSuccess
        if (!scheduled) {
            failPluginRecoveryBatch(purpose.batchId, "recovery_evaluation_schedule_failed")
        }
    }

    @Synchronized
    private fun finishPluginRecoveryInitialEvaluation(
        epoch: PluginRecoveryEpoch,
        outcome: PluginRecoveryEvaluationOutcome,
    ) {
        if (!isCurrentPluginRecoveryEpoch(epoch)) return
        val batch = pluginRecoveryBatch ?: return
        if (batch.stage != PluginRecoveryStage.EVALUATING_INITIAL_LIST) return
        when (outcome) {
            PluginRecoveryEvaluationOutcome.Cancelled -> return
            is PluginRecoveryEvaluationOutcome.Completed -> {
                val compensations = mutableListOf<PluginInstallRecoveryExecution.CompensationRequired>()
                outcome.evaluations.forEach { evaluated ->
                    when (val execution = evaluated.execution) {
                        PluginInstallRecoveryExecution.Finalized -> Unit
                        is PluginInstallRecoveryExecution.CompensationRequired -> {
                            if (
                                execution.operationId == evaluated.claim.operationId &&
                                execution.pluginId == evaluated.claim.pluginId
                            ) {
                                compensations += execution
                            } else {
                                batch.blocked = true
                            }
                        }
                        is PluginInstallRecoveryExecution.Deferred,
                        is PluginInstallRecoveryExecution.Quarantined,
                        null,
                        -> batch.blocked = true
                    }
                }
                val duplicatePluginIds = compensations.groupingBy { it.pluginId }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys
                compensations.filterNot { it.pluginId in duplicatePluginIds }
                    .forEach(batch.pendingCompensations::addLast)
                if (duplicatePluginIds.isNotEmpty()) batch.blocked = true
                startNextPluginRecoveryCompensation(batch)
            }
        }
    }

    private fun startNextPluginRecoveryCompensation(batch: PendingPluginRecoveryBatch) {
        if (!isCurrentPluginRecoveryEpoch(batch.epoch)) return
        val compensation = if (batch.pendingCompensations.isEmpty()) {
            null
        } else {
            batch.pendingCompensations.removeFirst()
        }
        if (compensation == null) {
            finishPluginRecoveryGate(batch.epoch, batch.blocked)
            return
        }
        batch.activeCompensation = compensation
        batch.stage = PluginRecoveryStage.AWAITING_UNINSTALL
        transmit(
            PluginAppServerRequests.pluginUninstall(
                id = requestIds.next(),
                pluginId = compensation.pluginId,
            ),
            RequestPurpose.PluginInstallRecoveryUninstall(
                operationId = compensation.operationId,
                pluginId = compensation.pluginId,
            ),
        )
    }

    private fun handlePluginRecoveryUninstallAccepted(
        purpose: RequestPurpose.PluginInstallRecoveryUninstall,
    ) {
        val batch = pluginRecoveryBatch ?: return
        val active = batch.activeCompensation
        if (
            !isCurrentPluginRecoveryEpoch(batch.epoch) ||
            batch.stage != PluginRecoveryStage.AWAITING_UNINSTALL ||
            active == null ||
            active.operationId != purpose.operationId ||
            active.pluginId != purpose.pluginId
        ) {
            failPluginRecoveryCompensation(
                purpose.operationId,
                "recovery_uninstall_correlation_changed",
            )
            return
        }
        batch.stage = PluginRecoveryStage.MARKING_UNINSTALL_ACCEPTED
        val transactions = checkNotNull(pluginInstallTransactions)
        val epoch = batch.epoch
        val scheduled = runCatching {
            pluginPreparationExecutor.execute {
                val outcome = synchronized(pluginRecoveryExecutionLock) {
                    if (epoch.cancelled) {
                        PluginRecoveryMutationOutcome.Cancelled
                    } else {
                        runCatching {
                            transactions.markRecoveryCompensationAccepted(purpose.operationId)
                        }.fold(
                            onSuccess = { PluginRecoveryMutationOutcome.Applied },
                            onFailure = { PluginRecoveryMutationOutcome.Failed },
                        )
                    }
                }
                finishPluginRecoveryUninstallAcceptance(epoch, purpose.operationId, outcome)
            }
        }.isSuccess
        if (!scheduled) {
            failPluginRecoveryCompensation(
                purpose.operationId,
                "recovery_uninstall_acceptance_schedule_failed",
            )
        }
    }

    @Synchronized
    private fun finishPluginRecoveryUninstallAcceptance(
        epoch: PluginRecoveryEpoch,
        operationId: String,
        outcome: PluginRecoveryMutationOutcome,
    ) {
        if (!isCurrentPluginRecoveryEpoch(epoch)) return
        val batch = pluginRecoveryBatch ?: return
        if (
            batch.stage != PluginRecoveryStage.MARKING_UNINSTALL_ACCEPTED ||
            batch.activeCompensation?.operationId != operationId
        ) return
        when (outcome) {
            PluginRecoveryMutationOutcome.Cancelled -> return
            PluginRecoveryMutationOutcome.Failed -> failPluginRecoveryCompensation(
                operationId,
                "recovery_uninstall_acceptance_failed",
            )
            PluginRecoveryMutationOutcome.Applied -> {
                val proofOperationId = nextPluginRecoveryId("absence-proof")
                batch.expectedProofOperationId = proofOperationId
                batch.stage = PluginRecoveryStage.AWAITING_ABSENCE_PROOF
                transmit(
                    PluginAppServerRequests.pluginList(
                        id = requestIds.next(),
                        workspacePath = sessionStore.workspacePath,
                        forceRefetch = true,
                    ),
                    RequestPurpose.PluginInstallRecoveryAbsenceProof(
                        operationId = operationId,
                        proofOperationId = proofOperationId,
                    ),
                )
            }
        }
    }

    private fun handlePluginRecoveryAbsenceProof(
        purpose: RequestPurpose.PluginInstallRecoveryAbsenceProof,
        listed: PluginListWireResult,
    ) {
        val batch = pluginRecoveryBatch ?: return
        if (
            !isCurrentPluginRecoveryEpoch(batch.epoch) ||
            batch.stage != PluginRecoveryStage.AWAITING_ABSENCE_PROOF ||
            batch.activeCompensation?.operationId != purpose.operationId ||
            batch.expectedProofOperationId != purpose.proofOperationId
        ) {
            failPluginRecoveryCompensation(
                purpose.operationId,
                "recovery_absence_proof_correlation_changed",
            )
            return
        }
        batch.expectedProofOperationId = null
        batch.stage = PluginRecoveryStage.EVALUATING_ABSENCE_PROOF
        val transactions = checkNotNull(pluginInstallTransactions)
        val epoch = batch.epoch
        val scheduled = runCatching {
            pluginPreparationExecutor.execute {
                val outcome = synchronized(pluginRecoveryExecutionLock) {
                    if (epoch.cancelled) {
                        PluginRecoverySingleEvaluationOutcome.Cancelled
                    } else {
                        PluginRecoverySingleEvaluationOutcome.Completed(
                            runCatching {
                                transactions.recoverClaim(purpose.operationId, listed)
                            }.getOrNull(),
                        )
                    }
                }
                finishPluginRecoveryAbsenceEvaluation(epoch, purpose.operationId, outcome)
            }
        }.isSuccess
        if (!scheduled) {
            failPluginRecoveryCompensation(
                purpose.operationId,
                "recovery_absence_evaluation_schedule_failed",
            )
        }
    }

    @Synchronized
    private fun finishPluginRecoveryAbsenceEvaluation(
        epoch: PluginRecoveryEpoch,
        operationId: String,
        outcome: PluginRecoverySingleEvaluationOutcome,
    ) {
        if (!isCurrentPluginRecoveryEpoch(epoch)) return
        val batch = pluginRecoveryBatch ?: return
        if (
            batch.stage != PluginRecoveryStage.EVALUATING_ABSENCE_PROOF ||
            batch.activeCompensation?.operationId != operationId
        ) return
        when (outcome) {
            PluginRecoverySingleEvaluationOutcome.Cancelled -> return
            is PluginRecoverySingleEvaluationOutcome.Completed -> {
                if (outcome.execution !== PluginInstallRecoveryExecution.Finalized) {
                    batch.blocked = true
                }
                batch.activeCompensation = null
                startNextPluginRecoveryCompensation(batch)
            }
        }
    }

    private fun failPluginRecoveryBatch(batchId: String, reason: String) {
        val batch = pluginRecoveryBatch ?: return
        if (batch.epoch.batchId != batchId || !isCurrentPluginRecoveryEpoch(batch.epoch)) return
        protocolDiagnostics("Plugin install recovery deferred: $reason")
        finishPluginRecoveryGate(batch.epoch, blocked = true)
    }

    private fun failPluginRecoveryCompensation(operationId: String, reason: String) {
        val batch = pluginRecoveryBatch ?: return
        if (
            !isCurrentPluginRecoveryEpoch(batch.epoch) ||
            batch.activeCompensation?.operationId != operationId
        ) return
        protocolDiagnostics("Plugin install recovery compensation deferred: $reason")
        batch.blocked = true
        batch.activeCompensation = null
        batch.expectedProofOperationId = null
        startNextPluginRecoveryCompensation(batch)
    }

    private fun finishPluginRecoveryGate(epoch: PluginRecoveryEpoch, blocked: Boolean) {
        if (!isCurrentPluginRecoveryEpoch(epoch)) return
        synchronized(pluginRecoveryExecutionLock) { epoch.cancelled = true }
        pluginRecoveryEpoch = null
        pluginRecoveryBatch = null
        startPluginUninstallRecoveryBeforeBootstrap(epoch.generation, blocked)
    }

    private fun startPluginUninstallRecoveryBeforeBootstrap(
        activeGeneration: Long,
        installRecoveryBlocked: Boolean,
    ) {
        val transactions = pluginUninstallTransactions
        if (transactions == null) {
            finishCombinedPluginRecoveryGate(activeGeneration, installRecoveryBlocked)
            return
        }
        if (pluginUninstallRecoveryEpoch != null || generation != activeGeneration) return
        val epoch = PluginUninstallRecoveryEpoch(
            generation = activeGeneration,
            batchId = nextPluginRecoveryId("uninstall-batch"),
            installRecoveryBlocked = installRecoveryBlocked,
        )
        pluginUninstallRecoveryEpoch = epoch
        pluginUninstallRecoveryBatch = null
        pluginRecoveryGateInProgress = true
        pluginMutationsBlockedByRecovery = true
        val scheduled = runCatching {
            pluginPreparationExecutor.execute {
                val outcome = synchronized(pluginRecoveryExecutionLock) {
                    if (epoch.cancelled) {
                        PluginUninstallRecoveryClaimOutcome.Cancelled
                    } else {
                        runCatching(transactions::claimRecoveryBatch).fold(
                            onSuccess = PluginUninstallRecoveryClaimOutcome::Loaded,
                            onFailure = { PluginUninstallRecoveryClaimOutcome.Failed },
                        )
                    }
                }
                finishPluginUninstallRecoveryClaimLoad(epoch, outcome)
            }
        }.isSuccess
        if (!scheduled) {
            protocolDiagnostics("Plugin uninstall recovery claim could not be scheduled")
            finishPluginUninstallRecoveryGate(epoch, blocked = true)
        }
    }

    @Synchronized
    private fun finishPluginUninstallRecoveryClaimLoad(
        epoch: PluginUninstallRecoveryEpoch,
        outcome: PluginUninstallRecoveryClaimOutcome,
    ) {
        if (!isCurrentPluginUninstallRecoveryEpoch(epoch)) return
        when (outcome) {
            PluginUninstallRecoveryClaimOutcome.Cancelled -> return
            PluginUninstallRecoveryClaimOutcome.Failed -> {
                protocolDiagnostics("Plugin uninstall recovery journal is unavailable")
                finishPluginUninstallRecoveryGate(epoch, blocked = true)
            }
            is PluginUninstallRecoveryClaimOutcome.Loaded -> {
                val claims = outcome.claims
                if (claims.isEmpty()) {
                    finishPluginUninstallRecoveryGate(epoch, blocked = false)
                    return
                }
                if (claims.map { it.operationId }.distinct().size != claims.size ||
                    claims.any { it.operationId.isBlank() || it.pluginId.isBlank() }
                ) {
                    protocolDiagnostics("Plugin uninstall recovery claims were malformed")
                    finishPluginUninstallRecoveryGate(epoch, blocked = true)
                    return
                }
                pluginUninstallRecoveryBatch = PendingPluginUninstallRecoveryBatch(epoch, claims)
                val transmitted = transmit(
                    PluginAppServerRequests.pluginList(
                        id = requestIds.next(),
                        workspacePath = sessionStore.workspacePath,
                        forceRefetch = true,
                    ),
                    RequestPurpose.PluginUninstallRecoveryList(epoch.batchId),
                )
                if (!transmitted) {
                    failPluginUninstallRecoveryBatch(
                        epoch.batchId,
                        "uninstall_recovery_list_transport_ambiguous",
                    )
                }
            }
        }
    }

    private fun handlePluginUninstallRecoveryList(
        purpose: RequestPurpose.PluginUninstallRecoveryList,
        listed: PluginListWireResult,
    ) {
        val batch = pluginUninstallRecoveryBatch ?: return
        if (!isCurrentPluginUninstallRecoveryEpoch(batch.epoch) ||
            batch.epoch.batchId != purpose.batchId || batch.evaluating
        ) {
            failPluginUninstallRecoveryBatch(
                purpose.batchId,
                "uninstall_recovery_list_correlation_changed",
            )
            return
        }
        batch.evaluating = true
        val transactions = checkNotNull(pluginUninstallTransactions)
        val epoch = batch.epoch
        val scheduled = runCatching {
            pluginPreparationExecutor.execute {
                val outcome = synchronized(pluginRecoveryExecutionLock) {
                    if (epoch.cancelled) {
                        PluginUninstallRecoveryEvaluationOutcome.Cancelled
                    } else {
                        PluginUninstallRecoveryEvaluationOutcome.Completed(
                            batch.claims.map { claim ->
                                runCatching {
                                    transactions.recoverClaim(claim.operationId, listed)
                                }.getOrNull()
                            },
                        )
                    }
                }
                finishPluginUninstallRecoveryEvaluation(epoch, outcome)
            }
        }.isSuccess
        if (!scheduled) {
            failPluginUninstallRecoveryBatch(
                purpose.batchId,
                "uninstall_recovery_evaluation_schedule_failed",
            )
        }
    }

    @Synchronized
    private fun finishPluginUninstallRecoveryEvaluation(
        epoch: PluginUninstallRecoveryEpoch,
        outcome: PluginUninstallRecoveryEvaluationOutcome,
    ) {
        if (!isCurrentPluginUninstallRecoveryEpoch(epoch)) return
        when (outcome) {
            PluginUninstallRecoveryEvaluationOutcome.Cancelled -> return
            is PluginUninstallRecoveryEvaluationOutcome.Completed -> {
                val blocked = outcome.executions.any {
                    it != PluginUninstallExecution.Removed && it != PluginUninstallExecution.Retry
                }
                finishPluginUninstallRecoveryGate(epoch, blocked)
            }
        }
    }

    private fun failPluginUninstallRecoveryBatch(batchId: String, reason: String) {
        val batch = pluginUninstallRecoveryBatch ?: return
        if (batch.epoch.batchId != batchId ||
            !isCurrentPluginUninstallRecoveryEpoch(batch.epoch)
        ) return
        protocolDiagnostics("Plugin uninstall recovery deferred: $reason")
        finishPluginUninstallRecoveryGate(batch.epoch, blocked = true)
    }

    private fun finishPluginUninstallRecoveryGate(
        epoch: PluginUninstallRecoveryEpoch,
        blocked: Boolean,
    ) {
        if (!isCurrentPluginUninstallRecoveryEpoch(epoch)) return
        synchronized(pluginRecoveryExecutionLock) { epoch.cancelled = true }
        pluginUninstallRecoveryEpoch = null
        pluginUninstallRecoveryBatch = null
        finishCombinedPluginRecoveryGate(
            activeGeneration = epoch.generation,
            blocked = epoch.installRecoveryBlocked || blocked,
        )
    }

    private fun finishCombinedPluginRecoveryGate(activeGeneration: Long, blocked: Boolean) {
        if (generation != activeGeneration) return
        pluginRecoveryGateInProgress = false
        pluginMutationsBlockedByRecovery = blocked
        if (blocked) {
            bootstrapPluginDomainReadOnly(activeGeneration)
        } else {
            bootstrapPluginDomain(activeGeneration)
        }
    }

    private fun bootstrapPluginDomainReadOnly(activeGeneration: Long) {
        if (!signedIn || pluginBootstrappedGeneration == activeGeneration) return
        pluginBootstrappedGeneration = activeGeneration
        if (bundledSetupPlugin.enabled) {
            bundledSetupBootstrapPhase = BundledSetupBootstrapPhase.FAILED
            bundledSetupProvenPluginHandle = null
        }
        refreshPlugins(forceRefetch = false)
        refreshApps(forceRefetch = false)
        refreshSkills(forceReload = false)
    }

    private fun isCurrentPluginRecoveryEpoch(epoch: PluginRecoveryEpoch): Boolean =
        !epoch.cancelled && pluginRecoveryEpoch === epoch && generation == epoch.generation

    private fun isCurrentPluginUninstallRecoveryEpoch(
        epoch: PluginUninstallRecoveryEpoch,
    ): Boolean = !epoch.cancelled && pluginUninstallRecoveryEpoch === epoch &&
        generation == epoch.generation

    private fun cancelPluginRecoveryEpoch() {
        val epoch = pluginRecoveryEpoch
        if (epoch != null) synchronized(pluginRecoveryExecutionLock) { epoch.cancelled = true }
        val uninstallEpoch = pluginUninstallRecoveryEpoch
        if (uninstallEpoch != null) {
            synchronized(pluginRecoveryExecutionLock) { uninstallEpoch.cancelled = true }
        }
        pluginRecoveryEpoch = null
        pluginRecoveryBatch = null
        pluginUninstallRecoveryEpoch = null
        pluginUninstallRecoveryBatch = null
        pluginRecoveryGateInProgress = false
    }

    private fun nextPluginRecoveryId(kind: String): String =
        "$kind-${nextPluginOperationId()}"

    private fun bootstrapPluginDomain(activeGeneration: Long) {
        if (!signedIn || pluginBootstrappedGeneration == activeGeneration) return
        pluginBootstrappedGeneration = activeGeneration
        if (!bundledSetupPlugin.enabled) {
            refreshPlugins(forceRefetch = false)
            refreshApps(forceRefetch = false)
            refreshSkills(forceReload = false)
            return
        }
        startBundledSetupBootstrap()
    }

    private fun startBundledSetupBootstrap() {
        bundledSetupRetryQueued = false
        bundledSetupBootstrapPhase = BundledSetupBootstrapPhase.ADDING_MARKETPLACE
        bundledSetupProvenPluginHandle = null
        val root = checkNotNull(bundledSetupPlugin.marketplaceRoot)
        transmit(
            PluginAppServerRequests.marketplaceAdd(requestIds.next(), root),
            RequestPurpose.BundledMarketplaceAdd,
        )
    }

    private fun maybeStartQueuedBundledSetupRetry() {
        if (!bundledSetupRetryQueued || !canUsePluginDomain()) return
        if (requestPurposes.values.any { it.isPluginPurpose() }) return
        startBundledSetupBootstrap()
    }

    private fun requestBundledSetupPluginList(expectInstalled: Boolean) {
        if (bundledSetupBootstrapPhase == BundledSetupBootstrapPhase.FAILED) return
        bundledSetupBootstrapPhase = if (expectInstalled) {
            BundledSetupBootstrapPhase.PROVING_INSTALL
        } else {
            BundledSetupBootstrapPhase.DISCOVERING
        }
        val operationId = nextPluginOperationId()
        if (!pluginReducer.beginOperation(operationId, PluginOperationKind.REFRESH_PLUGINS)) {
            failBundledSetupBootstrap("Bundled setup plugin discovery was busy")
            return
        }
        val root = checkNotNull(bundledSetupPlugin.marketplaceRoot)
        sendPluginRequest(
            operationId = operationId,
            purpose = RequestPurpose.BundledPluginList(operationId, expectInstalled),
            request = PluginAppServerRequests.pluginList(
                id = requestIds.next(),
                workspacePath = sessionStore.workspacePath,
                forceRefetch = true,
                // Direct discovery is a deliberate recovery path for App
                // Server builds whose marketplace/add surface accepts only
                // Git sources. Installation still uses the returned exact
                // marketplace path and is subsequently proven by plugin/list.
                additionalWorkingDirectories = listOf(root),
            ),
        )
    }

    private fun continueBundledSetupAfterPluginList(
        listed: PluginListWireResult,
        expectInstalled: Boolean,
    ) {
        val expectedRoot = checkNotNull(bundledSetupPlugin.marketplaceRoot)
        val expectedManifest = File(
            expectedRoot,
            BundledSetupPluginContract.MARKETPLACE_MANIFEST_RELATIVE_PATH,
        )
        val record = listed.records.singleOrNull { candidate ->
            candidate.locator.pluginName == BundledSetupPluginContract.PLUGIN_NAME &&
                candidate.locator.marketplaceName == BundledSetupPluginContract.MARKETPLACE_NAME &&
                candidate.card.sourceKind == PluginSourceKind.LOCAL &&
                candidate.locator.marketplacePath?.let { path ->
                    runCatching {
                        File(path).canonicalFile == expectedManifest.canonicalFile
                    }.getOrDefault(false)
                } == true
        }
        if (record == null) {
            failBundledSetupBootstrap("Bundled setup plugin was not discovered exactly once")
            return
        }
        val expectedVersion = checkNotNull(bundledSetupPlugin.expectedPluginVersion)
        // App Server exposes `version` for remote marketplace entries. Exact local
        // marketplace entries may legitimately return null here; their source bytes and
        // manifest version were already atomically materialized and verified by
        // CodexRuntimeBootstrapMaterializer, and the post-install proof below still requires
        // an exact localVersion match. A non-null conflicting version remains fail-closed.
        if (record.availableVersion != null && record.availableVersion != expectedVersion) {
            failBundledSetupBootstrap("Bundled setup plugin source version did not match")
            return
        }
        val needsInstallOrUpdate = !record.card.installed ||
            !record.card.enabled || record.localVersion != expectedVersion
        if (!needsInstallOrUpdate) {
            bundledSetupProvenPluginHandle = record.card.handle
            requestBundledSetupSkillsProof()
            return
        }
        if (expectInstalled) {
            failBundledSetupBootstrap("Bundled setup plugin install or update was not proven")
            return
        }
        if (!record.card.installed && !record.card.installable) {
            failBundledSetupBootstrap("Bundled setup plugin is not installable")
            return
        }
        bundledSetupBootstrapPhase = BundledSetupBootstrapPhase.INSTALLING
        val operationId = nextPluginOperationId()
        if (!pluginReducer.beginOperation(
                operationId,
                PluginOperationKind.INSTALL_PLUGIN,
                record.card.handle,
            )
        ) {
            failBundledSetupBootstrap("Bundled setup plugin install was busy")
            return
        }
        sendPluginRequest(
            operationId = operationId,
            purpose = RequestPurpose.BundledPluginInstall(operationId),
            request = PluginAppServerRequests.pluginInstall(
                id = requestIds.next(),
                locator = record.locator,
                installAttemptId = operationId,
            ),
        )
    }

    private fun requestBundledSetupSkillsProof() {
        bundledSetupBootstrapPhase = BundledSetupBootstrapPhase.PROVING_SKILL
        val operationId = nextPluginOperationId()
        if (!pluginReducer.beginOperation(operationId, PluginOperationKind.REFRESH_SKILLS)) {
            failBundledSetupBootstrap("Bundled setup skill proof was busy")
            return
        }
        sendPluginRequest(
            operationId = operationId,
            purpose = RequestPurpose.BundledPluginSkills(operationId),
            request = AppServerRequests.skillsList(
                id = requestIds.next(),
                workingDirectories = listOf(sessionStore.workspacePath),
                forceReload = true,
            ),
        )
    }

    private fun failBundledSetupBootstrap(summary: String) {
        if (bundledSetupBootstrapPhase == BundledSetupBootstrapPhase.FAILED) return
        bundledSetupRetryQueued = false
        bundledSetupBootstrapPhase = BundledSetupBootstrapPhase.FAILED
        bundledSetupProvenPluginHandle = null
        protocolDiagnostics(summary)
        // Setup is an optional capability domain. Keep core chat healthy and
        // still populate the ordinary plugin/app/skill views for repair.
        if (refreshPlugins(forceRefetch = true) == null) pluginRefreshQueued = true
        if (refreshApps(forceRefetch = true) == null) appRefreshQueued = true
        if (refreshSkills(forceReload = true) == null) skillRefreshQueued = true
    }

    private fun invalidateBundledSetupBootstrap() {
        bundledSetupRetryQueued = false
        bundledSetupBootstrapPhase = BundledSetupBootstrapPhase.IDLE
        bundledSetupProvenPluginHandle = null
        pluginBootstrappedGeneration = null
    }

    private fun refreshCapabilitiesAfterMutation() {
        if (refreshPlugins(forceRefetch = true) == null) pluginRefreshQueued = true
        refreshAppsAndSkillsAfterMutation()
    }

    /** The uninstall proof already is the one required fresh full plugin/list. */
    private fun refreshAppsAndSkillsAfterMutation() {
        if (refreshApps(forceRefetch = true) == null) appRefreshQueued = true
        if (refreshSkills(forceReload = true) == null) skillRefreshQueued = true
    }

    private fun sendAppListPage(
        operationId: String,
        cursor: String?,
        forceRefetch: Boolean,
    ): String? = sendPluginRequest(
        operationId = operationId,
        purpose = RequestPurpose.PluginApps(operationId),
        request = PluginAppServerRequests.appList(
            id = requestIds.next(),
            threadId = reducer.snapshot().currentThreadId,
            cursor = cursor,
            forceRefetch = forceRefetch,
        ),
    )

    private fun sendPluginRequest(
        operationId: String,
        purpose: RequestPurpose,
        request: EncodedRequest,
    ): String? {
        val sent = transmit(request, purpose)
        if (!sent) {
            pluginReducer.fail(
                operationId,
                PluginOperationFailure.TRANSPORT_AMBIGUOUS,
                retryable = false,
            )
        }
        notifyObservers()
        return operationId
    }

    private fun schedulePluginRuntimePreparation(
        operationId: String,
        locator: ai.hans.standard.plugins.PluginLocator,
        runtimeRecord: PluginWireRecord,
    ) {
        val transactions = pluginInstallTransactions ?: return
        val expectedGeneration = generation
        val scheduled = runCatching {
            pluginPreparationExecutor.execute {
                val preparation = runCatching {
                    transactions.prepare(operationId, runtimeRecord)
                }.getOrElse {
                    PluginInstallRuntimePreparation.Rejected(
                        "runtime_dependency_prepare_failed",
                    )
                }
                finishPluginRuntimePreparation(
                    operationId = operationId,
                    locator = locator,
                    expectedGeneration = expectedGeneration,
                    preparation = preparation,
                )
            }
        }.isSuccess
        if (!scheduled) failPluginRuntimePreparationScheduling(operationId)
    }

    /**
     * Correlates a fresh plugin/read result to the exact declaration captured before the read.
     * Staging and dependency preparation are both disk/blocking work and never run under the
     * controller monitor. A staged receipt which was not claimed is explicitly discarded.
     */
    private fun schedulePluginRuntimeSurfaceEvidence(
        purpose: RequestPurpose.PluginRuntimeSurfaceRead,
        read: PluginReadWireResult,
    ) {
        val transactions = pluginInstallTransactions
        val stager = pluginSurfaceEvidenceStager
        val expectedGeneration = generation
        if (transactions == null || stager == null) {
            failPluginRuntimePreparationScheduling(purpose.operationId)
            return
        }
        val scheduled = runCatching {
            pluginPreparationExecutor.execute {
                var stagedReceipt: ai.hans.standard.plugins.runtime.PluginSurfaceEvidenceStageReceipt? = null
                val preparation = if (
                    read.detail.handle != purpose.runtimeRecord.card.handle ||
                    read.sourceKind != purpose.runtimeRecord.card.sourceKind ||
                    read.localSourcePath != purpose.runtimeRecord.localSourcePath
                ) {
                    PluginInstallRuntimePreparation.Rejected("surface_plugin_read_correlation_changed")
                } else {
                    when (val staged = runCatching {
                        stager.stage(purpose.declaration, read.detail)
                    }.getOrElse {
                        PluginSurfaceEvidenceStageResult.Rejected("surface_evidence_stage_failed")
                    }) {
                        PluginSurfaceEvidenceStageResult.NotDeclared ->
                            PluginInstallRuntimePreparation.Rejected("surface_declaration_lost")
                        is PluginSurfaceEvidenceStageResult.Rejected ->
                            PluginInstallRuntimePreparation.Rejected(staged.reason)
                        is PluginSurfaceEvidenceStageResult.Staged -> {
                            stagedReceipt = staged.receipt
                            runCatching {
                                transactions.prepare(purpose.operationId, purpose.runtimeRecord)
                            }.getOrElse {
                                PluginInstallRuntimePreparation.Rejected(
                                    "runtime_dependency_prepare_failed",
                                )
                            }
                        }
                    }
                }
                if (stagedReceipt != null && preparation !is PluginInstallRuntimePreparation.Prepared) {
                    runCatching { stager.discard(checkNotNull(stagedReceipt)) }
                }
                val effective = if (
                    stagedReceipt != null &&
                    preparation === PluginInstallRuntimePreparation.StandardCodexPlugin
                ) {
                    PluginInstallRuntimePreparation.Rejected("surface_runtime_manifest_required")
                } else {
                    preparation
                }
                finishPluginRuntimePreparation(
                    operationId = purpose.operationId,
                    locator = purpose.locator,
                    expectedGeneration = expectedGeneration,
                    preparation = effective,
                )
            }
        }.isSuccess
        if (!scheduled) failPluginRuntimePreparationScheduling(purpose.operationId)
    }

    @Synchronized
    private fun failPluginRuntimePreparationScheduling(operationId: String) {
        cancelPluginInstallDeadline(operationId)
        if (pluginReducer.isPending(operationId, PluginOperationKind.INSTALL_PLUGIN)) {
            pluginReducer.fail(
                operationId,
                PluginOperationFailure.LOCAL_INCOMPATIBLE,
                retryable = true,
            )
        }
        notifyObservers()
    }

    @Synchronized
    private fun finishPluginRuntimePreparation(
        operationId: String,
        locator: ai.hans.standard.plugins.PluginLocator,
        expectedGeneration: Long?,
        preparation: PluginInstallRuntimePreparation,
    ) {
        val transactions = pluginInstallTransactions ?: return
        val stillCurrent = generation == expectedGeneration && canUsePluginDomain() &&
            pluginReducer.isPending(operationId, PluginOperationKind.INSTALL_PLUGIN)
        if (!stillCurrent) {
            cancelPluginInstallDeadline(operationId)
            if (preparation is PluginInstallRuntimePreparation.Prepared) {
                runCatching { transactions.fail(operationId) }
            }
            if (pluginReducer.isPending(operationId, PluginOperationKind.INSTALL_PLUGIN)) {
                pluginReducer.fail(
                    operationId,
                    PluginOperationFailure.TRANSPORT_AMBIGUOUS,
                    retryable = false,
                )
            }
            notifyObservers()
            return
        }
        when (preparation) {
            PluginInstallRuntimePreparation.StandardCodexPlugin -> sendPluginRequest(
                operationId,
                RequestPurpose.PluginInstall(operationId),
                PluginAppServerRequests.pluginInstall(
                    id = requestIds.next(),
                    locator = locator,
                    installAttemptId = operationId,
                ),
            )
            is PluginInstallRuntimePreparation.RemoteMcpConnectionRequired -> {
                cancelPluginInstallDeadline(operationId)
                val projected = runCatching {
                    pluginReducer.requireRemoteMcpConnection(operationId, preparation.request)
                }.isSuccess
                if (!projected &&
                    pluginReducer.isPending(operationId, PluginOperationKind.INSTALL_PLUGIN)
                ) {
                    pluginReducer.fail(
                        operationId,
                        PluginOperationFailure.MALFORMED_RESPONSE,
                        retryable = false,
                    )
                }
                notifyObservers()
            }
            is PluginInstallRuntimePreparation.RemoteMcpPolicyReviewRequired -> {
                cancelPluginInstallDeadline(operationId)
                val projected = runCatching {
                    pluginReducer.requireRemoteMcpPolicyReview(
                        operationId = operationId,
                        request = preparation.request,
                        sourceSha256 = preparation.sourceSha256,
                    )
                }.isSuccess
                if (!projected &&
                    pluginReducer.isPending(operationId, PluginOperationKind.INSTALL_PLUGIN)
                ) {
                    pluginReducer.fail(
                        operationId,
                        PluginOperationFailure.MALFORMED_RESPONSE,
                        retryable = false,
                    )
                }
                notifyObservers()
            }
            is PluginInstallRuntimePreparation.Rejected -> {
                cancelPluginInstallDeadline(operationId)
                pluginReducer.fail(
                    operationId,
                    PluginOperationFailure.LOCAL_INCOMPATIBLE,
                    retryable = true,
                )
                notifyObservers()
            }
            is PluginInstallRuntimePreparation.Prepared -> {
                val intentPersisted = runCatching {
                    transactions.markRemoteInstallIntent(operationId)
                }.onFailure {
                    protocolDiagnostics("Runtime plugin install intent could not be persisted")
                }.isSuccess
                if (!intentPersisted) {
                    failRuntimePluginInstall(
                        operationId,
                        PluginOperationFailure.LOCAL_INCOMPATIBLE,
                        retryable = true,
                    )
                    notifyObservers()
                    return
                }
                sendPluginRequest(
                    operationId,
                    RequestPurpose.PluginRuntimeInstall(operationId),
                    PluginAppServerRequests.pluginInstall(
                        id = requestIds.next(),
                        locator = locator,
                        installAttemptId = operationId,
                    ),
                )
                if (!pluginReducer.isPending(operationId, PluginOperationKind.INSTALL_PLUGIN)) {
                    runCatching { transactions.fail(operationId) }
                }
            }
        }
    }

    @Synchronized
    private fun finishPluginUninstallPreparation(
        operationId: String,
        proofOperationId: String,
        pluginId: String,
        invalidatesBundledSetup: Boolean,
        expectedGeneration: Long?,
        preparation: PluginUninstallPreparation,
    ) {
        val stillCurrent = generation == expectedGeneration && canUsePluginDomain() &&
            pluginReducer.isPending(operationId, PluginOperationKind.UNINSTALL_PLUGIN) &&
            pluginReducer.isPending(proofOperationId, PluginOperationKind.REFRESH_PLUGINS)
        if (preparation !is PluginUninstallPreparation.Prepared || !stillCurrent) {
            if (preparation is PluginUninstallPreparation.Prepared) {
                runCatching {
                    pluginPreparationExecutor.execute {
                        runCatching { pluginUninstallTransactions?.fail(operationId) }
                    }
                }
            }
            failPluginUninstallOperations(
                operationId = operationId,
                proofOperationId = proofOperationId,
                failure = if (preparation is PluginUninstallPreparation.Rejected) {
                    PluginOperationFailure.LOCAL_INCOMPATIBLE
                } else {
                    PluginOperationFailure.TRANSPORT_AMBIGUOUS
                },
                retryable = true,
            )
            notifyObservers()
            return
        }
        val transmitted = transmit(
            PluginAppServerRequests.pluginUninstall(requestIds.next(), pluginId),
            RequestPurpose.PluginUninstall(
                operationId = operationId,
                proofOperationId = proofOperationId,
                pluginId = pluginId,
                invalidatesBundledSetup = invalidatesBundledSetup,
            ),
        )
        if (!transmitted) {
            failPluginUninstallOperations(
                operationId,
                proofOperationId,
                PluginOperationFailure.TRANSPORT_AMBIGUOUS,
                retryable = false,
                blockMutations = true,
            )
        }
        notifyObservers()
    }

    private fun schedulePluginUninstallAcceptance(purpose: RequestPurpose.PluginUninstall) {
        val transactions = pluginUninstallTransactions
        val expectedGeneration = generation
        val scheduled = transactions != null && runCatching {
            pluginPreparationExecutor.execute {
                val accepted = runCatching {
                    transactions.markRemoteAccepted(purpose.operationId)
                }.isSuccess
                finishPluginUninstallAcceptance(purpose, expectedGeneration, accepted)
            }
        }.isSuccess
        if (!scheduled) {
            failPluginUninstallRequest(
                operationId = purpose.operationId,
                proofOperationId = purpose.proofOperationId,
                failure = PluginOperationFailure.BUSY,
                retryable = true,
                blockMutations = true,
            )
        }
    }

    @Synchronized
    private fun finishPluginUninstallAcceptance(
        purpose: RequestPurpose.PluginUninstall,
        expectedGeneration: Long?,
        accepted: Boolean,
    ) {
        val stillCurrent = generation == expectedGeneration && canUsePluginDomain() &&
            pluginReducer.isPending(purpose.operationId, PluginOperationKind.UNINSTALL_PLUGIN) &&
            pluginReducer.isPending(purpose.proofOperationId, PluginOperationKind.REFRESH_PLUGINS)
        if (!accepted || !stillCurrent) {
            failPluginUninstallOperations(
                purpose.operationId,
                purpose.proofOperationId,
                PluginOperationFailure.TRANSPORT_AMBIGUOUS,
                retryable = false,
                blockMutations = true,
            )
            notifyObservers()
            return
        }
        val transmitted = transmit(
            PluginAppServerRequests.pluginList(
                id = requestIds.next(),
                workspacePath = sessionStore.workspacePath,
                forceRefetch = true,
            ),
            RequestPurpose.PluginUninstallProof(
                uninstallOperationId = purpose.operationId,
                proofOperationId = purpose.proofOperationId,
                invalidatesBundledSetup = purpose.invalidatesBundledSetup,
            ),
        )
        if (!transmitted) {
            failPluginUninstallOperations(
                purpose.operationId,
                purpose.proofOperationId,
                PluginOperationFailure.TRANSPORT_AMBIGUOUS,
                retryable = false,
                blockMutations = true,
            )
        }
        notifyObservers()
    }

    private fun schedulePluginUninstallProof(
        purpose: RequestPurpose.PluginUninstallProof,
        listed: PluginListWireResult,
    ) {
        val transactions = pluginUninstallTransactions
        val expectedGeneration = generation
        val scheduled = transactions != null && runCatching {
            pluginPreparationExecutor.execute {
                val outcome = runCatching {
                    transactions.proveAndDeactivate(purpose.uninstallOperationId, listed)
                }
                finishPluginUninstallProof(purpose, listed, expectedGeneration, outcome)
            }
        }.isSuccess
        if (!scheduled) {
            failPluginUninstallRequest(
                operationId = purpose.uninstallOperationId,
                proofOperationId = purpose.proofOperationId,
                failure = PluginOperationFailure.BUSY,
                retryable = true,
                blockMutations = true,
            )
        }
    }

    @Synchronized
    private fun finishPluginUninstallProof(
        purpose: RequestPurpose.PluginUninstallProof,
        listed: PluginListWireResult,
        expectedGeneration: Long?,
        outcome: Result<PluginUninstallExecution>,
    ) {
        if (generation != expectedGeneration || !canUsePluginDomain()) return
        val execution = outcome.onFailure {
            protocolDiagnostics("Plugin uninstall proof or exact cleanup failed")
        }.getOrNull()
        when (execution) {
            PluginUninstallExecution.Removed -> {
                pluginReducer.applyPluginList(purpose.proofOperationId, listed)
                pluginReducer.applyPluginUninstall(
                    purpose.uninstallOperationId,
                    PluginUninstallWireResult,
                )
                if (purpose.invalidatesBundledSetup) {
                    bundledSetupBootstrapPhase = BundledSetupBootstrapPhase.FAILED
                    bundledSetupProvenPluginHandle = null
                }
                refreshAppsAndSkillsAfterMutation()
            }
            PluginUninstallExecution.Retry -> {
                pluginReducer.applyPluginList(purpose.proofOperationId, listed)
                pluginReducer.fail(
                    purpose.uninstallOperationId,
                    PluginOperationFailure.REMOTE_REJECTED,
                    retryable = true,
                )
            }
            is PluginUninstallExecution.Quarantined,
            is PluginUninstallExecution.Deferred,
            null,
            -> {
                if (pluginReducer.isPending(
                        purpose.proofOperationId,
                        PluginOperationKind.REFRESH_PLUGINS,
                    )
                ) {
                    pluginReducer.applyPluginList(purpose.proofOperationId, listed)
                }
                if (pluginReducer.isPending(
                        purpose.uninstallOperationId,
                        PluginOperationKind.UNINSTALL_PLUGIN,
                    )
                ) {
                    pluginReducer.fail(
                        purpose.uninstallOperationId,
                        PluginOperationFailure.LOCAL_INCOMPATIBLE,
                        retryable = true,
                    )
                }
                pluginMutationsBlockedByRecovery = true
            }
        }
        notifyObservers()
    }

    private fun failPluginUninstallRequest(
        operationId: String,
        proofOperationId: String,
        failure: PluginOperationFailure,
        retryable: Boolean,
        blockMutations: Boolean,
    ) {
        runCatching {
            pluginPreparationExecutor.execute {
                runCatching { pluginUninstallTransactions?.fail(operationId) }
                    .onFailure { protocolDiagnostics("Plugin uninstall recovery was deferred") }
            }
        }.onFailure { protocolDiagnostics("Plugin uninstall failure could not be journaled") }
        failPluginUninstallOperations(
            operationId,
            proofOperationId,
            failure,
            retryable,
            blockMutations,
        )
        notifyObservers()
    }

    private fun failPluginUninstallOperations(
        operationId: String,
        proofOperationId: String,
        failure: PluginOperationFailure,
        retryable: Boolean,
        blockMutations: Boolean = false,
    ) {
        if (pluginReducer.isPending(operationId, PluginOperationKind.UNINSTALL_PLUGIN)) {
            pluginReducer.fail(operationId, failure, retryable)
        }
        if (pluginReducer.isPending(proofOperationId, PluginOperationKind.REFRESH_PLUGINS)) {
            pluginReducer.fail(proofOperationId, failure, retryable)
        }
        if (blockMutations) pluginMutationsBlockedByRecovery = true
    }

    private fun requestRuntimePluginInstallProof(operationId: String) {
        val proofOperationId = nextPluginOperationId()
        if (!pluginReducer.beginOperation(
                proofOperationId,
                PluginOperationKind.REFRESH_PLUGINS,
            )
        ) {
            failRuntimePluginInstall(operationId, PluginOperationFailure.BUSY, retryable = true)
            return
        }
        val transmitted = transmit(
            PluginAppServerRequests.pluginList(
                id = requestIds.next(),
                workspacePath = sessionStore.workspacePath,
                forceRefetch = true,
            ),
            RequestPurpose.PluginRuntimeInstallProof(
                installOperationId = operationId,
                proofOperationId = proofOperationId,
            ),
        )
        if (!transmitted) {
            pluginReducer.fail(
                proofOperationId,
                PluginOperationFailure.TRANSPORT_AMBIGUOUS,
                retryable = false,
            )
            failRuntimePluginInstall(
                operationId,
                PluginOperationFailure.TRANSPORT_AMBIGUOUS,
                retryable = false,
            )
        }
        notifyObservers()
    }

    private fun scheduleRuntimePluginCommit(
        purpose: RequestPurpose.PluginRuntimeInstallProof,
        listed: PluginListWireResult,
    ) {
        val transactions = pluginInstallTransactions
        val expectedGeneration = generation
        val scheduled = transactions != null && runCatching {
            pluginPreparationExecutor.execute {
                val outcome = runCatching {
                    transactions.proveAndCommit(
                        purpose.installOperationId,
                        listed.records,
                    )
                }
                finishRuntimePluginCommit(
                    purpose = purpose,
                    listed = listed,
                    expectedGeneration = expectedGeneration,
                    outcome = outcome,
                )
            }
        }.isSuccess
        if (!scheduled) {
            if (pluginReducer.isPending(
                    purpose.proofOperationId,
                    PluginOperationKind.REFRESH_PLUGINS,
                )
            ) {
                pluginReducer.fail(
                    purpose.proofOperationId,
                    PluginOperationFailure.BUSY,
                    retryable = true,
                )
            }
            failRuntimePluginInstall(
                purpose.installOperationId,
                PluginOperationFailure.BUSY,
                retryable = true,
            )
        }
    }

    @Synchronized
    private fun finishRuntimePluginCommit(
        purpose: RequestPurpose.PluginRuntimeInstallProof,
        listed: PluginListWireResult,
        expectedGeneration: Long?,
        outcome: Result<Boolean>,
    ) {
        cancelPluginInstallDeadline(purpose.installOperationId)
        val stillCurrent = generation == expectedGeneration && canUsePluginDomain()
        if (!stillCurrent) {
            // The exact install is durable. A new App Server generation refreshes and reconciles
            // the catalog; stale response data is never projected into the replacement session.
            return
        }
        val committed = outcome.onFailure {
            protocolDiagnostics("Runtime plugin activation proof failed")
        }.getOrDefault(false)
        if (committed) {
            pluginReducer.applyPluginList(purpose.proofOperationId, listed)
            pluginReducer.proveRuntimePluginInstall(purpose.installOperationId)
            refreshCapabilitiesAfterMutation()
        } else {
            if (pluginReducer.isPending(
                    purpose.proofOperationId,
                    PluginOperationKind.REFRESH_PLUGINS,
                )
            ) {
                pluginReducer.fail(
                    purpose.proofOperationId,
                    PluginOperationFailure.MALFORMED_RESPONSE,
                    retryable = true,
                )
            }
            failRuntimePluginInstall(
                purpose.installOperationId,
                PluginOperationFailure.MALFORMED_RESPONSE,
                retryable = true,
            )
        }
        notifyObservers()
    }

    private fun failRuntimePluginInstall(
        operationId: String,
        failure: PluginOperationFailure,
        retryable: Boolean,
    ) {
        cancelPluginInstallDeadline(operationId)
        val transactions = pluginInstallTransactions
        if (transactions != null) {
            runCatching {
                pluginPreparationExecutor.execute {
                    runCatching { transactions.fail(operationId) }
                        .onFailure { protocolDiagnostics("Runtime plugin rollback failed") }
                }
            }.onFailure {
                protocolDiagnostics("Runtime plugin rollback could not be scheduled")
            }
        }
        if (pluginReducer.isPending(operationId, PluginOperationKind.INSTALL_PLUGIN)) {
            pluginReducer.fail(operationId, failure, retryable)
        }
    }

    private fun failMalformedPluginPurpose(purpose: RequestPurpose) {
        when (purpose) {
            is RequestPurpose.PluginInstallRecoveryList ->
                failPluginRecoveryBatch(purpose.batchId, "recovery_list_malformed")
            is RequestPurpose.PluginInstallRecoveryUninstall ->
                failPluginRecoveryCompensation(
                    operationId = purpose.operationId,
                    reason = "recovery_uninstall_malformed",
                )
            is RequestPurpose.PluginInstallRecoveryAbsenceProof ->
                failPluginRecoveryCompensation(
                    operationId = purpose.operationId,
                    reason = "recovery_absence_proof_malformed",
                )
            is RequestPurpose.PluginUninstallRecoveryList ->
                failPluginUninstallRecoveryBatch(
                    purpose.batchId,
                    "uninstall_recovery_list_malformed",
                )
            is RequestPurpose.PluginUninstall -> failPluginUninstallRequest(
                operationId = purpose.operationId,
                proofOperationId = purpose.proofOperationId,
                failure = PluginOperationFailure.MALFORMED_RESPONSE,
                retryable = false,
                blockMutations = true,
            )
            is RequestPurpose.PluginUninstallProof -> failPluginUninstallRequest(
                operationId = purpose.uninstallOperationId,
                proofOperationId = purpose.proofOperationId,
                failure = PluginOperationFailure.MALFORMED_RESPONSE,
                retryable = false,
                blockMutations = true,
            )
            is RequestPurpose.PluginRuntimeSurfaceRead -> {
                cancelPluginInstallDeadline(purpose.operationId)
                pluginReducer.fail(
                    purpose.operationId,
                    PluginOperationFailure.MALFORMED_RESPONSE,
                    retryable = false,
                )
            }
            is RequestPurpose.PluginRuntimeInstall -> failRuntimePluginInstall(
                purpose.operationId,
                PluginOperationFailure.MALFORMED_RESPONSE,
                retryable = false,
            )
            is RequestPurpose.PluginRuntimeInstallProof -> {
                if (pluginReducer.isPending(
                        purpose.proofOperationId,
                        PluginOperationKind.REFRESH_PLUGINS,
                    )
                ) {
                    pluginReducer.fail(
                        purpose.proofOperationId,
                        PluginOperationFailure.MALFORMED_RESPONSE,
                        retryable = false,
                    )
                }
                failRuntimePluginInstall(
                    purpose.installOperationId,
                    PluginOperationFailure.MALFORMED_RESPONSE,
                    retryable = false,
                )
            }
            is RequestPurpose.PluginInstall -> {
                cancelPluginInstallDeadline(purpose.operationId)
                pluginReducer.fail(
                    purpose.operationId,
                    PluginOperationFailure.MALFORMED_RESPONSE,
                    retryable = false,
                )
            }
            else -> pluginReducer.fail(
                purpose.pluginOperationId(),
                PluginOperationFailure.MALFORMED_RESPONSE,
                retryable = false,
            )
        }
    }

    private fun abortRuntimePluginTransactions() {
        cancelPluginRecoveryEpoch()
        cancelAllPluginInstallDeadlines()
        runCatching {
            pluginPreparationExecutor.execute {
                pluginInstallTransactions?.let { transactions ->
                    runCatching(transactions::abortAll).onFailure {
                        protocolDiagnostics("Runtime plugin rollback failed during session loss")
                    }
                }
                pluginUninstallTransactions?.let { transactions ->
                    runCatching(transactions::abortAll).onFailure {
                        protocolDiagnostics("Plugin uninstall recovery failed during session loss")
                    }
                }
            }
        }.onFailure {
            protocolDiagnostics("Runtime plugin recovery could not be scheduled during session loss")
        }
    }

    /** Arms one bounded, monotonic lease across local prepare, remote proof and local commit. */
    @Synchronized
    private fun schedulePluginInstallDeadline(operationId: String): Boolean {
        val deadline = pluginInstallDeadline ?: return true
        val now = runCatching(pluginInstallDeadlineNowMillis).getOrNull() ?: return false
        if (now < 0L || pluginInstallTimeoutMillis !in 1L..MAX_PLUGIN_INSTALL_TIMEOUT_MILLIS) {
            return false
        }
        val deadlineAt = runCatching { Math.addExact(now, pluginInstallTimeoutMillis) }
            .getOrNull() ?: return false
        val lease = runCatching {
            deadline.schedule(
                operationId = operationId,
                leaseId = operationId,
                deadlineAtElapsedRealtimeMillis = deadlineAt,
                onExpired = ::handlePluginInstallDeadlineExpired,
            )
        }.getOrNull() ?: return false
        val previous = pluginInstallDeadlines.put(operationId, lease)
        if (previous != null) {
            pluginInstallDeadlines[operationId] = previous
            lease.cancel()
            return false
        }
        // A deliberately synchronous test scheduler may expire re-entrantly before the lease is
        // inserted. Replay the exact identity once; the map check below makes this race idempotent.
        if (lease.isExpired) handlePluginInstallDeadlineExpired(lease.identity)
        return !lease.isExpired
    }

    @Synchronized
    private fun handlePluginInstallDeadlineExpired(identity: PluginInstallDeadlineIdentity) {
        val current = pluginInstallDeadlines[identity.operationId] ?: return
        if (current.identity != identity) return
        pluginInstallDeadlines.remove(identity.operationId)
        failRuntimePluginInstall(
            operationId = identity.operationId,
            failure = PluginOperationFailure.BUSY,
            retryable = true,
        )
        notifyObservers()
    }

    @Synchronized
    private fun cancelPluginInstallDeadline(operationId: String) {
        pluginInstallDeadlines.remove(operationId)?.cancel()
    }

    @Synchronized
    private fun cancelAllPluginInstallDeadlines() {
        val leases = pluginInstallDeadlines.values.toList()
        pluginInstallDeadlines.clear()
        leases.forEach(PluginInstallDeadlineLease::cancel)
    }

    private fun canUsePluginDomain(): Boolean =
        runtimePhase == ClientRuntimePhase.READY && signedIn && threadReady &&
            sessionPhase in setOf(ClientSessionPhase.READY, ClientSessionPhase.BUSY)

    private inline fun <reified T : Any> extensionPayload(result: AppServerResult): T {
        val extension = result as? ExtensionAppServerResult
            ?: throw CrossCorrelationException("Expected extension App Server result")
        return extension.payload as? T
            ?: throw CrossCorrelationException("Extension result type mismatch")
    }

    private fun responseRequestId(envelope: JSONObject): RequestId? = when (
        val value = envelope.opt("id")
    ) {
        is Byte -> RequestId.Number(value.toLong())
        is Short -> RequestId.Number(value.toLong())
        is Int -> RequestId.Number(value.toLong())
        is Long -> RequestId.Number(value)
        is String -> runCatching { RequestId.Text(value) }.getOrNull()
        else -> null
    }

    private fun finishRehydrationIfReady() {
        if (!accountReadComplete) return
        if (signedIn && !threadReady) return
        val activeGeneration = generation ?: return
        reducer.confirmRehydratedGeneration(activeGeneration)
        rehydrating = false
        sessionPhase = if (signedIn) ClientSessionPhase.READY else ClientSessionPhase.AUTH_REQUIRED
        replayingPerformanceEvents = true
        try {
            while (bufferedEvents.isNotEmpty()) {
                val buffered = bufferedEvents.removeFirst()
                bufferedEventBytes -= buffered.frameBytes
                applyEvent(buffered.eventSequence, buffered.event)
            }
        } finally {
            replayingPerformanceEvents = false
            // Recovered state is not a live start/wait receipt.
            val restoredSession = reducer.snapshot()
            val restoredThread = restoredSession.threads.singleOrNull {
                it.threadId == restoredSession.currentThreadId
            }
            val restoredTurn = restoredThread?.currentTurn
            performanceObservedTurnId = restoredTurn?.turnId.takeIf { restoredTurn?.status == TurnStatus.IN_PROGRESS }
            performanceLastTerminalTurnId = restoredTurn?.turnId.takeUnless { restoredTurn?.status == TurnStatus.IN_PROGRESS }
            performanceLastTerminalTurnId?.let { performanceTerminalTurnIds.add(it) }
            performanceUserWaitReported = restoredThread?.runtimeStatus?.let {
                it.status == ai.hans.standard.codex.ThreadRuntimeStatus.ACTIVE && it.activeFlags.isNotEmpty()
            } == true
            performanceAssistantOutputObserved = true // Replayed history cannot prove first live output.
        }
        automaticRestartCount = 0
        updateSessionPhase()
        if (signedIn) {
            startPluginRecoveryBeforeBootstrap(activeGeneration)
        } else {
            pluginMutationsBlockedByRecovery =
                pluginInstallTransactions != null || pluginUninstallTransactions != null
        }
    }

    private fun startPendingDispatchAfterBoundary(messageId: String) {
        val pending = pendingDispatch
        if (pending == null || pending.clientMessageId != messageId) return
        val threadId = reducer.snapshot().currentThreadId ?: run {
            markPendingDispatchFailed(retryable = true)
            setProblem(ClientProblemCode.THREAD_RECOVERY, retryable = true)
            return
        }
        val request = AppServerRequests.turnStart(
            id = requestIds.next(),
            threadId = threadId,
            input = pending.input,
            options = checkNotNull(pending.options),
            clientUserMessageId = pending.clientMessageId,
        )
        transmit(
            request,
            RequestPurpose.TurnStart(pending.clientMessageId, pending.selection, pending.confirmsDefaultSelection),
        )
    }

    private fun markDispatchSent(
        messageId: String,
        selection: DispatchSelection,
        turnId: String,
        confirmsDefaultSelection: Boolean = true,
    ) {
        cancelSetupDispatchDeadline(messageId = messageId)
        if (messageId.startsWith(SETUP_CLIENT_MESSAGE_PREFIX)) {
            // Handles the complementary response-first ordering without depending on a later
            // timeline projection pass to discover the setup message.
            rememberSetupTurn(turnId)
        }
        val wasPending = outbound[messageId]?.status == OutboundMessageStatus.PENDING
        outbound[messageId]?.threadId?.let { threadId ->
            dynamicToolTurnAuthorizationGate.bindDispatch(messageId, threadId, turnId)
            if (wasPending && !messageId.startsWith(SETUP_CLIENT_MESSAGE_PREFIX)) {
                rememberLocalVoiceControlTurn(threadId, turnId)
            }
        }
        outbound[messageId]?.apply {
            status = OutboundMessageStatus.SENT
            retryable = false
            this.turnId = turnId
        }
        if (pendingDispatch?.clientMessageId == messageId) pendingDispatch = null
        pendingSelection = null
        if (wasPending && confirmsDefaultSelection) {
            confirmedSelection = selection
            try {
                settingsStore.saveConfirmedDispatch(
                    selection.model,
                    selection.effort.wireValue,
                    selection.serviceTier,
                )
            } catch (_: Exception) {
                setProblem(ClientProblemCode.LOCAL_PERSISTENCE, retryable = true)
            }
        }
        updateSessionPhase()
        if (wasPending) performanceEvent(PerformanceEvent.DISPATCH_ACKNOWLEDGED)
        settleRealtimeDispatch(messageId, Result.success(Unit))
    }

    private fun removeVisibleInputReceiptForMessage(messageId: String) {
        val message = outbound[messageId] ?: return
        removeVisibleInputReceipt(message.threadId, messageId)
    }

    private fun removeVisibleInputReceipt(threadId: String, messageId: String) {
        runCatching { visibleInputReceipts.remove(threadId, messageId) }
            .onFailure { protocolDiagnostics("Visible input receipt could not be removed") }
    }

    private fun markPendingDispatchFailed(
        retryable: Boolean,
        releaseDynamicToolAuthority: Boolean = true,
    ) {
        val pending = pendingDispatch ?: return
        if (releaseDynamicToolAuthority) {
            dynamicToolTurnAuthorizationGate.releaseRejectedDispatch(pending.clientMessageId)
        }
        cancelSetupDispatchDeadline(messageId = pending.clientMessageId)
        outbound[pending.clientMessageId]?.apply {
            status = OutboundMessageStatus.FAILED
            this.retryable = retryable
        }
        pendingDispatch = null
        pendingSelection = null
        updateSessionPhase()
        performanceEvent(PerformanceEvent.DISPATCH_FAILED)
        settleRealtimeDispatch(pending.clientMessageId,
            Result.failure(CodexRealtimeFailure(CodexRealtimeIssue.CONNECTION_FAILED)))
    }

    private fun armSetupDispatchDeadline(
        requestId: RequestId,
        purpose: RequestPurpose,
        activeGeneration: Long,
    ) {
        val messageId = purpose.setupMessageIdOrNull() ?: return
        cancelSetupDispatchDeadline()
        val cancellation = setupDispatchDeadlineScheduler.schedule(setupDispatchTimeoutMillis) {
            synchronized(this) {
                expireSetupDispatch(requestId, messageId, activeGeneration)
            }
        }
        pendingSetupDispatchDeadline = PendingSetupDispatchDeadline(
            requestId = requestId,
            messageId = messageId,
            generation = activeGeneration,
            cancellation = cancellation,
        )
    }

    private fun expireSetupDispatch(
        requestId: RequestId,
        messageId: String,
        expectedGeneration: Long,
    ) {
        val deadline = pendingSetupDispatchDeadline ?: return
        if (
            deadline.requestId != requestId ||
            deadline.messageId != messageId ||
            deadline.generation != expectedGeneration
        ) {
            return
        }
        pendingSetupDispatchDeadline = null
        if (
            generation != expectedGeneration ||
            runtimePhase != ClientRuntimePhase.READY ||
            pendingDispatch?.clientMessageId != messageId
        ) {
            return
        }
        correlator.discard(requestId)
        requestPurposes.remove(requestId)
        markPendingDispatchFailed(retryable = true)
        controlledRestart(
            CodexClientProblem(ClientProblemCode.DISPATCH_AMBIGUOUS, retryable = true),
        )
    }

    private fun cancelSetupDispatchDeadline(
        requestId: RequestId? = null,
        messageId: String? = null,
    ) {
        val pending = pendingSetupDispatchDeadline ?: return
        if (requestId != null && pending.requestId != requestId) return
        if (messageId != null && pending.messageId != messageId) return
        pendingSetupDispatchDeadline = null
        pending.cancellation.cancel()
    }

    private fun transmit(request: EncodedRequest, purpose: RequestPurpose): Boolean =
        transmitAttempt(request, purpose) == RequestTransmissionResult.SENT

    private fun transmitAttempt(
        request: EncodedRequest,
        purpose: RequestPurpose,
        restartOnAmbiguity: Boolean = true,
    ): RequestTransmissionResult {
        val activeGeneration = generation
            ?: return RequestTransmissionResult.REJECTED_BEFORE_TRANSPORT
        correlator.register(request)
        if (requestPurposes.putIfAbsent(request.id, purpose) != null) {
            throw CrossCorrelationException("Request purpose id is already pending")
        }
        armSetupDispatchDeadline(request.id, purpose, activeGeneration)
        armWorkInterruptDeadline(request.id, purpose, activeGeneration)
        if (purpose is RequestPurpose.NotificationToolOutput || purpose is RequestPurpose.NotificationHistory) {
            nativeNotificationDeadlines[request.id] = notificationExternalDeadlineScheduler.schedule(notificationExternalTimeoutMillis) {
                synchronized(this) {
                    if (generation != activeGeneration || requestPurposes[request.id] !== purpose) return@synchronized
                    correlator.discard(request.id)
                    requestPurposes.remove(request.id)
                    nativeNotificationDeadlines.remove(request.id)
                    retireNativeNotificationRequest(request.id)
                    if (purpose is RequestPurpose.NotificationToolOutput) {
                        protocolDiagnostics("notification_event_receipt_timeout")
                        settleNativeNotification(purpose, NativeNotificationDispatchReceipt.OutcomeAmbiguous)
                    } else protocolDiagnostics("notification_event_history_timeout")
                    notifyObservers()
                }
            }
        }
        val performanceSendEvent = when (purpose) {
            is RequestPurpose.TurnStart -> PerformanceEvent.TURN_START_SEND
            is RequestPurpose.TurnSteer -> PerformanceEvent.TURN_STEER_SEND
            is RequestPurpose.UserInterrupt, is RequestPurpose.BoundaryInterrupt -> PerformanceEvent.INTERRUPT_SEND
            else -> null
        }
        return try {
            performanceSendEvent?.let(::performanceEvent)
            transport.sendFrame(activeGeneration, request.json.toByteArray(StandardCharsets.UTF_8))
            RequestTransmissionResult.SENT
        } catch (error: Exception) {
            if (performanceSendEvent != null) performanceEvent(PerformanceEvent.TRANSPORT_OUTCOME_AMBIGUOUS)
            recordThreadBootstrapFailure(
                purpose,
                BootstrapFailureStage.TRANSPORT_OUTCOME_AMBIGUOUS,
                local = error,
            )
            if (restartOnAmbiguity) {
                controlledRestart(
                    CodexClientProblem(ClientProblemCode.DISPATCH_AMBIGUOUS, retryable = false),
                )
            }
            RequestTransmissionResult.OUTCOME_AMBIGUOUS
        }
    }

    private fun controlledRestart(
        failure: CodexClientProblem,
        automatic: Boolean = true,
    ) {
        invalidateNativeNotificationRequests()
        invalidateRealtime()
        cancelWorkInterruptDeadlines()
        invalidateRemoteControl()
        invalidateSelectionContext()
        cancelSetupDispatchDeadline()
        markPendingDispatchFailed(retryable = false)
        abandonPendingDynamicCalls(clearRequestIds = false)
        abortRuntimePluginTransactions()
        pluginReducer.onSessionLost()
        problem = failure
        val activeGeneration = generation
        if (restartInFlight) {
            notifyObservers()
            return
        }
        if (automatic && automaticRestartCount >= MAX_AUTOMATIC_RESTARTS) {
            failPermanently(failure.code, failure.retryable)
            return
        }
        if (automatic) automaticRestartCount += 1
        restartInFlight = true
        runtimePhase = ClientRuntimePhase.RESTARTING
        sessionPhase = ClientSessionPhase.BOOTSTRAPPING
        correlator = ResponseCorrelator()
        requestPurposes.clear()
        notifyObservers()
        try {
            if (runtimeSessionActive && activeGeneration != null) {
                transport.restart(nextOperation(), activeGeneration)
            } else {
                // A terminal/preflight failure has no supervisor session to restart.
                // Starting with the existing listener retains account and thread recovery.
                transport.start(nextOperation(), this)
            }
        } catch (_: Exception) {
            failPermanently(ClientProblemCode.RUNTIME_FAILED, retryable = true)
        }
    }

    private fun recordNativeNotificationReceipt(receipt: NativeNotificationExternalReceipt) {
        if (receipt.threadId != reducer.snapshot().currentThreadId) return
        val previous = nativeNotificationHistory?.takeIf { it.threadId == receipt.threadId }
        nativeNotificationHistory = NativeNotificationExternalHistory(receipt.threadId,
            (previous?.receipts.orEmpty() + receipt).distinct().takeLast(256),
            previous?.turnStatuses.orEmpty())
        if (receipt.turnId !in remoteControlledTurnIds && pendingNativeNotifications.values.any {
                it.threadId == receipt.threadId && it.eventId == receipt.eventId && it.payloadSha256 == receipt.payloadSha256
            }) {
            nativeNotificationTurnIds.add(receipt.turnId)
            nativeNotificationUnprovenTurnIds.remove(receipt.turnId)
            if (unknownActiveTurnId == receipt.turnId) identityOnlyActiveTurn = receipt.threadId to receipt.turnId
        }
        while (nativeNotificationTurnIds.size > 128) nativeNotificationTurnIds.remove(nativeNotificationTurnIds.first())
    }

    private fun settleNativeNotification(
        purpose: RequestPurpose.NotificationToolOutput,
        receipt: NativeNotificationDispatchReceipt,
    ) {
        val entry = pendingNativeNotifications.entries.firstOrNull { it.value === purpose } ?: return
        pendingNativeNotifications.remove(entry.key)
        if (pendingNativeNotifications.isEmpty()) {
            remoteControlledTurnIds.addAll(nativeNotificationUnprovenTurnIds)
            nativeNotificationUnprovenTurnIds.clear()
            while (remoteControlledTurnIds.size > 128) remoteControlledTurnIds.remove(remoteControlledTurnIds.first())
        }
        nativeNotificationDeadlines.remove(entry.key)?.cancel()
        retireNativeNotificationRequest(entry.key)
        runCatching { purpose.onReceipt(receipt) }
            .onFailure { protocolDiagnostics("notification_event_receipt_delivery_failed") }
    }

    private fun retireNativeNotificationRequest(id: RequestId) {
        retiredNativeNotificationRequests.add(id)
        while (retiredNativeNotificationRequests.size > 256) {
            retiredNativeNotificationRequests.remove(retiredNativeNotificationRequests.first())
        }
    }

    private fun invalidateNativeNotificationRequests() {
        val pending = pendingNativeNotifications.values.toList()
        pending.forEach { settleNativeNotification(it, NativeNotificationDispatchReceipt.OutcomeAmbiguous) }
        val ids = requestPurposes.filterValues {
            it is RequestPurpose.NotificationToolOutput || it is RequestPurpose.NotificationHistory
        }.keys.toList()
        ids.forEach { id ->
            correlator.discard(id); requestPurposes.remove(id); retireNativeNotificationRequest(id)
            nativeNotificationDeadlines.remove(id)?.cancel()
        }
    }

    private fun updateSessionPhase() {
        if (sessionPhase in setOf(
                ClientSessionPhase.AUTH_REQUIRED,
                ClientSessionPhase.LOGIN_PENDING,
                ClientSessionPhase.BOOTSTRAPPING,
                ClientSessionPhase.RECOVERING_THREAD,
                ClientSessionPhase.FAILED,
            )
        ) {
            return
        }
        sessionPhase = if (activeTurn != null || unknownActiveTurnId != null) {
            ClientSessionPhase.BUSY
        } else {
            ClientSessionPhase.READY
        }
    }

    private fun failSession(code: ClientProblemCode, retryable: Boolean) {
        invalidateNativeNotificationRequests()
        invalidateRealtime()
        cancelWorkInterruptDeadlines()
        invalidateSelectionContext()
        abortRuntimePluginTransactions()
        pluginReducer.onSessionLost()
        problem = CodexClientProblem(code, retryable)
        sessionPhase = ClientSessionPhase.FAILED
        notifyObservers()
    }

    private fun failPermanently(code: ClientProblemCode, retryable: Boolean) {
        invalidateNativeNotificationRequests()
        invalidateRealtime()
        cancelWorkInterruptDeadlines()
        invalidateRemoteControl()
        invalidateSelectionContext()
        abandonPendingDynamicCalls(clearRequestIds = false)
        abortRuntimePluginTransactions()
        problem = CodexClientProblem(code, retryable)
        runtimePhase = ClientRuntimePhase.FAILED
        sessionPhase = ClientSessionPhase.FAILED
        restartInFlight = false
        notifyObservers()
    }

    private fun setProblem(code: ClientProblemCode, retryable: Boolean) {
        problem = CodexClientProblem(code, retryable)
        notifyObservers()
    }

    private fun rememberTerminalTurn(
        threadId: String,
        turnId: String,
        status: TurnStatus,
    ) {
        if (status == TurnStatus.IN_PROGRESS) return
        retireWorkInterruptsFor(threadId, turnId)
        if (retainedActiveTurn?.let { it.threadId to it.turnId } == threadId to turnId) {
            retainedActiveTurn = null
        }
        if (identityOnlyActiveTurn == threadId to turnId) identityOnlyActiveTurn = null
        terminalTurnIds.add(turnId)
        terminalTurns[turnId] = ClientTerminalTurn(threadId, turnId, status)
        while (terminalTurnIds.size > MAX_TERMINAL_TURNS) {
            val oldest = terminalTurnIds.first()
            terminalTurnIds.remove(oldest)
            terminalTurns.remove(oldest)
        }
    }

    private fun readConfirmedSelection(): DispatchSelection {
        val stored = runCatching {
            DispatchSelection.from(settingsStore.read())
        }.getOrElse {
            DispatchSelection.from(HansSettings())
        }
        return catalog?.let { currentCatalog ->
            resolveDispatchSelection(
                models = currentCatalog.models,
                requestedModel = stored.model,
                requestedEffort = stored.effort,
                requestedServiceTier = stored.serviceTier,
            )
        } ?: stored
    }

    private fun trimOutbound() {
        while (outbound.size > MAX_OUTBOUND_MESSAGES) {
            val removable = outbound.entries.firstOrNull {
                it.value.status != OutboundMessageStatus.PENDING
            } ?: return
            outbound.remove(removable.key)
        }
    }

    private fun nextOperation(): Long {
        check(nextOperationId < Long.MAX_VALUE) { "Operation id sequence exhausted" }
        return nextOperationId++
    }

    private fun nextPluginOperationId(): String {
        check(nextPluginOperationId < Long.MAX_VALUE) { "Plugin operation id exhausted" }
        return "hans-plugin-${nextPluginOperationId++}"
    }

    private fun snapshotLocked(): CodexClientSnapshot {
        val timeline = buildTimeline()
        return CodexClientSnapshot(
            runtimePhase = runtimePhase,
            sessionPhase = sessionPhase,
            generation = generation,
            session = reducer.snapshot(),
            models = catalog?.models ?: emptyList(),
            deviceCodeLogin = deviceCodeLogin,
            outboundTimeline = outbound.values.map { it.snapshot() },
            timeline = timeline,
            pendingSelection = pendingSelection,
            pendingSettingsSelection = queuedSettingsSelection ?: pendingSettingsUpdate?.selection,
            workInterrupt = workInterruptSnapshot(),
            confirmedSelection = confirmedSelection,
            problem = problem,
            plugins = pluginReducer.snapshot(),
            remoteControl = remoteControl.snapshot,
            remoteControlProjectPath = sessionStore.workspacePath,
            remotePhoneToolsActive = remotePhoneTools?.hasActiveWork == true,
            remotePhoneToolsAvailable = phoneToolsMcpConfigured && runtimePhase == ClientRuntimePhase.READY,
            agentChannelHistoryRevision = agentChannelHistoryRevision,
            pendingDynamicToolCalls = pendingDynamicCalls.size,
            terminalTurns = terminalTurns.values.toList(),
            setupTurnIds = setupTurnIds.toSet(),
            bundledSetupBootstrapStatus = bundledSetupBootstrapStatus(),
            migrationReadiness = CodexMigrationReadiness(
                accountReadComplete = accountReadComplete,
                threadResumeConfirmed = reducer.snapshot().currentThreadId?.let { current ->
                    resumedThreadId == current
                } == true,
                memoryModeEnabledAck = reducer.snapshot().currentThreadId?.let { current ->
                    memoryModeEnabledThreadId == current
                } == true,
                activeTurn = migrationTurnOrToolWorkActive(
                    activeTurnPresent = activeTurn != null,
                    unknownActiveTurnPresent = unknownActiveTurnId != null,
                    pendingDispatchPresent = pendingDispatch != null || pendingSettingsUpdate != null,
                    pendingDynamicToolCallCount = pendingDynamicCalls.size,
                ),
                effectiveSelection = migrationEffectiveSelection,
            ),
        )
    }

    private fun workInterruptSnapshot(): ClientWorkInterruptSnapshot = ClientWorkInterruptSnapshot(
        phase = when {
            runtimePhase != ClientRuntimePhase.READY -> ClientWorkInterruptPhase.IDLE
            interruptibleTurnIdentity()?.let(::pendingInterruptFor) == true ->
                ClientWorkInterruptPhase.PENDING
            interruptibleTurnIdentity() != null ->
                ClientWorkInterruptPhase.AVAILABLE
            pendingDispatch != null || sessionPhase == ClientSessionPhase.BUSY ->
                ClientWorkInterruptPhase.AWAITING_TURN
            else -> ClientWorkInterruptPhase.IDLE
        },
        revision = workInterruptRevision,
    )

    private fun bundledSetupBootstrapStatus(): BundledSetupBootstrapStatus = when {
        !bundledSetupPlugin.enabled -> BundledSetupBootstrapStatus.DISABLED
        bundledSetupBootstrapPhase == BundledSetupBootstrapPhase.READY &&
            bundledSetupProofStillEffective() ->
            BundledSetupBootstrapStatus.READY
        bundledSetupBootstrapPhase == BundledSetupBootstrapPhase.READY ->
            BundledSetupBootstrapStatus.FAILED
        bundledSetupBootstrapPhase == BundledSetupBootstrapPhase.FAILED ->
            BundledSetupBootstrapStatus.FAILED
        else -> BundledSetupBootstrapStatus.PREPARING
    }

    private fun bundledSetupProofStillEffective(): Boolean {
        if (!signedIn) return false
        val domain = pluginReducer.snapshot()
        val provenHandle = bundledSetupProvenPluginHandle ?: return false
        val plugin = domain.plugins.singleOrNull { it.handle == provenHandle } ?: return false
        val skill = domain.skills.singleOrNull {
            it.name == BundledSetupPluginContract.QUALIFIED_SKILL_NAME
        } ?: return false
        return plugin.name == BundledSetupPluginContract.PLUGIN_NAME &&
            plugin.installed && plugin.enabled && skill.enabled && skill.path != null
    }

    private fun buildTimeline(): List<ClientTimelineItem> {
        val itemsByIdentity = LinkedHashMap<String, ClientTimelineItem>()
        fun put(item: ClientTimelineItem) {
            itemsByIdentity[timelineId(item.role, item.id)] = item
        }
        val session = reducer.snapshot()
        recoveredTimeline.forEach { recovered ->
            put(
                timelineItem(
                    id = recovered.id,
                    role = recovered.role,
                    text = recovered.text,
                    complete = recovered.complete,
                    status = recovered.status,
                    agentPhase = recovered.agentPhase,
                    turnId = recovered.turnId,
                ),
            )
            if (
                recovered.id.startsWith(SETUP_CLIENT_MESSAGE_PREFIX) &&
                recovered.turnId != null
            ) {
                rememberSetupTurn(recovered.turnId)
            }
        }
        outbound.values.filter { it.threadId == session.currentThreadId }.forEach { message ->
            val turnId = message.turnId
            val status = when (message.status) {
                OutboundMessageStatus.PENDING -> ClientTimelineStatus.PENDING
                OutboundMessageStatus.SENT -> ClientTimelineStatus.SENT
                OutboundMessageStatus.FAILED -> ClientTimelineStatus.FAILED
            }
            put(timelineItem(
                id = message.clientMessageId,
                role = ClientTimelineRole.USER,
                text = message.displayText,
                complete = message.status != OutboundMessageStatus.PENDING,
                status = status,
                turnId = turnId,
            ))
            if (
                message.clientMessageId.startsWith(SETUP_CLIENT_MESSAGE_PREFIX) &&
                turnId != null
            ) {
                rememberSetupTurn(turnId)
            }
        }
        session.threads.filter { it.threadId == session.currentThreadId }.forEach { thread ->
            thread.messages.forEach { message ->
                put(timelineItem(
                    id = message.itemId,
                    role = ClientTimelineRole.HANS,
                    text = message.text,
                    complete = message.complete,
                    status = if (message.complete) {
                        ClientTimelineStatus.COMPLETE
                    } else {
                        ClientTimelineStatus.STREAMING
                    },
                    agentPhase = message.phase,
                    turnId = message.turnId,
                ))
            }
            thread.tools.forEach { tool ->
                val toolText = buildString {
                    append(tool.label)
                    tool.output?.takeIf(String::isNotBlank)?.let { output ->
                        append('\n')
                        append(output)
                    }
                }
                val status = when (tool.status) {
                    ai.hans.standard.codex.ToolStatus.IN_PROGRESS ->
                        ClientTimelineStatus.IN_PROGRESS
                    ai.hans.standard.codex.ToolStatus.COMPLETED ->
                        ClientTimelineStatus.COMPLETE
                    ai.hans.standard.codex.ToolStatus.FAILED -> ClientTimelineStatus.FAILED
                    ai.hans.standard.codex.ToolStatus.DECLINED -> ClientTimelineStatus.DECLINED
                }
                put(timelineItem(
                    id = tool.itemId,
                    role = ClientTimelineRole.TOOL,
                    text = toolText,
                    complete = tool.complete,
                    status = status,
                    turnId = tool.turnId,
                ))
                if (
                    tool.type == "dynamicToolCall" &&
                    (
                        SETUP_TOOL_LABEL_PREFIXES.any(tool.label::startsWith) ||
                            tool.label in UNIQUE_SETUP_TOOL_LABELS
                    )
                ) {
                    rememberSetupTurn(tool.turnId)
                }
            }
        }
        val items = itemsByIdentity.values.toList()
        val visibleMetadata = items.mapTo(HashSet()) { item ->
            timelineId(item.role, item.id)
        }
        while (timelineMetadata.size > MAX_TIMELINE_METADATA) {
            val removable = timelineMetadata.keys.firstOrNull { it !in visibleMetadata } ?: break
            timelineMetadata.remove(removable)
        }
        return items.sortedBy(ClientTimelineItem::order)
    }

    private fun hydrateRecoveredTimeline(
        threadId: String,
        items: List<RecoveredConversationItem>,
    ): List<RecoveredTimelineEntry> = items.mapNotNull { item ->
        when (item) {
            is RecoveredConversationItem.User -> {
                val clientId = item.clientId ?: return@mapNotNull null
                val receipt = runCatching { visibleInputReceipts.read(threadId, clientId) }
                    .onFailure {
                        protocolDiagnostics("Visible input receipt could not be read")
                    }
                    .getOrNull() ?: return@mapNotNull null
                if (receipt.attachmentPlaceholder && !item.hasAttachment) {
                    return@mapNotNull null
                }
                val displayText = receipt.recoverDisplayText(item.textParts)
                    ?: return@mapNotNull null
                RecoveredTimelineEntry(
                    id = clientId,
                    role = ClientTimelineRole.USER,
                    text = displayText,
                    complete = true,
                    status = ClientTimelineStatus.SENT,
                    agentPhase = null,
                    turnId = item.turnId,
                )
            }
            is RecoveredConversationItem.Hans -> RecoveredTimelineEntry(
                id = item.itemId,
                role = ClientTimelineRole.HANS,
                text = item.text,
                complete = item.complete,
                status = if (item.complete) {
                    ClientTimelineStatus.COMPLETE
                } else {
                    ClientTimelineStatus.STREAMING
                },
                agentPhase = item.phase,
                turnId = item.turnId,
            )
        }
    }

    private fun timelineItem(
        id: String,
        role: ClientTimelineRole,
        text: String,
        complete: Boolean,
        status: ClientTimelineStatus,
        agentPhase: ai.hans.standard.codex.AgentMessagePhase? = null,
        turnId: String? = null,
    ): ClientTimelineItem {
        val stableId = timelineId(role, id)
        val metadata = ensureTimelineOrder(stableId)
        val fingerprint = "$text\u0000$complete\u0000${status.name}\u0000${agentPhase?.wireValue}"
        if (metadata.fingerprint != fingerprint) {
            metadata.fingerprint = fingerprint
            metadata.revision += 1
        }
        return ClientTimelineItem(
            id = id,
            role = role,
            text = text,
            order = metadata.order,
            revision = metadata.revision,
            complete = complete,
            status = status,
            agentPhase = agentPhase,
            turnId = turnId,
        )
    }

    private fun rememberSetupTurn(turnId: String) {
        if (turnId.isBlank()) return
        setupTurnIds.remove(turnId)
        setupTurnIds.add(turnId)
        while (setupTurnIds.size > MAX_SETUP_TURNS) {
            setupTurnIds.remove(setupTurnIds.first())
        }
    }

    private fun ensureTimelineOrder(stableId: String): TimelineMetadata =
        timelineMetadata.getOrPut(stableId) {
            check(nextTimelineOrder < Long.MAX_VALUE) { "Timeline order exhausted" }
            TimelineMetadata(order = nextTimelineOrder++)
        }

    private fun timelineId(role: ClientTimelineRole, id: String): String =
        "${role.name}:$id"

    private fun notifyObservers() {
        updateRemotePhoneToolAuthority()
        realtime.workState(generation, reducer.snapshot().currentThreadId,
            activeTurn?.turnId ?: unknownActiveTurnId, pendingDispatch != null)
        val value = snapshotLocked()
        publishPerformanceState(value.session)
        observers.toList().forEach { observer -> observer.onSnapshot(value) }
    }

    private fun performanceTurnStarted(turnId: String) {
        if (replayingPerformanceEvents || rehydrating || performanceObservedTurnId == turnId ||
            turnId in performanceTerminalTurnIds
        ) return
        performanceObservedTurnId = turnId
        performanceUserWaitReported = false
        performanceAssistantOutputObserved = false
        performanceEvent(PerformanceEvent.TURN_STARTED)
    }

    private fun performanceTurnCompleted(turnId: String) {
        if (replayingPerformanceEvents || rehydrating || turnId in performanceTerminalTurnIds ||
            (performanceObservedTurnId != null && performanceObservedTurnId != turnId)
        ) return
        performanceObservedTurnId = null
        performanceLastTerminalTurnId = turnId
        performanceTerminalTurnIds.add(turnId)
        while (performanceTerminalTurnIds.size > MAX_TERMINAL_TURNS) {
            performanceTerminalTurnIds.remove(performanceTerminalTurnIds.first())
        }
        performanceUserWaitReported = false
        performanceEvent(PerformanceEvent.TURN_COMPLETED)
    }

    private fun performanceEvent(event: PerformanceEvent) {
        if (performanceObserver === PerformanceSessionObserver.NONE || replayingPerformanceEvents || rehydrating) return
        runCatching {
            publishPerformanceState(reducer.snapshot())
            performanceObserver.onEvent(event)
        }
    }

    private fun publishPerformanceState(session: ai.hans.standard.codex.SessionUiSnapshot) {
        if (performanceObserver === PerformanceSessionObserver.NONE) return
        runCatching {
            val authenticated = session.account.phase == ai.hans.standard.codex.AccountPhase.SIGNED_IN
            if (performanceContextThreadId != session.currentThreadId) {
                performanceContextThreadId = session.currentThreadId
                val restoredThread = session.threads.singleOrNull { it.threadId == session.currentThreadId }
                val restored = restoredThread?.currentTurn
                performanceObservedTurnId = restored?.turnId.takeIf { restored?.status == TurnStatus.IN_PROGRESS }
                performanceLastTerminalTurnId = restored?.turnId.takeUnless { restored?.status == TurnStatus.IN_PROGRESS }
                performanceTerminalTurnIds.clear()
                performanceLastTerminalTurnId?.let { performanceTerminalTurnIds.add(it) }
                performanceUserWaitReported = restoredThread?.runtimeStatus?.let {
                    it.status == ai.hans.standard.codex.ThreadRuntimeStatus.ACTIVE && it.activeFlags.isNotEmpty()
                } == true
                performanceAssistantOutputObserved = restored != null
            }
            val phase = when {
                !authenticated || session.currentThreadId == null || rehydrating || replayingPerformanceEvents ||
                    runtimePhase != ClientRuntimePhase.READY || localRuntimeStopRequested ||
                    sessionPhase !in setOf(ClientSessionPhase.READY, ClientSessionPhase.BUSY) -> PerformancePhase.UNKNOWN
                pendingDispatch != null -> PerformancePhase.DISPATCH_PENDING
                performanceUserWaitReported -> PerformancePhase.WAITING_FOR_USER
                performanceObservedTurnId != null -> PerformancePhase.TURN_ACTIVE
                pendingDynamicCalls.isNotEmpty() -> PerformancePhase.UNKNOWN
                session.threads.singleOrNull { it.threadId == session.currentThreadId }?.let {
                    it.currentTurn == null && it.runtimeStatus.status == ai.hans.standard.codex.ThreadRuntimeStatus.ACTIVE
                } == true -> PerformancePhase.UNKNOWN
                else -> PerformancePhase.IDLE_BETWEEN_TURNS
            }
            performanceObserver.onState(
                generation.takeIf { authenticated }, session.currentThreadId.takeIf { authenticated }, phase,
            )
        }
    }

    private class PluginRecoveryEpoch(
        val generation: Long,
        val batchId: String,
    ) {
        @Volatile
        var cancelled: Boolean = false
    }

    private class PendingPluginRecoveryBatch(
        val epoch: PluginRecoveryEpoch,
        val claims: List<PluginInstallRecoveryClaim>,
        var expectedProofOperationId: String?,
        var stage: PluginRecoveryStage,
    ) {
        val pendingCompensations = ArrayDeque<PluginInstallRecoveryExecution.CompensationRequired>()
        var activeCompensation: PluginInstallRecoveryExecution.CompensationRequired? = null
        var blocked: Boolean = false
    }

    private enum class PluginRecoveryStage {
        AWAITING_INITIAL_LIST,
        EVALUATING_INITIAL_LIST,
        AWAITING_UNINSTALL,
        MARKING_UNINSTALL_ACCEPTED,
        AWAITING_ABSENCE_PROOF,
        EVALUATING_ABSENCE_PROOF,
    }

    private class PluginUninstallRecoveryEpoch(
        val generation: Long,
        val batchId: String,
        val installRecoveryBlocked: Boolean,
    ) {
        @Volatile
        var cancelled: Boolean = false
    }

    private class PendingPluginUninstallRecoveryBatch(
        val epoch: PluginUninstallRecoveryEpoch,
        val claims: List<PluginUninstallRecoveryClaim>,
    ) {
        var evaluating: Boolean = false
    }

    private sealed interface PluginUninstallRecoveryClaimOutcome {
        data object Cancelled : PluginUninstallRecoveryClaimOutcome
        data object Failed : PluginUninstallRecoveryClaimOutcome
        data class Loaded(val claims: List<PluginUninstallRecoveryClaim>) :
            PluginUninstallRecoveryClaimOutcome
    }

    private sealed interface PluginUninstallRecoveryEvaluationOutcome {
        data object Cancelled : PluginUninstallRecoveryEvaluationOutcome
        data class Completed(val executions: List<PluginUninstallExecution?>) :
            PluginUninstallRecoveryEvaluationOutcome
    }

    private sealed interface PluginRecoveryClaimOutcome {
        data object Cancelled : PluginRecoveryClaimOutcome
        data object Failed : PluginRecoveryClaimOutcome
        data class Loaded(val claims: List<PluginInstallRecoveryClaim>) : PluginRecoveryClaimOutcome
    }

    private data class PluginRecoveryEvaluation(
        val claim: PluginInstallRecoveryClaim,
        val execution: PluginInstallRecoveryExecution?,
    )

    private sealed interface PluginRecoveryEvaluationOutcome {
        data object Cancelled : PluginRecoveryEvaluationOutcome
        data class Completed(val evaluations: List<PluginRecoveryEvaluation>) :
            PluginRecoveryEvaluationOutcome
    }

    private sealed interface PluginRecoveryMutationOutcome {
        data object Cancelled : PluginRecoveryMutationOutcome
        data object Applied : PluginRecoveryMutationOutcome
        data object Failed : PluginRecoveryMutationOutcome
    }

    private sealed interface PluginRecoverySingleEvaluationOutcome {
        data object Cancelled : PluginRecoverySingleEvaluationOutcome
        data class Completed(val execution: PluginInstallRecoveryExecution?) :
            PluginRecoverySingleEvaluationOutcome
    }

    private sealed interface RequestPurpose {
        fun isPluginPurpose(): Boolean = this is PluginList || this is PluginRead ||
            this is PluginInstall || this is PluginRuntimeInstall ||
            this is PluginRuntimeSurfaceRead ||
            this is PluginRuntimeInstallProof || this is PluginUninstall ||
            this is PluginUninstallProof || this is PluginUninstallRecoveryList ||
            this is PluginInstallRecoveryList ||
            this is PluginInstallRecoveryUninstall ||
            this is PluginInstallRecoveryAbsenceProof ||
            this is PluginSkillConfig || this is MarketplaceUpgrade ||
            this is PluginApps || this is PluginSkills ||
            this is BundledMarketplaceAdd || this is BundledPluginList ||
            this is BundledPluginInstall || this is BundledPluginSkills

        fun isBundledSetupPurpose(): Boolean = this is BundledMarketplaceAdd ||
            this is BundledPluginList || this is BundledPluginInstall ||
            this is BundledPluginSkills

        fun setupMessageIdOrNull(): String? = when (this) {
            is TurnStart -> messageId
            is TurnSteer -> messageId
            is BoundaryInterrupt -> messageId
            else -> null
        }?.takeIf { it.startsWith(SETUP_CLIENT_MESSAGE_PREFIX) }

        fun pluginOperationId(): String = when (this) {
            is PluginList -> operationId
            is PluginRead -> operationId
            is PluginInstall -> operationId
            is PluginRuntimeSurfaceRead -> operationId
            is PluginRuntimeInstall -> operationId
            is PluginRuntimeInstallProof -> proofOperationId
            is PluginUninstall -> operationId
            is PluginUninstallProof -> proofOperationId
            is PluginSkillConfig -> operationId
            is MarketplaceUpgrade -> operationId
            is PluginApps -> operationId
            is PluginSkills -> operationId
            is BundledPluginList -> operationId
            is BundledPluginInstall -> operationId
            is BundledPluginSkills -> operationId
            BundledMarketplaceAdd -> throw CrossCorrelationException(
                "Bundled marketplace add has no catalog operation",
            )
            else -> throw CrossCorrelationException("Purpose is not a plugin operation")
        }

        data object AccountRead : RequestPurpose
        data object LoginStart : RequestPurpose
        data object Logout : RequestPurpose
        data object ModelList : RequestPurpose
        data class SettingsUpdate(val requestId: RequestId, val contextEpoch: Long) : RequestPurpose
        data class ThreadResume(val threadId: String) : RequestPurpose
        data class ThreadStart(val proof: FreshThreadBootstrapProof) : RequestPurpose
        data class ThreadMemoryEnable(val threadId: String,
            val freshProof: FreshThreadBootstrapProof? = null) : RequestPurpose
        data class ThreadMaterialize(val threadId: String, val proof: FreshThreadBootstrapProof) : RequestPurpose
        data class TurnStart(
            val messageId: String,
            val selection: DispatchSelection,
            val confirmsDefaultSelection: Boolean = true,
        ) : RequestPurpose

        data class TurnSteer(
            val messageId: String,
            val selection: DispatchSelection,
            val confirmsDefaultSelection: Boolean = true,
        ) : RequestPurpose

        data class NotificationToolOutput(
            val threadId: String,
            val eventId: String,
            val payloadSha256: String,
            val onReceipt: (NativeNotificationDispatchReceipt) -> Unit,
        ) : RequestPurpose

        data class NotificationHistory(val threadId: String) : RequestPurpose

        data class BoundaryInterrupt(
            val messageId: String,
            val interruptedTurnId: String,
        ) : RequestPurpose

        data class UserInterrupt(val threadId: String, val turnId: String) : RequestPurpose
        data object BundledMarketplaceAdd : RequestPurpose
        data class BundledPluginList(
            val operationId: String,
            val expectInstalled: Boolean,
        ) : RequestPurpose
        data class BundledPluginInstall(val operationId: String) : RequestPurpose
        data class BundledPluginSkills(val operationId: String) : RequestPurpose
        data class PluginList(val operationId: String) : RequestPurpose
        data class PluginRead(val operationId: String) : RequestPurpose
        data class PluginInstall(val operationId: String) : RequestPurpose
        data class PluginRuntimeSurfaceRead(
            val operationId: String,
            val locator: ai.hans.standard.plugins.PluginLocator,
            val runtimeRecord: PluginWireRecord,
            val declaration: PluginSurfaceDeclarationReceipt,
        ) : RequestPurpose
        data class PluginRuntimeInstall(val operationId: String) : RequestPurpose
        data class PluginRuntimeInstallProof(
            val installOperationId: String,
            val proofOperationId: String,
        ) : RequestPurpose
        data class PluginInstallRecoveryList(
            val batchId: String,
            val proofOperationId: String,
        ) : RequestPurpose
        data class PluginInstallRecoveryUninstall(
            val operationId: String,
            val pluginId: String,
        ) : RequestPurpose
        data class PluginInstallRecoveryAbsenceProof(
            val operationId: String,
            val proofOperationId: String,
        ) : RequestPurpose
        data class PluginUninstall(
            val operationId: String,
            val proofOperationId: String,
            val pluginId: String,
            val invalidatesBundledSetup: Boolean,
        ) : RequestPurpose
        data class PluginUninstallProof(
            val uninstallOperationId: String,
            val proofOperationId: String,
            val invalidatesBundledSetup: Boolean,
        ) : RequestPurpose
        data class PluginUninstallRecoveryList(
            val batchId: String,
        ) : RequestPurpose
        data class PluginSkillConfig(
            val operationId: String,
            val handle: PluginHandle,
            val skillName: String,
            val skillPath: String,
        ) : RequestPurpose
        data class MarketplaceUpgrade(val operationId: String) : RequestPurpose
        data class PluginApps(val operationId: String) : RequestPurpose
        data class PluginSkills(val operationId: String) : RequestPurpose
    }

    private enum class BundledSetupBootstrapPhase {
        IDLE,
        ADDING_MARKETPLACE,
        DISCOVERING,
        INSTALLING,
        PROVING_INSTALL,
        PROVING_SKILL,
        READY,
        FAILED,
    }

    private enum class RequestTransmissionResult {
        SENT,
        REJECTED_BEFORE_TRANSPORT,
        OUTCOME_AMBIGUOUS,
    }

    private data class NativeNotificationHistoryPage(val history: NativeNotificationExternalHistory?)

    private data class PendingDispatch(
        val clientMessageId: String,
        val input: List<CodexInput>,
        val selection: DispatchSelection,
        val options: DispatchOptions?,
        val confirmsDefaultSelection: Boolean = true,
    )

    private class PendingSettingsUpdate(
        val requestId: RequestId,
        val generation: Long,
        val contextEpoch: Long,
        val threadId: String,
        val selection: DispatchSelection,
        var eventSequenceFloor: Long,
    ) {
        var acknowledged = false
        var observedSelection: DispatchSelection? = null
        var deadline: SetupDispatchDeadline? = null
    }

    private data class PendingSetupDispatchDeadline(
        val requestId: RequestId,
        val messageId: String,
        val generation: Long,
        val cancellation: SetupDispatchDeadline,
    )

    private data class DynamicCallIdentity(
        val threadId: String,
        val turnId: String,
        val callId: String,
    ) {
        companion object {
            fun from(params: ai.hans.standard.codex.DynamicToolCallParams) =
                DynamicCallIdentity(params.threadId, params.turnId, params.callId)
        }
    }

    private class PendingDynamicCall(
        val requestId: ServerRequestId,
        val generation: Long,
        val identity: DynamicCallIdentity,
        val params: ai.hans.standard.codex.DynamicToolCallParams,
    ) : DynamicToolCancellation {
        private val cancelled = AtomicBoolean(false)

        // Published/consumed only under the controller monitor; cancellation is lock-free so
        // executor effect gates never need to acquire that monitor in the reverse lock order.
        var executionHandle: DynamicToolExecutionHandle? = null

        override fun isCancellationRequested(): Boolean = cancelled.get()

        fun requestCancellation() {
            cancelled.set(true)
        }
    }

    private data class DynamicToolDispatch(
        val pending: PendingDynamicCall,
        val executor: DynamicToolExecutor,
    )

    private data class MutableOutbound(
        val clientMessageId: String,
        val threadId: String,
        val displayText: String,
        var status: OutboundMessageStatus = OutboundMessageStatus.PENDING,
        var retryable: Boolean = false,
        var turnId: String? = null,
    ) {
        fun snapshot(): OutboundUserMessageUi = OutboundUserMessageUi(
            clientUserMessageId = clientMessageId,
            threadId = threadId,
            displayText = displayText,
            status = status,
            retryable = retryable,
            turnId = turnId,
        )
    }

    private data class BufferedEvent(
        val eventSequence: Long,
        val event: ServerEvent,
        val frameBytes: Int,
    )

    private data class TimelineMetadata(
        val order: Long,
        var revision: Long = 0,
        var fingerprint: String? = null,
    )

    private data class RecoveredTimelineEntry(
        val id: String,
        val role: ClientTimelineRole,
        val text: String,
        val complete: Boolean,
        val status: ClientTimelineStatus,
        val agentPhase: ai.hans.standard.codex.AgentMessagePhase?,
        val turnId: String?,
    )

    private companion object {
        const val MAX_BUFFERED_EVENTS = 256
        const val MAX_BUFFERED_EVENT_BYTES = 2 * 1024 * 1024
        const val MAX_OUTBOUND_MESSAGES = 256
        const val MAX_TIMELINE_METADATA = 4_096
        const val MAX_TERMINAL_TURNS = 256
        const val MAX_SETUP_TURNS = 256
        const val MAX_AUTOMATIC_RESTARTS = 1
        const val MAX_PENDING_DYNAMIC_CALLS = 16
        const val MAX_COMPLETED_DYNAMIC_REQUESTS = 256
        const val MAX_COMPLETED_DYNAMIC_RESULTS = 256
        const val MAX_PROTOCOL_DIAGNOSTIC_DETAIL_CHARS = 160
        const val DEFAULT_SETUP_DISPATCH_TIMEOUT_MILLIS = 30_000L
        const val MAX_SETUP_DISPATCH_TIMEOUT_MILLIS = 5 * 60_000L
        const val DEFAULT_PLUGIN_INSTALL_TIMEOUT_MILLIS = 5 * 60_000L
        const val MAX_PLUGIN_INSTALL_TIMEOUT_MILLIS = 30 * 60_000L
        const val SETUP_CLIENT_MESSAGE_PREFIX = "hans-setup-"
        val SETUP_DYNAMIC_NAMESPACES = setOf("hans_setup", "hans_profile")
        val SETUP_TOOL_LABEL_PREFIXES = listOf("hans_setup.", "hans_profile.")
        val UNIQUE_SETUP_TOOL_LABELS = setOf(
            "get_setup_state",
            "record_choice",
            "request_step_ui",
            "begin_key_capture",
            "read_key_capture",
            "begin_live_test",
            "read_live_test",
            "verify_step",
            "begin_interview",
            "record_answer",
            "propose_summary",
        )

        fun isValidClientMessageId(value: String): Boolean =
            value.isNotBlank() && value.length <= 256 && value.none(Char::isISOControl)
    }
}
