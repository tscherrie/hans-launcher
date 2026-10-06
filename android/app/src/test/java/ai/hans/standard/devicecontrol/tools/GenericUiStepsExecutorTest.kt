package ai.hans.standard.devicecontrol.tools

import ai.hans.standard.codex.*
import ai.hans.standard.phone.accessibility.*
import ai.hans.standard.phone.accessibility.android.AccessibilityCommandCallback
import ai.hans.standard.phone.accessibility.android.EventDrivenSemanticSnapshotWaiter
import ai.hans.standard.phone.accessibility.android.HansAccessibilitySession
import ai.hans.standard.remotecontrol.CrossTurnPhoneToolFence
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Only synthetic views/commands: this suite never opens an app or controls a real device. */
class GenericUiStepsExecutorTest {
    @Test fun malformedUnknownNullAndOversizedFieldsNeverReachTheSessionOrLauncher() {
        val malformed = listOf(
            "not json", "[]", "null", "{}",
            plan().put("unexpected", true).toString(),
            plan().put("packageName", JSONObject.NULL).toString(),
            plan().put("packageName", "invalid").toString(),
            plan().put("launch", "true").toString(),
            plan().put("launch", JSONObject.NULL).toString(),
            plan().put("timeoutMs", 100.5).toString(),
            plan().put("timeoutMs", 99).toString(),
            plan().put("timeoutMs", 5_001).toString(),
            plan().put("timeoutMs", JSONObject.NULL).toString(),
            plan().put("steps", JSONObject.NULL).toString(),
            plan().put("steps", JSONArray()).toString(),
            plan(*Array(5) { click() }).toString(),
            plan(click().put("extra", true)).toString(),
            plan(click().put("action", JSONObject.NULL)).toString(),
            plan(click().put("action", "long_click")).toString(),
            plan(click().put("target", JSONObject.NULL)).toString(),
            plan(click().put("target", JSONObject())).toString(),
            plan(click().put("target", JSONObject().put("unknown", "Next"))).toString(),
            plan(click().put("target", JSONObject().put("text", JSONObject.NULL))).toString(),
            plan(click().put("target", JSONObject().put("text", 123))).toString(),
            plan(click().put("target", JSONObject().put("text", "x".repeat(513)))).toString(),
            plan(click().put("target", JSONObject().put("className", "x".repeat(256)))).toString(),
            plan(click().put("target", JSONObject().put("role", "invented"))).toString(),
            plan(click().put("value", "unwanted")).toString(),
            plan(click().put("postcondition", "text_equals_request")).toString(),
            plan(click().put("postcondition", JSONObject.NULL)).toString(),
            plan(edit().removeValue()).toString(),
            plan(edit().put("value", JSONObject.NULL)).toString(),
            plan(edit().put("value", "x".repeat(32_769))).toString(),
        )
        malformed.forEachIndexed { index, json ->
            val fixture = Fixture()
            assertCode("invalid_arguments", fixture.result(call(json, "invalid-$index")))
            assertEquals(0, fixture.session.waits)
            assertTrue(fixture.session.submitted.isEmpty())
            assertTrue(fixture.launches.isEmpty())
        }
    }

    @Test fun wrongNamespaceAndToolAreRejectedWithoutActions() {
        val fixture = Fixture()
        assertCode("invalid_arguments", fixture.result(call().copy(namespace = "android", callId = "namespace")))
        assertCode("invalid_arguments", fixture.result(call().copy(tool = "click_ui", callId = "tool")))
        assertEquals(0, fixture.session.waits)
    }

    @Test fun optionalLaunchRunsOnceBeforeFirstFreshViewAndDuplicateDoesNothing() {
        val fixture = Fixture()
        val order = mutableListOf<String>()
        fixture.onLaunch = { order += "launch" }
        fixture.session.onAwait = { order += "view" }
        fixture.session.views += snapshot(1)
        val request = call(plan().put("launch", true).toString())
        val first = fixture.result(request)
        assertTrue(first.success)
        assertEquals(listOf("launch", "view"), order)
        assertEquals(1, fixture.launches.size)
        val launch = fixture.launches.single()
        assertEquals("android", launch.namespace)
        assertEquals("launch_app", launch.tool)
        assertEquals(setOf("packageName"), JSONObject(launch.argumentsJson).keys().asSequence().toSet())
        assertEquals(PACKAGE, JSONObject(launch.argumentsJson).getString("packageName"))
        assertReplayOf(first, fixture.result(request))
        assertEquals(1, fixture.launches.size)
        assertEquals(1, fixture.session.executed.size)
    }

