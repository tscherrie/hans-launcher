package ai.hans.standard.phone.accessibility.resume

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

internal data class UiTaskContinuationMutation<T>(
    val document: UiTaskContinuationDocument,
    val result: T,
)

internal interface UiTaskContinuationStorage {
    fun read(): UiTaskContinuationDocument

    fun <T> mutate(
        transform: (UiTaskContinuationDocument) -> UiTaskContinuationMutation<T>,
    ): T
}

/** Atomic, device-encrypted, no-backup storage. It contains identifiers and hashes, never args. */
internal class AtomicFileUiTaskContinuationStorage(
    context: Context,
    file: File = File(context.applicationContext.noBackupFilesDir, FILE_NAME),
) : UiTaskContinuationStorage {
    private val atomicFile = AtomicFile(file)

    override fun read(): UiTaskContinuationDocument = synchronized(PROCESS_LOCK) {
        readUnlocked()
    }

    override fun <T> mutate(
        transform: (UiTaskContinuationDocument) -> UiTaskContinuationMutation<T>,
    ): T = synchronized(PROCESS_LOCK) {
        val before = readUnlocked()
        val mutation = transform(before)
        if (mutation.document != before) writeUnlocked(mutation.document)
        check(readUnlocked() == mutation.document) { "ui_continuation_storage_verification" }
        mutation.result
    }

    private fun readUnlocked(): UiTaskContinuationDocument {
        val base = atomicFile.baseFile
        if (!base.exists() && !File("${base.path}.bak").exists() && !File("${base.path}.new").exists()) {
            return UiTaskContinuationDocument()
        }
        val bytes = atomicFile.readFully()
        require(bytes.isNotEmpty() && bytes.size <= UiTaskContinuationBounds.MAX_DOCUMENT_BYTES) {
            "ui_continuation_storage_size"
        }
        return UiTaskContinuationCodec.decode(bytes.toString(StandardCharsets.UTF_8))
    }

    private fun writeUnlocked(document: UiTaskContinuationDocument) {
        val bytes = UiTaskContinuationCodec.encode(document).toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= UiTaskContinuationBounds.MAX_DOCUMENT_BYTES) {
            "ui_continuation_storage_size"
        }
        val output = atomicFile.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (failure: Exception) {
            atomicFile.failWrite(output)
            throw failure
        }
    }

    companion object {
        internal const val FILE_NAME = "hans-ui-task-continuations-v1.json"
        private val PROCESS_LOCK = Any()
    }
}

internal object UiTaskContinuationCodec {
    private const val SCHEMA = "hans.ui-task-continuations"
    private const val VERSION = 1

    fun encode(document: UiTaskContinuationDocument): String = JSONObject()
        .put("schema", SCHEMA)
        .put("version", VERSION)
        .put(
            "records",
            JSONArray().also { values ->
                document.records.forEach { record ->
                    values.put(
                        JSONObject()
                            .put("threadId", record.identity.threadId)
                            .put("turnId", record.identity.turnId)
                            .put("callId", record.identity.callId)
                            .put("tool", record.identity.tool)
                            .put("argumentFingerprint", record.identity.argumentFingerprint)
                            .put("blockedReason", record.blockedReason.name)
                            .put("status", record.status.name)
                            .put("createdAtEpochMillis", record.createdAtEpochMillis)
                            .put("updatedAtEpochMillis", record.updatedAtEpochMillis)
                            .put("claimToken", record.claimToken ?: JSONObject.NULL),
                    )
                }
            },
        )
        .toString()

    fun decode(raw: String): UiTaskContinuationDocument {
        val bytes = raw.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= UiTaskContinuationBounds.MAX_DOCUMENT_BYTES) {
            "ui_continuation_storage_size"
        }
        val root = JSONObject(raw)
        requireExactKeys(root, setOf("schema", "version", "records"))
        require(root.getString("schema") == SCHEMA) { "ui_continuation_storage_schema" }
        require(root.getInt("version") == VERSION) { "ui_continuation_storage_version" }
        val values = root.getJSONArray("records")
        require(values.length() <= UiTaskContinuationBounds.MAX_RECORDS) {
            "ui_continuation_storage_records"
        }
        val records = buildList(values.length()) {
            repeat(values.length()) { index ->
                val value = values.getJSONObject(index)
                requireExactKeys(
                    value,
                    setOf(
                        "threadId",
                        "turnId",
                        "callId",
                        "tool",
                        "argumentFingerprint",
                        "blockedReason",
                        "status",
                        "createdAtEpochMillis",
                        "updatedAtEpochMillis",
                        "claimToken",
                    ),
                )
                add(
                    UiTaskContinuationRecord(
                        identity = UiTaskContinuationIdentity(
                            threadId = value.getString("threadId"),
                            turnId = value.getString("turnId"),
                            callId = value.getString("callId"),
                            tool = value.getString("tool"),
                            argumentFingerprint = value.getString("argumentFingerprint"),
                        ),
                        blockedReason = enumValue<UiTaskContinuationBlockedReason>(
                            value.getString("blockedReason"),
                        ),
                        status = enumValue<UiTaskContinuationStatus>(value.getString("status")),
                        createdAtEpochMillis = value.getLong("createdAtEpochMillis"),
                        updatedAtEpochMillis = value.getLong("updatedAtEpochMillis"),
                        claimToken = value.optionalString("claimToken"),
                    ),
                )
            }
        }
        return UiTaskContinuationDocument(records)
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T =
        enumValues<T>().singleOrNull { it.name == value }
            ?: throw IllegalArgumentException("Invalid UI continuation enum")

    private fun JSONObject.optionalString(name: String): String? =
        if (isNull(name)) null else getString(name)

    private fun requireExactKeys(value: JSONObject, expected: Set<String>) {
        require(value.keys().asSequence().toSet() == expected) {
            "Unexpected UI continuation fields"
        }
    }
}
