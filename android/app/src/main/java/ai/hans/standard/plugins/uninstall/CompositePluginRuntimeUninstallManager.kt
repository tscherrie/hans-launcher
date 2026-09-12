package ai.hans.standard.plugins.uninstall

import ai.hans.standard.mcp.RemoteMcpActivationIdentity
import ai.hans.standard.mcp.RemoteMcpCatalogDeactivationResult
import ai.hans.standard.mcp.RemoteMcpDeactivationResult
import ai.hans.standard.mcp.RemoteMcpDiscoveryCatalogRecoveryDescriptor
import ai.hans.standard.mcp.RemoteMcpDiscoveryCatalogStore
import ai.hans.standard.mcp.RemoteMcpFinalizedActivationDescriptor
import ai.hans.standard.mcp.RemoteMcpSessionRegistry
import ai.hans.standard.plugins.runtime.PluginSurfaceActivationStore
import ai.hans.standard.plugins.runtime.PluginSurfacePrivateRecord
import ai.hans.standard.runtime.python.PythonPluginEntrypointDeactivationResult
import ai.hans.standard.runtime.python.PythonPluginEntrypointFinalizedSnapshot
import ai.hans.standard.runtime.python.PythonPluginEntrypointRegistry

/**
 * Exact ordinary-uninstall boundary spanning every root-free runtime publication owner.
 *
 * A complete private-store inventory is captured before any removal. A mismatching, pending or
 * unavailable owner prevents the first mutation. Recovery may tolerate only exact components from
 * the persisted descriptor which are already absent after an earlier partial cleanup.
 */
