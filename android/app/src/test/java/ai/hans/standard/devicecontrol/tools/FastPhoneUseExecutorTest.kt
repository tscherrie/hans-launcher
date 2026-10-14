package ai.hans.standard.devicecontrol.tools

import ai.hans.standard.codex.*
import ai.hans.standard.fastphone.*
import ai.hans.standard.fastphone.routines.RoutineObservation
import ai.hans.standard.phone.accessibility.*
import ai.hans.standard.phone.accessibility.android.AccessibilityCommandCallback
import ai.hans.standard.phone.accessibility.android.HansAccessibilitySession
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

/** Real executor semantics against synthetic UI, never a real phone or paid request. */
class FastPhoneUseExecutorTest {
    @Test fun reconnectingSessionMakesNoPaidRequestOrLaunchAndKeepsActionRetryDisabled() {
        val fixture = Fixture().apply {
            activeSession = null
            missingSession = DeviceControlProjection.serviceReconnecting()
        }
        val result = fixture.result(call(plan().put("launch", true).toString()))
        assertCode("accessibility_service_reconnecting", result)
        val json = JSONObject(result.contentText)
        assertTrue(json.getBoolean("retryable"))
        assertTrue(json.getBoolean("specialAccessGranted"))
        assertFalse(json.getBoolean("retryActions"))
        assertFalse(json.getBoolean("externalEffectMayHaveStarted"))
        assertEquals(0, json.getInt("decisions"))
        assertEquals(0, json.getJSONArray("steps").length())
        assertTrue(fixture.questions.isEmpty())
        assertTrue(fixture.launches.isEmpty())
        assertTrue(fixture.session.actions.isEmpty())
    }

    @Test fun disabledMissingKeyAndUnavailableUiMakeNoPaidRequestsOrActions() {
        val disabled = Fixture().apply { access.enabled = false }
        assertCode("fast_phone_disabled", disabled.result())
        val missing = Fixture().apply { access.key = null }
        assertCode("fast_phone_key_missing", missing.result())
        assertEquals(listOf("fast_phone_key_missing"), missing.access.failures)
        val locked = Fixture().apply { available = false }
        assertCode("fast_phone_cancelled_or_context_changed", locked.result())
        listOf(disabled, missing, locked).forEach { assertTrue(it.questions.isEmpty()); assertTrue(it.session.actions.isEmpty()) }
    }

    @Test fun malformedAndExpandedArgumentsNeverReachNetworkOrUi() {
        val invalid = listOf("not json", "[]", "null", "{}",
            plan().put("extra", true).toString(), plan().put("packageName", "invalid").toString(),
            plan().put("goal", JSONObject.NULL).toString(), plan().put("goal", "x".repeat(2_049)).toString(),
            plan().put("allowedActions", JSONArray()).toString(),
            plan().put("allowedActions", JSONArray(listOf("click", "click"))).toString(),
            plan().put("allowedActions", JSONArray(listOf("shell"))).toString(),
            plan().put("allowedActions", JSONArray(listOf("set_text"))).toString(),
            plan().put("textToEnter", "unpermitted").toString(),
            plan().put("launch", "true").toString(), plan().put("timeoutMs", 999).toString(),
            plan().put("timeoutMs", 45_001).toString(), plan().put("timeoutMs", 1_000.5).toString(),
            plan().put("maxSteps", 0).toString(), plan().put("maxSteps", 9).toString(),
            plan().put("confirmationRisk", "credential_ui").toString())
        invalid.forEachIndexed { index, args ->
            val fixture = Fixture()
            assertCode("invalid_arguments", fixture.result(call(args, "bad-$index")))
            assertTrue(fixture.questions.isEmpty()); assertEquals(0, fixture.session.waits)
        }
        assertCode("invalid_arguments", Fixture().result(call().copy(namespace = "android")))
        assertCode("invalid_arguments", Fixture().result(call().copy(tool = "click_ui")))
    }

