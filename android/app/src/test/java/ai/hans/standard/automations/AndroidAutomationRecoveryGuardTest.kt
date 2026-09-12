package ai.hans.standard.automations

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adapter/coordinator composition only: no Android service, bundle or static SDK calls. */
class AndroidAutomationRecoveryGuardTest {
    @Test
    fun scheduleRetainsCanonicalIdentityForEveryWorkKind() {
        AutomationRuntimeWorkKind.entries.forEach { kind ->
            val schedule = schedule(kind = kind, generation = Long.MAX_VALUE)

            assertEquals(GUARD_JOB_ID, schedule.jobId)
            assertEquals(EXECUTION_JOB_ID, schedule.executionJobId)
            assertEquals(kind, schedule.kind)
            assertEquals(Long.MAX_VALUE, schedule.generation)
            assertEquals(ORIGINAL_NONCE, schedule.nonce)
        }
    }

    @Test
    fun scheduleRejectsInvalidOrAliasedJobIdsAndNonpositiveGenerations() {
        invalidJobIds().forEach { (guardId, executionId) ->
            assertThrows(IllegalArgumentException::class.java) {
                schedule().copy(jobId = guardId, executionJobId = executionId)
            }
        }
        listOf(0L, -1L, Long.MIN_VALUE).forEach { generation ->
            assertThrows(IllegalArgumentException::class.java) {
                schedule(generation = generation)
            }
        }
    }

    @Test
    fun scheduleRejectsMalformedOrNoncanonicalNonce() {
        listOf("", "not-a-uuid", "1-1-1-1-1", ORIGINAL_NONCE.uppercase(), " $ORIGINAL_NONCE")
            .forEach { nonce ->
                assertThrows(IllegalArgumentException::class.java) {
                    schedule(nonce = nonce)
                }
            }
    }

    @Test
    fun guardRejectsInvalidOrAliasedIdsBeforeProbingBackend() {
        invalidJobIds().forEach { (guardId, executionId) ->
            val backend = FakeBackend()

            assertThrows(IllegalArgumentException::class.java) {
                AndroidAutomationRecoveryGuard(
                    guardId,
                    executionId,
                    AutomationRuntimeWorkKind.TIMER,
                    backend,
                )
            }

            assertEquals(0, backend.pendingCalls)
            assertTrue(backend.scheduleAttempts.isEmpty())
            assertEquals(0, backend.cancelCalls)
        }
    }

    @Test
    fun armAcceptsMissingOrOwnedPendingAndProtectsTheSuppliedGeneration() {
        listOf(
            AutomationRecoveryGuardPending.Missing,
            AutomationRecoveryGuardPending.Owned(schedule()),
        ).forEach { prior ->
            val harness = Harness(prior)

            assertTrue(harness.guard.arm(ticket(generation = 3)))

            val successor = schedule(generation = 3, nonce = NEXT_NONCE)
            assertEquals(AutomationRecoveryGuardPending.Owned(successor), harness.backend.state)
            assertEquals(listOf(successor), harness.backend.scheduleAttempts)
            assertEquals(listOf("scheduled" to successor), harness.observations)
            assertEquals(1, harness.nonceRequests)
            assertEquals(0, harness.backend.cancelCalls)
        }
    }

    @Test
    fun armPreservesForeignOrMismatchedOwnedPendingWithoutCreatingANonce() {
        foreignPendingStates().forEach { prior ->
            val harness = Harness(prior)

            assertFalse(harness.guard.arm(ticket(generation = 2)))

            assertSame(prior, harness.backend.state)
            assertTrue(harness.backend.scheduleAttempts.isEmpty())
            assertEquals(0, harness.nonceRequests)
            assertEquals(0, harness.backend.cancelCalls)
            assertTrue(harness.observations.isEmpty())
        }
    }

