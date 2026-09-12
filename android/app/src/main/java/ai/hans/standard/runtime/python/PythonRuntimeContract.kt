package ai.hans.standard.runtime.python

import ai.hans.standard.codex.JsonContract
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

object PythonRuntimeContract {
    const val PROTOCOL_VERSION = 1
    const val REQUIRED_PYTHON_SERIES = "3.14"
    const val BASELINE_ENVIRONMENT_DIGEST =
        "8739c76e681f900923b900c9df0ef75cf421d39cabb54650c4b9ad19b6a76d85"

    const val MAX_REQUEST_BYTES = 256 * 1024
    const val MAX_RESULT_BYTES = 512 * 1024
    const val MAX_STATE_BYTES = 32 * 1024
    const val MAX_EVENT_BYTES = 64 * 1024
    const val MAX_CAPABILITY_BYTES = 64 * 1024
    const val MAX_BINDER_CHUNK_BYTES = 32 * 1024
    const val MAX_IDENTIFIER_BYTES = 128
    const val MAX_SOURCE_BYTES = 192 * 1024
    const val MAX_ERROR_MESSAGE_BYTES = 8 * 1024
    const val MAX_CAPABILITIES = 64
    const val MAX_CAPABILITY_WAIT_MILLIS = 30_000L
    const val MIN_EXECUTION_WINDOW_MILLIS = 100L
    const val MAX_EXECUTION_WINDOW_MILLIS = 60L * 60L * 1_000L
    const val DEFAULT_STDOUT_BYTES = 256 * 1024
    const val DEFAULT_STDERR_BYTES = 128 * 1024
    const val DEFAULT_RESULT_BYTES = 256 * 1024
    const val DEFAULT_MAXIMUM_EVENTS = 4_096
    const val MAX_STREAM_BYTES = 1024 * 1024
    const val MAXIMUM_EVENTS = 16_384

    private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    private val SAFE_NAME = Regex("[A-Za-z_][A-Za-z0-9_.]{0,255}")
    private val SAFE_PLUGIN_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val SHA_256 = Regex("[a-f0-9]{64}")
    private val SAFE_ERROR = Regex("[a-z0-9][a-z0-9_.:-]{0,95}")
    private val REQUIRED_IMPORTS = setOf("json", "asyncio", "sqlite3", "_ssl", "_hans_android")

    fun validate(request: PythonExecutionRequest, nowElapsedRealtimeMillis: Long) {
        require(SAFE_ID.matches(request.requestId)) { "Invalid Python request id" }
        require(SAFE_ID.matches(request.idempotencyKey)) { "Invalid Python idempotency key" }
        require(SHA_256.matches(request.environmentDigest)) { "Invalid environment digest" }
        require(request.allowedCapabilities.size <= MAX_CAPABILITIES) { "Too many capabilities" }
        request.allowedCapabilities.forEach {
            require(SAFE_PLUGIN_ID.matches(it)) { "Invalid capability identifier" }
        }
        validateJsonValue(request.argumentsJson, MAX_REQUEST_BYTES, "Python arguments")
        request.workspaceHandle?.let {
            require(SHA_256.matches(it)) { "Invalid workspace handle" }
        }
        validateEntrypoint(request.entrypoint)
        val remaining = request.limits.deadlineElapsedRealtimeMillis - nowElapsedRealtimeMillis
        require(remaining in MIN_EXECUTION_WINDOW_MILLIS..MAX_EXECUTION_WINDOW_MILLIS) {
            "Python execution deadline is outside the supported window"
        }
        require(request.limits.maximumStdoutBytes in 1..MAX_STREAM_BYTES)
        require(request.limits.maximumStderrBytes in 1..MAX_STREAM_BYTES)
        require(request.limits.maximumResultBytes in 1..MAX_RESULT_BYTES)
        require(request.limits.maximumEvents in 1..MAXIMUM_EVENTS)
    }

