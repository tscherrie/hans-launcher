package ai.hans.standard.voice

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@JvmInline
value class RecordingId(val value: Long) {
    init {
        require(value > 0) { "recording id must be positive" }
    }
}

data class PcmAudioFormat(
    val sampleRateHz: Int = 16_000,
    val channelCount: Int = 1,
    val bitsPerSample: Int = 16,
) {
    init {
        require(sampleRateHz > 0)
        require(channelCount > 0)
        require(bitsPerSample > 0 && bitsPerSample % 8 == 0)
    }

    val bytesPerFrame: Int = channelCount * (bitsPerSample / 8)

    fun bytesForDuration(durationMillis: Long): Int {
        require(durationMillis > 0)
        val bytes = sampleRateHz.toLong() * bytesPerFrame * durationMillis / 1_000L
        require(bytes in bytesPerFrame.toLong()..Int.MAX_VALUE.toLong()) {
            "PCM chunk size is outside the supported range"
        }
        require(bytes % bytesPerFrame == 0L) { "PCM chunk must end on a complete frame" }
        return bytes.toInt()
    }
}

data class DictationRecordingConfig(
    val audioFormat: PcmAudioFormat = PcmAudioFormat(),
    val chunkDurationMillis: Long = 1_000L,
    val maximumDurationMillis: Long = HARD_MAXIMUM_DURATION_MILLIS,
    /**
     * Abort an accidentally left-open recording when neither local speech
     * activity nor incremental STT progress has been observed for this long.
     * This is independent from [maximumDurationMillis]: active dictation may
     * still run for the full one-hour hard limit.
     */
    val progressInactivityTimeoutMillis: Long = DEFAULT_PROGRESS_INACTIVITY_TIMEOUT_MILLIS,
) {
    init {
        require(chunkDurationMillis in 100L..5_000L)
        require(maximumDurationMillis in chunkDurationMillis..HARD_MAXIMUM_DURATION_MILLIS)
        require(progressInactivityTimeoutMillis in 1_000L..HARD_MAXIMUM_DURATION_MILLIS)
    }

    val chunkBytes: Int = audioFormat.bytesForDuration(chunkDurationMillis)

    companion object {
        const val HARD_MAXIMUM_DURATION_MILLIS = 60L * 60L * 1_000L
        const val DEFAULT_PROGRESS_INACTIVITY_TIMEOUT_MILLIS = 60_000L
    }
}

class PcmAudioChunk private constructor(
    val recordingId: RecordingId,
    val index: Long,
    pcmBytes: ByteArray,
    val isFinal: Boolean,
    val capturedAtMillis: Long,
) {
    private val bytes = pcmBytes.copyOf()

    init {
        require(index >= 0)
        require(capturedAtMillis >= 0)
        require(isFinal || bytes.isNotEmpty()) { "only the final audio marker may be empty" }
    }

    val byteCount: Int
        get() = bytes.size

    fun copyBytes(): ByteArray = bytes.copyOf()

    companion object {
        fun create(
            recordingId: RecordingId,
            index: Long,
            pcmBytes: ByteArray,
            isFinal: Boolean,
            capturedAtMillis: Long,
        ): PcmAudioChunk = PcmAudioChunk(
            recordingId = recordingId,
            index = index,
            pcmBytes = pcmBytes,
            isFinal = isFinal,
            capturedAtMillis = capturedAtMillis,
        )
    }
}

fun interface MonotonicClock {
    fun nowMillis(): Long
}

fun interface RecordingTaskDispatcher {
    fun dispatch(task: () -> Unit)
}

class ExecutorRecordingTaskDispatcher : RecordingTaskDispatcher, AutoCloseable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hans-dictation-events").apply { isDaemon = true }
    }

    override fun dispatch(task: () -> Unit) {
        try {
            executor.execute(task)
        } catch (failure: RejectedExecutionException) {
            // Capture/STT callbacks can outlive the service. A closed owner must not
            // accept new work, but a late callback must not crash the shared process.
            if (!executor.isShutdown) throw failure
        }
    }

    override fun close() {
        // In particular, retain the already queued microphone teardown. Interrupting
        // the worker and discarding its queue can otherwise leak a live AudioRecord.
        executor.shutdown()
    }
}

fun interface ScheduledRecordingDeadline {
    fun cancel()
}

fun interface RecordingDeadlineScheduler {
    fun schedule(delayMillis: Long, task: () -> Unit): ScheduledRecordingDeadline
}

