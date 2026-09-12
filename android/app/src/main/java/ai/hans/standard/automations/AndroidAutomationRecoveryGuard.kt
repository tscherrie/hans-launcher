package ai.hans.standard.automations

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import java.time.Duration
import java.util.UUID

/** A content-free, app-owned scheduling identity; it is never permission to execute a run. */
internal data class AutomationRecoveryGuardSchedule(
    val jobId: Int,
    val executionJobId: Int,
    val kind: AutomationRuntimeWorkKind,
    val generation: Long,
    val nonce: String,
) {
    init {
        require(jobId > 0 && executionJobId > 0 && jobId != executionJobId)
        require(generation >= 1)
        require(runCatching { UUID.fromString(nonce).toString() == nonce }.getOrDefault(false))
    }
}

internal sealed interface AutomationRecoveryGuardPending {
    data object Missing : AutomationRecoveryGuardPending
    data object Foreign : AutomationRecoveryGuardPending
    data class Owned(val schedule: AutomationRecoveryGuardSchedule) : AutomationRecoveryGuardPending
}

internal interface AutomationRecoveryGuardBackend {
    fun pending(): AutomationRecoveryGuardPending
    fun schedule(schedule: AutomationRecoveryGuardSchedule): Boolean
    fun cancel()
}

/**
 * One persisted backstop per work kind, independent of both execution and ordinary alarm IDs.
 * It is installed before every cycle, including queued cycles, and cancelled only while the
 * work coordinator holds its current-registration/caught-up lock. While work is active, a
 * delivered guard installs its successor before returning. It never executes an automation,
 * changes a lease, invents a generation or relaxes the external-dispatch fence.
 *
 * Android may defer even this deadline under Doze, quota or system-health restrictions. These
 * are bounded inexact scheduling requests, not exact-alarm promises or an idle polling loop.
 */