    fun encodeRequest(request: PythonExecutionRequest, nowElapsedRealtimeMillis: Long): String {
        validate(request, nowElapsedRealtimeMillis)
        val entrypoint = JSONObject().put("kind", request.entrypoint.kind.wireName)
        when (request.entrypoint.kind) {
            PythonEntrypointKind.CODE -> entrypoint.put("source", request.entrypoint.source)
            PythonEntrypointKind.MODULE -> entrypoint
                .put("module", request.entrypoint.module)
                .put("function", request.entrypoint.function)
            PythonEntrypointKind.PLUGIN -> entrypoint
                .put("pluginId", request.entrypoint.pluginId)
                .put("relativePath", request.entrypoint.relativePath)
                .put("function", request.entrypoint.function)
        }
        return JsonContract.encodeBounded(
            JSONObject()
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("requestId", request.requestId)
                .put("idempotencyKey", request.idempotencyKey)
                .put("environmentDigest", request.environmentDigest)
                .put("entrypoint", entrypoint)
                .put("arguments", parseJsonValue(request.argumentsJson))
                .put("workspaceHandle", request.workspaceHandle ?: JSONObject.NULL)
                .put("allowedCapabilities", JSONArray(request.allowedCapabilities.sorted()))
                .put(
                    "limits",
                    JSONObject()
                        .put("deadlineElapsedRealtimeMillis", request.limits.deadlineElapsedRealtimeMillis)
                        .put("maximumStdoutBytes", request.limits.maximumStdoutBytes)
                        .put("maximumStderrBytes", request.limits.maximumStderrBytes)
                        .put("maximumResultBytes", request.limits.maximumResultBytes)
                        .put("maximumEvents", request.limits.maximumEvents),
                ),
            MAX_REQUEST_BYTES,
        )
    }

    fun decodeRequest(raw: String, nowElapsedRealtimeMillis: Long): PythonExecutionRequest {
        val json = JsonContract.parseObject(raw, MAX_REQUEST_BYTES)
        requireProtocol(json)
        JsonContract.requireOnlyKeys(
            json,
            setOf(
                "protocolVersion", "requestId", "idempotencyKey", "environmentDigest",
                "entrypoint", "arguments", "workspaceHandle", "allowedCapabilities", "limits",
            ),
            "Python execution request",
        )
        val entrypointJson = JsonContract.requiredObject(json, "entrypoint")
        val kind = PythonEntrypointKind.entries.singleOrNull {
            it.wireName == JsonContract.requiredString(entrypointJson, "kind", 16)
        } ?: throw IllegalArgumentException("Unsupported Python entrypoint kind")
        val entrypoint = when (kind) {
            PythonEntrypointKind.CODE -> PythonEntrypoint(
                kind = kind,
                source = JsonContract.requiredString(entrypointJson, "source", MAX_SOURCE_BYTES, true),
            )
            PythonEntrypointKind.MODULE -> PythonEntrypoint(
                kind = kind,
                module = JsonContract.requiredString(entrypointJson, "module", 256),
                function = JsonContract.requiredString(entrypointJson, "function", 256),
            )
            PythonEntrypointKind.PLUGIN -> PythonEntrypoint(
                kind = kind,
                pluginId = JsonContract.requiredString(entrypointJson, "pluginId", MAX_IDENTIFIER_BYTES),
                relativePath = JsonContract.requiredString(entrypointJson, "relativePath", 1_024),
                function = JsonContract.requiredString(entrypointJson, "function", 256),
            )
        }
        val allowed = JsonContract.requiredArray(json, "allowedCapabilities")
        val capabilities = buildSet {
            repeat(allowed.length()) { index ->
                add((allowed.opt(index) as? String) ?: error("Capability must be a string"))
            }
        }
        val limits = JsonContract.requiredObject(json, "limits")
        val request = PythonExecutionRequest(
            requestId = JsonContract.requiredString(json, "requestId", MAX_IDENTIFIER_BYTES),
            idempotencyKey = JsonContract.requiredString(json, "idempotencyKey", MAX_IDENTIFIER_BYTES),
            environmentDigest = JsonContract.requiredString(json, "environmentDigest", 64),
            entrypoint = entrypoint,
            argumentsJson = jsonValueToString(json.opt("arguments")),
            workspaceHandle = JsonContract.optionalString(json, "workspaceHandle", MAX_IDENTIFIER_BYTES),
            allowedCapabilities = capabilities,
            limits = PythonResourceLimits(
                deadlineElapsedRealtimeMillis = JsonContract.requiredLong(
                    limits,
                    "deadlineElapsedRealtimeMillis",
                ),
                maximumStdoutBytes = limits.requiredInt("maximumStdoutBytes"),
                maximumStderrBytes = limits.requiredInt("maximumStderrBytes"),
                maximumResultBytes = limits.requiredInt("maximumResultBytes"),
                maximumEvents = limits.requiredInt("maximumEvents"),
            ),
        )
        validate(request, nowElapsedRealtimeMillis)
        return request
    }

