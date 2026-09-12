package ai.hans.standard.ui

import ai.hans.standard.BuildConfig
import ai.hans.standard.codex.AccountPhase
import ai.hans.standard.codex.ReasoningEffort
import ai.hans.standard.integration.ClientProblemCode
import ai.hans.standard.integration.ClientRuntimePhase
import ai.hans.standard.integration.ClientSessionPhase
import ai.hans.standard.integration.ClientTimelineRole
import ai.hans.standard.integration.ClientTimelineStatus
import ai.hans.standard.integration.CodexClientSnapshot
import ai.hans.standard.integration.resolveDispatchSelection
import ai.hans.standard.integration.supportedReasoningEfforts
import ai.hans.standard.phone.capabilities.LaunchProfileType
import ai.hans.standard.phone.capabilities.PERSONAL_LAUNCH_PROFILE_ID
import ai.hans.standard.plugins.PluginAvailability
import ai.hans.standard.plugins.PluginCard
import ai.hans.standard.plugins.PluginDisabledReason
import ai.hans.standard.plugins.PluginHandle
import ai.hans.standard.plugins.PluginOperationKind
import ai.hans.standard.plugins.PluginOperationStatus
import ai.hans.standard.settings.HansSettings
import ai.hans.standard.settings.ReadAloudMode
import ai.hans.standard.setup.HansSetupOutputSanitizer
import ai.hans.standard.network.InternetSnapshot
import ai.hans.standard.network.internetWorkNotice
import ai.hans.standard.voice.PendingDictation
import ai.hans.standard.voice.withoutVisibleInFlightReceipts
import ai.hans.standard.voice.audio.SpeechAudioRouteState
import ai.hans.standard.voice.realtime.LiveVoiceVoiceSelection
import java.net.URI

data class HansLocalUiState(
    val requestedDestination: HansDestination = HansDestination.CHAT,
    val text: String = "",
    val attachments: List<HansPendingAttachment> = emptyList(),
    /** Exact outbound awaiting a correlated App Server SENT result. */
    val pendingComposerMessageId: String? = null,
    val selectedPluginList: PluginListKind = PluginListKind.INSTALLED,
    /** Opaque handle chosen by this launcher instance; never inferred from plugin/read. */
    val selectedPluginHandle: PluginHandle? = null,
    val pendingModelId: String? = null,
    val pendingEffortId: String? = null,
    /** Null means no staged tier change; false explicitly stages the normal queue. */
    val pendingFastModeEnabled: Boolean? = null,
    val supportingMessage: String = "",
    val internet: InternetSnapshot = InternetSnapshot(),
    /** Stable local/transport notice; independent of Android's validation heuristic. */
    val connectionFailureMessage: String = "",
    val pendingDictations: List<PendingDictation> = emptyList(),
    val pendingDictationStorageUnavailable: Boolean = false,
    val apps: List<AppUiModel> = emptyList(),
    val appProfiles: List<AppProfileUiModel> = listOf(
        AppProfileUiModel(
            profileId = PERSONAL_LAUNCH_PROFILE_ID,
            type = LaunchProfileType.PERSONAL,
            locked = false,
        ),
    ),
    val privateSpace: PrivateSpaceUiState = PrivateSpaceUiState(),
    val appQuery: String = "",
    val appsLoading: Boolean = false,
    val appsErrorMessage: String = "",
    val speechCredentialStatus: SpeechCredentialUiStatus = SpeechCredentialUiStatus.MISSING,
    val dictationStatus: DictationUiStatus? = null,
    val dictationPreview: String = "",
    val liveVoiceStatus: LiveVoiceUiStatus? = null,
    /** Runtime-confirmed local WebRTC capture mute state. */
    val liveVoiceInputMuted: Boolean = false,
    /** Runtime-confirmed voice mapping captured for the current call. */
    val liveVoiceVoiceSelection: LiveVoiceVoiceSelection? = null,
    val speechAudioRoute: SpeechAudioRouteState = SpeechAudioRouteState(),
    val liveVoiceMessages: List<ChatMessageUiModel> = emptyList(),
    /** Validated local notification summaries; never part of the Codex thread history. */
    val notificationMessages: List<ChatMessageUiModel> = emptyList(),
    val actionKeyConfigured: Boolean = false,
    val modelToggleKeyConfigured: Boolean = false,
    val actionKeyCapturing: Boolean = false,
    val capturingModelToggleKey: Boolean = false,
    val actionKeyNotice: String = "",
    val mp01VendorActionConflict: Mp01VendorActionConflictUiState =
        Mp01VendorActionConflictUiState(),
    val capabilityAccess: List<CapabilityAccessUiModel> = emptyList(),
    val everydayAccessBundleActive: Boolean = false,
    val persistentAndroidConsents: List<PersistentAndroidConsentUiModel> = emptyList(),
    /** Last explicitly refreshed, verified Workbench inventory. Never populated by polling. */
    val workbench: WorkbenchUiState = WorkbenchUiState(),
    /** Last bounded read of the persistent automation store; this state never polls. */
    val automations: AutomationsUiState = AutomationsUiState(),
    /** Explicitly refreshed app-local state; projection never initializes the remote runtime. */
    val remoteWorker: RemoteWorkerSettingsUiState = RemoteWorkerSettingsUiState(),
    /** Last durable read of the app-private, user-confirmed transcription glossary. */
    val confirmedSttGlossary: ConfirmedSttGlossaryUiState = ConfirmedSttGlossaryUiState(),
    val sttLatency: SttLatencyUiState = SttLatencyUiState(),
    val remoteMcpPolicyReviewPending: Boolean = false,
    val revision: Long = 0,
)

