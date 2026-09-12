package ai.hans.standard.media

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Content-free ownership receipt for private media passed to one exact App Server turn.
 *
 * The imported file must outlive turn/start or turn/steer acknowledgement. It is released only
 * after the correlated turn becomes terminal and deletion has been confirmed. Receipts contain
 * opaque identifiers only; prompts, paths and media metadata never enter this recovery file.
 */
internal data class MediaTurnLeaseKey(
    val threadId: String,
    val turnId: String,
) {
    init {
        MediaTurnLeaseBounds.requireOpaqueId(threadId, "thread")
        MediaTurnLeaseBounds.requireOpaqueId(turnId, "turn")
    }
}

internal data class MediaTurnLease(
    val key: MediaTurnLeaseKey,
    val importIds: Set<String>,
    val createdAtEpochMillis: Long,
) {
    init {
        require(importIds.isNotEmpty()) { "A media turn lease must own at least one import" }
        require(importIds.size <= MediaTurnLeaseBounds.MAX_IMPORTS_PER_TURN) {
            "Too many imports in one media turn lease"
        }
        importIds.forEach(MediaTurnLeaseBounds::requireImportId)
        require(createdAtEpochMillis >= 0L) { "Invalid media turn lease timestamp" }
    }
}

internal interface MediaTurnLeaseStorage {
    fun read(): List<MediaTurnLease>
    fun write(leases: List<MediaTurnLease>)
}

/** Android-private, atomic recovery storage for leases that survive Activity/process recreation. */
internal class AtomicFileMediaTurnLeaseStorage(
    context: Context,
    fileName: String = FILE_NAME,
) : MediaTurnLeaseStorage {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, fileName))

    override fun read(): List<MediaTurnLease> = synchronized(PROCESS_LOCK) {
        if (!file.baseFile.exists()) return emptyList()
        val bytes = file.readFully()
        require(bytes.isNotEmpty() && bytes.size <= MediaTurnLeaseBounds.MAX_DOCUMENT_BYTES) {
            "Invalid media turn lease document size"
        }
        MediaTurnLeaseCodec.decode(bytes.toString(StandardCharsets.UTF_8))
    }

    override fun write(leases: List<MediaTurnLease>) = synchronized(PROCESS_LOCK) {
        val bytes = MediaTurnLeaseCodec.encode(leases).toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MediaTurnLeaseBounds.MAX_DOCUMENT_BYTES) {
            "Media turn lease document is too large"
        }
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }

    private companion object {
        const val FILE_NAME = "media-turn-leases-v1.json"
        val PROCESS_LOCK = Any()
    }
}

/** Strict, bounded recovery codec. Unknown fields fail closed instead of changing ownership. */
internal object MediaTurnLeaseCodec {
    private const val SCHEMA = "hans.media-turn-leases"
    private const val VERSION = 1

    fun encode(leases: List<MediaTurnLease>): String {
        require(leases.size <= MediaTurnLeaseBounds.MAX_LEASES) { "Too many media turn leases" }
        require(leases.map(MediaTurnLease::key).distinct().size == leases.size) {
            "Duplicate media turn lease"
        }
        return JSONObject()
            .put("schema", SCHEMA)
            .put("version", VERSION)
            .put(
                "leases",
                JSONArray().also { array ->
                    leases.sortedWith(
                        compareBy<MediaTurnLease>(MediaTurnLease::createdAtEpochMillis)
                            .thenBy { it.key.threadId }
                            .thenBy { it.key.turnId },
                    ).forEach { lease ->
                        array.put(
                            JSONObject()
                                .put("threadId", lease.key.threadId)
                                .put("turnId", lease.key.turnId)
                                .put("importIds", JSONArray(lease.importIds.sorted()))
                                .put("createdAtEpochMillis", lease.createdAtEpochMillis),
                        )
                    }
                },
            )
            .toString()
    }

    fun decode(document: String): List<MediaTurnLease> {
        val bytes = document.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= MediaTurnLeaseBounds.MAX_DOCUMENT_BYTES) {
            "Invalid media turn lease document size"
        }
        val root = JSONObject(document)
        requireExactKeys(root, setOf("schema", "version", "leases"))
        require(root.getString("schema") == SCHEMA) { "Invalid media turn lease schema" }
        require(root.getInt("version") == VERSION) { "Invalid media turn lease version" }
        val values = root.getJSONArray("leases")
        require(values.length() <= MediaTurnLeaseBounds.MAX_LEASES) {
            "Too many media turn leases"
        }
        val result = ArrayList<MediaTurnLease>(values.length())
        repeat(values.length()) { index ->
            val value = values.getJSONObject(index)
            requireExactKeys(
                value,
                setOf("threadId", "turnId", "importIds", "createdAtEpochMillis"),
            )
            val imports = value.getJSONArray("importIds")
            require(imports.length() in 1..MediaTurnLeaseBounds.MAX_IMPORTS_PER_TURN) {
                "Invalid media import count"
            }
            val importIds = buildSet {
                repeat(imports.length()) { importIndex ->
                    val raw = imports.get(importIndex)
                    require(raw is String) { "Invalid media import id type" }
                    add(MediaTurnLeaseBounds.requireImportId(raw))
                }
            }
            require(importIds.size == imports.length()) { "Duplicate media import id" }
            result += MediaTurnLease(
                key = MediaTurnLeaseKey(
                    threadId = value.getString("threadId"),
                    turnId = value.getString("turnId"),
                ),
                importIds = importIds,
                createdAtEpochMillis = value.getLong("createdAtEpochMillis"),
            )
        }
        require(result.map(MediaTurnLease::key).distinct().size == result.size) {
            "Duplicate media turn lease"
        }
        return result
    }

    private fun requireExactKeys(value: JSONObject, expected: Set<String>) {
        require(value.keys().asSequence().toSet() == expected) {
            "Unexpected media turn lease fields"
        }
    }
}

