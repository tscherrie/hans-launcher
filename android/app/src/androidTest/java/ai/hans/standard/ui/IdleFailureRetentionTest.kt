package ai.hans.standard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class IdleFailureRetentionTest {
    @Test fun diagnosticFailureIsSuppressedWithoutReplacingPrimary() {
        val primary = AssertionError("No public real-window frame-metric evidence")
        val secondary = IllegalStateException("diagnostic unavailable")
        retainIdleFailure(primary) { throw secondary }
        assertEquals(1, primary.suppressed.size)
        assertSame(secondary, primary.suppressed.single())
    }

    @Test fun successfulDiagnosticLeavesPrimaryUntouched() {
        val primary = AssertionError("original")
        var ran = false
        retainIdleFailure(primary) { ran = true }
        assertEquals(true, ran)
        assertEquals(0, primary.suppressed.size)
    }

    @Test fun cleanupFailureWithoutPrimaryCannotBecomePass() {
        val secondary = IllegalStateException("receipt write failed")
        try {
            retainIdleFailure(null) { throw secondary }
            fail("failure must propagate")
        } catch (actual: IllegalStateException) {
            assertSame(secondary, actual)
        }
    }

    @Test fun repeatedPrimaryDoesNotSelfSuppress() {
        val primary = AssertionError("original")
        retainIdleFailure(primary) { throw primary }
        assertEquals(0, primary.suppressed.size)
    }
}
