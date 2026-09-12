package ai.hans.standard.git

import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import org.eclipse.jgit.api.TransportCommand

internal object GitLimits {
    const val MAX_REPOSITORY_ID_CHARS = 64
    const val MAX_REMOTE_URI_CHARS = 2_048
    const val MAX_REMOTE_NAME_CHARS = 64
    const val MAX_RELATIVE_PATH_BYTES = 4_096
    const val MAX_PATHS_PER_REQUEST = 128
    const val MAX_STATUS_PATHS = 1_000
    const val MAX_DIFF_BYTES = 256 * 1024
    const val MAX_LOG_COMMITS = 200
    const val MAX_BRANCHES = 200
    const val MAX_COMMIT_MESSAGE_CHARS = 16 * 1024
    const val MAX_IDENTITY_CHARS = 320
    const val MAX_REMOTE_UPDATES = 256
    const val MAX_RESULT_BYTES = 512 * 1024
    const val MAX_REPOSITORY_FILES = 100_000
    const val MAX_REPOSITORY_BYTES = 2L * 1024L * 1024L * 1024L
}

@JvmInline
internal value class GitRepositoryId(val value: String) {
    init {
        require(value.length <= GitLimits.MAX_REPOSITORY_ID_CHARS && REPOSITORY_ID.matches(value)) {
            "git_repository_id_invalid"
        }
    }

    override fun toString(): String = value

    private companion object {
        val REPOSITORY_ID = Regex("[a-z][a-z0-9._-]{0,63}")
    }
}

internal enum class GitRemoteScheme(val wireName: String) {
    HTTPS("https"),
    SSH("ssh"),
    APP_PRIVATE_FILE("app_private_file"),
}

/** A credential-free remote locator. Secrets and URL user-info are rejected. */
internal class GitRemoteUri private constructor(
    val scheme: GitRemoteScheme,
    val transportUri: String,
    val host: String?,
) {
    companion object {
        fun parsePublic(value: String): GitRemoteUri {
            require(value.length in 1..GitLimits.MAX_REMOTE_URI_CHARS) { "git_remote_uri_invalid" }
            require(value.none(Char::isISOControl)) { "git_remote_uri_invalid" }
            if (SCP_LIKE_REMOTE.matches(value)) {
                throw IllegalArgumentException("git_remote_scheme_unsupported")
            }
            val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("git_remote_uri_invalid") }
            val scheme = when (uri.scheme?.lowercase()) {
                "https" -> GitRemoteScheme.HTTPS
                "ssh" -> GitRemoteScheme.SSH
                else -> throw IllegalArgumentException("git_remote_scheme_unsupported")
            }
            require(uri.fragment == null && uri.rawQuery == null) { "git_remote_credentials_forbidden" }
            when (scheme) {
                GitRemoteScheme.HTTPS -> require(uri.rawUserInfo == null) {
                    "git_remote_credentials_forbidden"
                }
                GitRemoteScheme.SSH -> require(
                    uri.rawUserInfo == null || SSH_USERNAME.matches(uri.rawUserInfo),
                ) { "git_remote_credentials_forbidden" }
                GitRemoteScheme.APP_PRIVATE_FILE -> error("git_remote_scheme_unsupported")
            }
            require(!uri.host.isNullOrBlank() && uri.host.length <= 253) { "git_remote_host_invalid" }
            require(!uri.path.isNullOrBlank() && uri.path.startsWith('/')) { "git_remote_path_invalid" }
            return GitRemoteUri(scheme, uri.toASCIIString(), uri.host.lowercase())
        }

        private val SSH_USERNAME = Regex("[A-Za-z0-9._-]{1,64}")
        private val SCP_LIKE_REMOTE = Regex("[A-Za-z0-9._-]+@[A-Za-z0-9.-]+:.+")

        /** Test/internal sync path which is still proven to remain below an app-private boundary. */
        fun appPrivateFile(repository: File, privateBoundary: File): GitRemoteUri {
            val boundary = privateBoundary.canonicalFile
            val remote = repository.canonicalFile
            require(boundary.isDirectory && remote.isDirectory) { "git_private_remote_unavailable" }
            require(remote.path.startsWith(boundary.path + File.separator)) {
                "git_private_remote_outside_boundary"
            }
            return GitRemoteUri(GitRemoteScheme.APP_PRIVATE_FILE, remote.toURI().toASCIIString(), null)
        }
    }
}

@JvmInline
internal value class GitRemoteName(val value: String) {
    init {
        require(value.length <= GitLimits.MAX_REMOTE_NAME_CHARS && REMOTE_NAME.matches(value)) {
            "git_remote_name_invalid"
        }
    }

    override fun toString(): String = value

    private companion object {
        val REMOTE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    }
}

@JvmInline
internal value class GitRelativePath(val value: String) {
    init {
        require(value.isNotBlank()) { "git_path_invalid" }
        require(value.toByteArray(StandardCharsets.UTF_8).size <= GitLimits.MAX_RELATIVE_PATH_BYTES) {
            "git_path_invalid"
        }
        require(!value.startsWith('/') && !value.endsWith('/') && '\\' !in value) {
            "git_path_invalid"
        }
        val segments = value.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) { "git_path_invalid" }
        require(segments.all { segment ->
            segment.toByteArray(StandardCharsets.UTF_8).size <= 255 &&
                segment.none { it == '\u0000' || it.isISOControl() }
        }) { "git_path_invalid" }
        require(segments.first() != ".git") { "git_metadata_path_forbidden" }
    }

    override fun toString(): String = value
}

