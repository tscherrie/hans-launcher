package ai.hans.standard.setup

import ai.hans.standard.localization.TestResourceTextResolver

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.phone.capabilities.AccessibilityGrantState
import ai.hans.standard.phone.consent.HansPhoneActionPolicy
import ai.hans.standard.phone.keys.ActionKeyCommand
import ai.hans.standard.profile.ProfileClock
import ai.hans.standard.profile.ProfileNonceSource
import ai.hans.standard.profile.UserProfileDocument
import ai.hans.standard.profile.UserProfileDynamicToolCatalog
import ai.hans.standard.profile.UserProfileDynamicToolExecutor
import ai.hans.standard.profile.UserProfileRepository
import ai.hans.standard.profile.UserProfileStorage
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HansSetupDynamicToolsTest {
    @Test
    fun unfinishedLegacyHoldChoiceCapturesPressWithoutRewritingStoredChoice() {
        listOf(true, false).forEach { compatible ->
            val router = SetupUiCommandRouter()
            var requested: SetupUiCommand.BeginKeyCapture? = null
            val registration = router.attach(SetupUiCommandHandler { command, _, completion ->
                requested = command as SetupUiCommand.BeginKeyCapture
                completion(SetupUiCommandResult.Accepted())
            })
            try {
                val fixture = fixture(attachUi = false, uiRouter = router,
                    initial = documentAt(HansSetupStep.HARDWARE_MAPPING).copy(
                        inputChoice = HansSetupInputChoice.HARDWARE_HOLD))
                fixture.probe.holdCompatible = compatible
                call(fixture.executor, "get_setup_state", "{}")
                assertEquals(HansSetupStep.HARDWARE_MAPPING, fixture.repository.read().currentStep)
                val result = call(fixture.executor, "begin_key_capture", "{}")
                assertTrue(result.success)
                assertEquals(HansSetupInputChoice.HARDWARE_TOGGLE, checkNotNull(requested).inputChoice)
                assertEquals(HansSetupInputChoice.HARDWARE_HOLD, fixture.repository.read().inputChoice)
            } finally { registration.close() }
        }
    }

    @Test
    fun hiddenDesktopAccessNeverAppearsInSetupProjectionOrChangesSavedProject() {
        val fixture = fixture(initial = documentAtReview())
        preserveVerifiedFreshState(fixture)
        fixture.executor.refreshFreshEvidence()
        val project = HansSetupDesktopProject("/synthetic/hans/files/codex-workspace",
            "Legacy desktop instructions", "The folder grants no Android permissions.")
        fixture.probe.project = project
        val before = fixture.repository.read()
        val json = JSONObject(call(fixture.executor, "get_setup_state", "{}").contentText)
        assertFalse(json.has("desktopProject"))
        assertTrue(json.has("voiceUsageInstructions"))
        assertEquals(project, fixture.probe.project)
        assertEquals(before, fixture.repository.read())
        val speech = json.getJSONObject("technicalSummary").getJSONObject("speech")
        assertEquals("chatgpt_login", speech.getString("access"))
        assertEquals("optional_user_initiated_not_verified", speech.getString("practice"))
        assertFalse(speech.has("credentialProof"))
        assertFalse(speech.has("voiceDictationProof"))
    }

    @Test
    fun policyWireValuesAreExplicitAndDefaultSetupProjectionDoesNotClaimFullAccess() {
        assertEquals("confirm_actions", HansPhoneActionPolicy.CONFIRM_ACTIONS.wireValue)
        assertEquals("user_authorized_full_access", HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS.wireValue)
        val fixture = fixture(attachUi = false)
        val before = fixture.repository.read()

        val json = JSONObject(call(fixture.executor, "get_setup_state", "{}").contentText)

        assertEquals("confirm_actions", json.getString("phoneActionPolicy"))
        assertEquals("intro", json.getString("currentStep"))
        assertFalse(json.getBoolean("complete"))
        assertEquals(before, fixture.repository.read())
    }

    @Test
    fun sameEffectiveEverydayAccessExportsDifferentTrustedPoliciesIncludingCompletionSummary() {
        HansPhoneActionPolicy.entries.forEach { policy ->
            val fixture = completedEverydayAccessFixture(actionPolicy = policy)
            var bundleReads = 0
            fixture.probe.optionalResults[HansSetupOptionalCapability.EVERYDAY_ACCESS] =
                everydayAccessSetupProbe(policy) {
                    bundleReads += 1
                    true
                }
            val json = JSONObject(call(fixture.executor, "get_setup_state", "{}").contentText)

            assertEquals(policy.wireValue, json.getString("phoneActionPolicy"))
            assertTrue(json.getBoolean("complete"))
            val summary = json.getJSONObject("technicalSummary")
            assertEquals(policy.wireValue, summary.getString("phoneActionPolicy"))
            val optional = summary.getJSONArray("optionalCapabilities")
            val everyday = (0 until optional.length()).map(optional::getJSONObject)
                .single { it.getString("id") == "everyday_access" }
            assertTrue(everyday.getBoolean("effective"))
            assertEquals("verified", everyday.getString("proof"))
            assertEquals(if (policy == HansPhoneActionPolicy.CONFIRM_ACTIONS) 1 else 0, bundleReads)
        }
    }

    @Test
    fun fullPolicyWithoutBundleDoesNotManufactureAndroidGrantsOrCompleteSetup() {
        val fixture = fixture(attachUi = false, actionPolicy = HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS)
        var bundleReads = 0
        fixture.probe.optionalResults[HansSetupOptionalCapability.EVERYDAY_ACCESS] =
            everydayAccessSetupProbe(HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS) {
                bundleReads += 1
                false
            }
        fixture.probe.results[HansSetupStep.MICROPHONE_ACCESS] = HansSetupProbeResult(false, "microphone_permission_missing")
        fixture.probe.results[HansSetupStep.ACCESSIBILITY_ACCESS] = accessibilitySetupGrantProbe(AccessibilityGrantState.NOT_GRANTED)
        fixture.probe.optionalResults[HansSetupOptionalCapability.CONTACTS] = HansSetupProbeResult(false, "contacts_permission_missing")

        val json = JSONObject(call(fixture.executor, "get_setup_state", "{}").contentText)
        val state = fixture.repository.read()

        assertEquals("user_authorized_full_access", json.getString("phoneActionPolicy"))
        assertEquals(0, bundleReads)
        assertEquals(true, state.optionalCapabilityRecord(HansSetupOptionalCapability.EVERYDAY_ACCESS).effective)
        assertFalse(json.getBoolean("complete"))
        assertEquals("intro", json.getString("currentStep"))
        listOf(HansSetupStep.MICROPHONE_ACCESS, HansSetupStep.ACCESSIBILITY_ACCESS, HansSetupStep.ACCESSIBILITY_LIVE_TEST).forEach {
            assertFalse(it.name, state.record(it).status == HansSetupStepStatus.VERIFIED)
        }
        assertFalse(state.optionalCapabilityRecord(HansSetupOptionalCapability.CONTACTS).effective == true)
        call(fixture.executor, "get_setup_state", "{}")
        assertEquals("Reading the policy must not add mutations", state, fixture.repository.read())
    }

    @Test
    fun callerCannotOverrideTheReadOnlySetupPolicyField() {
        val fixture = fixture(attachUi = false)
        val before = fixture.repository.read()
        val forged = call(
            fixture.executor, "get_setup_state",
            """{"phoneActionPolicy":"user_authorized_full_access"}""",
        )
        assertFalse(forged.success)
        assertEquals(before, fixture.repository.read())
        assertEquals(
            "confirm_actions",
            JSONObject(call(fixture.executor, "get_setup_state", "{}").contentText).getString("phoneActionPolicy"),
        )
    }

    @Test
    fun accessibilitySetupCopyDescribesBothPoliciesWithoutClaimingAndroidOrRootAccess() {
        val full = setupAccessibilityDescription(HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS, text = TestResourceTextResolver(java.util.Locale.GERMAN))
        val confirm = setupAccessibilityDescription(HansPhoneActionPolicy.CONFIRM_ACTIONS, text = TestResourceTextResolver(java.util.Locale.GERMAN))
        assertTrue(full.contains("ohne zusätzliche Hans-Rückfragen"))
        assertFalse(full.contains("bestätigungspflichtig"))
        assertTrue(confirm.contains("Zusätzliche Hans-Bestätigungen können erforderlich sein"))
        assertFalse(confirm.contains("ohne zusätzliche Hans-Rückfragen"))
        listOf(full, confirm).forEach {
            assertTrue(it.contains("auf deinen Auftrag"))
            assertTrue(it.contains("Android-Berechtigungen"))
            assertTrue(it.contains("Zugriffsgrenzen bleiben bestehen"))
            assertTrue(it.contains("Hans bleibt rootfrei"))
        }
    }

    @Test
    fun finalCatalogNamesMatchBundledConversationalSkillContract() {
        assertEquals("hans_setup", HansSetupDynamicToolCatalog.namespace.name)
        assertEquals(
            listOf(
                "get_setup_state",
                "record_choice",
                "request_step_ui",
                "begin_key_capture",
                "read_key_capture",
                "begin_live_test",
                "read_live_test",
                "verify_step",
                "accept_existing_setup",
                "advance",
            ),
            HansSetupDynamicToolCatalog.namespace.tools.map { it.name },
        )
    }

    @Test
    fun recordChoiceSchemaAndStateExposeCanonicalTokensInsteadOfFreeText() {
        val recordChoice = HansSetupDynamicToolCatalog.namespace.tools.single {
            it.name == "record_choice"
        }
        val choiceSchema = JSONObject(recordChoice.inputSchemaJson)
            .getJSONObject("properties")
            .getJSONObject("choice")
        val schemaChoices = choiceSchema.getJSONArray("enum")
        assertTrue((0 until schemaChoices.length()).any { schemaChoices.getString(it) == "begin" })
        assertTrue((0 until schemaChoices.length()).any { schemaChoices.getString(it) == "yes" })

        val fixture = fixture()
        val intro = JSONObject(call(fixture.executor, "get_setup_state", "{}").contentText)
        assertEquals(
            listOf("begin", "defer"),
            (0 until intro.getJSONArray("allowedChoices").length()).map {
                intro.getJSONArray("allowedChoices").getString(it)
            },
        )

        fixture.repository.recordSimpleChoice(HansSetupStep.INTRO, "begin")
        val inputChoice = JSONObject(call(fixture.executor, "get_setup_state", "{}").contentText)
        assertEquals(
            listOf("hardware_toggle", "no_hardware_key", "defer"),
            (0 until inputChoice.getJSONArray("allowedChoices").length()).map {
                inputChoice.getJSONArray("allowedChoices").getString(it)
            },
        )
    }

    @Test
    fun naturalIntroConfirmationAliasesAdvanceWithoutASecondPrompt() {
        listOf("start", "yes", "ja").forEach { alias ->
            val fixture = fixture()
            val accepted = call(
                fixture.executor,
                "record_choice",
                """{"step":"intro","choice":"$alias"}""",
            )

            assertTrue(alias, accepted.success)
            assertEquals(alias, HansSetupStep.INPUT_CHOICE, fixture.repository.read().currentStep)
        }
    }

    @Test
    fun invalidChoiceDoesNotConsumeTheTurnAndCanonicalCorrectionSucceeds() {
        val fixture = fixture()
        val turnId = "turn_invalid_then_corrected"

        val invalid = call(
            fixture.executor,
            "record_choice",
            """{"step":"intro","choice":"invented"}""",
            turnId,
        )
        assertFalse(invalid.success)
        val invalidJson = JSONObject(invalid.contentText)
        assertEquals("invalid_setup_choice", invalidJson.getString("errorCode"))
        assertFalse(invalidJson.getBoolean("actionConsumed"))
        assertEquals("intro", invalidJson.getString("currentStep"))
        assertTrue(invalidJson.isNull("currentCapability"))
        assertEquals("begin", invalidJson.getJSONArray("allowedChoices").getString(0))

        val corrected = call(
            fixture.executor,
            "record_choice",
            """{"step":"intro","choice":"begin"}""",
            turnId,
        )
        assertTrue(corrected.success)
        assertEquals(HansSetupStep.INPUT_CHOICE, fixture.repository.read().currentStep)
    }

    @Test
    fun wrongToolDoesNotConsumeTheTurnAndStateProjectionGuidesCorrection() {
        val fixture = fixture()
        val turnId = "turn_wrong_tool_then_corrected"

        val wrongTool = call(
            fixture.executor,
            "request_step_ui",
            """{"step":"intro"}""",
            turnId,
        )
        assertFalse(wrongTool.success)
        val failure = JSONObject(wrongTool.contentText)
        assertFalse(failure.getBoolean("actionConsumed"))
        assertEquals("intro", failure.getString("currentStep"))

        assertTrue(
            call(
                fixture.executor,
                "record_choice",
                """{"step":"intro","choice":"begin"}""",
                turnId,
            ).success,
        )
    }

    @Test
    fun missingChoiceDoesNotConsumeTheTurnAndCanonicalCorrectionSucceeds() {
        val fixture = fixture()
        val turnId = "turn_missing_then_corrected"

        val missing = call(
            fixture.executor,
            "record_choice",
            """{"step":"intro"}""",
            turnId,
        )
        assertFalse(missing.success)
        assertFalse(JSONObject(missing.contentText).getBoolean("actionConsumed"))

        val corrected = call(
            fixture.executor,
            "record_choice",
            """{"step":"intro","choice":"begin"}""",
            turnId,
        )
        assertTrue(corrected.success)
        assertEquals(HansSetupStep.INPUT_CHOICE, fixture.repository.read().currentStep)
    }

    @Test
    fun retiredCameraChoiceIsRejectedBeforeAnyActivityIsNeeded() {
        val fixture = fixture(attachUi = false)
        reachCameraChoice(fixture.repository)
        val before = fixture.repository.read()
        val result = call(fixture.executor, "record_choice",
            """{"step":"camera_hold_choice","choice":"enabled"}""")
        assertFalse(result.success)
        assertEquals("setup_step_retired", JSONObject(result.contentText).getString("errorCode"))
        assertEquals(before, fixture.repository.read())
        assertEquals(HansSetupStep.APP_NOTIFICATIONS_CONSENT, before.currentStep)
    }

    @Test
    fun lostActivityCallbackCompletesDynamicToolAtRouterDeadline() {
        val deadlines = FakeDeadlineScheduler()
        val router = SetupUiCommandRouter(deadlines, requestTimeoutMillis = 1_234)
        router.attach(SetupUiCommandHandler { _, _, _ -> Unit })
        val fixture = fixture(attachUi = false, uiRouter = router)
        reachNotificationAccess(fixture.repository)
        var completed: DynamicToolExecutionResult? = null
        fixture.executor.execute(DynamicToolCallParams(
            threadId = "thread_setup", turnId = "turn_lost_activity_callback",
            callId = "call_lost_activity_callback", namespace = HansSetupDynamicToolCatalog.NAMESPACE,
            tool = "request_step_ui", argumentsJson = """{"step":"notification_access"}"""),
        ) { completed = it }
        assertNull(completed)
        assertEquals(listOf(1_234L), deadlines.pendingDelays())
        deadlines.runNext()
        val result = checkNotNull(completed)
        assertFalse(result.success)
        assertEquals("setup_ui_request_timeout", JSONObject(result.contentText).getString("errorCode"))
        assertEquals(HansSetupStep.NOTIFICATION_ACCESS, fixture.repository.read().currentStep)
        assertEquals(HansSetupStepStatus.AWAITING_USER,
            fixture.repository.read().record(HansSetupStep.NOTIFICATION_ACCESS).status)
    }

    @Test
    fun lateCameraReceiptAfterTimeoutCannotVerifySetupStep() {
        val deadlines = FakeDeadlineScheduler()
        val router = SetupUiCommandRouter(deadlines, requestTimeoutMillis = 1_234)
        var lateCompletion: ((SetupUiCommandResult) -> Unit)? = null
        router.attach(SetupUiCommandHandler { _, _, completion ->
            lateCompletion = completion
        })
        val fixture = fixture(attachUi = false, uiRouter = router)
        reachCameraCapture(fixture.repository)
        var completed: DynamicToolExecutionResult? = null

        fixture.executor.execute(
            DynamicToolCallParams(
                threadId = "thread_setup",
                turnId = "turn_late_camera_receipt",
                callId = "call_late_camera_receipt",
                namespace = HansSetupDynamicToolCatalog.NAMESPACE,
                tool = "begin_live_test",
                argumentsJson = """{"step":"camera_capture_test"}""",
            ),
        ) { completed = it }

        deadlines.runNext()
        assertFalse(checkNotNull(completed).success)
        assertEquals(
            HansSetupStepStatus.AWAITING_USER,
            fixture.repository.read().record(HansSetupStep.CAMERA_CAPTURE_TEST).status,
        )

        lateCompletion?.invoke(
            SetupUiCommandResult.Accepted(liveVerificationAccepted = true),
        )
        assertEquals(
            HansSetupStepStatus.AWAITING_USER,
            fixture.repository.read().record(HansSetupStep.CAMERA_CAPTURE_TEST).status,
        )
    }

    @Test
    fun winningCameraReceiptVerifiesInsideTheRouterCompletionBoundary() {
        val fixture = fixture(liveVerificationAccepted = true)
        reachCameraCapture(fixture.repository)

        val result = call(
            fixture.executor,
            "begin_live_test",
            """{"step":"camera_capture_test"}""",
        )

        assertTrue(result.success)
        assertEquals(
            HansSetupStepStatus.VERIFIED,
            fixture.repository.read().record(HansSetupStep.CAMERA_CAPTURE_TEST).status,
        )
    }

    @Test
    fun getStateDoesNotReopenNoHardwareChoiceOrChangeStoredNullableCameraChoice() {
        val fixture = fixture()
        reachNotificationAccess(fixture.repository)
        preserveVerifiedFreshState(fixture)
        val cameraChoiceBefore = fixture.repository.read().cameraHoldEnabled

        val result = call(fixture.executor, "get_setup_state", "{}")

        assertTrue(result.success)
        assertEquals(HansSetupStep.NOTIFICATION_ACCESS, fixture.repository.read().currentStep)
        assertEquals(
            HansSetupStepStatus.SKIPPED,
            fixture.repository.read().record(HansSetupStep.HARDWARE_MAPPING).status,
        )
        assertEquals(cameraChoiceBefore, fixture.repository.read().cameraHoldEnabled)
    }

    @Test
    fun getStateAdoptsExistingToggleMappingWithoutInventingLiveProof() {
        val fixture = fixture(initial = documentAt(HansSetupStep.INPUT_CHOICE))
        fixture.probe.configuredChoice = HansSetupInputChoice.HARDWARE_TOGGLE

        val result = call(fixture.executor, "get_setup_state", "{}")

        assertTrue(result.success)
        val state = fixture.repository.read()
        assertEquals(HansSetupInputChoice.HARDWARE_TOGGLE, state.inputChoice)
        assertEquals(
            HansSetupStepStatus.VERIFIED,
            state.record(HansSetupStep.INPUT_CHOICE).status,
        )
        assertEquals(
            HansSetupStepStatus.VERIFIED,
            state.record(HansSetupStep.HARDWARE_MAPPING).status,
        )
        assertEquals(
            HansSetupStepStatus.AWAITING_USER,
            state.record(HansSetupStep.HARDWARE_LIVE_TEST).status,
        )
        assertEquals(HansSetupStep.MICROPHONE_CONSENT, state.currentStep)
    }

    @Test
    fun legacyHoldMappingUsesEffectivePressControlEvenOnHoldIncompatibleDevice() {
        val fixture = fixture(initial = documentAt(HansSetupStep.INPUT_CHOICE))
        fixture.probe.configuredChoice = HansSetupInputChoice.HARDWARE_HOLD
        fixture.probe.holdCompatible = false
        call(fixture.executor, "get_setup_state", "{}")
        val state = fixture.repository.read()
        assertEquals(HansSetupInputChoice.HARDWARE_TOGGLE, state.inputChoice)
        assertEquals(HansSetupStepStatus.VERIFIED, state.record(HansSetupStep.HARDWARE_MAPPING).status)
        assertEquals(HansSetupStep.MICROPHONE_CONSENT, state.currentStep)
        assertEquals(HansSetupInputChoice.HARDWARE_HOLD, fixture.probe.configuredChoice)
    }

    @Test
    fun existingMappingNeverRequiresAForcedVoiceRecordingOrGestureChoice() {
        val fixture = fixture(initial = documentAt(HansSetupStep.INPUT_CHOICE))
        fixture.probe.configuredChoice = HansSetupInputChoice.HARDWARE_HOLD
        call(fixture.executor, "get_setup_state", "{}")
        val state = fixture.repository.read()
        assertEquals(HansSetupInputChoice.HARDWARE_TOGGLE, state.inputChoice)
        assertEquals(HansSetupStepStatus.VERIFIED, state.record(HansSetupStep.HARDWARE_MAPPING).status)
        val result = call(fixture.executor, "begin_live_test", """{"step":"hardware_live_test"}""")
        assertFalse(result.success)
        assertEquals("setup_step_retired", JSONObject(result.contentText).getString("errorCode"))
    }

    @Test
    fun freshExistingAccessAdoptsConsentButMissingAccessIsNotARefusal() {
        val fixture = fixture()
        fixture.probe.results[HansSetupStep.MICROPHONE_ACCESS] =
            HansSetupProbeResult(true, "microphone_permission_granted")
        fixture.probe.results[HansSetupStep.ACCESSIBILITY_ACCESS] =
            HansSetupProbeResult(true, "accessibility_access_granted")

        call(fixture.executor, "get_setup_state", "{}")

        val state = fixture.repository.read()
        listOf(
            HansSetupStep.MICROPHONE_CONSENT,
            HansSetupStep.MICROPHONE_ACCESS,
            HansSetupStep.ACCESSIBILITY_CONSENT,
            HansSetupStep.ACCESSIBILITY_ACCESS,
        ).forEach { step ->
            assertEquals(step.name, HansSetupStepStatus.VERIFIED, state.record(step).status)
        }
        assertEquals(
            HansSetupStepStatus.AWAITING_USER,
            state.record(HansSetupStep.HOME_ROLE_CONSENT).status,
        )
        assertEquals(
            HansSetupStepStatus.AWAITING_USER,
            state.record(HansSetupStep.HOME_ROLE).status,
        )
    }

    @Test
    fun explicitCoreNotNowIsNotOverwrittenByAnEffectiveAndroidGrant() {
        val fixture = fixture()
        fixture.repository.startOrResume()
        fixture.repository.recordSimpleChoice(HansSetupStep.INTRO, "begin")
        fixture.repository.recordSimpleChoice(HansSetupStep.INPUT_CHOICE, "no_hardware_key")
        fixture.repository.recordSimpleChoice(HansSetupStep.MICROPHONE_CONSENT, "not_now")
        fixture.probe.results[HansSetupStep.MICROPHONE_ACCESS] =
            HansSetupProbeResult(true, "microphone_permission_granted")

        call(fixture.executor, "get_setup_state", "{}")

        val state = fixture.repository.read()
        assertEquals(
            HansSetupStepStatus.SKIPPED,
            state.record(HansSetupStep.MICROPHONE_CONSENT).status,
        )
        assertEquals(
            HansSetupStepStatus.SKIPPED,
            state.record(HansSetupStep.MICROPHONE_ACCESS).status,
        )
    }

    @Test
    fun previouslyVerifiedCoreAccessStillReopensWhenFreshProbeIsMissing() {
        val fixture = fixture(initial = documentAt(HansSetupStep.CAMERA_CAPTURE_TEST))
        fixture.probe.configuredChoice = HansSetupInputChoice.HARDWARE_TOGGLE
        fixture.probe.results[HansSetupStep.MICROPHONE_ACCESS] =
            HansSetupProbeResult(false, "microphone_permission_missing")

        call(fixture.executor, "get_setup_state", "{}")

        val state = fixture.repository.read()
        assertEquals(HansSetupStep.MICROPHONE_ACCESS, state.currentStep)
        assertEquals(
            HansSetupStepStatus.AWAITING_USER,
            state.record(HansSetupStep.MICROPHONE_ACCESS).status,
        )
        assertEquals(
            HansSetupStepStatus.VERIFIED,
            state.record(HansSetupStep.MICROPHONE_CONSENT).status,
        )
    }

    @Test
    fun cameraHoldIsNeitherAdoptedNorOverwrittenBySetupRepair() {
        val fixture = fixture(initial = HansSetupDocument(cameraHoldEnabled = true))
        fixture.probe.cameraHoldEnabled = false
        call(fixture.executor, "get_setup_state", "{}")
        assertEquals(true, fixture.repository.read().cameraHoldEnabled)
        assertFalse(fixture.repository.read().steps.containsKey(HansSetupStep.CAMERA_HOLD_CHOICE))
        assertFalse(fixture.repository.read().steps.containsKey(HansSetupStep.CAMERA_HOLD_LIVE_TEST))
    }

    @Test
    fun explicitExistingSetupAcceptancePreservesProofAndSkipsUnprovenTests() {
        val fixture = fixture(initial = documentAt(HansSetupStep.INPUT_CHOICE))
        fixture.probe.configuredChoice = HansSetupInputChoice.HARDWARE_TOGGLE
        fixture.probe.results[HansSetupStep.MICROPHONE_ACCESS] =
            HansSetupProbeResult(true, "microphone_permission_granted")

        val result = call(
            fixture.executor,
            "accept_existing_setup",
            """{"explicitUserConfirmation":true}""",
        )

        assertTrue(result.success)
        val state = fixture.repository.read()
        assertTrue(state.complete)
        assertEquals(
            HansSetupStepStatus.VERIFIED,
            state.record(HansSetupStep.HARDWARE_MAPPING).status,
        )
        assertEquals(
            HansSetupStepStatus.VERIFIED,
            state.record(HansSetupStep.MICROPHONE_ACCESS).status,
        )
        assertFalse(state.steps.containsKey(HansSetupStep.HARDWARE_LIVE_TEST))
        assertEquals(
            HansSetupStepStatus.SKIPPED,
            state.record(HansSetupStep.ACCESSIBILITY_ACCESS).status,
        )
        assertEquals(
            HansSetupStepStatus.VERIFIED,
            state.record(HansSetupStep.REVIEW).status,
        )
    }

    @Test
    fun temporaryAccessibilityReadFailureDoesNotReopenCompletedSetupOrDiscardProof() {
        val fixture = completedFixture()
        fixture.probe.results[HansSetupStep.ACCESSIBILITY_ACCESS] =
            accessibilitySetupGrantProbe(AccessibilityGrantState.UNKNOWN)

        val state = fixture.executor.refreshFreshEvidence()

        assertTrue(state.complete)
        listOf(
            HansSetupStep.ACCESSIBILITY_ACCESS,
            HansSetupStep.ACCESSIBILITY_LIVE_TEST,
            HansSetupStep.REVIEW,
        ).forEach { step ->
            assertEquals(step.name, HansSetupStepStatus.VERIFIED, state.record(step).status)
        }
    }

    @Test
    fun transientReadDoesNotGrantAnUnconfiguredAccessibilityStep() {
        val fixture = fixture(initial = documentAt(HansSetupStep.ACCESSIBILITY_ACCESS))
        preserveVerifiedFreshState(fixture)
        fixture.probe.results[HansSetupStep.ACCESSIBILITY_ACCESS] =
            accessibilitySetupGrantProbe(AccessibilityGrantState.UNKNOWN)

        val state = fixture.executor.refreshFreshEvidence()

        assertFalse(state.complete)
        assertEquals(HansSetupStep.ACCESSIBILITY_ACCESS, state.currentStep)
        assertEquals(
            HansSetupStepStatus.AWAITING_USER,
            state.record(HansSetupStep.ACCESSIBILITY_ACCESS).status,
        )
    }

    @Test
    fun actualAccessibilityRevocationStillReopensAccessAndItsVerifiedLiveTests() {
        val fixture = completedFixture()
        fixture.probe.results[HansSetupStep.ACCESSIBILITY_ACCESS] =
            accessibilitySetupGrantProbe(AccessibilityGrantState.NOT_GRANTED)

        val revoked = fixture.executor.refreshFreshEvidence()

        assertFalse(revoked.complete)
        assertEquals(HansSetupStep.ACCESSIBILITY_ACCESS, revoked.currentStep)
        listOf(
            HansSetupStep.ACCESSIBILITY_ACCESS,
            HansSetupStep.ACCESSIBILITY_LIVE_TEST,
        ).forEach { step ->
            assertEquals(step.name, HansSetupStepStatus.AWAITING_USER, revoked.record(step).status)
        }

        fixture.probe.results[HansSetupStep.ACCESSIBILITY_ACCESS] =
            accessibilitySetupGrantProbe(AccessibilityGrantState.GRANTED)
        val restored = fixture.executor.refreshFreshEvidence()
        assertEquals(
            HansSetupStepStatus.VERIFIED,
            restored.record(HansSetupStep.ACCESSIBILITY_ACCESS).status,
        )
        assertEquals(HansSetupStep.ACCESSIBILITY_LIVE_TEST, restored.currentStep)
        assertFalse(restored.complete)
    }

    @Test
    fun durableGrantPreservesAcceptedExistingSetupWhileAndroidRebindsTheService() {
        val fixture = completedFixture(
            skipped = setOf(
                HansSetupStep.ACCESSIBILITY_LIVE_TEST,
                    HansSetupStep.PERSONAL_PROFILE,
            ),
        )
        // A durable grant is independent of the service's temporary bound/session state.
        fixture.probe.results[HansSetupStep.ACCESSIBILITY_ACCESS] =
            accessibilitySetupGrantProbe(AccessibilityGrantState.GRANTED)

        val state = fixture.executor.refreshFreshEvidence()

        assertTrue(state.complete)
        assertEquals(
            HansSetupStepStatus.VERIFIED,
            state.record(HansSetupStep.ACCESSIBILITY_ACCESS).status,
        )
        listOf(
            HansSetupStep.ACCESSIBILITY_LIVE_TEST,
            HansSetupStep.PERSONAL_PROFILE,
        ).forEach { step ->
            assertEquals(step.name, HansSetupStepStatus.SKIPPED, state.record(step).status)
        }
    }

    @Test
    fun apiCredentialAvailabilityIsNotProbedAndNeverReopensCompletedSetup() {
        val fixture = completedFixture()
        fixture.probe.results[HansSetupStep.SPEECH_CREDENTIAL_ACCESS] =
            HansSetupProbeResult(false, "speech_credential_missing")
        assertTrue(fixture.executor.refreshFreshEvidence().complete)
        assertFalse(fixture.probe.probedSteps.contains(HansSetupStep.SPEECH_CREDENTIAL_ACCESS))
        assertFalse(fixture.probe.probedSteps.contains(HansSetupStep.CAMERA_HOLD_CHOICE))
    }

    @Test
    fun fullAccessPreservesCompletedEverydayAccessWithoutDurableBundle() {
        val fixture = completedEverydayAccessFixture()
        var bundleReads = 0
        val fresh = everydayAccessSetupProbe(HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS) {
            bundleReads += 1
            false
        }
        fixture.probe.optionalResults[HansSetupOptionalCapability.EVERYDAY_ACCESS] = fresh

        val state = fixture.executor.refreshFreshEvidence()

        assertTrue(state.complete)
        assertEquals(0, bundleReads)
        assertEquals("everyday_access_full_access_policy", fresh.detailCode)
        assertEquals(
            HansSetupOptionalCapabilityRecord(
                HansSetupCapabilityDecision.ENABLE,
                HansSetupStepStatus.VERIFIED,
                effective = true,
            ),
            state.optionalCapabilityRecord(HansSetupOptionalCapability.EVERYDAY_ACCESS),
        )
    }

    @Test
    fun confirmationModeStillReopensMissingEverydayBundle() {
        val fixture = completedEverydayAccessFixture()
        val fresh = everydayAccessSetupProbe(HansPhoneActionPolicy.CONFIRM_ACTIONS) { false }
        fixture.probe.optionalResults[HansSetupOptionalCapability.EVERYDAY_ACCESS] = fresh

        val state = fixture.executor.refreshFreshEvidence()

        assertFalse(state.complete)
        assertFalse(fresh.verified)
        assertEquals("everyday_access_bundle_missing", fresh.detailCode)
        assertEquals(HansSetupStep.OPTIONAL_CAPABILITIES, state.currentStep)
        assertEquals(
            HansSetupStepStatus.AWAITING_USER,
            state.optionalCapabilityRecord(HansSetupOptionalCapability.EVERYDAY_ACCESS).status,
        )
    }

    @Test
    fun fullAccessPreservesExplicitEverydayAccessNotNowChoice() {
        val fixture = completedFixture()
        val previous = fixture.repository.read()
            .optionalCapabilityRecord(HansSetupOptionalCapability.EVERYDAY_ACCESS)
        assertEquals(HansSetupCapabilityDecision.NOT_NOW, previous.decision)
        fixture.probe.optionalResults[HansSetupOptionalCapability.EVERYDAY_ACCESS] =
            everydayAccessSetupProbe(HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS) { false }

        val state = fixture.executor.refreshFreshEvidence()

        assertTrue(state.complete)
        assertEquals(
            previous,
            state.optionalCapabilityRecord(HansSetupOptionalCapability.EVERYDAY_ACCESS),
        )
    }

    @Test
    fun fullAccessDoesNotMaskMicrophoneRevocationOrItsRequiredLiveTests() {
        val fixture = completedEverydayAccessFixture()
        fixture.probe.optionalResults[HansSetupOptionalCapability.EVERYDAY_ACCESS] =
            everydayAccessSetupProbe(HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS) { false }
        fixture.probe.results[HansSetupStep.MICROPHONE_ACCESS] =
            HansSetupProbeResult(false, "microphone_permission_missing")

        val state = fixture.executor.refreshFreshEvidence()

        assertFalse(state.complete)
        assertEquals(
            HansSetupStepStatus.VERIFIED,
            state.optionalCapabilityRecord(HansSetupOptionalCapability.EVERYDAY_ACCESS).status,
        )
        listOf(
            HansSetupStep.MICROPHONE_ACCESS,
        ).forEach { step ->
            assertEquals(step.name, HansSetupStepStatus.AWAITING_USER, state.record(step).status)
        }
    }

    @Test
    fun fullAccessDoesNotMaskAccessibilityRevocationOrReplaceItsLiveProof() {
        val fixture = completedEverydayAccessFixture()
        fixture.probe.optionalResults[HansSetupOptionalCapability.EVERYDAY_ACCESS] =
            everydayAccessSetupProbe(HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS) { false }
        fixture.probe.results[HansSetupStep.ACCESSIBILITY_ACCESS] =
            accessibilitySetupGrantProbe(AccessibilityGrantState.NOT_GRANTED)

        val revoked = fixture.executor.refreshFreshEvidence()

        assertFalse(revoked.complete)
        assertEquals(
            HansSetupStepStatus.VERIFIED,
            revoked.optionalCapabilityRecord(HansSetupOptionalCapability.EVERYDAY_ACCESS).status,
        )
        listOf(
            HansSetupStep.ACCESSIBILITY_ACCESS,
            HansSetupStep.ACCESSIBILITY_LIVE_TEST,
        ).forEach { step ->
            assertEquals(step.name, HansSetupStepStatus.AWAITING_USER, revoked.record(step).status)
        }

        fixture.probe.results[HansSetupStep.ACCESSIBILITY_ACCESS] =
            accessibilitySetupGrantProbe(AccessibilityGrantState.GRANTED)
        val restored = fixture.executor.refreshFreshEvidence()

        assertEquals(
            HansSetupStepStatus.VERIFIED,
            restored.record(HansSetupStep.ACCESSIBILITY_ACCESS).status,
        )
        assertEquals(
            HansSetupStepStatus.AWAITING_USER,
            restored.record(HansSetupStep.ACCESSIBILITY_LIVE_TEST).status,
        )
        assertFalse(restored.complete)
    }

    @Test
    fun transientOptionalCapabilityProbePreservesProofButDefiniteRevocationDoesNot() {
        val initial = completedFixture().repository.read()
        // A completed setup may have explicitly selected this capability before the restart.
        val enabled = fixture(
            initial = initial.copy(
                optionalCapabilities = initial.optionalCapabilities +
                    (HansSetupOptionalCapability.LOCATION to HansSetupOptionalCapabilityRecord(
                        decision = HansSetupCapabilityDecision.ENABLE,
                        status = HansSetupStepStatus.VERIFIED,
                        effective = true,
                    )),
            ),
        )
        preserveVerifiedFreshState(enabled)
        enabled.probe.optionalResults[HansSetupOptionalCapability.LOCATION] =
            HansSetupProbeResult(false, "location_probe_temporarily_unavailable", transient = true)

        assertTrue(enabled.executor.refreshFreshEvidence().complete)
        assertEquals(
            HansSetupStepStatus.VERIFIED,
            enabled.repository.read().optionalCapabilityRecord(HansSetupOptionalCapability.LOCATION)
                .status,
        )

        enabled.probe.optionalResults[HansSetupOptionalCapability.LOCATION] =
            HansSetupProbeResult(false, "location_permission_missing")
        assertFalse(enabled.executor.refreshFreshEvidence().complete)
        assertEquals(
            HansSetupStepStatus.AWAITING_USER,
            enabled.repository.read().optionalCapabilityRecord(HansSetupOptionalCapability.LOCATION)
                .status,
        )
    }

    @Test
    fun explicitExistingSetupAcceptancePreservesFreshActiveOptionalCapability() {
        val fixture = fixture()
        fixture.probe.optionalResults[HansSetupOptionalCapability.LOCATION] =
            HansSetupProbeResult(true, "location_permission_granted")

        val result = call(
            fixture.executor,
            "accept_existing_setup",
            """{"explicitUserConfirmation":true}""",
        )

        assertTrue(result.success)
        val state = fixture.repository.read()
        assertTrue(state.complete)
        assertEquals(
            HansSetupCapabilityDecision.ENABLE,
            state.optionalCapabilityRecord(HansSetupOptionalCapability.LOCATION).decision,
        )
        assertEquals(
            HansSetupStepStatus.VERIFIED,
            state.optionalCapabilityRecord(HansSetupOptionalCapability.LOCATION).status,
        )
        assertEquals(
            true,
            state.optionalCapabilityRecord(HansSetupOptionalCapability.LOCATION).effective,
        )
        assertEquals(
            HansSetupStepStatus.SKIPPED,
            state.optionalCapabilityRecord(HansSetupOptionalCapability.CONTACTS).status,
        )
    }

    @Test
    fun existingSetupAcceptanceRequiresExactBooleanAndDoesNotConsumeRejectedTurn() {
        val fixture = fixture()
        val turnId = "turn_existing_setup_confirmation"

        val rejected = call(
            fixture.executor,
            "accept_existing_setup",
            """{"explicitUserConfirmation":false}""",
            turnId,
        )

        assertFalse(rejected.success)
        assertEquals(
            "setup_existing_configuration_confirmation_required",
            JSONObject(rejected.contentText).getString("errorCode"),
        )
        assertFalse(JSONObject(rejected.contentText).getBoolean("actionConsumed"))
        assertFalse(fixture.repository.read().complete)

        val accepted = call(
            fixture.executor,
            "accept_existing_setup",
            """{"explicitUserConfirmation":true}""",
            turnId,
        )
        assertTrue(accepted.success)
        assertTrue(fixture.repository.read().complete)
    }

    @Test
    fun lifecycleRefreshUsesLegacyMappingAsPressWithoutReopeningRemovedHoldTest() {
        val fixture = fixture(initial = documentAt(HansSetupStep.HOME_ROLE_CONSENT).copy(
            inputChoice = HansSetupInputChoice.HARDWARE_HOLD))
        preserveVerifiedFreshState(fixture)
        fixture.probe.holdCompatible = false
        fixture.executor.refreshFreshEvidence()
        assertEquals(HansSetupStep.HOME_ROLE_CONSENT, fixture.repository.read().currentStep)
        assertEquals(HansSetupInputChoice.HARDWARE_TOGGLE, fixture.repository.read().inputChoice)
        assertEquals(HansSetupStepStatus.VERIFIED, fixture.repository.read().record(HansSetupStep.HARDWARE_MAPPING).status)
    }

    @Test
    fun settingsRequestRemainsUnverifiedUntilFreshProbe() {
        val fixture = fixture()
        reachNotificationAccess(fixture.repository)

        val opened = call(
            fixture.executor,
            "request_step_ui",
            """{"step":"notification_access"}""",
        )
        assertTrue(opened.success)
        assertEquals(
            "settings_opened",
            JSONObject(opened.contentText).getString("currentStepStatus"),
        )

        fixture.probe.results[HansSetupStep.NOTIFICATION_ACCESS] =
            HansSetupProbeResult(true, "notification_access_granted")
        val verified = call(
            fixture.executor,
            "verify_step",
            """{"step":"notification_access"}""",
        )
        assertTrue(verified.success)
        assertEquals(HansSetupStep.NOTIFICATION_LIVE_TEST, fixture.repository.read().currentStep)
    }

    @Test
    fun settingsRequestDoesNotClaimOpenedWhenAndroidRouteFailed() {
        val fixture = fixture(settingsRouteOpened = false)
        reachNotificationAccess(fixture.repository)

        val result = call(
            fixture.executor,
            "request_step_ui",
            """{"step":"notification_access"}""",
        )

        assertFalse(result.success)
        assertEquals("settings_ui_not_opened", JSONObject(result.contentText).getString("errorCode"))
        assertEquals(
            HansSetupStepStatus.AWAITING_USER,
            fixture.repository.read().record(HansSetupStep.NOTIFICATION_ACCESS).status,
        )
        assertEquals(
            "settings_ui_not_opened",
            fixture.repository.read().record(HansSetupStep.NOTIFICATION_ACCESS).detailCode,
        )
    }

    @Test
    fun runtimePermissionDenialTerminallySkipsAccessAndDependentLiveTest() {
        val fixture = fixture(
            initial = documentAt(HansSetupStep.APP_NOTIFICATIONS_ACCESS),
            liveVerificationAccepted = false,
        )

        val result = call(
            fixture.executor,
            "request_step_ui",
            """{"step":"app_notifications_access"}""",
        )

        assertTrue(result.success)
        assertEquals(
            HansSetupStepStatus.SKIPPED,
            fixture.repository.read().record(HansSetupStep.APP_NOTIFICATIONS_ACCESS).status,
        )
        assertEquals(
            HansSetupStepStatus.SKIPPED,
            fixture.repository.read().record(HansSetupStep.NOTIFICATION_LIVE_TEST).status,
        )
        assertEquals(
            HansSetupStep.NOTIFICATION_LISTENER_CONSENT,
            fixture.repository.read().currentStep,
        )
    }

    @Test
    fun notificationLiveTestRequiresFreshNonceBoundIngressReceipt() {
        val fixture = fixture()
        reachNotificationLiveTest(fixture.repository)
        fixture.probe.results[HansSetupStep.NOTIFICATION_LIVE_TEST] =
            HansSetupProbeResult(false, "notification_ingress_receipt_missing", transient = true)

        assertTrue(
            call(
                fixture.executor,
                "begin_live_test",
                """{"step":"notification_live_test"}""",
            ).success,
        )
        val waiting = call(
            fixture.executor,
            "read_live_test",
            """{"step":"notification_live_test"}""",
        )
        assertTrue(waiting.success)
        assertEquals(
            HansSetupStepStatus.VERIFYING,
            fixture.repository.read().record(HansSetupStep.NOTIFICATION_LIVE_TEST).status,
        )

        fixture.probe.results[HansSetupStep.NOTIFICATION_LIVE_TEST] =
            HansSetupProbeResult(true, "notification_ingress_receipt_observed")
        assertTrue(
            call(
                fixture.executor,
                "read_live_test",
                """{"step":"notification_live_test"}""",
            ).success,
        )
        assertEquals(HansSetupStep.ACCESSIBILITY_CONSENT, fixture.repository.read().currentStep)
    }

    @Test
    fun readLiveTestIsStableAfterARequiredServiceDisappears() {
        val fixture = fixture()
        reachNotificationLiveTest(fixture.repository)
        fixture.probe.results[HansSetupStep.NOTIFICATION_LIVE_TEST] =
            HansSetupProbeResult(false, "notification_listener_disconnected")

        call(
            fixture.executor,
            "begin_live_test",
            """{"step":"notification_live_test"}""",
        )
        assertTrue(
            call(
                fixture.executor,
                "read_live_test",
                """{"step":"notification_live_test"}""",
            ).success,
        )
        assertEquals(
            HansSetupStepStatus.AWAITING_USER,
            fixture.repository.read().record(HansSetupStep.NOTIFICATION_LIVE_TEST).status,
        )

        val reread = call(
            fixture.executor,
            "read_live_test",
            """{"step":"notification_live_test"}""",
        )
        assertTrue(reread.success)
        assertEquals(
            HansSetupStepStatus.AWAITING_USER,
            fixture.repository.read().record(HansSetupStep.NOTIFICATION_LIVE_TEST).status,
        )
    }

    @Test
    fun extraArgumentsAndOldReadStateNameFailClosed() {
        val fixture = fixture()
        assertFalse(call(fixture.executor, "get_setup_state", """{"extra":true}""").success)
        assertFalse(call(fixture.executor, "read_state", "{}").success)
    }

    @Test
    fun oneSideEffectPerTurnIsEnforcedWhileReadOnlyStateRemainsAvailable() {
        val fixture = fixture()
        val turnId = "turn_single_action"

        assertTrue(
            call(
                fixture.executor,
                "record_choice",
                """{"step":"intro","choice":"begin"}""",
                turnId,
            ).success,
        )
        val rejected = call(
            fixture.executor,
            "record_choice",
            """{"step":"input_choice","choice":"no_hardware_key"}""",
            turnId,
        )
        assertFalse(rejected.success)
        val rejectedJson = JSONObject(rejected.contentText)
        assertEquals(
            "setup_profile_turn_action_limit",
            rejectedJson.getString("errorCode"),
        )
        assertTrue(rejectedJson.getString("message").contains("one setup or profile change"))

        val read = call(fixture.executor, "get_setup_state", "{}", turnId)
        assertTrue(read.success)
        assertEquals("input_choice", JSONObject(read.contentText).getString("currentStep"))
    }

    @Test
    fun setupMutationAlsoBlocksProfileMutationInTheSameTurn() {
        val guard = SetupProfileTurnActionGuard()
        val fixture = fixture(turnActionGuard = guard)
        val profileRepository = UserProfileRepository(
            storage = MemoryProfileStorage(),
            clock = ProfileClock { 42 },
            nonces = ProfileNonceSource { "profile_nonce_123456789" },
        )
        val profileExecutor = UserProfileDynamicToolExecutor(
            repository = profileRepository,
            backgroundExecutor = Executor(Runnable::run),
            turnActionGuard = guard,
        )
        val turnId = "turn_cross_namespace"

        assertTrue(
            call(
                fixture.executor,
                "record_choice",
                """{"step":"intro","choice":"begin"}""",
                turnId,
            ).success,
        )
        var profileResult: DynamicToolExecutionResult? = null
        profileExecutor.execute(
            DynamicToolCallParams(
                threadId = "thread_setup",
                turnId = turnId,
                callId = "call_profile_cross_namespace",
                namespace = UserProfileDynamicToolCatalog.NAMESPACE,
                tool = "begin_interview",
                argumentsJson = "{}",
            ),
        ) { profileResult = it }

        assertFalse(checkNotNull(profileResult).success)
        assertFalse(profileRepository.read().interviewActive)
    }

    @Test
    fun setupProjectionOmitsHistoricalStepsRawCodesAndInternalOperationNonces() {
        val fixture = fixture()
        fixture.repository.startOrResume()
        val state = JSONObject(call(fixture.executor, "get_setup_state", "{}").contentText)

        assertFalse(state.has("steps"))
        assertFalse(state.has("revision"))
        assertFalse(state.has("detailCode"))
        assertFalse(state.has("operationNonce"))
        assertTrue(state.has("allowedChoices"))
        assertEquals("intro", state.getString("currentStep"))
        assertEquals("awaiting_user", state.getString("currentStepStatus"))
    }

    @Test
    fun optionalCapabilitiesAreExposedAndProvenOneAtATime() {
        val fixture = fixture(initial = documentAt(HansSetupStep.OPTIONAL_CAPABILITIES))
        preserveVerifiedFreshState(fixture)

        val initial = JSONObject(call(fixture.executor, "get_setup_state", "{}").contentText)
        assertEquals("everyday_access", initial.getString("currentCapability"))
        assertTrue(
            call(
                fixture.executor,
                "record_choice",
                """{"step":"optional_capabilities","capability":"everyday_access","choice":"yes"}""",
            ).success,
        )
        assertTrue(
            call(
                fixture.executor,
                "request_step_ui",
                """{"step":"optional_capabilities","capability":"everyday_access"}""",
            ).success,
        )
        fixture.probe.optionalResults[HansSetupOptionalCapability.EVERYDAY_ACCESS] =
            HansSetupProbeResult(true, "everyday_access_bundle_active")
        val verified = call(
            fixture.executor,
            "verify_step",
            """{"step":"optional_capabilities","capability":"everyday_access"}""",
        )
        assertTrue(verified.success)
        assertEquals(
            "notification_link_metadata",
            JSONObject(verified.contentText).getString("currentCapability"),
        )

        val wrongCapability = call(
            fixture.executor,
            "record_choice",
            """{"step":"optional_capabilities","capability":"location","choice":"not_now"}""",
        )
        assertFalse(wrongCapability.success)
    }

    @Test
    fun everyRetiredStepIsAbsentFromSchemaAndRejectedWithoutLaunchingAnything() {
        val fixture = fixture(initial = documentAt(HansSetupStep.MODEL_REASONING), attachUi = false)
        val before = fixture.repository.read()
        HANS_SETUP_RETIRED_STEPS.forEach { step ->
            listOf("request_step_ui", "begin_live_test", "read_live_test", "verify_step").forEach { tool ->
                val result = call(fixture.executor, tool, JSONObject().put("step", wireStep(step)).toString())
                assertFalse(result.success)
                assertEquals("setup_step_retired", JSONObject(result.contentText).getString("errorCode"))
                assertEquals(before, fixture.repository.read())
            }
        }
        val schema = HansSetupDynamicToolCatalog.namespace.tools
            .single { it.name == "record_choice" }.inputSchemaJson
        HANS_SETUP_RETIRED_STEPS.forEach { assertFalse(schema.contains(wireStep(it))) }
        assertFalse(schema.contains("hardware_hold"))
    }

    @Test
    fun reviewTechnicalSummaryIsBoundedAndContainsNoRawCodesNoncesOrTimestamps() {
        val fixture = fixture(initial = documentAtReview())
        preserveVerifiedFreshState(fixture)
        val result = JSONObject(call(fixture.executor, "get_setup_state", "{}").contentText)
        val summary = result.getJSONObject("technicalSummary")
        val encoded = summary.toString()

        assertEquals("confirm_actions", result.getString("phoneActionPolicy"))
        assertEquals("confirm_actions", summary.getString("phoneActionPolicy"))
        assertTrue(summary.getJSONArray("permissions").length() <= 5)
        assertEquals(
            HansSetupOptionalCapability.entries.size,
            summary.getJSONArray("optionalCapabilities").length(),
        )
        assertEquals(
            "chatgpt_login",
            summary.getJSONObject("speech").getString("access"),
        )
        assertFalse(encoded.contains("detailCode"))
        assertFalse(encoded.contains("operationNonce"))
        assertFalse(encoded.contains("requestedAtMillis"))
        assertFalse(encoded.contains("verifiedAtMillis"))
        assertFalse(encoded.contains("prerequisite_verified"))
        assertFalse(encoded.contains("setup_nonce"))
    }

    @Test
    fun reviewToolRequiresPreparedFreshNonceAndExplicitConfirmation() {
        val fixture = fixture(initial = documentAtReview())

        val uncorrelated = call(
            fixture.executor,
            "record_choice",
            """{"step":"review","choice":"confirm"}""",
        )
        assertFalse(uncorrelated.success)

        val prepared = call(
            fixture.executor,
            "record_choice",
            """{"step":"review","choice":"prepare_confirmation"}""",
        )
        val preparedJson = JSONObject(prepared.contentText)
        val nonce = preparedJson.getString("confirmationNonce")
        assertFalse(preparedJson.has("operationNonce"))
        assertFalse(preparedJson.has("steps"))

        val rejected = call(
            fixture.executor,
            "record_choice",
            JSONObject()
                .put("step", "review")
                .put("choice", "confirm")
                .put("confirmationNonce", nonce)
                .put("explicitUserConfirmation", false)
                .toString(),
        )
        assertFalse(rejected.success)
        val confirmed = call(
            fixture.executor,
            "record_choice",
            JSONObject()
                .put("step", "review")
                .put("choice", "confirm")
                .put("confirmationNonce", nonce)
                .put("explicitUserConfirmation", true)
                .toString(),
        )
        assertTrue(confirmed.success)
        assertTrue(JSONObject(confirmed.contentText).getBoolean("complete"))
    }

    @Test
    fun modelStepCanBeExplicitlyDeferredOrFinishedWithNotNow() {
        val fixture = fixture(initial = documentAt(HansSetupStep.MODEL_REASONING))

        val deferred = call(
            fixture.executor,
            "record_choice",
            """{"step":"model_reasoning","choice":"defer"}""",
        )
        assertTrue(deferred.success)
        assertEquals(HansSetupStep.MODEL_REASONING, fixture.repository.read().currentStep)

        val notNow = call(
            fixture.executor,
            "record_choice",
            """{"step":"model_reasoning","choice":"not_now"}""",
        )
        assertTrue(notNow.success)
        assertEquals(
            HansSetupStepStatus.SKIPPED,
            fixture.repository.read().record(HansSetupStep.MODEL_REASONING).status,
        )
        assertEquals(HansSetupStep.OPTIONAL_CAPABILITIES, fixture.repository.read().currentStep)
    }

    private fun fixture(
        attachUi: Boolean = true,
        initial: HansSetupDocument = HansSetupDocument(),
        settingsRouteOpened: Boolean = true,
        liveVerificationAccepted: Boolean? = null,
        turnActionGuard: SetupProfileTurnActionGuard = SetupProfileTurnActionGuard(),
        uiRouter: SetupUiCommandRouter = SetupUiCommandRouter(),
        actionPolicy: HansPhoneActionPolicy = HansPhoneActionPolicy.CONFIRM_ACTIONS,
    ): Fixture {
        val repository = HansSetupRepository(
            storage = MemoryStorage(initial),
            clock = HansSetupClock { 42 },
            nonces = HansSetupNonceSource { "setup_nonce_123456789" },
        )
        val probe = FakeProbe()
        val router = uiRouter
        if (attachUi) {
            router.attach(SetupUiCommandHandler { command, _, completion ->
                completion(
                    SetupUiCommandResult.Accepted(
                        settingsOpened = command !is SetupUiCommand.OpenSettings ||
                            settingsRouteOpened,
                        liveVerificationAccepted = liveVerificationAccepted,
                    ),
                )
            })
        }
        return Fixture(
            repository,
            probe,
            HansSetupDynamicToolExecutor(
                repository,
                probe,
                router,
                Executor(Runnable::run),
                turnActionGuard,
                actionPolicy = actionPolicy,
            ),
        )
    }

    private fun reachCameraChoice(repository: HansSetupRepository) {
        reachCameraCapture(repository)
        repository.recordSimpleChoice(HansSetupStep.CAMERA_CAPTURE_TEST, "not_now")
    }

    private fun reachCameraCapture(repository: HansSetupRepository) {
        repository.startOrResume()
        repository.recordSimpleChoice(HansSetupStep.INTRO, "begin")
        repository.recordSimpleChoice(HansSetupStep.INPUT_CHOICE, "no_hardware_key")
        repository.recordSimpleChoice(HansSetupStep.MICROPHONE_CONSENT, "enable")
        val microphone = repository.beginOperation(HansSetupStep.MICROPHONE_ACCESS)
        repository.applyFreshProbe(
            microphone,
            HansSetupProbeResult(true, "microphone_permission_granted"),
        )
    }

    private fun reachNotificationAccess(repository: HansSetupRepository) {
        reachCameraChoice(repository)
        repository.recordSimpleChoice(HansSetupStep.APP_NOTIFICATIONS_CONSENT, "enable")
        val appNotifications = repository.beginOperation(HansSetupStep.APP_NOTIFICATIONS_ACCESS)
        repository.applyFreshProbe(
            appNotifications,
            HansSetupProbeResult(true, "app_notification_permission_granted"),
        )
        repository.recordSimpleChoice(
            HansSetupStep.NOTIFICATION_LISTENER_CONSENT,
            "enable",
        )
    }

    private fun reachNotificationLiveTest(repository: HansSetupRepository) {
        reachNotificationAccess(repository)
        val access = repository.beginOperation(HansSetupStep.NOTIFICATION_ACCESS)
        repository.applyFreshProbe(
            access,
            HansSetupProbeResult(true, "notification_access_granted"),
        )
    }

    private fun preserveVerifiedFreshState(fixture: Fixture) {
        val current = fixture.repository.read()
        if (current.record(HansSetupStep.HARDWARE_MAPPING).status == HansSetupStepStatus.VERIFIED) {
            fixture.probe.configuredChoice =
                current.inputChoice ?: HansSetupInputChoice.HARDWARE_TOGGLE
        }
        mapOf(
            HansSetupStep.MICROPHONE_ACCESS to "microphone_permission_granted",
            HansSetupStep.APP_NOTIFICATIONS_ACCESS to "app_notification_permission_granted",
            HansSetupStep.NOTIFICATION_ACCESS to "notification_access_granted",
            HansSetupStep.ACCESSIBILITY_ACCESS to "accessibility_access_granted",
            HansSetupStep.HOME_ROLE to "home_role_held",
            HansSetupStep.SPEECH_CREDENTIAL_ACCESS to "speech_credential_available",
        ).forEach { (step, detailCode) ->
            if (current.record(step).status == HansSetupStepStatus.VERIFIED) {
                fixture.probe.results[step] = HansSetupProbeResult(true, detailCode)
            }
        }
    }

    private fun completedEverydayAccessFixture(
        actionPolicy: HansPhoneActionPolicy = HansPhoneActionPolicy.CONFIRM_ACTIONS,
    ): Fixture {
        val completed = completedFixture().repository.read()
        return fixture(
            actionPolicy = actionPolicy,
            initial = completed.copy(
                optionalCapabilities = completed.optionalCapabilities +
                    (HansSetupOptionalCapability.EVERYDAY_ACCESS to HansSetupOptionalCapabilityRecord(
                        decision = HansSetupCapabilityDecision.ENABLE,
                        status = HansSetupStepStatus.VERIFIED,
                        effective = true,
                    )),
            ),
        ).also(::preserveVerifiedFreshState)
    }

    private fun completedFixture(skipped: Set<HansSetupStep> = emptySet()): Fixture {
        val initial = documentAtReview()
        val fixture = fixture(
            initial = initial.copy(
                steps = initial.steps + skipped.associateWith {
                    HansSetupStepRecord(
                        status = HansSetupStepStatus.SKIPPED,
                        detailCode = "existing_configuration_not_proven",
                    )
                },
            ),
        )
        preserveVerifiedFreshState(fixture)
        fixture.repository.acceptExistingConfiguration(explicitUserConfirmation = true)
        assertTrue(fixture.repository.read().complete)
        return fixture
    }

    private fun call(
        executor: HansSetupDynamicToolExecutor,
        tool: String,
        arguments: String,
        turnId: String = "turn_${NEXT_CALL.incrementAndGet()}",
    ): DynamicToolExecutionResult {
        var result: DynamicToolExecutionResult? = null
        executor.execute(
            DynamicToolCallParams(
                threadId = "thread_setup",
                turnId = turnId,
                callId = "call_${tool.take(32)}_${NEXT_CALL.incrementAndGet()}",
                namespace = HansSetupDynamicToolCatalog.NAMESPACE,
                tool = tool,
                argumentsJson = arguments,
            ),
        ) { result = it }
        return checkNotNull(result)
    }

    private data class Fixture(
        val repository: HansSetupRepository,
        val probe: FakeProbe,
        val executor: HansSetupDynamicToolExecutor,
    )

    private class FakeProbe : HansSetupFreshProbe {
        var project: HansSetupDesktopProject? = null
        override fun desktopProject(): HansSetupDesktopProject? = project

        val probedSteps = mutableListOf<HansSetupStep>()
        val results = mutableMapOf<HansSetupStep, HansSetupProbeResult>()
        val optionalResults =
            mutableMapOf<HansSetupOptionalCapability, HansSetupProbeResult>()
        var holdCompatible = true
        var configuredChoice: HansSetupInputChoice? = null
        var cameraHoldEnabled = false

        override fun probe(step: HansSetupStep, operationNonce: String?): HansSetupProbeResult {
            probedSteps += step
            return results[step] ?: HansSetupProbeResult(false, "probe_not_ready")
        }

        override fun hardwareHoldCompatible(): Boolean = holdCompatible

        override fun configuredInputChoice(): HansSetupInputChoice? = configuredChoice

        override fun configuredCameraHoldEnabled(): Boolean = cameraHoldEnabled

        override fun probeOptionalCapability(
            capability: HansSetupOptionalCapability,
            operationNonce: String?,
        ): HansSetupProbeResult = optionalResults[capability]
            ?: HansSetupProbeResult(false, "optional_capability_not_ready")

        override fun postNotificationLiveTest(operationNonce: String): Boolean = true
    }

    private fun documentAt(step: HansSetupStep): HansSetupDocument {
        val preceding = HANS_SETUP_ORDER.takeWhile { it != step }
        return HansSetupDocument(
            started = true,
            inputChoice = HansSetupInputChoice.HARDWARE_TOGGLE.takeIf {
                HansSetupStep.HARDWARE_MAPPING in preceding
            },
            steps = preceding.associateWith {
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
    }

    private fun documentAtReview(): HansSetupDocument = documentAt(HansSetupStep.REVIEW).copy(
        requestedModel = "gpt-5.6-luna",
        requestedReasoningEffort = "max",
        effectiveModel = "gpt-5.6-luna",
        effectiveReasoningEffort = "max",
        profileConfirmed = true,
    )

    private class MemoryStorage(
        private var value: HansSetupDocument = HansSetupDocument(),
    ) : HansSetupStorage {

        override fun read(): HansSetupDocument = value

        override fun write(document: HansSetupDocument) {
            value = document
        }
    }

    private class MemoryProfileStorage : UserProfileStorage {
        private var value = UserProfileDocument()

        override fun read(): UserProfileDocument = value

        override fun write(document: UserProfileDocument) {
            value = document
        }

        override fun clear() {
            value = UserProfileDocument()
        }
    }

    private class FakeDeadlineScheduler : SetupUiCommandDeadlineScheduler {
        private val tasks = mutableListOf<Task>()

        override fun schedule(
            delayMillis: Long,
            task: () -> Unit,
        ): SetupUiCommandDeadline = Task(delayMillis, task).also(tasks::add)

        fun pendingDelays(): List<Long> = tasks.filterNot(Task::cancelled).map(Task::delayMillis)

        fun runNext() {
            checkNotNull(tasks.firstOrNull { !it.cancelled }) {
                "No pending deadline"
            }.task()
        }

        private class Task(
            val delayMillis: Long,
            val task: () -> Unit,
        ) : SetupUiCommandDeadline {
            var cancelled = false
                private set

            override fun cancel() {
                cancelled = true
            }
        }
    }

    private companion object {
        val NEXT_CALL = AtomicInteger()
    }
}
