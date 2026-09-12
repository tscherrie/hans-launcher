package ai.hans.standard.browser

import ai.hans.standard.artifacts.ArtifactHandle
import ai.hans.standard.artifacts.AtomicArtifactStore
import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolCancellationDisposition
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.work.WorkHttpCall
import ai.hans.standard.work.WorkHttpRequest
import ai.hans.standard.work.WorkHttpResponse
import ai.hans.standard.work.WorkHttpResponseMetadata
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BrowserDynamicToolsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun visibleNavigationIsAdvertisedOnlyWithCompleteIsolationProof() {
        val unavailable = BrowserDynamicToolCatalog.namespace(VisibleBrowserReadiness.UNAVAILABLE)
        assertEquals(setOf("read_page", "download"), unavailable.tools.map { it.name }.toSet())

        val incomplete = BrowserDynamicToolCatalog.namespace(
            VisibleBrowserReadiness(
                available = true,
                isolatedEphemeralProfile = true,
                publicHttpPolicyEnforced = true,
                freshUrlPostconditions = false,
            ),
        )
        assertFalse(incomplete.tools.any { it.name == "navigate_visible" })

        val ready = BrowserDynamicToolCatalog.namespace(VisibleBrowserReadiness.AVAILABLE_ISOLATED)
        assertEquals(setOf("read_page", "download", "navigate_visible"), ready.tools.map { it.name }.toSet())
    }

    @Test
    fun readPageUsesAnonymousGetAndReturnsOnlyBoundedTextMetadata() {
        var observed: WorkHttpRequest? = null
        val fixture = fixture { request ->
            observed = request
            responseCall(
                statusCode = 200,
                finalUrl = "https://example.com/final",
                contentType = "text/plain; charset=utf-8",
                body = "Hallo Browser".toByteArray(),
                headers = mapOf("set-cookie" to listOf("session=must-not-leak")),
            )
        }

        val result = execute(
            fixture.executor,
            "read_page",
            JSONObject().put("url", "https://example.com/start"),
        )
        val json = JSONObject(result.contentText)

        assertTrue(result.contentText, result.success)
        assertEquals("GET", observed?.method)
        assertEquals(emptyMap<String, String>(), observed?.headers)
        assertEquals(null, observed?.body)
        assertFalse(observed?.allowPrivateNetwork ?: true)
        assertEquals("Hallo Browser", json.getString("text"))
        assertEquals("https://example.com/final", json.getString("finalUrl"))
        assertFalse(json.has("headers"))
        assertFalse(json.toString().contains("session="))
        assertTrue(fixture.artifacts.listMetadata().isEmpty())
    }

    @Test
    fun readPageRejectsBinaryOrOversizeBodies() {
        val binary = fixture {
            responseCall(
                statusCode = 200,
                finalUrl = it.url,
                contentType = "image/png",
                body = byteArrayOf(1, 2, 3),
            )
        }
        val binaryResult = execute(
            binary.executor,
            "read_page",
            JSONObject().put("url", "https://example.com/picture.png"),
        )
        assertFalse(binaryResult.success)
        assertEquals("browser_content_not_text", JSONObject(binaryResult.contentText).getString("errorCode"))

        val oversized = fixture {
            responseCall(
                statusCode = 200,
                finalUrl = it.url,
                contentType = "text/plain",
                body = "12345".toByteArray(),
                declaredLength = 5L,
            )
        }
        val oversizedResult = execute(
            oversized.executor,
            "read_page",
            JSONObject().put("url", "https://example.com/text").put("maxBytes", 4),
        )
        assertFalse(oversizedResult.success)
        assertEquals("browser_read_limit_exceeded", JSONObject(oversizedResult.contentText).getString("errorCode"))
    }

    @Test
    fun downloadCommitsAtomicallyAndReturnsOnlyOpaqueArtifactIdentity() {
        val bytes = ByteArray(8_192) { index -> (index % 251).toByte() }
        val fixture = fixture {
            responseCall(
                statusCode = 200,
                finalUrl = "https://cdn.example.com/files/report.pdf",
                contentType = "application/pdf",
                body = bytes,
            )
        }

        val result = execute(
            fixture.executor,
            "download",
            JSONObject().put("url", "https://example.com/report"),
        )
        val json = JSONObject(result.contentText)

        assertTrue(result.contentText, result.success)
        val handle = ArtifactHandle(json.getString("artifactHandle"))
        assertTrue(handle.value.startsWith("art_"))
        assertFalse(json.has("path"))
        assertFalse(json.has("body"))
        assertEquals("report.pdf", json.getString("displayName"))
        assertEquals(bytes.toList(), fixture.artifacts.open(handle).use { it.readBytes().toList() })
        assertEquals(json.getString("sha256"), fixture.artifacts.metadata(handle).sha256)
    }

    @Test
    fun failedDownloadNeverLeavesACommittedArtifact() {
        val fixture = fixture {
            responseCall(
                statusCode = 200,
                finalUrl = it.url,
                contentType = "application/octet-stream",
                body = ByteArray(9),
                declaredLength = 9L,
            )
        }

        val result = execute(
            fixture.executor,
            "download",
            JSONObject().put("url", "https://example.com/file").put("maxBytes", 8),
        )

        assertFalse(result.success)
        assertEquals("browser_download_limit_exceeded", JSONObject(result.contentText).getString("errorCode"))
        assertTrue(fixture.artifacts.listMetadata().isEmpty())
    }

    @Test
    fun unsafeUrlsFailBeforeTransportAndUnknownArgumentsFailClosed() {
        var started = false
        val fixture = fixture {
            started = true
            error("transport must not start")
        }

        listOf(
            "file:///sdcard/secret",
            "https://user:password@example.com/private",
            "https://example.com/page#fragment",
            "http://metadata.google.internal/latest",
        ).forEach { url ->
            val result = execute(fixture.executor, "read_page", JSONObject().put("url", url))
            assertFalse(url, result.success)
        }
        val extra = execute(
            fixture.executor,
            "download",
            JSONObject().put("url", "https://example.com/file").put("cookies", "yes"),
        )
        assertFalse(extra.success)
        assertFalse(started)
    }

    @Test
    fun visibleNavigationRequiresFreshExactUrlBoundPostcondition() {
        var observedRequest: VisibleBrowserNavigationRequest? = null
        val goodFallback = object : VisibleBrowserFallback {
            override fun readiness() = VisibleBrowserReadiness.AVAILABLE_ISOLATED

            override fun navigate(
                request: VisibleBrowserNavigationRequest,
                cancellation: DynamicToolCancellation,
            ): VisibleBrowserNavigationReceipt {
                observedRequest = request
                return VisibleBrowserNavigationReceipt(
                    operationNonce = request.operationNonce,
                    requestedUrl = request.requestedUrl,
                    actionStarted = true,
                    finalUrl = "https://example.com/final",
                    before = VisibleBrowserObservation(41, "https://example.com/before"),
                    after = VisibleBrowserObservation(42, "https://example.com/final"),
                    postcondition = VisibleBrowserPostconditionStatus.VERIFIED,
                )
            }
        }
        val fixture = fixture(visible = goodFallback) { error("HTTP not expected") }
        val result = execute(
            fixture.executor,
            "navigate_visible",
            JSONObject().put("url", "https://example.com/start"),
        )
        assertTrue(result.contentText, result.success)
        assertTrue(JSONObject(result.contentText).getBoolean("postconditionVerified"))
        assertTrue(observedRequest?.operationNonce?.startsWith("br_") == true)

        val ambiguous = object : VisibleBrowserFallback {
            override fun readiness() = VisibleBrowserReadiness.AVAILABLE_ISOLATED

            override fun navigate(
                request: VisibleBrowserNavigationRequest,
                cancellation: DynamicToolCancellation,
            ) = VisibleBrowserNavigationReceipt(
                operationNonce = request.operationNonce,
                requestedUrl = request.requestedUrl,
                actionStarted = true,
                finalUrl = request.requestedUrl,
                before = VisibleBrowserObservation(4, "https://example.com/before"),
                after = VisibleBrowserObservation(4, request.requestedUrl),
                postcondition = VisibleBrowserPostconditionStatus.VERIFIED,
            )
        }
        val failed = execute(
            fixture(visible = ambiguous) { error("HTTP not expected") }.executor,
            "navigate_visible",
            JSONObject().put("url", "https://example.com/start"),
        )
        assertFalse(failed.success)
        assertEquals(
            "visible_browser_postcondition_not_verified",
            JSONObject(failed.contentText).getString("errorCode"),
        )
    }

    @Test
    fun cancellationCancelsTransportAndCannotCommitPartialDownload() {
        val readEntered = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val transportCancelled = AtomicBoolean(false)
        val completed = AtomicBoolean(false)
        val executorService = Executors.newSingleThreadExecutor()
        val fixture = fixture(executor = executorService) {
            object : WorkHttpCall {
                override fun execute(): WorkHttpResponse = object : WorkHttpResponse {
                    override val metadata = WorkHttpResponseMetadata(
                        statusCode = 200,
                        finalUrl = it.url,
                        headers = emptyMap(),
                        contentType = "application/octet-stream",
                        declaredContentLength = null,
                    )
                    override val body: InputStream = object : InputStream() {
                        private var emitted = false

                        override fun read(): Int {
                            readEntered.countDown()
                            releaseRead.await(5, TimeUnit.SECONDS)
                            if (emitted) return -1
                            emitted = true
                            return 7
                        }
                    }

                    override fun close() = Unit
                }

                override fun cancel() {
                    transportCancelled.set(true)
                    releaseRead.countDown()
                }
            }
        }
        try {
            val handle = fixture.executor.executeCancellable(
                call("download", JSONObject().put("url", "https://example.com/slow")),
                DynamicToolCancellation.NONE,
            ) { completed.set(true) }

            assertTrue(readEntered.await(5, TimeUnit.SECONDS))
            assertEquals(
                DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED,
                handle.cancel(),
            )
            executorService.shutdown()
            assertTrue(executorService.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue(transportCancelled.get())
            assertFalse(completed.get())
            assertTrue(fixture.artifacts.listMetadata().isEmpty())
        } finally {
            releaseRead.countDown()
            executorService.shutdownNow()
        }
    }

    @Test
    fun operationNonceBindsThreadTurnAndCall() {
        val receipts = mutableListOf<String>()
        val fallback = object : VisibleBrowserFallback {
            override fun readiness() = VisibleBrowserReadiness.AVAILABLE_ISOLATED

            override fun navigate(
                request: VisibleBrowserNavigationRequest,
                cancellation: DynamicToolCancellation,
            ): VisibleBrowserNavigationReceipt {
                receipts += request.operationNonce
                return VisibleBrowserNavigationReceipt(
                    request.operationNonce,
                    request.requestedUrl,
                    true,
                    request.requestedUrl,
                    VisibleBrowserObservation(1, "https://example.com/old"),
                    VisibleBrowserObservation(2, request.requestedUrl),
                    VisibleBrowserPostconditionStatus.VERIFIED,
                )
            }
        }
        val fixture = fixture(visible = fallback) { error("HTTP not expected") }
        execute(fixture.executor, "navigate_visible", JSONObject().put("url", "https://example.com/a"))
        execute(fixture.executor, "navigate_visible", JSONObject().put("url", "https://example.com/a"))

        assertEquals(2, receipts.size)
        assertNotEquals(receipts[0], receipts[1])
    }

    @Test
    fun receiptRejectsCrossRequestOrNonCanonicalFinalUrl() {
        val request = VisibleBrowserNavigationRequest("br_${"1".repeat(64)}", "https://example.com/start")
        val receipt = VisibleBrowserNavigationReceipt(
            operationNonce = "br_${"2".repeat(64)}",
            requestedUrl = request.requestedUrl,
            actionStarted = true,
            finalUrl = "https://example.com/%7Euser",
            before = VisibleBrowserObservation(1, "https://example.com/"),
            after = VisibleBrowserObservation(2, "https://example.com/%7Euser"),
            postcondition = VisibleBrowserPostconditionStatus.VERIFIED,
        )
        assertFalse(receipt.proves(request))
        assertThrows(IllegalArgumentException::class.java) {
            BrowserUrlPolicy.requirePublicHttpUrl("javascript:alert(1)")
        }
    }

    private fun fixture(
        visible: VisibleBrowserFallback = VisibleBrowserFallback.UNAVAILABLE,
        executor: Executor = Executor(Runnable::run),
        factory: (WorkHttpRequest) -> WorkHttpCall,
    ): Fixture {
        val boundary = temporary.newFolder("browser-${System.nanoTime()}")
        val artifacts = AtomicArtifactStore(boundary.resolve("artifacts"), boundary)
        return Fixture(
            artifacts,
            BrowserDynamicToolExecutor(
                artifactStore = artifacts,
                httpCallFactory = StatelessBrowserHttpCallFactory(factory),
                visibleFallback = visible,
                backgroundExecutor = executor,
            ),
        )
    }

    private fun execute(
        executor: BrowserDynamicToolExecutor,
        tool: String,
        arguments: JSONObject,
    ): DynamicToolExecutionResult {
        var output: DynamicToolExecutionResult? = null
        executor.execute(call(tool, arguments)) { output = it }
        return requireNotNull(output)
    }

    private fun call(tool: String, arguments: JSONObject) = DynamicToolCallParams(
        threadId = "thread-browser",
        turnId = "turn-browser",
        callId = "call-$tool-${System.nanoTime()}",
        namespace = BrowserDynamicToolCatalog.NAMESPACE,
        tool = tool,
        argumentsJson = arguments.toString(),
    )

    private fun responseCall(
        statusCode: Int,
        finalUrl: String,
        contentType: String?,
        body: ByteArray,
        declaredLength: Long? = body.size.toLong(),
        headers: Map<String, List<String>> = emptyMap(),
    ) = object : WorkHttpCall {
        override fun execute(): WorkHttpResponse = object : WorkHttpResponse {
            override val metadata = WorkHttpResponseMetadata(
                statusCode = statusCode,
                finalUrl = finalUrl,
                headers = headers,
                contentType = contentType,
                declaredContentLength = declaredLength,
            )
            override val body: InputStream = ByteArrayInputStream(body)
            override fun close() = Unit
        }

        override fun cancel() = Unit
    }

    private data class Fixture(
        val artifacts: AtomicArtifactStore,
        val executor: BrowserDynamicToolExecutor,
    )
}
