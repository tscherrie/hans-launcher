package ai.hans.standard.voice.tts.android

import ai.hans.standard.voice.tts.StreamingTtsPlayback
import ai.hans.standard.voice.tts.StreamingTtsPlayer
import ai.hans.standard.voice.tts.TtsAudioChunk
import ai.hans.standard.voice.tts.TtsAudioStreamFormat
import ai.hans.standard.voice.tts.TtsPlayerFailure
import ai.hans.standard.voice.tts.TtsSegmentId
import java.util.ArrayDeque

/**
 * Bounded PCM streaming player. Speech speed is intentionally not changed in
 * AudioTrack: the requested speed is synthesized server-side by the Speech API.
 */
class AndroidStreamingTtsPlayer(
    private val sinkFactory: Pcm16AudioSinkFactory = AndroidAudioTrackSinkFactory,
) : StreamingTtsPlayer {
    override fun open(
        segmentId: TtsSegmentId,
        format: TtsAudioStreamFormat,
        listener: StreamingTtsPlayer.Listener,
    ): StreamingTtsPlayback {
        if (!format.isSupportedPcm()) {
            listener.safeFailure(TtsPlayerFailure(CODE_UNSUPPORTED_FORMAT, false))
            return TerminalPlayback
        }
        val sink = try {
            sinkFactory.create(OpenAiTtsRequest.SAMPLE_RATE_HZ, OpenAiTtsRequest.CHANNEL_COUNT)
        } catch (_: RuntimeException) {
            listener.safeFailure(TtsPlayerFailure(CODE_OUTPUT_UNAVAILABLE, true))
            return TerminalPlayback
        }
        return Playback(sink, listener).also { it.start() }
    }

    private class Playback(
        private val sink: Pcm16AudioSink,
        private val listener: StreamingTtsPlayer.Listener,
    ) : StreamingTtsPlayback {
        private val monitor = Any()
        private val pausedChunks = ArrayDeque<TtsAudioChunk>()
        private var pausedBytes = 0
        private var expectedSequence = 0L
        private var acceptedBytes = 0L
        private var paused = false
        private var inputFinished = false
        private var markerArmed = false
        private var terminal = false
        private var releaseState = ReleaseState.NOT_STARTED

        fun start() {
            try {
                sink.play()
            } catch (_: RuntimeException) {
                fail(TtsPlayerFailure(CODE_OUTPUT_START_FAILED, true))
            }
        }

        override fun write(chunk: TtsAudioChunk) {
            var failure: TtsPlayerFailure? = null
            synchronized(monitor) {
                if (terminal) return
                failure = when {
                    inputFinished -> TtsPlayerFailure(CODE_WRITE_AFTER_FINISH, false)
                    chunk.sequence != expectedSequence -> {
                        TtsPlayerFailure(CODE_CHUNK_OUT_OF_ORDER, false)
                    }
                    chunk.byteCount > MAX_ACCEPTED_CHUNK_BYTES -> {
                        TtsPlayerFailure(CODE_CHUNK_TOO_LARGE, false)
                    }
                    chunk.byteCount % BYTES_PER_FRAME != 0 -> {
                        TtsPlayerFailure(CODE_MALFORMED_PCM, false)
                    }
                    acceptedBytes + chunk.byteCount > MAX_TOTAL_AUDIO_BYTES -> {
                        TtsPlayerFailure(CODE_AUDIO_TOO_LARGE, false)
                    }
                    paused && pausedBytes + chunk.byteCount > MAX_PAUSED_BYTES -> {
                        TtsPlayerFailure(CODE_PAUSED_BACKPRESSURE, true)
                    }
                    else -> null
                }
                if (failure == null) {
                    expectedSequence += 1
                    acceptedBytes += chunk.byteCount
                    if (paused) {
                        pausedChunks.addLast(chunk)
                        pausedBytes += chunk.byteCount
                    } else if (!writeChunkLocked(chunk)) {
                        failure = TtsPlayerFailure(CODE_OUTPUT_WRITE_FAILED, true)
                    }
                }
            }
            failure?.let(::fail)
        }

        override fun finishInput() {
            var failure: TtsPlayerFailure? = null
            var completeImmediately = false
            synchronized(monitor) {
                if (terminal || inputFinished) return
                inputFinished = true
                when {
                    acceptedBytes == 0L -> {
                        failure = TtsPlayerFailure(CODE_EMPTY_AUDIO, true)
                    }
                    acceptedBytes % BYTES_PER_FRAME != 0L -> {
                        failure = TtsPlayerFailure(CODE_MALFORMED_PCM, false)
                    }
                    else -> {
                        val frames = acceptedBytes / BYTES_PER_FRAME
                        if (frames <= 0L || frames > Int.MAX_VALUE.toLong()) {
                            failure = TtsPlayerFailure(CODE_AUDIO_TOO_LARGE, false)
                        } else {
                            markerArmed = true
                            val armed = try {
                                sink.armCompletionMarker(frames.toInt(), ::onMarkerReached)
                            } catch (_: RuntimeException) {
                                false
                            }
                            if (!armed && !terminal) {
                                markerArmed = false
                                failure = TtsPlayerFailure(CODE_DRAIN_UNAVAILABLE, true)
                            } else if (!terminal) {
                                completeImmediately = try {
                                    sink.playedFrames() >= frames
                                } catch (_: RuntimeException) {
                                    false
                                }
                            }
                        }
                    }
                }
            }
            when {
                failure != null -> fail(failure!!)
                completeImmediately -> onMarkerReached()
            }
        }

        override fun pause() {
            var failure: TtsPlayerFailure? = null
            synchronized(monitor) {
                if (terminal || paused) return
                paused = true
                try {
                    sink.pause()
                } catch (_: RuntimeException) {
                    failure = TtsPlayerFailure(CODE_OUTPUT_PAUSE_FAILED, true)
                }
            }
            failure?.let(::fail)
        }

        override fun resume() {
            var failure: TtsPlayerFailure? = null
            synchronized(monitor) {
                if (terminal || !paused) return
                try {
                    sink.play()
                    paused = false
                    while (pausedChunks.isNotEmpty() && failure == null) {
                        val chunk = pausedChunks.removeFirst()
                        pausedBytes -= chunk.byteCount
                        if (!writeChunkLocked(chunk)) {
                            failure = TtsPlayerFailure(CODE_OUTPUT_WRITE_FAILED, true)
                        }
                    }
                } catch (_: RuntimeException) {
                    failure = TtsPlayerFailure(CODE_OUTPUT_RESUME_FAILED, true)
                }
            }
            failure?.let(::fail)
        }

        override fun stop() {
            // A write may own monitor while awaiting its bounded routing acknowledgement.
            // Signal first; actual track stop/release remains serialized below.
            sink.cancelPendingRouteWait()
            val shouldRelease = synchronized(monitor) {
                if (terminal) {
                    if (releaseState != ReleaseState.SUCCEEDED) {
                        error(CODE_OUTPUT_TERMINAL_UNCONFIRMED)
                    }
                    false
                } else {
                    terminal = true
                    pausedChunks.clear()
                    pausedBytes = 0
                    releaseState = ReleaseState.IN_PROGRESS
                    true
                }
            }
            if (!shouldRelease) return
            val released = releaseSink(flush = true)
            synchronized(monitor) {
                releaseState = if (released) ReleaseState.SUCCEEDED else ReleaseState.FAILED
            }
            check(released) { CODE_OUTPUT_TERMINAL_UNCONFIRMED }
        }

        private fun writeChunkLocked(chunk: TtsAudioChunk): Boolean {
            val bytes = chunk.copyBytes()
            var offset = 0
            while (offset < bytes.size) {
                val requested = minOf(MAX_SINK_WRITE_BYTES, bytes.size - offset)
                val written = try {
                    sink.write(bytes, offset, requested)
                } catch (_: RuntimeException) {
                    return false
                }
                if (written <= 0 || written > requested) return false
                offset += written
            }
            return true
        }

        private fun onMarkerReached() {
            val shouldComplete = synchronized(monitor) {
                if (terminal || !inputFinished || !markerArmed) {
                    false
                } else {
                    terminal = true
                    markerArmed = false
                    pausedChunks.clear()
                    pausedBytes = 0
                    true
                }
            }
            if (!shouldComplete) return
            val released = releaseSink(flush = false)
            synchronized(monitor) {
                releaseState = if (released) ReleaseState.SUCCEEDED else ReleaseState.FAILED
            }
            listener.safeCompleted()
        }

        private fun fail(failure: TtsPlayerFailure) {
            val shouldReport = synchronized(monitor) {
                if (terminal) {
                    false
                } else {
                    terminal = true
                    markerArmed = false
                    pausedChunks.clear()
                    pausedBytes = 0
                    true
                }
            }
            if (!shouldReport) return
            val released = releaseSink(flush = true)
            synchronized(monitor) {
                releaseState = if (released) ReleaseState.SUCCEEDED else ReleaseState.FAILED
            }
            listener.safeFailure(failure)
        }

        private fun releaseSink(flush: Boolean): Boolean {
            var succeeded = true
            try {
                sink.stop()
            } catch (_: RuntimeException) {
                succeeded = false
            }
            if (flush) {
                try {
                    sink.flush()
                } catch (_: RuntimeException) {
                    succeeded = false
                }
            }
            try {
                sink.release()
            } catch (_: RuntimeException) {
                succeeded = false
            }
            return succeeded
        }

        private enum class ReleaseState {
            NOT_STARTED,
            IN_PROGRESS,
            SUCCEEDED,
            FAILED,
        }
    }

    private object TerminalPlayback : StreamingTtsPlayback {
        override fun write(chunk: TtsAudioChunk) = Unit
        override fun finishInput() = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun stop() = Unit
    }

    private companion object {
        const val BYTES_PER_FRAME = 2
        const val MAX_SINK_WRITE_BYTES = 8 * 1_024
        const val MAX_ACCEPTED_CHUNK_BYTES = 64 * 1_024
        const val MAX_PAUSED_BYTES = 128 * 1_024
        const val MAX_TOTAL_AUDIO_BYTES = 64L * 1_024L * 1_024L

        const val CODE_UNSUPPORTED_FORMAT = "unsupported_format"
        const val CODE_OUTPUT_UNAVAILABLE = "output_unavailable"
        const val CODE_OUTPUT_START_FAILED = "output_start_failed"
        const val CODE_OUTPUT_TERMINAL_UNCONFIRMED = "output_terminal_unconfirmed"
        const val CODE_WRITE_AFTER_FINISH = "write_after_finish"
        const val CODE_CHUNK_OUT_OF_ORDER = "chunk_out_of_order"
        const val CODE_CHUNK_TOO_LARGE = "chunk_too_large"
        const val CODE_MALFORMED_PCM = "malformed_pcm"
        const val CODE_AUDIO_TOO_LARGE = "audio_too_large"
        const val CODE_PAUSED_BACKPRESSURE = "paused_backpressure"
        const val CODE_OUTPUT_WRITE_FAILED = "output_write_failed"
        const val CODE_EMPTY_AUDIO = "empty_audio"
        const val CODE_DRAIN_UNAVAILABLE = "drain_unavailable"
        const val CODE_OUTPUT_PAUSE_FAILED = "output_pause_failed"
        const val CODE_OUTPUT_RESUME_FAILED = "output_resume_failed"

        fun TtsAudioStreamFormat.isSupportedPcm(): Boolean =
            mimeType.equals(OpenAiTtsRequest.PCM_MIME_TYPE, ignoreCase = true) &&
                sampleRateHz == OpenAiTtsRequest.SAMPLE_RATE_HZ &&
                channelCount == OpenAiTtsRequest.CHANNEL_COUNT

        fun StreamingTtsPlayer.Listener.safeCompleted() {
            try {
                onCompleted()
            } catch (_: RuntimeException) {
                // Consumer callbacks are outside the audio driver's lifecycle.
            }
        }

        fun StreamingTtsPlayer.Listener.safeFailure(failure: TtsPlayerFailure) {
            try {
                onFailure(failure)
            } catch (_: RuntimeException) {
                // Consumer callbacks are outside the audio driver's lifecycle.
            }
        }
    }
}
