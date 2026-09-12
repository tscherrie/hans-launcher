package ai.hans.standard.voice.tts

import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@JvmInline
value class TtsMessageId(val value: String) {
    init {
        require(value.isNotBlank()) { "message id must not be blank" }
        require(value.length <= MAX_LENGTH) { "message id is too long" }
    }

    companion object {
        private const val MAX_LENGTH = 256
    }
}

data class TtsSegmentId(
    val messageId: TtsMessageId,
    val ordinal: Long,
) {
    init {
        require(ordinal >= 0) { "segment ordinal must not be negative" }
    }
}

enum class TtsMessageKind {
    /** A visible Hans progress/tool/status message. */
    INTERMEDIATE,

    /** A visible final Hans answer. */
    FINAL_OUTPUT,
}

enum class TtsReadAloudMode {
    FINAL_ONLY,
    ALL_VISIBLE_HANS_MESSAGES,
}

data class TtsPlaybackSettings(
    val voice: String = DEFAULT_VOICE,
    val speed: Double = DEFAULT_SPEED,
    val readAloudMode: TtsReadAloudMode = TtsReadAloudMode.ALL_VISIBLE_HANS_MESSAGES,
) {
    init {
        require(voice.isNotBlank()) { "voice must not be blank" }
        require(voice.length <= 128) { "voice is too long" }
        require(speed in 0.25..4.0) { "speech speed is outside the supported range" }
    }

    companion object {
        const val DEFAULT_VOICE = "fable"
        const val DEFAULT_SPEED = 1.25
    }
}

/** Injected adapter over the effective Hans settings store. */
fun interface TtsSettingsSource {
    fun snapshot(): TtsPlaybackSettings
}

/**
 * A complete visible-text snapshot. Revisions for one message must increase
 * monotonically; callers do not need to calculate text deltas.
 */
data class TtsMessageRevision(
    val messageId: TtsMessageId,
    val revision: Long,
    val text: String,
    val kind: TtsMessageKind,
    val isFinal: Boolean,
) {
    init {
        require(revision >= 0) { "revision must not be negative" }
    }
}

data class TtsSynthesisRequest(
    val segmentId: TtsSegmentId,
    val text: String,
    val voice: String,
    val speed: Double,
) {
    init {
        require(text.isNotBlank()) { "segment text must not be blank" }
    }
}

data class TtsAudioStreamFormat(
    val mimeType: String,
    val sampleRateHz: Int? = null,
    val channelCount: Int? = null,
) {
    init {
        require(mimeType.isNotBlank()) { "audio MIME type must not be blank" }
        require(sampleRateHz == null || sampleRateHz > 0)
        require(channelCount == null || channelCount > 0)
    }
}

class TtsAudioChunk private constructor(
    val sequence: Long,
    bytes: ByteArray,
) {
    private val immutableBytes = bytes.copyOf()

    init {
        require(sequence >= 0) { "audio sequence must not be negative" }
        require(immutableBytes.isNotEmpty()) { "audio chunk must not be empty" }
    }

    val byteCount: Int
        get() = immutableBytes.size

    fun copyBytes(): ByteArray = immutableBytes.copyOf()

    companion object {
        fun create(sequence: Long, bytes: ByteArray): TtsAudioChunk =
            TtsAudioChunk(sequence, bytes)
    }
}

data class TtsProviderFailure(
    /** Stable, non-sensitive provider code. Never put response text or credentials here. */
    val code: String,
    val retryable: Boolean,
) {
    init {
        require(code.isNotBlank() && code.length <= 128)
    }
}

interface StreamingTtsProvider {
    /**
     * Starts one sentence. Implementations emit callbacks in strict order on
     * one logical stream and must honor pause/resume without buffering without
     * bound. No API credential is part of this interface.
     */
    fun start(request: TtsSynthesisRequest, listener: Listener): StreamingTtsSynthesis

    interface Listener {
        fun onStreamReady(format: TtsAudioStreamFormat)

        fun onAudioChunk(chunk: TtsAudioChunk)

        fun onCompleted()

        fun onFailure(failure: TtsProviderFailure)
    }
}

interface StreamingTtsSynthesis {
    fun pause()

    fun resume()

    /** Idempotently cancels synthesis and releases provider resources. */
    fun cancel()
}

data class TtsPlayerFailure(
    /** Stable, non-sensitive player code. */
    val code: String,
    val retryable: Boolean,
) {
    init {
        require(code.isNotBlank() && code.length <= 128)
    }
}

interface StreamingTtsPlayer {
    /** Opens a bounded streaming sink for exactly one sentence. */
    fun open(
        segmentId: TtsSegmentId,
        format: TtsAudioStreamFormat,
        listener: Listener,
    ): StreamingTtsPlayback

    interface Listener {
        /** Fired only after every accepted byte has actually finished playing. */
        fun onCompleted()

        fun onFailure(failure: TtsPlayerFailure)
    }
}

interface StreamingTtsPlayback {
    /** Implementations copy or synchronously accept bytes with bounded backpressure. */
    fun write(chunk: TtsAudioChunk)

