package ai.hans.standard.automations

import ai.hans.standard.BuildConfig
import ai.hans.standard.backup.HansBackupMaintenance
import ai.hans.standard.backup.HansBackupProcessState
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

class AndroidAutomationWakeupAdapter internal constructor(
    private val backend: AndroidAutomationWakeupBackend,
    private val inexactWindow: Duration = AndroidAutomationWakeupPolicy.DEFAULT_INEXACT_WINDOW,
) : AutomationWakeupAdapter {
    constructor(
        context: Context,
        inexactWindow: Duration = AndroidAutomationWakeupPolicy.DEFAULT_INEXACT_WINDOW,
    ) : this(
        backend = AndroidPlatformAutomationWakeupBackend(context.applicationContext),
        inexactWindow = inexactWindow,
    )

    override fun replaceWakeup(plan: AutomationWakeupPlan): AutomationAdapterResult {
        val exactAccess = backend.canScheduleExactAlarms()
        return when (
            val decision = AndroidAutomationWakeupPolicy.decide(
                plan,
                exactAlarmAccessGranted = exactAccess,
                inexactWindow = inexactWindow,
            )
        ) {
            is AndroidAutomationWakeupDecision.ExactAlarmAccessRequired -> {
                // Do not clear an already valid wakeup merely because exact-alarm access was
                // revoked or its inexact fallback could not be installed. A stale one-shot wakeup
                // can at worst cause an idempotent reconciliation; losing the only wakeup can
                // strand persisted work indefinitely.
                val fallbackResult = decision.fallback?.let(::replaceSuccessfullyFirst)
                if (fallbackResult is AutomationAdapterResult.Rejected) {
                    fallbackResult
                } else {
                    AutomationAdapterResult.Rejected(
                        "exact_alarm_access_required",
                        retryable = false,
                    )
                }
            }
            is AndroidAutomationWakeupDecision.Ready -> replaceSuccessfullyFirst(decision.request)
        }
    }

    /**
     * Same-channel scheduling uses Android's stable JobInfo/PendingIntent identity to replace the
     * old entry. The other channel is cancelled only after that replacement succeeds. This gives
     * failures loss-preserving semantics; a crash between the two operations may yield one extra
     * idempotent wakeup, never no wakeup at all.
     */
    private fun replaceSuccessfullyFirst(
        request: AndroidAutomationWakeupRequest,
    ): AutomationAdapterResult = when (request) {
        AndroidAutomationWakeupRequest.CancelExisting -> {
            backend.cancelInexactWakeup()
            backend.cancelExactWakeup()
            AutomationAdapterResult.Accepted
        }
        is AndroidAutomationWakeupRequest.JobScheduler -> {
            if (
                backend.scheduleInexactWakeup(
                    request.earliestAt,
                    request.overrideDeadlineAt,
                    request.persistedAcrossReboot,
                )
            ) {
                backend.cancelExactWakeup()
                AutomationAdapterResult.Accepted
            } else {
                AutomationAdapterResult.Rejected("job_scheduler_rejected")
            }
        }
        is AndroidAutomationWakeupRequest.ExactAlarm -> {
            if (request.triggerAt.toEpochMilli() <= backend.wallClockMillis()) {
                if (backend.enqueueTimerCycle()) {
                    backend.cancelInexactWakeup()
                    backend.cancelExactWakeup()
                    AutomationAdapterResult.Accepted
                } else {
                    AutomationAdapterResult.Rejected("job_scheduler_rejected")
                }
            } else if (backend.scheduleExactWakeup(request.triggerAt)) {
                backend.cancelInexactWakeup()
                AutomationAdapterResult.Accepted
            } else {
                AutomationAdapterResult.Rejected("exact_alarm_rejected")
            }
        }
    }

    companion object {
        const val ACTION_ALARM = "ai.hans.standard.automations.ALARM"
    }
}

/** Injectable platform seam; unit tests can prove replacement failure never clears prior work. */
internal interface AndroidAutomationWakeupBackend {
    fun canScheduleExactAlarms(): Boolean

    fun wallClockMillis(): Long

    fun scheduleInexactWakeup(
        earliestAt: Instant,
        deadlineAt: Instant,
        persistedAcrossReboot: Boolean,
    ): Boolean

    fun scheduleExactWakeup(triggerAt: Instant): Boolean

    fun enqueueTimerCycle(): Boolean

    fun cancelInexactWakeup()

    fun cancelExactWakeup()
}

private class AndroidPlatformAutomationWakeupBackend(
    context: Context,
) : AndroidAutomationWakeupBackend {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)
    private val jobScheduler = appContext.getSystemService(JobScheduler::class.java)

    override fun canScheduleExactAlarms(): Boolean =
        runCatching { alarmManager?.canScheduleExactAlarms() == true }.getOrDefault(false)

    override fun wallClockMillis(): Long = System.currentTimeMillis()

    override fun scheduleInexactWakeup(
        earliestAt: Instant,
        deadlineAt: Instant,
        persistedAcrossReboot: Boolean,
    ): Boolean = runCatching {
        AndroidAutomationDispatch.scheduleAt(
            appContext,
            earliestAt,
            deadlineAt,
            persistedAcrossReboot,
        )
    }.getOrDefault(false)

    override fun scheduleExactWakeup(triggerAt: Instant): Boolean = runCatching {
        val manager = alarmManager ?: return@runCatching false
        manager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt.toEpochMilli(),
            alarmPendingIntent(),
        )
        true
    }.getOrDefault(false)

    override fun enqueueTimerCycle(): Boolean = runCatching {
        AndroidAutomationDispatch.enqueue(appContext, AutomationRuntimeTrigger.Timer)
    }.getOrDefault(false)

    override fun cancelInexactWakeup() {
        runCatching { jobScheduler?.cancel(AndroidAutomationDispatch.WAKEUP_JOB_ID) }
    }

    override fun cancelExactWakeup() {
        runCatching { alarmManager?.cancel(alarmPendingIntent()) }
    }

    private fun alarmPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        ALARM_REQUEST_CODE,
        Intent(appContext, HansAutomationAlarmReceiver::class.java)
            .setAction(AndroidAutomationWakeupAdapter.ACTION_ALARM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val ALARM_REQUEST_CODE = 41_903
    }
}

