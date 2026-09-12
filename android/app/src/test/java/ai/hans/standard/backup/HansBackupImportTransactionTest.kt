package ai.hans.standard.backup

import ai.hans.standard.automations.*
import ai.hans.standard.phone.keys.ActionKeyTrigger
import ai.hans.standard.profile.ProfileAnswer
import ai.hans.standard.profile.UserProfileDocument
import ai.hans.standard.profile.UserProfileRepository
import ai.hans.standard.profile.UserProfileStorage
import ai.hans.standard.settings.HansSettings
import ai.hans.standard.settings.HansSettingsStore
import ai.hans.standard.settings.ReadAloudMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class HansBackupImportTransactionTest {
    private val now = Instant.parse("2026-08-27T09:00:00Z")

    @Test
    fun importRestoresLiveAndTtsVoicesIndependentlyWithoutChangingConfirmedDispatch() {
        val fixture = Fixture()
        val before = fixture.settings.read()
        val imported = payload().copy(settings = payload().settings.copy(
            model = "gpt-5.6-terra",
            reasoningEffort = "high",
            serviceTier = HansSettings.FAST_SERVICE_TIER,
        ))

        fixture.transaction.replaceAll(imported)

        val restored = fixture.settings.read()
        assertEquals("willow", restored.liveVoice)
        assertEquals("nova", restored.voice)
        assertEquals(imported.settings.copy(
            model = before.model,
            reasoningEffort = before.reasoningEffort,
            serviceTier = before.serviceTier,
        ), restored)
        assertNull(fixture.journal.pending)
    }

    @Test
    fun failedFinalCommitRollsBackBothAlreadyWrittenVoicesIndependently() {
        val fixture = Fixture()
        val before = fixture.beforeImage()
        fixture.journal.failNextClear = {
            assertEquals("willow", fixture.settings.read().liveVoice)
            assertEquals("nova", fixture.settings.read().voice)
            error("injected_journal_clear_failure")
        }

        assertEquals("backup_import_failed", assertThrows(HansBackupException::class.java) {
            fixture.transaction.replaceAll(payload())
        }.errorCode)

        assertEquals(before, fixture.beforeImage())
        assertEquals("ripple", fixture.settings.read().liveVoice)
        assertEquals("fable", fixture.settings.read().voice)
        assertNull(fixture.journal.pending)
        assertTrue(fixture.maintenance.isRecoveryReady)
    }

    @Test
    fun definitionsImportRetainsTerminalHistoryReceiptsManualIdsAndRecoveryState() {
        val receiptKey = AutomationRunKey(AutomationId("backup.compacted"), now.minusSeconds(600))
        val fixture = Fixture(AutomationStorageSnapshot(
            receipts = listOf(AutomationRunReceipt(receiptKey, AutomationRunState.SUCCEEDED, now.minusSeconds(590))),
            manualInvocations = listOf(AutomationManualInvocation(
                AutomationManualRequestId("d".repeat(64)), receiptKey, 1, now.minusSeconds(600),
            )),
        ))
        val definition = definition("backup.old")
        val claim = claim(fixture.storage, definition)
        fixture.storage.completeLease(claim.run.key, claim.lease.token, now, AutomationCompletion.Succeeded)
        fixture.storage.storeRecoveryState(AutomationRecoveryState(boot, "UTC", now))
        val before = fixture.storage.snapshot()

        fixture.transaction.replaceAll(payload(definition("backup.new")))

        val after = fixture.storage.snapshot()
        assertEquals(listOf("backup.new"), after.definitions.map { it.id.value })
        assertEquals(before.runs, after.runs)
        assertEquals(before.receipts, after.receipts)
        assertEquals(before.manualInvocations, after.manualInvocations)
        assertEquals(before.recoveryState, after.recoveryState)
        assertEquals("Importiertes Profil", fixture.profile.read().confirmedSummary)
        assertNull(fixture.journal.pending)
        assertTrue(fixture.maintenance.isRecoveryReady)
    }

    @Test
    fun onlyChangedDefinitionCursorsAreResetWhileUnchangedSchedulesKeepTheirCheckpoint() {
        val fixture = Fixture()
        val kept = definition("backup.kept")
        val changed = definition("backup.changed")
        listOf(kept, changed).forEach { definition ->
            fixture.storage.upsertDefinition(definition)
            fixture.storage.recordDiscovery(AutomationDiscoveryBatch(definition.id, 1, now, emptyList()))
        }
        val before = fixture.storage.schedulerCursor(kept.id)

        fixture.transaction.replaceAll(payload(kept, changed.copy(revision = 2, instruction = "Neue Aufgabe")))

        assertNotNull(before)
        assertEquals(before, fixture.storage.schedulerCursor(kept.id))
        assertNull(fixture.storage.schedulerCursor(changed.id))
    }

    @Test
    fun rejectedCompareAndSwapNeverRestoresTheStaleBeforeImageOverAConflictingDefinition() {
        val fixture = Fixture()
        fixture.storage.upsertDefinition(definition("backup.before"))
        val before = fixture.beforeImage()
        val conflicting = definition("backup.concurrent")
        // Normal callers use maintenance. Inject a lower-level conflict to exercise the actual
        // persistent CAS rejection, not a fake gateway's rollback behavior.
        fixture.journal.afterStage = { fixture.raw.upsertDefinition(conflicting) }

        assertEquals("backup_state_changed", assertThrows(HansBackupException::class.java) {
            fixture.transaction.replaceAll(payload(definition("backup.imported")))
        }.errorCode)

        assertEquals(before.automations.definitions + conflicting, fixture.storage.definitions())
        assertEquals(before.profile, fixture.profile.read())
        assertEquals(before.settings, fixture.settings.read())
        assertEquals(before.pluginChoices, fixture.choices.read())
        assertNull(fixture.journal.pending)
        assertTrue(fixture.maintenance.isRecoveryReady)
    }

    @Test
    fun cancellingAQueuedCycleDoesNotReleaseItsReservationBeforeTheQueuedCallbackEnds() {
        val fixture = Fixture()
        val executor = HoldingExecutor()
        val owner = fixture.owner(executor, harmlessExecutor())
        val handle = owner.requestCycle(AutomationRuntimeTrigger.Timer)
        handle.cancel()

        val failure = assertThrows(HansBackupException::class.java) {
            fixture.transaction.replaceAll(payload())
        }
        assertEquals("backup_runtime_busy", failure.errorCode)
        assertNull(fixture.journal.pending)

        executor.runNext()
        fixture.transaction.replaceAll(payload())
        assertEquals("Importiertes Profil", fixture.profile.read().confirmedSummary)
    }

    @Test
    fun stoppedRunningExecutorStillExcludesImportEvenAfterItsLeaseWasRevoked() {
        val fixture = Fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val executionThread = AtomicReference<Thread>()
        val definition = definition("backup.running")
        fixture.storage.upsertDefinition(definition)
        fixture.storage.enqueueManualRun(definition.id, 1, requestId, now)
        val owner = fixture.owner(Executor { task ->
            Thread(task).also { executionThread.set(it); it.start() }
        }, object : CodexAutomationExecutor {
            override fun execute(
                request: CodexAutomationExecutionRequest,
                heartbeat: AutomationHeartbeat,
            ): CodexAutomationExecutionOutcome {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                return CodexAutomationExecutionOutcome.RetryableFailure("job_execution_stopped")
            }
        })
        owner.addActivityObserver { if (it.activeAutomationCount == 0 && entered.count == 0L) finished.countDown() }
        val handle = owner.requestCycle(AutomationRuntimeTrigger.ManualRun)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            handle.cancel()
            fixture.storage.cancelDefinition(definition.id, 1, now)
            assertTrue(fixture.storage.snapshot().leases.isEmpty())
            assertEquals(
                "backup_runtime_busy",
                assertThrows(HansBackupException::class.java) {
                    fixture.transaction.replaceAll(payload())
                }.errorCode,
            )
            assertNull(fixture.journal.pending)
        } finally {
            release.countDown()
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        executionThread.get().join(5_000)
        assertFalse(executionThread.get().isAlive)
        val completed = fixture.storage.snapshot()
        fixture.transaction.replaceAll(payload())
        assertEquals(completed.runs, fixture.storage.snapshot().runs)
    }

    @Test
    fun importCannotHideAPostDispatchStoppedRunOrItsLateCompletion() {
        val fixture = Fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val executorThread = AtomicReference<Thread>()
        val definition = definition("backup.dispatched")
        fixture.storage.upsertDefinition(definition)
        fixture.storage.enqueueManualRun(definition.id, 1, requestId, now)
        val owner = fixture.owner(Executor { task ->
            Thread(task).also { executorThread.set(it); it.start() }
        }, object : CodexAutomationExecutor {
            override fun execute(
                request: CodexAutomationExecutionRequest,
                heartbeat: AutomationHeartbeat,
            ): CodexAutomationExecutionOutcome {
                assertTrue(heartbeat.markExternalDispatchStarted())
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                return CodexAutomationExecutionOutcome.RetryableFailure("codex_result_unknown")
            }
        })
        val handle = owner.requestCycle(AutomationRuntimeTrigger.ManualRun) { done.countDown() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            handle.cancel()
            val before = fixture.storage.snapshot()
            assertEquals("backup_runtime_busy", assertThrows(HansBackupException::class.java) {
                fixture.transaction.replaceAll(payload())
            }.errorCode)
            assertEquals(before, fixture.storage.snapshot())
        } finally {
            release.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        executorThread.get().join(5_000)
        assertFalse(executorThread.get().isAlive)
        val settled = fixture.storage.snapshot()
        assertEquals(AutomationRunState.FAILED_TERMINAL, settled.runs.single().state)
        assertNotNull(settled.runs.single().dispatchFence)
        assertEquals("backup_automation_work_unsettled", assertThrows(HansBackupException::class.java) {
            fixture.transaction.replaceAll(payload())
        }.errorCode)
        assertEquals(settled, fixture.storage.snapshot())
    }

    @Test
    fun newCycleCannotEnterBetweenJournalStageAndSuccessfulCommit() {
        val fixture = Fixture()
        val staged = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requesting = CountDownLatch(1)
        val executionEntered = CountDownLatch(1)
        val importResult = AtomicReference<Result<Unit>>()
        fixture.journal.afterStage = {
            staged.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
        }
        val owner = fixture.owner(Executor(Runnable::run), harmlessExecutor())
        val importer = Thread {
            importResult.set(runCatching { fixture.transaction.replaceAll(payload(definition("backup.imported"))) })
        }
        importer.start()
        assertTrue(staged.await(5, TimeUnit.SECONDS))
        val requester = Thread {
            requesting.countDown()
            owner.requestCycle(AutomationRuntimeTrigger.Timer) { executionEntered.countDown() }
        }
        requester.start()
        assertTrue(requesting.await(5, TimeUnit.SECONDS))
        assertEquals(1L, executionEntered.count)
        release.countDown()
        importer.join(5_000)
        requester.join(5_000)
        assertFalse(importer.isAlive)
        assertFalse(requester.isAlive)
        importResult.get().getOrThrow()
        assertEquals(0L, executionEntered.count)
        assertEquals("backup.imported", fixture.storage.definitions().single().id.value)
    }

    @Test
    fun concurrentProfileMutationRunsAfterRollbackAndIsNotOverwrittenByTheBeforeImage() {
        val fixture = Fixture()
        val partial = CountDownLatch(1)
        val release = CountDownLatch(1)
        val mutationRequested = CountDownLatch(1)
        val mutateResult = AtomicReference<Result<UserProfileDocument>>()
        val importResult = AtomicReference<Result<Unit>>()
        fixture.settings.failNextRestore = {
            partial.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            error("injected_partial_write")
        }
        val repository = UserProfileRepository(fixture.profile)
        val importer = Thread { importResult.set(runCatching { fixture.transaction.replaceAll(payload()) }) }
        importer.start()
        assertTrue(partial.await(5, TimeUnit.SECONDS))
        val mutation = Thread {
            mutationRequested.countDown()
            mutateResult.set(runCatching {
                repository.beginInterview()
                repository.recordAnswer(ProfileAnswer("interests", "Was magst du?", "Wandern"))
            })
        }
        mutation.start()
        assertTrue(mutationRequested.await(5, TimeUnit.SECONDS))
        release.countDown()
        importer.join(5_000)
        mutation.join(5_000)
        assertFalse(importer.isAlive)
        assertFalse(mutation.isAlive)
        assertTrue(importResult.get().isFailure)
        mutateResult.get().getOrThrow()
        assertEquals("Vorheriges Profil", fixture.profile.read().confirmedSummary)
        assertEquals("Wandern", fixture.profile.read().draftAnswers.single().answer)
        assertNull(fixture.journal.pending)
        assertTrue(fixture.maintenance.isRecoveryReady)
    }

    @Test
    fun startupRestoresPendingJournalBeforeSchedulingAndNeverStartsAnAccountForExport() {
        val fixture = Fixture()
        fixture.storage.upsertDefinition(definition("backup.before"))
        val before = fixture.beforeImage()
        fixture.journal.pending = before
        fixture.storage.replaceSnapshotForRestore(
            fixture.storage.snapshot().copy(definitions = listOf(definition("backup.partial"))),
        )
        fixture.profile.write(UserProfileDocument(confirmedSummary = "Teilimport"))
        fixture.settings.saveVoice("nova", 1.5f, ReadAloudMode.FINAL_ONLY)
        fixture.settings.saveLiveVoice("willow")
        assertEquals("willow", fixture.settings.read().liveVoice)
        assertEquals("nova", fixture.settings.read().voice)
        var schedules = 0
        var accountStarts = 0
        val gateway = fixture.gateway(onSnapshot = { assertEquals(0, accountStarts) })

        val recovery = startRuntimeAfterBackupRecovery(gateway) {
            assertEquals(before.automations, fixture.storage.snapshot())
            assertEquals(before.profile, fixture.profile.read())
            assertEquals(before.settings, fixture.settings.read())
            assertEquals("ripple", fixture.settings.read().liveVoice)
            assertEquals("fable", fixture.settings.read().voice)
            assertNull(fixture.journal.pending)
            schedules += 1
        }

        assertTrue(recovery.isSuccess)
        assertEquals(1, schedules)
        HansBackupCoordinator(gateway).exportDocument()
        assertEquals(1, schedules)
        assertEquals(0, accountStarts)
    }

    @Test
    fun malformedJournalKeepsMutationsAndSchedulingBlockedUntilExplicitRecoverySucceeds() {
        val fixture = Fixture()
        fixture.journal.failRead = true
        var scheduled = 0
        val result = startRuntimeAfterBackupRecovery(fixture.gateway()) { scheduled += 1 }

        assertTrue(result.isFailure)
        assertFalse(fixture.maintenance.isRecoveryReady)
        assertEquals(0, scheduled)
        assertThrows(HansBackupException::class.java) { fixture.storage.upsertDefinition(definition("denied")) }
        val owner = fixture.owner(Executor(Runnable::run), harmlessExecutor())
        var cycleFailure = false
        owner.requestCycle(AutomationRuntimeTrigger.Timer) { cycleFailure = it.isFailure }
        assertTrue(cycleFailure)
        assertThrows(HansBackupException::class.java) { owner.scheduleChanged() }

        fixture.journal.failRead = false
        assertTrue(startRuntimeAfterBackupRecovery(fixture.gateway()) { scheduled += 1 }.isSuccess)
        assertEquals(1, scheduled)
        assertTrue(fixture.maintenance.isRecoveryReady)
    }

    @Test
    fun legacyJournalWithOldLeaseCannotReplayAfterTheExecutorCompleted() {
        val fixture = Fixture()
        val claim = claim(fixture.storage, definition("backup.legacy"))
        fixture.journal.pending = fixture.beforeImage()
        fixture.storage.completeLease(claim.run.key, claim.lease.token, now, AutomationCompletion.Succeeded)
        val completed = fixture.storage.snapshot()
        var scheduled = 0

        assertTrue(startRuntimeAfterBackupRecovery(fixture.gateway()) { scheduled += 1 }.isFailure)
        assertEquals(0, scheduled)
        assertNotNull(fixture.journal.pending)
        assertFalse(fixture.maintenance.isRecoveryReady)
        // Inspect the persisted delegate, not the deliberately blocked application-facing store.
        assertEquals(completed, fixture.raw.snapshot())
    }

    @Test
    fun failedRollbackCannotBeMistakenForACompletedImport() {
        val fixture = Fixture()
        val before = fixture.beforeImage()
        fixture.journal.failNextClear = {
            assertEquals("willow", fixture.settings.read().liveVoice)
            assertEquals("nova", fixture.settings.read().voice)
            fixture.settings.failAllRestores = true
            error("injected_journal_clear_failure")
        }

        assertEquals("backup_import_rollback_pending", assertThrows(HansBackupException::class.java) {
            fixture.transaction.replaceAll(payload())
        }.errorCode)
        assertNotNull(fixture.journal.pending)
        assertEquals(before.settings, fixture.journal.pending?.settings)
        assertFalse(fixture.maintenance.isRecoveryReady)
        assertThrows(HansBackupException::class.java) { fixture.transaction.replaceAll(payload()) }
        fixture.settings.failAllRestores = false
        assertTrue(fixture.transaction.recoverInterruptedImport())
        assertEquals("Vorheriges Profil", fixture.profile.read().confirmedSummary)
        assertEquals(before.settings, fixture.settings.read())
        assertEquals("ripple", fixture.settings.read().liveVoice)
        assertEquals("fable", fixture.settings.read().voice)
        assertNull(fixture.journal.pending)
    }

    private fun payload(vararg definitions: AutomationDefinition) = HansBackupPayload(
        settings = HansSettings(voice = "nova", liveVoice = "willow"),
        confirmedProfileSummary = "Importiertes Profil",
        automations = definitions.toList(),
        plugins = emptyList(),
        skills = emptyList(),
    )

    private fun definition(id: String) = testDefinition(
        id = id,
        dtStart = LocalDateTime.of(2027, 1, 1, 9, 0),
    ).copy(requirements = AutomationRequirements(confirmationPolicy = AutomationConfirmationPolicy.CAPABILITY_POLICY))

    private fun claim(storage: AutomationStorage, definition: AutomationDefinition): AutomationLeaseClaim {
        storage.upsertDefinition(definition)
        check(storage.enqueueManualRun(definition.id, 1, requestId, now) is AutomationManualEnqueueResult.Enqueued)
        storage.materializeDueInbox(now)
        return checkNotNull(storage.acquireNextLease(
            AutomationWorkerId("backup-test-worker"), lease, boot, now, Duration.ofMinutes(2),
        ))
    }

    private fun harmlessExecutor() = object : CodexAutomationExecutor {
        override fun execute(request: CodexAutomationExecutionRequest, heartbeat: AutomationHeartbeat) =
            CodexAutomationExecutionOutcome.Succeeded
    }

    private inner class Fixture(initialSnapshot: AutomationStorageSnapshot = AutomationStorageSnapshot()) {
        val maintenance = HansBackupMaintenance()
        val raw = PersistentAutomationStorage(object : AutomationSnapshotPersistence {
            private var bytes: ByteArray? = AutomationSnapshotJsonCodec.encode(initialSnapshot).toByteArray()
            override fun read(): ByteArray? = bytes?.copyOf()
            override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
        })
        val storage = BackupMaintainedAutomationStorage(raw, maintenance)
        val settings = Settings(maintenance)
        val profile = Profile(maintenance)
        val choices = object : ImportedPluginChoiceStore {
            private var value = StoredPluginChoices()
            override fun read() = value
            override fun write(choices: StoredPluginChoices) { value = choices }
        }
        val journal = Journal()
        val transaction = HansBackupImportTransaction(
            maintenance, settings, profile, storage, choices, journal, BackupClock { now.toEpochMilli() },
        )

        fun beforeImage() = HansBackupRollbackState(settings.read(), profile.read(), storage.snapshot(), choices.read())

        fun gateway(onSnapshot: () -> Unit = {}) = object : HansBackupStateGateway {
            override fun <T> withStateAccess(block: () -> T): T = maintenance.withStateAccess(block)
            override fun snapshot(): HansBackupPayload = withStateAccess {
                onSnapshot()
                HansBackupPayload(settings.read(), profile.read().confirmedSummary, storage.definitions(),
                    plugins = choices.read().plugins, skills = choices.read().skills)
            }
            override fun replaceAll(payload: HansBackupPayload) = transaction.replaceAll(payload)
            override fun recoverInterruptedImport() = transaction.recoverInterruptedImport()
        }

        fun owner(executor: Executor, codex: CodexAutomationExecutor) = AutomationRuntimeOwner(
            storage = storage,
            wakeupAdapter = object : AutomationWakeupAdapter {
                override fun replaceWakeup(plan: AutomationWakeupPlan) = AutomationAdapterResult.Accepted
            },
            cycleDispatcher = AutomationRuntimeCycleDispatcher { AutomationAdapterResult.Accepted },
            backgroundExecutor = executor,
            platformState = object : AutomationPlatformStateSource {
                override fun now() = this@HansBackupImportTransactionTest.now
                override fun systemZone() = ZoneId.of("UTC")
                override fun bootSessionId() = boot
            },
            liveEnvironment = AutomationLiveEnvironmentSource {
                AutomationExecutionEnvironment(setOf(AutomationCapabilityId.CODEX_APP_SERVER),
                    emptySet(), true, true, true)
            },
            codexExecutor = codex,
        )
    }

    private class Journal : BackupImportJournal {
        var pending: HansBackupRollbackState? = null
        var afterStage: (() -> Unit)? = null
        var failNextClear: (() -> Unit)? = null
        var failRead = false
        override fun stage(state: HansBackupRollbackState) { pending = state; afterStage?.invoke() }
        override fun read(): HansBackupRollbackState? {
            check(!failRead) { "backup_journal_integrity" }
            return pending
        }
        override fun clear() {
            val fail = failNextClear.also { failNextClear = null }
            fail?.invoke()
            pending = null
        }
    }

    private class Profile(private val maintenance: HansBackupMaintenance) : UserProfileStorage {
        private var value = UserProfileDocument(confirmedSummary = "Vorheriges Profil")
        override fun <T> withTransaction(block: () -> T): T = maintenance.withStateAccess(block)
        override fun read() = withTransaction { value }
        override fun write(document: UserProfileDocument) = withTransaction { value = document }
        override fun clear() = withTransaction { value = UserProfileDocument() }
    }

    private class Settings(private val maintenance: HansBackupMaintenance) : HansSettingsStore {
        private var value = HansSettings()
        var failNextRestore: (() -> Unit)? = null
        var failAllRestores = false
        override fun read() = maintenance.withStateAccess { value }
        override fun saveConfirmedDispatch(model: String, reasoningEffort: String, serviceTier: String) =
            maintenance.withStateAccess {
                value.copy(model = model, reasoningEffort = reasoningEffort, serviceTier = serviceTier)
                    .also { value = it }
            }
        override fun saveVoice(voice: String, speechRate: Float, readAloudMode: ReadAloudMode) =
            maintenance.withStateAccess {
                value.copy(voice = voice, speechRate = speechRate, readAloudMode = readAloudMode).also { value = it }
            }
        override fun saveLiveVoice(voice: String) = maintenance.withStateAccess {
            value.copy(liveVoice = voice).also { value = it }
        }
        override fun saveInputControls(dictationKeyTrigger: ActionKeyTrigger, cameraHoldToTalkEnabled: Boolean) =
            maintenance.withStateAccess {
                value.copy(dictationKeyTrigger = dictationKeyTrigger, cameraHoldToTalkEnabled = cameraHoldToTalkEnabled)
                    .also { value = it }
            }
        override fun saveRestoredNonDispatchPreferences(restored: HansSettings) = maintenance.withStateAccess {
            check(!failAllRestores) { "injected_disk_failure" }
            val fail = failNextRestore.also { failNextRestore = null }
            fail?.invoke()
            value = restored.copy(model = value.model, reasoningEffort = value.reasoningEffort, serviceTier = value.serviceTier)
            value
        }
    }

    private class HoldingExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runNext() = tasks.removeFirst().run()
    }

    private companion object {
        val boot = AutomationBootSessionId("backup-test-boot")
        val lease = AutomationLeaseToken("backup-test-lease-token")
        val requestId = AutomationManualRequestId("c".repeat(64))
    }
}