class ExecutorRecordingDeadlineScheduler : RecordingDeadlineScheduler, AutoCloseable {
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "hans-dictation-deadline").apply { isDaemon = true }
        }

    override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledRecordingDeadline {
        require(delayMillis >= 0)
        val future = executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
        return ScheduledRecordingDeadline { future.cancel(false) }
    }

    override fun close() {
        executor.shutdownNow()
    }
}

fun interface RecordAudioPermissionChecker {
    fun isRecordAudioGranted(): Boolean
}

/**
 * Irreversible audio-boundary admission executed immediately before microphone capture.
 * Network-only transcription preparation may start earlier; no microphone/audio bypass is allowed.
 * Production returns true only after all Hans speech has been synchronously stopped and queued
 * pre-stop speech can no longer start. A timeout, exception or negative acknowledgement must fail
 * the recording start closed.
 */
fun interface DictationCaptureStartBarrier {
    fun awaitCaptureReady(): Boolean
}

enum class AudioFocusRequestResult {
    GRANTED,
    DENIED,
}

enum class RecordingAudioFocusChange {
    GAINED,
    LOST_TRANSIENT,
    LOST_TRANSIENT_CAN_DUCK,
    LOST_PERMANENTLY,
}

interface RecordingAudioFocusCoordinator {
    fun request(onChange: (RecordingAudioFocusChange) -> Unit): AudioFocusRequestResult

    fun abandon()
}

sealed interface VoiceEnvironmentEvent {
    data class AppForegroundChanged(val packageName: String?) : VoiceEnvironmentEvent

    data class RuntimeActivityChanged(val active: Boolean) : VoiceEnvironmentEvent

    data class TextToSpeechActivityChanged(val active: Boolean) : VoiceEnvironmentEvent
}

interface PcmAudioCapture : AutoCloseable {
    fun start(listener: Listener)

    fun requestStop()

    interface Listener {
        fun onAudioChunk(pcmBytes: ByteArray, capturedAtMillis: Long)

        fun onCaptureStopped(finalPcmBytes: ByteArray, capturedAtMillis: Long)

        fun onCaptureFailure(failure: AudioCaptureFailure)
    }
}

fun interface PcmAudioCaptureFactory {
    fun create(
        recordingId: RecordingId,
        format: PcmAudioFormat,
        fixedChunkBytes: Int,
    ): PcmAudioCapture
}

enum class AudioCaptureFailure {
    PERMISSION_DENIED,
    INITIALIZATION_FAILED,
    START_FAILED,
    READ_FAILED,
    DEVICE_DISCONNECTED,
}

interface IncrementalSttProvider {
    fun openSession(
        recordingId: RecordingId,
        format: PcmAudioFormat,
        progressListener: RecordingProgressListener = RecordingProgressListener.NONE,
    ): IncrementalSttSession
}

/** Content-free evidence that an active recording is still producing useful work. */
enum class RecordingProgress {
    /** The provider transport is ready to accept incremental audio. */
    TRANSCRIPTION_SESSION_READY,

    /** The transcription provider emitted non-blank text progress. */
    TRANSCRIPT_DELTA,

    /** The local PCM stream contains sustained speech-like audio. */
    LOCAL_SPEECH_ACTIVITY,
}

fun interface RecordingProgressListener {
    fun onProgress(progress: RecordingProgress)

    companion object {
        val NONE = RecordingProgressListener {}
    }
}

interface IncrementalSttSession {
    /**
     * Accepts ordered fixed PCM chunks. Implementations may transcribe every
     * chunk immediately, but must not expose a user message from partial text.
     */
    fun submitChunk(chunk: PcmAudioChunk, callback: (Result<Unit>) -> Unit)

    /** Called once, and only after every submitted chunk including the final marker was accepted. */
    fun finish(callback: (Result<String>) -> Unit)

    fun cancel()
}

interface DictationRecordingListener {
    fun onRecordingStateChanged(state: RecordingState)

    /** The only API that releases a complete dictation to the conversation layer. */
    fun onUserMessageReady(recordingId: RecordingId, transcript: String)

    fun onStartRejected(activeRecordingId: RecordingId)
}

internal class RecordingIdGenerator {
    private val next = AtomicLong(0L)

    fun next(): RecordingId {
        val value = next.incrementAndGet()
        check(value > 0) { "recording id exhausted" }
        return RecordingId(value)
    }
}