/** Thin public-API JobScheduler worker. The process-wide owner is installed by HansApplication. */
class HansAutomationJobService : JobService() {
    private data class ActiveJob(
        val parameters: JobParameters,
        val binding: AndroidAutomationJobBinding,
        val registration: AutomationRuntimeWorkCoordinator.Registration,
        val cycle: AutomationJobCycleState,
    )

    private val activeJobs = ConcurrentHashMap<Int, ActiveJob>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val diagnostics by lazy(LazyThreadSafetyMode.NONE) {
        AndroidAutomationJobDiagnostics.create(this)
    }

    override fun onStartJob(params: JobParameters): Boolean {
        val binding = AndroidAutomationDispatch.bindingForJob(this, params.jobId) ?: return false
        val started = binding.coordinator.start(
            params.jobId,
            AndroidAutomationDispatch.triggerFrom(params.extras).workKind(),
        )
            as? AutomationRuntimeWorkCoordinator.StartResult.Started
            ?: return false
        val active = ActiveJob(
            params,
            binding,
            started.registration,
            AutomationJobCycleState(scheduleKey(params)),
        )
        val previous = activeJobs.put(params.jobId, active)
        previous?.let(::retire)
        started.superseded?.cancel()
        started.registration.attachCancellation(active.cycle::cancel)
        runCatching { binding.onStarted(params.jobId, started.registration.ticket, scheduleKey(params).schedulingNonce) }
            .onFailure { Log.w("HansAutomationJob", "job=${params.jobId} observer_failed") }
        startCycle(active)
        return true
    }

    private fun startCycle(active: ActiveJob) {
        if (!isCurrent(active)) return
        val guard = active.binding.recoveryGuard
        val guardReady = guard == null || runCatching {
            active.binding.coordinator.installRecoveryGuard(active.registration, guard::arm)
        }.getOrDefault(false)
        if (!guardReady) {
            // Do not enter the owner's queue or obtain a lease without an accepted backstop.
            // Post even for synchronous failure: onStartJob must return before jobFinished.
            if (!mainHandler.post { if (isCurrent(active)) finishForRetry(active) }) retire(active)
            return
        }
        val epoch = active.cycle.beginCycle() ?: return
        val trigger = active.registration.ticket.toRuntimeTrigger()
        val handle = active.binding.owner.requestCycle(trigger) { result ->
            // Android serializes start/stop on the main handler, but the owner's completion is
            // normally on a worker and can also arrive synchronously on executor rejection.
            // Always enqueue it: never recurse into a new cycle before publishing this handle.
            if (!mainHandler.post { completeCycle(active, epoch, result) }) {
                // A terminating main looper cannot finish platform work. Preserve its pending
                // generation and stop only this local run; Android owns recovery afterward.
                retire(active)
            }
        }
        active.cycle.attachHandle(epoch, handle)
    }

    /** Invoked only on the same main handler as Android's lifecycle callbacks. */
    private fun completeCycle(
        active: ActiveJob,
        epoch: Long,
        result: Result<AutomationRuntimeCycleReport>,
    ) {
        if (!isCurrent(active) || !active.cycle.takeCompletion(epoch)) return
        try {
            val report = result.getOrNull()
            val retryableWakeupFailure = report?.wakeupResult
                ?.let { it as? AutomationAdapterResult.Rejected }
                ?.retryable == true
            val checkpointedYield = report?.stopReason != null
            if (result.isFailure || retryableWakeupFailure || checkpointedYield) {
                finishForRetry(active)
                return
            }
            when (active.binding.coordinator.acknowledgeAndRecheck(active.registration)) {
                is AutomationRuntimeWorkCoordinator.CheckpointResult.Continue ->
                    startCycle(active)
                AutomationRuntimeWorkCoordinator.CheckpointResult.ReadyToFinish -> {
                    when (
                        active.binding.coordinator.finishIfCaughtUp(active.registration) {
                            // This is under request()/recovery's lock and only for the current,
                            // fully acknowledged registration. A late worker cannot delete the
                            // backstop of a new generation. Stop/retry paths never cancel it.
                            active.binding.recoveryGuard?.cancelCaughtUp()
                            jobFinished(active.parameters, false)
                            activeJobs.remove(active.parameters.jobId, active)
                        }
                    ) {
                        is AutomationRuntimeWorkCoordinator.FinalizationResult.Continue ->
                            startCycle(active)
                        AutomationRuntimeWorkCoordinator.FinalizationResult.Finished -> {
                            closeLocalRun(active)
                            // This passive observer runs after the actual ACK, jobFinished and
                            // release, outside the coordinator lock. It cannot create work or
                            // turn an observation failure into a retry of completed effects.
                            runCatching {
                                active.binding.onFinished(active.parameters.jobId, active.registration.ticket,
                                    scheduleKey(active.parameters).schedulingNonce)
                            }.onFailure {
                                Log.w("HansAutomationJob", "job=${active.parameters.jobId} observer_failed")
                            }
                        }
                        AutomationRuntimeWorkCoordinator.FinalizationResult.Superseded ->
                            retire(active)
                    }
                }
                AutomationRuntimeWorkCoordinator.CheckpointResult.Superseded -> retire(active)
            }
        } catch (_: Exception) {
            // Previously these exceptions were swallowed by the worker callback and stranded the
            // job. Do not crash Android's main looper or acknowledge an uncommitted checkpoint.
            Log.w("HansAutomationJob", "job=${active.parameters.jobId} completion_checkpoint_failed")
            finishForRetry(active)
        }
    }

