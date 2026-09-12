package ai.hans.standard.runtime

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationStdinWriterTest {
    @Test
    fun serialLanePreservesFrameOrderAndCopiesCallerOwnedBytes() {
        val output = GatedOutput(expectedFlushes = 3)
        val writer = GenerationStdinWriter(1, output, { error("Unexpected failure") })
        try {
            assertTrue(writer.offerFrame("first".toByteArray()))
            assertTrue(output.entered.await(1, TimeUnit.SECONDS))
            val mutable = "second".toByteArray()
            assertTrue(writer.offerFrame(mutable))
            mutable.fill('x'.code.toByte())
            assertTrue(writer.offerFrame("third".toByteArray()))
            output.release.countDown()
            assertTrue(output.flushed.await(1, TimeUnit.SECONDS))
            assertEquals("first\nsecond\nthird\n", output.text())
        } finally {
            output.release.countDown()
            writer.closeAfterProcessTermination()
            assertTrue(output.closed.await(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun byteBudgetIncludesTheBlockedInFlightFrameAndNewlineFraming() {
        val output = GatedOutput(expectedFlushes = 2)
        val writer = GenerationStdinWriter(1, output, {}, maximumOutstandingBytes = 7)
        try {
            assertTrue(writer.offerFrame("abc".toByteArray())) // 4 bytes in flight.
            assertTrue(output.entered.await(1, TimeUnit.SECONDS))
            assertTrue(writer.offerFrame("de".toByteArray())) // 3 bytes queued: exact limit.
            assertFalse(writer.offerFrame("f".toByteArray()))
            assertFalse(writer.offerFrame(ByteArray(0)))
            output.release.countDown()
            assertTrue(output.flushed.await(1, TimeUnit.SECONDS))
            assertEquals("abc\nde\n", output.text())
        } finally {
            output.release.countDown()
            writer.closeAfterProcessTermination()
            assertTrue(output.closed.await(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun frameCountBudgetIncludesTheBlockedInFlightFrame() {
        val output = GatedOutput()
        val writer = GenerationStdinWriter(1, output, {}, maximumOutstandingFrames = 2)
        try {
            assertTrue(writer.offerFrame("a".toByteArray()))
            assertTrue(output.entered.await(1, TimeUnit.SECONDS))
            assertTrue(writer.offerFrame("b".toByteArray()))
            assertFalse(writer.offerFrame("c".toByteArray()))
        } finally {
            writer.cancelPending()
            output.release.countDown()
            writer.closeAfterProcessTermination()
            assertTrue(output.closed.await(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun cancellationDropsQueuedFramesAndNeverClosesBeforeProcessTerminationPermission() {
        val output = GatedOutput()
        val writer = GenerationStdinWriter(1, output, {})
        try {
            assertTrue(writer.offerFrame("active".toByteArray()))
            assertTrue(output.entered.await(1, TimeUnit.SECONDS))
            assertTrue(writer.offerFrame("must-not-be-written".toByteArray()))
            writer.cancelPending()
            assertFalse(writer.offerFrame("too-late".toByteArray()))
            output.release.countDown()
            assertTrue(output.writeReturned.await(1, TimeUnit.SECONDS))
            assertFalse(output.closed.await(25, TimeUnit.MILLISECONDS))
            writer.closeAfterProcessTermination()
            assertTrue(output.closed.await(1, TimeUnit.SECONDS))
            assertEquals("active", output.text())
        } finally {
            output.release.countDown()
            writer.closeAfterProcessTermination()
        }
    }

    @Test
    fun pipeFailureRejectsFurtherFramesAndNotifiesItsOwnerOnce() {
        val failed = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val notifications = AtomicInteger()
        val writer = GenerationStdinWriter(8, object : OutputStream() {
            override fun write(value: Int) { throw IOException("Synthetic broken pipe") }
            override fun close() { closed.countDown() }
        }, {
            notifications.incrementAndGet()
            failed.countDown()
        })
        try {
            assertTrue(writer.offerFrame("first".toByteArray()))
            assertTrue(failed.await(1, TimeUnit.SECONDS))
            assertFalse(writer.offerFrame("second".toByteArray()))
            assertEquals(1, notifications.get())
        } finally {
            writer.closeAfterProcessTermination()
            assertTrue(closed.await(1, TimeUnit.SECONDS))
        }
    }

    private class GatedOutput(expectedFlushes: Int = 1) : OutputStream() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writeReturned = CountDownLatch(1)
        val flushed = CountDownLatch(expectedFlushes)
        val closed = CountDownLatch(1)
        private val bytes = ByteArrayOutputStream()

        override fun write(value: Int) = synchronized(bytes) { bytes.write(value) }

        override fun write(value: ByteArray, offset: Int, length: Int) {
            entered.countDown()
            release.await()
            synchronized(bytes) { bytes.write(value, offset, length) }
            writeReturned.countDown()
        }

        override fun flush() { flushed.countDown() }
        override fun close() { closed.countDown() }
        fun text(): String = synchronized(bytes) { bytes.toString(Charsets.UTF_8.name()) }
    }
}
