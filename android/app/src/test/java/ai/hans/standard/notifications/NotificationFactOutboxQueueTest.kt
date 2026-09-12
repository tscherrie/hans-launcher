package ai.hans.standard.notifications

import ai.hans.standard.phone.notifications.NotificationEventKind
import ai.hans.standard.phone.notifications.NotificationInboxEvent
import ai.hans.standard.phone.notifications.NotificationSnapshot
import ai.hans.standard.phone.notifications.facts.*
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** The fake archive isolates the cross-store crash boundary; real SQLite tests live separately. */
class NotificationFactOutboxQueueTest {
    @Test fun transientReadFailureCannotEraseAValidDurableOutbox() {
        val f = Fixture()
        f.complete(f.claim())
        val before = f.storage.state
        var unavailable = true
        var writes = 0
        val storage = object : NotificationTriageStorage {
            override fun read() = f.storage.state
            override fun readStatus(): NotificationTriageStorageRead = if (unavailable) {
                NotificationTriageStorageRead.Unavailable
            } else NotificationTriageStorageRead.Available(f.storage.state)
            override fun write(state: NotificationTriageQueueState) {
                writes++
                unavailable = false
                f.storage.write(state)
            }
        }
        val queue = NotificationTriageQueue(storage,
            HansNotificationExclusionPolicy(setOf("ai.hans.standard")), { f.now }, f.archive)
        assertFalse(queue.clearAll())
        assertFalse(queue.repairToVerifiedEmpty())
        assertFalse(queue.purgeFactOutbox(NotificationFactPrivacyScope.Package(PACKAGE)))
        assertEquals(0, writes)
        assertEquals(before, f.storage.state)
        unavailable = false
        assertTrue(queue.clearAll())
        assertEquals(before.factOutbox, f.storage.state.factOutbox)
    }

    @Test fun silentDecisionDurablyStagesExactClaimWithoutUserSpeech() {
        val f = Fixture()
        val work = f.claim()
        f.complete(work)
        val batch = f.storage.state.factOutbox.single().batch
        assertEquals(work.receipt.id, batch.batchId)
        assertEquals("source_${work.receipt.id}", batch.sourceRef)
        assertEquals(work.notification.sourceSequence, batch.sourceRevision)
        assertEquals(work.notification.observedAtEpochMillis, batch.observedAtEpochMillis)
        assertEquals(listOf(candidate()), batch.validatedCandidates)
        assertEquals(f.archive.token, batch.token)
        assertTrue(f.queue.userSuggestions().isEmpty())
        assertEquals("", f.storage.state.records.single().envelope.text)
        assertNull(f.storage.state.records.single().factCaptureToken)
        assertTrue(f.archive.committed.isEmpty())
    }

    @Test fun processRecreationCommitsExistingOutboxWithoutAnotherModelCall() {
        val f = Fixture()
        f.complete(f.claim())
        val recreated = f.newQueue()
        val processor = NotificationTriageProcessor(
            recreated, { error("must_not_reclassify") }, { error("must_not_speak") },
        )
        assertEquals(NotificationTriageStepResult.PROGRESSED, processor.processOne())
        assertEquals(1, f.archive.committed.size)
        assertTrue(f.storage.state.factOutbox.isEmpty())
        assertEquals(NotificationTriageStepResult.IDLE, processor.processOne())
    }

    @Test fun crashAfterDatabaseCommitBeforeOutboxAckReplaysIdenticalBatch() {
        val f = Fixture()
        f.complete(f.claim())
        val batch = f.storage.state.factOutbox.single().batch
        f.storage.failNextWrite = true
        assertThrows(IllegalStateException::class.java) { f.queue.drainNextFactOutbox() }
        assertEquals(batch, f.storage.state.factOutbox.single().batch)
        assertEquals(1, f.archive.committed.size)
        assertEquals(NotificationFactOutboxDrainResult.PROGRESSED, f.newQueue().drainNextFactOutbox())
        assertEquals(2, f.archive.commitCalls)
        assertEquals(1, f.archive.committed.size)
        assertTrue(f.storage.state.factOutbox.isEmpty())
    }

