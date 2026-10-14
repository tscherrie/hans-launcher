package ai.hans.standard.voice.tts

import java.io.Closeable
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class SystemSpeechVoice(val id: String, val locale: String, val label: String, val offline: Boolean)
data class SystemSpeechOptions(val enabled: Boolean = true, val voiceId: String? = null, val speed: Float = 1f)
enum class SystemSpeechStatus { IDLE, CHECKING, READY, SPEAKING, COMPLETED, STOPPED, UNAVAILABLE, FAILED }
data class SystemSpeechSnapshot(
    val status: SystemSpeechStatus = SystemSpeechStatus.IDLE,
    val options: SystemSpeechOptions = SystemSpeechOptions(),
    val voices: List<SystemSpeechVoice> = emptyList(),
    val selectedVoice: SystemSpeechVoice? = null,
    val failureCode: String? = null,
)

/** Public system engine seam. Events refer to one exact utterance, never a global busy flag. */
interface SystemSpeechEngine : Closeable {
    interface Events {
        fun initialized(success: Boolean)
        fun started(id: String)
        fun done(id: String)
        fun stopped(id: String)
        fun failed(id: String)
        fun interrupted(id: String)
    }
    fun initialize(events: Events)
    fun voices(): List<SystemSpeechVoice>
    fun configure(voiceId: String, speed: Float): Boolean
    fun speak(text: String, id: String): Boolean
    fun stop(): Boolean
    val maxInputLength: Int
}

