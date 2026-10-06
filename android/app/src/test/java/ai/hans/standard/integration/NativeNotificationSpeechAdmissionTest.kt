package ai.hans.standard.integration

import ai.hans.standard.codex.AgentMessagePhase
import ai.hans.standard.codex.CodexSessionReducer
import ai.hans.standard.codex.ReasoningEffort
import ai.hans.standard.codex.TurnStatus
import ai.hans.standard.localization.TestResourceTextResolver
import ai.hans.standard.notifications.hooks.notificationHookOutcomeDiagnosticJson
import ai.hans.standard.voice.tts.CodexTimelineSpeechProjector
import ai.hans.standard.voice.tts.TtsMessageDropReason
import ai.hans.standard.voice.tts.TtsMessageId
import ai.hans.standard.voice.tts.TtsMessageKind
import ai.hans.standard.voice.tts.TtsMessageRevision
import ai.hans.standard.voice.tts.TtsPlaybackEvent
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Actual native intake and explicit event reporting are intentionally separate authorities. */
class NativeNotificationSpeechAdmissionTest {
    @Test fun irrelevantBusyHookAndUsersFinalNeverCreateAReportOrNewSpeechAuthority() {
        val f = Fixture()
        f.accept(turnId = "user-B")
        val item = final(turnId = "user-B", text = "Finished the user's unrelated request.")
        val projector = CodexTimelineSpeechProjector(text = TestResourceTextResolver(Locale.ENGLISH))
        projector.accept(snapshot(), enabled = false)
        f.observe(listOf(item), listOf(terminal("user-B")))
        val sink = Sink()
        val userSpeech = CodexSpeechAdmission(Any(), sink)
        userSpeech.finishDispatch(userSpeech.beginDispatch(), true, false, 10, true, false)
        userSpeech.project(true, false, false) { intent, userEnabled ->
            assertFalse(userEnabled)
            assertFalse(intent.readAloud)
            projector.accept(snapshot(item), enabled = userEnabled, speakAfterOrderExclusive = intent.baselineOrder)
        }

        assertTrue(sink.outputs.isEmpty())
        assertTrue(f.admission.cards("thread").isEmpty())
        assertFalse(f.admission.outputIsNotificationOwned(item.id))
        assertFalse(f.admission.hasOutstandingSpeech(2))
        val diagnostics = f.admission.snapshot()
        assertEquals(1L, diagnostics.acceptedCount)
        assertEquals(1L, diagnostics.visibleFinalCount)
        assertEquals(0L, diagnostics.reportPresentedCount)
        assertEquals(0L, diagnostics.speechQueuedCount)
        assertEquals("terminal_without_explicit_report", diagnostics.recent.single().speechGate)
    }

    @Test fun onlyAnExplicitAcceptedEventReportCreatesItsOwnCardAndSpeechGuard() {
        val f = Fixture()
        f.accept()
        f.observe(listOf(final()))
        val report = f.present()
        assertEquals("hans-notice-1", report.card.id)
        assertNotEquals("native-final", report.card.id)
        assertEquals("native-final", report.card.timelineAnchorId)
        assertEquals("thread", report.card.threadId)
        assertEquals("Should I draft a reply?", report.card.text)
        assertTrue(report.speechAllowed)
        assertFalse(report.replay)
        assertEquals(listOf(report.card), f.admission.cards("thread"))
        assertTrue(f.admission.cards("other-thread").isEmpty())
        assertTrue(f.admission.cards(null).isEmpty())
        assertEquals(f.source, f.admission.sourceForReport("private-event", "thread", "work", 1))
        assertTrue(f.admission.outputIsNotificationOwned(report.card.id))
        assertFalse(f.admission.outputIsNotificationOwned("native-final"))
        assertTrue(f.admission.outputAllowed(report.card.id, 2))
        assertEquals(1L, f.admission.snapshot().reportPresentedCount)
        assertEquals(1L, f.admission.snapshot().speechQueuedCount)
    }

