package ai.hans.standard.notifications.hooks

import ai.hans.standard.codex.TurnStatus
import ai.hans.standard.integration.NativeNotificationExternalHistory
import ai.hans.standard.integration.NativeNotificationExternalReceipt
import org.junit.Assert.*
import org.junit.Test

class NotificationEventReceiptReconcilerTest {
    @Test fun emptyOrMissingHistoryNeverReleasesUncertainEventForReplay() {
        val (ledger, record) = fixture()
        val reconciler = NotificationEventReceiptReconciler(ledger)
        reconciler.reconcile(null, emptyList())
        reconciler.reconcile(NativeNotificationExternalHistory("main-thread", emptyList(), emptyMap()), emptyList())
        assertEquals(record.eventId, ledger.unsettled().single().eventId)
        assertEquals(NotificationEventPhase.UNCERTAIN, ledger.unsettled().single().phase)
        assertTrue(ledger.pending().isEmpty())
    }

    @Test fun exactRawToolReceiptAndTerminalTurnRecoverAndSettleWithoutResending() {
        val (ledger, record) = fixture()
        val receipt = proof(record)
        NotificationEventReceiptReconciler(ledger).reconcile(
            NativeNotificationExternalHistory("main-thread", listOf(receipt), mapOf("turn-1" to TurnStatus.COMPLETED)), emptyList())
        assertTrue(ledger.unsettled().isEmpty())
        assertTrue(ledger.pending().isEmpty())
    }

    @Test fun sameEventInWrongThreadCannotSettleOrRecoverLocalClaim() {
        val (ledger, record) = fixture()
        NotificationEventReceiptReconciler(ledger).reconcile(
            NativeNotificationExternalHistory("other-thread", listOf(proof(record).copy(threadId = "other-thread")),
                mapOf("turn-1" to TurnStatus.COMPLETED)),
            listOf(NotificationEventTerminalProof("other-thread", "turn-1", TurnStatus.COMPLETED)))
        assertEquals(NotificationEventPhase.UNCERTAIN, ledger.unsettled().single().phase)
    }

    @Test fun wrongHashToolNameNamespaceOrConflictingTurnsCannotProveIngestion() {
        listOf<(NativeNotificationExternalReceipt) -> List<NativeNotificationExternalReceipt>>(
            { listOf(it.copy(payloadSha256 = "0".repeat(64))) },
            { listOf(it.copy(toolName = "some_other_tool")) },
            { listOf(it.copy(toolNamespace = "some_other_namespace")) },
            { listOf(it, it.copy(turnId = "turn-2")) },
        ).forEach { mutate ->
            val (ledger, record) = fixture()
            NotificationEventReceiptReconciler(ledger).reconcile(
                NativeNotificationExternalHistory("main-thread", mutate(proof(record)), mapOf("turn-1" to TurnStatus.COMPLETED)), emptyList())
            assertEquals(NotificationEventPhase.UNCERTAIN, ledger.unsettled().single().phase)
            assertEquals("recovery_correlation_conflict", ledger.status().failureCode)
            assertTrue(ledger.pending().isEmpty())
        }
    }

    @Test fun conflictingLiveAndPersistedTerminalStatusesRemainUnsettled() {
        val (ledger, record) = fixture()
        NotificationEventReceiptReconciler(ledger).reconcile(
            NativeNotificationExternalHistory("main-thread", listOf(proof(record)), mapOf("turn-1" to TurnStatus.COMPLETED)),
            listOf(NotificationEventTerminalProof("main-thread", "turn-1", TurnStatus.FAILED)))
        assertEquals(NotificationEventPhase.ACCEPTED, ledger.unsettled().single().phase)
        assertEquals("terminal_correlation_conflict", ledger.status().failureCode)
    }

    @Test fun liveTerminalProofSettlesOnlyAlreadyAcceptedExactTurn() {
        val (ledger, record) = fixture()
        val reconciler = NotificationEventReceiptReconciler(ledger)
        val terminal = listOf(NotificationEventTerminalProof("main-thread", "turn-1", TurnStatus.INTERRUPTED))
        reconciler.reconcile(null, terminal)
        assertEquals(NotificationEventPhase.UNCERTAIN, ledger.unsettled().single().phase)
        assertTrue(ledger.accepted(record, "main-thread", "turn-1"))
        reconciler.reconcile(null, terminal)
        assertTrue(ledger.unsettled().isEmpty())
        assertTrue(ledger.pending().isEmpty())
    }

    private fun fixture(): Pair<NotificationEventLedger, NotificationEventRecord> {
        val storage = object : NotificationEventStorage {
            var state = NotificationEventState()
            override fun read(): NotificationEventState = state
            override fun write(state: NotificationEventState) { this.state = state }
        }
        val ledger = NotificationEventLedger(storage)
        ledger.activateAfter(0)
        ledger.accept(NotificationEventLedgerTest.event(1))
        val claim = ledger.claim(ledger.pending().single().eventId, "main-thread")!!
        ledger.markUncertain(claim)
        return ledger to claim
    }

    private fun proof(record: NotificationEventRecord) = NativeNotificationExternalReceipt(record.eventId,
        record.payloadSha256, "main-thread", "turn-1")
}
