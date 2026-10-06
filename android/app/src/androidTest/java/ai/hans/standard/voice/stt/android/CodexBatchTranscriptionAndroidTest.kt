package ai.hans.standard.voice.stt.android

import ai.hans.standard.voice.PcmAudioChunk
import ai.hans.standard.voice.RecordingId
import ai.hans.standard.voice.RecordingProgressListener
import ai.hans.standard.voice.stt.BatchTranscriptionCancellation
import ai.hans.standard.voice.stt.CodexBatchTranscriptionFailure
import ai.hans.standard.voice.stt.CodexBatchTranscriptionGateway
import ai.hans.standard.voice.stt.CodexBatchTranscriptionProvider
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Offline only: sealed APK executable, empty fixture auth directory, and synthetic PCM. */
@RunWith(AndroidJUnit4::class)
class CodexBatchTranscriptionAndroidTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun packagedHelperRunsFromSealedNativeLibraryDirectory() {
        val helper = File(context.applicationInfo.nativeLibraryDir,
            AndroidCodexBatchTranscriptionGateway.EXECUTABLE_NAME)
        assertTrue("Missing sealed transcription helper", helper.isFile)
        assertTrue("Transcription helper is not executable", helper.canExecute())
        assertEquals(File(context.applicationInfo.nativeLibraryDir).canonicalFile, helper.canonicalFile.parentFile)
        val result = runOfflineHelper("--version")
        assertEquals(0, result.exitCode)
        assertEquals("hans-codex-transcribe 0.1.0", result.stdout.trim())
        assertTrue(result.stderr.isEmpty())
    }

    @Test fun emptyOwnedCodexHomeHasNoAuthenticationAndNoBackendRequest() {
        val result = runOfflineHelper("--check-auth")
        assertFailure(result, "codex_transcription_auth_missing")
    }

    @Test fun malformedAudioIsRejectedBeforeEmptyHomeAuthentication() {
        val result = runOfflineHelper("--transcribe", input = "not a WAV".toByteArray(Charsets.US_ASCII))
        assertFailure(result, "codex_transcription_invalid_audio")
    }

    @Test fun forbiddenRefreshOverrideIsRejectedWithoutOpeningAnyConnection() {
        val result = runOfflineHelper("--check-auth", extraEnvironment = mapOf(
            "CODEX_REFRESH_TOKEN_URL_OVERRIDE" to "https://example.invalid/not-used",
        ))
        assertFailure(result, "codex_transcription_unsupported_environment")
    }

    @Test fun syntheticRecordingCompletesOnlyOnceAndTheNextRecordingIsIndependent() {
        val gateway = FakeGateway()
        CodexBatchTranscriptionProvider(gateway).use { provider ->
            val firstId = RecordingId(6101)
            val first = provider.openSession(firstId, CodexBatchTranscriptionProvider.FORMAT,
                RecordingProgressListener {})
            submitSyntheticPcm(firstId, first::submitChunk)
            assertTrue(gateway.requests.isEmpty())
            val firstResults = mutableListOf<Result<String>>()
            val duplicateResults = mutableListOf<Result<String>>()
            first.finish(firstResults::add)
            first.finish(duplicateResults::add)
            assertEquals(1, gateway.requests.size)
            assertEquals(48_044, gateway.requests[0].byteCount)
            gateway.requests[0].reply(Result.success("Open settings"))
            gateway.requests[0].reply(Result.success("Duplicate must not be delivered"))
            assertEquals("Open settings", firstResults.single().getOrThrow())
            assertTrue(duplicateResults.isEmpty())
            assertTrue(gateway.requests[0].wav.all { it == 0.toByte() })

            val secondId = RecordingId(6102)
            val second = provider.openSession(secondId, CodexBatchTranscriptionProvider.FORMAT,
                RecordingProgressListener {})
            submitSyntheticPcm(secondId, second::submitChunk)
            val secondResults = mutableListOf<Result<String>>()
            second.finish(secondResults::add)
            gateway.requests[0].reply(Result.success("Stale first recording"))
            gateway.requests[1].reply(Result.success("Current recording"))
            assertEquals(2, gateway.requests.size)
            assertEquals("Open settings", firstResults.single().getOrThrow())
            assertEquals("Current recording", secondResults.single().getOrThrow())
        }
    }

    @Test fun networkLossKeepsLocalCaptureButCancelsAnUploadExactlyOnce() {
        val gateway = FakeGateway()
        CodexBatchTranscriptionProvider(gateway).use { provider ->
            val id = RecordingId(6103)
            val session = provider.openSession(id, CodexBatchTranscriptionProvider.FORMAT,
                RecordingProgressListener {})
            provider.onNetworkUnavailable()
            submitSyntheticPcm(id, session::submitChunk)
            assertTrue(gateway.requests.isEmpty())
            val results = mutableListOf<Result<String>>()
            session.finish(results::add)
            val request = gateway.requests.single()
            provider.onNetworkUnavailable()
            provider.onNetworkUnavailable()
            request.reply(Result.success("Late text must not be delivered"))
            assertEquals(1, request.cancellations)
            assertEquals("codex_transcription_network_unavailable",
                (results.single().exceptionOrNull() as CodexBatchTranscriptionFailure).code)
            assertTrue(request.wav.all { it == 0.toByte() })
            provider.openSession(RecordingId(6104), CodexBatchTranscriptionProvider.FORMAT,
                RecordingProgressListener {}).cancel()
        }
    }

    private fun submitSyntheticPcm(
        id: RecordingId,
        submit: (PcmAudioChunk, (Result<Unit>) -> Unit) -> Unit,
    ) {
        val pcm = ByteArray(48_000) { (it % 13).toByte() }
        submit(PcmAudioChunk.create(id, 0, pcm, isFinal = true, capturedAtMillis = 0)) {
            assertTrue(it.isSuccess)
        }
    }

    private data class HelperResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runOfflineHelper(
        command: String,
        input: ByteArray = byteArrayOf(),
        extraEnvironment: Map<String, String> = emptyMap(),
    ): HelperResult {
        val fixture = Files.createTempDirectory(context.cacheDir.toPath(), "batch-transcribe-offline-").toFile()
        val emptyCodexHome = File(fixture, "empty-codex-home")
        assertTrue(emptyCodexHome.mkdir())
        assertTrue(emptyCodexHome.listFiles().orEmpty().isEmpty())
        var child: Process? = null
        try {
            val helper = File(context.applicationInfo.nativeLibraryDir,
                AndroidCodexBatchTranscriptionGateway.EXECUTABLE_NAME)
            val builder = ProcessBuilder(helper.absolutePath, command).directory(fixture)
            builder.environment().clear()
            builder.environment().putAll(mapOf(
                "CODEX_HOME" to emptyCodexHome.absolutePath,
                "HOME" to fixture.absolutePath,
                "TMPDIR" to fixture.absolutePath,
                "PATH" to "/system/bin",
                "ANDROID_ROOT" to "/system",
                "ANDROID_DATA" to "/data",
            ) + extraEnvironment)
            child = builder.start()
            child.outputStream.use { it.write(input) }
            assertTrue("Offline helper exceeded its 12-second deadline", child.waitFor(12, TimeUnit.SECONDS))
            val result = HelperResult(child.exitValue(), readBounded(child.inputStream), readBounded(child.errorStream))
            // Only this freshly-created directory is inspected; the application's real login
            // directory is never selected, opened, copied or modified by these tests.
            assertFalse(File(emptyCodexHome, "auth.json").exists())
            return result
        } finally {
            child?.let { process ->
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor(2, TimeUnit.SECONDS)
                }
                runCatching { process.outputStream.close() }
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
            }
            // Exact owned fixture only; never delete cacheDir or any application auth directory.
            assertEquals(context.cacheDir.canonicalFile, fixture.canonicalFile.parentFile)
            assertTrue(fixture.name.startsWith("batch-transcribe-offline-"))
            assertTrue("Owned offline fixture could not be removed", fixture.deleteRecursively())
        }
    }

    private fun readBounded(input: InputStream): String = input.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            assertTrue("Unexpected helper output size", output.size() + count <= 4096)
            output.write(buffer, 0, count)
        }
        output.toString(Charsets.UTF_8.name())
    }

    private fun assertFailure(result: HelperResult, code: String) {
        assertEquals(1, result.exitCode)
        val protocol = JSONObject(result.stdout)
        assertEquals(setOf("error"), protocol.keys().asSequence().toSet())
        assertEquals(code, protocol.getString("error"))
        assertTrue(result.stderr.isEmpty())
    }

    private class FakeGateway : CodexBatchTranscriptionGateway {
        val requests = mutableListOf<Request>()
        override fun transcribe(wav: ByteArray, callback: (Result<String>) -> Unit): BatchTranscriptionCancellation =
            Request(wav, callback).also(requests::add)
    }

    private class Request(val wav: ByteArray, val reply: (Result<String>) -> Unit) : BatchTranscriptionCancellation {
        val byteCount = wav.size
        var cancellations = 0
        override fun cancel() { cancellations++ }
    }
}
