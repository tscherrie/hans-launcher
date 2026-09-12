package ai.hans.standard.automations

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationAuthorizationAndCancellationTest {
    @Test
    fun exactAutomationCannotExecuteUntilExactAlarmSpecialAccessIsLive() {
        val key = AutomationRunKey(
            AutomationId("automation.exact"),
            Instant.parse("2026-01-02T09:00:00Z"),
        )
        val definition = testDefinition(id = key.automationId.value).copy(
            timingPolicy = AutomationTimingPolicy.USER_VISIBLE_EXACT,
            requirements = AutomationRequirements(
                confirmationPolicy = AutomationConfirmationPolicy.CAPABILITY_POLICY,
            ),
        )
        var grants = emptySet<AutomationPermissionId>()
        val provider = EnvironmentAutomationAuthorizationProvider {
            AutomationExecutionEnvironment(
                availableCapabilities = setOf(AutomationCapabilityId.CODEX_APP_SERVER),
                grantedPermissions = grants,
                codexAuthenticated = true,
                networkAvailable = true,
                deviceUnlocked = true,
            )
        }

        assertEquals(
            "exact_alarm_access_required",
            (provider.authorize(definition, key) as AutomationAuthorizationDecision.Deferred).errorCode,
        )
        grants = setOf(AutomationPermissionId.SCHEDULE_EXACT_ALARM)
        assertEquals(AutomationAuthorizationDecision.Allowed, provider.authorize(definition, key))
    }

    @Test
    fun defaultRequirementsFailClosedUntilCapabilityLoginNetworkAndConfirmationArePresent() {
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val key = AutomationRunKey(AutomationId("automation.test"), now)
        var environment = AutomationExecutionEnvironment(
            availableCapabilities = emptySet(),
            grantedPermissions = emptySet(),
            codexAuthenticated = false,
            networkAvailable = false,
            deviceUnlocked = true,
        )
        val provider = EnvironmentAutomationAuthorizationProvider { environment }
        val definition = testDefinition()

        assertEquals(
            "capability_unavailable",
            (provider.authorize(definition, key) as AutomationAuthorizationDecision.Deferred).errorCode,
        )
        environment = environment.copy(
            availableCapabilities = setOf(AutomationCapabilityId.CODEX_APP_SERVER),
        )
        assertEquals(
            "codex_login_required",
            (provider.authorize(definition, key) as AutomationAuthorizationDecision.Deferred).errorCode,
        )
        environment = environment.copy(codexAuthenticated = true)
        assertEquals(
            "network_offline",
            (provider.authorize(definition, key) as AutomationAuthorizationDecision.Deferred).errorCode,
        )
        environment = environment.copy(networkAvailable = true)
        assertEquals(
            "user_confirmation_required",
            (provider.authorize(definition, key) as AutomationAuthorizationDecision.Deferred).errorCode,
        )
        environment = environment.copy(confirmedRuns = setOf(key))
        assertEquals(AutomationAuthorizationDecision.Allowed, provider.authorize(definition, key))
    }

    @Test
    fun unmetPermissionDefersWithoutCallingExecutorOrConsumingAnAttempt() {
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val clock = AuthorizationTestClock(now)
        val storage = InMemoryAutomationStorage()
        val definition = testDefinition().copy(
            requirements = AutomationRequirements(
                requiredPermissions = setOf(
                    AutomationPermissionId("android.permission.READ_CALENDAR"),
                ),
                confirmationPolicy = AutomationConfirmationPolicy.CAPABILITY_POLICY,
            ),
        )
        val key = storage.insertDueRun(definition, now)
        var permissionGranted = false
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
            instantSource = clock,
            tokenSource = sequencedTokenSource(),
            workerId = AutomationWorkerId("worker-authorization"),
            bootSessionId = AutomationBootSessionId("boot-0001"),
            authorizationProvider = EnvironmentAutomationAuthorizationProvider {
                AutomationExecutionEnvironment(
                    availableCapabilities = setOf(AutomationCapabilityId.CODEX_APP_SERVER),
                    grantedPermissions = if (permissionGranted) {
                        setOf(AutomationPermissionId("android.permission.READ_CALENDAR"))
                    } else {
                        emptySet()
                    },
                    codexAuthenticated = true,
                    networkAvailable = true,
                    deviceUnlocked = true,
                )
            },
        )

        assertEquals(AutomationRunAttemptStatus.DEFERRED, runner.runNext().status)
        assertEquals(0, executions)
        val deferred = storage.snapshot().runs.single()
        assertEquals(0, deferred.attemptCount)
        assertEquals("permission_required", deferred.lastFailureCode)
        assertEquals(AutomationRunState.RETRY_WAIT, deferred.state)

        permissionGranted = true
        clock.value = deferred.availableAt
        assertEquals(AutomationRunAttemptStatus.SUCCEEDED, runner.runNext().status)
        assertEquals(1, executions)
        assertEquals(key, storage.snapshot().runs.single().key)
        assertEquals(1, storage.snapshot().runs.single().attemptCount)
    }

    @Test
    fun cancellationRevokesLeaseDisablesDefinitionAndLeavesAuditableHistory() {
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val storage = InMemoryAutomationStorage()
        val definition = testDefinition()
        val key = storage.insertDueRun(definition, now)
        val token = AutomationLeaseToken("lease-token-cancelled")
        storage.acquireNextLease(
            AutomationWorkerId("worker-cancelled"),
            token,
            AutomationBootSessionId("boot-0001"),
            now,
            Duration.ofMinutes(2),
        )

        val result = storage.cancelDefinition(definition.id, 1, now.plusSeconds(5))

        assertTrue(result is AutomationCancellationResult.Cancelled)
        result as AutomationCancellationResult.Cancelled
        assertEquals(2, result.newRevision)
        assertEquals(1, result.runsCancelled)
        assertEquals(1, result.leasesRevoked)
        assertFalse(storage.definition(definition.id)!!.enabled)
        assertEquals(2, storage.definition(definition.id)!!.revision)
        assertEquals(AutomationRunState.SKIPPED, storage.snapshot().runs.single().state)
        assertEquals("automation_cancelled", storage.snapshot().runs.single().lastFailureCode)
        assertTrue(storage.snapshot().leases.isEmpty())
        assertEquals(
            AutomationHeartbeatResult.LEASE_NOT_FOUND,
            storage.heartbeat(key, token, now.plusSeconds(6), Duration.ofMinutes(2)),
        )
        assertNull(
            storage.acquireNextLease(
                AutomationWorkerId("worker-after-cancel"),
                AutomationLeaseToken("lease-token-after-cancel"),
                AutomationBootSessionId("boot-0001"),
                now.plusSeconds(6),
                Duration.ofMinutes(2),
            ),
        )
    }

    @Test
    fun exactConfirmationImmediatelyReleasesAConfirmationDeferredRun() {
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val storage = InMemoryAutomationStorage()
        val definition = testDefinition()
        val key = storage.insertDueRun(definition, now)
        val token = AutomationLeaseToken("lease-token-await-confirmation")
        storage.acquireNextLease(
            AutomationWorkerId("worker-await-confirmation"),
            token,
            AutomationBootSessionId("boot-0001"),
            now,
            Duration.ofMinutes(2),
        )
        assertEquals(
            AutomationCompletionResult.DEFERRED,
            storage.completeLease(
                key,
                token,
                now.plusSeconds(1),
                AutomationCompletion.Deferred(
                    "user_confirmation_required",
                    now.plus(Duration.ofMinutes(15)),
                ),
            ),
        )

        val confirmedAt = now.plusSeconds(2)
        assertEquals(
            AutomationConfirmationWriteResult.STORED,
            storage.recordConfirmation(
                AutomationRunConfirmationReceipt(
                    AutomationConfirmationId("confirmation-immediate-release"),
                    key,
                    definition.revision,
                    confirmedAt,
                    confirmedAt.plus(Duration.ofMinutes(30)),
                ),
                confirmedAt,
            ),
        )

        val run = storage.snapshot().runs.single()
        assertEquals(AutomationRunState.PENDING, run.state)
        assertEquals(confirmedAt, run.availableAt)
        assertEquals(null, run.lastFailureCode)
        assertTrue(key in storage.confirmedRunKeys(confirmedAt))
    }

    @Test
    fun exactConfirmationDoesNotBypassARealRetryBackoffWithTheSameErrorCode() {
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val storage = InMemoryAutomationStorage()
        val definition = testDefinition()
        val key = storage.insertDueRun(definition, now)
        val token = AutomationLeaseToken("lease-token-real-confirmation-backoff")
        storage.acquireNextLease(
            AutomationWorkerId("worker-real-confirmation-backoff"),
            token,
            AutomationBootSessionId("boot-0001"),
            now,
            Duration.ofMinutes(2),
        )
        assertEquals(
            AutomationCompletionResult.RETRY_SCHEDULED,
            storage.completeLease(
                key,
                token,
                now.plusSeconds(1),
                AutomationCompletion.RetryableFailure("user_confirmation_required"),
            ),
        )
        val backoff = storage.snapshot().runs.single()
        assertEquals(AutomationRunState.RETRY_WAIT, backoff.state)
        assertEquals(AutomationRunWaitKind.RETRY_BACKOFF, backoff.waitKind)

        val confirmedAt = now.plusSeconds(2)
        assertEquals(
            AutomationConfirmationWriteResult.STORED,
            storage.recordConfirmation(
                AutomationRunConfirmationReceipt(
                    AutomationConfirmationId("confirmation-does-not-bypass-real-backoff"),
                    key,
                    definition.revision,
                    confirmedAt,
                    confirmedAt.plus(Duration.ofMinutes(30)),
                ),
                confirmedAt,
            ),
        )

        val stillWaiting = storage.snapshot().runs.single()
        assertEquals(AutomationRunState.RETRY_WAIT, stillWaiting.state)
        assertEquals(AutomationRunWaitKind.RETRY_BACKOFF, stillWaiting.waitKind)
        assertEquals(backoff.availableAt, stillWaiting.availableAt)
        assertEquals("user_confirmation_required", stillWaiting.lastFailureCode)
        assertTrue(key in storage.confirmedRunKeys(confirmedAt))
    }

    @Test
    fun oneConfirmationReceiptIdCannotAuthorizeTwoDifferentRuns() {
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val storage = InMemoryAutomationStorage()
        val definition = testDefinition()
        val first = storage.insertDueRun(definition, now)
        val secondAt = now.plusSeconds(1)
        storage.recordDiscovery(
            AutomationDiscoveryBatch(
                definition.id,
                definition.revision,
                secondAt,
                listOf(discoveredItem(definition, secondAt)),
            ),
        )
        storage.materializeDueInbox(secondAt)
        val receiptId = AutomationConfirmationId("confirmation-one-use-only")

        assertEquals(
            AutomationConfirmationWriteResult.STORED,
            storage.recordConfirmation(
                AutomationRunConfirmationReceipt(
                    receiptId,
                    first,
                    definition.revision,
                    secondAt,
                    secondAt.plusSeconds(600),
                ),
                secondAt,
            ),
        )
        assertEquals(
            AutomationConfirmationWriteResult.CONFIRMATION_ID_CONFLICT,
            storage.recordConfirmation(
                AutomationRunConfirmationReceipt(
                    receiptId,
                    AutomationRunKey(definition.id, secondAt),
                    definition.revision,
                    secondAt,
                    secondAt.plusSeconds(600),
                ),
                secondAt,
            ),
        )
        assertEquals(setOf(first), storage.confirmedRunKeys(secondAt))
    }

    private fun sequencedTokenSource(): AutomationLeaseTokenSource {
        var sequence = 0
        return AutomationLeaseTokenSource {
            sequence += 1
            AutomationLeaseToken("lease-token-authorization-${sequence.toString().padStart(3, '0')}")
        }
    }
}

private class AuthorizationTestClock(var value: Instant) : AutomationInstantSource {
    override fun now(): Instant = value
}
