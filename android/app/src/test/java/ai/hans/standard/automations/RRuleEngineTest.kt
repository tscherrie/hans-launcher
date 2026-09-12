package ai.hans.standard.automations

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RRuleEngineTest {
    @Test
    fun parserAcceptsTheSupportedBoundedRfc5545Subset() {
        listOf(
            "FREQ=MINUTELY;INTERVAL=5;BYSECOND=0,30;COUNT=8",
            "FREQ=HOURLY;INTERVAL=3;BYMINUTE=0,15,30,45;BYSECOND=5;COUNT=8",
            "FREQ=DAILY;INTERVAL=2;BYHOUR=8,17;BYDAY=MO,TU,WE,TH,FR",
            "RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;WKST=SU",
            "FREQ=MONTHLY;BYMONTHDAY=1,-1;BYSETPOS=-1",
            "FREQ=MONTHLY;BYDAY=1MO,-1FR;BYMINUTE=15;WKST=MO",
            "FREQ=YEARLY;BYMONTH=1,6;BYMONTHDAY=1;BYHOUR=9;" +
                "UNTIL=20300101T090000Z",
            "FREQ=YEARLY;BYDAY=20MO,-20FR",
        ).forEach { raw ->
            assertTrue("Expected valid: $raw", RRuleParser.parse(raw) is RRuleParseResult.Valid)
        }
    }

    @Test
    fun parserFailsClosedForEveryUnsupportedOrMalformedPart() {
        val invalidRules = listOf(
            "FREQ=SECONDLY",
            "FREQ=DAILY;BYWEEKNO=1",
            "FREQ=DAILY;COUNT=0",
            "FREQ=DAILY;COUNT=2;UNTIL=20260101T000000Z",
            "FREQ=DAILY;UNTIL=2026-01-01",
            "FREQ=YEARLY;BYMONTH=13",
            "FREQ=MINUTELY;BYSECOND=60",
            "FREQ=HOURLY;BYMINUTE=-1",
            "FREQ=DAILY;BYHOUR=24",
            "FREQ=MONTHLY;BYSETPOS=0;BYDAY=MO",
            "FREQ=MONTHLY;BYSETPOS=1",
            "FREQ=DAILY;INTERVAL=abc",
            "FREQ=DAILY;INTERVAL=0",
            "FREQ=DAILY;FREQ=WEEKLY",
            "FREQ=WEEKLY;BYDAY=1MO",
            "FREQ=WEEKLY;BYMONTHDAY=1",
            "FREQ=DAILY;BYDAY=1MO",
            "FREQ=MONTHLY;BYDAY=0MO",
            "FREQ=MONTHLY;BYDAY=6MO",
            "BYDAY=MO",
            "FREQ=DAILY;",
        )

        invalidRules.forEach { raw ->
            assertTrue("Expected invalid: $raw", RRuleParser.parse(raw) is RRuleParseResult.Invalid)
        }
    }

    @Test
    fun minutelyRulesExpandSecondsAndApplyCountToTheExpandedSet() {
        val minutely = schedule(
            LocalDateTime.of(2026, 1, 1, 8, 0, 10),
            "FREQ=MINUTELY;INTERVAL=2;BYSECOND=15,45;COUNT=5",
        )

        assertEquals(
            listOf(
                "2026-01-01T08:00:15Z",
                "2026-01-01T08:00:45Z",
                "2026-01-01T08:02:15Z",
                "2026-01-01T08:02:45Z",
                "2026-01-01T08:04:15Z",
            ).map(Instant::parse),
            occurrences(minutely, "2026-01-01T07:59:00Z", "2026-01-01T09:00:00Z"),
        )
    }

    @Test
    fun hourlyRulesExpandMinutesAndSecondsInRfcOrder() {
        val hourly = schedule(
            LocalDateTime.of(2026, 1, 1, 8, 30, 10),
            "FREQ=HOURLY;INTERVAL=2;BYMINUTE=0,30;BYSECOND=0,30;COUNT=6",
        )

        assertEquals(
            listOf(
                "2026-01-01T08:30:30Z",
                "2026-01-01T10:00:00Z",
                "2026-01-01T10:00:30Z",
                "2026-01-01T10:30:00Z",
                "2026-01-01T10:30:30Z",
                "2026-01-01T12:00:00Z",
            ).map(Instant::parse),
            occurrences(hourly, "2026-01-01T08:00:00Z", "2026-01-01T13:00:00Z"),
        )
    }

    @Test
    fun dailyRulesExpandHoursMinutesAndSeconds() {
        val daily = schedule(
            LocalDateTime.of(2026, 1, 1, 8, 0),
            "FREQ=DAILY;BYHOUR=9,17;BYMINUTE=15;BYSECOND=30;COUNT=4",
        )

        assertEquals(
            listOf(
                "2026-01-01T09:15:30Z",
                "2026-01-01T17:15:30Z",
                "2026-01-02T09:15:30Z",
                "2026-01-02T17:15:30Z",
            ).map(Instant::parse),
            occurrences(daily, "2026-01-01T00:00:00Z", "2026-01-03T00:00:00Z"),
        )
    }

    @Test
    fun hourlyRulesRunInLocalTimeAndCountBoundsTheWholeSeries() {
        val hourly = schedule(
            LocalDateTime.of(2026, 1, 1, 8, 30),
            "FREQ=HOURLY;INTERVAL=2;COUNT=4",
        )
        assertEquals(
            listOf(
                "2026-01-01T08:30:00Z",
                "2026-01-01T10:30:00Z",
                "2026-01-01T12:30:00Z",
                "2026-01-01T14:30:00Z",
            ).map(Instant::parse),
            occurrences(hourly, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"),
        )
        assertEquals(
            listOf(Instant.parse("2026-01-01T14:30:00Z")),
            occurrences(hourly, "2026-01-01T12:30:00Z", "2026-01-02T00:00:00Z"),
        )
    }

    @Test
    fun untilIsInclusiveAndStopsOccurrencesBeforeTheQueryWindowEnds() {
        val bounded = schedule(
            LocalDateTime.of(2026, 1, 1, 9, 0),
            "FREQ=DAILY;UNTIL=20260103T090000Z",
        )
        assertEquals(
            listOf(
                "2026-01-01T09:00:00Z",
                "2026-01-02T09:00:00Z",
                "2026-01-03T09:00:00Z",
            ).map(Instant::parse),
            occurrences(bounded, "2025-12-31T00:00:00Z", "2026-01-10T00:00:00Z"),
        )
    }

    @Test
    fun yearlyRulesSupportDefaultAnniversaryAndSelectedMonths() {
        val anniversary = schedule(
            LocalDateTime.of(2024, 2, 29, 7, 0),
            "FREQ=YEARLY",
        )
        assertEquals(
            listOf(
                "2024-02-29T07:00:00Z",
                "2028-02-29T07:00:00Z",
            ).map(Instant::parse),
            occurrences(anniversary, "2024-01-01T00:00:00Z", "2029-01-01T00:00:00Z"),
        )

        val twiceYearly = schedule(
            LocalDateTime.of(2026, 1, 1, 9, 0),
            "FREQ=YEARLY;BYMONTH=1,6;BYMONTHDAY=1",
        )
        assertEquals(
            listOf(
                "2026-01-01T09:00:00Z",
                "2026-06-01T09:00:00Z",
                "2027-01-01T09:00:00Z",
                "2027-06-01T09:00:00Z",
            ).map(Instant::parse),
            occurrences(twiceYearly, "2025-12-31T00:00:00Z", "2027-07-01T00:00:00Z"),
        )
    }

    @Test
    fun dailyAndWeeklyIntervalsUseDtStartAndWeekStartDeterministically() {
        val daily = schedule(
            LocalDateTime.of(2026, 1, 5, 9, 0),
            "FREQ=DAILY;INTERVAL=2;BYDAY=MO,TU,WE,TH,FR",
        )
        assertEquals(
            listOf(
                "2026-01-05T09:00:00Z",
                "2026-01-07T09:00:00Z",
                "2026-01-09T09:00:00Z",
                "2026-01-13T09:00:00Z",
            ).map(Instant::parse),
            occurrences(daily, "2026-01-04T00:00:00Z", "2026-01-14T00:00:00Z"),
        )

        val weekly = schedule(
            LocalDateTime.of(2026, 1, 5, 9, 0),
            "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;WKST=MO",
        )
        assertEquals(
            listOf(
                "2026-01-05T09:00:00Z",
                "2026-01-07T09:00:00Z",
                "2026-01-19T09:00:00Z",
                "2026-01-21T09:00:00Z",
            ).map(Instant::parse),
            occurrences(weekly, "2026-01-04T00:00:00Z", "2026-01-25T00:00:00Z"),
        )

        val sundayWeek = schedule(
            LocalDateTime.of(2026, 1, 6, 9, 0),
            "FREQ=WEEKLY;INTERVAL=2;BYDAY=SU;WKST=SU;COUNT=2",
        )
        val mondayWeek = schedule(
            LocalDateTime.of(2026, 1, 6, 9, 0),
            "FREQ=WEEKLY;INTERVAL=2;BYDAY=SU;WKST=MO;COUNT=2",
        )
        assertEquals(
            listOf("2026-01-18T09:00:00Z", "2026-02-01T09:00:00Z").map(Instant::parse),
            occurrences(sundayWeek, "2026-01-01T00:00:00Z", "2026-02-02T00:00:00Z"),
        )
        assertEquals(
            listOf("2026-01-11T09:00:00Z", "2026-01-25T09:00:00Z").map(Instant::parse),
            occurrences(mondayWeek, "2026-01-01T00:00:00Z", "2026-02-02T00:00:00Z"),
        )
    }

    @Test
    fun monthlyRulesSupportInvalidDaySkippingNegativeDaysAndOrdinalWeekdays() {
        val defaultDay31 = schedule(
            LocalDateTime.of(2026, 1, 31, 8, 0),
            "FREQ=MONTHLY",
        )
        assertEquals(
            listOf("2026-01-31T08:00:00Z", "2026-03-31T08:00:00Z").map(Instant::parse),
            occurrences(defaultDay31, "2026-01-01T00:00:00Z", "2026-04-01T00:00:00Z"),
        )

        val firstMondayLastFriday = schedule(
            LocalDateTime.of(2026, 1, 5, 9, 0),
            "FREQ=MONTHLY;BYDAY=1MO,-1FR",
        )
        assertEquals(
            listOf(
                "2026-01-05T09:00:00Z",
                "2026-01-30T09:00:00Z",
                "2026-02-02T09:00:00Z",
                "2026-02-27T09:00:00Z",
            ).map(Instant::parse),
            occurrences(
                firstMondayLastFriday,
                "2026-01-01T00:00:00Z",
                "2026-03-01T00:00:00Z",
            ),
        )

        val lastDay = schedule(
            LocalDateTime.of(2026, 1, 31, 12, 0),
            "FREQ=MONTHLY;BYMONTHDAY=-1",
        )
        assertEquals(
            listOf("2026-01-31T12:00:00Z", "2026-02-28T12:00:00Z").map(Instant::parse),
            occurrences(lastDay, "2026-01-01T00:00:00Z", "2026-03-01T00:00:00Z"),
        )
    }

    @Test
    fun bySetPosSelectsFromEachFullyExpandedFrequencyPeriod() {
        val lastWeekday = schedule(
            LocalDateTime.of(2026, 1, 1, 9, 0),
            "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1",
        )
        assertEquals(
            listOf(
                "2026-01-30T09:00:00Z",
                "2026-02-27T09:00:00Z",
                "2026-03-31T09:00:00Z",
            ).map(Instant::parse),
            occurrences(lastWeekday, "2026-01-01T00:00:00Z", "2026-04-01T00:00:00Z"),
        )

        val firstAndLastTime = schedule(
            LocalDateTime.of(2026, 1, 1, 7, 0),
            "FREQ=DAILY;BYHOUR=8,9,17;BYMINUTE=15;BYSETPOS=1,-1;COUNT=4",
        )
        assertEquals(
            listOf(
                "2026-01-01T08:15:00Z",
                "2026-01-01T17:15:00Z",
                "2026-01-02T08:15:00Z",
                "2026-01-02T17:15:00Z",
            ).map(Instant::parse),
            occurrences(firstAndLastTime, "2026-01-01T00:00:00Z", "2026-01-03T00:00:00Z"),
        )
    }

    @Test
    fun yearlyOrdinalDaysUseYearScopeWithoutByMonthAndMonthScopeWithIt() {
        val yearScoped = schedule(
            LocalDateTime.of(2024, 1, 1, 9, 0),
            "FREQ=YEARLY;BYDAY=1MO,-1FR",
        )
        assertEquals(
            listOf(
                "2024-01-01T09:00:00Z",
                "2024-12-27T09:00:00Z",
                "2025-01-06T09:00:00Z",
                "2025-12-26T09:00:00Z",
            ).map(Instant::parse),
            occurrences(yearScoped, "2023-12-31T00:00:00Z", "2026-01-01T00:00:00Z"),
        )

        val monthScoped = schedule(
            LocalDateTime.of(2026, 1, 1, 9, 0),
            "FREQ=YEARLY;BYMONTH=1,6;BYDAY=1MO;COUNT=4",
        )
        assertEquals(
            listOf(
                "2026-01-05T09:00:00Z",
                "2026-06-01T09:00:00Z",
                "2027-01-04T09:00:00Z",
                "2027-06-07T09:00:00Z",
            ).map(Instant::parse),
            occurrences(monthScoped, "2026-01-01T00:00:00Z", "2028-01-01T00:00:00Z"),
        )
    }

    @Test
    fun dateByPartsLimitSubDailyAndDailyRules() {
        val endOfFebruarySaturday = schedule(
            LocalDateTime.of(2025, 1, 1, 10, 0),
            "FREQ=DAILY;BYMONTH=2;BYMONTHDAY=-1;BYDAY=SA;COUNT=1",
        )
        assertEquals(
            listOf(Instant.parse("2026-02-28T10:00:00Z")),
            occurrences(
                endOfFebruarySaturday,
                "2025-01-01T00:00:00Z",
                "2027-01-01T00:00:00Z",
            ),
        )
    }

    @Test
    fun dstGapUsesOffsetBeforeTheGapAsRequiredByRfc5545() {
        val schedule = AutomationSchedule(
            dtStartLocal = LocalDateTime.of(2026, 3, 28, 2, 30),
            timeZone = AutomationTimeZone.Fixed("Europe/Berlin"),
            rrule = "FREQ=DAILY",
        )

        assertEquals(
            listOf(
                "2026-03-28T01:30:00Z",
                // 02:30 does not exist locally; the pre-gap +01:00 offset maps it to 03:30 CEST.
                "2026-03-29T01:30:00Z",
                "2026-03-30T00:30:00Z",
            ).map(Instant::parse),
            occurrences(schedule, "2026-03-27T00:00:00Z", "2026-03-31T00:00:00Z"),
        )
    }

    @Test
    fun dstOverlapChoosesTheFirstOccurrenceAsRequiredByRfc5545() {
        val schedule = AutomationSchedule(
            dtStartLocal = LocalDateTime.of(2026, 10, 24, 2, 30),
            timeZone = AutomationTimeZone.Fixed("Europe/Berlin"),
            rrule = "FREQ=DAILY",
        )

        assertEquals(
            listOf(
                "2026-10-24T00:30:00Z",
                // First 02:30 is still CEST (+02:00), not the later CET occurrence.
                "2026-10-25T00:30:00Z",
                "2026-10-26T01:30:00Z",
            ).map(Instant::parse),
            occurrences(schedule, "2026-10-23T00:00:00Z", "2026-10-27T00:00:00Z"),
        )
    }

    @Test
    fun expandedDailyTimesRemainWallClockStableAcrossBothDstTransitions() {
        val spring = AutomationSchedule(
            dtStartLocal = LocalDateTime.of(2026, 3, 28, 1, 0),
            timeZone = AutomationTimeZone.Fixed("Europe/Berlin"),
            rrule = "FREQ=DAILY;BYHOUR=2;BYMINUTE=30;COUNT=3",
        )
        assertEquals(
            listOf(
                "2026-03-28T01:30:00Z",
                "2026-03-29T01:30:00Z",
                "2026-03-30T00:30:00Z",
            ).map(Instant::parse),
            occurrences(spring, "2026-03-27T00:00:00Z", "2026-03-31T00:00:00Z"),
        )

        val autumn = AutomationSchedule(
            dtStartLocal = LocalDateTime.of(2026, 10, 24, 1, 0),
            timeZone = AutomationTimeZone.Fixed("Europe/Berlin"),
            rrule = "FREQ=DAILY;BYHOUR=2;BYMINUTE=30;COUNT=3",
        )
        assertEquals(
            listOf(
                "2026-10-24T00:30:00Z",
                "2026-10-25T00:30:00Z",
                "2026-10-26T01:30:00Z",
            ).map(Instant::parse),
            occurrences(autumn, "2026-10-23T00:00:00Z", "2026-10-27T00:00:00Z"),
        )
    }

    @Test
    fun nextOccurrenceAlignsNearAQueryLongAfterDtStartWithoutReplayingTheSeries() {
        val oldSchedule = schedule(
            LocalDateTime.of(2000, 1, 1, 9, 0),
            "FREQ=DAILY;BYMONTH=12;BYMONTHDAY=31",
        )

        val result = RRuleEvaluator.nextOccurrence(
            oldSchedule,
            ZoneId.of("UTC"),
            Instant.parse("2026-08-24T00:00:00Z"),
        ) as RRuleEvaluationResult.Success

        assertEquals(listOf(Instant.parse("2026-12-31T09:00:00Z")), result.occurrences)
    }

    @Test
    fun nextOccurrenceStopsAfterTheFirstMinutelyResultInsteadOfOverflowingTheHorizon() {
        val frequent = schedule(
            LocalDateTime.of(2026, 1, 1, 0, 0),
            "FREQ=MINUTELY",
        )

        val result = RRuleEvaluator.nextOccurrence(
            frequent,
            ZoneId.of("UTC"),
            Instant.parse("2026-08-24T10:00:30Z"),
        ) as RRuleEvaluationResult.Success

        assertEquals(listOf(Instant.parse("2026-08-24T10:01:00Z")), result.occurrences)
    }

    @Test
    fun evaluatorFailsClosedBeforeBuildingAnUnboundedCartesianCandidateSet() {
        val oversized = schedule(
            LocalDateTime.of(2026, 1, 1, 0, 0),
            "FREQ=YEARLY;BYDAY=MO,TU,WE,TH,FR,SA,SU;" +
                "BYHOUR=${(0..23).joinToString(",")};" +
                "BYMINUTE=${(0..59).joinToString(",")};" +
                "BYSECOND=${(0..59).joinToString(",")}",
        )

        assertEquals(
            RRuleEvaluationResult.Failure("candidate_set_too_large"),
            RRuleEvaluator.evaluate(
                oversized,
                ZoneId.of("UTC"),
                Instant.parse("2025-12-31T00:00:00Z"),
                Instant.parse("2026-12-31T23:59:59Z"),
            ),
        )
    }

    @Test
    fun followSystemAndFixedZonesHaveExplicitlyDifferentSemantics() {
        val local = LocalDateTime.of(2026, 1, 2, 9, 0)
        val fixed = AutomationSchedule(local, AutomationTimeZone.Fixed("UTC"), "FREQ=DAILY")
        val follow = AutomationSchedule(local, AutomationTimeZone.FollowSystem, "FREQ=DAILY")

        assertEquals(
            Instant.parse("2026-01-02T09:00:00Z"),
            RRuleEvaluator.startInstant(fixed, ZoneId.of("Europe/Oslo")),
        )
        assertEquals(
            Instant.parse("2026-01-02T08:00:00Z"),
            RRuleEvaluator.startInstant(follow, ZoneId.of("Europe/Oslo")),
        )
    }

    @Test
    fun evaluationWindowIsExclusiveAtStartAndInclusiveAtEnd() {
        val schedule = schedule(
            LocalDateTime.of(2026, 1, 1, 9, 0),
            "FREQ=DAILY",
        )

        assertEquals(
            listOf(Instant.parse("2026-01-02T09:00:00Z")),
            occurrences(schedule, "2026-01-01T09:00:00Z", "2026-01-02T09:00:00Z"),
        )
    }

    private fun schedule(dtStart: LocalDateTime, rule: String): AutomationSchedule =
        AutomationSchedule(dtStart, AutomationTimeZone.Fixed("UTC"), rule)

    private fun occurrences(
        schedule: AutomationSchedule,
        after: String,
        through: String,
    ): List<Instant> = (
        RRuleEvaluator.evaluate(
            schedule,
            ZoneId.of("UTC"),
            Instant.parse(after),
            Instant.parse(through),
        ) as RRuleEvaluationResult.Success
        ).occurrences
}