    @Test
    fun armWrongTicketKindDoesNotEvenProbeTheBackend() {
        val harness = Harness()
        harness.backend.pendingFailure = IllegalStateException("must not be probed")

        assertFalse(harness.guard.arm(ticket(kind = AutomationRuntimeWorkKind.BOOT)))

        assertEquals(0, harness.backend.pendingCalls)
        assertTrue(harness.backend.scheduleAttempts.isEmpty())
        assertEquals(0, harness.nonceRequests)
        assertEquals(0, harness.backend.cancelCalls)
    }

    @Test
    fun armOwnershipReadFailureKeepsThePriorGuard() {
        val harness = Harness()
        val prior = harness.backend.state
        harness.backend.pendingFailure = IllegalStateException("platform lookup unavailable")

        assertFalse(harness.guard.arm(ticket(generation = 2)))

        assertSame(prior, harness.backend.state)
        assertTrue(harness.backend.scheduleAttempts.isEmpty())
        assertEquals(0, harness.nonceRequests)
        assertEquals(0, harness.backend.cancelCalls)
        assertTrue(harness.observations.isEmpty())
    }

    @Test
    fun rejectedOrThrowingReplacementDoesNotCancelPriorOrReportAcceptance() {
        listOf(false, true).forEach { throwBeforeAcceptance ->
            val harness = Harness()
            val prior = harness.backend.state
            harness.backend.acceptSchedule = false
            if (throwBeforeAcceptance) {
                harness.backend.scheduleFailure = IllegalStateException("schedule unavailable")
            }

            assertFalse(harness.guard.arm(ticket(generation = 2)))

            assertSame(prior, harness.backend.state)
            assertEquals(listOf(schedule(generation = 2, nonce = NEXT_NONCE)), harness.backend.scheduleAttempts)
            assertEquals(0, harness.backend.cancelCalls)
            assertTrue(harness.observations.isEmpty())
        }
    }

    @Test
    fun invalidGeneratedNonceNeverReachesPlatformReplacement() {
        val harness = Harness()
        val prior = harness.backend.state
        harness.generatedNonce = "1-1-1-1-1"

        assertFalse(harness.guard.arm(ticket(generation = 2)))

        assertSame(prior, harness.backend.state)
        assertTrue(harness.backend.scheduleAttempts.isEmpty())
        assertEquals(0, harness.backend.cancelCalls)
        assertTrue(harness.observations.isEmpty())
    }

    @Test
    fun passiveObserverFailureCannotTurnAcceptedReplacementIntoRejection() {
        val harness = Harness()
        harness.observerFailure = IllegalStateException("diagnostics unavailable")

        assertTrue(harness.guard.arm(ticket(generation = 2)))

        val successor = schedule(generation = 2, nonce = NEXT_NONCE)
        assertEquals(AutomationRecoveryGuardPending.Owned(successor), harness.backend.state)
        assertEquals(listOf(successor), harness.backend.scheduleAttempts)
        assertEquals(listOf("scheduled" to successor), harness.observations)
        assertEquals(0, harness.backend.cancelCalls)
    }

    @Test
    fun caughtUpFinalizationCancelsOnlyItsOwnedGuard() {
        val harness = Harness()
        harness.request()
        val registration = harness.start()
        assertTrue(harness.coordinator.installRecoveryGuard(registration, harness.guard::arm))
        assertEquals(
            AutomationRuntimeWorkCoordinator.CheckpointResult.ReadyToFinish,
            harness.coordinator.acknowledgeAndRecheck(registration),
        )

        assertEquals(
            AutomationRuntimeWorkCoordinator.FinalizationResult.Finished,
            harness.coordinator.finishIfCaughtUp(registration, harness.guard::cancelCaughtUp),
        )

        assertEquals(AutomationRecoveryGuardPending.Missing, harness.backend.state)
        assertEquals(1, harness.backend.cancelCalls)
        assertEquals(listOf("scheduled", "cancelled"), harness.observations.map { it.first })
        assertEquals(schedule(nonce = NEXT_NONCE), harness.observations.last().second)
        assertNull(harness.ledger.pending(AutomationRuntimeWorkKind.TIMER))
        assertEquals(1, harness.ledger.acknowledgements)
        assertFalse(harness.coordinator.isActive(EXECUTION_JOB_ID))
    }

