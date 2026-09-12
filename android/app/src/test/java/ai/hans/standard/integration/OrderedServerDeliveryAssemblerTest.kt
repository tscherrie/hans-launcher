package ai.hans.standard.integration

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderedServerDeliveryAssemblerTest {
    @Test
    fun stateFrameChunksAndNoticeShareOneStrictSequence() {
        val assembler = OrderedServerDeliveryAssembler(
            maximumChunkBytes = 4,
            maximumFrameBytes = 64,
            maximumChunkCount = 16,
        )
        assertNull(assembler.acceptSingle(generation = 1, eventSequence = 1))
        val frame = "{\"x\":1}".toByteArray()

        assertEquals(
            ServerChunkAssemblyResult.Incomplete,
            assembler.acceptChunk(1, 2, 0, 2, frame.size, frame.copyOfRange(0, 4)),
        )
        val complete = assembler.acceptChunk(
            1,
            2,
            1,
            2,
            frame.size,
            frame.copyOfRange(4, frame.size),
        ) as ServerChunkAssemblyResult.Complete
        assertArrayEquals(frame, complete.frame)
        assertEquals(2L, complete.eventSequence)
        assertNull(assembler.acceptSingle(generation = 1, eventSequence = 3))
    }

    @Test
    fun gapsDuplicatesAndInterleavedCallbacksFailClosed() {
        val gap = OrderedServerDeliveryAssembler(maximumChunkBytes = 8)
        assertNull(gap.acceptSingle(1, 1))
        assertEquals(
            TransportProtocolFailure.OUT_OF_ORDER_DELIVERY,
            gap.acceptSingle(1, 3),
        )

        val duplicate = OrderedServerDeliveryAssembler(maximumChunkBytes = 8)
        assertNull(duplicate.acceptSingle(1, 1))
        assertEquals(
            TransportProtocolFailure.OUT_OF_ORDER_DELIVERY,
            duplicate.acceptSingle(1, 1),
        )

        val interleaved = OrderedServerDeliveryAssembler(
            maximumChunkBytes = 4,
            maximumFrameBytes = 64,
        )
        assertNull(interleaved.acceptSingle(1, 1))
        val frame = "{\"x\":1}".toByteArray()
        assertEquals(
            ServerChunkAssemblyResult.Incomplete,
            interleaved.acceptChunk(1, 2, 0, 2, frame.size, frame.copyOfRange(0, 4)),
        )
        assertEquals(
            TransportProtocolFailure.OUT_OF_ORDER_DELIVERY,
            interleaved.acceptSingle(1, 3),
        )
    }

    @Test
    fun malformedMetadataOversizeAndInvalidJsonAreRejectedBeforeDelivery() {
        val metadata = OrderedServerDeliveryAssembler(
            maximumChunkBytes = 4,
            maximumFrameBytes = 16,
            maximumChunkCount = 4,
        )
        assertNull(metadata.acceptSingle(1, 1))
        assertTrue(
            metadata.acceptChunk(1, 2, 0, 2, 7, ByteArray(3))
                is ServerChunkAssemblyResult.Rejected,
        )

        val oversized = OrderedServerDeliveryAssembler(
            maximumChunkBytes = 4,
            maximumFrameBytes = 8,
            maximumChunkCount = 4,
        )
        assertNull(oversized.acceptSingle(1, 1))
        val oversizeResult = oversized.acceptChunk(1, 2, 0, 3, 9, ByteArray(4))
            as ServerChunkAssemblyResult.Rejected
        assertEquals(TransportProtocolFailure.OVERSIZED_FRAME, oversizeResult.failure)

        val invalidJson = OrderedServerDeliveryAssembler(
            maximumChunkBytes = 8,
            maximumFrameBytes = 16,
            maximumChunkCount = 2,
        )
        assertNull(invalidJson.acceptSingle(1, 1))
        val invalid = invalidJson.acceptChunk(
            1,
            2,
            0,
            1,
            2,
            byteArrayOf(0xC3.toByte(), 0x28),
        ) as ServerChunkAssemblyResult.Rejected
        assertEquals(TransportProtocolFailure.INVALID_FRAME, invalid.failure)
    }

    @Test
    fun newGenerationResetsSequenceAndOldGenerationIsStale() {
        val assembler = OrderedServerDeliveryAssembler()
        assertNull(assembler.acceptSingle(1, 1))
        assertNull(assembler.acceptSingle(1, 2))
        assertNull(assembler.acceptSingle(2, 1))
        assertEquals(
            TransportProtocolFailure.STALE_GENERATION,
            assembler.acceptSingle(1, 3),
        )
        assertNull(assembler.acceptSingle(2, 2))
    }

    @Test
    fun standaloneGenerationZeroFailureCanPrecedeARealSession() {
        val assembler = OrderedServerDeliveryAssembler()
        assertNull(assembler.acceptSingle(0, 0))
        assertNull(assembler.acceptSingle(1, 1))
    }
}
