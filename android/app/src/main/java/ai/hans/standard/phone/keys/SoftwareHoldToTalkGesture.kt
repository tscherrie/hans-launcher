package ai.hans.standard.phone.keys

/** Stable id prevents a late permission result or pointer callback owning a newer gesture. */
@JvmInline
value class SoftwareHoldGestureId(val value: Long) {
    init {
        require(value > 0)
    }
}

sealed interface SoftwareHoldGestureEffect {
    data object CapturePhoto : SoftwareHoldGestureEffect
    data class RequestMicrophonePermission(
        val gestureId: SoftwareHoldGestureId,
    ) : SoftwareHoldGestureEffect
    data class StartOwnedDictation(
        val gestureId: SoftwareHoldGestureId,
    ) : SoftwareHoldGestureEffect
    data class StopOwnedDictation(
        val gestureId: SoftwareHoldGestureId,
    ) : SoftwareHoldGestureEffect
}

/**
 * Event-driven foreground gesture controller for the optional camera-button fallback.
 *
 * It has no timer or polling loop. The UI supplies monotonic timestamps and one delayed
 * long-press event only while a pointer is down. Ownership is explicit: a gesture that
 * observes another recording can never stop that recording on release or focus loss.
 */
class SoftwareHoldToTalkGestureController(
    val longPressMillis: Long = DEFAULT_LONG_PRESS_MILLIS,
) {
    private sealed interface State {
        data object Idle : State
        data class Pressed(
            val id: SoftwareHoldGestureId,
            val startedAtMillis: Long,
        ) : State
        data class AwaitingPermission(val id: SoftwareHoldGestureId) : State
        data class OwnedRecording(val id: SoftwareHoldGestureId) : State
        data class SuppressedUntilRelease(val id: SoftwareHoldGestureId) : State
    }

    private var state: State = State.Idle
    private var nextId = 0L

    @Synchronized
    fun onPointerDown(nowMillis: Long): SoftwareHoldGestureId? {
        require(nowMillis >= 0)
        if (state != State.Idle) return null
        val id = SoftwareHoldGestureId(++nextId)
        state = State.Pressed(id, nowMillis)
        return id
    }

    @Synchronized
    fun onLongPress(
        gestureId: SoftwareHoldGestureId,
        nowMillis: Long,
        recordingAlreadyActive: Boolean,
        microphonePermissionGranted: Boolean,
    ): List<SoftwareHoldGestureEffect> {
        require(nowMillis >= 0)
        val pressed = state as? State.Pressed ?: return emptyList()
        if (pressed.id != gestureId || nowMillis - pressed.startedAtMillis < longPressMillis) {
            return emptyList()
        }
        if (recordingAlreadyActive) {
            state = State.SuppressedUntilRelease(gestureId)
            return emptyList()
        }
        return if (microphonePermissionGranted) {
            state = State.OwnedRecording(gestureId)
            listOf(SoftwareHoldGestureEffect.StartOwnedDictation(gestureId))
        } else {
            state = State.AwaitingPermission(gestureId)
            listOf(SoftwareHoldGestureEffect.RequestMicrophonePermission(gestureId))
        }
    }

    @Synchronized
    fun onPointerUp(
        gestureId: SoftwareHoldGestureId,
        nowMillis: Long,
    ): List<SoftwareHoldGestureEffect> {
        require(nowMillis >= 0)
        return when (val current = state) {
            is State.Pressed -> {
                if (current.id != gestureId) return emptyList()
                state = State.Idle
                if (nowMillis - current.startedAtMillis < longPressMillis) {
                    listOf(SoftwareHoldGestureEffect.CapturePhoto)
                } else {
                    // Fail closed if a delayed threshold callback raced the UP event.
                    emptyList()
                }
            }
            is State.AwaitingPermission -> {
                if (current.id != gestureId) return emptyList()
                state = State.Idle
                emptyList()
            }
            is State.OwnedRecording -> {
                if (current.id != gestureId) return emptyList()
                state = State.Idle
                listOf(SoftwareHoldGestureEffect.StopOwnedDictation(gestureId))
            }
            is State.SuppressedUntilRelease -> {
                if (current.id != gestureId) return emptyList()
                state = State.Idle
                emptyList()
            }
            State.Idle -> emptyList()
        }
    }

    @Synchronized
    fun onPointerCancel(
        gestureId: SoftwareHoldGestureId,
        nowMillis: Long,
    ): List<SoftwareHoldGestureEffect> {
        require(nowMillis >= 0)
        return cancelMatchingGesture(gestureId)
    }

    @Synchronized
    fun onMicrophonePermissionResult(
        gestureId: SoftwareHoldGestureId,
        granted: Boolean,
        recordingAlreadyActive: Boolean,
    ): List<SoftwareHoldGestureEffect> {
        val awaiting = state as? State.AwaitingPermission ?: return emptyList()
        if (awaiting.id != gestureId) return emptyList()
        if (!granted) {
            state = State.Idle
            return emptyList()
        }
        if (recordingAlreadyActive) {
            state = State.SuppressedUntilRelease(gestureId)
            return emptyList()
        }
        state = State.OwnedRecording(gestureId)
        return listOf(SoftwareHoldGestureEffect.StartOwnedDictation(gestureId))
    }

    @Synchronized
    fun onFocusLost(): List<SoftwareHoldGestureEffect> = when (val current = state) {
        is State.OwnedRecording -> {
            state = State.Idle
            listOf(SoftwareHoldGestureEffect.StopOwnedDictation(current.id))
        }
        is State.Pressed,
        is State.AwaitingPermission,
        is State.SuppressedUntilRelease,
        -> {
            state = State.Idle
            emptyList()
        }
        State.Idle -> emptyList()
    }

    /** Recording ended independently (timeout/failure); a later UP must not stop anything else. */
    @Synchronized
    fun onOwnedRecordingEnded() {
        val current = state as? State.OwnedRecording ?: return
        state = State.SuppressedUntilRelease(current.id)
    }

    @Synchronized
    fun ownsRecording(): Boolean = state is State.OwnedRecording

    private fun cancelMatchingGesture(
        gestureId: SoftwareHoldGestureId,
    ): List<SoftwareHoldGestureEffect> {
        return when (val current = state) {
            is State.OwnedRecording -> {
                if (current.id != gestureId) return emptyList()
                state = State.Idle
                listOf(SoftwareHoldGestureEffect.StopOwnedDictation(gestureId))
            }
            is State.Pressed -> if (current.id == gestureId) clear() else emptyList()
            is State.AwaitingPermission -> if (current.id == gestureId) clear() else emptyList()
            is State.SuppressedUntilRelease -> if (current.id == gestureId) clear() else emptyList()
            State.Idle -> emptyList()
        }
    }

    private fun clear(): List<SoftwareHoldGestureEffect> {
        state = State.Idle
        return emptyList()
    }

    companion object {
        const val DEFAULT_LONG_PRESS_MILLIS = 500L
    }
}
