package ai.hans.standard.voice.tts

internal data class SentenceSpan(
    val sourceStart: Int,
    val sourceEndExclusive: Int,
    val text: String,
) {
    init {
        require(sourceStart >= 0)
        require(sourceEndExclusive > sourceStart)
        require(text.isNotBlank())
    }
}

/**
 * Conservative sentence segmentation for streamed prose. A punctuation mark
 * at the end of any snapshot is immediately speakable; [isFinal] additionally
 * flushes a trailing fragment without sentence punctuation.
 */
internal class NaturalSentenceSegmenter {
    fun segment(
        text: String,
        fromIndex: Int = 0,
        isFinal: Boolean,
    ): List<SentenceSpan> {
        require(fromIndex in 0..text.length)
        if (fromIndex == text.length) return emptyList()

        val result = mutableListOf<SentenceSpan>()
        var cursor = fromIndex
        while (cursor < text.length) {
            cursor = skipWhitespace(text, cursor)
            if (cursor >= text.length) break
            val sentenceStart = cursor
            var index = cursor
            var boundaryFound = false

            while (index < text.length) {
                val char = text[index]
                if (char == '\n' || char == '\r') {
                    appendTrimmed(text, sentenceStart, index, result)
                    cursor = skipLineBreaks(text, index)
                    boundaryFound = true
                    break
                }

                if (char in SENTENCE_PUNCTUATION && isSentencePunctuation(text, index)) {
                    val boundaryEnd = consumePunctuationAndClosers(text, index)
                    val followedByWhitespace =
                        boundaryEnd < text.length && text[boundaryEnd].isWhitespace()
                    val canFlushAtEnd = boundaryEnd == text.length
                    if (followedByWhitespace || canFlushAtEnd) {
                        appendTrimmed(text, sentenceStart, boundaryEnd, result)
                        cursor = boundaryEnd
                        boundaryFound = true
                        break
                    }
                    index = boundaryEnd
                    continue
                }
                index += 1
            }

            if (!boundaryFound) {
                if (isFinal) appendTrimmed(text, sentenceStart, text.length, result)
                break
            }
        }
        return result
    }

    private fun isSentencePunctuation(text: String, index: Int): Boolean {
        val char = text[index]
        if (char != '.') return true
        val previous = text.getOrNull(index - 1)
        val next = text.getOrNull(index + 1)
        if (previous?.isDigit() == true && next?.isDigit() == true) return false
        return !isAbbreviation(text, index)
    }

    private fun isAbbreviation(text: String, periodIndex: Int): Boolean {
        var start = periodIndex - 1
        while (start >= 0 && (text[start].isLetter() || text[start] == '.')) start -= 1
        val token = text.substring(start + 1, periodIndex + 1).lowercase()
        if (token in COMMON_ABBREVIATIONS) return true
        val letters = token.count(Char::isLetter)
        return letters == 1
    }

    private fun consumePunctuationAndClosers(text: String, start: Int): Int {
        var index = start
        while (index < text.length && text[index] in SENTENCE_PUNCTUATION) index += 1
        while (index < text.length && text[index] in CLOSING_CHARACTERS) index += 1
        return index
    }

    private fun appendTrimmed(
        text: String,
        start: Int,
        endExclusive: Int,
        result: MutableList<SentenceSpan>,
    ) {
        var trimmedStart = start
        var trimmedEnd = endExclusive
        while (trimmedStart < trimmedEnd && text[trimmedStart].isWhitespace()) trimmedStart += 1
        while (trimmedEnd > trimmedStart && text[trimmedEnd - 1].isWhitespace()) trimmedEnd -= 1
        if (trimmedStart == trimmedEnd) return
        result += SentenceSpan(
            sourceStart = trimmedStart,
            sourceEndExclusive = trimmedEnd,
            text = text.substring(trimmedStart, trimmedEnd),
        )
    }

    private fun skipWhitespace(text: String, start: Int): Int {
        var index = start
        while (index < text.length && text[index].isWhitespace()) index += 1
        return index
    }

    private fun skipLineBreaks(text: String, start: Int): Int {
        var index = start
        while (index < text.length && (text[index] == '\n' || text[index] == '\r')) index += 1
        return index
    }

    private companion object {
        val SENTENCE_PUNCTUATION = setOf('.', '!', '?', '\u2026')
        val CLOSING_CHARACTERS = setOf(
            '"',
            '\'',
            '\u2019',
            '\u201c',
            '\u201d',
            ')',
            ']',
            '}',
        )
        val COMMON_ABBREVIATIONS = setOf(
            "dr.",
            "mr.",
            "mrs.",
            "ms.",
            "prof.",
            "sr.",
            "jr.",
            "bzw.",
            "ca.",
            "d.h.",
            "e.g.",
            "etc.",
            "i.e.",
            "nr.",
            "u.a.",
            "vgl.",
            "vs.",
            "z.b.",
        )
    }
}
