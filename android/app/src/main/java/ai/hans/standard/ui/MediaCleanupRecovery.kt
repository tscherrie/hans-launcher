package ai.hans.standard.ui

import ai.hans.standard.codex.ThreadRuntimeStatus
import ai.hans.standard.codex.ThreadUiSnapshot
import ai.hans.standard.integration.ClientRuntimePhase
import ai.hans.standard.integration.ClientSessionPhase

/**
 * Returns only a successfully resumed current thread proven idle by App Server.
 * Recovering, active, stale and merely cached thread summaries never authorize media deletion.
 */
internal fun authoritativeIdleMediaThreadIds(
    runtimePhase: ClientRuntimePhase,
    sessionPhase: ClientSessionPhase,
    rehydrationRequired: Boolean,
    currentThreadId: String?,
    threads: List<ThreadUiSnapshot>,
): Set<String> {
    if (
        runtimePhase != ClientRuntimePhase.READY ||
        sessionPhase != ClientSessionPhase.READY ||
        rehydrationRequired
    ) {
        return emptySet()
    }
    val exactThreadId = currentThreadId ?: return emptySet()
    val currentThread = threads.firstOrNull { it.threadId == exactThreadId } ?: return emptySet()
    return if (currentThread.runtimeStatus.status == ThreadRuntimeStatus.IDLE) {
        setOf(exactThreadId)
    } else {
        emptySet()
    }
}
