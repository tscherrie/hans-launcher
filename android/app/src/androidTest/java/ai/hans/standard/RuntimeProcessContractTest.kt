package ai.hans.standard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.hans.standard.runtime.IRuntimeProbeCallback
import ai.hans.standard.runtime.IRuntimeService
import ai.hans.standard.runtime.ProbeContract
import ai.hans.standard.runtime.RuntimeService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RuntimeProcessContractTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun packagedExecutableRunsAsChildOfDedicatedRuntimeProcess() {
        val serviceLatch = CountDownLatch(1)
        val resultLatch = CountDownLatch(1)
        val result = AtomicReference<ProbeResult>()
        var runtime: IRuntimeService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                runtime = IRuntimeService.Stub.asInterface(binder)
                serviceLatch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }

        assertTrue(
            context.bindService(
                Intent(context, RuntimeService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            ),
        )
        try {
            assertTrue("runtime did not bind", serviceLatch.await(10, TimeUnit.SECONDS))
            runtime!!.runNativeProbe(
                7L,
                object : IRuntimeProbeCallback.Stub() {
                    override fun onResult(
                        requestId: Long,
                        runtimePid: Int,
                        exitCode: Int,
                        stdout: String,
                        stderr: String,
                        error: String,
                    ) {
                        result.set(
                            ProbeResult(requestId, runtimePid, exitCode, stdout, stderr, error),
                        )
                        resultLatch.countDown()
                    }
                },
            )
            assertTrue("probe did not finish", resultLatch.await(10, TimeUnit.SECONDS))

            val actual = result.get()
            val report = ProbeContract.parse(actual.stdout)
            assertEquals(7L, actual.requestId)
            assertEquals(0, actual.exitCode)
            assertEquals("", actual.stderr)
            assertEquals("", actual.error)
            assertTrue(report != null)
            assertEquals(actual.runtimePid, report!!.parentPid)
            assertEquals("private", report.environment)
            assertNotEquals(actual.runtimePid, report.pid)
            assertNotEquals(android.os.Process.myPid(), actual.runtimePid)
        } finally {
            context.unbindService(connection)
        }
    }

    private data class ProbeResult(
        val requestId: Long,
        val runtimePid: Int,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val error: String,
    )
}
