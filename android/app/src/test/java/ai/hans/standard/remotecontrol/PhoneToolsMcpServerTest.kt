package ai.hans.standard.remotecontrol

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolCancellationDisposition
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolFunctionSpec
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneToolsMcpServerTest {
    @Test fun initializesEachSupportedVersionAndNegotiatesUnknownVersionWithoutSessionState() {
        server().use { server ->
            for (version in listOf("2024-11-05", "2025-03-26", "2025-06-18", "future")) {
                val response = request(server, rpc("initialize", JSONObject().put("protocolVersion", version)))
                assertEquals(200, response.status)
                val result = response.json.getJSONObject("result")
                assertEquals(if (version == "future") "2025-06-18" else version, result.getString("protocolVersion"))
                assertFalse(result.getJSONObject("capabilities").getJSONObject("tools").getBoolean("listChanged"))
                assertFalse(response.raw.lowercase().contains("mcp-session-id"))
            }
            assertEquals(202, request(server, JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized")).status)
            assertEquals(200, request(server, rpc("ping")).status)
        }
    }

    @Test fun listsExactToolNamesAndPreservesTurnIdentityArgumentsAndBinaryImages() {
        val captured = AtomicReference<DynamicToolCallParams>()
        val executed = AtomicInteger()
        server(execute = { call, _, done ->
            captured.set(call); executed.incrementAndGet()
            done(DynamicToolExecutionResult("{\"ok\":true}", true,
                imageUrls = listOf("data:image/png;base64,aGVsbG8=")))
            handle()
        }).use { server ->
            val list = request(server, rpc("tools/list")).json.getJSONObject("result").getJSONArray("tools")
            assertEquals(1, list.length())
            assertEquals("hans_phone__inspect", list.getJSONObject(0).getString("name"))
            assertEquals("Inspect phone", list.getJSONObject(0).getString("description"))
            assertEquals("object", list.getJSONObject(0).getJSONObject("inputSchema").getString("type"))
            val response = request(server, call(arguments = JSONObject().put("query", "screen")))
            assertEquals(200, response.status)
            val actual = captured.get()
            assertEquals("thread-1", actual.threadId)
            assertEquals("turn-1", actual.turnId)
            assertEquals("call-1", actual.callId)
            assertEquals("hans_phone", actual.namespace)
            assertEquals("inspect", actual.tool)
            assertEquals("screen", JSONObject(actual.argumentsJson).getString("query"))
            val result = response.json.getJSONObject("result")
            assertFalse(result.getBoolean("isError"))
            val content = result.getJSONArray("content")
            assertEquals("{\"ok\":true}", content.getJSONObject(0).getString("text"))
            assertEquals("image", content.getJSONObject(1).getString("type"))
            assertEquals("image/png", content.getJSONObject(1).getString("mimeType"))
            assertEquals("aGVsbG8=", content.getJSONObject(1).getString("data"))
            assertFalse(content.getJSONObject(0).getString("text").contains("base64"))
            assertEquals(1, executed.get())
        }
    }

    @Test fun requiresBearerAndRejectsBrowserOriginHostAliasesAndAlternatePaths() {
        server().use { server ->
            assertEquals(401, request(server, rpc("ping"), auth = "Bearer wrong").status)
            assertEquals(401, request(server, rpc("ping"), auth = null).status)
            assertEquals(403, request(server, rpc("ping"), host = "localhost:${server.config.port}").status)
            assertEquals(403, request(server, rpc("ping"), extra = "Origin: https://example.test\r\n").status)
            assertEquals(403, request(server, rpc("ping"), extra = "Origin: null\r\n").status)
            assertEquals(404, request(server, rpc("ping"), path = "/mcp?token=untrusted").status)
            assertEquals(405, request(server, rpc("ping"), method = "GET").status)
            assertEquals(405, request(server, rpc("ping"), method = "DELETE").status)
            assertFalse(server.config.toString().contains(server.config.token))
        }
    }

    @Test fun rejectsDuplicateHeadersAmbiguousLengthsEncodingsAndUnsupportedProtocol() {
        server().use { server ->
            for (extra in listOf("content-length: 1\r\n", "HOST: 127.0.0.1:${server.config.port}\r\n",
                "Transfer-Encoding: chunked\r\n", "Content-Encoding: gzip\r\n", "Expect: 100-continue\r\n",
                "MCP-Session-Id: guessed\r\n", "MCP-Protocol-Version: future\r\n")) {
                assertEquals(extra, 400, request(server, rpc("ping"), extra = extra).status)
            }
            assertEquals(413, raw(server, "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:${server.config.port}\r\n" +
                "Authorization: Bearer ${server.config.token}\r\nContent-Type: application/json\r\n" +
                "Content-Length: 100000\r\n\r\n").status)
            assertEquals(431, request(server, rpc("ping"), extra = "X-Fill: ${"x".repeat(4_100)}\r\n").status)
        }
    }

    @Test fun malformedBodiesAndMissingOrGuessedMetadataNeverExecute() {
        val executed = AtomicInteger()
        server(execute = { _, _, done -> executed.incrementAndGet(); done(DynamicToolExecutionResult("{}", true)); handle() }).use { server ->
            val missing = call().also { it.getJSONObject("params").remove("_meta") }
            val invalid = call().also { it.getJSONObject("params").getJSONObject("_meta").put("threadId", "bad\nthread") }
            val unknown = call().also { it.getJSONObject("params").put("name", "hans_phone_inspect") }
            val arrayArguments = call().also { it.getJSONObject("params").put("arguments", org.json.JSONArray()) }
            for (body in listOf(missing, invalid, unknown, arrayArguments)) {
                assertEquals(-32602, request(server, body).json.getJSONObject("error").getInt("code"))
            }
            for (body in listOf("{secret", "{} trailing", "[${"[".repeat(40)}0${"]".repeat(40)}]")) {
                val response = bytes(server, body.toByteArray())
                assertEquals(-32700, response.json.getJSONObject("error").getInt("code"))
                assertFalse(response.raw.contains("secret"))
            }
            assertEquals(-32700, bytes(server, byteArrayOf(0xC3.toByte(), 0x28)).json.getJSONObject("error").getInt("code"))
            assertEquals(0, executed.get())
            assertEquals(202, request(server, call().also { it.remove("id") }).status)
            assertEquals(0, executed.get())
        }
    }

    @Test fun failedToolsRemainMcpErrorsAndDuplicateCompletionsAreIgnored() {
        server(execute = { _, _, done ->
            done(DynamicToolExecutionResult("{\"error\":\"permission_required\"}", false))
            done(DynamicToolExecutionResult("{\"incorrect\":true}", true))
            handle()
        }).use { server ->
            val response = request(server, call())
            assertTrue(response.json.getJSONObject("result").getBoolean("isError"))
            assertFalse(response.raw.contains("incorrect"))
        }
    }

    @Test fun disconnectCancelsRealExecutionAndItsSignal() {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val signal = AtomicReference<DynamicToolCancellation>()
        server(execute = { _, cancellation, _ -> signal.set(cancellation); entered.countDown(); handle { cancelled.countDown() } }).use { server ->
            open(server, call()).use {
                assertTrue(entered.await(2, TimeUnit.SECONDS))
            }
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
            assertTrue(signal.get().isCancellationRequested())
        }
    }

    @Test fun deadlineAndServerCloseCancelOutstandingExecution() {
        val timedOut = CountDownLatch(1)
        server(timeout = 75, execute = { _, _, _ -> handle { timedOut.countDown() } }).use { server ->
            open(server, call()).use { assertTrue(timedOut.await(2, TimeUnit.SECONDS)) }
        }
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val server = server(execute = { _, _, _ -> entered.countDown(); handle { cancelled.countDown() } })
        open(server, call()).use {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            server.close()
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        }
        server.close()
    }

    @Test fun cancellationNotificationCancelsUniqueIdButNotAmbiguousIdsAcrossThreads() {
        val entered = CountDownLatch(2)
        val cancellations = AtomicInteger()
        val signals = ConcurrentLinkedQueue<DynamicToolCancellation>()
        server(execute = { _, cancellation, _ -> signals.add(cancellation); entered.countDown(); handle { cancellations.incrementAndGet() } }).use { server ->
            open(server, call(thread = "thread-1")).use { first ->
                open(server, call(thread = "thread-2")).use {
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    val cancellation = JSONObject().put("jsonrpc", "2.0").put("method", "notifications/cancelled")
                        .put("params", JSONObject().put("requestId", 1))
                    assertEquals(202, request(server, cancellation).status)
                    assertEquals(0, cancellations.get())
                    assertTrue(signals.none { it.isCancellationRequested() })
                    first.close()
                }
            }
        }
        val cancelled = CountDownLatch(1)
        val enteredOnce = CountDownLatch(1)
        server(execute = { _, _, _ -> enteredOnce.countDown(); handle { cancelled.countDown() } }).use { server ->
            open(server, call()).use {
                assertTrue(enteredOnce.await(2, TimeUnit.SECONDS))
                assertEquals(202, request(server, JSONObject().put("jsonrpc", "2.0")
                    .put("method", "notifications/cancelled").put("params", JSONObject().put("requestId", 1))).status)
                assertTrue(cancelled.await(2, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun activeCallsAreBoundedAndControlRequestsRemainAvailable() {
        val entered = CountDownLatch(4)
        server(execute = { _, _, _ -> entered.countDown(); handle() }).use { server ->
            val calls = (1..4).map { open(server, call(thread = "thread-$it")) }
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                assertEquals(-32000, request(server, call(thread = "thread-5")).json.getJSONObject("error").getInt("code"))
                assertEquals(200, request(server, rpc("ping")).status)
            } finally { calls.forEach(Socket::close) }
        }
    }

    @Test fun headerReadHasATotalDeadlineAndTokensAreRandomPerInstance() {
        server(readTimeout = 50).use { first ->
            server().use { second ->
                assertNotEquals(first.config.token, second.config.token)
                assertEquals(64, first.config.token.length)
            }
            Socket("127.0.0.1", first.config.port).use { socket ->
                socket.soTimeout = 2_000
                socket.getOutputStream().write("POST /mcp HTTP/1.1\r\nHost:".toByteArray())
                // A deadline may close the socket before an HTTP error can be delivered.
                socket.getInputStream().readBytes()
            }
        }
    }

    private fun server(
        timeout: Long = 5_000,
        readTimeout: Int = 1_000,
        execute: (DynamicToolCallParams, DynamicToolCancellation, (DynamicToolExecutionResult) -> Unit) -> DynamicToolExecutionHandle =
            { _, _, done -> done(DynamicToolExecutionResult("{}", true)); handle() },
    ) = PhoneToolsMcpServer({ listOf(DynamicToolNamespaceSpec("hans_phone", "Phone tools", listOf(
        DynamicToolFunctionSpec("inspect", "Inspect phone", "{\"type\":\"object\"}")))) }, execute, timeout, readTimeout).apply { start() }

    private fun handle(onCancel: () -> Unit = {}) = object : DynamicToolExecutionHandle {
        override fun cancel(): DynamicToolCancellationDisposition {
            onCancel()
            return DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT
        }
    }

    private fun rpc(method: String, params: JSONObject = JSONObject()) = JSONObject()
        .put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params)

    private fun call(thread: String = "thread-1", arguments: JSONObject = JSONObject()) = rpc("tools/call", JSONObject()
        .put("name", "hans_phone__inspect").put("arguments", arguments).put("_meta", JSONObject()
            .put("threadId", thread).put("callId", "call-1")
            .put("x-codex-turn-metadata", JSONObject().put("turn_id", "turn-1"))))

    private fun request(server: PhoneToolsMcpServer, body: JSONObject, auth: String? = "Bearer ${server.config.token}",
        host: String = "127.0.0.1:${server.config.port}", extra: String = "", method: String = "POST", path: String = "/mcp"): Response =
        open(server, body, auth, host, extra, method, path).use(::readResponse)

    private fun open(server: PhoneToolsMcpServer, body: JSONObject, auth: String? = "Bearer ${server.config.token}",
        host: String = "127.0.0.1:${server.config.port}", extra: String = "", method: String = "POST", path: String = "/mcp"): Socket {
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        return Socket("127.0.0.1", server.config.port).apply {
            soTimeout = 4_000
            getOutputStream().apply {
                write(("$method $path HTTP/1.1\r\nHost: $host\r\n" + (auth?.let { "Authorization: $it\r\n" } ?: "") +
                    "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n$extra\r\n").toByteArray())
                write(bytes); flush()
            }
        }
    }

    private fun raw(server: PhoneToolsMcpServer, request: String): Response = Socket("127.0.0.1", server.config.port).use {
        it.soTimeout = 4_000
        it.getOutputStream().write(request.toByteArray())
        readResponse(it)
    }

    private fun bytes(server: PhoneToolsMcpServer, body: ByteArray): Response = Socket("127.0.0.1", server.config.port).use {
        it.soTimeout = 4_000
        it.getOutputStream().apply {
            write(("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:${server.config.port}\r\nAuthorization: Bearer ${server.config.token}\r\n" +
                "Content-Type: application/json\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray())
            write(body)
        }
        readResponse(it)
    }

    private fun readResponse(socket: Socket): Response {
        // HTTP length, not EOF: a rejected request can still have unread bytes on the server.
        val input = socket.getInputStream()
        val headers = StringBuilder()
        while (!headers.endsWith("\r\n\r\n")) {
            val byte = input.read()
            check(byte >= 0 && headers.length < 20_000) { "Incomplete HTTP response" }
            headers.append(byte.toChar())
        }
        val length = headers.toString().lineSequence().first { it.startsWith("Content-Length:") }
            .substringAfter(':').trim().toInt()
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            check(count > 0) { "Incomplete HTTP body" }
            offset += count
        }
        return Response(headers.toString() + body.toString(StandardCharsets.UTF_8))
    }

    private class Response(val raw: String) {
        val status: Int get() = raw.substringBefore("\r\n").split(' ')[1].toInt()
        val json: JSONObject get() = JSONObject(raw.substringAfter("\r\n\r\n"))
    }
}
