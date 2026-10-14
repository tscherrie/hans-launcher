package ai.hans.standard.codex

import org.json.JSONObject

/** Native data-only context. This type cannot encode application/developer authority. */
class UntrustedTurnContext(source: String, value: String) {
    private val sourceId = source
    private val quotedValue = value

    init {
        require(source.matches(Regex("[a-z][a-z0-9_]{0,63}"))) { "Invalid context source" }
        require(value.isNotBlank()) { "Empty context fragment" }
        JsonContract.requireUtf8Bound(value, MAX_BYTES, "Untrusted turn context")
    }

    internal fun toJson(): JSONObject = JSONObject().put(sourceId,
        JSONObject().put("kind", "untrusted").put("value", quotedValue))

    override fun toString() = "UntrustedTurnContext(private=true)"

    companion object { const val MAX_BYTES = 8 * 1_024 }
}
