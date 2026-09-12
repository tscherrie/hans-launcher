package ai.hans.standard.automations

import java.time.Duration
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationPersistenceTest {
    @Test
    fun failedHeartbeatDoesNotRewriteAnUnchangedPersistentSnapshot() {
        val persistence = MemorySnapshotPersistence()
        val storage = PersistentAutomationStorage(persistence)
        val now = Instant.parse("2026-01-02T09:00:00Z")
        storage.upsertDefinition(testDefinition())
        val writesBefore = persistence.writeCount

        assertEquals(
            AutomationHeartbeatResult.LEASE_NOT_FOUND,
            storage.heartbeat(
                AutomationRunKey(AutomationId("automation.test"), now),
                AutomationLeaseToken("lease-token-no-op-write"),
                now,
                Duration.ofMinutes(1),
            ),
        )

        assertEquals(writesBefore, persistence.writeCount)
    }

    @Test
    fun definitionsInboxRunsLeasesAndRecoveryRoundTripWithoutLosingRequirements() {
        val persistence = MemorySnapshotPersistence()
        val storage = PersistentAutomationStorage(persistence)
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val definition = testDefinition(
            target = CodexAutomationTarget.ThreadBound("thread-019f-persistent"),
        ).copy(
            requirements = AutomationRequirements(
                requiredCapabilities = setOf(
                    AutomationCapabilityId.CODEX_APP_SERVER,
                    AutomationCapabilityId("android.calendar.read"),
                ),
                requiredPermissions = setOf(
                    AutomationPermissionId("android.permission.READ_CALENDAR"),
                ),
                requiresUnlockedDevice = true,
                confirmationPolicy = AutomationConfirmationPolicy.CAPABILITY_POLICY,
            ),
            timingPolicy = AutomationTimingPolicy.USER_VISIBLE_EXACT,
        )
        storage.upsertDefinition(definition)
        storage.recordDiscovery(
            AutomationDiscoveryBatch(
                definition.id,
                definition.revision,
                now,
                listOf(discoveredItem(definition, now)),
            ),
        )
        storage.materializeDueInbox(now)
        val token = AutomationLeaseToken("lease-token-persistent")
        val claim = storage.acquireNextLease(
            AutomationWorkerId("worker-persistent"),
            token,
            AutomationBootSessionId("boot-0001"),
            now,
            Duration.ofMinutes(2),
        )
        assertEquals(
            AutomationDispatchFenceMarkResult.MARKED,
            storage.markDispatchFence(
                checkNotNull(claim).run.key,
                token,
                now.plusSeconds(1),
                Duration.ofMinutes(2),
            ),
        )
        storage.storeRecoveryState(
            AutomationRecoveryState(
                AutomationBootSessionId("boot-0001"),
                "Europe/Oslo",
                now,
            ),
        )

        val restored = PersistentAutomationStorage(persistence)

        assertEquals(storage.snapshot(), restored.snapshot())
        assertEquals(
            setOf(AutomationPermissionId("android.permission.READ_CALENDAR")),
            restored.definition(definition.id)?.requirements?.requiredPermissions,
        )
        assertEquals(
            AutomationTimingPolicy.USER_VISIBLE_EXACT,
            restored.definition(definition.id)?.timingPolicy,
        )
        assertEquals(token, restored.snapshot().runs.single().dispatchFence?.leaseToken)
    }

    @Test
    fun legacyVersionOneRunWithoutDispatchFenceMigratesToSafePreDispatchState() {
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val definition = testDefinition(id = "automation.legacy.v1")
        val currentJson = JSONObject(
            AutomationSnapshotJsonCodec.encode(
                AutomationStorageSnapshot(
                    definitions = listOf(definition),
                    runs = listOf(
                        AutomationRun(
                            key = AutomationRunKey(definition.id, now),
                            definitionRevision = definition.revision,
                            state = AutomationRunState.PENDING,
                            attemptCount = 0,
                            availableAt = now,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    ),
                ),
            ),
        )
        currentJson.put("version", 1)
        currentJson.getJSONArray("runs").getJSONObject(0).remove("dispatchFence")

        val restored = PersistentAutomationStorage(
            MemorySnapshotPersistence(currentJson.toString().toByteArray()),
        )

        assertEquals(definition, restored.definition(definition.id))
        assertEquals(AutomationRunState.PENDING, restored.snapshot().runs.single().state)
        assertEquals(null, restored.snapshot().runs.single().dispatchFence)
    }

    @Test
    fun legacyLeasedRunWithoutFenceBecomesTerminalAndCannotReachRecoveryOrExecutor() {
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val definition = testDefinition(id = "automation.legacy.leased")
        val sourcePersistence = MemorySnapshotPersistence()
        val source = PersistentAutomationStorage(sourcePersistence)
        source.upsertDefinition(definition)
        source.recordDiscovery(
            AutomationDiscoveryBatch(
                definition.id,
                definition.revision,
                now,
                listOf(discoveredItem(definition, now)),
            ),
        )
        source.materializeDueInbox(now)
        source.acquireNextLease(
            AutomationWorkerId("worker-legacy-before-upgrade"),
            AutomationLeaseToken("lease-token-legacy-before-upgrade"),
            AutomationBootSessionId("boot-legacy-before-upgrade"),
            now,
            Duration.ofMinutes(2),
        )
        val legacyJson = JSONObject(
            checkNotNull(sourcePersistence.bytes).toString(Charsets.UTF_8),
        ).apply {
            put("version", 1)
            getJSONArray("runs").getJSONObject(0).remove("dispatchFence")
        }

        val restored = PersistentAutomationStorage(
            MemorySnapshotPersistence(legacyJson.toString().toByteArray()),
        )
        val migrated = restored.snapshot().runs.single()
        assertEquals(AutomationRunState.FAILED_TERMINAL, migrated.state)
        assertEquals("automation_outcome_ambiguous", migrated.lastFailureCode)
        assertEquals(null, migrated.dispatchFence)
        assertTrue(restored.snapshot().leases.isEmpty())

        val recovery = restored.recoverLeases(
            now.plus(Duration.ofDays(1)),
            AutomationBootSessionId("boot-legacy-after-upgrade"),
            forceSameBootRecovery = true,
        )
        assertEquals(0, recovery.recoveredAfterBoot)
        assertEquals(0, recovery.recoveredAfterExpiry)
        assertEquals(0, recovery.retriesScheduled)
        assertEquals(0, recovery.terminalFailures)

        var executions = 0
        val runner = AutomationExecutionRunner(
            storage = restored,
            executor = object : CodexAutomationExecutor {
                override fun execute(
                    request: CodexAutomationExecutionRequest,
                    heartbeat: AutomationHeartbeat,
                ): CodexAutomationExecutionOutcome {
                    executions += 1
                    return CodexAutomationExecutionOutcome.Succeeded
                }
            },
            instantSource = AutomationInstantSource { now.plus(Duration.ofDays(1)) },
            tokenSource = AutomationLeaseTokenSource {
                AutomationLeaseToken("lease-token-must-never-be-used")
            },
            workerId = AutomationWorkerId("worker-legacy-after-upgrade"),
            bootSessionId = AutomationBootSessionId("boot-legacy-after-upgrade"),
            authorizationProvider = AutomationAuthorizationProvider { _, _ ->
                AutomationAuthorizationDecision.Allowed
            },
        )
        assertEquals(AutomationRunAttemptStatus.NO_RUN_AVAILABLE, runner.runNext().status)
        assertEquals(0, executions)
    }

    @Test
    fun legacyPendingAndRetryWaitRunsRemainRetryablePreDispatchWork() {
        val now = Instant.parse("2026-01-02T09:00:00Z")
        AutomationRunState.entries
            .filter { it in setOf(AutomationRunState.PENDING, AutomationRunState.RETRY_WAIT) }
            .forEachIndexed { index, state ->
                val definition = testDefinition(id = "automation.legacy.safe.$index")
                val snapshot = AutomationStorageSnapshot(
                    definitions = listOf(definition),
                    runs = listOf(
                        AutomationRun(
                            key = AutomationRunKey(definition.id, now),
                            definitionRevision = definition.revision,
                            state = state,
                            attemptCount = index,
                            availableAt = now,
                            createdAt = now,
                            updatedAt = now,
                            lastFailureCode = if (state == AutomationRunState.RETRY_WAIT) {
                                "legacy_retryable_failure"
                            } else {
                                null
                            },
                        ),
                    ),
                )
                val root = JSONObject(AutomationSnapshotJsonCodec.encode(snapshot)).apply {
                    put("version", 1)
                    getJSONArray("runs").getJSONObject(0).remove("dispatchFence")
                }

                val restored = PersistentAutomationStorage(
                    MemorySnapshotPersistence(root.toString().toByteArray()),
                ).snapshot().runs.single()
                assertEquals(state, restored.state)
                assertEquals(null, restored.dispatchFence)
        }
    }

    @Test
    fun deferredPreconditionKindSurvivesPersistenceAndCanBeReleasedAfterRestart() {
        val persistence = MemorySnapshotPersistence()
        val storage = PersistentAutomationStorage(persistence)
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val definition = testDefinition(id = "automation.persisted.precondition")
        val key = storage.run {
            upsertDefinition(definition)
            recordDiscovery(
                AutomationDiscoveryBatch(
                    definition.id,
                    definition.revision,
                    now,
                    listOf(discoveredItem(definition, now)),
                ),
            )
            materializeDueInbox(now)
            snapshot().runs.single().key
        }
        val token = AutomationLeaseToken("lease-persisted-precondition")
        storage.acquireNextLease(
            AutomationWorkerId("worker-persisted-precondition"),
            token,
            AutomationBootSessionId("boot-persisted-precondition"),
            now,
            Duration.ofMinutes(2),
        )
        storage.completeLease(
            key,
            token,
            now.plusSeconds(1),
            AutomationCompletion.Deferred(
                "device_unlock_required",
                now.plus(Duration.ofMinutes(15)),
            ),
        )

        val restored = PersistentAutomationStorage(persistence)
        assertEquals(
            AutomationRunWaitKind.DEFERRED_PRECONDITION,
            restored.snapshot().runs.single().waitKind,
        )
        val unlockedAt = now.plusSeconds(5)
        assertEquals(
            1,
            restored.releaseDeferredRuns(
                setOf(AutomationResolvedPrecondition.DEVICE_UNLOCKED),
                unlockedAt,
            ),
        )
        assertEquals(unlockedAt, restored.snapshot().runs.single().availableAt)
        assertEquals(
            AutomationRunWaitKind.DEFERRED_PRECONDITION,
            PersistentAutomationStorage(persistence).snapshot().runs.single().waitKind,
        )
    }

    @Test
    fun versionTwoRetryWaitWithoutDiscriminatorMigratesToNonBypassableBackoff() {
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val definition = testDefinition(id = "automation.legacy.v2.wait")
        val retryAt = now.plus(Duration.ofMinutes(5))
        val root = JSONObject(
            AutomationSnapshotJsonCodec.encode(
                AutomationStorageSnapshot(
                    definitions = listOf(definition),
                    runs = listOf(
                        AutomationRun(
                            key = AutomationRunKey(definition.id, now),
                            definitionRevision = definition.revision,
                            state = AutomationRunState.RETRY_WAIT,
                            attemptCount = 0,
                            availableAt = retryAt,
                            createdAt = now,
                            updatedAt = now,
                            lastFailureCode = "network_offline",
                            waitKind = AutomationRunWaitKind.DEFERRED_PRECONDITION,
                        ),
                    ),
                ),
            ),
        ).apply {
            put("version", 2)
            getJSONArray("runs").getJSONObject(0).remove("waitKind")
        }

        val restored = PersistentAutomationStorage(
            MemorySnapshotPersistence(root.toString().toByteArray()),
        )

        assertEquals(AutomationRunWaitKind.RETRY_BACKOFF, restored.snapshot().runs.single().waitKind)
        assertEquals(
            0,
            restored.releaseDeferredRuns(
                setOf(AutomationResolvedPrecondition.VALIDATED_NETWORK),
                now.plusSeconds(1),
            ),
        )
        assertEquals(retryAt, restored.snapshot().runs.single().availableAt)
    }

    @Test
    fun failedAtomicWriteDoesNotPublishDefinitionOrReturnLease() {
        val persistence = MemorySnapshotPersistence()
        val storage = PersistentAutomationStorage(persistence)
        persistence.failWrites = true

        assertThrows(AutomationPersistenceException::class.java) {
            storage.upsertDefinition(testDefinition())
        }

        assertTrue(storage.definitions().isEmpty())
        assertEquals(null, persistence.bytes)
    }

    @Test
    fun corruptOversizedAndInternallyInconsistentSnapshotsFailClosed() {
        assertEquals(
            "automation_snapshot_corrupt",
            assertThrows(AutomationPersistenceException::class.java) {
                PersistentAutomationStorage(MemorySnapshotPersistence("not-json".toByteArray()))
            }.errorCode,
        )

        val definition = testDefinition()
        val duplicate = AutomationStorageSnapshot(definitions = listOf(definition, definition))
        assertEquals(
            "automation_snapshot_inconsistent",
            assertThrows(AutomationPersistenceException::class.java) {
                PersistentAutomationStorage(
                    MemorySnapshotPersistence(
                        AutomationSnapshotJsonCodec.encode(duplicate).toByteArray(),
                    ),
                )
            }.errorCode,
        )
    }

    @Test
    fun boundedHistoryPrunesOldestTerminalRunsButNeverActiveWork() {
        val persistence = MemorySnapshotPersistence()
        val storage = PersistentAutomationStorage(
            persistence,
            AutomationPersistenceLimits(maximumRunHistory = 3),
        )
        val definition = testDefinition()
        storage.upsertDefinition(definition)
        val base = Instant.parse("2026-01-01T09:00:00Z")
        repeat(4) { index ->
            val now = base.plusSeconds(index.toLong() * 86_400)
            val key = AutomationRunKey(definition.id, now)
            storage.recordDiscovery(
                AutomationDiscoveryBatch(
                    definition.id,
                    definition.revision,
                    now,
                    listOf(discoveredItem(definition, now)),
                ),
            )
            storage.materializeDueInbox(now)
            val token = AutomationLeaseToken("lease-token-history-${index.toString().padStart(3, '0')}")
            storage.acquireNextLease(
                AutomationWorkerId("worker-history"),
                token,
                AutomationBootSessionId("boot-0001"),
                now,
                Duration.ofMinutes(1),
            )
            storage.completeLease(key, token, now.plusSeconds(1), AutomationCompletion.Succeeded)
        }

        val runs = PersistentAutomationStorage(persistence).snapshot().runs
        assertEquals(3, runs.size)
        assertFalse(runs.any { it.key.scheduledAt == base })
        assertTrue(runs.all { it.state == AutomationRunState.SUCCEEDED })

        val reopened = PersistentAutomationStorage(persistence)
        assertEquals(base, reopened.snapshot().receipts.single().key.scheduledAt)
        reopened.resetSchedulerCursors(setOf(definition.id))
        val replay = AutomationScheduler(reopened).reconcile(
            now = base,
            systemZone = java.time.ZoneId.of("UTC"),
            source = AutomationDiscoverySource.BOOT_RECOVERY,
        )
        assertEquals(1, replay.duplicatesIgnored)
        assertEquals(0, replay.inboxItemsInserted)
    }

    @Test
    fun persistedLeaseIsRecoveredAfterProcessDeathWithoutDoubleExecution() {
        val persistence = MemorySnapshotPersistence()
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val first = PersistentAutomationStorage(persistence)
        val definition = testDefinition()
        first.upsertDefinition(definition)
        first.recordDiscovery(
            AutomationDiscoveryBatch(
                definition.id,
                definition.revision,
                now,
                listOf(discoveredItem(definition, now)),
            ),
        )
        first.materializeDueInbox(now)
        first.acquireNextLease(
            AutomationWorkerId("worker-before-death"),
            AutomationLeaseToken("lease-token-before-death"),
            AutomationBootSessionId("boot-0001"),
            now,
            Duration.ofSeconds(30),
        )

        val restored = PersistentAutomationStorage(persistence)
        val recovery = restored.recoverLeases(
            now.plusSeconds(31),
            AutomationBootSessionId("boot-0001"),
        )

        assertEquals(1, recovery.recoveredAfterExpiry)
        assertEquals(1, restored.snapshot().runs.single().attemptCount)
        assertEquals(AutomationRunState.RETRY_WAIT, restored.snapshot().runs.single().state)
        assertTrue(restored.snapshot().leases.isEmpty())
    }

    @Test
    fun manualInvocationAndExactConfirmationSurviveProcessDeath() {
        val persistence = MemorySnapshotPersistence()
        val now = Instant.parse("2026-01-02T09:00:00Z")
        val first = PersistentAutomationStorage(persistence)
        val definition = testDefinition()
        first.upsertDefinition(definition)
        val requestId = AutomationManualRequestId("c".repeat(64))
        val key = (first.enqueueManualRun(
            definition.id,
            definition.revision,
            requestId,
            now,
        ) as AutomationManualEnqueueResult.Enqueued).key
        first.materializeDueInbox(now)
        first.recordConfirmation(
            AutomationRunConfirmationReceipt(
                AutomationConfirmationId("confirmation-process-restart"),
                key,
                definition.revision,
                now,
                now.plus(Duration.ofHours(1)),
            ),
            now,
        )

        val restored = PersistentAutomationStorage(persistence)

        assertEquals(setOf(key), restored.confirmedRunKeys(now.plusSeconds(1)))
        assertEquals(
            AutomationManualEnqueueResult.Duplicate(key),
            restored.enqueueManualRun(
                definition.id,
                definition.revision,
                requestId,
                now.plusSeconds(1),
            ),
        )
        assertEquals(1, restored.snapshot().manualInvocations.size)
        assertEquals(1, restored.snapshot().confirmations.size)
    }

    @Test
    fun backupRestorePublishesOneCompleteDefinitionsOnlySnapshot() {
        val persistence = MemorySnapshotPersistence()
        val storage = PersistentAutomationStorage(persistence)
        val old = testDefinition()
        storage.upsertDefinition(old)
        val replacement = testDefinition(id = "automation.restored").copy(revision = 7)
        val writesBefore = persistence.writeCount

        storage.replaceSnapshotForRestore(
            AutomationStorageSnapshot(definitions = listOf(replacement)),
        )

        assertEquals(writesBefore + 1, persistence.writeCount)
        assertEquals(listOf(replacement), storage.definitions())
        assertEquals(
            AutomationStorageSnapshot(definitions = listOf(replacement)),
            PersistentAutomationStorage(persistence).snapshot(),
        )
    }

    @Test
    fun failedBackupRestoreWriteLeavesEntirePreviousSnapshotPublished() {
        val persistence = MemorySnapshotPersistence()
        val storage = PersistentAutomationStorage(persistence)
        val old = testDefinition()
        storage.upsertDefinition(old)
        val before = storage.snapshot()
        persistence.failWrites = true

        assertThrows(AutomationPersistenceException::class.java) {
            storage.replaceSnapshotForRestore(
                AutomationStorageSnapshot(
                    definitions = listOf(testDefinition(id = "automation.restored")),
                ),
            )
        }

        assertEquals(before, storage.snapshot())
    }

    @Test
    fun backupRestoreCompareAndSwapRejectsConcurrentAutomationMutation() {
        val persistence = MemorySnapshotPersistence()
        val storage = PersistentAutomationStorage(persistence)
        val expected = storage.snapshot()
        storage.upsertDefinition(testDefinition())
        val writesBefore = persistence.writeCount

        assertFalse(
            storage.compareAndReplaceSnapshotForRestore(
                expected,
                AutomationStorageSnapshot(
                    definitions = listOf(testDefinition(id = "automation.restored")),
                ),
            ),
        )

        assertEquals(writesBefore, persistence.writeCount)
        assertEquals(listOf(testDefinition()), storage.definitions())
    }
}

private class MemorySnapshotPersistence(
    initialBytes: ByteArray? = null,
) : AutomationSnapshotPersistence {
    var bytes: ByteArray? = initialBytes?.copyOf()
    var failWrites: Boolean = false
    var writeCount: Int = 0

    override fun read(): ByteArray? = bytes?.copyOf()

    override fun write(bytes: ByteArray) {
        if (failWrites) throw AutomationPersistenceException("test_write_failed")
        writeCount += 1
        this.bytes = bytes.copyOf()
    }
}
