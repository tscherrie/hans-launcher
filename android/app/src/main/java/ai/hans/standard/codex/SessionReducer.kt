package ai.hans.standard.codex

enum class AccountPhase {
    UNKNOWN,
    SIGNED_OUT,
    LOGIN_PENDING,
    SIGNED_IN,
    ERROR,
}

data class AccountUiSnapshot(
    val phase: AccountPhase,
    val identity: AccountIdentity?,
    val authMode: AccountAuthMode?,
    val planType: AccountPlanType?,
    val pendingLoginId: String?,
    val error: String?,
)

data class MessageUiSnapshot(
    val itemId: String,
    val turnId: String,
    val text: String,
    val phase: AgentMessagePhase?,
    val complete: Boolean,
)

data class ToolUiSnapshot(
    val itemId: String,
    val turnId: String,
    val type: String,
    val label: String,
    val status: ToolStatus,
    val output: String?,
    val complete: Boolean,
)

data class TurnUiSnapshot(
    val turnId: String,
    val status: TurnStatus,
    val error: String?,
)

data class ThreadUiSnapshot(
    val threadId: String,
    val name: String?,
    val preview: String,
    val runtimeStatus: ThreadStatusSnapshot,
    val effectiveOptions: DispatchOptions?,
    val currentTurn: TurnUiSnapshot?,
    val messages: List<MessageUiSnapshot>,
    val tools: List<ToolUiSnapshot>,
    val tokenUsage: ThreadTokenUsageSnapshot? = null,
)

data class SessionUiSnapshot(
    val account: AccountUiSnapshot,
    val currentThreadId: String?,
    val threads: List<ThreadUiSnapshot>,
    val delivery: DeliveryUiSnapshot,
)

data class DeliveryCursor(
    val generation: Long,
    val sequence: Long,
) {
    init {
        require(generation >= 0) { "Delivery generation must not be negative" }
        require(sequence >= 0) { "Delivery sequence must not be negative" }
    }
}

data class DeliveredServerEvent(
    val cursor: DeliveryCursor,
    val event: ServerEvent,
)

enum class DeliveryDisposition {
    APPLIED,
    DUPLICATE_IGNORED,
    STALE_REJECTED,
    REHYDRATION_REQUIRED,
}

data class DeliveryUiSnapshot(
    val generation: Long?,
    val lastSequence: Long?,
    val pendingGeneration: Long?,
    val rehydrationRequired: Boolean,
)

data class ReductionResult(
    val snapshot: SessionUiSnapshot,
    val disposition: DeliveryDisposition,
)

/**
 * Deterministic, Android-free projection of correlated responses and server
 * notifications. Terminal item/turn states are authoritative: delayed starts
 * or deltas can never roll them backwards.
 */
class CodexSessionReducer {
    private var account = MutableAccount()
    private val threads = LinkedHashMap<String, MutableThread>()
    private var currentThreadId: String? = null
    private var tokenUsage: ThreadTokenUsageSnapshot? = null
    private var activeGeneration: Long? = null
    private var lastSequence: Long? = null
    private var pendingGeneration: Long? = null
    private var recoveryAccountRead = false
    private var requiredRecoveryThreadId: String? = null
    private val recoveredThreadIds = LinkedHashSet<String>()

    @Synchronized
    fun apply(response: CorrelatedResponse): SessionUiSnapshot {
        when (response) {
            is CorrelatedResponse.Failure -> applyFailure(response)
            is CorrelatedResponse.Success -> applyResult(response.result)
        }
        return snapshotLocked()
    }

