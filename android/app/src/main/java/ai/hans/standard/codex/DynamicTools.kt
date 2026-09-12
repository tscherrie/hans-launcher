package ai.hans.standard.codex

import java.nio.charset.StandardCharsets
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/** Exact experimental dynamic-tool subset from Codex App Server 0.154.0 v2. */
data class DynamicToolFunctionSpec(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
    val deferLoading: Boolean = false,
) {
    init {
        requireDynamicToolName(name, MAX_DYNAMIC_TOOL_NAME_CHARS, "Dynamic tool name")
        requireBoundedText(description, MAX_DYNAMIC_TOOL_DESCRIPTION_BYTES, "Tool description")
        val schema = JsonContract.parseObject(inputSchemaJson, MAX_DYNAMIC_TOOL_SCHEMA_BYTES)
        require(schema.optString("type") == "object") {
            "Dynamic tool input schema must describe an object"
        }
    }

    internal fun toJson(): JSONObject = JSONObject()
        .put("type", "function")
        .put("name", name)
        .put("description", description)
        .put("inputSchema", JsonContract.parseObject(inputSchemaJson, MAX_DYNAMIC_TOOL_SCHEMA_BYTES))
        .apply { if (deferLoading) put("deferLoading", true) }
}

data class DynamicToolNamespaceSpec(
    val name: String,
    val description: String,
    val tools: List<DynamicToolFunctionSpec>,
) {
    init {
        requireDynamicToolName(name, MAX_DYNAMIC_TOOL_NAMESPACE_CHARS, "Dynamic tool namespace")
        require(name !in RESERVED_DYNAMIC_TOOL_NAMESPACES) {
            "Dynamic tool namespace is reserved"
        }
        requireBoundedText(description, MAX_DYNAMIC_TOOL_DESCRIPTION_BYTES, "Namespace description")
        // Native App Server 0.151.0 validates Unicode scalar count, not UTF-8 bytes
        // or UTF-16 code units. Keep the separate local byte budget as well.
        require(description.codePointCount(0, description.length) <= DynamicToolLimits.MAX_NAMESPACE_DESCRIPTION_CODE_POINTS) {
            "Dynamic tool namespace description is too long"
        }
        require(tools.isNotEmpty()) { "Dynamic tool namespace must not be empty" }
        require(tools.size <= MAX_DYNAMIC_TOOLS_PER_NAMESPACE) { "Too many dynamic tools" }
        require(tools.map(DynamicToolFunctionSpec::name).toSet().size == tools.size) {
            "Duplicate dynamic tool name"
        }
    }

    internal fun toJson(): JSONObject = JSONObject()
        .put("type", "namespace")
        .put("name", name)
        .put("description", description)
        .put(
            "tools",
            JSONArray().also { array -> tools.forEach { tool -> array.put(tool.toJson()) } },
        )
}

sealed interface ServerRequestId {
    data class Number(val value: Long) : ServerRequestId

    data class Text(val value: String) : ServerRequestId {
        init {
            require(value.isNotBlank()) { "Server request id must not be blank" }
            require(value.length <= ProtocolLimits.MAX_REQUEST_ID_CHARS) {
                "Server request id is too long"
            }
            require(value.none(Char::isISOControl)) { "Server request id contains controls" }
        }
    }
}

data class DynamicToolCallParams(
    val threadId: String,
    val turnId: String,
    val callId: String,
    val namespace: String?,
    val tool: String,
    /** Immutable, bounded canonical JSON value. Android tools require an object. */
    val argumentsJson: String,
) {
    init {
        requireOpaqueDynamicId(threadId, "Dynamic tool thread id")
        requireOpaqueDynamicId(turnId, "Dynamic tool turn id")
        requireOpaqueDynamicId(callId, "Dynamic tool call id")
        namespace?.let {
            requireDynamicToolName(it, MAX_DYNAMIC_TOOL_NAMESPACE_CHARS, "Dynamic tool namespace")
        }
        requireDynamicToolName(tool, MAX_DYNAMIC_TOOL_NAME_CHARS, "Dynamic tool name")
        JsonContract.requireUtf8Bound(
            argumentsJson,
            MAX_DYNAMIC_TOOL_ARGUMENT_BYTES,
            "Dynamic tool arguments",
        )
    }
}

