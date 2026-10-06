package ai.hans.standard.voice.realtime

/**
 * Bounded PCM16 activity evidence, not speech recognition or physical speaker-drain proof.
 * Runs only when active-call monitoring is enabled and retains no audio. Each call represents
 * one observed PCM frame; missing callbacks are never interpreted as silence. Telephone
 * farewell failures remain sticky. Continuous dictation can explicitly opt into fresh-frame
 * recovery after the previous measurement has first been reported unreliable.
 */
internal class LiveVoiceAudioActivityMeter(
    private val direction: LiveVoiceAudioDirection,
    /** Farewell monitoring begins inside a spoken tail; continuous dictation does not. */
    private val requireInitialInputQuiet: Boolean = true,
    /** Continuous sessions may begin a NEW measurement after an explicitly invalidated one. */
    private val recoverAfterUnreliable: Boolean = false,
) {
    private var lastObservedAtNanos: Long? = null
    private var lastNotificationAtNanos: Long? = null
    private var loudNanos = 0L
    private var quietNanos = 0L
    private var speechActive = false
    private var initialQuietObserved = false
    private var initialInputNonQuietNanos = 0L
    private var monitoringFailed = false
    private var recoveryAfterNanos: Long? = null

    fun reset() {
        lastObservedAtNanos = null
        lastNotificationAtNanos = null
        loudNanos = 0L
        quietNanos = 0L
        speechActive = false
        initialQuietObserved = false
        initialInputNonQuietNanos = 0L
        monitoringFailed = false
        recoveryAfterNanos = null
    }

    fun observe(
        pcm: ByteArray,
        audioFormat: Int,
        channelCount: Int,
        sampleRate: Int,
        observedAtNanos: Long,
    ): LiveVoiceAudioActivity? {
        val frameNanos = validFrameDuration(pcm, audioFormat, channelCount, sampleRate)
        if (monitoringFailed) {
            // Emit one invalidation, not an event per unsupported frame. Only a fresh valid
            // frame may restart continuous monitoring. Neither the gap nor its boundary
            // frame supplies quiet/attack duration to the new measurement.
            if (!recoverAfterUnreliable || frameNanos == null ||
                observedAtNanos <= requireNotNull(recoveryAfterNanos)
            ) return null
            reset()
        }
        val previous = lastObservedAtNanos
        if (previous != null && observedAtNanos <= previous) return unreliable(observedAtNanos)
        if (frameNanos == null) return unreliable(observedAtNanos)
        if (previous != null && observedAtNanos - previous > MAX_CALLBACK_GAP_NANOS) {
            return unreliable(observedAtNanos)
        }
        lastObservedAtNanos = observedAtNanos

        var sumSquares = 0L
        var index = 0
        while (index < pcm.size) {
            val sample = ((pcm[index].toInt() and 0xff) or (pcm[index + 1].toInt() shl 8))
                .toShort().toLong()
            sumSquares += sample * sample
            index += 2
        }
        val meanSquare = sumSquares / (pcm.size / 2)
        val attackLevel = if (direction == LiveVoiceAudioDirection.INPUT) INPUT_ATTACK else OUTPUT_ATTACK
        val quietLevel = attackLevel / 2
        val loud = meanSquare >= attackLevel.toLong() * attackLevel
        val quiet = meanSquare < quietLevel.toLong() * quietLevel
        loudNanos = if (loud) (loudNanos + frameNanos).coerceAtMost(ATTACK_NANOS) else 0L
        quietNanos = if (quiet) (quietNanos + frameNanos).coerceAtMost(QUIET_NANOS) else 0L

        // Ignore the microphone tail of the farewell that armed this monitor. The session
        // requires this quiet evidence before closing. Bound the ignored non-quiet tail:
        // continued/ambiguous input must invalidate the candidate even before a quiet edge.
        if (direction == LiveVoiceAudioDirection.INPUT && requireInitialInputQuiet && !initialQuietObserved) {
            if (!quiet) {
                initialInputNonQuietNanos += frameNanos
                if (initialInputNonQuietNanos >= MAX_INITIAL_INPUT_TAIL_NANOS) {
                    return unreliable(observedAtNanos)
                }
            }
            if (quietNanos < QUIET_NANOS) return null
            initialQuietObserved = true
            loudNanos = 0L
            return activity(false, observedAtNanos)
        }
        if (!speechActive && loudNanos >= ATTACK_NANOS) {
            speechActive = true
            return activity(true, observedAtNanos)
        }
        if (speechActive && quietNanos >= QUIET_NANOS) {
            speechActive = false
            initialQuietObserved = true
            return activity(false, observedAtNanos)
        }
        if (!speechActive && !initialQuietObserved && quietNanos >= QUIET_NANOS) {
            initialQuietObserved = true
            return activity(false, observedAtNanos)
        }
        // At most four steady-state notifications per second, not a hop per frame. Quiet
        // heartbeats prove callbacks are still arriving; a stalled transport cannot look quiet.
        if (speechActive && loud &&
            observedAtNanos - (lastNotificationAtNanos ?: observedAtNanos) >= HEARTBEAT_NANOS
        ) {
            return activity(true, observedAtNanos)
        }
        if (!speechActive && quietNanos >= QUIET_NANOS &&
            observedAtNanos - (lastNotificationAtNanos ?: observedAtNanos) >= HEARTBEAT_NANOS
        ) {
            return activity(false, observedAtNanos)
        }
        return null
    }

    private fun activity(active: Boolean, observedAtNanos: Long): LiveVoiceAudioActivity {
        lastNotificationAtNanos = observedAtNanos
        return LiveVoiceAudioActivity(direction, active, observedAtNanos)
    }

    private fun unreliable(observedAtNanos: Long): LiveVoiceAudioActivity {
        monitoringFailed = true
        recoveryAfterNanos = maxOf(observedAtNanos, lastObservedAtNanos ?: observedAtNanos)
        loudNanos = 0L
        quietNanos = 0L
        return LiveVoiceAudioActivity(direction, speechActive, observedAtNanos, reliable = false)
    }

    private fun validFrameDuration(pcm: ByteArray, format: Int, channels: Int, rate: Int): Long? {
        // Android AudioFormat.ENCODING_PCM_16BIT = 2. No Android dependency in this policy.
        if (format != PCM_16_BIT || channels !in 1..2 || rate !in 8_000..192_000 ||
            pcm.isEmpty() || pcm.size > MAX_FRAME_BYTES || pcm.size % (2 * channels) != 0
        ) return null
        val duration = (pcm.size / (2 * channels)).toLong() * NANOS_PER_SECOND / rate
        return duration.takeIf { it in 1..MAX_FRAME_NANOS }
    }

    private companion object {
        const val PCM_16_BIT = 2
        const val INPUT_ATTACK = 512
        const val OUTPUT_ATTACK = 128
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val ATTACK_NANOS = 40_000_000L
        const val QUIET_NANOS = 300_000_000L
        const val MAX_INITIAL_INPUT_TAIL_NANOS = 200_000_000L
        const val HEARTBEAT_NANOS = 250_000_000L
        const val MAX_CALLBACK_GAP_NANOS = 100_000_000L
        const val MAX_FRAME_NANOS = 100_000_000L
        const val MAX_FRAME_BYTES = 76_800
    }
}