    @Synchronized
    fun apply(delivery: DeliveredServerEvent): ReductionResult {
        val disposition = acceptCursor(delivery.cursor)
        if (disposition != DeliveryDisposition.APPLIED) {
            return ReductionResult(snapshotLocked(), disposition)
        }
        when (val event = delivery.event) {
            is ServerEvent.AccountLoginCompleted -> applyLoginCompleted(event)
            is ServerEvent.AccountUpdated -> applyAccountUpdated(event)
            is ServerEvent.ThreadStarted -> mergeThreadSummary(event.thread)
            is ServerEvent.ThreadStatusChanged -> {
                thread(event.threadId).runtimeStatus = event.status
            }
            is ServerEvent.ThreadTokenUsageUpdated -> applyTokenUsage(event)
            is ServerEvent.TurnStarted -> applyTurnStarted(event)
            is ServerEvent.TurnCompleted -> applyTurnCompleted(event)
            is ServerEvent.ItemStarted -> applyItemStarted(event)
            is ServerEvent.ItemCompleted -> applyItemCompleted(event)
            is ServerEvent.AgentMessageDelta -> applyMessageDelta(event)
            is ServerEvent.CommandOutputDelta -> applyCommandDelta(event)
            // A settings snapshot is correlated with its request by the controller. It does
            // not replace the effective options of a turn that is already running.
            is ServerEvent.ThreadSettingsUpdated,
            ServerEvent.SkillsChanged, is ServerEvent.Raw -> Unit
        }
        trimState()
        return ReductionResult(snapshotLocked(), DeliveryDisposition.APPLIED)
    }

    /**
     * Called only after account/read plus thread/resume have rebuilt the state
     * for [generation]. The host then replays events beginning at
     * [lastHydratedSequence] + 1.
     */
    @Synchronized
    fun confirmRehydratedGeneration(
        generation: Long,
        lastHydratedSequence: Long = -1,
    ): SessionUiSnapshot {
        require(generation >= 0) { "Delivery generation must not be negative" }
        require(lastHydratedSequence >= -1) { "Hydrated sequence must be at least -1" }
        if (pendingGeneration != null && pendingGeneration != generation) {
            throw CrossCorrelationException("Rehydration confirmed for the wrong generation")
        }
        if (activeGeneration != null && generation < activeGeneration!!) {
            throw CrossCorrelationException("Cannot rehydrate an older generation")
        }
        if (pendingGeneration != null && !recoveryAccountRead) {
            throw CrossCorrelationException("account/read must complete before rehydration")
        }
        requiredRecoveryThreadId?.let { requiredThread ->
            if (requiredThread !in recoveredThreadIds) {
                throw CrossCorrelationException(
                    "thread/resume must complete before rehydration",
                )
            }
        }
        activeGeneration = generation
        lastSequence = lastHydratedSequence.takeIf { it >= 0 }
        pendingGeneration = null
        recoveryAccountRead = false
        requiredRecoveryThreadId = null
        recoveredThreadIds.clear()
        clearTokenUsageCorrelation()
        return snapshotLocked()
    }

    @Synchronized
    fun snapshot(): SessionUiSnapshot = snapshotLocked()

    /** Read the single selected-thread measurement without rebuilding conversation snapshots. */
    @Synchronized
    internal fun currentThreadTokenUsage(): ThreadTokenUsageSnapshot? = tokenUsage

    /** The controller knows a runtime generation has changed before any event can be replayed. */
    @Synchronized
    internal fun clearTokenUsageForRuntimeChange() = clearTokenUsageCorrelation()

    private fun acceptCursor(cursor: DeliveryCursor): DeliveryDisposition {
        val active = activeGeneration
        if (active == null) {
            activeGeneration = cursor.generation
            lastSequence = cursor.sequence
            return DeliveryDisposition.APPLIED
        }
        if (pendingGeneration != null) {
            return if (cursor.generation < pendingGeneration!!) {
                DeliveryDisposition.STALE_REJECTED
            } else {
                if (cursor.generation > pendingGeneration!!) {
                    requireRehydration(cursor.generation)
                }
                DeliveryDisposition.REHYDRATION_REQUIRED
            }
        }
        if (cursor.generation < active) return DeliveryDisposition.STALE_REJECTED
        if (cursor.generation > active) {
            requireRehydration(cursor.generation)
            return DeliveryDisposition.REHYDRATION_REQUIRED
        }
        val previous = lastSequence
        if (previous == cursor.sequence) return DeliveryDisposition.DUPLICATE_IGNORED
        if (previous != null && cursor.sequence < previous) {
            return DeliveryDisposition.STALE_REJECTED
        }
        // The Binder sequence covers runtime state callbacks, responses and
        // notifications. This reducer sees only notifications, so gaps are
        // expected; the transport owns liveness/order validation upstream.
        lastSequence = cursor.sequence
        return DeliveryDisposition.APPLIED
    }

