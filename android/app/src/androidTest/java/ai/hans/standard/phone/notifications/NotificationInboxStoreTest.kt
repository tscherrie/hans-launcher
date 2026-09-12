package ai.hans.standard.phone.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationInboxStoreTest {
    private lateinit var context: Context
    private lateinit var databaseName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "notification-inbox-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun sqliteRoundTripDeduplicatesUpdatesPagesRetainsAndSurvivesReopen() {
        var store = NotificationInboxStore(
            context = context,
            retentionLimit = 3,
            databaseName = databaseName,
        )
        val first = store.accept(signal(key = "key-1", text = "first", observedAt = 1))
            as NotificationWriteResult.Stored
        assertEquals(NotificationEventKind.POSTED, first.kind)
        assertTrue(
            store.accept(signal(key = "key-1", text = "first", observedAt = 2))
                is NotificationWriteResult.Duplicate,
        )

        val update = store.accept(signal(key = "key-1", text = "updated", observedAt = 3))
            as NotificationWriteResult.Stored
        assertEquals(NotificationEventKind.UPDATED, update.kind)
        val removal = store.accept(
            NotificationNormalizer.removed("example.app", "key-1", 4, reason = 2),
        ) as NotificationWriteResult.Stored
        assertEquals(NotificationEventKind.REMOVED, removal.kind)
        val second = store.accept(signal(key = "key-2", text = "second", observedAt = 5))
            as NotificationWriteResult.Stored

        val pageOne = store.queryPage(limit = 2)
        assertEquals(2, pageOne.events.size)
        assertTrue(pageOne.hasMore)
        assertTrue(pageOne.events[0].sequence < pageOne.events[1].sequence)
        assertEquals("updated", pageOne.events.first().snapshot.text)

        val pageTwo = store.queryPage(
            afterSequenceExclusive = pageOne.nextAfterSequenceExclusive,
            limit = 2,
        )
        assertEquals(1, pageTwo.events.size)
        assertFalse(pageTwo.hasMore)
        assertEquals(second.sequence, pageTwo.events.single().sequence)

        val latestBeforeClose = pageTwo.nextAfterSequenceExclusive
        store.close()
        store = NotificationInboxStore(
            context = context,
            retentionLimit = 3,
            databaseName = databaseName,
        )
        assertEquals(3, store.queryPage(limit = 10).events.size)
        val afterReopen = store.accept(signal(key = "key-3", text = "third", observedAt = 6))
            as NotificationWriteResult.Stored
        assertTrue(afterReopen.sequence > latestBeforeClose)
        assertEquals(3, store.queryPage(limit = 10).events.size)
        store.close()
    }

    @Test
    fun actionAndRemoteInputMetadataRoundTripsWithoutPendingIntent() {
        val now = System.currentTimeMillis()
        val store = NotificationInboxStore(
            context = context,
            databaseName = databaseName,
        )
        val action = RawNotificationAction(
            title = "Reply",
            semanticAction = 1,
            isContextual = true,
            allowGeneratedReplies = true,
            authenticationRequired = false,
            hasActionIntent = true,
            remoteInputs = listOf(
                RawNotificationRemoteInput(
                    resultKey = "reply",
                    label = "Antwort",
                    choices = listOf("Ja", "Nein"),
                    allowFreeFormInput = true,
                    allowedDataTypes = listOf("text/plain"),
                    editChoicesBeforeSending = 1,
                ),
            ),
        )
        store.accept(
            signal(
                key = "reply-key",
                text = "Question",
                observedAt = now,
                actions = listOf(action),
            ),
        )

        val restored = store.queryPage(limit = 1).events.single().snapshot.actions.single()
        assertEquals("Reply", restored.title)
        assertTrue(restored.hasActionIntent)
        assertEquals("reply", restored.remoteInputs.single().resultKey)
        assertEquals(listOf("Ja", "Nein"), restored.remoteInputs.single().choices)
        store.close()
    }

    private fun signal(
        key: String,
        text: String,
        observedAt: Long,
        actions: List<RawNotificationAction> = emptyList(),
    ): NotificationSignal.Upsert = NotificationNormalizer.upsert(
        raw = RawNotificationSnapshot(
            packageName = "example.app",
            androidKey = key,
            postTimeEpochMillis = observedAt,
            notificationWhenEpochMillis = observedAt,
            title = "Title",
            text = text,
            subtext = "Subtext",
            category = "message",
            channelId = "messages",
            ongoing = false,
            clearable = true,
            actions = actions,
        ),
        observedAtEpochMillis = observedAt,
    )
}
