package ai.hans.standard.workspace

import ai.hans.standard.artifacts.ArtifactHandle
import ai.hans.standard.artifacts.ArtifactOrigin
import ai.hans.standard.artifacts.AtomicArtifactStore
import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolExecutionGate
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolFunctionSpec
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import ai.hans.standard.codex.JsonContract
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executor
import org.json.JSONArray
import org.json.JSONObject

object WorkspaceDynamicToolCatalog {
    const val WORKSPACE_NAMESPACE = "hans_workspace"
    const val ARTIFACT_NAMESPACE = "hans_artifact"

    val workspaceNamespace = DynamicToolNamespaceSpec(
        name = WORKSPACE_NAMESPACE,
        description =
            "Immutable app-private workspaces. Use handles, never guessed Android filesystem paths. " +
                "Every edit creates a new snapshot and preserves the old one.",
        tools = listOf(
            function("create", "Create an empty private workspace snapshot.", JSONObject()),
            function(
                "list",
                "List a bounded page of files and hashes in one workspace snapshot.",
                JSONObject()
                    .put("workspaceHandle", string(64))
                    .put("offset", integer(0, MAX_LIST_OFFSET.toLong()))
                    .put("limit", integer(1, MAX_LIST_ENTRIES.toLong())),
                listOf("workspaceHandle"),
            ),
            function(
                "read_text",
                "Read one bounded UTF-8 text file from a workspace snapshot.",
                JSONObject()
                    .put("workspaceHandle", string(64))
                    .put("relativePath", string(4_096))
                    .put("maxUtf8Bytes", integer(1, MAX_TEXT_BYTES.toLong())),
                listOf("workspaceHandle", "relativePath"),
            ),
            function(
                "write_text",
                "Create or replace one UTF-8 file. seedWorkspaceHandle is optional; the seed stays immutable.",
                JSONObject()
                    .put("seedWorkspaceHandle", string(64))
                    .put("relativePath", string(4_096))
                    .put(
                        "text",
                        JSONObject().put("type", "string").put("maxLength", MAX_TEXT_CHARS),
                    ),
                listOf("relativePath", "text"),
            ),
            function(
                "delete",
                "Create a new snapshot without one file. The seed snapshot stays immutable.",
                JSONObject()
                    .put("seedWorkspaceHandle", string(64))
                    .put("relativePath", string(4_096)),
                listOf("seedWorkspaceHandle", "relativePath"),
            ),
        ),
    )

    val artifactNamespace = DynamicToolNamespaceSpec(
        name = ARTIFACT_NAMESPACE,
        description =
            "Durable bounded result artifacts. Large or binary data remains behind an opaque handle " +
                "instead of being embedded in JSON.",
        tools = listOf(
            function(
                "metadata",
                "Verify an artifact and return safe metadata without embedding its payload.",
                JSONObject().put("artifactHandle", string(68)),
                listOf("artifactHandle"),
            ),
            function(
                "read_text",
                "Read a bounded UTF-8 prefix only when the artifact MIME type is textual.",
                JSONObject()
                    .put("artifactHandle", string(68))
                    .put("maxUtf8Bytes", integer(1, MAX_TEXT_BYTES.toLong())),
                listOf("artifactHandle"),
            ),
            function(
                "from_workspace",
                "Promote one workspace file to a durable artifact and return its opaque handle.",
                JSONObject()
                    .put("workspaceHandle", string(64))
                    .put("relativePath", string(4_096))
                    .put("displayName", string(255))
                    .put("mimeType", string(255)),
                listOf("workspaceHandle", "relativePath", "displayName", "mimeType"),
            ),
        ),
    )

    private fun function(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String> = emptyList(),
    ) = DynamicToolFunctionSpec(
        name,
        description,
        JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray(required))
            .put("additionalProperties", false)
            .toString(),
    )

    private fun string(maxLength: Int) = JSONObject()
        .put("type", "string")
        .put("minLength", 1)
        .put("maxLength", maxLength)

    private fun integer(minimum: Long, maximum: Long) = JSONObject()
        .put("type", "integer")
        .put("minimum", minimum)
        .put("maximum", maximum)

    internal const val MAX_TEXT_BYTES = 256 * 1024
    private const val MAX_TEXT_CHARS = 256 * 1024
    internal const val MAX_LIST_ENTRIES = 200
    private const val MAX_LIST_OFFSET = 1_000_000
}