internal class AndroidAutomationRecoveryGuard(
    val jobId: Int,
    val executionJobId: Int,
    val kind: AutomationRuntimeWorkKind,
    private val backend: AutomationRecoveryGuardBackend,
    private val nonceSource: () -> String = { UUID.randomUUID().toString() },
    private val observer: (String, AutomationRecoveryGuardSchedule) -> Unit = { _, _ -> },
) {
    constructor(
        context: Context,
        jobId: Int,
        executionJobId: Int,
        kind: AutomationRuntimeWorkKind,
        observer: (String, AutomationRecoveryGuardSchedule) -> Unit = { _, _ -> },
    ) : this(
        jobId,
        executionJobId,
        kind,
        AndroidRecoveryGuardBackend(context.applicationContext, jobId),
        observer = observer,
    )

    init {
        require(jobId > 0 && executionJobId > 0 && jobId != executionJobId)
    }

    /** Caller holds the coordinator lock; a failed replacement must preserve the old wakeup. */
    fun arm(ticket: AutomationRuntimeWorkTicket): Boolean = runCatching {
        if (ticket.kind != kind) return@runCatching false
        when (val pending = backend.pending()) {
            AutomationRecoveryGuardPending.Missing -> Unit
            AutomationRecoveryGuardPending.Foreign -> return@runCatching false
            is AutomationRecoveryGuardPending.Owned -> if (!owns(pending.schedule)) return@runCatching false
        }
        val schedule = AutomationRecoveryGuardSchedule(jobId, executionJobId, kind, ticket.generation, nonceSource())
        val accepted = backend.schedule(schedule)
        if (accepted) observe("scheduled", schedule)
        accepted
    }.getOrDefault(false)

    /** Called only inside finishIfCaughtUp, never by a stopped or superseded worker. */
    fun cancelCaughtUp() {
        runCatching {
            val pending = backend.pending() as? AutomationRecoveryGuardPending.Owned ?: return@runCatching
            if (owns(pending.schedule)) {
                backend.cancel()
                observe("cancelled", pending.schedule)
            }
        }
    }

    fun receive(
        delivered: AutomationRecoveryGuardSchedule,
        coordinator: AutomationRuntimeWorkCoordinator,
        scheduleExecution: (AutomationRuntimeWorkTicket) -> Boolean,
    ): AutomationRuntimeWorkCoordinator.RecoveryGuardResult {
        if (!owns(delivered)) return AutomationRuntimeWorkCoordinator.RecoveryGuardResult.STALE
        return runCatching {
            coordinator.recoverFromGuard(
                jobId = executionJobId,
                kind = kind,
                generation = delivered.generation,
                isCurrentSchedule = {
                    val pending = backend.pending() as? AutomationRecoveryGuardPending.Owned
                    (pending?.schedule == delivered).also { current ->
                        if (current) observe("received", delivered)
                    }
                },
                rearmGuard = ::arm,
                schedulePlatformJob = scheduleExecution,
            )
        }.getOrDefault(AutomationRuntimeWorkCoordinator.RecoveryGuardResult.REARM_REJECTED)
    }

    private fun owns(schedule: AutomationRecoveryGuardSchedule): Boolean =
        schedule.jobId == jobId && schedule.executionJobId == executionJobId && schedule.kind == kind

    private fun observe(event: String, schedule: AutomationRecoveryGuardSchedule) {
        // Observability cannot turn an accepted platform handoff into a retry or erase it.
        runCatching { observer(event, schedule) }
    }

    companion object {
        val CHECK_AFTER: Duration = Duration.ofMinutes(2)
        val INEXACT_WINDOW: Duration = Duration.ofSeconds(30)
        private const val SCHEMA = 1
        private const val KEY_SCHEMA = "recovery_schema"
        private const val KEY_EXECUTION_ID = "recovery_execution_id"
        private const val KEY_KIND = "recovery_kind"
        private const val KEY_GENERATION = "recovery_generation"
        private const val KEY_NONCE = "recovery_nonce"

        fun readDelivery(params: JobParameters): AutomationRecoveryGuardSchedule? =
            decode(params.jobId, params.extras)

        internal fun platformJob(context: Context, schedule: AutomationRecoveryGuardSchedule): JobInfo {
            val delay = CHECK_AFTER.toMillis()
            return JobInfo.Builder(schedule.jobId, ComponentName(context, HansAutomationWakeupJobService::class.java))
                .setMinimumLatency(delay)
                .setOverrideDeadline(delay + INEXACT_WINDOW.toMillis())
                .setPersisted(true)
                .setExtras(extras(schedule))
                .build()
        }

        internal fun decode(jobId: Int, extras: PersistableBundle): AutomationRecoveryGuardSchedule? =
            runCatching {
                if (extras.getInt(KEY_SCHEMA, 0) != SCHEMA) return@runCatching null
                AutomationRecoveryGuardSchedule(
                    jobId,
                    extras.getInt(KEY_EXECUTION_ID, 0),
                    AutomationRuntimeWorkKind.valueOf(checkNotNull(extras.getString(KEY_KIND))),
                    extras.getLong(KEY_GENERATION, 0),
                    checkNotNull(extras.getString(KEY_NONCE)),
                )
            }.getOrNull()

        internal fun extras(schedule: AutomationRecoveryGuardSchedule): PersistableBundle =
            PersistableBundle().apply {
                putInt(KEY_SCHEMA, SCHEMA)
                putInt(KEY_EXECUTION_ID, schedule.executionJobId)
                putString(KEY_KIND, schedule.kind.name)
                putLong(KEY_GENERATION, schedule.generation)
                putString(KEY_NONCE, schedule.nonce)
            }
    }
}

private class AndroidRecoveryGuardBackend(
    private val context: Context,
    private val jobId: Int,
) : AutomationRecoveryGuardBackend {
    private val scheduler = context.getSystemService(JobScheduler::class.java)
    private val component = ComponentName(context, HansAutomationWakeupJobService::class.java)

    override fun pending(): AutomationRecoveryGuardPending {
        val pending = scheduler.getPendingJob(jobId) ?: return AutomationRecoveryGuardPending.Missing
        if (pending.service != component || !pending.isPersisted) return AutomationRecoveryGuardPending.Foreign
        val schedule = AndroidAutomationRecoveryGuard.decode(pending.id, pending.extras)
            ?: return AutomationRecoveryGuardPending.Foreign
        return AutomationRecoveryGuardPending.Owned(schedule)
    }

    override fun schedule(schedule: AutomationRecoveryGuardSchedule): Boolean {
        require(schedule.jobId == jobId)
        return scheduler.schedule(AndroidAutomationRecoveryGuard.platformJob(context, schedule)) == JobScheduler.RESULT_SUCCESS
    }

    override fun cancel() {
        scheduler.cancel(jobId)
    }
}