    @Test fun unknownNoAckAndWrongThreadTurnOrClientEpochCannotReport() {
        val f = Fixture()
        f.begin()
        assertNull(f.admission.sourceForReport("private-event", "thread", "work", 1))
        assertRejected(f.report(), "notification_report_no_live_receipt")
        f.accept(eventId = "accepted-event")
        listOf(
            ReportCaller("missing-event", "thread", "work", 1),
            ReportCaller("accepted-event", "other-thread", "work", 1),
            ReportCaller("accepted-event", "thread", "other-turn", 1),
            ReportCaller("accepted-event", "thread", "work", 2),
        ).forEach { caller ->
            assertNull(f.admission.sourceForReport(caller.eventId, caller.threadId, caller.turnId, caller.clientEpoch))
            assertRejected(f.report(caller = caller), "notification_report_no_live_receipt")
        }
        assertTrue(f.admission.cards("thread").isEmpty())
        assertEquals(0L, f.admission.snapshot().reportPresentedCount)
        assertEquals(0L, f.admission.snapshot().speechQueuedCount)
    }

    @Test fun rejectedAmbiguousOrMismatchedNativeReceiptsAreNeverReportProof() {
        listOf(
            NativeNotificationDispatchReceipt.RejectedByRuntime,
            NativeNotificationDispatchReceipt.OutcomeAmbiguous,
            NativeNotificationDispatchReceipt.Accepted("other-thread", "work"),
            NativeNotificationDispatchReceipt.Accepted("thread", ""),
        ).forEach { receipt ->
            val f = Fixture()
            f.admission.receipt(f.begin(), receipt)
            f.observe(listOf(final()))
            assertNull(f.admission.sourceForReport("private-event", "thread", "work", 1))
            assertRejected(f.report(), "notification_report_no_live_receipt")
            val row = f.admission.snapshot().recent.single()
            assertTrue(row.claimSettled)
            assertFalse(row.accepted)
            assertFalse(row.turnTerminal)
            assertFalse(row.reportPresented)
            assertFalse(f.admission.hasOutstandingSpeech(2))
        }
    }

    @Test fun identicalReplayKeepsOneCardOneQueueAdmissionAndTheOriginalSpeechEpoch() {
        val f = Fixture()
        f.accept()
        val first = f.present()
        val replay = f.present(anchorId = "later-native-final", speechEpoch = 3)
        assertEquals(first.card, replay.card)
        assertTrue(replay.replay)
        assertFalse(replay.speechAllowed)
        assertEquals(listOf(first.card), f.admission.cards("thread"))
        assertEquals(1L, f.admission.snapshot().reportPresentedCount)
        assertEquals(1L, f.admission.snapshot().speechQueuedCount)
        assertTrue(f.admission.outputAllowed(first.card.id, 2))
        assertFalse(f.admission.outputAllowed(first.card.id, 3))
    }

    @Test fun changedTextForTheSameEventIsAConflictNotAnUpdatedOrSecondReport() {
        val f = Fixture()
        f.accept()
        val first = f.present()
        assertRejected(f.report(text = "Different instructions."), "notification_report_conflict")
        assertEquals(listOf(first.card), f.admission.cards("thread"))
        assertEquals(1L, f.admission.snapshot().reportPresentedCount)
        assertEquals(1L, f.admission.snapshot().speechQueuedCount)
        assertTrue(f.admission.outputAllowed(first.card.id, 2))
    }

