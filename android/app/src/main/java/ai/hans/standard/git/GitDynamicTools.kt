package ai.hans.standard.git

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolExecutionGate
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolFunctionSpec
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import ai.hans.standard.codex.JsonContract
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executor
import org.eclipse.jgit.api.errors.GitAPIException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Capability-probed dynamic-tool projection of [JGitRepositoryService]. Tool arguments contain
 * only opaque repository IDs, safe relative paths and credential-free remote URLs. Credentials
 * and author identity are injected by trusted Android-side providers and never cross Codex JSON.
 */
internal object GitDynamicToolCatalog {
    const val NAMESPACE = "hans_git"

    fun namespace(readiness: GitDynamicReadiness): DynamicToolNamespaceSpec? {
        if (!readiness.localRepository) return null
        val tools = buildList {
            add(INIT)
            add(OPEN)
            add(STATUS)
            add(DIFF)
            add(LOG)
            add(BRANCH)
            add(CHECKOUT)
            add(STAGE)
            if (readiness.commitIdentity) add(COMMIT)
            if (readiness.transport.https || readiness.transport.ssh) {
                add(CLONE)
                add(FETCH)
                add(PULL)
                add(PUSH)
            }
        }
        return DynamicToolNamespaceSpec(
            name = NAMESPACE,
            description =
                "Root-free Git for Hans app-private repositories. Use opaque repository IDs and " +
                    "relative paths only. Credentials are supplied privately by Android and must " +
                    "never be placed in arguments.",
            tools = tools,
        )
    }

    fun availableToolNames(readiness: GitDynamicReadiness): Set<String> =
        namespace(readiness)?.tools?.mapTo(linkedSetOf(), DynamicToolFunctionSpec::name).orEmpty()

    private val repositoryId = string(GitLimits.MAX_REPOSITORY_ID_CHARS)
    private val remoteName = string(GitLimits.MAX_REMOTE_NAME_CHARS)
    private val relativePath = string(GitLimits.MAX_RELATIVE_PATH_BYTES)

    private val INIT = function(
        "init",
        "Initialize an empty app-private Git repository and verify its resulting state.",
        JSONObject()
            .put("repositoryId", repositoryId)
            .put("initialBranch", string(255)),
        listOf("repositoryId"),
    )
    private val OPEN = function(
        "open",
        "Open and integrity-check an app-private repository by opaque ID.",
        JSONObject().put("repositoryId", repositoryId),
        listOf("repositoryId"),
    )
    private val STATUS = function(
        "status",
        "Return a bounded, deterministic working-tree status.",
        JSONObject()
            .put("repositoryId", repositoryId)
            .put("maxPaths", integer(1, MAX_DYNAMIC_STATUS_PATHS)),
        listOf("repositoryId"),
    )
    private val DIFF = function(
        "diff",
        "Return a bounded worktree or staged patch. The result reports if it was truncated.",
        JSONObject()
            .put("repositoryId", repositoryId)
            .put("scope", enum("worktree", "staged"))
            .put("maxBytes", integer(1, GitLimits.MAX_DIFF_BYTES)),
        listOf("repositoryId", "scope"),
    )
    private val LOG = function(
        "log",
        "Return a bounded newest-first commit history without exposing filesystem paths.",
        JSONObject()
            .put("repositoryId", repositoryId)
            .put("maxCommits", integer(1, MAX_DYNAMIC_LOG_COMMITS)),
        listOf("repositoryId"),
    )
    private val BRANCH = function(
        "branch",
        "List local branches or create one from an optional bounded start point.",
        JSONObject()
            .put("repositoryId", repositoryId)
            .put("action", enum("list", "create"))
            .put("branchName", string(255))
            .put("startPoint", string(512))
            .put("maxBranches", integer(1, MAX_DYNAMIC_BRANCHES)),
        listOf("repositoryId", "action"),
    )
    private val CHECKOUT = function(
        "checkout",
        "Switch the app-private working tree to an existing local branch and verify it.",
        JSONObject()
            .put("repositoryId", repositoryId)
            .put("branchName", string(255)),
        listOf("repositoryId", "branchName"),
    )
    private val STAGE = function(
        "stage",
        "Stage bounded relative paths, including deletions, and verify the postcondition.",
        JSONObject()
            .put("repositoryId", repositoryId)
            .put(
                "paths",
                JSONObject()
                    .put("type", "array")
                    .put("items", relativePath)
                    .put("minItems", 1)
                    .put("maxItems", MAX_DYNAMIC_PATHS),
            ),
        listOf("repositoryId", "paths"),
    )
    private val COMMIT = function(
        "commit",
        "Commit staged changes using the Android-configured identity and verify the new HEAD.",
        JSONObject()
            .put("repositoryId", repositoryId)
            .put("message", text(GitLimits.MAX_COMMIT_MESSAGE_CHARS)),
        listOf("repositoryId", "message"),
    )
    private val CLONE = function(
        "clone",
        "Clone an authorized HTTPS or SSH remote into a new app-private repository. " +
            "Do not put credentials in the URL.",
        JSONObject()
            .put("repositoryId", repositoryId)
            .put("remoteUri", string(GitLimits.MAX_REMOTE_URI_CHARS)),
        listOf("repositoryId", "remoteUri"),
    )
    private val FETCH = remoteFunction("fetch", "Fetch the configured remote and verify local state.")
    private val PULL = remoteFunction(
        "pull",
        "Fast-forward-only pull from the configured remote and verify local state.",
    )
    private val PUSH = remoteFunction("push", "Push to the configured remote and report each result.")

