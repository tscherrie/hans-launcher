package ai.hans.standard.setup

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolFunctionSpec
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import ai.hans.standard.codex.FrameLimitException
import ai.hans.standard.codex.JsonContract
import ai.hans.standard.codex.MalformedEnvelopeException
import ai.hans.standard.phone.consent.HansPhoneActionPolicy
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

object HansSetupDynamicToolCatalog {
    const val NAMESPACE = "hans_setup"

    private val SETUP_CHOICE_TOKENS = listOf(
        "begin",
        "start",
        "yes",
        "ja",
        "hardware_toggle",
        "no_hardware_key",
        "enabled",
        "disabled",
        "enable",
        "allow",
        "use_current",
        "not_now",
        "denied",
        "skip",
        "no",
        "nein",
        "defer",
        "prepare_confirmation",
        "confirm",
    )

    val namespace = DynamicToolNamespaceSpec(
        name = NAMESPACE,
        description =
            "Turn-based device setup. Read state first; settings-opened is never verification.",
        tools = listOf(
            function("get_setup_state", "Read current ordered setup state and verified evidence.", emptySchema()),
            function(
                "record_choice",
                "Record one explicit user choice for the current step. Read setup state first " +
                    "and copy a token from its allowedChoices field; do not invent a token.",
                objectSchema(
                    JSONObject()
                        .put("step", enumSchema(HANS_SETUP_ORDER.map(::wireStep)))
                        .put("choice", enumSchema(SETUP_CHOICE_TOKENS))
                        .put(
                            "capability",
                            enumSchema(HansSetupOptionalCapability.entries.map(::wireCapability)),
                        )
                        .put("model", stringSchema(128))
                        .put("reasoningEffort", stringSchema(128))
                        .put("confirmationNonce", stringSchema(128))
                        .put(
                            "explicitUserConfirmation",
                            JSONObject().put("type", "boolean"),
                        ),
                    listOf("step"),
                ),
            ),
            function(
                "request_step_ui",
                "Ask the visible Hans Activity to open the current Android permission/settings UI.",
                stepSchema(),
            ),
            function(
                "begin_key_capture",
                "Begin foreground capture of one user-demonstrated dictation hardware key.",
                emptySchema(),
            ),
            function("read_key_capture", "Read the current hardware-key capture result.", emptySchema()),
            function(
                "begin_live_test",
                "Arm the current camera, notification, or Accessibility live test. Voice practice is optional normal use.",
                stepSchema(),
            ),
            function(
                "read_live_test",
                "Read a live test and apply only fresh service/session evidence.",
                stepSchema(),
            ),
            function(
                "verify_step",
                "Freshly probe the current permission, role, or mapping step.",
                stepSchema(),
            ),
            function(
                "accept_existing_setup",
                "After an explicit user request, accept the effective existing configuration, " +
                    "skip unproven checklist items, and close setup without inventing proof.",
                objectSchema(
                    JSONObject().put(
                        "explicitUserConfirmation",
                        JSONObject().put("type", "boolean"),
                    ),
                    listOf("explicitUserConfirmation"),
                ),
            ),
            function("advance", "Advance only after the current step is verified or skipped.", emptySchema()),
        ),
    )

    private fun function(name: String, description: String, schema: JSONObject) =
        DynamicToolFunctionSpec(name, description, schema.toString())

    private fun emptySchema() = objectSchema(JSONObject(), emptyList())

    private fun stepSchema() = objectSchema(
        JSONObject()
            .put("step", enumSchema(HANS_SETUP_ORDER.map(::wireStep)))
            .put(
                "capability",
                enumSchema(HansSetupOptionalCapability.entries.map(::wireCapability)),
            ),
        listOf("step"),
    )

    private fun stringSchema(maxLength: Int) = JSONObject()
        .put("type", "string")
        .put("minLength", 1)
        .put("maxLength", maxLength)

    private fun enumSchema(values: List<String>) = stringSchema(64)
        .put("enum", JSONArray(values))

    private fun objectSchema(properties: JSONObject, required: List<String>) = JSONObject()
        .put("type", "object")
        .put("properties", properties)
        .put("required", JSONArray(required))
        .put("additionalProperties", false)

}