    @Test fun identicalMessagesFromIndependentEventsKeepDistinctReportsEvenInTheSameTurn() {
        val f = Fixture()
        f.accept(eventId = "event-one")
        f.accept(eventId = "event-two")
        val one = f.present(eventId = "event-one")
        val two = f.present(eventId = "event-two")
        assertNotEquals(one.card.id, two.card.id)
        assertEquals(one.card.text, two.card.text)
        assertEquals(listOf(one.card, two.card), f.admission.cards("thread"))
        assertTrue(one.speechAllowed)
        assertTrue(two.speechAllowed)
        val diagnostics = f.admission.snapshot()
        assertEquals(2L, diagnostics.acceptedCount)
        assertEquals(2L, diagnostics.reportPresentedCount)
        assertEquals(2L, diagnostics.speechQueuedCount)
        assertEquals(diagnostics.recent[0].workOrdinal, diagnostics.recent[1].workOrdinal)
        assertNotEquals(diagnostics.recent[0].eventOrdinal, diagnostics.recent[1].eventOrdinal)
    }

    @Test fun sourceRemovalRevokesOnlyItsOwnReportsAndCannotStopIndependentUserOutput() {
        val f = Fixture()
        val otherSource = NativeNotificationSpeechAdmission.Source("different.app", "different-key")
        f.accept()
        f.accept(eventId = "other-event", source = otherSource)
        val own = f.present()
        val other = f.present(eventId = "other-event")
        assertTrue(f.admission.revokeSource(NativeNotificationSpeechAdmission.Source("unrelated.app", "key")).isEmpty())
        assertTrue(f.admission.outputAllowed(own.card.id, 2))
        assertEquals(setOf(own.card.id), f.admission.revokeSource(f.source))
        assertFalse(f.admission.outputAllowed(own.card.id, 2))
        assertTrue(f.admission.outputAllowed(other.card.id, 2))
        assertTrue(f.admission.outputAllowed("ordinary-user-final", 2))
        assertFalse(f.admission.outputIsNotificationOwned("ordinary-user-final"))
        assertEquals(listOf(other.card), f.admission.cards("thread"))
        assertNull(f.admission.sourceForReport("private-event", "thread", "work", 1))
        assertRejected(f.report(), "notification_report_no_live_receipt")
    }

    @Test fun sourceOrPrivacyRevocationBeforeAckCannotBeUndoneByALateAcceptedReceipt() {
        listOf(false, true).forEach { global ->
            val f = Fixture()
            val token = f.begin()
            if (global) f.admission.revokeAll("privacy_revoked") else f.admission.revokeSource(f.source)
            f.admission.receipt(token, NativeNotificationDispatchReceipt.Accepted("thread", "work"))
            assertNull(f.admission.sourceForReport("private-event", "thread", "work", 1))
            assertRejected(f.report(), "notification_report_no_live_receipt")
            assertTrue(f.admission.cards("thread").isEmpty())
            assertEquals(0L, f.admission.snapshot().reportPresentedCount)
            assertEquals(0L, f.admission.snapshot().speechQueuedCount)
            assertEquals(if (global) "privacy_revoked" else "source_removed",
                f.admission.snapshot().recent.single().revocationReason)
        }
    }

    @Test fun initialMuteCurrentGateAndMuteWatermarkAllowTextButNeverReplayOldSpeech() {
        listOf("initial", "current", "watermark").forEach { mode ->
            val f = Fixture()
            f.accept(initialGate = if (mode == "initial") "ringer_or_media_muted" else null)
            if (mode == "watermark") assertTrue(f.admission.suppressThrough(100).isEmpty())
            val report = f.present(gate = if (mode == "current") "dnd_suppressed" else null)
            assertFalse(report.speechAllowed)
            assertEquals(listOf(report.card), f.admission.cards("thread"))
            assertTrue(f.admission.outputIsNotificationOwned(report.card.id))
            assertFalse(f.admission.outputAllowed(report.card.id, 2))
            f.observe(listOf(final())) // volume returned; speech suppression remains monotone
            val replay = f.present()
            assertTrue(replay.replay)
            assertFalse(replay.speechAllowed)
            assertEquals(0L, f.admission.snapshot().speechQueuedCount)
            f.accept(eventId = "fresh-event", observedAt = 101)
            assertTrue(f.present(eventId = "fresh-event").speechAllowed)
        }
        val queued = Fixture()
        queued.accept()
        val report = queued.present()
        assertEquals(setOf(report.card.id), queued.admission.suppressThrough(100))
        assertFalse(queued.admission.outputAllowed(report.card.id, 2))
        assertEquals(listOf(report.card), queued.admission.cards("thread"))
        assertFalse(queued.present().speechAllowed)
    }

