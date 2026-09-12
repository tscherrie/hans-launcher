package ai.hans.standard.codex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppServerEventsTest {
    @Test
    fun accountLoginAndAccountUpdatedEventsUsePinnedTypes() {
        val completed = AppServerEventDecoder.decode(
            """
            {"method":"account/login/completed","params":{
              "success":true,
              "loginId":"login-1",
              "error":null,
              "onboardingEntrypoint":null
            }}
            """.trimIndent(),
        ) as ServerEvent.AccountLoginCompleted
        assertTrue(completed.success)
        assertEquals("login-1", completed.loginId)
        assertNull(completed.error)

        val updated = AppServerEventDecoder.decode(
            """{"method":"account/updated","params":{"authMode":"chatgpt","planType":"pro"}}""",
        ) as ServerEvent.AccountUpdated
        assertEquals(AccountAuthMode.CHATGPT, updated.authMode)
        assertEquals(AccountPlanType.PRO, updated.planType)
    }

    @Test
    fun agentMessageDeltaAndAuthoritativeCompletionAreTyped() {
        val delta = AppServerEventDecoder.decode(
            """{"method":"item/agentMessage/delta","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"item-1","delta":"Hallo "}}""",
        ) as ServerEvent.AgentMessageDelta
        assertEquals("Hallo ", delta.delta)

        val completed = AppServerEventDecoder.decode(
            """
            {"method":"item/completed","params":{
              "threadId":"thread-1",
              "turnId":"turn-1",
              "completedAtMs":1234,
              "item":{"type":"agentMessage","id":"item-1","text":"Hallo Welt","phase":"final_answer"}
            }}
            """.trimIndent(),
        ) as ServerEvent.ItemCompleted
        val message = completed.item as StreamItem.AgentMessage
        assertEquals("Hallo Welt", message.text)
        assertEquals(AgentMessagePhase.FINAL_ANSWER, message.phase)
        assertEquals(1234L, completed.completedAtMillis)
    }

    @Test
    fun commandAndToolStatusEventsAreTypedAgainstTheirSeparateEnums() {
        val command = AppServerEventDecoder.decode(
            """
            {"method":"item/started","params":{
              "threadId":"thread-1",
              "turnId":"turn-1",
              "startedAtMs":1000,
              "item":{
                "type":"commandExecution",
                "id":"command-1",
                "command":"pwd",
                "commandActions":[],
                "cwd":"/data/user/0/ai.hans.standard/files",
                "status":"inProgress",
                "aggregatedOutput":null
              }
            }}
            """.trimIndent(),
        ) as ServerEvent.ItemStarted
        assertEquals(ToolStatus.IN_PROGRESS, (command.item as StreamItem.Command).status)

        val output = AppServerEventDecoder.decode(
            """{"method":"item/commandExecution/outputDelta","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"command-1","delta":"/data"}}""",
        ) as ServerEvent.CommandOutputDelta
        assertEquals("/data", output.delta)

        val mcp = AppServerEventDecoder.decode(
            """
            {"method":"item/completed","params":{
              "threadId":"thread-1","turnId":"turn-1","completedAtMs":1100,
              "item":{"type":"mcpToolCall","id":"mcp-1","server":"phone","tool":"battery","arguments":{},"status":"completed"}
            }}
            """.trimIndent(),
        ) as ServerEvent.ItemCompleted
        assertEquals(ToolStatus.COMPLETED, (mcp.item as StreamItem.McpTool).status)

        assertThrows(UnsupportedProtocolValueException::class.java) {
            AppServerEventDecoder.decode(
                """
                {"method":"item/completed","params":{
                  "threadId":"thread-1","turnId":"turn-1","completedAtMs":1100,
                  "item":{"type":"dynamicToolCall","id":"tool-1","tool":"phone","arguments":{},"status":"declined"}
                }}
                """.trimIndent(),
            )
        }
    }

    @Test
    fun terminalTurnCarriesFailureAndRequiresPinnedItemsArray() {
        val failed = AppServerEventDecoder.decode(
            """
            {"method":"turn/completed","params":{
              "threadId":"thread-1",
              "turn":{
                "id":"turn-1",
                "items":[],
                "status":"failed",
                "error":{"message":"network unavailable","additionalDetails":null,"codexErrorInfo":null},
                "startedAt":100,
                "completedAt":101
              }
            }}
            """.trimIndent(),
        ) as ServerEvent.TurnCompleted
        assertEquals(TurnStatus.FAILED, failed.turn.status)
        assertEquals("network unavailable", failed.turn.errorMessage)

        assertThrows(MalformedEnvelopeException::class.java) {
            AppServerEventDecoder.decode(
                """{"method":"turn/started","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"inProgress"}}}""",
            )
        }
    }

    @Test
    fun unknownExtensionsAreBoundedRawEventsWithoutWeakeningKnownEvents() {
        val raw = AppServerEventDecoder.decode(
            """{"method":"future/item/progress","params":[1,"two",{"safe":true}],"emittedAtMs":1787500838939}""",
        ) as ServerEvent.Raw
        assertEquals("future/item/progress", raw.method)
        assertEquals("[1,\"two\",{\"safe\":true}]", raw.boundedParamsJson)

        assertThrows(MalformedEnvelopeException::class.java) {
            AppServerEventDecoder.decode(
                """{"method":"item/agentMessage/delta","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"item-1"}}""",
            )
        }

        assertThrows(MalformedEnvelopeException::class.java) {
            AppServerEventDecoder.decode(
                """{"method":"future/event","params":{},"emittedAtMs":-1}""",
            )
        }
    }

    @Test
    fun skillsChangedAcceptsAdditiveInvalidationMetadata() {
        assertEquals(
            ServerEvent.SkillsChanged,
            AppServerEventDecoder.decode(
                """{"method":"skills/changed","params":{"revision":7}}""",
            ),
        )
    }

    @Test
    fun oversizedEventFrameIsRejectedBeforeParsing() {
        val oversized = "a".repeat(ProtocolLimits.MAX_EVENT_FRAME_BYTES)
        assertThrows(FrameLimitException::class.java) {
            AppServerEventDecoder.decode(
                """{"method":"future/event","params":{"value":"$oversized"}}""",
            )
        }
    }
}
