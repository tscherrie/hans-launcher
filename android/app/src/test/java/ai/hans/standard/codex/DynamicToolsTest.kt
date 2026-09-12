package ai.hans.standard.codex

import ai.hans.standard.phone.tools.AndroidDynamicToolCatalog
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicToolsTest {
    @Test
    fun threadStartUsesTheExactPinnedNestedNamespaceDynamicToolShape() {
        val request = AppServerRequests.threadStart(
            id = RequestId.Number(81),
            options = DispatchOptions.DEFAULT,
            dynamicTools = listOf(AndroidDynamicToolCatalog.namespace),
        )
        val params = JSONObject(request.json).getJSONObject("params")
        val dynamicTools = params.getJSONArray("dynamicTools")

        assertEquals(1, dynamicTools.length())
        val namespace = dynamicTools.getJSONObject(0)
        assertEquals("namespace", namespace.getString("type"))
        assertEquals("android", namespace.getString("name"))
        assertEquals(
            setOf("type", "name", "description", "tools"),
            namespace.keys().asSequence().toSet(),
        )
        val tools = namespace.getJSONArray("tools")
        assertEquals(6, tools.length())
        val battery = (0 until tools.length())
            .map(tools::getJSONObject)
            .single { it.getString("name") == "read_battery" }
        assertEquals("function", battery.getString("type"))
        assertEquals("object", battery.getJSONObject("inputSchema").getString("type"))
        assertFalse(battery.has("namespace"))
        assertFalse(battery.has("tools"))
        assertFalse(battery.has("deferLoading"))
        assertEquals(
            setOf("type", "name", "description", "inputSchema"),
            battery.keys().asSequence().toSet(),
        )
    }

    @Test
    fun decodesPinnedServerRequestAndPreservesArbitraryJsonArguments() {
        val decoded = DynamicToolProtocol.decodeServerRequest(
            """{"method":"item/tool/call","id":60,"params":{"threadId":"thr-1","turnId":"turn-1","callId":"call-1","namespace":"android","tool":"read_battery","arguments":{"value":"same","enabled":true}}}""",
        ) as ServerRequestDecodeResult.DynamicToolCall

        assertEquals(ServerRequestId.Number(60), decoded.call.requestId)
        assertEquals("thr-1", decoded.call.params.threadId)
        assertEquals("turn-1", decoded.call.params.turnId)
        assertEquals("call-1", decoded.call.params.callId)
        assertEquals("android", decoded.call.params.namespace)
        assertEquals("read_battery", decoded.call.params.tool)
        val arguments = JSONObject(decoded.call.params.argumentsJson)
        assertEquals("same", arguments.getString("value"))
        assertTrue(arguments.getBoolean("enabled"))
    }

    @Test
    fun emitsTheExactPinnedDynamicToolResponseShapeForStringIds() {
        val raw = DynamicToolProtocol.response(
            ServerRequestId.Text("server-call-1"),
            DynamicToolExecutionResult("{\"status\":\"succeeded\"}", success = true),
        )
        val response = JSONObject(raw)

        assertEquals("server-call-1", response.getString("id"))
        assertFalse(response.has("jsonrpc"))
        assertEquals(setOf("id", "result"), response.keys().asSequence().toSet())
        val result = response.getJSONObject("result")
        assertTrue(result.getBoolean("success"))
        val content = result.getJSONArray("contentItems").getJSONObject(0)
        assertEquals("inputText", content.getString("type"))
        assertEquals("{\"status\":\"succeeded\"}", content.getString("text"))
        assertEquals(2, content.length())
    }

    @Test
    fun emitsPinnedInputImageItemsWithoutEmbeddingBase64InToolJson() {
        val image = "data:image/jpeg;base64,AQIDBA=="
        val raw = DynamicToolProtocol.response(
            ServerRequestId.Text("visual-call-1"),
            DynamicToolExecutionResult(
                contentText = "{\"status\":\"succeeded\",\"visual\":true}",
                success = true,
                imageUrls = listOf(image),
            ),
        )
        val items = JSONObject(raw)
            .getJSONObject("result")
            .getJSONArray("contentItems")

        assertEquals(2, items.length())
        assertEquals("inputText", items.getJSONObject(0).getString("type"))
        assertFalse(items.getJSONObject(0).getString("text").contains("AQIDBA"))
        assertEquals("inputImage", items.getJSONObject(1).getString("type"))
        assertEquals(image, items.getJSONObject(1).getString("imageUrl"))
    }

    @Test
    fun rejectsUnknownMalformedAndOversizedServerRequestsWithoutParsingThemAsResponses() {
        val unsupported = DynamicToolProtocol.decodeServerRequest(
            """{"id":7,"method":"private/unknown","params":{}}""",
        )
        assertEquals(ServerRequestDecodeResult.Unsupported(ServerRequestId.Number(7)), unsupported)

        val malformed = DynamicToolProtocol.decodeServerRequest(
            """{"id":8,"method":"item/tool/call","params":{"threadId":"thr","turnId":"turn","callId":"call","tool":"read_battery","arguments":{},"extra":true}}""",
        )
        assertEquals(ServerRequestDecodeResult.Malformed(ServerRequestId.Number(8)), malformed)

        val oversized = JSONObject()
            .put("id", "oversized-request")
            .put("method", "item/tool/call")
            .put(
                "params",
                JSONObject()
                    .put("threadId", "thr")
                    .put("turnId", "turn")
                    .put("callId", "call")
                    .put("tool", "read_battery")
                    .put("arguments", JSONObject().put("payload", "x".repeat(300_000))),
            )
            .toString()
        assertEquals(
            ServerRequestDecodeResult.Malformed(ServerRequestId.Text("oversized-request")),
            DynamicToolProtocol.decodeServerRequest(oversized),
        )
    }

    @Test
    fun reservedNamespaceAndNonObjectOutputFailClosedAtConstruction() {
        assertThrows(IllegalArgumentException::class.java) {
            DynamicToolNamespaceSpec(
                name = "computer",
                description = "Reserved",
                tools = listOf(
                    DynamicToolFunctionSpec("test", "Test", "{\"type\":\"object\"}"),
                ),
            )
        }
        assertThrows(Exception::class.java) {
            DynamicToolExecutionResult("[\"not-an-object\"]", success = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DynamicToolExecutionResult(
                "{\"status\":\"succeeded\"}",
                success = true,
                imageUrls = listOf("https://example.invalid/screenshot.png"),
            )
        }
    }
}
