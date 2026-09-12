package ai.hans.standard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChatTimelineIdentityTest {
    @Test
    fun appServerItemIdsAreNamespacedByVisibleAuthor() {
        val user = ChatMessageUiModel(
            id = "shared-app-server-item",
            author = ChatMessageAuthor.USER,
            text = "Frage",
        )
        val hans = ChatMessageUiModel(
            id = "shared-app-server-item",
            author = ChatMessageAuthor.HANS,
            text = "Antwort",
        )

        assertEquals("USER:shared-app-server-item", chatTimelineItemKey(user))
        assertEquals("HANS:shared-app-server-item", chatTimelineItemKey(hans))
        assertNotEquals(chatTimelineItemKey(user), chatTimelineItemKey(hans))
    }
}