    @Test fun clickThenTextUsesFreshUniqueHandlesAndReturnsNoIntermediateTrees() {
        val fixture = Fixture()
        fixture.session.views += snapshot(1)
        fixture.session.views += snapshot(2, nodes = listOf(editNode()))
        val result = fixture.result(call(plan(click(), edit()).toString()))
        assertTrue(result.success)
        assertEquals(2, fixture.session.waits)
        assertEquals(0, fixture.session.currentReads)
        assertEquals(0, fixture.session.refreshes)
        val first = fixture.session.executed[0] as AccessibilityCommand.Click
        val second = fixture.session.executed[1] as AccessibilityCommand.SetText
        assertEquals(1L, first.handle.correlation.snapshotId.value)
        assertEquals(2L, second.handle.correlation.snapshotId.value)
        assertEquals("hello", second.value)
        assertEquals(UiPostconditionExpectation.TEXT_EQUALS_REQUEST, second.postcondition)
        assertNotEquals(first.idempotencyKey, second.idempotencyKey)
        val json = JSONObject(result.contentText)
        assertFalse(json.getBoolean("intermediateViewsReturned"))
        assertEquals(2, json.getJSONArray("steps").length())
        assertFalse(result.contentText.contains("\"nodes\""))
        assertFalse(result.contentText.contains("nextObservation"))
        assertFalse(result.contentText.contains("hello"))
        assertTrue(fixture.launches.isEmpty())
    }

    @Test fun explicitExactSelectorConjunctionAndPostconditionReachExistingCommand() {
        val fixture = Fixture()
        fixture.session.views += snapshot(1, nodes = listOf(
            button().copy(contentDescription = "Go"), button().copy(contentDescription = "Other"),
        ))
        val target = JSONObject().put("text", "Next").put("contentDescription", "Go")
            .put("className", "android.widget.Button").put("role", "button")
        val result = fixture.result(call(plan(click().put("target", target)
            .put("postcondition", "target_disappeared")).toString()))
        assertTrue(result.success)
        val action = fixture.session.executed.single() as AccessibilityCommand.Click
        assertEquals(0, action.handle.nodeOrdinal)
        assertEquals(UiPostconditionExpectation.TARGET_DISAPPEARED, action.postcondition)
    }

    @Test fun completeAmbiguousSnapshotFailsClosedWithoutWaitingForAnotherView() {
        val fixture = Fixture()
        fixture.session.views += snapshot(1, nodes = listOf(button(), button()))
        fixture.session.views += snapshot(2)
        assertCode("ui_target_ambiguous", fixture.result(call()))
        assertEquals(1, fixture.session.predicateChecks)
        assertTrue(fixture.session.submitted.isEmpty())
    }

    @Test fun temporaryIncompleteSnapshotWaitsForFreshCompleteUniqueTarget() {
        val fixture = Fixture()
        fixture.session.views += snapshot(1, incomplete = true)
        fixture.session.views += snapshot(2)
        assertTrue(fixture.result(call()).success)
        assertEquals(1, fixture.session.waits)
        assertEquals(2, fixture.session.predicateChecks)
        assertEquals(2L, (fixture.session.executed.single() as AccessibilityCommand.Click)
            .handle.correlation.snapshotId.value)
        assertEquals(0, fixture.session.currentReads)
        assertEquals(0, fixture.session.refreshes)
    }

    @Test fun incompleteThenCompleteAmbiguousSnapshotStillNeverSubmits() {
        val fixture = Fixture()
        fixture.session.views += snapshot(1, incomplete = true)
        fixture.session.views += snapshot(2, nodes = listOf(button(), button()))
        fixture.session.views += snapshot(3)
        assertCode("ui_target_ambiguous", fixture.result(call()))
        assertEquals(2, fixture.session.predicateChecks)
        assertTrue(fixture.session.submitted.isEmpty())
    }

    @Test fun persistentIncompleteSnapshotAtDeadlineFailsClosedWithNoUiContent() {
        val fixture = Fixture()
        fixture.session.views += snapshot(1, incomplete = true)
        fixture.session.views += snapshot(2, incomplete = true)
        fixture.session.onObservation = { if (it.correlation.snapshotId.value == 2L) fixture.now = 100 }
        val result = fixture.result(call(plan().put("timeoutMs", 100).toString()))
        assertCode("ui_target_snapshot_incomplete", result)
        assertEquals(listOf(100L), fixture.session.waitTimeouts)
        assertEquals(2, fixture.session.predicateChecks)
        assertTrue(fixture.session.submitted.isEmpty())
        assertFalse(result.contentText.contains("Next"))
        assertFalse(result.contentText.contains(PACKAGE))
        assertFalse(result.contentText.contains("nodes"))
        assertFalse(JSONObject(result.contentText).getBoolean("retrySequence"))
    }

    @Test fun laterMissingOrWrongAppViewClearsStaleIncompleteWaitDiagnosis() {
        listOf(button().copy(text = "Other"), button().copy(packageName = "other.example")).forEach { node ->
            val fixture = Fixture()
            fixture.session.views += snapshot(1, incomplete = true)
            fixture.session.views += snapshot(2, nodes = listOf(node))
            assertCode("ui_target_wait_expired", fixture.result(call()))
            assertTrue(fixture.session.submitted.isEmpty())
        }
    }

