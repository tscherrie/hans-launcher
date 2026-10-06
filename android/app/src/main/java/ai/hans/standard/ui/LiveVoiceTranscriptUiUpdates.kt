package ai.hans.standard.ui

/** A blank final cancels only its own unfinished display card, never an independent reply. */
object LiveVoiceTranscriptUiUpdates {
    fun discardIncomplete(
        messages: List<ChatMessageUiModel>,
        utteranceId: String?,
        author: ChatMessageAuthor,
    ): List<ChatMessageUiModel> = messages.filterNot {
        utteranceId != null && it.id == utteranceId && it.author == author && !it.complete
    }
}
