package ai.hans.standard.voice.android

import ai.hans.standard.voice.AudioCaptureFailure
import ai.hans.standard.voice.FixedPcmChunker
import ai.hans.standard.voice.MonotonicClock
import ai.hans.standard.voice.PcmAudioCapture
import ai.hans.standard.voice.PcmAudioCaptureFactory
import ai.hans.standard.voice.PcmAudioFormat
import ai.hans.standard.voice.RecordingId
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean

class AndroidPcmAudioCaptureFactory(
    private val clock: MonotonicClock,
) : PcmAudioCaptureFactory {
    // DictationRecordingCoordinator checks the runtime grant immediately
    // before this call, and this method still propagates SecurityException if
    // Android revokes the grant between the check and AudioRecord creation.
    @SuppressLint("MissingPermission")
    override fun create(
        recordingId: RecordingId,
        format: PcmAudioFormat,
        fixedChunkBytes: Int,
    ): PcmAudioCapture {
        require(format.channelCount == 1) { "Android dictation currently requires mono PCM" }
        require(format.bitsPerSample == 16) { "Android dictation currently requires PCM 16-bit" }
        require(fixedChunkBytes > 0 && fixedChunkBytes % format.bytesPerFrame == 0)

        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minimumBuffer = AudioRecord.getMinBufferSize(
            format.sampleRateHz,
            channelMask,
            encoding,
        )
        check(minimumBuffer > 0) { "AudioRecord reported no usable input buffer" }
        val requestedBuffer = maxOf(minimumBuffer * 2, fixedChunkBytes * 2)
        val audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(format.sampleRateHz)
                    .setEncoding(encoding)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(requestedBuffer)
            .build()
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            error("AudioRecord did not initialize")
        }
        return AndroidAudioRecordCapture(
            audioRecord = audioRecord,
            clock = clock,
            fixedChunkBytes = fixedChunkBytes,
            readBufferBytes = minOf(fixedChunkBytes, 16 * 1024).coerceAtLeast(format.bytesPerFrame),
        )
    }
}

internal class AndroidAudioRecordCapture(
    private val audioRecord: AudioRecord,
    private val clock: MonotonicClock,
    fixedChunkBytes: Int,
    private val readBufferBytes: Int,
) : PcmAudioCapture {
    private val chunker = FixedPcmChunker(fixedChunkBytes)
    private val started = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    @Volatile
    private var captureThread: Thread? = null

    override fun start(listener: PcmAudioCapture.Listener) {
        check(started.compareAndSet(false, true)) { "Audio capture already started" }
        check(!released.get()) { "Audio capture already closed" }
        val worker = Thread(
            { captureLoop(listener) },
            "hans-dictation-audio-record",
        ).apply { isDaemon = true }
        captureThread = worker
        worker.start()
    }

    override fun requestStop() {
        if (!stopRequested.compareAndSet(false, true)) return
        runCatching {
            if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop()
            }
        }
    }

    override fun close() {
        requestStop()
        val worker = captureThread
        if (worker != null && worker !== Thread.currentThread()) {
            runCatching { worker.join(CLOSE_JOIN_TIMEOUT_MILLIS) }
        }
        releaseAudioRecord()
    }

    private fun captureLoop(listener: PcmAudioCapture.Listener) {
        var failure: AudioCaptureFailure? = null
        try {
            audioRecord.startRecording()
            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                failure = AudioCaptureFailure.START_FAILED
                return
            }
            val readBuffer = ByteArray(readBufferBytes)
            while (!stopRequested.get()) {
                val count = audioRecord.read(
                    readBuffer,
                    0,
                    readBuffer.size,
                    AudioRecord.READ_BLOCKING,
                )
                when {
                    count > 0 -> {
                        chunker.append(readBuffer, length = count).forEach { fixedChunk ->
                            listener.onAudioChunk(fixedChunk, clock.nowMillis())
                        }
                    }
                    count == AudioRecord.ERROR_DEAD_OBJECT -> {
                        if (!stopRequested.get()) failure = AudioCaptureFailure.DEVICE_DISCONNECTED
                        return
                    }
                    count == AudioRecord.ERROR_INVALID_OPERATION ||
                        count == AudioRecord.ERROR_BAD_VALUE -> {
                        if (!stopRequested.get()) failure = AudioCaptureFailure.READ_FAILED
                        return
                    }
                    count < 0 -> {
                        if (!stopRequested.get()) failure = AudioCaptureFailure.READ_FAILED
                        return
                    }
                }
            }
        } catch (_: SecurityException) {
            failure = AudioCaptureFailure.PERMISSION_DENIED
        } catch (_: Exception) {
            if (!stopRequested.get()) failure = AudioCaptureFailure.READ_FAILED
        } finally {
            runCatching {
                if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop()
                }
            }
            if (failure == null) {
                runCatching {
                    listener.onCaptureStopped(chunker.finish(), clock.nowMillis())
                }
            } else {
                runCatching { listener.onCaptureFailure(checkNotNull(failure)) }
            }
            releaseAudioRecord()
        }
    }

    private fun releaseAudioRecord() {
        if (released.compareAndSet(false, true)) {
            runCatching { audioRecord.release() }
        }
    }

    companion object {
        private const val CLOSE_JOIN_TIMEOUT_MILLIS = 2_000L
    }
}
