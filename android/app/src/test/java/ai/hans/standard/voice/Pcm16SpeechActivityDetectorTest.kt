package ai.hans.standard.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Pcm16SpeechActivityDetectorTest {
    private val detector = Pcm16SpeechActivityDetector()
    private val format = PcmAudioFormat(sampleRateHz = 24_000)

    @Test
    fun silenceQuietNoiseAndSingleClickDoNotCountAsSpeech() {
        assertFalse(detector.containsSpeechLikeActivity(ByteArray(512), format))
        assertFalse(detector.containsSpeechLikeActivity(pcm16(IntArray(256) { 180 }), format))

        val isolatedClick = IntArray(256)
        isolatedClick[100] = 4_000
        assertFalse(detector.containsSpeechLikeActivity(pcm16(isolatedClick), format))
    }

    @Test
    fun sustainedSpeechLikeEnergyCountsWithoutMutatingPcm() {
        val samples = IntArray(256) { index ->
            when (index % 8) {
                0, 1 -> 2_400
                2, 3 -> 1_200
                4, 5 -> -2_400
                else -> -1_200
            }
        }
        val pcm = pcm16(samples)
        val original = pcm.copyOf()

        assertTrue(detector.containsSpeechLikeActivity(pcm, format))
        assertTrue(original.contentEquals(pcm))
    }

    @Test
    fun unsupportedOrTruncatedBuffersFailClosed() {
        assertFalse(
            detector.containsSpeechLikeActivity(
                pcm16(IntArray(256) { 2_000 }),
                format.copy(bitsPerSample = 8),
            ),
        )
        assertFalse(detector.containsSpeechLikeActivity(ByteArray(31), format))
    }

    private fun pcm16(samples: IntArray): ByteArray = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, sample ->
            val value = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bytes[index * 2] = (value and 0xff).toByte()
            bytes[index * 2 + 1] = ((value ushr 8) and 0xff).toByte()
        }
    }
}
