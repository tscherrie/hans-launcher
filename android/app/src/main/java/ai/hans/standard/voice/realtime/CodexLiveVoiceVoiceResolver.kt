package ai.hans.standard.voice.realtime

import java.util.Locale

/** Codex V3 voices are independent from both the API Live and Speech/TTS catalogues. */
object CodexLiveVoiceVoiceResolver {
    const val DEFAULT_VOICE = "cove"
    val supportedVoices: Set<String> = setOf(
        "juniper", "maple", "spruce", "ember", "vale", "breeze", "arbor", "sol", "cove",
    )

    fun resolve(value: String?): LiveVoiceVoiceSelection {
        val requested = value?.trim()?.lowercase(Locale.ROOT)
            ?.takeIf { it.length in 1..120 && it.none(Char::isISOControl) }
        val exact = requested in supportedVoices
        return LiveVoiceVoiceSelection(
            requestedTtsVoice = null,
            effectiveRealtimeVoice = if (exact) requireNotNull(requested) else DEFAULT_VOICE,
            resolution = if (exact) LiveVoiceVoiceResolution.EXACT else LiveVoiceVoiceResolution.FALLBACK,
            requestedLiveVoice = requested,
        )
    }
}