    @Test
    fun cancelCaughtUpLeavesMissingForeignAndMismatchedPendingUntouched() {
        (foreignPendingStates() + AutomationRecoveryGuardPending.Missing).forEach { prior ->
            val harness = Harness(prior)

            harness.guard.cancelCaughtUp()

            assertSame(prior, harness.backend.state)
            assertEquals(0, harness.backend.cancelCalls)
            assertTrue(harness.backend.scheduleAttempts.isEmpty())
            assertTrue(harness.observations.isEmpty())
        }
    }

    @Test
    fun cancelProbeOrBackendFailureDoesNotScheduleOrClaimCancellation() {
        listOf(false, true).forEach { failDuringCancellation ->
            val harness = Harness()
            val prior = harness.backend.state
            if (failDuringCancellation) {
                harness.backend.cancelFailure = IllegalStateException("cancel unavailable")
            } else {
                harness.backend.pendingFailure = IllegalStateException("lookup unavailable")
            }

            harness.guard.cancelCaughtUp()

            assertSame(prior, harness.backend.state)
            assertEquals(if (failDuringCancellation) 1 else 0, harness.backend.cancelCalls)
            assertTrue(harness.backend.scheduleAttempts.isEmpty())
            assertTrue(harness.observations.isEmpty())
        }
    }

    @Test
    fun passiveObserverFailureCannotResurrectASuccessfullyCancelledGuard() {
        val harness = Harness()
        harness.observerFailure = IllegalStateException("diagnostics unavailable")

        harness.guard.cancelCaughtUp()

        assertEquals(AutomationRecoveryGuardPending.Missing, harness.backend.state)
        assertEquals(1, harness.backend.cancelCalls)
        assertTrue(harness.backend.scheduleAttempts.isEmpty())
        assertEquals(listOf("cancelled" to schedule()), harness.observations)
    }

    @Test
    fun receiveWrongGuardIdExecutionIdOrKindDoesNotProbeOrDispatch() {
        listOf(
            schedule().copy(jobId = GUARD_JOB_ID + 1),
            schedule().copy(executionJobId = EXECUTION_JOB_ID + 1),
            schedule().copy(kind = AutomationRuntimeWorkKind.BOOT),
        ).forEach { delivered ->
            val harness = Harness()
            val pending = harness.request()
            harness.backend.pendingFailure = IllegalStateException("must not be probed")

            assertEquals(AutomationRuntimeWorkCoordinator.RecoveryGuardResult.STALE, harness.receive(delivered))

            assertEquals(0, harness.backend.pendingCalls)
            assertNoReplacementOrExecution(harness)
            assertWorkUnchanged(harness, pending)
        }
    }

    @Test
    fun receiveSupersededNonceOrGenerationCannotReplaceCurrentGuard() {
        listOf(
            schedule(nonce = NEXT_NONCE),
            schedule(generation = 2),
        ).forEach { current ->
            val prior = AutomationRecoveryGuardPending.Owned(current)
            val harness = Harness(prior)
            val pending = harness.request()

            assertEquals(AutomationRuntimeWorkCoordinator.RecoveryGuardResult.STALE, harness.receive())

            assertSame(prior, harness.backend.state)
            assertNoReplacementOrExecution(harness)
            assertWorkUnchanged(harness, pending)
        }
    }

    @Test
    fun receiveMissingForeignOrMismatchedPendingIsProvenStale() {
        (foreignPendingStates() + AutomationRecoveryGuardPending.Missing).forEach { prior ->
            val harness = Harness(prior)
            val pending = harness.request()

            assertEquals(AutomationRuntimeWorkCoordinator.RecoveryGuardResult.STALE, harness.receive())

            assertSame(prior, harness.backend.state)
            assertNoReplacementOrExecution(harness)
            assertWorkUnchanged(harness, pending)
        }
    }

