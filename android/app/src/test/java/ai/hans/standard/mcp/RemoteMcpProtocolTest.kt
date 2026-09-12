package ai.hans.standard.mcp

import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMcpProtocolTest {
    @Test
    fun modernRequestsCarryExactPerRequestEnvelopeAndLegacyDoesNot() {
        val discover = RemoteMcpProtocol.modernDiscoverRequest(1)
        val discoverJson = JSONObject(discover.body.toString(StandardCharsets.UTF_8))
        assertEquals("server/discover", discover.method)
        assertNull(discover.name)
        assertModernMetadata(discoverJson)

        val modernTool = RemoteMcpTool(
            name = "tasks/create",
            title = null,
            description = null,
            inputSchemaJson = """{"type":"object"}""",
            outputSchemaJson = null,
        )
        val call = RemoteMcpProtocol.toolsCallRequest(
            2,
            modernTool,
            """{"title":"hello"}""",
            RemoteMcpProtocolEra.MODERN_2026,
        )
        assertEquals("tools/call", call.method)
        assertEquals("tasks/create", call.name)
        assertModernMetadata(JSONObject(call.body.toString(StandardCharsets.UTF_8)))

        val legacy = RemoteMcpProtocol.initializeRequest(3)
        val legacyParams = JSONObject(legacy.body.toString(StandardCharsets.UTF_8))
            .getJSONObject("params")
        assertEquals(RemoteMcpProtocol.LEGACY_PROTOCOL_VERSION, legacyParams.getString("protocolVersion"))
        assertFalse(legacyParams.has("_meta"))
    }

    @Test
    fun modernUnsupportedVersionRequiresExactStatusCorrelationAndShape() {
        val exact = unsupportedVersionResponse(7)
        val decoded = RemoteMcpProtocol.decodeEnvelope(
            exact,
            7,
            64 * 1024,
            RemoteMcpProtocolEra.MODERN_2026,
        )
        assertTrue(decoded is RemoteMcpResponseEnvelope.UnsupportedProtocolVersion)

        assertFails {
            RemoteMcpProtocol.decodeEnvelope(
                exact,
                8,
                64 * 1024,
                RemoteMcpProtocolEra.MODERN_2026,
            )
        }
        assertFails {
            val wrongRequested = JSONObject(exact.body.toString(StandardCharsets.UTF_8))
            wrongRequested.getJSONObject("error").getJSONObject("data")
                .put("requested", "2025-11-25")
            RemoteMcpProtocol.decodeEnvelope(
                exact.copy(body = wrongRequested.toString().toByteArray()),
                7,
                64 * 1024,
                RemoteMcpProtocolEra.MODERN_2026,
            )
        }
        assertFails {
            RemoteMcpProtocol.decodeEnvelope(
                exact.copy(statusCode = 401),
                7,
                64 * 1024,
                RemoteMcpProtocolEra.MODERN_2026,
            )
        }
    }

    @Test
    fun exactGenericJsonRpcHttp400IsTheOnlyParsedLegacySignal() {
        val generic = RemoteMcpHttpResponse(
            400,
            "application/json",
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 11)
                .put(
                    "error",
                    JSONObject().put("code", -32601).put("message", "Method not found"),
                )
                .toString()
                .toByteArray(),
            null,
        )
        assertEquals(
            RemoteMcpResponseEnvelope.LegacyEraRequired,
            RemoteMcpProtocol.decodeEnvelope(
                generic,
                11,
                64 * 1024,
                RemoteMcpProtocolEra.MODERN_2026,
            ),
        )
        assertFails {
            RemoteMcpProtocol.decodeEnvelope(
                generic.copy(body = "not-json".toByteArray()),
                11,
                64 * 1024,
                RemoteMcpProtocolEra.MODERN_2026,
            )
        }

        listOf(-32602, 17_001).forEach { errorCode ->
            val modernFailure = generic.copy(
                body = JSONObject(generic.body.toString(StandardCharsets.UTF_8))
                    .apply { getJSONObject("error").put("code", errorCode) }
                    .toString()
                    .toByteArray(),
            )
            val decoded = RemoteMcpProtocol.decodeEnvelope(
                modernFailure,
                11,
                64 * 1024,
                RemoteMcpProtocolEra.MODERN_2026,
            )
            assertTrue(decoded is RemoteMcpResponseEnvelope.ModernProtocolError)
            assertEquals(errorCode, (decoded as RemoteMcpResponseEnvelope.ModernProtocolError).code)
        }
    }

    @Test
    fun modernResultRequiresCompleteAndInputRequiredFailsClosedWithTypedCode() {
        val inputRequired = response(
            4,
            JSONObject()
                .put("resultType", "input_required")
                .put("inputRequests", JSONObject()),
        )
        val failure = runCatching {
            RemoteMcpProtocol.decodeEnvelope(
                inputRequired,
                4,
                64 * 1024,
                RemoteMcpProtocolEra.MODERN_2026,
            )
        }.exceptionOrNull()
        assertTrue(failure?.message == "mcp_modern_input_required_unsupported")
    }

    @Test
    fun xMcpHeaderDefinitionsAreFilteredOrExtractedExactly() {
        val validSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "region",
                        JSONObject().put("type", "string").put("x-mcp-header", "Region"),
                    )
                    .put(
                        "nested",
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject().put(
                                    "enabled",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("x-mcp-header", "Enabled"),
                                ),
                            ),
                    ),
            )
        val valid = tool("execute").put("inputSchema", validSchema)
        val invalidComposition = tool("invalid-composition").put(
            "inputSchema",
            JSONObject()
                .put("type", "object")
                .put(
                    "oneOf",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "string")
                            .put("x-mcp-header", "Unsafe"),
                    ),
                ),
        )
        val invalidDuplicate = tool("invalid-duplicate").put(
            "inputSchema",
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put("a", JSONObject().put("type", "string").put("x-mcp-header", "Region"))
                        .put("b", JSONObject().put("type", "string").put("x-mcp-header", "region")),
                ),
        )
        val parsed = RemoteMcpProtocol.parseToolsPage(
            JSONObject()
                .put("tools", JSONArray().put(valid).put(invalidComposition).put(invalidDuplicate))
                .put("ttlMs", 0)
                .put("cacheScope", "private"),
            RemoteMcpProtocolEra.MODERN_2026,
        )
        assertEquals(listOf("execute"), parsed.tools.map(RemoteMcpTool::name))

        val message = RemoteMcpProtocol.toolsCallRequest(
            9,
            parsed.tools.single(),
            """{"region":"eu-west","nested":{"enabled":true}}""",
            RemoteMcpProtocolEra.MODERN_2026,
        )
        assertEquals(mapOf("Enabled" to "true", "Region" to "eu-west"), message.parameterHeaders)
    }

    @Test
    fun modernToolsListAcceptsOnlyBoundedCacheHints() {
        val result = JSONObject()
            .put("tools", JSONArray())
            .put("ttlMs", 60_000L)
            .put("cacheScope", "private")
        assertTrue(
            RemoteMcpProtocol.parseToolsPage(result, RemoteMcpProtocolEra.MODERN_2026)
                .tools.isEmpty(),
        )
        assertFails {
            RemoteMcpProtocol.parseToolsPage(
                JSONObject(result.toString()).put("cacheScope", "tenant-secret"),
                RemoteMcpProtocolEra.MODERN_2026,
            )
        }
        assertFails { RemoteMcpProtocol.parseToolsPage(result) }
        assertFails {
            RemoteMcpProtocol.parseToolsPage(
                JSONObject().put("tools", JSONArray()).put("cacheScope", "private"),
                RemoteMcpProtocolEra.MODERN_2026,
            )
        }
        assertFails {
            RemoteMcpProtocol.parseToolsPage(
                JSONObject().put("tools", JSONArray()).put("ttlMs", 1.5).put("cacheScope", "private"),
                RemoteMcpProtocolEra.MODERN_2026,
            )
        }
    }

    @Test
    fun toolResultAcceptsBoundedEmbeddedTextAndBlobResources() {
        val textResource = JSONObject()
            .put("type", "resource")
            .put(
                "resource",
                JSONObject()
                    .put("uri", "file:///report.txt")
                    .put("mimeType", "text/plain")
                    .put("text", "hello"),
            )
        val blobResource = JSONObject()
            .put("type", "resource")
            .put(
                "resource",
                JSONObject()
                    .put("uri", "file:///image.png")
                    .put("mimeType", "image/png")
                    .put("blob", "aGVsbG8="),
            )
        val result = JSONObject()
            .put("content", JSONArray().put(textResource).put(blobResource))

        assertFalse(RemoteMcpProtocol.parseToolResult(result).isError)
        assertFails {
            RemoteMcpProtocol.parseToolResult(
                JSONObject()
                    .put(
                        "content",
                        JSONArray().put(
                            JSONObject(textResource.toString()).put(
                                "resource",
                                JSONObject(textResource.getJSONObject("resource").toString())
                                    .put("blob", "also-present"),
                            ),
                        ),
                    ),
            )
        }
    }

    @Test
    fun correlatesJsonRpcIdAndRejectsHostileOrOversizedFrames() {
        val valid = response(
            7,
            JSONObject().put("tools", org.json.JSONArray()).put("nextCursor", JSONObject.NULL),
        )
        assertTrue(
            RemoteMcpProtocol.decodeEnvelope(valid, 7, 64 * 1024) is
                RemoteMcpResponseEnvelope.Success,
        )
        assertFails { RemoteMcpProtocol.decodeEnvelope(valid, 8, 64 * 1024) }

        val oversized = valid.copy(body = ByteArray(2_048) { 'x'.code.toByte() })
        assertFails { RemoteMcpProtocol.decodeEnvelope(oversized, 7, 1_024) }

        val extra = JSONObject(valid.body.toString(StandardCharsets.UTF_8)).put("token", "secret")
        assertFails {
            RemoteMcpProtocol.decodeEnvelope(
                valid.copy(body = extra.toString().toByteArray()),
                7,
                64 * 1024,
            )
        }
    }

    @Test
    fun acceptsOneBoundedSseDataFrameAndRejectsUnsupportedFields() {
        val json = response(3, JSONObject().put("ok", true)).body.toString(StandardCharsets.UTF_8)
        val sse = RemoteMcpHttpResponse(
            200,
            "text/event-stream; charset=utf-8",
            "event: message\ndata: $json\n\n".toByteArray(),
            null,
        )
        assertTrue(
            RemoteMcpProtocol.decodeEnvelope(sse, 3, 64 * 1024) is
                RemoteMcpResponseEnvelope.Success,
        )
        assertFails {
            RemoteMcpProtocol.decodeEnvelope(
                sse.copy(body = "cookie: steal\ndata: $json\n".toByteArray()),
                3,
                64 * 1024,
            )
        }

        val notification = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", "notifications/progress")
            .put("params", JSONObject().put("progress", 1))
            .toString()
        val notificationThenResponse = sse.copy(
            body = "data: $notification\n\ndata: $json\n\n".toByteArray(),
        )
        assertTrue(
            RemoteMcpProtocol.decodeEnvelope(notificationThenResponse, 3, 64 * 1024) is
                RemoteMcpResponseEnvelope.Success,
        )

        assertFails {
            RemoteMcpProtocol.decodeEnvelope(
                sse.copy(body = "data: $json\n\ndata: $json\n\n".toByteArray()),
                3,
                64 * 1024,
            )
        }

        val mismatched = response(4, JSONObject().put("ok", true))
            .body.toString(StandardCharsets.UTF_8)
        assertFails {
            RemoteMcpProtocol.decodeEnvelope(
                sse.copy(body = "data: $mismatched\n\ndata: $json\n\n".toByteArray()),
                3,
                64 * 1024,
            )
        }
    }

    @Test
    fun strictToolsDiscoveryRejectsDuplicateAndUnknownContentTypes() {
        val tool = JSONObject()
            .put("name", "tasks/list")
            .put("description", "List tasks")
            .put("inputSchema", JSONObject().put("type", "object"))
        val page = RemoteMcpProtocol.parseToolsPage(
            JSONObject().put("tools", org.json.JSONArray().put(tool)).put("nextCursor", JSONObject.NULL),
        )
        assertEquals("tasks/list", page.tools.single().name)

        assertFails {
            RemoteMcpProtocol.parseToolResult(
                JSONObject()
                    .put("content", org.json.JSONArray().put(JSONObject().put("type", "shell").put("command", "id")))
                    .put("isError", false),
            )
        }
    }

    @Test
    fun toolsDiscoveryPreservesCanonicalBoundedAnnotationsIconsAndMeta() {
        val tool = JSONObject()
            .put("name", "tasks/list")
            .put("description", "List tasks")
            .put("inputSchema", JSONObject().put("type", "object"))
            .put(
                "annotations",
                JSONObject()
                    .put("openWorldHint", false)
                    .put("readOnlyHint", true)
                    .put("destructiveHint", false)
                    .put("idempotentHint", true),
            )
            .put(
                "icons",
                JSONArray().put(
                    JSONObject()
                        .put("theme", "dark")
                        .put("src", "https://example.test/tasks.png")
                        .put("mimeType", "image/png"),
                ),
            )
            .put(
                "_meta",
                JSONObject()
                    .put("z", JSONArray().put("second").put("first"))
                    .put("a", JSONObject().put("tenantLabel", "private-marker")),
            )

        val parsed = RemoteMcpProtocol.parseToolsPage(
            JSONObject().put("tools", JSONArray().put(tool)),
        ).tools.single()

        assertEquals(
            """{"destructiveHint":false,"idempotentHint":true,"openWorldHint":false,"readOnlyHint":true}""",
            parsed.annotationsJson,
        )
        assertEquals(
            """[{"mimeType":"image/png","src":"https://example.test/tasks.png","theme":"dark"}]""",
            parsed.iconsJson,
        )
        assertEquals(
            """{"a":{"tenantLabel":"private-marker"},"z":["second","first"]}""",
            parsed.metaJson,
        )
        assertFalse(parsed.toString().contains("private-marker"))
        assertFalse(parsed.toString().contains("tasks/list"))
    }

    @Test
    fun toolsDiscoveryRejectsExcessiveDepthFanOutAndTotalResponseSize() {
        var nested = JSONObject().put("leaf", true)
        repeat(40) { nested = JSONObject().put("child", nested) }
        assertFails {
            RemoteMcpProtocol.parseToolsPage(
                page(tool("deep").put("_meta", nested)),
            )
        }

        val wide = JSONObject()
        repeat(1_025) { index -> wide.put("field$index", true) }
        assertFails {
            RemoteMcpProtocol.parseToolsPage(
                page(tool("wide").put("_meta", wide)),
            )
        }

        val broadArray = JSONArray()
        repeat(4_097) { broadArray.put(true) }
        assertFails {
            RemoteMcpProtocol.parseToolsPage(
                page(tool("broad").put("_meta", JSONObject().put("items", broadArray))),
            )
        }

        val oversized = JSONArray()
        repeat(150) { index ->
            oversized.put(
                tool("tool-$index").put(
                    "description",
                    "x".repeat(15 * 1024),
                ),
            )
        }
        assertFails {
            RemoteMcpProtocol.parseToolsPage(JSONObject().put("tools", oversized))
        }
    }

    @Test
    fun httpsEndpointPolicyRejectsCredentialsFragmentsAndNonHttps() {
        assertFails { requireHttpsEndpoint("http://example.com/mcp") }
        assertFails { requireHttpsEndpoint("https://token@example.com/mcp") }
        assertFails { requireHttpsEndpoint("https://example.com/mcp#fragment") }
        assertEquals("example.com", requireHttpsEndpoint("https://example.com/mcp").host)
    }

    private fun response(id: Long, result: JSONObject) = RemoteMcpHttpResponse(
        200,
        "application/json",
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("result", result)
            .toString()
            .toByteArray(),
        null,
    )

    private fun tool(name: String): JSONObject = JSONObject()
        .put("name", name)
        .put("inputSchema", JSONObject().put("type", "object"))

    private fun page(tool: JSONObject): JSONObject = JSONObject()
        .put("tools", JSONArray().put(tool))

    private fun assertModernMetadata(request: JSONObject) {
        val metadata = request.getJSONObject("params").getJSONObject("_meta")
        assertEquals(
            RemoteMcpProtocol.MODERN_PROTOCOL_VERSION,
            metadata.getString("io.modelcontextprotocol/protocolVersion"),
        )
        assertTrue(metadata.get("io.modelcontextprotocol/clientInfo") is JSONObject)
        assertTrue(metadata.get("io.modelcontextprotocol/clientCapabilities") is JSONObject)
        assertEquals(3, metadata.length())
    }

    private fun unsupportedVersionResponse(id: Long) = RemoteMcpHttpResponse(
        400,
        "application/json",
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put(
                "error",
                JSONObject()
                    .put("code", -32022)
                    .put("message", "unsupported")
                    .put(
                        "data",
                        JSONObject()
                            .put("requested", RemoteMcpProtocol.MODERN_PROTOCOL_VERSION)
                            .put(
                                "supported",
                                JSONArray().put(RemoteMcpProtocol.LEGACY_PROTOCOL_VERSION),
                            ),
                    ),
            )
            .toString()
            .toByteArray(),
        null,
    )

    private fun assertFails(block: () -> Unit) {
        assertTrue(runCatching(block).isFailure)
    }
}
