package ai.hans.standard.diagnostics

import ai.hans.standard.codex.SessionUiSnapshot
import ai.hans.standard.codex.ToolStatus
import ai.hans.standard.codex.ToolUiSnapshot

/** Passive, bounded, content-free view. Summary-only resumed history is not diagnostic evidence. */
internal object ToolHistoryDiagnostics {
    internal const val MAX_ENTRIES = 24
    private val safeToolNames = setOf(
        "inspect_ui", "find_ui", "inspect_visual_ui", "click_ui", "set_text_ui",
        "scroll_ui", "swipe_ui", "press_key", "run_steps", "launch_app",
        "list_replyable_notifications", "reply_to_notification",
    )

    fun encode(snapshot: SessionUiSnapshot?): String {
        val current = snapshot?.threads?.singleOrNull { it.threadId == snapshot.currentThreadId }
        return encodeTools(current?.tools.orEmpty(), current != null)
    }

    internal fun encodeTools(tools: List<ToolUiSnapshot>, snapshotAvailable: Boolean): String {
        val failures = tools.filter {
            it.complete && it.type == "dynamicToolCall" &&
                (it.status == ToolStatus.FAILED || it.failureDiagnostic != null)
        }
        return MigrationCanonicalJson.encode(linkedMapOf(
            "schema" to "hans-tool-failures-v1",
            "snapshotAvailable" to snapshotAvailable,
            "historyMeaning" to "loaded_current_thread_events_only_not_complete_persisted_history",
            "failedToolCount" to failures.size,
            "omitted" to (failures.size - MAX_ENTRIES).coerceAtLeast(0),
            "failures" to failures.takeLast(MAX_ENTRIES).map {
                val failure = it.failureDiagnostic ?: ToolFailureDiagnostic(ToolFailureCode.UNKNOWN)
                linkedMapOf(
                    "tool" to it.label.takeIf(safeToolNames::contains).orEmpty().ifEmpty { "unknown" },
                    "code" to failure.code.wire,
                    "detail" to failure.detail?.wire,
                )
            },
        ))
    }
}
