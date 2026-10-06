package ai.hans.standard.voice.realtime

/** Confirmed native Voice-to-work ownership; grants no additional execution authority. */
data class CodexVoiceWorkScope(
    val threadId: String,
    val turnId: String,
) {
    init {
        require(listOf(threadId, turnId).all { value ->
            value.isNotBlank() && value.length <= 512 && value.none(Char::isISOControl)
        })
    }
}

enum class LiveVoiceTranscriptAuthor { USER, HANS }

/** Stable local utterance identity survives partial/final delivery and observer replay. */
data class LiveVoiceTranscriptRevision(
    val sessionInstanceId: String,
    val generation: Long,
    val utteranceId: String,
    val author: LiveVoiceTranscriptAuthor,
    val text: String,
    val isFinal: Boolean,
    /** Explicit short-task presentation episode, retained through user revisions/barge-in
     * until a new confirmed work binding or session end. NOT a claim that this utterance
     * renders a particular item. Never set for PHONE or pre-handoff completed speech. */
    val dictationWorkScope: CodexVoiceWorkScope? = null,
) {
    init {
        require(sessionInstanceId.isNotBlank() && sessionInstanceId.length <= 128 &&
            sessionInstanceId.none(Char::isISOControl))
        require(generation >= 0)
        require(utteranceId.isNotBlank() && utteranceId.length <= 128 && utteranceId.none(Char::isISOControl))
        require(author == LiveVoiceTranscriptAuthor.HANS || dictationWorkScope == null)
    }

    val displayId: String get() = "live-$sessionInstanceId-$generation-$utteranceId"
}
