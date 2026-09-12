package ai.hans.standard.voice.audio

enum class SpeechAudioRoute { SPEAKER, EARPIECE, EXTERNAL, UNKNOWN }
enum class SpeechAudioRouteRequestResult { ACCEPTED, UNAVAILABLE, EXTERNAL_DEVICE, CALL_ACTIVE, FAILED }
data class SpeechAudioRouteState(
    val active: Boolean = false,
    val available: Set<SpeechAudioRoute> = emptySet(),
    val effective: SpeechAudioRoute = SpeechAudioRoute.UNKNOWN,
    val requested: SpeechAudioRoute? = null,
    val failure: SpeechAudioRouteRequestResult? = null,
    val revision: Long = 0,
)

/** A playback endpoint, never a capture controller. Accepted preference is not effective routing. */
interface SpeechAudioRouteEndpoint : AutoCloseable {
    val retainsPlaybackPreference: Boolean get() = false
    fun available(): Set<SpeechAudioRoute>
    fun effective(): SpeechAudioRoute
    fun externalDevicePresent(): Boolean
    fun callActive(): Boolean
    fun request(route: SpeechAudioRoute): Boolean
    fun observeChanged(callback: () -> Unit): AutoCloseable
}

/** No polling, persistence, microphone controls or automatic replay of a previous selection. */
open class SpeechAudioRouteController(private val deliver: ((() -> Unit) -> Unit) = { it() }) {
    private val lock = Any()
    private val endpoints = linkedMapOf<Any, Pair<SpeechAudioRouteEndpoint, AutoCloseable>>()
    private val observers = linkedMapOf<Any, (SpeechAudioRouteState) -> Unit>()
    private var state = SpeechAudioRouteState()
    private var refreshSequence = 0L
    private var playbackOwner: Any? = null
    private var playbackPreference: SpeechAudioRoute? = null

    /** One lease per complete TTS response, not per sentence/AudioTrack. No persistent setting. */
    fun beginPlayback(): AutoCloseable {
        val owner = Any()
        synchronized(lock) {
            check(playbackOwner == null) { "speech_playback_already_owned" }
            playbackOwner = owner
            playbackPreference = null
        }
        return AutoCloseable {
            synchronized(lock) {
                if (playbackOwner === owner) {
                    playbackOwner = null
                    playbackPreference = null
                    state = state.copy(requested = null)
                }
            }
            refresh()
        }
    }

    fun snapshot(): SpeechAudioRouteState = synchronized(lock) { state }

    protected fun reportRouteFailure() {
        synchronized(lock) { state = state.copy(failure = SpeechAudioRouteRequestResult.FAILED) }
        refresh()
    }

    fun observe(callback: (SpeechAudioRouteState) -> Unit): AutoCloseable {
        val key = Any()
        synchronized(lock) { observers[key] = callback }
        deliver { runCatching { synchronized(lock) { observers[key] }?.invoke(snapshot()) } }
        return AutoCloseable { synchronized(lock) { observers.remove(key) } }
    }

    fun attach(endpoint: SpeechAudioRouteEndpoint): AutoCloseable {
        val key = Any()
        val (owner, preference) = synchronized(lock) { playbackOwner to playbackPreference }
        if (endpoint.retainsPlaybackPreference && preference != null) {
            val applied = try {
                when {
                    endpoint.callActive() -> false
                    endpoint.externalDevicePresent() -> preference != SpeechAudioRoute.EARPIECE
                    preference !in endpoint.available() -> false
                    else -> endpoint.request(preference)
                }
            } catch (_: RuntimeException) { false }
            if (!applied) {
                endpoint.close()
                error("speech_private_route_unavailable")
            }
        }
        val subscription = try { endpoint.observeChanged { refresh() } } catch (error: RuntimeException) {
            runCatching { endpoint.close() }
            throw error
        }
        synchronized(lock) {
            endpoints[key] = endpoint to subscription
            state = state.copy(requested = if (playbackOwner === owner) preference else null, failure = null)
        }
        refresh()
        return AutoCloseable {
            val removed = synchronized(lock) {
                val removed = endpoints.remove(key)
                if (removed != null) state = state.copy(requested = null, failure = null)
                removed
            }
            removed?.let { (owned, subscription) ->
                try { subscription.close() } finally { owned.close() }
            }
            refresh()
        }
    }

    fun request(route: SpeechAudioRoute): SpeechAudioRouteRequestResult {
        val endpoint = synchronized(lock) { endpoints.values.singleOrNull()?.first }
            val outcome = try {
                when {
                    endpoint == null || route !in setOf(SpeechAudioRoute.SPEAKER, SpeechAudioRoute.EARPIECE) -> SpeechAudioRouteRequestResult.UNAVAILABLE
                    endpoint.callActive() -> SpeechAudioRouteRequestResult.CALL_ACTIVE
                    route !in endpoint.available() -> SpeechAudioRouteRequestResult.UNAVAILABLE
                    endpoint.request(route) -> SpeechAudioRouteRequestResult.ACCEPTED
                    else -> SpeechAudioRouteRequestResult.FAILED
                }
            } catch (_: RuntimeException) { SpeechAudioRouteRequestResult.FAILED }
        val result = synchronized(lock) {
            if (endpoints.values.singleOrNull()?.first !== endpoint) return@synchronized SpeechAudioRouteRequestResult.UNAVAILABLE
            state = state.copy(
                requested = if (outcome == SpeechAudioRouteRequestResult.ACCEPTED) route else null,
                failure = outcome.takeUnless { it == SpeechAudioRouteRequestResult.ACCEPTED },
            )
            if (outcome == SpeechAudioRouteRequestResult.ACCEPTED && playbackOwner != null && endpoint?.retainsPlaybackPreference == true) {
                playbackPreference = route
            }
            outcome
        }
        refresh()
        return result
    }

    private fun refresh() {
        val (endpoint, sequence) = synchronized(lock) {
            endpoints.values.singleOrNull()?.first to ++refreshSequence
        }
            val effective = runCatching { endpoint?.effective() }.getOrNull() ?: SpeechAudioRoute.UNKNOWN
            val available = runCatching {
                if (endpoint == null || endpoint.callActive()) emptySet()
                else endpoint.available()
            }.getOrDefault(emptySet())
        synchronized(lock) {
            if (sequence != refreshSequence || endpoints.values.singleOrNull()?.first !== endpoint) return
            state = state.copy(active = endpoints.isNotEmpty(), available = available,
                effective = effective, revision = state.revision + 1)
        }
        // Read the newest snapshot on delivery, so old queued callbacks cannot regress UI state.
        deliver {
            val (latest, listeners) = synchronized(lock) { state to observers.values.toList() }
            listeners.forEach { listener -> runCatching { listener(latest) } }
        }
    }
}
