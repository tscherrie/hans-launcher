package ai.hans.standard.codex

/** Server-reported counters only; absent optional counters remain unknown, not zero. */
data class TokenUsageBreakdown(
    val inputTokens: Long,
    val cachedInputTokens: Long,
    val outputTokens: Long,
    val reasoningOutputTokens: Long,
    val totalTokens: Long,
    val cacheWriteInputTokens: Long? = null,
) {
    init {
        require(inputTokens >= 0 && cachedInputTokens >= 0 && outputTokens >= 0)
        require(reasoningOutputTokens >= 0 && totalTokens >= 0)
        require(cacheWriteInputTokens == null || cacheWriteInputTokens >= 0)
    }

    internal fun doesNotPrecede(previous: TokenUsageBreakdown): Boolean =
        inputTokens >= previous.inputTokens &&
            cachedInputTokens >= previous.cachedInputTokens &&
            outputTokens >= previous.outputTokens &&
            reasoningOutputTokens >= previous.reasoningOutputTokens &&
            totalTokens >= previous.totalTokens &&
            (cacheWriteInputTokens == null || previous.cacheWriteInputTokens == null ||
                cacheWriteInputTokens >= previous.cacheWriteInputTokens)
}

/** Last model request and cumulative thread usage are distinct authoritative snapshots. */
data class ThreadTokenUsageSnapshot(
    val last: TokenUsageBreakdown,
    val total: TokenUsageBreakdown,
    val modelContextWindow: Long? = null,
) {
    init {
        require(modelContextWindow == null || modelContextWindow > 0)
    }
}
