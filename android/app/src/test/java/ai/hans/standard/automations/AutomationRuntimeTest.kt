package ai.hans.standard.automations

import ai.hans.standard.backup.HansBackupMaintenance
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationRuntimeTest {
    @Test
    fun runNowAndLifecycleSignalsUseTrackedPlatformDispatcher() {
        val now = Instant.parse("2026-01-01T08:00:00Z")
        val state = MutablePlatformState(now)
        val storage = InMemoryAutomationStorage()
        val definition = testDefinition()
        storage.upsertDefinition(definition)
        val dispatcher = RecordingCycleDispatcher()
        val gateway = RecordingGateway()
        val owner = owner(
            storage = storage,
            state = state,
            wakeups = RecordingWakeupAdapter(),
            gateway = gateway,
            dispatcher = dispatcher,
        )

        assertTrue(
            owner.enqueueRunNow(
                definition.id,
                definition.revision,
                AutomationManualRequestId("f".repeat(64)),
            ) is AutomationManualEnqueueResult.Enqueued,
        )
        assertEquals(AutomationAdapterResult.Accepted, owner.scheduleChanged())
        assertEquals(AutomationAdapterResult.Accepted, owner.connectivityRestored())
        assertEquals(AutomationAdapterResult.Accepted, owner.userPresent())

        assertEquals(
            listOf(
                AutomationRuntimeTrigger.ManualRun,
                AutomationRuntimeTrigger.DefinitionChanged,
                AutomationRuntimeTrigger.ConnectivityRestored,
                AutomationRuntimeTrigger.UserPresent,
            ),
            dispatcher.triggers,
        )
        assertEquals(0, gateway.independentExecutions)
        assertEquals(1, storage.snapshot().inbox.size)
    }

    @Test
    fun userPresentReconcilesLatestOverdueOccurrenceWithoutPolling() {
        val now = Instant.parse("2026-01-05T12:00:00Z")
        val state = MutablePlatformState(now)
        val storage = InMemoryAutomationStorage()
        val definition = testDefinition(id = "automation.unlock_catch_up").copy(
            requirements = AutomationRequirements(
                confirmationPolicy = AutomationConfirmationPolicy.CAPABILITY_POLICY,
            ),
        )
        storage.upsertDefinition(definition)
        val gateway = RecordingGateway()
        val owner = owner(storage, state, RecordingWakeupAdapter(), gateway)
        var report: AutomationRuntimeCycleReport? = null

        owner.requestCycle(AutomationRuntimeTrigger.UserPresent) {
            report = it.getOrThrow()
        }

        assertEquals(5, report?.reconciled?.occurrencesFound)
        assertEquals(1, report?.reconciled?.occurrencesSelected)
        assertEquals(1, report?.successfulRuns)
        assertEquals(1, gateway.independentExecutions)
        val run = storage.snapshot().runs.single()
        assertEquals(Instant.parse("2026-01-05T09:00:00Z"), run.key.scheduledAt)
        assertEquals(AutomationRunState.SUCCEEDED, run.state)
    }

    @Test
    fun explicitUiRunNowDurablyConfirmsOnlyThatEveryRunOccurrence() {
        val now = Instant.parse("2026-01-01T08:00:00Z")
        val state = MutablePlatformState(now)
        val storage = InMemoryAutomationStorage()
        val definition = testDefinition(id = "automation.ui_run_now")
        storage.upsertDefinition(definition)
        val gateway = RecordingGateway()
        val dispatcher = RecordingCycleDispatcher()
        val owner = owner(
            storage,
            state,
            RecordingWakeupAdapter(),
            gateway,
            dispatcher,
        )

        val enqueued = owner.enqueueUserApprovedRunNow(
            definition.id,
            definition.revision,
            AutomationManualRequestId("a".repeat(64)),
        ) as AutomationManualEnqueueResult.Enqueued

        assertEquals(listOf(AutomationRuntimeTrigger.ManualRun), dispatcher.triggers)
        assertEquals(setOf(enqueued.key), storage.confirmedRunKeys(now))
        owner.requestCycle(AutomationRuntimeTrigger.ManualRun)
        assertEquals(1, gateway.independentExecutions)
        assertEquals(AutomationRunState.SUCCEEDED, storage.snapshot().runs.single().state)
        assertTrue(storage.snapshot().confirmations.isEmpty())
    }

    @Test
    fun explicitUiRunNowKeepsBackupImportOutUntilPreparedRunIsDispatched() {
        val now = Instant.parse("2026-01-01T08:00:00Z")
        val state = MutablePlatformState(now)
        val delegate = InMemoryAutomationStorage()
        val confirmationWritten = CountDownLatch(1)
        val releasePreparation = CountDownLatch(1)
        val storage = ConfirmationHookAutomationStorage(delegate) {
            confirmationWritten.countDown()
            check(releasePreparation.await(5, TimeUnit.SECONDS))
        }
        val definition = testDefinition(id = "automation.ui_run_now.backup_order")
        storage.upsertDefinition(definition)
        val maintenance = HansBackupMaintenance()
        val enteredDispatch = CountDownLatch(1)
        val releaseDispatch = CountDownLatch(1)
        val importAttempted = CountDownLatch(1)
        val importEntered = CountDownLatch(1)
        val dispatcher = AutomationRuntimeCycleDispatcher { trigger ->
            assertEquals(AutomationRuntimeTrigger.ManualRun, trigger)
            enteredDispatch.countDown()
            check(releaseDispatch.await(5, TimeUnit.SECONDS))
            AutomationAdapterResult.Accepted
        }
        val owner = AutomationRuntimeOwner(
            storage = storage,
            wakeupAdapter = RecordingWakeupAdapter(),
            cycleDispatcher = dispatcher,
            backgroundExecutor = Executor(Runnable::run),
            platformState = state,
            liveEnvironment = allowedEnvironment(),
            codexExecutor = GatewayCodexAutomationExecutor(RecordingGateway()),
            backupMaintenance = maintenance,
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val enqueue = executor.submit<AutomationManualEnqueueResult> {
                owner.enqueueUserApprovedRunNow(
                    definition.id,
                    definition.revision,
                    AutomationManualRequestId("c".repeat(64)),
                )
            }
            assertTrue(confirmationWritten.await(5, TimeUnit.SECONDS))

            val import = executor.submit {
                importAttempted.countDown()
                maintenance.importExclusively { importEntered.countDown() }
            }
            assertTrue(importAttempted.await(5, TimeUnit.SECONDS))
            assertFalse(importEntered.await(250, TimeUnit.MILLISECONDS))

            // Let durable preparation return. The import must not slip into the handoff before
            // dispatch, and must remain excluded while dispatch itself is in progress.
            releasePreparation.countDown()
            assertTrue(enteredDispatch.await(5, TimeUnit.SECONDS))
            assertFalse(importEntered.await(250, TimeUnit.MILLISECONDS))

            releaseDispatch.countDown()
            assertTrue(enqueue.get(5, TimeUnit.SECONDS) is AutomationManualEnqueueResult.Enqueued)
            import.get(5, TimeUnit.SECONDS)
            assertTrue(importEntered.await(5, TimeUnit.SECONDS))
        } finally {
            releasePreparation.countDown()
            releaseDispatch.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun explicitUiRunNowConfirmationRemainsValidUntilItsTwentyFourHourBoundary() {
        val now = Instant.parse("2026-01-01T08:00:00Z")
        val state = MutablePlatformState(now)
        val storage = InMemoryAutomationStorage()
        val definition = testDefinition(id = "automation.ui_run_now.confirmation_expiry")
        storage.upsertDefinition(definition)
        val owner = owner(
            storage,
            state,
            RecordingWakeupAdapter(),
            RecordingGateway(),
            RecordingCycleDispatcher(),
        )

        val enqueued = owner.enqueueUserApprovedRunNow(
            definition.id,
            definition.revision,
            AutomationManualRequestId("d".repeat(64)),
        ) as AutomationManualEnqueueResult.Enqueued

        assertEquals(
            setOf(enqueued.key),
            storage.confirmedRunKeys(now.plus(Duration.ofHours(24)).minus(Duration.ofMinutes(1))),
        )
        assertFalse(
            enqueued.key in storage.confirmedRunKeys(now.plus(Duration.ofHours(24))),
        )
    }

    @Test
    fun runNowReportsPlatformDispatchFailureInsteadOfClaimingToolSuccess() {
        val now = Instant.parse("2026-01-01T08:00:00Z")
        val state = MutablePlatformState(now)
        val storage = InMemoryAutomationStorage()
        val definition = testDefinition(id = "automation.run.now.dispatch.failure")
        storage.upsertDefinition(definition)
        val owner = owner(
            storage = storage,
            state = state,
            wakeups = RecordingWakeupAdapter(),
            gateway = RecordingGateway(),
            dispatcher = AutomationRuntimeCycleDispatcher {
                AutomationAdapterResult.Rejected("job_scheduler_rejected")
            },
        )

        val result = owner.enqueueRunNow(
            definition.id,
            definition.revision,
            AutomationManualRequestId("e".repeat(64)),
        ) as AutomationManualEnqueueResult.DispatchRejected

        assertEquals("job_scheduler_rejected", result.errorCode)
        assertTrue(result.retryable)
        assertEquals(result.key, storage.snapshot().inbox.single().key)
    }

    @Test
    fun monotonicBudgetCheckpointsAfterCurrentRunAndLeavesLaterWorkPending() {
        val now = Instant.parse("2026-01-01T08:00:00Z")
        val state = MutablePlatformState(now)
        val storage = InMemoryAutomationStorage()
        val requirements = AutomationRequirements(
            confirmationPolicy = AutomationConfirmationPolicy.CAPABILITY_POLICY,
        )
        storage.insertDueRun(
            testDefinition(id = "automation.budget.first").copy(requirements = requirements),
            now,
        )
        storage.insertDueRun(
            testDefinition(id = "automation.budget.second").copy(requirements = requirements),
            now,
        )
        val monotonic = MutableMonotonicTimeSource()
        var executions = 0
        val owner = AutomationRuntimeOwner(
            storage = storage,
            wakeupAdapter = RecordingWakeupAdapter(),
            cycleDispatcher = RecordingCycleDispatcher(),
            backgroundExecutor = Executor(Runnable::run),
            platformState = state,
            liveEnvironment = allowedEnvironment(),
            codexExecutor = object : CodexAutomationExecutor {
                override fun execute(
                    request: CodexAutomationExecutionRequest,
                    heartbeat: AutomationHeartbeat,
                ): CodexAutomationExecutionOutcome {
                    assertTrue(heartbeat.markExternalDispatchStarted())
                    executions += 1
                    monotonic.nowNanos += Duration.ofSeconds(6).toNanos()
                    return CodexAutomationExecutionOutcome.Succeeded
                }
            },
            maximumCycleDuration = Duration.ofSeconds(5),
            monotonicTimeSource = monotonic,
        )
        var report: AutomationRuntimeCycleReport? = null

        owner.requestCycle(AutomationRuntimeTrigger.Timer) { report = it.getOrThrow() }

        assertEquals(1, executions)
        assertEquals(1, report?.executionAttempts)
        assertEquals(
            AutomationRuntimeCycleStopReason.TIME_BUDGET_EXHAUSTED,
            report?.stopReason,
        )
        assertEquals(
            listOf(AutomationRunState.PENDING, AutomationRunState.SUCCEEDED),
            storage.snapshot().runs.map { it.state }.sortedBy { it.ordinal },
        )
    }

    @Test
    fun incidentalCycleDefersDeniedExactWorkButStillRunsNormalAutomation() {
        val now = Instant.parse("2026-01-01T10:00:00Z")
        val state = MutablePlatformState(now)
        val storage = InMemoryAutomationStorage()
        val unattended = AutomationRequirements(
            confirmationPolicy = AutomationConfirmationPolicy.CAPABILITY_POLICY,
        )
        val exact = testDefinition(id = "automation.exact_due").copy(
            requirements = unattended,
            timingPolicy = AutomationTimingPolicy.USER_VISIBLE_EXACT,
        )
        val normal = testDefinition(
            id = "automation.normal_due",
            dtStart = java.time.LocalDateTime.of(2026, 1, 1, 10, 0),
        ).copy(requirements = unattended)
        storage.upsertDefinition(exact)
        storage.upsertDefinition(normal)
        val gateway = RecordingGateway()
        val owner = owner(storage, state, RecordingWakeupAdapter(), gateway)

        owner.requestCycle(AutomationRuntimeTrigger.Timer)

        val runs = storage.snapshot().runs.associateBy { it.key.automationId }
        assertEquals("exact_alarm_access_required", runs.getValue(exact.id).lastFailureCode)
        assertEquals(AutomationRunState.RETRY_WAIT, runs.getValue(exact.id).state)
        assertEquals(AutomationRunState.SUCCEEDED, runs.getValue(normal.id).state)
        assertEquals(1, gateway.independentExecutions)
    }

    @Test
    fun liveEnvironmentCannotForgeEveryRunConfirmationWithoutDurableReceipt() {
        val now = Instant.parse("2026-01-01T09:00:00Z")
        val state = MutablePlatformState(now)
        val storage = InMemoryAutomationStorage()
        val definition = testDefinition()
        val key = storage.insertDueRun(definition, now)
        val gateway = RecordingGateway()
        val owner = AutomationRuntimeOwner(
            storage = storage,
            wakeupAdapter = RecordingWakeupAdapter(),
            cycleDispatcher = RecordingCycleDispatcher(),
            backgroundExecutor = Executor(Runnable::run),
            platformState = state,
            liveEnvironment = AutomationLiveEnvironmentSource {
                AutomationExecutionEnvironment(
                    availableCapabilities = setOf(AutomationCapabilityId.CODEX_APP_SERVER),
                    grantedPermissions = emptySet(),
                    codexAuthenticated = true,
                    networkAvailable = true,
                    deviceUnlocked = true,
                    confirmedRuns = setOf(key),
                )
            },
            codexExecutor = GatewayCodexAutomationExecutor(gateway),
        )

        owner.requestCycle(AutomationRuntimeTrigger.Timer)

        assertEquals(0, gateway.independentExecutions)
        assertEquals("user_confirmation_required", storage.snapshot().runs.single().lastFailureCode)
    }

    @Test
    fun cancellingAnActiveCycleMakesGatewayHeartbeatFailBeforeItsSideEffect() {
        val now = Instant.parse("2026-01-01T09:00:00Z")
        val state = MutablePlatformState(now)
        val storage = InMemoryAutomationStorage()
        val definition = testDefinition().copy(
            requirements = AutomationRequirements(
                confirmationPolicy = AutomationConfirmationPolicy.CAPABILITY_POLICY,
            ),
        )
        storage.insertDueRun(definition, now)
        val enteredGateway = CountDownLatch(1)
        val continueGateway = CountDownLatch(1)
        var sideEffects = 0
        val gateway = object : AutomationCodexGateway {
            override fun executeInExistingThread(
                threadId: String,
                instruction: String,
                idempotencyKey: String,
                heartbeat: AutomationHeartbeat,
            ) = error("not used")

            override fun executeInNewThread(
                instruction: String,
                idempotencyKey: String,
                heartbeat: AutomationHeartbeat,
            ): CodexAutomationExecutionOutcome {
                enteredGateway.countDown()
                check(continueGateway.await(5, TimeUnit.SECONDS))
                if (!heartbeat.beat()) {
                    return CodexAutomationExecutionOutcome.RetryableFailure("job_execution_stopped")
                }
                sideEffects += 1
                return CodexAutomationExecutionOutcome.Succeeded
            }
        }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val owner = AutomationRuntimeOwner(
            storage = storage,
            wakeupAdapter = RecordingWakeupAdapter(),
            cycleDispatcher = RecordingCycleDispatcher(),
            backgroundExecutor = executor,
            platformState = state,
            liveEnvironment = AutomationLiveEnvironmentSource {
                AutomationExecutionEnvironment(
                    availableCapabilities = setOf(AutomationCapabilityId.CODEX_APP_SERVER),
                    grantedPermissions = emptySet(),
                    codexAuthenticated = true,
                    networkAvailable = true,
                    deviceUnlocked = true,
                )
            },
            codexExecutor = GatewayCodexAutomationExecutor(gateway),
        )

        val handle = owner.requestCycle(AutomationRuntimeTrigger.Timer)
        assertTrue(enteredGateway.await(5, TimeUnit.SECONDS))
        handle.cancel()
        continueGateway.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))

        assertEquals(0, sideEffects)
        assertEquals(AutomationRunState.RETRY_WAIT, storage.snapshot().runs.single().state)
    }

    @Test
    fun timerCycleReconcilesExecutesAndReplacesTheNextWakeup() {
        val now = Instant.parse("2026-01-01T09:00:00Z")
        val state = MutablePlatformState(now)
        val storage = InMemoryAutomationStorage()
        val definition = testDefinition().copy(
            requirements = AutomationRequirements(
                confirmationPolicy = AutomationConfirmationPolicy.CAPABILITY_POLICY,
            ),
        )
        storage.upsertDefinition(definition)
        val wakeups = RecordingWakeupAdapter()
        val gateway = RecordingGateway()
        val owner = owner(storage, state, wakeups, gateway)
        var report: AutomationRuntimeCycleReport? = null

        owner.requestCycle(AutomationRuntimeTrigger.Timer) { report = it.getOrThrow() }

        assertEquals(1, report?.materializedRuns)
        assertEquals(1, report?.successfulRuns)
        assertEquals(1, gateway.independentExecutions)
        assertEquals(AutomationRunState.SUCCEEDED, storage.snapshot().runs.single().state)
        assertEquals(Instant.parse("2026-01-02T09:00:00Z"), wakeups.plans.single().wakeAt)
    }

    @Test
    fun manualRequestIdIsDurablyIdempotentAndDoesNotAdvanceScheduleCursor() {
        val now = Instant.parse("2026-01-01T08:00:00Z")
        val state = MutablePlatformState(now)
        val storage = InMemoryAutomationStorage()
        val definition = testDefinition().copy(
            requirements = AutomationRequirements(
                confirmationPolicy = AutomationConfirmationPolicy.CAPABILITY_POLICY,
            ),
        )
        storage.upsertDefinition(definition)
        val requestId = AutomationManualRequestId("a".repeat(64))

        val first = storage.enqueueManualRun(
            definition.id,
            definition.revision,
            requestId,
            state.now(),
        )
        val duplicate = storage.enqueueManualRun(
            definition.id,
            definition.revision,
            requestId,
            state.now().plusSeconds(1),
        )

        assertTrue(first is AutomationManualEnqueueResult.Enqueued)
        assertEquals(
            (first as AutomationManualEnqueueResult.Enqueued).key,
            (duplicate as AutomationManualEnqueueResult.Duplicate).key,
        )
        assertEquals(1, storage.snapshot().manualInvocations.size)
        assertEquals(1, storage.snapshot().inbox.size)
        assertEquals(null, storage.schedulerCursor(definition.id))
    }

    @Test
    fun liveAuthorizationAndDurableConfirmationAreMergedAtExecutionTime() {
        val now = Instant.parse("2026-01-01T08:00:00Z")
        val state = MutablePlatformState(now)
        val storage = InMemoryAutomationStorage()
        val definition = testDefinition()
        storage.upsertDefinition(definition)
        val key = storage.enqueueManualRun(
            definition.id,
            definition.revision,
            AutomationManualRequestId("b".repeat(64)),
            now,
        ).let { (it as AutomationManualEnqueueResult.Enqueued).key }
        storage.materializeDueInbox(now)
        assertEquals(
            AutomationConfirmationWriteResult.STORED,
            storage.recordConfirmation(
                AutomationRunConfirmationReceipt(
                    AutomationConfirmationId("confirmation-runtime-0001"),
                    key,
                    definition.revision,
                    now,
                    now.plusSeconds(600),
                ),
                now,
            ),
        )
        val gateway = RecordingGateway()
        val owner = owner(storage, state, RecordingWakeupAdapter(), gateway)

        owner.requestCycle(AutomationRuntimeTrigger.Timer)

        assertEquals(1, gateway.independentExecutions)
        assertEquals(AutomationRunState.SUCCEEDED, storage.snapshot().runs.single().state)
        assertTrue(storage.snapshot().confirmations.isEmpty())
    }

    @Test
    fun simultaneousManualRequestsReceiveDistinctValidRunKeys() {
        val now = Instant.parse("2026-01-01T08:00:00Z")
        val storage = InMemoryAutomationStorage()
        val definition = testDefinition()
        storage.upsertDefinition(definition)

        val first = storage.enqueueManualRun(
            definition.id,
            definition.revision,
            AutomationManualRequestId("d".repeat(64)),
            now,
        ) as AutomationManualEnqueueResult.Enqueued
        val second = storage.enqueueManualRun(
            definition.id,
            definition.revision,
            AutomationManualRequestId("e".repeat(64)),
            now,
        ) as AutomationManualEnqueueResult.Enqueued

        assertTrue(first.key != second.key)
        assertEquals(2, storage.snapshot().inbox.size)
        assertTrue(storage.snapshot().inbox.all { it.readyAt >= it.key.scheduledAt })
    }

    @Test
    fun rejectedBackgroundExecutorCompletesWithASafeFailureInsteadOfHangingJobWork() {
        val state = MutablePlatformState(Instant.parse("2026-01-01T08:00:00Z"))
        val owner = AutomationRuntimeOwner(
            storage = InMemoryAutomationStorage(),
            wakeupAdapter = RecordingWakeupAdapter(),
            cycleDispatcher = RecordingCycleDispatcher(),
            backgroundExecutor = Executor { throw IllegalStateException("private executor detail") },
            platformState = state,
            liveEnvironment = AutomationLiveEnvironmentSource.FAIL_CLOSED,
            codexExecutor = GatewayCodexAutomationExecutor(RecordingGateway()),
        )
        var callbacks = 0
        var failureMessage: String? = null

        owner.requestCycle(AutomationRuntimeTrigger.Timer) { result ->
            callbacks += 1
            failureMessage = result.exceptionOrNull()?.message
        }

        assertEquals(1, callbacks)
        assertEquals("automation_runtime_executor_rejected", failureMessage)
    }

    private fun owner(
        storage: AutomationStorage,
        state: MutablePlatformState,
        wakeups: RecordingWakeupAdapter,
        gateway: RecordingGateway,
        dispatcher: AutomationRuntimeCycleDispatcher = RecordingCycleDispatcher(),
    ) = AutomationRuntimeOwner(
        storage = storage,
        wakeupAdapter = wakeups,
        cycleDispatcher = dispatcher,
        backgroundExecutor = Executor(Runnable::run),
        platformState = state,
        liveEnvironment = allowedEnvironment(),
        codexExecutor = GatewayCodexAutomationExecutor(gateway),
        leaseTokenSource = object : AutomationLeaseTokenSource {
            private var sequence = 0
            override fun next(): AutomationLeaseToken {
                sequence += 1
                return AutomationLeaseToken("lease-token-runtime-${sequence.toString().padStart(4, '0')}")
            }
        },
    )

    private fun allowedEnvironment() = AutomationLiveEnvironmentSource {
        AutomationExecutionEnvironment(
            availableCapabilities = setOf(AutomationCapabilityId.CODEX_APP_SERVER),
            grantedPermissions = emptySet(),
            codexAuthenticated = true,
            networkAvailable = true,
            deviceUnlocked = true,
        )
    }
}

