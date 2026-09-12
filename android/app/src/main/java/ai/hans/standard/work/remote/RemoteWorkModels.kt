package ai.hans.standard.work.remote

import ai.hans.standard.artifacts.ArtifactHandle
import ai.hans.standard.codex.JsonContract
import ai.hans.standard.workspace.WorkspaceHandle
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@JvmInline
value class RemoteWorkerId(val value: String) {
    init {
        require(SAFE_ID.matches(value) && ".." !in value) { "Invalid remote worker id" }
    }
}

@JvmInline
value class RemoteWorkOperationId(val value: String) {
    init {
        require(SAFE_ID.matches(value) && ".." !in value) { "Invalid remote work operation id" }
    }
}

@JvmInline
value class RemoteWorkAdapterId(val value: String) {
    init {
        require(SAFE_ADAPTER.matches(value) && ".." !in value) { "Invalid remote work adapter id" }
        require(value.substringBefore('.') !in LOCAL_DEVICE_ADAPTER_ROOTS) {
            "Android and phone actions cannot be delegated"
        }
    }
}

data class RemoteWorkerConnectionIdentity(
    val workerId: RemoteWorkerId,
    val endpoint: String,
    val serverSpkiSha256: String,
    val deviceKeyAlias: String,
) {
    init {
        val uri = URI(endpoint)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
            "Remote worker endpoint must be HTTPS"
        }
        require(uri.rawUserInfo == null && uri.fragment == null && uri.query == null) {
            "Remote worker endpoint contains unsupported URL components"
        }
        require(uri.path.isNullOrEmpty() || uri.path == "/") {
            "Remote worker endpoint must be an origin"
        }
        require(SHA_256.matches(serverSpkiSha256)) { "Invalid remote worker server key pin" }
        require(SAFE_KEY_ALIAS.matches(deviceKeyAlias)) { "Invalid remote worker key alias" }
    }

    override fun toString(): String =
        "RemoteWorkerConnectionIdentity(workerId=${workerId.value}, endpoint=<redacted>)"

    /** Stable non-secret correlation value; endpoint and key aliases never enter tool output. */
    val configurationDigest: String
        get() = sha256(
            listOf(
                "hans-remote-worker-identity-v1",
                workerId.value,
                URI(endpoint).normalize().toASCIIString().removeSuffix("/") + "/",
                serverSpkiSha256,
                deviceKeyAlias,
            ).joinToString("\n", postfix = "\n").toByteArray(StandardCharsets.UTF_8),
        )
}

/**
 * UI/setup factory. The Android-Keystore alias is deterministic but reveals neither endpoint nor
 * certificate pin and is never accepted as user input.
 */
internal fun remoteWorkerConnectionIdentity(
    workerId: String,
    endpoint: String,
    serverSpkiSha256: String,
): RemoteWorkerConnectionIdentity {
    val id = RemoteWorkerId(workerId)
    val normalizedEndpoint = URI(endpoint).normalize().toASCIIString().removeSuffix("/") + "/"
    val aliasDigest = sha256(
        listOf(
            "hans-remote-worker-device-key-v1",
            id.value,
            normalizedEndpoint,
            serverSpkiSha256,
        ).joinToString("\n", postfix = "\n").toByteArray(StandardCharsets.UTF_8),
    )
    return RemoteWorkerConnectionIdentity(
        workerId = id,
        endpoint = normalizedEndpoint,
        serverSpkiSha256 = serverSpkiSha256,
        deviceKeyAlias = "hans.remote.${aliasDigest.take(40)}",
    )
}

/**
 * Local, exact allow-list entry. A server-discovered adapter is never executable merely because
 * it exists: both its stable id and its semantic version must match this user-controlled record.
 */
data class RemoteWorkAdapterApproval(
    val id: RemoteWorkAdapterId,
    val version: String,
) {
    init {
        require(REMOTE_WORK_ADAPTER_VERSION.matches(version)) {
            "Invalid remote work adapter version"
        }
    }
}

/**
 * Immutable connection configuration. Remote work is deliberately opt-in and therefore disabled
 * when callers omit [enabled]. No endpoint, pin, or key alias is exposed by [toString].
 */
data class RemoteWorkerConfiguration(
    val identity: RemoteWorkerConnectionIdentity,
    val approvedAdapters: List<RemoteWorkAdapterApproval>,
    val enabled: Boolean = false,
) {
    init {
        require(approvedAdapters.size <= MAX_REMOTE_ADAPTERS)
        require(approvedAdapters.map { it.id }.distinct().size == approvedAdapters.size) {
            "Duplicate remote work adapter approval"
        }
    }

    override fun toString(): String =
        "RemoteWorkerConfiguration(workerId=${identity.workerId.value}, enabled=$enabled, " +
            "approvedAdapters=${approvedAdapters.map { it.id.value }})"
}

