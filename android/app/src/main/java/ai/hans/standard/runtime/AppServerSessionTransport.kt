package ai.hans.standard.runtime

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object AppServerSessionContract {
    const val PROTOCOL_VERSION = 1

    // 24 KiB is intentionally far below Binder's shared transaction limit.
    // Callback methods are synchronous, so this is also bounded backpressure.
    const val MAX_BINDER_CHUNK_BYTES = 24 * 1024
    const val MAX_CLIENT_FRAME_BYTES = 1 * 1024 * 1024
    const val MAX_SERVER_FRAME_BYTES = 8 * 1024 * 1024
    const val MAX_CHUNK_COUNT = 512
    const val MAX_DETAIL_CHARACTERS = 192

    const val STATE_STOPPED = 0
    const val STATE_STARTING = 1
    const val STATE_READY = 2
    const val STATE_STOPPING = 3
    const val STATE_EXITED = 4
    const val STATE_FAILED = 5

    const val NOTICE_CLIENT_FRAME_REJECTED = 1
    const val NOTICE_SERVER_FRAME_OVERSIZED = 2
    const val NOTICE_SERVER_FRAME_INVALID = 3
    const val NOTICE_STDOUT_PARTIAL_EOF = 4
    const val NOTICE_STALE_GENERATION = 5
    const val NOTICE_SESSION_NOT_READY = 6

    fun initializeRequestId(generation: Long): String = "hans:initialize:$generation"

    fun assistantProfileRequestId(generation: Long): String = "hans:assistant-profile:$generation"

    fun boundedDetail(detail: String): String {
        val singleLine = detail.replace('\n', ' ').replace('\r', ' ')
        return singleLine.take(MAX_DETAIL_CHARACTERS)
    }
}

internal class SessionEventSequencer {
    private var generation = 0L
    private var eventSequence = 0L

    @Synchronized
    fun nextGeneration(): Long {
        check(generation < Long.MAX_VALUE) { "session generation exhausted" }
        generation += 1
        eventSequence = 0
        return generation
    }

    @Synchronized
    fun nextEvent(expectedGeneration: Long): Long {
        check(expectedGeneration == generation) { "stale session generation" }
        check(eventSequence < Long.MAX_VALUE) { "session event sequence exhausted" }
        eventSequence += 1
        return eventSequence
    }

    @Synchronized
    fun currentGeneration(): Long = generation
}

internal sealed interface JsonLineReadResult {
    data class Frame(val bytes: ByteArray) : JsonLineReadResult
    data class Oversized(val bytesSeen: Long) : JsonLineReadResult
    data class PartialEof(val bytesSeen: Long) : JsonLineReadResult
    data object Eof : JsonLineReadResult
}

internal class BoundedJsonLineFramer(
    private val maximumFrameBytes: Int = AppServerSessionContract.MAX_SERVER_FRAME_BYTES,
) {
    init {
        require(maximumFrameBytes > 0)
    }

    fun readNext(input: InputStream): JsonLineReadResult {
        val output = ByteArrayOutputStream(minOf(maximumFrameBytes, 16_384))
        var bytesSeen = 0L
        var oversized = false
        while (true) {
            val next = input.read()
            if (next < 0) {
                return when {
                    bytesSeen == 0L -> JsonLineReadResult.Eof
                    oversized -> JsonLineReadResult.PartialEof(bytesSeen)
                    else -> JsonLineReadResult.PartialEof(bytesSeen)
                }
            }
            if (next == '\n'.code) {
                if (oversized) return JsonLineReadResult.Oversized(bytesSeen)
                var bytes = output.toByteArray()
                if (bytes.lastOrNull() == '\r'.code.toByte()) {
                    bytes = bytes.copyOf(bytes.size - 1)
                }
                return JsonLineReadResult.Frame(bytes)
            }
            bytesSeen = if (bytesSeen == Long.MAX_VALUE) Long.MAX_VALUE else bytesSeen + 1
            if (!oversized) {
                if (output.size() >= maximumFrameBytes) {
                    oversized = true
                } else {
                    output.write(next)
                }
            }
        }
    }
}

