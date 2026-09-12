package ai.hans.standard.backup

import ai.hans.standard.HansApplication
import ai.hans.standard.automations.AutomationBootSessionId
import ai.hans.standard.automations.AutomationCompletion
import ai.hans.standard.automations.AutomationCompletionResult
import ai.hans.standard.automations.AutomationDefinition
import ai.hans.standard.automations.AutomationDispatchFenceMarkResult
import ai.hans.standard.automations.AutomationId
import ai.hans.standard.automations.AutomationLeaseToken
import ai.hans.standard.automations.AutomationManualEnqueueResult
import ai.hans.standard.automations.AutomationManualRequestId
import ai.hans.standard.automations.AutomationProcessRecoveryFixture
import ai.hans.standard.automations.AutomationProcessRecoveryProtocol
import ai.hans.standard.automations.AutomationRecoveryState
import ai.hans.standard.automations.AutomationRetryPolicy
import ai.hans.standard.automations.AutomationRunKey
import ai.hans.standard.automations.AutomationRunReceipt
import ai.hans.standard.automations.AutomationRunState
import ai.hans.standard.automations.AutomationRuntimeTrigger
import ai.hans.standard.automations.AutomationSchedule
import ai.hans.standard.automations.AutomationSnapshotJsonCodec
import ai.hans.standard.automations.AutomationStorageSnapshot
import ai.hans.standard.automations.AutomationTimeZone
import ai.hans.standard.automations.AutomationWorkerId
import ai.hans.standard.automations.CodexAutomationTarget
import ai.hans.standard.automations.HansAutomationRuntime
import ai.hans.standard.automations.InMemoryAutomationStorage
import ai.hans.standard.automations.MissedRunPolicy
import ai.hans.standard.automations.SQLiteAutomationSnapshotPersistence
import ai.hans.standard.profile.AtomicFileUserProfileStorage
import ai.hans.standard.profile.UserProfileDocument
import ai.hans.standard.settings.HansSettings
import ai.hans.standard.settings.SharedPreferencesHansSettingsStore
import android.app.job.JobScheduler
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

/**
 * Prepares only fresh, synthetic state, then returns false so HansApplication itself reads and
 * recovers the real journal. Unlike the separate process-loss fixture, this never installs its
 * own owner. Its existing debug dispatch interceptor observes the first product enqueue and
 * rejects platform execution, keeping the acceptance test account/network/agent-free.
 */
internal object BackupRecoveryFixture {
    private sealed interface State {
        data object Unarmed : State
        data object Rejected : State
        data class Active(val instance: BackupRecoveryFixtureInstance) : State
    }

    @Volatile private var state: State = State.Unarmed

    /** null delegates to the existing debug fixture; false continues the normal Application. */
    fun bootstrap(application: HansApplication): Boolean? {
        val descriptor = try {
            BackupRecoveryFixtureProtocol.readDescriptor(application)
        } catch (_: Exception) {
            reject()
            return true
        } ?: return null
        state = State.Rejected
        return try {
            BackupRecoveryFixtureProtocol.requireBoundDebugEmulator(application, descriptor)
            AutomationProcessRecoveryFixture.assertProductionStoresAbsent(application)
            val extraPaths = listOf(
                File(application.noBackupFilesDir, AutomationProcessRecoveryProtocol.DESCRIPTOR_FILE_NAME),
                File(application.noBackupFilesDir, BackupRecoveryFixtureProtocol.JOURNAL_FILE_NAME),
                File(application.noBackupFilesDir, "hans-imported-plugin-choices-v1.json"),
            )
            extraPaths.forEach { file ->
                listOf("", ".bak", ".new").forEach { suffix ->
                    check(!Files.exists(File(file.path + suffix).toPath(), NOFOLLOW_LINKS)) {
                        "backup_fixture_existing_state"
                    }
                }
            }
            check(HansBackupProcessState.maintenance.isRecoveryReady)
            check(application.getSystemService(JobScheduler::class.java).allPendingJobs.isEmpty())
            val instance = BackupRecoveryFixtureInstance(application, descriptor)
            instance.prepare()
            state = State.Active(instance)
            false
        } catch (_: Exception) {
            reject()
            true
        }
    }

