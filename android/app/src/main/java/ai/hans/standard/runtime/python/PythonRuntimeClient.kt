package ai.hans.standard.runtime.python

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Thin, testable adapter around one authenticated isolated-worker Binder generation. */
class PythonRuntimeClient(
    private val runtime: IPythonRuntimeService,
    private val sessionNonce: String,
    private val descriptorBroker: PythonRuntimeDescriptorBroker,
    private val capabilityGateway: PythonCapabilityGateway,
    private val callbackExecutor: Executor = DEFAULT_PYTHON_CALLBACK_EXECUTOR,
    private val nowElapsedRealtimeMillis: () -> Long = android.os.SystemClock::elapsedRealtime,
) : PythonRuntimeGateway {
    init {
        require(PythonRuntimeFdContract.isSessionNonce(sessionNonce)) { "Invalid Python session" }
        require(runtime.protocolVersion == PythonRuntimeContract.PROTOCOL_VERSION) {
            "Unsupported Python Binder protocol"
        }
    }

    /** Performs the descriptor-backed bootstrap before any queued execution is dispatched. */
    fun initialize(completion: (Result<PythonRuntimeSnapshot>) -> Unit) {
        val operationId = operationIds.next()
        val delivered = AtomicBoolean(false)
        fun finish(result: Result<PythonRuntimeSnapshot>) {
            if (!delivered.compareAndSet(false, true)) return
            if (runCatching { callbackExecutor.execute { completion(result) } }.isFailure) {
                completion(result)
            }
        }
        val callback = object : IPythonRuntimeCallback.Stub() {
            override fun onState(callbackOperationId: Long, stateJson: String) = Unit

            override fun onResult(callbackOperationId: Long, resultJson: String) {
                if (callbackOperationId != operationId) return
                finish(
                    runCatching { PythonRuntimeContract.decodeSnapshot(resultJson) }
                        .mapCatching { snapshot ->
                            require(snapshot.readiness?.ready == true &&
                                snapshot.phase == PythonRuntimePhase.READY) {
                                buildString {
                                    append("Python runtime is not ready")
                                    append("; phase=").append(snapshot.phase)
                                    append("; errorCode=")
                                        .append(snapshot.readiness?.errorCode ?: "none")
                                    append("; detail=")
                                        .append(snapshot.readiness?.detail ?: snapshot.detail ?: "none")
                                    append("; verifiedImports=")
                                        .append(snapshot.readiness?.verifiedImports.orEmpty().sorted())
                                }
                            }
                            snapshot
                        },
                )
            }
        }
        try {
            descriptorBroker.openBootstrap(sessionNonce).use { lease ->
                runtime.runReadinessGate(
                    operationId,
                    lease.manifestJson,
                    lease.descriptor,
                    callback,
                )
            }
        } catch (error: Throwable) {
            finish(Result.failure(error))
        }
    }

    override fun snapshot(): PythonRuntimeSnapshot = try {
        PythonRuntimeContract.decodeSnapshot(runtime.readState(sessionNonce))
    } catch (_: Exception) {
        processDiedSnapshot()
    }

    override fun execute(
        request: PythonExecutionRequest,
        streamListener: PythonStreamListener,
        callback: PythonResultCallback,
    ): PythonExecutionHandle {
        val completed = AtomicBoolean(false)
        val capabilityLock = Any()
        val activeCapabilities = mutableMapOf<Long, PythonCapabilityHandle>()

        fun cancelCapabilities() {
            val handles = synchronized(capabilityLock) {
                activeCapabilities.values.toList().also { activeCapabilities.clear() }
            }
            handles.forEach { runCatching(it::cancel) }
        }
        val operationId = operationIds.next()
        val finish: (PythonExecutionResult) -> Unit = { result ->
            if (completed.compareAndSet(false, true)) {
                cancelCapabilities()
                if (runCatching {
                        callbackExecutor.execute { callback.onResult(result) }
                    }.isFailure
                ) {
                    runCatching { callback.onResult(result) }
                }
            }
        }
        val runtimeCallback = object : IPythonRuntimeCallback.Stub() {
            override fun onState(callbackOperationId: Long, stateJson: String) = Unit

            override fun onResult(callbackOperationId: Long, resultJson: String) {
                if (callbackOperationId != operationId) return
                val result = runCatching {
                    PythonRuntimeContract.decodeResult(resultJson, request.requestId)
                }.getOrElse {
                    PythonExecutionResult(
                        requestId = request.requestId,
                        status = PythonExecutionStatus.RUNTIME_CORRUPT,
                        errorCode = "invalid_runtime_result",
                        errorMessage = it.message,
                    )
                }
                finish(result)
            }
        }
        val streamCallback = object : IPythonStreamCallback.Stub() {
            override fun onChunk(
                callbackOperationId: Long,
                requestId: String,
                sequence: Long,
                streamKind: Int,
                payload: ByteArray,
            ): Boolean {
                if (callbackOperationId != operationId || requestId != request.requestId) return false
                if (payload.size > PythonRuntimeContract.MAX_BINDER_CHUNK_BYTES) return false
                val kind = PythonStreamKind.fromWire(streamKind) ?: return false
                return runCatching {
                    streamListener.onChunk(
                        PythonStreamChunk(requestId, sequence, kind, payload.copyOf()),
                    )
                }.getOrDefault(false)
            }
        }
        val capabilityCallback = object : IPythonCapabilityCallback.Stub() {
            override fun onCapabilityRequest(
                callbackOperationId: Long,
                requestId: String,
                sequence: Long,
                requestJson: String,
            ) {
                if (callbackOperationId != operationId || requestId != request.requestId) return
                val capabilityRequest = PythonCapabilityRequest(requestId, sequence, requestJson)
                val capabilityCompleted = AtomicBoolean(false)
                try {
                    val handle = capabilityGateway.execute(capabilityRequest) { response ->
                        capabilityCompleted.set(true)
                        synchronized(capabilityLock) { activeCapabilities.remove(sequence) }
                        if (!completed.get()) {
                            runCatching {
                                runtime.completeCapability(
                                    sessionNonce,
                                    requestId,
                                    sequence,
                                    response,
                                )
                            }
                        }
                    }
                    val cancelImmediately = synchronized(capabilityLock) {
                        if (completed.get() || capabilityCompleted.get()) {
                            true
                        } else if (activeCapabilities.containsKey(sequence)) {
                            true
                        } else {
                            activeCapabilities[sequence] = handle
                            false
                        }
                    }
                    if (cancelImmediately) handle.cancel()
                } catch (_: Exception) {
                    runCatching {
                        runtime.completeCapability(
                            sessionNonce,
                            requestId,
                            sequence,
                            PythonRuntimeContract.capabilityFailure(
                                capabilityRequest,
                                "capability_gateway_failed",
                            ),
                        )
                    }
                }
            }
        }
        return try {
            val encoded = PythonRuntimeContract.encodeRequest(request, nowElapsedRealtimeMillis())
            descriptorBroker.openExecution(sessionNonce, request).use { lease ->
                runtime.execute(
                    operationId,
                    sessionNonce,
                    encoded,
                    lease.manifestJson,
                    lease.descriptor,
                    lease.workspaceDescriptor,
                    runtimeCallback,
                    streamCallback,
                    capabilityCallback,
                )
            }
            LocalPythonExecutionHandle {
                cancelCapabilities()
                !completed.get() && runtime.cancel(sessionNonce, request.requestId)
            }
        } catch (error: Exception) {
            finish(
                PythonExecutionResult(
                    requestId = request.requestId,
                    status = PythonExecutionStatus.DEPENDENCY_MISSING,
                    errorCode = "python_environment_unavailable",
                    errorMessage = error.message,
                ),
            )
            LocalPythonExecutionHandle { false }
        }
    }

    fun restart(completion: (PythonRuntimeSnapshot) -> Unit) {
        val operationId = operationIds.next()
        runtime.restart(sessionNonce, operationId, snapshotCallback(operationId, completion))
    }

    fun stop(completion: (PythonRuntimeSnapshot) -> Unit) {
        val operationId = operationIds.next()
        runtime.stop(sessionNonce, operationId, snapshotCallback(operationId, completion))
    }

    private fun snapshotCallback(
        operationId: Long,
        completion: (PythonRuntimeSnapshot) -> Unit,
    ): IPythonRuntimeCallback = object : IPythonRuntimeCallback.Stub() {
        override fun onState(callbackOperationId: Long, stateJson: String) = Unit
        override fun onResult(callbackOperationId: Long, resultJson: String) {
            if (callbackOperationId != operationId) return
            runCatching { PythonRuntimeContract.decodeSnapshot(resultJson) }
                .onSuccess(completion)
        }
    }

    private fun processDiedSnapshot() = PythonRuntimeSnapshot(
        phase = PythonRuntimePhase.CRASHED,
        generation = 0,
        runtimePid = 0,
        detail = "Python service is unavailable",
    )

    private companion object {
        val operationIds = OperationIds()
    }
}

private class OperationIds {
    private val next = java.util.concurrent.atomic.AtomicLong(1)
    fun next(): Long = next.getAndUpdate { if (it == Long.MAX_VALUE) 1 else it + 1 }
}

internal val DEFAULT_PYTHON_CALLBACK_EXECUTOR: Executor =
    Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hans-python-callbacks").apply { isDaemon = true }
    }
