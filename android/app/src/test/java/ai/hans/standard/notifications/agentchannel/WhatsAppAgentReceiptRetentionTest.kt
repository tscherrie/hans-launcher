package ai.hans.standard.notifications.agentchannel

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Intake freshness cannot erase the only durable record of possibly executed work. */
class WhatsAppAgentReceiptRetentionTest {
    private var now = 1_000L
    private val storage = EncodedStorage()
    private fun channel() = WhatsAppAgentChannel(storage) { now }
    private val own = "a".repeat(64)
    private fun source(text: String) = WhatsAppNotificationSource("com.whatsapp", 0, 12345,
        "notification-key", "enrolled-chat", own, false, false,
        listOf(WhatsAppNotificationMessage(text, now, own, true)))
    private fun enrolled(): WhatsAppAgentChannel = channel().also {
        it.observe(source("Enrollment only"))
        assertTrue(it.confirmSelfChat(it.listEnrollmentCandidates().single().id))
        now++
    }
    private fun request(c: WhatsAppAgentChannel, id: String = "request"): String =
        c.observe(source("[Codex:] [id:$id] Open an app")).readyReceipts.single().id
    private fun clientId(id: String) = "whatsapp-agent-$id"

    @Test fun expiredReadyInputCannotBecomeAClaimEvenThoughTheSourceIsStillEnrolled() {
        val c = enrolled()
        val id = request(c)
        now += AgentChannelLimits.REQUEST_TTL_MILLIS + 1
        assertNull(c.claim(id))
        assertTrue(c.pendingReceipts().isEmpty())
        assertTrue(c.unsettledReceipts().isEmpty())
        assertEquals(0, c.status().uncertainCount)
    }

    @Test fun allAdmittedStatusesRemainVisibleAndSettleableAfterSeveralIntakeTtls() {
        for (status in listOf(AgentChannelWorkflowStatus.CLAIMED,
            AgentChannelWorkflowStatus.DISPATCHED, AgentChannelWorkflowStatus.UNCERTAIN)) {
            storage.write(AgentChannelState())
            val c = enrolled()
            val id = request(c)
            assertNotNull(c.claim(id))
            assertTrue(c.prepareDispatch(id, "thread-selected", clientId(id)))
            if (status == AgentChannelWorkflowStatus.DISPATCHED) assertTrue(c.markDispatched(id, clientId(id)))
            if (status == AgentChannelWorkflowStatus.UNCERTAIN) assertTrue(c.markUncertain(id))
            now += 3 * AgentChannelLimits.REQUEST_TTL_MILLIS
            val reopened = channel()
            assertEquals(status, reopened.unsettledReceipts().single().status)
            assertEquals(1, reopened.status().uncertainCount)
            assertTrue(reopened.pendingReceipts().isEmpty())
            assertFalse(reopened.prepareDispatch(id, "thread-other", clientId(id)))
            assertTrue(reopened.recordAcceptedTurn(id, "thread-selected", clientId(id), "turn-exact"))
            assertTrue(reopened.complete(id, true))
            assertEquals(0, reopened.status().uncertainCount)
            assertEquals("", storage.read().requests.single().requestText)
        }
    }

    @Test fun lateCompletionRetainsDedupeFromSettlementRatherThanOldReceiptTime() {
        val c = enrolled()
        val id = request(c)
        assertNotNull(c.claim(id))
        now += 3 * AgentChannelLimits.REQUEST_TTL_MILLIS
        assertTrue(c.complete(id, true))
        val settled = now
        assertEquals(settled, storage.read().requests.single().settledAtEpochMillis)
        now++
        assertTrue(c.observe(source("[Codex:] [id:request] Open an app")).readyReceipts.isEmpty())
        assertEquals(AgentChannelWorkflowStatus.COMPLETED, storage.read().requests.single().status)
        now = settled + AgentChannelLimits.TOMBSTONE_TTL_MILLIS + 1
        assertEquals(1, c.observe(source("[Codex:] [id:request] Open an app")).readyReceipts.size)
    }

