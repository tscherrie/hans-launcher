package ai.hans.standard

import ai.hans.standard.runtime.CodexRuntimeContract
import ai.hans.standard.runtime.CodeModeHostContract
import ai.hans.standard.runtime.ICodexRuntimeCallback
import ai.hans.standard.runtime.IRuntimeService
import ai.hans.standard.runtime.RuntimeService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class CodexRuntimeGateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun pinnedAppServerExecutesVersionAndJsonRpcInitializeFromNativeLibraryDir() {
        val serviceLatch = CountDownLatch(1)
        val resultLatch = CountDownLatch(1)
        val result = AtomicReference<CodexGateResult>()
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
            runtime!!.runCodexReadinessGate(
                149L,
                object : ICodexRuntimeCallback.Stub() {
                    override fun onResult(
                        requestId: Long,
                        runtimePid: Int,
                        executablePath: String,
                        executableBytes: Long,
                        versionExitCode: Int,
                        version: String,
                        versionStdout: String,
                        versionStderr: String,
                        initializeExitCode: Int,
                        userAgent: String,
                        codexHome: String,
                        platformFamily: String,
                        platformOs: String,
                        initializeResponseJson: String,
                        initializeStderr: String,
                        timedOut: Boolean,
                        cleanupSucceeded: Boolean,
                        error: String,
                    ) {
                        result.set(
                            CodexGateResult(
                                requestId = requestId,
                                runtimePid = runtimePid,
                                executablePath = executablePath,
                                executableBytes = executableBytes,
                                versionExitCode = versionExitCode,
                                version = version,
                                versionStdout = versionStdout,
                                versionStderr = versionStderr,
                                initializeExitCode = initializeExitCode,
                                userAgent = userAgent,
                                codexHome = codexHome,
                                platformFamily = platformFamily,
                                platformOs = platformOs,
                                initializeResponseJson = initializeResponseJson,
                                initializeStderr = initializeStderr,
                                timedOut = timedOut,
                                cleanupSucceeded = cleanupSucceeded,
                                error = error,
                            ),
                        )
                        resultLatch.countDown()
                    }
                },
            )
            assertTrue("Codex readiness gate did not finish", resultLatch.await(90, TimeUnit.SECONDS))

            val actual = requireNotNull(result.get()) { "Codex readiness gate returned no result" }
            assertEquals(149L, actual.requestId)
            assertEquals(
                "Codex readiness gate requires a quiescent RuntimeService; " +
                    "run this fixture in its isolated instrumentation phase. " +
                    "Runtime error: ${actual.error}",
                "",
                actual.error,
            )
            val executable = File(actual.executablePath)
            val codeModeHost = CodeModeHostContract.executableFile(
                context.applicationInfo.nativeLibraryDir,
            )
            val nativeLibraryDirectory = File(context.applicationInfo.nativeLibraryDir).canonicalFile
            assertNotEquals(android.os.Process.myPid(), actual.runtimePid)
            assertTrue(executable.isFile)
            assertTrue(executable.canExecute())
            assertEquals(nativeLibraryDirectory, executable.canonicalFile.parentFile)
            assertEquals(BuildConfig.CODEX_RUNTIME_BYTES, actual.executableBytes)
            assertEquals(BuildConfig.CODEX_RUNTIME_BYTES, executable.length())
            assertEquals(BuildConfig.CODEX_RUNTIME_SHA256, executable.sha256())
            assertTrue(codeModeHost.isFile)
            assertTrue(codeModeHost.canExecute())
            assertEquals(nativeLibraryDirectory, codeModeHost.canonicalFile.parentFile)
            assertEquals(BuildConfig.CODE_MODE_HOST_BYTES, codeModeHost.length())
            assertEquals(BuildConfig.CODE_MODE_HOST_SHA256, codeModeHost.sha256())
            assertEquals(0, actual.versionExitCode)
            assertEquals(BuildConfig.CODEX_RUNTIME_VERSION, actual.version)
            assertEquals(
                "codex-app-server ${BuildConfig.CODEX_RUNTIME_VERSION}",
                actual.versionStdout.trim(),
            )
            assertTrue(
                "unexpected initialize termination: exit=${actual.initializeExitCode}, " +
                    "timedOut=${actual.timedOut}, cleanup=${actual.cleanupSucceeded}, " +
                    "error=${actual.error}",
                actual.initializeExitCode == 0 ||
                    actual.initializeExitCode == CodexRuntimeContract.READINESS_GATE_CONTROLLED_STOP,
            )
            assertEquals("unix", actual.platformFamily)
            assertEquals("linux", actual.platformOs)
            assertTrue(actual.userAgent.contains("hans_android_runtime"))
            assertTrue(
                File(actual.codexHome).canonicalPath.startsWith(
                    context.noBackupFilesDir.canonicalPath + File.separator,
                ),
            )
            assertEquals(1L, JSONObject(actual.initializeResponseJson).getLong("id"))
            assertFalse("initialize unexpectedly timed out: ${actual.error}", actual.timedOut)
            assertTrue("child process cleanup failed", actual.cleanupSucceeded)
        } finally {
            context.unbindService(connection)
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class CodexGateResult(
        val requestId: Long,
        val runtimePid: Int,
        val executablePath: String,
        val executableBytes: Long,
        val versionExitCode: Int,
        val version: String,
        val versionStdout: String,
        val versionStderr: String,
        val initializeExitCode: Int,
        val userAgent: String,
        val codexHome: String,
        val platformFamily: String,
        val platformOs: String,
        val initializeResponseJson: String,
        val initializeStderr: String,
        val timedOut: Boolean,
        val cleanupSucceeded: Boolean,
        val error: String,
    )
}
