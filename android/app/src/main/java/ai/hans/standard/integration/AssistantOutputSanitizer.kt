package ai.hans.standard.integration

/**
 * A display/speech projection, never a replacement for the stored Codex transcript.
 * Callers must select assistant-authored text; user input and tool results are not inputs here.
 * The restricted notification announcement path still deliberately omits destinations. Ordinary
 * assistant chat and its speech use AssistantMarkdown instead so named links remain tappable.
 *
 * Web destinations disappear, while inline Markdown web-link labels remain readable. Local
 * paths, filenames, numbers and non-link prose retain their original characters. For a streaming
 * snapshot only a still-ambiguous suffix is withheld: neither a URL prefix nor half of link
 * markup may become a sentence that a later revision would have to retract from speech.
 *
 * Each invocation is O(text.length) time and space, with no regex backtracking, recursion, shared
 * mutable state, I/O or global payload truncation. Bracket pairing uses one integer per input
 * character; ordinary text without square brackets does not allocate that table.
 */
object AssistantOutputSanitizer {
    fun sanitize(text: String, complete: Boolean = true): String {
        if (text.isEmpty()) return text
        val limit = if (!complete && text.last().isHighSurrogate()) text.length - 1 else text.length
        val brackets = if (text.indexOf('[') >= 0) structureIndexes(text, limit) else null
        val output = Projection()
        var index = 0
        while (index < limit) {
            val char = text[index]
            if (char == '\\') {
                output.append(char)
                index += 1
                if (index < limit) output.append(text[index++])
                continue
            }
            if (char == ']' && brackets != null && brackets[index] > index) {
                // A recognized web link scheduled the removal of its destination. Its label
                // was processed normally, including nested links or bare addresses in the label.
                index = brackets[index]
                continue
            }
            val image = char == '!' && isTokenBoundary(text, index) &&
                index + 1 < limit && text[index + 1] == '['
            val labelStart = if (image) index + 1 else index
            if (text[labelStart] == '[' && brackets != null) {
                val labelEnd = brackets[labelStart]
                if (labelEnd < 0 || labelEnd + 1 == limit) {
                    if (!complete) break
                } else if (text[labelEnd + 1] == '(') {
                    val destination = skipWhitespace(text, labelEnd + 2, limit)
                    val webStart = if (destination < limit && text[destination] == '<') {
                        destination + 1
                    } else destination
                    when (prefixAt(text, webStart, limit, complete)) {
                        Prefix.PENDING -> break
                        Prefix.WEB -> {
                            brackets[labelEnd] = markdownDestinationEnd(
                                text, destination, webStart, limit, complete, brackets,
                            )
                            index = labelStart + 1
                            continue
                        }
                        Prefix.NONE -> Unit
                    }
                }
            }

            // Remove wrappers only when they enclose just a web address. This also prevents
            // orphan <>/()/backticks from being spoken, without stripping ordinary file markup.
            val closing = closingWrapper(char)
            if (closing != null && (index == 0 || text[index - 1] !in "/\\_")) {
                val start = skipWhitespace(text, index + 1, limit)
                when (prefixAt(text, start, limit, complete)) {
                    Prefix.PENDING -> break
                    Prefix.WEB -> {
                        val end = urlEnd(text, start, limit)
                        val after = skipWhitespace(text, end, limit)
                        if (after < limit && text[after] == closing) {
                            output.omitAddress()
                            index = after + 1
                            continue
                        }
                        if (after == limit && !complete) {
                            output.omitAddress()
                            break
                        }
                    }
                    Prefix.NONE -> Unit
                }
            }
            if (isTokenBoundary(text, index)) {
                when (prefixAt(text, index, limit, complete)) {
                    Prefix.PENDING -> break
                    Prefix.WEB -> {
                        val end = urlEnd(text, index, limit)
                        output.omitAddress()
                        if (end == limit && !complete) break
                        var punctuation = end
                        while (punctuation > index && text[punctuation - 1] in URL_TRAILING_PUNCTUATION) {
                            punctuation -= 1
                        }
                        while (punctuation < end) output.append(text[punctuation++])
                        index = end
                        continue
                    }
                    Prefix.NONE -> Unit
                }
            }
            // A standalone image marker can still become ![label](URL); an ordinary sentence's
            // final exclamation mark is not withheld.
            if (!complete && char == '!' && index + 1 == limit && isTokenBoundary(text, index)) break
            output.append(char)
            index += 1
        }
        return output.text()
    }

    private enum class Prefix { NONE, PENDING, WEB }

    private fun prefixAt(text: String, start: Int, limit: Int, complete: Boolean): Prefix {
        val available = limit - start
        if (available == 0) return if (complete) Prefix.NONE else Prefix.PENDING
        for (prefix in WEB_PREFIXES) {
            val count = minOf(available, prefix.length)
            if (!text.regionMatches(start, prefix, 0, count, ignoreCase = true)) continue
            if (available >= prefix.length) return Prefix.WEB
            if (!complete) return Prefix.PENDING
        }
        return Prefix.NONE
    }