    @Test fun boundedFullInFlightLedgerFailsClosedUntilExplicitCancellationAndFiniteTombstones() {
        val c = enrolled()
        repeat(AgentChannelLimits.MAX_REQUESTS) { index ->
            val id = request(c, "request-$index")
            assertNotNull(c.claim(id))
            assertTrue(c.prepareDispatch(id, "thread-selected", clientId(id)))
            assertTrue(c.markDispatched(id, clientId(id)))
        }
        now += 3 * AgentChannelLimits.REQUEST_TTL_MILLIS
        val reopened = channel()
        assertEquals(AgentChannelLimits.MAX_REQUESTS, reopened.status().uncertainCount)
        assertEquals(AgentChannelLimits.MAX_REQUESTS, reopened.unsettledReceipts().size)
        assertEquals("ledger_full", reopened.observe(source("[Codex:] [id:new] Open an app")).failureCode)
        assertEquals(AgentChannelLimits.MAX_REQUESTS, storage.read().requests.size)
        assertTrue(reopened.cancelPending())
        assertTrue(storage.read().requests.all { it.status == AgentChannelWorkflowStatus.CANCELLED &&
            it.requestText.isEmpty() && it.dispatchCorrelation == null && it.settledAtEpochMillis == now })
        assertEquals("ledger_full", reopened.observe(source("[Codex:] [id:new] Open an app")).failureCode)
        now += AgentChannelLimits.TOMBSTONE_TTL_MILLIS + 1
        assertEquals(1, reopened.observe(source("[Codex:] [id:new] Open an app")).readyReceipts.size)
        assertEquals(1, storage.read().requests.size)
    }

    @Test fun dispatchPreparationSealsExactIdentityBeforeTransportAndNeverRewritesIt() {
        val c = enrolled()
        val id = request(c)
        assertFalse(c.prepareDispatch(id, "thread-selected", clientId(id))) // Not yet durably claimed.
        assertNotNull(c.claim(id))
        assertFalse(c.prepareDispatch(id, "thread-selected", "unrelated-message"))
        assertFalse(c.prepareDispatch(id, "", clientId(id)))
        assertTrue(c.prepareDispatch(id, "thread-selected", clientId(id)))
        val reopened = channel()
        assertEquals(AgentChannelDispatchCorrelation("thread-selected", clientId(id)),
            reopened.unsettledReceipts().single().dispatchCorrelation)
        assertTrue(reopened.prepareDispatch(id, "thread-selected", clientId(id)))
        assertFalse(reopened.prepareDispatch(id, "thread-changed", clientId(id)))
        assertFalse(reopened.markDispatched(id, "unrelated-message"))
        assertTrue(reopened.markDispatched(id, clientId(id)))
        assertFalse(reopened.recordAcceptedTurn(id, "thread-changed", clientId(id), "turn-exact"))
        assertFalse(reopened.recordAcceptedTurn(id, "thread-selected", "unrelated-message", "turn-exact"))
        assertTrue(reopened.recordAcceptedTurn(id, "thread-selected", clientId(id), "turn-exact"))
        assertFalse(reopened.recordAcceptedTurn(id, "thread-selected", clientId(id), "different-turn"))
    }

    @Test fun definitelyUnsentReleaseErasesPreparationButAmbiguousAdmissionCannotRetry() {
        val c = enrolled()
        val id = request(c)
        assertNotNull(c.claim(id))
        assertTrue(c.prepareDispatch(id, "thread-old", clientId(id)))
        assertTrue(c.releaseUnsentClaim(id))
        assertNull(storage.read().requests.single().dispatchCorrelation)
        assertNotNull(c.claim(id))
        assertTrue(c.prepareDispatch(id, "thread-new", clientId(id)))
        assertTrue(c.markUncertain(id))
        assertFalse(c.releaseUnsentClaim(id))
        assertTrue(c.pendingReceipts().isEmpty())
    }

