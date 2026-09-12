package ai.hans.standard.automations

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RRuleReconciliationTest {
    @Test
    fun subdailyAggregationMatchesEnumerationWithIntervalPhaseFiltersExpansionAndSetPositions() {
        val rules = listOf(
            "FREQ=MINUTELY;INTERVAL=2;BYSECOND=15,45;COUNT=5000",
            "FREQ=MINUTELY;INTERVAL=7;BYMINUTE=0,7,14,21,28,35,42,49,56;" +
                "BYHOUR=8,9,17;BYSECOND=15,45;BYSETPOS=-1;BYDAY=MO,TU,WE,TH,FR",
            "FREQ=MINUTELY;INTERVAL=10000;BYSECOND=0,30",
            "FREQ=MINUTELY;BYSECOND=0,30;BYSETPOS=1,-1;UNTIL=20260202T084015Z",
            "FREQ=MINUTELY;BYMONTH=1;BYMONTHDAY=-1;BYHOUR=9;BYSECOND=5,25",
            "FREQ=MINUTELY;BYSECOND=15;BYSETPOS=5",
            "FREQ=HOURLY;INTERVAL=7;BYMINUTE=0,30;BYSECOND=0,30;BYSETPOS=1,-1",
            "FREQ=HOURLY;INTERVAL=25;BYHOUR=8,9,17;COUNT=10",
            "FREQ=HOURLY;BYMONTH=2;BYMONTHDAY=1,-1;BYDAY=SU;BYMINUTE=0,30;BYSECOND=5,25",
            "FREQ=HOURLY;BYMINUTE=0,30;BYSECOND=0,30;BYSETPOS=-2;COUNT=40",
        )
        for (rule in rules) {
            assertEquivalent(
                AutomationSchedule(LocalDateTime.of(2026, 1, 30, 8, 30, 10), FIXED_UTC, rule),
                Instant.parse("2026-01-30T00:00:00Z"),
                Instant.parse("2026-02-03T12:12:12.123Z"),
            )
        }
    }

    @Test
    fun countAlwaysAppliesToTheOriginalExpandedSeriesNotTheCursorWindow() {
        for (count in listOf(1, 2, 3, 55, 10_000)) {
            for (frequency in listOf("MINUTELY", "HOURLY")) {
                val rule = "FREQ=$frequency;INTERVAL=2;BYSECOND=0,30;COUNT=$count"
                assertEquivalent(
                    AutomationSchedule(LocalDateTime.of(2026, 1, 1, 8, 30, 10), FIXED_UTC, rule),
                    Instant.parse("2026-01-02T08:30:30Z"),
                    Instant.parse("2026-01-04T12:00:00Z"),
                )
            }
        }
    }

    @Test
    fun calendarPeriodsMatchEnumerationForAllSupportedFrequenciesAndOrdinalRules() {
        val rules = listOf(
            "FREQ=DAILY;INTERVAL=2;BYHOUR=9,17;BYMINUTE=15;BYSECOND=30;BYDAY=MO,TU,WE,TH,FR",
            "FREQ=DAILY;BYHOUR=8,9,17;BYMINUTE=15;BYSETPOS=1,-1;COUNT=500",
            "FREQ=WEEKLY;INTERVAL=2;BYDAY=SU,WE;WKST=SU;BYHOUR=8,17;COUNT=120",
            "FREQ=MONTHLY;BYMONTHDAY=1,-1;BYSETPOS=-1",
            "FREQ=MONTHLY;BYDAY=1MO,-1FR;BYMINUTE=15",
            "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1;COUNT=60",
            "FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=29",
            "FREQ=YEARLY;BYDAY=1MO,-1FR",
            "FREQ=YEARLY;BYMONTH=1,6;BYMONTHDAY=1;BYHOUR=9;UNTIL=20270101T090000Z",
        )
        for (rule in rules) {
            assertEquivalent(
                AutomationSchedule(LocalDateTime.of(2024, 1, 30, 8, 20), FIXED_UTC, rule),
                Instant.parse("2024-03-05T00:00:00Z"),
                Instant.parse("2029-01-01T12:00:00Z"),
            )
        }
    }

    @Test
    fun fullWindowsMatchEnumerationAcrossSpringFallHalfHourAndSkippedDayTransitions() {
        val windows = listOf(
            Window("Europe/Berlin", "2026-03-27T00:00:00", "2026-03-26T00:00:00Z", "2026-03-31T12:00:00Z"),
            Window("Europe/Berlin", "2026-10-23T00:00:00", "2026-10-22T00:00:00Z", "2026-10-27T12:00:00Z"),
            Window("Australia/Lord_Howe", "2026-04-03T00:00:00", "2026-04-02T00:00:00Z", "2026-04-07T12:00:00Z"),
            Window("Australia/Lord_Howe", "2026-10-02T00:00:00", "2026-10-01T00:00:00Z", "2026-10-06T12:00:00Z"),
            Window("Pacific/Apia", "2011-12-28T00:00:00", "2011-12-27T00:00:00Z", "2012-01-02T12:00:00Z"),
        )
        val rules = listOf(
            "FREQ=MINUTELY;INTERVAL=7;BYSECOND=5,35",
            "FREQ=MINUTELY;INTERVAL=7;BYSECOND=5,35;COUNT=1500",
            "FREQ=HOURLY;BYMINUTE=0,15,30,45;BYSECOND=0,30;BYSETPOS=1,-1;COUNT=170",
            "FREQ=DAILY;BYHOUR=1,2,3;BYMINUTE=15,30;COUNT=20",
        )
        for (window in windows) for (rule in rules) {
            assertEquivalent(
                AutomationSchedule(LocalDateTime.parse(window.start), AutomationTimeZone.Fixed(window.zone), rule),
                Instant.parse(window.after), Instant.parse(window.through),
            )
        }
    }

    @Test
    fun cursorWindowsPartitionTheSameUniqueSetEvenInsideDstTransitions() {
        for (window in listOf(
            Window("Europe/Berlin", "2026-03-28T00:00:00", "2026-03-27T00:00:00Z", "2026-03-30T12:00:00Z"),
            Window("Europe/Berlin", "2026-10-24T00:00:00", "2026-10-23T00:00:00Z", "2026-10-26T12:00:00Z"),
            Window("Pacific/Apia", "2011-12-29T00:00:00", "2011-12-28T00:00:00Z", "2012-01-01T12:00:00Z"),
        )) {
            val schedule = AutomationSchedule(
                LocalDateTime.parse(window.start), AutomationTimeZone.Fixed(window.zone),
                "FREQ=MINUTELY;INTERVAL=7;BYSECOND=5,35",
            )
            val after = Instant.parse(window.after)
            val through = Instant.parse(window.through)
            val expected = enumerate(schedule, after, through, UTC)
            val discovered = mutableListOf<Instant>()
            var count = 0
            var cursor = after
            while (cursor < through) {
                val next = minOf(cursor.plusSeconds(901), through)
                val result = success(RRuleEvaluator.evaluateForReconciliation(
                    schedule, UTC, cursor, next, MissedRunPolicy(MissedRunMode.SKIP, Duration.ofDays(7)),
                ))
                discovered += result.selectedOccurrences
                count += result.occurrencesFound
                cursor = next
            }
            assertEquals(window.zone, expected, discovered.sorted())
            assertEquals(window.zone, expected.size, count)
            assertEquals(window.zone, discovered.size, discovered.distinct().size)
        }
    }

    @Test
    fun fixedAndFollowSystemZonesBothRetainTheirExplicitSemantics() {
        for (zone in listOf("Pacific/Kiritimati", "America/Los_Angeles", "Europe/Berlin")) {
            for (scheduleZone in listOf(FIXED_UTC, AutomationTimeZone.FollowSystem)) {
                assertEquivalent(
                    AutomationSchedule(
                        LocalDateTime.of(2026, 1, 2, 9, 17), scheduleZone,
                        "FREQ=HOURLY;INTERVAL=7;BYMINUTE=17,45;BYSECOND=10;COUNT=20",
                    ),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-01-07T01:23:45Z"),
                    ZoneId.of(zone),
                )
            }
        }
    }

    @Test
    fun untilAndCursorBothPreserveTheirInclusiveAndExclusiveBoundaries() {
        val schedule = AutomationSchedule(
            LocalDateTime.of(2026, 1, 1, 9, 0), FIXED_UTC,
            "FREQ=MINUTELY;BYSECOND=0,30;UNTIL=20260101T090130Z",
        )
        for (after in listOf("2026-01-01T09:00:00Z", "2026-01-01T09:01:30Z", "2026-01-01T10:00:00Z")) {
            assertEquivalent(schedule, Instant.parse(after), Instant.parse("2026-01-03T00:00:00Z"))
        }
    }

    private fun assertEquivalent(
        schedule: AutomationSchedule,
        after: Instant,
        through: Instant,
        systemZone: ZoneId = UTC,
    ) {
        val expected = enumerate(schedule, after, through, systemZone)
        for (mode in MissedRunMode.entries) for (grace in listOf(Duration.ZERO, Duration.ofHours(1))) {
            val policy = MissedRunPolicy(mode, grace, 3)
            val result = success(RRuleEvaluator.evaluateForReconciliation(schedule, systemZone, after, through, policy))
            val timelyFrom = through.minus(grace)
            val missed = expected.filter { it < timelyFrom }
            val timely = expected.filter { it >= timelyFrom }
            val tail = when (mode) {
                MissedRunMode.SKIP -> emptyList()
                MissedRunMode.RUN_LATEST -> missed.takeLast(1)
                MissedRunMode.CATCH_UP -> missed.takeLast(3)
            }
            val label = "${schedule.rrule}; ${schedule.timeZone}; $mode; $grace"
            assertEquals(label, expected.size, result.occurrencesFound)
            assertEquals(label, (tail + timely).distinct().sorted(), result.selectedOccurrences)
        }
    }

    private fun enumerate(schedule: AutomationSchedule, after: Instant, through: Instant, zone: ZoneId): List<Instant> {
        val result = RRuleEvaluator.evaluate(schedule, zone, after, through)
        assertTrue("Reference enumeration: $result", result is RRuleEvaluationResult.Success)
        return (result as RRuleEvaluationResult.Success).occurrences
    }

    private fun success(result: RRuleReconciliationResult): RRuleReconciliationResult.Success {
        assertTrue(result.toString(), result is RRuleReconciliationResult.Success)
        return result as RRuleReconciliationResult.Success
    }

    private data class Window(val zone: String, val start: String, val after: String, val through: String)

    private companion object {
        val UTC: ZoneId = ZoneId.of("UTC")
        val FIXED_UTC = AutomationTimeZone.Fixed("UTC")
    }
}
