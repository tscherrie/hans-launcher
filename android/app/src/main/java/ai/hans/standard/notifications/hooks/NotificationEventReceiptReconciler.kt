package ai.hans.standard.notifications.hooks

import ai.hans.standard.codex.TurnStatus
import ai.hans.standard.integration.NativeNotificationExternalHistory
import ai.hans.standard.integration.NativeNotificationExternalMessage

internal data class NotificationEventTerminalProof(val threadId: String, val turnId: String, val status: TurnStatus)

/** Positive proof only. Absence from a bounded history page can NEVER authorize a replay. */
internal class NotificationEventReceiptReconciler(private val ledger: NotificationEventLedger) {
    fun reconcile(history: NativeNotificationExternalHistory?, terminals: List<NotificationEventTerminalProof>) {
        ledger.unsettled().forEach { record ->
            val correlation = record.correlation ?: return@forEach
            val matches = history?.takeIf { it.threadId == correlation.threadId }?.receipts
                ?.filter { it.eventId == record.eventId }.orEmpty()
            val exact = matches.filter { it.payloadSha256 == record.payloadSha256 && it.threadId == correlation.threadId &&
                it.toolName == NativeNotificationExternalMessage.TOOL_NAME && it.toolNamespace == NativeNotificationExternalMessage.TOOL_NAMESPACE }
            if (matches.size != exact.size || exact.map { it.turnId }.distinct().size > 1) {
                ledger.reportFailure("recovery_correlation_conflict"); return@forEach
            }
            val recoveredTurn = exact.firstOrNull()?.turnId
            if (recoveredTurn != null && !ledger.recoverAccepted(record.eventId, record.payloadSha256,
                    correlation.threadId, recoveredTurn)) return@forEach
            val turnId = correlation.turnId ?: recoveredTurn ?: return@forEach
            val liveStatuses = terminals.filter { it.threadId == correlation.threadId && it.turnId == turnId }
                .map { it.status }.distinct()
            val historical = history?.takeIf { it.threadId == correlation.threadId }?.turnStatuses?.get(turnId)
                ?.takeIf { it != TurnStatus.IN_PROGRESS }
            if (liveStatuses.size > 1 || liveStatuses.singleOrNull()?.let { historical != null && it != historical } == true) {
                ledger.reportFailure("terminal_correlation_conflict"); return@forEach
            }
            val terminal = liveStatuses.singleOrNull() ?: historical ?: return@forEach
            ledger.settle(record.eventId, correlation.threadId, turnId, terminal == TurnStatus.COMPLETED)
        }
    }
}
