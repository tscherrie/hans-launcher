package ai.hans.standard.plugins.runtime

import ai.hans.standard.plugins.PluginRuntimeKind
import ai.hans.standard.plugins.PluginRuntimeRequirements
import ai.hans.standard.plugins.PluginUnsupportedHostConstraint
import ai.hans.standard.plugins.PluginDetailSnapshot

/** Exact, fresh plugin/read evidence; no skill path or hook implementation enters this model. */
internal fun PluginDetailSnapshot.toCodexNativeSurfaceEvidence(): CodexNativeSurfaceEvidence =
    CodexNativeSurfaceEvidence(
        enabledSkillNames = skills.filter { it.enabled }.mapTo(linkedSetOf()) { it.name },
        enabledHooks = hooks.mapTo(linkedSetOf()) { it.key to it.eventName },
        complete = true,
    )

internal enum class PluginSurfaceReadiness(val wireName: String) {
    READY("ready"),
    DEGRADED("degraded"),
    INCOMPATIBLE("incompatible"),
}

internal data class RemoteMcpPassiveStatus(
    val pluginId: String,
    val serverId: String,
    val configured: Boolean,
    val authenticated: Boolean,
    val discoveryProven: Boolean,
    val discoveredToolNames: Set<String>,
) {
    init {
        require(pluginId.matches(Regex("[a-z][a-z0-9._-]{0,127}")))
        require(serverId.matches(Regex("[a-z][a-z0-9._:-]{0,95}")))
        require(!authenticated || configured)
        require(!discoveryProven || configured)
        require(discoveredToolNames.size <= 256)
    }
}

internal fun interface RemoteMcpPassiveStatusSource {
    /** Must be a cache/snapshot read: no process, credential resolution, or network I/O. */
    fun snapshot(): List<RemoteMcpPassiveStatus>
}

internal data class PluginSurfaceInventoryItem(
    val id: String,
    val kind: String,
    val readiness: PluginSurfaceReadiness,
    val detailCode: String,
)

internal data class PluginSurfaceInventorySnapshot(
    val pluginId: String,
    val readiness: PluginSurfaceReadiness,
    val items: List<PluginSurfaceInventoryItem>,
    val resolvedEntrypointIds: Set<String>,
    val availableCapabilityIds: Set<String>,
) {
    init {
        require(items.size <= 768)
    }
}

/** Pure root-free policy check which never silently downgrades an executable requirement. */
internal object StandardRootFreePluginPolicy {
    private val rejectedRuntimes = setOf(
        PluginRuntimeKind.NODE_JS,
        PluginRuntimeKind.MCP_SERVER,
        PluginRuntimeKind.MCP_LOCAL_PROCESS,
        PluginRuntimeKind.POSIX_SHELL,
        PluginRuntimeKind.SIGNED_NATIVE_EXECUTABLE,
        PluginRuntimeKind.DESKTOP_UI,
    )
    private val rejectedConstraints = setOf(
        PluginUnsupportedHostConstraint.ROOT_ACCESS,
        PluginUnsupportedHostConstraint.CHROOT,
        PluginUnsupportedHostConstraint.SYSTEM_UID,
        PluginUnsupportedHostConstraint.PLATFORM_SIGNATURE,
        PluginUnsupportedHostConstraint.WRITABLE_NATIVE_CODE,
        PluginUnsupportedHostConstraint.SOURCE_DISTRIBUTION_BUILD,
    )

    fun rejectionCodes(requirements: PluginRuntimeRequirements): List<String> = buildList {
        requirements.runtimes
            .filter { it.required && it.kind in rejectedRuntimes }
            .sortedBy { it.id }
            .forEach { add("unsupported_runtime:${it.kind.wireName}:${it.id}") }
        requirements.unsupportedHostConstraints
            .filter { it in rejectedConstraints }
            .sortedBy { it.wireName }
            .forEach { add("unsupported_host:${it.wireName}") }
    }
}

/**
 * Passive, deterministic Workbench projection. It never resolves OAuth handles or probes a remote
 * server; the remote source publishes evidence only after a real, bounded discovery succeeds.
 */
