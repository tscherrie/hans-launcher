package ai.hans.standard.settings

import ai.hans.standard.voice.tts.android.OpenAiTtsRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HansSettingsVoiceContractTest {
    @Test
    fun settingsAndStreamingTransportExposeTheSameVoiceCatalog() {
        assertEquals(OpenAiTtsRequest.supportedVoices, HansSettings.SUPPORTED_VOICES)
        assertEquals("fable", HansSettings().voice)
    }

    @Test
    fun unsupportedVoiceCannotBecomePersistedSettingsState() {
        assertThrows(IllegalArgumentException::class.java) {
            HansSettings(voice = "not-a-public-voice")
        }
    }
}
