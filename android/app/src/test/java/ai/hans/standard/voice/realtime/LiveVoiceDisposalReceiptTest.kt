package ai.hans.standard.voice.realtime

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveVoiceDisposalReceiptTest {
    @Test fun successfulLateDisposalEmitsExactlyOnePositiveEventAfterTimeout() {
        val events = AtomicInteger()
        val receipt = LiveVoiceDisposalReceipt { events.incrementAndGet() }
        assertFalse(receipt.awaitNanos(0))
        assertEquals(0, events.get())
        receipt.complete(true)
        receipt.complete(true)
        receipt.complete(false)
        assertEquals(1, events.get())
        assertTrue(receipt.awaitNanos(0))
    }

    @Test fun failedDisposalNeverEmitsPositiveEventIncludingOnRepeatedClose() {
        val events = AtomicInteger()
        val receipt = LiveVoiceDisposalReceipt { events.incrementAndGet() }
        receipt.complete(false)
        receipt.complete(true)
        assertEquals(0, events.get())
        assertFalse(receipt.awaitNanos(0))
    }

    @Test fun requestOrDisposedFlagIsNotCompletionAndRepeatedWaitReadsActualOutcome() {
        val receipt = LiveVoiceDisposalReceipt()
        assertFalse(receipt.awaitNanos(0))
        receipt.complete(true)
        assertTrue(receipt.awaitNanos(0))
        assertTrue(receipt.awaitNanos(0))
        receipt.complete(false)
        assertTrue(receipt.awaitNanos(0))
    }

    @Test fun failedCleanupCannotBeReportedSuccessfulByARepeatedClose() {
        val receipt = LiveVoiceDisposalReceipt()
        receipt.complete(false)
        assertFalse(receipt.awaitNanos(0))
        receipt.complete(true)
        assertFalse(receipt.awaitNanos(0))
    }

    @Test fun boundedWaitDoesNotCancelQueuedCleanupBehindBusyNativeControl() {
        val control = LiveVoiceTransportControl()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val receipt = LiveVoiceDisposalReceipt()
        try {
            assertTrue(control.execute { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(control.execute { receipt.complete(true) })
            val start = System.nanoTime()
            assertFalse(receipt.awaitNanos(TimeUnit.MILLISECONDS.toNanos(25)))
            assertTrue("Timeout must not wait for the native worker", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1))
            release.countDown()
            assertTrue(receipt.awaitNanos(TimeUnit.SECONDS.toNanos(2)))
        } finally { release.countDown(); control.close() }
    }

    @Test fun interruptedWaitPreservesInterruptAndDoesNotPoisonLaterCompletion() {
        val receipt = LiveVoiceDisposalReceipt()
        Thread.currentThread().interrupt()
        try {
            assertFalse(receipt.awaitNanos(TimeUnit.SECONDS.toNanos(1)))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        receipt.complete(true)
        assertTrue(receipt.awaitNanos(0))
    }

    @Test fun androidCloseQueuesWithoutCallerSideNativeWaitAndAcknowledgesAfterAllSteps() {
        val source = sequenceOf(
            File("src/main/java/ai/hans/standard/voice/realtime/AndroidWebRtcRealtimeTransport.kt"),
            File("android/app/src/main/java/ai/hans/standard/voice/realtime/AndroidWebRtcRealtimeTransport.kt"),
        ).first(File::isFile).readText()
        val close = source.substringAfter("override fun closeAndAwait(").substringBefore("private fun controlResult")
        assertTrue(close.contains("terminal.set(true)"))
        assertTrue(close.contains("control.execute { disposeResources() }"))
        assertTrue(close.contains("disposalReceipt.awaitNanos(remainingNanos)"))
        assertFalse(close.contains("control.call"))
        assertFalse(close.contains("bufferedInput?.close()"))
        val disposal = source.substringAfter("private fun disposeResources()").substringBefore("private inline fun ignoreRuntimeFailure")
        assertFalse(disposal.contains("ignoreRuntimeFailure"))
        assertTrue(disposal.contains("catch (_: RuntimeException) { failed = true }"))
        assertTrue(disposal.indexOf("control.close()") < disposal.indexOf("disposalReceipt.complete(completedSuccessfully)"))
        assertTrue(disposal.indexOf("restoreCommunicationAudio()") < disposal.indexOf("disposalReceipt.complete(completedSuccessfully)"))
    }
}
