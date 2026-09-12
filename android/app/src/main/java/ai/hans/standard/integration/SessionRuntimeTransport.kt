package ai.hans.standard.integration

import ai.hans.standard.runtime.AppServerSessionContract
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/** Android-free seam around the isolated runtime process. */
interface SessionRuntimeTransport {
    val protocolVersion: Int

    fun start(operationId: Long, listener: RuntimeSessionListener)
    fun restart(operationId: Long, expectedGeneration: Long)
    fun sendFrame(generation: Long, frame: ByteArray)
    fun stop(operationId: Long, expectedGeneration: Long)
}

interface RuntimeSessionListener {
    fun onSessionState(
        operationId: Long,
        generation: Long,
        eventSequence: Long,
        state: Int,
    )

    fun onServerFrame(
        generation: Long,
        eventSequence: Long,
        frame: ByteArray,
    )

    fun onTransportNotice(
        generation: Long,
        eventSequence: Long,
        code: Int,
        relatedSequence: Long,
    )

    fun onTransportProtocolFailure(failure: TransportProtocolFailure)
}

enum class TransportProtocolFailure {
    STALE_GENERATION,
    OUT_OF_ORDER_DELIVERY,
    INVALID_CHUNK_METADATA,
    OUT_OF_ORDER_CHUNK,
    OVERSIZED_FRAME,
    INVALID_FRAME,
}

internal sealed interface ServerChunkAssemblyResult {
    data object Incomplete : ServerChunkAssemblyResult

    data class Complete(
        val generation: Long,
        val eventSequence: Long,
        val frame: ByteArray,
    ) : ServerChunkAssemblyResult

    data class Rejected(val failure: TransportProtocolFailure) : ServerChunkAssemblyResult
}

/**
 * Reassembles Binder chunks while validating the one global callback order.
 * Runtime state, notices and complete frames all consume exactly one sequence;
 * every chunk belonging to a frame carries that frame's same sequence.
 */
