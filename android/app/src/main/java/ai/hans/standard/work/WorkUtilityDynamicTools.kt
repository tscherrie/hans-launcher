package ai.hans.standard.work

import ai.hans.standard.artifacts.ArtifactHandle
import ai.hans.standard.artifacts.ArtifactMetadata
import ai.hans.standard.artifacts.ArtifactOrigin
import ai.hans.standard.artifacts.AtomicArtifactStore
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
import ai.hans.standard.workspace.PrivateWorkspaceStore
import ai.hans.standard.workspace.WorkspaceHandle
import ai.hans.standard.workspace.WorkspacePaths
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

object WorkUtilityDynamicToolCatalog {
    const val NAMESPACE = "hans_work"

    val namespace = DynamicToolNamespaceSpec(
        name = NAMESPACE,
        description =
            "Bounded Android work utilities over immutable workspace and artifact handles. " +
                "These are typed replacements for common shell operations; they never expose raw app paths.",
        tools = listOf(
            function(
                "hash_file",
                "Return the verified SHA-256 and size of one workspace file.",
                JSONObject()
                    .put("workspaceHandle", string(64))
                    .put("relativePath", string(4_096)),
                listOf("workspaceHandle", "relativePath"),
            ),
            function(
                "search_text",
                "Search bounded UTF-8 workspace files and return line-oriented matches.",
                JSONObject()
                    .put("workspaceHandle", string(64))
                    .put("query", string(MAX_QUERY_CHARS))
                    .put("regex", JSONObject().put("type", "boolean"))
                    .put("caseSensitive", JSONObject().put("type", "boolean"))
                    .put("pathPrefix", string(4_096))
                    .put("maxMatches", integer(1, MAX_MATCHES.toLong())),
                listOf("workspaceHandle", "query"),
            ),
            function(
                "replace_text",
                "Replace bounded literal text in one UTF-8 file and return a new immutable workspace snapshot.",
                JSONObject()
                    .put("workspaceHandle", string(64))
                    .put("relativePath", string(4_096))
                    .put("oldText", string(MAX_REPLACE_TEXT_CHARS))
                    .put("newText", JSONObject().put("type", "string").put("maxLength", MAX_REPLACE_TEXT_CHARS))
                    .put("maxReplacements", integer(1, MAX_REPLACEMENTS.toLong())),
                listOf("workspaceHandle", "relativePath", "oldText", "newText"),
            ),
            function(
                "create_zip",
                "Create a deterministic ZIP from selected workspace files and return an artifact handle.",
                JSONObject()
                    .put("workspaceHandle", string(64))
                    .put(
                        "relativePaths",
                        JSONObject()
                            .put("type", "array")
                            .put("maxItems", MAX_ARCHIVE_ENTRIES)
                            .put("items", string(4_096)),
                    )
                    .put("displayName", string(255)),
                listOf("workspaceHandle"),
            ),
            function(
                "extract_zip",
                "Safely extract a ZIP artifact into a new immutable workspace snapshot.",
                JSONObject()
                    .put("artifactHandle", string(68))
                    .put("seedWorkspaceHandle", string(64))
                    .put("destinationPrefix", string(4_096))
                    .put("maxExtractedBytes", integer(1, MAX_EXTRACTED_BYTES)),
                listOf("artifactHandle"),
            ),
            function(
                "http_request",
                "Perform one bounded HTTP(S) request and store the response body as an artifact. " +
                    "Private/LAN access requires the separately authorized private-network capability.",
                JSONObject()
                    .put("url", string(WorkHttpRequest.MAX_URL_BYTES))
                    .put(
                        "method",
                        JSONObject().put("type", "string").put("enum", JSONArray(WorkHttpRequest.ALLOWED_METHODS.sorted())),
                    )
                    .put(
                        "headers",
                        JSONObject()
                            .put("type", "object")
                            .put("maxProperties", WorkHttpRequest.MAX_HEADERS)
                            .put("additionalProperties", JSONObject().put("type", "string").put("maxLength", WorkHttpRequest.MAX_HEADER_VALUE_BYTES)),
                    )
                    .put("bodyText", JSONObject().put("type", "string").put("maxLength", MAX_HTTP_BODY_CHARS))
                    .put("bodyArtifactHandle", string(68))
                    .put("allowPrivateNetwork", JSONObject().put("type", "boolean"))
                    .put("maxResponseBytes", integer(1, MAX_HTTP_RESPONSE_BYTES)),
                listOf("url"),
            ),
        ),
    )