class WorkspaceDynamicToolExecutor(
    private val workspaceStore: PrivateWorkspaceStore,
    private val artifactStore: AtomicArtifactStore,
    private val backgroundExecutor: Executor,
) : DynamicToolExecutor {
    override val specs = listOf(
        WorkspaceDynamicToolCatalog.workspaceNamespace,
        WorkspaceDynamicToolCatalog.artifactNamespace,
    )

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
        val scheduled = gate.schedule(backgroundExecutor) {
            val result = runCatching { executeSafely(call, gate) }
                .getOrElse { failureResult(call, it.workspaceFailureCode()) }
            gate.complete(result)
        }
        if (!scheduled) gate.complete(failureResult(call, "workspace_executor_rejected"))
        return gate
    }

    override fun failureResult(
        call: DynamicToolCallParams,
        code: String,
    ): DynamicToolExecutionResult = result(
        JSONObject()
            .put("status", "failed")
            .put("errorCode", code.takeIf(SAFE_CODE::matches) ?: "workspace_tool_failed"),
        false,
    )

    private fun executeSafely(
        call: DynamicToolCallParams,
        gate: DynamicToolExecutionGate,
    ): DynamicToolExecutionResult {
        val args = JsonContract.parseObject(call.argumentsJson, 512 * 1024)
        return when (call.namespace) {
            WorkspaceDynamicToolCatalog.WORKSPACE_NAMESPACE -> executeWorkspace(call, args, gate)
            WorkspaceDynamicToolCatalog.ARTIFACT_NAMESPACE -> executeArtifact(call, args, gate)
            else -> failureResult(call, "unknown_workspace_namespace")
        }
    }

    private fun executeWorkspace(
        call: DynamicToolCallParams,
        args: JSONObject,
        gate: DynamicToolExecutionGate,
    ): DynamicToolExecutionResult {
        return when (call.tool) {
        "create" -> {
            args.requireOnly(emptySet())
            if (!gate.markExternalEffectStarted()) return cancelled(call)
            val snapshot = workspaceStore.begin().use { it.commit() }
            workspaceResult(snapshot)
        }
        "list" -> {
            args.requireOnly(setOf("workspaceHandle", "offset", "limit"))
            val handle = WorkspaceHandle(args.requiredString("workspaceHandle", 64))
            val offset = args.optionalInt("offset", 0, 0..1_000_000)
            val limit = args.optionalInt(
                "limit",
                WorkspaceDynamicToolCatalog.MAX_LIST_ENTRIES,
                1..WorkspaceDynamicToolCatalog.MAX_LIST_ENTRIES,
            )
            if (!gate.markExternalEffectStarted()) return cancelled(call)
            val snapshot = workspaceStore.snapshot(handle)
            val page = snapshot.manifest.files.drop(offset).take(limit)
            result(
                JSONObject()
                    .put("status", "ok")
                    .put("workspaceHandle", handle.value)
                    .put("totalFiles", snapshot.manifest.files.size)
                    .put("totalBytes", snapshot.manifest.byteCount)
                    .put("offset", offset)
                    .put("hasMore", offset + page.size < snapshot.manifest.files.size)
                    .put(
                        "files",
                        JSONArray().also { array ->
                            page.forEach { entry ->
                                array.put(
                                    JSONObject()
                                        .put("relativePath", entry.relativePath)
                                        .put("byteCount", entry.byteCount)
                                        .put("sha256", entry.sha256),
                                )
                            }
                        },
                    ),
                true,
            )
        }
        "read_text" -> {
            args.requireOnly(setOf("workspaceHandle", "relativePath", "maxUtf8Bytes"))
            val handle = WorkspaceHandle(args.requiredString("workspaceHandle", 64))
            val path = args.requiredString("relativePath", 4_096)
            val maxBytes = args.optionalInt(
                "maxUtf8Bytes",
                WorkspaceDynamicToolCatalog.MAX_TEXT_BYTES,
                1..WorkspaceDynamicToolCatalog.MAX_TEXT_BYTES,
            )
            if (!gate.markExternalEffectStarted()) return cancelled(call)
            val entry = workspaceStore.snapshot(handle).manifest.entry(path)
                ?: return failureResult(call, "workspace_file_not_found")
            require(entry.byteCount <= maxBytes) { "workspace_text_limit_exceeded" }
            val bytes = workspaceStore.openFile(handle, path).use { it.readBytes(maxBytes) }
            result(
                JSONObject()
                    .put("status", "ok")
                    .put("workspaceHandle", handle.value)
                    .put("relativePath", path)
                    .put("sha256", entry.sha256)
                    .put("text", decodeUtf8(bytes)),
                true,
            )
        }
        "write_text" -> {
            args.requireOnly(setOf("seedWorkspaceHandle", "relativePath", "text"))
            val seed = args.optionalString("seedWorkspaceHandle", 64)?.let(::WorkspaceHandle)
            val path = args.requiredString("relativePath", 4_096)
            val text = args.requiredText("text", 256 * 1024)
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            require(bytes.size <= WorkspaceDynamicToolCatalog.MAX_TEXT_BYTES) {
                "workspace_text_limit_exceeded"
            }
            if (!gate.markExternalEffectStarted()) return cancelled(call)
            val snapshot = workspaceStore.begin(seed).use { transaction ->
                transaction.delete(path)
                transaction.copy(path, ByteArrayInputStream(bytes), bytes.size.coerceAtLeast(1).toLong())
                transaction.commit()
            }
            workspaceResult(snapshot)
        }
        "delete" -> {
            args.requireOnly(setOf("seedWorkspaceHandle", "relativePath"))
            val seed = WorkspaceHandle(args.requiredString("seedWorkspaceHandle", 64))
            val path = args.requiredString("relativePath", 4_096)
            if (!gate.markExternalEffectStarted()) return cancelled(call)
            val snapshot = workspaceStore.begin(seed).use { transaction ->
                require(transaction.delete(path)) { "workspace_file_not_found" }
                transaction.commit()
            }
            workspaceResult(snapshot)
        }
            else -> failureResult(call, "unknown_workspace_tool")
        }
    }

    private fun executeArtifact(
        call: DynamicToolCallParams,
        args: JSONObject,
        gate: DynamicToolExecutionGate,
    ): DynamicToolExecutionResult {
        return when (call.tool) {
        "metadata" -> {
            args.requireOnly(setOf("artifactHandle"))
            val handle = ArtifactHandle(args.requiredString("artifactHandle", 68))
            if (!gate.markExternalEffectStarted()) return cancelled(call)
            result(artifactStore.metadata(handle).toJson(), true)
        }
        "read_text" -> {
            args.requireOnly(setOf("artifactHandle", "maxUtf8Bytes"))
            val handle = ArtifactHandle(args.requiredString("artifactHandle", 68))
            val maxBytes = args.optionalInt(
                "maxUtf8Bytes",
                WorkspaceDynamicToolCatalog.MAX_TEXT_BYTES,
                1..WorkspaceDynamicToolCatalog.MAX_TEXT_BYTES,
            )
            if (!gate.markExternalEffectStarted()) return cancelled(call)
            val metadata = artifactStore.metadata(handle)
            require(metadata.mimeType.isTextualMime()) { "artifact_not_textual" }
            require(metadata.byteCount <= maxBytes) { "artifact_text_limit_exceeded" }
            val bytes = artifactStore.open(handle).use { it.readBytes(maxBytes) }
            result(metadata.toJson().put("text", decodeUtf8(bytes)), true)
        }
        "from_workspace" -> {
            args.requireOnly(setOf("workspaceHandle", "relativePath", "displayName", "mimeType"))
            val workspace = WorkspaceHandle(args.requiredString("workspaceHandle", 64))
            val path = args.requiredString("relativePath", 4_096)
            val displayName = args.requiredString("displayName", 255)
            val mimeType = args.requiredString("mimeType", 255)
            if (!gate.markExternalEffectStarted()) return cancelled(call)
            val metadata = workspaceStore.openFile(workspace, path).use { input ->
                artifactStore.put(
                    displayName = displayName,
                    mimeType = mimeType,
                    origin = ArtifactOrigin.CODEX,
                    source = input,
                    workspaceHandle = workspace.value,
                )
            }
            result(metadata.toJson(), true)
        }
            else -> failureResult(call, "unknown_artifact_tool")
        }
    }

    private fun workspaceResult(snapshot: WorkspaceSnapshot) = result(
        JSONObject()
            .put("status", "ok")
            .put("workspaceHandle", snapshot.handle.value)
            .put("fileCount", snapshot.manifest.files.size)
            .put("byteCount", snapshot.manifest.byteCount),
        true,
    )

    private fun ai.hans.standard.artifacts.ArtifactMetadata.toJson() = JSONObject()
        .put("status", "ok")
        .put("artifactHandle", handle.value)
        .put("displayName", displayName)
        .put("mimeType", mimeType)
        .put("byteCount", byteCount)
        .put("sha256", sha256)
        .put("origin", origin.name.lowercase())
        .apply { workspaceHandle?.let { put("workspaceHandle", it) } }

    private fun result(value: JSONObject, success: Boolean): DynamicToolExecutionResult {
        val text = value.toString()
        require(text.toByteArray(StandardCharsets.UTF_8).size <= MAX_RESULT_BYTES) {
            "Workspace tool result is too large"
        }
        return DynamicToolExecutionResult(text, success)
    }

    private fun cancelled(call: DynamicToolCallParams) = failureResult(call, "dynamic_tool_cancelled")

    private fun Throwable.workspaceFailureCode(): String = when (message) {
        "workspace_file_not_found" -> "workspace_file_not_found"
        "workspace_text_limit_exceeded" -> "workspace_text_limit_exceeded"
        "artifact_not_textual" -> "artifact_not_textual"
        "artifact_text_limit_exceeded" -> "artifact_text_limit_exceeded"
        else -> when (this) {
            is IllegalArgumentException -> "invalid_workspace_arguments"
            is IllegalStateException -> "workspace_integrity_failure"
            else -> "workspace_tool_failed"
        }
    }

    private fun JSONObject.requireOnly(allowed: Set<String>) {
        require(keys().asSequence().all { it in allowed }) { "Unexpected workspace argument" }
    }

    private fun JSONObject.requiredString(key: String, maxChars: Int): String {
        val value = opt(key) as? String ?: throw IllegalArgumentException("Missing $key")
        require(value.isNotBlank() && value.length <= maxChars && value.none(Char::isISOControl)) {
            "Invalid $key"
        }
        return value
    }

    private fun JSONObject.optionalString(key: String, maxChars: Int): String? =
        if (has(key)) requiredString(key, maxChars) else null

    private fun JSONObject.requiredText(key: String, maxChars: Int): String {
        val value = opt(key) as? String ?: throw IllegalArgumentException("Missing $key")
        require(value.length <= maxChars && '\u0000' !in value) { "Invalid $key" }
        return value
    }

    private fun JSONObject.optionalInt(key: String, default: Int, range: IntRange): Int {
        if (!has(key)) return default
        val value = when (val raw = opt(key)) {
            is Int -> raw
            is Long -> raw.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
            else -> null
        } ?: throw IllegalArgumentException("Invalid $key")
        require(value in range) { "Invalid $key" }
        return value
    }

    private fun java.io.InputStream.readBytes(maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total = Math.addExact(total, count)
            require(total <= maxBytes) { "workspace_text_limit_exceeded" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

    private fun String.isTextualMime(): Boolean =
        startsWith("text/") || this in setOf(
            "application/json",
            "application/xml",
            "application/javascript",
            "application/x-yaml",
            "application/yaml",
            "image/svg+xml",
        ) || endsWith("+json") || endsWith("+xml")

    private companion object {
        const val MAX_RESULT_BYTES = 512 * 1024
        val SAFE_CODE = Regex("[a-z0-9_.:-]{1,96}")
    }
}
