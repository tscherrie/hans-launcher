package ai.hans.standard.profile

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutionGate
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolFunctionSpec
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import ai.hans.standard.codex.JsonContract
import ai.hans.standard.setup.SetupProfileTurnActionGuard
import java.util.concurrent.Executor
import org.json.JSONArray
import org.json.JSONObject

object UserProfileDynamicToolCatalog {
    const val NAMESPACE = "hans_profile"

    val namespace = DynamicToolNamespaceSpec(
        name = NAMESPACE,
        description =
            "Private local user profile and resumable Kennenlerngespraech. During an active " +
                "setup, read remains available but changes are accepted only at the personal " +
                "profile step.",
        tools = listOf(
            function("read", "Read confirmed profile and any resumable interview draft.", emptySchema()),
            function("begin_interview", "Begin or resume a profile interview without erasing its draft.", emptySchema()),
            function(
                "record_answer",
                "Persist one answer, then ask at most one natural follow-up question.",
                objectSchema(
                    properties = JSONObject()
                        .put("topic", stringSchema(80))
                        .put("question", stringSchema(1_000))
                        .put("answer", stringSchema(8_000)),
                    required = listOf("topic", "question", "answer"),
                ),
            ),
            function(
                "propose_summary",
                "Store a review summary. Read it to the user and ask for explicit confirmation.",
                objectSchema(
                    properties = JSONObject().put("summary", stringSchema(MAX_PROFILE_SUMMARY_CHARS)),
                    required = listOf("summary"),
                ),
            ),
            function(
                "confirm",
                "Confirm the proposed profile only after the user explicitly approved it.",
                objectSchema(
                    properties = JSONObject()
                        .put("confirmationNonce", stringSchema(128))
                        .put("explicitUserConfirmation", JSONObject().put("type", "boolean")),
                    required = listOf("confirmationNonce", "explicitUserConfirmation"),
                ),
            ),
            function(
                "delete",
                "Delete local profile and interview data only after explicit user confirmation.",
                objectSchema(
                    properties = JSONObject().put(
                        "explicitUserConfirmation",
                        JSONObject().put("type", "boolean"),
                    ),
                    required = listOf("explicitUserConfirmation"),
                ),
            ),
        ),
    )

    private fun function(name: String, description: String, schema: JSONObject) =
        DynamicToolFunctionSpec(name, description, schema.toString())

    private fun emptySchema() = objectSchema(JSONObject(), emptyList())

    private fun stringSchema(maxLength: Int) = JSONObject()
        .put("type", "string")
        .put("minLength", 1)
        .put("maxLength", maxLength)

    private fun objectSchema(properties: JSONObject, required: List<String>) = JSONObject()
        .put("type", "object")
        .put("properties", properties)
        .put("required", JSONArray(required))
        .put("additionalProperties", false)
}

/**
 * Keeps the reusable profile store independent from setup while allowing the production host to
 * enforce setup sequencing. A failed state lookup must deny mutation rather than silently turning
 * a setup-bound interview into a standalone one.
 */
fun interface UserProfileMutationPolicy {
    fun mayMutate(): Boolean

    companion object {
        val ALLOW_STANDALONE = UserProfileMutationPolicy { true }
    }
}

data class UserProfileSetupSequenceState(
    val started: Boolean,
    val complete: Boolean,
    val atPersonalProfileStep: Boolean,
)

class SetupAwareUserProfileMutationPolicy(
    private val setupState: () -> UserProfileSetupSequenceState,
) : UserProfileMutationPolicy {
    override fun mayMutate(): Boolean {
        val state = setupState()
        return !state.started || state.complete || state.atPersonalProfileStep
    }
}

