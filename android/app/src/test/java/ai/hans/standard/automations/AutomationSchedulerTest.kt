package ai.hans.standard.automations

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationSchedulerTest {
    @Test
    fun missedRunPoliciesSkipRunLatestOrBoundCatchUpDeterministically() {
        val now = Instant.parse("2026-01-05T12:00:00Z")

        val skip = reconcileWithPolicy(MissedRunMode.SKIP, now, maximumCatchUp = 2)
        assertEquals(5, skip.report.occurrencesFound)
        assertEquals(0, skip.report.occurrencesSelected)
        assertTrue(skip.storage.snapshot().inbox.isEmpty())

        val latest = reconcileWithPolicy(MissedRunMode.RUN_LATEST, now, maximumCatchUp = 2)
        assertEquals(1, latest.report.occurrencesSelected)
        assertEquals(
            listOf(Instant.parse("2026-01-05T09:00:00Z")),
            latest.storage.snapshot().inbox.map { it.key.scheduledAt },
        )

        val catchUp = reconcileWithPolicy(MissedRunMode.CATCH_UP, now, maximumCatchUp = 2)
        assertEquals(2, catchUp.report.occurrencesSelected)
        assertEquals(
            listOf(
                Instant.parse("2026-01-04T09:00:00Z"),
                Instant.parse("2026-01-05T09:00:00Z"),
            ),
            catchUp.storage.snapshot().inbox.map { it.key.scheduledAt },
        )
    }

    @Test
    fun skipPolicyStillRunsAnOccurrenceInsideItsGraceWindow() {
        val storage = InMemoryAutomationStorage()
        val definition = testDefinition(
            missedRunPolicy = MissedRunPolicy(
                mode = MissedRunMode.SKIP,
                gracePeriod = Duration.ofMinutes(5),
            ),
        )
        storage.upsertDefinition(definition)
        val report = AutomationScheduler(storage).reconcile(
            now = Instant.parse("2026-01-01T09:02:00Z"),
            systemZone = ZoneId.of("UTC"),
            source = AutomationDiscoverySource.TIMER,
        )

        assertEquals(1, report.occurrencesSelected)
        assertEquals(
            Instant.parse("2026-01-01T09:00:00Z"),
            storage.snapshot().inbox.single().key.scheduledAt,
        )
    }

    @Test
    fun offlineRecoveryUsesDurableCursorAndDoesNotReplayEveryMissedRun() {
        val storage = InMemoryAutomationStorage()
        val definition = testDefinition(
            missedRunPolicy = MissedRunPolicy(
                mode = MissedRunMode.RUN_LATEST,
                gracePeriod = Duration.ofMinutes(5),
            ),
        )
        storage.upsertDefinition(definition)
        val scheduler = AutomationScheduler(storage)
        val firstNow = Instant.parse("2026-01-01T09:01:00Z")
        scheduler.reconcile(firstNow, ZoneId.of("UTC"), AutomationDiscoverySource.TIMER)
        assertEquals(firstNow, storage.schedulerCursor(definition.id)?.evaluatedThrough)

        val restored = InMemoryAutomationStorage(storage.snapshot())
        val recoveryNow = Instant.parse("2026-01-04T12:00:00Z")
        val report = AutomationScheduler(restored).reconcile(
            recoveryNow,
            ZoneId.of("UTC"),
            AutomationDiscoverySource.OFFLINE_RECOVERY,
        )

        assertEquals(3, report.occurrencesFound)
        assertEquals(1, report.occurrencesSelected)
        assertEquals(1, report.inboxItemsInserted)
        assertEquals(
            listOf(
                Instant.parse("2026-01-01T09:00:00Z"),
                Instant.parse("2026-01-04T09:00:00Z"),
            ),
            restored.snapshot().inbox.map { it.key.scheduledAt },
        )
        assertEquals(recoveryNow, restored.schedulerCursor(definition.id)?.evaluatedThrough)
    }

    @Test
    fun discoveryCursorAndUniqueRunKeyMakeRescheduleReplayIdempotent() {
        val storage = InMemoryAutomationStorage()
        val definition = testDefinition(
            missedRunPolicy = MissedRunPolicy(
                mode = MissedRunMode.SKIP,
                gracePeriod = Duration.ofMinutes(5),
            ),
        )
        storage.upsertDefinition(definition)
        val scheduler = AutomationScheduler(storage)
        val now = Instant.parse("2026-01-02T09:01:00Z")
        val first = scheduler.reconcile(
            now,
            ZoneId.of("UTC"),
            AutomationDiscoverySource.TIMER,
        )
        storage.resetSchedulerCursors(setOf(definition.id))
        val replay = scheduler.reconcile(
            now,
            ZoneId.of("UTC"),
            AutomationDiscoverySource.BOOT_RECOVERY,
        )

        assertEquals(1, first.inboxItemsInserted)
        assertEquals(0, replay.inboxItemsInserted)
        assertEquals(1, replay.duplicatesIgnored)
        assertEquals(1, storage.snapshot().inbox.size)
    }

    @Test
    fun unsupportedRuleFailsClosedWithoutAdvancingCursorOrCreatingInboxWork() {
        val storage = InMemoryAutomationStorage()
        val definition = testDefinition(rrule = "FREQ=SECONDLY")
        storage.upsertDefinition(definition)

        val report = AutomationScheduler(storage).reconcile(
            Instant.parse("2026-01-02T12:00:00Z"),
            ZoneId.of("UTC"),
            AutomationDiscoverySource.TIMER,
        )

        assertEquals(1, report.failures.size)
        assertEquals("unsupported_frequency", report.failures.single().errorCode)
        assertTrue(storage.snapshot().inbox.isEmpty())
        assertNull(storage.schedulerCursor(definition.id))
    }

    @Test
    fun timeZoneRescheduleTouchesOnlyFollowSystemDefinitions() {
        val fixed = testDefinition(id = "automation.fixed")
        val following = testDefinition(
            id = "automation.following",
            timeZone = AutomationTimeZone.FollowSystem,
        )
        val disabledFollowing = testDefinition(
            id = "automation.disabled",
            enabled = false,
            timeZone = AutomationTimeZone.FollowSystem,
        )
        val storage = InMemoryAutomationStorage()
        listOf(fixed, following, disabledFollowing).forEach(storage::upsertDefinition)
        storage.storeRecoveryState(
            AutomationRecoveryState(
                AutomationBootSessionId("boot-0001"),
                "Europe/Oslo",
                Instant.parse("2026-01-01T00:00:00Z"),
            ),
        )
        val observedAt = Instant.parse("2026-02-01T00:00:00Z")

        val applied = AutomationRescheduleCoordinator(storage).apply(
            AutomationRescheduleSignal.TimeZoneChanged(
                observedAt,
                ZoneId.of("Europe/Oslo"),
                ZoneId.of("America/New_York"),
            ),
        )

        assertEquals(
            setOf(following.id),
            applied.directive.affectedAutomationIds,
        )
        assertTrue(applied.directive.replacePlatformWakeup)
        assertFalse(applied.directive.recoverLeases)
        assertEquals(observedAt, storage.schedulerCursor(following.id)?.evaluatedThrough)
        assertNull(storage.schedulerCursor(fixed.id))
        assertEquals("America/New_York", storage.recoveryState()?.systemZoneId)
    }

    @Test
    fun bootDirectiveRecoversSnapshotLeaseAndPreservesDurableScheduleCursor() {
        val storage = InMemoryAutomationStorage()
        val definition = testDefinition()
        val now = Instant.parse("2026-01-02T09:00:00Z")
        storage.insertDueRun(definition, now)
        storage.acquireNextLease(
            AutomationWorkerId("worker-one"),
            AutomationLeaseToken("lease-token-oldboot"),
            AutomationBootSessionId("boot-old1"),
            now,
            Duration.ofHours(1),
        )
        storage.recordDiscovery(
            AutomationDiscoveryBatch(definition.id, definition.revision, now, emptyList()),
        )
        val restored = InMemoryAutomationStorage(storage.snapshot())

        val applied = AutomationRescheduleCoordinator(restored).apply(
            AutomationRescheduleSignal.BootCompleted(
                observedAt = now.plusSeconds(2),
                bootSessionId = AutomationBootSessionId("boot-new1"),
                systemZone = ZoneId.of("UTC"),
            ),
        )

        assertTrue(applied.directive.recoverLeases)
        assertEquals(1, applied.leaseRecovery?.recoveredAfterBoot)
        assertEquals(now, restored.schedulerCursor(definition.id)?.evaluatedThrough)
        assertEquals("boot-new1", restored.recoveryState()?.bootSessionId?.value)
    }

    @Test
    fun movingWallClockForwardPreservesCrossedOccurrenceForMissedRunReconciliation() {
        val storage = InMemoryAutomationStorage()
        val definition = testDefinition()
        storage.upsertDefinition(definition)
        val beforeChange = Instant.parse("2026-01-02T08:00:00Z")
        storage.recordDiscovery(
            AutomationDiscoveryBatch(
                definition.id,
                definition.revision,
                beforeChange,
                emptyList(),
            ),
        )
        val afterChange = Instant.parse("2026-01-02T10:00:00Z")

        AutomationRescheduleCoordinator(storage).apply(
            AutomationRescheduleSignal.WallClockChanged(
                observedAt = afterChange,
                systemZone = ZoneId.of("UTC"),
                bootSessionId = AutomationBootSessionId("boot-same1"),
            ),
        )

        assertEquals(beforeChange, storage.schedulerCursor(definition.id)?.evaluatedThrough)
        val report = AutomationScheduler(storage).reconcile(
            afterChange,
            ZoneId.of("UTC"),
            AutomationDiscoverySource.MANUAL_RECONCILIATION,
        )
        assertEquals(1, report.occurrencesFound)
        assertEquals(1, report.occurrencesSelected)
        assertEquals(
            listOf(Instant.parse("2026-01-02T09:00:00Z")),
            storage.snapshot().inbox.map { it.key.scheduledAt },
        )
    }

    @Test
    fun nextWakeupPicksTheEarliestOccurrenceAndCoalescesEqualTimes() {
        val storage = InMemoryAutomationStorage()
        val earlyOne = testDefinition(id = "automation.early_one")
        val earlyTwo = testDefinition(id = "automation.early_two")
        val later = testDefinition(
            id = "automation.later",
            dtStart = LocalDateTime.of(2026, 1, 1, 10, 0),
        )
        listOf(earlyOne, earlyTwo, later).forEach(storage::upsertDefinition)

        val plan = AutomationScheduler(storage).nextWakeup(
            Instant.parse("2026-01-01T08:00:00Z"),
            ZoneId.of("UTC"),
        )

        assertEquals(Instant.parse("2026-01-01T09:00:00Z"), plan.wakeAt)
        assertEquals(setOf(earlyOne.id, earlyTwo.id), plan.automationIds)
        assertTrue(plan.failures.isEmpty())
    }

    @Test
    fun longIntervalBeyondSearchHorizonGetsAMaintenanceWakeup() {
        val storage = InMemoryAutomationStorage()
        val definition = testDefinition(rrule = "FREQ=DAILY;INTERVAL=10000")
        storage.upsertDefinition(definition)
        val now = Instant.parse("2026-01-02T08:00:00Z")

        val plan = AutomationScheduler(storage).nextWakeup(now, ZoneId.of("UTC"))

        assertEquals(Instant.parse("2036-01-02T08:00:00Z"), plan.wakeAt)
        assertEquals(setOf(definition.id), plan.automationIds)
        assertEquals(AutomationTimingPolicy.RELIABLE_INEXACT, plan.timingPolicy)
    }

    private fun reconcileWithPolicy(
        mode: MissedRunMode,
        now: Instant,
        maximumCatchUp: Int,
    ): ReconciledStorage {
        val storage = InMemoryAutomationStorage()
        storage.upsertDefinition(
            testDefinition(
                missedRunPolicy = MissedRunPolicy(
                    mode = mode,
                    gracePeriod = Duration.ofHours(1),
                    maximumCatchUpRuns = maximumCatchUp,
                ),
            ),
        )
        val report = AutomationScheduler(storage).reconcile(
            now,
            ZoneId.of("UTC"),
            AutomationDiscoverySource.OFFLINE_RECOVERY,
        )
        return ReconciledStorage(storage, report)
    }

    private data class ReconciledStorage(
        val storage: InMemoryAutomationStorage,
        val report: AutomationReconciliationReport,
    )
}
