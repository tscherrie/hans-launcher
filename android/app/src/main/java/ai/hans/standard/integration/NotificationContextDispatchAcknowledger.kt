package ai.hans.standard.integration

/**
 * Correlates a bounded batch of validated local notification summaries with the exact outbound
 * user message that carried it. Merely allocating/dispatching a local message id is insufficient:
 * the batch is acknowledged only after App Server returns the correlated SENT receipt.
 */
internal class NotificationContextDispatchAcknowledger(
    private val markInjected: (String, Set<String>) -> Boolean,
) {
    private val pending = linkedMapOf<String, Set<String>>()
    private val failClosedOverflowReservations = linkedSetOf<String>()

    @Synchronized
    fun register(clientUserMessageId: String, announcementIds: Set<String>) {
        if (clientUserMessageId.isBlank() || announcementIds.isEmpty()) return
        if (clientUserMessageId !in pending && pending.size >= MAX_PENDING_DISPATCHES) {
            // The outbound turn has already been accepted, so forgetting these IDs could inject
            // the same private context into a later turn. Keep a process-lifetime reservation
            // instead of silently evicting an older correlation.
            failClosedOverflowReservations += announcementIds
            return
        }
        pending[clientUserMessageId] = announcementIds.toSet()
    }

    /** Rehydrates process-local correlation from the durable pre-dispatch reservations. */
    @Synchronized
    fun restore(reservations: Map<String, Set<String>>) {
        pending.clear()
        failClosedOverflowReservations.clear()
        reservations.entries
            .asSequence()
            .filter { (messageId, ids) -> messageId.isNotBlank() && ids.isNotEmpty() }
            .take(MAX_PENDING_DISPATCHES)
            .forEach { (messageId, ids) -> pending[messageId] = ids.toSet() }
        reservations.entries.drop(MAX_PENDING_DISPATCHES).forEach { (_, ids) ->
            failClosedOverflowReservations += ids
        }
    }

    fun observe(outbound: List<OutboundUserMessageUi>) {
        val sent = mutableListOf<Pair<String, Set<String>>>()
        synchronized(this) {
            outbound.forEach { item ->
                val ids = pending[item.clientUserMessageId] ?: return@forEach
                when (item.status) {
                    OutboundMessageStatus.PENDING -> Unit
                    OutboundMessageStatus.SENT -> if (!item.turnId.isNullOrBlank()) {
                        sent += item.clientUserMessageId to ids
                    }
                    // Transport failure is ambiguous: the App Server may have received the frame
                    // before Binder/runtime reported failure. Releasing here could duplicate
                    // private context after reconnect, so FAILED remains durably reserved.
                    OutboundMessageStatus.FAILED -> Unit
                }
            }
        }
        sent.forEach { (messageId, ids) ->
            if (markInjected(messageId, ids)) {
                synchronized(this) {
                    if (pending[messageId] == ids) pending.remove(messageId)
                }
            }
        }
    }

    @Synchronized
    fun clearProcessCorrelations() {
        pending.clear()
        failClosedOverflowReservations.clear()
    }

    @Synchronized
    fun forgetAfterDefiniteRelease(clientUserMessageId: String, announcementIds: Set<String>) {
        if (pending[clientUserMessageId] == announcementIds) {
            pending.remove(clientUserMessageId)
        }
    }

    @Synchronized
    fun reservedAnnouncementIds(): Set<String> =
        pending.values.flatten().toSet() + failClosedOverflowReservations

    @Synchronized
    fun pendingIdsForTest(): Set<String> = reservedAnnouncementIds()

    private companion object {
        // The Center itself contains at most 100 records, so retaining every exact message
        // correlation is bounded even when each message carries only one announcement.
        const val MAX_PENDING_DISPATCHES = 100
    }
}
