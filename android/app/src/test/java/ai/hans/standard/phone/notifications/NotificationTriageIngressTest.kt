package ai.hans.standard.phone.notifications

import ai.hans.standard.notifications.HansNotificationExclusionPolicy
import ai.hans.standard.notifications.NotificationIngressResult
import ai.hans.standard.notifications.NotificationTriageQueue
import ai.hans.standard.notifications.NotificationTriageQueueState
import ai.hans.standard.notifications.NotificationTriageStorage
import ai.hans.standard.notifications.NotificationDismissalReason
import ai.hans.standard.notifications.NotificationUrgency
import ai.hans.standard.notifications.RestrictedTriageDecision
import ai.hans.standard.notifications.TriageCompletionResult
import ai.hans.standard.notifications.UserFacingNotificationSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTriageIngressTest {
    @Test
    fun onlySuccessfullyStoredUpsertCrossesIntoRestrictedQueue() {
        val queue = queue()
        val ingress = NotificationTriageIngress(queue)
        val upsert = upsert("com.example.chat", "key-1")

        val result = ingress.afterInboxWrite(
            upsert,
            NotificationWriteResult.Stored(7, NotificationEventKind.POSTED),
        )

        assertTrue(result is NotificationIngressResult.Queued)
        assertEquals(7L, queue.receipts().single().sourceSequence)
    }

    @Test
    fun removalCancelsQueuedWorkWhileDuplicateAndInconsistentUpsertsDoNotEnterQueue() {
        val queue = queue()
        val ingress = NotificationTriageIngress(queue)
        val upsert = upsert("com.example.chat", "key-2")
        val removed = NotificationSignal.Removed("com.example.chat", "key-2", 200, 1)

        assertNull(ingress.afterInboxWrite(upsert, NotificationWriteResult.Duplicate(null)))
        assertNull(
            ingress.afterInboxWrite(
                upsert,
                NotificationWriteResult.Stored(8, NotificationEventKind.REMOVED),
            ),
        )
        assertEquals(
            NotificationIngressResult.CancelledRemovedNotification(0, false),
            ingress.afterInboxWrite(
                removed,
                NotificationWriteResult.Stored(9, NotificationEventKind.REMOVED),
            ),
        )
        assertTrue(queue.receipts().isEmpty())
    }

    @Test
    fun postedThenRemovedBurstInvalidatesLeaseAndCreatesNoStaleSuggestion() {
        val queue = queue()
        val ingress = NotificationTriageIngress(queue)
        val upsert = upsert("com.example.chat", "burst-key")
        ingress.afterInboxWrite(
            upsert,
            NotificationWriteResult.Stored(11, NotificationEventKind.POSTED),
        )
        val work = requireNotNull(queue.claimNextRestrictedTriage())

        val result = ingress.afterInboxWrite(
            NotificationSignal.Removed("com.example.chat", "burst-key", 300, 1),
            NotificationWriteResult.Stored(12, NotificationEventKind.REMOVED),
        )

        assertEquals(NotificationIngressResult.CancelledRemovedNotification(1, true), result)
        assertEquals(
            TriageCompletionResult.MissingOrExpiredLease,
            queue.completeRestrictedTriage(
                work.receipt.id,
                work.claimToken,
                RestrictedTriageDecision.SuggestUser(
                    UserFacingNotificationSuggestion("stale", NotificationUrgency.HIGH),
                ),
            ),
        )
        assertTrue(queue.userSuggestions().isEmpty())
    }

    @Test
    fun ownHansUpsertIsExcludedAfterSuccessfulInboxWrite() {
        val queue = queue()
        val result = NotificationTriageIngress(queue).afterInboxWrite(
            upsert("ai.hans.standard", "hans-key"),
            NotificationWriteResult.Stored(10, NotificationEventKind.POSTED),
        )

        assertEquals(NotificationIngressResult.ExcludedOwnNotification("ai.hans.standard"), result)
        assertTrue(queue.receipts().isEmpty())
    }

    private fun queue(): NotificationTriageQueue = NotificationTriageQueue(
        storage = MemoryStorage(),
        exclusionPolicy = HansNotificationExclusionPolicy(setOf("ai.hans.standard")),
        clock = { 1_000 },
    )

    private fun upsert(packageName: String, key: String): NotificationSignal.Upsert =
        NotificationSignal.Upsert(
            snapshot = NotificationSnapshot(
                packageName = packageName,
                androidKey = key,
                postTimeEpochMillis = 100,
                notificationWhenEpochMillis = 100,
                title = "Title",
                text = "Text",
                subtext = "",
                category = "message",
                channelId = "messages",
                ongoing = false,
                clearable = true,
                actions = emptyList(),
            ),
            observedAtEpochMillis = 200,
        )

    private class MemoryStorage : NotificationTriageStorage {
        private var state = NotificationTriageQueueState(emptyList())
        override fun read(): NotificationTriageQueueState = state
        override fun write(state: NotificationTriageQueueState) {
            this.state = state
        }
    }
}
