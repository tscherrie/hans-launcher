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
        val children = PythonCapabilityExecutionScope(callbackExecutor, callback::onResult)
        val operationId = operationIds.next()
        val finish: (PythonExecutionResult) -> Unit = { result ->
            children.finish(result, workerPhysicallyFinished = true)
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
                // Reserve BEFORE entering another executor. A concurrent worker result or stop
                // must see in-dispatch children even before their handles are returned.
                val child = children.begin(sequence)
                if (child == null) {
                    runCatching { runtime.completeCapability(sessionNonce, requestId, sequence,
                        PythonRuntimeContract.capabilityFailure(capabilityRequest, "capability_dispatch_closed")) }
                    return
                }
                try {
                    val handle = capabilityGateway.execute(capabilityRequest) { response ->
                        if (child.responded.compareAndSet(false, true) && children.acceptsResponses()) {
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
                    children.publish(child, handle)
                } catch (_: Exception) {
                    children.dispatchFailed(child)
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
        var dispatchAttempted = false
        val cancellation = LocalPythonExecutionHandle {
            children.cancelChildren()
            !children.isWorkerFinished() && runtime.cancel(sessionNonce, request.requestId)
        }
        val physicalHandle = object : PythonExecutionHandle, PythonWorkerTerminationAware {
            override fun cancel(): Boolean = cancellation.cancel()
            override fun onQuiescent(listener: () -> Unit): Boolean = children.onQuiescent(listener)
            override fun workerTerminated() {
                children.finish(PythonExecutionResult(request.requestId, PythonExecutionStatus.PROCESS_DIED,
                    errorCode = "python_process_died"), workerPhysicallyFinished = true)
            }
        }
        return try {
            val encoded = PythonRuntimeContract.encodeRequest(request, nowElapsedRealtimeMillis())
            descriptorBroker.openExecution(sessionNonce, request).use { lease ->
                dispatchAttempted = true
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
            physicalHandle
        } catch (error: Exception) {
            children.finish(
                PythonExecutionResult(
                    requestId = request.requestId,
                    status = PythonExecutionStatus.DEPENDENCY_MISSING,
                    errorCode = "python_environment_unavailable",
                    errorMessage = error.message,
                ),
                // A thrown Binder invocation might already have started Python. Only known
                // pre-dispatch failure or confirmed Binder death proves the worker stopped.
                workerPhysicallyFinished = !dispatchAttempted || runCatching { !runtime.asBinder().isBinderAlive }.getOrDefault(false),
            )
            physicalHandle
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

/** Joins real child receipts; unknown support remains pending and can never become a timeout proof. */
internal class PythonCapabilityExecutionScope(
    private val callbackExecutor: Executor,
    private val deliver: (PythonExecutionResult) -> Unit,
) {
    internal class Child(val sequence: Long) {
        val responded = AtomicBoolean(false)
        var handle: PythonCapabilityHandle? = null
        var unknown = false
    }

    private val lock = Any()
    private val children = linkedMapOf<Long, Child>()
    private val seen = mutableSetOf<Long>()
    private val receipt = PythonQuiescenceReceipt()
    private var closed = false
    private var workerFinished = false
    private var result: PythonExecutionResult? = null
    private var deliveryClaimed = false

    val hasUnknownChildren: Boolean get() = synchronized(lock) { children.values.any { it.unknown } }
    fun onQuiescent(listener: () -> Unit): Boolean = receipt.onQuiescent(listener)
    fun acceptsResponses(): Boolean = synchronized(lock) { !closed }
    fun isWorkerFinished(): Boolean = synchronized(lock) { workerFinished }

    fun begin(sequence: Long): Child? = synchronized(lock) {
        if (closed || sequence < 0 || children.size >= 64 || seen.size >= 4_096 || !seen.add(sequence)) null
        else Child(sequence).also { children[sequence] = it }
    }

    fun publish(child: Child, handle: PythonCapabilityHandle) {
        synchronized(lock) {
            if (children[child.sequence] !== child) return
            child.handle = handle
        }
        val supported = runCatching {
            handle.onQuiescent {
                synchronized(lock) { if (children[child.sequence] === child) children.remove(child.sequence) }
                drain()
            }
        }.getOrDefault(false)
        val cancel = synchronized(lock) {
            if (children[child.sequence] !== child) false else {
                child.unknown = !supported
                closed
            }
        }
        if (cancel) runCatching(handle::cancel)
    }

    fun dispatchFailed(child: Child) = synchronized(lock) {
        if (children[child.sequence] === child) child.unknown = true
    }

    fun cancelChildren() {
        val handles = synchronized(lock) {
            closed = true
            children.values.mapNotNull { it.handle }
        }
        handles.forEach { runCatching(it::cancel) }
    }

    fun finish(value: PythonExecutionResult, workerPhysicallyFinished: Boolean) {
        synchronized(lock) {
            closed = true
            if (result == null) result = value
            if (workerPhysicallyFinished) workerFinished = true
        }
        cancelChildren()
        drain()
    }

    private fun drain() {
        val value = synchronized(lock) {
            if (deliveryClaimed || !workerFinished || children.isNotEmpty()) return
            val available = result ?: return
            deliveryClaimed = true
            available
        }
        val delivered = AtomicBoolean(false)
        val task = Runnable {
            if (delivered.compareAndSet(false, true)) {
                try { deliver(value) } finally { receipt.complete() }
            }
        }
        if (runCatching { callbackExecutor.execute(task) }.isFailure) runCatching { task.run() }
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
