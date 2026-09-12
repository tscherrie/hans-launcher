package ai.hans.standard.voice.tts.android

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.atomic.AtomicBoolean
import ai.hans.standard.voice.audio.AndroidSpeechAudioRouteController
import ai.hans.standard.voice.audio.SpeechAudioTrackRouteRegistration
import ai.hans.standard.voice.audio.SpeechPlaybackFrameOffset

/** Production sink for raw 24 kHz, mono, signed PCM16 little-endian audio. */
object AndroidAudioTrackSinkFactory : Pcm16AudioSinkFactory {
    override fun create(sampleRateHz: Int, channelCount: Int): Pcm16AudioSink =
        createRouted(sampleRateHz, channelCount, null)

    internal fun createRouted(sampleRateHz: Int, channelCount: Int, routes: AndroidSpeechAudioRouteController?): Pcm16AudioSink {
        require(sampleRateHz == OpenAiTtsRequest.SAMPLE_RATE_HZ) { "unsupported_sample_rate" }
        require(channelCount == OpenAiTtsRequest.CHANNEL_COUNT) { "unsupported_channel_count" }

        val channelMask = AudioFormat.CHANNEL_OUT_MONO
        val minimum = AudioTrack.getMinBufferSize(
            sampleRateHz,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "audio_track_buffer_unavailable" }
        val bufferBytes = maxOf(minimum, TARGET_BUFFER_BYTES)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            error("audio_track_initialization_failed")
        }
        return AndroidAudioTrackSink(track, routes)
    }

    private const val TARGET_BUFFER_BYTES = 4 * 1_024
}

class AndroidRoutedAudioTrackSinkFactory(private val routes: AndroidSpeechAudioRouteController) : Pcm16AudioSinkFactory {
    override fun create(sampleRateHz: Int, channelCount: Int): Pcm16AudioSink =
        AndroidAudioTrackSinkFactory.createRouted(sampleRateHz, channelCount, routes)
}

private class AndroidAudioTrackSink(
    private val track: AudioTrack,
    private val routes: AndroidSpeechAudioRouteController?,
) : Pcm16AudioSink {
    private val released = AtomicBoolean(false)
    private val markerDelivered = AtomicBoolean(false)
    private val markerThread = HandlerThread("hans-tts-audio-marker").apply { start() }
    private val markerHandler = Handler(markerThread.looper)

    @Volatile
    private var markerCallback: (() -> Unit)? = null
    @Volatile private var routeRegistration: SpeechAudioTrackRouteRegistration? = null
    private val frameOffset = SpeechPlaybackFrameOffset()

    init {
        track.setPlaybackPositionUpdateListener(
            object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(audioTrack: AudioTrack?) {
                    deliverMarker()
                }

                override fun onPeriodicNotification(audioTrack: AudioTrack?) = Unit
            },
            markerHandler,
        )
    }

    override fun play() {
        if (routeRegistration == null) routeRegistration = routes?.attachTrack(track)
        track.play()
    }

    override fun pause() {
        track.pause()
        releaseRoute()
    }

    override fun write(bytes: ByteArray, offset: Int, byteCount: Int): Int {
        routeRegistration?.prepareBeforeWrite {
                val frames = track.startThresholdInFrames
                check(frames in 1..MAX_SILENT_PREROLL_FRAMES) { "speech_route_preroll_bound" }
                val silence = ByteArray(frames * 2)
                val written = track.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING)
                check(written == silence.size) { "speech_route_preroll_failed" }
                frameOffset.addSilence(frames)
        }
        return track.write(bytes, offset, byteCount, AudioTrack.WRITE_BLOCKING)
    }

    override fun playedFrames(): Long = frameOffset.speechFrames(Integer.toUnsignedLong(track.playbackHeadPosition))

    override fun armCompletionMarker(
        framePosition: Int,
        onReached: () -> Unit,
    ): Boolean {
        if (released.get() || framePosition <= 0) return false
        markerCallback = onReached
        markerDelivered.set(false)
        // AudioTrack documents zero as SUCCESS, but that symbolic constant is
        // not exposed by every public Android SDK stub we support.
        val markerPosition = frameOffset.marker(framePosition) ?: return false
        val armed = track.setNotificationMarkerPosition(markerPosition) == AUDIO_STATUS_SUCCESS
        if (!armed) markerCallback = null
        return armed
    }

    override fun stop() {
        releaseRoute()
        track.stop()
    }

    override fun cancelPendingRouteWait() { routeRegistration?.cancelPendingWait() }

    override fun flush() {
        track.flush()
    }

    override fun release() {
        cancelPendingRouteWait()
        if (!released.compareAndSet(false, true)) return
        markerCallback = null
        releaseRoute()
        track.setPlaybackPositionUpdateListener(null)
        track.release()
        markerThread.quitSafely()
    }

    private fun deliverMarker() {
        if (!markerDelivered.compareAndSet(false, true)) return
        val callback = markerCallback
        markerCallback = null
        callback?.invoke()
    }

    private fun releaseRoute() {
        val previous = routeRegistration
        routeRegistration = null
        runCatching { previous?.close() }
    }

    private companion object {
        const val AUDIO_STATUS_SUCCESS = 0
        const val MAX_SILENT_PREROLL_FRAMES = 32_768
    }
}
