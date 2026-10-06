package ai.hans.standard.voice.realtime

import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * Lossless, volatile early-microphone prefix for a physically paced ADM callback.
 *
 * Capture and transmission are separate gates. Before readiness, valid physical frames are
 * retained while native receives zeroes. Afterwards exactly one old frame replaces each new
 * frame, with its original timestamp. Explicit mute admits no more physical frames but drains
 * the already accepted prefix. No accepted samples are resampled, accelerated or dropped.
 * Consequently the initial backlog remains as input latency while capture continues. This is
 * intentional: silence classification must not silently erase a quiet instruction.
 *
 * The physical AudioRecord read already paces this callback; this class must NEVER sleep.
 * It retains at most [maxFrames] ten-millisecond frames and fails closed on overflow/format
 * change. No PCM reaches diagnostics, disk, observers or another session.
 */
internal class StartupPcmBuffer(
    private val maxFrames: Int = MAX_FRAMES,
    private val onFailure: (String) -> Unit = {},
    private val onDrained: () -> Unit = {},
) : AutoCloseable {
    private data class Frame(val bytes: ByteArray, val timestampNanos: Long)

    private val lock = Any()
    private val frames = ArrayDeque<Frame>()
    private var closed = false
    private var capturing = true
    private var transmitting = false
    private var activationFrames = 0
    private var capturedFrames = 0L
    private var emittedFrames = 0L
    private var drainNotificationArmed = false
    private var awaitEmissionReceipt = false

    init { require(maxFrames > ACTIVATION_FRAMES) }

    val hasCapturedFrame: Boolean get() = synchronized(lock) { capturedFrames > 0 }
    val isOpen: Boolean get() = synchronized(lock) { !closed }
    val acceptsPhysicalFrames: Boolean get() = synchronized(lock) { !closed && capturing }
    val hasPendingDrain: Boolean get() = synchronized(lock) { frames.isNotEmpty() || awaitEmissionReceipt }
    val queuedMillis: Long get() = synchronized(lock) {
        (frames.size + (if (!capturing && awaitEmissionReceipt) 1 else 0)) * FRAME_MILLIS
    }

    /** Atomic admission cutoff. Mute never revokes speech accepted before this boundary. */
    fun setCaptureEnabled(enabled: Boolean): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        if (capturing == enabled) return@synchronized true
        capturing = enabled
        if (enabled) {
            drainNotificationArmed = false
        } else {
            drainNotificationArmed = frames.isNotEmpty() || awaitEmissionReceipt
        }
        true
    }

    /** Call only after native recording/track admission and session/media readiness are proven. */
    fun setTransmissionEnabled(enabled: Boolean): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        if (enabled && !transmitting) {
            // A callback can have sampled nativeCalledInitRecording before its blocking read.
            // Retain two transition frames, ensuring the loop has observed native admission
            // before the first preserved speech frame is consumed.
            activationFrames = ACTIVATION_FRAMES
        }
        transmitting = enabled
        true
    }

    fun render(
        buffer: ByteBuffer,
        audioFormat: Int,
        channels: Int,
        sampleRateHz: Int,
        bytesRead: Int,
        capturedAtNanos: Long,
    ): Long {
        var failure: String? = null
        var drained = false
        val result = try {
            synchronized(lock) {
                if (closed) {
                    zero(buffer)
                    return@synchronized capturedAtNanos
                }
                if (audioFormat != PCM_16_BIT || channels != 1 || sampleRateHz != SAMPLE_RATE_HZ ||
                    bytesRead != FRAME_BYTES || buffer.capacity() != FRAME_BYTES || buffer.isReadOnly
                ) {
                    failure = "action_voice_pcm_format_unsupported"
                    closeLocked()
                    zero(buffer)
                    return@synchronized capturedAtNanos
                }
                if (!capturing && drainNotificationArmed && awaitEmissionReceipt && frames.isEmpty()) {
                    // Observe the next ADM iteration before this drain disables native input.
                    // Stopping from the final prefix-copy callback could skip its JNI handoff.
                    // This is a LOCAL callback boundary, not an RTP/server-consumption receipt.
                    drainNotificationArmed = false
                    drained = true
                }
                awaitEmissionReceipt = false
                if (capturing) {
                    if (frames.size >= maxFrames) {
                        failure = "action_voice_startup_buffer_full"
                        closeLocked()
                        zero(buffer)
                        return@synchronized capturedAtNanos
                    }
                    val bytes = ByteArray(FRAME_BYTES)
                    buffer.clear()
                    buffer.get(bytes)
                    frames.addLast(Frame(bytes, capturedAtNanos))
                    capturedFrames += 1
                }
                // Every post-mute physical frame is overwritten, never retained or replayed.
                zero(buffer)
                if (!transmitting || activationFrames > 0) {
                    if (transmitting) activationFrames -= 1
                    return@synchronized capturedAtNanos
                }
                val frame = frames.pollFirst() ?: return@synchronized capturedAtNanos
                buffer.put(frame.bytes)
                buffer.clear()
                frame.bytes.fill(0)
                emittedFrames += 1
                awaitEmissionReceipt = true
                frame.timestampNanos
            }
        } catch (_: RuntimeException) {
            synchronized(lock) { closeLocked() }
            runCatching { zero(buffer) }
            failure = "action_voice_pcm_callback_failed"
            capturedAtNanos
        }
        // Never call transport cleanup while holding the buffer monitor or throw through ADM.
        failure?.let { code -> runCatching { onFailure(code) } }
        if (drained && failure == null) runCatching { onDrained() }
        return result
    }

    internal fun counts(): Pair<Long, Long> = synchronized(lock) { capturedFrames to emittedFrames }

    override fun close() = synchronized(lock) { closeLocked() }

    private fun closeLocked() {
        closed = true
        capturing = false
        transmitting = false
        activationFrames = 0
        drainNotificationArmed = false
        awaitEmissionReceipt = false
        clearLocked()
    }

    private fun clearLocked() {
        frames.forEach { it.bytes.fill(0) }
        frames.clear()
    }

    private fun zero(buffer: ByteBuffer) {
        buffer.clear()
        repeat(buffer.capacity()) { buffer.put(0.toByte()) }
        buffer.clear()
    }

    companion object {
        const val SAMPLE_RATE_HZ = 48_000
        const val PCM_16_BIT = 2
        const val FRAME_BYTES = 960
        const val FRAME_MILLIS = 10L
        const val MAX_FRAMES = 3_000 // Thirty seconds / 2.88 MB PCM, bounded even on network stalls.
        const val ACTIVATION_FRAMES = 2
    }
}

/** Pure separation of user capture consent, prefix delivery and independent speaker output. */
internal data class EarlyCapturePipelineState(
    val capturePhysicalFrames: Boolean,
    val keepRecorderRunning: Boolean,
    val sendNativeInput: Boolean,
    val playOutput: Boolean,
)

internal object EarlyCapturePipelinePolicy {
    fun resolve(muted: Boolean, pendingPrefix: Boolean, ready: Boolean,
                contextEnabled: Boolean, ended: Boolean): EarlyCapturePipelineState {
        val capture = !muted && !ended
        val keepRecorder = !ended && (capture || pendingPrefix)
        return EarlyCapturePipelineState(
            capturePhysicalFrames = capture,
            keepRecorderRunning = keepRecorder,
            sendNativeInput = keepRecorder && ready && contextEnabled,
            playOutput = ready && !ended,
        )
    }
}