    @Test fun failedAtomicDecisionWriteCannotLeaveAnArchiveBatchWithoutAReceipt() {
        val f = Fixture()
        val work = f.claim()
        f.storage.failNextWrite = true
        assertThrows(IllegalStateException::class.java) { f.complete(work) }
        assertTrue(f.storage.state.factOutbox.isEmpty())
        assertTrue(f.archive.committed.isEmpty())
        assertEquals(NotificationDeliveryState.RESTRICTED_TRIAGE_IN_PROGRESS,
            f.storage.state.records.single().state)
        f.complete(work)
        assertEquals(1, f.storage.state.factOutbox.size)
    }

    @Test fun globalForgetDuringInferenceCannotAcquireAFreshGenerationForOldText() {
        val f = Fixture()
        val work = f.claim()
        f.archive.token = f.archive.token.copy(allGeneration = 1)
        f.complete(work)
        assertTrue(f.storage.state.factOutbox.isEmpty())
    }

    @Test fun sourceForgetDuringInferenceBlocksRestagingWithoutRevokingOtherSources() {
        val f = Fixture()
        f.complete(f.claim(1))
        val retained = f.storage.state.factOutbox.single().batch
        val work = f.claim(2)
        f.archive.forgottenSources += "source_${work.receipt.id}"
        f.complete(work)
        assertEquals(listOf(retained), f.storage.state.factOutbox.map { it.batch })
        assertEquals(NotificationFactOutboxDrainResult.PROGRESSED, f.queue.drainNextFactOutbox())
        assertEquals(retained, f.archive.committed.values.single())
    }

    @Test fun packageExclusionDuringInferenceInvalidatesTheOldPackageGeneration() {
        val f = Fixture()
        val work = f.claim()
        f.archive.token = f.archive.token.copy(packageGeneration = 1)
        f.complete(work)
        assertTrue(f.storage.state.factOutbox.isEmpty())
    }

    @Test fun unavailableCaptureCannotBeRetroactivelyAuthorizedAfterModelReturns() {
        val f = Fixture()
        f.archive.captureAvailable = false
        val work = f.claim()
        f.archive.captureAvailable = true
        f.complete(work)
        assertTrue(f.storage.state.factOutbox.isEmpty())
    }

    @Test fun corruptOrForeignSpanAndHashAreRejectedIndependentlyOfTheSpeechDecision() {
        for (invalid in listOf(candidate().copy(sourceSha256 = "0".repeat(64)),
            candidate().copy(startUtf16 = 1, endUtf16 = TEXT.length + 1))) {
            val f = Fixture()
            val work = f.claim()
            f.queue.completeRestrictedTriage(work.receipt.id, work.claimToken,
                RestrictedTriageDecision.SuggestUser(
                    UserFacingNotificationSuggestion("Wichtiger Termin.", NotificationUrgency.HIGH),
                    listOf(invalid)))
            assertTrue(f.storage.state.factOutbox.isEmpty())
            assertEquals(1, f.queue.userSuggestions().size)
        }
    }

    @Test fun explicitSourceForgetDropsOnlyTheMatchingValidatedBatch() {
        val f = Fixture()
        f.complete(f.claim(1))
        f.complete(f.claim(2))
        val first = f.storage.state.factOutbox.first().batch
        val second = f.storage.state.factOutbox.last().batch
        assertTrue(f.queue.purgeFactOutbox(NotificationFactPrivacyScope.Source(
            first.packageName, first.sourceRef)))
        assertEquals(listOf(second), f.storage.state.factOutbox.map { it.batch })
    }

    @Test fun factForgetDropsWholeBatchWithoutRewritingAnIdempotencyPayload() {
        val f = Fixture()
        f.complete(f.claim())
        val batch = f.storage.state.factOutbox.single().batch
        val factId = NotificationFactIds.forCandidate(batch.packageName, batch.sourceRef, candidate())
        assertTrue(f.queue.purgeFactOutbox(NotificationFactPrivacyScope.Fact(factId, 1)))
        assertTrue(f.storage.state.factOutbox.isEmpty())
        assertEquals(1, f.storage.state.records.size)
    }