    @Test fun clientAndSpeechEpochGuardsNeverRebindAnOldNoticeIntoANewSession() {
        val f = Fixture()
        f.accept()
        val report = f.present()
        assertFalse(f.admission.outputAllowed(report.card.id, 1))
        assertFalse(f.admission.outputAllowed(report.card.id, 3))
        assertRejected(f.report(caller = ReportCaller("private-event", "thread", "work", 2)),
            "notification_report_no_live_receipt")
        f.admission.observe("thread", 2, listOf(final()), emptyList(), null) { true }
        assertFalse(f.admission.outputAllowed(report.card.id, 2))
        assertTrue(f.admission.cards("thread").isEmpty())
        assertNull(f.admission.sourceForReport("private-event", "thread", "work", 1))
        assertEquals("session_changed", f.admission.snapshot().recent.single().revocationReason)
    }

    @Test fun nativeFinalExplicitReportClaimSettlementAndTurnTerminalAreIndependentMonotoneProofs() {
        val f = Fixture()
        f.accept()
        val acknowledged = f.admission.snapshot().recent.single()
        assertTrue(acknowledged.accepted)
        assertTrue(acknowledged.claimSettled)
        assertFalse(acknowledged.turnTerminal)
        assertFalse(acknowledged.finalVisible)
        assertFalse(acknowledged.reportPresented)
        f.present(gate = "ringer_or_media_muted")
        assertTrue(f.admission.snapshot().recent.single().reportPresented)
        assertFalse(f.admission.snapshot().recent.single().finalVisible)
        f.admission.observe("thread", 1, listOf(final()), emptyList(), null) { false }
        assertEquals(0L, f.admission.snapshot().visibleFinalCount)
        f.observe(listOf(final()), listOf(terminal()))
        f.observe(emptyList(), emptyList()) // shrinking snapshots cannot erase prior positive proofs
        f.observe(listOf(final()), listOf(terminal()))
        val diagnostics = f.admission.snapshot()
        val row = diagnostics.recent.single()
        assertTrue(row.turnTerminal)
        assertTrue(row.finalVisible)
        assertTrue(row.reportPresented)
        assertEquals(1L, diagnostics.visibleFinalCount)
        assertEquals(1L, diagnostics.reportPresentedCount)
        assertEquals(0L, diagnostics.speechQueuedCount)
    }

    @Test fun terminalQueuedOrDroppedSpeechRemainsOutstandingUntilPositiveRelease() {
        val f = Fixture()
        f.accept()
        val report = f.present()
        f.observe(emptyList(), listOf(terminal()))
        assertTrue(f.admission.hasOutstandingSpeech(2))
        f.admission.deliveryEvent(TtsPlaybackEvent.MessageDropped(
            TtsMessageId(report.card.id), TtsMessageDropReason.ADMISSION_REJECTED))
        assertTrue(f.admission.hasOutstandingSpeech(2))
        f.admission.playback(report.card.id, "STOPPING", null, released = false)
        assertTrue(f.admission.hasOutstandingSpeech(2))
        f.admission.confirmOutputsReleased(setOf("unrelated-output"))
        assertTrue(f.admission.hasOutstandingSpeech(2))
        f.admission.confirmOutputsReleased(setOf(report.card.id))
        assertFalse(f.admission.hasOutstandingSpeech(2))
        f.accept(eventId = "completed-event")
        val completed = f.present(eventId = "completed-event")
        f.admission.deliveryEvent(TtsPlaybackEvent.MessageCompleted(TtsMessageId(completed.card.id)))
        assertFalse(f.admission.hasOutstandingSpeech(2))
    }

