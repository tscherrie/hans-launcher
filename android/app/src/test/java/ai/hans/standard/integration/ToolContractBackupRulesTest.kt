package ai.hans.standard.integration

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ToolContractBackupRulesTest {
    @Test
    fun threadRecoveryPointersAreExcludedFromCloudAndDeviceTransfer() {
        val document = document("data_extraction_rules.xml")
        listOf("cloud-backup", "device-transfer").forEach { tag ->
            val sections = document.getElementsByTagName(tag)
            assertEquals(1, sections.length)
            assertExcluded(sections.item(0) as Element)
        }
    }

    @Test
    fun threadRecoveryPointersAreExcludedFromLegacyAutoBackup() {
        assertExcluded(document("backup_rules.xml").documentElement)
    }

    private fun assertExcluded(section: Element) {
        val source = source("java/ai/hans/standard/integration/DynamicToolContractMigration.kt").readText()
        val names = Regex("const val PREFERENCES_NAME = \"([^\"]+)\"")
            .findAll(source).map { it.groupValues[1] }.toList()
        assertEquals("one fixed contract state filename", 1, names.size)
        val excludes = section.getElementsByTagName("exclude")
        val sharedPreferences = (0 until excludes.length).map { excludes.item(it) as Element }
            .filter { it.getAttribute("domain") == "sharedpref" }
            .map { it.getAttribute("path") }
        assertTrue("${section.tagName}: local thread pointers must not migrate without their Codex home",
            "${names.single()}.xml" in sharedPreferences)
    }

    private fun document(name: String) = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(source("res/xml/$name"))

    private fun source(relative: String): File = listOf(
        File("src/main/$relative"), File("android/app/src/main/$relative"),
    ).firstOrNull(File::isFile) ?: error("missing backup contract source: $relative")
}
