package ai.hans.standard.setup

import ai.hans.standard.integration.BundledSetupBootstrapStatus
import ai.hans.standard.integration.ClientRuntimePhase
import ai.hans.standard.integration.ClientSessionPhase
import ai.hans.standard.integration.CodexDispatchAttemptResult
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HansSetupHandoffCoordinatorTest {
    @Test
    fun orderedAckReturnsBeforeAnyDeferredObserverCanDriveSetup() {
        val queued = ArrayDeque<() -> Unit>()
        val events = mutableListOf<String>()
        val deferral = HansSetupHandoffSignalDeferral(
            enqueue = { task ->
                queued.addLast(task)
                true
            },
            publish = { events += "observer_drive" },
        )

        events += "ack_set"
        assertTrue(deferral.scheduleAfterAcknowledgement())
        events += "receiver_returned"

        assertEquals(listOf("ack_set", "receiver_returned"), events)
        assertEquals(1, queued.size)
        queued.removeFirst().invoke()
        assertEquals(listOf("ack_set", "receiver_returned", "observer_drive"), events)
    }

    @Test
    fun rejectedSignalQueueNeverRunsObserverInsideBroadcastDeadline() {
        var observerRan = false
        val deferral = HansSetupHandoffSignalDeferral(
            enqueue = { false },
            publish = { observerRan = true },
        )

        assertFalse(deferral.scheduleAfterAcknowledgement())
        assertFalse(observerRan)
    }

    @Test
    fun dispatchGateAllowsReadyNewTurnButNeverBusySteer() {
        assertTrue(
            HansSetupHandoffDispatchGate.allowsNewTurn(
                ClientRuntimePhase.READY,
                ClientSessionPhase.READY,
                BundledSetupBootstrapStatus.READY,
                selectionAvailable = true,
                setupSkillAvailable = true,
            ),
        )
        assertFalse(
            HansSetupHandoffDispatchGate.allowsNewTurn(
                ClientRuntimePhase.READY,
                ClientSessionPhase.BUSY,
                BundledSetupBootstrapStatus.READY,
                selectionAvailable = true,
                setupSkillAvailable = true,
            ),
        )
    }

    @Test
    fun livePrerequisitesMissingDespiteReadySnapshotDoNotReserveUnsentSetup() {
        listOf(true to false, false to true).forEach { (selectionAvailable, setupSkillAvailable) ->
            val coordinator = coordinator(MemoryHandoffStorage())
            val install = command("f508661b-6aee-4cc8-8992-f405a0c3fc94")
            coordinator.register(install)
            // The bootstrap snapshot is READY but the live client/skill disappeared before
            // dispatch. Both values must be captured in the gate before its reservation.
            val ready = HansSetupHandoffDispatchGate.allowsNewTurn(
                ClientRuntimePhase.READY,
                ClientSessionPhase.READY,
                BundledSetupBootstrapStatus.READY,
                selectionAvailable = selectionAvailable,
                setupSkillAvailable = setupSkillAvailable,
            )
            assertType<HansSetupHandoffAction.None>(
                coordinator.nextAction { environment(ready = ready) },
            )
            assertEquals(HansSetupHandoffPhase.QUEUED, coordinator.snapshot().records.single().phase)
            assertNull(coordinator.snapshot().records.single().reservationOwnerId)

            val recovered = HansSetupHandoffDispatchGate.allowsNewTurn(
                ClientRuntimePhase.READY,
                ClientSessionPhase.READY,
                BundledSetupBootstrapStatus.READY,
                selectionAvailable = true,
                setupSkillAvailable = true,
            )
            val dispatch = assertType<HansSetupHandoffAction.Dispatch>(
                coordinator.nextAction { environment(ready = recovered) },
            )
            assertEquals(install.clientMessageId(), dispatch.record.clientUserMessageId)
            assertType<HansSetupHandoffAction.None>(
                coordinator.nextAction { environment(ready = recovered) },
            )
        }
    }

    @Test
    fun contractRejectsMalformedImplicitOrUnsupportedRequests() {
        val id = "84e3b844-47da-42eb-b8f0-bda209b8dd78"
        assertEquals(
            command(id),
            HansSetupHandoffContract.parseFields(
                HansSetupHandoffContract.ACTION_CONTINUE_SETUP,
                true,
                1,
                id,
                "install",
            ),
        )
        assertNull(
            HansSetupHandoffContract.parseFields(
                HansSetupHandoffContract.ACTION_CONTINUE_SETUP,
                false,
                1,
                id,
                "install",
            ),
        )
        assertNull(
            HansSetupHandoffContract.parseFields(
                HansSetupHandoffContract.ACTION_CONTINUE_SETUP,
                true,
                2,
                id,
                "install",
            ),
        )
        assertNull(
            HansSetupHandoffContract.parseFields(
                HansSetupHandoffContract.ACTION_CONTINUE_SETUP,
                true,
                1,
                "not-a-uuid",
                "install",
            ),
        )
        assertNull(
            HansSetupHandoffContract.parseFields(
                HansSetupHandoffContract.ACTION_CONTINUE_SETUP,
                true,
                1,
                id.uppercase(),
                "install",
            ),
        )
        assertNull(
            HansSetupHandoffContract.parseFields(
                HansSetupHandoffContract.ACTION_CONTINUE_SETUP,
                true,
                "1",
                id,
                "install",
            ),
        )
        assertNull(
            HansSetupHandoffContract.parseFields(
                HansSetupHandoffContract.ACTION_CONTINUE_SETUP,
                true,
                1,
                id,
                "root",
            ),
        )
    }

    @Test
    fun preLoginRequestWaitsThenDispatchesExactlyOnceWhenReady() {
        val storage = MemoryHandoffStorage()
        val coordinator = coordinator(storage)
        val request = command("84e3b844-47da-42eb-b8f0-bda209b8dd78")

        assertEquals(
            HansSetupHandoffRegistration.ACCEPTED,
            coordinator.register(request).registration,
        )
        assertType<HansSetupHandoffAction.None>(
            coordinator.nextAction { environment(ready = false) },
        )
        val dispatch = assertType<HansSetupHandoffAction.Dispatch>(
            coordinator.nextAction { environment(ready = true) },
        )
        assertEquals(request.clientMessageId(), dispatch.record.clientUserMessageId)
        assertType<HansSetupHandoffAction.None>(
            coordinator.nextAction { environment(ready = true) },
        )
    }

    @Test
    fun freshRepairIdBeforeLoginDoesNotSendSecondSetupPromptBeforeFirstAnswer() {
        val storage = MemoryHandoffStorage()
        val coordinator = coordinator(storage)
        val install = command("971e52a0-d4eb-4a7c-8767-e52718d147a4")
        val repair = command(
            "b33029b5-2faf-49d0-9dd8-79ce7282d42a",
            HansSetupHandoffReason.REPAIR,
        )
        coordinator.register(install)
        assertType<HansSetupHandoffAction.None>(
            coordinator.nextAction { environment(ready = false) },
        )
        coordinator.register(repair)

        val dispatch = assertType<HansSetupHandoffAction.Dispatch>(
            coordinator.nextAction { environment(ready = true) },
        )
        assertTrue(
            coordinator.recordDispatchResult(dispatch.record, CodexDispatchAttemptResult.Accepted(dispatch.record.clientUserMessageId)),
        )
        val started = assertType<HansSetupHandoffAction.StartLocalSetup>(
            coordinator.nextAction { record ->
                environment(
                    ready = true,
                    outbound = if (record.clientUserMessageId == dispatch.record.clientUserMessageId) {
                        HansSetupHandoffOutboundStatus.SENT
                    } else {
                        HansSetupHandoffOutboundStatus.ABSENT
                    },
                    setupComplete = false,
                )
            },
        )
        assertTrue(coordinator.markSetupStarted(started.record))

        // The first onboarding question is waiting for the user: incomplete is not unstarted.
        assertType<HansSetupHandoffAction.None>(
            coordinator.nextAction { environment(ready = true, setupComplete = false) },
        )
    }

    @Test
    fun concurrentCoordinatorInstancesRegisterAndReserveOnlyOnce() {
        val storage = MemoryHandoffStorage()
        val request = command("9981d495-9bbb-4ef4-9c5b-7ddb76b48df4")
        val coordinators = listOf(coordinator(storage), coordinator(storage))
        val registrations = runConcurrently(coordinators) { current ->
            current.register(request).registration
        }

        assertEquals(
            setOf(
                HansSetupHandoffRegistration.ACCEPTED,
                HansSetupHandoffRegistration.DUPLICATE,
            ),
            registrations.toSet(),
        )
        val actions = runConcurrently(coordinators) { current ->
            current.nextAction { environment(ready = true) }
        }
        assertEquals(1, actions.count { it is HansSetupHandoffAction.Dispatch })
        assertEquals(1, actions.count { it is HansSetupHandoffAction.None })
        assertEquals(1, storage.read().records.size)
    }

    @Test
    fun queuedBeforeDispatchSurvivesProcessDeathAndUsesNewOwner() {
        val storage = MemoryHandoffStorage()
        val request = command("c9547ca7-3e6d-4de8-b881-4bf7c9c55bba")
        coordinator(storage, OWNER_A).register(request)

        val recreated = coordinator(storage, OWNER_B)
        val dispatch = assertType<HansSetupHandoffAction.Dispatch>(
            recreated.nextAction { environment(ready = true) },
        )

        assertEquals(OWNER_B, dispatch.record.reservationOwnerId)
        assertEquals(HansSetupHandoffPhase.DISPATCH_RESERVED, dispatch.record.phase)
    }

    @Test
    fun processDeathAfterReservationBeforeDispatchSurfacesRecoveryWithoutReplay() {
        val storage = MemoryHandoffStorage()
        val request = command("90ad589e-39ef-46d5-ad77-5b284e60b828")
        val first = coordinator(storage, OWNER_A)
        first.register(request)
        val reserved = assertType<HansSetupHandoffAction.Dispatch>(
            first.nextAction { environment(ready = true) },
        )

        val recreated = coordinator(storage, OWNER_B)
        val recovery = assertType<HansSetupHandoffAction.RecoveryRequired>(
            recreated.nextAction { environment(ready = true) },
        )

        assertEquals(reserved.record.clientUserMessageId, recovery.record.clientUserMessageId)
        assertEquals(HansSetupHandoffPhase.RECOVERY_REQUIRED, recovery.record.phase)
        assertFalse(
            recreated.nextAction { environment(ready = true) } is HansSetupHandoffAction.Dispatch,
        )
    }

    @Test
    fun actualAuthAndBootstrapPhasesWaitThenReadyDispatchesOnce() {
        val storage = MemoryHandoffStorage()
        val coordinator = coordinator(storage)
        coordinator.register(command("27bfcbcf-4ec3-4877-b7f7-6b10a05282c4"))

        val authRequired = HansSetupHandoffDispatchGate.allowsNewTurn(
            runtimePhase = ClientRuntimePhase.READY,
            sessionPhase = ClientSessionPhase.AUTH_REQUIRED,
            bootstrapStatus = BundledSetupBootstrapStatus.PREPARING,
            selectionAvailable = false,
            setupSkillAvailable = false,
        )
        assertType<HansSetupHandoffAction.None>(
            coordinator.nextAction { environment(ready = authRequired) },
        )

        val ready = HansSetupHandoffDispatchGate.allowsNewTurn(
            runtimePhase = ClientRuntimePhase.READY,
            sessionPhase = ClientSessionPhase.READY,
            bootstrapStatus = BundledSetupBootstrapStatus.READY,
            selectionAvailable = true,
            setupSkillAvailable = true,
        )
        assertType<HansSetupHandoffAction.Dispatch>(
            coordinator.nextAction { environment(ready = ready) },
        )
        assertType<HansSetupHandoffAction.None>(
            coordinator.nextAction { environment(ready = ready) },
        )
    }

    @Test
    fun processDeathAfterAcceptanceSurfacesRecoveryAndRepairSupersedesWithoutReplay() {
        val storage = MemoryHandoffStorage()
        val old = command("d4adc724-4560-4c4f-bc44-d48da7c11cef")
        val first = coordinator(storage, OWNER_A)
        first.register(old)
        val oldDispatch = assertType<HansSetupHandoffAction.Dispatch>(
            first.nextAction { environment(ready = true) },
        )
        assertTrue(first.recordDispatchResult(oldDispatch.record, CodexDispatchAttemptResult.Accepted(old.clientMessageId())))

        val recreated = coordinator(storage, OWNER_B)
        val recovery = assertType<HansSetupHandoffAction.RecoveryRequired>(
            recreated.nextAction { environment(ready = true) },
        )
        assertEquals(old.handoffId, recovery.record.command.handoffId)
        assertEquals(HansSetupHandoffPhase.RECOVERY_REQUIRED, recovery.record.phase)
        assertEquals(
            HansSetupHandoffRegistration.DUPLICATE,
            recreated.register(old).registration,
        )

        val repair = command(
            "457d5bda-052f-4e70-b1a6-d95074fa43a3",
            HansSetupHandoffReason.REPAIR,
        )
        recreated.register(repair)
        val repairDispatch = assertType<HansSetupHandoffAction.Dispatch>(
            recreated.nextAction { environment(ready = true) },
        )

        assertEquals(repair.clientMessageId(), repairDispatch.record.clientUserMessageId)
        assertEquals(
            listOf(HansSetupHandoffPhase.REJECTED, HansSetupHandoffPhase.DISPATCH_RESERVED),
            recreated.snapshot().records.map(HansSetupHandoffRecord::phase),
        )
    }

    @Test
    fun receiverFirstRepairAfterReservedOwnerDeathDoesNotRequireAnotherRepair() {
        assertReceiverFirstRepairRecoversForeignReservation(HansSetupHandoffPhase.DISPATCH_RESERVED)
    }

    @Test
    fun receiverFirstRepairAfterAcceptedOwnerDeathDoesNotRequireAnotherRepair() {
        assertReceiverFirstRepairRecoversForeignReservation(HansSetupHandoffPhase.DISPATCH_ACCEPTED)
    }

    @Test
    fun receiverRetryOfPreCrashRepairAliasNeverAuthorizesRecoveryReplay() {
        listOf(HansSetupHandoffPhase.DISPATCH_RESERVED, HansSetupHandoffPhase.DISPATCH_ACCEPTED).forEach { phase ->
            val earlyRepair = command("94ce7752-2d15-4322-8b8b-edb36607f4bc", HansSetupHandoffReason.REPAIR)
            val original = record(command("6f02a3d3-13ab-41f4-80f6-63592c80ef27"), phase, 1)
                .copy(aliases = listOf(earlyRepair))
            val storage = MemoryHandoffStorage(HansSetupHandoffDocument(listOf(original)))
            val restarted = coordinator(storage, OWNER_B)

            assertEquals(HansSetupHandoffRegistration.DUPLICATE, restarted.register(earlyRepair).registration)
            assertEquals(
                HansSetupHandoffRegistration.CONFLICT,
                restarted.register(earlyRepair.copy(reason = HansSetupHandoffReason.INSTALL)).registration,
            )
            val recovery = assertType<HansSetupHandoffAction.RecoveryRequired>(
                restarted.nextAction { environment(ready = true) },
            )
            assertEquals(original.clientUserMessageId, recovery.record.clientUserMessageId)
            assertEquals(listOf(earlyRepair), recovery.record.aliases)
            assertEquals(1, restarted.snapshot().records.size)
        }
    }

    @Test
    fun receiverFirstInstallDoesNotAuthorizeReplayOfForeignReservation() {
        val earlyRepair = command("b333d7d3-a1fa-4eb7-b3a5-5dfd60024786", HansSetupHandoffReason.REPAIR)
        val original = record(
            command("10a202d1-5a4e-42c3-bdb3-97c5d5fca48f"),
            HansSetupHandoffPhase.DISPATCH_ACCEPTED,
            1,
        ).copy(aliases = listOf(earlyRepair))
        val storage = MemoryHandoffStorage(HansSetupHandoffDocument(listOf(original)))
        val restarted = coordinator(storage, OWNER_B)
        val install = command("853d1a59-f1dc-4cfc-b07c-414e19c1ad94")
        restarted.register(install)

        val recovery = assertType<HansSetupHandoffAction.RecoveryRequired>(
            restarted.nextAction { environment(ready = true) },
        )
        assertEquals(original.clientUserMessageId, recovery.record.clientUserMessageId)
        assertEquals(listOf(earlyRepair), recovery.record.aliases)
        assertEquals(HansSetupHandoffPhase.QUEUED, storage.read().record(install.handoffId).phase)
        assertType<HansSetupHandoffAction.RecoveryRequired>(
            restarted.nextAction { environment(ready = true) },
        )
    }

    @Test
    fun receiverFirstRepairWaitsForExistingPendingSendThenUsesItsExactReceipt() {
        listOf(HansSetupHandoffPhase.DISPATCH_RESERVED, HansSetupHandoffPhase.DISPATCH_ACCEPTED).forEach { phase ->
            val original = record(command("47cb035b-dafd-4a88-94dc-f122c64c0777"), phase, 1)
            val storage = MemoryHandoffStorage(HansSetupHandoffDocument(listOf(original)))
            val restarted = coordinator(storage, OWNER_B)
            val repair = command("e2f45592-2d78-4190-bd8a-2db8f09b4a78", HansSetupHandoffReason.REPAIR)
            restarted.register(repair)

            repeat(2) {
                assertType<HansSetupHandoffAction.None>(
                    restarted.nextAction { current ->
                        environment(
                            ready = true,
                            outbound = if (current.clientUserMessageId == original.clientUserMessageId) {
                                HansSetupHandoffOutboundStatus.PENDING
                            } else {
                                HansSetupHandoffOutboundStatus.ABSENT
                            },
                        )
                    },
                )
            }
            assertEquals(HansSetupHandoffPhase.QUEUED, storage.read().record(repair.handoffId).phase)
            val start = assertType<HansSetupHandoffAction.StartLocalSetup>(
                restarted.nextAction { current ->
                    environment(
                        ready = true,
                        outbound = if (current.clientUserMessageId == original.clientUserMessageId) {
                            HansSetupHandoffOutboundStatus.SENT
                        } else {
                            HansSetupHandoffOutboundStatus.ABSENT
                        },
                    )
                },
            )
            assertEquals(original.clientUserMessageId, start.record.clientUserMessageId)
            assertEquals(listOf(repair), start.record.aliases)
            assertTrue(restarted.markSetupStarted(start.record))
            assertType<HansSetupHandoffAction.None>(restarted.nextAction { environment(ready = true) })
        }
    }

    @Test
    fun receiverFirstRepairWithLateCanonicalSentCoalescesWithoutSecondPrompt() {
        listOf(HansSetupHandoffPhase.DISPATCH_RESERVED, HansSetupHandoffPhase.DISPATCH_ACCEPTED).forEach { phase ->
            val earlyRepair = command("20948c87-a709-4229-a252-0f0960c3aeaa", HansSetupHandoffReason.REPAIR)
            val original = record(command("b00bedf8-68ec-4f73-b8ee-d64a56e9cbfd"), phase, 1)
                .copy(aliases = listOf(earlyRepair))
            val storage = MemoryHandoffStorage(HansSetupHandoffDocument(listOf(original)))
            val restarted = coordinator(storage, OWNER_B)
            val repair = command("014388f3-87a3-4e47-b18f-3c826fd6e778", HansSetupHandoffReason.REPAIR)
            restarted.register(repair)

            val start = assertType<HansSetupHandoffAction.StartLocalSetup>(
                restarted.nextAction { current ->
                    environment(
                        ready = true,
                        outbound = if (current.clientUserMessageId == original.clientUserMessageId) {
                            HansSetupHandoffOutboundStatus.SENT
                        } else {
                            HansSetupHandoffOutboundStatus.ABSENT
                        },
                    )
                },
            )
            assertEquals(original.clientUserMessageId, start.record.clientUserMessageId)
            assertEquals(listOf(earlyRepair, repair), start.record.aliases)
            assertTrue(restarted.markSetupStarted(start.record))
            assertEquals(HansSetupHandoffRegistration.DUPLICATE, restarted.register(repair).registration)
            assertType<HansSetupHandoffAction.None>(restarted.nextAction { environment(ready = true) })
        }
    }

    @Test
    fun receiverFirstRepairAfterProvenPreTransportRejectionKeepsOneSafeQueuedRequest() {
        val storage = MemoryHandoffStorage()
        val initial = coordinator(storage, OWNER_A)
        val install = command("f6da7fc4-54d5-4edb-b8e3-c08e6e350ed4")
        initial.register(install)
        val reserved = assertType<HansSetupHandoffAction.Dispatch>(
            initial.nextAction { environment(ready = true) },
        )
        initial.recordDispatchResult(reserved.record, CodexDispatchAttemptResult.RejectedBeforeTransport)
        val restarted = coordinator(storage, OWNER_B)
        val repair = command("eb248c78-d3a6-4c89-886d-dc8b6f75a8a6", HansSetupHandoffReason.REPAIR)
        restarted.register(repair)

        val retry = assertType<HansSetupHandoffAction.Dispatch>(
            restarted.nextAction { environment(ready = true) },
        )
        assertEquals(install.clientMessageId(), retry.record.clientUserMessageId)
        assertEquals(OWNER_B, retry.record.reservationOwnerId)
        assertEquals(listOf(repair), retry.record.aliases)
        assertEquals(1, restarted.snapshot().records.size)
    }

    @Test
    fun wallClockRollbackCannotStrandAnOtherwiseReadyHandoff() {
        var now = 100L
        val coordinator = HansSetupHandoffCoordinator(
            MemoryHandoffStorage(),
            HansSetupHandoffClock { now },
            OWNER_A,
        )
        val install = command("6646c6f3-6315-4e36-a49a-e52787587de5")
        coordinator.register(install)
        now = 90L
        val dispatch = assertType<HansSetupHandoffAction.Dispatch>(
            coordinator.nextAction { environment(ready = true) },
        )
        assertEquals(100L, dispatch.record.updatedAtMillis)
        now = 80L
        assertTrue(coordinator.recordDispatchResult(dispatch.record, CodexDispatchAttemptResult.Accepted(install.clientMessageId())))
        now = 70L
        val sent = assertType<HansSetupHandoffAction.StartLocalSetup>(
            coordinator.nextAction { environment(ready = true, outbound = HansSetupHandoffOutboundStatus.SENT) },
        )
        now = 60L
        assertTrue(coordinator.markSetupStarted(sent.record))
        assertEquals(100L, coordinator.snapshot().records.single().updatedAtMillis)
        assertEquals(HansSetupHandoffPhase.SETUP_STARTED, coordinator.snapshot().records.single().phase)
    }

    @Test
    fun processDeathAfterSentResumesLocalSetupWithoutSecondDispatch() {
        val storage = MemoryHandoffStorage()
        val first = coordinator(storage, OWNER_A)
        val request = command("4c22b50c-fd23-4b29-bb80-7066e4980163")
        first.register(request)
        val dispatch = assertType<HansSetupHandoffAction.Dispatch>(
            first.nextAction { environment(ready = true) },
        )
        first.recordDispatchResult(dispatch.record, CodexDispatchAttemptResult.Accepted(request.clientMessageId()))
        assertType<HansSetupHandoffAction.StartLocalSetup>(
            first.nextAction {
                environment(ready = false, outbound = HansSetupHandoffOutboundStatus.SENT)
            },
        )

        val recreated = coordinator(storage, OWNER_B)
        val resume = assertType<HansSetupHandoffAction.StartLocalSetup>(
            recreated.nextAction { environment(ready = false) },
        )
        assertTrue(recreated.markSetupStarted(resume.record))
        assertType<HansSetupHandoffAction.None>(
            recreated.nextAction { environment(ready = true) },
        )
    }

    @Test
    fun explicitAmbiguousFailureBecomesDurableRecovery() {
        val storage = MemoryHandoffStorage()
        val coordinator = coordinator(storage)
        coordinator.register(command("8dd37fcf-f6e6-44e9-9774-b19a00a58bf9"))
        val dispatch = assertType<HansSetupHandoffAction.Dispatch>(
            coordinator.nextAction { environment(ready = true) },
        )

        val recovery = coordinator.markDispatchAmbiguous(dispatch.record)

        assertEquals(HansSetupHandoffPhase.RECOVERY_REQUIRED, recovery?.phase)
        assertType<HansSetupHandoffAction.RecoveryRequired>(
            coordinator.nextAction { environment(ready = true) },
        )
    }

    @Test
    fun transportUnknownCannotReturnToQueuedOrDispatchAgain() {
        val coordinator = coordinator(MemoryHandoffStorage())
        coordinator.register(command("b8e4209a-3caf-48fd-b9ae-e4139a3f1fa0"))
        val dispatch = assertType<HansSetupHandoffAction.Dispatch>(
            coordinator.nextAction { environment(ready = true) },
        )
        // The setup host/callsite must preserve this typed outcome instead of reducing it to null.
        coordinator.recordDispatchResult(
            dispatch.record,
            CodexDispatchAttemptResult.TransportOutcomeAmbiguous,
        )

        assertEquals(HansSetupHandoffPhase.RECOVERY_REQUIRED, coordinator.snapshot().records.single().phase)
        assertType<HansSetupHandoffAction.RecoveryRequired>(
            coordinator.nextAction { environment(ready = true) },
        )
    }

    @Test
    fun definiteRejectionBeforeTransportPreservesUnsentRequestForOneLaterAttempt() {
        val coordinator = coordinator(MemoryHandoffStorage())
        coordinator.register(command("922f1c6a-bcc8-4a4b-bd41-60f23a164b8c"))
        val alias = command("cb7cdd44-a4b9-469f-9d13-a1fa15329d73", HansSetupHandoffReason.REPAIR)
        coordinator.register(alias)
        val dispatch = assertType<HansSetupHandoffAction.Dispatch>(
            coordinator.nextAction { environment(ready = true) },
        )
        assertFalse(coordinator.recordDispatchResult(dispatch.record, CodexDispatchAttemptResult.RejectedBeforeTransport))
        val queued = coordinator.snapshot().records.single()
        assertEquals(HansSetupHandoffPhase.QUEUED, queued.phase)
        assertNull(queued.reservationOwnerId)
        assertEquals(listOf(alias), queued.aliases)
        assertEquals(dispatch.record.clientUserMessageId, queued.clientUserMessageId)
        assertType<HansSetupHandoffAction.None>(coordinator.nextAction { environment(ready = false) })
        val retry = assertType<HansSetupHandoffAction.Dispatch>(
            coordinator.nextAction { environment(ready = true) },
        )
        assertEquals(dispatch.record.clientUserMessageId, retry.record.clientUserMessageId)
        assertType<HansSetupHandoffAction.None>(coordinator.nextAction { environment(ready = true) })
    }

    @Test
    fun unknownTransportKeepsExactCorrelationUntilLateServerReceipt() {
        val storage = MemoryHandoffStorage()
        val coordinator = coordinator(storage)
        coordinator.register(command("e2ccf2bd-9b53-42fb-a77b-721283df9b7f"))
        val alias = command("bc541a43-7c7f-4476-9b50-0bcb69b69b33", HansSetupHandoffReason.REPAIR)
        coordinator.register(alias)
        val dispatch = assertType<HansSetupHandoffAction.Dispatch>(
            coordinator.nextAction { environment(ready = true) },
        )
        coordinator.recordDispatchResult(dispatch.record, CodexDispatchAttemptResult.TransportOutcomeAmbiguous)
        val recovery = checkNotNull(coordinator.markDispatchAmbiguous(dispatch.record))
        assertEquals(HansSetupHandoffPhase.RECOVERY_REQUIRED, recovery.phase)
        assertEquals(dispatch.record.clientUserMessageId, recovery.clientUserMessageId)
        assertEquals(listOf(alias), recovery.aliases)
        assertType<HansSetupHandoffAction.RecoveryRequired>(
            coordinator.nextAction { environment(ready = true, outbound = HansSetupHandoffOutboundStatus.PENDING) },
        )
        assertType<HansSetupHandoffAction.RecoveryRequired>(
            coordinator.nextAction { record ->
                environment(
                    ready = true,
                    outbound = if (record.clientUserMessageId == alias.clientMessageId()) {
                        HansSetupHandoffOutboundStatus.SENT
                    } else {
                        HansSetupHandoffOutboundStatus.ABSENT
                    },
                )
            },
        )
        val restarted = coordinator(storage, OWNER_B)
        val sent = assertType<HansSetupHandoffAction.StartLocalSetup>(
            restarted.nextAction { environment(ready = true, outbound = HansSetupHandoffOutboundStatus.SENT) },
        )
        assertTrue(restarted.markSetupStarted(sent.record))
        assertType<HansSetupHandoffAction.None>(restarted.nextAction { environment(ready = true) })
    }

    @Test
    fun mismatchedAcceptedIdCannotMasqueradeAsCanonicalOrAliasReceipt() {
        val coordinator = coordinator(MemoryHandoffStorage())
        coordinator.register(command("17c4dc24-f6e7-4d09-8c63-33b2b26f2d22"))
        val alias = command("ed830e85-449a-486c-96d2-3f0f7f6cdcd2", HansSetupHandoffReason.REPAIR)
        coordinator.register(alias)
        val dispatch = assertType<HansSetupHandoffAction.Dispatch>(
            coordinator.nextAction { environment(ready = true) },
        )
        assertFalse(coordinator.recordDispatchResult(dispatch.record, CodexDispatchAttemptResult.Accepted(alias.clientMessageId())))
        assertEquals(HansSetupHandoffPhase.RECOVERY_REQUIRED, coordinator.snapshot().records.single().phase)
        assertEquals(dispatch.record.clientUserMessageId, coordinator.snapshot().records.single().clientUserMessageId)
    }

    @Test
    fun completeSetupSkipsOneCoalescedAgentTurnAndRetainsBothRequestReceipts() {
        val storage = MemoryHandoffStorage()
        val coordinator = coordinator(storage)
        val first = command("77b11775-a0c0-488e-b0cf-86bfa8c9f86f")
        val second = command("f50abb91-7d36-4190-a4e4-04ea8f57e049")
        coordinator.register(first)
        coordinator.register(second)

        val firstSkip = assertType<HansSetupHandoffAction.SkipAlreadyComplete>(
            coordinator.nextAction { environment(ready = true, setupComplete = true) },
        )
        assertEquals(first.handoffId, firstSkip.record.command.handoffId)
        assertEquals(listOf(second), firstSkip.record.aliases)
        assertEquals(
            HansSetupHandoffPhase.ALREADY_COMPLETE,
            coordinator.register(second).record?.phase,
        )
        assertType<HansSetupHandoffAction.None>(
            coordinator.nextAction { environment(ready = true, setupComplete = true) },
        )
    }

    @Test
    fun terminalHistoryIsPrunedSoNewInstallIsNotPermanentlyBlocked() {
        val terminalRecords = List(HansSetupHandoffBounds.MAX_RECORDS) { index ->
            val request = command(UUID(0L, index.toLong() + 1L).toString())
            HansSetupHandoffRecord(
                command = request,
                clientUserMessageId = request.clientMessageId(),
                phase = HansSetupHandoffPhase.SETUP_STARTED,
                reservationOwnerId = OWNER_A,
                receivedAtMillis = index.toLong(),
                updatedAtMillis = index.toLong(),
            )
        }
        val storage = MemoryHandoffStorage(HansSetupHandoffDocument(terminalRecords))
        val coordinator = coordinator(storage)
        val fresh = command("a35b27f7-1617-42cb-a8bc-55e83b0f69a2")

        val result = coordinator.register(fresh)

        assertEquals(HansSetupHandoffRegistration.ACCEPTED, result.registration)
        assertTrue(storage.read().records.size <= HansSetupHandoffBounds.MAX_RETAINED_TERMINAL_RECORDS + 1)
        assertEquals(fresh.handoffId, storage.read().records.last().command.handoffId)
    }

    @Test
    fun repairSupersedesCapacityOfRecoveryRecordsButPreservesActiveWork() {
        val queued = record(
            command("3c04fd37-215e-4270-ab60-d78fb65a7775"),
            HansSetupHandoffPhase.QUEUED,
            0,
        )
        val serverSent = record(
            command("798d3732-45ec-45fa-8f18-4701822a9fc9"),
            HansSetupHandoffPhase.SERVER_SENT,
            1,
        )
        val recoveries = List(HansSetupHandoffBounds.MAX_RECORDS - 2) { index ->
            record(
                command(UUID(1L, index.toLong() + 1L).toString()),
                HansSetupHandoffPhase.RECOVERY_REQUIRED,
                index.toLong() + 2,
            )
        }
        val storage = MemoryHandoffStorage(
            HansSetupHandoffDocument(listOf(queued, serverSent) + recoveries),
        )
        val coordinator = coordinator(storage)
        val repair = command(
            "8b304220-c252-477b-84f8-02ed42378900",
            HansSetupHandoffReason.REPAIR,
        )

        val result = coordinator.register(repair)
        val snapshot = storage.read()

        assertEquals(HansSetupHandoffRegistration.ACCEPTED, result.registration)
        assertTrue(snapshot.records.size <= HansSetupHandoffBounds.MAX_RETAINED_TERMINAL_RECORDS + 3)
        val canonical = snapshot.record(serverSent.command.handoffId)
        assertEquals(queued.command, canonical.registeredCommand(queued.command.handoffId))
        assertEquals(
            HansSetupHandoffPhase.SERVER_SENT,
            snapshot.record(serverSent.command.handoffId).phase,
        )
        assertEquals(repair, canonical.registeredCommand(repair.handoffId))
        assertEquals(serverSent.clientUserMessageId, canonical.clientUserMessageId)
        assertTrue(snapshot.records.none { it.phase == HansSetupHandoffPhase.RECOVERY_REQUIRED })
    }

    @Test
    fun aliasRetryIsIdempotentButSameIdWithDifferentReasonIsConflict() {
        val storage = MemoryHandoffStorage()
        val coordinator = coordinator(storage)
        val install = command("67a3f586-0479-43e6-bb84-31b27032d231")
        val repair = command("b702e25d-a8d8-4c5e-96d4-2e5e5a2dc3e1", HansSetupHandoffReason.REPAIR)
        coordinator.register(install)
        assertEquals(HansSetupHandoffRegistration.ACCEPTED, coordinator.register(repair).registration)
        val beforeRetry = storage.read()
        assertEquals(HansSetupHandoffRegistration.DUPLICATE, coordinator.register(repair).registration)
        assertEquals(
            HansSetupHandoffRegistration.CONFLICT,
            coordinator.register(repair.copy(reason = HansSetupHandoffReason.INSTALL)).registration,
        )
        assertEquals(beforeRetry, storage.read())
        assertEquals(listOf(repair), storage.read().records.single().aliases)
    }

    @Test
    fun distinctConcurrentInstallAndRepairIdsReserveOnlyOneSetupTurn() {
        val storage = MemoryHandoffStorage()
        val requests = listOf(
            command("910c3fae-a9ce-42f5-b393-337f261939df"),
            command("00bf42fe-afb8-4f6b-b2ae-ae4dc3cd22e6", HansSetupHandoffReason.REPAIR),
        )
        val registrations = runConcurrently(requests) { coordinator(storage).register(it) }
        assertTrue(registrations.all { it.registration == HansSetupHandoffRegistration.ACCEPTED })
        assertEquals(1, storage.read().records.size)
        assertEquals(2, storage.read().requestCount)
        val actions = runConcurrently(listOf(coordinator(storage), coordinator(storage))) {
            it.nextAction { environment(ready = true) }
        }
        assertEquals(1, actions.count { it is HansSetupHandoffAction.Dispatch })
        assertEquals(1, actions.count { it is HansSetupHandoffAction.None })
    }

    @Test
    fun freshIdsDuringReservedAcceptedOrServerSentWorkKeepCanonicalCorrelation() {
        listOf(
            HansSetupHandoffPhase.DISPATCH_RESERVED,
            HansSetupHandoffPhase.DISPATCH_ACCEPTED,
            HansSetupHandoffPhase.SERVER_SENT,
        ).forEach { phase ->
            val original = record(
                command("30ba20e9-11d1-4cde-9dc9-12402ad142d2"), phase, 1,
            ).copy(reservationOwnerId = OWNER_A)
            val storage = MemoryHandoffStorage(HansSetupHandoffDocument(listOf(original)))
            val coordinator = coordinator(storage)
            val alias = command("d38fbf37-4d1e-4221-b798-556bce161c12", HansSetupHandoffReason.REPAIR)
            coordinator.register(alias)
            val canonical = storage.read().records.single()
            assertEquals(phase, canonical.phase)
            assertEquals(original.clientUserMessageId, canonical.clientUserMessageId)
            assertEquals(original.reservationOwnerId, canonical.reservationOwnerId)
            assertEquals(listOf(alias), canonical.aliases)
            if (phase != HansSetupHandoffPhase.SERVER_SENT) {
                assertType<HansSetupHandoffAction.None>(
                    coordinator.nextAction { current ->
                        environment(
                            ready = true,
                            outbound = if (current.clientUserMessageId == alias.clientMessageId()) {
                                HansSetupHandoffOutboundStatus.SENT
                            } else {
                                HansSetupHandoffOutboundStatus.ABSENT
                            },
                        )
                    },
                )
            }
            val start = assertType<HansSetupHandoffAction.StartLocalSetup>(
                coordinator.nextAction {
                    environment(ready = true, outbound = HansSetupHandoffOutboundStatus.SENT)
                },
            )
            assertTrue(coordinator.markSetupStarted(start.record))
            assertType<HansSetupHandoffAction.None>(
                coordinator.nextAction { environment(ready = true) },
            )
        }
    }

    @Test
    fun aliasBeforeCrashCannotAuthorizeReplayButFreshExplicitRepairCanRecover() {
        val storage = MemoryHandoffStorage()
        val initial = coordinator(storage, OWNER_A)
        val install = command("622fb3d5-7de1-4d0c-9f78-aec2543bbce2")
        val earlyRepair = command("f62c49f0-ac48-4bdc-80e9-8e02ed3766c7", HansSetupHandoffReason.REPAIR)
        initial.register(install)
        val dispatch = assertType<HansSetupHandoffAction.Dispatch>(
            initial.nextAction { environment(ready = true) },
        )
        initial.recordDispatchResult(dispatch.record, CodexDispatchAttemptResult.Accepted(install.clientMessageId()))
        initial.register(earlyRepair)
        val restarted = coordinator(storage, OWNER_B)
        assertType<HansSetupHandoffAction.RecoveryRequired>(
            restarted.nextAction { environment(ready = true) },
        )
        assertEquals(HansSetupHandoffRegistration.DUPLICATE, restarted.register(earlyRepair).registration)
        assertType<HansSetupHandoffAction.RecoveryRequired>(
            restarted.nextAction { environment(ready = true) },
        )
        val explicitRepair = command("ff6fa642-68bd-44c1-901a-f539b545c9f2", HansSetupHandoffReason.REPAIR)
        restarted.register(explicitRepair)
        val recoveryDispatch = assertType<HansSetupHandoffAction.Dispatch>(
            restarted.nextAction { environment(ready = true) },
        )
        assertEquals(explicitRepair.clientMessageId(), recoveryDispatch.record.clientUserMessageId)
        assertEquals(listOf(earlyRepair), storage.read().record(install.handoffId).aliases)
    }

    @Test
    fun freshResumeAfterSetupStartedIsNotSwallowedByPreviousAlias() {
        val storage = MemoryHandoffStorage()
        val coordinator = coordinator(storage)
        val first = command("b25e698e-8c27-41f7-b6a4-05a73d4c1f10")
        val retry = command("852de4e7-feec-48e4-8121-4c36c1e25a36", HansSetupHandoffReason.REPAIR)
        coordinator.register(first)
        coordinator.register(retry)
        val dispatch = assertType<HansSetupHandoffAction.Dispatch>(
            coordinator.nextAction { environment(ready = true) },
        )
        coordinator.recordDispatchResult(dispatch.record, CodexDispatchAttemptResult.Accepted(first.clientMessageId()))
        val start = assertType<HansSetupHandoffAction.StartLocalSetup>(
            coordinator.nextAction { environment(ready = true, outbound = HansSetupHandoffOutboundStatus.SENT) },
        )
        coordinator.markSetupStarted(start.record)
        assertEquals(HansSetupHandoffRegistration.DUPLICATE, coordinator.register(retry).registration)
        assertType<HansSetupHandoffAction.None>(coordinator.nextAction { environment(ready = true) })
        val resume = command("0f1096a6-46bd-4e9e-9392-ffca1e8b396d", HansSetupHandoffReason.REPAIR)
        coordinator.register(resume)
        val resumed = assertType<HansSetupHandoffAction.Dispatch>(
            coordinator.nextAction { environment(ready = true, setupComplete = false) },
        )
        assertEquals(resume.clientMessageId(), resumed.record.clientUserMessageId)
        assertEquals(2, storage.read().records.size)
    }

    @Test
    fun legacyQueuedRequestsConvergeWithoutMergingAlreadyDispatchedOrRecoveryRecords() {
        val queued = record(command("c80c030c-a054-41c0-a1dd-2694d6bb9a61"), HansSetupHandoffPhase.QUEUED, 1)
        val active = record(command("82b94691-9c44-4cde-9d10-025da90fbbe4"), HansSetupHandoffPhase.SERVER_SENT, 2)
        val otherActive = record(command("312b855c-c205-4532-9d72-fc23e5f51154"), HansSetupHandoffPhase.SERVER_SENT, 3)
        val ambiguous = record(command("8e18c194-2527-4e02-a53d-43f3f94bf880"), HansSetupHandoffPhase.RECOVERY_REQUIRED, 4)
        val storage = MemoryHandoffStorage(HansSetupHandoffDocument(listOf(queued, active, otherActive, ambiguous)))
        val coordinator = coordinator(storage)
        assertType<HansSetupHandoffAction.StartLocalSetup>(
            coordinator.nextAction { environment(ready = true) },
        )
        val migrated = storage.read()
        assertEquals(4, migrated.requestCount)
        assertEquals(3, migrated.records.size)
        assertEquals(listOf(queued.command), migrated.record(active.command.handoffId).aliases)
        assertEquals(otherActive, migrated.record(otherActive.command.handoffId))
        assertEquals(ambiguous, migrated.record(ambiguous.command.handoffId))
    }

    @Test
    fun legacyRepairBehindRecoveryKeepsItsPositionAndAuthorizesOnlyNewCanonicalTurn() {
        val ambiguous = record(command("ce901820-ef97-44ad-a30b-ff0ab5117182"), HansSetupHandoffPhase.RECOVERY_REQUIRED, 1)
        val install = record(command("d258972c-f029-462b-a86f-6b0c3041e647"), HansSetupHandoffPhase.QUEUED, 2)
        val repair = record(command("d258972c-f029-462b-a86f-6b0c3041e648", HansSetupHandoffReason.REPAIR), HansSetupHandoffPhase.QUEUED, 3)
        val storage = MemoryHandoffStorage(HansSetupHandoffDocument(listOf(ambiguous, install, repair)))
        val coordinator = coordinator(storage)
        val dispatch = assertType<HansSetupHandoffAction.Dispatch>(
            coordinator.nextAction { environment(ready = true) },
        )
        assertEquals(install.clientUserMessageId, dispatch.record.clientUserMessageId)
        assertEquals(listOf(repair.command), dispatch.record.aliases)
        assertEquals(HansSetupHandoffPhase.REJECTED, storage.read().record(ambiguous.command.handoffId).phase)
    }

    @Test
    fun aliasIdsCountTowardCapacityAndTerminalGroupsArePrunedAsOneReceipt() {
        val first = command(UUID(5L, 1L).toString())
        val aliases = (2..HansSetupHandoffBounds.MAX_RECORDS).map { command(UUID(5L, it.toLong()).toString()) }
        val full = record(first, HansSetupHandoffPhase.QUEUED, 1).copy(aliases = aliases)
        val storage = MemoryHandoffStorage(HansSetupHandoffDocument(listOf(full)))
        val coordinator = coordinator(storage)
        val fresh = command(UUID(6L, 1L).toString())
        assertEquals(HansSetupHandoffRegistration.CAPACITY_REACHED, coordinator.register(fresh).registration)
        assertEquals(HansSetupHandoffBounds.MAX_RECORDS, storage.read().requestCount)
        storage.mutate { current ->
            HansSetupHandoffMutation(current.copy(records = current.records.map { it.copy(phase = HansSetupHandoffPhase.SETUP_STARTED) }), Unit)
        }
        assertEquals(HansSetupHandoffRegistration.ACCEPTED, coordinator.register(fresh).registration)
        assertEquals(listOf(fresh), storage.read().records.map { it.command })
    }

    @Test
    fun sentHandoffResumesExistingPartialSetupWithoutReplacingItsTruth() {
        val localStorage = MemorySetupStorage(
            HansSetupDocument(
                revision = 8,
                started = true,
                currentStep = HansSetupStep.INPUT_CHOICE,
                steps = mapOf(
                    HansSetupStep.INTRO to HansSetupStepRecord(
                        status = HansSetupStepStatus.VERIFIED,
                        detailCode = "intro_accepted",
                    ),
                ),
                updatedAtMillis = 100,
            ),
        )
        val setupRepository = HansSetupRepository(localStorage, HansSetupClock { 500 })
        val coordinator = coordinator(MemoryHandoffStorage())
        val request = command("2d6f986a-050f-4e0e-bdf6-aa2bbf2e3bba")
        coordinator.register(request)
        val dispatch = assertType<HansSetupHandoffAction.Dispatch>(
            coordinator.nextAction { environment(ready = true) },
        )
        coordinator.recordDispatchResult(dispatch.record, CodexDispatchAttemptResult.Accepted(request.clientMessageId()))
        val start = assertType<HansSetupHandoffAction.StartLocalSetup>(
            coordinator.nextAction {
                environment(ready = false, outbound = HansSetupHandoffOutboundStatus.SENT)
            },
        )

        val resumed = setupRepository.startOrResume()
        coordinator.markSetupStarted(start.record)

        assertTrue(resumed.started)
        assertEquals(HansSetupStep.INPUT_CHOICE, resumed.currentStep)
        assertEquals(HansSetupStepStatus.VERIFIED, resumed.record(HansSetupStep.INTRO).status)
        assertNull(resumed.inputChoice)
        assertFalse(resumed.complete)
    }

    @Test
    fun acknowledgementDigestBindsProtocolIdReasonAndStatus() {
        val acknowledgement = HansSetupHandoffAcknowledgement.create(
            command("84e3b844-47da-42eb-b8f0-bda209b8dd78"),
            HansSetupHandoffAckStatus.ACCEPTED,
        )

        assertEquals(
            "3ecf99e9396e9c5ec14710df511b2fc1fdc025bfb302c086d687c04e209f3297",
            acknowledgement.sha256,
        )
        assertTrue(acknowledgement.resultData.endsWith(":sha256:${acknowledgement.sha256}"))
    }

    private fun command(
        id: String,
        reason: HansSetupHandoffReason = HansSetupHandoffReason.INSTALL,
    ) = HansSetupHandoffCommand(1, id, reason)

    private fun assertReceiverFirstRepairRecoversForeignReservation(phase: HansSetupHandoffPhase) {
        val earlyRepair = command("a4599444-5517-493d-ab90-21c2c7f50579", HansSetupHandoffReason.REPAIR)
        val original = record(command("8d903d14-8c02-4b84-9898-0b99090b9f5c"), phase, 1)
            .copy(aliases = listOf(earlyRepair))
        val storage = MemoryHandoffStorage(HansSetupHandoffDocument(listOf(original)))
        val restarted = coordinator(storage, OWNER_B)
        val repair = command("7d099bc4-e4ea-4b76-9c98-f682dbdbfa50", HansSetupHandoffReason.REPAIR)

        // Android may deliver CONTINUE_SETUP to the receiver before LauncherActivity exists.
        assertEquals(HansSetupHandoffRegistration.ACCEPTED, restarted.register(repair).registration)
        assertEquals(HansSetupHandoffRegistration.DUPLICATE, restarted.register(repair).registration)
        val replacement = assertType<HansSetupHandoffAction.Dispatch>(
            restarted.nextAction { environment(ready = true) },
        )
        assertEquals(repair.clientMessageId(), replacement.record.clientUserMessageId)
        assertEquals(OWNER_B, replacement.record.reservationOwnerId)
        assertEquals(HansSetupHandoffPhase.REJECTED, storage.read().record(original.command.handoffId).phase)
        assertEquals(listOf(earlyRepair), storage.read().record(original.command.handoffId).aliases)
        assertType<HansSetupHandoffAction.None>(restarted.nextAction { environment(ready = true) })
        assertTrue(restarted.recordDispatchResult(replacement.record, CodexDispatchAttemptResult.Accepted(repair.clientMessageId())))
        val start = assertType<HansSetupHandoffAction.StartLocalSetup>(
            restarted.nextAction { current ->
                environment(
                    ready = true,
                    outbound = if (current.clientUserMessageId == repair.clientMessageId()) {
                        HansSetupHandoffOutboundStatus.SENT
                    } else {
                        HansSetupHandoffOutboundStatus.ABSENT
                    },
                )
            },
        )
        assertTrue(restarted.markSetupStarted(start.record))
        assertType<HansSetupHandoffAction.None>(restarted.nextAction { environment(ready = true) })
    }

    private fun HansSetupHandoffCommand.clientMessageId(): String =
        HansSetupHandoffRecord.messageIdFor(handoffId)

    private fun record(
        command: HansSetupHandoffCommand,
        phase: HansSetupHandoffPhase,
        timestamp: Long,
    ) = HansSetupHandoffRecord(
        command = command,
        clientUserMessageId = command.clientMessageId(),
        phase = phase,
        reservationOwnerId = if (phase in setOf(HansSetupHandoffPhase.DISPATCH_RESERVED, HansSetupHandoffPhase.DISPATCH_ACCEPTED)) OWNER_A else null,
        receivedAtMillis = timestamp,
        updatedAtMillis = timestamp,
    )

    private fun HansSetupHandoffDocument.record(handoffId: String): HansSetupHandoffRecord =
        records.single { it.command.handoffId == handoffId }

    private fun coordinator(
        storage: HansSetupHandoffStorage,
        owner: String = OWNER_A,
    ) = HansSetupHandoffCoordinator(storage, HansSetupHandoffClock { 2_000 }, owner)

    private fun environment(
        ready: Boolean,
        outbound: HansSetupHandoffOutboundStatus = HansSetupHandoffOutboundStatus.ABSENT,
        setupComplete: Boolean = false,
    ) = HansSetupHandoffEnvironment(ready, outbound, setupComplete)

    private fun <T, R> runConcurrently(values: List<T>, action: (T) -> R): List<R> {
        val barrier = CyclicBarrier(values.size)
        val executor = Executors.newFixedThreadPool(values.size)
        return try {
            values.map { value ->
                executor.submit(Callable {
                    barrier.await()
                    action(value)
                })
            }.map { it.get() }
        } finally {
            executor.shutdownNow()
        }
    }

    private inline fun <reified T> assertType(value: Any): T {
        assertTrue("Expected ${T::class.java.name}, got ${value::class.java.name}", value is T)
        return value as T
    }

    private class MemoryHandoffStorage(
        private var value: HansSetupHandoffDocument = HansSetupHandoffDocument(),
    ) : HansSetupHandoffStorage {
        @Synchronized
        override fun read(): HansSetupHandoffDocument = value

        @Synchronized
        override fun <T> mutate(
            transform: (HansSetupHandoffDocument) -> HansSetupHandoffMutation<T>,
        ): T {
            val mutation = transform(value)
            value = mutation.document
            return mutation.result
        }
    }

    private class MemorySetupStorage(
        private var value: HansSetupDocument,
    ) : HansSetupStorage {
        override fun read(): HansSetupDocument = value

        override fun write(document: HansSetupDocument) {
            value = document
        }
    }

    private companion object {
        const val OWNER_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val OWNER_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    }
}
