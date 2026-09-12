package ai.hans.standard.notifications

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only an in-memory fake child; no executable, login, notification, or network access. */
@RunWith(AndroidJUnit4::class)
class AndroidRestrictedTriageProcessFenceTest {
    @Test
    fun preemptionBeforeTheLauncherReturnsAbortsTheLateChildBeforeProtocolWrites() {
        val epoch = AtomicLong(0)
        val process = FakeProcess()
        val fence = RestrictedTriageProcessFence(
            ensureCurrent = { captured ->
                if (epoch.get() != captured) throw NotificationTriagePreemptedException()
            },
            abort = RestrictedTriageProcessTermination::abortImmediately,
        )

        assertThrows(NotificationTriagePreemptedException::class.java) {
            fence.start(0) {
                // Model the exact race: preemption observes no registered process, then startup
                // returns it. The post-registration epoch check must still abort it.
                epoch.incrementAndGet()
                fence.abortCurrent()
                process
            }
            fence.writeCurrent(0) { process.sent.write("synthetic notification".toByteArray()) }
        }
        assertTrue(process.destroyed.get())
        assertEquals(0, process.sent.size())
    }

    private class FakeProcess : Process() {
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
}
