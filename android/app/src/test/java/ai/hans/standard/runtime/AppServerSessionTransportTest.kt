package ai.hans.standard.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class AppServerSessionTransportTest {
    @Test
    fun jsonLineFramerPreservesFramesAndOrder() {
        val input = ByteArrayInputStream("{\"id\":1}\r\n{\"id\":2}\n".toByteArray())
        val framer = BoundedJsonLineFramer(maximumFrameBytes = 128)

        assertArrayEquals(
            "{\"id\":1}".toByteArray(),
            (framer.readNext(input) as JsonLineReadResult.Frame).bytes,
        )
        assertArrayEquals(
            "{\"id\":2}".toByteArray(),
            (framer.readNext(input) as JsonLineReadResult.Frame).bytes,
        )
        assertEquals(JsonLineReadResult.Eof, framer.readNext(input))
    }

    @Test
    fun oversizedServerFrameIsDiscardedWithoutLosingNextFrame() {
        val input = ByteArrayInputStream(
            (("x".repeat(33)) + "\n{\"id\":2}\n").toByteArray(),
        )
        val framer = BoundedJsonLineFramer(maximumFrameBytes = 32)

        assertEquals(JsonLineReadResult.Oversized(33), framer.readNext(input))
        assertArrayEquals(
            "{\"id\":2}".toByteArray(),
            (framer.readNext(input) as JsonLineReadResult.Frame).bytes,
        )
        assertEquals(JsonLineReadResult.Eof, framer.readNext(input))
    }

    @Test
    fun eofIsDistinguishedFromPartialFrame() {
        val framer = BoundedJsonLineFramer(maximumFrameBytes = 32)

        assertEquals(
            JsonLineReadResult.PartialEof(8),
            framer.readNext(ByteArrayInputStream("{\"id\":1}".toByteArray())),
        )
        assertEquals(
            JsonLineReadResult.Eof,
            framer.readNext(ByteArrayInputStream(ByteArray(0))),
        )
    }

    @Test
    fun initializeImmediatelyFollowedByEventIsObservedReadyFirst() {
        val initializeId = AppServerSessionContract.initializeRequestId(3)
        val initialize = "{\"id\":\"$initializeId\",\"result\":{}}"
        val event = "{\"method\":\"thread/started\",\"params\":{}}"
        val observed = mutableListOf<String>()
        val buffered = PreReadyFrameBuffer(maximumBytes = 512, maximumFrames = 4)
        var ready = false

        val termination = AppServerStdoutLoop(
            BoundedJsonLineFramer(maximumFrameBytes = 512),
        ).run(
            input = ByteArrayInputStream("$initialize\n$event\n".toByteArray()),
            shouldStop = { false },
            onFrame = { frame ->
                if (JsonFrameValidator.hasResponseId(frame, initializeId)) {
                    ready = true
                    buffered.releaseAfterReady(
                        onReady = { observed += "READY" },
                        onFrame = { observed += it.toString(Charsets.UTF_8) },
                        onDroppedFrame = { observed += "DROPPED" },
                    )
                } else if (ready) {
                    observed += frame.toString(Charsets.UTF_8)
                } else {
                    buffered.hold(frame)
                }
            },
            onOversized = { observed += "OVERSIZED" },
        )

        assertEquals(StdoutLoopTermination.Eof, termination)
        assertEquals(listOf("READY", event), observed)
    }

    @Test
    fun preReadyReleaseAlwaysEmitsReadyBeforeFramesAndBoundedDropNotice() {
        val buffer = PreReadyFrameBuffer(maximumBytes = 20, maximumFrames = 2)
        buffer.hold("{\"id\":1}".toByteArray())
        buffer.hold("{\"id\":2}".toByteArray())
        buffer.hold("{\"id\":3}".toByteArray())
        val observed = mutableListOf<String>()

        buffer.releaseAfterReady(
            onReady = { observed += "READY" },
            onFrame = { observed += it.toString(Charsets.UTF_8) },
            onDroppedFrame = { observed += "DROPPED" },
        )

        assertEquals(listOf("READY", "{\"id\":1}", "{\"id\":2}", "DROPPED"), observed)
        assertFalse(buffer.droppedFrame)
        assertTrue(buffer.drain().isEmpty())
    }

    @Test
    fun throwingStdoutBecomesOneDeterministicFailureCleanup() {
        val input = object : InputStream() {
            override fun read(): Int = throw IOException("synthetic reader failure")
        }
        val termination = AppServerStdoutLoop().run(
            input = input,
            shouldStop = { false },
            onFrame = { error("unexpected frame") },
            onOversized = { error("unexpected oversized frame") },
        )
        var exits = 0
        var failures = 0
        StdoutTerminationRouter.route(
            termination = termination,
            sessionStopping = false,
            onPartialEof = { error("unexpected partial EOF") },
            onExited = { exits += 1 },
            onFailure = { failures += 1 },
        )

        assertEquals(StdoutLoopTermination.Failure, termination)
        assertEquals(0, exits)
        assertEquals(1, failures)
    }

    @Test
    fun chunkerUsesSmallOrderedBinderTransactionsAndReassemblesExactly() {
        val frame = ("{\"text\":\"" + "a".repeat(70) + "\"}").toByteArray()
        val chunks = BinderFrameChunker.chunks(frame, maximumChunkBytes = 16)
        val reassembled = ByteArrayOutputStream()

        assertEquals((frame.size + 15) / 16, chunks.size)
        chunks.forEachIndexed { index, chunk ->
            assertEquals(index, chunk.index)
            assertEquals(chunks.size, chunk.count)
            assertEquals(frame.size, chunk.totalBytes)
            assertTrue(chunk.payload.size in 1..16)
            reassembled.write(chunk.payload)
        }
        assertArrayEquals(frame, reassembled.toByteArray())
    }

    @Test
    fun orderedClientAssemblerCompletesFramesAndAdvancesSequence() {
        val frame = "{\"id\":7,\"params\":{\"text\":\"abcdefghijklmnop\"}}".toByteArray()
        val chunks = BinderFrameChunker.chunks(frame, maximumChunkBytes = 8)
        val assembler = OrderedClientFrameAssembler(
            maximumChunkBytes = 8,
            maximumFrameBytes = 256,
            maximumChunkCount = 64,
        )

        chunks.dropLast(1).forEach { chunk ->
            assertEquals(
                FrameAssemblyResult.Incomplete,
                assembler.accept(1, chunk.index, chunk.count, chunk.totalBytes, chunk.payload),
            )
        }
        val last = chunks.last()
        val completed = assembler.accept(
            1,
            last.index,
            last.count,
            last.totalBytes,
            last.payload,
        ) as FrameAssemblyResult.Complete

        assertEquals(1L, completed.clientSequence)
        assertArrayEquals(frame, completed.bytes)
        assertEquals(2L, assembler.nextExpectedClientSequence())
    }

    @Test
    fun clientAssemblerRejectsOutOfOrderChunksAndAllowsCleanRetry() {
        val frame = "{\"id\":1,\"method\":\"account/read\",\"params\":{}}".toByteArray()
        val chunks = BinderFrameChunker.chunks(frame, maximumChunkBytes = 16)
        val assembler = OrderedClientFrameAssembler(
            maximumChunkBytes = 16,
            maximumFrameBytes = 256,
            maximumChunkCount = 64,
        )

        assertTrue(
            assembler.accept(
                1,
                chunks[1].index,
                chunks[1].count,
                chunks[1].totalBytes,
                chunks[1].payload,
            ) is FrameAssemblyResult.Rejected,
        )
        assertEquals(1L, assembler.nextExpectedClientSequence())
        chunks.forEachIndexed { index, chunk ->
            val result = assembler.accept(
                1,
                chunk.index,
                chunk.count,
                chunk.totalBytes,
                chunk.payload,
            )
            if (index == chunks.lastIndex) {
                assertTrue(result is FrameAssemblyResult.Complete)
            } else {
                assertEquals(FrameAssemblyResult.Incomplete, result)
            }
        }
    }

    @Test
    fun clientAssemblerRejectsStaleOversizedAndInvalidFramesWithoutAdvancing() {
        val assembler = OrderedClientFrameAssembler(
            maximumChunkBytes = 32,
            maximumFrameBytes = 64,
            maximumChunkCount = 4,
        )
        assertTrue(
            assembler.accept(2, 0, 1, 2, "{}".toByteArray()) is FrameAssemblyResult.Rejected,
        )
        assertTrue(
            assembler.accept(1, 0, 3, 65, ByteArray(32)) is FrameAssemblyResult.Rejected,
        )
        assertTrue(
            assembler.accept(1, 0, 1, 2, byteArrayOf(0xC3.toByte(), 0x28))
                is FrameAssemblyResult.Rejected,
        )
        assertTrue(
            assembler.accept(1, 0, 1, 5, "{}\n{}".toByteArray())
                is FrameAssemblyResult.Rejected,
        )
        assertEquals(1L, assembler.nextExpectedClientSequence())
    }

    @Test
    fun initializeResponseDetectionDoesNotExposeOrMisclassifyOtherFrames() {
        val id = AppServerSessionContract.initializeRequestId(8)
        assertTrue(JsonFrameValidator.hasResponseId("{\"id\":\"$id\",\"result\":{}}".toByteArray(), id))
        assertFalse(JsonFrameValidator.hasResponseId("{\"id\":8,\"result\":{}}".toByteArray(), id))
        assertFalse(JsonFrameValidator.hasResponseId("not json".toByteArray(), id))
    }

    @Test
    fun generationsAndEventSequencesResetOnlyOnRestart() {
        val sequences = SessionEventSequencer()

        val firstGeneration = sequences.nextGeneration()
        assertEquals(1L, firstGeneration)
        assertEquals(1L, sequences.nextEvent(firstGeneration))
        assertEquals(2L, sequences.nextEvent(firstGeneration))

        val restartedGeneration = sequences.nextGeneration()
        assertEquals(2L, restartedGeneration)
        assertEquals(1L, sequences.nextEvent(restartedGeneration))
        assertThrows(IllegalStateException::class.java) {
            sequences.nextEvent(firstGeneration)
        }
    }

    @Test
    fun publicTransportLimitsStayBinderSafeAndDetailsStayBounded() {
        assertTrue(AppServerSessionContract.MAX_BINDER_CHUNK_BYTES < 32 * 1024)
        assertTrue(
            AppServerSessionContract.MAX_SERVER_FRAME_BYTES <=
                AppServerSessionContract.MAX_BINDER_CHUNK_BYTES *
                AppServerSessionContract.MAX_CHUNK_COUNT,
        )
        val detail = AppServerSessionContract.boundedDetail("a".repeat(500) + "\nsecret")
        assertEquals(AppServerSessionContract.MAX_DETAIL_CHARACTERS, detail.length)
        assertFalse('\n' in detail)
    }

    @Test
    fun emptyFrameAndImpossibleChunkCountAreRejectedBeforeAllocation() {
        assertThrows(IllegalArgumentException::class.java) {
            BinderFrameChunker.chunks(ByteArray(0))
        }
        val assembler = OrderedClientFrameAssembler(
            maximumChunkBytes = 8,
            maximumFrameBytes = 64,
            maximumChunkCount = 2,
        )
        assertTrue(
            assembler.accept(1, 0, 3, 17, ByteArray(8)) is FrameAssemblyResult.Rejected,
        )
    }
}
