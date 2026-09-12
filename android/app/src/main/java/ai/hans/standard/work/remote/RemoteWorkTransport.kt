package ai.hans.standard.work.remote

import ai.hans.standard.artifacts.ArtifactHandle
import ai.hans.standard.workspace.WorkspaceHandle
import ai.hans.standard.workspace.WorkspacePaths
import java.io.Closeable
import java.io.InputStream

internal data class RemoteWorkAdapterDescriptor(
    val id: RemoteWorkAdapterId,
    val version: String,
    val description: String,
) {
    init {
        require(version.matches(REMOTE_WORK_ADAPTER_VERSION))
        require(description.isNotBlank() && description.length <= 512)
        require(description.none(Char::isISOControl))
    }
}

/** A capability receipt is usable only while fresh and for the exact pinned configuration. */
internal data class RemoteWorkerActivation(
    val configuration: RemoteWorkerConfiguration,
    val probe: RemoteWorkerProbeReceipt,
    val adapters: List<RemoteWorkAdapterDescriptor>,
    val expiresAtEpochMillis: Long,
) {
    init {
        require(configuration.enabled) { "Remote work is disabled" }
        require(probe.proves(configuration.identity)) { "Remote worker probe identity mismatch" }
        require(adapters.isNotEmpty()) { "No approved remote work adapters" }
        require(adapters.map { it.id }.distinct().size == adapters.size)
        require(expiresAtEpochMillis >= probe.observedAtEpochMillis)
        val approvals = configuration.approvedAdapters.associateBy { it.id }
        require(adapters.all { adapter -> approvals[adapter.id]?.version == adapter.version }) {
            "Remote worker adapter approval mismatch"
        }
    }

    fun requireFresh(nowEpochMillis: Long) {
        require(nowEpochMillis in probe.observedAtEpochMillis..expiresAtEpochMillis) {
            "Remote worker probe is stale"
        }
    }

    fun requireAdapter(id: RemoteWorkAdapterId): RemoteWorkAdapterDescriptor =
        requireNotNull(adapters.singleOrNull { it.id == id }) {
            "Remote work adapter is not approved"
        }
}

internal data class RemoteWorkerProbeReceipt(
    val worker: RemoteWorkerId,
    val configurationDigest: String,
    val protocolVersion: Int,
    val adapters: List<RemoteWorkAdapterDescriptor>,
    val observedAtEpochMillis: Long,
) {
    init {
        require(SHA_256.matches(configurationDigest))
        require(protocolVersion == REMOTE_WORK_PROTOCOL_VERSION)
        require(adapters.isNotEmpty() && adapters.size <= MAX_REMOTE_ADAPTERS)
        require(adapters.map { it.id }.distinct().size == adapters.size)
        require(observedAtEpochMillis >= 0L)
    }

    fun proves(identity: RemoteWorkerConnectionIdentity): Boolean =
        worker == identity.workerId && configurationDigest == identity.configurationDigest
}

internal enum class RemoteWorkPayloadKind { WORKSPACE_FILE, ARTIFACT }

internal data class RemoteWorkPayloadDescriptor(
    val kind: RemoteWorkPayloadKind,
    val sha256: String,
    val byteCount: Long,
    val workspace: WorkspaceHandle? = null,
    val relativePath: String? = null,
    val artifact: ArtifactHandle? = null,
) {
    init {
        require(SHA_256.matches(sha256))
        require(byteCount in 0..RemoteWorkLimits.MAX_OUTPUT_BYTES)
        when (kind) {
            RemoteWorkPayloadKind.WORKSPACE_FILE -> {
                require(workspace != null && artifact == null)
                require(!relativePath.isNullOrBlank() && relativePath.length <= 4_096)
                require(relativePath.none(Char::isISOControl))
                WorkspacePaths.requireSafeRelativePath(relativePath)
            }
            RemoteWorkPayloadKind.ARTIFACT -> {
                require(artifact != null && workspace == null && relativePath == null)
            }
        }
    }
}

internal interface RemoteWorkPayloadLease : Closeable {
    val descriptor: RemoteWorkPayloadDescriptor
    val input: InputStream
}

