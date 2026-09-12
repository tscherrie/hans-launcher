package ai.hans.standard.runtime.python.resolver

import ai.hans.standard.runtime.python.PythonEnvironmentContract
import ai.hans.standard.runtime.python.PythonEnvironmentLock
import ai.hans.standard.runtime.python.PythonEnvironmentResolutionRequest
import ai.hans.standard.runtime.python.PythonEnvironmentTarget
import ai.hans.standard.runtime.python.PythonNativeCatalogPin
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

object PythonResolverLimits {
    const val MAX_PROJECTS = 128
    const val MAX_CANDIDATES = 1_024
    const val MAX_CANDIDATES_PER_PROJECT = 128
    const val MAX_SIMPLE_FILES = 4_096
    const val MAX_DEPENDENCIES_PER_CANDIDATE = 256
    const val MAX_DEPENDENCY_BYTES = 2_048
    const val MAX_SIMPLE_JSON_BYTES = 4 * 1024 * 1024
    const val MAX_CORE_METADATA_BYTES = 1024 * 1024
    const val MAX_TOTAL_METADATA_BYTES = 16L * 1024L * 1024L
    const val MAX_WORKER_ARCHIVE_BYTES = 24 * 1024 * 1024
    const val MAX_WORKER_RESULT_BYTES = 512 * 1024
    const val MAX_RESOLUTION_ROUNDS = 128
    const val MAX_CACHE_ENTRIES = 512
    const val MAX_CACHE_BYTES = 32L * 1024L * 1024L
    const val RESOLUTION_TIMEOUT_MILLIS = 4 * 60 * 1_000L
}

enum class PythonResolutionErrorCode {
    INVALID_REQUIREMENT,
    INDEX_PROTOCOL_ERROR,
    INDEX_UNAVAILABLE,
    METADATA_INVALID,
    PACKAGE_INCOMPATIBLE,
    DEPENDENCY_CONFLICT,
    RESOLUTION_TOO_COMPLEX,
    RESOURCE_LIMIT_EXCEEDED,
    HASH_MISMATCH,
    CANCELLED,
    WORKER_UNAVAILABLE,
    WORKER_PROTOCOL_ERROR,
}

open class PythonResolutionException(
    val code: PythonResolutionErrorCode,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class PythonResolutionCancelledException : PythonResolutionException(
    PythonResolutionErrorCode.CANCELLED,
    "Python dependency resolution was cancelled",
)

/** Cancellation is push-based so an in-flight HTTP call can be interrupted immediately. */
interface PythonResolutionCancellation {
    fun isCancelled(): Boolean
    fun onCancel(action: () -> Unit): Closeable

    fun throwIfCancelled() {
        if (isCancelled()) throw PythonResolutionCancelledException()
    }

    companion object {
        val NONE = object : PythonResolutionCancellation {
            override fun isCancelled() = false
            override fun onCancel(action: () -> Unit) = Closeable {}
        }
    }
}

class MutablePythonResolutionCancellation : PythonResolutionCancellation {
    private val cancelled = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    override fun isCancelled(): Boolean = cancelled.get()

    override fun onCancel(action: () -> Unit): Closeable {
        if (cancelled.get()) {
            action()
            return Closeable {}
        }
        listeners += action
        if (cancelled.get() && listeners.remove(action)) action()
        return Closeable { listeners.remove(action) }
    }

    fun cancel(): Boolean {
        if (!cancelled.compareAndSet(false, true)) return false
        listeners.toList().forEach { runCatching(it) }
        listeners.clear()
        return true
    }
}

data class PythonResolverCandidate(
    val normalizedName: String,
    val version: String,
    val fileName: String,
    val sourceUri: String,
    val sha256: String,
    val sizeBytes: Long,
    val requiresPython: String?,
    val yanked: Boolean,
    val requiresDist: List<String>,
    /** Non-null only for an artifact already present in the signed APK catalog. */
    val nativePin: PythonNativeCatalogPin? = null,
) {
    init {
        require(PythonEnvironmentContract.normalizePackageName(normalizedName) == normalizedName)
        require(PythonEnvironmentContract.isPackageName(normalizedName))
        require(PythonEnvironmentContract.isVersion(version))
        require(PythonEnvironmentContract.isWheelFileName(fileName))
        require(PythonEnvironmentContract.isSha256(sha256))
        require(sizeBytes in 1..(64L * 1024L * 1024L))
        require(sourceUri.length <= 2_048 && if (nativePin == null) {
            sourceUri.startsWith("https://")
        } else {
            sourceUri.startsWith("apk-native://") &&
                nativePin.normalizedName == normalizedName &&
                nativePin.version == version &&
                nativePin.payloadSha256 == sha256
        })
        require(requiresPython == null || requiresPython.length <= 512)
        require(requiresDist.size <= PythonResolverLimits.MAX_DEPENDENCIES_PER_CANDIDATE)
        requiresDist.forEach {
            require(it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <=
                PythonResolverLimits.MAX_DEPENDENCY_BYTES)
        }
    }
}

data class PythonResolverProject(
    val normalizedName: String,
    val candidates: List<PythonResolverCandidate>,
    /** Bytes fetched from the Simple response and all PEP 658 metadata for this project. */
    val metadataBytes: Long = 0L,
) {
    init {
        require(PythonEnvironmentContract.normalizePackageName(normalizedName) == normalizedName)
        require(candidates.size <= PythonResolverLimits.MAX_CANDIDATES_PER_PROJECT)
        require(candidates.all { it.normalizedName == normalizedName })
        require(candidates.map { it.fileName }.distinct().size == candidates.size)
        require(metadataBytes in 0L..PythonResolverLimits.MAX_TOTAL_METADATA_BYTES)
    }
}

sealed interface PythonResolverWorkerOutcome {
    data class NeedsProjects(val normalizedNames: Set<String>) : PythonResolverWorkerOutcome
    data class Resolved(val selections: List<PythonResolverSelection>) : PythonResolverWorkerOutcome
    data class Failed(
        val code: PythonResolutionErrorCode,
        val detail: String? = null,
    ) : PythonResolverWorkerOutcome
}

data class PythonResolverSelection(
    val normalizedName: String,
    val version: String,
    val fileName: String,
)

fun interface PythonResolverWorker {
    fun resolve(
        request: PythonEnvironmentResolutionRequest,
        projects: Map<String, PythonResolverProject>,
        cancellation: PythonResolutionCancellation,
    ): PythonResolverWorkerOutcome
}

fun interface PythonPackageCatalog {
    fun project(
        normalizedName: String,
        target: PythonEnvironmentTarget,
        cancellation: PythonResolutionCancellation,
    ): PythonResolverProject
}

/** Complete resolution plus exact lock proof; the wheel bytes are downloaded separately. */
interface CancellablePythonEnvironmentResolver {
    fun resolve(
        request: PythonEnvironmentResolutionRequest,
        cancellation: PythonResolutionCancellation = PythonResolutionCancellation.NONE,
    ): PythonEnvironmentLock
}
