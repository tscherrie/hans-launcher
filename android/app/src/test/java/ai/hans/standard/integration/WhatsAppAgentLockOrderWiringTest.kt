package ai.hans.standard.integration

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Privacy purge already enters the host: the agent path must use that same outer lock order. */
class WhatsAppAgentLockOrderWiringTest {
    @Test
    fun agentDispatchTakesPrivacyBeforeHostAndChannelNotTheReverse() {
        val source = File("src/main/java/ai/hans/standard/integration/AndroidCodexSessionHost.kt").readText()
        val entry = source.substringAfter("internal fun dispatchWhatsAppAgentRequest(")
            .substringBefore("val current =")
        val monitors = Regex("synchronized\\s*\\(\\s*([^)]*)\\)").findAll(entry)
            .map { it.groupValues[1].trim() }.toList()
        assertEquals("Expected explicit privacy/host/channel transaction", 3, monitors.size)
        assertTrue(monitors[0].endsWith("NotificationPrivacyMutationCoordinator.lock"))
        assertEquals("dispatchLock", monitors[1])
        assertEquals("channel", monitors[2])
        val body = source.substringAfter("internal fun dispatchWhatsAppAgentRequest(")
            .substringBefore("\n    fun dispatch(")
        assertTrue("Fresh source and privacy readiness must be checked inside the transaction",
            body.contains("intakeAvailable()"))
        assertTrue(body.indexOf("intakeAvailable()") < body.indexOf("dispatchSerial("))
    }
}
