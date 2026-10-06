package ai.hans.standard.ui

import ai.hans.standard.settings.HansSettings
import ai.hans.standard.voice.realtime.LiveVoiceVoiceResolution
import ai.hans.standard.voice.realtime.LiveVoiceVoiceSelection
import ai.hans.standard.voice.realtime.CodexLiveVoiceVoiceResolver
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Android 12–16 UI regression suite; all speech and persistence callbacks are local fakes. */
class LiveVoiceSettingsUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun all9CodexLiveVoicesAreIndependentlySelectableWithoutTtsSavesOrPreviews() {
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

        compose.onNodeWithText("Textantworten vorlesen").assertExists()
        compose.onNodeWithTag("open_voice_chooser").performScrollTo()
            .assertTextEquals("Hans’ Stimme: Cove")
        compose.onNodeWithTag("live_voice_cove").assertDoesNotExist()
        compose.onNodeWithTag("preview_voice").assertDoesNotExist()
        compose.onNodeWithTag("open_voice_chooser").performClick()
        assertEquals(9, CodexLiveVoiceVoiceResolver.supportedVoices.size)
        CodexLiveVoiceVoiceResolver.supportedVoices.forEach { voice ->
            compose.onNodeWithTag("live_voice_$voice")
                .performScrollTo()
                .performClick()
                .assertIsSelected()
            compose.onNodeWithTag("voice_fable").assertDoesNotExist()
        }
        compose.onNodeWithTag("live_voice_fable").assertDoesNotExist()
        compose.onNodeWithTag("close_voice_chooser").performClick()
        compose.onNodeWithTag("live_voice_cove").assertDoesNotExist()
        compose.onNodeWithTag("open_voice_chooser").assertTextContains("Hans’ Stimme:", substring = true)
        compose.runOnIdle {
            assertEquals(CodexLiveVoiceVoiceResolver.supportedVoices.toList(), liveSaves)
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
                    liveVoiceVoiceSelection = confirmedCove(),
                ),
            ),
        )
        val requests = mutableListOf<String>()
        showSpeechSettings({ state.value }, callbacks(onLiveSelected = requests::add))

        compose.onNodeWithTag("live_voice_selection_summary")
            .performScrollTo()
            .assertTextEquals("Aktuelle Sprachsitzung: Cove")
        compose.onNodeWithTag("open_voice_chooser").performScrollTo().performClick()
        compose.onNodeWithTag("preview_voice").assertIsNotEnabled()
        compose.onNodeWithTag("live_voice_ember").performScrollTo().performClick()
        compose.onNodeWithTag("live_voice_ember").assertIsNotSelected()
        compose.onNodeWithTag("live_voice_cove").assertIsSelected()
        compose.runOnIdle {
            assertEquals(listOf("ember"), requests)
            // A click alone is not persistence proof and cannot update the effective call voice.
            assertEquals("cove", state.value.selectedLiveVoiceId)
            state.value = state.value.copy(selectedLiveVoiceId = "ember")
        }

        compose.onNodeWithTag("live_voice_ember").assertIsSelected()
        compose.onNodeWithTag("live_voice_cove").assertIsNotSelected()
        compose.onNodeWithTag("close_voice_chooser").performClick()
        compose.onNodeWithTag("open_voice_chooser").assertTextEquals("Hans’ Stimme: Ember")
        compose.onNodeWithTag("live_voice_selection_summary")
            .performScrollTo()
            .assertTextEquals("Aktuelle Sprachsitzung: Cove")
        compose.onNodeWithTag("voice_fable").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(activeLiveVoiceId = "maple") }
        compose.onNodeWithTag("live_voice_selection_summary")
            .assertTextEquals("Aktuelle Sprachsitzung: Maple")
    }

    @Test
    fun currentCallTextRequiresAnActiveConfirmedSnapshotAndDisappearsWhenTheCallEnds() {
        val local = mutableStateOf(HansLocalUiState(liveVoiceStatus = LiveVoiceUiStatus.LISTENING))
        showSpeechSettings(
            state = { project(HansSettings(codexLiveVoice = "ember"), local.value) },
        )

        compose.onNodeWithTag("live_voice_selection_summary").assertDoesNotExist()
        compose.onNodeWithTag("open_voice_chooser").performScrollTo().assertTextEquals("Hans’ Stimme: Ember")
        compose.runOnIdle {
            local.value = local.value.copy(liveVoiceVoiceSelection = confirmedCove())
        }
        compose.onNodeWithTag("live_voice_selection_summary")
            .assertTextEquals("Aktuelle Sprachsitzung: Cove")

        listOf(null, LiveVoiceUiStatus.FAILED).forEach { status ->
            compose.runOnIdle { local.value = local.value.copy(liveVoiceStatus = status) }
            compose.onNodeWithTag("live_voice_selection_summary").assertDoesNotExist()
            compose.onNodeWithTag("open_voice_chooser").assertTextEquals("Hans’ Stimme: Ember")
        }
    }

    @Test
    fun anActiveTaskVoiceWithoutAConfirmedVoiceIdCannotStartAPreview() {
        var previews = 0
        showSpeechSettings(
            state = { project().copy(voiceSessionActive = true, activeLiveVoiceId = null) },
            callbacks = callbacks(onPreview = { previews++ }),
        )
        compose.onNodeWithTag("open_voice_chooser").performScrollTo().performClick()
        compose.onNodeWithTag("preview_voice").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, previews) }
    }

    @Test
    fun readAloudUsesSelectedLiveVoiceWithoutApiVoiceOrUnsupportedRateControls() {
        val state = mutableStateOf(project(HansSettings(codexLiveVoice = "ember")))
        val liveSaves = mutableListOf<String>()
        val ttsSaves = mutableListOf<String>()
        val previewedVoices = mutableListOf<String?>()
        showSpeechSettings(
            state = { state.value },
            callbacks = callbacks(
                onLiveSelected = { voice ->
                    liveSaves += voice
                    state.value = state.value.copy(selectedLiveVoiceId = voice)
                },
                onTtsSelected = { voice ->
                    ttsSaves += voice
                    state.value = state.value.copy(selectedVoiceId = voice)
                },
                onPreview = { previewedVoices += state.value.selectedLiveVoiceId },
            ),
        )

        compose.onNodeWithTag("read_aloud_live_voice_help")
            .performScrollTo().assertTextEquals("Liest fertige Textantworten mit derselben Stimme vor. Antworten während eines Telefonats bleiben davon unberührt.")
        compose.onNodeWithTag("open_voice_chooser").performScrollTo().performClick()
        compose.onNodeWithTag("preview_voice").performClick()
        HansSettings.SUPPORTED_VOICES.forEach { voice ->
            compose.onNodeWithTag("voice_$voice").assertDoesNotExist()
        }
        SpeechRateOptions.forEach { rate -> compose.onNodeWithTag("speech_rate_$rate").assertDoesNotExist() }
        compose.onNodeWithTag("live_voice_maple").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("preview_voice").performClick()
        compose.runOnIdle {
            assertTrue(ttsSaves.isEmpty())
            assertEquals(listOf("ember", "maple"), previewedVoices)
            assertEquals(listOf("maple"), liveSaves)
            assertEquals("maple", state.value.selectedLiveVoiceId)
        }
    }

    private fun showSpeechSettings(
        state: () -> SettingsUiState,
        callbacks: SettingsUiCallbacks = callbacks(),
    ) {
        compose.setGermanContent {
            MaterialTheme { SettingsScreen(state(), callbacks) }
        }
        compose.onNodeWithTag("settings_group_speech").performScrollTo().performClick()
    }

    private fun project(
        settings: HansSettings = HansSettings(),
        local: HansLocalUiState = HansLocalUiState(),
    ): SettingsUiState = HansClientUiProjector.project(null, local, settings, ai.hans.standard.localization.AndroidHansTextResolver(germanUiTestContext())).settings

    private fun confirmedCove() = LiveVoiceVoiceSelection(
        requestedTtsVoice = null,
        requestedLiveVoice = "cove",
        effectiveRealtimeVoice = "cove",
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
