package ai.hans.standard.voice

import ai.hans.standard.integration.OutboundMessageStatus
import ai.hans.standard.integration.OutboundUserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingDictationDeliveryTest {
    private val submitted = PendingDictation(
        id = "draft-one",
        transcript = "Erinnere mich morgen",
        delivery = PendingDictationDelivery.AWAITING_RECEIPT,
        clientUserMessageId = "outbound-one",
    )

    @Test
    fun acceptedOrPendingTransportDoesNotDiscardTranscriptOrClaimSent() {
        assertEquals(
            PendingDictationReceiptAction.KEEP,
            reconcilePendingDictationReceipt(submitted, listOf(receipt(OutboundMessageStatus.PENDING))),
        )
        assertFalse(submitted.canRetry)
        assertFalse(submitted.canDiscard)
        assertEquals("Erinnere mich morgen", submitted.transcript)
    }

    @Test
    fun freshInFlightDictationIsOnlyTheNormalUserBubbleNotAManualDraft() {
        for (status in listOf(OutboundMessageStatus.PENDING, OutboundMessageStatus.SENT)) {
            assertTrue(listOf(submitted).withoutVisibleInFlightReceipts(listOf(receipt(status))).isEmpty())
        }
        // Projection never deletes the persisted safety copy or promotes it to SENT.
        assertEquals(PendingDictationDelivery.AWAITING_RECEIPT, submitted.delivery)
        assertFalse(submitted.canRetry)
        assertFalse(submitted.canDiscard)
    }

    @Test
    fun missingFailedOrUncorrelatedReceiptNeverHidesRecoverableSpeech() {
        val cases = listOf(
            emptyList(),
            listOf(receipt(OutboundMessageStatus.FAILED)),
            listOf(receipt(OutboundMessageStatus.PENDING).copy(clientUserMessageId = "other")),
            listOf(receipt(OutboundMessageStatus.PENDING).copy(threadId = "")),
        )
        cases.forEach { assertEquals(listOf(submitted), listOf(submitted).withoutVisibleInFlightReceipts(it)) }
        val recovery = submitted.copy(delivery = PendingDictationDelivery.OUTCOME_UNKNOWN)
        assertEquals(listOf(recovery), listOf(recovery).withoutVisibleInFlightReceipts(listOf(receipt(OutboundMessageStatus.SENT))))
        assertFalse(recovery.canRetry)
        assertTrue(recovery.canDiscard)
    }

    @Test
    fun definitelyUnsentAndInterruptedDraftsRemainExplicitRecoveryNotAutomaticQueue() {
        listOf(false, true).forEach { incomplete ->
            val recovery = PendingDictation("unsent", "Text", incomplete = incomplete)
            assertTrue(recovery.canRetry)
            assertTrue(recovery.canDiscard)
            assertEquals(listOf(recovery), listOf(recovery).withoutVisibleInFlightReceipts(emptyList()))
        }
    }

    @Test
    fun onlyExactTurnAndThreadAcknowledgementRemovesDraft() {
        assertEquals(
            PendingDictationReceiptAction.KEEP,
            reconcilePendingDictationReceipt(
                submitted, listOf(receipt(OutboundMessageStatus.SENT).copy(clientUserMessageId = "other")),
            ),
        )
        assertEquals(
            PendingDictationReceiptAction.KEEP,
            reconcilePendingDictationReceipt(
                submitted, listOf(receipt(OutboundMessageStatus.SENT).copy(turnId = null)),
            ),
        )
        assertEquals(
            PendingDictationReceiptAction.ACKNOWLEDGE,
            reconcilePendingDictationReceipt(submitted, listOf(receipt(OutboundMessageStatus.SENT))),
        )
    }

    @Test
    fun uncertainFailureCannotBecomeAutomaticRetryEvenIfTransportSaysRetryable() {
        assertEquals(
            PendingDictationReceiptAction.MARK_UNKNOWN,
            reconcilePendingDictationReceipt(submitted, listOf(receipt(OutboundMessageStatus.FAILED))),
        )
        val unknown = submitted.copy(delivery = PendingDictationDelivery.OUTCOME_UNKNOWN)
        assertFalse(unknown.canRetry)
        assertEquals(PendingDictationReceiptAction.KEEP, reconcilePendingDictationReceipt(unknown, emptyList()))
        assertEquals(
            PendingDictationReceiptAction.ACKNOWLEDGE,
            reconcilePendingDictationReceipt(unknown, listOf(receipt(OutboundMessageStatus.SENT))),
        )
    }

    @Test
    fun incompleteTranscriptIsAnExplicitReviewableUnsentDraft() {
        val draft = PendingDictation("partial", "Treffen am", incomplete = true)
        assertTrue(draft.incomplete)
        assertTrue(draft.canRetry)
        assertEquals(PendingDictationDelivery.AWAITING_USER, draft.delivery)
        assertEquals(
            PendingDictationReceiptAction.KEEP,
            reconcilePendingDictationReceipt(draft, listOf(receipt(OutboundMessageStatus.SENT))),
        )
    }

    @Test
    fun delayedInitialAndOldPublicationCannotResurrectOrHideDrafts() {
        var stored = emptyList<PendingDictation>()
        val source = PendingDictationSnapshotSource { stored }
        val initialEmpty = source.capture()
        stored = listOf(submitted)
        val newDraft = source.capture()
        val observed = mutableListOf<PendingDictationSnapshot>()
        val observer = PendingDictationSnapshotObserver { observed += it }
        observer.deliver(newDraft)
        observer.deliver(initialEmpty)
        assertEquals(listOf(submitted), observed.last().drafts)
        stored = emptyList()
        val acknowledged = source.capture()
        observer.deliver(acknowledged)
        observer.deliver(newDraft)
        observer.deliver(acknowledged)
        assertTrue(observed.last().drafts.isEmpty())
        assertEquals(2, observed.size)
        observer.close()
        observer.deliver(source.capture())
        assertEquals(2, observed.size)
    }

    @Test
    fun snapshotReadFailureIsVisibleAndCannotOverwriteNewerRecovery() {
        var readable = false
        val source = PendingDictationSnapshotSource {
            check(readable)
            listOf(submitted)
        }
        val failed = source.capture()
        assertTrue(failed.storageUnavailable)
        readable = true
        val recovered = source.capture()
        assertFalse(recovered.storageUnavailable)
        assertTrue(recovered.revision > failed.revision)
    }

    @Test
    fun hostRestartMarksOnlyUnconfirmedOutcomeUnknownAndStillAllowsExactLateAck() {
        val recovered = submitted.afterSessionHostRestart()
        assertEquals(submitted.copy(delivery = PendingDictationDelivery.OUTCOME_UNKNOWN), recovered)
        assertFalse(recovered.canRetry)
        assertEquals(
            PendingDictationReceiptAction.ACKNOWLEDGE,
            reconcilePendingDictationReceipt(recovered, listOf(receipt(OutboundMessageStatus.SENT))),
        )
        val unsent = PendingDictation("unsent", "nur Entwurf", incomplete = true)
        assertEquals(unsent, unsent.afterSessionHostRestart())
        assertEquals(recovered, recovered.afterSessionHostRestart())
    }

    private fun receipt(status: OutboundMessageStatus) = OutboundUserMessageUi(
        clientUserMessageId = "outbound-one",
        threadId = "thread-one",
        displayText = submitted.transcript,
        status = status,
        retryable = true,
        turnId = if (status == OutboundMessageStatus.SENT) "turn-one" else null,
    )
}
