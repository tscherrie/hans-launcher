package ai.hans.standard.voice.tts

import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Only a voice session adapter; this port never submits or queues agent work. */
interface CodexReadAloudSessionPort : Closeable {
    fun prepare()
    fun speak(text: String): Boolean
    /** Fail-closed default: a STOPPED event alone is never proof of natural playback completion. */
    val completedNaturally: Boolean get() = false
    fun prepareGuarded(admissionGuard: () -> Boolean): Boolean {
        if (!runCatching(admissionGuard).getOrDefault(false)) return false
        prepare()
        return true
    }
    fun speakGuarded(text: String, admissionGuard: () -> Boolean): Boolean =
        runCatching(admissionGuard).getOrDefault(false) && speak(text)
    fun stop()
    /** True proves native session close AND physical output release, not merely a stop request. */
    fun stopAndAwait(timeoutMillis: Long): Boolean
}

enum class CodexReadAloudDeliveryPhase { IDLE, CONNECTING, READY, SPEAKING, STOPPING, STOPPED, FAILED }

data class CodexReadAloudDeliverySnapshot(
    val phase: CodexReadAloudDeliveryPhase,
    val messageId: TtsMessageId?,
    val queuedCount: Int,
    val failureCode: String? = null,
    /** FAILED is not release proof: an unconfirmed physical owner remains retained. */
    val releaseConfirmed: Boolean = true,
)

/**
 * Bounded delivery of complete, visible speech revisions. No model jobs, partial output or
 * reasoning enter this queue. The caller owns projection of visible Hans text into revisions.
 *
 * All state lives on one serial executor. Session callbacks only enqueue events; blocking
 * release checks never run on the voice-session callback thread. External calls/observers are
 * never made while holding an internal lock. Injected executors must also be serial.
 */
