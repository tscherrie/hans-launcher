package ai.hans.standard.integration

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keep the actual Android host on one fence, including replacement clients/revisions. */
class CrossTurnPhoneToolHostWiringTest {
    @Test
    fun independentBackgroundExecutorsUseTheSameHostOwnedPhysicalFence() {
        val host = File(
            "src/main/java/ai/hans/standard/integration/AndroidCodexSessionHost.kt",
        ).readText()
        assertEquals(1, Regex("CrossTurnPhoneToolFence\\(").findAll(host).count())
        assertTrue(
            Regex(
                "fun backgroundDynamicToolExecutor\\(\\): DynamicToolExecutor\\s*=\\s*" +
                    "phoneToolFence\\.wrap\\(dynamicToolCoordinator\\.backgroundExecutor\\(\\)\\)",
            ).containsMatchIn(host),
        )
    }

    @Test
    fun everyAttachedClientUsesTheHostOwnedFenceAndRealPhoneEvidenceInvalidation() {
        val host = File(
            "src/main/java/ai/hans/standard/integration/AndroidCodexSessionHost.kt",
        ).readText()
        assertEquals(1, Regex("CrossTurnPhoneToolFence\\(").findAll(host).count())
        assertTrue(host.contains("private val phoneToolFence ="))
        assertTrue(host.contains("HansPhoneToolEvidence::invalidateRetainedEvidence"))
        assertTrue(host.contains("onQuiescent = ::onPhoneToolWorkQuiescent"))
        assertTrue(host.contains("remoteControlMain.post { dynamicToolCoordinator.onActivityChanged() }"))
        val attach = host.substringAfter("private fun attachClient(")
            .substringBefore("val observer = CodexClientObserver")
        assertTrue(attach.contains("dynamicToolExecutor = phoneToolFence.wrap(toolContract)"))
        val workProbe = host.substringAfter("private fun hasActiveDynamicToolContractWork()")
            .substringBefore("private fun currentDeveloperInstructions()")
        assertTrue(workProbe.contains("phoneToolFence.hasActiveWork"))
    }
}
