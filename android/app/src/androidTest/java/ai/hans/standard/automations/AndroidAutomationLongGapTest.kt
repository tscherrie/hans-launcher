package ai.hans.standard.automations

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises Android's real java.time/collection implementation; never registers or runs work. */
@RunWith(AndroidJUnit4::class)
class AndroidAutomationLongGapTest {
    @Test
    fun allMissedRunPoliciesRecoverMoreThanFiftyThousandOccurrencesAndPreserveTheCursor() {
        val now = Instant.parse("2026-02-05T00:00:00Z")
        for ((mode, missed) in listOf(
            MissedRunMode.SKIP to 0,
            MissedRunMode.RUN_LATEST to 1,
            MissedRunMode.CATCH_UP to 3,
        )) {
            val definition = definition(mode = mode)
            val storage = InMemoryAutomationStorage().also { it.upsertDefinition(definition) }
            val scheduler = AutomationScheduler(storage)
            val result = scheduler.reconcile(now, UTC, AutomationDiscoverySource.BOOT_RECOVERY)

            assertTrue(result.failures.toString(), result.failures.isEmpty())
            assertEquals(50_401, result.occurrencesFound)
            assertEquals((missed downTo 0).map { now.minusSeconds(it * 60L) }, scheduled(storage))
            assertEquals(now, storage.schedulerCursor(definition.id)?.evaluatedThrough)
            assertEquals(0, scheduler.reconcile(now, UTC, AutomationDiscoverySource.TIMER).occurrencesFound)
            val next = scheduler.reconcile(now.plusSeconds(60), UTC, AutomationDiscoverySource.TIMER)
            assertEquals(1, next.occurrencesFound)
            assertEquals(1, next.inboxItemsInserted)
        }
    }

    @Test
    fun inclusiveUntilAndOriginalCountAreNotRestartedAtTheRecentTail() {
        for ((rule, count, last) in listOf(
            Triple("FREQ=MINUTELY;UNTIL=20260204T230000Z", 50_341, Instant.parse("2026-02-04T23:00:00Z")),
            Triple("FREQ=MINUTELY;COUNT=3", 3, START.plusSeconds(120)),
        )) {
            val definition = definition(rule = rule)
            val storage = InMemoryAutomationStorage().also { it.upsertDefinition(definition) }
            // Exhausted COUNT must finish before the work bound even with a very distant now.
            val now = if (rule.contains("COUNT")) Instant.parse("2426-02-05T00:00:00Z")
            else Instant.parse("2026-02-05T00:00:00Z")
            val result = AutomationScheduler(storage).reconcile(now, UTC, AutomationDiscoverySource.BOOT_RECOVERY)
            assertTrue(result.failures.toString(), result.failures.isEmpty())
            assertEquals(count, result.occurrencesFound)
            assertEquals(listOf(last), scheduled(storage))
            assertEquals(now, storage.schedulerCursor(definition.id)?.evaluatedThrough)
        }
    }

    @Test
    fun longRecoveryAcrossTheBerlinSpringGapDeduplicatesProjectedOccurrences() {
        val definition = definition().copy(schedule = AutomationSchedule(
            LocalDateTime.of(2026, 3, 1, 0, 0), AutomationTimeZone.Fixed("Europe/Berlin"), "FREQ=MINUTELY",
        ))
        val storage = InMemoryAutomationStorage().also { it.upsertDefinition(definition) }
        val now = Instant.parse("2026-04-09T22:00:00Z")
        val result = AutomationScheduler(storage).reconcile(now, UTC, AutomationDiscoverySource.BOOT_RECOVERY)
        assertTrue(result.failures.toString(), result.failures.isEmpty())
        assertEquals(57_541, result.occurrencesFound)
        assertEquals(listOf(now.minusSeconds(60), now), scheduled(storage))
        assertEquals(now, storage.schedulerCursor(definition.id)?.evaluatedThrough)
    }

    private fun definition(
        mode: MissedRunMode = MissedRunMode.RUN_LATEST,
        rule: String = "FREQ=MINUTELY",
    ) = AutomationDefinition(
        id = AutomationId("android-long-gap-fixture"),
        revision = 1,
        enabled = true,
        schedule = AutomationSchedule(LocalDateTime.of(2026, 1, 1, 0, 0), AutomationTimeZone.Fixed("UTC"), rule),
        missedRunPolicy = MissedRunPolicy(mode, Duration.ZERO, 3),
        retryPolicy = AutomationRetryPolicy(),
        target = CodexAutomationTarget.Independent,
        instruction = "Synthetic arithmetic fixture; never execute.",
        updatedAt = START,
    )

    private fun scheduled(storage: InMemoryAutomationStorage) =
        storage.snapshot().inbox.map { it.key.scheduledAt }.sorted()

    private companion object {
        val START: Instant = Instant.parse("2026-01-01T00:00:00Z")
        val UTC: ZoneId = ZoneId.of("UTC")
    }
}