    @Test fun completedStepIsNeverRepeatedWhileNextSnapshotBecomesComplete() {
        val fixture = Fixture()
        fixture.session.views += snapshot(1)
        fixture.session.views += snapshot(2, nodes = listOf(editNode()), incomplete = true)
        fixture.session.views += snapshot(3, nodes = listOf(editNode()))
        fixture.session.afterAction = { fixture.now += 30 }
        val result = fixture.result(call(plan(click(), edit()).put("timeoutMs", 100).toString()))
        assertTrue(result.success)
        assertEquals(listOf(100L, 70L), fixture.session.waitTimeouts)
        assertEquals(3, fixture.session.predicateChecks)
        assertEquals(2, fixture.session.executed.size)
        assertEquals(1L, (fixture.session.executed[0] as AccessibilityCommand.Click).handle.correlation.snapshotId.value)
        assertEquals(3L, (fixture.session.executed[1] as AccessibilityCommand.SetText).handle.correlation.snapshotId.value)
        assertEquals(2, JSONObject(result.contentText).getJSONArray("steps").length())
        assertFalse(result.contentText.contains("hello"))
        assertFalse(result.contentText.contains("\"nodes\""))
    }

    @Test fun incompleteWaitNeverRenewsDeadlineAfterLaunchOrOnLaterPublication() {
        val fixture = Fixture()
        fixture.onLaunch = { fixture.now = 30 }
        fixture.session.views += snapshot(1, incomplete = true)
        fixture.session.views += snapshot(2)
        fixture.session.onObservation = { fixture.now = if (it.isComplete) 100 else 90 }
        assertCode("ui_sequence_deadline_exceeded", fixture.result(call(plan()
            .put("launch", true).put("timeoutMs", 100).toString())))
        assertEquals(listOf(70L), fixture.session.waitTimeouts)
        assertEquals(1, fixture.launches.size)
        assertTrue(fixture.session.submitted.isEmpty())
    }

    @Test fun contextChangeDuringIncompleteObservationStopsWithoutLateSubmission() {
        listOf("session", "epoch", "locked").forEach { kind ->
            val fixture = Fixture()
            fixture.session.views += snapshot(1, incomplete = true)
            fixture.session.views += snapshot(2)
            fixture.session.onObservation = { fixture.changeContext(kind) }
            assertCode("ui_sequence_cancelled_or_context_changed", fixture.result(call()))
            assertEquals(1, fixture.session.predicateChecks)
            assertTrue(fixture.session.submitted.isEmpty())
        }
    }

    @Test fun unexpectedIncompleteReturnedViewIsRecheckedBeforeSubmission() {
        val fixture = Fixture()
        fixture.session.awaitDelegate = { _, _, _ -> snapshot(1, incomplete = true).also { fixture.session.current = it } }
        assertCode("ui_target_snapshot_incomplete", fixture.result(call()))
        assertTrue(fixture.session.submitted.isEmpty())
    }

    @Test(timeout = 10_000) fun realEventDrivenWaiterAcceptsOnlyPublishedCompleteFrameWithoutRecapture() {
        val fixture = Fixture(queued = true)
        val published = AtomicReference<SemanticUiSnapshot?>()
        val incompleteObserved = CountDownLatch(1)
        val captures = AtomicInteger()
        val waiter = EventDrivenSemanticSnapshotWaiter(
            freshSnapshot = { captures.incrementAndGet(); snapshot(1, incomplete = true).also(published::set) },
            currentSnapshot = published::get,
            elapsedRealtimeMillis = { System.nanoTime() / 1_000_000 },
        )
        fixture.session.awaitDelegate = { timeout, cancelled, predicate ->
            waiter.await(timeout, cancelled, { true }) { view ->
                fixture.session.current = view
                val accepted = predicate(view)
                if (!view.isComplete) {
                    assertFalse("Incomplete frames must keep waiting", accepted)
                    incompleteObserved.countDown()
                }
                accepted
            }
        }
        fixture.session.wakeDelegate = waiter::wake
        val result = AtomicReference<DynamicToolExecutionResult?>()
        val handle = fixture.executor.executeCancellable(call(), DynamicToolCancellation.NONE, result::set)
        val worker = start(fixture.jobs.single())
        try {
            assertTrue(incompleteObserved.await(2, TimeUnit.SECONDS))
            assertNull(result.get())
            assertTrue(fixture.session.submitted.isEmpty())
            assertEquals(1, captures.get())
            published.set(snapshot(2))
            waiter.wake()
            worker.await()
            assertTrue(checkNotNull(result.get()).success)
            assertEquals(1, captures.get())
            assertEquals(2L, (fixture.session.executed.single() as AccessibilityCommand.Click)
                .handle.correlation.snapshotId.value)
        } finally {
            handle.cancel()
            worker.await()
        }
    }