class UserProfileDynamicToolExecutor(
    private val repository: UserProfileRepository,
    private val backgroundExecutor: Executor,
    private val turnActionGuard: SetupProfileTurnActionGuard = SetupProfileTurnActionGuard.PROCESS,
    private val mutationPolicy: UserProfileMutationPolicy =
        UserProfileMutationPolicy.ALLOW_STANDALONE,
    private val onMutation: () -> Unit = {},
) : DynamicToolExecutor {
    override val specs: List<DynamicToolNamespaceSpec> = listOf(UserProfileDynamicToolCatalog.namespace)

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
                .getOrElse { failureResult(call, it.safeProfileErrorCode()) }
            gate.complete(result)
        }
        if (!scheduled) {
            gate.complete(failureResult(call, "profile_executor_rejected"))
        }
        return gate
    }

    override fun failureResult(
        call: DynamicToolCallParams,
        code: String,
    ): DynamicToolExecutionResult = result(
        success = false,
        body = JSONObject()
            .put("status", "failed")
            .put("errorCode", code.takeIf { it.matches(SAFE_CODE) } ?: "profile_tool_failed"),
    )

    private fun executeSafely(
        call: DynamicToolCallParams,
        gate: DynamicToolExecutionGate,
    ): DynamicToolExecutionResult {
        require(call.namespace == UserProfileDynamicToolCatalog.NAMESPACE) {
            "unknown_profile_namespace"
        }
        val args = JsonContract.parseObject(call.argumentsJson, 64 * 1_024)
        if (call.tool in SIDE_EFFECT_TOOLS) {
            if (!gate.markExternalEffectStarted()) return cancelledResult()
            if (!runCatching(mutationPolicy::mayMutate).getOrDefault(false)) {
                return setupSequenceRequiredResult()
            }
            if (!gate.markExternalEffectStarted()) return cancelledResult()
            // Validate the complete request before reserving this turn's single setup/profile
            // mutation. A malformed model-generated call is not an external effect and must not
            // prevent a canonical correction in the same turn.
            validateSideEffectArguments(call.tool, args)
            if (!turnActionGuard.claim(call.threadId, call.turnId)) {
                return turnActionLimitResult()
            }
        }
        val execution = try {
            when (call.tool) {
                "read" -> {
                    requireKeys(args, emptySet())
                    if (!gate.markExternalEffectStarted()) return cancelledResult()
                    result(true, repository.read().toFullJsonProjection())
                }
                "begin_interview" -> {
                    requireKeys(args, emptySet())
                    if (!gate.markExternalEffectStarted()) return cancelledResult()
                    result(true, repository.beginInterview().toFullJsonProjection())
                }
                "record_answer" -> {
                    requireKeys(args, setOf("topic", "question", "answer"))
                    val answer = ProfileAnswer(
                        topic = JsonContract.requiredString(args, "topic", 80),
                        question = JsonContract.requiredString(args, "question", 1_000),
                        answer = JsonContract.requiredString(args, "answer", 8_000),
                    )
                    if (!gate.markExternalEffectStarted()) return cancelledResult()
                    val document = repository.recordAnswer(answer)
                    result(true, document.toMutationReceipt())
                }
                "propose_summary" -> {
                    requireKeys(args, setOf("summary"))
                    val summary = JsonContract.requiredString(
                        args,
                        "summary",
                        MAX_PROFILE_SUMMARY_CHARS,
                    )
                    if (!gate.markExternalEffectStarted()) return cancelledResult()
                    result(true, repository.proposeSummary(summary).toProposalReceipt())
                }
                "confirm" -> {
                    requireKeys(args, setOf("confirmationNonce", "explicitUserConfirmation"))
                    val nonce = JsonContract.requiredString(args, "confirmationNonce", 128)
                    val explicitUserConfirmation = args.getBoolean("explicitUserConfirmation")
                    if (!gate.markExternalEffectStarted()) return cancelledResult()
                    result(
                        true,
                        repository.confirm(
                            nonce = nonce,
                            explicitUserConfirmation = explicitUserConfirmation,
                        ).toMutationReceipt(),
                    )
                }
                "delete" -> {
                    requireKeys(args, setOf("explicitUserConfirmation"))
                    val explicitUserConfirmation = args.getBoolean("explicitUserConfirmation")
                    if (!gate.markExternalEffectStarted()) return cancelledResult()
                    repository.delete(explicitUserConfirmation)
                    result(true, JSONObject().put("status", "deleted"))
                }
                else -> failureResult(call, "unknown_profile_tool")
            }
        } catch (failure: Exception) {
            val code = failure.safeProfileErrorCode()
            if (call.tool in SIDE_EFFECT_TOOLS && code in PRE_EFFECT_PROFILE_ERRORS) {
                turnActionGuard.releaseUnused(call.threadId, call.turnId)
            }
            throw failure
        }
        if (execution.success && call.tool in SIDE_EFFECT_TOOLS) {
            // Cancellation can win before the repository boundary. Once the durable mutation is
            // committed, however, reconciliation belongs to the same atomic operation and must
            // finish best-effort even if the App Server callback is now suppressed by the gate.
            runCatching(onMutation)
        }
        return execution
    }

    private fun UserProfileDocument.toFullJsonProjection(): JSONObject = JSONObject()
        .put("status", "ok")
        .put("interviewActive", interviewActive)
        .put("hasConfirmedProfile", hasConfirmedProfile)
        .put("confirmedSummary", confirmedSummary ?: JSONObject.NULL)
        .put("proposedSummary", proposedSummary ?: JSONObject.NULL)
        .put("confirmationNonce", confirmationNonce ?: JSONObject.NULL)
        .put(
            "draftAnswers",
            JSONArray().also { array ->
                draftAnswers.forEach { answer ->
                    array.put(
                        JSONObject()
                            .put("topic", answer.topic)
                            .put("question", answer.question)
                            .put("answer", answer.answer),
                    )
                }
            },
        )

    private fun UserProfileDocument.toMutationReceipt(): JSONObject = JSONObject()
        .put("status", "ok")
        .put("interviewActive", interviewActive)
        .put("hasConfirmedProfile", hasConfirmedProfile)
        .put("draftAnswerCount", draftAnswers.size)

    private fun UserProfileDocument.toProposalReceipt(): JSONObject = toMutationReceipt()
        .put("summaryReadyForReview", !proposedSummary.isNullOrBlank())
        .put("confirmationNonce", confirmationNonce ?: JSONObject.NULL)

    private fun turnActionLimitResult(): DynamicToolExecutionResult = result(
        false,
        JSONObject()
            .put("status", "failed")
            .put("errorCode", "setup_profile_turn_action_limit")
            .put(
                "message",
                "Only one setup or profile change is allowed per agent turn. " +
                    "Reading state is still allowed; continue the next change in a new turn.",
            ),
    )

    private fun setupSequenceRequiredResult(): DynamicToolExecutionResult = result(
        false,
        JSONObject()
            .put("status", "failed")
            .put("errorCode", "profile_setup_step_required"),
    )

    private fun cancelledResult(): DynamicToolExecutionResult = result(
        false,
        JSONObject()
            .put("status", "failed")
            .put("errorCode", "dynamic_tool_cancelled"),
    )

    private fun requireKeys(args: JSONObject, expected: Set<String>) {
        val actual = buildSet {
            val keys = args.keys()
            while (keys.hasNext()) add(keys.next())
        }
        require(actual == expected) { "invalid_profile_arguments" }
    }

    private fun validateSideEffectArguments(tool: String, args: JSONObject) {
        when (tool) {
            "begin_interview" -> requireKeys(args, emptySet())
            "record_answer" -> {
                requireKeys(args, setOf("topic", "question", "answer"))
                JsonContract.requiredString(args, "topic", 80)
                JsonContract.requiredString(args, "question", 1_000)
                JsonContract.requiredString(args, "answer", 8_000)
            }
            "propose_summary" -> {
                requireKeys(args, setOf("summary"))
                JsonContract.requiredString(args, "summary", MAX_PROFILE_SUMMARY_CHARS)
            }
            "confirm" -> {
                requireKeys(args, setOf("confirmationNonce", "explicitUserConfirmation"))
                JsonContract.requiredString(args, "confirmationNonce", 128)
                JsonContract.requiredBoolean(args, "explicitUserConfirmation")
            }
            "delete" -> {
                requireKeys(args, setOf("explicitUserConfirmation"))
                JsonContract.requiredBoolean(args, "explicitUserConfirmation")
            }
        }
    }

    private fun result(success: Boolean, body: JSONObject) = DynamicToolExecutionResult(
        contentText = body.toString(),
        success = success,
    )

    private fun Throwable.safeProfileErrorCode(): String = message
        ?.takeIf { it.matches(SAFE_CODE) }
        ?: "profile_tool_failed"

    private companion object {
        val SAFE_CODE = Regex("[a-z0-9_.:-]{1,96}")
        val SIDE_EFFECT_TOOLS = setOf(
            "begin_interview",
            "record_answer",
            "propose_summary",
            "confirm",
            "delete",
        )
        val PRE_EFFECT_PROFILE_ERRORS = setOf(
            "invalid_profile_arguments",
            "profile_interview_not_active",
            "profile_draft_empty",
            "profile_answer_limit",
            "profile_confirmation_required",
            "profile_confirmation_stale",
            "profile_summary_missing",
        )
    }
}