    @Test fun packageForgetDoesNotDeleteAnotherPackagesPendingClaims() {
        val f = Fixture()
        f.complete(f.claim(1))
        f.complete(f.claim(2, "com.example.other"))
        assertTrue(f.queue.purgeFactOutbox(NotificationFactPrivacyScope.Package(PACKAGE)))
        assertEquals("com.example.other", f.storage.state.factOutbox.single().batch.packageName)
    }

    @Test fun rawHistoryClearAndNotificationDismissalNeverMeanForget() {
        val f = Fixture()
        f.complete(f.claim())
        val batch = f.storage.state.factOutbox.single().batch
        f.queue.cancelOutstanding(PACKAGE, "key-1")
        assertTrue(f.queue.clearAll())
        assertTrue(f.storage.state.records.isEmpty())
        assertEquals(batch, f.storage.state.factOutbox.single().batch)
        assertEquals(NotificationFactOutboxDrainResult.PROGRESSED, f.queue.drainNextFactOutbox())
    }

    @Test fun cancelledOrSupersededInFlightLeaseCannotArchive() {
        for (remove in listOf(true, false)) {
            val f = Fixture()
            val work = f.claim()
            if (remove) f.queue.cancelOutstanding(PACKAGE, "key-1")
            else f.queue.ingest(event(2).copy(snapshot = event(2).snapshot.copy(androidKey = "key-1")))
            assertEquals(TriageCompletionResult.MissingOrExpiredLease, f.complete(work))
            assertTrue(f.storage.state.factOutbox.isEmpty())
        }
    }

    @Test fun staleTokenAtDrainIsAcknowledgedAsObsoleteNotStoredOrResurrected() {
        val f = Fixture()
        f.complete(f.claim())
        f.archive.token = f.archive.token.copy(packageGeneration = 1)
        assertEquals(NotificationFactOutboxDrainResult.PROGRESSED, f.queue.drainNextFactOutbox())
        assertTrue(f.archive.committed.isEmpty())
        assertTrue(f.storage.state.factOutbox.isEmpty())
    }

    @Test fun archiveOutageDefersOnlyTheLocalCommitAndDoesNotReclassifyOrSpeak() {
        val f = Fixture()
        f.complete(f.claim())
        f.archive.nextResult = NotificationFactCommitResult.Unavailable(NotificationFactUnavailableReason.IO_FAILURE)
        val processor = NotificationTriageProcessor(f.queue, { error("must_not_reclassify") },
            { error("must_not_speak") })
        assertEquals(NotificationTriageStepResult.PROGRESSED, processor.processOne())
        assertEquals(NotificationTriageStepResult.IDLE, processor.processOne())
        assertEquals(1, f.archive.commitCalls)
        val pending = f.storage.state.factOutbox.single()
        assertEquals(1, pending.attempts)
        assertEquals(f.now + NotificationTriageRetryPolicy.delayMillis(1), pending.nextAttemptAtEpochMillis)
        assertEquals(pending.nextAttemptAtEpochMillis, f.queue.nextFactOutboxRecoveryAtEpochMillis())
        f.now = pending.nextAttemptAtEpochMillis
        f.archive.nextResult = null
        assertEquals(NotificationTriageStepResult.PROGRESSED, processor.processOne())
        assertTrue(f.storage.state.factOutbox.isEmpty())
    }

    @Test fun validUrgentDeliveryHasPriorityOverAFailingArchive() {
        val f = Fixture()
        val work = f.claim()
        f.queue.completeRestrictedTriage(work.receipt.id, work.claimToken,
            RestrictedTriageDecision.SuggestUser(
                UserFacingNotificationSuggestion("Termin beginnt bald.", NotificationUrgency.HIGH),
                listOf(candidate())))
        f.archive.nextResult = NotificationFactCommitResult.CapacityExceeded
        var delivered = 0
        val processor = NotificationTriageProcessor(f.queue, { error("must_not_reclassify") }, {
            delivered += 1
            UserFacingDeliveryDisposition.ACCEPTED
        })
        assertEquals(NotificationTriageStepResult.PROGRESSED, processor.processOne())
        assertEquals(1, delivered)
        assertEquals(0, f.archive.commitCalls)
        assertEquals(NotificationDeliveryState.DELIVERED_TO_USER, f.queue.receipts().single().state)
    }