data class HansPendingAttachment(
    val id: String,
    val label: String,
    val absolutePath: String,
    /** All representative frames from one video share one transactional import. */
    val importId: String? = null,
    /** Bounded, locally derived context (for example video metadata and completed transcript). */
    val contextText: String = "",
)

/** Pure projection from confirmed runtime state plus small, non-authoritative UI draft state. */
object HansClientUiProjector {
    fun project(
        client: CodexClientSnapshot?,
        local: HansLocalUiState,
        settings: HansSettings,
    ): HansUiState {
        if (client == null) {
            return HansUiState(
                destination = HansDestination.AUTH_GATE,
                authGate = AuthGateUiState(
                    stage = AuthGateStage.CHECKING,
                    internetNotice = local.internet.status.notice,
                ),
                chat = ChatUiState(
                    composer = ComposerUiState(
                        text = local.text,
                        enabled = false,
                        attachments = local.attachments.map {
                            ComposerAttachmentUiModel(it.id, it.label)
                        },
                    ),
                    runtimeStatus = RuntimeUiStatus.CONNECTING,
                    internetNotice = local.internet.status.notice,
                    connectionFailureMessage = local.connectionFailureMessage,
                    pendingDictations = local.pendingDictations,
                    dictationStatus = local.dictationStatus,
                    dictationPreview = local.dictationPreview,
                    liveVoiceStatus = local.liveVoiceStatus,
                    liveVoiceInputMuted = local.liveVoiceInputMuted,
                    liveVoiceVoiceSelection = local.liveVoiceVoiceSelection,
                    speechAudioRoute = local.speechAudioRoute,
                    cameraHoldToTalkEnabled = settings.cameraHoldToTalkEnabled,
                ),
                settings = settingsState(null, local, settings),
            )
        }

        val authenticated = client.session.account.phase == AccountPhase.SIGNED_IN
        val showAuthGate = !authenticated ||
            client.runtimePhase == ClientRuntimePhase.FAILED ||
            client.sessionPhase in setOf(
                ClientSessionPhase.BOOTSTRAPPING,
                ClientSessionPhase.AUTH_REQUIRED,
                ClientSessionPhase.LOGIN_PENDING,
                ClientSessionPhase.RECOVERING_THREAD,
                ClientSessionPhase.FAILED,
            )
        val destination = if (showAuthGate) {
            HansDestination.AUTH_GATE
        } else {
            local.requestedDestination.takeUnless { it == HansDestination.AUTH_GATE }
                ?: HansDestination.CHAT
        }

        val clientTimeline = client.timeline
            .sortedBy { it.order }
            .map { item ->
                val author = when (item.role) {
                    ClientTimelineRole.USER -> ChatMessageAuthor.USER
                    ClientTimelineRole.HANS -> ChatMessageAuthor.HANS
                    ClientTimelineRole.SYSTEM,
                    ClientTimelineRole.TOOL,
                    -> ChatMessageAuthor.SYSTEM
                }
                val projectedText = when {
                    item.text.isNotBlank() && item.status == ClientTimelineStatus.FAILED ->
                        "${item.text}\nNicht gesendet."
                    item.text.isNotBlank() -> item.text
                    item.role == ClientTimelineRole.TOOL -> "Telefonaktion läuft"
                    else -> ""
                }
                val setupTurn = item.turnId != null && item.turnId in client.setupTurnIds
                val text = when {
                    // A conversational setup must never expose the implementation transcript.
                    // Codex can legitimately inspect the bundled setup skill or call shell and
                    // dynamic tools before composing its next question. Those receipts remain in
                    // the App Server history for diagnostics, but are not user-facing messages.
                    setupTurn && item.role in setOf(
                        ClientTimelineRole.SYSTEM,
                        ClientTimelineRole.TOOL,
                    ) -> ""
                    item.role == ClientTimelineRole.HANS ->
                        // Keep Markdown destinations until the rich-text projection. Removing
                        // them here would make the visible link label impossible to activate.
                        HansSetupOutputSanitizer.sanitizeAssistantText(projectedText, setupTurn)
                    else -> projectedText
                }
                ChatMessageUiModel(
                    id = item.id,
                    author = author,
                    text = text,
                    revision = item.revision,
                    complete = item.complete,
                )
            }
            .filter { it.text.isNotBlank() }
        val timeline = mergeAnchoredLocalMessages(
            clientMessages = clientTimeline,
            localMessages = local.liveVoiceMessages + local.notificationMessages,
        )

        val dictationOwnsInput = local.dictationStatus.blocksComposerInput()
        val canCompose = !dictationOwnsInput &&
            local.pendingComposerMessageId == null && authenticated &&
            client.runtimePhase == ClientRuntimePhase.READY &&
            client.sessionPhase in setOf(ClientSessionPhase.READY, ClientSessionPhase.BUSY)
        val isWorking = client.sessionPhase == ClientSessionPhase.BUSY || client.timeline.any {
            it.status in setOf(
                ClientTimelineStatus.PENDING,
                ClientTimelineStatus.STREAMING,
                ClientTimelineStatus.IN_PROGRESS,
            )
        }

        return HansUiState(
            destination = destination,
            authGate = authState(client, local.supportingMessage).copy(
                internetNotice = internetWorkNotice(local.internet.status, hasActiveWork = false),
            ),
            chat = ChatUiState(
                messages = timeline,
                composer = ComposerUiState(
                    text = local.text,
                    enabled = canCompose,
                    attachments = local.attachments.map {
                        ComposerAttachmentUiModel(it.id, it.label)
                    },
                ),
                runtimeStatus = runtimeStatus(client),
                isWorking = isWorking,
                internetNotice = internetWorkNotice(local.internet.status, isWorking),
                connectionFailureMessage = listOfNotNull(
                    local.connectionFailureMessage.takeIf(String::isNotBlank),
                    client.problem?.code?.userMessage(),
                    "Der Speicher für ungesendete Sprachtexte kann nicht gelesen werden. Er wurde nicht überschrieben; bitte nicht erneut aufnehmen, bis das behoben ist."
                        .takeIf { local.pendingDictationStorageUnavailable },
                ).distinct().joinToString("\n"),
                pendingDictations = local.pendingDictations.withoutVisibleInFlightReceipts(client.outboundTimeline),
                dictationStatus = local.dictationStatus,
                dictationPreview = local.dictationPreview,
                liveVoiceStatus = local.liveVoiceStatus,
                liveVoiceInputMuted = local.liveVoiceInputMuted,
                liveVoiceVoiceSelection = local.liveVoiceVoiceSelection,
                speechAudioRoute = local.speechAudioRoute,
                cameraHoldToTalkEnabled = settings.cameraHoldToTalkEnabled,
                timelineRevision = client.timeline.sumOf { it.revision + 1L } +
                    local.liveVoiceMessages.sumOf { it.revision + 1L } +
                    local.notificationMessages.sumOf { it.revision + 1L } +
                    local.revision,
            ),
            apps = AppsUiState(
                apps = local.apps,
                profiles = local.appProfiles,
                query = local.appQuery,
                loading = local.appsLoading,
                errorMessage = local.appsErrorMessage,
                privateSpace = local.privateSpace,
            ),
            plugins = pluginsState(
                client = client,
                selectedList = local.selectedPluginList,
                locallySelectedHandle = local.selectedPluginHandle,
                remoteMcpPolicyReviewPending = local.remoteMcpPolicyReviewPending,
            ),
            settings = settingsState(client, local, settings).copy(
                privateSpace = local.privateSpace,
            ),
            workbench = local.workbench,
            automations = local.automations,
        )
    }

