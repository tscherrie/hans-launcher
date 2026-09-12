package ai.hans.standard.automations.androidgateway

import ai.hans.standard.automations.AutomationHeartbeat
import ai.hans.standard.automations.CodexAutomationExecutionOutcome
import ai.hans.standard.codex.AccountReadResult
import ai.hans.standard.codex.AppServerEventDecoder
import ai.hans.standard.codex.AppServerRequests
import ai.hans.standard.codex.ApprovalPolicy
import ai.hans.standard.codex.CodexInput
import ai.hans.standard.codex.CorrelatedResponse
import ai.hans.standard.codex.CodexServiceTier
import ai.hans.standard.codex.DispatchOptions
import ai.hans.standard.codex.DispatchSandbox
import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolCancellationDisposition
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolProtocol
import ai.hans.standard.codex.EncodedRequest
import ai.hans.standard.codex.InitializeResult
import ai.hans.standard.codex.ProtocolLimits
import ai.hans.standard.codex.ReasoningEffort
import ai.hans.standard.codex.RequestIdSequence
import ai.hans.standard.codex.ResponseCorrelator
import ai.hans.standard.codex.ServerEvent
import ai.hans.standard.codex.ServerRequestDecodeResult
import ai.hans.standard.codex.ServerRequestId
import ai.hans.standard.codex.ThreadStartResult
import ai.hans.standard.codex.TurnStartResult
import ai.hans.standard.codex.TurnStatus
import java.io.Closeable
import java.io.File
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

data class IndependentCodexSelection(
    val model: String,
    val effort: ReasoningEffort,
    val serviceTier: String = CodexServiceTier.STANDARD,
)

fun interface IndependentCodexSelectionSource {
    fun current(): IndependentCodexSelection
}

fun interface AutomationDeveloperInstructionsSource {
    fun current(): String?

    companion object {
        val EMPTY = AutomationDeveloperInstructionsSource { null }
    }
}

/** Resolved exactly once for each independent run, after authorization but before process start. */
fun interface AutomationDynamicToolExecutorSource {
    fun current(): DynamicToolExecutor?

    companion object {
        fun fixed(executor: DynamicToolExecutor?) = AutomationDynamicToolExecutorSource { executor }
    }
}

sealed interface OneShotAppServerInbound {
    data class Frame(val raw: String) : OneShotAppServerInbound
    data object Oversized : OneShotAppServerInbound
    data object Eof : OneShotAppServerInbound
    data object Failed : OneShotAppServerInbound
}

/** A process, its loopback network stack, and its private transient files form one close unit. */
interface OneShotAppServerSession : Closeable {
    val expectedCodexHome: File
    val workspaceDirectory: File

    fun send(frame: String)

    /** Returns null only when no frame arrived inside the bounded wait. */
    fun poll(maximumWaitMillis: Long): OneShotAppServerInbound?

    /** Non-blocking best-effort cancellation used by JobService.onStopJob. */
    fun requestCancellation() = Unit
}

fun interface OneShotAppServerSessionFactory {
    /** Starts a fresh pinned App Server. Callers heartbeat immediately before invoking this. */
    fun open(): OneShotAppServerSession
}

internal class AutomationGatewayFailure(
    val code: String,
    val retryable: Boolean,
) : IllegalStateException(code) {
    init {
        require(code.matches(SAFE_ERROR_CODE))
    }
}

/**
 * Runs one independent automation in a fresh App Server process while keeping the normal
 * app-private CODEX_HOME (and therefore the user's login and plugin configuration).
 */
