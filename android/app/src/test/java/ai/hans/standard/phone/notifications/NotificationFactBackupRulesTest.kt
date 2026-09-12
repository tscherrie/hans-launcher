package ai.hans.standard.phone.notifications

import ai.hans.standard.phone.notifications.facts.AndroidNotificationFactRepository
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class NotificationFactBackupRulesTest {
    @Test fun fullBackupCannotCopyArchiveOrItsPrivacyGenerationSeal() {
        assertAllExcluded(document("backup_rules.xml").documentElement)
    }

    @Test fun cloudAndDeviceTransferEachExcludeEveryArchiveCompanion() {
        val document = document("data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val nodes = document.getElementsByTagName(section)
            assertTrue("exactly one $section policy", nodes.length == 1)
            assertAllExcluded(nodes.item(0) as Element)
        }
    }

    private fun assertAllExcluded(section: Element) {
        val database = AndroidNotificationFactRepository.DEFAULT_DATABASE_NAME
        val required = listOf(database, "$database-shm", "$database-wal", "$database-journal",
            "$database.archive-state", "$database.archive-state.bak", "$database.archive-state.new")
        val exclusions = section.getElementsByTagName("exclude")
        val actual = (0 until exclusions.length).map { exclusions.item(it) as Element }
            .filter { it.getAttribute("domain") == "database" }.map { it.getAttribute("path") }.toSet()
        required.forEach { assertTrue("archive backup exclusion missing: $it", it in actual) }
    }

    private fun document(name: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(listOf(File("src/main/res/xml/$name"), File("android/app/src/main/res/xml/$name"))
            .firstOrNull(File::isFile) ?: error("missing backup contract"))
}
