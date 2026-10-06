package ai.hans.standard.notifications.hooks

import org.junit.Assert.*
import org.junit.Test

class NotificationEventRetryGateTest {
    @Test fun definitelyUnsentEventDoesNotRetryOnSelfScheduledDrainOrOrdinaryReceiptChatter() {
        val gate = NotificationEventRetryGate()
        assertTrue(gate.mayAttempt("event-2"))
        gate.rejectedBeforeTransport("event-2")
        repeat(20) { assertFalse(gate.mayAttempt("event-2")) }
    }

    @Test fun realAcceptanceOfPreviousNativeEventFreesNextEventWhileMainTurnRemainsBusy() {
        val gate = NotificationEventRetryGate()
        gate.rejectedBeforeTransport("event-2")
        assertFalse(gate.mayAttempt("event-2"))
        gate.runtimeReceiptAccepted()
        assertTrue(gate.mayAttempt("event-2"))
    }

    @Test fun actualCaptureNetworkUnlockOrControlWakePermitsDefinitelyUnsentRetry() {
        val gate = NotificationEventRetryGate()
        gate.rejectedBeforeTransport("event-2")
        gate.externalWake()
        assertTrue(gate.mayAttempt("event-2"))
    }

    @Test fun openingRetryGateNeverMakesAmbiguousClaimReadyAgain() {
        val storage = object : NotificationEventStorage {
            var state = NotificationEventState()
            override fun read() = state
            override fun write(state: NotificationEventState) { this.state = state }
        }
        val ledger = NotificationEventLedger(storage)
        ledger.activateAfter(0)
        ledger.accept(NotificationEventLedgerTest.event(1))
        val claim = ledger.claim(ledger.pending().single().eventId, "main-thread")!!
        ledger.markUncertain(claim)
        val gate = NotificationEventRetryGate()
        gate.runtimeReceiptAccepted()
        gate.externalWake()
        assertTrue(ledger.pending().isEmpty())
        assertEquals(NotificationEventPhase.UNCERTAIN, ledger.unsettled().single().phase)
    }
}
