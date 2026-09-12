package ai.hans.standard.media

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTurnLeaseTrackerTest {
    @Test
    fun registrationPersistsOwnershipWithoutDeletingAtSentAcknowledgement() {
        val storage = FakeStorage()
        val tracker = MediaTurnLeaseTracker(storage) { 42L }

        assertTrue(tracker.register("thread-1", "turn-1", setOf("import-1")))

        assertEquals(
            listOf(MediaTurnLease(key("turn-1"), setOf("import-1"), 42L)),
            storage.values,
        )
        assertEquals(storage.values, tracker.snapshotForTest())
    }

    @Test
    fun unrelatedTerminalReceiptCannotReleaseAnotherTurnsMedia() {
        val tracker = MediaTurnLeaseTracker(FakeStorage()) { 42L }
        tracker.register("thread-1", "turn-1", setOf("import-1"))
        val deleted = mutableListOf<String>()

        val released = tracker.releaseTerminalTurns(setOf(key("turn-2"))) { importId ->
            deleted += importId
            true
        }

        assertTrue(released.isEmpty())
        assertTrue(deleted.isEmpty())
        assertEquals(setOf(key("turn-1")), tracker.snapshotForTest().mapTo(mutableSetOf()) { it.key })
    }

    @Test
    fun exactTerminalReceiptDeletesOnceAndForgetsLease() {
        val storage = FakeStorage()
        val tracker = MediaTurnLeaseTracker(storage) { 42L }
        tracker.register("thread-1", "turn-1", setOf("import-1", "import-2"))
        val deleted = mutableListOf<String>()

        val released = tracker.releaseTerminalTurns(setOf(key("turn-1"))) { importId ->
            deleted += importId
            true
        }

        assertEquals(setOf(key("turn-1")), released)
        assertEquals(setOf("import-1", "import-2"), deleted.toSet())
        assertEquals(2, deleted.size)
        assertTrue(storage.values.isEmpty())
        assertTrue(tracker.snapshotForTest().isEmpty())

        tracker.releaseTerminalTurns(setOf(key("turn-1"))) { importId ->
            deleted += importId
            true
        }
        assertEquals(2, deleted.size)
    }

    @Test
    fun terminalKnownBeforeLeaseRegistrationStillReleasesAfterRegistration() {
        val tracker = MediaTurnLeaseTracker(FakeStorage()) { 42L }
        val terminalSnapshot = setOf(key("turn-fast"))

        tracker.register("thread-1", "turn-fast", setOf("import-fast"))
        val released = tracker.releaseTerminalTurns(terminalSnapshot) { true }

        assertEquals(setOf(key("turn-fast")), released)
        assertTrue(tracker.snapshotForTest().isEmpty())
    }

    @Test
    fun sharedImportStaysUntilEveryOwningTurnIsTerminal() {
        val tracker = MediaTurnLeaseTracker(FakeStorage()) { 42L }
        tracker.register("thread-1", "turn-1", setOf("video-import"))
        tracker.register("thread-1", "turn-2", setOf("video-import"))
        val deleted = mutableListOf<String>()

        assertEquals(
            setOf(key("turn-1")),
            tracker.releaseTerminalTurns(setOf(key("turn-1"))) { importId ->
                deleted += importId
                true
            },
        )
        assertTrue(deleted.isEmpty())

        assertEquals(
            setOf(key("turn-2")),
            tracker.releaseTerminalTurns(setOf(key("turn-2"))) { importId ->
                deleted += importId
                true
            },
        )
        assertEquals(listOf("video-import"), deleted)
    }

    @Test
    fun failedDeletionRetainsRecoveryReceiptForRetry() {
        val storage = FakeStorage()
        val tracker = MediaTurnLeaseTracker(storage) { 42L }
        tracker.register("thread-1", "turn-1", setOf("import-1"))

        assertTrue(tracker.releaseTerminalTurns(setOf(key("turn-1"))) { false }.isEmpty())
        assertEquals(setOf(key("turn-1")), storage.values.mapTo(mutableSetOf()) { it.key })

        assertEquals(
            setOf(key("turn-1")),
            tracker.releaseTerminalTurns(setOf(key("turn-1"))) { true },
        )
        assertTrue(storage.values.isEmpty())
    }

    @Test
    fun codecRoundTripsBoundedContentFreeReceiptsAndRejectsUnknownFields() {
        val leases = listOf(
            MediaTurnLease(key("turn-2"), setOf("import-b", "import-a"), 20L),
            MediaTurnLease(key("turn-1"), setOf("import-c"), 10L),
        )

        val encoded = MediaTurnLeaseCodec.encode(leases)
        assertEquals(leases.sortedBy { it.createdAtEpochMillis }, MediaTurnLeaseCodec.decode(encoded))
        assertFalse(encoded.contains("/data/"))

        val expanded = JSONObject(encoded).put("unexpected", true).toString()
        assertThrows(IllegalArgumentException::class.java) {
            MediaTurnLeaseCodec.decode(expanded)
        }
    }

    @Test
    fun sameTurnIdInAnotherThreadCannotReleaseMedia() {
        val tracker = MediaTurnLeaseTracker(FakeStorage()) { 42L }
        tracker.register("thread-1", "turn-shared", setOf("import-1"))

        val released = tracker.releaseTerminalTurns(
            setOf(MediaTurnLeaseKey("thread-2", "turn-shared")),
        ) { true }

        assertTrue(released.isEmpty())
        assertEquals(
            setOf(key("turn-shared")),
            tracker.snapshotForTest().mapTo(mutableSetOf()) { it.key },
        )
    }

    @Test
    fun authoritativeIdleThreadReleasesLeaseRestoredAfterProcessDeath() {
        val restored = MediaTurnLease(key("turn-before-death"), setOf("import-1"), 42L)
        val tracker = MediaTurnLeaseTracker(FakeStorage(listOf(restored))) { 99L }
        val deleted = mutableListOf<String>()

        val released = tracker.releaseTerminalTurns(
            terminalTurns = emptySet(),
            terminalThreadIds = setOf("thread-1"),
        ) { importId ->
            deleted += importId
            true
        }

        assertEquals(setOf(key("turn-before-death")), released)
        assertEquals(listOf("import-1"), deleted)
        assertTrue(tracker.snapshotForTest().isEmpty())
    }

    @Test
    fun activeOrUnrelatedThreadCannotReleaseRestoredLease() {
        val restored = MediaTurnLease(key("turn-before-death"), setOf("import-1"), 42L)
        val tracker = MediaTurnLeaseTracker(FakeStorage(listOf(restored))) { 99L }

        val released = tracker.releaseTerminalTurns(
            terminalTurns = emptySet(),
            terminalThreadIds = setOf("thread-2"),
        ) { true }

        assertTrue(released.isEmpty())
        assertEquals(listOf(restored), tracker.snapshotForTest())
    }

    private fun key(turnId: String) = MediaTurnLeaseKey("thread-1", turnId)

    private class FakeStorage(
        initial: List<MediaTurnLease> = emptyList(),
    ) : MediaTurnLeaseStorage {
        var values = initial
            private set

        override fun read(): List<MediaTurnLease> = values

        override fun write(leases: List<MediaTurnLease>) {
            values = leases
        }
    }
}
