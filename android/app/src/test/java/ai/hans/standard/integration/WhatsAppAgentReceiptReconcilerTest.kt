package ai.hans.standard.integration

import ai.hans.standard.codex.ReasoningEffort
import ai.hans.standard.codex.RecoveredConversationItem
import ai.hans.standard.codex.RecoveredHistoryStatus
import ai.hans.standard.codex.ThreadResumeResult
import ai.hans.standard.codex.ThreadRuntimeStatus
import ai.hans.standard.codex.ThreadStatusSnapshot
import ai.hans.standard.codex.ThreadSummary
import ai.hans.standard.codex.ThreadTurnReceipt
import ai.hans.standard.codex.TurnStatus
import ai.hans.standard.notifications.agentchannel.AgentChannelLimits
import ai.hans.standard.notifications.agentchannel.AgentChannelState
import ai.hans.standard.notifications.agentchannel.AgentChannelStateCodec
import ai.hans.standard.notifications.agentchannel.AgentChannelStorage
import ai.hans.standard.notifications.agentchannel.AgentChannelWorkflowStatus
import ai.hans.standard.notifications.agentchannel.WhatsAppAgentChannel
import ai.hans.standard.notifications.agentchannel.WhatsAppNotificationMessage
import ai.hans.standard.notifications.agentchannel.WhatsAppNotificationSource
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Serialized restart fixtures; no replay, network, account or notification reply is involved. */
class WhatsAppAgentReceiptReconcilerTest {
    private var now = 1_000L
    private val storage = EncodedStorage()
    private val channel = reopened()
    private fun reopened() = WhatsAppAgentChannel(storage) { now }

    @Test fun acceptedThreadAndClientIdRecoverTurnAfterProcessRestartWithNoRamTimeline() {
        val id = prepared()
        assertTrue(channel.markDispatched(id, clientId(id)))
        val restarted = reopened()
        assertNull(restarted.unsettledReceipts().single().dispatchCorrelation?.turnId)
        val proof = agentChannelRecoveredHistoryFromResume(resume(id, TurnStatus.COMPLETED))!!
        assertFalse(proof.toString().contains("private persisted text"))

        WhatsAppAgentReceiptReconciler(restarted).reconcile(emptyList(), emptyList(), proof)

        val completed = storage.read().requests.single()
        assertEquals(AgentChannelWorkflowStatus.COMPLETED, completed.status)
        assertEquals("turn-accepted", completed.dispatchCorrelation?.turnId)
        assertEquals("", completed.requestText)
        assertTrue(restarted.unsettledReceipts().isEmpty())
        assertTrue(restarted.pendingReceipts().isEmpty())
    }

    @Test fun crashAfterSendBeforeDispatchCommitRecoversOnlyFromExactPersistedMessageReceipt() {
        val id = prepared() // Still CLAIMED, but thread/client identity was sealed before send.
        val restarted = reopened()
        WhatsAppAgentReceiptReconciler(restarted).reconcile(emptyList(), emptyList(),
            agentChannelRecoveredHistoryFromResume(resume(id, TurnStatus.COMPLETED)))
        assertEquals(AgentChannelWorkflowStatus.COMPLETED, storage.read().requests.single().status)
        assertTrue(restarted.pendingReceipts().isEmpty())
    }

    @Test fun persistedTurnIdCanSettleFromPositiveTurnReceiptWithoutRetainedUserItem() {
        val id = prepared()
        assertTrue(channel.markDispatched(id, clientId(id)))
        assertTrue(channel.recordAcceptedTurn(id, "thread-enrolled", clientId(id), "turn-accepted"))
        now += 3 * AgentChannelLimits.REQUEST_TTL_MILLIS
        val restarted = reopened()
        assertEquals(1, restarted.unsettledReceipts().size)
        assertEquals(1, restarted.status().uncertainCount)
        val history = resume(id, TurnStatus.INTERRUPTED).copy(recoveredItems = emptyList())
        WhatsAppAgentReceiptReconciler(restarted).reconcile(emptyList(), emptyList(),
            agentChannelRecoveredHistoryFromResume(history))
        assertEquals(AgentChannelWorkflowStatus.FAILED, storage.read().requests.single().status)
        assertEquals(now, storage.read().requests.single().settledAtEpochMillis)
    }

    @Test fun unrelatedThreadClientIdOrMissingTurnCannotInventCompletionFromIdleHistory() {
        val id = prepared()
        assertTrue(channel.markDispatched(id, clientId(id)))
        val reconciler = WhatsAppAgentReceiptReconciler(reopened())
        val correct = agentChannelRecoveredHistoryFromResume(resume(id, TurnStatus.COMPLETED))!!
        listOf(correct.copy(threadId = "other-thread"), correct.copy(messageTurns = emptyMap()),
            correct.copy(messageTurns = emptyMap(), turnStatuses = emptyMap()),
            correct.copy(messageTurns = mapOf("unrelated-client" to "turn-accepted")))
            .forEach { proof ->
                reconciler.reconcile(emptyList(), emptyList(), proof)
                assertEquals(AgentChannelWorkflowStatus.DISPATCHED, storage.read().requests.single().status)
                assertEquals(1, reopened().status().uncertainCount)
            }
    }

