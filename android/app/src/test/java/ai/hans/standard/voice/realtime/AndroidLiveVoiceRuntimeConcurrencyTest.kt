package ai.hans.standard.voice.realtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidLiveVoiceRuntimeConcurrencyTest {
    @Test
    fun hostStateQueriesDoNotWaitForRuntimeMonitorWhileStartNeedsHost() {
        val runtime = AndroidLiveVoiceRuntime
        runtime.clearForTest()
        val runtimeMonitor = requireNotNull(field("monitor").get(runtime))
        val hostMonitor = ReentrantLock()
        val runtimeHeld = CountDownLatch(1)
        val hostHeld = CountDownLatch(1)
        val queriesFinished = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        var hostAcquired = false
        val start = Thread {
            try {
                synchronized(runtimeMonitor) {
                    runtimeHeld.countDown()
                    check(hostHeld.await(2, TimeUnit.SECONDS))
                    // Bound the inverse acquisition so a regression fails the assertion without
                    // permanently deadlocking the Gradle test JVM.
                    hostAcquired = hostMonitor.tryLock(2, TimeUnit.SECONDS)
                    if (hostAcquired) hostMonitor.unlock()
                }
            } catch (failure: Throwable) {
                error.compareAndSet(null, failure)
            }
        }.apply { name = "runtime-start-needs-host"; isDaemon = true }
        val notification = Thread {
            try {
                hostMonitor.lock()
                try {
                    hostHeld.countDown()
                    check(runtimeHeld.await(2, TimeUnit.SECONDS))
                    assertEquals(LiveVoicePhase.IDLE, runtime.snapshot().phase)
                    assertNull(runtime.currentCallToken())
                    assertNull(runtime.currentVoiceSessionId())
                    assertFalse(runtime.isPendingStart(0))
                    assertFalse(runtime.consumeStop(0))
                    assertFalse(runtime.isLatestCommand(0))
                    assertFalse(runtime.wasStopRequested(0))
                    assertFalse(runtime.setInputMuted(true))
                    runtime.refreshContext()
                    queriesFinished.countDown()
                } finally {
                    hostMonitor.unlock()
                }
            } catch (failure: Throwable) {
                error.compareAndSet(null, failure)
            }
        }.apply { name = "notification-host-state-query"; isDaemon = true }
        try {
            start.start()
            assertTrue(runtimeHeld.await(2, TimeUnit.SECONDS))
            notification.start()
            assertTrue("Host reads must finish while the start still holds the runtime monitor",
                queriesFinished.await(750, TimeUnit.MILLISECONDS))
        } finally {
            // All waits have their own deadline; open both latches even after an early failure.
            runtimeHeld.countDown()
            hostHeld.countDown()
            start.join(3_000)
            notification.join(3_000)
            runtime.clearForTest()
        }
        assertFalse(start.isAlive)
        assertFalse(notification.isAlive)
        error.get()?.let { throw AssertionError("Concurrent state query failed", it) }
        assertTrue(hostAcquired)
    }

    @Test
    fun publishedReferenceReadsEffectiveSessionStateRatherThanRequestedPhase() {
        val runtime = AndroidLiveVoiceRuntime
        runtime.clearForTest()
        val fake = Session()
        val publication = field("publishedSession")
        try {
            publication.set(runtime, LiveVoiceRuntimeSessionBinding(fake, 42))
            assertSame(fake.state, runtime.snapshot())
            val next = LiveVoiceSnapshot(phase = LiveVoicePhase.CONFIGURING, inputMuted = true)
            fake.state = next
            assertSame(next, runtime.snapshot())
            assertTrue(runtime.setInputMuted(true))
            assertEquals(listOf(true), fake.mutes)
            runtime.refreshContext()
            assertEquals(1, fake.refreshes)
            // A reachable session object alone cannot authorize an active-call tool.
            assertNull(runtime.currentVoiceSessionId())
            assertNull(runtime.currentCallToken())
        } finally {
            runtime.clearForTest()
        }
        assertEquals(LiveVoicePhase.IDLE, runtime.snapshot().phase)
        assertFalse(runtime.setInputMuted(false))
    }

    @Test
    fun callIdentityRequiresExactPublishedAndCurrentCommandToken() {
        val runtime = AndroidLiveVoiceRuntime
        runtime.clearForTest()
        val fence = field("commandFence").get(runtime) as LiveVoiceServiceCommandFence
        val publication = field("publishedSession")
        val callbackToken = field("callbackToken")
        try {
            val old = fence.reserveStart()!!
            callbackToken.setLong(runtime, old)
            publication.set(runtime, LiveVoiceRuntimeSessionBinding(Session(), old))
            assertEquals(old, runtime.currentCallToken())
            assertEquals("effective-session", runtime.currentVoiceSessionId())

            assertTrue(fence.complete(old))
            assertNull(runtime.currentVoiceSessionId())
            val next = fence.reserveStart()!!
            callbackToken.setLong(runtime, next)
            assertEquals(next, runtime.currentCallToken())
            assertNull("An old session cannot become the new call's identity", runtime.currentVoiceSessionId())

            publication.set(runtime, LiveVoiceRuntimeSessionBinding(Session(), next))
            assertEquals("effective-session", runtime.currentVoiceSessionId())
            assertTrue(fence.claimStart(next))
            assertEquals(next, fence.requestStop(next))
            // Active stop keeps identity until real cleanup completes; no optimistic release.
            assertEquals(next, runtime.currentCallToken())
            assertTrue(fence.complete(next))
            assertNull(runtime.currentCallToken())
            assertNull(runtime.currentVoiceSessionId())
        } finally {
            runtime.clearForTest()
        }
    }

    @Test
    fun physicalStopDoesNotHoldRuntimeMonitorWhileWaitingForSession() {
        val runtime = AndroidLiveVoiceRuntime
        runtime.clearForTest()
        val fence = field("commandFence").get(runtime) as LiveVoiceServiceCommandFence
        val monitor = requireNotNull(field("monitor").get(runtime))
        val stopEntered = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        val monitorAcquired = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val fake = Session().apply {
            stopAction = {
                stopEntered.countDown()
                check(releaseStop.await(2, TimeUnit.SECONDS))
            }
        }
        var stopThread: Thread? = null
        var queryThread: Thread? = null
        try {
            val token = fence.reserveStart()!!
            assertTrue(fence.claimStart(token))
            field("session").set(runtime, fake)
            field("sessionToken").set(runtime, token)
            field("publishedSession").set(runtime, LiveVoiceRuntimeSessionBinding(fake, token))
            stopThread = Thread {
                try { assertEquals(token, runtime.stopSession(token)) }
                catch (failure: Throwable) { error.compareAndSet(null, failure) }
            }.apply { isDaemon = true; start() }
            assertTrue(stopEntered.await(2, TimeUnit.SECONDS))
            queryThread = Thread {
                synchronized(monitor) { monitorAcquired.countDown() }
            }.apply { isDaemon = true; start() }
            assertTrue("Physical stop must run after releasing the global monitor",
                monitorAcquired.await(750, TimeUnit.MILLISECONDS))
            assertEquals(token, runtime.currentCallToken())
            assertNull("A second start cannot steal the still-stopping physical lease", fence.reserveStart())
        } finally {
            releaseStop.countDown()
            stopThread?.join(3_000)
            queryThread?.join(3_000)
            runtime.clearForTest()
        }
        assertFalse(stopThread?.isAlive == true)
        assertFalse(queryThread?.isAlive == true)
        error.get()?.let { throw AssertionError("Physical stop failed", it) }
    }

    private fun field(name: String) = AndroidLiveVoiceRuntime::class.java
        .getDeclaredField(name).apply { isAccessible = true }

    private class Session : HansLiveVoiceSession {
        @Volatile var state = LiveVoiceSnapshot(phase = LiveVoicePhase.LISTENING)
        val mutes = mutableListOf<Boolean>()
        var refreshes = 0
        var stopAction: () -> Unit = {}
        override val snapshot: LiveVoiceSnapshot get() = state
        override val voiceSessionId = "effective-session"
        override fun start() = Unit
        override fun refreshContext() { refreshes++ }
        override fun setInputMuted(value: Boolean): Boolean { mutes += value; return true }
        override fun interruptHans() = Unit
        override fun stop() = stopAction()
        override fun close() = Unit
    }
}
