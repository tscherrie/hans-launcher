package ai.hans.standard.ui

import ai.hans.standard.voice.audio.SpeechAudioRoute
import ai.hans.standard.voice.audio.SpeechAudioRouteState
import ai.hans.standard.voice.realtime.LiveVoiceVoiceResolution
import ai.hans.standard.voice.realtime.LiveVoiceVoiceSelection
import android.view.KeyEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HansTodoUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tappingHansRequiresAnExplicitPhoneCallConfirmation() {
        var starts = 0
        compose.setGermanContent {
            MaterialTheme { ChatScreen(ChatUiState(), chatCallbacks().copy(onStartLiveVoice = { starts++ })) }
        }
        compose.onNodeWithTag("hans_title").performClick()
        compose.onNodeWithTag("live_start_confirmation").assertExists()
        compose.onNodeWithTag("live_start_phone_icon")
            .assertContentDescriptionEquals("Telefon")
        compose.runOnIdle { assertEquals(0, starts) }
        compose.onNodeWithTag("live_start_confirm").performClick()
        compose.onNodeWithTag("live_start_confirmation").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test fun liveConfirmationCanBeCancelledWithoutStartingAndExpiresAfterExactlyFiveSeconds() {
        var starts = 0
        compose.setGermanContent {
            MaterialTheme { ChatScreen(ChatUiState(), chatCallbacks().copy(onStartLiveVoice = { starts++ })) }
        }
        compose.onNodeWithTag("hans_title").performClick()
        compose.onNodeWithTag("live_start_cancel").performClick()
        compose.onNodeWithTag("live_start_confirmation").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, starts) }

        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("hans_title").performClick()
            // The click only changes state. Start the dialog's LaunchedEffect in its
            // opening recomposition before measuring its five-second lifetime.
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.onNodeWithTag("live_start_confirmation").assertExists()
            val confirmationOpenedAt = compose.mainClock.currentTime
            assertEquals(5_000L, LIVE_START_CONFIRMATION_TIMEOUT_MILLIS)

            compose.mainClock.advanceTimeBy(4_999L, ignoreFrameDuration = true)
            assertEquals(confirmationOpenedAt + 4_999L, compose.mainClock.currentTime)
            compose.onNodeWithTag("live_start_confirmation").assertExists()
            compose.runOnIdle { assertEquals(0, starts) }

            compose.mainClock.advanceTimeBy(1L, ignoreFrameDuration = true)
            assertEquals(confirmationOpenedAt + 5_000L, compose.mainClock.currentTime)
            // The timeout changes state at the deadline; one rendering frame must
            // publish that change. waitForIdle cannot recompose a paused test clock.
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.onNodeWithTag("live_start_confirmation").assertDoesNotExist()
            compose.runOnIdle { assertEquals(0, starts) }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun longPressingOneHansResponseOffersOnlyReadAloudAndReplaysThatExactMessage() {
        val requested = mutableListOf<String>()
        val exact = "**Servus** – hier ist dein Ergebnis."
        val state = mutableStateOf(
            ChatUiState(
                messages = listOf(
                    ChatMessageUiModel("answer", ChatMessageAuthor.HANS, exact),
                    ChatMessageUiModel("user", ChatMessageAuthor.USER, "Danke"),
                ),
            ),
        )
        compose.setGermanContent {
            MaterialTheme {
                ChatScreen(
                    state.value,
                    chatCallbacks().copy(onReadAssistantMessageAloud = requested::add),
                )
            }
        }
        compose.onNodeWithTag("message_answer").performTouchInput { longClick() }
        compose.onNodeWithTag("message_read_aloud_menu").assertExists()
        compose.onNodeWithTag("message_read_aloud_action").assertTextEquals("Vorlesen")
        compose.onNodeWithText("Kopieren").assertDoesNotExist()
        compose.runOnIdle {
            state.value = state.value.copy(
                messages = state.value.messages.map {
                    if (it.id == "answer") it.copy(text = "Später eingetroffene Ergänzung", revision = 2)
                    else it
                },
            )
        }
        compose.onNodeWithTag("message_read_aloud_action")
            .assertContentDescriptionEquals("Diese Antwort vorlesen")
            .performClick()
        compose.onNodeWithTag("message_read_aloud_menu").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(exact), requested) }

        compose.onNodeWithTag("message_user").performTouchInput { longClick() }
        compose.onNodeWithTag("message_read_aloud_menu").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(exact), requested) }
    }

    @Test fun activeLiveStopsImmediatelyWithoutAConfirmationOrStartCallback() {
        var starts = 0
        var stops = 0
        compose.setGermanContent {
            MaterialTheme {
                ChatScreen(
                    ChatUiState(liveVoiceStatus = LiveVoiceUiStatus.LISTENING),
                    chatCallbacks().copy(onStartLiveVoice = { starts++ }, onStopLiveVoice = { stops++ }),
                )
            }
        }
        compose.onNodeWithTag("live_call_screen").assertExists()
        compose.onNodeWithTag("hans_title").assertDoesNotExist()
        compose.onNodeWithTag("live_call_hang_up").performClick()
        compose.onNodeWithTag("live_start_confirmation").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, starts); assertEquals(1, stops) }
    }

    @Test fun activeLiveIsAnEarpieceOnlyCallSurfaceWithoutChatOrRouteControls() {
        compose.setGermanContent {
            MaterialTheme {
                ChatScreen(
                    ChatUiState(
                        liveVoiceStatus = LiveVoiceUiStatus.HANS_SPEAKING,
                        liveVoiceVoiceSelection = LiveVoiceVoiceSelection(
                            requestedTtsVoice = "fable",
                            effectiveRealtimeVoice = "ballad",
                            resolution = LiveVoiceVoiceResolution.APPROXIMATE,
                        ),
                        speechAudioRoute = SpeechAudioRouteState(
                            active = true,
                            available = setOf(SpeechAudioRoute.EARPIECE, SpeechAudioRoute.SPEAKER),
                            effective = SpeechAudioRoute.EARPIECE,
                        ),
                    ),
                    chatCallbacks(),
                )
            }
        }
        compose.onNodeWithTag("live_call_screen").assertExists()
        compose.onNodeWithTag("live_call_avatar").assertExists()
        compose.onNodeWithTag("live_call_status").assertTextEquals("Hans spricht")
        compose.onNodeWithTag("live_call_voice").assertDoesNotExist()
        compose.onNodeWithTag("live_call_mute").assertExists()
        compose.onNodeWithTag("live_call_hang_up").assertExists()
        compose.onNodeWithTag("speech_audio_route").assertDoesNotExist()
        compose.onNodeWithTag("chat_timeline").assertDoesNotExist()
        compose.onNodeWithTag("composer").assertDoesNotExist()
    }

    @Test fun connectingCallUsesNaturalRingingLanguage() {
        compose.setGermanContent {
            MaterialTheme {
                ChatScreen(
                    ChatUiState(liveVoiceStatus = LiveVoiceUiStatus.CONNECTING),
                    chatCallbacks(),
                )
            }
        }
        compose.onNodeWithTag("live_call_status").assertTextEquals("Wird angerufen …")
        compose.onNodeWithTag("live_call_voice").assertDoesNotExist()
    }

    @Test fun liveMuteWaitsForConfirmedRuntimeStateAndCanBeReversed() {
        val state = mutableStateOf(
            ChatUiState(liveVoiceStatus = LiveVoiceUiStatus.LISTENING),
        )
        val requested = mutableListOf<Boolean>()
        compose.setGermanContent {
            MaterialTheme {
                ChatScreen(
                    state.value,
                    chatCallbacks().copy(onLiveVoiceInputMutedChanged = requested::add),
                )
            }
        }
        compose.onNodeWithTag("live_call_mute")
            .assertContentDescriptionEquals("Aufnahme beenden")
            .performClick()
        compose.runOnIdle { assertEquals(listOf(true), requested) }
        compose.onNodeWithTag("live_call_mute")
            .assertContentDescriptionEquals("Aufnahme beenden")
        compose.runOnIdle { state.value = state.value.copy(liveVoiceInputMuted = true) }
        compose.onNodeWithTag("live_call_mute")
            .assertContentDescriptionEquals("Mikrofon ist stumm. Mikrofon einschalten")
            .performClick()
        compose.runOnIdle { assertEquals(listOf(true, false), requested) }
    }

    @Test fun systemBackMinimizesToAVisibleCallBarWithoutStoppingTheCall() {
        var stops = 0
        compose.setGermanContent {
            MaterialTheme {
                ChatScreen(
                    ChatUiState(liveVoiceStatus = LiveVoiceUiStatus.LISTENING),
                    chatCallbacks().copy(onStopLiveVoice = { stops++ }),
                )
            }
        }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithTag("live_call_screen").assertDoesNotExist()
        compose.onNodeWithTag("live_call_bar").assertExists()
        compose.onNodeWithTag("live_call_bar_hang_up").assertExists()
        compose.runOnIdle { assertEquals(0, stops) }
    }

    @Test fun reconnectAndFailureStayVisibleEvenWhenTheMicrophoneWasMuted() {
        val state = mutableStateOf(
            ChatUiState(
                liveVoiceStatus = LiveVoiceUiStatus.RECONNECTING,
                liveVoiceInputMuted = true,
            ),
        )
        compose.setGermanContent {
            MaterialTheme { ChatScreen(state.value, chatCallbacks()) }
        }
        compose.onNodeWithTag("live_call_status")
            .assertTextEquals("Verbindung wird wiederhergestellt …")
        compose.runOnIdle {
            state.value = state.value.copy(liveVoiceStatus = LiveVoiceUiStatus.FAILED)
        }
        compose.onNodeWithTag("live_call_status").assertTextEquals("Verbindung unterbrochen")
    }

    @Test fun explicitStartCallbackDoesNotFallBackToTheLegacyToggle() {
        val state = mutableStateOf(ChatUiState())
        var starts = 0
        var toggles = 0
        compose.setGermanContent {
            MaterialTheme {
                ChatScreen(
                    state.value,
                    chatCallbacks().copy(
                        onStartLiveVoice = { starts++ },
                        onToggleLiveVoice = { toggles++ },
                    ),
                )
            }
        }
        compose.onNodeWithTag("hans_title").performClick()
        compose.onNodeWithTag("live_start_confirmation").assertExists()
        compose.runOnIdle { assertEquals(0, starts); assertEquals(0, toggles) }
        compose.onNodeWithTag("live_start_confirm").performClick()
        compose.runOnIdle { assertEquals(1, starts); assertEquals(0, toggles) }
    }

    @Test fun visibleMenuContainsOnlyGroupsAndKeepsAckSelectionsInsideTheChosenGroup() {
        val selected = mutableListOf<String>()
        compose.setGermanContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatUiState(composer = ComposerUiState(text = "Entwurf bleibt")),
                    callbacks = chatCallbacks(),
                    sidebarSettings = SettingsUiState(
                        selectedModelId = "gpt-5.6-luna",
                        selectedReasoningEffortId = "max",
                        reasoningEfforts = listOf(ReasoningEffortUiOption.MAX, ReasoningEffortUiOption.ULTRA),
                    ),
                    sidebarCallbacks = settingsCallbacks().copy(onModelSelected = selected::add),
                )
            }
        }
        compose.onNodeWithTag("open_chat_navigation").performClick()
        compose.onNodeWithTag("chat_navigation_panel").assertExists()
        compose.onNodeWithTag("open_settings").assertDoesNotExist()
        compose.onNodeWithTag("settings_content").assertDoesNotExist()
        compose.onNodeWithTag("model_gpt-5.6-sol").assertDoesNotExist()
        compose.onNodeWithTag("persistent_android_consent_notice").assertDoesNotExist()
        val ids = listOf("runtime", "speech", "input", "personal", "permissions", "maintenance")
        val positions = ids.map {
            compose.onNodeWithTag("settings_group_$it").fetchSemanticsNode().positionInRoot.y
        }
        assertTrue(positions.zipWithNext().all { (before, after) -> before < after })
        compose.onNodeWithTag("settings_group_runtime").performScrollTo().performClick()
        compose.onNodeWithTag("settings_group_title").assertTextEquals("Modell & Antworten")
        compose.onNodeWithTag("settings_group_overview").assertDoesNotExist()
        compose.onNodeWithTag("model_gpt-5.6-sol").performScrollTo().performClick()
        compose.onNodeWithTag("model_gpt-5.6-sol").assertIsNotSelected()
        compose.onNodeWithTag("model_gpt-5.6-luna").assertIsSelected()
        compose.runOnIdle { assertEquals(listOf("gpt-5.6-sol"), selected) }
        compose.onNodeWithTag("settings_back_to_groups").performClick()
        compose.onNodeWithTag("settings_group_permissions").performScrollTo().assertExists().performClick()
        compose.onNodeWithTag("persistent_android_consent_notice").assertExists()
        compose.onNodeWithTag("model_gpt-5.6-sol").assertDoesNotExist()
        compose.onNodeWithTag("chat_navigation_panel").assertExists()
        compose.onNodeWithTag("close_chat_navigation").performClick()
        compose.onNodeWithTag("chat_navigation_panel").assertDoesNotExist()
        compose.onNodeWithTag("composer").assertExists()
    }

    @Test fun externalSettingsEntryOpensTheSameSidebarWithoutAnotherSettingsScreen() {
        compose.setGermanContent {
            MaterialTheme {
                ChatScreen(ChatUiState(), chatCallbacks(), sidebarRequested = true,
                    sidebarSettings = SettingsUiState(), sidebarCallbacks = settingsCallbacks())
            }
        }
        compose.onNodeWithTag("chat_screen").assertExists()
        compose.onNodeWithTag("chat_navigation_panel").assertExists()
        compose.onNodeWithTag("settings_group_runtime").assertExists()
    }

    @Test fun audioToggleAppearsOnlyForActiveOutputAndWaitsForActualAndroidRoute() {
        val route = mutableStateOf(SpeechAudioRouteState())
        val requested = mutableListOf<SpeechAudioRoute>()
        compose.setGermanContent {
            MaterialTheme {
                ChatScreen(ChatUiState(speechAudioRoute = route.value),
                    chatCallbacks().copy(onSpeechAudioRouteRequested = requested::add))
            }
        }
        compose.onNodeWithTag("speech_audio_route").assertDoesNotExist()
        compose.runOnIdle {
            route.value = SpeechAudioRouteState(active = true,
                available = setOf(SpeechAudioRoute.EARPIECE, SpeechAudioRoute.SPEAKER),
                effective = SpeechAudioRoute.SPEAKER)
        }
        compose.onNodeWithTag("speech_audio_route")
            .assert(hasAnyAncestor(hasTestTag("speech_output_controls"))).performClick()
        compose.onNodeWithTag("speech_route_earpiece").performClick()
        compose.runOnIdle { assertEquals(listOf(SpeechAudioRoute.EARPIECE), requested) }
        compose.onNodeWithTag("speech_audio_route")
            .assertContentDescriptionEquals("Tonausgabe: Lautsprecher. Ändern")
        compose.runOnIdle { route.value = route.value.copy(effective = SpeechAudioRoute.EARPIECE) }
        compose.onNodeWithTag("speech_audio_route")
            .assertContentDescriptionEquals("Tonausgabe: Hörmuschel. Ändern")
        compose.runOnIdle { route.value = SpeechAudioRouteState() }
        compose.onNodeWithTag("speech_audio_route").assertDoesNotExist()
    }

    @Test fun missingEarpieceRemainsDisabledInsteadOfPretendingToSwitch() {
        compose.setGermanContent {
            MaterialTheme {
                ChatScreen(ChatUiState(speechAudioRoute = SpeechAudioRouteState(active = true,
                    available = setOf(SpeechAudioRoute.SPEAKER), effective = SpeechAudioRoute.SPEAKER)), chatCallbacks())
            }
        }
        compose.onNodeWithTag("speech_audio_route").performClick()
        compose.onNodeWithTag("speech_route_earpiece").assertIsNotEnabled()
    }

    private fun chatCallbacks() = ChatUiCallbacks(
        onComposerChanged = {}, onSend = {}, onChooseMedia = {}, onRemoveAttachment = {},
        onOpenApps = {}, onOpenPlugins = {}, onOpenSettings = {}, onToggleLiveVoice = {},
    )

    private fun settingsCallbacks() = SettingsUiCallbacks(
        onBack = {}, onStartGettingToKnow = {}, onModelSelected = {}, onReasoningEffortSelected = {},
        onVoiceSelected = {}, onSpeechRateSelected = {}, onReadAloudModeSelected = {}, onPreviewVoice = {},
        onStartActionKeySetup = {}, onStartModelToggleKeySetup = {}, onCancelActionKeySetup = {},
        onClearActionKey = {}, onClearModelToggleKey = {}, onCapabilityAccessRequested = {},
    )
}
