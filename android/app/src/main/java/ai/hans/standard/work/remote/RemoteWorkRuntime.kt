package ai.hans.standard.work.remote

import ai.hans.standard.artifacts.ArtifactMetadata
import ai.hans.standard.artifacts.ArtifactOrigin
import ai.hans.standard.artifacts.AtomicArtifactStore

internal class RemoteWorkAmbiguousFailure : IllegalStateException("remote_work_ambiguous")

internal data class RemoteWorkImportReceipt(
    val operationId: RemoteWorkOperationId,
    val executionId: RemoteExecutionId,
    val artifacts: List<RemoteWorkImportedArtifact>,
    val outputJson: String?,
)

/**
 * Receipt-driven coordinator. Every potentially mutating remote boundary is preceded by a durable
 * intent. Network ambiguity is resolved only by exact lookup/status receipts; submit and cancel
 * are never silently replayed.
 */
internal class RemoteWorkRuntime(
    private val activation: RemoteWorkerActivation,
    private val transport: RemoteWorkerTransport,
    private val journal: RemoteWorkJournal,
    private val artifactStore: AtomicArtifactStore,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    fun submit(
        request: RemoteWorkRequest,
        payloads: RemoteWorkPayloadSource,
    ): RemoteWorkJournalRecord = synchronized(lock) {
        activation.requireFresh(nowEpochMillis())
        require(request.worker == activation.configuration.identity.workerId)
        activation.requireAdapter(request.adapter)
        val descriptors = payloads.descriptors(request)
        val requestDigest = RemoteWorkRequestDigest.compute(request, descriptors)
        val prepared = journal.readOrNull(request.operationId)?.let { existing ->
            requireExactRequest(existing, request, requestDigest)
            if (existing.phase != RemoteWorkPhase.PREPARED) return@synchronized existing
            existing
        } ?: journal.create(
                request = request,
                configurationDigest = activation.configuration.identity.configurationDigest,
                requestDigest = requestDigest,
            )
        val intent = journal.transition(prepared, RemoteWorkPhase.SUBMIT_INTENT)
        try {
            applyReceipt(intent, transport.submit(activation.configuration.identity, request, payloads))
        } catch (failure: Throwable) {
            markAmbiguous(intent)
            throw if (failure is RemoteWorkAmbiguousFailure) failure else RemoteWorkAmbiguousFailure()
        }
    }

    fun status(operationId: RemoteWorkOperationId): RemoteWorkJournalRecord = synchronized(lock) {
        val record = journal.read(operationId)
        if (record.phase in TERMINAL_PHASES) return@synchronized record
        val executionId = record.executionId
            ?: return@synchronized recoverAmbiguousLocked(record)
        val receipt = transport.status(
            activation.configuration.identity,
            operationId,
            executionId,
        )
        applyReceipt(record, receipt)
    }

    fun cancel(operationId: RemoteWorkOperationId): RemoteWorkJournalRecord = synchronized(lock) {
        var record = journal.read(operationId)
        if (record.phase in TERMINAL_PHASES || record.phase == RemoteWorkPhase.RESULT_PROVEN ||
            record.phase == RemoteWorkPhase.IMPORTING
        ) {
            return@synchronized record
        }
        if (record.executionId == null) record = recoverAmbiguousLocked(record)
        if (record.phase in TERMINAL_PHASES || record.phase == RemoteWorkPhase.RESULT_PROVEN) {
            return@synchronized record
        }
        val executionId = requireNotNull(record.executionId) { "Remote execution is unresolved" }
        val intent = journal.transition(record, RemoteWorkPhase.CANCEL_INTENT)
        try {
            applyReceipt(
                intent,
                transport.cancel(
                    activation.configuration.identity,
                    operationId,
                    executionId,
                ),
            )
        } catch (failure: Throwable) {
            markAmbiguous(intent)
            throw if (failure is RemoteWorkAmbiguousFailure) failure else RemoteWorkAmbiguousFailure()
        }
    }

    fun recoverAmbiguous(operationId: RemoteWorkOperationId): RemoteWorkJournalRecord =
        synchronized(lock) { recoverAmbiguousLocked(journal.read(operationId)) }

    fun import(operationId: RemoteWorkOperationId): RemoteWorkImportReceipt = synchronized(lock) {
        var record = journal.read(operationId)
        if (record.phase == RemoteWorkPhase.IMPORT_COMMITTED) return@synchronized record.importReceipt()
        require(record.phase in setOf(RemoteWorkPhase.RESULT_PROVEN, RemoteWorkPhase.IMPORTING)) {
            "Remote work result is not ready to import"
        }
        val executionId = requireNotNull(record.executionId)
        record.resultArtifacts.forEach { remote ->
            if (record.importedArtifacts.any { it.remoteId == remote.opaqueRemoteId }) return@forEach
            val existing = findRecoveredImport(record, remote)
            val local = existing ?: transport.download(
                activation.configuration.identity,
                operationId,
                executionId,
                remote,
            ).use { download ->
                require(download.artifact == remote)
                artifactStore.put(
                    displayName = remote.displayName,
                    mimeType = remote.mimeType,
                    origin = ArtifactOrigin.REMOTE_WORKER,
                    source = download.input,
                    maxBytes = maxOf(1L, remote.byteCount),
                    workspaceHandle = record.requestDigest,
                ).also { metadata ->
                    require(metadata.byteCount == remote.byteCount && metadata.sha256 == remote.sha256) {
                        "Remote work artifact verification failed"
                    }
                }.handle
            }
            val imported = record.importedArtifacts + RemoteWorkImportedArtifact(
                remoteId = remote.opaqueRemoteId,
                localHandle = local,
            )
            record = journal.transition(
                record,
                phase = if (imported.size == record.resultArtifacts.size) {
                    RemoteWorkPhase.IMPORT_COMMITTED
                } else {
                    RemoteWorkPhase.IMPORTING
                },
                importedArtifacts = imported,
            )
        }
        if (record.resultArtifacts.isEmpty() && record.phase == RemoteWorkPhase.RESULT_PROVEN) {
            record = journal.transition(record, RemoteWorkPhase.IMPORT_COMMITTED)
        }
        require(record.phase == RemoteWorkPhase.IMPORT_COMMITTED)
        record.importReceipt()
    }

    fun record(operationId: RemoteWorkOperationId): RemoteWorkJournalRecord = journal.read(operationId)

    private fun recoverAmbiguousLocked(record: RemoteWorkJournalRecord): RemoteWorkJournalRecord {
        require(record.phase in RECOVERABLE_PHASES) { "Remote work operation is not ambiguous" }
        val receipt = record.executionId?.let { executionId ->
            transport.status(
                activation.configuration.identity,
                record.operationId,
                executionId,
            )
        } ?: transport.lookup(
            activation.configuration.identity,
            record.operationId,
            record.requestDigest,
        )
        if (receipt == null) {
            if (record.phase != RemoteWorkPhase.AMBIGUOUS) markAmbiguous(record)
            throw RemoteWorkAmbiguousFailure()
        }
        return applyReceipt(record, receipt)
    }

    private fun applyReceipt(
        current: RemoteWorkJournalRecord,
        receipt: RemoteWorkStatusReceipt,
    ): RemoteWorkJournalRecord {
        require(receipt.operationId == current.operationId)
        require(receipt.worker == current.worker)
        require(receipt.configurationDigest == current.configurationDigest)
        require(receipt.requestDigest == current.requestDigest)
        require(current.executionId == null || current.executionId == receipt.executionId)
        val phase = receipt.phase
        require(phase in REMOTE_APPLICABLE_PHASES)
        return journal.transition(
            current,
            phase = phase,
            executionId = receipt.executionId,
            resultArtifacts = receipt.resultArtifacts,
            outputJson = receipt.outputJson,
            importedArtifacts = emptyList(),
        )
    }

    private fun markAmbiguous(expected: RemoteWorkJournalRecord) {
        runCatching {
            val actual = journal.read(expected.operationId)
            if (actual == expected && RemoteWorkPhase.AMBIGUOUS in allowedRuntimeNext(actual.phase)) {
                journal.transition(actual, RemoteWorkPhase.AMBIGUOUS)
            }
        }
    }

    private fun findRecoveredImport(
        record: RemoteWorkJournalRecord,
        remote: RemoteWorkResultArtifact,
    ) = sequence {
        var offset = 0
        while (offset < MAX_IMPORT_RECOVERY_SCAN) {
            val page = artifactStore.listMetadata(offset, IMPORT_RECOVERY_PAGE_SIZE)
            yieldAll(page)
            if (page.size < IMPORT_RECOVERY_PAGE_SIZE) break
            offset += page.size
        }
    }.singleOrNull { metadata: ArtifactMetadata ->
        metadata.origin == ArtifactOrigin.REMOTE_WORKER &&
            metadata.workspaceHandle == record.requestDigest &&
            metadata.displayName == remote.displayName &&
            metadata.mimeType == remote.mimeType &&
            metadata.byteCount == remote.byteCount &&
            metadata.sha256 == remote.sha256
    }?.handle

    private fun requireExactRequest(
        record: RemoteWorkJournalRecord,
        request: RemoteWorkRequest,
        requestDigest: String,
    ) {
        require(record.worker == request.worker && record.adapter == request.adapter)
        require(record.configurationDigest == activation.configuration.identity.configurationDigest)
        require(record.requestDigest == requestDigest)
        require(record.maximumOutputBytes == request.limits.maximumOutputBytes)
        require(record.maximumArtifacts == request.limits.maximumArtifacts)
    }

    private fun RemoteWorkJournalRecord.importReceipt() = RemoteWorkImportReceipt(
        operationId = operationId,
        executionId = requireNotNull(executionId),
        artifacts = importedArtifacts,
        outputJson = outputJson,
    )

    private companion object {
        const val IMPORT_RECOVERY_PAGE_SIZE = 500
        const val MAX_IMPORT_RECOVERY_SCAN = 100_000
        val TERMINAL_PHASES = setOf(
            RemoteWorkPhase.IMPORT_COMMITTED,
            RemoteWorkPhase.CANCELLED,
            RemoteWorkPhase.FAILED,
        )
        val RECOVERABLE_PHASES = setOf(
            RemoteWorkPhase.SUBMIT_INTENT,
            RemoteWorkPhase.ACCEPTED,
            RemoteWorkPhase.RUNNING,
            RemoteWorkPhase.CANCEL_INTENT,
            RemoteWorkPhase.AMBIGUOUS,
        )
        val REMOTE_APPLICABLE_PHASES = setOf(
            RemoteWorkPhase.ACCEPTED,
            RemoteWorkPhase.RUNNING,
            RemoteWorkPhase.RESULT_PROVEN,
            RemoteWorkPhase.CANCELLED,
            RemoteWorkPhase.FAILED,
        )
    }
}

private fun allowedRuntimeNext(current: RemoteWorkPhase): Set<RemoteWorkPhase> = when (current) {
    RemoteWorkPhase.SUBMIT_INTENT,
    RemoteWorkPhase.ACCEPTED,
    RemoteWorkPhase.RUNNING,
    RemoteWorkPhase.CANCEL_INTENT,
    RemoteWorkPhase.AMBIGUOUS,
    -> setOf(RemoteWorkPhase.AMBIGUOUS)
    else -> emptySet()
}
