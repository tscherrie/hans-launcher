package ai.hans.standard.git

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.TransportCommand
import org.eclipse.jgit.api.errors.NoHeadException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.transport.FetchResult
import org.eclipse.jgit.transport.PushResult
import org.eclipse.jgit.treewalk.EmptyTreeIterator

/** Root-free typed Git service. No method accepts or starts a shell command. */
internal class JGitRepositoryService(
    private val store: AppPrivateGitRepositoryStore,
    private val credentialBroker: GitCredentialBroker,
    private val transportReadinessProbe: GitTransportReadinessProbe,
) {
    fun initialize(
        repositoryId: GitRepositoryId,
        initialBranch: String = "main",
        cancellation: GitCancellation = GitCancellation.NONE,
    ): GitMutationResult<GitRepositorySnapshot> = store.withRepositoryLock(repositoryId) {
        requireBranch(initialBranch)
        cancellation.throwIfCancelled()
        val directory = store.prepareNewDirectory(repositoryId)
        try {
            Git.init().setDirectory(directory).setInitialBranch(initialBranch).call().use {
                cancellation.throwIfCancelled()
            }
            store.verify(repositoryId)
            val snapshot = open(repositoryId)
            GitMutationResult(snapshot, postcondition(repositoryId, null))
        } catch (error: Throwable) {
            store.discardIncomplete(repositoryId)
            throw error
        }
    }

    fun open(repositoryId: GitRepositoryId): GitRepositorySnapshot =
        store.withRepositoryLock(repositoryId) {
            openGit(repositoryId).use { git -> repositorySnapshot(repositoryId, git) }
        }

    fun status(
        repositoryId: GitRepositoryId,
        maxPaths: Int = GitLimits.MAX_STATUS_PATHS,
        cancellation: GitCancellation = GitCancellation.NONE,
    ): GitStatusSnapshot = store.withRepositoryLock(repositoryId) {
        require(maxPaths in 1..GitLimits.MAX_STATUS_PATHS) { "git_status_limit_invalid" }
        cancellation.throwIfCancelled()
        openGit(repositoryId).use { git -> status(git, maxPaths, cancellation) }
    }

    fun diff(
        repositoryId: GitRepositoryId,
        scope: GitDiffScope,
        maxBytes: Int = GitLimits.MAX_DIFF_BYTES,
        cancellation: GitCancellation = GitCancellation.NONE,
    ): GitDiffSnapshot = store.withRepositoryLock(repositoryId) {
        require(maxBytes in 1..GitLimits.MAX_DIFF_BYTES) { "git_diff_limit_invalid" }
        cancellation.throwIfCancelled()
        openGit(repositoryId).use { git ->
            val output = BoundedByteArrayOutputStream(maxBytes)
            val command = git.diff()
                .setCached(scope == GitDiffScope.STAGED)
                .setOutputStream(output)
            if (scope == GitDiffScope.STAGED && git.repository.resolve(Constants.HEAD) == null) {
                command.setOldTree(EmptyTreeIterator())
            }
            command.call()
            cancellation.throwIfCancelled()
            val bytes = output.toByteArray()
            GitDiffSnapshot(
                repositoryId = repositoryId,
                scope = scope,
                patch = decodeUtf8(bytes, tolerateTruncatedTail = output.truncated),
                byteCount = bytes.size,
                truncated = output.truncated,
            )
        }
    }

    fun log(
        repositoryId: GitRepositoryId,
        maxCommits: Int = 50,
        cancellation: GitCancellation = GitCancellation.NONE,
    ): GitLogSnapshot = store.withRepositoryLock(repositoryId) {
        require(maxCommits in 1..GitLimits.MAX_LOG_COMMITS) { "git_log_limit_invalid" }
        cancellation.throwIfCancelled()
        openGit(repositoryId).use { git ->
            val commits = try {
                git.log().setMaxCount(maxCommits + 1).call().map { commit ->
                    cancellation.throwIfCancelled()
                    commit.toSnapshot()
                }
            } catch (_: NoHeadException) {
                emptyList()
            }
            GitLogSnapshot(repositoryId, commits.take(maxCommits), commits.size > maxCommits)
        }
    }

    fun branches(
        repositoryId: GitRepositoryId,
        maxBranches: Int = GitLimits.MAX_BRANCHES,
        cancellation: GitCancellation = GitCancellation.NONE,
    ): GitBranchListSnapshot = store.withRepositoryLock(repositoryId) {
        require(maxBranches in 1..GitLimits.MAX_BRANCHES) { "git_branch_limit_invalid" }
        cancellation.throwIfCancelled()
        openGit(repositoryId).use { git ->
            val current = runCatching { git.repository.branch }.getOrNull()
            val branches = git.branchList().call().map { ref ->
                cancellation.throwIfCancelled()
                val name = Repository.shortenRefName(ref.name)
                GitBranchSnapshot(name, ref.objectId?.name, name == current)
            }.sortedBy(GitBranchSnapshot::name)
            GitBranchListSnapshot(
                repositoryId = repositoryId,
                branches = branches.take(maxBranches),
                totalBranches = branches.size,
                truncated = branches.size > maxBranches,
            )
        }
    }

    fun createBranch(
        repositoryId: GitRepositoryId,
        branchName: String,
        startPoint: String? = null,
        cancellation: GitCancellation = GitCancellation.NONE,
    ): GitMutationResult<GitBranchSnapshot> = store.withRepositoryLock(repositoryId) {
        requireBranch(branchName)
        startPoint?.let(::requireStartPoint)
        cancellation.throwIfCancelled()
        openGit(repositoryId).use { git ->
            val headBefore = head(git.repository)
            val command = git.branchCreate().setName(branchName)
            startPoint?.let(command::setStartPoint)
            val created = command.call()
            cancellation.throwIfCancelled()
            store.verify(repositoryId)
            val verified = requireNotNull(git.repository.findRef("refs/heads/$branchName")) {
                "git_branch_postcondition_failed"
            }
            check(verified.objectId?.name == created.objectId?.name) {
                "git_branch_postcondition_failed"
            }
            GitMutationResult(
                GitBranchSnapshot(branchName, verified.objectId?.name, current = false),
                postcondition(repositoryId, headBefore),
            )
        }
    }

    fun checkout(
        repositoryId: GitRepositoryId,
        branchName: String,
        cancellation: GitCancellation = GitCancellation.NONE,
    ): GitMutationResult<GitRepositorySnapshot> = store.withRepositoryLock(repositoryId) {
        requireBranch(branchName)
        cancellation.throwIfCancelled()
        openGit(repositoryId).use { git ->
            val headBefore = head(git.repository)
            git.checkout().setName(branchName).call()
            cancellation.throwIfCancelled()
            store.verify(repositoryId)
            val snapshot = repositorySnapshot(repositoryId, git)
            check(snapshot.branch == branchName) { "git_checkout_postcondition_failed" }
            GitMutationResult(snapshot, postcondition(repositoryId, headBefore))
        }
    }

    fun stage(
        repositoryId: GitRepositoryId,
        paths: List<GitRelativePath>,
        cancellation: GitCancellation = GitCancellation.NONE,
    ): GitMutationResult<List<GitRelativePath>> = store.withRepositoryLock(repositoryId) {
        require(paths.isNotEmpty() && paths.size <= GitLimits.MAX_PATHS_PER_REQUEST) {
            "git_stage_path_count_invalid"
        }
        require(paths.distinct().size == paths.size) { "git_stage_path_duplicate" }
        openGit(repositoryId).use { git ->
            val headBefore = head(git.repository)
            paths.sortedBy(GitRelativePath::value).forEach { path ->
                cancellation.throwIfCancelled()
                git.add().addFilepattern(path.value).call()
                git.add().setUpdate(true).addFilepattern(path.value).call()
            }
            cancellation.throwIfCancelled()
            store.verify(repositoryId)
            GitMutationResult(paths.sortedBy(GitRelativePath::value), postcondition(repositoryId, headBefore))
        }
    }

    fun commit(
        repositoryId: GitRepositoryId,
        message: String,
        identity: GitIdentity,
        cancellation: GitCancellation = GitCancellation.NONE,
    ): GitMutationResult<GitCommitSnapshot> = store.withRepositoryLock(repositoryId) {
        require(message.isNotBlank() && message.length <= GitLimits.MAX_COMMIT_MESSAGE_CHARS) {
            "git_commit_message_invalid"
        }
        require(message.none { it == '\u0000' }) { "git_commit_message_invalid" }
        cancellation.throwIfCancelled()
        openGit(repositoryId).use { git ->
            val headBefore = head(git.repository)
            val commit = git.commit()
                .setMessage(message)
                .setAuthor(identity.name, identity.email)
                .setCommitter(identity.name, identity.email)
                .call()
            cancellation.throwIfCancelled()
            store.verify(repositoryId)
            val outcome = postcondition(repositoryId, headBefore)
            check(outcome.headAfter == commit.id.name) { "git_commit_postcondition_failed" }
            GitMutationResult(commit.toSnapshot(), outcome)
        }
    }

    fun clone(
        repositoryId: GitRepositoryId,
        remote: GitRemoteUri,
        cancellation: GitCancellation = GitCancellation.NONE,
    ): GitMutationResult<GitRepositorySnapshot> = store.withRepositoryLock(repositoryId) {
        requireTransportReady(remote.scheme)
        cancellation.throwIfCancelled()
        val directory = store.prepareNewDirectory(repositoryId)
        try {
            val command = Git.cloneRepository()
                .setURI(remote.transportUri)
                .setDirectory(directory)
                .setProgressMonitor(
                    CancellationProgressMonitor(cancellation) {
                        store.verifyCandidate(repositoryId)
                    },
                )
            configureTransport(command, repositoryId, remote, GitNetworkOperation.CLONE).use {
                command.call().use { cancellation.throwIfCancelled() }
            }
            store.verify(repositoryId)
            val snapshot = open(repositoryId)
            GitMutationResult(snapshot, postcondition(repositoryId, null))
        } catch (error: Throwable) {
            store.discardIncomplete(repositoryId)
            throw error
        }
    }

    fun fetch(
        repositoryId: GitRepositoryId,
        remoteName: GitRemoteName = GitRemoteName("origin"),
        cancellation: GitCancellation = GitCancellation.NONE,
    ): GitNetworkResult = networkOperation(
        repositoryId,
        remoteName,
        GitNetworkOperation.FETCH,
        cancellation,
    ) { git, commandSession, monitor ->
        val command = git.fetch().setRemote(remoteName.value).setProgressMonitor(monitor)
        commandSession.configure(command)
        command.call().toUpdates()
    }

    fun pull(
        repositoryId: GitRepositoryId,
        remoteName: GitRemoteName = GitRemoteName("origin"),
        cancellation: GitCancellation = GitCancellation.NONE,
    ): GitNetworkResult = networkOperation(
        repositoryId,
        remoteName,
        GitNetworkOperation.PULL,
        cancellation,
    ) { git, commandSession, monitor ->
        val result = git.pull()
            .setRemote(remoteName.value)
            .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
            .setProgressMonitor(monitor)
            .also(commandSession::configure)
            .call()
        require(result.isSuccessful) { "git_pull_not_fast_forward" }
        result.fetchResult?.toUpdates().orEmpty()
    }

    fun push(
        repositoryId: GitRepositoryId,
        remoteName: GitRemoteName = GitRemoteName("origin"),
        cancellation: GitCancellation = GitCancellation.NONE,
    ): GitNetworkResult = networkOperation(
        repositoryId,
        remoteName,
        GitNetworkOperation.PUSH,
        cancellation,
    ) { git, commandSession, monitor ->
        val command = git.push().setRemote(remoteName.value).setProgressMonitor(monitor)
        commandSession.configure(command)
        command.call().flatMap { result -> result.toUpdates() }
    }

    private fun networkOperation(
        repositoryId: GitRepositoryId,
        remoteName: GitRemoteName,
        operation: GitNetworkOperation,
        cancellation: GitCancellation,
        action: (Git, GitCredentialSession, ProgressMonitor) -> List<GitRemoteUpdate>,
    ): GitNetworkResult = store.withRepositoryLock(repositoryId) {
        cancellation.throwIfCancelled()
        openGit(repositoryId).use { git ->
            val headBefore = head(git.repository)
            val remote = remoteFor(git.repository, remoteName)
            requireTransportReady(remote.scheme)
            val request = GitCredentialRequest(operation, remote.scheme, remote.host, repositoryId)
            val session = credentialSession(request)
            session.use {
                val updates = action(
                    git,
                    session,
                    CancellationProgressMonitor(cancellation) { store.verify(repositoryId) },
                )
                require(updates.size <= GitLimits.MAX_REMOTE_UPDATES) { "git_remote_update_limit_exceeded" }
                cancellation.throwIfCancelled()
                store.verify(repositoryId)
                GitNetworkResult(
                    operation = operation,
                    remoteName = remoteName,
                    updates = updates.sortedBy(GitRemoteUpdate::name),
                    successful = updates.all(GitRemoteUpdate::successful),
                    postcondition = postcondition(repositoryId, headBefore),
                )
            }
        }
    }

    private fun configureTransport(
        command: TransportCommand<*, *>,
        repositoryId: GitRepositoryId,
        remote: GitRemoteUri,
        operation: GitNetworkOperation,
    ): GitCredentialSession {
        val session = credentialSession(
            GitCredentialRequest(operation, remote.scheme, remote.host, repositoryId),
        )
        try {
            session.configure(command)
            return session
        } catch (error: Throwable) {
            session.close()
            throw error
        }
    }

    private fun remoteFor(repository: Repository, name: GitRemoteName): GitRemoteUri {
        val value = repository.config.getString("remote", name.value, "url")
            ?: throw IllegalArgumentException("git_remote_not_configured")
        if (value.startsWith("file:")) {
            return store.appPrivateRemote(value)
        }
        return GitRemoteUri.parsePublic(value)
    }

    private fun requireTransportReady(scheme: GitRemoteScheme) {
        require(transportReadinessProbe.probe().supports(scheme)) { "git_transport_unavailable" }
    }

    private fun credentialSession(request: GitCredentialRequest): GitCredentialSession =
        if (request.scheme == GitRemoteScheme.APP_PRIVATE_FILE) {
            GitCredentialSession.PUBLIC
        } else {
            requireNotNull(credentialBroker.open(request)) { "git_credentials_unavailable" }
        }

    private fun openGit(repositoryId: GitRepositoryId): Git = Git.open(store.existingDirectory(repositoryId))

    private fun repositorySnapshot(repositoryId: GitRepositoryId, git: Git): GitRepositorySnapshot =
        GitRepositorySnapshot(
            repositoryId = repositoryId,
            headObjectId = head(git.repository),
            branch = runCatching { git.repository.branch }.getOrNull(),
            status = status(git, GitLimits.MAX_STATUS_PATHS, GitCancellation.NONE),
        )

    private fun status(
        git: Git,
        maxPaths: Int,
        cancellation: GitCancellation,
    ): GitStatusSnapshot {
        cancellation.throwIfCancelled()
        val status = git.status().call()
        val states = linkedMapOf<String, MutableSet<GitPathState>>()
        fun add(paths: Set<String>, state: GitPathState) {
            paths.forEach { path ->
                cancellation.throwIfCancelled()
                states.getOrPut(path) { linkedSetOf() } += state
            }
        }
        add(status.added, GitPathState.ADDED)
        add(status.changed, GitPathState.CHANGED)
        add(status.conflicting, GitPathState.CONFLICTING)
        add(status.missing, GitPathState.MISSING)
        add(status.modified, GitPathState.MODIFIED)
        add(status.removed, GitPathState.REMOVED)
        add(status.uncommittedChanges, GitPathState.UNCOMMITTED_CHANGE)
        add(status.untracked, GitPathState.UNTRACKED)
        add(status.untrackedFolders, GitPathState.UNTRACKED_FOLDER)
        val sorted = states.entries.sortedBy(Map.Entry<String, *>::key)
        return GitStatusSnapshot(
            clean = status.isClean,
            totalPaths = sorted.size,
            paths = sorted.take(maxPaths).map { (path, pathStates) ->
                GitStatusPath(GitRelativePath(path), pathStates.toSet())
            },
            truncated = sorted.size > maxPaths,
        )
    }

    private fun postcondition(repositoryId: GitRepositoryId, headBefore: String?): GitPostcondition {
        val snapshot = open(repositoryId)
        return GitPostcondition(repositoryId, headBefore, snapshot.headObjectId, snapshot.status)
    }

    private fun head(repository: Repository): String? = repository.resolve(Constants.HEAD)?.name

    private fun RevCommit.toSnapshot(): GitCommitSnapshot = GitCommitSnapshot(
        objectId = id.name,
        parentObjectIds = parents.take(MAX_LOG_PARENTS).map { it.id.name },
        authorName = authorIdent.name.safeLogText(GitLimits.MAX_IDENTITY_CHARS),
        authorEmail = authorIdent.emailAddress.safeLogText(GitLimits.MAX_IDENTITY_CHARS),
        committedEpochSeconds = commitTime.toLong(),
        shortMessage = shortMessage.safeLogText(MAX_LOG_MESSAGE_CHARS),
        parentsTruncated = parentCount > MAX_LOG_PARENTS,
    )

    private fun FetchResult.toUpdates(): List<GitRemoteUpdate> = trackingRefUpdates.map { update ->
        GitRemoteUpdate(
            name = update.localName.take(512),
            status = update.result.name.lowercase(),
            oldObjectId = update.oldObjectId?.name,
            newObjectId = update.newObjectId?.name,
            successful = update.result.name in SUCCESSFUL_FETCH_RESULTS,
        )
    }

    private fun PushResult.toUpdates(): List<GitRemoteUpdate> = remoteUpdates.map { update ->
        GitRemoteUpdate(
            name = update.remoteName.take(512),
            status = update.status.name.lowercase(),
            oldObjectId = update.expectedOldObjectId?.name,
            newObjectId = update.newObjectId?.name,
            successful = update.status.name in SUCCESSFUL_PUSH_STATUSES,
        )
    }

    private companion object {
        const val MAX_LOG_PARENTS = 32
        const val MAX_LOG_MESSAGE_CHARS = 1_024
        val SUCCESSFUL_FETCH_RESULTS = setOf("NEW", "NO_CHANGE", "FAST_FORWARD", "FORCED", "RENAMED")
        val SUCCESSFUL_PUSH_STATUSES = setOf("OK", "UP_TO_DATE")
    }
}

