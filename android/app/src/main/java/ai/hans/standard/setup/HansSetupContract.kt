package ai.hans.standard.setup

import java.util.UUID

/** Ordered, device-specific setup contract. Profile data itself remains in hans_profile. */
enum class HansSetupStep {
    INTRO,
    INPUT_CHOICE,
    HARDWARE_MAPPING,
    MICROPHONE_CONSENT,
    MICROPHONE_ACCESS,
    HARDWARE_LIVE_TEST,
    CAMERA_CAPTURE_TEST,
    CAMERA_HOLD_CHOICE,
    CAMERA_HOLD_LIVE_TEST,
    APP_NOTIFICATIONS_CONSENT,
    APP_NOTIFICATIONS_ACCESS,
    NOTIFICATION_LISTENER_CONSENT,
    NOTIFICATION_ACCESS,
    NOTIFICATION_LIVE_TEST,
    ACCESSIBILITY_CONSENT,
    ACCESSIBILITY_ACCESS,
    ACCESSIBILITY_LIVE_TEST,
    HOME_ROLE_CONSENT,
    HOME_ROLE,
    SPEECH_CREDENTIAL_CONSENT,
    SPEECH_CREDENTIAL_ACCESS,
    VOICE_DICTATION_TEST,
    MODEL_REASONING,
    OPTIONAL_CAPABILITIES,
    PERSONAL_PROFILE,
    REVIEW,
    COMPLETE,
}

/** Optional public-API capabilities are offered independently, one at a time. */
enum class HansSetupOptionalCapability {
    EVERYDAY_ACCESS,
    NOTIFICATION_LINK_METADATA,
    CONTACTS,
    CALENDAR,
    LOCATION,
    PHOTOS_VIDEOS,
    AUDIO_MEDIA,
    EXACT_ALARMS,
    QUICK_SETTINGS_TILE,
    ALL_FILES,
}

enum class HansSetupCapabilityDecision {
    ENABLE,
    NOT_NOW,
}

data class HansSetupOptionalCapabilityRecord(
    val decision: HansSetupCapabilityDecision? = null,
    val status: HansSetupStepStatus = HansSetupStepStatus.AWAITING_USER,
    /** Freshly proven effective state. Null means no effective-state probe has completed. */
    val effective: Boolean? = null,
) {
    init {
        if (status == HansSetupStepStatus.VERIFIED) {
            require(decision == HansSetupCapabilityDecision.ENABLE && effective == true) {
                "setup_capability_proof_incomplete"
            }
        }
        if (status == HansSetupStepStatus.SKIPPED) {
            require(decision == HansSetupCapabilityDecision.NOT_NOW && effective == false) {
                "setup_capability_skip_incomplete"
            }
        }
        if (effective == true) {
            require(
                decision == HansSetupCapabilityDecision.ENABLE &&
                    status == HansSetupStepStatus.VERIFIED,
            ) { "setup_capability_effective_without_proof" }
        }
    }

    val terminal: Boolean
        get() = status == HansSetupStepStatus.VERIFIED ||
            status == HansSetupStepStatus.SKIPPED
}

enum class HansSetupStepStatus {
    AWAITING_USER,
    SETTINGS_OPENED,
    VERIFYING,
    VERIFIED,
    SKIPPED,
    BLOCKED,
}

enum class HansSetupInputChoice {
    HARDWARE_TOGGLE,
    HARDWARE_HOLD,
    NO_HARDWARE_KEY,
}

data class HansSetupStepRecord(
    val status: HansSetupStepStatus = HansSetupStepStatus.AWAITING_USER,
    val generation: Long = 0,
    val operationNonce: String? = null,
    val detailCode: String? = null,
    val requestedAtMillis: Long? = null,
    val verifiedAtMillis: Long? = null,
    val liveStartObserved: Boolean = false,
    val auxiliaryEvidenceObserved: Boolean = false,
) {
    init {
        require(generation >= 0)
        operationNonce?.let(::requireSetupNonce)
        detailCode?.let(::requireSetupCode)
        requestedAtMillis?.let { require(it >= 0) }
        verifiedAtMillis?.let { require(it >= 0) }
    }

    val terminal: Boolean
        get() = status == HansSetupStepStatus.VERIFIED ||
            status == HansSetupStepStatus.SKIPPED
}

