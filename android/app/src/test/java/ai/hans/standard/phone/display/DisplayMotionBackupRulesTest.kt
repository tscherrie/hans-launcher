package ai.hans.standard.phone.display

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class DisplayMotionBackupRulesTest {
    @Test
    fun panelPreferenceIsExcludedFromBothCloudBackupAndDeviceTransfer() {
        val document = document("data_extraction_rules.xml")
        listOf("cloud-backup", "device-transfer").forEach { tag ->
            val sections = document.getElementsByTagName(tag)
            assertEquals(1, sections.length)
            assertExcluded(sections.item(0) as Element)
        }
    }

    @Test
    fun panelPreferenceIsAlsoExcludedFromLegacyAndroidBackup() {
        assertExcluded(document("backup_rules.xml").documentElement)
    }

    private fun assertExcluded(section: Element) {
        val excludes = section.getElementsByTagName("exclude")
        val preferenceFiles = (0 until excludes.length).map { excludes.item(it) as Element }
            .filter { it.getAttribute("domain") == "sharedpref" }
            .map { it.getAttribute("path") }
        assertTrue(
            "${section.tagName}: a new display must not inherit the previous device's override",
            "${DisplayMotionPreferencesStore.PREFERENCES_NAME}.xml" in preferenceFiles,
        )
    }

    private fun document(name: String) = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(
        listOf(File("src/main/res/xml/$name"), File("android/app/src/main/res/xml/$name"))
            .firstOrNull(File::isFile) ?: error("Missing backup rules: $name"),
    )
}
