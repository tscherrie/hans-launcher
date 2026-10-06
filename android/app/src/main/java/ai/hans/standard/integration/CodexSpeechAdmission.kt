package ai.hans.standard.integration

import ai.hans.standard.voice.tts.TtsMessageRevision

/**
 * Pure admission state for one host. The shared monitor orders intent publication, projection,
 * and nonblocking delivery posts. Sink methods MUST only enqueue; never await media/network here.
 */
internal class CodexSpeechAdmission(private val monitor: Any, private val sink: Sink) {
    interface Sink {
        fun begin(epoch: Long)
        fun configure(enabled: Boolean, allowIntermediate: Boolean, epoch: Long)
        fun held(active: Boolean, inputRevision: Long)
        fun prepare(epoch: Long)
        fun submit(revision: TtsMessageRevision, epoch: Long)
    }
    data class Intent(val readAloud: Boolean, val baselineOrder: Long, val epoch: Long)
    class Dispatch internal constructor()
    enum class Completion { ACCEPTED, REJECTED, SUPERSEDED }

    @Volatile var epoch: Long = 0
        private set
    private var intent = Intent(false, Long.MAX_VALUE, 0)
    private var pending: Dispatch? = null
    private var inputRevision = 0L
    private var held = false
    private var outputEpochInitialized = false
    val inputHeld: Boolean get() = synchronized(monitor) { held }
    val dispatchPending: Boolean get() = synchronized(monitor) { pending != null }

    /** Initialize the current output lease without changing a user's intent or baseline. */
    fun ensureOutputEpoch(rotateQuiescentSupplement: Boolean = false): Long = synchronized(monitor) {
        if (rotateQuiescentSupplement && outputEpochInitialized && !intent.readAloud && !held && pending == null) {
            // Completed notification-only work must not exhaust Delivery's per-epoch replay
            // tombstones. This is only requested after positive output quiescence, never while
            // a user intent or another queued/active notification owns the output lease.
            begin(intent.copy(epoch = nextEpoch()), enabled = false, allowIntermediate = false)
        }
        if (!outputEpochInitialized) {
            sink.begin(intent.epoch)
            outputEpochInitialized = true
        }
        intent.epoch
    }

    fun beginDispatch(): Dispatch = synchronized(monitor) {
        Dispatch().also { pending = it }
    }

    /** A late rejection/exception must never restore an older preview, dictation or account. */
    fun finishDispatch(token: Dispatch, accepted: Boolean, readAloud: Boolean,
        baselineOrder: Long, audible: Boolean, allowIntermediate: Boolean): Completion = synchronized(monitor) {
        if (pending !== token) return@synchronized Completion.SUPERSEDED
        pending = null
        if (!accepted) return@synchronized Completion.REJECTED
        begin(Intent(readAloud, baselineOrder, nextEpoch()), readAloud && audible, allowIntermediate)
        if (readAloud && audible) sink.prepare(epoch)
        Completion.ACCEPTED
    }

    fun rejectDispatch(token: Dispatch): Boolean = synchronized(monitor) {
        if (pending !== token) return@synchronized false
        pending = null
        true
    }

    /** Exact explicit notice only: never promotes ordinary timeline output or user intent. */
    fun submitSupplementary(revision: TtsMessageRevision, expectedEpoch: Long,
        audible: Boolean, allowIntermediate: Boolean): Boolean = synchronized(monitor) {
        if (!audible || held || pending != null || intent.epoch != expectedEpoch) return@synchronized false
        ensureOutputEpoch()
        sink.configure(true, allowIntermediate && intent.readAloud, intent.epoch)
        sink.submit(revision, intent.epoch)
        true
    }

    /** Physical input ownership has its own monotonic revision and survives context changes. */
    fun setInputHeld(active: Boolean, baselineOrder: Long, audible: Boolean,
        allowIntermediate: Boolean, prepareAfterRelease: Boolean): Boolean = synchronized(monitor) {
        if (held == active) return@synchronized false
        held = active
        inputRevision++
        if (active) {
            pending = null
            val next = Intent(true, baselineOrder, nextEpoch())
            sink.begin(next.epoch)
            sink.held(true, inputRevision)
            sink.configure(audible, allowIntermediate, next.epoch)
            intent = next
        } else {
            sink.held(false, inputRevision)
            if (prepareAfterRelease && intent.readAloud && audible) sink.prepare(intent.epoch)
        }
        true
    }

    /** Preview is an output interaction, not cancellation of a still-running automatic answer. */
    fun preview(revision: TtsMessageRevision) = synchronized(monitor) {
        val next = intent.copy(epoch = nextEpoch())
        begin(next, enabled = true, allowIntermediate = false)
        sink.submit(revision, next.epoch)
    }

    /** Includes global stop, account/runtime changes. Old pending completions cannot revive it. */
    fun revoke() = synchronized(monitor) {
        pending = null
        begin(Intent(false, Long.MAX_VALUE, nextEpoch()), enabled = false, allowIntermediate = false)
    }

    /** Projection consumes revisions only while submission into their initialized epoch is atomic. */
    fun project(audible: Boolean, forceDisable: Boolean, allowIntermediate: Boolean,
        additionalReadAloud: Boolean = false,
        projection: (Intent, Boolean) -> List<TtsMessageRevision>) = synchronized(monitor) {
        if (pending != null) return@synchronized
        val current = intent
        val userEnabled = current.readAloud && audible
        val enabled = (current.readAloud || additionalReadAloud) && audible
        if (current.readAloud || additionalReadAloud || forceDisable) {
            ensureOutputEpoch()
            sink.configure(enabled, allowIntermediate && current.readAloud, current.epoch)
        }
        // The projection sees only the original user authority. Enabling the physical sink for
        // a separately reported notice must never enable unrelated ordinary messages.
        projection(current, userEnabled).forEach { sink.submit(it, current.epoch) }
    }

    private fun begin(next: Intent, enabled: Boolean, allowIntermediate: Boolean) {
        sink.begin(next.epoch)
        outputEpochInitialized = true
        sink.configure(enabled, allowIntermediate, next.epoch)
        intent = next // Delivery initialization is enqueued before the new intent becomes visible.
    }

    private fun nextEpoch(): Long {
        check(epoch != Long.MAX_VALUE) { "codex_speech_epoch_exhausted" }
        epoch += 1
        return epoch
    }
}
