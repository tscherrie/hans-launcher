package ai.hans.standard.ui

import ai.hans.standard.codex.*
import ai.hans.standard.integration.*
import ai.hans.standard.localization.TestResourceTextResolver
import ai.hans.standard.settings.HansSettings
import ai.hans.standard.voice.realtime.CodexVoiceWorkScope
import ai.hans.standard.voice.realtime.LiveVoiceTranscriptAuthor
import ai.hans.standard.voice.realtime.LiveVoiceTranscriptRevision
import ai.hans.standard.voice.tts.CodexTimelineSpeechProjector
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

/** A notice is its own local presentation owner, never a fabricated native FINAL rendition. */
class NotificationReportProjectionTest {
    private val resolver = TestResourceTextResolver(Locale.ENGLISH)
    private val notice = ChatMessageUiModel("hans-notice-one", ChatMessageAuthor.HANS,
        "Important update. Shall I draft a reply?", revision = 0, complete = true, localArrivalOrder = 2)

    @Test fun ownNoticeCannotSuppressTheDelegatedVoiceAnswerOrAcquireOrdinaryTimelineTtsAuthority() {
        val voice = LiveVoiceTranscriptRevision("voice-session", 1, "voice-item", LiveVoiceTranscriptAuthor.HANS,
            "Still working on your request.", true, CodexVoiceWorkScope("main-thread", "user-turn"))
        val local = HansLocalUiState(notificationReportMessages = listOf(notice), notificationReportThreadId = "main-thread",
            liveVoiceMessages = listOf(ChatMessageUiModel(voice.displayId, ChatMessageAuthor.HANS,
                voice.text, liveVoiceTranscript = voice, localArrivalOrder = 1)))
        val client = snapshot()
        val messages = HansClientUiProjector.project(client, local, HansSettings(), resolver).chat.messages
        assertEquals(setOf(notice.id, voice.displayId), messages.map { it.id }.toSet())
        val speech = CodexTimelineSpeechProjector(text = resolver)
        assertTrue(speech.accept(client, enabled = true).isEmpty())
        assertTrue(speech.accept(client, enabled = true).isEmpty())
    }

    @Test fun nativeUserFinalAndSourceBoundNoticeAreIndependentEvenWithIdenticalText() {
        val native = ClientTimelineItem("native-final", ClientTimelineRole.HANS, notice.text, 1, 1,
            true, ClientTimelineStatus.COMPLETE, AgentMessagePhase.FINAL_ANSWER, "user-turn")
        val local = HansLocalUiState(notificationReportMessages = listOf(notice), notificationReportThreadId = "main-thread")
        val messages = HansClientUiProjector.project(snapshot(native), local, HansSettings(), resolver).chat.messages
        assertEquals(setOf(native.id, notice.id), messages.map { it.id }.toSet())
        assertEquals(2, messages.count { it.text == notice.text })
        val speech = CodexTimelineSpeechProjector(text = resolver)
        speech.accept(snapshot(), enabled = true)
        assertEquals(listOf(native.id), speech.accept(snapshot(native), enabled = true).map { it.messageId.value })
    }

    @Test fun removalSnapshotAndThreadFenceCannotRetainOrLeakTheOldNotice() {
        val local = HansLocalUiState(notificationReportMessages = listOf(notice), notificationReportThreadId = "main-thread")
        assertEquals(listOf(notice.id), HansClientUiProjector.project(snapshot(), local, HansSettings(), resolver).chat.messages.map { it.id })
        assertTrue(HansClientUiProjector.project(snapshot(), local.copy(notificationReportMessages = emptyList()),
            HansSettings(), resolver).chat.messages.isEmpty())
        assertTrue(HansClientUiProjector.project(snapshot(threadId = "other-thread"), local,
            HansSettings(), resolver).chat.messages.isEmpty())
    }

