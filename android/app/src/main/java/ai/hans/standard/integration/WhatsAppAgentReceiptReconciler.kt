package ai.hans.standard.integration

import ai.hans.standard.codex.ProtocolLimits
import ai.hans.standard.codex.RecoveredConversationItem
import ai.hans.standard.codex.RecoveredHistoryStatus
import ai.hans.standard.codex.ThreadResumeResult
import ai.hans.standard.codex.TurnStatus
import ai.hans.standard.notifications.agentchannel.WhatsAppAgentChannel

/** Content-free positive proof, extracted only from an explicitly LOADED persisted history page. */
internal data class AgentChannelRecoveredHistory(
    val threadId: String,
    val messageTurns: Map<String, String>,
    val turnStatuses: Map<String, TurnStatus>,
)

internal fun agentChannelRecoveredHistoryFromResume(result: ThreadResumeResult): AgentChannelRecoveredHistory? {
    if (result.recoveredHistoryStatus != RecoveredHistoryStatus.LOADED ||
        result.recoveredItems.size > ProtocolLimits.MAX_RECOVERED_HISTORY_ITEMS ||
        result.initialTurnReceipts.size > ProtocolLimits.RECENT_HISTORY_TURN_LIMIT) return null
    val statuses = linkedMapOf<String, TurnStatus>()
    result.initialTurnReceipts.forEach { receipt ->
        if (statuses.put(receipt.turnId, receipt.status) != null) return null
    }
    val messages = linkedMapOf<String, String>()
    result.recoveredItems.filterIsInstance<RecoveredConversationItem.User>().forEach { item ->
        val clientId = item.clientId ?: return@forEach
        if (!clientId.startsWith("whatsapp-agent-")) return@forEach
        if (item.turnId !in statuses) return null
        val previous = messages.put(clientId, item.turnId)
        if (previous != null && previous != item.turnId) return null
    }
    return AgentChannelRecoveredHistory(result.thread.id, messages.toMap(), statuses.toMap())
}

/** Never submits/replays work. Absence from a bounded history page is not terminal proof. */
internal class WhatsAppAgentReceiptReconciler(private val channel: WhatsAppAgentChannel) {
    fun reconcile(
        outbound: List<OutboundUserMessageUi>,
        terminalTurns: List<ClientTerminalTurn>,
        recovered: AgentChannelRecoveredHistory?,
    ) {
        channel.unsettledReceipts().forEach { receipt ->
            val correlation = receipt.dispatchCorrelation ?: return@forEach
            val live = outbound.filter { it.clientUserMessageId == correlation.clientUserMessageId &&
                it.threadId == correlation.threadId }
            // Conflicting receipts cannot rewrite the immutable identity chosen before transport.
            val liveTurnIds = live.mapNotNull { it.turnId }.distinct()
            if (liveTurnIds.size > 1) {
                channel.markUncertain(receipt.id)
                return@forEach
            }
            val history = recovered?.takeIf { it.threadId == correlation.threadId }
            val recoveredTurnId = history?.messageTurns?.get(correlation.clientUserMessageId)
            val liveTurnId = liveTurnIds.singleOrNull()
            if (liveTurnId != null && recoveredTurnId != null && liveTurnId != recoveredTurnId) {
                channel.markUncertain(receipt.id)
                return@forEach
            }
            val observedTurnId = liveTurnId ?: recoveredTurnId
            if (observedTurnId != null && !channel.recordAcceptedTurn(receipt.id,
                    correlation.threadId, correlation.clientUserMessageId, observedTurnId)) return@forEach
            val turnId = correlation.turnId ?: observedTurnId ?: run {
                if (live.any { it.status == OutboundMessageStatus.FAILED }) channel.markUncertain(receipt.id)
                return@forEach
            }
            val liveStatuses = terminalTurns.filter { it.threadId == correlation.threadId && it.turnId == turnId }
                .map { it.status }.distinct()
            val recoveredStatus = history?.turnStatuses?.get(turnId)?.takeIf { it != TurnStatus.IN_PROGRESS }
            if (liveStatuses.size > 1 || (liveStatuses.singleOrNull() != null && recoveredStatus != null &&
                    liveStatuses.single() != recoveredStatus)) {
                channel.markUncertain(receipt.id)
                return@forEach
            }
            val terminal = liveStatuses.singleOrNull() ?: recoveredStatus ?: return@forEach
            channel.complete(receipt.id, terminal == TurnStatus.COMPLETED)
        }
    }
}
