package ai.hans.standard.integration

import ai.hans.standard.codex.CodexRealtimeProtocol
import ai.hans.standard.codex.JsonContract
import java.util.UUID
import org.json.JSONObject

/** Passive, content-free receipts. These are never a capability or dispatch authority. */
data class CodexRealtimeDiagnostics(
    val state: CodexRealtimeState = CodexRealtimeState.IDLE,
    val started: Boolean = false,
    val stopAcknowledged: Boolean = false,
    val handoffCount: Int = 0,
    val userFinalCount: Int = 0,
    val assistantFinalCount: Int = 0,
    val lastNativePhase: CodexRealtimeNativePhase = CodexRealtimeNativePhase.NONE,
    val lastError: CodexRealtimeIssue? = null,
    val userDeltaCount: Int = 0,
    val userDeltaCharacters: Int = 0,
)

enum class CodexRealtimeState { IDLE, STARTING, ACTIVE, DRAINING, RECOVERY_REQUIRED, CLOSED }
enum class CodexRealtimeNativePhase {
    NONE, START_ACCEPTED, STARTED, SDP, USER_TRANSCRIPT, ASSISTANT_TRANSCRIPT,
    USER_ITEM, ASSISTANT_ITEM, HANDOFF, STOP_ACCEPTED, ERROR, CLOSED,
}

/**
 * Single-owner optional protocol domain. The controller serializes access and schedules only
 * one-shot active-session deadlines. No idle polling, auth-token reads, or model turn replay.
 */