    @Test fun terminalProofMustMatchTheImmutablePersistedTurnAndCannotOverwriteIt() {
        val id = prepared()
        assertTrue(channel.markDispatched(id, clientId(id)))
        assertTrue(channel.recordAcceptedTurn(id, "thread-enrolled", clientId(id), "original-turn"))
        WhatsAppAgentReceiptReconciler(reopened()).reconcile(emptyList(), emptyList(),
            agentChannelRecoveredHistoryFromResume(resume(id, TurnStatus.COMPLETED)))
        assertEquals(AgentChannelWorkflowStatus.DISPATCHED, storage.read().requests.single().status)
        assertEquals("original-turn", storage.read().requests.single().dispatchCorrelation?.turnId)
    }

    @Test fun legacyUncorrelatedClaimAndDispatchRemainVisibleWithoutRetryOrGuessedAssociation() {
        for (status in listOf(AgentChannelWorkflowStatus.CLAIMED, AgentChannelWorkflowStatus.DISPATCHED)) {
            channel.clearPrivateData()
            val id = ready()
            channel.claim(id)
            if (status == AgentChannelWorkflowStatus.DISPATCHED) assertTrue(channel.markDispatched(id))
            now += AgentChannelLimits.REQUEST_TTL_MILLIS + 1
            val restarted = reopened()
            WhatsAppAgentReceiptReconciler(restarted).reconcile(emptyList(), emptyList(),
                agentChannelRecoveredHistoryFromResume(resume(id, TurnStatus.COMPLETED)))
            assertEquals(status, storage.read().requests.single().status)
            assertEquals(1, restarted.status().uncertainCount)
            assertEquals(1, restarted.unsettledReceipts().size)
            assertTrue(restarted.pendingReceipts().isEmpty())
            assertFalse(WhatsAppAgentHandoff(restarted, { true }) {
                fail("Uncorrelated admitted work must never be resent")
                CodexDispatchAttemptResult.RejectedBeforeTransport
            }.drainOne())
        }
    }

    @Test fun absentMalformedOrConflictingPersistedHistoryIsNotACompletionProof() {
        val id = prepared()
        assertNull(agentChannelRecoveredHistoryFromResume(resume(id, TurnStatus.COMPLETED)
            .copy(recoveredHistoryStatus = RecoveredHistoryStatus.UNAVAILABLE)))
        val item = (resume(id, TurnStatus.COMPLETED).recoveredItems.single() as RecoveredConversationItem.User)
        assertNull(agentChannelRecoveredHistoryFromResume(resume(id, TurnStatus.COMPLETED).copy(
            recoveredItems = listOf(item, item.copy(itemId = "different-item", turnId = "other-turn")),
            initialTurnReceipts = listOf(ThreadTurnReceipt("turn-accepted", TurnStatus.COMPLETED),
                ThreadTurnReceipt("other-turn", TurnStatus.COMPLETED)))))
        assertNull(agentChannelRecoveredHistoryFromResume(resume(id, TurnStatus.COMPLETED).copy(
            initialTurnReceipts = listOf(ThreadTurnReceipt("turn-accepted", TurnStatus.COMPLETED),
                ThreadTurnReceipt("turn-accepted", TurnStatus.COMPLETED)))))
    }

    @Test fun runningHistoryIsPositiveAdmissionButNotTaskCompletion() {
        val id = prepared()
        val restarted = reopened()
        WhatsAppAgentReceiptReconciler(restarted).reconcile(emptyList(), emptyList(),
            agentChannelRecoveredHistoryFromResume(resume(id, TurnStatus.IN_PROGRESS)))
        assertEquals(AgentChannelWorkflowStatus.DISPATCHED, storage.read().requests.single().status)
        assertEquals("turn-accepted", storage.read().requests.single().dispatchCorrelation?.turnId)
        assertEquals(1, restarted.status().uncertainCount)
    }

    @Test fun liveTurnAcceptanceIsPersistedBeforeLaterRestartTerminalReconciliation() {
        val id = prepared()
        assertTrue(channel.markDispatched(id, clientId(id)))
        val outbound = OutboundUserMessageUi(clientId(id), "thread-enrolled", "not inspected",
            OutboundMessageStatus.SENT, false, "turn-accepted")
        WhatsAppAgentReceiptReconciler(channel).reconcile(listOf(outbound), emptyList(), null)
        assertEquals("turn-accepted", storage.read().requests.single().dispatchCorrelation?.turnId)
        WhatsAppAgentReceiptReconciler(reopened()).reconcile(emptyList(),
            listOf(ClientTerminalTurn("thread-enrolled", "turn-accepted", TurnStatus.COMPLETED)), null)
        assertEquals(AgentChannelWorkflowStatus.COMPLETED, storage.read().requests.single().status)
    }

