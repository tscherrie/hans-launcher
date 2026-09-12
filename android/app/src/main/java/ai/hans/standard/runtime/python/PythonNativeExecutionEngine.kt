package ai.hans.standard.runtime.python

import ai.hans.standard.codex.JsonContract
import android.os.Process
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

internal interface PythonRuntimeObserver {
    fun onState(operationId: Long, snapshot: PythonRuntimeSnapshot)
    fun onResult(operationId: Long, resultJson: String)
    fun onStream(operationId: Long, chunk: PythonStreamChunk): Boolean
    fun onCapabilityRequest(operationId: Long, request: PythonCapabilityRequest)
}

/** Owns one embedded interpreter and serializes all initialize/execute/shutdown calls. */
internal class PythonNativeExecutionEngine(
    private val bootstrapProvider: () -> PythonNativeBootstrapLease,
    private val nativeRuntimeProvider: () -> PythonNativeRuntime = { PythonNativeBridge },
    private val nowElapsedRealtimeMillis: () -> Long,
    private val hardAbort: () -> Unit,
    runtimePid: Int = Process.myPid(),
    private val executionExecutor: java.util.concurrent.ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "hans-python-execution").apply { isDaemon = true }
        },
    private val controlExecutor: java.util.concurrent.ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "hans-python-control").apply { isDaemon = true }
        },
    private val timerExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "hans-python-deadline").apply { isDaemon = true }
        },
) {
    private val state = PythonRuntimeStateMachine(runtimePid)
    private val reserved = AtomicReference<String?>(null)
    private val closed = AtomicBoolean(false)
    private val pendingCapabilities = ConcurrentHashMap<CapabilityKey, CompletableFuture<String>>()
    @Volatile private var nativeRuntime: PythonNativeRuntime? = null
    @Volatile private var bootstrapLease: PythonNativeBootstrapLease? = null
    @Volatile private var currentObserver: PythonRuntimeObserver? = null
    @Volatile private var currentOperationId: Long = 0
    @Volatile private var currentRequest: PythonExecutionRequest? = null
    @Volatile private var userCodeHasRunSinceBootstrap = false

    fun snapshot(): PythonRuntimeSnapshot = state.snapshot()

    fun runReadinessGate(operationId: Long, observer: PythonRuntimeObserver) {
        if (!reserve("readiness:$operationId")) {
            observer.onResult(operationId, PythonRuntimeContract.encodeSnapshot(snapshot()))
            return
        }
        executionExecutor.execute {
            try {
                ensureReady(operationId, observer, restarting = false)
                observer.onResult(operationId, PythonRuntimeContract.encodeSnapshot(snapshot()))
            } finally {
                reserved.set(null)
            }
        }
    }

    fun execute(
        operationId: Long,
        requestJson: String,
        executionLease: PythonNativeExecutionLease,
        observer: PythonRuntimeObserver,
    ) {
        val request = try {
            PythonRuntimeContract.decodeRequest(requestJson, nowElapsedRealtimeMillis())
        } catch (error: Exception) {
            executionLease.close()
            observer.onResult(
                operationId,
                PythonRuntimeContract.encodeResult(
                    PythonExecutionResult(
                        requestId = extractSafeRequestId(requestJson),
                        status = PythonExecutionStatus.INVALID_REQUEST,
                        errorCode = "invalid_request",
                        errorMessage = error.safeMessage(),
                    ),
                ),
            )
            return
        }
        if (executionLease.manifest.requestId != request.requestId ||
            executionLease.manifest.environmentDigest != request.environmentDigest
        ) {
            executionLease.close()
            observer.onResult(
                operationId,
                failure(
                    request.requestId,
                    PythonExecutionStatus.INVALID_REQUEST,
                    "execution_lease_mismatch",
                ),
            )
            return
        }
        if (!reserve(request.requestId)) {
            executionLease.close()
            observer.onResult(
                operationId,
                PythonRuntimeContract.encodeResult(
                    PythonExecutionResult(
                        requestId = request.requestId,
                        status = PythonExecutionStatus.BUSY,
                        errorCode = "runtime_busy",
                        errorMessage = "Another Python operation is active",
                    ),
                ),
            )
            return
        }
        try {
            executionExecutor.execute {
                executeReserved(operationId, request, requestJson, executionLease, observer)
            }
        } catch (error: Throwable) {
            reserved.compareAndSet(request.requestId, null)
            executionLease.close()
            observer.onResult(
                operationId,
                failure(
                    request.requestId,
                    PythonExecutionStatus.PROCESS_DIED,
                    "runtime_closed",
                    error,
                ),
            )
        }
    }

    fun cancel(requestId: String): Boolean {
        if (reserved.get() != requestId) return false
        state.beginCancellation(requestId) ?: return false
        notifyCurrentState()
        controlExecutor.execute { runCatching { nativeRuntime?.cancel(requestId) } }
        return true
    }

    fun completeCapability(requestId: String, sequence: Long, resultJson: String): Boolean {
        val key = CapabilityKey(requestId, sequence)
        val pending = pendingCapabilities[key] ?: return false
        val validated = runCatching {
            PythonRuntimeContract.validateCapabilityResult(resultJson, requestId, sequence)
        }.getOrElse {
            PythonRuntimeContract.capabilityFailure(requestId, sequence, "invalid_capability_result")
        }
        return pending.complete(validated)
    }

    fun restart(operationId: Long, observer: PythonRuntimeObserver) {
        val marker = "restart:$operationId"
        if (!reserve(marker)) {
            currentRequest?.let { cancel(it.requestId) }
            executionExecutor.execute { restartAfterActive(operationId, observer, marker) }
            return
        }
        executionExecutor.execute { restartAfterActive(operationId, observer, marker) }
    }

    fun stop(operationId: Long, observer: PythonRuntimeObserver) {
        val marker = "stop:$operationId"
        if (!reserve(marker)) {
            currentRequest?.let { cancel(it.requestId) }
            executionExecutor.execute { stopAfterActive(operationId, observer, marker) }
            return
        }
        executionExecutor.execute { stopAfterActive(operationId, observer, marker) }
    }

    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        currentRequest?.let { cancel(it.requestId) }
        pendingCapabilities.values.forEach { it.cancel(false) }
        pendingCapabilities.clear()
        // CPython must be finalized on the same serial worker which initialized and executed it.
        // A running nativeExecute stays ahead of this task; the control executor only requests
        // cancellation so the execution worker can reach this lifecycle boundary safely.
        executionExecutor.execute {
            runCatching { nativeRuntime?.shutdown() }
            nativeRuntime = null
            runCatching { state.beginShutdown() }
            runCatching { state.stopped() }
            bootstrapLease?.close()
            bootstrapLease = null
        }
        executionExecutor.shutdown()
        controlExecutor.shutdown()
        timerExecutor.shutdownNow()
    }

    private fun executeReserved(
        operationId: Long,
        request: PythonExecutionRequest,
        requestJson: String,
        executionLease: PythonNativeExecutionLease,
        observer: PythonRuntimeObserver,
    ) {
        currentObserver = observer
        currentOperationId = operationId
        currentRequest = request
        val deadlineTriggered = AtomicBoolean(false)
        var outputFailure: String? = null
        try {
            if (!ensureReady(operationId, observer, restarting = false)) {
                observer.onResult(
                    operationId,
                    failure(request.requestId, PythonExecutionStatus.RUNTIME_CORRUPT, "runtime_not_ready"),
                )
                return
            }
            val hasNativeAuthority = executionLease.manifest.allowedNativeModules.isNotEmpty()
            if (hasNativeAuthority && userCodeHasRunSinceBootstrap &&
                !recycleRuntimeForNativeIsolation(operationId, observer)
            ) {
                observer.onResult(
                    operationId,
                    failure(
                        request.requestId,
                        PythonExecutionStatus.RUNTIME_CORRUPT,
                        "native_isolation_reset_failed",
                    ),
                )
                return
            }
            observer.onState(operationId, state.beginExecution(request.requestId))
            val remaining = request.limits.deadlineElapsedRealtimeMillis - nowElapsedRealtimeMillis()
            val hardAbortTask = AtomicReference<java.util.concurrent.ScheduledFuture<*>?>(null)
            val deadlineTask = timerExecutor.schedule({
                deadlineTriggered.set(true)
                if (cancel(request.requestId)) {
                    hardAbortTask.set(
                        timerExecutor.schedule({
                            if (reserved.get() == request.requestId) hardAbort()
                        }, HARD_ABORT_GRACE_MILLIS, TimeUnit.MILLISECONDS),
                    )
                }
            }, remaining.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            val limiter = PythonOutputLimiter(request)
            val sink = object : PythonNativeEventSink {
                override fun onNativeEvent(eventJson: String) {
                    if (outputFailure != null) return
                    val accepted = runCatching {
                        val event = PythonNativeEventCodec.decode(eventJson) as? PythonNativeEvent.Stream
                            ?: error("Capability request used the stream event channel")
                        limiter.accept(event).all { payload ->
                            observer.onStream(
                                operationId,
                                PythonStreamChunk(
                                    requestId = request.requestId,
                                    sequence = event.sequence,
                                    kind = event.kind,
                                    payload = payload,
                                ),
                            )
                        }
                    }.getOrElse {
                        outputFailure = it.safeMessage()
                        false
                    }
                    if (!accepted) {
                        outputFailure = outputFailure ?: "Python output consumer rejected a chunk"
                        controlExecutor.execute { runCatching { nativeRuntime?.cancel(request.requestId) } }
                    }
                }

                override fun onNativeCapabilityRequest(requestJson: String): String =
                    awaitCapability(operationId, request, observer, requestJson)
            }
            val nativeResult = try {
                // Every execution can mutate interpreter-global state. Native-authorized
                // requests therefore start from and return to a freshly bootstrapped
                // interpreter; this marker also forces a pre-reset when ordinary Python ran
                // immediately before them.
                userCodeHasRunSinceBootstrap = true
                nativeRuntime!!.execute(executionLease.augmentRequest(requestJson), sink)
            } finally {
                deadlineTask.cancel(false)
                hardAbortTask.get()?.cancel(false)
            }
            val result = when {
                outputFailure != null -> PythonExecutionResult(
                    requestId = request.requestId,
                    status = PythonExecutionStatus.OUTPUT_LIMIT_EXCEEDED,
                    errorCode = "output_limit_exceeded",
                    errorMessage = outputFailure,
                )
                deadlineTriggered.get() -> PythonExecutionResult(
                    requestId = request.requestId,
                    status = PythonExecutionStatus.TIMED_OUT,
                    errorCode = "execution_timed_out",
                    errorMessage = "Python execution exceeded its deadline",
                )
                else -> PythonRuntimeContract.decodeResult(nativeResult, request.requestId)
            }
            executionLease.close()
            observer.onState(operationId, state.finishExecution(request.requestId))
            val finalResult = if (hasNativeAuthority &&
                !recycleRuntimeForNativeIsolation(operationId, observer)
            ) {
                PythonExecutionResult(
                    requestId = request.requestId,
                    status = PythonExecutionStatus.RUNTIME_CORRUPT,
                    errorCode = "native_isolation_reset_failed",
                    errorMessage = "Python could not discard request-scoped native state",
                )
            } else {
                result
            }
            observer.onResult(operationId, PythonRuntimeContract.encodeResult(finalResult))
        } catch (error: Throwable) {
            state.crash(error.safeMessage())
            observer.onState(operationId, snapshot())
            observer.onResult(
                operationId,
                failure(request.requestId, PythonExecutionStatus.RUNTIME_CORRUPT, "native_runtime_failed", error),
            )
            runCatching { nativeRuntime?.shutdown() }
            nativeRuntime = null
            userCodeHasRunSinceBootstrap = false
        } finally {
            executionLease.close()
            failPendingCapabilities(request.requestId, "execution_finished")
            val snapshot = state.snapshot()
            if (snapshot.activeRequestId == request.requestId) {
                runCatching { observer.onState(operationId, state.finishExecution(request.requestId)) }
            }
            currentRequest = null
            currentObserver = null
            currentOperationId = 0
            reserved.compareAndSet(request.requestId, null)
        }
    }

    private fun ensureReady(
        operationId: Long,
        observer: PythonRuntimeObserver,
        restarting: Boolean,
    ): Boolean {
        if (state.snapshot().phase == PythonRuntimePhase.READY) return true
        val starting = try {
            state.beginStart(restarting)
        } catch (_: Exception) {
            return false
        }
        observer.onState(operationId, starting)
        val readiness = try {
            val runtime = nativeRuntime ?: nativeRuntimeProvider().also { nativeRuntime = it }
            val bootstrap = bootstrapLease ?: bootstrapProvider().also { bootstrapLease = it }
            PythonRuntimeReadinessGate.evaluate(
                runtime.bootstrap(bootstrap.nativeConfigJson()),
                bootstrap.manifest,
            )
        } catch (error: Throwable) {
            PythonRuntimeReadiness(
                ready = false,
                writableNativeImports = true,
                errorCode = "runtime_bootstrap_failed",
                detail = error.safeMessage(),
            )
        }
        observer.onState(operationId, state.finishStart(readiness))
        if (readiness.ready) userCodeHasRunSinceBootstrap = false
        return readiness.ready
    }

    /**
     * Finalizes every object and extension-module reference around a request which owns
     * request-scoped native authority. The process remains the same isolated Android worker, but
     * CPython is rebuilt from the signed stdlib before any later request can run.
     */
    private fun recycleRuntimeForNativeIsolation(
        operationId: Long,
        observer: PythonRuntimeObserver,
    ): Boolean {
        // A hostile extension may leave a non-daemon Python thread behind. CPython
        // finalization normally drains cleanly, but the isolated worker must not hang
        // forever if that thread refuses to terminate.
        val hardAbortTask = timerExecutor.schedule(
            { hardAbort() },
            NATIVE_RECYCLE_HARD_ABORT_MILLIS,
            TimeUnit.MILLISECONDS,
        )
        return try {
            observer.onState(operationId, state.beginShutdown())
            nativeRuntime?.shutdown()
            nativeRuntime = null
            userCodeHasRunSinceBootstrap = false
            observer.onState(operationId, state.stopped())
            ensureReady(operationId, observer, restarting = true)
        } catch (error: Throwable) {
            nativeRuntime = null
            userCodeHasRunSinceBootstrap = false
            observer.onState(operationId, state.crash(error.safeMessage()))
            false
        } finally {
            hardAbortTask.cancel(false)
        }
    }

    private fun restartAfterActive(
        operationId: Long,
        observer: PythonRuntimeObserver,
        marker: String,
    ) {
        if (!reserved.compareAndSet(null, marker) && reserved.get() != marker) {
            observer.onResult(operationId, PythonRuntimeContract.encodeSnapshot(snapshot()))
            return
        }
        try {
            runCatching { nativeRuntime?.shutdown() }
            nativeRuntime = null
            userCodeHasRunSinceBootstrap = false
            if (state.snapshot().phase != PythonRuntimePhase.STOPPED) {
                state.beginShutdown()
                state.stopped()
            }
            ensureReady(operationId, observer, restarting = true)
            observer.onResult(operationId, PythonRuntimeContract.encodeSnapshot(snapshot()))
        } finally {
            reserved.compareAndSet(marker, null)
        }
    }

    private fun stopAfterActive(
        operationId: Long,
        observer: PythonRuntimeObserver,
        marker: String,
    ) {
        if (!reserved.compareAndSet(null, marker) && reserved.get() != marker) {
            observer.onResult(operationId, PythonRuntimeContract.encodeSnapshot(snapshot()))
            return
        }
        try {
            observer.onState(operationId, state.beginShutdown())
            runCatching { nativeRuntime?.shutdown() }
            nativeRuntime = null
            userCodeHasRunSinceBootstrap = false
            observer.onState(operationId, state.stopped())
            observer.onResult(operationId, PythonRuntimeContract.encodeSnapshot(snapshot()))
        } finally {
            reserved.compareAndSet(marker, null)
        }
    }

    private fun awaitCapability(
        operationId: Long,
        request: PythonExecutionRequest,
        observer: PythonRuntimeObserver,
        raw: String,
    ): String {
        val event = runCatching { PythonNativeEventCodec.decode(raw) as PythonNativeEvent.Capability }
            .getOrElse {
                return PythonRuntimeContract.capabilityFailure(
                    request.requestId,
                    1,
                    "invalid_capability_request",
                )
            }
        if (event.requestId != request.requestId || event.capabilityName !in request.allowedCapabilities) {
            return PythonRuntimeContract.capabilityFailure(
                request.requestId,
                event.sequence,
                "capability_denied",
            )
        }
        val key = CapabilityKey(request.requestId, event.sequence)
        val pending = CompletableFuture<String>()
        if (pendingCapabilities.putIfAbsent(key, pending) != null) {
            return PythonRuntimeContract.capabilityFailure(
                request.requestId,
                event.sequence,
                "duplicate_capability_request",
            )
        }
        return try {
            observer.onCapabilityRequest(
                operationId,
                PythonCapabilityRequest(request.requestId, event.sequence, event.normalizedJson),
            )
            val remaining = (request.limits.deadlineElapsedRealtimeMillis - nowElapsedRealtimeMillis())
                .coerceAtMost(PythonRuntimeContract.MAX_CAPABILITY_WAIT_MILLIS)
            if (remaining <= 0) throw TimeoutException()
            pending.get(remaining, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            PythonRuntimeContract.capabilityFailure(
                request.requestId,
                event.sequence,
                "capability_timed_out",
            )
        } catch (_: Exception) {
            PythonRuntimeContract.capabilityFailure(
                request.requestId,
                event.sequence,
                "capability_failed",
            )
        } finally {
            pendingCapabilities.remove(key, pending)
        }
    }

    private fun notifyCurrentState() {
        val observer = currentObserver ?: return
        runCatching { observer.onState(currentOperationId, snapshot()) }
    }

    private fun reserve(value: String): Boolean = !closed.get() && reserved.compareAndSet(null, value)

    private fun failPendingCapabilities(requestId: String, code: String) {
        pendingCapabilities.entries.filter { it.key.requestId == requestId }.forEach { entry ->
            entry.value.complete(
                PythonRuntimeContract.capabilityFailure(requestId, entry.key.sequence, code),
            )
        }
    }

    private fun extractSafeRequestId(raw: String): String = runCatching {
        val value = JsonContract.parseObject(raw, PythonRuntimeContract.MAX_REQUEST_BYTES)
            .optString("requestId")
        value.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) }
    }.getOrNull() ?: "invalid-request"

    private fun failure(
        requestId: String,
        status: PythonExecutionStatus,
        code: String,
        error: Throwable? = null,
    ): String = PythonRuntimeContract.encodeResult(
        PythonExecutionResult(
            requestId = requestId,
            status = status,
            errorCode = code,
            errorMessage = error?.safeMessage(),
        ),
    )

    private fun Throwable.safeMessage(): String =
        (message ?: javaClass.simpleName).take(PythonRuntimeContract.MAX_ERROR_MESSAGE_BYTES)

    private data class CapabilityKey(val requestId: String, val sequence: Long)

    private companion object {
        const val HARD_ABORT_GRACE_MILLIS = 2_000L
        const val NATIVE_RECYCLE_HARD_ABORT_MILLIS = 10_000L
    }
}
