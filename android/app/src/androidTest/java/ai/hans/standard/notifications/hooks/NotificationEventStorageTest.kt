package ai.hans.standard.notifications.hooks

import android.content.Context
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.hans.standard.phone.notifications.NotificationEventKind
import ai.hans.standard.phone.notifications.NotificationInboxEvent
import ai.hans.standard.phone.notifications.NotificationSnapshot
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationEventStorageTest {
    private lateinit var context: Context
    private lateinit var fileName: String

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fileName = "notification-hook-fixture-${System.nanoTime()}.json"
    }

    @After fun tearDown() {
        AtomicFile(File(context.noBackupFilesDir, fileName)).delete()
        AtomicFile(File(context.noBackupFilesDir, "$fileName.initialized")).delete()
    }

    @Test fun durableActivationSkipsExistingBacklogAndRetainsDistinctUpdatesAcrossReopen() {
        val first = NotificationEventLedger(AtomicFileNotificationEventStorage(context, fileName))
        assertTrue(first.activateAfter(20))
        assertTrue(first.accept(event(1)))
        assertTrue(first.accept(event(21)))
        assertTrue(first.accept(event(22, NotificationEventKind.UPDATED)))
        val reopened = NotificationEventLedger(AtomicFileNotificationEventStorage(context, fileName))
        assertTrue(reopened.activateAfter(22))
        assertEquals(listOf(21L, 22L), reopened.pending().map { it.sequence })
        assertTrue(reopened.accept(event(22, NotificationEventKind.UPDATED)))
        assertEquals(2, reopened.pending().size)
    }

    @Test fun claimSurvivesProcessStyleReopenAndOnlyExactNativeReceiptRecoversIt() {
        val first = NotificationEventLedger(AtomicFileNotificationEventStorage(context, fileName))
        first.activateAfter(0)
        first.accept(event(1))
        val claim = first.claim(first.pending().single().eventId, "main-thread")!!
        val reopened = NotificationEventLedger(AtomicFileNotificationEventStorage(context, fileName))
        assertTrue(reopened.pending().isEmpty())
        assertEquals(1, reopened.status().uncertainCount)
        assertFalse(reopened.recoverAccepted(claim.eventId, claim.payloadSha256, "wrong-thread", "turn-1"))
        assertTrue(reopened.recoverAccepted(claim.eventId, claim.payloadSha256, "main-thread", "turn-1"))
        assertTrue(reopened.settle(claim.eventId, "main-thread", "turn-1", true))
        assertEquals(0, reopened.status().unsettledCount)
    }

    @Test fun privacyClearIsDurableInvalidatesLateCallbacksAndDoesNotReplayOldOutbox() {
        val first = NotificationEventLedger(AtomicFileNotificationEventStorage(context, fileName))
        first.activateAfter(0)
        first.accept(event(1))
        val claim = first.claim(first.pending().single().eventId, "main-thread")!!
        assertTrue(first.clearPrivateData())
        val reopened = NotificationEventLedger(AtomicFileNotificationEventStorage(context, fileName))
        assertFalse(reopened.accepted(claim, "main-thread", "turn-1"))
        assertTrue(reopened.accept(event(1)))
        assertTrue(reopened.pending().isEmpty())
        assertFalse(File(context.noBackupFilesDir, fileName).readText().contains("private notification text"))
    }

    @Test fun removedSourceCancelsEveryPendingVersionAndClosesClaimBeforeTransport() {
        val first = NotificationEventLedger(AtomicFileNotificationEventStorage(context, fileName))
        first.activateAfter(0)
        first.accept(event(1))
        first.accept(event(2, NotificationEventKind.UPDATED))
        val claim = first.claim(first.pending().first().eventId, "main-thread")!!
        first.accept(event(3, NotificationEventKind.REMOVED))
        val reopened = NotificationEventLedger(AtomicFileNotificationEventStorage(context, fileName))
        assertTrue(reopened.pending().isEmpty())
        assertFalse(reopened.mayTransmit(claim))
        assertTrue(reopened.releaseUnsent(claim))
    }

    @Test fun initializedMissingOrCorruptFileFailsClosedInsteadOfResettingMigration() {
        val storage = AtomicFileNotificationEventStorage(context, fileName)
        assertNotNull(storage.read())
        val base = File(context.noBackupFilesDir, fileName)
        assertTrue(base.delete()) // This exact fixture is test-owned.
        assertNull(AtomicFileNotificationEventStorage(context, fileName).read())
        base.writeText("not a valid ledger")
        assertNull(AtomicFileNotificationEventStorage(context, fileName).read())
    }

    @Test fun atomicFileRollsBackInterruptedUpdateAndNeverExposesPartialReceipt() {
        val storage = AtomicFileNotificationEventStorage(context, fileName)
        val ledger = NotificationEventLedger(storage)
        ledger.activateAfter(0)
        ledger.accept(event(1))
        val committed = storage.read()!!
        val file = AtomicFile(File(context.noBackupFilesDir, fileName))
        val partial = file.startWrite()
        partial.write("{\"partial\":true".toByteArray())
        file.failWrite(partial)
        assertEquals(committed, AtomicFileNotificationEventStorage(context, fileName).read())
    }

    @Test fun explicitReportReceiptAndSeparateCardSurviveAtomicReopenWithoutSpeechReplay() {
        val ledger = NotificationEventLedger(AtomicFileNotificationEventStorage(context, fileName))
        ledger.activateAfter(0)
        ledger.accept(event(1))
        val claim = ledger.claim(ledger.pending().single().eventId, "main-thread")!!
        ledger.accepted(claim, "main-thread", "turn-1")
        val notice = NotificationEventReport("hans-notice-one", "Shall I draft a reply?",
            notificationEventSha256("Shall I draft a reply?"), "native-anchor")
        assertEquals(notice, ledger.commitReport(claim.eventId, "main-thread", "turn-1", notice))
        val reopened = NotificationEventLedger(AtomicFileNotificationEventStorage(context, fileName))
        assertEquals(listOf(notice), reopened.reports("main-thread"))
        assertTrue(reopened.reports("wrong-thread").isEmpty())
        assertEquals(notice, reopened.commitReport(claim.eventId, "main-thread", "turn-1", notice.copy(id = "hans-notice-retry")))
        assertEquals(0L, reopened.status().outcomes.speechQueuedCount)
    }

    @Test fun removedSourceRetainsOneAdmittedConversationMessageAcrossAtomicReopenAndTerminalReconciliation() {
        val ledger = NotificationEventLedger(AtomicFileNotificationEventStorage(context, fileName))
        ledger.activateAfter(0)
        ledger.accept(event(1))
        val claim = ledger.claim(ledger.pending().single().eventId, "main-thread")!!
        ledger.accepted(claim, "main-thread", "turn-1")
        val text = "private notice report"
        val report = NotificationEventReport("hans-notice-one", text, notificationEventSha256(text), "native-anchor")
        assertEquals(report, ledger.commitReport(claim.eventId, "main-thread", "turn-1", report))
        assertEquals(1, ledger.reports("main-thread").size)
        assertTrue(ledger.accept(event(2, NotificationEventKind.REMOVED)))
        val reopened = NotificationEventLedger(AtomicFileNotificationEventStorage(context, fileName))
        assertEquals(listOf(report), reopened.reports("main-thread"))
        assertTrue(reopened.reports("other-thread").isEmpty())
        assertTrue(reopened.pending().isEmpty())
        assertFalse(reopened.mayTransmit(claim))
        assertFalse(reopened.hasLiveReportSource(claim.eventId, "main-thread", claim.packageName, claim.androidKey))
        assertNull(reopened.reportFor(claim.eventId, "main-thread", "turn-1"))
        assertNull(reopened.reportSourceFor(claim.eventId, "main-thread", "turn-1"))
        assertNull(reopened.commitReport(claim.eventId, "main-thread", "turn-1", report))
        assertTrue(reopened.accepted(claim, "main-thread", "turn-1"))
        assertTrue(reopened.settle(claim.eventId, "main-thread", "turn-1", true))
        val terminal = NotificationEventLedger(AtomicFileNotificationEventStorage(context, fileName))
        assertEquals(listOf(report), terminal.reports("main-thread"))
        assertTrue(terminal.accepted(claim, "main-thread", "turn-1"))
        assertEquals(listOf(report), terminal.reports("main-thread"))
        assertEquals(0L, terminal.status().outcomes.speechQueuedCount)
        val record = AtomicFileNotificationEventStorage(context, fileName).read()!!.records.single()
        assertFalse(record.sourceActive)
        assertEquals("", record.payloadJson)
        assertEquals(NotificationEventPhase.COMPLETED, record.phase)
    }

    @Test fun supersededReportsSurviveAtomicReopenButPackageExclusionAndExplicitHistoryClearEraseThem() {
        val ledger = NotificationEventLedger(AtomicFileNotificationEventStorage(context, fileName))
        ledger.activateAfter(0)
        ledger.accept(event(1))
        val first = ledger.claim(ledger.pending().single().eventId, "main-thread")!!
        val firstReport = NotificationEventReport("hans-notice-one", "First admitted notice",
            notificationEventSha256("First admitted notice"), null)
        assertEquals(firstReport, ledger.commitReport(first.eventId, "main-thread", "turn-1", firstReport))
        assertTrue(ledger.settle(first.eventId, "main-thread", "turn-1", true))
        assertTrue(ledger.remove(first.packageName, first.androidKey))
        assertTrue(ledger.accept(event(2, NotificationEventKind.UPDATED)))
        val second = ledger.claim(ledger.pending().single().eventId, "main-thread")!!
        val secondReport = NotificationEventReport("hans-notice-two", "Second admitted notice",
            notificationEventSha256("Second admitted notice"), null)
        assertEquals(secondReport, ledger.commitReport(second.eventId, "main-thread", "turn-2", secondReport))
        val reopened = NotificationEventLedger(AtomicFileNotificationEventStorage(context, fileName))
        assertEquals(listOf(firstReport, secondReport), reopened.reports("main-thread"))
        assertEquals(secondReport, reopened.commitReport(second.eventId, "main-thread", "turn-2",
            secondReport.copy(id = "hans-notice-retry")))
        assertEquals(listOf(firstReport, secondReport), reopened.reports("main-thread"))
        assertTrue(reopened.reports("main-thread") { false }.isEmpty())
        assertTrue(reopened.purgeExcluded { false })
        val excluded = NotificationEventLedger(AtomicFileNotificationEventStorage(context, fileName))
        assertTrue(excluded.reports("main-thread").isEmpty())
        assertFalse(File(context.noBackupFilesDir, fileName).readText().contains(firstReport.text))
        assertFalse(File(context.noBackupFilesDir, fileName).readText().contains(secondReport.text))
        assertNull(excluded.commitReport(second.eventId, "main-thread", "turn-2", secondReport))
        assertTrue(excluded.accept(event(3)))
        val third = excluded.claim(excluded.pending().single().eventId, "main-thread")!!
        assertEquals(secondReport, excluded.commitReport(third.eventId, "main-thread", "turn-3", secondReport))
        assertTrue(excluded.clearPrivateData())
        val cleared = NotificationEventLedger(AtomicFileNotificationEventStorage(context, fileName))
        assertTrue(cleared.reports("main-thread").isEmpty())
        assertFalse(File(context.noBackupFilesDir, fileName).readText().contains(secondReport.text))
        assertTrue(cleared.accept(event(3)))
        assertTrue(cleared.pending().isEmpty())
        assertNull(cleared.commitReport(third.eventId, "main-thread", "turn-3", secondReport))
    }

    @Test fun trafficStormCoalescesDurablyAndAcceptedBaselineSurvivesAtomicReopen() {
        val storage = AtomicFileNotificationEventStorage(context, fileName)
        val ledger = NotificationEventLedger(storage)
        assertTrue(ledger.activateAfter(0))
        fun traffic(sequence: Long) = event(sequence,
            if (sequence == 1L) NotificationEventKind.POSTED else NotificationEventKind.UPDATED).let {
            it.copy(snapshot = it.snapshot.copy(title = "Connected", category = "service", ongoing = true,
                clearable = false, text = "↓ ${sequence}.00 MB | 1.00 KB/s  ↑ 2.00 MB | 0.00 B/s"))
        }
        for (sequence in 1L..300L) assertTrue(ledger.accept(traffic(sequence)))
        assertEquals(1, ledger.pending().size)
        val claim = ledger.claim(ledger.pending().single().eventId, "main-thread")!!
        assertTrue(ledger.accepted(claim, "main-thread", "turn"))
        assertTrue(ledger.settle(claim.eventId, "main-thread", "turn", true))
        val reopened = NotificationEventLedger(AtomicFileNotificationEventStorage(context, fileName))
        assertTrue(reopened.activateAfter(300))
        assertTrue(reopened.accept(traffic(301)))
        assertTrue(reopened.pending().isEmpty())
        assertEquals(300L, reopened.status().technicalUpdateCount)
        assertTrue(reopened.accept(traffic(302).let { it.copy(snapshot = it.snapshot.copy(text = "Connection lost")) }))
        assertEquals(1, reopened.pending().size)
    }

    @Test fun legacyFullReadyStormMigratesWithoutChangingSurvivingPayloadOrReplayingCursor() {
        val storage = AtomicFileNotificationEventStorage(context, fileName)
        val ledger = NotificationEventLedger(storage)
        assertTrue(ledger.activateAfter(0))
        val state = storage.read()!!
        val events = mutableMapOf<Long, NotificationInboxEvent>()
        val records = (1L..256L).map { sequence ->
            val e = event(sequence, if (sequence == 1L) NotificationEventKind.POSTED else NotificationEventKind.UPDATED).let {
                it.copy(snapshot = it.snapshot.copy(title = "Connected", category = "service", ongoing = true,
                    clearable = false, text = "↓ ${sequence}.00 MB | 1.00 KB/s  ↑ 2.00 MB | 0.00 B/s"))
            }
            val id = notificationEventSha256("${state.generation}:$sequence")
            events[sequence] = e
            val payload = NotificationExternalEventPayload.encode(id, e, includeStatusMetadata = false)
            NotificationEventRecord(sequence, id, e.snapshot.packageName, e.snapshot.androidKey, payload,
                notificationEventSha256(payload), NotificationEventPhase.READY, true)
        }
        storage.write(state.copy(lastObservedSequence = 256, records = records))
        val reopened = NotificationEventLedger(AtomicFileNotificationEventStorage(context, fileName))
        assertTrue(reopened.activateAfter(999, events::get))
        assertEquals(records.last().payloadJson, reopened.pending().single().payloadJson)
        assertEquals(256L, storage.read()!!.lastObservedSequence)
        assertTrue(reopened.accept(event(10)))
        assertEquals(1, reopened.pending().size)
    }

    private fun event(sequence: Long, kind: NotificationEventKind = NotificationEventKind.POSTED) =
        NotificationInboxEvent(sequence, kind, sequence, null, NotificationSnapshot("example.app", "source-key",
            sequence, sequence, "Notification", "private notification text $sequence", "", "msg", "channel", false, true, emptyList()))
}
