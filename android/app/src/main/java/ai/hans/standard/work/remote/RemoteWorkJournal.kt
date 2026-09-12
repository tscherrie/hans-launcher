package ai.hans.standard.work.remote

import ai.hans.standard.artifacts.ArtifactHandle
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class RemoteWorkImportedArtifact(
    val remoteId: String,
    val localHandle: ArtifactHandle,
) {
    init {
        require(SAFE_ID.matches(remoteId) && ".." !in remoteId)
    }
}

internal data class RemoteWorkJournalRecord(
    val operationId: RemoteWorkOperationId,
    val worker: RemoteWorkerId,
    val adapter: RemoteWorkAdapterId,
    val configurationDigest: String,
    val requestDigest: String,
    val maximumOutputBytes: Long,
    val maximumArtifacts: Int,
    val phase: RemoteWorkPhase,
    val executionId: RemoteExecutionId?,
    val resultArtifacts: List<RemoteWorkResultArtifact>,
    val outputJson: String?,
    val importedArtifacts: List<RemoteWorkImportedArtifact>,
    val generation: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(SHA_256.matches(configurationDigest) && SHA_256.matches(requestDigest))
        require(maximumOutputBytes in 1..RemoteWorkLimits.MAX_OUTPUT_BYTES)
        require(maximumArtifacts in 1..RemoteWorkLimits.MAX_ARTIFACTS)
        require(generation >= 1L)
        require(createdAtEpochMillis >= 0L && updatedAtEpochMillis >= createdAtEpochMillis)
        require(resultArtifacts.size <= maximumArtifacts)
        require(resultArtifacts.fold(0L) { total, artifact ->
            Math.addExact(total, artifact.byteCount)
        } <= maximumOutputBytes)
        require(resultArtifacts.map { it.opaqueRemoteId }.distinct().size == resultArtifacts.size)
        require(importedArtifacts.size <= resultArtifacts.size)
        require(importedArtifacts.map { it.remoteId }.distinct().size == importedArtifacts.size)
        require(importedArtifacts.all { imported ->
            resultArtifacts.any { it.opaqueRemoteId == imported.remoteId }
        })
        require(
            outputJson == null ||
                outputJson.toByteArray(StandardCharsets.UTF_8).size <= MAX_OUTPUT_JSON_BYTES,
        )
        when (phase) {
            RemoteWorkPhase.PREPARED,
            RemoteWorkPhase.SUBMIT_INTENT,
            -> require(executionId == null && resultArtifacts.isEmpty() && importedArtifacts.isEmpty())
            RemoteWorkPhase.ACCEPTED,
            RemoteWorkPhase.RUNNING,
            RemoteWorkPhase.CANCEL_INTENT,
            RemoteWorkPhase.CANCELLED,
            RemoteWorkPhase.FAILED,
            RemoteWorkPhase.AMBIGUOUS,
            -> require(importedArtifacts.isEmpty())
            RemoteWorkPhase.RESULT_PROVEN -> require(executionId != null && importedArtifacts.isEmpty())
            RemoteWorkPhase.IMPORTING -> require(
                executionId != null && importedArtifacts.size < resultArtifacts.size,
            )
            RemoteWorkPhase.IMPORT_COMMITTED -> require(
                executionId != null && importedArtifacts.size == resultArtifacts.size,
            )
        }
        if (phase in REMOTE_ID_REQUIRED_PHASES) require(executionId != null)
    }

    fun exactRemoteReceipt(receipt: RemoteWorkStatusReceipt): Boolean =
        operationId == receipt.operationId &&
            worker == receipt.worker &&
            configurationDigest == receipt.configurationDigest &&
            requestDigest == receipt.requestDigest &&
            executionId == receipt.executionId
}