private class CancellationProgressMonitor(
    private val cancellation: GitCancellation,
    private val budgetCheck: () -> Unit = {},
) : ProgressMonitor {
    private var updatesSinceBudgetCheck = 0

    override fun start(totalTasks: Int) = checkNow()
    override fun beginTask(title: String?, totalWork: Int) = checkNow()
    override fun update(completed: Int) {
        cancellation.throwIfCancelled()
        updatesSinceBudgetCheck += 1
        if (updatesSinceBudgetCheck >= BUDGET_CHECK_INTERVAL) checkNow()
    }
    override fun endTask() = checkNow()
    override fun isCancelled(): Boolean = cancellation.isCancelled()
    override fun showDuration(enabled: Boolean) = Unit

    private fun checkNow() {
        cancellation.throwIfCancelled()
        budgetCheck()
        updatesSinceBudgetCheck = 0
    }

    private companion object {
        const val BUDGET_CHECK_INTERVAL = 256
    }
}

private class BoundedByteArrayOutputStream(
    private val maxBytes: Int,
) : OutputStream() {
    private val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
    var truncated: Boolean = false
        private set

    override fun write(value: Int) {
        if (output.size() < maxBytes) {
            output.write(value)
        } else {
            truncated = true
        }
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "git_diff_write_invalid" }
        val remaining = maxBytes - output.size()
        if (length <= remaining) {
            output.write(bytes, offset, length)
        } else {
            if (remaining > 0) output.write(bytes, offset, remaining)
            truncated = true
        }
    }

    fun toByteArray(): ByteArray = output.toByteArray()
}

