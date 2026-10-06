package ai.hans.standard.voice.stt.android

import ai.hans.standard.voice.stt.CodexBatchTranscriptionFailure
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchTranscriptionWireTest {
    @Test fun validTextIsPreservedIncludingGermanNamesNumbersAndEscapedLineBreaks() {
        val transcript = " Jeremias: öffne Signal.\nNummer 42, Größe 1,5. "
        val wire = JSONObject().put("text", transcript).toString()
        assertEquals(transcript, decode(wire, 0).getOrThrow())
    }

    @Test fun exactlyMaximumLengthTranscriptIsAccepted() {
        val transcript = "x".repeat(16_000)
        assertEquals(transcript, decode(JSONObject().put("text", transcript).toString(), 0).getOrThrow())
    }

    @Test fun validStableNativeFailureNeverBecomesTranscriptText() {
        val result = decode("{\"error\":\"codex_transcription_auth_required\"}\n", 1)
        assertTrue(result.isFailure)
        assertEquals("codex_transcription_auth_required", (result.exceptionOrNull() as CodexBatchTranscriptionFailure).code)
    }

    @Test fun exitCodeAndResponseTypeMustAgree() {
        invalid("{\"text\":\"Should not be accepted\"}", 1)
        invalid("{\"error\":\"codex_transcription_timeout\"}", 0)
    }

    @Test fun aNonzeroNativeExitPreservesOnlyAWellFormedStableFailureCode() {
        invalid("{\"error\":\"token=private-secret\"}", 1)
        invalid("{\"error\":\"codex_transcription_quota: account detail\"}", 1)
        invalid("{\"error\":\"codex_transcription_TIMEOUT\"}", 1)
        invalid("{\"error\":\"codex_transcription_\"}", 1)
        invalid(JSONObject().put("error", "codex_transcription_" + "x".repeat(65)).toString(), 1)
    }

    @Test fun missingOrAdditionalFieldsFailClosed() {
        invalid("{}")
        invalid("{\"text\":\"Text\",\"error\":\"codex_transcription_timeout\"}")
        invalid("{\"text\":\"Text\",\"account\":\"private account\"}")
        invalid("{\"other\":\"Text\"}")
        invalid("{\"error\":\"codex_transcription_timeout\",\"detail\":\"private detail\"}", 1)
    }

    @Test fun nonStringTextAndErrorAreRejected() {
        listOf("null", "true", "42", "[]", "{}").forEach { value ->
            invalid("{\"text\":$value}")
            invalid("{\"error\":$value}", 1)
        }
    }

    @Test fun emptyBlankAndOverlongTranscriptsAreRejected() {
        listOf("", " \t\n", "x".repeat(16_001)).forEach { text ->
            invalid(JSONObject().put("text", text).toString())
        }
    }

    @Test fun multipleOutputLinesAndPrettyPrintedObjectsAreRejected() {
        invalid("{\"text\":\"First\"}\n{\"text\":\"Second\"}")
        invalid("{\n\"text\":\"Text\"\n}")
        invalid("{\"text\":\"Text\"}\r\nprivate stderr")
    }

    @Test fun trailingJsonOrNonJsonMaterialIsRejected() {
        invalid("{\"text\":\"Text\"} {\"text\":\"Second\"}")
        invalid("{\"text\":\"Text\"} private trailing data")
        invalid("{\"text\":\"Text\"} true")
    }

    @Test fun malformedJsonAndNonObjectTopLevelValuesAreRejected() {
        listOf("", "not json", "[]", "null", "\"Text\"", "{\"text\":", "{\"text\":\"Text\"").forEach { invalid(it) }
    }

    @Test fun duplicateFieldsAndNonstandardQuotingAreRejected() {
        invalid("{\"text\":\"First\",\"text\":\"Second\"}")
        invalid("{'text':'Text'}")
        invalid("{text:\"Text\"}")
        invalid("{\"text\":\"Text\",}")
    }

    @Test fun malformedUtf8FailsWithOnlyTheStableResponseInvalidCode() {
        val result = BatchTranscriptionWire.decode(byteArrayOf(0xC3.toByte(), 0x28), 0)
        assertInvalid(result)
    }

    @Test fun oversizedWireResponseIsRejectedEvenIfTheTextFieldLooksValid() {
        invalid(" ".repeat(65_537) + "{\"text\":\"Text\"}")
        invalid("{\"text\":\"Text\",\"extra\":\"" + "x".repeat(65_537) + "\"}")
    }

    private fun decode(wire: String, code: Int = 0): Result<String> =
        BatchTranscriptionWire.decode(wire.toByteArray(Charsets.UTF_8), code)

    private fun invalid(wire: String, code: Int = 0) = assertInvalid(decode(wire, code))

    private fun assertInvalid(result: Result<String>) {
        assertTrue("unsafe or malformed native output must not become user text", result.isFailure)
        val failure = result.exceptionOrNull()
        assertTrue(failure is CodexBatchTranscriptionFailure)
        assertEquals("codex_transcription_response_invalid", (failure as CodexBatchTranscriptionFailure).code)
        assertEquals("codex_transcription_response_invalid", failure.message)
    }
}
