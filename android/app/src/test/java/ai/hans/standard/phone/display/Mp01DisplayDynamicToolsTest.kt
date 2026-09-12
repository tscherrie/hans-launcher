package ai.hans.standard.phone.display

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolExecutionResult
import java.util.concurrent.Executor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp01DisplayDynamicToolsTest {
    @Test
    fun catalogContainsOnlyTheDocumentedBoundedSurface() {
        assertEquals(
            setOf("status", "set_profile", "full_refresh"),
            Mp01DisplayDynamicToolCatalog.namespace.tools.map { it.name }.toSet(),
        )
    }

    @Test
    fun statusReportsFreshAvailabilityWithoutWriting() {
        val transport = RecordingTransport()
        val executor = executor(transport)

        val result = executor.run("status", "{}")

        assertTrue(result.success)
        val body = JSONObject(result.contentText)
        assertTrue(body.getBoolean("available"))
        assertEquals("fresh_probe", body.getString("verification"))
        assertTrue(transport.commands.isEmpty())
    }

    @Test
    fun profileWriteIsExplicitlyUnverified() {
        val transport = RecordingTransport()
        val result = executor(transport).run("set_profile", "{\"profile\":\"smooth\"}")

        assertTrue(result.success)
        val body = JSONObject(result.contentText)
        assertEquals("sent_unverified", body.getString("status"))
        assertFalse(body.getBoolean("physicallyApplied"))
        assertEquals("device_observation_required", body.getString("verification"))
        assertEquals(listOf('s'.code.toByte()), transport.commands)
    }

    @Test
    fun invalidOrExtraArgumentsFailWithoutWriting() {
        val transport = RecordingTransport()
        val executor = executor(transport)

        val invalid = executor.run("set_profile", "{\"profile\":\"undocumented\"}")
        val extra = executor.run("full_refresh", "{\"force\":true}")

        assertFalse(invalid.success)
        assertFalse(extra.success)
        assertTrue(transport.commands.isEmpty())
    }

    private fun executor(transport: RecordingTransport) = Mp01DisplayDynamicToolExecutor(
        controller = Mp01DisplayController(
            evidenceSource = Mp01DisplayTrustEvidenceSource {
                Mp01DisplayTrustEvidence(
                    device = AndroidDeviceIdentity("ALONG", "Minimal_Phone", "MP01", "MP01"),
                    trustedMinimalSystemPackage = true,
                )
            },
            transport = transport,
        ),
        backgroundExecutor = Executor { it.run() },
    )

    private fun Mp01DisplayDynamicToolExecutor.run(
        tool: String,
        arguments: String,
    ): DynamicToolExecutionResult {
        var result: DynamicToolExecutionResult? = null
        execute(
            DynamicToolCallParams(
                threadId = "thread-1",
                turnId = "turn-1",
                callId = "call-1-$tool",
                namespace = Mp01DisplayDynamicToolCatalog.NAMESPACE,
                tool = tool,
                argumentsJson = arguments,
            ),
        ) { result = it }
        return requireNotNull(result)
    }

    private class RecordingTransport : Mp01EinkSocketTransport {
        val commands = mutableListOf<Byte>()
        override fun canConnect(): Boolean = true
        override fun send(command: Byte) {
            commands += command
        }
    }
}