    private fun remoteFunction(name: String, description: String) = function(
        name,
        description,
        JSONObject()
            .put("repositoryId", repositoryId)
            .put("remoteName", remoteName),
        listOf("repositoryId"),
    )

    private fun function(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String>,
    ) = DynamicToolFunctionSpec(
        name = name,
        description = description,
        inputSchemaJson = JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray(required))
            .put("additionalProperties", false)
            .toString(),
    )

    private fun string(maxLength: Int) = JSONObject()
        .put("type", "string")
        .put("minLength", 1)
        .put("maxLength", maxLength)

    private fun text(maxLength: Int) = JSONObject()
        .put("type", "string")
        .put("minLength", 1)
        .put("maxLength", maxLength)

    private fun integer(minimum: Int, maximum: Int) = JSONObject()
        .put("type", "integer")
        .put("minimum", minimum)
        .put("maximum", maximum)

    private fun enum(vararg values: String) = JSONObject()
        .put("type", "string")
        .put("enum", JSONArray(values))

    internal const val MAX_DYNAMIC_STATUS_PATHS = 64
    internal const val MAX_DYNAMIC_LOG_COMMITS = 100
    internal const val MAX_DYNAMIC_BRANCHES = 100
    internal const val MAX_DYNAMIC_PATHS = 64
}

