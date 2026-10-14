package ai.hans.standard.fastphone.routines

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** One bounded text-only daily run; scheduling, foreground gating and model transport are injected. */
class DailyPhoneRoutineLearningCoordinator(
    private val store: DailyPhoneRoutineLearningStore,
    private val runner: PhoneRoutineAnalysisRunner,
) : AutoCloseable {
    private val running = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val cancellation = AtomicLong()
    private val active = AtomicReference<DailyPhoneRoutineClaim?>()
    private val admissionLock = Any()
    private var runnerStarted = false
    private val subscription = store.subscribe { snapshot ->
        val claim = active.get()
        if (claim != null && (!snapshot.enabled || snapshot.revision != claim.revision)) preemptCurrent()
    }

    fun runOnce(shouldContinue: () -> Boolean = { true }): DailyPhoneRoutineRunResult {
        if (closed.get() || !continues(shouldContinue) || !running.compareAndSet(false, true)) return DailyPhoneRoutineRunResult.SKIPPED
        val epoch = cancellation.get()
        var claim: DailyPhoneRoutineClaim? = null
        fun permitted(): Boolean = !closed.get() && cancellation.get() == epoch && continues(shouldContinue)
        return try {
            if (!permitted()) return DailyPhoneRoutineRunResult.PREEMPTED
            val acquired = store.claim() ?: return DailyPhoneRoutineRunResult.SKIPPED
            claim = acquired
            active.set(acquired)
            if (!permitted() || !authorityMatches(acquired)) {
                store.fail(acquired, "preempted"); return DailyPhoneRoutineRunResult.PREEMPTED
            }
            val admitted = synchronized(admissionLock) {
                if (!permitted() || !authorityMatches(acquired)) false else { runnerStarted = true; true }
            }
            if (!admitted) { store.fail(acquired, "preempted"); return DailyPhoneRoutineRunResult.PREEMPTED }
            val result = try { runner.run(acquired.input.inputJson, acquired.expectedAccountFingerprint) }
                finally { synchronized(admissionLock) {
                    runnerStarted = false
                    runCatching { runner.acknowledgeCompletedRun() }
                } }
            if (!permitted() || !authorityMatches(acquired)) {
                store.fail(acquired, "preempted"); DailyPhoneRoutineRunResult.PREEMPTED
            } else {
                if (store.complete(acquired, result,
                        admitPublication = { commit -> synchronized(admissionLock) { commit() } },
                        shouldPublish = { permitted() && authorityMatches(acquired) })) DailyPhoneRoutineRunResult.COMPLETED
                else if (!permitted()) DailyPhoneRoutineRunResult.PREEMPTED else DailyPhoneRoutineRunResult.FAILED
            }
        } catch (_: Exception) {
            claim?.let { store.fail(it, if (permitted()) "analysis_failed" else "preempted") }
            if (permitted()) DailyPhoneRoutineRunResult.FAILED else DailyPhoneRoutineRunResult.PREEMPTED
        } finally { synchronized(admissionLock) { runnerStarted = false }; active.set(null); running.set(false) }
    }
    private fun authorityMatches(claim: DailyPhoneRoutineClaim): Boolean {
        val snapshot = store.snapshot()
        return snapshot.enabled && snapshot.revision == claim.revision &&
            runner.currentAccountFingerprint() == claim.expectedAccountFingerprint
    }

    fun preemptCurrent() = synchronized(admissionLock) {
        cancellation.incrementAndGet()
        // An idle preemption must not poison the restricted runner's next operation.
        if (runnerStarted) runCatching { runner.preemptCurrent() }
        Unit
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        preemptCurrent(); subscription.close(); runCatching { runner.close() }
    }
    private fun continues(predicate: () -> Boolean) = runCatching { predicate() }.getOrDefault(false)
}
