package ai.hans.standard.automations

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.TreeSet
import kotlin.math.abs

enum class RRuleFrequency {
    MINUTELY,
    HOURLY,
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY,
}

data class RRuleDay(
    val dayOfWeek: DayOfWeek,
    val ordinal: Int? = null,
) {
    init {
        require(ordinal == null || ordinal in -53..53 && ordinal != 0)
    }
}

data class SupportedRRule(
    val frequency: RRuleFrequency,
    val interval: Int,
    val bySecond: Set<Int> = emptySet(),
    val byMinute: Set<Int> = emptySet(),
    val byHour: Set<Int> = emptySet(),
    val byDay: Set<RRuleDay> = emptySet(),
    val byMonthDay: Set<Int> = emptySet(),
    val byMonth: Set<Int> = emptySet(),
    val bySetPos: Set<Int> = emptySet(),
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    val count: Int? = null,
    val until: Instant? = null,
)

sealed interface RRuleParseResult {
    data class Valid(val rule: SupportedRRule) : RRuleParseResult
    data class Invalid(val errorCode: String) : RRuleParseResult
}

/**
 * A deliberately bounded RFC 5545 recurrence parser.
 *
 * Hans schedules always have a local DATE-TIME DTSTART plus an explicit or follow-system time
 * zone, so UNTIL is required to be the corresponding UTC DATE-TIME form. SECONDLY, BYWEEKNO,
 * BYYEARDAY and leap-second value 60 are outside this product surface and fail closed.
 */
object RRuleParser {
    private const val MAX_COUNT = 50_000
    private const val MAX_INTERVAL = 10_000
    private const val MAX_RULE_LENGTH = 1_024
    private val supportedParts = setOf(
        "FREQ",
        "INTERVAL",
        "COUNT",
        "UNTIL",
        "BYSECOND",
        "BYMINUTE",
        "BYHOUR",
        "BYDAY",
        "BYMONTHDAY",
        "BYMONTH",
        "BYSETPOS",
        "WKST",
    )
    private val dayPattern = Regex("([+-]?\\d{1,2})?(MO|TU|WE|TH|FR|SA|SU)")
    private val untilFormatter = DateTimeFormatter
        .ofPattern("uuuuMMdd'T'HHmmss'Z'")
        .withResolverStyle(ResolverStyle.STRICT)
        .withZone(ZoneOffset.UTC)

    fun parse(raw: String): RRuleParseResult {
        val body = raw.trim().removePrefixCaseInsensitive("RRULE:")
        if (body.isBlank() || body.length > MAX_RULE_LENGTH) return invalid("invalid_rrule")
        val parts = linkedMapOf<String, String>()
        for (segment in body.split(';')) {
            val separator = segment.indexOf('=')
            if (separator <= 0 || separator == segment.lastIndex) return invalid("invalid_rrule")
            val key = segment.substring(0, separator).trim().uppercase()
            val value = segment.substring(separator + 1).trim().uppercase()
            if (key !in supportedParts) return invalid("unsupported_rrule_part")
            if (parts.put(key, value) != null) return invalid("duplicate_rrule_part")
        }

        val frequency = when (parts["FREQ"]) {
            "MINUTELY" -> RRuleFrequency.MINUTELY
            "HOURLY" -> RRuleFrequency.HOURLY
            "DAILY" -> RRuleFrequency.DAILY
            "WEEKLY" -> RRuleFrequency.WEEKLY
            "MONTHLY" -> RRuleFrequency.MONTHLY
            "YEARLY" -> RRuleFrequency.YEARLY
            null -> return invalid("frequency_required")
            else -> return invalid("unsupported_frequency")
        }
        val interval = parts["INTERVAL"]?.toIntOrNull() ?: if ("INTERVAL" in parts) {
            return invalid("invalid_interval")
        } else {
            1
        }
        if (interval !in 1..MAX_INTERVAL) return invalid("invalid_interval")

        val bySecond = parseIntegerSet(parts["BYSECOND"], 0..59, allowZero = true)
            ?: return invalid("invalid_bysecond")
        val byMinute = parseIntegerSet(parts["BYMINUTE"], 0..59, allowZero = true)
            ?: return invalid("invalid_byminute")
        val byHour = parseIntegerSet(parts["BYHOUR"], 0..23, allowZero = true)
            ?: return invalid("invalid_byhour")
        val byDay = if ("BYDAY" in parts) {
            parseDays(parts.getValue("BYDAY")) ?: return invalid("invalid_byday")
        } else {
            emptySet()
        }
        val byMonthDay = parseIntegerSet(
            parts["BYMONTHDAY"],
            -31..31,
            allowZero = false,
        ) ?: return invalid("invalid_bymonthday")
        val byMonth = parseIntegerSet(parts["BYMONTH"], 1..12, allowZero = false)
            ?: return invalid("invalid_bymonth")
        val bySetPos = parseIntegerSet(
            parts["BYSETPOS"],
            -366..366,
            allowZero = false,
        ) ?: return invalid("invalid_bysetpos")
        val weekStart = if ("WKST" in parts) {
            parsePlainDay(parts.getValue("WKST")) ?: return invalid("invalid_week_start")
        } else {
            DayOfWeek.MONDAY
        }
        val count = parts["COUNT"]?.toIntOrNull()?.also {
            if (it !in 1..MAX_COUNT) return invalid("invalid_count")
        } ?: if ("COUNT" in parts) {
            return invalid("invalid_count")
        } else {
            null
        }
        val until = parts["UNTIL"]?.let { rawUntil ->
            runCatching { Instant.from(untilFormatter.parse(rawUntil)) }.getOrNull()
                ?: return invalid("invalid_until")
        }
        if (count != null && until != null) return invalid("count_until_conflict")

        val ordinalDays = byDay.filter { it.ordinal != null }
        if (ordinalDays.isNotEmpty() && frequency !in ORDINAL_DAY_FREQUENCIES) {
            return invalid("ordinal_byday_requires_monthly_or_yearly")
        }
        if (frequency == RRuleFrequency.MONTHLY && ordinalDays.any { abs(it.ordinal!!) > 5 }) {
            return invalid("monthly_byday_ordinal_out_of_range")
        }
        if (frequency == RRuleFrequency.WEEKLY && byMonthDay.isNotEmpty()) {
            return invalid("bymonthday_not_valid_with_weekly")
        }
        if (bySetPos.isNotEmpty() && parts.keys.none { it in SET_POSITION_SOURCES }) {
            return invalid("bysetpos_requires_by_rule_part")
        }

        return RRuleParseResult.Valid(
            SupportedRRule(
                frequency = frequency,
                interval = interval,
                bySecond = bySecond,
                byMinute = byMinute,
                byHour = byHour,
                byDay = byDay,
                byMonthDay = byMonthDay,
                byMonth = byMonth,
                bySetPos = bySetPos,
                weekStart = weekStart,
                count = count,
                until = until,
            ),
        )
    }

