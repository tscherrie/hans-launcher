package ai.hans.standard.plugins.install

import java.io.File

/** Durable phases of one exact plugin installation. Never reorder or rename persisted values. */
internal enum class PluginInstallJournalPhase {
    LOCAL_PREPARING,
    LOCAL_PREPARED,
    REMOTE_INSTALL_INTENT,
    REMOTE_ACCEPTED,
    REMOTE_PROVEN_INSTALLED,
    LOCAL_COMMIT_INTENT,
    LOCAL_COMMITTED,
    FINALIZED,
    ROLLBACK_INTENT,
    COMPENSATION_UNINSTALL_INTENT,
    COMPENSATION_ACCEPTED,
    COMPENSATION_PROVEN_ABSENT,
    RECONCILE_REQUIRED,
    QUARANTINED,
}

/**
 * Immutable identity captured before any side effect.
 *
 * The journal deliberately records every field used by the App Server proof instead of relying on
 * a display name or a mutable catalog row during recovery.
 */
internal data class PluginInstallIdentity(
    val pluginId: String,
    val pluginHandleSha256: String,
    val pluginName: String,
    val marketplaceName: String,
    val marketplacePath: String?,
    val expectedInstalledVersion: String,
    val canonicalSourceRoot: String,
    val sourceSha256: String,
) {
    init {
        PluginInstallJournalBounds.requireToken(pluginId, "plugin id")
        PluginInstallJournalBounds.requireSha256(pluginHandleSha256, "plugin handle")
        PluginInstallJournalBounds.requireToken(
            pluginName,
            "plugin name",
            PluginInstallJournalBounds.MAX_NAME_CHARS,
        )
        PluginInstallJournalBounds.requireToken(
            marketplaceName,
            "marketplace name",
            PluginInstallJournalBounds.MAX_NAME_CHARS,
        )
        marketplacePath?.let {
            PluginInstallJournalBounds.requireAbsolutePath(it, "marketplace path")
        }
        PluginInstallJournalBounds.requireToken(
            expectedInstalledVersion,
            "installed version",
            PluginInstallJournalBounds.MAX_VERSION_CHARS,
        )
        PluginInstallJournalBounds.requireAbsolutePath(canonicalSourceRoot, "source root")
        PluginInstallJournalBounds.requireSha256(sourceSha256, "source digest")
    }
}

/** Stable App Server idempotency identity for this operation. */
internal data class PluginInstallAttempt(
    val installAttemptId: String,
    val attemptOrdinal: Int,
) {
    init {
        PluginInstallJournalBounds.requireOpaqueId(installAttemptId, "install attempt")
        require(attemptOrdinal in 1..PluginInstallJournalBounds.MAX_ATTEMPT_ORDINAL) {
            "Invalid install attempt ordinal"
        }
    }
}

internal enum class PluginDependencyRecoveryKind {
    PYTHON_ENVIRONMENT_V1,
    COMPOSITE_V1,
}

/**
 * Stable, secret-free identities of the runtime owners coordinated by a composite install.
 *
 * These values are persisted in the outer plugin journal. They therefore contain only opaque
 * transaction ids and SHA-256 proofs. In particular, endpoints, OAuth handles, tokens, paths,
 * executable names and schemas do not belong in this contract.
 */
internal enum class PluginDependencyRecoveryComponentKind(val canonicalOrder: Int) {
    PYTHON_ENVIRONMENT_V1(0),
    PLUGIN_SURFACE_V1(1),
    REMOTE_MCP_V1(2),
}

internal data class PluginDependencyRecoveryComponent(
    val kind: PluginDependencyRecoveryComponentKind,
    /** Fixed "python"/"surface" or the logical Remote MCP server id. */
    val componentId: String,
    val transactionId: String,
    val stateDigest: String,
    val secondaryTransactionId: String? = null,
    val secondaryStateDigest: String? = null,
) {
    init {
        PluginInstallJournalBounds.requireOpaqueId(componentId, "dependency component")
        PluginInstallJournalBounds.requireOpaqueId(transactionId, "dependency transaction")
        PluginInstallJournalBounds.requireSha256(stateDigest, "dependency state")
        require((secondaryTransactionId == null) == (secondaryStateDigest == null)) {
            "Secondary dependency recovery identity must be complete or absent"
        }
        secondaryTransactionId?.let {
            PluginInstallJournalBounds.requireOpaqueId(it, "secondary dependency transaction")
        }
        secondaryStateDigest?.let {
            PluginInstallJournalBounds.requireSha256(it, "secondary dependency state")
        }
        when (kind) {
            PluginDependencyRecoveryComponentKind.PYTHON_ENVIRONMENT_V1 ->
                require(componentId == PYTHON_COMPONENT_ID) { "Invalid Python component id" }
            PluginDependencyRecoveryComponentKind.PLUGIN_SURFACE_V1 -> {
                require(componentId == PLUGIN_SURFACE_COMPONENT_ID) {
                    "Invalid plugin surface component id"
                }
                require(secondaryTransactionId == null) {
                    "Plugin surface recovery has no secondary identity"
                }
            }
            PluginDependencyRecoveryComponentKind.REMOTE_MCP_V1 ->
                require(secondaryTransactionId == null) {
                    "Remote MCP recovery has no secondary identity"
                }
        }
    }

    internal companion object {
        const val PYTHON_COMPONENT_ID = "python"
        const val PLUGIN_SURFACE_COMPONENT_ID = "surface"
    }
}

