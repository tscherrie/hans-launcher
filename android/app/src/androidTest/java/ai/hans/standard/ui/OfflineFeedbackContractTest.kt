package ai.hans.standard.ui

import ai.hans.standard.voice.PendingDictation
import ai.hans.standard.voice.PendingDictationDelivery
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Pure UI contracts: callbacks are recorded, never connected to a runtime, account or network. */
class OfflineFeedbackContractTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun offlineNoticeSurvivesReadyRuntimeAndRecompositionWithoutConsumingDraftOrPhoto() {
        val state = mutableStateOf(offlineChat())
        val actions = mutableListOf<String>()
        showChat(state, actions)

        compose.onNodeWithTag("chat_connection_notice").assertIsDisplayed()
        compose.onNodeWithText(OFFLINE_NOTICE).assertIsDisplayed()
        compose.onNodeWithTag("runtime_sleeping_indicator", useUnmergedTree = true)
            .assertDoesNotExist()
        assertComposerPreserved()

        compose.runOnIdle {
            state.value = state.value.copy(isWorking = true, timelineRevision = 1)
        }
        compose.onNodeWithText(OFFLINE_NOTICE).assertIsDisplayed()
        assertComposerPreserved()

        compose.onNodeWithText("Interneteinstellungen").performClick()
        compose.runOnIdle { assertEquals(listOf("internet-settings"), actions) }
        assertComposerPreserved()

