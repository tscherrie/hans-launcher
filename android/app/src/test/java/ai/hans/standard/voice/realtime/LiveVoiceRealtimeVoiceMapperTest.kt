package ai.hans.standard.voice.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveVoiceRealtimeVoiceMapperTest {
    @Test
    fun everySharedSpeechVoiceKeepsItsExactRealtimeIdentity() {
        LiveVoiceRealtimeVoiceMapper.supportedRealtimeVoices.forEach { voice ->
            assertEquals(
                LiveVoiceVoiceSelection(
                    requestedTtsVoice = voice,
                    effectiveRealtimeVoice = voice,
                    resolution = LiveVoiceVoiceResolution.EXACT,
                ),
                LiveVoiceRealtimeVoiceMapper.resolve(voice),
            )
        }
    }

    @Test
    fun speechOnlyVoicesUseTheDocumentedApproximationTable() {
        val expected = mapOf(
            "fable" to "ballad",
            "nova" to "coral",
            "onyx" to "cedar",
        )
        assertEquals(expected, LiveVoiceRealtimeVoiceMapper.approximateVoices)
        expected.forEach { (requested, effective) ->
            assertEquals(
                LiveVoiceVoiceSelection(
                    requestedTtsVoice = requested,
                    effectiveRealtimeVoice = effective,
                    resolution = LiveVoiceVoiceResolution.APPROXIMATE,
                ),
                LiveVoiceRealtimeVoiceMapper.resolve(requested),
            )
        }
    }

    @Test
    fun unknownOrUnavailableSettingFallsBackTransparentlyToMarin() {
        val unknown = LiveVoiceRealtimeVoiceMapper.resolve("future-voice")
        assertEquals("future-voice", unknown.requestedTtsVoice)
        assertEquals("marin", unknown.effectiveRealtimeVoice)
        assertEquals(LiveVoiceVoiceResolution.FALLBACK, unknown.resolution)

        val unavailable = LiveVoiceRealtimeVoiceMapper.resolve(null)
        assertNull(unavailable.requestedTtsVoice)
        assertEquals("marin", unavailable.effectiveRealtimeVoice)
        assertEquals(LiveVoiceVoiceResolution.FALLBACK, unavailable.resolution)
    }
}
