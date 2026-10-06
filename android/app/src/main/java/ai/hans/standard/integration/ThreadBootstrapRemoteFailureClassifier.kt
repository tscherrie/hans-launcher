package ai.hans.standard.integration

import ai.hans.standard.codex.RemoteError

/** Fixed diagnostic vocabulary only. These categories never authorize recovery or data changes. */
internal enum class ThreadBootstrapRemoteFailureReason(val diagnosticName: String) {
    DYNAMIC_NAMESPACE_DESCRIPTION_LIMIT("dynamic_namespace_description_limit"),
    ROLLOUT_MISSING("rollout_missing"),
    THREAD_MISSING("thread_missing"),
    THREAD_CLOSING("thread_closing"),
    THREAD_ARCHIVED("thread_archived"),
    ACTIVE_WRITER("active_writer"),
    THREAD_ALREADY_RUNNING("thread_already_running"),
    THREAD_PATH_MISMATCH("thread_path_mismatch"),
    ROLLOUT_PATH_INVALID("rollout_path_invalid"),
    THREAD_LOCATE_FAILED("thread_locate_failed"),
    CONFIGURATION_LOAD("configuration_load"),
    INVALID_THREAD_ID("invalid_thread_id"),
    HISTORY_EMPTY("history_empty"),
    PERMISSIONS_CONFLICT("permissions_conflict"),
    RESUME_HISTORY_MISSING("resume_history_missing"),
    THREAD_READ_FAILED("thread_read_failed"),
    THREAD_RESUME_FAILED("thread_resume_failed"),
    WORKSPACE_ROOT_INVALID("workspace_root_invalid"),
    UNCLASSIFIED("unclassified"),
}

/**
 * Matches pinned Codex 0.155.0 error templates, never arbitrary words such as "not found".
 * ID-bearing messages must match this request's ID exactly. Variable configuration/error/path
 * tails are inspected only for their pinned prefix/suffix and are never returned or retained.
 * Neither a category nor -32600 proves permanent absence; callers must preserve the saved task.
 *
 * Sources: app-server/src/request_processors/{thread_processor.rs,config_errors.rs},
 * app-server/tests/suite/v2/thread_resume.rs (competing writers), runtime.lock.json.
 */
internal object ThreadBootstrapRemoteFailureClassifier {
    fun classify(
        error: RemoteError?,
        expectedThreadId: String? = null,
    ): ThreadBootstrapRemoteFailureReason {
        if (error == null || error.message.length !in 1..MAX_MESSAGE_CHARACTERS) {
            return ThreadBootstrapRemoteFailureReason.UNCLASSIFIED
        }
        val message = error.message
        val threadId = expectedThreadId?.takeIf { id ->
            id.length in 1..MAX_THREAD_ID_CHARACTERS &&
                id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
        }
        return when (error.code) {
            -32600L -> when {
                message == "dynamic tool namespace description must be at most 1024 characters" ->
                    ThreadBootstrapRemoteFailureReason.DYNAMIC_NAMESPACE_DESCRIPTION_LIMIT
                threadId != null && message == "no rollout found for thread id $threadId" ->
                    ThreadBootstrapRemoteFailureReason.ROLLOUT_MISSING
                threadId != null && message == "thread not found: $threadId" ->
                    ThreadBootstrapRemoteFailureReason.THREAD_MISSING
                threadId != null &&
                    message == "thread $threadId is closing; retry thread/resume after the thread is closed" ->
                    ThreadBootstrapRemoteFailureReason.THREAD_CLOSING
                threadId != null && message == "session $threadId is archived. " +
                    "Run `codex unarchive $threadId` to unarchive it first." ->
                    ThreadBootstrapRemoteFailureReason.THREAD_ARCHIVED
                threadId != null && message == "thread $threadId already has an active writer" ->
                    ThreadBootstrapRemoteFailureReason.ACTIVE_WRITER
                threadId != null && message == "thread $threadId is already running" ->
                    ThreadBootstrapRemoteFailureReason.THREAD_ALREADY_RUNNING
                threadId != null && (
                    message == "thread $threadId is already running with a different rollout path" ||
                        message.hasDetailAfter("cannot resume running thread $threadId with stale path: requested ") ||
                        message.hasDetailAfter("cannot resume paginated thread $threadId with stale path: requested ")
                    ) -> ThreadBootstrapRemoteFailureReason.THREAD_PATH_MISMATCH
                message.hasDetailAfter("failed to resolve rollout path `") && message.contains("`: ") ->
                    ThreadBootstrapRemoteFailureReason.ROLLOUT_PATH_INVALID
                threadId != null && (
                    message.hasDetailAfter("failed to locate thread id $threadId: ") ||
                        message.hasDetailAfter("failed to locate archived thread id $threadId: ")
                    ) -> ThreadBootstrapRemoteFailureReason.THREAD_LOCATE_FAILED
                message.hasDetailAfter("failed to load configuration: ") ->
                    ThreadBootstrapRemoteFailureReason.CONFIGURATION_LOAD
                message.hasDetailAfter("invalid session id: ") ||
                    message.hasDetailAfter("invalid thread id: ") ->
                    ThreadBootstrapRemoteFailureReason.INVALID_THREAD_ID
                message == "history must not be empty" -> ThreadBootstrapRemoteFailureReason.HISTORY_EMPTY
                message == "`permissions` cannot be combined with `sandbox`" ->
                    ThreadBootstrapRemoteFailureReason.PERMISSIONS_CONFLICT
                else -> ThreadBootstrapRemoteFailureReason.UNCLASSIFIED
            }
            -32602L -> if (
                message.hasDetailAfter("cannot restore workspace root `") &&
                message.endsWith(". Pass `runtimeWorkspaceRoots` with valid local paths or an empty list.") &&
                message.contains("` on this host: ")
            ) {
                ThreadBootstrapRemoteFailureReason.WORKSPACE_ROOT_INVALID
            } else {
                ThreadBootstrapRemoteFailureReason.UNCLASSIFIED
            }
            -32603L -> when {
                threadId != null && message == "thread $threadId did not include persisted history" ->
                    ThreadBootstrapRemoteFailureReason.RESUME_HISTORY_MISSING
                message.hasDetailAfter("failed to read thread: ") ->
                    ThreadBootstrapRemoteFailureReason.THREAD_READ_FAILED
                message.hasDetailAfter("error resuming thread: ") ->
                    ThreadBootstrapRemoteFailureReason.THREAD_RESUME_FAILED
                else -> ThreadBootstrapRemoteFailureReason.UNCLASSIFIED
            }
            else -> ThreadBootstrapRemoteFailureReason.UNCLASSIFIED
        }
    }

    private fun String.hasDetailAfter(prefix: String): Boolean = length > prefix.length && startsWith(prefix)

    private const val MAX_MESSAGE_CHARACTERS = 16_384
    private const val MAX_THREAD_ID_CHARACTERS = 256
}