    @Test
    fun matchingScheduleCannotInventAFutureWorkGeneration() {
        val delivered = schedule(generation = 2)
        val prior = AutomationRecoveryGuardPending.Owned(delivered)
        val harness = Harness(prior)
        val pending = harness.request()

        assertEquals(AutomationRuntimeWorkCoordinator.RecoveryGuardResult.STALE, harness.receive(delivered))

        assertSame(prior, harness.backend.state)
        assertNoReplacementOrExecution(harness, received = delivered)
        assertWorkUnchanged(harness, pending)
    }

    @Test
    fun currentGuardWithoutPendingWorkDoesNotCreateARequestOrDispatch() {
        val harness = Harness()

        assertEquals(AutomationRuntimeWorkCoordinator.RecoveryGuardResult.NO_PENDING_WORK, harness.receive())

        assertNoReplacementOrExecution(harness, received = schedule())
        assertEquals(0, harness.ledger.records)
        assertEquals(0, harness.ledger.acknowledgements)
        assertTrue(harness.ledger.allPending().isEmpty())
    }

    @Test
    fun liveRegistrationRearmsLatestCoalescedTicketWithoutReplacingExecution() {
        val harness = Harness()
        harness.request()
        val registration = harness.start()
        val latest = harness.request()

        assertEquals(AutomationRuntimeWorkCoordinator.RecoveryGuardResult.ACTIVE_REARMED, harness.receive())

        val successor = schedule(generation = 2, nonce = NEXT_NONCE)
        assertEquals(AutomationRecoveryGuardPending.Owned(successor), harness.backend.state)
        assertEquals(listOf(successor), harness.backend.scheduleAttempts)
        assertTrue(harness.executions.isEmpty())
        assertEquals(1, harness.requestSchedules)
        assertTrue(harness.coordinator.isCurrent(registration))
        assertEquals(1L, registration.ticket.generation)
        assertWorkUnchanged(harness, latest, expectedRecords = 2)
    }

    @Test
    fun restoredInactiveCoordinatorInstallsSuccessorBeforeSchedulingExistingWork() {
        val harness = Harness()
        val pending = harness.request()
        harness.start()
        val restored = AutomationRuntimeWorkCoordinator(harness.ledger)
        val restoredGuard = AndroidAutomationRecoveryGuard(
            GUARD_JOB_ID,
            EXECUTION_JOB_ID,
            AutomationRuntimeWorkKind.TIMER,
            harness.backend,
            nonceSource = { NEXT_NONCE },
        )
        val executions = mutableListOf<AutomationRuntimeWorkTicket>()

        val result = restoredGuard.receive(schedule(), restored) { ticket ->
            harness.backend.trace += "execution"
            assertEquals(
                AutomationRecoveryGuardPending.Owned(schedule(nonce = NEXT_NONCE)),
                harness.backend.state,
            )
            assertEquals(pending, ticket)
            executions += ticket
            true
        }

        assertEquals(AutomationRuntimeWorkCoordinator.RecoveryGuardResult.EXECUTION_SCHEDULED, result)
        assertEquals(listOf(pending), executions)
        assertEquals(listOf("pending", "pending", "schedule", "execution"), harness.backend.trace)
        assertFalse(restored.isActive(EXECUTION_JOB_ID))
        assertEquals(0, harness.backend.cancelCalls)
        assertWorkUnchanged(harness, pending)
    }

