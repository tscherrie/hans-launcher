package ai.hans.standard.runtime.python.resolver

import ai.hans.standard.runtime.python.PythonEnvironmentArchiveProvider
import ai.hans.standard.runtime.python.PythonEnvironmentArchiveReceipt
import ai.hans.standard.runtime.python.PythonEnvironmentContract
import ai.hans.standard.runtime.python.PythonEnvironmentResolutionRequest
import ai.hans.standard.runtime.python.PythonEntrypoint
import ai.hans.standard.runtime.python.PythonEntrypointKind
import ai.hans.standard.runtime.python.PythonExecutionRequest
import ai.hans.standard.runtime.python.PythonExecutionStatus
import ai.hans.standard.runtime.python.PythonResourceLimits
import ai.hans.standard.runtime.python.PythonResultCallback
import ai.hans.standard.runtime.python.PythonRuntimeGateway
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Immutable descriptor lease for one resolver invocation. */
class PythonResolverArchiveLease internal constructor(
    val receipt: PythonEnvironmentArchiveReceipt,
    private val releaseAction: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) releaseAction()
    }
}

/**
 * Builds per-resolution read-only PYZ files. Candidate metadata crosses the process boundary only
 * through this descriptor-backed archive, never through Binder or writable worker storage.
 */
class PythonResolverArchiveRegistry(
    baseResolverArchive: File,
    rootDirectory: File,
    expectedBaseSha256: String,
) : PythonEnvironmentArchiveProvider {
    private val base = baseResolverArchive.canonicalFile
    private val root = rootDirectory.canonicalFile
    private val lock = Any()
    private val active = mutableMapOf<String, ActiveArchive>()

    init {
        require(base.isFile && !java.nio.file.Files.isSymbolicLink(base.toPath()))
        require(PythonEnvironmentContract.isSha256(expectedBaseSha256))
        require(hash(base) == expectedBaseSha256) { "Python resolver bundle digest mismatch" }
        require(!java.nio.file.Files.isSymbolicLink(rootDirectory.toPath()))
        require(root.mkdirs() || root.isDirectory)
        require(!java.nio.file.Files.isSymbolicLink(root.toPath()))
    }

    fun prepare(
        request: PythonEnvironmentResolutionRequest,
        projects: Map<String, PythonResolverProject>,
        cancellation: PythonResolutionCancellation,
    ): PythonResolverArchiveLease {
        cancellation.throwIfCancelled()
        val requestJson = requestJson(request).toString().toByteArray(Charsets.UTF_8)
        val catalogJson = catalogJson(projects).toString().toByteArray(Charsets.UTF_8)
        if (requestJson.size + catalogJson.size > PythonResolverLimits.MAX_WORKER_ARCHIVE_BYTES) {
            throw PythonResolutionException(
                PythonResolutionErrorCode.RESOURCE_LIMIT_EXCEEDED,
                "Resolver catalog exceeds its archive limit",
            )
        }
        val staging = File(root, ".resolver-${System.nanoTime()}.tmp")
        try {
            ZipOutputStream(FileOutputStream(staging).buffered()).use { output ->
                val names = mutableSetOf<String>()
                ZipFile(base).use { source ->
                    val entries = source.entries().asSequence()
                        .filterNot { it.isDirectory }
                        .sortedBy { it.name }
                        .toList()
                    require(entries.size in 2..512) { "Resolver bundle has an invalid entry count" }
                    entries.forEach { entry ->
                        cancellation.throwIfCancelled()
                        validateEntry(entry.name)
                        require(names.add(entry.name)) { "Duplicate resolver bundle entry" }
                        val payload = source.getInputStream(entry).use { it.readBytesBounded(2 * 1024 * 1024) }
                        output.writeStored(entry.name, payload)
                    }
                }
                mapOf(
                    "hans_resolver_payload/catalog.json" to catalogJson,
                    "hans_resolver_payload/request.json" to requestJson,
                ).toSortedMap().forEach { (name, payload) ->
                    require(names.add(name)) { "Resolver payload collides with its bundle" }
                    output.writeStored(name, payload)
                }
            }
            FileOutputStream(staging, true).use { it.fd.sync() }
            if (staging.length() !in 1..PythonResolverLimits.MAX_WORKER_ARCHIVE_BYTES.toLong()) {
                throw PythonResolutionException(
                    PythonResolutionErrorCode.RESOURCE_LIMIT_EXCEEDED,
                    "Resolver archive exceeds its byte limit",
                )
            }
            val digest = hash(staging)
            val destination = File(root, "$digest.pyz")
            synchronized(lock) {
                if (!destination.exists()) {
                    require(staging.renameTo(destination)) { "Cannot publish resolver archive" }
                    destination.setReadable(true, true)
                    destination.setWritable(false, false)
                    destination.setExecutable(false, false)
                } else {
                    require(
                        destination.isFile &&
                            !java.nio.file.Files.isSymbolicLink(destination.toPath()) &&
                            destination.length() == staging.length() && hash(destination) == digest,
                    ) { "Existing resolver archive is corrupt" }
                }
                val existing = active[digest]
                active[digest] = existing?.copy(references = existing.references + 1)
                    ?: ActiveArchive(destination.canonicalFile, destination.length(), 1)
            }
            val receipt = PythonEnvironmentArchiveReceipt(destination.canonicalFile, digest, destination.length())
            return PythonResolverArchiveLease(receipt) { release(digest) }
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    fun contains(environmentDigest: String): Boolean = synchronized(lock) { environmentDigest in active }

    override fun open(environmentDigest: String): PythonEnvironmentArchiveReceipt = synchronized(lock) {
        val archive = active[environmentDigest] ?: error("Resolver archive is no longer leased")
        require(archive.file.isFile && archive.file.length() == archive.sizeBytes)
        require(hash(archive.file) == environmentDigest) { "Resolver archive changed after publication" }
        PythonEnvironmentArchiveReceipt(archive.file, environmentDigest, archive.sizeBytes)
    }

    private fun release(digest: String) = synchronized(lock) {
        val archive = active[digest] ?: return@synchronized Unit
        if (archive.references > 1) {
            active[digest] = archive.copy(references = archive.references - 1)
        } else {
            active.remove(digest)
            archive.file.setWritable(true, true)
            archive.file.delete()
        }
    }

    private fun requestJson(request: PythonEnvironmentResolutionRequest) = JSONObject()
        .put("schemaVersion", 1)
        .put("pluginId", request.pluginId)
        .put("requirements", JSONArray(request.requirements))
        .put(
            "target",
            JSONObject()
                .put("pythonVersion", request.target.pythonVersion)
                .put("interpreterTag", request.target.interpreterTag)
                .put("androidAbi", request.target.androidAbi)
                .put("minimumAndroidApi", request.target.minimumAndroidApi),
        )

    private fun catalogJson(projects: Map<String, PythonResolverProject>) = JSONObject().also { root ->
        projects.toSortedMap().forEach { (name, project) ->
            root.put(
                name,
                JSONArray(
                    project.candidates.sortedWith(
                        compareBy<PythonResolverCandidate> { it.version }.thenBy { it.fileName },
                    ).map { candidate ->
                        JSONObject()
                            .put("name", candidate.normalizedName)
                            .put("version", candidate.version)
                            .put("filename", candidate.fileName)
                            .put("url", candidate.sourceUri)
                            .put("sha256", candidate.sha256)
                            .put("sizeBytes", candidate.sizeBytes)
                            .put("requiresPython", candidate.requiresPython ?: JSONObject.NULL)
                            .put("yanked", candidate.yanked)
                            .put("requiresDist", JSONArray(candidate.requiresDist))
                    },
                ),
            )
        }
    }

    private fun validateEntry(name: String) {
        require(name.isNotBlank() && !name.startsWith('/') && '\\' !in name && ':' !in name)
        require(name.split('/').all { it.isNotBlank() && it != "." && it != ".." })
        require(name.endsWith(".py") || name.endsWith(".typed")) {
            "Resolver bundle contains a non-source payload"
        }
    }

    private fun ZipOutputStream.writeStored(name: String, payload: ByteArray) {
        val crc = CRC32().also { it.update(payload) }
        putNextEntry(ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = payload.size.toLong()
            compressedSize = payload.size.toLong()
            this.crc = crc.value
            time = 315_532_800_000L
        })
        write(payload)
        closeEntry()
    }

    private fun java.io.InputStream.readBytesBounded(maximum: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(maximum, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            require(output.size() + read <= maximum) { "Resolver source entry exceeds its byte limit" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(file.inputStream()).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }

    private data class ActiveArchive(val file: File, val sizeBytes: Long, val references: Int)
}

/** Routes only explicitly leased resolver digests away from normal plugin environments. */
class ResolverAwarePythonEnvironmentArchiveProvider(
    private val resolver: PythonResolverArchiveRegistry,
    private val environments: PythonEnvironmentArchiveProvider,
) : PythonEnvironmentArchiveProvider {
    override fun open(environmentDigest: String): PythonEnvironmentArchiveReceipt =
        if (resolver.contains(environmentDigest)) resolver.open(environmentDigest)
        else environments.open(environmentDigest)
}

/** Synchronous background-thread adapter around the asynchronous isolated Python gateway. */
class IsolatedPythonResolverWorker(
    private val runtime: PythonRuntimeGateway,
    private val archives: PythonResolverArchiveRegistry,
    private val nowElapsedRealtimeMillis: () -> Long = android.os.SystemClock::elapsedRealtime,
) : PythonResolverWorker {
    private val sequence = AtomicLong()

    override fun resolve(
        request: PythonEnvironmentResolutionRequest,
        projects: Map<String, PythonResolverProject>,
        cancellation: PythonResolutionCancellation,
    ): PythonResolverWorkerOutcome {
        cancellation.throwIfCancelled()
        archives.prepare(request, projects, cancellation).use { archive ->
            val ordinal = sequence.incrementAndGet()
            val id = "resolver-${ordinal.toString(36)}-${archive.receipt.sha256.take(16)}"
            val deadline = nowElapsedRealtimeMillis() + PythonResolverLimits.RESOLUTION_TIMEOUT_MILLIS
            val execution = PythonExecutionRequest(
                requestId = id,
                idempotencyKey = "idem-$id",
                environmentDigest = archive.receipt.sha256,
                entrypoint = PythonEntrypoint(
                    kind = PythonEntrypointKind.MODULE,
                    module = "hans_resolver_worker",
                    function = "resolve",
                ),
                argumentsJson = "{}",
                limits = PythonResourceLimits(
                    deadlineElapsedRealtimeMillis = deadline,
                    maximumStdoutBytes = 8 * 1024,
                    maximumStderrBytes = 64 * 1024,
                    maximumResultBytes = PythonResolverLimits.MAX_WORKER_RESULT_BYTES,
                    maximumEvents = 128,
                ),
            )
            val result = AtomicReference<ai.hans.standard.runtime.python.PythonExecutionResult?>()
            val done = CountDownLatch(1)
            val handle = try {
                runtime.execute(execution, callback = PythonResultCallback {
                    result.set(it)
                    done.countDown()
                })
            } catch (error: Exception) {
                throw PythonResolutionException(
                    PythonResolutionErrorCode.WORKER_UNAVAILABLE,
                    "Python resolver worker could not be started",
                    error,
                )
            }
            cancellation.onCancel {
                handle.cancel()
                done.countDown()
            }.use {
                val wait = (deadline - nowElapsedRealtimeMillis()).coerceAtLeast(1L)
                val completedInTime = try {
                    done.await(wait, TimeUnit.MILLISECONDS)
                } catch (error: InterruptedException) {
                    handle.cancel()
                    Thread.currentThread().interrupt()
                    throw PythonResolutionException(
                        PythonResolutionErrorCode.CANCELLED,
                        "Python dependency resolver wait was interrupted",
                        error,
                    )
                }
                if (!completedInTime) {
                    handle.cancel()
                    throw PythonResolutionException(
                        PythonResolutionErrorCode.RESOLUTION_TOO_COMPLEX,
                        "Python dependency resolver timed out",
                    )
                }
            }
            cancellation.throwIfCancelled()
            val completed = result.get() ?: workerProtocol("Python resolver returned no result")
            if (completed.status != PythonExecutionStatus.SUCCEEDED || completed.valueJson == null) {
                throw PythonResolutionException(
                    PythonResolutionErrorCode.WORKER_UNAVAILABLE,
                    "Python resolver worker failed with ${completed.errorCode ?: completed.status.name}",
                )
            }
            return decode(completed.valueJson, request.pluginId)
        }
    }

    private fun decode(raw: String, pluginId: String): PythonResolverWorkerOutcome {
        if (raw.toByteArray(Charsets.UTF_8).size > PythonResolverLimits.MAX_WORKER_RESULT_BYTES) {
            workerProtocol("Python resolver result exceeds its byte limit")
        }
        val json = try {
            JSONObject(raw)
        } catch (error: Exception) {
            throw PythonResolutionException(
                PythonResolutionErrorCode.WORKER_PROTOCOL_ERROR,
                "Python resolver returned malformed JSON",
                error,
            )
        }
        if (json.optInt("schemaVersion", -1) != 1) workerProtocol("Unsupported resolver result")
        return when (json.optString("status")) {
            "needs_projects" -> {
                val names = json.optJSONArray("projects") ?: workerProtocol("Missing project list")
                if (names.length() !in 1..PythonResolverLimits.MAX_PROJECTS) {
                    workerProtocol("Invalid project request count")
                }
                PythonResolverWorkerOutcome.NeedsProjects(buildSet {
                    repeat(names.length()) { index ->
                        val value = names.opt(index) as? String ?: workerProtocol("Invalid project name")
                        val normalized = PythonEnvironmentContract.normalizePackageName(value)
                        if (value != normalized || !PythonEnvironmentContract.isPackageName(value)) {
                            workerProtocol("Invalid project name")
                        }
                        add(value)
                    }
                })
            }
            "resolved" -> {
                if (json.optString("pluginId") != pluginId) workerProtocol("Resolver plugin mismatch")
                val selected = json.optJSONArray("selected") ?: workerProtocol("Missing selection")
                if (selected.length() > 128) workerProtocol("Too many selected packages")
                PythonResolverWorkerOutcome.Resolved(buildList {
                    repeat(selected.length()) { index ->
                        val item = selected.optJSONObject(index) ?: workerProtocol("Invalid selection")
                        add(
                            PythonResolverSelection(
                                normalizedName = item.optString("name"),
                                version = item.optString("version"),
                                fileName = item.optString("filename"),
                            ),
                        )
                    }
                })
            }
            "failed" -> PythonResolverWorkerOutcome.Failed(
                when (json.optString("errorCode")) {
                    "invalid_requirement" -> PythonResolutionErrorCode.INVALID_REQUIREMENT
                    "dependency_conflict" -> PythonResolutionErrorCode.DEPENDENCY_CONFLICT
                    "resolution_too_complex" -> PythonResolutionErrorCode.RESOLUTION_TOO_COMPLEX
                    "package_incompatible" -> PythonResolutionErrorCode.PACKAGE_INCOMPATIBLE
                    "metadata_invalid" -> PythonResolutionErrorCode.METADATA_INVALID
                    "worker_protocol_error" -> PythonResolutionErrorCode.WORKER_PROTOCOL_ERROR
                    else -> workerProtocol("Unknown resolver failure code")
                },
            )
            else -> workerProtocol("Unknown resolver result status")
        }
    }

    private fun workerProtocol(message: String): Nothing = throw PythonResolutionException(
        PythonResolutionErrorCode.WORKER_PROTOCOL_ERROR,
        message,
    )
}
