package ai.hans.standard.automations

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationExecutionTest {
    @Test
    fun leaseRevokedAfterAuthorizationPreventsExecutorDispatch() {
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val storage = InMemoryAutomationStorage()
        val definition = testDefinition()
        storage.insertDueRun(definition, now)
        var executions = 0
        val runner = AutomationExecutionRunner(
            storage = storage,
            executor = object : CodexAutomationExecutor {
                override fun execute(
                    request: CodexAutomationExecutionRequest,
                    heartbeat: AutomationHeartbeat,
                ): CodexAutomationExecutionOutcome {
                    executions += 1
                    return CodexAutomationExecutionOutcome.Succeeded
                }
            },
            instantSource = MutableInstantSource(now),
            tokenSource = AutomationLeaseTokenSource {
                AutomationLeaseToken("lease-token-pre-dispatch")
            },
            workerId = AutomationWorkerId("worker-pre-dispatch"),
            bootSessionId = AutomationBootSessionId("boot-0001"),
            authorizationProvider = AutomationAuthorizationProvider { _, _ ->
                storage.cancelDefinition(definition.id, definition.revision, now)
                AutomationAuthorizationDecision.Allowed
            },
        )

        assertEquals(AutomationRunAttemptStatus.OWNERSHIP_LOST, runner.runNext().status)
        assertEquals(0, executions)
        assertEquals(AutomationRunState.SKIPPED, storage.snapshot().runs.single().state)
    }

    @Test
    fun jobCancellationDefersWithoutDispatchOrAttemptConsumption() {
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val storage = InMemoryAutomationStorage()
        storage.insertDueRun(testDefinition(), now)
        var executions = 0
        val runner = runner(
            storage,
            object : CodexAutomationExecutor {
                override fun execute(
                    request: CodexAutomationExecutionRequest,
                    heartbeat: AutomationHeartbeat,
                ): CodexAutomationExecutionOutcome {
                    executions += 1
                    return CodexAutomationExecutionOutcome.Succeeded
                }
            },
            MutableInstantSource(now),
        )

        assertEquals(
            AutomationRunAttemptStatus.DEFERRED,
            runner.runNext(cancellationRequested = { true }).status,
        )
        assertEquals(0, executions)
        assertEquals(0, storage.snapshot().runs.single().attemptCount)
        assertEquals("job_execution_stopped", storage.snapshot().runs.single().lastFailureCode)
    }

    @Test
    fun cancellationAfterClaimButBeforeExternalDispatchDefersWithoutRetryConsumption() {
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val storage = InMemoryAutomationStorage()
        storage.insertDueRun(testDefinition(), now)
        var cancelled = false
        val runner = runner(
            storage,
            object : CodexAutomationExecutor {
                override fun execute(
                    request: CodexAutomationExecutionRequest,
                    heartbeat: AutomationHeartbeat,
                ): CodexAutomationExecutionOutcome {
                    assertTrue(heartbeat.beat())
                    cancelled = true
                    return CodexAutomationExecutionOutcome.RetryableFailure(
                        "job_execution_stopped",
                    )
                }
            },
            MutableInstantSource(now),
        )

        assertEquals(
            AutomationRunAttemptStatus.DEFERRED,
            runner.runNext(cancellationRequested = { cancelled }).status,
        )
        val run = storage.snapshot().runs.single()
        assertEquals(AutomationRunState.RETRY_WAIT, run.state)
        assertEquals(0, run.attemptCount)
        assertEquals("job_execution_stopped", run.lastFailureCode)
    }

    @Test
    fun cancellationAfterExternalDispatchIsTerminalAmbiguousAndNeverRedispatched() {
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val storage = InMemoryAutomationStorage()
        storage.insertDueRun(testDefinition(), now)
        var cancelled = false
        val runner = runner(
            storage,
            object : CodexAutomationExecutor {
                override fun execute(
                    request: CodexAutomationExecutionRequest,
                    heartbeat: AutomationHeartbeat,
                ): CodexAutomationExecutionOutcome {
                    assertTrue(heartbeat.markExternalDispatchStarted())
                    cancelled = true
                    return CodexAutomationExecutionOutcome.RetryableFailure(
                        "job_execution_stopped",
                    )
                }
            },
            MutableInstantSource(now),
        )

        assertEquals(
            AutomationRunAttemptStatus.FAILED_TERMINAL,
            runner.runNext(cancellationRequested = { cancelled }).status,
        )
        val run = storage.snapshot().runs.single()
        assertEquals(AutomationRunState.FAILED_TERMINAL, run.state)
        assertEquals(1, run.attemptCount)
        assertEquals("automation_outcome_ambiguous", run.lastFailureCode)
        assertTrue(run.dispatchFence != null)
        assertTrue(storage.snapshot().leases.isEmpty())
    }

    @Test
    fun provenPreDispatchRejectionClearsPersistentFenceBeforeRetryCompletion() {
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val storage = InMemoryAutomationStorage()
        storage.insertDueRun(testDefinition(id = "automation.runner.proven.reject"), now)
        val runner = runner(
            storage,
            object : CodexAutomationExecutor {
                override fun execute(
                    request: CodexAutomationExecutionRequest,
                    heartbeat: AutomationHeartbeat,
                ): CodexAutomationExecutionOutcome {
                    assertTrue(heartbeat.markExternalDispatchStarted())
                    assertTrue(heartbeat.clearExternalDispatchAfterProvenRejection())
                    return CodexAutomationExecutionOutcome.RetryableFailure(
                        "codex_dispatch_rejected",
                    )
                }
            },
            MutableInstantSource(now),
        )

        assertEquals(AutomationRunAttemptStatus.RETRY_SCHEDULED, runner.runNext().status)
        val run = storage.snapshot().runs.single()
        assertEquals(AutomationRunState.RETRY_WAIT, run.state)
        assertEquals("codex_dispatch_rejected", run.lastFailureCode)
        assertNull(run.dispatchFence)
    }

    @Test
    fun knownSuccessClearsFenceWhilePermanentFailureRetainsAuditFence() {
        listOf(
            CodexAutomationExecutionOutcome.Succeeded,
            CodexAutomationExecutionOutcome.PermanentFailure("codex_request_invalid"),
        ).forEachIndexed { index, outcome ->
            val now = Instant.parse("2026-01-02T09:00:00Z").plusSeconds(index.toLong())
            val storage = InMemoryAutomationStorage()
            storage.insertDueRun(
                testDefinition(id = "automation.runner.known.terminal.$index"),
                now,
            )
            val runner = runner(
                storage,
                object : CodexAutomationExecutor {
                    override fun execute(
                        request: CodexAutomationExecutionRequest,
                        heartbeat: AutomationHeartbeat,
                    ): CodexAutomationExecutionOutcome {
                        assertTrue(heartbeat.markExternalDispatchStarted())
                        return outcome
                    }
                },
                MutableInstantSource(now),
            )

            val expectedStatus = if (outcome == CodexAutomationExecutionOutcome.Succeeded) {
                AutomationRunAttemptStatus.SUCCEEDED
            } else {
                AutomationRunAttemptStatus.FAILED_TERMINAL
            }
            assertEquals(expectedStatus, runner.runNext().status)
            if (outcome == CodexAutomationExecutionOutcome.Succeeded) {
                assertNull(storage.snapshot().runs.single().dispatchFence)
            } else {
                assertTrue(storage.snapshot().runs.single().dispatchFence != null)
                assertEquals(
                    "codex_request_invalid",
                    storage.snapshot().runs.single().lastFailureCode,
                )
            }
        }
    }

    @Test
    fun retryableOutcomeAfterExternalDispatchIsTerminalEvenWithoutJobCancellation() {
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val storage = InMemoryAutomationStorage()
        storage.insertDueRun(testDefinition(), now)
        val runner = runner(
            storage,
            object : CodexAutomationExecutor {
                override fun execute(
                    request: CodexAutomationExecutionRequest,
                    heartbeat: AutomationHeartbeat,
                ): CodexAutomationExecutionOutcome {
                    assertTrue(heartbeat.markExternalDispatchStarted())
                    return CodexAutomationExecutionOutcome.RetryableFailure(
                        "codex_completion_probe_failed",
                    )
                }
            },
            MutableInstantSource(now),
        )

        assertEquals(AutomationRunAttemptStatus.FAILED_TERMINAL, runner.runNext().status)
        assertEquals(
            "automation_outcome_ambiguous",
            storage.snapshot().runs.single().lastFailureCode,
        )
    }

    @Test
    fun threadBoundRequestKeepsItsThreadWhileIndependentRequestExplicitlyCreatesOne() {
        val key = AutomationRunKey(
            AutomationId("automation.test"),
            Instant.parse("2026-01-02T09:00:00Z"),
        )
        val bound = CodexAutomationExecutionRequest(
            key,
            1,
            CodexAutomationTarget.ThreadBound("thread-019f-test"),
            "Führe die Aufgabe aus.",
        )
        val independent = bound.copy(target = CodexAutomationTarget.Independent)

        assertEquals("thread-019f-test", bound.existingThreadId)
        assertFalse(bound.requestsIndependentThread)
        assertNull(independent.existingThreadId)
        assertTrue(independent.requestsIndependentThread)
        assertEquals(bound.idempotencyKey, independent.idempotencyKey)
        assertEquals("automation:automation.test:1767344400:0", bound.idempotencyKey)
    }

    @Test
    fun runnerBuildsTypedRequestAndCommitsSuccessfulLease() {
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val clock = MutableInstantSource(now)
        val definition = testDefinition(
            target = CodexAutomationTarget.ThreadBound("thread-019f-test"),
        )
        val storage = InMemoryAutomationStorage()
        val key = storage.insertDueRun(definition, now)
        val executor = RecordingExecutor(CodexAutomationExecutionOutcome.Succeeded)
        val runner = runner(storage, executor, clock)

        val result = runner.runNext()

        assertEquals(AutomationRunAttemptStatus.SUCCEEDED, result.status)
        assertEquals(key, result.runKey)
        assertEquals("thread-019f-test", executor.requests.single().existingThreadId)
        assertEquals(definition.instruction, executor.requests.single().instruction)
        assertEquals(key.stableIdempotencyKey(), executor.requests.single().idempotencyKey)
        assertEquals(AutomationRunState.SUCCEEDED, storage.snapshot().runs.single().state)
        assertTrue(storage.snapshot().leases.isEmpty())
    }

    @Test
    fun retryableOfflineOutcomeSurvivesUntilBackoffThenRunsAgain() {
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val clock = MutableInstantSource(now)
        val storage = InMemoryAutomationStorage()
        storage.insertDueRun(testDefinition(), now)
        val executor = SequencedExecutor(
            mutableListOf(
                CodexAutomationExecutionOutcome.RetryableFailure("network_offline"),
                CodexAutomationExecutionOutcome.Succeeded,
            ),
        )
        val runner = runner(storage, executor, clock)

        assertEquals(AutomationRunAttemptStatus.RETRY_SCHEDULED, runner.runNext().status)
        val retryWait = storage.snapshot().runs.single()
        assertEquals(AutomationRunWaitKind.RETRY_BACKOFF, retryWait.waitKind)
        val retryAt = retryWait.availableAt
        clock.value = retryAt.minusMillis(1)
        assertEquals(AutomationRunAttemptStatus.NO_RUN_AVAILABLE, runner.runNext().status)
        clock.value = retryAt
        assertEquals(AutomationRunAttemptStatus.SUCCEEDED, runner.runNext().status)
        assertEquals(2, executor.requests.size)
        assertEquals(AutomationRunState.SUCCEEDED, storage.snapshot().runs.single().state)
    }

    @Test
    fun executorExceptionBecomesStableRetryCodeWithoutLeakingExceptionText() {
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val storage = InMemoryAutomationStorage()
        storage.insertDueRun(testDefinition(), now)
        val runner = runner(
            storage,
            object : CodexAutomationExecutor {
                override fun execute(
                    request: CodexAutomationExecutionRequest,
                    heartbeat: AutomationHeartbeat,
                ): CodexAutomationExecutionOutcome {
                    throw IllegalStateException("secret user instruction and filesystem path")
                }
            },
            MutableInstantSource(now),
        )

        assertEquals(AutomationRunAttemptStatus.RETRY_SCHEDULED, runner.runNext().status)
        assertEquals("executor_exception", storage.snapshot().runs.single().lastFailureCode)
        assertFalse(
            storage.snapshot().runs.single().lastFailureCode.orEmpty().contains("secret"),
        )
    }

    @Test
    fun executorLosesOwnershipAfterLeaseExpiryAndCannotCommitSuccess() {
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val clock = MutableInstantSource(now)
        val storage = InMemoryAutomationStorage()
        storage.insertDueRun(testDefinition(), now)
        var heartbeatAccepted = true
        val executor = object : CodexAutomationExecutor {
            override fun execute(
                request: CodexAutomationExecutionRequest,
                heartbeat: AutomationHeartbeat,
            ): CodexAutomationExecutionOutcome {
                clock.value = now.plusSeconds(121)
                heartbeatAccepted = heartbeat.beat()
                return CodexAutomationExecutionOutcome.Succeeded
            }
        }

        val result = runner(storage, executor, clock).runNext()

        assertFalse(heartbeatAccepted)
        assertEquals(AutomationRunAttemptStatus.OWNERSHIP_LOST, result.status)
        assertEquals(AutomationRunState.LEASED, storage.snapshot().runs.single().state)
        assertEquals(1, storage.snapshot().leases.size)
    }

    private fun runner(
        storage: AutomationStorage,
        executor: CodexAutomationExecutor,
        clock: MutableInstantSource,
    ): AutomationExecutionRunner {
        var tokenSequence = 0
        return AutomationExecutionRunner(
            storage = storage,
            executor = executor,
            instantSource = clock,
            tokenSource = AutomationLeaseTokenSource {
                tokenSequence += 1
                AutomationLeaseToken("lease-token-runner-${tokenSequence.toString().padStart(4, '0')}")
            },
            workerId = AutomationWorkerId("worker-runner"),
            bootSessionId = AutomationBootSessionId("boot-0001"),
            authorizationProvider = AutomationAuthorizationProvider {
                _, _ -> AutomationAuthorizationDecision.Allowed
            },
            leaseDuration = Duration.ofMinutes(2),
        )
    }
}

private class MutableInstantSource(var value: Instant) : AutomationInstantSource {
    override fun now(): Instant = value
}

private class RecordingExecutor(
    private val outcome: CodexAutomationExecutionOutcome,
) : CodexAutomationExecutor {
    val requests = mutableListOf<CodexAutomationExecutionRequest>()

    override fun execute(
        request: CodexAutomationExecutionRequest,
        heartbeat: AutomationHeartbeat,
    ): CodexAutomationExecutionOutcome {
        requests += request
        assertTrue(heartbeat.beat())
        return outcome
    }
}

private class SequencedExecutor(
    private val outcomes: MutableList<CodexAutomationExecutionOutcome>,
) : CodexAutomationExecutor {
    val requests = mutableListOf<CodexAutomationExecutionRequest>()

    override fun execute(
        request: CodexAutomationExecutionRequest,
        heartbeat: AutomationHeartbeat,
    ): CodexAutomationExecutionOutcome {
        requests += request
        check(heartbeat.beat())
        // MutableList.removeFirst() can compile to Java 21's SequencedCollection method even
        // though Hans deliberately runs its host tests on the pinned Java 17 toolchain.
        return outcomes.removeAt(0)
    }
}
