package ai.hans.standard.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingDispatcherShutdownTest {
    @Test
    fun closeRetainsAlreadyQueuedMicrophoneCleanup() {
        val dispatcher = ExecutorRecordingTaskDispatcher()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleanup = CountDownLatch(1)
        try {
            dispatcher.dispatch {
                entered.countDown()
                try {
                    release.await(5, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    // Emulate an in-flight operation exiting during service teardown.
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            dispatcher.dispatch { cleanup.countDown() }
            dispatcher.close()
            release.countDown()
            assertTrue("Accepted resource cleanup must not be discarded", cleanup.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            dispatcher.close()
        }
    }

    @Test
    fun lateCaptureCallbacksAfterCloseAreIgnoredWithoutThrowing() {
        val dispatcher = ExecutorRecordingTaskDispatcher()
        val callbackRan = AtomicBoolean(false)
        dispatcher.close()
        dispatcher.dispatch { callbackRan.set(true) }
        assertFalse(callbackRan.get())
    }
}