internal sealed interface StdoutLoopTermination {
    data object Stopped : StdoutLoopTermination
    data object Eof : StdoutLoopTermination
    data class PartialEof(val bytesSeen: Long) : StdoutLoopTermination
    data object Failure : StdoutLoopTermination
}

internal class AppServerStdoutLoop(
    private val framer: BoundedJsonLineFramer = BoundedJsonLineFramer(),
) {
    fun run(
        input: InputStream,
        shouldStop: () -> Boolean,
        onFrame: (ByteArray) -> Unit,
        onOversized: (Long) -> Unit,
    ): StdoutLoopTermination {
        return try {
            while (!shouldStop()) {
                when (val result = framer.readNext(input)) {
                    is JsonLineReadResult.Frame -> onFrame(result.bytes)
                    is JsonLineReadResult.Oversized -> onOversized(result.bytesSeen)
                    is JsonLineReadResult.PartialEof ->
                        return StdoutLoopTermination.PartialEof(result.bytesSeen)
                    JsonLineReadResult.Eof -> return StdoutLoopTermination.Eof
                }
            }
            StdoutLoopTermination.Stopped
        } catch (_: Exception) {
            StdoutLoopTermination.Failure
        }
    }
}

internal object StdoutTerminationRouter {
    fun route(
        termination: StdoutLoopTermination,
        sessionStopping: Boolean,
        onPartialEof: () -> Unit,
        onExited: () -> Unit,
        onFailure: () -> Unit,
    ) {
        if (sessionStopping) return
        when (termination) {
            StdoutLoopTermination.Stopped -> Unit
            StdoutLoopTermination.Eof -> onExited()
            is StdoutLoopTermination.PartialEof -> {
                onPartialEof()
                onExited()
            }
            StdoutLoopTermination.Failure -> onFailure()
        }
    }
}

internal class PreReadyFrameBuffer(
    private val maximumBytes: Int = 256 * 1024,
    private val maximumFrames: Int = 32,
) {
    private val frames = ArrayDeque<ByteArray>()
    private var bytes = 0
    var droppedFrame = false
        private set

    init {
        require(maximumBytes > 0)
        require(maximumFrames > 0)
    }

    fun hold(frame: ByteArray) {
        if (
            frame.isEmpty() ||
            frame.size > maximumBytes ||
            frames.size >= maximumFrames ||
            bytes > maximumBytes - frame.size
        ) {
            droppedFrame = true
            return
        }
        frames.addLast(frame.copyOf())
        bytes += frame.size
    }

    fun drain(): List<ByteArray> {
        val result = frames.toList()
        frames.clear()
        bytes = 0
        return result
    }

    fun releaseAfterReady(
        onReady: () -> Unit,
        onFrame: (ByteArray) -> Unit,
        onDroppedFrame: () -> Unit,
    ) {
        val bufferedFrames = drain()
        val hadDroppedFrame = droppedFrame
        droppedFrame = false
        onReady()
        bufferedFrames.forEach(onFrame)
        if (hadDroppedFrame) onDroppedFrame()
    }

    fun clear() {
        frames.clear()
        bytes = 0
        droppedFrame = false
    }
}

internal object JsonFrameValidator {
    fun decodeObject(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            return null
        }
        if ('\n' in text || '\r' in text) return null
        return try {
            JSONObject(text)
            text
        } catch (_: Exception) {
            null
        }
    }

    fun hasResponseId(bytes: ByteArray, expectedId: String): Boolean {
        val text = decodeObject(bytes) ?: return false
        return try {
            JSONObject(text).optString("id", "") == expectedId
        } catch (_: Exception) {
            false
        }
    }
}

internal data class BinderFrameChunk(
    val index: Int,
    val count: Int,
    val totalBytes: Int,
    val payload: ByteArray,
)

internal object BinderFrameChunker {
    fun chunks(
        frame: ByteArray,
        maximumChunkBytes: Int = AppServerSessionContract.MAX_BINDER_CHUNK_BYTES,
    ): List<BinderFrameChunk> {
        require(frame.isNotEmpty()) { "frame must not be empty" }
        require(maximumChunkBytes > 0)
        val count = (frame.size + maximumChunkBytes - 1) / maximumChunkBytes
        require(count <= AppServerSessionContract.MAX_CHUNK_COUNT) { "frame needs too many chunks" }
        return List(count) { index ->
            val start = index * maximumChunkBytes
            val end = minOf(frame.size, start + maximumChunkBytes)
            BinderFrameChunk(
                index = index,
                count = count,
                totalBytes = frame.size,
                payload = frame.copyOfRange(start, end),
            )
        }
    }
}