    @Test
    fun rearmRejectionOrFailureNeverDispatchesAndPreservesPriorWakeup() {
        listOf(false, true).forEach { throwBeforeAcceptance ->
            val harness = Harness()
            val pending = harness.request()
            val prior = harness.backend.state
            harness.backend.acceptSchedule = false
            if (throwBeforeAcceptance) {
                harness.backend.scheduleFailure = IllegalStateException("schedule unavailable")
            }

            assertEquals(AutomationRuntimeWorkCoordinator.RecoveryGuardResult.REARM_REJECTED, harness.receive())

            assertSame(prior, harness.backend.state)
            assertEquals(1, harness.backend.scheduleAttempts.size)
            assertTrue(harness.executions.isEmpty())
            assertEquals(0, harness.backend.cancelCalls)
            assertEquals(listOf("received" to schedule()), harness.observations)
            assertWorkUnchanged(harness, pending)
        }
    }

    @Test
    fun executionRejectionOrExceptionKeepsTheAlreadyAcceptedSuccessor() {
        listOf(false, true).forEach { throwBeforeAcceptance ->
            val harness = Harness()
            val pending = harness.request()
            harness.acceptExecution = false
            if (throwBeforeAcceptance) {
                harness.executionFailure = IllegalStateException("execution scheduling unavailable")
            }

            assertEquals(
                AutomationRuntimeWorkCoordinator.RecoveryGuardResult.REARMED_EXECUTION_REJECTED,
                harness.receive(),
            )

            val successor = schedule(nonce = NEXT_NONCE)
            assertEquals(AutomationRecoveryGuardPending.Owned(successor), harness.backend.state)
            assertEquals(listOf(successor), harness.backend.scheduleAttempts)
            assertEquals(listOf(pending), harness.executions)
            assertEquals(0, harness.backend.cancelCalls)
            assertWorkUnchanged(harness, pending)
        }
    }

    @Test
    fun rearmObserverFailureDoesNotSuppressExecutionOrRetryTheOldGuard() {
        val harness = Harness()
        val pending = harness.request()
        harness.observerFailure = IllegalStateException("diagnostics unavailable")

        assertEquals(AutomationRuntimeWorkCoordinator.RecoveryGuardResult.EXECUTION_SCHEDULED, harness.receive())

        assertEquals(
            AutomationRecoveryGuardPending.Owned(schedule(nonce = NEXT_NONCE)),
            harness.backend.state,
        )
        assertEquals(listOf(pending), harness.executions)
        assertEquals(1, harness.backend.scheduleAttempts.size)
        assertEquals(0, harness.backend.cancelCalls)
        assertEquals(listOf("received", "scheduled"), harness.observations.map { it.first })
        assertWorkUnchanged(harness, pending)
    }

    @Test
    fun scheduleOwnershipReadFailureIsRetryableRatherThanClaimingProvenStaleness() {
        val harness = Harness()
        val pending = harness.request()
        val prior = harness.backend.state
        harness.backend.pendingFailure = IllegalStateException("platform ownership not readable")

        assertEquals(AutomationRuntimeWorkCoordinator.RecoveryGuardResult.REARM_REJECTED, harness.receive())
        assertSame(prior, harness.backend.state)
        assertNoReplacementOrExecution(harness)
        assertWorkUnchanged(harness, pending)

        harness.backend.pendingFailure = null
        harness.backend.state = AutomationRecoveryGuardPending.Missing

        assertEquals(AutomationRuntimeWorkCoordinator.RecoveryGuardResult.STALE, harness.receive())
        assertNoReplacementOrExecution(harness)
        assertWorkUnchanged(harness, pending)
    }

    @Test
    fun duplicateDeliveryCannotOverwriteAcceptedSuccessorOrScheduleExecutionAgain() {
        val harness = Harness()
        val pending = harness.request()
        assertEquals(AutomationRuntimeWorkCoordinator.RecoveryGuardResult.EXECUTION_SCHEDULED, harness.receive())
        val accepted = harness.backend.state

        assertEquals(AutomationRuntimeWorkCoordinator.RecoveryGuardResult.STALE, harness.receive())

        assertSame(accepted, harness.backend.state)
        assertEquals(1, harness.backend.scheduleAttempts.size)
        assertEquals(listOf(pending), harness.executions)
        assertEquals(0, harness.backend.cancelCalls)
        assertWorkUnchanged(harness, pending)
    }

