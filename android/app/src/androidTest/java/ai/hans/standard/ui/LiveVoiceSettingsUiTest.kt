package ai.hans.standard.ui

import ai.hans.standard.settings.HansSettings
import ai.hans.standard.voice.realtime.LiveVoiceVoiceResolution
import ai.hans.standard.voice.realtime.LiveVoiceVoiceSelection
import ai.hans.standard.voice.realtime.OpenAiLiveProtocol
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Android 12–16 UI regression suite; all speech and persistence callbacks are local fakes. */
class LiveVoiceSettingsUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun all22LiveVoicesAreIndependentlySelectableWithoutTtsSavesOrPreviews() {
        val state = mutableStateOf(project())
        val liveSaves = mutableListOf<String>()
        val ttsSaves = mutableListOf<String>()
        var previews = 0
        showSpeechSettings(
            state = { state.value },
            callbacks = callbacks(
                onLiveSelected = { voice ->
                    liveSaves += voice
                    // Simulate the owner publishing the successfully persisted preference.
                    state.value = state.value.copy(selectedLiveVoiceId = voice)
                },
                onTtsSelected = ttsSaves::add,
                onPreview = { previews++ },
            ),
        )

        compose.onNodeWithText("Vorlesestimme").assertExists()
        compose.onNodeWithText("Live-Stimme für neue Gespräche").assertExists()
        assertEquals(22, OpenAiLiveProtocol.supportedVoices.size)
        OpenAiLiveProtocol.supportedVoices.forEach { voice ->
            compose.onNodeWithTag("live_voice_$voice")
                .performScrollTo()
                .assertTextEquals(voice)
                .performClick()
                .assertIsSelected()
            compose.onNodeWithTag("voice_fable").assertIsSelected()
        }
        compose.onNodeWithTag("live_voice_fable").assertDoesNotExist()
        compose.onNodeWithTag("preview_live_voice").assertDoesNotExist()
        compose.onNodeWithTag("live_voice_selection_summary").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(OpenAiLiveProtocol.supportedVoices.toList(), liveSaves)
            assertTrue(ttsSaves.isEmpty())
            assertEquals(0, previews)
        }
    }

    @Test
    fun savedNextVoiceAndCurrentConfirmedVoiceStayDistinctAfterASelection() {
        val state = mutableStateOf(
            project(
                local = HansLocalUiState(
                    liveVoiceStatus = LiveVoiceUiStatus.LISTENING,
                    liveVoiceVoiceSelection = confirmedRipple(),
                ),
            ),
        )
        val requests = mutableListOf<String>()
        showSpeechSettings({ state.value }, callbacks(onLiveSelected = requests::add))

        compose.onNodeWithTag("live_voice_selection_summary")
            .performScrollTo()
            .assertTextEquals("Dieses Gespräch: ripple")
        compose.onNodeWithTag("live_voice_willow").performScrollTo().performClick()
        compose.onNodeWithTag("live_voice_willow").assertIsNotSelected()
        compose.onNodeWithTag("live_voice_ripple").assertIsSelected()
        compose.onNodeWithTag("live_voice_selection_summary")
            .assertTextEquals("Dieses Gespräch: ripple")
        compose.runOnIdle {
            assertEquals(listOf("willow"), requests)
            // A click alone is not persistence proof and cannot update the effective call voice.
            assertEquals("ripple", state.value.selectedLiveVoiceId)
            state.value = state.value.copy(selectedLiveVoiceId = "willow")
        }

        compose.onNodeWithTag("live_voice_willow").assertIsSelected()
        compose.onNodeWithTag("live_voice_ripple").assertIsNotSelected()
        compose.onNodeWithTag("live_voice_selection_summary")
            .performScrollTo()
            .assertTextEquals("Dieses Gespräch: ripple / Nächstes Gespräch: willow")
        compose.onNodeWithTag("voice_fable").assertIsSelected()
        compose.runOnIdle { state.value = state.value.copy(activeLiveVoiceId = "quartz") }
        compose.onNodeWithTag("live_voice_selection_summary")
            .assertTextEquals("Dieses Gespräch: quartz / Nächstes Gespräch: willow")
    }

    @Test
    fun currentCallTextRequiresAnActiveConfirmedSnapshotAndDisappearsWhenTheCallEnds() {
        val local = mutableStateOf(HansLocalUiState(liveVoiceStatus = LiveVoiceUiStatus.LISTENING))
        showSpeechSettings(
            state = { project(HansSettings(liveVoice = "willow"), local.value) },
        )

        compose.onNodeWithTag("live_voice_selection_summary").assertDoesNotExist()
        compose.onNodeWithTag("live_voice_willow").performScrollTo().assertIsSelected()
        compose.runOnIdle {
            local.value = local.value.copy(liveVoiceVoiceSelection = confirmedRipple())
        }
        compose.onNodeWithTag("live_voice_selection_summary")
            .assertTextEquals("Dieses Gespräch: ripple / Nächstes Gespräch: willow")

        listOf(null, LiveVoiceUiStatus.FAILED).forEach { status ->
            compose.runOnIdle { local.value = local.value.copy(liveVoiceStatus = status) }
            compose.onNodeWithTag("live_voice_selection_summary").assertDoesNotExist()
            compose.onNodeWithTag("live_voice_willow").assertIsSelected()
        }
    }

    @Test
    fun readAloudChoicesAndPreviewRemainTtsOnlyWithAnIndependentLivePreference() {
        val state = mutableStateOf(project(HansSettings(liveVoice = "willow")))
        val liveSaves = mutableListOf<String>()
        val ttsSaves = mutableListOf<String>()
        val previewedVoices = mutableListOf<String?>()
        showSpeechSettings(
            state = { state.value },
            callbacks = callbacks(
                onLiveSelected = liveSaves::add,
                onTtsSelected = { voice ->
                    ttsSaves += voice
                    state.value = state.value.copy(selectedVoiceId = voice)
                },
                onPreview = { previewedVoices += state.value.selectedVoiceId },
            ),
        )

        compose.onNodeWithTag("preview_voice").performScrollTo().performClick()
        HansSettings.SUPPORTED_VOICES.forEach { voice ->
            compose.onNodeWithTag("voice_$voice")
                .performScrollTo()
                .performClick()
                .assertIsSelected()
            compose.onNodeWithTag("live_voice_willow").assertIsSelected()
        }
        compose.onNodeWithTag("voice_nova").performScrollTo().performClick()
        compose.onNodeWithTag("preview_voice").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(HansSettings.SUPPORTED_VOICES.toList() + "nova", ttsSaves)
            assertEquals(listOf("fable", "nova"), previewedVoices)
            assertTrue(liveSaves.isEmpty())
            assertEquals("willow", state.value.selectedLiveVoiceId)
        }
    }

    private fun showSpeechSettings(
        state: () -> SettingsUiState,
        callbacks: SettingsUiCallbacks = callbacks(),
    ) {
        compose.setContent {
            MaterialTheme { SettingsScreen(state(), callbacks) }
        }
        compose.onNodeWithTag("settings_group_speech").performScrollTo().performClick()
    }

    private fun project(
        settings: HansSettings = HansSettings(),
        local: HansLocalUiState = HansLocalUiState(),
    ): SettingsUiState = HansClientUiProjector.project(null, local, settings).settings

    private fun confirmedRipple() = LiveVoiceVoiceSelection(
        requestedTtsVoice = null,
        requestedLiveVoice = "ripple",
        effectiveRealtimeVoice = "ripple",
        resolution = LiveVoiceVoiceResolution.EXACT,
    )

    private fun callbacks(
        onLiveSelected: (String) -> Unit = {},
        onTtsSelected: (String) -> Unit = {},
        onPreview: () -> Unit = {},
    ) = SettingsUiCallbacks(
        onBack = {},
        onStartGettingToKnow = {},
        onModelSelected = {},
        onReasoningEffortSelected = {},
        onVoiceSelected = onTtsSelected,
        onLiveVoiceSelected = onLiveSelected,
        onSpeechRateSelected = {},
        onReadAloudModeSelected = {},
        onPreviewVoice = onPreview,
        onStartActionKeySetup = {},
        onStartModelToggleKeySetup = {},
        onCancelActionKeySetup = {},
        onClearActionKey = {},
        onClearModelToggleKey = {},
        onCapabilityAccessRequested = {},
    )
}
