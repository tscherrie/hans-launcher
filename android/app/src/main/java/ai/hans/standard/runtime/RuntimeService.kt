package ai.hans.standard.runtime

import ai.hans.standard.BuildConfig
import ai.hans.standard.runtime.network.RuntimeNetworkStack
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class RuntimeService : Service() {
    private val requestExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hans-runtime-requests").apply { isDaemon = true }
    }
    private val streamExecutor = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "hans-runtime-stream").apply { isDaemon = true }
    }
    private val codexProcessLease = AtomicBoolean(false)
    private val runtimeNetworkStack = RuntimeNetworkStack()
    private lateinit var sessionSupervisor: AppServerSessionSupervisor
    private lateinit var codeModeHostSupervisor: CodeModeHostSupervisor
    private lateinit var bootstrapMaterializer: CodexRuntimeBootstrapMaterializer

    private val binder = object : IRuntimeService.Stub() {
        override fun readNativeMemoryHealth(expectedGeneration: Long): String {
            check(android.os.Binder.getCallingUid() == Process.myUid()) { "Caller not authorized" }
            return sessionSupervisor.readNativeMemoryHealth(expectedGeneration, noBackupFilesDir.canonicalFile)
        }
        override fun runNativeProbe(requestId: Long, callback: IRuntimeProbeCallback) {
            requestExecutor.execute {
                val result = runProbe()
                try {
                    callback.onResult(
                        requestId,
                        Process.myPid(),
                        result.exitCode,
                        result.stdout,
                        result.stderr,
                        result.error,
                    )
                } catch (_: RemoteException) {
                    // The launcher went away; the runtime has no work to retain.
                }
            }
        }

        override fun runCodexReadinessGate(requestId: Long, callback: ICodexRuntimeCallback) {
            requestExecutor.execute {
                val result = if (!codexProcessLease.compareAndSet(false, true)) {
                    CodexGateExecutionResult(error = "A persistent App Server session is active")
                } else {
                    try {
                        runCodexReadinessGate()
                    } finally {
                        codexProcessLease.set(false)
                    }
                }
                try {
                    callback.onResult(
                        requestId,
                        Process.myPid(),
                        result.executablePath,
                        result.executableBytes,
                        result.versionExitCode,
                        result.version,
                        result.versionStdout,
                        result.versionStderr,
                        result.initializeExitCode,
                        result.userAgent,
                        result.codexHome,
                        result.platformFamily,
                        result.platformOs,
                        result.initializeResponseJson,
                        result.initializeStderr,
                        result.timedOut,
                        result.cleanupSucceeded,
                        result.error,
                    )
                } catch (_: RemoteException) {
                    // The launcher went away. Child processes are already gone.
                }
            }
        }

        override fun getSessionProtocolVersion(): Int = AppServerSessionContract.PROTOCOL_VERSION

        override fun startAppServerSession(
            operationId: Long,
            callback: IAppServerSessionCallback,
        ) {
            sessionSupervisor.start(operationId, callback)
        }

        override fun restartAppServerSession(operationId: Long, expectedGeneration: Long) {
            sessionSupervisor.restart(operationId, expectedGeneration)
        }

        override fun sendAppServerFrameChunk(
            generation: Long,
            clientSequence: Long,
            chunkIndex: Int,
            chunkCount: Int,
            totalBytes: Int,
            payload: ByteArray,
        ) {
            sessionSupervisor.acceptClientChunk(
                generation = generation,
                clientSequence = clientSequence,
                chunkIndex = chunkIndex,
                chunkCount = chunkCount,
                totalBytes = totalBytes,
                payload = payload,
            )
        }

        override fun stopAppServerSession(operationId: Long, expectedGeneration: Long) {
            sessionSupervisor.stop(operationId, expectedGeneration)
        }
    }

    override fun onCreate() {
        super.onCreate()
        bootstrapMaterializer = CodexRuntimeBootstrapMaterializer(
            marketplaceRoot = BundledSetupPluginContract
                .marketplaceRootForFilesDirectory(filesDir),
            assets = AndroidBundledPluginAssetSource(assets),
        )
        codeModeHostSupervisor = CodeModeHostSupervisor(
            executableProvider = {
                CodeModeHostContract.executableFile(applicationInfo.nativeLibraryDir)
            },
            expectedExecutableBytes = BuildConfig.CODE_MODE_HOST_BYTES,
        )
        sessionSupervisor = AppServerSessionSupervisor(
            executableProvider = {
                CodexRuntimeContract.executableFile(applicationInfo.nativeLibraryDir)
            },
            directoriesProvider = {
                CodexRuntimeContract.directories(
                    noBackupFilesDirectory = noBackupFilesDir,
                    cacheDirectory = cacheDir,
                ).canonicalizedAndCreated()
            },
            bootstrapProvider = bootstrapMaterializer::materialize,
            environmentProvider = { directories ->
                controlledEnvironment(directories)
            },
            argumentsProvider = { directories, environment ->
                CodeModeHostContract.appServerArguments(
                    codeModeHostSupervisor.endpoint(
                        workingDirectory = directories.workingDirectory,
                        environment = environment,
                    ),
                )
            },
            clientVersion = BuildConfig.VERSION_NAME,
            expectedExecutableBytes = BuildConfig.CODEX_RUNTIME_BYTES,
            runtimePidProvider = { Process.myPid() },
            acquireProcessLease = { codexProcessLease.compareAndSet(false, true) },
            releaseProcessLease = { codexProcessLease.set(false) },
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        sessionSupervisor.shutdown()
        codeModeHostSupervisor.shutdown()
        runtimeNetworkStack.close()
        requestExecutor.shutdownNow()
        streamExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun runCodexReadinessGate(): CodexGateExecutionResult {
        val executable = CodexRuntimeContract.executableFile(applicationInfo.nativeLibraryDir)
        if (!executable.isFile) {
            return CodexGateExecutionResult(
                executablePath = executable.absolutePath,
                error = "Packaged Codex runtime is missing",
            )
        }
        if (!executable.canExecute()) {
            return CodexGateExecutionResult(
                executablePath = executable.absolutePath,
                executableBytes = executable.length(),
                error = "Packaged Codex runtime is not executable",
            )
        }
        if (executable.length() != BuildConfig.CODEX_RUNTIME_BYTES) {
            return CodexGateExecutionResult(
                executablePath = executable.absolutePath,
                executableBytes = executable.length(),
                error = "Packaged Codex runtime size does not match the pinned release",
            )
        }
        val codeModeHost = CodeModeHostContract.executableFile(applicationInfo.nativeLibraryDir)
        if (!codeModeHost.isFile || !codeModeHost.canExecute()) {
            return CodexGateExecutionResult(
                executablePath = executable.absolutePath,
                executableBytes = executable.length(),
                error = "Packaged Code Mode host is unavailable",
            )
        }
        if (codeModeHost.length() != BuildConfig.CODE_MODE_HOST_BYTES) {
            return CodexGateExecutionResult(
                executablePath = executable.absolutePath,
                executableBytes = executable.length(),
                error = "Packaged Code Mode host size does not match the pinned release",
            )
        }

        val directories = try {
            CodexRuntimeContract.directories(noBackupFilesDir, cacheDir).canonicalizedAndCreated()
        } catch (exception: Exception) {
            return CodexGateExecutionResult(
                executablePath = executable.absolutePath,
                executableBytes = executable.length(),
                error = exception.safeMessage(),
            )
        }
        val environment = try {
            bootstrapMaterializer.materialize()
            controlledEnvironment(directories)
        } catch (exception: Exception) {
            return CodexGateExecutionResult(
                executablePath = executable.absolutePath,
                executableBytes = executable.length(),
                error = exception.safeMessage(),
            )
        }

        val versionResult = runFiniteProcess(
            executable = executable,
            arguments = listOf("--version"),
            directories = directories,
            environment = environment,
            timeoutSeconds = CodexRuntimeContract.VERSION_TIMEOUT_SECONDS,
        )
        val parsedVersion = CodexRuntimeContract.parseVersion(versionResult.stdout)
        val versionError = when {
            versionResult.timedOut -> "Codex --version timed out"
            versionResult.exitCode != 0 -> "Codex --version exited with ${versionResult.exitCode}"
            parsedVersion == null -> "Codex returned an invalid version response"
            parsedVersion != BuildConfig.CODEX_RUNTIME_VERSION ->
                "Codex version $parsedVersion does not match ${BuildConfig.CODEX_RUNTIME_VERSION}"
            versionResult.error.isNotEmpty() -> versionResult.error
            else -> ""
        }
        if (versionError.isNotEmpty()) {
            return CodexGateExecutionResult(
                executablePath = executable.absolutePath,
                executableBytes = executable.length(),
                versionExitCode = versionResult.exitCode,
                version = parsedVersion.orEmpty(),
                versionStdout = versionResult.stdout,
                versionStderr = versionResult.stderr,
                timedOut = versionResult.timedOut,
                cleanupSucceeded = versionResult.cleanupSucceeded,
                error = versionError,
            )
        }

        val appServerArguments = try {
            CodeModeHostContract.appServerArguments(
                codeModeHostSupervisor.endpoint(
                    workingDirectory = directories.workingDirectory,
                    environment = environment,
                ),
            )
        } catch (exception: Exception) {
            return CodexGateExecutionResult(
                executablePath = executable.absolutePath,
                executableBytes = executable.length(),
                versionExitCode = versionResult.exitCode,
                version = parsedVersion.orEmpty(),
                versionStdout = versionResult.stdout,
                versionStderr = versionResult.stderr,
                error = exception.safeMessage(),
            )
        }

        val initializeResult = runInitializeProcess(
            executable = executable,
            arguments = appServerArguments,
            directories = directories,
            environment = environment,
        )
        val parsedInitialize = try {
            if (initializeResult.responseJson.isEmpty()) null else {
                CodexRuntimeContract.parseInitializeResponse(
                    responseJson = initializeResult.responseJson,
                    expectedCodexHome = directories.codexHomeDirectory.absolutePath,
                )
            }
        } catch (exception: Exception) {
            return CodexGateExecutionResult(
                executablePath = executable.absolutePath,
                executableBytes = executable.length(),
                versionExitCode = versionResult.exitCode,
                version = parsedVersion.orEmpty(),
                versionStdout = versionResult.stdout,
                versionStderr = versionResult.stderr,
                initializeExitCode = initializeResult.exitCode,
                initializeResponseJson = initializeResult.responseJson,
                initializeStderr = initializeResult.stderr,
                timedOut = initializeResult.timedOut,
                cleanupSucceeded =
                    versionResult.cleanupSucceeded && initializeResult.cleanupSucceeded,
                error = exception.safeMessage(),
            )
        }
        val initializeError = when {
            initializeResult.timedOut -> "Codex initialize timed out"
            initializeResult.error.isNotEmpty() -> initializeResult.error
            initializeResult.exitCode != 0 &&
                initializeResult.exitCode != CodexRuntimeContract.READINESS_GATE_CONTROLLED_STOP ->
                "Codex did not exit cleanly after initialize: ${initializeResult.exitCode}"
            parsedInitialize == null -> "Codex returned no initialize response"
            !initializeResult.cleanupSucceeded -> "Codex process cleanup did not complete"
            else -> ""
        }

        return CodexGateExecutionResult(
            executablePath = executable.absolutePath,
            executableBytes = executable.length(),
            versionExitCode = versionResult.exitCode,
            version = parsedVersion.orEmpty(),
            versionStdout = versionResult.stdout,
            versionStderr = versionResult.stderr,
            initializeExitCode = initializeResult.exitCode,
            userAgent = parsedInitialize?.userAgent.orEmpty(),
            codexHome = parsedInitialize?.codexHome.orEmpty(),
            platformFamily = parsedInitialize?.platformFamily.orEmpty(),
            platformOs = parsedInitialize?.platformOs.orEmpty(),
            initializeResponseJson = initializeResult.responseJson,
            initializeStderr = initializeResult.stderr,
            timedOut = initializeResult.timedOut,
            cleanupSucceeded = versionResult.cleanupSucceeded && initializeResult.cleanupSucceeded,
            error = initializeError,
        )
    }

    private fun runFiniteProcess(
        executable: File,
        arguments: List<String>,
        directories: CodexRuntimeDirectories,
        environment: Map<String, String>,
        timeoutSeconds: Long,
    ): ProcessExecutionResult {
        var process: java.lang.Process? = null
        var stdoutFuture: Future<String>? = null
        var stderrFuture: Future<String>? = null
        return try {
            process = newProcess(executable, arguments, directories, environment)
            process.outputStream.close()
            stdoutFuture = streamExecutor.submit<String> {
                BoundedStreams.readToEnd(process.inputStream, CodexRuntimeContract.MAX_STDOUT_BYTES)
            }
            stderrFuture = streamExecutor.submit<String> {
                BoundedStreams.readToEnd(process.errorStream, CodexRuntimeContract.MAX_STDERR_BYTES)
            }
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                val cleaned = process.terminateAndWait()
                ProcessExecutionResult(timedOut = true, cleanupSucceeded = cleaned)
            } else {
                ProcessExecutionResult(
                    exitCode = process.exitValue(),
                    stdout = stdoutFuture.awaitOutput(),
                    stderr = stderrFuture.awaitOutput(),
                    cleanupSucceeded = !process.isAlive,
                )
            }
        } catch (exception: Exception) {
            val cleaned = process?.terminateAndWait() ?: true
            ProcessExecutionResult(cleanupSucceeded = cleaned, error = exception.safeMessage())
        } finally {
            stdoutFuture?.cancel(true)
            stderrFuture?.cancel(true)
            process?.closeStreams()
        }
    }

    private fun runInitializeProcess(
        executable: File,
        arguments: List<String>,
        directories: CodexRuntimeDirectories,
        environment: Map<String, String>,
    ): InitializeExecutionResult {
        var process: java.lang.Process? = null
        var responseFuture: Future<String>? = null
        var stderrFuture: Future<String>? = null
        return try {
            process = newProcess(executable, arguments, directories, environment)
            responseFuture = streamExecutor.submit<String> {
                BoundedStreams.readJsonRpcResponse(
                    input = process.inputStream,
                    requestId = CodexRuntimeContract.INITIALIZE_REQUEST_ID,
                    maximumTotalBytes = CodexRuntimeContract.MAX_STDOUT_BYTES,
                    maximumLineBytes = CodexRuntimeContract.MAX_JSON_LINE_BYTES,
                )
            }
            stderrFuture = streamExecutor.submit<String> {
                BoundedStreams.readToEnd(process.errorStream, CodexRuntimeContract.MAX_STDERR_BYTES)
            }
            process.outputStream.write(
                (CodexRuntimeContract.initializeRequest(BuildConfig.VERSION_NAME) + "\n")
                    .toByteArray(StandardCharsets.UTF_8),
            )
            process.outputStream.flush()
            val response = responseFuture.get(
                CodexRuntimeContract.INITIALIZE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )

            // The app server does honor stdin EOF, but the MP01 needs a little
            // over five seconds to unwind. Preserve a genuine exit code when
            // it exits within the explicit gate window; only then fall back
            // to the documented controlled-stop sentinel.
            process.outputStream.close()
            val exited = process.waitFor(
                CodexRuntimeContract.READINESS_GATE_GRACEFUL_EXIT_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            val cleaned = exited || process.terminateAndWait()
            InitializeExecutionResult(
                exitCode = if (exited) {
                    process.exitValue()
                } else {
                    CodexRuntimeContract.READINESS_GATE_CONTROLLED_STOP
                },
                responseJson = response,
                stderr = stderrFuture.awaitOutput(),
                cleanupSucceeded = cleaned,
            )
        } catch (_: TimeoutException) {
            val cleaned = process?.terminateAndWait() ?: true
            InitializeExecutionResult(timedOut = true, cleanupSucceeded = cleaned)
        } catch (exception: Exception) {
            val cleaned = process?.terminateAndWait() ?: true
            InitializeExecutionResult(cleanupSucceeded = cleaned, error = exception.safeMessage())
        } finally {
            responseFuture?.cancel(true)
            stderrFuture?.cancel(true)
            process?.closeStreams()
        }
    }

    private fun newProcess(
        executable: File,
        arguments: List<String>,
        directories: CodexRuntimeDirectories,
        environment: Map<String, String>,
    ): java.lang.Process {
        val processBuilder = ProcessBuilder(listOf(executable.absolutePath) + arguments)
            .directory(directories.workingDirectory)
        processBuilder.environment().run {
            clear()
            putAll(environment)
        }
        return processBuilder.start()
    }

    private fun controlledEnvironment(
        directories: CodexRuntimeDirectories,
    ): Map<String, String> = CodexRuntimeContract.controlledEnvironment(
        directories = directories,
        androidRoot = System.getenv("ANDROID_ROOT") ?: "/system",
        androidData = System.getenv("ANDROID_DATA") ?: "/data",
        network = runtimeNetworkStack.restart(directories),
    )

    private fun runProbe(): ProbeExecutionResult {
        val executable = ProbeContract.executableFile(applicationInfo.nativeLibraryDir)
        if (!executable.isFile) {
            return ProbeExecutionResult(error = "Packaged probe is missing: ${executable.absolutePath}")
        }
        if (!executable.canExecute()) {
            return ProbeExecutionResult(error = "Packaged probe is not executable: ${executable.absolutePath}")
        }

        return try {
            val runtimeDirectory =
                File(noBackupFilesDir, "runtime").requirePrivateDirectory().canonicalFile
            val temporaryDirectory =
                File(cacheDir, "runtime-tmp").requirePrivateDirectory().canonicalFile
            val processBuilder = ProcessBuilder(executable.absolutePath, ProbeContract.ARGUMENT)
                .directory(runtimeDirectory)
            processBuilder.environment().run {
                clear()
                putAll(
                    RuntimeEnvironment.sanitized(
                        runtimeDirectory = runtimeDirectory.absolutePath,
                        temporaryDirectory = temporaryDirectory.absolutePath,
                        androidRoot = System.getenv("ANDROID_ROOT") ?: "/system",
                        androidData = System.getenv("ANDROID_DATA") ?: "/data",
                    ),
                )
            }
            val process = processBuilder.start()
            process.outputStream.close()
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            val report = ProbeContract.parse(stdout)
            val error = when {
                exitCode != 0 -> "Probe exited with $exitCode"
                report == null -> "Probe returned an invalid contract payload"
                report.parentPid != Process.myPid() -> "Probe parent PID does not match runtime PID"
                else -> ""
            }
            ProbeExecutionResult(exitCode, stdout, stderr, error)
        } catch (exception: Exception) {
            ProbeExecutionResult(error = exception.safeMessage())
        }
    }

    private fun CodexRuntimeDirectories.canonicalizedAndCreated(): CodexRuntimeDirectories {
        return CodexRuntimeDirectories(
            workingDirectory = workingDirectory.requirePrivateDirectory().canonicalFile,
            homeDirectory = homeDirectory.requirePrivateDirectory().canonicalFile,
            codexHomeDirectory = codexHomeDirectory.requirePrivateDirectory().canonicalFile,
            temporaryDirectory = temporaryDirectory.requirePrivateDirectory().canonicalFile,
        )
    }

    private fun File.requirePrivateDirectory(): File {
        check((isDirectory || mkdirs()) && isDirectory) {
            "Could not create app-private runtime directory: $absolutePath"
        }
        return this
    }

    private fun java.lang.Process.terminateAndWait(): Boolean {
        // Signal protocol EOF first, but keep stdout/stderr open while their
        // bounded drain futures consume the child's final output. Closing the
        // read streams here would turn a successful controlled stop into an
        // ExecutionException in awaitOutput().
        runCatching { outputStream.close() }
        if (!isAlive) return true
        destroy()
        if (waitFor(CodexRuntimeContract.SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return true
        destroyForcibly()
        return waitFor(CodexRuntimeContract.SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun java.lang.Process.closeStreams() {
        runCatching { outputStream.close() }
        runCatching { inputStream.close() }
        runCatching { errorStream.close() }
    }

    private fun Future<String>?.awaitOutput(): String {
        if (this == null) return ""
        return try {
            get(CodexRuntimeContract.SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (exception: ExecutionException) {
            throw exception.cause ?: exception
        }
    }

    private fun Throwable.safeMessage(): String = message ?: javaClass.simpleName

    private data class ProbeExecutionResult(
        val exitCode: Int = -1,
        val stdout: String = "",
        val stderr: String = "",
        val error: String = "",
    )

    private data class ProcessExecutionResult(
        val exitCode: Int = -1,
        val stdout: String = "",
        val stderr: String = "",
        val timedOut: Boolean = false,
        val cleanupSucceeded: Boolean = true,
        val error: String = "",
    )

    private data class InitializeExecutionResult(
        val exitCode: Int = -1,
        val responseJson: String = "",
        val stderr: String = "",
        val timedOut: Boolean = false,
        val cleanupSucceeded: Boolean = true,
        val error: String = "",
    )

    private data class CodexGateExecutionResult(
        val executablePath: String = "",
        val executableBytes: Long = 0L,
        val versionExitCode: Int = -1,
        val version: String = "",
        val versionStdout: String = "",
        val versionStderr: String = "",
        val initializeExitCode: Int = -1,
        val userAgent: String = "",
        val codexHome: String = "",
        val platformFamily: String = "",
        val platformOs: String = "",
        val initializeResponseJson: String = "",
        val initializeStderr: String = "",
        val timedOut: Boolean = false,
        val cleanupSucceeded: Boolean = true,
        val error: String = "",
    )
}
