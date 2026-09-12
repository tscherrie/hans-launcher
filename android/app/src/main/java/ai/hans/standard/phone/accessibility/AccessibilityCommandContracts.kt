package ai.hans.standard.phone.accessibility

import java.util.Collections

@JvmInline
value class AccessibilityIdempotencyKey(val value: String) {
    init {
        require(value.length in 8..160 && value.all(::isSafeKeyCharacter)) {
            "invalid accessibility idempotency key"
        }
    }

    private companion object {
        fun isSafeKeyCharacter(character: Char): Boolean =
            character.isLetterOrDigit() || character in "-_.:"
    }
}

enum class UiTextMatchMode {
    EXACT,
    PREFIX_CASE_INSENSITIVE,
    CONTAINS_CASE_INSENSITIVE,
}

data class UiFindQuery(
    val packageName: String? = null,
    val className: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val role: SemanticUiRole? = null,
    val requiredAction: SemanticUiAction? = null,
    val requireVisible: Boolean = true,
    val requireEnabled: Boolean? = null,
    val textMatchMode: UiTextMatchMode = UiTextMatchMode.EXACT,
    val maxResults: Int = 20,
) {
    init {
        require(maxResults in 1..MAX_FIND_RESULTS)
        listOf(packageName, className, text, contentDescription)
            .filterNotNull()
            .forEach { require(it.length <= MAX_QUERY_FIELD_CHARACTERS) }
    }

    companion object {
        const val MAX_FIND_RESULTS = 50
        const val MAX_QUERY_FIELD_CHARACTERS = 4_096
    }
}

enum class AccessibilityConfirmationRisk(val priority: Int) {
    NONE(0),
    EXTERNAL_COMMUNICATION(10),
    DESTRUCTIVE(20),
    CREDENTIAL_UI(30),
}

enum class UiScrollDirection {
    FORWARD,
    BACKWARD,
}

enum class AccessibilityGlobalAction {
    BACK,
    HOME,
    RECENTS,
}

enum class UiPostconditionExpectation {
    ACTION_ACCEPTED,
    NODE_STATE_CHANGED,
    TARGET_DISAPPEARED,
    WINDOW_CHANGED,
    TEXT_EQUALS_REQUEST,
}

data class UiCoordinateGesture(
    val start: UiPoint,
    val end: UiPoint? = null,
    val durationMillis: Long = 100,
) {
    init {
        require(durationMillis in 1L..10_000L)
    }
}

sealed interface AccessibilityCommand {
    val idempotencyKey: AccessibilityIdempotencyKey

    data class Find(
        override val idempotencyKey: AccessibilityIdempotencyKey,
        val correlation: UiSnapshotCorrelation,
        val query: UiFindQuery,
    ) : AccessibilityCommand

    data class Click(
        override val idempotencyKey: AccessibilityIdempotencyKey,
        val handle: SemanticNodeHandle,
        val confirmationRisk: AccessibilityConfirmationRisk = AccessibilityConfirmationRisk.NONE,
        val postcondition: UiPostconditionExpectation =
            UiPostconditionExpectation.ACTION_ACCEPTED,
    ) : AccessibilityCommand

    data class SetText(
        override val idempotencyKey: AccessibilityIdempotencyKey,
        val handle: SemanticNodeHandle,
        val value: String,
        val confirmationRisk: AccessibilityConfirmationRisk = AccessibilityConfirmationRisk.NONE,
        val postcondition: UiPostconditionExpectation =
            UiPostconditionExpectation.TEXT_EQUALS_REQUEST,
    ) : AccessibilityCommand {
        init {
            require(value.length <= MAX_SET_TEXT_CHARACTERS)
        }
    }

    data class Scroll(
        override val idempotencyKey: AccessibilityIdempotencyKey,
        val handle: SemanticNodeHandle,
        val direction: UiScrollDirection,
        val confirmationRisk: AccessibilityConfirmationRisk = AccessibilityConfirmationRisk.NONE,
        val postcondition: UiPostconditionExpectation =
            UiPostconditionExpectation.NODE_STATE_CHANGED,
    ) : AccessibilityCommand

