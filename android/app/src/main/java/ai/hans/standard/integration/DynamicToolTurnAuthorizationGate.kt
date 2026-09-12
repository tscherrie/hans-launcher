package ai.hans.standard.integration

import ai.hans.standard.codex.DynamicToolCallParams

/**
 * Fail-closed authority boundary for host-supplied context that must never authorize tools.
 *
 * The controller owns and calls this object under its monitor. It stores identifiers only; no
 * notification text, user input, tool arguments or other personal data crosses this boundary.
 */
internal class DynamicToolTurnAuthorizationGate {
    private val blockedDispatches = LinkedHashSet<String>()
    private val blockedTurns = LinkedHashSet<TurnIdentity>()
    private var generationFailClosed = false

    fun prepareDispatch(messageId: String, policy: DynamicToolTurnPolicy) {
        if (policy != DynamicToolTurnPolicy.BLOCK_UNTRUSTED_NOTIFICATION_CONTEXT) return
        if (blockedDispatches.size >= MAX_BLOCKED_DISPATCHES) {
            generationFailClosed = true
            blockedDispatches.clear()
            blockedTurns.clear()
            return
        }
        blockedDispatches += messageId
    }

    fun bindDispatch(messageId: String, threadId: String, turnId: String) {
        if (!blockedDispatches.remove(messageId)) return
        if (blockedTurns.size >= MAX_BLOCKED_TURNS) {
            generationFailClosed = true
            blockedTurns.clear()
            return
        }
        blockedTurns += TurnIdentity(threadId, turnId)
    }

    /** A recovered in-progress turn has no process-local provenance, so it cannot regain tools. */
    fun blockRecoveredTurn(threadId: String, turnId: String) {
        if (blockedTurns.size >= MAX_BLOCKED_TURNS) {
            generationFailClosed = true
            blockedTurns.clear()
            return
        }
        blockedTurns += TurnIdentity(threadId, turnId)
    }

    fun releaseRejectedDispatch(messageId: String) {
        blockedDispatches.remove(messageId)
    }

    fun completeTurn(threadId: String, turnId: String) {
        blockedTurns.remove(TurnIdentity(threadId, turnId))
    }

    fun blocks(call: DynamicToolCallParams): Boolean =
        generationFailClosed ||
            blockedDispatches.isNotEmpty() ||
            TurnIdentity(call.threadId, call.turnId) in blockedTurns

    fun resetGeneration() {
        blockedDispatches.clear()
        blockedTurns.clear()
        generationFailClosed = false
    }

    private data class TurnIdentity(val threadId: String, val turnId: String)

    private companion object {
        const val MAX_BLOCKED_DISPATCHES = 4
        const val MAX_BLOCKED_TURNS = 8
    }
}

internal enum class DynamicToolTurnPolicy {
    ALLOW,
    BLOCK_UNTRUSTED_NOTIFICATION_CONTEXT,
}
