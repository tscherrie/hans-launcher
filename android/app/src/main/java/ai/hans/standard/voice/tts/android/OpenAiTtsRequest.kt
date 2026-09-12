package ai.hans.standard.voice.tts.android

import ai.hans.standard.voice.tts.TtsSynthesisRequest
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * The public Speech API contract used by Hans. Keeping validation and JSON
 * construction independent from Android makes the wire contract directly
 * testable without a device or a network connection.
 */
object OpenAiTtsRequest {
    const val ENDPOINT = "https://api.openai.com/v1/audio/speech"
    const val MODEL = "gpt-4o-mini-tts"
    const val RESPONSE_FORMAT = "pcm"
    const val SAMPLE_RATE_HZ = 24_000
    const val CHANNEL_COUNT = 1
    const val PCM_MIME_TYPE = "audio/pcm"
    const val DEFAULT_INSTRUCTIONS =
        "Speak naturally and clearly in the language of the input."

    const val MAX_INPUT_CHARACTERS = 4_096
    const val MAX_INSTRUCTIONS_CHARACTERS = 1_024
    const val MAX_REQUEST_BYTES = 32 * 1_024

    val supportedVoices: Set<String> = linkedSetOf(
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

    data class Validated(
        val input: String,
        val voice: String,
        val speed: Double,
        val instructions: String,
    )

    fun validate(
        request: TtsSynthesisRequest,
        instructions: String = DEFAULT_INSTRUCTIONS,
    ): Validated {
        val input = request.text.trim()
        require(input.isNotEmpty()) { "tts_input_blank" }
        require(input.length <= MAX_INPUT_CHARACTERS) { "tts_input_too_large" }
        require(!hasUnpairedSurrogate(input)) { "tts_input_invalid_unicode" }

        val voice = request.voice.trim().lowercase(Locale.US)
        require(voice in supportedVoices) { "tts_voice_unsupported" }
        require(request.speed.isFinite() && request.speed in 0.25..4.0) {
            "tts_speed_unsupported"
        }

        val boundedInstructions = instructions.trim()
        require(boundedInstructions.isNotEmpty()) { "tts_instructions_blank" }
        require(boundedInstructions.length <= MAX_INSTRUCTIONS_CHARACTERS) {
            "tts_instructions_too_large"
        }
        require(!hasUnpairedSurrogate(boundedInstructions)) {
            "tts_instructions_invalid_unicode"
        }

        return Validated(
            input = input,
            voice = voice,
            speed = request.speed,
            instructions = boundedInstructions,
        )
    }

    fun encodeUtf8(validated: Validated): ByteArray {
        val json = buildString(
            validated.input.length + validated.instructions.length + 160,
        ) {
            append('{')
            append("\"model\":\"")
            append(MODEL)
            append("\",\"input\":\"")
            appendJsonStringContents(validated.input)
            append("\",\"voice\":\"")
            append(validated.voice)
            append("\",\"response_format\":\"")
            append(RESPONSE_FORMAT)
            append("\",\"speed\":")
            append(formatSpeed(validated.speed))
            append(",\"instructions\":\"")
            appendJsonStringContents(validated.instructions)
            append("\"}")
        }
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_REQUEST_BYTES) { "tts_request_too_large" }
        return bytes
    }

    private fun formatSpeed(speed: Double): String =
        java.math.BigDecimal.valueOf(speed).stripTrailingZeros().toPlainString()

    private fun StringBuilder.appendJsonStringContents(value: String) {
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
    }

    private fun hasUnpairedSurrogate(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val current = value[index]
            when {
                current.isHighSurrogate() -> {
                    if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) {
                        return true
                    }
                    index += 2
                }
                current.isLowSurrogate() -> return true
                else -> index += 1
            }
        }
        return false
    }
}
