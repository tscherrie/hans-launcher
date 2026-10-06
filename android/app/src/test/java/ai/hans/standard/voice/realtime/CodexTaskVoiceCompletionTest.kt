package ai.hans.standard.voice.realtime

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Work lifecycle only. Playback-tail behavior belongs to the owning session's tests. */
class CodexTaskVoiceCompletionTest {
    private val origin = TimeUnit.SECONDS.toNanos(100)
    private fun time(ms: Long) = origin + TimeUnit.MILLISECONDS.toNanos(ms)
    private fun controller() = CodexTaskVoiceCompletion(origin)
    private fun CodexTaskVoiceCompletion.state(
        ms: Long, revision: Long, active: String? = null, pending: Boolean = false,
        terminal: String? = null, outcome: CodexTaskVoiceWorkOutcome = CodexTaskVoiceWorkOutcome.COMPLETED,
    ) = onWorkState(CodexTaskVoiceWorkState(revision, active, pending,
        terminal?.let { CodexTaskVoiceTerminal(it, outcome) }), time(ms))
    private fun CodexTaskVoiceCompletion.beginWork() {
        state(0, 0)
        onHandoff(time(100))
        state(200, 1, active = "task")
    }
    private fun finished(outcome: CodexTaskVoiceWorkOutcome = CodexTaskVoiceWorkOutcome.COMPLETED) =
        CodexTaskVoiceCompletion.Decision.Finished(outcome)
    private fun missingWork() = CodexTaskVoiceCompletion.Decision.Failed("codex_task_voice_work_receipt_timeout")

    @Test fun matchingTerminalFinishesImmediatelyWithoutAnyOutputTextInputOrMuteReceipt() {
        val c = controller(); c.beginWork()
        assertNull(c.evaluate(time(299)))
        c.state(300, 2, terminal = "task")
        assertEquals(finished(), c.evaluate(time(300)))
        assertNull(c.nextDeadlineNanos)
    }

    @Test fun finishedDecisionMeansWorkNotFinishedSpeechInBothAnswerReceiptOrders() {
        for (answerFirst in listOf(false, true)) {
            val c = controller(); c.beginWork()
            if (answerFirst) c.onAssistantResponseStarted("answer", time(250))
            c.state(300, 2, terminal = "task")
            if (!answerFirst) c.onAssistantResponseStarted("answer", time(350))
            // The speaker can still be playing; the session owns its bounded output tail.
            c.onAudioActivity(LiveVoiceAudioActivity(LiveVoiceAudioDirection.OUTPUT, true, time(400)), time(400))
            assertEquals(finished(), c.evaluate(time(400)))
        }
    }

    @Test fun muteAndArbitrarilyLongBufferedInputNeverDelayMatchingWorkTerminal() {
        for (muted in listOf(false, true)) {
            val c = controller(); c.beginWork()
            c.onInputMuted(muted, time(220))
            c.updateInputDelayNanos(TimeUnit.MINUTES.toNanos(2), time(230))
            c.state(300, 2, terminal = "task")
            assertEquals(finished(), c.evaluate(time(300)))
        }
    }

    @Test fun decisionIsStickyAtReceiptBeforeFirstEvaluateDespiteLateUserDeltaHandoffAndAudio() {
        val c = controller(); c.beginWork()
        c.state(300, 2, terminal = "task")
        // No evaluate between the actual terminal and these late receipts.
        c.onUserInput(time(301))
        c.onHandoff(time(302))
        c.onAssistantResponseStarted("duplicate-or-late", time(303))
        c.onAudioActivity(LiveVoiceAudioActivity(LiveVoiceAudioDirection.INPUT, true, time(304), false), time(304))
        c.onAudioActivity(LiveVoiceAudioActivity(LiveVoiceAudioDirection.OUTPUT, true, time(305), false), time(305))
        c.onInputMuted(false, time(306))
        c.state(307, 3, active = "too-late")
        assertEquals(finished(), c.evaluate(time(1_000_000)))
        assertNull(c.nextDeadlineNanos)
    }

    @Test fun duplicateAndContradictoryLaterTerminalsCannotChangeObservedOutcome() {
        val c = controller(); c.beginWork()
        c.state(300, 2, terminal = "task", outcome = CodexTaskVoiceWorkOutcome.INTERRUPTED)
        c.state(301, 2, terminal = "task", outcome = CodexTaskVoiceWorkOutcome.COMPLETED)
        c.state(302, 3, terminal = "task", outcome = CodexTaskVoiceWorkOutcome.FAILED)
        assertEquals(finished(CodexTaskVoiceWorkOutcome.INTERRUPTED), c.evaluate(time(303)))
    }

