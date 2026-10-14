package ai.hans.standard.fastphone.routines

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneRoutineAuthorityGateTest {
    @Test fun revocationClosesBeforeQueuedMutationAndOnlySuccessfulCommitReopens() {
        val gate = PhoneRoutineAuthorityGate()
        assertFalse(gate.blocked)
        val epoch = gate.beginChange()
        assertTrue(gate.blocked)
        gate.finishChange(epoch, false)
        assertTrue(gate.blocked)
        gate.finishChange(epoch, true)
        assertFalse(gate.blocked)
    }

    @Test fun earlierQueuedCompletionCannotReopenNewerForgetOrDisable() {
        val gate = PhoneRoutineAuthorityGate()
        val old = gate.beginChange()
        val current = gate.beginChange()
        gate.finishChange(old, true)
        assertTrue(gate.blocked)
        gate.finishChange(current, true)
        assertFalse(gate.blocked)
    }

    @Test fun aCompletedRevocationCycleStillInvalidatesAnAlreadyAssembledContext() {
        val gate = PhoneRoutineAuthorityGate()
        val before = gate.capture()
        assertTrue(gate.permits(before))
        val change = gate.beginChange()
        assertFalse(gate.permits(before))
        gate.finishChange(change, true)
        assertFalse(gate.blocked)
        assertFalse(gate.permits(before))
        assertTrue(gate.permits(gate.capture()))
    }
}
