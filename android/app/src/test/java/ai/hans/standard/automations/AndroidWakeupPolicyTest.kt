package ai.hans.standard.automations

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidWakeupPolicyTest {
    @Test
    fun failedInexactReplacementPreservesExistingExactWakeup() {
        val backend = RecordingWakeupBackend(
            exactWakeupPresent = true,
            scheduleInexactSucceeds = false,
        )
        val adapter = AndroidAutomationWakeupAdapter(backend)

        val result = adapter.replaceWakeup(
            wakeupPlan(AutomationTimingPolicy.RELIABLE_INEXACT),
        )

        assertEquals(
            AutomationAdapterResult.Rejected("job_scheduler_rejected"),
            result,
        )
        assertTrue(backend.exactWakeupPresent)
        assertEquals(0, backend.cancelExactCalls)
        assertEquals(listOf("schedule-inexact"), backend.events)
    }

    @Test
    fun failedExactReplacementPreservesExistingInexactWakeup() {
        val backend = RecordingWakeupBackend(
            inexactWakeupPresent = true,
            scheduleExactSucceeds = false,
        )
        val adapter = AndroidAutomationWakeupAdapter(backend)

        val result = adapter.replaceWakeup(
            wakeupPlan(AutomationTimingPolicy.USER_VISIBLE_EXACT),
        )

        assertEquals(
            AutomationAdapterResult.Rejected("exact_alarm_rejected"),
            result,
        )
        assertTrue(backend.inexactWakeupPresent)
        assertEquals(0, backend.cancelInexactCalls)
        assertEquals(listOf("schedule-exact"), backend.events)
    }

    @Test
    fun successfulReplacementSchedulesBeforeCancellingOtherChannel() {
        val backend = RecordingWakeupBackend(exactWakeupPresent = true)
        val adapter = AndroidAutomationWakeupAdapter(backend)

        val result = adapter.replaceWakeup(
            wakeupPlan(AutomationTimingPolicy.RELIABLE_INEXACT),
        )

        assertEquals(AutomationAdapterResult.Accepted, result)
        assertTrue(backend.inexactWakeupPresent)
        assertTrue(!backend.exactWakeupPresent)
        assertEquals(listOf("schedule-inexact", "cancel-exact"), backend.events)
    }

    @Test
    fun failedImmediateEnqueuePreservesBothExistingWakeupChannels() {
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val backend = RecordingWakeupBackend(
            now = now,
            inexactWakeupPresent = true,
            exactWakeupPresent = true,
            enqueueTimerSucceeds = false,
        )
        val adapter = AndroidAutomationWakeupAdapter(backend)

        val result = adapter.replaceWakeup(
            wakeupPlan(
                timingPolicy = AutomationTimingPolicy.USER_VISIBLE_EXACT,
                wakeAt = now,
            ),
        )

        assertEquals(
            AutomationAdapterResult.Rejected("job_scheduler_rejected"),
            result,
        )
        assertTrue(backend.inexactWakeupPresent)
        assertTrue(backend.exactWakeupPresent)
        assertEquals(listOf("enqueue-timer"), backend.events)
    }

    @Test
    fun consumedWakeupUsesBoundedExponentialAlarmRetriesAfterDispatchFailure() {
        val retries = (0 until AndroidAutomationDispatchRetryPolicy.MAX_ATTEMPTS)
            .map { AndroidAutomationDispatchRetryPolicy.afterFailure(it)!! }

        assertEquals((1..6).toList(), retries.map { it.attempt })
        assertEquals(
            listOf(30L, 60L, 120L, 240L, 480L, 900L),
            retries.map { it.delay.seconds },
        )
        assertNull(
            AndroidAutomationDispatchRetryPolicy.afterFailure(
                AndroidAutomationDispatchRetryPolicy.MAX_ATTEMPTS,
            ),
        )
        assertNull(AndroidAutomationDispatchRetryPolicy.afterFailure(Int.MAX_VALUE))
    }

    @Test
    fun normalAutomationsUsePersistedJobSchedulerWindowByDefault() {
        val wakeAt = Instant.parse("2026-01-02T09:00:00Z")
        val decision = AndroidAutomationWakeupPolicy.decide(
            AutomationWakeupPlan(
                wakeAt = wakeAt,
                automationIds = setOf(AutomationId("automation.test")),
                failures = emptyList(),
            ),
            exactAlarmAccessGranted = true,
        )

        assertTrue(decision is AndroidAutomationWakeupDecision.Ready)
        val request = (decision as AndroidAutomationWakeupDecision.Ready).request
            as AndroidAutomationWakeupRequest.JobScheduler
        assertEquals(wakeAt, request.earliestAt)
        assertEquals(wakeAt.plusSeconds(15 * 60), request.overrideDeadlineAt)
        assertTrue(request.persistedAcrossReboot)
    }

    @Test
    fun recoveryWindowStartsAtThePersistedDueTimeInsteadOfAdvancingIt() {
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val dueAt = now.plusSeconds(30)
        val plan = wakeupPlan(AutomationTimingPolicy.RELIABLE_INEXACT, dueAt).copy(
            maximumInexactDelay = Duration.ofSeconds(30),
        )

        for (exactAccess in listOf(false, true)) {
            val decision = AndroidAutomationWakeupPolicy.decide(plan, exactAccess)
                as AndroidAutomationWakeupDecision.Ready
            val request = decision.request as AndroidAutomationWakeupRequest.JobScheduler

            assertEquals(dueAt, request.earliestAt)
            assertEquals(now.plusSeconds(60), request.overrideDeadlineAt)
            assertTrue(request.persistedAcrossReboot)
        }
    }

    @Test
    fun maximumRecoveryDelayCannotLengthenTheConfiguredInexactWindow() {
        val plan = wakeupPlan(AutomationTimingPolicy.RELIABLE_INEXACT).copy(
            maximumInexactDelay = Duration.ofSeconds(30),
        )
        val wakeAt = checkNotNull(plan.wakeAt)
        val shorter = AndroidAutomationWakeupPolicy.decide(
            plan,
            exactAlarmAccessGranted = false,
            inexactWindow = Duration.ofSeconds(10),
        ) as AndroidAutomationWakeupDecision.Ready
        val longerCap = AndroidAutomationWakeupPolicy.decide(
            plan.copy(maximumInexactDelay = Duration.ofHours(1)),
            exactAlarmAccessGranted = false,
        ) as AndroidAutomationWakeupDecision.Ready

        assertEquals(
            wakeAt.plusSeconds(10),
            (shorter.request as AndroidAutomationWakeupRequest.JobScheduler).overrideDeadlineAt,
        )
        assertEquals(
            wakeAt.plusSeconds(15 * 60),
            (longerCap.request as AndroidAutomationWakeupRequest.JobScheduler).overrideDeadlineAt,
        )
    }

    @Test
    fun recoveryCapLeavesGrantedExactAlarmUnchanged() {
        val plan = wakeupPlan(AutomationTimingPolicy.USER_VISIBLE_EXACT).copy(
            nextInexactWakeAt = Instant.parse("2026-01-02T09:05:00Z"),
            maximumInexactDelay = Duration.ofSeconds(30),
        )

        val decision = AndroidAutomationWakeupPolicy.decide(plan, exactAlarmAccessGranted = true)
            as AndroidAutomationWakeupDecision.Ready

        assertEquals(
            AndroidAutomationWakeupRequest.ExactAlarm(plan.wakeAt!!, allowWhileIdle = true),
            decision.request,
        )
    }

    @Test
    fun deniedExactAlarmCapsOnlyItsUnrelatedInexactFallback() {
        val dueAt = Instant.parse("2026-01-02T09:05:00Z")
        val plan = wakeupPlan(AutomationTimingPolicy.USER_VISIBLE_EXACT).copy(
            nextInexactWakeAt = dueAt,
            maximumInexactDelay = Duration.ofSeconds(30),
        )

        for ((configuredWindow, expectedSeconds) in listOf(
            Duration.ofMinutes(15) to 30L,
            Duration.ofSeconds(10) to 10L,
        )) {
            val decision = AndroidAutomationWakeupPolicy.decide(
                plan,
                exactAlarmAccessGranted = false,
                inexactWindow = configuredWindow,
            ) as AndroidAutomationWakeupDecision.ExactAlarmAccessRequired

            assertEquals(
                AndroidAutomationWakeupRequest.JobScheduler(
                    earliestAt = dueAt,
                    overrideDeadlineAt = dueAt.plusSeconds(expectedSeconds),
                    persistedAcrossReboot = true,
                ),
                decision.fallback,
            )
        }
    }

    @Test
    fun recoveryCapCannotAuthorizeAnExactTaskAsInexactWork() {
        val plan = wakeupPlan(AutomationTimingPolicy.USER_VISIBLE_EXACT).copy(
            maximumInexactDelay = Duration.ofSeconds(30),
        )

        val decision = AndroidAutomationWakeupPolicy.decide(plan, exactAlarmAccessGranted = false)
            as AndroidAutomationWakeupDecision.ExactAlarmAccessRequired

        assertNull(decision.fallback)
    }

    @Test
    fun emptyPlanCancelsExistingWakeupsEvenIfItContainsAWindowCap() {
        val plan = AutomationWakeupPlan(
            wakeAt = null,
            automationIds = emptySet(),
            failures = emptyList(),
            maximumInexactDelay = Duration.ofSeconds(30),
        )

        assertEquals(
            AndroidAutomationWakeupDecision.Ready(AndroidAutomationWakeupRequest.CancelExisting),
            AndroidAutomationWakeupPolicy.decide(plan, exactAlarmAccessGranted = false),
        )
    }

    @Test
    fun recoveryWindowRejectsNonPositiveAndUnboundedValues() {
        val plan = wakeupPlan(AutomationTimingPolicy.RELIABLE_INEXACT)
        assertNull(plan.maximumInexactDelay)
        for (invalid in listOf(Duration.ZERO, Duration.ofNanos(-1), Duration.ofHours(6).plusNanos(1))) {
            assertThrows(IllegalArgumentException::class.java) {
                plan.copy(maximumInexactDelay = invalid)
            }
        }
        for (valid in listOf(Duration.ofNanos(1), Duration.ofHours(6))) {
            assertEquals(valid, plan.copy(maximumInexactDelay = valid).maximumInexactDelay)
        }
    }

    @Test
    fun userVisibleExactScheduleNeverSilentlyClaimsExactnessWithoutSpecialAccess() {
        val wakeAt = Instant.parse("2026-01-02T09:00:00Z")
        val plan = AutomationWakeupPlan(
            wakeAt = wakeAt,
            automationIds = setOf(AutomationId("automation.alarm")),
            timingPolicy = AutomationTimingPolicy.USER_VISIBLE_EXACT,
            failures = emptyList(),
        )

        val denied = AndroidAutomationWakeupPolicy.decide(plan, exactAlarmAccessGranted = false)
        assertTrue(denied is AndroidAutomationWakeupDecision.ExactAlarmAccessRequired)
        assertNull((denied as AndroidAutomationWakeupDecision.ExactAlarmAccessRequired).fallback)

        val granted = AndroidAutomationWakeupPolicy.decide(plan, exactAlarmAccessGranted = true)
        assertEquals(
            AndroidAutomationWakeupRequest.ExactAlarm(wakeAt, allowWhileIdle = true),
            (granted as AndroidAutomationWakeupDecision.Ready).request,
        )
    }

    @Test
    fun deniedExactAlarmStillSchedulesTheNextUnrelatedInexactAutomation() {
        val storage = InMemoryAutomationStorage()
        val exact = testDefinition(id = "automation.exact").copy(
            timingPolicy = AutomationTimingPolicy.USER_VISIBLE_EXACT,
        )
        val normal = testDefinition(
            id = "automation.normal",
            dtStart = LocalDateTime.of(2026, 1, 1, 10, 0),
        )
        listOf(exact, normal).forEach(storage::upsertDefinition)
        val plan = AutomationScheduler(storage).nextWakeup(
            Instant.parse("2026-01-01T08:00:00Z"),
            ZoneId.of("UTC"),
        )

        val denied = AndroidAutomationWakeupPolicy.decide(plan, exactAlarmAccessGranted = false)
            as AndroidAutomationWakeupDecision.ExactAlarmAccessRequired

        assertEquals(Instant.parse("2026-01-01T09:00:00Z"), plan.wakeAt)
        assertEquals(
            Instant.parse("2026-01-01T10:00:00Z"),
            denied.fallback?.earliestAt,
        )
    }

    @Test
    fun schedulerCarriesExactTimingOnlyForTheEarliestCoalescedDefinitions() {
        val storage = InMemoryAutomationStorage()
        val earlyNormal = testDefinition(id = "automation.early_normal")
        val earlyExact = testDefinition(id = "automation.early_exact").copy(
            timingPolicy = AutomationTimingPolicy.USER_VISIBLE_EXACT,
        )
        val laterExact = testDefinition(
            id = "automation.later_exact",
            dtStart = LocalDateTime.of(2026, 1, 1, 10, 0),
        ).copy(timingPolicy = AutomationTimingPolicy.USER_VISIBLE_EXACT)
        listOf(earlyNormal, earlyExact, laterExact).forEach(storage::upsertDefinition)

        val plan = AutomationScheduler(storage).nextWakeup(
            Instant.parse("2026-01-01T08:00:00Z"),
            ZoneId.of("UTC"),
        )

        assertEquals(AutomationTimingPolicy.USER_VISIBLE_EXACT, plan.timingPolicy)
        assertEquals(setOf(earlyNormal.id, earlyExact.id), plan.automationIds)
        assertEquals(Instant.parse("2026-01-01T09:00:00Z"), plan.nextInexactWakeAt)
    }

    private fun wakeupPlan(
        timingPolicy: AutomationTimingPolicy,
        wakeAt: Instant = Instant.parse("2026-01-02T09:00:00Z"),
    ) = AutomationWakeupPlan(
        wakeAt = wakeAt,
        automationIds = setOf(AutomationId("automation.test")),
        timingPolicy = timingPolicy,
        nextInexactWakeAt = if (timingPolicy == AutomationTimingPolicy.RELIABLE_INEXACT) {
            wakeAt
        } else {
            null
        },
        failures = emptyList(),
    )

    private class RecordingWakeupBackend(
        private val now: Instant = Instant.parse("2026-01-02T08:00:00Z"),
        var inexactWakeupPresent: Boolean = false,
        var exactWakeupPresent: Boolean = false,
        private val exactAccessGranted: Boolean = true,
        private val scheduleInexactSucceeds: Boolean = true,
        private val scheduleExactSucceeds: Boolean = true,
        private val enqueueTimerSucceeds: Boolean = true,
    ) : AndroidAutomationWakeupBackend {
        val events = mutableListOf<String>()
        var cancelInexactCalls = 0
        var cancelExactCalls = 0

        override fun canScheduleExactAlarms(): Boolean = exactAccessGranted

        override fun wallClockMillis(): Long = now.toEpochMilli()

        override fun scheduleInexactWakeup(
            earliestAt: Instant,
            deadlineAt: Instant,
            persistedAcrossReboot: Boolean,
        ): Boolean {
            events += "schedule-inexact"
            if (scheduleInexactSucceeds) inexactWakeupPresent = true
            return scheduleInexactSucceeds
        }

        override fun scheduleExactWakeup(triggerAt: Instant): Boolean {
            events += "schedule-exact"
            if (scheduleExactSucceeds) exactWakeupPresent = true
            return scheduleExactSucceeds
        }

        override fun enqueueTimerCycle(): Boolean {
            events += "enqueue-timer"
            return enqueueTimerSucceeds
        }

        override fun cancelInexactWakeup() {
            events += "cancel-inexact"
            cancelInexactCalls += 1
            inexactWakeupPresent = false
        }

        override fun cancelExactWakeup() {
            events += "cancel-exact"
            cancelExactCalls += 1
            exactWakeupPresent = false
        }
    }
}
