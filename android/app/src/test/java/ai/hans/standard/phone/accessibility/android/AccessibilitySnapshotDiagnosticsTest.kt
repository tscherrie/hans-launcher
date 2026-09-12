package ai.hans.standard.phone.accessibility.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilitySnapshotDiagnosticsTest {
    @Test
    fun detailCodesAreAClosedPrivacySafeAllowlist() {
        assertEquals(
            setOf(
                "display_unavailable",
                "display_invalid",
                "display_context_failed",
                "no_active_root",
                "invalid_window",
                "display_bounds_failed",
                "projection_root_read_failed",
                "projection_empty",
                "projection_failed",
                "snapshot_factory_failed",
                "snapshot_publication_failed",
                "snapshot_id_exhausted",
                "capture_failed",
                "snapshot_failure_unclassified",
            ),
            AccessibilitySnapshotFailure.entries.mapTo(mutableSetOf()) { it.detailCode },
        )
        assertTrue(
            AccessibilitySnapshotFailure.entries.all {
                it.detailCode.matches(Regex("[a-z][a-z0-9_]{2,79}"))
            },
        )
    }

    @Test
    fun successfulPublicationCanClearAStaleFailure() {
        val diagnostics = AccessibilitySnapshotDiagnostics()

        diagnostics.record(AccessibilitySnapshotFailure.NO_ACTIVE_ROOT)
        assertEquals(AccessibilitySnapshotFailure.NO_ACTIVE_ROOT, diagnostics.latest())

        diagnostics.record(AccessibilitySnapshotFailure.SNAPSHOT_FACTORY_FAILED)
        assertEquals(AccessibilitySnapshotFailure.SNAPSHOT_FACTORY_FAILED, diagnostics.latest())

        diagnostics.clear()
        assertNull(diagnostics.latest())
    }
}
