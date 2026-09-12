package ai.hans.standard.ui

import ai.hans.standard.diagnostics.memory.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NativeMemoryHealthRequestTest {
    @Test fun boundedReplyDecodesMetadataOnly() = runBlocking {
        val result = NativeMemoryHealthResult.Unavailable(NativeMemoryUnavailableReason.CONFIGURATION_UNRESOLVED)
        assertEquals(result, awaitNativeMemoryHealthReply { NativeMemoryHealthWire.encode(result) })
    }
    @Test fun blockedReplyTimesOutWithoutWaitingForTransport() = runBlocking {
        val release = CountDownLatch(1)
        try {
            try {
                awaitNativeMemoryHealthReply(100) { release.await(5, TimeUnit.SECONDS); "{}" }
                fail("timeout expected")
            } catch (_: TimeoutCancellationException) { }
        } finally { release.countDown() }
    }
    @Test fun cancelledScreenDoesNotDeliverLateReply() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var delivered = false
        val job = launch(Dispatchers.Default) {
            awaitNativeMemoryHealthReply {
                entered.countDown(); release.await(5, TimeUnit.SECONDS)
                NativeMemoryHealthWire.encode(NativeMemoryHealthResult.Unavailable(NativeMemoryUnavailableReason.IO))
            }
            delivered = true
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertFalse(delivered)
        } finally { release.countDown(); job.cancelAndJoin() }
    }
}
