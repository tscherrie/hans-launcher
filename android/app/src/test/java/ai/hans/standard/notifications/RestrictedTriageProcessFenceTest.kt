package ai.hans.standard.notifications

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictedTriageProcessFenceTest {
    @Test
    fun preemptionWhileLauncherHasNotReturnedAbortsLateProcessBeforeAnyProtocolWrite() {
        val epoch = AtomicLong(0)
        val process = TestProcess()
        val fence = fence(epoch)
        val starting = CountDownLatch(1)
        val returnProcess = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit {
                fence.start(0) {
                    starting.countDown()
                    await(returnProcess)
                    process
                }
                fence.writeCurrent(0) { process.sent.write("notification text".toByteArray()) }
            }
            await(starting)
            epoch.incrementAndGet()
            fence.abortCurrent()
            assertFalse("The blocked launcher has not published its child yet", process.destroyed.get())

            returnProcess.countDown()
            assertPreempted { result.get(2, TimeUnit.SECONDS) }
            assertTrue(process.destroyed.get())
            assertEquals(0, process.sent.size())
        } finally {
            returnProcess.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun preemptionAfterRegistrationBeforeEpochRecheckAlsoAbortsTheProcess() {
        val epoch = AtomicLong(0)
        val checks = AtomicInteger()
        val registered = CountDownLatch(1)
        val finishRecheck = CountDownLatch(1)
        val process = TestProcess()
        val fence = RestrictedTriageProcessFence(
            ensureCurrent = { captured ->
                if (checks.incrementAndGet() == 2) {
                    registered.countDown()
                    await(finishRecheck)
                }
                if (epoch.get() != captured) throw NotificationTriagePreemptedException()
            },
            abort = { it.destroyForcibly() },
        )
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit { fence.start(0) { process } }
            await(registered)
            epoch.incrementAndGet()
            fence.abortCurrent()
            assertTrue("Registration must make the child visible to the preemptor", process.destroyed.get())
            finishRecheck.countDown()
            assertPreempted { result.get(2, TimeUnit.SECONDS) }
        } finally {
            finishRecheck.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun preemptionBetweenProtocolStepsRejectsTheNextWrite() {
        val epoch = AtomicLong(0)
        val fence = fence(epoch)
        val process = fence.start(0) { TestProcess() } as TestProcess
        fence.writeCurrent(0) { process.sent.write("initialize\n".toByteArray()) }
        val preparingTurn = CountDownLatch(1)
        val finishPreparation = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit {
                preparingTurn.countDown()
                await(finishPreparation)
                fence.writeCurrent(0) { process.sent.write("private notification\n".toByteArray()) }
            }
            await(preparingTurn)
            epoch.incrementAndGet()
            fence.abortCurrent()
            finishPreparation.countDown()
            assertPreempted { result.get(2, TimeUnit.SECONDS) }
            assertEquals("initialize\n", process.sent.toString(Charsets.UTF_8.name()))
        } finally {
            finishPreparation.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun closingDuringStartupAbortsTheLateProcessAndDoesNotAllowWrites() {
        val closed = AtomicBoolean(false)
        val process = TestProcess()
        val fence = RestrictedTriageProcessFence(
            ensureCurrent = { if (closed.get()) throw NotificationTriagePreemptedException() },
            abort = { it.destroyForcibly() },
        )
        assertThrows(NotificationTriagePreemptedException::class.java) {
            fence.start(0) {
                closed.set(true)
                fence.abortCurrent()
                process
            }
        }
        assertTrue(process.destroyed.get())
        assertEquals(0, process.sent.size())
    }

    @Test
    fun rejectedRegistrationAndOldCleanupCannotReplaceOrClearTheActiveProcess() {
        val epoch = AtomicLong(0)
        val fence = fence(epoch)
        val active = fence.start(0) { TestProcess() } as TestProcess
        val rejected = TestProcess()
        assertThrows(IllegalStateException::class.java) { fence.start(0) { rejected } }
        assertTrue(rejected.destroyed.get())
        assertFalse(active.destroyed.get())
        fence.release(rejected)
        fence.abortCurrent()
        assertTrue(active.destroyed.get())

        fence.release(active)
        epoch.incrementAndGet()
        val replacement = fence.start(1) { TestProcess() } as TestProcess
        fence.release(active)
        fence.writeCurrent(1) { replacement.sent.write(1) }
        fence.abortCurrent()
        assertEquals(1, replacement.sent.size())
        assertTrue(replacement.destroyed.get())
    }

    @Test
    fun preemptionAndNormalCleanupDestroyBeforeAStdinCloseThatWaitsForProcessExit() {
        for (terminate in listOf<(Process) -> Unit>(
            RestrictedTriageProcessTermination::abortImmediately,
            RestrictedTriageProcessTermination::terminate,
        )) {
            val process = BlockingCloseProcess()
            val worker = Executors.newSingleThreadExecutor()
            try {
                val result = worker.submit { terminate(process) }
                await(process.closeEntered)
                assertTrue("Closing stdin before kill can deadlock preemption", process.killedBeforeClose.get())
                result.get(2, TimeUnit.SECONDS)
                assertFalse(process.isAlive)
            } finally {
                process.destroyForcibly()
                worker.shutdownNow()
            }
        }
    }

    private fun fence(epoch: AtomicLong) = RestrictedTriageProcessFence(
        ensureCurrent = { captured ->
            if (epoch.get() != captured) throw NotificationTriagePreemptedException()
        },
        abort = { it.destroyForcibly() },
    )

    private fun await(latch: CountDownLatch) {
        assertTrue("Expected deterministic race checkpoint", latch.await(2, TimeUnit.SECONDS))
    }

    private fun assertPreempted(block: () -> Unit) {
        val failure = assertThrows(ExecutionException::class.java, block)
        assertTrue(failure.cause is NotificationTriagePreemptedException)
    }

    private class TestProcess : Process() {
        val destroyed = AtomicBoolean(false)
        val sent = ByteArrayOutputStream()
        override fun getOutputStream() = sent
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = if (destroyed.get()) 0 else throw IllegalThreadStateException()
        override fun isAlive(): Boolean = !destroyed.get()
        override fun destroy() { destroyed.set(true) }
        override fun destroyForcibly(): Process = apply { destroy() }
    }

    private class BlockingCloseProcess : Process() {
        val closeEntered = CountDownLatch(1)
        val killedBeforeClose = AtomicBoolean(false)
        private val killed = CountDownLatch(1)
        private val output = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun close() {
                killedBeforeClose.set(killed.count == 0L)
                closeEntered.countDown()
                check(killed.await(2, TimeUnit.SECONDS)) { "stdin_close_blocked_before_kill" }
            }
        }
        override fun getOutputStream() = output
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int { killed.await(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = killed.await(timeout, unit)
        override fun exitValue(): Int = if (!isAlive) 0 else throw IllegalThreadStateException()
        override fun isAlive(): Boolean = killed.count != 0L
        override fun destroy() { killed.countDown() }
        override fun destroyForcibly(): Process = apply { destroy() }
    }
}