    fun encodeResult(result: PythonExecutionResult): String = JsonContract.encodeBounded(
        JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("requestId", result.requestId)
            .put("status", result.status.name)
            .put("value", result.valueJson?.let(::parseJsonValue) ?: JSONObject.NULL)
            .put("errorCode", result.errorCode ?: JSONObject.NULL)
            .put("errorMessage", result.errorMessage ?: JSONObject.NULL)
            .put("metrics", result.metricsJson?.let(::parseJsonValue) ?: JSONObject.NULL),
        MAX_RESULT_BYTES,
    )

    fun decodeResult(raw: String, expectedRequestId: String? = null): PythonExecutionResult {
        val json = JsonContract.parseObject(raw, MAX_RESULT_BYTES)
        requireProtocol(json)
        val requestId = JsonContract.requiredString(json, "requestId", MAX_IDENTIFIER_BYTES)
        require(expectedRequestId == null || requestId == expectedRequestId) {
            "Python result request id does not match"
        }
        val status = runCatching {
            PythonExecutionStatus.valueOf(JsonContract.requiredString(json, "status", 64))
        }.getOrElse { throw IllegalArgumentException("Unsupported Python execution status") }
        val errorCode = JsonContract.optionalString(json, "errorCode", 96)
        errorCode?.let { require(SAFE_ERROR.matches(it)) { "Unsafe Python error code" } }
        return PythonExecutionResult(
            requestId = requestId,
            status = status,
            valueJson = json.nullableJsonValue("value"),
            errorCode = errorCode,
            errorMessage = JsonContract.optionalString(json, "errorMessage", MAX_ERROR_MESSAGE_BYTES),
            metricsJson = json.nullableJsonValue("metrics"),
        )
    }

    fun decodeReadiness(raw: String): PythonRuntimeReadiness {
        val json = JsonContract.parseObject(raw, MAX_STATE_BYTES)
        requireProtocol(json)
        val ready = JsonContract.requiredBoolean(json, "ready")
        val imports = json.optJSONArray("verifiedImports") ?: JSONArray()
        val verifiedImports = buildSet {
            repeat(imports.length()) { index ->
                add((imports.opt(index) as? String) ?: error("Readiness import must be a string"))
            }
        }
        val readiness = PythonRuntimeReadiness(
            ready = ready,
            pythonVersion = JsonContract.optionalString(json, "pythonVersion", 64),
            abi = JsonContract.optionalString(json, "abi", 64),
            stdlibDigest = JsonContract.optionalString(json, "stdlibDigest", 64),
            verifiedImports = verifiedImports,
            writableNativeImports = JsonContract.optionalBoolean(json, "writableNativeImports", true),
            errorCode = JsonContract.optionalString(json, "errorCode", 96),
            detail = JsonContract.optionalString(json, "detail", MAX_ERROR_MESSAGE_BYTES),
        )
        if (ready) {
            require(readiness.pythonVersion?.startsWith("$REQUIRED_PYTHON_SERIES.") == true ||
                readiness.pythonVersion == REQUIRED_PYTHON_SERIES) { "Unexpected Python version" }
            require(readiness.abi == "arm64-v8a" || readiness.abi == "x86_64") {
                "Unexpected Python ABI"
            }
            require(readiness.stdlibDigest?.matches(SHA_256) == true) {
                "Invalid stdlib digest"
            }
            require(!readiness.writableNativeImports) { "Writable native imports are enabled" }
            require(readiness.verifiedImports.containsAll(REQUIRED_IMPORTS)) {
                "Required Python modules did not pass readiness"
            }
        }
        return readiness
    }

    fun encodeSnapshot(snapshot: PythonRuntimeSnapshot): String = JsonContract.encodeBounded(
        JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("phase", snapshot.phase.name)
            .put("generation", snapshot.generation)
            .put("runtimePid", snapshot.runtimePid)
            .put("activeRequestId", snapshot.activeRequestId ?: JSONObject.NULL)
            .put("detail", snapshot.detail ?: JSONObject.NULL)
            .put(
                "readiness",
                snapshot.readiness?.let(::readinessJson) ?: JSONObject.NULL,
            ),
        MAX_STATE_BYTES,
    )

    fun decodeSnapshot(raw: String): PythonRuntimeSnapshot {
        val json = JsonContract.parseObject(raw, MAX_STATE_BYTES)
        requireProtocol(json)
        val readiness = (json.opt("readiness") as? JSONObject)?.let {
            decodeReadiness(it.put("protocolVersion", PROTOCOL_VERSION).toString())
        }
        return PythonRuntimeSnapshot(
            phase = PythonRuntimePhase.valueOf(JsonContract.requiredString(json, "phase", 64)),
            generation = JsonContract.requiredLong(json, "generation"),
            runtimePid = json.requiredInt("runtimePid"),
            activeRequestId = JsonContract.optionalString(json, "activeRequestId", MAX_IDENTIFIER_BYTES),
            readiness = readiness,
            detail = JsonContract.optionalString(json, "detail", MAX_ERROR_MESSAGE_BYTES),
        )
    }

