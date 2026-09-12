package ai.hans.standard

import ai.hans.standard.runtime.CodeModeHostContract
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Proves that the packaged host can initialize V8 and execute a real Code Mode cell as an app UID. */
@RunWith(AndroidJUnit4::class)
class CodeModeHostExecutionGateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun packagedHostExecutesARealCellOverItsPinnedStdioProtocol() {
        val executable = CodeModeHostContract.executableFile(context.applicationInfo.nativeLibraryDir)
        assertTrue(executable.isFile)
        assertTrue(executable.canExecute())

        val process = ProcessBuilder(executable.absolutePath, "--listen", "stdio")
            .directory(context.noBackupFilesDir)
            .start()
        val stderrExecutor = Executors.newSingleThreadExecutor()
        val readExecutor = Executors.newSingleThreadExecutor()
        val stderrDrain = stderrExecutor.submit {
            process.errorStream.use { input ->
                val buffer = ByteArray(8 * 1024)
                while (input.read(buffer) >= 0) Unit
            }
        }

        try {
            writeFrame(
                process.outputStream,
                JSONObject()
                    .put("type", "connection/hello")
                    .put("supportedVersions", JSONArray().put(1))
                    .put("requiredCapabilities", JSONArray())
                    .put("optionalCapabilities", JSONArray()),
            )
            val hello = readFrameWithTimeout(readExecutor, process.inputStream)
            assertEquals("connection/ready", hello.getString("type"))
            assertEquals(1, hello.getInt("selectedVersion"))

            val sessionId = "hans-android-runtime-gate"
            writeFrame(
                process.outputStream,
                operationRequest(
                    id = 1,
                    request = JSONObject()
                        .put("method", "session/open")
                        .put("sessionId", sessionId),
                ),
            )
            assertSuccessfulOperation(readFrameWithTimeout(readExecutor, process.inputStream), 1)

            writeFrame(
                process.outputStream,
                operationRequest(
                    id = 2,
                    request = JSONObject()
                        .put("method", "session/execute")
                        .put("sessionId", sessionId)
                        .put(
                            "request",
                            JSONObject()
                                .put("tool_call_id", "hans-android-runtime-gate")
                                .put("enabled_tools", JSONArray())
                                .put("source", "text('android code mode runtime ready');")
                                .put("yield_time_ms", 5_000)
                                .put("max_output_tokens", 64),
                        ),
                ),
            )

            var sawExecutionStarted = false
            var sawSuccessfulInitialResponse = false
            for (attempt in 0 until 4) {
                val frame = readFrameWithTimeout(readExecutor, process.inputStream)
                when (frame.getString("type")) {
                    "operation/response" -> {
                        if (frame.getLong("id") == 2L) {
                            assertSuccessfulOperation(frame, 2)
                            sawExecutionStarted = true
                        }
                    }
                    "execute/initialResponse" -> {
                        if (frame.getLong("id") == 2L) {
                            assertEquals("ok", frame.getJSONObject("result").getString("status"))
                            sawSuccessfulInitialResponse = true
                        }
                    }
                }
                if (sawExecutionStarted && sawSuccessfulInitialResponse) break
            }
            assertTrue("Code Mode host never acknowledged cell execution", sawExecutionStarted)
            assertTrue("Code Mode host never returned the cell's initial response", sawSuccessfulInitialResponse)
            assertTrue("Code Mode host exited during cell execution", process.isAlive)
        } finally {
            runCatching { process.outputStream.close() }
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
            }
            readExecutor.shutdownNow()
            stderrDrain.cancel(true)
            stderrExecutor.shutdownNow()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }

    private fun operationRequest(id: Long, request: JSONObject): JSONObject =
        JSONObject()
            .put("type", "operation/request")
            .put("id", id)
            .put("request", request)

    private fun assertSuccessfulOperation(frame: JSONObject, expectedId: Long) {
        assertEquals("operation/response", frame.getString("type"))
        assertEquals(expectedId, frame.getLong("id"))
        assertEquals("ok", frame.getJSONObject("result").getString("status"))
    }

    private fun writeFrame(output: OutputStream, value: JSONObject) {
        val payload = value.toString().toByteArray(Charsets.UTF_8)
        val prefix = ByteBuffer.allocate(Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(payload.size)
            .array()
        output.write(prefix)
        output.write(payload)
        output.flush()
    }

    private fun readFrameWithTimeout(
        executor: java.util.concurrent.ExecutorService,
        input: InputStream,
    ): JSONObject = executor.submit<JSONObject> { readFrame(input) }
        .get(30, TimeUnit.SECONDS)

    private fun readFrame(input: InputStream): JSONObject {
        val prefix = ByteArray(Int.SIZE_BYTES)
        readFully(input, prefix)
        val length = ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN).int
        require(length in 1..MAX_FRAME_BYTES) { "Code Mode frame length is invalid" }
        val payload = ByteArray(length)
        readFully(input, payload)
        return JSONObject(payload.toString(Charsets.UTF_8))
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) throw EOFException("Code Mode host closed its protocol stream")
            offset += count
        }
    }

    private companion object {
        const val MAX_FRAME_BYTES = 64 * 1024 * 1024
    }
}
