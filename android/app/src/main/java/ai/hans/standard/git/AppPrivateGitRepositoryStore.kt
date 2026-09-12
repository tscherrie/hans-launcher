package ai.hans.standard.git

import java.io.File
import java.net.URI
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Fixed-root repository storage. Callers use opaque IDs and can never supply filesystem paths. */
internal class AppPrivateGitRepositoryStore(
    requestedRoot: File,
    privateBoundary: File,
    private val maxFiles: Int = GitLimits.MAX_REPOSITORY_FILES,
    private val maxBytes: Long = GitLimits.MAX_REPOSITORY_BYTES,
    private val treeWalker: (Path, FileVisitor<Path>) -> Unit = { start, visitor ->
        Files.walkFileTree(start, visitor)
    },
) {
    private val requestedBoundaryPath = privateBoundary.toPath()
    private val requestedRootPath = requestedRoot.toPath()
    private val boundary = privateBoundary.canonicalFile
    private val root = requestedRoot.canonicalFile
    private val locks = ConcurrentHashMap<GitRepositoryId, ReentrantLock>()

    init {
        require(maxFiles in 1..GitLimits.MAX_REPOSITORY_FILES) { "git_repository_file_limit_invalid" }
        require(maxBytes in 1..GitLimits.MAX_REPOSITORY_BYTES) { "git_repository_byte_limit_invalid" }
        require(!Files.isSymbolicLink(requestedBoundaryPath)) { "git_private_boundary_symlink" }
        require(!Files.isSymbolicLink(requestedRootPath)) { "git_repository_root_symlink" }
        require(boundary.isDirectory || boundary.mkdirs()) { "git_private_boundary_unavailable" }
        require(!Files.isSymbolicLink(boundary.toPath())) { "git_private_boundary_symlink" }
        require(root == boundary || root.path.startsWith(boundary.path + File.separator)) {
            "git_repository_root_outside_boundary"
        }
        require(root.isDirectory || root.mkdirs()) { "git_repository_root_unavailable" }
        requireSafeRoot()
    }

    fun <T> withRepositoryLock(repositoryId: GitRepositoryId, block: () -> T): T {
        val lock = locks.computeIfAbsent(repositoryId) { ReentrantLock() }
        return lock.withLock(block)
    }

    fun existingDirectory(repositoryId: GitRepositoryId): File {
        requireSafeRoot()
        val directory = directory(repositoryId)
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) {
            "git_repository_not_found"
        }
        require(File(directory, ".git").isDirectory) { "git_repository_invalid" }
        verifyTree(directory)
        return directory
    }

    fun prepareNewDirectory(repositoryId: GitRepositoryId): File {
        requireSafeRoot()
        val directory = directory(repositoryId)
        require(!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "git_repository_already_exists"
        }
        require(directory.mkdir()) { "git_repository_create_failed" }
        return directory
    }

    fun verify(repositoryId: GitRepositoryId): File {
        val directory = existingDirectory(repositoryId)
        verifyTree(directory)
        return directory
    }

    /** Bounded validation during clone, before JGit has finished creating repository metadata. */
    fun verifyCandidate(repositoryId: GitRepositoryId): File {
        requireSafeRoot()
        val directory = directory(repositoryId)
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) {
            "git_repository_not_found"
        }
        verifyTree(directory)
        return directory
    }

    fun discardIncomplete(repositoryId: GitRepositoryId) {
        val directory = directory(repositoryId)
        if (Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            deleteTreeWithoutFollowingLinks(directory)
        }
    }

    fun appPrivateRemote(value: String): GitRemoteUri {
        val uri = runCatching { URI(value) }.getOrElse {
            throw IllegalArgumentException("git_private_remote_unavailable")
        }
        require(uri.scheme == "file" && uri.userInfo == null && uri.fragment == null) {
            "git_private_remote_unavailable"
        }
        return GitRemoteUri.appPrivateFile(File(uri), boundary)
    }

    private fun directory(repositoryId: GitRepositoryId): File {
        val candidate = File(root, repositoryId.value)
        check(candidate.parentFile == root) { "git_repository_path_escape" }
        return candidate
    }

    private fun requireSafeRoot() {
        require(boundary.isDirectory && !Files.isSymbolicLink(boundary.toPath())) {
            "git_private_boundary_unsafe"
        }
        require(root.isDirectory && !Files.isSymbolicLink(root.toPath())) {
            "git_repository_root_unsafe"
        }
        require(root.canonicalFile == root && (root == boundary || root.path.startsWith(boundary.path + File.separator))) {
            "git_repository_root_escaped"
        }
    }

    private fun verifyTree(directory: File) {
        var originalFailure: NoSuchFileException? = null
        repeat(VERIFY_TREE_MAX_ATTEMPTS) { attempt ->
            try {
                verifyTreeOnce(directory)
                return
            } catch (failure: NoSuchFileException) {
                val firstFailure = originalFailure
                if (firstFailure == null) {
                    originalFailure = failure
                } else if (failure !== firstFailure) {
                    firstFailure.addSuppressed(failure)
                }
                if (attempt == VERIFY_TREE_MAX_ATTEMPTS - 1) {
                    throw checkNotNull(originalFailure)
                }
                LockSupport.parkNanos(VERIFY_TREE_RETRY_DELAY_NANOS)
            }
        }
    }

    private fun verifyTreeOnce(directory: File) {
        var entries = 0
        var bytes = 0L
        treeWalker(
            directory.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    path: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    require(!attributes.isSymbolicLink) { "git_repository_symlink_forbidden" }
                    entries += 1
                    require(entries <= maxFiles) { "git_repository_file_limit_exceeded" }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                    require(!attributes.isSymbolicLink) { "git_repository_symlink_forbidden" }
                    require(attributes.isRegularFile) { "git_repository_entry_unsupported" }
                    entries += 1
                    require(entries <= maxFiles) { "git_repository_file_limit_exceeded" }
                    bytes = Math.addExact(bytes, attributes.size())
                    require(bytes <= maxBytes) { "git_repository_byte_limit_exceeded" }
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private companion object {
        const val VERIFY_TREE_MAX_ATTEMPTS = 3
        const val VERIFY_TREE_RETRY_DELAY_NANOS = 2_000_000L
    }
}

private fun deleteTreeWithoutFollowingLinks(directory: File) {
    Files.walkFileTree(
        directory.toPath(),
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(path)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(path: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.deleteIfExists(path)
                return FileVisitResult.CONTINUE
            }
        },
    )
}
