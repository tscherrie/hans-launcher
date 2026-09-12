package ai.hans.standard.runtime.python

import ai.hans.standard.codex.JsonContract
import java.nio.charset.StandardCharsets
import org.json.JSONObject

internal sealed interface PythonNativeEvent {
    val requestId: String
    val sequence: Long

    data class Stream(
        override val requestId: String,
        override val sequence: Long,
        val kind: PythonStreamKind,
        val text: String,
    ) : PythonNativeEvent

    data class Capability(
        override val requestId: String,
        override val sequence: Long,
        val capabilityName: String,
        val normalizedJson: String,
    ) : PythonNativeEvent
}

internal object PythonNativeEventCodec {
    fun decode(raw: String): PythonNativeEvent {
        val json = JsonContract.parseObject(raw, PythonRuntimeContract.MAX_EVENT_BYTES)
        require(JsonContract.requiredLong(json, "protocolVersion") ==
            PythonRuntimeContract.PROTOCOL_VERSION.toLong())
        val type = JsonContract.requiredString(json, "type", 32)
        val requestId = JsonContract.requiredString(
            json,
            "requestId",
            PythonRuntimeContract.MAX_IDENTIFIER_BYTES,
        )
        val sequence = JsonContract.requiredLong(json, "sequence")
        require(sequence > 0) { "Python event sequence must be positive" }
        return when (type) {
            "stream" -> {
                JsonContract.requireOnlyKeys(
                    json,
                    setOf("protocolVersion", "type", "requestId", "sequence", "stream", "text"),
                    "Python stream event",
                )
                val kind = when (JsonContract.requiredString(json, "stream", 16)) {
                    "stdout" -> PythonStreamKind.STDOUT
                    "stderr" -> PythonStreamKind.STDERR
                    "progress" -> PythonStreamKind.PROGRESS
                    else -> error("Unsupported Python stream")
                }
                PythonNativeEvent.Stream(
                    requestId = requestId,
                    sequence = sequence,
                    kind = kind,
                    text = JsonContract.requiredString(
                        json,
                        "text",
                        PythonRuntimeContract.MAX_EVENT_BYTES,
                        allowBlank = true,
                    ),
                )
            }
            "capability_request" -> {
                JsonContract.requireOnlyKeys(
                    json,
                    setOf("protocolVersion", "type", "requestId", "sequence", "capability"),
                    "Python capability event",
                )
                val capability = JsonContract.requiredObject(json, "capability")
                val name = JsonContract.requiredString(
                    capability,
                    "name",
                    PythonRuntimeContract.MAX_IDENTIFIER_BYTES,
                )
                PythonNativeEvent.Capability(
                    requestId = requestId,
                    sequence = sequence,
                    capabilityName = name,
                    normalizedJson = JsonContract.encodeBounded(
                        json,
                        PythonRuntimeContract.MAX_CAPABILITY_BYTES,
                    ),
                )
            }
            else -> error("Unsupported Python native event")
        }
    }
}

/** Enforces monotone event order and separate stdout/stderr limits before Binder delivery. */
internal class PythonOutputLimiter(
    private val request: PythonExecutionRequest,
) {
    private var lastSequence = 0L
    private var eventCount = 0
    private var stdoutBytes = 0
    private var stderrBytes = 0

    @Synchronized
    fun accept(event: PythonNativeEvent.Stream): List<ByteArray> {
        require(event.requestId == request.requestId) { "Python event request id does not match" }
        require(event.sequence > lastSequence) { "Python event sequence is not monotone" }
        lastSequence = event.sequence
        eventCount += 1
        check(eventCount <= request.limits.maximumEvents) { "Python emitted too many events" }
        val bytes = event.text.toByteArray(StandardCharsets.UTF_8)
        when (event.kind) {
            PythonStreamKind.STDOUT -> {
                stdoutBytes += bytes.size
                check(stdoutBytes <= request.limits.maximumStdoutBytes) { "Python stdout limit exceeded" }
            }
            PythonStreamKind.STDERR -> {
                stderrBytes += bytes.size
                check(stderrBytes <= request.limits.maximumStderrBytes) { "Python stderr limit exceeded" }
            }
            PythonStreamKind.PROGRESS -> check(bytes.size <= PythonRuntimeContract.MAX_EVENT_BYTES)
        }
        if (bytes.isEmpty()) return listOf(ByteArray(0))
        return bytes.asList().chunked(PythonRuntimeContract.MAX_BINDER_CHUNK_BYTES).map { chunk ->
            chunk.toByteArray()
        }
    }
}

internal fun PythonExecutionResult.toDynamicToolProjection(
    stdout: String,
    stderr: String,
): String = JSONObject()
    .put("status", if (succeeded) "succeeded" else "failed")
    .put("executionStatus", status.name.lowercase())
    .put("requestId", requestId)
    .put("value", valueJson?.let { org.json.JSONArray("[$it]").opt(0) } ?: JSONObject.NULL)
    .put("stdout", stdout)
    .put("stderr", stderr)
    .put("errorCode", errorCode ?: JSONObject.NULL)
    .put("errorMessage", errorMessage ?: JSONObject.NULL)
    .toString()
