package ai.hans.standard.automations

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationWorkGenerationTest {
    @Test
    fun everyRuntimeTriggerIncludingUserPresentRoundTripsThroughDurableWorkKind() {
        val observedAt = Instant.parse("2026-08-30T10:15:30Z")
        val triggers = listOf(
            AutomationRuntimeTrigger.Timer,
            AutomationRuntimeTrigger.ManualRun,
            AutomationRuntimeTrigger.ConnectivityRestored,
            AutomationRuntimeTrigger.DefinitionChanged,
            AutomationRuntimeTrigger.Boot(observedAt),
            AutomationRuntimeTrigger.TimeZoneChanged(observedAt),
            AutomationRuntimeTrigger.WallClockChanged(observedAt),
            AutomationRuntimeTrigger.UserPresent,
        )

        triggers.forEach { trigger ->
            val ticket = AutomationRuntimeWorkTicket(
                kind = trigger.workKind(),
                generation = 1,
                observedAt = trigger.observedAtOrNull(),
            )
            assertEquals(trigger, ticket.toRuntimeTrigger())
        }
        assertEquals(
            AutomationRuntimeWorkKind.USER_PRESENT,
            AutomationRuntimeTrigger.UserPresent.workKind(),
        )
    }

    @Test
    fun committedGenerationSurvivesCoordinatorProcessRecreation() {
        val ledger = MemoryWorkLedger()
        val first = AutomationRuntimeWorkCoordinator(ledger)
        assertTrue(
            first.request(
                jobId = 42,
                kind = AutomationRuntimeWorkKind.MANUAL_RUN,
                observedAt = null,
                schedulePlatformJob = { true },
            ),
        )

        val restored = AutomationRuntimeWorkCoordinator(ledger)
        val started = restored.start(42, AutomationRuntimeWorkKind.MANUAL_RUN)
            as AutomationRuntimeWorkCoordinator.StartResult.Started

        assertEquals(1L, started.registration.ticket.generation)
        assertEquals(
            AutomationRuntimeWorkCoordinator.CheckpointResult.ReadyToFinish,
            restored.acknowledgeAndRecheck(started.registration),
        )
        assertEquals(
            AutomationRuntimeWorkCoordinator.FinalizationResult.Finished,
            restored.finishIfCaughtUp(started.registration) {},
        )
        assertEquals(null, ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN))
    }

    @Test
    fun sameIdCoalescingRetainsNewGenerationAndWorkerConsumesItBeforeFinishing() {
        val ledger = MemoryWorkLedger()
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        val schedules = AtomicInteger()
        assertTrue(coordinator.request(7, AutomationRuntimeWorkKind.TIMER, null) {
            schedules.incrementAndGet()
            true
        })
        val started = coordinator.start(7, AutomationRuntimeWorkKind.TIMER)
            as AutomationRuntimeWorkCoordinator.StartResult.Started

        assertTrue(coordinator.request(7, AutomationRuntimeWorkKind.TIMER, null) {
            schedules.incrementAndGet()
            true
        })
        val next = coordinator.acknowledgeAndRecheck(started.registration)
            as AutomationRuntimeWorkCoordinator.CheckpointResult.Continue

        assertEquals(1, schedules.get())
        assertEquals(2L, next.ticket.generation)
        assertEquals(
            AutomationRuntimeWorkCoordinator.CheckpointResult.ReadyToFinish,
            coordinator.acknowledgeAndRecheck(started.registration),
        )
        assertEquals(
            AutomationRuntimeWorkCoordinator.FinalizationResult.Finished,
            coordinator.finishIfCaughtUp(started.registration) {},
        )
    }

    @Test
    fun triggerBetweenFinalCheckpointAndJobFinishedContinuesWithoutBeingLost() {
        val ledger = MemoryWorkLedger()
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        coordinator.request(9, AutomationRuntimeWorkKind.DEFINITION_CHANGED, null) {
            true
        }
        val started = coordinator.start(9, AutomationRuntimeWorkKind.DEFINITION_CHANGED)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        assertEquals(
            AutomationRuntimeWorkCoordinator.CheckpointResult.ReadyToFinish,
            coordinator.acknowledgeAndRecheck(started.registration),
        )
        val schedules = AtomicInteger()
        assertTrue(
            coordinator.request(9, AutomationRuntimeWorkKind.DEFINITION_CHANGED, null) {
                schedules.incrementAndGet()
                true
            },
        )
        var platformFinished = false

        val late = coordinator.finishIfCaughtUp(started.registration) {
            platformFinished = true
        }

        assertEquals(0, schedules.get())
        assertFalse(platformFinished)
        assertEquals(
            2L,
            (late as AutomationRuntimeWorkCoordinator.FinalizationResult.Continue)
                .ticket.generation,
        )
    }

    @Test
    fun triggerDuringPlatformJobFinishedWaitsThenForcesFreshSchedule() {
        val ledger = MemoryWorkLedger()
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        coordinator.request(10, AutomationRuntimeWorkKind.TIMER, null) { true }
        val started = coordinator.start(10, AutomationRuntimeWorkKind.TIMER)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        assertEquals(
            AutomationRuntimeWorkCoordinator.CheckpointResult.ReadyToFinish,
            coordinator.acknowledgeAndRecheck(started.registration),
        )
        val insidePlatformFinish = CountDownLatch(1)
        val allowPlatformFinish = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val finish = executor.submit<AutomationRuntimeWorkCoordinator.FinalizationResult> {
            coordinator.finishIfCaughtUp(started.registration) {
                insidePlatformFinish.countDown()
                check(allowPlatformFinish.await(5, TimeUnit.SECONDS))
            }
        }
        assertTrue(insidePlatformFinish.await(5, TimeUnit.SECONDS))
        val schedules = AtomicInteger()
        val late = executor.submit<Boolean> {
            coordinator.request(10, AutomationRuntimeWorkKind.TIMER, null) {
                schedules.incrementAndGet()
                true
            }
        }
        assertFalse(late.isDone)
        allowPlatformFinish.countDown()

        assertEquals(
            AutomationRuntimeWorkCoordinator.FinalizationResult.Finished,
            finish.get(5, TimeUnit.SECONDS),
        )
        assertTrue(late.get(5, TimeUnit.SECONDS))
        assertEquals(1, schedules.get())
        assertEquals(2L, ledger.pending(AutomationRuntimeWorkKind.TIMER)?.generation)
        executor.shutdownNow()
    }

    @Test
    fun releaseForRetryFinishesBeforeReleasingAndRetainsUnacknowledgedGeneration() {
        val ledger = MemoryWorkLedger()
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        coordinator.request(21, AutomationRuntimeWorkKind.MANUAL_RUN, null) { true }
        val started = coordinator.start(21, AutomationRuntimeWorkKind.MANUAL_RUN)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        val pending = ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN)
        var finishes = 0

        assertTrue(coordinator.releaseForRetry(started.registration) {
            assertTrue("Without a replacement, Android must retain its framework retry", it)
            assertTrue("Platform finish must share request's monitor", Thread.holdsLock(coordinator))
            assertTrue("Registration must remain active until finish returns", coordinator.isCurrent(started.registration))
            assertEquals(pending, ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN))
            finishes += 1
        })

        assertFalse(coordinator.isActive(21))
        assertEquals(pending, ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN))
        assertEquals(1L, ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN)?.generation)
        assertFalse(coordinator.releaseForRetry(started.registration) { finishes += 1 })
        assertEquals(1, finishes)
    }

    @Test
    fun releaseForRetryDoesNotFinishOrRemoveSupersedingRegistration() {
        val ledger = MemoryWorkLedger()
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        coordinator.request(22, AutomationRuntimeWorkKind.TIMER, null) { true }
        val first = coordinator.start(22, AutomationRuntimeWorkKind.TIMER)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        val replacement = coordinator.start(22, AutomationRuntimeWorkKind.TIMER)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        var platformFinished = false

        assertFalse(coordinator.releaseForRetry(first.registration) { platformFinished = true })

        assertFalse(platformFinished)
        assertTrue(coordinator.isCurrent(replacement.registration))
        assertEquals(1L, ledger.pending(AutomationRuntimeWorkKind.TIMER)?.generation)
    }

    @Test
    fun releaseForRetryPlatformExceptionKeepsRegistrationAndPendingGeneration() {
        val ledger = MemoryWorkLedger()
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        coordinator.request(24, AutomationRuntimeWorkKind.MANUAL_RUN, null) { true }
        val started = coordinator.start(24, AutomationRuntimeWorkKind.MANUAL_RUN)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        val pending = ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN)
        val failure = IllegalStateException("Platform finish failed")

        val thrown = assertThrows(IllegalStateException::class.java) {
            coordinator.releaseForRetry(started.registration) { throw failure }
        }

        assertEquals(failure, thrown)
        assertTrue(coordinator.isCurrent(started.registration))
        assertEquals(pending, ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN))
        assertEquals(1L, ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN)?.generation)
    }

    @Test
    fun requestDuringRetryFinishWaitsThenSchedulesNewPendingGeneration() {
        val ledger = MemoryWorkLedger()
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        coordinator.request(23, AutomationRuntimeWorkKind.MANUAL_RUN, null) { true }
        val started = coordinator.start(23, AutomationRuntimeWorkKind.MANUAL_RUN)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        val insidePlatformFinish = CountDownLatch(1)
        val allowPlatformFinish = CountDownLatch(1)
        val requestAttempted = CountDownLatch(1)
        val platformFinished = AtomicBoolean(false)
        val schedules = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val finish = executor.submit<Boolean> {
                coordinator.releaseForRetry(started.registration) {
                    assertTrue(Thread.holdsLock(coordinator))
                    assertTrue(coordinator.isCurrent(started.registration))
                    insidePlatformFinish.countDown()
                    check(allowPlatformFinish.await(5, TimeUnit.SECONDS))
                    platformFinished.set(true)
                }
            }
            assertTrue(insidePlatformFinish.await(5, TimeUnit.SECONDS))
            val late = executor.submit<Boolean> {
                requestAttempted.countDown()
                coordinator.request(23, AutomationRuntimeWorkKind.MANUAL_RUN, null) {
                    assertTrue("A new platform job must be scheduled after finish", platformFinished.get())
                    assertFalse("The released worker must not absorb new work", coordinator.isActive(23))
                    schedules.incrementAndGet()
                    true
                }
            }
            assertTrue(requestAttempted.await(5, TimeUnit.SECONDS))
            assertFalse("request must not return while the old platform job is finishing", late.isDone)
            assertEquals(0, schedules.get())
            assertEquals(1L, ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN)?.generation)
            allowPlatformFinish.countDown()

            assertTrue(finish.get(5, TimeUnit.SECONDS))
            assertTrue(late.get(5, TimeUnit.SECONDS))
            assertEquals(1, schedules.get())
            assertFalse(coordinator.isActive(23))
            assertEquals(2L, ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN)?.generation)
            val resumed = coordinator.start(23, AutomationRuntimeWorkKind.MANUAL_RUN)
                as AutomationRuntimeWorkCoordinator.StartResult.Started
            assertEquals(2L, resumed.registration.ticket.generation)
        } finally {
            allowPlatformFinish.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun retryReplacementIsInstalledBeforeFinishWithoutFrameworkRetryOrAcknowledgement() {
        val ledger = MemoryWorkLedger()
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        coordinator.request(31, AutomationRuntimeWorkKind.TIMER, null) { true }
        val started = coordinator.start(31, AutomationRuntimeWorkKind.TIMER)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        val pending = ledger.pending(AutomationRuntimeWorkKind.TIMER)
        val calls = mutableListOf<String>()

        assertTrue(coordinator.releaseForRetry(
            started.registration,
            installReplacement = {
                assertTrue(Thread.holdsLock(coordinator))
                assertTrue(coordinator.isCurrent(started.registration))
                assertEquals(pending, ledger.pending(AutomationRuntimeWorkKind.TIMER))
                calls += "install"
                true
            },
        ) { needsFrameworkRetry ->
            assertFalse(needsFrameworkRetry)
            assertTrue(Thread.holdsLock(coordinator))
            assertTrue(coordinator.isCurrent(started.registration))
            assertEquals(listOf("install"), calls)
            calls += "finish"
        })

        assertEquals(listOf("install", "finish"), calls)
        assertFalse(coordinator.isActive(31))
        assertEquals(pending, ledger.pending(AutomationRuntimeWorkKind.TIMER))
        val replacement = coordinator.start(31, AutomationRuntimeWorkKind.TIMER)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        assertEquals(1L, replacement.registration.ticket.generation)
    }

    @Test
    fun rejectedRetryReplacementFallsBackToFrameworkRetryWithoutAcknowledgement() {
        val ledger = MemoryWorkLedger()
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        coordinator.request(32, AutomationRuntimeWorkKind.MANUAL_RUN, null) { true }
        val started = coordinator.start(32, AutomationRuntimeWorkKind.MANUAL_RUN)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        val pending = ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN)
        val calls = mutableListOf<String>()

        assertTrue(coordinator.releaseForRetry(
            started.registration,
            installReplacement = {
                assertTrue(Thread.holdsLock(coordinator))
                calls += "rejected"
                false
            },
        ) { needsFrameworkRetry ->
            assertTrue(needsFrameworkRetry)
            assertTrue(coordinator.isCurrent(started.registration))
            assertEquals(pending, ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN))
            calls += "framework-retry"
        })

        assertEquals(listOf("rejected", "framework-retry"), calls)
        assertFalse(coordinator.isActive(32))
        assertEquals(pending, ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN))
    }

    @Test
    fun throwingRetryReplacementFallsBackToFrameworkRetryWithoutAcknowledgement() {
        val ledger = MemoryWorkLedger()
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        coordinator.request(33, AutomationRuntimeWorkKind.TIMER, null) { true }
        val started = coordinator.start(33, AutomationRuntimeWorkKind.TIMER)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        val pending = ledger.pending(AutomationRuntimeWorkKind.TIMER)
        val calls = mutableListOf<String>()

        assertTrue(coordinator.releaseForRetry(
            started.registration,
            installReplacement = {
                assertTrue(Thread.holdsLock(coordinator))
                calls += "install-threw"
                throw IllegalStateException("Platform rejected replacement installation")
            },
        ) { needsFrameworkRetry ->
            assertTrue(needsFrameworkRetry)
            assertTrue(coordinator.isCurrent(started.registration))
            assertEquals(pending, ledger.pending(AutomationRuntimeWorkKind.TIMER))
            calls += "framework-retry"
        })

        assertEquals(listOf("install-threw", "framework-retry"), calls)
        assertFalse(coordinator.isActive(33))
        assertEquals(pending, ledger.pending(AutomationRuntimeWorkKind.TIMER))
    }

    @Test
    fun staleRetryRegistrationCannotInstallReplacementOrFinishCurrentWorker() {
        val ledger = MemoryWorkLedger()
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        coordinator.request(34, AutomationRuntimeWorkKind.TIMER, null) { true }
        val stale = coordinator.start(34, AutomationRuntimeWorkKind.TIMER)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        val current = coordinator.start(34, AutomationRuntimeWorkKind.TIMER)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        val pending = ledger.pending(AutomationRuntimeWorkKind.TIMER)

        assertFalse(coordinator.releaseForRetry(
            stale.registration,
            installReplacement = { throw AssertionError("A stale worker must not install a retry") },
        ) { throw AssertionError("A stale worker must not finish a platform job") })

        assertTrue(coordinator.isCurrent(current.registration))
        assertEquals(pending, ledger.pending(AutomationRuntimeWorkKind.TIMER))
    }

    @Test
    fun acceptedRetryReplacementAndFinishFailurePreserveOriginalErrorAndRetirableRegistration() {
        val ledger = MemoryWorkLedger()
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        coordinator.request(35, AutomationRuntimeWorkKind.MANUAL_RUN, null) { true }
        val started = coordinator.start(35, AutomationRuntimeWorkKind.MANUAL_RUN)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        val pending = ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN)
        val failure = IllegalStateException("Platform finish failed after accepted replacement")
        val calls = mutableListOf<String>()

        val thrown = assertThrows(IllegalStateException::class.java) {
            coordinator.releaseForRetry(
                started.registration,
                installReplacement = {
                    calls += "accepted"
                    true
                },
            ) { needsFrameworkRetry ->
                assertFalse(needsFrameworkRetry)
                calls += "finish-threw"
                throw failure
            }
        }

        assertSame(failure, thrown)
        assertEquals(listOf("accepted", "finish-threw"), calls)
        assertTrue(coordinator.isCurrent(started.registration))
        assertEquals(pending, ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN))
        assertTrue("Caller must still be able to retire the failed worker", coordinator.release(started.registration))
        assertFalse(coordinator.isActive(35))
        assertEquals(pending, ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN))
    }

    @Test
    fun concurrentRequestWaitsAcrossRetryInstallAndFinishThenSchedulesGenerationTwo() {
        val ledger = MemoryWorkLedger()
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        coordinator.request(36, AutomationRuntimeWorkKind.MANUAL_RUN, null) { true }
        val started = coordinator.start(36, AutomationRuntimeWorkKind.MANUAL_RUN)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        val insideInstall = CountDownLatch(1)
        val allowInstall = CountDownLatch(1)
        val insideFinish = CountDownLatch(1)
        val allowFinish = CountDownLatch(1)
        val requestAttempted = CountDownLatch(1)
        val platformFinished = AtomicBoolean(false)
        val schedules = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val retry = executor.submit<Boolean> {
                coordinator.releaseForRetry(
                    started.registration,
                    installReplacement = {
                        assertTrue(Thread.holdsLock(coordinator))
                        assertTrue(coordinator.isCurrent(started.registration))
                        insideInstall.countDown()
                        check(allowInstall.await(5, TimeUnit.SECONDS))
                        true
                    },
                ) { needsFrameworkRetry ->
                    assertFalse(needsFrameworkRetry)
                    assertTrue(Thread.holdsLock(coordinator))
                    assertTrue(coordinator.isCurrent(started.registration))
                    insideFinish.countDown()
                    check(allowFinish.await(5, TimeUnit.SECONDS))
                    platformFinished.set(true)
                }
            }
            assertTrue(insideInstall.await(5, TimeUnit.SECONDS))
            val late = executor.submit<Boolean> {
                requestAttempted.countDown()
                coordinator.request(36, AutomationRuntimeWorkKind.MANUAL_RUN, null) {
                    assertTrue(platformFinished.get())
                    assertFalse(coordinator.isActive(36))
                    schedules.incrementAndGet()
                    true
                }
            }
            assertTrue(requestAttempted.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { late.get(100, TimeUnit.MILLISECONDS) }
            assertEquals(0, schedules.get())
            assertEquals(1L, ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN)?.generation)
            allowInstall.countDown()
            assertTrue(insideFinish.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { late.get(100, TimeUnit.MILLISECONDS) }
            assertEquals(0, schedules.get())
            assertEquals(1L, ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN)?.generation)
            allowFinish.countDown()

            assertTrue(retry.get(5, TimeUnit.SECONDS))
            assertTrue(late.get(5, TimeUnit.SECONDS))
            assertEquals(1, schedules.get())
            assertFalse(coordinator.isActive(36))
            assertEquals(2L, ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN)?.generation)
            val next = coordinator.start(36, AutomationRuntimeWorkKind.MANUAL_RUN)
                as AutomationRuntimeWorkCoordinator.StartResult.Started
            assertEquals(2L, next.registration.ticket.generation)
        } finally {
            allowInstall.countDown()
            allowFinish.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun reentrantRequestDuringRetryInstallRemainsPendingForTheReplacementWorker() {
        val ledger = object : MemoryWorkLedger() {
            override fun acknowledge(ticket: AutomationRuntimeWorkTicket): AutomationRuntimeWorkTicket? {
                throw AssertionError("Retry installation must not acknowledge any generation")
            }
        }
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        coordinator.request(37, AutomationRuntimeWorkKind.TIMER, null) { true }
        val started = coordinator.start(37, AutomationRuntimeWorkKind.TIMER)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        var unexpectedSchedules = 0

        assertTrue(coordinator.releaseForRetry(
            started.registration,
            installReplacement = {
                assertTrue(coordinator.request(37, AutomationRuntimeWorkKind.TIMER, null) {
                    unexpectedSchedules += 1
                    true
                })
                assertEquals(2L, ledger.pending(AutomationRuntimeWorkKind.TIMER)?.generation)
                true
            },
        ) { needsFrameworkRetry ->
            assertFalse(needsFrameworkRetry)
            assertTrue(coordinator.isCurrent(started.registration))
            assertEquals(2L, ledger.pending(AutomationRuntimeWorkKind.TIMER)?.generation)
        })

        assertEquals(0, unexpectedSchedules)
        assertFalse(coordinator.isActive(37))
        assertEquals(2L, ledger.pending(AutomationRuntimeWorkKind.TIMER)?.generation)
        val replacement = coordinator.start(37, AutomationRuntimeWorkKind.TIMER)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        assertEquals(2L, replacement.registration.ticket.generation)
        assertEquals(2L, ledger.pending(AutomationRuntimeWorkKind.TIMER)?.generation)
    }

    @Test
    fun recoveryGuardInstallationUsesLatestPendingTicketWithoutAcknowledgement() {
        val ledger = object : MemoryWorkLedger() {
            override fun acknowledge(ticket: AutomationRuntimeWorkTicket): AutomationRuntimeWorkTicket? {
                throw AssertionError("Installing a guard must not acknowledge work")
            }
        }
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        coordinator.request(51, AutomationRuntimeWorkKind.TIMER, null) { true }
        val started = coordinator.start(51, AutomationRuntimeWorkKind.TIMER)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        coordinator.request(51, AutomationRuntimeWorkKind.TIMER, null) {
            throw AssertionError("An active worker must coalesce the new generation")
        }
        val latest = ledger.pending(AutomationRuntimeWorkKind.TIMER)

        assertTrue(coordinator.installRecoveryGuard(started.registration) { ticket ->
            assertTrue(Thread.holdsLock(coordinator))
            assertTrue(coordinator.isCurrent(started.registration))
            assertEquals(latest, ticket)
            true
        })

        assertEquals(1L, started.registration.ticket.generation)
        assertEquals(2L, latest?.generation)
        assertEquals(latest, ledger.pending(AutomationRuntimeWorkKind.TIMER))
        assertTrue(coordinator.isCurrent(started.registration))
    }

    @Test
    fun supersededReleasedAndAcknowledgedRegistrationsCannotInstallRecoveryGuards() {
        val ledger = MemoryWorkLedger()
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        coordinator.request(52, AutomationRuntimeWorkKind.TIMER, null) { true }
        val stale = coordinator.start(52, AutomationRuntimeWorkKind.TIMER)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        val current = coordinator.start(52, AutomationRuntimeWorkKind.TIMER)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        val forbiddenInstall: (AutomationRuntimeWorkTicket) -> Boolean = {
            throw AssertionError("An unowned or acknowledged registration must not arm a guard")
        }

        assertFalse(coordinator.installRecoveryGuard(stale.registration, forbiddenInstall))
        assertTrue(coordinator.release(current.registration))
        assertFalse(coordinator.installRecoveryGuard(current.registration, forbiddenInstall))
        val resumed = coordinator.start(52, AutomationRuntimeWorkKind.TIMER)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        assertEquals(
            AutomationRuntimeWorkCoordinator.CheckpointResult.ReadyToFinish,
            coordinator.acknowledgeAndRecheck(resumed.registration),
        )
        assertFalse(coordinator.installRecoveryGuard(resumed.registration, forbiddenInstall))
        assertTrue(coordinator.isCurrent(resumed.registration))
        assertEquals(null, ledger.pending(AutomationRuntimeWorkKind.TIMER))
    }

    @Test
    fun rejectedOrThrowingRecoveryGuardInstallationPreservesPendingRegistration() {
        listOf(false, true).forEach { throws ->
            val ledger = MemoryWorkLedger()
            val coordinator = AutomationRuntimeWorkCoordinator(ledger)
            coordinator.request(53, AutomationRuntimeWorkKind.MANUAL_RUN, null) { true }
            val started = coordinator.start(53, AutomationRuntimeWorkKind.MANUAL_RUN)
                as AutomationRuntimeWorkCoordinator.StartResult.Started
            val pending = ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN)

            assertFalse(coordinator.installRecoveryGuard(started.registration) {
                if (throws) throw IllegalStateException("Synthetic guard installation failure")
                false
            })

            assertTrue(coordinator.isCurrent(started.registration))
            assertEquals(pending, ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN))
        }
    }

    @Test
    fun reentrantSupersessionDuringGuardInstallationCannotAuthorizeOldCycle() {
        val ledger = MemoryWorkLedger()
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        coordinator.request(54, AutomationRuntimeWorkKind.TIMER, null) { true }
        val old = coordinator.start(54, AutomationRuntimeWorkKind.TIMER)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        var replacement: AutomationRuntimeWorkCoordinator.Registration? = null

        assertFalse(coordinator.installRecoveryGuard(old.registration) {
            replacement = (coordinator.start(54, AutomationRuntimeWorkKind.TIMER)
                as AutomationRuntimeWorkCoordinator.StartResult.Started).registration
            true
        })

        assertTrue(coordinator.isCurrent(checkNotNull(replacement)))
        assertEquals(1L, ledger.pending(AutomationRuntimeWorkKind.TIMER)?.generation)
    }

    @Test
    fun inactiveGuardRearmsBeforeSchedulingExistingTicketWithoutRecordingOrAcknowledging() {
        val ledger = object : MemoryWorkLedger() {
            override fun acknowledge(ticket: AutomationRuntimeWorkTicket): AutomationRuntimeWorkTicket? {
                throw AssertionError("A recovery guard must not acknowledge work")
            }
        }
        val beforeProcessLoss = AutomationRuntimeWorkCoordinator(ledger)
        beforeProcessLoss.request(55, AutomationRuntimeWorkKind.MANUAL_RUN, null) { true }
        beforeProcessLoss.start(55, AutomationRuntimeWorkKind.MANUAL_RUN)
        val restored = AutomationRuntimeWorkCoordinator(ledger)
        val pending = ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN)
        val calls = mutableListOf<String>()

        val result = restored.recoverFromGuard(
            jobId = 55,
            kind = AutomationRuntimeWorkKind.MANUAL_RUN,
            generation = 1,
            isCurrentSchedule = {
                assertTrue(Thread.holdsLock(restored))
                calls += "nonce"
                true
            },
            rearmGuard = {
                assertTrue(Thread.holdsLock(restored))
                assertEquals(pending, it)
                assertEquals(listOf("nonce"), calls)
                calls += "rearm"
                true
            },
            schedulePlatformJob = {
                assertTrue(Thread.holdsLock(restored))
                assertFalse(restored.isActive(55))
                assertEquals(pending, it)
                assertEquals(listOf("nonce", "rearm"), calls)
                calls += "execution"
                true
            },
        )

        assertEquals(AutomationRuntimeWorkCoordinator.RecoveryGuardResult.EXECUTION_SCHEDULED, result)
        assertEquals(listOf("nonce", "rearm", "execution"), calls)
        assertEquals(pending, ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN))
        assertEquals(1L, ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN)?.generation)
        assertFalse(restored.isActive(55))
    }

    @Test
    fun activeOrQueuedCycleOnlyRearmsGuardWithoutDispatchOrCancellation() {
        val ledger = MemoryWorkLedger()
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        coordinator.request(56, AutomationRuntimeWorkKind.TIMER, null) { true }
        val started = coordinator.start(56, AutomationRuntimeWorkKind.TIMER)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        val cancellations = AtomicInteger()
        started.registration.attachCancellation { cancellations.incrementAndGet() }
        var guardCount = 0

        val result = coordinator.recoverFromGuard(
            jobId = 56,
            kind = AutomationRuntimeWorkKind.TIMER,
            generation = 1,
            isCurrentSchedule = { true },
            rearmGuard = {
                assertTrue(coordinator.isCurrent(started.registration))
                guardCount += 1
                true
            },
            schedulePlatformJob = { throw AssertionError("Queued/active cycles must not be duplicated") },
        )

        assertEquals(AutomationRuntimeWorkCoordinator.RecoveryGuardResult.ACTIVE_REARMED, result)
        assertEquals(1, guardCount)
        assertEquals(0, cancellations.get())
        assertTrue(coordinator.isCurrent(started.registration))
        assertEquals(1L, ledger.pending(AutomationRuntimeWorkKind.TIMER)?.generation)
    }

    @Test
    fun staleNonceAndFutureOrInvalidGuardGenerationsHaveNoSchedulingSideEffects() {
        listOf(false to 1L, true to 2L, true to 0L, true to -1L).forEach { (current, generation) ->
            val ledger = MemoryWorkLedger()
            val coordinator = AutomationRuntimeWorkCoordinator(ledger)
            coordinator.request(57, AutomationRuntimeWorkKind.TIMER, null) { true }
            val probes = AtomicInteger()
            val forbiddenSchedule: (AutomationRuntimeWorkTicket) -> Boolean = {
                throw AssertionError("A stale guard must not replace a guard or execution job")
            }

            val result = coordinator.recoverFromGuard(
                jobId = 57,
                kind = AutomationRuntimeWorkKind.TIMER,
                generation = generation,
                isCurrentSchedule = {
                    assertTrue(Thread.holdsLock(coordinator))
                    probes.incrementAndGet()
                    current
                },
                rearmGuard = forbiddenSchedule,
                schedulePlatformJob = forbiddenSchedule,
            )

            assertEquals(AutomationRuntimeWorkCoordinator.RecoveryGuardResult.STALE, result)
            assertEquals(1, probes.get())
            assertEquals(1L, ledger.pending(AutomationRuntimeWorkKind.TIMER)?.generation)
        }
    }

    @Test
    fun failedGuardNonceProbePreservesOriginalErrorInsteadOfClaimingStale() {
        val ledger = MemoryWorkLedger()
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        coordinator.request(58, AutomationRuntimeWorkKind.TIMER, null) { true }
        val failure = IllegalStateException("Synthetic platform schedule lookup failure")
        val forbiddenSchedule: (AutomationRuntimeWorkTicket) -> Boolean = {
            throw AssertionError("An unverified schedule cannot dispatch")
        }

        val thrown = assertThrows(IllegalStateException::class.java) {
            coordinator.recoverFromGuard(
                58,
                AutomationRuntimeWorkKind.TIMER,
                1,
                isCurrentSchedule = { throw failure },
                rearmGuard = forbiddenSchedule,
                schedulePlatformJob = forbiddenSchedule,
            )
        }

        assertSame(failure, thrown)
        assertEquals(1L, ledger.pending(AutomationRuntimeWorkKind.TIMER)?.generation)
    }

    @Test
    fun guardAfterAcknowledgementDoesNotReplayOrTouchAnotherWorkKind() {
        val ledger = MemoryWorkLedger()
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        coordinator.request(59, AutomationRuntimeWorkKind.TIMER, null) { true }
        val started = coordinator.start(59, AutomationRuntimeWorkKind.TIMER)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        coordinator.acknowledgeAndRecheck(started.registration)
        coordinator.request(60, AutomationRuntimeWorkKind.MANUAL_RUN, null) { true }
        val forbiddenSchedule: (AutomationRuntimeWorkTicket) -> Boolean = {
            throw AssertionError("Acknowledged work must not rearm or execute")
        }

        assertEquals(
            AutomationRuntimeWorkCoordinator.RecoveryGuardResult.NO_PENDING_WORK,
            coordinator.recoverFromGuard(
                59,
                AutomationRuntimeWorkKind.TIMER,
                1,
                isCurrentSchedule = { true },
                rearmGuard = forbiddenSchedule,
                schedulePlatformJob = forbiddenSchedule,
            ),
        )

        assertTrue(coordinator.isCurrent(started.registration))
        assertEquals(null, ledger.pending(AutomationRuntimeWorkKind.TIMER))
        assertEquals(1L, ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN)?.generation)
    }

    @Test
    fun oldGuardGenerationRearmsAndDispatchesNewestCoalescedObservation() {
        val ledger = MemoryWorkLedger()
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        val firstTime = Instant.parse("2026-08-27T08:00:00Z")
        val secondTime = firstTime.plusSeconds(5)
        coordinator.request(61, AutomationRuntimeWorkKind.WALL_CLOCK_CHANGED, firstTime) { true }
        coordinator.request(61, AutomationRuntimeWorkKind.WALL_CLOCK_CHANGED, secondTime) { true }
        val latest = ledger.pending(AutomationRuntimeWorkKind.WALL_CLOCK_CHANGED)
        val delivered = mutableListOf<AutomationRuntimeWorkTicket>()

        assertEquals(
            AutomationRuntimeWorkCoordinator.RecoveryGuardResult.EXECUTION_SCHEDULED,
            coordinator.recoverFromGuard(
                61,
                AutomationRuntimeWorkKind.WALL_CLOCK_CHANGED,
                1,
                isCurrentSchedule = { true },
                rearmGuard = { delivered += it; true },
                schedulePlatformJob = { delivered += it; true },
            ),
        )

        assertEquals(listOf(latest, latest), delivered)
        assertEquals(2L, latest?.generation)
        assertEquals(secondTime, latest?.observedAt)
        assertEquals(latest, ledger.pending(AutomationRuntimeWorkKind.WALL_CLOCK_CHANGED))
    }

    @Test
    fun rejectedOrThrowingGuardRearmNeverDispatchesAndKeepsPendingWork() {
        listOf(false, true).forEach { throws ->
            val ledger = MemoryWorkLedger()
            val coordinator = AutomationRuntimeWorkCoordinator(ledger)
            coordinator.request(62, AutomationRuntimeWorkKind.MANUAL_RUN, null) { true }
            val pending = ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN)

            val result = coordinator.recoverFromGuard(
                62,
                AutomationRuntimeWorkKind.MANUAL_RUN,
                1,
                isCurrentSchedule = { true },
                rearmGuard = {
                    if (throws) throw IllegalStateException("Synthetic guard rearm failure")
                    false
                },
                schedulePlatformJob = { throw AssertionError("No dispatch without a replacement guard") },
            )

            assertEquals(AutomationRuntimeWorkCoordinator.RecoveryGuardResult.REARM_REJECTED, result)
            assertFalse(coordinator.isActive(62))
            assertEquals(pending, ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN))
        }
    }

    @Test
    fun rejectedOrThrowingExecutionLeavesReplacementGuardAndDoesNotRequestOldGuardRetry() {
        listOf(false, true).forEach { throws ->
            val ledger = MemoryWorkLedger()
            val coordinator = AutomationRuntimeWorkCoordinator(ledger)
            coordinator.request(63, AutomationRuntimeWorkKind.TIMER, null) { true }
            val pending = ledger.pending(AutomationRuntimeWorkKind.TIMER)
            val calls = mutableListOf<String>()

            val result = coordinator.recoverFromGuard(
                63,
                AutomationRuntimeWorkKind.TIMER,
                1,
                isCurrentSchedule = { true },
                rearmGuard = { calls += "replacement-guard"; true },
                schedulePlatformJob = {
                    assertEquals(listOf("replacement-guard"), calls)
                    calls += "execution-rejected"
                    if (throws) throw IllegalStateException("Synthetic execution schedule failure")
                    false
                },
            )

            assertEquals(
                AutomationRuntimeWorkCoordinator.RecoveryGuardResult.REARMED_EXECUTION_REJECTED,
                result,
            )
            assertEquals(listOf("replacement-guard", "execution-rejected"), calls)
            assertEquals(pending, ledger.pending(AutomationRuntimeWorkKind.TIMER))
        }
    }

    @Test
    fun ledgerReadFailureAfterRearmKeepsReplacementInsteadOfRetryingOldGuard() {
        var readsRejected = false
        val ledger = object : MemoryWorkLedger() {
            override fun pending(kind: AutomationRuntimeWorkKind): AutomationRuntimeWorkTicket? {
                if (readsRejected) throw IllegalStateException("Synthetic ledger read failure")
                return super.pending(kind)
            }
        }
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        coordinator.request(71, AutomationRuntimeWorkKind.TIMER, null) { true }
        val guarded = AtomicReference<AutomationRuntimeWorkTicket?>()

        val result = coordinator.recoverFromGuard(
            71,
            AutomationRuntimeWorkKind.TIMER,
            1,
            isCurrentSchedule = { true },
            rearmGuard = {
                guarded.set(it)
                readsRejected = true
                true
            },
            schedulePlatformJob = { throw AssertionError("Cannot dispatch with unreadable pending work") },
        )

        assertEquals(
            AutomationRuntimeWorkCoordinator.RecoveryGuardResult.REARMED_EXECUTION_REJECTED,
            result,
        )
        assertEquals(1L, guarded.get()?.generation)
        readsRejected = false
        assertEquals(guarded.get(), ledger.pending(AutomationRuntimeWorkKind.TIMER))
    }

    @Test
    fun mismatchedActiveWorkKindCannotBeUsedAsRecoveryGuardBinding() {
        val ledger = MemoryWorkLedger()
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        coordinator.request(64, AutomationRuntimeWorkKind.TIMER, null) { true }
        val timer = coordinator.start(64, AutomationRuntimeWorkKind.TIMER)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        ledger.record(AutomationRuntimeWorkKind.MANUAL_RUN, null)
        val forbiddenSchedule: (AutomationRuntimeWorkTicket) -> Boolean = {
            throw AssertionError("A mismatched active binding must not schedule")
        }

        assertEquals(
            AutomationRuntimeWorkCoordinator.RecoveryGuardResult.STALE,
            coordinator.recoverFromGuard(
                64,
                AutomationRuntimeWorkKind.MANUAL_RUN,
                1,
                isCurrentSchedule = { true },
                rearmGuard = forbiddenSchedule,
                schedulePlatformJob = forbiddenSchedule,
            ),
        )

        assertTrue(coordinator.isCurrent(timer.registration))
        assertEquals(1L, ledger.pending(AutomationRuntimeWorkKind.TIMER)?.generation)
        assertEquals(1L, ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN)?.generation)
    }

    @Test
    fun reentrantAcknowledgementDuringGuardRearmDoesNotDispatchAcknowledgedWork() {
        val ledger = MemoryWorkLedger()
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        coordinator.request(65, AutomationRuntimeWorkKind.TIMER, null) { true }
        val started = coordinator.start(65, AutomationRuntimeWorkKind.TIMER)
            as AutomationRuntimeWorkCoordinator.StartResult.Started

        val result = coordinator.recoverFromGuard(
            65,
            AutomationRuntimeWorkKind.TIMER,
            1,
            isCurrentSchedule = { true },
            rearmGuard = {
                assertEquals(
                    AutomationRuntimeWorkCoordinator.CheckpointResult.ReadyToFinish,
                    coordinator.acknowledgeAndRecheck(started.registration),
                )
                assertTrue(coordinator.release(started.registration))
                true
            },
            schedulePlatformJob = { throw AssertionError("The ticket was already acknowledged") },
        )

        assertEquals(AutomationRuntimeWorkCoordinator.RecoveryGuardResult.NO_PENDING_WORK, result)
        assertFalse(coordinator.isActive(65))
        assertEquals(null, ledger.pending(AutomationRuntimeWorkKind.TIMER))
    }

    @Test
    fun reentrantRequestDuringGuardRearmDispatchesNewestPendingTicket() {
        val ledger = MemoryWorkLedger()
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        coordinator.request(66, AutomationRuntimeWorkKind.TIMER, null) { true }
        val guarded = AtomicReference<AutomationRuntimeWorkTicket?>()
        val dispatched = AtomicReference<AutomationRuntimeWorkTicket?>()

        val result = coordinator.recoverFromGuard(
            66,
            AutomationRuntimeWorkKind.TIMER,
            1,
            isCurrentSchedule = { true },
            rearmGuard = {
                guarded.set(it)
                assertFalse(coordinator.request(66, AutomationRuntimeWorkKind.TIMER, null) { false })
                true
            },
            schedulePlatformJob = { dispatched.set(it); true },
        )

        assertEquals(AutomationRuntimeWorkCoordinator.RecoveryGuardResult.EXECUTION_SCHEDULED, result)
        assertEquals(1L, guarded.get()?.generation)
        assertEquals(2L, dispatched.get()?.generation)
        assertEquals(dispatched.get(), ledger.pending(AutomationRuntimeWorkKind.TIMER))
    }

    @Test
    fun supersededFinishCannotCancelNewerRecoveryGuard() {
        val ledger = MemoryWorkLedger()
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        coordinator.request(67, AutomationRuntimeWorkKind.TIMER, null) { true }
        val stale = coordinator.start(67, AutomationRuntimeWorkKind.TIMER)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        val current = coordinator.start(67, AutomationRuntimeWorkKind.TIMER)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        val guarded = AtomicBoolean(false)
        assertTrue(coordinator.installRecoveryGuard(current.registration) { guarded.set(true); true })

        assertEquals(
            AutomationRuntimeWorkCoordinator.CheckpointResult.Superseded,
            coordinator.acknowledgeAndRecheck(stale.registration),
        )
        assertEquals(
            AutomationRuntimeWorkCoordinator.FinalizationResult.Superseded,
            coordinator.finishIfCaughtUp(stale.registration) { guarded.set(false) },
        )

        assertTrue(guarded.get())
        assertTrue(coordinator.isCurrent(current.registration))
        assertEquals(1L, ledger.pending(AutomationRuntimeWorkKind.TIMER)?.generation)
    }

    @Test
    fun requestBetweenAcknowledgementAndFinalizationKeepsGuardForContinuedCycle() {
        val ledger = MemoryWorkLedger()
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        coordinator.request(68, AutomationRuntimeWorkKind.TIMER, null) { true }
        val started = coordinator.start(68, AutomationRuntimeWorkKind.TIMER)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        val guarded = AtomicReference<AutomationRuntimeWorkTicket?>()
        assertTrue(coordinator.installRecoveryGuard(started.registration) { guarded.set(it); true })
        coordinator.acknowledgeAndRecheck(started.registration)
        coordinator.request(68, AutomationRuntimeWorkKind.TIMER, null) {
            throw AssertionError("The registration is still active until finalization")
        }

        assertEquals(
            AutomationRuntimeWorkCoordinator.FinalizationResult.Continue(checkNotNull(ledger.pending(AutomationRuntimeWorkKind.TIMER))),
            coordinator.finishIfCaughtUp(started.registration) {
                guarded.set(null)
                throw AssertionError("A new pending generation must retain its guard")
            },
        )
        assertEquals(1L, guarded.get()?.generation)
        assertTrue(coordinator.installRecoveryGuard(started.registration) { guarded.set(it); true })
        assertEquals(2L, guarded.get()?.generation)
        coordinator.acknowledgeAndRecheck(started.registration)
        assertEquals(
            AutomationRuntimeWorkCoordinator.FinalizationResult.Finished,
            coordinator.finishIfCaughtUp(started.registration) {
                assertTrue(Thread.holdsLock(coordinator))
                guarded.set(null)
            },
        )
        assertEquals(null, guarded.get())
        assertEquals(null, ledger.pending(AutomationRuntimeWorkKind.TIMER))
    }

    @Test
    fun concurrentRequestWaitsAcrossGuardRearmAndExecutionScheduling() {
        val ledger = MemoryWorkLedger()
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        coordinator.request(69, AutomationRuntimeWorkKind.TIMER, null) { true }
        val insideRearm = CountDownLatch(1)
        val allowRearm = CountDownLatch(1)
        val insideSchedule = CountDownLatch(1)
        val allowSchedule = CountDownLatch(1)
        val requestAttempted = CountDownLatch(1)
        val guarded = AtomicReference<AutomationRuntimeWorkTicket?>()
        val recoveryScheduled = AtomicBoolean(false)
        val laterSchedules = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val recovery = executor.submit<AutomationRuntimeWorkCoordinator.RecoveryGuardResult> {
                coordinator.recoverFromGuard(
                    69,
                    AutomationRuntimeWorkKind.TIMER,
                    1,
                    isCurrentSchedule = { true },
                    rearmGuard = {
                        assertTrue(Thread.holdsLock(coordinator))
                        insideRearm.countDown()
                        check(allowRearm.await(5, TimeUnit.SECONDS))
                        guarded.set(it)
                        true
                    },
                    schedulePlatformJob = {
                        assertTrue(Thread.holdsLock(coordinator))
                        assertEquals(guarded.get(), it)
                        insideSchedule.countDown()
                        check(allowSchedule.await(5, TimeUnit.SECONDS))
                        recoveryScheduled.set(true)
                        true
                    },
                )
            }
            assertTrue(insideRearm.await(5, TimeUnit.SECONDS))
            val request = executor.submit<Boolean> {
                requestAttempted.countDown()
                coordinator.request(69, AutomationRuntimeWorkKind.TIMER, null) {
                    assertTrue(recoveryScheduled.get())
                    assertEquals(1L, guarded.get()?.generation)
                    laterSchedules.incrementAndGet()
                    true
                }
            }
            assertTrue(requestAttempted.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { request.get(100, TimeUnit.MILLISECONDS) }
            assertEquals(1L, ledger.pending(AutomationRuntimeWorkKind.TIMER)?.generation)
            allowRearm.countDown()
            assertTrue(insideSchedule.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { request.get(100, TimeUnit.MILLISECONDS) }
            assertEquals(0, laterSchedules.get())
            allowSchedule.countDown()

            assertEquals(
                AutomationRuntimeWorkCoordinator.RecoveryGuardResult.EXECUTION_SCHEDULED,
                recovery.get(5, TimeUnit.SECONDS),
            )
            assertTrue(request.get(5, TimeUnit.SECONDS))
            assertEquals(1, laterSchedules.get())
            assertEquals(2L, ledger.pending(AutomationRuntimeWorkKind.TIMER)?.generation)
            assertEquals(1L, guarded.get()?.generation)
        } finally {
            allowRearm.countDown()
            allowSchedule.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun concurrentRequestCannotLoseItsNewGuardToPreviousFinalization() {
        val ledger = MemoryWorkLedger()
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        coordinator.request(70, AutomationRuntimeWorkKind.TIMER, null) { true }
        val first = coordinator.start(70, AutomationRuntimeWorkKind.TIMER)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        val guarded = AtomicReference<AutomationRuntimeWorkTicket?>()
        assertTrue(coordinator.installRecoveryGuard(first.registration) { guarded.set(it); true })
        coordinator.acknowledgeAndRecheck(first.registration)
        val insideCancel = CountDownLatch(1)
        val allowCancel = CountDownLatch(1)
        val requestAttempted = CountDownLatch(1)
        val schedules = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val finish = executor.submit<AutomationRuntimeWorkCoordinator.FinalizationResult> {
                coordinator.finishIfCaughtUp(first.registration) {
                    assertTrue(Thread.holdsLock(coordinator))
                    insideCancel.countDown()
                    check(allowCancel.await(5, TimeUnit.SECONDS))
                    guarded.set(null)
                }
            }
            assertTrue(insideCancel.await(5, TimeUnit.SECONDS))
            val request = executor.submit<Boolean> {
                requestAttempted.countDown()
                val accepted = coordinator.request(70, AutomationRuntimeWorkKind.TIMER, null) {
                    assertEquals(null, guarded.get())
                    schedules.incrementAndGet()
                    true
                }
                val second = coordinator.start(70, AutomationRuntimeWorkKind.TIMER)
                    as AutomationRuntimeWorkCoordinator.StartResult.Started
                assertTrue(coordinator.installRecoveryGuard(second.registration) { guarded.set(it); true })
                accepted
            }
            assertTrue(requestAttempted.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { request.get(100, TimeUnit.MILLISECONDS) }
            assertEquals(1L, guarded.get()?.generation)
            allowCancel.countDown()

            assertEquals(AutomationRuntimeWorkCoordinator.FinalizationResult.Finished, finish.get(5, TimeUnit.SECONDS))
            assertTrue(request.get(5, TimeUnit.SECONDS))
            assertEquals(1, schedules.get())
            assertEquals(2L, guarded.get()?.generation)
            assertEquals(2L, ledger.pending(AutomationRuntimeWorkKind.TIMER)?.generation)
            assertEquals(
                AutomationRuntimeWorkCoordinator.FinalizationResult.Superseded,
                coordinator.finishIfCaughtUp(first.registration) {
                    throw AssertionError("Late completion must not cancel the new guard")
                },
            )
        } finally {
            allowCancel.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun platformScheduleFailureIsReportedButCommittedGenerationRemainsRecoverable() {
        val ledger = MemoryWorkLedger()
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)

        assertFalse(
            coordinator.request(11, AutomationRuntimeWorkKind.MANUAL_RUN, null) {
                false
            },
        )
        assertEquals(1L, ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN)?.generation)
    }

    @Test
    fun duplicateOnStartSupersedesAndCancelsOldHandleEvenIfItAttachesLate() {
        val ledger = MemoryWorkLedger()
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        coordinator.request(13, AutomationRuntimeWorkKind.CONNECTIVITY_RESTORED, null) {
            true
        }
        val first = coordinator.start(13, AutomationRuntimeWorkKind.CONNECTIVITY_RESTORED)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        val second = coordinator.start(13, AutomationRuntimeWorkKind.CONNECTIVITY_RESTORED)
            as AutomationRuntimeWorkCoordinator.StartResult.Started
        val cancelled = AtomicBoolean(false)

        second.superseded?.cancel()
        first.registration.attachCancellation { cancelled.set(true) }

        assertTrue(cancelled.get())
        assertFalse(coordinator.isCurrent(first.registration))
        assertTrue(coordinator.isCurrent(second.registration))
    }

    @Test
    fun processDeathAfterRecordBeforeScheduleIsRecoveredWithoutANewUserTrigger() {
        val ledger = MemoryWorkLedger()
        val beforeDeath = AutomationRuntimeWorkCoordinator(ledger)
        runCatching {
            beforeDeath.request(17, AutomationRuntimeWorkKind.MANUAL_RUN, null) {
                throw SimulatedProcessDeath()
            }
        }
        val scheduledKinds = mutableListOf<AutomationRuntimeWorkKind>()

        val restored = AutomationRuntimeWorkCoordinator(ledger)
        assertTrue(
            restored.recoverPending(
                jobId = { 100 + it.ordinal },
                schedulePlatformJob = {
                    scheduledKinds += it.kind
                    true
                },
            ),
        )

        assertEquals(listOf(AutomationRuntimeWorkKind.MANUAL_RUN), scheduledKinds)
        assertEquals(1L, ledger.pending(AutomationRuntimeWorkKind.MANUAL_RUN)?.generation)
    }

    @Test
    fun acknowledgedClockTimestampDoesNotLeakIntoLaterBackwardClockEvent() {
        val ledger = MemoryWorkLedger()
        val old = Instant.parse("2026-08-25T10:00:00Z")
        val corrected = Instant.parse("2026-08-25T08:00:00Z")
        val first = ledger.record(AutomationRuntimeWorkKind.WALL_CLOCK_CHANGED, old)
        assertEquals(null, ledger.acknowledge(first))

        val second = ledger.record(AutomationRuntimeWorkKind.WALL_CLOCK_CHANGED, corrected)

        assertEquals(corrected, second.observedAt)
        assertEquals(corrected, ledger.pending(second.kind)?.observedAt)
    }
}

private open class MemoryWorkLedger : AutomationRuntimeWorkLedger {
    private data class State(
        val requested: Long,
        val acknowledged: Long,
        val observedAt: Instant?,
    )

    private val states = linkedMapOf<AutomationRuntimeWorkKind, State>()

    @Synchronized
    override fun record(
        kind: AutomationRuntimeWorkKind,
        observedAt: Instant?,
    ): AutomationRuntimeWorkTicket {
        val previous = states[kind] ?: State(0, 0, null)
        val priorObservedAt = previous.observedAt.takeIf {
            previous.requested > previous.acknowledged
        }
        val retainedObservedAt = listOfNotNull(priorObservedAt, observedAt).maxOrNull()
        val next = previous.copy(
            requested = previous.requested + 1,
            observedAt = retainedObservedAt,
        )
        states[kind] = next
        return AutomationRuntimeWorkTicket(kind, next.requested, retainedObservedAt)
    }

    @Synchronized
    override fun pending(kind: AutomationRuntimeWorkKind): AutomationRuntimeWorkTicket? {
        val state = states[kind] ?: return null
        if (state.requested <= state.acknowledged) return null
        return AutomationRuntimeWorkTicket(kind, state.requested, state.observedAt)
    }

    @Synchronized
    override fun allPending(): List<AutomationRuntimeWorkTicket> =
        AutomationRuntimeWorkKind.entries.mapNotNull(::pending)

    @Synchronized
    open override fun acknowledge(
        ticket: AutomationRuntimeWorkTicket,
    ): AutomationRuntimeWorkTicket? {
        val state = checkNotNull(states[ticket.kind])
        check(ticket.generation <= state.requested)
        val acknowledged = maxOf(state.acknowledged, ticket.generation)
        states[ticket.kind] = state.copy(
            acknowledged = acknowledged,
            observedAt = if (acknowledged >= state.requested) null else state.observedAt,
        )
        return pending(ticket.kind)
    }
}

private class SimulatedProcessDeath : RuntimeException()