class OneShotIndependentAutomationCodexRunner(
    private val sessionFactory: OneShotAppServerSessionFactory,
    private val selectionSource: IndependentCodexSelectionSource,
    private val dynamicToolsSource: AutomationDynamicToolExecutorSource,
    private val developerInstructions: AutomationDeveloperInstructionsSource =
        AutomationDeveloperInstructionsSource.EMPTY,
    private val timeouts: AutomationGatewayTimeouts = AutomationGatewayTimeouts(),
    private val clock: MonotonicNanoClock = MonotonicNanoClock.SYSTEM,
    private val clientVersion: String,
) : IndependentAutomationCodexRunner, Closeable {
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    init {
        require(clientVersion.isNotBlank() && clientVersion.length <= 64)
    }

    constructor(
        sessionFactory: OneShotAppServerSessionFactory,
        selectionSource: IndependentCodexSelectionSource,
        dynamicTools: DynamicToolExecutor?,
        developerInstructions: AutomationDeveloperInstructionsSource =
            AutomationDeveloperInstructionsSource.EMPTY,
        timeouts: AutomationGatewayTimeouts = AutomationGatewayTimeouts(),
        clock: MonotonicNanoClock = MonotonicNanoClock.SYSTEM,
        clientVersion: String,
    ) : this(
        sessionFactory = sessionFactory,
        selectionSource = selectionSource,
        dynamicToolsSource = AutomationDynamicToolExecutorSource.fixed(dynamicTools),
        developerInstructions = developerInstructions,
        timeouts = timeouts,
        clock = clock,
        clientVersion = clientVersion,
    )

    override fun execute(
        instruction: String,
        idempotencyKey: String,
        heartbeat: AutomationHeartbeat,
    ): CodexAutomationExecutionOutcome {
        if (closed.get()) return retryable("codex_gateway_closed")
        if (!running.compareAndSet(false, true)) return retryable("codex_independent_busy")
        try {
            // Lazy sources may construct/bind the process-wide Host. Cover that possible App
            // Server side effect before resolving them, then beat again immediately before open.
            if (!safeHeartbeat(heartbeat)) return retryable("automation_ownership_lost")
            val selection = runCatching(selectionSource::current).getOrElse {
                return permanent("codex_selection_unavailable")
            }
            val instructions = runCatching(developerInstructions::current).getOrElse {
                return permanent("codex_instructions_unavailable")
            }
            val dynamicTools = runCatching(dynamicToolsSource::current).getOrElse {
                return permanent("codex_dynamic_tools_unavailable")
            }
            // Creating directories, a proxy, and the child process is the first side effect.
            if (!safeHeartbeat(heartbeat)) return retryable("automation_ownership_lost")
            val session = try {
                sessionFactory.open()
            } catch (failure: AutomationGatewayFailure) {
                return outcome(failure)
            } catch (_: Exception) {
                return retryable("codex_process_start_failed")
            }
            var result: CodexAutomationExecutionOutcome =
                retryable("codex_independent_failed")
            val protocol = IndependentProtocolSession(
                session = session,
                selection = selection,
                dynamicTools = dynamicTools,
                developerInstructions = instructions,
                heartbeat = heartbeat,
                timeouts = timeouts,
                clock = clock,
                clientVersion = clientVersion,
            )
            var cancellation: AutoCloseable = AutoCloseable {}
            try {
                cancellation = heartbeat.onCancellation(session::requestCancellation)
                result = try {
                    protocol.run(instruction, idempotencyKey)
                } catch (failure: AutomationGatewayFailure) {
                    outcome(failure)
                } catch (_: Exception) {
                    retryable("codex_independent_failed")
                }
            } finally {
                runCatching(cancellation::close)
                try {
                    session.close()
                } catch (failure: AutomationGatewayFailure) {
                    result = outcome(failure)
                } catch (_: Exception) {
                    result = retryable("codex_session_cleanup_failed")
                }
            }
            return failClosedAfterDispatch(result, protocol.externalDispatchStarted)
        } finally {
            running.set(false)
        }
    }

    override fun close() {
        closed.set(true)
        (sessionFactory as? Closeable)?.let { runCatching(it::close) }
    }

    private companion object {
        fun outcome(failure: AutomationGatewayFailure): CodexAutomationExecutionOutcome =
            if (failure.retryable) retryable(failure.code) else permanent(failure.code)

        fun failClosedAfterDispatch(
            outcome: CodexAutomationExecutionOutcome,
            externalDispatchStarted: Boolean,
        ): CodexAutomationExecutionOutcome =
            if (
                externalDispatchStarted &&
                outcome is CodexAutomationExecutionOutcome.RetryableFailure
            ) {
                permanent("codex_turn_outcome_ambiguous")
            } else {
                outcome
            }

        fun retryable(code: String) = CodexAutomationExecutionOutcome.RetryableFailure(code)

        fun permanent(code: String) = CodexAutomationExecutionOutcome.PermanentFailure(code)
    }
}