    private fun applyResult(result: AppServerResult) {
        when (result) {
            is AccountReadResult -> {
                account = if (result.account == null) {
                    clearTokenUsageCorrelation()
                    MutableAccount(phase = AccountPhase.SIGNED_OUT)
                } else {
                    MutableAccount(
                        phase = AccountPhase.SIGNED_IN,
                        identity = result.account,
                    )
                }
                if (pendingGeneration != null) recoveryAccountRead = true
            }
            is DeviceCodeLoginResult -> {
                account.phase = AccountPhase.LOGIN_PENDING
                account.pendingLoginId = result.loginId
                account.error = null
            }
            AccountLogoutResult -> {
                account = MutableAccount(phase = AccountPhase.SIGNED_OUT)
                clearTokenUsageCorrelation()
            }
            is ThreadStartResult -> {
                selectThread(result.threadId)
                val thread = thread(result.threadId)
                thread.runtimeStatus = ThreadStatusSnapshot(ThreadRuntimeStatus.IDLE)
                // ThreadStartParams has no effort. Do not claim full effective
                // DispatchOptions until a correlated turn/start proves them.
                thread.effectiveOptions = null
            }
            is ThreadResumeResult -> {
                selectThread(result.thread.id)
                mergeThreadSummary(result.thread)
                thread(result.thread.id).effectiveOptions = null
                if (pendingGeneration != null) recoveredThreadIds.add(result.thread.id)
            }
            is ThreadListResult -> result.threads.forEach(::mergeThreadSummary)
            is TurnStartResult -> {
                selectThread(result.threadId)
                val thread = thread(result.threadId)
                thread.effectiveOptions = result.effectiveOptions
                // A very short turn can complete before its correlated start response arrives.
                // That authoritative response can establish metrics ownership without reopening it.
                if (thread.tokenUsageLatestTurnId == null &&
                    thread.turns[result.turnId]?.status?.isTerminal() == true &&
                    account.phase != AccountPhase.SIGNED_OUT
                ) {
                    thread.tokenUsageLatestTurnId = result.turnId
                }
                updateTurn(thread, result.turnId, result.status, error = null)
            }
            is TurnSteerResult -> {
                selectThread(result.threadId)
                val thread = thread(result.threadId)
                // A cold-resume steer acknowledges only identity, not thread-default or
                // active-turn model settings. Preserve only separately proven options.
                result.effectiveOptions?.let { thread.effectiveOptions = it }
                updateTurn(thread, result.turnId, TurnStatus.IN_PROGRESS, error = null)
            }
            is TurnInterruptResult -> {
                val thread = thread(result.threadId)
                val turn = thread.turns[result.turnId]
                if (turn != null && !turn.status.isTerminal()) {
                    turn.status = TurnStatus.INTERRUPTED
                }
            }
            is InitializeResult,
            is ModelListResult,
            ThreadMemoryModeSetResult,
            ThreadSettingsUpdateResult,
            is SkillsListResult,
            is ExtensionAppServerResult,
            -> Unit
        }
        trimState()
    }

    private fun applyFailure(response: CorrelatedResponse.Failure) {
        if (response.method == AppServerMethod.ACCOUNT_LOGIN_START) {
            account.phase = AccountPhase.ERROR
            account.pendingLoginId = null
            account.error = boundedText(response.error.message, 16_384)
        }
    }

    private fun applyLoginCompleted(event: ServerEvent.AccountLoginCompleted) {
        val pending = account.pendingLoginId
        if (pending == null || (event.loginId != null && event.loginId != pending)) {
            return // stale completion from an earlier login attempt
        }
        account.pendingLoginId = null
        if (event.success) {
            account.phase = AccountPhase.SIGNED_IN
            account.error = null
        } else {
            account.phase = AccountPhase.ERROR
            account.error = boundedText(event.error ?: "Login failed", 16_384)
        }
    }

