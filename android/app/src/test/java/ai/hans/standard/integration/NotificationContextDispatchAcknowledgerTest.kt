package ai.hans.standard.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationContextDispatchAcknowledgerTest {
    @Test
    fun contextIsAcknowledgedOnlyAfterExactOutboundBecomesSent() {
        val marked = mutableListOf<Set<String>>()
        val acknowledger = NotificationContextDispatchAcknowledger(
            markInjected = { _, ids -> marked += ids; true },
        )
        acknowledger.register("message-1", setOf("notification-1"))

        acknowledger.observe(listOf(outbound("message-1", OutboundMessageStatus.PENDING)))
        assertTrue(marked.isEmpty())
        assertEquals(setOf("notification-1"), acknowledger.pendingIdsForTest())

        acknowledger.observe(
            listOf(
                outbound("message-1", OutboundMessageStatus.SENT).copy(turnId = null),
            ),
        )
        assertTrue(marked.isEmpty())

        acknowledger.observe(listOf(outbound("message-1", OutboundMessageStatus.SENT)))
        assertEquals(listOf(setOf("notification-1")), marked)
        assertTrue(acknowledger.pendingIdsForTest().isEmpty())
    }

    @Test
    fun failedTransportLeavesNotificationContextDurablyReservedBecauseDeliveryIsAmbiguous() {
        val marked = mutableListOf<Set<String>>()
        val acknowledger = NotificationContextDispatchAcknowledger(
            markInjected = { _, ids -> marked += ids; true },
        )
        acknowledger.register("message-failed", setOf("notification-retry"))

        acknowledger.observe(listOf(outbound("message-failed", OutboundMessageStatus.FAILED)))

        assertTrue(marked.isEmpty())
        assertEquals(setOf("notification-retry"), acknowledger.pendingIdsForTest())
    }

    @Test
    fun acceptedPendingDispatchRemainsReservedThroughAmbiguousFailureUntilSentReceipt() {
        val acknowledger = NotificationContextDispatchAcknowledger(
            markInjected = { _, _ -> true },
        )
        val available = setOf("notification-1", "notification-2")

        acknowledger.register("message-a", available)
        assertEquals(available, acknowledger.reservedAnnouncementIds())
        assertTrue((available - acknowledger.reservedAnnouncementIds()).isEmpty())

        acknowledger.observe(listOf(outbound("message-a", OutboundMessageStatus.PENDING)))
        assertEquals(available, acknowledger.reservedAnnouncementIds())

        acknowledger.observe(listOf(outbound("message-a", OutboundMessageStatus.FAILED)))
        assertEquals(available, acknowledger.reservedAnnouncementIds())
    }

    @Test
    fun failedSentPersistenceAndCorrelationOverflowRemainFailClosedUntilReset() {
        val acknowledger = NotificationContextDispatchAcknowledger(
            markInjected = { _, _ -> false },
        )
        acknowledger.register("message-write-failure", setOf("notification-write-failure"))
        acknowledger.observe(
            listOf(outbound("message-write-failure", OutboundMessageStatus.SENT)),
        )
        assertEquals(
            setOf("notification-write-failure"),
            acknowledger.reservedAnnouncementIds(),
        )

        repeat(20) { index ->
            acknowledger.register("message-$index", setOf("notification-$index"))
        }
        val reserved = acknowledger.reservedAnnouncementIds()
        assertTrue("notification-write-failure" in reserved)
        repeat(20) { index -> assertTrue("notification-$index" in reserved) }

        acknowledger.clearProcessCorrelations()
        assertTrue(acknowledger.reservedAnnouncementIds().isEmpty())
    }

    @Test
    fun restoredDurableReservationSurvivesReconnectAndTerminalReceiptMutatesExactBatch() {
        val marked = mutableListOf<Pair<String, Set<String>>>()
        val acknowledger = NotificationContextDispatchAcknowledger(
            markInjected = { messageId, ids -> marked += messageId to ids; true },
        )
        acknowledger.restore(mapOf("message-restored" to setOf("notification-restored")))

        acknowledger.observe(listOf(outbound("message-restored", OutboundMessageStatus.PENDING)))
        assertEquals(setOf("notification-restored"), acknowledger.reservedAnnouncementIds())

        acknowledger.observe(listOf(outbound("message-restored", OutboundMessageStatus.SENT)))
        assertEquals(
            listOf("message-restored" to setOf("notification-restored")),
            marked,
        )
        assertTrue(acknowledger.reservedAnnouncementIds().isEmpty())
    }

    @Test
    fun failedReceiptRemainsFailClosedUntilAnExplicitDefiniteRelease() {
        val acknowledger = NotificationContextDispatchAcknowledger(
            markInjected = { _, _ -> true },
        )
        acknowledger.register("message-failed", setOf("notification-failed"))

        acknowledger.observe(listOf(outbound("message-failed", OutboundMessageStatus.FAILED)))
        assertEquals(setOf("notification-failed"), acknowledger.reservedAnnouncementIds())

        acknowledger.forgetAfterDefiniteRelease(
            "message-failed",
            setOf("notification-failed"),
        )
        assertTrue(acknowledger.reservedAnnouncementIds().isEmpty())
    }

    @Test
    fun everyBoundedCenterCorrelationIncludingEntriesSeventeenThroughOneHundredCanComplete() {
        val marked = linkedSetOf<String>()
        val acknowledger = NotificationContextDispatchAcknowledger(
            markInjected = { _, ids -> marked += ids; true },
        )
        repeat(100) { index ->
            acknowledger.register("message-$index", setOf("notification-$index"))
        }
        assertEquals(100, acknowledger.reservedAnnouncementIds().size)

        acknowledger.observe(
            List(100) { index -> outbound("message-$index", OutboundMessageStatus.SENT) },
        )

        assertEquals(100, marked.size)
        assertTrue("notification-16" in marked)
        assertTrue("notification-99" in marked)
        assertTrue(acknowledger.reservedAnnouncementIds().isEmpty())
    }

    @Test
    fun restoreReplacesStalePriorGenerationCorrelations() {
        val acknowledger = NotificationContextDispatchAcknowledger(
            markInjected = { _, _ -> true },
        )
        acknowledger.register("old-message", setOf("same-stable-id"))

        acknowledger.restore(mapOf("new-message" to setOf("new-generation-id")))

        assertEquals(setOf("new-generation-id"), acknowledger.reservedAnnouncementIds())
        acknowledger.observe(listOf(outbound("old-message", OutboundMessageStatus.SENT)))
        assertEquals(setOf("new-generation-id"), acknowledger.reservedAnnouncementIds())
    }

    private fun outbound(
        id: String,
        status: OutboundMessageStatus,
    ): OutboundUserMessageUi = OutboundUserMessageUi(
        clientUserMessageId = id,
        threadId = "thread-1",
        displayText = "text",
        status = status,
        retryable = status == OutboundMessageStatus.FAILED,
        turnId = if (status == OutboundMessageStatus.SENT) "turn-1" else null,
    )
}
