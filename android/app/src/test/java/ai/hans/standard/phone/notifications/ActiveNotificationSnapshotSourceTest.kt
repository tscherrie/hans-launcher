package ai.hans.standard.phone.notifications

import ai.hans.standard.notifications.NotificationTriageQueueHealth
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveNotificationSnapshotSourceTest {
    @Test
    fun readinessProbeDoesNotTakeGenerationMonitorWhileReadyPublicationIsBlocked() {
        val gate = NotificationAuthoritativeSnapshotGate()
        val lease = gate.beginReconciliation()
        val publicationEntered = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val probeFinished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val probeValue = AtomicReference<Boolean>()
        val committed = AtomicReference<Boolean>()
        val publisher = Thread {
            try {
                committed.set(gate.commitReady(lease) {
                    publicationEntered.countDown()
                    check(releasePublication.await(3, TimeUnit.SECONDS))
                    true
                })
            } catch (error: Throwable) { failure.set(error) }
        }.apply { isDaemon = true }
        val reader = Thread {
            try {
                // Models the host probing authority while holding its unrelated dispatch lock.
                synchronized(Any()) { probeValue.set(gate.isReady()) }
            } catch (error: Throwable) { failure.set(error) }
            finally { probeFinished.countDown() }
        }.apply { isDaemon = true }
        publisher.start()
        try {
            assertTrue(publicationEntered.await(2, TimeUnit.SECONDS))
            reader.start()
            assertTrue("Readiness probe waited on the generation/publication monitor",
                probeFinished.await(1, TimeUnit.SECONDS))
            assertEquals(false, probeValue.get())
        } finally {
            releasePublication.countDown()
            publisher.join(2_000)
            reader.join(2_000)
        }
        assertNull(failure.get())
        assertEquals(true, committed.get())
        assertTrue(gate.isReady())
        gate.quarantine()
        assertFalse(gate.isReady())
    }

    @Test
    fun lockFreePublicationRemainsGenerationBoundAfterQuarantineOrFailedCommit() {
        val gate = NotificationAuthoritativeSnapshotGate()
        val stale = gate.beginReconciliation()
        gate.quarantine()
        val current = gate.beginReconciliation()
        assertFalse(gate.commitReady(current) { false })
        assertFalse(gate.isReady())
        assertTrue(gate.commitReady(current) { true })
        gate.keepQuarantinedIfCurrent(stale)
        assertTrue(gate.isReady())
        gate.keepQuarantinedIfCurrent(current)
        assertFalse(gate.isReady())
        val interrupted = gate.beginReconciliation()
        assertFalse(gate.commitReady(interrupted) { gate.quarantine(); true })
        assertFalse(gate.isReady())
    }

    @Test
    fun transientRepairPreservesValidatedFactOutboxAndCapacityCounters() {
        var health = NotificationTriageQueueHealth.Available(
            recordCount = 3, factOutboxCount = 2, factOutboxCapacityDrops = 7,
        )
        val fence = FakePrivacyPurgeFence(required = true)
        assertTrue(ensureNotificationQueueAndCenterHealthyBeforeReady(
            queueHealth = { health },
            privacyFence = fence,
            preempt = {},
            repairQueueToVerifiedEmpty = {
                health = health.copy(recordCount = 0)
                true
            },
            clearValidatedCenter = { true },
        ))
        assertEquals(2, health.factOutboxCount)
        assertEquals(7L, health.factOutboxCapacityDrops)
        assertFalse(fence.isRequired())
    }

    @Test
    fun remainingTransientRecordCannotBeHiddenByHealthyFactOutbox() {
        val fence = FakePrivacyPurgeFence(required = true)
        assertFalse(ensureNotificationQueueAndCenterHealthyBeforeReady(
            queueHealth = { NotificationTriageQueueHealth.Available(1, 2, 7) },
            privacyFence = fence,
            preempt = {},
            repairQueueToVerifiedEmpty = { true },
            clearValidatedCenter = { true },
        ))
        assertTrue(fence.isRequired())
    }

    @Test
    fun unavailableQueueRepairsQueueAndCenterBeforeFenceCanBecomeClean() {
        var health: NotificationTriageQueueHealth = NotificationTriageQueueHealth.Unavailable
        val fence = FakePrivacyPurgeFence(required = false)
        var preemptions = 0
        var centerClears = 0

        assertTrue(
            ensureNotificationQueueAndCenterHealthyBeforeReady(
                queueHealth = { health },
                privacyFence = fence,
                preempt = { preemptions += 1 },
                repairQueueToVerifiedEmpty = {
                    health = NotificationTriageQueueHealth.Available(0)
                    true
                },
                clearValidatedCenter = {
                    centerClears += 1
                    true
                },
            ),
        )

        assertFalse(fence.isRequired())
        assertEquals(1, preemptions)
        assertEquals(1, centerClears)
        assertEquals(NotificationTriageQueueHealth.Available(0), health)
    }

    @Test
    fun failedQueueOrCenterRepairKeepsSharedFenceClosed() {
        listOf(false to true, true to false).forEach { (queueSucceeds, centerSucceeds) ->
            var health: NotificationTriageQueueHealth = NotificationTriageQueueHealth.Unavailable
            val fence = FakePrivacyPurgeFence(required = false)

            assertFalse(
                ensureNotificationQueueAndCenterHealthyBeforeReady(
                    queueHealth = { health },
                    privacyFence = fence,
                    preempt = {},
                    repairQueueToVerifiedEmpty = {
                        if (queueSucceeds) health = NotificationTriageQueueHealth.Available(0)
                        queueSucceeds
                    },
                    clearValidatedCenter = { centerSucceeds },
                ),
            )
            assertTrue(fence.isRequired())
        }
    }

    @Test
    fun healthyQueueWithNoInterruptedPurgeDoesNotDestroyDeliveryState() {
        val fence = FakePrivacyPurgeFence(required = false)
        var destructiveCalls = 0

        assertTrue(
            ensureNotificationQueueAndCenterHealthyBeforeReady(
                queueHealth = { NotificationTriageQueueHealth.Available(7) },
                privacyFence = fence,
                preempt = { destructiveCalls += 1 },
                repairQueueToVerifiedEmpty = {
                    destructiveCalls += 1
                    false
                },
                clearValidatedCenter = {
                    destructiveCalls += 1
                    false
                },
            ),
        )
        assertEquals(0, destructiveCalls)
    }
    @Test
    fun staleBlockedReconcileCannotPublishReadyOrWakeAfterNewerQuarantine() {
        val gate = NotificationAuthoritativeSnapshotGate().also { it.markReady() }
        val reconcileEntered = CountDownLatch(1)
        val releaseReconcile = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val result = AtomicReference<Boolean>()
        val readyPublications = AtomicInteger(0)
        val wakes = AtomicInteger(0)

        val thread = Thread {
            result.set(
                reconcileAuthoritativeNotificationSnapshot(
                    gate = gate,
                    source = ActiveNotificationSnapshotSource { listOf("active") },
                    reconcile = {
                        reconcileEntered.countDown()
                        check(releaseReconcile.await(2, TimeUnit.SECONDS))
                    },
                    wake = { wakes.incrementAndGet() },
                    beforeReady = {
                        readyPublications.incrementAndGet()
                        true
                    },
                ),
            )
            finished.countDown()
        }
        thread.start()
        assertTrue(reconcileEntered.await(2, TimeUnit.SECONDS))

        gate.quarantine()
        releaseReconcile.countDown()

        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertFalse(result.get())
        assertFalse(gate.isReady())
        assertEquals(0, readyPublications.get())
        assertEquals(0, wakes.get())
    }

    @Test
    fun newerSuccessfulReconcileCanOpenOnceAfterOlderLeaseWasInvalidated() {
        val gate = NotificationAuthoritativeSnapshotGate()
        val staleLease = gate.beginReconciliation()
        gate.quarantine()
        assertFalse(gate.commitReady(staleLease) { true })

        var publications = 0
        var wakes = 0
        assertTrue(
            reconcileAuthoritativeNotificationSnapshot(
                gate = gate,
                source = ActiveNotificationSnapshotSource { listOf("latest") },
                reconcile = { assertEquals(listOf("latest"), it) },
                wake = { wakes += 1 },
                beforeReady = {
                    publications += 1
                    true
                },
            ),
        )
        assertTrue(gate.isReady())
        assertEquals(1, publications)
        assertEquals(1, wakes)
    }
    @Test
    fun nullSnapshotAbortsConnectionSyncInsteadOfPretendingInboxIsEmpty() {
        val snapshot = readRequiredActiveNotificationSnapshot(
            ActiveNotificationSnapshotSource<String> { null },
        )

        assertNull(snapshot)
    }

    @Test
    fun snapshotFailureAlsoAbortsButRealEmptySnapshotRemainsAuthoritative() {
        assertNull(
            readRequiredActiveNotificationSnapshot(
                ActiveNotificationSnapshotSource<String> { error("binder unavailable") },
            ),
        )
        assertEquals(
            emptyList<String>(),
            readRequiredActiveNotificationSnapshot<String>(
                ActiveNotificationSnapshotSource<String> { emptyList() },
            ),
        )
    }

    @Test
    fun authoritativeGateStartsAndReconnectsQuarantinedUntilSuccessfulSnapshot() {
        val gate = NotificationAuthoritativeSnapshotGate()

        assertFalse(gate.isReady())
        gate.markReady()
        assertTrue(gate.isReady())
        gate.quarantine()
        assertFalse(gate.isReady())

        assertNull(
            readRequiredActiveNotificationSnapshot(
                ActiveNotificationSnapshotSource<String> { null },
            ),
        )
        assertFalse(gate.isReady())
        requireNotNull(
            readRequiredActiveNotificationSnapshot(
                ActiveNotificationSnapshotSource { emptyList<String>() },
            ),
        )
        gate.markReady()
        assertTrue(gate.isReady())
    }

    @Test
    fun reconciliationPublishesReadyOnlyAfterLatestStateCancellationAndBeforeExactWake() {
        val gate = NotificationAuthoritativeSnapshotGate().also { it.markReady() }
        val order = mutableListOf<String>()

        assertTrue(
            reconcileAuthoritativeNotificationSnapshot(
                gate = gate,
                source = ActiveNotificationSnapshotSource { listOf("latest") },
                reconcile = { active ->
                    assertFalse(gate.isReady())
                    assertEquals(listOf("latest"), active)
                    order += "cancel-stale"
                },
                wake = {
                    assertTrue(gate.isReady())
                    order += "wake"
                },
            ),
        )

        assertEquals(listOf("cancel-stale", "wake"), order)
        assertTrue(gate.isReady())
    }

    @Test
    fun unavailableOrFailingSnapshotNeverPublishesReadyOrWakesDurableWork() {
        listOf<ActiveNotificationSnapshotSource<String>>(
            ActiveNotificationSnapshotSource { null },
            ActiveNotificationSnapshotSource { error("binder unavailable") },
        ).forEach { source ->
            val gate = NotificationAuthoritativeSnapshotGate().also { it.markReady() }
            var reconciled = false
            var woke = false

            assertFalse(
                reconcileAuthoritativeNotificationSnapshot(
                    gate = gate,
                    source = source,
                    reconcile = { reconciled = true },
                    wake = { woke = true },
                ),
            )
            assertFalse(gate.isReady())
            assertFalse(reconciled)
            assertFalse(woke)
        }
    }

    @Test
    fun laterCallbackRetriesTransientSnapshotFailureAndRunsOnlyAfterRecovery() {
        val gate = NotificationAuthoritativeSnapshotGate()
        var snapshotAvailable = false
        var reconciliations = 0
        var callbacks = 0
        val reconcile = {
            reconcileAuthoritativeNotificationSnapshot(
                gate = gate,
                source = ActiveNotificationSnapshotSource {
                    if (snapshotAvailable) emptyList<String>() else null
                },
                reconcile = { reconciliations += 1 },
                wake = {},
            )
        }

        assertFalse(
            runNotificationCallbackWithAuthoritativeSnapshot(
                gate = gate,
                reconcile = reconcile,
                callback = { callbacks += 1 },
            ),
        )
        assertFalse(gate.isReady())
        assertEquals(0, reconciliations)
        assertEquals(0, callbacks)

        snapshotAvailable = true
        assertTrue(
            runNotificationCallbackWithAuthoritativeSnapshot(
                gate = gate,
                reconcile = reconcile,
                callback = {
                    assertTrue(gate.isReady())
                    callbacks += 1
                },
            ),
        )
        assertTrue(gate.isReady())
        assertEquals(1, reconciliations)
        assertEquals(1, callbacks)
    }

    @Test
    fun unavailablePrivacyPolicyQuarantinesPreemptsAndPurgesBeforeAnyFurtherDelivery() {
        val gate = NotificationAuthoritativeSnapshotGate().also { it.markReady() }
        val fence = FakePrivacyPurgeFence(required = false)
        var preempted = false
        var purged = false

        assertFalse(
            enforceNotificationCapturePolicyAvailability(
                policyAvailable = false,
                gate = gate,
                purgeFence = fence,
                preempt = { preempted = true },
                purge = {
                    purged = true
                    false // Storage failure must not reopen the gate.
                },
            ),
        )

        assertFalse(gate.isReady())
        assertTrue(preempted)
        assertTrue(purged)
        assertTrue(fence.isRequired())
    }

    @Test
    fun failedPrivacyPurgeSurvivesRepairAndReopenUntilACompleteRetrySucceeds() {
        val durableState = FakePrivacyPurgeState(required = false)
        val firstProcessFence = FakePrivacyPurgeFence(durableState)
        val firstGate = NotificationAuthoritativeSnapshotGate().also { it.markReady() }

        assertFalse(
            enforceNotificationCapturePolicyAvailability(
                policyAvailable = false,
                gate = firstGate,
                purgeFence = firstProcessFence,
                preempt = {},
                purge = { false },
            ),
        )
        assertTrue(durableState.required)

        // A new object models process recreation after the policy document was repaired.
        val reopenedFence = FakePrivacyPurgeFence(durableState)
        val repairedGate = NotificationAuthoritativeSnapshotGate()
        var purgeAttempts = 0
        assertFalse(
            enforceNotificationCapturePolicyAvailability(
                policyAvailable = true,
                gate = repairedGate,
                purgeFence = reopenedFence,
                preempt = {},
                purge = {
                    purgeAttempts += 1
                    false
                },
            ),
        )
        assertTrue(reopenedFence.isRequired())
        assertFalse(repairedGate.isReady())

        assertTrue(
            enforceNotificationCapturePolicyAvailability(
                policyAvailable = true,
                gate = repairedGate,
                purgeFence = reopenedFence,
                preempt = {},
                purge = {
                    purgeAttempts += 1
                    true
                },
            ),
        )
        assertFalse(reopenedFence.isRequired())
        assertEquals(2, purgeAttempts)
    }

    @Test
    fun policyBecomingUnavailableOnTheTypedCaptureReadQuarantinesAndPurges() {
        val gate = NotificationAuthoritativeSnapshotGate().also { it.markReady() }
        val fence = FakePrivacyPurgeFence(required = false)
        var preemptions = 0
        var purges = 0

        assertFalse(
            enforceNotificationCaptureDecision(
                decision = NotificationCaptureDecision.PolicyUnavailable,
                gate = gate,
                purgeFence = fence,
                preempt = { preemptions += 1 },
                purge = {
                    purges += 1
                    true
                },
            ),
        )

        assertFalse(gate.isReady())
        assertTrue(fence.isRequired())
        assertEquals(1, preemptions)
        assertEquals(1, purges)
    }

    @Test
    fun callbackStorageFailureQuarantinesAndPreemptsUntilSnapshotReconciliation() {
        val gate = NotificationAuthoritativeSnapshotGate().also { it.markReady() }
        var preemptions = 0

        assertFalse(
            runNotificationCallbackWithAuthoritativeSnapshot(
                gate = gate,
                reconcile = { error("already ready") },
                onFailure = { preemptions += 1 },
                callback = { error("notification_storage_failure") },
            ),
        )

        assertFalse(gate.isReady())
        assertEquals(1, preemptions)
    }

    @Test
    fun durablePrivacyFenceBlocksRestrictedModelProcessingEvenWhenSnapshotIsReadyAndIdle() {
        assertFalse(
            notificationProcessingPermitted(
                authoritativeSnapshotReady = true,
                interactiveIdle = true,
                privacyPurgeRequired = true,
            ),
        )
        assertFalse(
            notificationProcessingPermitted(
                authoritativeSnapshotReady = false,
                interactiveIdle = true,
                privacyPurgeRequired = false,
            ),
        )
        assertFalse(
            notificationProcessingPermitted(
                authoritativeSnapshotReady = true,
                interactiveIdle = false,
                privacyPurgeRequired = false,
            ),
        )
        assertTrue(
            notificationProcessingPermitted(
                authoritativeSnapshotReady = true,
                interactiveIdle = true,
                privacyPurgeRequired = false,
            ),
        )
    }

    private data class FakePrivacyPurgeState(var required: Boolean)

    private class FakePrivacyPurgeFence(
        private val state: FakePrivacyPurgeState,
    ) : NotificationPrivacyPurgeFence {
        constructor(required: Boolean) : this(FakePrivacyPurgeState(required))

        override fun isRequired(): Boolean = state.required
        override fun markRequired(): Boolean {
            state.required = true
            return true
        }

        override fun markClean(): Boolean {
            state.required = false
            return true
        }
    }
}