    private fun applyAccountUpdated(event: ServerEvent.AccountUpdated) {
        account.authMode = event.authMode
        account.planType = event.planType
        account.pendingLoginId = null
        account.error = null
        account.phase = if (event.authMode == null) {
            clearTokenUsageCorrelation()
            AccountPhase.SIGNED_OUT
        } else {
            AccountPhase.SIGNED_IN
        }
    }

    private fun applyTokenUsage(event: ServerEvent.ThreadTokenUsageUpdated) {
        if (account.phase == AccountPhase.SIGNED_OUT || event.threadId != currentThreadId) return
        // Telemetry never creates a thread or establishes turn ownership by itself.
        val thread = threads[event.threadId] ?: return
        if (event.turnId != thread.tokenUsageLatestTurnId || event.turnId !in thread.turns) return
        if (thread.currentTurnId != null && thread.currentTurnId != event.turnId) return
        val previous = tokenUsage
        if (previous == event.tokenUsage) return
        if (previous != null && !event.tokenUsage.total.doesNotPrecede(previous.total)) return
        tokenUsage = event.tokenUsage
    }

    private fun selectThread(threadId: String) {
        if (currentThreadId != threadId) tokenUsage = null
        currentThreadId = threadId
    }

    private fun clearTokenUsageCorrelation() {
        tokenUsage = null
        threads.values.forEach { it.tokenUsageLatestTurnId = null }
    }

    private fun applyTurnStarted(event: ServerEvent.TurnStarted) {
        val thread = thread(event.threadId)
        val existing = thread.turns[event.turn.id]
        if (existing?.status?.isTerminal() == true) return
        updateTurn(thread, event.turn.id, event.turn.status, event.turn.errorMessage)
    }

    private fun applyTurnCompleted(event: ServerEvent.TurnCompleted) {
        val thread = thread(event.threadId)
        updateTurn(thread, event.turn.id, event.turn.status, event.turn.errorMessage)
    }

    private fun applyItemStarted(event: ServerEvent.ItemStarted) {
        val thread = thread(event.threadId)
        when (val item = event.item) {
            is StreamItem.AgentMessage -> {
                val existing = thread.messages[item.id]
                if (existing?.complete == true) return
                val message = existing ?: MutableMessage(
                    itemId = item.id,
                    turnId = event.turnId,
                    order = event.startedAtMillis,
                ).also { thread.messages[item.id] = it }
                if (message.text.isEmpty()) message.text = boundedMessage(item.text)
                message.phase = item.phase ?: message.phase
            }
            is StreamItem.Command,
            is StreamItem.McpTool,
            is StreamItem.DynamicTool,
            -> upsertTool(thread, event.turnId, item, event.startedAtMillis, complete = false)
            is StreamItem.Other -> Unit
        }
    }

    private fun applyItemCompleted(event: ServerEvent.ItemCompleted) {
        val thread = thread(event.threadId)
        when (val item = event.item) {
            is StreamItem.AgentMessage -> {
                val message = thread.messages[item.id] ?: MutableMessage(
                    itemId = item.id,
                    turnId = event.turnId,
                    order = event.completedAtMillis,
                ).also { thread.messages[item.id] = it }
                message.text = boundedMessage(item.text)
                message.phase = item.phase
                message.complete = true
                message.order = minOf(message.order, event.completedAtMillis)
            }
            is StreamItem.Command,
            is StreamItem.McpTool,
            is StreamItem.DynamicTool,
            -> upsertTool(thread, event.turnId, item, event.completedAtMillis, complete = true)
            is StreamItem.Other -> Unit
        }
    }

    private fun applyMessageDelta(event: ServerEvent.AgentMessageDelta) {
        val thread = thread(event.threadId)
        val message = thread.messages[event.itemId] ?: MutableMessage(
            itemId = event.itemId,
            turnId = event.turnId,
            order = thread.nextSyntheticOrder(),
        ).also { thread.messages[event.itemId] = it }
        if (message.complete) return
        message.text = boundedMessage(message.text + event.delta)
    }

    private fun applyCommandDelta(event: ServerEvent.CommandOutputDelta) {
        val thread = thread(event.threadId)
        val tool = thread.tools[event.itemId] ?: MutableTool(
            itemId = event.itemId,
            turnId = event.turnId,
            type = "commandExecution",
            label = "Command",
            status = ToolStatus.IN_PROGRESS,
            order = thread.nextSyntheticOrder(),
        ).also { thread.tools[event.itemId] = it }
        if (tool.complete) return
        tool.output = boundedText(
            (tool.output ?: "") + event.delta,
            ProtocolLimits.MAX_UI_TOOL_OUTPUT_CHARS,
        )
    }

    private fun upsertTool(
        thread: MutableThread,
        turnId: String,
        item: StreamItem,
        order: Long,
        complete: Boolean,
    ) {
        val existing = thread.tools[item.id]
        if (existing?.complete == true && !complete) return
        val status: ToolStatus
        val label: String
        val output: String?
        when (item) {
            is StreamItem.Command -> {
                status = item.status
                label = item.command
                output = item.aggregatedOutput
            }
            is StreamItem.McpTool -> {
                status = item.status
                label = "${item.server}: ${item.tool}"
                output = null
            }
            is StreamItem.DynamicTool -> {
                status = item.status
                label = item.tool
                output = null
            }
            else -> return
        }
        val tool = existing ?: MutableTool(
            itemId = item.id,
            turnId = turnId,
            type = item.type,
            label = label,
            status = status,
            order = order,
        ).also { thread.tools[item.id] = it }
        tool.label = boundedText(label, 8_192)
        tool.status = status
        if (output != null) {
            tool.output = boundedText(output, ProtocolLimits.MAX_UI_TOOL_OUTPUT_CHARS)
        }
        tool.complete = complete || status != ToolStatus.IN_PROGRESS
        tool.order = minOf(tool.order, order)
    }

    private fun updateTurn(
        thread: MutableThread,
        turnId: String,
        status: TurnStatus,
        error: String?,
    ) {
        val existing = thread.turns[turnId]
        if (existing != null && existing.status.isTerminal() && !status.isTerminal()) return
        if (account.phase != AccountPhase.SIGNED_OUT && status == TurnStatus.IN_PROGRESS &&
            (existing == null || thread.tokenUsageLatestTurnId == null)
        ) {
            thread.tokenUsageLatestTurnId = turnId
        }
        val turn = existing ?: MutableTurn(turnId).also { thread.turns[turnId] = it }
        turn.status = status
        turn.error = error?.let { boundedText(it, 16_384) }
        thread.currentTurnId = when {
            status == TurnStatus.IN_PROGRESS -> turnId
            thread.currentTurnId == turnId -> null
            else -> thread.currentTurnId
        }
        thread.runtimeStatus = if (status == TurnStatus.IN_PROGRESS) {
            ThreadStatusSnapshot(ThreadRuntimeStatus.ACTIVE)
        } else {
            ThreadStatusSnapshot(ThreadRuntimeStatus.IDLE)
        }
    }

    private fun mergeThreadSummary(summary: ThreadSummary) {
        val thread = thread(summary.id)
        if (summary.updatedAtSeconds < thread.updatedAtSeconds) return
        thread.name = summary.name
        thread.preview = boundedText(summary.preview, 16_384)
        thread.updatedAtSeconds = summary.updatedAtSeconds
        thread.runtimeStatus = summary.status
    }

    private fun thread(id: String): MutableThread = threads.getOrPut(id) { MutableThread(id) }

    private fun requireRehydration(generation: Long) {
        clearTokenUsageCorrelation()
        pendingGeneration = generation
        recoveryAccountRead = false
        requiredRecoveryThreadId = currentThreadId
        recoveredThreadIds.clear()
    }

    private fun trimState() {
        threads.values.forEach { thread ->
            trimMap(thread.messages, ProtocolLimits.MAX_MESSAGES_PER_THREAD) { it.complete }
            trimMap(thread.tools, ProtocolLimits.MAX_TOOLS_PER_THREAD) { it.complete }
        }
    }

