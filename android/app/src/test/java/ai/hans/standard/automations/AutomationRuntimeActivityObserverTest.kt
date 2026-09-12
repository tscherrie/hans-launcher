package ai.hans.standard.automations

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationRuntimeActivityObserverTest {
    @Test
    fun backlogIsIdleButPersistedLeaseIsActive() {
        val now = Instant.parse("2026-08-25T12:00:00Z")
        val backlog = InMemoryAutomationStorage()
        val definition = testDefinition(id = "automation.activity.backlog")
        backlog.upsertDefinition(definition)
        backlog.enqueueManualRun(
            definition.id,
            definition.revision,
            AutomationManualRequestId("a".repeat(64)),
            now,
        )
        val backlogSnapshots = mutableListOf<AutomationRuntimeActivitySnapshot>()
        owner(backlog, Executor(Runnable::run), now)
            .addActivityObserver { backlogSnapshots += it }
        assertEquals(0, backlogSnapshots.single().activeAutomationCount)

        val leased = InMemoryAutomationStorage()
        leased.insertDueRun(testDefinition(id = "automation.activity.lease"), now)
        assertTrue(
            leased.acquireNextLease(
                AutomationWorkerId("activity-worker"),
                AutomationLeaseToken("activity-lease-token"),
                AutomationBootSessionId("activity-boot"),
                now,
                Duration.ofMinutes(1),
            ) != null,
        )
        val leaseSnapshots = mutableListOf<AutomationRuntimeActivitySnapshot>()
        owner(leased, Executor(Runnable::run), now)
            .addActivityObserver { leaseSnapshots += it }
        assertEquals(1, leaseSnapshots.single().persistedLeaseCount)
        assertEquals(1, leaseSnapshots.single().activeAutomationCount)
    }

    @Test
    fun queuedCyclesNeverExposeFalseIdleAndFinishExactlyOnce() {
        val executor = HoldingExecutor()
        val runtime = owner(InMemoryAutomationStorage(), executor)
        val counts = mutableListOf<Int>()
        runtime.addActivityObserver { counts += it.activeAutomationCount }

        runtime.requestCycle(AutomationRuntimeTrigger.Timer)
        runtime.requestCycle(AutomationRuntimeTrigger.ConnectivityRestored)
        assertEquals(listOf(0, 1, 2), counts)

        executor.runNext()
        assertEquals(1, counts.last())
        executor.runNext()
        assertEquals(0, counts.last())
        assertTrue(counts.drop(1).dropLast(1).none { it == 0 })
    }

    @Test
    fun rejectedCycleReturnsToIdleOnceAndRemovedObserverStaysSilent() {
        val runtime = owner(
            InMemoryAutomationStorage(),
            Executor { throw IllegalStateException("rejected") },
        )
        val counts = mutableListOf<Int>()
        val registration = runtime.addActivityObserver { counts += it.activeAutomationCount }

        runtime.requestCycle(AutomationRuntimeTrigger.Timer)
        assertEquals(listOf(0, 1, 0), counts)

        registration.close()
        runtime.requestCycle(AutomationRuntimeTrigger.Timer)
        assertEquals(listOf(0, 1, 0), counts)
    }

    @Test
    fun concurrentFinishCannotPublishIdleAheadOfAnEarlierActiveTransition() {
        val runtime = owner(InMemoryAutomationStorage(), Executor(Runnable::run))
        val firstActiveEntered = CountDownLatch(1)
        val releaseFirstActive = CountDownLatch(1)
        val counts = mutableListOf<Int>()
        runtime.addActivityObserver { snapshot ->
            synchronized(counts) { counts += snapshot.activeAutomationCount }
            if (snapshot.activeAutomationCount == 1 && firstActiveEntered.count == 1L) {
                firstActiveEntered.countDown()
                assertTrue(releaseFirstActive.await(2, TimeUnit.SECONDS))
            }
        }

        val first = Thread { runtime.requestCycle(AutomationRuntimeTrigger.Timer) }
        first.start()
        assertTrue(firstActiveEntered.await(2, TimeUnit.SECONDS))

        val second = Thread {
            runtime.requestCycle(AutomationRuntimeTrigger.ConnectivityRestored)
        }
        second.start()
        second.join(2_000)
        assertTrue(!second.isAlive)
        releaseFirstActive.countDown()
        first.join(2_000)
        assertTrue(!first.isAlive)

        val observed = synchronized(counts) { counts.toList() }
        assertEquals(listOf(0, 1, 2, 1, 0), observed)
        assertTrue(observed.drop(1).dropLast(1).none { it == 0 })
    }

    private fun owner(
        storage: AutomationStorage,
        executor: Executor,
        now: Instant = Instant.parse("2026-08-25T12:00:00Z"),
    ) = AutomationRuntimeOwner(
        storage = storage,
        wakeupAdapter = object : AutomationWakeupAdapter {
            override fun replaceWakeup(plan: AutomationWakeupPlan) = AutomationAdapterResult.Accepted
        },
        cycleDispatcher = AutomationRuntimeCycleDispatcher { AutomationAdapterResult.Accepted },
        backgroundExecutor = executor,
        platformState = object : AutomationPlatformStateSource {
            override fun now(): Instant = now
            override fun systemZone(): ZoneId = ZoneId.of("UTC")
            override fun bootSessionId() = AutomationBootSessionId("activity-boot")
        },
        liveEnvironment = AutomationLiveEnvironmentSource.FAIL_CLOSED,
        codexExecutor = object : CodexAutomationExecutor {
            override fun execute(
                request: CodexAutomationExecutionRequest,
                heartbeat: AutomationHeartbeat,
            ) = CodexAutomationExecutionOutcome.Succeeded
        },
    )

    private class HoldingExecutor : Executor {
        private val commands = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            commands.addLast(command)
        }

        fun runNext() = commands.removeFirst().run()
    }
}