/**
 * Opaque durable coordinates for reconstructing a local dependency transaction after a restart.
 * Paths and callables are intentionally excluded; the owning store must independently prove every
 * digest and transaction receipt before acting on these identifiers.
 */
internal data class PluginDependencyRecoveryDescriptor(
    val kind: PluginDependencyRecoveryKind,
    val environmentTransactionId: String = COMPOSITE_PLACEHOLDER_TRANSACTION_ID,
    val environmentDigest: String = COMPOSITE_PLACEHOLDER_DIGEST,
    val entrypointTransactionId: String? = null,
    val entrypointMetadataDigest: String? = null,
    val components: List<PluginDependencyRecoveryComponent> = emptyList(),
) {
    init {
        when (kind) {
            PluginDependencyRecoveryKind.PYTHON_ENVIRONMENT_V1 -> {
                require(components.isEmpty()) { "Legacy Python recovery cannot contain components" }
                PluginInstallJournalBounds.requireOpaqueId(
                    environmentTransactionId,
                    "environment transaction",
                )
                PluginInstallJournalBounds.requireSha256(environmentDigest, "environment digest")
                require((entrypointTransactionId == null) == (entrypointMetadataDigest == null)) {
                    "Python entrypoint recovery identity must be complete or absent"
                }
                entrypointTransactionId?.let {
                    PluginInstallJournalBounds.requireOpaqueId(it, "entrypoint transaction")
                }
                entrypointMetadataDigest?.let {
                    PluginInstallJournalBounds.requireSha256(it, "entrypoint metadata digest")
                }
            }
            PluginDependencyRecoveryKind.COMPOSITE_V1 -> {
                require(environmentTransactionId == COMPOSITE_PLACEHOLDER_TRANSACTION_ID &&
                    environmentDigest == COMPOSITE_PLACEHOLDER_DIGEST &&
                    entrypointTransactionId == null && entrypointMetadataDigest == null) {
                    "Composite recovery cannot carry legacy Python coordinates"
                }
                require(components.size in 1..PluginInstallJournalBounds.MAX_DEPENDENCY_COMPONENTS) {
                    "Invalid composite dependency component count"
                }
                require(components == components.sortedWith(COMPONENT_ORDER)) {
                    "Composite dependency components are not canonical"
                }
                require(components.map { it.kind to it.componentId }.distinct().size ==
                    components.size) {
                    "Duplicate composite dependency component"
                }
                require(components.count {
                    it.kind == PluginDependencyRecoveryComponentKind.PYTHON_ENVIRONMENT_V1
                } <= 1) { "Multiple Python dependency components" }
                require(components.count {
                    it.kind == PluginDependencyRecoveryComponentKind.PLUGIN_SURFACE_V1
                } <= 1) { "Multiple plugin surface dependency components" }
                require(components.count {
                    it.kind == PluginDependencyRecoveryComponentKind.REMOTE_MCP_V1
                } <= PluginInstallJournalBounds.MAX_REMOTE_MCP_DEPENDENCY_COMPONENTS) {
                    "Too many Remote MCP dependency components"
                }
            }
        }
    }

    fun asComponents(): List<PluginDependencyRecoveryComponent> = when (kind) {
        PluginDependencyRecoveryKind.PYTHON_ENVIRONMENT_V1 -> listOf(
            PluginDependencyRecoveryComponent(
                kind = PluginDependencyRecoveryComponentKind.PYTHON_ENVIRONMENT_V1,
                componentId = PluginDependencyRecoveryComponent.PYTHON_COMPONENT_ID,
                transactionId = environmentTransactionId,
                stateDigest = environmentDigest,
                secondaryTransactionId = entrypointTransactionId,
                secondaryStateDigest = entrypointMetadataDigest,
            ),
        )
        PluginDependencyRecoveryKind.COMPOSITE_V1 -> components
    }

    internal companion object {
        private const val COMPOSITE_PLACEHOLDER_TRANSACTION_ID = "composite"
        private const val COMPOSITE_PLACEHOLDER_DIGEST =
            "0000000000000000000000000000000000000000000000000000000000000000"

        val COMPONENT_ORDER = compareBy<PluginDependencyRecoveryComponent>(
            { it.kind.canonicalOrder },
            PluginDependencyRecoveryComponent::componentId,
        )

        fun composite(
            components: Collection<PluginDependencyRecoveryComponent>,
        ): PluginDependencyRecoveryDescriptor = PluginDependencyRecoveryDescriptor(
            kind = PluginDependencyRecoveryKind.COMPOSITE_V1,
            components = components.sortedWith(COMPONENT_ORDER),
        )
    }
}