    @Test fun completedInterruptedAndFailedWorkKeepTheirExactTerminalOutcome() {
        CodexTaskVoiceWorkOutcome.entries.forEach { outcome ->
            val c = controller(); c.beginWork()
            c.state(300, 2, terminal = "task", outcome = outcome)
            assertEquals(finished(outcome), c.evaluate(time(300)))
        }
    }

    @Test fun mediaMalformedIdsDuplicatesGapsAndFutureTimesCannotAuthorizeOrBlockWork() {
        val c = controller(); c.beginWork()
        repeat(1_000) {
            c.onAssistantResponseStarted("", Long.MAX_VALUE)
            c.onUserInput(Long.MAX_VALUE)
            c.onAudioActivity(LiveVoiceAudioActivity(LiveVoiceAudioDirection.OUTPUT, true, Long.MAX_VALUE, false),
                Long.MAX_VALUE)
            c.onInputMuted(it % 2 == 0, Long.MAX_VALUE)
            c.updateInputDelayNanos(-1, Long.MAX_VALUE)
        }
        assertNull(c.evaluate(time(250)))
        c.state(300, 2, terminal = "task")
        assertEquals(finished(), c.evaluate(time(300)))
    }

    @Test fun lateUserDeltaBeforeTerminalDoesNotDiscardObservedWork() {
        val c = controller(); c.beginWork()
        c.onUserInput(time(250))
        c.state(300, 2, terminal = "task")
        assertEquals(finished(), c.evaluate(time(300)))
    }

    @Test fun noHandoffClosesAtExactly60SecondsFromActivation() {
        val c = controller()
        assertEquals(time(60_000), c.nextDeadlineNanos)
        assertNull(c.evaluate(time(59_999)))
        assertEquals(CodexTaskVoiceCompletion.Decision.NoHandoffTimeout, c.evaluate(time(60_000)))
        assertNull(c.nextDeadlineNanos)
    }

    @Test fun speechTranscriptsMuteAndNativeOnlyAnswersNeverExtendNoHandoffDeadline() {
        val c = controller()
        c.onUserInput(time(20_000))
        c.onInputMuted(true, time(30_000))
        c.onAssistantResponseStarted("native-answer", time(59_000))
        c.onAudioActivity(LiveVoiceAudioActivity(LiveVoiceAudioDirection.OUTPUT, true, time(59_500)), time(59_500))
        assertEquals(time(60_000), c.nextDeadlineNanos)
        assertEquals(CodexTaskVoiceCompletion.Decision.NoHandoffTimeout, c.evaluate(time(60_000)))
    }

    @Test fun preExistingTurnCompletedWithoutHandoffNeverCountsAsRequestedWork() {
        val c = controller()
        c.state(0, 1, active = "prior")
        c.state(300, 2, terminal = "prior")
        assertNull(c.evaluate(time(300)))
        assertEquals(CodexTaskVoiceCompletion.Decision.NoHandoffTimeout, c.evaluate(time(60_000)))
    }

    @Test fun handoffCanSteerExistingActiveTurnAndRepeatedSteersNeedOnlyOneTerminal() {
        val c = controller()
        c.state(0, 1, active = "prior")
        c.onHandoff(time(100)); c.onHandoff(time(150)); c.onHandoff(time(200))
        c.state(300, 2, terminal = "prior")
        assertEquals(finished(), c.evaluate(time(300)))
    }

    @Test fun handoffPermanentlyCancelsSixtySecondCutoffForKnownLongRunningWork() {
        val c = controller(); c.beginWork()
        assertTrue(c.hasHandoff)
        assertNull(c.nextDeadlineNanos)
        assertNull(c.evaluate(time(600_000)))
        c.state(600_100, 2, terminal = "task")
        assertEquals(finished(), c.evaluate(time(600_100)))
    }

    @Test fun lateHandoffCannotWinBecauseNoHandoffTimerCallbackWasDelayed() {
        val c = controller()
        c.onHandoff(time(60_000))
        assertEquals(CodexTaskVoiceCompletion.Decision.NoHandoffTimeout, c.evaluate(time(60_001)))
        val before = controller(); before.onHandoff(time(59_999))
        assertNull(before.evaluate(time(60_000)))
        assertTrue(before.hasHandoff)
    }

    @Test fun handoffWithoutObservedWorkFailsVisiblyAfter45Seconds() {
        val c = controller(); c.state(0, 0); c.onHandoff(time(100))
        assertEquals(time(45_100), c.nextDeadlineNanos)
        assertNull(c.evaluate(time(45_099)))
        assertEquals(missingWork(), c.evaluate(time(45_100)))
    }

    @Test fun terminalBeforeHandoffCannotBeReassignedToThatLaterRequest() {
        val c = controller()
        c.state(0, 1, active = "prior"); c.state(50, 2, terminal = "prior")
        c.onHandoff(time(100))
        c.state(200, 3, terminal = "prior") // A sticky/replayed old terminal is still not owned.
        assertNull(c.evaluate(time(200)))
        assertEquals(missingWork(), c.evaluate(time(45_100)))
    }

    @Test fun terminalWithoutAnyObservedActiveTurnIsNotEnoughEvenAfterHandoff() {
        val c = controller(); c.state(0, 0); c.onHandoff(time(100))
        c.state(200, 1, terminal = "unmatched")
        assertNull(c.evaluate(time(200)))
        assertEquals(missingWork(), c.evaluate(time(45_100)))
    }

    @Test fun unrelatedTerminalCannotReplaceTheTrackedTaskTerminal() {
        val c = controller(); c.beginWork()
        c.state(300, 2, terminal = "other")
        assertNull(c.evaluate(time(300)))
        assertEquals(time(45_300), c.nextDeadlineNanos)
        c.state(500, 3, terminal = "task")
        assertEquals(finished(), c.evaluate(time(500)))
    }

    @Test fun idleWithoutMatchingTerminalIsBoundedButNeverCalledSuccess() {
        val c = controller(); c.beginWork(); c.state(300, 2)
        assertEquals(time(45_300), c.nextDeadlineNanos)
        assertEquals(missingWork(), c.evaluate(time(45_300)))
    }

    @Test fun pendingDispatchBlocksCompletionUntilSettledWithoutNewWork() {
        val c = controller(); c.beginWork()
        c.state(300, 2, pending = true, terminal = "task")
        assertNull(c.evaluate(time(300)))
        assertEquals(time(45_300), c.nextDeadlineNanos)
        c.onUserInput(time(400)); c.onAssistantResponseStarted("late-answer", time(500))
        c.state(600, 3)
        assertEquals(finished(), c.evaluate(time(600)))
    }

    @Test fun pendingDispatchAfterTerminalWithoutActiveWorkTimesOutAndRepeatedReceiptsDoNotExtendIt() {
        val c = controller(); c.beginWork()
        c.state(300, 2, pending = true, terminal = "task")
        assertEquals(time(45_300), c.nextDeadlineNanos)
        c.state(44_000, 3, pending = true, terminal = "task")
        assertEquals(time(45_300), c.nextDeadlineNanos)
        assertNull(c.evaluate(time(45_299)))
        assertEquals(missingWork(), c.evaluate(time(45_300)))
        c.state(45_301, 4)
        assertEquals(missingWork(), c.evaluate(time(45_301)))
    }

    @Test fun pendingDispatchDeadlineIsRemovedWhenGenuineNextTurnStarts() {
        val c = controller(); c.beginWork()
        c.state(300, 2, pending = true, terminal = "task")
        c.state(600, 3, active = "next")
        assertNull(c.nextDeadlineNanos)
        assertNull(c.evaluate(time(3_600_000)))
        c.state(3_600_001, 4, terminal = "next")
        assertEquals(finished(), c.evaluate(time(3_600_001)))
    }

    @Test fun handoffWhilePriorTerminalIsBlockedByPendingDispatchRequiresNewWork() {
        val c = controller(); c.beginWork()
        c.state(300, 2, pending = true, terminal = "task")
        c.onHandoff(time(400))
        c.state(500, 3, terminal = "task")
        assertNull(c.evaluate(time(500)))
        c.state(600, 4, active = "next")
        c.state(700, 5, terminal = "next")
        assertEquals(finished(), c.evaluate(time(700)))
    }

    @Test fun multipleObservedTurnsAllNeedTerminalsAndNoActiveTurnMayRemain() {
        val c = controller(); c.beginWork()
        c.state(250, 2, active = "second")
        c.state(300, 3, active = "second", terminal = "task")
        assertNull(c.evaluate(time(300)))
        c.state(400, 4, terminal = "second")
        assertEquals(finished(), c.evaluate(time(400)))
    }

