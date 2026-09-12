package ai.hans.standard.integration

import ai.hans.standard.notifications.NotificationUrgency
import ai.hans.standard.notifications.UserFacingNotificationDelivery
import ai.hans.standard.notifications.UserFacingNotificationSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatedNotificationSpeechSuppressionTest {
    @Test
    fun mutePermanentlySkipsPendingSpeechWithoutClaimingPlaybackOrRemovingContext() {
        val storage = SpeechStorage()
        val center = center(storage, now = { 100L })
        assertTrue(center.accept(delivery("muted", 90L)))
        val item = center.pendingSpeech().single()
        assertTrue(center.reserveContext(setOf(item.id), "reserved-message"))

        assertTrue(center.suppressSpeechThrough(100L))

        assertTrue(center.pendingSpeech().isEmpty())
        val retained = center.snapshot().single()
        assertEquals(item.summary, retained.summary)
        assertNull(retained.spokenAtEpochMillis)
        assertTrue(retained.speechSuppressed)
        assertEquals(mapOf("reserved-message" to setOf(item.id)), center.contextReservations())
        assertTrue(center.releaseContextReservation(setOf(item.id), "reserved-message"))
        assertEquals(listOf(item.id), center.pendingContext().map { it.id })

        val reopened = center(storage, now = { 200L })
        assertTrue(reopened.pendingSpeech().isEmpty())
        assertEquals(listOf(item.id), reopened.pendingContext().map { it.id })
        assertNull(reopened.snapshot().single().spokenAtEpochMillis)
    }

    @Test
    fun delayedTriageAndStagedActivationCannotReplayMutedArrivalsButNewArrivalCanSpeak() {
        var now = 100L
        val storage = SpeechStorage()
        val center = center(storage, now = { now })
        val staged = delivery("already-staged", 90L)
        assertTrue(center.stage(staged))
        assertTrue(center.suppressSpeechThrough(100L))
        now = 200L

        assertTrue(center.activate(staged.idempotencyKey))
        assertTrue(center.accept(delivery("late-triage", 95L)))
        assertTrue(center.accept(delivery("boundary", 100L)))
        assertTrue(center.pendingSpeech().isEmpty())
        // Duplicate stage/activation does not reset an established speech-only suppression.
        assertTrue(center.accept(staged))
        assertTrue(center.pendingSpeech().isEmpty())

        assertTrue(center.accept(delivery("fresh", 200L)))
        assertEquals(listOf("fresh"), center.pendingSpeech().map { it.summary })
        assertEquals(4, center.snapshot().size)
        assertEquals(4, center.pendingContext().size)
    }

    @Test
    fun failedSuppressionWriteStaysSpeechOnlyAndFreshSessionFencesUnpersistedBacklog() {
        val storage = SpeechStorage()
        val center = center(storage, now = { 100L })
        assertTrue(center.accept(delivery("not-persisted", 90L)))
        storage.failWrites = true

        assertFalse(center.suppressSpeechThrough(100L))
        assertFalse(storage.items.single().speechSuppressed)
        assertTrue(center.pendingSpeech().isEmpty())
        assertEquals(1, center.snapshot().size)
        assertEquals(1, center.pendingContext().size)
        assertNull(center.snapshot().single().spokenAtEpochMillis)

        // Same setting as the production Context constructor. Startup must be fail-closed even
        // when both the prior mute write and its own best-effort flag write are unavailable.
        val reopened = center(storage, now = { 200L }, initialCutoff = 200L)
        assertTrue(reopened.pendingSpeech().isEmpty())
        assertEquals(1, reopened.snapshot().size)
        storage.failWrites = false
        assertTrue(reopened.suppressSpeechThrough(200L))
        assertTrue(storage.items.single().speechSuppressed)
    }

    @Test
    fun writeThatDoesNotPersistCannotClaimDurableSuppression() {
        val storage = SpeechStorage()
        val center = center(storage, now = { 100L })
        assertTrue(center.accept(delivery("dropped-write", 90L)))
        storage.dropWrites = true

        assertFalse(center.suppressSpeechThrough(100L))
        assertTrue(center.pendingSpeech().isEmpty())
        assertEquals(1, center.pendingContext().size)
    }

    @Test
    fun freshSessionSkipsLegacyMissingReceiptAndAnyPriorArrivalWithoutDeletingThem() {
        val storage = SpeechStorage()
        val previous = center(storage, now = { 100L })
        assertTrue(previous.accept(delivery("legacy", null)))
        assertTrue(previous.accept(delivery("prior-process", 90L)))
        var now = 200L
        val reopened = center(storage, now = { now }, initialCutoff = now)

        assertTrue(reopened.pendingSpeech().isEmpty())
        assertTrue(reopened.snapshot().all { it.speechSuppressed })
        assertTrue(reopened.snapshot().all { it.spokenAtEpochMillis == null })
        assertEquals(2, reopened.pendingContext().size)
        now = 201L
        assertTrue(reopened.accept(delivery("new-process", now)))
        assertEquals(listOf("new-process"), reopened.pendingSpeech().map { it.summary })
    }

    @Test
    fun futureReceiptNeverBecomesPlayableWhenTheClockCatchesUp() {
        var now = 100L
        val storage = SpeechStorage()
        val center = center(storage, now = { now }, initialCutoff = 90L)
        assertTrue(center.accept(delivery("future-clock", 150L)))
        assertTrue(center.pendingSpeech().isEmpty())
        assertTrue(storage.items.single().speechSuppressed)

        now = 200L
        assertTrue(center.pendingSpeech().isEmpty())
        val reopened = center(storage, now = { now })
        assertTrue(reopened.pendingSpeech().isEmpty())
        assertTrue(reopened.accept(delivery("current-clock", now)))
        assertEquals(listOf("current-clock"), reopened.pendingSpeech().map { it.summary })
    }

    @Test
    fun olderRepeatedCutoffNeverReopensSuppressedReceiptTimeWindow() {
        var now = 200L
        val center = center(SpeechStorage(), now = { now })
        assertTrue(center.suppressSpeechThrough(200L))
        now = 100L
        assertTrue(center.suppressSpeechThrough(100L))
        now = 300L
        assertTrue(center.accept(delivery("clock-rollback", 150L)))
        assertTrue(center.pendingSpeech().isEmpty())
    }

    @Test
    fun suppressedUnspokenHistoryDoesNotExhaustAllFutureAnnouncementSlots() {
        var now = 100L
        val storage = SpeechStorage()
        val center = center(storage, now = { now })
        repeat(100) { assertTrue(center.accept(delivery("muted-$it", 90L))) }
        assertTrue(center.suppressSpeechThrough(now))
        now = 101L

        assertTrue(center.accept(delivery("fresh-important", now)))
        assertEquals(100, center.snapshot().size)
        assertEquals(listOf("fresh-important"), center.pendingSpeech().map { it.summary })
        assertTrue(center.snapshot().all { it.spokenAtEpochMillis == null })
    }

    private fun center(
        storage: SpeechStorage,
        now: () -> Long,
        initialCutoff: Long? = null,
    ) = ValidatedNotificationAnnouncementCenter(
        storage = storage,
        privacyGenerationStore = SpeechGenerationStore(),
        clock = now,
        initialSpeechSuppressionCutoffEpochMillis = initialCutoff,
    )

    private fun delivery(key: String, receivedAt: Long?) = UserFacingNotificationDelivery(
        receiptId = key,
        idempotencyKey = key,
        suggestion = UserFacingNotificationSuggestion(key, NotificationUrgency.HIGH),
        sourceReceivedAtEpochMillis = receivedAt,
    )

    private class SpeechStorage : ValidatedAnnouncementStorage {
        var items = emptyList<ValidatedNotificationAnnouncement>()
        var failWrites = false
        var dropWrites = false
        override fun read(): List<ValidatedNotificationAnnouncement> = items
        override fun write(items: List<ValidatedNotificationAnnouncement>): Boolean {
            if (failWrites) return false
            if (!dropWrites) this.items = items
            return true
        }
    }

    private class SpeechGenerationStore : NotificationPrivacyGenerationStore {
        private var generation = 1L
        override fun current(): Long = generation
        override fun advance(): Long = ++generation
    }
}