internal class OrderedServerDeliveryAssembler(
    private val maximumChunkBytes: Int = AppServerSessionContract.MAX_BINDER_CHUNK_BYTES,
    private val maximumFrameBytes: Int = AppServerSessionContract.MAX_SERVER_FRAME_BYTES,
    private val maximumChunkCount: Int = AppServerSessionContract.MAX_CHUNK_COUNT,
) {
    private var generation: Long? = null
    private var lastEventSequence: Long? = null
    private var pending: PendingFrame? = null
    private var rejectedGeneration: Long? = null

    init {
        require(maximumChunkBytes > 0)
        require(maximumFrameBytes > 0)
        require(maximumChunkCount > 0)
    }

    @Synchronized
    fun acceptSingle(generation: Long, eventSequence: Long): TransportProtocolFailure? {
        prepareGeneration(generation, eventSequence)?.let { return it }
        if (pending != null) return reject(TransportProtocolFailure.OUT_OF_ORDER_DELIVERY)
        return acceptNextSequence(eventSequence)
    }

    @Synchronized
    fun acceptChunk(
        generation: Long,
        eventSequence: Long,
        chunkIndex: Int,
        chunkCount: Int,
        totalBytes: Int,
        payload: ByteArray,
    ): ServerChunkAssemblyResult {
        prepareGeneration(generation, eventSequence)?.let {
            return ServerChunkAssemblyResult.Rejected(it)
        }
        if (rejectedGeneration == generation) {
            return ServerChunkAssemblyResult.Rejected(
                TransportProtocolFailure.OUT_OF_ORDER_DELIVERY,
            )
        }
        if (totalBytes <= 0 || totalBytes > maximumFrameBytes) {
            return rejected(TransportProtocolFailure.OVERSIZED_FRAME)
        }
        if (payload.isEmpty() || payload.size > maximumChunkBytes) {
            return rejected(TransportProtocolFailure.INVALID_CHUNK_METADATA)
        }
        val expectedCount = (totalBytes + maximumChunkBytes - 1) / maximumChunkBytes
        if (chunkCount != expectedCount || chunkCount !in 1..maximumChunkCount) {
            return rejected(TransportProtocolFailure.INVALID_CHUNK_METADATA)
        }
        if (chunkIndex !in 0 until chunkCount) {
            return rejected(TransportProtocolFailure.INVALID_CHUNK_METADATA)
        }
        val expectedPayloadBytes = if (chunkIndex == chunkCount - 1) {
            totalBytes - (chunkCount - 1) * maximumChunkBytes
        } else {
            maximumChunkBytes
        }
        if (payload.size != expectedPayloadBytes) {
            return rejected(TransportProtocolFailure.INVALID_CHUNK_METADATA)
        }

        val current = pending
        val frame = if (current == null) {
            acceptNextSequence(eventSequence)?.let {
                return ServerChunkAssemblyResult.Rejected(it)
            }
            if (chunkIndex != 0) {
                return rejected(TransportProtocolFailure.OUT_OF_ORDER_CHUNK)
            }
            PendingFrame(
                generation = generation,
                eventSequence = eventSequence,
                chunkCount = chunkCount,
                totalBytes = totalBytes,
                nextChunkIndex = 0,
                output = ByteArrayOutputStream(totalBytes),
            ).also { pending = it }
        } else {
            if (
                current.generation != generation ||
                current.eventSequence != eventSequence ||
                current.chunkCount != chunkCount ||
                current.totalBytes != totalBytes
            ) {
                return rejected(TransportProtocolFailure.OUT_OF_ORDER_DELIVERY)
            }
            current
        }
        if (chunkIndex != frame.nextChunkIndex) {
            return rejected(TransportProtocolFailure.OUT_OF_ORDER_CHUNK)
        }
        frame.output.write(payload)
        frame.nextChunkIndex += 1
        if (frame.nextChunkIndex < frame.chunkCount) {
            return ServerChunkAssemblyResult.Incomplete
        }

        pending = null
        val completed = frame.output.toByteArray()
        if (completed.size != frame.totalBytes || !isOneUtf8JsonObject(completed)) {
            return rejected(TransportProtocolFailure.INVALID_FRAME)
        }
        return ServerChunkAssemblyResult.Complete(
            generation = generation,
            eventSequence = eventSequence,
            frame = completed,
        )
    }

    @Synchronized
    fun currentGeneration(): Long? = generation

    private fun prepareGeneration(
        incomingGeneration: Long,
        incomingSequence: Long,
    ): TransportProtocolFailure? {
        if (incomingGeneration < 0 || incomingSequence < 0) {
            return reject(TransportProtocolFailure.STALE_GENERATION)
        }
        val current = generation
        if (current == null) {
            if (!isFirstDelivery(incomingGeneration, incomingSequence)) {
                return reject(TransportProtocolFailure.OUT_OF_ORDER_DELIVERY)
            }
            generation = incomingGeneration
            lastEventSequence = if (incomingGeneration == 0L) -1L else null
            rejectedGeneration = null
            return null
        }
        if (incomingGeneration < current) {
            return TransportProtocolFailure.STALE_GENERATION
        }
        if (incomingGeneration > current) {
            if (incomingSequence != 1L || pending != null) {
                return reject(TransportProtocolFailure.OUT_OF_ORDER_DELIVERY)
            }
            generation = incomingGeneration
            lastEventSequence = null
            pending = null
            rejectedGeneration = null
        }
        return null
    }

    private fun isFirstDelivery(generation: Long, sequence: Long): Boolean =
        (generation == 0L && sequence == 0L) || (generation > 0L && sequence == 1L)

    private fun acceptNextSequence(eventSequence: Long): TransportProtocolFailure? {
        val expected = (lastEventSequence ?: 0L) + 1L
        if (eventSequence != expected) {
            return reject(TransportProtocolFailure.OUT_OF_ORDER_DELIVERY)
        }
        lastEventSequence = eventSequence
        return null
    }

    private fun rejected(failure: TransportProtocolFailure): ServerChunkAssemblyResult.Rejected =
        ServerChunkAssemblyResult.Rejected(reject(failure))

    private fun reject(failure: TransportProtocolFailure): TransportProtocolFailure {
        rejectedGeneration = generation
        pending = null
        return failure
    }

    private data class PendingFrame(
        val generation: Long,
        val eventSequence: Long,
        val chunkCount: Int,
        val totalBytes: Int,
        var nextChunkIndex: Int,
        val output: ByteArrayOutputStream,
    )
}

private fun isOneUtf8JsonObject(bytes: ByteArray): Boolean {
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val text = try {
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        return false
    }
    if ('\n' in text || '\r' in text) return false
    return try {
        JSONObject(text)
        true
    } catch (_: Exception) {
        false
    }
}
