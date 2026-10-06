package ai.hans.standard.voice.stt.android

import ai.hans.standard.runtime.CodexRuntimeContract
import ai.hans.standard.runtime.network.RuntimeNetworkStack
import ai.hans.standard.voice.stt.BatchTranscriptionCancellation
import ai.hans.standard.voice.stt.CodexBatchTranscriptionFailure
import ai.hans.standard.voice.stt.CodexBatchTranscriptionGateway
import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Sealed APK-native executable owns Codex authentication and HTTP. No credentials cross stdout. */
class AndroidCodexBatchTranscriptionGateway(
    context: Context,
    private val admissionStillValid: () -> Boolean = { true },
) : CodexBatchTranscriptionGateway, AutoCloseable {
    private val appContext = context.applicationContext
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "hans-batch-transcription").apply { isDaemon = true }
    }
    private val deadline = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "hans-batch-transcription-deadline").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val active = LinkedHashSet<Operation>()
    private val executable: File get() = File(appContext.applicationInfo.nativeLibraryDir, EXECUTABLE_NAME)

    fun isAvailable(): Boolean = !closed.get() && executable.isFile && executable.canExecute()

    override fun transcribe(wav: ByteArray, callback: (Result<String>) -> Unit): BatchTranscriptionCancellation {
        val operation = Operation(wav, callback)
        synchronized(lock) {
            if (closed.get() || !isAvailable()) {
                operation.complete(failure("unavailable"))
                operation.cleanup()
                return operation
            }
            active.add(operation)
        }
        try { executor.execute(operation::run) }
        catch (_: RuntimeException) { operation.complete(failure("unavailable")); operation.cleanup() }
        return operation
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val requests = synchronized(lock) { active.toList().also { active.clear() } }
        requests.forEach(Operation::cancel)
        executor.shutdownNow()
        deadline.shutdownNow()
    }

    private inner class Operation(private val wav: ByteArray, private val callback: (Result<String>) -> Unit) :
        BatchTranscriptionCancellation {
        private val terminal = AtomicBoolean(false)
        private val process = AtomicReference<Process?>()
        private val network = RuntimeNetworkStack()

        fun run() {
            var timer: java.util.concurrent.ScheduledFuture<*>? = null
            try {
                if (terminal.get()) return
                timer = deadline.schedule({ complete(failure("timeout")) }, DEADLINE_SECONDS, TimeUnit.SECONDS)
                if (!admissionStillValid()) { complete(failure("auth_changed")); return }
                val base = CodexRuntimeContract.directories(appContext.noBackupFilesDir, appContext.cacheDir)
                // Independent proxy lifetime and CA materialization; never retire App Server's proxy.
                val directories = base.copy(workingDirectory = File(base.workingDirectory, "batch-transcription"))
                check(directories.workingDirectory.mkdirs() || directories.workingDirectory.isDirectory)
                val env = CodexRuntimeContract.controlledEnvironment(directories,
                    System.getenv("ANDROID_ROOT") ?: "/system", System.getenv("ANDROID_DATA") ?: "/data",
                    network.restart(directories))
                if (terminal.get()) return
                val child = ProcessBuilder(executable.absolutePath, "--transcribe")
                    .directory(directories.workingDirectory).also { builder ->
                        builder.environment().clear()
                        builder.environment().putAll(env)
                    }.start()
                process.set(child)
                if (terminal.get()) { terminate(); return }
                val stderr = executor.submit<Boolean> {
                    try {
                        var count = 0
                        val discard = ByteArray(4096)
                        while (true) {
                            val read = child.errorStream.read(discard)
                            if (read < 0) break
                            count += read
                            if (count > MAX_RESPONSE_BYTES) {
                                complete(failure("response_invalid")); return@submit false
                            }
                        }
                        true
                    } catch (_: Exception) { false }
                }
                child.outputStream.use { it.write(wav); it.flush() }
                val response = readBounded(child.inputStream)
                if (!child.waitFor(2, TimeUnit.SECONDS)) { complete(failure("timeout")); return }
                if (!stderr.get(2, TimeUnit.SECONDS)) { complete(failure("response_invalid")); return }
                if (!admissionStillValid()) { complete(failure("auth_changed")); return }
                complete(BatchTranscriptionWire.decode(response, child.exitValue()))
            } catch (_: Exception) {
                complete(failure("unavailable"))
            } finally {
                timer?.cancel(false)
                cleanup()
            }
        }

        fun complete(result: Result<String>) {
            if (!terminal.compareAndSet(false, true)) return
            terminate()
            runCatching { callback(result) }
        }

        override fun cancel() {
            terminal.set(true)
            cleanup()
        }

        fun cleanup() {
            terminate()
            network.close()
            wav.fill(0)
            synchronized(lock) { active.remove(this) }
        }

        private fun terminate() {
            process.getAndSet(null)?.let { child ->
                runCatching { child.destroyForcibly() }
                runCatching { child.outputStream.close() }
                runCatching { child.inputStream.close() }
                runCatching { child.errorStream.close() }
            }
        }
    }

    companion object {
        const val EXECUTABLE_NAME = "libcodex_transcribe.so"
        private const val DEADLINE_SECONDS = 40L
        private const val MAX_RESPONSE_BYTES = 65_536
        private fun failure(suffix: String): Result<String> =
            Result.failure(CodexBatchTranscriptionFailure("codex_transcription_$suffix"))
        private fun readBounded(input: InputStream): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) return output.toByteArray()
                require(output.size() + count <= MAX_RESPONSE_BYTES)
                output.write(buffer, 0, count)
            }
        }
    }
}