    @Test(timeout = 10_000) fun realEventDrivenIncompleteWaitExpiresWithoutRecaptureOrAction() {
        val fixture = Fixture()
        val published = AtomicReference<SemanticUiSnapshot?>()
        val captures = AtomicInteger()
        val observations = AtomicInteger()
        val waiter = EventDrivenSemanticSnapshotWaiter(
            freshSnapshot = { captures.incrementAndGet(); snapshot(1, incomplete = true).also(published::set) },
            currentSnapshot = published::get,
            elapsedRealtimeMillis = { System.nanoTime() / 1_000_000 },
        )
        fixture.session.awaitDelegate = { timeout, cancelled, predicate ->
            waiter.await(timeout, cancelled, { true }) { view ->
                fixture.session.current = view
                observations.incrementAndGet()
                predicate(view)
            }
        }
        assertCode("ui_target_snapshot_incomplete", fixture.result(call(plan().put("timeoutMs", 100).toString())))
        assertEquals(1, captures.get())
        assertEquals(1, observations.get())
        assertEquals(listOf(100L), fixture.session.waitTimeouts)
        assertTrue(fixture.session.submitted.isEmpty())
    }

    @Test fun packageVisibilityEnabledActionAndEditableMustAllMatch() {
        val invalidNodes = listOf(
            button().copy(packageName = "other.example"), button().copy(visible = false),
            button().copy(enabled = false), button().copy(actions = emptySet()),
            button().copy(text = "Next "), button().copy(text = "next"),
        )
        invalidNodes.forEach { node ->
            val fixture = Fixture()
            fixture.session.views += snapshot(1, nodes = listOf(node))
            assertCode("ui_target_wait_expired", fixture.result(call()))
            assertTrue(fixture.session.submitted.isEmpty())
        }
        val fixture = Fixture()
        fixture.session.views += snapshot(1, nodes = listOf(editNode().copy(editable = false)))
        assertCode("ui_target_wait_expired", fixture.result(call(plan(edit()).toString())))
        assertTrue(fixture.session.submitted.isEmpty())
    }

    @Test fun wrongAppCanBecomeReadyOnFreshEventWithoutPollingOrActionRetry() {
        val fixture = Fixture()
        fixture.session.views += snapshot(1, nodes = listOf(button().copy(packageName = "other.example")))
        fixture.session.views += snapshot(2)
        assertTrue(fixture.result(call()).success)
        assertEquals(1, fixture.session.waits)
        assertEquals(2, fixture.session.predicateChecks)
        assertEquals(1, fixture.session.executed.size)
    }

    @Test fun missingSessionOrUnavailableDeviceNeverLaunches() {
        val missing = Fixture().apply { activeSession = null }
        assertCode("accessibility_session_unavailable", missing.result(call(plan().put("launch", true).toString())))
        val locked = Fixture().apply { available = false }
        assertCode("ui_sequence_context_unavailable", locked.result(call(plan().put("launch", true).toString())))
        assertTrue(missing.launches.isEmpty())
        assertTrue(locked.launches.isEmpty())
    }

    @Test fun foreignSessionCorrelationAndFailedRetentionNeverSubmit() {
        val foreign = Fixture()
        foreign.session.views += snapshot(1, session = AccessibilitySessionId("other-session"))
        assertCode("ui_target_snapshot_unavailable", foreign.result(call()))
        val unavailable = Fixture()
        unavailable.session.views += snapshot(1)
        unavailable.session.retain = false
        assertCode("ui_target_snapshot_unavailable", unavailable.result(call()))
        assertTrue(foreign.session.submitted.isEmpty())
        assertTrue(unavailable.session.submitted.isEmpty())
    }

    @Test fun launchFailureStopsBeforeAnyUiRead() {
        val fixture = Fixture()
        fixture.launchResult = DynamicToolExecutionResult("{\"errorCode\":\"launch_denied\"}", false)
        val result = fixture.result(call(plan().put("launch", true).toString()))
        assertFalse(result.success)
        assertEquals("launch", JSONObject(result.contentText).getString("stoppedAt"))
        assertEquals(0, fixture.session.waits)
    }

    @Test fun failedOrReplayedChildStopsSequenceWithoutRepeatingAction() {
        listOf(false, true).forEach { replay ->
            val fixture = Fixture()
            fixture.session.views += snapshot(1)
            fixture.session.views += snapshot(2)
            fixture.session.outcome = { command -> receipt(command, failed = !replay, replayed = replay) }
            val result = fixture.result(call(plan(click(), click()).toString()))
            assertFalse(result.success)
            assertEquals(1, fixture.session.submitted.size)
            assertEquals(1, JSONObject(result.contentText).getJSONArray("steps").length())
            assertFalse(JSONObject(result.contentText).getBoolean("retrySequence"))
        }
    }