    private fun function(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String>,
    ) = DynamicToolFunctionSpec(
        name = name,
        description = description,
        inputSchemaJson = JSONObject()
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

    internal const val MAX_QUERY_CHARS = 4_096
    internal const val MAX_MATCHES = 500
    internal const val MAX_REPLACE_TEXT_CHARS = 256 * 1024
    internal const val MAX_REPLACEMENTS = 10_000
    internal const val MAX_ARCHIVE_ENTRIES = 4_096
    internal const val MAX_EXTRACTED_BYTES = 256L * 1024L * 1024L
    internal const val MAX_HTTP_RESPONSE_BYTES = 64L * 1024L * 1024L
    internal const val MAX_HTTP_BODY_CHARS = 512 * 1024
}

class WorkUtilityDynamicToolExecutor(
    private val workspaceStore: PrivateWorkspaceStore,
    private val artifactStore: AtomicArtifactStore,
    private val httpCallFactory: WorkHttpCallFactory,
    private val privateNetworkAuthorization: PrivateNetworkAuthorization,
    private val backgroundExecutor: Executor,
) : DynamicToolExecutor {
    override val specs = listOf(WorkUtilityDynamicToolCatalog.namespace)

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
        val httpCall = AtomicReference<WorkHttpCall?>(null)
        val scheduled = gate.schedule(backgroundExecutor) {
            val result = runCatching { executeSafely(call, gate, httpCall) }
                .getOrElse { failureResult(call, it.workFailureCode()) }
            gate.complete(result)
        }
        if (!scheduled) gate.complete(failureResult(call, "work_executor_rejected"))
        return object : DynamicToolExecutionHandle {
            override fun cancel(): DynamicToolCancellationDisposition {
                runCatching { httpCall.get()?.cancel() }
                return gate.cancel()
            }
        }
    }

    override fun failureResult(
        call: DynamicToolCallParams,
        code: String,
    ): DynamicToolExecutionResult = jsonResult(
        JSONObject()
            .put("status", "failed")
            .put("errorCode", code.takeIf(SAFE_ERROR_CODE::matches) ?: "work_tool_failed"),
        success = false,
    )

    private fun executeSafely(
        call: DynamicToolCallParams,
        gate: DynamicToolExecutionGate,
        activeHttpCall: AtomicReference<WorkHttpCall?>,
    ): DynamicToolExecutionResult {
        require(call.namespace == WorkUtilityDynamicToolCatalog.NAMESPACE) { "unknown_work_namespace" }
        val args = JsonContract.parseObject(call.argumentsJson, 3 * 1024 * 1024)
        return when (call.tool) {
            "hash_file" -> hashFile(call, args, gate)
            "search_text" -> searchText(call, args, gate)
            "replace_text" -> replaceText(call, args, gate)
            "create_zip" -> createZip(call, args, gate)
            "extract_zip" -> extractZip(call, args, gate)
            "http_request" -> httpRequest(call, args, gate, activeHttpCall)
            else -> failureResult(call, "unknown_work_tool")
        }
    }

    private fun hashFile(
        call: DynamicToolCallParams,
        args: JSONObject,
        gate: DynamicToolExecutionGate,
    ): DynamicToolExecutionResult {
        args.requireOnly("workspaceHandle", "relativePath")
        val handle = WorkspaceHandle(args.requiredString("workspaceHandle", 64))
        val path = args.requiredString("relativePath", 4_096)
        if (!gate.markExternalEffectStarted()) return cancelled(call)
        val entry = workspaceStore.snapshot(handle).manifest.entry(path)
            ?: return failureResult(call, "workspace_file_not_found")
        return jsonResult(
            JSONObject()
                .put("status", "ok")
                .put("workspaceHandle", handle.value)
                .put("relativePath", entry.relativePath)
                .put("byteCount", entry.byteCount)
                .put("sha256", entry.sha256),
        )
    }

