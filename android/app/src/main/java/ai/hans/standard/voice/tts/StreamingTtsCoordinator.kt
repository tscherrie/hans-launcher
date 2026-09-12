package ai.hans.standard.voice.tts

import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Serial, Activity-independent streaming TTS pipeline. It queues whole Hans
 * messages, synthesizes one natural sentence at a time, and advances from a
 * player's completion callback without requiring another runtime/UI event.
 */
class StreamingTtsCoordinator(
    private val settingsSource: TtsSettingsSource,
    private val provider: StreamingTtsProvider,
    private val player: StreamingTtsPlayer,
    private val audioFocus: TtsAudioFocusCoordinator,
    private val dispatcher: TtsTaskDispatcher,
    private val listener: TtsPlaybackListener,
) {
    private val segmenter = NaturalSentenceSegmenter()
    private val pendingMessages = ArrayDeque<MessageWork>()
    private val messagesById = mutableMapOf<TtsMessageId, MessageWork>()
    private val terminalRevisions = LinkedHashMap<TtsMessageId, Long>()
    private val pauseReasons = linkedSetOf<TtsPauseReason>()
    private val admissionGeneration = AtomicLong(0L)

    @Volatile
    private var visibleState: TtsPlaybackState = TtsPlaybackState.Idle

    private var currentMessage: MessageWork? = null
    private var activeSegment: ActiveSegment? = null
    private var retrySegment: SegmentWork? = null
    private var failureBlock: FailureRecord? = null
    private var explicitlyStopped = false
    private var focusHeld = false
    private var focusGeneration = 0L
    private var nextFocusGeneration = 0L
    private var nextAttempt = 0L
    private var physicalStopUnconfirmed = false

    fun state(): TtsPlaybackState = visibleState

    fun submit(revision: TtsMessageRevision) {
        val generation = admissionGeneration.get()
        dispatcher.dispatch {
            if (!admissionStillCurrent(generation, revision)) return@dispatch
            acceptRevision(revision)
        }
    }

    /**
     * Admits a queued revision only at the point where the coordinator's serial dispatcher
     * executes it. This closes the priority-control race where a later stop can overtake an
     * ordinary submit before that submit has entered coordinator state.
     */
    fun submitGuarded(
        revision: TtsMessageRevision,
        admission: () -> Boolean,
    ) {
        val generation = admissionGeneration.get()
        dispatcher.dispatch {
            if (!admissionStillCurrent(generation, revision)) return@dispatch
            if (!runCatching(admission).getOrDefault(false)) {
                emit(
                    TtsPlaybackEvent.MessageDropped(
                        revision.messageId,
                        TtsMessageDropReason.ADMISSION_REJECTED,
                    ),
                )
                return@dispatch
            }
            acceptRevision(revision)
        }
    }

    fun pause() {
        dispatcher.dispatchControl {
            if (!pauseReasons.add(TtsPauseReason.USER)) return@dispatchControl
            pauseActiveTransport()
            if (failureBlock == null) publishPausedState()
        }
    }

    fun resume() {
        dispatcher.dispatchControl {
            if (!pauseReasons.remove(TtsPauseReason.USER)) return@dispatchControl
            if (pauseReasons.isEmpty() && failureBlock == null) {
                resumeActiveTransportOrPump()
            } else if (failureBlock == null) {
                publishPausedState()
            }
        }
    }

    /** Explicit user stop. Unlike queue updates, this may interrupt a sentence. */
    fun stop() {
        admissionGeneration.incrementAndGet()
        dispatcher.dispatchControl { stopEverything() }
    }

    /** Privacy-sensitive stop that acknowledges physical player/provider cancellation. */
    fun stopAndAwait(timeoutMillis: Long): Boolean {
        admissionGeneration.incrementAndGet()
        return dispatcher.dispatchControlAndAwait(timeoutMillis) {
            check(stopEverything()) { "tts_physical_stop_unconfirmed" }
        }
    }

    /** Retries the failed sentence from its beginning, never from the middle. */
    fun retry() {
        dispatcher.dispatchControl {
            val failed = failureBlock ?: return@dispatchControl
            if (!failed.failure.retryable) return@dispatchControl
            failureBlock = null
            retrySegment = failed.committedSegment
            explicitlyStopped = false
            pump()
        }
    }

    /**
     * Abandons only the message currently blocking this coordinator in a failed state. Pending
     * messages remain queued and are pumped immediately. A domain owner may later retry its own
     * durable item with a fresh message ID; this transport ID becomes terminal so stale callbacks
     * cannot affect that new attempt.
     */
    fun abandonFailedMessage() {
        dispatcher.dispatchControl {
            if (failureBlock == null) return@dispatchControl
            failureBlock = null
            retrySegment = null
            pauseReasons.remove(TtsPauseReason.AUDIO_FOCUS)
            abandonAudioFocus()
            currentMessage?.let { message ->
                messagesById.remove(message.messageId)
                currentMessage = null
                rememberTerminal(message)
                emit(
                    TtsPlaybackEvent.MessageDropped(
                        message.messageId,
                        TtsMessageDropReason.FAILED_MESSAGE_ABANDONED,
                    ),
                )
            }
            explicitlyStopped = false
            pump()
        }
    }

    /** App switches are deliberately advisory and cannot stop or pause playback. */
    @Suppress("UNUSED_PARAMETER")
    fun onAppForegroundChanged(packageName: String?) {
        dispatcher.dispatch {
            // Kept as an explicit integration boundary. No Activity ownership.
        }
    }

    fun onProcessStopping() {
        admissionGeneration.incrementAndGet()
        dispatcher.dispatchControl { stopEverything() }
    }

    private fun admissionStillCurrent(
        generation: Long,
        revision: TtsMessageRevision,
    ): Boolean {
        if (generation == admissionGeneration.get()) return true
        emit(
            TtsPlaybackEvent.MessageDropped(
                revision.messageId,
                TtsMessageDropReason.EXPLICIT_STOP,
            ),
        )
        return false
    }

    private fun acceptRevision(revision: TtsMessageRevision) {
        if (revision.text.length > MAX_MESSAGE_CHARACTERS) {
            emit(
                TtsPlaybackEvent.InputRejected(
                    revision.messageId,
                    TtsInputRejectionReason.MESSAGE_TOO_LARGE,
                ),
            )
            return
        }
        if (terminalRevisions.containsKey(revision.messageId)) {
            ignoreRevision(revision, TtsRevisionIgnoreReason.TERMINAL_MESSAGE)
            return
        }

        val existing = messagesById[revision.messageId]
        if (existing != null) {
            updateExistingMessage(existing, revision)
            pump()
            return
        }

        val settings = try {
            settingsSource.snapshot()
        } catch (_: Exception) {
            emit(
                TtsPlaybackEvent.InputRejected(
                    revision.messageId,
                    TtsInputRejectionReason.SETTINGS_UNAVAILABLE,
                ),
            )
            return
        }
        if (!settings.readAloudMode.accepts(revision.kind)) {
            emit(TtsPlaybackEvent.MessageFiltered(revision.messageId))
            return
        }

        val message = MessageWork(
            messageId = revision.messageId,
            kind = revision.kind,
            settings = settings,
            revision = revision.revision,
            sourceText = revision.text,
            sourceFinal = revision.isFinal,
        )
        rebuildReadySegments(message)
        messagesById[message.messageId] = message
        pendingMessages.addLast(message)
        explicitlyStopped = false
        emit(TtsPlaybackEvent.MessageQueued(message.messageId))
        enforcePendingMessageLimit()
        pump()
    }

    private fun updateExistingMessage(message: MessageWork, update: TtsMessageRevision) {
        when {
            update.revision < message.revision -> {
                ignoreRevision(update, TtsRevisionIgnoreReason.STALE)
                return
            }
            update.revision == message.revision -> {
                val exactDuplicate =
                    update.text == message.sourceText &&
                        update.kind == message.kind &&
                        update.isFinal == message.sourceFinal
                ignoreRevision(
                    update,
                    if (exactDuplicate) {
                        TtsRevisionIgnoreReason.DUPLICATE
                    } else {
                        TtsRevisionIgnoreReason.SAME_REVISION_CONFLICT
                    },
                )
                return
            }
            message.sourceFinal -> {
                ignoreRevision(update, TtsRevisionIgnoreReason.AFTER_FINAL)
                return
            }
        }

        val immutablePrefix = message.sourceText.substring(0, message.committedSourceEnd)
        if (!update.text.startsWith(immutablePrefix)) {
            ignoreRevision(update, TtsRevisionIgnoreReason.REWRITES_STARTED_AUDIO)
            return
        }

        message.revision = update.revision
        message.kind = update.kind
        message.sourceText = update.text
        message.sourceFinal = update.isFinal
        rebuildReadySegments(message)
    }

    private fun rebuildReadySegments(message: MessageWork) {
        val rebuilt = segmenter.segment(
            text = message.sourceText,
            fromIndex = message.committedSourceEnd,
            isFinal = message.sourceFinal,
        )
        message.readySegments.clear()
        message.readySegments.addAll(rebuilt)
    }

    private fun enforcePendingMessageLimit() {
        while (pendingMessages.size > MAX_NOT_STARTED_MESSAGES) {
            val dropped = pendingMessages.removeFirst()
            messagesById.remove(dropped.messageId)
            rememberTerminal(dropped)
            emit(
                TtsPlaybackEvent.MessageDropped(
                    dropped.messageId,
                    TtsMessageDropReason.QUEUE_OVERFLOW,
                ),
            )
        }
    }

    private fun pump() {
        if (explicitlyStopped || failureBlock != null || activeSegment != null) return
        if (pauseReasons.isNotEmpty()) {
            publishPausedState()
            return
        }

        retrySegment?.let { retry ->
            if (focusHeld) {
                retrySegment = null
                startCommittedSegment(retry)
            } else {
                requestAudioFocus(FocusTarget(retry, isCommitted = true))
            }
            return
        }

        while (true) {
            val message = currentMessage ?: pendingMessages.pollFirst()?.also {
                currentMessage = it
                emit(TtsPlaybackEvent.MessageStarted(it.messageId))
            }
            if (message == null) {
                abandonAudioFocus()
                setState(TtsPlaybackState.Idle)
                return
            }

            val nextSpan = message.readySegments.peekFirst()
            if (nextSpan == null) {
                if (message.sourceFinal) {
                    completeCurrentMessage(message)
                    continue
                }
                abandonAudioFocus()
                setState(TtsPlaybackState.WaitingForText(message.messageId))
                return
            }

            val candidate = SegmentWork(
                segmentId = TtsSegmentId(message.messageId, message.nextSegmentOrdinal),
                text = nextSpan.text,
                sourceEndExclusive = nextSpan.sourceEndExclusive,
                voice = message.settings.voice,
                speed = message.settings.speed,
            )
            if (focusHeld) {
                commitAndStartFreshSegment(message, candidate)
            } else {
                requestAudioFocus(FocusTarget(candidate, isCommitted = false))
            }
            return
        }
    }

    private fun requestAudioFocus(target: FocusTarget) {
        setState(TtsPlaybackState.AcquiringAudioFocus(target.segment.segmentId))
        val generation = ++nextFocusGeneration
        val result = try {
            audioFocus.request { change ->
                dispatcher.dispatchControl { onAudioFocusChange(generation, change) }
            }
        } catch (_: Exception) {
            TtsAudioFocusRequestResult.DENIED
        }

        if (result == TtsAudioFocusRequestResult.GRANTED) {
            focusGeneration = generation
            focusHeld = true
            if (target.isCommitted) {
                retrySegment = null
                startCommittedSegment(target.segment)
            } else {
                val message = currentMessage
                if (message == null || message.messageId != target.segment.segmentId.messageId) {
                    failWithoutActive(
                        TtsFailure(
                            TtsFailureKind.PROTOCOL,
                            CODE_MESSAGE_ORDER_CHANGED,
                            retryable = false,
                        ),
                        target.segment.segmentId,
                    )
                } else {
                    commitAndStartFreshSegment(message, target.segment)
                }
            }
        } else {
            focusGeneration = 0L
            focusHeld = false
            failWithoutActive(
                TtsFailure(
                    TtsFailureKind.AUDIO_FOCUS_DENIED,
                    CODE_AUDIO_FOCUS_DENIED,
                    retryable = true,
                ),
                target.segment.segmentId,
                committedSegment = target.segment.takeIf { target.isCommitted },
            )
        }
    }

    private fun commitAndStartFreshSegment(message: MessageWork, segment: SegmentWork) {
        val span = message.readySegments.pollFirst()
        if (
            span == null ||
            span.text != segment.text ||
            span.sourceEndExclusive != segment.sourceEndExclusive ||
            segment.segmentId.ordinal != message.nextSegmentOrdinal
        ) {
            failWithoutActive(
                TtsFailure(
                    TtsFailureKind.PROTOCOL,
                    CODE_SEGMENT_COMMIT_MISMATCH,
                    retryable = false,
                ),
                segment.segmentId,
            )
            return
        }
        message.committedSourceEnd = span.sourceEndExclusive
        message.nextSegmentOrdinal += 1
        startCommittedSegment(segment)
    }

    private fun startCommittedSegment(segment: SegmentWork) {
        val attempt = ++nextAttempt
        val active = ActiveSegment(segment, attempt)
        activeSegment = active
        emit(TtsPlaybackEvent.SegmentStarted(segment.segmentId))
        setState(TtsPlaybackState.Synthesizing(segment.segmentId))
        try {
            active.synthesis = provider.start(
                TtsSynthesisRequest(
                    segmentId = segment.segmentId,
                    text = segment.text,
                    voice = segment.voice,
                    speed = segment.speed,
                ),
                object : StreamingTtsProvider.Listener {
                    override fun onStreamReady(format: TtsAudioStreamFormat) {
                        dispatcher.dispatchStreamEvent {
                            handleStreamReady(attempt, segment.segmentId, format)
                        }
                    }

                    override fun onAudioChunk(chunk: TtsAudioChunk) {
                        dispatcher.dispatchStreamEvent {
                            handleAudioChunk(attempt, segment.segmentId, chunk)
                        }
                    }

                    override fun onCompleted() {
                        dispatcher.dispatchStreamEvent {
                            handleProviderCompleted(attempt, segment.segmentId)
                        }
                    }

                    override fun onFailure(failure: TtsProviderFailure) {
                        dispatcher.dispatchStreamEvent {
                            handleProviderFailure(attempt, segment.segmentId, failure)
                        }
                    }
                },
            )
            if (pauseReasons.isNotEmpty()) {
                runCatching { active.synthesis?.pause() }
                publishPausedState()
            }
        } catch (_: Exception) {
            failActive(
                active,
                TtsFailure(TtsFailureKind.PROVIDER, CODE_PROVIDER_START, retryable = true),
            )
        }
    }

    private fun handleStreamReady(
        attempt: Long,
        segmentId: TtsSegmentId,
        format: TtsAudioStreamFormat,
    ) {
        val active = activeFor(attempt, segmentId) ?: return
        if (active.streamFormat != null || active.providerCompleted) {
            failProtocol(active, CODE_DUPLICATE_STREAM_READY)
            return
        }
        active.streamFormat = format
        try {
            active.playback = player.open(
                segmentId,
                format,
                object : StreamingTtsPlayer.Listener {
                    override fun onCompleted() {
                        dispatcher.dispatchStreamEvent {
                            handlePlaybackCompleted(attempt, segmentId)
                        }
                    }

                    override fun onFailure(failure: TtsPlayerFailure) {
                        dispatcher.dispatchStreamEvent {
                            handlePlayerFailure(attempt, segmentId, failure)
                        }
                    }
                },
            )
            if (pauseReasons.isNotEmpty()) {
                runCatching { active.playback?.pause() }
                publishPausedState()
            } else {
                setState(TtsPlaybackState.Playing(segmentId))
            }
        } catch (_: Exception) {
            failActive(
                active,
                TtsFailure(TtsFailureKind.PLAYER, CODE_PLAYER_OPEN, retryable = true),
            )
        }
    }

    private fun handleAudioChunk(
        attempt: Long,
        segmentId: TtsSegmentId,
        chunk: TtsAudioChunk,
    ) {
        val active = activeFor(attempt, segmentId) ?: return
        val playback = active.playback
        if (active.streamFormat == null || playback == null || active.providerCompleted) {
            failProtocol(active, CODE_AUDIO_PROTOCOL_ORDER)
            return
        }
        when {
            chunk.sequence < active.nextAudioSequence -> return // idempotent provider retry
            chunk.sequence > active.nextAudioSequence -> {
                failProtocol(active, CODE_AUDIO_SEQUENCE_GAP)
                return
            }
        }
        active.nextAudioSequence += 1
        try {
            playback.write(chunk)
        } catch (_: Exception) {
            failActive(
                active,
                TtsFailure(TtsFailureKind.PLAYER, CODE_PLAYER_WRITE, retryable = true),
            )
        }
    }

    private fun handleProviderCompleted(attempt: Long, segmentId: TtsSegmentId) {
        val active = activeFor(attempt, segmentId) ?: return
        val playback = active.playback
        if (active.streamFormat == null || playback == null) {
            failProtocol(active, CODE_PROVIDER_COMPLETED_BEFORE_STREAM)
            return
        }
        if (active.providerCompleted) return
        active.providerCompleted = true
        try {
            playback.finishInput()
        } catch (_: Exception) {
            failActive(
                active,
                TtsFailure(TtsFailureKind.PLAYER, CODE_PLAYER_FINISH, retryable = true),
            )
        }
    }

    private fun handlePlaybackCompleted(attempt: Long, segmentId: TtsSegmentId) {
        val active = activeFor(attempt, segmentId) ?: return
        if (!active.providerCompleted) {
            failProtocol(active, CODE_PLAYBACK_COMPLETED_EARLY)
            return
        }
        activeSegment = null
        emit(TtsPlaybackEvent.SegmentCompleted(segmentId))
        // Historical regression guard: advancing is part of this completion
        // event. No later text/runtime event is required to start segment N+1.
        pump()
    }

    private fun handleProviderFailure(
        attempt: Long,
        segmentId: TtsSegmentId,
        providerFailure: TtsProviderFailure,
    ) {
        val active = activeFor(attempt, segmentId) ?: return
        failActive(
            active,
            TtsFailure(
                TtsFailureKind.PROVIDER,
                providerFailure.code,
                providerFailure.retryable,
            ),
        )
    }

    private fun handlePlayerFailure(
        attempt: Long,
        segmentId: TtsSegmentId,
        playerFailure: TtsPlayerFailure,
    ) {
        val active = activeFor(attempt, segmentId) ?: return
        failActive(
            active,
            TtsFailure(
                TtsFailureKind.PLAYER,
                playerFailure.code,
                playerFailure.retryable,
            ),
        )
    }

    private fun onAudioFocusChange(generation: Long, change: TtsAudioFocusChange) {
        if (!focusHeld || generation != focusGeneration) return
        when (change) {
            TtsAudioFocusChange.GAINED -> {
                if (pauseReasons.remove(TtsPauseReason.AUDIO_FOCUS) && pauseReasons.isEmpty()) {
                    resumeActiveTransportOrPump()
                } else if (pauseReasons.isNotEmpty() && failureBlock == null) {
                    publishPausedState()
                }
            }
            TtsAudioFocusChange.LOST_TRANSIENT,
            TtsAudioFocusChange.LOST_TRANSIENT_CAN_DUCK,
            -> {
                pauseReasons += TtsPauseReason.AUDIO_FOCUS
                pauseActiveTransport()
                if (failureBlock == null) publishPausedState()
            }
            TtsAudioFocusChange.LOST_PERMANENTLY -> {
                val active = activeSegment
                if (active != null) {
                    failActive(
                        active,
                        TtsFailure(
                            TtsFailureKind.AUDIO_FOCUS_LOST,
                            CODE_AUDIO_FOCUS_LOST,
                            retryable = true,
                        ),
                    )
                } else {
                    failWithoutActive(
                        TtsFailure(
                            TtsFailureKind.AUDIO_FOCUS_LOST,
                            CODE_AUDIO_FOCUS_LOST,
                            retryable = true,
                        ),
                        currentMessage?.previewSegmentId(),
                    )
                }
            }
        }
    }

    private fun pauseActiveTransport() {
        activeSegment?.let { active ->
            runCatching { active.synthesis?.pause() }
            runCatching { active.playback?.pause() }
        }
    }

    private fun resumeActiveTransportOrPump() {
        val active = activeSegment
        if (active == null) {
            pump()
            return
        }
        runCatching { active.playback?.resume() }
        runCatching { active.synthesis?.resume() }
        setState(
            if (active.playback == null) {
                TtsPlaybackState.Synthesizing(active.work.segmentId)
            } else {
                TtsPlaybackState.Playing(active.work.segmentId)
            },
        )
    }

    private fun publishPausedState() {
        setState(
            TtsPlaybackState.Paused(
                segmentId = activeSegment?.work?.segmentId ?: retrySegment?.segmentId,
                reasons = pauseReasons.toSet(),
            ),
        )
    }

    private fun failProtocol(active: ActiveSegment, code: String) {
        failActive(
            active,
            TtsFailure(TtsFailureKind.PROTOCOL, code, retryable = false),
        )
    }

    private fun failActive(active: ActiveSegment, failure: TtsFailure) {
        if (activeSegment !== active) return
        stopActiveTransports(active)
        activeSegment = null
        pauseReasons.remove(TtsPauseReason.AUDIO_FOCUS)
        abandonAudioFocus()
        failureBlock = FailureRecord(failure, active.work)
        setState(TtsPlaybackState.Failed(active.work.segmentId, failure))
        emit(TtsPlaybackEvent.MessageFailed(active.work.segmentId.messageId, failure))
    }

    private fun failWithoutActive(
        failure: TtsFailure,
        segmentId: TtsSegmentId?,
        committedSegment: SegmentWork? = null,
    ) {
        pauseReasons.remove(TtsPauseReason.AUDIO_FOCUS)
        abandonAudioFocus()
        failureBlock = FailureRecord(failure, committedSegment)
        setState(TtsPlaybackState.Failed(segmentId, failure))
        segmentId?.let { emit(TtsPlaybackEvent.MessageFailed(it.messageId, failure)) }
    }

    private fun completeCurrentMessage(message: MessageWork) {
        check(currentMessage === message)
        messagesById.remove(message.messageId)
        currentMessage = null
        rememberTerminal(message)
        emit(TtsPlaybackEvent.MessageCompleted(message.messageId))
    }

    private fun stopEverything(): Boolean {
        activeSegment?.let(::stopActiveTransports)
        activeSegment = null
        retrySegment = null
        failureBlock = null
        pauseReasons.clear()

        currentMessage?.let { message ->
            rememberTerminal(message)
            emit(
                TtsPlaybackEvent.MessageDropped(
                    message.messageId,
                    TtsMessageDropReason.EXPLICIT_STOP,
                ),
            )
        }
        currentMessage = null
        while (pendingMessages.isNotEmpty()) {
            val message = pendingMessages.removeFirst()
            rememberTerminal(message)
            emit(
                TtsPlaybackEvent.MessageDropped(
                    message.messageId,
                    TtsMessageDropReason.EXPLICIT_STOP,
                ),
            )
        }
        messagesById.clear()
        abandonAudioFocus()
        explicitlyStopped = true
        setState(TtsPlaybackState.Stopped)
        return !physicalStopUnconfirmed
    }

    /**
     * A failed transport stop is sticky for the lifetime of this coordinator. Once the player
     * or provider cannot prove that it is terminal, a later empty stop must not accidentally
     * authorize microphone capture while the old transport may still be alive.
     */
    private fun stopActiveTransports(active: ActiveSegment): Boolean {
        val providerStopped = runCatching { active.synthesis?.cancel() }.isSuccess
        val playbackStopped = runCatching { active.playback?.stop() }.isSuccess
        if (!providerStopped || !playbackStopped) physicalStopUnconfirmed = true
        return providerStopped && playbackStopped
    }

    private fun abandonAudioFocus() {
        if (!focusHeld && focusGeneration == 0L) return
        focusHeld = false
        focusGeneration = 0L
        runCatching { audioFocus.abandon() }
    }

    private fun activeFor(attempt: Long, segmentId: TtsSegmentId): ActiveSegment? {
        return activeSegment?.takeIf {
            it.attempt == attempt && it.work.segmentId == segmentId
        }
    }

    private fun rememberTerminal(message: MessageWork) {
        terminalRevisions[message.messageId] = message.revision
        while (terminalRevisions.size > MAX_TERMINAL_MESSAGE_IDS) {
            val iterator = terminalRevisions.entries.iterator()
            iterator.next()
            iterator.remove()
        }
    }

    private fun ignoreRevision(
        revision: TtsMessageRevision,
        reason: TtsRevisionIgnoreReason,
    ) {
        emit(TtsPlaybackEvent.RevisionIgnored(revision.messageId, revision.revision, reason))
    }

    private fun setState(state: TtsPlaybackState) {
        if (visibleState == state) return
        visibleState = state
        runCatching { listener.onStateChanged(state) }
    }

    private fun emit(event: TtsPlaybackEvent) {
        runCatching { listener.onEvent(event) }
    }

    private fun TtsReadAloudMode.accepts(kind: TtsMessageKind): Boolean = when (this) {
        TtsReadAloudMode.FINAL_ONLY -> kind == TtsMessageKind.FINAL_OUTPUT
        TtsReadAloudMode.ALL_VISIBLE_HANS_MESSAGES -> true
    }

    private fun MessageWork.previewSegmentId(): TtsSegmentId? {
        return readySegments.peekFirst()?.let { TtsSegmentId(messageId, nextSegmentOrdinal) }
    }

    private data class MessageWork(
        val messageId: TtsMessageId,
        var kind: TtsMessageKind,
        val settings: TtsPlaybackSettings,
        var revision: Long,
        var sourceText: String,
        var sourceFinal: Boolean,
        var committedSourceEnd: Int = 0,
        var nextSegmentOrdinal: Long = 0,
        val readySegments: ArrayDeque<SentenceSpan> = ArrayDeque(),
    )

    private data class SegmentWork(
        val segmentId: TtsSegmentId,
        val text: String,
        val sourceEndExclusive: Int,
        val voice: String,
        val speed: Double,
    )

    private data class FocusTarget(
        val segment: SegmentWork,
        val isCommitted: Boolean,
    )

    private data class ActiveSegment(
        val work: SegmentWork,
        val attempt: Long,
        var synthesis: StreamingTtsSynthesis? = null,
        var playback: StreamingTtsPlayback? = null,
        var streamFormat: TtsAudioStreamFormat? = null,
        var nextAudioSequence: Long = 0,
        var providerCompleted: Boolean = false,
    )

    private data class FailureRecord(
        val failure: TtsFailure,
        val committedSegment: SegmentWork?,
    )

    companion object {
        const val MAX_NOT_STARTED_MESSAGES = 3
        const val MAX_MESSAGE_CHARACTERS = 1_000_000

        private const val MAX_TERMINAL_MESSAGE_IDS = 512
        private const val CODE_AUDIO_FOCUS_DENIED = "audio_focus_denied"
        private const val CODE_AUDIO_FOCUS_LOST = "audio_focus_lost"
        private const val CODE_AUDIO_PROTOCOL_ORDER = "audio_protocol_order"
        private const val CODE_AUDIO_SEQUENCE_GAP = "audio_sequence_gap"
        private const val CODE_DUPLICATE_STREAM_READY = "duplicate_stream_ready"
        private const val CODE_MESSAGE_ORDER_CHANGED = "message_order_changed"
        private const val CODE_PLAYBACK_COMPLETED_EARLY = "playback_completed_early"
        private const val CODE_PLAYER_FINISH = "player_finish_failed"
        private const val CODE_PLAYER_OPEN = "player_open_failed"
        private const val CODE_PLAYER_WRITE = "player_write_failed"
        private const val CODE_PROVIDER_COMPLETED_BEFORE_STREAM =
            "provider_completed_before_stream"
        private const val CODE_PROVIDER_START = "provider_start_failed"
        private const val CODE_SEGMENT_COMMIT_MISMATCH = "segment_commit_mismatch"
    }
}
