package ai.hans.standard.plugins

import ai.hans.standard.runtime.python.LocalPythonExecutionHandle
import ai.hans.standard.runtime.python.PythonExecutionHandle
import ai.hans.standard.runtime.python.PythonExecutionRequest
import ai.hans.standard.runtime.python.PythonExecutionResult
import ai.hans.standard.runtime.python.PythonExecutionStatus
import ai.hans.standard.runtime.python.PythonResultCallback
import ai.hans.standard.runtime.python.PythonRuntimeGateway
import ai.hans.standard.runtime.python.PythonRuntimePhase
import ai.hans.standard.runtime.python.PythonRuntimeSnapshot
import ai.hans.standard.runtime.python.PythonStreamListener
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PythonRuntimePluginEntrypointProberTest {
    @Test
    fun provesExactDeclaredBindingsWithoutCapabilities() {
        val captured = AtomicReference<PythonExecutionRequest>()
        val runtime = immediateRuntime { request ->
            captured.set(request)
            PythonExecutionResult(
                request.requestId,
                PythonExecutionStatus.SUCCEEDED,
                JSONObject().put("resolved", JSONArray().put("open-door")).toString(),
            )
        }

        val proven = PythonRuntimePluginEntrypointProber(
            runtime = runtime,
            nowElapsedRealtimeMillis = { 10_000L },
            timeoutMillis = 1_000L,
        ).prove(
            pluginId = "garage",
            environmentDigest = "a".repeat(64),
            bindings = listOf(PythonPluginEntrypointBinding("open-door", "garage/actions.py", "run")),
            cancellation = NeverCancelled,
        )

        assertEquals(setOf("open-door"), proven)
        assertEquals(emptySet<String>(), captured.get().allowedCapabilities)
        assertEquals("a".repeat(64), captured.get().environmentDigest)
        val arguments = JSONObject(captured.get().argumentsJson)
        assertEquals("garage", arguments.getString("pluginId"))
        assertEquals("a".repeat(64), arguments.getString("environmentDigest"))
        assertEquals("garage/actions.py", arguments.getJSONArray("entrypoints").getJSONObject(0)
            .getString("relativePath"))
        assertEquals("run", arguments.getJSONArray("entrypoints").getJSONObject(0)
            .getString("callable"))
        assertTrue(captured.get().entrypoint.source.orEmpty().contains("plugin_import_scope"))
        assertTrue(captured.get().entrypoint.source.orEmpty().contains("resolve_callable"))
        assertTrue(!captured.get().entrypoint.source.orEmpty().contains("_archive_read"))
    }

    @Test
    fun rejectsUndeclaredDuplicateAndMalformedRuntimeProofs() {
        val binding = PythonPluginEntrypointBinding("open-door", "garage.py", "run")
        listOf(
            JSONObject().put("resolved", JSONArray().put("other")).toString(),
            JSONObject().put("resolved", JSONArray().put("open-door").put("open-door")).toString(),
            JSONObject().put("other", JSONArray()).toString(),
        ).forEach { proof ->
            assertThrows(IllegalArgumentException::class.java) {
                PythonRuntimePluginEntrypointProber(
                    immediateRuntime { request ->
                        PythonExecutionResult(request.requestId, PythonExecutionStatus.SUCCEEDED, proof)
                    },
                    nowElapsedRealtimeMillis = { 10_000L },
                    timeoutMillis = 1_000L,
                ).prove("garage", "a".repeat(64), listOf(binding), NeverCancelled)
            }
        }
    }

    @Test
    fun rejectsFailedRuntimeExecution() {
        assertThrows(IllegalArgumentException::class.java) {
            PythonRuntimePluginEntrypointProber(
                immediateRuntime { request ->
                    PythonExecutionResult(request.requestId, PythonExecutionStatus.PYTHON_EXCEPTION)
                },
                nowElapsedRealtimeMillis = { 10_000L },
                timeoutMillis = 1_000L,
            ).prove(
                "garage",
                "a".repeat(64),
                listOf(PythonPluginEntrypointBinding("open-door", "garage.py", "run")),
                NeverCancelled,
            )
        }
    }

    @Test
    fun cancellationInterruptsTheIsolatedWorker() {
        val entered = CountDownLatch(1)
        val cancelledHandle = AtomicBoolean(false)
        val cancellation = MutableCancellation()
        val runtime = object : PythonRuntimeGateway {
            override fun snapshot() = PythonRuntimeSnapshot(PythonRuntimePhase.RUNNING, 1, 123)

            override fun execute(
                request: PythonExecutionRequest,
                streamListener: PythonStreamListener,
                callback: PythonResultCallback,
            ): PythonExecutionHandle {
                entered.countDown()
                return object : PythonExecutionHandle {
                    override fun cancel(): Boolean = cancelledHandle.compareAndSet(false, true)
                }
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Throwable?> {
                runCatching {
                    PythonRuntimePluginEntrypointProber(
                        runtime,
                        nowElapsedRealtimeMillis = { 10_000L },
                        timeoutMillis = 5_000L,
                    ).prove(
                        "garage",
                        "a".repeat(64),
                        listOf(PythonPluginEntrypointBinding("open-door", "garage.py", "run")),
                        cancellation,
                    )
                }.exceptionOrNull()
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            cancellation.cancel()
            assertTrue(result.get(1, TimeUnit.SECONDS) is PluginRuntimePreparationCancelledException)
            assertTrue(cancelledHandle.get())
        } finally {
            executor.shutdownNow()
        }
    }

    private fun immediateRuntime(result: (PythonExecutionRequest) -> PythonExecutionResult) =
        object : PythonRuntimeGateway {
            override fun snapshot() = PythonRuntimeSnapshot(PythonRuntimePhase.READY, 1, 123)

            override fun execute(
                request: PythonExecutionRequest,
                streamListener: PythonStreamListener,
                callback: PythonResultCallback,
            ): PythonExecutionHandle {
                callback.onResult(result(request))
                return LocalPythonExecutionHandle { false }
            }
        }

    private data object NeverCancelled : PluginRuntimePreparationCancellation {
        override fun isCancellationRequested() = false
        override fun onCancel(action: () -> Unit) = Closeable {}
    }

    private class MutableCancellation : PluginRuntimePreparationCancellation {
        private val cancelled = AtomicBoolean(false)
        private val listener = AtomicReference<(() -> Unit)?>(null)

        override fun isCancellationRequested() = cancelled.get()

        override fun onCancel(action: () -> Unit): Closeable {
            listener.set(action)
            if (cancelled.get()) listener.getAndSet(null)?.invoke()
            return Closeable { listener.compareAndSet(action, null) }
        }

        fun cancel() {
            if (cancelled.compareAndSet(false, true)) listener.getAndSet(null)?.invoke()
        }
    }
}