/**
 * Wall time is diagnostic. Elapsed time is authoritative only while [ownerProcessEpoch] remains
 * unchanged; it may reset across a reboot/process-epoch takeover.
 */
internal data class PluginInstallJournalTimestamps(
    val createdAtWallEpochMillis: Long,
    val updatedAtWallEpochMillis: Long,
    val createdAtElapsedRealtimeMillis: Long,
    val updatedAtElapsedRealtimeMillis: Long,
) {
    init {
        require(createdAtWallEpochMillis >= 0L) { "Invalid created wall timestamp" }
        require(updatedAtWallEpochMillis >= 0L) { "Invalid updated wall timestamp" }
        require(createdAtElapsedRealtimeMillis >= 0L) { "Invalid created elapsed timestamp" }
        require(updatedAtElapsedRealtimeMillis >= 0L) { "Invalid updated elapsed timestamp" }
    }
}

/** One compare-and-set revision. Identity and creation timestamps never change. */
internal data class PluginInstallJournalEntry(
    val operationId: String,
    val leaseId: String,
    val revision: Long,
    val ownerProcessEpoch: String,
    val phase: PluginInstallJournalPhase,
    val identity: PluginInstallIdentity,
    val installAttempt: PluginInstallAttempt,
    val dependencyRecovery: PluginDependencyRecoveryDescriptor?,
    val timestamps: PluginInstallJournalTimestamps,
) {
    init {
        PluginInstallJournalBounds.requireOpaqueId(operationId, "operation")
        PluginInstallJournalBounds.requireOpaqueId(leaseId, "lease")
        require(revision in 1..PluginInstallJournalBounds.MAX_REVISION) {
            "Invalid plugin install journal revision"
        }
        PluginInstallJournalBounds.requireOpaqueId(ownerProcessEpoch, "owner process epoch")
    }

    val cursor: PluginInstallJournalCursor
        get() = PluginInstallJournalCursor(operationId, leaseId, revision)
}

/** Exact CAS key. A recovery process must acquire a new lease by replacing this exact cursor. */
internal data class PluginInstallJournalCursor(
    val operationId: String,
    val leaseId: String,
    val revision: Long,
) {
    init {
        PluginInstallJournalBounds.requireOpaqueId(operationId, "operation")
        PluginInstallJournalBounds.requireOpaqueId(leaseId, "lease")
        require(revision in 1..PluginInstallJournalBounds.MAX_REVISION) {
            "Invalid plugin install journal revision"
        }
    }
}

internal enum class PluginInstallJournalMutationResult {
    APPLIED,
    OPERATION_ALREADY_EXISTS,
    CURSOR_MISMATCH,
    JOURNAL_FULL,
}

internal object PluginInstallJournalBounds {
    const val MAX_DOCUMENT_BYTES = 128 * 1024
    const val MAX_ENTRIES = 16
    const val MAX_ID_CHARS = 128
    const val MAX_NAME_CHARS = 512
    const val MAX_VERSION_CHARS = 512
    const val MAX_PATH_CHARS = 4_096
    /** Python + plugin surface + at most sixteen Remote MCP servers. */
    const val MAX_DEPENDENCY_COMPONENTS = 18
    const val MAX_REMOTE_MCP_DEPENDENCY_COMPONENTS = 16
    const val MAX_ATTEMPT_ORDINAL = 1_000_000
    const val MAX_REVISION = 1_000_000_000L

    private val OPAQUE_ID = Regex("[A-Za-z0-9._:-]{1,$MAX_ID_CHARS}")
    private val SHA_256 = Regex("[0-9a-f]{64}")

    fun requireOpaqueId(value: String, label: String): String {
        require(OPAQUE_ID.matches(value)) { "Invalid $label id" }
        return value
    }

    fun requireSha256(value: String, label: String): String {
        require(SHA_256.matches(value)) { "Invalid $label SHA-256" }
        return value
    }

    fun requireToken(value: String, label: String, maxChars: Int = MAX_ID_CHARS): String {
        require(value.isNotBlank() && value.length <= maxChars) { "Invalid $label" }
        require(value.none(Char::isISOControl)) { "$label contains control characters" }
        return value
    }

    fun requireAbsolutePath(value: String, label: String): String {
        require(value.length in 1..MAX_PATH_CHARS && '\u0000' !in value) { "Invalid $label" }
        require(File(value).isAbsolute) { "$label must be absolute" }
        return value
    }
}