    @Test fun clickThenExactTextThenDoneUsesOneOuterCallAndFreshLocalTargets() {
        val fixture = Fixture()
        fixture.session.views += snapshot(1, listOf(button("Open")))
        fixture.session.views += snapshot(2, listOf(edit()))
        fixture.session.views += snapshot(3, listOf(label("Saved")))
        fixture.answers += selected("a0"); fixture.answers += selected("a0"); fixture.answers += selected("done")
        val result = fixture.result(call(plan().put("allowedActions", JSONArray(listOf("click", "set_text")))
            .put("textToEnter", "exact private text").toString()))
        assertTrue(result.success)
        assertEquals(3, fixture.questions.size)
        assertEquals(1, JSONObject(fixture.questions[1].evidence).getJSONArray("actionsAlreadyAttempted").length())
        val click = fixture.session.actions[0] as AccessibilityCommand.Click
        val text = fixture.session.actions[1] as AccessibilityCommand.SetText
        assertEquals(1L, click.handle.correlation.snapshotId.value)
        assertEquals(2L, text.handle.correlation.snapshotId.value)
        assertEquals("exact private text", text.value)
        assertEquals(UiPostconditionExpectation.TEXT_EQUALS_REQUEST, text.postcondition)
        assertNotEquals(click.idempotencyKey, text.idempotencyKey)
        val json = JSONObject(result.contentText)
        assertEquals(2, json.getJSONArray("steps").length())
        assertFalse(json.getBoolean("intermediateViewsReturned"))
        assertFalse(json.getBoolean("externalTaskIndependentlyVerified"))
        assertFalse(result.contentText.contains("exact private text"))
        assertFalse(result.contentText.contains("\"nodes\""))
        assertFalse(result.contentText.contains("actionsAlreadyAttempted"))
        assertTrue(fixture.keys.all { chars -> chars.all { it == '\u0000' } })
    }

    @Test fun directSetTextCandidateAvoidsAnExtraFocusClick() {
        val fixture = Fixture()
        fixture.session.views += snapshot(1, listOf(edit().copy(clickable = true,
            actions = setOf(SemanticUiAction.CLICK, SemanticUiAction.SET_TEXT))))
        fixture.answers += selected("escalate")
        fixture.result(call(plan().put("allowedActions", JSONArray(listOf("click", "set_text")))
            .put("textToEnter", "hello").toString()))
        val actions = fixture.questions.single().choices.filter { it.id.startsWith("a") }
        assertEquals(1, actions.size)
        assertTrue(actions.single().description.startsWith("set_text"))
    }

    @Test fun suppliedRiskIsForwardedAndNoUnpermittedActionsAreCandidates() {
        val fixture = Fixture()
        fixture.session.views += snapshot(1)
        fixture.session.views += snapshot(2, listOf(label("Sent")))
        fixture.answers += selected("a0"); fixture.answers += selected("done")
        assertTrue(fixture.result(call(plan().put("confirmationRisk", "external_communication").toString())).success)
        assertEquals(AccessibilityConfirmationRisk.EXTERNAL_COMMUNICATION,
            (fixture.session.actions.single() as AccessibilityCommand.Click).confirmationRisk)
        assertTrue(fixture.questions.first().choices.none { it.description.startsWith("back:") || it.description.startsWith("scroll_") })
    }

    @Test fun replayAndCallIdConflictNeverRepeatPaidOrPhysicalOperations() {
        val fixture = Fixture()
        fixture.session.views += snapshot(1, listOf(label("Done")))
        fixture.answers += selected("done")
        val first = fixture.result()
        assertTrue(first.success)
        val replay = fixture.result()
        assertTrue(JSONObject(replay.contentText).getBoolean("replayed"))
        assertCode("idempotency_key_conflict", fixture.result(call(plan().put("goal", "different").toString())))
        assertEquals(1, fixture.questions.size)
    }

