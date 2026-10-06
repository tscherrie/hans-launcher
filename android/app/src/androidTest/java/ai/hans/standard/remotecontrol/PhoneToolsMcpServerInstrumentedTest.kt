package ai.hans.standard.remotecontrol

import ai.hans.standard.codex.DynamicToolCancellationDisposition
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolFunctionSpec
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real Android socket/lifecycle check for API 31–36; no private APIs or granted phone action. */
class PhoneToolsMcpServerInstrumentedTest {
    @Test fun loopbackRequiresAuthenticationAndUsesExactPhoneCallMetadata() {
        val executed = CountDownLatch(1)
        PhoneToolsMcpServer({ listOf(DynamicToolNamespaceSpec("hans_phone", "Phone", listOf(
            DynamicToolFunctionSpec("inspect", "Inspect", "{\"type\":\"object\"}")))) },
            { call, _, done ->
                assertEquals("thread-test", call.threadId)
                assertEquals("turn-test", call.turnId)
                assertEquals("call-test", call.callId)
                executed.countDown()
                done(DynamicToolExecutionResult("{\"ok\":true}", true))
                object : DynamicToolExecutionHandle {
                    override fun cancel() = DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT
                }
            }).use { server ->
            server.start()
            val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call")
                .put("params", JSONObject().put("name", "hans_phone__inspect").put("arguments", JSONObject())
                    .put("_meta", JSONObject().put("threadId", "thread-test").put("callId", "call-test")
                        .put("x-codex-turn-metadata", JSONObject().put("turn_id", "turn-test"))))
            assertTrue(request(server, body, "wrong").startsWith("HTTP/1.1 401"))
            assertEquals(1L, executed.count)
            val response = request(server, body, server.config.token)
            assertTrue(response.startsWith("HTTP/1.1 200"))
            assertFalse(JSONObject(response.substringAfter("\r\n\r\n")).getJSONObject("result").getBoolean("isError"))
            assertTrue(executed.await(1, TimeUnit.SECONDS))
        }
    }

    private fun request(server: PhoneToolsMcpServer, body: JSONObject, token: String): String =
        Socket("127.0.0.1", server.config.port).use { socket ->
            socket.soTimeout = 5_000
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                write(("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:${server.config.port}\r\nAuthorization: Bearer $token\r\n" +
                    "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n").toByteArray(Charsets.US_ASCII))
                write(bytes)
                flush()
            }
            val input = socket.getInputStream()
            val headers = StringBuilder()
            while (!headers.endsWith("\r\n\r\n")) {
                val byte = input.read()
                check(byte >= 0 && headers.length < 20_000)
                headers.append(byte.toChar())
            }
            val length = headers.toString().lineSequence().first { it.startsWith("Content-Length:") }
                .substringAfter(':').trim().toInt()
            val content = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(content, offset, length - offset)
                check(count > 0)
                offset += count
            }
            headers.toString() + content.toString(Charsets.UTF_8)
        }
}