enum class HansSetupDictationEvidence {
    LISTENING,
    SENT,
    /** Own spoken input and a correlated native Voice reply; not completed agent work. */
    NATIVE_LIVE_COMPLETED,
    FAILED,
}

data class HansSetupDocument(
    val revision: Long = 0,
    val started: Boolean = false,
    val currentStep: HansSetupStep = HansSetupStep.INTRO,
    val steps: Map<HansSetupStep, HansSetupStepRecord> = emptyMap(),
    val inputChoice: HansSetupInputChoice? = null,
    val cameraHoldEnabled: Boolean? = null,
    val requestedModel: String? = null,
    val requestedReasoningEffort: String? = null,
    val effectiveModel: String? = null,
    val effectiveReasoningEffort: String? = null,
    val optionalCapabilities:
        Map<HansSetupOptionalCapability, HansSetupOptionalCapabilityRecord> = emptyMap(),
    val profileConfirmed: Boolean = false,
    val updatedAtMillis: Long = 0,
) {
    init {
        require(revision >= 0)
        require(updatedAtMillis >= 0)
        requestedModel?.let { requireSetupSelection(it, "setup_model") }
        requestedReasoningEffort?.let { requireSetupSelection(it, "setup_reasoning_effort") }
        effectiveModel?.let { requireSetupSelection(it, "setup_model") }
        effectiveReasoningEffort?.let { requireSetupSelection(it, "setup_reasoning_effort") }
        require((requestedModel == null) == (requestedReasoningEffort == null)) {
            "setup_selection_incomplete"
        }
        require((effectiveModel == null) == (effectiveReasoningEffort == null)) {
            "setup_effective_selection_incomplete"
        }
    }

    fun record(step: HansSetupStep): HansSetupStepRecord =
        steps[step] ?: HansSetupStepRecord()

    fun optionalCapabilityRecord(
        capability: HansSetupOptionalCapability,
    ): HansSetupOptionalCapabilityRecord =
        optionalCapabilities[capability] ?: HansSetupOptionalCapabilityRecord()

    val currentOptionalCapability: HansSetupOptionalCapability?
        get() = if (record(HansSetupStep.OPTIONAL_CAPABILITIES).terminal) {
            null
        } else {
            HansSetupOptionalCapability.entries.firstOrNull {
                !optionalCapabilityRecord(it).terminal
            }
        }

    val complete: Boolean
        get() = currentStep == HansSetupStep.COMPLETE &&
            record(HansSetupStep.COMPLETE).status == HansSetupStepStatus.VERIFIED
}

data class HansSetupOperationToken(
    val step: HansSetupStep,
    val generation: Long,
    val nonce: String,
) {
    init {
        require(generation > 0)
        requireSetupNonce(nonce)
        require(step != HansSetupStep.COMPLETE)
    }
}

data class HansSetupProbeResult(
    val verified: Boolean,
    val detailCode: String,
    /** True means the capability may become ready shortly without another settings visit. */
    val transient: Boolean = false,
    /** The platform/device combination cannot satisfy this choice as configured. */
    val blocked: Boolean = false,
) {
    init {
        requireSetupCode(detailCode)
        require(!verified || (!transient && !blocked)) { "invalid_verified_probe_flags" }
        require(!(transient && blocked)) { "invalid_probe_flags" }
    }
}

fun interface HansSetupClock {
    fun nowMillis(): Long
}

fun interface HansSetupNonceSource {
    fun next(): String

    companion object {
        val UUIDS = HansSetupNonceSource { UUID.randomUUID().toString().replace("-", "") }
    }
}

interface HansSetupStorage {
    fun read(): HansSetupDocument

    fun write(document: HansSetupDocument)
}