    private fun parseIntegerSet(
        value: String?,
        validRange: IntRange,
        allowZero: Boolean,
    ): Set<Int>? {
        if (value == null) return emptySet()
        if (value.isBlank()) return null
        val result = linkedSetOf<Int>()
        for (token in value.split(',')) {
            if (token.isBlank()) return null
            val parsed = token.toIntOrNull() ?: return null
            if (parsed !in validRange || !allowZero && parsed == 0) return null
            result += parsed
        }
        return result.takeIf(Set<Int>::isNotEmpty)
    }

    private fun parseDays(value: String): Set<RRuleDay>? {
        if (value.isBlank()) return null
        val result = linkedSetOf<RRuleDay>()
        for (token in value.split(',')) {
            val match = dayPattern.matchEntire(token) ?: return null
            val ordinal = match.groupValues[1].takeIf(String::isNotEmpty)?.toIntOrNull()
            if (ordinal != null && (ordinal !in -53..53 || ordinal == 0)) return null
            val day = parsePlainDay(match.groupValues[2]) ?: return null
            result += RRuleDay(day, ordinal)
        }
        return result.takeIf(Set<RRuleDay>::isNotEmpty)
    }

    private fun parsePlainDay(value: String): DayOfWeek? = when (value) {
        "MO" -> DayOfWeek.MONDAY
        "TU" -> DayOfWeek.TUESDAY
        "WE" -> DayOfWeek.WEDNESDAY
        "TH" -> DayOfWeek.THURSDAY
        "FR" -> DayOfWeek.FRIDAY
        "SA" -> DayOfWeek.SATURDAY
        "SU" -> DayOfWeek.SUNDAY
        else -> null
    }

    private fun String.removePrefixCaseInsensitive(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this

    private fun invalid(code: String): RRuleParseResult = RRuleParseResult.Invalid(code)

    private val ORDINAL_DAY_FREQUENCIES = setOf(
        RRuleFrequency.MONTHLY,
        RRuleFrequency.YEARLY,
    )
    private val SET_POSITION_SOURCES = setOf(
        "BYSECOND",
        "BYMINUTE",
        "BYHOUR",
        "BYDAY",
        "BYMONTHDAY",
        "BYMONTH",
    )
}

sealed interface RRuleEvaluationResult {
    data class Success(val occurrences: List<Instant>) : RRuleEvaluationResult
    data class Failure(val errorCode: String) : RRuleEvaluationResult
}

/** Full discovery count and the policy-selected tail, without retaining the elapsed history. */
sealed interface RRuleReconciliationResult {
    data class Success(
        val occurrencesFound: Int,
        val selectedOccurrences: List<Instant>,
    ) : RRuleReconciliationResult
    data class Failure(val errorCode: String) : RRuleReconciliationResult
}

object RRuleEvaluator {
    const val DEFAULT_MAXIMUM_OCCURRENCES = 50_000
    const val MAXIMUM_CANDIDATE_DAYS = 36_600L
    const val MAXIMUM_CANDIDATE_HOURS = MAXIMUM_CANDIDATE_DAYS * 24L + 72L
    const val NEXT_OCCURRENCE_HORIZON_YEARS = 10L