    @Test fun invalidIdLowConfidenceAndEscalationNeverAct() {
        listOf(selected("unknown") to "fast_phone_invalid_response",
            selected("a0", 0.79) to "fast_phone_low_confidence",
            selected("a0", Double.NaN) to "fast_phone_low_confidence",
            selected("escalate") to "fast_phone_escalated").forEach { (answer, code) ->
            val fixture = Fixture()
            fixture.session.views += snapshot(1); fixture.answers += answer
            assertCode(code, fixture.result()); assertTrue(fixture.session.actions.isEmpty())
        }
    }

    @Test fun configurationSessionEpochAndLockChangesDuringDecisionNeverActOrVerifyReplacementKey() {
        listOf("revision", "disabled", "session", "epoch", "locked").forEach { kind ->
            val fixture = Fixture()
            fixture.session.views += snapshot(1); fixture.answers += selected("a0")
            fixture.onExecute = { when (kind) {
                "revision" -> fixture.access.rev++
                "disabled" -> fixture.access.enabled = false
                "session" -> fixture.activeSession = Session()
                "epoch" -> fixture.epoch++
                "locked" -> fixture.available = false
            } }
            assertCode("fast_phone_cancelled_or_context_changed", fixture.result())
            assertTrue(fixture.session.actions.isEmpty()); assertEquals(0, fixture.access.successes)
            assertTrue(fixture.access.failures.isEmpty())
            assertTrue(fixture.keys.single().all { it == '\u0000' })
        }
    }

    @Test fun staleNetworkDecisionIsDiscardedAndFreshUiIsDecidedAgainWithoutClickingOldTarget() {
        val fixture = Fixture()
        fixture.session.views += snapshot(1, listOf(button("Old")))
        fixture.session.views += snapshot(3, listOf(button("New")))
        fixture.session.views += snapshot(4, listOf(label("Done")))
        fixture.session.refreshOverride = snapshot(2, listOf(button("New")))
        fixture.answers += selected("a0"); fixture.answers += selected("a0"); fixture.answers += selected("done")
        assertTrue(fixture.result().success)
        assertEquals(3, fixture.questions.size)
        assertEquals(3L, (fixture.session.actions.single() as AccessibilityCommand.Click).handle.correlation.snapshotId.value)
    }

    @Test fun appTextSeparatorsCannotForgeAnUnchangedScreenFingerprint() {
        val fixture = Fixture()
        fixture.session.views += snapshot(1, listOf(button("first:second").copy(contentDescription = "third")))
        fixture.session.views += snapshot(3, listOf(label("Done")))
        fixture.session.refreshOverride = snapshot(2, listOf(button("first").copy(contentDescription = "second:third")))
        fixture.answers += selected("a0"); fixture.answers += selected("done")
        assertTrue(fixture.result().success)
        assertEquals(2, fixture.questions.size)
        assertTrue(fixture.session.actions.isEmpty())
    }

    @Test fun incompleteWrongAppPasswordAndOvercomplexScreensNeverMakePaidDecisions() {
        val cases = listOf(snapshot(1, incomplete = true), snapshot(1, listOf(button().copy(packageName = "other.app"))),
            snapshot(1, listOf(edit().copy(role = SemanticUiRole.PASSWORD_FIELD, text = "private-password"))),
            snapshot(1, List(65) { index -> button("Button $index") }))
        cases.forEach { view ->
            val fixture = Fixture(); fixture.session.views += view
            assertFalse(fixture.result().success)
            assertTrue(fixture.questions.isEmpty()); assertTrue(fixture.session.actions.isEmpty())
        }
    }

    @Test fun noSemanticChangesAfterAcceptedClickEscalatesWithoutRepeat() {
        val fixture = Fixture()
        fixture.session.verified = false
        fixture.session.views += snapshot(1)
        fixture.session.views += snapshot(2)
        fixture.answers += selected("a0")
        assertCode("fast_phone_unverified_action_stopped", fixture.result())
        assertEquals(1, fixture.questions.size); assertEquals(1, fixture.session.actions.size)
    }

