package ai.hans.standard.work.remote

import ai.hans.standard.artifacts.ArtifactHandle
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolRemoteWorkerTransportTest {
    @Test
    fun probeAndChunkedSubmitUseBoundedHandleFreeWireFormat() {
        val identity = identity()
        val payloadBytes = ByteArray(1024 * 1024 + 9) { (it % 251).toByte() }
        val artifactHandle = ArtifactHandle("art_${"1".repeat(64)}")
        val descriptor = RemoteWorkPayloadDescriptor(
            RemoteWorkPayloadKind.ARTIFACT,
            sha256(payloadBytes),
            payloadBytes.size.toLong(),
            artifact = artifactHandle,
        )
        val source = OnePayloadSource(descriptor, payloadBytes)
        val request = RemoteWorkRequest(
            RemoteWorkOperationId("wire-submit"),
            identity.workerId,
            RemoteWorkAdapterId("linux.build"),
            null,
            listOf(artifactHandle),
            "{\"target\":\"release\"}",
            RemoteWorkLimits(),
        )
        val calls = CapturingCalls(identity, request)
        val transport = ProtocolRemoteWorkerTransport(calls, nowEpochMillis = { 5_000L })

        val probe = transport.probe(identity)
        assertTrue(probe.proves(identity))
        val receipt = transport.submit(identity, request, source)
        assertEquals(RemoteWorkPhase.ACCEPTED, receipt.phase)

        val manifest = calls.requests.single { it.path.endsWith("/manifest") }
        val manifestText = manifest.body.decodeToString()
        assertFalse(manifestText.contains(artifactHandle.value))
        assertFalse(manifestText.contains(identity.deviceKeyAlias))
        assertFalse(manifestText.contains(identity.endpoint))
        val chunks = calls.requests.filter { "/chunks/" in it.path }
        assertEquals(2, chunks.size)
        assertEquals(payloadBytes.size, chunks.sumOf { it.body.size })
        assertEquals(receipt.requestDigest, JSONObject(manifestText).getString("requestDigest"))
    }

    @Test
    fun absentExactLookupReturnsNullWithoutSubmit() {
        val identity = identity()
        val request = RemoteWorkRequest(
            RemoteWorkOperationId("lookup-missing"),
            identity.workerId,
            RemoteWorkAdapterId("linux.build"),
            null,
            emptyList(),
            "{}",
            RemoteWorkLimits(),
        )
        val calls = CapturingCalls(identity, request, lookupMissing = true)
        val transport = ProtocolRemoteWorkerTransport(calls, nowEpochMillis = { 5_000L })
        assertNull(transport.lookup(identity, request.operationId, "ab".repeat(32)))
        assertEquals(listOf("/v1/operations/lookup-missing/lookup"), calls.requests.map { it.path })
    }

    private class OnePayloadSource(
        private val descriptor: RemoteWorkPayloadDescriptor,
        private val bytes: ByteArray,
    ) : RemoteWorkPayloadSource {
        override fun descriptors(request: RemoteWorkRequest) = listOf(descriptor)
        override fun open(descriptor: RemoteWorkPayloadDescriptor): RemoteWorkPayloadLease {
            require(descriptor == this.descriptor)
            return object : RemoteWorkPayloadLease {
                override val descriptor = this@OnePayloadSource.descriptor
                override val input: InputStream = ByteArrayInputStream(bytes)
                override fun close() = input.close()
            }
        }
    }

    private class CapturingCalls(
        private val identity: RemoteWorkerConnectionIdentity,
        private val request: RemoteWorkRequest,
        private val lookupMissing: Boolean = false,
    ) : RemoteWorkHttpCallFactory {
        val requests = mutableListOf<RemoteWorkHttpRequest>()
        private var requestDigest: String? = null

        override fun create(identity: RemoteWorkerConnectionIdentity, request: RemoteWorkHttpRequest): RemoteWorkHttpCall {
            require(identity == this.identity)
            requests += request.copy(body = request.body.copyOf())
            return object : RemoteWorkHttpCall {
                override fun execute(): RemoteWorkHttpResponse = when {
                    request.path == "/v1/probe" -> json(
                        JSONObject()
                            .put("version", REMOTE_WORK_PROTOCOL_VERSION)
                            .put("worker", identity.workerId.value)
                            .put("configurationDigest", identity.configurationDigest)
                            .put(
                                "adapters",
                                JSONArray().put(
                                    JSONObject()
                                        .put("id", "linux.build")
                                        .put("version", "1")
                                        .put("description", "Build"),
                                ),
                            ),
                    )
                    request.path.endsWith("/manifest") -> {
                        requestDigest = JSONObject(request.body.decodeToString()).getString("requestDigest")
                        empty(204)
                    }
                    "/chunks/" in request.path -> empty(204)
                    request.path.endsWith("/submit") -> json(status(requireNotNull(requestDigest)))
                    request.path.endsWith("/lookup") && lookupMissing -> empty(404)
                    else -> error("unexpected request")
                }

                override fun cancel() = Unit
                override fun close() = Unit
            }
        }

        private fun status(digest: String) = JSONObject()
            .put("version", REMOTE_WORK_PROTOCOL_VERSION)
            .put("operationId", request.operationId.value)
            .put("executionId", "execution-1")
            .put("worker", identity.workerId.value)
            .put("configurationDigest", identity.configurationDigest)
            .put("requestDigest", digest)
            .put("phase", RemoteWorkPhase.ACCEPTED.name)
            .put("resultArtifacts", JSONArray())
            .put("output", JSONObject.NULL)

        private fun json(value: JSONObject): RemoteWorkHttpResponse = response(
            200,
            "application/json",
            value.toString().toByteArray(),
        )

        private fun empty(status: Int): RemoteWorkHttpResponse = response(
            status,
            "",
            ByteArray(0),
        )

        private fun response(
            status: Int,
            type: String,
            bytes: ByteArray,
        ) = object : RemoteWorkHttpResponse {
            override val statusCode = status
            override val contentType = type
            override val declaredContentLength = bytes.size.toLong()
            override val body: InputStream = ByteArrayInputStream(bytes)
            override fun close() = body.close()
        }
    }

    private fun identity() = RemoteWorkerConnectionIdentity(
        RemoteWorkerId("worker-1"),
        "https://worker.example/",
        "ab".repeat(32),
        "remote.key",
    )
}
