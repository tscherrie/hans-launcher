package ai.hans.standard.runtime.python

import java.io.BufferedInputStream
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/**
 * Executes the pre-activation import proof in the real isolated CPython worker.
 *
 * The store temporarily exposes only the staged read-only archive by digest. No capability is
 * granted, and a failed, malformed, timed-out, or interrupted proof can never activate it.
 */
class PythonEnvironmentRuntimeSelfTester(
    private val runtime: PythonRuntimeGateway,
    private val nowElapsedRealtimeMillis: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : PythonEnvironmentImportSelfTester {
    init {
        require(timeoutMillis in PythonRuntimeContract.MIN_EXECUTION_WINDOW_MILLIS..
            PythonRuntimeContract.MAX_EXECUTION_WINDOW_MILLIS)
    }

    override fun test(environment: PythonPreparedEnvironment): PythonImportSelfTestResult {
        val requestId = "envtest-${UUID.randomUUID().toString().replace("-", "")}".take(128)
        val request = PythonExecutionRequest(
            requestId = requestId,
            idempotencyKey = requestId,
            environmentDigest = sha256(environment.environmentArchive),
            entrypoint = PythonEntrypoint(
                kind = PythonEntrypointKind.CODE,
                source = IMPORT_PROOF_SOURCE,
            ),
            argumentsJson = JSONObject()
                .put("imports", JSONArray(environment.importNames.sorted()))
                .toString(),
            allowedCapabilities = emptySet(),
            limits = PythonResourceLimits(
                deadlineElapsedRealtimeMillis = nowElapsedRealtimeMillis() + timeoutMillis,
                maximumStdoutBytes = 8 * 1024,
                maximumStderrBytes = 16 * 1024,
                maximumResultBytes = 64 * 1024,
                maximumEvents = 64,
            ),
        )
        val result = AtomicReference<PythonExecutionResult?>()
        val latch = CountDownLatch(1)
        val handle = runCatching {
            runtime.execute(
                request = request,
                streamListener = PythonStreamListener { true },
                callback = PythonResultCallback {
                    result.compareAndSet(null, it)
                    latch.countDown()
                },
            )
        }.getOrElse {
            return PythonImportSelfTestResult(
                succeeded = false,
                errorCode = "self_test_start_failed",
                detail = it.message,
            )
        }
        val completed = try {
            latch.await(timeoutMillis + WAIT_GRACE_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            handle.cancel()
            return PythonImportSelfTestResult(false, errorCode = "self_test_interrupted")
        }
        if (!completed) {
            handle.cancel()
            return PythonImportSelfTestResult(false, errorCode = "self_test_timed_out")
        }
        val execution = result.get()
            ?: return PythonImportSelfTestResult(false, errorCode = "self_test_missing_result")
        if (!execution.succeeded) {
            return PythonImportSelfTestResult(
                succeeded = false,
                errorCode = execution.errorCode ?: execution.status.name.lowercase(Locale.US),
                detail = execution.errorMessage,
            )
        }
        val imported = runCatching {
            val projection = JSONObject(execution.valueJson ?: error("Missing import proof value"))
            require(projection.keys().asSequence().toSet() == setOf("imports"))
            val names = projection.getJSONArray("imports")
            buildSet {
                repeat(names.length()) { index ->
                    add(names.getString(index))
                }
            }
        }.getOrElse {
            return PythonImportSelfTestResult(
                succeeded = false,
                errorCode = "self_test_invalid_result",
                detail = it.message,
            )
        }
        return PythonImportSelfTestResult(
            succeeded = imported.containsAll(environment.importNames),
            importedNames = imported,
            errorCode = if (imported.containsAll(environment.importNames)) null else "imports_incomplete",
        )
    }

    private fun sha256(file: java.io.File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        const val WAIT_GRACE_MILLIS = 2_000L
        val IMPORT_PROOF_SOURCE = """
            import importlib
            _hans_imported = []
            for _hans_name in arguments.get("imports", []):
                importlib.import_module(_hans_name)
                _hans_imported.append(_hans_name)
            result = {"imports": _hans_imported}
        """.trimIndent()
    }
}
