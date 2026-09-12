package ai.hans.standard.voice

import kotlin.math.abs

/**
 * Content-free, local guard against abandoning a long, actively spoken dictation.
 *
 * This is deliberately not a transcription engine. It recognizes sustained
 * speech-like PCM energy so the one-minute inactivity watchdog can distinguish
 * an open, silent microphone from a user who is still talking while the remote
 * transcription stream has not emitted its next text delta yet.
 */
fun interface PcmSpeechActivityDetector {
    fun containsSpeechLikeActivity(pcmBytes: ByteArray, format: PcmAudioFormat): Boolean
}

class Pcm16SpeechActivityDetector(
    private val minimumMeanAbsoluteAmplitude: Int = DEFAULT_MINIMUM_MEAN_AMPLITUDE,
    private val minimumPeakAmplitude: Int = DEFAULT_MINIMUM_PEAK_AMPLITUDE,
    private val minimumActiveSampleRatio: Double = DEFAULT_MINIMUM_ACTIVE_SAMPLE_RATIO,
) : PcmSpeechActivityDetector {
    init {
        require(minimumMeanAbsoluteAmplitude in 1..Short.MAX_VALUE.toInt())
        require(minimumPeakAmplitude in minimumMeanAbsoluteAmplitude..32_768)
        require(minimumActiveSampleRatio in 0.0..1.0)
    }

    override fun containsSpeechLikeActivity(
        pcmBytes: ByteArray,
        format: PcmAudioFormat,
    ): Boolean {
        if (format.bitsPerSample != 16 || pcmBytes.size < MINIMUM_SAMPLE_COUNT * 2) return false

        val completeByteCount = pcmBytes.size - (pcmBytes.size % 2)
        val sampleCount = completeByteCount / 2
        var absoluteSum = 0L
        var peak = 0
        var activeSamples = 0
        var offset = 0
        while (offset < completeByteCount) {
            val sample = (
                (pcmBytes[offset].toInt() and 0xff) or
                    (pcmBytes[offset + 1].toInt() shl 8)
                ).toShort().toInt()
            val amplitude = if (sample == Short.MIN_VALUE.toInt()) 32_768 else abs(sample)
            absoluteSum += amplitude
            if (amplitude > peak) peak = amplitude
            if (amplitude >= minimumPeakAmplitude) activeSamples += 1
            offset += 2
        }

        val mean = absoluteSum.toDouble() / sampleCount
        val activeRatio = activeSamples.toDouble() / sampleCount
        return mean >= minimumMeanAbsoluteAmplitude &&
            peak >= minimumPeakAmplitude &&
            activeRatio >= minimumActiveSampleRatio
    }

    companion object {
        /** Roughly -43 dBFS mean energy, above ordinary close-mic electronic noise. */
        const val DEFAULT_MINIMUM_MEAN_AMPLITUDE = 240

        /** Reject isolated low-level noise even when its average is elevated. */
        const val DEFAULT_MINIMUM_PEAK_AMPLITUDE = 900

        /** A single click must not keep an otherwise forgotten recording alive. */
        const val DEFAULT_MINIMUM_ACTIVE_SAMPLE_RATIO = 0.01

        private const val MINIMUM_SAMPLE_COUNT = 32
    }
}
