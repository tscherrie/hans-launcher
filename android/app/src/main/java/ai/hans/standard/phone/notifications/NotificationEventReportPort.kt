package ai.hans.standard.phone.notifications

import ai.hans.standard.codex.DynamicToolCallParams

/**
 * Host-owned delivery boundary for one accepted notification event. The host must verify the
 * unchanged caller's native event lease, current thread/turn and fresh source/privacy authority.
 * Reporting an event never grants permission for any other action or a new agent task.
 * Awaiting intake proof is not an external effect: invoke [admitEffect] only after that wait,
 * immediately before committing the first card/store/audio effect. A false result forbids it.
 */
fun interface NotificationEventReportPort {
    fun report(
        call: DynamicToolCallParams,
        eventId: String,
        text: String,
        admitEffect: () -> Boolean,
    ): NotificationEventReportResult
}

sealed interface NotificationEventReportResult {
    /** A chat receipt and optional queue admission, not proof that audio played or was audible. */
    data class Presented(
        val reportId: String,
        val speechQueued: Boolean,
        val replay: Boolean = false,
    ) : NotificationEventReportResult

    data class Rejected(val errorCode: String) : NotificationEventReportResult
}

internal object NotificationEventReportLimits {
    const val MAX_ID_CHARACTERS = 128
    const val MAX_TEXT_CHARACTERS = 1_200
    const val MAX_TEXT_UTF8_BYTES = 4_096
}