/** One reusable engine; per-answer leases preserve the existing delivery/notification receipts. */
class SystemSpeechRuntime(
    private val engineFactory: () -> SystemSpeechEngine,
    private val optionsSource: () -> SystemSpeechOptions,
    private val saveOptions: (SystemSpeechOptions) -> Unit,
    private val language: () -> Locale = { Locale.getDefault() },
    private val focus: TtsAudioFocusCoordinator,
    private val audible: () -> Boolean,
    private val reportFailure: (String) -> Unit = {},
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "hans-system-speech").apply { isDaemon = true }
    },
    private val initTimeoutMillis: Long = 10_000,
    private val playbackTimeoutMillis: Long = 180_000,
    private val idleReleaseMillis: Long = 60_000,
) : Closeable {
    private val observers = CopyOnWriteArraySet<(SystemSpeechSnapshot) -> Unit>()
    @Volatile var snapshot = SystemSpeechSnapshot(options = optionsSource())
        private set
    private var engine: SystemSpeechEngine? = null
    private var ready = false
    private var active: Session? = null
    private var sequence = 0L
    private var deadline: ScheduledFuture<*>? = null
    private var initDeadline: ScheduledFuture<*>? = null
    private var idleRelease: ScheduledFuture<*>? = null
    private val closed = AtomicBoolean()

    fun observe(observer: (SystemSpeechSnapshot) -> Unit): Closeable {
        observers.add(observer)
        observer(snapshot)
        return Closeable {
            observers.remove(observer)
            post { if (active == null && observers.isEmpty()) scheduleIdleRelease() }
        }
    }
    fun refresh() = post { ensureEngine(); if (ready) updateCatalog() }
    fun update(options: SystemSpeechOptions) {
        require(options.speed in 0.5f..2f)
        saveOptions(options)
        post {
            snapshot = snapshot.copy(options = options)
            active?.let { requestStop(it) }
            if (ready) updateCatalog() else publish(snapshot.status)
        }
    }

    fun session(observer: (CodexReadAloudDeliveryPhase) -> Unit,
        guard: () -> Boolean = { true }): CodexReadAloudSessionPort = Session(observer, guard)

    private inner class Session(
        val observer: (CodexReadAloudDeliveryPhase) -> Unit,
        val ownerGuard: () -> Boolean,
    ) : CodexReadAloudSessionPort {
        val cancelled = AtomicBoolean()
        val submitted = AtomicBoolean()
        val released = CountDownLatch(1)
        @Volatile override var completedNaturally = false
            private set
        var guard: () -> Boolean = { true }
        var chunks: List<String> = emptyList()
        var index = 0
        var utterance: String? = null
        var failed = false
        var readyNotified = false
        var started = false
        fun allowed() = !cancelled.get() && optionsSource().enabled &&
            runCatching { ownerGuard() && guard() }.getOrDefault(false)
        override fun prepare() { prepareGuarded { true } }
        override fun prepareGuarded(admissionGuard: () -> Boolean): Boolean {
            if (!allowed() || closed.get()) return false
            post {
                guard = admissionGuard
                if (!allowed()) finish(this)
                else if (active != null && active !== this) {
                    failed = true
                    finish(this)
                } else {
                    active = this
                    idleRelease?.cancel(false)
                    observer(CodexReadAloudDeliveryPhase.CONNECTING)
                    ensureEngine()
                    if (ready) prepareReady(this)
                }
            }
            return true
        }
        override fun speak(text: String) = speakGuarded(text) { true }
        override fun speakGuarded(text: String, admissionGuard: () -> Boolean): Boolean {
            if (text.isBlank() || text.length > 32_000 || !allowed() || !submitted.compareAndSet(false, true)) return false
            post {
                guard = admissionGuard
                if (active !== this || !ready || !allowed()) { finish(this); return@post }
                chunks = splitSystemSpeech(text, minOf(runCatching { engine!!.maxInputLength }.getOrDefault(0), 3_000))
                if (chunks.isEmpty()) { fail(this, "system_speech_text_invalid"); return@post }
                val voice = snapshot.selectedVoice
                if (voice == null || !runCatching { engine!!.configure(voice.id, optionsSource().speed) }.getOrDefault(false)) {
                    fail(this, "system_speech_voice_missing"); return@post
                }
                val granted = runCatching { focus.request { change ->
                    if (change != TtsAudioFocusChange.GAINED) {
                        cancelled.set(true)
                        post { requestStop(this) }
                    }
                } == TtsAudioFocusRequestResult.GRANTED }.getOrDefault(false)
                if (!granted) { fail(this, "system_speech_focus_denied"); return@post }
                deadline?.cancel(false)
                deadline = worker.schedule({ fail(this, "system_speech_timeout") }, playbackTimeoutMillis, TimeUnit.MILLISECONDS)
                speakNext(this)
            }
            return true
        }
        override fun stop() {
            cancelled.set(true) // Invalidate queued start/next-chunk work before waiting on the worker.
            post { requestStop(this) }
        }
        override fun stopAndAwait(timeoutMillis: Long): Boolean {
            if (released.count == 0L) return true
            stop()
            return try { released.await(timeoutMillis, TimeUnit.MILLISECONDS) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        }
        override fun close() { if (released.count != 0L) stop() }
        fun naturalCompletion() { completedNaturally = allowed(); finish(this) }
    }

    private fun ensureEngine() {
        if (engine != null) return
        idleRelease?.cancel(false)
        publish(SystemSpeechStatus.CHECKING)
        val created = try {
            engineFactory()
        } catch (_: Exception) {
            unavailable("system_speech_engine_missing")
            return
        }
        engine = created
        initDeadline?.cancel(false)
        initDeadline = worker.schedule(
            { if (engine === created && !ready) unavailable("system_speech_init_timeout") },
            initTimeoutMillis,
            TimeUnit.MILLISECONDS,
        )
        runCatching { created.initialize(object : SystemSpeechEngine.Events {
            override fun initialized(success: Boolean) = post {
                if (engine !== created) return@post
                initDeadline?.cancel(false)
                if (!success) { unavailable("system_speech_engine_missing"); return@post }
                ready = true
                updateCatalog()
                active?.let(::prepareReady) ?: scheduleIdleRelease()
            }
            override fun started(id: String) = event(id) { session ->
                if (!session.allowed() || !audible()) requestStop(session)
                else {
                    session.started = true
                    publish(SystemSpeechStatus.SPEAKING)
                    session.observer(CodexReadAloudDeliveryPhase.SPEAKING)
                }
            }
            override fun done(id: String) = event(id) { session ->
                session.utterance = null
                if (!session.allowed()) finish(session)
                else if (!session.started) fail(session, "system_speech_output_unconfirmed")
                else { session.index++; if (session.index == session.chunks.size) session.naturalCompletion()
                    else speakNext(session) }
            }
            override fun stopped(id: String) = event(id) { session -> session.utterance = null; finish(session) }
            override fun failed(id: String) = event(id) { session ->
                session.utterance = null; fail(session, "system_speech_output_failed")
            }
            override fun interrupted(id: String) = event(id, ::requestStop)
            private fun event(id: String, action: (Session) -> Unit) = post {
                if (engine === created) active?.takeIf { it.utterance == id }?.let(action)
            }
        }) }.onFailure { unavailable("system_speech_engine_missing") }
    }

    private fun updateCatalog() {
        val voices = runCatching { engine?.voices().orEmpty() }.getOrDefault(emptyList())
            .distinctBy { it.id }.sortedBy { it.label }.take(512)
        val options = optionsSource()
        val selected = selectSystemSpeechVoice(voices, options.voiceId, language())?.takeIf { voice ->
            active?.utterance != null || runCatching {
                engine?.configure(voice.id, options.speed) == true
            }.getOrDefault(false)
        }
        snapshot = snapshot.copy(voices = voices, options = options, selectedVoice = selected)
        publish(if (active?.utterance != null) snapshot.status else if (selected != null) SystemSpeechStatus.READY
            else SystemSpeechStatus.UNAVAILABLE, if (selected == null) "system_speech_voice_missing" else null)
    }
    private fun prepareReady(session: Session) {
        if (!session.allowed()) { finish(session); return }
        if (snapshot.selectedVoice == null) { fail(session, "system_speech_voice_missing"); return }
        if (!session.readyNotified) {
            session.readyNotified = true
            session.observer(CodexReadAloudDeliveryPhase.READY)
        }
    }
    private fun speakNext(session: Session) {
        if (!session.allowed() || !audible()) { requestStop(session); return }
        val id = "system-speech-${++sequence}"
        session.started = false
        session.utterance = id
        if (!runCatching { engine?.speak(session.chunks[session.index], id) == true }.getOrDefault(false)) {
            session.utterance = null
            fail(session, "system_speech_output_failed")
        }
    }
    private fun requestStop(session: Session) {
        session.cancelled.set(true)
        if (active !== session || session.utterance == null) { finish(session); return }
        session.observer(CodexReadAloudDeliveryPhase.STOPPING)
        // Await the engine's correlated onStop/onDone/error event before admitting a microphone.
        runCatching { engine?.stop() }
    }
    private fun fail(session: Session, code: String) {
        if (active !== session || session.released.count == 0L) return
        session.failed = true
        publish(SystemSpeechStatus.FAILED, code)
        runCatching { reportFailure(code) }
        requestStop(session)
    }
    private fun unavailable(code: String) {
        ready = false
        engine?.let { runCatching { it.close() } }
        engine = null
        publish(SystemSpeechStatus.UNAVAILABLE, code)
        active?.let { fail(it, code) }
    }
    private fun finish(session: Session) {
        if (session.released.count == 0L) return
        if (active === session) {
            deadline?.cancel(false)
            active = null
            runCatching { focus.abandon() }
            publish(when {
                session.failed -> SystemSpeechStatus.FAILED
                session.completedNaturally -> SystemSpeechStatus.COMPLETED
                else -> SystemSpeechStatus.STOPPED
            }, snapshot.failureCode.takeIf { session.failed })
            scheduleIdleRelease()
        }
        session.released.countDown()
        session.observer(if (session.failed) CodexReadAloudDeliveryPhase.FAILED else CodexReadAloudDeliveryPhase.STOPPED)
    }
    private fun scheduleIdleRelease() {
        idleRelease?.cancel(false)
        if (observers.isNotEmpty()) return
        idleRelease = worker.schedule({
            if (active == null && observers.isEmpty()) {
                engine?.let { runCatching { it.close() } }; engine = null; ready = false
                initDeadline?.cancel(false)
                publish(SystemSpeechStatus.IDLE)
            }
        }, idleReleaseMillis, TimeUnit.MILLISECONDS)
    }
    private fun publish(status: SystemSpeechStatus, code: String? = null) {
        snapshot = snapshot.copy(status = status, failureCode = code)
        observers.forEach { runCatching { it(snapshot) } }
    }
    private fun post(block: () -> Unit) {
        if (!closed.get()) runCatching { worker.execute(block) }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        worker.execute {
            active?.cancelled?.set(true)
            runCatching { engine?.stop() }; runCatching { engine?.close() }
            active?.let { it.utterance = null; finish(it) }
            runCatching { focus.abandon() }; deadline?.cancel(false); initDeadline?.cancel(false); idleRelease?.cancel(false)
            worker.shutdown()
        }
    }
}

internal fun selectSystemSpeechVoice(voices: List<SystemSpeechVoice>, preferred: String?, locale: Locale): SystemSpeechVoice? =
    voices.firstOrNull { it.id == preferred } ?: voices.filter {
        Locale.forLanguageTag(it.locale).language == locale.language
    }.sortedWith(compareByDescending<SystemSpeechVoice> { it.offline }
        .thenByDescending { it.locale.equals(locale.toLanguageTag(), ignoreCase = true) }
        .thenBy { it.id }).firstOrNull()

internal fun splitSystemSpeech(text: String, limit: Int): List<String> {
    if (limit < 2) return emptyList()
    val result = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + limit, text.length)
        if (end < text.length) {
            val boundary = (end - 1 downTo start + limit / 2).firstOrNull {
                text[it].isWhitespace() && (text.getOrNull(it - 1) in listOf('.', '!', '?', '\n'))
            } ?: (end - 1 downTo start + limit / 2).firstOrNull { text[it].isWhitespace() }
            if (boundary != null) end = boundary + 1
            if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        }
        text.substring(start, end).takeIf { it.isNotBlank() }?.let(result::add)
        start = end
    }
    return result
}
