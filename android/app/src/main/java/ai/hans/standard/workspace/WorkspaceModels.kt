package ai.hans.standard.workspace

import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/**
 * Opaque, content-addressed identifier for an immutable private workspace snapshot.
 *
 * Paths never cross the Codex/App-Server boundary. Callers retain this handle and
 * ask [PrivateWorkspaceStore] for narrowly scoped file leases instead.
 */
@JvmInline
value class WorkspaceHandle(val value: String) {
    init {
        require(SHA_256.matches(value)) { "Invalid workspace handle" }
    }

    override fun toString(): String = value

    private companion object {
        val SHA_256 = Regex("[0-9a-f]{64}")
    }
}

data class WorkspaceFileEntry(
    val relativePath: String,
    val byteCount: Long,
    val sha256: String,
) {
    init {
        WorkspacePaths.requireSafeRelativePath(relativePath)
        require(byteCount >= 0L) { "Negative workspace file size" }
        require(SHA_256.matches(sha256)) { "Invalid workspace file digest" }
    }

    private companion object {
        val SHA_256 = Regex("[0-9a-f]{64}")
    }
}

data class WorkspaceManifest(
    val files: List<WorkspaceFileEntry>,
) {
    init {
        require(files.size <= WorkspaceQuotas.ABSOLUTE_MAX_FILES) { "Too many workspace files" }
        require(files.map { it.relativePath }.distinct().size == files.size) {
            "Duplicate workspace path"
        }
        require(files == files.sortedBy { it.relativePath }) {
            "Workspace manifest must be path-sorted"
        }
    }

    val byteCount: Long = files.fold(0L) { total, file -> Math.addExact(total, file.byteCount) }

    val contentDigest: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(MANIFEST_VERSION)
        files.forEach { entry ->
            digest.update(entry.relativePath.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            digest.update(entry.byteCount.toString().toByteArray(StandardCharsets.US_ASCII))
            digest.update(0)
            digest.update(entry.sha256.toByteArray(StandardCharsets.US_ASCII))
            digest.update('\n'.code.toByte())
        }
        digest.digest().toHex()
    }

    fun entry(path: String): WorkspaceFileEntry? {
        WorkspacePaths.requireSafeRelativePath(path)
        return files.binarySearchBy(path) { it.relativePath }
            .takeIf { it >= 0 }
            ?.let(files::get)
    }

    companion object {
        private val MANIFEST_VERSION = "hans-workspace-manifest-v1\n".toByteArray(StandardCharsets.US_ASCII)

        fun scan(directory: File, quotas: WorkspaceQuotas = WorkspaceQuotas()): WorkspaceManifest {
            val canonicalRoot = directory.canonicalFile
            require(canonicalRoot.isDirectory) { "Workspace snapshot is not a directory" }
            require(!Files.isSymbolicLink(canonicalRoot.toPath())) { "Workspace root is a symbolic link" }

            val entries = mutableListOf<WorkspaceFileEntry>()
            var totalBytes = 0L
            Files.walk(canonicalRoot.toPath()).use { paths ->
                paths.forEach { path ->
                    if (path == canonicalRoot.toPath()) return@forEach
                    require(!Files.isSymbolicLink(path)) { "Workspace contains a symbolic link" }
                    val relative = canonicalRoot.toPath().relativize(path).joinToString("/") { it.toString() }
                    WorkspacePaths.requireSafeRelativePath(relative)
                    when {
                        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> Unit
                        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> {
                            require(entries.size < quotas.maxFiles) { "Workspace file-count limit exceeded" }
                            val bytes = Files.size(path)
                            require(bytes <= quotas.maxSingleFileBytes) { "Workspace file-size limit exceeded" }
                            totalBytes = Math.addExact(totalBytes, bytes)
                            require(totalBytes <= quotas.maxTotalBytes) { "Workspace byte limit exceeded" }
                            entries += WorkspaceFileEntry(
                                relativePath = relative,
                                byteCount = bytes,
                                sha256 = sha256(path.toFile()),
                            )
                        }
                        else -> error("Workspace contains an unsupported filesystem entry")
                    }
                }
            }
            return WorkspaceManifest(entries.sortedBy { it.relativePath })
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
            return digest.digest().toHex()
        }
    }
}

data class WorkspaceSnapshot(
    val handle: WorkspaceHandle,
    val manifest: WorkspaceManifest,
)

