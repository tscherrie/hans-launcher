package ai.hans.standard.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerStopWiringTest {
    @Test
    fun composerCallsTheProcessOwnedCodexInterruptRatherThanSteeringOrHangingUp() {
        val root = sequenceOf(File("src/main/java"), File("android/app/src/main/java"))
            .first { it.isDirectory }
        val activity = File(root, "ai/hans/standard/LauncherActivity.kt").readText()
        val host = File(root, "ai/hans/standard/integration/AndroidCodexSessionHost.kt").readText()
        assertTrue(activity.contains("onInterruptWork = sessionHost::interrupt,"))
        val interrupt = host.substringAfter("fun interrupt(): Boolean {").substringBefore("\n    }")
        assertTrue(interrupt.contains("whatsAppAgentChannel?.cancelPending()"))
        assertTrue(interrupt.contains("return currentClient()?.interrupt() == true"))
    }
}