        compose.runOnIdle {
            state.value = state.value.copy(internetNotice = "", isWorking = false)
        }
        compose.onNodeWithTag("chat_connection_notice").assertDoesNotExist()
        compose.onNodeWithText("Interneteinstellungen").assertDoesNotExist()
        assertComposerPreserved()
        compose.runOnIdle { assertEquals(listOf("internet-settings"), actions) }
    }

    @Test
    fun failedSendRemainsVisibleAfterInternetRecoversWithoutDiscardingComposer() {
        val failure = "Nachricht nicht gesendet. Bitte erneut versuchen."
        val state = mutableStateOf(offlineChat().copy(connectionFailureMessage = failure))
        val actions = mutableListOf<String>()
        showChat(state, actions)

        compose.onNodeWithText("$OFFLINE_NOTICE\n$failure").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(internetNotice = "") }

        compose.onNodeWithTag("chat_connection_notice").assertIsDisplayed()
        compose.onNodeWithText(failure).assertIsDisplayed()
        compose.onNodeWithText("Interneteinstellungen").assertDoesNotExist()
        assertComposerPreserved()
        compose.runOnIdle { assertEquals(emptyList<String>(), actions) }
    }

    @Test
    fun savedDictationRequiresExplicitRetryOrDiscardEvenAfterConnectivityReturns() {
        val pending = PendingDictation(DRAFT_ID, TRANSCRIPT)
        val state = mutableStateOf(pendingChat(pending))
        val actions = mutableListOf<String>()
        showChat(state, actions)

        showTimelineText("Sprachtext gespeichert, noch nicht gesendet")
        showTimelineText(TRANSCRIPT)
        compose.runOnIdle {
            assertEquals(emptyList<String>(), actions)
            state.value = state.value.copy(internetNotice = "", timelineRevision = 1)
        }
        showTimelineText("Sprachtext gespeichert, noch nicht gesendet")
        compose.runOnIdle { assertEquals(emptyList<String>(), actions) }

        compose.onNodeWithText("Senden").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("retry:$DRAFT_ID"), actions) }
        // A click is not a delivery receipt: only a runtime state update may remove this draft.
        showTimelineText(TRANSCRIPT)
        compose.onNodeWithText("Entwurf entfernen").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("retry:$DRAFT_ID", "discard:$DRAFT_ID"), actions)
        }
    }

    @Test
    fun interruptedDictationClearlyMarksPartialTextAndRequiresExplicitPartialSend() {
        val state = mutableStateOf(
            pendingChat(PendingDictation(DRAFT_ID, TRANSCRIPT, incomplete = true)),
        )
        val actions = mutableListOf<String>()
        showChat(state, actions)

        showTimelineText("Diktat unterbrochen – unvollständiger Text, noch nicht gesendet")
        showTimelineText(TRANSCRIPT)
        compose.onNodeWithText("Senden").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), actions) }

        compose.runOnIdle {
            state.value = state.value.copy(internetNotice = "", timelineRevision = 1)
        }
        showTimelineText("Diktat unterbrochen – unvollständiger Text, noch nicht gesendet")
        compose.runOnIdle { assertEquals(emptyList<String>(), actions) }

        compose.onNodeWithText("Diesen Teil senden").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("retry:$DRAFT_ID"), actions) }
        showTimelineText(TRANSCRIPT)
        compose.onNodeWithText("Entwurf entfernen").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun awaitingReceiptAndUnknownDeliveryNeverExposeARepeatSendAction() {
        val pending = PendingDictation(
            DRAFT_ID,
            TRANSCRIPT,
            delivery = PendingDictationDelivery.AWAITING_RECEIPT,
            clientUserMessageId = "dictation-$DRAFT_ID",
        )
        val state = mutableStateOf(pendingChat(pending).copy(internetNotice = ""))
        val actions = mutableListOf<String>()
        showChat(state, actions)

        showTimelineText(TRANSCRIPT)
        compose.onNodeWithText("Sprachtext wartet auf Sendebestätigung").assertDoesNotExist()
        compose.onNodeWithText("Senden").assertDoesNotExist()
        compose.onNodeWithText("Diesen Teil senden").assertDoesNotExist()
        compose.onNodeWithText("Entwurf entfernen").assertDoesNotExist()

        compose.runOnIdle {
            state.value = state.value.copy(
                pendingDictations = listOf(
                    pending.copy(delivery = PendingDictationDelivery.OUTCOME_UNKNOWN),
                ),
                timelineRevision = 1,
            )
        }
        showTimelineText("Sendestatus unbekannt. Prüfe den Chat, bevor du den Auftrag wiederholst.")
        showTimelineText(TRANSCRIPT)
        compose.onNodeWithText("Senden").assertDoesNotExist()
        compose.onNodeWithText("Diesen Teil senden").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), actions) }

        compose.onNodeWithText("Entwurf entfernen").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("discard:$DRAFT_ID"), actions) }
    }

    @Test
    fun authNoticeIsVisibleInEveryLoginStageWithoutStartingLoginOrOpeningBrowser() {
        val state = mutableStateOf(AuthGateUiState(internetNotice = OFFLINE_NOTICE))
        val actions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                AuthGateScreen(
                    state = state.value,
                    callbacks = AuthGateUiCallbacks(
                        onStartChatGptLogin = { actions += "login" },
                        onOpenVerificationPage = { actions += "browser" },
                        onCopyUserCode = { actions += "copy" },
                        onCancel = { actions += "cancel" },
                        onRetry = { actions += "retry" },
                    ),
                )
            }
        }

        AuthGateStage.entries.forEach { stage ->
            compose.runOnIdle { state.value = state.value.copy(stage = stage) }
            compose.onNodeWithTag("auth_internet_notice")
                .performScrollTo()
                .assertIsDisplayed()
                .assertTextEquals(OFFLINE_NOTICE)
        }
        compose.runOnIdle {
            assertEquals(emptyList<String>(), actions)
            state.value = state.value.copy(internetNotice = "")
        }
        compose.onNodeWithTag("auth_internet_notice").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), actions) }
    }

    private fun showChat(state: State<ChatUiState>, actions: MutableList<String>) {
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = state.value,
                    callbacks = ChatUiCallbacks(
                        onComposerChanged = { actions += "draft-changed" },
                        onSend = { actions += "send" },
                        onChooseMedia = {},
                        onRemoveAttachment = { actions += "remove:$it" },
                        onOpenApps = {},
                        onOpenPlugins = {},
                        onOpenSettings = {},
                        onToggleLiveVoice = {},
                        onOpenInternetSettings = { actions += "internet-settings" },
                        onRetryPendingDictation = { actions += "retry:$it" },
                        onDiscardPendingDictation = { actions += "discard:$it" },
                    ),
                )
            }
        }
    }

    private fun assertComposerPreserved() {
        compose.onNodeWithTag("composer").assertIsDisplayed().assertTextEquals(COMPOSER_DRAFT)
        compose.onNodeWithText(PHOTO_LABEL).assertIsDisplayed()
        compose.onNodeWithText("Entfernen").assertIsDisplayed()
    }

    private fun showTimelineText(text: String) {
        compose.onNodeWithTag("chat_timeline").performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun offlineChat() = ChatUiState(
        runtimeStatus = RuntimeUiStatus.ONLINE,
        internetNotice = OFFLINE_NOTICE,
        composer = ComposerUiState(
            text = COMPOSER_DRAFT,
            attachments = listOf(ComposerAttachmentUiModel("test-photo", PHOTO_LABEL)),
        ),
    )

    private fun pendingChat(pending: PendingDictation) = ChatUiState(
        runtimeStatus = RuntimeUiStatus.ONLINE,
        internetNotice = OFFLINE_NOTICE,
        pendingDictations = listOf(pending),
    )

    private companion object {
        const val OFFLINE_NOTICE = "Keine Internetverbindung. Entwurf bleibt erhalten."
        const val COMPOSER_DRAFT = "Bitte dieses Foto ansehen."
        const val PHOTO_LABEL = "Testfoto.jpg"
        const val DRAFT_ID = "00000000-0000-0000-0000-000000000001"
        const val TRANSCRIPT = "Bitte erinnere mich an meinen Termin."
    }
}
