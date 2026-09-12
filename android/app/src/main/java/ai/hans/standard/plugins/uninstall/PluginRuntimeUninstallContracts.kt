package ai.hans.standard.plugins.uninstall

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Finalized root-free runtime owners which may publish callable plugin state. */
internal enum class PluginRuntimeUninstallComponentKind(val order: Int) {
    PYTHON_ENTRYPOINT_V1(0),
    PLUGIN_SURFACE_V1(1),
    REMOTE_MCP_V1(2),
}

/**
 * Secret-free exact identity of one finalized local activation.
 *
 * The owning store has to prove all fields again before it removes anything. These coordinates
 * never contain a path, endpoint, OAuth handle, token, callable or schema body.
 */
internal data class PluginRuntimeUninstallComponent(
    val kind: PluginRuntimeUninstallComponentKind,
    val componentId: String,
    val identity: String,
    val stateSha256: String,
    val secondaryStateSha256: String? = null,
) {
    init {
        require(SAFE_ID.matches(componentId)) { "Invalid runtime uninstall component id" }
        require(SAFE_IDENTITY.matches(identity)) { "Invalid runtime uninstall component identity" }
        require(SHA_256.matches(stateSha256)) { "Invalid runtime uninstall component state" }
        secondaryStateSha256?.let {
            require(SHA_256.matches(it)) { "Invalid secondary runtime uninstall state" }
        }
        when (kind) {
            PluginRuntimeUninstallComponentKind.PYTHON_ENTRYPOINT_V1 ->
                require(componentId == PYTHON_COMPONENT_ID && SHA_256.matches(identity)) {
                    "Invalid Python runtime uninstall identity"
                }
            PluginRuntimeUninstallComponentKind.PLUGIN_SURFACE_V1 ->
                require(componentId == SURFACE_COMPONENT_ID && SURFACE_RECEIPT.matches(identity)) {
                    "Invalid plugin surface uninstall identity"
                }
            PluginRuntimeUninstallComponentKind.REMOTE_MCP_V1 ->
                require(SHA_256.matches(identity)) { "Invalid Remote MCP uninstall identity" }
        }
    }

    companion object {
        const val PYTHON_COMPONENT_ID = "python"
        const val SURFACE_COMPONENT_ID = "surface"
        private val SAFE_ID = Regex("[a-z][a-z0-9._:-]{0,95}")
        private val SAFE_IDENTITY = Regex("[A-Za-z0-9._:-]{1,128}")
        private val SURFACE_RECEIPT = Regex("[0-9a-f]{32}")
        internal val SHA_256 = Regex("[0-9a-f]{64}")
    }
}

/** Immutable exact cleanup plan captured before the first remote uninstall side effect. */
internal data class PluginRuntimeUninstallDescriptor(
    val pluginId: String,
    val components: List<PluginRuntimeUninstallComponent>,
) {
    init {
        require(PLUGIN_ID.matches(pluginId)) { "Invalid runtime uninstall plugin id" }
        require(components.size <= MAX_COMPONENTS) { "Too many runtime uninstall components" }
        require(components == components.sortedWith(COMPONENT_ORDER)) {
            "Runtime uninstall components are not canonical"
        }
        require(components.map { it.kind to it.componentId }.distinct().size == components.size) {
            "Duplicate runtime uninstall component"
        }
        require(components.count {
            it.kind == PluginRuntimeUninstallComponentKind.PYTHON_ENTRYPOINT_V1
        } <= 1)
        require(components.count {
            it.kind == PluginRuntimeUninstallComponentKind.PLUGIN_SURFACE_V1
        } <= 1)
        require(components.count {
            it.kind == PluginRuntimeUninstallComponentKind.REMOTE_MCP_V1
        } <= MAX_REMOTE_MCP_COMPONENTS)
    }

    val descriptorSha256: String
        get() = sha256(
            buildString {
                append(pluginId).append('\n')
                components.forEach { component ->
                    append(component.kind.name).append('\u0000')
                    append(component.componentId).append('\u0000')
                    append(component.identity).append('\u0000')
                    append(component.stateSha256).append('\u0000')
                    append(component.secondaryStateSha256.orEmpty()).append('\n')
                }
            }.toByteArray(StandardCharsets.UTF_8),
        )

    companion object {
        const val MAX_COMPONENTS = 18
        const val MAX_REMOTE_MCP_COMPONENTS = 16
        private val PLUGIN_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        val COMPONENT_ORDER = compareBy<PluginRuntimeUninstallComponent>(
            { it.kind.order },
            PluginRuntimeUninstallComponent::componentId,
        )

        fun canonical(
            pluginId: String,
            components: Collection<PluginRuntimeUninstallComponent>,
        ) = PluginRuntimeUninstallDescriptor(pluginId, components.sortedWith(COMPONENT_ORDER))
    }
}

internal sealed interface PluginRuntimeUninstallSnapshot {
    data class Exact(val descriptor: PluginRuntimeUninstallDescriptor) :
        PluginRuntimeUninstallSnapshot
    data object Changed : PluginRuntimeUninstallSnapshot
    data object Unavailable : PluginRuntimeUninstallSnapshot
}

internal enum class PluginRuntimeUninstallDeactivationResult {
    /** Every expected route is now absent and no non-matching activation was touched. */
    EXACT,
    /** At least one store no longer matches; no new removal was allowed to start. */
    CHANGED,
    /** A store could not prove its current state. */
    UNAVAILABLE,
    /** Some exact removals completed before a later store failed; durable recovery must resume. */
    PARTIAL,
}

/**
 * Composite local boundary. [deactivateExact] must preflight every still-present component before
 * the first mutation and must tolerate already-absent exact components only during crash recovery.
 */
internal interface PluginRuntimeUninstallManager {
    fun snapshot(pluginId: String): PluginRuntimeUninstallSnapshot

    fun deactivateExact(
        descriptor: PluginRuntimeUninstallDescriptor,
        allowAlreadyAbsent: Boolean,
    ): PluginRuntimeUninstallDeactivationResult

    companion object {
        val NONE = object : PluginRuntimeUninstallManager {
            override fun snapshot(pluginId: String): PluginRuntimeUninstallSnapshot =
                PluginRuntimeUninstallSnapshot.Exact(
                    PluginRuntimeUninstallDescriptor.canonical(pluginId, emptyList()),
                )

            override fun deactivateExact(
                descriptor: PluginRuntimeUninstallDescriptor,
                allowAlreadyAbsent: Boolean,
            ): PluginRuntimeUninstallDeactivationResult =
                if (descriptor.components.isEmpty()) {
                    PluginRuntimeUninstallDeactivationResult.EXACT
                } else {
                    PluginRuntimeUninstallDeactivationResult.CHANGED
                }
        }
    }
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
