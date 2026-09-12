package ai.hans.standard.integration

import ai.hans.standard.codex.AgentMessagePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveVoiceCodexTaskProjectionTest {
    @Test
    fun finalAnswerWinsOverCommentaryAndToolOutput() {
        val result = LiveVoiceCodexTaskProjection.completedOutput(
            listOf(
                item(
                    id = "commentary",
                    role = ClientTimelineRole.HANS,
                    text = "Ich prüfe das.",
                    order = 2,
                    phase = AgentMessagePhase.COMMENTARY,
                ),
                item(
                    id = "tool",
                    role = ClientTimelineRole.TOOL,
                    text = "private tool output",
                    order = 3,
                ),
                item(
                    id = "final",
                    role = ClientTimelineRole.HANS,
                    text = "Erledigt.",
                    order = 4,
                    phase = AgentMessagePhase.FINAL_ANSWER,
                ),
            ),
        )

        assertEquals("Erledigt.", result)
    }

    @Test
    fun newestVisibleCommentaryBecomesBoundedNaturalProgress() {
        val result = LiveVoiceCodexTaskProjection.newestProgress(
            listOf(
                item(
                    id = "one",
                    role = ClientTimelineRole.HANS,
                    text = "Erster Stand",
                    order = 2,
                    phase = AgentMessagePhase.COMMENTARY,
                ),
                item(
                    id = "two",
                    role = ClientTimelineRole.HANS,
                    text = "  Ich bin\nfast\tfertig.  ",
                    order = 5,
                    phase = AgentMessagePhase.COMMENTARY,
                ),
            ),
        )

        assertEquals("Ich bin fast fertig.", result)
    }

    @Test
    fun toolOnlyTimelineNeverLeaksAsSpokenResultOrProgress() {
        val items = listOf(
            item(
                id = "tool",
                role = ClientTimelineRole.TOOL,
                text = "secret raw output",
                order = 1,
            ),
        )

        assertEquals("", LiveVoiceCodexTaskProjection.completedOutput(items))
        assertNull(LiveVoiceCodexTaskProjection.newestProgress(items))
    }

    private fun item(
        id: String,
        role: ClientTimelineRole,
        text: String,
        order: Long,
        phase: AgentMessagePhase? = null,
    ) = ClientTimelineItem(
        id = id,
        role = role,
        text = text,
        order = order,
        revision = 1,
        complete = true,
        status = ClientTimelineStatus.COMPLETE,
        agentPhase = phase,
    )
}
