package ai.hans.standard.voice.tts.android

/** Small seam around AudioTrack so the streaming state machine is JVM-testable. */
interface Pcm16AudioSink {
    /** Thread-safe signal only: no AudioTrack release or microphone changes. */
    fun cancelPendingRouteWait() = Unit
    fun play()

    fun pause()

    /** Returns the accepted byte count, or a non-positive value on failure. */
    fun write(bytes: ByteArray, offset: Int, byteCount: Int): Int

    /** Unsigned playback-head frame count since this sink was created. */
    fun playedFrames(): Long

    /** Arms a one-shot completion callback at the absolute frame position. */
    fun armCompletionMarker(framePosition: Int, onReached: () -> Unit): Boolean

    fun stop()

    fun flush()

    fun release()
}

fun interface Pcm16AudioSinkFactory {
    fun create(sampleRateHz: Int, channelCount: Int): Pcm16AudioSink
}