    @Test fun settledAcknowledgementsAndTerminalQueuedReportsCannotBeEvictedAtCapacity() {
        listOf(false, true).forEach { queued ->
            val f = Fixture()
            val cards = mutableListOf<NativeNotificationReportCard>()
            repeat(256) { index ->
                val eventId = "event-$index"
                val turnId = "work-$index"
                f.accept(eventId = eventId, turnId = turnId)
                if (queued) {
                    cards += f.present(eventId = eventId, turnId = turnId).card
                    f.observe(emptyList(), listOf(terminal(turnId)))
                }
            }
            assertNull(f.begin(eventId = "blocked-event"))
            assertEquals(1L, f.admission.snapshot().speechCapacityBlockedCount)
            if (queued) {
                assertTrue(f.admission.hasOutstandingSpeech(2))
                f.admission.deliveryEvent(TtsPlaybackEvent.MessageCompleted(TtsMessageId(cards.first().id)))
            } else {
                assertTrue(f.admission.snapshot().recent.all { it.claimSettled && !it.turnTerminal })
                f.observe(emptyList(), listOf(terminal("work-0")))
            }
            assertNotNull(f.begin(eventId = "new-event"))
        }
    }

    @Test fun completedReportsAndRejectedClaimsDoNotExhaustCapacityAndDiagnosticsAreBoundedContentFree() {
        val f = Fixture()
        var firstReportId: String? = null
        repeat(300) { index ->
            val eventId = "private-event-$index"
            val turnId = "private-work-$index"
            f.accept(eventId = eventId, turnId = turnId)
            val card = f.present(eventId = eventId, turnId = turnId,
                text = "PRIVATE report body $index").card
            if (index == 0) firstReportId = card.id
            f.observe(emptyList(), listOf(terminal(turnId)))
            f.admission.deliveryEvent(TtsPlaybackEvent.MessageCompleted(TtsMessageId(card.id)))
        }
        val diagnostics = f.admission.snapshot()
        assertEquals(300L, diagnostics.acceptedCount)
        assertEquals(300L, diagnostics.reportPresentedCount)
        assertEquals(300L, diagnostics.speechQueuedCount)
        assertEquals(0L, diagnostics.speechCapacityBlockedCount)
        assertEquals(48, diagnostics.recent.size)
        assertTrue(diagnostics.recent.all { it.claimSettled && it.turnTerminal && it.reportPresented })
        assertFalse(f.admission.hasOutstandingSpeech(2))
        assertTrue(f.admission.outputIsNotificationOwned(requireNotNull(firstReportId)))
        assertFalse(f.admission.outputAllowed(requireNotNull(firstReportId), 2))
        assertFalse(f.admission.outputAllowed("hans-notice-unknown", 2))
        val json = notificationHookOutcomeDiagnosticJson(diagnostics).toString()
        listOf("private-event", "private-work", "private.app", "private-key", "PRIVATE report body",
            "hans-notice-", "thread", "native-final").forEach { assertFalse(it, json.contains(it)) }
        assertEquals(48, notificationHookOutcomeDiagnosticJson(diagnostics).getJSONArray("recent").length())
        assertTrue(json.length < 32_768)

        val rejected = Fixture()
        repeat(300) { index ->
            rejected.admission.receipt(rejected.begin(eventId = "rejected-$index"),
                NativeNotificationDispatchReceipt.RejectedByRuntime)
        }
        assertEquals(0L, rejected.admission.snapshot().speechCapacityBlockedCount)
        assertEquals(48, rejected.admission.snapshot().recent.size)
        assertTrue(rejected.admission.snapshot().recent.all { it.claimSettled && !it.turnTerminal && !it.accepted })
    }