    private fun isTokenBoundary(text: String, index: Int): Boolean {
        if (index == 0) return true
        val previous = text[index - 1]
        // In particular, do not reinterpret /tmp/www.example.txt or C:\\docs\\www.example.txt.
        return !previous.isLetterOrDigit() && previous !in "_./\\@-"
    }

    /**
     * Brackets use an unmatched-open linked stack. Quote positions store the next unescaped
     * same-kind quote, so even many malformed/nested link titles need no repeated suffix scans.
     * These entries are disjoint; no second table or recursive Markdown parser is necessary.
     */
    private fun structureIndexes(text: String, limit: Int): IntArray {
        val result = IntArray(limit) { -1 }
        var open = -1
        var doubleQuote = -1
        var singleQuote = -1
        var index = 0
        while (index < limit) {
            when (text[index]) {
                '\\' -> index += 1
                '[' -> {
                    result[index] = open
                    open = index
                }
                ']' -> if (open >= 0) {
                    val previous = result[open]
                    result[open] = index
                    open = previous
                }
                '"' -> {
                    if (doubleQuote >= 0) result[doubleQuote] = index
                    doubleQuote = index
                }
                '\'' -> {
                    if (singleQuote >= 0) result[singleQuote] = index
                    singleQuote = index
                }
            }
            index += 1
        }
        while (open >= 0) {
            val previous = result[open]
            result[open] = -1
            open = previous
        }
        return result
    }

    private fun urlEnd(text: String, start: Int, limit: Int): Int {
        var parentheses = 0
        var squares = 0
        var index = start
        while (index < limit) {
            val char = text[index]
            if (char.isWhitespace() || char in "<>`\"'\u201c\u201d\u201e\u2018\u2019\u00ab\u00bb\u2039\u203a") break
            when (char) {
                '\\' -> if (index + 1 < limit && text[index + 1] in "()[]") index += 1
                '(' -> parentheses += 1
                ')' -> if (parentheses == 0) break else parentheses -= 1
                '[' -> squares += 1
                ']' -> if (squares == 0) break else squares -= 1
                '}' -> break
            }
            index += 1
        }
        return index
    }

    private fun markdownDestinationEnd(
        text: String,
        destination: Int,
        webStart: Int,
        limit: Int,
        complete: Boolean,
        structure: IntArray,
    ): Int {
        val end = urlEnd(text, webStart, limit)
        var after = end
        if (destination != webStart && after < limit && text[after] == '>') after += 1
        after = skipWhitespace(text, after, limit)
        if (after == limit) return limit
        if (text[after] == ')') return after + 1
        if (after > end && text[after] in "\"'") {
            val quoteEnd = structure[after]
            if (quoteEnd >= 0) {
                val close = skipWhitespace(text, quoteEnd + 1, limit)
                if (close < limit && text[close] == ')') return close + 1
                if (close == limit && !complete) return limit
            } else if (!complete) return limit
        }
        // A malformed link may be followed by real prose. Remove its address, not all the
        // remaining sentences merely because a closing parenthesis was never delivered.
        return end
    }

    private fun skipWhitespace(text: String, start: Int, limit: Int): Int {
        var index = start
        while (index < limit && text[index].isWhitespace()) index += 1
        return index
    }

    private fun closingWrapper(char: Char): Char? = when (char) {
        '(' -> ')'
        '[' -> ']'
        '<' -> '>'
        '`', '\'', '"' -> char
        '\u201c' -> '\u201d'
        '\u201e' -> '\u201c'
        '\u2018' -> '\u2019'
        '\u00ab' -> '\u00bb'
        '\u2039' -> '\u203a'
        else -> null
    }

    private class Projection {
        private val output = StringBuilder()
        private var afterAddress = false
        private var pendingSpace = false
        private var lineHasWords = false

        fun omitAddress() {
            while (output.isNotEmpty() && output.last().isWhitespace() && output.last() !in "\r\n") {
                output.setLength(output.length - 1)
                pendingSpace = true
            }
            afterAddress = true
        }

        fun append(char: Char) {
            if (afterAddress) {
                if (char.isWhitespace() && char !in "\r\n") {
                    pendingSpace = true
                    return
                }
                if (char in CLOSING_PUNCTUATION &&
                    (!lineHasWords || char in SENTENCE_ENDINGS && output.isNotEmpty() &&
                        output.last() in SENTENCE_ENDINGS)
                ) return
                if (pendingSpace && output.isNotEmpty() && !output.last().isWhitespace() &&
                    char !in CLOSING_PUNCTUATION && char !in "\r\n"
                ) output.append(' ')
                afterAddress = false
                pendingSpace = false
            }
            output.append(char)
            if (char in "\r\n") lineHasWords = false
            else if (!char.isWhitespace() && char !in CLOSING_PUNCTUATION && char !in "([{<`\"'") {
                lineHasWords = true
            }
        }

        fun text(): String = output.toString()
    }

    private val WEB_PREFIXES = arrayOf("http://", "https://", "www.")
    private const val URL_TRAILING_PUNCTUATION = ".,;:!?\u2026"
    private const val SENTENCE_ENDINGS = ".!?\u2026"
    private const val CLOSING_PUNCTUATION = ".,;:!?\u2026)]}>`\"'\u201c\u201d\u201e\u2018\u2019\u00ab\u00bb\u2039\u203a"
}
