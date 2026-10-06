package ai.hans.standard.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudSettingsWiringTest {
    @Test fun textReadAloudKeepsModeButDoesNotDuplicateTheVoicePicker() {
        val section = settingsSource().substringAfter("private fun ReadAloudSettings(")
            .substringBefore("private fun LiveSpeechSettings(")
        assertTrue(section.contains("ReadAloudUiMode.entries"))
        assertTrue(section.contains("callbacks.onReadAloudModeSelected(mode)"))
        assertTrue(section.contains("voice_settings_text_replies_help"))
        assertFalse(section.contains("onClick = callbacks.onPreviewVoice"))
        assertFalse(section.contains("callbacks.onVoiceSelected"))
        assertFalse(section.contains("callbacks.onSpeechRateSelected"))
        assertFalse(section.contains("state.voices"))
        assertFalse(section.contains("state.selectedVoiceId"))
        assertFalse(section.contains("SpeechRateOptions"))
    }

    @Test fun theSingleVoiceChooserKeepsSelectionOwnerConfirmedAndPreviewExplicit() {
        val section = settingsSource().substringAfter("private fun LiveSpeechSettings(")
            .substringBefore("private fun InputSettings(")
        assertTrue(section.contains("if (showVoiceChooser)"))
        assertTrue(section.contains("selected = state.selectedLiveVoiceId == voice.id"))
        assertTrue(section.contains("callbacks.onLiveVoiceSelected(voice.id)"))
        assertTrue(section.contains("onClick = callbacks.onPreviewVoice"))
        assertTrue(section.contains("state.activeLiveVoiceId == null"))
        assertTrue(section.contains("!state.voiceSessionActive"))
        assertFalse(section.contains("callbacks.onVoiceSelected"))
        assertFalse(section.contains("mutableStateOf(state.selectedLiveVoiceId)"))
    }

    @Test fun unsupportedHoldAndGlossaryControlsAreAbsentWithoutPreferenceWrites() {
        val source = settingsSource()
        assertFalse(source.contains("callbacks.onDictationTriggerSelected"))
        assertFalse(source.contains("callbacks.onCameraHoldToTalkChanged"))
        assertFalse(source.contains("callbacks.onConfirmedSttGlossarySaved"))
        assertFalse(source.contains("callbacks.onSttLatencyChanged"))
        assertTrue(source.contains("voice_settings_task_help"))
        assertTrue(source.contains("voice_settings_call_help"))
    }

    @Test fun keyfreeSettingsDoNotExposeOrMutateLegacyCredentials() {
        val section = settingsSource().substringAfter("private fun ReadAloudSettings(")
            .substringBefore("private fun LiveSpeechSettings(")
        assertFalse(section.contains("SpeechCredentialSettings"))
        assertFalse(section.contains("onConfigureSpeechCredential"))
        assertFalse(section.contains("onRemoveSpeechCredential"))
        assertFalse(section.contains("advanced_read_aloud_toggle"))
    }

    private fun settingsSource(): String = sequenceOf(
        File("src/main/java/ai/hans/standard/ui/SettingsScreen.kt"),
        File("android/app/src/main/java/ai/hans/standard/ui/SettingsScreen.kt"),
    ).first(File::isFile).readText()
}
