package ai.hans.standard.integration

import ai.hans.standard.codex.AccountPhase

/** User-confirmed recovery only. A bootstrap error never invokes this boundary automatically. */
object ExplicitConversationRecoveryPolicy {
    fun canOffer(client: CodexClientSnapshot?): Boolean = client != null &&
        client.runtimePhase == ClientRuntimePhase.READY &&
        client.sessionPhase == ClientSessionPhase.FAILED &&
        client.session.account.phase == AccountPhase.SIGNED_IN &&
        client.problem?.code == ClientProblemCode.THREAD_RECOVERY &&
        !client.migrationReadiness.activeTurn && client.pendingDynamicToolCalls == 0 &&
        !client.remotePhoneToolsActive && client.deviceCodeLogin == null

    /** The preservation commit must succeed before the ordinary runtime restart can begin. */
    fun startConfirmed(
        client: CodexClientSnapshot?,
        realtimeState: CodexRealtimeState,
        selectedThreadId: String?,
        preserveSelected: (String) -> Boolean,
        restart: () -> Unit,
    ): Boolean {
        if (!canOffer(client) || realtimeState !in setOf(CodexRealtimeState.IDLE, CodexRealtimeState.CLOSED) ||
            selectedThreadId.isNullOrBlank()) return false
        return try {
            if (!preserveSelected(selectedThreadId)) false else {
                restart()
                true
            }
        } catch (_: Exception) {
            // No fallback clearing, login, permission mutation or lost-reference eviction.
            false
        }
    }
}
