package ai.hans.standard.diagnostics

import ai.hans.standard.codex.AccountPhase
import ai.hans.standard.codex.ThreadTokenUsageSnapshot
import ai.hans.standard.codex.TokenUsageBreakdown
import ai.hans.standard.integration.CodexClientSnapshot

/** No personal content or stable identifiers; consumed only by the explicit Activity dump path. */
internal object PerformanceDiagnostics {
    fun context(snapshot: CodexClientSnapshot): Pair<Long?, String?> =
        if (snapshot.session.account.phase == AccountPhase.SIGNED_IN) {
            snapshot.generation to snapshot.session.currentThreadId
        } else null to null

    fun encode(snapshot: CodexClientSnapshot?, toolMeasurements: Map<String, Any>?): String {
        val usage = snapshot?.takeIf { context(it).first != null && context(it).second != null }
            ?.let { state -> state.session.threads.singleOrNull { it.threadId == state.session.currentThreadId }?.tokenUsage }
        return MigrationCanonicalJson.encode(linkedMapOf(
            "schema" to "hans-performance-v1",
            "retention" to "process_memory_only",
            "tokenUsage" to usage?.payload(),
            "tokenUsageMeaning" to "server_reported_last_model_request_and_cumulative_thread_not_live_context_occupancy",
            "tools" to toolMeasurements,
        ))
    }

    private fun ThreadTokenUsageSnapshot.payload(): Map<String, Any?> = linkedMapOf(
        "last" to last.payload(),
        "total" to total.payload(),
        "modelContextWindow" to modelContextWindow,
    )

    private fun TokenUsageBreakdown.payload(): Map<String, Any?> = linkedMapOf(
        "inputTokens" to inputTokens,
        "cachedInputTokens" to cachedInputTokens,
        "cacheWriteInputTokens" to cacheWriteInputTokens,
        "outputTokens" to outputTokens,
        "reasoningOutputTokens" to reasoningOutputTokens,
        "totalTokens" to totalTokens,
    )
}
