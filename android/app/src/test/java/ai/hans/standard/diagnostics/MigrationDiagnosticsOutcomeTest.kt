package ai.hans.standard.diagnostics

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationDiagnosticsOutcomeTest {
    @Test
    fun onlyTypedClosedReasonsMayLeaveTheCollector() {
        val codes = MigrationDiagnosticsBlocker.entries.map { it.resultData }
        assertEquals(codes.size, codes.distinct().size)
        MigrationDiagnosticsBlocker.entries.forEach { blocker ->
            assertTrue(blocker.resultData.matches(Regex("hans\\.migration-diagnostics\\.v1:[a-z_]+")))
            val outcome = MigrationDiagnosticsOutcome.collect {
                throw MigrationDiagnosticsBlocked(blocker)
            }
            assertEquals(MigrationDiagnosticsOutcome.Blocked(blocker), outcome)
            assertFalse(outcome is MigrationDiagnosticsOutcome.Collected)
        }
    }

    @Test
    fun arbitraryMessagesAndNestedCausesStayGenericAndCannotImpersonateAReason() {
        listOf(
            IOException("private path and credential content"),
            IllegalArgumentException(MigrationDiagnosticsBlocker.SETUP_INCOMPLETE.resultData),
            RuntimeException(MigrationDiagnosticsBlocked(MigrationDiagnosticsBlocker.SETUP_INCOMPLETE)),
        ).forEach { failure ->
            assertEquals(MigrationDiagnosticsOutcome.Failed, MigrationDiagnosticsOutcome.collect { throw failure })
        }
    }

    @Test
    fun successfulReceiptsAreUnchanged() {
        val receipt = "{\"schema\":\"fixture\"}"
        assertEquals(MigrationDiagnosticsOutcome.Collected(receipt), MigrationDiagnosticsOutcome.collect { receipt })
    }
}
