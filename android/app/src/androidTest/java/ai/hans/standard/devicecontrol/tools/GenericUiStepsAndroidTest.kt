package ai.hans.standard.devicecontrol.tools

import ai.hans.standard.codex.*
import ai.hans.standard.phone.accessibility.*
import ai.hans.standard.phone.accessibility.android.AccessibilityCommandCallback
import ai.hans.standard.phone.accessibility.android.EventDrivenSemanticSnapshotWaiter
import ai.hans.standard.phone.accessibility.android.HansAccessibilitySession
import ai.hans.standard.remotecontrol.CrossTurnPhoneToolFence
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.runner.RunWith

/** Android JSON and cancellation contracts only; never launches or operates a real app. */
@RunWith(AndroidJUnit4::class)
class GenericUiStepsAndroidTest {
    @Test fun androidJsonRejectsMalformedAndNullArguments() {
        val invalid = listOf("null", "[]", "malformed", plan().put("launch", JSONObject.NULL).toString(),
            plan().put("launch", "true").toString(), plan().put("timeoutMs", 100.25).toString(),
            plan().put("timeoutMs", 5_001).toString(), plan().put("unexpected", true).toString(),
            plan().put("steps", JSONArray()).toString(),
            plan(click().put("target", JSONObject().put("text", JSONObject.NULL))).toString(),
            plan(click().put("target", JSONObject().put("text", 123))).toString(),
            plan(click().put("target", JSONObject().put("text", "x".repeat(513)))).toString(),
            plan(edit().put("value", JSONObject.NULL)).toString())
        invalid.forEachIndexed { index, json ->
            val fixture = Fixture()
            assertCode("invalid_arguments", immediate(fixture.executor, call(json, "invalid-$index")))
            assertEquals(0, fixture.launches)
            assertEquals(0, fixture.waits)
            assertEquals(0, fixture.actions)
        }
    }

    @Test fun launchAndTwoStepsReturnCompactReceiptsAndReplayWithoutActions() {
        val fixture = Fixture()
        fixture.views += snapshot(1)
        fixture.views += snapshot(2, edit = true)
        val request = call(plan(click(), edit()).put("launch", true).toString())
        val result = immediate(fixture.executor, request)
        assertTrue(result.success)
        val json = JSONObject(result.contentText)
        assertEquals(2, json.getJSONArray("steps").length())
        assertFalse(json.getBoolean("intermediateViewsReturned"))
        assertFalse(result.contentText.contains("\"nodes\""))
        assertFalse(result.contentText.contains("nextObservation"))
        assertFalse(result.contentText.contains("private synthetic text"))
        assertEquals("observed_not_verified", json.getJSONArray("steps").getJSONObject(0)
            .getJSONObject("result").getJSONObject("postcondition").getString("status"))
        assertEquals(1, fixture.launches)
        assertEquals(2, fixture.waits)
        assertEquals(2, fixture.actions)
        assertEquals(0, fixture.extraReads)
        assertTrue(JSONObject(immediate(fixture.executor, request).contentText).getBoolean("replayed"))
        assertEquals(2, fixture.actions)
        assertEquals(1, fixture.launches)
    }

    @Test fun completeAmbiguousViewStopsBeforeLaterUniqueView() {
        val fixture = Fixture()
        fixture.views += snapshot(1, ambiguous = true)
        fixture.views += snapshot(2)
        assertCode("ui_target_ambiguous", immediate(fixture.executor, call()))
        assertEquals(1, fixture.predicateChecks)
        assertEquals(0, fixture.actions)
    }

    @Test fun temporaryIncompleteViewWaitsForCompleteUniqueView() {
        val fixture = Fixture()
        fixture.views += snapshot(1, incomplete = true)
        fixture.views += snapshot(2)
        assertTrue(immediate(fixture.executor, call()).success)
        assertEquals(1, fixture.waits)
        assertEquals(2, fixture.predicateChecks)
        assertEquals(listOf(2L), fixture.actionSnapshots)
        assertEquals(0, fixture.extraReads)
    }