    private fun isCurrent(active: ActiveJob): Boolean =
        activeJobs[active.parameters.jobId] === active &&
            active.binding.coordinator.isCurrent(active.registration)

    private fun closeLocalRun(active: ActiveJob) {
        // Cancellation listeners must not run under a coordinator monitor, which is also held
        // during scheduling/ledger writes. Signal the executor before attempting that monitor.
        active.cycle.cancel()
        active.registration.cancel()
    }

    private fun retire(active: ActiveJob) {
        activeJobs.remove(active.parameters.jobId, active)
        closeLocalRun(active)
        active.binding.coordinator.release(active.registration)
    }

    private fun finishForRetry(active: ActiveJob) {
        if (!isCurrent(active)) return
        closeLocalRun(active)
        // Install a replacement under request()'s lock. A successful same-ID schedule replaces
        // and stops the old delivery by Android contract; a rejected install is the only path that
        // completes the old delivery with a framework retry request.
        // A plain framework retry can retain an expired timing constraint without a timely
        // reevaluation on Android 12. An explicit scheduling with a deadline closes that gap.
        // Failed installation falls back to the framework; neither path acknowledges the work.
        try {
            val released = active.binding.coordinator.releaseForRetry(
                active.registration,
                installReplacement = {
                    active.binding.scheduleRetry(
                        active.registration.ticket,
                        AndroidAutomationDispatch.retryAttemptFrom(active.parameters.extras),
                    )
                },
            ) { needsFrameworkRetry ->
                // schedule() with the same ID stops the running job and replaces its scheduling.
                // Android explicitly requires that path to be used *instead of* jobFinished().
                // Only a rejected replacement leaves the old framework delivery responsible for
                // retrying the still-unacknowledged generation.
                if (needsFrameworkRetry) jobFinished(active.parameters, true)
                activeJobs.remove(active.parameters.jobId, active)
            }
            if (!released) retire(active)
        } catch (_: Exception) {
            // Keep the ledger pending if the platform cannot accept completion. The framework
            // retains responsibility for its job; never turn this into a successful checkpoint.
            retire(active)
            Log.w("HansAutomationJob", "job=${active.parameters.jobId} retry_finish_failed")
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val active = activeJobs[params.jobId]
        val stoppedCurrentScheduling = active?.cycle?.matchesStop(scheduleKey(params)) == true
        if (stoppedCurrentScheduling) {
            retire(active)
        }
        diagnostics.recordStop(params.jobId, params.stopReason)
        // A same-ID replacement intentionally stops the old scheduling. Its nonce no longer owns
        // this job ID and must not request a framework retry that can overwrite the replacement.
        return stoppedCurrentScheduling
    }

    override fun onDestroy() {
        activeJobs.values.toList().forEach(::retire)
        super.onDestroy()
    }

    private fun scheduleKey(params: JobParameters): AutomationJobScheduleKey =
        AutomationJobScheduleKey(
            params.jobId,
            params.extras.getString(AndroidAutomationDispatch.KEY_SCHEDULE_NONCE),
        )
}

/**
 * A persisted future wakeup is deliberately separate from the execution job. If the worker were
 * to replace its own JobScheduler ID while finishing a cycle, Android would stop that running job.
 */
class HansAutomationWakeupJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        if (params.jobId == AndroidAutomationNetworkRecoveryBackstop.JOB_ID) {
            // JobScheduler may satisfy the constraint with a validated non-default network. Hans'
            // actual work follows Android's default route, so consume this one-shot only when the
            // assigned network and the current default are both usable.
            if (
                !AutomationNetworkRecoveryDeliveryPolicy.ready(
                    assignedNetworkValidated =
                        AndroidAutomationNetworkRecoveryBackstop.isValidated(this, params.network),
                    defaultNetworkValidated =
                        AndroidAutomationNetworkRecoveryBackstop.isDefaultValidated(this),
                )
            ) {
                return finishNetworkRecoveryForRetry(params)
            }
            val accepted = AndroidAutomationDispatch.enqueueOrRetry(
                this,
                AutomationRuntimeTrigger.ConnectivityRestored,
            )
            return if (accepted) false else finishNetworkRecoveryForRetry(params)
        }
        val recoveryBinding = AndroidAutomationDispatch.bindingForRecoveryJob(this, params.jobId)
        if (recoveryBinding != null) {
            val guard = checkNotNull(recoveryBinding.recoveryGuard)
            val delivered = AndroidAutomationRecoveryGuard.readDelivery(params) ?: return false
            val result = guard.receive(delivered, recoveryBinding.coordinator) { ticket ->
                // Reuse the real bounded execution replacement without minting a generation.
                recoveryBinding.scheduleRetry(ticket, 0)
            }
            if (result == AutomationRuntimeWorkCoordinator.RecoveryGuardResult.REARM_REJECTED) {
                // No successor was accepted. Keep the framework-owned delivery retryable;
                // never finish(true) after a new successor has already been scheduled.
                val posted = Handler(Looper.getMainLooper()).post { jobFinished(params, true) }
                if (!posted) Log.w("HansAutomationJob", "recovery_guard_finish_rejected")
                // If the looper is terminating, do not falsely acknowledge this delivery.
                // The unfinished framework job remains recoverable with the dying process.
                return true
            }
            return false
        }
        if (params.jobId != AndroidAutomationDispatch.WAKEUP_JOB_ID) {
            // A namespaced debug wakeup may reuse this real component. Unknown IDs must never
            // open the product ledger, including after an absent/rejected fixture descriptor.
            (applicationContext as? AndroidAutomationDispatchInterceptor)
                ?.interceptAutomationWakeup(params)
            return false
        }
        if (!AndroidAutomationDispatch.enqueue(this, AutomationRuntimeTrigger.Timer)) {
            AndroidAutomationDispatch.scheduleRetry(
                this,
                AutomationRuntimeTrigger.Timer,
                failedAttempt = 0,
            )
        }
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean =
        params.jobId == AndroidAutomationNetworkRecoveryBackstop.JOB_ID ||
            AndroidAutomationDispatch.bindingForRecoveryJob(this, params.jobId) != null

