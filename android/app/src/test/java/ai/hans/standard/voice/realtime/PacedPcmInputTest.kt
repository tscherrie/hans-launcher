package ai.hans.standard.voice.realtime

import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacedPcmInputTest {
    private class Clock {
        var now = 0L
        var waits = 0
        fun await(@Suppress("UNUSED_PARAMETER") lock: Object, nanos: Long) {
            waits += 1
            now += nanos
        }
        fun input(maxBytes: Int = PacedPcmInput.MAX_QUEUED_BYTES,
            onFailure: (String) -> Unit = {}) = PacedPcmInput(maxBytes, { now }, ::await, onFailure)
    }

    private fun frame(input: PacedPcmInput): ByteArray {
        val buffer = ByteBuffer.allocate(PacedPcmInput.FRAME_BYTES)
        input.render(buffer, PacedPcmInput.PCM_16_BIT, 1, PacedPcmInput.SAMPLE_RATE_HZ)
        return buffer.array()
    }

    @Test fun prefixIsPreservedBeforeReadinessAndPauseDoesNotDropQueuedInput() {
        val input = Clock().input()
        val done = CountDownLatch(1)
        val pcm = ByteArray(480) { (it % 127).toByte() }
        try {
            assertTrue(input.append(pcm, 24_000) { assertTrue(it.isSuccess); done.countDown() })
            repeat(2) { assertTrue(frame(input).all { it == 0.toByte() }) }
            assertEquals(1L, done.count)
            assertTrue(input.setEnabled(true))
            assertTrue(input.setEnabled(false))
            assertTrue(frame(input).all { it == 0.toByte() })
            assertTrue(input.setEnabled(true))
            assertArrayEquals(pcm, frame(input))
            assertTrue(done.await(1, TimeUnit.SECONDS))
        } finally { input.close() }
    }

    @Test fun splitChunksKeepOrderCopyOwnershipAndZeroPadOnlyAnUnderrun() {
        val input = Clock().input()
        val done = CountDownLatch(2)
        val first = ByteArray(600) { 11 }
        val second = ByteArray(120) { 22 }
        try {
            assertTrue(input.append(first, 24_000) { assertTrue(it.isSuccess); done.countDown() })
            assertTrue(input.append(second, 24_000) { assertTrue(it.isSuccess); done.countDown() })
            first.fill(99) // The FIFO owns a copy, never caller-owned mutable storage.
            second.fill(99)
            input.setEnabled(true)
            assertArrayEquals(ByteArray(480) { 11 }, frame(input))
            assertEquals(2L, done.count)
            val tail = frame(input)
            assertArrayEquals(ByteArray(120) { 11 }, tail.copyOfRange(0, 120))
            assertArrayEquals(ByteArray(120) { 22 }, tail.copyOfRange(120, 240))
            assertArrayEquals(ByteArray(240), tail.copyOfRange(240, 480))
            assertTrue(done.await(1, TimeUnit.SECONDS))
            assertArrayEquals(ByteArray(480), frame(input))
        } finally { input.close() }
    }

    @Test fun admissionChecksFormatChunkLimitAndTotalBufferLimitWithoutCallingRejectedCallbacks() {
        val input = Clock().input(maxBytes = 960)
        val called = AtomicInteger()
        try {
            val callback: (Result<Unit>) -> Unit = { called.incrementAndGet() }
            assertFalse(input.append(byteArrayOf(), 24_000, callback))
            assertFalse(input.append(byteArrayOf(1), 24_000, callback))
            assertFalse(input.append(ByteArray(480), 48_000, callback))
            assertFalse(input.append(ByteArray(48_002), 24_000, callback))
            assertTrue(input.append(ByteArray(960), 24_000) {})
            assertFalse(input.append(ByteArray(2), 24_000, callback))
            assertEquals(0, called.get())
        } finally { input.close() }
        assertEquals(0, called.get())
    }

    @Test fun tinyChunksAreCountBoundedAsWellAsByteBoundedAndAllFailOnceOnClose() {
        val input = Clock().input()
        val done = CountDownLatch(PacedPcmInput.MAX_PENDING_CHUNKS)
        val calls = AtomicInteger()
        repeat(PacedPcmInput.MAX_PENDING_CHUNKS) {
            assertTrue(input.append(ByteArray(2), 24_000) {
                assertTrue(it.isFailure); calls.incrementAndGet(); done.countDown()
            })
        }
        assertFalse(input.append(ByteArray(2), 24_000) { error("Rejected callback") })
        input.close()
        input.close()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(PacedPcmInput.MAX_PENDING_CHUNKS, calls.get())
        assertFalse(input.append(ByteArray(2), 24_000) { error("Closed callback") })
        assertFalse(input.setEnabled(true))
    }

    @Test fun everyFrameIsPacedIncludingPreReadyUnderrunAndClosedSilenceWithoutCatchUpBurst() {
        val clock = Clock()
        val input = clock.input()
        try {
            frame(input)
            assertEquals(10_000_000L, clock.now)
            input.setEnabled(true)
            frame(input)
            assertEquals(20_000_000L, clock.now)
            clock.now += 1_000_000_000L // One late frame may run now; no subsequent catch-up burst.
            frame(input)
            val late = clock.now
            frame(input)
            assertEquals(late + 10_000_000L, clock.now)
            input.close()
            frame(input)
            assertEquals(late + 20_000_000L, clock.now)
        } finally { input.close() }
    }

    @Test fun spuriousWakeupCannotEmitAnEarlyFrame() {
        var now = 0L
        var waits = 0
        val input = PacedPcmInput(nanoTime = { now }, awaitNanos = { _, nanos ->
            waits += 1
            if (waits > 1) now += nanos
        })
        try {
            frame(input)
            assertEquals(2, waits)
            assertEquals(10_000_000L, now)
        } finally { input.close() }
    }

    @Test fun unsupportedAdmFormatFailsClosedAndErasesOutputRatherThanCorruptingAudio() {
        listOf(Triple(3, 1, 24_000), Triple(2, 2, 24_000), Triple(2, 1, 48_000)).forEach {
            (format, channels, rate) ->
            val failure = AtomicReference<String?>()
            val failed = CountDownLatch(2)
            val input = Clock().input(onFailure = { code -> failure.set(code); failed.countDown() })
            try {
                input.append(ByteArray(480) { 7 }, 24_000) {
                    assertTrue(it.isFailure); failed.countDown()
                }
                input.setEnabled(true)
                val buffer = ByteBuffer.wrap(ByteArray(480) { 9 })
                input.render(buffer, format, channels, rate)
                assertArrayEquals(ByteArray(480), buffer.array())
                assertTrue(failed.await(1, TimeUnit.SECONDS))
                assertEquals("dictation_pcm_format_unsupported", failure.get())
                assertFalse(input.append(ByteArray(2), 24_000) {})
            } finally { input.close() }
        }
    }

    @Test fun unexpectedFrameSizeAndInterruptedPacingBothFailAcceptedChunksExactlyOnce() {
        listOf(false, true).forEach { interrupt ->
            val clock = Clock()
            val done = CountDownLatch(1)
            val calls = AtomicInteger()
            val input = PacedPcmInput(nanoTime = { clock.now }, awaitNanos = { lock, nanos ->
                if (interrupt) throw InterruptedException() else clock.await(lock, nanos)
            })
            input.append(ByteArray(480), 24_000) {
                assertTrue(it.isFailure); calls.incrementAndGet(); done.countDown()
            }
            input.setEnabled(true)
            input.render(ByteBuffer.allocate(if (interrupt) 480 else 482), 2, 1, 24_000)
            input.close()
            assertTrue(done.await(1, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        }
    }

    @Test fun completionMayCloseInputWithoutJoiningItsOwnAudioThreadOrDroppingOtherCallbacks() {
        val input = Clock().input()
        val done = CountDownLatch(2)
        input.append(ByteArray(480) { 4 }, 24_000) {
            assertTrue(it.isSuccess); input.close(); done.countDown()
        }
        input.append(ByteArray(480) { 5 }, 24_000) {
            assertTrue(it.isFailure); done.countDown()
        }
        input.setEnabled(true)
        frame(input)
        assertTrue(done.await(1, TimeUnit.SECONDS))
        input.close()
    }

    @Test fun realClockPacesNativeCallbackInsteadOfBusySpinning() {
        val input = PacedPcmInput()
        try {
            val started = System.nanoTime()
            repeat(3) { frame(input) }
            assertTrue(System.nanoTime() - started >= 30_000_000L)
        } finally { input.close() }
    }

    @Test fun unexpectedCallbackRuntimeFailureZerosAudioAndFailsEveryAcceptedChunkOnce() {
        val finished = CountDownLatch(3)
        val failure = AtomicReference<String?>()
        val calls = AtomicInteger()
        val input = PacedPcmInput(awaitNanos = { _, _ ->
            throw IllegalStateException("Synthetic callback failure")
        }, onFailure = { failure.set(it); finished.countDown() })
        try {
            repeat(2) {
                assertTrue(input.append(ByteArray(480) { 7 }, 24_000) { result ->
                    assertTrue(result.isFailure)
                    calls.incrementAndGet()
                    finished.countDown()
                })
            }
            input.setEnabled(true)
            val output = ByteBuffer.wrap(ByteArray(480) { 9 })
            val started = System.nanoTime()
            input.render(output, 2, 1, 24_000) // Must not throw onto the native audio thread.
            input.render(output, 2, 1, 24_000) // Repeated failure during teardown remains paced.
            assertTrue(System.nanoTime() - started >= 20_000_000L)
            assertArrayEquals(ByteArray(480), output.array())
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertEquals("dictation_pcm_callback_failed", failure.get())
            assertEquals(2, calls.get())
            assertFalse(input.append(ByteArray(480), 24_000) { error("Rejected callback") })
        } finally { input.close() }
    }

    @Test fun invalidReadOnlyNativeBufferFailsClosedRatherThanEscapingTheCallback() {
        val done = CountDownLatch(2)
        val failure = AtomicReference<String?>()
        val input = Clock().input(onFailure = { failure.set(it); done.countDown() })
        try {
            input.append(ByteArray(480) { 7 }, 24_000) { assertTrue(it.isFailure); done.countDown() }
            input.setEnabled(true)
            input.render(ByteBuffer.allocate(480).asReadOnlyBuffer(), 2, 1, 24_000)
            assertTrue(done.await(1, TimeUnit.SECONDS))
            assertEquals("dictation_pcm_callback_failed", failure.get())
            assertFalse(input.setEnabled(true))
        } finally { input.close() }
    }
}
