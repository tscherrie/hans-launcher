package ai.hans.standard.voice.tts.android

import ai.hans.standard.voice.tts.TtsMessageId
import ai.hans.standard.voice.tts.TtsSegmentId
import ai.hans.standard.voice.tts.TtsSynthesisRequest
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiTtsRequestTest {
    @Test
    fun encodesOfficialSpeechContractIncludingServerSideSpeedAndInstructions() {
        val request = request(
            text = "  Hallo \"Hans\".\nWie geht es?  ",
            voice = " FABLE ",
            speed = 1.25,
        )

        val validated = OpenAiTtsRequest.validate(request, " Warm und klar sprechen. ")
        val encoded = String(OpenAiTtsRequest.encodeUtf8(validated), StandardCharsets.UTF_8)
        val json = JSONObject(encoded)

        assertEquals("gpt-4o-mini-tts", json.getString("model"))
        assertEquals("Hallo \"Hans\".\nWie geht es?", json.getString("input"))
        assertEquals("fable", json.getString("voice"))
        assertEquals("pcm", json.getString("response_format"))
        assertEquals(1.25, json.getDouble("speed"), 0.0)
        assertEquals("Warm und klar sprechen.", json.getString("instructions"))
        assertEquals(6, json.length())
        assertFalse(encoded.contains("message-id"))
    }

    @Test
    fun everyDocumentedVoiceIncludingFableIsAccepted() {
        val expected = setOf(
            "alloy",
            "ash",
            "ballad",
            "coral",
            "echo",
            "fable",
            "nova",
            "onyx",
            "sage",
            "shimmer",
            "verse",
            "marin",
            "cedar",
        )

        assertEquals(expected, OpenAiTtsRequest.supportedVoices)
        expected.forEach { voice ->
            assertEquals(voice, OpenAiTtsRequest.validate(request(voice = voice)).voice)
        }
    }

    @Test
    fun invalidVoiceSpeedTextInstructionsAndUnicodeAreRejectedBeforeTransport() {
        assertInvalid(request(voice = "not-a-voice"))
        assertInvalid(request(speed = 4.01))
        assertInvalid(request(text = "x".repeat(OpenAiTtsRequest.MAX_INPUT_CHARACTERS + 1)))
        assertInvalid(request(text = "\uD800"))
        assertInvalid(request(), instructions = " ")
        assertInvalid(
            request(),
            instructions = "x".repeat(OpenAiTtsRequest.MAX_INSTRUCTIONS_CHARACTERS + 1),
        )
    }

    @Test
    fun jsonEscapesEveryControlCharacterWithoutLosingUnicode() {
        val input = "A\u0000\b\u000c\n\r\t\\\" Grüß dich 👋"
        val json = JSONObject(
            String(
                OpenAiTtsRequest.encodeUtf8(
                    OpenAiTtsRequest.validate(request(text = input)),
                ),
                StandardCharsets.UTF_8,
            ),
        )

        assertEquals(input, json.getString("input"))
        assertTrue(json.getString("instructions").isNotBlank())
    }

    private fun assertInvalid(
        request: TtsSynthesisRequest,
        instructions: String = OpenAiTtsRequest.DEFAULT_INSTRUCTIONS,
    ) {
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiTtsRequest.validate(request, instructions)
        }
    }

    private fun request(
        text: String = "Hallo.",
        voice: String = "fable",
        speed: Double = 1.25,
    ) = TtsSynthesisRequest(
        segmentId = TtsSegmentId(TtsMessageId("message-id"), 0),
        text = text,
        voice = voice,
        speed = speed,
    )
}
