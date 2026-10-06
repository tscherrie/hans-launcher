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

    private fun snapshot(vararg items: ClientTimelineItem, threadId: String = "main-thread") = CodexClientSnapshot(
        ClientRuntimePhase.READY, ClientSessionPhase.READY, 1,
        SessionUiSnapshot(AccountUiSnapshot(AccountPhase.SIGNED_IN, null, null, null, null, null),
            threadId, emptyList(), DeliveryUiSnapshot(1, 1, null, false)),
        emptyList(), null, emptyList(), items.toList(), null,
        DispatchSelection(DispatchOptions.DEFAULT.model, DispatchOptions.DEFAULT.effort), null)
}
