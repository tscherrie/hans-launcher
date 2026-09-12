package ai.hans.standard.automations

import java.time.Duration
import java.time.Instant

/**
 * Platform-neutral plan consumed by a thin Android AlarmManager/JobScheduler adapter.
 *
 * The core never assumes exact-alarm access. A caller must probe
 * `AlarmManager.canScheduleExactAlarms()` immediately before applying this request.
 */
sealed interface AndroidAutomationWakeupRequest {
    data object CancelExisting : AndroidAutomationWakeupRequest

    data class JobScheduler(
        val earliestAt: Instant,
        val overrideDeadlineAt: Instant,
        val persistedAcrossReboot: Boolean,
    ) : AndroidAutomationWakeupRequest {
        init {
            require(overrideDeadlineAt >= earliestAt)
        }
    }

    data class ExactAlarm(
        val triggerAt: Instant,
        val allowWhileIdle: Boolean,
    ) : AndroidAutomationWakeupRequest
}

sealed interface AndroidAutomationWakeupDecision {
    data class Ready(val request: AndroidAutomationWakeupRequest) :
        AndroidAutomationWakeupDecision

    /** The exact task stays blocked; [fallback] contains only unrelated inexact work. */
    data class ExactAlarmAccessRequired(
        val fallback: AndroidAutomationWakeupRequest.JobScheduler?,
    ) : AndroidAutomationWakeupDecision
}

object AndroidAutomationWakeupPolicy {
    val DEFAULT_INEXACT_WINDOW: Duration = Duration.ofMinutes(15)

    fun decide(
        plan: AutomationWakeupPlan,
        exactAlarmAccessGranted: Boolean,
        inexactWindow: Duration = DEFAULT_INEXACT_WINDOW,
    ): AndroidAutomationWakeupDecision {
        require(!inexactWindow.isNegative && !inexactWindow.isZero)
        require(inexactWindow <= Duration.ofHours(6))
        val effectiveInexactWindow = plan.maximumInexactDelay
            ?.let { minOf(inexactWindow, it) }
            ?: inexactWindow
        val wakeAt = plan.wakeAt
            ?: return AndroidAutomationWakeupDecision.Ready(
                AndroidAutomationWakeupRequest.CancelExisting,
            )
        val primaryInexact = AndroidAutomationWakeupRequest.JobScheduler(
            earliestAt = wakeAt,
            overrideDeadlineAt = wakeAt.plus(effectiveInexactWindow),
            persistedAcrossReboot = true,
        )
        return when {
            plan.timingPolicy != AutomationTimingPolicy.USER_VISIBLE_EXACT ->
                AndroidAutomationWakeupDecision.Ready(primaryInexact)
            exactAlarmAccessGranted -> AndroidAutomationWakeupDecision.Ready(
                AndroidAutomationWakeupRequest.ExactAlarm(
                    triggerAt = wakeAt,
                    allowWhileIdle = true,
                ),
            )
            else -> AndroidAutomationWakeupDecision.ExactAlarmAccessRequired(
                plan.nextInexactWakeAt?.let { inexactAt ->
                    AndroidAutomationWakeupRequest.JobScheduler(
                        earliestAt = inexactAt,
                        overrideDeadlineAt = inexactAt.plus(effectiveInexactWindow),
                        persistedAcrossReboot = true,
                    )
                },
            )
        }
    }
}

data class AndroidAutomationDispatchRetry(
    val attempt: Int,
    val delay: Duration,
)

/**
 * Bounded application-owned backoff after a rejected dispatch or an incomplete execution cycle.
 * Dispatch failures use inexact AlarmManager alarms; incomplete cycles use persisted JobScheduler
 * replacements. Neither requires exact-alarm access. After the application-owned cycle retries
 * are exhausted, pending work is retained and Android's framework backoff remains the fallback.
 */
object AndroidAutomationDispatchRetryPolicy {
    const val MAX_ATTEMPTS: Int = 6
    private val INITIAL_DELAY: Duration = Duration.ofSeconds(30)
    private val MAXIMUM_DELAY: Duration = Duration.ofMinutes(15)

    fun afterFailure(failedAttempt: Int): AndroidAutomationDispatchRetry? {
        require(failedAttempt >= 0) { "Invalid dispatch retry attempt" }
        if (failedAttempt >= MAX_ATTEMPTS) return null
        val nextAttempt = failedAttempt + 1
        val multiplier = 1L shl (nextAttempt - 1)
        val delay = INITIAL_DELAY.multipliedBy(multiplier).coerceAtMost(MAXIMUM_DELAY)
        return AndroidAutomationDispatchRetry(nextAttempt, delay)
    }
}