    private fun finishNetworkRecoveryForRetry(params: JobParameters): Boolean {
        // Preserve the framework-owned one-shot when validation vanished or no durable dispatch
        // handoff was accepted. Posting guarantees onStartJob returns before jobFinished.
        Handler(Looper.getMainLooper()).post { jobFinished(params, true) }
        // If the main looper is already terminating and rejects the post, keeping the job marked
        // asynchronous lets Android recover it with the dying process instead of consuming it.
        return true
    }
}

/** Exact alarms only enqueue the JobService; no long work runs inside receiver time limits. */
class HansAutomationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val (trigger, failedAttempt) = when (intent?.action) {
            AndroidAutomationWakeupAdapter.ACTION_ALARM ->
                AutomationRuntimeTrigger.Timer to 0
            AndroidAutomationDispatch.ACTION_DISPATCH_RETRY ->
                AndroidAutomationDispatch.triggerFrom(intent) to
                    AndroidAutomationDispatch.retryAttemptFrom(intent)
            else -> return
        }
        if (!AndroidAutomationDispatch.enqueue(context, trigger)) {
            AndroidAutomationDispatch.scheduleRetry(context, trigger, failedAttempt)
        }
    }
}

/** Boot/time/package broadcasts are converted into a serialized JobService cycle. */
class HansAutomationSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val now = Instant.now()
        val trigger = when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED -> AutomationRuntimeTrigger.Boot(now)
            Intent.ACTION_TIMEZONE_CHANGED -> AutomationRuntimeTrigger.TimeZoneChanged(now)
            Intent.ACTION_TIME_CHANGED -> AutomationRuntimeTrigger.WallClockChanged(now)
            Intent.ACTION_MY_PACKAGE_REPLACED -> AutomationRuntimeTrigger.DefinitionChanged
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ->
                AutomationRuntimeTrigger.DefinitionChanged
            else -> return
        }
        if (!AndroidAutomationDispatch.enqueue(context, trigger)) {
            AndroidAutomationDispatch.scheduleRetry(context, trigger, failedAttempt = 0)
        }
    }
}

/**
 * ACTION_USER_PRESENT is protected and explicitly documented as a runtime-registered broadcast.
 * Keeping this receiver process-scoped avoids an ineffective manifest declaration on target 36.
 * Launcher resume and the user-enabled AccessibilityService provide additional cold-process
 * opportunities; a persisted retry remains the eventual-delivery fallback when neither exists.
 */
internal class HansAutomationUserPresentReceiver(
    private val dispatch: (Context) -> Unit = { context ->
        AndroidAutomationUnlockRecovery.onProtectedUserPresent(context)
    },
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_USER_PRESENT) dispatch(context)
    }
}

/** One receiver for the Application process; registration performs no polling or wakeup. */
internal object AndroidAutomationUserPresentMonitor {
    @Volatile
    private var receiver: HansAutomationUserPresentReceiver? = null

    @Synchronized
    fun ensureRegistered(context: Context): Boolean {
        if (receiver != null) return true
        val appContext = context.applicationContext
        val candidate = HansAutomationUserPresentReceiver()
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                // USER_PRESENT is a protected system-only broadcast. EXPORTED is required so the
                // framework sender can reach a context-registered receiver on current Android.
                appContext.registerReceiver(candidate, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(candidate, filter)
            }
        }.isSuccess
        if (registered) {
            receiver = candidate
        } else {
            Log.w("HansAutomation", "user_present_registration_failed")
        }
        return registered
    }
}

/** Synchronous app-private ledger; commit success is part of dispatch acceptance. */
private class SharedPreferencesAutomationRuntimeWorkLedger(
    private val preferences: SharedPreferences,
) : AutomationRuntimeWorkLedger {
    @Synchronized
    override fun record(
        kind: AutomationRuntimeWorkKind,
        observedAt: Instant?,
    ): AutomationRuntimeWorkTicket {
        val requestedKey = requestedKey(kind)
        val current = preferences.getLong(requestedKey, 0L)
        check(current < Long.MAX_VALUE) { "automation_work_generation_exhausted" }
        val generation = current + 1L
        val acknowledged = preferences.getLong(acknowledgedKey(kind), 0L)
        val priorObservedAt = if (current > acknowledged) {
            preferences.getString(observedAtKey(kind), null)
                ?.let { runCatching { Instant.parse(it) }.getOrNull() }
        } else {
            null
        }
        val retainedObservedAt = listOfNotNull(priorObservedAt, observedAt).maxOrNull()
        val editor = preferences.edit().putLong(requestedKey, generation)
        if (retainedObservedAt != null) {
            editor.putString(observedAtKey(kind), retainedObservedAt.toString())
        }
        check(editor.commit()) { "automation_work_generation_write_failed" }
        return AutomationRuntimeWorkTicket(kind, generation, retainedObservedAt)
    }

    @Synchronized
    override fun pending(kind: AutomationRuntimeWorkKind): AutomationRuntimeWorkTicket? {
        val requested = preferences.getLong(requestedKey(kind), 0L)
        val acknowledged = preferences.getLong(acknowledgedKey(kind), 0L)
        if (requested <= acknowledged) return null
        val observedAt = preferences.getString(observedAtKey(kind), null)
            ?.let { Instant.parse(it) }
        return AutomationRuntimeWorkTicket(kind, requested, observedAt)
    }

    @Synchronized
    override fun allPending(): List<AutomationRuntimeWorkTicket> =
        AutomationRuntimeWorkKind.entries.mapNotNull(::pending)

    @Synchronized
    override fun acknowledge(
        ticket: AutomationRuntimeWorkTicket,
    ): AutomationRuntimeWorkTicket? {
        val requested = preferences.getLong(requestedKey(ticket.kind), 0L)
        val previousAcknowledged = preferences.getLong(acknowledgedKey(ticket.kind), 0L)
        check(ticket.generation <= requested) { "automation_work_generation_invalid" }
        val acknowledged = maxOf(previousAcknowledged, ticket.generation)
        if (acknowledged != previousAcknowledged) {
            val editor = preferences.edit()
                .putLong(acknowledgedKey(ticket.kind), acknowledged)
            if (acknowledged >= requested) editor.remove(observedAtKey(ticket.kind))
            check(editor.commit()) { "automation_work_generation_write_failed" }
        }
        return pending(ticket.kind)
    }

    private fun requestedKey(kind: AutomationRuntimeWorkKind): String =
        "${kind.name.lowercase()}_requested"

    private fun acknowledgedKey(kind: AutomationRuntimeWorkKind): String =
        "${kind.name.lowercase()}_acknowledged"

    private fun observedAtKey(kind: AutomationRuntimeWorkKind): String =
        "${kind.name.lowercase()}_observed_at"
}

