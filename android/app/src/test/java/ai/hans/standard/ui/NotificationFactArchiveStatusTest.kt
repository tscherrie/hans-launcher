package ai.hans.standard.ui

import ai.hans.standard.notifications.NotificationTriageQueueHealth
import ai.hans.standard.phone.notifications.facts.*
import org.junit.Assert.*
import org.junit.Test

class NotificationFactArchiveStatusTest {
    @Test fun unknownStorageDoesNotClaimEmptyHealthyArchive() {
        val status = NotificationFactArchiveStatus.from(null, NotificationTriageQueueHealth.Unavailable)
        assertNull(status.available)
        assertNull(status.factCount)
        assertNull(status.pendingFacts)
        assertTrue(status.summary.contains("unbekannt"))
    }
    @Test fun actualCountsAndCapacityWarningsAreProjectedWithoutContent() {
        val status = NotificationFactArchiveStatus.from(
            NotificationFactArchiveHealth(true, null, 7, 4096, 0, true, NotificationFactCapacity()),
            NotificationTriageQueueHealth.Available(9, 3, 11),
        )
        assertEquals(7, status.factCount)
        assertEquals(3, status.pendingFacts)
        assertEquals(11L, status.capacityDrops)
        assertTrue(status.summary.contains("Kapazitätsgrenze"))
        assertFalse(status.summary.contains("sourceRef"))
    }
    @Test fun unreadableArchiveAndReadableQueueStayIndependent() {
        val status = NotificationFactArchiveStatus.from(
            NotificationFactArchiveHealth(false, NotificationFactUnavailableReason.UNINITIALIZED_OR_MISSING,
                null, null, null, false, NotificationFactCapacity()),
            NotificationTriageQueueHealth.Available(0, 2),
        )
        assertEquals(false, status.available)
        assertNull(status.factCount)
        assertEquals(2, status.pendingFacts)
        assertTrue(status.summary.contains("nicht verfügbar"))
        assertFalse(status.summary.contains("keine Fakten"))
    }
    @Test fun newerRefreshAndLifecycleStopInvalidateOldResults() {
        val guard = NotificationFactStatusGeneration()
        val first = guard.begin()
        val second = guard.begin()
        assertFalse(guard.accepts(first))
        assertTrue(guard.accepts(second))
        guard.invalidate()
        assertFalse(guard.accepts(second))
    }
    @Test fun disclosureSeparatesSelectedFactsFromRawRetentionAndNativeMemory() {
        val text = NotificationFactArchiveStatus.DISCLOSURE
        assertTrue(text.contains("100.000"))
        assertTrue(text.contains("128 MiB"))
        assertTrue(text.contains("sieben Tage"))
        assertTrue(text.contains("ohne automatisches Alterslöschen"))
        assertTrue(text.contains("nicht Teil des Hans-Backups"))
        assertTrue(text.contains("nicht das native Codex-Gedächtnis"))
    }
}
