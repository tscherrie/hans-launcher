package ai.hans.standard.backup

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class HansBackupResultCopyTest {
    @Test
    fun pluginAndSkillReferencesAreNeverDescribedAsActuallyRestored() {
        listOf(
            HansBackupImportResult(true, pluginReferencesRetainedForReconciliation = 1),
            HansBackupImportResult(true, skillChoicesRetainedForReconciliation = 1),
        ).forEach { result ->
            val message = backupImportResultMessage(result, true)
            assertTrue(message.contains("nur vorgemerkt"))
            assertTrue(message.contains("nicht installiert oder aktiviert"))
            assertFalse(message.contains("vollständig"))
        }
    }

    @Test
    fun stagedSelectionIsNotAlreadyEffectiveAndUnavailableSelectionIsExplicit() {
        val result = HansBackupImportResult(true, requestedModel = "gpt-5.6-luna")
        assertTrue(backupImportResultMessage(result, true).contains("erst nach Bestätigung"))
        assertTrue(backupImportResultMessage(result, false).contains("nicht verfügbar"))
    }

    @Test
    fun recoveryFallbackExplainsBlockedWorkWithoutClaimingTheJournalWasRepaired() {
        val resource = source("res/values/strings.xml")
        val strings = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }.newDocumentBuilder().parse(resource).getElementsByTagName("string")
        val messages = (0 until strings.length).map { strings.item(it) as Element }
            .filter { it.getAttribute("name") == "backup_recovery_message" }
        assertEquals(1, messages.size)
        val message = messages.single().textContent
        assertTrue(message.contains("bleiben gesperrt"))
        assertTrue(message.contains("nicht gelöscht"))
        assertTrue(
            source("java/ai/hans/standard/backup/AndroidBackupRecoveryScreen.kt").readText()
                .contains("setText(R.string.backup_recovery_message)"),
        )
    }

    private fun source(path: String) = listOf(
        File("src/main/$path"), File("android/app/src/main/$path"),
    ).first(File::isFile)
}
