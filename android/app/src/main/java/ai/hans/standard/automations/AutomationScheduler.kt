package ai.hans.standard.automations

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

data class AutomationReconciliationFailure(
    val automationId: AutomationId,
    val errorCode: String,
)

data class AutomationReconciliationReport(
    val definitionsEvaluated: Int,
    val occurrencesFound: Int,
    val occurrencesSelected: Int,
    val inboxItemsInserted: Int,
    val duplicatesIgnored: Int,
    val failures: List<AutomationReconciliationFailure>,
)

class AutomationScheduler(
    private val storage: AutomationStorage,
) {
    /**
     * Reconciles every enabled definition through [now]. Discovery and cursor advancement are
     * committed atomically per definition; duplicate occurrence keys make crash replay harmless.
     */
    fun reconcile(
        now: Instant,
        systemZone: ZoneId,
        source: AutomationDiscoverySource,
    ): AutomationReconciliationReport {
        var evaluated = 0
        var found = 0
        var selected = 0
        var inserted = 0
        var duplicates = 0
        val failures = mutableListOf<AutomationReconciliationFailure>()

        storage.definitions().filter { it.enabled }.forEach { definition ->
            val cursor = storage.schedulerCursor(definition.id)
                ?.takeIf { it.definitionRevision == definition.revision }
            if (cursor != null && cursor.evaluatedThrough >= now) return@forEach
            val start = RRuleEvaluator.startInstant(definition.schedule, systemZone)
            if (start == null) {
                failures += AutomationReconciliationFailure(
                    definition.id,
                    "time_zone_resolution_failed",
                )
                return@forEach
            }
            val afterExclusive = cursor?.evaluatedThrough ?: start.minusSafely(Duration.ofNanos(1))
            evaluated += 1
            val occurrences = when (
                val evaluation = RRuleEvaluator.evaluateForReconciliation(
                    definition.schedule,
                    systemZone,
                    afterExclusive,
                    now,
                    definition.missedRunPolicy,
                )
            ) {
                is RRuleReconciliationResult.Success -> evaluation
                is RRuleReconciliationResult.Failure -> {
                    failures += AutomationReconciliationFailure(
                        definition.id,
                        evaluation.errorCode,
                    )
                    return@forEach
                }
            }
            if (occurrences.occurrencesFound > Int.MAX_VALUE - found) {
                failures += AutomationReconciliationFailure(definition.id, "occurrence_count_overflow")
                return@forEach
            }
            found += occurrences.occurrencesFound
            val selectedOccurrences = occurrences.selectedOccurrences
            selected += selectedOccurrences.size
            val result = storage.recordDiscovery(
                AutomationDiscoveryBatch(
                    automationId = definition.id,
                    definitionRevision = definition.revision,
                    evaluatedThrough = now,
                    items = selectedOccurrences.map { scheduledAt ->
                        AutomationInboxItem(
                            key = AutomationRunKey(definition.id, scheduledAt),
                            definitionRevision = definition.revision,
                            discoveredAt = now,
                            readyAt = maxOf(now, scheduledAt),
                            source = source,
                        )
                    },
                ),
            )
            if (result.accepted) {
                inserted += result.inserted
                duplicates += result.duplicates
            } else {
                failures += AutomationReconciliationFailure(
                    definition.id,
                    result.errorCode ?: "discovery_rejected",
                )
            }
        }
        return AutomationReconciliationReport(
            evaluated,
            found,
            selected,
            inserted,
            duplicates,
            failures,
        )
    }

    fun nextWakeup(now: Instant, systemZone: ZoneId): AutomationWakeupPlan {
        data class Candidate(
            val id: AutomationId,
            val occurrence: Instant,
            val timingPolicy: AutomationTimingPolicy,
        )

        val candidates = mutableListOf<Candidate>()
        val failures = mutableListOf<AutomationReconciliationFailure>()
        storage.definitions().filter { it.enabled }.forEach { definition ->
            when (
                val result = RRuleEvaluator.nextOccurrence(
                    definition.schedule,
                    systemZone,
                    now,
                )
            ) {
                is RRuleEvaluationResult.Success -> {
                    val occurrence = result.occurrences.firstOrNull()
                    val wakeAt = occurrence ?: runCatching {
                        now.atZone(systemZone)
                            .plusYears(RRuleEvaluator.NEXT_OCCURRENCE_HORIZON_YEARS)
                            .toInstant()
                    }.getOrNull()
                    wakeAt?.let {
                        candidates += Candidate(
                            definition.id,
                            it,
                            if (occurrence == null) {
                                AutomationTimingPolicy.RELIABLE_INEXACT
                            } else {
                                definition.timingPolicy
                            },
                        )
                    }
                }
                is RRuleEvaluationResult.Failure -> failures += AutomationReconciliationFailure(
                    definition.id,
                    result.errorCode,
                )
            }
        }
        val earliest = candidates.minOfOrNull { it.occurrence }
        val earliestCandidates = candidates.filter { it.occurrence == earliest }
        val nextInexact = candidates
            .filter { it.timingPolicy == AutomationTimingPolicy.RELIABLE_INEXACT }
            .minOfOrNull { it.occurrence }
        return AutomationWakeupPlan(
            wakeAt = earliest,
            automationIds = if (earliest == null) {
                emptySet()
            } else {
                earliestCandidates.mapTo(linkedSetOf()) { it.id }
            },
            timingPolicy = if (earliest != null && earliestCandidates
                    .any { it.timingPolicy == AutomationTimingPolicy.USER_VISIBLE_EXACT }
            ) {
                AutomationTimingPolicy.USER_VISIBLE_EXACT
            } else {
                AutomationTimingPolicy.RELIABLE_INEXACT
            },
            nextInexactWakeAt = nextInexact,
            failures = failures,
        )
    }

}

