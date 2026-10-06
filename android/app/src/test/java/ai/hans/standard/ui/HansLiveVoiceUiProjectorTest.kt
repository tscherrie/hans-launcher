package ai.hans.standard.ui

import ai.hans.standard.localization.TestResourceTextResolver
import java.util.Locale

import ai.hans.standard.settings.HansSettings
import ai.hans.standard.voice.realtime.LiveVoiceVoiceResolution
import ai.hans.standard.voice.realtime.LiveVoiceVoiceSelection
import ai.hans.standard.voice.realtime.CodexLiveVoiceVoiceResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HansLiveVoiceUiProjectorTest {
    private val localizationText by lazy { TestResourceTextResolver(Locale.GERMAN) }

    @Test
    fun defaultsKeepFableForReadAloudAndCoveForNewLiveCallsWithoutClaimingAnActiveVoice() {
        val state = project()

        assertEquals("fable", state.selectedVoiceId)
        assertEquals("cove", state.selectedLiveVoiceId)
        assertNull(state.activeLiveVoiceId)
    }

    @Test
    fun liveCatalogueContainsEveryProtocolVoiceExactlyOnceWithActualIdsAndNoTtsAliases() {
        val state = project()
        val expected = listOf(
            "juniper", "maple", "spruce", "ember", "vale", "breeze", "arbor", "sol", "cove",
        )

        assertEquals(9, state.liveVoices.size)
        assertEquals(expected, state.liveVoices.map { it.id })
        assertEquals(CodexLiveVoiceVoiceResolver.supportedVoices, state.liveVoices.map { it.id }.toSet())
        assertEquals(expected, state.liveVoices.map { it.label })
        assertEquals(HansSettings.SUPPORTED_VOICES.toList(), state.voices.map { it.id })
        assertFalse(state.liveVoices.any { it.id in setOf("fable", "nova", "onyx") })
    }

    @Test
    fun eachSavedLiveVoiceIsIndependentFromEveryReadAloudVoice() {
        HansSettings.SUPPORTED_VOICES.forEach { ttsVoice ->
            HansSettings.SUPPORTED_CODEX_LIVE_VOICES.forEach { liveVoice ->
                val state = project(HansSettings(voice = ttsVoice, codexLiveVoice = liveVoice))

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
            effectiveRealtimeVoice = "cove",
            resolution = LiveVoiceVoiceResolution.FALLBACK,
        )
        val local = HansLocalUiState(
            liveVoiceStatus = LiveVoiceUiStatus.LISTENING,
            liveVoiceVoiceSelection = snapshot,
        )
        val state = project(HansSettings(voice = "fable", codexLiveVoice = "ember"), local)

        assertEquals("cove", state.activeLiveVoiceId)
        assertEquals("ember", state.selectedLiveVoiceId)
        assertEquals("fable", state.selectedVoiceId)

        val changedPreference = project(HansSettings(voice = "nova", codexLiveVoice = "maple"), local)
        assertEquals("cove", changedPreference.activeLiveVoiceId)
        assertEquals("maple", changedPreference.selectedLiveVoiceId)
        assertEquals("nova", changedPreference.selectedVoiceId)
    }

    @Test
    fun liveStatusOrSavedPreferenceAloneNeverEstablishesAnActiveVoice() {
        LiveVoiceUiStatus.entries.forEach { status ->
            val state = project(
                HansSettings(codexLiveVoice = "ember"),
                HansLocalUiState(liveVoiceStatus = status),
            )

            assertNull("Unconfirmed voice while $status", state.activeLiveVoiceId)
            assertEquals("ember", state.selectedLiveVoiceId)
        }
    }

    @Test
    fun idleOrFailedCallCannotExposeAStaleVoiceSnapshotAsCurrent() {
        val snapshot = LiveVoiceVoiceSelection(
            requestedTtsVoice = null,
            requestedLiveVoice = "cove",
            effectiveRealtimeVoice = "cove",
            resolution = LiveVoiceVoiceResolution.EXACT,
        )
        listOf(null, LiveVoiceUiStatus.FAILED).forEach { status ->
            val state = project(
                HansSettings(codexLiveVoice = "ember"),
                HansLocalUiState(liveVoiceStatus = status, liveVoiceVoiceSelection = snapshot),
            )

            assertNull(state.activeLiveVoiceId)
            assertEquals("ember", state.selectedLiveVoiceId)
        }
    }

    private fun project(
        settings: HansSettings = HansSettings(),
        local: HansLocalUiState = HansLocalUiState(),
    ): SettingsUiState = HansClientUiProjector.project(null, local, settings, text = localizationText).settings
}
