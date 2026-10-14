package ai.hans.standard.fastphone.routines

import ai.hans.standard.codex.CodexInput
import ai.hans.standard.codex.UntrustedTurnContext
import ai.hans.standard.fastphone.routines.android.AndroidPhoneRoutineDailyLearning
import ai.hans.standard.fastphone.routines.android.AndroidPhoneRoutineDailyLearningRuntime
import ai.hans.standard.fastphone.routines.android.PhoneRoutineDailySchedulingSnapshot
import ai.hans.standard.fastphone.routines.android.PhoneRoutineDailyScheduleStatus
import ai.hans.standard.fastphone.routines.android.PhoneRoutineDailyWork
import ai.hans.standard.fastphone.routines.android.PhoneRoutineDailyWorkState
import ai.hans.standard.notifications.CodexAppServerPhoneRoutineAnalysisRunner
import ai.hans.standard.settings.fastphone.FastPhoneUseSettingsStore
import android.content.Context
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One application-owned store; neither a launcher Activity nor the personal chat owns a job. */
class PhoneRoutineLearningIntegration(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val runner = CodexAppServerPhoneRoutineAnalysisRunner(appContext)
    private val store = DailyPhoneRoutineLearningStore(appContext,
        PhoneRoutineLearningStore(appContext), runner::currentAccountFingerprint)
    private val coordinator = DailyPhoneRoutineLearningCoordinator(store, runner)
    private val fastPhoneUse = FastPhoneUseSettingsStore(appContext)
    private val serial = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(64), { command -> Thread(command, "hans-phone-learning-state").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())
    private val observers = CopyOnWriteArraySet<() -> Unit>()
    private val stateLock = Any()
    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val authority = PhoneRoutineAuthorityGate()
    @Volatile private var currentState = DailyPhoneRoutineLearningSnapshot(false,
        DailyPhoneRoutineLearningStatus.DISABLED, null, 0, null, null, 0)
    @Volatile private var dailyRuntime: AndroidPhoneRoutineDailyLearningRuntime? = null
    @Volatile private var lastAuthorityRevision = -1L
    private var stateSubscription: AutoCloseable? = null
    private var scheduleSubscription: AutoCloseable? = null
    private var fastPhoneSubscription: AutoCloseable? = null

    fun start() {
        if (closed.get()) return
        if (!started.compareAndSet(false, true)) { reconcile(); return }
        val runtime = AndroidPhoneRoutineDailyLearning.install(appContext, object : PhoneRoutineDailyWork {
            override fun snapshot(): PhoneRoutineDailyWorkState {
                val state = refreshState()
                return PhoneRoutineDailyWorkState(
                    enabled = state.enabled && !authority.blocked && fastPhoneUse.snapshot().requestedEnabled,
                    generation = state.revision,
                    nextEligibleAtMillis = state.nextEligibleAtMillis,
                    authenticationReady = state.status != DailyPhoneRoutineLearningStatus.ACCOUNT_UNAVAILABLE,
                )
            }
            override fun run(cancelled: () -> Boolean) {
                coordinator.runOnce { !cancelled() }
            }
            override fun cancelActive() = coordinator.preemptCurrent()
        })
        if (closed.get()) { AndroidPhoneRoutineDailyLearning.uninstall(runtime); return }
        dailyRuntime = runtime
        stateSubscription = store.subscribe(::acceptState)
        scheduleSubscription = runtime.subscribe { notifyObservers() }
        fastPhoneSubscription = fastPhoneUse.subscribe { state ->
            if (!state.requestedEnabled) coordinator.preemptCurrent()
            runtime.reconcile()
        }
    }

    /** UI reads an immutable cache, never authentication files or the history file. */
    fun snapshot(): DailyPhoneRoutineLearningSnapshot = currentState.let { state ->
        if (!authority.blocked) state else state.copy(enabled = false,
            status = DailyPhoneRoutineLearningStatus.ERROR, errorCode = "authority_change_unconfirmed")
    }
    fun schedulingSnapshot(): PhoneRoutineDailySchedulingSnapshot = dailyRuntime?.snapshot()
        ?: PhoneRoutineDailySchedulingSnapshot(PhoneRoutineDailyScheduleStatus.DISABLED, false, null)

    fun subscribe(observer: () -> Unit): AutoCloseable {
        observers.add(observer)
        observer()
        return AutoCloseable { observers.remove(observer) }
    }

    fun reconcile() { dailyRuntime?.reconcile() }

    fun setEnabled(enabled: Boolean, completion: (Boolean) -> Unit = {}) {
        changeAuthority(completion) { store.setEnabled(enabled) }
    }

    fun forget(completion: (Boolean) -> Unit) {
        changeAuthority(completion, store::forget)
    }

    fun observeCompleted(observation: RoutineObservation) {
        enqueue({}) {
            if (!authority.blocked && fastPhoneUse.snapshot().requestedEnabled) store.observeCompleted(observation)
            true
        }
    }

    /** Data-only native turn context, sampled again after any delayed model boundary. */
    fun context(input: List<CodexInput>): UntrustedTurnContext? {
        val text = input.filterIsInstance<CodexInput.Text>().joinToString("\n") { it.text }
        if (text.isBlank()) return null
        val epoch = authority.capture()
        val data = runCatching {
            if (epoch != null && fastPhoneUse.snapshot().requestedEnabled) {
                val before = refreshState()
                val quoted = store.promptData(currentUserText = text)
                val after = refreshState()
                if (before.revision == after.revision && authority.permits(epoch)) quoted else INACTIVE_CONTEXT
            } else INACTIVE_CONTEXT
        }.getOrDefault(INACTIVE_CONTEXT)
        return UntrustedTurnContext("hans_phone_routine_hints",
            if (authority.permits(epoch)) data else INACTIVE_CONTEXT)
    }

    private fun changeAuthority(completion: (Boolean) -> Unit, action: () -> Boolean) {
        val epoch = authority.beginChange()
        coordinator.preemptCurrent()
        dailyRuntime?.authorityChanged()
        notifyObservers()
        enqueue(completion) {
            val success = runCatching(action).getOrDefault(false)
            authority.finishChange(epoch, success)
            // Re-open scheduling only after the last requested authority change is durable.
            dailyRuntime?.authorityChanged()
            notifyObservers()
            success
        }
    }

    private fun enqueue(completion: (Boolean) -> Unit, action: () -> Boolean) {
        try {
            serial.execute {
                val success = runCatching(action).getOrDefault(false)
                dailyRuntime?.reconcile()
                runCatching { completion(success) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { completion(false) }
    }

    private fun notifyObservers() { observers.forEach { runCatching(it) } }

    private fun refreshState(): DailyPhoneRoutineLearningSnapshot = store.snapshot().also(::acceptState)

    private fun acceptState(state: DailyPhoneRoutineLearningSnapshot) {
        if (closed.get()) return
        val authorityChanged = synchronized(stateLock) {
            if (currentState == state || state.revision < currentState.revision) return
            currentState = state
            (state.revision != lastAuthorityRevision).also { lastAuthorityRevision = state.revision }
        }
        // Foreign cancellation and observer callbacks must never run under our cache monitor.
        if (authorityChanged) dailyRuntime?.authorityChanged() else dailyRuntime?.reconcile()
        notifyObservers()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        fastPhoneSubscription?.close()
        stateSubscription?.close()
        scheduleSubscription?.close()
        dailyRuntime?.let(AndroidPhoneRoutineDailyLearning::uninstall)
        dailyRuntime = null
        coordinator.close()
        serial.shutdownNow()
        observers.clear()
    }

    private companion object {
        const val INACTIVE_CONTEXT = "{\"version\":1,\"active\":false,\"candidates\":[]}"
    }
}
