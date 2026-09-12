package ai.hans.standard.automations

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@JvmInline
value class AutomationId(val value: String) {
    init {
        require(value.matches(SAFE_ID)) { "Invalid automation id" }
    }

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{2,95}")
    }
}

@JvmInline
value class AutomationWorkerId(val value: String) {
    init {
        require(value.matches(SAFE_ID)) { "Invalid worker id" }
    }

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{2,127}")
    }
}

@JvmInline
value class AutomationLeaseToken(val value: String) {
    init {
        require(value.length in 16..160 && value.all(::safeTokenCharacter)) {
            "Invalid lease token"
        }
    }

    private companion object {
        fun safeTokenCharacter(character: Char): Boolean =
            character.isLetterOrDigit() || character in "-_.:"
    }
}

@JvmInline
value class AutomationBootSessionId(val value: String) {
    init {
        require(value.length in 8..160 && value.all(::safeTokenCharacter)) {
            "Invalid boot session id"
        }
    }

    private companion object {
        fun safeTokenCharacter(character: Char): Boolean =
            character.isLetterOrDigit() || character in "-_.:"
    }
}

sealed interface AutomationTimeZone {
    data class Fixed(val zoneId: String) : AutomationTimeZone {
        init {
            require(runCatching { ZoneId.of(zoneId) }.isSuccess) { "Invalid time zone" }
        }
    }

    data object FollowSystem : AutomationTimeZone

    fun resolve(systemZone: ZoneId): ZoneId = when (this) {
        is Fixed -> ZoneId.of(zoneId)
        FollowSystem -> systemZone
    }
}

data class AutomationSchedule(
    val dtStartLocal: LocalDateTime,
    val timeZone: AutomationTimeZone,
    val rrule: String,
) {
    init {
        require(rrule.isNotBlank() && rrule.length <= 1_024) { "Invalid RRULE length" }
        require(dtStartLocal.nano == 0) { "Automation schedules use whole-second precision" }
    }
}

enum class MissedRunMode {
    SKIP,
    RUN_LATEST,
    CATCH_UP,
}

data class MissedRunPolicy(
    val mode: MissedRunMode = MissedRunMode.RUN_LATEST,
    val gracePeriod: Duration = Duration.ofMinutes(5),
    val maximumCatchUpRuns: Int = 16,
) {
    init {
        require(!gracePeriod.isNegative && gracePeriod <= MAX_GRACE) {
            "Invalid missed-run grace period"
        }
        require(maximumCatchUpRuns in 1..256) { "Invalid catch-up limit" }
    }

    private companion object {
        val MAX_GRACE: Duration = Duration.ofDays(7)
    }
}

data class AutomationRetryPolicy(
    val maximumAttempts: Int = 4,
    val initialBackoff: Duration = Duration.ofSeconds(30),
    val backoffMultiplier: Int = 2,
    val maximumBackoff: Duration = Duration.ofHours(6),
) {
    init {
        require(maximumAttempts in 1..20) { "Invalid maximum attempts" }
        require(!initialBackoff.isNegative && initialBackoff <= MAX_BACKOFF_LIMIT) {
            "Invalid initial backoff"
        }
        require(backoffMultiplier in 1..10) { "Invalid backoff multiplier" }
        require(maximumBackoff >= initialBackoff && maximumBackoff <= MAX_BACKOFF_LIMIT) {
            "Invalid maximum backoff"
        }
    }

    /** Delay after the given one-based failed attempt, saturating at [maximumBackoff]. */
    fun delayAfterFailure(attemptNumber: Int): Duration {
        require(attemptNumber >= 1) { "Attempt number must be positive" }
        var delay = initialBackoff
        repeat((attemptNumber - 1).coerceAtMost(32)) {
            delay = runCatching { delay.multipliedBy(backoffMultiplier.toLong()) }
                .getOrDefault(maximumBackoff)
                .coerceAtMost(maximumBackoff)
        }
        return delay.coerceAtMost(maximumBackoff)
    }

    private fun Duration.coerceAtMost(other: Duration): Duration =
        if (this > other) other else this

    private companion object {
        val MAX_BACKOFF_LIMIT: Duration = Duration.ofDays(30)
    }
}

sealed interface CodexAutomationTarget {
    data class ThreadBound(val threadId: String) : CodexAutomationTarget {
        init {
            require(threadId.length in 3..256 && threadId.all(::safeThreadCharacter)) {
                "Invalid Codex thread id"
            }
        }

        private companion object {
            fun safeThreadCharacter(character: Char): Boolean =
                character.isLetterOrDigit() || character in "-_.:"
        }
    }

    data object Independent : CodexAutomationTarget
}

