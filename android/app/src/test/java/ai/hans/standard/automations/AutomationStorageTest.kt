package ai.hans.standard.automations

import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationStorageTest {
    @Test
    fun manualIdempotencyKeyCannotBeReusedForDifferentAutomationSemantics() {
        val storage = InMemoryAutomationStorage()
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val first = testDefinition(id = "automation.manual_first")
        val second = testDefinition(id = "automation.manual_second")
        storage.upsertDefinition(first)
        storage.upsertDefinition(second)
        val requestId = AutomationManualRequestId("c".repeat(64))

        assertTrue(
            storage.enqueueManualRun(first.id, first.revision, requestId, now) is
                AutomationManualEnqueueResult.Enqueued,
        )
        assertEquals(
            AutomationManualEnqueueResult.RequestConflict,
            storage.enqueueManualRun(second.id, second.revision, requestId, now),
        )
        assertEquals(1, storage.snapshot().manualInvocations.size)
        assertEquals(first.id, storage.snapshot().manualInvocations.single().key.automationId)
    }

    @Test
    fun removeDefinitionRequiresTheExactDisabledRevision() {
        val storage = InMemoryAutomationStorage()
        val id = AutomationId("revision.safe.delete")
        val disabled = testDefinition(id = id.value, revision = 2, enabled = false)
        assertEquals(DefinitionWriteResult.INSERTED, storage.upsertDefinition(disabled))

        assertFalse(storage.removeDefinition(id, expectedRevision = 1))
        assertEquals(disabled, storage.definition(id))
        assertTrue(storage.removeDefinition(id, expectedRevision = 2))
        assertNull(storage.definition(id))
    }

    @Test
    fun duplicateDiscoveryAndCrashReplayProduceExactlyOneRunKey() {
        val definition = testDefinition()
        val storage = InMemoryAutomationStorage()
        assertEquals(DefinitionWriteResult.INSERTED, storage.upsertDefinition(definition))
        val scheduledAt = Instant.parse("2026-01-02T09:00:00Z")
        val item = discoveredItem(definition, scheduledAt)
        val batch = AutomationDiscoveryBatch(
            definition.id,
            definition.revision,
            scheduledAt,
            listOf(item),
        )

        assertEquals(1, storage.recordDiscovery(batch).inserted)
        val duplicate = storage.recordDiscovery(batch)
        assertEquals(0, duplicate.inserted)
        assertEquals(1, duplicate.duplicates)

        val restored = InMemoryAutomationStorage(storage.snapshot())
        assertEquals(1, restored.recordDiscovery(batch).duplicates)
        assertEquals(listOf(item.key), restored.materializeDueInbox(scheduledAt).createdRuns)
        assertTrue(restored.materializeDueInbox(scheduledAt).createdRuns.isEmpty())
        assertEquals(listOf(item.key), restored.snapshot().runs.map { it.key })
    }

    @Test
    fun leaseClaimHeartbeatAndCompletionUseCompareAndSetOwnership() {
        val storage = InMemoryAutomationStorage()
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val definition = testDefinition()
        val key = storage.insertDueRun(definition, now)
        val owner = AutomationWorkerId("worker-one")
        val token = AutomationLeaseToken("lease-token-000001")
        val wrongToken = AutomationLeaseToken("lease-token-999999")
        val boot = AutomationBootSessionId("boot-0001")

        val claim = storage.acquireNextLease(
            owner,
            token,
            boot,
            now,
            Duration.ofMinutes(1),
        )
        assertEquals(key, claim?.run?.key)
        assertNull(
            storage.acquireNextLease(
                AutomationWorkerId("worker-two"),
                wrongToken,
                boot,
                now,
                Duration.ofMinutes(1),
            ),
        )
        assertEquals(
            AutomationHeartbeatResult.TOKEN_MISMATCH,
            storage.heartbeat(key, wrongToken, now.plusSeconds(10), Duration.ofMinutes(1)),
        )
        assertEquals(
            AutomationHeartbeatResult.INVALID_TIME,
            storage.heartbeat(key, token, now.minusSeconds(1), Duration.ofMinutes(1)),
        )
        assertEquals(
            AutomationHeartbeatResult.EXTENDED,
            storage.heartbeat(key, token, now.plusSeconds(10), Duration.ofMinutes(1)),
        )
        assertEquals(
            AutomationCompletionResult.TOKEN_MISMATCH,
            storage.completeLease(key, wrongToken, now.plusSeconds(20), AutomationCompletion.Succeeded),
        )
        assertEquals(
            AutomationCompletionResult.COMPLETED,
            storage.completeLease(key, token, now.plusSeconds(20), AutomationCompletion.Succeeded),
        )
        assertEquals(AutomationRunState.SUCCEEDED, storage.snapshot().runs.single().state)
        assertTrue(storage.snapshot().leases.isEmpty())
    }

    @Test
    fun anExpiredLeaseCannotBeRevivedByALateHeartbeatOrCompletion() {
        val storage = InMemoryAutomationStorage()
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val key = storage.insertDueRun(testDefinition(), now)
        val token = AutomationLeaseToken("lease-token-expired")
        storage.acquireNextLease(
            AutomationWorkerId("worker-one"),
            token,
            AutomationBootSessionId("boot-0001"),
            now,
            Duration.ofSeconds(30),
        )

        assertEquals(
            AutomationHeartbeatResult.LEASE_EXPIRED,
            storage.heartbeat(key, token, now.plusSeconds(30), Duration.ofMinutes(1)),
        )
        assertEquals(
            AutomationCompletionResult.LEASE_EXPIRED,
            storage.completeLease(
                key,
                token,
                now.plusSeconds(30),
                AutomationCompletion.Succeeded,
            ),
        )
        assertEquals(AutomationRunState.LEASED, storage.snapshot().runs.single().state)
    }

    @Test
    fun processDeathSnapshotRecoversExpiredLeaseWithDeterministicBackoff() {
        val storage = InMemoryAutomationStorage()
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val definition = testDefinition()
        val key = storage.insertDueRun(definition, now)
        storage.acquireNextLease(
            AutomationWorkerId("worker-one"),
            AutomationLeaseToken("lease-token-process"),
            AutomationBootSessionId("boot-0001"),
            now,
            Duration.ofSeconds(30),
        )

        val restored = InMemoryAutomationStorage(storage.snapshot())
        val recoveredAt = now.plusSeconds(31)
        val recovery = restored.recoverLeases(
            recoveredAt,
            AutomationBootSessionId("boot-0001"),
        )

        assertEquals(0, recovery.recoveredAfterBoot)
        assertEquals(1, recovery.recoveredAfterExpiry)
        assertEquals(1, recovery.retriesScheduled)
        val run = restored.snapshot().runs.single()
        assertEquals(key, run.key)
        assertEquals(AutomationRunState.RETRY_WAIT, run.state)
        assertEquals(recoveredAt.plusSeconds(10), run.availableAt)
        assertEquals("lease_expired", run.lastFailureCode)
        assertTrue(restored.snapshot().leases.isEmpty())
        assertNull(
            restored.acquireNextLease(
                AutomationWorkerId("worker-two"),
                AutomationLeaseToken("lease-token-too-early"),
                AutomationBootSessionId("boot-0001"),
                recoveredAt.plusSeconds(9),
                Duration.ofMinutes(1),
            ),
        )
    }

    @Test
    fun fencedProcessDeathIsAmbiguousAfterExpiryAndNeverRedispatchedForEitherGatewayPath() {
        listOf(
            CodexAutomationTarget.ThreadBound("thread-persisted-fence"),
            CodexAutomationTarget.Independent,
        ).forEachIndexed { index, target ->
            val now = Instant.parse("2026-01-02T09:00:00Z").plusSeconds(index.toLong())
            val storage = InMemoryAutomationStorage()
            val definition = testDefinition(
                id = "automation.fenced.expiry.$index",
                target = target,
            )
            val key = storage.insertDueRun(definition, now)
            val token = AutomationLeaseToken("lease-token-fenced-expiry-$index")
            storage.acquireNextLease(
                AutomationWorkerId("worker-before-process-death"),
                token,
                AutomationBootSessionId("boot-0001"),
                now,
                Duration.ofSeconds(30),
            )
            assertEquals(
                AutomationDispatchFenceMarkResult.MARKED,
                storage.markDispatchFence(key, token, now.plusSeconds(1), Duration.ofSeconds(30)),
            )

            val restored = InMemoryAutomationStorage(storage.snapshot())
            val recovery = restored.recoverLeases(
                now.plusSeconds(32),
                AutomationBootSessionId("boot-0001"),
            )

            assertEquals(1, recovery.recoveredAfterExpiry)
            assertEquals(0, recovery.retriesScheduled)
            assertEquals(1, recovery.terminalFailures)
            assertEquals(AutomationRunState.FAILED_TERMINAL, restored.snapshot().runs.single().state)
            assertEquals(
                "automation_outcome_ambiguous",
                restored.snapshot().runs.single().lastFailureCode,
            )
            assertTrue(restored.snapshot().runs.single().dispatchFence != null)
            assertNull(
                restored.acquireNextLease(
                    AutomationWorkerId("worker-must-not-redispatch"),
                    AutomationLeaseToken("lease-token-no-redispatch-$index"),
                    AutomationBootSessionId("boot-0001"),
                    now.plusSeconds(60),
                    Duration.ofMinutes(1),
                ),
            )
        }
    }

    @Test
    fun fencedLeaseRecoveryAfterRebootIsTerminalInsteadOfRetryable() {
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val storage = InMemoryAutomationStorage()
        val key = storage.insertDueRun(testDefinition(id = "automation.fenced.reboot"), now)
        val token = AutomationLeaseToken("lease-token-fenced-reboot")
        storage.acquireNextLease(
            AutomationWorkerId("worker-before-reboot"),
            token,
            AutomationBootSessionId("boot-old1"),
            now,
            Duration.ofHours(1),
        )
        storage.markDispatchFence(key, token, now.plusSeconds(1), Duration.ofHours(1))

        val recovery = InMemoryAutomationStorage(storage.snapshot()).let { restored ->
            restored.recoverLeases(now.plusSeconds(2), AutomationBootSessionId("boot-new1")) to
                restored.snapshot().runs.single()
        }

        assertEquals(1, recovery.first.recoveredAfterBoot)
        assertEquals(0, recovery.first.retriesScheduled)
        assertEquals(1, recovery.first.terminalFailures)
        assertEquals(AutomationRunState.FAILED_TERMINAL, recovery.second.state)
        assertEquals("automation_outcome_ambiguous", recovery.second.lastFailureCode)
    }

    @Test
    fun provenPreDispatchRejectionCanClearOnlyTheOwningLeaseFenceAndRetryNormally() {
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val storage = InMemoryAutomationStorage()
        val key = storage.insertDueRun(testDefinition(id = "automation.fence.clear"), now)
        val token = AutomationLeaseToken("lease-token-fence-owner")
        storage.acquireNextLease(
            AutomationWorkerId("worker-fence-owner"),
            token,
            AutomationBootSessionId("boot-0001"),
            now,
            Duration.ofMinutes(1),
        )
        assertEquals(
            AutomationDispatchFenceMarkResult.MARKED,
            storage.markDispatchFence(key, token, now.plusSeconds(1), Duration.ofMinutes(1)),
        )
        assertEquals(
            AutomationDispatchFenceClearResult.TOKEN_MISMATCH,
            storage.clearDispatchFenceAfterRejected(
                key,
                AutomationLeaseToken("lease-token-fence-wrong"),
                now.plusSeconds(2),
            ),
        )
        assertEquals(
            AutomationDispatchFenceClearResult.CLEARED,
            storage.clearDispatchFenceAfterRejected(key, token, now.plusSeconds(2)),
        )

        assertEquals(
            AutomationCompletionResult.RETRY_SCHEDULED,
            storage.completeLease(
                key,
                token,
                now.plusSeconds(3),
                AutomationCompletion.RetryableFailure("codex_dispatch_rejected"),
            ),
        )
        assertEquals(AutomationRunState.RETRY_WAIT, storage.snapshot().runs.single().state)
        assertNull(storage.snapshot().runs.single().dispatchFence)
    }

    @Test
    fun rebootSnapshotInvalidatesOldBootLeaseBeforeItsWallClockExpiry() {
        val storage = InMemoryAutomationStorage()
        val now = Instant.parse("2026-01-02T09:00:00Z")
        storage.insertDueRun(testDefinition(), now)
        storage.acquireNextLease(
            AutomationWorkerId("worker-one"),
            AutomationLeaseToken("lease-token-reboot1"),
            AutomationBootSessionId("boot-old1"),
            now,
            Duration.ofHours(1),
        )

        val restored = InMemoryAutomationStorage(storage.snapshot())
        val recovery = restored.recoverLeases(
            now.plusSeconds(5),
            AutomationBootSessionId("boot-new1"),
        )

        assertEquals(1, recovery.recoveredAfterBoot)
        assertEquals(0, recovery.recoveredAfterExpiry)
        assertEquals(AutomationRunState.RETRY_WAIT, restored.snapshot().runs.single().state)
        assertEquals(
            "lease_recovered_after_reboot",
            restored.snapshot().runs.single().lastFailureCode,
        )
    }

    @Test
    fun backwardWallClockChangeForceRecoversSameBootLease() {
        val storage = InMemoryAutomationStorage()
        val acquiredAt = Instant.parse("2026-01-02T09:00:00Z")
        storage.insertDueRun(testDefinition(), acquiredAt)
        val boot = AutomationBootSessionId("boot-0001")
        storage.acquireNextLease(
            AutomationWorkerId("worker-clock-change"),
            AutomationLeaseToken("lease-token-clock-change"),
            boot,
            acquiredAt,
            Duration.ofHours(1),
        )
        val correctedClock = acquiredAt.minus(Duration.ofHours(6))

        val recovery = storage.recoverLeases(
            correctedClock,
            boot,
            forceSameBootRecovery = true,
        )

        assertEquals(1, recovery.recoveredAfterClockChange)
        assertEquals(AutomationRunState.RETRY_WAIT, storage.snapshot().runs.single().state)
        assertEquals(
            "lease_recovered_after_clock_change",
            storage.snapshot().runs.single().lastFailureCode,
        )
        assertTrue(storage.snapshot().leases.isEmpty())
    }

    @Test
    fun retryBackoffSaturatesAndMaximumAttemptsBecomeTerminal() {
        val policy = AutomationRetryPolicy(
            maximumAttempts = 3,
            initialBackoff = Duration.ofSeconds(10),
            backoffMultiplier = 3,
            maximumBackoff = Duration.ofSeconds(20),
        )
        val definition = testDefinition(retryPolicy = policy)
        val storage = InMemoryAutomationStorage()
        var now = Instant.parse("2026-01-02T09:00:00Z")
        val key = storage.insertDueRun(definition, now)
        val boot = AutomationBootSessionId("boot-0001")

        repeat(3) { index ->
            val token = AutomationLeaseToken("lease-token-attempt${index + 1}")
            val claim = storage.acquireNextLease(
                AutomationWorkerId("worker-one"),
                token,
                boot,
                now,
                Duration.ofMinutes(1),
            )
            assertEquals(index + 1, claim?.run?.attemptCount)
            val result = storage.completeLease(
                key,
                token,
                now.plusSeconds(1),
                AutomationCompletion.RetryableFailure("network_offline"),
            )
            if (index < 2) {
                assertEquals(AutomationCompletionResult.RETRY_SCHEDULED, result)
                now = storage.snapshot().runs.single().availableAt
            } else {
                assertEquals(AutomationCompletionResult.FAILED_TERMINAL, result)
            }
        }

        val finalRun = storage.snapshot().runs.single()
        assertEquals(AutomationRunState.FAILED_TERMINAL, finalRun.state)
        assertEquals(3, finalRun.attemptCount)
        assertEquals(Duration.ofSeconds(20), policy.delayAfterFailure(3))
    }

    @Test
    fun revisionChangeMakesPreviouslyDiscoveredInboxWorkAuditableButUnexecutable() {
        val first = testDefinition(revision = 1)
        val second = testDefinition(revision = 2, instruction = "Neue Anweisung")
        val storage = InMemoryAutomationStorage()
        storage.upsertDefinition(first)
        val scheduledAt = Instant.parse("2026-01-02T09:00:00Z")
        storage.recordDiscovery(
            AutomationDiscoveryBatch(
                first.id,
                first.revision,
                scheduledAt,
                listOf(discoveredItem(first, scheduledAt)),
            ),
        )
        assertEquals(DefinitionWriteResult.UPDATED, storage.upsertDefinition(second))

        val materialized = storage.materializeDueInbox(scheduledAt)

        assertTrue(materialized.createdRuns.isEmpty())
        assertTrue(materialized.staleRunsSkipped.isEmpty())
        assertTrue(storage.snapshot().inbox.isEmpty())
        assertEquals(AutomationRunState.SKIPPED, storage.snapshot().runs.single().state)
        assertEquals(
            "automation_definition_updated",
            storage.snapshot().runs.single().lastFailureCode,
        )
    }

    @Test
    fun concurrentWorkersCanClaimAUniqueRunOnlyOnce() {
        val storage = InMemoryAutomationStorage()
        val now = Instant.parse("2026-01-02T09:00:00Z")
        storage.insertDueRun(testDefinition(), now)
        val executor = Executors.newFixedThreadPool(8)
        val claims = (0 until 32).map { index ->
            executor.submit<AutomationLeaseClaim?> {
                storage.acquireNextLease(
                    AutomationWorkerId("worker-${index.toString().padStart(3, '0')}"),
                    AutomationLeaseToken("lease-token-${index.toString().padStart(6, '0')}"),
                    AutomationBootSessionId("boot-0001"),
                    now,
                    Duration.ofMinutes(1),
                )
            }
        }.map { it.get(5, TimeUnit.SECONDS) }
        executor.shutdownNow()

        assertEquals(1, claims.count { it != null })
        assertEquals(1, storage.snapshot().leases.size)
        assertEquals(AutomationRunState.LEASED, storage.snapshot().runs.single().state)
    }

    @Test
    fun malformedOrInternallyInconsistentSnapshotsFailClosed() {
        val definition = testDefinition()
        assertThrows(IllegalArgumentException::class.java) {
            InMemoryAutomationStorage(
                AutomationStorageSnapshot(definitions = listOf(definition, definition)),
            )
        }
        assertFalse(
            InMemoryAutomationStorage().recordDiscovery(
                AutomationDiscoveryBatch(
                    definition.id,
                    definition.revision,
                    Instant.EPOCH,
                    emptyList(),
                ),
            ).accepted,
        )

        val scheduledAt = Instant.parse("2026-01-02T09:00:00Z")
        val item = discoveredItem(definition, scheduledAt)
        val run = AutomationRun(
            key = item.key,
            definitionRevision = definition.revision,
            state = AutomationRunState.PENDING,
            attemptCount = 0,
            availableAt = scheduledAt,
            createdAt = scheduledAt,
            updatedAt = scheduledAt,
        )
        assertThrows(IllegalArgumentException::class.java) {
            InMemoryAutomationStorage(
                AutomationStorageSnapshot(
                    definitions = listOf(definition),
                    inbox = listOf(item),
                    runs = listOf(run),
                ),
            )
        }
    }
}
