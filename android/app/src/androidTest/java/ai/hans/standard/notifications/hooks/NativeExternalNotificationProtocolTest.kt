package ai.hans.standard.notifications.hooks

import ai.hans.standard.BuildConfig
import ai.hans.standard.codex.AppServerRequests
import ai.hans.standard.codex.RequestId
import ai.hans.standard.integration.NativeNotificationExternalHistoryDecoder
import ai.hans.standard.integration.NativeNotificationExternalMessage
import ai.hans.standard.runtime.BoundedJsonLineFramer
import ai.hans.standard.runtime.CodexRuntimeContract
import ai.hans.standard.runtime.JsonLineReadResult
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real pinned native protocol, disposable Android emulators only. Its only model endpoint is a
 * credentialless HTTP/SSE fixture bound to 127.0.0.1. No personal CODEX_HOME/auth, external model,
 * RuntimeService, phone tools, microphone, audio, user messages or real notifications are used.
 * A parser rejection, disconnected child, or error before native ingestion is a failure, not a
 * passing compatibility result. The runner should additionally isolate emulator egress.
 */
@RunWith(AndroidJUnit4::class)
class NativeExternalNotificationProtocolTest {
    @Test
    fun idleToolOutputPersistsAsFunctionCallOutputUsingOnlyLocalMock() {
        Fixture.open(holdFirstResponse = false).use { fixture ->
            val event = fixture.event("idle")
            val threadId = fixture.startThread()
            val turnId = fixture.submit(threadId, event)
            fixture.awaitCompleted(threadId, turnId)
            fixture.assertHistory(threadId, turnId, listOf(event))
            fixture.assertModelSaw(event)
            fixture.restart()
            fixture.resume(threadId)
            fixture.assertHistory(threadId, turnId, listOf(event))
            fixture.assertNoCredentialsOrUnexpectedWork()
        }
    }

    @Test
    fun activeToolOutputJoinsExistingTurnUsingOnlyLocalMock() {
        Fixture.open(holdFirstResponse = true).use { fixture ->
            val first = fixture.event("first")
            val second = fixture.event("second")
            val threadId = fixture.startThread()
            val turnId = fixture.submit(threadId, first)
            fixture.awaitFirstModelRequest()
            fixture.assertModelSaw(first)
            // The real native model response remains open, so this is an actual active turn.
            val joinedTurnId = fixture.submit(threadId, second)
            assertEquals("Native tool output must join the active turn, not create a second one", turnId, joinedTurnId)
            fixture.releaseFirstResponse()
            fixture.awaitCompleted(threadId, turnId)
            fixture.assertModelSaw(second)
            fixture.assertHistory(threadId, turnId, listOf(first, second))
            fixture.assertOneTurnStarted(threadId, turnId)
            fixture.restart()
            fixture.resume(threadId)
            fixture.assertHistory(threadId, turnId, listOf(first, second))
            fixture.assertNoCredentialsOrUnexpectedWork()
        }
    }

