package ai.hans.standard.ui

import ai.hans.standard.settings.HansSettings
import ai.hans.standard.voice.realtime.LiveVoiceVoiceResolution
import ai.hans.standard.voice.realtime.LiveVoiceVoiceSelection
import ai.hans.standard.voice.realtime.OpenAiLiveProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HansLiveVoiceUiProjectorTest {
    @Test
    fun defaultsKeepFableForReadAloudAndRippleForNewLiveCallsWithoutClaimingAnActiveVoice() {
        val state = project()

        assertEquals("fable", state.selectedVoiceId)
        assertEquals("ripple", state.selectedLiveVoiceId)
        assertNull(state.activeLiveVoiceId)
    }

    @Test
    fun liveCatalogueContainsEveryProtocolVoiceExactlyOnceWithActualIdsAndNoTtsAliases() {
        val state = project()
        val expected = listOf(
            "alloy", "ash", "ballad", "beacon", "bossa", "cedar", "cinder", "coral",
            "delta", "echo", "gleam", "marin", "meridian", "quartz", "ripple", "sage",
            "shimmer", "stone", "tempo", "verse", "vesper", "willow",
        )

        assertEquals(22, state.liveVoices.size)
        assertEquals(expected, state.liveVoices.map { it.id })
        assertEquals(OpenAiLiveProtocol.supportedVoices, state.liveVoices.map { it.id }.toSet())
        assertEquals(expected, state.liveVoices.map { it.label })
        assertEquals(HansSettings.SUPPORTED_VOICES.toList(), state.voices.map { it.id })
        assertFalse(state.liveVoices.any { it.id in setOf("fable", "nova", "onyx") })
    }

    @Test
    fun eachSavedLiveVoiceIsIndependentFromEveryReadAloudVoice() {
        HansSettings.SUPPORTED_VOICES.forEach { ttsVoice ->
            HansSettings.SUPPORTED_LIVE_VOICES.forEach { liveVoice ->
                val state = project(HansSettings(voice = ttsVoice, liveVoice = liveVoice))

                assertEquals(ttsVoice, state.selectedVoiceId)
                assertEquals(liveVoice, state.selectedLiveVoiceId)
                assertNull(state.activeLiveVoiceId)
            }
        }
    }

    @Test
    fun currentCallUsesOnlyConfirmedEffectiveVoiceWhileSavedPreferenceTargetsTheNextCall() {
        val snapshot = LiveVoiceVoiceSelection(
            requestedTtsVoice = null,
            requestedLiveVoice = "beacon",
            effectiveRealtimeVoice = "ripple",
            resolution = LiveVoiceVoiceResolution.FALLBACK,
        )
        val local = HansLocalUiState(
            liveVoiceStatus = LiveVoiceUiStatus.LISTENING,
            liveVoiceVoiceSelection = snapshot,
        )
        val state = project(HansSettings(voice = "fable", liveVoice = "willow"), local)

        assertEquals("ripple", state.activeLiveVoiceId)
        assertEquals("willow", state.selectedLiveVoiceId)
        assertEquals("fable", state.selectedVoiceId)

        val changedPreference = project(HansSettings(voice = "nova", liveVoice = "quartz"), local)
        assertEquals("ripple", changedPreference.activeLiveVoiceId)
        assertEquals("quartz", changedPreference.selectedLiveVoiceId)
        assertEquals("nova", changedPreference.selectedVoiceId)
    }

    @Test
    fun liveStatusOrSavedPreferenceAloneNeverEstablishesAnActiveVoice() {
        LiveVoiceUiStatus.entries.forEach { status ->
            val state = project(
                HansSettings(liveVoice = "willow"),
                HansLocalUiState(liveVoiceStatus = status),
            )

            assertNull("Unconfirmed voice while $status", state.activeLiveVoiceId)
            assertEquals("willow", state.selectedLiveVoiceId)
        }
    }

    @Test
    fun idleOrFailedCallCannotExposeAStaleVoiceSnapshotAsCurrent() {
        val snapshot = LiveVoiceVoiceSelection(
            requestedTtsVoice = null,
            requestedLiveVoice = "ripple",
            effectiveRealtimeVoice = "ripple",
            resolution = LiveVoiceVoiceResolution.EXACT,
        )
        listOf(null, LiveVoiceUiStatus.FAILED).forEach { status ->
            val state = project(
                HansSettings(liveVoice = "willow"),
                HansLocalUiState(liveVoiceStatus = status, liveVoiceVoiceSelection = snapshot),
            )

            assertNull(state.activeLiveVoiceId)
            assertEquals("willow", state.selectedLiveVoiceId)
        }
    }

    private fun project(
        settings: HansSettings = HansSettings(),
        local: HansLocalUiState = HansLocalUiState(),
    ): SettingsUiState = HansClientUiProjector.project(null, local, settings).settings
}
