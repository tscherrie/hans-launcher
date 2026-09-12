package ai.hans.standard.automations

import ai.hans.standard.BuildConfig
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.PersistableBundle
import android.os.Process
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One explicitly armed, APK-pinned, account-free fixture on a disposable emulator. All execution
 * and wakeup components are framework-owned; the host never supplies a successful run outcome.
 * This entire source file, including self-termination, is absent from the release source set.
 */
internal object AutomationProcessRecoveryFixture {
    private sealed interface State {
        data object Unarmed : State
        data object Rejected : State
        data class Active(val instance: AutomationProcessRecoveryInstance) : State
    }

    @Volatile private var state: State = State.Unarmed

    fun bootstrap(context: Context): Boolean {
        // An existing but malformed descriptor is not permission to start the real product.
        val descriptor = try {
            AutomationProcessRecoveryProtocol.readDescriptor(context.noBackupFilesDir)
        } catch (_: Exception) {
            state = State.Rejected
            Log.e("HansAutomationFixture", "fixture_descriptor_rejected")
            return true
        } ?: return false
        state = State.Rejected
        var fixture: AutomationProcessRecoveryInstance? = null
        try {
            requireDebugEmulator(context, descriptor)
            check(HansAutomationRuntime.currentOrNull() == null) { "fixture_product_owner_present" }
            assertProductionStoresAbsent(context)
            fixture = AutomationProcessRecoveryInstance(context.applicationContext, descriptor)
            fixture.restoreBindings()
            fixture.record("bootstrapped", mapOf("productionStoresAbsent" to true))
            state = State.Active(fixture)
        } catch (error: Exception) {
            runCatching { fixture?.rejectBootstrap() }
            // Keep the failure content-free: a malformed file must not expose arbitrary payloads.
            val code = error.message?.takeIf { it.matches(Regex("[a-z][a-z0-9_]{2,95}")) } ?: "invalid_state"
            Log.e("HansAutomationFixture", "fixture_bootstrap_rejected:$code")
        }
        return true
    }

    fun requireActive(): AutomationProcessRecoveryInstance =
        (state as? State.Active)?.instance ?: error("fixture_not_active")

    fun receiveWakeup(params: JobParameters): Boolean = runCatching {
        requireActive().onWakeup(params)
        true
    }.getOrElse {
        Log.e("HansAutomationFixture", "fixture_wakeup_rejected")
        false
    }

    fun interceptEnqueue(trigger: AutomationRuntimeTrigger): Boolean? = when (val current = state) {
        State.Unarmed -> null
        State.Rejected -> false
        is State.Active -> runCatching { current.instance.dispatch(trigger) }.getOrDefault(false)
    }

    fun interceptRetry(trigger: AutomationRuntimeTrigger, failedAttempt: Int): Boolean? =
        when (val current = state) {
            State.Unarmed -> null
            State.Rejected -> false
            is State.Active -> {
                runCatching { current.instance.record("dispatch_retry_rejected", mapOf("failedAttempt" to failedAttempt)) }
                // Never escape a failed isolated dispatch into production alarms or stores.
                false
            }
        }

