package ai.hans.standard.profile

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ProfileBackupRulesTest {
    @Test
    fun cloudBackupExcludesUnconfirmedInterviewAndAtomicWriteSidecars() {
        assertProfileExcluded(
            document("data_extraction_rules.xml")
                .getElementsByTagName("cloud-backup")
                .item(0) as Element,
        )
    }

    @Test
    fun deviceTransferExcludesUnconfirmedInterviewAndAtomicWriteSidecars() {
        assertProfileExcluded(
            document("data_extraction_rules.xml")
                .getElementsByTagName("device-transfer")
                .item(0) as Element,
        )
    }

    @Test
    fun legacyFullBackupExcludesUnconfirmedInterviewAndAtomicWriteSidecars() {
        assertProfileExcluded(document("backup_rules.xml").documentElement)
    }

    private fun assertProfileExcluded(section: Element) {
        val excludes = section.getElementsByTagName("exclude")
        val fileExclusions = (0 until excludes.length)
            .map { excludes.item(it) as Element }
            .filter { it.getAttribute("domain") == "file" }
            .map { it.getAttribute("path") }
        val profileSource = source(
            "java/ai/hans/standard/profile/AtomicFileUserProfileStorage.kt",
        ).readText()
        // Derive the actual on-disk filename so a storage rename cannot silently bypass this gate.
        val profileNames = Regex("const val FILE_NAME = \"([^\"]+)\"")
            .findAll(profileSource)
            .map { it.groupValues[1] }
            .toList()
        assertEquals("one fixed profile persistence filename", 1, profileNames.size)
        val profileName = profileNames.single()
        listOf(profileName, "$profileName.bak", "$profileName.new").forEach { path ->
            assertTrue(
                "${section.tagName} must not copy the interview draft, confirmation nonce or its atomic sidecar: $path",
                path in fileExclusions,
            )
        }
    }

    private fun document(name: String) = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(source("res/xml/$name"))

    private fun source(relativePath: String): File = listOf(
        File("src/main/$relativePath"),
        File("android/app/src/main/$relativePath"),
    ).firstOrNull(File::isFile) ?: error("missing profile backup contract source $relativePath")
}
