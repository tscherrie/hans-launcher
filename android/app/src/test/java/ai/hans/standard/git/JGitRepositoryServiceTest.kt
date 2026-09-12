package ai.hans.standard.git

import java.io.File
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.RefSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JGitRepositoryServiceTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun localLifecycleHasBoundedResultsAndVerifiedPostconditions() {
        val fixture = fixture()
        val id = GitRepositoryId("project")

        val initialized = fixture.service.initialize(id)
        assertTrue(initialized.postcondition.verified)
        assertEquals("main", initialized.value.branch)
        assertEquals(null, initialized.value.headObjectId)

        fixture.repository(id).resolve("README.md").writeText("first line\n")
        val untracked = fixture.service.status(id)
        assertFalse(untracked.clean)
        assertEquals("README.md", untracked.paths.single().path.value)

        val staged = fixture.service.stage(id, listOf(GitRelativePath("README.md")))
        assertTrue(staged.postcondition.verified)
        val stagedDiff = fixture.service.diff(id, GitDiffScope.STAGED)
        assertTrue(stagedDiff.patch.contains("README.md"))
        assertFalse(stagedDiff.truncated)

        val committed = fixture.service.commit(
            id,
            "Initial commit",
            GitIdentity("Hans Test", "hans@example.test"),
        )
        assertTrue(committed.postcondition.verified)
        assertEquals(committed.value.objectId, committed.postcondition.headAfter)
        assertTrue(committed.postcondition.statusAfter.clean)
        assertEquals("Initial commit", fixture.service.log(id, 10).commits.single().shortMessage)

        val branch = fixture.service.createBranch(id, "feature/test")
        assertEquals("feature/test", branch.value.name)
        assertTrue(branch.postcondition.verified)
        assertTrue(fixture.service.branches(id).branches.any { it.name == "feature/test" })

        val checkedOut = fixture.service.checkout(id, "feature/test")
        assertEquals("feature/test", checkedOut.value.branch)
        assertTrue(checkedOut.postcondition.verified)

        fixture.repository(id).resolve("README.md").writeText("x".repeat(4_096))
        val bounded = fixture.service.diff(id, GitDiffScope.WORKTREE, maxBytes = 64)
        assertEquals(64, bounded.byteCount)
        assertTrue(bounded.truncated)
    }

    @Test
    fun cancellationWinsBeforeRepositoryAccess() {
        val fixture = fixture()
        val id = GitRepositoryId("cancelled")

        val failure = runCatching {
            fixture.service.initialize(id, cancellation = GitCancellation { true })
        }.exceptionOrNull()

        assertTrue(failure is GitOperationCancelledException)
        assertFalse(fixture.repository(id).exists())
    }

    @Test
    fun cloneFetchPullAndPushWorkAgainstProvenAppPrivateRemote() {
        val fixture = fixture()
        val remoteDirectory = fixture.boundary.resolve("remote.git")
        val bare = Git.init().setBare(true).setDirectory(remoteDirectory).call()
        val seedDirectory = fixture.boundary.resolve("seed")
        Git.init().setInitialBranch("main").setDirectory(seedDirectory).call().use { seed ->
            seedDirectory.resolve("README.md").writeText("seed\n")
            seed.add().addFilepattern("README.md").call()
            seed.commit()
                .setMessage("seed")
                .setAuthor("Seed", "seed@example.test")
                .setCommitter("Seed", "seed@example.test")
                .call()
            seed.push()
                .setRemote(remoteDirectory.toURI().toASCIIString())
                .setRefSpecs(RefSpec("refs/heads/main:refs/heads/main"))
                .call()
        }
        bare.repository.updateRef(Constants.HEAD, true).link("refs/heads/main")
        bare.close()

        val remote = GitRemoteUri.appPrivateFile(remoteDirectory, fixture.boundary)
        val id = GitRepositoryId("clone")
        val cloned = fixture.service.clone(id, remote)
        assertEquals("main", cloned.value.branch)
        assertEquals("seed\n", fixture.repository(id).resolve("README.md").readText())

        val peerDirectory = fixture.boundary.resolve("peer")
        Git.cloneRepository()
            .setURI(remoteDirectory.toURI().toASCIIString())
            .setDirectory(peerDirectory)
            .call().use { peer ->
                peerDirectory.resolve("peer.txt").writeText("from peer\n")
                peer.add().addFilepattern("peer.txt").call()
                peer.commit()
                    .setMessage("peer")
                    .setAuthor("Peer", "peer@example.test")
                    .setCommitter("Peer", "peer@example.test")
                    .call()
                peer.push().setRemote("origin").call()
            }

        assertTrue(fixture.service.fetch(id).successful)
        val beforePull = fixture.service.open(id).headObjectId
        assertTrue(fixture.service.pull(id).successful)
        assertTrue(fixture.repository(id).resolve("peer.txt").isFile)
        assertNotEquals(beforePull, fixture.service.open(id).headObjectId)

        fixture.repository(id).resolve("local.txt").writeText("from local\n")
        fixture.service.stage(id, listOf(GitRelativePath("local.txt")))
        val localCommit = fixture.service.commit(
            id,
            "local",
            GitIdentity("Local", "local@example.test"),
        )
        val pushed = fixture.service.push(id)
        assertTrue(pushed.successful)
        FileRepositoryBuilder().setGitDir(remoteDirectory).setBare().build().use { remoteRepository ->
            assertEquals(localCommit.value.objectId, remoteRepository.resolve("refs/heads/main").name)
        }
    }

    private fun fixture(): Fixture {
        val boundary = temporary.newFolder("private-${System.nanoTime()}")
        val store = AppPrivateGitRepositoryStore(boundary.resolve("repositories"), boundary)
        return Fixture(
            boundary,
            JGitRepositoryService(
                store = store,
                credentialBroker = GitCredentialBroker { null },
                transportReadinessProbe = GitTransportReadinessProbe {
                    GitTransportReadiness(https = false, ssh = false)
                },
            ),
        )
    }

    private data class Fixture(
        val boundary: File,
        val service: JGitRepositoryService,
    ) {
        fun repository(id: GitRepositoryId): File = boundary.resolve("repositories").resolve(id.value)
    }
}
