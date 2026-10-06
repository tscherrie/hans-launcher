package ai.hans.standard.plugins

import ai.hans.standard.R

import ai.hans.standard.codex.CrossCorrelationException
import ai.hans.standard.codex.FrameLimitException
import ai.hans.standard.codex.ProtocolLimits
import ai.hans.standard.mcp.RemoteMcpConnectionReason
import ai.hans.standard.mcp.RemoteMcpConnectionRequest
import ai.hans.standard.mcp.RemoteMcpPolicyReviewRequest
import ai.hans.standard.mcp.oauth.RemoteMcpOAuthUiActions
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/**
 * Immutable-snapshot state machine for experimental plugin APIs. Wire values
 * remain private; UI operations use opaque handles and safe failure enums.
 */
internal class PluginCatalogReducer {
    private val records = LinkedHashMap<PluginHandle, PluginWireRecord>()
    private val operations = LinkedHashMap<String, MutableOperation>()
    private val apps = LinkedHashMap<String, AppCard>()
    private var skills: List<SkillCard> = emptyList()
    private var selectedPlugin: PluginDetailSnapshot? = null
    private var phase = PluginCatalogPhase.EMPTY
    private var revision = 0L
    private var marketplaceLoadIssueCount = 0
    private var marketplaceUpgradeIssueCount = 0
    private var appPageOperationId: String? = null
    private var appExpectedCursor: String? = null
    private val appSeenCursors = LinkedHashSet<String>()
    private val appPageAccumulator = LinkedHashMap<String, AppCard>()
    private var connectionAction: MutableConnectionAction? = null