    @Test fun incompleteThenCompleteAmbiguousViewNeverActs() {
        val fixture = Fixture()
        fixture.views += snapshot(1, incomplete = true)
        fixture.views += snapshot(2, ambiguous = true)
        fixture.views += snapshot(3)
        assertCode("ui_target_ambiguous", immediate(fixture.executor, call()))
        assertEquals(2, fixture.predicateChecks)
        assertEquals(0, fixture.actions)
    }

    @Test fun persistentIncompleteViewKeepsSpecificFailureAtDeadlineWithoutUiContent() {
        val fixture = Fixture()
        fixture.views += snapshot(1, incomplete = true)
        fixture.views += snapshot(2, incomplete = true)
        fixture.onObservation = { if (it.correlation.snapshotId.value == 2L) fixture.now = 100 }
        val result = immediate(fixture.executor, call(plan().put("timeoutMs", 100).toString()))
        assertCode("ui_target_snapshot_incomplete", result)
        assertEquals(listOf(100L), fixture.waitTimeouts)
        assertEquals(2, fixture.predicateChecks)
        assertEquals(0, fixture.actions)
        assertFalse(result.contentText.contains("Next"))
        assertFalse(result.contentText.contains(PACKAGE))
        assertFalse(result.contentText.contains("nodes"))
        assertFalse(JSONObject(result.contentText).getBoolean("retrySequence"))
    }

    @Test fun completedStepIsNotRepeatedWhileNextViewBecomesCompleteWithinRemainingDeadline() {
        val fixture = Fixture()
        fixture.views += snapshot(1)
        fixture.views += snapshot(2, edit = true, incomplete = true)
        fixture.views += snapshot(3, edit = true)
        fixture.afterAction = { fixture.now += 30 }
        val result = immediate(fixture.executor, call(plan(click(), edit()).put("timeoutMs", 100).toString()))
        assertTrue(result.success)
        assertEquals(listOf(100L, 70L), fixture.waitTimeouts)
        assertEquals(3, fixture.predicateChecks)
        assertEquals(listOf(1L, 3L), fixture.actionSnapshots)
        assertEquals(2, JSONObject(result.contentText).getJSONArray("steps").length())
        assertFalse(result.contentText.contains("private synthetic text"))
        assertEquals(0, fixture.extraReads)
    }

    @Test fun lateCompleteViewCannotRenewDeadlineAfterIncompleteObservation() {
        val fixture = Fixture()
        fixture.views += snapshot(1, incomplete = true)
        fixture.views += snapshot(2)
        fixture.onObservation = { fixture.now = if (it.isComplete) 100 else 90 }
        assertCode("ui_sequence_deadline_exceeded", immediate(fixture.executor,
            call(plan().put("timeoutMs", 100).toString())))
        assertEquals(listOf(100L), fixture.waitTimeouts)
        assertEquals(0, fixture.actions)
    }

    @Test fun contextChangeDuringIncompleteObservationStopsBeforeLaterCompleteView() {
        val fixture = Fixture()
        fixture.views += snapshot(1, incomplete = true)
        fixture.views += snapshot(2)
        fixture.onObservation = { fixture.epoch++ }
        assertCode("ui_sequence_cancelled_or_context_changed", immediate(fixture.executor, call()))
        assertEquals(1, fixture.predicateChecks)
        assertEquals(0, fixture.actions)
    }

    @Test fun laterCompleteMissingTargetClearsStaleIncompleteFailure() {
        val fixture = Fixture()
        fixture.views += snapshot(1, incomplete = true)
        fixture.views += snapshot(2, edit = true)
        assertCode("ui_target_wait_expired", immediate(fixture.executor, call()))
        assertEquals(2, fixture.predicateChecks)
        assertEquals(0, fixture.actions)
    }