internal sealed interface FrameAssemblyResult {
    data object Incomplete : FrameAssemblyResult
    data class Complete(val clientSequence: Long, val bytes: ByteArray) : FrameAssemblyResult
    data class Rejected(
        val relatedSequence: Long,
        val detail: String,
    ) : FrameAssemblyResult
}

internal class OrderedClientFrameAssembler(
    private val maximumChunkBytes: Int = AppServerSessionContract.MAX_BINDER_CHUNK_BYTES,
    private val maximumFrameBytes: Int = AppServerSessionContract.MAX_CLIENT_FRAME_BYTES,
    private val maximumChunkCount: Int = AppServerSessionContract.MAX_CHUNK_COUNT,
) {
    private var nextClientSequence = 1L
    private var pending: PendingFrame? = null

    @Synchronized
    fun accept(
        clientSequence: Long,
        chunkIndex: Int,
        chunkCount: Int,
        totalBytes: Int,
        payload: ByteArray,
    ): FrameAssemblyResult {
        fun reject(detail: String): FrameAssemblyResult.Rejected {
            pending = null
            return FrameAssemblyResult.Rejected(
                relatedSequence = clientSequence,
                detail = AppServerSessionContract.boundedDetail(detail),
            )
        }

        if (clientSequence != nextClientSequence) {
            return reject("Expected client sequence $nextClientSequence")
        }
        if (totalBytes <= 0 || totalBytes > maximumFrameBytes) {
            return reject("Client frame size is outside the allowed range")
        }
        if (payload.isEmpty() || payload.size > maximumChunkBytes) {
            return reject("Client chunk size is outside the allowed range")
        }
        val expectedChunkCount = (totalBytes + maximumChunkBytes - 1) / maximumChunkBytes
        if (chunkCount != expectedChunkCount || chunkCount !in 1..maximumChunkCount) {
            return reject("Client chunk count does not match frame size")
        }
        if (chunkIndex !in 0 until chunkCount) {
            return reject("Client chunk index is outside the frame")
        }
        val expectedPayloadBytes = if (chunkIndex == chunkCount - 1) {
            totalBytes - (chunkCount - 1) * maximumChunkBytes
        } else {
            maximumChunkBytes
        }
        if (payload.size != expectedPayloadBytes) {
            return reject("Client chunk byte count does not match its index")
        }

        val current = pending
        val frame = if (current == null) {
            if (chunkIndex != 0) return reject("Client frame must start with chunk zero")
            PendingFrame(
                clientSequence = clientSequence,
                chunkCount = chunkCount,
                totalBytes = totalBytes,
                nextChunkIndex = 0,
                output = ByteArrayOutputStream(totalBytes),
            ).also { pending = it }
        } else {
            if (
                current.clientSequence != clientSequence ||
                current.chunkCount != chunkCount ||
                current.totalBytes != totalBytes
            ) {
                return reject("Client frame metadata changed during reassembly")
            }
            current
        }
        if (chunkIndex != frame.nextChunkIndex) {
            return reject("Client chunks arrived out of order")
        }

        frame.output.write(payload)
        frame.nextChunkIndex += 1
        if (frame.nextChunkIndex < frame.chunkCount) return FrameAssemblyResult.Incomplete

        val completed = frame.output.toByteArray()
        pending = null
        if (completed.size != frame.totalBytes || JsonFrameValidator.decodeObject(completed) == null) {
            return FrameAssemblyResult.Rejected(
                relatedSequence = clientSequence,
                detail = "Client frame is not one valid UTF-8 JSON object",
            )
        }
        nextClientSequence += 1
        return FrameAssemblyResult.Complete(clientSequence, completed)
    }

    @Synchronized
    fun nextExpectedClientSequence(): Long = nextClientSequence

    private data class PendingFrame(
        val clientSequence: Long,
        val chunkCount: Int,
        val totalBytes: Int,
        var nextChunkIndex: Int,
        val output: ByteArrayOutputStream,
    )
}