    private fun assertNoReplacementOrExecution(
        harness: Harness,
        received: AutomationRecoveryGuardSchedule? = null,
    ) {
        assertTrue(harness.backend.scheduleAttempts.isEmpty())
        assertTrue(harness.executions.isEmpty())
        val expected: List<Pair<String, AutomationRecoveryGuardSchedule>> =
            received?.let { listOf("received" to it) } ?: emptyList()
        assertEquals(expected, harness.observations)
        assertEquals(0, harness.backend.cancelCalls)
    }

    private fun assertWorkUnchanged(
        harness: Harness,
        pending: AutomationRuntimeWorkTicket,
        expectedRecords: Int = 1,
    ) {
        assertEquals(pending, harness.ledger.pending(pending.kind))
        assertEquals(listOf(pending), harness.ledger.allPending())
        assertEquals(expectedRecords, harness.ledger.records)
        assertEquals(0, harness.ledger.acknowledgements)
    }

    private class Harness(
        initialPending: AutomationRecoveryGuardPending = AutomationRecoveryGuardPending.Owned(schedule()),
    ) {
        val backend = FakeBackend(initialPending)
        val ledger = MemoryLedger()
        val coordinator = AutomationRuntimeWorkCoordinator(ledger)
        val observations = mutableListOf<Pair<String, AutomationRecoveryGuardSchedule>>()
        val executions = mutableListOf<AutomationRuntimeWorkTicket>()
        var generatedNonce = NEXT_NONCE
        var nonceRequests = 0
        var requestSchedules = 0
        var observerFailure: RuntimeException? = null
        var executionFailure: RuntimeException? = null
        var acceptExecution = true
        val guard = AndroidAutomationRecoveryGuard(
            GUARD_JOB_ID,
            EXECUTION_JOB_ID,
            AutomationRuntimeWorkKind.TIMER,
            backend,
            nonceSource = {
                nonceRequests += 1
                generatedNonce
            },
            observer = { event, schedule ->
                observations += event to schedule
                observerFailure?.let { throw it }
            },
        )

        fun request(): AutomationRuntimeWorkTicket {
            check(coordinator.request(EXECUTION_JOB_ID, AutomationRuntimeWorkKind.TIMER, null) {
                requestSchedules += 1
                true
            })
            return checkNotNull(ledger.pending(AutomationRuntimeWorkKind.TIMER))
        }

        fun start(): AutomationRuntimeWorkCoordinator.Registration =
            (coordinator.start(EXECUTION_JOB_ID, AutomationRuntimeWorkKind.TIMER)
                as AutomationRuntimeWorkCoordinator.StartResult.Started).registration

        fun receive(
            delivered: AutomationRecoveryGuardSchedule = schedule(),
        ): AutomationRuntimeWorkCoordinator.RecoveryGuardResult =
            guard.receive(delivered, coordinator) { ticket ->
                backend.trace += "execution"
                executions += ticket
                executionFailure?.let { throw it }
                acceptExecution
            }
    }

    private class FakeBackend(
        var state: AutomationRecoveryGuardPending = AutomationRecoveryGuardPending.Missing,
    ) : AutomationRecoveryGuardBackend {
        val trace = mutableListOf<String>()
        val scheduleAttempts = mutableListOf<AutomationRecoveryGuardSchedule>()
        var pendingCalls = 0
        var cancelCalls = 0
        var acceptSchedule = true
        var pendingFailure: RuntimeException? = null
        var scheduleFailure: RuntimeException? = null
        var cancelFailure: RuntimeException? = null

        override fun pending(): AutomationRecoveryGuardPending {
            trace += "pending"
            pendingCalls += 1
            pendingFailure?.let { throw it }
            return state
        }

        override fun schedule(schedule: AutomationRecoveryGuardSchedule): Boolean {
            trace += "schedule"
            scheduleAttempts += schedule
            // Model a rejection/exception before platform acceptance, retaining the old job.
            scheduleFailure?.let { throw it }
            if (acceptSchedule) state = AutomationRecoveryGuardPending.Owned(schedule)
            return acceptSchedule
        }

        override fun cancel() {
            trace += "cancel"
            cancelCalls += 1
            cancelFailure?.let { throw it }
            state = AutomationRecoveryGuardPending.Missing
        }
    }