    @Test fun failedPreparationPersistenceNeverClaimsToHaveSealedTransportIdentity() {
        val c = enrolled()
        val id = request(c)
        assertNotNull(c.claim(id))
        storage.failWrites = true
        assertFalse(c.prepareDispatch(id, "thread-selected", clientId(id)))
        assertNull(storage.read().requests.single().dispatchCorrelation)
        assertEquals(AgentChannelWorkflowStatus.CLAIMED, storage.read().requests.single().status)
    }

    @Test fun legacyVersionOneFilesOmitAdditiveFieldsWithoutGuessingDispatchIdentity() {
        val c = enrolled()
        val id = request(c)
        assertNotNull(c.claim(id))
        assertTrue(c.markDispatched(id))
        val legacy = JSONObject(storage.encoded)
        legacy.getJSONArray("requests").getJSONObject(0).apply { remove("correlation"); remove("settled") }
        val decoded = AgentChannelStateCodec.decode(legacy.toString())
        assertNull(decoded.requests.single().dispatchCorrelation)
        assertNull(decoded.requests.single().settledAtEpochMillis)
        storage.encoded = legacy.toString()
        now += AgentChannelLimits.REQUEST_TTL_MILLIS + 1
        assertEquals(1, channel().status().uncertainCount)
    }

    @Test fun malformedPresentCorrelationFailsClosedInsteadOfBecomingLegacyNull() {
        val c = enrolled()
        val id = request(c)
        assertNotNull(c.claim(id))
        assertTrue(c.prepareDispatch(id, "thread-selected", clientId(id)))
        val original = storage.encoded
        val valid = JSONObject().put("threadId", "thread-selected").put("clientUserMessageId", clientId(id))
            .put("turnId", JSONObject.NULL)
        val malformed = listOf<Any>("not-an-object", 7, true,
            JSONObject(valid.toString()).apply { remove("threadId") },
            JSONObject(valid.toString()).apply { remove("turnId") },
            JSONObject(valid.toString()).put("unexpected", "extra"),
            JSONObject(valid.toString()).put("threadId", ""),
            JSONObject(valid.toString()).put("threadId", "x".repeat(257)),
            JSONObject(valid.toString()).put("threadId", "thread\nother"),
            JSONObject(valid.toString()).put("threadId", JSONObject.NULL),
            JSONObject(valid.toString()).put("clientUserMessageId", 123),
            JSONObject(valid.toString()).put("clientUserMessageId", "whatsapp-agent-other-receipt"),
            JSONObject(valid.toString()).put("turnId", false))
        malformed.forEach { broken ->
            val document = JSONObject(original)
            document.getJSONArray("requests").getJSONObject(0).put("correlation", broken)
            assertThrows(Exception::class.java) { AgentChannelStateCodec.decode(document.toString()) }
        }
    }

    @Test fun preparedReadyOrMalformedSettlementCannotBeLoadedAsAnExecutableLedger() {
        val c = enrolled()
        val id = request(c)
        assertNotNull(c.claim(id))
        assertTrue(c.prepareDispatch(id, "thread-selected", clientId(id)))
        val prepared = JSONObject(storage.encoded)
        prepared.getJSONArray("requests").getJSONObject(0).put("status", "READY")
        assertThrows(Exception::class.java) { AgentChannelStateCodec.decode(prepared.toString()) }
        val original = storage.encoded
        listOf<Any>(-1, "1000", 1.25, true).forEach { broken ->
            val document = JSONObject(original)
            document.getJSONArray("requests").getJSONObject(0).put("settled", broken)
            assertThrows(Exception::class.java) { AgentChannelStateCodec.decode(document.toString()) }
        }
        val claimedWithSettlement = JSONObject(original)
        claimedWithSettlement.getJSONArray("requests").getJSONObject(0).put("settled", now)
        assertThrows(Exception::class.java) { AgentChannelStateCodec.decode(claimedWithSettlement.toString()) }
    }

    private class EncodedStorage : AgentChannelStorage {
        var encoded = AgentChannelStateCodec.encode(AgentChannelState())
        var failWrites = false
        override fun read() = AgentChannelStateCodec.decode(encoded)
        override fun write(state: AgentChannelState) {
            check(!failWrites)
            encoded = AgentChannelStateCodec.encode(state)
        }
    }
}