    /**
     * Local voice transcripts and validated notification summaries are anchored to the Codex
     * item that was last visible when they arrived. A later user/assistant item therefore becomes
     * the real list tail instead of being hidden above a permanent local overlay.
     */
    private fun mergeAnchoredLocalMessages(
        clientMessages: List<ChatMessageUiModel>,
        localMessages: List<ChatMessageUiModel>,
    ): List<ChatMessageUiModel> {
        if (localMessages.isEmpty()) return clientMessages
        val clientIds = clientMessages.mapTo(linkedSetOf(), ChatMessageUiModel::id)
        val orderedLocal = localMessages.sortedWith(
            compareBy<ChatMessageUiModel> { it.localArrivalOrder }.thenBy { it.id },
        )
        val anchored = orderedLocal
            .filter { it.localTimelineAnchorId in clientIds }
            .groupBy(ChatMessageUiModel::localTimelineAnchorId)
        val result = ArrayList<ChatMessageUiModel>(clientMessages.size + localMessages.size)
        // Old on-disk records without an anchor are intentionally placed before current thread
        // history. They remain visible but can never cover newly arriving chat messages again.
        result += orderedLocal.filter { it.localTimelineAnchorId !in clientIds }
        clientMessages.forEach { message ->
            result += message
            anchored[message.id]?.let(result::addAll)
        }
        return result
    }

