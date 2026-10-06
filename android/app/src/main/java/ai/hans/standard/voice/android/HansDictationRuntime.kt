package ai.hans.standard.voice.android

import ai.hans.standard.phone.accessibility.DictationLifecycleStamp
import ai.hans.standard.phone.accessibility.android.ReadOnlyDictationLifecycleProbe
import ai.hans.standard.voice.RecordingFailure
import ai.hans.standard.voice.RecordingId
import ai.hans.standard.voice.RecordingState
import ai.hans.standard.voice.stt.SttTranscriptionDelay
import java.util.LinkedHashSet

enum class DictationUiPhase {
    IDLE,
    PREPARING,
    LISTENING,
    FINALIZING,
    WAITING_TO_SEND,
    SENT,
    /** Correlated native Voice reply, not a task dispatch/completion acknowledgement. */
    NATIVE_COMPLETED,
    FAILED,
}

data class DictationRuntimeSnapshot(
    val phase: DictationUiPhase = DictationUiPhase.IDLE,
    val activeRecordingId: RecordingId? = null,
    val failure: RecordingFailure? = null,
    val revision: Long = 0,
    /** Ephemeral UI only: never persisted or submitted as a conversation message. */
    val provisionalTranscript: String = "",
    val confirmedTranscriptionDelay: SttTranscriptionDelay? = null,
    /** Confirmed capture mute; the Voice session and its Codex task remain active. */
    val inputMuted: Boolean = false,
    /** Current recording is accepted, but no visible own transcript has arrived yet. */
    val awaitingFirstUserTranscript: Boolean = false,
)

fun interface DictationRuntimeObserver {
    fun onSnapshot(snapshot: DictationRuntimeSnapshot)
}

/** Process-local, passive publication. It owns no microphone or stop operation. */
object HansDictationRuntime : ReadOnlyDictationLifecycleProbe {
    private val observers = LinkedHashSet<DictationRuntimeObserver>()
    private var current = DictationRuntimeSnapshot()
    private var lifecycleGeneration = 0L
    private var recordingActive = false
    private var pendingDeliveryId: String? = null

    @Synchronized
    fun snapshotUi(): DictationRuntimeSnapshot = current

    @Synchronized
    fun addObserver(observer: DictationRuntimeObserver) {
        observers += observer
        runCatching { observer.onSnapshot(current) }
    }

    @Synchronized
    fun removeObserver(observer: DictationRuntimeObserver) {
        observers -= observer
    }

    @Synchronized
    fun publish(state: RecordingState) {
        if (state is RecordingState.AwaitingAudioFocus) pendingDeliveryId = null
        val active = state.isRecordingLifecycleActive()
        if (active != recordingActive) {
            recordingActive = active
            lifecycleGeneration += 1
        }
        val next = DictationRuntimeSnapshot(
            phase = when (state) {
                RecordingState.Idle -> DictationUiPhase.IDLE
                is RecordingState.AwaitingAudioFocus -> DictationUiPhase.PREPARING
                is RecordingState.Recording -> DictationUiPhase.LISTENING
                is RecordingState.Stopping,
                is RecordingState.Finalizing,
                -> DictationUiPhase.FINALIZING
                // A completed transcript is not an acknowledged Codex turn.
                is RecordingState.Completed -> DictationUiPhase.WAITING_TO_SEND
                is RecordingState.Failed -> DictationUiPhase.FAILED
            },
            activeRecordingId = state.recordingIdOrNull(),
            failure = (state as? RecordingState.Failed)?.failure,
            revision = current.revision + 1,
            provisionalTranscript = current.provisionalTranscript.takeIf {
                active && current.activeRecordingId == state.recordingIdOrNull()
            }.orEmpty(),
            confirmedTranscriptionDelay = current.confirmedTranscriptionDelay.takeIf {
                active && current.activeRecordingId == state.recordingIdOrNull()
            },
            inputMuted = active && current.activeRecordingId == state.recordingIdOrNull() && current.inputMuted,
            awaitingFirstUserTranscript = active && if (
                current.activeRecordingId == state.recordingIdOrNull() && recordingActive &&
                current.phase in setOf(DictationUiPhase.PREPARING, DictationUiPhase.LISTENING,
                    DictationUiPhase.FINALIZING)
            ) {
                current.awaitingFirstUserTranscript
            } else {
                state is RecordingState.AwaitingAudioFocus
            },
        )
        current = next
        observers.toList().forEach { runCatching { it.onSnapshot(next) } }
    }

    @Synchronized
    fun resetIdle() {
        pendingDeliveryId = null
        if (recordingActive) {
            recordingActive = false
            lifecycleGeneration += 1
        }
        val next = DictationRuntimeSnapshot(revision = current.revision + 1)
        current = next
        observers.toList().forEach { runCatching { it.onSnapshot(next) } }
    }

