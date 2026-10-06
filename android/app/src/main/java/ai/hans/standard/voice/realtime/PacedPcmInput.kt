package ai.hans.standard.voice.realtime

import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * One public-ADM callback consumer and bounded, copied recorder input. No Android APIs.
 *
 * The pinned ADM's external-input loop does not block on AudioRecord.read, so this callback
 * MUST pace every frame, including startup, underrun and shutdown silence. It never catches
 * up by sending a burst after a scheduling delay. Completion callbacks run on a separate
 * worker: a callback may close the transport without making the native audio thread join itself.
 */
internal class PacedPcmInput(
    private val maxQueuedBytes: Int = MAX_QUEUED_BYTES,
    private val nanoTime: () -> Long = System::nanoTime,
    private val awaitNanos: (Object, Long) -> Unit = { monitor, nanos ->
        monitor.wait(nanos / 1_000_000, (nanos % 1_000_000).toInt())
    },
    private val onFailure: (String) -> Unit = {},
) : AutoCloseable {
    private class Chunk(val bytes: ByteArray, val callback: (Result<Unit>) -> Unit) {
        var position = 0
    }

    private val lock = Object()
    private val chunks = ArrayDeque<Chunk>()
    private val callbacks = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hans-pcm-completion").apply { isDaemon = true }
    }
    private var queuedBytes = 0
    private var outstandingChunks = 0
    private var enabled = false
    private var closed = false
    private var nextFrameAtNanos: Long? = null

    init { require(maxQueuedBytes >= FRAME_BYTES) }

    fun append(pcm: ByteArray, sampleRateHz: Int, callback: (Result<Unit>) -> Unit): Boolean {
        if (sampleRateHz != SAMPLE_RATE_HZ || pcm.isEmpty() || pcm.size > MAX_CHUNK_BYTES ||
            pcm.size % BYTES_PER_SAMPLE != 0
        ) return false
        return synchronized(lock) {
            if (closed || outstandingChunks >= MAX_PENDING_CHUNKS ||
                pcm.size > maxQueuedBytes - queuedBytes
            ) return@synchronized false
            chunks.addLast(Chunk(pcm.copyOf(), callback))
            queuedBytes += pcm.size
            outstandingChunks += 1
            true
        }
    }

    /** Pausing never discards the queued prefix. Only explicit close/failure discards PCM. */
    fun setEnabled(value: Boolean): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        enabled = value
        true
    }

    /** Called only by the ADM audio callback; output is a whole 10-ms, little-endian PCM frame. */
    fun render(buffer: ByteBuffer, audioFormat: Int, channels: Int, sampleRateHz: Int): Long = try {
        renderFrame(buffer, audioFormat, channels, sampleRateHz)
    } catch (_: RuntimeException) {
        // The pinned ADM does NOT catch exceptions thrown by AudioBufferCallback. Never let
        // an unexpected buffer/clock/wait failure silently kill its audio thread with accepted
        // chunks left hanging. The audio thread only schedules failure; native teardown runs
        // on the transport's control worker and therefore never joins this same callback.
        synchronized(lock) {
            try { zeroFrame(buffer) } catch (_: RuntimeException) { Unit }
            failLocked("dictation_pcm_callback_failed")
        }
        // Independent emergency pacing even if the injected/production pacing clock failed.
        // Repeated callback failures while native teardown is pending must not busy-spin.
        try { Thread.sleep(FRAME_NANOS / 1_000_000) } catch (_: InterruptedException) { Unit }
        System.nanoTime()
    }

    private fun renderFrame(buffer: ByteBuffer, audioFormat: Int, channels: Int, sampleRateHz: Int): Long =
        synchronized(lock) {
            // Zero the entire frame even on rejection: never repeat an old PCM tail.
            zeroFrame(buffer)
            val deadline = nextFrameAtNanos ?: (nanoTime() + FRAME_NANOS)
            try {
                var remaining = deadline - nanoTime()
                while (remaining > 0) {
                    awaitNanos(lock, remaining)
                    remaining = deadline - nanoTime()
                }
            } catch (_: InterruptedException) {
                failLocked("dictation_pcm_interrupted")
            }
            val timestamp = nanoTime()
            nextFrameAtNanos = timestamp + FRAME_NANOS
            if (closed) return@synchronized timestamp
            if (audioFormat != PCM_16_BIT || channels != 1 || sampleRateHz != SAMPLE_RATE_HZ ||
                buffer.capacity() != FRAME_BYTES
            ) {
                failLocked("dictation_pcm_format_unsupported")
                return@synchronized timestamp
            }
            if (!enabled) return@synchronized timestamp
            var frameOffset = 0
            while (frameOffset < FRAME_BYTES && chunks.isNotEmpty()) {
                val chunk = chunks.first()
                val count = minOf(FRAME_BYTES - frameOffset, chunk.bytes.size - chunk.position)
                buffer.position(frameOffset)
                buffer.put(chunk.bytes, chunk.position, count)
                chunk.bytes.fill(0, chunk.position, chunk.position + count)
                chunk.position += count
                frameOffset += count
                queuedBytes -= count
                if (chunk.position == chunk.bytes.size) {
                    chunks.removeFirst()
                    completeLocked(chunk, Result.success(Unit))
                }
            }
            buffer.clear()
            timestamp
        }

    private fun zeroFrame(buffer: ByteBuffer) {
        buffer.clear()
        repeat(buffer.capacity()) { buffer.put(0.toByte()) }
        buffer.clear()
    }

    private fun completeLocked(chunk: Chunk, result: Result<Unit>) {
        // Submission and executor shutdown share the same lock, so no accepted callback can
        // race past shutdown or fall back to execution on the native audio thread.
        callbacks.execute {
            synchronized(lock) { outstandingChunks -= 1 }
            try { chunk.callback(result) } catch (_: RuntimeException) { Unit }
        }
    }

    private fun failLocked(code: String) {
        if (closed) return
        closeLocked(code)
        callbacks.execute { try { onFailure(code) } catch (_: RuntimeException) { Unit } }
        callbacks.shutdown()
    }

    private fun closeLocked(code: String) {
        closed = true
        enabled = false
        while (chunks.isNotEmpty()) {
            val chunk = chunks.removeFirst()
            chunk.bytes.fill(0)
            completeLocked(chunk, Result.failure(IllegalStateException(code)))
        }
        queuedBytes = 0
        lock.notifyAll()
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closeLocked("dictation_pcm_closed")
            callbacks.shutdown()
        }
    }

    companion object {
        const val SAMPLE_RATE_HZ = 24_000
        const val PCM_16_BIT = 2 // android.media.AudioFormat.ENCODING_PCM_16BIT; pure JVM helper.
        const val BYTES_PER_SAMPLE = 2
        const val FRAME_BYTES = 480
        const val FRAME_NANOS = 10_000_000L
        const val MAX_CHUNK_BYTES = 48_000
        const val MAX_QUEUED_BYTES = 384_000 // Eight seconds; caller retains larger startup buffers.
        const val MAX_PENDING_CHUNKS = 256 // Includes completion callbacks not yet dispatched.
    }
}