    /** Signals that no more provider chunks will arrive for this sentence. */
    fun finishInput()

    fun pause()

    fun resume()

    /** Idempotently stops playback and releases player resources. */
    fun stop()
}

enum class TtsAudioFocusRequestResult {
    GRANTED,
    DENIED,
}

enum class TtsAudioFocusChange {
    GAINED,
    LOST_TRANSIENT,
    LOST_TRANSIENT_CAN_DUCK,
    LOST_PERMANENTLY,
}

interface TtsAudioFocusCoordinator {
    fun request(onChange: (TtsAudioFocusChange) -> Unit): TtsAudioFocusRequestResult

    fun abandon()
}

enum class TtsPauseReason {
    USER,
    AUDIO_FOCUS,
}

enum class TtsFailureKind {
    AUDIO_FOCUS_DENIED,
    AUDIO_FOCUS_LOST,
    PROVIDER,
    PLAYER,
    PROTOCOL,
    INPUT,
}

data class TtsFailure(
    val kind: TtsFailureKind,
    val code: String,
    val retryable: Boolean,
) {
    init {
        require(code.isNotBlank() && code.length <= 128)
    }
}

sealed interface TtsPlaybackState {
    data object Idle : TtsPlaybackState

    data class WaitingForText(val messageId: TtsMessageId) : TtsPlaybackState

    data class AcquiringAudioFocus(val segmentId: TtsSegmentId) : TtsPlaybackState

    data class Synthesizing(val segmentId: TtsSegmentId) : TtsPlaybackState

    data class Playing(val segmentId: TtsSegmentId) : TtsPlaybackState

    data class Paused(
        val segmentId: TtsSegmentId?,
        val reasons: Set<TtsPauseReason>,
    ) : TtsPlaybackState

    data class Failed(
        val segmentId: TtsSegmentId?,
        val failure: TtsFailure,
    ) : TtsPlaybackState

    data object Stopped : TtsPlaybackState
}

enum class TtsMessageDropReason {
    QUEUE_OVERFLOW,
    EXPLICIT_STOP,
    /** A domain owner abandoned only the failed message so later queued work can proceed. */
    FAILED_MESSAGE_ABANDONED,
    /** A foreground interaction invalidated the queued message before coordinator admission. */
    ADMISSION_REJECTED,
}

enum class TtsRevisionIgnoreReason {
    DUPLICATE,
    STALE,
    SAME_REVISION_CONFLICT,
    REWRITES_STARTED_AUDIO,
    AFTER_FINAL,
    TERMINAL_MESSAGE,
}

enum class TtsInputRejectionReason {
    MESSAGE_TOO_LARGE,
    SETTINGS_UNAVAILABLE,
}

sealed interface TtsPlaybackEvent {
    data class MessageQueued(val messageId: TtsMessageId) : TtsPlaybackEvent

    data class MessageStarted(val messageId: TtsMessageId) : TtsPlaybackEvent

    data class MessageCompleted(val messageId: TtsMessageId) : TtsPlaybackEvent

    /**
     * The current message could not finish. Unlike [MessageDropped], this is not terminal: its
     * owner may still retry the committed sentence or explicitly abandon only this failed work.
     */
    data class MessageFailed(
        val messageId: TtsMessageId,
        val failure: TtsFailure,
    ) : TtsPlaybackEvent

    data class MessageDropped(
        val messageId: TtsMessageId,
        val reason: TtsMessageDropReason,
    ) : TtsPlaybackEvent

    data class MessageFiltered(val messageId: TtsMessageId) : TtsPlaybackEvent

    data class RevisionIgnored(
        val messageId: TtsMessageId,
        val revision: Long,
        val reason: TtsRevisionIgnoreReason,
    ) : TtsPlaybackEvent

    data class InputRejected(
        val messageId: TtsMessageId,
        val reason: TtsInputRejectionReason,
    ) : TtsPlaybackEvent

    data class SegmentStarted(val segmentId: TtsSegmentId) : TtsPlaybackEvent

    data class SegmentCompleted(val segmentId: TtsSegmentId) : TtsPlaybackEvent
}

interface TtsPlaybackListener {
    fun onStateChanged(state: TtsPlaybackState)

    /** Events contain identifiers and status only, never message or audio contents. */
    fun onEvent(event: TtsPlaybackEvent)
}

/**
 * Must execute tasks on one logical thread and must not invoke a task
 * reentrantly from inside another task. Ordinary tasks and streaming callbacks
 * each retain FIFO order. Control tasks may overtake queued ordinary/streaming
 * work so pause and stop cannot be trapped behind generated audio.
 */