    private const val MAXIMUM_PERIODS = 2_000_000L
    private const val MAXIMUM_CANDIDATES_PER_PERIOD = 100_000L

    fun evaluate(
        schedule: AutomationSchedule,
        systemZone: ZoneId,
        afterExclusive: Instant,
        throughInclusive: Instant,
        maximumOccurrences: Int = DEFAULT_MAXIMUM_OCCURRENCES,
    ): RRuleEvaluationResult = evaluateInternal(
        schedule = schedule,
        systemZone = systemZone,
        afterExclusive = afterExclusive,
        throughInclusive = throughInclusive,
        maximumOccurrences = maximumOccurrences,
        returnAfterFirstOccurrence = false,
    )

    /**
     * Reconciliation counts the complete (cursor, now] set, but retains only the permitted missed
     * tail and timely occurrences. The 50,000-item discovery-batch limit applies to selected work,
     * not to discarded history. Subdaily rules aggregate ordinary days arithmetically; transition
     * days use the same period expansion, RFC offset resolution and duplicate rules as [evaluate].
     */
    fun evaluateForReconciliation(
        schedule: AutomationSchedule,
        systemZone: ZoneId,
        afterExclusive: Instant,
        throughInclusive: Instant,
        policy: MissedRunPolicy,
    ): RRuleReconciliationResult {
        if (throughInclusive <= afterExclusive) {
            return RRuleReconciliationResult.Success(0, emptyList())
        }
        val rule = when (val parsed = RRuleParser.parse(schedule.rrule)) {
            is RRuleParseResult.Valid -> parsed.rule
            is RRuleParseResult.Invalid -> return RRuleReconciliationResult.Failure(parsed.errorCode)
        }
        val zone = runCatching { schedule.timeZone.resolve(systemZone) }.getOrElse {
            return RRuleReconciliationResult.Failure("invalid_time_zone")
        }
        val start = resolveRfcDateTime(schedule.dtStartLocal, zone)
            ?: return RRuleReconciliationResult.Failure("time_zone_resolution_failed")
        val through = rule.until?.let { minOf(throughInclusive, it) } ?: throughInclusive
        if (through < start || through <= afterExclusive) {
            return RRuleReconciliationResult.Success(0, emptyList())
        }
        val selected = ReconciliationOccurrences(
            afterExclusive,
            runCatching { throughInclusive.minus(policy.gracePeriod) }.getOrDefault(Instant.MIN),
            when (policy.mode) {
                MissedRunMode.SKIP -> 0
                MissedRunMode.RUN_LATEST -> 1
                MissedRunMode.CATCH_UP -> policy.maximumCatchUpRuns
            },
        )
        return try {
            if (rule.frequency == RRuleFrequency.MINUTELY || rule.frequency == RRuleFrequency.HOURLY) {
                reconcileSubdaily(schedule, rule, zone, start, afterExclusive, through, selected)
            } else {
                reconcileCalendarPeriods(schedule, rule, zone, start, afterExclusive, through, selected)
            }
            RRuleReconciliationResult.Success(selected.found, selected.result())
        } catch (failure: ReconciliationFailure) {
            RRuleReconciliationResult.Failure(failure.code)
        } catch (_: java.time.DateTimeException) {
            RRuleReconciliationResult.Failure("evaluation_overflow")
        } catch (_: ArithmeticException) {
            RRuleReconciliationResult.Failure("evaluation_overflow")
        }
    }