    data class Global(
        override val idempotencyKey: AccessibilityIdempotencyKey,
        val sessionId: AccessibilitySessionId,
        val action: AccessibilityGlobalAction,
        val confirmationRisk: AccessibilityConfirmationRisk = AccessibilityConfirmationRisk.NONE,
        val postcondition: UiPostconditionExpectation =
            UiPostconditionExpectation.WINDOW_CHANGED,
    ) : AccessibilityCommand

    data class CoordinateGesture(
        override val idempotencyKey: AccessibilityIdempotencyKey,
        val correlation: UiSnapshotCorrelation,
        val gesture: UiCoordinateGesture,
        val confirmationRisk: AccessibilityConfirmationRisk = AccessibilityConfirmationRisk.NONE,
        val postcondition: UiPostconditionExpectation =
            UiPostconditionExpectation.ACTION_ACCEPTED,
    ) : AccessibilityCommand

    companion object {
        const val MAX_SET_TEXT_CHARACTERS = 32_768
    }
}

data class AccessibilityConfirmationRequest(
    val idempotencyKey: AccessibilityIdempotencyKey,
    val commandFingerprint: String,
    val risk: AccessibilityConfirmationRisk,
    val correlation: UiSnapshotCorrelation?,
) {
    init {
        require(commandFingerprint.matches(Regex("[0-9a-f]{64}")))
    }
}

data class AccessibilityUserApproval(
    val approvalId: String,
    val idempotencyKey: AccessibilityIdempotencyKey,
    val commandFingerprint: String,
    val risk: AccessibilityConfirmationRisk,
    val correlation: UiSnapshotCorrelation?,
) {
    init {
        require(approvalId.length in 8..160 && approvalId.all { it.isLetterOrDigit() || it in "-_.:" })
        require(commandFingerprint.matches(Regex("[0-9a-f]{64}")))
    }
}

fun interface AccessibilityUserConfirmationGate {
    /** Implemented by a trusted user-presence/approval surface. */
    fun isApproved(
        request: AccessibilityConfirmationRequest,
        approval: AccessibilityUserApproval?,
    ): Boolean
}

object ExactAccessibilityUserConfirmationGate : AccessibilityUserConfirmationGate {
    override fun isApproved(
        request: AccessibilityConfirmationRequest,
        approval: AccessibilityUserApproval?,
    ): Boolean = approval != null &&
        approval.idempotencyKey == request.idempotencyKey &&
        approval.commandFingerprint == request.commandFingerprint &&
        approval.risk == request.risk &&
        approval.correlation == request.correlation
}

fun interface AccessibilityRiskPolicy {
    fun requiredRisk(
        command: AccessibilityCommand,
        target: SemanticUiNode?,
    ): AccessibilityConfirmationRisk
}

object DefaultAccessibilityRiskPolicy : AccessibilityRiskPolicy {
    override fun requiredRisk(
        command: AccessibilityCommand,
        target: SemanticUiNode?,
    ): AccessibilityConfirmationRisk {
        val declared = when (command) {
            is AccessibilityCommand.Find -> AccessibilityConfirmationRisk.NONE
            is AccessibilityCommand.Click -> command.confirmationRisk
            is AccessibilityCommand.SetText -> command.confirmationRisk
            is AccessibilityCommand.Scroll -> command.confirmationRisk
            is AccessibilityCommand.Global -> command.confirmationRisk
            is AccessibilityCommand.CoordinateGesture -> command.confirmationRisk
        }
        val structural = if (
            command is AccessibilityCommand.SetText &&
            target?.role == SemanticUiRole.PASSWORD_FIELD
        ) {
            AccessibilityConfirmationRisk.CREDENTIAL_UI
        } else {
            AccessibilityConfirmationRisk.NONE
        }
        return if (structural.priority > declared.priority) structural else declared
    }
}

enum class AccessibilityPostconditionKind {
    SNAPSHOT_QUERY,
    NODE_ACTION,
    GLOBAL_ACTION,
    COORDINATE_GESTURE,
    REQUEST_NOT_EXECUTED,
    DICTATION_NONINTERFERENCE,
}

enum class AccessibilityPostconditionStatus {
    VERIFIED,
    OBSERVED_NOT_VERIFIED,
    NOT_EVALUATED,
    FAILED,
}

