package ai.hans.standard.settings.fastphone

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class FastPhoneUseBackupRulesTest {
    @Test fun encryptedCredentialAndLegacyOptInCannotMigrateThroughEitherBackupFormat() {
        val legacy = document("backup_rules.xml").documentElement
        assertExclusions(legacy)
        val modern = document("data_extraction_rules.xml")
        listOf("cloud-backup", "device-transfer").forEach { kind ->
            assertExclusions(modern.getElementsByTagName(kind).item(0) as Element)
        }
    }

    private fun assertExclusions(section: Element) {
        val excludes = section.getElementsByTagName("exclude")
        val preferenceFiles = (0 until excludes.length).map { excludes.item(it) as Element }
            .filter { it.getAttribute("domain") == "sharedpref" }.map { it.getAttribute("path") }
        assertTrue("${FastPhoneUseSettingsStore.CREDENTIAL_PREFERENCES_NAME}.xml" in preferenceFiles)
        assertTrue("${FastPhoneUseSettingsStore.PREFERENCES_NAME}.xml" in preferenceFiles)
    }

    private fun document(name: String) = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(listOf(File("src/main/res/xml/$name"),
        File("android/app/src/main/res/xml/$name")).first(File::isFile))
}