internal class CodexRealtimeCoordinator(
    private val send: (generation: Long, wire: String) -> Boolean,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val scheduleAudioDeadline: ((() -> Unit) -> SetupDispatchDeadline)? = null,
    private val scheduleDrainDeadline: ((() -> Unit) -> SetupDispatchDeadline)? = null,
    private val onDrainRecoveryRequired: () -> Unit = {},
    /** The controller proves local interactive provenance before returning the current turn. */
    private val localHandoffTurn: (generation: Long, threadId: String) -> String? = { _, _ -> null },
) {
    private var active: Session? = null
    private var nextLease = 0L
    // Session-less notifications cannot be safely attributed after an ambiguous stop.
    // Refuse another call in that generation until an authoritative close arrives.
    private var draining: Session? = null
    // A native stop drains handoffs, but the resulting turn/started may arrive later.
    // Keep origin separate from the media lease; callers must additionally prove account
    // identity and that the Desktop relay has remained quiescent.
    private var origin: Session? = null
    private var lastSession: Session? = null
    private var workRevision = 0L
    private var latestWork: ai.hans.standard.voice.realtime.CodexTaskVoiceWorkState? = null
    // First owner is immutable, including across media closure and later calls. Terminal
    // tombstones prevent a replayed turn from being rebound after the controller's LRU expires.
    private val voiceBindings = LinkedHashMap<VoiceTurn, VoiceBinding>()
    private val ambiguousVoiceOrigins = LinkedHashSet<Pair<Long, String>>()
    private var voiceBindingCapacityExceeded = false

    fun workState(generation: Long?, threadId: String?, activeTurnId: String?, pendingDispatch: Boolean,
        terminal: ai.hans.standard.voice.realtime.CodexTaskVoiceTerminal? = null) {
        val session = active?.takeIf { it.generation == generation && it.threadId == threadId } ?: return
        val previous = latestWork
        val nextTerminal = terminal ?: previous?.terminal
        if (previous != null && previous.activeTurnId == activeTurnId && previous.pendingDispatch == pendingDispatch &&
            previous.terminal == nextTerminal) return
        val state = ai.hans.standard.voice.realtime.CodexTaskVoiceWorkState(
            ++workRevision, activeTurnId, pendingDispatch, nextTerminal)
        latestWork = state
        safely { session.callbacks.onWorkState(state) }
    }

    val hasPendingStart: Boolean get() = active?.let { !it.started || !it.sdpDelivered } == true
    val hasDrainingSession: Boolean get() = draining != null
    val requiresRecovery: Boolean get() = draining?.drainExpired == true
    val lease: Long? get() = active?.lease ?: draining?.lease

    fun ownsThread(generation: Long?, threadId: String?): Boolean = origin?.let {
        it.generation == generation && it.threadId == threadId && it.started &&
            (active === it || draining === it || it.handoffs.size > it.claimedTurns.size)
    } == true

    /** Bind observed main turns once. After close only unmatched handoff receipts retain origin. */
    fun claimTurnOrigin(generation: Long?, threadId: String?, turnId: String): Boolean {
        val session = origin ?: return false
        if (!ownsThread(generation, threadId)) return false
        if (session.claimedTurns.size >= MAX_ITEM_RECEIPTS) return false
        val firstClaim = session.claimedTurns.add(turnId)
        bindVoiceTurn(session, turnId, confirmedHandoff = session.unmatchedVoiceHandoffs > 0)
        if (firstClaim && session.unmatchedVoiceHandoffs > 0) session.unmatchedVoiceHandoffs--
        return true
    }

    /** Read-only capability lookup. In particular this never attributes a tool call to whatever
     * voice session happens to be active when the model finally invokes it. */
    fun voiceControlSessionIdFor(generation: Long, threadId: String, turnId: String): String? {
        val session = active?.takeIf { it.generation == generation && it.threadId == threadId &&
            it.started && !it.voiceControlRevoked } ?: return null
        if (voiceBindingCapacityExceeded || generation to threadId in ambiguousVoiceOrigins) return null
        val binding = voiceBindings[VoiceTurn(generation, threadId, turnId)] ?: return null
        return session.voiceControlSessionId?.takeIf {
            binding.lease == session.lease && binding.sessionId == it &&
                binding.confirmedHandoff && !binding.terminal
        }
    }

    fun completeVoiceTurn(generation: Long?, threadId: String, turnId: String) {
        if (generation == null) return
        voiceBindings[VoiceTurn(generation, threadId, turnId)]?.terminal = true
    }

    private fun bindVoiceTurn(session: Session, turnId: String, confirmedHandoff: Boolean,
        newHandoff: Boolean = false) {
        if (session.voiceControlRevoked || voiceBindingCapacityExceeded ||
            session.generation to session.threadId in ambiguousVoiceOrigins) return
        val key = VoiceTurn(session.generation, session.threadId, turnId)
        val existing = voiceBindings[key]
        if (existing != null) {
            // A turn steered during two separate calls cannot identify which call caused a
            // later tool invocation. Keep A's owner and fail closed for B, never overwrite.
            if (existing.lease == session.lease && !existing.terminal && confirmedHandoff) {
                existing.confirmedHandoff = true
                publishWorkBinding(session, turnId, existing, allowRepublish = newHandoff)
            }
            return
        }
        if (voiceBindings.size >= MAX_ITEM_RECEIPTS) {
            voiceBindingCapacityExceeded = true
            return
        }
        val binding = VoiceBinding(session.lease, session.voiceControlSessionId, confirmedHandoff)
        voiceBindings[key] = binding
        publishWorkBinding(session, turnId, binding)
    }

    private fun publishWorkBinding(session: Session, turnId: String, binding: VoiceBinding,
        allowRepublish: Boolean = false) {
        if (active !== session || !session.started || binding.sessionId == null ||
            !binding.confirmedHandoff || binding.terminal || (binding.displayPublished && !allowRepublish)) return
        binding.displayPublished = true
        safely { session.callbacks.onWorkBound(ai.hans.standard.voice.realtime.CodexVoiceWorkScope(session.threadId, turnId)) }
    }

    private fun observeVoiceHandoff(session: Session) {
        val turnId = runCatching { localHandoffTurn(session.generation, session.threadId) }.getOrNull()
        if (turnId == null) session.unmatchedVoiceHandoffs = incrementBounded(session.unmatchedVoiceHandoffs)
        else {
            bindVoiceTurn(session, turnId, confirmedHandoff = true, newHandoff = true)
        }
    }

    fun revokeOrigin() {
        origin = null
        lastSession?.invalidated = true
        lastSession?.voiceControlRevoked = true
    }

    fun claimUnroutedDictation(expectedLease: Long, generation: Long, threadId: String): Boolean {
        val session = lastSession ?: return false
        if (active != null || draining != null || session.lease != expectedLease ||
            session.generation != generation || session.threadId != threadId || !session.started || !session.sdpDelivered ||
            !session.closeConfirmed || session.stopRequestId == null || session.lastError != null ||
            session.invalidated || session.handoffs.isNotEmpty() || session.claimedTurns.isNotEmpty() || session.fallbackClaimed) return false
        session.fallbackClaimed = true
        return true
    }

    fun diagnostics(): CodexRealtimeDiagnostics {
        val session = active ?: draining ?: lastSession ?: return CodexRealtimeDiagnostics()
        val state = when {
            active === session -> if (session.started) CodexRealtimeState.ACTIVE else CodexRealtimeState.STARTING
            draining === session -> if (session.drainExpired) CodexRealtimeState.RECOVERY_REQUIRED else CodexRealtimeState.DRAINING
            else -> CodexRealtimeState.CLOSED
        }
        return CodexRealtimeDiagnostics(state, session.started, session.stopAcknowledged,
            session.handoffs.size, session.userFinalCount, session.assistantFinalCount,
            session.lastNativePhase, session.lastError, session.userDeltaCount, session.userDeltaCharacters)
    }

    fun start(generation: Long, threadId: String, offerSdp: String, prompt: String, voice: String?,
        callbacks: CodexRealtimeCallbacks, options: CodexRealtimeOptions = CodexRealtimeOptions()): Long? {
        if (active != null || draining != null) {
            safely { callbacks.onStartRejected() }
            safely { callbacks.onError(if (requiresRecovery) CodexRealtimeIssue.RECOVERY_REQUIRED else CodexRealtimeIssue.ALREADY_ACTIVE) }
            return null
        }
        val sessionId = newId()
        val requestId = CodexRealtimeProtocol.PREFIX + newId()
        val wire = runCatching {
            CodexRealtimeProtocol.start(requestId, threadId, sessionId, offerSdp, prompt, voice, options)
        }.getOrElse {
            safely { callbacks.onStartRejected() }
            safely { callbacks.onError(CodexRealtimeIssue.INVALID_REQUEST) }
            return null
        }
        // A drained handoff can still have a delayed turn/started. Without a native handoff ->
        // turn identifier, B must not acquire that possibly-A turn. Keep the thread fail-closed
        // until a new runtime generation (a fresh context has a distinct thread identity).
        lastSession?.takeIf { it.unmatchedVoiceHandoffs > 0 }?.let {
            if (ambiguousVoiceOrigins.size >= MAX_ITEM_RECEIPTS) voiceBindingCapacityExceeded = true
            else ambiguousVoiceOrigins += it.generation to it.threadId
        }
        val localVoiceId = runCatching { callbacks.voiceControlSessionId }.getOrNull()?.takeIf {
            it.length == 36 && runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false)
        }
        val session = Session(++nextLease, generation, threadId, sessionId, requestId, callbacks,
            voiceControlSessionId = localVoiceId)
        active = session
        latestWork = null
        lastSession = session
        if (!runCatching { send(generation, wire) }.getOrDefault(false)) {
            // A transport exception is ambiguous: revoke callbacks and attempt stop, not retry.
            fail(session, CodexRealtimeIssue.CONNECTION_FAILED)
            return null
        }
        return session.lease
    }

    fun appendAudio(expectedLease: Long, generation: Long, threadId: String, base64: String,
        sampleRateHz: Int, callback: (Result<Unit>) -> Unit): Boolean {
        val scheduler = scheduleAudioDeadline ?: return false
        val session = active?.takeIf {
            it.lease == expectedLease && it.generation == generation && it.threadId == threadId &&
                it.started && it.pendingAppend == null && it.audioFrames < MAX_AUDIO_FRAMES
        } ?: return false
        val id = CodexRealtimeProtocol.PREFIX + newId()
        val wire = runCatching { CodexRealtimeProtocol.appendAudio(id, threadId, base64, sampleRateHz) }
            .getOrNull() ?: return false
        val pending = PendingAppend(id, callback)
        session.pendingAppend = pending
        session.audioFrames++
        pending.deadline = runCatching { scheduler {
            if (active === session && session.pendingAppend === pending) fail(session, CodexRealtimeIssue.TIMED_OUT)
        } }.getOrElse { fail(session, CodexRealtimeIssue.CONNECTION_FAILED); return true }
        if (session.pendingAppend !== pending) pending.deadline?.cancel()
        if (active === session && session.pendingAppend === pending &&
            !runCatching { send(generation, wire) }.getOrDefault(false)) {
            fail(session, CodexRealtimeIssue.CONNECTION_FAILED)
        }
        return true
    }

    fun stop(expectedLease: Long) {
        val session = active?.takeIf { it.lease == expectedLease } ?: return
        active = null
        draining = session
        finishAppend(session, Result.failure(CodexRealtimeFailure(CodexRealtimeIssue.SESSION_CHANGED)))
        sendStop(session)
    }

    /** Shares the bounded one-request lane with audio; no retries and no playback claims. */
    fun appendSpeech(expectedLease: Long, generation: Long, threadId: String, text: String,
        callback: (Result<Unit>) -> Unit): Boolean {
        val scheduler = scheduleAudioDeadline ?: return false
        val session = active?.takeIf {
            it.lease == expectedLease && it.generation == generation && it.threadId == threadId &&
                it.started && it.sdpDelivered && it.pendingAppend == null &&
                it.speechMessages < MAX_SPEECH_MESSAGES
        } ?: return false
        val id = CodexRealtimeProtocol.PREFIX + newId()
        val wire = runCatching { CodexRealtimeProtocol.appendSpeech(id, threadId, text) }
            .getOrNull() ?: return false
        val pending = PendingAppend(id, callback)
        session.pendingAppend = pending
        session.speechMessages++
        pending.deadline = runCatching { scheduler {
            if (active === session && session.pendingAppend === pending) fail(session, CodexRealtimeIssue.TIMED_OUT)
        } }.getOrElse { fail(session, CodexRealtimeIssue.CONNECTION_FAILED); return true }
        if (session.pendingAppend !== pending) pending.deadline?.cancel()
        if (active === session && session.pendingAppend === pending &&
            !runCatching { send(generation, wire) }.getOrDefault(false)) {
            fail(session, CodexRealtimeIssue.CONNECTION_FAILED)
        }
        return true
    }

    fun timeout(expectedLease: Long) {
        active?.takeIf { it.lease == expectedLease && (!it.started || !it.sdpDelivered) }
            ?.let { fail(it, CodexRealtimeIssue.TIMED_OUT) }
    }

    fun invalidate(issue: CodexRealtimeIssue = CodexRealtimeIssue.SESSION_CHANGED, sendStop: Boolean = true) {
        revokeOrigin()
        lastSession?.invalidated = true
        val session = active
        active = null
        if (session != null) {
            draining = session
            finishAppend(session, Result.failure(CodexRealtimeFailure(issue)))
            safely { session.callbacks.onError(issue) }
            if (sendStop) sendStop(session)
        }
    }

    /** New runtime generations cannot contain an old session; forget its draining barrier. */
    fun resetGeneration() {
        invalidate(sendStop = false)
        draining?.drainDeadline?.cancel()
        draining = null
        lastSession = null
        voiceBindings.clear()
        ambiguousVoiceOrigins.clear()
        voiceBindingCapacityExceeded = false
    }

    /** Consume owned late replies even after cancellation; never hand them to chat correlation. */
    fun onFrame(generation: Long, raw: String): Boolean {
        val envelope = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        val id = envelope.opt("id") as? String
        val method = envelope.opt("method") as? String
        val ownedReply = id?.startsWith(CodexRealtimeProtocol.PREFIX) == true && method == null
        val ownedEvent = method?.startsWith(CodexRealtimeProtocol.EVENT_PREFIX) == true && id == null
        if (!ownedReply && !ownedEvent) return false
        val session = (active ?: draining)?.takeIf { it.generation == generation } ?: return true
        try {
            if (ownedReply) {
                val appendReply = id == session.pendingAppend?.requestId
                if (id != session.startRequestId && id != session.stopRequestId && !appendReply) return true
                require(envelope.has("result") != envelope.has("error"))
                if (envelope.has("error")) {
                    val error = JsonContract.requiredObject(envelope, "error")
                    val issue = CodexRealtimeProtocol.issue(
                        JsonContract.requiredString(error, "message", 8_192, allowBlank = true),
                        error.optInt("code"))
                    session.lastNativePhase = CodexRealtimeNativePhase.ERROR
                    session.lastError = issue
                    if (appendReply && active === session) {
                        fail(session, issue)
                    } else if (id == session.startRequestId && active === session) {
                        if (session.started) fail(session, issue)
                        else {
                            active = null // Rejected before native startup: no session to stop.
                            finishAppend(session, Result.failure(CodexRealtimeFailure(issue)))
                            safely { session.callbacks.onStartRejected() }
                            safely { session.callbacks.onError(issue) }
                        }
                    } else if (id == session.stopRequestId && draining === session) {
                        requireRecovery(session)
                    }
                    return true
                }
                require(JsonContract.requiredObject(envelope, "result").length() == 0)
                if (appendReply && active === session) finishAppend(session, Result.success(Unit))
                if (id == session.startRequestId) session.lastNativePhase = CodexRealtimeNativePhase.START_ACCEPTED
                if (id == session.stopRequestId) {
                    session.stopAcknowledged = true
                    session.lastNativePhase = CodexRealtimeNativePhase.STOP_ACCEPTED
                }
                // Empty start/stop ACK means the operation was queued, not connected/closed.
                return true
            }
            val params = JsonContract.requiredObject(envelope, "params")
            if (JsonContract.requiredString(params, "threadId", 256) != session.threadId) return true
            when (method) {
                "thread/realtime/started" -> {
                    if (JsonContract.optionalString(params, "realtimeSessionId", 256) != session.sessionId) return true
                    require(JsonContract.requiredString(params, "version", 8) == "v3")
                    if (active !== session) { sendStop(session); return true }
                    if (!session.started) {
                        session.started = true
                        origin = session
                        session.lastNativePhase = CodexRealtimeNativePhase.STARTED
                        safely { session.callbacks.onStarted() }
                    }
                }
                "thread/realtime/sdp" -> {
                    if (active !== session || !session.started || session.sdpDelivered) return true
                    val sdp = JsonContract.requiredString(params, "sdp", CodexRealtimeProtocol.MAX_SDP_BYTES)
                    require(sdp.startsWith("v=0"))
                    session.sdpDelivered = true
                    session.lastNativePhase = CodexRealtimeNativePhase.SDP
                    safely { session.callbacks.onRemoteSdp(sdp) }
                }
                "thread/realtime/transcript/delta", "thread/realtime/transcript/done" -> {
                    if (!session.started) return true
                    val role = JsonContract.requiredString(params, "role", 32)
                    if (role !in setOf("user", "assistant")) return true
                    val final = method.endsWith("/done")
                    val text = JsonContract.requiredString(params, if (final) "text" else "delta",
                        CodexRealtimeProtocol.MAX_TEXT_BYTES, allowBlank = true)
                    session.lastNativePhase = if (role == "user") CodexRealtimeNativePhase.USER_TRANSCRIPT else CodexRealtimeNativePhase.ASSISTANT_TRANSCRIPT
                    if (final) {
                        if (role == "user") session.userFinalCount = incrementBounded(session.userFinalCount)
                        else session.assistantFinalCount = incrementBounded(session.assistantFinalCount)
                    } else if (role == "user" && text.isNotEmpty()) {
                        session.userDeltaCount = incrementBounded(session.userDeltaCount)
                        session.userDeltaCharacters = (session.userDeltaCharacters.toLong() + text.length)
                            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    }
                    if (final || text.isNotEmpty()) safely { session.callbacks.onTranscript(role, text, final) }
                }
                "thread/realtime/item/started", "thread/realtime/item/completed" -> {
                    if (!session.started) return true
                    // This extension observes only transcript segments. Other canonical item
                    // shapes are not our contract and must not break an ordinary Live call.
                    val item = params.opt("item") as? JSONObject ?: return true
                    if (item.opt("type") != "transcriptSegment") return true
                    if (JsonContract.requiredString(item, "realtimeSessionId", 256) != session.sessionId) return true
                    val itemId = JsonContract.requiredString(item, "id", 256)
                    val role = JsonContract.requiredString(item, "role", 32)
                    if (role !in setOf("user", "assistant")) return true
                    session.lastNativePhase = if (role == "user") CodexRealtimeNativePhase.USER_ITEM else CodexRealtimeNativePhase.ASSISTANT_ITEM
                    val existingRole = session.itemRoles[itemId]
                    require(existingRole == null || existingRole == role)
                    if (existingRole == null && session.itemRoles.size >= MAX_ITEM_RECEIPTS) return true
                    session.itemRoles[itemId] = role
                    if (method.endsWith("/started")) {
                        if (session.startedItems.add(itemId) && itemId !in session.completedItems) {
                            safely { session.callbacks.onItemStarted(itemId, role) }
                        }
                    } else if (session.completedItems.add(itemId)) {
                        val text = JsonContract.requiredString(item, "text", CodexRealtimeProtocol.MAX_TEXT_BYTES, allowBlank = true)
                        safely { session.callbacks.onItemCompleted(itemId, role, text) }
                    }
                }
                "thread/realtime/itemAdded" -> {
                    // Unknown/malformed raw items must not be taken as proof of zero handoffs.
                    // Keep ordinary Live forward-compatible, but disallow fallback delivery.
                    val item = params.opt("item") as? JSONObject
                    if (!session.started || item?.opt("type") != "handoff_request") {
                        session.invalidated = true
                        return true
                    }
                    // This older event carries threadId only. The matched Started receipt and
                    // strict requested-close barrier make it session-scoped, not dispatch proof.
                    val handoffId = JsonContract.requiredString(item, "handoff_id", 256)
                    JsonContract.requiredString(item, "item_id", 256)
                    if (session.handoffs.size < MAX_ITEM_RECEIPTS && session.handoffs.add(handoffId)) {
                        session.lastNativePhase = CodexRealtimeNativePhase.HANDOFF
                        observeVoiceHandoff(session)
                        safely { session.callbacks.onHandoff() }
                    }
                }
                "thread/realtime/error" -> {
                    val issue = CodexRealtimeProtocol.issue(JsonContract.requiredString(params, "message", 8_192, allowBlank = true))
                    session.lastNativePhase = CodexRealtimeNativePhase.ERROR
                    session.lastError = issue
                    if (active === session) fail(session, issue)
                    else safely { session.callbacks.onError(issue) }
                }
                "thread/realtime/closed" -> {
                    val reason = JsonContract.optionalString(params, "reason", 64)
                    // Requested-close is thread-scoped, not session-scoped. It can confirm
                    // only our outstanding stop; a duplicate from A must not close active B.
                    if (reason == "requested" && active === session) return true
                    session.lastNativePhase = CodexRealtimeNativePhase.CLOSED
                    if (active === session) {
                        active = null
                        draining = session
                        // An unexpected peer close is not clean dictation delivery authority.
                        session.invalidated = true
                        finishAppend(session, Result.failure(CodexRealtimeFailure(CodexRealtimeIssue.SESSION_CHANGED)))
                        // The consumer closes media on this event, but its stop callback can
                        // no longer find an active lease. Retain and drain it here instead.
                        sendStop(session)
                        deliverClosed(session, confirmed = false)
                    }
                    // A transport/error close may race our queued stop. Native stop emits a
                    // second, final requested close after shutdown. Do not reuse a thread-only
                    // notification channel until that final barrier has passed.
                    if (draining === session && reason == "requested") {
                        session.drainDeadline?.cancel()
                        session.drainDeadline = null
                        draining = null
                        deliverClosed(session, confirmed = true)
                    }
                }
                // Other experimental realtime events (including item/transcript/*) are consumed
                // but never echoed as turns or duplicate live transcripts.
                else -> Unit
            }
        } catch (_: Exception) {
            session.lastError = CodexRealtimeIssue.MALFORMED_RESPONSE
            session.lastNativePhase = CodexRealtimeNativePhase.ERROR
            if (active === session) fail(session, CodexRealtimeIssue.MALFORMED_RESPONSE)
            else safely { session.callbacks.onError(CodexRealtimeIssue.MALFORMED_RESPONSE) }
        }
        return true
    }

    private fun fail(session: Session, issue: CodexRealtimeIssue) {
        if (active !== session) return
        active = null
        draining = session
        session.lastError = issue
        finishAppend(session, Result.failure(CodexRealtimeFailure(issue)))
        safely { session.callbacks.onError(issue) }
        sendStop(session)
    }

    private fun sendStop(session: Session) {
        if (session.stopRequestId != null) return
        val id = CodexRealtimeProtocol.PREFIX + newId()
        session.stopRequestId = id
        session.drainDeadline = runCatching {
            scheduleDrainDeadline?.invoke { if (draining === session) requireRecovery(session) }
        }.getOrElse { requireRecovery(session); null }
        if (!runCatching { send(session.generation, CodexRealtimeProtocol.stop(id, session.threadId)) }.getOrDefault(false)) {
            requireRecovery(session)
        }
    }

    private fun requireRecovery(session: Session) {
        if (draining !== session || session.drainExpired) return
        session.drainExpired = true
        session.lastError = session.lastError ?: CodexRealtimeIssue.RECOVERY_REQUIRED
        session.drainDeadline?.cancel()
        session.drainDeadline = null
        // Never clear an ambiguous lease or restart a process that may own accepted work.
        safely { session.callbacks.onError(CodexRealtimeIssue.RECOVERY_REQUIRED) }
        safely(onDrainRecoveryRequired)
    }

    private fun deliverClosed(session: Session, confirmed: Boolean) {
        // Media may have received an unconfirmed close before the requested-close barrier.
        // Confirm that barrier independently, exactly once, even after ordinary onClosed.
        if (confirmed && !session.closeConfirmed) {
            session.closeConfirmed = true
            safely { session.callbacks.onCloseConfirmed() }
        }
        if (session.closeDelivered) return
        session.closeDelivered = true
        safely { session.callbacks.onClosed() }
    }

    private fun incrementBounded(value: Int): Int = if (value == Int.MAX_VALUE) value else value + 1

    private fun safely(block: () -> Unit) { runCatching(block) }

    private fun finishAppend(session: Session, result: Result<Unit>) {
        val pending = session.pendingAppend ?: return
        session.pendingAppend = null
        pending.deadline?.cancel()
        safely { pending.callback(result) }
    }

    private class PendingAppend(val requestId: String, val callback: (Result<Unit>) -> Unit) {
        var deadline: SetupDispatchDeadline? = null
    }

    private data class Session(
        val lease: Long, val generation: Long, val threadId: String, val sessionId: String,
        val startRequestId: String, val callbacks: CodexRealtimeCallbacks,
        val voiceControlSessionId: String? = null,
        var voiceControlRevoked: Boolean = false, var unmatchedVoiceHandoffs: Int = 0,
        var stopRequestId: String? = null, var started: Boolean = false, var sdpDelivered: Boolean = false,
        var pendingAppend: PendingAppend? = null, var audioFrames: Int = 0, var speechMessages: Int = 0,
        var drainDeadline: SetupDispatchDeadline? = null, var drainExpired: Boolean = false,
        var closeDelivered: Boolean = false,
        var closeConfirmed: Boolean = false, var invalidated: Boolean = false, var fallbackClaimed: Boolean = false,
        var stopAcknowledged: Boolean = false,
        var userFinalCount: Int = 0, var assistantFinalCount: Int = 0,
        var userDeltaCount: Int = 0, var userDeltaCharacters: Int = 0,
        var lastNativePhase: CodexRealtimeNativePhase = CodexRealtimeNativePhase.NONE,
        var lastError: CodexRealtimeIssue? = null,
        val itemRoles: MutableMap<String, String> = LinkedHashMap(),
        val startedItems: MutableSet<String> = LinkedHashSet(),
        val completedItems: MutableSet<String> = LinkedHashSet(),
        val handoffs: MutableSet<String> = LinkedHashSet(),
        val claimedTurns: MutableSet<String> = LinkedHashSet(),
    )

    private data class VoiceTurn(val generation: Long, val threadId: String, val turnId: String)
    private data class VoiceBinding(val lease: Long, val sessionId: String?,
        var confirmedHandoff: Boolean, var terminal: Boolean = false, var displayPublished: Boolean = false)

    private companion object {
        // Native audio_in uses try_send into a 256-frame channel: ACKs do not bound its backlog.
        const val MAX_AUDIO_FRAMES = 255
        const val MAX_SPEECH_MESSAGES = 64
        const val MAX_ITEM_RECEIPTS = 4096
    }
}
