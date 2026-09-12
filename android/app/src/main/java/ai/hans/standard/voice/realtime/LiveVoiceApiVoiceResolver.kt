package ai.hans.standard.voice.realtime

import java.util.Locale

/** The Live API has its own voice catalogue; it must never be used as a Speech/TTS setting. */
object LiveVoiceApiVoiceResolver {
    const val DEFAULT_VOICE = "ripple"

    val supportedVoices: Set<String>
        get() = OpenAiLiveProtocol.supportedVoices

    fun resolve(requestedLiveVoice: String?): LiveVoiceVoiceSelection {
        val requested = requestedLiveVoice
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() && it.length <= 120 && it.none(Char::isISOControl) }
        val exact = requested != null && requested in supportedVoices
        return LiveVoiceVoiceSelection(
            requestedTtsVoice = null,
            effectiveRealtimeVoice = if (exact) checkNotNull(requested) else DEFAULT_VOICE,
            resolution = if (exact) LiveVoiceVoiceResolution.EXACT else LiveVoiceVoiceResolution.FALLBACK,
            requestedLiveVoice = requested,
        )
    }
}