    private fun pluginsState(
        client: CodexClientSnapshot,
        selectedList: PluginListKind,
        locallySelectedHandle: PluginHandle?,
        remoteMcpPolicyReviewPending: Boolean,
    ): PluginsUiState {
        val operations = client.plugins.operations
        val pendingTarget = operations
            .lastOrNull { it.status == PluginOperationStatus.PENDING && it.target != null }
            ?.target
            ?.value
        val installedCards = client.plugins.plugins.filter(PluginCard::installed)
        val selectedCard = locallySelectedHandle?.let { localHandle ->
            installedCards.singleOrNull { it.handle == localHandle }
        }
        val selectedDetail = client.plugins.selectedPlugin?.takeIf { detail ->
            selectedCard != null && detail.handle == selectedCard.handle
        }
        val selectedRead = selectedCard?.let { card ->
            operations.lastOrNull {
                it.kind == PluginOperationKind.READ_PLUGIN && it.target == card.handle
            }
        }
        val skillChangePending = selectedCard != null && operations.any {
            it.kind == PluginOperationKind.CONFIGURE_SKILL &&
                it.target == selectedCard.handle &&
                it.status == PluginOperationStatus.PENDING
        }
        val uninstallOperationIndex = selectedCard?.let { card ->
            operations.indexOfLast {
                it.kind == PluginOperationKind.UNINSTALL_PLUGIN && it.target == card.handle
            }
        } ?: -1
        val uninstallOperation = operations.getOrNull(uninstallOperationIndex)
        val uninstallCatalogProof = operations
            .drop(uninstallOperationIndex + 1)
            .lastOrNull { it.kind == PluginOperationKind.REFRESH_PLUGINS }
        val uninstallPending = uninstallOperation?.status == PluginOperationStatus.PENDING
        val uninstallConfirmationPending =
            uninstallOperation?.status == PluginOperationStatus.SUCCESS &&
                uninstallCatalogProof?.status != PluginOperationStatus.SUCCESS &&
                uninstallCatalogProof?.status != PluginOperationStatus.FAILURE
        val latestDetailOperation = selectedCard?.let { card ->
            operations.lastOrNull {
                it.target == card.handle &&
                    it.kind in setOf(
                        PluginOperationKind.READ_PLUGIN,
                        PluginOperationKind.CONFIGURE_SKILL,
                        PluginOperationKind.UNINSTALL_PLUGIN,
                    )
            }
        }
        val detailOperationError = when {
            uninstallOperation?.status == PluginOperationStatus.SUCCESS &&
                uninstallCatalogProof?.status == PluginOperationStatus.SUCCESS ->
                "Die Deinstallation wurde vom Plugin-Katalog nicht bestätigt."
            uninstallOperation?.status == PluginOperationStatus.SUCCESS &&
                uninstallCatalogProof?.status == PluginOperationStatus.FAILURE ->
                "Der Plugin-Katalog konnte die Deinstallation nicht bestätigen."
            latestDetailOperation?.status == PluginOperationStatus.FAILURE -> when (
                latestDetailOperation.kind
            ) {
                PluginOperationKind.READ_PLUGIN ->
                    "Plugin-Details konnten nicht aktualisiert werden."
                PluginOperationKind.CONFIGURE_SKILL ->
                    "Die Skill-Änderung wurde nicht bestätigt."
                PluginOperationKind.UNINSTALL_PLUGIN ->
                    "Die Deinstallation wurde nicht bestätigt."
                else -> ""
            }
            else -> ""
        }
        val marketplaceOperationIndex = operations.indexOfLast {
            it.kind == PluginOperationKind.REFRESH_MARKETPLACE
        }
        val marketplaceOperation = operations.getOrNull(marketplaceOperationIndex)
        val marketplaceCatalogProof = operations
            .drop(marketplaceOperationIndex + 1)
            .lastOrNull { it.kind == PluginOperationKind.REFRESH_PLUGINS }
        val marketplaceRefreshing =
            marketplaceOperation?.status == PluginOperationStatus.PENDING ||
                marketplaceOperation?.status == PluginOperationStatus.SUCCESS &&
                marketplaceCatalogProof?.status !in setOf(
                    PluginOperationStatus.SUCCESS,
                    PluginOperationStatus.FAILURE,
                )
        val marketplaceMessage = when {
            marketplaceOperation?.status == PluginOperationStatus.FAILURE ->
                "Die Marketplace-Aktualisierung wurde nicht bestätigt."
            marketplaceOperation?.status == PluginOperationStatus.SUCCESS &&
                marketplaceCatalogProof?.status == PluginOperationStatus.FAILURE ->
                "Der Plugin-Katalog konnte nach der Marketplace-Aktualisierung nicht geladen werden."
            client.plugins.marketplaceUpgradeIssueCount > 0 ->
                "Die letzte Aktualisierung meldete ${client.plugins.marketplaceUpgradeIssueCount} Probleme."
            client.plugins.marketplaceLoadIssueCount > 0 ->
                "${client.plugins.marketplaceLoadIssueCount} Marketplaces konnten nicht geladen werden."
            else -> ""
        }
        return PluginsUiState(
            selectedList = selectedList,
            installed = installedCards.map(::pluginUiModel),
            available = client.plugins.plugins
                .filter { !it.installed && it.installable }
                .map(::pluginUiModel),
            operationPluginId = pendingTarget,
            pluginReadPending = operations.any {
                it.kind == PluginOperationKind.READ_PLUGIN &&
                    it.status == PluginOperationStatus.PENDING
            },
            selectedPluginId = selectedCard?.handle?.value,
            selectedPlugin = selectedDetail?.let { detail ->
                val card = selectedCard ?: return@let null
                PluginDetailUiModel(
                    id = detail.handle.value,
                    name = card.displayName?.takeIf(String::isNotBlank)
                        ?: card.name,
                    description = detail.description?.takeIf(String::isNotBlank)
                        ?: card.shortDescription.orEmpty(),
                    marketplaceName = card.marketplaceDisplayName,
                    apps = detail.apps.map { app ->
                        PluginAppUiModel(
                            id = app.id,
                            name = app.name,
                            description = app.description.orEmpty(),
                            connectionUrl = validatedHttpsPluginLinkOrNull(app.installUrl),
                        )
                    },
                    skills = detail.skills.map { skill ->
                        PluginSkillUiModel(
                            name = skill.name,
                            displayName = skill.displayName?.takeIf(String::isNotBlank)
                                ?: skill.name,
                            description = skill.shortDescription?.takeIf(String::isNotBlank)
                                ?: skill.description,
                            enabled = skill.enabled,
                        )
                    },
                    hookCount = detail.hookCount,
                    mcpServerCount = detail.mcpServerCount,
                    scheduledTaskCount = detail.scheduledTaskCount,
                    shareUrl = validatedHttpsPluginLinkOrNull(detail.shareUrl),
                    skillChangePending = skillChangePending,
                    uninstallPending = uninstallPending,
                    uninstallConfirmationPending = uninstallConfirmationPending,
                    operationErrorMessage = detailOperationError,
                )
            },
            selectedPluginLoading = selectedCard != null &&
                selectedDetail == null &&
                selectedRead?.status != PluginOperationStatus.FAILURE &&
                selectedRead?.status != PluginOperationStatus.SUCCESS,
            selectedPluginErrorMessage = if (
                selectedCard != null &&
                selectedDetail == null &&
                selectedRead?.status in setOf(
                    PluginOperationStatus.FAILURE,
                    PluginOperationStatus.SUCCESS,
                )
            ) {
                "Die Plugin-Details konnten nicht geladen werden."
            } else {
                ""
            },
            marketplaceRefreshing = marketplaceRefreshing,
            marketplaceMessage = marketplaceMessage,
            connectionAction = client.plugins.connectionAction?.let { action ->
                PluginConnectionActionUiModel(
                    pluginId = action.pluginId,
                    serverId = action.serverId,
                    kind = action.kind,
                    title = action.title,
                    message = action.message,
                    actionLabel = action.actionLabel,
                    policyReview = action.policyReview,
                )
            },
            remoteMcpPolicyReviewPending = remoteMcpPolicyReviewPending,
        )
    }

