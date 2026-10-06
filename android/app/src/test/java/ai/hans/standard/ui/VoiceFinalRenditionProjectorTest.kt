package ai.hans.standard.ui

import ai.hans.standard.codex.AccountPhase
import ai.hans.standard.codex.AccountUiSnapshot
import ai.hans.standard.codex.AgentMessagePhase
import ai.hans.standard.codex.DeliveryUiSnapshot
import ai.hans.standard.codex.DispatchOptions
import ai.hans.standard.codex.SessionUiSnapshot
import ai.hans.standard.integration.*
import ai.hans.standard.R
import ai.hans.standard.localization.HansTextResolver
import ai.hans.standard.localization.TestResourceTextResolver
import ai.hans.standard.settings.HansSettings
import ai.hans.standard.voice.realtime.CodexVoiceWorkScope
import ai.hans.standard.voice.realtime.LiveVoiceTranscriptAuthor
import ai.hans.standard.voice.realtime.LiveVoiceTranscriptRevision
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class VoiceFinalRenditionProjectorTest {
    private val source = CodexVoiceWorkScope("thread-1", "voice-turn")
    private val finalText = "Guten Morgen, Jeremias! Ich bin bereit für den Tag. Und du, gut geschlafen?"
    private val spokenText = "Guten Morgen, Jeremias! Bin bereit für den Tag. Und du, gut geschlafen?"

    @Test fun shortTaskStatusAndNativeFinalHaveOnePresentationOwnerInEitherUiArrivalOrder() {
        for (voiceFirst in listOf(true, false)) {
            val voice = spoken("native-1", spokenText, source)
            val native = nativeFinal()
            if (voiceFirst) assertEquals(listOf(voice.id), project(emptyList(), listOf(voice)).map { it.id })
            else assertEquals(listOf(native.id), project(listOf(native), emptyList()).map { it.id })
            val messages = project(listOf(native), listOf(voice))
            assertEquals(listOf("native-final"), messages.map { it.id })
            assertEquals(listOf(finalText), messages.map { it.text })
        }
    }

    @Test fun interimStatusVoiceOnlyReplyAndIdenticalIndependentRepliesAreRetained() {
        val interim = spoken("native-interim", "Einen Moment, ich schau kurz nach.")
        val voiceOnly = spoken("native-alone", "Das ist eine rein gesprochene Antwort.")
        val repeated = spoken("native-repeat", spokenText)
        val otherTurn = spoken("native-other", spokenText, source.copy(turnId = "other-turn"))
        val rendition = spoken("native-final-rendition", spokenText, source)
        val messages = project(listOf(nativeFinal()), listOf(interim, voiceOnly, repeated, otherTurn, rendition))
        assertEquals(setOf("native-final", interim.id, voiceOnly.id, repeated.id, otherTurn.id), messages.map { it.id }.toSet())
        assertEquals(2, messages.count { it.text == spokenText })
        assertTrue(messages.any { it.text == interim.text })
    }

    @Test fun unrelatedThreadTurnPhaseOrIncompleteOutputCannotReplaceShortTaskStatus() {
        val voice = spoken("native-1", spokenText, source)
        val native = nativeFinal()
        for (unmatched in listOf(native.copy(turnId = "other-turn"),
            native.copy(agentPhase = AgentMessagePhase.COMMENTARY), native.copy(complete = false), native.copy(text = ""))) {
            assertTrue(project(listOf(unmatched), listOf(voice)).any { it.id == voice.id })
        }
        val foreignVoice = spoken("native-foreign", spokenText, source.copy(threadId = "other-thread"))
        assertTrue(project(listOf(native), listOf(foreignVoice)).any { it.id == foreignVoice.id })
        val partial = voice.copy(complete = false, liveVoiceTranscript = voice.liveVoiceTranscript?.copy(isFinal = false))
        assertFalse(project(listOf(native), listOf(partial)).any { it.id == partial.id })
    }

    @Test fun invisibleNativeFinalOrSameIdWithAnotherAuthorCannotHideTheOnlySpokenAnswer() {
        val voice = spoken("native-1", spokenText, source)
        val resolver = object : HansTextResolver by TestResourceTextResolver(Locale.GERMAN) {
            override fun text(resourceId: Int, vararg formatArgs: Any): String =
                if (resourceId in setOf(R.string.integration_setup_checked, R.string.integration_setup_continue)) ""
                else TestResourceTextResolver(Locale.GERMAN).text(resourceId, *formatArgs)
        }
        val renderedBlank = nativeFinal().copy(text = "{\"currentStep\":\"intro\"}")
        assertEquals(listOf(voice.id), project(listOf(renderedBlank), listOf(voice), setOf("voice-turn"), resolver).map { it.id })
        assertTrue(project(listOf(nativeFinal().copy(role = ClientTimelineRole.USER)), listOf(voice)).any { it.id == voice.id })
    }

    private fun nativeFinal() = ClientTimelineItem("native-final", ClientTimelineRole.HANS, finalText,
        1, 0, true, ClientTimelineStatus.COMPLETE, AgentMessagePhase.FINAL_ANSWER, "voice-turn")

    private fun spoken(id: String, text: String, target: CodexVoiceWorkScope? = null): ChatMessageUiModel {
        val event = LiveVoiceTranscriptRevision("voice-session", 1, id, LiveVoiceTranscriptAuthor.HANS, text, true, target)
        return ChatMessageUiModel(event.displayId, ChatMessageAuthor.HANS, text,
            localArrivalOrder = 1, liveVoiceTranscript = event)
    }

    private fun project(native: List<ClientTimelineItem>, voice: List<ChatMessageUiModel>,
        setupTurnIds: Set<String> = emptySet(),
        resolver: HansTextResolver = TestResourceTextResolver(Locale.GERMAN)): List<ChatMessageUiModel> {
        val client = CodexClientSnapshot(ClientRuntimePhase.READY, ClientSessionPhase.READY, 1,
            SessionUiSnapshot(AccountUiSnapshot(AccountPhase.SIGNED_IN, null, null, null, null, null),
                "thread-1", emptyList(), DeliveryUiSnapshot(1, 1, null, false)), emptyList(), null,
            emptyList(), native, null, DispatchSelection(DispatchOptions.DEFAULT.model, DispatchOptions.DEFAULT.effort), null,
            setupTurnIds = setupTurnIds)
        return HansClientUiProjector.project(client, HansLocalUiState(liveVoiceMessages = voice),
            HansSettings(), resolver).chat.messages
    }
}