    @Test fun foregroundWorkPreemptsEvenArchiveDrain() {
        val f = Fixture()
        f.complete(f.claim())
        val processor = NotificationTriageProcessor(f.queue, { error("must_not_run") },
            { error("must_not_run") }, processingPermit = { false })
        assertEquals(NotificationTriageStepResult.PAUSED_FOR_INTERACTION, processor.processOne())
        assertEquals(0, f.archive.commitCalls)
    }

    @Test fun slowArchiveFailureBacksOffFromCompletionAndCannotStarveNewClassification() {
        val f = Fixture()
        f.complete(f.claim(1))
        f.queue.ingest(event(2))
        f.archive.beforeCommit = { f.now += 20_000 }
        f.archive.nextResult = NotificationFactCommitResult.CapacityExceeded
        var classified = 0
        val processor = NotificationTriageProcessor(f.queue, {
            classified++
            RestrictedTriageDecision.NotRelevant(NotificationDismissalReason.NOT_ACTIONABLE)
        }, { error("must_not_speak") })
        assertEquals(NotificationTriageStepResult.PROGRESSED, processor.processOne())
        assertEquals(f.now + NotificationTriageRetryPolicy.delayMillis(1),
            f.storage.state.factOutbox.single().nextAttemptAtEpochMillis)
        assertEquals(NotificationTriageStepResult.PROGRESSED, processor.processOne())
        assertEquals(1, classified)
        assertEquals(1, f.archive.commitCalls)
    }

    @Test fun fortyEightSlowFailingBatchesYieldToReadyClassificationWithinTwoSlices() {
        val f = Fixture()
        val originalBatches = f.seedOutbox(48)
        f.queue.ingest(event(2))
        f.archive.beforeCommit = { f.now += 20_000 }
        f.archive.nextResult = NotificationFactCommitResult.CapacityExceeded
        val classified = mutableListOf<Long>()
        val processor = NotificationTriageProcessor(f.queue, {
            classified += it.notification.sourceSequence
            RestrictedTriageDecision.NotRelevant(NotificationDismissalReason.NOT_ACTIONABLE)
        }, { error("must_not_speak") })

        assertEquals(NotificationTriageStepResult.PROGRESSED, processor.processOne())
        assertEquals(1, f.archive.commitCalls)
        assertTrue(classified.isEmpty())
        assertEquals(NotificationTriageStepResult.PROGRESSED, processor.processOne())
        assertEquals(listOf(2L), classified)
        assertEquals(1, f.archive.commitCalls)

        // A continuously arriving classification must also yield back to the archive lane.
        f.queue.ingest(event(3))
        assertEquals(NotificationTriageStepResult.PROGRESSED, processor.processOne())
        assertEquals(2, f.archive.commitCalls)
        assertEquals(listOf(2L), classified)
        assertEquals(NotificationTriageStepResult.PROGRESSED, processor.processOne())
        assertEquals(listOf(2L, 3L), classified)
        assertEquals(2, f.archive.commitCalls)
        assertEquals(originalBatches, f.storage.state.factOutbox.map { it.batch })
    }

    @Test fun slowRetriesCannotRevisitTheListHeadBeforeEveryOlderDueBatch() {
        val f = Fixture()
        val originalBatches = f.seedOutbox(48)
        f.archive.beforeCommit = { f.now += 20_000 }
        f.archive.nextResult = NotificationFactCommitResult.CapacityExceeded

        repeat(originalBatches.size) {
            assertEquals(NotificationFactOutboxDrainResult.DEFERRED, f.queue.drainNextFactOutbox())
        }
        assertEquals(originalBatches.map { it.batchId }, f.archive.attemptedBatchIds)
        assertTrue(f.storage.state.factOutbox.all { it.attempts == 1 })
        assertEquals(originalBatches, f.storage.state.factOutbox.map { it.batch })

        assertEquals(NotificationFactOutboxDrainResult.DEFERRED, f.queue.drainNextFactOutbox())
        assertEquals(originalBatches.first().batchId, f.archive.attemptedBatchIds.last())
        assertEquals(2, f.storage.state.factOutbox.first().attempts)
    }