/** Durable, exact-CAS journal. No ambiguous submit/cancel is silently retried. */
internal class RemoteWorkJournal(
    directory: File,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val root = directory.absoluteFile
    private val lock = Any()
    @Volatile private var unavailable = false

    init {
        runCatching {
            requireRoot()
            cleanupTemporaryFiles()
            require(recordFiles().size <= MAX_RECORDS) { "Remote work journal is full" }
        }.onFailure { unavailable = true }
    }

    fun isAvailable(): Boolean = !unavailable

    fun create(
        request: RemoteWorkRequest,
        configurationDigest: String,
        requestDigest: String,
    ): RemoteWorkJournalRecord = synchronized(lock) {
        checkAvailable()
        require(recordFiles().size < MAX_RECORDS) { "Remote work journal is full" }
        require(readOrNull(request.operationId) == null) { "Duplicate remote work operation" }
        val now = nowEpochMillis().also { require(it >= 0L) }
        RemoteWorkJournalRecord(
            operationId = request.operationId,
            worker = request.worker,
            adapter = request.adapter,
            configurationDigest = configurationDigest,
            requestDigest = requestDigest,
            maximumOutputBytes = request.limits.maximumOutputBytes,
            maximumArtifacts = request.limits.maximumArtifacts,
            phase = RemoteWorkPhase.PREPARED,
            executionId = null,
            resultArtifacts = emptyList(),
            outputJson = null,
            importedArtifacts = emptyList(),
            generation = 1L,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        ).also(::writeNew)
    }

    fun read(operationId: RemoteWorkOperationId): RemoteWorkJournalRecord = synchronized(lock) {
        checkNotNull(readOrNull(operationId)) { "Unknown remote work operation" }
    }

    fun readOrNull(operationId: RemoteWorkOperationId): RemoteWorkJournalRecord? = synchronized(lock) {
        checkAvailable()
        val file = recordFile(operationId)
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return@synchronized null
        readFile(file).also { require(it.operationId == operationId) }
    }

    fun list(): List<RemoteWorkJournalRecord> = synchronized(lock) {
        checkAvailable()
        recordFiles().map(::readFile).sortedBy { it.createdAtEpochMillis }
    }

    fun compareAndSet(
        expected: RemoteWorkJournalRecord,
        replacement: RemoteWorkJournalRecord,
    ): Boolean = synchronized(lock) {
        checkAvailable()
        require(replacement.operationId == expected.operationId)
        require(replacement.worker == expected.worker && replacement.adapter == expected.adapter)
        require(replacement.configurationDigest == expected.configurationDigest)
        require(replacement.requestDigest == expected.requestDigest)
        require(replacement.maximumOutputBytes == expected.maximumOutputBytes)
        require(replacement.maximumArtifacts == expected.maximumArtifacts)
        require(replacement.createdAtEpochMillis == expected.createdAtEpochMillis)
        require(replacement.generation == expected.generation + 1L)
        require(replacement.updatedAtEpochMillis >= expected.updatedAtEpochMillis)
        val file = recordFile(expected.operationId)
        if (!file.exists()) return@synchronized false
        val actual = readFile(file)
        if (actual != expected) return@synchronized false
        writeReplacing(file, replacement)
        true
    }

    fun transition(
        expected: RemoteWorkJournalRecord,
        phase: RemoteWorkPhase,
        executionId: RemoteExecutionId? = expected.executionId,
        resultArtifacts: List<RemoteWorkResultArtifact> = expected.resultArtifacts,
        outputJson: String? = expected.outputJson,
        importedArtifacts: List<RemoteWorkImportedArtifact> = expected.importedArtifacts,
    ): RemoteWorkJournalRecord {
        require(phase in allowedNextPhases(expected.phase)) { "Invalid remote work transition" }
        val replacement = expected.copy(
            phase = phase,
            executionId = executionId,
            resultArtifacts = resultArtifacts,
            outputJson = outputJson,
            importedArtifacts = importedArtifacts,
            generation = Math.addExact(expected.generation, 1L),
            updatedAtEpochMillis = maxOf(nowEpochMillis(), expected.updatedAtEpochMillis),
        )
        check(compareAndSet(expected, replacement)) { "Remote work journal changed" }
        return replacement
    }

    private fun writeNew(record: RemoteWorkJournalRecord) {
        val file = recordFile(record.operationId)
        require(!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        write(file, record, replace = false)
    }

    private fun writeReplacing(file: File, record: RemoteWorkJournalRecord) =
        write(file, record, replace = true)

    private fun write(file: File, record: RemoteWorkJournalRecord, replace: Boolean) {
        val bytes = RemoteWorkJournalCodec.encode(record)
        val temporary = File(root, ".tmp-${UUID.randomUUID()}")
        require(temporary.createNewFile()) { "Could not create remote work transaction" }
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            val options = buildList {
                add(StandardCopyOption.ATOMIC_MOVE)
                if (replace) add(StandardCopyOption.REPLACE_EXISTING)
            }.toTypedArray()
            try {
                Files.move(temporary.toPath(), file.toPath(), *options)
            } catch (_: AtomicMoveNotSupportedException) {
                throw IllegalStateException("Atomic remote work journal is unavailable")
            }
        } finally {
            if (Files.exists(temporary.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(temporary.toPath())
            ) {
                Files.deleteIfExists(temporary.toPath())
            }
        }
    }

    private fun readFile(file: File): RemoteWorkJournalRecord {
        require(file.isFile && !Files.isSymbolicLink(file.toPath()))
        require(file.length() in 1..MAX_RECORD_BYTES.toLong())
        return RemoteWorkJournalCodec.decode(file.readBytes())
    }

    private fun recordFiles(): List<File> = root.listFiles().orEmpty().filter { file ->
        file.name.startsWith(RECORD_PREFIX) && file.name.endsWith(RECORD_SUFFIX)
    }.also { files ->
        require(files.size <= MAX_RECORDS)
        require(files.all { file ->
            file.name.removePrefix(RECORD_PREFIX).removeSuffix(RECORD_SUFFIX).matches(SHA_256)
        })
    }

    private fun recordFile(operationId: RemoteWorkOperationId): File = File(
        root,
        "$RECORD_PREFIX${sha256(operationId.value.toByteArray(StandardCharsets.UTF_8))}$RECORD_SUFFIX",
    ).also { check(it.parentFile == root) }

    private fun cleanupTemporaryFiles() {
        root.listFiles().orEmpty().filter { it.name.startsWith(".tmp-") }.forEach { candidate ->
            require(candidate.isFile && !Files.isSymbolicLink(candidate.toPath()))
            Files.delete(candidate.toPath())
        }
    }

    private fun requireRoot() {
        if (!root.exists()) require(root.mkdirs())
        require(root.isDirectory && !Files.isSymbolicLink(root.toPath()))
    }

    private fun checkAvailable() = check(!unavailable) { "Remote work journal is unavailable" }

    private companion object {
        const val MAX_RECORDS = 128
        const val MAX_RECORD_BYTES = 1024 * 1024
        const val RECORD_PREFIX = "operation-"
        const val RECORD_SUFFIX = ".json"
    }
}

private object RemoteWorkJournalCodec {
    fun encode(record: RemoteWorkJournalRecord): ByteArray = JSONObject()
        .put("version", 1)
        .put("operationId", record.operationId.value)
        .put("worker", record.worker.value)
        .put("adapter", record.adapter.value)
        .put("configurationDigest", record.configurationDigest)
        .put("requestDigest", record.requestDigest)
        .put("maximumOutputBytes", record.maximumOutputBytes)
        .put("maximumArtifacts", record.maximumArtifacts)
        .put("phase", record.phase.name)
        .put("executionId", record.executionId?.value ?: JSONObject.NULL)
        .put("resultArtifacts", JSONArray().apply { record.resultArtifacts.forEach { put(it.json()) } })
        .put("outputJson", record.outputJson ?: JSONObject.NULL)
        .put("importedArtifacts", JSONArray().apply { record.importedArtifacts.forEach { put(it.json()) } })
        .put("generation", record.generation)
        .put("createdAtEpochMillis", record.createdAtEpochMillis)
        .put("updatedAtEpochMillis", record.updatedAtEpochMillis)
        .toString().toByteArray(StandardCharsets.UTF_8).also {
            require(it.size <= 1024 * 1024)
        }

    fun decode(bytes: ByteArray): RemoteWorkJournalRecord {
        require(bytes.size in 1..(1024 * 1024))
        val text = bytes.toString(StandardCharsets.UTF_8)
        require(text.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes))
        val root = JSONObject(text)
        require(root.keys().asSequence().toSet() == FIELDS)
        require(root.getInt("version") == 1)
        val artifacts = root.getJSONArray("resultArtifacts").let { array ->
            require(array.length() <= RemoteWorkLimits.MAX_ARTIFACTS)
            (0 until array.length()).map { index -> array.getJSONObject(index).artifact() }
        }
        val imported = root.getJSONArray("importedArtifacts").let { array ->
            require(array.length() <= RemoteWorkLimits.MAX_ARTIFACTS)
            (0 until array.length()).map { index -> array.getJSONObject(index).imported() }
        }
        return RemoteWorkJournalRecord(
            operationId = RemoteWorkOperationId(root.getString("operationId")),
            worker = RemoteWorkerId(root.getString("worker")),
            adapter = RemoteWorkAdapterId(root.getString("adapter")),
            configurationDigest = root.getString("configurationDigest"),
            requestDigest = root.getString("requestDigest"),
            maximumOutputBytes = root.getLong("maximumOutputBytes"),
            maximumArtifacts = root.getInt("maximumArtifacts"),
            phase = RemoteWorkPhase.valueOf(root.getString("phase")),
            executionId = root.nullableString("executionId")?.let(::RemoteExecutionId),
            resultArtifacts = artifacts,
            outputJson = root.nullableString("outputJson"),
            importedArtifacts = imported,
            generation = root.getLong("generation"),
            createdAtEpochMillis = root.getLong("createdAtEpochMillis"),
            updatedAtEpochMillis = root.getLong("updatedAtEpochMillis"),
        )
    }

    private fun RemoteWorkResultArtifact.json() = JSONObject()
        .put("opaqueRemoteId", opaqueRemoteId)
        .put("displayName", displayName)
        .put("mimeType", mimeType)
        .put("byteCount", byteCount)
        .put("sha256", sha256)

    private fun JSONObject.artifact(): RemoteWorkResultArtifact {
        require(keys().asSequence().toSet() == ARTIFACT_FIELDS)
        return RemoteWorkResultArtifact(
            opaqueRemoteId = getString("opaqueRemoteId"),
            displayName = getString("displayName"),
            mimeType = getString("mimeType"),
            byteCount = getLong("byteCount"),
            sha256 = getString("sha256"),
        )
    }

    private fun RemoteWorkImportedArtifact.json() = JSONObject()
        .put("remoteId", remoteId)
        .put("localHandle", localHandle.value)

    private fun JSONObject.imported(): RemoteWorkImportedArtifact {
        require(keys().asSequence().toSet() == IMPORTED_FIELDS)
        return RemoteWorkImportedArtifact(
            remoteId = getString("remoteId"),
            localHandle = ArtifactHandle(getString("localHandle")),
        )
    }

    private fun JSONObject.nullableString(name: String): String? =
        if (isNull(name)) null else getString(name)

    private val FIELDS = setOf(
        "version", "operationId", "worker", "adapter", "configurationDigest",
        "requestDigest", "maximumOutputBytes", "maximumArtifacts", "phase", "executionId", "resultArtifacts", "outputJson",
        "importedArtifacts", "generation", "createdAtEpochMillis", "updatedAtEpochMillis",
    )
    private val ARTIFACT_FIELDS = setOf(
        "opaqueRemoteId", "displayName", "mimeType", "byteCount", "sha256",
    )
    private val IMPORTED_FIELDS = setOf("remoteId", "localHandle")
}

