package ai.hans.standard.runtime.python

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import java.util.LinkedHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lazy main-process owner of the Python Binder generation. It binds only for real work, fails all
 * ambiguous in-flight executions when the worker dies, then permits the next call to rebind.
 */
class PythonRuntimeSupervisor(
    context: Context,
    private val capabilityGateway: PythonCapabilityGateway,
    private val callbackExecutor: Executor = DEFAULT_PYTHON_CALLBACK_EXECUTOR,
    private val descriptorBroker: PythonRuntimeDescriptorBroker =
        AndroidPythonRuntimeDescriptorBroker(context),
) : PythonRuntimeGateway, AutoCloseable {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val pending = LinkedHashMap<String, PendingExecution>()
    private var closed = false
    private var client: PythonRuntimeClient? = null
    private var lastSnapshot = stoppedSnapshot()
    private var activeBinding: RuntimeServiceConnection? = null
    private var nextBindingGeneration = 1L
    private val sessionNonce = PythonRuntimeFdContract.newSessionNonce()

    /**
     * Android keeps a ServiceConnection registered when its service process dies and may reconnect
     * it automatically. A fresh connection object per bind lets us explicitly retire that stale
     * registration and ignore every late callback from its generation.
     */
    private inner class RuntimeServiceConnection(
        val generation: Long,
    ) : ServiceConnection {
        var binder: IBinder? = null
        var deathRecipient: IBinder.DeathRecipient? = null

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = IPythonRuntimeService.Stub.asInterface(binder)
            val recipient = IBinder.DeathRecipient {
                handleBinderLoss(this, "Python worker died", workerTerminationProven = true)
            }
            val candidate = runCatching {
                binder.linkToDeath(recipient, 0)
                PythonRuntimeClient(
                    runtime = service,
                    sessionNonce = sessionNonce,
                    descriptorBroker = descriptorBroker,
                    capabilityGateway = capabilityGateway,
                    callbackExecutor = callbackExecutor,
                )
            }.getOrNull()
            if (candidate == null) {
                runCatching { binder.unlinkToDeath(recipient, 0) }
                handleBinderLoss(this, "Python worker rejected its protocol")
                return
            }
            val accepted = synchronized(lock) {
                if (closed || activeBinding !== this) false else {
                    this.binder = binder
                    deathRecipient = recipient
                    true
                }
            }
            if (!accepted) {
                runCatching { binder.unlinkToDeath(recipient, 0) }
                detachBinding(this)
                return
            }
            if (runCatching {
                    callbackExecutor.execute {
                        candidate.initialize { result ->
                            val snapshot = result.getOrNull()
                            if (snapshot == null) {
                                handleBinderLoss(
                                    this,
                                    result.exceptionOrNull()?.message ?: "Python worker bootstrap failed",
                                )
                                return@initialize
                            }
                            val work = synchronized(lock) {
                                if (closed || activeBinding !== this || this.binder !== binder) {
                                    return@synchronized emptyList()
                                }
                                client = candidate
                                lastSnapshot = snapshot
                                pending.values.filter { !it.started.get() }
                            }
                            if (work.isEmpty()) {
                                releaseBindingIfIdle(this)
                            } else {
                                work.forEach(::dispatch)
                            }
                        }
                    }
                }.isFailure
            ) {
                handleBinderLoss(this, "Python worker bootstrap could not be scheduled")
            }
        }

        override fun onServiceDisconnected(name: ComponentName) =
            handleBinderLoss(this, "Python worker disconnected")

        override fun onBindingDied(name: ComponentName) =
            handleBinderLoss(this, "Python worker binding died")

        override fun onNullBinding(name: ComponentName) =
            handleBinderLoss(this, "Python worker returned a null binding")
    }

    override fun snapshot(): PythonRuntimeSnapshot = synchronized(lock) {
        client?.snapshot()?.also { lastSnapshot = it } ?: lastSnapshot
    }

    override fun execute(
        request: PythonExecutionRequest,
        streamListener: PythonStreamListener,
        callback: PythonResultCallback,
    ): PythonExecutionHandle {
        val pendingExecution = PendingExecution(request, streamListener, callback)
        val connected = synchronized(lock) {
            if (closed) {
                null
            } else {
                require(!pending.containsKey(request.requestId)) { "Duplicate Python request id" }
                require(pending.size < MAX_PENDING_EXECUTIONS) { "Too many queued Python executions" }
                pending[request.requestId] = pendingExecution
                client
            }
        }
        if (closed) {
            completeProcessDied(pendingExecution, "Python supervisor is closed")
        } else if (connected != null) {
            dispatch(pendingExecution)
        } else {
            bind()
        }
        val cancellation = LocalPythonExecutionHandle { cancel(pendingExecution) }
        return object : PythonExecutionHandle {
            override fun cancel(): Boolean = cancellation.cancel()
            override fun onQuiescent(listener: () -> Unit): Boolean = pendingExecution.physical.onQuiescent(listener)
        }
    }

    override fun close() {
        val (work, binding) = synchronized(lock) {
            if (closed) return
            closed = true
            val values = pending.values.toList()
            pending.clear()
            val ownedBinding = activeBinding
            activeBinding = null
            client = null
            lastSnapshot = stoppedSnapshot(lastSnapshot.generation)
            values to ownedBinding
        }
        work.forEach { pendingExecution ->
            pendingExecution.delegate?.cancel()
            completeProcessDied(pendingExecution, "Python supervisor closed")
        }
        binding?.let(::detachBinding)
    }

    private fun dispatch(work: PendingExecution) {
        synchronized(work.lifecycleLock) {
            if (work.completed.get() || !work.started.compareAndSet(false, true)) return
        }
        val target = synchronized(lock) { client }
        if (target == null) {
            val alreadyCompleted = synchronized(work.lifecycleLock) {
                work.started.set(false)
                work.completed.get()
            }
            if (alreadyCompleted) work.physical.notDispatched()
            bind()
            return
        }
        try {
            val handle = target.execute(work.request, work.streamListener) { result ->
                val accepted = synchronized(lock) { pending.remove(work.request.requestId) === work }
                val complete = synchronized(work.lifecycleLock) { accepted && work.completed.compareAndSet(false, true) }
                if (complete) {
                    try { runCatching { work.callback.onResult(result) } }
                    finally { work.physical.callbackFinished() }
                }
                releaseBindingIfIdle()
            }
            work.delegate = handle
            work.physical.publish(handle)
            if (work.workerTerminationProven.get()) (handle as? PythonWorkerTerminationAware)?.workerTerminated()
            if (work.completed.get()) handle.cancel()
        } catch (_: Exception) {
            // A throwing dispatch may have crossed an effect boundary. Keep physical state
            // unknown instead of converting the synthetic failure callback into a receipt.
            completeProcessDied(work, "Python dispatch failed")
        }
    }

    private fun cancel(work: PendingExecution): Boolean {
        if (work.completed.get()) return false
        val delegate = work.delegate
        if (delegate != null) return delegate.cancel()
        val removed = synchronized(lock) { pending.remove(work.request.requestId) === work }
        val (complete, noDispatch) = synchronized(work.lifecycleLock) {
            (removed && work.completed.compareAndSet(false, true)) to !work.started.get()
        }
        if (complete) {
            try { work.callback.onResult(
                PythonExecutionResult(
                    requestId = work.request.requestId,
                    status = PythonExecutionStatus.CANCELLED,
                    errorCode = if (noDispatch) "cancelled_before_dispatch" else "cancel_requested_dispatch_in_progress",
                ),
            ) } finally { work.physical.callbackFinished() }
            if (noDispatch) work.physical.notDispatched()
            releaseBindingIfIdle()
        }
        return removed
    }

    private fun bind() {
        val binding = synchronized(lock) {
            if (closed || activeBinding != null || pending.isEmpty()) null else {
                RuntimeServiceConnection(nextBindingGeneration++).also { activeBinding = it }
            }
        }
        if (binding == null) return
        val succeeded = runCatching {
            appContext.bindService(
                Intent(appContext, PythonRuntimeService::class.java),
                binding,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)
        if (!succeeded) handleBinderLoss(binding, "Python worker could not be bound")
    }

    private fun handleBinderLoss(binding: RuntimeServiceConnection, detail: String, workerTerminationProven: Boolean = false) {
        val physicallyTerminated = workerTerminationProven ||
            runCatching { binding.binder?.isBinderAlive == false }.getOrDefault(false)
        val work = synchronized(lock) {
            if (activeBinding !== binding) return
            activeBinding = null
            client = null
            lastSnapshot = PythonRuntimeSnapshot(
                phase = PythonRuntimePhase.CRASHED,
                generation = lastSnapshot.generation,
                runtimePid = 0,
                detail = detail,
            )
            val values = pending.values.toList()
            pending.clear()
            values
        }
        detachBinding(binding)
        work.forEach {
            if (physicallyTerminated) {
                it.workerTerminationProven.set(true)
                (it.delegate as? PythonWorkerTerminationAware)?.workerTerminated()
            }
            it.delegate?.cancel()
            completeProcessDied(it, detail)
        }
    }

    private fun completeProcessDied(work: PendingExecution, detail: String) {
        val (complete, noDispatch) = synchronized(work.lifecycleLock) {
            work.completed.compareAndSet(false, true) to !work.started.get()
        }
        if (complete) {
            try { runCatching {
                work.callback.onResult(
                    PythonExecutionResult(
                        requestId = work.request.requestId,
                        status = PythonExecutionStatus.PROCESS_DIED,
                        errorCode = "python_process_died",
                        errorMessage = detail,
                    ),
                )
            } } finally { work.physical.callbackFinished() }
            if (noDispatch) work.physical.notDispatched()
        }
    }

    private fun releaseBindingIfIdle(expectedBinding: RuntimeServiceConnection? = null) {
        val binding = synchronized(lock) {
            val ownedBinding = activeBinding
            if (pending.isNotEmpty() || ownedBinding == null ||
                (expectedBinding != null && ownedBinding !== expectedBinding)
            ) {
                null
            } else {
                activeBinding = null
                client = null
                if (!closed) lastSnapshot = stoppedSnapshot(lastSnapshot.generation)
                ownedBinding
            }
        }
        binding?.let(::detachBinding)
    }

    private fun detachBinding(binding: RuntimeServiceConnection) {
        val (binder, recipient) = synchronized(lock) {
            val ownedBinder = binding.binder
            val ownedRecipient = binding.deathRecipient
            binding.binder = null
            binding.deathRecipient = null
            ownedBinder to ownedRecipient
        }
        if (binder != null && recipient != null) {
            runCatching { binder.unlinkToDeath(recipient, 0) }
        }
        runCatching { appContext.unbindService(binding) }
    }

    private data class PendingExecution(
        val request: PythonExecutionRequest,
        val streamListener: PythonStreamListener,
        val callback: PythonResultCallback,
        val started: AtomicBoolean = AtomicBoolean(false),
        val completed: AtomicBoolean = AtomicBoolean(false),
        val lifecycleLock: Any = Any(),
        val physical: PythonPhysicalExecutionReceipt = PythonPhysicalExecutionReceipt(),
        val workerTerminationProven: AtomicBoolean = AtomicBoolean(false),
        @Volatile var delegate: PythonExecutionHandle? = null,
    )

    private companion object {
        const val MAX_PENDING_EXECUTIONS = 32

        fun stoppedSnapshot(generation: Long = 0) = PythonRuntimeSnapshot(
            phase = PythonRuntimePhase.STOPPED,
            generation = generation,
            runtimePid = 0,
        )
    }
}
