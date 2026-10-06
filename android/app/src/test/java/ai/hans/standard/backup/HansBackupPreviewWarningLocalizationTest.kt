package ai.hans.standard.backup

import ai.hans.standard.localization.TestResourceTextResolver
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HansBackupPreviewWarningLocalizationTest {
    @Test
    fun previewCanBeRenderedInBothLanguagesWithoutChangingItsSemanticWarning() {
        val warning = HansBackupPreviewWarning(HansBackupWarningKind.RETARGETED_AUTOMATIONS, 1)
        val before = warning.copy()
        assertEquals("1 thread-bound automation will deliberately start in a new thread after import.",
            warning.render(TestResourceTextResolver()))
        assertEquals("1 threadgebundene Automation startet nach dem Import bewusst in einem neuen Thread.",
            warning.render(TestResourceTextResolver(Locale.GERMAN)))
        assertEquals(before, warning)
    }

    @Test
    fun countWarningsHavePluralFormsAndSelectionWarningStillRequiresServerConfirmation() {
        val english = TestResourceTextResolver()
        assertEquals("2 skill selections differ from the current catalog.",
            HansBackupPreviewWarning(HansBackupWarningKind.CHANGED_SKILLS, 2).render(english))
        assertTrue(HansBackupPreviewWarning(HansBackupWarningKind.SELECTION_STAGED).render(english)
            .contains("only after confirmation by the Codex App Server"))
    }
}