    @Test fun acceptedClickWithRealSemanticTransitionCanContinueWithoutRepressing() {
        val fixture = Fixture()
        fixture.session.verified = false
        fixture.session.views += snapshot(1)
        fixture.session.views += snapshot(2, listOf(label("Done")))
        fixture.answers += selected("a0"); fixture.answers += selected("done")
        val result = fixture.result()
        assertTrue(result.success)
        assertEquals(1, fixture.session.actions.size)
        assertEquals(2, fixture.questions.size)
        assertTrue(result.contentText.contains("semantic_screen_changed_not_external_task_proof"))
    }

    @Test fun repeatedSameTargetAndExhaustedStepBudgetStopWithoutExtraMutation() {
        val repeated = Fixture()
        repeated.session.views += snapshot(1); repeated.session.views += snapshot(2)
        repeated.answers += selected("a0"); repeated.answers += selected("a0")
        assertCode("fast_phone_repeat_action_blocked", repeated.result())
        assertEquals(1, repeated.session.actions.size)
        val budget = Fixture()
        budget.session.views += snapshot(1); budget.session.views += snapshot(2, listOf(button("Other")))
        budget.answers += selected("a0"); budget.answers += selected("a0")
        assertCode("fast_phone_step_budget_exhausted", budget.result(call(plan().put("maxSteps", 1).toString())))
        assertEquals(1, budget.session.actions.size)
    }

    @Test fun timeSpentInModelCountsAgainstSingleOverallDeadline() {
        val fixture = Fixture()
        fixture.session.views += snapshot(1); fixture.answers += selected("a0")
        fixture.onExecute = { fixture.now = 1_000 }
        assertCode("fast_phone_deadline_exceeded", fixture.result(call(plan().put("timeoutMs", 1_000).toString())))
        assertTrue(fixture.session.actions.isEmpty())
        assertEquals(listOf(1_000L), fixture.timeouts)
    }

    @Test fun rejectedOrReplayedPhysicalReceiptsAreNeverRetried() {
        listOf("rejected", "replayed").forEach { mode ->
            val fixture = Fixture()
            fixture.session.failed = mode == "rejected"; fixture.session.replayed = mode == "replayed"
            fixture.session.views += snapshot(1); fixture.answers += selected("a0")
            assertFalse(fixture.result().success)
            assertEquals(1, fixture.session.actions.size); assertEquals(1, fixture.questions.size)
        }
    }

    @Test fun serviceErrorIsVisibleContentFreeAndCredentialIsAlwaysWiped() {
        val fixture = Fixture()
        fixture.session.views += snapshot(1); fixture.answers += DecisionsOutcome.Failure("fast_phone_quota_exhausted")
        val result = fixture.result()
        assertCode("fast_phone_quota_exhausted", result)
        assertEquals(listOf("fast_phone_quota_exhausted"), fixture.access.failures)
        assertTrue(fixture.keys.single().all { it == '\u0000' })
        assertFalse(result.contentText.contains("synthetic-key"))
    }

    @Test fun launchRunsOnlyAfterEnabledCredentialAndUiPreflight() {
        val fixture = Fixture()
        fixture.session.views += snapshot(1, listOf(label("Done"))); fixture.answers += selected("done")
        assertTrue(fixture.result(call(plan().put("launch", true).toString())).success)
        assertEquals(1, fixture.launches.size)
        assertEquals("launch_app", fixture.launches.single().tool)
        val disabled = Fixture().apply { access.enabled = false }
        assertFalse(disabled.result(call(plan().put("launch", true).toString())).success)
        assertTrue(disabled.launches.isEmpty())
    }

