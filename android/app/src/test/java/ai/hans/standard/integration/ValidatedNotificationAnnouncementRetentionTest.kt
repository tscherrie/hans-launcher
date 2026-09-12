package ai.hans.standard.integration

import ai.hans.standard.notifications.NotificationUrgency
import ai.hans.standard.notifications.HansNotificationExclusionPolicy
import ai.hans.standard.notifications.NotificationDeliveryState
import ai.hans.standard.notifications.NotificationDismissalReason
import ai.hans.standard.notifications.NotificationTriageBounds
import ai.hans.standard.notifications.NotificationTriageProcessor
import ai.hans.standard.notifications.NotificationTriageQueue
import ai.hans.standard.notifications.NotificationTriageQueueState
import ai.hans.standard.notifications.NotificationTriageStepResult
import ai.hans.standard.notifications.NotificationTriageStorage
import ai.hans.standard.notifications.RestrictedNotificationTriageExecutor
import ai.hans.standard.notifications.RestrictedTriageDecision
import ai.hans.standard.notifications.UserDeliveryCompletionResult
import ai.hans.standard.notifications.UserFacingDeliveryDisposition
import ai.hans.standard.notifications.UserFacingNotificationActivationDisposition
import ai.hans.standard.notifications.UserFacingNotificationDelivery
import ai.hans.standard.notifications.UserFacingNotificationSuggestion
import ai.hans.standard.notifications.UserFacingNotificationSuggestionSink
import ai.hans.standard.phone.notifications.NotificationActionMetadata
import ai.hans.standard.phone.notifications.NotificationEventKind
import ai.hans.standard.phone.notifications.NotificationInboxEvent
import ai.hans.standard.phone.notifications.NotificationPrivacyPurgeFence
import ai.hans.standard.phone.notifications.NotificationSnapshot
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatedNotificationAnnouncementRetentionTest {
    @Test
    fun durableContextReservationSurvivesReopenAndOnlyExactTerminalOutcomeReleasesIt() {
        val storage = RetentionStorage()
        val generation = RetentionGenerationStore()
        val first = ValidatedNotificationAnnouncementCenter(
            storage = storage,
            privacyGenerationStore = generation,
            clock = { 100L },
            privacyFence = RetentionPrivacyFence(),
            deliveryFence = RetentionDeliveryFence(),
            retentionRead = { NotificationAnnouncementRetentionRead.Available(24) },
        )
        assertTrue(first.accept(delivery("reserved")))
        val id = first.pendingContext().single().id
        assertTrue(first.reserveContext(setOf(id), "message-reserved"))
        assertTrue(first.pendingContext().isEmpty())

        val reopened = ValidatedNotificationAnnouncementCenter(
            storage = storage,
            privacyGenerationStore = generation,
            clock = { 200L },
            privacyFence = RetentionPrivacyFence(),
            deliveryFence = RetentionDeliveryFence(),
            retentionRead = { NotificationAnnouncementRetentionRead.Available(24) },
        )
        assertEquals(
            mapOf("message-reserved" to setOf(id)),
            reopened.contextReservations(),
        )
        assertTrue(reopened.pendingContext().isEmpty())
        assertFalse(reopened.releaseContextReservation(setOf(id), "wrong-message"))
        assertTrue(reopened.pendingContext().isEmpty())
        assertTrue(reopened.releaseContextReservation(setOf(id), "message-reserved"))
        assertEquals(listOf(id), reopened.pendingContext().map { it.id })

        assertTrue(reopened.reserveContext(setOf(id), "message-sent"))
        assertTrue(reopened.markContextInjected(setOf(id), "message-sent"))
        assertTrue(reopened.contextReservations().isEmpty())
        assertTrue(reopened.pendingContext().isEmpty())
    }

    @Test
    fun ambiguousReservedContextCannotBeRemovedBySupersessionOrQueueRevocation() {
        val storage = RetentionStorage()
        val center = center(storage = storage, clock = { 300L })
        val key = "reserved-revocation"
        val supersessionKey = "source:" + "a".repeat(64)
        assertTrue(center.accept(delivery(key, supersessionKey)))
        val id = center.pendingContext().single().id
        assertTrue(center.reserveContext(setOf(id), "ambiguous-message"))

        assertTrue(center.revokePending(key))
        assertEquals(setOf(id), center.contextReservations()["ambiguous-message"])
        assertTrue(center.snapshot().isEmpty())
        assertTrue(center.pendingSpeech().isEmpty())
        assertTrue(center.pendingContext().isEmpty())
        assertTrue(center.removePendingBySupersessionKey(supersessionKey))
        assertEquals(setOf(id), center.contextReservations()["ambiguous-message"])
        assertTrue(center.snapshot().isEmpty())

        val reopened = ValidatedNotificationAnnouncementCenter(
            storage = storage,
            privacyGenerationStore = RetentionGenerationStore(),
            clock = { 400L },
            privacyFence = RetentionPrivacyFence(),
            deliveryFence = RetentionDeliveryFence(),
            retentionRead = { NotificationAnnouncementRetentionRead.Available(24) },
        )
        assertTrue(reopened.snapshot().isEmpty())
        assertTrue(reopened.pendingSpeech().isEmpty())
        assertEquals(setOf(id), reopened.contextReservations()["ambiguous-message"])
        assertTrue(reopened.markContextInjected(setOf(id), "ambiguous-message"))
        assertTrue(storage.items.isEmpty())
    }

    @Test
    fun newerSupersedingUpdateTombstonesReservedOldOutputWithoutLosingCorrelation() {
        val storage = RetentionStorage()
        val center = center(storage = storage, clock = { 450L })
        val supersessionKey = "source:" + "b".repeat(64)
        assertTrue(center.accept(delivery("old", supersessionKey)))
        val oldId = center.pendingContext().single().id
        assertTrue(center.reserveContext(setOf(oldId), "ambiguous-old"))

        assertTrue(center.accept(delivery("new", supersessionKey)))

        assertEquals(1, center.snapshot().size)
        assertEquals(1, center.pendingSpeech().size)
        assertEquals(setOf(oldId), center.contextReservations()["ambiguous-old"])
        assertTrue(center.pendingContext().none { it.id == oldId })
        assertTrue(center.markContextInjected(setOf(oldId), "ambiguous-old"))
        assertTrue(center.contextReservations().isEmpty())
        assertEquals(1, storage.items.size)
    }

    @Test
    fun capacityNeverEvictsUnspokenAnnouncementAndReusesSpokenSlotWithoutMainContext() {
        val storage = RetentionStorage()
        val center = center(storage = storage, clock = { 500L })
        repeat(100) { index -> assertTrue(center.accept(delivery("live-$index"))) }
        val before = storage.items

        assertFalse(center.stage(delivery("overflow-live")))
        assertEquals(before, storage.items)

        val completedId = center.snapshot().first().id
        assertTrue(center.markSpoken(completedId))
        assertTrue(center.stage(delivery("replacement-after-consumed")))
        assertEquals(100, storage.items.size)
        assertTrue(storage.items.none { it.id == completedId })
    }

    @Test
    fun reservedContextIdsAreFilteredBeforeThePerTurnLimitIsApplied() {
        val storage = RetentionStorage()
        val center = center(storage = storage, clock = { 500L })
        repeat(10) { index -> assertTrue(center.accept(delivery("context-$index"))) }
        val allIds = storage.items.map(ValidatedNotificationAnnouncement::id)

        val available = center.pendingContext(excludingIds = allIds.take(8).toSet())

        assertEquals(allIds.takeLast(2), available.map(ValidatedNotificationAnnouncement::id))
    }

    @Test
    fun expiryPurgesDurablyBeforeUiSpeechOrContextCanReadWithoutInboxActivity() {
        var now = 1_000L
        val storage = RetentionStorage()
        val observations = mutableListOf<List<ValidatedNotificationAnnouncement>>()
        val center = center(storage = storage, clock = { now })
        center.addObserver { observations += it }

        assertTrue(center.accept(delivery("expiring")))
        assertEquals(1, center.snapshot().size)

        now += TimeUnit.HOURS.toMillis(1) + 1L

        assertTrue(center.snapshot().isEmpty())
        assertTrue(center.pendingSpeech().isEmpty())
        assertTrue(center.pendingContext().isEmpty())
        assertTrue(storage.items.isEmpty())
        assertTrue(observations.last().isEmpty())
    }

    @Test
    fun expiryErasesPrivateContentButRetainsMinimalAmbiguousDispatchCorrelation() {
        var now = 1_500L
        val storage = RetentionStorage()
        val center = center(storage = storage, clock = { now })
        assertTrue(center.accept(delivery("expiring-reserved")))
        val id = center.pendingContext().single().id
        assertTrue(center.reserveContext(setOf(id), "ambiguous-expired"))

        now += TimeUnit.HOURS.toMillis(1) + 1L

        assertTrue(center.snapshot().isEmpty())
        assertTrue(center.pendingSpeech().isEmpty())
        assertTrue(center.pendingContext().isEmpty())
        assertEquals(setOf(id), center.contextReservations()["ambiguous-expired"])
        val tombstone = storage.items.single()
        assertTrue(tombstone.outputRevoked)
        assertEquals("Expired notification reservation.", tombstone.summary)
        assertEquals("", tombstone.supersessionKey)
        assertEquals(null, tombstone.timelineAnchorId)
        assertTrue(center.markContextInjected(setOf(id), "ambiguous-expired"))
        assertTrue(storage.items.isEmpty())
    }

    @Test
    fun failedExpiryWriteKeepsEveryOutputHiddenBehindDurableFence() {
        var now = 2_000L
        val storage = RetentionStorage()
        val localFence = RetentionPrivacyFence()
        val center = center(
            storage = storage,
            clock = { now },
            privacyFence = localFence,
        )
        assertTrue(center.accept(delivery("expiry-write-failure")))
        storage.failWrites = true
        now += TimeUnit.HOURS.toMillis(1) + 1L

        assertTrue(center.snapshot().isEmpty())
        assertTrue(center.pendingSpeech().isEmpty())
        assertTrue(center.pendingContext().isEmpty())
        assertTrue(localFence.isRequired())
        assertEquals(1, storage.items.size)
    }

    @Test
    fun unavailableRetentionPolicyFailsClosedWithoutPublishingStoredSummary() {
        val storage = RetentionStorage()
        val localFence = RetentionPrivacyFence()
        val deliveryFence = RetentionDeliveryFence()
        var policyAvailable = true
        val center = ValidatedNotificationAnnouncementCenter(
            storage = storage,
            privacyGenerationStore = RetentionGenerationStore(),
            clock = { 3_000L },
            privacyFence = localFence,
            deliveryFence = deliveryFence,
            retentionRead = {
                if (policyAvailable) {
                    NotificationAnnouncementRetentionRead.Available(1)
                } else {
                    NotificationAnnouncementRetentionRead.Unavailable
                }
            },
        )
        assertTrue(center.accept(delivery("policy-unavailable")))

        policyAvailable = false

        assertTrue(center.snapshot().isEmpty())
        assertTrue(center.pendingSpeech().isEmpty())
        assertTrue(center.pendingContext().isEmpty())
        assertTrue(localFence.isRequired())
        assertTrue(deliveryFence.isRequired())
    }

    @Test
    fun sentContextWithFailedCompletionWriteCanNeverBeInjectedAgainAfterRuntimeLoss() {
        val storage = RetentionStorage()
        val localFence = RetentionPrivacyFence()
        val generation = RetentionGenerationStore()
        val center = ValidatedNotificationAnnouncementCenter(
            storage = storage,
            privacyGenerationStore = generation,
            clock = { 4_000L },
            privacyFence = localFence,
            deliveryFence = RetentionDeliveryFence(),
            retentionRead = { NotificationAnnouncementRetentionRead.Available(24) },
        )
        assertTrue(center.accept(delivery("context-write-failure")))
        val id = center.pendingContext().single().id
        storage.failWrites = true

        assertFalse(center.markContextInjected(setOf(id)))
        assertTrue(localFence.isRequired())
        assertTrue(center.pendingContext().isEmpty())
        assertTrue(center.snapshot().isEmpty())

        storage.failWrites = false
        val reopened = ValidatedNotificationAnnouncementCenter(
            storage = storage,
            privacyGenerationStore = generation,
            clock = { 5_000L },
            privacyFence = localFence,
            deliveryFence = RetentionDeliveryFence(),
            retentionRead = { NotificationAnnouncementRetentionRead.Available(24) },
        )
        assertTrue(reopened.pendingContext().isEmpty())
        assertTrue(reopened.snapshot().isEmpty())
        assertTrue(storage.items.isEmpty())
    }

    @Test
    fun expiredPrivateReceiptSurvivesReopenAndLetsProcessorReachNewerCommittedItem() {
        var now = 1_000L
        val centerStorage = RetentionStorage()
        val generation = RetentionGenerationStore()
        val queueStorage = CrossStoreQueueStorage()
        val queue = crossStoreQueue(queueStorage) { now }
        val firstCenter = crossStoreCenter(centerStorage, generation, { now }, maxAgeHours = 1)
        val stagingOnly = centerSink(firstCenter, activate = false)

        enqueueCommitted(queue, stagingOnly, sequence = 1L, summary = "Alte private Meldung.")
        now += TimeUnit.HOURS.toMillis(1) + 1L
        enqueueCommitted(queue, stagingOnly, sequence = 2L, summary = "Neue Meldung.")

        // The first receipt is content-erased before process recreation; the newer one remains
        // private and staged. Neither may surface before the Queue chooses its terminal outcome.
        assertTrue(firstCenter.snapshot().isEmpty())
        val firstTombstone = centerStorage.items.first()
        assertEquals(
            ValidatedNotificationActivationTombstone.SUPPRESSED,
            firstTombstone.activationTombstone,
        )
        assertEquals("Expired notification activation receipt.", firstTombstone.summary)

        val reopened = crossStoreCenter(centerStorage, generation, { now }, maxAgeHours = 1)
        val processor = NotificationTriageProcessor(
            queue = queue,
            restrictedExecutor = RestrictedNotificationTriageExecutor { error("no model work") },
            suggestionSink = centerSink(reopened, activate = true),
        )

        assertEquals(NotificationTriageStepResult.PROGRESSED, processor.processOne())
        val afterSuppression = queue.receipts().associateBy { it.sourceSequence }
        assertEquals(
            NotificationDeliveryState.DISMISSED_BY_TRIAGE,
            afterSuppression.getValue(1L).state,
        )
        assertEquals(
            NotificationDismissalReason.DELIVERY_RETENTION_EXPIRED,
            afterSuppression.getValue(1L).dismissalReason,
        )
        assertNull(afterSuppression.getValue(1L).suggestion)
        assertEquals(
            NotificationDeliveryState.DELIVERY_COMMITTED_PENDING_ACTIVATION,
            afterSuppression.getValue(2L).state,
        )

        assertEquals(NotificationTriageStepResult.PROGRESSED, processor.processOne())
        assertEquals(
            NotificationDeliveryState.DELIVERED_TO_USER,
            queue.receipts().single { it.sourceSequence == 2L }.state,
        )
        assertEquals(listOf("Neue Meldung."), reopened.snapshot().map { it.summary })
        assertTrue(centerStorage.items.none { it.id == firstTombstone.id })
    }

    @Test
    fun activeReceiptSurvivesTerminalWriteCrashRetentionAndReopenWithoutRepublish() {
        var now = 10_000L
        val centerStorage = RetentionStorage()
        val generation = RetentionGenerationStore()
        val queueStorage = CrossStoreQueueStorage()
        val queue = crossStoreQueue(queueStorage) { now }
        val firstCenter = crossStoreCenter(centerStorage, generation, { now }, maxAgeHours = 1)
        enqueueCommitted(
            queue,
            centerSink(firstCenter, activate = false),
            sequence = 11L,
            summary = "Einmal sichtbare Meldung.",
        )
        val delivery = requireNotNull(queue.nextCommittedUserDelivery())
        var nonEmptyPublications = 0
        firstCenter.addObserver { if (it.isNotEmpty()) nonEmptyPublications += 1 }

        queueStorage.failNextWrite = true
        runCatching {
            queue.activateCommittedSuggestion(delivery, centerSink(firstCenter, activate = true))
        }.onSuccess { error("terminal write must fail") }
        assertEquals(1, nonEmptyPublications)
        assertEquals(
            NotificationDeliveryState.DELIVERY_COMMITTED_PENDING_ACTIVATION,
            queue.receipts().single().state,
        )

        now += TimeUnit.HOURS.toMillis(1) + 1L
        assertTrue(firstCenter.snapshot().isEmpty())
        assertEquals(
            ValidatedNotificationActivationTombstone.ACTIVE,
            centerStorage.items.single().activationTombstone,
        )

        val reopened = crossStoreCenter(centerStorage, generation, { now }, maxAgeHours = 1)
        reopened.addObserver { if (it.isNotEmpty()) nonEmptyPublications += 1 }
        val recovered = requireNotNull(queue.nextCommittedUserDelivery())
        val result = queue.activateCommittedSuggestion(
            recovered,
            centerSink(reopened, activate = true),
        ) as UserDeliveryCompletionResult.Accepted

        assertEquals(NotificationDeliveryState.DELIVERED_TO_USER, result.receipt.state)
        assertEquals(1, nonEmptyPublications)
        assertTrue(reopened.snapshot().isEmpty())
        assertTrue(centerStorage.items.isEmpty())
    }

    @Test
    fun unacknowledgedReceiptBackpressuresUntilExactQueueHorizonThenFreesCapacity() {
        var now = 20_000L
        val storage = RetentionStorage()
        val generation = RetentionGenerationStore()
        val center = crossStoreCenter(storage, generation, { now }, maxAgeHours = 1)
        val deadline = now + NotificationTriageBounds.MAX_BACKLOG_AGE_MILLIS
        repeat(100) { index ->
            assertTrue(center.stage(delivery("receipt-$index", deadline = deadline)))
        }

        now += TimeUnit.HOURS.toMillis(1) + 1L
        assertTrue(center.snapshot().isEmpty())
        assertEquals(100, storage.items.size)
        assertTrue(storage.items.all { it.activationTombstone != null })
        assertFalse(center.stage(delivery("before-horizon", deadline = deadline + 1L)))
        assertEquals(100, storage.items.size)

        now = deadline
        assertTrue(center.snapshot().isEmpty())
        assertTrue(storage.items.isEmpty())
        assertTrue(
            center.stage(
                delivery(
                    "after-horizon",
                    deadline = now + NotificationTriageBounds.MAX_BACKLOG_AGE_MILLIS,
                ),
            ),
        )
    }

    @Test
    fun queueHorizonCleansPrivateOrphanEvenWhenContentRetentionIsLonger() {
        var now = 30_000L
        val storage = RetentionStorage()
        val generation = RetentionGenerationStore()
        val center = crossStoreCenter(storage, generation, { now }, maxAgeHours = 24)
        val deadline = now + NotificationTriageBounds.MAX_BACKLOG_AGE_MILLIS
        assertTrue(center.stage(delivery("private-orphan", deadline = deadline)))
        assertTrue(storage.items.single().summary.contains("wichtige"))

        now = deadline

        assertTrue(center.snapshot().isEmpty())
        assertTrue(storage.items.isEmpty())
    }

    @Test
    fun queueHorizonClearsActiveReceiptButKeepsContentUnderLongerRetention() {
        var now = 35_000L
        val storage = RetentionStorage()
        val generation = RetentionGenerationStore()
        val center = crossStoreCenter(storage, generation, { now }, maxAgeHours = 24)
        val item = delivery(
            "active-long-retention",
            deadline = now + NotificationTriageBounds.MAX_BACKLOG_AGE_MILLIS,
        )
        assertTrue(center.stage(item))
        assertEquals(
            UserFacingNotificationActivationDisposition.ACTIVE,
            center.activateResult(item.idempotencyKey),
        )

        now = item.activationExpiresAtEpochMillis

        assertEquals(1, center.snapshot().size)
        val retained = storage.items.single()
        assertFalse(retained.activationReceiptPending)
        assertNull(retained.activationExpiresAtEpochMillis)
        assertNull(retained.activationTombstone)
    }

    @Test
    fun queueHorizonClearsReceiptButPreservesAmbiguousContextTombstone() {
        var now = 37_000L
        val storage = RetentionStorage()
        val generation = RetentionGenerationStore()
        val center = crossStoreCenter(storage, generation, { now }, maxAgeHours = 1)
        val item = delivery(
            "reserved-through-horizon",
            deadline = now + NotificationTriageBounds.MAX_BACKLOG_AGE_MILLIS,
        )
        assertTrue(center.stage(item))
        assertEquals(
            UserFacingNotificationActivationDisposition.ACTIVE,
            center.activateResult(item.idempotencyKey),
        )
        val id = center.snapshot().single().id
        assertTrue(center.reserveContext(setOf(id), "ambiguous-through-horizon"))

        now += TimeUnit.HOURS.toMillis(1) + 1L
        assertTrue(center.snapshot().isEmpty())
        assertTrue(storage.items.single().outputRevoked)
        now = item.activationExpiresAtEpochMillis
        assertTrue(center.snapshot().isEmpty())

        val retained = storage.items.single()
        assertFalse(retained.activationReceiptPending)
        assertNull(retained.activationExpiresAtEpochMillis)
        assertTrue(retained.outputRevoked)
        assertEquals("ambiguous-through-horizon", retained.contextReservationId)
        assertTrue(
            center.releaseContextReservation(setOf(id), "ambiguous-through-horizon"),
        )
        assertTrue(storage.items.isEmpty())
    }

    @Test
    fun failedFinalAckLeavesOnlyBoundedReceiptAndNeverReplaysAfterPrivacyExpiry() {
        var now = 40_000L
        val storage = RetentionStorage()
        val generation = RetentionGenerationStore()
        val center = crossStoreCenter(storage, generation, { now }, maxAgeHours = 1)
        val item = delivery(
            "failed-final-ack",
            deadline = now + NotificationTriageBounds.MAX_BACKLOG_AGE_MILLIS,
        )
        assertTrue(center.stage(item))
        assertEquals(
            UserFacingNotificationActivationDisposition.ACTIVE,
            center.activateResult(item.idempotencyKey),
        )
        storage.failWrites = true
        assertFalse(
            center.finalizeActivation(
                item.idempotencyKey,
                UserFacingNotificationActivationDisposition.ACTIVE,
            ),
        )
        storage.failWrites = false

        now += TimeUnit.HOURS.toMillis(1) + 1L
        assertTrue(center.snapshot().isEmpty())
        assertEquals(
            ValidatedNotificationActivationTombstone.ACTIVE,
            storage.items.single().activationTombstone,
        )
        now = item.activationExpiresAtEpochMillis
        assertTrue(center.snapshot().isEmpty())
        assertTrue(storage.items.isEmpty())
    }

    @Test
    fun sentContextAckPreservesUnacknowledgedActiveReceiptAsContentErasedTombstone() {
        assertContextAckPreservesActivationReceipt(sent = true)
    }

    @Test
    fun failedContextAckPreservesUnacknowledgedActiveReceiptAsContentErasedTombstone() {
        assertContextAckPreservesActivationReceipt(sent = false)
    }

    @Test
    fun supersessionPreservesUnacknowledgedActiveReceiptAsTypedTombstone() {
        val now = 55_000L
        val storage = RetentionStorage()
        val generation = RetentionGenerationStore()
        val center = crossStoreCenter(storage, generation, { now }, maxAgeHours = 24)
        val supersessionKey = "source:" + "c".repeat(64)
        val old = delivery(
            key = "superseded-active-receipt",
            supersessionKey = supersessionKey,
            deadline = now + NotificationTriageBounds.MAX_BACKLOG_AGE_MILLIS,
        )
        assertTrue(center.stage(old))
        assertEquals(
            UserFacingNotificationActivationDisposition.ACTIVE,
            center.activateResult(old.idempotencyKey),
        )

        val replacement = delivery(
            key = "superseding-private-receipt",
            supersessionKey = supersessionKey,
            deadline = now + NotificationTriageBounds.MAX_BACKLOG_AGE_MILLIS,
        )
        assertTrue(center.stage(replacement))

        val activeTombstone = storage.items.single {
            it.activationTombstone == ValidatedNotificationActivationTombstone.ACTIVE
        }
        assertEquals("Expired notification activation receipt.", activeTombstone.summary)
        assertEquals(
            UserFacingNotificationActivationDisposition.ACTIVE,
            center.activateResult(old.idempotencyKey),
        )
        assertTrue(
            center.finalizeActivation(
                old.idempotencyKey,
                UserFacingNotificationActivationDisposition.ACTIVE,
            ),
        )
        assertEquals(1, storage.items.size)
        assertTrue(storage.items.single().activationPending)
    }

    @Test
    fun removalPreservesUnacknowledgedPrivateReceiptAsSuppressedTombstone() {
        val now = 57_000L
        val storage = RetentionStorage()
        val generation = RetentionGenerationStore()
        val center = crossStoreCenter(storage, generation, { now }, maxAgeHours = 24)
        val supersessionKey = "source:" + "d".repeat(64)
        val item = delivery(
            key = "removed-private-receipt",
            supersessionKey = supersessionKey,
            deadline = now + NotificationTriageBounds.MAX_BACKLOG_AGE_MILLIS,
        )
        assertTrue(center.stage(item))

        assertTrue(center.removePendingBySupersessionKey(supersessionKey))

        val tombstone = storage.items.single()
        assertEquals(
            ValidatedNotificationActivationTombstone.SUPPRESSED,
            tombstone.activationTombstone,
        )
        assertEquals("Expired notification activation receipt.", tombstone.summary)
        assertTrue(center.snapshot().isEmpty())
        assertTrue(center.pendingSpeech().isEmpty())
        assertTrue(center.pendingContext().isEmpty())
        assertEquals(
            UserFacingNotificationActivationDisposition.SUPPRESSED,
            center.activateResult(item.idempotencyKey),
        )
        assertTrue(
            center.finalizeActivation(
                item.idempotencyKey,
                UserFacingNotificationActivationDisposition.SUPPRESSED,
            ),
        )
        assertTrue(storage.items.isEmpty())
    }

    private fun assertContextAckPreservesActivationReceipt(sent: Boolean) {
        var now = 50_000L
        val storage = RetentionStorage()
        val generation = RetentionGenerationStore()
        val center = crossStoreCenter(storage, generation, { now }, maxAgeHours = 1)
        val item = delivery(
            "context-receipt-$sent",
            deadline = now + NotificationTriageBounds.MAX_BACKLOG_AGE_MILLIS,
        )
        assertTrue(center.stage(item))
        assertEquals(
            UserFacingNotificationActivationDisposition.ACTIVE,
            center.activateResult(item.idempotencyKey),
        )
        val id = center.snapshot().single().id
        assertTrue(center.reserveContext(setOf(id), "context-message-$sent"))

        now += TimeUnit.HOURS.toMillis(1) + 1L
        assertTrue(center.snapshot().isEmpty())
        assertTrue(storage.items.single().outputRevoked)
        val acknowledged = if (sent) {
            center.markContextInjected(setOf(id), "context-message-$sent")
        } else {
            center.releaseContextReservation(setOf(id), "context-message-$sent")
        }
        assertTrue(acknowledged)
        assertTrue(center.contextReservations().isEmpty())
        val tombstone = storage.items.single()
        assertEquals(
            ValidatedNotificationActivationTombstone.ACTIVE,
            tombstone.activationTombstone,
        )
        assertEquals("Expired notification activation receipt.", tombstone.summary)
        assertEquals(
            UserFacingNotificationActivationDisposition.ACTIVE,
            center.activateResult(item.idempotencyKey),
        )
        assertTrue(
            center.finalizeActivation(
                item.idempotencyKey,
                UserFacingNotificationActivationDisposition.ACTIVE,
            ),
        )
        assertTrue(storage.items.isEmpty())
    }

    private fun crossStoreCenter(
        storage: RetentionStorage,
        generation: RetentionGenerationStore,
        clock: () -> Long,
        maxAgeHours: Int,
    ) = ValidatedNotificationAnnouncementCenter(
        storage = storage,
        privacyGenerationStore = generation,
        clock = clock,
        privacyFence = RetentionPrivacyFence(),
        deliveryFence = RetentionDeliveryFence(),
        retentionRead = { NotificationAnnouncementRetentionRead.Available(maxAgeHours) },
    )

    private fun crossStoreQueue(
        storage: CrossStoreQueueStorage,
        clock: () -> Long,
    ) = NotificationTriageQueue(
        storage = storage,
        exclusionPolicy = HansNotificationExclusionPolicy(setOf("ai.hans.standard")),
        clock = clock,
    )

    private fun enqueueCommitted(
        queue: NotificationTriageQueue,
        sink: UserFacingNotificationSuggestionSink,
        sequence: Long,
        summary: String,
    ) {
        assertTrue(
            queue.ingest(notificationEvent(sequence)) is
                ai.hans.standard.notifications.NotificationIngressResult.Queued,
        )
        val work = requireNotNull(queue.claimNextRestrictedTriage())
        queue.completeRestrictedTriage(
            work.receipt.id,
            work.claimToken,
            RestrictedTriageDecision.SuggestUser(
                UserFacingNotificationSuggestion(summary, NotificationUrgency.HIGH),
            ),
        )
        val lease = requireNotNull(queue.claimNextUserDelivery())
        val result = queue.deliverClaimedSuggestion(lease, sink)
            as UserDeliveryCompletionResult.Accepted
        assertEquals(
            NotificationDeliveryState.DELIVERY_COMMITTED_PENDING_ACTIVATION,
            result.receipt.state,
        )
    }

    private fun centerSink(
        center: ValidatedNotificationAnnouncementCenter,
        activate: Boolean,
    ) = object : UserFacingNotificationSuggestionSink {
        override fun deliver(
            delivery: UserFacingNotificationDelivery,
        ): UserFacingDeliveryDisposition = if (center.stage(delivery)) {
            UserFacingDeliveryDisposition.ACCEPTED
        } else {
            UserFacingDeliveryDisposition.RETRY
        }

        override fun activate(
            delivery: UserFacingNotificationDelivery,
        ): UserFacingNotificationActivationDisposition = if (activate) {
            center.activateResult(delivery.idempotencyKey)
        } else {
            UserFacingNotificationActivationDisposition.RETRY
        }

        override fun finalizeActivation(
            delivery: UserFacingNotificationDelivery,
            disposition: UserFacingNotificationActivationDisposition,
        ) {
            center.finalizeActivation(delivery.idempotencyKey, disposition)
        }

        override fun revoke(delivery: UserFacingNotificationDelivery) {
            center.revokePending(delivery.idempotencyKey)
        }
    }

    private fun notificationEvent(sequence: Long) = NotificationInboxEvent(
        sequence = sequence,
        kind = NotificationEventKind.POSTED,
        observedAtEpochMillis = 0L,
        removalReason = null,
        snapshot = NotificationSnapshot(
            packageName = "com.example.chat",
            androidKey = "key-$sequence",
            postTimeEpochMillis = 0L,
            notificationWhenEpochMillis = 0L,
            title = "Person $sequence",
            text = "Private source text $sequence",
            subtext = "",
            category = "message",
            channelId = "messages",
            ongoing = false,
            clearable = true,
            actions = emptyList<NotificationActionMetadata>(),
        ),
    )

    private fun center(
        storage: RetentionStorage,
        clock: () -> Long,
        privacyFence: RetentionPrivacyFence = RetentionPrivacyFence(),
    ) = ValidatedNotificationAnnouncementCenter(
        storage = storage,
        privacyGenerationStore = RetentionGenerationStore(),
        clock = clock,
        privacyFence = privacyFence,
        deliveryFence = RetentionDeliveryFence(),
        retentionRead = { NotificationAnnouncementRetentionRead.Available(1) },
    )

    private fun delivery(
        key: String,
        supersessionKey: String = "",
        deadline: Long = Long.MAX_VALUE,
    ) = UserFacingNotificationDelivery(
        receiptId = "receipt-$key",
        idempotencyKey = key,
        supersessionKey = supersessionKey,
        activationExpiresAtEpochMillis = deadline,
        suggestion = UserFacingNotificationSuggestion(
            summary = "Eine wichtige, aber zeitlich begrenzte Meldung.",
            urgency = NotificationUrgency.HIGH,
        ),
    )
}

