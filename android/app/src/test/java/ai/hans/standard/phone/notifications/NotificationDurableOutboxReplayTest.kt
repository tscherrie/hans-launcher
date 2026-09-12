package ai.hans.standard.phone.notifications

import ai.hans.standard.notifications.HansNotificationExclusionPolicy
import ai.hans.standard.notifications.NotificationDeliveryState
import ai.hans.standard.notifications.NotificationIngressResult
import ai.hans.standard.notifications.NotificationTriageQueue
import ai.hans.standard.notifications.NotificationTriageQueueState
import ai.hans.standard.notifications.NotificationTriageRuntime
import ai.hans.standard.notifications.NotificationTriageStorage
import ai.hans.standard.notifications.NotificationTriageTransactionCoordinator
import ai.hans.standard.notifications.RestrictedNotificationTriageExecutor
import ai.hans.standard.notifications.UserFacingDeliveryDisposition
import ai.hans.standard.notifications.UserFacingNotificationSuggestionSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDurableOutboxReplayTest {
    @Test
    fun replayWakeRunsAfterDurableAckAndOutsideTheHelpersTransaction() {
        val lock = Any()
        var acknowledged = false
        var wakes = 0

        assertEquals(OutboxDrainBatch.REPLAYED, drainNotificationOutboxBatch(
            lock = lock,
            pending = {
                assertTrue(Thread.holdsLock(lock))
                listOf(event(sequence = 21L, text = "Synthetic transaction probe"))
            },
            replay = { assertTrue(Thread.holdsLock(lock)); true },
            acknowledge = {
                assertTrue(Thread.holdsLock(lock))
                acknowledged = true
                true
            },
            onReplayed = {
                assertTrue("Wake preceded durable ACK", acknowledged)
                assertFalse("Wake retained the helper's transaction", Thread.holdsLock(lock))
                wakes += 1
            },
        ))
        assertEquals(1, wakes)
    }

    @Test
    fun failedAckDoesNotWakeAndKeepsTheSameEventReplayable() {
        val pending = mutableListOf(event(sequence = 22L, text = "Synthetic ACK retry"))
        val queue = queue()
        val ingress = NotificationTriageIngress(queue)
        val lock = NotificationTriageTransactionCoordinator.lock
        var acknowledgeSucceeds = false
        var replays = 0
        var wakes = 0
        fun drain() = drainNotificationOutboxBatch(
            lock = lock,
            pending = { pending.toList() },
            replay = {
                replays += 1
                replayDurableNotificationOutboxEvent(
                    it, ingress::afterInboxWrite, invalidate = { _, _ -> true }, preempt = {},
                )
            },
            acknowledge = { sequence ->
                if (acknowledgeSucceeds) pending.removeAll { it.sequence == sequence }
                acknowledgeSucceeds
            },
            onReplayed = { wakes += 1 },
        )

        assertEquals(OutboxDrainBatch.FAILED, drain())
        assertEquals(0, wakes)
        assertEquals(listOf(22L), pending.map { it.sequence })
        assertEquals(1, queue.receipts().size)

        acknowledgeSucceeds = true
        assertEquals(OutboxDrainBatch.REPLAYED, drain())
        assertEquals(2, replays)
        assertEquals(1, wakes)
        assertTrue(pending.isEmpty())
        assertEquals("A repeated durable event must remain idempotent", 1, queue.receipts().size)
    }

    @Test
    fun privacyClearAfterAckBeforeDelayedWakeCannotRestoreSourceOrStartModelWork() {
        val lock = NotificationTriageTransactionCoordinator.lock
        val queue = queue()
        val ingress = NotificationTriageIngress(queue)
        val pending = mutableListOf(event(sequence = 23L, text = "Synthetic forgotten source"))
        val callbackEntered = CountDownLatch(1)
        val releaseWake = CountDownLatch(1)
        val drainFinished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val modelCalls = AtomicInteger(0)
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val runtime = NotificationTriageRuntime(
            queue, RestrictedNotificationTriageExecutor {
                modelCalls.incrementAndGet()
                error("Forgotten source must not reach the model")
            }, UserFacingNotificationSuggestionSink { UserFacingDeliveryDisposition.ACCEPTED },
            scheduler = scheduler, ownsScheduler = false,
        )
        val drain = Thread {
            try {
                assertEquals(OutboxDrainBatch.REPLAYED, drainNotificationOutboxBatch(
                    lock = lock, pending = { pending.toList() },
                    replay = {
                        replayDurableNotificationOutboxEvent(
                            it, ingress::afterInboxWrite, invalidate = { _, _ -> true }, preempt = {},
                        )
                    },
                    acknowledge = { sequence -> pending.removeAll { it.sequence == sequence } },
                    onReplayed = {
                        assertFalse(Thread.holdsLock(lock))
                        callbackEntered.countDown()
                        check(releaseWake.await(5, TimeUnit.SECONDS))
                        runtime.requestDrain()
                    },
                ))
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                drainFinished.countDown()
            }
        }
        try {
            drain.start()
            assertTrue(callbackEntered.await(2, TimeUnit.SECONDS))
            synchronized(lock) {
                assertTrue("ACK must precede the privacy mutation", pending.isEmpty())
                assertEquals(1, queue.receipts().size)
                assertTrue(queue.clearAll())
            }
            releaseWake.countDown()
            assertTrue(drainFinished.await(2, TimeUnit.SECONDS))
            scheduler.submit {}.get(2, TimeUnit.SECONDS)
            assertEquals(null, failure.get())
            assertTrue(queue.receipts().isEmpty())
            assertEquals(0, modelCalls.get())
        } finally {
            releaseWake.countDown()
            drain.join(2_000)
            runtime.close()
            scheduler.shutdownNow()
        }
    }

    @Test
    fun privacyClearCannotLinearizeBetweenOutboxReadAndReplayAck() {
        val lock = Any()
        val replayEntered = CountDownLatch(1)
        val allowReplay = CountDownLatch(1)
        val clearEntered = CountDownLatch(1)
        var acknowledged = false
        var wakes = 0
        val durableEvent = event(sequence = 9L, text = "Private")

        val drain = Thread {
            assertEquals(
                OutboxDrainBatch.REPLAYED,
                drainNotificationOutboxBatch(
                    lock = lock,
                    pending = { listOf(durableEvent) },
                    replay = {
                        replayEntered.countDown()
                        check(allowReplay.await(2, TimeUnit.SECONDS))
                        true
                    },
                    acknowledge = {
                        acknowledged = true
                        true
                    },
                    onReplayed = { wakes += 1 },
                ),
            )
        }
        val privacyClear = Thread {
            check(replayEntered.await(2, TimeUnit.SECONDS))
            synchronized(lock) { clearEntered.countDown() }
        }

        drain.start()
        privacyClear.start()
        assertTrue(replayEntered.await(2, TimeUnit.SECONDS))
        assertFalse(clearEntered.await(100, TimeUnit.MILLISECONDS))
        allowReplay.countDown()
        drain.join(2_000)
        assertTrue(clearEntered.await(2, TimeUnit.SECONDS))
        privacyClear.join(2_000)
        assertTrue(acknowledged)
        assertEquals(1, wakes)
    }

    @Test
    fun successfulLiveReplayEmitsOneExactEventDrivenRuntimeWake() {
        var wakes = 0
        assertEquals(
            OutboxDrainBatch.REPLAYED,
            drainNotificationOutboxBatch(
                lock = Any(),
                pending = { listOf(event(sequence = 10L, text = "Live")) },
                replay = { true },
                acknowledge = { true },
                onReplayed = { wakes += 1 },
            ),
        )
        assertEquals(1, wakes)

        assertEquals(
            OutboxDrainBatch.EMPTY,
            drainNotificationOutboxBatch(
                lock = Any(),
                pending = { emptyList() },
                replay = { true },
                acknowledge = { true },
                onReplayed = { wakes += 1 },
            ),
        )
        assertEquals(1, wakes)
    }

    @Test
    fun replayedUpdateSupersedesStaleLeaseAndInvalidatesCenterBeforeAck() {
        val queue = queue()
        queue.ingest(event(sequence = 1L, text = "Old"))
        requireNotNull(queue.claimNextRestrictedTriage())
        val ingress = NotificationTriageIngress(queue)
        val invalidated = mutableListOf<Pair<String, String>>()
        var preempted = false

        val replayed = replayDurableNotificationOutboxEvent(
            event = event(sequence = 2L, text = "Current"),
            applyIngress = ingress::afterInboxWrite,
            invalidate = { packageName, androidKey ->
                invalidated += packageName to androidKey
                true
            },
            preempt = { preempted = true },
        )

        assertTrue(replayed)
        assertTrue(preempted)
        assertEquals(listOf("com.example.chat" to "thread"), invalidated)
        assertEquals(
            listOf(
                NotificationDeliveryState.DISMISSED_BY_TRIAGE,
                NotificationDeliveryState.PENDING_RESTRICTED_TRIAGE,
            ),
            queue.receipts().map { it.state },
        )
        assertEquals("Current", requireNotNull(queue.claimNextRestrictedTriage()).notification.text)
    }

    @Test
    fun replayedRemovalAlwaysCancelsQueueAndInvalidatesCenterIdempotently() {
        val queue = queue()
        queue.ingest(event(sequence = 1L, text = "Private"))
        val ingress = NotificationTriageIngress(queue)
        var invalidations = 0
        val removed = event(sequence = 2L, kind = NotificationEventKind.REMOVED, text = "Private")

        repeat(2) {
            assertTrue(
                replayDurableNotificationOutboxEvent(
                    event = removed,
                    applyIngress = ingress::afterInboxWrite,
                    invalidate = { _, _ ->
                        invalidations += 1
                        true
                    },
                    preempt = {},
                ),
            )
        }

        assertEquals(2, invalidations)
        assertEquals(NotificationDeliveryState.DISMISSED_BY_TRIAGE, queue.receipts().single().state)
        assertFalse(queue.receipts().single().suggestion != null)
    }

    @Test
    fun failedIngressKeepsOutboxUnacknowledgeable() {
        var invalidated = false

        assertFalse(
            replayDurableNotificationOutboxEvent(
                event = event(sequence = 3L, text = "Retry"),
                applyIngress = { _, _ -> null },
                invalidate = { _, _ ->
                    invalidated = true
                    true
                },
                preempt = {},
            ),
        )
        assertFalse(invalidated)
    }

    @Test
    fun failedValidatedCenterInvalidationKeepsReplayUnacknowledgeable() {
        val queue = queue()
        val ingress = NotificationTriageIngress(queue)

        assertFalse(
            replayDurableNotificationOutboxEvent(
                event = event(sequence = 4L, text = "Retry after Center I/O recovers"),
                applyIngress = ingress::afterInboxWrite,
                invalidate = { _, _ -> false },
                preempt = {},
            ),
        )
        // Queue replay is idempotent. The durable SQLite outbox remains authoritative and may
        // repeat this exact source sequence once Center revocation becomes provable.
        assertEquals(1, queue.receipts().size)
        assertTrue(
            replayDurableNotificationOutboxEvent(
                event = event(sequence = 4L, text = "Retry after Center I/O recovers"),
                applyIngress = ingress::afterInboxWrite,
                invalidate = { _, _ -> true },
                preempt = {},
            ),
        )
        assertEquals(1, queue.receipts().size)
    }

    private fun queue() = NotificationTriageQueue(
        storage = MemoryStorage(),
        exclusionPolicy = HansNotificationExclusionPolicy(setOf("ai.hans.standard")),
        clock = { 10_000L },
    )

    private fun event(
        sequence: Long,
        kind: NotificationEventKind = NotificationEventKind.POSTED,
        text: String,
    ) = NotificationInboxEvent(
        sequence = sequence,
        kind = kind,
        observedAtEpochMillis = sequence * 100,
        removalReason = if (kind == NotificationEventKind.REMOVED) 7 else null,
        snapshot = NotificationSnapshot(
            packageName = "com.example.chat",
            androidKey = "thread",
            postTimeEpochMillis = sequence * 100,
            notificationWhenEpochMillis = sequence * 100,
            title = "Alex",
            text = text,
            subtext = "",
            category = "message",
            channelId = "messages",
            ongoing = false,
            clearable = true,
            actions = emptyList(),
        ),
    )

    private class MemoryStorage : NotificationTriageStorage {
        private var state = NotificationTriageQueueState(emptyList())
        override fun read(): NotificationTriageQueueState = state
        override fun write(state: NotificationTriageQueueState) {
            this.state = state
        }
    }
}