internal interface RemoteWorkPayloadSource {
    fun descriptors(request: RemoteWorkRequest): List<RemoteWorkPayloadDescriptor>
    fun open(descriptor: RemoteWorkPayloadDescriptor): RemoteWorkPayloadLease
}

internal data class RemoteWorkStatusReceipt(
    val operationId: RemoteWorkOperationId,
    val executionId: RemoteExecutionId,
    val worker: RemoteWorkerId,
    val configurationDigest: String,
    val requestDigest: String,
    val phase: RemoteWorkPhase,
    val resultArtifacts: List<RemoteWorkResultArtifact>,
    val outputJson: String?,
    val observedAtEpochMillis: Long,
) {
    init {
        require(SHA_256.matches(configurationDigest))
        require(SHA_256.matches(requestDigest))
        require(phase in REMOTE_RECEIPT_PHASES)
        require(resultArtifacts.size <= RemoteWorkLimits.MAX_ARTIFACTS)
        require(resultArtifacts.map { it.opaqueRemoteId }.distinct().size == resultArtifacts.size)
        require(outputJson == null || outputJson.toByteArray().size <= MAX_OUTPUT_JSON_BYTES)
        require(observedAtEpochMillis >= 0L)
        if (phase == RemoteWorkPhase.RESULT_PROVEN) require(resultArtifacts.isNotEmpty() || outputJson != null)
        if (phase in setOf(
                RemoteWorkPhase.ACCEPTED,
                RemoteWorkPhase.RUNNING,
                RemoteWorkPhase.CANCELLED,
            )
        ) {
            require(resultArtifacts.isEmpty() && outputJson == null)
        }
    }

    fun proves(
        identity: RemoteWorkerConnectionIdentity,
        request: RemoteWorkRequest,
        expectedRequestDigest: String,
    ): Boolean = worker == identity.workerId &&
        worker == request.worker &&
        configurationDigest == identity.configurationDigest &&
        requestDigest == expectedRequestDigest &&
        operationId == request.operationId
}

internal interface RemoteWorkDownloadLease : Closeable {
    val artifact: RemoteWorkResultArtifact
    val input: InputStream
}

/** Every successful return is authenticated by the pinned TLS session used by the implementation. */
internal interface RemoteWorkerTransport {
    fun probe(identity: RemoteWorkerConnectionIdentity): RemoteWorkerProbeReceipt

    fun submit(
        identity: RemoteWorkerConnectionIdentity,
        request: RemoteWorkRequest,
        payloads: RemoteWorkPayloadSource,
    ): RemoteWorkStatusReceipt

    fun status(
        identity: RemoteWorkerConnectionIdentity,
        operationId: RemoteWorkOperationId,
        executionId: RemoteExecutionId,
    ): RemoteWorkStatusReceipt

    /** Exact operation lookup used after an ambiguous submit. It never creates or retries work. */
    fun lookup(
        identity: RemoteWorkerConnectionIdentity,
        operationId: RemoteWorkOperationId,
        requestDigest: String,
    ): RemoteWorkStatusReceipt?

    fun cancel(
        identity: RemoteWorkerConnectionIdentity,
        operationId: RemoteWorkOperationId,
        executionId: RemoteExecutionId,
    ): RemoteWorkStatusReceipt

    fun download(
        identity: RemoteWorkerConnectionIdentity,
        operationId: RemoteWorkOperationId,
        executionId: RemoteExecutionId,
        artifact: RemoteWorkResultArtifact,
    ): RemoteWorkDownloadLease
}

internal const val REMOTE_WORK_PROTOCOL_VERSION = 1
internal const val MAX_REMOTE_ADAPTERS = 128
internal const val MAX_OUTPUT_JSON_BYTES = 512 * 1024
private val REMOTE_RECEIPT_PHASES = setOf(
    RemoteWorkPhase.ACCEPTED,
    RemoteWorkPhase.RUNNING,
    RemoteWorkPhase.RESULT_PROVEN,
    RemoteWorkPhase.CANCELLED,
    RemoteWorkPhase.FAILED,
)
