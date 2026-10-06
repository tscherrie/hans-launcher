package ai.hans.standard.notifications.agentchannel

import java.util.UUID

/** No LLM decision, title match, or prefix alone can enroll or authorize this channel. */
class WhatsAppAgentChannel(
    private val storage: AgentChannelStorage,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    @Synchronized fun status(): AgentChannelStatus {
        val state = read() ?: return AgentChannelStatus(false, null, 0, 0)
        return AgentChannelStatus(true, state.binding,
            state.requests.count { it.status == AgentChannelWorkflowStatus.READY && current(state, it) },
            // An admitted request is not expired input. Even legacy uncorrelated dispatches
            // remain visibly unconfirmed until positive completion or an explicit local cancel.
            state.requests.count { admittedUnderCurrentBinding(state, it) && it.status in UNSETTLED_STATUSES },
            state.retrievalHints.count { it.bindingGeneration == state.binding?.generation &&
                fresh(it.observedAtEpochMillis, AgentChannelLimits.CANDIDATE_TTL_MILLIS) })
    }

    @Synchronized fun listEnrollmentCandidates(): List<AgentChannelEnrollmentCandidate> =
        read()?.enrollmentCandidates?.filter { fresh(it.observedAtEpochMillis, AgentChannelLimits.CANDIDATE_TTL_MILLIS) }.orEmpty()

    @Synchronized fun pendingReceipts(): List<AgentChannelRequestReceipt> {
        val state = read() ?: return emptyList()
        return state.requests.filter { it.status == AgentChannelWorkflowStatus.READY && current(state, it) }
    }

    @Synchronized fun unsettledReceipts(): List<AgentChannelRequestReceipt> {
        val state = read() ?: return emptyList()
        return state.requests.filter { admittedUnderCurrentBinding(state, it) && it.status in UNSETTLED_STATUSES }
    }

    @Synchronized fun retrievalHints(): List<AgentChannelRetrievalHint> = read()?.let { state ->
        state.retrievalHints.filter { it.bindingGeneration == state.binding?.generation &&
            fresh(it.observedAtEpochMillis, AgentChannelLimits.CANDIDATE_TTL_MILLIS) }
    }.orEmpty()

    /** Call only after notification capture/privacy consent has accepted the source. */
    @Synchronized fun observe(source: WhatsAppNotificationSource): AgentChannelIngressResult {
        var state = read() ?: return AgentChannelIngressResult(failureCode = "storage_unavailable")
        if (source.notificationKey.isBlank() || source.notificationKey.length > 2_048 ||
            source.messages.size > AgentChannelLimits.MAX_MESSAGES) return AgentChannelIngressResult(failureCode = "source_bounds")
        val identity = source.identity() ?: return unprovenSourceHint(state, source)
        val now = nowEpochMillis()
        // No body is retained for enrollment: the source identity and visible label suffice.
        val safeSource = source.copy(messages = emptyList(), messagesTruncated = false)
        val previous = state.enrollmentCandidates.firstOrNull { it.sourceIdentity == identity }
        val candidate = AgentChannelEnrollmentCandidate(previous?.id ?: newId(), identity, safeSource, now)
        if (source.messages.any { ownMessage(source, it) } && source.messages.all { ownMessage(source, it) }) {
            state = state.copy(enrollmentCandidates = (state.enrollmentCandidates.filter {
                it.sourceIdentity != identity && fresh(it.observedAtEpochMillis, AgentChannelLimits.CANDIDATE_TTL_MILLIS)
            } + candidate).takeLast(AgentChannelLimits.MAX_ENROLLMENT_CANDIDATES))
        }
        val binding = state.binding
        if (binding == null || binding.sourceIdentity != identity) {
            return if (write(state)) AgentChannelIngressResult(ignoredCount = source.messages.size)
            else AgentChannelIngressResult(failureCode = "storage_unavailable")
        }
        if (binding.source.ownPersonIdentity != null && source.ownPersonIdentity != null &&
            binding.source.ownPersonIdentity != source.ownPersonIdentity)
            return AgentChannelIngressResult(ignoredCount = source.messages.size)
        val ready = mutableListOf<AgentChannelRequestReceipt>()
        val hints = mutableListOf<AgentChannelRetrievalHint>()
        var ignored = 0
        var failure: String? = null
        val requests = state.requests.map {
            if (it.status == AgentChannelWorkflowStatus.READY &&
                !fresh(it.messageAtEpochMillis, AgentChannelLimits.REQUEST_TTL_MILLIS)) cancel(it) else it
        }.filter {
            // Unsettled work never disappears because intake TTL elapsed. The bounded ledger
            // fails closed at capacity until positive settlement or explicit local cancellation.
            it.status in setOf(AgentChannelWorkflowStatus.READY, AgentChannelWorkflowStatus.CLAIMED,
                AgentChannelWorkflowStatus.DISPATCHED, AgentChannelWorkflowStatus.UNCERTAIN) ||
                fresh(it.settledAtEpochMillis ?: it.receivedAtEpochMillis, AgentChannelLimits.TOMBSTONE_TTL_MILLIS)
        }.toMutableList()
        fun hint(reason: String) {
            val id = channelDigest(binding.generation, source.notificationKey, reason)
            if (state.retrievalHints.none { it.id == id }) hints += AgentChannelRetrievalHint(
                id, binding.generation, identity, source.notificationKey, source.shortcutId!!, reason, now)
        }
        if (source.messagesTruncated) hint("message_list_incomplete")
        source.messages.forEach { message ->
            // An incoming notification may contain a historical aggregate. Never replay it on binding.
            if (message.timestampEpochMillis <= binding.confirmedAtEpochMillis ||
                !fresh(message.timestampEpochMillis, AgentChannelLimits.REQUEST_TTL_MILLIS)) { ignored++; return@forEach }
            if (!ownMessage(source, message)) {
                ignored++; return@forEach
            }
            when (val parsed = AgentChannelPrefixParser.parse(message)) {
                is AgentChannelParseResult.Request -> {
                    if (source.messagesTruncated) { hint("message_list_incomplete"); return@forEach }
                    val key = if (parsed.requestId != null) channelDigest(identity, "id", parsed.requestId)
                        // Without an explicit ID, repeated identical content is deliberately suppressed
                        // even if the app refreshes message timestamps. New IDs permit intentional repeats.
                        else channelDigest(identity, "message", message.text)
                    if (requests.any { it.idempotencyKey == key }) { ignored++; return@forEach }
                    if (requests.size >= AgentChannelLimits.MAX_REQUESTS) { failure = "ledger_full"; return@forEach }
                    val receipt = AgentChannelRequestReceipt(newId(), binding.generation, identity, key,
                        source.notificationKey, parsed.requestId, parsed.body, now, message.timestampEpochMillis,
                        AgentChannelWorkflowStatus.READY)
                    requests += receipt
                    ready += receipt
                }
                is AgentChannelParseResult.RetrievalRequired -> hint(parsed.reason)
                AgentChannelParseResult.Ignore -> ignored++
            }
        }
        state = state.copy(requests = requests, retrievalHints = (state.retrievalHints.filter {
            it.bindingGeneration == binding.generation && fresh(it.observedAtEpochMillis, AgentChannelLimits.CANDIDATE_TTL_MILLIS)
        } + hints).takeLast(AgentChannelLimits.MAX_RETRIEVAL_HINTS))
        return if (write(state)) AgentChannelIngressResult(ready, hints, ignored, failure)
        else AgentChannelIngressResult(failureCode = "storage_unavailable")
    }

    /** Route only an entirely admitted command envelope away from the ordinary push hook. */
    @Synchronized fun isExclusivelyAdmittedAgentSource(source: WhatsAppNotificationSource): Boolean {
        val state = read() ?: return false
        val identity = source.identity() ?: return false
        val binding = state.binding ?: return false
        if (identity != binding.sourceIdentity || source.messagesTruncated || source.messages.isEmpty()) return false
        return source.messages.all { message ->
            if (!ownMessage(source, message)) return@all false
            val parsed = AgentChannelPrefixParser.parse(message) as? AgentChannelParseResult.Request ?: return@all false
            val key = if (parsed.requestId != null) channelDigest(identity, "id", parsed.requestId)
                else channelDigest(identity, "message", message.text)
            state.requests.any { admittedUnderCurrentBinding(state, it) && it.idempotencyKey == key }
        }
    }

    /** Explicit LOCAL UI confirmation that this app-issued identity is the actual self-chat. */
    @Synchronized fun confirmSelfChat(candidateId: String): Boolean {
        val state = read() ?: return false
        val candidate = state.enrollmentCandidates.firstOrNull { it.id == candidateId &&
            fresh(it.observedAtEpochMillis, AgentChannelLimits.CANDIDATE_TTL_MILLIS) } ?: return false
        if (candidate.source.identity() != candidate.sourceIdentity) return false
        val binding = AgentChannelBinding(newId(), candidate.sourceIdentity, nowEpochMillis(), candidate.source)
        return write(state.copy(binding = binding, requests = state.requests.map(::cancel), retrievalHints = emptyList()))
    }

    @Synchronized fun revoke(): Boolean = read()?.let { state ->
        write(state.copy(binding = null, enrollmentCandidates = emptyList(), requests = state.requests.map(::cancel), retrievalHints = emptyList()))
    } ?: false

    /** For an explicit notification-data privacy purge; reopening still requires a new local binding. */
    @Synchronized fun clearPrivateData(): Boolean = write(AgentChannelState())

    @Synchronized fun cancelPending(): Boolean = read()?.let { state ->
        write(state.copy(requests = state.requests.map(::cancel), retrievalHints = emptyList()))
    } ?: false

    /** Durable claim BEFORE dispatch. A crash/uncertain acceptance must never auto-retry this request. */
    @Synchronized fun claim(receiptId: String): AgentChannelRequestReceipt? {
        val state = read() ?: return null
        val receipt = state.requests.firstOrNull { it.id == receiptId &&
            it.status == AgentChannelWorkflowStatus.READY && current(state, it) } ?: return null
        val claimed = receipt.copy(status = AgentChannelWorkflowStatus.CLAIMED)
        return if (write(state.copy(requests = state.requests.map { if (it.id == receiptId) claimed else it }))) claimed else null
    }

    @Synchronized fun isCurrent(receipt: AgentChannelRequestReceipt): Boolean {
        val state = read() ?: return false
        return state.requests.any { it == receipt && current(state, it) && it.status in setOf(
            AgentChannelWorkflowStatus.CLAIMED, AgentChannelWorkflowStatus.DISPATCHED) }
    }

    /** Caller must have positive RejectedBeforeTransport proof; never use after timeout/unknown transport. */
    @Synchronized fun releaseUnsentClaim(receiptId: String): Boolean {
        val state = read() ?: return false
        val receipt = state.requests.firstOrNull { it.id == receiptId &&
            it.status == AgentChannelWorkflowStatus.CLAIMED && current(state, it) } ?: return false
        return write(state.copy(requests = state.requests.map {
            if (it.id == receipt.id) it.copy(status = AgentChannelWorkflowStatus.READY, dispatchCorrelation = null) else it
        }))
    }

    /** Host transaction calls this before any frame can leave; failures prevent dispatch. */
    @Synchronized fun prepareDispatch(receiptId: String, threadId: String, clientUserMessageId: String): Boolean {
        val state = read() ?: return false
        val receipt = state.requests.firstOrNull { it.id == receiptId &&
            it.status == AgentChannelWorkflowStatus.CLAIMED && current(state, it) } ?: return false
        val correlation = runCatching { AgentChannelDispatchCorrelation(threadId, clientUserMessageId) }.getOrNull() ?: return false
        if (clientUserMessageId != "whatsapp-agent-${receipt.id}") return false
        if (receipt.dispatchCorrelation != null) return receipt.dispatchCorrelation == correlation
        return write(state.copy(requests = state.requests.map {
            if (it.id == receiptId) it.copy(dispatchCorrelation = correlation) else it
        }))
    }

    @Synchronized fun markDispatched(receiptId: String, acceptedClientUserMessageId: String? = null): Boolean {
        val state = read() ?: return false
        val receipt = state.requests.firstOrNull { it.id == receiptId &&
            it.status == AgentChannelWorkflowStatus.CLAIMED && admittedUnderCurrentBinding(state, it) } ?: return false
        if (acceptedClientUserMessageId != null &&
            acceptedClientUserMessageId != "whatsapp-agent-${receipt.id}") return false
        if (acceptedClientUserMessageId != null && receipt.dispatchCorrelation != null &&
            receipt.dispatchCorrelation.clientUserMessageId != acceptedClientUserMessageId) return false
        return transition(receiptId, setOf(AgentChannelWorkflowStatus.CLAIMED), AgentChannelWorkflowStatus.DISPATCHED)
    }

    /** Only a positive exact App Server receipt, live or recovered, may establish this turn. */
    @Synchronized fun recordAcceptedTurn(receiptId: String, threadId: String,
        clientUserMessageId: String, turnId: String): Boolean {
        val state = read() ?: return false
        val receipt = state.requests.firstOrNull { it.id == receiptId &&
            it.status in UNSETTLED_STATUSES && admittedUnderCurrentBinding(state, it) } ?: return false
        val previous = receipt.dispatchCorrelation ?: return false
        if (previous.threadId != threadId || previous.clientUserMessageId != clientUserMessageId ||
            (previous.turnId != null && previous.turnId != turnId)) return false
        val correlation = runCatching { previous.copy(turnId = turnId) }.getOrNull() ?: return false
        val updated = receipt.copy(status = AgentChannelWorkflowStatus.DISPATCHED, dispatchCorrelation = correlation)
        if (updated == receipt) return true
        return write(state.copy(requests = state.requests.map { if (it.id == receiptId) updated else it }))
    }
    @Synchronized fun markUncertain(receiptId: String): Boolean = transition(receiptId,
        setOf(AgentChannelWorkflowStatus.CLAIMED, AgentChannelWorkflowStatus.DISPATCHED), AgentChannelWorkflowStatus.UNCERTAIN)
    @Synchronized fun complete(receiptId: String, success: Boolean): Boolean = transition(receiptId,
        setOf(AgentChannelWorkflowStatus.CLAIMED, AgentChannelWorkflowStatus.DISPATCHED, AgentChannelWorkflowStatus.UNCERTAIN),
        if (success) AgentChannelWorkflowStatus.COMPLETED else AgentChannelWorkflowStatus.FAILED)

    private fun transition(id: String, from: Set<AgentChannelWorkflowStatus>, to: AgentChannelWorkflowStatus): Boolean {
        val state = read() ?: return false
        val receipt = state.requests.firstOrNull { it.id == id && it.status in from } ?: return false
        if (!admittedUnderCurrentBinding(state, receipt)) return false
        return write(state.copy(requests = state.requests.map { if (it.id == id) it.copy(status = to,
            requestText = if (to in setOf(AgentChannelWorkflowStatus.COMPLETED, AgentChannelWorkflowStatus.FAILED)) "" else it.requestText,
            settledAtEpochMillis = if (to in setOf(AgentChannelWorkflowStatus.COMPLETED, AgentChannelWorkflowStatus.FAILED))
                nowEpochMillis() else null) else it }))
    }

    private fun cancel(receipt: AgentChannelRequestReceipt): AgentChannelRequestReceipt =
        if (receipt.status in setOf(AgentChannelWorkflowStatus.COMPLETED, AgentChannelWorkflowStatus.FAILED,
                AgentChannelWorkflowStatus.CANCELLED)) receipt
        else receipt.copy(status = AgentChannelWorkflowStatus.CANCELLED, requestText = "",
            dispatchCorrelation = null, settledAtEpochMillis = nowEpochMillis())
    private fun ownMessage(source: WhatsAppNotificationSource, message: WhatsAppNotificationMessage): Boolean =
        // Exact chat enrollment is the authorization boundary. Stable Person metadata, when both
        // sides supply it, can reject contradictions; absence never falls back to a display name.
        source.ownPersonIdentity == null || message.senderIdentity == null ||
            message.senderIdentity == source.ownPersonIdentity
    private fun unprovenSourceHint(state: AgentChannelState, source: WhatsAppNotificationSource): AgentChannelIngressResult {
        val binding = state.binding ?: return AgentChannelIngressResult(ignoredCount = source.messages.size)
        val enrolled = binding.source
        if (source.packageName != WHATSAPP_PACKAGE || source.androidUserId != enrolled.androidUserId ||
            source.postingUid != enrolled.postingUid || source.shortcutId != enrolled.shortcutId ||
            source.isGroupConversation || source.isGroupSummary ||
            source.messages.none { it.text.startsWith("[Codex:]") && it.timestampEpochMillis > binding.confirmedAtEpochMillis &&
                fresh(it.timestampEpochMillis, AgentChannelLimits.REQUEST_TTL_MILLIS) })
            return AgentChannelIngressResult(ignoredCount = source.messages.size)
        val hint = AgentChannelRetrievalHint(channelDigest(binding.generation, source.notificationKey, "unproven_metadata"),
            binding.generation, binding.sourceIdentity, source.notificationKey, enrolled.shortcutId!!,
            "source_metadata_incomplete", nowEpochMillis())
        if (state.retrievalHints.any { it.id == hint.id }) return AgentChannelIngressResult(ignoredCount = source.messages.size)
        return if (write(state.copy(retrievalHints = (state.retrievalHints + hint).takeLast(AgentChannelLimits.MAX_RETRIEVAL_HINTS))))
            AgentChannelIngressResult(retrievalHints = listOf(hint)) else AgentChannelIngressResult(failureCode = "storage_unavailable")
    }
    private fun current(state: AgentChannelState, receipt: AgentChannelRequestReceipt): Boolean =
        admittedUnderCurrentBinding(state, receipt) && fresh(receipt.messageAtEpochMillis, AgentChannelLimits.REQUEST_TTL_MILLIS)
    private fun admittedUnderCurrentBinding(state: AgentChannelState, receipt: AgentChannelRequestReceipt): Boolean =
        state.binding?.generation == receipt.bindingGeneration && state.binding.sourceIdentity == receipt.sourceIdentity &&
            receipt.messageAtEpochMillis > state.binding.confirmedAtEpochMillis
    private fun fresh(time: Long, ttl: Long): Boolean = time >= 0 && nowEpochMillis() >= time && nowEpochMillis() - time <= ttl
    private fun read(): AgentChannelState? = runCatching { storage.read() }.getOrNull()
    private fun write(state: AgentChannelState): Boolean = runCatching { storage.write(state); storage.read() == state }.getOrDefault(false)
    private fun newId(): String = UUID.randomUUID().toString()

    private companion object {
        val UNSETTLED_STATUSES = setOf(AgentChannelWorkflowStatus.CLAIMED,
            AgentChannelWorkflowStatus.DISPATCHED, AgentChannelWorkflowStatus.UNCERTAIN)
    }
}