/**
 * Process-wide guard shared by setup and profile tools. A conversational turn
 * may inspect state repeatedly, but it may cause at most one durable change or
 * visible Android side effect across both namespaces.
 */
class SetupProfileTurnActionGuard {
    private val claimedTurns = linkedSetOf<TurnKey>()

    @Synchronized
    fun claim(threadId: String, turnId: String): Boolean {
        val key = TurnKey(threadId, turnId)
        if (!claimedTurns.add(key)) return false
        while (claimedTurns.size > MAX_REMEMBERED_TURNS) {
            claimedTurns.remove(claimedTurns.first())
        }
        return true
    }

    /**
     * Releases a reservation only when validation proved that no durable change or visible
     * Android action began. Executors must never call this after crossing an effect boundary.
     */
    @Synchronized
    fun releaseUnused(threadId: String, turnId: String): Boolean =
        claimedTurns.remove(TurnKey(threadId, turnId))

    private data class TurnKey(
        val threadId: String,
        val turnId: String,
    )

    companion object {
        const val MAX_REMEMBERED_TURNS = 1_024

        /** Shared by the independently constructed production executors. */
        val PROCESS = SetupProfileTurnActionGuard()
    }
}

/** Kept in the serialized enum for upgrades, never offered or actionable in current setup. */
internal val HANS_SETUP_RETIRED_STEPS: Set<HansSetupStep> = setOf(
    HansSetupStep.HARDWARE_LIVE_TEST,
    HansSetupStep.CAMERA_HOLD_CHOICE,
    HansSetupStep.CAMERA_HOLD_LIVE_TEST,
    HansSetupStep.SPEECH_CREDENTIAL_CONSENT,
    HansSetupStep.SPEECH_CREDENTIAL_ACCESS,
    HansSetupStep.VOICE_DICTATION_TEST,
)

internal val HANS_SETUP_ORDER: List<HansSetupStep> = listOf(
    HansSetupStep.INTRO,
    HansSetupStep.INPUT_CHOICE,
    HansSetupStep.HARDWARE_MAPPING,
    HansSetupStep.MICROPHONE_CONSENT,
    HansSetupStep.MICROPHONE_ACCESS,
    HansSetupStep.CAMERA_CAPTURE_TEST,
    HansSetupStep.APP_NOTIFICATIONS_CONSENT,
    HansSetupStep.APP_NOTIFICATIONS_ACCESS,
    HansSetupStep.NOTIFICATION_LISTENER_CONSENT,
    HansSetupStep.NOTIFICATION_ACCESS,
    HansSetupStep.NOTIFICATION_LIVE_TEST,
    HansSetupStep.ACCESSIBILITY_CONSENT,
    HansSetupStep.ACCESSIBILITY_ACCESS,
    HansSetupStep.ACCESSIBILITY_LIVE_TEST,
    HansSetupStep.HOME_ROLE_CONSENT,
    HansSetupStep.HOME_ROLE,
    HansSetupStep.MODEL_REASONING,
    HansSetupStep.OPTIONAL_CAPABILITIES,
    HansSetupStep.PERSONAL_PROFILE,
    HansSetupStep.REVIEW,
    HansSetupStep.COMPLETE,
).also { order ->
    check(order.toSet() + HANS_SETUP_RETIRED_STEPS == HansSetupStep.entries.toSet()) {
        "setup_order_incomplete"
    }
    check(order.none { it in HANS_SETUP_RETIRED_STEPS }) { "setup_retired_step_reintroduced" }
}

internal fun requireSetupCode(value: String) {
    require(value.matches(Regex("[a-z0-9_.:-]{1,96}"))) { "invalid_setup_code" }
}

internal fun requireSetupNonce(value: String) {
    require(value.matches(Regex("[A-Za-z0-9_-]{16,128}"))) { "invalid_setup_nonce" }
}

internal fun requireSetupSelection(value: String, code: String) {
    require(value.isNotBlank() && value.length <= 128 && value.none(Char::isISOControl)) { code }
}
