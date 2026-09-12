package ai.hans.standard.voice

import ai.hans.standard.integration.OutboundMessageStatus
import ai.hans.standard.integration.OutboundUserMessageUi

enum class PendingDictationDelivery {
    AWAITING_USER,
    AWAITING_RECEIPT,
    OUTCOME_UNKNOWN,
}

data class PendingDictation(
    val id: String,
    val transcript: String,
    /** An interrupted transcript is a reviewable draft, never an automatically sent instruction. */
    val incomplete: Boolean = false,
    val delivery: PendingDictationDelivery = PendingDictationDelivery.AWAITING_USER,
    val clientUserMessageId: String? = null,
) {
    val canRetry: Boolean get() = delivery == PendingDictationDelivery.AWAITING_USER
    /** An in-flight request is not an editable draft; removing it would lose its receipt. */
    val canDiscard: Boolean get() = delivery != PendingDictationDelivery.AWAITING_RECEIPT
}

/** A normally submitted dictation already has a user bubble; do not duplicate it as a draft. */
internal fun List<PendingDictation>.withoutVisibleInFlightReceipts(
    outbound: List<OutboundUserMessageUi>,
): List<PendingDictation> = filterNot { pending ->
    pending.delivery == PendingDictationDelivery.AWAITING_RECEIPT &&
        pending.clientUserMessageId != null && outbound.any {
            it.clientUserMessageId == pending.clientUserMessageId &&
                it.threadId.isNotBlank() && it.status != OutboundMessageStatus.FAILED
        }
}

data class PendingDictationSnapshot(
    val drafts: List<PendingDictation> = emptyList(),
    val storageUnavailable: Boolean = false,
    val revision: Long = 0,
)

/** Capturing the contents and their revision is one serialized operation, never a stale label. */
internal class PendingDictationSnapshotSource(private val read: () -> List<PendingDictation>) {
    private var revision = 0L

    @Synchronized
    fun capture(): PendingDictationSnapshot {
        val result = runCatching { PendingDictationSnapshot(read()) }
            .getOrDefault(PendingDictationSnapshot(storageUnavailable = true))
        return result.copy(revision = ++revision)
    }
}

/** A delayed initial/event snapshot must not resurrect an acknowledged or discarded draft. */
internal class PendingDictationSnapshotObserver(private val observer: (PendingDictationSnapshot) -> Unit) {
    private var active = true
    private var lastRevision = -1L

    @Synchronized
    fun deliver(snapshot: PendingDictationSnapshot) {
        if (!active || snapshot.revision <= lastRevision) return
        lastRevision = snapshot.revision
        runCatching { observer(snapshot) }
    }

    @Synchronized
    fun close() {
        active = false
    }
}

internal enum class PendingDictationReceiptAction { KEEP, ACKNOWLEDGE, MARK_UNKNOWN }

/** Only a new process-owned session host invokes this, never an ordinary store read/reopen. */
internal fun PendingDictation.afterSessionHostRestart(): PendingDictation =
    if (delivery == PendingDictationDelivery.AWAITING_RECEIPT) {
        copy(delivery = PendingDictationDelivery.OUTCOME_UNKNOWN)
    } else {
        this
    }

/** PENDING/dispatch acceptance is not proof of delivery; uncertain failures are never replayed. */
internal fun reconcilePendingDictationReceipt(
    pending: PendingDictation,
    outbound: List<OutboundUserMessageUi>,
): PendingDictationReceiptAction {
    val receiptId = pending.clientUserMessageId ?: return PendingDictationReceiptAction.KEEP
    val exact = outbound.firstOrNull { it.clientUserMessageId == receiptId }
        ?: return PendingDictationReceiptAction.KEEP
    return when (exact.status) {
        OutboundMessageStatus.SENT -> if (
            exact.threadId.isNotBlank() && !exact.turnId.isNullOrBlank()
        ) {
            PendingDictationReceiptAction.ACKNOWLEDGE
        } else {
            PendingDictationReceiptAction.KEEP
        }
        OutboundMessageStatus.FAILED -> PendingDictationReceiptAction.MARK_UNKNOWN
        OutboundMessageStatus.PENDING -> PendingDictationReceiptAction.KEEP
    }
}