    @Test fun explicitReportSupplementPreservesUsersOriginalIntentBaselineAndEpoch() {
        listOf(false, true).forEach { readAloud ->
            val f = Fixture()
            f.accept()
            val sink = Sink()
            val speech = CodexSpeechAdmission(Any(), sink)
            speech.finishDispatch(speech.beginDispatch(), true, readAloud, 42, true, true)
            val epoch = speech.ensureOutputEpoch()
            val report = f.present(speechEpoch = epoch)
            assertTrue(report.speechAllowed)
            assertTrue(speech.submitSupplementary(revision(report.card), epoch, true, true))
            assertEquals(listOf(report.card.id), sink.outputs)
            assertTrue(sink.enabled)
            assertEquals(readAloud, sink.intermediate)
            val projector = CodexTimelineSpeechProjector(text = TestResourceTextResolver(Locale.ENGLISH))
            projector.accept(snapshot(), enabled = false)
            speech.project(true, false, true) { intent, userEnabled ->
                assertEquals(readAloud, userEnabled)
                assertEquals(readAloud, intent.readAloud)
                assertEquals(42L, intent.baselineOrder)
                assertEquals(epoch, intent.epoch)
                projector.accept(snapshot(final().copy(order = 43)), userEnabled,
                    speakAfterOrderExclusive = intent.baselineOrder)
            }
            assertEquals(if (readAloud) listOf(report.card.id, "native-final") else listOf(report.card.id),
                sink.outputs)
            assertEquals(epoch, speech.epoch)
            f.admission.deliveryEvent(TtsPlaybackEvent.MessageCompleted(TtsMessageId(report.card.id)))
            assertFalse(f.admission.hasOutstandingSpeech(epoch))
            if (readAloud) {
                assertEquals(epoch, speech.ensureOutputEpoch(rotateQuiescentSupplement = true))
            } else {
                assertEquals(epoch + 1, speech.ensureOutputEpoch(rotateQuiescentSupplement = true))
            }
        }
    }

    @Test fun earlyReportWaitGetsItsSourceOnlyAfterTheExactAcceptedNativeReceipt() {
        val f = Fixture()
        val token = f.begin()
        val waiter = WaitingSource(f.admission)
        try {
            waiter.assertWaiting()
            assertNull(f.admission.sourceForReport("private-event", "thread", "work", 1))
            val unrelated = f.begin(eventId = "unrelated-event")
            f.admission.receipt(unrelated, NativeNotificationDispatchReceipt.Accepted("thread", "other-work"))
            assertEquals(1L, waiter.completed.count)
            assertNull(waiter.result.get())
            f.admission.receipt(token, NativeNotificationDispatchReceipt.Accepted("thread", "work"))
            assertEquals(f.source, waiter.awaitResult())
            assertTrue(f.admission.cards("thread").isEmpty())
            assertEquals(0L, f.admission.snapshot().reportPresentedCount)
            assertEquals(0L, f.admission.snapshot().speechQueuedCount)
        } finally {
            waiter.close()
        }
    }

    @Test fun sourcePrivacyCancellationAndDefiniteRejectionWakeReportWaitWithoutAReceipt() {
        listOf("source", "privacy", "unsent", "rejected", "ambiguous").forEach { ending ->
            val f = Fixture()
            val token = f.begin()
            val waiter = WaitingSource(f.admission)
            try {
                waiter.assertWaiting()
                when (ending) {
                    "source" -> f.admission.revokeSource(f.source)
                    "privacy" -> f.admission.revokeAll("privacy_revoked")
                    "unsent" -> f.admission.unsent(token)
                    "rejected" -> f.admission.receipt(token, NativeNotificationDispatchReceipt.RejectedByRuntime)
                    else -> f.admission.receipt(token, NativeNotificationDispatchReceipt.OutcomeAmbiguous)
                }
                assertNull(ending, waiter.awaitResult())
                assertRejected(f.report(), "notification_report_no_live_receipt")
                assertEquals(0L, f.admission.snapshot().reportPresentedCount)
                assertEquals(0L, f.admission.snapshot().speechQueuedCount)
            } finally {
                waiter.close()
            }
        }
    }

