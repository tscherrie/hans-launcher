package ai.hans.standard.integration

import ai.hans.standard.codex.AgentMessagePhase
import ai.hans.standard.codex.CodexSessionReducer
import ai.hans.standard.codex.ReasoningEffort
import ai.hans.standard.codex.TurnStatus
import ai.hans.standard.localization.AndroidHansTextResolver
import ai.hans.standard.notifications.hooks.notificationHookOutcomeDiagnosticJson
import ai.hans.standard.voice.tts.CodexTimelineSpeechProjector
import ai.hans.standard.voice.tts.TtsMessageId
import ai.hans.standard.voice.tts.TtsMessageKind
import ai.hans.standard.voice.tts.TtsMessageRevision
import ai.hans.standard.voice.tts.TtsPlaybackEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Local deterministic report/Android-JSON tests. No push send, cloud call, recording or audio. */
@RunWith(AndroidJUnit4::class)
class NativeNotificationSpeechAdmissionAndroidTest {
    @Test fun irrelevantBusyHookAndOrdinaryUserFinalRemainSilentWithoutAnExplicitReport() {
        val f = Fixture()
        f.accept(turnId = "user-B")
        val item = final("user-B")
        f.observe(listOf(item), listOf(terminal("user-B")))
        val projector = CodexTimelineSpeechProjector(text = AndroidHansTextResolver(
            InstrumentationRegistry.getInstrumentation().targetContext))
        projector.accept(snapshot(), enabled = false)
        val sink = Sink()
        val speech = CodexSpeechAdmission(Any(), sink)
        speech.finishDispatch(speech.beginDispatch(), true, false, 10, true, false)
        speech.project(true, false, false) { intent, enabled ->
            assertFalse(enabled)
            assertFalse(intent.readAloud)
            projector.accept(snapshot(item), enabled, intent.baselineOrder)
        }

        assertTrue(sink.outputs.isEmpty())
        assertTrue(f.admission.cards("thread").isEmpty())
        assertFalse(f.admission.outputIsNotificationOwned(item.id))
        assertFalse(f.admission.hasOutstandingSpeech(2))
        val json = notificationHookOutcomeDiagnosticJson(f.admission.snapshot())
        assertEquals(1L, json.getLong("acceptedCount"))
        assertEquals(1L, json.getLong("visibleFinalCount"))
        assertEquals(0L, json.getLong("reportPresentedCount"))
        assertEquals(0L, json.getLong("speechQueuedCount"))
        assertEquals("terminal_without_explicit_report",
            json.getJSONArray("recent").getJSONObject(0).getString("speechGate"))
    }

    @Test fun exactAckAndExplicitEventIdCreateIndependentIdempotentCardsNotNativeFinalSpeech() {
        val f = Fixture()
        val token = f.begin()
        assertNull(f.admission.sourceForReport("private-event", "thread", "work", 1))
        assertRejected(f.report())
        f.admission.receipt(token, NativeNotificationDispatchReceipt.Accepted("thread", "work"))
        assertRejected(f.admission.report("unknown-event", "thread", "work", 1, "Report", null, 2, null))
        assertRejected(f.admission.report("private-event", "other-thread", "work", 1, "Report", null, 2, null))
        assertRejected(f.admission.report("private-event", "thread", "other-turn", 1, "Report", null, 2, null))
        assertRejected(f.admission.report("private-event", "thread", "work", 2, "Report", null, 2, null))
        assertEquals(f.source, f.admission.sourceForReport("private-event", "thread", "work", 1))
        val first = f.present()
        val replay = f.present()
        assertTrue(first.speechAllowed)
        assertFalse(first.replay)
        assertEquals(first.card, replay.card)
        assertTrue(replay.replay)
        assertFalse(replay.speechAllowed)
        assertNotEquals("native-final", first.card.id)
        assertTrue(f.admission.outputIsNotificationOwned(first.card.id))
        assertFalse(f.admission.outputIsNotificationOwned("native-final"))
        assertTrue(f.admission.outputAllowed(first.card.id, 2))
        assertFalse(f.admission.outputAllowed(first.card.id, 3))
        assertEquals("notification_report_conflict",
            (f.report(text = "Changed body") as NativeNotificationSpeechAdmission.ReportResult.Rejected).code)
        f.accept(eventId = "independent-event")
        val other = f.present(eventId = "independent-event")
        assertNotEquals(first.card.id, other.card.id)
        assertEquals(first.card.text, other.card.text)
        assertEquals(listOf(first.card, other.card), f.admission.cards("thread"))
        assertTrue(f.admission.cards("other-thread").isEmpty())
        assertEquals(2L, f.admission.snapshot().reportPresentedCount)
        assertEquals(2L, f.admission.snapshot().speechQueuedCount)
    }

