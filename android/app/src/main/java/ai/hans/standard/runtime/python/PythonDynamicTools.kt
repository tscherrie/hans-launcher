package ai.hans.standard.runtime.python

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolCancellationDisposition
import ai.hans.standard.codex.DynamicToolExecutionGate
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolFunctionSpec
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import ai.hans.standard.codex.JsonContract
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

object PythonDynamicToolCatalog {
    const val NAMESPACE = "hans_python"

    val namespace = DynamicToolNamespaceSpec(
        name = NAMESPACE,
        description = "Hans's app-private, root-free CPython 3.14 runtime. It executes bounded " +
            "code and registered plugin entrypoints without pretending that Android has a global python3.",
        tools = listOf(
            function(
                "runtime_status",
                "Read the effective CPython worker state. This does not start the runtime.",
                emptySchema(),
            ),
            function(
                "run",
                "Run bounded Python source in Hans's private CPython worker without Android " +
                    "capabilities.",
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("code", stringSchema(PythonRuntimeContract.MAX_SOURCE_BYTES))
                            .put("arguments", JSONObject())
                            .put("workspaceHandle", sha256Schema())
                            .put("timeoutMillis", integerSchema(100, 3_600_000)),
                    )
                    .put("required", JSONArray().put("code"))
                    .put("additionalProperties", false),
            ),
            function(
                "run_module",
                "Call a Python function from an active, verified package environment without " +
                    "Android capabilities.",
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("module", stringSchema(256))
                            .put("function", stringSchema(256))
                            .put("pluginId", stringSchema(128))
                            .put("arguments", JSONObject())
                            .put("workspaceHandle", sha256Schema())
                            .put("timeoutMillis", integerSchema(100, 3_600_000)),
                    )
                    .put("required", JSONArray().put("module").put("function"))
                    .put("additionalProperties", false),
            ),
            function(
                "run_plugin_entrypoint",
                "Run a proven, committed Python entrypoint by its declared id inside one " +
                    "installed Hans plugin environment. Only its declared, currently installed " +
                    "capabilities are available.",
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("pluginId", stringSchema(128))
                            .put("entrypointId", stringSchema(64))
                            .put("arguments", JSONObject())
                            .put("workspaceHandle", sha256Schema())
                            .put("timeoutMillis", integerSchema(100, 3_600_000)),
                    )
                    .put(
                        "required",
                        JSONArray().put("pluginId").put("entrypointId"),
                    )
                    .put("additionalProperties", false),
            ),
            function(
                "packages_status",
                "Read the effective package-environment digest and runtime readiness.",
                emptySchema(),
            ),
        ),
    )

    private fun function(name: String, description: String, schema: JSONObject) =
        DynamicToolFunctionSpec(name, description, schema.toString())

    private fun emptySchema() = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject())
        .put("required", JSONArray())
        .put("additionalProperties", false)

    private fun stringSchema(maxLength: Int) = JSONObject()
        .put("type", "string")
        .put("maxLength", maxLength)

    private fun integerSchema(minimum: Int, maximum: Int) = JSONObject()
        .put("type", "integer")
        .put("minimum", minimum)
        .put("maximum", maximum)

    private fun sha256Schema() = JSONObject()
        .put("type", "string")
        .put("minLength", 64)
        .put("maxLength", 64)
        .put("pattern", "^[a-f0-9]{64}$")
}

