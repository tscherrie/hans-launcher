package ai.hans.standard.voice.realtime

import ai.hans.standard.integration.*
import ai.hans.standard.voice.realtime.TaskVoiceLifecycleDiagnostics.Event
import ai.hans.standard.voice.realtime.TaskVoiceLifecycleDiagnostics.Reason
import java.util.PriorityQueue
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CodexTaskVoiceSessionTest {
    @Test fun typedShortTaskStatusKeepsIdentityAndConfirmedWorkScopeInEitherBindingOrder() {
        for (flatStartsFirst in listOf(true, false)) Fixture().use { h ->
            h.activate(); h.beginWork()
            h.native.onTranscript("assistant", "Einen Moment, ich schau kurz nach.", true); h.drain()
            assertNull(h.revisions.last().dictationWorkScope)
            val source = CodexVoiceWorkScope("main", "turn")
            if (flatStartsFirst) { h.native.onTranscript("assistant", "Guten Morgen, ", false); h.drain() }
            h.native.onWorkBound(source); h.drain()
            if (!flatStartsFirst) { h.native.onTranscript("assistant", "Guten Morgen, ", false); h.drain() }
            val partial = h.revisions.last()
            h.native.onTranscript("assistant", "Guten Morgen, Jeremias! Bin bereit.", true); h.drain()
            val final = h.revisions.last()
            assertEquals(partial.displayId, final.displayId)
            assertEquals(source, final.dictationWorkScope)
            assertTrue(final.isFinal)
            // Equal speech stays a distinct utterance, not text-deduplicated. During the same
            // short-task work scope it is companion status, not claimed as an item rendition.
            h.native.onTranscript("assistant", final.text, true); h.drain()
            assertNotEquals(final.displayId, h.revisions.last().displayId)
            assertEquals(source, h.revisions.last().dictationWorkScope)
        }
    }

    @Test fun aCompletedVoiceOnlyUtteranceIsNeverRetargetedByLaterWorkBinding() = Fixture().use { h ->
        h.activate()
        h.native.onTranscript("assistant", "Voice only.", true); h.drain()
        val before = h.revisions.single()
        h.native.onWorkBound(CodexVoiceWorkScope("main", "turn")); h.drain()
        assertEquals(listOf(before), h.revisions)
        assertNull(before.dictationWorkScope)
        h.native.onTranscript("user", "A new request", true); h.drain()
        h.native.onTranscript("assistant", "Voice only.", true); h.drain()
        assertEquals(CodexVoiceWorkScope("main", "turn"), h.revisions.last().dictationWorkScope)
        assertNotEquals(before.displayId, h.revisions.last().displayId)
    }

    @Test fun confirmedShortTaskEpisodeSurvivesLateDeltaOrFinalOnlyUserInputAndBargeIn() {
        for (partialFirst in listOf(true, false)) Fixture().use { h ->
            h.activate()
            if (partialFirst) { h.native.onTranscript("user", "Hello", false); h.drain() }
            val scope = CodexVoiceWorkScope("main", "turn")
            h.native.onWorkBound(scope); h.drain()
            h.native.onTranscript("user", "Hello Hans.", true); h.drain()
            h.native.onTranscript("assistant", "Hello Jeremias.", true); h.drain()
            assertEquals(scope, h.revisions.last().dictationWorkScope)
            h.native.onTranscript("user", "Another request", false); h.drain()
            h.native.onTranscript("assistant", "A companion status during the same episode.", true); h.drain()
            assertEquals(scope, h.revisions.last().dictationWorkScope)
            h.native.onWorkBound(scope); h.drain() // A real same-turn steering handoff is idempotent.
            h.native.onTranscript("assistant", "Working on the revised request.", true); h.drain()
            assertEquals(scope, h.revisions.last().dictationWorkScope)
            val nextScope = CodexVoiceWorkScope("main", "next-turn")
            h.native.onWorkBound(nextScope); h.drain()
            h.native.onTranscript("assistant", "The next confirmed work episode.", true); h.drain()
            assertEquals(nextScope, h.revisions.last().dictationWorkScope)
        }
    }

    @Test fun phoneSpeechNeverReceivesTheShortTaskDisplayScope() = Fixture(entryPoint = LiveVoiceEntryPoint.PHONE).use { h ->
        h.activate()
        h.native.onWorkBound(CodexVoiceWorkScope("main", "turn")); h.drain()
        h.native.onTranscript("assistant", "Hello Jeremias.", true); h.drain()
        assertNull(h.revisions.last().dictationWorkScope)
    }

    @Test fun dictationFirstInputFeedbackIgnoresWhitespaceAndAssistantOutputThenClearsOnUserText() = Fixture().use { h ->
        h.start()
        assertTrue(h.session.snapshot.awaitingFirstUserTranscript)
        h.native.onStarted(); h.media.listener.onOpen(); h.media.listener.onInputCaptureStarted(); h.drain()
        h.native.onTranscript("user", " \t", false); h.drain()
        assertTrue(h.session.snapshot.awaitingFirstUserTranscript)
        h.native.onTranscript("user", " \n", true); h.drain()
        assertTrue(h.session.snapshot.awaitingFirstUserTranscript)
        h.native.onTranscript("assistant", "I am here.", true); h.drain()
        assertTrue(h.session.snapshot.awaitingFirstUserTranscript)
        h.native.onTranscript("user", "Hello", false); h.drain()
        assertFalse(h.session.snapshot.awaitingFirstUserTranscript)
        val partial = h.revisions.last()
        h.native.onTranscript("user", "Hello Hans.", true); h.drain()
        assertEquals(partial.displayId, h.revisions.last().displayId)
        assertFalse(h.session.snapshot.awaitingFirstUserTranscript)
    }

    @Test fun phoneAndTerminalDictationNeverShowFirstInputFeedback() {
        Fixture(entryPoint = LiveVoiceEntryPoint.PHONE).use { h ->
            h.activate(); assertFalse(h.session.snapshot.awaitingFirstUserTranscript)
        }
        Fixture().use { h ->
            h.activate(); assertTrue(h.session.snapshot.awaitingFirstUserTranscript)
            h.session.stop(); h.drain()
            assertEquals(LiveVoicePhase.STOPPED, h.session.snapshot.phase)
            assertFalse(h.session.snapshot.awaitingFirstUserTranscript)
        }
    }

    @Test fun unconfirmedDictationCloseRemovesFirstInputFeedbackWithoutClaimingRelease() = Fixture().use { h ->
        h.activate(); assertTrue(h.session.snapshot.awaitingFirstUserTranscript)
        h.media.confirmClose = false
        h.session.stop(); h.drain()
        assertEquals(LiveVoicePhase.CONFIGURING, h.session.snapshot.phase)
        assertNotNull(h.session.snapshot.lastFailureCode)
        assertFalse(h.session.snapshot.awaitingFirstUserTranscript)
    }
    @Test fun flatAndCanonicalFinalsRenderEachVoiceMessageOnlyOnceInEitherOrder() {
        for (entryPoint in LiveVoiceEntryPoint.entries) {
            for (role in listOf("user", "assistant")) {
                for (canonicalFirst in listOf(true, false)) Fixture(entryPoint = entryPoint).use { h ->
                    h.activate()
                    h.native.onItemStarted("segment", role)
                    h.native.onTranscript(role, "Hello", false)
                    if (canonicalFirst) h.native.onItemCompleted("segment", role, "Hello.")
                    h.native.onTranscript(role, "Hello.", true)
                    if (!canonicalFirst) h.native.onItemCompleted("segment", role, "Hello.")
                    h.drain()
                    assertEquals("$entryPoint / $role / canonicalFirst=$canonicalFirst",
                        listOf("$role:Hello:false", "$role:Hello.:true"), h.transcripts)
                }
            }
        }
    }

    @Test fun delayedCanonicalCompletionCannotFinalizeOrClearTheNextFlatPartial() {
        for (entryPoint in LiveVoiceEntryPoint.entries) {
            for (role in listOf("user", "assistant")) Fixture(entryPoint = entryPoint).use { h ->
                h.activate()
                h.native.onItemStarted("previous", role)
                h.native.onTranscript(role, "First.", true)
                h.native.onTranscript(role, "Second ", false)
                h.native.onItemCompleted("previous", role, "First.")
                h.native.onTranscript(role, "message", false)
                h.native.onTranscript(role, "Second message.", true)
                h.drain()
                assertEquals("$entryPoint / $role", listOf("$role:First.:true",
                    "$role:Second :false", "$role:Second message:false", "$role:Second message.:true"),
                    h.transcripts)
            }
        }
    }

    @Test fun canonicalAssistantStartCannotClearPartialOrNotifyTheSameReplyAgain() {
        for (entryPoint in LiveVoiceEntryPoint.entries) Fixture(entryPoint = entryPoint).use { h ->
            h.activate()
            h.native.onTranscript("assistant", "Already ", false)
            h.native.onItemStarted("late-canonical-start", "assistant")
            h.native.onTranscript("assistant", "working", false)
            h.native.onTranscript("assistant", "Already working.", true)
            h.native.onItemCompleted("late-canonical-start", "assistant", "Already working.")
            h.drain()
            assertEquals(entryPoint.name, listOf("assistant:Already :false",
                "assistant:Already working:false", "assistant:Already working.:true"), h.transcripts)
            assertEquals(entryPoint.name, 1, h.responses.size)
        }
    }

    @Test fun canonicalHistorySplitsCannotSplitOneFlatVoiceMessage() {
        for (entryPoint in LiveVoiceEntryPoint.entries) {
            for (role in listOf("user", "assistant")) Fixture(entryPoint = entryPoint).use { h ->
                h.activate()
                h.native.onItemStarted("part-one", role)
                h.native.onTranscript(role, "One ", false)
                h.native.onItemCompleted("part-one", role, "One ")
                h.native.onItemStarted("part-two", role)
                h.native.onTranscript(role, "message", false)
                h.native.onItemCompleted("part-two", role, "message")
                h.native.onTranscript(role, "One message.", true)
                h.drain()
                assertEquals("$entryPoint / $role", listOf("$role:One :false",
                    "$role:One message:false", "$role:One message.:true"), h.transcripts)
                if (role == "assistant") assertEquals(1, h.responses.size)
            }
        }
    }

    @Test fun genuineIdenticalUtterancesRemainTwoMessagesRatherThanTextDeduplicated() {
        for (entryPoint in LiveVoiceEntryPoint.entries) {
            for (role in listOf("user", "assistant")) Fixture(entryPoint = entryPoint).use { h ->
                h.activate()
                repeat(2) { index ->
                    h.native.onItemStarted("utterance-$index", role)
                    h.native.onTranscript(role, "Again", false)
                    h.native.onItemCompleted("utterance-$index", role, "Again.")
                    h.native.onTranscript(role, "Again.", true)
                }
                h.drain()
                assertEquals("$entryPoint / $role", listOf("$role:Again:false", "$role:Again.:true",
                    "$role:Again:false", "$role:Again.:true"), h.transcripts)
                if (role == "assistant") assertEquals(2, h.responses.size)
            }
        }
    }

    @Test fun correctedFlatFinalWinsOverDifferentCanonicalHistoryText() {
        for (entryPoint in LiveVoiceEntryPoint.entries) {
            for (role in listOf("user", "assistant")) {
                for (canonicalFirst in listOf(true, false)) Fixture(entryPoint = entryPoint).use { h ->
                    h.activate()
                    h.native.onItemStarted("revised", role)
                    h.native.onTranscript(role, "Draft wording", false)
                    if (canonicalFirst) h.native.onItemCompleted("revised", role, "Draft wording")
                    h.native.onTranscript(role, "Corrected final wording.", true)
                    if (!canonicalFirst) h.native.onItemCompleted("revised", role, "Draft wording")
                    h.drain()
                    assertEquals("$entryPoint / $role / canonicalFirst=$canonicalFirst",
                        listOf("$role:Draft wording:false", "$role:Corrected final wording.:true"), h.transcripts)
                }
            }
        }
    }

    @Test fun lateCanonicalSegmentsCannotResurrectAnEmptyFlatFinal() {
        for (role in listOf("user", "assistant")) Fixture().use { h ->
            h.activate()
            h.native.onItemStarted("empty-final", role)
            h.native.onTranscript(role, "Discarded", false)
            h.native.onTranscript(role, "", true)
            h.native.onItemCompleted("empty-final", role, "Discarded")
            h.drain()
            assertEquals(role, listOf("$role:Discarded:false", "$role::true"), h.transcripts)
        }
    }

    @Test fun canonicalDisplayDuplicatesDoNotChangeTaskCompletionOrSubmitAnotherTask() = Fixture().use { h ->
        h.activate(); h.beginWork(); h.completeWork()
        h.native.onTranscript("assistant", "Done.", true)
        h.native.onItemStarted("answer", "assistant")
        h.native.onItemCompleted("answer", "assistant", "Done.")
        h.drain(); h.tick(1_500)
        assertEquals(listOf("assistant:Done.:true"), h.transcripts)
        assertEquals(1, h.media.inputFinishes)
        assertEquals(1, h.starts)
        assertEquals(1, h.stops)
        assertEquals(LiveVoicePhase.STOPPED, h.session.snapshot.phase)
    }

    @Test fun dictationNeedsFirstPhysicalFrameAndNeverUsesPhoneGreeting() = Fixture().use { h ->
        h.start(); h.native.onStarted(); h.media.listener.onOpen(); h.drain()
        assertEquals(0, h.media.started)
        assertEquals(LiveVoiceEntryPoint.DICTATION, h.session.snapshot.entryPoint)
        assertTrue(h.media.setup.instructions.contains("Do not greet"))
        assertTrue(h.media.monitoring)
        h.media.listener.onInputCaptureStarted(); h.drain()
        assertEquals(1, h.media.started)
        assertEquals(LiveVoicePhase.LISTENING, h.session.snapshot.phase)
    }

    @Test fun earlyPhysicalInputCanBeShownBeforeNativeReadiness() = Fixture().use { h ->
        h.start(); h.media.listener.onInputCaptureStarted(); h.drain()
        assertEquals(LiveVoicePhase.LISTENING, h.session.snapshot.phase)
        assertEquals(0, h.media.started)
        h.native.onStarted(); h.media.listener.onOpen(); h.drain()
        assertEquals(1, h.media.started)
    }

    @Test fun noHandoffSixtySecondTimerClosesWithoutAnyFurtherCallbacksOrTextSubmission() = Fixture().use { h ->
        h.activate()
        h.tick(59_999)
        assertEquals(0, h.stops)
        h.tick(60_000)
        assertEquals(LiveVoicePhase.STOPPED, h.session.snapshot.phase)
        assertEquals(1, h.stops)
        assertTrue(h.media.closed)
        assertEquals(0, h.media.inputFinishes)
        assertEquals(1, h.starts)
    }

    @Test fun knownRunningTaskHasNoSixtySecondCutoff() = Fixture().use { h ->
        h.activate()
        h.native.onWorkState(CodexTaskVoiceWorkState(1, "turn"))
        h.native.onHandoff(); h.drain(); h.tick(61_000)
        assertEquals(0, h.stops)
        assertFalse(h.media.closed)
        assertEquals(0, h.media.inputFinishes)
    }

    @Test fun workTerminalImmediatelyClosesInputAndTimerEndsTailWithoutAnyAudioOrText() = Fixture().use { h ->
        h.activate(); h.beginWork(); h.completeWork()
        assertEquals(1, h.media.inputFinishes)
        assertTrue(h.media.inputFinished)
        assertTrue(h.media.muted)
        assertTrue(h.session.snapshot.inputMuted)
        assertFalse(h.session.setInputMuted(false))
        assertFalse(h.session.setInputMuted(true))
        assertEquals(0, h.stops)
        assertFalse(h.media.closed)
        // Advance the actual scheduled Runnable: no PCM, transcript, input or work callback.
        h.tick(1_499)
        assertEquals(0, h.stops)
        h.tick(1_500)
        assertEquals(LiveVoicePhase.STOPPED, h.session.snapshot.phase)
        assertEquals(1, h.stops)
        assertTrue(h.media.closed)
        assertEquals(1, h.media.closeReceipts)
        assertFalse(h.session.setInputMuted(false))
        val records = requireNotNull(TaskVoiceLifecycleDiagnostics.snapshot()).records
        val completion = records.single { it.type == Event.COMPLETION_MATCHED }
        val inputClosed = records.single { it.details.reason == Reason.INPUT_CLOSED }
        val tailStarted = records.single { it.type == Event.TAIL_STARTED }
        val tailClosed = records.single { it.type == Event.TAIL_CLOSED }
        val sessionClosed = records.single { it.type == Event.SESSION_CLOSED }
        assertEquals(CodexTaskVoiceWorkOutcome.COMPLETED, completion.details.workOutcome)
        assertTrue(completion.sequence < inputClosed.sequence)
        assertTrue(inputClosed.sequence < tailStarted.sequence)
        assertTrue(tailStarted.sequence < tailClosed.sequence)
        assertTrue(tailClosed.sequence < sessionClosed.sequence)
        assertEquals(Reason.TAIL_NO_OUTPUT, tailClosed.details.reason)
        assertEquals(true, sessionClosed.details.mediaClosed)
        assertEquals(true, sessionClosed.details.nativeClosed)
    }

    @Test fun transportWithoutConfirmedInputFinishClosesImmediatelyInsteadOfKeepingMicOpen() = Fixture().use { h ->
        h.activate(); h.media.canFinishInput = false
        h.beginWork(); h.completeWork()
        assertEquals(1, h.media.inputFinishes)
        assertTrue(h.media.closed)
        assertTrue(h.media.muted)
        assertEquals(1, h.stops)
        assertEquals(LiveVoicePhase.STOPPED, h.session.snapshot.phase)
        assertFalse(h.session.setInputMuted(false))
        val records = requireNotNull(TaskVoiceLifecycleDiagnostics.snapshot()).records
        assertFalse(records.any { it.type == Event.TAIL_STARTED })
        assertEquals(Reason.INPUT_CLOSE_UNCONFIRMED,
            records.single { it.type == Event.SESSION_CLOSED }.details.reason)
    }

    @Test fun outputMayRunBrieflyThenScheduledGapClosesWithoutSilentPcm() = Fixture().use { h ->
        h.activate(); h.beginWork()
        h.native.onItemStarted("answer", "assistant"); h.drain()
        h.completeWork()
        h.audio(LiveVoiceAudioDirection.OUTPUT, true, 1_200)
        h.audio(LiveVoiceAudioDirection.OUTPUT, true, 1_900)
        h.audio(LiveVoiceAudioDirection.OUTPUT, true, 2_500)
        assertEquals(1, h.media.inputFinishes)
        assertFalse(h.session.setInputMuted(false))
        h.tick(3_249)
        assertEquals(0, h.stops)
        h.tick(3_250)
        assertEquals(1, h.stops)
        assertTrue(h.media.closed)
        assertEquals(Reason.TAIL_OUTPUT_QUIET_OR_GAP, requireNotNull(
            TaskVoiceLifecycleDiagnostics.snapshot()).records.single { it.type == Event.TAIL_CLOSED }.details.reason)
    }

    @Test fun answerAndTerminalReceiptOrderNeverControlsCompletion() {
        for (answerFirst in listOf(true, false)) Fixture().use { h ->
            h.activate(); h.beginWork()
            if (answerFirst) { h.native.onItemStarted("answer", "assistant"); h.drain() }
            h.completeWork()
            if (!answerFirst) { h.native.onItemStarted("answer", "assistant"); h.drain() }
            h.native.onTranscript("assistant", "Done.", true); h.drain()
            assertEquals(1, h.media.inputFinishes)
            h.tick(1_500)
            assertEquals(1, h.stops)
            assertEquals(LiveVoicePhase.STOPPED, h.session.snapshot.phase)
        }
    }

    @Test fun progressBeforeTerminalAndLaterDuplicateTextCannotManufactureOrExtendOutputTail() = Fixture().use { h ->
        h.activate(); h.beginWork()
        h.native.onItemStarted("progress", "assistant"); h.drain()
        h.audio(LiveVoiceAudioDirection.OUTPUT, true, 50)
        h.audio(LiveVoiceAudioDirection.OUTPUT, false, 90)
        h.tick(100); h.completeWork()
        h.tick(1_400)
        h.native.onItemStarted("progress", "assistant")
        h.native.onTranscript("assistant", "working...", false)
        h.native.onTranscript("assistant", "working...", true)
        h.drain()
        h.audio(LiveVoiceAudioDirection.OUTPUT, true, 1_500, observedMillis = 50)
        h.tick(1_599)
        assertEquals(0, h.stops)
        h.tick(1_600)
        assertEquals(1, h.stops)
        assertEquals(1, h.media.inputFinishes)
    }

    @Test fun duplicatedStaleFutureAndUnreliableOutputCannotExtendLastValidOutputDeadline() = Fixture().use { h ->
        h.activate(); h.beginWork(); h.completeWork()
        h.audio(LiveVoiceAudioDirection.OUTPUT, true, 1_200)
        h.audio(LiveVoiceAudioDirection.OUTPUT, true, 1_700, observedMillis = 1_200)
        h.audio(LiveVoiceAudioDirection.OUTPUT, true, 1_750, observedMillis = 1_000)
        h.audio(LiveVoiceAudioDirection.OUTPUT, true, 1_800, observedMillis = 5_000)
        h.audio(LiveVoiceAudioDirection.OUTPUT, true, 1_850, reliable = false)
        h.tick(1_949)
        assertEquals(0, h.stops)
        h.tick(1_950)
        assertEquals(1, h.stops)
    }

    @Test fun continuousOutputHasHardTwentySecondLimitAndCannotReopenAfterward() = Fixture().use { h ->
        h.activate(); h.beginWork(); h.completeWork()
        for (millis in 500L..19_500L step 500L) h.audio(LiveVoiceAudioDirection.OUTPUT, true, millis)
        h.tick(19_999)
        assertEquals(0, h.stops)
        h.tick(20_000)
        assertEquals(1, h.stops)
        h.audio(LiveVoiceAudioDirection.OUTPUT, true, 20_001)
        h.native.onTranscript("assistant", "late final", true)
        h.native.onHandoff(); h.drain()
        assertEquals(1, h.stops)
        assertEquals(1, h.media.inputFinishes)
        assertEquals(LiveVoicePhase.STOPPED, h.session.snapshot.phase)
    }

    @Test fun lateUserDeltaFinalHandoffAndWorkCannotRevokeCompletedTasksInputCutoff() = Fixture().use { h ->
        h.activate(); h.beginWork(); h.completeWork()
        h.tick(500)
        h.native.onTranscript("user", "delayed original words", false)
        h.native.onTranscript("user", "delayed original words", true)
        h.native.onHandoff()
        h.native.onWorkState(CodexTaskVoiceWorkState(3, "late-turn"))
        h.native.onWorkState(CodexTaskVoiceWorkState(4,
            terminal = CodexTaskVoiceTerminal("late-turn", CodexTaskVoiceWorkOutcome.COMPLETED)))
        h.native.onStarted(); h.media.listener.onOpen(); h.media.listener.onInputCaptureStarted(); h.drain()
        assertEquals(1, h.media.started)
        assertEquals(1, h.media.inputFinishes)
        assertTrue(h.session.snapshot.inputMuted)
        assertFalse(h.session.setInputMuted(false))
        h.tick(1_500)
        assertEquals(1, h.stops)
    }

    @Test fun unrelatedTerminalAndNativeOnlyAnswerDoNotCloseObservedTask() = Fixture().use { h ->
        h.activate(); h.beginWork()
        h.native.onWorkState(CodexTaskVoiceWorkState(2, "turn",
            terminal = CodexTaskVoiceTerminal("unrelated", CodexTaskVoiceWorkOutcome.COMPLETED)))
        h.native.onItemStarted("answer", "assistant")
        h.native.onTranscript("assistant", "Done.", true); h.drain()
        h.tick(5_000)
        assertEquals(0, h.media.inputFinishes)
        assertEquals(0, h.stops)
        h.completeWork(revision = 3)
        assertEquals(1, h.media.inputFinishes)
        h.tick(6_500)
        assertEquals(1, h.stops)
    }

    @Test fun everyObservedTurnAndPendingDispatchMustSettleBeforeInputCloses() = Fixture().use { h ->
        h.activate(); h.beginWork()
        h.native.onHandoff()
        h.native.onWorkState(CodexTaskVoiceWorkState(2, "second"))
        h.native.onWorkState(CodexTaskVoiceWorkState(3, "second", terminal =
            CodexTaskVoiceTerminal("turn", CodexTaskVoiceWorkOutcome.COMPLETED)))
        h.drain(); h.tick(1_000)
        assertEquals(0, h.media.inputFinishes)
        h.native.onWorkState(CodexTaskVoiceWorkState(4, pendingDispatch = true, terminal =
            CodexTaskVoiceTerminal("second", CodexTaskVoiceWorkOutcome.COMPLETED)))
        h.drain(); h.tick(2_000)
        assertEquals(0, h.media.inputFinishes)
        h.native.onWorkState(CodexTaskVoiceWorkState(5)); h.drain()
        assertEquals(1, h.media.inputFinishes)
        h.tick(3_500)
        assertEquals(1, h.stops)
    }

    @Test fun priorTaskTerminalWithoutVoiceHandoffDoesNotCountAsCompletedWork() = Fixture().use { h ->
        h.activate()
        h.native.onWorkState(CodexTaskVoiceWorkState(1, "prior"))
        h.native.onWorkState(CodexTaskVoiceWorkState(2,
            terminal = CodexTaskVoiceTerminal("prior", CodexTaskVoiceWorkOutcome.COMPLETED)))
        h.drain(); h.tick(5_000)
        assertEquals(0, h.media.inputFinishes)
        assertEquals(0, h.stops)
    }

    @Test fun missingWorkReceiptStillFailsVisiblyWithoutCallingItCompleted() = Fixture().use { h ->
        h.activate(); h.native.onHandoff(); h.drain()
        h.tick(44_999)
        assertEquals(0, h.stops)
        h.tick(45_000)
        assertEquals(LiveVoicePhase.FAILED, h.session.snapshot.phase)
        assertEquals("codex_task_voice_work_receipt_timeout", h.session.snapshot.lastFailureCode)
        assertEquals(0, h.media.inputFinishes)
        assertEquals(1, h.stops)
    }

    @Test fun everyMatchedTerminalOutcomeEndsVoiceWithoutRewritingItsOutcome() {
        for (outcome in CodexTaskVoiceWorkOutcome.values()) Fixture().use { h ->
            h.activate(); h.beginWork(); h.completeWork(outcome = outcome)
            assertEquals(outcome, requireNotNull(TaskVoiceLifecycleDiagnostics.snapshot()).records
                .single { it.type == Event.COMPLETION_MATCHED }.details.workOutcome)
            h.tick(1_500)
            assertEquals(LiveVoicePhase.STOPPED, h.session.snapshot.phase)
            assertEquals(1, h.stops)
        }
    }

    @Test fun telephoneKeepsListeningAfterWorkTerminalAndDoesNotUseTaskVoiceTail() =
        Fixture(entryPoint = LiveVoiceEntryPoint.PHONE).use { h ->
            h.activate(); h.beginWork(); h.completeWork()
            h.tick(61_000)
            assertEquals(0, h.media.inputFinishes)
            assertEquals(0, h.stops)
            assertFalse(h.media.closed)
            assertTrue(h.session.setInputMuted(true))
            assertTrue(h.session.setInputMuted(false))
        }

    @Test fun expiredActivationCannotConnectAfterDecidingToClose() = Fixture(activatedAgoMillis = 61_000).use { h ->
        h.session.start(); h.drain()
        assertEquals(0, h.starts)
        assertTrue(h.media.closed)
        assertEquals(LiveVoicePhase.STOPPED, h.session.snapshot.phase)
    }

    private class Fixture(activatedAgoMillis: Long = 0,
        entryPoint: LiveVoiceEntryPoint = LiveVoiceEntryPoint.DICTATION) : AutoCloseable {
        val scheduler = VirtualScheduler()
        lateinit var native: CodexRealtimeCallbacks
        lateinit var media: Media
        var starts = 0; var stops = 0
        val transcripts = mutableListOf<String>()
        val revisions = mutableListOf<LiveVoiceTranscriptRevision>()
        val responses = mutableListOf<LiveVoiceResponseReady>()
        val gateway = object : CodexRealtimeGateway {
            override fun start(offerSdp: String, prompt: String, voice: String?, callbacks: CodexRealtimeCallbacks): CodexRealtimeCall {
                native = callbacks; starts++
                callbacks.onRemoteSdp("v=answer")
                var stopped = false
                return CodexRealtimeCall {
                    if (!stopped) { stopped = true; stops++; callbacks.onCloseConfirmed(); callbacks.onClosed() }
                }
            }
            override fun interruptCurrentTurn(): Boolean = error("must not interrupt work")
        }
        val session = CodexLiveVoiceSession(gateway, { Media(it).also { media = it } },
            LiveVoiceInstructionsProvider { "Native Hans instructions" }, object : LiveVoiceObserver {
                override fun onUserTranscript(text: String, isFinal: Boolean) { transcripts += "user:$text:$isFinal" }
                override fun onHansTranscript(text: String, isFinal: Boolean) { transcripts += "assistant:$text:$isFinal" }
                override fun onTranscriptRevision(event: LiveVoiceTranscriptRevision) {
                    revisions += event
                    super<LiveVoiceObserver>.onTranscriptRevision(event)
                }
                override fun onHansResponseReady(event: LiveVoiceResponseReady) { responses += event }
            }, scheduler = scheduler,
            nanoTime = { scheduler.nowNanos }, entryPoint = entryPoint,
            activatedAtNanos = scheduler.nowNanos - TimeUnit.MILLISECONDS.toNanos(activatedAgoMillis))
        fun start() { session.start(); drain() }
        fun activate() {
            start(); native.onStarted(); media.listener.onOpen(); media.listener.onInputCaptureStarted(); drain()
        }
        fun beginWork() {
            native.onHandoff(); native.onWorkState(CodexTaskVoiceWorkState(1, "turn")); drain()
        }
        fun completeWork(revision: Long = 2, outcome: CodexTaskVoiceWorkOutcome = CodexTaskVoiceWorkOutcome.COMPLETED) {
            native.onWorkState(CodexTaskVoiceWorkState(revision,
                terminal = CodexTaskVoiceTerminal("turn", outcome))); drain()
        }
        fun drain() = scheduler.runCurrent()
        fun tick(ms: Long) = scheduler.advanceTo(ms)
        fun audio(direction: LiveVoiceAudioDirection, active: Boolean, ms: Long,
            observedMillis: Long = ms, reliable: Boolean = true) {
            tick(ms)
            media.listener.onAudioActivity(LiveVoiceAudioActivity(direction, active,
                VirtualScheduler.ORIGIN + TimeUnit.MILLISECONDS.toNanos(observedMillis), reliable))
            drain()
        }
        override fun close() { session.close(); drain(); scheduler.shutdownNow() }
    }

    private class Media(val provider: LiveSessionProvider) : LiveVoiceTransport {
        lateinit var listener: LiveVoiceTransport.Listener
        lateinit var setup: LiveSessionSetup
        var started = 0; var closed = false; var monitoring = false
        var muted = false; var inputFinished = false; var inputFinishes = 0
        var closeReceipts = 0; var canFinishInput = true; var confirmClose = true
        override fun connect(setup: LiveSessionSetup, listener: LiveVoiceTransport.Listener) {
            this.setup = setup; this.listener = listener
            provider.create(setup, "v=offer", object : LiveSessionProvider.Callback {
                override fun onCreated(answer: LiveSessionAnswer) = Unit
                override fun onFailure(failure: LiveVoiceFailure) = listener.onClosed(failure)
            })
        }
        override fun connect(credential: RealtimeEphemeralCredential, listener: LiveVoiceTransport.Listener) = error("API key")
        override fun confirmSessionStarted(): Boolean { started++; return true }
        override fun sendUtf8(event: String): Boolean = error("no second dispatch")
        override fun setInputAudioEnabled(enabled: Boolean) = !enabled || !inputFinished
        override fun setUserInputMuted(muted: Boolean): Boolean {
            if (!muted && inputFinished) return false
            this.muted = muted
            return true
        }
        override fun finishInputForOutputTail(): Boolean {
            inputFinishes++
            if (!canFinishInput) return false
            inputFinished = true; muted = true
            return true
        }
        override fun clearOutputAudio() = false
        override fun setAudioActivityMonitoringEnabled(enabled: Boolean): Boolean { monitoring = enabled; return true }
        override fun close() { closed = true; muted = true }
        override fun closeAndAwait(timeoutMillis: Long): Boolean { closeReceipts++; close(); return confirmClose }
    }

    /** Runs actual one-shot callbacks, without wall-clock sleeps or synthetic media ticks. */
    private class VirtualScheduler : AbstractExecutorService(), ScheduledExecutorService {
        var nowNanos = ORIGIN
            private set
        private var nextOrder = 0L
        private var stopped = false
        private val queue = PriorityQueue<Task<*>>(compareBy({ it.dueNanos }, { it.order }))
        fun runCurrent() = advanceTo(TimeUnit.NANOSECONDS.toMillis(nowNanos - ORIGIN))
        fun advanceTo(millis: Long) {
            val target = ORIGIN + TimeUnit.MILLISECONDS.toNanos(millis)
            require(target >= nowNanos)
            var executed = 0
            while (queue.peek()?.let { it.dueNanos <= target } == true) {
                check(executed++ < 100_000) { "Session scheduler did not settle" }
                val task = queue.remove()
                nowNanos = task.dueNanos
                if (!task.isCancelled) {
                    task.run()
                    if (!task.isCancelled) task.get()
                }
            }
            nowNanos = target
        }
        override fun execute(command: Runnable) { schedule(command, 0, TimeUnit.NANOSECONDS) }
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
            schedule(Callable { command.run(); Unit }, delay, unit)
        override fun <V> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> {
            if (stopped) throw RejectedExecutionException("Session scheduler closed")
            return Task(nowNanos + unit.toNanos(delay.coerceAtLeast(0)), nextOrder++, callable).also(queue::add)
        }
        override fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit): ScheduledFuture<*> =
            throw UnsupportedOperationException("Task Voice must not poll")
        override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
            throw UnsupportedOperationException("Task Voice must not poll")
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true; queue.forEach { it.cancel(false) }; queue.clear()
            return mutableListOf()
        }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && queue.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
        private inner class Task<V>(val dueNanos: Long, val order: Long, callable: Callable<V>) :
            FutureTask<V>(callable), ScheduledFuture<V> {
            override fun getDelay(unit: TimeUnit) = unit.convert(dueNanos - nowNanos, TimeUnit.NANOSECONDS)
            override fun compareTo(other: Delayed) = getDelay(TimeUnit.NANOSECONDS).compareTo(other.getDelay(TimeUnit.NANOSECONDS))
        }
        companion object { val ORIGIN = TimeUnit.SECONDS.toNanos(100) }
    }
}
