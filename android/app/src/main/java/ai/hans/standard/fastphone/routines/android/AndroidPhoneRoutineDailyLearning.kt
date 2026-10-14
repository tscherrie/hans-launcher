package ai.hans.standard.fastphone.routines.android

import ai.hans.standard.backup.HansBackupMaintenance
import ai.hans.standard.backup.HansBackupProcessState
import ai.hans.standard.notifications.NotificationTriageIntegration
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.UserManager
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Core adapter supplied by the Application; no model, credentials or history lives here. */
interface PhoneRoutineDailyWork {
    fun snapshot(): PhoneRoutineDailyWorkState
    /** Check [cancelled] before claiming, before inference and immediately before committing. */
    fun run(cancelled: () -> Boolean)
    /** Fast, non-blocking interruption of an active read-only model session. */
    fun cancelActive()
}

internal interface PhoneRoutineDailyPlatform {
    fun environment(): PhoneRoutineDailyEnvironment
    fun schedule(wakeAtMillis: Long): Boolean
    fun cancel()
}

/**
 * One inexact platform-owned job and event-driven opportunities, never an idle timer or a
 * foreground service. The core owns durable once-daily claims, bounded retries and account binding.
 */
class AndroidPhoneRoutineDailyLearningRuntime internal constructor(
    private val work: PhoneRoutineDailyWork,
    private val platform: PhoneRoutineDailyPlatform,
    private val worker: Executor,
    private val completionExecutor: Executor,
    private val maintenance: HansBackupMaintenance,
    private val nowMillis: () -> Long,
    private val closeWorker: () -> Unit = {},
    private val attachActivityObserver: ((() -> Unit) -> AutoCloseable)? = null,
) : AutoCloseable {
    constructor(context: Context, work: PhoneRoutineDailyWork) : this(
        work = work,
        platform = AndroidPhoneRoutineDailyPlatform(context.applicationContext),
        ownedWorker = newWorker(),
        maintenance = HansBackupProcessState.maintenance,
    )

    private constructor(
        work: PhoneRoutineDailyWork,
        platform: PhoneRoutineDailyPlatform,
        ownedWorker: ThreadPoolExecutor,
        maintenance: HansBackupMaintenance,
    ) : this(
        work = work,
        platform = platform,
        worker = ownedWorker,
        completionExecutor = Executor { command ->
            check(Handler(Looper.getMainLooper()).post(command)) { "daily_learning_main_loop_unavailable" }
        },
        maintenance = maintenance,
        nowMillis = System::currentTimeMillis,
        closeWorker = { ownedWorker.shutdownNow() },
        attachActivityObserver = NotificationTriageIntegration::attachInteractiveActivityChange,
    )

    private val closed = AtomicBoolean(false)
    private val reconcileQueued = AtomicBoolean(false)
    private val active = AtomicReference<Execution?>(null)
    private val transientNotBeforeMillis = AtomicLong(0L)
    private val observers = CopyOnWriteArraySet<(PhoneRoutineDailySchedulingSnapshot) -> Unit>()
    private val current = AtomicReference(PhoneRoutineDailySchedulingSnapshot(
        PhoneRoutineDailyScheduleStatus.DISABLED, false, null))
    private var activityRegistration: AutoCloseable? = null

    fun snapshot(): PhoneRoutineDailySchedulingSnapshot = current.get()

    fun subscribe(observer: (PhoneRoutineDailySchedulingSnapshot) -> Unit): AutoCloseable {
        observers.add(observer)
        runCatching { observer(snapshot()) }
        return AutoCloseable { observers.remove(observer) }
    }

    internal fun start() {
        if (closed.get()) return
        activityRegistration = attachActivityObserver?.invoke(::onInteractiveActivityChanged)
        reconcile()
    }

    /** A changed opt-in, account, key availability or forget operation must call this boundary. */
    fun authorityChanged() {
        cancelCurrent()
        transientNotBeforeMillis.set(0L)
        reconcile()
    }

    /** Coalesced and off-main; activity does not itself make a model request. */
    fun reconcile() {
        if (closed.get() || !reconcileQueued.compareAndSet(false, true)) return
        try {
            worker.execute {
                reconcileQueued.set(false)
                if (!closed.get()) reconcileNow()
            }
        } catch (_: RuntimeException) {
            reconcileQueued.set(false)
            if (!closed.get()) publish(PhoneRoutineDailySchedulingSnapshot(
                PhoneRoutineDailyScheduleStatus.SCHEDULING_FAILED, false, null))
        }
    }

    private fun onInteractiveActivityChanged() {
        if (!safeEnvironment().interactiveIdle) cancelCurrent()
        reconcile()
    }

    private fun reconcileNow() {
        val state = runCatching(work::snapshot).getOrNull()
        if (state == null) {
            cancelCurrent()
            runCatching(platform::cancel)
            publish(PhoneRoutineDailySchedulingSnapshot(PhoneRoutineDailyScheduleStatus.SCHEDULING_FAILED, false, null))
            return
        }
        val running = active.get()
        if (running != null && !running.cancelled.get()) {
            if ((running.generation >= 0 && running.generation != state.generation) || !state.enabled || !state.authenticationReady ||
                !safeEnvironment().let { it.backupReady && it.userStorageUnlocked && it.interactiveIdle && it.defaultNetworkValidated }) {
                cancelCurrent()
            } else {
                publish(PhoneRoutineDailySchedulingSnapshot(PhoneRoutineDailyScheduleStatus.RUNNING, false, null))
                return
            }
        }
        // Never replace a still-running JobScheduler ID: Android would stop that delivery.
        if (active.get() != null) return
        val eligible = state.nextEligibleAtMillis?.let { maxOf(it, transientNotBeforeMillis.get()) }
        val plan = PhoneRoutineDailyJobPolicy.plan(state.copy(nextEligibleAtMillis = eligible), safeEnvironment(), nowMillis())
        if (plan.wakeAtMillis == null) {
            runCatching(platform::cancel)
            publish(PhoneRoutineDailySchedulingSnapshot(plan.status, false, null))
        } else {
            val accepted = runCatching { platform.schedule(plan.wakeAtMillis) }.getOrDefault(false)
            publish(PhoneRoutineDailySchedulingSnapshot(
                if (accepted) plan.status else PhoneRoutineDailyScheduleStatus.SCHEDULING_FAILED,
                accepted, if (accepted) plan.wakeAtMillis else null))
        }
    }

    /** JobService only. A returned registration is the sole owner of this Android delivery. */
    internal fun beginJob(assignedNetworkValidated: Boolean, finished: (Boolean) -> Unit): Execution? {
        if (closed.get()) return null
        val execution = Execution(assignedNetworkValidated, finished)
        if (!active.compareAndSet(null, execution)) return null
        publish(PhoneRoutineDailySchedulingSnapshot(PhoneRoutineDailyScheduleStatus.RUNNING, false, null))
        try {
            worker.execute { perform(execution) }
        } catch (_: RuntimeException) {
            execution.cancelled.set(true)
            finish(execution, true)
        }
        return execution
    }

    private fun perform(execution: Execution) {
        var reservation: AutoCloseable? = null
        try {
            if (closed.get() || execution.cancelled.get()) return
            val state = work.snapshot()
            execution.generation = state.generation
            if (!PhoneRoutineDailyJobPolicy.mayRun(state, safeEnvironment(),
                    execution.assignedNetworkValidated, nowMillis())) return
            reservation = maintenance.reserveAutomationActivity()
            val cancelled = {
                closed.get() || execution.cancelled.get() || active.get() !== execution ||
                    runCatching {
                        val fresh = work.snapshot()
                        val environment = safeEnvironment()
                        !fresh.enabled || !fresh.authenticationReady || fresh.generation != execution.generation ||
                            !environment.backupReady || !environment.userStorageUnlocked ||
                            !environment.interactiveIdle || !environment.defaultNetworkValidated
                    }.getOrDefault(true)
            }
            if (!cancelled()) work.run(cancelled)
        } catch (_: Exception) {
            // The core retains bounded retry/checkpoint state; private failures are not logged.
        } finally {
            runCatching { reservation?.close() }
            // A missing assigned network, adapter exception or pre-claim cancellation must not
            // cause a zero-delay delivery loop. Durable model-attempt limits remain core-owned.
            val now = nowMillis()
            val deferUntil = if (now > Long.MAX_VALUE - PhoneRoutineDailyJobPolicy.DEFER_MILLIS)
                Long.MAX_VALUE else now + PhoneRoutineDailyJobPolicy.DEFER_MILLIS
            transientNotBeforeMillis.set(deferUntil)
            finish(execution, false)
        }
    }

    private fun finish(execution: Execution, retryDelivery: Boolean) {
        if (!execution.completionEnqueued.compareAndSet(false, true)) return
        try {
            completionExecutor.execute {
                if (!active.compareAndSet(execution, null)) return@execute
                if (!execution.stopped.get() && execution.finishedOnce.compareAndSet(false, true)) {
                    // A disappearing system delivery must not crash the Application's looper.
                    runCatching { execution.finished(retryDelivery) }
                }
                if (!closed.get()) reconcile()
            }
        } catch (_: RuntimeException) {
            // Keep the registration alive: Android recovers a process whose looper disappears.
        }
    }

    /** Android already owns completion after onStopJob; never call jobFinished for this delivery. */
    internal fun stopJob(execution: Execution): Boolean {
        execution.stopped.set(true)
        if (execution.cancelled.compareAndSet(false, true) && active.get() === execution) {
            runCatching(work::cancelActive)
        }
        // Framework retry is only a liveness backstop. The core decides durable eligibility and
        // limits paid attempts; disabled/forgotten generations never pass the worker's gate.
        return !closed.get()
    }

    private fun cancelCurrent() {
        val execution = active.get() ?: return
        if (execution.cancelled.compareAndSet(false, true)) runCatching(work::cancelActive)
    }

    private fun safeEnvironment(): PhoneRoutineDailyEnvironment =
        runCatching(platform::environment).getOrDefault(PhoneRoutineDailyEnvironment(false, false, false, false))
            .let { it.copy(backupReady = it.backupReady && maintenance.isRecoveryReady) }

    private fun publish(snapshot: PhoneRoutineDailySchedulingSnapshot) {
        current.set(snapshot)
        observers.forEach { observer -> runCatching { observer(snapshot) } }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { activityRegistration?.close() }
        cancelCurrent()
        runCatching(platform::cancel)
        closeWorker()
        publish(PhoneRoutineDailySchedulingSnapshot(PhoneRoutineDailyScheduleStatus.CLOSED, false, null))
        observers.clear()
    }

    internal class Execution(
        val assignedNetworkValidated: Boolean,
        val finished: (Boolean) -> Unit,
    ) {
        @Volatile var generation = -1L
        val cancelled = AtomicBoolean(false)
        val stopped = AtomicBoolean(false)
        val completionEnqueued = AtomicBoolean(false)
        val finishedOnce = AtomicBoolean(false)
    }

    private companion object {
        fun newWorker() = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(8), { runnable -> Thread(runnable, "hans-daily-phone-learning").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy())
    }
}