internal class GitDynamicToolExecutor(
    private val service: JGitRepositoryService,
    private val readinessProbe: GitDynamicReadinessProbe,
    private val identityProvider: () -> GitIdentity?,
    private val backgroundExecutor: Executor,
) : DynamicToolExecutor {
    override val specs: List<DynamicToolNamespaceSpec>
        get() = effectiveReadiness().let { readiness ->
            GitDynamicToolCatalog.namespace(readiness)?.let(::listOf).orEmpty()
        }

    override fun execute(
        call: DynamicToolCallParams,
        completion: (DynamicToolExecutionResult) -> Unit,
    ) {
        executeCancellable(call, DynamicToolCancellation.NONE, completion)
    }

    override fun executeCancellable(
        call: DynamicToolCallParams,
        cancellation: DynamicToolCancellation,
        completion: (DynamicToolExecutionResult) -> Unit,
    ): DynamicToolExecutionHandle {
        val gate = DynamicToolExecutionGate(cancellation, completion)
        val scheduled = gate.schedule(backgroundExecutor) {
            val result = runCatching { executeSafely(call, gate) }
                .getOrElse { failureResult(call, it.gitFailureCode()) }
            gate.complete(result)
        }
        if (!scheduled) gate.complete(failureResult(call, "git_executor_rejected"))
        return gate
    }

    override fun failureResult(
        call: DynamicToolCallParams,
        code: String,
    ): DynamicToolExecutionResult = result(
        JSONObject()
            .put("status", "failed")
            .put("errorCode", code.takeIf(KNOWN_FAILURE_CODES::contains) ?: "git_tool_failed"),
        success = false,
    )

    private fun executeSafely(
        call: DynamicToolCallParams,
        gate: DynamicToolExecutionGate,
    ): DynamicToolExecutionResult {
        require(call.namespace == GitDynamicToolCatalog.NAMESPACE) { "git_namespace_unknown" }
        val readiness = effectiveReadiness()
        require(call.tool in GitDynamicToolCatalog.availableToolNames(readiness)) {
            "git_tool_unavailable"
        }
        val args = JsonContract.parseObject(call.argumentsJson, MAX_ARGUMENT_BYTES)
        val cancellation = GitCancellation(gate::isCancellationRequested)
        return when (call.tool) {
            "init" -> {
                args.requireOnly("repositoryId", "initialBranch")
                externalEffect(gate, call) {
                    service.initialize(
                        repositoryId = args.repositoryId(),
                        initialBranch = args.optionalString("initialBranch", 255) ?: "main",
                        cancellation = cancellation,
                    ).toJson()
                }
            }
            "open" -> {
                args.requireOnly("repositoryId")
                externalEffect(gate, call) { service.open(args.repositoryId()).toJson() }
            }
            "status" -> {
                args.requireOnly("repositoryId", "maxPaths")
                externalEffect(gate, call) {
                    service.status(
                        repositoryId = args.repositoryId(),
                        maxPaths = args.optionalInt(
                            "maxPaths",
                            GitDynamicToolCatalog.MAX_DYNAMIC_STATUS_PATHS,
                            1..GitDynamicToolCatalog.MAX_DYNAMIC_STATUS_PATHS,
                        ),
                        cancellation = cancellation,
                    ).toJson()
                }
            }
            "diff" -> {
                args.requireOnly("repositoryId", "scope", "maxBytes")
                val scope = when (args.requiredString("scope", 16)) {
                    GitDiffScope.WORKTREE.wireName -> GitDiffScope.WORKTREE
                    GitDiffScope.STAGED.wireName -> GitDiffScope.STAGED
                    else -> throw IllegalArgumentException("git_diff_scope_invalid")
                }
                externalEffect(gate, call) {
                    service.diff(
                        repositoryId = args.repositoryId(),
                        scope = scope,
                        maxBytes = args.optionalInt(
                            "maxBytes",
                            GitLimits.MAX_DIFF_BYTES,
                            1..GitLimits.MAX_DIFF_BYTES,
                        ),
                        cancellation = cancellation,
                    ).toJson()
                }
            }
            "log" -> {
                args.requireOnly("repositoryId", "maxCommits")
                externalEffect(gate, call) {
                    service.log(
                        repositoryId = args.repositoryId(),
                        maxCommits = args.optionalInt(
                            "maxCommits",
                            50,
                            1..GitDynamicToolCatalog.MAX_DYNAMIC_LOG_COMMITS,
                        ),
                        cancellation = cancellation,
                    ).toJson()
                }
            }
            "branch" -> {
                args.requireOnly("repositoryId", "action", "branchName", "startPoint", "maxBranches")
                val repositoryId = args.repositoryId()
                when (args.requiredString("action", 16)) {
                    "list" -> {
                        require(!args.has("branchName") && !args.has("startPoint")) {
                            "git_argument_invalid"
                        }
                        externalEffect(gate, call) {
                            service.branches(
                                repositoryId = repositoryId,
                                maxBranches = args.optionalInt(
                                    "maxBranches",
                                    GitDynamicToolCatalog.MAX_DYNAMIC_BRANCHES,
                                    1..GitDynamicToolCatalog.MAX_DYNAMIC_BRANCHES,
                                ),
                                cancellation = cancellation,
                            ).toJson()
                        }
                    }
                    "create" -> {
                        require(!args.has("maxBranches")) { "git_argument_invalid" }
                        externalEffect(gate, call) {
                            service.createBranch(
                                repositoryId = repositoryId,
                                branchName = args.requiredString("branchName", 255),
                                startPoint = args.optionalString("startPoint", 512),
                                cancellation = cancellation,
                            ).toJson { branch -> branch.toJson() }
                        }
                    }
                    else -> throw IllegalArgumentException("git_argument_invalid")
                }
            }
            "checkout" -> {
                args.requireOnly("repositoryId", "branchName")
                externalEffect(gate, call) {
                    service.checkout(
                        repositoryId = args.repositoryId(),
                        branchName = args.requiredString("branchName", 255),
                        cancellation = cancellation,
                    ).toJson()
                }
            }
            "stage" -> {
                args.requireOnly("repositoryId", "paths")
                val paths = args.requiredPaths()
                externalEffect(gate, call) {
                    service.stage(args.repositoryId(), paths, cancellation).toJson { staged ->
                        JSONArray().also { array -> staged.forEach { array.put(it.value) } }
                    }
                }
            }
            "commit" -> {
                args.requireOnly("repositoryId", "message")
                val identity = requireNotNull(identityProvider()) { "git_identity_unavailable" }
                externalEffect(gate, call) {
                    service.commit(
                        repositoryId = args.repositoryId(),
                        message = args.requiredText("message", GitLimits.MAX_COMMIT_MESSAGE_CHARS),
                        identity = identity,
                        cancellation = cancellation,
                    ).toJson { commit -> commit.toJson() }
                }
            }
            "clone" -> {
                args.requireOnly("repositoryId", "remoteUri")
                val remote = GitRemoteUri.parsePublic(
                    args.requiredString("remoteUri", GitLimits.MAX_REMOTE_URI_CHARS),
                )
                require(readiness.transport.supports(remote.scheme)) { "git_transport_unavailable" }
                externalEffect(gate, call) {
                    service.clone(args.repositoryId(), remote, cancellation).toJson()
                }
            }
            "fetch", "pull", "push" -> {
                args.requireOnly("repositoryId", "remoteName")
                val repositoryId = args.repositoryId()
                val remoteName = GitRemoteName(args.optionalString("remoteName", 64) ?: "origin")
                externalEffect(gate, call) {
                    when (call.tool) {
                        "fetch" -> service.fetch(repositoryId, remoteName, cancellation)
                        "pull" -> service.pull(repositoryId, remoteName, cancellation)
                        else -> service.push(repositoryId, remoteName, cancellation)
                    }.toJson()
                }
            }
            else -> failureResult(call, "git_tool_unknown")
        }
    }

    private fun effectiveReadiness(): GitDynamicReadiness {
        val probed = readinessProbe.probe()
        return probed.copy(commitIdentity = probed.commitIdentity && identityProvider() != null)
    }

    private inline fun externalEffect(
        gate: DynamicToolExecutionGate,
        call: DynamicToolCallParams,
        block: () -> JSONObject,
    ): DynamicToolExecutionResult {
        if (!gate.markExternalEffectStarted()) return failureResult(call, "git_operation_cancelled")
        return result(block(), success = true)
    }

    private fun result(value: JSONObject, success: Boolean): DynamicToolExecutionResult {
        val text = value.toString()
        require(text.toByteArray(StandardCharsets.UTF_8).size <= GitLimits.MAX_RESULT_BYTES) {
            "git_result_limit_exceeded"
        }
        return DynamicToolExecutionResult(text, success)
    }

    private fun GitRepositorySnapshot.toJson(): JSONObject = JSONObject()
        .put("status", "ok")
        .put("repositoryId", repositoryId.value)
        .putNullable("headObjectId", headObjectId)
        .putNullable("branch", branch)
        .put("workingTree", status.toJson())

    private fun GitStatusSnapshot.toJson(): JSONObject = JSONObject()
        .put("clean", clean)
        .put("totalPaths", totalPaths)
        .put("truncated", truncated)
        .put(
            "paths",
            JSONArray().also { array ->
                paths.forEach { path ->
                    array.put(
                        JSONObject()
                            .put("path", path.path.value)
                            .put(
                                "states",
                                JSONArray().also { states ->
                                    path.states.sortedBy(GitPathState::wireName).forEach {
                                        states.put(it.wireName)
                                    }
                                },
                            ),
                    )
                }
            },
        )

    private fun GitDiffSnapshot.toJson(): JSONObject = JSONObject()
        .put("status", "ok")
        .put("repositoryId", repositoryId.value)
        .put("scope", scope.wireName)
        .put("patch", patch)
        .put("byteCount", byteCount)
        .put("truncated", truncated)

    private fun GitLogSnapshot.toJson(): JSONObject = JSONObject()
        .put("status", "ok")
        .put("repositoryId", repositoryId.value)
        .put("hasMore", hasMore)
        .put("commits", JSONArray().also { array -> commits.forEach { array.put(it.toJson()) } })

    private fun GitBranchListSnapshot.toJson(): JSONObject = JSONObject()
        .put("status", "ok")
        .put("repositoryId", repositoryId.value)
        .put("totalBranches", totalBranches)
        .put("truncated", truncated)
        .put("branches", JSONArray().also { array -> branches.forEach { array.put(it.toJson()) } })

    private fun GitBranchSnapshot.toJson(): JSONObject = JSONObject()
        .put("name", name)
        .putNullable("objectId", objectId)
        .put("current", current)

    private fun GitCommitSnapshot.toJson(): JSONObject = JSONObject()
        .put("objectId", objectId)
        .put("parentObjectIds", JSONArray(parentObjectIds))
        .put("parentsTruncated", parentsTruncated)
        .put("authorName", authorName)
        .put("authorEmail", authorEmail)
        .put("committedEpochSeconds", committedEpochSeconds)
        .put("shortMessage", shortMessage)

    private fun <T> GitMutationResult<T>.toJson(
        valueProjection: (T) -> Any = { value ->
            when (value) {
                is GitRepositorySnapshot -> value.toJson()
                else -> value.toString()
            }
        },
    ): JSONObject = JSONObject()
        .put("status", "ok")
        .put("value", valueProjection(value))
        .put("postcondition", postcondition.toJson())

    private fun GitPostcondition.toJson(): JSONObject = JSONObject()
        .put("repositoryId", repositoryId.value)
        .put("verified", verified)
        .putNullable("headBefore", headBefore)
        .putNullable("headAfter", headAfter)
        .put("workingTreeAfter", statusAfter.toJson())

    private fun GitNetworkResult.toJson(): JSONObject = JSONObject()
        .put("status", if (successful) "ok" else "rejected")
        .put("operation", operation.wireName)
        .put("remoteName", remoteName.value)
        .put("successful", successful)
        .put(
            "updates",
            JSONArray().also { array ->
                updates.forEach { update ->
                    array.put(
                        JSONObject()
                            .put("name", update.name)
                            .put("status", update.status)
                            .put("successful", update.successful)
                            .putNullable("oldObjectId", update.oldObjectId)
                            .putNullable("newObjectId", update.newObjectId),
                    )
                }
            },
        )
        .put("postcondition", postcondition.toJson())

    private fun JSONObject.repositoryId(): GitRepositoryId =
        GitRepositoryId(requiredString("repositoryId", GitLimits.MAX_REPOSITORY_ID_CHARS))

    private fun JSONObject.requiredPaths(): List<GitRelativePath> {
        val raw = optJSONArray("paths") ?: throw IllegalArgumentException("git_paths_invalid")
        require(raw.length() in 1..GitDynamicToolCatalog.MAX_DYNAMIC_PATHS) { "git_paths_invalid" }
        return List(raw.length()) { index ->
            val path = raw.opt(index) as? String ?: throw IllegalArgumentException("git_paths_invalid")
            GitRelativePath(path)
        }.also { paths -> require(paths.distinct().size == paths.size) { "git_stage_path_duplicate" } }
    }

    private fun JSONObject.requireOnly(vararg allowed: String) {
        val allowedSet = allowed.toSet()
        require(keys().asSequence().all { it in allowedSet }) { "git_arguments_unexpected" }
    }

    private fun JSONObject.requiredString(key: String, maxChars: Int): String {
        val value = opt(key) as? String ?: throw IllegalArgumentException("git_argument_missing")
        require(value.isNotBlank() && value.length <= maxChars && value.none(Char::isISOControl)) {
            "git_argument_invalid"
        }
        return value
    }

    private fun JSONObject.optionalString(key: String, maxChars: Int): String? =
        if (has(key)) requiredString(key, maxChars) else null

    private fun JSONObject.requiredText(key: String, maxChars: Int): String {
        val value = opt(key) as? String ?: throw IllegalArgumentException("git_argument_missing")
        require(value.isNotBlank() && value.length <= maxChars && '\u0000' !in value) {
            "git_argument_invalid"
        }
        return value
    }

    private fun JSONObject.optionalInt(key: String, default: Int, range: IntRange): Int {
        if (!has(key)) return default
        val value = when (val raw = opt(key)) {
            is Int -> raw
            is Long -> raw.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
            else -> null
        } ?: throw IllegalArgumentException("git_argument_invalid")
        require(value in range) { "git_argument_invalid" }
        return value
    }

    private fun JSONObject.putNullable(key: String, value: String?): JSONObject = apply {
        put(key, value ?: JSONObject.NULL)
    }

    private fun Throwable.gitFailureCode(): String {
        val chain = generateSequence(this) { it.cause }
        val known = chain.mapNotNull(Throwable::message).firstOrNull(KNOWN_FAILURE_CODES::contains)
        if (known != null) return known
        return when {
            chain.any { it is GitOperationCancelledException } -> "git_operation_cancelled"
            chain.any { it is GitAPIException } -> "git_operation_failed"
            chain.any { it is IOException } -> "git_io_failed"
            chain.any { it is IllegalArgumentException } -> "git_arguments_invalid"
            chain.any { it is IllegalStateException } -> "git_integrity_failure"
            else -> "git_tool_failed"
        }
    }

    private companion object {
        const val MAX_ARGUMENT_BYTES = 512 * 1024
        val KNOWN_FAILURE_CODES = setOf(
            "git_argument_invalid",
            "git_argument_missing",
            "git_arguments_invalid",
            "git_arguments_unexpected",
            "git_branch_invalid",
            "git_branch_limit_invalid",
            "git_branch_postcondition_failed",
            "git_checkout_postcondition_failed",
            "git_commit_message_invalid",
            "git_commit_postcondition_failed",
            "git_credentials_unavailable",
            "git_diff_limit_invalid",
            "git_diff_scope_invalid",
            "git_diff_write_invalid",
            "git_executor_rejected",
            "git_identity_unavailable",
            "git_integrity_failure",
            "git_io_failed",
            "git_log_limit_invalid",
            "git_metadata_path_forbidden",
            "git_namespace_unknown",
            "git_operation_cancelled",
            "git_operation_failed",
            "git_path_invalid",
            "git_paths_invalid",
            "git_private_boundary_symlink",
            "git_private_boundary_unavailable",
            "git_private_boundary_unsafe",
            "git_private_remote_outside_boundary",
            "git_private_remote_unavailable",
            "git_pull_not_fast_forward",
            "git_remote_credentials_forbidden",
            "git_remote_host_invalid",
            "git_remote_name_invalid",
            "git_remote_not_configured",
            "git_remote_path_invalid",
            "git_remote_scheme_unsupported",
            "git_remote_update_limit_exceeded",
            "git_remote_uri_invalid",
            "git_repository_already_exists",
            "git_repository_byte_limit_exceeded",
            "git_repository_create_failed",
            "git_repository_entry_unsupported",
            "git_repository_file_limit_exceeded",
            "git_repository_id_invalid",
            "git_repository_invalid",
            "git_repository_not_found",
            "git_repository_path_escape",
            "git_repository_root_escaped",
            "git_repository_root_outside_boundary",
            "git_repository_root_symlink",
            "git_repository_root_unavailable",
            "git_repository_root_unsafe",
            "git_repository_symlink_forbidden",
            "git_result_limit_exceeded",
            "git_stage_path_count_invalid",
            "git_stage_path_duplicate",
            "git_status_limit_invalid",
            "git_start_point_invalid",
            "git_tool_failed",
            "git_tool_unavailable",
            "git_tool_unknown",
            "git_transport_unavailable",
        )
    }
}