/** A running job retains its exact owner/ledger even if a later job uses another binding. */
internal class AndroidAutomationJobBinding(
    val owner: AutomationRuntimeOwner,
    val coordinator: AutomationRuntimeWorkCoordinator,
    val scheduleRetry: (AutomationRuntimeWorkTicket, Int) -> Boolean,
    val onStarted: (Int, AutomationRuntimeWorkTicket, String?) -> Unit = { _, _, _ -> },
    val onFinished: (Int, AutomationRuntimeWorkTicket, String?) -> Unit = { _, _, _ -> },
    // Null is available only to the existing namespaced, in-process debug fixtures. Every
    // production binding and the real process-loss fixture supplies the platform backstop.
    val recoveryGuard: AndroidAutomationRecoveryGuard? = null,
)

/**
 * Optional composition boundary implemented only in the debug source set. Null delegates to
 * the ordinary product path; false is an explicit rejection and must not open product stores.
 * There is no Android control endpoint and the shipping Application does not implement it.
 */
internal interface AndroidAutomationDispatchInterceptor {
    fun interceptAutomationEnqueue(trigger: AutomationRuntimeTrigger): Boolean?

    fun interceptAutomationRetry(trigger: AutomationRuntimeTrigger, failedAttempt: Int): Boolean?

    fun interceptAutomationWakeup(params: JobParameters): Boolean?
}

internal object AndroidAutomationDispatch {
    const val WAKEUP_JOB_ID = 0x48414E53 // "HANS"
    const val ACTION_DISPATCH_RETRY = "ai.hans.standard.automations.DISPATCH_RETRY"
    const val KEY_SCHEDULE_NONCE = "schedule_nonce"
    private const val EXECUTION_JOB_ID_BASE = 0x48414E60
    private const val RETRY_ALARM_REQUEST_CODE_BASE = 0x48414E70
    private const val RECOVERY_JOB_ID_BASE = 0x48414E80
    private const val KEY_TRIGGER = "trigger"
    private const val KEY_OBSERVED_AT = "observed_at"
    private const val KEY_RETRY_ATTEMPT = "retry_attempt"
    private const val EXECUTION_RETRY_WINDOW_MILLIS = 5_000L
    private const val WORK_LEDGER_PREFERENCES = "hans_automation_work_generations_v1"
    private val TEST_JOB_IDS = 0x48540000..0x4854ffff
    private val testBindings = ConcurrentHashMap<Int, AndroidAutomationJobBinding>()

    @Volatile
    private var workCoordinator: AutomationRuntimeWorkCoordinator? = null

    private fun coordinator(context: Context): AutomationRuntimeWorkCoordinator {
        workCoordinator?.let { return it }
        return synchronized(this) {
            workCoordinator ?: AutomationRuntimeWorkCoordinator(
                SharedPreferencesAutomationRuntimeWorkLedger(
                    context.applicationContext.getSharedPreferences(
                        WORK_LEDGER_PREFERENCES,
                        Context.MODE_PRIVATE,
                    ),
                ),
            ).also { workCoordinator = it }
        }
    }

    fun bindingForJob(context: Context, jobId: Int): AndroidAutomationJobBinding? {
        // Unknown test jobs fail closed instead of opening the production work ledger.
        if (jobId in TEST_JOB_IDS) return testBindings[jobId]
        if (!HansBackupProcessState.maintenance.isRecoveryReady) return null
        val kind = AutomationRuntimeWorkKind.entries.singleOrNull { executionJobId(it) == jobId }
            ?: return null
        val owner = HansAutomationRuntime.currentOrNull() ?: return null
        return AndroidAutomationJobBinding(
            owner,
            coordinator(context),
            scheduleRetry = { ticket, failedAttempt ->
                scheduleExecutionRetry(context, jobId, ticket, failedAttempt)
            },
            recoveryGuard = AndroidAutomationRecoveryGuard(context, recoveryJobId(kind), jobId, kind),
        )
    }

    fun bindingForRecoveryJob(context: Context, jobId: Int): AndroidAutomationJobBinding? {
        if (jobId in TEST_JOB_IDS) {
            return testBindings.values.singleOrNull { it.recoveryGuard?.jobId == jobId }
        }
        val kind = AutomationRuntimeWorkKind.entries.singleOrNull { recoveryJobId(it) == jobId }
            ?: return null
        return bindingForJob(context, executionJobId(kind))
    }

