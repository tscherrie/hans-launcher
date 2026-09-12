package ai.hans.standard.text

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A deliberately small, offline Markdown dialect for assistant-authored text, never stored data. */
data class AssistantMarkdownDocument(
    val blocks: List<AssistantMarkdownBlock>,
    /** Selectable display text, including useful list markers but without formatting syntax. */
    val plainText: String,
    /** A stable, speakable prefix; an ambiguous streaming suffix is not ready for speech yet. */
    val spokenText: String,
)

enum class AssistantMarkdownBlockKind { PARAGRAPH, HEADING, LIST_ITEM, QUOTE, CODE }

data class AssistantMarkdownBlock(
    val kind: AssistantMarkdownBlockKind,
    val runs: List<AssistantMarkdownRun>,
    val headingLevel: Int = 0,
    val listMarker: String? = null,
    val codeLanguage: String? = null,
) {
    val text: String get() = runs.joinToString("") { it.text }
}

data class AssistantMarkdownRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    /** Already canonicalized, credential-free http(s), or null. Never an intent/file/data URI. */
    val url: String? = null,
)

/**
 * Paragraphs, ATX headings, flat lists, block quotes, fenced/inline code, emphasis and web links.
 * HTML and unsupported constructs remain text; images are alt text, never fetched. There is no
 * WebView, I/O, recursive parse, regular-expression backtracking or global text-size truncation.
 * Inline scans consume the ranges they inspect and delimiter/bracket matching is linear.
 *
 * Both projections come from the same tokens. Only speech withholds potentially unfinished
 * markup, a web address, a block prefix or a trailing UTF-16 high surrogate. Consequently a later
 * snapshot does not retract a label/delimiter that an earlier snapshot already sent to speech.
 */
object AssistantMarkdown {
    fun parse(raw: String, complete: Boolean = true): AssistantMarkdownDocument {
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        val source = if (!complete && normalized.lastOrNull()?.isHighSurrogate() == true) {
            normalized.dropLast(1)
        } else normalized
        if (source.isEmpty()) return AssistantMarkdownDocument(emptyList(), "", "")
        val blocks = ArrayList<AssistantMarkdownBlock>()
        val speech = ArrayList<String>()
        var speechBlocked = false
        for (block in sourceBlocks(source, complete)) {
            val parsed = if (block.kind == AssistantMarkdownBlockKind.CODE) {
                val stableEnd = if (block.pendingCodeLine >= 0) block.pendingCodeLine else block.text.length
                val spoken = codeSpeech(block.text.substring(0, stableEnd), block.complete)
                InlineResult(
                    listOf(AssistantMarkdownRun(block.text, code = true)),
                    spoken.first,
                    block.pendingCodeLine >= 0 || spoken.second,
                )
            } else parseInline(block.text, block.complete, block.pendingBlockPrefix)
            blocks += AssistantMarkdownBlock(
                kind = block.kind,
                runs = parsed.runs,
                headingLevel = block.headingLevel,
                listMarker = block.listMarker,
                codeLanguage = block.codeLanguage,
            )
            if (!speechBlocked) {
                val number = block.listMarker?.takeIf { it.firstOrNull()?.isDigit() == true }
                speech += if (number != null && parsed.spokenText.isNotEmpty()) {
                    "$number ${parsed.spokenText}"
                } else parsed.spokenText
                speechBlocked = parsed.pending
            }
        }
        return AssistantMarkdownDocument(
            blocks = blocks,
            plainText = blocks.joinToString("\n\n") {
                (it.listMarker?.let { marker -> "$marker " } ?: "") + it.text
            },
            spokenText = speech.filter { it.isNotEmpty() }.joinToString("\n\n").trimEnd(),
        )
    }

    /** Used again at the click boundary, including when a caller constructs a run manually. */
    fun safeWebUrl(raw: String): String? = webLink(raw)?.url

    /** Only opaque Hans-issued file receipts, never raw filesystem/content/intent URLs. */
    fun safeFileUrl(raw: String): String? = raw.takeIf {
        it.matches(Regex("hansfile://(?:open|share)/[0-9a-f]{32}"))
    }

    fun safeLinkUrl(raw: String): String? = safeFileUrl(raw) ?: safeWebUrl(raw)

    private data class WebLink(val url: String, val label: String)

