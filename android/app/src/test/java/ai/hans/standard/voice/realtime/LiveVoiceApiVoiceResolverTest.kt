package ai.hans.standard.voice.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveVoiceApiVoiceResolverTest {
    @Test
    fun `every Live voice resolves exactly without masquerading as a speech preference`() {
        assertEquals(22, LiveVoiceApiVoiceResolver.supportedVoices.size)
        OpenAiLiveProtocol.supportedVoices.forEach { voice ->
            val selection = LiveVoiceApiVoiceResolver.resolve(voice)

            assertEquals(voice, selection.requestedLiveVoice)
            assertEquals(voice, selection.effectiveRealtimeVoice)
            assertEquals(LiveVoiceVoiceResolution.EXACT, selection.resolution)
            assertNull(selection.requestedTtsVoice)
        }
    }

    @Test
    fun `missing unsupported and malformed preferences use Ripple without TTS mapping`() {
        listOf(null, "", " ", "unknown", "fable", "nova", "onyx", "rip\u0000ple", "x".repeat(121))
            .forEach { voice ->
                val selection = LiveVoiceApiVoiceResolver.resolve(voice)

                assertEquals("ripple", selection.effectiveRealtimeVoice)
                assertEquals(LiveVoiceVoiceResolution.FALLBACK, selection.resolution)
                assertNull(selection.requestedTtsVoice)
            }
    }

    @Test
    fun `explicit Live choice is normalized using a locale independent identifier`() {
        val selection = LiveVoiceApiVoiceResolver.resolve("  RIPPLE  ")

        assertEquals("ripple", selection.requestedLiveVoice)
        assertEquals("ripple", selection.effectiveRealtimeVoice)
        assertEquals(LiveVoiceVoiceResolution.EXACT, selection.resolution)
    }

    @Test
    fun `legacy Realtime catalogue defaults and speech mappings stay unchanged`() {
        assertEquals("marin", LiveVoiceSessionConfig().voice)
        assertEquals(10, LiveVoiceRealtimeVoiceMapper.supportedRealtimeVoices.size)
        assertEquals("ballad", LiveVoiceRealtimeVoiceMapper.resolve("fable").effectiveRealtimeVoice)
        val ripple = LiveVoiceRealtimeVoiceMapper.resolve("ripple")
        assertEquals("marin", ripple.effectiveRealtimeVoice)
        assertEquals(LiveVoiceVoiceResolution.FALLBACK, ripple.resolution)
        assertNull(ripple.requestedLiveVoice)
    }
}