    @Test fun onlyFreshCompletedGoalNotReplayOrFailureNotifiesCandidateLearning() {
        val fixture = Fixture()
        fixture.session.views += snapshot(1, listOf(label("Done"))); fixture.answers += selected("done")
        assertTrue(fixture.result().success); assertTrue(fixture.result().success)
        assertEquals(1, fixture.completedObservations.size)
        val observed = fixture.completedObservations.single()
        assertEquals(PACKAGE, observed.packageName); assertEquals(setOf("click"), observed.allowedActions)
        val failed = Fixture()
        failed.session.views += snapshot(1); failed.answers += selected("escalate")
        assertFalse(failed.result().success); assertTrue(failed.completedObservations.isEmpty())
    }

    @Test fun goalsQuotingEnteredTextAreNeverPassedToLearningEvenWithNormalizedWhitespaceAndCase() {
        val fixture = Fixture()
        fixture.session.views += snapshot(1, listOf(label("Done"))); fixture.answers += selected("done")
        val args = plan().put("goal", "Send HELLO   PRIVATE WORLD to the supplied chat")
            .put("allowedActions", JSONArray(listOf("set_text"))).put("textToEnter", "hello private world")
        assertTrue(fixture.result(call(args.toString())).success)
        assertTrue(fixture.completedObservations.isEmpty())
    }

    @Test fun learningObserverFailureCannotTurnCompletedPhoneWorkIntoFailure() {
        val fixture = Fixture()
        fixture.onCompleted = { error("private-storage-failure") }
        fixture.session.views += snapshot(1, listOf(label("Done"))); fixture.answers += selected("done")
        val result = fixture.result()
        assertTrue(result.success); assertFalse(result.contentText.contains("private-storage-failure"))
    }

    @Test(timeout = 8_000) fun cancellationStopsHttpAndNeverAdmitsLateUiAction() {
        val fixture = Fixture(queued = true)
        fixture.session.views += snapshot(1); fixture.answers += selected("a0")
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        fixture.onExecute = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        val completions = AtomicInteger()
        val handle = fixture.executor.executeCancellable(call(), DynamicToolCancellation.NONE) { completions.incrementAndGet() }
        Thread { fixture.jobs.single().run(); done.countDown() }.start()
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val quiescent = AtomicInteger(); handle.onQuiescent { quiescent.incrementAndGet() }
        handle.cancel()
        assertEquals(1, fixture.networkCancels.get())
        assertEquals(0, quiescent.get())
        release.countDown(); assertTrue(done.await(3, TimeUnit.SECONDS))
        assertEquals(1, quiescent.get()); assertEquals(0, completions.get())
        assertTrue(fixture.session.actions.isEmpty())
        assertTrue(fixture.completedObservations.isEmpty())
    }

    @Test(timeout = 8_000) fun cancellationKeepsPhysicalLeaseUntilRealActionCallback() {
        val fixture = Fixture(queued = true)
        fixture.session.views += snapshot(1); fixture.answers += selected("a0")
        fixture.session.holdCallback = true
        val done = CountDownLatch(1)
        val handle = fixture.executor.executeCancellable(call(), DynamicToolCancellation.NONE) {}
        Thread { fixture.jobs.single().run(); done.countDown() }.start()
        assertTrue(fixture.session.submitted.await(3, TimeUnit.SECONDS))
        val quiescent = AtomicInteger(); handle.onQuiescent { quiescent.incrementAndGet() }
        assertEquals(DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED, handle.cancel())
        assertEquals(0, quiescent.get())
        fixture.session.deliverHeld()
        assertTrue(done.await(3, TimeUnit.SECONDS)); assertEquals(1, quiescent.get())
        assertEquals(1, fixture.session.actions.size)
    }

    @Test(timeout = 8_000) fun configurationInvalidationActivelyCancelsHttpAndCannotVerifyNewCredential() {
        val fixture = Fixture(queued = true)
        fixture.session.views += snapshot(1); fixture.answers += selected("done")
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        fixture.onExecute = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        var result: DynamicToolExecutionResult? = null
        fixture.executor.execute(call()) { result = it }
        Thread { fixture.jobs.single().run(); done.countDown() }.start()
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        fixture.access.rev++; fixture.executor.invalidateConfiguration()
        assertEquals(1, fixture.networkCancels.get())
        release.countDown(); assertTrue(done.await(3, TimeUnit.SECONDS))
        assertCode("fast_phone_cancelled_or_context_changed", checkNotNull(result))
        assertEquals(0, fixture.access.successes)
    }