    private fun searchText(
        call: DynamicToolCallParams,
        args: JSONObject,
        gate: DynamicToolExecutionGate,
    ): DynamicToolExecutionResult {
        args.requireOnly("workspaceHandle", "query", "regex", "caseSensitive", "pathPrefix", "maxMatches")
        val handle = WorkspaceHandle(args.requiredString("workspaceHandle", 64))
        val query = args.requiredString("query", WorkUtilityDynamicToolCatalog.MAX_QUERY_CHARS)
        val regex = args.optionalBoolean("regex", false)
        val caseSensitive = args.optionalBoolean("caseSensitive", false)
        val prefix = args.optionalString("pathPrefix", 4_096)?.also {
            WorkspacePaths.requireSafeRelativePath(it.removeSuffix("/"))
        }
        val maxMatches = args.optionalInt("maxMatches", 100, 1..WorkUtilityDynamicToolCatalog.MAX_MATCHES)
        val pattern = if (regex) {
            Pattern.compile(
                query,
                if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
            )
        } else null
        if (!gate.markExternalEffectStarted()) return cancelled(call)
        val snapshot = workspaceStore.snapshot(handle)
        val matches = JSONArray()
        var scannedFiles = 0
        var scannedBytes = 0L
        var skippedBinaryOrLarge = 0
        fileLoop@ for (entry in snapshot.manifest.files) {
            if (gate.isCancellationRequested()) return cancelled(call)
            if (prefix != null && entry.relativePath != prefix && !entry.relativePath.startsWith("$prefix/")) continue
            if (entry.byteCount > MAX_SEARCH_FILE_BYTES || scannedBytes + entry.byteCount > MAX_SEARCH_TOTAL_BYTES) {
                skippedBinaryOrLarge += 1
                continue
            }
            val bytes = workspaceStore.openFile(handle, entry.relativePath).use { input ->
                input.readBounded(MAX_SEARCH_FILE_BYTES.toInt())
            }
            val text = runCatching { decodeUtf8(bytes) }.getOrNull()
            if (text == null || text.indexOf('\u0000') >= 0) {
                skippedBinaryOrLarge += 1
                continue
            }
            scannedFiles += 1
            scannedBytes += bytes.size
            text.lineSequence().forEachIndexed { index, line ->
                if (matches.length() >= maxMatches) return@forEachIndexed
                val matched = pattern?.matcher(line)?.find() ?: if (caseSensitive) {
                    line.contains(query)
                } else {
                    line.contains(query, ignoreCase = true)
                }
                if (matched) {
                    matches.put(
                        JSONObject()
                            .put("relativePath", entry.relativePath)
                            .put("line", index + 1)
                            .put("preview", line.take(MAX_PREVIEW_CHARS)),
                    )
                }
            }
            if (matches.length() >= maxMatches) break@fileLoop
        }
        return jsonResult(
            JSONObject()
                .put("status", "ok")
                .put("workspaceHandle", handle.value)
                .put("matches", matches)
                .put("matchLimitReached", matches.length() >= maxMatches)
                .put("scannedFiles", scannedFiles)
                .put("scannedBytes", scannedBytes)
                .put("skippedBinaryOrLarge", skippedBinaryOrLarge),
        )
    }