private class IndependentProtocolSession(
    private val session: OneShotAppServerSession,
    private val selection: IndependentCodexSelection,
    private val dynamicTools: DynamicToolExecutor?,
    private val developerInstructions: String?,
    private val heartbeat: AutomationHeartbeat,
    private val timeouts: AutomationGatewayTimeouts,
    private val clock: MonotonicNanoClock,
    private val clientVersion: String,
) {
    private val requestIds = RequestIdSequence()
    private val correlator = ResponseCorrelator()
    private val completedToolRequestIds = LinkedHashSet<String>()
    private val completedToolResults = LinkedHashMap<DynamicCallIdentity, DynamicToolExecutionResult>()
    private var acceptedFrames = 0
    var externalDispatchStarted: Boolean = false
        private set
    private val deadline = saturatedAdd(
        clock.nowNanos(),
        TimeUnit.MILLISECONDS.toNanos(timeouts.independentTimeoutMillis),
    )

    fun run(
        instruction: String,
        idempotencyKey: String,
    ): CodexAutomationExecutionOutcome {
        val initialized = awaitResponse(
            AppServerRequests.initialize(
                id = requestIds.next(),
                clientName = "hans_automation",
                clientTitle = "Hans Automation",
                clientVersion = clientVersion,
                experimentalApi = true,
            ),
            activeTurn = null,
        ).requireSuccessResult<InitializeResult>()
        if (
            initialized.platformFamily != "unix" ||
            initialized.platformOs != "linux" ||
            runCatching { File(initialized.codexHome).canonicalFile }.getOrNull() !=
            runCatching { session.expectedCodexHome.canonicalFile }.getOrNull()
        ) {
            throw permanentFailure("codex_runtime_identity_mismatch")
        }
        sendRaw(JSONObject().put("method", "initialized").put("params", JSONObject()).toString())

        val account = awaitResponse(
            AppServerRequests.accountRead(requestIds.next(), refreshToken = false),
            activeTurn = null,
        ).requireSuccessResult<AccountReadResult>()
        if (account.account == null) throw retryableFailure("codex_login_required")

        val options = DispatchOptions(
            model = selection.model,
            effort = selection.effort,
            serviceTier = selection.serviceTier,
            approvalPolicy = ApprovalPolicy.NEVER,
            sandbox = DispatchSandbox.DANGER_FULL_ACCESS,
            cwd = session.workspaceDirectory.absolutePath,
            personality = ai.hans.standard.runtime.CodexAssistantProfile.personality,
            reasoningSummary = ai.hans.standard.runtime.CodexAssistantProfile.reasoningSummary,
        )
        val started = awaitResponse(
            AppServerRequests.threadStart(
                id = requestIds.next(),
                options = options,
                developerInstructions = developerInstructions,
                ephemeral = false,
                dynamicTools = dynamicTools?.specs.orEmpty(),
            ),
            activeTurn = null,
        ).requireSuccessResult<ThreadStartResult>()
        if (
            started.effectiveModel != selection.model ||
            started.effectiveEffort != selection.effort ||
            started.effectiveServiceTier != selection.serviceTier
        ) {
            throw permanentFailure("codex_selection_rejected")
        }

        val turnRequest = AppServerRequests.turnStart(
            id = requestIds.next(),
            threadId = started.threadId,
            input = listOf(CodexInput.Text(instruction)),
            options = options,
            clientUserMessageId = idempotencyKey,
        )
        if (!safeMarkExternalDispatchStarted(heartbeat)) {
            throw retryableFailure("automation_ownership_lost")
        }
        externalDispatchStarted = true
        val turnResponse = awaitResponse(
            turnRequest,
            activeTurn = ActiveAutomationTurn(started.threadId, null),
        )
        if (turnResponse is CorrelatedResponse.Failure) {
            // A correlated JSON-RPC error is the only authoritative proof that App Server rejected
            // turn/start before accepting a turn. Transport/protocol exceptions intentionally
            // retain the durable fence because their external outcome is unknown.
            if (!safeClearExternalDispatchAfterProvenRejection(heartbeat)) {
                throw permanentFailure("codex_turn_outcome_ambiguous")
            }
            externalDispatchStarted = false
            throw retryableFailure("codex_request_failed")
        }
        val turn = turnResponse.requireSuccessResult<TurnStartResult>()
        val activeTurn = ActiveAutomationTurn(started.threadId, turn.turnId)
        when (turn.status) {
            TurnStatus.COMPLETED -> return CodexAutomationExecutionOutcome.Succeeded
            TurnStatus.FAILED -> return retryable("codex_turn_failed")
            TurnStatus.INTERRUPTED -> return retryable("codex_turn_interrupted")
            TurnStatus.IN_PROGRESS -> Unit
        }

        while (true) {
            when (val frame = nextFrame()) {
                is ParsedInbound.Response ->
                    throw permanentFailure("codex_unexpected_response")
                is ParsedInbound.ServerRequest -> handleServerRequest(frame.raw, activeTurn)
                is ParsedInbound.Event -> {
                    val event = try {
                        AppServerEventDecoder.decode(frame.raw)
                    } catch (_: Exception) {
                        throw permanentFailure("codex_protocol_incompatible")
                    }
                    if (event is ServerEvent.TurnCompleted) {
                        if (
                            event.threadId != activeTurn.threadId ||
                            event.turn.id != activeTurn.turnId
                        ) {
                            continue
                        }
                        return when (event.turn.status) {
                            TurnStatus.COMPLETED -> CodexAutomationExecutionOutcome.Succeeded
                            TurnStatus.FAILED -> retryable("codex_turn_failed")
                            TurnStatus.INTERRUPTED -> retryable("codex_turn_interrupted")
                            TurnStatus.IN_PROGRESS ->
                                throw permanentFailure("codex_protocol_incompatible")
                        }
                    }
                }
            }
        }
    }

    private fun awaitResponse(
        request: EncodedRequest,
        activeTurn: ActiveAutomationTurn?,
    ): CorrelatedResponse {
        correlator.register(request)
        sendRaw(request.json)
        while (true) {
            when (val frame = nextFrame()) {
                is ParsedInbound.Response -> {
                    val correlated = try {
                        correlator.accept(frame.raw)
                    } catch (_: Exception) {
                        throw permanentFailure("codex_protocol_incompatible")
                    }
                    if (correlated.id != request.id) {
                        throw permanentFailure("codex_response_correlation_failed")
                    }
                    return correlated
                }
                is ParsedInbound.ServerRequest -> handleServerRequest(frame.raw, activeTurn)
                is ParsedInbound.Event -> {
                    // Startup may legitimately emit catalog/plugin invalidation hints. Decode all
                    // known events strictly so malformed protocol never gets silently accepted.
                    try {
                        AppServerEventDecoder.decode(frame.raw)
                    } catch (_: Exception) {
                        throw permanentFailure("codex_protocol_incompatible")
                    }
                }
            }
        }
    }

    private fun nextFrame(): ParsedInbound {
        while (true) {
            val remaining = remainingMillis(deadline)
                ?: throw retryableFailure("codex_turn_timeout")
            val wait = boundedWait(minOf(remaining, timeouts.heartbeatIntervalMillis))
            val inbound = try {
                session.poll(wait)
            } catch (_: Exception) {
                throw retryableFailure("codex_process_io_failed")
            }
            if (inbound == null) {
                ensureHeartbeat()
                continue
            }
            acceptedFrames += 1
            if (acceptedFrames > MAX_ACCEPTED_FRAMES) {
                throw permanentFailure("codex_frame_budget_exceeded")
            }
            val raw = when (inbound) {
                is OneShotAppServerInbound.Frame -> inbound.raw
                OneShotAppServerInbound.Oversized ->
                    throw permanentFailure("codex_frame_oversized")
                OneShotAppServerInbound.Eof -> throw retryableFailure("codex_process_closed")
                OneShotAppServerInbound.Failed ->
                    throw retryableFailure("codex_process_io_failed")
            }
            if (
                raw.isBlank() ||
                raw.toByteArray(Charsets.UTF_8).size > ProtocolLimits.MAX_INBOUND_FRAME_BYTES
            ) {
                throw permanentFailure("codex_frame_oversized")
            }
            val envelope = try {
                JSONObject(raw)
            } catch (_: Exception) {
                throw permanentFailure("codex_protocol_incompatible")
            }
            return when {
                envelope.has("id") && envelope.has("method") -> ParsedInbound.ServerRequest(raw)
                envelope.has("id") -> ParsedInbound.Response(raw)
                envelope.has("method") -> ParsedInbound.Event(raw)
                else -> throw permanentFailure("codex_protocol_incompatible")
            }
        }
    }

    private fun handleServerRequest(raw: String, activeTurn: ActiveAutomationTurn?) {
        when (val decoded = DynamicToolProtocol.decodeServerRequest(raw)) {
            is ServerRequestDecodeResult.DynamicToolCall -> {
                val call = decoded.call
                val requestKey = call.requestId.stableKey()
                if (requestKey in completedToolRequestIds) return
                val params = call.params
                if (
                    activeTurn?.turnId == null ||
                    params.threadId != activeTurn.threadId ||
                    params.turnId != activeTurn.turnId ||
                    !isRegistered(params)
                ) {
                    sendToolErrorOnce(call.requestId, -32602, "invalid_dynamic_tool_request")
                    return
                }
                val identity = DynamicCallIdentity.from(params)
                completedToolResults[identity]?.let { prior ->
                    sendToolResultOnce(call.requestId, prior)
                    return
                }
                if (completedToolResults.size >= MAX_COMPLETED_TOOL_RESULTS) {
                    sendToolErrorOnce(call.requestId, -32000, "dynamic_tool_capacity_exceeded")
                    return
                }
                val result = executeDynamicTool(params)
                completedToolResults[identity] = result
                sendToolResultOnce(call.requestId, result)
            }
            is ServerRequestDecodeResult.Unsupported ->
                sendToolErrorOnce(decoded.requestId, -32601, "method_not_supported")
            is ServerRequestDecodeResult.Malformed -> decoded.requestId?.let { requestId ->
                sendToolErrorOnce(requestId, -32602, "invalid_dynamic_tool_request")
            } ?: throw permanentFailure("codex_protocol_incompatible")
            ServerRequestDecodeResult.NotServerRequest ->
                throw permanentFailure("codex_protocol_incompatible")
        }
    }

    private fun executeDynamicTool(params: DynamicToolCallParams): DynamicToolExecutionResult {
        val executor = dynamicTools
            ?: throw permanentFailure("codex_dynamic_tools_unavailable")
        ensureHeartbeat()
        val result = AtomicReference<DynamicToolExecutionResult?>(null)
        val accepting = AtomicBoolean(true)
        val completion = CountDownLatch(1)
        val cancellationRequested = AtomicBoolean(false)
        val executionHandle = AtomicReference<DynamicToolExecutionHandle?>(null)
        val cancellationDisposition = AtomicReference<DynamicToolCancellationDisposition?>(null)

        fun cancelExecution(): DynamicToolCancellationDisposition? {
            val disposition = executionHandle.get()?.let { handle ->
                runCatching(handle::cancel).getOrElse {
                    DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED
                }
            }
            if (disposition == DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED) {
                cancellationDisposition.set(disposition)
            } else if (disposition != null) {
                cancellationDisposition.compareAndSet(null, disposition)
            }
            return cancellationDisposition.get()
        }

        val cancellation = heartbeat.onCancellation {
            cancellationRequested.set(true)
            accepting.set(false)
            cancelExecution()
            completion.countDown()
        }
        try {
            val handle = executor.executeCancellable(
                call = params,
                cancellation = DynamicToolCancellation(cancellationRequested::get),
            ) { candidate ->
                if (
                    !cancellationRequested.get() &&
                    accepting.compareAndSet(true, false)
                ) {
                    result.set(candidate)
                    completion.countDown()
                }
            }
            executionHandle.set(handle)
            if (cancellationRequested.get()) cancelExecution()
        } catch (_: Exception) {
            accepting.set(false)
            runCatching(cancellation::close)
            // A legacy/custom executor may throw after entering an external API. Without a
            // published handle there is no proof that the request remained pre-effect.
            throw permanentFailure("dynamic_tool_outcome_ambiguous")
        }
        val toolDeadline = minOf(
            deadline,
            saturatedAdd(
                clock.nowNanos(),
                TimeUnit.MILLISECONDS.toNanos(timeouts.toolTimeoutMillis),
            ),
        )
        try {
            while (true) {
                val remaining = remainingMillis(toolDeadline)
                if (remaining == null) {
                    accepting.set(false)
                    if (
                        cancelExecution() ==
                        DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED
                    ) {
                        throw permanentFailure("dynamic_tool_outcome_ambiguous")
                    }
                    return safeToolFailure(executor, params, "dynamic_tool_timeout")
                }
                val wait = boundedWait(minOf(remaining, timeouts.heartbeatIntervalMillis))
                if (
                    runCatching { completion.await(wait, TimeUnit.MILLISECONDS) }
                        .getOrDefault(false)
                ) {
                    ensureHeartbeat()
                    return result.get()
                        ?: safeToolFailure(executor, params, "dynamic_tool_missing_result")
                }
                ensureHeartbeat()
            }
        } finally {
            accepting.set(false)
            runCatching(cancellation::close)
            cancelExecution()
        }
    }

    private fun safeToolFailure(
        executor: DynamicToolExecutor,
        params: DynamicToolCallParams,
        code: String,
    ): DynamicToolExecutionResult = runCatching {
        executor.failureResult(params, code)
    }.getOrElse {
        throw permanentFailure("codex_dynamic_tool_failed")
    }

    private fun isRegistered(params: DynamicToolCallParams): Boolean {
        val namespace = params.namespace ?: return false
        return dynamicTools?.specs.orEmpty().any { spec ->
            spec.name == namespace && spec.tools.any { it.name == params.tool }
        }
    }

    private fun sendToolResultOnce(
        requestId: ServerRequestId,
        result: DynamicToolExecutionResult,
    ) {
        if (!rememberToolRequest(requestId)) return
        sendRaw(DynamicToolProtocol.response(requestId, result))
    }

    private fun sendToolErrorOnce(
        requestId: ServerRequestId,
        code: Int,
        message: String,
    ) {
        if (!rememberToolRequest(requestId)) return
        sendRaw(DynamicToolProtocol.error(requestId, code, message))
    }

    private fun rememberToolRequest(requestId: ServerRequestId): Boolean {
        val key = requestId.stableKey()
        if (!completedToolRequestIds.add(key)) return false
        if (completedToolRequestIds.size > MAX_COMPLETED_TOOL_REQUESTS) {
            throw permanentFailure("codex_dynamic_tool_capacity_exceeded")
        }
        return true
    }

    private fun sendRaw(raw: String) {
        if (
            raw.isBlank() ||
            raw.toByteArray(Charsets.UTF_8).size > ProtocolLimits.MAX_OUTBOUND_FRAME_BYTES
        ) {
            throw permanentFailure("codex_outbound_frame_invalid")
        }
        ensureHeartbeat()
        try {
            session.send(raw)
        } catch (_: Exception) {
            throw retryableFailure("codex_process_io_failed")
        }
    }

    private fun ensureHeartbeat() {
        if (!safeHeartbeat(heartbeat)) throw retryableFailure("automation_ownership_lost")
    }

    private fun boundedWait(proposedMillis: Long): Long = runCatching {
        heartbeat.boundWaitMillis(proposedMillis)
    }.getOrDefault(1L).coerceIn(1L, proposedMillis.coerceAtLeast(1L))

    private fun remainingMillis(targetDeadline: Long): Long? {
        val remaining = targetDeadline - clock.nowNanos()
        if (remaining <= 0) return null
        return maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining))
    }

    private inline fun <reified T> CorrelatedResponse.requireSuccessResult(): T = when (this) {
        is CorrelatedResponse.Success -> result as? T
            ?: throw permanentFailure("codex_protocol_incompatible")
        is CorrelatedResponse.Failure -> throw retryableFailure("codex_request_failed")
    }

    private companion object {
        const val MAX_ACCEPTED_FRAMES = 4_096
        const val MAX_COMPLETED_TOOL_REQUESTS = 512
        const val MAX_COMPLETED_TOOL_RESULTS = 128

        fun retryable(code: String) = CodexAutomationExecutionOutcome.RetryableFailure(code)
    }
}