/** Passive process registry; unlock events do not create the main Hans host or an analysis client. */
object AndroidPhoneRoutineDailyLearning {
    private val runtime = AtomicReference<AndroidPhoneRoutineDailyLearningRuntime?>(null)

    fun install(context: Context, work: PhoneRoutineDailyWork): AndroidPhoneRoutineDailyLearningRuntime {
        val created = AndroidPhoneRoutineDailyLearningRuntime(context, work)
        runtime.getAndSet(created)?.close()
        created.start()
        return created
    }

    @Suppress("UNUSED_PARAMETER")
    fun onActivity(context: Context) {
        // Deliberately touch neither Context services nor lazy Application fields here.
        runtime.get()?.reconcile()
    }

    internal fun current(): AndroidPhoneRoutineDailyLearningRuntime? = runtime.get()

    fun uninstall(expected: AndroidPhoneRoutineDailyLearningRuntime) {
        if (runtime.compareAndSet(expected, null)) expected.close()
    }
}

internal class AndroidPhoneRoutineDailyPlatform(private val context: Context) : PhoneRoutineDailyPlatform {
    override fun environment() = PhoneRoutineDailyEnvironment(
        backupReady = HansBackupProcessState.maintenance.isRecoveryReady,
        userStorageUnlocked = context.getSystemService(UserManager::class.java)?.isUserUnlocked == true,
        interactiveIdle = NotificationTriageIntegration.isInteractiveIdle(),
        defaultNetworkValidated = validated(context,
            context.getSystemService(ConnectivityManager::class.java)?.activeNetwork),
    )

