package ai.hans.standard.phone.keys

import android.view.KeyEvent

@JvmInline
value class CaptureSessionId(val value: Long) {
    init {
        require(value > 0)
    }
}

data class ActionKeyCaptureRequest(
    val sessionId: CaptureSessionId,
    val mappingId: String,
    val action: KeySemanticAction,
    val trigger: ActionKeyTrigger,
    val startedAtMillis: Long,
    val expiresAtMillis: Long,
) {
    init {
        require(startedAtMillis >= 0)
        require(expiresAtMillis > startedAtMillis)
        require(action == KeySemanticAction.DICTATION || trigger == ActionKeyTrigger.PRESS)
    }
}

sealed interface ActionKeyCaptureState {
    data object Idle : ActionKeyCaptureState

    data class Capturing(
        val request: ActionKeyCaptureRequest,
        val deliveredEventCount: Int,
        val candidateDown: ObservableAndroidKeyEvent?,
        val longPressObserved: Boolean,
    ) : ActionKeyCaptureState

    data class Completed(
        val request: ActionKeyCaptureRequest,
        val mapping: ActionKeyMapping,
    ) : ActionKeyCaptureState

    data class Rejected(
        val request: ActionKeyCaptureRequest,
        val reason: CaptureRejection,
    ) : ActionKeyCaptureState

    data class TimedOut(
        val request: ActionKeyCaptureRequest,
        val reason: CaptureTimeoutReason,
    ) : ActionKeyCaptureState

    data class Cancelled(val request: ActionKeyCaptureRequest) : ActionKeyCaptureState
}

sealed interface CaptureRejection {
    data class Reserved(val key: ReservedHardwareKey) : CaptureRejection
    data object NotAnIdentifiablePhysicalDevice : CaptureRejection
    data object AmbiguousMultipleKeys : CaptureRejection
    data object HoldWasNotDemonstrated : CaptureRejection
}

enum class CaptureTimeoutReason {
    NO_DELIVERED_EVENT,
    INCOMPLETE_PRESS,
}

enum class CaptureIgnoreReason {
    STALE_SESSION,
    NOT_CAPTURING,
    REPEATED_DOWN,
    DUPLICATE_EVENT,
    UP_WITHOUT_DELIVERED_DOWN,
    TIMEOUT_NOT_REACHED,
}

sealed interface CaptureEventResult {
    data class StateChanged(val state: ActionKeyCaptureState) : CaptureEventResult
    data class Ignored(val reason: CaptureIgnoreReason) : CaptureEventResult
}

/**
 * Serial capture reducer used by onboarding. It can only record events passed
 * to [onDeliveredEvent]; timeout explicitly reports when Android delivered
 * nothing, instead of pretending that a reserved hardware key is supported.
 */
