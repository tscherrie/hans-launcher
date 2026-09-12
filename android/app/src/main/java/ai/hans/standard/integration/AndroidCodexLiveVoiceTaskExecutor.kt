package ai.hans.standard.integration

import ai.hans.standard.codex.AgentMessagePhase
import ai.hans.standard.codex.CodexInput
import ai.hans.standard.codex.TurnStatus
import ai.hans.standard.voice.realtime.LiveVoiceDiagnostics
import ai.hans.standard.voice.realtime.LiveVoiceTaskExecutor
import ai.hans.standard.voice.realtime.LiveVoiceTaskFailure
import ai.hans.standard.voice.realtime.LiveVoiceTaskHandle
import ai.hans.standard.voice.realtime.LiveVoiceTaskProgress
import ai.hans.standard.voice.realtime.LiveVoiceTaskRequest
import ai.hans.standard.voice.realtime.LiveVoiceTaskResult
import ai.hans.standard.voice.realtime.LiveVoiceSetupWorkflowContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connects the Realtime `use_hans` tool to the same local Codex session that backs chat.
 * Realtime remains the conversational surface; Codex performs phone/plugin/tool work and its
 * visible commentary/final output is returned through this narrow, cancellable boundary.
 */
class AndroidCodexLiveVoiceTaskExecutor internal constructor(
    private val host: LiveVoiceCodexTaskHost,
    private val setupWorkflowProvider: () -> LiveVoiceSetupWorkflowContext? = { null },
) : LiveVoiceTaskExecutor {
    constructor(
        host: AndroidCodexSessionHost,
        setupWorkflowProvider: () -> LiveVoiceSetupWorkflowContext? = { null },
    ) : this(AndroidLiveVoiceCodexTaskHost(host), setupWorkflowProvider)

    override fun execute(
        request: LiveVoiceTaskRequest,
        listener: LiveVoiceTaskExecutor.Listener,
    ): LiveVoiceTaskHandle {
        val baselineOrder = host.snapshot()?.timeline?.maxOfOrNull { it.order } ?: 0L
        val operation = Operation(
            host = host,
            listener = listener,
            baselineOrder = baselineOrder,
        )
        host.addObserver(operation)
        val setupRoutingContext = runCatching { setupWorkflowProvider() }
            .getOrNull()
            ?.takeIf { it.active }
            ?.internalCodexRoutingContext()
        val acceptedId = runCatching {
            host.dispatch(request.request, setupRoutingContext)
        }.getOrNull()
        if (acceptedId == null) {
            operation.fail("codex_task_unavailable", retryable = true)
        } else {
            operation.accept(acceptedId)
            host.snapshot()?.let(operation::onSnapshot)
        }
        return operation
    }

    private class Operation(
        private val host: LiveVoiceCodexTaskHost,
        private val listener: LiveVoiceTaskExecutor.Listener,
        private val baselineOrder: Long,
    ) : CodexClientObserver, LiveVoiceTaskHandle {
        private val terminal = AtomicBoolean(false)
        private val monitor = Any()
        private var acceptedMessageId: String? = null
        private var targetThreadId: String? = null
        private var targetTurnId: String? = null
        private var lastProgressFingerprint: String? = null

        fun accept(messageId: String) {
            synchronized(monitor) { acceptedMessageId = messageId }
        }

        override fun onSnapshot(snapshot: CodexClientSnapshot) {
            if (terminal.get()) return
            val messageId = synchronized(monitor) { acceptedMessageId } ?: return
            val outbound = snapshot.outboundTimeline.firstOrNull {
                it.clientUserMessageId == messageId
            }
            if (outbound?.status == OutboundMessageStatus.FAILED) {
                fail("codex_task_dispatch_failed", retryable = outbound.retryable)
                return
            }

            outbound?.turnId?.takeIf(String::isNotBlank)?.let { observedTurnId ->
                val mismatch = synchronized(monitor) {
                    when (val alreadyBound = targetTurnId) {
                        null -> {
                            targetTurnId = observedTurnId
                            targetThreadId = outbound.threadId
                            LiveVoiceDiagnostics.event(
                                "TARGET_TURN_BOUND",
                                "turn=${LiveVoiceDiagnostics.safeId(observedTurnId)}",
                            )
                            false
                        }
                        observedTurnId -> false
                        else -> true
                    }
                }
                if (mismatch) {
                    fail("codex_task_turn_correlation_changed", retryable = false)
                    return
                }
            }

            val correlation = synchronized(monitor) {
                targetThreadId?.let { threadId ->
                    targetTurnId?.let { turnId -> threadId to turnId }
                }
            }
            val targetItems = correlation?.second?.let { turnId ->
                snapshot.timeline.filter {
                    it.order > baselineOrder && it.turnId == turnId
                }
            }.orEmpty()
            LiveVoiceCodexTaskProjection.newestProgress(targetItems)?.let(::emitProgressOnce)

            val terminalTurn = correlation?.let { (threadId, turnId) ->
                snapshot.terminalTurns.firstOrNull {
                    it.threadId == threadId && it.turnId == turnId
                }
            }
            if (terminalTurn != null) {
                LiveVoiceDiagnostics.event(
                    "TARGET_TURN_TERMINAL",
                    "turn=${LiveVoiceDiagnostics.safeId(terminalTurn.turnId)} " +
                        "status=${terminalTurn.status.wireValue}",
                )
                when (terminalTurn.status) {
                    TurnStatus.COMPLETED -> complete(targetItems)
                    TurnStatus.INTERRUPTED ->
                        fail("codex_task_interrupted", retryable = true)
                    TurnStatus.FAILED -> fail(
                        "codex_task_failed",
                        retryable = snapshot.problem?.retryable == true,
                    )
                    TurnStatus.IN_PROGRESS -> Unit
                }
                return
            }

            when {
                snapshot.runtimePhase == ClientRuntimePhase.FAILED ->
                    fail("codex_runtime_failed", retryable = true)
                snapshot.sessionPhase == ClientSessionPhase.AUTH_REQUIRED ->
                    fail("codex_login_required", retryable = false)
                snapshot.sessionPhase == ClientSessionPhase.FAILED ->
                    fail("codex_task_failed", retryable = snapshot.problem?.retryable == true)
            }
        }

        override fun cancel() {
            finish { }
        }

        fun fail(code: String, retryable: Boolean) {
            finish {
                runCatching {
                    listener.onFailure(LiveVoiceTaskFailure(code, retryable))
                }
            }
        }

        private fun complete(items: List<ClientTimelineItem>) {
            val output = LiveVoiceCodexTaskProjection.completedOutput(items)
            if (output.isBlank()) {
                fail("codex_task_completed_without_output", retryable = false)
            } else {
                finish {
                    runCatching { listener.onCompleted(LiveVoiceTaskResult(output)) }
                }
            }
        }

        private fun emitProgressOnce(summary: String) {
            val fingerprint = summary
            val fresh = synchronized(monitor) {
                if (lastProgressFingerprint == fingerprint) {
                    false
                } else {
                    lastProgressFingerprint = fingerprint
                    true
                }
            }
            if (fresh) runCatching {
                listener.onProgress(LiveVoiceTaskProgress(summary))
            }
        }

        private inline fun finish(callback: () -> Unit) {
            if (!terminal.compareAndSet(false, true)) return
            host.removeObserver(this)
            callback()
        }
    }
}

