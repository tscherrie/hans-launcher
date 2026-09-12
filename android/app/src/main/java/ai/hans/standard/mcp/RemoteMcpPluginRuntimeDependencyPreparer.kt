package ai.hans.standard.mcp

import ai.hans.standard.plugins.EmptyPluginRuntimeDependencyTransaction
import ai.hans.standard.plugins.PluginRuntimeDependencyComponentRecoveryProvider
import ai.hans.standard.plugins.PluginRuntimeDependencyPreparer
import ai.hans.standard.plugins.PluginRuntimeDependencyRecoveryResult
import ai.hans.standard.plugins.PluginRuntimeDependencyTransaction
import ai.hans.standard.plugins.PluginRuntimePreparationCancellation
import ai.hans.standard.plugins.PluginRuntimeRequirements
import ai.hans.standard.plugins.requireBoundedToken
import ai.hans.standard.plugins.install.PluginDependencyRecoveryComponent
import ai.hans.standard.plugins.install.PluginDependencyRecoveryComponentKind
import ai.hans.standard.plugins.install.PluginDependencyRecoveryDescriptor
import ai.hans.standard.plugins.install.PluginInstallJournalPhase
import ai.hans.standard.plugins.install.PluginInstallLocalRecoverability
import ai.hans.standard.plugins.runtime.OAuthCredentialHandle
import ai.hans.standard.plugins.runtime.PluginSurfaceManifestLoadResult
import ai.hans.standard.plugins.runtime.PluginSurfaceManifestLoader
import ai.hans.standard.plugins.runtime.RemoteMcpRequirement
import java.io.File

internal fun interface RemoteMcpPluginRequirementSource {
    fun load(pluginId: String, sourceRoot: File): List<RemoteMcpRequirement>
}

internal class PluginSurfaceRemoteMcpRequirementSource(
    private val manifests: PluginSurfaceManifestLoader,
) : RemoteMcpPluginRequirementSource {
    override fun load(pluginId: String, sourceRoot: File): List<RemoteMcpRequirement> =
        when (val loaded = manifests.load(pluginId, sourceRoot)) {
            PluginSurfaceManifestLoadResult.NotDeclared -> emptyList()
            is PluginSurfaceManifestLoadResult.Declared -> loaded.manifest.remoteMcpServers
            is PluginSurfaceManifestLoadResult.Rejected ->
                throw RemoteMcpPluginPreparationFailure("mcp_surface_manifest_rejected")
        }
}

internal fun interface RemoteMcpPreflightDiscoverer {
    /** Opens an isolated session that is never registered with the runtime router. */
    fun discover(
        definition: RemoteMcpActivationDefinition,
        cancellation: PluginRuntimePreparationCancellation,
    ): RemoteMcpDiscoveryReceipt
}

internal class SessionRemoteMcpPreflightDiscoverer(
    private val sessions: RemoteMcpSessionFactory,
) : RemoteMcpPreflightDiscoverer {
    override fun discover(
        definition: RemoteMcpActivationDefinition,
        cancellation: PluginRuntimePreparationCancellation,
    ): RemoteMcpDiscoveryReceipt {
        cancellation.throwIfCancellationRequested()
        val session = sessions.open(definition)
        val cancellationRegistration = cancellation.onCancel(session::close)
        return try {
            session.discover(RemoteMcpCancellation(cancellation::isCancellationRequested)).also {
                cancellation.throwIfCancellationRequested()
            }
        } finally {
            runCatching(cancellationRegistration::close)
            runCatching(session::close)
        }
    }
}

internal enum class RemoteMcpConnectionReason {
    MISSING,
    EXPIRED,
    STALE_IDENTITY,
    CREDENTIAL_STORE_UNAVAILABLE,
}

/** Safe handoff to the later browser-OAuth/UI flow. It deliberately contains no endpoint/handle. */
internal data class RemoteMcpConnectionRequest(
    val pluginId: String,
    val serverId: String,
    val reason: RemoteMcpConnectionReason,
) {
    init {
        requireBoundedToken(pluginId, "Remote MCP plugin")
        requireBoundedToken(serverId, "Remote MCP server")
    }
}