    override fun schedule(wakeAtMillis: Long): Boolean {
        val delay = (wakeAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        val existing = scheduler.getPendingJob(JOB_ID)
        if (existing?.service == ComponentName(context, HansPhoneRoutineDailyLearningJobService::class.java) &&
            existing.extras.getLong(KEY_WAKE_AT, -1L) == wakeAtMillis) return true
        return scheduler.schedule(platformJob(context, delay, wakeAtMillis)) == JobScheduler.RESULT_SUCCESS
    }

    override fun cancel() { context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) }

    companion object {
        const val JOB_ID = 0x48414C31 // "HAL1", distinct from existing automation jobs.
        const val KEY_WAKE_AT = "daily_learning_wake_at"

        internal fun platformJob(context: Context, delayMillis: Long, wakeAtMillis: Long,
            jobId: Int = JOB_ID): JobInfo = JobInfo.Builder(jobId,
                ComponentName(context, HansPhoneRoutineDailyLearningJobService::class.java))
            .setMinimumLatency(delayMillis.coerceAtLeast(0))
            .setRequiresBatteryNotLow(true)
            .setRequiredNetwork(NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED).build())
            .setPersisted(true)
            .setBackoffCriteria(PhoneRoutineDailyJobPolicy.DEFER_MILLIS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            // No override deadline: deadlines could override network/battery constraints.
            .setExtras(PersistableBundle().apply { putLong(KEY_WAKE_AT, wakeAtMillis) }).build()

        internal fun validated(context: Context, network: Network?): Boolean {
            val capabilities = network?.let {
                context.getSystemService(ConnectivityManager::class.java)?.getNetworkCapabilities(it)
            } ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }
}

/** Public Android lifecycle owner. No long work runs on the main looper. */
class HansPhoneRoutineDailyLearningJobService : JobService() {
    private data class Active(val runtime: AndroidPhoneRoutineDailyLearningRuntime,
        val execution: AndroidPhoneRoutineDailyLearningRuntime.Execution)
    private val active = java.util.concurrent.ConcurrentHashMap<Int, Active>()

    override fun onStartJob(params: JobParameters): Boolean {
        if (params.jobId != AndroidPhoneRoutineDailyPlatform.JOB_ID) return false
        val runtime = AndroidPhoneRoutineDailyLearning.current() ?: return false
        lateinit var registration: Active
        val execution = runtime.beginJob(
            AndroidPhoneRoutineDailyPlatform.validated(this, params.network),
        ) { retry ->
            active.remove(params.jobId, registration)
            jobFinished(params, retry)
        } ?: return false
        registration = Active(runtime, execution)
        active[params.jobId] = registration
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val current = active.remove(params.jobId) ?: return false
        return current.runtime.stopJob(current.execution)
    }
}