    private class Fixture private constructor(
        private val directory: File,
        private val mock: LocalResponsesMock,
        private val executable: File,
    ) : AutoCloseable {
        private val home = File(directory, "home")
        private val workspace = File(directory, "workspace")
        private val temporary = File(directory, "tmp")
        private val frames = LinkedBlockingQueue<JSONObject>(256)
        private val observed = ArrayList<JSONObject>()
        private val readFailure = AtomicReference<Throwable>()
        private var process: Process? = null
        private var reader: Thread? = null
        private var stderr: Thread? = null
        private var nextId = 1L
        @Volatile private var closing = false

        fun event(label: String): NativeNotificationExternalMessage {
            val id = "native-fixture-$label-${UUID.randomUUID()}"
            val payload = JSONObject().put("schema", NativeNotificationExternalMessage.SCHEMA)
                .put("eventId", id).put("source", "synthetic_notification_fixture")
                .put("title", "Protocol fixture € 🧪").put("text", "No phone action requested.").toString()
            return NativeNotificationExternalMessage.create(id, payload)
        }

        private fun start() {
            check(process == null)
            readFailure.set(null)
            frames.clear()
            closing = false
            val overrides = listOf(
                "model_provider=\"notification_protocol_fixture\"",
                "model=\"gpt-6-sol\"",
                "model_providers.notification_protocol_fixture.name=\"Local protocol fixture\"",
                "model_providers.notification_protocol_fixture.base_url=\"${mock.baseUrl}\"",
                "model_providers.notification_protocol_fixture.requires_openai_auth=false",
                "model_providers.notification_protocol_fixture.wire_api=\"responses\"",
                "model_providers.notification_protocol_fixture.supports_websockets=false",
                "model_providers.notification_protocol_fixture.request_max_retries=0",
                "model_providers.notification_protocol_fixture.stream_max_retries=0",
                "model_providers.notification_protocol_fixture.stream_idle_timeout_ms=45000",
                "approval_policy=\"never\"", "sandbox_mode=\"read-only\"",
                "cli_auth_credentials_store=\"file\"",
                "allow_login_shell=false", "check_for_update_on_startup=false",
                "web_search=\"disabled\"", "tools.web_search=false",
                "features.api_key_model_discovery=false", "features.shell_tool=false",
                "features.unified_exec=false", "features.code_mode=false", "features.multi_agent=false",
                "features.apps=false", "features.plugins=false", "features.remote_plugin=false",
                "features.hooks=false", "features.memories=false", "features.remote_control=false",
                "memories.generate_memories=false", "memories.use_memories=false",
                "analytics.enabled=false", "feedback.enabled=false",
                "otel.exporter=\"none\"", "otel.metrics_exporter=\"none\"", "otel.trace_exporter=\"none\"",
            )
            val command = buildList {
                add(executable.absolutePath)
                add("--strict-config")
                overrides.forEach { add("-c"); add(it) }
            }
            val builder = ProcessBuilder(command).directory(workspace)
            builder.environment().run {
                clear()
                putAll(mapOf(
                    "ANDROID_ROOT" to "/system", "ANDROID_DATA" to "/data",
                    "HOME" to home.path, "CODEX_HOME" to home.path, "TMPDIR" to temporary.path,
                    "PATH" to "/system/bin", "SHELL" to "/system/bin/sh", "LANG" to "C.UTF-8",
                    "CODEX_INTERNAL_APP_SERVER_REMOTE_CONTROL_DISABLED" to "1",
                    // All HTTP destinations are intercepted locally; no inherited network/auth env.
                    "HTTP_PROXY" to mock.baseUrl, "HTTPS_PROXY" to mock.baseUrl,
                    "ALL_PROXY" to mock.baseUrl, "NO_PROXY" to "127.0.0.1,localhost",
                ))
                check(keys.none { it.contains("API_KEY") || it.contains("TOKEN") || it.contains("AUTH") })
            }
            val child = builder.start().also { process = it }
            reader = Thread({
                try {
                    val input = BufferedInputStream(child.inputStream)
                    val framer = BoundedJsonLineFramer(1024 * 1024)
                    while (true) when (val line = framer.readNext(input)) {
                        is JsonLineReadResult.Frame -> {
                            val value = JSONObject(line.bytes.toString(Charsets.UTF_8))
                            check(frames.offer(value)) { "Native fixture frame queue exceeded its bound" }
                        }
                        JsonLineReadResult.Eof -> if (closing) return@Thread else throw EOFException("Native fixture exited")
                        else -> throw IllegalStateException("Native fixture emitted an oversized or partial frame")
                    }
                } catch (failure: Throwable) {
                    if (!closing) readFailure.compareAndSet(null, failure)
                }
            }, "native-notification-fixture-stdout").apply { isDaemon = true; start() }
            stderr = Thread({
                runCatching { child.errorStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (input.read(buffer) >= 0) Unit // No stderr contents/auth/path leakage into results.
                } }
            }, "native-notification-fixture-stderr").apply { isDaemon = true; start() }
            val initId = nextId++
            val initialized = rpc(JSONObject(CodexRuntimeContract.initializeRequest(BuildConfig.VERSION_NAME, initId)))
            CodexRuntimeContract.parseInitializeResponse(initialized.toString(), initId, home.canonicalPath)
            send(JSONObject().put("method", "initialized").put("params", JSONObject()))
            val account = success(request("account/read", JSONObject().put("refreshToken", false)))
            assertTrue("Isolated native fixture must be explicitly signed out", account.has("account") && account.isNull("account"))
            assertFalse(File(home, "auth.json").exists())
        }