    private class MemoryLedger : AutomationRuntimeWorkLedger {
        private data class State(
            val requested: Long = 0,
            val acknowledged: Long = 0,
            val observedAt: Instant? = null,
        )

        private val states = linkedMapOf<AutomationRuntimeWorkKind, State>()
        var records = 0
            private set
        var acknowledgements = 0
            private set

        override fun record(kind: AutomationRuntimeWorkKind, observedAt: Instant?): AutomationRuntimeWorkTicket {
            val previous = states[kind] ?: State()
            val priorTime = previous.observedAt.takeIf { previous.requested > previous.acknowledged }
            val next = previous.copy(
                requested = previous.requested + 1,
                observedAt = listOfNotNull(priorTime, observedAt).maxOrNull(),
            )
            states[kind] = next
            records += 1
            return AutomationRuntimeWorkTicket(kind, next.requested, next.observedAt)
        }

        override fun pending(kind: AutomationRuntimeWorkKind): AutomationRuntimeWorkTicket? {
            val state = states[kind] ?: return null
            return if (state.requested > state.acknowledged) {
                AutomationRuntimeWorkTicket(kind, state.requested, state.observedAt)
            } else {
                null
            }
        }

        override fun allPending(): List<AutomationRuntimeWorkTicket> = states.keys.mapNotNull(::pending)

        override fun acknowledge(ticket: AutomationRuntimeWorkTicket): AutomationRuntimeWorkTicket? {
            val current = checkNotNull(states[ticket.kind])
            check(ticket.generation <= current.requested)
            states[ticket.kind] = current.copy(acknowledged = maxOf(current.acknowledged, ticket.generation))
            acknowledgements += 1
            return pending(ticket.kind)
        }
    }

    private companion object {
        const val GUARD_JOB_ID = 0x4854F201
        const val EXECUTION_JOB_ID = 0x4854F101
        const val ORIGINAL_NONCE = "12345678-1234-4234-8234-123456789abc"
        const val NEXT_NONCE = "12345678-1234-4234-8234-123456789abd"

        fun schedule(
            kind: AutomationRuntimeWorkKind = AutomationRuntimeWorkKind.TIMER,
            generation: Long = 1,
            nonce: String = ORIGINAL_NONCE,
        ): AutomationRecoveryGuardSchedule = AutomationRecoveryGuardSchedule(
            GUARD_JOB_ID,
            EXECUTION_JOB_ID,
            kind,
            generation,
            nonce,
        )

        fun ticket(
            kind: AutomationRuntimeWorkKind = AutomationRuntimeWorkKind.TIMER,
            generation: Long = 1,
        ): AutomationRuntimeWorkTicket = AutomationRuntimeWorkTicket(
            kind,
            generation,
            Instant.parse("2026-08-27T10:00:00Z"),
        )

        fun invalidJobIds(): List<Pair<Int, Int>> = listOf(0 to 1, -1 to 1, 1 to 0, 1 to -1, 1 to 1)

        fun foreignPendingStates(): List<AutomationRecoveryGuardPending> = listOf(
            AutomationRecoveryGuardPending.Foreign,
            AutomationRecoveryGuardPending.Owned(schedule().copy(jobId = GUARD_JOB_ID + 1)),
            AutomationRecoveryGuardPending.Owned(schedule().copy(executionJobId = EXECUTION_JOB_ID + 1)),
            AutomationRecoveryGuardPending.Owned(schedule().copy(kind = AutomationRuntimeWorkKind.BOOT)),
        )
    }
}
