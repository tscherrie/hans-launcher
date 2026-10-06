package ai.hans.standard.voice.realtime

import ai.hans.standard.integration.CodexRealtimeCall
import ai.hans.standard.integration.CodexRealtimeCallbacks
import ai.hans.standard.integration.CodexRealtimeGateway
import ai.hans.standard.integration.CodexRealtimeIssue
import java.util.UUID
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Single-use SDP exchange. Only native App Server reads the account's credentials. */
class CodexLiveSessionProvider(
    private val gateway: CodexRealtimeGateway,
    private val events: Events,
    private val voiceControlSessionId: String? = null,
) : LiveSessionProvider, AutoCloseable {
    interface Events {
        fun onStarted()
        fun onTranscript(role: String, text: String, isFinal: Boolean)
        fun onWorkState(state: CodexTaskVoiceWorkState) {}
        fun onHandoff() {}
        fun onWorkBound(scope: CodexVoiceWorkScope) {}
        /** Canonical new assistant segment, not proof that playback completed. */
        fun onAssistantResponseStarted(id: String) {}
        /** Separate from flat transcript finals; never echo this as a second user request. */
        fun onTranscriptSegmentCompleted(id: String, role: String, text: String) {}
        /** Positive native drain/no-admission receipt; delivered even after local cancellation. */
        fun onCloseConfirmed() {}
        fun onClosed()
    }

    private val requested = AtomicBoolean(false)
    private val terminal = AtomicBoolean(false)
    private val answered = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val call = AtomicReference<CodexRealtimeCall?>()
    private val lifecycleLock = Any()
    private val nativeClosed = CountDownLatch(1)
    private val closeReceiptDelivered = AtomicBoolean(false)
    private val startedItems = LinkedHashSet<String>()
    private val completedItems = LinkedHashSet<String>()

    override fun create(
        setup: LiveSessionSetup,
        offerSdp: String,
        callback: LiveSessionProvider.Callback,
    ): LiveVoiceCancellation {
        val accepted = synchronized(lifecycleLock) {
            !terminal.get() && requested.compareAndSet(false, true)
        }
        if (!accepted) {
            callback.onFailure(LiveVoiceFailure("codex_live_session_already_used", false))
            return LiveVoiceCancellation.NONE
        }
        if (setup.config.model != CodexLiveVoiceSession.MODEL ||
            setup.config.voice !in CodexLiveVoiceVoiceResolver.supportedVoices ||
            setup.history.isNotEmpty() || offerSdp.isBlank() || offerSdp.length > 128_000) {
            terminal.set(true)
            confirmNativeClose() // Configuration rejected before invoking the gateway.
            callback.onFailure(LiveVoiceFailure("codex_live_configuration_invalid", false))
            return LiveVoiceCancellation.NONE
        }
        val callbacks = object : CodexRealtimeCallbacks {
            override val voiceControlSessionId: String? = this@CodexLiveSessionProvider.voiceControlSessionId
            override fun onStartRejected() { confirmNativeClose() }
            override fun onCloseConfirmed() { confirmNativeClose() }
            override fun onStarted() {
                if (!terminal.get() && started.compareAndSet(false, true)) events.onStarted()
            }
            override fun onRemoteSdp(sdp: String) {
                if (terminal.get()) return
                if (sdp.isBlank() || sdp.length > 128_000) {
                    onError(CodexRealtimeIssue.MALFORMED_RESPONSE)
                    return
                }
                if (answered.compareAndSet(false, true)) {
                    callback.onCreated(LiveSessionAnswer("codex:${UUID.randomUUID()}", sdp))
                }
            }
            override fun onTranscript(role: String, text: String, isFinal: Boolean) {
                if (!terminal.get() && role in setOf("user", "assistant") && text.length <= 64_000) {
                    events.onTranscript(role, text, isFinal)
                }
            }
            override fun onWorkState(state: CodexTaskVoiceWorkState) {
                if (!terminal.get()) events.onWorkState(state)
            }
            override fun onHandoff() {
                if (!terminal.get()) events.onHandoff()
            }
            override fun onWorkBound(scope: CodexVoiceWorkScope) {
                if (!terminal.get()) events.onWorkBound(scope)
            }
            override fun onItemStarted(itemId: String, role: String) {
                if (terminal.get() || role != "assistant" || !validItemId(itemId)) return
                val deliver = synchronized(lifecycleLock) {
                    !terminal.get() && itemId !in completedItems && startedItems.size < MAX_ITEM_RECEIPTS &&
                        startedItems.add(itemId)
                }
                if (deliver) events.onAssistantResponseStarted(itemId)
            }
            override fun onItemCompleted(itemId: String, role: String, text: String) {
                if (terminal.get() || role !in setOf("user", "assistant") || !validItemId(itemId) ||
                    text.length > 64_000) return
                val deliver = synchronized(lifecycleLock) {
                    !terminal.get() && completedItems.size < MAX_ITEM_RECEIPTS && completedItems.add(itemId)
                }
                if (deliver) events.onTranscriptSegmentCompleted(itemId, role, text)
            }
            override fun onError(issue: CodexRealtimeIssue) {
                if (!terminal.compareAndSet(false, true)) return
                call.getAndSet(null)?.let { runCatching { it.stop() } }
                callback.onFailure(LiveVoiceFailure(issue.failureCode(), false))
            }
            override fun onClosed() {
                if (!terminal.compareAndSet(false, true)) return
                // A transport close may precede native queue drainage. Request its final
                // barrier; only onCloseConfirmed (or proven no admission) releases waiters.
                call.getAndSet(null)?.let { runCatching { it.stop() } }
                events.onClosed()
            }
        }
        if (terminal.get()) {
            confirmNativeClose() // Local cancellation won before gateway admission.
            return LiveVoiceCancellation.NONE
        }
        val created = runCatching {
            gateway.start(offerSdp, setup.instructions, setup.config.voice, callbacks)
        }.getOrNull()
        // Native callbacks may complete or cancellation may arrive before start returns.
        if (created != null) {
            call.set(created)
            if (terminal.get()) call.getAndSet(null)?.let { runCatching { it.stop() } }
        } else if (!terminal.get()) {
            callbacks.onError(CodexRealtimeIssue.NOT_AVAILABLE)
        }
        return OnceCancellation(::close)
    }

    override fun close() {
        val neverAdmitted = synchronized(lifecycleLock) {
            terminal.set(true)
            !requested.get()
        }
        if (neverAdmitted) confirmNativeClose()
        call.getAndSet(null)?.let { runCatching { it.stop() } }
    }

    /** False means native drainage is unproven, never permission to start another session. */
    fun closeAndAwait(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0)
        close()
        return try {
            nativeClosed.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun confirmNativeClose() {
        nativeClosed.countDown()
        if (closeReceiptDelivered.compareAndSet(false, true)) runCatching { events.onCloseConfirmed() }
    }

    companion object {
        private const val MAX_ITEM_RECEIPTS = 256
        private fun validItemId(id: String) = id.isNotEmpty() && id.length <= 256 && id.none(Char::isISOControl)
        private fun CodexRealtimeIssue.failureCode(): String = "codex_live_" + name.lowercase(Locale.ROOT)
    }
}
