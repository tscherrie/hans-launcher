package ai.hans.standard.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveVoiceTranscriptUiUpdatesTest {
    @Test fun blankFinalDiscardsOnlyTheSameAuthorsIncompleteUtterance() {
        for (author in listOf(ChatMessageAuthor.USER, ChatMessageAuthor.HANS)) {
            val old = ChatMessageUiModel("old", author, "Previous answer", complete = true)
            val partial = ChatMessageUiModel("current", author, "Discarded partial", complete = false)
            val independent = ChatMessageUiModel("independent", author, partial.text, complete = false)
            val otherAuthor = ChatMessageUiModel("current", ChatMessageAuthor.SYSTEM, "Independent status", complete = false)
            assertEquals(listOf(old, independent, otherAuthor),
                LiveVoiceTranscriptUiUpdates.discardIncomplete(listOf(old, partial, independent, otherAuthor), "current", author))
            assertEquals(listOf(old, partial),
                LiveVoiceTranscriptUiUpdates.discardIncomplete(listOf(old, partial), null, author))
            assertEquals(listOf(old),
                LiveVoiceTranscriptUiUpdates.discardIncomplete(listOf(old), old.id, author))
        }
    }
}
