package ai.hans.standard.automations.androidgateway

import ai.hans.standard.BuildConfig
import ai.hans.standard.codex.ProtocolLimits
import ai.hans.standard.runtime.AppServerSessionContract
import ai.hans.standard.runtime.BoundedJsonLineFramer
import ai.hans.standard.runtime.CodexRuntimeContract
import ai.hans.standard.runtime.CodexRuntimeDirectories
import ai.hans.standard.runtime.CodexAppServerV1Policy
import ai.hans.standard.runtime.JsonLineReadResult
import ai.hans.standard.runtime.network.RuntimeNetworkStack
import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal object AndroidOneShotAppServerProcessContract {
    fun command(executable: File): List<String> =
        listOf(executable.absolutePath) +
            CodexAppServerV1Policy.strictPersonalAssistantArguments()
}

/**
 * Starts the packaged, integrity-pinned App Server as a one-shot child in Android's ordinary app
 * sandbox. The persistent CODEX_HOME is mounted directly as HOME; credentials are never copied to
 * a temporary or externally readable location. Only network CA material and TMPDIR are per-run.
 */
class AndroidOneShotAppServerSessionFactory(
    context: Context,
    private val networkStackFactory: () -> RuntimeNetworkStack = { RuntimeNetworkStack() },
) : OneShotAppServerSessionFactory, AutoCloseable {
    private val appContext = context.applicationContext
    private val executable = CodexRuntimeContract.executableFile(
        appContext.applicationInfo.nativeLibraryDir,
    )
    private val mainDirectories = CodexRuntimeContract.directories(
        noBackupFilesDirectory = appContext.noBackupFilesDir,
        cacheDirectory = appContext.cacheDir,
    )
    private val sessionsRoot = File(appContext.cacheDir, SESSIONS_DIRECTORY_NAME)
    private val executableVerified = AtomicBoolean(false)
    private val active = AtomicBoolean(false)
    private val activeSession = AtomicReference<OneShotAppServerSession?>(null)
    private val closed = AtomicBoolean(false)

    override fun open(): OneShotAppServerSession {
        if (closed.get()) throw retryable("codex_gateway_closed")
        if (!active.compareAndSet(false, true)) throw retryable("codex_independent_busy")
        var root: File? = null
        var network: RuntimeNetworkStack? = null
        var process: Process? = null
        try {
            verifyExecutable()
            verifyPersistentHomeAndLogin()
            sweepAbandonedSessions()
            root = File(sessionsRoot, UUID.randomUUID().toString())
            val transientDirectories = createTransientDirectories(root)
            network = networkStackFactory()
            val networkEnvironment = network.restart(transientDirectories)
            val environment = CodexRuntimeContract.controlledEnvironment(
                directories = transientDirectories,
                androidRoot = System.getenv("ANDROID_ROOT") ?: "/system",
                androidData = System.getenv("ANDROID_DATA") ?: "/data",
                network = networkEnvironment,
            )
            process = ProcessBuilder(AndroidOneShotAppServerProcessContract.command(executable))
                .directory(mainDirectories.workingDirectory)
                .also { builder ->
                    builder.environment().run {
                        clear()
                        putAll(environment)
                    }
                }
                .start()
            lateinit var created: ProcessOneShotAppServerSession
            created = ProcessOneShotAppServerSession(
                process = process,
                network = network,
                transientRoot = root,
                sessionsRoot = sessionsRoot,
                expectedCodexHome = mainDirectories.codexHomeDirectory,
                workspaceDirectory = mainDirectories.workingDirectory,
                onClosed = {
                    activeSession.compareAndSet(created, null)
                    active.set(false)
                },
            )
            activeSession.set(created)
            if (closed.get()) {
                runCatching(created::close)
                throw retryable("codex_gateway_closed")
            }
            return created
        } catch (failure: AutomationGatewayFailure) {
            runCatching { process?.destroyForcibly() }
            runCatching { network?.close() }
            root?.let { runCatching { deleteSessionRoot(it, sessionsRoot) } }
            active.set(false)
            throw failure
        } catch (_: Exception) {
            runCatching { process?.destroyForcibly() }
            runCatching { network?.close() }
            root?.let { runCatching { deleteSessionRoot(it, sessionsRoot) } }
            active.set(false)
            throw retryable("codex_process_start_failed")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeSession.getAndSet(null)?.let { runCatching(it::close) }
    }

    private fun verifyExecutable() {
        if (executableVerified.get()) return
        synchronized(executableVerified) {
            if (executableVerified.get()) return
            val path = executable.toPath()
            if (
                !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
                !executable.canExecute() ||
                executable.length() != BuildConfig.CODEX_RUNTIME_BYTES
            ) {
                throw permanent("codex_runtime_integrity_failed")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(executable).use { input ->
                val buffer = ByteArray(32 * 1_024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
            if (actual != BuildConfig.CODEX_RUNTIME_SHA256) {
                throw permanent("codex_runtime_integrity_failed")
            }
            executableVerified.set(true)
        }
    }

    private fun verifyPersistentHomeAndLogin() {
        listOf(
            mainDirectories.workingDirectory,
            mainDirectories.homeDirectory,
            mainDirectories.codexHomeDirectory,
            mainDirectories.temporaryDirectory,
        ).forEach(::ensurePrivateDirectory)
        val auth = File(mainDirectories.codexHomeDirectory, AUTH_FILE_NAME)
        if (
            !Files.isRegularFile(auth.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            auth.length() !in 1..MAX_AUTH_BYTES.toLong()
        ) {
            throw retryable("codex_login_required")
        }
    }

    private fun createTransientDirectories(root: File): CodexRuntimeDirectories {
        ensurePrivateDirectory(sessionsRoot)
        if (!SESSION_ID_PATTERN.matches(root.name) || root.parentFile != sessionsRoot) {
            throw permanent("codex_session_path_invalid")
        }
        ensurePrivateDirectory(root)
        val networkWork = File(root, "network-work")
        val temporary = File(root, "tmp")
        ensurePrivateDirectory(networkWork)
        ensurePrivateDirectory(temporary)
        return CodexRuntimeDirectories(
            workingDirectory = networkWork,
            homeDirectory = mainDirectories.homeDirectory,
            codexHomeDirectory = mainDirectories.codexHomeDirectory,
            temporaryDirectory = temporary,
        )
    }

    private fun ensurePrivateDirectory(directory: File) {
        val path = directory.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!directory.mkdirs() && !directory.isDirectory) {
                throw retryable("codex_directory_unavailable")
            }
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw permanent("codex_directory_invalid")
        }
        runCatching { Files.setPosixFilePermissions(path, OWNER_DIRECTORY_MODE) }
    }

    private fun sweepAbandonedSessions(now: Long = System.currentTimeMillis()) {
        if (!Files.exists(sessionsRoot.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        if (!Files.isDirectory(sessionsRoot.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw permanent("codex_session_path_invalid")
        }
        sessionsRoot.listFiles().orEmpty().forEach { child ->
            if (
                SESSION_ID_PATTERN.matches(child.name) &&
                child.lastModified() <= now - ABANDONED_AFTER_MILLIS
            ) {
                if (!deleteSessionRoot(child, sessionsRoot)) {
                    throw retryable("codex_session_cleanup_failed")
                }
            }
        }
    }

    private companion object {
        const val AUTH_FILE_NAME = "auth.json"
        const val MAX_AUTH_BYTES = 1 * 1_024 * 1_024
        const val SESSIONS_DIRECTORY_NAME = "automation-codex-sessions"
        const val ABANDONED_AFTER_MILLIS = 24 * 60 * 60 * 1_000L
        val SESSION_ID_PATTERN =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
        val OWNER_DIRECTORY_MODE = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )

        fun retryable(code: String) = AutomationGatewayFailure(code, retryable = true)
        fun permanent(code: String) = AutomationGatewayFailure(code, retryable = false)
    }
}

/** Central construction seam used by HansApplication without forcing its lazy session host. */
object AndroidAutomationCodexGatewayFactory {
    fun create(
        context: Context,
        existingThreadProvider: () -> ExistingThreadAutomationAdapter,
        selectionSource: IndependentCodexSelectionSource,
        dynamicToolsSource: AutomationDynamicToolExecutorSource,
        developerInstructionsSource: AutomationDeveloperInstructionsSource =
            AutomationDeveloperInstructionsSource.EMPTY,
        timeouts: AutomationGatewayTimeouts = AutomationGatewayTimeouts(),
    ): AndroidAutomationCodexGateway {
        val independent = OneShotIndependentAutomationCodexRunner(
            sessionFactory = AndroidOneShotAppServerSessionFactory(context),
            selectionSource = selectionSource,
            dynamicToolsSource = dynamicToolsSource,
            developerInstructions = developerInstructionsSource,
            timeouts = timeouts,
            clientVersion = BuildConfig.VERSION_NAME,
        )
        return AndroidAutomationCodexGateway(
            existingThread = LazyExistingThreadAutomationAdapter(existingThreadProvider),
            independent = independent,
            timeouts = timeouts,
        )
    }
}

private class ProcessOneShotAppServerSession(
    private val process: Process,
    private val network: RuntimeNetworkStack,
    private val transientRoot: File,
    private val sessionsRoot: File,
    override val expectedCodexHome: File,
    override val workspaceDirectory: File,
    private val onClosed: () -> Unit,
) : OneShotAppServerSession {
    private val closed = AtomicBoolean(false)
    private val terminalSignalSent = AtomicBoolean(false)
    private val inbound = ArrayBlockingQueue<OneShotAppServerInbound>(MAX_QUEUED_FRAMES)
    private val writer = BufferedOutputStream(process.outputStream, 32 * 1_024)
    private val readers: ExecutorService = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "hans-automation-app-server-io").apply { isDaemon = true }
    }

    init {
        readers.execute(::readStdout)
        readers.execute(::drainStderr)
    }

    override fun send(frame: String) {
        if (closed.get()) throw IllegalStateException("codex_session_closed")
        val bytes = frame.toByteArray(StandardCharsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > ProtocolLimits.MAX_OUTBOUND_FRAME_BYTES) {
            throw IllegalArgumentException("codex_outbound_frame_invalid")
        }
        synchronized(writer) {
            writer.write(bytes)
            writer.write('\n'.code)
            writer.flush()
        }
    }

    override fun poll(maximumWaitMillis: Long): OneShotAppServerInbound? {
        require(maximumWaitMillis > 0)
        return inbound.poll(maximumWaitMillis, TimeUnit.MILLISECONDS)
    }

    override fun requestCancellation() {
        inbound.clear()
        signalTerminal(OneShotAppServerInbound.Eof)
        runCatching { process.destroy() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var cleanupSucceeded = false
        runCatching { synchronized(writer) { writer.close() } }
        if (process.isAlive) {
            process.destroy()
            if (!runCatching { process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)) {
                process.destroyForcibly()
                runCatching { process.waitFor(2, TimeUnit.SECONDS) }
            }
        }
        val processStopped = !process.isAlive
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        readers.shutdownNow()
        runCatching { readers.awaitTermination(2, TimeUnit.SECONDS) }
        runCatching { network.close() }
        try {
            cleanupSucceeded = deleteSessionRoot(transientRoot, sessionsRoot)
        } finally {
            onClosed()
        }
        when {
            !processStopped -> throw AutomationGatewayFailure(
                "codex_process_cleanup_failed",
                retryable = true,
            )
            !cleanupSucceeded -> throw AutomationGatewayFailure(
                "codex_session_cleanup_failed",
                retryable = true,
            )
        }
    }

    private fun readStdout() {
        try {
            BufferedInputStream(process.inputStream, 32 * 1_024).use { input ->
                val framer = BoundedJsonLineFramer(AppServerSessionContract.MAX_SERVER_FRAME_BYTES)
                while (!closed.get()) {
                    when (val result = framer.readNext(input)) {
                        is JsonLineReadResult.Frame -> {
                            val raw = decodeUtf8(result.bytes) ?: run {
                                signalTerminal(OneShotAppServerInbound.Failed)
                                return
                            }
                            if (!inbound.offer(OneShotAppServerInbound.Frame(raw))) {
                                signalQueueOverflow()
                                return
                            }
                        }
                        is JsonLineReadResult.Oversized -> {
                            signalTerminal(OneShotAppServerInbound.Oversized)
                            return
                        }
                        is JsonLineReadResult.PartialEof -> {
                            signalTerminal(OneShotAppServerInbound.Failed)
                            return
                        }
                        JsonLineReadResult.Eof -> {
                            signalTerminal(OneShotAppServerInbound.Eof)
                            return
                        }
                    }
                }
            }
        } catch (_: Exception) {
            if (!closed.get()) signalTerminal(OneShotAppServerInbound.Failed)
        }
    }

    private fun drainStderr() {
        try {
            process.errorStream.use { input ->
                val buffer = ByteArray(8 * 1_024)
                var total = 0L
                while (!closed.get()) {
                    val count = input.read(buffer)
                    if (count < 0) return
                    total += count
                    if (total > MAX_STDERR_BYTES) {
                        signalTerminal(OneShotAppServerInbound.Failed)
                        runCatching { process.destroyForcibly() }
                        return
                    }
                }
            }
        } catch (_: Exception) {
            if (!closed.get()) signalTerminal(OneShotAppServerInbound.Failed)
        }
    }

    private fun signalQueueOverflow() {
        inbound.clear()
        signalTerminal(OneShotAppServerInbound.Failed)
        runCatching { process.destroyForcibly() }
    }

    private fun signalTerminal(value: OneShotAppServerInbound) {
        if (!terminalSignalSent.compareAndSet(false, true)) return
        if (!inbound.offer(value)) {
            inbound.clear()
            inbound.offer(OneShotAppServerInbound.Failed)
            runCatching { process.destroyForcibly() }
        }
    }

    private companion object {
        const val MAX_QUEUED_FRAMES = 256
        const val MAX_STDERR_BYTES = 1L * 1_024 * 1_024

        fun decodeUtf8(bytes: ByteArray): String? = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
    }
}

private fun deleteSessionRoot(root: File, expectedParent: File): Boolean = runCatching {
    if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return@runCatching true
    val parent = root.parentFile
    if (
        parent == null ||
        parent.canonicalFile != expectedParent.canonicalFile ||
        !root.name.matches(
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"),
        )
    ) {
        return@runCatching false
    }
    Files.walkFileTree(
        root.toPath(),
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(
                dir: Path,
                exc: java.io.IOException?,
            ): FileVisitResult {
                if (exc != null) throw exc
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        },
    )
    !Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)
}.getOrDefault(false)
