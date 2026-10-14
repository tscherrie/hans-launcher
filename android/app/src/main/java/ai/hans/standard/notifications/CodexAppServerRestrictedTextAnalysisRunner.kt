package ai.hans.standard.notifications

import ai.hans.standard.BuildConfig
import ai.hans.standard.codex.ReasoningEffort
import ai.hans.standard.runtime.BoundedJsonLineFramer
import ai.hans.standard.runtime.CodexRuntimeContract
import ai.hans.standard.runtime.CodexRuntimeDirectories
import ai.hans.standard.runtime.JsonLineReadResult
import ai.hans.standard.runtime.network.RuntimeNetworkStack
import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** Deliberately not a data class: prompts and untrusted evidence never appear in toString(). */
internal class RestrictedTextAnalysisRequest(
    val baseInstructions: String,
    val developerInstructions: String,
    val userInput: String,
) {
    override fun toString() = "RestrictedTextAnalysisRequest(private=true)"
}

/**
 * Shared one-shot, text-only App Server runner. Notifications and routine analysis use exactly
 * the same isolated auth projection, strict tool-free configuration and correlated final frames.
 */
internal class CodexAppServerRestrictedTextAnalysisRunner(
    context: Context,
    private val model: String = DEFAULT_MODEL,
    private val effort: ReasoningEffort = ReasoningEffort.LOW,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    private val maximumResponseBytes: Int = NotificationTriageBounds.MAX_MODEL_RESPONSE_BYTES,
) : Closeable {
    private val appContext = context.applicationContext
    private val timeoutExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "hans-notification-triage-timeout").apply { isDaemon = true }
    }
    private val executable = CodexRuntimeContract.executableFile(appContext.applicationInfo.nativeLibraryDir)
    private val mainDirectories = CodexRuntimeContract.directories(
        noBackupFilesDirectory = appContext.noBackupFilesDir,
        cacheDirectory = appContext.cacheDir,
    )
    private val authSource = File(mainDirectories.codexHomeDirectory, AUTH_FILE_NAME)
    private val executableVerified = AtomicBoolean(false)
    private val workspacePrepared = AtomicBoolean(false)
    private val operationGate = RestrictedTextAnalysisOperationGate()
    private val processFence = RestrictedTriageProcessFence(
        ensureCurrent = ::ensureNotPreempted,
        abort = ::abortImmediately,
    )

    init {
        require(timeoutSeconds in 10..120) { "Restricted triage timeout is invalid" }
        require(maximumResponseBytes in 1..32 * 1_024) { "Restricted response bound is invalid" }
    }

    fun runText(request: RestrictedTextAnalysisRequest, expectedAccountFingerprint: String? = null): String =
        withOperation { epoch -> runText(request, epoch, expectedAccountFingerprint) }

    internal fun runText(
        request: RestrictedTextAnalysisRequest,
        startingPreemptionEpoch: Long,
        expectedAccountFingerprint: String? = null,
    ): String {
        ensureNotPreempted(startingPreemptionEpoch)
        ensureAccount(expectedAccountFingerprint)
        return runIsolatedStage(request, startingPreemptionEpoch, expectedAccountFingerprint).also {
            ensureNotPreempted(startingPreemptionEpoch)
            ensureAccount(expectedAccountFingerprint)
        }
    }

    internal fun <T> withOperation(block: (Long) -> T): T {
        return operationGate.withOperation { startingPreemptionEpoch ->
            verifyExecutableOnce()
            prepareWorkspaceOnce()
            block(startingPreemptionEpoch)
        }
    }

    internal fun ensureCurrent(epoch: Long) = ensureNotPreempted(epoch)

    fun currentAccountFingerprint(): String? =
        RestrictedTriageAuthProjector(authSource, authSource).currentAccountFingerprint()

    private fun ensureAccount(expected: String?) {
        if (expected != null && (!expected.matches(Regex("[0-9a-f]{64}")) || currentAccountFingerprint() != expected)) {
            throw RestrictedNotificationTriageFailure("restricted_analysis_account_changed")
        }
    }

    fun preemptCurrent() {
        operationGate.preemptCurrent()
        processFence.abortCurrent()
    }

    /**
     * Daily coordinator only: called after runText returned or threw, with its admission lock
     * held. A cancellation arriving after the operation returned must not poison a future run.
     * Notifications deliberately do not acknowledge here: their pending-preemption behavior is
     * part of the existing multi-stage pipeline contract.
     */
    fun acknowledgeCompletedRun() = operationGate.acknowledgeCompletedRun()

    override fun close() {
        if (!operationGate.close()) return
        processFence.abortCurrent()
        timeoutExecutor.shutdownNow()
    }

    private fun runIsolatedStage(
        request: RestrictedTextAnalysisRequest,
        startingPreemptionEpoch: Long,
        expectedAccountFingerprint: String?,
    ): String {
        val isolatedDirectories = RestrictedTriageAppServerPolicy.newSessionDirectories(appContext)
        val lease = RestrictedTriageSessionWorkspace.acquireSession(isolatedDirectories)
        try {
            createPrivateDirectories(isolatedDirectories)
            RestrictedTriageAuthProjector(
                source = authSource,
                destination = File(isolatedDirectories.codexHomeDirectory, AUTH_FILE_NAME),
            ).projectLatest(expectedAccountFingerprint)
            ensureAccount(expectedAccountFingerprint)
            ensureNotPreempted(startingPreemptionEpoch)
            return runSession(request, isolatedDirectories, startingPreemptionEpoch, expectedAccountFingerprint)
        } finally {
            lease.close()
            RestrictedTriageSessionWorkspace.deleteSession(isolatedDirectories)
        }
    }

    private fun runSession(
        request: RestrictedTextAnalysisRequest,
        isolatedDirectories: CodexRuntimeDirectories,
        startingPreemptionEpoch: Long,
        expectedAccountFingerprint: String?,
    ): String {
        val networkStack = RuntimeNetworkStack()
        var process: Process? = null
        var timeout: ScheduledFuture<*>? = null
        val timedOut = AtomicBoolean(false)
        val stderrExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "hans-notification-triage-stderr").apply { isDaemon = true }
        }
        try {
            val network = networkStack.restart(isolatedDirectories)
            val environment = CodexRuntimeContract.controlledEnvironment(
                directories = isolatedDirectories,
                androidRoot = System.getenv("ANDROID_ROOT") ?: "/system",
                androidData = System.getenv("ANDROID_DATA") ?: "/data",
                network = network,
            )
            process = processFence.start(startingPreemptionEpoch) {
                ProcessBuilder(RestrictedTriageAppServerPolicy.command(executable))
                    .directory(isolatedDirectories.workingDirectory)
                    .also { builder ->
                        builder.environment().run {
                            clear()
                            putAll(environment)
                        }
                    }
                    .start()
            }
            val activeProcess = process
            val stderrDrain = stderrExecutor.submit<Unit> {
                activeProcess.errorStream.use { stream ->
                    val buffer = ByteArray(8 * 1_024)
                    while (stream.read(buffer) >= 0) Unit
                }
            }
            timeout = timeoutExecutor.schedule(
                {
                    timedOut.set(true)
                    activeProcess.destroyForcibly()
                },
                timeoutSeconds,
                TimeUnit.SECONDS,
            )

            // The process owns these streams. Do not use BufferedOutputStream.use here: its
            // exceptional close flushes before finally can kill a child that stopped reading.
            // Every protocol write is explicitly flushed; failure cleanup discards buffered bytes
            // and terminates the child before closing its underlying pipes.
            val output = BufferedOutputStream(activeProcess.outputStream)
            val input = BufferedInputStream(activeProcess.inputStream)
            val result = executeProtocol(input, output, request, isolatedDirectories, startingPreemptionEpoch, expectedAccountFingerprint)
            timeout.cancel(false)
            terminate(activeProcess)
            runCatching { stderrDrain.get(2, TimeUnit.SECONDS) }
            if (timedOut.get()) throw RestrictedNotificationTriageFailure("triage_timeout")
            return result
        } catch (failure: NotificationTriagePreemptedException) {
            throw failure
        } catch (failure: RestrictedNotificationTriageFailure) {
            throw failure
        } catch (_: Exception) {
            if (timedOut.get()) {
                throw RestrictedNotificationTriageFailure("triage_timeout")
            }
            throw RestrictedNotificationTriageFailure("isolated_app_server_failure")
        } finally {
            timeout?.cancel(false)
            process?.let(processFence::release)
            process?.let(::terminate)
            networkStack.close()
            stderrExecutor.shutdownNow()
        }
    }

    private fun executeProtocol(
        input: BufferedInputStream,
        output: BufferedOutputStream,
        request: RestrictedTextAnalysisRequest,
        isolatedDirectories: CodexRuntimeDirectories,
        startingPreemptionEpoch: Long,
        expectedAccountFingerprint: String?,
    ): String {
        val accumulator = RestrictedAppServerFrameAccumulator(maximumResponseBytes)
        send(output, RestrictedTriageAppServerPolicy.initializeRequest().json, startingPreemptionEpoch, expectedAccountFingerprint)
        val initialize = awaitResponse(input, accumulator, INITIALIZE_ID)
        CodexRuntimeContract.parseInitializeResponse(
            responseJson = initialize.toString(),
            expectedRequestId = INITIALIZE_ID,
            expectedCodexHome = isolatedDirectories.codexHomeDirectory.absolutePath,
        )
        send(
            output,
            JSONObject().put("method", "initialized").put("params", JSONObject()).toString(),
            startingPreemptionEpoch,
            expectedAccountFingerprint,
        )

        send(
            output,
            RestrictedTriageAppServerPolicy.threadStartRequest(
                cwd = isolatedDirectories.workingDirectory.absolutePath,
                model = model,
                effort = effort,
                baseInstructions = request.baseInstructions,
                developerInstructions = request.developerInstructions,
            ).json,
            startingPreemptionEpoch,
            expectedAccountFingerprint,
        )
        val threadResponse = awaitResponse(input, accumulator, THREAD_START_ID)
        val threadId = threadResponse.requireResultObject()
            .requireObject("thread")
            .requireBoundedString("id", 256)

        send(
            output,
            RestrictedTriageAppServerPolicy.turnStartRequest(
                threadId = threadId,
                prompt = request.userInput,
                cwd = isolatedDirectories.workingDirectory.absolutePath,
                model = model,
                effort = effort,
            ).json,
            startingPreemptionEpoch,
            expectedAccountFingerprint,
        )
        val turnResponse = awaitResponse(input, accumulator, TURN_START_ID)
        val turnId = turnResponse.requireResultObject()
            .requireObject("turn")
            .requireBoundedString("id", 256)
        while (!accumulator.completed(threadId, turnId)) {
            ensureNotPreempted(startingPreemptionEpoch)
            accumulator.accept(readFrame(input))
        }
        return accumulator.finalAnswer(threadId, turnId)
            ?: throw RestrictedNotificationTriageFailure("missing_final_typed_result")
    }

    private fun awaitResponse(
        input: BufferedInputStream,
        accumulator: RestrictedAppServerFrameAccumulator,
        requestId: Long,
    ): JSONObject {
        while (true) {
            val frame = readFrame(input)
            accumulator.accept(frame)
            if (frame.optLong("id", Long.MIN_VALUE) == requestId) {
                if (frame.has("error")) {
                    throw RestrictedNotificationTriageFailure("app_server_request_failed")
                }
                return frame
            }
        }
    }

    private fun readFrame(input: BufferedInputStream): JSONObject =
        when (val result = BoundedJsonLineFramer(MAX_SERVER_FRAME_BYTES).readNext(input)) {
            is JsonLineReadResult.Frame -> runCatching {
                JSONObject(result.bytes.toString(Charsets.UTF_8))
            }.getOrElse {
                throw RestrictedNotificationTriageFailure("malformed_app_server_frame")
            }
            is JsonLineReadResult.Oversized ->
                throw RestrictedNotificationTriageFailure("oversized_app_server_frame")
            is JsonLineReadResult.PartialEof,
            JsonLineReadResult.Eof,
            -> throw RestrictedNotificationTriageFailure("app_server_closed_early")
        }

    private fun send(output: BufferedOutputStream, frame: String, startingPreemptionEpoch: Long, expectedAccountFingerprint: String?) {
        ensureAccount(expectedAccountFingerprint)
        val bytes = frame.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_CLIENT_FRAME_BYTES) {
            throw RestrictedNotificationTriageFailure("invalid_client_frame")
        }
        processFence.writeCurrent(startingPreemptionEpoch) {
            output.write(bytes)
            output.write('\n'.code)
        }
        processFence.writeCurrent(startingPreemptionEpoch, output::flush)
    }

    private fun verifyExecutableOnce() {
        if (executableVerified.get()) return
        synchronized(executableVerified) {
            if (executableVerified.get()) return
            check(
                Files.isRegularFile(executable.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    executable.canExecute() &&
                    executable.length() == BuildConfig.CODEX_RUNTIME_BYTES,
            ) { "restricted_triage_runtime_unavailable" }
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(executable).use { input ->
                val buffer = ByteArray(32 * 1_024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            check(sha == BuildConfig.CODEX_RUNTIME_SHA256) {
                "restricted_triage_runtime_integrity_failure"
            }
            executableVerified.set(true)
        }
    }

    private fun prepareWorkspaceOnce() {
        if (workspacePrepared.get()) return
        synchronized(workspacePrepared) {
            if (workspacePrepared.get()) return
            RestrictedTriageSessionWorkspace.cleanLegacyFixedHome(appContext)
            RestrictedTriageSessionWorkspace.sweepAbandonedSessions(appContext)
            workspacePrepared.set(true)
        }
    }

    private fun createPrivateDirectories(directories: CodexRuntimeDirectories) {
        listOf(
            directories.workingDirectory,
            directories.homeDirectory,
            directories.codexHomeDirectory,
            directories.temporaryDirectory,
        ).forEach { directory ->
            check((directory.isDirectory || directory.mkdirs()) && directory.isDirectory) {
                "restricted_triage_directory_unavailable"
            }
        }
    }

    private fun terminate(process: Process) = RestrictedTriageProcessTermination.terminate(process)

    private fun abortImmediately(process: Process) = RestrictedTriageProcessTermination.abortImmediately(process)

    private fun ensureNotPreempted(startingPreemptionEpoch: Long) {
        operationGate.ensureCurrent(startingPreemptionEpoch)
    }

    private companion object {
        const val AUTH_FILE_NAME = "auth.json"
        const val DEFAULT_MODEL = "gpt-6-luna"
        const val DEFAULT_TIMEOUT_SECONDS = 75L
        const val INITIALIZE_ID = 1L
        const val THREAD_START_ID = 2L
        const val TURN_START_ID = 3L
        const val MAX_CLIENT_FRAME_BYTES = 128 * 1_024
        const val MAX_SERVER_FRAME_BYTES = 256 * 1_024
    }
}

/** Preserves the notification pipeline's operation-wide cancellation semantics after extraction. */
internal class RestrictedTextAnalysisOperationGate {
    private val closed = AtomicBoolean(false)
    private val preemptionEpoch = AtomicLong(0)
    private val consumedPreemptionEpoch = AtomicLong(0)

    fun <T> withOperation(block: (Long) -> T): T {
        check(!closed.get()) { "restricted_triage_executor_closed" }
        val captured = preemptionEpoch.get()
        if (consumedPreemptionEpoch.getAndSet(captured) != captured) throw NotificationTriagePreemptedException()
        try {
            return block(captured).also { ensureCurrent(captured) }
        } catch (failure: Exception) {
            val current = preemptionEpoch.get()
            if (current != captured) {
                consumedPreemptionEpoch.accumulateAndGet(current) { left, right -> maxOf(left, right) }
                throw NotificationTriagePreemptedException()
            }
            throw failure
        }
    }

    fun ensureCurrent(captured: Long) {
        if (closed.get() || preemptionEpoch.get() != captured) throw NotificationTriagePreemptedException()
    }

    fun preemptCurrent() { preemptionEpoch.incrementAndGet() }

    /** Caller serializes this with preemption and invokes it only after its operation finishes. */
    fun acknowledgeCompletedRun() {
        consumedPreemptionEpoch.accumulateAndGet(preemptionEpoch.get()) { left, right -> maxOf(left, right) }
    }

    fun close(): Boolean = closed.compareAndSet(false, true)
}
