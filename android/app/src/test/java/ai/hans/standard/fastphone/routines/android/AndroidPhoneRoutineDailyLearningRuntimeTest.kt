package ai.hans.standard.fastphone.routines.android

import ai.hans.standard.backup.HansBackupMaintenance
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class AndroidPhoneRoutineDailyLearningRuntimeTest {
    @Test fun startupReconciliationCoalescesAndPublishesOnlyAcceptedSchedule() {
        val f = Fixture()
        repeat(30) { f.runtime.reconcile() }
        assertEquals(1, f.worker.size)
        f.worker.drain()
        assertEquals(1, f.platform.schedules.size)
        assertTrue(f.runtime.snapshot().scheduled)
        f.platform.accept = false
        f.runtime.reconcile(); f.worker.drain()
        assertEquals(PhoneRoutineDailyScheduleStatus.SCHEDULING_FAILED, f.runtime.snapshot().status)
        assertFalse(f.runtime.snapshot().scheduled)
        assertNull(f.runtime.snapshot().nextWakeAtMillis)
    }

    @Test fun dueRunChecksAdmissionOnWorkerThenFinishesExactlyOnce() {
        val f = Fixture()
        f.work.action = { cancelled -> assertFalse(cancelled()); f.work.tomorrow() }
        f.begin()
        assertEquals(0, f.work.runs)
        f.worker.drain(); f.completions.drain(); f.worker.drain()
        assertEquals(1, f.work.runs)
        assertEquals(listOf(false), f.finished)
        assertEquals(86_400_000L, f.runtime.snapshot().nextWakeAtMillis)
    }

    @Test fun foregroundStartBeforeWorkerClaimsPreventsAnyModelRun() {
        val f = Fixture()
        f.begin()
        f.platform.environment = f.platform.environment.copy(interactiveIdle = false)
        f.activityChanged()
        f.worker.drain(); f.completions.drain(); f.worker.drain()
        assertEquals(0, f.work.runs)
        assertEquals(1, f.work.cancellations)
        assertEquals(PhoneRoutineDailyScheduleStatus.WAITING_FOR_INTERACTION, f.runtime.snapshot().status)
    }

    @Test fun assignedNetworkMismatchCannotStartOrCreateAnImmediateRetryLoop() {
        val f = Fixture()
        f.begin(assignedNetwork = false)
        f.worker.drain(); f.completions.drain(); f.worker.drain()
        assertEquals(0, f.work.runs)
        assertEquals(f.now + PhoneRoutineDailyJobPolicy.DEFER_MILLIS, f.runtime.snapshot().nextWakeAtMillis)
    }

    @Test fun lockAfterFirstUnlockDoesNotBlockReadOnlyRun() {
        // Environment intentionally contains credential-storage unlock, not Keyguard/display state.
        val f = Fixture()
        f.begin(); f.worker.drain(); f.completions.drain()
        assertEquals(1, f.work.runs)
    }

    @Test fun initialUnlockLoginAndBackupRecoveryAreHardPreclaimGates() {
        listOf("unlock", "auth", "backup").forEach { gate ->
            val f = Fixture()
            when (gate) {
                "unlock" -> f.platform.environment = f.platform.environment.copy(userStorageUnlocked = false)
                "auth" -> f.work.state = f.work.state.copy(authenticationReady = false)
                "backup" -> f.maintenance.requireRecovery()
            }
            f.begin(); f.worker.drain(); f.completions.drain(); f.worker.drain()
            assertEquals(gate, 0, f.work.runs)
        }
    }

    @Test fun generationOrEnvironmentRevocationIsVisibleInsideRunBeforeCommit() {
        listOf("generation", "network", "backup").forEach { gate ->
            val f = Fixture()
            f.work.action = { cancelled ->
                assertFalse(cancelled())
                when (gate) {
                    "generation" -> f.work.state = f.work.state.copy(generation = 9L)
                    "network" -> f.platform.environment = f.platform.environment.copy(defaultNetworkValidated = false)
                    "backup" -> f.maintenance.requireRecovery()
                }
                assertTrue(gate, cancelled())
            }
            f.begin(); f.worker.drain(); f.completions.drain()
        }
    }

    @Test fun disablingOrForgettingCancelsActiveAndCannotResurrectItsGeneration() {
        val f = Fixture()
        f.work.action = { cancelled ->
            f.work.state = f.work.state.copy(enabled = false, generation = 4L, nextEligibleAtMillis = null)
            f.runtime.authorityChanged()
            assertTrue(cancelled())
        }
        f.begin(); f.worker.drain(); f.completions.drain(); f.worker.drain()
        assertEquals(1, f.work.cancellations)
        assertEquals(PhoneRoutineDailyScheduleStatus.DISABLED, f.runtime.snapshot().status)
        assertFalse(f.runtime.snapshot().scheduled)
    }

    @Test fun activeReservationPreventsBackupImportUntilActualWorkerCompletion() {
        val f = Fixture()
        f.work.action = {
            assertTrue(runCatching { f.maintenance.importExclusively { error("must not enter") } }.isFailure)
        }
        f.begin(); f.worker.drain()
        assertEquals(42, f.maintenance.importExclusively { 42 })
        f.completions.drain()
    }

    @Test fun stoppedJobNeverCallsJobFinishedAndLateCompletionIsHarmless() {
        val f = Fixture()
        val execution = f.begin()
        assertTrue(f.runtime.stopJob(execution))
        assertTrue(f.runtime.stopJob(execution))
        f.worker.drain(); f.completions.drain(); f.worker.drain()
        assertEquals(0, f.work.runs)
        assertTrue(f.finished.isEmpty())
        assertEquals(PhoneRoutineDailyJobPolicy.DEFER_MILLIS + f.now, f.runtime.snapshot().nextWakeAtMillis)
    }

    @Test fun reconciliationNeverReplacesRunningJobIncludingBeforeClaim() {
        val f = Fixture()
        f.runtime.reconcile()
        f.begin()
        // Reconcile was queued first, but generation is not known until worker admission.
        f.worker.next()
        assertEquals(0, f.work.cancellations)
        assertTrue(f.platform.schedules.isEmpty())
        f.worker.drain(); f.completions.drain()
    }

    @Test fun adapterFailureLeavesBackoffAndClosePreventsFutureRuns() {
        val f = Fixture()
        f.work.action = { throw IllegalStateException("private backend failure") }
        f.begin(); f.worker.drain(); f.completions.drain(); f.worker.drain()
        assertEquals(f.now + PhoneRoutineDailyJobPolicy.DEFER_MILLIS, f.runtime.snapshot().nextWakeAtMillis)
        f.runtime.close(); f.runtime.close(); f.runtime.reconcile(); f.worker.drain()
        assertEquals(PhoneRoutineDailyScheduleStatus.CLOSED, f.runtime.snapshot().status)
        assertNull(f.runtime.beginJob(true) { error("closed callback") })
    }

    private class Fixture {
        val now = 2_000L
        val worker = QueueExecutor()
        val completions = QueueExecutor()
        val maintenance = HansBackupMaintenance()
        val work = Work()
        val platform = Platform()
        val finished = mutableListOf<Boolean>()
        private var activityObserver: (() -> Unit)? = null
        val runtime = AndroidPhoneRoutineDailyLearningRuntime(work, platform, worker, completions,
            maintenance, { now }, attachActivityObserver = { observer ->
                activityObserver = observer
                AutoCloseable { activityObserver = null }
            }).also { it.start(); worker.drain(); platform.schedules.clear() }
        fun activityChanged() = checkNotNull(activityObserver).invoke()
        fun begin(assignedNetwork: Boolean = true) = checkNotNull(runtime.beginJob(assignedNetwork) { finished += it })
    }

    private class Work : PhoneRoutineDailyWork {
        var state = PhoneRoutineDailyWorkState(true, 2L, 1_000L, true)
        var runs = 0
        var cancellations = 0
        var action: ((() -> Boolean) -> Unit)? = null
        override fun snapshot() = state
        override fun run(cancelled: () -> Boolean) { runs++; action?.invoke(cancelled) }
        override fun cancelActive() { cancellations++ }
        fun tomorrow() { state = state.copy(nextEligibleAtMillis = 86_400_000L) }
    }

    private class Platform : PhoneRoutineDailyPlatform {
        var environment = PhoneRoutineDailyEnvironment(true, true, true, true)
        val schedules = mutableListOf<Long>()
        var cancellations = 0
        var accept = true
        override fun environment() = environment
        override fun schedule(wakeAtMillis: Long): Boolean { schedules += wakeAtMillis; return accept }
        override fun cancel() { cancellations++ }
    }

    private class QueueExecutor : Executor {
        private val queue = ArrayDeque<Runnable>()
        val size get() = queue.size
        override fun execute(command: Runnable) { queue.add(command) }
        fun next() = queue.removeFirst().run()
        fun drain() { var count = 0; while (queue.isNotEmpty()) { check(count++ < 40); next() } }
    }
}