private class RetentionStorage : ValidatedAnnouncementStorage {
    var items: List<ValidatedNotificationAnnouncement> = emptyList()
    var failWrites = false

    override fun read(): List<ValidatedNotificationAnnouncement> = items

    override fun readStatus(): ValidatedAnnouncementStorageRead =
        ValidatedAnnouncementStorageRead.Available(items)

    override fun write(items: List<ValidatedNotificationAnnouncement>): Boolean {
        if (failWrites) return false
        this.items = items
        return true
    }
}

private class CrossStoreQueueStorage : NotificationTriageStorage {
    private var state = NotificationTriageQueueState(emptyList())
    var failNextWrite = false

    override fun read(): NotificationTriageQueueState = state

    override fun write(state: NotificationTriageQueueState) {
        if (failNextWrite) {
            failNextWrite = false
            error("simulated_queue_terminal_write_failure")
        }
        this.state = state
    }
}

private class RetentionGenerationStore : NotificationPrivacyGenerationStore {
    private var generation = 1L
    override fun current(): Long = generation
    override fun advance(): Long = ++generation
}

private class RetentionPrivacyFence : NotificationAnnouncementPrivacyFence {
    private var required = false
    override fun isRequired(): Boolean = required
    override fun markRequired(): Boolean {
        required = true
        return true
    }
    override fun markClean(): Boolean {
        required = false
        return true
    }
}

private class RetentionDeliveryFence : NotificationPrivacyPurgeFence {
    private var required = false
    override fun isRequired(): Boolean = required
    override fun markRequired(): Boolean {
        required = true
        return true
    }
    override fun markClean(): Boolean {
        required = false
        return true
    }
}