    /**
     * Process-local, debug-only composition seam. It cannot replace production job IDs, the
     * process-wide runtime or user stores, and exposes no Android component or remote endpoint.
     * Callers must settle their owned worker callbacks before closing their binding.
     */
    internal fun installTestBinding(
        context: Context,
        jobId: Int,
        owner: AutomationRuntimeOwner,
        coordinator: AutomationRuntimeWorkCoordinator,
        scheduleRetry: (AutomationRuntimeWorkTicket, Int) -> Boolean = { _, _ -> false },
        onStarted: (Int, AutomationRuntimeWorkTicket, String?) -> Unit = { _, _, _ -> },
        onFinished: (Int, AutomationRuntimeWorkTicket, String?) -> Unit = { _, _, _ -> },
        recoveryGuard: AndroidAutomationRecoveryGuard? = null,
    ): AutoCloseable {
        check(
            BuildConfig.DEBUG &&
                context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        ) { "automation_test_binding_requires_debug_application" }
        require(jobId in TEST_JOB_IDS) { "automation_test_binding_requires_owned_job_id" }
        check(context.getSystemService(JobScheduler::class.java).getPendingJob(jobId) == null) {
            "automation_test_binding_requires_unused_job_id"
        }
        checkTestRecoveryGuard(jobId, recoveryGuard)
        val binding = AndroidAutomationJobBinding(owner, coordinator, scheduleRetry, onStarted, onFinished, recoveryGuard)
        check(testBindings.putIfAbsent(jobId, binding) == null) {
            "automation_test_binding_already_installed"
        }
        return AutoCloseable { testBindings.remove(jobId, binding) }
    }

    /** Reuses the actual synchronous production ledger, but only in a UUID-owned test namespace. */
    internal fun isolatedTestWorkLedger(context: Context, fixtureId: UUID): AutomationRuntimeWorkLedger {
        checkDebugTestApplication(context)
        return SharedPreferencesAutomationRuntimeWorkLedger(
            context.applicationContext.getSharedPreferences(
                "automation-process-$fixtureId-work",
                Context.MODE_PRIVATE,
            ),
        )
    }

    /**
     * Restore a binding for an Android-retained test job without replacing/cancelling that job.
     * Missing jobs are a distinct error; callers may use the fresh-install seam only after
     * observing that absence. The expected scheduling identity comes from committed fixture
     * evidence, never from trusting whatever job happens to occupy this reserved ID.
     */
    internal fun restoreTestBinding(
        context: Context,
        jobId: Int,
        expectedScheduleNonce: UUID,
        expectedTrigger: String,
        owner: AutomationRuntimeOwner,
        coordinator: AutomationRuntimeWorkCoordinator,
        scheduleRetry: (AutomationRuntimeWorkTicket, Int) -> Boolean = { _, _ -> false },
        onStarted: (Int, AutomationRuntimeWorkTicket, String?) -> Unit = { _, _, _ -> },
        onFinished: (Int, AutomationRuntimeWorkTicket, String?) -> Unit = { _, _, _ -> },
        recoveryGuard: AndroidAutomationRecoveryGuard? = null,
    ): AutoCloseable {
        checkDebugTestApplication(context)
        require(jobId in TEST_JOB_IDS) { "automation_test_binding_requires_owned_job_id" }
        require(expectedTrigger in setOf("timer", "boot")) { "automation_test_binding_invalid_trigger" }
        val pending = checkNotNull(context.getSystemService(JobScheduler::class.java).getPendingJob(jobId)) {
            "automation_test_restore_job_missing"
        }
        check(pending.service == ComponentName(context, HansAutomationJobService::class.java)) {
            "automation_test_restore_service_mismatch"
        }
        check(pending.extras.getString(KEY_SCHEDULE_NONCE) == expectedScheduleNonce.toString()) {
            "automation_test_restore_nonce_mismatch"
        }
        check(pending.extras.getString(KEY_TRIGGER) == expectedTrigger) {
            "automation_test_restore_trigger_mismatch"
        }
        checkTestRecoveryGuard(jobId, recoveryGuard)
        val binding = AndroidAutomationJobBinding(owner, coordinator, scheduleRetry, onStarted, onFinished, recoveryGuard)
        check(testBindings.putIfAbsent(jobId, binding) == null) {
            "automation_test_binding_already_installed"
        }
        return AutoCloseable { testBindings.remove(jobId, binding) }
    }

    private fun checkDebugTestApplication(context: Context) {
        check(
            BuildConfig.DEBUG &&
                context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        ) { "automation_test_binding_requires_debug_application" }
    }

    private fun checkTestRecoveryGuard(jobId: Int, guard: AndroidAutomationRecoveryGuard?) {
        if (guard == null) return
        require(guard.executionJobId == jobId && guard.jobId in TEST_JOB_IDS && guard.jobId != jobId) {
            "automation_test_guard_requires_owned_job_ids"
        }
        check(testBindings.values.none { it.recoveryGuard?.jobId == guard.jobId }) {
            "automation_test_guard_id_already_bound"
        }
    }

    fun enqueue(context: Context, trigger: AutomationRuntimeTrigger): Boolean {
        (context.applicationContext as? AndroidAutomationDispatchInterceptor)
            ?.interceptAutomationEnqueue(trigger)?.let { return it }
        if (!HansBackupProcessState.maintenance.isRecoveryReady) return false
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val jobId = executionJobId(trigger)
        val kind = trigger.workKind()
        return runCatching {
            val coordinator = coordinator(context)
            val accepted = coordinator.request(
                jobId = jobId,
                kind = kind,
                observedAt = trigger.observedAtOrNull(),
                schedulePlatformJob = {
                    scheduler.schedule(executionJob(context, trigger)) == JobScheduler.RESULT_SUCCESS
                },
            )
            val recovered = coordinator.recoverPending(
                excludedKinds = setOf(kind),
                jobId = ::executionJobId,
                schedulePlatformJob = { ticket ->
                    scheduler.schedule(executionJob(context, ticket.toRuntimeTrigger())) ==
                        JobScheduler.RESULT_SUCCESS
                },
            )
            accepted && recovered
        }.getOrDefault(false)
    }

