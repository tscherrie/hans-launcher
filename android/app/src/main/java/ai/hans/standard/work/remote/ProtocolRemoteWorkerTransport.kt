package ai.hans.standard.work.remote

import ai.hans.standard.codex.JsonContract
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned, bounded remote-worker wire protocol. Uploads are chunked and idempotently addressed
 * by operation, payload ordinal, and chunk ordinal. A failed submit is intentionally not retried;
 * callers recover it through [lookup].
 */
internal class ProtocolRemoteWorkerTransport(
    private val calls: RemoteWorkHttpCallFactory,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : RemoteWorkerTransport {
    override fun probe(identity: RemoteWorkerConnectionIdentity): RemoteWorkerProbeReceipt {
        val root = jsonRequest(identity, RemoteWorkHttpRequest("GET", PROBE_PATH))
        root.requireOnly(PROBE_FIELDS)
        require(root.getInt("version") == REMOTE_WORK_PROTOCOL_VERSION)
        val adapters = root.getJSONArray("adapters").let { array ->
            require(array.length() in 1..MAX_REMOTE_ADAPTERS)
            (0 until array.length()).map { index ->
                array.getJSONObject(index).let { value ->
                    value.requireOnly(ADAPTER_FIELDS)
                    RemoteWorkAdapterDescriptor(
                        id = RemoteWorkAdapterId(value.getString("id")),
                        version = value.getString("version"),
                        description = value.getString("description"),
                    )
                }
            }
        }
        return RemoteWorkerProbeReceipt(
            worker = RemoteWorkerId(root.getString("worker")),
            configurationDigest = root.getString("configurationDigest"),
            protocolVersion = root.getInt("version"),
            adapters = adapters,
            observedAtEpochMillis = nowEpochMillis().also { require(it >= 0L) },
        )
    }

    override fun submit(
        identity: RemoteWorkerConnectionIdentity,
        request: RemoteWorkRequest,
        payloads: RemoteWorkPayloadSource,
    ): RemoteWorkStatusReceipt {
        require(request.worker == identity.workerId)
        val descriptors = payloads.descriptors(request)
        val requestDigest = RemoteWorkRequestDigest.compute(request, descriptors)
        val operationPath = operationPath(request.operationId)
        val manifest = submitManifest(request, requestDigest, descriptors)
        unitRequest(
            identity,
            RemoteWorkHttpRequest(
                method = "PUT",
                path = "$operationPath/manifest",
                body = manifest,
            ),
        )
        descriptors.forEachIndexed { payloadIndex, descriptor ->
            uploadPayload(identity, request.operationId, payloadIndex, descriptor, payloads)
        }
        return statusRequest(
            identity,
            RemoteWorkHttpRequest(
                method = "POST",
                path = "$operationPath/submit",
                body = JSONObject()
                    .put("version", REMOTE_WORK_PROTOCOL_VERSION)
                    .put("requestDigest", requestDigest)
                    .toString().toByteArray(StandardCharsets.UTF_8),
                timeoutMillis = minOf(
                    RemoteWorkHttpRequest.MAX_REMOTE_TIMEOUT_MILLIS,
                    request.limits.maximumRuntimeSeconds * 1_000L,
                ),
            ),
        )
    }

    override fun status(
        identity: RemoteWorkerConnectionIdentity,
        operationId: RemoteWorkOperationId,
        executionId: RemoteExecutionId,
    ): RemoteWorkStatusReceipt = statusRequest(
        identity,
        RemoteWorkHttpRequest(
            "GET",
            "${operationPath(operationId)}/executions/${executionId.value}",
        ),
    )

    override fun lookup(
        identity: RemoteWorkerConnectionIdentity,
        operationId: RemoteWorkOperationId,
        requestDigest: String,
    ): RemoteWorkStatusReceipt? {
        require(SHA_256.matches(requestDigest))
        val request = RemoteWorkHttpRequest(
            method = "POST",
            path = "${operationPath(operationId)}/lookup",
            body = JSONObject()
                .put("version", REMOTE_WORK_PROTOCOL_VERSION)
                .put("requestDigest", requestDigest)
                .toString().toByteArray(StandardCharsets.UTF_8),
        )
        return response(identity, request) { lease ->
            when (lease.statusCode) {
                404 -> null
                in 200..299 -> decodeStatus(readJson(lease))
                else -> throw RemoteWorkFailure("remote_work_lookup_rejected")
            }
        }
    }

    override fun cancel(
        identity: RemoteWorkerConnectionIdentity,
        operationId: RemoteWorkOperationId,
        executionId: RemoteExecutionId,
    ): RemoteWorkStatusReceipt = statusRequest(
        identity,
        RemoteWorkHttpRequest(
            method = "POST",
            path = "${operationPath(operationId)}/executions/${executionId.value}/cancel",
            body = JSONObject()
                .put("version", REMOTE_WORK_PROTOCOL_VERSION)
                .toString().toByteArray(StandardCharsets.UTF_8),
        ),
    )

    override fun download(
        identity: RemoteWorkerConnectionIdentity,
        operationId: RemoteWorkOperationId,
        executionId: RemoteExecutionId,
        artifact: RemoteWorkResultArtifact,
    ): RemoteWorkDownloadLease {
        val request = RemoteWorkHttpRequest(
            method = "GET",
            path = "${operationPath(operationId)}/executions/${executionId.value}/artifacts/" +
                artifact.opaqueRemoteId,
            maxResponseBytes = artifact.byteCount,
        )
        val call = calls.create(identity, request)
        val response = try {
            call.execute()
        } catch (error: Throwable) {
            call.close()
            throw error
        }
        if (response.statusCode !in 200..299) {
            response.close()
            call.close()
            throw RemoteWorkFailure("remote_work_download_rejected")
        }
        response.declaredContentLength?.let { length ->
            if (length != artifact.byteCount) {
                response.close()
                call.close()
                throw RemoteWorkFailure("remote_work_artifact_size_mismatch")
            }
        }
        return object : RemoteWorkDownloadLease {
            override val artifact = artifact
            override val input: InputStream = response.body
            override fun close() {
                response.close()
                call.close()
            }
        }
    }

    private fun uploadPayload(
        identity: RemoteWorkerConnectionIdentity,
        operationId: RemoteWorkOperationId,
        payloadIndex: Int,
        descriptor: RemoteWorkPayloadDescriptor,
        source: RemoteWorkPayloadSource,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        var byteCount = 0L
        source.open(descriptor).use { lease ->
            require(lease.descriptor == descriptor)
            var chunkIndex = 0
            val buffer = ByteArray(UPLOAD_CHUNK_BYTES)
            while (true) {
                val count = lease.input.readChunk(buffer)
                if (count == 0) break
                byteCount = Math.addExact(byteCount, count.toLong())
                require(byteCount <= descriptor.byteCount) {
                    "Remote work payload grew while uploading"
                }
                digest.update(buffer, 0, count)
                unitRequest(
                    identity,
                    RemoteWorkHttpRequest(
                        method = "PUT",
                        path = "${operationPath(operationId)}/payloads/$payloadIndex/chunks/$chunkIndex",
                        body = buffer.copyOf(count),
                        contentType = "application/octet-stream",
                    ),
                )
                chunkIndex = Math.addExact(chunkIndex, 1)
            }
        }
        require(byteCount == descriptor.byteCount) { "Remote work payload size changed" }
        require(digest.digest().toHexString() == descriptor.sha256) {
            "Remote work payload digest changed"
        }
    }

    private fun submitManifest(
        request: RemoteWorkRequest,
        requestDigest: String,
        descriptors: List<RemoteWorkPayloadDescriptor>,
    ): ByteArray {
        val arguments = JsonContract.parseObject(
            request.argumentsJson,
            RemoteWorkRequest.MAX_ARGUMENT_BYTES,
        )
        val root = JSONObject()
            .put("version", REMOTE_WORK_PROTOCOL_VERSION)
            .put("operationId", request.operationId.value)
            .put("worker", request.worker.value)
            .put("adapter", request.adapter.value)
            .put("requestDigest", requestDigest)
            .put("arguments", arguments)
            .put(
                "limits",
                JSONObject()
                    .put("maximumRuntimeSeconds", request.limits.maximumRuntimeSeconds)
                    .put("maximumOutputBytes", request.limits.maximumOutputBytes)
                    .put("maximumArtifacts", request.limits.maximumArtifacts),
            )
            .put(
                "payloads",
                JSONArray().apply {
                    descriptors.forEachIndexed { index, descriptor ->
                        put(
                            JSONObject()
                                .put("index", index)
                                .put("kind", descriptor.kind.name.lowercase())
                                .put("sha256", descriptor.sha256)
                                .put("byteCount", descriptor.byteCount)
                                .apply {
                                    descriptor.relativePath?.let { put("relativePath", it) }
                                },
                        )
                    }
                },
            )
        return root.toString().toByteArray(StandardCharsets.UTF_8).also { bytes ->
            require(bytes.size <= RemoteWorkHttpRequest.MAX_REMOTE_REQUEST_BYTES) {
                "Remote work manifest is too large"
            }
        }
    }

    private fun statusRequest(
        identity: RemoteWorkerConnectionIdentity,
        request: RemoteWorkHttpRequest,
    ): RemoteWorkStatusReceipt = response(identity, request) { lease ->
        if (lease.statusCode !in 200..299) throw RemoteWorkFailure("remote_work_request_rejected")
        decodeStatus(readJson(lease))
    }

    private fun unitRequest(
        identity: RemoteWorkerConnectionIdentity,
        request: RemoteWorkHttpRequest,
    ) = response(identity, request) { lease ->
        if (lease.statusCode !in 200..299) throw RemoteWorkFailure("remote_work_upload_rejected")
        if (lease.declaredContentLength != null && lease.declaredContentLength != 0L) {
            throw RemoteWorkFailure("remote_work_unexpected_response")
        }
        Unit
    }

    private fun jsonRequest(
        identity: RemoteWorkerConnectionIdentity,
        request: RemoteWorkHttpRequest,
    ): JSONObject = response(identity, request) { lease ->
        if (lease.statusCode !in 200..299) throw RemoteWorkFailure("remote_work_request_rejected")
        readJson(lease)
    }

    private fun <T> response(
        identity: RemoteWorkerConnectionIdentity,
        request: RemoteWorkHttpRequest,
        block: (RemoteWorkHttpResponse) -> T,
    ): T {
        val call = calls.create(identity, request)
        return try {
            call.use { it.execute().use(block) }
        } catch (known: RemoteWorkFailure) {
            throw known
        } catch (_: Exception) {
            throw RemoteWorkFailure("remote_work_protocol_failed")
        }
    }

    private fun readJson(response: RemoteWorkHttpResponse): JSONObject {
        require(response.contentType.startsWith("application/json", ignoreCase = true)) {
            "Remote worker response is not JSON"
        }
        val bytes = response.body.readBounded(MAX_JSON_RESPONSE_BYTES)
        return JsonContract.parseObject(bytes.toString(StandardCharsets.UTF_8), MAX_JSON_RESPONSE_BYTES)
    }

    private fun decodeStatus(root: JSONObject): RemoteWorkStatusReceipt {
        root.requireOnly(STATUS_FIELDS)
        require(root.getInt("version") == REMOTE_WORK_PROTOCOL_VERSION)
        val artifacts = root.getJSONArray("resultArtifacts").let { array ->
            require(array.length() <= RemoteWorkLimits.MAX_ARTIFACTS)
            (0 until array.length()).map { index ->
                array.getJSONObject(index).let { value ->
                    value.requireOnly(RESULT_ARTIFACT_FIELDS)
                    RemoteWorkResultArtifact(
                        opaqueRemoteId = value.getString("opaqueRemoteId"),
                        displayName = value.getString("displayName"),
                        mimeType = value.getString("mimeType"),
                        byteCount = value.getLong("byteCount"),
                        sha256 = value.getString("sha256"),
                    )
                }
            }
        }
        val outputJson = if (root.isNull("output")) {
            null
        } else {
            root.getJSONObject("output").toString().also {
                require(it.toByteArray(StandardCharsets.UTF_8).size <= MAX_OUTPUT_JSON_BYTES)
            }
        }
        return RemoteWorkStatusReceipt(
            operationId = RemoteWorkOperationId(root.getString("operationId")),
            executionId = RemoteExecutionId(root.getString("executionId")),
            worker = RemoteWorkerId(root.getString("worker")),
            configurationDigest = root.getString("configurationDigest"),
            requestDigest = root.getString("requestDigest"),
            phase = RemoteWorkPhase.valueOf(root.getString("phase")),
            resultArtifacts = artifacts,
            outputJson = outputJson,
            observedAtEpochMillis = nowEpochMillis().also { require(it >= 0L) },
        )
    }

    private companion object {
        const val PROBE_PATH = "/v1/probe"
        const val UPLOAD_CHUNK_BYTES = 1024 * 1024
        const val MAX_JSON_RESPONSE_BYTES = 1024 * 1024
        val PROBE_FIELDS = setOf("version", "worker", "configurationDigest", "adapters")
        val ADAPTER_FIELDS = setOf("id", "version", "description")
        val STATUS_FIELDS = setOf(
            "version",
            "operationId",
            "executionId",
            "worker",
            "configurationDigest",
            "requestDigest",
            "phase",
            "resultArtifacts",
            "output",
        )
        val RESULT_ARTIFACT_FIELDS = setOf(
            "opaqueRemoteId",
            "displayName",
            "mimeType",
            "byteCount",
            "sha256",
        )
    }
}

internal class OkHttpPinnedRemoteWorkerTransport(
    authenticator: RemoteWorkRequestAuthenticator,
    baseClient: OkHttpClient = OkHttpClient(),
    nowEpochMillis: () -> Long = System::currentTimeMillis,
) : RemoteWorkerTransport by ProtocolRemoteWorkerTransport(
    calls = OkHttpPinnedRemoteWorkCallFactory(authenticator, baseClient),
    nowEpochMillis = nowEpochMillis,
)

private fun operationPath(operationId: RemoteWorkOperationId): String =
    "/v1/operations/${operationId.value}"

private fun JSONObject.requireOnly(fields: Set<String>) {
    require(keys().asSequence().toSet() == fields) { "Unexpected remote worker response fields" }
}

private fun InputStream.readBounded(maximumBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (count == 0) continue
        require(output.size() + count <= maximumBytes) { "Remote worker response is too large" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun InputStream.readChunk(buffer: ByteArray): Int {
    var offset = 0
    while (offset < buffer.size) {
        val count = read(buffer, offset, buffer.size - offset)
        if (count < 0) break
        if (count > 0) offset += count
    }
    return offset
}

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