    @Test fun observedNotVerifiedReceiptKeepsExistingMeaningAndIsNeverRetried() {
        val fixture = Fixture()
        fixture.session.views += snapshot(1)
        fixture.session.outcome = { receipt(it, observedOnly = true) }
        val result = fixture.result(call())
        assertTrue(result.success)
        assertEquals("observed_not_verified", JSONObject(result.contentText).getJSONArray("steps")
            .getJSONObject(0).getJSONObject("result").getJSONObject("postcondition").getString("status"))
        assertEquals(1, fixture.session.executed.size)
    }

    @Test fun contextChangesAfterWaitOrFirstActionStopBeforeNextSubmission() {
        listOf("session", "epoch", "locked").forEach { kind ->
            val fixture = Fixture()
            fixture.session.views += snapshot(1)
            fixture.session.onAwait = { fixture.changeContext(kind) }
            assertCode("ui_sequence_cancelled_or_context_changed", fixture.result(call()))
            assertTrue(fixture.session.submitted.isEmpty())
            val after = Fixture()
            after.session.views += snapshot(1)
            after.session.views += snapshot(2)
            after.session.afterAction = { after.changeContext(kind) }
            assertCode("ui_sequence_cancelled_or_context_changed", after.result(call(plan(click(), click()).toString())))
            assertEquals(1, after.session.submitted.size)
        }
    }

    @Test fun overallDeadlineIncludesLaunchAndFreshViewWait() {
        val duringLaunch = Fixture()
        duringLaunch.onLaunch = { duringLaunch.now = 100 }
        assertCode("ui_sequence_deadline_exceeded", duringLaunch.result(call(plan().put("launch", true)
            .put("timeoutMs", 100).toString())))
        assertEquals(0, duringLaunch.session.waits)
        val duringWait = Fixture()
        duringWait.session.views += snapshot(1)
        duringWait.session.onAwait = { duringWait.now = 100 }
        assertCode("ui_sequence_deadline_exceeded", duringWait.result(call(plan().put("timeoutMs", 100).toString())))
        assertTrue(duringWait.session.submitted.isEmpty())
    }

    @Test fun duplicateCompletedCallReturnsReceiptAndChangedArgumentsConflict() {
        val fixture = Fixture()
        fixture.session.views += snapshot(1)
        val original = call()
        val first = fixture.result(original)
        assertReplayOf(first, fixture.result(original))
        assertCode("idempotency_key_conflict", fixture.result(original.copy(argumentsJson = plan()
            .put("launch", true).toString())))
        assertEquals(1, fixture.session.executed.size)
        assertEquals(1, fixture.session.waits)
    }

    @Test fun queueRejectionReturnsFailureWithoutExecutingChildOrNextStep() {
        val fixture = Fixture()
        fixture.session.views += snapshot(1)
        fixture.session.rejectSubmission = true
        assertCode("accessibility_queue_full", fixture.result(call(plan(click(), click()).toString())))
        assertTrue(fixture.session.executed.isEmpty())
        assertEquals(1, fixture.session.waits)
    }

    @Test fun duplicateChildCallbackCompletesOuterOnlyOnce() {
        val fixture = Fixture()
        fixture.session.views += snapshot(1)
        fixture.session.duplicateCallback = true
        var completions = 0
        fixture.executor.execute(call()) { completions++; assertTrue(it.success) }
        assertEquals(1, completions)
        assertEquals(1, fixture.session.executed.size)
    }

    @Test fun exceptionAfterCompletedStepPreservesProgressAndNeverLeaksExceptionText() {
        val fixture = Fixture()
        fixture.session.views += snapshot(1)
        fixture.session.onAwait = { if (fixture.session.waits == 2) error("private-exception-content") }
        val result = fixture.result(call(plan(click(), click()).toString()))
        assertCode("ui_sequence_execution_failed", result)
        val json = JSONObject(result.contentText)
        assertEquals(1, json.getJSONArray("steps").length())
        assertTrue(json.getBoolean("externalEffectMayHaveStarted"))
        assertFalse(json.getBoolean("retrySequence"))
        assertFalse(result.contentText.contains("private-exception-content"))
        assertEquals(1, fixture.session.executed.size)
    }

    @Test(timeout = 10_000) fun throwingSubmitAfterAdmissionKeepsFenceUntilRealCallback() {
        val fixture = Fixture(queued = true)
        fixture.session.views += snapshot(1)
        fixture.session.holdAfterAction = true
        fixture.session.throwAfterAdmission = true
        val fence = CrossTurnPhoneToolFence {}
        val wrapped = fence.wrap(fixture.executor)
        val delivered = CountDownLatch(1)
        val result = AtomicReference<DynamicToolExecutionResult?>()
        val completions = AtomicInteger()
        wrapped.execute(call()) { result.set(it); completions.incrementAndGet(); delivered.countDown() }
        val worker = start(fixture.jobs.single())
        try {
            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            assertCode("ui_sequence_execution_failed", checkNotNull(result.get()))
            val json = JSONObject(checkNotNull(result.get()).contentText)
            assertTrue(json.getBoolean("externalEffectMayHaveStarted"))
            assertEquals(1, json.getInt("unconfirmedStep"))
            assertFalse(checkNotNull(result.get()).contentText.contains("private-submit-error"))
            assertTrue(fence.hasActiveWork)
            assertCode("phone_tools_busy", immediate(wrapped, call(id = "blocked")))
        } finally {
            fixture.session.deliverHeld()
            worker.await()
        }
        assertFalse(fence.hasActiveWork)
        assertEquals(1, completions.get())
        assertEquals(1, fixture.session.executed.size)
    }