    /** Voice owns delivery; clearing its UI is neither a draft nor proof of completed work. */
    @Synchronized
    fun completeNativeSession(recordingId: RecordingId) {
        if (current.activeRecordingId != recordingId) return
        pendingDeliveryId = null
        if (recordingActive) {
            recordingActive = false
            lifecycleGeneration += 1
        }
        current = DictationRuntimeSnapshot(
            phase = DictationUiPhase.NATIVE_COMPLETED,
            activeRecordingId = recordingId,
            revision = current.revision + 1,
        )
        observers.toList().forEach { runCatching { it.onSnapshot(current) } }
    }

    @Synchronized
    fun publishPartial(recordingId: RecordingId, transcript: String) {
        if (!recordingActive || current.activeRecordingId != recordingId ||
            current.phase !in setOf(DictationUiPhase.LISTENING, DictationUiPhase.FINALIZING)
        ) return
        val preview = transcript.takeLast(MAX_PREVIEW_CHARACTERS)
            .let { if (it.firstOrNull()?.isLowSurrogate() == true) it.drop(1) else it }
        if (preview.isBlank() || preview == current.provisionalTranscript) return
        current = current.copy(provisionalTranscript = preview,
            awaitingFirstUserTranscript = false, revision = current.revision + 1)
        observers.toList().forEach { runCatching { it.onSnapshot(current) } }
    }

    /** Only the current native recording can retire its own UI wait; no text is retained here. */
    @Synchronized
    fun observeNativeUserTranscript(recordingId: RecordingId, text: String) {
        if (text.isBlank()) return
        confirmNativeTranscriptAwaiting(recordingId, false)
    }

    /** False includes closing/error with retained safety ownership, not just successful speech. */
    @Synchronized
    fun confirmNativeTranscriptAwaiting(recordingId: RecordingId, awaiting: Boolean) {
        if (awaiting || !recordingActive || current.activeRecordingId != recordingId ||
            !current.awaitingFirstUserTranscript) return
        current = current.copy(awaitingFirstUserTranscript = false, revision = current.revision + 1)
        observers.toList().forEach { runCatching { it.onSnapshot(current) } }
    }

    @Synchronized
    fun confirmTranscriptionDelay(recordingId: RecordingId, delay: SttTranscriptionDelay) {
        if (!recordingActive || current.activeRecordingId != recordingId ||
            current.confirmedTranscriptionDelay == delay
        ) return
        current = current.copy(confirmedTranscriptionDelay = delay, revision = current.revision + 1)
        observers.toList().forEach { runCatching { it.onSnapshot(current) } }
    }

    const val MAX_PREVIEW_CHARACTERS = 4_000

    /** Only the matching native owner may publish a successful media mute transition. */
    @Synchronized
    fun confirmInputMuted(recordingId: RecordingId, muted: Boolean) {
        if (!recordingActive || current.activeRecordingId != recordingId || current.inputMuted == muted) return
        current = current.copy(inputMuted = muted, revision = current.revision + 1)
        observers.toList().forEach { runCatching { it.onSnapshot(current) } }
    }

    @Synchronized
    fun awaitDelivery(recordingId: RecordingId, pendingId: String) {
        if (current.activeRecordingId == recordingId && !recordingActive) {
            pendingDeliveryId = pendingId
        }
    }

    @Synchronized
    fun confirmDelivery(pendingId: String) {
        if (recordingActive || pendingDeliveryId != pendingId) return
        pendingDeliveryId = null
        current = current.copy(phase = DictationUiPhase.SENT, revision = current.revision + 1)
        observers.toList().forEach { runCatching { it.onSnapshot(current) } }
    }

    @Synchronized
    fun forgetPendingDelivery(pendingId: String) {
        if (recordingActive || pendingDeliveryId != pendingId) return
        resetIdle()
    }

    @Synchronized
    fun reportStartFailure(failure: RecordingFailure) {
        if (recordingActive) return
        current = DictationRuntimeSnapshot(
            phase = DictationUiPhase.FAILED,
            failure = failure,
            revision = current.revision + 1,
        )
        observers.toList().forEach { runCatching { it.onSnapshot(current) } }
    }

    @Synchronized
    override fun snapshot(): DictationLifecycleStamp = DictationLifecycleStamp(
        generation = lifecycleGeneration,
        recordingActive = recordingActive,
    )
}

private fun RecordingState.isRecordingLifecycleActive(): Boolean = when (this) {
    is RecordingState.AwaitingAudioFocus,
    is RecordingState.Recording,
    is RecordingState.Stopping,
    is RecordingState.Finalizing,
    -> true
    RecordingState.Idle,
    is RecordingState.Completed,
    is RecordingState.Failed,
    -> false
}

private fun RecordingState.recordingIdOrNull(): RecordingId? = when (this) {
    RecordingState.Idle -> null
    is RecordingState.AwaitingAudioFocus -> recordingId
    is RecordingState.Recording -> recordingId
    is RecordingState.Stopping -> recordingId
    is RecordingState.Finalizing -> recordingId
    is RecordingState.Completed -> recordingId
    is RecordingState.Failed -> recordingId
}
