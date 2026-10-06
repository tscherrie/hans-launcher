package ai.hans.standard.ui

import ai.hans.standard.voice.audio.SpeechAudioRoute
import ai.hans.standard.voice.audio.SpeechAudioRouteRequestResult
import ai.hans.standard.voice.audio.SpeechAudioRouteState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Runs unchanged in the Android 12–16 instrumentation matrix; no time-driven UI state. */
class LiveVoiceSpeakerUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun persistentLiveButtonWaitsForEffectiveRouteAndCanSwitchBothWays() {
        val route = mutableStateOf(SpeechAudioRouteState(active = true,
            available = setOf(SpeechAudioRoute.SPEAKER, SpeechAudioRoute.EARPIECE),
            effective = SpeechAudioRoute.SPEAKER))
        val phase = mutableStateOf(LiveVoiceUiStatus.LISTENING)
        val requests = mutableListOf<SpeechAudioRoute>()
        compose.setGermanContent {
            MaterialTheme {
                ChatScreen(ChatUiState(liveVoiceStatus = phase.value, speechAudioRoute = route.value),
                    callbacks(requests::add))
            }
        }
        compose.onNodeWithTag("live_call_speaker").assertIsSelected().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(SpeechAudioRoute.EARPIECE), requests)
            route.value = route.value.copy(requested = SpeechAudioRoute.EARPIECE)
        }
        compose.onNodeWithTag("live_call_speaker").assertIsSelected()
            .assertContentDescriptionEquals("Tonausgabe: Lautsprecher. Zur Hörmuschel wechseln")
        compose.runOnIdle {
            route.value = route.value.copy(effective = SpeechAudioRoute.EARPIECE)
            phase.value = LiveVoiceUiStatus.HANS_SPEAKING
        }
        compose.onNodeWithTag("live_call_speaker").assertIsNotSelected()
            .assertContentDescriptionEquals("Tonausgabe: Hörmuschel. Lautsprecher einschalten")
            .performClick()
        compose.runOnIdle {
            assertEquals(listOf(SpeechAudioRoute.EARPIECE, SpeechAudioRoute.SPEAKER), requests)
            route.value = route.value.copy(failure = SpeechAudioRouteRequestResult.FAILED)
            phase.value = LiveVoiceUiStatus.WAITING_FOR_TASK
        }
        compose.onNodeWithTag("live_call_speaker").assertIsNotSelected().assertIsEnabled()
    }

    @Test fun noEarpieceOrNoActiveRouteDisablesTheSwitchWithoutHidingIt() {
        val route = mutableStateOf(SpeechAudioRouteState(active = true,
            available = setOf(SpeechAudioRoute.SPEAKER), effective = SpeechAudioRoute.SPEAKER))
        compose.setGermanContent {
            MaterialTheme {
                LiveVoiceCallScreen(LiveVoiceUiStatus.LISTENING, false, {}, {}, audioRoute = route.value)
            }
        }
        compose.onNodeWithTag("live_call_speaker").assertIsSelected().assertIsNotEnabled()
        compose.runOnIdle { route.value = SpeechAudioRouteState() }
        compose.onNodeWithTag("live_call_speaker").assertIsNotSelected().assertIsNotEnabled()
    }

    @Test fun externalRouteIsDescribedTruthfullyAndConnectingControlStaysDisabled() {
        val phase = mutableStateOf(LiveVoiceUiStatus.CONNECTING)
        val requests = mutableListOf<SpeechAudioRoute>()
        compose.setGermanContent {
            MaterialTheme {
                LiveVoiceCallScreen(phase.value, false, {}, {},
                    audioRoute = SpeechAudioRouteState(active = true,
                        available = setOf(SpeechAudioRoute.SPEAKER), effective = SpeechAudioRoute.EXTERNAL),
                    onAudioRouteRequested = requests::add)
            }
        }
        compose.onNodeWithTag("live_call_speaker").assertIsNotEnabled().assertIsNotSelected()
        compose.runOnIdle { phase.value = LiveVoiceUiStatus.LISTENING }
        compose.onNodeWithTag("live_call_speaker")
            .assertContentDescriptionEquals("Tonausgabe: Headset / Bluetooth. Lautsprecher einschalten")
            .assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(SpeechAudioRoute.SPEAKER), requests) }
    }

    private fun callbacks(onRoute: (SpeechAudioRoute) -> Unit) = ChatUiCallbacks(
        onComposerChanged = {}, onSend = {}, onChooseMedia = {}, onRemoveAttachment = {},
        onOpenApps = {}, onOpenPlugins = {}, onOpenSettings = {}, onToggleLiveVoice = {},
        onSpeechAudioRouteRequested = onRoute,
    )
}