    private fun replaceText(
        call: DynamicToolCallParams,
        args: JSONObject,
        gate: DynamicToolExecutionGate,
    ): DynamicToolExecutionResult {
        args.requireOnly("workspaceHandle", "relativePath", "oldText", "newText", "maxReplacements")
        val handle = WorkspaceHandle(args.requiredString("workspaceHandle", 64))
        val path = args.requiredString("relativePath", 4_096)
        val old = args.requiredString("oldText", WorkUtilityDynamicToolCatalog.MAX_REPLACE_TEXT_CHARS)
        val replacement = args.requiredText("newText", WorkUtilityDynamicToolCatalog.MAX_REPLACE_TEXT_CHARS)
        val max = args.optionalInt("maxReplacements", WorkUtilityDynamicToolCatalog.MAX_REPLACEMENTS, 1..WorkUtilityDynamicToolCatalog.MAX_REPLACEMENTS)
        val entry = workspaceStore.snapshot(handle).manifest.entry(path)
            ?: return failureResult(call, "workspace_file_not_found")
        require(entry.byteCount <= MAX_REPLACE_FILE_BYTES) { "workspace_text_limit_exceeded" }
        val original = workspaceStore.openFile(handle, path).use { decodeUtf8(it.readBounded(MAX_REPLACE_FILE_BYTES.toInt())) }
        val occurrences = original.countNonOverlapping(old, max + 1)
        require(occurrences > 0) { "replace_text_not_found" }
        require(occurrences <= max) { "replace_limit_exceeded" }
        val updated = original.replace(old, replacement)
        val bytes = updated.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_REPLACE_FILE_BYTES) { "workspace_text_limit_exceeded" }
        if (!gate.markExternalEffectStarted()) return cancelled(call)
        val next = workspaceStore.begin(handle).use { transaction ->
            check(transaction.delete(path))
            transaction.write(path, MAX_REPLACE_FILE_BYTES.toLong()) { it.write(bytes) }
            transaction.commit()
        }
        return jsonResult(
            JSONObject()
                .put("status", "ok")
                .put("previousWorkspaceHandle", handle.value)
                .put("workspaceHandle", next.handle.value)
                .put("relativePath", path)
                .put("replacements", occurrences)
                .put("byteCount", bytes.size),
        )
    }

    private fun createZip(
        call: DynamicToolCallParams,
        args: JSONObject,
        gate: DynamicToolExecutionGate,
    ): DynamicToolExecutionResult {
        args.requireOnly("workspaceHandle", "relativePaths", "displayName")
        val handle = WorkspaceHandle(args.requiredString("workspaceHandle", 64))
        val requested = args.optionalStringArray("relativePaths", WorkUtilityDynamicToolCatalog.MAX_ARCHIVE_ENTRIES, 4_096)
        val displayName = (args.optionalString("displayName", 255) ?: "workspace-${handle.value.take(12)}.zip")
            .requireSafeDisplayName(".zip")
        val snapshot = workspaceStore.snapshot(handle)
        val entries = if (requested == null) {
            snapshot.manifest.files
        } else {
            require(requested.distinct().size == requested.size) { "duplicate_archive_path" }
            requested.map { path ->
                snapshot.manifest.entry(path) ?: error("workspace_file_not_found")
            }
        }
        require(entries.size <= WorkUtilityDynamicToolCatalog.MAX_ARCHIVE_ENTRIES) { "archive_entry_limit_exceeded" }
        if (!gate.markExternalEffectStarted()) return cancelled(call)
        val generatedPath = "generated/${handle.value.take(16)}-${System.nanoTime()}.zip"
        val generated = workspaceStore.begin().use { transaction ->
            transaction.write(generatedPath, MAX_ZIP_BYTES) { output ->
                ZipOutputStream(output).use { zip ->
                    zip.setLevel(6)
                    entries.sortedBy { it.relativePath }.forEach { entry ->
                        if (gate.isCancellationRequested()) error("work_cancelled")
                        val zipEntry = ZipEntry(entry.relativePath).apply { time = 0L }
                        zip.putNextEntry(zipEntry)
                        workspaceStore.openFile(handle, entry.relativePath).use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            transaction.commit()
        }
        val artifact = workspaceStore.openFile(generated.handle, generatedPath).use { input ->
            artifactStore.put(
                displayName = displayName,
                mimeType = "application/zip",
                origin = ArtifactOrigin.ANDROID,
                source = input,
                maxBytes = MAX_ZIP_BYTES,
                workspaceHandle = handle.value,
            )
        }
        return jsonResult(artifactResult(artifact).put("sourceWorkspaceHandle", handle.value))
    }

    private fun extractZip(
        call: DynamicToolCallParams,
        args: JSONObject,
        gate: DynamicToolExecutionGate,
    ): DynamicToolExecutionResult {
        args.requireOnly("artifactHandle", "seedWorkspaceHandle", "destinationPrefix", "maxExtractedBytes")
        val artifactHandle = ArtifactHandle(args.requiredString("artifactHandle", 68))
        val seed = args.optionalString("seedWorkspaceHandle", 64)?.let(::WorkspaceHandle)
        val prefix = args.optionalString("destinationPrefix", 4_096)?.removeSuffix("/")?.also {
            WorkspacePaths.requireSafeRelativePath(it)
        }
        val maxExtracted = args.optionalLong(
            "maxExtractedBytes",
            WorkUtilityDynamicToolCatalog.MAX_EXTRACTED_BYTES,
            1L..WorkUtilityDynamicToolCatalog.MAX_EXTRACTED_BYTES,
        )
        val metadata = artifactStore.metadata(artifactHandle)
        require(metadata.byteCount <= MAX_COMPRESSED_ZIP_BYTES) { "archive_compressed_limit_exceeded" }
        if (!gate.markExternalEffectStarted()) return cancelled(call)
        var totalBytes = 0L
        var entryCount = 0
        val seen = linkedSetOf<String>()
        val snapshot = workspaceStore.begin(seed).use { transaction ->
            artifactStore.open(artifactHandle).use { source ->
                ZipInputStream(source).use { zip ->
                    while (true) {
                        if (gate.isCancellationRequested()) error("work_cancelled")
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) {
                            zip.closeEntry()
                            continue
                        }
                        entryCount += 1
                        require(entryCount <= WorkUtilityDynamicToolCatalog.MAX_ARCHIVE_ENTRIES) {
                            "archive_entry_limit_exceeded"
                        }
                        require(entry.name.length <= 4_096) { "archive_path_limit_exceeded" }
                        val relative = if (prefix == null) entry.name else "$prefix/${entry.name}"
                        WorkspacePaths.requireSafeRelativePath(relative)
                        require(seen.add(relative)) { "duplicate_archive_path" }
                        seed?.let { if (workspaceStore.snapshot(it).manifest.entry(relative) != null) transaction.delete(relative) }
                        val remaining = maxExtracted - totalBytes
                        require(remaining > 0L) { "archive_extracted_limit_exceeded" }
                        val perFileLimit = minOf(remaining, workspaceStore.limits.maxSingleFileBytes)
                        val written = transaction.write(relative, perFileLimit) { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                if (count > 0) output.write(buffer, 0, count)
                            }
                        }
                        totalBytes = Math.addExact(totalBytes, written)
                        require(totalBytes <= maxExtracted) { "archive_extracted_limit_exceeded" }
                        zip.closeEntry()
                    }
                }
            }
            transaction.commit()
        }
        return jsonResult(
            JSONObject()
                .put("status", "ok")
                .put("artifactHandle", artifactHandle.value)
                .put("workspaceHandle", snapshot.handle.value)
                .put("entryCount", entryCount)
                .put("extractedBytes", totalBytes),
        )
    }

    private fun httpRequest(
        call: DynamicToolCallParams,
        args: JSONObject,
        gate: DynamicToolExecutionGate,
        activeHttpCall: AtomicReference<WorkHttpCall?>,
    ): DynamicToolExecutionResult {
        args.requireOnly(
            "url",
            "method",
            "headers",
            "bodyText",
            "bodyArtifactHandle",
            "allowPrivateNetwork",
            "maxResponseBytes",
        )
        val url = args.requiredString("url", WorkHttpRequest.MAX_URL_BYTES)
        WorkNetworkEndpointPolicy.validateUri(url)
        val method = (args.optionalString("method", 16) ?: "GET").uppercase(Locale.ROOT)
        val headers = args.optionalStringMap("headers", WorkHttpRequest.MAX_HEADERS, WorkHttpRequest.MAX_HEADER_VALUE_BYTES)
        val bodyText = args.optionalText("bodyText", WorkUtilityDynamicToolCatalog.MAX_HTTP_BODY_CHARS)
        val bodyHandle = args.optionalString("bodyArtifactHandle", 68)?.let(::ArtifactHandle)
        require(bodyText == null || bodyHandle == null) { "multiple_http_bodies" }
        val body = when {
            bodyText != null -> bodyText.toByteArray(StandardCharsets.UTF_8)
            bodyHandle != null -> artifactStore.open(bodyHandle).use {
                it.readBounded(WorkHttpRequest.MAX_REQUEST_BODY_BYTES)
            }
            else -> null
        }
        val allowPrivate = args.optionalBoolean("allowPrivateNetwork", false)
        if (allowPrivate) {
            require(privateNetworkAuthorization.isAuthorized(call.threadId, call.turnId, call.callId, url)) {
                "private_network_not_authorized"
            }
        }
        val maxResponse = args.optionalLong(
            "maxResponseBytes",
            WorkUtilityDynamicToolCatalog.MAX_HTTP_RESPONSE_BYTES,
            1L..WorkUtilityDynamicToolCatalog.MAX_HTTP_RESPONSE_BYTES,
        )
        val request = WorkHttpRequest(url, method, headers, body, allowPrivate)
        if (!gate.markExternalEffectStarted()) return cancelled(call)
        val transportCall = httpCallFactory.create(request)
        activeHttpCall.set(transportCall)
        if (gate.isCancellationRequested()) {
            transportCall.cancel()
            return cancelled(call)
        }
        return transportCall.execute().use { response ->
            val declared = response.metadata.declaredContentLength
            require(declared == null || declared <= maxResponse) { "http_response_limit_exceeded" }
            val displayName = response.metadata.finalUrl
                .substringBefore('?')
                .substringAfterLast('/')
                .takeIf { it.isNotBlank() }
                ?.sanitizeDisplayName()
                ?: "http-response.bin"
            val mime = response.metadata.contentType
                ?.substringBefore(';')
                ?.lowercase(Locale.ROOT)
                ?.takeIf(SAFE_MIME_TYPE::matches)
                ?: "application/octet-stream"
            val artifact = artifactStore.put(
                displayName = displayName,
                mimeType = mime,
                origin = ArtifactOrigin.ANDROID,
                source = response.body,
                maxBytes = maxResponse,
            )
            jsonResult(
                artifactResult(artifact)
                    .put("statusCode", response.metadata.statusCode)
                    .put("finalUrl", response.metadata.finalUrl)
                    .put("headers", response.metadata.headers.safeResponseHeaders()),
                success = response.metadata.statusCode in 200..399,
            )
        }.also { activeHttpCall.set(null) }
    }

    private fun cancelled(call: DynamicToolCallParams) = failureResult(call, "work_cancelled")

    private fun artifactResult(metadata: ArtifactMetadata) = JSONObject()
        .put("status", "ok")
        .put("artifactHandle", metadata.handle.value)
        .put("displayName", metadata.displayName)
        .put("mimeType", metadata.mimeType)
        .put("byteCount", metadata.byteCount)
        .put("sha256", metadata.sha256)

    private companion object {
        const val MAX_SEARCH_FILE_BYTES = 2L * 1024L * 1024L
        const val MAX_SEARCH_TOTAL_BYTES = 32L * 1024L * 1024L
        const val MAX_REPLACE_FILE_BYTES = 2 * 1024 * 1024
        const val MAX_PREVIEW_CHARS = 1_024
        const val MAX_ZIP_BYTES = 128L * 1024L * 1024L
        const val MAX_COMPRESSED_ZIP_BYTES = 128L * 1024L * 1024L
        val SAFE_ERROR_CODE = Regex("[a-z0-9_]{1,96}")
        val SAFE_MIME_TYPE = Regex("[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]{0,126}/[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]{0,126}")
    }
}