class HansSetupDynamicToolExecutor(
    private val repository: HansSetupRepository,
    private val probe: HansSetupFreshProbe,
    private val uiRouter: SetupUiCommandRouter,
    private val backgroundExecutor: Executor,
    private val turnActionGuard: SetupProfileTurnActionGuard = SetupProfileTurnActionGuard.PROCESS,
    private val actionPolicy: HansPhoneActionPolicy = HansPhoneActionPolicy.CONFIRM_ACTIONS,
) : DynamicToolExecutor {
    override val specs: List<DynamicToolNamespaceSpec> = listOf(HansSetupDynamicToolCatalog.namespace)

    /** Event/turn-driven repair probe. Explicit skips and disabled choices are never reopened. */
    fun refreshFreshEvidence(): HansSetupDocument {
        val configuredChoice = probe.configuredInputChoice()?.let { choice ->
            if (choice == HansSetupInputChoice.HARDWARE_HOLD) HansSetupInputChoice.HARDWARE_TOGGLE else choice
        }
        val freshAccess = linkedMapOf<HansSetupStep, HansSetupProbeResult>()
        val verifiedAccess = linkedMapOf<HansSetupStep, String>()
        ADOPTABLE_ACCESS_STEPS.forEach { step ->
            val result = probe.probe(step)
            freshAccess[step] = result
            if (result.verified) verifiedAccess[step] = result.detailCode
        }
        val freshOptional = linkedMapOf<
            HansSetupOptionalCapability,
            HansSetupProbeResult,
        >()
        val verifiedOptional = linkedMapOf<HansSetupOptionalCapability, String>()
        HansSetupOptionalCapability.entries.forEach { capability ->
            val result = probe.probeOptionalCapability(capability)
            freshOptional[capability] = result
            if (result.verified) verifiedOptional[capability] = result.detailCode
        }
        var state = repository.reconcileExistingConfiguration(
            inputChoice = configuredChoice,
            cameraHoldEnabled = repository.read().cameraHoldEnabled == true,
            verifiedAccess = verifiedAccess,
            verifiedOptionalCapabilities = verifiedOptional,
        )
        REVOCABLE_STEPS.forEach { step ->
            if (
                state.record(step).status == HansSetupStepStatus.VERIFIED
            ) {
                val fresh = when (step) {
                    HansSetupStep.HARDWARE_MAPPING -> configuredChoice?.let {
                        HansSetupProbeResult(true, "hardware_mapping_present")
                    } ?: freshProbe(step, state)
                    else -> freshAccess[step] ?: freshProbe(step, state)
                }
                if (!fresh.verified && !fresh.transient) {
                    state = repository.revokeStep(step, fresh.detailCode, blocked = fresh.blocked)
                }
            }
        }
        HansSetupOptionalCapability.entries.forEach { capability ->
            val record = state.optionalCapabilityRecord(capability)
            if (
                capability != HansSetupOptionalCapability.QUICK_SETTINGS_TILE &&
                record.decision == HansSetupCapabilityDecision.ENABLE &&
                record.status == HansSetupStepStatus.VERIFIED
            ) {
                val fresh = freshOptional[capability] ?: probe.probeOptionalCapability(capability)
                if (!fresh.verified && !fresh.transient) {
                    state = repository.revokeOptionalCapability(capability)
                }
            }
        }
        return state
    }

    override fun execute(
        call: DynamicToolCallParams,
        completion: (DynamicToolExecutionResult) -> Unit,
    ) {
        val once = AtomicBoolean(false)
        fun finish(result: DynamicToolExecutionResult) {
            if (once.compareAndSet(false, true)) runCatching { completion(result) }
        }
        try {
            backgroundExecutor.execute {
                runCatching { executeSafely(call, ::finish) }
                    .onFailure { finish(failureResult(call, it.safeSetupErrorCode())) }
            }
        } catch (_: Exception) {
            finish(failureResult(call, "setup_executor_rejected"))
        }
    }

    override fun failureResult(
        call: DynamicToolCallParams,
        code: String,
    ): DynamicToolExecutionResult = result(
        false,
        JSONObject()
            .put("status", "failed")
            .put("errorCode", code.takeIf { it.matches(SAFE_CODE) } ?: "setup_tool_failed"),
    )

    private fun executeSafely(
        call: DynamicToolCallParams,
        completion: (DynamicToolExecutionResult) -> Unit,
    ) {
        require(call.namespace == HansSetupDynamicToolCatalog.NAMESPACE) {
            "unknown_setup_namespace"
        }
        val args = JsonContract.parseObject(call.argumentsJson, MAX_ARGUMENT_BYTES)
        val turnReserved = if (
            call.tool in SIDE_EFFECT_TOOLS &&
            !turnActionGuard.claim(call.threadId, call.turnId)
        ) {
            completion(turnActionLimitResult())
            return
        } else {
            call.tool in SIDE_EFFECT_TOOLS
        }
        try {
            when (call.tool) {
                "get_setup_state" -> {
                    requireKeys(args, emptySet())
                    completion(ok(refreshFreshEvidence()))
                }
                "record_choice" -> recordChoice(args, completion)
                "request_step_ui" -> requestStepUi(args, completion)
                "begin_key_capture" -> beginKeyCapture(args, completion)
                "read_key_capture" -> {
                    requireKeys(args, emptySet())
                    completion(ok(repository.read()))
                }
                "begin_live_test" -> beginLiveTest(args, completion)
                "read_live_test" -> readLiveTest(args, completion)
                "verify_step" -> verifyStep(args, completion)
                "accept_existing_setup" -> {
                    requireKeys(args, setOf("explicitUserConfirmation"))
                    require(args.get("explicitUserConfirmation") is Boolean) {
                        "invalid_setup_arguments"
                    }
                    require(args.getBoolean("explicitUserConfirmation")) {
                        "setup_existing_configuration_confirmation_required"
                    }
                    refreshFreshEvidence()
                    completion(ok(repository.acceptExistingConfiguration(true)))
                }
                "advance" -> {
                    requireKeys(args, emptySet())
                    completion(ok(repository.advance()))
                }
                else -> completion(failureResult(call, "unknown_setup_tool"))
            }
        } catch (failure: Exception) {
            if (
                turnReserved &&
                (
                    failure is MalformedEnvelopeException ||
                        failure is FrameLimitException ||
                        failure.message in PRE_EFFECT_VALIDATION_ERRORS
                )
            ) {
                turnActionGuard.releaseUnused(call.threadId, call.turnId)
                completion(validationFailureResult(failure.safeSetupErrorCode()))
                return
            }
            throw failure
        }
    }

    private fun recordChoice(
        args: JSONObject,
        completion: (DynamicToolExecutionResult) -> Unit,
    ) {
        val step = requiredStep(args)
        if (step == HansSetupStep.REVIEW) {
            val choice = normalizedChoice(
                step,
                JsonContract.requiredString(args, "choice", 64),
            )
            when (choice) {
                "defer" -> {
                    requireKeys(args, setOf("step", "choice"))
                    completion(ok(repository.recordSimpleChoice(step, choice)))
                }
                "prepare_confirmation" -> {
                    requireKeys(args, setOf("step", "choice"))
                    repository.beginReviewConfirmation()
                    completion(ok(repository.read()))
                }
                "confirm" -> {
                    requireKeys(
                        args,
                        setOf(
                            "step",
                            "choice",
                            "confirmationNonce",
                            "explicitUserConfirmation",
                        ),
                    )
                    require(args.get("explicitUserConfirmation") is Boolean) {
                        "invalid_setup_arguments"
                    }
                    completion(
                        ok(
                            repository.confirmReview(
                                confirmationNonce = JsonContract.requiredString(
                                    args,
                                    "confirmationNonce",
                                    128,
                                ),
                                explicitUserConfirmation = args.getBoolean(
                                    "explicitUserConfirmation",
                                ),
                            ),
                        ),
                    )
                }
                else -> error("invalid_setup_choice")
            }
            return
        }
        if (step == HansSetupStep.MODEL_REASONING) {
            if (args.has("choice")) {
                requireKeys(args, setOf("step", "choice"))
                completion(
                    ok(
                        repository.recordSimpleChoice(
                            step,
                            normalizedChoice(
                                step,
                                JsonContract.requiredString(args, "choice", 64),
                            ),
                        ),
                    ),
                )
                return
            }
            requireKeys(args, setOf("step", "model", "reasoningEffort"))
            val model = JsonContract.requiredString(args, "model", 128)
            val effort = JsonContract.requiredString(args, "reasoningEffort", 128)
            val token = repository.beginOperation(step)
            requestUi(
                SetupUiCommand.ApplyModelSelection(token, model, effort),
                completion,
            ) { accepted ->
                if (accepted == null) repository.markOperationAwaiting(token, "model_selection_ui_rejected")
                else repository.requestModelSelection(token, model, effort)
            }
            return
        }
        if (step == HansSetupStep.OPTIONAL_CAPABILITIES) {
            requireKeys(args, setOf("step", "capability", "choice"))
            completion(
                ok(
                    repository.recordOptionalCapabilityChoice(
                        capability = requiredCapability(args),
                        choice = normalizedChoice(
                            step,
                            JsonContract.requiredString(args, "choice", 64),
                        ),
                    ),
                ),
            )
            return
        }
        requireKeys(args, setOf("step", "choice"))
        val choice = normalizedChoice(
            step,
            JsonContract.requiredString(args, "choice", 64),
        )
        if (step == HansSetupStep.CAMERA_HOLD_CHOICE) {
            if (choice == "defer") {
                completion(ok(repository.recordSimpleChoice(step, choice)))
                return
            }
            val enabled = when (choice) {
                "enabled" -> true
                "disabled", "skip", "not_now", "denied" -> false
                else -> error("invalid_setup_choice")
            }
            val token = repository.beginOperation(step)
            requestUi(
                SetupUiCommand.ApplyCameraHoldChoice(token, enabled),
                completion,
            ) { accepted ->
                if (accepted == null) repository.markOperationAwaiting(token, "camera_choice_ui_rejected")
                else repository.applyCameraChoice(token, enabled)
            }
            return
        }
        completion(
            ok(
                repository.recordSimpleChoice(
                    step = step,
                    choice = choice,
                    hardwareHoldCompatible = probe.hardwareHoldCompatible(),
                ),
            ),
        )
    }

    private fun requestStepUi(
        args: JSONObject,
        completion: (DynamicToolExecutionResult) -> Unit,
    ) {
        val step = requiredStep(args)
        require(step in SETTINGS_STEPS) { "setup_step_has_no_settings_ui" }
        if (step == HansSetupStep.OPTIONAL_CAPABILITIES) {
            requireKeys(args, setOf("step", "capability"))
            val capability = requiredCapability(args)
            val token = repository.beginOptionalCapabilityOperation(capability)
            requestUi(
                SetupUiCommand.OpenSettings(token, capability),
                completion,
            ) { accepted ->
                when (accepted?.liveVerificationAccepted) {
                    true -> repository.acceptOptionalCapabilityUiProof(
                        token,
                        capability,
                        true,
                    )
                    false -> repository.acceptOptionalCapabilityUiProof(
                        token,
                        capability,
                        false,
                    )
                    null -> if (accepted == null) {
                        repository.markOptionalCapabilityAwaiting(token, capability)
                    } else {
                        repository.markOptionalCapabilitySettingsOpened(token, capability)
                    }
                }
            }
            return
        }
        requireKeys(args, setOf("step"))
        val token = repository.beginOperation(step)
        requestUi(SetupUiCommand.OpenSettings(token), completion) { accepted ->
            when (accepted?.liveVerificationAccepted) {
                true -> repository.applyFreshProbe(
                    token,
                    HansSetupProbeResult(true, "capability_ui_receipt_verified"),
                )
                false -> repository.recordSimpleChoice(step, "not_now")
                null -> if (accepted != null) {
                    repository.markSettingsOpened(token)
                } else {
                    repository.markOperationAwaiting(token, "settings_ui_not_opened")
                }
            }
        }
    }

    private fun beginKeyCapture(
        args: JSONObject,
        completion: (DynamicToolExecutionResult) -> Unit,
    ) {
        requireKeys(args, emptySet())
        val state = repository.read()
        require(state.currentStep == HansSetupStep.HARDWARE_MAPPING) { "setup_step_not_current" }
        val choice = state.inputChoice ?: error("setup_input_choice_missing")
        require(choice != HansSetupInputChoice.NO_HARDWARE_KEY) { "setup_hardware_key_skipped" }
        val token = repository.beginOperation(HansSetupStep.HARDWARE_MAPPING)
        // A historical hold choice is readable progress, not a gesture to re-enable.
        requestUi(SetupUiCommand.BeginKeyCapture(token, HansSetupInputChoice.HARDWARE_TOGGLE), completion) { accepted ->
            if (accepted == null) repository.markOperationAwaiting(token, "key_capture_ui_rejected")
            else repository.read()
        }
    }

    private fun beginLiveTest(
        args: JSONObject,
        completion: (DynamicToolExecutionResult) -> Unit,
    ) {
        requireKeys(args, setOf("step"))
        val step = requiredStep(args)
        val token = repository.beginLiveTest(step)
        requestUi(SetupUiCommand.ArmLiveTest(token), completion) { accepted ->
            when {
                accepted == null -> repository.markOperationAwaiting(token, "live_test_ui_rejected")
                step == HansSetupStep.VOICE_DICTATION_TEST -> repository.acceptVoicePreview(
                    token,
                    accepted.liveVerificationAccepted == true,
                )
                step == HansSetupStep.CAMERA_CAPTURE_TEST -> if (
                    accepted.liveVerificationAccepted == true
                ) {
                    repository.acceptCameraCapture(token)
                } else {
                    repository.markOperationAwaiting(token, "camera_capture_not_verified")
                }
                else -> repository.read()
            }
        }
    }

    private fun readLiveTest(
        args: JSONObject,
        completion: (DynamicToolExecutionResult) -> Unit,
    ) {
        requireKeys(args, setOf("step"))
        val step = requiredStep(args)
        val state = repository.read()
        require(state.currentStep == step || state.record(step).terminal) { "setup_step_not_current" }
        val record = state.record(step)
        if (
            record.terminal ||
            record.status != HansSetupStepStatus.VERIFYING ||
            step !in FRESH_LIVE_TEST_STEPS
        ) {
            completion(ok(state))
            return
        }
        val token = record.toToken(step)
        completion(ok(repository.applyFreshProbe(token, freshProbe(step, state, token.nonce))))
    }

    private fun verifyStep(
        args: JSONObject,
        completion: (DynamicToolExecutionResult) -> Unit,
    ) {
        val step = requiredStep(args)
        if (step == HansSetupStep.OPTIONAL_CAPABILITIES) {
            requireKeys(args, setOf("step", "capability"))
            val capability = requiredCapability(args)
            val token = repository.beginOptionalCapabilityOperation(capability)
            completion(
                ok(
                    repository.applyOptionalCapabilityProbe(
                        token,
                        capability,
                        probe.probeOptionalCapability(capability, token.nonce),
                    ),
                ),
            )
            return
        }
        requireKeys(args, setOf("step"))
        require(step in FRESH_PROBE_STEPS) { "setup_step_has_no_fresh_probe" }
        val token = repository.beginOperation(step)
        completion(ok(repository.applyFreshProbe(token, freshProbe(step, repository.read()))))
    }

    private fun freshProbe(
        step: HansSetupStep,
        state: HansSetupDocument,
        operationNonce: String? = null,
    ): HansSetupProbeResult = probe.probe(step, operationNonce)

    private fun requestUi(
        command: SetupUiCommand,
        completion: (DynamicToolExecutionResult) -> Unit,
        apply: (SetupUiCommandResult.Accepted?) -> HansSetupDocument,
    ) {
        val once = AtomicBoolean(false)
        uiRouter.request(command) { uiResult ->
            if (!once.compareAndSet(false, true)) return@request
            try {
                when (uiResult) {
                    is SetupUiCommandResult.Accepted -> completion(ok(apply(uiResult)))
                    SetupUiCommandResult.UserInteractionRequired -> {
                        apply(null)
                        completion(
                            result(
                                false,
                                JSONObject()
                                    .put("status", "user_interaction_required")
                                    .put("errorCode", "user_interaction_required"),
                            ),
                        )
                    }
                    is SetupUiCommandResult.Rejected -> {
                        apply(null)
                        completion(
                            result(
                                false,
                                JSONObject()
                                    .put("status", "failed")
                                    .put("errorCode", uiResult.code),
                            ),
                        )
                    }
                }
            } catch (failure: Exception) {
                completion(
                    result(
                        false,
                        JSONObject()
                            .put("status", "failed")
                            .put("errorCode", failure.safeSetupErrorCode()),
                    ),
                )
            }
        }
    }

    private fun ok(document: HansSetupDocument): DynamicToolExecutionResult =
        result(true, document.toJsonProjection())

    private fun HansSetupDocument.toJsonProjection(): JSONObject {
        val currentRecord = record(currentStep)
        return JSONObject()
            .put("status", "ok")
            .put("phoneActionPolicy", actionPolicy.wireValue)
            .put("started", started)
            .put("currentStep", wireStep(currentStep))
            .put("currentStepStatus", currentRecord.status.name.lowercase())
            .put("allowedChoices", JSONArray(allowedChoices(currentStep, currentRecord.status)))
            .put("complete", complete)
            .also { projection ->
                if (currentStep in setOf(HansSetupStep.INPUT_CHOICE, HansSetupStep.HARDWARE_MAPPING,
                        HansSetupStep.REVIEW, HansSetupStep.COMPLETE)) {
                    projection.put("voiceUsageInstructions", JSONArray(probe.voiceUsageInstructions()))
                }
                if (
                    currentStep in setOf(
                        HansSetupStep.INPUT_CHOICE,
                        HansSetupStep.HARDWARE_MAPPING,
                        HansSetupStep.HARDWARE_LIVE_TEST,
                    )
                ) {
                    projection.put(
                        "inputChoice",
                        inputChoice?.name?.lowercase() ?: JSONObject.NULL,
                    )
                }
                if (
                    currentStep == HansSetupStep.CAMERA_HOLD_CHOICE ||
                    currentStep == HansSetupStep.CAMERA_HOLD_LIVE_TEST
                ) {
                    projection.put(
                        "cameraHoldEnabled",
                        cameraHoldEnabled ?: JSONObject.NULL,
                    )
                }
                if (
                    currentStep == HansSetupStep.MODEL_REASONING ||
                    currentStep == HansSetupStep.REVIEW ||
                    currentStep == HansSetupStep.COMPLETE
                ) {
                    projection
                        .put("requestedModel", requestedModel ?: JSONObject.NULL)
                        .put(
                            "requestedReasoningEffort",
                            requestedReasoningEffort ?: JSONObject.NULL,
                        )
                        .put("effectiveModel", effectiveModel ?: JSONObject.NULL)
                        .put(
                            "effectiveReasoningEffort",
                            effectiveReasoningEffort ?: JSONObject.NULL,
                        )
                }
                if (
                    currentStep == HansSetupStep.PERSONAL_PROFILE ||
                    currentStep == HansSetupStep.REVIEW ||
                    currentStep == HansSetupStep.COMPLETE
                ) {
                    projection.put("profileConfirmed", profileConfirmed)
                }
                if (currentStep == HansSetupStep.OPTIONAL_CAPABILITIES) {
                    val capability = currentOptionalCapability
                    projection.put(
                        "currentCapability",
                        capability?.let(::wireCapability) ?: JSONObject.NULL,
                    )
                    if (capability != null) {
                        val capabilityRecord = optionalCapabilityRecord(capability)
                        projection
                            .put(
                                "capabilityDecision",
                                capabilityRecord.decision?.name?.lowercase() ?: JSONObject.NULL,
                            )
                            .put(
                                "capabilityStatus",
                                capabilityRecord.status.name.lowercase(),
                            )
                            .put(
                                "capabilityEffective",
                                capabilityRecord.effective ?: JSONObject.NULL,
                            )
                    }
                }
                if (
                    currentStep == HansSetupStep.REVIEW ||
                    currentStep == HansSetupStep.COMPLETE
                ) {
                    projection.put("technicalSummary", safeTechnicalSummary())

                }
                if (
                    currentStep == HansSetupStep.REVIEW &&
                    currentRecord.status == HansSetupStepStatus.VERIFYING
                ) {
                    projection.put(
                        "confirmationNonce",
                        currentRecord.operationNonce ?: JSONObject.NULL,
                    )
                }
            }
    }

    private fun HansSetupDocument.safeTechnicalSummary(): JSONObject = JSONObject()
        .put("phoneActionPolicy", actionPolicy.wireValue)
        .put(
            "input",
            JSONObject()
                .put("choice", inputChoice?.name?.lowercase() ?: "not_configured")
                .put(
                    "mappingProof",
                    safeProof(record(HansSetupStep.HARDWARE_MAPPING).status),
                )
                .put("voiceControl", "press_to_start_press_to_mute_or_unmute"),
        )
        .put(
            "camera",
            JSONObject()
                .put(
                    "captureProof",
                    safeProof(record(HansSetupStep.CAMERA_CAPTURE_TEST).status),
                ),
        )
        .put(
            "permissions",
            JSONArray().also { summary ->
                SUMMARY_PERMISSION_STEPS.forEach { (id, consent, access, liveTest) ->
                    summary.put(
                        JSONObject()
                            .put("id", id)
                            .put("choice", safeChoice(record(consent).status))
                            .put("accessProof", safeProof(record(access).status))
                            .also { item ->
                                if (liveTest != null) {
                                    item.put("liveProof", safeProof(record(liveTest).status))
                                }
                            },
                    )
                }
            },
        )
        .put(
            "optionalCapabilities",
            JSONArray().also { summary ->
                HansSetupOptionalCapability.entries.forEach { capability ->
                    val record = optionalCapabilityRecord(capability)
                    summary.put(
                        JSONObject()
                            .put("id", wireCapability(capability))
                            .put(
                                "choice",
                                record.decision?.name?.lowercase() ?: "not_answered",
                            )
                            .put("proof", safeProof(record.status))
                            .put("effective", record.effective ?: JSONObject.NULL),
                    )
                }
            },
        )
        .put(
            "speech",
            JSONObject()
                .put("access", "chatgpt_login")
                .put("practice", "optional_user_initiated_not_verified")
                .put("shortTask", "press_speak_press_to_mute_automatic_close")
                .put("phone", "tap_hans_longer_conversation_goodbye_ends"),
        )
        .put(
            "model",
            JSONObject()
                .put("requested", requestedModel ?: JSONObject.NULL)
                .put("requestedEffort", requestedReasoningEffort ?: JSONObject.NULL)
                .put("effective", effectiveModel ?: JSONObject.NULL)
                .put("effectiveEffort", effectiveReasoningEffort ?: JSONObject.NULL)
                .put("proof", safeProof(record(HansSetupStep.MODEL_REASONING).status)),
        )
        .put(
            "profile",
            JSONObject()
                .put("choice", if (profileConfirmed) "confirmed" else "not_now")
                .put("proof", safeProof(record(HansSetupStep.PERSONAL_PROFILE).status)),
        )

    private fun safeChoice(status: HansSetupStepStatus): String = when (status) {
        HansSetupStepStatus.VERIFIED -> "enabled"
        HansSetupStepStatus.SKIPPED -> "not_now"
        else -> "not_answered"
    }

    private fun safeProof(status: HansSetupStepStatus): String = when (status) {
        HansSetupStepStatus.VERIFIED -> "verified"
        HansSetupStepStatus.SKIPPED -> "not_enabled"
        HansSetupStepStatus.BLOCKED -> "unavailable"
        else -> "not_verified"
    }

    private fun normalizedChoice(step: HansSetupStep, rawChoice: String): String {
        val choice = rawChoice.lowercase()
        return when {
            step == HansSetupStep.INTRO &&
                (choice == "start" || choice in POSITIVE_CHOICE_ALIASES) -> "begin"
            step == HansSetupStep.INTRO && choice in NEGATIVE_CHOICE_ALIASES -> "defer"
            step == HansSetupStep.INPUT_CHOICE && choice in NEGATIVE_CHOICE_ALIASES ->
                "no_hardware_key"
            step == HansSetupStep.CAMERA_HOLD_CHOICE && choice in POSITIVE_CHOICE_ALIASES ->
                "enabled"
            step == HansSetupStep.CAMERA_HOLD_CHOICE && choice in NEGATIVE_CHOICE_ALIASES ->
                "disabled"
            step in CONSENT_STEPS && choice in POSITIVE_CHOICE_ALIASES -> "enable"
            step == HansSetupStep.OPTIONAL_CAPABILITIES &&
                choice in POSITIVE_CHOICE_ALIASES -> "enable"
            step == HansSetupStep.MODEL_REASONING && choice in POSITIVE_CHOICE_ALIASES ->
                "use_current"
            step == HansSetupStep.PERSONAL_PROFILE && choice in NEGATIVE_CHOICE_ALIASES -> "skip"
            step != HansSetupStep.REVIEW && choice in NEGATIVE_CHOICE_ALIASES -> "not_now"
            else -> choice
        }
    }

    private fun allowedChoices(
        step: HansSetupStep,
        status: HansSetupStepStatus,
    ): List<String> = if (step in HANS_SETUP_RETIRED_STEPS) emptyList() else when (step) {
        HansSetupStep.INTRO -> listOf("begin", "defer")
        HansSetupStep.INPUT_CHOICE ->
            listOf("hardware_toggle", "no_hardware_key", "defer")
        HansSetupStep.CAMERA_HOLD_CHOICE -> listOf("enabled", "disabled", "defer")
        in CONSENT_STEPS -> listOf("enable", "not_now", "defer")
        HansSetupStep.MODEL_REASONING -> listOf("use_current", "not_now", "defer")
        HansSetupStep.OPTIONAL_CAPABILITIES -> listOf("enable", "not_now", "defer")
        HansSetupStep.PERSONAL_PROFILE -> listOf("skip", "defer")
        HansSetupStep.REVIEW -> if (status == HansSetupStepStatus.VERIFYING) {
            listOf("confirm", "defer")
        } else {
            listOf("prepare_confirmation", "defer")
        }
        HansSetupStep.COMPLETE -> emptyList()
        else -> listOf("not_now", "defer")
    }

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

    private fun validationFailureResult(code: String): DynamicToolExecutionResult {
        val state = repository.read()
        return result(
            false,
            JSONObject()
                .put("status", "failed")
                .put("errorCode", code)
                .put("actionConsumed", false)
                .put("currentStep", wireStep(state.currentStep))
                .put(
                    "currentCapability",
                    if (state.currentStep == HansSetupStep.OPTIONAL_CAPABILITIES) {
                        state.currentOptionalCapability?.let(::wireCapability) ?: JSONObject.NULL
                    } else {
                        JSONObject.NULL
                    },
                )
                .put(
                    "allowedChoices",
                    JSONArray(
                        allowedChoices(
                            state.currentStep,
                            state.record(state.currentStep).status,
                        ),
                    ),
                ),
        )
    }

    private fun requiredStep(args: JSONObject): HansSetupStep = parseStep(
        JsonContract.requiredString(args, "step", 64),
    ).also { require(it !in HANS_SETUP_RETIRED_STEPS) { "setup_step_retired" } }

    private fun requiredCapability(args: JSONObject): HansSetupOptionalCapability =
        parseCapability(JsonContract.requiredString(args, "capability", 64))

    private fun HansSetupStepRecord.toToken(step: HansSetupStep): HansSetupOperationToken =
        HansSetupOperationToken(
            step = step,
            generation = generation,
            nonce = operationNonce ?: error("setup_live_test_not_started"),
        )

    private fun requireKeys(args: JSONObject, expected: Set<String>) {
        val actual = buildSet {
            val keys = args.keys()
            while (keys.hasNext()) add(keys.next())
        }
        require(actual == expected) { "invalid_setup_arguments" }
    }

    private fun result(success: Boolean, body: JSONObject) = DynamicToolExecutionResult(
        contentText = body.toString(),
        success = success,
    )

    private fun Throwable.safeSetupErrorCode(): String = message
        ?.takeIf { it.matches(SAFE_CODE) }
        ?: "setup_tool_failed"

    private data class PermissionSummaryStep(
        val id: String,
        val consent: HansSetupStep,
        val access: HansSetupStep,
        val liveTest: HansSetupStep?,
    )

    private companion object {
        const val MAX_ARGUMENT_BYTES = 16 * 1_024
        val SAFE_CODE = Regex("[a-z0-9_.:-]{1,96}")
        val SETTINGS_STEPS = setOf(
            HansSetupStep.APP_NOTIFICATIONS_ACCESS,
            HansSetupStep.NOTIFICATION_ACCESS,
            HansSetupStep.ACCESSIBILITY_ACCESS,
            HansSetupStep.HOME_ROLE,
            HansSetupStep.MICROPHONE_ACCESS,
            HansSetupStep.OPTIONAL_CAPABILITIES,
        )
        val FRESH_PROBE_STEPS = setOf(
            HansSetupStep.HARDWARE_MAPPING,
            HansSetupStep.APP_NOTIFICATIONS_ACCESS,
            HansSetupStep.NOTIFICATION_ACCESS,
            HansSetupStep.ACCESSIBILITY_ACCESS,
            HansSetupStep.HOME_ROLE,
            HansSetupStep.MICROPHONE_ACCESS,
        )
        val ADOPTABLE_ACCESS_STEPS = listOf(
            HansSetupStep.MICROPHONE_ACCESS,
            HansSetupStep.APP_NOTIFICATIONS_ACCESS,
            HansSetupStep.NOTIFICATION_ACCESS,
            HansSetupStep.ACCESSIBILITY_ACCESS,
            HansSetupStep.HOME_ROLE,
        )
        val FRESH_LIVE_TEST_STEPS = setOf(
            HansSetupStep.NOTIFICATION_LIVE_TEST,
            HansSetupStep.ACCESSIBILITY_LIVE_TEST,
        )
        val REVOCABLE_STEPS = listOf(
            HansSetupStep.HARDWARE_MAPPING,
            HansSetupStep.APP_NOTIFICATIONS_ACCESS,
            HansSetupStep.NOTIFICATION_ACCESS,
            HansSetupStep.ACCESSIBILITY_ACCESS,
            HansSetupStep.HOME_ROLE,
            HansSetupStep.MICROPHONE_ACCESS,
        )
        val SIDE_EFFECT_TOOLS = setOf(
            "record_choice",
            "request_step_ui",
            "begin_key_capture",
            "begin_live_test",
            "read_live_test",
            "verify_step",
            "accept_existing_setup",
            "advance",
        )
        val POSITIVE_CHOICE_ALIASES = setOf("yes", "ja")
        val NEGATIVE_CHOICE_ALIASES = setOf("no", "nein")
        val CONSENT_STEPS = setOf(
            HansSetupStep.MICROPHONE_CONSENT,
            HansSetupStep.APP_NOTIFICATIONS_CONSENT,
            HansSetupStep.NOTIFICATION_LISTENER_CONSENT,
            HansSetupStep.ACCESSIBILITY_CONSENT,
            HansSetupStep.HOME_ROLE_CONSENT,
        )
        val PRE_EFFECT_VALIDATION_ERRORS = setOf(
            "invalid_setup_arguments",
            "invalid_setup_choice",
            "invalid_setup_step",
            "setup_step_retired",
            "invalid_setup_capability",
            "setup_step_not_current",
            "setup_capability_not_current",
            "setup_step_has_no_settings_ui",
            "setup_input_choice_missing",
            "setup_hardware_key_skipped",
            "setup_step_has_no_fresh_probe",
            "setup_live_test_not_started",
            "setup_capability_choice_required",
            "setup_capability_not_enabled",
            "setup_choice_requires_ui",
            "setup_effective_selection_missing",
            "setup_live_test_requires_ui",
            "setup_not_dictation_live_test_step",
            "setup_not_live_test_step",
            "setup_review_confirmation_required",
            "setup_review_confirmation_stale",
            "setup_existing_configuration_confirmation_required",
            "setup_step_not_verified",
        )
        val SUMMARY_PERMISSION_STEPS = listOf(
            PermissionSummaryStep(
                "microphone",
                HansSetupStep.MICROPHONE_CONSENT,
                HansSetupStep.MICROPHONE_ACCESS,
                null,
            ),
            PermissionSummaryStep(
                "app_notifications",
                HansSetupStep.APP_NOTIFICATIONS_CONSENT,
                HansSetupStep.APP_NOTIFICATIONS_ACCESS,
                HansSetupStep.NOTIFICATION_LIVE_TEST,
            ),
            PermissionSummaryStep(
                "notification_listener",
                HansSetupStep.NOTIFICATION_LISTENER_CONSENT,
                HansSetupStep.NOTIFICATION_ACCESS,
                HansSetupStep.NOTIFICATION_LIVE_TEST,
            ),
            PermissionSummaryStep(
                "accessibility",
                HansSetupStep.ACCESSIBILITY_CONSENT,
                HansSetupStep.ACCESSIBILITY_ACCESS,
                HansSetupStep.ACCESSIBILITY_LIVE_TEST,
            ),
            PermissionSummaryStep(
                "home_role",
                HansSetupStep.HOME_ROLE_CONSENT,
                HansSetupStep.HOME_ROLE,
                null,
            ),
        )
    }
}

internal fun wireStep(step: HansSetupStep): String = step.name.lowercase()

internal fun wireCapability(capability: HansSetupOptionalCapability): String =
    capability.name.lowercase()

internal fun parseStep(value: String): HansSetupStep = runCatching {
    HansSetupStep.valueOf(value.uppercase())
}.getOrElse { error("invalid_setup_step") }

internal fun parseCapability(value: String): HansSetupOptionalCapability = runCatching {
    HansSetupOptionalCapability.valueOf(value.uppercase())
}.getOrElse { error("invalid_setup_capability") }