private sealed interface ParsedInbound {
    val raw: String

    data class Response(override val raw: String) : ParsedInbound
    data class ServerRequest(override val raw: String) : ParsedInbound
    data class Event(override val raw: String) : ParsedInbound
}

private data class ActiveAutomationTurn(
    val threadId: String,
    val turnId: String?,
)

private data class DynamicCallIdentity(
    val threadId: String,
    val turnId: String,
    val callId: String,
) {
    companion object {
        fun from(params: DynamicToolCallParams) = DynamicCallIdentity(
            params.threadId,
            params.turnId,
            params.callId,
        )
    }
}

private fun ServerRequestId.stableKey(): String = when (this) {
    is ServerRequestId.Number -> "n:$value"
    is ServerRequestId.Text -> "s:$value"
}

private fun safeHeartbeat(heartbeat: AutomationHeartbeat): Boolean =
    runCatching(heartbeat::beat).getOrDefault(false)

private fun safeMarkExternalDispatchStarted(heartbeat: AutomationHeartbeat): Boolean =
    runCatching(heartbeat::markExternalDispatchStarted).getOrDefault(false)

private fun safeClearExternalDispatchAfterProvenRejection(
    heartbeat: AutomationHeartbeat,
): Boolean = runCatching(heartbeat::clearExternalDispatchAfterProvenRejection).getOrDefault(false)

private fun saturatedAdd(left: Long, right: Long): Long =
    if (right > 0 && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

private fun retryableFailure(code: String) = AutomationGatewayFailure(code, retryable = true)

private fun permanentFailure(code: String) = AutomationGatewayFailure(code, retryable = false)

private val SAFE_ERROR_CODE = Regex("[a-z][a-z0-9_]{2,95}")