    @Synchronized
    fun snapshot(): PluginDomainSnapshot = PluginDomainSnapshot(
        phase = phase,
        revision = revision,
        plugins = records.values.map(PluginWireRecord::card).sortedWith(
            compareByDescending<PluginCard> { it.installed }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName ?: it.name }
                .thenBy { it.pluginId },
        ),
        apps = apps.values.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER, AppCard::name).thenBy(AppCard::id),
        ),
        skills = skills.toList(),
        selectedPlugin = selectedPlugin,
        operations = operations.values.map(MutableOperation::snapshot),
        marketplaceLoadIssueCount = marketplaceLoadIssueCount,
        marketplaceUpgradeIssueCount = marketplaceUpgradeIssueCount,
        connectionAction = connectionAction?.publicSnapshot,
    )

    @Synchronized
    fun resolve(handle: PluginHandle): PluginLocator? = records[handle]?.locator

    /** Trusted runtime metadata only; local paths never enter the launcher snapshot. */
    @Synchronized
    fun resolveRuntimeRecord(handle: PluginHandle): PluginWireRecord? = records[handle]

    /**
     * Resolves the current opaque catalog handle and source only when the same explicit action is
     * still visible. Callers must re-read the manifest after this method returns.
     */
    @Synchronized
    fun resolveRemoteMcpOAuthTarget(
        pluginId: String,
        serverId: String,
        expectedKind: PluginConnectionActionKind,
    ): PluginRemoteMcpOAuthTarget? {
        val action = connectionAction?.takeIf {
            it.pluginId == pluginId &&
                it.serverId == serverId &&
                it.kind == expectedKind
        } ?: return null
        val request = (action.exact as? ExactConnectionAction.OAuth)?.request ?: return null
        val record = records[action.target]?.takeIf {
            it.card.pluginId == pluginId && !it.card.installed && it.card.installable
        } ?: return null
        val source = record.localSourcePath?.takeIf(String::isNotBlank) ?: return null
        return PluginRemoteMcpOAuthTarget(request, record.card.handle, source)
    }

    /** Exact review target only while the matching safe action remains visible. */
    @Synchronized
    fun resolveRemoteMcpPolicyReviewTarget(
        pluginId: String,
        serverId: String,
        expected: PluginRemoteMcpPolicyReviewSnapshot,
    ): PluginRemoteMcpPolicyReviewTarget? {
        val action = connectionAction?.takeIf {
            it.pluginId == pluginId &&
                it.serverId == serverId &&
                it.kind == PluginConnectionActionKind.REVIEW_REMOTE_MCP_POLICY &&
                it.publicSnapshot.policyReview == expected
        } ?: return null
        val exact = action.exact as? ExactConnectionAction.PolicyReview ?: return null
        val record = records[action.target]?.takeIf {
            it.card.pluginId == pluginId && !it.card.installed && it.card.installable
        } ?: return null
        val source = record.localSourcePath?.takeIf(String::isNotBlank) ?: return null
        return PluginRemoteMcpPolicyReviewTarget(
            request = exact.request,
            sourceSha256 = exact.sourceSha256,
            handle = record.card.handle,
            localSourcePath = source,
            runtimeRecord = record,
        )
    }

    @Synchronized
    fun resolveRemoteMcpRetryTarget(pluginId: String, serverId: String): PluginHandle? {
        val action = connectionAction?.takeIf {
            it.pluginId == pluginId &&
                it.serverId == serverId &&
                it.kind == PluginConnectionActionKind.RETRY_INSTALL
        } ?: return null
        return records[action.target]?.takeIf {
            it.card.pluginId == pluginId && !it.card.installed && it.card.installable &&
                !it.localSourcePath.isNullOrBlank()
        }?.card?.handle
    }

    @Synchronized
    fun resolveSkillPath(handle: PluginHandle, skillName: String): String? {
        val detail = selectedPlugin?.takeIf { it.handle == handle } ?: return null
        if (detail.skills.none { it.name == skillName }) return null
        return skills.singleOrNull { it.name == skillName }?.path
    }

    @Synchronized
    fun beginOperation(
        operationId: String,
        kind: PluginOperationKind,
        target: PluginHandle? = null,
    ): Boolean {
        requireBoundedToken(operationId, "Plugin operation id")
        if (operations.values.any {
                it.status == PluginOperationStatus.PENDING &&
                    it.kind == kind && it.target == target
            }
        ) {
            return false
        }
        trimOperations()
        if (operations.size >= ProtocolLimits.MAX_PLUGIN_OPERATIONS) return false
        if (target != null && target !in records) return false
        operations[operationId] = MutableOperation(operationId, kind, target)
        if (kind == PluginOperationKind.REFRESH_PLUGINS) {
            phase = PluginCatalogPhase.LOADING
        }
        if (kind == PluginOperationKind.REFRESH_APPS) {
            appPageOperationId = operationId
            appExpectedCursor = null
            appSeenCursors.clear()
            appPageAccumulator.clear()
        }
        bumpRevision()
        return true
    }

    @Synchronized
    fun applyPluginList(operationId: String, result: PluginListWireResult) {
        requirePending(operationId, PluginOperationKind.REFRESH_PLUGINS)
        val incoming = LinkedHashMap<PluginHandle, PluginWireRecord>()
        result.records.forEach { record ->
            val previous = incoming.putIfAbsent(record.card.handle, record)
            if (previous != null && previous != record) {
                throw CrossCorrelationException("Conflicting duplicate plugin")
            }
        }
        if (incoming.size > ProtocolLimits.MAX_PLUGINS_TOTAL) {
            throw FrameLimitException("Plugin catalog is too large")
        }
        records.clear()
        records.putAll(incoming)
        connectionAction = connectionAction?.takeIf { action ->
            incoming[action.target]?.let { record ->
                record.card.pluginId == action.pluginId &&
                    !record.card.installed &&
                    record.card.installable &&
                    !record.localSourcePath.isNullOrBlank()
            } == true
        }
        selectedPlugin = selectedPlugin?.takeIf { it.handle in records }
        marketplaceLoadIssueCount = result.marketplaceLoadIssueCount
        phase = PluginCatalogPhase.READY
        succeed(operationId)
    }

    @Synchronized
    fun applyPluginRead(operationId: String, result: PluginReadWireResult) {
        val operation = requirePending(operationId, PluginOperationKind.READ_PLUGIN)
        if (operation.target != result.detail.handle) {
            throw CrossCorrelationException("plugin/read returned a different plugin")
        }
        selectedPlugin = result.detail
        succeed(operationId)
    }

    @Synchronized
    fun applyPluginInstall(operationId: String, result: PluginInstallWireResult) {
        requirePending(operationId, PluginOperationKind.INSTALL_PLUGIN)
        // Auth metadata is already strictly decoded; plugin/list proves actual installation.
        result.appsNeedingAuth.size
        succeed(operationId)
    }

    /** Runtime-backed plugins keep the user operation pending until fresh list proof commits it. */
    @Synchronized
    fun acceptRuntimePluginInstall(operationId: String, result: PluginInstallWireResult) {
        requirePending(operationId, PluginOperationKind.INSTALL_PLUGIN)
        result.appsNeedingAuth.size
        bumpRevision()
    }

    @Synchronized
    fun proveRuntimePluginInstall(operationId: String) {
        requirePending(operationId, PluginOperationKind.INSTALL_PLUGIN)
        succeed(operationId)
    }

    /** Ends the attempted install before plugin/install and exposes only a safe user action. */
    @Synchronized
    fun requireRemoteMcpConnection(
        operationId: String,
        request: RemoteMcpConnectionRequest,
    ) {
        val operation = requirePending(operationId, PluginOperationKind.INSTALL_PLUGIN)
        val target = requireNotNull(operation.target) { "Remote MCP install has no target" }
        val record = records[target]
            ?: throw CrossCorrelationException("Remote MCP plugin target disappeared")
        if (record.card.pluginId != request.pluginId) {
            throw CrossCorrelationException("Remote MCP connection belongs to another plugin")
        }
        val ui = RemoteMcpOAuthUiActions.connect(request)
        operation.status = PluginOperationStatus.ACTION_REQUIRED
        operation.failure = null
        operation.retryable = false
        connectionAction = MutableConnectionAction(
            exact = ExactConnectionAction.OAuth(request),
            target = target,
            kind = PluginConnectionActionKind.CONNECT_REMOTE_MCP,
            publicSnapshot = PluginConnectionActionSnapshot(
                pluginId = ui.pluginId,
                serverId = ui.serverId,
                kind = PluginConnectionActionKind.CONNECT_REMOTE_MCP,
                titleResource = ui.titleResource,
                messageResource = ui.messageResource,
                actionLabelResource = ui.actionLabelResource,
            ),
        )
        bumpRevision()
    }

    /** Ends pre-effect preparation and publishes a one-shot, exact, secret-free review action. */
    @Synchronized
    fun requireRemoteMcpPolicyReview(
        operationId: String,
        request: RemoteMcpPolicyReviewRequest,
        sourceSha256: String,
    ) {
        require(sourceSha256.matches(Regex("[0-9a-f]{64}")))
        val operation = requirePending(operationId, PluginOperationKind.INSTALL_PLUGIN)
        val target = requireNotNull(operation.target) { "Remote MCP install has no target" }
        val record = records[target]
            ?: throw CrossCorrelationException("Remote MCP plugin target disappeared")
        if (record.card.pluginId != request.activationIdentity.pluginId) {
            throw CrossCorrelationException("Remote MCP policy belongs to another plugin")
        }
        val review = request.toPublicPolicyReview(sourceSha256)
        operation.status = PluginOperationStatus.ACTION_REQUIRED
        operation.failure = null
        operation.retryable = false
        connectionAction = MutableConnectionAction(
            exact = ExactConnectionAction.PolicyReview(request, sourceSha256),
            target = target,
            kind = PluginConnectionActionKind.REVIEW_REMOTE_MCP_POLICY,
            publicSnapshot = PluginConnectionActionSnapshot(
                pluginId = request.activationIdentity.pluginId,
                serverId = request.activationIdentity.serverId,
                kind = PluginConnectionActionKind.REVIEW_REMOTE_MCP_POLICY,
                titleResource = R.string.presentation_plugin_policy_title,
                messageResource = R.string.presentation_plugin_policy_message,
                actionLabelResource = R.string.presentation_plugin_policy_action,
                policyReview = review,
            ),
        )
        bumpRevision()
    }

    /** OAuth completion never resumes installation; it only replaces Connect with explicit Retry. */
    @Synchronized
    fun markRemoteMcpConnected(pluginId: String, serverId: String): Boolean {
        val current = connectionAction?.takeIf {
            it.pluginId == pluginId && it.serverId == serverId &&
                it.exact is ExactConnectionAction.OAuth
        } ?: records.values
            .singleOrNull {
                it.card.pluginId == pluginId &&
                    !it.card.installed &&
                    it.card.installable &&
                    !it.localSourcePath.isNullOrBlank()
            }
            ?.let { record ->
                // The browser can recreate LauncherActivity after Android reclaimed the process.
                // The callback contains only the exact persisted challenge identity, while the
                // next explicit retry re-runs source/manifest/credential proof from scratch.
                val request = RemoteMcpConnectionRequest(
                    pluginId = pluginId,
                    serverId = serverId,
                    reason = RemoteMcpConnectionReason.MISSING,
                )
                MutableConnectionAction(
                    exact = ExactConnectionAction.OAuth(request),
                    target = record.card.handle,
                    kind = PluginConnectionActionKind.CONNECT_REMOTE_MCP,
                    publicSnapshot = RemoteMcpOAuthUiActions.connect(request).let { ui ->
                        PluginConnectionActionSnapshot(
                            pluginId = ui.pluginId,
                            serverId = ui.serverId,
                            kind = PluginConnectionActionKind.CONNECT_REMOTE_MCP,
                            titleResource = ui.titleResource,
                            messageResource = ui.messageResource,
                            actionLabelResource = ui.actionLabelResource,
                        )
                    },
                )
            }
            ?: return false
        val record = records[current.target]?.takeIf {
            it.card.pluginId == pluginId &&
                !it.card.installed &&
                it.card.installable &&
                !it.localSourcePath.isNullOrBlank()
        } ?: return false
        val ui = RemoteMcpOAuthUiActions.retryInstall(pluginId, serverId)
        connectionAction = current.copy(
            target = record.card.handle,
            kind = PluginConnectionActionKind.RETRY_INSTALL,
            publicSnapshot = PluginConnectionActionSnapshot(
                pluginId = ui.pluginId,
                serverId = ui.serverId,
                kind = PluginConnectionActionKind.RETRY_INSTALL,
                titleResource = ui.titleResource,
                messageResource = ui.messageResource,
                actionLabelResource = ui.actionLabelResource,
            ),
        )
        bumpRevision()
        return true
    }

    /** Policy approval never installs. It exposes a distinct user-triggered retry action. */
    @Synchronized
    fun markRemoteMcpPolicyReviewed(
        request: RemoteMcpPolicyReviewRequest,
        sourceSha256: String,
    ): Boolean {
        val current = connectionAction?.takeIf {
            it.kind == PluginConnectionActionKind.REVIEW_REMOTE_MCP_POLICY &&
                it.exact == ExactConnectionAction.PolicyReview(request, sourceSha256)
        } ?: return false
        val record = records[current.target]?.takeIf {
            it.card.pluginId == request.activationIdentity.pluginId &&
                !it.card.installed && it.card.installable && !it.localSourcePath.isNullOrBlank()
        } ?: return false
        connectionAction = current.copy(
            target = record.card.handle,
            kind = PluginConnectionActionKind.RETRY_INSTALL,
            publicSnapshot = PluginConnectionActionSnapshot(
                pluginId = request.activationIdentity.pluginId,
                serverId = request.activationIdentity.serverId,
                kind = PluginConnectionActionKind.RETRY_INSTALL,
                titleResource = R.string.presentation_plugin_policy_saved,
                messageResource = R.string.presentation_plugin_install_not_resumed,
                actionLabelResource = R.string.presentation_plugin_retry_install,
            ),
        )
        bumpRevision()
        return true
    }

    @Synchronized
    fun isPending(operationId: String, kind: PluginOperationKind): Boolean =
        operations[operationId]?.let {
            it.kind == kind && it.status == PluginOperationStatus.PENDING
        } == true

    @Synchronized
    fun applyPluginUninstall(operationId: String, result: PluginUninstallWireResult) {
        requirePending(operationId, PluginOperationKind.UNINSTALL_PLUGIN)
        succeed(operationId)
    }

    @Synchronized
    fun applyMarketplaceUpgrade(operationId: String, result: MarketplaceUpgradeWireResult) {
        requirePending(operationId, PluginOperationKind.REFRESH_MARKETPLACE)
        marketplaceUpgradeIssueCount = result.issueCount
        succeed(operationId)
    }

    /** Returns the next cursor, or null after publishing the fully merged app list. */
    @Synchronized
    fun applyAppPage(operationId: String, result: AppListPageWireResult): String? {
        requirePending(operationId, PluginOperationKind.REFRESH_APPS)
        if (appPageOperationId != operationId || result.requestedCursor != appExpectedCursor) {
            throw CrossCorrelationException("app/list cursor does not match pending refresh")
        }
        result.apps.forEach { app ->
            val previous = appPageAccumulator.putIfAbsent(app.id, app)
            if (previous != null && previous != app) {
                throw CrossCorrelationException("Conflicting duplicate app")
            }
        }
        if (appPageAccumulator.size > ProtocolLimits.MAX_APPS_TOTAL) {
            throw FrameLimitException("Merged app list is too large")
        }
        val cursor = result.nextCursor
        if (cursor != null && !appSeenCursors.add(cursor)) {
            throw CrossCorrelationException("app/list cursor loop detected")
        }
        appExpectedCursor = cursor
        if (cursor == null) {
            apps.clear()
            apps.putAll(appPageAccumulator)
            clearAppPaging()
            succeed(operationId)
        } else {
            bumpRevision()
        }
        return cursor
    }

    @Synchronized
    fun applyAppListUpdated(updated: List<AppCard>) {
        if (updated.size > ProtocolLimits.MAX_APPS_TOTAL) {
            throw FrameLimitException("Updated app list is too large")
        }
        val incoming = LinkedHashMap<String, AppCard>()
        updated.forEach { app ->
            val previous = incoming.putIfAbsent(app.id, app)
            if (previous != null && previous != app) {
                throw CrossCorrelationException("Conflicting duplicate updated app")
            }
        }
        apps.clear()
        apps.putAll(incoming)
        bumpRevision()
    }

    @Synchronized
    fun applySkills(operationId: String, updated: List<SkillCard>) {
        requirePending(operationId, PluginOperationKind.REFRESH_SKILLS)
        skills = updated.toList()
        succeed(operationId)
    }

    @Synchronized
    fun applySkillConfig(
        operationId: String,
        handle: PluginHandle,
        skillName: String,
        skillPath: String,
        result: SkillConfigWriteWireResult,
    ) {
        val operation = requirePending(operationId, PluginOperationKind.CONFIGURE_SKILL)
        if (operation.target != handle) {
            throw CrossCorrelationException("Skill configuration returned for another plugin")
        }
        val globalIndex = skills.indexOfFirst { it.name == skillName && it.path == skillPath }
        if (globalIndex < 0) {
            throw CrossCorrelationException("Configured skill no longer matches the catalog")
        }
        skills = skills.toMutableList().also { updated ->
            updated[globalIndex] = updated[globalIndex].copy(enabled = result.effectiveEnabled)
        }
        val detail = selectedPlugin
        if (detail?.handle != handle || detail.skills.none { it.name == skillName }) {
            throw CrossCorrelationException("Configured skill no longer matches plugin detail")
        }
        selectedPlugin = detail.copy(
            skills = detail.skills.map { skill ->
                if (skill.name == skillName) {
                    skill.copy(enabled = result.effectiveEnabled)
                } else {
                    skill
                }
            },
        )
        succeed(operationId)
    }

    @Synchronized
    fun fail(
        operationId: String,
        failure: PluginOperationFailure,
        retryable: Boolean,
    ) {
        val operation = operations[operationId] ?: return
        if (operation.status != PluginOperationStatus.PENDING) return
        operation.status = PluginOperationStatus.FAILURE
        operation.failure = failure
        operation.retryable = retryable
        if (operation.kind == PluginOperationKind.REFRESH_PLUGINS) {
            phase = if (records.isEmpty()) PluginCatalogPhase.FAILED else PluginCatalogPhase.STALE
        }
        if (operation.kind == PluginOperationKind.REFRESH_APPS &&
            appPageOperationId == operationId
        ) {
            clearAppPaging()
        }
        bumpRevision()
    }

    @Synchronized
    fun onSessionLost() {
        operations.values.filter { it.status == PluginOperationStatus.PENDING }.forEach {
            it.status = PluginOperationStatus.FAILURE
            it.failure = PluginOperationFailure.TRANSPORT_AMBIGUOUS
            it.retryable = false
        }
        clearAppPaging()
        phase = if (records.isEmpty()) PluginCatalogPhase.FAILED else PluginCatalogPhase.STALE
        bumpRevision()
    }

    private fun requirePending(
        operationId: String,
        expectedKind: PluginOperationKind,
    ): MutableOperation {
        val operation = operations[operationId]
            ?: throw CrossCorrelationException("Plugin operation is not pending")
        if (operation.kind != expectedKind || operation.status != PluginOperationStatus.PENDING) {
            throw CrossCorrelationException("Plugin operation correlation mismatch")
        }
        return operation
    }

    private fun succeed(operationId: String) {
        val operation = operations[operationId]
            ?: throw CrossCorrelationException("Plugin operation is not pending")
        operation.status = PluginOperationStatus.SUCCESS
        operation.failure = null
        operation.retryable = false
        bumpRevision()
    }

    private fun clearAppPaging() {
        appPageOperationId = null
        appExpectedCursor = null
        appSeenCursors.clear()
        appPageAccumulator.clear()
    }

    private fun trimOperations() {
        while (operations.size >= ProtocolLimits.MAX_PLUGIN_OPERATIONS) {
            val terminal = operations.entries.firstOrNull {
                it.value.status != PluginOperationStatus.PENDING
            } ?: return
            operations.remove(terminal.key)
        }
    }

    private fun bumpRevision() {
        check(revision < Long.MAX_VALUE) { "Plugin revision exhausted" }
        revision += 1
    }

    private data class MutableOperation(
        val operationId: String,
        val kind: PluginOperationKind,
        val target: PluginHandle?,
        var status: PluginOperationStatus = PluginOperationStatus.PENDING,
        var retryable: Boolean = false,
        var failure: PluginOperationFailure? = null,
    ) {
        fun snapshot(): PluginOperationSnapshot = PluginOperationSnapshot(
            operationId = operationId,
            kind = kind,
            target = target,
            status = status,
            retryable = retryable,
            failure = failure,
        )
    }

    private data class MutableConnectionAction(
        val exact: ExactConnectionAction,
        val target: PluginHandle,
        val kind: PluginConnectionActionKind,
        val publicSnapshot: PluginConnectionActionSnapshot,
    ) {
        val pluginId: String
            get() = exact.pluginId
        val serverId: String
            get() = exact.serverId
    }

    private sealed interface ExactConnectionAction {
        val pluginId: String
        val serverId: String

        data class OAuth(val request: RemoteMcpConnectionRequest) : ExactConnectionAction {
            override val pluginId: String = request.pluginId
            override val serverId: String = request.serverId
        }

        data class PolicyReview(
            val request: RemoteMcpPolicyReviewRequest,
            val sourceSha256: String,
        ) : ExactConnectionAction {
            override val pluginId: String = request.activationIdentity.pluginId
            override val serverId: String = request.activationIdentity.serverId
        }
    }
}

