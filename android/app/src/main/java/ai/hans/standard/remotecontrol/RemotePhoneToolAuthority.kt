package ai.hans.standard.remotecontrol

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolCancellationDisposition
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.JsonContract
import ai.hans.standard.codex.ProtocolLimits
import java.io.Closeable
import java.security.MessageDigest
import org.json.JSONObject

/** Effective state supplied by the host, never by MCP arguments or model-authored metadata. */
internal data class RemotePhoneToolRuntime(
    val generation: Long?,
    val allowed: Boolean,
    val localThreadId: String?,
    val notificationRestrictedTurnIds: Set<String> = emptySet(),
)

/**
 * A generation-scoped authority over an immutable phone-tool executor lease.
 *
 * Remote call metadata identifies a candidate; only a live turn observed on the trusted native
 * App Server event channel after consent authorizes it. Retired turn IDs are never reactivated in
 * the same generation. Bounds fail closed instead of evicting replay protection or active work.
 * Callbacks, parent cancellation signals and executor methods always run outside [monitor].
 */
internal class RemotePhoneToolAuthority(
    private val executor: DynamicToolExecutor,
) : Closeable {
    private val monitor = Any()
    private var state = RemotePhoneToolRuntime(null, false, null)
    private var highestGeneration: Long? = null
    private var generationRetired = false
    private var exhausted = false
    private var closed = false
    private val seenTurns = HashSet<TurnKey>()
    private val retiredThreads = HashSet<String>()
    private val turns = LinkedHashMap<TurnKey, TurnLease>()
    private val calls = HashSet<ActiveCall>()
    private var cachedBytes = 0L

    /** Includes model work between tool calls so the host can preserve remote-work lifecycle. */
    val hasActiveWork: Boolean get() = synchronized(monitor) { turns.isNotEmpty() }

    val activeTurns: List<Pair<String, String>> get() = synchronized(monitor) {
        turns.keys.map { it.threadId to it.turnId }
    }

    fun updateState(runtime: RemotePhoneToolRuntime) {
        val stopped = synchronized(monitor) {
            if (closed) return
            val incoming = runtime.generation
            val highest = highestGeneration
            if (incoming != null && highest != null && incoming < highest) return
            if (incoming != null && incoming == highest && generationRetired) return
            val generationChanged = incoming != state.generation
            val cancelled = mutableListOf<ActiveCall>()
            if (generationChanged) {
                cancelled += retireTurnsLocked { true }
                if (incoming == null) {
                    generationRetired = highest != null
                } else {
                    highestGeneration = incoming
                    generationRetired = false
                    exhausted = false
                    seenTurns.clear()
                    retiredThreads.clear()
                }
            }
            state = runtime.copy(notificationRestrictedTurnIds = runtime.notificationRestrictedTurnIds.toSet())
            cancelled += retireTurnsLocked { key ->
                !runtime.allowed || key.threadId == runtime.localThreadId ||
                    key.turnId in runtime.notificationRestrictedTurnIds
            }
            cancelled
        }
        stopped.forEach(::abort)
    }

    /** Only the host's native event stream may call this entry point. */
    fun onEvent(generation: Long, raw: String) {
        val event = try {
            parseEvent(raw)
        } catch (_: Exception) {
            // Losing a lifecycle frame must never preserve an unverifiable live-turn lease.
            val stopped = synchronized(monitor) {
                if (state.generation != generation || closed) return
                retireTurnsLocked { true }
            }
            stopped.forEach(::abort)
            return
        } ?: return
        val stopped = synchronized(monitor) {
            if (closed || exhausted || generationRetired || state.generation != generation) return
            when (event.method) {
                "turn/started" -> {
                    val key = TurnKey(event.threadId, event.turnId ?: return)
                    if (key in seenTurns) return
                    if (seenTurns.size >= MAX_SEEN_TURNS) {
                        exhausted = true
                        retireTurnsLocked { true }
                    } else {
                        seenTurns += key
                        if (state.allowed && key.threadId !in retiredThreads && key.threadId != state.localThreadId &&
                            key.turnId !in state.notificationRestrictedTurnIds &&
                            turns.size < MAX_LIVE_TURNS
                        ) {
                            turns[key] = TurnLease(key, generation)
                        }
                        emptyList()
                    }
                }
                "turn/completed", "turn/interrupted" -> {
                    val key = TurnKey(event.threadId, event.turnId ?: return)
                    // A completion received before its start also permanently closes that turn.
                    if (seenTurns.size >= MAX_SEEN_TURNS && key !in seenTurns) {
                        exhausted = true
                        retireTurnsLocked { true }
                    } else {
                        seenTurns += key
                        retireTurnsLocked { it == key }
                    }
                }
                else -> {
                    if (retiredThreads.size >= MAX_SEEN_TURNS && event.threadId !in retiredThreads) {
                        exhausted = true
                        retireTurnsLocked { true }
                    } else {
                        retiredThreads += event.threadId
                        retireTurnsLocked { it.threadId == event.threadId }
                    }
                }
            }
        }
        stopped.forEach(::abort)
    }

    fun execute(
        params: DynamicToolCallParams,
        cancellation: DynamicToolCancellation,
        onResult: (DynamicToolExecutionResult) -> Unit,
    ): DynamicToolExecutionHandle {
        val parentCancelled = cancellationRequested(cancellation)
        val fingerprint = fingerprint(params)
        var immediate: DynamicToolExecutionResult? = null
        val active = synchronized(monitor) {
            val key = TurnKey(params.threadId, params.turnId)
            val turn = turns[key]
            when {
                parentCancelled -> immediate = error("cancelled")
                !permittedLocked(turn) -> immediate = error("remote_phone_tools_not_authorized")
                else -> {
                    checkNotNull(turn)
                    val seen = turn.seen[params.callId]
                    when {
                        seen != null && seen.fingerprint != fingerprint -> immediate = error("call_identity_conflict")
                        seen != null -> immediate = seen.result ?: error(
                            if (seen.running) "call_in_progress" else "call_result_not_cached",
                        )
                        turn.seen.size >= MAX_CALLS_PER_TURN -> immediate = error("turn_call_limit")
                        calls.size >= MAX_ACTIVE_CALLS -> immediate = error("remote_phone_tools_busy")
                        else -> {
                            val record = SeenCall(fingerprint)
                            turn.seen[params.callId] = record
                            return@synchronized ActiveCall(turn, record, cancellation, onResult).also(calls::add)
                        }
                    }
                }
            }
            null
        }
        if (active == null) {
            deliver(onResult, checkNotNull(immediate))
            return NO_EFFECT_HANDLE
        }
        val exposedHandle = object : DynamicToolExecutionHandle {
            override fun cancel(): DynamicToolCancellationDisposition = abort(active)
        }
        if (isCancelled(active)) {
            abort(active)
            return exposedHandle
        }
        val enterExecutor = synchronized(monitor) {
            if (active.finished || !permittedLocked(active.turn)) false else {
                active.enteredExecutor = true
                true
            }
        }
        if (!enterExecutor) {
            abort(active)
            return exposedHandle
        }
        try {
            val handle = executor.executeCancellable(params, DynamicToolCancellation { isCancelled(active) }) {
                complete(active, it)
            }
            val cancelLateHandle = synchronized(monitor) {
                active.handle = handle
                active.aborted || !permittedLocked(active.turn)
            }
            if (cancelLateHandle) runCatching { handle.cancel() }
        } catch (_: Exception) {
            complete(active, error("execution_result_unknown"))
        }
        return exposedHandle
    }

    /** Repeated starts cannot resurrect these turns; genuinely new turns may be admitted later. */
    fun cancelAll() {
        val stopped = synchronized(monitor) { retireTurnsLocked { true } }
        stopped.forEach(::abort)
    }

    override fun close() {
        val stopped = synchronized(monitor) {
            closed = true
            retireTurnsLocked { true }
        }
        stopped.forEach(::abort)
    }

    private fun isCancelled(call: ActiveCall): Boolean {
        // Do not invoke the host's composite signal while holding our monitor.
        val parentCancelled = cancellationRequested(call.parentCancellation)
        return synchronized(monitor) {
            parentCancelled || call.finished || call.aborted || !permittedLocked(call.turn)
        }
    }

    private fun complete(call: ActiveCall, result: DynamicToolExecutionResult) {
        val parentCancelled = cancellationRequested(call.parentCancellation)
        val delivery = synchronized(monitor) {
            if (call.finished) return
            val accepted = if (parentCancelled || call.aborted || !permittedLocked(call.turn)) {
                call.aborted = true
                error("cancelled")
            } else result
            finishLocked(call, accepted)
            accepted
        }
        val handle = synchronized(monitor) { call.handle.takeIf { call.aborted } }
        runCatching { handle?.cancel() }
        deliver(call.onResult, delivery)
    }

    private fun abort(call: ActiveCall): DynamicToolCancellationDisposition {
        var result: DynamicToolExecutionResult? = null
        val (handle, disposition) = synchronized(monitor) {
            if (!call.finished) {
                call.aborted = true
                result = error("cancelled")
                finishLocked(call, checkNotNull(result))
            }
            call.handle to if (call.enteredExecutor) {
                DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED
            } else DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT
        }
        if (call.aborted) runCatching { handle?.cancel() }
        result?.let { deliver(call.onResult, it) }
        return disposition
    }

    private fun finishLocked(call: ActiveCall, result: DynamicToolExecutionResult) {
        call.finished = true
        call.record.running = false
        calls.remove(call)
        if (turns[call.turn.key] !== call.turn) return
        val bytes = 2L * (result.contentText.length.toLong() + result.imageUrls.sumOf { it.length.toLong() })
        if (bytes <= MAX_TURN_CACHE_BYTES - call.turn.cachedBytes && bytes <= MAX_CACHE_BYTES - cachedBytes) {
            call.record.result = result.copy(imageUrls = result.imageUrls.toList())
            call.turn.cachedBytes += bytes
            cachedBytes += bytes
        }
    }

    private fun permittedLocked(turn: TurnLease?): Boolean = turn != null && !closed && !exhausted &&
        !generationRetired && state.allowed && state.generation == turn.generation &&
        turns[turn.key] === turn && turn.key.threadId != state.localThreadId &&
        turn.key.threadId !in retiredThreads &&
        turn.key.turnId !in state.notificationRestrictedTurnIds

    private fun retireTurnsLocked(predicate: (TurnKey) -> Boolean): List<ActiveCall> {
        val retiring = turns.values.filter { predicate(it.key) }.toSet()
        retiring.forEach {
            turns.remove(it.key)
            cachedBytes -= it.cachedBytes
        }
        return calls.filter { it.turn in retiring }.onEach { it.aborted = true }
    }

    private data class TurnKey(val threadId: String, val turnId: String)
    private class TurnLease(val key: TurnKey, val generation: Long) {
        val seen = HashMap<String, SeenCall>()
        var cachedBytes = 0L
    }
    private class SeenCall(val fingerprint: String) {
        var running = true
        var result: DynamicToolExecutionResult? = null
    }
    private class ActiveCall(
        val turn: TurnLease,
        val record: SeenCall,
        val parentCancellation: DynamicToolCancellation,
        val onResult: (DynamicToolExecutionResult) -> Unit,
    ) {
        var enteredExecutor = false
        var finished = false
        var aborted = false
        var handle: DynamicToolExecutionHandle? = null
    }

    private data class Event(val method: String, val threadId: String, val turnId: String?)

    private companion object {
        const val MAX_LIVE_TURNS = 32
        const val MAX_ACTIVE_CALLS = 8
        const val MAX_CALLS_PER_TURN = 256
        const val MAX_SEEN_TURNS = 4_096
        const val MAX_TURN_CACHE_BYTES = 512L * 1_024
        const val MAX_CACHE_BYTES = 2L * 1_024 * 1_024
        val NO_EFFECT_HANDLE = object : DynamicToolExecutionHandle {
            override fun cancel() = DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT
        }

        fun cancellationRequested(signal: DynamicToolCancellation): Boolean =
            runCatching(signal::isCancellationRequested).getOrDefault(true)

        fun deliver(callback: (DynamicToolExecutionResult) -> Unit, result: DynamicToolExecutionResult) {
            runCatching { callback(result) }
        }

        fun error(code: String) = DynamicToolExecutionResult(
            JSONObject().put("error", JSONObject().put("code", code).put(
                "message", when (code) {
                    "cancelled", "execution_result_unknown" ->
                        "Execution stopped or its result is unknown; an external effect may already have occurred."
                    "call_in_progress" -> "This call is already running; do not repeat the action with a new call ID."
                    "call_result_not_cached" -> "This call was already processed but its result is not retained; do not repeat the action."
                    else -> "Remote phone tool request was not executed."
                },
            )).toString(),
            success = false,
        )

        fun fingerprint(params: DynamicToolCallParams): String {
            val digest = MessageDigest.getInstance("SHA-256")
            listOf(params.namespace, params.tool, params.argumentsJson).forEach { value ->
                val bytes = value?.toByteArray(Charsets.UTF_8)
                digest.update((bytes?.size ?: -1).toString().toByteArray(Charsets.US_ASCII))
                digest.update(0.toByte())
                bytes?.let(digest::update)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun parseEvent(raw: String): Event? {
            val envelope = JsonContract.parseObject(raw, ProtocolLimits.MAX_EVENT_FRAME_BYTES)
            if (envelope.has("id")) return null
            val method = JsonContract.requiredString(envelope, "method", 64)
            if (method !in setOf("turn/started", "turn/completed", "turn/interrupted",
                    "thread/closed", "thread/archived", "thread/unloaded")) return null
            val params = JsonContract.requiredObject(envelope, "params")
            fun id(parent: JSONObject, key: String) = JsonContract.requiredString(parent, key, 512)
                .also { require(it.none(Char::isISOControl)) }
            val threadId = id(params, "threadId")
            val turnId = if (method.startsWith("turn/")) {
                val turn = params.opt("turn") as? JSONObject
                if (method == "turn/started" && turn?.opt("status") != "inProgress") return null
                if (turn != null) id(turn, "id") else id(params, "turnId")
            } else null
            return Event(method, threadId, turnId)
        }
    }
}