    @Test fun cancellationBeforeQueuedOuterWorkNeverLaunchesOrWaits() {
        val fixture = Fixture(queued = true)
        var completions = 0
        val handle = fixture.executor.executeCancellable(call(plan().put("launch", true).toString()),
            DynamicToolCancellation.NONE) { completions++ }
        assertEquals(DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT, handle.cancel())
        fixture.jobs.single().run()
        assertEquals(0, completions)
        assertEquals(0, fixture.session.waits)
        assertTrue(fixture.launches.isEmpty())
    }

    @Test(timeout = 10_000) fun cancellationWakesReadOnlyWaitAndFenceWaitsForWaiterExit() {
        val fixture = Fixture(queued = true)
        fixture.session.blockWait = true
        val fence = CrossTurnPhoneToolFence {}
        val wrapped = fence.wrap(fixture.executor)
        val completions = AtomicInteger()
        val handle = wrapped.executeCancellable(call(), DynamicToolCancellation.NONE) { completions.incrementAndGet() }
        val worker = start(fixture.jobs.single())
        try {
            assertTrue(fixture.session.waitEntered.await(2, TimeUnit.SECONDS))
            handle.cancel()
            assertEquals(1, fixture.session.wakes)
            assertTrue(fence.hasActiveWork)
            assertCode("phone_tools_busy", immediate(wrapped, call(id = "blocked")))
            assertTrue(fixture.session.submitted.isEmpty())
        } finally {
            fixture.session.waitRelease.countDown()
            worker.await()
        }
        assertFalse(fence.hasActiveWork)
        assertEquals(0, completions.get())
    }

    @Test(timeout = 10_000) fun cancellationOfQueuedChildKeepsFenceUntilActualCallback() {
        cancellationFence(holdAfterAction = false)
    }

    @Test(timeout = 10_000) fun cancellationAfterActionKeepsFenceUntilPhysicalCompletionWithoutUndoClaim() {
        cancellationFence(holdAfterAction = true)
    }

    @Test(timeout = 10_000) fun queueTimeSessionEpochAndDeadlineGuardsPreventLateMutation() {
        listOf("session", "epoch", "deadline").forEach { change ->
            val fixture = Fixture(queued = true)
            fixture.session.views += snapshot(1)
            fixture.session.holdBeforeAction = true
            val result = AtomicReference<DynamicToolExecutionResult?>()
            fixture.executor.execute(call()) { result.set(it) }
            val worker = start(fixture.jobs.single())
            try {
                assertTrue(fixture.session.submitEntered.await(2, TimeUnit.SECONDS))
                if (change == "deadline") fixture.now = 5_000 else fixture.changeContext(change)
            } finally {
                fixture.session.deliverHeld()
                worker.await()
            }
            assertFalse(checkNotNull(result.get()).success)
            assertTrue(fixture.session.executed.isEmpty())
        }
    }

    @Test(timeout = 10_000) fun inFlightDuplicateDoesNotQueueAnotherChild() {
        val fixture = Fixture(queued = true)
        fixture.session.views += snapshot(1)
        fixture.session.holdBeforeAction = true
        fixture.executor.execute(call()) {}
        val worker = start(fixture.jobs.single())
        try {
            assertTrue(fixture.session.submitEntered.await(2, TimeUnit.SECONDS))
            var duplicate: DynamicToolExecutionResult? = null
            fixture.executor.execute(call()) { duplicate = it }
            fixture.jobs[1].run()
            assertCode("ui_sequence_busy", checkNotNull(duplicate))
            assertEquals(1, fixture.session.submitted.size)
        } finally {
            fixture.session.deliverHeld()
            worker.await()
        }
        assertEquals(1, fixture.session.executed.size)
    }

    private fun cancellationFence(holdAfterAction: Boolean) {
        val fixture = Fixture(queued = true)
        fixture.session.views += snapshot(1)
        fixture.session.holdBeforeAction = !holdAfterAction
        fixture.session.holdAfterAction = holdAfterAction
        val fence = CrossTurnPhoneToolFence {}
        val wrapped = fence.wrap(fixture.executor)
        val completions = AtomicInteger()
        val handle = wrapped.executeCancellable(call(), DynamicToolCancellation.NONE) { completions.incrementAndGet() }
        val worker = start(fixture.jobs.single())
        try {
            assertTrue(fixture.session.submitEntered.await(2, TimeUnit.SECONDS))
            handle.cancel()
            assertTrue(fence.hasActiveWork)
            assertCode("phone_tools_busy", immediate(wrapped, call(id = "blocked")))
        } finally {
            fixture.session.deliverHeld()
            worker.await()
        }
        assertFalse(fence.hasActiveWork)
        assertEquals(0, completions.get())
        assertEquals(if (holdAfterAction) 1 else 0, fixture.session.executed.size)
    }

