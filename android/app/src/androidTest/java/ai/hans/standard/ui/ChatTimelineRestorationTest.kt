package ai.hans.standard.ui

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
import ai.hans.standard.settings.HansSettings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

/** UI boundary for a fresh process: the runtime first projects no history, then its resume result. */
class ChatTimelineRestorationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun coldResumeHydrationReplacesTheEmptyStateWithTheVisibleConversation() {
        val state = mutableStateOf(
            ChatUiState(
                runtimeStatus = RuntimeUiStatus.CONNECTING,
                composer = ComposerUiState(enabled = false),
            ),
        )
        compose.setContent {
            MaterialTheme {
                ChatScreen(state = state.value, callbacks = callbacks())
            }
        }

        compose.onNodeWithText("Was steht an?").assertIsDisplayed()

        compose.runOnIdle {
            state.value = HansClientUiProjector.project(
                client = resumedClient(
                    ClientTimelineItem(
                        id = "user-before-process-recreation",
                        role = ClientTimelineRole.USER,
                        text = "Was hatten wir vor dem Neustart besprochen?",
                        order = 1,
                        revision = 1,
                        complete = true,
                        status = ClientTimelineStatus.SENT,
                    ),
                    ClientTimelineItem(
                        id = "answer-before-process-recreation",
                        role = ClientTimelineRole.HANS,
                        text = "Die sichtbare Unterhaltung ist wieder da.",
                        order = 2,
                        revision = 1,
                        complete = true,
                        status = ClientTimelineStatus.COMPLETE,
                    ),
                ),
                local = HansLocalUiState(),
                settings = HansSettings(),
            ).chat
        }

        compose.onNodeWithText("Was steht an?").assertDoesNotExist()
        compose.onNodeWithText("Was hatten wir vor dem Neustart besprochen?")
            .assertIsDisplayed()
        compose.onNodeWithText("Die sichtbare Unterhaltung ist wieder da.")
            .assertIsDisplayed()
    }

    @Test
    fun restoredUserAndHansItemsWithTheSameProtocolIdRemainDistinct() {
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = HansClientUiProjector.project(
                        client = resumedClient(
                            ClientTimelineItem(
                                id = "shared-app-server-item",
                                role = ClientTimelineRole.USER,
                                text = "Meine wiederhergestellte Frage",
                                order = 1,
                                revision = 1,
                                complete = true,
                                status = ClientTimelineStatus.SENT,
                            ),
                            ClientTimelineItem(
                                id = "shared-app-server-item",
                                role = ClientTimelineRole.HANS,
                                text = "Meine wiederhergestellte Antwort",
                                order = 2,
                                revision = 1,
                                complete = true,
                                status = ClientTimelineStatus.COMPLETE,
                            ),
                        ),
                        local = HansLocalUiState(),
                        settings = HansSettings(),
                    ).chat,
                    callbacks = callbacks(),
                )
            }
        }

        // Composition itself is the regression assertion: duplicate LazyColumn keys throw.
        compose.onNodeWithText("Meine wiederhergestellte Frage").assertIsDisplayed()
        compose.onNodeWithText("Meine wiederhergestellte Antwort").assertIsDisplayed()
    }

    private fun callbacks() = ChatUiCallbacks(
        onComposerChanged = {},
        onSend = {},
        onChooseMedia = {},
        onRemoveAttachment = {},
        onOpenApps = {},
        onOpenPlugins = {},
        onOpenSettings = {},
        onToggleLiveVoice = {},
    )

    private fun resumedClient(vararg timeline: ClientTimelineItem) = CodexClientSnapshot(
        runtimePhase = ClientRuntimePhase.READY,
        sessionPhase = ClientSessionPhase.READY,
        generation = 2,
        session = SessionUiSnapshot(
            account = AccountUiSnapshot(AccountPhase.SIGNED_IN, null, null, null, null, null),
            currentThreadId = "thread-from-previous-process",
            threads = emptyList(),
            delivery = DeliveryUiSnapshot(1, 1, null, false),
        ),
        models = emptyList(),
        deviceCodeLogin = null,
        outboundTimeline = emptyList(),
        timeline = timeline.toList(),
        pendingSelection = null,
        confirmedSelection = DispatchSelection(
            DispatchOptions.DEFAULT.model,
            DispatchOptions.DEFAULT.effort,
        ),
        problem = null,
    )
}
