package ai.hans.standard.runtime

import android.os.IBinder
import android.os.RemoteException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class AppServerSessionSupervisor(
    private val executableProvider: () -> File,
    private val directoriesProvider: () -> CodexRuntimeDirectories,
    private val bootstrapProvider: () -> CodexRuntimeBootstrapReceipt,
    private val environmentProvider: (CodexRuntimeDirectories) -> Map<String, String>,
    private val argumentsProvider: (CodexRuntimeDirectories, Map<String, String>) -> List<String>,
    private val clientVersion: String,
    private val expectedExecutableBytes: Long,
    private val runtimePidProvider: () -> Int,
    private val acquireProcessLease: () -> Boolean,
    private val releaseProcessLease: () -> Unit,
    private val processStarter: (ProcessBuilder) -> Process = { it.start() },
) {
    /** Binder worker only; no supervisor lock is held across SQLite IO. */
    fun readNativeMemoryHealth(expectedGeneration: Long, appOwnedRoot: File): String {
        val unavailable = ai.hans.standard.diagnostics.memory.NativeMemoryHealthResult.Unavailable(
            ai.hans.standard.diagnostics.memory.NativeMemoryUnavailableReason.CONFIGURATION_UNRESOLVED)
        val session = active
        if (session == null || closed.get() || !session.ready || session.stopRequested ||
            (expectedGeneration != 0L && session.generation != expectedGeneration)) {
            return ai.hans.standard.diagnostics.memory.NativeMemoryHealthWire.encode(unavailable)
        }
        val result = ai.hans.standard.diagnostics.memory.AndroidNativeMemoryHealthReader().read(
            ai.hans.standard.diagnostics.memory.NativeMemoryHealthRequest(appOwnedRoot, session.effectiveSqliteHome,
                ai.hans.standard.BuildConfig.CODEX_RUNTIME_VERSION,
                ai.hans.standard.BuildConfig.CODEX_RUNTIME_SHA256))
        return ai.hans.standard.diagnostics.memory.NativeMemoryHealthWire.encode(
            if (active === session && !closed.get() && session.ready && !session.stopRequested) result else unavailable,
            session.generation)
    }
    private val commandExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hans-app-server-supervisor").apply { isDaemon = true }
    }
    private val timerExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "hans-app-server-timer").apply { isDaemon = true }
    }
    private val sequencer = SessionEventSequencer()
    private val closed = AtomicBoolean(false)
    private val leaseLock = Any()

    @Volatile
    private var active: Session? = null

    @Volatile
    private var leaseOwned = false

    fun start(operationId: Long, callback: IAppServerSessionCallback) {
        if (closed.get()) {
            notifyStandaloneFailure(callback, operationId, "Runtime service is shutting down")
            return
        }
        if (!reserveLease()) {
            notifyStandaloneFailure(callback, operationId, "Another runtime process is active")
            return
        }
        commandExecutor.execute { startInternal(operationId, callback) }
    }

    fun restart(operationId: Long, expectedGeneration: Long) {
        if (closed.get()) return
        commandExecutor.execute {
            val session = active ?: return@execute
            if (session.generation != expectedGeneration) {
                session.dispatchNotice(
                    code = AppServerSessionContract.NOTICE_STALE_GENERATION,
                    relatedSequence = expectedGeneration,
                    detail = "Restart generation is stale",
                )
                return@execute
            }
            val callback = session.callback
            stopInternal(
                operationId = operationId,
                session = session,
                releaseLease = false,
            )
            startInternal(operationId, callback)
        }
    }

    fun acceptClientChunk(
        generation: Long,
        clientSequence: Long,
        chunkIndex: Int,
        chunkCount: Int,
        totalBytes: Int,
        payload: ByteArray,
    ) {
        if (closed.get()) return
        if (payload.size > AppServerSessionContract.MAX_BINDER_CHUNK_BYTES) {
            commandExecutor.execute {
                val session = active ?: return@execute
                if (session.generation != generation) {
                    session.dispatchNotice(
                        code = AppServerSessionContract.NOTICE_STALE_GENERATION,
                        relatedSequence = clientSequence,
                        detail = "Client frame generation is stale",
                    )
                    return@execute
                }
                session.dispatchNotice(
                    code = AppServerSessionContract.NOTICE_CLIENT_FRAME_REJECTED,
                    relatedSequence = clientSequence,
                    detail = "Client Binder chunk exceeded the transport limit",
                )
            }
            return
        }
        // Copy before returning from Binder so no caller-owned mutable array is
        // retained by the asynchronous command queue.
        val stablePayload = payload.copyOf()
        commandExecutor.execute {
            val session = active ?: return@execute
            if (session.generation != generation) {
                session.dispatchNotice(
                    code = AppServerSessionContract.NOTICE_STALE_GENERATION,
                    relatedSequence = clientSequence,
                    detail = "Client frame generation is stale",
                )
                return@execute
            }
            if (!session.ready) {
                session.dispatchNotice(
                    code = AppServerSessionContract.NOTICE_SESSION_NOT_READY,
                    relatedSequence = clientSequence,
                    detail = "App Server session is not ready",
                )
                return@execute
            }
            when (
                val result = session.clientFrames.accept(
                    clientSequence = clientSequence,
                    chunkIndex = chunkIndex,
                    chunkCount = chunkCount,
                    totalBytes = totalBytes,
                    payload = stablePayload,
                )
            ) {
                FrameAssemblyResult.Incomplete -> Unit
                is FrameAssemblyResult.Rejected -> session.dispatchNotice(
                    code = AppServerSessionContract.NOTICE_CLIENT_FRAME_REJECTED,
                    relatedSequence = result.relatedSequence,
                    detail = result.detail,
                )
                is FrameAssemblyResult.Complete -> {
                    if (session.writer?.offerFrame(result.bytes) != true) {
                        session.dispatchNotice(
                            code = AppServerSessionContract.NOTICE_CLIENT_FRAME_REJECTED,
                            relatedSequence = result.clientSequence,
                            detail = "App Server stdin backlog exceeded its bounded capacity",
                        )
                    }
                }
            }
        }
    }

    fun stop(operationId: Long, expectedGeneration: Long) {
        if (closed.get()) return
        commandExecutor.execute {
            val session = active ?: return@execute
            if (session.generation != expectedGeneration) {
                session.dispatchNotice(
                    code = AppServerSessionContract.NOTICE_STALE_GENERATION,
                    relatedSequence = expectedGeneration,
                    detail = "Stop generation is stale",
                )
                return@execute
            }
            stopInternal(operationId, session, releaseLease = true)
        }
    }

    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        val stopped = commandExecutor.submit {
            active?.let { stopInternal(0L, it, releaseLease = true) }
            releaseLeaseIfOwned()
        }
        runCatching {
            stopped.get(
                CodexRuntimeContract.SHUTDOWN_TIMEOUT_SECONDS * 2,
                TimeUnit.SECONDS,
            )
        }
        commandExecutor.shutdownNow()
        timerExecutor.shutdownNow()
        releaseLeaseIfOwned()
    }

    fun hasReservedProcess(): Boolean = leaseOwned

    private fun startInternal(operationId: Long, callback: IAppServerSessionCallback) {
        val existing = active
        if (existing != null) {
            if (existing.callback.asBinder() == callback.asBinder()) {
                existing.dispatchState(
                    operationId = operationId,
                    state = if (existing.ready) {
                        AppServerSessionContract.STATE_READY
                    } else {
                        AppServerSessionContract.STATE_STARTING
                    },
                    detail = "Session already active",
                )
            } else {
                notifyStandaloneFailure(callback, operationId, "Session already has an owner")
            }
            return
        }
        // A previously queued start may have reserved the lease and then
        // failed before this command is reached. Re-check on the serialized
        // executor so no later queued start can launch without owning it.
        if (!reserveLease()) {
            notifyStandaloneFailure(callback, operationId, "Another runtime process is active")
            return
        }

        val executable: File
        val directories: CodexRuntimeDirectories
        val environment: Map<String, String>
        val arguments: List<String>
        try {
            executable = executableProvider().canonicalFile
            check(executable.isFile && executable.canExecute()) {
                "Packaged App Server is unavailable"
            }
            check(executable.length() == expectedExecutableBytes) {
                "Packaged App Server size does not match the pinned release"
            }
            directories = directoriesProvider()
            // The marketplace must be complete before Codex reads HOME or
            // starts discovery during initialize. A failed materialization is
            // a prerequisite failure, never a partially ready session.
            bootstrapProvider()
            environment = environmentProvider(directories)
            arguments = argumentsProvider(directories, environment)
        } catch (_: Exception) {
            notifyStandaloneFailure(callback, operationId, "App Server prerequisites failed")
            releaseLeaseIfOwned()
            return
        }

        val generation = sequencer.nextGeneration()
        val binder = callback.asBinder()
        lateinit var deathRecipient: IBinder.DeathRecipient
        deathRecipient = IBinder.DeathRecipient {
            commandExecutor.execute { handleBinderDeath(binder) }
        }
        try {
            binder.linkToDeath(deathRecipient, 0)
        } catch (_: RemoteException) {
            notifyStandaloneFailure(callback, operationId, "Session owner is no longer alive")
            releaseLeaseIfOwned()
            return
        }

        val session = Session(
            generation = generation,
            startOperationId = operationId,
            callback = callback,
            deathRecipient = deathRecipient,
            expectedCodexHome = directories.codexHomeDirectory.absolutePath,
            workspacePath = directories.workingDirectory.absolutePath,
            sqliteEnvironmentOverride = environment["CODEX_SQLITE_HOME"],
        )
        active = session
        session.dispatchState(operationId, AppServerSessionContract.STATE_STARTING, "Starting")

        try {
            val processBuilder = ProcessBuilder(listOf(executable.absolutePath) + arguments)
                .directory(directories.workingDirectory)
            processBuilder.environment().run {
                clear()
                putAll(environment)
            }
            val process = processStarter(processBuilder)
            session.process = process
            session.writer = GenerationStdinWriter(
                generation = generation,
                output = BufferedOutputStream(process.outputStream),
                onFailure = {
                    runCatching {
                        commandExecutor.execute {
                            if (active === session && !session.stopRequested) {
                                failSession(session, "App Server request write failed")
                            }
                        }
                    }
                },
            )
            session.stderrFuture = session.ioExecutor.submit<Long> {
                process.errorStream.use(::drainDiscarding)
            }
            session.stdoutFuture = session.ioExecutor.submit<Unit> {
                readStdout(session, process.inputStream)
            }

            val initializeId = AppServerSessionContract.initializeRequestId(generation)
            val initialize = CodexRuntimeContract.initializeRequest(clientVersion, initializeId)
            check(session.writer!!.offerFrame(initialize.toByteArray(StandardCharsets.UTF_8)))
            session.initializeTimeout = timerExecutor.schedule(
                {
                    commandExecutor.execute {
                        if (active === session && !session.ready) {
                            failSession(session, "App Server initialize timed out")
                        }
                    }
                },
                CodexRuntimeContract.INITIALIZE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
        } catch (_: Exception) {
            failSession(session, "App Server process could not start")
        }
    }

    private fun readStdout(session: Session, rawInput: InputStream) {
        var termination: StdoutLoopTermination = StdoutLoopTermination.Failure
        try {
            val input = BufferedInputStream(rawInput, 32 * 1024)
            termination = AppServerStdoutLoop().run(
                input = input,
                shouldStop = { session.stopRequested },
                onFrame = { bytes ->
                    runOnSupervisorExecutor {
                        if (active === session && !session.stopRequested) {
                            handleServerFrame(session, bytes)
                        }
                    }
                },
                onOversized = {
                    runOnSupervisorExecutor {
                        if (active === session && !session.stopRequested) {
                            session.dispatchNotice(
                                code = AppServerSessionContract.NOTICE_SERVER_FRAME_OVERSIZED,
                                relatedSequence = 0,
                                detail = "App Server frame exceeded the transport limit",
                            )
                        }
                    }
                },
            )
        } catch (_: Exception) {
            termination = StdoutLoopTermination.Failure
        } finally {
            // Every terminal transition is serialized behind all frames that
            // were already accepted. Waiting here also provides bounded
            // backpressure instead of growing an unbounded executor queue.
            runCatching {
                runOnSupervisorExecutor {
                    handleStdoutTermination(session, termination)
                }
            }
            // Stream close can block too. It belongs to the IO owner, not supervision.
            runCatching { rawInput.close() }
        }
    }

    /** Called only by [commandExecutor]. */
    private fun handleServerFrame(session: Session, bytes: ByteArray) {
        if (active !== session || session.stopRequested) return
        val text = JsonFrameValidator.decodeObject(bytes)
        if (text == null) {
            session.dispatchNotice(
                code = AppServerSessionContract.NOTICE_SERVER_FRAME_INVALID,
                relatedSequence = 0,
                detail = "App Server emitted an invalid JSON frame",
            )
            return
        }
        val initializeId = AppServerSessionContract.initializeRequestId(session.generation)
        if (JsonFrameValidator.hasResponseId(bytes, initializeId)) {
            if (session.initialized) {
                session.dispatchNotice(
                    code = AppServerSessionContract.NOTICE_SERVER_FRAME_INVALID,
                    relatedSequence = 0,
                    detail = "App Server repeated the initialize response",
                )
                return
            }
            val parsed = runCatching {
                CodexRuntimeContract.parseInitializeResponse(
                    responseJson = text,
                    expectedRequestId = initializeId,
                    expectedCodexHome = session.expectedCodexHome,
                )
            }
            parsed.fold(
                onSuccess = {
                    session.initialized = true
                    try {
                        check(session.writer!!.offerFrame(
                            "{\"method\":\"initialized\",\"params\":{}}".toByteArray(StandardCharsets.UTF_8),
                        ))
                        check(session.writer!!.offerFrame(
                            CodexAssistantProfile.configReadRequest(
                                AppServerSessionContract.assistantProfileRequestId(session.generation),
                                session.workspacePath,
                            ).toByteArray(StandardCharsets.UTF_8),
                        ))
                    } catch (_: Exception) {
                        failSession(session, "Hans assistant profile could not be checked")
                    }
                },
                onFailure = { failSession(session, "App Server initialize was rejected") },
            )
            return
        }
        val profileRequestId = AppServerSessionContract.assistantProfileRequestId(session.generation)
        if (JsonFrameValidator.hasResponseId(bytes, profileRequestId)) {
            if (!session.initialized || session.ready) {
                failSession(session, "Unexpected Hans assistant profile response")
                return
            }
            try {
                CodexAssistantProfile.requireEffectiveConfig(text, profileRequestId)
            } catch (_: Exception) {
                failSession(session, "Codex did not confirm the Hans assistant profile")
                return
            }
            // The merged config response may contain plugin environment variables or credentials.
            // Consume it here: it must never reach Binder, the transcript or application logs.
            session.effectiveSqliteHome = ai.hans.standard.diagnostics.memory.NativeMemorySqliteHome.resolve(
                text, session.expectedCodexHome, session.sqliteEnvironmentOverride, session.workspacePath)
            session.ready = true
            session.initializeTimeout?.cancel(false)
            session.preReadyFrames.releaseAfterReady(
                onReady = {
                    session.dispatchState(
                        operationId = session.startOperationId,
                        state = AppServerSessionContract.STATE_READY,
                        detail = "Ready; ${CodexAssistantProfile.ID} verified",
                    )
                },
                onFrame = session::dispatchFrame,
                onDroppedFrame = {
                    session.dispatchNotice(
                        code = AppServerSessionContract.NOTICE_SERVER_FRAME_OVERSIZED,
                        relatedSequence = 0,
                        detail = "Pre-ready App Server output exceeded its buffer",
                    )
                },
            )
            return
        }
        if (!session.ready) {
            session.preReadyFrames.hold(bytes)
            return
        }
        session.dispatchFrame(bytes)
    }

    /** Called only by [commandExecutor]. */
    private fun handleStdoutTermination(
        session: Session,
        termination: StdoutLoopTermination,
    ) {
        if (active !== session) return
        StdoutTerminationRouter.route(
            termination = termination,
            sessionStopping = session.stopRequested,
            onPartialEof = {
                session.dispatchNotice(
                    code = AppServerSessionContract.NOTICE_STDOUT_PARTIAL_EOF,
                    relatedSequence = 0,
                    detail = "App Server stdout ended mid-frame",
                )
            },
            onExited = { handleUnexpectedEof(session) },
            onFailure = { failSession(session, "App Server stdout reader failed") },
        )
    }

    private fun runOnSupervisorExecutor(block: () -> Unit) {
        commandExecutor.submit(block).get()
    }

    private fun handleUnexpectedEof(session: Session) {
        if (active !== session || session.stopRequested) return
        session.stopRequested = true
        cleanupProcess(session)
        session.dispatchState(
            operationId = 0L,
            state = AppServerSessionContract.STATE_EXITED,
            detail = "App Server exited",
            allowStopping = true,
        )
        detachSession(session, releaseLease = true)
    }

    private fun failSession(session: Session, detail: String) {
        if (active !== session) return
        session.stopRequested = true
        cleanupProcess(session)
        session.dispatchState(
            operationId = session.startOperationId,
            state = AppServerSessionContract.STATE_FAILED,
            detail = detail,
            allowStopping = true,
        )
        detachSession(session, releaseLease = true)
    }

    private fun stopInternal(
        operationId: Long,
        session: Session,
        releaseLease: Boolean,
    ) {
        if (active !== session) return
        session.stopRequested = true
        session.dispatchState(
            operationId = operationId,
            state = AppServerSessionContract.STATE_STOPPING,
            detail = "Stopping",
            allowStopping = true,
        )
        cleanupProcess(session)
        session.dispatchState(
            operationId = operationId,
            state = AppServerSessionContract.STATE_STOPPED,
            detail = "Stopped",
            allowStopping = true,
        )
        detachSession(session, releaseLease)
    }

    private fun cleanupProcess(session: Session) {
        session.initializeTimeout?.cancel(false)
        session.preReadyFrames.clear()
        val writer = session.writer
        writer?.cancelPending()
        val process = session.process
        if (process != null && process.isAlive) {
            process.destroy()
            if (!process.waitFor(CodexRuntimeContract.SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(CodexRuntimeContract.SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }
        // Never close/flush buffered stdin before killing a stalled child, or on the
        // supervisor lane. The generation-owned writer closes after any active write
        // unwinds, and only after this explicit process-termination boundary.
        writer?.closeAfterProcessTermination()
        if (process != null && (session.stdoutFuture == null || session.stderrFuture == null || writer == null)) {
            // A partial startup can fail before an IO owner is installed.
            session.ioExecutor.execute {
                if (session.stdoutFuture == null) runCatching { process.inputStream.close() }
                if (session.stderrFuture == null) runCatching { process.errorStream.close() }
                if (writer == null) runCatching { process.outputStream.close() }
            }
        }
        // Drain the two admitted readers so even a not-yet-started reader owns its
        // finally/close. Old close stalls must never consume a replacement's lanes.
        session.ioExecutor.shutdown()
        session.writer = null
        session.process = null
    }

    private fun detachSession(session: Session, releaseLease: Boolean) {
        if (active === session) active = null
        runCatching { session.callback.asBinder().unlinkToDeath(session.deathRecipient, 0) }
        if (releaseLease) releaseLeaseIfOwned()
    }

    private fun handleBinderDeath(owner: IBinder) {
        val session = active ?: return
        if (session.callback.asBinder() != owner) return
        session.stopRequested = true
        cleanupProcess(session)
        // No callback is attempted: the only listener is already dead.
        detachSession(session, releaseLease = true)
    }

    private fun reserveLease(): Boolean = synchronized(leaseLock) {
        if (leaseOwned) return@synchronized true
        if (!acquireProcessLease()) return@synchronized false
        leaseOwned = true
        true
    }

    private fun releaseLeaseIfOwned() = synchronized(leaseLock) {
        if (leaseOwned) {
            leaseOwned = false
            releaseProcessLease()
        }
    }

    private fun notifyStandaloneFailure(
        callback: IAppServerSessionCallback,
        operationId: Long,
        detail: String,
    ) {
        runCatching {
            callback.onSessionState(
                operationId,
                0L,
                0L,
                AppServerSessionContract.STATE_FAILED,
                0,
                AppServerSessionContract.boundedDetail(detail),
            )
        }
    }

    private fun drainDiscarding(input: InputStream): Long {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return total
            total = if (Long.MAX_VALUE - total < count) Long.MAX_VALUE else total + count
            // Content is deliberately discarded: stderr can contain message or
            // authentication data and must never be logged or sent over Binder.
        }
    }

    private inner class Session(
        val generation: Long,
        val startOperationId: Long,
        val callback: IAppServerSessionCallback,
        val deathRecipient: IBinder.DeathRecipient,
        val expectedCodexHome: String,
        val workspacePath: String,
        val sqliteEnvironmentOverride: String?,
    ) {
        val callbackLock = Any()
        val clientFrames = OrderedClientFrameAssembler()
        val preReadyFrames = PreReadyFrameBuffer()
        // Only two readers and at most one partial-startup cleanup task are submitted.
        // No client work or unbounded cached pool can expand this generation's IO lanes.
        val ioExecutor = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "hans-app-server-io-$generation").apply { isDaemon = true }
        }

        @Volatile var ready = false
        @Volatile var effectiveSqliteHome: File? = null
        @Volatile var initialized = false
        @Volatile var stopRequested = false
        @Volatile var process: Process? = null
        @Volatile var writer: GenerationStdinWriter? = null
        @Volatile var stdoutFuture: Future<*>? = null
        @Volatile var stderrFuture: Future<*>? = null
        @Volatile var initializeTimeout: ScheduledFuture<*>? = null
        private val callbackFailed = AtomicBoolean(false)

        fun dispatchState(
            operationId: Long,
            state: Int,
            detail: String,
            allowStopping: Boolean = false,
        ) {
            if ((!allowStopping && stopRequested) || active !== this || callbackFailed.get()) return
            dispatch {
                callback.onSessionState(
                    operationId,
                    generation,
                    sequencer.nextEvent(generation),
                    state,
                    runtimePidProvider(),
                    AppServerSessionContract.boundedDetail(detail),
                )
            }
        }

        fun dispatchFrame(bytes: ByteArray) {
            if (stopRequested || active !== this || callbackFailed.get()) return
            val chunks = runCatching { BinderFrameChunker.chunks(bytes) }.getOrElse {
                dispatchNotice(
                    code = AppServerSessionContract.NOTICE_SERVER_FRAME_OVERSIZED,
                    relatedSequence = 0,
                    detail = "App Server frame could not be chunked",
                )
                return
            }
            dispatch {
                val eventSequence = sequencer.nextEvent(generation)
                for (chunk in chunks) {
                    callback.onFrameChunk(
                        generation,
                        eventSequence,
                        chunk.index,
                        chunk.count,
                        chunk.totalBytes,
                        chunk.payload,
                    )
                }
            }
        }

        fun dispatchNotice(code: Int, relatedSequence: Long, detail: String) {
            if (stopRequested || active !== this || callbackFailed.get()) return
            dispatch {
                callback.onTransportNotice(
                    generation,
                    sequencer.nextEvent(generation),
                    code,
                    relatedSequence,
                    AppServerSessionContract.boundedDetail(detail),
                )
            }
        }

        private fun dispatch(block: () -> Unit) {
            try {
                synchronized(callbackLock) { block() }
            } catch (_: Exception) {
                if (callbackFailed.compareAndSet(false, true)) {
                    commandExecutor.execute { handleBinderDeath(callback.asBinder()) }
                }
            }
        }
    }
}