private fun JSONObject.requireOnly(vararg names: String) {
    JsonContract.requireOnlyKeys(this, names.toSet(), "Hans work tool arguments")
}

private fun JSONObject.requiredString(name: String, maxChars: Int): String =
    JsonContract.requiredString(this, name, maxChars)

private fun JSONObject.requiredText(name: String, maxChars: Int): String {
    require(has(name) && !isNull(name)) { "Missing $name" }
    val value = get(name)
    require(value is String && value.length <= maxChars) { "Invalid $name" }
    return value
}

private fun JSONObject.optionalString(name: String, maxChars: Int): String? =
    JsonContract.optionalString(this, name, maxChars)

private fun JSONObject.optionalText(name: String, maxChars: Int): String? {
    if (!has(name) || isNull(name)) return null
    val value = get(name)
    require(value is String && value.length <= maxChars) { "Invalid $name" }
    return value
}

private fun JSONObject.optionalBoolean(name: String, default: Boolean): Boolean {
    if (!has(name) || isNull(name)) return default
    val value = get(name)
    require(value is Boolean) { "Invalid $name" }
    return value
}

private fun JSONObject.optionalInt(name: String, default: Int, range: IntRange): Int {
    if (!has(name) || isNull(name)) return default
    val value = get(name)
    require(value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() in range) {
        "Invalid $name"
    }
    return value.toInt()
}

