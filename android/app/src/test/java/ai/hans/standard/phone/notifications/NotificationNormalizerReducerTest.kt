package ai.hans.standard.phone.notifications

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationNormalizerReducerTest {
    @Test
    fun duplicateUpdateRemoveAndRepostHaveExplicitSemantics() {
        val postedSignal = signal(text = "Hallo", observedAt = 10)
        val posted = NotificationReducer.reduce(previous = null, signal = postedSignal)
            as NotificationReduction.Append
        assertEquals(NotificationEventKind.POSTED, posted.event.kind)

        val active = StoredNotificationState(
            snapshot = posted.event.snapshot,
            active = true,
            fingerprint = posted.fingerprint,
            lastSequence = 1,
        )
        val duplicate = NotificationReducer.reduce(active, postedSignal)
        assertTrue(duplicate is NotificationReduction.IgnoreDuplicate)

        val updatedSignal = signal(text = "Hallo, aktualisiert", observedAt = 20)
        val updated = NotificationReducer.reduce(active, updatedSignal)
            as NotificationReduction.Append
        assertEquals(NotificationEventKind.UPDATED, updated.event.kind)
        assertNotEquals(posted.fingerprint, updated.fingerprint)

        val updatedState = StoredNotificationState(
            snapshot = updated.event.snapshot,
            active = true,
            fingerprint = updated.fingerprint,
            lastSequence = 2,
        )
        val removed = NotificationReducer.reduce(
            updatedState,
            NotificationNormalizer.removed("example.app", "key-1", 30, reason = 2),
        ) as NotificationReduction.Append
        assertEquals(NotificationEventKind.REMOVED, removed.event.kind)
        assertEquals("Hallo, aktualisiert", removed.event.snapshot.text)

        val inactive = StoredNotificationState(
            snapshot = removed.event.snapshot,
            active = false,
            fingerprint = removed.fingerprint,
            lastSequence = 3,
        )
        assertTrue(
            NotificationReducer.reduce(
                inactive,
                NotificationNormalizer.removed("example.app", "key-1", 40, reason = 2),
            ) is NotificationReduction.IgnoreDuplicate,
        )

        val reposted = NotificationReducer.reduce(inactive, updatedSignal)
            as NotificationReduction.Append
        assertEquals(NotificationEventKind.POSTED, reposted.event.kind)
    }

    @Test
    fun timestampOnlyRedeliveryIsDuplicate() {
        val firstSignal = NotificationNormalizer.upsert(
            raw = raw().copy(
                postTimeEpochMillis = 1,
                notificationWhenEpochMillis = 1,
            ),
            observedAtEpochMillis = 1,
        )
        val posted = NotificationReducer.reduce(null, firstSignal)
            as NotificationReduction.Append
        val active = StoredNotificationState(
            snapshot = posted.event.snapshot,
            active = true,
            fingerprint = posted.fingerprint,
            lastSequence = 7,
        )
        val refreshed = NotificationNormalizer.upsert(
            raw = raw().copy(
                postTimeEpochMillis = 2,
                notificationWhenEpochMillis = 2,
            ),
            observedAtEpochMillis = 2,
        )

        assertEquals(
            NotificationReduction.IgnoreDuplicate(lastSequence = 7),
            NotificationReducer.reduce(active, refreshed),
        )
    }

    @Test
    fun unknownRemovalIsCapturedOnce() {
        val removed = NotificationReducer.reduce(
            previous = null,
            signal = NotificationNormalizer.removed("example.app", "unknown-key", 50, 4),
        ) as NotificationReduction.Append

        assertEquals(NotificationEventKind.REMOVED, removed.event.kind)
        assertFalse(removed.active)
        assertEquals("unknown-key", removed.event.snapshot.androidKey)
    }

    @Test
    fun oversizedBidiAndControlInputIsSafeAndUtf8Bounded() {
        val hostile = "\u202Ehidden\u202C\u0000\u0007  " + "🧪".repeat(2_000)
        val normalized = NotificationNormalizer.upsert(
            raw = raw(text = hostile, title = "A\n\tB\u2066C\u2069"),
            observedAtEpochMillis = 1,
        ).snapshot

        assertEquals("A BC", normalized.title)
        assertFalse(normalized.text.contains('\u202E'))
        assertFalse(normalized.text.contains('\u202C'))
        assertFalse(normalized.text.contains('\u0000'))
        assertTrue(
            normalized.text.toByteArray(StandardCharsets.UTF_8).size <=
                NotificationLimits.TEXT_UTF8_BYTES,
        )
        assertFalse(normalized.text.last().isHighSurrogate())
    }

    @Test
    fun actionsAndRemoteInputsAreBoundedAndSorted() {
        val rawInputs = (0 until 20).map { index ->
            RawNotificationRemoteInput(
                resultKey = "result-$index",
                label = "Reply",
                choices = (0 until 30).map { "Choice $it" },
                allowFreeFormInput = true,
                allowedDataTypes = listOf("image/png", "text/plain", "image/png"),
                editChoicesBeforeSending = 1,
            )
        }
        val actions = (0 until 20).map {
            RawNotificationAction(
                title = "Action $it",
                semanticAction = it,
                isContextual = false,
                allowGeneratedReplies = true,
                authenticationRequired = false,
                hasActionIntent = true,
                remoteInputs = rawInputs,
            )
        }

        val snapshot = NotificationNormalizer.upsert(
            raw = raw(actions = actions),
            observedAtEpochMillis = 1,
        ).snapshot

        assertEquals(NotificationLimits.MAX_ACTIONS, snapshot.actions.size)
        assertEquals(
            NotificationLimits.MAX_REMOTE_INPUTS_PER_ACTION,
            snapshot.actions.first().remoteInputs.size,
        )
        assertEquals(
            NotificationLimits.MAX_CHOICES_PER_REMOTE_INPUT,
            snapshot.actions.first().remoteInputs.first().choices.size,
        )
        assertEquals(
            listOf("image/png", "text/plain"),
            snapshot.actions.first().remoteInputs.first().allowedDataTypes,
        )
    }

    @Test
    fun digestUsesSequenceOrderRatherThanNotificationTimestamp() {
        val events = listOf(
            event(sequence = 3, postTime = 1, text = "third"),
            event(sequence = 1, postTime = 3, text = "first"),
            event(sequence = 2, postTime = 2, text = "second"),
        )

        val digest = NotificationDigestBuilder.build(
            events = events,
            afterSequenceExclusive = 0,
            maxEvents = 10,
            maxUtf8Bytes = 2_048,
            sourceHasMore = false,
        )

        assertTrue(digest.text.indexOf("#1") < digest.text.indexOf("#2"))
        assertTrue(digest.text.indexOf("#2") < digest.text.indexOf("#3"))
        assertEquals(3L, digest.nextAfterSequenceExclusive)
        assertFalse(digest.hasMore)
    }

    @Test
    fun digestNeverExceedsUtf8BudgetAndKeepsCursorResumable() {
        val events = listOf(
            event(sequence = 1, postTime = 1, text = "🧪".repeat(200)),
            event(sequence = 2, postTime = 2, text = "next"),
        )

        val digest = NotificationDigestBuilder.build(
            events = events,
            afterSequenceExclusive = 0,
            maxEvents = 10,
            maxUtf8Bytes = 96,
            sourceHasMore = false,
        )

        assertTrue(digest.utf8Bytes <= 96)
        assertEquals(digest.utf8Bytes, digest.text.toByteArray(StandardCharsets.UTF_8).size)
        assertEquals(1, digest.eventCount)
        assertEquals(1L, digest.nextAfterSequenceExclusive)
        assertTrue(digest.hasMore)

        val zeroBudget = NotificationDigestBuilder.build(
            events = events,
            afterSequenceExclusive = 0,
            maxEvents = 10,
            maxUtf8Bytes = 0,
            sourceHasMore = false,
        )
        assertEquals("", zeroBudget.text)
        assertEquals(0, zeroBudget.utf8Bytes)
        assertEquals(0, zeroBudget.eventCount)
        assertEquals(0L, zeroBudget.nextAfterSequenceExclusive)
        assertTrue(zeroBudget.hasMore)
    }

    private fun signal(text: String, observedAt: Long): NotificationSignal.Upsert =
        NotificationNormalizer.upsert(
            raw = raw(text = text),
            observedAtEpochMillis = observedAt,
        )

    private fun raw(
        text: CharSequence? = "Body",
        title: CharSequence? = "Title",
        actions: List<RawNotificationAction> = emptyList(),
    ): RawNotificationSnapshot = RawNotificationSnapshot(
        packageName = "example.app",
        androidKey = "key-1",
        postTimeEpochMillis = 1,
        notificationWhenEpochMillis = 1,
        title = title,
        text = text,
        subtext = "Subtext",
        category = "message",
        channelId = "messages",
        ongoing = false,
        clearable = true,
        actions = actions,
    )

    private fun event(
        sequence: Long,
        postTime: Long,
        text: String,
    ): NotificationInboxEvent = NotificationInboxEvent(
        sequence = sequence,
        kind = NotificationEventKind.POSTED,
        observedAtEpochMillis = postTime,
        removalReason = null,
        snapshot = NotificationNormalizer.upsert(
            raw = raw(text = text),
            observedAtEpochMillis = postTime,
        ).snapshot.copy(postTimeEpochMillis = postTime),
    )
}
