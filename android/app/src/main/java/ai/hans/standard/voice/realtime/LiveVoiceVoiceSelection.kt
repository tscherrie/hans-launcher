package ai.hans.standard.voice.realtime

import java.util.Locale

/** Whether a voice choice is exact or uses a disclosed legacy approximation/fallback. */
enum class LiveVoiceVoiceResolution {
    EXACT,
    APPROXIMATE,
    FALLBACK,
}

/**
 * The effective voice for one call. The historical Realtime fields remain compatible with the
 * legacy Speech-to-Realtime mapping. Explicit Live choices have their own requested id and are
 * never represented as a TTS preference; effectiveRealtimeVoice also carries confirmed Live ids.
 */
data class LiveVoiceVoiceSelection(
    val requestedTtsVoice: String?,
    val effectiveRealtimeVoice: String,
    val resolution: LiveVoiceVoiceResolution,
    val requestedLiveVoice: String? = null,
)

/** Read once for every newly started call, never during an ongoing Live or Realtime session. */
fun interface LiveVoiceVoiceSelectionProvider {
    fun resolve(): LiveVoiceVoiceSelection
}

/** Mapping from the Hans Speech setting to the current public Realtime built-in voice catalogue. */
object LiveVoiceRealtimeVoiceMapper {
    const val FALLBACK_VOICE = LiveVoiceSessionConfig.DEFAULT_REALTIME_VOICE

    val supportedRealtimeVoices: Set<String> = linkedSetOf(
        "alloy",
        "ash",
        "ballad",
        "coral",
        "echo",
        "sage",
        "shimmer",
        "verse",
        "marin",
        "cedar",
    )

    /** Speech-only voices use an explicitly approximate Realtime counterpart. */
    val approximateVoices: Map<String, String> = linkedMapOf(
        "fable" to "ballad",
        "nova" to "coral",
        "onyx" to "cedar",
    )

    fun resolve(requestedTtsVoice: String?): LiveVoiceVoiceSelection {
        val requested = requestedTtsVoice.normalizedVoiceId()
        if (requested != null && requested in supportedRealtimeVoices) {
            return LiveVoiceVoiceSelection(
                requestedTtsVoice = requested,
                effectiveRealtimeVoice = requested,
                resolution = LiveVoiceVoiceResolution.EXACT,
            )
        }
        approximateVoices[requested]?.let { effective ->
            return LiveVoiceVoiceSelection(
                requestedTtsVoice = requested,
                effectiveRealtimeVoice = effective,
                resolution = LiveVoiceVoiceResolution.APPROXIMATE,
            )
        }
        return LiveVoiceVoiceSelection(
            requestedTtsVoice = requested,
            effectiveRealtimeVoice = FALLBACK_VOICE,
            resolution = LiveVoiceVoiceResolution.FALLBACK,
        )
    }

    private fun String?.normalizedVoiceId(): String? = this
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf { value ->
            value.isNotEmpty() &&
                value.length <= 120 &&
                value.none(Char::isISOControl)
        }
}