private fun JSONObject.optionalLong(name: String, default: Long, range: LongRange): Long {
    if (!has(name) || isNull(name)) return default
    val value = get(name)
    require(value is Number && value.toDouble() == value.toLong().toDouble() && value.toLong() in range) {
        "Invalid $name"
    }
    return value.toLong()
}

private fun JSONObject.optionalStringArray(name: String, maxItems: Int, maxChars: Int): List<String>? {
    if (!has(name) || isNull(name)) return null
    val array = get(name)
    require(array is JSONArray && array.length() <= maxItems) { "Invalid $name" }
    return List(array.length()) { index ->
        val value = array.get(index)
        require(value is String && value.isNotBlank() && value.length <= maxChars) { "Invalid $name entry" }
        value
    }
}

private fun JSONObject.optionalStringMap(name: String, maxEntries: Int, maxChars: Int): Map<String, String> {
    if (!has(name) || isNull(name)) return emptyMap()
    val objectValue = get(name)
    require(objectValue is JSONObject && objectValue.length() <= maxEntries) { "Invalid $name" }
    return buildMap {
        objectValue.keys().forEach { key ->
            val value = objectValue.get(key)
            require(value is String && value.length <= maxChars) { "Invalid $name value" }
            put(key, value)
        }
    }
}

private fun InputStream.readBounded(maxBytes: Int): ByteArray {
    require(maxBytes > 0)
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (count == 0) continue
        total = Math.addExact(total, count)
        require(total <= maxBytes) { "byte_limit_exceeded" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes))
    .toString()