/**
 * One bounded stdin owner per process generation. Neither queue admission nor cancellation
 * waits for pipe IO. In-flight bytes count toward both limits, so a stalled process cannot
 * grow an unbounded writer backlog. A retired lane can never write to its successor's pipe.
 */
internal class GenerationStdinWriter(
    generation: Long,
    private val output: OutputStream,
    private val onFailure: () -> Unit,
    private val maximumOutstandingBytes: Int = 2 * AppServerSessionContract.MAX_CLIENT_FRAME_BYTES,
    private val maximumOutstandingFrames: Int = 64,
) {
    private val lock = Object()
    private val queued = ArrayDeque<ByteArray>()
    @Volatile private var accepting = true
    private var closeRequested = false
    private var outstandingBytes = 0
    private var outstandingFrames = 0

    init {
        require(maximumOutstandingBytes > 0 && maximumOutstandingFrames > 0)
        Thread(::writeLoop, "hans-app-server-stdin-$generation").apply {
            isDaemon = true
            start()
        }
    }

    fun offerFrame(bytes: ByteArray): Boolean = synchronized(lock) {
        if (!accepting || bytes.isEmpty() ||
            bytes.size >= maximumOutstandingBytes ||
            bytes.size + 1 > maximumOutstandingBytes - outstandingBytes ||
            outstandingFrames >= maximumOutstandingFrames
        ) return@synchronized false
        queued.addLast(bytes.copyOf())
        outstandingBytes += bytes.size + 1
        outstandingFrames += 1
        lock.notifyAll()
        true
    }

    fun cancelPending() = synchronized(lock) {
        accepting = false
        discardQueued()
        lock.notifyAll()
    }

    /** Called only after the owning process has been destroyed or observed terminated. */
    fun closeAfterProcessTermination() = synchronized(lock) {
        accepting = false
        discardQueued()
        closeRequested = true
        lock.notifyAll()
    }

    private fun discardQueued() {
        queued.forEach { outstandingBytes -= it.size + 1 }
        outstandingFrames -= queued.size
        queued.clear()
    }

    private fun writeLoop() {
        try {
            while (true) {
                val frame = synchronized(lock) {
                    while (accepting && queued.isEmpty()) lock.wait()
                    if (accepting) queued.removeFirst() else null
                } ?: break
                try {
                    if (accepting) {
                        output.write(frame)
                        if (accepting) {
                            output.write('\n'.code)
                            output.flush()
                        }
                    }
                } finally {
                    synchronized(lock) {
                        outstandingBytes -= frame.size + 1
                        outstandingFrames -= 1
                    }
                }
            }
        } catch (_: Exception) {
            val report = synchronized(lock) {
                val wasAccepting = accepting
                accepting = false
                discardQueued()
                wasAccepting
            }
            if (report) runCatching(onFailure)
        } finally {
            synchronized(lock) {
                while (!closeRequested) {
                    try {
                        lock.wait()
                    } catch (_: InterruptedException) {
                        // Interrupting a write is not process-termination evidence.
                    }
                }
            }
            runCatching { output.close() }
        }
    }
}