class CodexReadAloudDelivery(
    private val sessionFactory: (Long, (CodexReadAloudDeliveryPhase) -> Unit) -> CodexReadAloudSessionPort,
    private val observer: (CodexReadAloudDeliverySnapshot) -> Unit = {},
    serialExecutor: ExecutorService? = null,
    private val stopBarrierMillis: Long = 2_000,
    private val playbackEvent: (TtsPlaybackEvent) -> Unit = {},
) : Closeable {
    private val ownedExecutor = if (serialExecutor == null) Executors.newSingleThreadExecutor {
        Thread(it, "hans-codex-read-aloud-delivery").apply { isDaemon = true }
    } else null
    private val executor = serialExecutor ?: requireNotNull(ownedExecutor)
    private val closed = AtomicBoolean(false)
    private val onWorker = ThreadLocal.withInitial { false }
    private val pending = linkedMapOf<TtsMessageId, TtsMessageRevision>()
    private val admissionGuards = linkedMapOf<TtsMessageId, () -> Boolean>()
    // Never evict within an epoch: eviction could turn an old visible revision into fresh speech.
    private val seen = linkedMapOf<TtsMessageId, SeenRevision>()
    private var enabled = false
    private var allowIntermediate = false
    private var dictationHeld = false
    private var lastInputEpoch = Long.MIN_VALUE
    private var failed = false
    private var epoch = Long.MIN_VALUE
    private var preparationRequested = false
    private var nextGeneration = 0L
    private var active: ActiveSession? = null
    private var phase = CodexReadAloudDeliveryPhase.IDLE
    private var failureCode: String? = null

    init { require(stopBarrierMillis in 1..30_000) }

    fun configure(enabled: Boolean, allowIntermediate: Boolean, expectedEpoch: Long) = post {
        if (expectedEpoch != epoch) return@post
        val changed = this.enabled != enabled
        this.enabled = enabled
        this.allowIntermediate = allowIntermediate
        if (!enabled) {
            clearPending()
            preparationRequested = false
            stopActive(stopBarrierMillis)
        } else {
            if (changed && active == null) failed = false
            if (!allowIntermediate) {
                pending.values.filter { it.kind == TtsMessageKind.INTERMEDIATE }.toList().forEach {
                    pending.remove(it.messageId)
                    admissionGuards.remove(it.messageId)
                    seen[it.messageId]?.retired = true
                    drop(it.messageId, TtsMessageDropReason.ADMISSION_REJECTED)
                }
                if (active?.message?.kind == TtsMessageKind.INTERMEDIATE) stopActive(stopBarrierMillis)
            }
            pump()
        }
        publish()
    }

    /** Monotonic host epoch includes account/context/turn identity; stale updates cannot roll it back. */
    fun beginTurn(epoch: Long) {
        require(epoch >= 0)
        post {
            if (epoch <= this.epoch) return@post
            this.epoch = epoch
            enabled = false
            allowIntermediate = false
            // dictationHeld/lastInputEpoch describe the physical microphone, not this account.
            clearPending()
            seen.clear()
            preparationRequested = false
            failed = false
            failureCode = null
            if (!stopActive(stopBarrierMillis)) {
                // Retain the old physical barrier but never let its late event start a new epoch.
                failed = true
                phase = CodexReadAloudDeliveryPhase.FAILED
                failureCode = "codex_read_aloud_release_unconfirmed"
            }
            publish()
        }
    }

    fun submit(revision: TtsMessageRevision, expectedEpoch: Long) =
        submitGuarded(revision, expectedEpoch) { true }

    fun submitGuarded(revision: TtsMessageRevision, expectedEpoch: Long,
        admissionGuard: () -> Boolean) = post {
        if (expectedEpoch != epoch) return@post
        val previous = seen[revision.messageId]
        if (previous != null && revision.revision <= previous.revision) return@post
        if (previous == null && seen.size >= MAX_TRACKED_IDS) return@post
        val tracked = previous ?: SeenRevision(revision.revision).also { seen[revision.messageId] = it }
        tracked.revision = revision.revision
        if (tracked.retired) return@post
        if (!enabled || failed || !allowed(admissionGuard)) {
            tracked.retired = true
            pending.remove(revision.messageId)
            admissionGuards.remove(revision.messageId)
            drop(revision.messageId, TtsMessageDropReason.ADMISSION_REJECTED)
            publish()
            return@post
        }
        if (!revision.isFinal) {
            if (pending.remove(revision.messageId) != null) {
                admissionGuards.remove(revision.messageId)
                publish()
            }
            return@post
        }
        if (revision.text.isBlank() || revision.text.length > MAX_TEXT_CHARACTERS ||
            (revision.kind == TtsMessageKind.INTERMEDIATE && !allowIntermediate)) {
            pending.remove(revision.messageId)
            admissionGuards.remove(revision.messageId)
            tracked.retired = true
            drop(revision.messageId, TtsMessageDropReason.ADMISSION_REJECTED)
            publish()
            return@post
        }
        if (!pending.containsKey(revision.messageId) &&
            pending.size + (if (active?.message != null) 1 else 0) >= MAX_OUTPUTS) {
            tracked.retired = true
            drop(revision.messageId, TtsMessageDropReason.QUEUE_OVERFLOW)
            return@post
        }
        pending[revision.messageId] = revision
        admissionGuards[revision.messageId] = admissionGuard
        if (!tracked.queued) {
            tracked.queued = true
            emit(TtsPlaybackEvent.MessageQueued(revision.messageId))
        }
        pump()
        publish()
    }

    /** Optional connection preparation, consumed once even if the unused session expires. */
    fun prepare(expectedEpoch: Long) = post {
        if (expectedEpoch != epoch) return@post
        if (!enabled || dictationHeld || failed || active != null) return@post
        preparationRequested = true
        pump()
    }

    /**
     * Physical capture revisions are independent of speech account/context epochs. A context
     * change must neither release a live microphone nor discard its eventual release event.
     * Newer repeated states advance the physical fence without clearing newly held output.
     */
    fun setDictationHeld(held: Boolean, inputEpoch: Long) {
        require(inputEpoch >= 0)
        post {
            if (inputEpoch <= lastInputEpoch) return@post
            lastInputEpoch = inputEpoch
            if (dictationHeld == held) return@post
            dictationHeld = held
            if (held) {
                // Clear exactly at the edge; newer completed revisions can now wait for release.
                clearPending()
                preparationRequested = false
                stopActive(stopBarrierMillis)
            } else pump()
            publish()
        }
    }

    fun stop() = post {
        clearPending()
        preparationRequested = false
        stopActive(stopBarrierMillis)
        publish()
    }

    /** Explicit bounded stop: discard all pending speech, including output held during dictation. */
    fun stopAndAwait(timeoutMillis: Long): Boolean = awaitStop(timeoutMillis, preserveHeldOutput = false)

    /** Source revocation retires only these outputs, never a different pending/active answer. */
    fun cancelMessages(messageIds: Set<TtsMessageId>) = post { cancelMatchingNow(messageIds, stopBarrierMillis) }

    fun cancelMessagesAndAwait(messageIds: Set<TtsMessageId>, timeoutMillis: Long): Boolean {
        require(timeoutMillis in 1..30_000)
        if (messageIds.isEmpty()) return true
        if (closed.get()) return false
        if (onWorker.get() == true) return cancelMatchingNow(messageIds, timeoutMillis)
        val answer = CompletableFuture<Boolean>()
        if (!post { answer.complete(cancelMatchingNow(messageIds, timeoutMillis)) }) return false
        return try { answer.get(timeoutMillis, TimeUnit.MILLISECONDS) } catch (_: Exception) { false }
    }

    private fun cancelMatchingNow(messageIds: Set<TtsMessageId>, timeoutMillis: Long): Boolean {
        messageIds.forEach { id ->
            pending.remove(id)
            admissionGuards.remove(id)
            seen[id]?.retired = true
            drop(id, TtsMessageDropReason.ADMISSION_REJECTED)
        }
        val session = active
        val ownsRevoked = session != null && (session.message?.messageId in messageIds ||
            (session.message == null && session.preparationMessageId in messageIds))
        val released = !ownsRevoked || stopActive(timeoutMillis, TtsMessageDropReason.ADMISSION_REJECTED)
        publish()
        if (released) pump()
        return released
    }

    /**
     * Capture/native-lease barrier: preserve newer completed output only while a physical
     * dictation hold is current. Explicit stop()/stopAndAwait() always discard pending speech.
     */
    fun stopOutputAndAwait(timeoutMillis: Long): Boolean = awaitStop(timeoutMillis, preserveHeldOutput = true)

    private fun awaitStop(timeoutMillis: Long, preserveHeldOutput: Boolean): Boolean {
        require(timeoutMillis in 1..30_000)
        if (closed.get()) return false
        if (onWorker.get() == true) return stopAllNow(timeoutMillis, preserveHeldOutput)
        val answer = CompletableFuture<Boolean>()
        if (!post { answer.complete(stopAllNow(timeoutMillis, preserveHeldOutput)) }) return false
        return try { answer.get(timeoutMillis, TimeUnit.MILLISECONDS) } catch (_: Exception) { false }
    }

    private fun stopAllNow(timeoutMillis: Long, preserveHeldOutput: Boolean): Boolean {
        if (!preserveHeldOutput || !dictationHeld) clearPending()
        preparationRequested = false
        val released = stopActive(timeoutMillis)
        publish()
        return released
    }

    private fun pump() {
        if (!enabled || dictationHeld || failed || closed.get()) return
        pending.keys.toList().forEach { id ->
            if (!allowed(admissionGuards[id] ?: { true })) {
                pending.remove(id)
                admissionGuards.remove(id)
                seen[id]?.retired = true
                drop(id, TtsMessageDropReason.ADMISSION_REJECTED)
            }
        }
        if (active == null && (pending.isNotEmpty() || preparationRequested)) {
            preparationRequested = false
            val session = ActiveSession(++nextGeneration, epoch)
            session.preparationMessageId = pending.keys.firstOrNull()
            session.preparationGuard = pending.keys.firstOrNull()?.let { admissionGuards[it] } ?: { true }
            active = session
            phase = CodexReadAloudDeliveryPhase.CONNECTING
            failureCode = null
            publish()
            if (active !== session || session.stopping || closed.get()) return
            if (!allowed(session.preparationGuard)) {
                stopActive(stopBarrierMillis, TtsMessageDropReason.ADMISSION_REJECTED)
                pump()
                return
            }
            val port = try {
                sessionFactory(session.epoch) { event -> postSessionEvent { sessionPhase(session, event) } }
            } catch (_: Exception) { fail("session_create_failed"); return }
            session.port = port
            if (!allowed(session.preparationGuard)) {
                stopActive(stopBarrierMillis, TtsMessageDropReason.ADMISSION_REJECTED)
                pump()
                return
            }
            try {
                if (!port.prepareGuarded(session.preparationGuard)) {
                    if (!allowed(session.preparationGuard)) {
                        stopActive(stopBarrierMillis, TtsMessageDropReason.ADMISSION_REJECTED)
                        pump()
                    } else fail("prepare_rejected") // Never loop on an unconfirmed older owner.
                }
            } catch (_: Exception) { fail("prepare_failed") }
            return
        }
        val session = active ?: return
        if (session.stopping || session.message != null || phase != CodexReadAloudDeliveryPhase.READY) return
        val next = pending.values.firstOrNull() ?: return
        pending.remove(next.messageId)
        session.admissionGuard = admissionGuards.remove(next.messageId) ?: { true }
        session.message = next
        seen[next.messageId]?.retired = true // An ambiguous rejection must never replay this output.
        // Retain READY while the request is merely accepted. Only the engine's observed
        // non-silent-output SPEAKING callback may claim that playback actually began.
        publish()
        // Observers may synchronously request the stop barrier. Never speak after that callback
        // has already retired this generation or disposed its physical-output session.
        if (active !== session || session.stopping || !enabled || dictationHeld || failed || closed.get()) return
        if (!allowed(session.admissionGuard)) {
            stopActive(stopBarrierMillis, TtsMessageDropReason.ADMISSION_REJECTED)
            pump()
            return
        }
        if (runCatching { session.port?.speakGuarded(next.text, session.admissionGuard) == true }
                .getOrDefault(false).not()) {
            if (!allowed(session.admissionGuard)) {
                stopActive(stopBarrierMillis, TtsMessageDropReason.ADMISSION_REJECTED)
                pump()
            } else fail("speech_rejected")
        }
    }

    private fun sessionPhase(session: ActiveSession, event: CodexReadAloudDeliveryPhase) {
        if (active !== session || active?.generation != session.generation) return
        if (session.epoch != epoch) {
            // Only the still-retained old release barrier may be completed. Nothing from an old
            // account/turn can change current delivery policy or start a newer speech connection.
            if (event in setOf(CodexReadAloudDeliveryPhase.STOPPED, CodexReadAloudDeliveryPhase.FAILED) &&
                release(session, stopBarrierMillis)) {
                publish()
                shutdownIfReleased()
            }
            return
        }
        when (event) {
            CodexReadAloudDeliveryPhase.FAILED -> fail("session_failed")
            CodexReadAloudDeliveryPhase.STOPPED -> {
                // A STOPPED event is not sufficient by itself. The adapter must prove both barriers.
                val natural = !session.cancelledByOwner && !failed &&
                    allowed(session.admissionGuard) && session.port?.completedNaturally == true
                if (release(session, stopBarrierMillis)) {
                    if (natural) complete(session) else settleDrop(session,
                        if (allowed(session.admissionGuard)) TtsMessageDropReason.EXPLICIT_STOP
                        else TtsMessageDropReason.ADMISSION_REJECTED)
                    phase = if (failed) CodexReadAloudDeliveryPhase.FAILED else CodexReadAloudDeliveryPhase.STOPPED
                    publish()
                    pump()
                    shutdownIfReleased()
                } else fail("release_unconfirmed")
            }
            CodexReadAloudDeliveryPhase.STOPPING -> {
                session.stopping = true
                phase = if (failed) CodexReadAloudDeliveryPhase.FAILED else CodexReadAloudDeliveryPhase.STOPPING
                publish()
            }
            else -> {
                if (session.stopping || failed) return
                if (!allowed(if (session.message == null) session.preparationGuard else session.admissionGuard)) {
                    stopActive(stopBarrierMillis, TtsMessageDropReason.ADMISSION_REJECTED)
                    pump()
                    publish()
                    return
                }
                phase = event
                if (event == CodexReadAloudDeliveryPhase.SPEAKING && !session.started) {
                    session.started = true
                    session.message?.let { emit(TtsPlaybackEvent.MessageStarted(it.messageId)) }
                }
                publish()
                if (event == CodexReadAloudDeliveryPhase.READY) pump()
            }
        }
    }

    private fun stopActive(timeoutMillis: Long,
        dropReason: TtsMessageDropReason = TtsMessageDropReason.EXPLICIT_STOP): Boolean {
        val session = active ?: return true
        session.cancelledByOwner = true
        settleDrop(session, dropReason)
        if (active !== session) return true // A receipt observer may reenter the stop barrier.
        session.stopping = true
        if (session.port == null) {
            // Construction/preparation has not begun; a reentrant observer can safely cancel it.
            active = null
            phase = if (failed) CodexReadAloudDeliveryPhase.FAILED else CodexReadAloudDeliveryPhase.STOPPED
            return true
        }
        phase = CodexReadAloudDeliveryPhase.STOPPING
        publish()
        runCatching { session.port?.stop() }
        val released = release(session, timeoutMillis)
        if (released) phase = if (failed) CodexReadAloudDeliveryPhase.FAILED else CodexReadAloudDeliveryPhase.STOPPED
        // On an unconfirmed stop retain the generation/port. Never overlap another audio session.
        return released
    }

    private fun release(session: ActiveSession, timeoutMillis: Long): Boolean {
        val port = session.port ?: return false
        if (!runCatching { port.stopAndAwait(timeoutMillis) }.getOrDefault(false)) return false
        if (active === session) active = null
        runCatching { port.close() }
        return true
    }

    private fun fail(reason: String) {
        failed = true
        failureCode = "codex_read_aloud_$reason"
        active?.let { session ->
            if (!session.settled && session.message != null) {
                session.settled = true
                if (session.epoch == epoch) seen[session.message!!.messageId]?.settled = true
                emit(TtsPlaybackEvent.MessageFailed(session.message!!.messageId,
                    TtsFailure(TtsFailureKind.PROVIDER, requireNotNull(failureCode), false)))
            }
        }
        clearPending()
        preparationRequested = false
        stopActive(stopBarrierMillis)
        phase = CodexReadAloudDeliveryPhase.FAILED
        publish()
        shutdownIfReleased()
    }

    private fun clearPending() {
        pending.keys.toList().forEach {
            seen[it]?.retired = true
            drop(it, TtsMessageDropReason.EXPLICIT_STOP)
        }
        pending.clear()
        admissionGuards.clear()
    }

    private fun allowed(guard: () -> Boolean): Boolean = runCatching(guard).getOrDefault(false)

    private fun drop(id: TtsMessageId, reason: TtsMessageDropReason) {
        val tracked = seen[id]
        if (tracked?.settled == true) return
        tracked?.settled = true
        emit(TtsPlaybackEvent.MessageDropped(id, reason))
    }

    private fun settleDrop(session: ActiveSession, reason: TtsMessageDropReason) {
        val message = session.message ?: return
        if (session.settled) return
        session.settled = true
        if (session.epoch == epoch) seen[message.messageId]?.settled = true
        emit(TtsPlaybackEvent.MessageDropped(message.messageId, reason))
    }

    private fun complete(session: ActiveSession) {
        val message = session.message ?: return
        if (session.settled) return
        session.settled = true
        if (session.epoch == epoch) seen[message.messageId]?.settled = true
        emit(TtsPlaybackEvent.MessageCompleted(message.messageId))
    }

    private fun emit(event: TtsPlaybackEvent) { runCatching { playbackEvent(event) } }

    private fun publish() {
        val snapshot = CodexReadAloudDeliverySnapshot(
            phase, active?.takeIf { it.epoch == epoch }?.message?.messageId, pending.size, failureCode,
            releaseConfirmed = active == null,
        )
        runCatching { observer(snapshot) }
    }

    private fun post(block: () -> Unit): Boolean {
        if (closed.get()) return false
        return postSessionEvent(block)
    }

    /** Closing denies new admissions, not positive disposal receipts from its retained owner. */
    private fun postSessionEvent(block: () -> Unit): Boolean {
        return try {
            executor.execute {
                onWorker.set(true)
                try { block() } finally { onWorker.remove() }
            }
            true
        } catch (_: RuntimeException) { false }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            executor.execute {
                clearPending()
                preparationRequested = false
                val released = stopActive(stopBarrierMillis)
                // Even an unconfirmed terminal session must dispose its owned scheduler/media.
                // Its controller lease still retains the unconfirmed-close safety barrier.
                active?.port?.let { runCatching { it.close() } }
                phase = if (released) CodexReadAloudDeliveryPhase.STOPPED else CodexReadAloudDeliveryPhase.FAILED
                if (!released) failureCode = "codex_read_aloud_release_unconfirmed"
                publish()
                shutdownIfReleased()
            }
        } catch (_: RuntimeException) { Unit }
    }

    private fun shutdownIfReleased() {
        if (closed.get() && active == null) ownedExecutor?.shutdown()
    }

    private data class SeenRevision(var revision: Long, var retired: Boolean = false,
        var queued: Boolean = false, var settled: Boolean = false)
    private class ActiveSession(val generation: Long, val epoch: Long) {
        var port: CodexReadAloudSessionPort? = null
        var message: TtsMessageRevision? = null
        var stopping = false
        var preparationGuard: () -> Boolean = { true }
        var preparationMessageId: TtsMessageId? = null
        var admissionGuard: () -> Boolean = { true }
        var started = false
        var settled = false
        var cancelledByOwner = false
    }

    companion object {
        const val MAX_OUTPUTS = 8
        const val MAX_TRACKED_IDS = 256
        const val MAX_TEXT_CHARACTERS = 64_000
    }
}
