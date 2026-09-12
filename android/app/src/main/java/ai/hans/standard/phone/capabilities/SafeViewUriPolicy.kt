package ai.hans.standard.phone.capabilities

import java.net.URI

/**
 * Pure, fail-closed policy for public ACTION_VIEW targets.
 *
 * The returned value is still untrusted input. Acceptance means only that it is
 * safe to hand to Android's public VIEW resolver after explicit user confirmation.
 */
object SafeViewUriPolicy {
    const val MAX_UTF16_CHARS = 2_048

    private val allowedSchemes = setOf("http", "https", "geo", "market")

    fun accept(raw: String): String? {
        if (raw.length !in 1..MAX_UTF16_CHARS || raw.any(::isUnsafeCharacter)) return null
        val parsed = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase() ?: return null
        if (scheme !in allowedSchemes) return null

        when (scheme) {
            "http", "https" -> {
                if (parsed.host.isNullOrBlank() || parsed.rawUserInfo != null) return null
                if (parsed.host.any { !it.isLetterOrDigit() && it !in ".-" }) return null
            }
            "geo", "market" -> if (parsed.rawSchemeSpecificPart.isNullOrBlank()) return null
        }
        return raw
    }

    private fun isUnsafeCharacter(character: Char): Boolean =
        character.isISOControl() ||
            character.isWhitespace() ||
            character.code in BIDI_CONTROLS ||
            character == '\\'

    private val BIDI_CONTROLS = buildSet {
        add(0x061C)
        add(0x200E)
        add(0x200F)
        addAll(0x202A..0x202E)
        addAll(0x2066..0x2069)
    }
}
