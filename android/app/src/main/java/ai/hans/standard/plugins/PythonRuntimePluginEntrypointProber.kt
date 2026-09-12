package ai.hans.standard.plugins

import ai.hans.standard.runtime.python.PythonEntrypoint
import ai.hans.standard.runtime.python.PythonEntrypointKind
import ai.hans.standard.runtime.python.PythonExecutionRequest
import ai.hans.standard.runtime.python.PythonResourceLimits
import ai.hans.standard.runtime.python.PythonResultCallback
import ai.hans.standard.runtime.python.PythonRuntimeContract
import ai.hans.standard.runtime.python.PythonRuntimeGateway
import ai.hans.standard.runtime.python.PythonStreamListener
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/** Proves source loading and public callable identity in the real isolated worker. */
internal class PythonRuntimePluginEntrypointProber(
    private val runtime: PythonRuntimeGateway,
    private val nowElapsedRealtimeMillis: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : PythonPluginEntrypointProber {
    init {
        require(timeoutMillis in PythonRuntimeContract.MIN_EXECUTION_WINDOW_MILLIS..
            PythonRuntimeContract.MAX_EXECUTION_WINDOW_MILLIS)
    }

    override fun prove(
        pluginId: String,
        environmentDigest: String,
        bindings: List<PythonPluginEntrypointBinding>,
        cancellation: PluginRuntimePreparationCancellation,
    ): Set<String> {
        cancellation.throwIfCancellationRequested()
        if (bindings.isEmpty()) return emptySet()
        require(bindings.size <= MAX_ENTRYPOINTS) { "Too many Python entrypoints to prove" }
        val requestId = "pyprobe-${UUID.randomUUID().toString().replace("-", "")}".take(128)
        val request = PythonExecutionRequest(
            requestId = requestId,
            idempotencyKey = requestId,
            environmentDigest = environmentDigest,
            entrypoint = PythonEntrypoint(
                kind = PythonEntrypointKind.CODE,
                source = PROBE_SOURCE,
            ),
            argumentsJson = JSONObject()
                .put("pluginId", pluginId)
                .put("environmentDigest", environmentDigest)
                .put(
                    "entrypoints",
                    JSONArray(bindings.map { binding ->
                        JSONObject()
                            .put("id", binding.requirementId)
                            .put("relativePath", binding.relativePath)
                            .put("callable", binding.callableName ?: JSONObject.NULL)
                    }),
                )
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
        val result = AtomicReference<ai.hans.standard.runtime.python.PythonExecutionResult?>()
        val done = CountDownLatch(1)
        val handle = runtime.execute(
            request = request,
            streamListener = PythonStreamListener { true },
            callback = PythonResultCallback {
                result.compareAndSet(null, it)
                done.countDown()
            },
        )
        cancellation.onCancel {
            handle.cancel()
            done.countDown()
        }.use {
            val completed = try {
                done.await(timeoutMillis + WAIT_GRACE_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                handle.cancel()
                throw PluginRuntimePreparationCancelledException()
            }
            if (!completed) {
                handle.cancel()
                error("Python entrypoint proof timed out")
            }
        }
        cancellation.throwIfCancellationRequested()
        val execution = result.get() ?: error("Python entrypoint proof returned no result")
        require(execution.succeeded && execution.valueJson != null) {
            "Python entrypoint proof failed: ${execution.errorCode ?: execution.status.name.lowercase()}"
        }
        val projection = JSONObject(execution.valueJson)
        require(projection.keys().asSequence().toSet() == setOf("resolved")) {
            "Python entrypoint proof returned an invalid shape"
        }
        val resolved = projection.getJSONArray("resolved")
        require(resolved.length() <= bindings.size) { "Python entrypoint proof returned too many ids" }
        val declared = bindings.mapTo(linkedSetOf(), PythonPluginEntrypointBinding::requirementId)
        return buildSet {
            repeat(resolved.length()) { index ->
                val id = resolved.getString(index)
                require(id in declared && add(id)) {
                    "Python entrypoint proof returned an undeclared or duplicate id"
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        const val WAIT_GRACE_MILLIS = 2_000L
        const val MAX_ENTRYPOINTS = 64
        val PROBE_SOURCE = """
            import hans_fd_importer

            _hans_resolved = []
            for _hans_entry in arguments.get("entrypoints", []):
                try:
                    _hans_id = _hans_entry["id"]
                    _hans_path = _hans_entry["relativePath"]
                    _hans_callable = _hans_entry.get("callable")
                    with hans_fd_importer.plugin_import_scope(
                        arguments.get("pluginId"),
                        arguments.get("environmentDigest"),
                    ) as _hans_scope:
                        if _hans_callable is None:
                            _hans_scope.import_entry(_hans_path)
                        else:
                            # Deliberately resolve only. Proof must never invoke plugin code.
                            _hans_scope.resolve_callable(_hans_path, _hans_callable)
                    _hans_resolved.append(_hans_id)
                except Exception:
                    continue
            result = {"resolved": _hans_resolved}
        """.trimIndent()
    }
}