    private fun pluginUiModel(plugin: PluginCard): PluginUiModel = PluginUiModel(
        id = plugin.handle.value,
        name = plugin.displayName?.takeIf(String::isNotBlank) ?: plugin.name,
        description = plugin.shortDescription.orEmpty(),
        statusLabel = when {
            plugin.installed && plugin.enabled -> "Installiert und aktiv"
            plugin.installed -> "Installiert, aber deaktiviert"
            plugin.availability == PluginAvailability.DISABLED_BY_ADMIN -> when (
                plugin.disabledReason
            ) {
                PluginDisabledReason.PLAN_NOT_ELIGIBLE -> "Im aktuellen Tarif nicht verfügbar"
                PluginDisabledReason.REQUIRED_APP_UNAVAILABLE -> "Benötigte App nicht verfügbar"
                else -> "Nicht verfügbar"
            }
            plugin.installable -> "Kann installiert werden"
            else -> "Nicht installierbar"
        },
        actionEnabled = plugin.installed || plugin.installable,
    )

    private fun authState(
        client: CodexClientSnapshot,
        supportingMessage: String,
    ): AuthGateUiState {
        val deviceCode = client.deviceCodeLogin
        val sessionRecovery = AuthGateRecoveryPolicy.isAuthenticatedRecovery(client)
        val stage = when {
            client.sessionPhase == ClientSessionPhase.LOGIN_PENDING && deviceCode != null ->
                AuthGateStage.DEVICE_CODE_AWAITING
            client.sessionPhase == ClientSessionPhase.LOGIN_PENDING -> AuthGateStage.COMPLETING
            client.sessionPhase == ClientSessionPhase.AUTH_REQUIRED -> AuthGateStage.SIGNED_OUT
            client.sessionPhase == ClientSessionPhase.FAILED ||
                client.runtimePhase == ClientRuntimePhase.FAILED -> AuthGateStage.ERROR
            else -> AuthGateStage.CHECKING
        }
        return AuthGateUiState(
            stage = stage,
            userCode = deviceCode?.userCode.orEmpty(),
            verificationUri = deviceCode?.verificationUrl.orEmpty(),
            errorMessage = when {
                sessionRecovery && client.problem?.code == ClientProblemCode.THREAD_RECOVERY ->
                    "Du bist angemeldet. Versuche, das Gespräch erneut zu öffnen."
                else -> client.problem?.code?.userMessage().orEmpty()
            },
            errorTitle = when {
                !sessionRecovery -> "Anmeldung nicht abgeschlossen"
                client.problem?.code != ClientProblemCode.THREAD_RECOVERY ->
                    "Hans konnte nicht verbunden werden"
                client.session.currentThreadId == null -> "Gespräch konnte nicht gestartet werden"
                else -> "Gespräch konnte nicht wiederhergestellt werden"
            },
            sessionRecovery = sessionRecovery,
            supportingMessage = supportingMessage,
        )
    }

