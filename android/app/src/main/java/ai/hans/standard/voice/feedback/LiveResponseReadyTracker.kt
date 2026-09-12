package ai.hans.standard.voice.feedback

import ai.hans.standard.voice.realtime.LiveVoiceResponseReady

/** Serialized by LiveVoiceSession; no timer, audio callback, or historical transcript replay. */
internal class LiveResponseReadyTracker(
    private val sessionInstanceId: String,
    private val maxTrackedResponses: Int = 256,
) {
    private val consumed = LinkedHashSet<String>()
    private var generation = -1L
    private var active: LiveVoiceResponseReady? = null

    init {
        require(maxTrackedResponses in 1..4_096)
    }

    fun started(generation: Long, responseId: String?) {
        if (generation < this.generation) return
        if (generation > this.generation) {
            cancelActive()
            this.generation = generation
        }
        if (!validId(responseId) || responseId in consumed) {
            cancelActive()
            return
        }
        if (active?.responseId != responseId) cancelActive()
        active = LiveVoiceResponseReady(sessionInstanceId, generation, responseId!!)
    }

    fun text(generation: Long, responseId: String?, text: String): LiveVoiceResponseReady? {
        val response = active ?: return null
        if (response.generation != generation || response.responseId != responseId || text.isBlank()) {
            return null
        }
        if (response.responseId in consumed) return null
        remember(response.responseId)
        return response
    }

    fun finished(generation: Long, responseId: String?) {
        val response = active ?: return
        if (response.generation == generation &&
            (responseId == null || response.responseId == responseId)
        ) cancelActive()
    }

    /** Also used at transport boundaries and explicit interruption before response.done arrives. */
    fun cancelActive() {
        active?.let { remember(it.responseId) }
        active = null
    }

    private fun remember(responseId: String) {
        consumed.add(responseId)
        while (consumed.size > maxTrackedResponses) consumed.remove(consumed.first())
    }

    private fun validId(value: String?): Boolean = value != null && value.isNotBlank() &&
        value.length <= LiveVoiceResponseReady.MAX_RESPONSE_ID_CHARACTERS &&
        value.none(Char::isISOControl)
}
