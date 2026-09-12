package ai.hans.standard.voice.realtime

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveVoiceTransportControlTest {
    @Test fun callbackCleanupQueuesWithoutBlockingAndSeesLatePublishedResource() {
        val control = LiveVoiceTransportControl()
        val creating = CountDownLatch(1)
        val finishCreate = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        var requestHandle: (() -> Unit)? = null
        try {
            control.execute {
                creating.countDown()
                check(finishCreate.await(2, TimeUnit.SECONDS))
                requestHandle = { events += "cancel-handle" }
                events += "published-handle"
            }
            assertTrue(creating.await(2, TimeUnit.SECONDS))
            // Represents route loss/provider failure while create() has not returned its lease.
            assertTrue(control.execute { requestHandle?.invoke() })
            finishCreate.countDown()
            control.call { Unit }
            assertEquals(listOf("published-handle", "cancel-handle"), events)
        } finally {
            finishCreate.countDown()
            control.close()
        }
    }

    @Test fun terminalSignalRejectsQueuedUnmuteBeforeTeardownAndNoLaterNativeAccessOccurs() {
        val control = LiveVoiceTransportControl()
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val terminal = AtomicBoolean(false)
        val events = Collections.synchronizedList(mutableListOf<String>())
        try {
            control.execute { running.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
            assertTrue(running.await(2, TimeUnit.SECONDS))
            control.execute { if (!terminal.get()) events += "record:true" }
            terminal.set(true)
            control.execute { events += "record:false"; events += "dispose" }
            control.execute { if (!terminal.get()) events += "record:true" }
            release.countDown()
            control.call { Unit }
            assertEquals(listOf("record:false", "dispose"), events)
        } finally { release.countDown(); control.close() }
    }

    @Test fun reentrantControlCallDoesNotWaitOnItselfAndFailureDoesNotPoisonWorker() {
        val control = LiveVoiceTransportControl()
        try {
            assertEquals(42, control.call { control.call { 42 } })
            var rejected = false
            try { control.call<Unit> { error("native failure") } }
            catch (_: IllegalStateException) { rejected = true }
            assertTrue(rejected)
            assertEquals("still-owned", control.call { "still-owned" })
        } finally { control.close() }
        assertFalse(control.execute { error("closed worker ran a callback") })
    }

    @Test fun interruptedCloseCallerDoesNotCancelQueuedResourceCleanup() {
        val control = LiveVoiceTransportControl()
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleanup = CountDownLatch(1)
        val callerStarted = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val caller = Thread {
            Thread.currentThread().interrupt()
            callerStarted.countDown()
            try { control.call { cleanup.countDown() } }
            catch (_: IllegalStateException) {
                if (Thread.currentThread().isInterrupted) interrupted.countDown()
            }
        }
        try {
            control.execute { running.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
            assertTrue(running.await(2, TimeUnit.SECONDS))
            caller.start()
            assertTrue(callerStarted.await(2, TimeUnit.SECONDS))
            assertTrue(interrupted.await(2, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(cleanup.await(2, TimeUnit.SECONDS))
        } finally { release.countDown(); caller.join(2_000); control.close() }
    }
}