    private fun requireDebugEmulator(context: Context, descriptor: AutomationProcessRecoveryDescriptor) {
        check(BuildConfig.DEBUG && context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        check(context.packageName == "ai.hans.standard" && Build.VERSION.SDK_INT in 31..36)
        val hardware = Build.HARDWARE.lowercase(Locale.ROOT)
        val identity = listOf(Build.PRODUCT, Build.MODEL, Build.FINGERPRINT).joinToString(" ")
            .lowercase(Locale.ROOT)
        check(hardware in setOf("ranchu", "goldfish") && "sdk" in identity) {
            "fixture_requires_disposable_emulator"
        }
        check(context.applicationInfo.splitSourceDirs.isNullOrEmpty()) { "fixture_requires_pinned_base_apk" }
        val digest = MessageDigest.getInstance("SHA-256")
        File(context.applicationInfo.sourceDir).inputStream().use { source ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == descriptor.appApkSha256) {
            "fixture_apk_mismatch"
        }
    }

    fun assertProductionStoresAbsent(context: Context) {
        val data = File(context.applicationInfo.dataDir)
        val paths = listOf(
            "no_backup/hans_automations_v1.db", "no_backup/hans_automations_v1.json",
            "shared_prefs/hans_automation_work_generations_v1.xml",
            "no_backup/codex", "files/codex-workspace", "cache/automation-codex-sessions", "cache/codex-tmp",
            "shared_prefs/hans_settings_v1.xml", "shared_prefs/hans_codex_session_v1.xml",
            "shared_prefs/hans_dynamic_tool_contract_v1.xml", "shared_prefs/hans_speech_credential_v1.xml",
            "no_backup/hans-setup-state-v1.json", "no_backup/hans-setup-handoff-v1.json",
            "no_backup/persistent-android-consents-v2.json", "files/hans-user-profile-v1.json",
            "no_backup/hans-migration-session-readiness-v1.json",
        )
        paths.forEach { path ->
            listOf("", ".bak", ".new", "-wal", "-shm", "-journal").forEach { suffix ->
                check(!Files.exists(data.resolve(path + suffix).toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    "fixture_product_store_present"
                }
            }
        }
        check(HansAutomationRuntime.currentOrNull() == null) { "fixture_product_owner_present" }
    }
}

internal class AutomationProcessRecoveryInstance(
    private val context: Context,
    val descriptor: AutomationProcessRecoveryDescriptor,
) {
    private val journal = AutomationProcessRecoveryJournal.open(context.noBackupFilesDir, descriptor)
    private val ownedStoragePaths = validateOwnedStoragePaths()
    private val scheduler = context.getSystemService(JobScheduler::class.java)
    private val platform = AndroidAutomationPlatformStateSource(context)
    private val persistence = SQLiteAutomationSnapshotPersistence(context, descriptor.databaseName)
    private val storage = PersistentAutomationStorage(persistence)
    private val effects = ProcessRecoveryEffects(context.noBackupFilesDir.resolve(descriptor.effectsDatabaseName))
    private val preferences = context.getSharedPreferences(descriptor.workPreferencesName, Context.MODE_PRIVATE)
    private val ledger = AndroidAutomationDispatch.isolatedTestWorkLedger(context, UUID.fromString(descriptor.fixtureId))
    private val coordinator = AutomationRuntimeWorkCoordinator(ledger)
    private val executor = Executors.newSingleThreadExecutor { command -> Thread(command, "hans-process-fixture") }
    private val ownedExecutionIds = setOf(descriptor.executionJobId, descriptor.bootExecutionJobId)
    private val ownedRecoveryIds = ownedExecutionIds.map { it + 0x20 }.toSet()
    private val bindings = mutableListOf<AutoCloseable>()
    private val afterFence = descriptor.scenario.endsWith("post-fence")
    private val processLoss = descriptor.scenario.startsWith("process-")
    private val owner = AutomationRuntimeOwner(
        storage = storage,
        wakeupAdapter = AndroidAutomationWakeupAdapter(FixtureWakeupBackend()),
        cycleDispatcher = AutomationRuntimeCycleDispatcher { trigger ->
            if (dispatch(trigger)) AutomationAdapterResult.Accepted else AutomationAdapterResult.Rejected("fixture_dispatch_rejected")
        },
        backgroundExecutor = executor,
        platformState = platform,
        liveEnvironment = AutomationLiveEnvironmentSource.FAIL_CLOSED,
        codexExecutor = object : CodexAutomationExecutor {
            override fun execute(request: CodexAutomationExecutionRequest, heartbeat: AutomationHeartbeat) =
                executeSynthetic(request, heartbeat)
        },
        workerId = AutomationWorkerId("process-${descriptor.fixtureId}"),
    )

    private fun validateOwnedStoragePaths() {
        val fresh = journal.events().isEmpty()
        val paths = listOf(
            context.noBackupFilesDir.resolve(descriptor.databaseName),
            context.noBackupFilesDir.resolve(descriptor.effectsDatabaseName),
            File(context.applicationInfo.dataDir).resolve("shared_prefs/${descriptor.workPreferencesName}.xml"),
        )
        paths.forEach { base ->
            listOf("", "-wal", "-shm", "-journal", ".bak", ".new").forEach { suffix ->
                val path = File(base.path + suffix).toPath()
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    check(!fresh && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        "fixture_unexpected_storage_path"
                    }
                }
            }
        }
    }

    /** Only a rejected bootstrap, before Android can invoke a service, may use this cleanup. */
    fun rejectBootstrap() {
        check(ownedExecutionIds.none(coordinator::isActive))
        bindings.forEach(AutoCloseable::close)
        bindings.clear()
        executor.shutdownNow()
        persistence.close()
        effects.close()
    }

    fun restoreBindings() {
        for (jobId in ownedExecutionIds) {
            val kind = when (jobId) {
                descriptor.executionJobId -> AutomationRuntimeWorkKind.TIMER
                descriptor.bootExecutionJobId -> AutomationRuntimeWorkKind.BOOT
                else -> error("fixture_unexpected_execution_id")
            }
            val recoveryGuard = AndroidAutomationRecoveryGuard(context, jobId + 0x20, jobId, kind,
                observer = ::onRecoveryGuardEvent)
            assertRetainedRecoveryGuard(recoveryGuard)
            val pending = scheduler.getPendingJob(jobId)
            val retry: (AutomationRuntimeWorkTicket, Int) -> Boolean = { ticket, attempt ->
                val accepted = AndroidAutomationDispatch.scheduleExecutionRetry(context, jobId, ticket, attempt)
                if (accepted) {
                    val scheduled = checkNotNull(scheduler.getPendingJob(jobId))
                    record("execution_retry_scheduled", mapOf(
                        "jobId" to jobId, "nonce" to checkNotNull(scheduled.extras.getString("schedule_nonce")),
                        "trigger" to checkNotNull(scheduled.extras.getString("trigger")),
                    ))
                }
                accepted
            }
            val started: (Int, AutomationRuntimeWorkTicket, String?) -> Unit = { id, ticket, nonce ->
                record("service_started", snapshotFields() + mapOf("jobId" to id, "generation" to ticket.generation,
                    "nonce" to checkNotNull(nonce), "kind" to ticket.kind.name))
            }
            val finished: (Int, AutomationRuntimeWorkTicket, String?) -> Unit = { id, ticket, nonce -> onFinished(id, ticket, nonce) }
            if (pending != null) {
                val expected = journal.events().lastOrNull {
                    it.event in setOf("execution_scheduled", "execution_retry_scheduled") &&
                        it.fields["jobId"] == jobId.toLong()
                } ?: error("fixture_retained_job_without_schedule_evidence")
                bindings += AndroidAutomationDispatch.restoreTestBinding(
                    context, jobId, UUID.fromString(expected.fields.getValue("nonce") as String),
                    expected.fields.getValue("trigger") as String, owner, coordinator, retry, started, finished,
                    recoveryGuard,
                )
                record("binding_restored", mapOf("jobId" to jobId, "nonce" to expected.fields.getValue("nonce")))
            } else {
                bindings += AndroidAutomationDispatch.installTestBinding(
                    context, jobId, owner, coordinator, retry, started, finished,
                    recoveryGuard,
                )
                record("binding_installed_without_pending_job", mapOf("jobId" to jobId))
            }
        }
    }

    /** Rebind an existing persisted guard, never cancel/recreate it merely for this fixture. */
    private fun assertRetainedRecoveryGuard(guard: AndroidAutomationRecoveryGuard) {
        val pending = scheduler.getPendingJob(guard.jobId) ?: return
        check(pending.service == ComponentName(context, HansAutomationWakeupJobService::class.java) &&
            pending.isPersisted) { "fixture_retained_guard_component_mismatch" }
        val schedule = checkNotNull(AndroidAutomationRecoveryGuard.decode(pending.id, pending.extras)) {
            "fixture_retained_guard_invalid_identity"
        }
        check(schedule.jobId == guard.jobId && schedule.executionJobId == guard.executionJobId &&
            schedule.kind == guard.kind) { "fixture_retained_guard_binding_mismatch" }
        val expected = journal.events().lastOrNull {
            it.event in setOf("recovery_guard_scheduled", "recovery_guard_cancelled") &&
                it.fields["jobId"] == guard.jobId.toLong()
        }
        check(expected?.event == "recovery_guard_scheduled" &&
            expected.fields["nonce"] == schedule.nonce &&
            expected.fields["generation"] == schedule.generation &&
            expected.fields["executionJobId"] == guard.executionJobId.toLong() &&
            expected.fields["kind"] == guard.kind.name) { "fixture_retained_guard_evidence_mismatch" }
        record("recovery_guard_binding_restored", mapOf("jobId" to guard.jobId,
            "executionJobId" to guard.executionJobId, "kind" to guard.kind.name,
            "generation" to schedule.generation, "nonce" to schedule.nonce))
    }

    private fun onRecoveryGuardEvent(event: String, schedule: AutomationRecoveryGuardSchedule) {
        check(schedule.jobId in ownedRecoveryIds && schedule.executionJobId in ownedExecutionIds &&
            schedule.jobId == schedule.executionJobId + 0x20) { "fixture_guard_identity_not_owned" }
        val earliest = AndroidAutomationRecoveryGuard.CHECK_AFTER.toMillis()
        val deadline = earliest + AndroidAutomationRecoveryGuard.INEXACT_WINDOW.toMillis()
        if (event == "scheduled") {
            val pending = checkNotNull(scheduler.getPendingJob(schedule.jobId)) { "fixture_guard_not_persisted" }
            check(pending.service == ComponentName(context, HansAutomationWakeupJobService::class.java) &&
                pending.isPersisted && pending.minLatencyMillis == earliest &&
                pending.maxExecutionDelayMillis == deadline &&
                AndroidAutomationRecoveryGuard.decode(pending.id, pending.extras) == schedule) {
                "fixture_guard_platform_contract_mismatch"
            }
        }
        check(event in setOf("scheduled", "received", "cancelled")) { "fixture_guard_event_unknown" }
        record("recovery_guard_$event", mapOf("jobId" to schedule.jobId,
            "executionJobId" to schedule.executionJobId, "kind" to schedule.kind.name,
            "generation" to schedule.generation, "nonce" to schedule.nonce,
            "minimumLatencyMillis" to earliest, "overrideDeadlineMillis" to deadline,
            "persisted" to true))
    }

    /** Called once by #arm. The first job cannot run until after a 15-second setup grace period. */
    fun arm() {
        check(journal.events().none { it.event == "armed" || it.event == "seeded" }) { "fixture_already_armed" }
        check(storage.snapshot().runs.isEmpty() && ledger.allPending().isEmpty()) { "fixture_not_empty" }
        check(effects.countEntries() == 0L && effects.countEffects() == 0L)
        check(ownedExecutionIds.all { scheduler.getPendingJob(it) == null })
        check(ownedRecoveryIds.all { scheduler.getPendingJob(it) == null })
        check(scheduler.getPendingJob(descriptor.wakeupJobId) == null)
        val now = platform.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val definition = AutomationDefinition(
            id = AutomationId("automation.process.${descriptor.fixtureId}"), revision = 1, enabled = true,
            schedule = AutomationSchedule(LocalDateTime.ofInstant(now, ZoneId.of("UTC")), AutomationTimeZone.Fixed("UTC"), "FREQ=DAILY;COUNT=1"),
            missedRunPolicy = MissedRunPolicy(), retryPolicy = AutomationRetryPolicy(),
            target = CodexAutomationTarget.Independent,
            instruction = "Record one isolated synthetic process recovery effect.", updatedAt = now,
            requirements = AutomationRequirements(emptySet(), requiresCodexAuthentication = false, requiresNetwork = false,
                confirmationPolicy = AutomationConfirmationPolicy.CAPABILITY_POLICY),
        )
        check(storage.upsertDefinition(definition) == DefinitionWriteResult.INSERTED)
        val key = AutomationRunKey(definition.id, now)
        check(storage.recordDiscovery(AutomationDiscoveryBatch(definition.id, 1, now,
            listOf(AutomationInboxItem(key, 1, now, now, AutomationDiscoverySource.TIMER)))).accepted)
        check(storage.materializeDueInbox(now).createdRuns == listOf(key))
        record("seeded", snapshotFields())
        check(coordinator.request(descriptor.executionJobId, AutomationRuntimeWorkKind.TIMER, null) {
            record("armed", snapshotFields())
            scheduleExecution(AutomationRuntimeWorkTicket(AutomationRuntimeWorkKind.TIMER, 1, null), 15_000L,
                descriptor.initialScheduleNonce)
        }) { "fixture_initial_schedule_rejected" }
    }

    fun dispatch(trigger: AutomationRuntimeTrigger): Boolean {
        check(journal.events().any { it.event == "armed" }) { "fixture_not_armed" }
        if (trigger is AutomationRuntimeTrigger.Boot) {
            record("boot_received", mapOf("trigger" to "BOOT"))
        }
        // Only real timer/boot delivery is relevant to this narrow gate. Other system signals
        // remain within the fixture and cannot instantiate a product owner/ledger.
        if (trigger != AutomationRuntimeTrigger.Timer && trigger !is AutomationRuntimeTrigger.Boot) {
            record("unrelated_system_trigger_ignored", mapOf("trigger" to trigger.javaClass.simpleName))
            return true
        }
        val kind = trigger.workKind()
        if (trigger == AutomationRuntimeTrigger.Timer) record("wakeup_received", mapOf("trigger" to kind.name))
        val accepted = coordinator.request(jobIdFor(kind), kind, trigger.observedAtOrNull()) {
            scheduleExecution(checkNotNull(ledger.pending(kind)))
        }
        val recovered = coordinator.recoverPending(setOf(kind), ::jobIdFor) { scheduleExecution(it) }
        return accepted && recovered
    }

    private fun jobIdFor(kind: AutomationRuntimeWorkKind): Int = when (kind) {
        AutomationRuntimeWorkKind.TIMER -> descriptor.executionJobId
        AutomationRuntimeWorkKind.BOOT -> descriptor.bootExecutionJobId
        else -> error("fixture_unexpected_work_kind")
    }

    private fun scheduleExecution(ticket: AutomationRuntimeWorkTicket, delay: Long = 0L,
        nonce: String = UUID.randomUUID().toString()): Boolean {
        val jobId = jobIdFor(ticket.kind)
        val triggerName = if (ticket.kind == AutomationRuntimeWorkKind.BOOT) "boot" else "timer"
        val extras = PersistableBundle().apply {
            putString("trigger", triggerName)
            putString(AndroidAutomationDispatch.KEY_SCHEDULE_NONCE, nonce)
            ticket.observedAt?.let { putLong("observed_at", it.toEpochMilli()) }
        }
        record("execution_scheduled", mapOf("jobId" to jobId, "nonce" to nonce, "trigger" to triggerName,
            "generation" to ticket.generation, "minimumLatencyMillis" to delay, "persisted" to false))
        val job = JobInfo.Builder(jobId, ComponentName(context, HansAutomationJobService::class.java))
            .setMinimumLatency(delay).setOverrideDeadline(delay).setPersisted(false).setExtras(extras).build()
        val accepted = scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
        record(if (accepted) "platform_schedule_accepted" else "platform_schedule_rejected",
            mapOf("jobId" to jobId, "nonce" to nonce))
        return accepted
    }

    fun onWakeup(params: JobParameters) {
        check(params.jobId == descriptor.wakeupJobId)
        check(params.extras.getString("fixture_id") == descriptor.fixtureId)
        val expected = journal.events().lastOrNull { it.event == "wakeup_scheduled" }
            ?: error("fixture_wakeup_without_evidence")
        check(params.extras.getString("schedule_nonce") == expected.fields["nonce"])
        check(dispatch(AutomationRuntimeTrigger.Timer)) { "fixture_wakeup_dispatch_rejected" }
    }

    private fun executeSynthetic(request: CodexAutomationExecutionRequest, heartbeat: AutomationHeartbeat): CodexAutomationExecutionOutcome {
        check(request.idempotencyKey == storage.snapshot().runs.single().key.stableIdempotencyKey())
        val entry = effects.recordEntry(request.idempotencyKey)
        if (entry == 1L) {
            if (afterFence) {
                check(heartbeat.markExternalDispatchStarted()) { "fixture_dispatch_fence_rejected" }
                effects.recordEffect(request.idempotencyKey, entry)
            }
            record("gate_ready", snapshotFields())
            if (processLoss) {
                // Gives the passive host time to observe this exact live PID. No heartbeat,
                // cancellation, lease release or graceful shutdown runs before the hard death.
                CountDownLatch(1).await(5, TimeUnit.SECONDS)
                record("process_killing", snapshotFields())
                Process.killProcess(Process.myPid())
                throw AssertionError("fixture_self_termination_returned")
            }
            // The host must perform a real emulator reboot. A timeout is not a successful gate.
            CountDownLatch(1).await(3, TimeUnit.MINUTES)
            record("reboot_gate_timed_out", snapshotFields())
            return CodexAutomationExecutionOutcome.PermanentFailure("fixture_reboot_not_observed")
        }
        if (afterFence || entry != 2L) {
            record("unexpected_executor_entry", snapshotFields())
            return CodexAutomationExecutionOutcome.PermanentFailure("fixture_unexpected_executor_entry")
        }
        check(heartbeat.beat() && heartbeat.markExternalDispatchStarted()) { "fixture_retry_ownership_lost" }
        effects.recordEffect(request.idempotencyKey, entry)
        record("effect_recorded", snapshotFields())
        return CodexAutomationExecutionOutcome.Succeeded
    }

    private fun onFinished(jobId: Int, ticket: AutomationRuntimeWorkTicket, nonce: String?) {
        val fields = snapshotFields() + mapOf("jobId" to jobId, "generation" to ticket.generation,
            "nonce" to checkNotNull(nonce), "kind" to ticket.kind.name)
        record("cycle_finished", fields)
        val run = storage.snapshot().runs.single()
        val terminal = run.state in setOf(AutomationRunState.SUCCEEDED, AutomationRunState.FAILED_TERMINAL)
        if (terminal && ledger.allPending().isEmpty() && ownedExecutionIds.none(coordinator::isActive) &&
            journal.events().any { it.event == "gate_ready" } && journal.events().none { it.event == "recovered" }) {
            assertExpectedTerminal()
            AutomationProcessRecoveryFixture.assertProductionStoresAbsent(context)
            record("recovered", fields + mapOf("productionStoresAbsent" to true))
        }
    }

    fun record(event: String, fields: Map<String, Any> = emptyMap()) {
        journal.append(event, Process.myPid(), Process.myUid(), platform.bootSessionId().value,
            System.currentTimeMillis(), fields)
    }

    private fun snapshotFields(): Map<String, Any> {
        val snapshot = storage.snapshot()
        val run = snapshot.runs.singleOrNull()
        val requested = AutomationRuntimeWorkKind.entries.sumOf { preferences.getLong("${it.name.lowercase(Locale.ROOT)}_requested", 0) }
        val acknowledged = AutomationRuntimeWorkKind.entries.sumOf { preferences.getLong("${it.name.lowercase(Locale.ROOT)}_acknowledged", 0) }
        return buildMap {
            put("effectCount", effects.countEffects())
            put("executorEntries", effects.countEntries())
            put("requestedGeneration", requested)
            put("acknowledgedGeneration", acknowledged)
            put("leaseCount", snapshot.leases.size)
            put("coordinatorActive", ownedExecutionIds.any(coordinator::isActive))
            put("recoveryGuardCount", ownedRecoveryIds.count { scheduler.getPendingJob(it) != null })
            run?.let {
                put("runKey", it.key.stableIdempotencyKey())
                put("state", it.state.name)
                put("fence", it.dispatchFence != null)
                put("attemptCount", it.attemptCount)
                put("availableAt", it.availableAt.toString())
                it.lastFailureCode?.let { failure -> put("lastFailureCode", failure) }
            }
            snapshot.leases.singleOrNull()?.let { put("leaseExpiresAt", it.expiresAt.toString()) }
        }
    }

    private fun assertExpectedTerminal() {
        assertRecoveryIdentity()
        val snapshot = storage.snapshot()
        val run = snapshot.runs.single()
        check(snapshot.leases.isEmpty() && snapshot.inbox.isEmpty())
        check(effects.countEffects() == 1L)
        if (afterFence) {
            check(run.state == AutomationRunState.FAILED_TERMINAL && run.lastFailureCode == "automation_outcome_ambiguous")
            check(run.dispatchFence != null && effects.countEntries() == 1L)
        } else {
            check(run.state == AutomationRunState.SUCCEEDED && run.dispatchFence == null)
            check(effects.countEntries() == 2L)
        }
        check(ledger.allPending().isEmpty() && ownedExecutionIds.none(coordinator::isActive))
    }

    private fun assertRecoveryIdentity() {
        val events = journal.events()
        check(events.none { it.event in setOf("reboot_gate_timed_out", "unexpected_executor_entry") }) {
            "fixture_stimulus_failed"
        }
        val gate = events.single { it.event == "gate_ready" }
        check(gate.fields["recoveryGuardCount"] == 1L && events.any {
            it.event == "recovery_guard_scheduled" && it.sequence < gate.sequence &&
                it.pid == gate.pid && it.uid == gate.uid && it.bootId == gate.bootId &&
                it.fields["executionJobId"] == descriptor.executionJobId.toLong() &&
                it.fields["generation"] == gate.fields["requestedGeneration"] &&
                it.fields["persisted"] == true
        }) { "fixture_guard_not_installed_before_gate" }
        check(Process.myUid() == gate.uid) { "fixture_application_identity_changed" }
        if (processLoss) {
            check(Process.myPid() != gate.pid) { "fixture_process_not_replaced" }
            check(platform.bootSessionId().value == gate.bootId) { "fixture_unexpected_reboot" }
            val killed = events.single { it.event == "process_killing" }
            check(killed.pid == gate.pid && killed.bootId == gate.bootId && killed.uid == gate.uid)
            val minimumRecoveryAt = Instant.parse(gate.fields.getValue("leaseExpiresAt") as String)
                .plusSeconds(if (afterFence) 0L else 30L)
            check(!platform.now().isBefore(minimumRecoveryAt)) { "fixture_recovery_before_real_lease_expiry" }
        } else {
            check(platform.bootSessionId().value != gate.bootId) { "fixture_reboot_not_observed" }
            val currentBoot = platform.bootSessionId().value
            check(events.any { it.event == "boot_received" && it.bootId == currentBoot }) {
                "fixture_boot_broadcast_not_observed"
            }
            check(events.any { it.event == "cycle_finished" && it.bootId == currentBoot &&
                it.fields["jobId"] == descriptor.bootExecutionJobId.toLong() }) {
                "fixture_boot_cycle_not_finished"
            }
            check(preferences.getLong("boot_requested", 0) >= 1 &&
                preferences.getLong("boot_requested", 0) == preferences.getLong("boot_acknowledged", 0)) {
                "fixture_boot_work_not_acknowledged"
            }
        }
    }

    /** Read-only verification, after the host already captured autonomous recovered evidence. */
    fun verify() {
        check(journal.events().any { it.event == "recovered" }) { "fixture_not_autonomously_recovered" }
        assertExpectedTerminal()
        AutomationProcessRecoveryFixture.assertProductionStoresAbsent(context)
        check(ownedExecutionIds.all { scheduler.getPendingJob(it) == null }) { "fixture_execution_job_not_finished" }
        check(ownedRecoveryIds.all { scheduler.getPendingJob(it) == null }) { "fixture_recovery_guard_not_finished" }
        // A newly opened view is safe only now, after the actual completion/ACK checkpoint.
        SQLiteAutomationSnapshotPersistence(context, descriptor.databaseName).use { reopened ->
            check(PersistentAutomationStorage(reopened).snapshot() == storage.snapshot())
        }
    }

    private inner class FixtureWakeupBackend : AndroidAutomationWakeupBackend {
        override fun canScheduleExactAlarms(): Boolean = false
        override fun wallClockMillis(): Long = System.currentTimeMillis()
        override fun scheduleInexactWakeup(earliestAt: Instant, deadlineAt: Instant, persistedAcrossReboot: Boolean): Boolean {
            val nonce = UUID.randomUUID().toString()
            val now = wallClockMillis()
            val earliest = (earliestAt.toEpochMilli() - now).coerceAtLeast(0)
            val deadline = (deadlineAt.toEpochMilli() - now).coerceAtLeast(earliest)
            record("wakeup_scheduled", mapOf("jobId" to descriptor.wakeupJobId, "nonce" to nonce,
                "earliestAt" to earliestAt.toString(), "deadlineAt" to deadlineAt.toString(),
                "persisted" to persistedAcrossReboot))
            val job = JobInfo.Builder(descriptor.wakeupJobId, ComponentName(context, HansAutomationWakeupJobService::class.java))
                .setMinimumLatency(earliest).setOverrideDeadline(deadline).setPersisted(persistedAcrossReboot)
                .setExtras(PersistableBundle().apply {
                    putString("fixture_id", descriptor.fixtureId)
                    putString("schedule_nonce", nonce)
                }).build()
            val accepted = scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
            record(if (accepted) "wakeup_schedule_accepted" else "wakeup_schedule_rejected",
                mapOf("jobId" to descriptor.wakeupJobId, "nonce" to nonce))
            return accepted
        }
        override fun scheduleExactWakeup(triggerAt: Instant): Boolean = error("fixture_exact_alarm_forbidden")
        override fun enqueueTimerCycle(): Boolean = dispatch(AutomationRuntimeTrigger.Timer)
        override fun cancelInexactWakeup() {
            val pending = scheduler.getPendingJob(descriptor.wakeupJobId) ?: return
            check(pending.service == ComponentName(context, HansAutomationWakeupJobService::class.java))
            check(pending.extras.getString("fixture_id") == descriptor.fixtureId)
            scheduler.cancel(descriptor.wakeupJobId)
            record("wakeup_cancelled", mapOf("jobId" to descriptor.wakeupJobId))
        }
        override fun cancelExactWakeup() = Unit // This fixture never installs any exact alarm.
    }
}

/** Every executor/effect attempt is recorded: no deduplication constraint can hide a replay. */
private class ProcessRecoveryEffects(file: File) {
    private val database: SQLiteDatabase
    init {
        check(!Files.isSymbolicLink(file.toPath()))
        val exists = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        if (exists) check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        database = if (exists) SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            else SQLiteDatabase.openOrCreateDatabase(file, null)
        database.execSQL("PRAGMA synchronous=FULL")
        if (!exists) {
            database.beginTransaction()
            try {
                database.execSQL("CREATE TABLE executor_entries (sequence INTEGER PRIMARY KEY AUTOINCREMENT, idempotency_key TEXT NOT NULL)")
                database.execSQL("CREATE TABLE synthetic_effects (sequence INTEGER PRIMARY KEY AUTOINCREMENT, idempotency_key TEXT NOT NULL, executor_entry INTEGER NOT NULL)")
                database.version = 1
                database.setTransactionSuccessful()
            } finally { database.endTransaction() }
        }
        check(database.version == 1) { "fixture_effect_schema_mismatch" }
        for ((table, columns) in listOf("executor_entries" to listOf("sequence", "idempotency_key"),
            "synthetic_effects" to listOf("sequence", "idempotency_key", "executor_entry"))) {
            val actual = database.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            check(actual == columns) { "fixture_effect_schema_mismatch" }
        }
    }

    @Synchronized fun recordEntry(key: String): Long = database.insertOrThrow("executor_entries", null,
        ContentValues().apply { put("idempotency_key", key) })
    @Synchronized fun recordEffect(key: String, entry: Long) {
        database.insertOrThrow("synthetic_effects", null, ContentValues().apply {
            put("idempotency_key", key)
            put("executor_entry", entry)
        })
    }
    @Synchronized fun countEntries(): Long = count("executor_entries")
    @Synchronized fun countEffects(): Long = count("synthetic_effects")
    fun close() = database.close()
    private fun count(table: String): Long = database.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }
}