    private fun webLink(raw: String): WebLink? {
        if (raw.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f || it == '\\' }) return null
        val candidate = when {
            raw.startsWith("https://", true) || raw.startsWith("http://", true) -> raw
            raw.startsWith("www.", true) -> "https://$raw"
            else -> return null
        }
        val url = candidate.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        val authority = candidate.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        if ('@' in authority) return null
        return WebLink(url.toString(), url.host.removePrefix("www.").ifEmpty { url.host })
    }

    private data class SourceBlock(
        val kind: AssistantMarkdownBlockKind,
        val text: String,
        val complete: Boolean,
        val headingLevel: Int = 0,
        val listMarker: String? = null,
        val codeLanguage: String? = null,
        val pendingBlockPrefix: Int = -1,
        val pendingCodeLine: Int = -1,
    )

    private data class LinePrefix(
        val kind: AssistantMarkdownBlockKind,
        val end: Int,
        val headingLevel: Int = 0,
        val marker: String? = null,
    )

    private data class Fence(val character: Char, val length: Int, val info: String)

    private fun sourceBlocks(source: String, complete: Boolean): List<SourceBlock> {
        val lines = source.split('\n')
        val result = ArrayList<SourceBlock>()
        val paragraph = StringBuilder()
        var paragraphLastLine = 0
        fun flushParagraph(closed: Boolean) {
            if (paragraph.isEmpty()) return
            val value = paragraph.toString()
            val pending = if (!closed && possibleBlockPrefix(value.substring(paragraphLastLine))) {
                paragraphLastLine
            } else -1
            result += SourceBlock(AssistantMarkdownBlockKind.PARAGRAPH, value, closed,
                pendingBlockPrefix = pending)
            paragraph.setLength(0)
            paragraphLastLine = 0
        }
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val lineComplete = complete || index < lines.lastIndex
            if (line.isBlank() && lineComplete) {
                flushParagraph(true)
                index += 1
                continue
            }
            val openingFence = fence(line)
            if (openingFence != null) {
                // A streaming info line may still acquire a backtick and cease to be a
                // fence. Do not finalize the preceding paragraph's ambiguous inline text
                // until a newline (or final message) makes this block boundary stable.
                flushParagraph(lineComplete)
                val content = StringBuilder()
                var lastLineStart = -1
                var hasContentLine = false
                index += 1
                var closed = false
                while (index < lines.size) {
                    val candidate = lines[index]
                    if (closesFence(candidate, openingFence)) {
                        closed = true
                        index += 1
                        break
                    }
                    if (hasContentLine) content.append('\n')
                    hasContentLine = true
                    lastLineStart = content.length
                    content.append(candidate)
                    index += 1
                }
                val pendingLine = if (!complete && !closed && lastLineStart >= 0 &&
                    possibleFenceClose(content.substring(lastLineStart), openingFence)) lastLineStart else -1
                result += SourceBlock(
                    AssistantMarkdownBlockKind.CODE,
                    content.toString(),
                    complete || closed,
                    codeLanguage = openingFence.info.takeIf { it.isNotEmpty() },
                    pendingCodeLine = pendingLine,
                )
                continue
            }
            val prefix = linePrefix(line)
            if (prefix != null) {
                flushParagraph(true)
                result += SourceBlock(prefix.kind, line.substring(prefix.end), lineComplete,
                    headingLevel = prefix.headingLevel, listMarker = prefix.marker)
            } else {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraphLastLine = paragraph.length
                paragraph.append(line)
            }
            index += 1
        }
        flushParagraph(complete)
        return result
    }

    private fun indentEnd(line: String): Int {
        var index = 0
        while (index < line.length && index < 3 && line[index] == ' ') index += 1
        return index
    }

    private fun linePrefix(line: String): LinePrefix? {
        val start = indentEnd(line)
        if (start == line.length) return null
        var end = start
        while (end < line.length && line[end] == '#') end += 1
        if (end - start in 1..6 && end < line.length && line[end].isWhitespace()) {
            return LinePrefix(AssistantMarkdownBlockKind.HEADING, skipSpaces(line, end), end - start)
        }
        if (line[start] == '>' && start + 1 < line.length && line[start + 1].isWhitespace()) {
            return LinePrefix(AssistantMarkdownBlockKind.QUOTE, skipSpaces(line, start + 1))
        }
        if (line[start] in "-+*" && start + 1 < line.length && line[start + 1].isWhitespace()) {
            return LinePrefix(AssistantMarkdownBlockKind.LIST_ITEM, skipSpaces(line, start + 1), marker = "•")
        }
        end = start
        while (end < line.length && end - start < 9 && line[end].isDigit()) end += 1
        if (end > start && end + 1 < line.length && line[end] in ".)" && line[end + 1].isWhitespace()) {
            return LinePrefix(AssistantMarkdownBlockKind.LIST_ITEM, skipSpaces(line, end + 1),
                marker = line.substring(start, end) + ".")
        }
        return null
    }

    private fun possibleBlockPrefix(line: String): Boolean {
        val value = line.substring(indentEnd(line))
        if (value.isEmpty()) return true
        if (value in listOf("-", "+", "*", ">", "~", "~~")) return true
        if (value.length <= 6 && value.all { it == '#' }) return true
        val digits = value.takeWhile { it.isDigit() }
        return digits.length in 1..9 && (value == digits || value == "$digits." || value == "$digits)")
    }

    private fun fence(line: String): Fence? {
        val start = indentEnd(line)
        if (start == line.length || line[start] !in "`~") return null
        var end = start
        while (end < line.length && line[end] == line[start]) end += 1
        if (end - start < 3) return null
        val info = line.substring(end).trim()
        if (line[start] == '`' && '`' in info) return null
        return Fence(line[start], end - start, info)
    }

    private fun closesFence(line: String, opening: Fence): Boolean {
        val start = indentEnd(line)
        var end = start
        while (end < line.length && line[end] == opening.character) end += 1
        return end - start >= opening.length && line.substring(end).isBlank()
    }

    private fun possibleFenceClose(line: String, opening: Fence): Boolean {
        val value = line.substring(indentEnd(line))
        return value.isNotEmpty() && value.all { it == opening.character || it.isWhitespace() }
    }

    private enum class TokenKind { TEXT, MARKER }
    private data class Token(
        val text: String,
        val source: Int,
        val kind: TokenKind = TokenKind.TEXT,
        val code: Boolean = false,
        val url: String? = null,
        val spoken: String? = null,
        var opens: Boolean = false,
        var closes: Boolean = false,
        var matched: Boolean = false,
    )
    private data class InlineResult(
        val runs: List<AssistantMarkdownRun>,
        val spokenText: String,
        val pending: Boolean,
    )
    private data class LinkScan(val end: Int, val destination: String?, val pending: Boolean)

    private fun parseInline(text: String, complete: Boolean, pendingPrefix: Int = -1): InlineResult {
        val tokens = ArrayList<Token>()
        val codePairs = codePairs(text)
        val brackets = bracketPairs(text, codePairs, complete)
        val markerStacks = HashMap<String, ArrayDeque<Int>>()
        var pendingAt = if (pendingPrefix >= 0) pendingPrefix else Int.MAX_VALUE
        var index = 0
        var activeLink: WebLink? = null
        var labelEnd = -1
        var linkEnd = -1
        fun append(value: String, at: Int, code: Boolean = false, url: String? = activeLink?.url) {
            if (value.isNotEmpty()) tokens += Token(value, at, code = code, url = url,
                spoken = if (code) codeSpeech(value, true).first else null)
        }
        while (index < text.length) {
            if (index == labelEnd) {
                index = linkEnd
                labelEnd = -1
                linkEnd = -1
                activeLink = null
                continue
            }
            val char = text[index]
            if (char == '\\') {
                if (index + 1 == text.length && !complete) pendingAt = minOf(pendingAt, index)
                if (index + 1 < text.length && text[index + 1] in ESCAPABLE) {
                    append(text.substring(index + 1, index + 2), index)
                    index += 2
                } else {
                    append(text.substring(index, index + 1), index)
                    index += 1
                }
                continue
            }
            if (char == '`') {
                var end = index
                while (end < text.length && text[end] == '`') end += 1
                val close = codePairs[index]
                if (close >= end && (labelEnd < 0 || close < labelEnd)) {
                    // Display a currently closed code span immediately, but the terminal run
                    // could still grow in the next snapshot. Speech cannot commit it yet.
                    if (!complete && close + end - index == text.length) pendingAt = minOf(pendingAt, index)
                    append(text.substring(end, close), index, code = true)
                    index = close + end - index
                } else {
                    if (!complete) pendingAt = minOf(pendingAt, index)
                    append(text.substring(index, end), index)
                    index = end
                }
                continue
            }
            val image = char == '!' && index + 1 < text.length && text[index + 1] == '['
            val bracketStart = if (image) index + 1 else index
            if (labelEnd < 0 && text[bracketStart] == '[') {
                val close = brackets[bracketStart]
                if (close < 0 || close + 1 == text.length) {
                    if (!complete) pendingAt = minOf(pendingAt, index)
                } else if (text[close + 1] == '(') {
                    val scan = scanLink(text, close + 1, complete)
                    val link = scan.destination?.let { destination ->
                        safeFileUrl(destination)?.let { WebLink(it, "Datei") } ?: webLink(destination)
                    }
                    if (scan.pending) pendingAt = minOf(pendingAt, index)
                    if (link != null || scan.pending) {
                        val label = text.substring(bracketStart + 1, close)
                        val visibleLink = link ?: WebLink("", "Link")
                        val rawLabelLink = webLink(label.trim())
                        if (label.isBlank() || rawLabelLink != null) {
                            append(rawLabelLink?.label ?: visibleLink.label, index,
                                url = if (image) null else link?.url)
                            index = scan.end
                        } else {
                            activeLink = if (image || link == null) null else link
                            labelEnd = close
                            linkEnd = scan.end
                            index = bracketStart + 1
                        }
                        continue
                    }
                    // An invalid destination remains literal and inert. Consume its scanned
                    // range once: many nested malformed candidates cannot rescan a suffix.
                    append(text.substring(index, scan.end), index, url = null)
                    index = scan.end
                    continue
                }
            }
            if (char == '<') {
                val end = text.indexOf('>', index + 1)
                if (end >= 0) {
                    val inner = text.substring(index + 1, end)
                    val link = webLink(inner)
                    if (link != null) append(link.label, index, url = activeLink?.url ?: link.url)
                    else append(text.substring(index, end + 1), index)
                    index = end + 1
                    continue
                }
                if (!complete) pendingAt = minOf(pendingAt, index)
                // No closing angle exists. Literal fallback consumes the inspected tail.
                append(text.substring(index), index)
                break
            }
            val afterMarker = tokens.lastOrNull()?.let {
                it.kind == TokenKind.MARKER && it.source + it.text.length == index
            } == true
            if (tokenBoundary(text, index) || afterMarker) {
                val prefix = webPrefix(text, index)
                if (prefix > 0) {
                    val underscoreClosers = markerStacks.filter { it.key[0] == '_' && it.value.isNotEmpty() }
                        .keys.mapTo(HashSet()) { it.length }
                    val end = urlEnd(text, index, labelEnd, underscoreClosers)
                    val isPending = !complete && end == text.length
                    if (isPending) pendingAt = minOf(pendingAt, index)
                    var addressEnd = end
                    while (addressEnd > index && text[addressEnd - 1] in TRAILING_URL_PUNCTUATION) addressEnd -= 1
                    val link = webLink(text.substring(index, addressEnd))
                    if (link != null) {
                        append(link.label, index, url = if (isPending) null else activeLink?.url ?: link.url)
                        append(text.substring(addressEnd, end), addressEnd)
                    } else append(if (isPending) "Link" else text.substring(index, end), index)
                    index = end
                    continue
                }
                if (prefix < 0 && !complete) pendingAt = minOf(pendingAt, index)
            }
            if (char == '*' || char == '_') {
                var end = index
                while (end < text.length && text[end] == char) end += 1
                val marker = text.substring(index, end)
                val before = text.getOrNull(index - 1)
                val after = text.getOrNull(end)
                val intraword = char == '_' && before?.isLetterOrDigit() == true && after?.isLetterOrDigit() == true
                if (marker.length <= 3 && !intraword) {
                    val token = Token(marker, index, TokenKind.MARKER, url = activeLink?.url)
                    val tokenIndex = tokens.size
                    tokens += token
                    val terminal = after == null && !complete
                    if (terminal) pendingAt = minOf(pendingAt, index)
                    val canClose = before != null && !before.isWhitespace() &&
                        (char != '_' || after?.isLetterOrDigit() != true)
                    val canOpen = after != null && !after.isWhitespace() &&
                        (char != '_' || before?.isLetterOrDigit() != true)
                    val stack = markerStacks.getOrPut(marker) { ArrayDeque() }
                    if (canClose && stack.isNotEmpty()) {
                        val opening = tokens[stack.removeLast()]
                        opening.apply { matched = true; opens = true }
                        token.apply { matched = true; closes = true }
                        if (terminal) pendingAt = minOf(pendingAt, opening.source)
                    } else if (canClose && marker.length == 3 &&
                        markerStacks[marker.take(1)]?.isNotEmpty() == true &&
                        markerStacks[marker.take(2)]?.isNotEmpty() == true) {
                        // The ordinary nested forms **bold *italic*** and *italic **bold***
                        // share one closing run. This is only a paired 1+2 close, not a
                        // general CommonMark delimiter redistribution algorithm.
                        val single = tokens[markerStacks.getValue(marker.take(1)).removeLast()]
                        val double = tokens[markerStacks.getValue(marker.take(2)).removeLast()]
                        single.apply { matched = true; opens = true }
                        double.apply { matched = true; opens = true }
                        token.apply { matched = true; closes = true }
                        if (terminal) pendingAt = minOf(pendingAt, single.source, double.source)
                    } else if (canOpen) stack.addLast(tokenIndex)
                } else {
                    if (!complete && end == text.length) pendingAt = minOf(pendingAt, index)
                    append(marker, index)
                }
                index = end
                continue
            }
            if (char == '!' && index + 1 == text.length && !complete && tokenBoundary(text, index)) {
                pendingAt = minOf(pendingAt, index)
            }
            // Collect ordinary runs rather than allocating an object for every text character.
            val start = index++
            while (index < text.length && index != labelEnd && text[index] !in SPECIAL &&
                !(tokenBoundary(text, index) && webPrefix(text, index) != 0)) index += 1
            append(text.substring(start, index), start)
        }
        if (!complete) {
            markerStacks.values.forEach { stack ->
                stack.forEach { pendingAt = minOf(pendingAt, tokens[it].source) }
            }
        }
        var bold = 0
        var italic = 0
        val runs = ArrayList<AssistantMarkdownRun>()
        val speech = StringBuilder()
        tokens.forEach { token ->
            if (token.kind == TokenKind.MARKER && token.matched) {
                val delta = if (token.opens) 1 else -1
                if (token.text.length >= 2) bold += delta
                if (token.text.length != 2) italic += delta
            } else {
                val run = AssistantMarkdownRun(token.text, bold > 0, italic > 0, token.code, token.url)
                runs += run
                if (token.source < pendingAt) {
                    val spokenValue = token.spoken ?: token.text
                    // A block-prefix boundary can fall inside an otherwise ordinary token.
                    val count = if (pendingAt < token.source + token.text.length &&
                        token.kind == TokenKind.TEXT && !token.code && token.url == null) {
                        pendingAt - token.source
                    } else spokenValue.length
                    speech.append(spokenValue, 0, count)
                }
            }
        }
        return InlineResult(runs, speech.toString(), pendingAt != Int.MAX_VALUE)
    }

    /** Code remains visibly literal; only its spoken web addresses use the same safe host labels. */
    private fun codeSpeech(text: String, complete: Boolean): Pair<String, Boolean> {
        val output = StringBuilder()
        var index = 0
        while (index < text.length) {
            if (tokenBoundary(text, index)) {
                val prefix = webPrefix(text, index)
                if (prefix < 0 && !complete) return output.toString() to true
                if (prefix > 0) {
                    val end = urlEnd(text, index, -1)
                    if (!complete && end == text.length) return output.toString() to true
                    var addressEnd = end
                    while (addressEnd > index && text[addressEnd - 1] in TRAILING_URL_PUNCTUATION) addressEnd -= 1
                    val link = webLink(text.substring(index, addressEnd))
                    output.append(link?.label ?: text.substring(index, addressEnd))
                    output.append(text, addressEnd, end)
                    index = end
                    continue
                }
            }
            output.append(text[index++])
        }
        return output.toString() to false
    }

    private fun bracketPairs(text: String, codePairs: IntArray, complete: Boolean): IntArray {
        val result = IntArray(text.length) { -1 }
        val stack = ArrayDeque<Int>()
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '\\' -> if (index + 1 < text.length && text[index + 1] in ESCAPABLE) index += 1
                '`' -> {
                    val start = index
                    while (index < text.length && text[index] == '`') index += 1
                    val close = codePairs[start]
                    // Code punctuation is literal, including brackets inside a link label.
                    // Reuse the linear code index instead of searching the suffix again.
                    if (close >= index) index = close + index - start
                    // An unfinished code span may still absorb every later bracket. Keep an
                    // enclosing label unresolved until its interpretation becomes stable.
                    else if (!complete) break
                    continue
                }
                '[' -> stack.addLast(index)
                ']' -> if (stack.isNotEmpty()) result[stack.removeLast()] = index
            }
            index += 1
        }
        return result
    }

    private fun codePairs(text: String): IntArray {
        val result = IntArray(text.length) { -1 }
        val runs = ArrayList<Pair<Int, Int>>()
        var index = 0
        while (index < text.length) {
            if (text[index] == '\\' && index + 1 < text.length && text[index + 1] in ESCAPABLE) {
                index += 2
            } else if (text[index] == '`') {
                val start = index
                while (index < text.length && text[index] == '`') index += 1
                runs += start to index - start
            } else index += 1
        }
        val next = HashMap<Int, Int>()
        for ((start, length) in runs.asReversed()) {
            result[start] = next[length] ?: -1
            next[length] = start
        }
        return result
    }

    private fun scanLink(text: String, opening: Int, complete: Boolean): LinkScan {
        var index = opening + 1
        var depth = 1
        var quote: Char? = null
        var angled = false
        while (index < text.length) {
            val char = text[index]
            if (char == '\\' && index + 1 < text.length && text[index + 1] in ESCAPABLE) {
                index += 2
                continue
            }
            if (quote != null) {
                if (char == quote) quote = null
            } else if (char in "\"'" && text.getOrNull(index - 1)?.isWhitespace() == true) {
                quote = char
            } else if (char == '<') angled = true
            else if (char == '>') angled = false
            else if (!angled && char == '(') depth += 1
            else if (!angled && char == ')') {
                depth -= 1
                if (depth == 0) {
                    return LinkScan(index + 1, destination(text.substring(opening + 1, index)), false)
                }
            }
            index += 1
        }
        return LinkScan(index, null, !complete)
    }

    private fun destination(source: String): String? {
        val value = source.trim()
        if (value.isEmpty()) return null
        val end = if (value[0] == '<') value.indexOf('>') else value.indexOfFirst { it.isWhitespace() }
        val address: String
        val rest: String
        if (value[0] == '<') {
            if (end < 0) return null
            address = value.substring(1, end)
            rest = value.substring(end + 1).trim()
        } else if (end >= 0) {
            address = value.substring(0, end)
            rest = value.substring(end).trim()
        } else {
            address = value
            rest = ""
        }
        if (rest.isNotEmpty() && (rest.length < 2 || rest.first() !in "\"'" || rest.last() != rest.first())) {
            return null
        }
        val unescaped = StringBuilder()
        var index = 0
        while (index < address.length) {
            if (address[index] == '\\' && index + 1 < address.length && address[index + 1] in ESCAPABLE) index += 1
            unescaped.append(address[index++])
        }
        return unescaped.toString()
    }

    /** Positive: known web prefix; negative: possibly the start of one at end of snapshot. */
    private fun webPrefix(text: String, start: Int): Int {
        for (prefix in WEB_PREFIXES) {
            val available = text.length - start
            val count = minOf(available, prefix.length)
            if (text.regionMatches(start, prefix, 0, count, true)) return if (available >= prefix.length) 1 else -1
        }
        return 0
    }

    private fun tokenBoundary(text: String, index: Int): Boolean = index == 0 ||
        (!text[index - 1].isLetterOrDigit() && text[index - 1] !in "_./\\@-")

    private fun urlEnd(text: String, start: Int, labelEnd: Int, underscoreClosers: Set<Int> = emptySet()): Int {
        var depth = 0
        var index = start
        while (index < text.length && index != labelEnd) {
            val char = text[index]
            if (char.isWhitespace() || char in "<>`\"'[]{}*\u201c\u201d\u201e\u2018\u2019\u00ab\u00bb") break
            if (char == '_' && underscoreClosers.isNotEmpty()) {
                var end = index
                while (end < text.length && text[end] == '_') end += 1
                if (end - index in underscoreClosers && text.getOrNull(end)?.isLetterOrDigit() != true) break
                index = end
                continue
            }
            if (char == '(') depth += 1
            if (char == ')') { if (depth == 0) break else depth -= 1 }
            index += 1
        }
        return index
    }

    private fun skipSpaces(text: String, start: Int): Int {
        var index = start
        while (index < text.length && text[index].isWhitespace()) index += 1
        return index
    }

    private val WEB_PREFIXES = listOf("https://", "http://", "www.")
    private const val TRAILING_URL_PUNCTUATION = ".,;:!?"
    private const val SPECIAL = "\\`[!*_<"
    private const val ESCAPABLE = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
}