/**
 * Serializes lease registration and terminal cleanup. Storage failure never causes early deletion:
 * the current process continues from its in-memory receipts and leaves files intact on ambiguity.
 */
internal class MediaTurnLeaseTracker(
    private val storage: MediaTurnLeaseStorage,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var initialized = false
    private var leases = linkedMapOf<MediaTurnLeaseKey, MediaTurnLease>()

    @Synchronized
    fun register(threadId: String, turnId: String, importIds: Set<String>): Boolean {
        if (importIds.isEmpty()) return true
        val key = MediaTurnLeaseKey(threadId, turnId)
        require(importIds.size <= MediaTurnLeaseBounds.MAX_IMPORTS_PER_TURN)
        importIds.forEach(MediaTurnLeaseBounds::requireImportId)
        ensureLoaded()

        val previous = leases[key]
        val merged = (previous?.importIds.orEmpty() + importIds).toSet()
        require(merged.size <= MediaTurnLeaseBounds.MAX_IMPORTS_PER_TURN)
        if (previous == null && leases.size >= MediaTurnLeaseBounds.MAX_LEASES) return false
        val updated = MediaTurnLease(
            key = key,
            importIds = merged,
            createdAtEpochMillis = previous?.createdAtEpochMillis ?: nowMillis(),
        )
        val next = LinkedHashMap(leases).apply { put(key, updated) }
        val persisted = runCatching { storage.write(next.values.toList()) }.isSuccess
        leases = next
        return persisted
    }

    /**
     * Deletes imports owned only by terminal turns, then atomically forgets fully released leases.
     * [deleteAndConfirmAbsent] must return true for both successful deletion and already-absent data.
     */
    @Synchronized
    fun releaseTerminalTurns(
        terminalTurns: Set<MediaTurnLeaseKey>,
        /** Threads proven idle by a completed thread/resume recovery. */
        terminalThreadIds: Set<String> = emptySet(),
        deleteAndConfirmAbsent: (String) -> Boolean,
    ): Set<MediaTurnLeaseKey> {
        if (terminalTurns.isEmpty() && terminalThreadIds.isEmpty()) return emptySet()
        terminalThreadIds.forEach { MediaTurnLeaseBounds.requireOpaqueId(it, "thread") }
        ensureLoaded()
        val matched = leases.values.filter {
            it.key in terminalTurns || it.key.threadId in terminalThreadIds
        }
        if (matched.isEmpty()) return emptySet()

        val unmatched = leases.values.filterNot {
            it.key in terminalTurns || it.key.threadId in terminalThreadIds
        }
        val protectedImports = unmatched.flatMapTo(mutableSetOf(), MediaTurnLease::importIds)
        val deletionResults = linkedMapOf<String, Boolean>()
        matched.asSequence()
            .flatMap { it.importIds.asSequence() }
            .filterNot { it in protectedImports }
            .distinct()
            .forEach { importId ->
                deletionResults[importId] = runCatching {
                    deleteAndConfirmAbsent(importId)
                }.getOrDefault(false)
            }

        val releasable = matched.filter { lease ->
            lease.importIds.all { importId ->
                importId in protectedImports || deletionResults[importId] == true
            }
        }
        if (releasable.isEmpty()) return emptySet()
        val releasedKeys = releasable.mapTo(mutableSetOf(), MediaTurnLease::key)
        val next = LinkedHashMap(leases).apply { releasedKeys.forEach { remove(it) } }
        if (runCatching { storage.write(next.values.toList()) }.isFailure) return emptySet()
        leases = next
        return releasedKeys
    }

    @Synchronized
    internal fun snapshotForTest(): List<MediaTurnLease> {
        ensureLoaded()
        return leases.values.toList()
    }

    private fun ensureLoaded() {
        if (initialized) return
        initialized = true
        val restored = runCatching { storage.read() }.getOrElse { emptyList() }
        leases = restored.associateByTo(linkedMapOf(), MediaTurnLease::key)
    }
}

private object MediaTurnLeaseBounds {
    const val MAX_LEASES = 256
    const val MAX_IMPORTS_PER_TURN = 64
    const val MAX_DOCUMENT_BYTES = 128 * 1_024
    private const val MAX_OPAQUE_ID_CHARS = 256
    private val safeImportId = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

    fun requireOpaqueId(value: String, label: String): String = value.also {
        require(it.isNotBlank() && it.length <= MAX_OPAQUE_ID_CHARS && it.none(Char::isISOControl)) {
            "Invalid media $label id"
        }
    }

    fun requireImportId(value: String): String = value.also {
        require(safeImportId.matches(it)) { "Invalid media import id" }
    }
}