    @Test fun supplementaryOutputDeniesHeldPendingStaleEpochAndInaudibleWithoutSubmitting() {
        listOf("held", "pending", "stale", "inaudible").forEach { reason ->
            val sink = Sink()
            val speech = CodexSpeechAdmission(Any(), sink)
            speech.finishDispatch(speech.beginDispatch(), true, false, 42, true, true)
            if (reason == "held") speech.setInputHeld(true, 42, true, true, false)
            val currentEpoch = speech.ensureOutputEpoch()
            val pending = if (reason == "pending") speech.beginDispatch() else null
            val expectedEpoch = if (reason == "stale") currentEpoch + 1 else currentEpoch
            val output = TtsMessageRevision(TtsMessageId("hans-notice-denied"), 1, "Report.",
                TtsMessageKind.FINAL_OUTPUT, true)
            assertFalse(reason, speech.submitSupplementary(output, expectedEpoch,
                audible = reason != "inaudible", allowIntermediate = true))
            assertTrue(reason, sink.outputs.isEmpty())
            assertEquals(currentEpoch, speech.epoch)
            if (pending != null) assertTrue(speech.rejectDispatch(pending))
        }
    }

    @Test fun reportAdmissionIsNotQueueProofAndFailedSubmissionCannotBeRevivedByReplay() {
        val f = Fixture()
        f.accept()
        val admitted = f.report() as NativeNotificationSpeechAdmission.ReportResult.Presented
        assertTrue(admitted.speechAllowed)
        assertEquals("ADMITTED", f.admission.snapshot().recent.single().playbackPhase)
        assertEquals(1L, f.admission.snapshot().reportPresentedCount)
        assertEquals(0L, f.admission.snapshot().speechQueuedCount)
        assertTrue(f.admission.hasOutstandingSpeech(2))

        f.admission.reportSubmissionResult(admitted.card.id, false)

        val failed = f.admission.snapshot().recent.single()
        assertTrue(failed.reportPresented)
        assertEquals("DROPPED", failed.playbackPhase)
        assertEquals("output_admission_not_queued", failed.speechGate)
        assertEquals(0L, f.admission.snapshot().speechQueuedCount)
        assertFalse(f.admission.hasOutstandingSpeech(2))
        assertFalse(f.admission.outputAllowed(admitted.card.id, 2))
        assertEquals(listOf(admitted.card), f.admission.cards("thread"))
        val replay = f.present()
        assertTrue(replay.replay)
        assertFalse(replay.speechAllowed)
        assertEquals(admitted.card.id, replay.card.id)
        f.admission.reportSubmissionResult(admitted.card.id, true) // contradictory late receipt
        f.admission.reportSubmissionResult("hans-notice-unknown", true)
        assertEquals(0L, f.admission.snapshot().speechQueuedCount)
        assertEquals("DROPPED", f.admission.snapshot().recent.single().playbackPhase)
    }

    /** Latches plus the exact worker's timed-wait state witness the real ACK wait, without sleeps. */
    private class WaitingSource(admission: NativeNotificationSpeechAdmission) {
        private val entered = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val result = AtomicReference<NativeNotificationSpeechAdmission.Source?>()
        private val failure = AtomicReference<Throwable?>()
        private val worker = Thread {
            entered.countDown()
            try {
                result.set(admission.awaitSourceForReport("private-event", "thread", "work", 1, 3_000))
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                completed.countDown()
            }
        }.apply { isDaemon = true; start() }

        fun assertWaiting() {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (worker.state != Thread.State.TIMED_WAITING && completed.count != 0L &&
                System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.TIMED_WAITING, worker.state)
            assertEquals(1L, completed.count)
        }

        fun awaitResult(): NativeNotificationSpeechAdmission.Source? {
            assertTrue(completed.await(1, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("ACK waiter failed", it) }
            return result.get()
        }

        fun close() {
            if (worker.isAlive) worker.interrupt()
            worker.join(1_000)
            assertFalse("ACK waiter leaked", worker.isAlive)
        }
    }