data class DynamicToolServerCall(
    val requestId: ServerRequestId,
    val params: DynamicToolCallParams,
)

sealed interface ServerRequestDecodeResult {
    data object NotServerRequest : ServerRequestDecodeResult
    data class DynamicToolCall(val call: DynamicToolServerCall) : ServerRequestDecodeResult
    data class Unsupported(val requestId: ServerRequestId) : ServerRequestDecodeResult
    data class Malformed(val requestId: ServerRequestId?) : ServerRequestDecodeResult
}

data class DynamicToolExecutionResult(
    val contentText: String,
    val success: Boolean,
    /**
     * Bounded data URLs returned as App Server `inputImage` content items. Keeping images out of
     * [contentText] lets the model actually inspect them instead of receiving an opaque base64
     * string inside JSON. Dynamic tools currently need only one visual observation, but the
     * collection shape follows the pinned App Server protocol and keeps the contract reusable.
     */
    val imageUrls: List<String> = emptyList(),
) {
    init {
        JsonContract.parseObject(contentText, MAX_DYNAMIC_TOOL_OUTPUT_TEXT_BYTES)
        require(imageUrls.size <= MAX_DYNAMIC_TOOL_OUTPUT_IMAGES) {
            "Too many dynamic tool output images"
        }
        imageUrls.forEach { imageUrl ->
            require(imageUrl.length <= MAX_DYNAMIC_TOOL_IMAGE_URL_CHARACTERS) {
                "Dynamic tool image URL is too large"
            }
            require(DYNAMIC_TOOL_IMAGE_DATA_URL.matches(imageUrl)) {
                "Dynamic tool image URL must be a bounded image data URL"
            }
        }
    }
}

/** Read-only cancellation signal supplied by lifecycle-bound callers such as automations. */
fun interface DynamicToolCancellation {
    fun isCancellationRequested(): Boolean

    companion object {
        val NONE = DynamicToolCancellation { false }
    }
}

/**
 * Tells a caller whether cancellation won before any externally observable operation started.
 * Once an executor crosses an external-effect boundary, the outcome must be treated as ambiguous.
 */
enum class DynamicToolCancellationDisposition {
    CANCELLED_BEFORE_EXTERNAL_EFFECT,
    EXTERNAL_EFFECT_MAY_HAVE_STARTED,
}

interface DynamicToolExecutionHandle {
    /** Non-blocking and idempotent; repeated calls return the same safety disposition. */
    fun cancel(): DynamicToolCancellationDisposition
}

/**
 * Shared race-safe gate for cancellable executors. It cancels queued ExecutorService futures where
 * possible and linearizes cancellation against confirmation/broker/platform entry points.
 */