        fun startThread(): String {
            val result = success(request("thread/start", JSONObject()
                .put("cwd", workspace.canonicalPath).put("ephemeral", false).put("historyMode", "paginated")
                .put("baseInstructions", "Protocol fixture. Do not call any tools.")
                .put("developerInstructions", "No actions or commands. This is synthetic local protocol data.")))
            val threadId = result.getJSONObject("thread").getString("id")
            assertTrue(threadId.isNotBlank())
            assertEquals("paginated", result.getJSONObject("thread").getString("historyMode"))
            // Hans first persists exact thread metadata through memoryMode/set. Preserve that
            // storage barrier, but keep fixture memory disabled:
            // no memory generation, model turn, user message, or external provider is introduced.
            success(request("thread/memoryMode/set", JSONObject().put("threadId", threadId).put("mode", "disabled")))
            // Read only metadata here. A fresh paginated thread has no source rollout until its
            // first actual turn; assertHistory checks its full persisted history after that turn.
            val materialized = success(request("thread/read", JSONObject().put("threadId", threadId).put("includeTurns", false)))
                .getJSONObject("thread")
            assertEquals(threadId, materialized.getString("id"))
            assertEquals("paginated", materialized.getString("historyMode"))
            return threadId
        }

        fun submit(threadId: String, event: NativeNotificationExternalMessage): String {
            val encoded = AppServerRequests.notificationToolOutputTurnStart(RequestId.Number(nextId++), threadId, event.payloadJson)
            val request = JSONObject(encoded.json)
            val params = request.getJSONObject("params")
            assertEquals(0, params.getJSONArray("input").length())
            assertFalse(params.has("clientUserMessageId"))
            assertFalse(params.has("model"))
            val turn = success(rpc(request)).getJSONObject("turn")
            assertEquals("inProgress", turn.getString("status"))
            return turn.getString("id").also { assertTrue(it.isNotBlank()) }
        }

        fun awaitCompleted(threadId: String, turnId: String) {
            val frame = awaitFrame { value -> value.optString("method") == "turn/completed" &&
                value.optJSONObject("params")?.optString("threadId") == threadId &&
                value.optJSONObject("params")?.optJSONObject("turn")?.optString("id") == turnId }
            val turn = frame.getJSONObject("params").getJSONObject("turn")
            assertEquals("Native fixture must complete through the real turn handler", "completed", turn.getString("status"))
            assertTrue(turn.isNull("error"))
        }

        fun assertHistory(threadId: String, turnId: String, expected: List<NativeNotificationExternalMessage>) {
            val result = success(request("thread/turns/list", JSONObject().put("threadId", threadId)
                .put("limit", 8).put("sortDirection", "desc").put("itemsView", "full")))
            val turns = result.getJSONArray("data")
            assertEquals("Only this fixture's one actual turn may exist", 1, turns.length())
            val turn = turns.getJSONObject(0)
            assertEquals(turnId, turn.getString("id"))
            assertEquals("completed", turn.getString("status"))
            val items = turn.getJSONArray("items")
            val outputs = ArrayList<JSONObject>()
            repeat(items.length()) { index ->
                val item = items.getJSONObject(index)
                assertFalse("External facts must not become user authority", item.optString("type") == "userMessage")
                if (item.optString("type") == "functionCallOutput") outputs.add(item)
                else assertTrue("Unexpected native phone/tool/action item", item.optString("type") in setOf("agentMessage", "reasoning"))
            }
            assertEquals("No duplicate tool-output ingestion is permitted", expected.size, outputs.size)
            expected.forEach { event ->
                val matching = outputs.filter { it.opt("output") == event.payloadJson }
                assertEquals("Persisted raw output must match exactly once", 1, matching.size)
                val output = matching.single()
                assertEquals(NativeNotificationExternalMessage.TOOL_NAME, output.getString("name"))
                assertEquals(NativeNotificationExternalMessage.TOOL_NAMESPACE, output.getString("namespace"))
                val proof = NativeNotificationExternalHistoryDecoder.item(threadId, turnId, output)
                assertNotNull("Production decoder rejected real native persisted output", proof)
                assertEquals(event.eventId, proof!!.eventId)
                assertEquals(event.payloadSha256, proof.payloadSha256)
            }
            val recovered = NativeNotificationExternalHistoryDecoder.page(threadId, result)
            assertNotNull("Production full-history page proof must accept real native history", recovered)
            assertEquals(expected.map { it.eventId }.toSet(), recovered!!.receipts.map { it.eventId }.toSet())
        }

