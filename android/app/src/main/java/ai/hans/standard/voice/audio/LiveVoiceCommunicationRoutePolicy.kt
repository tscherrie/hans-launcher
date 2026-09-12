package ai.hans.standard.voice.audio

/** A Live call starts on speaker. Only an explicit request changes its built-in route. */
internal class LiveVoiceCommunicationRoutePolicy {
    enum class Result { PENDING, CONFIRMED, LOST }

    var requested: SpeechAudioRoute = SpeechAudioRoute.SPEAKER
        private set
    private var pending = true
    private var confirmed = false

    fun requestAccepted(route: SpeechAudioRoute) {
        require(route == SpeechAudioRoute.SPEAKER || route == SpeechAudioRoute.EARPIECE)
        requested = route
        pending = true
    }

    fun routeChanged(effective: SpeechAudioRoute): Result = when {
        effective == requested -> {
            pending = false
            confirmed = true
            Result.CONFIRMED
        }
        pending || !confirmed -> Result.PENDING
        else -> Result.LOST
    }

    fun acknowledgementExpired(effective: SpeechAudioRoute): Result =
        if (routeChanged(effective) == Result.CONFIRMED) Result.CONFIRMED else Result.LOST
}