    fun enqueueOrRetry(context: Context, trigger: AutomationRuntimeTrigger): Boolean =
        enqueue(context, trigger) || scheduleRetry(context, trigger, failedAttempt = 0)

    fun scheduleAt(
        context: Context,
        earliestAt: Instant,
        deadlineAt: Instant,
        persisted: Boolean,
    ): Boolean {
        if (!HansBackupProcessState.maintenance.isRecoveryReady) return false
        val now = System.currentTimeMillis()
        val earliestDelay = (earliestAt.toEpochMilli() - now).coerceAtLeast(0)
        val deadlineDelay = (deadlineAt.toEpochMilli() - now).coerceAtLeast(earliestDelay)
        val job = JobInfo.Builder(
            WAKEUP_JOB_ID,
            ComponentName(context, HansAutomationWakeupJobService::class.java),
        )
            .setMinimumLatency(earliestDelay)
            .setOverrideDeadline(deadlineDelay)
            .setPersisted(persisted)
            .setExtras(triggerExtras(AutomationRuntimeTrigger.Timer))
            .build()
        return context.getSystemService(JobScheduler::class.java).schedule(job) ==
            JobScheduler.RESULT_SUCCESS
    }

    fun scheduleRetry(
        context: Context,
        trigger: AutomationRuntimeTrigger,
        failedAttempt: Int,
    ): Boolean {
        (context.applicationContext as? AndroidAutomationDispatchInterceptor)
            ?.interceptAutomationRetry(trigger, failedAttempt)?.let { return it }
        if (!HansBackupProcessState.maintenance.isRecoveryReady) return false
        val retry = AndroidAutomationDispatchRetryPolicy.afterFailure(failedAttempt) ?: return false
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            retryAlarmRequestCode(trigger),
            Intent(context, HansAutomationAlarmReceiver::class.java)
                .setAction(ACTION_DISPATCH_RETRY)
                .putExtra(KEY_TRIGGER, triggerName(trigger))
                .putExtra(KEY_RETRY_ATTEMPT, retry.attempt)
                .also { intent ->
                    triggerObservedAt(trigger)?.let {
                        intent.putExtra(KEY_OBSERVED_AT, it.toEpochMilli())
                    }
                },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerAt = SystemClock.elapsedRealtime() + retry.delay.toMillis()
        return runCatching {
            context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent,
            )
        }.isSuccess
    }

    /**
     * Reschedule the same pending work, not a new request/generation. The caller holds the work
     * coordinator lock until the old job has finished. A fresh nonce keeps its delayed stop
     * callback from retiring this replacement. Android may still defer deadlines in Doze or
     * under system restrictions: this is an inexact recovery request, not an exact alarm.
     */
    internal fun scheduleExecutionRetry(
        context: Context,
        jobId: Int,
        ticket: AutomationRuntimeWorkTicket,
        failedAttempt: Int,
    ): Boolean {
        val ownedTestJob = jobId in TEST_JOB_IDS && BuildConfig.DEBUG &&
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            testBindings.containsKey(jobId)
        require(jobId == executionJobId(ticket.kind) || ownedTestJob) {
            "automation_execution_retry_requires_owned_job_id"
        }
        val retry = AndroidAutomationDispatchRetryPolicy.afterFailure(failedAttempt) ?: return false
        val delayMillis = retry.delay.toMillis()
        val job = JobInfo.Builder(jobId, ComponentName(context, HansAutomationJobService::class.java))
            .setMinimumLatency(delayMillis)
            .setOverrideDeadline(delayMillis + EXECUTION_RETRY_WINDOW_MILLIS)
            .setBackoffCriteria(delayMillis, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPersisted(true)
            .setExtras(triggerExtras(ticket.toRuntimeTrigger()).apply {
                putString(KEY_SCHEDULE_NONCE, UUID.randomUUID().toString())
                putInt(KEY_RETRY_ATTEMPT, retry.attempt)
            })
            .build()
        return context.getSystemService(JobScheduler::class.java).schedule(job) ==
            JobScheduler.RESULT_SUCCESS
    }

    fun triggerFrom(extras: PersistableBundle): AutomationRuntimeTrigger {
        val observedAt = extras.getLong(KEY_OBSERVED_AT, 0)
            .takeIf { it > 0 }
            ?.let(Instant::ofEpochMilli)
            ?: Instant.now()
        return triggerFrom(extras.getString(KEY_TRIGGER), observedAt)
    }

    fun triggerFrom(intent: Intent): AutomationRuntimeTrigger {
        val observedAt = intent.getLongExtra(KEY_OBSERVED_AT, 0)
            .takeIf { it > 0 }
            ?.let(Instant::ofEpochMilli)
            ?: Instant.now()
        return triggerFrom(intent.getStringExtra(KEY_TRIGGER), observedAt)
    }

    fun retryAttemptFrom(intent: Intent): Int =
        intent.getIntExtra(KEY_RETRY_ATTEMPT, 0).coerceAtLeast(0)

    fun retryAttemptFrom(extras: PersistableBundle): Int =
        extras.getInt(KEY_RETRY_ATTEMPT, 0).coerceAtLeast(0)

    private fun triggerFrom(name: String?, observedAt: Instant): AutomationRuntimeTrigger =
        when (name) {
            "manual" -> AutomationRuntimeTrigger.ManualRun
            "connectivity" -> AutomationRuntimeTrigger.ConnectivityRestored
            "definition" -> AutomationRuntimeTrigger.DefinitionChanged
            "user_present" -> AutomationRuntimeTrigger.UserPresent
            "boot" -> AutomationRuntimeTrigger.Boot(observedAt)
            "timezone" -> AutomationRuntimeTrigger.TimeZoneChanged(observedAt)
            "clock" -> AutomationRuntimeTrigger.WallClockChanged(observedAt)
            else -> AutomationRuntimeTrigger.Timer
        }

    private fun triggerExtras(trigger: AutomationRuntimeTrigger): PersistableBundle =
        PersistableBundle().apply {
            putString(KEY_TRIGGER, triggerName(trigger))
            triggerObservedAt(trigger)?.let { putLong(KEY_OBSERVED_AT, it.toEpochMilli()) }
        }

    private fun triggerName(trigger: AutomationRuntimeTrigger): String = when (trigger) {
        AutomationRuntimeTrigger.Timer -> "timer"
        AutomationRuntimeTrigger.ManualRun -> "manual"
        AutomationRuntimeTrigger.ConnectivityRestored -> "connectivity"
        AutomationRuntimeTrigger.DefinitionChanged -> "definition"
        AutomationRuntimeTrigger.UserPresent -> "user_present"
        is AutomationRuntimeTrigger.Boot -> "boot"
        is AutomationRuntimeTrigger.TimeZoneChanged -> "timezone"
        is AutomationRuntimeTrigger.WallClockChanged -> "clock"
    }

    private fun triggerObservedAt(trigger: AutomationRuntimeTrigger): Instant? = when (trigger) {
        is AutomationRuntimeTrigger.Boot -> trigger.observedAt
        is AutomationRuntimeTrigger.TimeZoneChanged -> trigger.observedAt
        is AutomationRuntimeTrigger.WallClockChanged -> trigger.observedAt
        else -> null
    }

    private fun retryAlarmRequestCode(trigger: AutomationRuntimeTrigger): Int =
        RETRY_ALARM_REQUEST_CODE_BASE + triggerOrdinal(trigger)

    /** Distinct IDs preserve different reschedule signals while the serialized worker is busy. */
    private fun executionJobId(trigger: AutomationRuntimeTrigger): Int =
        executionJobId(trigger.workKind())

    private fun executionJobId(kind: AutomationRuntimeWorkKind): Int =
        EXECUTION_JOB_ID_BASE + kind.ordinal

    private fun recoveryJobId(kind: AutomationRuntimeWorkKind): Int =
        RECOVERY_JOB_ID_BASE + kind.ordinal

    private fun executionJob(context: Context, trigger: AutomationRuntimeTrigger): JobInfo =
        JobInfo.Builder(
            executionJobId(trigger),
            ComponentName(context, HansAutomationJobService::class.java),
        )
            .setMinimumLatency(0)
            .setOverrideDeadline(0)
            // Explicit replacement gets a new scheduling identity; Android's own retries retain
            // these extras. Receiving start/stop never invents an identity for legacy jobs.
            .setExtras(triggerExtras(trigger).apply { putString(KEY_SCHEDULE_NONCE, UUID.randomUUID().toString()) })
            .build()

    private fun triggerOrdinal(trigger: AutomationRuntimeTrigger): Int = when (trigger) {
        AutomationRuntimeTrigger.Timer -> 0
        AutomationRuntimeTrigger.ManualRun -> 1
        AutomationRuntimeTrigger.ConnectivityRestored -> 2
        AutomationRuntimeTrigger.DefinitionChanged -> 3
        is AutomationRuntimeTrigger.Boot -> 4
        is AutomationRuntimeTrigger.TimeZoneChanged -> 5
        is AutomationRuntimeTrigger.WallClockChanged -> 6
        AutomationRuntimeTrigger.UserPresent -> 7
    }
}

