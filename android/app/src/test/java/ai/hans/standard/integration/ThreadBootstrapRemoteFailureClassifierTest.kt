package ai.hans.standard.integration

import ai.hans.standard.codex.RemoteError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadBootstrapRemoteFailureClassifierTest {
    @Test fun everyPinnedFamilyProducesOnlyItsFixedCategory() {
        val covered = pinnedCases.map { case ->
            val actual = classify(case.code, case.message)
            assertEquals(case.message, case.reason, actual)
            assertTrue(actual.diagnosticName.matches(Regex("[a-z_]+")))
            assertFalse(actual.diagnosticName.contains(THREAD_ID))
            assertFalse(actual.diagnosticName.contains(PRIVATE_DETAIL))
            actual
        }.toSet()
        assertEquals(
            ThreadBootstrapRemoteFailureReason.entries.toSet() - ThreadBootstrapRemoteFailureReason.UNCLASSIFIED,
            covered,
        )
    }

    @Test fun idBearingFamiliesRequireTheExactRequestIdAndWholeMessage() {
        pinnedCases.filter { it.message.contains(THREAD_ID) }.forEach { case ->
            listOf(null, "other-thread", "", "bad\nthread", "x".repeat(257)).forEach { expectedId ->
                assertUnclassified(case.code, case.message, expectedId)
            }
            if (case.reason !in setOf(ThreadBootstrapRemoteFailureReason.THREAD_LOCATE_FAILED,
                    ThreadBootstrapRemoteFailureReason.THREAD_PATH_MISMATCH)) {
                assertUnclassified(case.code, case.message + " PRIVATE_TRAILING")
            }
            assertUnclassified(case.code, "PRIVATE_PREFIX " + case.message)
            assertUnclassified(case.code, case.message.replace(THREAD_ID, "other-thread"))
        }
    }

    @Test fun unknownErrorCodesAndMisleadingWordsNeverProveKnownFailure() {
        pinnedCases.forEach { case ->
            listOf(-1L, 0L, -32601L, 429L, Long.MAX_VALUE).forEach { code ->
                assertUnclassified(code, case.message)
            }
        }
        listOf(
            "thread not found",
            "PRIVATE_CREDENTIAL no rollout found for thread id $THREAD_ID",
            "active writer",
            "failed to load configuration:",
            "failed to load configuration: ",
            "error resuming thread: ",
            "failed to read thread: ",
            "cannot restore workspace root `/PRIVATE_PATH`",
            "failed to resolve rollout path ",
            "failed to locate thread id $THREAD_ID: ",
            "failed to locate archived thread id $THREAD_ID: ",
        ).forEach { message ->
            listOf(-32600L, -32602L, -32603L).forEach { assertUnclassified(it, message) }
        }
    }

    @Test fun codeIsPartOfEachPinnedContract() {
        pinnedCases.forEach { case ->
            listOf(-32600L, -32602L, -32603L).filterNot { it == case.code }.forEach { code ->
                assertUnclassified(code, case.message)
            }
        }
    }

    @Test fun untrustedDetailsAndDataNeverBecomeDiagnosticText() {
        val details = listOf(
            PRIVATE_DETAIL,
            "sk-test-secret /data/user/0/private/file user@example.invalid",
            "\nreason=active_writer\nPRIVATE_TRANSCRIPT",
            "\u0000\u001b[31mPRIVATE_TOKEN",
        )
        details.forEach { detail ->
            val actual = ThreadBootstrapRemoteFailureClassifier.classify(
                RemoteError(-32600, "failed to load configuration: $detail", detail),
                THREAD_ID,
            )
            assertEquals("configuration_load", actual.diagnosticName)
            assertFalse(actual.diagnosticName.contains(detail))
            val unknown = ThreadBootstrapRemoteFailureClassifier.classify(
                RemoteError(-32600, detail, "no rollout found for thread id $THREAD_ID"),
                THREAD_ID,
            )
            assertEquals(ThreadBootstrapRemoteFailureReason.UNCLASSIFIED, unknown)
        }
    }

    @Test fun classifierBoundsInputAndHandlesMissingErrors() {
        assertEquals(
            ThreadBootstrapRemoteFailureReason.UNCLASSIFIED,
            ThreadBootstrapRemoteFailureClassifier.classify(null, THREAD_ID),
        )
        assertUnclassified(-32600, "")
        val prefix = "failed to load configuration: "
        assertEquals(
            ThreadBootstrapRemoteFailureReason.CONFIGURATION_LOAD,
            classify(-32600, prefix + "x".repeat(16_384 - prefix.length)),
        )
        assertUnclassified(-32600, prefix + "x".repeat(16_385 - prefix.length))
        assertUnclassified(-32600, "no rollout found for thread id " + "x".repeat(257), "x".repeat(257))
    }

    private fun classify(code: Long, message: String, expectedId: String? = THREAD_ID) =
        ThreadBootstrapRemoteFailureClassifier.classify(RemoteError(code, message, PRIVATE_DETAIL), expectedId)

    private fun assertUnclassified(code: Long, message: String, expectedId: String? = THREAD_ID) =
        assertEquals(ThreadBootstrapRemoteFailureReason.UNCLASSIFIED, classify(code, message, expectedId))

    private data class Case(val code: Long, val message: String, val reason: ThreadBootstrapRemoteFailureReason)

    private val pinnedCases = listOf(
        Case(-32600, "dynamic tool namespace description must be at most 1024 characters",
            ThreadBootstrapRemoteFailureReason.DYNAMIC_NAMESPACE_DESCRIPTION_LIMIT),
        Case(-32600, "no rollout found for thread id $THREAD_ID", ThreadBootstrapRemoteFailureReason.ROLLOUT_MISSING),
        Case(-32600, "thread not found: $THREAD_ID", ThreadBootstrapRemoteFailureReason.THREAD_MISSING),
        Case(-32600, "thread $THREAD_ID is closing; retry thread/resume after the thread is closed",
            ThreadBootstrapRemoteFailureReason.THREAD_CLOSING),
        Case(-32600, "session $THREAD_ID is archived. Run `codex unarchive $THREAD_ID` to unarchive it first.",
            ThreadBootstrapRemoteFailureReason.THREAD_ARCHIVED),
        Case(-32600, "thread $THREAD_ID already has an active writer", ThreadBootstrapRemoteFailureReason.ACTIVE_WRITER),
        Case(-32600, "thread $THREAD_ID is already running", ThreadBootstrapRemoteFailureReason.THREAD_ALREADY_RUNNING),
        Case(-32600, "thread $THREAD_ID is already running with a different rollout path",
            ThreadBootstrapRemoteFailureReason.THREAD_PATH_MISMATCH),
        Case(-32600, "cannot resume running thread $THREAD_ID with stale path: requested `/PRIVATE_A`, active `/PRIVATE_B`",
            ThreadBootstrapRemoteFailureReason.THREAD_PATH_MISMATCH),
        Case(-32600, "cannot resume paginated thread $THREAD_ID with stale path: requested /PRIVATE_A, current /PRIVATE_B; " +
            "omit path and resume by thread id", ThreadBootstrapRemoteFailureReason.THREAD_PATH_MISMATCH),
        Case(-32600, "failed to resolve rollout path `/PRIVATE_PATH`: file does not exist",
            ThreadBootstrapRemoteFailureReason.ROLLOUT_PATH_INVALID),
        Case(-32600, "failed to locate thread id $THREAD_ID: $PRIVATE_DETAIL",
            ThreadBootstrapRemoteFailureReason.THREAD_LOCATE_FAILED),
        Case(-32600, "failed to locate archived thread id $THREAD_ID: $PRIVATE_DETAIL",
            ThreadBootstrapRemoteFailureReason.THREAD_LOCATE_FAILED),
        Case(-32600, "failed to load configuration: $PRIVATE_DETAIL", ThreadBootstrapRemoteFailureReason.CONFIGURATION_LOAD),
        Case(-32600, "invalid session id: $PRIVATE_DETAIL", ThreadBootstrapRemoteFailureReason.INVALID_THREAD_ID),
        Case(-32600, "invalid thread id: $PRIVATE_DETAIL", ThreadBootstrapRemoteFailureReason.INVALID_THREAD_ID),
        Case(-32600, "history must not be empty", ThreadBootstrapRemoteFailureReason.HISTORY_EMPTY),
        Case(-32600, "`permissions` cannot be combined with `sandbox`", ThreadBootstrapRemoteFailureReason.PERMISSIONS_CONFLICT),
        Case(-32603, "thread $THREAD_ID did not include persisted history",
            ThreadBootstrapRemoteFailureReason.RESUME_HISTORY_MISSING),
        Case(-32603, "failed to read thread: $PRIVATE_DETAIL", ThreadBootstrapRemoteFailureReason.THREAD_READ_FAILED),
        Case(-32603, "error resuming thread: $PRIVATE_DETAIL", ThreadBootstrapRemoteFailureReason.THREAD_RESUME_FAILED),
        Case(-32602, "cannot restore workspace root `/PRIVATE_PATH` on this host: $PRIVATE_DETAIL. " +
            "Pass `runtimeWorkspaceRoots` with valid local paths or an empty list.",
            ThreadBootstrapRemoteFailureReason.WORKSPACE_ROOT_INVALID),
    )

    private companion object {
        const val THREAD_ID = "00000000-1111-2222-3333-444444444444"
        const val PRIVATE_DETAIL = "PRIVATE_ERROR_DETAIL"
    }
}
