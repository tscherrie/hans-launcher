package ai.hans.standard.automations

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationRuntimeRecoveryWakeupTest {
    private val now = Instant.parse("2026-01-01T08:00:00Z")
    private val boot = AutomationBootSessionId("boot-wakeup-tests")

    @Test
    fun persistedFutureInboxKeepsItsReadyTimeAndRequestsThirtySecondWindow() {
        val definition = testDefinition(id = "automation.wakeup.inbox")
        val dueAt = now.plusSeconds(90)
        val item = discoveredItem(definition, now).copy(readyAt = dueAt)
        val storage = InMemoryAutomationStorage(
            AutomationStorageSnapshot(definitions = listOf(definition), inbox = listOf(item)),
        )

        val cycle = runCycle(storage)

        assertEquals(listOf(item), storage.snapshot().inbox)
        assertEquals(0, cycle.executions)
        assertRecoveryWakeup(cycle.plan, dueAt, setOf(definition.id))
    }

    @Test
    fun persistedPendingRunIsNotExecutedBeforeItsAvailableTime() {
        val definition = testDefinition(id = "automation.wakeup.pending")
        val dueAt = now.plusSeconds(45)
        val pending = storedRun(definition, AutomationRunState.PENDING, dueAt)
        val storage = InMemoryAutomationStorage(
            AutomationStorageSnapshot(definitions = listOf(definition), runs = listOf(pending)),
        )

        val cycle = runCycle(storage)

        assertEquals(listOf(pending), storage.snapshot().runs)
        assertEquals(0, cycle.executions)
        assertRecoveryWakeup(cycle.plan, dueAt, setOf(definition.id))
    }

    @Test
    fun persistedRetryKeepsBackoffAndDropsTightWindowAfterSuccessfulRecovery() {
        val storage = failedAttemptStorage()
        val waiting = storage.snapshot().runs.single()
        assertEquals(AutomationRunState.RETRY_WAIT, waiting.state)
        assertEquals(now.plusSeconds(30), waiting.availableAt)

        val beforeDue = runCycle(storage)

        assertEquals(listOf(waiting), storage.snapshot().runs)
        assertEquals(0, beforeDue.executions)
        assertRecoveryWakeup(beforeDue.plan, waiting.availableAt, setOf(waiting.key.automationId))

        val recovered = runCycle(storage, waiting.availableAt)

        assertEquals(1, recovered.executions)
        assertEquals(AutomationRunState.SUCCEEDED, storage.snapshot().runs.single().state)
        assertNull(recovered.plan.maximumInexactDelay)
        assertEquals(Instant.parse("2026-01-01T09:00:00Z"), recovered.plan.wakeAt)
        assertDefaultWindow(recovered.plan)
    }

    @Test
    fun unexpiredSameBootLeaseIsNeitherReclaimedNorScheduledBeforeExpiry() {
        val seed = InMemoryAutomationStorage()
        val definition = testDefinition(id = "automation.wakeup.lease")
        seed.insertDueRun(definition, now)
        val claim = checkNotNull(seed.acquireNextLease(
            owner = AutomationWorkerId("wakeup-test-owner"),
            token = AutomationLeaseToken("lease-wakeup-unexpired"),
            bootSessionId = boot,
            now = now,
            leaseDuration = Duration.ofMinutes(2),
        ))
        val storage = InMemoryAutomationStorage(seed.snapshot())

        val cycle = runCycle(storage)

        assertEquals(listOf(claim.lease), storage.snapshot().leases)
        assertEquals(AutomationRunState.LEASED, storage.snapshot().runs.single().state)
        assertEquals(0, cycle.executions)
        assertRecoveryWakeup(cycle.plan, now.plusSeconds(120), setOf(definition.id))
    }

    @Test
    fun earliestDurableCandidateWinsAcrossInboxPendingRetryAndLease() {
        val inbox = testDefinition(id = "automation.wakeup.mixed.inbox")
        val pending = testDefinition(id = "automation.wakeup.mixed.pending")
        val retry = testDefinition(id = "automation.wakeup.mixed.retry")
        val leased = testDefinition(id = "automation.wakeup.mixed.lease")
        val leasedRun = storedRun(leased, AutomationRunState.LEASED, now, attemptCount = 1)
        val storage = InMemoryAutomationStorage(
            AutomationStorageSnapshot(
                definitions = listOf(inbox, pending, retry, leased),
                inbox = listOf(discoveredItem(inbox, now).copy(readyAt = now.plusSeconds(90))),
                runs = listOf(
                    storedRun(pending, AutomationRunState.PENDING, now.plusSeconds(45)),
                    storedRun(retry, AutomationRunState.RETRY_WAIT, now.plusSeconds(30), attemptCount = 1),
                    leasedRun,
                ),
                leases = listOf(AutomationLease(
                    key = leasedRun.key,
                    token = AutomationLeaseToken("lease-wakeup-mixed"),
                    owner = AutomationWorkerId("wakeup-test-owner"),
                    bootSessionId = boot,
                    generation = 1,
                    acquiredAt = now,
                    heartbeatAt = now,
                    expiresAt = now.plusSeconds(120),
                )),
            ),
        )

        val cycle = runCycle(storage)

        assertEquals(0, cycle.executions)
        assertRecoveryWakeup(cycle.plan, now.plusSeconds(30), setOf(retry.id))
    }

    @Test
    fun earlierExactScheduleRemainsExactAndDeniedAccessUsesOnlyDurableFallback() {
        val exact = testDefinition(
            id = "automation.wakeup.earlier.exact",
            dtStart = LocalDateTime.of(2026, 1, 1, 8, 0, 10),
        ).copy(timingPolicy = AutomationTimingPolicy.USER_VISIBLE_EXACT)
        val pending = testDefinition(id = "automation.wakeup.exact.fallback")
        val storage = InMemoryAutomationStorage(
            AutomationStorageSnapshot(
                definitions = listOf(exact, pending),
                runs = listOf(storedRun(pending, AutomationRunState.PENDING, now.plusSeconds(30))),
            ),
        )

        val cycle = runCycle(storage)

        assertEquals(0, cycle.executions)
        assertEquals(now.plusSeconds(10), cycle.plan.wakeAt)
        assertEquals(setOf(exact.id), cycle.plan.automationIds)
        assertEquals(AutomationTimingPolicy.USER_VISIBLE_EXACT, cycle.plan.timingPolicy)
        assertEquals(Duration.ofSeconds(30), cycle.plan.maximumInexactDelay)
        val granted = AndroidAutomationWakeupPolicy.decide(cycle.plan, exactAlarmAccessGranted = true)
            as AndroidAutomationWakeupDecision.Ready
        assertEquals(
            AndroidAutomationWakeupRequest.ExactAlarm(now.plusSeconds(10), allowWhileIdle = true),
            granted.request,
        )
        val denied = AndroidAutomationWakeupPolicy.decide(cycle.plan, exactAlarmAccessGranted = false)
            as AndroidAutomationWakeupDecision.ExactAlarmAccessRequired
        assertEquals(now.plusSeconds(30), denied.fallback?.earliestAt)
        assertEquals(now.plusSeconds(60), denied.fallback?.overrideDeadlineAt)
    }

    @Test
    fun earlierNormalScheduleKeepsPriorityWhileDurableWorkCapsItsWindow() {
        val scheduled = testDefinition(
            id = "automation.wakeup.earlier.normal",
            dtStart = LocalDateTime.of(2026, 1, 1, 8, 0, 10),
        )
        val pending = testDefinition(id = "automation.wakeup.later.pending")
        val storage = InMemoryAutomationStorage(
            AutomationStorageSnapshot(
                definitions = listOf(scheduled, pending),
                runs = listOf(storedRun(pending, AutomationRunState.PENDING, now.plusSeconds(60))),
            ),
        )

        val cycle = runCycle(storage)

        assertRecoveryWakeup(cycle.plan, now.plusSeconds(10), setOf(scheduled.id))
        assertEquals(now.plusSeconds(60), storage.snapshot().runs.single().availableAt)
        assertEquals(0, cycle.executions)
    }

    @Test
    fun emptyRuntimeCancelsWakeupWithoutARecoveryWindow() {
        val cycle = runCycle(InMemoryAutomationStorage())

        assertEquals(0, cycle.executions)
        assertNull(cycle.plan.wakeAt)
        assertNull(cycle.plan.maximumInexactDelay)
        assertEquals(
            AndroidAutomationWakeupDecision.Ready(AndroidAutomationWakeupRequest.CancelExisting),
            AndroidAutomationWakeupPolicy.decide(cycle.plan, exactAlarmAccessGranted = false),
        )
    }

    @Test
    fun cancelledAndRemovedWorkDoesNotLeaveAnIdleRecoveryWakeup() {
        val definition = testDefinition(id = "automation.wakeup.removed")
        val storage = InMemoryAutomationStorage(
            AutomationStorageSnapshot(
                definitions = listOf(definition),
                runs = listOf(storedRun(definition, AutomationRunState.PENDING, now.plusSeconds(30))),
            ),
        )
        assertRecoveryWakeup(runCycle(storage).plan, now.plusSeconds(30), setOf(definition.id))
        val cancelled = storage.cancelDefinition(definition.id, definition.revision, now)
            as AutomationCancellationResult.Cancelled
        assertTrue(storage.removeDefinition(definition.id, cancelled.newRevision))

        val cycle = runCycle(storage)

        assertEquals(0, cycle.executions)
        assertEquals(AutomationRunState.SKIPPED, storage.snapshot().runs.single().state)
        assertNull(cycle.plan.wakeAt)
        assertNull(cycle.plan.maximumInexactDelay)
    }

    @Test
    fun exhaustedRetryKeepsOnlyTheOrdinaryFutureScheduleWindow() {
        val storage = failedAttemptStorage(maximumAttempts = 1)
        assertEquals(AutomationRunState.FAILED_TERMINAL, storage.snapshot().runs.single().state)

        val cycle = runCycle(storage)

        assertEquals(0, cycle.executions)
        assertNull(cycle.plan.maximumInexactDelay)
        assertEquals(Instant.parse("2026-01-01T09:00:00Z"), cycle.plan.wakeAt)
        assertDefaultWindow(cycle.plan)
    }

    private fun failedAttemptStorage(maximumAttempts: Int = 3): InMemoryAutomationStorage {
        val definition = testDefinition(
            id = "automation.wakeup.retry",
            retryPolicy = AutomationRetryPolicy(
                maximumAttempts = maximumAttempts,
                initialBackoff = Duration.ofSeconds(30),
                backoffMultiplier = 2,
                maximumBackoff = Duration.ofMinutes(1),
            ),
        ).copy(requirements = AutomationRequirements(
            confirmationPolicy = AutomationConfirmationPolicy.CAPABILITY_POLICY,
        ))
        val seed = InMemoryAutomationStorage()
        val key = seed.insertDueRun(definition, now)
        val claim = checkNotNull(seed.acquireNextLease(
            owner = AutomationWorkerId("wakeup-test-owner"),
            token = AutomationLeaseToken("lease-wakeup-retry"),
            bootSessionId = boot,
            now = now,
            leaseDuration = Duration.ofMinutes(2),
        ))
        assertEquals(
            if (maximumAttempts == 1) AutomationCompletionResult.FAILED_TERMINAL
            else AutomationCompletionResult.RETRY_SCHEDULED,
            seed.completeLease(key, claim.lease.token, now, AutomationCompletion.RetryableFailure("test_retry")),
        )
        return InMemoryAutomationStorage(seed.snapshot())
    }

    private fun storedRun(
        definition: AutomationDefinition,
        state: AutomationRunState,
        availableAt: Instant,
        attemptCount: Int = 0,
    ) = AutomationRun(
        key = AutomationRunKey(definition.id, now),
        definitionRevision = definition.revision,
        state = state,
        attemptCount = attemptCount,
        availableAt = availableAt,
        createdAt = now,
        updatedAt = now,
    )

    private fun assertRecoveryWakeup(plan: AutomationWakeupPlan, dueAt: Instant, ids: Set<AutomationId>) {
        assertEquals(dueAt, plan.wakeAt)
        assertEquals(dueAt, plan.nextInexactWakeAt)
        assertEquals(ids, plan.automationIds)
        assertEquals(AutomationTimingPolicy.RELIABLE_INEXACT, plan.timingPolicy)
        assertEquals(Duration.ofSeconds(30), plan.maximumInexactDelay)
        val decision = AndroidAutomationWakeupPolicy.decide(plan, exactAlarmAccessGranted = false)
            as AndroidAutomationWakeupDecision.Ready
        assertEquals(
            AndroidAutomationWakeupRequest.JobScheduler(dueAt, dueAt.plusSeconds(30), true),
            decision.request,
        )
    }

    private fun assertDefaultWindow(plan: AutomationWakeupPlan) {
        val decision = AndroidAutomationWakeupPolicy.decide(plan, exactAlarmAccessGranted = false)
            as AndroidAutomationWakeupDecision.Ready
        val request = decision.request as AndroidAutomationWakeupRequest.JobScheduler
        assertEquals(plan.wakeAt, request.earliestAt)
        assertEquals(plan.wakeAt!!.plusSeconds(15 * 60), request.overrideDeadlineAt)
    }

    private fun runCycle(storage: AutomationStorage, instant: Instant = now): Cycle {
        var observedPlan: AutomationWakeupPlan? = null
        var executions = 0
        var result: Result<AutomationRuntimeCycleReport>? = null
        val owner = AutomationRuntimeOwner(
            storage = storage,
            wakeupAdapter = object : AutomationWakeupAdapter {
                override fun replaceWakeup(plan: AutomationWakeupPlan): AutomationAdapterResult {
                    observedPlan = plan
                    return AutomationAdapterResult.Accepted
                }
            },
            cycleDispatcher = AutomationRuntimeCycleDispatcher { AutomationAdapterResult.Accepted },
            backgroundExecutor = Executor(Runnable::run),
            platformState = object : AutomationPlatformStateSource {
                override fun now(): Instant = instant
                override fun systemZone(): ZoneId = ZoneId.of("UTC")
                override fun bootSessionId(): AutomationBootSessionId = boot
            },
            liveEnvironment = AutomationLiveEnvironmentSource {
                AutomationExecutionEnvironment(
                    availableCapabilities = setOf(AutomationCapabilityId.CODEX_APP_SERVER),
                    grantedPermissions = emptySet(),
                    codexAuthenticated = true,
                    networkAvailable = true,
                    deviceUnlocked = true,
                )
            },
            codexExecutor = object : CodexAutomationExecutor {
                override fun execute(
                    request: CodexAutomationExecutionRequest,
                    heartbeat: AutomationHeartbeat,
                ): CodexAutomationExecutionOutcome {
                    assertTrue(heartbeat.markExternalDispatchStarted())
                    executions += 1
                    return CodexAutomationExecutionOutcome.Succeeded
                }
            },
        )
        owner.requestCycle(AutomationRuntimeTrigger.Timer) { result = it }
        checkNotNull(result).getOrThrow()
        return Cycle(checkNotNull(observedPlan), executions)
    }

    private data class Cycle(val plan: AutomationWakeupPlan, val executions: Int)
}