    @Test fun localTransportFailureWithoutTerminalReceiptRemainsUncertain() {
        val id = prepared()
        assertTrue(channel.markDispatched(id, clientId(id)))
        WhatsAppAgentReceiptReconciler(channel).reconcile(listOf(OutboundUserMessageUi(clientId(id),
            "thread-enrolled", "unused", OutboundMessageStatus.FAILED, true)), emptyList(), null)
        assertEquals(AgentChannelWorkflowStatus.UNCERTAIN, storage.read().requests.single().status)
        assertTrue(reopened().pendingReceipts().isEmpty())
    }

    @Test fun sourceRevocationAndPrivacyPurgePreventLateHistoryFromRestoringWork() {
        val id = prepared()
        assertTrue(channel.markDispatched(id, clientId(id)))
        assertTrue(channel.revoke())
        WhatsAppAgentReceiptReconciler(channel).reconcile(emptyList(), emptyList(),
            agentChannelRecoveredHistoryFromResume(resume(id, TurnStatus.COMPLETED)))
        assertEquals(AgentChannelWorkflowStatus.CANCELLED, storage.read().requests.single().status)
        assertNull(storage.read().requests.single().dispatchCorrelation)
        assertTrue(channel.clearPrivateData())
        WhatsAppAgentReceiptReconciler(channel).reconcile(emptyList(), emptyList(),
            agentChannelRecoveredHistoryFromResume(resume(id, TurnStatus.COMPLETED)))
        assertEquals(AgentChannelState(), storage.read())
    }

    @Test fun controllerPublishesOnlyRawLoadedHistoryAndClearsItOnRuntimeAndThreadChanges() {
        val source = File("src/main/java/ai/hans/standard/integration/CodexSessionController.kt").readText()
        val resume = source.substringAfter("is RequestPurpose.ThreadResume -> {\n                invalidateSelectionContext()")
            .substringBefore("is RequestPurpose.ThreadStart ->")
        assertTrue(resume.contains("recoveredAgentChannelHistory = agentChannelRecoveredHistoryFromResume(resumed)"))
        assertTrue(resume.indexOf("agentChannelRecoveredHistoryFromResume(resumed)") < resume.indexOf("hydrateRecoveredTimeline("))
        val getter = source.substringAfter("fun agentChannelRecoveredHistory():").substringBefore("\n    /**")
        assertTrue(getter.contains("!signedIn"))
        assertTrue(getter.contains("threadReady"))
        assertTrue(getter.contains("reducer.snapshot().currentThreadId"))
        assertTrue(Regex("recoveredAgentChannelHistory = null").findAll(source).count() >= 2)
    }

    private fun prepared(): String = ready().also { id ->
        assertNotNull(channel.claim(id))
        assertTrue(channel.prepareDispatch(id, "thread-enrolled", clientId(id)))
    }

    private fun ready(): String {
        val own = "a".repeat(64)
        fun source(text: String) = WhatsAppNotificationSource("com.whatsapp", 0, 12345,
            "source-key", "enrolled-chat", own, false, false,
            listOf(WhatsAppNotificationMessage(text, now, own, true)))
        channel.observe(source("Enrollment only"))
        assertTrue(channel.confirmSelfChat(channel.listEnrollmentCandidates().single().id))
        now++
        return channel.observe(source("[Codex:] [id:request] Open an app")).readyReceipts.single().id
    }

    private fun resume(id: String, status: TurnStatus) = ThreadResumeResult(
        thread = ThreadSummary("thread-enrolled", null, "", 1, 1, ThreadStatusSnapshot(ThreadRuntimeStatus.IDLE)),
        effectiveModel = "model-test", effectiveEffort = ReasoningEffort.of("medium"), effectiveServiceTier = null,
        initialTurnReceipts = listOf(ThreadTurnReceipt("turn-accepted", status)),
        recoveredItems = listOf(RecoveredConversationItem.User("user-item", clientId(id), "turn-accepted",
            listOf("private persisted text must not enter the recovery proof"), false)),
        turnsBackwardsCursor = null, itemsBackwardsCursor = null,
        recoveredHistoryStatus = RecoveredHistoryStatus.LOADED,
    )

    private fun clientId(id: String) = "whatsapp-agent-$id"
    private class EncodedStorage : AgentChannelStorage {
        private var encoded = AgentChannelStateCodec.encode(AgentChannelState())
        override fun read(): AgentChannelState = AgentChannelStateCodec.decode(encoded)
        override fun write(state: AgentChannelState) { encoded = AgentChannelStateCodec.encode(state) }
    }
}