    private class Fixture(queued: Boolean = false) {
        val session = Session()
        var activeSession: HansAccessibilitySession? = session
        var available = true
        var epoch = 0L
        var now = 0L
        val jobs = mutableListOf<Runnable>()
        val launches = mutableListOf<DynamicToolCallParams>()
        var launchResult = DynamicToolExecutionResult("{\"status\":\"succeeded\"}", true)
        var onLaunch: () -> Unit = {}
        val executor = GenericUiStepsExecutor(
            backgroundExecutor = if (queued) Executor { jobs += it } else Executor(Runnable::run),
            launch = { call, gate ->
                check(gate.markExternalEffectStarted())
                launches += call
                onLaunch()
                launchResult
            },
            sessions = { activeSession },
            availability = UiInteractionAvailabilityProbe {
                if (available) UiInteractionAvailability.AVAILABLE else UiInteractionAvailability.DEVICE_LOCKED
            },
            evidenceEpoch = { epoch }, nowMillis = { now },
        )
        fun result(call: DynamicToolCallParams) = immediate(executor, call)
        fun changeContext(kind: String) {
            when (kind) {
                "session" -> activeSession = Session()
                "epoch" -> epoch++
                "locked" -> available = false
                else -> error("Unknown test context change")
            }
        }
    }

    private class Session : HansAccessibilitySession {
        override val sessionId = SESSION
        val views = ArrayDeque<SemanticUiSnapshot>()
        var current: SemanticUiSnapshot? = null
        val submitted = mutableListOf<AccessibilityCommand>()
        val executed = mutableListOf<AccessibilityCommand>()
        var waits = 0
        val waitTimeouts = mutableListOf<Long>()
        var predicateChecks = 0
        var currentReads = 0
        var refreshes = 0
        var wakes = 0
        var retain = true
        var blockWait = false
        var holdBeforeAction = false
        var holdAfterAction = false
        var rejectSubmission = false
        var duplicateCallback = false
        var throwAfterAdmission = false
        var onAwait: () -> Unit = {}
        var onObservation: (SemanticUiSnapshot) -> Unit = {}
        var awaitDelegate: ((Long, () -> Boolean, (SemanticUiSnapshot) -> Boolean) -> SemanticUiSnapshot?)? = null
        var wakeDelegate: () -> Unit = {}
        var afterAction: () -> Unit = {}
        var outcome: (AccessibilityCommand) -> AccessibilityExecutionResult = { receipt(it) }
        val waitEntered = CountDownLatch(1)
        val waitRelease = CountDownLatch(1)
        val submitEntered = CountDownLatch(1)
        private var held: (() -> Unit)? = null
        override fun currentSnapshot(): SemanticUiSnapshot? { currentReads++; return current }
        override fun refreshSnapshot(): SemanticUiSnapshot? { refreshes++; return current }
        override fun retainSnapshotForCommands(correlation: UiSnapshotCorrelation) = retain && current?.correlation == correlation
        override fun wakeSnapshotWaiters() { wakes++; wakeDelegate() }
        override fun awaitSnapshot(timeoutMillis: Long, cancelled: () -> Boolean,
            predicate: (SemanticUiSnapshot) -> Boolean): SemanticUiSnapshot? {
            waits++
            assertTrue(timeoutMillis in 1..5_000)
            waitTimeouts += timeoutMillis
            onAwait()
            awaitDelegate?.let { return it(timeoutMillis, cancelled, predicate) }
            if (blockWait) { waitEntered.countDown(); check(waitRelease.await(3, TimeUnit.SECONDS)) }
            if (cancelled()) return null
            while (views.isNotEmpty()) {
                val view = views.removeFirst().also { current = it }
                predicateChecks++
                val accepted = predicate(view)
                onObservation(view)
                if (cancelled()) return null
                if (accepted) return view
            }
            return null
        }
        override fun submit(command: AccessibilityCommand, approval: AccessibilityUserApproval?,
            callback: AccessibilityCommandCallback): Boolean = error("Must use the queue-time guarded boundary")
        override fun submitGuarded(command: AccessibilityCommand, cancelled: () -> Boolean,
            callback: AccessibilityCommandCallback): Boolean {
            submitted += command
            if (rejectSubmission) return false
            fun execute(): AccessibilityExecutionResult = if (cancelled()) receipt(command, failed = true) else {
                executed += command
                val result = outcome(command)
                afterAction()
                result
            }
            fun deliver(result: AccessibilityExecutionResult) {
                callback.onResult(result)
                if (duplicateCallback) callback.onResult(result)
            }
            when {
                holdBeforeAction -> held = { deliver(execute()) }
                holdAfterAction -> { val result = execute(); held = { deliver(result) } }
                else -> deliver(execute())
            }
            submitEntered.countDown()
            if (throwAfterAdmission) error("private-submit-error")
            return true
        }
        fun deliverHeld() { held?.also { held = null }?.invoke() }
    }

