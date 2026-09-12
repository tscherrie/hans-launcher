package ai.hans.standard.phone.notifications

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationBackupRulesTest {
    @Test
    fun everyAndroidBackupPathExcludesAllSqliteNotificationContentFiles() {
        val backup = resource("backup_rules.xml").readText()
        val extraction = resource("data_extraction_rules.xml").readText()
        val paths = listOf(
            "notification_inbox.db",
            "notification_inbox.db-shm",
            "notification_inbox.db-wal",
            "notification_inbox.db-journal",
        )

        paths.forEach { path ->
            assertTrue("full backup includes $path", backup.hasDatabaseExclusion(path))
            assertEquals(
                "cloud/device transfer exclusions missing for $path",
                2,
                extraction.databaseExclusionCount(path),
            )
        }
    }

    private fun resource(name: String): File = listOf(
        File("src/main/res/xml/$name"),
        File("android/app/src/main/res/xml/$name"),
    ).firstOrNull(File::isFile) ?: error("missing backup contract resource $name")

    private fun String.hasDatabaseExclusion(path: String): Boolean =
        contains("<exclude domain=\"database\" path=\"$path\" />")

    private fun String.databaseExclusionCount(path: String): Int =
        Regex(Regex.escape("<exclude domain=\"database\" path=\"$path\" />"))
            .findAll(this)
            .count()
}
