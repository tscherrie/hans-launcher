package ai.hans.standard.voice.feedback

import ai.hans.standard.localization.TestResourceTextResolver

import ai.hans.standard.codex.AgentMessagePhase
import ai.hans.standard.codex.AppServerMethod
import ai.hans.standard.codex.CorrelatedResponse
import ai.hans.standard.codex.CodexSessionReducer
import ai.hans.standard.codex.DeliveredServerEvent
import ai.hans.standard.codex.DeliveryCursor
import ai.hans.standard.codex.ReasoningEffort
import ai.hans.standard.codex.RequestId
import ai.hans.standard.codex.ServerEvent
import ai.hans.standard.codex.StreamItem
import ai.hans.standard.codex.ThreadRuntimeStatus
import ai.hans.standard.codex.ThreadStatusSnapshot
import ai.hans.standard.codex.ThreadUiSnapshot
import ai.hans.standard.codex.ThreadResumeResult
import ai.hans.standard.codex.ThreadSummary
import ai.hans.standard.codex.TurnSnapshot
import ai.hans.standard.codex.TurnStatus
import ai.hans.standard.codex.TurnUiSnapshot
import ai.hans.standard.integration.ClientRuntimePhase
import ai.hans.standard.integration.ClientTerminalTurn
import ai.hans.standard.integration.ClientSessionPhase
import ai.hans.standard.integration.ClientTimelineItem
import ai.hans.standard.integration.ClientTimelineRole
import ai.hans.standard.integration.ClientTimelineStatus
import ai.hans.standard.integration.CodexClientSnapshot
import ai.hans.standard.integration.DispatchSelection
import ai.hans.standard.voice.realtime.LiveVoicePhase
import ai.hans.standard.voice.realtime.LiveVoiceResponseReady
import ai.hans.standard.voice.realtime.LiveVoiceSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseReadyFeedbackControllerTest {
    @Test
    fun firstDeltaPulsesOnceAcrossFurtherDeltasCommentaryAndFinal() {
        val fixture = Fixture()
        fixture.controller.acceptCodex(snapshot())
        val first = hans("one", "turn-one", "Ich schaue", 1)
        fixture.controller.acceptCodex(snapshot("turn-one", first))
        fixture.controller.acceptCodex(snapshot("turn-one", first))
        fixture.controller.acceptCodex(snapshot("turn-one", first.copy(text = "Ich schaue nach.", revision = 2)))
        fixture.controller.acceptCodex(
            snapshot("turn-one", first, hans("two", "turn-one", "Erledigt.", 2).copy(
                agentPhase = AgentMessagePhase.FINAL_ANSWER,
                complete = true,
                status = ClientTimelineStatus.COMPLETE,
            )),
        )
        assertEquals(1, fixture.pulses.get())
    }

    @Test
    fun emptyStartedItemDoesNotConsumeTheFirstActualText() {
        val fixture = Fixture()
        fixture.controller.acceptCodex(snapshot())
        val empty = hans("one", "turn-one", "", 1)
        fixture.controller.acceptCodex(snapshot("turn-one", empty))
        assertEquals(0, fixture.pulses.get())
        fixture.controller.acceptCodex(snapshot("turn-one", empty.copy(text = "Hallo", revision = 2)))
        assertEquals(1, fixture.pulses.get())
    }

    @Test
    fun finalOnlyAnswerAndNextTextOrDictationTurnEachPulseOnce() {
        val fixture = Fixture()
        fixture.controller.acceptCodex(snapshot())
        val first = snapshot("text-turn", hans("one", "text-turn", "Hallo.", 1)).withTurnStatus(TurnStatus.COMPLETED)
        fixture.controller.acceptCodex(first)
        fixture.controller.acceptCodex(first)
        fixture.controller.acceptCodex(snapshot("dictation-turn", hans("two", "dictation-turn", "Guten Morgen.", 2)))
        assertEquals(2, fixture.pulses.get())
    }

    @Test
    fun realReducerClearsCurrentTurnButCorrelatedFinalOnlyAnswerStillPulses() {
        val fixture = Fixture()
        val reducer = CodexSessionReducer()
        reducer.apply(CorrelatedResponse.Success(
            id = RequestId.Number(1),
            method = AppServerMethod.THREAD_RESUME,
            result = ThreadResumeResult(
                thread = ThreadSummary("thread-one", "Hans", "", 1, 1,
                    ThreadStatusSnapshot(ThreadRuntimeStatus.IDLE)),
                effectiveModel = "gpt-5.6-luna",
                effectiveEffort = ReasoningEffort.MAX,
                effectiveServiceTier = null,
                initialTurnReceipts = emptyList(),
                turnsBackwardsCursor = null,
                itemsBackwardsCursor = null,
            ),
        ))
        fixture.controller.acceptCodex(snapshot().copy(session = reducer.snapshot()))
        reducer.apply(DeliveredServerEvent(DeliveryCursor(1, 0), ServerEvent.TurnStarted(
            "thread-one", TurnSnapshot("one", TurnStatus.IN_PROGRESS, null, 1, null),
        )))
        reducer.apply(DeliveredServerEvent(DeliveryCursor(1, 1), ServerEvent.ItemCompleted(
            "thread-one", "one", StreamItem.AgentMessage("message", "Fertig.", AgentMessagePhase.FINAL_ANSWER), 2,
        )))
        reducer.apply(DeliveredServerEvent(DeliveryCursor(1, 2), ServerEvent.TurnCompleted(
            "thread-one", TurnSnapshot("one", TurnStatus.COMPLETED, null, 1, 2),
        )))
        val actual = reducer.snapshot()
        assertNull(actual.threads.single().currentTurn)
        val message = actual.threads.single().messages.single()
        val completed = snapshot(null, hans(message.itemId, message.turnId, message.text, 1).copy(
            complete = message.complete,
            agentPhase = message.phase,
            status = ClientTimelineStatus.COMPLETE,
        )).copy(
            session = actual,
            terminalTurns = listOf(ClientTerminalTurn("thread-one", "one", TurnStatus.COMPLETED)),
        )
        fixture.controller.acceptCodex(completed)
        fixture.controller.acceptCodex(completed)
        assertEquals(1, fixture.pulses.get())
        // Loading exactly this terminal state at process startup is still a silent baseline.
        val restored = Fixture()
        restored.controller.acceptCodex(completed)
        restored.controller.acceptCodex(completed)
        assertEquals(0, restored.pulses.get())
    }

    @Test
    fun aFinishedWebLinkIsVisibleContentAndTriggersOnlyOneReadyPulse() {
        val fixture = Fixture()
        fixture.controller.acceptCodex(snapshot())
        val url = hans("one", "one", "https://example.test/a", 1).copy(complete = true)
        fixture.controller.acceptCodex(snapshot("one", url))
        // Bare web addresses now become a visible, tappable site name, not an empty message.
        assertEquals(1, fixture.pulses.get())
        fixture.controller.acceptCodex(snapshot("one", url.copy(text = "Hier ist die Antwort.", revision = 2)))
        assertEquals(1, fixture.pulses.get())
    }

    @Test
    fun recoveredHistoryAndGenerationOrThreadBoundariesAreSilent() {
        val fixture = Fixture()
        val old = snapshot("old-turn", hans("old", "old-turn", "Alte Antwort", 10))
        fixture.controller.acceptCodex(old.copy(sessionPhase = ClientSessionPhase.RECOVERING_THREAD))
        fixture.controller.acceptCodex(old)
        fixture.controller.acceptCodex(old.copy(generation = 2))
        fixture.controller.acceptCodex(old.copy(generation = 1)) // out-of-order retired runtime
        fixture.controller.acceptCodex(old.withThread("second-thread").copy(generation = 2))
        assertEquals(0, fixture.pulses.get())
        fixture.controller.acceptCodex(
            snapshot("new-turn", hans("new", "new-turn", "Neue Antwort", 11))
                .withThread("second-thread").copy(generation = 2),
        )
        assertEquals(1, fixture.pulses.get())
    }

    @Test
    fun sameGenerationRehydrationIsNotMistakenForANewAnswer() {
        val fixture = Fixture()
        fixture.controller.acceptCodex(snapshot())
        val restored = snapshot("recovered", hans("one", "recovered", "Aus der Historie", 1))
        fixture.controller.acceptCodex(restored.copy(sessionPhase = ClientSessionPhase.RECOVERING_THREAD))
        fixture.controller.acceptCodex(restored)
        fixture.controller.acceptCodex(restored.copy(timeline = restored.timeline.map { it.copy(revision = 2) }))
        assertEquals(0, fixture.pulses.get())
    }

    @Test
    fun nonUserFacingClassifierAndLiveOwnedCodexOutputAreConsumedWithoutReplay() {
        val fixture = Fixture()
        fixture.controller.acceptCodex(snapshot())
        val silent = snapshot("silent", hans("one", "silent", "Nicht wichtig", 1))
        fixture.controller.acceptCodex(silent, userFacing = false)
        fixture.controller.acceptCodex(silent, userFacing = true)
        val live = snapshot("live-task", hans("two", "live-task", "Zwischenergebnis", 2))
        fixture.controller.acceptCodex(live, liveVoiceOwnsOutput = true)
        fixture.controller.acceptCodex(live, liveVoiceOwnsOutput = false)
        assertEquals(0, fixture.pulses.get())
        fixture.controller.acceptCodex(snapshot("next", hans("three", "next", "Neue Antwort", 3)))
        assertEquals(1, fixture.pulses.get())
    }

    @Test
    fun uncorrelatedTextOtherTurnsAndUserOrToolRowsDoNotPulse() {
        val fixture = Fixture()
        fixture.controller.acceptCodex(snapshot())
        fixture.controller.acceptCodex(snapshot(
            "current",
            hans("uncorrelated", "current", "Text", 1).copy(turnId = null),
            hans("old", "old-turn", "Alter Text", 2),
            hans("user", "current", "Nutzereingabe", 3).copy(role = ClientTimelineRole.USER),
            hans("tool", "current", "Werkzeug", 4).copy(role = ClientTimelineRole.TOOL),
        ))
        assertEquals(0, fixture.pulses.get())
    }

    @Test
    fun disabledFeedbackConsumesTheResponseAndCancelsQueuedPulses() {
        val queue = QueuedExecutor()
        val fixture = Fixture(queue)
        fixture.controller.acceptCodex(snapshot())
        val first = snapshot("one", hans("one", "one", "Antwort", 1))
        fixture.controller.acceptCodex(first)
        val second = snapshot("two", hans("two", "two", "Leise Antwort", 2))
        fixture.controller.acceptCodex(second, enabled = false)
        fixture.controller.acceptCodex(second, enabled = true)
        queue.drain()
        assertEquals(0, fixture.pulses.get())
    }

    @Test
    fun interruptedTurnRevokesQueuedPulseAndRejectsLateText() {
        val queue = QueuedExecutor()
        val fixture = Fixture(queue)
        fixture.controller.acceptCodex(snapshot())
        val first = snapshot("one", hans("one", "one", "Antwort", 1))
        fixture.controller.acceptCodex(first)
        fixture.controller.acceptCodex(first.withTurnStatus(TurnStatus.INTERRUPTED))
        fixture.controller.acceptCodex(first.copy(timeline = first.timeline.map { it.copy(revision = 2) }))
        queue.drain()
        assertEquals(0, fixture.pulses.get())
        fixture.controller.acceptCodex(snapshot("two", hans("two", "two", "Neue Antwort", 2)))
        queue.drain()
        assertEquals(1, fixture.pulses.get())
    }

    @Test
    fun explicitCancelAndCloseInvalidateQueuedEffectsWithoutReplayingThem() {
        val queue = QueuedExecutor()
        val fixture = Fixture(queue)
        fixture.controller.acceptCodex(snapshot())
        val first = snapshot("one", hans("one", "one", "Antwort", 1))
        fixture.controller.acceptCodex(first)
        fixture.controller.cancelPending()
        fixture.controller.acceptCodex(first)
        queue.drain()
        assertEquals(0, fixture.pulses.get())
        fixture.controller.acceptCodex(snapshot("two", hans("two", "two", "Nächste Antwort", 2)))
        fixture.controller.close()
        queue.drain()
        fixture.controller.acceptCodex(snapshot("three", hans("three", "three", "Spät", 3)))
        assertEquals(0, fixture.pulses.get())
    }

    @Test
    fun firstLiveResponseIsNotBaselinedAndDuplicatesAreSilent() {
        val fixture = Fixture()
        fixture.controller.onLiveSnapshot(liveSnapshot())
        val first = LiveVoiceResponseReady("session-one", 1, "response-one")
        fixture.controller.acceptLive(first)
        fixture.controller.acceptLive(first)
        fixture.controller.acceptLive(first.copy(responseId = "response-two"))
        assertEquals(2, fixture.pulses.get())
    }

    @Test
    fun liveDisabledOldGenerationAndStoppedSessionAreSilent() {
        val fixture = Fixture()
        fixture.controller.onLiveSnapshot(liveSnapshot())
        val first = LiveVoiceResponseReady("session-one", 1, "response-one")
        fixture.controller.acceptLive(first, enabled = false)
        fixture.controller.acceptLive(first, enabled = true)
        fixture.controller.acceptLive(first.copy(generation = 0))
        fixture.controller.onLiveSnapshot(liveSnapshot().copy(phase = LiveVoicePhase.STOPPED))
        fixture.controller.acceptLive(first.copy(responseId = "late-response"))
        assertEquals(0, fixture.pulses.get())
    }

    @Test
    fun liveReconnectRevokesPendingAndNewSessionIdentityAvoidsGenerationCollisions() {
        val queue = QueuedExecutor()
        val fixture = Fixture(queue)
        fixture.controller.onLiveSnapshot(liveSnapshot())
        val first = LiveVoiceResponseReady("session-one", 1, "response-one")
        fixture.controller.acceptLive(first)
        fixture.controller.onLiveSnapshot(liveSnapshot().copy(phase = LiveVoicePhase.RECONNECTING))
        queue.drain()
        assertEquals(0, fixture.pulses.get())
        fixture.controller.onLiveSnapshot(liveSnapshot())
        fixture.controller.acceptLive(first.copy(sessionInstanceId = "session-two"))
        queue.drain()
        assertEquals(1, fixture.pulses.get())
    }

    @Test
    fun boundedDedupeDoesNotReplayOldTimelineOrders() {
        val pulses = AtomicInteger()
        val controller = ResponseReadyFeedbackController(
            ResponseReadyHaptics { pulses.incrementAndGet() },
            maxTrackedResponses = 2,
        text = TestResourceTextResolver(java.util.Locale.GERMAN))
        controller.acceptCodex(snapshot())
        for (index in 1..8) {
            controller.acceptCodex(snapshot("turn-$index", hans("item-$index", "turn-$index", "Antwort", index.toLong())))
        }
        controller.acceptCodex(snapshot("turn-1", hans("item-1", "turn-1", "Alte Antwort", 1)))
        assertEquals(8, pulses.get())
    }

    @Test
    fun concurrentIdenticalSnapshotsOnlyClaimOnePulseAndPlatformFailureIsIsolated() {
        val fixture = Fixture()
        fixture.controller.acceptCodex(snapshot())
        val answer = snapshot("one", hans("one", "one", "Antwort", 1))
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        repeat(8) {
            Thread {
                start.await()
                fixture.controller.acceptCodex(answer)
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertEquals(1, fixture.pulses.get())
        val failing = ResponseReadyFeedbackController(ResponseReadyHaptics { error("service unavailable") }, text = TestResourceTextResolver(java.util.Locale.GERMAN))
        failing.acceptCodex(snapshot())
        failing.acceptCodex(answer) // optional feedback cannot fail the caller
    }

    @Test
    fun cancellationDuringAnAlreadyClaimedPulseDoesNotDeadlockOrClaimRollback() {
        lateinit var controller: ResponseReadyFeedbackController
        var pulses = 0
        controller = ResponseReadyFeedbackController(ResponseReadyHaptics {
            controller.cancelPending()
            pulses += 1
        }, text = TestResourceTextResolver(java.util.Locale.GERMAN))
        controller.acceptCodex(snapshot())
        controller.acceptCodex(snapshot("one", hans("one", "one", "Antwort", 1)))
        assertEquals(1, pulses)
    }

    private class Fixture(executor: Executor = Executor { it.run() }) {
        val pulses = AtomicInteger()
        val controller = ResponseReadyFeedbackController(
            haptics = ResponseReadyHaptics { pulses.incrementAndGet() },
            executor = executor,
        text = TestResourceTextResolver(java.util.Locale.GERMAN))
    }

    private class QueuedExecutor : Executor {
        private val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue.addLast(command) }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    private fun liveSnapshot() = LiveVoiceSnapshot(phase = LiveVoicePhase.HANS_SPEAKING, generation = 1)

    private fun snapshot(turnId: String? = null, vararg timeline: ClientTimelineItem): CodexClientSnapshot {
        val threadId = "thread-one"
        return CodexClientSnapshot(
            runtimePhase = ClientRuntimePhase.READY,
            sessionPhase = if (turnId == null) ClientSessionPhase.READY else ClientSessionPhase.BUSY,
            generation = 1,
            session = CodexSessionReducer().snapshot().copy(
                currentThreadId = threadId,
                threads = listOf(ThreadUiSnapshot(
                    threadId = threadId,
                    name = null,
                    preview = "",
                    runtimeStatus = ThreadStatusSnapshot(ThreadRuntimeStatus.IDLE),
                    effectiveOptions = null,
                    currentTurn = turnId?.let { TurnUiSnapshot(it, TurnStatus.IN_PROGRESS, null) },
                    messages = emptyList(),
                    tools = emptyList(),
                )),
            ),
            models = emptyList(),
            deviceCodeLogin = null,
            outboundTimeline = emptyList(),
            timeline = timeline.toList(),
            pendingSelection = null,
            confirmedSelection = DispatchSelection("gpt-5.6-luna", ReasoningEffort.MAX),
            problem = null,
        )
    }

    private fun CodexClientSnapshot.withThread(threadId: String) = copy(
        session = session.copy(
            currentThreadId = threadId,
            threads = session.threads.map { it.copy(threadId = threadId) },
        ),
    )

    private fun CodexClientSnapshot.withTurnStatus(status: TurnStatus) = copy(
        session = session.copy(threads = session.threads.map { it.copy(currentTurn = it.currentTurn?.copy(status = status)) }),
    )

    private fun hans(id: String, turnId: String, text: String, order: Long) = ClientTimelineItem(
        id = id,
        role = ClientTimelineRole.HANS,
        text = text,
        order = order,
        revision = 1,
        complete = false,
        status = ClientTimelineStatus.STREAMING,
        agentPhase = AgentMessagePhase.COMMENTARY,
        turnId = turnId,
    )
}
