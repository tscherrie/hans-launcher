package ai.hans.standard

import ai.hans.standard.integration.OutboundMessageStatus
import ai.hans.standard.integration.OutboundUserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Test

class SetupConversationDispatchGateTest {
    @Test
    fun missingDispatchIsRejectedButLocallyPendingDispatchStillWaits() {
        assertEquals(
            SetupConversationDispatchAcceptance.REJECTED,
            setupConversationDispatchAcceptance("setup-message", emptyList()),
        )
        assertEquals(
            SetupConversationDispatchAcceptance.WAITING,
            setupConversationDispatchAcceptance(
                "setup-message",
                listOf(outbound(OutboundMessageStatus.PENDING)),
            ),
        )
    }

    @Test
    fun onlyCorrelatedSentReceiptAcceptsDurableSetupStart() {
        assertEquals(
            SetupConversationDispatchAcceptance.REJECTED,
            setupConversationDispatchAcceptance(
                "setup-message",
                listOf(outbound(OutboundMessageStatus.SENT, messageId = "other-message")),
            ),
        )
        assertEquals(
            SetupConversationDispatchAcceptance.ACCEPTED,
            setupConversationDispatchAcceptance(
                "setup-message",
                listOf(
                    outbound(OutboundMessageStatus.SENT, messageId = "other-message"),
                    outbound(OutboundMessageStatus.SENT),
                ),
            ),
        )
    }

    @Test
    fun correlatedFailedReceiptRejectsDurableSetupStart() {
        assertEquals(
            SetupConversationDispatchAcceptance.REJECTED,
            setupConversationDispatchAcceptance(
                "setup-message",
                listOf(outbound(OutboundMessageStatus.FAILED)),
            ),
        )
    }

    @Test
    fun savedPendingReceiptSurvivesRecreationUntilItsExactSentReceipt() {
        val original = "hans-setup-84e3b844-47da-42eb-b8f0-bda209b8dd78"
        val saved = SetupConversationPendingReceiptContract.save(original)
        val restored = SetupConversationPendingReceiptContract.restore(saved)

        assertEquals(original, restored)
        assertEquals(
            SetupConversationDispatchAcceptance.WAITING,
            setupConversationDispatchAcceptance(
                checkNotNull(restored),
                listOf(outbound(OutboundMessageStatus.PENDING, original)),
            ),
        )
        assertEquals(
            SetupConversationDispatchAcceptance.ACCEPTED,
            setupConversationDispatchAcceptance(
                restored,
                listOf(outbound(OutboundMessageStatus.SENT, original)),
            ),
        )
    }

    @Test
    fun savedPendingReceiptRejectsUntrustedOrUnboundedIds() {
        assertEquals(null, SetupConversationPendingReceiptContract.restore("hans-other-1"))
        assertEquals(null, SetupConversationPendingReceiptContract.restore("hans-setup-"))
        assertEquals(
            null,
            SetupConversationPendingReceiptContract.restore("hans-setup-${"x".repeat(256)}"),
        )
        assertEquals(
            null,
            SetupConversationPendingReceiptContract.restore("hans-setup-bad\nvalue"),
        )
    }

    @Test
    fun sentReceiptRetriesFailedPersistenceWithoutPersistingOptimistically() {
        val pending = listOf(outbound(OutboundMessageStatus.PENDING))
        var writes = 0
        assertEquals(
            SetupConversationReceiptOutcome.WAITING,
            reconcileSetupConversationReceipt("setup-message", pending) { writes += 1 },
        )
        assertEquals(0, writes)

        val sent = listOf(outbound(OutboundMessageStatus.SENT))
        val failed = reconcileSetupConversationReceipt("setup-message", sent) {
            writes += 1
            error("storage unavailable")
        }
        assertEquals(SetupConversationReceiptOutcome.PERSIST_FAILED, failed)
        assertEquals(true, failed.keepsPending)

        val retried = reconcileSetupConversationReceipt("setup-message", sent) {
            writes += 1
        }
        assertEquals(SetupConversationReceiptOutcome.PERSISTED, retried)
        assertEquals(false, retried.keepsPending)
        assertEquals(2, writes)
    }

    private fun outbound(
        status: OutboundMessageStatus,
        messageId: String = "setup-message",
    ) = OutboundUserMessageUi(
        clientUserMessageId = messageId,
        threadId = "thread-1",
        displayText = "Einrichtung starten",
        status = status,
        retryable = status == OutboundMessageStatus.FAILED,
        turnId = "turn-1".takeIf { status == OutboundMessageStatus.SENT },
    )
}
