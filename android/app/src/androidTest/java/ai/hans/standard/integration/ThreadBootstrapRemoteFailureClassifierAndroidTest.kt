package ai.hans.standard.integration

import ai.hans.standard.codex.RemoteError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure packaged-code contract: no Activity, session, account, microphone, filesystem or network. */
class ThreadBootstrapRemoteFailureClassifierAndroidTest {
    @Test fun pinnedResumeCategoriesAreStableOnEverySupportedAndroid() {
        val cases = listOf(
            Triple(-32600L, "no rollout found for thread id $THREAD_ID", "rollout_missing"),
            Triple(-32600L, "thread not found: $THREAD_ID", "thread_missing"),
            Triple(-32600L, "thread $THREAD_ID already has an active writer", "active_writer"),
            Triple(-32600L, "thread $THREAD_ID is already running", "thread_already_running"),
            Triple(-32600L, "thread $THREAD_ID is already running with a different rollout path", "thread_path_mismatch"),
            Triple(-32600L, "failed to resolve rollout path `/PRIVATE_PATH`: file does not exist", "rollout_path_invalid"),
            Triple(-32600L, "failed to locate thread id $THREAD_ID: PRIVATE_IO", "thread_locate_failed"),
            Triple(-32600L, "thread $THREAD_ID is closing; retry thread/resume after the thread is closed", "thread_closing"),
            Triple(-32600L, "session $THREAD_ID is archived. Run `codex unarchive $THREAD_ID` to unarchive it first.", "thread_archived"),
            Triple(-32600L, "failed to load configuration: PRIVATE_CONFIGURATION", "configuration_load"),
            Triple(-32600L, "invalid session id: PRIVATE_ID", "invalid_thread_id"),
            Triple(-32600L, "history must not be empty", "history_empty"),
            Triple(-32600L, "`permissions` cannot be combined with `sandbox`", "permissions_conflict"),
            Triple(-32600L, "dynamic tool namespace description must be at most 1024 characters", "dynamic_namespace_description_limit"),
            Triple(-32603L, "thread $THREAD_ID did not include persisted history", "resume_history_missing"),
            Triple(-32603L, "failed to read thread: PRIVATE_STORAGE", "thread_read_failed"),
            Triple(-32603L, "error resuming thread: PRIVATE_STORAGE", "thread_resume_failed"),
            Triple(-32602L, "cannot restore workspace root `/PRIVATE_PATH` on this host: PRIVATE_ERROR. " +
                "Pass `runtimeWorkspaceRoots` with valid local paths or an empty list.", "workspace_root_invalid"),
        )
        cases.forEach { (code, message, expected) ->
            val actual = classify(code, message)
            assertEquals(expected, actual)
            assertTrue(actual.all { it in 'a'..'z' || it == '_' })
        }
    }

    @Test fun foreignIdsUnknownErrorsAndSecretDataRemainUnclassified() {
        assertEquals("unclassified", classify(-32600, "no rollout found for thread id other-thread"))
        assertEquals("unclassified", classify(-1, "no rollout found for thread id $THREAD_ID"))
        assertEquals("unclassified", classify(-32600, "PRIVATE_SECRET"))
        assertEquals("unclassified", classify(-32600, "failed to load configuration: " + "x".repeat(16_384)))
        assertEquals("configuration_load", classify(-32600, "failed to load configuration: \nPRIVATE_TOKEN"))
    }

    private fun classify(code: Long, message: String): String = ThreadBootstrapRemoteFailureClassifier.classify(
        RemoteError(code, message, "PRIVATE_ERROR_DATA"), THREAD_ID,
    ).diagnosticName

    private companion object {
        const val THREAD_ID = "00000000-1111-2222-3333-444444444444"
    }
}
