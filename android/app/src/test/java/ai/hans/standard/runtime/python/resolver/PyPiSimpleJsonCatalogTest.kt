package ai.hans.standard.runtime.python.resolver

import ai.hans.standard.runtime.python.PythonEnvironmentContract
import ai.hans.standard.runtime.python.PythonEnvironmentTarget
import ai.hans.standard.work.WorkHttpCall
import ai.hans.standard.work.WorkHttpCallFactory
import ai.hans.standard.work.WorkHttpRequest
import ai.hans.standard.work.WorkHttpResponse
import ai.hans.standard.work.WorkHttpResponseMetadata
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PyPiSimpleJsonCatalogTest {
    @Test
    fun parsesPep691AndPep658MetadataIntoExactCandidate() {
        val metadata = (
            "Metadata-Version: 2.4\n" +
                "Name: Demo_Pkg\n" +
                "Version: 1.2.0\n" +
                "Requires-Python: >=3.14\n" +
                "Requires-Dist: child>=2; sys_platform == 'android'\n\n"
            ).toByteArray()
        val wheelUrl = "https://files.pythonhosted.org/packages/demo_pkg-1.2.0-py3-none-any.whl"
        val project = projectJson(
            fileName = "demo_pkg-1.2.0-py3-none-any.whl",
            wheelUrl = wheelUrl,
            metadataDigest = PythonEnvironmentContract.sha256(metadata),
            requiresPython = ">=3.14",
            yanked = "bad upload",
        )
        val factory = QueueFactory(
            queuedResponse("https://pypi.org/simple/demo-pkg/", project, "application/vnd.pypi.simple.v1+json"),
            queuedResponse("$wheelUrl.metadata", metadata, "text/plain"),
        )
        val catalog = PyPiSimpleJsonCatalog(PythonResolverHttpClient(factory, MemoryPythonResolverHttpCache()))
        val result = catalog.project("demo-pkg", TARGET, PythonResolutionCancellation.NONE)
        val candidate = result.candidates.single()
        assertEquals("demo-pkg", candidate.normalizedName)
        assertEquals("1.2.0", candidate.version)
        assertEquals(listOf("child>=2; sys_platform == 'android'"), candidate.requiresDist)
        assertTrue(candidate.yanked)
        assertEquals(2, factory.requests.size)
        assertTrue(factory.requests.first().headers["Accept"]!!.contains("pypi.simple"))
    }

    @Test
    fun etagCacheRevalidatesAndRejectsEscapedFinalOrigin() {
        val body = "{\"meta\":{\"api-version\":\"1.4\"},\"name\":\"demo\",\"files\":[]}".toByteArray()
        val cache = MemoryPythonResolverHttpCache()
        val first = QueueFactory(
            queuedResponse(
                "https://pypi.org/simple/demo/",
                body,
                "application/vnd.pypi.simple.v1+json",
                headers = mapOf("etag" to listOf("\"v1\"")),
            ),
        )
        PythonResolverHttpClient(first, cache).get(
            "https://pypi.org/simple/demo/",
            1024,
            setOf("pypi.org"),
            "application/json",
            PythonResolutionCancellation.NONE,
        )
        val second = QueueFactory(
            queuedResponse(
                "https://pypi.org/simple/demo/",
                ByteArray(0),
                null,
                status = 304,
            ),
        )
        val cached = PythonResolverHttpClient(second, cache).get(
            "https://pypi.org/simple/demo/",
            1024,
            setOf("pypi.org"),
            "application/json",
            PythonResolutionCancellation.NONE,
        )
        assertTrue(cached.body.contentEquals(body))
        assertEquals("\"v1\"", second.requests.single().headers["If-None-Match"])

        val escaped = QueueFactory(
            queuedResponse("https://evil.example/simple/demo/", body, "application/json"),
        )
        val error = runCatching {
            PythonResolverHttpClient(escaped).get(
                "https://pypi.org/simple/demo/",
                1024,
                setOf("pypi.org"),
                "application/json",
                PythonResolutionCancellation.NONE,
            )
        }.exceptionOrNull() as PythonResolutionException
        assertEquals(PythonResolutionErrorCode.INDEX_PROTOCOL_ERROR, error.code)
    }

    @Test
    fun metadataHashMismatchFailsInsteadOfCreatingLockInput() {
        val metadata = "Name: demo\nVersion: 1.0\n\n".toByteArray()
        val wheelUrl = "https://files.pythonhosted.org/packages/demo-1.0-py3-none-any.whl"
        val factory = QueueFactory(
            queuedResponse(
                "https://pypi.org/simple/demo/",
                projectJson("demo-1.0-py3-none-any.whl", wheelUrl, "0".repeat(64), null, false),
                "application/vnd.pypi.simple.v1+json",
            ),
            queuedResponse("$wheelUrl.metadata", metadata, "text/plain"),
        )
        val error = runCatching {
            PyPiSimpleJsonCatalog(PythonResolverHttpClient(factory)).project(
                "demo-pkg",
                TARGET,
                PythonResolutionCancellation.NONE,
            )
        }.exceptionOrNull() as PythonResolutionException
        assertEquals(PythonResolutionErrorCode.HASH_MISMATCH, error.code)
    }

    @Test
    fun incompatibleNativeAndMalformedMetadataAreNotCandidates() {
        val native = JSONObject()
            .put("filename", "demo-1.0-cp314-cp314-android_31_arm64_v8a.whl")
            .put("url", "https://files.pythonhosted.org/packages/native.whl")
            .put("hashes", JSONObject().put("sha256", "1".repeat(64)))
            .put("size", 123)
            .put("core-metadata", JSONObject().put("sha256", "2".repeat(64)))
        val json = JSONObject()
            .put("meta", JSONObject().put("api-version", "1.4"))
            .put("name", "demo")
            .put("files", JSONArray().put(native))
            .toString().toByteArray()
        val catalog = PyPiSimpleJsonCatalog(PythonResolverHttpClient(QueueFactory(
            queuedResponse("https://pypi.org/simple/demo/", json, "application/json"),
        )))
        assertTrue(catalog.project("demo", TARGET, PythonResolutionCancellation.NONE).candidates.isEmpty())
    }

    @Test
    fun acceptsCompatibleCp314AndMixedPureTagsButRejectsMalformedSimpleFields() {
        val exactMetadata = "Name: demo\nVersion: 2.0\nRequires-Python: >=3.14\n\n".toByteArray()
        val mixedMetadata = "Name: demo\nVersion: 1.0\nRequires-Python: >=3.14\n\n".toByteArray()
        val exactUrl = "https://files.pythonhosted.org/packages/demo-2.0-cp314-none-any.whl"
        val mixedUrl = "https://files.pythonhosted.org/packages/demo-1.0-py313.py314-none-any.whl"
        fun file(name: String, url: String, metadata: ByteArray, requiresPython: Any) = JSONObject()
            .put("filename", name)
            .put("url", url)
            .put("hashes", JSONObject().put("sha256", "a".repeat(64)))
            .put("size", 1234)
            .put("requires-python", requiresPython)
            .put("yanked", false)
            .put(
                "core-metadata",
                JSONObject().put("sha256", PythonEnvironmentContract.sha256(metadata)),
            )
        val simple = JSONObject()
            .put("meta", JSONObject().put("api-version", "1.4"))
            .put("name", "demo")
            .put(
                "files",
                JSONArray()
                    .put(file("demo-2.0-cp314-none-any.whl", exactUrl, exactMetadata, ">=3.14"))
                    .put(file("demo-1.0-py313.py314-none-any.whl", mixedUrl, mixedMetadata, ">=3.14"))
                    .put(file("demo-0.5-py3-none-any.whl", mixedUrl, mixedMetadata, JSONObject())),
            )
            .toString()
            .toByteArray()
        val factory = QueueFactory(
            queuedResponse("https://pypi.org/simple/demo/", simple, "application/json"),
            queuedResponse("$exactUrl.metadata", exactMetadata, "text/plain"),
            queuedResponse("$mixedUrl.metadata", mixedMetadata, "text/plain"),
        )
        val result = PyPiSimpleJsonCatalog(PythonResolverHttpClient(factory)).project(
            "demo",
            TARGET,
            PythonResolutionCancellation.NONE,
        )
        assertEquals(listOf("1.0", "2.0"), result.candidates.map { it.version })
        assertEquals(simple.size + exactMetadata.size + mixedMetadata.size, result.metadataBytes.toInt())
    }

    @Test
    fun byteLimitAndInflightCancellationStopNetworkWork() {
        val oversized = QueueFactory(
            queuedResponse("https://pypi.org/simple/demo/", ByteArray(5), "application/json"),
        )
        val sizeError = runCatching {
            PythonResolverHttpClient(oversized).get(
                "https://pypi.org/simple/demo/",
                4,
                setOf("pypi.org"),
                "application/json",
                PythonResolutionCancellation.NONE,
            )
        }.exceptionOrNull() as PythonResolutionException
        assertEquals(PythonResolutionErrorCode.RESOURCE_LIMIT_EXCEEDED, sizeError.code)

        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val factory = WorkHttpCallFactory {
            object : WorkHttpCall {
                override fun execute(): WorkHttpResponse {
                    entered.countDown()
                    released.await(5, TimeUnit.SECONDS)
                    throw IOException("cancelled")
                }

                override fun cancel() {
                    released.countDown()
                }
            }
        }
        val cancellation = MutablePythonResolutionCancellation()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<Throwable?> {
                runCatching {
                    PythonResolverHttpClient(factory).get(
                        "https://pypi.org/simple/demo/",
                        1024,
                        setOf("pypi.org"),
                        "application/json",
                        cancellation,
                    )
                }.exceptionOrNull()
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            cancellation.cancel()
            assertTrue(future.get(2, TimeUnit.SECONDS) is PythonResolutionCancelledException)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun projectJson(
        fileName: String,
        wheelUrl: String,
        metadataDigest: String,
        requiresPython: String?,
        yanked: Any,
    ): ByteArray = JSONObject()
        .put("meta", JSONObject().put("api-version", "1.4"))
        .put("name", "demo-pkg")
        .put(
            "files",
            JSONArray().put(
                JSONObject()
                    .put("filename", fileName)
                    .put("url", wheelUrl)
                    .put("hashes", JSONObject().put("sha256", "a".repeat(64)))
                    .put("size", 1234)
                    .put("requires-python", requiresPython ?: JSONObject.NULL)
                    .put("yanked", yanked)
                    .put("core-metadata", JSONObject().put("sha256", metadataDigest)),
            ),
        )
        .toString().toByteArray()

    private data class Response(
        val finalUrl: String,
        val body: ByteArray,
        val contentType: String?,
        val status: Int,
        val headers: Map<String, List<String>>,
    )

    private class QueueFactory(vararg responses: Response) : WorkHttpCallFactory {
        val queue = ArrayDeque(responses.toList())
        val requests = mutableListOf<WorkHttpRequest>()
        override fun create(request: WorkHttpRequest): WorkHttpCall {
            requests += request
            val response = queue.removeFirst()
            return object : WorkHttpCall {
                override fun execute(): WorkHttpResponse = object : WorkHttpResponse {
                    override val metadata = WorkHttpResponseMetadata(
                        response.status,
                        response.finalUrl,
                        response.headers,
                        response.contentType,
                        response.body.size.toLong(),
                    )
                    override val body = ByteArrayInputStream(response.body)
                    override fun close() = Unit
                }
                override fun cancel() = Unit
            }
        }
    }

    private fun queuedResponse(
        finalUrl: String,
        body: ByteArray,
        contentType: String?,
        status: Int = 200,
        headers: Map<String, List<String>> = emptyMap(),
    ) = Response(finalUrl, body, contentType, status, headers)

    private companion object {
        val TARGET = PythonEnvironmentTarget("3.14.7", "cp314", "arm64-v8a", 31)
    }
}
