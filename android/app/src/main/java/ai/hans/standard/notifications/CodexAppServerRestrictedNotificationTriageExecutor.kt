package ai.hans.standard.notifications

import ai.hans.standard.BuildConfig
import ai.hans.standard.codex.AppServerRequests
import ai.hans.standard.codex.ApprovalPolicy
import ai.hans.standard.codex.CodexInput
import ai.hans.standard.codex.DispatchOptions
import ai.hans.standard.codex.DispatchSandbox
import ai.hans.standard.codex.ReasoningEffort
import ai.hans.standard.codex.RequestId
import ai.hans.standard.runtime.CodexRuntimeDirectories
import android.content.Context
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileVisitResult
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/**
 * Notification pipeline wrapper. All model stages share the reusable restricted text runner;
 * enrichment preemption and the existing operation-wide epoch are intentionally unchanged.
 */
internal class CodexAppServerRestrictedNotificationTriageExecutor(
    context: Context,
    private val contextProvider: NotificationRelevanceContextProvider = NotificationRelevanceContextProvider.EMPTY,
    private val enrichmentProvider: NotificationEnrichmentProvider = NotificationEnrichmentProvider.NONE,
    private val personalMemoryContextProvider: NotificationPersonalMemoryContextProvider = NotificationPersonalMemoryContextProvider.NONE,
    model: String = "gpt-5.6-luna",
    effort: ReasoningEffort = ReasoningEffort.LOW,
    timeoutSeconds: Long = 75L,
) : RestrictedNotificationTriageExecutor, PreemptibleRestrictedNotificationTriageExecutor, Closeable {
    private val runner = CodexAppServerRestrictedTextAnalysisRunner(context, model, effort, timeoutSeconds)
    private val closed = AtomicBoolean(false)

    override fun triage(workItem: RestrictedTriageWorkItem): RestrictedTriageDecision =
        runner.withOperation { epoch ->
            val context = runCatching(contextProvider::current).getOrDefault(
                NotificationRelevanceContext(userIsDictating = true, liveVoiceIsActive = true, quietModeIsActive = true),
            )
            val pipeline = RestrictedNotificationDecisionPipeline(
                modelRunner = RestrictedNotificationModelStageRunner { request ->
                    runner.runText(RestrictedTextAnalysisRequest(
                        request.baseInstructions, request.developerInstructions, request.userInput,
                    ), epoch)
                },
                enrichmentProvider = enrichmentProvider,
                personalMemoryContextProvider = personalMemoryContextProvider,
            )
            pipeline.decide(workItem.notification, context).also { runner.ensureCurrent(epoch) }
        }

    override fun preemptCurrent() {
        runner.preemptCurrent()
        (enrichmentProvider as? PreemptibleNotificationEnrichmentProvider)?.preemptCurrent()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runner.close()
        runCatching { (enrichmentProvider as? Closeable)?.close() }
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
    private const val LEASE_FILE_NAME = ".session-lease"

    /** A kernel file lock protects this workspace even from a sweeper in another app process. */
    fun acquireSession(directories: CodexRuntimeDirectories): Closeable {
        val session = checkedSession(directories)
        val sessionsRoot = checkNotNull(session.parentFile) { "triage_session_root_invalid" }.toPath()
        check(!Files.isSymbolicLink(sessionsRoot)) { "triage_session_root_invalid" }
        Files.createDirectories(sessionsRoot)
        check(Files.isDirectory(sessionsRoot, LinkOption.NOFOLLOW_LINKS)) { "triage_session_root_invalid" }
        // Never attach to another invocation's workspace, even after an improbable UUID collision.
        Files.createDirectory(session.toPath())
        try {
            val channel = FileChannel.open(session.toPath().resolve(LEASE_FILE_NAME),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            try {
                val lock = channel.tryLock() ?: error("triage_session_lease_unavailable")
                return Closeable { try { lock.release() } finally { channel.close() } }
            } catch (failure: Exception) { channel.close(); throw failure }
        } catch (failure: Exception) {
            deleteTreeNoFollow(session.toPath())
            throw failure
        }
    }

    fun cleanLegacyFixedHome(context: Context) {
        val legacy = File(context.noBackupFilesDir, LEGACY_DIRECTORY_NAME)
        if (Files.exists(legacy.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            check(deleteTreeNoFollow(legacy.toPath())) { "legacy_triage_cleanup_failed" }
        }
    }

    fun sweepAbandonedSessions(
        context: Context,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ) = sweepAbandonedSessions(context.cacheDir, nowEpochMillis)

    internal fun sweepAbandonedSessions(cacheDirectory: File, nowEpochMillis: Long) {
        val root = File(cacheDirectory, SESSIONS_DIRECTORY_NAME)
        if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        check(Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "triage_session_root_invalid"
        }
        root.listFiles().orEmpty().forEach { child ->
            if (
                SESSION_ID_PATTERN.matches(child.name) &&
                child.lastModified() <= nowEpochMillis - ABANDONED_AFTER_MILLIS
            ) {
                if (!Files.isDirectory(child.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    check(deleteTreeNoFollow(child.toPath())) { "abandoned_triage_cleanup_failed" }
                    return@forEach
                }
                // Never follow a lease symlink or delete a workspace whose live owner holds it.
                val channel = FileChannel.open(child.toPath().resolve(LEASE_FILE_NAME),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                channel.use {
                    val lease = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    lease?.use { check(deleteTreeNoFollow(child.toPath())) { "abandoned_triage_cleanup_failed" } }
                }
            }
        }
    }

    fun deleteSession(directories: CodexRuntimeDirectories) {
        val session = checkedSession(directories)
        check(deleteTreeNoFollow(session.toPath())) { "triage_session_cleanup_failed" }
    }

    private fun checkedSession(directories: CodexRuntimeDirectories): File {
        val session = directories.homeDirectory.parentFile
            ?: throw IllegalStateException("triage_session_parent_missing")
        check(SESSION_ID_PATTERN.matches(session.name)) { "triage_session_path_invalid" }
        check(session.parentFile?.name == SESSIONS_DIRECTORY_NAME) {
            "triage_session_path_invalid"
        }
        return session
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

internal class RestrictedAppServerFrameAccumulator(
    private val maximumResponseBytes: Int = NotificationTriageBounds.MAX_MODEL_RESPONSE_BYTES,
) {
    init { require(maximumResponseBytes in 1..32 * 1_024) }
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
            maximumResponseBytes,
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
    fun projectLatest(expectedAccountFingerprint: String? = null) {
        check(Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "codex_login_unavailable"
        }
        check(source.length() in 1..MAX_AUTH_BYTES.toLong()) { "codex_login_invalid" }
        val parent = destination.parentFile ?: error("triage_auth_parent_missing")
        check((parent.isDirectory || parent.mkdirs()) && parent.isDirectory) {
            "triage_auth_parent_unavailable"
        }
        val sourceBytes = boundedRead(source)
        try {
        if (expectedAccountFingerprint != null) {
            check(expectedAccountFingerprint.matches(Regex("[0-9a-f]{64}")) &&
                accountFingerprint(sourceBytes) == expectedAccountFingerprint) { "restricted_analysis_account_changed" }
        }
        if (
            Files.isRegularFile(destination.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            destination.length() == sourceBytes.size.toLong() &&
            matchesDestination(sourceBytes)
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
        } finally { sourceBytes.fill(0) }
    }

    fun currentAccountFingerprint(): String? = runCatching {
        val bytes = boundedRead(source)
        try { accountFingerprint(bytes) } finally { bytes.fill(0) }
    }.getOrNull()

    private fun matchesDestination(sourceBytes: ByteArray): Boolean {
        val bytes = boundedRead(destination)
        return try { bytes.contentEquals(sourceBytes) } finally { bytes.fill(0) }
    }

    private fun boundedRead(file: File): ByteArray {
        val path = file.toPath()
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(before.isRegularFile && before.size() in 1..MAX_AUTH_BYTES.toLong()) { "codex_login_invalid" }
        val bytes = ByteArray(before.size().toInt())
        try {
        return FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            check(input.size() == before.size()) { "codex_login_changed" }
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                val count = input.read(buffer)
                check(count > 0) { "codex_login_changed" }
            }
            check(input.read(ByteBuffer.allocate(1)) == -1) { "codex_login_changed" }
            val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            check(after.isRegularFile && before.fileKey() == after.fileKey() && before.size() == after.size() &&
                before.lastModifiedTime() == after.lastModifiedTime()) { "codex_login_changed" }
            bytes
        }
        } catch (failure: Exception) { bytes.fill(0); throw failure }
    }

    private fun accountFingerprint(bytes: ByteArray): String? {
        val raw = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        if (!isStrictNotificationJsonObject(raw)) return null
        val root = JSONObject(raw)
        val mode = root.opt("auth_mode")
        if (mode != null && mode !== JSONObject.NULL && mode != "chatgpt") return null
        if (root.has("OPENAI_API_KEY") && !root.isNull("OPENAI_API_KEY")) return null
        val id = root.optJSONObject("tokens")?.opt("account_id") as? String ?: return null
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,256}"))) return null
        return MessageDigest.getInstance("SHA-256").digest(
            ("hans-chatgpt-account-v1\u0000" + id).toByteArray(Charsets.UTF_8),
        ).joinToString("") { "%02x".format(it.toInt() and 0xff) }
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

internal fun JSONObject.requireResultObject(): JSONObject = requireObject("result")

internal fun JSONObject.requireObject(key: String): JSONObject =
    optJSONObject(key) ?: throw RestrictedNotificationTriageFailure("malformed_app_server_frame")

internal fun JSONObject.requireBoundedString(key: String, maximumUtf8Bytes: Int): String {
    val value = opt(key) as? String
        ?: throw RestrictedNotificationTriageFailure("malformed_app_server_frame")
    if (value.isBlank() || value.toByteArray(Charsets.UTF_8).size > maximumUtf8Bytes) {
        throw RestrictedNotificationTriageFailure("malformed_app_server_frame")
    }
    return value
}