@JvmInline
value class AutomationCapabilityId(val value: String) : Comparable<AutomationCapabilityId> {
    init {
        require(value.matches(SAFE_ID)) { "Invalid automation capability id" }
    }

    override fun compareTo(other: AutomationCapabilityId): Int = value.compareTo(other.value)

    companion object {
        private val SAFE_ID = Regex("[a-z][a-z0-9_.-]{2,127}")

        val CODEX_APP_SERVER = AutomationCapabilityId("codex.app_server")
    }
}

/**
 * A public-API grant, role or special access that the executor must probe immediately before a
 * run. Values are stable identifiers, not promises that access is currently available.
 */
@JvmInline
value class AutomationPermissionId(val value: String) : Comparable<AutomationPermissionId> {
    init {
        require(value.matches(SAFE_ID)) { "Invalid automation permission id" }
    }

    override fun compareTo(other: AutomationPermissionId): Int = value.compareTo(other.value)

    companion object {
        private val SAFE_ID = Regex("[A-Za-z][A-Za-z0-9_.:-]{2,191}")

        val SCHEDULE_EXACT_ALARM =
            AutomationPermissionId("android.special.schedule_exact_alarm")
    }
}

enum class AutomationConfirmationPolicy {
    /** Safest default: a durable user confirmation must identify this exact scheduled run. */
    EVERY_RUN,

    /** The executor may run unattended but phone tools still apply their own confirmation policy. */
    CAPABILITY_POLICY,
}

enum class AutomationTimingPolicy {
    /** Normal default. Android may batch the wakeup to protect battery life. */
    RELIABLE_INEXACT,

    /** Only for a user-visible clock/calendar style promise; special access is still required. */
    USER_VISIBLE_EXACT,
}

data class AutomationRequirements(
    val requiredCapabilities: Set<AutomationCapabilityId> = setOf(
        AutomationCapabilityId.CODEX_APP_SERVER,
    ),
    val requiredPermissions: Set<AutomationPermissionId> = emptySet(),
    val requiresCodexAuthentication: Boolean = true,
    val requiresNetwork: Boolean = true,
    val requiresUnlockedDevice: Boolean = false,
    val confirmationPolicy: AutomationConfirmationPolicy = AutomationConfirmationPolicy.EVERY_RUN,
) {
    init {
        require(requiredCapabilities.size <= 64) { "Too many automation capabilities" }
        require(requiredPermissions.size <= 64) { "Too many automation permissions" }
    }
}

data class AutomationDefinition(
    val id: AutomationId,
    val revision: Long,
    val enabled: Boolean,
    val schedule: AutomationSchedule,
    val missedRunPolicy: MissedRunPolicy,
    val retryPolicy: AutomationRetryPolicy,
    val target: CodexAutomationTarget,
    /** Private user-authored instruction. Storage and adapters must never log it. */
    val instruction: String,
    val updatedAt: Instant,
    val requirements: AutomationRequirements = AutomationRequirements(),
    val timingPolicy: AutomationTimingPolicy = AutomationTimingPolicy.RELIABLE_INEXACT,
) {
    init {
        require(revision >= 1) { "Invalid definition revision" }
        require(instruction.isNotBlank() && instruction.length <= 32_768) {
            "Invalid automation instruction"
        }
    }
}

data class AutomationRunKey(
    val automationId: AutomationId,
    val scheduledAt: Instant,
) : Comparable<AutomationRunKey> {
    override fun compareTo(other: AutomationRunKey): Int =
        compareValuesBy(this, other, { it.scheduledAt }, { it.automationId.value })

    fun stableIdempotencyKey(): String = buildString {
        append("automation:")
        append(automationId.value)
        append(':')
        append(scheduledAt.epochSecond)
        append(':')
        append(scheduledAt.nano)
    }
}

enum class AutomationDiscoverySource {
    TIMER,
    BOOT_RECOVERY,
    TIME_ZONE_RESCHEDULE,
    OFFLINE_RECOVERY,
    MANUAL_RECONCILIATION,
    MANUAL_RUN,
}

data class AutomationInboxItem(
    val key: AutomationRunKey,
    val definitionRevision: Long,
    val discoveredAt: Instant,
    val readyAt: Instant,
    val source: AutomationDiscoverySource,
) {
    init {
        require(definitionRevision >= 1)
        require(readyAt >= key.scheduledAt) {
            "Inbox readiness cannot precede its scheduled occurrence"
        }
    }
}

enum class AutomationRunState {
    PENDING,
    LEASED,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED_TERMINAL,
    SKIPPED,
}

/** Why a durable run is waiting, so lifecycle recovery never bypasses a real retry backoff. */
enum class AutomationRunWaitKind {
    /** No Codex side effect started; a live runtime prerequisite was missing. */
    DEFERRED_PRECONDITION,

    /** A real attempt or lease recovery failed and its configured backoff must be respected. */
    RETRY_BACKOFF,
}