private class AndroidAutomationRuntimeCycleDispatcher(
    private val context: Context,
) : AutomationRuntimeCycleDispatcher {
    override fun dispatch(trigger: AutomationRuntimeTrigger): AutomationAdapterResult {
        if (AndroidAutomationDispatch.enqueue(context, trigger)) {
            return AutomationAdapterResult.Accepted
        }
        return if (AndroidAutomationDispatch.scheduleRetry(context, trigger, failedAttempt = 0)) {
            AutomationAdapterResult.Accepted
        } else {
            AutomationAdapterResult.Rejected("job_scheduler_rejected")
        }
    }
}

class AndroidAutomationPlatformStateSource(
    private val context: Context,
) : AutomationPlatformStateSource {
    override fun now(): Instant = Instant.now()

    override fun systemZone(): ZoneId = ZoneId.systemDefault()

    override fun bootSessionId(): AutomationBootSessionId {
        val bootCount = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrNull()
        if (bootCount != null && bootCount >= 0) {
            return AutomationBootSessionId("android-boot-$bootCount")
        }
        // Fail-safe fallback for devices that hide BOOT_COUNT. A wall-clock correction can cause
        // an unnecessary lease recovery, but never makes an old lease look current after reboot.
        val bootEpochMinute = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60_000
        return AutomationBootSessionId("android-boot-epoch-$bootEpochMinute")
    }
}

object AndroidAutomationRuntimeFactory {
    fun create(
        context: Context,
        backgroundExecutor: Executor,
        liveEnvironment: AutomationLiveEnvironmentSource,
        codexGateway: AutomationCodexGateway,
        storage: AutomationStorage = PersistentAutomationStorage(
            SQLiteAutomationSnapshotPersistence(context.applicationContext),
        ),
        backupMaintenance: HansBackupMaintenance = HansBackupProcessState.maintenance,
    ): AutomationRuntimeOwner {
        val appContext = context.applicationContext
        return AutomationRuntimeOwner(
            storage = storage,
            wakeupAdapter = AndroidAutomationWakeupAdapter(appContext),
            cycleDispatcher = AndroidAutomationRuntimeCycleDispatcher(appContext),
            backgroundExecutor = backgroundExecutor,
            platformState = AndroidAutomationPlatformStateSource(appContext),
            liveEnvironment = liveEnvironment,
            codexExecutor = GatewayCodexAutomationExecutor(codexGateway),
            backupMaintenance = backupMaintenance,
        ).also {
            AndroidAutomationUserPresentMonitor.ensureRegistered(appContext)
        }
    }
}
