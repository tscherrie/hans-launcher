package ai.hans.standard.profile

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolCancellationDisposition
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.setup.SetupProfileTurnActionGuard
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfileDynamicToolsTest {
    @Test
    fun malformedProfileCallDoesNotConsumeTheTurnBeforeCanonicalCorrection() {
        val executor = executor()
        val turnId = "profile_invalid_then_corrected"
        assertTrue(call(executor, "begin_interview", "{}").success)

        val malformed = call(
            executor,
            "record_answer",
            """{"topic":"family","answer":"Warm und nah."}""",
            turnId,
        )
        assertFalse(malformed.success)

        val corrected = call(
            executor,
            "record_answer",
            """{"topic":"family","question":"Wer gehört zu dir?","answer":"Warm und nah."}""",
            turnId,
        )
        assertTrue(corrected.success)
    }

    @Test
    fun rejectedProfilePreconditionDoesNotConsumeTheTurnBeforeRepair() {
        val executor = executor()
        val turnId = "profile_precondition_then_repair"

        val rejected = call(
            executor,
            "record_answer",
            """{"topic":"family","question":"Wer gehört zu dir?","answer":"Meine Familie."}""",
            turnId,
        )
        assertFalse(rejected.success)

        assertTrue(call(executor, "begin_interview", "{}", turnId).success)
    }

    @Test
    fun queuedCancellationPreventsEveryProfileStorageAndPolicySideEffect() {
        val storage = CountingStorage()
        val queued = ManualExecutor()
        var policyCalls = 0
        var callbacks = 0
        val executor = UserProfileDynamicToolExecutor(
            repository = repository(storage),
            backgroundExecutor = queued,
            turnActionGuard = SetupProfileTurnActionGuard(),
            mutationPolicy = UserProfileMutationPolicy {
                policyCalls += 1
                true
            },
        )

        val handle = executor.executeCancellable(
            params("begin_interview", "{}", "queued_profile_cancel"),
            DynamicToolCancellation.NONE,
        ) { callbacks += 1 }

        assertEquals(
            DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT,
            handle.cancel(),
        )
        queued.runNext()
        assertEquals(0, policyCalls)
        assertEquals(0, storage.accesses)
        assertEquals(0, callbacks)
    }

    @Test
    fun cancellationDuringMutationPolicyPreventsTurnClaimAndRepositoryMutation() {
        val storage = CountingStorage()
        val cancellationRequested = AtomicBoolean(false)
        val cancelDuringPolicy = AtomicBoolean(true)
        var callbacks = 0
        val executor = UserProfileDynamicToolExecutor(
            repository = repository(storage),
            backgroundExecutor = Executor(Runnable::run),
            turnActionGuard = SetupProfileTurnActionGuard(),
            mutationPolicy = UserProfileMutationPolicy {
                if (cancelDuringPolicy.get()) cancellationRequested.set(true)
                true
            },
        )

        val handle = executor.executeCancellable(
            params("begin_interview", "{}", "policy_profile_cancel"),
            DynamicToolCancellation { cancellationRequested.get() },
        ) { callbacks += 1 }

        assertEquals(
            DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED,
            handle.cancel(),
        )
        assertEquals(0, storage.accesses)
        assertEquals(0, callbacks)
        cancelDuringPolicy.set(false)
        cancellationRequested.set(false)
        assertTrue(
            call(executor, "begin_interview", "{}", "policy_profile_cancel").success,
        )
    }

    @Test
    fun cancellationDuringProfileWriteStillRunsRequiredReconciliationExactlyOnce() {
        val storage = CountingStorage()
        val queued = ManualExecutor()
        var reconciliations = 0
        var callbacks = 0
        var disposition: DynamicToolCancellationDisposition? = null
        lateinit var handle: DynamicToolExecutionHandle
        storage.onWrite = { disposition = handle.cancel() }
        val executor = UserProfileDynamicToolExecutor(
            repository = repository(storage),
            backgroundExecutor = queued,
            turnActionGuard = SetupProfileTurnActionGuard(),
            onMutation = { reconciliations += 1 },
        )

        handle = executor.executeCancellable(
            params("begin_interview", "{}", "profile_write_cancel"),
            DynamicToolCancellation.NONE,
        ) { callbacks += 1 }
        queued.runNext()

        assertEquals(
            DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED,
            disposition,
        )
        assertEquals(2, storage.accesses)
        assertEquals(1, reconciliations)
        assertEquals(0, callbacks)
    }

    @Test
    fun setupAwarePolicyAllowsStandalonePersonalProfileAndCompletedStatesOnly() {
        var state = UserProfileSetupSequenceState(
            started = false,
            complete = false,
            atPersonalProfileStep = false,
        )
        val policy = SetupAwareUserProfileMutationPolicy { state }

        assertTrue(policy.mayMutate())
        state = state.copy(started = true)
        assertFalse(policy.mayMutate())
        state = state.copy(atPersonalProfileStep = true)
        assertTrue(policy.mayMutate())
        state = state.copy(atPersonalProfileStep = false, complete = true)
        assertTrue(policy.mayMutate())
    }

    @Test
    fun completeTurnByTurnContractRequiresReviewAndExplicitConfirmation() {
        val executor = executor()
        assertTrue(call(executor, "begin_interview", "{}").success)
        assertTrue(
            call(
                executor,
                "record_answer",
                """{"topic":"name","question":"Wie soll ich dich nennen?","answer":"Alex"}""",
            ).success,
        )
        val proposal = call(
            executor,
            "propose_summary",
            """{"summary":"Name: Alex"}""",
        )
        val nonce = JSONObject(proposal.contentText).getString("confirmationNonce")
        val rejected = call(
            executor,
            "confirm",
            JSONObject()
                .put("confirmationNonce", nonce)
                .put("explicitUserConfirmation", false)
                .toString(),
        )
        assertFalse(rejected.success)

        val confirmed = call(
            executor,
            "confirm",
            JSONObject()
                .put("confirmationNonce", nonce)
                .put("explicitUserConfirmation", true)
                .toString(),
        )
        assertTrue(confirmed.success)
        assertTrue(JSONObject(confirmed.contentText).getBoolean("hasConfirmedProfile"))
    }

    @Test
    fun extraArgumentsAndUnknownToolsFailClosed() {
        val executor = executor()
        assertFalse(call(executor, "read", """{"unexpected":true}""").success)
        assertFalse(call(executor, "unknown", "{}").success)
    }

    @Test
    fun oneProfileMutationPerTurnIsEnforcedWhileReadRemainsAvailable() {
        val executor = executor()
        val turnId = "turn_single_profile_action"
        assertTrue(call(executor, "begin_interview", "{}", turnId).success)

        val rejected = call(
            executor,
            "record_answer",
            """{"topic":"name","question":"Name?","answer":"Alex"}""",
            turnId,
        )
        assertFalse(rejected.success)
        val error = JSONObject(rejected.contentText)
        assertEquals("setup_profile_turn_action_limit", error.getString("errorCode"))
        assertTrue(error.getString("message").contains("one setup or profile change"))

        val read = call(executor, "read", "{}", turnId)
        assertTrue(read.success)
        assertTrue(JSONObject(read.contentText).getBoolean("interviewActive"))
    }

    @Test
    fun mutationReceiptsDoNotRepeatPrivateDraftOrUnneededNonce() {
        val executor = executor()
        call(executor, "begin_interview", "{}")
        val receipt = call(
            executor,
            "record_answer",
            """{"topic":"name","question":"Name?","answer":"Alex"}""",
        )
        val body = JSONObject(receipt.contentText)

        assertTrue(receipt.success)
        assertEquals(1, body.getInt("draftAnswerCount"))
        assertFalse(body.has("draftAnswers"))
        assertFalse(body.has("proposedSummary"))
        assertFalse(body.has("confirmationNonce"))

        val read = JSONObject(call(executor, "read", "{}").contentText)
        assertEquals(1, read.getJSONArray("draftAnswers").length())
    }

    @Test
    fun activeSetupOutsidePersonalProfileDeniesEveryMutationButAllowsReads() {
        var profileStepAllowed = false
        val executor = executor(
            mutationPolicy = UserProfileMutationPolicy { profileStepAllowed },
        )

        val read = call(executor, "read", "{}", turnId = "blocked_read_turn")
        assertTrue(read.success)

        listOf(
            "begin_interview" to "{}",
            "record_answer" to
                """{"topic":"name","question":"Name?","answer":"Alex"}""",
            "propose_summary" to """{"summary":"Name: Alex"}""",
            "confirm" to
                """{"confirmationNonce":"nonce_1234567890","explicitUserConfirmation":true}""",
            "delete" to """{"explicitUserConfirmation":true}""",
        ).forEachIndexed { index, (tool, arguments) ->
            val rejected = call(
                executor,
                tool,
                arguments,
                turnId = "blocked_mutation_$index",
            )
            assertFalse("$tool must be gated during technical setup", rejected.success)
            assertEquals(
                "profile_setup_step_required",
                JSONObject(rejected.contentText).getString("errorCode"),
            )
        }

        profileStepAllowed = true
        assertTrue(
            call(executor, "begin_interview", "{}", turnId = "personal_profile_turn").success,
        )
    }

    @Test
    fun failedSetupStateLookupDeniesMutationWithoutConsumingTurnClaim() {
        var stateReadable = false
        val executor = executor(
            mutationPolicy = UserProfileMutationPolicy {
                check(stateReadable) { "setup_state_unavailable" }
                true
            },
        )
        val turnId = "retry_after_state_recovery"

        val rejected = call(executor, "begin_interview", "{}", turnId)
        assertFalse(rejected.success)
        assertEquals(
            "profile_setup_step_required",
            JSONObject(rejected.contentText).getString("errorCode"),
        )

        stateReadable = true
        assertTrue(call(executor, "begin_interview", "{}", turnId).success)
    }

    @Test
    fun successfulConfirmAndDeleteNotifyLiveContextWhileReadsAndFailuresDoNot() {
        val mutations = AtomicInteger(0)
        val executor = executor(onMutation = { mutations.incrementAndGet() })
        assertTrue(call(executor, "read", "{}").success)
        assertEquals(0, mutations.get())

        assertTrue(call(executor, "begin_interview", "{}").success)
        assertTrue(
            call(
                executor,
                "record_answer",
                """{"topic":"name","question":"Name?","answer":"Alex"}""",
            ).success,
        )
        val proposal = call(executor, "propose_summary", """{"summary":"Name: Alex"}""")
        val nonce = JSONObject(proposal.contentText).getString("confirmationNonce")
        val rejected = call(
            executor,
            "confirm",
            JSONObject()
                .put("confirmationNonce", nonce)
                .put("explicitUserConfirmation", false)
                .toString(),
        )
        assertFalse(rejected.success)
        assertEquals(3, mutations.get())

        assertTrue(
            call(
                executor,
                "confirm",
                JSONObject()
                    .put("confirmationNonce", nonce)
                    .put("explicitUserConfirmation", true)
                    .toString(),
            ).success,
        )
        assertEquals(4, mutations.get())
        assertTrue(
            call(
                executor,
                "delete",
                """{"explicitUserConfirmation":true}""",
            ).success,
        )
        assertEquals(5, mutations.get())
    }

    private fun executor(
        mutationPolicy: UserProfileMutationPolicy = UserProfileMutationPolicy.ALLOW_STANDALONE,
        onMutation: () -> Unit = {},
    ): UserProfileDynamicToolExecutor = UserProfileDynamicToolExecutor(
        repository = repository(MemoryStorage()),
        backgroundExecutor = Executor(Runnable::run),
        turnActionGuard = SetupProfileTurnActionGuard(),
        mutationPolicy = mutationPolicy,
        onMutation = onMutation,
    )

    private fun call(
        executor: UserProfileDynamicToolExecutor,
        tool: String,
        arguments: String,
        turnId: String = "turn_${NEXT_CALL.incrementAndGet()}",
    ): ai.hans.standard.codex.DynamicToolExecutionResult {
        var output: ai.hans.standard.codex.DynamicToolExecutionResult? = null
        executor.execute(params(tool, arguments, turnId)) { output = it }
        return checkNotNull(output)
    }

    private fun params(
        tool: String,
        arguments: String,
        turnId: String,
    ) = DynamicToolCallParams(
        threadId = "thread_1",
        turnId = turnId,
        callId = "call_${tool.take(32)}_${NEXT_CALL.incrementAndGet()}",
        namespace = UserProfileDynamicToolCatalog.NAMESPACE,
        tool = tool,
        argumentsJson = arguments,
    )

    private fun repository(storage: UserProfileStorage) = UserProfileRepository(
        storage = storage,
        clock = ProfileClock { 1 },
        nonces = ProfileNonceSource { "nonce_1234567890" },
    )

    private class MemoryStorage : UserProfileStorage {
        private var value = UserProfileDocument()

        override fun read(): UserProfileDocument = value

        override fun write(document: UserProfileDocument) {
            value = document
        }

        override fun clear() {
            value = UserProfileDocument()
        }
    }

    private class CountingStorage : UserProfileStorage {
        var accesses = 0
        var onWrite: () -> Unit = {}

        override fun read(): UserProfileDocument {
            accesses += 1
            return UserProfileDocument()
        }

        override fun write(document: UserProfileDocument) {
            accesses += 1
            onWrite()
        }

        override fun clear() {
            accesses += 1
        }
    }

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext() {
            tasks.removeFirst().run()
        }
    }

    private companion object {
        val NEXT_CALL = AtomicInteger()
    }
}
