package ai.hans.standard.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FixedPcmChunkerTest {
    @Test
    fun arbitraryReadsBecomeExactChunksAndOneFinalRemainder() {
        val chunker = FixedPcmChunker(fixedChunkBytes = 8)
        val chunks = buildList {
            addAll(chunker.append(byteArrayOf(0, 1, 2)))
            addAll(chunker.append(byteArrayOf(3, 4, 5, 6, 7, 8, 9, 10)))
            addAll(chunker.append(byteArrayOf(11, 12, 13, 14, 15, 16, 17)))
        }

        assertEquals(2, chunks.size)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7), chunks[0])
        assertArrayEquals(byteArrayOf(8, 9, 10, 11, 12, 13, 14, 15), chunks[1])
        assertArrayEquals(byteArrayOf(16, 17), chunker.finish())
        assertThrows(IllegalStateException::class.java) {
            chunker.append(byteArrayOf(18))
        }
    }

    @Test
    fun exactBoundaryProducesEmptyFinalMarkerWithoutDuplicatingAudio() {
        val chunker = FixedPcmChunker(fixedChunkBytes = 4)

        val chunks = chunker.append(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))

        assertEquals(2, chunks.size)
        assertEquals(0, chunker.bufferedBytes())
        assertArrayEquals(ByteArray(0), chunker.finish())
    }
}
