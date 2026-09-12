package ai.hans.standard.mcp

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

class OkHttpRemoteMcpCallFactoryTest {
    @Test
    fun modernWireMirrorsExactRoutingAndEncodedParameterHeadersWithoutSession() {
        val captured = AtomicReference<okhttp3.Request>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                captured.set(chain.request())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/json")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val tool = RemoteMcpTool(
            name = "tasks/create",
            title = null,
            description = null,
            inputSchemaJson = """{
                "type":"object",
                "properties":{"label":{"type":"string","x-mcp-header":"Label"}}
            }""".trimIndent(),
            outputSchemaJson = null,
        )
        val message = RemoteMcpProtocol.toolsCallRequest(
            1,
            tool,
            """{"label":"Hello, 世界"}""",
            RemoteMcpProtocolEra.MODERN_2026,
        )
        val wireRequest = RemoteMcpHttpRequest(
            endpoint = "https://mcp.example.com/v1",
            body = message.body,
            timeoutMillis = 10_000,
            maxResponseBytes = 64 * 1024,
            sessionId = null,
            protocolVersion = RemoteMcpProtocol.MODERN_PROTOCOL_VERSION,
            method = message.method,
            name = message.name,
            parameterHeaders = message.parameterHeaders,
        )
        assertTrue("Hello" !in wireRequest.toString())
        assertTrue("mcp.example.com" !in wireRequest.toString())
        OkHttpRemoteMcpCallFactory(client).create(
            wireRequest,
            null,
        ).execute()

        val request = captured.get()
        assertEquals(RemoteMcpProtocol.MODERN_PROTOCOL_VERSION, request.header("MCP-Protocol-Version"))
        assertEquals("tools/call", request.header("Mcp-Method"))
        assertEquals("tasks/create", request.header("Mcp-Name"))
        assertEquals("=?base64?SGVsbG8sIOS4lueVjA==?=", request.header("Mcp-Param-Label"))
        assertNull(request.header("Mcp-Session-Id"))
        val body = JSONObject(requireNotNull(request.body).let { body ->
            val buffer = okio.Buffer()
            body.writeTo(buffer)
            buffer.readUtf8()
        })
        assertEquals("tasks/create", body.getJSONObject("params").getString("name"))
    }

    @Test
    fun mcpNameUsesBase64SentinelWhenPlainAsciiIsUnsafe() {
        val captured = AtomicReference<okhttp3.Request>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                captured.set(chain.request())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/json")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val name = "naïve"
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", "tools/call")
            .put(
                "params",
                JSONObject()
                    .put("name", name)
                    .put("arguments", JSONObject())
                    .put(
                        "_meta",
                        JSONObject()
                            .put(
                                "io.modelcontextprotocol/protocolVersion",
                                RemoteMcpProtocol.MODERN_PROTOCOL_VERSION,
                            )
                            .put("io.modelcontextprotocol/clientInfo", JSONObject())
                            .put("io.modelcontextprotocol/clientCapabilities", JSONObject()),
                    ),
            )
            .toString()
            .toByteArray()
        OkHttpRemoteMcpCallFactory(client).create(
            RemoteMcpHttpRequest(
                "https://mcp.example.com/v1",
                body,
                10_000,
                64 * 1024,
                null,
                RemoteMcpProtocol.MODERN_PROTOCOL_VERSION,
                "tools/call",
                name,
            ),
            null,
        ).execute()
        assertEquals("=?base64?bmHDr3Zl?=", captured.get().header("Mcp-Name"))
    }

    @Test
    fun legacyWireKeepsProtocolAndSessionWithoutModernRoutingHeaders() {
        val captured = AtomicReference<okhttp3.Request>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                captured.set(chain.request())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/json")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        OkHttpRemoteMcpCallFactory(client).create(
            RemoteMcpHttpRequest(
                "https://mcp.example.com/v1",
                "{}".toByteArray(),
                10_000,
                64 * 1024,
                "legacy-session",
                RemoteMcpProtocol.LEGACY_PROTOCOL_VERSION,
            ),
            null,
        ).execute()
        assertEquals(RemoteMcpProtocol.LEGACY_PROTOCOL_VERSION, captured.get().header("MCP-Protocol-Version"))
        assertEquals("legacy-session", captured.get().header("Mcp-Session-Id"))
        assertNull(captured.get().header("Mcp-Method"))
        assertNull(captured.get().header("Mcp-Name"))
    }

    @Test
    fun redirectIsRejectedWithoutBearerReplay() {
        val calls = AtomicInteger()
        val authorization = AtomicReference<String?>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                calls.incrementAndGet()
                authorization.set(chain.request().header("Authorization"))
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(302)
                    .message("Found")
                    .header("Location", "https://attacker.example/steal")
                    .body(ByteArray(0).toResponseBody(null))
                    .build()
            }
            .build()
        val factory = OkHttpRemoteMcpCallFactory(client)
        val call = factory.create(
            RemoteMcpHttpRequest(
                "https://mcp.example.com/v1",
                "{}".toByteArray(),
                10_000,
                64 * 1024,
                null,
                null,
            ),
            "private-token",
        )

        assertTrue(runCatching { call.execute() }.isFailure)
        assertEquals(1, calls.get())
        assertEquals("Bearer private-token", authorization.get())
    }

    @Test
    fun cleartextEndpointIsRejectedBeforeCreatingNetworkCall() {
        val factory = OkHttpRemoteMcpCallFactory()
        assertTrue(
            runCatching {
                factory.create(
                    RemoteMcpHttpRequest(
                        "http://example.com/mcp",
                        "{}".toByteArray(),
                        10_000,
                        64 * 1024,
                        null,
                        null,
                    ),
                    null,
                )
            }.isFailure,
        )
    }
}
