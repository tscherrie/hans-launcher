package ai.hans.standard.workspace

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

/**
 * Immutable, transactional workspace snapshots below an app-private boundary.
 *
 * A snapshot is assembled below `.stage-*`, verified without following links,
 * and atomically renamed to its content digest. Interrupted transactions never
 * become addressable. The store intentionally exposes no arbitrary path API.
 */
class PrivateWorkspaceStore(
    requestedRoot: File,
    privateBoundary: File,
    private val quotas: WorkspaceQuotas = WorkspaceQuotas(),
) {
    /** Immutable limits for bounded adapters such as archive extraction and SAF import. */
    val limits: WorkspaceQuotas = quotas

    private val boundary = privateBoundary.canonicalFile
    private val root = requestedRoot.canonicalFile
    private val monitor = Any()

    init {
        require(boundary.isDirectory || boundary.mkdirs()) { "Private workspace boundary is unavailable" }
        require(!Files.isSymbolicLink(boundary.toPath())) { "Private workspace boundary is a symbolic link" }
        require(root == boundary || root.path.startsWith(boundary.path + File.separator)) {
            "Workspace storage must remain below the app-private boundary"
        }
        require(root.isDirectory || root.mkdirs()) { "Private workspace storage is unavailable" }
        requireSafeRoot()
    }

    fun begin(seed: WorkspaceHandle? = null): Transaction = synchronized(monitor) {
        requireSafeRoot()
        val staging = File(root, "$STAGING_PREFIX${UUID.randomUUID()}")
        require(staging.mkdir()) { "Could not create workspace transaction" }
        try {
            if (seed != null) {
                snapshot(seed)
                copySeed(snapshotDirectory(seed), staging)
            }
            Transaction(staging)
        } catch (error: Throwable) {
            deleteTreeWithoutFollowingLinks(staging)
            throw error
        }
    }

    fun snapshot(handle: WorkspaceHandle): WorkspaceSnapshot = synchronized(monitor) {
        requireSafeRoot()
        val directory = snapshotDirectory(handle)
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) {
            "Unknown workspace handle"
        }
        val manifest = WorkspaceManifest.scan(directory, quotas)
        check(manifest.contentDigest == handle.value) { "Workspace snapshot digest mismatch" }
        WorkspaceSnapshot(handle, manifest)
    }

    /**
     * Bounded, explicit inventory for the Workbench. This is never polled: callers refresh after
     * a committed operation or when the Workbench becomes visible. Every returned snapshot is
     * rescanned and content-address verified before it crosses the store boundary.
     */
    fun listSnapshots(offset: Int = 0, limit: Int = 100): List<WorkspaceSnapshot> = synchronized(monitor) {
        require(offset in 0..1_000_000) { "Invalid workspace inventory offset" }
        require(limit in 1..500) { "Invalid workspace inventory limit" }
        requireSafeRoot()
        root.listFiles().orEmpty()
            .asSequence()
            .filter { candidate ->
                candidate.isDirectory &&
                    !Files.isSymbolicLink(candidate.toPath()) &&
                    !candidate.name.startsWith(STAGING_PREFIX) &&
                    candidate.name.matches(Regex("[0-9a-f]{64}"))
            }
            .sortedBy(File::getName)
            .drop(offset)
            .take(limit)
            .map { directory -> snapshot(WorkspaceHandle(directory.name)) }
            .toList()
    }

    fun openFile(handle: WorkspaceHandle, relativePath: String): InputStream = synchronized(monitor) {
        WorkspacePaths.requireSafeRelativePath(relativePath)
        val directory = snapshotDirectory(handle)
        val target = resolveBelow(directory, relativePath)
        require(target.isFile && !Files.isSymbolicLink(target.toPath())) { "Unknown workspace file" }
        check(target.canonicalFile.path.startsWith(directory.canonicalPath + File.separator)) {
            "Workspace file escaped its snapshot"
        }
        FileInputStream(target)
    }

    fun fileForReadLease(handle: WorkspaceHandle, relativePath: String): File = synchronized(monitor) {
        WorkspacePaths.requireSafeRelativePath(relativePath)
        val directory = snapshotDirectory(handle)
        val target = resolveBelow(directory, relativePath)
        require(target.isFile && !Files.isSymbolicLink(target.toPath())) { "Unknown workspace file" }
        check(target.canonicalFile.path.startsWith(directory.canonicalPath + File.separator)) {
            "Workspace file escaped its snapshot"
        }
        target
    }

    /**
     * Exports one committed snapshot while holding the store's verification boundary.
     *
     * The caller receives metadata plus a copier, never a workspace path or retained stream.
     * Each file is opened without following links and checked against its committed byte count
     * and SHA-256 while it crosses the boundary. Archive adapters therefore cannot introduce a
     * path-check/later-open TOCTOU window.
     */
    internal fun <T> withVerifiedReadSnapshot(
        handle: WorkspaceHandle,
        export: (
            snapshot: WorkspaceSnapshot,
            copyFile: (WorkspaceFileEntry, OutputStream) -> Unit,
        ) -> T,
    ): T = synchronized(monitor) {
        val verified = snapshot(handle)
        val directory = snapshotDirectory(handle)
        val byPath = verified.manifest.files.associateBy(WorkspaceFileEntry::relativePath)
        val copier: (WorkspaceFileEntry, OutputStream) -> Unit = { requested, output ->
            require(byPath[requested.relativePath] == requested) {
                "Workspace export entry is not part of the verified snapshot"
            }
            val target = resolveBelow(directory, requested.relativePath)
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            Files.newInputStream(
                target.toPath(),
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS,
            ).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    copied = Math.addExact(copied, count.toLong())
                    require(copied <= requested.byteCount) {
                        "Workspace file changed during verified export"
                    }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
            check(copied == requested.byteCount && digest.digest().toHex() == requested.sha256) {
                "Workspace file changed during verified export"
            }
        }
        export(verified, copier)
    }

    fun cleanupIncompleteTransactions(
        olderThanEpochMillis: Long = System.currentTimeMillis() - DEFAULT_STALE_MILLIS,
    ): Int = synchronized(monitor) {
        requireSafeRoot()
        var removed = 0
        root.listFiles().orEmpty()
            .filter { it.name.startsWith(STAGING_PREFIX) && it.lastModified() <= olderThanEpochMillis }
            .forEach { candidate ->
                deleteTreeWithoutFollowingLinks(candidate)
                if (!Files.exists(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)) removed += 1
            }
        removed
    }

    inner class Transaction internal constructor(
        private val staging: File,
    ) : Closeable {
        private var closed = false
        private var committed = false

        fun write(
            relativePath: String,
            maxBytes: Long = quotas.maxSingleFileBytes,
            writer: (OutputStream) -> Unit,
        ): Long {
            checkOpen()
            WorkspacePaths.requireSafeRelativePath(relativePath)
            require(maxBytes in 1..quotas.maxSingleFileBytes) { "Invalid workspace file limit" }
            val target = resolveBelow(staging, relativePath)
            val parent = requireNotNull(target.parentFile) { "Workspace target has no parent" }
            requireParentDirectories(staging, parent)
            require(!target.exists() && !Files.isSymbolicLink(target.toPath())) {
                "Workspace path already exists"
            }
            val temporary = File(target.parentFile, ".${target.name}.part-${UUID.randomUUID()}")
            try {
                FileOutputStream(temporary).use { fileOutput ->
                    val bounded = WorkspaceBoundedOutputStream(fileOutput, maxBytes)
                    writer(bounded)
                    bounded.flush()
                    fileOutput.fd.sync()
                }
                moveAtomically(temporary, target)
                return target.length()
            } finally {
                if (Files.exists(temporary.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(temporary.toPath())
                }
            }
        }

        fun copy(
            relativePath: String,
            source: InputStream,
            maxBytes: Long = quotas.maxSingleFileBytes,
        ): Long = write(relativePath, maxBytes) { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                if (count > 0) output.write(buffer, 0, count)
            }
        }

        fun delete(relativePath: String): Boolean {
            checkOpen()
            WorkspacePaths.requireSafeRelativePath(relativePath)
            val target = resolveBelow(staging, relativePath)
            if (!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
            require(!Files.isDirectory(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "Workspace delete targets files, not directory trees"
            }
            require(!Files.isSymbolicLink(target.toPath())) { "Workspace delete target is a symbolic link" }
            Files.delete(target.toPath())
            removeEmptyParents(target.parentFile, staging)
            return true
        }

        fun commit(): WorkspaceSnapshot = synchronized(monitor) {
            checkOpen()
            val manifest = WorkspaceManifest.scan(staging, quotas)
            val handle = WorkspaceHandle(manifest.contentDigest)
            val finalDirectory = snapshotDirectory(handle)
            if (Files.exists(finalDirectory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                require(finalDirectory.isDirectory && !Files.isSymbolicLink(finalDirectory.toPath())) {
                    "Workspace content address is occupied"
                }
                val existing = WorkspaceManifest.scan(finalDirectory, quotas)
                check(existing.contentDigest == handle.value) { "Workspace content-address collision" }
                deleteTreeWithoutFollowingLinks(staging)
            } else {
                moveAtomically(staging, finalDirectory)
                syncDirectory(root)
            }
            committed = true
            closed = true
            WorkspaceSnapshot(handle, manifest)
        }

        override fun close() {
            synchronized(monitor) {
                if (closed) return
                closed = true
                if (!committed) deleteTreeWithoutFollowingLinks(staging)
            }
        }

        private fun checkOpen() {
            check(!closed) { "Workspace transaction is closed" }
            require(staging.isDirectory && !Files.isSymbolicLink(staging.toPath())) {
                "Workspace staging directory was replaced"
            }
        }
    }

    private fun snapshotDirectory(handle: WorkspaceHandle): File {
        val directory = File(root, handle.value)
        check(directory.parentFile == root)
        return directory
    }

    private fun requireSafeRoot() {
        require(boundary.isDirectory && !Files.isSymbolicLink(boundary.toPath())) {
            "Private workspace boundary is no longer safe"
        }
        require(root.isDirectory && !Files.isSymbolicLink(root.toPath())) {
            "Private workspace root is no longer safe"
        }
        require(root.canonicalFile == root && root.path.startsWith(boundary.path)) {
            "Private workspace root escaped its boundary"
        }
    }

    private fun copySeed(source: File, destination: File) {
        require(source.isDirectory && !Files.isSymbolicLink(source.toPath())) { "Unknown seed workspace" }
        Files.walkFileTree(
            source.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                    require(!attributes.isSymbolicLink) { "Seed contains a symbolic link" }
                    val relative = source.toPath().relativize(directory)
                    if (relative.toString().isNotEmpty()) {
                        val target = destination.toPath().resolve(relative)
                        Files.createDirectory(target)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    require(attributes.isRegularFile && !attributes.isSymbolicLink) {
                        "Seed contains an unsupported entry"
                    }
                    Files.copy(file, destination.toPath().resolve(source.toPath().relativize(file)))
                    return FileVisitResult.CONTINUE
                }
            },
        )
        WorkspaceManifest.scan(destination, quotas)
    }

    companion object {
        private const val STAGING_PREFIX = ".stage-"
        private const val DEFAULT_STALE_MILLIS = 24L * 60L * 60L * 1_000L
    }
}

private fun resolveBelow(root: File, relativePath: String): File {
    WorkspacePaths.requireSafeRelativePath(relativePath)
    val target = File(root, relativePath.replace('/', File.separatorChar))
    val normalizedRoot = root.toPath().toAbsolutePath().normalize()
    val normalizedTarget = target.toPath().toAbsolutePath().normalize()
    require(normalizedTarget.startsWith(normalizedRoot) && normalizedTarget != normalizedRoot) {
        "Workspace path escaped its root"
    }
    return target
}

private fun requireParentDirectories(root: File, parent: File) {
    val rootPath = root.toPath().toAbsolutePath().normalize()
    val parentPath = parent.toPath().toAbsolutePath().normalize()
    require(parentPath.startsWith(rootPath)) { "Workspace parent escaped its root" }
    var current = root
    rootPath.relativize(parentPath).forEach { segment ->
        current = File(current, segment.toString())
        if (Files.exists(current.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            require(current.isDirectory && !Files.isSymbolicLink(current.toPath())) {
                "Workspace parent is not a regular directory"
            }
        } else {
            require(current.mkdir()) { "Could not create workspace directory" }
        }
    }
}

private fun removeEmptyParents(start: File?, stop: File) {
    var current = start
    while (current != null && current != stop && current.list().orEmpty().isEmpty()) {
        if (!current.delete()) return
        current = current.parentFile
    }
}

private fun moveAtomically(source: File, target: File) {
    try {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        require(source.renameTo(target)) { "Atomic workspace move is unavailable" }
    }
}

private fun syncDirectory(directory: File) {
    runCatching {
        FileInputStream(directory).use { it.fd.sync() }
    }
}

private fun deleteTreeWithoutFollowingLinks(target: File) {
    if (!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(
        target.toPath(),
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.deleteIfExists(directory)
                return FileVisitResult.CONTINUE
            }
        },
    )
}

private class WorkspaceBoundedOutputStream(
    private val delegate: OutputStream,
    private val maxBytes: Long,
) : OutputStream() {
    var byteCount: Long = 0L
        private set

    override fun write(value: Int) {
        reserve(1)
        delegate.write(value)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
        reserve(length.toLong())
        delegate.write(buffer, offset, length)
    }

    override fun flush() = delegate.flush()

    private fun reserve(count: Long) {
        val next = Math.addExact(byteCount, count)
        require(next <= maxBytes) { "Workspace file-size limit exceeded" }
        byteCount = next
    }
}
