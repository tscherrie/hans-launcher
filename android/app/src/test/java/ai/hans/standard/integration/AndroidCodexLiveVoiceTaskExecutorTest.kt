package ai.hans.standard.integration

import ai.hans.standard.codex.AccountPhase
import ai.hans.standard.codex.AccountUiSnapshot
import ai.hans.standard.codex.AgentMessagePhase
import ai.hans.standard.codex.DeliveryUiSnapshot
import ai.hans.standard.codex.ReasoningEffort
import ai.hans.standard.codex.SessionUiSnapshot
import ai.hans.standard.codex.TurnStatus
import ai.hans.standard.voice.realtime.LiveVoiceTaskExecutor
import ai.hans.standard.voice.realtime.LiveVoiceTaskFailure
import ai.hans.standard.voice.realtime.LiveVoiceTaskProgress
import ai.hans.standard.voice.realtime.LiveVoiceTaskRequest
import ai.hans.standard.voice.realtime.LiveVoiceTaskResult
import ai.hans.standard.voice.realtime.LiveVoiceSetupWorkflowContext
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCodexLiveVoiceTaskExecutorTest {
    @Test
    fun activeSetupAddsInternalRoutingContextToTheSameSingleDispatch() {
        val host = FakeHost(snapshot())
        val listener = RecordingListener()
        val setup = LiveVoiceSetupWorkflowContext(
            revision = 28,
            started = true,
            complete = false,
            currentStep = "intro",
            currentStatus = "awaiting_user",
            awaitingUser = true,
            conversationIdentity = "thread-1",
        )

        AndroidCodexLiveVoiceTaskExecutor(host) { setup }.execute(
            LiveVoiceTaskRequest("realtime-call", "Ja, lass uns anfangen."),
            listener,
        )

        assertEquals(1, host.dispatches.size)
        assertEquals("Ja, lass uns anfangen.", host.dispatches.single().first)
        val routing = host.dispatches.single().second.orEmpty()
        assertTrue(routing.contains("\$hans-setup:setup-hans-device"))
        assertTrue(routing.contains("aktuellen Codex-Thread"))
        assertTrue(routing.contains("intro"))
    }

    @Test
    fun foreignReadyAndForeignFinalCannotCompleteTargetTask() {
        val host = FakeHost(snapshot())
        val listener = RecordingListener()
        AndroidCodexLiveVoiceTaskExecutor(host).execute(
            LiveVoiceTaskRequest("realtime-call", "Prüfe Maps."),
            listener,
        )

        host.emit(
            snapshot(
                outbound = listOf(outbound("turn-target")),
                timeline = listOf(finalItem("foreign-final", "turn-foreign", "Fremdes Ergebnis", 2)),
                terminals = listOf(
                    ClientTerminalTurn("thread-1", "turn-foreign", TurnStatus.COMPLETED),
                ),
                phase = ClientSessionPhase.READY,
            ),
        )

        assertNull(listener.result)
        assertNull(listener.failure)
        assertTrue(listener.progress.isEmpty())

        host.emit(
            snapshot(
                outbound = listOf(outbound("turn-target")),
                timeline = listOf(
                    finalItem("foreign-final", "turn-foreign", "Fremdes Ergebnis", 2),
                    commentaryItem("target-progress", "turn-target", "Ich prüfe Maps.", 3),
                    finalItem("target-final", "turn-target", "Maps ist installiert.", 4),
                ),
                terminals = listOf(
                    ClientTerminalTurn("thread-1", "turn-foreign", TurnStatus.COMPLETED),
                    ClientTerminalTurn("thread-1", "turn-target", TurnStatus.COMPLETED),
                ),
                phase = ClientSessionPhase.READY,
            ),
        )

        assertEquals("Maps ist installiert.", listener.result?.output)
        assertEquals(listOf("Ich prüfe Maps."), listener.progress.map { it.summary })
        assertNull(listener.failure)
    }

    @Test
    fun exactInterruptedReceiptFailsWithoutUsingUnrelatedCompletedTurn() {
        val host = FakeHost(snapshot())
        val listener = RecordingListener()
        AndroidCodexLiveVoiceTaskExecutor(host).execute(
            LiveVoiceTaskRequest("realtime-call", "Prüfe Maps."),
            listener,
        )

        host.emit(
            snapshot(
                outbound = listOf(outbound("turn-target")),
                timeline = listOf(
                    finalItem("other", "turn-other", "Fremdes Ergebnis", 2),
                ),
                terminals = listOf(
                    ClientTerminalTurn("thread-1", "turn-other", TurnStatus.COMPLETED),
                    ClientTerminalTurn("thread-1", "turn-target", TurnStatus.INTERRUPTED),
                ),
            ),
        )

        assertNull(listener.result)
        assertEquals("codex_task_interrupted", listener.failure?.code)
        assertEquals(true, listener.failure?.retryable)
    }

    private class FakeHost(
        initial: CodexClientSnapshot,
    ) : LiveVoiceCodexTaskHost {
        private val observers = Collections.synchronizedSet(mutableSetOf<CodexClientObserver>())
        @Volatile private var current = initial
        val dispatches = mutableListOf<Pair<String, String?>>()

        override fun addObserver(observer: CodexClientObserver) {
            observers += observer
            observer.onSnapshot(current)
        }

        override fun removeObserver(observer: CodexClientObserver) {
            observers -= observer
        }

        override fun snapshot(): CodexClientSnapshot = current

        override fun dispatch(text: String, internalRoutingContext: String?): String {
            dispatches += text to internalRoutingContext
            return "message-live"
        }

        fun emit(snapshot: CodexClientSnapshot) {
            current = snapshot
            observers.toList().forEach { it.onSnapshot(snapshot) }
        }
    }

    private class RecordingListener : LiveVoiceTaskExecutor.Listener {
        val progress = mutableListOf<LiveVoiceTaskProgress>()
        var result: LiveVoiceTaskResult? = null
        var failure: LiveVoiceTaskFailure? = null

        override fun onProgress(progress: LiveVoiceTaskProgress) {
            this.progress += progress
        }

        override fun onCompleted(result: LiveVoiceTaskResult) {
            this.result = result
        }

        override fun onFailure(failure: LiveVoiceTaskFailure) {
            this.failure = failure
        }
    }

    private fun outbound(turnId: String) = OutboundUserMessageUi(
        clientUserMessageId = "message-live",
        threadId = "thread-1",
        displayText = "redacted",
        status = OutboundMessageStatus.SENT,
        retryable = false,
        turnId = turnId,
    )

    private fun commentaryItem(
        id: String,
        turnId: String,
        text: String,
        order: Long,
    ) = item(id, turnId, text, order, AgentMessagePhase.COMMENTARY)

    private fun finalItem(
        id: String,
        turnId: String,
        text: String,
        order: Long,
    ) = item(id, turnId, text, order, AgentMessagePhase.FINAL_ANSWER)

    private fun item(
        id: String,
        turnId: String,
        text: String,
        order: Long,
        phase: AgentMessagePhase,
    ) = ClientTimelineItem(
        id = id,
        role = ClientTimelineRole.HANS,
        text = text,
        order = order,
        revision = 1,
        complete = true,
        status = ClientTimelineStatus.COMPLETE,
        agentPhase = phase,
        turnId = turnId,
    )

    private fun snapshot(
        outbound: List<OutboundUserMessageUi> = emptyList(),
        timeline: List<ClientTimelineItem> = emptyList(),
        terminals: List<ClientTerminalTurn> = emptyList(),
        phase: ClientSessionPhase = ClientSessionPhase.BUSY,
    ) = CodexClientSnapshot(
        runtimePhase = ClientRuntimePhase.READY,
        sessionPhase = phase,
        generation = 1,
        session = SessionUiSnapshot(
            account = AccountUiSnapshot(
                phase = AccountPhase.UNKNOWN,
                identity = null,
                authMode = null,
                planType = null,
                pendingLoginId = null,
                error = null,
            ),
            currentThreadId = "thread-1",
            threads = emptyList(),
            delivery = DeliveryUiSnapshot(
                generation = 1,
                lastSequence = 1,
                pendingGeneration = null,
                rehydrationRequired = false,
            ),
        ),
        models = emptyList(),
        deviceCodeLogin = null,
        outboundTimeline = outbound,
        timeline = timeline,
        pendingSelection = null,
        confirmedSelection = DispatchSelection("gpt-5.6-luna", ReasoningEffort.MAX),
        problem = null,
        terminalTurns = terminals,
    )
}