    fun capabilityFailure(request: PythonCapabilityRequest, code: String): String =
        capabilityFailure(request.requestId, request.sequence, code)

    fun capabilityFailure(requestId: String, sequence: Long, code: String): String =
        JsonContract.encodeBounded(
            JSONObject()
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("requestId", requestId)
                .put("sequence", sequence)
                .put("status", "failed")
                .put("errorCode", code.takeIf(SAFE_ERROR::matches) ?: "capability_failed"),
            MAX_CAPABILITY_BYTES,
        )

    fun validateCapabilityResult(
        raw: String,
        expectedRequestId: String,
        expectedSequence: Long,
    ): String {
        val json = JsonContract.parseObject(raw, MAX_CAPABILITY_BYTES)
        requireProtocol(json)
        require(JsonContract.requiredString(json, "requestId", MAX_IDENTIFIER_BYTES) == expectedRequestId)
        require(JsonContract.requiredLong(json, "sequence") == expectedSequence)
        require(JsonContract.requiredString(json, "status", 32) in setOf("succeeded", "failed"))
        return JsonContract.encodeBounded(json, MAX_CAPABILITY_BYTES)
    }

    private fun validateEntrypoint(entrypoint: PythonEntrypoint) {
        when (entrypoint.kind) {
            PythonEntrypointKind.CODE -> {
                require(entrypoint.source != null)
                JsonContract.requireUtf8Bound(entrypoint.source, MAX_SOURCE_BYTES, "Python source")
            }
            PythonEntrypointKind.MODULE -> {
                require(entrypoint.module?.matches(SAFE_NAME) == true) { "Invalid Python module" }
                require(entrypoint.function?.matches(SAFE_NAME) == true) { "Invalid Python function" }
            }
            PythonEntrypointKind.PLUGIN -> {
                require(entrypoint.pluginId?.matches(SAFE_PLUGIN_ID) == true) { "Invalid plugin id" }
                require(entrypoint.function?.matches(SAFE_NAME) == true) { "Invalid Python function" }
                val path = entrypoint.relativePath ?: error("Plugin entrypoint path is missing")
                require(path.length <= 1_024 && !path.startsWith('/') && '\\' !in path) {
                    "Invalid plugin entrypoint path"
                }
                require(path.split('/').none { it.isBlank() || it == "." || it == ".." }) {
                    "Plugin entrypoint escapes its plugin"
                }
            }
        }
    }

    private fun requireProtocol(json: JSONObject) {
        require(json.requiredInt("protocolVersion") == PROTOCOL_VERSION) {
            "Unsupported Python runtime protocol"
        }
    }

    private fun readinessJson(value: PythonRuntimeReadiness): JSONObject = JSONObject()
        .put("ready", value.ready)
        .put("pythonVersion", value.pythonVersion ?: JSONObject.NULL)
        .put("abi", value.abi ?: JSONObject.NULL)
        .put("stdlibDigest", value.stdlibDigest ?: JSONObject.NULL)
        .put("verifiedImports", JSONArray(value.verifiedImports.sorted()))
        .put("writableNativeImports", value.writableNativeImports)
        .put("errorCode", value.errorCode ?: JSONObject.NULL)
        .put("detail", value.detail ?: JSONObject.NULL)

    private fun validateJsonValue(raw: String, limit: Int, label: String) {
        JsonContract.requireUtf8Bound(raw, limit, label)
        parseJsonValue(raw)
    }

    private fun parseJsonValue(raw: String): Any {
        val wrapped = JSONArray("[$raw]")
        require(wrapped.length() == 1) { "Expected one JSON value" }
        return wrapped.opt(0) ?: JSONObject.NULL
    }

    private fun jsonValueToString(value: Any?): String = JSONArray().put(value ?: JSONObject.NULL)
        .toString()
        .let { it.substring(1, it.length - 1) }

    private fun JSONObject.nullableJsonValue(key: String): String? =
        if (!has(key) || isNull(key)) null else jsonValueToString(opt(key))

    private fun JSONObject.requiredInt(key: String): Int {
        val value = JsonContract.requiredLong(this, key)
        require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "Integer at '$key' is out of range" }
        return value.toInt()
    }
}