internal class PluginSurfaceInventoryProjector(
    private val androidTools: AndroidDynamicToolEntrypointRegistry,
    private val hooks: HansDeclarativeHookRegistry,
    private val remoteMcp: RemoteMcpPassiveStatusSource,
) {
    fun project(
        manifest: PluginSurfaceManifest,
        nativeEvidence: CodexNativeSurfaceEvidence,
    ): PluginSurfaceInventorySnapshot = projectInternal(
        manifest = manifest,
        nativeEvidence = nativeEvidence,
        remoteMode = RemoteProjectionMode.RUNTIME,
    )

    /**
     * Install-time surface proof deliberately stops at the declarative Remote MCP contract.
     * Network discovery, OAuth and route publication belong to the separately journaled MCP
     * dependency component; requiring them here would make the two preparers circular.
     */
    fun projectForPreparation(
        manifest: PluginSurfaceManifest,
        nativeEvidence: CodexNativeSurfaceEvidence,
        androidEvidence: List<AndroidDynamicToolBindingSnapshot> = androidTools.snapshot(),
        hookEvidence: List<HansDeclarativeHookBindingSnapshot> = hooks.snapshot(),
    ): PluginSurfaceInventorySnapshot = projectInternal(
        manifest = manifest,
        nativeEvidence = nativeEvidence,
        remoteMode = RemoteProjectionMode.DECLARATIVE_ONLY,
        fixedAndroidEvidence = androidEvidence,
        fixedHookEvidence = hookEvidence,
    )

    private fun projectInternal(
        manifest: PluginSurfaceManifest,
        nativeEvidence: CodexNativeSurfaceEvidence,
        remoteMode: RemoteProjectionMode,
        fixedAndroidEvidence: List<AndroidDynamicToolBindingSnapshot>? = null,
        fixedHookEvidence: List<HansDeclarativeHookBindingSnapshot>? = null,
    ): PluginSurfaceInventorySnapshot {
        val items = mutableListOf<PluginSurfaceInventoryItem>()
        val entrypoints = linkedSetOf<String>()
        val capabilities = linkedSetOf<String>()
        var incompatible = false
        var degraded = false

        manifest.nativeSkills.sortedBy(NativeSkillRequirement::id).forEach { requirement ->
            val ready = nativeEvidence.complete && requirement.name in nativeEvidence.enabledSkillNames
            items += item(requirement.id, "codex_skill", ready, nativeEvidence.complete)
            if (!ready && requirement.required && nativeEvidence.complete) {
                incompatible = true
            } else if (!ready) {
                degraded = true
            }
            if (ready) entrypoints += requirement.id
        }
        manifest.nativeHooks.sortedBy(NativeHookRequirement::id).forEach { requirement ->
            val ready = nativeEvidence.complete &&
                (requirement.key to requirement.eventName) in nativeEvidence.enabledHooks
            items += item(requirement.id, "codex_hook", ready, nativeEvidence.complete)
            if (!ready && requirement.required && nativeEvidence.complete) {
                incompatible = true
            } else if (!ready) {
                degraded = true
            }
            if (ready) entrypoints += requirement.id
        }

        val androidSnapshots = fixedAndroidEvidence ?: androidTools.snapshot()
        val android = androidTools.resolve(manifest.androidTools, androidSnapshots)
        entrypoints += android.resolvedRequirementIds
        capabilities += android.availableCapabilityIds
        incompatible = incompatible || android.missingRequiredIds.isNotEmpty()
        degraded = degraded || android.degradedIds.isNotEmpty()
        val androidById = androidSnapshots.associateBy(AndroidDynamicToolBindingSnapshot::bindingId)
        manifest.androidTools.sortedBy(AndroidToolRequirement::id).forEach { requirement ->
            val snapshot = androidById[requirement.bindingId]
            val ready = requirement.id in android.resolvedRequirementIds
            items += PluginSurfaceInventoryItem(
                requirement.id,
                "android_tool",
                when {
                    ready && requirement.id !in android.degradedIds -> PluginSurfaceReadiness.READY
                    snapshot != null -> PluginSurfaceReadiness.DEGRADED
                    else -> PluginSurfaceReadiness.INCOMPATIBLE
                },
                when {
                    ready -> "capability_proven"
                    snapshot == null -> "binding_unavailable"
                    else -> "capability_degraded"
                },
            )
        }

        val hookSnapshots = fixedHookEvidence ?: hooks.snapshot()
        val hookResolution = hooks.resolve(manifest.hooks, hookSnapshots)
        entrypoints += hookResolution.resolvedRequirementIds
        capabilities += hookResolution.availableCapabilityIds
        incompatible = incompatible || hookResolution.missingRequiredIds.isNotEmpty()
        degraded = degraded || hookResolution.degradedIds.isNotEmpty()
        manifest.hooks.sortedBy(DeclarativeHookRequirement::id).forEach { requirement ->
            val ready = requirement.id in hookResolution.resolvedRequirementIds
            items += PluginSurfaceInventoryItem(
                requirement.id,
                "hans_hook",
                when {
                    ready && requirement.id !in hookResolution.degradedIds -> PluginSurfaceReadiness.READY
                    ready -> PluginSurfaceReadiness.DEGRADED
                    else -> PluginSurfaceReadiness.INCOMPATIBLE
                },
                when {
                    ready && requirement.id !in hookResolution.degradedIds -> "capability_proven"
                    ready -> "capability_degraded"
                    else -> "hook_action_unavailable"
                },
            )
            if (!ready && !requirement.required) degraded = true
        }

        val remoteStatuses = if (remoteMode == RemoteProjectionMode.RUNTIME) {
            runCatching(remoteMcp::snapshot).getOrDefault(emptyList())
                .filter { it.pluginId == manifest.pluginId }
                .associateBy(RemoteMcpPassiveStatus::serverId)
        } else {
            emptyMap()
        }
        manifest.remoteMcpServers.sortedBy(RemoteMcpRequirement::id).forEach { requirement ->
            if (remoteMode == RemoteProjectionMode.DECLARATIVE_ONLY) {
                items += PluginSurfaceInventoryItem(
                    requirement.id,
                    "remote_https_mcp",
                    PluginSurfaceReadiness.DEGRADED,
                    "mcp_declaration_validated",
                )
                return@forEach
            }
            val status = remoteStatuses[requirement.id]
            val toolsReady = status?.discoveryProven == true &&
                requirement.allowedTools.all { it in status.discoveredToolNames }
            val authReady = requirement.oauthHandle == null || status?.authenticated == true
            val ready = status?.configured == true && toolsReady && authReady
            items += PluginSurfaceInventoryItem(
                requirement.id,
                "remote_https_mcp",
                if (ready) PluginSurfaceReadiness.READY else if (status?.configured == true) {
                    PluginSurfaceReadiness.DEGRADED
                } else {
                    PluginSurfaceReadiness.INCOMPATIBLE
                },
                when {
                    status == null || !status.configured -> "mcp_not_configured"
                    !authReady -> "mcp_auth_required"
                    !toolsReady -> "mcp_discovery_unproven"
                    else -> "mcp_tools_proven"
                },
            )
            if (ready) {
                entrypoints += requirement.allowedTools.map { "${requirement.id}:$it" }
                capabilities += "mcp.remote.${requirement.id}"
            } else if (requirement.required) {
                incompatible = true
            } else {
                degraded = true
            }
        }

        val overall = when {
            incompatible -> PluginSurfaceReadiness.INCOMPATIBLE
            degraded || items.any { it.readiness == PluginSurfaceReadiness.DEGRADED } ->
                PluginSurfaceReadiness.DEGRADED
            else -> PluginSurfaceReadiness.READY
        }
        return PluginSurfaceInventorySnapshot(
            pluginId = manifest.pluginId,
            readiness = overall,
            items = items.sortedWith(compareBy({ it.kind }, { it.id })),
            resolvedEntrypointIds = entrypoints,
            availableCapabilityIds = capabilities,
        )
    }

    private enum class RemoteProjectionMode { DECLARATIVE_ONLY, RUNTIME }

    private fun item(
        id: String,
        kind: String,
        ready: Boolean,
        complete: Boolean,
    ) = PluginSurfaceInventoryItem(
        id = id,
        kind = kind,
        readiness = when {
            ready -> PluginSurfaceReadiness.READY
            complete -> PluginSurfaceReadiness.INCOMPATIBLE
            else -> PluginSurfaceReadiness.DEGRADED
        },
        detailCode = when {
            ready -> "app_server_proven"
            complete -> "app_server_selector_missing"
            else -> "app_server_evidence_pending"
        },
    )
}
