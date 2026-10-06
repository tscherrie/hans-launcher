package ai.hans.standard.runtime.python

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolCancellationDisposition
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.JsonContract
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/**
 * Routes a Python `_hans_android.call()` back through Hans's ordinary dynamic-tool contracts.
 *
 * CPython never receives an Android Context, Binder, filesystem path, or permission bypass. A
 * capability name must match an installed dynamic-tool spec exactly, and the selected executor
 * remains responsible for live capability probes, Android grants, confirmation policy,
 * idempotency, and postcondition reporting.
 */
class PythonDynamicCapabilityGateway(
    executors: List<DynamicToolExecutor>,
) : PythonCapabilityGateway {
    private data class Route(
        val namespace: String,
        val tool: String,
        val executor: DynamicToolExecutor,
    )

    private val routes: Map<String, Route> = buildMap {
        executors.forEach { executor ->
            executor.specs.forEach { namespace ->
                namespace.tools.forEach { tool ->
                    val capability = capabilityName(namespace.name, tool.name)
                    require(put(capability, Route(namespace.name, tool.name, executor)) == null) {
                        "Duplicate Python capability route: $capability"
                    }
                }
            }
        }
    }

    /** Exact names which may be put into a Python request's per-execution allowlist. */
    val supportedCapabilities: Set<String> = routes.keys.toSortedSet()

    override fun execute(
        request: PythonCapabilityRequest,
        completion: (String) -> Unit,
    ): PythonCapabilityHandle {
        val completed = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val delegate = AtomicReference<DynamicToolExecutionHandle?>()

        fun finish(value: String) {
            if (!cancelled.get() && completed.compareAndSet(false, true)) {
                completion(value)
            }
        }

        val decoded = runCatching { decode(request) }.getOrElse {
            finish(PythonRuntimeContract.capabilityFailure(request, "invalid_capability_request"))
            return inertHandle(cancelled)
        }
        val route = routes[decoded.name]
        if (route == null) {
            finish(PythonRuntimeContract.capabilityFailure(request, "capability_unavailable"))
            return inertHandle(cancelled)
        }

        val call = DynamicToolCallParams(
            threadId = syntheticId("thread", request.requestId),
            turnId = syntheticId("turn", request.requestId),
            callId = syntheticId("call", "${request.requestId}:${request.sequence}:${decoded.name}"),
            namespace = route.namespace,
            tool = route.tool,
            argumentsJson = decoded.arguments.toString(),
        )
        val cancellation = DynamicToolCancellation { cancelled.get() }
        val handle = runCatching {
            route.executor.executeCancellable(call, cancellation) { result ->
                finish(project(request, result))
            }
        }.getOrElse {
            finish(PythonRuntimeContract.capabilityFailure(request, "capability_gateway_failed"))
            null
        }
        delegate.set(handle)
        if (cancelled.get()) handle?.cancel()

        return object : PythonCapabilityHandle {
            override fun onQuiescent(listener: () -> Unit): Boolean =
                // Delegate is published only after executeCancellable returns. Do not substitute
                // its logical result, cancellation disposition or an inner callback for this.
                handle?.onQuiescent(listener) ?: false

            override fun cancel() {
                if (cancelled.compareAndSet(false, true)) {
                    delegate.get()?.cancel()
                }
            }
        }
    }

    private fun decode(request: PythonCapabilityRequest): DecodedCapability {
        val envelope = JsonContract.parseObject(
            request.capabilityJson,
            PythonRuntimeContract.MAX_CAPABILITY_BYTES,
        )
        JsonContract.requireOnlyKeys(
            envelope,
            setOf("protocolVersion", "type", "requestId", "sequence", "capability"),
            "Python capability request",
        )
        require(JsonContract.requiredLong(envelope, "protocolVersion") ==
            PythonRuntimeContract.PROTOCOL_VERSION.toLong())
        require(JsonContract.requiredString(envelope, "type", 64) == "capability_request")
        require(JsonContract.requiredString(envelope, "requestId", 128) == request.requestId)
        require(JsonContract.requiredLong(envelope, "sequence") == request.sequence)
        val capability = JsonContract.requiredObject(envelope, "capability")
        JsonContract.requireOnlyKeys(
            capability,
            setOf("name", "arguments"),
            "Python capability",
        )
        val name = JsonContract.requiredString(capability, "name", MAX_CAPABILITY_NAME_BYTES)
        require(name.matches(CAPABILITY_NAME)) { "Invalid Python capability name" }
        val arguments = JsonContract.requiredObject(capability, "arguments")
        JsonContract.requireUtf8Bound(
            arguments.toString(),
            MAX_CAPABILITY_ARGUMENT_BYTES,
            "Python capability arguments",
        )
        return DecodedCapability(name, arguments)
    }

    private fun project(
        request: PythonCapabilityRequest,
        result: DynamicToolExecutionResult,
    ): String {
        if (result.imageUrls.isNotEmpty()) {
            return PythonRuntimeContract.capabilityFailure(
                request,
                "capability_binary_requires_artifact",
            )
        }
        val dynamicValue = runCatching {
            JsonContract.parseObject(result.contentText, MAX_DYNAMIC_RESULT_BYTES)
        }.getOrElse {
            return PythonRuntimeContract.capabilityFailure(request, "invalid_capability_result")
        }
        val projected = JSONObject()
            .put("protocolVersion", PythonRuntimeContract.PROTOCOL_VERSION)
            .put("requestId", request.requestId)
            .put("sequence", request.sequence)
            .put("status", if (result.success) "succeeded" else "failed")
            .put("value", dynamicValue)
        if (!result.success) {
            projected.put(
                "errorCode",
                dynamicValue.optString("errorCode")
                    .takeIf { it.matches(SAFE_ERROR) }
                    ?: "capability_failed",
            )
        }
        return runCatching {
            PythonRuntimeContract.validateCapabilityResult(
                projected.toString(),
                request.requestId,
                request.sequence,
            )
        }.getOrElse {
            PythonRuntimeContract.capabilityFailure(request, "capability_result_too_large")
        }
    }

    private fun inertHandle(cancelled: AtomicBoolean) = object : PythonCapabilityHandle {
        override fun cancel() {
            cancelled.set(true)
        }

        override fun onQuiescent(listener: () -> Unit): Boolean {
            listener()
            return true
        }
    }

    private data class DecodedCapability(
        val name: String,
        val arguments: JSONObject,
    )

    companion object {
        private const val MAX_CAPABILITY_NAME_BYTES = 200
        private const val MAX_CAPABILITY_ARGUMENT_BYTES = 48 * 1024
        private const val MAX_DYNAMIC_RESULT_BYTES = 48 * 1024
        private val CAPABILITY_NAME = Regex(
            "[A-Za-z][A-Za-z0-9_-]{0,63}\\.[A-Za-z][A-Za-z0-9_-]{0,63}",
        )
        private val SAFE_ERROR = Regex("[a-z0-9][a-z0-9_.:-]{0,95}")

        fun capabilityName(namespace: String, tool: String): String = "$namespace.$tool"

        private fun syntheticId(kind: String, value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
                .take(16)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return "py-$kind-$digest"
        }
    }
}