data class AutomationWakeupPlan(
    val wakeAt: Instant?,
    val automationIds: Set<AutomationId>,
    val timingPolicy: AutomationTimingPolicy = AutomationTimingPolicy.RELIABLE_INEXACT,
    /** Earliest work that remains valid when exact-alarm access is unavailable. */
    val nextInexactWakeAt: Instant? = if (
        timingPolicy == AutomationTimingPolicy.RELIABLE_INEXACT
    ) {
        wakeAt
    } else {
        null
    },
    val failures: List<AutomationReconciliationFailure>,
    /**
     * Optional cap on the requested inexact window for already persisted work. This never moves
     * [wakeAt] or grants exact-alarm access, and Android may dispatch later than this window.
     */
    val maximumInexactDelay: Duration? = null,
) {
    init {
        maximumInexactDelay?.let { delay ->
            require(!delay.isNegative && !delay.isZero)
            require(delay <= Duration.ofHours(6))
        }
    }
}

interface AutomationWakeupAdapter {
    /** Replaces, rather than appends, the platform wakeup to prevent duplicate alarms. */
    fun replaceWakeup(plan: AutomationWakeupPlan): AutomationAdapterResult
}

sealed interface AutomationAdapterResult {
    data object Accepted : AutomationAdapterResult
    data class Rejected(
        val errorCode: String,
        val retryable: Boolean = true,
    ) : AutomationAdapterResult {
        init {
            require(errorCode.matches(Regex("[a-z][a-z0-9_]{2,95}")))
        }
    }
}

sealed interface AutomationRescheduleSignal {
    val observedAt: Instant

    data class BootCompleted(
        override val observedAt: Instant,
        val bootSessionId: AutomationBootSessionId,
        val systemZone: ZoneId,
    ) : AutomationRescheduleSignal

    data class TimeZoneChanged(
        override val observedAt: Instant,
        val previousZone: ZoneId,
        val currentZone: ZoneId,
    ) : AutomationRescheduleSignal

    data class WallClockChanged(
        override val observedAt: Instant,
        val systemZone: ZoneId,
        val bootSessionId: AutomationBootSessionId,
    ) : AutomationRescheduleSignal

    data class ConnectivityRestored(
        override val observedAt: Instant,
        val systemZone: ZoneId,
    ) : AutomationRescheduleSignal
}

enum class AutomationRescheduleReason {
    BOOT,
    TIME_ZONE_CHANGE,
    WALL_CLOCK_CHANGE,
    CONNECTIVITY_RESTORED,
}

data class AutomationRescheduleDirective(
    val reason: AutomationRescheduleReason,
    val affectedAutomationIds: Set<AutomationId>,
    val replacePlatformWakeup: Boolean,
    val recoverLeases: Boolean,
    /** Non-null means future scheduling continues from this new wall-clock boundary. */
    val resetCursorThrough: Instant?,
    val reconciliationSource: AutomationDiscoverySource,
)

