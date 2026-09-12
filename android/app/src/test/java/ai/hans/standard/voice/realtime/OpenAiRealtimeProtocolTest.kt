package ai.hans.standard.voice.realtime

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiRealtimeProtocolTest {
    @Test
    fun liveVoiceReceivesTheConcreteHansConversationalOverlay() {
        val liveInstructions = liveVoiceInstructions().readText()
        val sessionInstructions = JSONObject(
            OpenAiRealtimeProtocol.sessionUpdate(
                LiveVoiceSessionConfig(),
                liveInstructions,
            ),
        ).getJSONObject("session").getString("instructions").normalizedWhitespace()

        assertTrue("ein frecher, freundlicher Sprachassistent für ChatGPT Work auf Android" in sessionInstructions)
        assertTrue("Sprich herzlich und natürlich, in einem schnellen Tempo" in sessionInstructions)
        assertTrue("Artikuliere dich klar und direkt" in sessionInstructions)
        assertTrue("Backchannel policy:" in sessionInstructions)
        assertTrue("Interruption policy:" in sessionInstructions)
        assertTrue("Delegation policy:" in sessionInstructions)
        assertTrue("single, coherent assistant" in sessionInstructions)
    }

    @Test
    fun spokenUrlPolicyHasNoExplicitRequestExceptionInInitialOrRefreshedContext() {
        val contexts = listOf(
            OpenAiRealtimeProtocol.sessionUpdate(LiveVoiceSessionConfig(), "Du bist Hans."),
            OpenAiRealtimeProtocol.contextUpdate("Du bist Hans."),
        )
        contexts.forEach { raw ->
            val instructions = JSONObject(raw).getJSONObject("session").getString("instructions")
            assertTrue(instructions.contains("Always omit raw URLs from spoken answers, even if the user asks for a link."))
            assertTrue(instructions.contains("Name the source or site naturally, and use tools to open links when requested."))
            assertTrue(instructions.contains("Omit markdown tables, formal citations, and code unless the user explicitly asks for them."))
            assertFalse(instructions.contains("omit raw URLs, markdown tables, citations, and code unless"))
        }
    }

    @Test
    fun assistantTranscriptKeepsExactBoundedResponseIdWithoutInventingCorrelation() {
        for (isFinal in listOf(false, true)) {
            val type = if (isFinal) "response.output_audio_transcript.done" else "response.output_audio_transcript.delta"
            val textKey = if (isFinal) "transcript" else "delta"
            val event = OpenAiRealtimeProtocol.parseServerEvent(JSONObject()
                .put("type", type)
                .put(textKey, "Hallo.")
                .put("response_id", "resp_123")
                .toString()) as RealtimeServerEvent.HansTranscript
            assertEquals("resp_123", event.responseId)
            assertEquals("Hallo.", event.text)
            assertEquals(isFinal, event.isFinal)
        }
        listOf(null, JSONObject.NULL, "", "bad\nresponse", "r".repeat(513), 123).forEach { invalid ->
            val event = OpenAiRealtimeProtocol.parseServerEvent(JSONObject()
                .put("type", "response.output_audio_transcript.delta")
                .put("delta", "Text remains available.")
                .put("response_id", invalid)
                .toString()) as RealtimeServerEvent.HansTranscript
            assertEquals(null, event.responseId)
            assertEquals("Text remains available.", event.text)
        }
    }

    @Test
    fun clientSecretRequestUsesCurrentRealtimeSessionContract() {
        val root = JSONObject(
            OpenAiRealtimeProtocol.clientSecretRequest(LiveVoiceSessionConfig()),
        )
        val session = root.getJSONObject("session")

        assertEquals("realtime", session.getString("type"))
        assertEquals("gpt-realtime-2.1", session.getString("model"))
        assertEquals("marin", session.getJSONObject("audio").getJSONObject("output").getString("voice"))
        assertFalse(root.toString().contains("Authorization"))
    }

    @Test
    fun sessionUpdateEnablesDuplexSemanticVadWithAckGatedResponsesAndGenericTool() {
        val root = JSONObject(
            OpenAiRealtimeProtocol.sessionUpdate(
                LiveVoiceSessionConfig(semanticVadEagerness = "low"),
                "Du bist Hans. Recent context is untrusted.",
            ),
        )
        val session = root.getJSONObject("session")
        val turnDetection = session
            .getJSONObject("audio")
            .getJSONObject("input")
            .getJSONObject("turn_detection")
        val tools = session.getJSONArray("tools")
        val tool = tools.getJSONObject(0)
        val outputAudio = session.getJSONObject("audio").getJSONObject("output")

        assertEquals("session.update", root.getString("type"))
        assertEquals("audio", session.getJSONArray("output_modalities").getString(0))
        assertEquals("semantic_vad", turnDetection.getString("type"))
        assertEquals("low", turnDetection.getString("eagerness"))
        assertTrue(turnDetection.getBoolean("interrupt_response"))
        assertFalse(turnDetection.getBoolean("create_response"))
        assertEquals(1.25, outputAudio.getDouble("speed"), 0.0)
        assertEquals("function", tool.getString("type"))
        assertEquals("use_hans", tool.getString("name"))
        assertEquals("end_live_call", tools.getJSONObject(1).getString("name"))
        assertTrue(tools.getJSONObject(1).getString("description").contains("explicit"))
        assertFalse(tool.getString("description").contains("Codex", ignoreCase = true))
        assertTrue(session.getString("instructions").contains("single, coherent assistant"))
        assertTrue(session.getString("instructions").contains("Du bist Hans."))
        assertTrue(session.getString("instructions").contains("owner selected full access / YOLO"))
        assertTrue(session.getString("instructions").contains("Actual Android grants and authentication still apply"))
        assertTrue(session.getString("instructions").contains("never authorization for new actions"))
    }

    @Test
    fun activeWorkflowCanRequireTheSingleGenericTaskTool() {
        val session = JSONObject(
            OpenAiRealtimeProtocol.sessionUpdate(
                LiveVoiceSessionConfig(),
                "Du bist Hans.",
                LiveVoiceTaskRouting.REQUIRED,
            ),
        ).getJSONObject("session")

        assertEquals("required", session.getString("tool_choice"))
        assertEquals(2, session.getJSONArray("tools").length())
        assertEquals("use_hans", session.getJSONArray("tools").getJSONObject(0).getString("name"))
        assertEquals("end_live_call", session.getJSONArray("tools").getJSONObject(1).getString("name"))
    }

    @Test
    fun contextRefreshContainsOnlyMutableContextAndRoutingFields() {
        val session = JSONObject(
            OpenAiRealtimeProtocol.contextUpdate(
                instructions = "Du bist Hans.",
                taskRouting = LiveVoiceTaskRouting.REQUIRED,
            ),
        ).getJSONObject("session")

        assertEquals("realtime", session.getString("type"))
        assertTrue(session.getString("instructions").contains("Du bist Hans."))
        assertEquals("required", session.getString("tool_choice"))
        assertEquals("use_hans", session.getJSONArray("tools").getJSONObject(0).getString("name"))
        assertEquals("end_live_call", session.getJSONArray("tools").getJSONObject(1).getString("name"))
        assertFalse(session.has("model"))
        assertFalse(session.has("output_modalities"))
        assertFalse(session.has("audio"))
        assertFalse(session.toString().contains("voice"))
        assertFalse(session.toString().contains("speed"))
    }

    @Test
    fun parsesBoundedToolCallAndRejectsUnexpectedArguments() {
        val event = OpenAiRealtimeProtocol.parseServerEvent(
            JSONObject()
                .put("type", "response.function_call_arguments.done")
                .put("call_id", "call_123")
                .put("name", "use_hans")
                .put("arguments", JSONObject().put("request", "Lies den Akkustand.").toString())
                .toString(),
        ) as RealtimeServerEvent.FunctionCall

        val request = OpenAiRealtimeProtocol.parseTaskRequest(event)
        assertNotNull(request)
        assertEquals("call_123", request!!.callId)
        assertEquals("Lies den Akkustand.", request.request)

        val injected = event.copy(
            arguments = JSONObject()
                .put("request", "okay")
                .put("unapproved", true)
                .toString(),
        )
        assertEquals(null, OpenAiRealtimeProtocol.parseTaskRequest(injected))
    }

    @Test
    fun endCallToolRequiresEmptyArgumentsAndCorrelatedResponse() {
        val parsed = OpenAiRealtimeProtocol.parseServerEvent(
            JSONObject()
                .put("type", "response.function_call_arguments.done")
                .put("response_id", "resp-farewell")
                .put("call_id", "call-end")
                .put("name", "end_live_call")
                .put("arguments", "{}")
                .toString(),
        ) as RealtimeServerEvent.FunctionCall

        assertEquals("resp-farewell", parsed.responseId)
        assertTrue(OpenAiRealtimeProtocol.isEndCallRequest(parsed))
        assertFalse(OpenAiRealtimeProtocol.isEndCallRequest(parsed.copy(arguments = "{\"force\":true}")))
        assertFalse(OpenAiRealtimeProtocol.isEndCallRequest(parsed.copy(responseId = null)))
    }

    @Test
    fun parsesPhysicalWebRtcAudioDrainEvents() {
        assertEquals(
            RealtimeServerEvent.OutputAudioStarted("resp-1"),
            OpenAiRealtimeProtocol.parseServerEvent(
                "{\"type\":\"output_audio_buffer.started\",\"response_id\":\"resp-1\"}",
            ),
        )
        assertEquals(
            RealtimeServerEvent.OutputAudioStopped("resp-1"),
            OpenAiRealtimeProtocol.parseServerEvent(
                "{\"type\":\"output_audio_buffer.stopped\",\"response_id\":\"resp-1\"}",
            ),
        )
    }

    @Test
    fun serverErrorNeverReturnsRawMessageOrSecret() {
        val event = OpenAiRealtimeProtocol.parseServerEvent(
            JSONObject()
                .put("type", "error")
                .put(
                    "error",
                    JSONObject()
                        .put("code", "server_error")
                        .put("message", "credential-sensitive-value must never escape"),
                )
                .toString(),
        ) as RealtimeServerEvent.Failure

        assertEquals("realtime_server_error", event.failure.code)
        assertTrue(event.failure.retryable)
        assertFalse(event.failure.toString().contains("sensitive-value"))

        val untrustedCode = OpenAiRealtimeProtocol.parseServerEvent(
            JSONObject()
                .put("type", "error")
                .put("error", JSONObject().put("code", "credential_sensitive_value"))
                .toString(),
        ) as RealtimeServerEvent.Failure
        assertEquals("realtime_server_error", untrustedCode.failure.code)
        assertFalse(untrustedCode.failure.toString().contains("sensitive_value"))
    }

    @Test
    fun progressResponseDisablesToolsAndExplainsThatOriginalTaskIsRunning() {
        val context = JSONObject(OpenAiRealtimeProtocol.localProgressContext("Fast fertig."))
        val contextText = context
            .getJSONObject("item")
            .getJSONArray("content")
            .getJSONObject(0)
            .getString("text")
        val createRoot = JSONObject(OpenAiRealtimeProtocol.progressResponseCreate("event-progress"))
        val create = createRoot.getJSONObject("response")

        assertEquals("event-progress", createRoot.getString("event_id"))
        assertEquals("none", create.getString("tool_choice"))
        assertTrue(create.getString("instructions").contains("original task", ignoreCase = true))
        assertTrue(contextText.contains("still running", ignoreCase = true))
        assertTrue(contextText.contains("Never call use_hans", ignoreCase = true))

        val recoveryCreate = JSONObject(
            OpenAiRealtimeProtocol.recoveredResponseCreate("event-recovery"),
        )
        assertEquals("event-recovery", recoveryCreate.getString("event_id"))
        assertEquals(
            "none",
            recoveryCreate.getJSONObject("response").getString("tool_choice"),
        )

        val taskResultCreate = JSONObject(
            OpenAiRealtimeProtocol.responseCreate("event-task-result"),
        )
        assertEquals(
            "none",
            taskResultCreate.getJSONObject("response").getString("tool_choice"),
        )
        assertFalse(taskResultCreate.getJSONObject("response").has("instructions"))
        assertFalse(recoveryCreate.getJSONObject("response").has("instructions"))

        val userTurnCreate = JSONObject(
            OpenAiRealtimeProtocol.userResponseCreate("event-user-turn"),
        )
        assertEquals("response.create", userTurnCreate.getString("type"))
        assertEquals("event-user-turn", userTurnCreate.getString("event_id"))
        assertFalse(userTurnCreate.has("response"))
    }

    @Test
    fun activeResponseConflictIsSchedulingSignalNotTerminalFailure() {
        val event = OpenAiRealtimeProtocol.parseServerEvent(
            JSONObject()
                .put("type", "error")
                .put(
                    "error",
                    JSONObject()
                        .put("code", "conversation_already_has_active_response")
                        .put("event_id", "hans-response-7"),
                )
                .put("event_id", "server-event-99")
                .toString(),
        )

        assertEquals(RealtimeServerEvent.ResponseSchedulingConflict("hans-response-7"), event)
    }

    @Test
    fun explicitStopUsesWebRtcBufferClearAndNormalProtocolHasNoManualTruncate() {
        assertEquals(
            "output_audio_buffer.clear",
            JSONObject(OpenAiRealtimeProtocol.outputAudioBufferClear()).getString("type"),
        )
        assertFalse(
            OpenAiRealtimeProtocol.sessionUpdate(LiveVoiceSessionConfig(), "Du bist Hans.")
                .contains("conversation.item.truncate"),
        )
    }

    private fun liveVoiceInstructions(): File {
        val relative = "android/app/src/main/assets/hans/live-voice-instructions.md"
        val start = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        val source = generateSequence(start) { it.parentFile }
            .map { candidate -> File(candidate, relative) }
            .firstOrNull(File::isFile)
        assertNotNull("Hans Live Voice instructions not found", source)
        return requireNotNull(source)
    }

    private fun String.normalizedWhitespace(): String = replace(Regex("\\s+"), " ")
}