internal class CompositePluginRuntimeUninstallManager(
    private val python: PythonPluginEntrypointRegistry,
    private val surfaces: PluginSurfaceActivationStore,
    private val remoteCatalogs: RemoteMcpDiscoveryCatalogStore,
    private val remoteActivations: RemoteMcpSessionRegistry,
) : PluginRuntimeUninstallManager {
    override fun snapshot(pluginId: String): PluginRuntimeUninstallSnapshot = when (
        val observed = observe(pluginId)
    ) {
        Observation.Changed -> PluginRuntimeUninstallSnapshot.Changed
        Observation.Unavailable -> PluginRuntimeUninstallSnapshot.Unavailable
        is Observation.Exact -> {
            if (!observed.state.remoteIsFullyCorrelated()) {
                PluginRuntimeUninstallSnapshot.Changed
            } else {
                PluginRuntimeUninstallSnapshot.Exact(observed.state.descriptor())
            }
        }
    }

    override fun deactivateExact(
        descriptor: PluginRuntimeUninstallDescriptor,
        allowAlreadyAbsent: Boolean,
    ): PluginRuntimeUninstallDeactivationResult {
        val observed = when (val current = observe(descriptor.pluginId)) {
            Observation.Changed -> return PluginRuntimeUninstallDeactivationResult.CHANGED
            Observation.Unavailable -> return PluginRuntimeUninstallDeactivationResult.UNAVAILABLE
            is Observation.Exact -> current.state
        }
        val expected = ExpectedRuntimeState.from(descriptor)
            ?: return PluginRuntimeUninstallDeactivationResult.CHANGED
        val preflight = observed.preflight(expected, allowAlreadyAbsent)
        if (preflight != PluginRuntimeUninstallDeactivationResult.EXACT) return preflight

        var changed = false
        fun fail(result: PluginRuntimeUninstallDeactivationResult) =
            if (changed) PluginRuntimeUninstallDeactivationResult.PARTIAL else result

        expected.python?.let { component ->
            if (observed.python != null) {
                when (python.deactivateFinalized(
                    pluginId = descriptor.pluginId,
                    expectedEnvironmentDigest = component.identity,
                    expectedSourceSha256 = checkNotNull(component.secondaryStateSha256),
                    expectedMetadataDigest = component.stateSha256,
                )) {
                    PythonPluginEntrypointDeactivationResult.DEACTIVATED -> changed = true
                    PythonPluginEntrypointDeactivationResult.MISSING ->
                        return fail(PluginRuntimeUninstallDeactivationResult.CHANGED)
                    PythonPluginEntrypointDeactivationResult.CHANGED ->
                        return fail(PluginRuntimeUninstallDeactivationResult.CHANGED)
                    PythonPluginEntrypointDeactivationResult.UNAVAILABLE ->
                        return fail(PluginRuntimeUninstallDeactivationResult.UNAVAILABLE)
                }
            }
        }

        expected.surface?.let { component ->
            if (observed.surface != null) {
                val removed = runCatching {
                    surfaces.deactivate(
                        pluginId = descriptor.pluginId,
                        receiptId = component.identity,
                        stateSha256 = component.stateSha256,
                    )
                }.getOrElse {
                    return fail(PluginRuntimeUninstallDeactivationResult.UNAVAILABLE)
                }
                if (!removed) return fail(PluginRuntimeUninstallDeactivationResult.CHANGED)
                changed = true
            }
        }

        expected.remote.forEach { (serverId, component) ->
            val identity = RemoteMcpActivationIdentity(
                pluginId = descriptor.pluginId,
                serverId = serverId,
                configurationDigest = component.identity,
            )
            if (serverId in observed.catalogs) {
                when (remoteCatalogs.deactivate(identity, component.stateSha256)) {
                    RemoteMcpCatalogDeactivationResult.DEACTIVATED -> changed = true
                    RemoteMcpCatalogDeactivationResult.MISSING,
                    RemoteMcpCatalogDeactivationResult.STALE_IDENTITY,
                    RemoteMcpCatalogDeactivationResult.STALE_METADATA,
                    RemoteMcpCatalogDeactivationResult.TRANSACTION_PENDING,
                    -> return fail(PluginRuntimeUninstallDeactivationResult.CHANGED)
                    RemoteMcpCatalogDeactivationResult.STORE_UNAVAILABLE ->
                        return fail(PluginRuntimeUninstallDeactivationResult.UNAVAILABLE)
                }
            }
            if (component.secondaryStateSha256 != null && serverId in observed.activations) {
                when (remoteActivations.deactivate(
                    identity,
                    checkNotNull(component.secondaryStateSha256),
                )) {
                    RemoteMcpDeactivationResult.DEACTIVATED -> changed = true
                    RemoteMcpDeactivationResult.MISSING,
                    RemoteMcpDeactivationResult.STALE_IDENTITY,
                    RemoteMcpDeactivationResult.TRANSACTION_PENDING,
                    -> return fail(PluginRuntimeUninstallDeactivationResult.CHANGED)
                    RemoteMcpDeactivationResult.REGISTRY_UNAVAILABLE ->
                        return fail(PluginRuntimeUninstallDeactivationResult.UNAVAILABLE)
                }
            }
        }
        return PluginRuntimeUninstallDeactivationResult.EXACT
    }

    private fun observe(pluginId: String): Observation {
        val pythonState = when (val current = runCatching {
            python.finalizedSnapshot(pluginId)
        }.getOrElse { PythonPluginEntrypointFinalizedSnapshot.Unavailable }) {
            PythonPluginEntrypointFinalizedSnapshot.Absent -> null
            PythonPluginEntrypointFinalizedSnapshot.Changed -> return Observation.Changed
            PythonPluginEntrypointFinalizedSnapshot.Unavailable -> return Observation.Unavailable
            is PythonPluginEntrypointFinalizedSnapshot.Present -> current
        }

        val surfaceState = runCatching {
            if (!surfaces.isAvailable()) return Observation.Unavailable
            if (surfaces.recordsFor(pluginId).isNotEmpty()) return Observation.Changed
            surfaces.active(pluginId)
        }.getOrElse { return Observation.Unavailable }

        val catalogs = runCatching { remoteCatalogs.recoverySnapshot(pluginId) }
            .getOrElse { return Observation.Unavailable }
        if (!catalogs.available) return Observation.Unavailable
        if (catalogs.pending.isNotEmpty()) return Observation.Changed
        val activations = runCatching { remoteActivations.recoverySnapshot(pluginId) }
            .getOrElse { return Observation.Unavailable }
        if (!activations.available) return Observation.Unavailable
        if (activations.pending.isNotEmpty()) return Observation.Changed

        val catalogsByServer = catalogs.finalized.associateBy { it.identity.serverId }
        val activationsByServer = activations.finalized.associateBy { it.identity.serverId }
        if (catalogsByServer.size != catalogs.finalized.size ||
            activationsByServer.size != activations.finalized.size
        ) return Observation.Changed

        return Observation.Exact(
            ObservedRuntimeState(
                pluginId = pluginId,
                python = pythonState,
                surface = surfaceState,
                catalogs = catalogsByServer,
                activations = activationsByServer,
            ),
        )
    }

    private sealed interface Observation {
        data class Exact(val state: ObservedRuntimeState) : Observation
        data object Changed : Observation
        data object Unavailable : Observation
    }

    private data class ObservedRuntimeState(
        val pluginId: String,
        val python: PythonPluginEntrypointFinalizedSnapshot.Present?,
        val surface: PluginSurfacePrivateRecord?,
        val catalogs: Map<String, RemoteMcpDiscoveryCatalogRecoveryDescriptor>,
        val activations: Map<String, RemoteMcpFinalizedActivationDescriptor>,
    ) {
        fun remoteIsFullyCorrelated(): Boolean {
            if (catalogs.keys != catalogs.keys + activations.keys) return false
            return catalogs.all { (serverId, catalog) ->
                val activation = activations[serverId]
                if (catalog.activationMetadataDigest == null) {
                    activation == null
                } else {
                    activation?.identity == catalog.identity &&
                        activation.metadataDigest == catalog.activationMetadataDigest
                }
            }
        }

        fun descriptor(): PluginRuntimeUninstallDescriptor {
            val values = mutableListOf<PluginRuntimeUninstallComponent>()
            python?.let {
                values += PluginRuntimeUninstallComponent(
                    kind = PluginRuntimeUninstallComponentKind.PYTHON_ENTRYPOINT_V1,
                    componentId = PluginRuntimeUninstallComponent.PYTHON_COMPONENT_ID,
                    identity = it.environmentDigest,
                    stateSha256 = it.metadataDigest,
                    secondaryStateSha256 = it.sourceSha256,
                )
            }
            surface?.let {
                values += PluginRuntimeUninstallComponent(
                    kind = PluginRuntimeUninstallComponentKind.PLUGIN_SURFACE_V1,
                    componentId = PluginRuntimeUninstallComponent.SURFACE_COMPONENT_ID,
                    identity = it.receiptId,
                    stateSha256 = it.stateSha256,
                )
            }
            catalogs.values.forEach { catalog ->
                values += catalog.component()
            }
            return PluginRuntimeUninstallDescriptor.canonical(pluginId, values)
        }

        fun preflight(
            expected: ExpectedRuntimeState,
            allowAlreadyAbsent: Boolean,
        ): PluginRuntimeUninstallDeactivationResult {
            val actualPython = python?.component()
            if (actualPython != null && actualPython != expected.python) {
                return PluginRuntimeUninstallDeactivationResult.CHANGED
            }
            if (!allowAlreadyAbsent && actualPython != expected.python) {
                return PluginRuntimeUninstallDeactivationResult.CHANGED
            }
            val actualSurface = surface?.component()
            if (actualSurface != null && actualSurface != expected.surface) {
                return PluginRuntimeUninstallDeactivationResult.CHANGED
            }
            if (!allowAlreadyAbsent && actualSurface != expected.surface) {
                return PluginRuntimeUninstallDeactivationResult.CHANGED
            }
            if ((catalogs.keys + activations.keys).any { it !in expected.remote }) {
                return PluginRuntimeUninstallDeactivationResult.CHANGED
            }
            expected.remote.forEach { (serverId, component) ->
                val catalog = catalogs[serverId]
                if (catalog != null && catalog.component() != component) {
                    return PluginRuntimeUninstallDeactivationResult.CHANGED
                }
                val activation = activations[serverId]
                if (activation != null && (
                        component.secondaryStateSha256 == null ||
                            activation.identity.pluginId != pluginId ||
                            activation.identity.serverId != serverId ||
                            activation.identity.configurationDigest != component.identity ||
                            activation.metadataDigest != component.secondaryStateSha256
                        )
                ) {
                    return PluginRuntimeUninstallDeactivationResult.CHANGED
                }
                if (!allowAlreadyAbsent && (
                        catalog == null ||
                            (component.secondaryStateSha256 != null && activation == null)
                        )
                ) {
                    return PluginRuntimeUninstallDeactivationResult.CHANGED
                }
            }
            return PluginRuntimeUninstallDeactivationResult.EXACT
        }

        private fun PythonPluginEntrypointFinalizedSnapshot.Present.component() =
            PluginRuntimeUninstallComponent(
                kind = PluginRuntimeUninstallComponentKind.PYTHON_ENTRYPOINT_V1,
                componentId = PluginRuntimeUninstallComponent.PYTHON_COMPONENT_ID,
                identity = environmentDigest,
                stateSha256 = metadataDigest,
                secondaryStateSha256 = sourceSha256,
            )

        private fun PluginSurfacePrivateRecord.component() = PluginRuntimeUninstallComponent(
            kind = PluginRuntimeUninstallComponentKind.PLUGIN_SURFACE_V1,
            componentId = PluginRuntimeUninstallComponent.SURFACE_COMPONENT_ID,
            identity = receiptId,
            stateSha256 = stateSha256,
        )

        private fun RemoteMcpDiscoveryCatalogRecoveryDescriptor.component() =
            PluginRuntimeUninstallComponent(
                kind = PluginRuntimeUninstallComponentKind.REMOTE_MCP_V1,
                componentId = identity.serverId,
                identity = identity.configurationDigest,
                stateSha256 = metadataDigest,
                secondaryStateSha256 = activationMetadataDigest,
            )
    }

    private data class ExpectedRuntimeState(
        val python: PluginRuntimeUninstallComponent?,
        val surface: PluginRuntimeUninstallComponent?,
        val remote: Map<String, PluginRuntimeUninstallComponent>,
    ) {
        companion object {
            fun from(descriptor: PluginRuntimeUninstallDescriptor): ExpectedRuntimeState? {
                val python = descriptor.components.singleOrNull {
                    it.kind == PluginRuntimeUninstallComponentKind.PYTHON_ENTRYPOINT_V1
                }
                val surface = descriptor.components.singleOrNull {
                    it.kind == PluginRuntimeUninstallComponentKind.PLUGIN_SURFACE_V1
                }
                val remoteValues = descriptor.components.filter {
                    it.kind == PluginRuntimeUninstallComponentKind.REMOTE_MCP_V1
                }
                val remote = remoteValues.associateBy(PluginRuntimeUninstallComponent::componentId)
                if (remote.size != remoteValues.size) return null
                if (python != null && python.secondaryStateSha256 == null) return null
                return ExpectedRuntimeState(python, surface, remote)
            }
        }
    }
}
