package ai.hans.standard.integration

import android.content.Context
import android.content.SharedPreferences
import ai.hans.standard.workspace.HansDesktopProject
import java.util.Collections
import java.util.WeakHashMap
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

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

    /** Explicit user recovery only: retain the old reference without changing any input receipts. */
    @Synchronized
    fun preserveSelectedThreadForExplicitRecovery(expectedThreadId: String): Boolean =
        withDurablePreferences {
            if (!isValidThreadId(expectedThreadId) ||
                preferences.getString(KEY_THREAD_ID, null) != expectedThreadId
            ) return@withDurablePreferences false

            val preserved = readExplicitRecoveryThreadIds() ?: return@withDurablePreferences false
            val alreadyPreserved = expectedThreadId in preserved
            if (!alreadyPreserved && preserved.size >= MAX_EXPLICIT_RECOVERY_THREADS) {
                return@withDurablePreferences false
            }
            val editor = preferences.edit().remove(KEY_THREAD_ID)
            if (!alreadyPreserved) {
                editor.putString(
                    KEY_EXPLICIT_RECOVERY_THREAD_IDS,
                    JSONArray(preserved + expectedThreadId).toString(),
                )
            }
            commit(editor)
            true
        }

    /** Parse only a bounded string array; never discard a malformed preservation journal. */
    private fun readExplicitRecoveryThreadIds(): List<String>? = try {
        val raw = preferences.getString(KEY_EXPLICIT_RECOVERY_THREAD_IDS, null)
        if (raw == null) {
            if (preferences.contains(KEY_EXPLICIT_RECOVERY_THREAD_IDS)) null else emptyList()
        } else if (raw.length > MAX_EXPLICIT_RECOVERY_JSON_CHARACTERS) {
            null
        } else {
            // Parse strings directly instead of a recursive JSONArray: corrupted nesting must
            // not overflow the stack before the reference-count/type checks can reject it.
            require(raw.none { it.isISOControl() && it !in "\t\n\r" })
            val tokens = JSONTokener(raw)
            require(tokens.nextClean() == '[')
            val result = mutableListOf<String>()
            if (tokens.nextClean() != ']') {
                tokens.back()
                while (true) {
                    require(result.size < MAX_EXPLICIT_RECOVERY_THREADS)
                    require(tokens.nextClean() == '"')
                    val id = tokens.nextString('"')
                    require(isValidThreadId(id) && id !in result)
                    result += id
                    when (tokens.nextClean()) {
                        ']' -> break
                        ',' -> Unit
                        else -> error("Invalid preserved thread reference array")
                    }
                }
            }
            require(tokens.nextClean() == '\u0000')
            require(!tokens.more())
            result
        }
    } catch (_: Exception) {
        null
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
        const val KEY_EXPLICIT_RECOVERY_THREAD_IDS = "explicit_recovery_thread_ids"
        const val MAX_THREAD_ID_CHARACTERS = 256
        const val MAX_EXPLICIT_RECOVERY_THREADS = 64
        // Includes worst-case JSON escaping of every character in all 64 thread references.
        const val MAX_EXPLICIT_RECOVERY_JSON_CHARACTERS =
            MAX_EXPLICIT_RECOVERY_THREADS * (MAX_THREAD_ID_CHARACTERS * 6 + 3) + 2
        const val MAX_VISIBLE_INPUT_RECEIPTS = 128
        const val VISIBLE_INPUT_RECEIPT_VERSION = 1
        val failedPreferences: MutableSet<SharedPreferences> =
            Collections.newSetFromMap(WeakHashMap<SharedPreferences, Boolean>())

        fun workspacePath(context: Context): String =
            HansDesktopProject.ensure(context.filesDir).canonicalPath
    }
}