    fun afterApplicationCreate(application: HansApplication) {
        (state as? State.Active)?.instance?.applicationCreated(application)
    }

    fun requireActive(): BackupRecoveryFixtureInstance =
        (state as? State.Active)?.instance ?: error("backup_fixture_not_active")

    fun interceptEnqueue(trigger: AutomationRuntimeTrigger): Boolean? = when (val current = state) {
        State.Unarmed -> null
        State.Rejected -> false
        is State.Active -> current.instance.observeDispatch(trigger)
    }

    fun interceptUnexpectedWork(): Boolean? = when (val current = state) {
        State.Unarmed -> null
        State.Rejected -> false
        is State.Active -> {
            current.instance.observeUnexpectedWork()
            false
        }
    }

    fun interceptRetry(trigger: AutomationRuntimeTrigger, failedAttempt: Int): Boolean? = when (val current = state) {
        State.Unarmed -> null
        State.Rejected -> false
        is State.Active -> current.instance.observeRetry(trigger, failedAttempt)
    }

    private fun reject() {
        state = State.Rejected
        HansBackupProcessState.maintenance.requireRecovery()
        Log.e("HansBackupFixture", "backup_fixture_rejected")
    }
}

/** Small in-process evidence only; a new process must be armed on new, empty app storage. */
internal class BackupRecoveryFixtureInstance(
    private val application: HansApplication,
    val descriptor: BackupRecoveryFixtureDescriptor,
) {
    val bootstrapPid: Int = Process.myPid()
    val preparedAtElapsedNanos: Long = SystemClock.elapsedRealtimeNanos()
    val before: HansBackupRollbackState = syntheticBefore(descriptor.scenario == "legacy-unsettled")
    private val journalFile = File(application.noBackupFilesDir, BackupRecoveryFixtureProtocol.JOURNAL_FILE_NAME)
    private val journal = AndroidHansBackupStateGateway.AtomicBackupImportJournal(application)
    private data class PendingEnqueue(val trigger: AutomationRuntimeTrigger, val recoveryBlocked: Boolean)
    private val rejectedEnqueueOnThread = ThreadLocal<PendingEnqueue>()

    lateinit var staged: HansBackupRollbackState
        private set
    lateinit var preparedJournalHashes: Map<String, String>
        private set
    @Volatile var applicationCreateCount: Int = 0
        private set
    @Volatile var applicationCompletedAtElapsedNanos: Long = 0
        private set
    @Volatile var startupBlocked: Boolean? = null
        private set
    @Volatile var dispatchCount: Int = 0
        private set
    @Volatile var definitionChangedDispatchCount: Int = 0
        private set
    @Volatile var definitionChangedRetryCount: Int = 0
        private set
    @Volatile var firstDispatch: BackupRecoveryDispatchObservation? = null
        private set
    @Volatile var firstRetry: BackupRecoveryRetryObservation? = null
        private set
    @Volatile var blockedDispatchAttemptCount: Int = 0
        private set
    @Volatile var blockedRetryAttemptCount: Int = 0
        private set
    @Volatile var firstBlockedDispatch: BackupRecoveryBlockedDispatchObservation? = null
        private set
    @Volatile var lastBlockedDispatch: BackupRecoveryBlockedDispatchObservation? = null
        private set
    @Volatile var firstBlockedRetry: BackupRecoveryRetryObservation? = null
        private set
    @Volatile var lastBlockedRetry: BackupRecoveryRetryObservation? = null
        private set
    @Volatile var unexpectedWorkCount: Int = 0
        private set
    @Volatile var unexpectedWakeupCount: Int = 0
        private set
    @Volatile var observationFailure: Boolean = false
        private set

    fun prepare() {
        check(application.passiveInitializedSessionHostSnapshot() == null)
        staged = if (descriptor.scenario == "safe") {
            before.copy(
                settings = before.settings.copy(voice = "cedar", speechRate = 1.8f, cameraHoldToTalkEnabled = true),
                profile = before.profile.copy(revision = 9, confirmedSummary = "Synthetic interrupted import"),
                automations = before.automations.copy(definitions = before.automations.definitions.map {
                    it.copy(revision = it.revision + 1, instruction = "Synthetic replacement; never execute.")
                }),
                pluginChoices = StoredPluginChoices(skills = listOf(BackupSkillChoice("synthetic-new", false))),
            )
        } else {
            before
        }
        // No maintenance state is forced here: the real journal alone must cause startup gating.
        writeStores(staged)
        when (descriptor.scenario) {
            "safe", "legacy-unsettled" -> journal.stage(before)
            "malformed" -> createNewFile(journalFile, "{not-a-valid-backup-journal".toByteArray())
            "orphan-new" -> createNewFile(File(journalFile.path + ".new"), "incomplete-synthetic-journal".toByteArray())
            else -> error("backup_fixture_unknown_scenario")
        }
        check(readStores() == staged)
        preparedJournalHashes = journalHashes()
        check(preparedJournalHashes.size == 1)
    }

    @Synchronized
    fun applicationCreated(actualApplication: HansApplication) {
        check(actualApplication === application && bootstrapPid == Process.myPid())
        applicationCreateCount += 1
        startupBlocked = actualApplication.backupRecoveryRequired
        applicationCompletedAtElapsedNanos = SystemClock.elapsedRealtimeNanos()
    }

    @Synchronized
    fun observeDispatch(trigger: AutomationRuntimeTrigger): Boolean? {
        val recoveryBlocked = !HansBackupProcessState.maintenance.isRecoveryReady
        if (rejectedEnqueueOnThread.get() != null) observationFailure = true
        rejectedEnqueueOnThread.set(PendingEnqueue(trigger, recoveryBlocked))
        if (recoveryBlocked) {
            blockedDispatchAttemptCount += 1
            runCatching {
                val observation = BackupRecoveryBlockedDispatchObservation(
                    trigger = trigger,
                    duringApplicationCreate = applicationCreateCount == 0,
                    startupBlocked = startupBlocked == true && application.backupRecoveryRequired,
                    recoveryReady = HansBackupProcessState.maintenance.isRecoveryReady,
                    journalUnchanged = journalHashes() == preparedJournalHashes,
                    storesUnchanged = readStores() == staged,
                    ownerAbsent = HansAutomationRuntime.currentOrNull() == null,
                    hostSnapshotAbsent = application.passiveInitializedSessionHostSnapshot() == null,
                )
                if (firstBlockedDispatch == null) firstBlockedDispatch = observation
                lastBlockedDispatch = observation
                if (descriptor.scenario == "safe" || applicationCreateCount != 1 ||
                    observation.duringApplicationCreate || !observation.startupBlocked || observation.recoveryReady ||
                    !observation.journalUnchanged || !observation.storesUnchanged || !observation.ownerAbsent ||
                    !observation.hostSnapshotAbsent
                ) observationFailure = true
            }.onFailure { observationFailure = true }
            // Android can deliver BOOT_COMPLETED when leaving the stopped state (API 35+).
            // Observe that attempt separately, then exercise enqueue's REAL recovery guard.
            // In particular an attempt during Application.onCreate is still an error above.
            return null
        }
        dispatchCount += 1
        if (trigger == AutomationRuntimeTrigger.DefinitionChanged) definitionChangedDispatchCount += 1
        runCatching {
            val observation = BackupRecoveryDispatchObservation(
                trigger = trigger,
                duringApplicationCreate = applicationCreateCount == 0,
                recoveryReady = HansBackupProcessState.maintenance.isRecoveryReady,
                journalAbsent = journalHashes().isEmpty(),
                restoredStores = readStores() == before,
                ownerInstalled = HansAutomationRuntime.currentOrNull() != null,
                hostSnapshotAbsent = application.passiveInitializedSessionHostSnapshot() == null,
            )
            if (firstDispatch == null) firstDispatch = observation
            if (descriptor.scenario != "safe" || !observation.recoveryReady ||
                !observation.journalAbsent || !observation.restoredStores || !observation.ownerInstalled ||
                !observation.hostSnapshotAbsent
            ) observationFailure = true
        }.onFailure { observationFailure = true }
        // A ready fixture still rejects actual scheduled work before any agent can execute.
        return false
    }

    @Synchronized
    fun observeRetry(trigger: AutomationRuntimeTrigger, failedAttempt: Int): Boolean? {
        val rejected = rejectedEnqueueOnThread.get()
        rejectedEnqueueOnThread.remove()
        val recoveryBlocked = !HansBackupProcessState.maintenance.isRecoveryReady
        val retry = BackupRecoveryRetryObservation(
            trigger = trigger,
            failedAttempt = failedAttempt,
            matchedRejectedEnqueue = rejected != null && rejected.trigger == trigger &&
                rejected.recoveryBlocked == recoveryBlocked,
            duringApplicationCreate = applicationCreateCount == 0,
            recoveryReady = HansBackupProcessState.maintenance.isRecoveryReady,
        )
        if (recoveryBlocked) {
            blockedRetryAttemptCount += 1
            if (firstBlockedRetry == null) firstBlockedRetry = retry
            lastBlockedRetry = retry
            if (descriptor.scenario == "safe" || applicationCreateCount != 1 || startupBlocked != true ||
                !application.backupRecoveryRequired || retry.duringApplicationCreate || retry.recoveryReady ||
                failedAttempt < 0 || !retry.matchedRejectedEnqueue || observationFailure
            ) unexpectedWorkCount += 1
            // Do not fake rejection: scheduleRetry itself must return before creating its PI.
            return null
        }
        if (firstRetry == null) firstRetry = retry
        if (trigger == AutomationRuntimeTrigger.DefinitionChanged) definitionChangedRetryCount += 1
        if (descriptor.scenario != "safe" || failedAttempt != 0 || !retry.matchedRejectedEnqueue ||
            !retry.recoveryReady || observationFailure
        ) unexpectedWorkCount += 1
        return false
    }

    @Synchronized
    fun observeUnexpectedWork() {
        unexpectedWakeupCount += 1
        unexpectedWorkCount += 1
    }

    /** Read-only fixture observation, including while the product's mutation boundary is shut. */
    fun readStores(): HansBackupRollbackState {
        val readOnlyProbeBoundary = HansBackupMaintenance()
        val automationState = SQLiteAutomationSnapshotPersistence(application).use { persistence ->
            AutomationSnapshotJsonCodec.decode(checkNotNull(persistence.read()).toString(Charsets.UTF_8))
        }
        return HansBackupRollbackState(
            SharedPreferencesHansSettingsStore(application, readOnlyProbeBoundary).read(),
            AtomicFileUserProfileStorage(application, maintenance = readOnlyProbeBoundary).read(),
            automationState,
            AtomicImportedPluginChoiceStore(application).read(),
        )
    }

    fun journalHashes(): Map<String, String> = buildMap {
        listOf("", ".bak", ".new").forEach { suffix ->
            val file = File(journalFile.path + suffix)
            if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) {
                check(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
                put(suffix, BackupRecoveryFixtureProtocol.sha256(file))
            }
        }
    }

    private fun writeStores(state: HansBackupRollbackState) {
        SharedPreferencesHansSettingsStore(application).saveRestoredNonDispatchPreferences(state.settings)
        AtomicFileUserProfileStorage(application).write(state.profile)
        AtomicImportedPluginChoiceStore(application).write(state.pluginChoices)
        SQLiteAutomationSnapshotPersistence(application).use { persistence ->
            persistence.write(AutomationSnapshotJsonCodec.encode(state.automations).toByteArray())
        }
    }

    private fun syntheticBefore(unsettled: Boolean): HansBackupRollbackState {
        val now = Instant.parse("2026-01-01T09:00:00Z")
        val definition = AutomationDefinition(
            id = AutomationId("backup-${descriptor.fixtureId}"), revision = 1, enabled = true,
            schedule = AutomationSchedule(LocalDateTime.of(2026, 1, 1, 9, 0), AutomationTimeZone.Fixed("UTC"), "FREQ=DAILY"),
            missedRunPolicy = MissedRunPolicy(), retryPolicy = AutomationRetryPolicy(),
            target = CodexAutomationTarget.Independent,
            instruction = "Synthetic fixture only; never execute an agent or external action.", updatedAt = now,
        )
        val storage = InMemoryAutomationStorage()
        storage.upsertDefinition(definition)
        check(storage.enqueueManualRun(definition.id, 1, AutomationManualRequestId("b".repeat(64)), now)
            is AutomationManualEnqueueResult.Enqueued)
        storage.materializeDueInbox(now)
        val claim = checkNotNull(storage.acquireNextLease(
            AutomationWorkerId("backup-fixture-worker"), AutomationLeaseToken("backup-fixture-lease-token"),
            AutomationBootSessionId("backup-fixture-boot"), now, Duration.ofMinutes(2),
        ))
        if (unsettled) {
            check(storage.markDispatchFence(claim.run.key, claim.lease.token, now, Duration.ofMinutes(2)) ==
                AutomationDispatchFenceMarkResult.MARKED)
        } else {
            check(storage.completeLease(claim.run.key, claim.lease.token, now, AutomationCompletion.Succeeded) ==
                AutomationCompletionResult.COMPLETED)
            storage.upsertDefinition(definition.copy(revision = 2, enabled = false))
        }
        storage.storeRecoveryState(AutomationRecoveryState(AutomationBootSessionId("backup-fixture-boot"), "UTC", now))
        val snapshot = storage.snapshot().let { state ->
            if (unsettled) state else state.copy(receipts = listOf(AutomationRunReceipt(
                AutomationRunKey(definition.id, now.minusSeconds(3_600)), AutomationRunState.SUCCEEDED, now,
            )))
        }
        // Validate the same invariants that the Android persistent implementation will load.
        val validated = InMemoryAutomationStorage(snapshot).snapshot()
        return HansBackupRollbackState(
            settings = HansSettings(voice = "alloy", speechRate = 1.1f),
            profile = UserProfileDocument(revision = 7, confirmedSummary = "Synthetic before ${descriptor.fixtureId}",
                updatedAtMillis = now.toEpochMilli()),
            automations = validated,
            pluginChoices = StoredPluginChoices(skills = listOf(BackupSkillChoice("synthetic-before", true))),
        )
    }

    private fun createNewFile(file: File, bytes: ByteArray) {
        FileChannel.open(file.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }
}

internal data class BackupRecoveryDispatchObservation(
    val trigger: AutomationRuntimeTrigger,
    val duringApplicationCreate: Boolean,
    val recoveryReady: Boolean,
    val journalAbsent: Boolean,
    val restoredStores: Boolean,
    val ownerInstalled: Boolean,
    val hostSnapshotAbsent: Boolean,
)

internal data class BackupRecoveryRetryObservation(
    val trigger: AutomationRuntimeTrigger,
    val failedAttempt: Int,
    val matchedRejectedEnqueue: Boolean,
    val duringApplicationCreate: Boolean,
    val recoveryReady: Boolean,
)

internal data class BackupRecoveryBlockedDispatchObservation(
    val trigger: AutomationRuntimeTrigger,
    val duringApplicationCreate: Boolean,
    val startupBlocked: Boolean,
    val recoveryReady: Boolean,
    val journalUnchanged: Boolean,
    val storesUnchanged: Boolean,
    val ownerAbsent: Boolean,
    val hostSnapshotAbsent: Boolean,
)
