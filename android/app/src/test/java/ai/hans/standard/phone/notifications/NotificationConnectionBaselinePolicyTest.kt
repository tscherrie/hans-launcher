package ai.hans.standard.phone.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationConnectionBaselinePolicyTest {
    @Test
    fun livePostedCallbackCannotBypassUnfinishedInitialBaseline() {
        val store = MemoryBaselineStore()
        val policy = NotificationConnectionBaselinePolicy(store)
        val stored = NotificationWriteResult.Stored(1, NotificationEventKind.POSTED)

        assertTrue(!policy.shouldQueueLiveWrite(stored))
        policy.acceptSnapshot(emptyList(), { _, _ -> error("no writes") }, { _, _ -> error("no queue") })
        assertTrue(policy.shouldQueueLiveWrite(stored))
        assertTrue(!policy.shouldQueueLiveWrite(NotificationWriteResult.Duplicate(1)))
        assertTrue(!policy.shouldQueueLiveWrite(NotificationWriteResult.PrunedByRetention))
    }

    @Test
    fun threeHistoricalActiveNotificationsCreateInboxBaselineWithoutTriageJobs() {
        val store = MemoryBaselineStore()
        val policy = NotificationConnectionBaselinePolicy(store)
        var sequence = 0L
        val queued = mutableListOf<Long>()
        val triageFlags = mutableListOf<Boolean>()

        val result = policy.acceptSnapshot(
            signals = listOf(signal("one"), signal("two"), signal("three")),
            inboxWrite = { _, queueForRestrictedTriage ->
                triageFlags += queueForRestrictedTriage
                sequence += 1
                NotificationWriteResult.Stored(sequence, NotificationEventKind.POSTED)
            },
            enqueueStored = { _, stored -> queued += stored.sequence },
        )

        assertEquals(3, result.storedCount)
        assertEquals(0, result.triageEligibleCount)
        assertTrue(result.baselineEstablished)
        assertTrue(queued.isEmpty())
        assertEquals(listOf(false, false, false), triageFlags)
    }

    @Test
    fun reconnectIgnoresUnchangedDuplicatesButQueuesOneTrulyNewNotification() {
        val store = MemoryBaselineStore(established = true)
        val policy = NotificationConnectionBaselinePolicy(store)
        val queued = mutableListOf<String>()

        val result = policy.acceptSnapshot(
            signals = listOf(signal("old-a"), signal("old-b"), signal("new-c")),
            inboxWrite = { item, queueForRestrictedTriage ->
                assertTrue(queueForRestrictedTriage)
                if (item.androidKey == "new-c") {
                    NotificationWriteResult.Stored(9, NotificationEventKind.POSTED)
                } else {
                    NotificationWriteResult.Duplicate(8)
                }
            },
            enqueueStored = { item, _ -> queued += item.androidKey },
        )

        assertEquals(1, result.storedCount)
        assertEquals(1, result.triageEligibleCount)
        assertEquals(listOf("new-c"), queued)
    }

    private fun signal(key: String): NotificationSignal.Upsert = NotificationSignal.Upsert(
        snapshot = NotificationSnapshot(
            packageName = "com.example.chat",
            androidKey = key,
            postTimeEpochMillis = 1,
            notificationWhenEpochMillis = 1,
            title = "Title",
            text = "Text",
            subtext = "",
            category = "message",
            channelId = "messages",
            ongoing = false,
            clearable = true,
            actions = emptyList(),
        ),
        observedAtEpochMillis = 1,
    )

    private class MemoryBaselineStore(
        var established: Boolean = false,
    ) : NotificationConnectionBaselineStore {
        override fun isEstablished(): Boolean = established
        override fun markEstablished(): Boolean {
            established = true
            return true
        }
    }
}