    private fun reconcileSubdaily(
        schedule: AutomationSchedule,
        rule: SupportedRRule,
        zone: ZoneId,
        start: Instant,
        after: Instant,
        through: Instant,
        selected: ReconciliationOccurrences,
    ) {
        val periodStart = periodStart(schedule.dtStartLocal, rule)
        val firstDate = if (rule.count != null) schedule.dtStartLocal.toLocalDate() else maxOf(
            schedule.dtStartLocal.toLocalDate(),
            // Any legal offset is in [-18h, +18h], including offsetBefore during a gap.
            after.atOffset(ZoneOffset.MIN).toLocalDate(),
        )
        val lastDate = through.atOffset(ZoneOffset.MAX).toLocalDate()
        val pattern = SubdailyPattern(schedule.dtStartLocal, rule)
        val recent = TreeSet<Instant>()
        var generated = 0L
        var date = firstDate
        var evaluatedDays = 0L
        while (date <= lastDate) {
            if (rule.count != null && generated >= rule.count) return
            if (evaluatedDays++ >= MAXIMUM_CANDIDATE_DAYS) {
                throw ReconciliationFailure("evaluation_window_too_large")
            }
            val day = date.atStartOfDay()
            date = date.plusDays(1)
            if (!matchesDateLimiters(rule, day.toLocalDate())) continue
            val distance = periodDistance(periodStart, day, rule)
            val remainder = Math.floorMod(-distance, rule.interval.toLong()).toInt()
            val bases = pattern.periodsByRemainder[remainder] ?: continue
            if (pattern.offsets.isEmpty()) continue
            val count = bases.size * pattern.offsets.size
            val earliest = day.toInstant(ZoneOffset.MAX)
            recent.headSet(earliest, false).clear()
            val latest = date.atStartOfDay().toInstant(ZoneOffset.MIN)
            val transition = zone.rules.nextTransition(earliest.minusNanos(1))
            if (transition == null || transition.instant >= latest) {
                // No offset change anywhere this local day can map to: the Cartesian set is
                // strictly ordered and cannot alias a neighboring day. Count without expanding it.
                val dayEpochSecond = day.toEpochSecond(zone.rules.getOffset(earliest))
                val at: (Int) -> Instant = { index ->
                    Instant.ofEpochSecond(
                        dayEpochSecond + bases[index / pattern.offsets.size] * pattern.periodSeconds +
                            pattern.offsets[index % pattern.offsets.size],
                    )
                }
                val first = firstIndex(count) { at(it) >= start }
                var end = firstIndex(count) { at(it) > through }
                rule.count?.let { end = minOf(end, first + (it - generated).toInt()) }
                if (end > first) {
                    generated += end - first
                    selected.addOrdered(first, end, at)
                }
            } else {
                // Keep local-period ordering for COUNT; DST gap projections can duplicate or
                // reorder instants across periods. Deduplicate only the still-reachable horizon.
                for (base in bases) {
                    recent.headSet(day.plusSeconds(base * pattern.periodSeconds).toInstant(ZoneOffset.MAX), false)
                        .clear()
                    val candidates = pattern.offsets.mapNotNull { offset ->
                        resolveRfcDateTime(day.plusSeconds(base * pattern.periodSeconds + offset), zone)
                    }.filter { it >= start && it <= through }.distinct().sorted()
                    for (candidate in candidates) {
                        if (!recent.add(candidate)) continue
                        generated++
                        selected.add(candidate)
                        if (rule.count != null && generated >= rule.count) return
                    }
                }
            }
        }
    }

    private fun reconcileCalendarPeriods(
        schedule: AutomationSchedule,
        rule: SupportedRRule,
        zone: ZoneId,
        start: Instant,
        after: Instant,
        through: Instant,
        selected: ReconciliationOccurrences,
    ) {
        val initial = periodStart(schedule.dtStartLocal, rule)
        val last = periodDistance(initial, periodStart(through.atZone(zone).toLocalDateTime(), rule), rule)
            .div(rule.interval.toLong())
        val first = if (rule.count != null) 0L else (
            periodDistance(initial, periodStart(after.atZone(zone).toLocalDateTime(), rule), rule)
                .coerceAtLeast(0L) / rule.interval - 1L
            ).coerceAtLeast(0L)
        val recent = TreeSet<Instant>()
        var generated = 0L
        var index = first
        while (index <= last) {
            if (index - first >= MAXIMUM_PERIODS) throw ReconciliationFailure("evaluation_window_too_large")
            val period = addPeriods(initial, Math.multiplyExact(index, rule.interval.toLong()), rule)
            // Future local periods cannot map to an instant earlier than this even across the
            // largest supported time-zone shift. Memory therefore does not grow with history.
            recent.headSet(period.toInstant(ZoneOffset.MAX), false).clear()
            val expanded = when (val expansion = expandPeriod(schedule.dtStartLocal, rule, period)) {
                is CandidateExpansion.Success -> expansion.candidates
                is CandidateExpansion.Failure -> throw ReconciliationFailure(expansion.errorCode)
            }
            val candidates = expanded.mapNotNull { resolveRfcDateTime(it, zone) }
                .filter { it >= start && it <= through }.distinct().sorted()
            for (candidate in candidates) {
                if (!recent.add(candidate)) continue
                generated++
                selected.add(candidate)
                if (rule.count != null && generated >= rule.count) return
            }
            index++
        }
    }

