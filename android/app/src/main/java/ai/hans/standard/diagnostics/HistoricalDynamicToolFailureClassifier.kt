package ai.hans.standard.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Best-effort metadata from an already received native dynamic-tool item. Neither parsing a
 * failure nor an unknown shape may restart a session or recover/replay any tool action. Only
 * fixed enums survive; arguments, text, native errors, identifiers and media are never retained.
 * Missing historical detail stays missing: local-only diagnostics cannot be reconstructed.
 */
object HistoricalDynamicToolFailureClassifier {
    const val MAX_TEXT_BYTES = 16_384
    const val MAX_CONTENT_ITEMS = 8
    private val unknown = ToolFailureDiagnostic(ToolFailureCode.UNKNOWN)

    fun classify(item: JSONObject): ToolFailureDiagnostic? {
        if (item.opt("type") != "dynamicToolCall") return null
        val status = item.opt("status")
        val success = item.opt("success")
        val failed = status == "failed" || (status == "completed" && success == false)
        if (!failed) return null
        // Contradictory or mistyped optional evidence must not produce a confident category.
        if (success != null && success !== JSONObject.NULL && success !is Boolean) return unknown
        if (status == "failed" && success == true) return unknown
        val content = item.opt("contentItems") as? JSONArray ?: return unknown
        if (content.length() !in 1..MAX_CONTENT_ITEMS) return unknown
        var text: String? = null
        repeat(content.length()) { index ->
            val part = content.opt(index) as? JSONObject ?: return unknown
            when (part.opt("type")) {
                "inputText" -> {
                    // Our result contract emits one text envelope. Multiple text results are
                    // ambiguous, even if one happens to contain a familiar-looking failure.
                    if (text != null) return unknown
                    text = part.opt("text") as? String ?: return unknown
                }
                "inputImage", "inputAudio" -> Unit // Do not inspect or copy media payloads.
                else -> return unknown
            }
        }
        val raw = text ?: return unknown
        if (raw.length > MAX_TEXT_BYTES || raw.toByteArray(StandardCharsets.UTF_8).size > MAX_TEXT_BYTES) {
            return unknown
        }
        return try {
            if (!StrictDiagnosticJson(raw).accepts()) unknown
            else classifyEnvelope(JSONObject(raw), 0)
        } catch (_: Exception) {
            // The classifier is optional: never expose the exception or fail protocol parsing.
            unknown
        }
    }

    private fun classifyEnvelope(value: JSONObject, wrapperDepth: Int): ToolFailureDiagnostic {
        if (wrapperDepth > 3) return unknown
        val error = value.opt("errorCode")
        val code = value.opt("code")
        val hasError = error != null && error !== JSONObject.NULL
        val hasCode = code != null && code !== JSONObject.NULL
        if (hasError && error !is String || hasCode && code !is String) return unknown
        if (hasError && hasCode && error != code) return unknown
        val name = (if (hasError) error else code) as? String
        if (name == null) {
            // execute_steps wraps the failing receipt, not the preceding successful receipts.
            val child = value.opt("failure") as? JSONObject ?: return unknown
            return classifyEnvelope(child, wrapperDepth + 1)
        }
        val classified = ToolFailureCode.fromCode(name)
        if (classified == ToolFailureCode.UNKNOWN) return unknown
        val direct = value.opt("detailCode")
        val postcondition = (value.opt("postcondition") as? JSONObject)?.opt("detailCode")
        val hasDirect = direct != null && direct !== JSONObject.NULL
        val hasPostcondition = postcondition != null && postcondition !== JSONObject.NULL
        if (hasDirect && direct !is String || hasPostcondition && postcondition !is String) return unknown
        if (hasDirect && hasPostcondition && direct != postcondition) return unknown
        val detail = (if (hasDirect) direct else postcondition) as? String
        return ToolFailureDiagnostic(classified, detail?.let(ToolFailureDetail::fromCode))
    }
}

/**
 * Validate before JSONObject: Android's parser is lenient and allocates nested objects first.
 * Callers must bound input bytes before construction. Larger native-history budgets are explicit
 * and cannot exceed these hard limits; ordinary failure envelopes retain their smaller defaults.
 */
internal class StrictDiagnosticJson(
    private val input: String,
    private val maxDepth: Int = 12,
    private val maxValues: Int = 2_048,
) {
    init {
        require(maxDepth in 1..32)
        require(maxValues in 1..32_768)
    }
    private var offset = 0
    private var values = 0

    fun accepts(): Boolean {
        whitespace()
        if (offset >= input.length || input[offset] != '{') return false
        value(0)
        whitespace()
        return offset == input.length
    }

    private fun whitespace() {
        while (offset < input.length && input[offset] in " \t\r\n") offset++
    }

    private fun take(char: Char): Boolean {
        whitespace()
        if (offset >= input.length || input[offset] != char) return false
        offset++
        return true
    }

    private fun string(): String {
        whitespace()
        val start = offset
        check(offset < input.length && input[offset++] == '"')
        while (offset < input.length) {
            val char = input[offset++]
            check(char.code >= 32)
            if (char == '"') {
                return JSONObject("{\"v\":" + input.substring(start, offset) + "}").getString("v")
            }
            if (char == '\\') {
                check(offset < input.length)
                val escape = input[offset++]
                if (escape == 'u') repeat(4) {
                    check(offset < input.length && input[offset++].digitToIntOrNull(16) != null)
                } else check(escape in "\"\\/bfnrt")
            }
        }
        error("invalid_diagnostic_json")
    }

    private fun value(depth: Int) {
        check(depth <= maxDepth && ++values <= maxValues)
        whitespace()
        check(offset < input.length)
        when (input[offset]) {
            '{' -> {
                offset++
                val keys = mutableSetOf<String>()
                if (take('}')) return
                while (true) {
                    check(keys.add(string()))
                    check(take(':'))
                    value(depth + 1)
                    if (take('}')) return
                    check(take(','))
                }
            }
            '[' -> {
                offset++
                if (take(']')) return
                while (true) {
                    value(depth + 1)
                    if (take(']')) return
                    check(take(','))
                }
            }
            '"' -> string()
            else -> {
                val start = offset
                while (offset < input.length && input[offset] !in " \t\r\n,]}") offset++
                val literal = input.substring(start, offset)
                check(literal in setOf("true", "false", "null") || NUMBER.matches(literal))
            }
        }
    }

    companion object {
        private val NUMBER = Regex("""-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?""")
    }
}