    @Test fun oldestDueDeadlineWinsWhileEqualDeadlinesKeepDurableOrder() {
        val f = Fixture()
        val batches = f.seedOutbox(3)
        f.storage.state = f.storage.state.copy(factOutbox =
            f.storage.state.factOutbox.mapIndexed { index, entry ->
                entry.copy(nextAttemptAtEpochMillis = if (index == 0) 900 else 800)
            })
        repeat(3) {
            assertEquals(NotificationFactOutboxDrainResult.PROGRESSED, f.queue.drainNextFactOutbox())
        }
        assertEquals(listOf(batches[1].batchId, batches[2].batchId, batches[0].batchId),
            f.archive.attemptedBatchIds)
        assertTrue(f.storage.state.factOutbox.isEmpty())
    }

    @Test fun validatedDeliveryStillPreemptsBothLanesAfterAnArchiveAttempt() {
        val f = Fixture()
        f.seedOutbox(48)
        f.archive.beforeCommit = { f.now += 20_000 }
        f.archive.nextResult = NotificationFactCommitResult.CapacityExceeded
        var classified = 0
        var delivered = 0
        val processor = NotificationTriageProcessor(f.queue, {
            classified++
            RestrictedTriageDecision.NotRelevant(NotificationDismissalReason.NOT_ACTIONABLE)
        }, {
            delivered++
            UserFacingDeliveryDisposition.ACCEPTED
        })
        assertEquals(NotificationTriageStepResult.PROGRESSED, processor.processOne())
        val urgent = f.claim(2)
        f.queue.completeRestrictedTriage(urgent.receipt.id, urgent.claimToken,
            RestrictedTriageDecision.SuggestUser(
                UserFacingNotificationSuggestion("Termin beginnt bald.", NotificationUrgency.HIGH)))
        f.queue.ingest(event(3))

        assertEquals(NotificationTriageStepResult.PROGRESSED, processor.processOne())
        assertEquals(1, delivered)
        assertEquals(0, classified)
        assertEquals(1, f.archive.commitCalls)
        assertEquals(NotificationDeliveryState.DELIVERED_TO_USER,
            f.queue.receipts().single { it.id == urgent.receipt.id }.state)
        assertEquals(NotificationTriageStepResult.PROGRESSED, processor.processOne())
        assertEquals(1, classified)
        assertEquals(1, delivered)
        assertEquals(1, f.archive.commitCalls)
    }

    @Test fun noNewClassificationDrainsDueBatchesThenStaysIdleUntilTheirDeadline() {
        val f = Fixture()
        val originalBatches = f.seedOutbox(3)
        f.archive.nextResult = NotificationFactCommitResult.CapacityExceeded
        val processor = NotificationTriageProcessor(f.queue, { error("must_not_reclassify") },
            { error("must_not_speak") })

        repeat(3) {
            assertEquals(NotificationTriageStepResult.PROGRESSED, processor.processOne())
        }
        assertEquals(originalBatches.map { it.batchId }, f.archive.attemptedBatchIds)
        val deadline = f.now + NotificationTriageRetryPolicy.delayMillis(1)
        assertEquals(deadline, f.queue.nextFactOutboxRecoveryAtEpochMillis())
        assertEquals(NotificationTriageStepResult.IDLE, processor.processOne())
        f.now = deadline - 1
        assertEquals(NotificationTriageStepResult.IDLE, processor.processOne())
        assertEquals(3, f.archive.commitCalls)

        f.now = deadline
        f.archive.nextResult = null
        repeat(3) {
            assertEquals(NotificationTriageStepResult.PROGRESSED, processor.processOne())
        }
        assertEquals(6, f.archive.commitCalls)
        assertEquals(3, f.archive.committed.size)
        assertEquals(NotificationTriageStepResult.IDLE, processor.processOne())
        assertNull(f.queue.nextFactOutboxRecoveryAtEpochMillis())
        assertNull(f.queue.nextRestrictedTriageEligibilityAtEpochMillis())
        assertTrue(f.storage.state.factOutbox.isEmpty())
    }

