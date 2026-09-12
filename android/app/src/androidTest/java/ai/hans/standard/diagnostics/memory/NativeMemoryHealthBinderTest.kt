package ai.hans.standard.diagnostics.memory

import ai.hans.standard.runtime.IRuntimeService
import ai.hans.standard.runtime.RuntimeService
import android.content.*
import android.os.Build
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Existing service Binder only, no Codex session start, permissions, account or database changes. */
class NativeMemoryHealthBinderTest {
    private fun withRuntime(check: (IRuntimeService) -> Unit) {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ready = CountDownLatch(1)
        var service: IRuntimeService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = IRuntimeService.Stub.asInterface(binder); ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, RuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        try { assertTrue(ready.await(10, TimeUnit.SECONDS)); check(service!!) }
        finally { context.unbindService(connection) }
    }
    @Test fun actualBinderRejectsStaleGenerationWithoutReadingDatabase() = withRuntime { service ->
        val result = NativeMemoryHealthWire.decode(service.readNativeMemoryHealth(Long.MAX_VALUE))
        assertEquals(NativeMemoryHealthResult.Unavailable(NativeMemoryUnavailableReason.CONFIGURATION_UNRESOLVED), result)
    }
    @Test fun ownUidCanReadBoundedCurrentStateWithoutPathsOrContents() = withRuntime { service ->
        val wire = service.readNativeMemoryHealth(0)
        assertTrue(wire.toByteArray().size <= NativeMemoryHealthWire.MAX_BYTES)
        NativeMemoryHealthWire.decode(wire)
        assertFalse(wire.contains("/data/"))
        assertFalse(wire.contains("raw_memory"))
        assertFalse(wire.contains("thread_id"))
    }
}
