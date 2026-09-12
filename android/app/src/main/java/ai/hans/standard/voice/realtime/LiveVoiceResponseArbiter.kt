package ai.hans.standard.voice.realtime

/**
 * Serializes every client-initiated `response.create` event.
 *
 * Realtime permits only one active response. Conversation items (including function outputs) may
 * still be appended immediately while a response is active; the matching response is requested
 * exactly once after the active response finishes.
 */
internal class LiveVoiceResponseArbiter {
    enum class Purpose {
        /** Answer a committed spoken user turn with the current session tools and instructions. */
        USER_TURN,

        /** A short spoken progress acknowledgement. Tools must be disabled for this response. */
        TASK_PROGRESS,

        /** Continue naturally after one or more task results were appended. */
        TASK_RESULT,

        /** Speak a recovered-result batch without permitting another tool call. */
        RECOVERED_RESULT,
    }

    data class CreateCommand(
        val purpose: Purpose,
        /** Correlates a server scheduling error without exposing a user/server identifier. */
        val eventId: String,
    )

    sealed interface FinishDisposition {
        data object Ignored : FinishDisposition
        data class Matched(
            val completed: CreateCommand?,
            val next: CreateCommand?,
        ) : FinishDisposition
    }

    private var responseActive = false
    private var activeResponseId: String? = null
    private var submittedCommand: CreateCommand? = null
    private var deferredPurpose: Purpose? = null
    private val completedResponseIds = LinkedHashSet<String>()
    private var nextEventSequence = 1L

    /** Returns a purpose only when the caller may send `response.create` immediately. */
    fun request(purpose: Purpose): CreateCommand? {
        deferredPurpose = merge(deferredPurpose, purpose)
        return drain()
    }

    /** Records responses created by this client and any unexpected server-created response. */
    fun responseStarted(responseId: String?) {
        responseActive = true
        activeResponseId = responseId
        // Keep submittedCommand until response.done. A correlated scheduling error can race this
        // event when another client-initiated response already owns the conversation.
    }

    /**
     * Releases a deferred request only for the active response's matching `response.done` event.
     * A missing response id is accepted because the protocol models the id as optional.
     */
    fun responseFinished(responseId: String?): FinishDisposition {
        if (responseId != null && responseId in completedResponseIds) {
            return FinishDisposition.Ignored
        }
        if (responseActive) {
            if (
                activeResponseId != null &&
                responseId != null &&
                activeResponseId != responseId
            ) {
                return FinishDisposition.Ignored
            }
            responseActive = false
            activeResponseId = null
        } else if (submittedCommand != null) {
            // Be robust to a missing response.created event: response.done is still terminal.
        } else {
            return FinishDisposition.Ignored
        }
        if (responseId != null) {
            completedResponseIds.add(responseId)
            while (completedResponseIds.size > MAX_COMPLETED_RESPONSE_IDS) {
                completedResponseIds.remove(completedResponseIds.first())
            }
        }
        val completed = submittedCommand
        submittedCommand = null
        return FinishDisposition.Matched(completed = completed, next = drain())
    }

    /**
     * The server rejected our create because another response won the race. Keep the requested
     * response deferred and wait for that response's `response.done` event.
     */
    fun responseSchedulingConflict(eventId: String?): Boolean {
        val submitted = submittedCommand ?: return false
        if (eventId != null && eventId != submitted.eventId) return false
        deferredPurpose = merge(deferredPurpose, submitted.purpose)
        submittedCommand = null
        if (!responseActive) {
            responseActive = true
            activeResponseId = null
        }
        return true
    }

    /** Requeues a request when transport submission itself failed. */
    fun createSendFailed() {
        submittedCommand?.let { deferredPurpose = merge(deferredPurpose, it.purpose) }
        submittedCommand = null
    }

    fun reset() {
        responseActive = false
        activeResponseId = null
        submittedCommand = null
        deferredPurpose = null
        completedResponseIds.clear()
        nextEventSequence = 1L
    }

    fun isIdle(): Boolean =
        !responseActive && submittedCommand == null && deferredPurpose == null

    fun isActive(purpose: Purpose): Boolean =
        responseActive && submittedCommand?.purpose == purpose

    private fun drain(): CreateCommand? {
        if (responseActive || submittedCommand != null) return null
        val next = deferredPurpose ?: return null
        deferredPurpose = null
        val command = CreateCommand(next, "hans-response-${nextEventSequence++}")
        submittedCommand = command
        return command
    }

    private fun merge(current: Purpose?, offered: Purpose): Purpose = when {
        current == Purpose.USER_TURN || offered == Purpose.USER_TURN -> Purpose.USER_TURN
        current == Purpose.RECOVERED_RESULT || offered == Purpose.RECOVERED_RESULT ->
            Purpose.RECOVERED_RESULT
        current == Purpose.TASK_RESULT || offered == Purpose.TASK_RESULT -> Purpose.TASK_RESULT
        else -> Purpose.TASK_PROGRESS
    }

    private companion object {
        const val MAX_COMPLETED_RESPONSE_IDS = 64
    }
}
