package ai.hans.standard.setup

import ai.hans.standard.integration.CodexDispatchAttemptResult
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** No production handoff, accounts or setup state is opened; every file is an isolated cache fixture. */
@RunWith(AndroidJUnit4::class)
class HansSetupHandoffPersistenceContractTest {
    @Test
    fun freshRepairAliasPersistsAcrossRestartAndRemainsBoundToOriginalRequest() = withStore { context, file ->
        val first = coordinator(context, file)
        first.register(INSTALL)
        first.register(REPAIR)
        val reopened = coordinator(context, file)
        val record = reopened.snapshot().records.single()
        assertEquals(listOf(REPAIR), record.aliases)
        assertEquals(2, reopened.snapshot().requestCount)
        assertEquals(HansSetupHandoffRegistration.DUPLICATE, reopened.register(REPAIR).registration)
        assertEquals(HansSetupHandoffRegistration.CONFLICT, reopened.register(REPAIR.copy(reason = HansSetupHandoffReason.INSTALL)).registration)
        assertEquals(HansSetupHandoffRecord.messageIdFor(INSTALL.handoffId), record.clientUserMessageId)
    }

    @Test
    fun versionOneQueuedBacklogMigratesAtomicallyWithoutSecondDispatch() = withStore { context, file ->
        val records = listOf(INSTALL, REPAIR).mapIndexed { index, command -> record(command, index.toLong()) }
        val legacy = JSONObject(HansSetupHandoffCodec.encode(HansSetupHandoffDocument(records))).put("version", 1)
        repeat(records.size) { legacy.getJSONArray("records").getJSONObject(it).remove("aliases") }
        file.writeText(legacy.toString())
        val coordinator = coordinator(context, file)
        assertEquals(HansSetupHandoffAction.None, coordinator.nextAction { environment(ready = false) })
        assertEquals(2, JSONObject(file.readText()).getInt("version"))
        assertEquals(1, coordinator.snapshot().records.size)
        assertEquals(listOf(REPAIR), coordinator.snapshot().records.single().aliases)
        val dispatch = coordinator.nextAction { environment(ready = true) } as HansSetupHandoffAction.Dispatch
        coordinator.recordDispatchResult(dispatch.record, CodexDispatchAttemptResult.Accepted(dispatch.record.clientUserMessageId))
        val start = coordinator.nextAction {
            environment(ready = true, outbound = HansSetupHandoffOutboundStatus.SENT)
        } as HansSetupHandoffAction.StartLocalSetup
        coordinator.markSetupStarted(start.record)
        assertEquals(HansSetupHandoffAction.None, coordinator(context, file).nextAction { environment(ready = true) })
    }

    @Test
    fun survivingAtomicBackupCannotBeMistakenForEmptyHandoffHistory() = withStore { context, file ->
        coordinator(context, file).register(INSTALL)
        val backup = File("${file.path}.bak")
        assertTrue(file.renameTo(backup))
        val recreated = coordinator(context, file)
        assertEquals(HansSetupHandoffRegistration.DUPLICATE, recreated.register(INSTALL).registration)
        assertEquals(1, recreated.snapshot().requestCount)
    }

    @Test
    fun corruptHandoffHistoryFailsClosedWithoutOverwritingAmbiguousReceipts() = withStore { context, file ->
        val corrupt = "{existing-unreadable-handoff-receipt".toByteArray()
        file.writeBytes(corrupt)
        assertTrue(runCatching { coordinator(context, file).register(INSTALL) }.isFailure)
        assertTrue(corrupt.contentEquals(file.readBytes()))
    }

    @Test
    fun ambiguousDispatchSurvivesReopenAndLateExactReceiptResolvesWithoutReplay() = withStore { context, file ->
        val first = coordinator(context, file)
        first.register(INSTALL)
        first.register(REPAIR)
        val dispatch = first.nextAction { environment(ready = true) } as HansSetupHandoffAction.Dispatch
        first.recordDispatchResult(dispatch.record, CodexDispatchAttemptResult.TransportOutcomeAmbiguous)
        val reopened = coordinator(context, file)
        val recovery = reopened.nextAction { environment(ready = true) } as HansSetupHandoffAction.RecoveryRequired
        assertEquals(dispatch.record.clientUserMessageId, recovery.record.clientUserMessageId)
        assertEquals(listOf(REPAIR), recovery.record.aliases)
        val lateReceipt = reopened.nextAction {
            environment(ready = true, outbound = HansSetupHandoffOutboundStatus.SENT)
        } as HansSetupHandoffAction.StartLocalSetup
        reopened.markSetupStarted(lateReceipt.record)
        assertEquals(HansSetupHandoffAction.None, reopened.nextAction { environment(ready = true) })
    }