data class WorkspaceQuotas(
    val maxFiles: Int = DEFAULT_MAX_FILES,
    val maxSingleFileBytes: Long = DEFAULT_MAX_SINGLE_FILE_BYTES,
    val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
) {
    init {
        require(maxFiles in 1..ABSOLUTE_MAX_FILES)
        require(maxSingleFileBytes in 1..ABSOLUTE_MAX_SINGLE_FILE_BYTES)
        require(maxTotalBytes in maxSingleFileBytes..ABSOLUTE_MAX_TOTAL_BYTES)
    }

    companion object {
        const val DEFAULT_MAX_FILES = 10_000
        const val ABSOLUTE_MAX_FILES = 100_000
        const val DEFAULT_MAX_SINGLE_FILE_BYTES = 128L * 1024L * 1024L
        const val ABSOLUTE_MAX_SINGLE_FILE_BYTES = 2L * 1024L * 1024L * 1024L
        const val DEFAULT_MAX_TOTAL_BYTES = 512L * 1024L * 1024L
        const val ABSOLUTE_MAX_TOTAL_BYTES = 8L * 1024L * 1024L * 1024L
    }
}

enum class WorkspaceSyncActionKind {
    UNCHANGED,
    KEEP_LOCAL,
    APPLY_EXTERNAL,
    DELETE_LOCAL,
    CONFLICT,
}

data class WorkspaceSyncAction(
    val relativePath: String,
    val kind: WorkspaceSyncActionKind,
    val base: WorkspaceFileEntry?,
    val local: WorkspaceFileEntry?,
    val external: WorkspaceFileEntry?,
)

data class WorkspaceSyncPlan(val actions: List<WorkspaceSyncAction>) {
    val conflicts: List<WorkspaceSyncAction> = actions.filter { it.kind == WorkspaceSyncActionKind.CONFLICT }
    val canApplyWithoutDataLoss: Boolean = conflicts.isEmpty()
}

/** Three-way planning only. Applying a conflict is deliberately impossible here. */
object WorkspaceSyncPlanner {
    fun plan(
        base: WorkspaceManifest,
        local: WorkspaceManifest,
        external: WorkspaceManifest,
    ): WorkspaceSyncPlan {
        val baseByPath = base.files.associateBy { it.relativePath }
        val localByPath = local.files.associateBy { it.relativePath }
        val externalByPath = external.files.associateBy { it.relativePath }
        val allPaths = (baseByPath.keys + localByPath.keys + externalByPath.keys).sorted()
        return WorkspaceSyncPlan(
            allPaths.map { path ->
                val baseEntry = baseByPath[path]
                val localEntry = localByPath[path]
                val externalEntry = externalByPath[path]
                val localChanged = !sameContent(baseEntry, localEntry)
                val externalChanged = !sameContent(baseEntry, externalEntry)
                val kind = when {
                    sameContent(localEntry, externalEntry) -> WorkspaceSyncActionKind.UNCHANGED
                    localChanged && externalChanged -> WorkspaceSyncActionKind.CONFLICT
                    localChanged -> WorkspaceSyncActionKind.KEEP_LOCAL
                    externalEntry == null -> WorkspaceSyncActionKind.DELETE_LOCAL
                    externalChanged -> WorkspaceSyncActionKind.APPLY_EXTERNAL
                    else -> WorkspaceSyncActionKind.UNCHANGED
                }
                WorkspaceSyncAction(path, kind, baseEntry, localEntry, externalEntry)
            },
        )
    }

    private fun sameContent(first: WorkspaceFileEntry?, second: WorkspaceFileEntry?): Boolean =
        first?.sha256 == second?.sha256 && first?.byteCount == second?.byteCount
}

internal object WorkspacePaths {
    private const val MAX_PATH_BYTES = 4_096
    private const val MAX_SEGMENT_BYTES = 255

    fun requireSafeRelativePath(path: String) {
        require(path.isNotBlank()) { "Empty workspace path" }
        require(path.toByteArray(StandardCharsets.UTF_8).size <= MAX_PATH_BYTES) { "Workspace path is too long" }
        require('\\' !in path) { "Workspace paths use forward slashes" }
        require(!path.startsWith('/')) { "Absolute workspace path" }
        require(!path.endsWith('/')) { "Workspace file path ends with a slash" }
        val segments = path.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) { "Unsafe workspace path" }
        require(segments.all { segment ->
            segment.toByteArray(StandardCharsets.UTF_8).size <= MAX_SEGMENT_BYTES &&
                segment.none { character -> character == '\u0000' || character.isISOControl() }
        }) { "Unsafe workspace path segment" }
    }
}

internal fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