/** Internal exact re-resolution receipt. It is never projected to Compose or persisted. */
internal data class PluginRemoteMcpOAuthTarget(
    val request: RemoteMcpConnectionRequest,
    val handle: PluginHandle,
    val localSourcePath: String,
)

/** Internal current-record proof. Paths and exact MCP request never reach a public snapshot. */
internal data class PluginRemoteMcpPolicyReviewTarget(
    val request: RemoteMcpPolicyReviewRequest,
    val sourceSha256: String,
    val handle: PluginHandle,
    val localSourcePath: String,
    val runtimeRecord: PluginWireRecord,
)

private fun RemoteMcpPolicyReviewRequest.toPublicPolicyReview(
    sourceSha256: String,
): PluginRemoteMcpPolicyReviewSnapshot = PluginRemoteMcpPolicyReviewSnapshot(
    configurationDigest = activationIdentity.configurationDigest,
    sourceDigest = sourceSha256,
    catalogDigest = catalogDigest,
    policyStoreRevision = policyStoreRevision,
    tools = tools.map { tool ->
        PluginRemoteMcpPolicyToolSnapshot(
            name = tool.name,
            title = tool.title
                ?.filterNot(Char::isISOControl)
                ?.trim()
                ?.takeIf(String::isNotEmpty),
            declaredHints = PluginRemoteMcpDeclaredHintsSnapshot(
                readOnly = tool.declaredHints.readOnly,
                destructive = tool.declaredHints.destructive,
                idempotent = tool.declaredHints.idempotent,
                openWorld = tool.declaredHints.openWorld,
            ),
            metadataDigest = tool.metadataDigest,
            description = tool.description,
        )
    },
)