        fun assertOneTurnStarted(threadId: String, turnId: String) {
            val started = observed.filter { it.optString("method") == "turn/started" &&
                it.optJSONObject("params")?.optString("threadId") == threadId }
            assertEquals("Joining tool output must not emit a second turn start", 1, started.size)
            assertEquals(turnId, started.single().getJSONObject("params").getJSONObject("turn").getString("id"))
        }

        fun assertModelSaw(event: NativeNotificationExternalMessage) = mock.assertSaw(event)
        fun awaitFirstModelRequest() = mock.awaitFirstRequest()
        fun releaseFirstResponse() = mock.releaseFirst()
        fun restart() { stop(); start() }
        fun resume(threadId: String) {
            val result = success(request("thread/resume", JSONObject().put("threadId", threadId)
                .put("excludeTurns", true)))
            assertEquals(threadId, result.getJSONObject("thread").getString("id"))
        }

        fun assertNoCredentialsOrUnexpectedWork() {
            mock.assertHealthy()
            assertFalse(File(home, "auth.json").exists())
            observed.forEach { frame ->
                val method = frame.optString("method")
                assertFalse("Unexpected interactive/phone/tool native request", frame.has("id") && frame.has("method"))
                assertFalse("No voice or login work is permitted", method.startsWith("thread/realtime/") || method.startsWith("account/login/"))
            }
        }

        private fun request(method: String, params: JSONObject): JSONObject = rpc(JSONObject()
            .put("id", nextId++).put("method", method).put("params", params))
        private fun rpc(request: JSONObject): JSONObject {
            val id = request.getLong("id")
            send(request)
            return awaitFrame { it.optLong("id", Long.MIN_VALUE) == id }.also { reply ->
                if (reply.has("error")) {
                    // This home and all messages are synthetic. Emit only bounded scalar fields;
                    // never dump request content, error.data, credentials, or the whole RPC frame.
                    val error = reply.optJSONObject("error")
                    throw AssertionError("Native RPC ${diagnostic(request.opt("method"), 64)} rejected " +
                        "the protocol fixture: code=${diagnostic(error?.opt("code"), 32)}, " +
                        "message=${diagnostic(error?.opt("message"), 512)}")
                }
            }
        }
        private fun diagnostic(value: Any?, limit: Int): String {
            val text = when (value) {
                is String -> value
                is Number, is Boolean -> value.toString()
                else -> "<missing-or-nonscalar>"
            }.replace(directory.absolutePath, "<fixture>")
            return text.map { if (it.isISOControl()) ' ' else it }.joinToString("").take(limit)
        }
        private fun success(reply: JSONObject): JSONObject {
            assertFalse("Native RPC rejected the protocol fixture", reply.has("error"))
            return reply.getJSONObject("result")
        }
        private fun send(frame: JSONObject) {
            val bytes = (frame.toString() + "\n").toByteArray(Charsets.UTF_8)
            assertTrue(bytes.size <= 128 * 1024)
            val output = checkNotNull(process).outputStream
            output.write(bytes)
            output.flush()
        }
        private fun awaitFrame(predicate: (JSONObject) -> Boolean): JSONObject {
            observed.firstOrNull(predicate)?.let { return it }
            val deadline = SystemClock.elapsedRealtime() + 45_000
            while (SystemClock.elapsedRealtime() < deadline) {
                readFailure.get()?.let { throw AssertionError("Native fixture transport failed", it) }
                mock.assertHealthy()
                val frame = frames.poll(200, TimeUnit.MILLISECONDS) ?: continue
                check(observed.size < 512) { "Native fixture observation bound exceeded" }
                observed.add(frame)
                if (predicate(frame)) return frame
            }
            throw AssertionError("Native fixture receipt timed out")
        }