object AutomationReschedulePlanner {
    fun plan(
        signal: AutomationRescheduleSignal,
        definitions: List<AutomationDefinition>,
    ): AutomationRescheduleDirective {
        val enabled = definitions.filter { it.enabled }
        return when (signal) {
            is AutomationRescheduleSignal.BootCompleted -> AutomationRescheduleDirective(
                reason = AutomationRescheduleReason.BOOT,
                affectedAutomationIds = enabled.mapTo(linkedSetOf()) { it.id },
                replacePlatformWakeup = true,
                recoverLeases = true,
                resetCursorThrough = null,
                reconciliationSource = AutomationDiscoverySource.BOOT_RECOVERY,
            )
            is AutomationRescheduleSignal.TimeZoneChanged -> AutomationRescheduleDirective(
                reason = AutomationRescheduleReason.TIME_ZONE_CHANGE,
                affectedAutomationIds = enabled
                    .filter { it.schedule.timeZone == AutomationTimeZone.FollowSystem }
                    .mapTo(linkedSetOf()) { it.id },
                replacePlatformWakeup = true,
                recoverLeases = false,
                resetCursorThrough = signal.observedAt,
                reconciliationSource = AutomationDiscoverySource.TIME_ZONE_RESCHEDULE,
            )
            is AutomationRescheduleSignal.WallClockChanged -> AutomationRescheduleDirective(
                reason = AutomationRescheduleReason.WALL_CLOCK_CHANGE,
                affectedAutomationIds = enabled.mapTo(linkedSetOf()) { it.id },
                replacePlatformWakeup = true,
                recoverLeases = true,
                resetCursorThrough = signal.observedAt,
                reconciliationSource = AutomationDiscoverySource.MANUAL_RECONCILIATION,
            )
            is AutomationRescheduleSignal.ConnectivityRestored -> AutomationRescheduleDirective(
                reason = AutomationRescheduleReason.CONNECTIVITY_RESTORED,
                affectedAutomationIds = enabled.mapTo(linkedSetOf()) { it.id },
                replacePlatformWakeup = false,
                recoverLeases = false,
                resetCursorThrough = null,
                reconciliationSource = AutomationDiscoverySource.OFFLINE_RECOVERY,
            )
        }
    }
}

data class AppliedRescheduleDirective(
    val directive: AutomationRescheduleDirective,
    val leaseRecovery: AutomationLeaseRecoveryResult?,
)

class AutomationRescheduleCoordinator(
    private val storage: AutomationStorage,
) {
    fun apply(signal: AutomationRescheduleSignal): AppliedRescheduleDirective {
        val directive = AutomationReschedulePlanner.plan(signal, storage.definitions())
        directive.resetCursorThrough?.let { through ->
            if (signal is AutomationRescheduleSignal.WallClockChanged) {
                directive.affectedAutomationIds.forEach { id ->
                    storage.schedulerCursor(id)?.let { cursor ->
                        storage.resetSchedulerCursors(
                            setOf(id),
                            minOf(cursor.evaluatedThrough, through),
                        )
                    }
                }
            } else {
                storage.resetSchedulerCursors(directive.affectedAutomationIds, through)
            }
        }
        val recovery = when (signal) {
            is AutomationRescheduleSignal.BootCompleted ->
                storage.recoverLeases(signal.observedAt, signal.bootSessionId)
            is AutomationRescheduleSignal.WallClockChanged -> storage.recoverLeases(
                signal.observedAt,
                signal.bootSessionId,
                forceSameBootRecovery = true,
            )
            else -> null
        }
        val (bootId, zone) = when (signal) {
            is AutomationRescheduleSignal.BootCompleted ->
                signal.bootSessionId to signal.systemZone
            is AutomationRescheduleSignal.TimeZoneChanged ->
                storage.recoveryState()?.bootSessionId to signal.currentZone
            is AutomationRescheduleSignal.WallClockChanged ->
                signal.bootSessionId to signal.systemZone
            is AutomationRescheduleSignal.ConnectivityRestored ->
                storage.recoveryState()?.bootSessionId to signal.systemZone
        }
        if (bootId != null) {
            storage.storeRecoveryState(
                AutomationRecoveryState(bootId, zone.id, signal.observedAt),
            )
        }
        return AppliedRescheduleDirective(directive, recovery)
    }
}

private fun Instant.minusSafely(duration: Duration): Instant =
    runCatching { minus(duration) }.getOrDefault(Instant.MIN)