internal class RemoteMcpConnectionRequiredException(
    val request: RemoteMcpConnectionRequest,
) : IllegalStateException("mcp_connection_required")

internal class RemoteMcpPluginPreparationFailure(
    val code: String,
) : IllegalStateException(code) {
    init {
        require(code.matches(Regex("mcp_[a-z0-9_]{1,80}")))
    }
}

internal enum class RemoteMcpPluginDeactivationResult {
    DEACTIVATED,
    ALREADY_ABSENT,
    STALE_IDENTITY,
    STALE_METADATA,
    TRANSACTION_PENDING,
    UNAVAILABLE,
}

/**
 * Receipt-scoped Remote MCP dependency owner used by the composite plugin transaction.
 *
 * Preflight can perform bounded HTTPS discovery, but it never publishes a router session. The
 * catalog and activation remain private PREPARED/COMMITTED receipts until [finalizeCommit].
 */
internal class RemoteMcpPluginRuntimeDependencyPreparer(
    private val requirements: RemoteMcpPluginRequirementSource,
    private val oauth: RemoteMcpOAuthVault,
    private val activations: RemoteMcpSessionRegistry,
    private val catalogs: RemoteMcpDiscoveryCatalogStore,
    private val preflight: RemoteMcpPreflightDiscoverer,
    private val toolPolicies: RemoteMcpToolPolicyStore,
) : PluginRuntimeDependencyPreparer, PluginRuntimeDependencyComponentRecoveryProvider {
    override fun prepare(
        requirements: PluginRuntimeRequirements,
        sourceRoot: File,
        cancellation: PluginRuntimePreparationCancellation,
    ): PluginRuntimeDependencyTransaction {
        val declared = this.requirements.load(requirements.pluginId, sourceRoot)
            .sortedBy(RemoteMcpRequirement::id)
        if (declared.isEmpty()) return EmptyPluginRuntimeDependencyTransaction
        require(declared.map(RemoteMcpRequirement::id).distinct().size == declared.size) {
            "Duplicate Remote MCP requirement"
        }

        val prepared = mutableListOf<RemoteMcpServerDependencyEntry>()
        try {
            declared.forEach { requirement ->
                cancellation.throwIfCancellationRequested()
                val definition = activationDefinition(requirements.pluginId, requirement)
                prepared += prepareServer(definition, cancellation)
            }
            return RemoteMcpPluginDependencyTransaction(prepared)
        } catch (failure: Throwable) {
            prepared.asReversed().forEach { entry ->
                runCatching(entry::rollback).onFailure(failure::addSuppressed)
            }
            throw failure
        }
    }

    override fun recover(
        pluginId: String,
        descriptor: PluginDependencyRecoveryComponent?,
        journalPhase: PluginInstallJournalPhase,
    ): PluginRuntimeDependencyRecoveryResult {
        val catalogSnapshot = catalogs.recoverySnapshot(pluginId)
        val activationStatus = activations.status()
        if (!catalogSnapshot.available || !activationStatus.available) {
            return without(PluginInstallLocalRecoverability.UNAVAILABLE)
        }
        if (descriptor == null) {
            val hasCatalog = catalogSnapshot.pending.isNotEmpty() || catalogSnapshot.finalized.isNotEmpty()
            val hasActivation = activationStatus.activations.any { it.pluginId == pluginId }
            return without(
                if (hasCatalog || hasActivation) {
                    PluginInstallLocalRecoverability.CHANGED
                } else {
                    PluginInstallLocalRecoverability.ABSENT
                },
            )
        }
        if (descriptor.kind != PluginDependencyRecoveryComponentKind.REMOTE_MCP_V1 ||
            !SAFE_COMPONENT_IDS.matches(descriptor.componentId)
        ) {
            return without(PluginInstallLocalRecoverability.CHANGED)
        }
        val candidates = catalogSnapshot.pending + catalogSnapshot.finalized
        val catalog = candidates.singleOrNull { it.transactionId == descriptor.transactionId }
            ?: return without(
                if (candidates.any { it.identity.serverId == descriptor.componentId } ||
                    activationStatus.activations.any {
                        it.pluginId == pluginId && it.serverId == descriptor.componentId
                    }
                ) {
                    PluginInstallLocalRecoverability.CHANGED
                } else {
                    PluginInstallLocalRecoverability.ABSENT
                },
            )
        if (catalog.identity.pluginId != pluginId ||
            catalog.identity.serverId != descriptor.componentId ||
            catalog.metadataDigest != descriptor.stateDigest
        ) {
            return without(PluginInstallLocalRecoverability.CHANGED)
        }

        val activationObservation = observeActivation(catalog)
        if (activationObservation.state == DependencyStorePhase.UNAVAILABLE) {
            return without(PluginInstallLocalRecoverability.UNAVAILABLE)
        }
        if (activationObservation.state == DependencyStorePhase.CHANGED) {
            return without(PluginInstallLocalRecoverability.CHANGED)
        }
        val catalogPhase = catalog.phase.toDependencyPhase()
        val activationPhase = activationObservation.state
        val state = classifyRecovery(catalog, catalogPhase, activationPhase, journalPhase)
        if (state == PluginInstallLocalRecoverability.CHANGED) return without(state)

        val catalogReceipt = try {
            if (catalog.phase == RemoteMcpCatalogPhase.FINALIZED) {
                catalogs.receiptForFinalizedRecovery(catalog)
            } else {
                catalogs.receiptForRecovery(catalog)
            }
        } catch (_: Throwable) {
            return without(PluginInstallLocalRecoverability.CHANGED)
        }
        val entry = RemoteMcpServerDependencyEntry(
            identity = catalog.identity,
            component = descriptor,
            routeState = catalog.routeState,
            activationReceipt = activationObservation.receipt,
            activationMetadataDigest = catalog.activationMetadataDigest,
            activationPhase = activationPhase,
            catalogReceipt = catalogReceipt,
            catalogPhase = catalogPhase,
            activations = activations,
            catalogs = catalogs,
        )
        return PluginRuntimeDependencyRecoveryResult.recoverable(
            recoverability = state,
            transaction = RemoteMcpPluginDependencyTransaction(listOf(entry)),
        )
    }

    fun deactivateFinalized(
        identity: RemoteMcpActivationIdentity,
        expectedCatalogMetadataDigest: String,
    ): RemoteMcpPluginDeactivationResult {
        val catalogResult = catalogs.deactivate(identity, expectedCatalogMetadataDigest)
        when (catalogResult) {
            RemoteMcpCatalogDeactivationResult.STALE_IDENTITY ->
                return RemoteMcpPluginDeactivationResult.STALE_IDENTITY
            RemoteMcpCatalogDeactivationResult.STALE_METADATA ->
                return RemoteMcpPluginDeactivationResult.STALE_METADATA
            RemoteMcpCatalogDeactivationResult.TRANSACTION_PENDING ->
                return RemoteMcpPluginDeactivationResult.TRANSACTION_PENDING
            RemoteMcpCatalogDeactivationResult.STORE_UNAVAILABLE ->
                return RemoteMcpPluginDeactivationResult.UNAVAILABLE
            RemoteMcpCatalogDeactivationResult.DEACTIVATED,
            RemoteMcpCatalogDeactivationResult.MISSING,
            -> Unit
        }
        return when (activations.deactivate(identity)) {
            RemoteMcpDeactivationResult.DEACTIVATED -> RemoteMcpPluginDeactivationResult.DEACTIVATED
            RemoteMcpDeactivationResult.MISSING -> if (
                catalogResult == RemoteMcpCatalogDeactivationResult.DEACTIVATED
            ) {
                RemoteMcpPluginDeactivationResult.DEACTIVATED
            } else {
                RemoteMcpPluginDeactivationResult.ALREADY_ABSENT
            }
            RemoteMcpDeactivationResult.STALE_IDENTITY ->
                RemoteMcpPluginDeactivationResult.STALE_IDENTITY
            RemoteMcpDeactivationResult.TRANSACTION_PENDING ->
                RemoteMcpPluginDeactivationResult.TRANSACTION_PENDING
            RemoteMcpDeactivationResult.REGISTRY_UNAVAILABLE ->
                RemoteMcpPluginDeactivationResult.UNAVAILABLE
        }
    }

    private fun prepareServer(
        definition: RemoteMcpActivationDefinition,
        cancellation: PluginRuntimePreparationCancellation,
    ): RemoteMcpServerDependencyEntry {
        val connection = connectionRequest(definition)
        if (connection != null) {
            if (definition.requirement.required) throw RemoteMcpConnectionRequiredException(connection)
            return prepareWithoutRoute(definition.identity, RemoteMcpCatalogRouteState.CONNECTION_REQUIRED)
        }

        val discovery = try {
            preflight.discover(definition, cancellation)
        } catch (failure: Throwable) {
            cancellation.throwIfCancellationRequested()
            val request = failure.connectionRequest(definition)
            if (definition.requirement.required) {
                if (request != null) throw RemoteMcpConnectionRequiredException(request)
                throw RemoteMcpPluginPreparationFailure("mcp_required_preflight_failed")
            }
            return prepareWithoutRoute(
                definition.identity,
                if (request == null) {
                    RemoteMcpCatalogRouteState.UNAVAILABLE
                } else {
                    RemoteMcpCatalogRouteState.CONNECTION_REQUIRED
                },
            )
        }
        cancellation.throwIfCancellationRequested()
        val reviewCatalog = try {
            RemoteMcpPolicyReviewCatalog.fromDiscovery(
                activationIdentity = definition.identity,
                allowedToolNames = definition.requirement.allowedTools,
                discovery = discovery,
            )
        } catch (failure: RemoteMcpFailure) {
            if (definition.requirement.required) {
                throw RemoteMcpPluginPreparationFailure(failure.code)
            }
            return prepareWithoutRoute(definition.identity, RemoteMcpCatalogRouteState.UNAVAILABLE)
        }
        val policySnapshot = toolPolicies.snapshot()
        if (!policySnapshot.available) {
            throw RemoteMcpPluginPreparationFailure("mcp_tool_policy_store_unavailable")
        }
        if (!reviewCatalog.hasExactPolicies(policySnapshot)) {
            throw RemoteMcpPolicyReviewRequiredException(
                reviewCatalog.request(policySnapshot.revision),
            )
        }
        // Session discovery deliberately reports every bounded remote tool separately from its
        // currently effective policy projection. Only after exact approval may the manifest
        // allowlist become route-eligible and enter the plugin transaction.
        val approvedDiscovery = RemoteMcpDiscoveryReceipt(
            tools = discovery.tools,
            allowedToolNames = definition.requirement.allowedTools,
        )

        val activationReceipt = activations.prepare(definition)
        try {
            val catalogReceipt = catalogs.prepare(
                RemoteMcpDiscoveryCatalogDefinition.ready(
                    activation = activationReceipt,
                    identity = definition.identity,
                    discovery = approvedDiscovery,
                ),
            )
            return RemoteMcpServerDependencyEntry.prepared(
                identity = definition.identity,
                routeState = RemoteMcpCatalogRouteState.READY,
                activationReceipt = activationReceipt,
                catalogReceipt = catalogReceipt,
                activations = activations,
                catalogs = catalogs,
            )
        } catch (failure: Throwable) {
            runCatching { activations.rollback(activationReceipt) }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    private fun prepareWithoutRoute(
        identity: RemoteMcpActivationIdentity,
        state: RemoteMcpCatalogRouteState,
    ): RemoteMcpServerDependencyEntry {
        val catalogReceipt = catalogs.prepare(
            RemoteMcpDiscoveryCatalogDefinition.withoutRoute(identity, state),
        )
        return RemoteMcpServerDependencyEntry.prepared(
            identity = identity,
            routeState = state,
            activationReceipt = null,
            catalogReceipt = catalogReceipt,
            activations = activations,
            catalogs = catalogs,
        )
    }

    private fun connectionRequest(
        definition: RemoteMcpActivationDefinition,
    ): RemoteMcpConnectionRequest? {
        val handle = definition.requirement.oauthHandle ?: return null
        val credentialIdentity = credentialIdentity(definition.identity, handle)
        val state = runCatching { oauth.state(credentialIdentity) }
            .getOrDefault(OAuthCredentialState.UNAVAILABLE)
        if (state == OAuthCredentialState.AVAILABLE) return null
        return RemoteMcpConnectionRequest(
            pluginId = definition.identity.pluginId,
            serverId = definition.identity.serverId,
            reason = state.connectionReason(),
        )
    }

    private fun Throwable.connectionRequest(
        definition: RemoteMcpActivationDefinition,
    ): RemoteMcpConnectionRequest? {
        val failure = this as? RemoteMcpFailure ?: return null
        val reason = when (failure.code) {
            "mcp_oauth_missing" -> RemoteMcpConnectionReason.MISSING
            "mcp_oauth_expired" -> RemoteMcpConnectionReason.EXPIRED
            "mcp_oauth_stale_identity" -> RemoteMcpConnectionReason.STALE_IDENTITY
            "mcp_oauth_vault_unavailable" -> RemoteMcpConnectionReason.CREDENTIAL_STORE_UNAVAILABLE
            else -> return null
        }
        return RemoteMcpConnectionRequest(
            pluginId = definition.identity.pluginId,
            serverId = definition.identity.serverId,
            reason = reason,
        )
    }

    private fun observeActivation(
        catalog: RemoteMcpDiscoveryCatalogRecoveryDescriptor,
    ): ActivationObservation {
        val transactionId = catalog.activationTransactionId
            ?: return ActivationObservation(DependencyStorePhase.ABSENT, null)
        val metadataDigest = catalog.activationMetadataDigest
            ?: return ActivationObservation(DependencyStorePhase.CHANGED, null)
        val pending = activations.pendingRecovery().filter { it.transactionId == transactionId }
        if (pending.size > 1) return ActivationObservation(DependencyStorePhase.CHANGED, null)
        pending.singleOrNull()?.let { activation ->
            if (activation.identity != catalog.identity ||
                activation.metadataDigest != metadataDigest
            ) {
                return ActivationObservation(DependencyStorePhase.CHANGED, null)
            }
            val receipt = runCatching { activations.receiptForRecovery(activation) }.getOrNull()
                ?: return ActivationObservation(DependencyStorePhase.CHANGED, null)
            return ActivationObservation(
                state = when (activation.phase) {
                    RemoteMcpActivationPhase.PREPARED -> DependencyStorePhase.PREPARED
                    RemoteMcpActivationPhase.COMMITTED -> DependencyStorePhase.COMMITTED
                    RemoteMcpActivationPhase.FINALIZED -> DependencyStorePhase.CHANGED
                },
                receipt = receipt,
            )
        }
        return when (activations.proveFinalized(catalog.identity, metadataDigest)) {
            RemoteMcpFinalizedActivationProof.EXACT ->
                ActivationObservation(DependencyStorePhase.FINALIZED, null)
            RemoteMcpFinalizedActivationProof.MISSING ->
                ActivationObservation(DependencyStorePhase.CHANGED, null)
            RemoteMcpFinalizedActivationProof.CHANGED ->
                ActivationObservation(DependencyStorePhase.CHANGED, null)
            RemoteMcpFinalizedActivationProof.UNAVAILABLE ->
                ActivationObservation(DependencyStorePhase.UNAVAILABLE, null)
        }
    }

    private fun classifyRecovery(
        catalog: RemoteMcpDiscoveryCatalogRecoveryDescriptor,
        catalogPhase: DependencyStorePhase,
        activationPhase: DependencyStorePhase,
        journalPhase: PluginInstallJournalPhase,
    ): PluginInstallLocalRecoverability {
        if (catalog.activationTransactionId == null) {
            if (activationPhase != DependencyStorePhase.ABSENT) {
                return PluginInstallLocalRecoverability.CHANGED
            }
            return when (catalogPhase) {
                DependencyStorePhase.PREPARED -> PluginInstallLocalRecoverability.PREPARED
                DependencyStorePhase.COMMITTED -> PluginInstallLocalRecoverability.COMMITTED
                DependencyStorePhase.FINALIZED -> if (journalPhase.allowsFinalizeRecovery()) {
                    PluginInstallLocalRecoverability.COMMITTED
                } else {
                    PluginInstallLocalRecoverability.CHANGED
                }
                else -> PluginInstallLocalRecoverability.CHANGED
            }
        }
        return when (catalogPhase) {
            DependencyStorePhase.PREPARED -> when (activationPhase) {
                DependencyStorePhase.PREPARED,
                DependencyStorePhase.COMMITTED,
                -> PluginInstallLocalRecoverability.PREPARED
                else -> PluginInstallLocalRecoverability.CHANGED
            }
            DependencyStorePhase.COMMITTED -> when (activationPhase) {
                DependencyStorePhase.PREPARED -> PluginInstallLocalRecoverability.PREPARED
                DependencyStorePhase.COMMITTED -> PluginInstallLocalRecoverability.COMMITTED
                else -> PluginInstallLocalRecoverability.CHANGED
            }
            DependencyStorePhase.FINALIZED -> when (activationPhase) {
                DependencyStorePhase.COMMITTED,
                DependencyStorePhase.FINALIZED,
                -> if (journalPhase.allowsFinalizeRecovery()) {
                    PluginInstallLocalRecoverability.COMMITTED
                } else {
                    PluginInstallLocalRecoverability.CHANGED
                }
                else -> PluginInstallLocalRecoverability.CHANGED
            }
            else -> PluginInstallLocalRecoverability.CHANGED
        }
    }

    private fun without(
        state: PluginInstallLocalRecoverability,
    ) = PluginRuntimeDependencyRecoveryResult.withoutTransaction(state)
}

private class RemoteMcpPluginDependencyTransaction(
    private val entries: List<RemoteMcpServerDependencyEntry>,
) : PluginRuntimeDependencyTransaction {
    override val resolvedEntrypointIds: Set<String> = emptySet()
    override val availableCapabilityIds: Set<String> = emptySet()
    override val recoveryDescriptor: PluginDependencyRecoveryDescriptor =
        PluginDependencyRecoveryDescriptor.composite(entries.map(RemoteMcpServerDependencyEntry::component))

    @Synchronized
    override fun commit() {
        entries.forEach(RemoteMcpServerDependencyEntry::commit)
    }

    @Synchronized
    override fun rollback() {
        var failure: Throwable? = null
        entries.asReversed().forEach { entry ->
            runCatching(entry::rollback).onFailure { current ->
                if (failure == null) failure = current else failure?.addSuppressed(current)
            }
        }
        failure?.let { throw it }
    }

    @Synchronized
    override fun finalizeCommit() {
        entries.forEach(RemoteMcpServerDependencyEntry::finalizeCommit)
    }
}

private class RemoteMcpServerDependencyEntry(
    private val identity: RemoteMcpActivationIdentity,
    val component: PluginDependencyRecoveryComponent,
    private val routeState: RemoteMcpCatalogRouteState,
    private val activationReceipt: RemoteMcpActivationReceipt?,
    private val activationMetadataDigest: String?,
    private var activationPhase: DependencyStorePhase,
    private val catalogReceipt: RemoteMcpDiscoveryCatalogReceipt,
    private var catalogPhase: DependencyStorePhase,
    private val activations: RemoteMcpSessionRegistry,
    private val catalogs: RemoteMcpDiscoveryCatalogStore,
) {
    fun commit() {
        if (catalogPhase == DependencyStorePhase.FINALIZED &&
            activationPhase in setOf(DependencyStorePhase.FINALIZED, DependencyStorePhase.ABSENT)
        ) {
            return
        }
        when (activationPhase) {
            DependencyStorePhase.PREPARED -> {
                activations.commit(checkNotNull(activationReceipt))
                activationPhase = DependencyStorePhase.COMMITTED
            }
            DependencyStorePhase.COMMITTED,
            DependencyStorePhase.FINALIZED,
            DependencyStorePhase.ABSENT,
            -> Unit
            else -> error("Remote MCP activation cannot be committed")
        }
        if (catalogPhase == DependencyStorePhase.PREPARED) {
            catalogs.commit(catalogReceipt)
            catalogPhase = DependencyStorePhase.COMMITTED
        }
        check(catalogPhase == DependencyStorePhase.COMMITTED)
    }

    fun rollback() {
        when (catalogPhase) {
            DependencyStorePhase.PREPARED,
            DependencyStorePhase.COMMITTED,
            -> catalogs.rollback(catalogReceipt)
            DependencyStorePhase.FINALIZED -> require(
                catalogs.deactivate(identity, component.stateDigest) in setOf(
                    RemoteMcpCatalogDeactivationResult.DEACTIVATED,
                    RemoteMcpCatalogDeactivationResult.MISSING,
                ),
            ) { "Remote MCP finalized catalog changed before rollback" }
            DependencyStorePhase.ABSENT -> Unit
            else -> error("Remote MCP catalog cannot be rolled back")
        }
        catalogPhase = DependencyStorePhase.ABSENT
        when (activationPhase) {
            DependencyStorePhase.PREPARED,
            DependencyStorePhase.COMMITTED,
            -> activations.rollback(checkNotNull(activationReceipt))
            DependencyStorePhase.FINALIZED -> require(
                activations.deactivate(identity) in setOf(
                    RemoteMcpDeactivationResult.DEACTIVATED,
                    RemoteMcpDeactivationResult.MISSING,
                ),
            ) { "Remote MCP finalized activation changed before rollback" }
            DependencyStorePhase.ABSENT -> Unit
            else -> error("Remote MCP activation cannot be rolled back")
        }
        activationPhase = DependencyStorePhase.ABSENT
    }

    fun finalizeCommit() {
        check(catalogPhase in setOf(DependencyStorePhase.COMMITTED, DependencyStorePhase.FINALIZED)) {
            "Remote MCP catalog is not committed"
        }
        check(activationPhase in setOf(
            DependencyStorePhase.COMMITTED,
            DependencyStorePhase.FINALIZED,
            DependencyStorePhase.ABSENT,
        )) { "Remote MCP activation is not committed" }
        if (catalogPhase == DependencyStorePhase.COMMITTED) {
            catalogs.finalize(catalogReceipt)
            catalogPhase = DependencyStorePhase.FINALIZED
        }
        if (activationPhase == DependencyStorePhase.COMMITTED) {
            activations.finalize(checkNotNull(activationReceipt))
            activationPhase = DependencyStorePhase.FINALIZED
        }
        if (activationPhase == DependencyStorePhase.ABSENT) {
            val finalized = catalogs.finalizedCatalog(identity)
            check(finalized.metadataDigest == component.stateDigest &&
                finalized.routeState == routeState && !finalized.routeEligible) {
                "Remote MCP no-route catalog proof failed"
            }
            check(
                activations.deactivateSupersededByFinalizedNoRoute(identity) in setOf(
                    RemoteMcpDeactivationResult.DEACTIVATED,
                    RemoteMcpDeactivationResult.MISSING,
                ),
            ) { "Remote MCP superseded activation could not be removed" }
        }
        activationMetadataDigest?.let { digest ->
            check(
                activations.proveFinalized(identity, digest) ==
                    RemoteMcpFinalizedActivationProof.EXACT,
            ) { "Remote MCP finalized activation proof failed" }
        }
    }

    companion object {
        fun prepared(
            identity: RemoteMcpActivationIdentity,
            routeState: RemoteMcpCatalogRouteState,
            activationReceipt: RemoteMcpActivationReceipt?,
            catalogReceipt: RemoteMcpDiscoveryCatalogReceipt,
            activations: RemoteMcpSessionRegistry,
            catalogs: RemoteMcpDiscoveryCatalogStore,
        ): RemoteMcpServerDependencyEntry {
            require((routeState == RemoteMcpCatalogRouteState.READY) ==
                (activationReceipt != null)) {
                "Remote MCP route state does not match its activation receipt"
            }
            val component = PluginDependencyRecoveryComponent(
                kind = PluginDependencyRecoveryComponentKind.REMOTE_MCP_V1,
                componentId = identity.serverId,
                transactionId = catalogReceipt.transactionId,
                stateDigest = catalogReceipt.metadataDigest,
            )
            return RemoteMcpServerDependencyEntry(
                identity = identity,
                component = component,
                routeState = routeState,
                activationReceipt = activationReceipt,
                activationMetadataDigest = activationReceipt?.metadataDigest,
                activationPhase = if (activationReceipt == null) {
                    DependencyStorePhase.ABSENT
                } else {
                    DependencyStorePhase.PREPARED
                },
                catalogReceipt = catalogReceipt,
                catalogPhase = DependencyStorePhase.PREPARED,
                activations = activations,
                catalogs = catalogs,
            )
        }
    }
}

private enum class DependencyStorePhase {
    PREPARED,
    COMMITTED,
    FINALIZED,
    ABSENT,
    CHANGED,
    UNAVAILABLE,
}

private data class ActivationObservation(
    val state: DependencyStorePhase,
    val receipt: RemoteMcpActivationReceipt?,
)

private fun RemoteMcpCatalogPhase.toDependencyPhase(): DependencyStorePhase = when (this) {
    RemoteMcpCatalogPhase.PREPARED -> DependencyStorePhase.PREPARED
    RemoteMcpCatalogPhase.COMMITTED -> DependencyStorePhase.COMMITTED
    RemoteMcpCatalogPhase.FINALIZED -> DependencyStorePhase.FINALIZED
}

private fun PluginInstallJournalPhase.allowsFinalizeRecovery(): Boolean = this in setOf(
    PluginInstallJournalPhase.LOCAL_COMMITTED,
    PluginInstallJournalPhase.FINALIZED,
)

private fun activationDefinition(
    pluginId: String,
    requirement: RemoteMcpRequirement,
): RemoteMcpActivationDefinition = RemoteMcpActivationDefinition(
    identity = RemoteMcpActivationIdentity(
        pluginId = pluginId,
        serverId = requirement.id,
        configurationDigest = RemoteMcpConfigurationIdentity.digest(requirement),
    ),
    requirement = requirement,
)

private fun credentialIdentity(
    identity: RemoteMcpActivationIdentity,
    handle: OAuthCredentialHandle,
) = RemoteMcpOAuthCredentialIdentity(
    pluginId = identity.pluginId,
    serverId = identity.serverId,
    configurationDigest = identity.configurationDigest,
    handle = handle,
)

private fun OAuthCredentialState.connectionReason(): RemoteMcpConnectionReason = when (this) {
    OAuthCredentialState.MISSING -> RemoteMcpConnectionReason.MISSING
    OAuthCredentialState.EXPIRED -> RemoteMcpConnectionReason.EXPIRED
    OAuthCredentialState.STALE_IDENTITY -> RemoteMcpConnectionReason.STALE_IDENTITY
    OAuthCredentialState.UNAVAILABLE -> RemoteMcpConnectionReason.CREDENTIAL_STORE_UNAVAILABLE
    OAuthCredentialState.AVAILABLE -> error("An available credential needs no connection request")
}

private val SAFE_COMPONENT_IDS = Regex("[a-z][a-z0-9._:-]{0,95}")