    @Test fun fairClassificationOpportunityStillHonorsReadinessAndRollingBudget() {
        val f = Fixture()
        f.seedOutbox(3)
        f.queue.ingest(event(2))
        f.archive.nextResult = NotificationFactCommitResult.CapacityExceeded
        var permitted = true
        var classified = 0
        val processor = NotificationTriageProcessor(f.queue, {
            classified++
            RestrictedTriageDecision.NotRelevant(NotificationDismissalReason.NOT_ACTIONABLE)
        }, { error("must_not_speak") }, processingPermit = { permitted })
        assertEquals(NotificationTriageStepResult.PROGRESSED, processor.processOne())

        permitted = false
        val beforePause = f.storage.state
        assertEquals(NotificationTriageStepResult.PAUSED_FOR_INTERACTION, processor.processOne())
        assertEquals(beforePause, f.storage.state)
        assertEquals(1, f.archive.commitCalls)
        permitted = true
        f.storage.state = f.storage.state.copy(recentTriageClaimEpochMillis =
            List(NotificationTriageBounds.MAX_TRIAGE_CALLS_PER_BUDGET_WINDOW) { f.now })
        assertEquals(NotificationTriageStepResult.PROGRESSED, processor.processOne())
        assertEquals(0, classified)
        assertEquals(2, f.archive.commitCalls)
        assertEquals(NotificationDeliveryState.PENDING_RESTRICTED_TRIAGE,
            f.queue.receipts().single { it.sourceSequence == 2L }.state)

        f.now += NotificationTriageBounds.GLOBAL_TRIAGE_BUDGET_WINDOW_MILLIS
        assertEquals(NotificationTriageStepResult.PROGRESSED, processor.processOne())
        assertEquals(1, classified)
        assertEquals(2, f.archive.commitCalls)
    }

    @Test fun conflictingBatchIsNeverAcknowledgedAndCapacityLossIsExplicitAndBounded() {
        val f = Fixture()
        f.complete(f.claim())
        f.archive.nextResult = NotificationFactCommitResult.Rejected(NotificationFactCommitResult.Reason.ID_CONFLICT)
        assertEquals(NotificationFactOutboxDrainResult.DEFERRED, f.queue.drainNextFactOutbox())
        assertEquals(1, f.storage.state.factOutbox.size)
        val template = f.storage.state.factOutbox.single()
        f.storage.state = f.storage.state.copy(factOutbox = List(NotificationFactOutboxCodec.MAX_ITEMS) {
            template.copy(batch = template.batch.copy(batchId = "batch_$it", sourceRef = "source_$it"))
        }, factOutboxCapacityDrops = Long.MAX_VALUE)
        f.complete(f.claim(2))
        assertEquals(NotificationFactOutboxCodec.MAX_ITEMS, f.storage.state.factOutbox.size)
        assertEquals(Long.MAX_VALUE, (f.queue.health() as NotificationTriageQueueHealth.Available).factOutboxCapacityDrops)
    }

    @Test fun duplicateOutboxIdentityMakesHealthUnavailableRatherThanDroppingAClaim() {
        val f = Fixture()
        f.complete(f.claim())
        val one = f.storage.state.factOutbox.single()
        f.storage.state = f.storage.state.copy(factOutbox = listOf(one, one))
        assertEquals(NotificationTriageQueueHealth.Unavailable, f.queue.health())
    }