    @Test
    fun receiverFirstRepairPersistsAcrossForeignReservationBeforeUiReconciliation() {
        listOf(false, true).forEach { accepted ->
            withStore { context, file ->
                val first = coordinator(context, file)
                first.register(INSTALL)
                first.register(REPAIR)
                val reserved = first.nextAction { environment(ready = true) } as HansSetupHandoffAction.Dispatch
                if (accepted) {
                    first.recordDispatchResult(reserved.record, CodexDispatchAttemptResult.Accepted(reserved.record.clientUserMessageId))
                }
                val receiver = coordinator(context, file, RESTARTED_OWNER)
                assertEquals(HansSetupHandoffRegistration.ACCEPTED, receiver.register(FRESH_REPAIR).registration)

                // A separately reopened coordinator models UI reconciliation AFTER the receiver
                // has returned and its repair receipt has already been atomically committed.
                val activity = coordinator(context, file, RESTARTED_OWNER)
                assertEquals(2, activity.snapshot().records.size)
                val replacement = activity.nextAction { environment(ready = true) } as HansSetupHandoffAction.Dispatch
                assertEquals(HansSetupHandoffRecord.messageIdFor(FRESH_REPAIR.handoffId), replacement.record.clientUserMessageId)
                assertEquals(RESTARTED_OWNER, replacement.record.reservationOwnerId)
                assertEquals(HansSetupHandoffPhase.REJECTED, activity.snapshot().records.first().phase)
                assertEquals(listOf(REPAIR), activity.snapshot().records.first().aliases)
                val reopened = coordinator(context, file, RESTARTED_OWNER)
                assertEquals(HansSetupHandoffRegistration.DUPLICATE, reopened.register(FRESH_REPAIR).registration)
                assertEquals(HansSetupHandoffAction.None, reopened.nextAction { environment(ready = true) })
            }
        }
    }

    @Test
    fun persistedReceiverFirstRepairDoesNotDuplicateAnExactLateSentReceipt() = withStore { context, file ->
        val first = coordinator(context, file)
        first.register(INSTALL)
        first.register(REPAIR)
        val dispatch = first.nextAction { environment(ready = true) } as HansSetupHandoffAction.Dispatch
        first.recordDispatchResult(dispatch.record, CodexDispatchAttemptResult.Accepted(dispatch.record.clientUserMessageId))
        coordinator(context, file, RESTARTED_OWNER).register(FRESH_REPAIR)

        val activity = coordinator(context, file, RESTARTED_OWNER)
        val sent = activity.nextAction { current ->
            environment(
                ready = true,
                outbound = if (current.clientUserMessageId == dispatch.record.clientUserMessageId) {
                    HansSetupHandoffOutboundStatus.SENT
                } else {
                    HansSetupHandoffOutboundStatus.ABSENT
                },
            )
        } as HansSetupHandoffAction.StartLocalSetup
        assertEquals(dispatch.record.clientUserMessageId, sent.record.clientUserMessageId)
        assertEquals(listOf(REPAIR, FRESH_REPAIR), sent.record.aliases)
        assertTrue(activity.markSetupStarted(sent.record))
        val reopened = coordinator(context, file, RESTARTED_OWNER)
        assertEquals(HansSetupHandoffRegistration.DUPLICATE, reopened.register(FRESH_REPAIR).registration)
        assertEquals(HansSetupHandoffAction.None, reopened.nextAction { environment(ready = true) })
    }

    private fun coordinator(context: Context, file: File, owner: String = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee") = HansSetupHandoffCoordinator(
        AtomicFileHansSetupHandoffStorage(context, file),
        HansSetupHandoffClock { 100 },
        owner,
    )

    private fun record(command: HansSetupHandoffCommand, time: Long) = HansSetupHandoffRecord(
        command, HansSetupHandoffRecord.messageIdFor(command.handoffId),
        HansSetupHandoffPhase.QUEUED, null, time, time,
    )

    private fun environment(ready: Boolean, outbound: HansSetupHandoffOutboundStatus = HansSetupHandoffOutboundStatus.ABSENT) =
        HansSetupHandoffEnvironment(ready, outbound, setupComplete = false)

    private fun withStore(block: (Context, File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = File(context.cacheDir, "hans-handoff-alias-test-${UUID.randomUUID()}")
        check(fixture.mkdir())
        try {
            block(context, File(fixture, "handoff.json"))
        } finally {
            check(fixture.deleteRecursively())
        }
    }

    private companion object {
        val INSTALL = HansSetupHandoffCommand(1, "9b1df852-a3df-4722-8cba-c356297fd32c", HansSetupHandoffReason.INSTALL)
        val REPAIR = HansSetupHandoffCommand(1, "2fe90f47-0914-45d5-a251-4cce3e7e35eb", HansSetupHandoffReason.REPAIR)
        val FRESH_REPAIR = HansSetupHandoffCommand(1, "c088c05c-f802-4b84-9a26-94b9c9365e88", HansSetupHandoffReason.REPAIR)
        const val RESTARTED_OWNER = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
    }
}
