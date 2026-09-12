package ai.hans.standard.git

import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitContractsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun remoteUriAcceptsCredentialFreeHttpsAndSshOnly() {
        val https = GitRemoteUri.parsePublic("https://example.com/team/repository.git")
        val ssh = GitRemoteUri.parsePublic("ssh://git@example.com/team/repository.git")

        assertEquals(GitRemoteScheme.HTTPS, https.scheme)
        assertEquals("example.com", https.host)
        assertEquals(GitRemoteScheme.SSH, ssh.scheme)
        assertEquals("example.com", ssh.host)

        assertFailure("git_remote_credentials_forbidden") {
            GitRemoteUri.parsePublic("https://token@example.com/team/repository.git")
        }
        assertFailure("git_remote_credentials_forbidden") {
            GitRemoteUri.parsePublic("https://example.com/team/repository.git?access_token=secret")
        }
        assertFailure("git_remote_credentials_forbidden") {
            GitRemoteUri.parsePublic("ssh://git:secret@example.com/team/repository.git")
        }
        assertFailure("git_remote_scheme_unsupported") {
            GitRemoteUri.parsePublic("file:///tmp/repository.git")
        }
        assertFailure("git_remote_scheme_unsupported") {
            GitRemoteUri.parsePublic("git@example.com:team/repository.git")
        }
    }

    @Test
    fun repositoryIdsAndRelativePathsCannotEscapePrivateStorage() {
        assertEquals("project-1", GitRepositoryId("project-1").value)
        assertEquals("src/main.kt", GitRelativePath("src/main.kt").value)

        listOf("../escape", "/absolute", "src/../escape", ".git/config", "src\\main.kt").forEach {
            assertFailure {
                GitRelativePath(it)
            }
        }
        listOf("../repo", "Repo", "1repo", "repo/child", "").forEach {
            assertFailure { GitRepositoryId(it) }
        }
    }

    @Test
    fun appPrivateRemoteRequiresRealDirectoryBelowBoundary() {
        val boundary = temporary.newFolder("private")
        val remote = boundary.resolve("remote.git").also { assertTrue(it.mkdir()) }
        val outside = temporary.newFolder("outside")

        assertEquals(
            GitRemoteScheme.APP_PRIVATE_FILE,
            GitRemoteUri.appPrivateFile(remote, boundary).scheme,
        )
        assertFailure("git_private_remote_outside_boundary") {
            GitRemoteUri.appPrivateFile(outside, boundary)
        }
    }

    @Test
    fun storeRejectsSymlinkRootsAndRepositoryEntries() {
        val boundary = temporary.newFolder("boundary")
        val realRoot = boundary.resolve("real-root").also { assertTrue(it.mkdir()) }
        val linkedRoot = boundary.toPath().resolve("linked-root")
        Files.createSymbolicLink(linkedRoot, realRoot.toPath())

        assertFailure("git_repository_root_symlink") {
            AppPrivateGitRepositoryStore(linkedRoot.toFile(), boundary)
        }

        val store = AppPrivateGitRepositoryStore(boundary.resolve("repositories"), boundary)
        val id = GitRepositoryId("unsafe")
        val directory = store.prepareNewDirectory(id)
        directory.resolve(".git").mkdir()
        Files.createSymbolicLink(directory.toPath().resolve("escape"), boundary.toPath())
        assertFailure("git_repository_symlink_forbidden") { store.verify(id) }
    }

    @Test
    fun storeRetriesWholeTreeWhenJGitGcLockDisappearsDuringVerification() {
        val boundary = temporary.newFolder("transient-gc-lock")
        val root = boundary.resolve("repositories")
        val id = GitRepositoryId("repository")
        val bootstrapStore = AppPrivateGitRepositoryStore(root, boundary)
        val directory = bootstrapStore.prepareNewDirectory(id)
        val gitDirectory = directory.resolve(".git").also { assertTrue(it.mkdir()) }
        directory.resolve("README.md").writeText("verified\n")
        gitDirectory.resolve("config").writeText("[core]\n")
        val entryLimit = Files.walk(directory.toPath()).use { paths -> paths.count().toInt() }
        val attempts = AtomicInteger()
        val vanishedLock = gitDirectory.resolve("gc.log.lock")
        val originalFailure = NoSuchFileException(vanishedLock.path)
        val store = AppPrivateGitRepositoryStore(
            requestedRoot = root,
            privateBoundary = boundary,
            maxFiles = entryLimit,
            treeWalker = { start, visitor ->
                if (attempts.incrementAndGet() == 1) {
                    vanishedLock.writeText("transient")
                    assertTrue(vanishedLock.delete())
                    Files.walkFileTree(start, visitor)
                    throw originalFailure
                }
                Files.walkFileTree(start, visitor)
            },
        )

        assertEquals(directory.canonicalFile, store.existingDirectory(id).canonicalFile)
        assertEquals(2, attempts.get())
        assertFalse(vanishedLock.exists())
    }

    @Test
    fun storeStopsAfterBoundedRetriesAndRethrowsOriginalMissingFileFailure() {
        val boundary = temporary.newFolder("persistent-missing-file")
        val root = boundary.resolve("repositories")
        val id = GitRepositoryId("repository")
        val bootstrapStore = AppPrivateGitRepositoryStore(root, boundary)
        val directory = bootstrapStore.prepareNewDirectory(id)
        val gitDirectory = directory.resolve(".git").also { assertTrue(it.mkdir()) }
        val attempts = AtomicInteger()
        val originalFailure = NoSuchFileException(gitDirectory.resolve("gc.log.lock").path)
        val store = AppPrivateGitRepositoryStore(
            requestedRoot = root,
            privateBoundary = boundary,
            treeWalker = { _, _ ->
                val attempt = attempts.incrementAndGet()
                if (attempt == 1) throw originalFailure
                throw NoSuchFileException(gitDirectory.resolve("gc.log.lock.$attempt").path)
            },
        )

        val failure = runCatching { store.existingDirectory(id) }.exceptionOrNull()

        assertSame(originalFailure, failure)
        assertEquals(3, attempts.get())
        assertEquals(2, originalFailure.suppressed.size)
    }

    @Test
    fun storeDoesNotRetryOtherTreeTraversalFailures() {
        val boundary = temporary.newFolder("non-transient-tree-failure")
        val root = boundary.resolve("repositories")
        val id = GitRepositoryId("repository")
        val bootstrapStore = AppPrivateGitRepositoryStore(root, boundary)
        val directory = bootstrapStore.prepareNewDirectory(id)
        assertTrue(directory.resolve(".git").mkdir())
        val attempts = AtomicInteger()
        val originalFailure = IOException("permanent traversal failure")
        val store = AppPrivateGitRepositoryStore(
            requestedRoot = root,
            privateBoundary = boundary,
            treeWalker = { _, _ ->
                attempts.incrementAndGet()
                throw originalFailure
            },
        )

        val failure = runCatching { store.existingDirectory(id) }.exceptionOrNull()

        assertSame(originalFailure, failure)
        assertEquals(1, attempts.get())
    }

    @Test
    fun readinessNeverClaimsAnUnprobedTransport() {
        val readiness = GitTransportReadiness(https = true, ssh = false)

        assertTrue(readiness.supports(GitRemoteScheme.HTTPS))
        assertFalse(readiness.supports(GitRemoteScheme.SSH))
        assertTrue(readiness.supports(GitRemoteScheme.APP_PRIVATE_FILE))
    }

    private fun assertFailure(expectedMessage: String? = null, action: () -> Unit) {
        val failure = runCatching(action).exceptionOrNull()
        assertTrue("Expected action to fail", failure != null)
        if (expectedMessage != null) assertEquals(expectedMessage, failure?.message)
    }
}