    private class Fixture {
        var now = 1_000L
        val storage = MemoryStorage()
        val archive = FakeArchive()
        val queue = newQueue()
        fun newQueue() = NotificationTriageQueue(storage,
            HansNotificationExclusionPolicy(setOf("ai.hans.standard")), { now }, archive)
        fun claim(sequence: Long = 1, packageName: String = PACKAGE): RestrictedTriageWorkItem {
            queue.ingest(event(sequence, packageName))
            return requireNotNull(queue.claimNextRestrictedTriage())
        }
        fun complete(work: RestrictedTriageWorkItem) = queue.completeRestrictedTriage(
            work.receipt.id, work.claimToken,
            RestrictedTriageDecision.NotRelevant(NotificationDismissalReason.NOT_ACTIONABLE, listOf(candidate())),
        )
        fun seedOutbox(count: Int): List<NotificationFactBatch> {
            require(count in 1..NotificationFactOutboxCodec.MAX_ITEMS)
            complete(claim())
            val template = storage.state.factOutbox.single()
            storage.state = storage.state.copy(factOutbox = List(count) { index ->
                template.copy(batch = template.batch.copy(
                    batchId = "batch_$index", sourceRef = "source_$index"))
            })
            return storage.state.factOutbox.map { it.batch }
        }
    }

    private class MemoryStorage : NotificationTriageStorage {
        var state = NotificationTriageQueueState(emptyList())
        var failNextWrite = false
        override fun read() = state
        override fun write(state: NotificationTriageQueueState) {
            if (failNextWrite) { failNextWrite = false; error("synthetic_atomic_write_failure") }
            this.state = state.normalize()
        }
    }

    private class FakeArchive : NotificationFactRepository {
        var token = NotificationArchiveCaptureToken("00000000-0000-4000-8000-000000000001", 0, 0)
        var captureAvailable = true
        var commitCalls = 0
        val attemptedBatchIds = mutableListOf<String>()
        var nextResult: NotificationFactCommitResult? = null
        var beforeCommit: () -> Unit = {}
        val committed = linkedMapOf<String, NotificationFactBatch>()
        val forgottenSources = mutableSetOf<String>()
        override fun captureToken(packageName: String) = token.takeIf { captureAvailable }
        override fun canStage(batch: NotificationFactBatch) = captureAvailable &&
            batch.token == token && batch.sourceRef !in forgottenSources
        override fun commit(batch: NotificationFactBatch): NotificationFactCommitResult {
            commitCalls++
            attemptedBatchIds += batch.batchId
            beforeCommit()
            nextResult?.let { return it }
            if (batch.token != token) return NotificationFactCommitResult.Rejected(NotificationFactCommitResult.Reason.STALE_TOKEN)
            val ids = batch.validatedCandidates.map { NotificationFactIds.forCandidate(batch.packageName, batch.sourceRef, it) }
            val old = committed[batch.batchId]
            if (old != null) {
                check(old == batch)
                return NotificationFactCommitResult.Replay(ids)
            }
            committed[batch.batchId] = batch
            return NotificationFactCommitResult.Stored(ids)
        }
        override fun query(query: NotificationFactQuery): NotificationFactQueryResult = error("unused")
        override fun correct(correction: NotificationFactCorrection): NotificationFactCorrectionResult = error("unused")
        override fun beginPrivacy(request: NotificationFactPrivacyRequest): NotificationFactPrivacyBeginResult = error("unused")
        override fun pendingIntents(): List<NotificationFactPrivacyIntent> = emptyList()
        override fun acknowledgePrivacy(intent: NotificationFactPrivacyIntent) = false
        override fun health() = NotificationFactArchiveHealth(true, null, committed.size, 0, 0, false, NotificationFactCapacity())
        override fun close() = Unit
    }

    private companion object {
        const val PACKAGE = "com.example.chat"
        const val TEXT = "We meet every Tuesday after work."
        fun candidate() = NotificationMemoryCandidate(NotificationMemoryKind.EVENT_DETAIL,
            NotificationMemorySourceField.TEXT, TEXT, 0, TEXT.length,
            MessageDigest.getInstance("SHA-256").digest(TEXT.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) })
        fun event(sequence: Long, packageName: String = PACKAGE) = NotificationInboxEvent(
            sequence, NotificationEventKind.POSTED, 100, null,
            NotificationSnapshot(packageName, "key-$sequence", 10, 10, "Friend", TEXT,
                "", "message", "messages", false, true, emptyList()),
        )
    }
}
