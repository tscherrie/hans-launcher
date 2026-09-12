package ai.hans.standard.runtime.python.resolver

import ai.hans.standard.runtime.python.PythonEnvironmentContract
import ai.hans.standard.runtime.python.PythonWheelPin
import ai.hans.standard.work.WorkHttpCall
import ai.hans.standard.work.WorkHttpCallFactory
import ai.hans.standard.work.WorkHttpRequest
import ai.hans.standard.work.WorkHttpResponse
import ai.hans.standard.work.WorkHttpResponseMetadata
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurePyPiWheelDownloaderTest {
    @Test
    fun writesOnlyExactHashAndSizePinnedWheel() {
        val body = "exact wheel bytes".toByteArray()
        val factory = bodyFactory(body)
        val pin = pin(body)
        val root = kotlin.io.path.createTempDirectory("wheel-download").toFile()
        val destination = root.resolve(pin.fileName)
        val receipt = SecurePyPiWheelDownloader(PythonResolverHttpClient(factory)).download(
            pin,
            destination,
            PythonResolutionCancellation.NONE,
        )
        assertEquals(pin.sha256, receipt.sha256)
        assertTrue(destination.readBytes().contentEquals(body))
        assertFalse(destination.canExecute())
    }

    @Test
    fun mismatchNeverPublishesDestination() {
        val expected = "expected".toByteArray()
        val received = "tampered".toByteArray()
        val root = kotlin.io.path.createTempDirectory("wheel-mismatch").toFile()
        val destination = root.resolve("demo.whl")
        val error = runCatching {
            SecurePyPiWheelDownloader(PythonResolverHttpClient(bodyFactory(received))).download(
                pin(expected),
                destination,
                PythonResolutionCancellation.NONE,
            )
        }.exceptionOrNull() as PythonResolutionException
        assertEquals(PythonResolutionErrorCode.HASH_MISMATCH, error.code)
        assertFalse(destination.exists())
    }

    private fun pin(body: ByteArray) = PythonWheelPin(
        packageName = "demo",
        version = "1.0",
        fileName = "demo-1.0-py3-none-any.whl",
        sha256 = PythonEnvironmentContract.sha256(body),
        sizeBytes = body.size.toLong(),
        sourceUri = URL,
    )

    private fun bodyFactory(body: ByteArray) = WorkHttpCallFactory { request: WorkHttpRequest ->
        object : WorkHttpCall {
            override fun execute(): WorkHttpResponse = object : WorkHttpResponse {
                override val metadata = WorkHttpResponseMetadata(
                    200,
                    URL,
                    emptyMap(),
                    "application/octet-stream",
                    body.size.toLong(),
                )
                override val body = ByteArrayInputStream(body)
                override fun close() = Unit
            }
            override fun cancel() = Unit
        }
    }

    private companion object {
        const val URL = "https://files.pythonhosted.org/packages/demo-1.0-py3-none-any.whl"
    }
}

