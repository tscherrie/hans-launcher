package ai.hans.standard.runtime.python

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PythonRuntimeDiagnosticsTest {
    @Test
    fun streamEventsAreDecodedChunkedAndIndependentlyLimited() {
        val request = request(stdout = 40_000, stderr = 10)
        val limiter = PythonOutputLimiter(request)
        val event = PythonNativeEvent.Stream(
            requestId = request.requestId,
            sequence = 1,
            kind = PythonStreamKind.STDOUT,
            text = "x".repeat(33_000),
        )

        val chunks = limiter.accept(event)

        assertEquals(2, chunks.size)
        assertEquals(PythonRuntimeContract.MAX_BINDER_CHUNK_BYTES, chunks.first().size)
        assertArrayEquals("x".repeat(33_000).toByteArray(), chunks.reduce(ByteArray::plus))
    }

    @Test
    fun duplicateSequenceAndPerStreamOverflowFailClosed() {
        val limiter = PythonOutputLimiter(request(stdout = 5, stderr = 5))
        limiter.accept(PythonNativeEvent.Stream("request-1", 1, PythonStreamKind.STDOUT, "hello"))
        assertThrows(IllegalArgumentException::class.java) {
            limiter.accept(PythonNativeEvent.Stream("request-1", 1, PythonStreamKind.STDERR, "x"))
        }

        val stderrLimiter = PythonOutputLimiter(request(stdout = 5, stderr = 5))
        assertThrows(IllegalStateException::class.java) {
            stderrLimiter.accept(PythonNativeEvent.Stream("request-1", 1, PythonStreamKind.STDERR, "longer"))
        }
    }

    @Test
    fun capabilityEventKeepsTheBoundedStructuredEnvelope() {
        val event = PythonNativeEventCodec.decode(
            """{"protocolVersion":1,"type":"capability_request","requestId":"request-1","sequence":9,"capability":{"name":"location","arguments":{}}}""",
        ) as PythonNativeEvent.Capability

        assertEquals("location", event.capabilityName)
        assertEquals(9, event.sequence)
    }

    private fun request(stdout: Int, stderr: Int) = PythonExecutionRequest(
        requestId = "request-1",
        idempotencyKey = "idem-1",
        environmentDigest = "a".repeat(64),
        entrypoint = PythonEntrypoint(PythonEntrypointKind.CODE, source = "pass"),
        argumentsJson = "{}",
        limits = PythonResourceLimits(
            deadlineElapsedRealtimeMillis = 10_000,
            maximumStdoutBytes = stdout,
            maximumStderrBytes = stderr,
        ),
    )
}