@JvmInline
value class RemoteExecutionId(val value: String) {
    init {
        require(SAFE_ID.matches(value) && ".." !in value) { "Invalid remote execution id" }
    }
}

data class RemoteWorkLimits(
    val maximumRuntimeSeconds: Int = 15 * 60,
    val maximumOutputBytes: Long = 512L * 1024L * 1024L,
    val maximumArtifacts: Int = 32,
) {
    init {
        require(maximumRuntimeSeconds in 1..MAX_RUNTIME_SECONDS)
        require(maximumOutputBytes in 1..MAX_OUTPUT_BYTES)
        require(maximumArtifacts in 1..MAX_ARTIFACTS)
    }

    companion object {
        const val MAX_RUNTIME_SECONDS = 24 * 60 * 60
        const val MAX_OUTPUT_BYTES = 4L * 1024L * 1024L * 1024L
        const val MAX_ARTIFACTS = 256
    }
}

data class RemoteWorkRequest(
    val operationId: RemoteWorkOperationId,
    val worker: RemoteWorkerId,
    val adapter: RemoteWorkAdapterId,
    val workspace: WorkspaceHandle?,
    val inputArtifacts: List<ArtifactHandle>,
    val argumentsJson: String,
    val limits: RemoteWorkLimits,
) {
    init {
        require(inputArtifacts.size <= MAX_INPUT_ARTIFACTS) { "Too many remote work inputs" }
        require(inputArtifacts.distinct().size == inputArtifacts.size) {
            "Duplicate remote work input"
        }
        JsonContract.parseObject(argumentsJson, MAX_ARGUMENT_BYTES)
    }

    companion object {
        const val MAX_INPUT_ARTIFACTS = 64
        const val MAX_ARGUMENT_BYTES = 256 * 1024
    }
}

internal object RemoteWorkRequestDigest {
    fun compute(
        request: RemoteWorkRequest,
        payloads: List<RemoteWorkPayloadDescriptor>,
    ): String {
        require(payloads.size <= 10_000)
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
            digest.update(':'.code.toByte())
            digest.update(bytes)
            digest.update('\n'.code.toByte())
        }
        field("hans-remote-work-request-v1")
        field(request.operationId.value)
        field(request.worker.value)
        field(request.adapter.value)
        field(request.workspace?.value.orEmpty())
        request.inputArtifacts.forEach { field(it.value) }
        field(request.argumentsJson)
        field(request.limits.maximumRuntimeSeconds.toString())
        field(request.limits.maximumOutputBytes.toString())
        field(request.limits.maximumArtifacts.toString())
        payloads.forEach { payload ->
            field(payload.kind.name)
            field(payload.workspace?.value.orEmpty())
            field(payload.relativePath.orEmpty())
            field(payload.artifact?.value.orEmpty())
            field(payload.byteCount.toString())
            field(payload.sha256)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

enum class RemoteWorkPhase {
    PREPARED,
    SUBMIT_INTENT,
    ACCEPTED,
    RUNNING,
    RESULT_PROVEN,
    IMPORTING,
    IMPORT_COMMITTED,
    CANCEL_INTENT,
    CANCELLED,
    FAILED,
    AMBIGUOUS,
}

data class RemoteWorkResultArtifact(
    val opaqueRemoteId: String,
    val displayName: String,
    val mimeType: String,
    val byteCount: Long,
    val sha256: String,
) {
    init {
        require(SAFE_ID.matches(opaqueRemoteId) && ".." !in opaqueRemoteId) {
            "Invalid remote artifact id"
        }
        require(displayName.isNotBlank() && displayName.length <= 255)
        require(displayName.none { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() })
        require(MIME_TYPE.matches(mimeType))
        require(byteCount in 0..RemoteWorkLimits.MAX_OUTPUT_BYTES)
        require(SHA_256.matches(sha256))
    }
}

internal val SAFE_ID = Regex("[A-Za-z0-9._:-]{1,128}")
internal val SAFE_ADAPTER = Regex("[a-z][a-z0-9._:-]{0,127}")
internal val SAFE_KEY_ALIAS = Regex("[A-Za-z0-9._:-]{1,128}")
internal val SHA_256 = Regex("[0-9a-f]{64}")
internal val REMOTE_WORK_ADAPTER_VERSION = Regex("[0-9]+(?:\\.[0-9]+){0,3}")
private val LOCAL_DEVICE_ADAPTER_ROOTS = setOf(
    "accessibility",
    "android",
    "bluetooth",
    "camera",
    "contacts",
    "device",
    "intent",
    "location",
    "mobile",
    "nfc",
    "notifications",
    "phone",
    "sensors",
    "sms",
    "telephony",
    "wifi",
)
private val MIME_TYPE = Regex(
    "[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]{0,126}/[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]{0,126}",
)

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }
