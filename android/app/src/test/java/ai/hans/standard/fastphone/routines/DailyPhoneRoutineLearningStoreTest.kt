package ai.hans.standard.fastphone.routines

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class DailyPhoneRoutineLearningStoreTest {
    @Test fun defaultOffNeverClaimsOrRetainsAccountlessLegacyHistory() {
        val fixture = Fixture()
        fixture.owner = null; fixture.store.snapshot()
        repeat(3) { fixture.observations.observeCompleted(observation()) }
        fixture.owner = OWNER_A
        assertNull(fixture.store.claim())
        assertFalse(fixture.store.snapshot().enabled)
        assertEquals(0, fixture.observations.currentAnalysisEvidence()!!.size)
        assertFalse(fixture.payload().getBoolean("active"))
        assertTrue(fixture.store.setEnabled(true))
        assertNull(fixture.store.claim())
    }

    @Test fun emptyHistoryIsCachedWithoutIdlePollingOrRepeatedHistoryReads() {
        val fixture = Fixture(); fixture.store.snapshot()
        val reads = fixture.history.reads; val writes = fixture.daily.writes
        repeat(5) { fixture.store.snapshot(); fixture.store.promptData() }
        assertEquals(reads, fixture.history.reads); assertEquals(writes, fixture.daily.writes)
        assertEquals(0, fixture.runner.calls)
    }

    @Test fun paraphrasesAreSemanticallyGroupedButGoalIsCopiedFromSuppliedEvidence() {
        val fixture = Fixture().enable()
        fixture.observe("open my garage", steps = 6)
        fixture.observe("raise garage door", steps = 4)
        fixture.observe("let me into garage", steps = 5)
        val claim = fixture.store.claim()!!
        val revision = fixture.store.snapshot().revision
        assertEquals(3, claim.input.evidence.size)
        assertTrue(fixture.store.complete(claim, proposal(claim)))
        assertEquals(revision, fixture.store.snapshot().revision)
        val candidate = fixture.payload().getJSONArray("candidates").getJSONObject(0)
        assertEquals(claim.input.evidence.first().goal, candidate.getString("quotedGoal"))
        assertEquals(4, candidate.getInt("maxSteps")); assertEquals(3, candidate.getInt("helperCompletionCount"))
        assertEquals("none", fixture.payload().getString("authority"))
        assertTrue(fixture.payload().getString("completionMeaning").contains("not verified external success"))
    }

    @Test fun oneAggregatedEvidenceWithThreeCompletionsIsEligible() {
        val fixture = Fixture().enable(); fixture.seed()
        val claim = fixture.store.claim()!!
        assertEquals(1, claim.input.evidence.size)
        assertTrue(fixture.store.complete(claim, proposal(claim)))
        assertEquals(1, fixture.store.snapshot().candidateCount)
    }

    @Test fun onlyOneSuccessfulAnalysisPerLocalDayAndUnchangedNextDayNeedsNoCall() {
        val fixture = Fixture().enable(); fixture.seed()
        assertEquals(DailyPhoneRoutineRunResult.COMPLETED, fixture.coordinator.runOnce())
        fixture.observe("different garage task")
        assertEquals(DailyPhoneRoutineRunResult.SKIPPED, fixture.coordinator.runOnce())
        assertEquals(1, fixture.runner.calls)
        fixture.clock.advanceDay()
        assertEquals(DailyPhoneRoutineRunResult.COMPLETED, fixture.coordinator.runOnce())
        fixture.clock.advanceDay()
        assertEquals(DailyPhoneRoutineLearningStatus.NO_NEW_HISTORY, fixture.store.snapshot().status)
        assertNotNull(fixture.store.snapshot().nextEligibleAtMillis)
        assertEquals(DailyPhoneRoutineRunResult.SKIPPED, fixture.coordinator.runOnce())
        assertEquals(2, fixture.runner.calls)
    }

    @Test fun completionDayIsChargedAtMidnightAndClockRollbackCannotAnalyzeAgain() {
        val fixture = Fixture().enable(); fixture.seed()
        val claim = fixture.store.claim()!!
        fixture.clock.day++ // A timezone/day boundary can change without the lease expiring.
        assertTrue(fixture.store.complete(claim, proposal(claim)))
        assertEquals(DailyPhoneRoutineLearningStatus.ALREADY_ANALYZED_TODAY, fixture.store.snapshot().status)
        fixture.clock.day--
        assertNull(fixture.store.claim())
    }

    @Test fun attemptsHaveDurableBackoffAndMaximumThreeCallsPerLocalDay() {
        val fixture = Fixture().enable(); fixture.seed()
        fixture.runner.result = { "not json" }
        assertEquals(DailyPhoneRoutineRunResult.FAILED, fixture.coordinator.runOnce())
        assertEquals("invalid_analysis_output", fixture.store.snapshot().errorCode)
        repeat(2) { assertEquals(DailyPhoneRoutineRunResult.SKIPPED, fixture.coordinator.runOnce()) }
        fixture.clock.time += 15 * 60_000
        assertEquals(DailyPhoneRoutineRunResult.FAILED, fixture.coordinator.runOnce())
        fixture.clock.time += 60 * 60_000
        assertEquals(DailyPhoneRoutineRunResult.FAILED, fixture.coordinator.runOnce())
        fixture.clock.time += 60 * 60_000
        assertNull(fixture.store.claim()); assertEquals(3, fixture.runner.calls)
        assertEquals(fixture.clock.nextLocalDayStartMillis(), fixture.store.snapshot().nextEligibleAtMillis)
        fixture.clock.advanceDay(); assertNotNull(fixture.store.claim())
    }

    @Test fun durableLeaseSurvivesRestartThenStaleRunCannotPublish() {
        val fixture = Fixture().enable(); fixture.seed()
        val old = fixture.store.claim()!!
        val restarted = fixture.reopen()
        assertNull(restarted.claim())
        fixture.clock.time += DailyPhoneRoutineLearningStore.LEASE_MILLIS + 1
        val fresh = restarted.claim()!!
        assertFalse(fixture.store.complete(old, proposal(old)))
        assertTrue(restarted.complete(fresh, proposal(fresh)))
    }

    @Test fun disabledForgottenAndChangedAccountRejectPendingOutputAndRevokeHints() {
        listOf("disable", "forget", "change", "missing").forEach { operation ->
            val fixture = Fixture().enable(); fixture.seed()
            val claim = fixture.store.claim()!!
            val revision = fixture.store.snapshot().revision
            when (operation) {
                "disable" -> assertTrue(fixture.store.setEnabled(false))
                "forget" -> assertTrue(fixture.store.forget())
                "change" -> fixture.owner = OWNER_B
                else -> fixture.owner = null
            }
            assertFalse(fixture.store.complete(claim, proposal(claim)))
            assertFalse(fixture.payload().getBoolean("active"))
            assertTrue(fixture.store.snapshot().revision > revision)
            if (operation == "forget") assertTrue(fixture.store.snapshot().enabled)
            else assertFalse(fixture.store.snapshot().enabled)
            if (operation != "disable") assertEquals(0, fixture.observations.currentAnalysisEvidence()!!.size)
        }
    }

    @Test fun accountChangeCannotReusePreviousPrivateGoalsOrOptIn() {
        val fixture = Fixture().enable(); fixture.seed("private garage recipient")
        fixture.coordinator.runOnce(); assertTrue(fixture.payload().getBoolean("active"))
        fixture.owner = OWNER_B
        assertFalse(fixture.payload().toString().contains("private garage recipient"))
        assertFalse(fixture.store.snapshot().enabled)
        assertFalse(fixture.store.observeCompleted(observation()))
        assertTrue(fixture.store.setEnabled(true)); assertNull(fixture.store.claim())
    }

    @Test fun secondInstanceCannotExportObserveOrRestoreStaleAuthorityAfterDisableOrForget() {
        val fixture = Fixture().enable(); fixture.seed(); fixture.coordinator.runOnce()
        val stale = fixture.reopen()
        assertTrue(JSONObject(stale.promptData()).getBoolean("active"))
        assertTrue(fixture.store.setEnabled(false))
        assertFalse(stale.snapshot().enabled)
        assertFalse(JSONObject(stale.promptData()).getBoolean("active"))
        assertFalse(stale.observeCompleted(observation()))
        assertTrue(stale.forget()); assertFalse(stale.snapshot().enabled)
        assertTrue(fixture.store.setEnabled(true)); fixture.seed(); fixture.clock.advanceDay()
        fixture.coordinator.runOnce(); assertTrue(JSONObject(stale.promptData()).getBoolean("active"))
        val before = stale.snapshot().revision
        assertTrue(fixture.store.forget())
        assertTrue(stale.snapshot().revision > before)
        assertFalse(JSONObject(stale.promptData()).getBoolean("active"))
        assertEquals(0, fixture.observations.currentAnalysisEvidence()!!.size)
    }

    @Test fun killedAccountResetBeforeHistoryClearMustResumeClearBeforeOptIn() {
        val fixture = Fixture().enable(); fixture.seed("old private garage")
        fixture.owner = OWNER_B; fixture.history.failWrite = true
        assertFalse(fixture.store.snapshot().enabled)
        val durable = JSONObject(fixture.daily.bytes!!.toString(Charsets.UTF_8))
        assertTrue(durable.getBoolean("historyResetPending"))
        fixture.history.failWrite = false
        val restarted = fixture.reopen()
        assertTrue(restarted.setEnabled(true))
        assertEquals(0, fixture.observations.currentAnalysisEvidence()!!.size)
        assertNull(restarted.claim())
        assertFalse(restarted.promptData().contains("old private garage"))
    }

    @Test fun unknownInventedDuplicateCrossPackageAndCrossActionGroupsAreRejected() {
        val mutations: List<(DailyPhoneRoutineClaim) -> String> = listOf(
            { claim -> JSONObject(proposal(claim)).put("goal", "invented instruction").toString() },
            { claim -> JSONObject(proposal(claim)).apply { getJSONArray("groups").getJSONObject(0).put("maxSteps", 8) }.toString() },
            { claim -> JSONObject(proposal(claim)).apply { getJSONArray("groups").getJSONObject(0).put("evidenceIds", JSONArray(listOf("f".repeat(64)))) }.toString() },
            { claim -> JSONObject(proposal(claim)).apply { getJSONArray("groups").put(getJSONArray("groups").getJSONObject(0)) }.toString() },
            { claim -> proposal(claim, claim.input.evidence.map { it.id }) },
        )
        mutations.forEachIndexed { index, mutation ->
            val fixture = Fixture().enable(); fixture.seed()
            if (index == mutations.lastIndex) fixture.observe("different app", pkg = "other.synthetic")
            val claim = fixture.store.claim()!!
            assertFalse(fixture.store.complete(claim, mutation(claim)))
            assertFalse(fixture.payload().getBoolean("active"))
        }
        val fixture = Fixture().enable(); fixture.seed()
        fixture.observe("same package different authority", actions = setOf("back"))
        val claim = fixture.store.claim()!!
        assertFalse(fixture.store.complete(claim, proposal(claim)))
    }

    @Test fun insufficientGroupSupportIsRejectedAndBudgetCanOnlyShrink() {
        val fixture = Fixture().enable()
        fixture.observe("one garage goal"); fixture.observe("two garage goal"); fixture.observe("three garage goal")
        val claim = fixture.store.claim()!!
        assertFalse(fixture.store.complete(claim, proposal(claim, listOf(claim.input.evidence.first().id))))
        fixture.clock.advanceDay()
        val next = fixture.store.claim()!!
        fixture.observe(next.input.evidence.first().goal, steps = 1)
        assertTrue(fixture.store.complete(next, proposal(next)))
        assertEquals(1, fixture.payload().getJSONArray("candidates").getJSONObject(0).getInt("maxSteps"))
    }

    @Test fun localQuerySelectionDoesNotExposeUnrelatedGoalsAndReturnsRevocationData() {
        val fixture = Fixture().enable(); fixture.seed("open the garage")
        fixture.coordinator.runOnce()
        assertTrue(JSONObject(fixture.store.promptData(currentUserText = "Bitte Garage öffnen")).getBoolean("active"))
        val unrelated = JSONObject(fixture.store.promptData(currentUserText = "send an email"))
        assertFalse(unrelated.getBoolean("active")); assertEquals(0, unrelated.getJSONArray("candidates").length())
        assertFalse(fixture.store.promptData(currentUserText = "send an email").contains("open the garage"))
        assertTrue(fixture.store.setEnabled(false))
        val revoked = fixture.payload()
        assertEquals("phone_routines", revoked.getString("source")); assertFalse(revoked.getBoolean("active"))
    }

    @Test fun corruptAndOversizeStateFailClosedAndForgetIsExplicitRecovery() {
        listOf("{bad", "[]", "x".repeat(32 * 1_024 + 1), "{\"version\":2}").forEach { corrupt ->
            val fixture = Fixture(); fixture.daily.bytes = corrupt.toByteArray()
            val store = fixture.reopen()
            assertFalse(store.snapshot().enabled); assertEquals("learning_storage_invalid", store.snapshot().errorCode)
            assertNull(store.claim()); assertFalse(store.setEnabled(true))
            assertTrue(store.forget()); assertTrue(store.setEnabled(true)); assertNull(store.claim())
        }
    }

    @Test fun writeReadbackProofIsRequiredForOptInAndAnalysisPublication() {
        val fixture = Fixture(); fixture.store.snapshot(); fixture.daily.discardWrites = true
        assertFalse(fixture.store.setEnabled(true)); assertFalse(fixture.payload().getBoolean("active"))
        val other = Fixture().enable(); other.seed(); val claim = other.store.claim()!!
        other.daily.discardWrites = true
        assertFalse(other.store.complete(claim, proposal(claim))); assertFalse(other.payload().getBoolean("active"))
    }

    @Test fun boundedEvidenceWindowOutputAndDurableStateContainNoInventedGoalOrSecrets() {
        val fixture = Fixture().enable()
        repeat(64) { index -> fixture.observe("漢".repeat(480) + index) }
        val claim = fixture.store.claim()!!
        assertTrue(claim.input.inputJson.toByteArray().size <= 32 * 1_024)
        assertTrue(claim.input.evidence.size in 1..64)
        val json = JSONObject(claim.input.inputJson)
        assertTrue(json.getInt("omittedEvidenceCount") > 0)
        assertEquals(setOf("id", "packageName", "goal", "allowedActions", "maxSteps", "helperCompletionCount"),
            json.getJSONArray("evidence").getJSONObject(0).keys().asSequence().toSet())
        assertFalse(claim.toString().contains("漢")); assertFalse(claim.input.toString().contains("漢"))
        assertFalse(fixture.store.complete(claim, "x".repeat(32 * 1_024 + 1)))
        assertTrue(fixture.daily.bytes!!.size <= 32 * 1_024)
        assertFalse(fixture.daily.bytes!!.toString(Charsets.UTF_8).contains("漢"))
    }

    @Test fun coordinatorChecksCancellationBeforeClaimBeforeNetworkAndBeforePublication() {
        val fixture = Fixture().enable(); fixture.seed()
        val writes = fixture.daily.writes
        assertEquals(DailyPhoneRoutineRunResult.SKIPPED, fixture.coordinator.runOnce { false })
        assertEquals(writes, fixture.daily.writes); assertEquals(0, fixture.runner.calls)
        var checks = 0
        assertEquals(DailyPhoneRoutineRunResult.PREEMPTED, fixture.coordinator.runOnce { ++checks < 3 })
        assertEquals(0, fixture.runner.calls)
        fixture.clock.time += 15 * 60_000
        fixture.runner.onRun = { fixture.coordinator.preemptCurrent() }
        assertEquals(DailyPhoneRoutineRunResult.PREEMPTED, fixture.coordinator.runOnce())
        assertFalse(fixture.payload().getBoolean("active")); assertTrue(fixture.runner.preemptions > 0)
    }

    @Test fun concurrentForgetWhileRunnerReturnsCannotPublishOrRestoreForgottenGoals() {
        val fixture = Fixture().enable(); fixture.seed()
        fixture.runner.onRun = { assertTrue(fixture.store.forget()) }
        assertEquals(DailyPhoneRoutineRunResult.PREEMPTED, fixture.coordinator.runOnce())
        assertTrue(fixture.store.snapshot().enabled)
        assertEquals(0, fixture.observations.currentAnalysisEvidence()!!.size)
        assertFalse(fixture.payload().getBoolean("active"))
    }

    @Test fun runnerAccountMismatchIsNeverInvokedAndExceptionsHaveOnlySafeErrorCodes() {
        val fixture = Fixture().enable(); fixture.seed(); fixture.runner.owner = OWNER_B
        assertEquals(DailyPhoneRoutineRunResult.PREEMPTED, fixture.coordinator.runOnce()); assertEquals(0, fixture.runner.calls)
        fixture.clock.time += 15 * 60_000; fixture.runner.owner = OWNER_A
        fixture.runner.result = { error("private token must never appear") }
        assertEquals(DailyPhoneRoutineRunResult.FAILED, fixture.coordinator.runOnce())
        assertEquals("analysis_failed", fixture.store.snapshot().errorCode)
        assertFalse(fixture.daily.bytes!!.toString(Charsets.UTF_8).contains("private token"))
    }

    @Test fun idlePreemptionDoesNotPoisonTheNextRunnerOrConsumeAnAttempt() {
        val fixture = Fixture().enable(); fixture.seed()
        fixture.coordinator.preemptCurrent()
        assertEquals(0, fixture.runner.preemptions)
        assertEquals(DailyPhoneRoutineRunResult.COMPLETED, fixture.coordinator.runOnce())
        assertEquals(1, fixture.runner.calls)
    }

    @Test fun completionCallbackCanAwaitCrossThreadPreemptionWithoutPublicationLockInversion() {
        val fixture = Fixture().enable(); fixture.seed()
        val otherThread = Executors.newSingleThreadExecutor()
        val callbackReached = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        val subscription = fixture.store.subscribe { snapshot ->
            if (snapshot.lastSuccessAtMillis != null) {
                val future = otherThread.submit { fixture.coordinator.preemptCurrent(); callbackReached.set(true) }
                try { future.get(2, TimeUnit.SECONDS) } catch (caught: Exception) { failure.set(caught) }
            }
        }
        try {
            assertEquals(DailyPhoneRoutineRunResult.COMPLETED, fixture.coordinator.runOnce())
            assertNull(failure.get()); assertTrue(callbackReached.get())
        } finally { subscription.close(); otherThread.shutdownNow() }
    }

    @Test fun completedRunAcknowledgesPendingRunnerPreemptionBeforeNextDailyAttempt() {
        val fixture = Fixture().enable(); fixture.seed()
        fixture.runner.onRun = { fixture.coordinator.preemptCurrent() }
        assertEquals(DailyPhoneRoutineRunResult.PREEMPTED, fixture.coordinator.runOnce())
        assertEquals(1, fixture.runner.acknowledgments)
        assertFalse(fixture.runner.pendingPreemption)
        fixture.runner.onRun = {}; fixture.clock.time += 15 * 60_000
        assertEquals(DailyPhoneRoutineRunResult.COMPLETED, fixture.coordinator.runOnce())
        assertEquals(2, fixture.runner.calls); assertEquals(2, fixture.runner.acknowledgments)
    }

    @Test fun finalCommitGuardRejectsCancellationAfterOutputValidation() {
        val fixture = Fixture().enable(); fixture.seed()
        val claim = fixture.store.claim()!!
        var guarded = false
        assertFalse(fixture.store.complete(claim, proposal(claim)) { guarded = true; false })
        assertTrue(guarded)
        assertFalse(fixture.payload().getBoolean("active"))
        assertEquals("preempted", fixture.store.snapshot().errorCode)
    }

    @Test fun malformedDurableExpandedGroupsAndMoreThanEightProposalsAreRejected() {
        val fixture = Fixture().enable(); fixture.seed()
        val claim = fixture.store.claim()!!
        val group = JSONObject(proposal(claim)).getJSONArray("groups").getJSONObject(0)
        val expanded = JSONObject().put("version", 1).put("groups", JSONArray(List(9) { group })).toString()
        assertFalse(fixture.store.complete(claim, expanded))
        val validState = JSONObject(fixture.daily.bytes!!.toString(Charsets.UTF_8))
        val mutations: List<JSONObject.() -> Unit> = listOf(
            { put("revision", 1.5) }, { put("attempts", 4) }, { put("owner", "not an account fingerprint") },
            { put("errorCode", "raw server response") }, { put("apiKey", "forbidden") },
            { put("historyResetPending", "true") }, { put("lastSuccessDay", 3); put("lastSuccessAtMillis", JSONObject.NULL) })
        mutations.forEach { mutation ->
            fixture.daily.bytes = JSONObject(validState.toString()).apply(mutation).toString().toByteArray()
            val corrupted = fixture.reopen()
            assertEquals(DailyPhoneRoutineLearningStatus.ERROR, corrupted.snapshot().status)
            assertFalse(JSONObject(corrupted.promptData()).getBoolean("active"))
        }
    }

    private class History : PhoneRoutineStorage {
        var bytes: ByteArray? = null; var reads = 0; var failWrite = false
        override fun read(): ByteArray? { reads++; return bytes?.copyOf() }
        override fun writeAtomically(bytes: ByteArray) { check(!failWrite); this.bytes = bytes.copyOf() }
    }
    private class Daily : DailyPhoneRoutineStorage {
        var bytes: ByteArray? = null; var writes = 0; var discardWrites = false
        override fun read() = bytes?.copyOf()
        override fun writeAtomically(bytes: ByteArray) { writes++; if (!discardWrites) this.bytes = bytes.copyOf() }
    }
    private class Clock : DailyPhoneRoutineClock {
        var time = 1_000_000L; var day = 20_000L
        override fun nowMillis() = time
        override fun localEpochDay() = day
        override fun nextLocalDayStartMillis() = time + 86_400_000L
        fun advanceDay() { time += 86_400_000L; day++ }
    }
    private class Runner : PhoneRoutineAnalysisRunner {
        var owner: String? = OWNER_A; var calls = 0; var preemptions = 0
        var pendingPreemption = false; var acknowledgments = 0
        var onRun: () -> Unit = {}
        var result: (String) -> String = { input ->
            val evidence = JSONObject(input).getJSONArray("evidence")
            val id = evidence.getJSONObject(0).getString("id")
            JSONObject().put("version", 1).put("groups", JSONArray().put(JSONObject()
                .put("evidenceIds", JSONArray(listOf(id))).put("templateEvidenceId", id))).toString()
        }
        override fun currentAccountFingerprint() = owner
        override fun run(inputJson: String, expectedAccountFingerprint: String): String {
            check(!pendingPreemption) { "pending runner preemption" }
            calls++; onRun(); return result(inputJson)
        }
        override fun preemptCurrent() { preemptions++; pendingPreemption = true }
        override fun acknowledgeCompletedRun() { acknowledgments++; pendingPreemption = false }
        override fun close() = Unit
    }
    private class Fixture {
        val history = History(); val daily = Daily(); val clock = Clock(); var owner: String? = OWNER_A
        val observations = PhoneRoutineLearningStore(history, nowMillis = { clock.time })
        val store = reopen(); val runner = Runner(); val coordinator = DailyPhoneRoutineLearningCoordinator(store, runner)
        fun reopen() = DailyPhoneRoutineLearningStore(daily, observations, clock) { owner }
        fun enable() = apply { assertTrue(store.setEnabled(true)) }
        fun observe(goal: String, pkg: String = "synthetic.garage", actions: Set<String> = setOf("click"), steps: Int = 4) {
            assertTrue(store.observeCompleted(observation(pkg, goal, actions, steps)))
        }
        fun seed(goal: String = "open the garage") { repeat(3) { observe(goal) } }
        fun payload() = JSONObject(store.promptData())
    }
    companion object {
        private const val OWNER_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val OWNER_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private fun observation(pkg: String = "synthetic.garage", goal: String = "open the garage", actions: Set<String> = setOf("click"), steps: Int = 4) =
            RoutineObservation(pkg, goal, actions, steps)
        private fun proposal(claim: DailyPhoneRoutineClaim, ids: List<String> = claim.input.evidence.map { it.id }): String =
            JSONObject().put("version", 1).put("groups", JSONArray().put(JSONObject().put("evidenceIds", JSONArray(ids))
                .put("templateEvidenceId", ids.first()))).toString()
    }
}