    private fun runtimeStatus(client: CodexClientSnapshot): RuntimeUiStatus = when {
        client.sessionPhase == ClientSessionPhase.AUTH_REQUIRED ||
            client.sessionPhase == ClientSessionPhase.LOGIN_PENDING -> RuntimeUiStatus.LOGIN_REQUIRED
        client.runtimePhase == ClientRuntimePhase.READY -> RuntimeUiStatus.ONLINE
        client.runtimePhase == ClientRuntimePhase.STARTING ||
            client.runtimePhase == ClientRuntimePhase.RESTARTING -> RuntimeUiStatus.CONNECTING
        else -> RuntimeUiStatus.OFFLINE
    }

    private fun settingsState(
        client: CodexClientSnapshot?,
        local: HansLocalUiState,
        settings: HansSettings,
    ): SettingsUiState {
        val advertised = client?.models.orEmpty()
        val selectableAdvertisedIds = advertised
            .asSequence()
            .filterNot { it.hidden }
            .filter { model ->
                model.supportedEfforts.any {
                    it.wireValue in HansSettings.SUPPORTED_REASONING_EFFORTS
                }
            }
            .mapTo(linkedSetOf()) { it.wireModel }
        val models = ModelUiOption.HANS_MODELS.filter { it.id in selectableAdvertisedIds }
        val requestedModel = client?.pendingSettingsSelection?.model
            ?: local.pendingModelId
            ?: client?.pendingSelection?.model
            ?: client?.confirmedSelection?.model
            ?: settings.model
        val requestedEffort = client?.pendingSettingsSelection?.effort
            ?: local.pendingEffortId
            ?.let(ReasoningEffort::of)
            ?: client?.pendingSelection?.effort
            ?: client?.confirmedSelection?.effort
            ?: ReasoningEffort.of(settings.reasoningEffort)
        val requestedServiceTier = client?.pendingSettingsSelection?.serviceTier ?: when (local.pendingFastModeEnabled) {
            true -> HansSettings.FAST_SERVICE_TIER
            false -> HansSettings.DEFAULT_SERVICE_TIER
            null -> if (client?.pendingSelection != null) {
                client.pendingSelection.serviceTier
            } else {
                client?.confirmedSelection?.serviceTier ?: settings.serviceTier
            }
        }
        val resolved = resolveDispatchSelection(
            models = advertised,
            requestedModel = requestedModel,
            requestedEffort = requestedEffort,
            requestedServiceTier = requestedServiceTier,
        )
        val effortModel = resolved?.model ?: requestedModel
        val efforts = supportedReasoningEfforts(advertised, effortModel).mapNotNull { effort ->
            ReasoningEffortUiOption.entries.firstOrNull { it.id == effort.wireValue }
        }
        val confirmed = client?.confirmedSelection
        val hasPlannedSelection = local.pendingModelId != null ||
            local.pendingEffortId != null ||
            local.pendingFastModeEnabled != null ||
            client?.pendingSelection != null ||
            (resolved != null && confirmed != null && resolved != confirmed)
        val pendingNotice = when {
            client?.pendingSettingsSelection != null && resolved != null ->
                "${modelLabel(resolved.model)} · ${effortLabel(resolved.effort.wireValue)}" +
                    (if (resolved.serviceTier == HansSettings.FAST_SERVICE_TIER) " · Fast" else "") +
                    " wird bestätigt …"
            client?.problem?.code == ClientProblemCode.SELECTION_UPDATE ->
                client.problem.code.userMessage()
            hasPlannedSelection && resolved != null -> {
                val speed = if (resolved.serviceTier == HansSettings.FAST_SERVICE_TIER) {
                    " · Fast"
                } else {
                    ""
                }
                "${modelLabel(resolved.model)} · ${effortLabel(resolved.effort.wireValue)}$speed wird mit deiner nächsten Nachricht angewendet."
            }
            client?.problem != null -> client.problem.code.userMessage()
            else -> ""
        }
        return SettingsUiState(
            models = models,
            reasoningEfforts = efforts,
            selectedModelId = confirmed?.model?.takeIf(selectableAdvertisedIds::contains),
            selectedReasoningEffortId = confirmed?.effort?.wireValue
                ?.takeIf { effort ->
                    supportedReasoningEfforts(advertised, confirmed.model)
                        .any { it.wireValue == effort }
                },
            fastModeAvailable = resolved?.model?.let { resolvedModel ->
                advertised.firstOrNull { it.wireModel == resolvedModel }
                    ?.serviceTiers
                    ?.any { it.id == HansSettings.FAST_SERVICE_TIER }
            } == true,
            fastModeEnabled = confirmed?.serviceTier == HansSettings.FAST_SERVICE_TIER,
            voices = HansSettings.SUPPORTED_VOICES.map { voice ->
                VoiceUiOption(voice, voice.replaceFirstChar { it.uppercase() })
            },
            selectedVoiceId = settings.voice,
            liveVoices = HansSettings.SUPPORTED_LIVE_VOICES.map { voice ->
                VoiceUiOption(voice, voice)
            },
            selectedLiveVoiceId = settings.liveVoice,
            activeLiveVoiceId = local.liveVoiceVoiceSelection
                ?.takeIf { local.liveVoiceStatus?.isActive == true }
                ?.effectiveRealtimeVoice,
            speechRate = settings.speechRate,
            readAloudMode = when (settings.readAloudMode) {
                ReadAloudMode.FINAL_ONLY -> ReadAloudUiMode.FINAL_ONLY
                ReadAloudMode.ALL_VISIBLE_ASSISTANT_MESSAGES -> ReadAloudUiMode.ALL_MESSAGES
            },
            speechCredentialStatus = local.speechCredentialStatus,
            cameraHoldToTalkEnabled = settings.cameraHoldToTalkEnabled,
            actionKey = ActionKeyUiState(
                configured = local.actionKeyConfigured,
                modelToggleConfigured = local.modelToggleKeyConfigured,
                capturing = local.actionKeyCapturing,
                capturingModelToggle = local.capturingModelToggleKey,
                notice = local.actionKeyNotice,
                dictationTrigger = settings.dictationKeyTrigger,
                mp01VendorConflict = local.mp01VendorActionConflict,
            ),
            capabilityAccess = local.capabilityAccess,
            everydayAccessBundleActive = local.everydayAccessBundleActive,
            persistentAndroidConsents = local.persistentAndroidConsents,
            runtimeNotice = pendingNotice,
            confirmedSttGlossary = local.confirmedSttGlossary,
            sttLatency = local.sttLatency,
            remoteWorker = local.remoteWorker,
            remoteControl = client?.remoteControl ?: ai.hans.standard.remotecontrol.RemoteControlSnapshot(),
            remoteControlThreadId = client?.session?.currentThreadId,
            remoteControlThreadName = client?.session?.threads?.firstOrNull {
                it.threadId == client.session.currentThreadId
            }?.name,
            codexUpdate = CodexUpdateUiState(
                bundledRuntimeVersion = BuildConfig.CODEX_RUNTIME_VERSION,
                runtimeReady = client?.runtimePhase == ClientRuntimePhase.READY,
                updateUrlConfigured = BuildConfig.HANS_UPDATE_URL.isNotBlank(),
            ),
        )
    }

