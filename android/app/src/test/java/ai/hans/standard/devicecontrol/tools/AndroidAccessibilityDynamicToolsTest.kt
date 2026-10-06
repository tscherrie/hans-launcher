package ai.hans.standard.devicecontrol.tools

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.diagnostics.ToolFailureCode
import ai.hans.standard.diagnostics.ToolFailureDetail
import ai.hans.standard.diagnostics.ToolFailureDiagnostic
import ai.hans.standard.phone.accessibility.AccessibilityCommand
import ai.hans.standard.phone.accessibility.AccessibilityConfirmationRequest
import ai.hans.standard.phone.accessibility.AccessibilityConfirmationRisk
import ai.hans.standard.phone.accessibility.AccessibilityExecutionResult
import ai.hans.standard.phone.accessibility.AccessibilityExecutionStatus
import ai.hans.standard.phone.accessibility.AccessibilityIdempotencyKey
import ai.hans.standard.phone.accessibility.AccessibilityObservation
import ai.hans.standard.phone.accessibility.AccessibilityPostcondition
import ai.hans.standard.phone.accessibility.AccessibilityPostconditionKind
import ai.hans.standard.phone.accessibility.AccessibilityPostconditionStatus
import ai.hans.standard.phone.accessibility.AccessibilitySessionId
import ai.hans.standard.phone.accessibility.AccessibilitySnapshotId
import ai.hans.standard.phone.accessibility.AccessibilityUserApproval
import ai.hans.standard.phone.accessibility.AccessibilityWindowId
import ai.hans.standard.phone.accessibility.BoundedSemanticUiSnapshotFactory
import ai.hans.standard.phone.accessibility.DictationLifecycleStamp
import ai.hans.standard.phone.accessibility.RawSemanticUiNode
import ai.hans.standard.phone.accessibility.RawSemanticUiSnapshot
import ai.hans.standard.phone.accessibility.SemanticUiAction
import ai.hans.standard.phone.accessibility.SemanticUiRole
import ai.hans.standard.phone.accessibility.SemanticUiSnapshot
import ai.hans.standard.phone.accessibility.SnapshotTruncationReason
import ai.hans.standard.phone.accessibility.UiBounds
import ai.hans.standard.phone.accessibility.UiDataTrust
import ai.hans.standard.phone.accessibility.UiInteractionAvailability
import ai.hans.standard.phone.accessibility.UiInteractionAvailabilityProbe
import ai.hans.standard.phone.accessibility.UiPostconditionExpectation
import ai.hans.standard.phone.accessibility.UiSnapshotCorrelation
import ai.hans.standard.phone.accessibility.android.AccessibilityCommandCallback
import ai.hans.standard.phone.accessibility.android.AccessibilitySnapshotFailure
import ai.hans.standard.phone.accessibility.android.AccessibilitySensitiveActionApproval
import ai.hans.standard.phone.accessibility.android.AndroidDictationLifecycleRegistry
import ai.hans.standard.phone.accessibility.android.HansAccessibilitySession
import ai.hans.standard.phone.accessibility.android.VisualUiCapture
import ai.hans.standard.phone.accessibility.android.VisualUiCaptureResult
import ai.hans.standard.phone.accessibility.resume.UiTaskContinuationCheckpoint
import ai.hans.standard.phone.accessibility.resume.UiTaskContinuationCheckpointer
import java.util.concurrent.Executor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAccessibilityDynamicToolsTest {
    @Test
    fun commandFailuresKeepActualKnownCodeAndDetailWithoutRetainingUnknownText() {
        listOf(
            Triple("adapter_stale_target", "request_rejected", ToolFailureDiagnostic(
                ToolFailureCode.ADAPTER_STALE_TARGET, ToolFailureDetail.REQUEST_REJECTED,
            )),
            Triple("postcondition_snapshot_unavailable", "adapter_operation_failed", ToolFailureDiagnostic(
                ToolFailureCode.POSTCONDITION_SNAPSHOT_UNAVAILABLE, ToolFailureDetail.ADAPTER_OPERATION_FAILED,
            )),
            Triple("private_account_name", "private_contact_detail", ToolFailureDiagnostic(
                ToolFailureCode.UNKNOWN, ToolFailureDetail.UNKNOWN,
            )),
        ).forEach { (code, detail, expected) ->
            val session = FakeSession(snapshot()) { command, _ ->
                succeeded(command).let {
                    it.copy(
                        status = AccessibilityExecutionStatus.FAILED,
                        errorCode = code,
                        postcondition = it.postcondition.copy(
                            status = AccessibilityPostconditionStatus.FAILED,
                            detailCode = detail,
                        ),
                    )
                }
            }
            val result = executor(session).run(followUpActionCall("command-diagnostic"))
            assertFalse(result.success)
            assertEquals(expected, result.failureDiagnostic)
            val wire = JSONObject(result.contentText)
            assertEquals(code, wire.getString("errorCode"))
            assertEquals(detail, wire.getJSONObject("postcondition").getString("detailCode"))
            assertFalse(wire.has("failureDiagnostic"))
            assertFalse(wire.has("nextObservation"))
            assertEquals(1, session.commands.size)
        }
    }

    @Test
    fun directFailureMappingPreservesOldWireAndDropsUnknownCodesFromMetadataOnly() {
        listOf("executor_rejected", "private_account_name").forEach { code ->
            val result = executor(FakeSession(snapshot())).failureResult(call("inspect_ui", "failure"), code)
            val expected = JSONObject().put("status", "failed")
                .put("capability", "android_accessibility").put("errorCode", code).toString()
            assertEquals(expected, result.contentText)
            assertFalse(result.success)
            assertEquals(ToolFailureCode.fromCode(code), result.failureDiagnostic?.code)
            assertEquals(null, result.failureDiagnostic?.detail)
        }
    }

    @Test
    fun screenshotCaptureFailureIsRecordedAndStillReleasesProofForRetry() {
        val proofs = VisualFallbackProofStore({ 1L }, { "fallback:capture-failure" })
        val token = proofs.issue(call("find_ui", "issue"), CORRELATION)
        val session = FakeSession(snapshot(), visualCapture = VisualUiCaptureResult.Failure("visual_capture_rate_limited"))
        val result = executor(session, fallbackProofs = proofs).run(call(
            "inspect_visual_ui", "capture",
            JSONObject().put("correlation", correlationValue()).put("fallbackToken", token).toString(),
        ))
        assertFalse(result.success)
        assertEquals(ToolFailureDiagnostic(ToolFailureCode.VISUAL_CAPTURE_RATE_LIMITED), result.failureDiagnostic)
        assertEquals("visual_capture_rate_limited", JSONObject(result.contentText).getString("errorCode"))
        assertEquals(1, session.captureCalls)
        assertTrue(proofs.claim(token, call("inspect_visual_ui", "retry"), CORRELATION, CORRELATION) != null)
    }

    @Test
    fun visualExpiryIsLocalMetadataOnlyAndDoesNotCaptureOrDispatch() {
        listOf("inspect_visual_ui", "visual_gesture_fallback").forEach { tool ->
            var now = 1L
            val proofs = VisualFallbackProofStore({ now }, { "fallback:expired-proof" })
            val token = proofs.issue(call("find_ui", "issue"), CORRELATION)
            now += 30_001
            val args = if (tool == "inspect_visual_ui") {
                JSONObject().put("correlation", correlationValue()).put("fallbackToken", token).toString()
            } else gestureArgs(token)
            val session = FakeSession(snapshot())
            val result = executor(session, fallbackProofs = proofs).run(call(tool, "expired", args))
            assertFalse(result.success)
            assertEquals(ToolFailureDiagnostic(
                ToolFailureCode.SEMANTIC_FALLBACK_PROOF_REQUIRED, ToolFailureDetail.PROOF_EXPIRED,
            ), result.failureDiagnostic)
            assertEquals(JSONObject().put("status", "failed").put("capability", "android_accessibility")
                .put("errorCode", "semantic_fallback_proof_required").toString(), result.contentText)
            assertEquals(0, session.captureCalls)
            assertTrue(session.commands.isEmpty())
        }
    }

    @Test
    fun currentSnapshotMismatchDoesNotConsumeProofOrRunGesture() {
        val proofCorrelation = CORRELATION.copy(snapshotId = AccessibilitySnapshotId(2))
        val proofs = VisualFallbackProofStore({ 1L }, { "fallback:changed-snapshot" })
        val token = proofs.issue(call("find_ui", "issue"), proofCorrelation)
        val session = FakeSession(snapshot())
        val result = executor(session, fallbackProofs = proofs).run(call(
            "visual_gesture_fallback", "changed",
            JSONObject(gestureArgs(token)).put("correlation", correlationValue().put("snapshotId", 2)).toString(),
        ))
        assertFalse(result.success)
        assertEquals(ToolFailureDiagnostic(
            ToolFailureCode.SEMANTIC_FALLBACK_PROOF_REQUIRED, ToolFailureDetail.PROOF_CURRENT_SNAPSHOT_MISMATCH,
        ), result.failureDiagnostic)
        assertTrue(session.commands.isEmpty())
        assertEquals(0, session.captureCalls)
        assertTrue(proofs.claim(token, call("visual_gesture_fallback", "matching"), proofCorrelation, proofCorrelation) != null)
    }

    @Test
    fun verifiedActionReturnsAndPinsOnlyItsExactReceiptWithoutInspectingAgain() {
        val after = CORRELATION.copy(snapshotId = AccessibilitySnapshotId(2))
        val newer = after.copy(snapshotId = AccessibilitySnapshotId(3), windowId = AccessibilityWindowId(9))
        val receipt = snapshot(packageName = "receipt.package", correlation = after)
        val session = FakeSession(
            snapshot = snapshot(packageName = "unrelated.current", correlation = newer),
            receiptSnapshot = { receipt },
            retainReceipt = { true },
        ) { command, _ -> verifiedReceipt(command, after) }

        val result = executor(session).run(followUpActionCall("exact-receipt"))

        assertTrue(result.success)
        val next = JSONObject(result.contentText).getJSONObject("nextObservation")
        assertEquals("succeeded", next.getString("status"))
        assertEquals(2L, next.getJSONObject("correlation").getLong("snapshotId"))
        assertTrue(next.toString().contains("receipt.package"))
        assertFalse(next.toString().contains("unrelated.current"))
        assertEquals(listOf(after), session.receiptReads)
        assertEquals(listOf(after), session.receiptPins)
        assertTrue(session.receiptWithdrawals.isEmpty())
        assertEquals(1, session.commands.size)
        assertEquals(0, session.refreshCalls)
        assertEquals(0, session.captureCalls)
    }

    @Test
    fun acceptedClickReturnsFreshHandlesWithoutUpgradingItsUnverifiedPostcondition() {
        val after = CORRELATION.copy(snapshotId = AccessibilitySnapshotId(2))
        val session = FakeSession(
            snapshot = snapshot(), receiptSnapshot = { snapshot(correlation = after) },
            retainReceipt = { true },
        ) { command, _ -> observedReceipt(command, after) }

        val result = executor(session).run(call(
            "click_ui", "accepted-click",
            JSONObject().put("handle", handleJson(snapshot().nodes.single().handle)).toString(),
        ))

        assertTrue(result.success)
        val receipt = JSONObject(result.contentText)
        assertEquals("observed_not_verified", receipt.getJSONObject("postcondition").getString("status"))
        assertEquals("android_action_observed", receipt.getString("actionCode"))
        assertEquals("android_action_accepted_only", receipt.getJSONObject("postcondition").getString("detailCode"))
        val next = receipt.getJSONObject("nextObservation")
        assertEquals("succeeded", next.getString("status"))
        assertEquals("untrusted_external", next.getString("trust"))
        assertEquals(2L, next.getJSONObject("correlation").getLong("snapshotId"))
        assertEquals(listOf(after), session.receiptPins)
        assertEquals(UiPostconditionExpectation.ACTION_ACCEPTED, (session.commands.single() as AccessibilityCommand.Click).postcondition)
        assertEquals(0, session.refreshCalls)
        assertEquals(0, session.captureCalls)
    }

    @Test
    fun replayedFailedUnevaluatedUntrustedAndUncorrelatedReceiptsNeverAcquireFollowUpHandles() {
        val after = CORRELATION.copy(snapshotId = AccessibilitySnapshotId(2))
        val variants: List<(AccessibilityExecutionResult) -> AccessibilityExecutionResult> = listOf(
            { it.copy(replayed = true) },
            { it.copy(postcondition = it.postcondition.copy(status = AccessibilityPostconditionStatus.FAILED)) },
            { it.copy(postcondition = it.postcondition.copy(status = AccessibilityPostconditionStatus.NOT_EVALUATED)) },
            { it.copy(postcondition = it.postcondition.copy(kind = AccessibilityPostconditionKind.SNAPSHOT_QUERY)) },
            { it.copy(postcondition = it.postcondition.copy(before = null)) },
            { it.copy(postcondition = it.postcondition.copy(before = after)) },
            { it.copy(postcondition = it.postcondition.copy(after = CORRELATION)) },
            { it.copy(postcondition = it.postcondition.copy(trust = UiDataTrust.UNTRUSTED_EXTERNAL)) },
            { it.copy(observation = (it.observation as AccessibilityObservation.ActionReceipt).copy(trust = UiDataTrust.UNTRUSTED_EXTERNAL)) },
            { it.copy(observation = (it.observation as AccessibilityObservation.ActionReceipt).copy(resultingCorrelation = null)) },
            { value ->
                val foreign = after.copy(sessionId = AccessibilitySessionId("foreign-session"))
                value.copy(
                    observation = (value.observation as AccessibilityObservation.ActionReceipt).copy(resultingCorrelation = foreign),
                    postcondition = value.postcondition.copy(after = foreign),
                )
            },
        )
        variants.forEachIndexed { index, transform ->
            val session = FakeSession(
                snapshot = snapshot(), receiptSnapshot = { snapshot(correlation = after) },
                retainReceipt = { true },
            ) { command, _ -> transform(verifiedReceipt(command, after)) }

            assertFollowUpUnavailable(executor(session).run(followUpActionCall("conservative-$index")))

            assertTrue(session.receiptReads.isEmpty())
            assertTrue(session.receiptPins.isEmpty())
            assertEquals(1, session.commands.size)
        }
    }

    @Test
    fun observedReceiptDoesNotBypassMissingStaleLockedOrReplacedSessionGates() {
        val after = CORRELATION.copy(snapshotId = AccessibilitySnapshotId(2))
        listOf("missing", "mismatched", "expired", "locked", "replaced").forEach { scenario ->
            var availability = UiInteractionAvailability.AVAILABLE
            var active: HansAccessibilitySession? = null
            val session = FakeSession(
                snapshot = snapshot(),
                receiptSnapshot = {
                    when (scenario) {
                        "missing" -> null
                        "mismatched" -> snapshot()
                        else -> snapshot(packageName = "private.receipt", correlation = after)
                    }
                },
                retainReceipt = { scenario != "expired" },
            ) { command, _ ->
                if (scenario == "locked") availability = UiInteractionAvailability.DEVICE_LOCKED
                if (scenario == "replaced") active = null
                observedReceipt(command, after)
            }
            active = session

            val result = executor(
                session, availabilityProbe = UiInteractionAvailabilityProbe { availability },
                sessionSource = AccessibilitySessionSource { active },
            ).run(followUpActionCall("observed-gate-$scenario"))

            assertFollowUpUnavailable(result)
            assertEquals("observed_not_verified", JSONObject(result.contentText).getJSONObject("postcondition").getString("status"))
            assertFalse(result.contentText.contains("private.receipt"))
            assertEquals(1, session.commands.size)
            assertEquals(0, session.refreshCalls)
            assertEquals(0, session.captureCalls)
            assertEquals(if (scenario == "expired") listOf(after) else emptyList<UiSnapshotCorrelation>(), session.receiptPins)
            assertEquals(if (scenario == "expired") listOf(after) else emptyList<UiSnapshotCorrelation>(), session.receiptWithdrawals)
        }
    }

    @Test
    fun rejectedOrFailedExecutionNeverPromotesAnObservedReceiptOrRetriesTheAction() {
        val after = CORRELATION.copy(snapshotId = AccessibilitySnapshotId(2))
        listOf(AccessibilityExecutionStatus.REJECTED, AccessibilityExecutionStatus.FAILED).forEach { status ->
            val session = FakeSession(
                snapshot = snapshot(), receiptSnapshot = { snapshot(correlation = after) },
                retainReceipt = { true },
            ) { command, _ ->
                observedReceipt(command, after).copy(status = status, errorCode = "accessibility_session_closed")
            }

            val result = executor(session).run(followUpActionCall("cancelled-${status.name.lowercase()}"))

            assertFalse(result.success)
            assertFalse(JSONObject(result.contentText).has("nextObservation"))
            assertTrue(session.receiptReads.isEmpty())
            assertTrue(session.receiptPins.isEmpty())
            assertEquals(1, session.commands.size)
        }
    }

    @Test
    fun missingMismatchedOrThrowingReceiptPreservesActionSuccessWithoutFreshCaptureOrRetry() {
        val after = CORRELATION.copy(snapshotId = AccessibilitySnapshotId(2))
        val readers: List<(UiSnapshotCorrelation) -> SemanticUiSnapshot?> = listOf(
            { null }, { snapshot() }, { throw IllegalStateException("receipt unavailable") },
        )
        readers.forEachIndexed { index, read ->
            val session = FakeSession(snapshot = snapshot(), receiptSnapshot = read, retainReceipt = { true }) {
                command, _ -> verifiedReceipt(command, after)
            }

            assertFollowUpUnavailable(executor(session).run(followUpActionCall("missing-$index")))

            assertEquals(listOf(after), session.receiptReads)
            assertTrue(session.receiptPins.isEmpty())
            assertEquals(1, session.commands.size)
            assertEquals(0, session.refreshCalls)
            assertEquals(0, session.captureCalls)
        }
    }

    @Test
    fun failedOrThrowingPromotionPreservesActionAndWithdrawsOnlyItsAttempt() {
        val after = CORRELATION.copy(snapshotId = AccessibilitySnapshotId(2))
        val promoters: List<(UiSnapshotCorrelation) -> Boolean> = listOf(
            { false }, { throw IllegalStateException("pin unavailable") },
        )
        promoters.forEachIndexed { index, promote ->
            val session = FakeSession(
                snapshot = snapshot(), receiptSnapshot = { snapshot(correlation = after) },
                retainReceipt = promote,
            ) { command, _ -> verifiedReceipt(command, after) }

            assertFollowUpUnavailable(executor(session).run(followUpActionCall("pin-$index")))

            assertEquals(listOf(after), session.receiptPins)
            assertEquals(listOf(after), session.receiptWithdrawals)
            assertEquals(1, session.commands.size)
        }
    }

    @Test
    fun lockBeforeOrDuringReceiptReadPreventsAnyPromotionOrUiDisclosure() {
        val after = CORRELATION.copy(snapshotId = AccessibilitySnapshotId(2))
        listOf(true, false).forEach { lockInAction ->
            var availability = UiInteractionAvailability.AVAILABLE
            val session = FakeSession(
                snapshot = snapshot(),
                receiptSnapshot = {
                    availability = UiInteractionAvailability.DEVICE_LOCKED
                    snapshot(packageName = "private.receipt", correlation = after)
                }, retainReceipt = { true },
            ) { command, _ ->
                if (lockInAction) availability = UiInteractionAvailability.DEVICE_LOCKED
                verifiedReceipt(command, after)
            }

            val result = executor(
                session, availabilityProbe = UiInteractionAvailabilityProbe { availability },
            ).run(followUpActionCall("lock-$lockInAction"))

            assertFollowUpUnavailable(result)
            assertFalse(result.contentText.contains("private.receipt"))
            assertTrue(session.receiptPins.isEmpty())
            assertEquals(if (lockInAction) 0 else 1, session.receiptReads.size)
            assertEquals(1, session.commands.size)
        }
    }

    @Test
    fun lockOrSessionReplacementAfterPromotionWithdrawsUndeliveredHandlesWithoutRepeatingAction() {
        val after = CORRELATION.copy(snapshotId = AccessibilitySnapshotId(2))
        listOf(true, false).forEach { lock ->
            var availability = UiInteractionAvailability.AVAILABLE
            var active: HansAccessibilitySession? = null
            var retained = false
            val session = FakeSession(
                snapshot = snapshot(), receiptSnapshot = { snapshot(correlation = after) },
                retainReceipt = {
                    retained = true
                    if (lock) availability = UiInteractionAvailability.DEVICE_LOCKED else active = null
                    true
                }, withdrawReceipt = { retained = false },
            ) { command, _ -> verifiedReceipt(command, after) }
            active = session

            val result = executor(
                session, availabilityProbe = UiInteractionAvailabilityProbe { availability },
                sessionSource = AccessibilitySessionSource { active },
            ).run(followUpActionCall("final-gate-$lock"))

            assertFollowUpUnavailable(result)
            assertFalse(retained)
            assertEquals(listOf(after), session.receiptWithdrawals)
            assertEquals(1, session.commands.size)
        }
    }

    @Test
    fun confirmationOnlyPromotesAfterApprovedVerifiedExecutionAndNeverAfterDenial() {
        val after = CORRELATION.copy(snapshotId = AccessibilitySnapshotId(2))
        listOf(true, false).forEach { allow ->
            var performed = 0
            val session = FakeSession(
                snapshot = snapshot(),
                sensitiveActionApproval = { _, request ->
                    if (allow) AccessibilitySensitiveActionApproval.Approved(exactApproval(request))
                    else AccessibilitySensitiveActionApproval.Denied
                },
                receiptSnapshot = { snapshot(correlation = after) }, retainReceipt = { true },
            ) { command, approval ->
                if (approval == null) confirmationRequired(command, AccessibilityConfirmationRequest(
                    command.idempotencyKey, "e".repeat(64),
                    AccessibilityConfirmationRisk.EXTERNAL_COMMUNICATION, CORRELATION,
                )) else {
                    performed += 1
                    verifiedReceipt(command, after)
                }
            }

            val result = executor(session).run(followUpActionCall("confirmation-$allow"))

            assertEquals(allow, result.success)
            assertEquals(if (allow) 1 else 0, performed)
            assertEquals(if (allow) listOf(after) else emptyList<UiSnapshotCorrelation>(), session.receiptPins)
            assertEquals(if (allow) 2 else 1, session.commands.size)
        }
    }

    @Test
    fun catalogUsesClosedTypedSchemasAndKeepsVisualControlAnExplicitFallback() {
        val tools = AndroidAccessibilityDynamicToolCatalog.namespace.tools.associateBy { it.name }
        assertEquals(
            setOf(
                "run_steps",
                "inspect_ui",
                "inspect_visual_ui",
                "find_ui",
                "click_ui",
                "set_text_ui",
                "scroll_ui",
                "global_ui_action",
                "visual_gesture_fallback",
            ),
            tools.keys,
        )
        assertTrue(tools.values.all {
            val schema = JSONObject(it.inputSchemaJson)
            schema.getString("type") == "object" && !schema.getBoolean("additionalProperties")
        })
        val visual = JSONObject(tools.getValue("visual_gesture_fallback").inputSchemaJson)
        val visualDescription = tools.getValue("visual_gesture_fallback").description
        assertTrue(visual.getJSONObject("properties").has("fallbackToken"))
        assertTrue(visualDescription.contains("zero-match"))
        assertTrue(visualDescription.contains("replacement visualFallbackToken returned by inspect_visual_ui"))
        assertFalse(visualDescription.contains("find_ui call on the same screen for every gesture"))
        assertTrue(visualDescription.contains("selected Hans action policy"))
        assertTrue(visualDescription.contains("for every gesture"))
        assertTrue(visualDescription.contains("user-authorized full access needs no extra Hans prompt"))
        assertTrue(visualDescription.contains("Android security and permission UI is excluded"))
        assertFalse(visualDescription.contains("explicit one-time user confirmation"))
        val screenshot = JSONObject(tools.getValue("inspect_visual_ui").inputSchemaJson)
        assertTrue(tools.getValue("inspect_visual_ui").description.contains("directly for the next gesture"))
        assertTrue(screenshot.getJSONObject("properties").has("fallbackToken"))
        assertTrue(screenshot.getJSONArray("required").toString().contains("correlation"))
        assertTrue(AndroidAccessibilityDynamicToolCatalog.namespace.description.contains("awake, unlocked"))
        assertTrue(AndroidAccessibilityDynamicToolCatalog.namespace.description.contains("never retry in a loop"))
        assertTrue(AndroidAccessibilityDynamicToolCatalog.namespace.description.contains("observed_not_verified is not proof"))
    }

    @Test
    fun everyAccessibilityToolDefersBeforeUiAccessWhileDeviceIsLocked() {
        val session = FakeSession(snapshot())
        val executor = executor(
            session = session,
            availability = UiInteractionAvailability.DEVICE_LOCKED,
        )

        AndroidAccessibilityDynamicToolCatalog.namespace.tools.forEachIndexed { index, spec ->
            val result = executor.run(call(spec.name, "call-locked-$index"))
            assertFalse(result.success)
            val receipt = JSONObject(result.contentText)
            assertEquals("deferred", receipt.getString("status"))
            assertEquals("user_action_required", receipt.getString("availability"))
            assertEquals("device_unlock_required", receipt.getString("errorCode"))
            assertEquals("unlock_device", receipt.getString("requiredUserAction"))
            assertFalse(receipt.getBoolean("retryable"))
            assertTrue(receipt.getBoolean("retryAfterUserAction"))
            assertFalse(receipt.getBoolean("automaticResume"))
            assertFalse(result.contentText.contains("accessibility_permission_required"))
        }

        assertEquals(0, session.refreshCalls)
        assertEquals(0, session.captureCalls)
        assertTrue(session.commands.isEmpty())
    }

    @Test
    fun initialLockGatePublishesOnlyAProvenDurableCheckpointAndNeverClaimsAutoResume() {
        val checkpoints = mutableListOf<DynamicToolCallParams>()
        val result = executor(
            session = FakeSession(snapshot()),
            availability = UiInteractionAvailability.DEVICE_LOCKED,
            continuationCheckpointer = UiTaskContinuationCheckpointer { call, _ ->
                checkpoints += call
                UiTaskContinuationCheckpoint.Persisted("a".repeat(64))
            },
        ).run(call("inspect_ui", "call-durable-lock"))

        val receipt = JSONObject(result.contentText)
        assertEquals(listOf("call-durable-lock"), checkpoints.map { it.callId })
        assertTrue(receipt.getBoolean("continuationCheckpointed"))
        assertEquals("a".repeat(64), receipt.getString("continuationId"))
        assertFalse(receipt.getBoolean("automaticResume"))
    }

    @Test
    fun lateLockRaceNeverCreatesAReplayCheckpoint() {
        val checkpoints = mutableListOf<DynamicToolCallParams>()
        val result = executor(
            session = FakeSession(snapshot()),
            availabilityProbe = SequenceAvailabilityProbe(
                UiInteractionAvailability.AVAILABLE,
                UiInteractionAvailability.SCREEN_NOT_INTERACTIVE,
            ),
            continuationCheckpointer = UiTaskContinuationCheckpointer { call, _ ->
                checkpoints += call
                UiTaskContinuationCheckpoint.Persisted("b".repeat(64))
            },
        ).run(call("inspect_ui", "call-late-lock"))

        val receipt = JSONObject(result.contentText)
        assertTrue(checkpoints.isEmpty())
        assertFalse(receipt.getBoolean("continuationCheckpointed"))
        assertFalse(receipt.getBoolean("automaticResume"))
    }

    @Test
    fun screenOffDefersBeforeRefreshSubmitAndVisualCaptureEvenAfterDispatchPassed() {
        val inspectSession = FakeSession(snapshot())
        val inspect = executor(
            session = inspectSession,
            availabilityProbe = SequenceAvailabilityProbe(
                UiInteractionAvailability.AVAILABLE,
                UiInteractionAvailability.SCREEN_NOT_INTERACTIVE,
            ),
        ).run(call("inspect_ui", "call-screen-off-before-refresh"))
        assertDeferredForWake(inspect)
        assertEquals(0, inspectSession.refreshCalls)

        val actionSession = FakeSession(snapshot())
        val action = executor(
            session = actionSession,
            availabilityProbe = SequenceAvailabilityProbe(
                UiInteractionAvailability.AVAILABLE,
                UiInteractionAvailability.SCREEN_NOT_INTERACTIVE,
            ),
        ).run(
            call(
                "click_ui",
                "call-screen-off-before-submit",
                JSONObject().put("handle", handleJson(snapshot().nodes.single().handle)).toString(),
            ),
        )
        assertDeferredForWake(action)
        assertTrue(actionSession.commands.isEmpty())

        val visualSession = FakeSession(snapshot())
        val proofs = VisualFallbackProofStore(
            elapsedRealtimeMillis = { 1L },
            tokenFactory = { "fallback:screen-off-visual" },
        )
        val proofCall = call("find_ui", "call-before-screen-off-visual")
        val token = proofs.issue(proofCall, CORRELATION)
        val visual = executor(
            session = visualSession,
            fallbackProofs = proofs,
            availabilityProbe = SequenceAvailabilityProbe(
                UiInteractionAvailability.AVAILABLE,
                UiInteractionAvailability.SCREEN_NOT_INTERACTIVE,
            ),
        ).run(
            call(
                "inspect_visual_ui",
                "call-screen-off-before-capture",
                JSONObject()
                    .put("correlation", correlationValue())
                    .put("fallbackToken", token)
                    .toString(),
            ),
        )
        assertDeferredForWake(visual)
        assertEquals(0, visualSession.captureCalls)
    }

    @Test
    fun lockDetectedByQueuedServiceExecutorKeepsTheActionableDeferredReceipt() {
        val currentSnapshot = snapshot()
        val session = FakeSession(snapshot = currentSnapshot) { command, _ ->
            AccessibilityExecutionResult(
                idempotencyKey = command.idempotencyKey,
                status = AccessibilityExecutionStatus.REJECTED,
                replayed = false,
                observation = null,
                postcondition = AccessibilityPostcondition(
                    kind = AccessibilityPostconditionKind.REQUEST_NOT_EXECUTED,
                    status = AccessibilityPostconditionStatus.NOT_EVALUATED,
                    detailCode = "user_action_required",
                    before = currentSnapshot.correlation,
                    after = null,
                    trust = UiDataTrust.LOCAL_SYSTEM,
                ),
                errorCode = "screen_wake_required",
            )
        }

        val result = executor(session).run(
            call(
                "click_ui",
                "call-queued-screen-off",
                JSONObject()
                    .put("handle", handleJson(currentSnapshot.nodes.single().handle))
                    .toString(),
            ),
        )

        assertDeferredForWake(result)
        assertTrue(session.commands.isNotEmpty())
    }

    @Test
    fun lockDetectedAtScreenshotBoundaryKeepsTheActionableDeferredReceipt() {
        val proofs = VisualFallbackProofStore(
            elapsedRealtimeMillis = { 1L },
            tokenFactory = { "fallback:deep-screen-off" },
        )
        val proofCall = call("find_ui", "call-before-deep-screen-off")
        val token = proofs.issue(proofCall, CORRELATION)
        val session = FakeSession(
            snapshot = snapshot(),
            visualCapture = VisualUiCaptureResult.Failure("screen_wake_required"),
        )

        val result = executor(session = session, fallbackProofs = proofs).run(
            call(
                "inspect_visual_ui",
                "call-deep-screen-off-visual",
                JSONObject()
                    .put("correlation", correlationValue())
                    .put("fallbackToken", token)
                    .toString(),
            ),
        )

        assertDeferredForWake(result)
        assertEquals(1, session.captureCalls)
    }

    @Test
    fun unavailableOrThrowingPlatformProbeFailsClosedWithoutTouchingTheSession() {
        listOf(
            UiInteractionAvailabilityProbe.FAIL_CLOSED,
            UiInteractionAvailabilityProbe { error("private platform failure") },
        ).forEachIndexed { index, probe ->
            val session = FakeSession(snapshot())
            val result = executor(session = session, availabilityProbe = probe)
                .run(call("inspect_ui", "call-unverified-ui-state-$index"))

            assertFalse(result.success)
            val receipt = JSONObject(result.contentText)
            assertEquals("deferred", receipt.getString("status"))
            assertEquals("user_action_required", receipt.getString("availability"))
            assertEquals(
                "device_ui_state_verification_required",
                receipt.getString("errorCode"),
            )
            assertEquals("wake_and_unlock_device", receipt.getString("requiredUserAction"))
            assertFalse(result.contentText.contains("private platform failure"))
            assertEquals(0, session.refreshCalls)
            assertEquals(0, session.captureCalls)
            assertTrue(session.commands.isEmpty())
        }
    }

    @Test
    fun disabledAccessibilityFailsClosedWithActionableSpecialAccessReceipt() {
        val result = executor(session = null).run(call("inspect_ui", "call-inspect"))

        assertFalse(result.success)
        val json = JSONObject(result.contentText)
        assertEquals("accessibility_permission_required", json.getString("errorCode"))
        assertEquals("accessibility_service", json.getString("requiredSpecialAccess"))
        assertEquals("special_access_required", json.getString("availability"))
    }

    @Test
    fun connectedAccessibilityWithMissingSessionReportsRetryableReconnectInsteadOfPermission() {
        val result = executor(session = null, serviceConnected = true)
            .run(call("inspect_ui", "call-reconnecting"))

        assertFalse(result.success)
        val json = JSONObject(result.contentText)
        assertEquals("accessibility_service_reconnecting", json.getString("errorCode"))
        assertEquals("temporarily_unavailable", json.getString("availability"))
        assertTrue(json.getBoolean("specialAccessGranted"))
        assertTrue(json.getBoolean("retryable"))
        assertFalse(result.contentText.contains("accessibility_permission_required"))
    }

    @Test
    fun unavailableSnapshotReportsOnlyTheAllowlistedCapturePhase() {
        val session = RefreshingSession(
            initial = null,
            refreshed = null,
            snapshotFailure = AccessibilitySnapshotFailure.PROJECTION_ROOT_READ_FAILED,
        )

        val result = executor(session = session, serviceConnected = true)
            .run(call("inspect_ui", "call-refresh-diagnostic"))

        assertFalse(result.success)
        val json = JSONObject(result.contentText)
        assertEquals("ui_snapshot_temporarily_unavailable", json.getString("errorCode"))
        assertEquals("projection_root_read_failed", json.getString("detailCode"))
        assertEquals(ToolFailureDiagnostic(
            ToolFailureCode.UI_SNAPSHOT_TEMPORARILY_UNAVAILABLE, ToolFailureDetail.PROJECTION_ROOT_READ_FAILED,
        ), result.failureDiagnostic)
        assertFalse(result.contentText.contains("RuntimeException"))
        assertFalse(result.contentText.contains("external ui text"))
        assertFalse(result.contentText.contains("accessibility_permission_required"))
    }

    @Test
    fun enabledSpecialAccessDuringAndroidRebindNeverClaimsPermissionIsMissing() {
        val result = executor(session = null, specialAccessEnabled = true)
            .run(call("inspect_ui", "call-platform-rebind"))

        assertFalse(result.success)
        val json = JSONObject(result.contentText)
        assertEquals("accessibility_service_reconnecting", json.getString("errorCode"))
        assertEquals("temporarily_unavailable", json.getString("availability"))
        assertTrue(json.getBoolean("specialAccessGranted"))
        assertTrue(json.getBoolean("retryable"))
        assertFalse(result.contentText.contains("accessibility_permission_required"))
    }

    @Test
    fun missingSnapshotGetsExactlyOneOnDemandRefreshBeforeInspection() {
        val session = RefreshingSession(initial = null, refreshed = snapshot())

        val result = executor(session = session, serviceConnected = true)
            .run(call("inspect_ui", "call-refresh-recovers"))

        assertTrue(result.success)
        assertEquals(1, session.refreshCalls)
        assertEquals("Checkout now", JSONObject(result.contentText)
            .getJSONArray("nodes").getJSONObject(0).getString("text"))
    }

    @Test
    fun inspectRefreshesAStaleNonNullCacheAfterTheForegroundWindowChanges() {
        val mapsCorrelation = UiSnapshotCorrelation(
            CORRELATION.sessionId,
            AccessibilityWindowId(41),
            AccessibilitySnapshotId(2),
        )
        val session = RefreshingSession(
            initial = snapshot("com.google.android.gm"),
            refreshed = snapshot("com.google.android.apps.maps", mapsCorrelation),
        )

        val result = executor(session = session, serviceConnected = true)
            .run(call("inspect_ui", "call-refresh-foreground-window"))

        assertTrue(result.success)
        assertEquals(1, session.refreshCalls)
        val json = JSONObject(result.contentText)
        assertEquals(
            "com.google.android.apps.maps",
            json.getJSONArray("nodes").getJSONObject(0).getString("packageName"),
        )
        assertEquals(41, json.getJSONObject("correlation").getInt("windowId"))
        assertEquals(2L, json.getJSONObject("correlation").getLong("snapshotId"))
        assertEquals(listOf(mapsCorrelation), session.retainedCorrelations)
    }

    @Test
    fun inspectFailsClosedWhenAnAccessibilityEventReplacesTheProjectedFrameBeforeRetention() {
        val projected = snapshot("org.example.first")
        val session = RefreshingSession(
            initial = null,
            refreshed = projected,
            retainResult = false,
        )

        val result = executor(session = session, serviceConnected = true)
            .run(call("inspect_ui", "call-inspect-retention-race"))

        assertFalse(result.success)
        assertEquals(1, session.refreshCalls)
        assertEquals(listOf(projected.correlation), session.retainedCorrelations)
        val json = JSONObject(result.contentText)
        assertEquals("ui_snapshot_temporarily_unavailable", json.getString("errorCode"))
        assertTrue(json.getBoolean("retryable"))
        assertFalse(result.contentText.contains("Checkout now"))
    }

    @Test
    fun findUsesTheFreshInspectCorrelationWithoutRefreshingAgain() {
        val freshCorrelation = UiSnapshotCorrelation(
            CORRELATION.sessionId,
            AccessibilityWindowId(52),
            AccessibilitySnapshotId(3),
        )
        val fresh = snapshot("org.example.fresh", freshCorrelation)
        val session = RefreshingSession(
            initial = snapshot("org.example.stale"),
            refreshed = fresh,
            handler = { command, _ ->
                assertEquals(freshCorrelation, (command as AccessibilityCommand.Find).correlation)
                succeeded(
                    command,
                    AccessibilityObservation.FoundNodes(
                        handles = listOf(fresh.nodes.single().handle),
                        snapshotTruncated = false,
                    ),
                    freshCorrelation,
                )
            },
        )
        val toolExecutor = executor(session = session, serviceConnected = true)

        val inspected = toolExecutor.run(call("inspect_ui", "call-inspect-fresh-chain"))
        val inspectedCorrelation = JSONObject(inspected.contentText).getJSONObject("correlation")
        val found = toolExecutor.run(
            call(
                "find_ui",
                "call-find-fresh-chain",
                JSONObject().put("correlation", inspectedCorrelation).toString(),
            ),
        )

        assertTrue(inspected.success)
        assertTrue(found.success)
        assertEquals(1, session.refreshCalls)
        assertEquals(1, session.commands.size)
    }

    @Test
    fun unavailableSnapshotAfterRefreshIsTransientAndDoesNotRevokeSpecialAccess() {
        val session = RefreshingSession(initial = null, refreshed = null)

        val result = executor(session = session, serviceConnected = true)
            .run(call("inspect_ui", "call-refresh-empty"))

        assertFalse(result.success)
        assertEquals(1, session.refreshCalls)
        val json = JSONObject(result.contentText)
        assertEquals("ui_snapshot_temporarily_unavailable", json.getString("errorCode"))
        assertEquals("temporarily_unavailable", json.getString("availability"))
        assertTrue(json.getBoolean("specialAccessGranted"))
        assertTrue(json.getBoolean("retryable"))
        assertEquals("snapshot_failure_unclassified", json.getString("detailCode"))
    }

    @Test
    fun failedFreshInspectionNeverFallsBackToAStaleCachedApp() {
        val session = RefreshingSession(
            initial = snapshot("com.google.android.gm"),
            refreshed = null,
            snapshotFailure = AccessibilitySnapshotFailure.NO_ACTIVE_ROOT,
        )

        val result = executor(session = session, serviceConnected = true)
            .run(call("inspect_ui", "call-refresh-must-not-use-stale-cache"))

        assertFalse(result.success)
        assertEquals(1, session.refreshCalls)
        val json = JSONObject(result.contentText)
        assertEquals("ui_snapshot_temporarily_unavailable", json.getString("errorCode"))
        assertEquals("no_active_root", json.getString("detailCode"))
        assertFalse(result.contentText.contains("com.google.android.gm"))
    }

    @Test
    fun inspectReturnsBoundedSemanticDataMarkedAsUntrusted() {
        val session = FakeSession(snapshot())

        val result = executor(session).run(call("inspect_ui", "call-inspect"))

        assertTrue(result.success)
        val json = JSONObject(result.contentText)
        assertEquals("untrusted_external", json.getString("trust"))
        assertEquals("Checkout now", json.getJSONArray("nodes").getJSONObject(0).getString("text"))
        assertEquals(
            "untrusted_external",
            json.getJSONArray("nodes").getJSONObject(0).getString("trust"),
        )
        assertFalse(result.contentText.contains("call-inspect"))
    }

    @Test
    fun inspectReportsReadablePlatformIncompleteSnapshotAsIncomplete() {
        val incomplete = BoundedSemanticUiSnapshotFactory().build(
            RawSemanticUiSnapshot(
                correlation = CORRELATION,
                displayBounds = UiBounds(0, 0, 1_080, 2_400),
                capturedAtElapsedMillis = 1,
                roots = listOf(
                    RawSemanticUiNode(
                        packageName = "org.example.dynamic",
                        text = "Dynamic app",
                        bounds = UiBounds(0, 0, 1_080, 2_400),
                    ),
                ),
                sourceTruncationReasons = setOf(
                    SnapshotTruncationReason.PLATFORM_NODE_UNAVAILABLE,
                ),
            ),
        )

        val result = executor(FakeSession(incomplete))
            .run(call("inspect_ui", "call-incomplete-inspect"))

        assertTrue(result.success)
        val json = JSONObject(result.contentText)
        assertFalse(json.getBoolean("snapshotComplete"))
        assertEquals(
            "Dynamic app",
            json.getJSONArray("nodes").getJSONObject(0).getString("text"),
        )
    }

    @Test
    fun visualInspectReturnsCorrelatedImageContentWithoutEmbeddingPixelsInJson() {
        val session = FakeSession(
            snapshot = snapshot(),
            visualCapture = VisualUiCaptureResult.Success(
                VisualUiCapture(
                    correlation = CORRELATION,
                    imageDataUrl = "data:image/jpeg;base64,AQIDBA==",
                    pixelWidth = 480,
                    pixelHeight = 800,
                    sourcePixelWidth = 1_440,
                    sourcePixelHeight = 2_400,
                    displayBounds = UiBounds(0, 0, 1_440, 2_400),
                    capturedAtElapsedMillis = 123,
                ),
            ),
        )
        var tokenCounter = 0
        val proofs = VisualFallbackProofStore(
            elapsedRealtimeMillis = { 1L },
            tokenFactory = { "fallback:visual-token-${++tokenCounter}" },
        )
        val proofCall = call("find_ui", "call-find-before-visual")
        val token = proofs.issue(proofCall, CORRELATION)
        val visualArgs = JSONObject()
            .put("correlation", correlationValue())
            .put("fallbackToken", token)
            .toString()

        val result = executor(session, fallbackProofs = proofs).run(
            call("inspect_visual_ui", "call-visual", visualArgs),
        )

        assertTrue(result.success)
        assertEquals(listOf("data:image/jpeg;base64,AQIDBA=="), result.imageUrls)
        val json = JSONObject(result.contentText)
        assertEquals("untrusted_external", json.getString("trust"))
        assertEquals(480, json.getInt("pixelWidth"))
        assertEquals(800, json.getInt("pixelHeight"))
        assertEquals(1_440, json.getInt("sourcePixelWidth"))
        assertEquals(2_400, json.getInt("sourcePixelHeight"))
        assertEquals(1_440, json.getJSONObject("displayBounds").getInt("right"))
        assertTrue(json.getString("coordinateMapping").contains("imageX/pixelWidth"))
        assertEquals("fallback:visual-token-2", json.getString("visualFallbackToken"))
        assertFalse(json.toString().contains("AQIDBA"))
        assertFalse(json.getBoolean("androidPixelsWrittenToDisk"))
        assertEquals("codex_app_server_input_image", json.getString("transport"))
    }

    @Test
    fun screenshotReplacementTokenGoesDirectlyToOneGestureAndReturnsObservedFollowUp() {
        val after = CORRELATION.copy(snapshotId = AccessibilitySnapshotId(2))
        val session = FakeSession(
            snapshot = snapshot(),
            visualCapture = VisualUiCaptureResult.Success(VisualUiCapture(
                correlation = CORRELATION,
                imageDataUrl = "data:image/jpeg;base64,AQIDBA==",
                pixelWidth = 480,
                pixelHeight = 800,
                sourcePixelWidth = 1_440,
                sourcePixelHeight = 2_400,
                displayBounds = UiBounds(0, 0, 1_440, 2_400),
                capturedAtElapsedMillis = 123,
            )),
            receiptSnapshot = { snapshot(correlation = after) },
            retainReceipt = { true },
        ) { command, _ ->
            when (command) {
                is AccessibilityCommand.Find -> succeeded(
                    command, AccessibilityObservation.FoundNodes(emptyList(), snapshotTruncated = false),
                )
                else -> observedReceipt(command, after).let { result ->
                    result.copy(postcondition = result.postcondition.copy(kind = AccessibilityPostconditionKind.COORDINATE_GESTURE))
                }
            }
        }
        var tokenCounter = 0
        val proofs = VisualFallbackProofStore(
            elapsedRealtimeMillis = { 1L },
            tokenFactory = { "fallback:direct-image-${++tokenCounter}" },
        )
        val executor = executor(session, fallbackProofs = proofs)
        val find = executor.run(call(
            "find_ui", "find-before-image",
            JSONObject().put("correlation", correlationValue()).toString(),
        ))
        val originalToken = JSONObject(find.contentText).getString("visualFallbackToken")
        val visual = executor.run(call(
            "inspect_visual_ui", "direct-image",
            JSONObject().put("correlation", correlationValue()).put("fallbackToken", originalToken).toString(),
        ))
        val imageReceipt = JSONObject(visual.contentText)
        val replacementToken = imageReceipt.getString("visualFallbackToken")
        val args = JSONObject(gestureArgs(replacementToken))
            .put("correlation", imageReceipt.getJSONObject("correlation")).toString()

        // The consumed semantic proof and other turns/threads cannot reuse the screenshot lease.
        listOf(
            call("visual_gesture_fallback", "old-token", gestureArgs(originalToken)),
            call("visual_gesture_fallback", "other-thread", args, threadId = "other-thread"),
            call("visual_gesture_fallback", "other-turn", args, turnId = "other-turn"),
            call("visual_gesture_fallback", "wrong-snapshot", JSONObject(args).put(
                "correlation", correlationValue().put("snapshotId", 99),
            ).toString()),
        ).zip(listOf(
            ToolFailureDetail.PROOF_MISSING_OR_CONSUMED,
            ToolFailureDetail.PROOF_THREAD_MISMATCH,
            ToolFailureDetail.PROOF_TURN_MISMATCH,
            ToolFailureDetail.PROOF_CORRELATION_MISMATCH,
        )).forEach { (rejected, detail) ->
            val result = executor.run(rejected)
            assertFalse(result.success)
            assertEquals("semantic_fallback_proof_required", JSONObject(result.contentText).getString("errorCode"))
            assertEquals(ToolFailureDiagnostic(ToolFailureCode.SEMANTIC_FALLBACK_PROOF_REQUIRED, detail), result.failureDiagnostic)
        }

        val gesture = executor.run(call("visual_gesture_fallback", "direct-gesture", args))

        assertTrue(gesture.success)
        assertEquals(null, gesture.failureDiagnostic)
        val gestureReceipt = JSONObject(gesture.contentText)
        assertEquals("observed_not_verified", gestureReceipt.getJSONObject("postcondition").getString("status"))
        assertEquals(2L, gestureReceipt.getJSONObject("nextObservation").getJSONObject("correlation").getLong("snapshotId"))
        assertEquals(2, session.commands.size)
        assertTrue(session.commands[0] is AccessibilityCommand.Find)
        assertTrue(session.commands[1] is AccessibilityCommand.CoordinateGesture)
        assertEquals(0, session.refreshCalls)
        assertEquals(1, session.captureCalls)
        assertEquals(listOf(after), session.receiptPins)
        assertFalse(executor.run(call("visual_gesture_fallback", "replayed-image-gesture", args)).success)
        assertEquals(2, session.commands.size)
    }

    @Test
    fun strictArgumentCodecRejectsCoercedNumbersAndUnknownFieldsBeforeSubmission() {
        val session = FakeSession(snapshot())
        val args = JSONObject().put("correlation", correlationValue()).put("unknown", true).toString()

        val result = executor(session).run(call("find_ui", "call-find", args))

        assertFalse(result.success)
        assertEquals("invalid_arguments", JSONObject(result.contentText).getString("errorCode"))
        assertTrue(session.commands.isEmpty())
    }

    @Test
    fun coordinateFallbackRequiresFreshZeroMatchProofFromSameTurnAndCurrentSnapshot() {
        val session = FakeSession(snapshot()) { command, _ ->
            when (command) {
                is AccessibilityCommand.Find -> succeeded(
                    command,
                    AccessibilityObservation.FoundNodes(emptyList(), snapshotTruncated = false),
                )
                else -> succeeded(command)
            }
        }
        var now = 10L
        var tokenCounter = 0
        val proofs = VisualFallbackProofStore(
            elapsedRealtimeMillis = { now },
            tokenFactory = { "fallback:test-token-${++tokenCounter}" },
        )
        val executor = executor(session, fallbackProofs = proofs)

        val rejected = executor.run(
            call(
                "visual_gesture_fallback",
                "gesture-before-find",
                gestureArgs("fallback:missing"),
            ),
        )
        assertEquals(
            "semantic_fallback_proof_required",
            JSONObject(rejected.contentText).getString("errorCode"),
        )

        val find = executor.run(
            call(
                "find_ui",
                "call-find",
                JSONObject().put("correlation", correlationValue()).toString(),
            ),
        )
        val token = JSONObject(find.contentText).getString("visualFallbackToken")
        val gesture = executor.run(
            call("visual_gesture_fallback", "call-gesture", gestureArgs(token)),
        )

        assertTrue(gesture.success)
        assertTrue(session.commands.last() is AccessibilityCommand.CoordinateGesture)
        val replay = executor.run(
            call("visual_gesture_fallback", "call-gesture-replay", gestureArgs(token)),
        )
        assertFalse(replay.success)
        now += 31_000
    }

    @Test
    fun externalAndDestructiveActionsRequireExactLocalUserConfirmation() {
        val target = snapshot().nodes.single().handle
        val session = FakeSession(
            snapshot = snapshot(),
            sensitiveActionApproval = { _, request ->
                AccessibilitySensitiveActionApproval.Approved(exactApproval(request))
            },
        ) { command, approval ->
                if (approval == null) {
                    val request = AccessibilityConfirmationRequest(
                        idempotencyKey = command.idempotencyKey,
                        commandFingerprint = "a".repeat(64),
                        risk = AccessibilityConfirmationRisk.DESTRUCTIVE,
                        correlation = target.correlation,
                    )
                    confirmationRequired(command, request)
                } else {
                    assertEquals(AccessibilityConfirmationRisk.DESTRUCTIVE, approval.risk)
                    succeeded(command)
                }
            }
        var authorizations = 0
        val executor = executor(
            session = session,
            authorizer = AccessibilityApprovalAuthorizer {
                authorizations += 1
                true
            },
        )

        val result = executor.run(
            call(
                "click_ui",
                "call-delete",
                JSONObject()
                    .put("handle", handleJson(target))
                    .put("declaredRisk", "destructive")
                    .toString(),
            ),
        )

        assertTrue(result.success)
        assertEquals(2, session.commands.size)
        assertEquals(1, authorizations)
    }

    @Test
    fun deniedConfirmationIsTerminalAndNeverDispatchesTheSensitiveRetry() {
        val target = snapshot().nodes.single().handle
        val session = FakeSession(
            snapshot = snapshot(),
            sensitiveActionApproval = { _, _ -> AccessibilitySensitiveActionApproval.Denied },
        ) { command, _ ->
                confirmationRequired(
                    command,
                    AccessibilityConfirmationRequest(
                        command.idempotencyKey,
                        "b".repeat(64),
                        AccessibilityConfirmationRisk.EXTERNAL_COMMUNICATION,
                        target.correlation,
                    ),
                )
            }

        val result = executor(session).run(
            call(
                "click_ui",
                "call-send",
                JSONObject().put("handle", handleJson(target)).toString(),
            ),
        )

        assertFalse(result.success)
        assertEquals("confirmation_denied", JSONObject(result.contentText).getString("errorCode"))
        assertEquals(1, session.commands.size)
    }

    @Test
    fun everyNonApprovalOutcomeHasExactTerminalCodeAndNoRetryOrAuthorization() {
        val outcomes = listOf(
            AccessibilitySensitiveActionApproval.Expired to "confirmation_expired",
            AccessibilitySensitiveActionApproval.ContextChanged to "confirmation_context_changed",
            AccessibilitySensitiveActionApproval.Unavailable to "confirmation_unavailable",
        )
        outcomes.forEachIndexed { index, (outcome, code) ->
            val target = snapshot().nodes.single().handle
            val session = FakeSession(
                snapshot = snapshot(),
                sensitiveActionApproval = { _, _ -> outcome },
            ) { command, _ ->
                    confirmationRequired(
                        command,
                        AccessibilityConfirmationRequest(
                            command.idempotencyKey,
                            "d".repeat(64),
                            AccessibilityConfirmationRisk.EXTERNAL_COMMUNICATION,
                            target.correlation,
                        ),
                    )
                }
            var authorizations = 0

            val result = executor(
                session = session,
                authorizer = AccessibilityApprovalAuthorizer {
                    authorizations += 1
                    true
                },
            ).run(
                call(
                    "click_ui",
                    "call-terminal-$index",
                    JSONObject().put("handle", handleJson(target)).toString(),
                ),
            )

            assertFalse(result.success)
            assertEquals(code, JSONObject(result.contentText).getString("errorCode"))
            assertEquals(1, session.commands.size)
            assertEquals(0, authorizations)
        }
    }

    @Test
    fun coordinateFallbackNeverCarriesDurableScopeAndExactRetryStillCompletes() {
        val mapsSnapshot = snapshot("com.google.android.apps.maps")
        var confirmations = 0
        val session = FakeSession(
            snapshot = mapsSnapshot,
            sensitiveActionApproval = { _, request ->
                confirmations += 1
                AccessibilitySensitiveActionApproval.Approved(exactApproval(request))
            },
        ) { command, approval ->
                if (approval == null) {
                    confirmationRequired(
                        command,
                        AccessibilityConfirmationRequest(
                            command.idempotencyKey,
                            "c".repeat(64),
                            AccessibilityConfirmationRisk.CREDENTIAL_UI,
                            CORRELATION,
                        ),
                    )
                } else {
                    succeeded(command)
                }
            }
        val proofs = VisualFallbackProofStore(
            elapsedRealtimeMillis = { 1L },
            tokenFactory = { "fallback:maps-visual-token" },
        )
        val token = proofs.issue(call("find_ui", "maps-proof"), CORRELATION)
        val executor = executor(
            session = session,
            fallbackProofs = proofs,
        )

        val result = executor.run(
            call("visual_gesture_fallback", "maps-gesture", gestureArgs(token)),
        )

        assertTrue(result.success)
        assertEquals(1, confirmations)
        assertEquals(2, session.commands.size)
        assertTrue(session.approvals.last() != null)
    }

    @Test
    fun accessibilityToolExecutionDoesNotStopOrMutateRunningDictation() {
        var stamp = DictationLifecycleStamp(generation = 7, recordingActive = true)
        val registration = AndroidDictationLifecycleRegistry.register { stamp }
        try {
            val session = FakeSession(snapshot()) { command, _ ->
                val before = AndroidDictationLifecycleRegistry.beforeAccessibilityAction()
                assertTrue(before.recordingActive)
                assertTrue(AndroidDictationLifecycleRegistry.remainedIndependent(before))
                succeeded(command)
            }

            val result = executor(session).run(
                call(
                    "global_ui_action",
                    "call-home",
                    JSONObject()
                        .put("sessionId", CORRELATION.sessionId.value)
                        .put("action", "home")
                        .toString(),
                ),
            )

            assertTrue(result.success)
            assertEquals(DictationLifecycleStamp(7, true), stamp)
        } finally {
            registration.close()
        }
    }

    private fun executor(
        session: HansAccessibilitySession?,
        serviceConnected: Boolean = false,
        specialAccessEnabled: Boolean = false,
        authorizer: AccessibilityApprovalAuthorizer = AccessibilityApprovalAuthorizer { true },
        availability: UiInteractionAvailability = UiInteractionAvailability.AVAILABLE,
        availabilityProbe: UiInteractionAvailabilityProbe =
            UiInteractionAvailabilityProbe { availability },
        fallbackProofs: VisualFallbackProofStore = VisualFallbackProofStore(
            elapsedRealtimeMillis = { 1L },
            tokenFactory = { "fallback:fixed-test-token" },
        ),
        continuationCheckpointer: UiTaskContinuationCheckpointer =
            UiTaskContinuationCheckpointer.NONE,
        sessionSource: AccessibilitySessionSource = AccessibilitySessionSource { session },
    ) = AndroidAccessibilityDynamicToolExecutor(
        backgroundExecutor = Executor(Runnable::run),
        sessions = sessionSource,
        serviceConnection = AccessibilityServiceConnectionProbe { serviceConnected },
        specialAccess = AccessibilitySpecialAccessProbe { specialAccessEnabled },
        uiAvailability = availabilityProbe,
        approvalAuthorizer = authorizer,
        fallbackProofs = fallbackProofs,
        continuationCheckpointer = continuationCheckpointer,
    )

    private fun AndroidAccessibilityDynamicToolExecutor.run(
        call: DynamicToolCallParams,
    ): DynamicToolExecutionResult {
        var callbacks = 0
        lateinit var result: DynamicToolExecutionResult
        execute(call) {
            callbacks += 1
            result = it
        }
        assertEquals(1, callbacks)
        return result
    }

    private fun call(
        tool: String,
        callId: String,
        arguments: String = "{}",
        threadId: String = "thread-test",
        turnId: String = "turn-test",
    ) = DynamicToolCallParams(
        threadId = threadId,
        turnId = turnId,
        callId = callId,
        namespace = AndroidAccessibilityDynamicToolCatalog.NAMESPACE,
        tool = tool,
        argumentsJson = arguments,
    )

    private fun gestureArgs(token: String): String = JSONObject()
        .put("correlation", correlationValue())
        .put("fallbackToken", token)
        .put("start", JSONObject().put("x", 100).put("y", 200))
        .toString()

    private fun followUpActionCall(id: String) = call(
        "click_ui", id, JSONObject().put("handle", handleJson(snapshot().nodes.single().handle))
            .put("postcondition", "node_state_changed").toString(),
    )

    private fun verifiedReceipt(command: AccessibilityCommand, after: UiSnapshotCorrelation): AccessibilityExecutionResult =
        succeeded(command, observation = AccessibilityObservation.ActionReceipt(
            null, "test_action_succeeded", after, UiDataTrust.LOCAL_SYSTEM,
        )).let { it.copy(postcondition = it.postcondition.copy(after = after)) }

    private fun observedReceipt(command: AccessibilityCommand, after: UiSnapshotCorrelation): AccessibilityExecutionResult =
        verifiedReceipt(command, after).let { result ->
            result.copy(
                observation = (result.observation as AccessibilityObservation.ActionReceipt).copy(actionCode = "android_action_observed"),
                postcondition = result.postcondition.copy(
                    status = AccessibilityPostconditionStatus.OBSERVED_NOT_VERIFIED,
                    detailCode = "android_action_accepted_only",
                ),
            )
        }

    private fun assertFollowUpUnavailable(result: DynamicToolExecutionResult) {
        assertTrue(result.success)
        val receipt = JSONObject(result.contentText)
        assertEquals("succeeded", receipt.getString("status"))
        val next = receipt.getJSONObject("nextObservation")
        assertEquals("unavailable", next.getString("status"))
        assertEquals("inspect_ui", next.getString("requiredTool"))
        assertFalse(next.getBoolean("retryAction"))
        assertFalse(next.has("nodes"))
    }

    private fun correlationValue(): JSONObject = JSONObject()
        .put("sessionId", CORRELATION.sessionId.value)
        .put("windowId", CORRELATION.windowId.value)
        .put("snapshotId", CORRELATION.snapshotId.value)

    private fun handleJson(handle: ai.hans.standard.phone.accessibility.SemanticNodeHandle): JSONObject =
        JSONObject()
            .put(
                "correlation",
                JSONObject()
                    .put("sessionId", handle.correlation.sessionId.value)
                    .put("windowId", handle.correlation.windowId.value)
                    .put("snapshotId", handle.correlation.snapshotId.value),
            )
            .put("nodeOrdinal", handle.nodeOrdinal)

    private fun assertDeferredForWake(result: DynamicToolExecutionResult) {
        assertFalse(result.success)
        val receipt = JSONObject(result.contentText)
        assertEquals("deferred", receipt.getString("status"))
        assertEquals("user_action_required", receipt.getString("availability"))
        assertEquals("screen_wake_required", receipt.getString("errorCode"))
        assertEquals("wake_device", receipt.getString("requiredUserAction"))
        assertFalse(receipt.getBoolean("retryable"))
    }

    private fun snapshot(
        packageName: String = "org.example.store",
        correlation: UiSnapshotCorrelation = CORRELATION,
    ): SemanticUiSnapshot = BoundedSemanticUiSnapshotFactory().build(
        RawSemanticUiSnapshot(
            correlation = correlation,
            displayBounds = UiBounds(0, 0, 1_080, 2_400),
            capturedAtElapsedMillis = 1,
            roots = listOf(
                RawSemanticUiNode(
                    packageName = packageName,
                    className = "android.widget.Button",
                    text = "Checkout now",
                    role = SemanticUiRole.BUTTON,
                    bounds = UiBounds(10, 10, 300, 120),
                    clickable = true,
                    actions = setOf(SemanticUiAction.CLICK),
                ),
            ),
        ),
    )

    private fun succeeded(
        command: AccessibilityCommand,
        observation: AccessibilityObservation? = AccessibilityObservation.ActionReceipt(
            target = null,
            actionCode = "test_action_succeeded",
            resultingCorrelation = CORRELATION,
            trust = UiDataTrust.LOCAL_SYSTEM,
        ),
        correlation: UiSnapshotCorrelation = CORRELATION,
    ) = AccessibilityExecutionResult(
        idempotencyKey = command.idempotencyKey,
        status = AccessibilityExecutionStatus.SUCCEEDED,
        replayed = false,
        observation = observation,
        postcondition = AccessibilityPostcondition(
            kind = if (command is AccessibilityCommand.Find) {
                AccessibilityPostconditionKind.SNAPSHOT_QUERY
            } else {
                AccessibilityPostconditionKind.NODE_ACTION
            },
            status = AccessibilityPostconditionStatus.VERIFIED,
            detailCode = "test_action_succeeded",
            before = correlation,
            after = correlation,
            trust = UiDataTrust.LOCAL_SYSTEM,
        ),
    )

    private fun confirmationRequired(
        command: AccessibilityCommand,
        request: AccessibilityConfirmationRequest,
    ) = AccessibilityExecutionResult(
        idempotencyKey = command.idempotencyKey,
        status = AccessibilityExecutionStatus.CONFIRMATION_REQUIRED,
        replayed = false,
        observation = null,
        postcondition = AccessibilityPostcondition(
            kind = AccessibilityPostconditionKind.REQUEST_NOT_EXECUTED,
            status = AccessibilityPostconditionStatus.NOT_EVALUATED,
            detailCode = "confirmation_required",
            before = request.correlation,
            after = null,
            trust = UiDataTrust.LOCAL_SYSTEM,
        ),
        requiredConfirmation = request,
        errorCode = "confirmation_required_${request.risk.name.lowercase()}",
    )

    private class FakeSession(
        private val snapshot: SemanticUiSnapshot,
        private val visualCapture: VisualUiCaptureResult =
            VisualUiCaptureResult.Failure("visual_capture_unavailable"),
        private val sensitiveActionApproval: (
            AccessibilityCommand,
            AccessibilityConfirmationRequest,
        ) -> AccessibilitySensitiveActionApproval = { _, _ ->
            AccessibilitySensitiveActionApproval.Unavailable
        },
        private val receiptSnapshot: (UiSnapshotCorrelation) -> SemanticUiSnapshot? = { null },
        private val retainReceipt: (UiSnapshotCorrelation) -> Boolean = { false },
        private val withdrawReceipt: (UiSnapshotCorrelation) -> Unit = {},
        private val handler: (AccessibilityCommand, AccessibilityUserApproval?) -> AccessibilityExecutionResult =
            { command, _ -> succeededStatic(command) },
    ) : HansAccessibilitySession {
        override val sessionId: AccessibilitySessionId = snapshot.correlation.sessionId
        val commands = mutableListOf<AccessibilityCommand>()
        val approvals = mutableListOf<AccessibilityUserApproval?>()
        val receiptReads = mutableListOf<UiSnapshotCorrelation>()
        val receiptPins = mutableListOf<UiSnapshotCorrelation>()
        val receiptWithdrawals = mutableListOf<UiSnapshotCorrelation>()
        var refreshCalls = 0
            private set
        var captureCalls = 0
            private set

        override fun currentSnapshot(): SemanticUiSnapshot = snapshot

        override fun receiptSnapshotForObservation(correlation: UiSnapshotCorrelation): SemanticUiSnapshot? {
            receiptReads += correlation
            return receiptSnapshot(correlation)
        }

        override fun retainReceiptSnapshotForCommands(correlation: UiSnapshotCorrelation): Boolean {
            receiptPins += correlation
            return retainReceipt(correlation)
        }

        override fun withdrawReceiptSnapshotForCommands(correlation: UiSnapshotCorrelation) {
            receiptWithdrawals += correlation
            withdrawReceipt(correlation)
        }

        override fun refreshSnapshot(): SemanticUiSnapshot {
            refreshCalls += 1
            return snapshot
        }

        override fun captureVisualSnapshot(): VisualUiCaptureResult {
            captureCalls += 1
            return visualCapture
        }

        override fun requestSensitiveActionApproval(
            command: AccessibilityCommand,
            request: AccessibilityConfirmationRequest,
        ): AccessibilitySensitiveActionApproval = sensitiveActionApproval(command, request)

        override fun submit(
            command: AccessibilityCommand,
            approval: AccessibilityUserApproval?,
            callback: AccessibilityCommandCallback,
        ): Boolean {
            commands += command
            approvals += approval
            callback.onResult(handler(command, approval))
            return true
        }

        companion object {
            private fun succeededStatic(command: AccessibilityCommand) = AccessibilityExecutionResult(
                idempotencyKey = command.idempotencyKey,
                status = AccessibilityExecutionStatus.SUCCEEDED,
                replayed = false,
                observation = AccessibilityObservation.ActionReceipt(
                    null,
                    "test_action_succeeded",
                    CORRELATION,
                    UiDataTrust.LOCAL_SYSTEM,
                ),
                postcondition = AccessibilityPostcondition(
                    AccessibilityPostconditionKind.NODE_ACTION,
                    AccessibilityPostconditionStatus.VERIFIED,
                    "test_action_succeeded",
                    CORRELATION,
                    CORRELATION,
                    UiDataTrust.LOCAL_SYSTEM,
                ),
            )
        }
    }

    private class SequenceAvailabilityProbe(
        vararg values: UiInteractionAvailability,
    ) : UiInteractionAvailabilityProbe {
        private val sequence = ArrayDeque(values.toList())
        private val fallback = values.lastOrNull() ?: UiInteractionAvailability.STATE_UNAVAILABLE

        override fun current(): UiInteractionAvailability =
            if (sequence.isEmpty()) fallback else sequence.removeFirst()
    }

    private class RefreshingSession(
        initial: SemanticUiSnapshot?,
        private val refreshed: SemanticUiSnapshot?,
        private val snapshotFailure: AccessibilitySnapshotFailure? = null,
        private val retainResult: Boolean = true,
        private val handler: ((AccessibilityCommand, AccessibilityUserApproval?) -> AccessibilityExecutionResult)? = null,
    ) : HansAccessibilitySession {
        override val sessionId = CORRELATION.sessionId
        private var current = initial
        var refreshCalls = 0
            private set
        val retainedCorrelations = mutableListOf<UiSnapshotCorrelation>()
        val commands = mutableListOf<AccessibilityCommand>()

        override fun currentSnapshot(): SemanticUiSnapshot? = current

        override fun refreshSnapshot(): SemanticUiSnapshot? {
            refreshCalls += 1
            current = refreshed
            return current
        }

        override fun retainSnapshotForCommands(correlation: UiSnapshotCorrelation): Boolean {
            retainedCorrelations += correlation
            return retainResult && current?.correlation == correlation
        }

        override fun latestSnapshotFailure(): AccessibilitySnapshotFailure? = snapshotFailure

        override fun submit(
            command: AccessibilityCommand,
            approval: AccessibilityUserApproval?,
            callback: AccessibilityCommandCallback,
        ): Boolean {
            val result = handler?.invoke(command, approval) ?: return false
            commands += command
            callback.onResult(result)
            return true
        }
    }

    private companion object {
        val CORRELATION = UiSnapshotCorrelation(
            AccessibilitySessionId("accessibility-session-test"),
            AccessibilityWindowId(7),
            AccessibilitySnapshotId(1),
        )
    }

    private fun exactApproval(request: AccessibilityConfirmationRequest) = AccessibilityUserApproval(
        approvalId = "ui:test-exact-approval",
        idempotencyKey = request.idempotencyKey,
        commandFingerprint = request.commandFingerprint,
        risk = request.risk,
        correlation = request.correlation,
    )
}
