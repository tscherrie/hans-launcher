package ai.hans.standard.runtime.python.resolver

import ai.hans.standard.runtime.python.PythonEnvironmentContract
import ai.hans.standard.runtime.python.PythonEnvironmentResolutionRequest
import ai.hans.standard.runtime.python.PythonEnvironmentTarget
import ai.hans.standard.runtime.python.PythonExecutionHandle
import ai.hans.standard.runtime.python.PythonExecutionRequest
import ai.hans.standard.runtime.python.PythonExecutionResult
import ai.hans.standard.runtime.python.PythonExecutionStatus
import ai.hans.standard.runtime.python.PythonResultCallback
import ai.hans.standard.runtime.python.PythonRuntimeGateway
import ai.hans.standard.runtime.python.PythonRuntimePhase
import ai.hans.standard.runtime.python.PythonRuntimeSnapshot
import ai.hans.standard.runtime.python.PythonStreamListener
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PythonResolverRuntimeBridgeTest {
    @Test
    fun archiveIsDeterministicReadOnlyAndReferenceCounted() {
        val root = kotlin.io.path.createTempDirectory("resolver-registry").toFile()
        val base = baseArchive(root)
        val registry = PythonResolverArchiveRegistry(base, File(root, "active"), hash(base))
        val request = request()
        val project = PythonResolverProject("demo", listOf(candidate()))
        val first = registry.prepare(request, mapOf("demo" to project), PythonResolutionCancellation.NONE)
        val second = registry.prepare(request, mapOf("demo" to project), PythonResolutionCancellation.NONE)
        assertEquals(first.receipt.sha256, second.receipt.sha256)
        assertFalse(first.receipt.archive.canExecute())
        ZipFile(first.receipt.archive).use { zip ->
            assertTrue(zip.getEntry("hans_resolver_payload/request.json") != null)
            assertTrue(zip.entries().asSequence().all { it.method == ZipEntry.STORED })
        }
        first.close()
        first.close()
        assertTrue(registry.contains(second.receipt.sha256))
        second.close()
        assertFalse(registry.contains(second.receipt.sha256))
    }

    @Test
    fun isolatedGatewayResultIsStrictlyDecoded() {
        val root = kotlin.io.path.createTempDirectory("resolver-worker").toFile()
        val base = baseArchive(root)
        val registry = PythonResolverArchiveRegistry(base, File(root, "active"), hash(base))
        var observed: PythonExecutionRequest? = null
        val runtime = object : PythonRuntimeGateway {
            override fun snapshot() = PythonRuntimeSnapshot(PythonRuntimePhase.STOPPED, 0, 0)
            override fun execute(
                request: PythonExecutionRequest,
                streamListener: PythonStreamListener,
                callback: PythonResultCallback,
            ): PythonExecutionHandle {
                observed = request
                callback.onResult(
                    PythonExecutionResult(
                        request.requestId,
                        PythonExecutionStatus.SUCCEEDED,
                        valueJson = """{"schemaVersion":1,"status":"needs_projects","projects":["demo"]}""",
                    ),
                )
                return object : PythonExecutionHandle { override fun cancel() = false }
            }
        }
        val outcome = IsolatedPythonResolverWorker(runtime, registry) { 1_000L }.resolve(
            request(),
            emptyMap(),
            PythonResolutionCancellation.NONE,
        )
        assertEquals(PythonResolverWorkerOutcome.NeedsProjects(setOf("demo")), outcome)
        assertEquals("hans_resolver_worker", observed!!.entrypoint.module)
        assertFalse(registry.contains(observed!!.environmentDigest))
    }

    @Test
    fun cancellationInterruptsWorkerWaitImmediately() {
        val root = kotlin.io.path.createTempDirectory("resolver-worker-cancel").toFile()
        val base = baseArchive(root)
        val registry = PythonResolverArchiveRegistry(base, File(root, "active"), hash(base))
        val started = CountDownLatch(1)
        val runtime = object : PythonRuntimeGateway {
            override fun snapshot() = PythonRuntimeSnapshot(PythonRuntimePhase.STOPPED, 0, 0)
            override fun execute(
                request: PythonExecutionRequest,
                streamListener: PythonStreamListener,
                callback: PythonResultCallback,
            ): PythonExecutionHandle {
                started.countDown()
                return object : PythonExecutionHandle { override fun cancel() = true }
            }
        }
        val cancellation = MutablePythonResolutionCancellation()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<Throwable?> {
                runCatching {
                    IsolatedPythonResolverWorker(runtime, registry) { 1_000L }.resolve(
                        request(),
                        emptyMap(),
                        cancellation,
                    )
                }.exceptionOrNull()
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            cancellation.cancel()
            assertTrue(future.get(2, TimeUnit.SECONDS) is PythonResolutionCancelledException)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun baseArchive(root: File): File = File(root, "base.pyz").also { file ->
        ZipOutputStream(FileOutputStream(file)).use { output ->
            listOf("hans_resolver_worker.py", "hans_resolver_payload/__init__.py").forEach { name ->
                val body = if (name.startsWith("hans_resolver_worker")) "def resolve(): return {}\n".toByteArray()
                else ByteArray(0)
                val crc = CRC32().also { it.update(body) }
                output.putNextEntry(ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = body.size.toLong()
                    compressedSize = body.size.toLong()
                    this.crc = crc.value
                })
                output.write(body)
                output.closeEntry()
            }
        }
    }

    private fun request() = PythonEnvironmentResolutionRequest(
        "test.plugin",
        listOf("demo"),
        PythonEnvironmentTarget("3.14.7", "cp314", "arm64-v8a", 31),
    )

    private fun candidate() = PythonResolverCandidate(
        "demo",
        "1.0",
        "demo-1.0-py3-none-any.whl",
        "https://files.pythonhosted.org/packages/demo.whl",
        "a".repeat(64),
        100,
        null,
        false,
        emptyList(),
    )

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        return digest.joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }
}
