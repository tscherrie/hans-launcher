package ai.hans.standard.integration

import ai.hans.standard.codex.CodexInput
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.diagnostics.MeasuredDynamicToolExecutor
import ai.hans.standard.diagnostics.PerformanceDiagnostics
import ai.hans.standard.diagnostics.ToolPerformanceRecorder
import ai.hans.standard.plugins.PluginHandle
import ai.hans.standard.plugins.PluginInstallTransactionCoordinator
import ai.hans.standard.plugins.PluginRemoteMcpOAuthTarget
import ai.hans.standard.plugins.PluginRemoteMcpPolicyReviewSnapshot
import ai.hans.standard.plugins.PluginRemoteMcpPolicyReviewTarget
import ai.hans.standard.plugins.install.PluginInstallDeadline
import ai.hans.standard.plugins.runtime.PluginSurfaceEvidenceStager
import ai.hans.standard.plugins.uninstall.PluginUninstallTransactionCoordinator
import ai.hans.standard.runtime.IRuntimeService
import ai.hans.standard.settings.HansSettingsStore
import java.util.concurrent.Executor

/** Launcher-facing facade. Runtime service binding remains owned by the host Activity. */
internal class CodexSessionClient(
    runtime: IRuntimeService,
    sessionStore: CodexSessionStore,
    visibleInputReceipts: VisibleInputReceiptStore = VisibleInputReceiptStore.NONE,
    settingsStore: HansSettingsStore,
    clientMessageIds: ClientMessageIdFactory = ClientMessageIdFactory.UUIDS,
    developerInstructions: DeveloperInstructionsProvider = DeveloperInstructionsProvider.EMPTY,
    dynamicToolExecutor: DynamicToolExecutor? = null,
    protocolDiagnostics: (String) -> Unit = {},
    pluginInstallTransactions: PluginInstallTransactionCoordinator? = null,
    pluginUninstallTransactions: PluginUninstallTransactionCoordinator? = null,
    pluginSurfaceEvidenceStager: PluginSurfaceEvidenceStager? = null,
    pluginPreparationExecutor: Executor = Executor { command -> command.run() },
    pluginInstallDeadline: PluginInstallDeadline? = null,
) {
    private val toolPerformance = ToolPerformanceRecorder()
    private val phoneToolsServer = dynamicToolExecutor?.let { executor ->
        startPhoneToolsServerOrNull { ai.hans.standard.remotecontrol.PhoneToolsMcpServer(
            specs = { executor.specs },
            execute = { params, cancellation, onResult ->
                controller.executeRemotePhoneTool(params, cancellation, onResult)
            },
        ) }
    }
    private val controller: CodexSessionController = CodexSessionController(
        transport = BinderSessionRuntimeTransport(runtime, phoneToolsServer?.config),
        sessionStore = sessionStore,
        visibleInputReceipts = visibleInputReceipts,
        settingsStore = settingsStore,
        clientMessageIds = clientMessageIds,
        developerInstructions = developerInstructions,
        dynamicToolExecutor = dynamicToolExecutor?.let { MeasuredDynamicToolExecutor(it, toolPerformance) },
        protocolDiagnostics = protocolDiagnostics,
        pluginInstallTransactions = pluginInstallTransactions,
        pluginUninstallTransactions = pluginUninstallTransactions,
        pluginSurfaceEvidenceStager = pluginSurfaceEvidenceStager,
        pluginPreparationExecutor = pluginPreparationExecutor,
        pluginInstallDeadline = pluginInstallDeadline,
        phoneToolsMcpConfigured = phoneToolsServer != null,
        performanceObserver = object : PerformanceSessionObserver {
            override fun onState(
                generation: Long?, threadId: String?,
                phase: ai.hans.standard.diagnostics.PerformancePhase,
            ) {
                toolPerformance.observeContext(generation, threadId)
                toolPerformance.observePhase(phase)
            }

            override fun onEvent(event: ai.hans.standard.diagnostics.PerformanceEvent) {
                toolPerformance.recordEvent(event)
            }
        },
    )

    init {
        controller.addObserver(CodexClientObserver { snapshot ->
            val (generation, threadId) = PerformanceDiagnostics.context(snapshot)
            toolPerformance.observeContext(generation, threadId)
        })
    }

    fun performanceDiagnostics(command: String): String = synchronized(controller) {
        val snapshot = controller.snapshot()
        val (generation, threadId) = PerformanceDiagnostics.context(snapshot)
        toolPerformance.observeContext(generation, threadId)
        when (command) {
            "--hans-performance-start" -> toolPerformance.start()
            "--hans-performance-stop" -> toolPerformance.stop()
        }
        PerformanceDiagnostics.encode(snapshot, toolPerformance.snapshot())
    }

    fun addObserver(observer: CodexClientObserver) = controller.addObserver(observer)
    fun removeObserver(observer: CodexClientObserver) = controller.removeObserver(observer)
    fun snapshot(): CodexClientSnapshot = controller.snapshot()
    fun agentChannelRecoveredHistory(): AgentChannelRecoveredHistory? = controller.agentChannelRecoveredHistory()
    fun notificationExternalHistory(): NativeNotificationExternalHistory? = controller.notificationExternalHistory()
    fun refreshNotificationExternalHistory(expectedThreadId: String): Boolean =
        controller.refreshNotificationExternalHistory(expectedThreadId)
    fun dispatchNotificationEvent(
        message: NativeNotificationExternalMessage,
        expectedThreadId: String,
        beforeTransport: () -> Boolean,
        onReceipt: (NativeNotificationDispatchReceipt) -> Unit,
    ): NativeNotificationDispatchResult = controller.dispatchNotificationEvent(
        message, expectedThreadId, beforeTransport, onReceipt)
    /** Passive enum/counter receipt only; never initializes or restarts a session. */
    fun realtimeDiagnostics(): CodexRealtimeDiagnostics = controller.realtimeDiagnostics()
    fun voiceControlSessionIdFor(call: ai.hans.standard.codex.DynamicToolCallParams): String? =
        controller.voiceControlSessionIdFor(call)
    fun start() = controller.start()
    fun restart() = controller.restart()
    fun stop() = controller.stop()
    fun startRealtime(offerSdp: String, prompt: String, voice: String?, callbacks: CodexRealtimeCallbacks): CodexRealtimeCall? =
        controller.startRealtime(offerSdp, prompt, voice, callbacks)
    fun startRealtime(offerSdp: String, prompt: String, voice: String?, options: CodexRealtimeOptions,
        callbacks: CodexRealtimeCallbacks): CodexRealtimeCall? =
        controller.startRealtime(offerSdp, prompt, voice, options, callbacks)
    fun closeRemotePhoneTools() {
        controller.closeRemotePhoneTools()
        phoneToolsServer?.close()
    }
    fun refreshAccount(): Boolean = controller.refreshAccount()
    fun refreshModels(): Boolean = controller.refreshModels()

    fun remoteControlSettingsOpened(): Boolean = controller.remoteControlSettingsOpened()
    fun remoteControlEnable(): Boolean = controller.remoteControlEnable()
    fun remoteControlDisable(): Boolean = controller.remoteControlDisable()
    fun remoteControlPair(): Boolean = controller.remoteControlPair()
    fun remoteControlRefresh(): Boolean = controller.remoteControlRefresh()
    fun remoteControlRefreshClients(): Boolean = controller.remoteControlRefreshClients()
    fun remoteControlLoadMoreClients(): Boolean = controller.remoteControlLoadMoreClients()
    fun remoteControlRevoke(clientId: String): Boolean = controller.remoteControlRevoke(clientId)
    fun remoteControlCheckPairing(): Boolean = controller.remoteControlCheckPairing()
    fun updateSelection(selection: DispatchSelection): Boolean = controller.updateSelection(selection)
    fun loginWithDeviceCode(): Boolean = controller.loginWithDeviceCode()
    fun logout(): Boolean = controller.logout()
    fun interrupt(): Boolean = controller.interrupt()
    fun refreshPlugins(forceRefetch: Boolean = true): String? =
        controller.refreshPlugins(forceRefetch)
    fun readPlugin(handle: PluginHandle): String? = controller.readPlugin(handle)
    fun installPlugin(handle: PluginHandle): String? = controller.installPlugin(handle)
    internal fun resolveRemoteMcpOAuthTarget(
        pluginId: String,
        serverId: String,
    ): PluginRemoteMcpOAuthTarget? = controller.resolveRemoteMcpOAuthTarget(pluginId, serverId)
    internal fun markRemoteMcpOAuthConnected(pluginId: String, serverId: String): Boolean =
        controller.markRemoteMcpOAuthConnected(pluginId, serverId)
    internal fun resolveRemoteMcpPolicyReviewTarget(
        pluginId: String,
        serverId: String,
        expected: PluginRemoteMcpPolicyReviewSnapshot,
    ): PluginRemoteMcpPolicyReviewTarget? = controller.resolveRemoteMcpPolicyReviewTarget(
        pluginId,
        serverId,
        expected,
    )
    internal fun markRemoteMcpPolicyReviewed(target: PluginRemoteMcpPolicyReviewTarget): Boolean =
        controller.markRemoteMcpPolicyReviewed(target)
    internal fun retryRemoteMcpPluginInstall(pluginId: String, serverId: String): String? =
        controller.retryRemoteMcpPluginInstall(pluginId, serverId)
    fun uninstallPlugin(handle: PluginHandle): String? = controller.uninstallPlugin(handle)
    fun configurePluginSkill(handle: PluginHandle, skillName: String, enabled: Boolean): String? =
        controller.configurePluginSkill(handle, skillName, enabled)
    fun refreshMarketplaces(marketplaceName: String? = null): String? =
        controller.refreshMarketplaces(marketplaceName)
    fun refreshApps(forceRefetch: Boolean = true): String? =
        controller.refreshApps(forceRefetch)
    fun refreshSkills(forceReload: Boolean = true): String? =
        controller.refreshSkills(forceReload)
    fun retryBundledSetupBootstrap(): Boolean = controller.retryBundledSetupBootstrap()
    fun bundledSetupSkillInput(): CodexInput.Skill? = controller.bundledSetupSkillInput()

    fun dispatch(
        input: List<CodexInput>,
        selection: DispatchSelection? = null,
        clientUserMessageId: String? = null,
        dynamicToolTurnPolicy: DynamicToolTurnPolicy = DynamicToolTurnPolicy.ALLOW,
    ): String? = controller.dispatch(
        input,
        selection,
        clientUserMessageId,
        dynamicToolTurnPolicy,
    )

    fun dispatchAttempt(
        input: List<CodexInput>,
        selection: DispatchSelection? = null,
        clientUserMessageId: String? = null,
        dynamicToolTurnPolicy: DynamicToolTurnPolicy = DynamicToolTurnPolicy.ALLOW,
        expectedThreadId: String? = null,
    ): CodexDispatchAttemptResult = controller.dispatchAttempt(
        input,
        selection,
        clientUserMessageId,
        dynamicToolTurnPolicy,
        expectedThreadId = expectedThreadId,
    )
}
