package ai.hans.standard.codex

import ai.hans.standard.integration.CodexRealtimeIssue
import ai.hans.standard.integration.CodexRealtimeOptions
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodexRealtimeProtocolTest {
    @Test fun speechUsesOnlyNativeTextAppendAndIsByteBounded() {
        val wire = JSONObject(CodexRealtimeProtocol.appendSpeech("hans_realtime_speech", "main", "Fertige Antwort."))
        assertEquals("thread/realtime/appendSpeech", wire.getString("method"))
        assertEquals(setOf("threadId", "text"), wire.getJSONObject("params").keys().asSequence().toSet())
        assertEquals("Fertige Antwort.", wire.getJSONObject("params").getString("text"))
        for (text in listOf("", "  ", "ü".repeat(CodexRealtimeProtocol.MAX_TEXT_BYTES))) {
            assertTrue(runCatching {
                CodexRealtimeProtocol.appendSpeech("hans_realtime_speech", "main", text)
            }.isFailure)
        }
    }

    private fun start(sdp: String = "v=0\r\n", prompt: String = "prompt", voice: String? = "arbor") =
        CodexRealtimeProtocol.start("hans_realtime_request", "main", "session", sdp, prompt, voice)

    @Test fun exactV3WebRtcRequestNeverExportsCredentialsOrStartsAnotherThread() {
        val wire = JSONObject(start())
        assertEquals("thread/realtime/start", wire.getString("method"))
        val params = wire.getJSONObject("params")
        assertEquals(setOf("threadId", "realtimeSessionId", "transport", "version", "model", "outputModality",
            "prompt", "includeStartupContext", "clientManagedHandoffs", "delegationAckFiller",
            "flushTranscriptTailOnSessionEnd", "voice"), params.keys().asSequence().toSet())
        assertEquals("webrtc", params.getJSONObject("transport").getString("type"))
        assertEquals("main", params.getString("threadId"))
        assertFalse(params.getBoolean("clientManagedHandoffs"))
        assertTrue(params.getBoolean("includeStartupContext"))
    }

    @Test fun sdpAndPromptAreByteBoundedAndApiVoiceCannotLeakIntoV3() {
        assertTrue(runCatching { start(sdp = "v=0" + "x".repeat(CodexRealtimeProtocol.MAX_SDP_BYTES)) }.isFailure)
        assertTrue(runCatching { start(prompt = "ü".repeat(CodexRealtimeProtocol.MAX_TEXT_BYTES)) }.isFailure)
        assertTrue(runCatching { start(voice = "marin") }.isFailure)
        assertTrue(runCatching { start(voice = "alloy") }.isFailure)
        assertTrue(runCatching { start(voice = null) }.isSuccess)
        assertTrue(runCatching { start(voice = "cove") }.isSuccess)
    }

    @Test fun knownProviderFailuresAreSafeClassifications() {
        assertEquals(CodexRealtimeIssue.NOT_AVAILABLE, CodexRealtimeProtocol.issue("no feature", -32601))
        assertEquals(CodexRealtimeIssue.NOT_AVAILABLE, CodexRealtimeProtocol.issue("not entitled bearer private"))
        assertEquals(CodexRealtimeIssue.USAGE_LIMIT, CodexRealtimeProtocol.issue("insufficient_quota"))
        assertEquals(CodexRealtimeIssue.AUTHENTICATION, CodexRealtimeProtocol.issue("HTTP 401 bearer private"))
        assertEquals(CodexRealtimeIssue.CONNECTION_FAILED, CodexRealtimeProtocol.issue("unknown private details"))
    }

    @Test fun shortCallDisablesFillerWithoutChangingNativeDelegationOrTailPolicy() {
        val params = JSONObject(CodexRealtimeProtocol.start("hans_realtime_short", "main", "session", "v=0",
            "prompt", null, CodexRealtimeOptions(delegationAckFiller = false))).getJSONObject("params")
        assertFalse(params.getBoolean("delegationAckFiller"))
        assertFalse(params.getBoolean("clientManagedHandoffs"))
        assertFalse(params.getBoolean("flushTranscriptTailOnSessionEnd"))
        assertTrue(JSONObject(start()).getJSONObject("params").getBoolean("delegationAckFiller"))
    }

    @Test fun readerOmitsStartupContextAndAutomaticReturnSpeechWithoutInventingNativePermissions() {
        val params = JSONObject(CodexRealtimeProtocol.start("hans_realtime_reader", "main", "session", "v=0",
            "Read the existing answer", null, CodexRealtimeOptions(false, false, true))).getJSONObject("params")
        assertFalse(params.getBoolean("includeStartupContext"))
        assertFalse(params.getBoolean("delegationAckFiller"))
        assertTrue(params.getBoolean("clientManagedHandoffs"))
        assertFalse(params.getBoolean("flushTranscriptTailOnSessionEnd"))
        assertFalse(params.has("allowIncomingHandoffs")) // Not available in the official runtime.
    }

    @Test fun bufferedAudioIsStrictPcm16Mono24KhzBoundedAndNeverContainsCredentials() {
        val audio = JSONObject(CodexRealtimeProtocol.appendAudio("hans_realtime_audio", "main", "AAAAAA==", 24_000))
        assertEquals("thread/realtime/appendAudio", audio.getString("method"))
        val params = audio.getJSONObject("params")
        assertEquals(setOf("threadId", "audio"), params.keys().asSequence().toSet())
        val chunk = params.getJSONObject("audio")
        assertEquals(setOf("data", "sampleRate", "numChannels", "samplesPerChannel"), chunk.keys().asSequence().toSet())
        assertEquals(24_000, chunk.getInt("sampleRate"))
        assertEquals(1, chunk.getInt("numChannels"))
        assertEquals(2, chunk.getInt("samplesPerChannel"))
        val max = Base64.getEncoder().encodeToString(ByteArray(CodexRealtimeProtocol.MAX_AUDIO_BYTES))
        assertTrue(runCatching { CodexRealtimeProtocol.appendAudio("hans_realtime_max", "main", max, 24_000) }.isSuccess)
        for ((data, rate) in listOf("AAAAAA==" to 16_000, "" to 24_000, "AA==" to 24_000,
            "AAAAAA" to 24_000, "AAAAAA==\n" to 24_000, "%%%=" to 24_000,
            Base64.getEncoder().encodeToString(ByteArray(CodexRealtimeProtocol.MAX_AUDIO_BYTES + 2)) to 24_000)) {
            assertTrue(runCatching { CodexRealtimeProtocol.appendAudio("hans_realtime_bad", "main", data, rate) }.isFailure)
        }
    }
}
