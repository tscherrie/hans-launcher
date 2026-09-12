package ai.hans.standard.integration

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import org.json.JSONArray
import org.json.JSONObject

class AppPrivateCodexSessionStore internal constructor(
    private val preferences: SharedPreferences,
    override val workspacePath: String,
) : CodexSessionStore, VisibleInputReceiptStore {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        workspacePath(context.applicationContext),
    )

    @Synchronized
    override fun readThreadId(): String? = withDurablePreferences {
        preferences.getString(KEY_THREAD_ID, null)?.takeIf(::isValidThreadId)
    }

    @Synchronized
    override fun saveThreadId(threadId: String) {
        require(isValidThreadId(threadId)) { "Invalid thread id" }
        withDurablePreferences {
            commit(preferences.edit().putString(KEY_THREAD_ID, threadId))
        }
    }

    @Synchronized
    override fun clearThreadId(expectedThreadId: String?) {
        withDurablePreferences {
            val current = preferences.getString(KEY_THREAD_ID, null)
            if (expectedThreadId == null || current == expectedThreadId) {
                val retainedReceipts = readReceipts().filterNot {
                    expectedThreadId == null || it.threadId == current
                }
                commit(
                    preferences.edit()
                        .remove(KEY_THREAD_ID)
                        .putString(KEY_VISIBLE_INPUT_RECEIPTS, encodeReceipts(retainedReceipts)),
                )
            }
        }
    }

    @Synchronized
    override fun read(threadId: String, clientId: String): VisibleInputReceipt? =
        withDurablePreferences {
            readReceipts().lastOrNull { it.threadId == threadId && it.clientId == clientId }
        }

    @Synchronized
    override fun record(receipt: VisibleInputReceipt) {
        withDurablePreferences {
            val updated = readReceipts().filterNot {
                it.threadId == receipt.threadId && it.clientId == receipt.clientId
            }.toMutableList()
            updated += receipt
            while (updated.size > MAX_VISIBLE_INPUT_RECEIPTS) updated.removeAt(0)
            commit(
                preferences.edit().putString(
                    KEY_VISIBLE_INPUT_RECEIPTS,
                    encodeReceipts(updated),
                ),
            )
        }
    }

    @Synchronized
    override fun remove(threadId: String, clientId: String) {
        withDurablePreferences {
            val current = readReceipts()
            val updated = current.filterNot {
                it.threadId == threadId && it.clientId == clientId
            }
            if (updated.size != current.size) {
                commit(
                    preferences.edit().putString(
                        KEY_VISIBLE_INPUT_RECEIPTS,
                        encodeReceipts(updated),
                    ),
                )
            }
        }
    }

    @Synchronized
    override fun clear(threadId: String?) {
        withDurablePreferences {
            val updated = if (threadId == null) {
                emptyList()
            } else {
                readReceipts().filterNot { it.threadId == threadId }
            }
            commit(
                preferences.edit().putString(
                    KEY_VISIBLE_INPUT_RECEIPTS,
                    encodeReceipts(updated),
                ),
            )
        }
    }

    private fun readReceipts(): List<VisibleInputReceipt> {
        val raw = preferences.getString(KEY_VISIBLE_INPUT_RECEIPTS, null) ?: return emptyList()
        return runCatching {
            val root = JSONObject(raw)
            if (root.optInt("version") != VISIBLE_INPUT_RECEIPT_VERSION) return@runCatching emptyList()
            val records = root.getJSONArray("records")
            buildList(minOf(records.length(), MAX_VISIBLE_INPUT_RECEIPTS)) {
                val start = (records.length() - MAX_VISIBLE_INPUT_RECEIPTS).coerceAtLeast(0)
                for (index in start until records.length()) {
                    val record = records.getJSONObject(index)
                    val digests = record.getJSONArray("visibleTextDigests")
                    if (digests.length() > ai.hans.standard.codex.ProtocolLimits.MAX_RECOVERED_HISTORY_INPUT_PARTS) {
                        continue
                    }
                    val parsed = runCatching {
                        VisibleInputReceipt(
                            threadId = record.getString("threadId"),
                            clientId = record.getString("clientId"),
                            visibleTextDigests = buildList(digests.length()) {
                                repeat(digests.length()) { add(digests.getString(it)) }
                            },
                            attachmentPlaceholder = record.optBoolean("attachmentPlaceholder"),
                        )
                    }.getOrNull()
                    if (parsed != null) add(parsed)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeReceipts(receipts: List<VisibleInputReceipt>): String = JSONObject()
        .put("version", VISIBLE_INPUT_RECEIPT_VERSION)
        .put(
            "records",
            JSONArray().also { records ->
                receipts.forEach { receipt ->
                    records.put(
                        JSONObject()
                            .put("threadId", receipt.threadId)
                            .put("clientId", receipt.clientId)
                            .put("visibleTextDigests", JSONArray(receipt.visibleTextDigests))
                            .put("attachmentPlaceholder", receipt.attachmentPlaceholder),
                    )
                }
            },
        )
        .toString()

    private fun <T> withDurablePreferences(action: () -> T): T = synchronized(failedPreferences) {
        check(preferences !in failedPreferences) { "Codex thread persistence failed; restart required" }
        action()
    }

    private fun commit(editor: SharedPreferences.Editor) {
        try {
            check(editor.commit()) { "Could not persist current Codex thread" }
        } catch (error: Exception) {
            // commit(false) can leave a changed RAM map and the OLD pointer on disk. Another
            // host in this process must not finalize a contract migration from that RAM value.
            failedPreferences.add(preferences)
            throw error
        }
    }

    private fun isValidThreadId(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_THREAD_ID_CHARACTERS &&
            value.none(Char::isISOControl)

    private companion object {
        const val PREFERENCES_NAME = "hans_codex_session_v1"
        const val KEY_THREAD_ID = "thread_id"
        const val KEY_VISIBLE_INPUT_RECEIPTS = "visible_input_receipts"
        const val WORKSPACE_DIRECTORY = "codex-workspace"
        const val MAX_THREAD_ID_CHARACTERS = 256
        const val MAX_VISIBLE_INPUT_RECEIPTS = 128
        const val VISIBLE_INPUT_RECEIPT_VERSION = 1
        val failedPreferences: MutableSet<SharedPreferences> =
            Collections.newSetFromMap(WeakHashMap<SharedPreferences, Boolean>())

        fun workspacePath(context: Context): String = File(context.filesDir, WORKSPACE_DIRECTORY).also {
            check((it.isDirectory || it.mkdirs()) && it.isDirectory) {
                "Could not create app-private Codex workspace"
            }
        }.canonicalPath
    }
}
