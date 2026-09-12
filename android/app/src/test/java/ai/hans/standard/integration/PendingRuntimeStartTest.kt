package ai.hans.standard.integration

import ai.hans.standard.runtime.AppServerSessionContract
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingRuntimeStartTest {
    @Test
    fun repeatedPreflightFailuresRequireTheirOwnCurrentOperationAndAreDeliveredOnlyOnce() {
        val pending = PendingRuntimeStart()
        assertFalse(pending.acceptStandaloneFailure(1, 0, AppServerSessionContract.STATE_FAILED))
        pending.begin(1)
        assertTrue(pending.acceptStandaloneFailure(1, 0, AppServerSessionContract.STATE_FAILED))
        assertFalse(pending.acceptStandaloneFailure(1, 0, AppServerSessionContract.STATE_FAILED))
        pending.begin(2)
        assertFalse(pending.acceptStandaloneFailure(1, 0, AppServerSessionContract.STATE_FAILED))
        assertTrue(pending.acceptStandaloneFailure(2, 0, AppServerSessionContract.STATE_FAILED))
    }

    @Test
    fun malformedOrNonterminalStandaloneEventsDoNotConsumeThePendingOperation() {
        val pending = PendingRuntimeStart()
        pending.begin(8)
        assertFalse(pending.acceptStandaloneFailure(8, 1, AppServerSessionContract.STATE_FAILED))
        assertFalse(pending.acceptStandaloneFailure(8, 0, AppServerSessionContract.STATE_READY))
        assertFalse(pending.acceptStandaloneFailure(8, 0, AppServerSessionContract.STATE_STARTING))
        assertTrue(pending.acceptStandaloneFailure(8, 0, AppServerSessionContract.STATE_FAILED))
    }

    @Test
    fun lateFailureCannotUndoASuccessfulStartOrConsumeTheNextStart() {
        val pending = PendingRuntimeStart()
        pending.begin(8)
        assertTrue(pending.complete(8))
        assertFalse(pending.acceptStandaloneFailure(8, 0, AppServerSessionContract.STATE_FAILED))
        pending.begin(9)
        assertFalse(pending.complete(8))
        assertTrue(pending.acceptStandaloneFailure(9, 0, AppServerSessionContract.STATE_FAILED))
    }
}