data class AutomationRun(
    val key: AutomationRunKey,
    val definitionRevision: Long,
    val state: AutomationRunState,
    val attemptCount: Int,
    val availableAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastFailureCode: String? = null,
    val waitKind: AutomationRunWaitKind? = if (state == AutomationRunState.RETRY_WAIT) {
        // Fail closed for source-constructed or legacy retry waits which predate the discriminator.
        AutomationRunWaitKind.RETRY_BACKOFF
    } else {
        null
    },
    /**
     * Durable point-of-no-automatic-retry fence. It is written under the active lease immediately
     * before the first possibly effectful Codex turn frame leaves Hans. A process death can then
     * distinguish a safe pre-dispatch retry from an outcome which needs explicit review.
     */
    val dispatchFence: AutomationDispatchFence? = null,
) {
    init {
        require(definitionRevision >= 1)
        require(attemptCount >= 0)
        require(lastFailureCode == null || lastFailureCode.matches(SAFE_ERROR_CODE)) {
            "Unsafe failure code"
        }
        require((state == AutomationRunState.RETRY_WAIT) == (waitKind != null)) {
            "Only a retry-wait run may declare a wait kind"
        }
    }

    private companion object {
        val SAFE_ERROR_CODE = Regex("[a-z][a-z0-9_]{2,95}")
    }
}

data class AutomationDispatchFence(
    val leaseToken: AutomationLeaseToken,
    val leaseGeneration: Int,
    val markedAt: Instant,
) {
    init {
        require(leaseGeneration >= 1)
    }
}

/** Lightweight bounded tombstone retained after detailed history is compacted. */
data class AutomationRunReceipt(
    val key: AutomationRunKey,
    val terminalState: AutomationRunState,
    val completedAt: Instant,
) {
    init {
        require(terminalState in TERMINAL_STATES) { "Receipt requires a terminal run state" }
    }

    private companion object {
        val TERMINAL_STATES = setOf(
            AutomationRunState.SUCCEEDED,
            AutomationRunState.FAILED_TERMINAL,
            AutomationRunState.SKIPPED,
        )
    }
}

@JvmInline
value class AutomationConfirmationId(val value: String) {
    init {
        require(value.length in 16..160 && value.all { it.isLetterOrDigit() || it in "-_.:" }) {
            "Invalid automation confirmation id"
        }
    }
}

data class AutomationRunConfirmationReceipt(
    val id: AutomationConfirmationId,
    val key: AutomationRunKey,
    val definitionRevision: Long,
    val confirmedAt: Instant,
    val expiresAt: Instant,
) {
    init {
        require(definitionRevision >= 1)
        require(expiresAt > confirmedAt)
        require(Duration.between(confirmedAt, expiresAt) <= Duration.ofHours(24)) {
            "Automation confirmation lifetime is too long"
        }
    }
}

@JvmInline
value class AutomationManualRequestId(val value: String) {
    init {
        require(value.matches(SAFE_ID)) { "Invalid manual automation request id" }
    }

    private companion object {
        val SAFE_ID = Regex("[a-f0-9]{64}")
    }
}

data class AutomationManualInvocation(
    val requestId: AutomationManualRequestId,
    val key: AutomationRunKey,
    val definitionRevision: Long,
    val requestedAt: Instant,
) {
    init {
        require(definitionRevision >= 1)
    }
}

data class AutomationLease(
    val key: AutomationRunKey,
    val token: AutomationLeaseToken,
    val owner: AutomationWorkerId,
    val bootSessionId: AutomationBootSessionId,
    val generation: Int,
    val acquiredAt: Instant,
    val heartbeatAt: Instant,
    val expiresAt: Instant,
) {
    init {
        require(generation >= 1)
        require(heartbeatAt >= acquiredAt)
        require(expiresAt > heartbeatAt)
    }
}

data class AutomationSchedulerCursor(
    val automationId: AutomationId,
    val definitionRevision: Long,
    val evaluatedThrough: Instant,
) {
    init {
        require(definitionRevision >= 1)
    }
}

data class AutomationRecoveryState(
    val bootSessionId: AutomationBootSessionId,
    val systemZoneId: String,
    val reconciledAt: Instant,
) {
    init {
        require(runCatching { ZoneId.of(systemZoneId) }.isSuccess)
    }
}

data class AutomationStorageSnapshot(
    val definitions: List<AutomationDefinition> = emptyList(),
    val inbox: List<AutomationInboxItem> = emptyList(),
    val runs: List<AutomationRun> = emptyList(),
    val receipts: List<AutomationRunReceipt> = emptyList(),
    val confirmations: List<AutomationRunConfirmationReceipt> = emptyList(),
    val manualInvocations: List<AutomationManualInvocation> = emptyList(),
    val leases: List<AutomationLease> = emptyList(),
    val cursors: List<AutomationSchedulerCursor> = emptyList(),
    val recoveryState: AutomationRecoveryState? = null,
)