        private fun stop() {
            val child = process ?: return
            closing = true
            runCatching { child.outputStream.close() }
            if (!child.waitFor(5, TimeUnit.SECONDS)) {
                child.destroy()
                if (!child.waitFor(5, TimeUnit.SECONDS)) {
                    child.destroyForcibly()
                    assertTrue("Owned native child did not terminate", child.waitFor(5, TimeUnit.SECONDS))
                }
            }
            runCatching { child.inputStream.close() }
            runCatching { child.errorStream.close() }
            reader?.join(2_000)
            stderr?.join(2_000)
            assertFalse("Owned native stdout reader remained alive", reader?.isAlive == true)
            assertFalse("Owned native stderr reader remained alive", stderr?.isAlive == true)
            process = null
        }

        override fun close() {
            mock.releaseFirst()
            try { stop() } finally {
                mock.close()
                // Fresh uniquely owned cache tree only, without following symbolic links.
                Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                        Files.delete(file); return FileVisitResult.CONTINUE
                    }
                    override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                        if (error != null) throw error
                        Files.delete(dir); return FileVisitResult.CONTINUE
                    }
                })
            }
        }

        companion object {
            fun open(holdFirstResponse: Boolean): Fixture {
                assertTrue("Native protocol fixture must never run on a personal phone", Build.HARDWARE in setOf("ranchu", "goldfish") ||
                    Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("sdk_gphone", true))
                assertEquals("This acceptance test is pinned to the audited native protocol", "0.160.1", BuildConfig.CODEX_RUNTIME_VERSION)
                val context = ApplicationProvider.getApplicationContext<Context>()
                val executable = CodexRuntimeContract.executableFile(context.applicationInfo.nativeLibraryDir)
                assertTrue(executable.isFile && executable.canExecute())
                val digest = MessageDigest.getInstance("SHA-256")
                executable.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                }
                assertEquals("Packaged native executable must match the locked artifact", BuildConfig.CODEX_RUNTIME_SHA256,
                    digest.digest().joinToString("") { "%02x".format(it) })
                val directory = Files.createTempDirectory(context.cacheDir.toPath(), "native-notification-protocol-").toFile()
                val mock = LocalResponsesMock(holdFirstResponse)
                val fixture = Fixture(directory, mock, executable)
                try {
                    listOf(fixture.home, fixture.workspace, fixture.temporary).forEach { assertTrue(it.mkdir()) }
                    fixture.start()
                    return fixture
                } catch (failure: Throwable) {
                    runCatching { fixture.close() }
                    throw failure
                }
            }
        }
    }

    /** Minimal credentialless Responses fixture; no OpenAI endpoint or tool invocation exists. */
    private class LocalResponsesMock(private val holdFirstResponse: Boolean) : AutoCloseable {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        private val closed = AtomicBoolean(false)
        private val failure = AtomicReference<Throwable>()
        private val firstRequest = CountDownLatch(1)
        private val releaseFirst = CountDownLatch(1)
        private val requestCount = AtomicInteger()
        private val requests = LinkedBlockingQueue<JSONObject>(4)
        private val activeSocket = AtomicReference<Socket>()
        val baseUrl = "http://127.0.0.1:${server.localPort}/v1"
        private val worker = Thread({
            try {
                while (!closed.get()) server.accept().use { socket ->
                    activeSocket.set(socket)
                    socket.soTimeout = 45_000
                    val input = BufferedInputStream(socket.getInputStream())
                    val requestLine = line(input)
                    assertTrue("Fixture must receive only local Responses POSTs", requestLine.startsWith("POST /v1/responses "))
                    val headers = linkedMapOf<String, String>()
                    var headerCount = 0
                    while (true) {
                        val value = line(input)
                        if (value.isEmpty()) break
                        check(++headerCount <= 64) { "Local HTTP header count exceeded its bound" }
                        val separator = value.indexOf(':')
                        check(separator > 0) { "Malformed mock HTTP header" }
                        headers[value.substring(0, separator).lowercase()] = value.substring(separator + 1).trim()
                    }
                    check(headers.keys.none { it in setOf("authorization", "proxy-authorization", "x-api-key") }) {
                        "Credentials must never be attached to the local fixture"
                    }
                    assertEquals("Only the loopback fixture may receive requests", "127.0.0.1:${server.localPort}", headers["host"])
                    check(headers["content-encoding"] == null || headers["content-encoding"] == "identity") {
                        "The local provider fixture expects an uncompressed JSON request"
                    }
                    val body = readBody(input, headers)
                    check(requests.offer(JSONObject(body.toString(Charsets.UTF_8)))) { "Fixture request bound exceeded" }
                    val index = requestCount.incrementAndGet()
                    firstRequest.countDown()
                    val output = BufferedOutputStream(socket.getOutputStream())
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    writeEvent(output, JSONObject().put("type", "response.created").put("response",
                        JSONObject().put("id", "fixture-response-$index").put("status", "in_progress").put("output", JSONArray())))
                    output.flush()
                    if (index == 1 && holdFirstResponse) check(releaseFirst.await(40, TimeUnit.SECONDS)) { "Held response was not released" }
                    writeEvent(output, JSONObject().put("type", "response.completed").put("response",
                        JSONObject().put("id", "fixture-response-$index").put("status", "completed").put("output", JSONArray())
                            .put("usage", JSONObject().put("input_tokens", 1).put("output_tokens", 0).put("total_tokens", 1))))
                    output.flush()
                    activeSocket.set(null)
                }
            } catch (problem: Throwable) {
                if (!closed.get()) failure.compareAndSet(null, problem)
                firstRequest.countDown()
            }
        }, "native-notification-loopback-responses").apply { isDaemon = true; start() }

        fun awaitFirstRequest() {
            assertTrue("Real native model request did not reach the local fixture", firstRequest.await(30, TimeUnit.SECONDS))
            assertHealthy()
            assertTrue(requestCount.get() >= 1)
        }
        fun releaseFirst() = releaseFirst.countDown()
        fun assertHealthy() { failure.get()?.let { throw AssertionError("Local Responses fixture failed", it) } }
        fun assertSaw(event: NativeNotificationExternalMessage) {
            assertHealthy()
            assertTrue("Real native model input omitted the accepted external output", requests.any request@{ request ->
                val input = request.optJSONArray("input") ?: return@request false
                (0 until input.length()).any item@{ index ->
                    val item = input.optJSONObject(index) ?: return@item false
                    item.optString("type") == "function_call_output" && item.opt("output") == event.payloadJson &&
                        item.opt("name") == NativeNotificationExternalMessage.TOOL_NAME &&
                        item.opt("namespace") == NativeNotificationExternalMessage.TOOL_NAMESPACE
                }
            })
        }
        override fun close() {
            closed.set(true)
            releaseFirst.countDown()
            runCatching { activeSocket.get()?.close() }
            server.close()
            worker.join(2_000)
            assertFalse("Owned mock server thread remained alive", worker.isAlive)
        }
        private fun writeEvent(output: java.io.OutputStream, value: JSONObject) {
            output.write(("event: ${value.getString("type")}\ndata: $value\n\n").toByteArray(Charsets.UTF_8))
        }
        private fun line(input: InputStream): String {
            val output = ByteArrayOutputStream()
            while (output.size() < 8192) {
                val next = input.read()
                if (next < 0) throw EOFException("Local HTTP peer closed a partial request")
                if (next == '\n'.code) return output.toString(Charsets.US_ASCII.name()).trimEnd('\r')
                output.write(next)
            }
            throw IllegalStateException("Local HTTP line exceeded its bound")
        }
        private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray {
            val output = ByteArrayOutputStream()
            if (headers["transfer-encoding"]?.lowercase() == "chunked") {
                while (true) {
                    val count = line(input).substringBefore(';').toInt(16)
                    check(count >= 0 && output.size() + count <= 512 * 1024)
                    if (count == 0) { assertEquals("", line(input)); break }
                    output.write(readExactly(input, count))
                    assertEquals("", line(input))
                }
            } else {
                val count = checkNotNull(headers["content-length"]).toInt()
                check(count in 1..512 * 1024)
                output.write(readExactly(input, count))
            }
            return output.toByteArray()
        }
        private fun readExactly(input: InputStream, count: Int): ByteArray {
            val result = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val read = input.read(result, offset, count - offset)
                if (read < 0) throw EOFException("Local HTTP body ended prematurely")
                offset += read
            }
            return result
        }
    }
}