    private class Running(private val done: CountDownLatch, private val failure: AtomicReference<Throwable?>) {
        fun await() { assertTrue("Synthetic worker did not finish", done.await(3, TimeUnit.SECONDS)); failure.get()?.let { throw it } }
    }

    private companion object {
        const val PACKAGE = "synthetic.steps"
        val SESSION = AccessibilitySessionId("synthetic-steps-session")
        fun start(job: Runnable): Running {
            val done = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            Thread {
                try { job.run() } catch (error: Throwable) { failure.set(error) } finally { done.countDown() }
            }.apply { isDaemon = true; start() }
            return Running(done, failure)
        }
        fun call(json: String = plan().toString(), id: String = "call") =
            DynamicToolCallParams("synthetic-thread", "synthetic-turn", id, "android_ui", "run_steps", json)
        fun click() = JSONObject().put("action", "click").put("target", JSONObject().put("text", "Next"))
        fun edit() = JSONObject().put("action", "set_text")
            .put("target", JSONObject().put("contentDescription", "Message")).put("value", "hello")
        fun JSONObject.removeValue(): JSONObject = apply { remove("value") }
        fun plan(vararg steps: JSONObject = arrayOf(click())) = JSONObject().put("packageName", PACKAGE)
            .put("steps", JSONArray(steps.toList()))
        fun immediate(executor: DynamicToolExecutor, call: DynamicToolCallParams): DynamicToolExecutionResult {
            var result: DynamicToolExecutionResult? = null
            executor.execute(call) { result = it }
            return checkNotNull(result) { "Expected a synchronous synthetic result" }
        }
        fun assertCode(expected: String, result: DynamicToolExecutionResult) {
            assertFalse(result.success)
            assertEquals(expected, JSONObject(result.contentText).getString("errorCode"))
        }
        fun assertReplayOf(original: DynamicToolExecutionResult, replay: DynamicToolExecutionResult) {
            assertEquals(original.success, replay.success)
            val expected = JSONObject(original.contentText).put("replayed", true)
            assertEquals(expected.toString(), replay.contentText)
            assertTrue(JSONObject(replay.contentText).getBoolean("replayed"))
        }
        fun button() = RawSemanticUiNode(packageName = PACKAGE, className = "android.widget.Button",
            text = "Next", role = SemanticUiRole.BUTTON, bounds = UiBounds(1, 1, 60, 40),
            clickable = true, actions = setOf(SemanticUiAction.CLICK))
        fun editNode() = RawSemanticUiNode(packageName = PACKAGE, className = "android.widget.EditText",
            contentDescription = "Message", role = SemanticUiRole.EDIT_TEXT, bounds = UiBounds(1, 50, 300, 100),
            editable = true, actions = setOf(SemanticUiAction.SET_TEXT))
        fun snapshot(id: Long, nodes: List<RawSemanticUiNode> = listOf(button()), incomplete: Boolean = false,
            session: AccessibilitySessionId = SESSION) = BoundedSemanticUiSnapshotFactory().build(RawSemanticUiSnapshot(
            UiSnapshotCorrelation(session, AccessibilityWindowId(1), AccessibilitySnapshotId(id)),
            UiBounds(0, 0, 400, 800), id, nodes,
            if (incomplete) setOf(SnapshotTruncationReason.PLATFORM_NODE_UNAVAILABLE) else emptySet(),
        ))
        fun receipt(command: AccessibilityCommand, failed: Boolean = false, replayed: Boolean = false,
            observedOnly: Boolean = false): AccessibilityExecutionResult {
            val correlation = when (command) {
                is AccessibilityCommand.Click -> command.handle.correlation
                is AccessibilityCommand.SetText -> command.handle.correlation
                else -> error("Unexpected synthetic command")
            }
            return AccessibilityExecutionResult(command.idempotencyKey,
                if (failed) AccessibilityExecutionStatus.FAILED else AccessibilityExecutionStatus.SUCCEEDED,
                replayed, null, AccessibilityPostcondition(AccessibilityPostconditionKind.NODE_ACTION,
                    when { failed -> AccessibilityPostconditionStatus.FAILED
                        observedOnly -> AccessibilityPostconditionStatus.OBSERVED_NOT_VERIFIED
                        else -> AccessibilityPostconditionStatus.VERIFIED },
                    if (failed) "synthetic_step_failed" else "synthetic_step_observed", correlation, correlation, UiDataTrust.LOCAL_SYSTEM),
                errorCode = if (failed) "synthetic_step_failed" else null)
        }
    }
}
