package ai.hans.standard.ui

import ai.hans.standard.localization.TestResourceTextResolver
import java.util.Locale

import ai.hans.standard.codex.AccountPhase
import ai.hans.standard.codex.AccountUiSnapshot
import ai.hans.standard.codex.DeliveryUiSnapshot
import ai.hans.standard.codex.DispatchOptions
import ai.hans.standard.codex.SessionUiSnapshot
import ai.hans.standard.integration.ClientRuntimePhase
import ai.hans.standard.integration.ClientSessionPhase
import ai.hans.standard.integration.ClientTimelineItem
import ai.hans.standard.integration.ClientTimelineRole
import ai.hans.standard.integration.ClientTimelineStatus
import ai.hans.standard.integration.CodexClientSnapshot
import ai.hans.standard.integration.DispatchSelection
import ai.hans.standard.integration.OutboundMessageStatus
import ai.hans.standard.integration.OutboundUserMessageUi
import ai.hans.standard.settings.HansSettings
import ai.hans.standard.voice.PendingDictation
import ai.hans.standard.voice.PendingDictationDelivery
import ai.hans.standard.voice.stt.SttTranscriptionDelay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationPreviewProjectionTest {
    private val localizationText by lazy { TestResourceTextResolver(Locale.GERMAN) }

    @Test
    fun finalizedDictationWhileBusyKeepsOneOrdinaryUserMessageWithoutManualDraftActions() {
        val pending = PendingDictation(
            id = "final-dictation",
            transcript = "Bitte auch Mittwoch prüfen",
            delivery = PendingDictationDelivery.AWAITING_RECEIPT,
            clientUserMessageId = "dictation-client",
        )
        val original = readyClient()
        val client = original.copy(
            sessionPhase = ClientSessionPhase.BUSY,
            outboundTimeline = listOf(OutboundUserMessageUi(
                "dictation-client", "preview-test-thread", pending.transcript,
                OutboundMessageStatus.PENDING, retryable = false,
            )),
            timeline = original.timeline + ClientTimelineItem(
                id = "dictation-client", role = ClientTimelineRole.USER,
                text = pending.transcript, order = 1, revision = 0,
                complete = false, status = ClientTimelineStatus.PENDING,
            ),
        )
        val local = HansLocalUiState(pendingDictations = listOf(pending))
        val state = HansClientUiProjector.project(client, local, HansSettings(), text = localizationText)
        assertTrue(state.chat.isWorking)
        assertTrue(state.chat.pendingDictations.isEmpty())
        assertEquals(1, state.chat.messages.count { it.text == pending.transcript })
        // Failed/unknown delivery becomes visible recovery, never a silent resend.
        val recovery = HansClientUiProjector.project(
            client.copy(outboundTimeline = client.outboundTimeline.map {
                it.copy(status = OutboundMessageStatus.FAILED)
            }),
            local.copy(pendingDictations = listOf(pending.copy(
                delivery = PendingDictationDelivery.OUTCOME_UNKNOWN,
            ))),
            HansSettings(), text = localizationText)
        assertEquals(1, recovery.chat.pendingDictations.size)
        assertFalse(recovery.chat.pendingDictations.single().canRetry)
    }

    @Test
    fun activePreviewUsesAnOrdinaryUserCardWithoutBecomingASentMessage() {
        val literal = "**Kein Markdown** 😃 <name>"
        for (status in listOf(DictationUiStatus.LISTENING, DictationUiStatus.FINALIZING)) {
            val state = ChatUiState(dictationStatus = status, dictationPreview = literal)
            val preview = checkNotNull(state.dictationPreviewMessage())
            assertEquals(ChatMessageAuthor.USER, preview.author)
            assertEquals(literal, preview.text)
            assertFalse(preview.complete)
            assertTrue(state.messages.isEmpty())
            assertEquals("", state.composer.text)
            assertEquals(preview.id, state.copy(dictationPreview = "Neu").dictationPreviewMessage()?.id)
        }
    }

    @Test
    fun emptyOrStalePreviewNeverCreatesAnExtraMessageCard() {
        for (status in listOf(null) + DictationUiStatus.entries) {
            assertNull(ChatUiState(dictationStatus = status, dictationPreview = " \n ").dictationPreviewMessage())
            if (status != DictationUiStatus.LISTENING && status != DictationUiStatus.FINALIZING) {
                assertNull(ChatUiState(dictationStatus = status, dictationPreview = "Veraltet").dictationPreviewMessage())
            }
        }
        val finalMessage = ChatMessageUiModel("final", ChatMessageAuthor.USER, "Fertiger Text")
        val completed = ChatUiState(messages = listOf(finalMessage), dictationPreview = "Veraltet")
        assertNull(completed.dictationPreviewMessage())
        assertEquals(listOf(finalMessage), completed.messages)
    }

    @Test
    fun previewWithoutACodexSessionIsLocalOnlyAndDoesNotReplaceTheTypedDraft() {
        val local = HansLocalUiState(
            text = "Mein vorhandener Entwurf",
            dictationStatus = DictationUiStatus.LISTENING,
            dictationPreview = "Ein vorläufiger Sprachtext",
        )
        val state = HansClientUiProjector.project(null, local, HansSettings(), text = localizationText)

        assertEquals(local.dictationPreview, state.chat.dictationPreview)
        assertEquals(local.text, state.chat.composer.text)
        assertFalse(state.chat.composer.canSend)
        assertTrue(state.chat.messages.isEmpty())
        assertTrue(state.chat.pendingDictations.isEmpty())
    }

    @Test
    fun partialsNeverBecomeMessagesOrOutboundCodexInputInAnAuthenticatedSession() {
        val client = readyClient()
        val local = HansLocalUiState(
            text = "Unabhängiger Tippentwurf",
            dictationStatus = DictationUiStatus.LISTENING,
            dictationPreview = "Noch nicht bestätigt",
        )
        val initial = HansClientUiProjector.project(client, local, HansSettings(), text = localizationText)
        val changed = HansClientUiProjector.project(
            client,
            local.copy(dictationPreview = "Noch nicht bestätigter Folgetext", revision = 1),
            HansSettings(), text = localizationText)

        assertEquals("Noch nicht bestätigter Folgetext", changed.chat.dictationPreview)
        assertEquals(initial.chat.messages, changed.chat.messages)
        assertEquals(listOf("Eine bereits gesendete Frage"), changed.chat.messages.map { it.text })
        assertEquals(local.text, changed.chat.composer.text)
        assertFalse(changed.chat.composer.canSend)
        assertTrue(changed.chat.pendingDictations.isEmpty())
        assertTrue(client.outboundTimeline.isEmpty())
        assertEquals(1, client.timeline.size)
    }

    @Test
    fun clearingProvisionalTextDoesNotRemoveConfirmedConversationHistoryOrTheDraft() {
        val client = readyClient()
        val local = HansLocalUiState(
            text = "Mein Entwurf",
            dictationStatus = DictationUiStatus.FINALIZING,
            dictationPreview = "Nur vorläufig",
        )
        val initial = HansClientUiProjector.project(client, local, HansSettings(), text = localizationText)
        val completed = HansClientUiProjector.project(
            client,
            local.copy(dictationStatus = DictationUiStatus.WAITING_TO_SEND, dictationPreview = ""),
            HansSettings(), text = localizationText)

        assertEquals("", completed.chat.dictationPreview)
        assertEquals(initial.chat.messages, completed.chat.messages)
        assertEquals(local.text, completed.chat.composer.text)
        assertTrue(completed.chat.pendingDictations.isEmpty())
    }

    @Test
    fun preferredDelayAloneNeverProjectsAServerConfirmedActiveDelay() {
        SttTranscriptionDelay.entries.forEach { preferred ->
            val state = HansClientUiProjector.project(
                readyClient(),
                HansLocalUiState(
                    dictationStatus = DictationUiStatus.LISTENING,
                    sttLatency = SttLatencyUiState(preferred = preferred),
                ),
                HansSettings(), text = localizationText)

            assertEquals(preferred, state.settings.sttLatency.preferred)
            assertNull(state.settings.sttLatency.confirmedActive)
        }
    }

    @Test
    fun nextRecordingPreferenceAndCurrentServerAcknowledgementRemainIndependent() {
        val delay = SttLatencyUiState(
            preferred = SttTranscriptionDelay.MINIMAL,
            confirmedActive = SttTranscriptionDelay.LOW,
            notice = "Gespeichert für die nächste Aufnahme.",
        )
        listOf(null, readyClient()).forEach { client ->
            val state = HansClientUiProjector.project(
                client,
                HansLocalUiState(dictationStatus = DictationUiStatus.LISTENING, sttLatency = delay),
                HansSettings(), text = localizationText)

            assertEquals(delay, state.settings.sttLatency)
        }
    }

    private fun readyClient() = CodexClientSnapshot(
        runtimePhase = ClientRuntimePhase.READY,
        sessionPhase = ClientSessionPhase.READY,
        generation = 1,
        session = SessionUiSnapshot(
            account = AccountUiSnapshot(AccountPhase.SIGNED_IN, null, null, null, null, null),
            currentThreadId = "preview-test-thread",
            threads = emptyList(),
            delivery = DeliveryUiSnapshot(1, 1, null, false),
        ),
        models = emptyList(),
        deviceCodeLogin = null,
        outboundTimeline = emptyList(),
        timeline = listOf(
            ClientTimelineItem(
                id = "confirmed-message",
                role = ClientTimelineRole.USER,
                text = "Eine bereits gesendete Frage",
                order = 0,
                revision = 0,
                complete = true,
                status = ClientTimelineStatus.SENT,
            ),
        ),
        pendingSelection = null,
        confirmedSelection = DispatchSelection(DispatchOptions.DEFAULT.model, DispatchOptions.DEFAULT.effort),
        problem = null,
    )
}