    private data class ReportCaller(val eventId: String, val threadId: String, val turnId: String, val clientEpoch: Long)

    private class Fixture {
        val source = NativeNotificationSpeechAdmission.Source("private.app", "private-key")
        private var nextId = 0
        val admission = NativeNotificationSpeechAdmission { (++nextId).toString() }
        fun begin(eventId: String = "private-event", source: NativeNotificationSpeechAdmission.Source = this.source,
            observedAt: Long = 100, initialGate: String? = null) =
            admission.begin(eventId, source, "thread", 1, 10, observedAt, initialGate)
        fun accept(eventId: String = "private-event", source: NativeNotificationSpeechAdmission.Source = this.source,
            turnId: String = "work", observedAt: Long = 100, initialGate: String? = null) {
            val token = requireNotNull(begin(eventId, source, observedAt, initialGate))
            admission.receipt(token, NativeNotificationDispatchReceipt.Accepted("thread", turnId))
        }
        fun report(caller: ReportCaller = ReportCaller("private-event", "thread", "work", 1),
            text: String = "Should I draft a reply?", anchorId: String? = "native-final",
            speechEpoch: Long = 2, gate: String? = null) =
            admission.report(caller.eventId, caller.threadId, caller.turnId, caller.clientEpoch,
                text, anchorId, speechEpoch, gate)
        fun present(eventId: String = "private-event", turnId: String = "work",
            text: String = "Should I draft a reply?", anchorId: String? = "native-final",
            speechEpoch: Long = 2, gate: String? = null) =
            (report(ReportCaller(eventId, "thread", turnId, 1), text, anchorId, speechEpoch, gate)
                as NativeNotificationSpeechAdmission.ReportResult.Presented).also { result ->
                    if (result.speechAllowed) admission.reportSubmissionResult(result.card.id, true)
                }
        fun observe(items: List<ClientTimelineItem>, terminalTurns: List<ClientTerminalTurn> = emptyList()) =
            admission.observe("thread", 1, items, terminalTurns, null) { true }
    }

    private class Sink : CodexSpeechAdmission.Sink {
        var enabled = false
        var intermediate = false
        val outputs = mutableListOf<String>()
        override fun begin(epoch: Long) = Unit
        override fun configure(enabled: Boolean, allowIntermediate: Boolean, epoch: Long) {
            this.enabled = enabled
            intermediate = allowIntermediate
        }
        override fun held(active: Boolean, inputRevision: Long) = Unit
        override fun prepare(epoch: Long) = Unit
        override fun submit(revision: TtsMessageRevision, epoch: Long) { outputs += revision.messageId.value }
    }

    companion object {
        private fun assertRejected(result: NativeNotificationSpeechAdmission.ReportResult, code: String) {
            assertTrue(result is NativeNotificationSpeechAdmission.ReportResult.Rejected)
            assertEquals(code, (result as NativeNotificationSpeechAdmission.ReportResult.Rejected).code)
        }
        private fun final(turnId: String = "work", text: String = "Ordinary final.") =
            ClientTimelineItem("native-final", ClientTimelineRole.HANS, text, 11, 1,
                true, ClientTimelineStatus.COMPLETE, AgentMessagePhase.FINAL_ANSWER, turnId)
        private fun terminal(turnId: String = "work") = ClientTerminalTurn("thread", turnId, TurnStatus.COMPLETED)
        private fun revision(card: NativeNotificationReportCard) =
            TtsMessageRevision(TtsMessageId(card.id), 1, card.text, TtsMessageKind.FINAL_OUTPUT, true)
        private fun snapshot(vararg items: ClientTimelineItem) = CodexClientSnapshot(
            ClientRuntimePhase.READY, ClientSessionPhase.BUSY, 1, CodexSessionReducer().snapshot(),
            emptyList(), null, emptyList(), items.toList(), null,
            DispatchSelection("gpt-6-astra", ReasoningEffort.MEDIUM), null)
    }
}
