package ai.hans.standard.work

import ai.hans.standard.artifacts.ArtifactOrigin
import ai.hans.standard.artifacts.AtomicArtifactStore
import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.workspace.PrivateWorkspaceStore
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.util.concurrent.Executor
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkUtilityDynamicToolsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun searchReplaceHashAndZipRoundTripUseImmutableHandles() {
        val fixture = fixture()
        val original = fixture.workspaces.begin().use { transaction ->
            transaction.write("src/hello.txt") { it.write("Hallo Welt\nNoch eine Welt\n".toByteArray()) }
            transaction.write("README.md") { it.write("# Beispiel\n".toByteArray()) }
            transaction.commit()
        }

        val search = execute(
            fixture.executor,
            "search_text",
            JSONObject()
                .put("workspaceHandle", original.handle.value)
                .put("query", "welt")
                .put("caseSensitive", false),
        )
        assertTrue(search.success)
        assertEquals(2, JSONObject(search.contentText).getJSONArray("matches").length())

        val replaced = execute(
            fixture.executor,
            "replace_text",
            JSONObject()
                .put("workspaceHandle", original.handle.value)
                .put("relativePath", "src/hello.txt")
                .put("oldText", "Welt")
                .put("newText", "Android"),
        )
        val updatedHandle = JSONObject(replaced.contentText).getString("workspaceHandle")
        assertFalse(updatedHandle == original.handle.value)
        assertEquals(
            "Hallo Android\nNoch eine Android\n",
            fixture.workspaces.openFile(ai.hans.standard.workspace.WorkspaceHandle(updatedHandle), "src/hello.txt")
                .use { String(it.readBytes()) },
        )
        assertEquals(
            "Hallo Welt\nNoch eine Welt\n",
            fixture.workspaces.openFile(original.handle, "src/hello.txt").use { String(it.readBytes()) },
        )

        val hash = execute(
            fixture.executor,
            "hash_file",
            JSONObject().put("workspaceHandle", updatedHandle).put("relativePath", "src/hello.txt"),
        )
        assertEquals(64, JSONObject(hash.contentText).getString("sha256").length)

        val zipped = execute(
            fixture.executor,
            "create_zip",
            JSONObject()
                .put("workspaceHandle", updatedHandle)
                .put("displayName", "project.zip"),
        )
        val artifactHandle = JSONObject(zipped.contentText).getString("artifactHandle")
        val extracted = execute(
            fixture.executor,
            "extract_zip",
            JSONObject().put("artifactHandle", artifactHandle),
        )
        assertTrue(extracted.contentText, extracted.success)
        val extractedHandle = ai.hans.standard.workspace.WorkspaceHandle(
            JSONObject(extracted.contentText).getString("workspaceHandle"),
        )
        assertEquals(
            "Hallo Android\nNoch eine Android\n",
            fixture.workspaces.openFile(extractedHandle, "src/hello.txt").use { String(it.readBytes()) },
        )
    }

    @Test
    fun zipTraversalIsRejectedAndTransactionNeverCommits() {
        val fixture = fixture()
        val bytes = java.io.ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("../escape.txt"))
                zip.write("bad".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val artifact = fixture.artifacts.put(
            "malicious.zip",
            "application/zip",
            ArtifactOrigin.USER_IMPORT,
            ByteArrayInputStream(bytes),
        )

        val result = execute(
            fixture.executor,
            "extract_zip",
            JSONObject().put("artifactHandle", artifact.handle.value),
        )

        assertFalse(result.success)
        assertFalse(temporary.root.resolve("escape.txt").exists())
        assertTrue(fixture.workspaceRoot.listFiles().orEmpty().none { !it.name.startsWith(".stage-") })
    }

    @Test
    fun httpBodyBecomesArtifactAndSensitiveResponseHeadersStayHidden() {
        var observed: WorkHttpRequest? = null
        val responseBytes = "server result".toByteArray()
        val factory = WorkHttpCallFactory { request ->
            observed = request
            object : WorkHttpCall {
                override fun execute(): WorkHttpResponse = object : WorkHttpResponse {
                    override val metadata = WorkHttpResponseMetadata(
                        statusCode = 200,
                        finalUrl = "https://example.com/result.txt",
                        headers = mapOf(
                            "content-type" to listOf("text/plain"),
                            "set-cookie" to listOf("secret=never-return"),
                        ),
                        contentType = "text/plain; charset=utf-8",
                        declaredContentLength = responseBytes.size.toLong(),
                    )
                    override val body: InputStream = ByteArrayInputStream(responseBytes)
                    override fun close() = Unit
                }

                override fun cancel() = Unit
            }
        }
        val fixture = fixture(factory = factory)

        val result = execute(
            fixture.executor,
            "http_request",
            JSONObject()
                .put("url", "https://example.com/api")
                .put("method", "POST")
                .put("headers", JSONObject().put("Content-Type", "application/json"))
                .put("bodyText", "{\"ok\":true}"),
        )
        val json = JSONObject(result.contentText)

        assertTrue(result.success)
        assertEquals("POST", observed?.method)
        assertEquals("{\"ok\":true}", observed?.body?.let(::String))
        assertFalse(json.getJSONObject("headers").has("set-cookie"))
        val artifact = fixture.artifacts.open(
            ai.hans.standard.artifacts.ArtifactHandle(json.getString("artifactHandle")),
        ).use { String(it.readBytes()) }
        assertEquals("server result", artifact)
    }

    @Test
    fun privateNetworkNeedsSeparateAuthorizationBeforeTransportStarts() {
        var started = false
        val fixture = fixture(
            factory = WorkHttpCallFactory {
                started = true
                error("must not start")
            },
            privateAuthorization = PrivateNetworkAuthorization.DENY,
        )

        val result = execute(
            fixture.executor,
            "http_request",
            JSONObject()
                .put("url", "http://192.168.1.20/open")
                .put("allowPrivateNetwork", true),
        )

        assertFalse(result.success)
        assertFalse(started)
        assertEquals("private_network_not_authorized", JSONObject(result.contentText).getString("errorCode"))
    }

    @Test
    fun endpointPolicyRejectsMetadataAndPrivateAddressesByDefault() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkNetworkEndpointPolicy.validateUri("http://169.254.169.254/latest")
                .also { WorkNetworkEndpointPolicy.requireAddressAllowed(InetAddress.getByName(it.host), true) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkNetworkEndpointPolicy.requireAddressAllowed(InetAddress.getByName("127.0.0.1"), false)
        }
        WorkNetworkEndpointPolicy.requireAddressAllowed(InetAddress.getByName("192.168.1.3"), true)
    }

    private fun fixture(
        factory: WorkHttpCallFactory = WorkHttpCallFactory { error("HTTP was not expected") },
        privateAuthorization: PrivateNetworkAuthorization = PrivateNetworkAuthorization.DENY,
    ): Fixture {
        val boundary = temporary.newFolder("work-${System.nanoTime()}")
        val workspaceRoot = boundary.resolve("workspaces")
        val workspaces = PrivateWorkspaceStore(workspaceRoot, boundary)
        val artifacts = AtomicArtifactStore(boundary.resolve("artifacts"), boundary)
        return Fixture(
            workspaces,
            artifacts,
            workspaceRoot,
            WorkUtilityDynamicToolExecutor(
                workspaces,
                artifacts,
                factory,
                privateAuthorization,
                Executor(Runnable::run),
            ),
        )
    }

    private fun execute(
        executor: WorkUtilityDynamicToolExecutor,
        tool: String,
        arguments: JSONObject,
    ): DynamicToolExecutionResult {
        var output: DynamicToolExecutionResult? = null
        executor.execute(
            DynamicToolCallParams(
                threadId = "thread-work",
                turnId = "turn-work",
                callId = "call-$tool-${System.nanoTime()}",
                namespace = WorkUtilityDynamicToolCatalog.NAMESPACE,
                tool = tool,
                argumentsJson = arguments.toString(),
            ),
        ) { output = it }
        return requireNotNull(output)
    }

    private data class Fixture(
        val workspaces: PrivateWorkspaceStore,
        val artifacts: AtomicArtifactStore,
        val workspaceRoot: java.io.File,
        val executor: WorkUtilityDynamicToolExecutor,
    )
}