    /** At most 1,440 base slots and 3,600 within-period offsets, independent of elapsed time. */
    private class SubdailyPattern(start: LocalDateTime, rule: SupportedRRule) {
        val periodSeconds = if (rule.frequency == RRuleFrequency.MINUTELY) 60L else 3_600L
        val periodsByRemainder: Map<Int, List<Int>> = (0 until (86_400 / periodSeconds).toInt())
            .filter { base ->
                val hour = (base * periodSeconds / 3_600).toInt()
                (rule.byHour.isEmpty() || hour in rule.byHour) &&
                    (rule.frequency != RRuleFrequency.MINUTELY || rule.byMinute.isEmpty() ||
                        base % 60 in rule.byMinute)
            }.groupBy { it % rule.interval }
        val offsets: List<Long> = run {
            val seconds = rule.bySecond.ifEmpty { setOf(start.second) }
            val expanded = if (rule.frequency == RRuleFrequency.MINUTELY) {
                seconds.map(Int::toLong).sorted()
            } else {
                rule.byMinute.ifEmpty { setOf(start.minute) }
                    .flatMap { minute -> seconds.map { second -> minute * 60L + second } }.sorted()
            }
            if (rule.bySetPos.isEmpty()) expanded else rule.bySetPos.mapNotNull { position ->
                expanded.getOrNull(if (position > 0) position - 1 else expanded.size + position)
            }.distinct().sorted()
        }
    }

    private class ReconciliationOccurrences(
        private val after: Instant,
        private val timelyFrom: Instant,
        private val missedLimit: Int,
    ) {
        var found = 0
            private set
        private val missed = TreeSet<Instant>()
        private val timely = TreeSet<Instant>()

        fun add(candidate: Instant) {
            if (candidate <= after) return
            count(1)
            retain(candidate)
        }

        fun addOrdered(first: Int, end: Int, at: (Int) -> Instant) {
            val discovered = maxOf(first, firstIndex(end) { at(it) > after })
            if (discovered >= end) return
            count(end - discovered)
            val firstTimely = maxOf(discovered, firstIndex(end) { at(it) >= timelyFrom })
            for (index in maxOf(discovered, firstTimely - missedLimit) until end) retain(at(index))
        }

        fun result(): List<Instant> = missed.toList() + timely.toList()

        private fun count(increment: Int) {
            if (increment > Int.MAX_VALUE - found) throw ReconciliationFailure("occurrence_count_overflow")
            found += increment
        }

        private fun retain(candidate: Instant) {
            if (candidate < timelyFrom) {
                if (missedLimit == 0) return
                missed += candidate
                if (missed.size > missedLimit) missed.pollFirst()
            } else {
                timely += candidate
            }
            if (missed.size + timely.size > DEFAULT_MAXIMUM_OCCURRENCES) {
                throw ReconciliationFailure("selected_occurrence_limit_exceeded")
            }
        }
    }