class PythonDynamicToolExecutor(
    private val runtime: PythonRuntimeGateway,
    private val environmentSelectionProvider: PythonEnvironmentSelectionProvider =
        PythonEnvironmentSelectionProvider.BASELINE,
    private val pluginEntrypointResolver: PythonPluginEntrypointResolver =
        PythonPluginEntrypointResolver.DENY_ALL,
    private val capabilityProvider: (DynamicToolCallParams) -> Set<String> = { emptySet() },
    private val nowElapsedRealtimeMillis: () -> Long = android.os.SystemClock::elapsedRealtime,
) : DynamicToolExecutor {
    override val specs = listOf(PythonDynamicToolCatalog.namespace)

    override fun execute(
        call: DynamicToolCallParams,
        completion: (DynamicToolExecutionResult) -> Unit,
    ) {
        executeCancellable(call, DynamicToolCancellation.NONE, completion)
    }

    override fun executeCancellable(
        call: DynamicToolCallParams,
        cancellation: DynamicToolCancellation,
        completion: (DynamicToolExecutionResult) -> Unit,
    ): DynamicToolExecutionHandle {
        val gate = DynamicToolExecutionGate(cancellation, completion)
        if (call.namespace != PythonDynamicToolCatalog.NAMESPACE) {
            gate.complete(failureResult(call, "unknown_dynamic_tool_namespace"))
            return gate
        }
        if (call.tool in setOf("runtime_status", "packages_status")) {
            val args = runCatching { strictArguments(call.argumentsJson, emptySet()) }.getOrNull()
            if (args == null) {
                gate.complete(failureResult(call, "invalid_arguments"))
            } else {
                val snapshot = runtime.snapshot()
                val environmentSnapshot = environmentSelectionProvider.snapshot()
                val projection = JSONObject()
                    .put("status", "succeeded")
                    .put("runtime", JSONObject(PythonRuntimeContract.encodeSnapshot(snapshot)))
                    .put("environmentDigest", environmentSnapshot.effectiveEnvironmentDigest)
                if (call.tool == "packages_status") {
                    projection.put("packages", environmentSnapshot.toJson())
                }
                gate.complete(
                    DynamicToolExecutionResult(
                        projection.toString(),
                        success = true,
                    ),
                )
            }
            return gate
        }
        val request = runCatching { decodeRequest(call) }.getOrElse {
            gate.complete(
                failureResult(
                    call,
                    if (it is PythonEnvironmentUnavailableException) {
                        "python_environment_unavailable"
                    } else if (it is PythonPluginEntrypointRejectedException) {
                        it.errorCode
                    } else {
                        "invalid_arguments"
                    },
                ),
            )
            return gate
        }
        if (!gate.markExternalEffectStarted()) return gate
        val stdout = BoundedBytes(request.limits.maximumStdoutBytes)
        val stderr = BoundedBytes(request.limits.maximumStderrBytes)
        val delegate = AtomicReference<PythonExecutionHandle?>()
        val physical = PythonPhysicalExecutionReceipt()
        val handle = runtime.execute(
            request = request,
            streamListener = PythonStreamListener { chunk ->
                when (chunk.kind) {
                    PythonStreamKind.STDOUT -> stdout.append(chunk.payload)
                    PythonStreamKind.STDERR -> stderr.append(chunk.payload)
                    PythonStreamKind.PROGRESS -> true
                }
            },
            callback = PythonResultCallback { result ->
                val projection = runCatching {
                    result.toDynamicToolProjection(stdout.text(), stderr.text())
                }.getOrElse {
                    JSONObject()
                        .put("status", "failed")
                        .put("errorCode", "invalid_python_result")
                        .toString()
                }
                try { gate.complete(DynamicToolExecutionResult(projection, result.succeeded)) }
                finally { physical.callbackFinished() }
            },
        )
        delegate.set(handle)
        physical.publish(handle)
        if (gate.isCancellationRequested()) handle.cancel()
        return object : DynamicToolExecutionHandle {
            override fun onQuiescent(listener: () -> Unit): Boolean = physical.onQuiescent(listener)
            override fun cancel(): DynamicToolCancellationDisposition {
                val disposition = gate.cancel()
                delegate.get()?.cancel()
                return disposition
            }
        }
    }

    override fun failureResult(
        call: DynamicToolCallParams,
        code: String,
    ): DynamicToolExecutionResult = DynamicToolExecutionResult(
        JSONObject()
            .put("status", "failed")
            .put("errorCode", code.takeIf { it.matches(SAFE_CODE) } ?: "python_tool_failed")
            .put("tool", call.tool)
            .toString(),
        success = false,
    )

    private fun decodeRequest(call: DynamicToolCallParams): PythonExecutionRequest {
        val allowedKeys = when (call.tool) {
            "run" -> setOf("code", "arguments", "workspaceHandle", "timeoutMillis")
            "run_module" -> setOf(
                "module", "function", "pluginId", "arguments", "workspaceHandle", "timeoutMillis",
            )
            "run_plugin_entrypoint" ->
                setOf("pluginId", "entrypointId", "arguments", "workspaceHandle", "timeoutMillis")
            else -> error("Unknown Python tool")
        }
        val args = strictArguments(call.argumentsJson, allowedKeys)
        val selectedPluginId = when (call.tool) {
            "run_plugin_entrypoint" -> JsonContract.requiredString(args, "pluginId", 128)
            "run_module" -> JsonContract.optionalString(args, "pluginId", 128)
            else -> null
        }
        if (selectedPluginId != null) {
            require(PythonEnvironmentContract.isPluginId(selectedPluginId))
        }
        val environmentDigest = environmentSelectionProvider.digestFor(selectedPluginId)
        require(PythonEnvironmentContract.isSha256(environmentDigest))
        if (call.tool == "run_module" && selectedPluginId != null &&
            environmentDigest == PythonRuntimeContract.BASELINE_ENVIRONMENT_DIGEST
        ) {
            throw PythonEnvironmentUnavailableException()
        }
        val resolvedEntrypoint = when (call.tool) {
            "run" -> ResolvedEntrypoint(
                entrypoint = PythonEntrypoint(
                    kind = PythonEntrypointKind.CODE,
                    source = JsonContract.requiredString(
                        args,
                        "code",
                        PythonRuntimeContract.MAX_SOURCE_BYTES,
                        allowBlank = true,
                    ),
                ),
                declaredCapabilities = emptySet(),
            )
            "run_module" -> ResolvedEntrypoint(
                entrypoint = PythonEntrypoint(
                    kind = PythonEntrypointKind.MODULE,
                    module = JsonContract.requiredString(args, "module", 256),
                    function = JsonContract.requiredString(args, "function", 256),
                ),
                declaredCapabilities = emptySet(),
            )
            else -> resolvePluginEntrypoint(
                pluginId = checkNotNull(selectedPluginId),
                entrypointId = JsonContract.requiredString(args, "entrypointId", 64),
                activeEnvironmentDigest = environmentDigest,
            )
        }
        val timeout = JsonContract.optionalLong(args, "timeoutMillis") ?: DEFAULT_TIMEOUT_MILLIS
        require(timeout in PythonRuntimeContract.MIN_EXECUTION_WINDOW_MILLIS..
            PythonRuntimeContract.MAX_EXECUTION_WINDOW_MILLIS)
        val arguments = if (!args.has("arguments")) "{}" else {
            JSONArray().put(args.opt("arguments")).toString().let { it.substring(1, it.length - 1) }
        }
        val workspaceHandle = JsonContract.optionalString(args, "workspaceHandle", 64)?.also {
            require(PythonEnvironmentContract.isSha256(it)) { "Invalid Python workspace handle" }
        }
        val installedCapabilities = if (call.tool == "run_plugin_entrypoint") {
            capabilityProvider(call).toSet()
        } else {
            emptySet()
        }
        return PythonExecutionRequest(
            requestId = "py-${safeDigest(call.callId)}",
            idempotencyKey = "idem-${safeDigest(call.callId)}",
            environmentDigest = environmentDigest,
            entrypoint = resolvedEntrypoint.entrypoint,
            argumentsJson = arguments,
            workspaceHandle = workspaceHandle,
            allowedCapabilities = resolvedEntrypoint.declaredCapabilities
                .intersect(installedCapabilities),
            limits = PythonResourceLimits(
                deadlineElapsedRealtimeMillis = nowElapsedRealtimeMillis() + timeout,
            ),
        ).also { PythonRuntimeContract.validate(it, nowElapsedRealtimeMillis()) }
    }

    private fun resolvePluginEntrypoint(
        pluginId: String,
        entrypointId: String,
        activeEnvironmentDigest: String,
    ): ResolvedEntrypoint {
        require(PLUGIN_ENTRYPOINT_ID.matches(entrypointId))
        return when (
            val resolution = pluginEntrypointResolver.resolve(
                pluginId,
                entrypointId,
                activeEnvironmentDigest,
            )
        ) {
            is PythonPluginEntrypointResolution.Rejected ->
                throw PythonPluginEntrypointRejectedException(resolution.errorCode)
            is PythonPluginEntrypointResolution.Resolved -> {
                if (resolution.pluginId != pluginId ||
                    resolution.entrypointId != entrypointId ||
                    resolution.environmentDigest != activeEnvironmentDigest ||
                    !PythonEnvironmentContract.isSha256(resolution.sourceSha256)
                ) {
                    throw PythonPluginEntrypointRejectedException(
                        PythonPluginEntrypointRegistry.ERROR_REGISTRY_INVALID,
                    )
                }
                ResolvedEntrypoint(
                    entrypoint = PythonEntrypoint(
                        kind = PythonEntrypointKind.PLUGIN,
                        pluginId = pluginId,
                        relativePath = resolution.relativePath,
                        function = resolution.function,
                    ),
                    declaredCapabilities = resolution.declaredCapabilities,
                )
            }
        }
    }

    private data class ResolvedEntrypoint(
        val entrypoint: PythonEntrypoint,
        val declaredCapabilities: Set<String>,
    )

    private fun strictArguments(raw: String, allowed: Set<String>): JSONObject {
        val value = JsonContract.parseObject(raw, 64 * 1024)
        JsonContract.requireOnlyKeys(value, allowed, "Python dynamic tool arguments")
        return value
    }

    private fun safeDigest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .take(16)
        .joinToString("") { "%02x".format(it) }

    private class BoundedBytes(private val limit: Int) {
        private val output = ByteArrayOutputStream(minOf(limit, 16 * 1024))

        @Synchronized
        fun append(bytes: ByteArray): Boolean {
            if (output.size() + bytes.size > limit) return false
            output.write(bytes)
            return true
        }

        @Synchronized
        fun text(): String = output.toByteArray().toString(StandardCharsets.UTF_8)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 120_000L
        val SAFE_CODE = Regex("[a-z0-9_.:-]{1,96}")
        val PLUGIN_ENTRYPOINT_ID = Regex("[a-z][a-z0-9._-]{0,63}")
    }

    private class PythonEnvironmentUnavailableException : IllegalStateException()
    private class PythonPluginEntrypointRejectedException(val errorCode: String) :
        IllegalStateException()
}
