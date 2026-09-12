package ai.hans.standard.phone.capabilities

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateSpaceBackupRulesTest {
    @Test
    fun containerVisibilityRemainsDeviceLocalAcrossEveryAndroidBackupPath() {
        val excludedPreference =
            "<exclude domain=\"sharedpref\" path=\"hans_private_space_visibility_v1.xml\" />"
        val fullBackup = resource("backup_rules.xml").readText()
        val extraction = resource("data_extraction_rules.xml").readText()

        assertTrue(fullBackup.contains(excludedPreference))
        assertEquals(
            "cloud backup and device transfer must both exclude the visibility preference",
            2,
            Regex(Regex.escape(excludedPreference)).findAll(extraction).count(),
        )
    }

    private fun resource(name: String): File = listOf(
        File("src/main/res/xml/$name"),
        File("android/app/src/main/res/xml/$name"),
    ).firstOrNull(File::isFile) ?: error("missing backup contract resource $name")
}