internal class DynamicToolExecutionGate(
    private val parentCancellation: DynamicToolCancellation,
    private val completion: (DynamicToolExecutionResult) -> Unit,
) : DynamicToolCancellation, DynamicToolExecutionHandle {
    private val transitionLock = Any()
    private val locallyCancelled = AtomicBoolean(false)
    private val externalEffectStarted = AtomicBoolean(false)
    private val completionAccepted = AtomicBoolean(false)
    private val future = AtomicReference<Future<*>?>(null)
    private val completionTask = AtomicReference<FutureTask<Unit>?>(null)

    override fun isCancellationRequested(): Boolean =
        locallyCancelled.get() || runCatching(parentCancellation::isCancellationRequested)
            .getOrDefault(true)

    override fun cancel(): DynamicToolCancellationDisposition {
        val disposition = synchronized(transitionLock) {
            locallyCancelled.set(true)
            disposition()
        }
        runCatching { future.get()?.cancel(false) }
        runCatching { completionTask.get()?.cancel(false) }
        return disposition
    }

    /** Must immediately precede every confirmation, broker, or platform boundary. */
    fun markExternalEffectStarted(): Boolean = synchronized(transitionLock) {
        if (isCancellationRequested()) {
            false
        } else {
            externalEffectStarted.set(true)
            true
        }
    }

    /** Schedule without interrupting a task which may already be inside a platform API. */
    fun schedule(executor: Executor, task: () -> Unit): Boolean {
        if (isCancellationRequested()) return true
        val guarded = Runnable {
            if (!isCancellationRequested()) task()
        }
        return try {
            if (executor is ExecutorService) {
                val submitted = executor.submit(guarded)
                future.set(submitted)
                if (isCancellationRequested()) submitted.cancel(false)
            } else {
                executor.execute(guarded)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun complete(result: DynamicToolExecutionResult) {
        if (isCancellationRequested()) return
        if (!completionAccepted.compareAndSet(false, true)) return
        val callback = FutureTask<Unit> {
            if (!isCancellationRequested()) runCatching { completion(result) }
        }
        completionTask.set(callback)
        // Covers cancellation after completion was claimed but before the callback task was
        // published. FutureTask then linearizes cancel(false) against callback entry without
        // invoking user code while transitionLock is held.
        if (isCancellationRequested()) callback.cancel(false)
        callback.run()
    }

    fun disposition(): DynamicToolCancellationDisposition =
        if (externalEffectStarted.get()) {
            DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED
        } else {
            DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT
        }
}

/**
 * Implementations must schedule execution asynchronously and invoke completion at most once.
 * The controller still deduplicates completions and never trusts this contract for correctness.
 */
interface DynamicToolExecutor {
    val specs: List<DynamicToolNamespaceSpec>

    fun execute(
        call: DynamicToolCallParams,
        completion: (DynamicToolExecutionResult) -> Unit,
    )

    /**
     * Lifecycle-bound variant. Existing interactive executors remain source-compatible, while the
     * conservative default treats entering a legacy executor as a possible external effect.
     */
    fun executeCancellable(
        call: DynamicToolCallParams,
        cancellation: DynamicToolCancellation,
        completion: (DynamicToolExecutionResult) -> Unit,
    ): DynamicToolExecutionHandle {
        val gate = DynamicToolExecutionGate(cancellation, completion)
        if (!gate.markExternalEffectStarted()) return gate
        execute(call, gate::complete)
        return gate
    }

    /** Pure, bounded failure projection used if asynchronous dispatch itself throws. */
    fun failureResult(call: DynamicToolCallParams, code: String): DynamicToolExecutionResult
}

internal object DynamicToolProtocol {
    fun decodeServerRequest(raw: String): ServerRequestDecodeResult {
        if (raw.toByteArray(StandardCharsets.UTF_8).size > MAX_DYNAMIC_TOOL_REQUEST_BYTES) {
            return ServerRequestDecodeResult.Malformed(extractBoundedId(raw))
        }
        val envelope = try {
            JsonContract.parseObject(raw, MAX_DYNAMIC_TOOL_REQUEST_BYTES)
        } catch (_: Exception) {
            return ServerRequestDecodeResult.Malformed(null)
        }
        if (!envelope.has("method") || !envelope.has("id")) {
            return ServerRequestDecodeResult.NotServerRequest
        }
        val requestId = parseServerRequestId(envelope.opt("id"))
            ?: return ServerRequestDecodeResult.Malformed(null)
        val method = try {
            JsonContract.requiredString(envelope, "method", ProtocolLimits.MAX_METHOD_CHARS)
        } catch (_: Exception) {
            return ServerRequestDecodeResult.Malformed(requestId)
        }
        if (method != DYNAMIC_TOOL_CALL_METHOD) {
            return ServerRequestDecodeResult.Unsupported(requestId)
        }
        return try {
            JsonContract.requireOnlyKeys(
                envelope,
                setOf("id", "method", "params"),
                "dynamic tool server request",
            )
            val params = JsonContract.requiredObject(envelope, "params")
            JsonContract.requireOnlyKeys(
                params,
                setOf("arguments", "callId", "namespace", "threadId", "tool", "turnId"),
                "dynamic tool call params",
            )
            if (!params.has("arguments")) {
                throw MalformedEnvelopeException("Missing dynamic tool arguments")
            }
            // org.json does not expose valueToString consistently on Android and the JVM
            // test artifact. Serializing through a one-element array preserves every JSON
            // value kind without falling back to an object's arbitrary toString().
            val wrappedArguments = JSONArray().put(params.opt("arguments")).toString()
            val arguments = wrappedArguments.substring(1, wrappedArguments.length - 1)
            JsonContract.requireUtf8Bound(
                arguments,
                MAX_DYNAMIC_TOOL_ARGUMENT_BYTES,
                "Dynamic tool arguments",
            )
            ServerRequestDecodeResult.DynamicToolCall(
                DynamicToolServerCall(
                    requestId = requestId,
                    params = DynamicToolCallParams(
                        threadId = JsonContract.requiredString(
                            params,
                            "threadId",
                            ProtocolLimits.MAX_OPAQUE_ID_CHARS,
                        ),
                        turnId = JsonContract.requiredString(
                            params,
                            "turnId",
                            ProtocolLimits.MAX_OPAQUE_ID_CHARS,
                        ),
                        callId = JsonContract.requiredString(
                            params,
                            "callId",
                            ProtocolLimits.MAX_OPAQUE_ID_CHARS,
                        ),
                        namespace = JsonContract.optionalString(
                            params,
                            "namespace",
                            MAX_DYNAMIC_TOOL_NAMESPACE_CHARS,
                        ),
                        tool = JsonContract.requiredString(
                            params,
                            "tool",
                            MAX_DYNAMIC_TOOL_NAME_CHARS,
                        ),
                        argumentsJson = arguments,
                    ),
                ),
            )
        } catch (_: Exception) {
            ServerRequestDecodeResult.Malformed(requestId)
        }
    }

    fun response(
        requestId: ServerRequestId,
        result: DynamicToolExecutionResult,
    ): String {
        val contentItems = JSONArray().put(
            JSONObject()
                .put("type", "inputText")
                .put("text", result.contentText),
        )
        result.imageUrls.forEach { imageUrl ->
            contentItems.put(
                JSONObject()
                    .put("type", "inputImage")
                    .put("imageUrl", imageUrl),
            )
        }
        return encodeResponse(
            JSONObject()
            .putServerRequestId(requestId)
            .put(
                "result",
                JSONObject()
                    .put("contentItems", contentItems)
                    .put("success", result.success),
            ),
        )
    }

    fun error(requestId: ServerRequestId, code: Int, message: String): String {
        require(code < 0) { "JSON-RPC error code must be negative" }
        require(
            message in setOf(
                "method_not_supported",
                "invalid_dynamic_tool_request",
                "dynamic_tool_capacity_exceeded",
                "dynamic_tool_execution_failed",
                "dynamic_tool_blocked_untrusted_notification_context",
                "remote_control_consent_not_active",
            ),
        )
        return encodeResponse(
            JSONObject()
                .putServerRequestId(requestId)
                .put(
                    "error",
                    JSONObject()
                        .put("code", code)
                        .put("message", message),
                ),
        )
    }

    private fun encodeResponse(value: JSONObject): String = JsonContract.encodeBounded(
        value,
        ProtocolLimits.MAX_OUTBOUND_FRAME_BYTES,
    )

    private fun JSONObject.putServerRequestId(id: ServerRequestId): JSONObject = apply {
        when (id) {
            is ServerRequestId.Number -> put("id", id.value)
            is ServerRequestId.Text -> put("id", id.value)
        }
    }

    private fun extractBoundedId(raw: String): ServerRequestId? {
        if (raw.length > ProtocolLimits.MAX_INBOUND_FRAME_BYTES) return null
        return runCatching { parseServerRequestId(JSONObject(raw).opt("id")) }.getOrNull()
    }

    private fun parseServerRequestId(value: Any?): ServerRequestId? = when (value) {
        is Byte -> ServerRequestId.Number(value.toLong())
        is Short -> ServerRequestId.Number(value.toLong())
        is Int -> ServerRequestId.Number(value.toLong())
        is Long -> ServerRequestId.Number(value)
        is String -> runCatching { ServerRequestId.Text(value) }.getOrNull()
        else -> null
    }
}

internal fun List<DynamicToolNamespaceSpec>.toDynamicToolsJson(): JSONArray {
    require(size <= DynamicToolLimits.MAX_NAMESPACES) { "Too many dynamic tool namespaces" }
    require(map(DynamicToolNamespaceSpec::name).toSet().size == size) {
        "Duplicate dynamic tool namespace"
    }
    require(sumOf { it.tools.size } <= DynamicToolLimits.MAX_FUNCTIONS) {
        "Too many dynamic tool functions"
    }
    return JSONArray().also { array ->
        forEach { namespace -> array.put(namespace.toJson()) }
    }
}

private fun requireOpaqueDynamicId(value: String, label: String) {
    require(value.isNotBlank()) { "$label must not be blank" }
    require(value.length <= ProtocolLimits.MAX_OPAQUE_ID_CHARS) { "$label is too long" }
    require(value.none(Char::isISOControl)) { "$label contains controls" }
}

private fun requireDynamicToolName(value: String, maxCharacters: Int, label: String) {
    require(value.length in 1..maxCharacters && DYNAMIC_TOOL_NAME.matches(value)) {
        "$label is invalid"
    }
}

private fun requireBoundedText(value: String, maxBytes: Int, label: String) {
    require(value.isNotBlank()) { "$label must not be blank" }
    JsonContract.requireUtf8Bound(value, maxBytes, label)
}

private val DYNAMIC_TOOL_NAME = Regex("[A-Za-z0-9_-]+")
private val RESERVED_DYNAMIC_TOOL_NAMESPACES = setOf(
    "functions",
    "multi_tool_use",
    "file_search",
    "web",
    "browser",
    "image_gen",
    "computer",
    "container",
    "terminal",
    "python",
    "python_user_visible",
    "api_tool",
    "tool_search",
    "submodel_delegator",
)
private const val DYNAMIC_TOOL_CALL_METHOD = "item/tool/call"
private const val MAX_DYNAMIC_TOOL_NAME_CHARS = 128
private const val MAX_DYNAMIC_TOOL_NAMESPACE_CHARS = 64
/** Shared local bounds for publication and wire encoding; not an upstream API limit. */
internal object DynamicToolLimits {
    const val MAX_NAMESPACES = 256
    const val MAX_FUNCTIONS = 4_096
    /** Native 0.151.0 boundary, independently probed at 1024/1025 including emoji. */
    const val MAX_NAMESPACE_DESCRIPTION_CODE_POINTS = 1_024
}

private const val MAX_DYNAMIC_TOOLS_PER_NAMESPACE = 64
private const val MAX_DYNAMIC_TOOL_DESCRIPTION_BYTES = 4 * 1024
private const val MAX_DYNAMIC_TOOL_SCHEMA_BYTES = 32 * 1024
private const val MAX_DYNAMIC_TOOL_REQUEST_BYTES = 256 * 1024
const val MAX_DYNAMIC_TOOL_ARGUMENT_BYTES = 64 * 1024
const val MAX_DYNAMIC_TOOL_OUTPUT_TEXT_BYTES = 512 * 1024
private const val MAX_DYNAMIC_TOOL_OUTPUT_IMAGES = 2
private const val MAX_DYNAMIC_TOOL_IMAGE_URL_CHARACTERS = 2 * 1024 * 1024
private val DYNAMIC_TOOL_IMAGE_DATA_URL = Regex(
    "data:image/(?:jpeg|png|webp);base64,[A-Za-z0-9+/]+={0,2}",
)
