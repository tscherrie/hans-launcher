package ai.hans.standard.settings

import ai.hans.standard.voice.realtime.OpenAiLiveProtocol
import ai.hans.standard.voice.tts.android.OpenAiTtsRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HansLiveVoiceSettingsTest {
    @Test
    fun `Codex voice is independent of API Live and speech with no unsupported migration`() {
        val settings = HansSettings(voice = "nova", liveVoice = "willow")
        assertEquals("cove", settings.codexLiveVoice)
        val expected = setOf("juniper", "maple", "spruce", "ember", "vale", "breeze", "arbor", "sol", "cove")
        assertEquals(expected, HansSettings.SUPPORTED_CODEX_LIVE_VOICES)
        expected.forEach { voice ->
            val updated = settings.copy(codexLiveVoice = voice)
            assertEquals("willow", updated.liveVoice)
            assertEquals("nova", updated.voice)
        }
        assertThrows(IllegalArgumentException::class.java) { settings.copy(codexLiveVoice = "ripple") }
    }

    @Test
    fun `new settings use Ripple for Live while keeping Fable for speech`() {
        val settings = HansSettings()

        assertEquals("ripple", settings.liveVoice)
        assertEquals("fable", settings.voice)
    }

    @Test
    fun `all twenty two Live wire voices remain independent from speech choices`() {
        val expected = setOf(
            "alloy", "ash", "ballad", "beacon", "bossa", "cedar", "cinder", "coral",
            "delta", "echo", "gleam", "marin", "meridian", "quartz", "ripple", "sage",
            "shimmer", "stone", "tempo", "verse", "vesper", "willow",
        )
        assertEquals(expected, HansSettings.SUPPORTED_LIVE_VOICES)
        assertEquals(OpenAiLiveProtocol.supportedVoices, HansSettings.SUPPORTED_LIVE_VOICES)

        expected.forEach { voice ->
            val settings = HansSettings(voice = "nova", liveVoice = voice)
            assertEquals(voice, settings.liveVoice)
            assertEquals("nova", settings.voice)
        }
    }

    @Test
    fun `speech allowlist stays at thirteen and rejects Live only voices`() {
        val expectedSpeech = setOf(
            "alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx",
            "sage", "shimmer", "verse", "marin", "cedar",
        )
        assertEquals(expectedSpeech, HansSettings.SUPPORTED_VOICES)
        assertEquals(expectedSpeech, OpenAiTtsRequest.supportedVoices)
        (HansSettings.SUPPORTED_LIVE_VOICES - expectedSpeech).forEach { voice ->
            assertThrows(IllegalArgumentException::class.java) { HansSettings(voice = voice) }
        }
        (expectedSpeech - HansSettings.SUPPORTED_LIVE_VOICES).forEach { voice ->
            assertThrows(IllegalArgumentException::class.java) { HansSettings(liveVoice = voice) }
        }
    }
}