    @Test fun idleSecondTurnTerminalDoesNotEraseUnfinishedFirstTurn() {
        val c = controller(); c.beginWork(); c.state(250, 2, active = "second")
        c.state(300, 3, terminal = "second")
        assertNull(c.evaluate(time(300)))
        assertEquals(time(45_300), c.nextDeadlineNanos)
        c.state(400, 4, terminal = "task")
        assertEquals(finished(), c.evaluate(time(400)))
    }

    @Test fun terminalAccompaniedByStillActiveIdentityDoesNotCloseBeforeIdleReceipt() {
        val c = controller(); c.beginWork()
        c.state(300, 2, active = "task", terminal = "task")
        assertNull(c.evaluate(time(300)))
        c.state(400, 3)
        assertEquals(finished(), c.evaluate(time(400)))
    }

    @Test fun staleOrDuplicateRevisionCannotDeclareAnActiveTaskFinished() {
        val c = controller(); c.state(0, 1); c.onHandoff(time(100))
        c.state(200, 5, active = "task")
        c.state(300, 4, terminal = "task"); c.state(400, 5, terminal = "task")
        assertNull(c.evaluate(time(400)))
        c.state(500, 6, terminal = "task")
        assertEquals(finished(), c.evaluate(time(500)))
    }

    @Test fun backwardsLifecycleClockDoesNotConsumeTerminalReceipt() {
        val c = controller(); c.beginWork()
        c.state(150, 2, terminal = "task")
        assertNull(c.evaluate(time(250)))
        c.state(300, 2, terminal = "task")
        assertEquals(finished(), c.evaluate(time(300)))
    }

    @Test fun timeoutDecisionIsStickyAgainstLateSuccessfulWork() {
        val c = controller(); c.onHandoff(time(100))
        assertEquals(missingWork(), c.evaluate(time(45_100)))
        c.state(45_200, 1, active = "late")
        c.state(45_300, 2, terminal = "late")
        assertEquals(missingWork(), c.evaluate(time(45_300)))
        assertNull(c.nextDeadlineNanos)
    }

    @Test fun invalidWorkIdentifiersFailWithoutKeepingPayload() {
        for (id in listOf("", "bad\nsecret", "x".repeat(257))) {
            val c = controller(); c.state(0, 1, active = id)
            assertEquals(CodexTaskVoiceCompletion.Decision.Failed("codex_task_voice_work_receipt_invalid"),
                c.evaluate(time(1)))
        }
    }

    @Test fun workReceiptCapacityIsBoundedAndDoesNotEvictUnfinishedTurns() {
        val c = controller(); c.onHandoff(time(0))
        repeat(256) { c.state(it.toLong() + 1, it.toLong(), active = "task-$it") }
        c.state(300, 256, active = "overflow")
        assertEquals(CodexTaskVoiceCompletion.Decision.Failed("codex_task_voice_receipt_capacity"),
            c.evaluate(time(301)))
    }

    @Test fun configuredLifecycleTimeoutsArePositiveAndHaveNoSpeechTimeout() {
        listOf(
            { CodexTaskVoiceCompletion.Config(noHandoffTimeoutNanos = 0) },
            { CodexTaskVoiceCompletion.Config(admissionTimeoutNanos = -1) },
        ).forEach { assertTrue(runCatching(it).exceptionOrNull() is IllegalArgumentException) }
        val c = CodexTaskVoiceCompletion(origin, CodexTaskVoiceCompletion.Config(
            noHandoffTimeoutNanos = TimeUnit.SECONDS.toNanos(2),
            admissionTimeoutNanos = TimeUnit.SECONDS.toNanos(3)))
        assertEquals(time(2_000), c.nextDeadlineNanos)
        c.onHandoff(time(100))
        assertEquals(time(3_100), c.nextDeadlineNanos)
        assertFalse(controller().hasHandoff)
    }

    @Test fun lifecycleDeadlinesSaturateWithoutOverflow() {
        val c = CodexTaskVoiceCompletion(Long.MAX_VALUE - 1)
        assertEquals(Long.MAX_VALUE, c.nextDeadlineNanos)
        assertNull(c.evaluate(Long.MAX_VALUE - 1))
        assertEquals(CodexTaskVoiceCompletion.Decision.NoHandoffTimeout, c.evaluate(Long.MAX_VALUE))
    }
}
