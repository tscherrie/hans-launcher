package ai.hans.standard.voice

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class PendingDictationStorageException : IllegalStateException("pending_dictation_storage_unavailable")

/** Durable drafts and exact delivery receipts. Network recovery never drains this store. */
class PendingDictationStore(context: Context) {
    private val file = AtomicFile(
        File(context.applicationContext.noBackupFilesDir, FILE_NAME),
    )

    @Synchronized
    fun enqueue(transcript: String, incomplete: Boolean = false): PendingDictation {
        val normalized = transcript.trim()
        require(normalized.isNotEmpty())
        require(normalized.length <= MAX_TRANSCRIPT_CHARACTERS)
        val current = readAll()
        require(current.size < MAX_PENDING_DICTATIONS) { "pending_dictation_queue_full" }
        require(current.sumOf { it.transcript.length } + normalized.length <= MAX_TOTAL_CHARACTERS) {
            "pending_dictation_storage_limit"
        }
        val pending = PendingDictation(UUID.randomUUID().toString(), normalized, incomplete)
        writeAll(current + pending)
        return pending
    }

    @Synchronized
    fun peek(): PendingDictation? = readAll().firstOrNull()

    @Synchronized
    fun remove(id: String): Boolean {
        val current = readAll()
        val updated = current.filterNot { it.id == id }
        if (updated.size == current.size) return false
        writeAll(updated)
        return true
    }

    @Synchronized
    fun count(): Int = readAll().size

    @Synchronized
    fun snapshot(): List<PendingDictation> = readAll()

    @Synchronized
    fun update(value: PendingDictation): Boolean {
        val current = readAll()
        if (current.none { it.id == value.id }) return false
        writeAll(current.map { if (it.id == value.id) value else it })
        return true
    }

    /** New session-host ownership cannot infer the outcome of a previous process's request. */
    @Synchronized
    fun markUnconfirmedAfterSessionHostRestart() {
        val current = readAll()
        val updated = current.map { it.afterSessionHostRestart() }
        if (updated != current) writeAll(updated)
    }

    /** Removes the local retry queue without touching Codex conversation data. */
    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun readAll(): List<PendingDictation> {
        val base = file.baseFile
        if (!base.exists() && !File("${base.path}.bak").exists() && !File("${base.path}.new").exists()) {
            return emptyList()
        }
        val bytes = runCatching { file.readFully() }.getOrElse { throw PendingDictationStorageException() }
        if (bytes.isEmpty() || bytes.size > MAX_FILE_BYTES) throw PendingDictationStorageException()
        return runCatching {
            val array = JSONArray(bytes.toString(Charsets.UTF_8))
            require(array.length() <= MAX_PENDING_DICTATIONS)
            buildList {
                var total = 0
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val id = item.getString("id")
                    val transcript = item.getString("transcript")
                    require(ID_PATTERN.matches(id))
                    require(transcript.isNotBlank())
                    require(transcript.length <= MAX_TRANSCRIPT_CHARACTERS)
                    total += transcript.length
                    require(total <= MAX_TOTAL_CHARACTERS)
                    val receiptId = item.optString("clientUserMessageId").takeIf(String::isNotBlank)
                    require(receiptId == null || RECEIPT_ID_PATTERN.matches(receiptId))
                    val delivery = if (item.has("delivery")) {
                        PendingDictationDelivery.valueOf(item.getString("delivery"))
                    } else {
                        // An older queue is retained for review, never replayed after an upgrade.
                        PendingDictationDelivery.AWAITING_USER
                    }
                    require(delivery == PendingDictationDelivery.AWAITING_USER || receiptId != null)
                    add(
                        PendingDictation(
                            id, transcript, item.optBoolean("incomplete", false), delivery, receiptId,
                        ),
                    )
                }
            }
        }.getOrElse { throw PendingDictationStorageException() }
    }

    private fun writeAll(values: List<PendingDictation>) {
        val array = JSONArray()
        values.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("transcript", item.transcript)
                    .put("incomplete", item.incomplete)
                    .put("delivery", item.delivery.name)
                    .put("clientUserMessageId", item.clientUserMessageId ?: ""),
            )
        }
        val bytes = array.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES)
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

    companion object {
        private const val FILE_NAME = "pending-dictations-v1.json"
        private const val MAX_PENDING_DICTATIONS = 16
        private const val MAX_TRANSCRIPT_CHARACTERS = 200_000
        private const val MAX_TOTAL_CHARACTERS = 1_000_000
        private const val MAX_FILE_BYTES = 4 * 1_024 * 1_024
        private val ID_PATTERN = Regex("[0-9a-f-]{36}")
        private val RECEIPT_ID_PATTERN = Regex("dictation-[0-9a-f-]{36}")
    }
}