sealed interface AgentChannelParseResult {
    data class Request(val body: String, val requestId: String?) : AgentChannelParseResult
    data class RetrievalRequired(val reason: String) : AgentChannelParseResult
    data object Ignore : AgentChannelParseResult
}

object AgentChannelPrefixParser {
    /** Optional ID syntax: [Codex:] [id:request-123] Do the task. Leading quotes/labels are not accepted. */
    fun parse(message: WhatsAppNotificationMessage): AgentChannelParseResult {
        val text = message.text
        if (!text.startsWith("[Codex:]")) return AgentChannelParseResult.Ignore
        if (message.truncated || text.toByteArray(Charsets.UTF_8).size > AgentChannelLimits.MAX_MESSAGE_BYTES ||
            text.trimEnd().endsWith("…") || text.trimEnd().endsWith("..."))
            return AgentChannelParseResult.RetrievalRequired("message_incomplete")
        if (message.hasAttachment) return AgentChannelParseResult.RetrievalRequired("attachment_requires_lookup")
        if (text.any { (it.isISOControl() && it !in "\n\r\t") || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' })
            return AgentChannelParseResult.Ignore
        var body = text.removePrefix("[Codex:]").trim()
        var id: String? = null
        if (body.startsWith("[id:")) {
            val match = Regex("^\\[id:([A-Za-z0-9_-]{1,64})]\\s+([\\s\\S]+)$").matchEntire(body)
                ?: return AgentChannelParseResult.Ignore
            id = match.groupValues[1]
            body = match.groupValues[2].trim()
        }
        if (body.isBlank()) return AgentChannelParseResult.Ignore
        if (body.toByteArray(Charsets.UTF_8).size > AgentChannelLimits.MAX_REQUEST_BYTES)
            return AgentChannelParseResult.RetrievalRequired("request_too_long")
        return AgentChannelParseResult.Request(body, id)
    }
}
