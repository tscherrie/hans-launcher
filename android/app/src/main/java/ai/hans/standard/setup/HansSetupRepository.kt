package ai.hans.standard.setup

import ai.hans.standard.phone.keys.ActionKeyCommand

/**
 * Serialized reducer for setup state. Opening Android settings is intentionally
 * not a success state; only a fresh probe or a correlated live event verifies a step.
 */
class HansSetupRepository(
    private val storage: HansSetupStorage,
    private val clock: HansSetupClock = HansSetupClock(System::currentTimeMillis),
    private val nonces: HansSetupNonceSource = HansSetupNonceSource.UUIDS,
) {
    @Synchronized
    fun read(): HansSetupDocument = normalize(storage.read())

    @Synchronized
    fun startOrResume(): HansSetupDocument = mutate { current ->
        current.copy(started = true)
    }

    @Synchronized
    fun recordSimpleChoice(
        step: HansSetupStep,
        choice: String,
        hardwareHoldCompatible: Boolean = true,
    ): HansSetupDocument = mutate { current ->
        require(current.currentStep == step) {
            "setup_step_not_current"
        }
        if (choice == "defer") {
            return@mutate current.withStatus(
                step,
                HansSetupStepStatus.AWAITING_USER,
                "step_deferred",
            )
        }
        if (choice == "not_now" || choice == "denied") {
            require(step in DECLINABLE_STEPS) { "invalid_setup_choice" }
            return@mutate current.skipStepAndDependents(step)
        }
        when (step) {
            HansSetupStep.INTRO -> when (choice) {
                "begin" -> current.withStatus(step, HansSetupStepStatus.VERIFIED, "intro_accepted")
                "skip" -> current.withStatus(
                    step,
                    HansSetupStepStatus.AWAITING_USER,
                    "intro_deferred",
                )
                else -> error("invalid_setup_choice")
            }
            HansSetupStep.INPUT_CHOICE -> when (choice) {
                "hardware_toggle" -> current.copy(
                    inputChoice = HansSetupInputChoice.HARDWARE_TOGGLE,
                ).withStatus(step, HansSetupStepStatus.VERIFIED, "hardware_toggle_selected")
                "no_hardware_key", "skip" -> current.copy(
                    inputChoice = HansSetupInputChoice.NO_HARDWARE_KEY,
                ).withStatus(step, HansSetupStepStatus.VERIFIED, "hardware_key_skipped")
                    .withStatus(
                        HansSetupStep.HARDWARE_MAPPING,
                        HansSetupStepStatus.SKIPPED,
                        "hardware_key_not_selected",
                    )
                    .withStatus(
                        HansSetupStep.HARDWARE_LIVE_TEST,
                        HansSetupStepStatus.SKIPPED,
                        "hardware_key_not_selected",
                    )
                else -> error("invalid_setup_choice")
            }
            HansSetupStep.CAMERA_HOLD_CHOICE -> error("setup_choice_requires_ui")
            HansSetupStep.MICROPHONE_CONSENT,
            HansSetupStep.APP_NOTIFICATIONS_CONSENT,
            HansSetupStep.NOTIFICATION_LISTENER_CONSENT,
            HansSetupStep.ACCESSIBILITY_CONSENT,
            HansSetupStep.HOME_ROLE_CONSENT,
            HansSetupStep.SPEECH_CREDENTIAL_CONSENT,
            -> when (choice) {
                "enable", "allow" -> current.withStatus(
                    step,
                    HansSetupStepStatus.VERIFIED,
                    "capability_consent_given",
                )
                else -> error("invalid_setup_choice")
            }
            HansSetupStep.CAMERA_CAPTURE_TEST,
            HansSetupStep.HARDWARE_LIVE_TEST,
            HansSetupStep.CAMERA_HOLD_LIVE_TEST,
            HansSetupStep.NOTIFICATION_LIVE_TEST,
            HansSetupStep.ACCESSIBILITY_LIVE_TEST,
            HansSetupStep.VOICE_DICTATION_TEST,
            -> error("setup_live_test_requires_ui")
            HansSetupStep.MODEL_REASONING -> when (choice) {
                "use_current" -> {
                    require(
                        current.effectiveModel != null &&
                            current.effectiveReasoningEffort != null,
                    ) { "setup_effective_selection_missing" }
                    current.copy(
                        requestedModel = current.effectiveModel,
                        requestedReasoningEffort = current.effectiveReasoningEffort,
                    ).withStatus(
                        step,
                        HansSetupStepStatus.VERIFIED,
                        "current_app_server_selection_accepted",
                    )
                }
                else -> error("invalid_setup_choice")
            }
            HansSetupStep.OPTIONAL_CAPABILITIES -> error("setup_capability_choice_required")
            HansSetupStep.PERSONAL_PROFILE -> when (choice) {
                "skip" -> current.withStatus(
                    step,
                    HansSetupStepStatus.SKIPPED,
                    "profile_explicitly_skipped",
                )
                else -> error("invalid_setup_choice")
            }
            HansSetupStep.REVIEW -> error("setup_review_confirmation_required")
            else -> error("invalid_setup_choice")
        }
    }

    @Synchronized
    fun recordOptionalCapabilityChoice(
        capability: HansSetupOptionalCapability,
        choice: String,
    ): HansSetupDocument = mutate { current ->
        require(current.currentStep == HansSetupStep.OPTIONAL_CAPABILITIES) {
            "setup_step_not_current"
        }
        require(current.currentOptionalCapability == capability) {
            "setup_capability_not_current"
        }
        when (choice) {
            "enable", "allow" -> current.withOptionalCapability(
                capability,
                HansSetupOptionalCapabilityRecord(
                    decision = HansSetupCapabilityDecision.ENABLE,
                    status = HansSetupStepStatus.AWAITING_USER,
                    effective = null,
                ),
                "optional_capability_enabled_pending_proof",
            )
            "not_now", "denied", "skip" -> current.withOptionalCapability(
                capability,
                HansSetupOptionalCapabilityRecord(
                    decision = HansSetupCapabilityDecision.NOT_NOW,
                    status = HansSetupStepStatus.SKIPPED,
                    effective = false,
                ),
                "optional_capability_not_now",
            )
            "defer" -> current
            else -> error("invalid_setup_choice")
        }
    }

    @Synchronized
    fun beginReviewConfirmation(): HansSetupOperationToken {
        require(read().currentStep == HansSetupStep.REVIEW) { "setup_step_not_current" }
        return beginOperation(HansSetupStep.REVIEW)
    }

    @Synchronized
    fun confirmReview(
        confirmationNonce: String,
        explicitUserConfirmation: Boolean,
    ): HansSetupDocument = mutate { current ->
        require(explicitUserConfirmation) { "setup_review_confirmation_required" }
        require(current.currentStep == HansSetupStep.REVIEW) { "setup_step_not_current" }
        val record = current.record(HansSetupStep.REVIEW)
        require(
            record.status == HansSetupStepStatus.VERIFYING &&
                record.operationNonce == confirmationNonce,
        ) { "setup_review_confirmation_stale" }
        current.withStatus(
            HansSetupStep.REVIEW,
            HansSetupStepStatus.VERIFIED,
            "review_confirmed",
        )
    }

    @Synchronized
    fun beginOperation(step: HansSetupStep): HansSetupOperationToken {
        var token: HansSetupOperationToken? = null
        mutate { current ->
            require(current.currentStep == step) { "setup_step_not_current" }
            val previous = current.record(step)
            val generation = previous.generation + 1
            token = HansSetupOperationToken(step, generation, nonces.next())
            current.withRecord(
                step,
                previous.copy(
                    status = HansSetupStepStatus.VERIFYING,
                    generation = generation,
                    operationNonce = checkNotNull(token).nonce,
                    detailCode = if (step == HansSetupStep.HARDWARE_MAPPING) {
                        KEY_CAPTURE_OPERATION_REQUESTED_CODE
                    } else {
                        "operation_requested"
                    },
                    requestedAtMillis = clock.nowMillis(),
                    verifiedAtMillis = null,
                    liveStartObserved = false,
                    auxiliaryEvidenceObserved = false,
                ),
            )
        }
        return checkNotNull(token)
    }

    @Synchronized
    fun beginOptionalCapabilityOperation(
        capability: HansSetupOptionalCapability,
    ): HansSetupOperationToken {
        var token: HansSetupOperationToken? = null
        mutate { current ->
            require(current.currentStep == HansSetupStep.OPTIONAL_CAPABILITIES) {
                "setup_step_not_current"
            }
            require(current.currentOptionalCapability == capability) {
                "setup_capability_not_current"
            }
            val capabilityRecord = current.optionalCapabilityRecord(capability)
            require(capabilityRecord.decision == HansSetupCapabilityDecision.ENABLE) {
                "setup_capability_not_enabled"
            }
            val previous = current.record(HansSetupStep.OPTIONAL_CAPABILITIES)
            val generation = previous.generation + 1
            token = HansSetupOperationToken(
                HansSetupStep.OPTIONAL_CAPABILITIES,
                generation,
                nonces.next(),
            )
            current.copy(
                optionalCapabilities = current.optionalCapabilities +
                    (
                        capability to capabilityRecord.copy(
                            status = HansSetupStepStatus.VERIFYING,
                            effective = null,
                        )
                        ),
            ).withRecord(
                HansSetupStep.OPTIONAL_CAPABILITIES,
                previous.copy(
                    status = HansSetupStepStatus.VERIFYING,
                    generation = generation,
                    operationNonce = checkNotNull(token).nonce,
                    detailCode = "optional_capability_operation_requested",
                    requestedAtMillis = clock.nowMillis(),
                    verifiedAtMillis = null,
                    liveStartObserved = false,
                    auxiliaryEvidenceObserved = false,
                ),
            )
        }
        return checkNotNull(token)
    }

    @Synchronized
    fun markSettingsOpened(token: HansSetupOperationToken): HansSetupDocument =
        updateToken(token) { record ->
            record.copy(
                status = HansSetupStepStatus.SETTINGS_OPENED,
                detailCode = "settings_opened_unverified",
            )
        }

    /**
     * Accepts the Restricted Settings recovery only after the Activity freshly reread the
     * effective special-access state. Opening app details or a Settings page is not proof.
     */
    @Synchronized
    fun acceptRestrictedSettingsRecovery(
        token: HansSetupOperationToken,
    ): HansSetupDocument {
        require(token.step in RESTRICTED_SETTINGS_ACCESS_STEPS) {
            "restricted_settings_step_unsupported"
        }
        return applyFreshProbe(
            token,
            HansSetupProbeResult(
                verified = true,
                detailCode = "restricted_settings_recovery_verified",
            ),
        )
    }

    /** Exposes a bounded, truthful recovery outcome without marking the access effective. */
    @Synchronized
    fun markRestrictedSettingsManualRequired(
        token: HansSetupOperationToken,
    ): HansSetupDocument {
        require(token.step in RESTRICTED_SETTINGS_ACCESS_STEPS) {
            "restricted_settings_step_unsupported"
        }
        return updateToken(token) { record ->
            record.copy(
                status = HansSetupStepStatus.AWAITING_USER,
                operationNonce = null,
                detailCode = "restricted_settings_manual_required",
                verifiedAtMillis = null,
            )
        }
    }

    /** Records the visible user's explicit "Not now" choice; it is never inferred from Back. */
    @Synchronized
    fun recordRestrictedSettingsNotNow(
        step: HansSetupStep,
    ): HansSetupDocument = mutate { current ->
        require(step in RESTRICTED_SETTINGS_ACCESS_STEPS) {
            "restricted_settings_step_unsupported"
        }
        require(current.currentStep == step) { "setup_step_not_current" }
        current.skipStepAndDependents(step)
    }

    @Synchronized
    fun markOptionalCapabilitySettingsOpened(
        token: HansSetupOperationToken,
        capability: HansSetupOptionalCapability,
    ): HansSetupDocument = mutate { current ->
        current.requireCurrentOptionalToken(token, capability)
        current.copy(
            optionalCapabilities = current.optionalCapabilities +
                (
                    capability to current.optionalCapabilityRecord(capability).copy(
                        status = HansSetupStepStatus.SETTINGS_OPENED,
                    )
                    ),
        ).withRecord(
            HansSetupStep.OPTIONAL_CAPABILITIES,
            current.record(HansSetupStep.OPTIONAL_CAPABILITIES).copy(
                status = HansSetupStepStatus.SETTINGS_OPENED,
                detailCode = "optional_capability_settings_opened_unverified",
            ),
        )
    }

    @Synchronized
    fun markOptionalCapabilityAwaiting(
        token: HansSetupOperationToken,
        capability: HansSetupOptionalCapability,
    ): HansSetupDocument = mutate { current ->
        current.requireCurrentOptionalToken(token, capability)
        current.withOptionalCapability(
            capability,
            current.optionalCapabilityRecord(capability).copy(
                status = HansSetupStepStatus.AWAITING_USER,
                effective = false,
            ),
            "optional_capability_awaiting_user",
        )
    }

    @Synchronized
    fun applyOptionalCapabilityProbe(
        token: HansSetupOperationToken,
        capability: HansSetupOptionalCapability,
        result: HansSetupProbeResult,
    ): HansSetupDocument = mutate { current ->
        current.requireCurrentOptionalToken(token, capability)
        val record = current.optionalCapabilityRecord(capability)
        if (result.verified) {
            current.withOptionalCapability(
                capability,
                record.copy(
                    status = HansSetupStepStatus.VERIFIED,
                    effective = true,
                ),
                "optional_capability_effective",
            )
        } else {
            current.withOptionalCapability(
                capability,
                record.copy(
                    status = when {
                        result.blocked -> HansSetupStepStatus.BLOCKED
                        result.transient -> HansSetupStepStatus.VERIFYING
                        else -> HansSetupStepStatus.AWAITING_USER
                    },
                    effective = false,
                ),
                "optional_capability_not_effective",
                preserveOperation = result.transient,
            )
        }
    }

    @Synchronized
    fun acceptOptionalCapabilityUiProof(
        token: HansSetupOperationToken,
        capability: HansSetupOptionalCapability,
        effective: Boolean,
    ): HansSetupDocument = if (effective) {
        applyOptionalCapabilityProbe(
            token,
            capability,
            HansSetupProbeResult(true, "optional_capability_ui_receipt_verified"),
        )
    } else {
        mutate { current ->
            current.requireCurrentOptionalToken(token, capability)
            current.withOptionalCapability(
                capability,
                HansSetupOptionalCapabilityRecord(
                    decision = HansSetupCapabilityDecision.NOT_NOW,
                    status = HansSetupStepStatus.SKIPPED,
                    effective = false,
                ),
                "optional_capability_ui_declined",
            )
        }
    }

    @Synchronized
    fun revokeOptionalCapability(
        capability: HansSetupOptionalCapability,
    ): HansSetupDocument = mutate { current ->
        val record = current.optionalCapabilityRecord(capability)
        if (
            record.decision != HansSetupCapabilityDecision.ENABLE ||
            record.status != HansSetupStepStatus.VERIFIED
        ) {
            current
        } else {
            current.withOptionalCapability(
                capability,
                record.copy(
                    status = HansSetupStepStatus.AWAITING_USER,
                    effective = false,
                ),
                "optional_capability_revoked",
            )
        }
    }

    @Synchronized
    fun markOperationAwaiting(
        token: HansSetupOperationToken,
        detailCode: String,
    ): HansSetupDocument = updateToken(token) { record ->
        record.copy(
            status = HansSetupStepStatus.AWAITING_USER,
            operationNonce = null,
            detailCode = detailCode,
            liveStartObserved = false,
            auxiliaryEvidenceObserved = false,
        )
    }

    @Synchronized
    fun abandonKeyCaptureForActivityRecreation(
        token: HansSetupOperationToken,
    ): HansSetupDocument {
        require(token.step == HansSetupStep.HARDWARE_MAPPING)
        return markOperationAwaiting(token, "key_capture_activity_recreated")
    }

    @Synchronized
    fun abandonDictationLiveTestForActivityRecreation(
        token: HansSetupOperationToken,
    ): HansSetupDocument {
        require(token.step in DICTATION_LIVE_TEST_STEPS) { "setup_not_dictation_live_test_step" }
        val current = read()
        // A recreated Activity may deliver an old callback after setup migrated forward.
        // It must neither reopen retired practice nor reset a newer operation.
        if (token.step in HANS_SETUP_RETIRED_STEPS || !current.matches(token)) return current
        return markOperationAwaiting(token, "dictation_live_test_activity_recreated")
    }

    @Synchronized
    fun applyCameraChoice(
        token: HansSetupOperationToken,
        enabled: Boolean,
    ): HansSetupDocument {
        require(token.step == HansSetupStep.CAMERA_HOLD_CHOICE)
        return mutate { current ->
            current.requireCurrentToken(token)
            var next = current.copy(cameraHoldEnabled = enabled)
                .withStatus(
                    token.step,
                    HansSetupStepStatus.VERIFIED,
                    if (enabled) "camera_hold_enabled" else "camera_hold_disabled",
                )
            if (!enabled) {
                next = next.withStatus(
                    HansSetupStep.CAMERA_HOLD_LIVE_TEST,
                    HansSetupStepStatus.SKIPPED,
                    "camera_hold_disabled",
                )
            }
            next
        }
    }

    @Synchronized
    fun requestModelSelection(
        token: HansSetupOperationToken,
        model: String,
        reasoningEffort: String,
    ): HansSetupDocument {
        require(token.step == HansSetupStep.MODEL_REASONING)
        requireSetupSelection(model, "setup_model")
        requireSetupSelection(reasoningEffort, "setup_reasoning_effort")
        return mutate { current ->
            current.requireCurrentToken(token)
            current.copy(
                requestedModel = model,
                requestedReasoningEffort = reasoningEffort,
            ).withRecord(
                token.step,
                current.record(token.step).copy(
                    status = HansSetupStepStatus.VERIFYING,
                    detailCode = "awaiting_app_server_selection_proof",
                ),
            )
        }
    }

    @Synchronized
    fun observeEffectiveSelection(model: String, reasoningEffort: String): HansSetupDocument =
        mutate { current ->
            val observed = current.copy(
                effectiveModel = model,
                effectiveReasoningEffort = reasoningEffort,
            )
            val modelStep = current.record(HansSetupStep.MODEL_REASONING)
            // A completed onboarding verifies selection support, not a permanent model lock.
            // Repair only the precise old invalidation; explicit new operations stay pending.
            val completedSelectionObservation =
                current.record(HansSetupStep.COMPLETE).status == HansSetupStepStatus.VERIFIED &&
                    (modelStep.status == HansSetupStepStatus.VERIFIED ||
                        (modelStep.status == HansSetupStepStatus.AWAITING_USER &&
                            modelStep.detailCode == "effective_selection_changed"))
            if (
                completedSelectionObservation ||
                (observed.requestedModel == model &&
                    observed.requestedReasoningEffort == reasoningEffort)
            ) {
                val selected = if (completedSelectionObservation) {
                    observed.copy(requestedModel = model, requestedReasoningEffort = reasoningEffort)
                } else {
                    observed
                }
                selected.withObservedVerification(
                    HansSetupStep.MODEL_REASONING,
                    "app_server_selection_confirmed",
                    observationChanged = selected != current,
                )
            } else if (
                current.record(HansSetupStep.MODEL_REASONING).status ==
                HansSetupStepStatus.VERIFIED
            ) {
                observed.withStatus(
                    HansSetupStep.MODEL_REASONING,
                    HansSetupStepStatus.AWAITING_USER,
                    "effective_selection_changed",
                )
            } else {
                observed
            }
        }

    @Synchronized
    fun observeProfileConfirmed(confirmed: Boolean): HansSetupDocument = mutate { current ->
        var next = current.copy(profileConfirmed = confirmed)
        if (confirmed) {
            next = next.withObservedVerification(
                HansSetupStep.PERSONAL_PROFILE,
                "profile_explicitly_confirmed",
                observationChanged = next != current,
            )
        } else if (
            current.record(HansSetupStep.PERSONAL_PROFILE).status ==
            HansSetupStepStatus.VERIFIED
        ) {
            next = next.withStatus(
                HansSetupStep.PERSONAL_PROFILE,
                HansSetupStepStatus.AWAITING_USER,
                "profile_confirmation_missing",
            )
        }
        next
    }

    /** Retired API-key setup observations cannot reopen or modify the key-free voice setup. */
    @Suppress("UNUSED_PARAMETER")
    @Synchronized
    fun observeSpeechCredentialAvailability(available: Boolean): HansSetupDocument = read()

    /**
     * Adopts settings and platform grants that were configured outside the guided conversation.
     * Every entry in [verifiedAccess] comes from a fresh effective-state probe. Missing entries
     * are unknown, not refusals, and are therefore left untouched.
     */
    @Synchronized
    fun reconcileExistingConfiguration(
        inputChoice: HansSetupInputChoice?,
        cameraHoldEnabled: Boolean,
        verifiedAccess: Map<HansSetupStep, String>,
        verifiedOptionalCapabilities: Map<HansSetupOptionalCapability, String>,
    ): HansSetupDocument = mutate { current ->
        require(verifiedAccess.keys.all { it in ACCESS_CONSENT_PAIRS }) {
            "setup_existing_access_step_unsupported"
        }
        verifiedAccess.values.forEach(::requireSetupCode)
        verifiedOptionalCapabilities.values.forEach(::requireSetupCode)
        var next = current
        if (inputChoice != null) {
            next = next.copy(inputChoice = inputChoice)
                .withStatus(
                    HansSetupStep.INPUT_CHOICE,
                    HansSetupStepStatus.VERIFIED,
                    when (inputChoice) {
                        HansSetupInputChoice.HARDWARE_TOGGLE ->
                            "existing_hardware_toggle_observed"
                        HansSetupInputChoice.HARDWARE_HOLD ->
                            "existing_hardware_hold_observed"
                        HansSetupInputChoice.NO_HARDWARE_KEY ->
                            error("setup_existing_hardware_choice_invalid")
                    },
                )
                .withStatus(
                    HansSetupStep.HARDWARE_MAPPING,
                    HansSetupStepStatus.VERIFIED,
                    "hardware_mapping_present",
                )
        }
        // cameraHoldEnabled is legacy persisted configuration, not a current setup choice.
        // Never enable, disable or reinterpret it during repair/resume.
        verifiedAccess.forEach { (access, detailCode) ->
            if (access in HANS_SETUP_RETIRED_STEPS) return@forEach
            val consent = checkNotNull(ACCESS_CONSENT_PAIRS[access])
            if (
                next.record(consent).status == HansSetupStepStatus.SKIPPED ||
                next.record(access).status == HansSetupStepStatus.SKIPPED
            ) {
                return@forEach
            }
            next = next.withStatus(
                consent,
                HansSetupStepStatus.VERIFIED,
                "existing_access_consent_observed",
            ).withStatus(
                access,
                HansSetupStepStatus.VERIFIED,
                detailCode,
            )
        }
        verifiedOptionalCapabilities.forEach { (capability, _) ->
            val existing = next.optionalCapabilityRecord(capability)
            if (existing.status != HansSetupStepStatus.SKIPPED) {
                next = next.withOptionalCapability(
                    capability,
                    HansSetupOptionalCapabilityRecord(
                        decision = HansSetupCapabilityDecision.ENABLE,
                        status = HansSetupStepStatus.VERIFIED,
                        effective = true,
                    ),
                    "existing_optional_capability_observed",
                )
            }
        }
        next
    }

    /**
     * Closes the conversational checklist only after an explicit user instruction. Freshly
     * proven configuration remains VERIFIED. Everything else is truthfully SKIPPED; no live
     * test, permission, or capability is manufactured by this operation.
     */
    @Synchronized
    fun acceptExistingConfiguration(
        explicitUserConfirmation: Boolean,
    ): HansSetupDocument = mutate { current ->
        require(explicitUserConfirmation) {
            "setup_existing_configuration_confirmation_required"
        }
        var next = current.copy(started = true)
        if (next.effectiveModel != null && next.effectiveReasoningEffort != null) {
            next = next.copy(
                requestedModel = next.effectiveModel,
                requestedReasoningEffort = next.effectiveReasoningEffort,
            ).withStatus(
                HansSetupStep.MODEL_REASONING,
                HansSetupStepStatus.VERIFIED,
                "current_app_server_selection_accepted",
            )
        }
        HansSetupOptionalCapability.entries.forEach { capability ->
            val existing = next.optionalCapabilityRecord(capability)
            if (!existing.terminal) {
                next = next.copy(
                    optionalCapabilities = next.optionalCapabilities +
                        (capability to HansSetupOptionalCapabilityRecord(
                            decision = HansSetupCapabilityDecision.NOT_NOW,
                            status = HansSetupStepStatus.SKIPPED,
                            effective = false,
                        )),
                )
            }
        }
        HANS_SETUP_ORDER.filterNot { it == HansSetupStep.COMPLETE }.forEach { step ->
            if (next.record(step).terminal) return@forEach
            next = next.withStatus(
                step,
                if (step == HansSetupStep.REVIEW) {
                    HansSetupStepStatus.VERIFIED
                } else {
                    HansSetupStepStatus.SKIPPED
                },
                if (step == HansSetupStep.REVIEW) {
                    "existing_configuration_explicitly_accepted"
                } else {
                    "existing_configuration_not_proven"
                },
            )
        }
        next
    }

    @Synchronized
    fun applyFreshProbe(
        token: HansSetupOperationToken,
        result: HansSetupProbeResult,
    ): HansSetupDocument = updateToken(token) { record ->
        if (result.verified) {
            record.copy(
                status = HansSetupStepStatus.VERIFIED,
                operationNonce = null,
                detailCode = result.detailCode,
                verifiedAtMillis = clock.nowMillis(),
            )
        } else {
            record.copy(
                status = when {
                    result.blocked -> HansSetupStepStatus.BLOCKED
                    result.transient -> HansSetupStepStatus.VERIFYING
                    else -> HansSetupStepStatus.AWAITING_USER
                },
                operationNonce = if (result.transient) record.operationNonce else null,
                detailCode = result.detailCode,
                verifiedAtMillis = null,
            )
        }
    }

    @Synchronized
    fun beginLiveTest(step: HansSetupStep): HansSetupOperationToken {
        require(step in LIVE_TEST_STEPS) { "setup_not_live_test_step" }
        return beginOperation(step)
    }

    @Synchronized
    fun acceptHardwareCommand(
        token: HansSetupOperationToken,
        command: ActionKeyCommand,
    ): HansSetupDocument = acceptHardwareCommand(token, command, accessibilityReceipt = false)

    @Synchronized
    fun acceptAccessibilityHardwareCommand(
        token: HansSetupOperationToken,
        command: ActionKeyCommand,
    ): HansSetupDocument = acceptHardwareCommand(token, command, accessibilityReceipt = true)

    private fun acceptHardwareCommand(
        token: HansSetupOperationToken,
        command: ActionKeyCommand,
        accessibilityReceipt: Boolean,
    ): HansSetupDocument = mutate { current ->
        val step = HansSetupStep.HARDWARE_LIVE_TEST
        require(token.step == step)
        val record = current.record(step)
        if (!current.matches(token) || record.status != HansSetupStepStatus.VERIFYING) {
            return@mutate current
        }
        when (current.inputChoice) {
            HansSetupInputChoice.HARDWARE_TOGGLE ->
                if (command != ActionKeyCommand.ToggleDictation) {
                    current
                } else if (record.liveStartObserved) {
                    current.withRecord(
                        step,
                        record.copy(
                            detailCode = "hardware_toggle_stop_command_observed",
                            auxiliaryEvidenceObserved =
                                record.auxiliaryEvidenceObserved || accessibilityReceipt,
                        ),
                    )
                } else {
                    current.withRecord(
                        step,
                        record.copy(
                            detailCode = "hardware_toggle_start_command_observed",
                            auxiliaryEvidenceObserved =
                                record.auxiliaryEvidenceObserved || accessibilityReceipt,
                        ),
                    )
                }
            HansSetupInputChoice.HARDWARE_HOLD -> when (command) {
                ActionKeyCommand.StartDictation -> current.withRecord(
                    step,
                    record.copy(
                        detailCode = "hardware_hold_start_command_observed",
                        auxiliaryEvidenceObserved =
                            record.auxiliaryEvidenceObserved || accessibilityReceipt,
                    ),
                )
                ActionKeyCommand.StopDictation -> if (record.liveStartObserved) {
                    current.withRecord(
                        step,
                        record.copy(
                            detailCode = "hardware_hold_stop_command_observed",
                            auxiliaryEvidenceObserved =
                                record.auxiliaryEvidenceObserved || accessibilityReceipt,
                        ),
                    )
                } else {
                    current
                }
                else -> current
            }
            HansSetupInputChoice.NO_HARDWARE_KEY,
            null,
            -> current
        }
    }

    @Synchronized
    fun acceptCameraDictation(
        token: HansSetupOperationToken,
        started: Boolean,
    ): HansSetupDocument = mutate { current ->
        val step = HansSetupStep.CAMERA_HOLD_LIVE_TEST
        require(token.step == step)
        val record = current.record(step)
        if (!current.matches(token) || record.status != HansSetupStepStatus.VERIFYING) {
            return@mutate current
        }
        if (started) {
            current.withRecord(
                step,
                record.copy(
                    detailCode = "camera_hold_start_command_observed",
                ),
            )
        } else if (record.liveStartObserved) {
            current.withRecord(
                step,
                record.copy(detailCode = "camera_hold_stop_command_observed"),
            )
        } else {
            current
        }
    }

    @Synchronized
    fun acceptCameraCapture(token: HansSetupOperationToken): HansSetupDocument = mutate { current ->
        val step = token.step
        require(
            step == HansSetupStep.CAMERA_CAPTURE_TEST ||
                step == HansSetupStep.CAMERA_HOLD_LIVE_TEST,
        )
        val record = current.record(step)
        if (!current.matches(token) || record.status != HansSetupStepStatus.VERIFYING) {
            return@mutate current
        }
        if (step == HansSetupStep.CAMERA_CAPTURE_TEST) {
            current.withVerifiedLiveTest(step, "camera_capture_live_verified")
        } else if (record.detailCode == "camera_dictation_sent_waiting_capture") {
            current.withVerifiedLiveTest(step, "camera_tap_and_hold_live_verified")
        } else {
            current.withRecord(
                step,
                record.copy(
                    auxiliaryEvidenceObserved = true,
                    detailCode = "camera_capture_receipt_observed",
                ),
            )
        }
    }

    @Synchronized
    fun acceptKeyCapture(
        token: HansSetupOperationToken,
        success: Boolean,
        blocked: Boolean = false,
        detailCode: String,
    ): HansSetupDocument = mutate { current ->
        val step = HansSetupStep.HARDWARE_MAPPING
        require(token.step == step)
        val record = current.record(step)
        if (!current.matches(token) || record.operationNonce == null) return@mutate current
        when {
            success -> current.withStatus(step, HansSetupStepStatus.VERIFIED, detailCode)
            blocked -> current.withStatus(step, HansSetupStepStatus.BLOCKED, detailCode)
            else -> current.withStatus(step, HansSetupStepStatus.AWAITING_USER, detailCode)
        }
    }

    @Synchronized
    fun acceptVoicePreview(token: HansSetupOperationToken, accepted: Boolean): HansSetupDocument =
        updateToken(token) { record ->
            if (accepted) {
                record.copy(
                    status = HansSetupStepStatus.VERIFYING,
                    detailCode = "voice_preview_observed_awaiting_dictation",
                    liveStartObserved = true,
                )
            } else {
                record.copy(
                    status = HansSetupStepStatus.AWAITING_USER,
                    operationNonce = null,
                    detailCode = "voice_preview_unavailable",
                )
            }
        }

    @Synchronized
    fun acceptDictationEvidence(
        token: HansSetupOperationToken,
        evidence: HansSetupDictationEvidence,
    ): HansSetupDocument =
        mutate { current ->
            val step = token.step
            if (step !in DICTATION_LIVE_TEST_STEPS) return@mutate current
            val record = current.record(step)
            if (!current.matches(token) || record.status != HansSetupStepStatus.VERIFYING) {
                return@mutate current
            }
            when (evidence) {
                HansSetupDictationEvidence.LISTENING -> when (step) {
                    HansSetupStep.HARDWARE_LIVE_TEST -> if (
                        record.detailCode in HARDWARE_START_COMMAND_CODES
                    ) {
                        current.withRecord(
                            step,
                            record.copy(
                                liveStartObserved = true,
                                detailCode = "hardware_recording_listening_observed",
                            ),
                        )
                    } else current
                    HansSetupStep.CAMERA_HOLD_LIVE_TEST -> if (
                        record.detailCode == "camera_hold_start_command_observed"
                    ) {
                        current.withRecord(
                            step,
                            record.copy(
                                liveStartObserved = true,
                                detailCode = "camera_recording_listening_observed",
                            ),
                        )
                    } else current
                    HansSetupStep.VOICE_DICTATION_TEST -> if (record.liveStartObserved) {
                        current.withRecord(
                            step,
                            record.copy(detailCode = "voice_dictation_listening_observed"),
                        )
                    } else current
                    else -> current
                }
                HansSetupDictationEvidence.SENT,
                HansSetupDictationEvidence.NATIVE_LIVE_COMPLETED -> when (step) {
                    HansSetupStep.HARDWARE_LIVE_TEST -> if (
                        record.liveStartObserved && record.detailCode in HARDWARE_STOP_COMMAND_CODES
                    ) {
                        if (record.auxiliaryEvidenceObserved) {
                            current.withVerifiedLiveTest(
                                step,
                                HARDWARE_ACCESSIBILITY_PROOF_CODE,
                            )
                        } else {
                            current.withStatus(
                                step,
                                HansSetupStepStatus.AWAITING_USER,
                                "hardware_accessibility_receipt_missing",
                            )
                        }
                    } else current
                    HansSetupStep.CAMERA_HOLD_LIVE_TEST -> if (
                        record.liveStartObserved &&
                        record.detailCode == "camera_hold_stop_command_observed"
                    ) {
                        if (record.auxiliaryEvidenceObserved) {
                            current.withVerifiedLiveTest(step, "camera_tap_and_hold_live_verified")
                        } else {
                            current.withRecord(
                                step,
                                record.copy(detailCode = "camera_dictation_sent_waiting_capture"),
                            )
                        }
                    } else current
                    HansSetupStep.VOICE_DICTATION_TEST -> if (
                        record.liveStartObserved &&
                        record.detailCode == "voice_dictation_listening_observed"
                    ) {
                        current.withVerifiedLiveTest(step, if (evidence == HansSetupDictationEvidence.SENT) {
                            "voice_preview_and_transcript_sent_verified"
                        } else {
                            "voice_preview_and_native_reply_verified"
                        })
                    } else current
                    else -> current
                }
                HansSetupDictationEvidence.FAILED -> current.withStatus(
                    step,
                    HansSetupStepStatus.AWAITING_USER,
                    "dictation_live_test_failed",
                )
            }
        }

    @Synchronized
    fun revokeStep(
        step: HansSetupStep,
        detailCode: String,
        blocked: Boolean = false,
    ): HansSetupDocument = mutate { current ->
        if (!current.record(step).terminal) return@mutate current
        var next = current.withStatus(
            step,
            if (blocked) HansSetupStepStatus.BLOCKED else HansSetupStepStatus.AWAITING_USER,
            detailCode,
        )
        when (step) {
            HansSetupStep.HARDWARE_MAPPING -> next = next.withStatus(
                HansSetupStep.HARDWARE_LIVE_TEST,
                repairStatus(current, HansSetupStep.HARDWARE_LIVE_TEST),
                "hardware_mapping_requires_repair",
            )
            HansSetupStep.CAMERA_HOLD_CHOICE -> next = next.withStatus(
                HansSetupStep.CAMERA_HOLD_LIVE_TEST,
                repairStatus(current, HansSetupStep.CAMERA_HOLD_LIVE_TEST),
                "camera_choice_requires_repair",
            )
            HansSetupStep.NOTIFICATION_ACCESS -> next = next.withStatus(
                HansSetupStep.NOTIFICATION_LIVE_TEST,
                repairStatus(current, HansSetupStep.NOTIFICATION_LIVE_TEST),
                "notification_access_requires_repair",
            )
            HansSetupStep.ACCESSIBILITY_ACCESS -> next = next.withStatus(
                HansSetupStep.ACCESSIBILITY_LIVE_TEST,
                repairStatus(current, HansSetupStep.ACCESSIBILITY_LIVE_TEST),
                "accessibility_access_requires_repair",
            ).withStatus(
                HansSetupStep.HARDWARE_LIVE_TEST,
                dependentRepairStatus(next, HansSetupStep.HARDWARE_LIVE_TEST),
                "accessibility_access_requires_repair",
            )
            HansSetupStep.MICROPHONE_ACCESS -> next = next.withStatus(
                HansSetupStep.HARDWARE_LIVE_TEST,
                dependentRepairStatus(next, HansSetupStep.HARDWARE_LIVE_TEST),
                "microphone_access_requires_repair",
            ).withStatus(
                HansSetupStep.CAMERA_HOLD_LIVE_TEST,
                dependentRepairStatus(next, HansSetupStep.CAMERA_HOLD_LIVE_TEST),
                "microphone_access_requires_repair",
            ).withStatus(
                HansSetupStep.VOICE_DICTATION_TEST,
                repairStatus(current, HansSetupStep.VOICE_DICTATION_TEST),
                "microphone_access_requires_repair",
            )
            HansSetupStep.SPEECH_CREDENTIAL_ACCESS -> next = next.withStatus(
                HansSetupStep.VOICE_DICTATION_TEST,
                repairStatus(current, HansSetupStep.VOICE_DICTATION_TEST),
                "speech_credential_requires_repair",
            )
            HansSetupStep.APP_NOTIFICATIONS_ACCESS -> next = next.withStatus(
                HansSetupStep.NOTIFICATION_LIVE_TEST,
                if (
                    current.record(HansSetupStep.NOTIFICATION_ACCESS).status ==
                    HansSetupStepStatus.SKIPPED
                ) {
                    HansSetupStepStatus.SKIPPED
                } else {
                    repairStatus(current, HansSetupStep.NOTIFICATION_LIVE_TEST)
                },
                "app_notification_access_requires_repair",
            )
            else -> Unit
        }
        next
    }

    @Synchronized
    fun advance(): HansSetupDocument = mutate { current ->
        require(current.record(current.currentStep).terminal || current.currentStep == HansSetupStep.COMPLETE) {
            "setup_step_not_verified"
        }
        current
    }

    private fun updateToken(
        token: HansSetupOperationToken,
        block: (HansSetupStepRecord) -> HansSetupStepRecord,
    ): HansSetupDocument = mutate { current ->
        current.requireCurrentToken(token)
        current.withRecord(token.step, block(current.record(token.step)))
    }

    private fun mutate(block: (HansSetupDocument) -> HansSetupDocument): HansSetupDocument {
        val stored = storage.read()
        val current = normalize(stored)
        val changed = normalize(block(current))
        if (changed == current && stored == current) return current
        val next = normalize(
            changed.copy(
                revision = current.revision + 1,
                updatedAtMillis = clock.nowMillis(),
            ),
        )
        storage.write(next)
        return next
    }

    private fun normalize(document: HansSetupDocument): HansSetupDocument {
        var next = document
        val keyCapture = next.record(HansSetupStep.HARDWARE_MAPPING)
        if (
            keyCapture.status == HansSetupStepStatus.VERIFYING &&
            keyCapture.detailCode == LEGACY_OPERATION_REQUESTED_CODE
        ) {
            next = next.withStatus(
                HansSetupStep.HARDWARE_MAPPING,
                HansSetupStepStatus.AWAITING_USER,
                "key_capture_recreation_recovery",
            )
        }
        // Leave historical terminal records readable, but revoke pending retired operations.
        // Actual action-key/camera preferences live elsewhere and are deliberately untouched.
        HANS_SETUP_RETIRED_STEPS.forEach { step ->
            val record = next.steps[step] ?: return@forEach
            if (!record.terminal || record.operationNonce != null) {
                next = next.withRecord(step, record.copy(
                    status = HansSetupStepStatus.SKIPPED,
                    operationNonce = null,
                    detailCode = "setup_step_retired",
                    verifiedAtMillis = null,
                    liveStartObserved = false,
                    auxiliaryEvidenceObserved = false,
                ))
            }
        }
        // Completed setups stay complete when a new optional disclosure is introduced. Settings
        // offers each upgrade explicitly; migration never manufactures consent.
        if (next.record(HansSetupStep.OPTIONAL_CAPABILITIES).terminal &&
            next.optionalCapabilities.isNotEmpty()
        ) {
            setOf(
                HansSetupOptionalCapability.ALL_FILES,
                HansSetupOptionalCapability.EVERYDAY_ACCESS,
                HansSetupOptionalCapability.NOTIFICATION_LINK_METADATA,
            ).forEach { introduced ->
                if (introduced !in next.optionalCapabilities) {
                    next = next.copy(
                        optionalCapabilities = next.optionalCapabilities +
                            (introduced to HansSetupOptionalCapabilityRecord(
                                decision = HansSetupCapabilityDecision.NOT_NOW,
                                status = HansSetupStepStatus.SKIPPED,
                                effective = false,
                            )),
                    )
                }
            }
        }
        val optionalAllTerminal = HansSetupOptionalCapability.entries.all { capability ->
            next.optionalCapabilityRecord(capability).terminal
        }
        val optionalStepTerminal = next.record(HansSetupStep.OPTIONAL_CAPABILITIES).terminal
        if (next.optionalCapabilities.isNotEmpty() && !optionalAllTerminal && optionalStepTerminal) {
            next = next.withStatus(
                HansSetupStep.OPTIONAL_CAPABILITIES,
                HansSetupStepStatus.AWAITING_USER,
                "optional_capabilities_incomplete",
            )
        } else if (optionalAllTerminal && !optionalStepTerminal) {
            next = next.withStatus(
                HansSetupStep.OPTIONAL_CAPABILITIES,
                HansSetupStepStatus.VERIFIED,
                "optional_capabilities_complete",
            )
        }
        val firstIncomplete = HANS_SETUP_ORDER
            .filterNot { it == HansSetupStep.COMPLETE }
            .firstOrNull { !next.record(it).terminal }
        val current = firstIncomplete ?: HansSetupStep.COMPLETE
        if (current == HansSetupStep.COMPLETE &&
            next.record(HansSetupStep.COMPLETE).status != HansSetupStepStatus.VERIFIED
        ) {
            next = next.withStatus(
                HansSetupStep.COMPLETE,
                HansSetupStepStatus.VERIFIED,
                "setup_complete",
            )
        }
        return next.copy(currentStep = current)
    }

    private fun HansSetupDocument.requireCurrentToken(token: HansSetupOperationToken) {
        require(currentStep == token.step) { "setup_step_not_current" }
        val record = record(token.step)
        require(record.generation == token.generation && record.operationNonce == token.nonce) {
            "stale_setup_operation"
        }
    }

    private fun HansSetupDocument.requireCurrentOptionalToken(
        token: HansSetupOperationToken,
        capability: HansSetupOptionalCapability,
    ) {
        require(token.step == HansSetupStep.OPTIONAL_CAPABILITIES) {
            "setup_step_not_current"
        }
        require(currentStep == HansSetupStep.OPTIONAL_CAPABILITIES) {
            "setup_step_not_current"
        }
        require(currentOptionalCapability == capability) {
            "setup_capability_not_current"
        }
        val record = record(HansSetupStep.OPTIONAL_CAPABILITIES)
        require(record.generation == token.generation && record.operationNonce == token.nonce) {
            "stale_setup_operation"
        }
    }

    private fun HansSetupDocument.matches(token: HansSetupOperationToken): Boolean {
        if (currentStep != token.step) return false
        val record = record(token.step)
        return record.generation == token.generation && record.operationNonce == token.nonce
    }

    /**
     * App Server snapshots repeatedly observe the same effective profile/selection. Keeping the
     * existing proof avoids turning clock ticks into persisted setup revisions and Live context
     * refreshes. A changed observation or proof record still receives a fresh verification.
     */
    private fun HansSetupDocument.withObservedVerification(
        step: HansSetupStep,
        detailCode: String,
        observationChanged: Boolean,
    ): HansSetupDocument {
        requireSetupCode(detailCode)
        val previous = record(step)
        val sameProof = previous.copy(
            status = HansSetupStepStatus.VERIFIED,
            operationNonce = null,
            detailCode = detailCode,
            liveStartObserved = false,
            auxiliaryEvidenceObserved = false,
        )
        if (!observationChanged && previous.verifiedAtMillis != null && previous == sameProof) {
            return this
        }
        return withStatus(step, HansSetupStepStatus.VERIFIED, detailCode)
    }

    private fun HansSetupDocument.withStatus(
        step: HansSetupStep,
        status: HansSetupStepStatus,
        detailCode: String,
    ): HansSetupDocument {
        requireSetupCode(detailCode)
        val previous = record(step)
        return withRecord(
            step,
            previous.copy(
                status = status,
                operationNonce = if (
                    status == HansSetupStepStatus.SETTINGS_OPENED ||
                    status == HansSetupStepStatus.VERIFYING
                ) {
                    previous.operationNonce
                } else {
                    null
                },
                detailCode = detailCode,
                verifiedAtMillis = if (status == HansSetupStepStatus.VERIFIED) {
                    clock.nowMillis()
                } else {
                    null
                },
                liveStartObserved = false,
                auxiliaryEvidenceObserved = false,
            ),
        )
    }

    private fun HansSetupDocument.withVerifiedLiveTest(
        step: HansSetupStep,
        detailCode: String,
    ): HansSetupDocument = withStatus(step, HansSetupStepStatus.VERIFIED, detailCode)

    private fun HansSetupDocument.withRecord(
        step: HansSetupStep,
        record: HansSetupStepRecord,
    ): HansSetupDocument = copy(steps = steps + (step to record))

    private fun HansSetupDocument.withOptionalCapability(
        capability: HansSetupOptionalCapability,
        capabilityRecord: HansSetupOptionalCapabilityRecord,
        detailCode: String,
        preserveOperation: Boolean = false,
    ): HansSetupDocument {
        val updated = copy(
            optionalCapabilities = optionalCapabilities + (capability to capabilityRecord),
        )
        val allTerminal = HansSetupOptionalCapability.entries.all { current ->
            updated.optionalCapabilityRecord(current).terminal
        }
        val stepStatus = when {
            allTerminal -> HansSetupStepStatus.VERIFIED
            preserveOperation -> HansSetupStepStatus.VERIFYING
            else -> HansSetupStepStatus.AWAITING_USER
        }
        return updated.withRecord(
            HansSetupStep.OPTIONAL_CAPABILITIES,
            updated.record(HansSetupStep.OPTIONAL_CAPABILITIES).copy(
                status = stepStatus,
                operationNonce = if (preserveOperation) {
                    updated.record(HansSetupStep.OPTIONAL_CAPABILITIES).operationNonce
                } else {
                    null
                },
                detailCode = detailCode,
                verifiedAtMillis = if (allTerminal) clock.nowMillis() else null,
                liveStartObserved = false,
                auxiliaryEvidenceObserved = false,
            ),
        )
    }

    private fun HansSetupDocument.skipStepAndDependents(
        step: HansSetupStep,
    ): HansSetupDocument {
        var next = withStatus(
            step,
            HansSetupStepStatus.SKIPPED,
            "explicitly_not_now",
        )
        DECLINE_DEPENDENCIES[step].orEmpty().forEach { dependent ->
            next = next.withStatus(
                dependent,
                HansSetupStepStatus.SKIPPED,
                "prerequisite_not_enabled",
            )
        }
        return next
    }

    private fun dependentRepairStatus(
        document: HansSetupDocument,
        step: HansSetupStep,
    ): HansSetupStepStatus = if (
        document.record(step).status == HansSetupStepStatus.SKIPPED
    ) {
        HansSetupStepStatus.SKIPPED
    } else when (step) {
        HansSetupStep.HARDWARE_LIVE_TEST ->
            if (document.inputChoice == HansSetupInputChoice.NO_HARDWARE_KEY) {
                HansSetupStepStatus.SKIPPED
            } else {
                HansSetupStepStatus.AWAITING_USER
            }
        HansSetupStep.CAMERA_HOLD_LIVE_TEST ->
            if (document.cameraHoldEnabled != true) {
                HansSetupStepStatus.SKIPPED
            } else {
                HansSetupStepStatus.AWAITING_USER
            }
        else -> HansSetupStepStatus.AWAITING_USER
    }

    private fun repairStatus(
        document: HansSetupDocument,
        step: HansSetupStep,
    ): HansSetupStepStatus = if (
        document.record(step).status == HansSetupStepStatus.SKIPPED
    ) {
        HansSetupStepStatus.SKIPPED
    } else {
        HansSetupStepStatus.AWAITING_USER
    }

    private companion object {
        val ACCESS_CONSENT_PAIRS = mapOf(
            HansSetupStep.MICROPHONE_ACCESS to HansSetupStep.MICROPHONE_CONSENT,
            HansSetupStep.APP_NOTIFICATIONS_ACCESS to HansSetupStep.APP_NOTIFICATIONS_CONSENT,
            HansSetupStep.NOTIFICATION_ACCESS to HansSetupStep.NOTIFICATION_LISTENER_CONSENT,
            HansSetupStep.ACCESSIBILITY_ACCESS to HansSetupStep.ACCESSIBILITY_CONSENT,
            HansSetupStep.HOME_ROLE to HansSetupStep.HOME_ROLE_CONSENT,
            HansSetupStep.SPEECH_CREDENTIAL_ACCESS to HansSetupStep.SPEECH_CREDENTIAL_CONSENT,
        )
        val SETUP_PREREQUISITE_SKIP_CODES = setOf(
            "hardware_key_not_selected",
            "prerequisite_not_enabled",
        )
        val DECLINE_DEPENDENCIES = mapOf(
            HansSetupStep.INPUT_CHOICE to setOf(
                HansSetupStep.HARDWARE_MAPPING,
                HansSetupStep.HARDWARE_LIVE_TEST,
            ),
            HansSetupStep.HARDWARE_MAPPING to setOf(HansSetupStep.HARDWARE_LIVE_TEST),
            HansSetupStep.MICROPHONE_CONSENT to setOf(
                HansSetupStep.MICROPHONE_ACCESS,
                HansSetupStep.HARDWARE_LIVE_TEST,
                HansSetupStep.CAMERA_HOLD_CHOICE,
                HansSetupStep.CAMERA_HOLD_LIVE_TEST,
                HansSetupStep.VOICE_DICTATION_TEST,
            ),
            HansSetupStep.MICROPHONE_ACCESS to setOf(
                HansSetupStep.HARDWARE_LIVE_TEST,
                HansSetupStep.CAMERA_HOLD_CHOICE,
                HansSetupStep.CAMERA_HOLD_LIVE_TEST,
                HansSetupStep.VOICE_DICTATION_TEST,
            ),
            HansSetupStep.APP_NOTIFICATIONS_CONSENT to setOf(
                HansSetupStep.APP_NOTIFICATIONS_ACCESS,
                HansSetupStep.NOTIFICATION_LIVE_TEST,
            ),
            HansSetupStep.APP_NOTIFICATIONS_ACCESS to
                setOf(HansSetupStep.NOTIFICATION_LIVE_TEST),
            HansSetupStep.NOTIFICATION_LISTENER_CONSENT to setOf(
                HansSetupStep.NOTIFICATION_ACCESS,
                HansSetupStep.NOTIFICATION_LIVE_TEST,
            ),
            HansSetupStep.NOTIFICATION_ACCESS to
                setOf(HansSetupStep.NOTIFICATION_LIVE_TEST),
            HansSetupStep.ACCESSIBILITY_CONSENT to setOf(
                HansSetupStep.ACCESSIBILITY_ACCESS,
                HansSetupStep.ACCESSIBILITY_LIVE_TEST,
                HansSetupStep.HARDWARE_LIVE_TEST,
            ),
            HansSetupStep.ACCESSIBILITY_ACCESS to
                setOf(
                    HansSetupStep.ACCESSIBILITY_LIVE_TEST,
                    HansSetupStep.HARDWARE_LIVE_TEST,
                ),
            HansSetupStep.HOME_ROLE_CONSENT to setOf(HansSetupStep.HOME_ROLE),
            HansSetupStep.SPEECH_CREDENTIAL_CONSENT to setOf(
                HansSetupStep.SPEECH_CREDENTIAL_ACCESS,
                HansSetupStep.VOICE_DICTATION_TEST,
            ),
            HansSetupStep.SPEECH_CREDENTIAL_ACCESS to setOf(
                HansSetupStep.VOICE_DICTATION_TEST,
            ),
            HansSetupStep.CAMERA_HOLD_CHOICE to
                setOf(HansSetupStep.CAMERA_HOLD_LIVE_TEST),
        )
        val DECLINABLE_STEPS = DECLINE_DEPENDENCIES.keys + setOf(
            HansSetupStep.HARDWARE_LIVE_TEST,
            HansSetupStep.CAMERA_CAPTURE_TEST,
            HansSetupStep.CAMERA_HOLD_LIVE_TEST,
            HansSetupStep.NOTIFICATION_LIVE_TEST,
            HansSetupStep.ACCESSIBILITY_LIVE_TEST,
            HansSetupStep.HOME_ROLE,
            HansSetupStep.VOICE_DICTATION_TEST,
            HansSetupStep.MODEL_REASONING,
            HansSetupStep.PERSONAL_PROFILE,
        )
        val LIVE_TEST_STEPS = setOf(
            HansSetupStep.HARDWARE_LIVE_TEST,
            HansSetupStep.CAMERA_CAPTURE_TEST,
            HansSetupStep.CAMERA_HOLD_LIVE_TEST,
            HansSetupStep.NOTIFICATION_LIVE_TEST,
            HansSetupStep.ACCESSIBILITY_LIVE_TEST,
            HansSetupStep.VOICE_DICTATION_TEST,
        )
        val RESTRICTED_SETTINGS_ACCESS_STEPS =
            RestrictedSettingsCapability.entries.mapTo(linkedSetOf()) { it.setupStep }
        val DICTATION_LIVE_TEST_STEPS = setOf(
            HansSetupStep.HARDWARE_LIVE_TEST,
            HansSetupStep.CAMERA_HOLD_LIVE_TEST,
            HansSetupStep.VOICE_DICTATION_TEST,
        )
        val HARDWARE_START_COMMAND_CODES = setOf(
            "hardware_toggle_start_command_observed",
            "hardware_hold_start_command_observed",
        )
        val HARDWARE_STOP_COMMAND_CODES = setOf(
            "hardware_toggle_stop_command_observed",
            "hardware_hold_stop_command_observed",
        )
        const val LEGACY_OPERATION_REQUESTED_CODE = "operation_requested"
        const val KEY_CAPTURE_OPERATION_REQUESTED_CODE = "key_capture_operation_requested_v2"
        const val HARDWARE_ACCESSIBILITY_PROOF_CODE =
            "hardware_accessibility_start_stop_sent_verified"
    }
}