private fun String.countNonOverlapping(needle: String, stopAfter: Int): Int {
    var count = 0
    var offset = 0
    while (offset <= length - needle.length) {
        val found = indexOf(needle, offset)
        if (found < 0) break
        count += 1
        if (count >= stopAfter) break
        offset = found + needle.length
    }
    return count
}

private fun String.requireSafeDisplayName(requiredSuffix: String): String {
    require(isNotBlank() && length <= 255 && endsWith(requiredSuffix, ignoreCase = true)) {
        "Invalid artifact display name"
    }
    require(none { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() }) {
        "Unsafe artifact display name"
    }
    return this
}

private fun String.sanitizeDisplayName(): String {
    val clean = map { character ->
        when {
            character == '/' || character == '\\' || character == '\u0000' || character.isISOControl() -> '_'
            else -> character
        }
    }.joinToString("").take(255).trim()
    return clean.ifBlank { "http-response.bin" }
}

private fun Map<String, List<String>>.safeResponseHeaders(): JSONObject {
    val safeNames = setOf("cache-control", "content-language", "content-length", "content-type", "etag", "last-modified", "location", "retry-after")
    return JSONObject().also { output ->
        entries.filter { it.key.lowercase(Locale.ROOT) in safeNames }.take(32).forEach { (name, values) ->
            output.put(name.lowercase(Locale.ROOT), JSONArray(values.take(8).map { it.take(2_048) }))
        }
    }
}

private fun Throwable.workFailureCode(): String {
    val code = message?.lowercase(Locale.ROOT)?.replace(Regex("[^a-z0-9]+"), "_")?.trim('_')
    return code?.take(96)?.takeIf { it.isNotBlank() } ?: "work_tool_failed"
}

private fun jsonResult(value: JSONObject, success: Boolean = true) =
    DynamicToolExecutionResult(value.toString(), success)