private fun allowedNextPhases(current: RemoteWorkPhase): Set<RemoteWorkPhase> = when (current) {
    RemoteWorkPhase.PREPARED -> setOf(RemoteWorkPhase.SUBMIT_INTENT)
    RemoteWorkPhase.SUBMIT_INTENT -> setOf(
        RemoteWorkPhase.ACCEPTED,
        RemoteWorkPhase.RUNNING,
        RemoteWorkPhase.RESULT_PROVEN,
        RemoteWorkPhase.FAILED,
        RemoteWorkPhase.AMBIGUOUS,
    )
    RemoteWorkPhase.ACCEPTED,
    RemoteWorkPhase.RUNNING,
    RemoteWorkPhase.AMBIGUOUS,
    -> setOf(
        RemoteWorkPhase.ACCEPTED,
        RemoteWorkPhase.RUNNING,
        RemoteWorkPhase.RESULT_PROVEN,
        RemoteWorkPhase.CANCEL_INTENT,
        RemoteWorkPhase.CANCELLED,
        RemoteWorkPhase.FAILED,
        RemoteWorkPhase.AMBIGUOUS,
    )
    RemoteWorkPhase.RESULT_PROVEN -> setOf(
        RemoteWorkPhase.RESULT_PROVEN,
        RemoteWorkPhase.IMPORTING,
        RemoteWorkPhase.IMPORT_COMMITTED,
    )
    RemoteWorkPhase.IMPORTING -> setOf(
        RemoteWorkPhase.IMPORTING,
        RemoteWorkPhase.IMPORT_COMMITTED,
    )
    RemoteWorkPhase.CANCEL_INTENT -> setOf(
        RemoteWorkPhase.ACCEPTED,
        RemoteWorkPhase.RUNNING,
        RemoteWorkPhase.CANCELLED,
        RemoteWorkPhase.RESULT_PROVEN,
        RemoteWorkPhase.FAILED,
        RemoteWorkPhase.AMBIGUOUS,
    )
    RemoteWorkPhase.IMPORT_COMMITTED,
    RemoteWorkPhase.CANCELLED,
    RemoteWorkPhase.FAILED,
    -> emptySet()
}

private val REMOTE_ID_REQUIRED_PHASES = setOf(
    RemoteWorkPhase.ACCEPTED,
    RemoteWorkPhase.RUNNING,
    RemoteWorkPhase.RESULT_PROVEN,
    RemoteWorkPhase.IMPORTING,
    RemoteWorkPhase.IMPORT_COMMITTED,
    RemoteWorkPhase.CANCEL_INTENT,
    RemoteWorkPhase.CANCELLED,
    RemoteWorkPhase.FAILED,
)