internal interface LiveVoiceCodexTaskHost {
    fun addObserver(observer: CodexClientObserver)
    fun removeObserver(observer: CodexClientObserver)
    fun snapshot(): CodexClientSnapshot?
    fun dispatch(text: String, internalRoutingContext: String? = null): String?
}

private class AndroidLiveVoiceCodexTaskHost(
    private val delegate: AndroidCodexSessionHost,
) : LiveVoiceCodexTaskHost {
    override fun addObserver(observer: CodexClientObserver) = delegate.addObserver(observer)

    override fun removeObserver(observer: CodexClientObserver) = delegate.removeObserver(observer)

    override fun snapshot(): CodexClientSnapshot? = delegate.snapshot()

    override fun dispatch(text: String, internalRoutingContext: String?): String? = delegate.dispatch(
        input = buildList {
            add(CodexInput.Text(text))
            internalRoutingContext?.let { add(CodexInput.UntrustedContext(it)) }
        },
        selection = null,
        readResponseAloud = false,
    )
}

internal object LiveVoiceCodexTaskProjection {
    fun newestProgress(items: List<ClientTimelineItem>): String? = items
        .asSequence()
        .filter {
            it.role == ClientTimelineRole.HANS &&
                it.agentPhase == AgentMessagePhase.COMMENTARY &&
                it.text.isNotBlank()
        }
        .maxByOrNull(ClientTimelineItem::order)
        ?.text
        ?.replace(Regex("[\\t\\r\\n ]+"), " ")
        ?.trim()
        ?.take(LiveVoiceTaskProgress.MAX_PROGRESS_CHARACTERS)
        ?.takeIf(String::isNotBlank)

    fun completedOutput(items: List<ClientTimelineItem>): String {
        val completedHans = items.filter {
            it.role == ClientTimelineRole.HANS &&
                it.complete &&
                it.status == ClientTimelineStatus.COMPLETE &&
                it.text.isNotBlank()
        }
        return completedHans.filter {
            it.agentPhase == AgentMessagePhase.FINAL_ANSWER
        }.ifEmpty { completedHans }
            .sortedBy(ClientTimelineItem::order)
            .joinToString("\n\n") { it.text.trim() }
            .take(LiveVoiceTaskResult.MAX_OUTPUT_CHARACTERS)
    }
}