private fun GitCancellation.throwIfCancelled() {
    if (isCancelled()) throw GitOperationCancelledException()
}

private fun requireBranch(value: String) {
    require(
        value.length in 1..255 &&
            !value.startsWith("refs/") &&
            Repository.isValidRefName("refs/heads/$value"),
    ) {
        "git_branch_invalid"
    }
}

private fun requireStartPoint(value: String) {
    require(
        value.length in 1..512 &&
            value.none(Char::isISOControl) &&
            START_POINT.matches(value),
    ) { "git_start_point_invalid" }
}

private fun String.safeLogText(maxChars: Int): String = buildString(minOf(length, maxChars)) {
    for (character in this@safeLogText) {
        if (length >= maxChars) break
        append(if (character.isISOControl()) ' ' else character)
    }
}

private fun decodeUtf8(bytes: ByteArray, tolerateTruncatedTail: Boolean): String =
    StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(if (tolerateTruncatedTail) CodingErrorAction.REPLACE else CodingErrorAction.REPORT)
    .onUnmappableCharacter(if (tolerateTruncatedTail) CodingErrorAction.REPLACE else CodingErrorAction.REPORT)
    .decode(java.nio.ByteBuffer.wrap(bytes))
    .toString()

private val START_POINT = Regex("[A-Za-z0-9][A-Za-z0-9._/@{}^~:+-]{0,511}")