    private class Access : DecisionsAccess {
        @Volatile var enabled = true
        @Volatile var rev = 1L
        var key: CharArray? = "synthetic-key".toCharArray()
        var successes = 0
        val failures = mutableListOf<String>()
        override fun isEnabled() = enabled
        override fun revision() = rev
        override fun readApiKey() = key?.copyOf()
        override fun recordFailure(code: String, expectedRevision: Long) { if (rev == expectedRevision) failures += code }
        override fun recordSuccess(expectedRevision: Long) { if (rev == expectedRevision) successes++ }
    }
    private class Fixture(queued: Boolean = false) {
        val access = Access(); val session = Session()
        var activeSession: HansAccessibilitySession? = session
        var missingSession = DeviceControlProjection.failure("accessibility_session_unavailable")
        var available = true; var epoch = 0L; var now = 0L
        val answers = ArrayDeque<DecisionsOutcome>(); val questions = mutableListOf<DecisionsQuestion>()
        val keys = mutableListOf<CharArray>(); val timeouts = mutableListOf<Long>()
        val jobs = mutableListOf<Runnable>(); val launches = mutableListOf<DynamicToolCallParams>()
        val networkCancels = AtomicInteger(); var onExecute: () -> Unit = {}
        val completedObservations = mutableListOf<RoutineObservation>()
        var onCompleted: (RoutineObservation) -> Unit = { completedObservations += it }
        val executor = FastPhoneUseExecutor(
            backgroundExecutor = if (queued) Executor { jobs += it } else Executor(Runnable::run),
            launch = { call, gate -> check(gate.markExternalEffectStarted()); launches += call
                DynamicToolExecutionResult("{\"status\":\"succeeded\"}", true) },
            client = DecisionsClient { question, key, timeout ->
                questions += question; keys += key; timeouts += timeout
                object : DecisionsCall {
                    override fun execute(): DecisionsOutcome { onExecute(); return answers.removeFirst() }
                    override fun cancel() { networkCancels.incrementAndGet() }
                }
            }, access = access, sessions = { activeSession }, evidenceEpoch = { epoch }, nowMillis = { now },
            availability = UiInteractionAvailabilityProbe { if (available) UiInteractionAvailability.AVAILABLE else UiInteractionAvailability.DEVICE_LOCKED },
            onCompleted = { onCompleted(it) }, missingSessionResult = { missingSession })
        fun result(call: DynamicToolCallParams = call()): DynamicToolExecutionResult {
            var result: DynamicToolExecutionResult? = null
            executor.execute(call) { result = it }
            return checkNotNull(result)
        }
    }
    private class Session : HansAccessibilitySession {
        override val sessionId = SESSION
        val views = ArrayDeque<SemanticUiSnapshot>(); var current: SemanticUiSnapshot? = null
        val actions = mutableListOf<AccessibilityCommand>(); var waits = 0
        var refreshOverride: SemanticUiSnapshot? = null
        var verified = true; var failed = false; var replayed = false; var holdCallback = false
        private var held: (() -> Unit)? = null
        val submitted = CountDownLatch(1)
        override fun currentSnapshot() = current
        override fun refreshSnapshot(): SemanticUiSnapshot? = refreshOverride?.also { refreshOverride = null } ?: current
        override fun retainSnapshotForCommands(correlation: UiSnapshotCorrelation) = current?.correlation == correlation
        override fun awaitSnapshot(timeoutMillis: Long, cancelled: () -> Boolean,
            predicate: (SemanticUiSnapshot) -> Boolean): SemanticUiSnapshot? {
            waits++
            while (!cancelled() && views.isNotEmpty()) {
                val view = views.removeFirst().also { current = it }
                if (predicate(view)) return view
            }
            return null
        }
        override fun submit(command: AccessibilityCommand, approval: AccessibilityUserApproval?,
            callback: AccessibilityCommandCallback) = error("Guarded boundary required")
        override fun submitGuarded(command: AccessibilityCommand, cancelled: () -> Boolean,
            callback: AccessibilityCommandCallback): Boolean {
            if (cancelled()) return false
            actions += command
            val result = receipt(command, failed, verified, replayed)
            if (holdCallback) held = { callback.onResult(result) } else callback.onResult(result)
            submitted.countDown()
            return true
        }
        fun deliverHeld() = held!!.invoke()
    }

