package ai.hans.standard.plugins.uninstall

import java.io.File

/** Durable phases of one ordinary plugin uninstall. Never reorder or rename persisted values. */
internal enum class PluginUninstallJournalPhase {
    TARGET_CAPTURED,
    REMOTE_UNINSTALL_INTENT,
    REMOTE_ACCEPTED,
    REMOTE_PROVEN_ABSENT,
    LOCAL_DEACTIVATION_INTENT,
    LOCAL_DEACTIVATED,
    FINALIZED,
    RECONCILE_REQUIRED,
    QUARANTINED,
}

/** Exact App Server target captured before plugin/uninstall is allowed onto the transport. */
internal data class PluginUninstallTargetIdentity(
    val pluginId: String,
    val pluginHandleSha256: String,
    val pluginName: String,
    val marketplaceName: String,
    val marketplacePath: String?,
    val installedVersion: String?,
    val canonicalSourceRoot: String?,
    val sourceSha256: String?,
) {
    init {
        PluginUninstallBounds.requirePluginId(pluginId)
        PluginUninstallBounds.requireSha256(pluginHandleSha256, "plugin handle")
        PluginUninstallBounds.requireText(pluginName, "plugin name")
        PluginUninstallBounds.requireText(marketplaceName, "marketplace name")
        marketplacePath?.let { PluginUninstallBounds.requireAbsolutePath(it, "marketplace path") }
        installedVersion?.let { PluginUninstallBounds.requireText(it, "installed version", 512) }
        require((canonicalSourceRoot == null) == (sourceSha256 == null)) {
            "Plugin uninstall source identity must be complete or absent"
        }
        canonicalSourceRoot?.let {
            PluginUninstallBounds.requireAbsolutePath(it, "source root")
        }
        sourceSha256?.let { PluginUninstallBounds.requireSha256(it, "source") }
    }
}

internal data class PluginUninstallJournalTimestamps(
    val createdAtWallEpochMillis: Long,
    val updatedAtWallEpochMillis: Long,
    val createdAtElapsedRealtimeMillis: Long,
    val updatedAtElapsedRealtimeMillis: Long,
) {
    init {
        require(createdAtWallEpochMillis >= 0L && updatedAtWallEpochMillis >= 0L)
        require(createdAtElapsedRealtimeMillis >= 0L && updatedAtElapsedRealtimeMillis >= 0L)
    }
}

internal data class PluginUninstallJournalEntry(
    val operationId: String,
    val leaseId: String,
    val revision: Long,
    val ownerProcessEpoch: String,
    val phase: PluginUninstallJournalPhase,
    val target: PluginUninstallTargetIdentity,
    val runtime: PluginRuntimeUninstallDescriptor,
    val timestamps: PluginUninstallJournalTimestamps,
) {
    init {
        PluginUninstallBounds.requireOpaqueId(operationId, "operation")
        PluginUninstallBounds.requireOpaqueId(leaseId, "lease")
        PluginUninstallBounds.requireOpaqueId(ownerProcessEpoch, "process epoch")
        require(revision in 1..PluginUninstallBounds.MAX_REVISION)
        require(runtime.pluginId == target.pluginId) { "Runtime uninstall plugin identity changed" }
    }

    val cursor: PluginUninstallJournalCursor
        get() = PluginUninstallJournalCursor(operationId, leaseId, revision)
}

internal data class PluginUninstallJournalCursor(
    val operationId: String,
    val leaseId: String,
    val revision: Long,
) {
    init {
        PluginUninstallBounds.requireOpaqueId(operationId, "operation")
        PluginUninstallBounds.requireOpaqueId(leaseId, "lease")
        require(revision in 1..PluginUninstallBounds.MAX_REVISION)
    }
}

internal enum class PluginUninstallJournalMutationResult {
    APPLIED,
    OPERATION_ALREADY_EXISTS,
    CURSOR_MISMATCH,
    JOURNAL_FULL,
}

internal object PluginUninstallBounds {
    const val MAX_DOCUMENT_BYTES = 128 * 1024
    const val MAX_ENTRIES = 16
    const val MAX_REVISION = 1_000_000_000L
    private const val MAX_ID_CHARS = 128
    private const val MAX_TEXT_CHARS = 512
    private const val MAX_PATH_CHARS = 4_096
    private val OPAQUE_ID = Regex("[A-Za-z0-9._:-]{1,$MAX_ID_CHARS}")
    private val PLUGIN_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    private val SHA_256 = Regex("[0-9a-f]{64}")

    fun requireOpaqueId(value: String, label: String) {
        require(OPAQUE_ID.matches(value)) { "Invalid plugin uninstall $label" }
    }

    fun requirePluginId(value: String) {
        require(PLUGIN_ID.matches(value)) { "Invalid plugin uninstall plugin id" }
    }

    fun requireSha256(value: String, label: String) {
        require(SHA_256.matches(value)) { "Invalid plugin uninstall $label digest" }
    }

    fun requireText(value: String, label: String, max: Int = MAX_TEXT_CHARS) {
        require(value.isNotBlank() && value.length <= max && value.none(Char::isISOControl)) {
            "Invalid plugin uninstall $label"
        }
    }

    fun requireAbsolutePath(value: String, label: String) {
        require(
            value.length in 1..MAX_PATH_CHARS && '\u0000' !in value && File(value).isAbsolute,
        ) { "Invalid plugin uninstall $label" }
    }
}