    @Test fun sourceAndPrivacyRevocationFenceLateAckWithoutRevokingAnotherSourcesCard() {
        val f = Fixture()
        val otherSource = NativeNotificationSpeechAdmission.Source("other.app", "other-key")
        f.accept()
        f.accept(eventId = "other-event", source = otherSource)
        val own = f.present()
        val other = f.present(eventId = "other-event")
        assertTrue(f.admission.revokeSource(NativeNotificationSpeechAdmission.Source("unrelated.app", "key")).isEmpty())
        assertEquals(setOf(own.card.id), f.admission.revokeSource(f.source))
        assertFalse(f.admission.outputAllowed(own.card.id, 2))
        assertTrue(f.admission.outputAllowed(other.card.id, 2))
        assertEquals(listOf(other.card), f.admission.cards("thread"))
        // The earlier source clear already removed its private report text and card.
        assertEquals(setOf(other.card.id), f.admission.revokeAll("privacy_revoked"))
        assertTrue(f.admission.cards("thread").isEmpty())
        assertFalse(f.admission.outputAllowed(other.card.id, 2))
        assertTrue(f.admission.outputAllowed("ordinary-user-final", 2))

        listOf(false, true).forEach { privacy ->
            val late = Fixture()
            val token = late.begin()
            if (privacy) late.admission.revokeAll("privacy_revoked") else late.admission.revokeSource(late.source)
            late.admission.receipt(token, NativeNotificationDispatchReceipt.Accepted("thread", "work"))
            assertNull(late.admission.sourceForReport("private-event", "thread", "work", 1))
            assertRejected(late.report())
            assertEquals(0L, late.admission.snapshot().reportPresentedCount)
            assertEquals(0L, late.admission.snapshot().speechQueuedCount)
        }
    }

    @Test fun initialMuteWatermarkAndCurrentGateKeepTextButNeverReviveOldSpeechOrSession() {
        listOf("initial", "watermark", "current").forEach { mode ->
            val f = Fixture()
            f.accept(initialGate = if (mode == "initial") "ringer_or_media_muted" else null)
            if (mode == "watermark") f.admission.suppressThrough(100)
            val report = f.present(gate = if (mode == "current") "dnd_suppressed" else null)
            assertFalse(report.speechAllowed)
            assertEquals(listOf(report.card), f.admission.cards("thread"))
            assertFalse(f.admission.outputAllowed(report.card.id, 2))
            f.observe(listOf(final()), emptyList())
            assertFalse(f.present().speechAllowed)
            assertEquals(0L, f.admission.snapshot().speechQueuedCount)
            f.admission.observe("thread", 2, listOf(final()), emptyList(), null) { true }
            assertTrue(f.admission.cards("thread").isEmpty())
            assertRejected(f.report())
            assertFalse(f.admission.outputAllowed(report.card.id, 2))
        }
    }

    @Test fun androidDiagnosticsSeparateReportFinalAndTerminalAndKeepUserSpeechIntentUnchanged() {
        val f = Fixture()
        f.accept()
        val admitted = f.admission.snapshot().recent.single()
        assertTrue(admitted.claimSettled)
        assertFalse(admitted.turnTerminal)
        assertFalse(admitted.finalVisible)
        assertFalse(admitted.reportPresented)
        val sink = Sink()
        val speech = CodexSpeechAdmission(Any(), sink)
        speech.finishDispatch(speech.beginDispatch(), true, true, 42, true, true)
        val epoch = speech.ensureOutputEpoch()
        val report = f.present(speechEpoch = epoch)
        assertTrue(report.speechAllowed)
        assertTrue(speech.submitSupplementary(
            TtsMessageRevision(TtsMessageId(report.card.id), 1, report.card.text,
                TtsMessageKind.FINAL_OUTPUT, true), epoch, true, true))
        speech.project(true, false, true) { intent, enabled ->
            assertTrue(enabled)
            assertTrue(intent.readAloud)
            assertEquals(42L, intent.baselineOrder)
            assertEquals(epoch, intent.epoch)
            emptyList()
        }
        assertEquals(listOf(report.card.id), sink.outputs)
        assertEquals(epoch, speech.epoch)
        assertFalse(f.admission.snapshot().recent.single().finalVisible)
        f.observe(listOf(final()), listOf(terminal()))
        f.observe(emptyList(), emptyList())
        f.admission.deliveryEvent(TtsPlaybackEvent.MessageCompleted(TtsMessageId(report.card.id)))
        assertFalse(f.admission.hasOutstandingSpeech(epoch))
        assertEquals(epoch, speech.ensureOutputEpoch(rotateQuiescentSupplement = true))
        repeat(55) { index ->
            f.admission.receipt(f.begin(eventId = "private-rejected-$index"),
                NativeNotificationDispatchReceipt.RejectedByRuntime)
        }
        val json = notificationHookOutcomeDiagnosticJson(f.admission.snapshot())
        assertEquals(1L, json.getLong("acceptedCount"))
        assertEquals(1L, json.getLong("visibleFinalCount"))
        assertEquals(1L, json.getLong("reportPresentedCount"))
        assertEquals(1L, json.getLong("speechQueuedCount"))
        assertEquals(48, json.getJSONArray("recent").length())
        val serialized = json.toString()
        listOf("private-event", "private-rejected", "private.app", "private-key", "thread",
            "work\"", "Should I draft a reply?", "hans-notice-", "native-final").forEach {
            assertFalse(it, serialized.contains(it))
        }
        assertTrue(serialized.length < 32_768)
    }