    private fun ClientProblemCode.userMessage(): String = when (this) {
        ClientProblemCode.AUTHENTICATION -> "Die ChatGPT-Anmeldung muss erneuert werden."
        ClientProblemCode.MODEL_CATALOG -> "Die verfügbaren Codex-Modelle konnten nicht geladen werden."
        ClientProblemCode.SELECTION_UPDATE ->
            "Die Auswahl konnte nicht bestätigt werden. Bitte erneut auswählen."
        ClientProblemCode.THREAD_RECOVERY -> "Das Gespräch konnte noch nicht wiederhergestellt werden."
        ClientProblemCode.DISPATCH_REJECTED -> "Die Nachricht konnte noch nicht gesendet werden."
        ClientProblemCode.DISPATCH_AMBIGUOUS ->
            "Die Verbindung brach beim Senden ab. Hans sendet die Nachricht nicht automatisch erneut."
        ClientProblemCode.LOCAL_PERSISTENCE -> "Der lokale Hans-Speicher ist nicht verfügbar."
        ClientProblemCode.PROTOCOL_VERSION,
        ClientProblemCode.TRANSPORT_ORDER,
        ClientProblemCode.TRANSPORT_REJECTED,
        ClientProblemCode.RUNTIME_FAILED,
        ClientProblemCode.MALFORMED_SERVER_FRAME,
        -> "Die Codex-Runtime ist gerade nicht verfügbar."
    }

