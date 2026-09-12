package ai.hans.standard.ui

import ai.hans.standard.settings.HansSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfirmedSttGlossaryProjectionTest {
    @Test
    fun durableGlossaryProjectionIsNotReplacedByAUiDraft() {
        val confirmed = ConfirmedSttGlossaryUiState(
            terms = listOf("Grenzebach", "Skill Me Now"),
            notice = "Gespeichert.",
        )

        val projected = HansClientUiProjector.project(
            client = null,
            local = HansLocalUiState(confirmedSttGlossary = confirmed),
            settings = HansSettings(),
        )

        assertEquals(confirmed, projected.settings.confirmedSttGlossary)
    }
}