fun interface TtsTaskDispatcher {
    fun dispatch(task: () -> Unit)

    /** User/audio-focus control plane. Implementations should prioritize it. */
    fun dispatchControl(task: () -> Unit) = dispatch(task)

    /** Prioritized control-plane barrier used by privacy deletion before it can report success. */
    fun dispatchControlAndAwait(timeoutMillis: Long, task: () -> Unit): Boolean {
        if (timeoutMillis <= 0) return false
        val completion = CountDownLatch(1)
        val succeeded = AtomicBoolean(false)
        return runCatching {
            dispatchControl {
                try {
                    task()
                    succeeded.set(true)
                } finally {
                    completion.countDown()
                }
            }
            completion.await(timeoutMillis, TimeUnit.MILLISECONDS) && succeeded.get()
        }.getOrDefault(false)
    }

    /**
     * Ordered streaming data plane. Production implementations synchronously
     * acknowledge completion to apply bounded backpressure to the producer.
     */
    fun dispatchStreamEvent(task: () -> Unit) = dispatch(task)
}

class ExecutorTtsTaskDispatcher : TtsTaskDispatcher, AutoCloseable {
    private val monitor = Object()
    private val controls = ArrayDeque<QueuedTask>()
    private val ordinary = ArrayDeque<QueuedTask>()
    private val closed = AtomicBoolean(false)
    private var queuedReentrantStreamEvents = 0
    private val worker = Thread(::runLoop, "hans-streaming-tts-events").apply {
        isDaemon = true
        start()
    }

    override fun dispatch(task: () -> Unit) {
        enqueue(QueuedTask(task = task))
    }

    override fun dispatchControl(task: () -> Unit) {
        synchronized(monitor) {
            ensureOpen()
            controls.addLast(QueuedTask(task = task))
            monitor.notifyAll()
        }
    }

    override fun dispatchControlAndAwait(timeoutMillis: Long, task: () -> Unit): Boolean {
        if (timeoutMillis <= 0 || Thread.currentThread() === worker) return false
        val completion = TaskCompletion()
        return runCatching {
            synchronized(monitor) {
                ensureOpen()
                controls.addLast(QueuedTask(task = task, completion = completion))
                monitor.notifyAll()
            }
            completion.await(timeoutMillis)
        }.getOrDefault(false)
    }

    override fun dispatchStreamEvent(task: () -> Unit) {
        if (Thread.currentThread() === worker) {
            synchronized(monitor) {
                ensureOpen()
                if (queuedReentrantStreamEvents >= MAX_REENTRANT_STREAM_EVENTS) {
                    throw RejectedExecutionException("synchronous TTS provider exceeded its bound")
                }
                queuedReentrantStreamEvents += 1
                ordinary.addLast(
                    QueuedTask(
                        task = task,
                        reentrantStreamEvent = true,
                    ),
                )
                monitor.notifyAll()
            }
            return
        }

        val completion = TaskCompletion()
        enqueue(QueuedTask(task = task, completion = completion))
        completion.await()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val abandoned = synchronized(monitor) {
            val queued = buildList {
                addAll(controls)
                addAll(ordinary)
            }
            controls.clear()
            ordinary.clear()
            queuedReentrantStreamEvents = 0
            monitor.notifyAll()
            queued
        }
        abandoned.forEach { it.completion?.complete(succeeded = false) }
        worker.interrupt()
    }

    private fun enqueue(task: QueuedTask) {
        synchronized(monitor) {
            ensureOpen()
            ordinary.addLast(task)
            monitor.notifyAll()
        }
    }

    private fun runLoop() {
        while (true) {
            val queued = synchronized(monitor) {
                while (!closed.get() && controls.isEmpty() && ordinary.isEmpty()) {
                    try {
                        monitor.wait()
                    } catch (_: InterruptedException) {
                        if (closed.get()) return
                    }
                }
                if (closed.get()) return
                if (controls.isNotEmpty()) {
                    controls.removeFirst()
                } else {
                    ordinary.removeFirst().also {
                        if (it.reentrantStreamEvent) queuedReentrantStreamEvents -= 1
                    }
                }
            }
            var succeeded = false
            try {
                queued.task()
                succeeded = true
            } catch (_: RuntimeException) {
                // Domain tasks translate failures into stable state themselves.
            } finally {
                queued.completion?.complete(succeeded)
            }
        }
    }

    private fun ensureOpen() {
        if (closed.get()) throw RejectedExecutionException("TTS dispatcher is closed")
    }

    private data class QueuedTask(
        val task: () -> Unit,
        val completion: TaskCompletion? = null,
        val reentrantStreamEvent: Boolean = false,
    )

    private class TaskCompletion {
        private val latch = CountDownLatch(1)
        private val succeeded = AtomicBoolean(false)

        fun complete(succeeded: Boolean) {
            this.succeeded.set(succeeded)
            latch.countDown()
        }

        fun await() {
            var interrupted = false
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }

        fun await(timeoutMillis: Long): Boolean {
            var interrupted = false
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    if (interrupted) Thread.currentThread().interrupt()
                    return false
                }
                try {
                    val completed = latch.await(remaining, TimeUnit.NANOSECONDS)
                    if (interrupted) Thread.currentThread().interrupt()
                    return completed && succeeded.get()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
    }

    private companion object {
        // Only a provider that calls back synchronously from start() can enter
        // this exceptional path. Real asynchronous streams have one in flight.
        const val MAX_REENTRANT_STREAM_EVENTS = 64
    }
}
