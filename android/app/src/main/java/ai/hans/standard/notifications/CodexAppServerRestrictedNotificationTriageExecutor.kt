package ai.hans.standard.notifications

import ai.hans.standard.BuildConfig
import ai.hans.standard.codex.AppServerRequests
import ai.hans.standard.codex.ApprovalPolicy
import ai.hans.standard.codex.CodexInput
import ai.hans.standard.codex.DispatchOptions
import ai.hans.standard.codex.DispatchSandbox
import ai.hans.standard.codex.ReasoningEffort
import ai.hans.standard.codex.RequestId
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
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/**
 * One-shot, isolated App Server evaluator for untrusted notifications.
 *
 * Every item gets a fresh process and ephemeral thread in a dedicated CODEX_HOME. Only the current
 * login material is projected into that home. Command, app, plugin, web, MCP, hook, memory, and
 * subagent surfaces are absent or disabled; there is no image input, and every unexpected tool or
 * image event fails closed.
 */
internal class CodexAppServerRestrictedNotificationTriageExecutor(
    context: Context,
    private val contextProvider: NotificationRelevanceContextProvider =
        NotificationRelevanceContextProvider.EMPTY,
    private val enrichmentProvider: NotificationEnrichmentProvider = NotificationEnrichmentProvider.NONE,
    private val personalMemoryContextProvider: NotificationPersonalMemoryContextProvider =
        NotificationPersonalMemoryContextProvider.NONE,
    private val model: String = DEFAULT_MODEL,
    private val effort: ReasoningEffort = ReasoningEffort.LOW,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) : RestrictedNotificationTriageExecutor, PreemptibleRestrictedNotificationTriageExecutor,
    Closeable {
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
    private val closed = AtomicBoolean(false)
    private val preemptionEpoch = AtomicLong(0)
    private val consumedPreemptionEpoch = AtomicLong(0)
    private val processFence = RestrictedTriageProcessFence(
        ensureCurrent = ::ensureNotPreempted,
        abort = ::abortImmediately,
    )

    init {
        require(timeoutSeconds in 10..120) { "Restricted triage timeout is invalid" }
    }

    override fun triage(workItem: RestrictedTriageWorkItem): RestrictedTriageDecision {
        check(!closed.get()) { "restricted_triage_executor_closed" }
        val startingPreemptionEpoch = preemptionEpoch.get()
        if (consumedPreemptionEpoch.getAndSet(startingPreemptionEpoch) !=
            startingPreemptionEpoch
        ) {
            throw NotificationTriagePreemptedException()
        }
        try {
            verifyExecutableOnce()
            prepareWorkspaceOnce()
            val context = runCatching(contextProvider::current)
                .getOrDefault(
                    NotificationRelevanceContext(
                        userIsDictating = true,
                        liveVoiceIsActive = true,
                        quietModeIsActive = true,
                    ),
                )
            val pipeline = RestrictedNotificationDecisionPipeline(
                modelRunner = RestrictedNotificationModelStageRunner { request ->
                    ensureNotPreempted(startingPreemptionEpoch)
                    runIsolatedStage(request, startingPreemptionEpoch).also {
                        ensureNotPreempted(startingPreemptionEpoch)
                    }
                },
                enrichmentProvider = enrichmentProvider,
                personalMemoryContextProvider = personalMemoryContextProvider,
            )
            return pipeline.decide(workItem.notification, context).also {
                ensureNotPreempted(startingPreemptionEpoch)
            }
        } catch (failure: Exception) {
            val currentPreemptionEpoch = preemptionEpoch.get()
            if (currentPreemptionEpoch != startingPreemptionEpoch) {
                consumedPreemptionEpoch.accumulateAndGet(currentPreemptionEpoch) { left, right ->
                    maxOf(left, right)
                }
                throw NotificationTriagePreemptedException()
            }
            throw failure
        }
    }

    override fun preemptCurrent() {
        preemptionEpoch.incrementAndGet()
        processFence.abortCurrent()
        (enrichmentProvider as? PreemptibleNotificationEnrichmentProvider)?.preemptCurrent()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        processFence.abortCurrent()
        runCatching { (enrichmentProvider as? Closeable)?.close() }
        timeoutExecutor.shutdownNow()
    }

    private fun runIsolatedStage(
        request: RestrictedNotificationModelStageRequest,
        startingPreemptionEpoch: Long,
    ): String {
        val isolatedDirectories = RestrictedTriageAppServerPolicy.newSessionDirectories(appContext)
        try {
            createPrivateDirectories(isolatedDirectories)
            RestrictedTriageAuthProjector(
                source = authSource,
                destination = File(isolatedDirectories.codexHomeDirectory, AUTH_FILE_NAME),
            ).projectLatest()
            ensureNotPreempted(startingPreemptionEpoch)
            return runSession(request, isolatedDirectories, startingPreemptionEpoch)
        } finally {
            RestrictedTriageSessionWorkspace.deleteSession(isolatedDirectories)
        }
    }

    private fun runSession(
        request: RestrictedNotificationModelStageRequest,
        isolatedDirectories: CodexRuntimeDirectories,
        startingPreemptionEpoch: Long,
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
            val result = executeProtocol(input, output, request, isolatedDirectories, startingPreemptionEpoch)
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
        request: RestrictedNotificationModelStageRequest,
        isolatedDirectories: CodexRuntimeDirectories,
        startingPreemptionEpoch: Long,
    ): String {
        val accumulator = RestrictedAppServerFrameAccumulator()
        send(output, RestrictedTriageAppServerPolicy.initializeRequest().json, startingPreemptionEpoch)
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

    private fun send(output: BufferedOutputStream, frame: String, startingPreemptionEpoch: Long) {
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
        if (closed.get() || preemptionEpoch.get() != startingPreemptionEpoch) {
            throw NotificationTriagePreemptedException()
        }
    }

    private companion object {
        const val AUTH_FILE_NAME = "auth.json"
        const val DEFAULT_MODEL = "gpt-5.6-luna"
        const val DEFAULT_TIMEOUT_SECONDS = 75L
        const val INITIALIZE_ID = 1L
        const val THREAD_START_ID = 2L
        const val TURN_START_ID = 3L
        const val MAX_CLIENT_FRAME_BYTES = 128 * 1_024
        const val MAX_SERVER_FRAME_BYTES = 256 * 1_024
    }
}

/**
 * Process startup is blocking and cannot be covered by the active-process pointer alone: a
 * preemption may see no process while the launcher is still returning it. Publish ownership,
 * then recheck the captured request epoch, so either the preemptor or this worker aborts it.
 * The injected launcher makes that exact handoff race reproducible without an Android runtime.
 */
internal class RestrictedTriageProcessFence(
    private val ensureCurrent: (Long) -> Unit,
    private val abort: (Process) -> Unit,
) {
    private val active = AtomicReference<Process?>(null)

    fun start(epoch: Long, launch: () -> Process): Process {
        ensureCurrent(epoch)
        val process = launch()
        try {
            check(active.compareAndSet(null, process)) { "restricted_triage_concurrent_session" }
            ensureCurrent(epoch)
            return process
        } catch (failure: Exception) {
            active.compareAndSet(process, null)
            abort(process)
            throw failure
        }
    }

    /** Never hold a lock around a pipe write: preemption must be able to kill a blocked child. */
    fun writeCurrent(epoch: Long, write: () -> Unit) {
        ensureCurrent(epoch)
        write()
    }

    fun abortCurrent() {
        active.get()?.let(abort)
    }

    fun release(process: Process) {
        active.compareAndSet(process, null)
    }
}

/** Closing a blocked stdin can wait for its writer; stop the child before touching any pipe. */
internal object RestrictedTriageProcessTermination {
    fun terminate(process: Process) {
        if (process.isAlive) {
            runCatching { process.destroy() }
            if (!runCatching { process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)) {
                runCatching { process.destroyForcibly() }
                runCatching { process.waitFor(2, TimeUnit.SECONDS) }
            }
        }
        closeStreams(process)
    }

    fun abortImmediately(process: Process) {
        runCatching { process.destroyForcibly() }
        closeStreams(process)
    }

    private fun closeStreams(process: Process) {
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }
}

internal object RestrictedTriageAppServerPolicy {
    private val configOverrides = listOf(
        "approval_policy=\"never\"",
        "sandbox_mode=\"read-only\"",
        "allow_login_shell=false",
        "check_for_update_on_startup=false",
        "history.persistence=\"none\"",
        "web_search=\"disabled\"",
        "tools.web_search=false",
        "features.shell_tool=false",
        "features.unified_exec=false",
        "features.multi_agent=false",
        "features.apps=false",
        "features.plugins=false",
        "features.remote_plugin=false",
        "features.hooks=false",
        "features.memories=false",
        "memories.generate_memories=false",
        "memories.use_memories=false",
        "memories.dedicated_tools=false",
        "features.skill_mcp_dependency_install=false",
        "apps._default.enabled=false",
    )

    fun command(executable: File): List<String> = buildList {
        add(executable.absolutePath)
        // The pinned runtime rejects any misspelled/removed safety override instead of silently
        // starting with a wider default tool profile.
        add("--strict-config")
        configOverrides.forEach { override ->
            add("-c")
            add(override)
        }
    }

    fun newSessionDirectories(context: Context): CodexRuntimeDirectories =
        newSessionDirectories(
            cacheDirectory = context.cacheDir,
            sessionDirectoryName = UUID.randomUUID().toString(),
        )

    internal fun newSessionDirectories(
        cacheDirectory: File,
        sessionDirectoryName: String,
    ): CodexRuntimeDirectories {
        require(sessionDirectoryName.isNotBlank()) { "Session directory name must not be blank" }
        require(File(sessionDirectoryName).name == sessionDirectoryName) {
            "Session directory name must be a single path segment"
        }
        val root = File(
            File(cacheDirectory, RestrictedTriageSessionWorkspace.SESSIONS_DIRECTORY_NAME),
            sessionDirectoryName,
        )
        val home = File(root, "home")
        return CodexRuntimeDirectories(
            workingDirectory = File(root, "empty-workspace"),
            homeDirectory = home,
            codexHomeDirectory = home,
            temporaryDirectory = File(root, "tmp"),
        )
    }

    fun initializeRequest() = AppServerRequests.initialize(
        id = RequestId.Number(1),
        clientName = "hans_notification_triage",
        clientTitle = "Hans Notification Triage",
        clientVersion = BuildConfig.VERSION_NAME,
        experimentalApi = false,
    )

    fun threadStartRequest(
        cwd: String,
        model: String,
        effort: ReasoningEffort,
        baseInstructions: String,
        developerInstructions: String,
    ) = AppServerRequests.threadStart(
        id = RequestId.Number(2),
        options = options(cwd, model, effort),
        developerInstructions = developerInstructions,
        baseInstructions = baseInstructions,
        ephemeral = true,
        dynamicTools = emptyList(),
    )

    fun turnStartRequest(
        threadId: String,
        prompt: String,
        cwd: String,
        model: String,
        effort: ReasoningEffort,
    ) = AppServerRequests.turnStart(
        id = RequestId.Number(3),
        threadId = threadId,
        input = listOf(CodexInput.Text(prompt)),
        options = options(cwd, model, effort),
        clientUserMessageId = null,
    )

    private fun options(
        cwd: String,
        model: String,
        effort: ReasoningEffort,
    ) = DispatchOptions(
        model = model,
        effort = effort,
        approvalPolicy = ApprovalPolicy.NEVER,
        sandbox = DispatchSandbox.READ_ONLY,
        cwd = cwd,
    )
}

/** Keeps every classifier invocation isolated and removes all App Server state afterward. */
internal object RestrictedTriageSessionWorkspace {
    const val SESSIONS_DIRECTORY_NAME = "notification-triage-sessions"
    private const val LEGACY_DIRECTORY_NAME = "notification-triage-app-server"
    private const val ABANDONED_AFTER_MILLIS = 10 * 60 * 1_000L

    fun cleanLegacyFixedHome(context: Context) {
        val legacy = File(context.noBackupFilesDir, LEGACY_DIRECTORY_NAME)
        if (Files.exists(legacy.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            check(deleteTreeNoFollow(legacy.toPath())) { "legacy_triage_cleanup_failed" }
        }
    }

    fun sweepAbandonedSessions(
        context: Context,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ) {
        val root = File(context.cacheDir, SESSIONS_DIRECTORY_NAME)
        if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        check(Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "triage_session_root_invalid"
        }
        root.listFiles().orEmpty().forEach { child ->
            if (
                SESSION_ID_PATTERN.matches(child.name) &&
                child.lastModified() <= nowEpochMillis - ABANDONED_AFTER_MILLIS
            ) {
                check(deleteTreeNoFollow(child.toPath())) { "abandoned_triage_cleanup_failed" }
            }
        }
    }

    fun deleteSession(directories: CodexRuntimeDirectories) {
        val session = directories.homeDirectory.parentFile
            ?: throw IllegalStateException("triage_session_parent_missing")
        check(SESSION_ID_PATTERN.matches(session.name)) { "triage_session_path_invalid" }
        check(session.parentFile?.name == SESSIONS_DIRECTORY_NAME) {
            "triage_session_path_invalid"
        }
        check(deleteTreeNoFollow(session.toPath())) { "triage_session_cleanup_failed" }
    }

    private fun deleteTreeNoFollow(root: Path): Boolean = runCatching {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return@runCatching true
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
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
        !Files.exists(root, LinkOption.NOFOLLOW_LINKS)
    }.getOrDefault(false)

    private val SESSION_ID_PATTERN =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
}

internal class RestrictedAppServerFrameAccumulator {
    private val completedTurns = mutableSetOf<Pair<String, String>>()
    private val finalAnswers = mutableMapOf<Pair<String, String>, String>()
    private var acceptedFrames = 0

    fun accept(frame: JSONObject) {
        acceptedFrames += 1
        if (acceptedFrames > MAX_ACCEPTED_FRAMES) {
            throw RestrictedNotificationTriageFailure("app_server_frame_budget_exceeded")
        }
        if (frame.has("id") && frame.has("method")) {
            throw RestrictedNotificationTriageFailure("server_request_forbidden")
        }
        val method = frame.optString("method", "")
        if (method.isEmpty()) return
        if (
            method.contains("requestApproval", ignoreCase = true) ||
            method == "item/tool/call" ||
            method.startsWith("item/commandExecution/") ||
            method.startsWith("process/") ||
            method.startsWith("command/")
        ) {
            throw RestrictedNotificationTriageFailure("tool_event_forbidden")
        }
        when (method) {
            "item/started" -> inspectItem(frame.requireObject("params"), completed = false)
            "item/completed" -> inspectItem(frame.requireObject("params"), completed = true)
            "turn/completed" -> inspectTurnCompleted(frame.requireObject("params"))
        }
    }

    fun completed(threadId: String, turnId: String): Boolean = threadId to turnId in completedTurns

    fun finalAnswer(threadId: String, turnId: String): String? = finalAnswers[threadId to turnId]

    private fun inspectItem(params: JSONObject, completed: Boolean) {
        val item = params.requireObject("item")
        val type = item.requireBoundedString("type", 128)
        if (type !in ALLOWED_ITEM_TYPES) {
            throw RestrictedNotificationTriageFailure("non_text_item_forbidden")
        }
        // App Server legitimately starts agent messages with an empty text field. Only completed
        // items can become a result; all item types are still allowlisted at both boundaries.
        if (!completed || type != "agentMessage") return
        if (!item.has("text")) {
            throw RestrictedNotificationTriageFailure("malformed_app_server_frame")
        }
        val phase = item.optString("phase", "")
        if (phase.isNotEmpty() && phase != "final_answer") return
        val text = item.requireBoundedString(
            "text",
            NotificationTriageBounds.MAX_MODEL_RESPONSE_BYTES,
        )
        val threadId = params.requireBoundedString("threadId", 256)
        val turnId = params.requireBoundedString("turnId", 256)
        finalAnswers[threadId to turnId] = text
    }

    private fun inspectTurnCompleted(params: JSONObject) {
        val turn = params.requireObject("turn")
        if (turn.requireBoundedString("status", 64) != "completed") {
            throw RestrictedNotificationTriageFailure("triage_turn_not_completed")
        }
        completedTurns += params.requireBoundedString("threadId", 256) to
            turn.requireBoundedString("id", 256)
    }

    private companion object {
        val ALLOWED_ITEM_TYPES = setOf("agentMessage", "reasoning", "userMessage")
        const val MAX_ACCEPTED_FRAMES = 512
    }
}

internal class RestrictedTriageAuthProjector(
    private val source: File,
    private val destination: File,
) {
    @Synchronized
    fun projectLatest() {
        check(Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "codex_login_unavailable"
        }
        check(source.length() in 1..MAX_AUTH_BYTES.toLong()) { "codex_login_invalid" }
        val parent = destination.parentFile ?: error("triage_auth_parent_missing")
        check((parent.isDirectory || parent.mkdirs()) && parent.isDirectory) {
            "triage_auth_parent_unavailable"
        }
        val sourceBytes = boundedRead(source)
        if (
            Files.isRegularFile(destination.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            destination.length() == sourceBytes.size.toLong() &&
            boundedRead(destination).contentEquals(sourceBytes)
        ) {
            applyPrivateMode(destination)
            return
        }
        val temporary = File.createTempFile("auth-", ".tmp", parent)
        try {
            applyPrivateMode(temporary)
            FileOutputStream(temporary).use { output ->
                output.write(sourceBytes)
                output.flush()
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            applyPrivateMode(destination)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun boundedRead(file: File): ByteArray {
        check(file.length() in 1..MAX_AUTH_BYTES.toLong()) { "codex_login_invalid" }
        return FileInputStream(file).use { input ->
            val buffer = ByteArray(16 * 1_024)
            val output = java.io.ByteArrayOutputStream(file.length().toInt())
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                check(total <= MAX_AUTH_BYTES) { "codex_login_invalid" }
                output.write(buffer, 0, count)
            }
            check(total > 0) { "codex_login_invalid" }
            output.toByteArray()
        }
    }

    private fun applyPrivateMode(file: File) {
        Files.setPosixFilePermissions(file.toPath(), PRIVATE_MODE)
    }

    private companion object {
        const val MAX_AUTH_BYTES = 1 * 1_024 * 1_024
        val PRIVATE_MODE = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
    }
}

internal class RestrictedNotificationTriageFailure(code: String) : IllegalStateException(code)

private fun JSONObject.requireResultObject(): JSONObject = requireObject("result")

private fun JSONObject.requireObject(key: String): JSONObject =
    optJSONObject(key) ?: throw RestrictedNotificationTriageFailure("malformed_app_server_frame")

private fun JSONObject.requireBoundedString(key: String, maximumUtf8Bytes: Int): String {
    val value = opt(key) as? String
        ?: throw RestrictedNotificationTriageFailure("malformed_app_server_frame")
    if (value.isBlank() || value.toByteArray(Charsets.UTF_8).size > maximumUtf8Bytes) {
        throw RestrictedNotificationTriageFailure("malformed_app_server_frame")
    }
    return value
}