/** Fail closed on extra fields/lines or a malformed native response; never propagate raw stderr. */
internal object BatchTranscriptionWire {
    fun decode(bytes: ByteArray, exitCode: Int): Result<String> = try {
        require(bytes.size <= 65_536)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().trim()
        require(text.length <= 65_536 && !text.contains('\n') && !text.contains('\r'))
        require(singleStringObject(text))
        val objectValue = JSONObject(text)
        require(objectValue.length() == 1)
        if (exitCode == 0) {
            val transcript = objectValue.get("text") as? String ?: error("invalid")
            require(transcript.isNotBlank() && transcript.length <= 16_000)
            Result.success(transcript)
        } else {
            val code = objectValue.get("error") as? String ?: error("invalid")
            Result.failure(CodexBatchTranscriptionFailure(code))
        }
    } catch (_: Exception) {
        Result.failure(CodexBatchTranscriptionFailure("codex_transcription_response_invalid"))
    }

    /** Linear, iterative validation; recursive regexes overflow on long valid transcripts. */
    private fun singleStringObject(text: String): Boolean {
        var index = 0
        fun spaces() { while (index < text.length && (text[index] == ' ' || text[index] == '\t')) index++ }
        fun consume(char: Char): Boolean = if (index < text.length && text[index] == char) {
            index++; true
        } else false
        if (!consume('{')) return false
        spaces()
        val key = when {
            text.startsWith("\"text\"", index) -> "\"text\""
            text.startsWith("\"error\"", index) -> "\"error\""
            else -> return false
        }
        index += key.length
        spaces()
        if (!consume(':')) return false
        spaces()
        if (!consume('"')) return false
        var ended = false
        while (index < text.length) {
            val char = text[index++]
            if (char == '"') { ended = true; break }
            if (char.code < 0x20) return false
            if (char != '\\') continue
            if (index >= text.length) return false
            when (text[index++]) {
                '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                'u' -> {
                    if (index + 4 > text.length) return false
                    repeat(4) {
                        if (text[index++] !in "0123456789abcdefABCDEF") return false
                    }
                }
                else -> return false
            }
        }
        if (!ended) return false
        spaces()
        return consume('}') && index == text.length
    }
}