    private fun <T> trimMap(
        map: LinkedHashMap<String, T>,
        maximum: Int,
        removable: (T) -> Boolean,
    ) {
        while (map.size > maximum) {
            val candidate = map.entries.firstOrNull { removable(it.value) } ?: map.entries.first()
            map.remove(candidate.key)
        }
    }

    private fun snapshotLocked(): SessionUiSnapshot = SessionUiSnapshot(
        account = AccountUiSnapshot(
            phase = account.phase,
            identity = account.identity,
            authMode = account.authMode,
            planType = account.planType,
            pendingLoginId = account.pendingLoginId,
            error = account.error,
        ),
        currentThreadId = currentThreadId,
        threads = threads.values
            .sortedByDescending { it.updatedAtSeconds }
            .map { thread ->
                thread.snapshot(if (thread.id == currentThreadId) tokenUsage else null)
            },
        delivery = DeliveryUiSnapshot(
            generation = activeGeneration,
            lastSequence = lastSequence,
            pendingGeneration = pendingGeneration,
            rehydrationRequired = pendingGeneration != null,
        ),
    )

    private data class MutableAccount(
        var phase: AccountPhase = AccountPhase.UNKNOWN,
        var identity: AccountIdentity? = null,
        var authMode: AccountAuthMode? = null,
        var planType: AccountPlanType? = null,
        var pendingLoginId: String? = null,
        var error: String? = null,
    )

    private class MutableThread(val id: String) {
        var name: String? = null
        var preview: String = ""
        var updatedAtSeconds: Long = 0
        var runtimeStatus = ThreadStatusSnapshot(ThreadRuntimeStatus.NOT_LOADED)
        var effectiveOptions: DispatchOptions? = null
        var currentTurnId: String? = null
        var tokenUsageLatestTurnId: String? = null
        val turns = LinkedHashMap<String, MutableTurn>()
        val messages = LinkedHashMap<String, MutableMessage>()
        val tools = LinkedHashMap<String, MutableTool>()
        private var syntheticOrder: Long = Long.MAX_VALUE / 2

        fun nextSyntheticOrder(): Long = syntheticOrder++

        fun snapshot(tokenUsage: ThreadTokenUsageSnapshot?): ThreadUiSnapshot {
            val current = currentTurnId?.let(turns::get)
            return ThreadUiSnapshot(
                threadId = id,
                name = name,
                preview = preview,
                runtimeStatus = runtimeStatus,
                effectiveOptions = effectiveOptions,
                currentTurn = current?.let { TurnUiSnapshot(it.id, it.status, it.error) },
                messages = messages.values.sortedBy { it.order }.map { it.snapshot() },
                tools = tools.values.sortedBy { it.order }.map { it.snapshot() },
                tokenUsage = tokenUsage,
            )
        }
    }

    private data class MutableTurn(
        val id: String,
        var status: TurnStatus = TurnStatus.IN_PROGRESS,
        var error: String? = null,
    )

    private data class MutableMessage(
        val itemId: String,
        val turnId: String,
        var text: String = "",
        var phase: AgentMessagePhase? = null,
        var complete: Boolean = false,
        var order: Long,
    ) {
        fun snapshot(): MessageUiSnapshot = MessageUiSnapshot(
            itemId = itemId,
            turnId = turnId,
            text = text,
            phase = phase,
            complete = complete,
        )
    }

    private data class MutableTool(
        val itemId: String,
        val turnId: String,
        val type: String,
        var label: String,
        var status: ToolStatus,
        var output: String? = null,
        var complete: Boolean = false,
        var order: Long,
    ) {
        fun snapshot(): ToolUiSnapshot = ToolUiSnapshot(
            itemId = itemId,
            turnId = turnId,
            type = type,
            label = label,
            status = status,
            output = output,
            complete = complete,
        )
    }
}

private fun TurnStatus.isTerminal(): Boolean = this != TurnStatus.IN_PROGRESS

private fun boundedMessage(value: String): String = boundedText(
    value,
    ProtocolLimits.MAX_UI_MESSAGE_CHARS,
)

private fun boundedText(value: String, maxChars: Int): String = if (value.length <= maxChars) {
    value
} else {
    value.take(maxChars - 1) + "…"
}
