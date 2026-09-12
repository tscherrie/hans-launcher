package ai.hans.standard.automations

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationDeferredRecoveryTest {
    private val now = Instant.parse("2026-01-02T09:00:00Z")

    @Test
    fun userPresentImmediatelyRunsMatchingLockedDeferralExactlyOnce() {
        val storage = InMemoryAutomationStorage()
        val definition = unattendedDefinition("automation.recover.unlock").copy(
            requirements = unattendedRequirements.copy(requiresUnlockedDevice = true),
        )
        storage.insertDueRun(definition, now)
        val state = RecoveryPlatformState(now)
        var environment = allowedEnvironment.copy(deviceUnlocked = false)
        var executions = 0
        val owner = owner(storage, state, { environment }) { heartbeat ->
            assertTrue(heartbeat.markExternalDispatchStarted())
            executions += 1
            CodexAutomationExecutionOutcome.Succeeded
        }

        owner.requestCycle(AutomationRuntimeTrigger.Timer)

        val deferred = storage.snapshot().runs.single()
        assertEquals(AutomationRunState.RETRY_WAIT, deferred.state)
        assertEquals(AutomationRunWaitKind.DEFERRED_PRECONDITION, deferred.waitKind)
        assertEquals("device_unlock_required", deferred.lastFailureCode)
        assertEquals(now.plus(Duration.ofMinutes(15)), deferred.availableAt)
        assertEquals(0, deferred.attemptCount)
        assertEquals(0, executions)

        state.instant = now.plusSeconds(5)
        environment = environment.copy(deviceUnlocked = true)
        owner.requestCycle(AutomationRuntimeTrigger.UserPresent)
        owner.requestCycle(AutomationRuntimeTrigger.UserPresent)

        val succeeded = storage.snapshot().runs.single()
        assertEquals(AutomationRunState.SUCCEEDED, succeeded.state)
        assertEquals(null, succeeded.waitKind)
        assertEquals(1, succeeded.attemptCount)
        assertEquals(1, executions)
    }

    @Test
    fun validatedNetworkImmediatelyRunsMatchingOfflineDeferralExactlyOnce() {
        val storage = InMemoryAutomationStorage()
        storage.insertDueRun(unattendedDefinition("automation.recover.network"), now)
        val state = RecoveryPlatformState(now)
        var environment = allowedEnvironment.copy(networkAvailable = false)
        var executions = 0
        val owner = owner(storage, state, { environment }) { heartbeat ->
            assertTrue(heartbeat.markExternalDispatchStarted())
            executions += 1
            CodexAutomationExecutionOutcome.Succeeded
        }

        owner.requestCycle(AutomationRuntimeTrigger.Timer)

        val deferred = storage.snapshot().runs.single()
        assertEquals(AutomationRunState.RETRY_WAIT, deferred.state)
        assertEquals(AutomationRunWaitKind.DEFERRED_PRECONDITION, deferred.waitKind)
        assertEquals("network_offline", deferred.lastFailureCode)
        assertEquals(now.plus(Duration.ofMinutes(5)), deferred.availableAt)
        assertEquals(0, executions)

        state.instant = now.plusSeconds(5)
        environment = environment.copy(networkAvailable = true)
        owner.requestCycle(AutomationRuntimeTrigger.ConnectivityRestored)
        owner.requestCycle(AutomationRuntimeTrigger.ConnectivityRestored)

        assertEquals(AutomationRunState.SUCCEEDED, storage.snapshot().runs.single().state)
        assertEquals(1, executions)
    }

    @Test
    fun lifecycleEventsReleaseOnlyMatchingPreconditionsAndNeverRealBackoff() {
        val storage = InMemoryAutomationStorage()
        val unlock = unattendedDefinition("automation.recover.only.unlock")
        val network = unattendedDefinition("automation.recover.only.network")
        val genuineFailure = unattendedDefinition("automation.recover.real.failure")
        val unlockKey = storage.insertDueRun(unlock, now)
        defer(storage, unlockKey, "device_unlock_required", now.plus(Duration.ofMinutes(15)), 1)
        val networkKey = storage.insertDueRun(network, now)
        defer(storage, networkKey, "network_offline", now.plus(Duration.ofMinutes(5)), 2)
        val failureKey = storage.insertDueRun(genuineFailure, now)
        val failureToken = lease(storage, failureKey, 3)
        assertEquals(
            AutomationCompletionResult.RETRY_SCHEDULED,
            storage.completeLease(
                failureKey,
                failureToken,
                now.plusSeconds(1),
                AutomationCompletion.RetryableFailure("network_offline"),
            ),
        )
        val failureBackoff = storage.snapshot().runs.single { it.key == failureKey }.availableAt

        val unlockAt = now.plusSeconds(2)
        assertEquals(
            1,
            storage.releaseDeferredRuns(
                setOf(AutomationResolvedPrecondition.DEVICE_UNLOCKED),
                unlockAt,
            ),
        )
        assertEquals(unlockAt, storage.snapshot().runs.single { it.key == unlockKey }.availableAt)
        assertEquals(
            now.plus(Duration.ofMinutes(5)),
            storage.snapshot().runs.single { it.key == networkKey }.availableAt,
        )
        assertEquals(failureBackoff, storage.snapshot().runs.single { it.key == failureKey }.availableAt)

        val onlineAt = now.plusSeconds(3)
        assertEquals(
            1,
            storage.releaseDeferredRuns(
                setOf(AutomationResolvedPrecondition.VALIDATED_NETWORK),
                onlineAt,
            ),
        )
        assertEquals(onlineAt, storage.snapshot().runs.single { it.key == networkKey }.availableAt)
        val preserved = storage.snapshot().runs.single { it.key == failureKey }
        assertEquals(AutomationRunWaitKind.RETRY_BACKOFF, preserved.waitKind)
        assertEquals(failureBackoff, preserved.availableAt)
    }

    private fun defer(
        storage: InMemoryAutomationStorage,
        key: AutomationRunKey,
        errorCode: String,
        retryAt: Instant,
        sequence: Int,
    ) {
        val token = lease(storage, key, sequence)
        assertEquals(
            AutomationCompletionResult.DEFERRED,
            storage.completeLease(
                key,
                token,
                now.plusSeconds(1),
                AutomationCompletion.Deferred(errorCode, retryAt),
            ),
        )
    }

    private fun lease(
        storage: InMemoryAutomationStorage,
        expectedKey: AutomationRunKey,
        sequence: Int,
    ): AutomationLeaseToken {
        val token = AutomationLeaseToken("lease-recovery-${sequence.toString().padStart(4, '0')}")
        val claim = checkNotNull(storage.acquireNextLease(
            owner = AutomationWorkerId("worker-recovery"),
            token = token,
            bootSessionId = AutomationBootSessionId("boot-recovery"),
            now = now,
            leaseDuration = Duration.ofMinutes(2),
        ))
        assertEquals(expectedKey, claim.run.key)
        return token
    }

    private fun owner(
        storage: AutomationStorage,
        state: RecoveryPlatformState,
        environment: () -> AutomationExecutionEnvironment,
        execute: (AutomationHeartbeat) -> CodexAutomationExecutionOutcome,
    ) = AutomationRuntimeOwner(
        storage = storage,
        wakeupAdapter = object : AutomationWakeupAdapter {
            override fun replaceWakeup(plan: AutomationWakeupPlan): AutomationAdapterResult =
                AutomationAdapterResult.Accepted
        },
        cycleDispatcher = AutomationRuntimeCycleDispatcher { AutomationAdapterResult.Accepted },
        backgroundExecutor = Executor(Runnable::run),
        platformState = state,
        liveEnvironment = AutomationLiveEnvironmentSource(environment),
        codexExecutor = object : CodexAutomationExecutor {
            override fun execute(
                request: CodexAutomationExecutionRequest,
                heartbeat: AutomationHeartbeat,
            ): CodexAutomationExecutionOutcome = execute(heartbeat)
        },
        leaseTokenSource = object : AutomationLeaseTokenSource {
            private var sequence = 0
            override fun next(): AutomationLeaseToken {
                sequence += 1
                return AutomationLeaseToken(
                    "lease-runtime-recovery-${sequence.toString().padStart(4, '0')}",
                )
            }
        },
    )

    private fun unattendedDefinition(id: String) = testDefinition(id = id).copy(
        requirements = unattendedRequirements,
    )

    private val unattendedRequirements = AutomationRequirements(
        confirmationPolicy = AutomationConfirmationPolicy.CAPABILITY_POLICY,
    )

    private val allowedEnvironment = AutomationExecutionEnvironment(
        availableCapabilities = setOf(AutomationCapabilityId.CODEX_APP_SERVER),
        grantedPermissions = emptySet(),
        codexAuthenticated = true,
        networkAvailable = true,
        deviceUnlocked = true,
    )
}

private class RecoveryPlatformState(
    var instant: Instant,
) : AutomationPlatformStateSource {
    override fun now(): Instant = instant
    override fun systemZone(): ZoneId = ZoneId.of("UTC")
    override fun bootSessionId(): AutomationBootSessionId =
        AutomationBootSessionId("boot-recovery")
}
