package ai.hans.standard.automations

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationSchedulerLongGapTest {
    @Test
    fun minutelyLatestRecoversAfterThirtyFiveDaysAndAdvancesWithoutReplay() {
        val (storage, definition) = minutely(MissedRunMode.RUN_LATEST)
        val now = Instant.parse("2026-02-05T00:00:00Z")
        val scheduler = AutomationScheduler(storage)
        val result = scheduler.reconcile(now, UTC, AutomationDiscoverySource.OFFLINE_RECOVERY)

        assertTrue(result.failures.toString(), result.failures.isEmpty())
        assertEquals(50_401, result.occurrencesFound)
        assertEquals(listOf(now.minusSeconds(60), now), scheduled(storage))
        assertEquals(now, storage.schedulerCursor(definition.id)?.evaluatedThrough)

        val restored = InMemoryAutomationStorage(storage.snapshot())
        val resumed = AutomationScheduler(restored)
        assertEquals(0, resumed.reconcile(now, UTC, AutomationDiscoverySource.TIMER).occurrencesFound)
        val next = resumed.reconcile(now.plusSeconds(60), UTC, AutomationDiscoverySource.TIMER)
        assertEquals(1, next.occurrencesFound)
        assertEquals(1, next.inboxItemsInserted)
        assertEquals(listOf(now.minusSeconds(60), now, now.plusSeconds(60)), scheduled(restored))
    }

    @Test
    fun minutelySkipAndCatchUpKeepExactlyTheirRequiredTailAfterLongGap() {
        val now = Instant.parse("2026-02-05T00:00:00Z")
        for ((mode, missedCount) in listOf(MissedRunMode.SKIP to 0, MissedRunMode.CATCH_UP to 3)) {
            val (storage, definition) = minutely(mode)
            val result = AutomationScheduler(storage).reconcile(now, UTC, AutomationDiscoverySource.BOOT_RECOVERY)
            assertTrue("$mode: ${result.failures}", result.failures.isEmpty())
            assertEquals(50_401, result.occurrencesFound)
            assertEquals(missedCount + 1, result.occurrencesSelected)
            assertEquals((missedCount downTo 0).map { now.minusSeconds(it * 60L) }, scheduled(storage))
            assertEquals(now, storage.schedulerCursor(definition.id)?.evaluatedThrough)
        }
    }

    @Test(timeout = 10_000)
    fun manyYearsOfMinutelyHistoryDoNotPreventCurrentDiscovery() {
        val (storage, definition) = minutely(MissedRunMode.RUN_LATEST)
        val now = Instant.parse("2046-02-05T00:00:00Z")
        val result = AutomationScheduler(storage).reconcile(now, UTC, AutomationDiscoverySource.OFFLINE_RECOVERY)
        assertTrue(result.failures.toString(), result.failures.isEmpty())
        assertEquals(ChronoUnit.MINUTES.between(START, now).toInt() + 1, result.occurrencesFound)
        assertEquals(listOf(now.minusSeconds(60), now), scheduled(storage))
        assertEquals(now, storage.schedulerCursor(definition.id)?.evaluatedThrough)
    }

    @Test
    fun longGapUntilRemainsInclusiveAndCountIsNotRestartedNearNow() {
        val now = Instant.parse("2026-02-05T00:00:00Z")
        val untilInstant = Instant.parse("2026-02-04T23:00:00Z")
        val (untilStorage, _) = minutely(MissedRunMode.RUN_LATEST, "FREQ=MINUTELY;UNTIL=20260204T230000Z")
        val until = AutomationScheduler(untilStorage).reconcile(now, UTC, AutomationDiscoverySource.OFFLINE_RECOVERY)
        assertTrue(until.failures.toString(), until.failures.isEmpty())
        assertEquals(50_341, until.occurrencesFound)
        assertEquals(listOf(untilInstant), scheduled(untilStorage))

        val (countStorage, _) = minutely(MissedRunMode.RUN_LATEST, "FREQ=MINUTELY;COUNT=3")
        val count = AutomationScheduler(countStorage).reconcile(now, UTC, AutomationDiscoverySource.OFFLINE_RECOVERY)
        assertTrue(count.failures.toString(), count.failures.isEmpty())
        assertEquals(3, count.occurrencesFound)
        assertEquals(listOf(Instant.parse("2026-01-01T00:02:00Z")), scheduled(countStorage))
    }

    @Test(timeout = 10_000)
    fun hourlyExpansionCountsTwentyYearsButRetainsOnlyTheSelectedTailAndTimelyWindow() {
        val definition = testDefinition(
            dtStart = LocalDateTime.of(2026, 1, 1, 0, 0),
            rrule = "FREQ=HOURLY;BYMINUTE=0,15,30,45;BYSECOND=0,30",
            missedRunPolicy = MissedRunPolicy(MissedRunMode.CATCH_UP, Duration.ZERO, 3),
        )
        val storage = InMemoryAutomationStorage().also { it.upsertDefinition(definition) }
        val now = Instant.parse("2046-02-05T00:00:00Z")
        val report = AutomationScheduler(storage).reconcile(now, UTC, AutomationDiscoverySource.BOOT_RECOVERY)
        assertTrue(report.failures.toString(), report.failures.isEmpty())
        assertEquals(ChronoUnit.HOURS.between(START, now).toInt() * 8 + 1, report.occurrencesFound)
        assertEquals(listOf(1_770L, 900L, 870L, 0L).map(now::minusSeconds), scheduled(storage))
    }

    @Test
    fun finiteCountExhaustsBeforeTheWorkBudgetEvenWhenNowIsCenturiesLater() {
        val (storage, definition) = minutely(MissedRunMode.RUN_LATEST, "FREQ=MINUTELY;COUNT=3")
        val now = Instant.parse("2426-02-05T00:00:00Z")
        val report = AutomationScheduler(storage).reconcile(now, UTC, AutomationDiscoverySource.BOOT_RECOVERY)
        assertTrue(report.failures.toString(), report.failures.isEmpty())
        assertEquals(3, report.occurrencesFound)
        assertEquals(listOf(START.plusSeconds(120)), scheduled(storage))
        assertEquals(now, storage.schedulerCursor(definition.id)?.evaluatedThrough)
    }

    @Test(timeout = 10_000)
    fun unrepresentableFullDiscoveryCountFailsExplicitlyWithoutSaturationOrCursorAdvance() {
        val (storage, definition) = minutely(
            MissedRunMode.RUN_LATEST,
            "FREQ=MINUTELY;BYSECOND=${(0..59).joinToString(",")}",
        )
        val report = AutomationScheduler(storage).reconcile(
            Instant.parse("2096-01-01T00:00:00Z"), UTC, AutomationDiscoverySource.BOOT_RECOVERY,
        )
        assertEquals("occurrence_count_overflow", report.failures.single().errorCode)
        assertEquals(0, report.occurrencesFound)
        assertTrue(storage.snapshot().inbox.isEmpty())
        assertNull(storage.schedulerCursor(definition.id))
    }

    @Test
    fun moreSelectedWorkThanOneDiscoveryBatchAllowsFailsWithoutDroppingTimelyRuns() {
        val definition = testDefinition(
            dtStart = LocalDateTime.of(2026, 1, 1, 0, 0),
            rrule = "FREQ=MINUTELY;BYSECOND=${(0..59).joinToString(",")}",
            missedRunPolicy = MissedRunPolicy(MissedRunMode.SKIP, Duration.ofDays(1)),
        )
        val storage = InMemoryAutomationStorage().also { it.upsertDefinition(definition) }
        val report = AutomationScheduler(storage).reconcile(
            START.plus(Duration.ofDays(1)), UTC, AutomationDiscoverySource.BOOT_RECOVERY,
        )
        assertEquals("selected_occurrence_limit_exceeded", report.failures.single().errorCode)
        assertTrue(storage.snapshot().inbox.isEmpty())
        assertNull(storage.schedulerCursor(definition.id))
    }

    private fun minutely(
        mode: MissedRunMode,
        rule: String = "FREQ=MINUTELY",
    ): Pair<InMemoryAutomationStorage, AutomationDefinition> {
        val definition = testDefinition(
            dtStart = LocalDateTime.of(2026, 1, 1, 0, 0),
            rrule = rule,
            missedRunPolicy = MissedRunPolicy(mode, Duration.ZERO, 3),
        )
        return InMemoryAutomationStorage().also { it.upsertDefinition(definition) } to definition
    }

    private fun scheduled(storage: InMemoryAutomationStorage) =
        storage.snapshot().inbox.map { it.key.scheduledAt }.sorted()

    private companion object {
        val UTC: ZoneId = ZoneId.of("UTC")
        val START: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
