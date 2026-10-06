package ai.hans.standard.setup

import ai.hans.standard.phone.keys.ActionKeyCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HansSetupRepositoryTest {
    @Test
    fun addingAllFilesAccessKeepsCompletedSetupWithoutInventingAndroidConsent() {
        val older = documentAt(HansSetupStep.COMPLETE).copy(optionalCapabilities =
            HansSetupOptionalCapability.entries.filterNot { it == HansSetupOptionalCapability.ALL_FILES }
                .associateWith { HansSetupOptionalCapabilityRecord(HansSetupCapabilityDecision.NOT_NOW,
                    HansSetupStepStatus.SKIPPED, false) })
        val migrated = repository(storage = MemoryStorage(older)).read()
        assertTrue(migrated.complete)
        assertEquals(false, migrated.optionalCapabilityRecord(HansSetupOptionalCapability.ALL_FILES).effective)
        assertEquals(HansSetupStepStatus.SKIPPED,
            migrated.optionalCapabilityRecord(HansSetupOptionalCapability.ALL_FILES).status)
    }

    @Test
    fun microphoneDeclineSkipsEveryDictationPathButKeepsDirectCameraTest() {
        val repository = repository()
        reachMicrophoneConsent(repository, "no_hardware_key")

        val declined = repository.recordSimpleChoice(
            HansSetupStep.MICROPHONE_CONSENT,
            "not_now",
        )

        assertEquals(null, declined.cameraHoldEnabled)
        listOf(
            HansSetupStep.MICROPHONE_ACCESS,
            HansSetupStep.HARDWARE_LIVE_TEST,
            HansSetupStep.CAMERA_HOLD_CHOICE,
            HansSetupStep.CAMERA_HOLD_LIVE_TEST,
            HansSetupStep.VOICE_DICTATION_TEST,
        ).forEach { step ->
            assertEquals(HansSetupStepStatus.SKIPPED, declined.record(step).status)
        }
        assertEquals(HansSetupStep.CAMERA_CAPTURE_TEST, declined.currentStep)
        assertEquals(
            HansSetupStepStatus.AWAITING_USER,
            declined.record(HansSetupStep.CAMERA_CAPTURE_TEST).status,
        )
    }

    @Test
    fun everyCapabilityRefusalRemainsTerminalAndReachesReview() {
        val repository = repository()
        reachMicrophoneConsent(repository, "no_hardware_key")
        repository.recordSimpleChoice(HansSetupStep.MICROPHONE_CONSENT, "not_now")
        repository.recordSimpleChoice(HansSetupStep.CAMERA_CAPTURE_TEST, "not_now")
        repository.recordSimpleChoice(HansSetupStep.APP_NOTIFICATIONS_CONSENT, "not_now")
        repository.recordSimpleChoice(HansSetupStep.NOTIFICATION_LISTENER_CONSENT, "not_now")
        repository.recordSimpleChoice(HansSetupStep.ACCESSIBILITY_CONSENT, "not_now")
        repository.recordSimpleChoice(HansSetupStep.HOME_ROLE_CONSENT, "not_now")
        repository.recordSimpleChoice(HansSetupStep.MODEL_REASONING, "not_now")
        HansSetupOptionalCapability.entries.forEach { capability ->
            assertEquals(capability, repository.read().currentOptionalCapability)
            repository.recordOptionalCapabilityChoice(capability, "not_now")
        }
        repository.recordSimpleChoice(HansSetupStep.PERSONAL_PROFILE, "skip")

        val review = repository.read()
        assertEquals(HansSetupStep.REVIEW, review.currentStep)
        assertTrue(
            HANS_SETUP_ORDER.takeWhile { it != HansSetupStep.REVIEW }
                .all { review.record(it).terminal },
        )
    }

    @Test
    fun introDeferralDoesNotAdvanceAndNoHardwareSkipsOnlyHardwarePath() {
        val repository = repository()
        repository.startOrResume()

        val deferred = repository.recordSimpleChoice(HansSetupStep.INTRO, "skip")
        assertEquals(HansSetupStep.INTRO, deferred.currentStep)
        assertFalse(deferred.record(deferred.currentStep).terminal)

        repository.recordSimpleChoice(HansSetupStep.INTRO, "begin")
        val noHardware = repository.recordSimpleChoice(
            HansSetupStep.INPUT_CHOICE,
            "no_hardware_key",
        )
        assertEquals(
            HansSetupStepStatus.SKIPPED,
            noHardware.record(HansSetupStep.HARDWARE_MAPPING).status,
        )
        assertEquals(
            HansSetupStepStatus.SKIPPED,
            noHardware.record(HansSetupStep.HARDWARE_LIVE_TEST).status,
        )
        assertEquals(HansSetupStep.MICROPHONE_CONSENT, noHardware.currentStep)
        assertFalse(noHardware.record(HansSetupStep.CAMERA_CAPTURE_TEST).terminal)
    }

    @Test
    fun optionalCapabilitiesAreDecidedAndProvenOneAtATime() {
        val repository = repository(
            storage = MemoryStorage(documentAt(HansSetupStep.OPTIONAL_CAPABILITIES)),
        )

        assertEquals(
            HansSetupOptionalCapability.EVERYDAY_ACCESS,
            repository.read().currentOptionalCapability,
        )
        repository.recordOptionalCapabilityChoice(
            HansSetupOptionalCapability.EVERYDAY_ACCESS,
            "enable",
        )
        val token = repository.beginOptionalCapabilityOperation(
            HansSetupOptionalCapability.EVERYDAY_ACCESS,
        )
        repository.applyOptionalCapabilityProbe(
            token,
            HansSetupOptionalCapability.EVERYDAY_ACCESS,
            HansSetupProbeResult(true, "everyday_access_bundle_active"),
        )
        assertEquals(
            HansSetupOptionalCapability.NOTIFICATION_LINK_METADATA,
            repository.read().currentOptionalCapability,
        )
        assertEquals(
            true,
            repository.read().optionalCapabilityRecord(
                HansSetupOptionalCapability.EVERYDAY_ACCESS,
            ).effective,
        )

        HansSetupOptionalCapability.entries.drop(1).forEach { capability ->
            repository.recordOptionalCapabilityChoice(capability, "not_now")
        }
        assertEquals(HansSetupStep.PERSONAL_PROFILE, repository.read().currentStep)
        assertEquals(null, repository.read().currentOptionalCapability)
    }

    @Test
    fun partialOptionalCapabilityStateReopensAStaleTerminalPhaseSafely() {
        val base = documentAt(HansSetupStep.PERSONAL_PROFILE)
        val repository = repository(
            storage = MemoryStorage(
                base.copy(
                    optionalCapabilities = mapOf(
                        HansSetupOptionalCapability.CONTACTS to
                            HansSetupOptionalCapabilityRecord(
                                decision = HansSetupCapabilityDecision.NOT_NOW,
                                status = HansSetupStepStatus.SKIPPED,
                                effective = false,
                            ),
                    ),
                ),
            ),
        )

        val repaired = repository.read()
        assertEquals(HansSetupStep.OPTIONAL_CAPABILITIES, repaired.currentStep)
        assertEquals(HansSetupOptionalCapability.CALENDAR, repaired.currentOptionalCapability)
    }

    @Test
    fun completedSetupFromBeforeOptionalConsentAdditionsStaysCompleteWithoutInventingConsent() {
        val legacyCapabilities = HansSetupOptionalCapability.entries
            .filterNot {
                it == HansSetupOptionalCapability.EVERYDAY_ACCESS ||
                    it == HansSetupOptionalCapability.NOTIFICATION_LINK_METADATA
            }
            .associateWith {
                HansSetupOptionalCapabilityRecord(
                    decision = HansSetupCapabilityDecision.NOT_NOW,
                    status = HansSetupStepStatus.SKIPPED,
                    effective = false,
                )
            }
        val repository = repository(
            storage = MemoryStorage(
                documentAt(HansSetupStep.COMPLETE).copy(
                    optionalCapabilities = legacyCapabilities,
                ),
            ),
        )

        val migrated = repository.read()
        assertEquals(HansSetupStep.COMPLETE, migrated.currentStep)
        assertEquals(
            HansSetupStepStatus.SKIPPED,
            migrated.optionalCapabilityRecord(HansSetupOptionalCapability.EVERYDAY_ACCESS).status,
        )
        assertEquals(
            HansSetupStepStatus.SKIPPED,
            migrated.optionalCapabilityRecord(
                HansSetupOptionalCapability.NOTIFICATION_LINK_METADATA,
            ).status,
        )
    }

    @Test
    fun reviewRequiresFreshNonceAndExplicitUserConfirmation() {
        var nonceIndex = 0
        val repository = repository(
            storage = MemoryStorage(documentAtReview()),
            nonceSource = HansSetupNonceSource { "review_nonce_${++nonceIndex}_123456789" },
        )

        val stale = repository.beginReviewConfirmation()
        val fresh = repository.beginReviewConfirmation()
        assertTrue(runCatching { repository.confirmReview(stale.nonce, true) }.isFailure)
        assertTrue(runCatching { repository.confirmReview(fresh.nonce, false) }.isFailure)
        assertTrue(repository.confirmReview(fresh.nonce, true).complete)
    }

    @Test
    fun keyMappingDoesNotRequireAForcedRecordingStopOrSentReceipt() {
        val repository = repository()
        reachHardwareLiveTest(repository)
        assertEquals(HansSetupStep.HOME_ROLE_CONSENT, repository.read().currentStep)
        assertTrue(runCatching { repository.beginLiveTest(HansSetupStep.HARDWARE_LIVE_TEST) }.isFailure)
        assertTrue(repository.read().record(HansSetupStep.HARDWARE_MAPPING).terminal)
    }

    @Test
    fun directCameraCaptureProofContinuesWithoutOfferingCameraHold() {
        val repository = repository()
        reachCameraCaptureTest(repository)
        val capture = repository.beginLiveTest(HansSetupStep.CAMERA_CAPTURE_TEST)
        val captured = repository.acceptCameraCapture(capture)
        assertEquals(HansSetupStepStatus.VERIFIED, captured.record(HansSetupStep.CAMERA_CAPTURE_TEST).status)
        assertEquals(HansSetupStep.APP_NOTIFICATIONS_CONSENT, captured.currentStep)
        assertTrue(runCatching { repository.beginOperation(HansSetupStep.CAMERA_HOLD_CHOICE) }.isFailure)
    }

    @Test
    fun allLegacyPendingVoiceStepsMigrateWithoutReopeningOrInventingPracticeProof() {
        HANS_SETUP_RETIRED_STEPS.forEach { retired ->
            val nextStep = when (retired) {
                HansSetupStep.HARDWARE_LIVE_TEST -> HansSetupStep.HOME_ROLE_CONSENT
                HansSetupStep.CAMERA_HOLD_CHOICE, HansSetupStep.CAMERA_HOLD_LIVE_TEST ->
                    HansSetupStep.APP_NOTIFICATIONS_CONSENT
                else -> HansSetupStep.MODEL_REASONING
            }
            val oldRecord = HansSetupStepRecord(HansSetupStepStatus.VERIFYING, 7,
                "legacy_operation_nonce_123456", "operation_requested", 10, null, true, true)
            val legacy = documentAt(nextStep).copy(currentStep = retired,
                inputChoice = HansSetupInputChoice.HARDWARE_HOLD, cameraHoldEnabled = true,
                effectiveModel = "gpt-6-astra", effectiveReasoningEffort = "medium",
                steps = documentAt(nextStep).steps + (retired to oldRecord))
            val storage = MemoryStorage(legacy)
            val repository = repository(storage = storage)
            val migrated = repository.startOrResume()
            assertEquals(nextStep, migrated.currentStep)
            assertEquals(HansSetupInputChoice.HARDWARE_HOLD, migrated.inputChoice)
            assertEquals(true, migrated.cameraHoldEnabled)
            assertEquals("gpt-6-astra", migrated.effectiveModel)
            assertEquals(legacy.steps - retired, migrated.steps - retired)
            assertEquals(HansSetupStepStatus.SKIPPED, migrated.record(retired).status)
            assertEquals(null, migrated.record(retired).operationNonce)
            assertEquals("setup_step_retired", migrated.record(retired).detailCode)
            assertTrue(runCatching { repository.beginOperation(retired) }.isFailure)
            assertEquals(migrated, repository(storage = storage).read())
        }
    }

    @Test
    fun modelStillWaitsForEffectiveAppServerReceiptWithoutMandatoryVoicePractice() {
        val repository = repository()
        reachVoiceTest(repository)
        val modelToken = repository.beginOperation(HansSetupStep.MODEL_REASONING)
        repository.requestModelSelection(modelToken, "gpt-5.6-luna", "max")
        repository.observeEffectiveSelection("gpt-5.6-sol", "ultra")
        assertEquals(
            HansSetupStepStatus.VERIFYING,
            repository.read().record(HansSetupStep.MODEL_REASONING).status,
        )
        val confirmed = repository.observeEffectiveSelection("gpt-5.6-luna", "max")
        assertEquals(HansSetupStep.OPTIONAL_CAPABILITIES, confirmed.currentStep)
    }

    @Test
    fun legacySentOrNativeReplyCannotVerifyRetiredVoicePractice() {
        val retired = HansSetupStep.VOICE_DICTATION_TEST
        val legacy = documentAt(HansSetupStep.MODEL_REASONING).copy(
            currentStep = retired, steps = documentAt(HansSetupStep.MODEL_REASONING).steps +
                (retired to HansSetupStepRecord(HansSetupStepStatus.VERIFYING, 1,
                    "legacy_operation_nonce_123456", "voice_preview_observed", 1, null, true, true)))
        val repository = repository(storage = MemoryStorage(legacy))
        val token = HansSetupOperationToken(retired, 1, "legacy_operation_nonce_123456")
        val before = repository.startOrResume()
        HansSetupDictationEvidence.entries.forEach { evidence ->
            assertEquals(before, repository.acceptDictationEvidence(token, evidence))
        }
        assertEquals(HansSetupStep.MODEL_REASONING, before.currentStep)
        assertEquals(HansSetupStepStatus.SKIPPED, before.record(retired).status)
    }

    @Test
    fun completedSetupAcceptsFreshEffectiveModelChangesWithoutReopening() {
        val completed = completedSelectionDocument()
        val storage = MemoryStorage(completed)
        val repository = repository(storage = storage)
        val updated = repository.observeEffectiveSelection("gpt-5.6-sol", "ultra")
        assertEquals(HansSetupStep.COMPLETE, updated.currentStep)
        assertEquals(HansSetupStepStatus.VERIFIED, updated.record(HansSetupStep.MODEL_REASONING).status)
        assertEquals("gpt-5.6-sol", updated.effectiveModel)
        assertEquals("ultra", updated.effectiveReasoningEffort)
        assertEquals(updated.effectiveModel, updated.requestedModel)
        assertEquals(updated.effectiveReasoningEffort, updated.requestedReasoningEffort)
        assertEquals(completed.steps - HansSetupStep.MODEL_REASONING, updated.steps - HansSetupStep.MODEL_REASONING)
        assertEquals(updated, repository(storage = storage).read())
    }

    @Test
    fun repeatedConfirmedProfileObservationsDoNotRewriteTimestampRevisionOrStorage() {
        val completed = completedSelectionDocument()
        val storage = MemoryStorage(completed.copy(
            profileConfirmed = false,
            steps = completed.steps + (HansSetupStep.PERSONAL_PROFILE to HansSetupStepRecord(
                status = HansSetupStepStatus.AWAITING_USER,
                detailCode = "profile_confirmation_missing",
            )),
        ))
        var nowMillis = 10_000L
        val repository = HansSetupRepository(storage, clock = HansSetupClock { nowMillis })
        val confirmed = repository.observeProfileConfirmed(true)
        assertEquals(10_000L, confirmed.record(HansSetupStep.PERSONAL_PROFILE).verifiedAtMillis)
        val writes = storage.writeCount

        repeat(100) {
            nowMillis += 17L
            assertEquals(confirmed, repository.observeProfileConfirmed(true))
            assertEquals(writes, storage.writeCount)
        }
        assertEquals(confirmed, repository.read())
    }

    @Test
    fun repeatedMatchingSelectionObservationsDoNotRewriteTimestampRevisionOrStorage() {
        val pending = documentAtReview()
        val storage = MemoryStorage(pending.copy(
            steps = pending.steps + (HansSetupStep.MODEL_REASONING to HansSetupStepRecord(
                status = HansSetupStepStatus.VERIFYING,
                detailCode = "awaiting_app_server_selection_proof",
            )),
        ))
        var nowMillis = 10_000L
        val repository = HansSetupRepository(storage, clock = HansSetupClock { nowMillis })
        val confirmed = repository.observeEffectiveSelection("gpt-5.6-luna", "max")
        assertEquals(10_000L, confirmed.record(HansSetupStep.MODEL_REASONING).verifiedAtMillis)
        val writes = storage.writeCount

        repeat(100) {
            nowMillis += 17L
            assertEquals(confirmed, repository.observeEffectiveSelection("gpt-5.6-luna", "max"))
            assertEquals(writes, storage.writeCount)
        }
        assertEquals(confirmed, repository.read())
    }

    @Test
    fun repeatedCompletedSetupSelectionObservationsDoNotRewriteTimestampRevisionOrStorage() {
        val storage = MemoryStorage(completedSelectionDocument())
        var nowMillis = 10_000L
        val repository = HansSetupRepository(storage, clock = HansSetupClock { nowMillis })
        val confirmed = repository.observeEffectiveSelection("gpt-5.6-luna", "max")
        val writes = storage.writeCount

        repeat(100) {
            nowMillis += 17L
            assertEquals(confirmed, repository.observeEffectiveSelection("gpt-5.6-luna", "max"))
            assertEquals(writes, storage.writeCount)
        }
        assertEquals(HansSetupStep.COMPLETE, repository.read().currentStep)
    }

    @Test
    fun profileRevocationAndReconfirmationRemainPersistedAfterStableObservations() {
        val storage = MemoryStorage(completedSelectionDocument())
        var nowMillis = 10_000L
        val repository = HansSetupRepository(storage, clock = HansSetupClock { nowMillis })
        val confirmed = repository.observeProfileConfirmed(true)
        nowMillis = 11_000L
        assertEquals(confirmed, repository.observeProfileConfirmed(true))

        nowMillis = 12_000L
        val revoked = repository.observeProfileConfirmed(false)
        assertFalse(revoked.profileConfirmed)
        assertEquals(HansSetupStepStatus.AWAITING_USER, revoked.record(HansSetupStep.PERSONAL_PROFILE).status)
        assertEquals(null, revoked.record(HansSetupStep.PERSONAL_PROFILE).verifiedAtMillis)
        assertEquals(confirmed.revision + 1, revoked.revision)
        assertEquals(revoked, repository.read())
        val writesAfterRevocation = storage.writeCount
        nowMillis = 13_000L
        assertEquals(revoked, repository.observeProfileConfirmed(false))
        assertEquals(writesAfterRevocation, storage.writeCount)

        nowMillis = 14_000L
        val reconfirmed = repository.observeProfileConfirmed(true)
        assertTrue(reconfirmed.profileConfirmed)
        assertEquals(HansSetupStepStatus.VERIFIED, reconfirmed.record(HansSetupStep.PERSONAL_PROFILE).status)
        assertEquals(14_000L, reconfirmed.record(HansSetupStep.PERSONAL_PROFILE).verifiedAtMillis)
        assertEquals(revoked.revision + 1, reconfirmed.revision)
        assertEquals(reconfirmed, repository.read())
    }

    @Test
    fun changedModelAndEffortKeepFreshVerificationAndPersistenceAfterStableObservations() {
        val storage = MemoryStorage(completedSelectionDocument())
        var nowMillis = 10_000L
        val repository = HansSetupRepository(storage, clock = HansSetupClock { nowMillis })
        val confirmed = repository.observeEffectiveSelection("gpt-5.6-luna", "max")
        nowMillis = 11_000L
        assertEquals(confirmed, repository.observeEffectiveSelection("gpt-5.6-luna", "max"))

        nowMillis = 12_000L
        val changedModel = repository.observeEffectiveSelection("gpt-5.6-sol", "max")
        assertEquals("gpt-5.6-sol", changedModel.effectiveModel)
        assertEquals(changedModel.effectiveModel, changedModel.requestedModel)
        assertEquals(confirmed.revision + 1, changedModel.revision)
        assertEquals(12_000L, changedModel.record(HansSetupStep.MODEL_REASONING).verifiedAtMillis)
        assertEquals(changedModel, repository.read())

        nowMillis = 13_000L
        val changedEffort = repository.observeEffectiveSelection("gpt-5.6-sol", "ultra")
        assertEquals("ultra", changedEffort.effectiveReasoningEffort)
        assertEquals(changedEffort.effectiveReasoningEffort, changedEffort.requestedReasoningEffort)
        assertEquals(changedModel.revision + 1, changedEffort.revision)
        assertEquals(13_000L, changedEffort.record(HansSetupStep.MODEL_REASONING).verifiedAtMillis)
        assertEquals(HansSetupStep.COMPLETE, changedEffort.currentStep)
        assertEquals(changedEffort, repository.read())
    }

    @Test
    fun historicalCompletionRecoversOnlyTheOldSelectionInvalidation() {
        val completed = completedSelectionDocument()
        val broken = completed.copy(steps = completed.steps + (HansSetupStep.MODEL_REASONING to
            HansSetupStepRecord(status = HansSetupStepStatus.AWAITING_USER, detailCode = "effective_selection_changed")))
        val storage = MemoryStorage(broken)
        val repository = repository(storage = storage)
        assertEquals(HansSetupStep.MODEL_REASONING, repository.read().currentStep)
        val repaired = repository.observeEffectiveSelection("gpt-5.6-sol", "ultra")
        assertEquals(HansSetupStep.COMPLETE, repaired.currentStep)
        assertEquals(HansSetupStepStatus.VERIFIED, repaired.record(HansSetupStep.MODEL_REASONING).status)
        assertEquals(repaired.effectiveModel, repaired.requestedModel)
        assertEquals(repaired.effectiveReasoningEffort, repaired.requestedReasoningEffort)
        assertEquals(completed.steps - HansSetupStep.MODEL_REASONING, repaired.steps - HansSetupStep.MODEL_REASONING)
        assertEquals(repaired, repository(storage = storage).read())
    }

    @Test
    fun historicalCompletionDoesNotConfirmAnExplicitPendingModelRequest() {
        val completed = completedSelectionDocument()
        val pending = completed.copy(steps = completed.steps + (HansSetupStep.MODEL_REASONING to
            HansSetupStepRecord(status = HansSetupStepStatus.VERIFYING, detailCode = "awaiting_app_server_selection_proof")))
        val repository = repository(storage = MemoryStorage(pending))
        val observed = repository.observeEffectiveSelection("gpt-5.6-sol", "ultra")
        assertEquals(HansSetupStepStatus.VERIFYING, observed.record(HansSetupStep.MODEL_REASONING).status)
        assertEquals(HansSetupStep.MODEL_REASONING, observed.currentStep)
        assertEquals(completed.requestedModel, observed.requestedModel)
        assertEquals(completed.requestedReasoningEffort, observed.requestedReasoningEffort)
    }

    @Test
    fun historicalCompletionDoesNotRepairAnUnrelatedModelBlocker() {
        val completed = completedSelectionDocument()
        val blocked = completed.copy(steps = completed.steps + (HansSetupStep.MODEL_REASONING to
            HansSetupStepRecord(status = HansSetupStepStatus.AWAITING_USER, detailCode = "model_selection_rejected")))
        val repository = repository(storage = MemoryStorage(blocked))
        val observed = repository.observeEffectiveSelection("gpt-5.6-sol", "ultra")
        assertEquals(HansSetupStep.MODEL_REASONING, observed.currentStep)
        assertEquals("model_selection_rejected", observed.record(HansSetupStep.MODEL_REASONING).detailCode)
    }

    @Test
    fun repairingHistoricalModelInvalidationDoesNotRestoreRevokedPermission() {
        val completed = completedSelectionDocument()
        val revoked = HansSetupStepRecord(status = HansSetupStepStatus.AWAITING_USER, detailCode = "microphone_permission_revoked")
        val broken = completed.copy(steps = completed.steps + mapOf(
            HansSetupStep.MICROPHONE_ACCESS to revoked,
            HansSetupStep.MODEL_REASONING to HansSetupStepRecord(
                status = HansSetupStepStatus.AWAITING_USER,
                detailCode = "effective_selection_changed",
            ),
        ))
        val repository = repository(storage = MemoryStorage(broken))
        val observed = repository.observeEffectiveSelection("gpt-5.6-sol", "ultra")
        assertEquals(HansSetupStep.MICROPHONE_ACCESS, observed.currentStep)
        assertEquals(revoked, observed.record(HansSetupStep.MICROPHONE_ACCESS))
        assertEquals(HansSetupStepStatus.VERIFIED, observed.record(HansSetupStep.MODEL_REASONING).status)
    }

    @Test
    fun selectionRecoveryDoesNotVerifyOtherUnmetStepsOrUnfinishedOnboarding() {
        val completed = completedSelectionDocument()
        val profileMissing = completed.copy(steps = completed.steps + (HansSetupStep.PERSONAL_PROFILE to
            HansSetupStepRecord(status = HansSetupStepStatus.AWAITING_USER, detailCode = "profile_not_confirmed")))
        val profileRepository = repository(storage = MemoryStorage(profileMissing))
        val observed = profileRepository.observeEffectiveSelection("gpt-5.6-sol", "ultra")
        assertEquals(HansSetupStep.PERSONAL_PROFILE, observed.currentStep)
        assertEquals(HansSetupStepStatus.AWAITING_USER, observed.record(HansSetupStep.PERSONAL_PROFILE).status)
        val unfinished = repository(storage = MemoryStorage(documentAtReview()))
        val changed = unfinished.observeEffectiveSelection("gpt-5.6-sol", "ultra")
        assertEquals(HansSetupStepStatus.AWAITING_USER, changed.record(HansSetupStep.MODEL_REASONING).status)
        assertEquals("effective_selection_changed", changed.record(HansSetupStep.MODEL_REASONING).detailCode)
    }

    private fun completedSelectionDocument(): HansSetupDocument {
        val repository = repository(storage = MemoryStorage(documentAtReview()))
        val review = repository.beginReviewConfirmation()
        return repository.confirmReview(review.nonce, explicitUserConfirmation = true)
    }

    @Test
    fun apiCredentialChangesCannotReopenKeyFreeSetupOrTouchStoredProgress() {
        val storage = MemoryStorage(completedSelectionDocument())
        val repository = repository(storage = storage)
        val before = repository.read()
        assertEquals(before, repository.observeSpeechCredentialAvailability(false))
        assertEquals(before, repository.observeSpeechCredentialAvailability(true))
        assertTrue(repository.read().complete)
        assertEquals(0, storage.writeCount)
    }

    @Test
    fun staleSettingsAndLiveReceiptsCannotConfirmNewerGenerations() {
        val repository = repository()
        reachNotificationAccess(repository)
        val stale = repository.beginOperation(HansSetupStep.NOTIFICATION_ACCESS)
        val current = repository.beginOperation(HansSetupStep.NOTIFICATION_ACCESS)
        assertTrue(runCatching { repository.markSettingsOpened(stale) }.isFailure)
        assertEquals(HansSetupStepStatus.SETTINGS_OPENED,
            repository.markSettingsOpened(current).record(HansSetupStep.NOTIFICATION_ACCESS).status)
        repository.applyFreshProbe(current, HansSetupProbeResult(true, "notification_access_granted"))
        val oldLive = repository.beginLiveTest(HansSetupStep.NOTIFICATION_LIVE_TEST)
        val currentLive = repository.beginLiveTest(HansSetupStep.NOTIFICATION_LIVE_TEST)
        assertTrue(runCatching {
            repository.applyFreshProbe(oldLive, HansSetupProbeResult(true, "late_live_receipt"))
        }.isFailure)
        assertEquals(currentLive.generation, repository.read().record(HansSetupStep.NOTIFICATION_LIVE_TEST).generation)
    }

    @Test
    fun activityRecreationReturnsActiveKeyCaptureToRetryableAwaitingState() {
        val repository = repository()
        repository.startOrResume()
        repository.recordSimpleChoice(HansSetupStep.INTRO, "begin")
        repository.recordSimpleChoice(HansSetupStep.INPUT_CHOICE, "hardware_toggle")
        val token = repository.beginOperation(HansSetupStep.HARDWARE_MAPPING)

        val recovered = repository.abandonKeyCaptureForActivityRecreation(token)

        val record = recovered.record(HansSetupStep.HARDWARE_MAPPING)
        assertEquals(HansSetupStep.HARDWARE_MAPPING, recovered.currentStep)
        assertEquals(HansSetupStepStatus.AWAITING_USER, record.status)
        assertEquals(null, record.operationNonce)
        assertEquals("key_capture_activity_recreated", record.detailCode)
        val afterLateReceipt = repository.acceptKeyCapture(token, true, false, "late")
        assertEquals(HansSetupStepStatus.AWAITING_USER, afterLateReceipt.record(token.step).status)
        assertEquals("key_capture_activity_recreated", afterLateReceipt.record(token.step).detailCode)
    }

    @Test
    fun activityRecreationCannotRestoreRetiredDictationOperations() {
        listOf(HansSetupStep.HARDWARE_LIVE_TEST, HansSetupStep.CAMERA_HOLD_LIVE_TEST,
            HansSetupStep.VOICE_DICTATION_TEST).forEach { step ->
            val base = documentAt(HansSetupStep.MODEL_REASONING)
            val storage = MemoryStorage(base.copy(currentStep = step,
                steps = base.steps + (step to HansSetupStepRecord(HansSetupStepStatus.VERIFYING, 2,
                    "retired_operation_nonce_123456", "operation_requested", 10, null, true, true))))
            val repository = repository(storage = storage)
            val token = HansSetupOperationToken(step, 2, "retired_operation_nonce_123456")
            val before = repository.startOrResume()
            val writesBefore = storage.writeCount
            repeat(3) {
                assertEquals(before, repository.abandonDictationLiveTestForActivityRecreation(token))
            }
            assertEquals(writesBefore, storage.writeCount)
            assertEquals(before, repository.acceptDictationEvidence(token, HansSetupDictationEvidence.LISTENING))
            assertEquals(HansSetupStep.MODEL_REASONING, before.currentStep)
            assertEquals(HansSetupStepStatus.SKIPPED, before.record(step).status)
            assertEquals(null, before.record(step).operationNonce)
        }
    }

    @Test
    fun historicalHardwareProofRemainsReadableButNeverReintroducesRetiredStep() {
        val base = documentAt(HansSetupStep.HOME_ROLE_CONSENT)
        val oldProof = HansSetupStepRecord(HansSetupStepStatus.VERIFIED,
            detailCode = "hardware_recording_start_stop_sent_verified")
        val repository = repository(storage = MemoryStorage(base.copy(
            steps = base.steps + (HansSetupStep.HARDWARE_LIVE_TEST to oldProof),
            inputChoice = HansSetupInputChoice.HARDWARE_TOGGLE)))
        val migrated = repository.read()
        assertEquals(HansSetupStep.HOME_ROLE_CONSENT, migrated.currentStep)
        assertEquals(oldProof, migrated.record(HansSetupStep.HARDWARE_LIVE_TEST))
        assertEquals(HansSetupStepStatus.VERIFIED, migrated.record(HansSetupStep.ACCESSIBILITY_LIVE_TEST).status)
        assertTrue(runCatching { repository.beginLiveTest(HansSetupStep.HARDWARE_LIVE_TEST) }.isFailure)
    }

    @Test
    fun newSetupOffersOnlyPressActionKeyOrNoPhysicalKeyNeverAHoldChoice() {
        val repository = repository()
        repository.startOrResume()
        repository.recordSimpleChoice(HansSetupStep.INTRO, "begin")
        assertTrue(runCatching {
            repository.recordSimpleChoice(HansSetupStep.INPUT_CHOICE, "hardware_hold")
        }.isFailure)
        assertEquals(HansSetupStep.INPUT_CHOICE, repository.read().currentStep)
        assertEquals(HansSetupStep.HARDWARE_MAPPING,
            repository.recordSimpleChoice(HansSetupStep.INPUT_CHOICE, "hardware_toggle").currentStep)
    }

    private fun reachMicrophoneConsent(
        repository: HansSetupRepository,
        hardwareChoice: String,
    ) {
        repository.startOrResume()
        repository.recordSimpleChoice(HansSetupStep.INTRO, "begin")
        repository.recordSimpleChoice(HansSetupStep.INPUT_CHOICE, hardwareChoice)
        if (hardwareChoice != "no_hardware_key") {
            val capture = repository.beginOperation(HansSetupStep.HARDWARE_MAPPING)
            repository.acceptKeyCapture(capture, true, false, "key_mapping_captured")
        }
    }

    private fun reachHardwareLiveTest(repository: HansSetupRepository) {
        reachMicrophoneConsent(repository, "hardware_toggle")
        repository.recordSimpleChoice(HansSetupStep.MICROPHONE_CONSENT, "enable")
        verifyFresh(repository, HansSetupStep.MICROPHONE_ACCESS)
        repository.recordSimpleChoice(HansSetupStep.CAMERA_CAPTURE_TEST, "not_now")
        repository.recordSimpleChoice(HansSetupStep.APP_NOTIFICATIONS_CONSENT, "not_now")
        repository.recordSimpleChoice(HansSetupStep.NOTIFICATION_LISTENER_CONSENT, "not_now")
        repository.recordSimpleChoice(HansSetupStep.ACCESSIBILITY_CONSENT, "enable")
        verifyFresh(repository, HansSetupStep.ACCESSIBILITY_ACCESS)
        repository.recordSimpleChoice(HansSetupStep.ACCESSIBILITY_LIVE_TEST, "not_now")
    }

    private fun reachCameraCaptureTest(repository: HansSetupRepository) {
        reachMicrophoneConsent(repository, "no_hardware_key")
        repository.recordSimpleChoice(HansSetupStep.MICROPHONE_CONSENT, "enable")
        verifyFresh(repository, HansSetupStep.MICROPHONE_ACCESS)
    }

    private fun reachNotificationAccess(repository: HansSetupRepository) {
        reachCameraCaptureTest(repository)
        repository.recordSimpleChoice(HansSetupStep.CAMERA_CAPTURE_TEST, "not_now")
        repository.recordSimpleChoice(HansSetupStep.APP_NOTIFICATIONS_CONSENT, "enable")
        verifyFresh(repository, HansSetupStep.APP_NOTIFICATIONS_ACCESS)
        repository.recordSimpleChoice(HansSetupStep.NOTIFICATION_LISTENER_CONSENT, "enable")
    }

    private fun reachVoiceTest(repository: HansSetupRepository) {
        reachCameraCaptureTest(repository)
        repository.recordSimpleChoice(HansSetupStep.CAMERA_CAPTURE_TEST, "not_now")
        repository.recordSimpleChoice(HansSetupStep.APP_NOTIFICATIONS_CONSENT, "not_now")
        repository.recordSimpleChoice(HansSetupStep.NOTIFICATION_LISTENER_CONSENT, "not_now")
        repository.recordSimpleChoice(HansSetupStep.ACCESSIBILITY_CONSENT, "not_now")
        repository.recordSimpleChoice(HansSetupStep.HOME_ROLE_CONSENT, "not_now")
    }

    private fun verifyFresh(repository: HansSetupRepository, step: HansSetupStep) {
        val token = repository.beginOperation(step)
        repository.applyFreshProbe(token, HansSetupProbeResult(true, "fresh_probe_verified"))
    }

    private fun documentAt(step: HansSetupStep): HansSetupDocument = HansSetupDocument(
        started = true,
        steps = HANS_SETUP_ORDER
            .takeWhile { it != step }
            .associateWith {
                HansSetupStepRecord(
                    status = HansSetupStepStatus.VERIFIED,
                    detailCode = if (it == HansSetupStep.HARDWARE_LIVE_TEST) {
                        "hardware_accessibility_start_stop_sent_verified"
                    } else {
                        "prerequisite_verified"
                    },
                )
            },
    )

    private fun documentAtReview(): HansSetupDocument = documentAt(HansSetupStep.REVIEW).copy(
        requestedModel = "gpt-5.6-luna",
        requestedReasoningEffort = "max",
        effectiveModel = "gpt-5.6-luna",
        effectiveReasoningEffort = "max",
        profileConfirmed = true,
    )

    private fun repository(
        storage: MemoryStorage = MemoryStorage(),
        nonceSource: HansSetupNonceSource = HansSetupNonceSource { "setup_nonce_123456789" },
    ) = HansSetupRepository(
        storage = storage,
        clock = HansSetupClock { 1234 },
        nonces = nonceSource,
    )

    private class MemoryStorage(
        private var value: HansSetupDocument = HansSetupDocument(),
    ) : HansSetupStorage {
        var writeCount = 0
            private set

        override fun read(): HansSetupDocument = value

        override fun write(document: HansSetupDocument) {
            value = document
            writeCount += 1
        }
    }
}
