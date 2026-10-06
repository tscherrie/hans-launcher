package ai.hans.standard.phone.accessibility.android

import ai.hans.standard.phone.accessibility.AccessibilityCommand
import ai.hans.standard.phone.accessibility.AccessibilityCommandExecutor
import ai.hans.standard.phone.accessibility.AccessibilityConfirmationRisk
import ai.hans.standard.phone.accessibility.AccessibilityExecutionStatus
import ai.hans.standard.phone.accessibility.AccessibilityGlobalAction
import ai.hans.standard.phone.accessibility.AccessibilityIdempotencyKey
import ai.hans.standard.phone.accessibility.AccessibilityPostconditionStatus
import ai.hans.standard.phone.accessibility.AccessibilitySnapshotId
import ai.hans.standard.phone.accessibility.AccessibilityUserApproval
import ai.hans.standard.phone.accessibility.AndroidAccessibilityAdapterResult
import ai.hans.standard.phone.accessibility.DictationLifecycleStamp
import ai.hans.standard.phone.accessibility.ExactAccessibilityUserConfirmationGate
import ai.hans.standard.phone.accessibility.InMemorySemanticUiSnapshotSource
import ai.hans.standard.phone.accessibility.RawSemanticUiNode
import ai.hans.standard.phone.accessibility.SemanticUiAction
import ai.hans.standard.phone.accessibility.SemanticUiRole
import ai.hans.standard.phone.accessibility.UiBounds
import ai.hans.standard.phone.accessibility.UiCoordinateGesture
import ai.hans.standard.phone.accessibility.UiPoint
import ai.hans.standard.phone.accessibility.UiPostconditionExpectation
import ai.hans.standard.phone.accessibility.UiScrollDirection
import ai.hans.standard.phone.accessibility.UiFindQuery
import ai.hans.standard.phone.accessibility.UiInteractionAvailability
import ai.hans.standard.phone.accessibility.UiInteractionAvailabilityProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootFreeAndroidAccessibilityAdapterTest {
    @Test
    fun retainedInspectFrameSupportsFindAndNodeActionWhileCurrentFrameAdvances() {
        val inspected = snapshot(snapshotId = 1, siblingText = "old status")
        val current = snapshot(snapshotId = 2, siblingText = "new status")
        val after = snapshot(snapshotId = 3, targetText = "target changed")
        val inspectedLocator = locatorFor(
            inspected.nodes[1],
            uniqueId = "target-id",
            path = listOf(0),
        )
        val store = RetainedAccessibilityFrameStore(elapsedRealtimeMillis = { 100L })
        store.publish(frameFor(inspected, inspectedLocator))
        assertTrue(store.retainCurrentForCommand(inspected.correlation))
        store.publish(frameFor(current, locatorFor(
            current.nodes[1],
            uniqueId = "target-id",
            path = listOf(0),
        )))
        val host = FakeRootFreeAccessibilityHost().apply {
            snapshotAfter = after
            observationBaseline = AccessibilitySnapshotId(2)
            nodeLocators[1] = inspectedLocator
            targetResolution = AndroidTargetResolution.Found(
                after.nodes[1],
                locatorFor(after.nodes[1], uniqueId = "target-id", path = listOf(0)),
            )
        }
        val registration = AndroidDictationLifecycleRegistry.register {
            DictationLifecycleStamp(1, false)
        }
        try {
            val executor = AccessibilityCommandExecutor(
                snapshots = store,
                adapter = RootFreeAndroidAccessibilityAdapter(store, host),
                confirmationGate = ExactAccessibilityUserConfirmationGate,
                dictationGuard = AndroidDictationLifecycleRegistry,
                uiAvailability = availableUi(),
            )
            val found = executor.execute(
                AccessibilityCommand.Find(
                    key("retained-find"),
                    inspected.correlation,
                    UiFindQuery(text = "target"),
                ),
            )
            val handle = (found.observation as ai.hans.standard.phone.accessibility.AccessibilityObservation.FoundNodes)
                .handles.single()
            val acted = executor.execute(AccessibilityCommand.Click(key("retained-click"), handle))

            assertEquals(AccessibilityExecutionStatus.SUCCEEDED, found.status)
            assertEquals(AccessibilityExecutionStatus.SUCCEEDED, acted.status)
            assertEquals(inspected.correlation, host.nodeCalls.single().first.correlation)
            assertEquals(current.correlation, store.current()?.correlation)
        } finally {
            registration.close()
        }
    }

    @Test
    fun nodeActionsAreTypedAndActionAcceptedIsHonestlyOnlyObserved() {
        val harness = Harness()

        val click = harness.adapter.click(
            harness.target,
            UiPostconditionExpectation.ACTION_ACCEPTED,
        )
        val text = harness.adapter.setText(
            harness.editor,
            "new value",
            UiPostconditionExpectation.ACTION_ACCEPTED,
        )
        val scroll = harness.adapter.scroll(
            harness.scroller,
            UiScrollDirection.BACKWARD,
            UiPostconditionExpectation.ACTION_ACCEPTED,
        )

        listOf(click, text, scroll).forEach { result ->
            val success = result as AndroidAccessibilityAdapterResult.Success
            assertEquals(
                AccessibilityPostconditionStatus.OBSERVED_NOT_VERIFIED,
                success.postcondition.status,
            )
            assertEquals("android_action_accepted_only", success.postcondition.detailCode)
            assertEquals(harness.before.correlation, success.postcondition.before)
            assertEquals(harness.after.correlation, success.postcondition.after)
        }
        assertTrue(harness.host.nodeCalls[0].second is AndroidNodeAction.Click)
        assertEquals(AndroidNodeAction.SetText("new value"), harness.host.nodeCalls[1].second)
        assertEquals(
            AndroidNodeAction.Scroll(UiScrollDirection.BACKWARD),
            harness.host.nodeCalls[2].second,
        )
        assertEquals(List(3) { harness.before.correlation }, harness.host.snapshotWaitCalls)
    }

    @Test
    fun staleMissingAndHostStatusesMapWithoutPostconditionWait() {
        val harness = Harness()
        val stale = harness.target.copy(correlation = testCorrelation(snapshot = 99))
        assertEquals(
            AndroidAccessibilityAdapterResult.StaleTarget,
            harness.adapter.click(stale, UiPostconditionExpectation.ACTION_ACCEPTED),
        )

        harness.host.nodeStatus = AndroidHostActionStatus.STALE_TARGET
        assertEquals(
            AndroidAccessibilityAdapterResult.StaleTarget,
            harness.adapter.click(harness.target, UiPostconditionExpectation.ACTION_ACCEPTED),
        )
        harness.host.nodeStatus = AndroidHostActionStatus.AMBIGUOUS_TARGET
        assertEquals(
            AndroidAccessibilityAdapterResult.StaleTarget,
            harness.adapter.click(harness.target, UiPostconditionExpectation.ACTION_ACCEPTED),
        )
        harness.host.nodeStatus = AndroidHostActionStatus.UNSUPPORTED
        assertEquals(
            AndroidAccessibilityAdapterResult.Unsupported,
            harness.adapter.click(harness.target, UiPostconditionExpectation.ACTION_ACCEPTED),
        )
        harness.host.nodeStatus = AndroidHostActionStatus.FAILED
        assertFailure(
            harness.adapter.click(harness.target, UiPostconditionExpectation.ACTION_ACCEPTED),
            "android_node_action_failed",
        )
        listOf(
            AndroidHostActionStatus.BLOCKED to "android_node_action_blocked",
            AndroidHostActionStatus.CANCELLED to "android_node_action_cancelled",
            AndroidHostActionStatus.TIMED_OUT to "android_node_action_timed_out",
        ).forEach { (status, code) ->
            harness.host.nodeStatus = status
            assertFailure(
                harness.adapter.click(
                    harness.target,
                    UiPostconditionExpectation.ACTION_ACCEPTED,
                ),
                code,
            )
        }

        assertTrue(harness.host.snapshotWaitCalls.isEmpty())
    }

    @Test
    fun deepUiAvailabilityStatusesPreserveExactActionableCodesAcrossAllHostActions() {
        val mappings = listOf(
            AndroidHostActionStatus.UI_DEVICE_LOCKED to "device_unlock_required",
            AndroidHostActionStatus.UI_SCREEN_NOT_INTERACTIVE to "screen_wake_required",
            AndroidHostActionStatus.UI_DEVICE_LOCKED_AND_SCREEN_NOT_INTERACTIVE to
                "device_wake_and_unlock_required",
            AndroidHostActionStatus.UI_STATE_UNAVAILABLE to
                "device_ui_state_verification_required",
        )
        val gesture = UiCoordinateGesture(UiPoint(10, 20), UiPoint(30, 400), 555)

        mappings.forEach { (status, code) ->
            Harness().also { harness ->
                harness.host.nodeStatus = status
                assertFailure(
                    harness.adapter.click(
                        harness.target,
                        UiPostconditionExpectation.ACTION_ACCEPTED,
                    ),
                    code,
                )
                assertTrue(harness.host.snapshotWaitCalls.isEmpty())
            }
            Harness().also { harness ->
                harness.host.globalStatus = status
                assertFailure(
                    harness.adapter.globalAction(
                        AccessibilityGlobalAction.BACK,
                        UiPostconditionExpectation.ACTION_ACCEPTED,
                    ),
                    code,
                )
                assertTrue(harness.host.snapshotWaitCalls.isEmpty())
            }
            Harness().also { harness ->
                harness.host.gestureStatus = status
                assertFailure(
                    harness.adapter.coordinateGesture(
                        harness.before.correlation,
                        gesture,
                        UiPostconditionExpectation.ACTION_ACCEPTED,
                    ),
                    code,
                )
                assertTrue(harness.host.snapshotWaitCalls.isEmpty())
            }
        }
    }

    @Test
    fun nodeStateChangedMustBeTargetSpecificNotAnUnrelatedScreenMutation() {
        val harness = Harness()
        val unrelated = snapshot(
            snapshotId = 2,
            targetText = "target",
            siblingText = "unrelated changed",
        )
        harness.host.snapshotAfter = unrelated
        harness.host.targetResolution = AndroidTargetResolution.Found(
            unrelated.nodes[1],
            locatorFor(unrelated.nodes[1], uniqueId = "target-id", path = listOf(0)),
        )

        assertFailure(
            harness.adapter.click(
                harness.target,
                UiPostconditionExpectation.NODE_STATE_CHANGED,
            ),
            "postcondition_not_verified",
        )

        val targetChanged = snapshot(
            snapshotId = 3,
            targetText = "target changed",
            siblingText = "sibling",
        )
        harness.host.snapshotAfter = targetChanged
        harness.host.targetResolution = AndroidTargetResolution.Found(
            targetChanged.nodes[1],
            locatorFor(targetChanged.nodes[1], uniqueId = "target-id", path = listOf(0)),
        )
        val changed = harness.adapter.click(
            harness.target,
            UiPostconditionExpectation.NODE_STATE_CHANGED,
        ) as AndroidAccessibilityAdapterResult.Success
        assertEquals("target_node_state_changed", changed.postcondition.detailCode)
    }

    @Test
    fun nodeStateChangedAcceptsAChangeInsideTheTargetsSubtree() {
        val before = semanticSnapshot(
            roots = listOf(
                rootNode(
                    targetNode(
                        text = "container",
                        role = SemanticUiRole.SCROLL_CONTAINER,
                        children = listOf(rawNode(text = "old child")),
                    ),
                ),
            ),
        )
        val after = semanticSnapshot(
            correlation = testCorrelation(snapshot = 2),
            roots = listOf(
                rootNode(
                    targetNode(
                        text = "container",
                        role = SemanticUiRole.SCROLL_CONTAINER,
                        children = listOf(rawNode(text = "new child")),
                    ),
                ),
            ),
        )
        val source = InMemorySemanticUiSnapshotSource().apply { publish(before) }
        val host = FakeRootFreeAccessibilityHost().apply {
            snapshotAfter = after
            val beforeLocator = locatorFor(
                before.nodes[1],
                uniqueId = "scroll-id",
                path = listOf(0),
            )
            nodeLocators[1] = beforeLocator
            targetResolution = AndroidTargetResolution.Found(
                after.nodes[1],
                locatorFor(after.nodes[1], uniqueId = "scroll-id", path = listOf(0)),
            )
        }
        val adapter = RootFreeAndroidAccessibilityAdapter(source, host)

        val result = adapter.scroll(
            before.nodes[1].handle,
            UiScrollDirection.FORWARD,
            UiPostconditionExpectation.NODE_STATE_CHANGED,
        )

        val success = result as AndroidAccessibilityAdapterResult.Success
        assertEquals("target_node_state_changed", success.postcondition.detailCode)
    }

    @Test
    fun textPostconditionTracksUniqueStableTargetAcrossOrdinalChanges() {
        val before = semanticSnapshot(
            roots = listOf(rootNode(editorNode("old"), siblingNode("sibling"))),
        )
        val after = semanticSnapshot(
            correlation = testCorrelation(snapshot = 2),
            roots = listOf(rootNode(siblingNode("sibling"), editorNode("requested"))),
        )
        val source = InMemorySemanticUiSnapshotSource().apply { publish(before) }
        val host = FakeRootFreeAccessibilityHost().apply {
            snapshotAfter = after
            nodeLocators[1] = locatorFor(
                before.nodes[1],
                uniqueId = "editor-id",
                path = listOf(0),
            )
            targetResolution = AndroidTargetResolution.Found(
                after.nodes[2],
                locatorFor(after.nodes[2], uniqueId = "editor-id", path = listOf(1)),
            )
        }
        val adapter = RootFreeAndroidAccessibilityAdapter(source, host)

        val result = adapter.setText(
            before.nodes[1].handle,
            "requested",
            UiPostconditionExpectation.TEXT_EQUALS_REQUEST,
        )

        val success = result as AndroidAccessibilityAdapterResult.Success
        assertEquals("text_value_matches", success.postcondition.detailCode)
    }

    @Test
    fun textPostconditionRejectsOrdinalReuseByADifferentNode() {
        val harness = Harness()
        harness.host.snapshotAfter = semanticSnapshot(
            correlation = testCorrelation(snapshot = 2),
            roots = listOf(
                rootNode(
                    editorNode("requested", bounds = UiBounds(500, 500, 700, 600)),
                    siblingNode("sibling"),
                    scrollerNode(),
                ),
            ),
        )
        harness.host.targetResolution = AndroidTargetResolution.Missing

        assertFailure(
            harness.adapter.setText(
                harness.editor,
                "requested",
                UiPostconditionExpectation.TEXT_EQUALS_REQUEST,
            ),
            "postcondition_not_verified",
        )
    }

    @Test
    fun textPostconditionRejectsAmbiguousStableIdentity() {
        val before = semanticSnapshot(
            roots = listOf(rootNode(editorNode("old"))),
        )
        val duplicatedEditor = editorNode("requested")
        val after = semanticSnapshot(
            correlation = testCorrelation(snapshot = 2),
            roots = listOf(rootNode(duplicatedEditor, editorNode("old again"))),
        )
        val source = InMemorySemanticUiSnapshotSource().apply { publish(before) }
        val host = FakeRootFreeAccessibilityHost().apply {
            snapshotAfter = after
            nodeLocators[1] = locatorFor(
                before.nodes[1],
                uniqueId = "editor-id",
                path = listOf(0),
            )
            targetResolution = AndroidTargetResolution.Ambiguous
        }
        val adapter = RootFreeAndroidAccessibilityAdapter(source, host)

        assertFailure(
            adapter.setText(
                before.nodes[1].handle,
                "requested",
                UiPostconditionExpectation.TEXT_EQUALS_REQUEST,
            ),
            "postcondition_not_verified",
        )
    }

    @Test
    fun targetDisappearedRequiresNoRemainingStableIdentity() {
        val harness = Harness()
        harness.host.snapshotAfter = semanticSnapshot(
            correlation = testCorrelation(snapshot = 2),
            roots = listOf(rootNode(siblingNode("sibling"), scrollerNode())),
        )
        harness.host.targetResolution = AndroidTargetResolution.Missing
        val gone = harness.adapter.click(
            harness.target,
            UiPostconditionExpectation.TARGET_DISAPPEARED,
        ) as AndroidAccessibilityAdapterResult.Success
        assertEquals("target_disappeared", gone.postcondition.detailCode)

        harness.host.snapshotAfter = semanticSnapshot(
            correlation = testCorrelation(snapshot = 3),
            roots = listOf(rootNode(targetNode("duplicate"), targetNode("another duplicate"))),
        )
        harness.host.targetResolution = AndroidTargetResolution.Ambiguous
        assertFailure(
            harness.adapter.click(
                harness.target,
                UiPostconditionExpectation.TARGET_DISAPPEARED,
            ),
            "postcondition_not_verified",
        )
    }

    @Test
    fun passwordTextIsExplicitlyObservedButNotClaimedVerified() {
        val before = semanticSnapshot(
            roots = listOf(rootNode(passwordNode())),
        )
        val after = semanticSnapshot(
            correlation = testCorrelation(snapshot = 2),
            roots = listOf(rootNode(passwordNode())),
        )
        val source = InMemorySemanticUiSnapshotSource().apply { publish(before) }
        val host = FakeRootFreeAccessibilityHost().apply {
            snapshotAfter = after
            nodeLocators[1] = locatorFor(
                before.nodes[1],
                uniqueId = "password-id",
                path = listOf(0),
            )
            targetResolution = AndroidTargetResolution.Found(
                after.nodes[1],
                locatorFor(after.nodes[1], uniqueId = "password-id", path = listOf(0)),
            )
        }

        val result = RootFreeAndroidAccessibilityAdapter(source, host).setText(
            before.nodes[1].handle,
            "secret",
            UiPostconditionExpectation.TEXT_EQUALS_REQUEST,
        ) as AndroidAccessibilityAdapterResult.Success

        assertEquals(AccessibilityPostconditionStatus.OBSERVED_NOT_VERIFIED, result.postcondition.status)
        assertEquals("password_text_not_observable", result.postcondition.detailCode)
        assertEquals("android_action_observed", result.actionCode)
    }

    @Test
    fun windowPostconditionChecksWindowAndRootIdentity() {
        val harness = Harness()
        harness.host.snapshotAfter = snapshot(snapshotId = 2)
        assertFailure(
            harness.adapter.globalAction(
                AccessibilityGlobalAction.HOME,
                UiPostconditionExpectation.WINDOW_CHANGED,
            ),
            "postcondition_not_verified",
        )

        harness.host.snapshotAfter = semanticSnapshot(
            correlation = testCorrelation(snapshot = 3, window = 8),
            roots = listOf(rootNode(targetNode(), siblingNode(), scrollerNode())),
        )
        val byWindow = harness.adapter.globalAction(
            AccessibilityGlobalAction.HOME,
            UiPostconditionExpectation.WINDOW_CHANGED,
        ) as AndroidAccessibilityAdapterResult.Success
        assertEquals("window_changed", byWindow.postcondition.detailCode)

        harness.host.snapshotAfter = semanticSnapshot(
            correlation = testCorrelation(snapshot = 4),
            roots = listOf(
                rootNode(
                    targetNode(),
                    siblingNode(),
                    scrollerNode(),
                    packageName = "different.package",
                ),
            ),
        )
        val byRoot = harness.adapter.globalAction(
            AccessibilityGlobalAction.BACK,
            UiPostconditionExpectation.WINDOW_CHANGED,
        ) as AndroidAccessibilityAdapterResult.Success
        assertEquals("window_changed", byRoot.postcondition.detailCode)
    }

    @Test
    fun globalAndGestureStatusesAreTypedAndGesturesPreserveExactCoordinates() {
        val harness = Harness()
        harness.host.globalStatus = AndroidHostActionStatus.UNSUPPORTED
        assertEquals(
            AndroidAccessibilityAdapterResult.Unsupported,
            harness.adapter.globalAction(
                AccessibilityGlobalAction.RECENTS,
                UiPostconditionExpectation.ACTION_ACCEPTED,
            ),
        )
        harness.host.globalStatus = AndroidHostActionStatus.FAILED
        assertFailure(
            harness.adapter.globalAction(
                AccessibilityGlobalAction.BACK,
                UiPostconditionExpectation.ACTION_ACCEPTED,
            ),
            "android_global_action_failed",
        )

        harness.host.gesturesSupported = false
        assertFalse(harness.adapter.supportsCoordinateGestures())
        harness.host.gesturesSupported = true
        val gesture = UiCoordinateGesture(UiPoint(10, 20), UiPoint(30, 400), 555)
        val success = harness.adapter.coordinateGesture(
            harness.before.correlation,
            gesture,
            UiPostconditionExpectation.ACTION_ACCEPTED,
        ) as AndroidAccessibilityAdapterResult.Success
        assertEquals(listOf(harness.before.correlation to gesture), harness.host.gestureCalls)
        assertEquals("android_action_accepted_only", success.postcondition.detailCode)

        harness.host.gestureStatus = AndroidHostActionStatus.STALE_TARGET
        assertEquals(
            AndroidAccessibilityAdapterResult.StaleTarget,
            harness.adapter.coordinateGesture(
                harness.before.correlation,
                gesture,
                UiPostconditionExpectation.ACTION_ACCEPTED,
            ),
        )
        harness.host.gestureStatus = AndroidHostActionStatus.UNSUPPORTED
        assertEquals(
            AndroidAccessibilityAdapterResult.Unsupported,
            harness.adapter.coordinateGesture(
                harness.before.correlation,
                gesture,
                UiPostconditionExpectation.ACTION_ACCEPTED,
            ),
        )
        harness.host.gestureStatus = AndroidHostActionStatus.FAILED
        assertFailure(
            harness.adapter.coordinateGesture(
                harness.before.correlation,
                gesture,
                UiPostconditionExpectation.ACTION_ACCEPTED,
            ),
            "android_gesture_failed",
        )
        listOf(
            AndroidHostActionStatus.BLOCKED to "android_gesture_blocked",
            AndroidHostActionStatus.CANCELLED to "android_gesture_cancelled",
            AndroidHostActionStatus.TIMED_OUT to "android_gesture_timed_out",
        ).forEach { (status, code) ->
            harness.host.gestureStatus = status
            assertFailure(
                harness.adapter.coordinateGesture(
                    harness.before.correlation,
                    gesture,
                    UiPostconditionExpectation.ACTION_ACCEPTED,
                ),
                code,
            )
        }
    }

    @Test
    fun missingOrCrossSessionPostconditionSnapshotFailsClosed() {
        val harness = Harness()
        harness.host.snapshotAfter = null
        assertFailure(
            harness.adapter.click(harness.target, UiPostconditionExpectation.ACTION_ACCEPTED),
            "postcondition_snapshot_unavailable",
        )

        harness.host.snapshotAfter = snapshot(
            snapshotId = 2,
            session = "accessibility-session-9999",
        )
        assertFailure(
            harness.adapter.click(harness.target, UiPostconditionExpectation.ACTION_ACCEPTED),
            "postcondition_snapshot_unavailable",
        )
    }

    @Test
    fun postconditionSnapshotMustBeNewerThanBaselineSampledAfterActionAcceptance() {
        val harness = Harness()
        harness.host.observationBaseline = AccessibilitySnapshotId(5)
        harness.host.snapshotAfter = snapshot(snapshotId = 4)

        assertFailure(
            harness.adapter.click(harness.target, UiPostconditionExpectation.ACTION_ACCEPTED),
            "postcondition_snapshot_unavailable",
        )

        // A pre-action frame retained by event coalescing is still not post-action evidence,
        // including the exact baseline id (not just an older frame).
        harness.host.snapshotAfter = snapshot(snapshotId = 5)
        assertFailure(
            harness.adapter.click(harness.target, UiPostconditionExpectation.ACTION_ACCEPTED),
            "postcondition_snapshot_unavailable",
        )

        harness.host.snapshotAfter = snapshot(snapshotId = 6)
        val accepted = harness.adapter.click(
            harness.target,
            UiPostconditionExpectation.ACTION_ACCEPTED,
        ) as AndroidAccessibilityAdapterResult.Success
        assertEquals(6L, accepted.resultingCorrelation?.snapshotId?.value)
    }

    @Test
    fun sensitiveActionsCannotReachHostWithoutExactApproval() {
        val harness = Harness()
        val registration = AndroidDictationLifecycleRegistry.register {
            DictationLifecycleStamp(44, true)
        }
        try {
            val executor = AccessibilityCommandExecutor(
                snapshots = harness.source,
                adapter = harness.adapter,
                confirmationGate = ExactAccessibilityUserConfirmationGate,
                dictationGuard = AndroidDictationLifecycleRegistry,
                uiAvailability = availableUi(),
            )
            val command = AccessibilityCommand.Click(
                idempotencyKey = key("external-send"),
                handle = harness.target,
                confirmationRisk = AccessibilityConfirmationRisk.EXTERNAL_COMMUNICATION,
            )

            val pending = executor.execute(command)

            assertEquals(AccessibilityExecutionStatus.CONFIRMATION_REQUIRED, pending.status)
            assertTrue(harness.host.nodeCalls.isEmpty())
            val request = checkNotNull(pending.requiredConfirmation)
            val approved = executor.execute(
                command,
                AccessibilityUserApproval(
                    approvalId = "approval-0001",
                    idempotencyKey = request.idempotencyKey,
                    commandFingerprint = request.commandFingerprint,
                    risk = request.risk,
                    correlation = request.correlation,
                ),
            )
            assertEquals(AccessibilityExecutionStatus.SUCCEEDED, approved.status)
            assertEquals(1, harness.host.nodeCalls.size)
        } finally {
            registration.close()
        }
    }

    @Test
    fun passwordRiskIsHardenedToCredentialConfirmation() {
        val before = semanticSnapshot(roots = listOf(rootNode(passwordNode())))
        val after = semanticSnapshot(
            correlation = testCorrelation(snapshot = 2),
            roots = listOf(rootNode(passwordNode())),
        )
        val source = InMemorySemanticUiSnapshotSource().apply { publish(before) }
        val host = FakeRootFreeAccessibilityHost().apply { snapshotAfter = after }
        val registration = AndroidDictationLifecycleRegistry.register {
            DictationLifecycleStamp(1, false)
        }
        try {
            val result = AccessibilityCommandExecutor(
                snapshots = source,
                adapter = RootFreeAndroidAccessibilityAdapter(source, host),
                confirmationGate = ExactAccessibilityUserConfirmationGate,
                dictationGuard = AndroidDictationLifecycleRegistry,
                uiAvailability = availableUi(),
            ).execute(
                AccessibilityCommand.SetText(
                    key("credential-entry"),
                    before.nodes[1].handle,
                    "secret",
                ),
            )

            assertEquals(AccessibilityExecutionStatus.CONFIRMATION_REQUIRED, result.status)
            assertEquals(
                AccessibilityConfirmationRisk.CREDENTIAL_UI,
                result.requiredConfirmation?.risk,
            )
            assertTrue(host.nodeCalls.isEmpty())
        } finally {
            registration.close()
        }
    }

    @Test
    fun appControlDoesNotStopActiveDictationAndDetectsAnyLifecycleMutation() {
        val harness = Harness()
        var stamp = DictationLifecycleStamp(50, true)
        val registration = AndroidDictationLifecycleRegistry.register { stamp }
        try {
            fun executor() = AccessibilityCommandExecutor(
                snapshots = harness.source,
                adapter = harness.adapter,
                confirmationGate = ExactAccessibilityUserConfirmationGate,
                dictationGuard = AndroidDictationLifecycleRegistry,
                uiAvailability = availableUi(),
            )
            val stable = executor().execute(
                AccessibilityCommand.Click(key("dictation-stable"), harness.target),
            )
            assertEquals(AccessibilityExecutionStatus.SUCCEEDED, stable.status)
            assertTrue(stamp.recordingActive)

            harness.host.onNodeAction = {
                stamp = DictationLifecycleStamp(51, false)
            }
            val interfered = executor().execute(
                AccessibilityCommand.Click(key("dictation-mutated"), harness.target),
            )
            assertEquals(AccessibilityExecutionStatus.FAILED, interfered.status)
            assertEquals("dictation_lifecycle_interference", interfered.errorCode)
        } finally {
            registration.close()
        }
    }

    private class Harness {
        val before = snapshot(snapshotId = 1)
        val after = snapshot(snapshotId = 2)
        val source = InMemorySemanticUiSnapshotSource().apply { publish(before) }
        val host = FakeRootFreeAccessibilityHost().apply {
            snapshotAfter = after
            nodeLocators[1] = locatorFor(
                before.nodes[1],
                uniqueId = "target-id",
                path = listOf(0),
            )
            nodeLocators[2] = locatorFor(
                before.nodes[2],
                uniqueId = "editor-id",
                path = listOf(1),
            )
            nodeLocators[3] = locatorFor(
                before.nodes[3],
                uniqueId = "scroller-id",
                path = listOf(2),
            )
            targetResolution = AndroidTargetResolution.Found(
                after.nodes[1],
                locatorFor(after.nodes[1], uniqueId = "target-id", path = listOf(0)),
            )
        }
        val adapter = RootFreeAndroidAccessibilityAdapter(source, host)
        val target = before.nodes[1].handle
        val editor = before.nodes[2].handle
        val scroller = before.nodes[3].handle
    }

    private companion object {
        fun availableUi(): UiInteractionAvailabilityProbe = UiInteractionAvailabilityProbe {
            UiInteractionAvailability.AVAILABLE
        }

        fun snapshot(
            snapshotId: Long,
            targetText: String = "target",
            siblingText: String = "sibling",
            session: String = "accessibility-session-0001",
        ) = semanticSnapshot(
            correlation = testCorrelation(snapshot = snapshotId, session = session),
            roots = listOf(
                rootNode(
                    targetNode(targetText),
                    editorNode(siblingText),
                    scrollerNode(),
                ),
            ),
        )

        fun rootNode(
            vararg children: RawSemanticUiNode,
            packageName: String = "test.package",
        ) = rawNode(
            packageName = packageName,
            className = "android.widget.FrameLayout",
            role = SemanticUiRole.UNKNOWN,
            bounds = TEST_DISPLAY_BOUNDS,
            children = children.toList(),
        )

        fun targetNode(
            text: String = "target",
            role: SemanticUiRole = SemanticUiRole.BUTTON,
            children: List<RawSemanticUiNode> = emptyList(),
        ) = rawNode(
            text = text,
            className = "android.widget.Button",
            role = role,
            bounds = UiBounds(20, 20, 220, 120),
            clickable = role == SemanticUiRole.BUTTON,
            scrollable = role == SemanticUiRole.SCROLL_CONTAINER,
            actions = when (role) {
                SemanticUiRole.BUTTON -> setOf(SemanticUiAction.CLICK)
                SemanticUiRole.SCROLL_CONTAINER -> setOf(
                    SemanticUiAction.SCROLL_FORWARD,
                    SemanticUiAction.SCROLL_BACKWARD,
                )
                else -> emptySet()
            },
            children = children,
        )

        fun editorNode(
            text: String,
            bounds: UiBounds = UiBounds(20, 140, 500, 240),
        ) = rawNode(
            text = text,
            className = "android.widget.EditText",
            role = SemanticUiRole.EDIT_TEXT,
            bounds = bounds,
            editable = true,
            actions = setOf(SemanticUiAction.SET_TEXT),
        )

        fun passwordNode() = rawNode(
            className = "android.widget.EditText",
            role = SemanticUiRole.PASSWORD_FIELD,
            bounds = UiBounds(20, 140, 500, 240),
            editable = true,
            actions = setOf(SemanticUiAction.SET_TEXT),
        )

        fun siblingNode(text: String = "sibling") = rawNode(
            text = text,
            className = "android.widget.TextView",
            role = SemanticUiRole.TEXT,
            bounds = UiBounds(20, 260, 500, 340),
        )

        fun scrollerNode() = rawNode(
            className = "android.widget.ScrollView",
            role = SemanticUiRole.SCROLL_CONTAINER,
            bounds = UiBounds(0, 360, 1_080, 2_000),
            scrollable = true,
            actions = setOf(
                SemanticUiAction.SCROLL_FORWARD,
                SemanticUiAction.SCROLL_BACKWARD,
            ),
        )

        fun key(value: String) = AccessibilityIdempotencyKey(value.padEnd(8, '-'))

        fun locatorFor(
            node: ai.hans.standard.phone.accessibility.SemanticUiNode,
            uniqueId: String,
            path: List<Int>,
        ) = AndroidNodeLocator(
            nodeOrdinal = node.handle.nodeOrdinal,
            displayId = 0,
            windowId = node.handle.correlation.windowId.value,
            uniqueId = uniqueId,
            viewIdResourceName = null,
            structuralPath = path,
            fingerprint = AndroidNodeFingerprint(
                packageName = node.packageName?.value,
                className = node.className?.value,
                role = node.role,
                bounds = node.bounds,
                visible = node.visible,
                enabled = node.enabled,
                clickable = node.clickable,
                editable = node.editable,
                scrollable = node.scrollable,
                actions = node.actions,
            ),
        )

        fun frameFor(
            snapshot: ai.hans.standard.phone.accessibility.SemanticUiSnapshot,
            vararg locators: AndroidNodeLocator,
        ) = AndroidAccessibilityFrame(
            snapshot = snapshot,
            displayId = 0,
            locators = locators.toList(),
        )

        fun assertFailure(result: AndroidAccessibilityAdapterResult, code: String) {
            val failure = result as AndroidAccessibilityAdapterResult.Failure
            assertEquals(code, failure.code)
        }
    }
}