private class MutablePlatformState(
    var instant: Instant,
    var zone: ZoneId = ZoneId.of("UTC"),
    var boot: AutomationBootSessionId = AutomationBootSessionId("boot-0001"),
) : AutomationPlatformStateSource {
    override fun now(): Instant = instant
    override fun systemZone(): ZoneId = zone
    override fun bootSessionId(): AutomationBootSessionId = boot
}

private class RecordingWakeupAdapter : AutomationWakeupAdapter {
    val plans = mutableListOf<AutomationWakeupPlan>()
    override fun replaceWakeup(plan: AutomationWakeupPlan): AutomationAdapterResult {
        plans += plan
        return AutomationAdapterResult.Accepted
    }
}

private class RecordingCycleDispatcher : AutomationRuntimeCycleDispatcher {
    val triggers = mutableListOf<AutomationRuntimeTrigger>()

    override fun dispatch(trigger: AutomationRuntimeTrigger): AutomationAdapterResult {
        triggers += trigger
        return AutomationAdapterResult.Accepted
    }
}

private class ConfirmationHookAutomationStorage(
    private val delegate: AutomationStorage,
    private val afterConfirmationWritten: () -> Unit,
) : AutomationStorage by delegate {
    override fun recordConfirmation(
        receipt: AutomationRunConfirmationReceipt,
        now: Instant,
    ): AutomationConfirmationWriteResult = delegate.recordConfirmation(receipt, now).also { result ->
        if (result == AutomationConfirmationWriteResult.STORED) afterConfirmationWritten()
    }
}

private class MutableMonotonicTimeSource(
    var nowNanos: Long = 0L,
) : AutomationMonotonicTimeSource {
    override fun nowNanos(): Long = nowNanos
}

private class RecordingGateway : AutomationCodexGateway {
    var independentExecutions = 0
    var threadExecutions = 0

    override fun executeInExistingThread(
        threadId: String,
        instruction: String,
        idempotencyKey: String,
        heartbeat: AutomationHeartbeat,
    ): CodexAutomationExecutionOutcome {
        check(heartbeat.beat())
        threadExecutions += 1
        return CodexAutomationExecutionOutcome.Succeeded
    }

    override fun executeInNewThread(
        instruction: String,
        idempotencyKey: String,
        heartbeat: AutomationHeartbeat,
    ): CodexAutomationExecutionOutcome {
        check(heartbeat.beat())
        independentExecutions += 1
        return CodexAutomationExecutionOutcome.Succeeded
    }
}
