package ai.hans.standard.voice.stt

import java.text.Normalizer
import java.util.Locale

/**
 * User-confirmed, read-only spelling context captured once for a dictation session.
 *
 * Neither field is evidence about the current utterance. The transcription model may use it only
 * to spell words that were actually spoken.
 */
data class SttTranscriptionContext(
    val confirmedProfileSummary: String? = null,
    val confirmedGlossaryTerms: List<String> = emptyList(),
)

fun interface SttTranscriptionContextSource {
    fun snapshot(): SttTranscriptionContext

    companion object {
        val EMPTY = SttTranscriptionContextSource { SttTranscriptionContext() }
    }
}

/** Produces a bounded, control-character-safe hint without fixing an input language. */
object SttTranscriptionPromptBuilder {
    /**
     * OpenAI rejects overlong transcription prompt strings before audio is processed.
     * Keep the entire UTF-8 value comfortably below the provider boundary; a byte budget also
     * remains correct for umlauts, emoji and other multi-byte confirmed names.
     */
    const val MAX_PROMPT_UTF8_BYTES = 512
    const val MAX_PROMPT_CHARACTERS = MAX_PROMPT_UTF8_BYTES
    const val MAX_GLOSSARY_TERMS = 32
    const val MAX_GLOSSARY_TERM_CHARACTERS = 64

    private const val HEADER =
        "Spelling context only. Transcribe exactly what is spoken, preserve the spoken " +
            "language or languages and code-switching, and never add unspoken content."
    private const val KEYWORDS_LABEL = "Confirmed keywords: "
    private const val PROFILE_LABEL = "Confirmed user context: "

    fun build(context: SttTranscriptionContext): String? {
        val keywords = context.confirmedGlossaryTerms
            .asSequence()
            .map(::sanitizeSingleLine)
            .filter(String::isNotBlank)
            .map { it.take(MAX_GLOSSARY_TERM_CHARACTERS).trim() }
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(MAX_GLOSSARY_TERMS)
            .toList()
        val profile = context.confirmedProfileSummary
            ?.let(::sanitizeSingleLine)
            ?.takeIf(String::isNotBlank)

        if (keywords.isEmpty() && profile == null) return null

        val prompt = StringBuilder(MAX_PROMPT_CHARACTERS)
        prompt.append(HEADER)
        if (keywords.isNotEmpty()) {
            appendSection(prompt, KEYWORDS_LABEL, keywords.joinToString(", "))
        }
        if (profile != null) {
            appendSection(prompt, PROFILE_LABEL, profile)
        }
        return takeUtf8Bytes(prompt.toString(), MAX_PROMPT_UTF8_BYTES).trimEnd()
    }

    private fun appendSection(target: StringBuilder, label: String, content: String) {
        val prefix = " $label"
        val room = MAX_PROMPT_UTF8_BYTES - target.toString().toByteArray(Charsets.UTF_8).size
        val prefixBytes = prefix.toByteArray(Charsets.UTF_8).size
        if (room <= prefixBytes) return
        target.append(prefix)
        val contentRoom = MAX_PROMPT_UTF8_BYTES -
            target.toString().toByteArray(Charsets.UTF_8).size
        target.append(takeUtf8Bytes(content, contentRoom))
    }

    internal fun takeUtf8Bytes(raw: String, maximumBytes: Int): String {
        require(maximumBytes >= 0)
        var index = 0
        var bytes = 0
        while (index < raw.length) {
            val codePoint = Character.codePointAt(raw, index)
            val characters = Character.charCount(codePoint)
            val encodedBytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (bytes + encodedBytes > maximumBytes) break
            bytes += encodedBytes
            index += characters
        }
        return raw.substring(0, index)
    }

    /** Collapses control/format characters so private context cannot reshape the protocol prompt. */
    internal fun sanitizeSingleLine(raw: String): String {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val cleaned = StringBuilder(normalized.length)
        normalized.forEach { character ->
            val type = Character.getType(character)
            when {
                character.isWhitespace() || character.isISOControl() -> cleaned.append(' ')
                type == Character.FORMAT.toInt() ||
                    type == Character.LINE_SEPARATOR.toInt() ||
                    type == Character.PARAGRAPH_SEPARATOR.toInt() -> cleaned.append(' ')
                else -> cleaned.append(character)
            }
        }
        return cleaned.toString().replace(Regex(" +"), " ").trim()
    }
}