    private fun DictationUiStatus?.blocksComposerInput(): Boolean = this in setOf(
        DictationUiStatus.PREPARING,
        DictationUiStatus.LISTENING,
    )

    private fun modelLabel(model: String): String = ModelUiOption.HANS_MODELS
        .firstOrNull { it.id == model }
        ?.label
        ?: model

    private fun effortLabel(effort: String): String = ReasoningEffortUiOption.entries
        .firstOrNull { it.id == effort }
        ?.label
        ?: effort
}

/** Defense-in-depth for every plugin link that crosses into Android's browser surface. */
internal fun validatedHttpsPluginLinkOrNull(value: String?): String? {
    if (
        value.isNullOrBlank() ||
        value.length > 4_096 ||
        value.any(::isUnsafePluginLinkCharacter)
    ) {
        return null
    }
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (
        !uri.scheme.equals("https", ignoreCase = true) ||
        !uri.isAbsolute ||
        uri.isOpaque ||
        uri.host.isNullOrBlank() ||
        uri.userInfo != null
    ) {
        return null
    }
    return value
}

private fun isUnsafePluginLinkCharacter(character: Char): Boolean =
    character.isISOControl() ||
        character == '\\' ||
        character in '\u202A'..'\u202E' ||
        character in '\u2066'..'\u2069' ||
        character in setOf('\u061C', '\u200E', '\u200F')
