package ai.hans.standard.diagnostics

import org.json.JSONObject
import java.util.Locale

/** Fixed diagnostic values only; never expose rollout paths, task IDs or tool payloads. */
internal enum class NativeToolHistoryStatus {
    AVAILABLE, PARTIAL, RUNTIME_UNAVAILABLE, UNSUPPORTED_RUNTIME, UNSUPPORTED_HISTORY,
    CONFIGURATION_UNRESOLVED, MISSING, UNSAFE_PATH, BUSY, CORRUPT, IO, TIMEOUT,
}

internal data class NativeToolHistoryEntry(val tool: String, val failure: ToolFailureDiagnostic)

/** Exact official App Server payloads whose persisted history format has been inspected.
 * 0.160.1 source: openai/codex@d27764b82f7118f674371e6d6e76271d9d606edb,
 * protocol/src/{protocol,items,dynamic_tools}.rs, history/src/rollout_payload.rs,
 * rollout/src/policy.rs and state/{src/sqlite.rs,migrations/0001_threads.sql}.
 * Both use state_5.sqlite, the selected threads.rollout_path, and paginated TurnItems.
 * Version text alone is not proof; an upgrade must re-audit this contract before admission.
 */
internal object NativeToolHistoryRuntimeContract {
    const val VERSION_0_155 = "0.155.0"
    const val SHA256_0_155 = "a18a82fbfcecec13f320545f4b8e0c542247e16918fdbc61f3f8f7e4d121306d"
    const val VERSION_0_160_1 = "0.160.1"
    const val SHA256_0_160_1 = "a949b9fd5a00d4c0be2243d04073d666293c3633355b51e687365839fa528170"

    fun supports(version: String, artifactSha256: String): Boolean = when (version) {
        VERSION_0_155 -> artifactSha256 == SHA256_0_155
        VERSION_0_160_1 -> artifactSha256 == SHA256_0_160_1
        else -> false
    }
}

internal data class NativeToolHistoryResult(
    val status: NativeToolHistoryStatus,
    val entries: List<NativeToolHistoryEntry> = emptyList(),
    val failuresSeen: Int = 0,
    val skippedLines: Int = 0,
    val tailOnly: Boolean = false,
) {
    fun encode(): String = MigrationCanonicalJson.encode(linkedMapOf(
        "schema" to "hans-native-tool-failures-v1",
        "status" to status.name.lowercase(Locale.ROOT),
        "historyMeaning" to "bounded_selected_persisted_rollout_only_not_live_or_inherited_history",
        "tailOnly" to tailOnly,
        "skippedLines" to skippedLines,
        "failedToolCount" to failuresSeen,
        "omitted" to (failuresSeen - entries.size).coerceAtLeast(0),
        "failures" to entries.map { linkedMapOf(
            "tool" to NativeToolHistoryParser.safeTool(it.tool),
            "code" to it.failure.code.wire,
            "detail" to it.failure.detail?.wire,
        ) },
    ))
}

/** Source-checked 0.155.0/0.160.1 native JSONL: item_completed stores PascalCase TurnItem
 * and snake_case fields (not the App Server's camelCase notification wire shape).
 * The legacy dynamic_tool_call_response event is transient, not durable evidence.
 */
internal object NativeToolHistoryParser {
    const val MAX_ENTRIES = 24
    const val MAX_LINE_BYTES = 262_144
    const val MAX_TAIL_BYTES = 2_097_152
    const val MAX_LINES = 8_192
    private val safeTools = setOf("inspect_ui", "find_ui", "inspect_visual_ui", "click_ui",
        "set_text_ui", "scroll_ui", "swipe_ui", "press_key", "run_steps", "execute_steps",
        "launch_app", "list_replyable_notifications", "reply_to_notification")

    fun safeTool(value: String): String = value.takeIf(safeTools::contains) ?: "unknown"

    fun metadata(line: String, expectedThreadId: String): Boolean? {
        val json = parse(line) ?: return null
        if (json.opt("type") != "session_meta") return null
        val payload = json.optJSONObject("payload") ?: return null
        if (payload.opt("id") != expectedThreadId) return null
        return payload.opt("history_mode") == "paginated"
    }

    fun failure(line: String, expectedThreadId: String): NativeToolHistoryEntry? {
        val json = parse(line) ?: throw IllegalArgumentException("invalid_native_record")
        if (json.opt("type") != "event_msg") return null
        val payload = json.optJSONObject("payload") ?: return null
        if (payload.opt("type") != "item_completed" || payload.opt("thread_id") != expectedThreadId) return null
        val item = payload.optJSONObject("item") ?: return null
        if (item.opt("type") != "DynamicToolCall") return null
        val normalized = JSONObject().put("type", "dynamicToolCall")
            .put("status", item.opt("status"))
            .put("success", item.opt("success"))
            .put("contentItems", item.opt("content_items"))
        val failure = HistoricalDynamicToolFailureClassifier.classify(normalized) ?: return null
        return NativeToolHistoryEntry(safeTool(item.opt("tool") as? String ?: ""), failure)
    }

    private fun parse(line: String): JSONObject? = try {
        if (line.length > MAX_LINE_BYTES || line.toByteArray(Charsets.UTF_8).size > MAX_LINE_BYTES ||
            !StrictDiagnosticJson(line, maxDepth = 32, maxValues = 32_768).accepts()) null
        else JSONObject(line)
    } catch (_: Exception) { null }
}