    @Test fun newImportantNoticeFollowsVisibleToolActivityAndPrecedesLaterAnswers() {
        val user = ClientTimelineItem("user", ClientTimelineRole.USER, "Check this for me", 1, 1,
            true, ClientTimelineStatus.SENT)
        val tool = ClientTimelineItem("tool", ClientTimelineRole.TOOL, "Checking calendar", 2, 1,
            false, ClientTimelineStatus.IN_PROGRESS)
        val report = createNotificationReport(notice.id, notice.text, listOf(user, tool))
        val local = HansLocalUiState(notificationReportMessages = listOf(notice.copy(
            localTimelineAnchorId = report.timelineAnchorId)), notificationReportThreadId = "main-thread")

        val first = HansClientUiProjector.project(snapshot(user, tool), local, HansSettings(), resolver).chat.messages
        assertEquals(listOf("user", "tool", notice.id), first.map { it.id })
        assertEquals(notice.text, first.last().text)

        val answer = ClientTimelineItem("answer", ClientTimelineRole.HANS, "Done", 3, 1,
            true, ClientTimelineStatus.COMPLETE, AgentMessagePhase.FINAL_ANSWER, "user-turn")
        val later = HansClientUiProjector.project(snapshot(user, tool, answer), local,
            HansSettings(), resolver).chat.messages
        assertEquals(listOf("user", "tool", notice.id, "answer"), later.map { it.id })
        assertEquals(1, later.count { it.id == notice.id })
    }

    @Test fun blankRunningToolIsAVisibleAnchorButEmptyMetadataDoesNotBuryTheNotice() {
        val user = ClientTimelineItem("user", ClientTimelineRole.USER, "Help", 1, 1,
            true, ClientTimelineStatus.SENT)
        val running = ClientTimelineItem("running-tool", ClientTimelineRole.TOOL, "", 2, 1,
            false, ClientTimelineStatus.IN_PROGRESS)
        val hidden = ClientTimelineItem("empty-metadata", ClientTimelineRole.SYSTEM, "", 3, 1,
            true, ClientTimelineStatus.COMPLETE)
        val report = createNotificationReport(notice.id, notice.text, listOf(hidden, running, user))
        assertEquals("running-tool", report.timelineAnchorId)
        val local = HansLocalUiState(notificationReportMessages = listOf(notice.copy(
            localTimelineAnchorId = report.timelineAnchorId)), notificationReportThreadId = "main-thread")
        val messages = HansClientUiProjector.project(snapshot(user, running, hidden), local,
            HansSettings(), resolver).chat.messages
        assertEquals(listOf("user", "running-tool", notice.id), messages.map { it.id })
        assertEquals(notice.text, messages.last().text)
    }

    @Test fun hiddenSetupToolsCannotBecomeTheAnchorForANewImportantNotice() {
        val answer = ClientTimelineItem("visible-answer", ClientTimelineRole.HANS, "Ready", 1, 1,
            true, ClientTimelineStatus.COMPLETE, AgentMessagePhase.FINAL_ANSWER, "ordinary-turn")
        val hidden = ClientTimelineItem("setup-tool", ClientTimelineRole.TOOL, "Internal setup receipt", 2, 1,
            true, ClientTimelineStatus.COMPLETE, turnId = "setup-turn")
        val client = snapshot(answer, hidden).copy(setupTurnIds = setOf("setup-turn"))
        val report = createNotificationReport(notice.id, notice.text, client.timeline, client.setupTurnIds)
        assertEquals(answer.id, report.timelineAnchorId)
        val local = HansLocalUiState(notificationReportMessages = listOf(notice.copy(
            localTimelineAnchorId = report.timelineAnchorId)), notificationReportThreadId = "main-thread")
        val messages = HansClientUiProjector.project(client, local, HansSettings(), resolver).chat.messages
        assertEquals(listOf(answer.id, notice.id), messages.map { it.id })
        assertEquals(notice.text, messages.last().text)
    }

    private fun snapshot(vararg items: ClientTimelineItem, threadId: String = "main-thread") = CodexClientSnapshot(
        ClientRuntimePhase.READY, ClientSessionPhase.READY, 1,
        SessionUiSnapshot(AccountUiSnapshot(AccountPhase.SIGNED_IN, null, null, null, null, null),
            threadId, emptyList(), DeliveryUiSnapshot(1, 1, null, false)),
        emptyList(), null, emptyList(), items.toList(), null,
        DispatchSelection(DispatchOptions.DEFAULT.model, DispatchOptions.DEFAULT.effort), null)
}