data class AccessibilityPostcondition(
    val kind: AccessibilityPostconditionKind,
    val status: AccessibilityPostconditionStatus,
    /** Stable local code only; never external UI text. */
    val detailCode: String,
    val before: UiSnapshotCorrelation?,
    val after: UiSnapshotCorrelation?,
    val trust: UiDataTrust,
) {
    init {
        require(detailCode.matches(SAFE_CODE)) { "unsafe postcondition code" }
    }

    private companion object {
        val SAFE_CODE = Regex("[a-z][a-z0-9_]{2,79}")
    }
}

sealed interface AccessibilityObservation {
    val trust: UiDataTrust

    class FoundNodes(
        handles: List<SemanticNodeHandle>,
        val snapshotTruncated: Boolean,
    ) : AccessibilityObservation {
        override val trust: UiDataTrust = UiDataTrust.UNTRUSTED_EXTERNAL
        val handles: List<SemanticNodeHandle> = Collections.unmodifiableList(handles.toList())
    }

    data class ActionReceipt(
        val target: SemanticNodeHandle?,
        val actionCode: String,
        val resultingCorrelation: UiSnapshotCorrelation?,
        override val trust: UiDataTrust,
    ) : AccessibilityObservation {
        init {
            require(actionCode.matches(Regex("[a-z][a-z0-9_]{2,79}")))
        }
    }
}

enum class AccessibilityExecutionStatus {
    SUCCEEDED,
    CONFIRMATION_REQUIRED,
    REJECTED,
    FAILED,
}

data class AccessibilityExecutionResult(
    val idempotencyKey: AccessibilityIdempotencyKey,
    val status: AccessibilityExecutionStatus,
    val replayed: Boolean,
    val observation: AccessibilityObservation?,
    val postcondition: AccessibilityPostcondition,
    val requiredConfirmation: AccessibilityConfirmationRequest? = null,
    /** Stable machine code only; no exception, text field or credential. */
    val errorCode: String? = null,
)

sealed interface AndroidAccessibilityAdapterResult {
    data class Success(
        val postcondition: AccessibilityPostcondition,
        val actionCode: String,
        val resultingCorrelation: UiSnapshotCorrelation?,
        val trust: UiDataTrust,
    ) : AndroidAccessibilityAdapterResult

    data object StaleTarget : AndroidAccessibilityAdapterResult

    data object Unsupported : AndroidAccessibilityAdapterResult

    data class Failure(val code: String) : AndroidAccessibilityAdapterResult
}

/**
 * Android-facing boundary. Implementations reacquire and recycle platform
 * nodes for each call; they must never retain AccessibilityNodeInfo in a
 * [SemanticNodeHandle].
 */
interface AndroidSemanticAccessibilityAdapter {
    fun click(
        handle: SemanticNodeHandle,
        expectedPostcondition: UiPostconditionExpectation,
    ): AndroidAccessibilityAdapterResult

    fun setText(
        handle: SemanticNodeHandle,
        value: String,
        expectedPostcondition: UiPostconditionExpectation,
    ): AndroidAccessibilityAdapterResult

    fun scroll(
        handle: SemanticNodeHandle,
        direction: UiScrollDirection,
        expectedPostcondition: UiPostconditionExpectation,
    ): AndroidAccessibilityAdapterResult

    fun globalAction(
        action: AccessibilityGlobalAction,
        expectedPostcondition: UiPostconditionExpectation,
    ): AndroidAccessibilityAdapterResult

    fun supportsCoordinateGestures(): Boolean

    fun coordinateGesture(
        correlation: UiSnapshotCorrelation,
        gesture: UiCoordinateGesture,
        expectedPostcondition: UiPostconditionExpectation,
    ): AndroidAccessibilityAdapterResult
}

data class DictationLifecycleStamp(
    val generation: Long,
    val recordingActive: Boolean,
) {
    init {
        require(generation >= 0)
    }
}

interface DictationNonInterferenceGuard {
    /** Passive observation only; this contract intentionally has no stop method. */
    fun beforeAccessibilityAction(): DictationLifecycleStamp

    fun remainedIndependent(before: DictationLifecycleStamp): Boolean
}

interface AccessibilityIdempotencyLedger {
    fun find(key: AccessibilityIdempotencyKey): AccessibilityIdempotencyRecord?

    fun store(key: AccessibilityIdempotencyKey, record: AccessibilityIdempotencyRecord)
}

data class AccessibilityIdempotencyRecord(
    val commandFingerprint: String,
    val result: AccessibilityExecutionResult,
)
