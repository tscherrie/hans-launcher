package ai.hans.standard.runtime.python

import java.util.concurrent.atomic.AtomicBoolean

enum class PythonRuntimePhase {
    STOPPED,
    STARTING,
    READY,
    RUNNING,
    CANCELLING,
    DEGRADED,
    CRASHED,
    RESTARTING,
    SHUTTING_DOWN,
}

enum class PythonExecutionStatus {
    SUCCEEDED,
    INVALID_REQUEST,
    BUSY,
    PYTHON_EXCEPTION,
    DEPENDENCY_MISSING,
    PACKAGE_INCOMPATIBLE,
    CAPABILITY_DENIED,
    TIMED_OUT,
    CANCELLED,
    OUTPUT_LIMIT_EXCEEDED,
    PROCESS_DIED,
    RUNTIME_CORRUPT,
    STORAGE_FULL,
    NETWORK_UNAVAILABLE,
}

enum class PythonStreamKind(val wireValue: Int) {
    STDOUT(1),
    STDERR(2),
    PROGRESS(3);

    companion object {
        fun fromWire(value: Int): PythonStreamKind? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class PythonEntrypointKind(val wireName: String) {
    CODE("code"),
    MODULE("module"),
    PLUGIN("plugin"),
}

data class PythonEntrypoint(
    val kind: PythonEntrypointKind,
    val source: String? = null,
    val module: String? = null,
    val function: String? = null,
    val pluginId: String? = null,
    val relativePath: String? = null,
)

data class PythonResourceLimits(
    /** Android elapsed-realtime deadline; it remains meaningful across app processes. */
    val deadlineElapsedRealtimeMillis: Long,
    val maximumStdoutBytes: Int = PythonRuntimeContract.DEFAULT_STDOUT_BYTES,
    val maximumStderrBytes: Int = PythonRuntimeContract.DEFAULT_STDERR_BYTES,
    val maximumResultBytes: Int = PythonRuntimeContract.DEFAULT_RESULT_BYTES,
    val maximumEvents: Int = PythonRuntimeContract.DEFAULT_MAXIMUM_EVENTS,
)

data class PythonExecutionRequest(
    val requestId: String,
    val idempotencyKey: String,
    val environmentDigest: String,
    val entrypoint: PythonEntrypoint,
    /** A bounded JSON value. It need not be an object. */
    val argumentsJson: String,
    val workspaceHandle: String? = null,
    val allowedCapabilities: Set<String> = emptySet(),
    val limits: PythonResourceLimits,
)

data class PythonExecutionResult(
    val requestId: String,
    val status: PythonExecutionStatus,
    /** Bounded arbitrary JSON value returned by the registered Python dispatcher. */
    val valueJson: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val metricsJson: String? = null,
) {
    val succeeded: Boolean get() = status == PythonExecutionStatus.SUCCEEDED
}

data class PythonRuntimeReadiness(
    val ready: Boolean,
    val pythonVersion: String? = null,
    val abi: String? = null,
    val stdlibDigest: String? = null,
    val verifiedImports: Set<String> = emptySet(),
    val writableNativeImports: Boolean = true,
    val errorCode: String? = null,
    val detail: String? = null,
)

data class PythonRuntimeSnapshot(
    val phase: PythonRuntimePhase,
    val generation: Long,
    val runtimePid: Int,
    val activeRequestId: String? = null,
    val readiness: PythonRuntimeReadiness? = null,
    val detail: String? = null,
)

data class PythonStreamChunk(
    val requestId: String,
    val sequence: Long,
    val kind: PythonStreamKind,
    val payload: ByteArray,
)

fun interface PythonStreamListener {
    /** False applies backpressure by terminating the execution as an output-limit failure. */
    fun onChunk(chunk: PythonStreamChunk): Boolean
}

interface PythonExecutionHandle {
    /** Non-blocking and idempotent. True means the request was still cancellable. */
    fun cancel(): Boolean
}

internal class LocalPythonExecutionHandle(
    private val cancellation: () -> Boolean,
) : PythonExecutionHandle {
    private val attempted = AtomicBoolean(false)
    @Volatile private var accepted = false

    override fun cancel(): Boolean {
        if (attempted.compareAndSet(false, true)) accepted = runCatching(cancellation).getOrDefault(false)
        return accepted
    }
}

fun interface PythonResultCallback {
    fun onResult(result: PythonExecutionResult)
}

interface PythonRuntimeGateway {
    fun snapshot(): PythonRuntimeSnapshot

    fun execute(
        request: PythonExecutionRequest,
        streamListener: PythonStreamListener = PythonStreamListener { true },
        callback: PythonResultCallback,
    ): PythonExecutionHandle
}

data class PythonCapabilityRequest(
    val requestId: String,
    val sequence: Long,
    val capabilityJson: String,
)

interface PythonCapabilityHandle {
    fun cancel()
}

fun interface PythonCapabilityGateway {
    /** Implementations schedule asynchronously and complete at most once with a bounded object. */
    fun execute(
        request: PythonCapabilityRequest,
        completion: (String) -> Unit,
    ): PythonCapabilityHandle

    companion object {
        val DENY_ALL = PythonCapabilityGateway { request, completion ->
            completion(PythonRuntimeContract.capabilityFailure(request, "capability_unavailable"))
            object : PythonCapabilityHandle { override fun cancel() = Unit }
        }
    }
}
