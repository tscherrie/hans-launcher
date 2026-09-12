package ai.hans.standard.runtime

import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Product-wide launch policy for every personal V1 App Server process, including automations. */
internal object CodexAppServerV1Policy {
    fun strictPersonalAssistantArguments(): List<String> = CodexAssistantProfile.strictArguments()
}

internal object CodeModeHostContract {
    const val EXECUTABLE_NAME = "libcodex_code_mode_host.so"
    const val START_TIMEOUT_SECONDS = 10L
    const val STOP_TIMEOUT_SECONDS = 5L
    private const val MAX_ENDPOINT_BYTES = 256

    fun executableFile(nativeLibraryDir: String): File {
        val directory = File(nativeLibraryDir)
        require(directory.isAbsolute) { "nativeLibraryDir must be absolute" }
        return File(directory, EXECUTABLE_NAME)
    }

    fun launchArguments(): List<String> = listOf("--listen", "grpc://127.0.0.1:0")

    /**
     * Personal Hans sessions use and generate Codex long-term memories. Strict parsing makes a
     * future removed/renamed option fail startup instead of silently reporting a false state.
     */
    fun appServerArguments(endpoint: String): List<String> =
        CodexAppServerV1Policy.strictPersonalAssistantArguments() + listOf(
            "--code-mode-host",
            validateEndpoint(endpoint),
        )

    fun readEndpoint(input: InputStream): String {
        val bytes = ArrayList<Byte>(64)
        while (true) {
            val next = input.read()
            check(next >= 0) { "Code Mode host closed before publishing its endpoint" }
            if (next == '\n'.code) break
            check(bytes.size < MAX_ENDPOINT_BYTES) { "Code Mode host endpoint is oversized" }
            bytes += next.toByte()
        }
        return validateEndpoint(bytes.toByteArray().toString(StandardCharsets.UTF_8).trim())
    }

    fun validateEndpoint(value: String): String {
        val uri = runCatching { URI(value) }
            .getOrElse { throw IllegalArgumentException("Code Mode host returned an invalid endpoint") }
        require(uri.scheme == "http") { "Code Mode host must use local plaintext gRPC" }
        require(uri.host == "127.0.0.1") { "Code Mode host must bind to IPv4 loopback" }
        require(uri.port in 1..65_535) { "Code Mode host returned an invalid port" }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "Code Mode host endpoint contains unsupported URL components"
        }
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") {
            "Code Mode host endpoint contains an unsupported path"
        }
        return "http://127.0.0.1:${uri.port}"
    }
}

/**
 * Owns the official Code Mode helper as a separate, APK-signed native process.
 *
 * Android only extracts native files whose APK names follow the `lib*.so`
 * convention. The upstream App Server normally looks for a sibling named
 * `codex-code-mode-host`, so Hans uses the App Server's supported remote-host
 * option and connects both official binaries over ephemeral loopback-only
 * plaintext gRPC. No executable is copied into app-writable storage.
 */
internal class CodeModeHostSupervisor(
    private val executableProvider: () -> File,
    private val expectedExecutableBytes: Long,
) {
    private val ioExecutor = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "hans-code-mode-host-io").apply { isDaemon = true }
    }

    private var process: Process? = null
    private var endpoint: String? = null
    private var stdoutDrain: Future<*>? = null
    private var stderrDrain: Future<*>? = null

    @Synchronized
    fun endpoint(
        workingDirectory: File,
        environment: Map<String, String>,
    ): String {
        val activeProcess = process
        val activeEndpoint = endpoint
        if (activeProcess?.isAlive == true && activeEndpoint != null) return activeEndpoint

        stopLocked()
        val executable = executableProvider().canonicalFile
        check(executable.isFile && executable.canExecute()) {
            "Packaged Code Mode host is unavailable"
        }
        check(executable.length() == expectedExecutableBytes) {
            "Packaged Code Mode host size does not match the pinned release"
        }

        val started = ProcessBuilder(
            listOf(executable.absolutePath) + CodeModeHostContract.launchArguments(),
        )
            .directory(workingDirectory)
            .also { builder ->
                builder.environment().run {
                    clear()
                    putAll(environment)
                }
            }
            .start()
        process = started
        stderrDrain = ioExecutor.submit { drain(started.errorStream) }
        val endpointFuture = ioExecutor.submit<String> {
            CodeModeHostContract.readEndpoint(started.inputStream)
        }

        return try {
            val published = endpointFuture.get(
                CodeModeHostContract.START_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            check(started.isAlive) { "Code Mode host exited during startup" }
            endpoint = published
            stdoutDrain = ioExecutor.submit { drain(started.inputStream) }
            published
        } catch (exception: TimeoutException) {
            endpointFuture.cancel(true)
            stopLocked()
            throw IllegalStateException("Code Mode host startup timed out", exception)
        } catch (exception: Exception) {
            endpointFuture.cancel(true)
            stopLocked()
            throw IllegalStateException("Code Mode host could not start", exception)
        }
    }

    @Synchronized
    fun shutdown() {
        stopLocked()
        ioExecutor.shutdownNow()
    }

    private fun stopLocked() {
        endpoint = null
        stdoutDrain?.cancel(true)
        stderrDrain?.cancel(true)
        stdoutDrain = null
        stderrDrain = null
        process?.let { child ->
            runCatching { child.outputStream.close() }
            if (child.isAlive) {
                child.destroy()
                if (!child.waitFor(CodeModeHostContract.STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    child.destroyForcibly()
                    child.waitFor(CodeModeHostContract.STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            }
            runCatching { child.inputStream.close() }
            runCatching { child.errorStream.close() }
        }
        process = null
    }

    private fun drain(input: InputStream) {
        input.use { stream ->
            val buffer = ByteArray(8 * 1024)
            while (!Thread.currentThread().isInterrupted && stream.read(buffer) >= 0) {
                // The host's transport is the loopback socket. Stdout/stderr
                // are drained solely to prevent child-process backpressure.
            }
        }
    }
}
