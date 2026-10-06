package ai.hans.standard.voice.realtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenAiLiveProtocolTest {
    @Test
    fun specificProjectLimitIsNotFlattenedIntoGenericQuota() {
        val event = OpenAiLiveProtocol.parseServerEvent(
            """{"type":"error","error":{"code":"project_spend_limit_exceeded","type":"insufficient_quota","message":"sk-secret"}}""",
        ) as LiveServerEvent.Failure
        assertEquals("live_project_spending_limit_reached", event.failure.code)
        assertFalse(event.failure.retryable)
        assertFalse(event.toString().contains("sk-secret"))
    }

    private val setup = LiveSessionSetup(LiveVoiceSessionConfig(), "Du bist Hans.")

    @Test
    fun webRtcCreationUsesFixedLiveModelAndOnlyLiveClientDelegationFields() {
        val root = JSONObject(OpenAiLiveProtocol.sessionCreate(setup, "v=0\r\n"))
        assertEquals(setOf("session", "transport"), root.keys().asSequence().toSet())
        val session = root.getJSONObject("session")
        assertEquals("gpt-live-1", session.getString("model"))
        assertEquals("client", session.getJSONObject("delegation").getString("type"))
        assertEquals("Du bist Hans.", session.getString("instructions"))
        assertFalse(session.getBoolean("store"))
        assertEquals(0, session.getJSONArray("input").length())
        val audio = session.getJSONObject("audio")
        assertEquals(setOf("output"), audio.keys().asSequence().toSet())
        val output = audio.getJSONObject("output")
        assertEquals(setOf("voice"), output.keys().asSequence().toSet())
        assertEquals("marin", output.getString("voice"))
        assertEquals("webrtc", root.getJSONObject("transport").getString("type"))
        assertEquals("v=0\r\n", root.getJSONObject("transport").getString("sdp"))
        for (field in listOf("type", "output_modalities", "tools", "tool_choice", "turn_detection")) {
            assertFalse("Legacy field $field", session.has(field))
        }
        for (field in listOf("input", "format", "speed", "transcription")) {
            assertFalse("Legacy audio field $field", audio.has(field))
        }
    }

    @Test
    fun webRtcPermissionsUseStringClientTypesButObjectServerSelectors() {
        val channel = JSONObject(OpenAiLiveProtocol.sessionCreate(setup, "offer"))
            .getJSONObject("session").getJSONObject("client").getJSONObject("data_channel")
        val clients = channel.getJSONArray("allowed_client_events")
        assertEquals(
            setOf("session.instructions.append", "session.thinking.append", "session.commentary.append",
                "session.input_audio.mute", "session.input_audio.unmute", "session.close"),
            (0 until clients.length()).map { clients.getString(it) }.toSet(),
        )
        val servers = channel.getJSONArray("allowed_server_events")
        val types = (0 until servers.length()).map { index ->
            val selector = servers.getJSONObject(index)
            assertEquals(setOf("type"), selector.keys().asSequence().toSet())
            selector.getString("type")
        }.toSet()
        assertTrue(types.containsAll(listOf("session.started", "session.closed", "error",
            "session.delegation.created", "session.input_transcript.delta", "session.output_transcript.delta")))
        assertFalse(types.any { it.startsWith("response.") })
        assertFalse(clients.toString().contains("session.start\""))
    }

    @Test
    fun clientOwnsAllBackendWorkWithoutManagedResponsesOrNativeWebSearch() {
        val session = JSONObject(OpenAiLiveProtocol.sessionCreate(setup, "offer"))
            .getJSONObject("session")
        // Live-managed web_search belongs to delegation.responses.tools. Client mode
        // configures no such backend; adding Realtime tool_choice is not a disable flag.
        val delegation = session.getJSONObject("delegation")
        assertEquals(setOf("type"), delegation.keys().asSequence().toSet())
        assertEquals("client", delegation.getString("type"))
        assertEquals(setOf("model", "instructions", "delegation", "audio", "input", "store", "client"),
            session.keys().asSequence().toSet())
        assertFalse(session.toString().contains("web_search"))
        assertFalse(session.has("tool_choice"))
        assertFalse(session.has("thinking_sound"))
    }

    @Test
    fun historyUsesOneTextPartAndSupportedRolesOnly() {
        val history = listOf(
            LiveHistoryMessage(LiveHistoryRole.USER, "Mein Termin?"),
            LiveHistoryMessage(LiveHistoryRole.ASSISTANT, "Welcher Tag?"),
            LiveHistoryMessage(LiveHistoryRole.DEVELOPER, "Only confirmed records."),
        )
        val input = JSONObject(OpenAiLiveProtocol.sessionCreate(setup.copy(history = history), "offer"))
            .getJSONObject("session").getJSONArray("input")
        listOf("user" to "input_text", "assistant" to "output_text", "developer" to "input_text")
            .forEachIndexed { index, (role, type) ->
                val message = input.getJSONObject(index)
                assertEquals("message", message.getString("type"))
                assertEquals(role, message.getString("role"))
                assertEquals(1, message.getJSONArray("content").length())
                assertEquals(type, message.getJSONArray("content").getJSONObject(0).getString("type"))
            }
    }

    @Test
    fun rejectsOversizeOrInvalidStartupWithoutSilentlyDroppingContext() {
        rejects { OpenAiLiveProtocol.sessionCreate(setup.copy(instructions = ""), "offer") }
        rejects { OpenAiLiveProtocol.sessionCreate(setup.copy(instructions = "a".repeat(65_537)), "offer") }
        rejects { OpenAiLiveProtocol.sessionCreate(setup, " ") }
        rejects { OpenAiLiveProtocol.sessionCreate(setup, "a".repeat(OpenAiLiveProtocol.MAX_SDP_BYTES + 1)) }
        rejects { OpenAiLiveProtocol.sessionCreate(setup.copy(config = setup.config.copy(voice = "fable")), "offer") }
        rejects { OpenAiLiveProtocol.sessionCreate(setup.copy(history = List(129) {
            LiveHistoryMessage(LiveHistoryRole.USER, "a")
        }), "offer") }
        rejects { OpenAiLiveProtocol.sessionCreate(setup.copy(history = listOf(
            LiveHistoryMessage(LiveHistoryRole.USER, "ä".repeat(4_097)),
        )), "offer") }
    }

    @Test
    fun allCurrentBuiltInVoicesCanBeSelectedAtStartup() {
        assertEquals(22, OpenAiLiveProtocol.supportedVoices.size)
        OpenAiLiveProtocol.supportedVoices.forEach { voice ->
            val request = JSONObject(OpenAiLiveProtocol.sessionCreate(
                setup.copy(config = setup.config.copy(voice = voice)), "offer",
            ))
            assertEquals(voice, request.getJSONObject("session").getJSONObject("audio")
                .getJSONObject("output").getString("voice"))
        }
    }

    @Test
    fun parsesJsonSessionAnswerAndNeverRendersSdp() {
        val answer = OpenAiLiveProtocol.parseSessionAnswer(
            """{"session":{"id":"live_opaque"},"transport":{"type":"webrtc","sdp":"private-sdp"}}""",
        )!!
        assertEquals("live_opaque", answer.sessionId)
        assertEquals("private-sdp", answer.sdp)
        assertFalse(answer.toString().contains("private-sdp"))
        listOf("v=0", "{}", "{", """{"session":{"id":5},"transport":{"type":"webrtc","sdp":"answer"}}""",
            """{"session":{"id":"live_1"},"transport":{"type":"websocket","sdp":"answer"}}""",
            """{"session":{"id":"live_1"},"transport":{"type":"webrtc","sdp":42}}""")
            .forEach { assertNull(OpenAiLiveProtocol.parseSessionAnswer(it)) }
        assertNull(OpenAiLiveProtocol.parseSessionAnswer(" ".repeat(OpenAiLiveProtocol.MAX_EVENT_BYTES + 1)))
    }

    @Test
    fun appendTypesUsePlainStringsAndRequiredNullableDelegationId() {
        val encoders = listOf(
            OpenAiLiveProtocol::instructionsAppend,
            OpenAiLiveProtocol::thinkingAppend,
            OpenAiLiveProtocol::commentaryAppend,
        )
        encoders.forEach { encode ->
            for (delegation in listOf(null, "item_opaque")) {
                val event = JSONObject(encode("Ergebnis.", "event_1", delegation))
                assertEquals("Ergebnis.", event.getString("content"))
                assertEquals("event_1", event.getString("event_id"))
                assertTrue(event.has("delegation_id"))
                if (delegation == null) assertTrue(event.isNull("delegation_id"))
                else assertEquals(delegation, event.getString("delegation_id"))
                assertEquals(setOf("type", "content", "event_id", "delegation_id"),
                    event.keys().asSequence().toSet())
            }
            rejects { encode("a".repeat(501), "event_1", null) }
            rejects { encode("ä".repeat(251), "event_1", null) }
            rejects { encode("", "event_1", null) }
            rejects { encode("Text", "bad\nevent", null) }
            rejects { encode("Text", "event_1", "") }
        }
    }

    @Test
    fun contentChunksPreserveUnicodeExactlyAndRespectByteBound() {
        for (text in listOf("a".repeat(1_001), "ä".repeat(501), "🙂".repeat(251),
            "a".repeat(500) + " ".repeat(500) + "b",
            "Text mit ä und 🙂. ".repeat(100))) {
            val chunks = OpenAiLiveProtocol.contentChunks(text)
            assertEquals(text, chunks.joinToString(""))
            assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).size <= 500 })
            chunks.forEach { OpenAiLiveProtocol.thinkingAppend(it, "chunk_1") }
        }
        rejects { OpenAiLiveProtocol.contentChunks("\uD800") }
        rejects { OpenAiLiveProtocol.commentaryAppend("\uD800", "event_1") }
    }

    @Test
    fun transcriptFragmentsRetainOriginalEventIdAndFractionalTimelineWithoutTurnFinality() {
        val input = OpenAiLiveProtocol.parseServerEvent(
            """{"type":"session.input_transcript.delta","event_id":"event_input","delta":"Donnerstag","start_ms":120.5,"end_ms":450.25} """,
        ) as LiveServerEvent.InputTranscript
        assertEquals("event_input", input.eventId)
        assertEquals("Donnerstag", input.text)
        assertEquals(120.5, input.startMs, 0.0)
        assertEquals(450.25, input.endMs, 0.0)
        val output = OpenAiLiveProtocol.parseServerEvent(
            """{"type":"session.output_transcript.delta","event_id":"event_output","delta":"Ich prüfe.","start_ms":500,"end_ms":800} """,
        ) as LiveServerEvent.OutputTranscript
        assertEquals("event_output", output.eventId)
        assertEquals("Ich prüfe.", output.text)
    }

    @Test
    fun delegationContainsOnlyOpaqueIdAndTimelineNotFabricatedTaskText() {
        val event = OpenAiLiveProtocol.parseServerEvent(
            """{"type":"session.delegation.created","event_id":"event_delegate","offset_ms":300.5,"delegation":{"id":"item_task","target":"client","type":"delegation"}}""",
        )
        assertEquals(LiveServerEvent.Delegation("item_task", 300.5, "event_delegate"), event)
        assertEquals(LiveServerEvent.Ignored, OpenAiLiveProtocol.parseServerEvent(
            """{"type":"session.delegation.created","event_id":"event_delegate","offset_ms":300,"delegation":{"id":"item_task","target":"responses","type":"delegation"}}""",
        ))
    }

    @Test
    fun startupAndCloseUseEventTypeNotEmbeddedActiveStatus() {
        val started = OpenAiLiveProtocol.parseServerEvent(
            """{"type":"session.started","event_id":"event_start","session":{"id":"live_1","model":"gpt-live-1","status":"active","expires_at":1234,"audio":{"output":{"voice":"marin"}}}}""",
        ) as LiveServerEvent.Started
        assertEquals("gpt-live-1", started.model)
        assertEquals("marin", started.voice)
        assertEquals(1234.0, started.expiresAtEpochSeconds!!, 0.0)
        val closed = OpenAiLiveProtocol.parseServerEvent(
            """{"type":"session.closed","event_id":"event_close","client_event_id":"close_1","session":{"id":"live_1","status":"active"},"reason":"close_requested","usage":{"seconds":23.5}}""",
        ) as LiveServerEvent.Closed
        assertEquals("close_requested", closed.reason)
        assertEquals(23.5, closed.usageSeconds, 0.0)
        assertEquals("close_1", closed.clientEventId)
    }

    @Test
    fun acknowledgmentsCorrelateAcceptanceWithoutInventingPlaybackEvents() {
        val ack = OpenAiLiveProtocol.parseServerEvent(
            """{"type":"session.commentary.appended","event_id":"ack_1","client_event_id":"result_1","start_ms":10,"end_ms":10} """,
        ) as LiveServerEvent.Acknowledged
        assertEquals("result_1", ack.clientEventId)
        assertEquals("session.commentary.appended", ack.type)
        assertEquals(10.0, ack.endMs!!, 0.0)
        assertTrue(OpenAiLiveProtocol.parseServerEvent(
            """{"type":"session.input_audio.muted","event_id":"ack_mute","client_event_id":"mute_1"}""",
        ) is LiveServerEvent.Acknowledged)
        assertEquals("session.input_audio.mute", JSONObject(OpenAiLiveProtocol.mute("mute_1")).getString("type"))
        assertEquals("session.input_audio.unmute", JSONObject(OpenAiLiveProtocol.unmute("unmute_1")).getString("type"))
        assertEquals("session.close", JSONObject(OpenAiLiveProtocol.close("close_1")).getString("type"))
    }

    @Test
    fun cumulativeUsageAndContentFreeErrorsAreParsed() {
        assertEquals(LiveServerEvent.Usage(12.0, 0.42, "event_usage"), OpenAiLiveProtocol.parseServerEvent(
            """{"type":"session.usage.updated","event_id":"event_usage","usage":{"seconds":12},"context_window":{"usage_ratio":0.42}}""",
        ))
        val error = OpenAiLiveProtocol.parseServerEvent(
            """{"type":"error","event_id":"event_error","error":{"code":"insufficient_quota","type":"invalid_request_error","message":"private-secret-content","client_event_id":"result_1"}}""",
        ) as LiveServerEvent.Failure
        assertEquals("live_quota_exhausted", error.failure.code)
        assertFalse(error.failure.retryable)
        assertEquals("result_1", error.clientEventId)
        assertFalse(error.toString().contains("private-secret-content"))
    }

    @Test
    fun legacyAndUndocumentedEventsNeverSynthesizeLiveTurnOrTaskEvents() {
        for (type in listOf("session.created", "session.updated", "response.created", "response.done",
            "response.function_call_arguments.done", "input_audio_buffer.speech_started",
            "input_audio_buffer.speech_stopped", "output_audio_buffer.stopped",
            "session.input_transcript.done", "session.conversation.get", "session.delegation.completed")) {
            assertEquals(type, LiveServerEvent.Ignored,
                OpenAiLiveProtocol.parseServerEvent(JSONObject().put("type", type).toString()))
        }
    }

    @Test
    fun rejectsMalformedOversizedAndTypeCoercedKnownEvents() {
        val valid = JSONObject()
            .put("type", "session.input_transcript.delta").put("event_id", "event_1")
            .put("delta", "Hi").put("start_ms", 1).put("end_ms", 2)
        val bad = listOf("{", "[]", "{}", " ".repeat(OpenAiLiveProtocol.MAX_EVENT_BYTES + 1),
            JSONObject(valid.toString()).put("event_id", 5).toString(),
            JSONObject(valid.toString()).put("delta", JSONArray()).toString(),
            JSONObject(valid.toString()).put("start_ms", "1").toString(),
            JSONObject(valid.toString()).put("start_ms", -1).toString(),
            JSONObject(valid.toString()).put("end_ms", 0).toString(),
            JSONObject(valid.toString()).put("delta", "a".repeat(32_001)).toString())
        bad.forEach { assertTrue(OpenAiLiveProtocol.parseServerEvent(it) is LiveServerEvent.Failure) }
    }

    private inline fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid Live input to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected; no original content is logged.
        }
    }
}
