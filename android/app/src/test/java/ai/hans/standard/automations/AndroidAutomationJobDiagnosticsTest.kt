package ai.hans.standard.automations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAutomationJobDiagnosticsTest {
    @Test
    fun stopDiagnosticIsContentFreeSortedAndStrictlyBounded() {
        var recorded: AutomationJobStopDiagnostic? = null
        val diagnostics = AndroidAutomationJobDiagnostics(
            platform = object : AutomationJobDiagnosticsPlatform {
                override fun pendingReasons(jobId: Int): IntArray =
                    (29 downTo 0).toList().plus(3).toIntArray()

                override fun pendingHistory(jobId: Int): List<AutomationPendingReasonHistory> =
                    listOf(AutomationPendingReasonHistory(-1, listOf(99))) +
                        (0L..9L).map { timestamp ->
                            AutomationPendingReasonHistory(
                                timestamp,
                                listOf(4, 2, 4, 1),
                            )
                        }
            },
            sink = AutomationJobDiagnosticSink { recorded = it },
        )

        diagnostics.recordStop(jobId = 42, stopReason = 12)

        val value = checkNotNull(recorded)
        assertEquals(42, value.jobId)
        assertEquals(12, value.stopReason)
        assertEquals((0..23).toList(), value.pendingReasons)
        assertEquals((2L..9L).toList(), value.pendingHistory.map { it.timestampMillis })
        assertTrue(value.pendingHistory.all { it.reasons == listOf(1, 2, 4) })
    }

    @Test
    fun pendingReasonHistoryUsesOnlyTheApi36PublicSurface() {
        assertFalse(AndroidAutomationJobDiagnostics.supportsPendingReasonHistory(31))
        assertFalse(AndroidAutomationJobDiagnostics.supportsPendingReasonHistory(35))
        assertTrue(AndroidAutomationJobDiagnostics.supportsPendingReasonHistory(36))
    }
}