    @Test fun unexpectedIncompleteReturnedViewIsRecheckedBeforeAction() {
        val fixture = Fixture()
        fixture.awaitDelegate = { _, _, _ -> snapshot(1, incomplete = true).also { fixture.current = it } }
        assertCode("ui_target_snapshot_incomplete", immediate(fixture.executor, call()))
        assertEquals(0, fixture.actions)
    }

    @Test(timeout = 10_000) fun realEventDrivenWaiterUsesOnlyPublishedCompleteViewWithoutRecapture() {
        val fixture = Fixture(queued = true)
        val published = AtomicReference<SemanticUiSnapshot?>()
        val incompleteObserved = CountDownLatch(1)
        val captures = AtomicInteger()
        val waiter = EventDrivenSemanticSnapshotWaiter(
            freshSnapshot = { captures.incrementAndGet(); snapshot(1, incomplete = true).also(published::set) },
            currentSnapshot = published::get,
            elapsedRealtimeMillis = { System.nanoTime() / 1_000_000 },
        )
        fixture.awaitDelegate = { timeout, cancelled, predicate ->
            waiter.await(timeout, cancelled, { true }) { view ->
                fixture.current = view
                val accepted = predicate(view)
                if (!view.isComplete) {
                    assertFalse("Incomplete frames must keep waiting", accepted)
                    incompleteObserved.countDown()
                }
                accepted
            }
        }
        fixture.wakeDelegate = waiter::wake
        val result = AtomicReference<DynamicToolExecutionResult?>()
        val handle = fixture.executor.executeCancellable(call(), DynamicToolCancellation.NONE, result::set)
        val worker = start(fixture.jobs.single())
        try {
            assertTrue(incompleteObserved.await(2, TimeUnit.SECONDS))
            assertNull(result.get())
            assertEquals(0, fixture.actions)
            assertEquals(1, captures.get())
            published.set(snapshot(2))
            waiter.wake()
            worker.await()
            assertTrue(checkNotNull(result.get()).success)
            assertEquals(1, captures.get())
            assertEquals(listOf(2L), fixture.actionSnapshots)
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
        fixture.awaitDelegate = { timeout, cancelled, predicate ->
            waiter.await(timeout, cancelled, { true }) { view ->
                fixture.current = view
                observations.incrementAndGet()
                predicate(view)
            }
        }
        assertCode("ui_target_snapshot_incomplete", immediate(fixture.executor,
            call(plan().put("timeoutMs", 100).toString())))
        assertEquals(1, captures.get())
        assertEquals(1, observations.get())
        assertEquals(listOf(100L), fixture.waitTimeouts)
        assertEquals(0, fixture.actions)
    }

    @Test fun changedContextAndDeadlineStopBeforeAction() {
        val changed = Fixture()
        changed.views += snapshot(1)
        changed.onWait = { changed.epoch++ }
        assertCode("ui_sequence_cancelled_or_context_changed", immediate(changed.executor, call()))
        assertEquals(0, changed.actions)
        val expired = Fixture()
        expired.views += snapshot(1)
        expired.onWait = { expired.now = 100 }
        assertCode("ui_sequence_deadline_exceeded", immediate(expired.executor,
            call(plan().put("timeoutMs", 100).toString())))
        assertEquals(0, expired.actions)
    }

    @Test(timeout = 10_000) fun cancellationDuringViewWaitKeepsFenceUntilWaiterReturns() {
        val fixture = Fixture(queued = true)
        fixture.blockWait = true
        val fence = CrossTurnPhoneToolFence {}
        val wrapped = fence.wrap(fixture.executor)
        val completions = AtomicInteger()
        val handle = wrapped.executeCancellable(call(), DynamicToolCancellation.NONE) {
            completions.incrementAndGet()
        }
        val worker = start(fixture.jobs.single())
        try {
            assertTrue(fixture.waitEntered.await(2, TimeUnit.SECONDS))
            handle.cancel()
            assertEquals(1, fixture.wakes)
            assertTrue(fence.hasActiveWork)
            assertCode("phone_tools_busy", immediate(wrapped, call(id = "blocked")))
        } finally {
            fixture.waitRelease.countDown()
            worker.await()
        }
        assertEquals(0, fixture.actions)
        assertEquals(0, completions.get())
        assertFalse(fence.hasActiveWork)
    }

    @Test(timeout = 10_000) fun cancellationAfterAdmissionKeepsFenceUntilActualCallback() {
        val fixture = Fixture(queued = true)
        fixture.views += snapshot(1)
        fixture.holdCallback = true
        val fence = CrossTurnPhoneToolFence {}
        val wrapped = fence.wrap(fixture.executor)
        val completions = AtomicInteger()
        val handle = wrapped.executeCancellable(call(), DynamicToolCancellation.NONE) {
            completions.incrementAndGet()
        }
        val worker = start(fixture.jobs.single())
        try {
            assertTrue(fixture.actionEntered.await(2, TimeUnit.SECONDS))
            assertEquals(1, fixture.actions)
            handle.cancel()
            assertTrue(fence.hasActiveWork)
            assertCode("phone_tools_busy", immediate(wrapped, call(id = "blocked")))
        } finally {
            fixture.finishAction?.invoke()
            worker.await()
        }
        assertEquals(1, fixture.actions)
        assertEquals(0, completions.get())
        assertFalse(fence.hasActiveWork)
    }

    private class Fixture(queued: Boolean = false) : HansAccessibilitySession {
        override val sessionId = SESSION
        val jobs = mutableListOf<Runnable>()
        val views = ArrayDeque<SemanticUiSnapshot>()
        var current: SemanticUiSnapshot? = null
        var launches = 0
        var waits = 0
        val waitTimeouts = mutableListOf<Long>()
        var predicateChecks = 0
        var actions = 0
        val actionSnapshots = mutableListOf<Long>()
        var extraReads = 0
        var wakes = 0
        var epoch = 0L
        var now = 0L
        var blockWait = false
        var holdCallback = false
        var onWait: () -> Unit = {}
        var onObservation: (SemanticUiSnapshot) -> Unit = {}
        var afterAction: () -> Unit = {}
        var awaitDelegate: ((Long, () -> Boolean, (SemanticUiSnapshot) -> Boolean) -> SemanticUiSnapshot?)? = null
        var wakeDelegate: () -> Unit = {}
        var finishAction: (() -> Unit)? = null
        val waitEntered = CountDownLatch(1)
        val waitRelease = CountDownLatch(1)
        val actionEntered = CountDownLatch(1)
        val executor = GenericUiStepsExecutor(
            backgroundExecutor = if (queued) Executor { jobs += it } else Executor(Runnable::run),
            launch = { request, gate ->
                check(gate.markExternalEffectStarted())
                assertEquals("launch_app", request.tool)
                assertEquals(PACKAGE, JSONObject(request.argumentsJson).getString("packageName"))
                launches++
                DynamicToolExecutionResult("{\"status\":\"succeeded\"}", true)
            }, sessions = { this }, availability = UiInteractionAvailabilityProbe { UiInteractionAvailability.AVAILABLE },
            evidenceEpoch = { epoch }, nowMillis = { now },
        )
        override fun currentSnapshot(): SemanticUiSnapshot? { extraReads++; return current }
        override fun refreshSnapshot(): SemanticUiSnapshot? { extraReads++; return current }
        override fun retainSnapshotForCommands(correlation: UiSnapshotCorrelation) = current?.correlation == correlation
        override fun wakeSnapshotWaiters() { wakes++; wakeDelegate() }
        override fun awaitSnapshot(timeoutMillis: Long, cancelled: () -> Boolean,
            predicate: (SemanticUiSnapshot) -> Boolean): SemanticUiSnapshot? {
            waits++
            assertTrue(timeoutMillis in 1..5_000)
            waitTimeouts += timeoutMillis
            onWait()
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
            callback: AccessibilityCommandCallback): Boolean = error("Expected guarded submission")
        override fun submitGuarded(command: AccessibilityCommand, cancelled: () -> Boolean,
            callback: AccessibilityCommandCallback): Boolean {
            check(!cancelled())
            actions++
            val handle = when (command) {
                is AccessibilityCommand.Click -> command.handle
                is AccessibilityCommand.SetText -> {
                    assertEquals("private synthetic text", command.value)
                    assertEquals(UiPostconditionExpectation.TEXT_EQUALS_REQUEST, command.postcondition)
                    command.handle
                }
                else -> error("Unexpected synthetic command")
            }
            val result = AccessibilityExecutionResult(command.idempotencyKey, AccessibilityExecutionStatus.SUCCEEDED,
                false, null, AccessibilityPostcondition(AccessibilityPostconditionKind.NODE_ACTION,
                    AccessibilityPostconditionStatus.OBSERVED_NOT_VERIFIED, "synthetic_action_accepted",
                    handle.correlation, handle.correlation, UiDataTrust.LOCAL_SYSTEM))
            actionSnapshots += handle.correlation.snapshotId.value
            afterAction()
            if (holdCallback) finishAction = { callback.onResult(result) } else callback.onResult(result)
            actionEntered.countDown()
            return true
        }
    }

    private class Running(private val done: CountDownLatch, private val error: AtomicReference<Throwable?>) {
        fun await() { assertTrue("Synthetic worker did not finish", done.await(3, TimeUnit.SECONDS)); error.get()?.let { throw it } }
    }

    private companion object {
        const val PACKAGE = "synthetic.steps"
        val SESSION = AccessibilitySessionId("synthetic-android-steps")
        fun call(json: String = plan().toString(), id: String = "call") =
            DynamicToolCallParams("synthetic-thread", "synthetic-turn", id, "android_ui", "run_steps", json)
        fun click() = JSONObject().put("action", "click").put("target", JSONObject().put("text", "Next"))
        fun edit() = JSONObject().put("action", "set_text").put("target", JSONObject().put("role", "edit_text"))
            .put("value", "private synthetic text")
        fun plan(vararg steps: JSONObject = arrayOf(click())) = JSONObject().put("packageName", PACKAGE)
            .put("steps", JSONArray(steps.toList()))
        fun immediate(executor: DynamicToolExecutor, call: DynamicToolCallParams): DynamicToolExecutionResult {
            var result: DynamicToolExecutionResult? = null
            executor.execute(call) { result = it }
            return checkNotNull(result)
        }
        fun assertCode(expected: String, result: DynamicToolExecutionResult) {
            assertFalse(result.success)
            assertEquals(expected, JSONObject(result.contentText).getString("errorCode"))
        }
        fun start(job: Runnable): Running {
            val done = CountDownLatch(1)
            val error = AtomicReference<Throwable?>()
            Thread {
                try { job.run() } catch (failure: Throwable) { error.set(failure) } finally { done.countDown() }
            }.apply { isDaemon = true; start() }
            return Running(done, error)
        }
        fun snapshot(id: Long, edit: Boolean = false, ambiguous: Boolean = false, incomplete: Boolean = false): SemanticUiSnapshot {
            val node = RawSemanticUiNode(packageName = PACKAGE,
                className = if (edit) "android.widget.EditText" else "android.widget.Button",
                text = if (edit) null else "Next", role = if (edit) SemanticUiRole.EDIT_TEXT else SemanticUiRole.BUTTON,
                bounds = UiBounds(1, 1, 300, 100), clickable = !edit, editable = edit,
                actions = setOf(if (edit) SemanticUiAction.SET_TEXT else SemanticUiAction.CLICK))
            return BoundedSemanticUiSnapshotFactory().build(RawSemanticUiSnapshot(
                UiSnapshotCorrelation(SESSION, AccessibilityWindowId(1), AccessibilitySnapshotId(id)),
                UiBounds(0, 0, 400, 800), id, if (ambiguous) listOf(node, node.copy()) else listOf(node),
                if (incomplete) setOf(SnapshotTruncationReason.PLATFORM_NODE_UNAVAILABLE) else emptySet(),
            ))
        }
    }
}