class ActionKeyCaptureStateMachine(
    private val minimumHoldMillis: Long = 450L,
) {
    init {
        require(minimumHoldMillis > 0)
    }

    @Volatile
    private var visibleState: ActionKeyCaptureState = ActionKeyCaptureState.Idle

    fun state(): ActionKeyCaptureState = visibleState

    @Synchronized
    fun begin(request: ActionKeyCaptureRequest): ActionKeyCaptureState {
        val current = visibleState
        if (current.requestOrNull() == request) {
            return current
        }
        require(current.requestOrNull()?.sessionId != request.sessionId) {
            "A capture session id cannot be reused for different capture state"
        }
        return ActionKeyCaptureState.Capturing(
            request = request,
            deliveredEventCount = 0,
            candidateDown = null,
            longPressObserved = false,
        ).also { visibleState = it }
    }

    @Synchronized
    fun onDeliveredEvent(
        sessionId: CaptureSessionId,
        event: ObservableAndroidKeyEvent,
    ): CaptureEventResult {
        val capturing = visibleState as? ActionKeyCaptureState.Capturing
            ?: return CaptureEventResult.Ignored(
                if (visibleState.requestOrNull()?.sessionId != sessionId) {
                    CaptureIgnoreReason.STALE_SESSION
                } else {
                    CaptureIgnoreReason.NOT_CAPTURING
                },
            )
        if (capturing.request.sessionId != sessionId) {
            return CaptureEventResult.Ignored(CaptureIgnoreReason.STALE_SESSION)
        }
        if (event.eventTimeMillis >= capturing.request.expiresAtMillis) {
            return timeout(capturing)
        }

        val observed = capturing.copy(
            deliveredEventCount = capturing.deliveredEventCount + 1,
        )
        visibleState = observed
        ReservedHardwareKeyPolicy.classify(event.keyCode)?.let { reserved ->
            return change(
                ActionKeyCaptureState.Rejected(
                    observed.request,
                    CaptureRejection.Reserved(reserved),
                ),
            )
        }
        if (!event.hasMappablePhysicalIdentity()) {
            return change(
                ActionKeyCaptureState.Rejected(
                    observed.request,
                    CaptureRejection.NotAnIdentifiablePhysicalDevice,
                ),
            )
        }

        return when (event.phase) {
            ObservableKeyPhase.DOWN -> onDown(observed, event)
            ObservableKeyPhase.UP -> onUp(observed, event)
        }
    }

    @Synchronized
    fun onTimeout(sessionId: CaptureSessionId, nowMillis: Long): CaptureEventResult {
        require(nowMillis >= 0)
        val capturing = visibleState as? ActionKeyCaptureState.Capturing
            ?: return CaptureEventResult.Ignored(
                if (visibleState.requestOrNull()?.sessionId != sessionId) {
                    CaptureIgnoreReason.STALE_SESSION
                } else {
                    CaptureIgnoreReason.NOT_CAPTURING
                },
            )
        if (capturing.request.sessionId != sessionId) {
            return CaptureEventResult.Ignored(CaptureIgnoreReason.STALE_SESSION)
        }
        if (nowMillis < capturing.request.expiresAtMillis) {
            return CaptureEventResult.Ignored(CaptureIgnoreReason.TIMEOUT_NOT_REACHED)
        }
        return timeout(capturing)
    }

    @Synchronized
    fun cancel(sessionId: CaptureSessionId): CaptureEventResult {
        val capturing = visibleState as? ActionKeyCaptureState.Capturing
            ?: return CaptureEventResult.Ignored(CaptureIgnoreReason.NOT_CAPTURING)
        if (capturing.request.sessionId != sessionId) {
            return CaptureEventResult.Ignored(CaptureIgnoreReason.STALE_SESSION)
        }
        return change(ActionKeyCaptureState.Cancelled(capturing.request))
    }

    private fun onDown(
        capturing: ActionKeyCaptureState.Capturing,
        event: ObservableAndroidKeyEvent,
    ): CaptureEventResult {
        val candidate = capturing.candidateDown
        if (candidate == null) {
            if (event.repeatCount > 0) {
                return CaptureEventResult.Ignored(CaptureIgnoreReason.REPEATED_DOWN)
            }
            return change(
                capturing.copy(
                    candidateDown = event,
                    longPressObserved = event.isLongPress,
                ),
            )
        }
        if (!candidate.samePhysicalGesture(event)) {
            return change(
                ActionKeyCaptureState.Rejected(
                    capturing.request,
                    CaptureRejection.AmbiguousMultipleKeys,
                ),
            )
        }
        if (event.repeatCount > 0 || event.isLongPress) {
            return change(
                capturing.copy(
                    longPressObserved = capturing.longPressObserved || event.isLongPress,
                ),
            )
        }
        return CaptureEventResult.Ignored(CaptureIgnoreReason.DUPLICATE_EVENT)
    }

    private fun onUp(
        capturing: ActionKeyCaptureState.Capturing,
        event: ObservableAndroidKeyEvent,
    ): CaptureEventResult {
        val candidate = capturing.candidateDown
            ?: return CaptureEventResult.Ignored(CaptureIgnoreReason.UP_WITHOUT_DELIVERED_DOWN)
        if (!candidate.samePhysicalGesture(event)) {
            return change(
                ActionKeyCaptureState.Rejected(
                    capturing.request,
                    CaptureRejection.AmbiguousMultipleKeys,
                ),
            )
        }
        if (
            capturing.request.trigger == ActionKeyTrigger.HOLD_TO_TALK &&
            !capturing.longPressObserved &&
            event.heldMillis < minimumHoldMillis
        ) {
            return change(
                ActionKeyCaptureState.Rejected(
                    capturing.request,
                    CaptureRejection.HoldWasNotDemonstrated,
                ),
            )
        }
        val device = checkNotNull(candidate.physicalDevice)
        val mapping = ActionKeyMapping(
            mappingId = capturing.request.mappingId,
            device = device.selector(),
            source = candidate.source,
            scanCode = candidate.scanCode,
            keyCode = candidate.keyCode,
            metaState = candidate.metaState,
            trigger = capturing.request.trigger,
            action = capturing.request.action,
        )
        return change(ActionKeyCaptureState.Completed(capturing.request, mapping))
    }

    private fun timeout(capturing: ActionKeyCaptureState.Capturing): CaptureEventResult = change(
        ActionKeyCaptureState.TimedOut(
            request = capturing.request,
            reason = if (capturing.deliveredEventCount == 0) {
                CaptureTimeoutReason.NO_DELIVERED_EVENT
            } else {
                CaptureTimeoutReason.INCOMPLETE_PRESS
            },
        ),
    )

    private fun change(state: ActionKeyCaptureState): CaptureEventResult.StateChanged {
        visibleState = state
        return CaptureEventResult.StateChanged(state)
    }
}

private fun ActionKeyCaptureState.requestOrNull(): ActionKeyCaptureRequest? = when (this) {
    ActionKeyCaptureState.Idle -> null
    is ActionKeyCaptureState.Capturing -> request
    is ActionKeyCaptureState.Completed -> request
    is ActionKeyCaptureState.Rejected -> request
    is ActionKeyCaptureState.TimedOut -> request
    is ActionKeyCaptureState.Cancelled -> request
}

internal fun ObservableAndroidKeyEvent.samePhysicalGesture(
    other: ObservableAndroidKeyEvent,
): Boolean =
    physicalDevice?.selector() == other.physicalDevice?.selector() &&
        source == other.source &&
        scanCode == other.scanCode &&
        keyCode == other.keyCode &&
        hasSameCaptureMetaState(other) &&
        downTimeMillis == other.downTimeMillis

private fun ObservableAndroidKeyEvent.hasSameCaptureMetaState(
    other: ObservableAndroidKeyEvent,
): Boolean = metaState == other.metaState || (
    // Android clears SYM's own modifier bit on release. Preserve the DOWN
    // state in the mapping and require every unrelated chord bit to stay exact.
    phase == ObservableKeyPhase.DOWN &&
        other.phase == ObservableKeyPhase.UP &&
        keyCode == KeyEvent.KEYCODE_SYM &&
        other.metaState == (metaState and KeyEvent.META_SYM_ON.inv())
    )