    private companion object {
        const val PACKAGE = "synthetic.phone"
        val SESSION = AccessibilitySessionId("synthetic-phone-session")
        fun selected(id: String, confidence: Double = 0.95) = DecisionsOutcome.Selected(id, confidence)
        fun plan() = JSONObject().put("packageName", PACKAGE).put("goal", "Complete the synthetic task")
            .put("allowedActions", JSONArray(listOf("click")))
        fun call(args: String = plan().toString(), id: String = "call") =
            DynamicToolCallParams("synthetic-thread", "synthetic-turn", id, "android_ui", "perform_goal", args)
        fun button(text: String = "Open") = RawSemanticUiNode(packageName = PACKAGE, text = text,
            role = SemanticUiRole.BUTTON, bounds = UiBounds(1, 1, 60, 40), clickable = true,
            actions = setOf(SemanticUiAction.CLICK))
        fun edit() = RawSemanticUiNode(packageName = PACKAGE, contentDescription = "Message",
            role = SemanticUiRole.EDIT_TEXT, bounds = UiBounds(1, 50, 300, 100), editable = true,
            actions = setOf(SemanticUiAction.SET_TEXT))
        fun label(text: String) = RawSemanticUiNode(packageName = PACKAGE, text = text,
            role = SemanticUiRole.TEXT, bounds = UiBounds(1, 1, 300, 40))
        fun snapshot(id: Long, nodes: List<RawSemanticUiNode> = listOf(button()), incomplete: Boolean = false) =
            BoundedSemanticUiSnapshotFactory().build(RawSemanticUiSnapshot(
                UiSnapshotCorrelation(SESSION, AccessibilityWindowId(1), AccessibilitySnapshotId(id)),
                UiBounds(0, 0, 400, 800), id, nodes,
                if (incomplete) setOf(SnapshotTruncationReason.PLATFORM_NODE_UNAVAILABLE) else emptySet()))
        fun receipt(command: AccessibilityCommand, failed: Boolean, verified: Boolean, replayed: Boolean): AccessibilityExecutionResult {
            val correlation = when (command) {
                is AccessibilityCommand.Click -> command.handle.correlation
                is AccessibilityCommand.SetText -> command.handle.correlation
                is AccessibilityCommand.Scroll -> command.handle.correlation
                else -> UiSnapshotCorrelation(SESSION, AccessibilityWindowId(1), AccessibilitySnapshotId(1))
            }
            return AccessibilityExecutionResult(command.idempotencyKey,
                if (failed) AccessibilityExecutionStatus.FAILED else AccessibilityExecutionStatus.SUCCEEDED,
                replayed, null, AccessibilityPostcondition(AccessibilityPostconditionKind.NODE_ACTION,
                    if (failed) AccessibilityPostconditionStatus.FAILED else if (verified) AccessibilityPostconditionStatus.VERIFIED
                    else AccessibilityPostconditionStatus.OBSERVED_NOT_VERIFIED,
                    "synthetic_action", correlation, correlation, UiDataTrust.LOCAL_SYSTEM),
                errorCode = if (failed) "synthetic_failed" else null)
        }
        fun assertCode(code: String, result: DynamicToolExecutionResult) {
            assertFalse(result.success); assertEquals(code, JSONObject(result.contentText).getString("errorCode"))
        }
    }
}