    private class Fixture {
        val source = NativeNotificationSpeechAdmission.Source("private.app", "private-key")
        private var nextId = 0
        val admission = NativeNotificationSpeechAdmission { (++nextId).toString() }
        fun begin(eventId: String = "private-event", source: NativeNotificationSpeechAdmission.Source = this.source,
            initialGate: String? = null) = admission.begin(eventId, source, "thread", 1, 10, 100, initialGate)
        fun accept(eventId: String = "private-event", source: NativeNotificationSpeechAdmission.Source = this.source,
            turnId: String = "work", initialGate: String? = null) {
            admission.receipt(requireNotNull(begin(eventId, source, initialGate)),
                NativeNotificationDispatchReceipt.Accepted("thread", turnId))
        }
        fun report(eventId: String = "private-event", text: String = "Should I draft a reply?",
            speechEpoch: Long = 2, gate: String? = null) =
            admission.report(eventId, "thread", "work", 1, text, "native-final", speechEpoch, gate)
        fun present(eventId: String = "private-event", speechEpoch: Long = 2, gate: String? = null) =
            (report(eventId, speechEpoch = speechEpoch, gate = gate)
                as NativeNotificationSpeechAdmission.ReportResult.Presented).also { result ->
                    if (result.speechAllowed) admission.reportSubmissionResult(result.card.id, true)
                }
        fun observe(items: List<ClientTimelineItem>, terminalTurns: List<ClientTerminalTurn>) =
            admission.observe("thread", 1, items, terminalTurns, null) { true }
    }

    private class Sink : CodexSpeechAdmission.Sink {
        val outputs = mutableListOf<String>()
        override fun begin(epoch: Long) = Unit
        override fun configure(enabled: Boolean, allowIntermediate: Boolean, epoch: Long) = Unit
        override fun held(active: Boolean, inputRevision: Long) = Unit
        override fun prepare(epoch: Long) = Unit
        override fun submit(revision: TtsMessageRevision, epoch: Long) { outputs += revision.messageId.value }
    }

    private fun assertRejected(result: NativeNotificationSpeechAdmission.ReportResult) {
        assertTrue(result is NativeNotificationSpeechAdmission.ReportResult.Rejected)
        assertEquals("notification_report_no_live_receipt",
            (result as NativeNotificationSpeechAdmission.ReportResult.Rejected).code)
    }
    private fun final(turnId: String = "work") = ClientTimelineItem(
        "native-final", ClientTimelineRole.HANS, "Ordinary user answer.", 11, 1, true,
        ClientTimelineStatus.COMPLETE, AgentMessagePhase.FINAL_ANSWER, turnId)
    private fun terminal(turnId: String = "work") = ClientTerminalTurn("thread", turnId, TurnStatus.COMPLETED)
    private fun snapshot(vararg items: ClientTimelineItem) = CodexClientSnapshot(
        ClientRuntimePhase.READY, ClientSessionPhase.BUSY, 1, CodexSessionReducer().snapshot(),
        emptyList(), null, emptyList(), items.toList(), null,
        DispatchSelection("gpt-6-astra", ReasoningEffort.MEDIUM), null)
}