    private fun firstIndex(size: Int, predicate: (Int) -> Boolean): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = low + (high - low) / 2
            if (predicate(middle)) high = middle else low = middle + 1
        }
        return low
    }

    private class ReconciliationFailure(val code: String) : RuntimeException()

    private fun evaluateInternal(
        schedule: AutomationSchedule,
        systemZone: ZoneId,
        afterExclusive: Instant,
        throughInclusive: Instant,
        maximumOccurrences: Int,
        returnAfterFirstOccurrence: Boolean,
    ): RRuleEvaluationResult {
        if (maximumOccurrences !in 1..DEFAULT_MAXIMUM_OCCURRENCES) {
            return RRuleEvaluationResult.Failure("invalid_occurrence_limit")
        }
        if (throughInclusive <= afterExclusive) {
            return RRuleEvaluationResult.Success(emptyList())
        }
        val rule = when (val parsed = RRuleParser.parse(schedule.rrule)) {
            is RRuleParseResult.Valid -> parsed.rule
            is RRuleParseResult.Invalid -> return RRuleEvaluationResult.Failure(parsed.errorCode)
        }
        val zone = runCatching { schedule.timeZone.resolve(systemZone) }.getOrElse {
            return RRuleEvaluationResult.Failure("invalid_time_zone")
        }
        val startInstant = resolveRfcDateTime(schedule.dtStartLocal, zone)
            ?: return RRuleEvaluationResult.Failure("time_zone_resolution_failed")
        val effectiveThrough = rule.until?.let { minOf(throughInclusive, it) } ?: throughInclusive
        if (effectiveThrough < startInstant) return RRuleEvaluationResult.Success(emptyList())

        val startPeriod = periodStart(schedule.dtStartLocal, rule)
        val throughLocal = effectiveThrough.atZone(zone).toLocalDateTime()
        val lastPeriodIndex = periodDistance(startPeriod, periodStart(throughLocal, rule), rule)
            .takeIf { it >= 0L }
            ?.div(rule.interval.toLong())
            ?: return RRuleEvaluationResult.Success(emptyList())
        val firstPeriodIndex = if (rule.count != null) {
            0L
        } else {
            val afterLocal = afterExclusive.atZone(zone).toLocalDateTime()
            val distance = periodDistance(startPeriod, periodStart(afterLocal, rule), rule)
                .coerceAtLeast(0L)
            (distance / rule.interval.toLong() - 1L).coerceAtLeast(0L)
        }
        if (lastPeriodIndex < firstPeriodIndex) return RRuleEvaluationResult.Success(emptyList())

        val occurrences = ArrayList<Instant>()
        val generatedInstants = hashSetOf<Instant>()
        var generatedCount = 0
        var periodIndex = firstPeriodIndex
        var evaluatedPeriods = 0L
        while (periodIndex <= lastPeriodIndex) {
            evaluatedPeriods += 1L
            if (evaluatedPeriods > MAXIMUM_PERIODS) {
                return RRuleEvaluationResult.Failure("evaluation_window_too_large")
            }
            val period = runCatching {
                addPeriods(startPeriod, periodIndex * rule.interval.toLong(), rule)
            }.getOrElse {
                return RRuleEvaluationResult.Failure("evaluation_overflow")
            }
            val localCandidates = when (
                val expansion = expandPeriod(schedule.dtStartLocal, rule, period)
            ) {
                is CandidateExpansion.Success -> expansion.candidates
                is CandidateExpansion.Failure -> {
                    return RRuleEvaluationResult.Failure(expansion.errorCode)
                }
            }
            val instantCandidates = localCandidates
                .asSequence()
                .mapNotNull { resolveRfcDateTime(it, zone) }
                .filter { it >= startInstant && it <= effectiveThrough }
                .distinct()
                .sorted()
                .toList()

            for (candidate in instantCandidates) {
                if (!generatedInstants.add(candidate)) continue
                generatedCount += 1
                if (rule.count != null && generatedCount > rule.count) break
                if (candidate > afterExclusive) {
                    if (occurrences.size == maximumOccurrences) {
                        return RRuleEvaluationResult.Failure("occurrence_limit_exceeded")
                    }
                    occurrences += candidate
                    if (returnAfterFirstOccurrence) {
                        return RRuleEvaluationResult.Success(occurrences)
                    }
                }
                if (rule.count != null && generatedCount >= rule.count) break
            }
            if (rule.count != null && generatedCount >= rule.count) break
            periodIndex += 1L
        }
        return RRuleEvaluationResult.Success(occurrences.sorted())
    }

    fun startInstant(schedule: AutomationSchedule, systemZone: ZoneId): Instant? = runCatching {
        resolveRfcDateTime(schedule.dtStartLocal, schedule.timeZone.resolve(systemZone))
    }.getOrNull()

    fun nextOccurrence(
        schedule: AutomationSchedule,
        systemZone: ZoneId,
        afterExclusive: Instant,
        horizonYears: Long = NEXT_OCCURRENCE_HORIZON_YEARS,
    ): RRuleEvaluationResult {
        if (horizonYears !in 1..100) return RRuleEvaluationResult.Failure("invalid_horizon")
        val through = runCatching {
            afterExclusive.atZone(systemZone).plusYears(horizonYears).toInstant()
        }.getOrElse { return RRuleEvaluationResult.Failure("horizon_overflow") }
        return evaluateInternal(
            schedule = schedule,
            systemZone = systemZone,
            afterExclusive = afterExclusive,
            throughInclusive = through,
            maximumOccurrences = DEFAULT_MAXIMUM_OCCURRENCES,
            returnAfterFirstOccurrence = true,
        )
    }

    /** RFC 5545 local-time rule: first offset in overlaps, pre-gap offset in gaps. */
    private fun resolveRfcDateTime(local: LocalDateTime, zone: ZoneId): Instant? {
        val rules = zone.rules
        val offsets = rules.getValidOffsets(local)
        val offset: ZoneOffset = when {
            offsets.isNotEmpty() -> offsets.first()
            else -> rules.getTransition(local)?.offsetBefore ?: return null
        }
        return local.toInstant(offset)
    }

    private fun periodStart(value: LocalDateTime, rule: SupportedRRule): LocalDateTime =
        when (rule.frequency) {
            RRuleFrequency.MINUTELY -> value.withSecond(0).withNano(0)
            RRuleFrequency.HOURLY -> value.withMinute(0).withSecond(0).withNano(0)
            RRuleFrequency.DAILY -> value.toLocalDate().atStartOfDay()
            RRuleFrequency.WEEKLY -> value.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(rule.weekStart))
                .atStartOfDay()
            RRuleFrequency.MONTHLY -> value.withDayOfMonth(1).toLocalDate().atStartOfDay()
            RRuleFrequency.YEARLY -> LocalDate.of(value.year, 1, 1).atStartOfDay()
        }

    private fun periodDistance(
        start: LocalDateTime,
        end: LocalDateTime,
        rule: SupportedRRule,
    ): Long = when (rule.frequency) {
        RRuleFrequency.MINUTELY -> ChronoUnit.MINUTES.between(start, end)
        RRuleFrequency.HOURLY -> ChronoUnit.HOURS.between(start, end)
        RRuleFrequency.DAILY -> ChronoUnit.DAYS.between(start, end)
        RRuleFrequency.WEEKLY -> ChronoUnit.WEEKS.between(start, end)
        RRuleFrequency.MONTHLY -> ChronoUnit.MONTHS.between(
            YearMonth.from(start),
            YearMonth.from(end),
        )
        RRuleFrequency.YEARLY -> end.year.toLong() - start.year.toLong()
    }

    private fun addPeriods(
        start: LocalDateTime,
        periods: Long,
        rule: SupportedRRule,
    ): LocalDateTime = when (rule.frequency) {
        RRuleFrequency.MINUTELY -> start.plusMinutes(periods)
        RRuleFrequency.HOURLY -> start.plusHours(periods)
        RRuleFrequency.DAILY -> start.plusDays(periods)
        RRuleFrequency.WEEKLY -> start.plusWeeks(periods)
        RRuleFrequency.MONTHLY -> start.plusMonths(periods)
        RRuleFrequency.YEARLY -> start.plusYears(periods)
    }

    private fun expandPeriod(
        dtStart: LocalDateTime,
        rule: SupportedRRule,
        periodStart: LocalDateTime,
    ): CandidateExpansion {
        val dates = when (rule.frequency) {
            RRuleFrequency.MINUTELY,
            RRuleFrequency.HOURLY,
            RRuleFrequency.DAILY,
            -> listOf(periodStart.toLocalDate()).filter { matchesDateLimiters(rule, it) }
            RRuleFrequency.WEEKLY -> weeklyDates(dtStart, rule, periodStart.toLocalDate())
            RRuleFrequency.MONTHLY -> monthlyDates(dtStart, rule, YearMonth.from(periodStart))
            RRuleFrequency.YEARLY -> yearlyDates(dtStart, rule, periodStart.year)
        }

        val hours: Collection<Int>
        val minutes: Collection<Int>
        val seconds: Collection<Int>
        when (rule.frequency) {
            RRuleFrequency.MINUTELY -> {
                val periodHour = periodStart.hour
                val periodMinute = periodStart.minute
                if (rule.byHour.isNotEmpty() && periodHour !in rule.byHour) {
                    return CandidateExpansion.Success(emptyList())
                }
                if (rule.byMinute.isNotEmpty() && periodMinute !in rule.byMinute) {
                    return CandidateExpansion.Success(emptyList())
                }
                hours = listOf(periodHour)
                minutes = listOf(periodMinute)
                seconds = rule.bySecond.ifEmpty { setOf(dtStart.second) }
            }
            RRuleFrequency.HOURLY -> {
                val periodHour = periodStart.hour
                if (rule.byHour.isNotEmpty() && periodHour !in rule.byHour) {
                    return CandidateExpansion.Success(emptyList())
                }
                hours = listOf(periodHour)
                minutes = rule.byMinute.ifEmpty { setOf(dtStart.minute) }
                seconds = rule.bySecond.ifEmpty { setOf(dtStart.second) }
            }
            RRuleFrequency.DAILY,
            RRuleFrequency.WEEKLY,
            RRuleFrequency.MONTHLY,
            RRuleFrequency.YEARLY,
            -> {
                hours = rule.byHour.ifEmpty { setOf(dtStart.hour) }
                minutes = rule.byMinute.ifEmpty { setOf(dtStart.minute) }
                seconds = rule.bySecond.ifEmpty { setOf(dtStart.second) }
            }
        }

        val candidateCount = dates.size.toLong() * hours.size * minutes.size * seconds.size
        if (candidateCount > MAXIMUM_CANDIDATES_PER_PERIOD) {
            return CandidateExpansion.Failure("candidate_set_too_large")
        }
        val candidates = ArrayList<LocalDateTime>(candidateCount.toInt())
        for (date in dates) {
            for (hour in hours) {
                for (minute in minutes) {
                    for (second in seconds) {
                        candidates += LocalDateTime.of(date, LocalTime.of(hour, minute, second))
                    }
                }
            }
        }
        val sorted = candidates.distinct().sorted()
        if (rule.bySetPos.isEmpty()) return CandidateExpansion.Success(sorted)
        val selected = rule.bySetPos.mapNotNullTo(sortedSetOf()) { position ->
            val index = if (position > 0) position - 1 else sorted.size + position
            sorted.getOrNull(index)
        }
        return CandidateExpansion.Success(selected.toList())
    }

    private fun matchesDateLimiters(rule: SupportedRRule, date: LocalDate): Boolean =
        (rule.byMonth.isEmpty() || date.monthValue in rule.byMonth) &&
            (rule.byMonthDay.isEmpty() || matchesMonthDay(date, rule.byMonthDay)) &&
            (rule.byDay.isEmpty() || rule.byDay.any { it.dayOfWeek == date.dayOfWeek })

    private fun weeklyDates(
        dtStart: LocalDateTime,
        rule: SupportedRRule,
        weekStart: LocalDate,
    ): List<LocalDate> {
        val selectedDays = rule.byDay.mapTo(linkedSetOf()) { it.dayOfWeek }
            .ifEmpty { setOf(dtStart.dayOfWeek) }
        return (0L..6L)
            .map(weekStart::plusDays)
            .filter { date ->
                date.dayOfWeek in selectedDays &&
                    (rule.byMonth.isEmpty() || date.monthValue in rule.byMonth)
            }
    }

    private fun monthlyDates(
        dtStart: LocalDateTime,
        rule: SupportedRRule,
        month: YearMonth,
    ): List<LocalDate> {
        if (rule.byMonth.isNotEmpty() && month.monthValue !in rule.byMonth) return emptyList()
        val initial = when {
            rule.byMonthDay.isNotEmpty() -> resolveMonthDays(month, rule.byMonthDay)
            rule.byDay.isNotEmpty() -> (1..month.lengthOfMonth()).map(month::atDay)
            dtStart.dayOfMonth <= month.lengthOfMonth() -> listOf(month.atDay(dtStart.dayOfMonth))
            else -> emptyList()
        }
        return if (rule.byDay.isEmpty()) {
            initial.sorted()
        } else {
            initial.filter { matchesByDayInMonth(it, rule.byDay) }.sorted()
        }
    }

    private fun yearlyDates(
        dtStart: LocalDateTime,
        rule: SupportedRRule,
        year: Int,
    ): List<LocalDate> {
        val selectedMonths: Set<Int> = when {
            rule.byMonth.isNotEmpty() -> rule.byMonth
            rule.byMonthDay.isNotEmpty() || rule.byDay.isNotEmpty() -> (1..12).toSet()
            else -> setOf(dtStart.monthValue)
        }
        val initial = buildList {
            for (monthValue in selectedMonths.sorted()) {
                val month = YearMonth.of(year, monthValue)
                when {
                    rule.byMonthDay.isNotEmpty() -> addAll(
                        resolveMonthDays(month, rule.byMonthDay),
                    )
                    rule.byDay.isNotEmpty() -> addAll(
                        (1..month.lengthOfMonth()).map(month::atDay),
                    )
                    dtStart.dayOfMonth <= month.lengthOfMonth() -> add(
                        month.atDay(dtStart.dayOfMonth),
                    )
                }
            }
        }
        if (rule.byDay.isEmpty()) return initial.distinct().sorted()
        return initial.filter { date ->
            if (rule.byMonth.isNotEmpty()) {
                matchesByDayInMonth(date, rule.byDay)
            } else {
                matchesByDayInYear(date, rule.byDay)
            }
        }.distinct().sorted()
    }

    private fun resolveMonthDays(month: YearMonth, monthDays: Set<Int>): List<LocalDate> =
        monthDays.mapNotNull { monthDay ->
            val resolved = if (monthDay > 0) monthDay else month.lengthOfMonth() + monthDay + 1
            resolved.takeIf { it in 1..month.lengthOfMonth() }?.let(month::atDay)
        }.distinct().sorted()

    private fun matchesMonthDay(date: LocalDate, monthDays: Set<Int>): Boolean =
        monthDays.any { monthDay ->
            val resolved = if (monthDay > 0) monthDay else date.lengthOfMonth() + monthDay + 1
            date.dayOfMonth == resolved
        }

    private fun matchesByDayInMonth(date: LocalDate, byDays: Set<RRuleDay>): Boolean =
        byDays.any { byDay ->
            if (date.dayOfWeek != byDay.dayOfWeek) return@any false
            val ordinal = byDay.ordinal ?: return@any true
            if (ordinal > 0) {
                (date.dayOfMonth - 1) / 7 + 1 == ordinal
            } else {
                -((date.lengthOfMonth() - date.dayOfMonth) / 7 + 1) == ordinal
            }
        }

    private fun matchesByDayInYear(date: LocalDate, byDays: Set<RRuleDay>): Boolean =
        byDays.any { byDay ->
            if (date.dayOfWeek != byDay.dayOfWeek) return@any false
            val ordinal = byDay.ordinal ?: return@any true
            if (ordinal > 0) {
                (date.dayOfYear - 1) / 7 + 1 == ordinal
            } else {
                val daysRemaining = date.lengthOfYear() - date.dayOfYear
                -(daysRemaining / 7 + 1) == ordinal
            }
        }

    private sealed interface CandidateExpansion {
        data class Success(val candidates: List<LocalDateTime>) : CandidateExpansion
        data class Failure(val errorCode: String) : CandidateExpansion
    }
}