internal data class GitIdentity(val name: String, val email: String) {
    init {
        require(name.isNotBlank() && name.length <= GitLimits.MAX_IDENTITY_CHARS) {
            "git_identity_name_invalid"
        }
        require(email.length in 3..GitLimits.MAX_IDENTITY_CHARS && EMAIL.matches(email)) {
            "git_identity_email_invalid"
        }
        require((name + email).none(Char::isISOControl)) { "git_identity_invalid" }
    }

    private companion object {
        val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")
    }
}

internal fun interface GitCancellation {
    fun isCancelled(): Boolean

    companion object {
        val NONE = GitCancellation { false }
    }
}

internal class GitOperationCancelledException : RuntimeException("git_operation_cancelled")

internal enum class GitNetworkOperation(val wireName: String) {
    CLONE("clone"),
    FETCH("fetch"),
    PULL("pull"),
    PUSH("push"),
}

internal data class GitCredentialRequest(
    val operation: GitNetworkOperation,
    val scheme: GitRemoteScheme,
    val host: String?,
    val repositoryId: GitRepositoryId,
)

/**
 * Opaque credential lease. Implementations may use Android Keystore, an SSH agent or an in-memory
 * token, but expose only a configuration callback to this package and must erase temporary secrets
 * from memory in [close]. No credential value crosses a dynamic-tool result.
 */
internal interface GitCredentialSession : AutoCloseable {
    fun configure(command: TransportCommand<*, *>)
    override fun close()

    companion object {
        val PUBLIC = object : GitCredentialSession {
            override fun configure(command: TransportCommand<*, *>) = Unit
            override fun close() = Unit
        }
    }
}

internal fun interface GitCredentialBroker {
    /** Returns null when this remote is not authorized or not configured. */
    fun open(request: GitCredentialRequest): GitCredentialSession?
}

internal data class GitTransportReadiness(
    val https: Boolean,
    val ssh: Boolean,
) {
    fun supports(scheme: GitRemoteScheme): Boolean = when (scheme) {
        GitRemoteScheme.HTTPS -> https
        GitRemoteScheme.SSH -> ssh
        GitRemoteScheme.APP_PRIVATE_FILE -> true
    }
}

internal fun interface GitTransportReadinessProbe {
    fun probe(): GitTransportReadiness
}

internal data class GitDynamicReadiness(
    val localRepository: Boolean,
    val commitIdentity: Boolean,
    val transport: GitTransportReadiness,
)

internal fun interface GitDynamicReadinessProbe {
    fun probe(): GitDynamicReadiness
}

internal enum class GitDiffScope(val wireName: String) {
    WORKTREE("worktree"),
    STAGED("staged"),
}

internal enum class GitPathState(val wireName: String) {
    ADDED("added"),
    CHANGED("changed"),
    CONFLICTING("conflicting"),
    MISSING("missing"),
    MODIFIED("modified"),
    REMOVED("removed"),
    UNCOMMITTED_CHANGE("uncommitted_change"),
    UNTRACKED("untracked"),
    UNTRACKED_FOLDER("untracked_folder"),
}

internal data class GitStatusPath(
    val path: GitRelativePath,
    val states: Set<GitPathState>,
) {
    init {
        require(states.isNotEmpty()) { "git_status_state_empty" }
    }
}

internal data class GitStatusSnapshot(
    val clean: Boolean,
    val totalPaths: Int,
    val paths: List<GitStatusPath>,
    val truncated: Boolean,
)

internal data class GitRepositorySnapshot(
    val repositoryId: GitRepositoryId,
    val headObjectId: String?,
    val branch: String?,
    val status: GitStatusSnapshot,
)

internal data class GitBranchSnapshot(
    val name: String,
    val objectId: String?,
    val current: Boolean,
)

internal data class GitBranchListSnapshot(
    val repositoryId: GitRepositoryId,
    val branches: List<GitBranchSnapshot>,
    val totalBranches: Int,
    val truncated: Boolean,
)

internal data class GitDiffSnapshot(
    val repositoryId: GitRepositoryId,
    val scope: GitDiffScope,
    val patch: String,
    val byteCount: Int,
    val truncated: Boolean,
)

internal data class GitCommitSnapshot(
    val objectId: String,
    val parentObjectIds: List<String>,
    val authorName: String,
    val authorEmail: String,
    val committedEpochSeconds: Long,
    val shortMessage: String,
    val parentsTruncated: Boolean,
)

internal data class GitLogSnapshot(
    val repositoryId: GitRepositoryId,
    val commits: List<GitCommitSnapshot>,
    val hasMore: Boolean,
)

internal data class GitPostcondition(
    val repositoryId: GitRepositoryId,
    val headBefore: String?,
    val headAfter: String?,
    val statusAfter: GitStatusSnapshot,
    val verified: Boolean = true,
)

internal data class GitMutationResult<T>(
    val value: T,
    val postcondition: GitPostcondition,
)

internal data class GitRemoteUpdate(
    val name: String,
    val status: String,
    val oldObjectId: String?,
    val newObjectId: String?,
    val successful: Boolean,
)

internal data class GitNetworkResult(
    val operation: GitNetworkOperation,
    val remoteName: GitRemoteName,
    val updates: List<GitRemoteUpdate>,
    val successful: Boolean,
    val postcondition: GitPostcondition,
)
