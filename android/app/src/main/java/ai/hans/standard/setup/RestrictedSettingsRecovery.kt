package ai.hans.standard.setup

import ai.hans.standard.R
import ai.hans.standard.localization.HansTextResolver

/**
 * Root-free recovery for Android's sideloaded-app "Restricted settings" gate.
 *
 * Android exposes no public API that tells an app whether the OEM app-info menu contains the
 * visible "Allow restricted settings" action. Hans therefore makes one bounded recovery pass:
 * explain the possible gate, open app details, return to the normal capability page once, and
 * then trust only a fresh effective-state read. Android 12 keeps the pre-existing single-settings
 * route and never enters this recovery branch.
 */
internal enum class RestrictedSettingsCapability(
    val setupStep: HansSetupStep,
) {
    ACCESSIBILITY(HansSetupStep.ACCESSIBILITY_ACCESS),
    NOTIFICATION_LISTENER(HansSetupStep.NOTIFICATION_ACCESS),
}

internal enum class RestrictedSettingsRecoveryStage {
    AWAITING_INITIAL_SETTINGS_RETURN,
    EXPLANATION_REQUIRED,
    AWAITING_APP_DETAILS_RETURN,
    AWAITING_RETRY_SETTINGS_RETURN,
    MANUAL_REQUIRED,
}

internal data class RestrictedSettingsRecoveryState(
    val apiLevel: Int,
    val capability: RestrictedSettingsCapability,
    val token: HansSetupOperationToken,
    val stage: RestrictedSettingsRecoveryStage =
        RestrictedSettingsRecoveryStage.AWAITING_INITIAL_SETTINGS_RETURN,
) {
    init {
        require(apiLevel >= 31) { "restricted_settings_api_unsupported" }
        require(token.step == capability.setupStep) { "restricted_settings_step_mismatch" }
    }

    companion object {
        /** Defensive primitive-only restore seam for Activity recreation. */
        fun restore(
            apiLevel: Int,
            capabilityName: String?,
            stepName: String?,
            generation: Long,
            nonce: String?,
            stageName: String?,
        ): RestrictedSettingsRecoveryState? = runCatching {
            val capability = RestrictedSettingsCapability.valueOf(checkNotNull(capabilityName))
            val step = HansSetupStep.valueOf(checkNotNull(stepName))
            val stage = RestrictedSettingsRecoveryStage.valueOf(checkNotNull(stageName))
            RestrictedSettingsRecoveryState(
                apiLevel = apiLevel,
                capability = capability,
                token = HansSetupOperationToken(step, generation, checkNotNull(nonce)),
                stage = stage,
            )
        }.getOrNull()
    }
}

internal sealed interface RestrictedSettingsRecoveryEffect {
    data object None : RestrictedSettingsRecoveryEffect
    data object LegacyAndroidUnchanged : RestrictedSettingsRecoveryEffect

    data class Verified(
        val capability: RestrictedSettingsCapability,
        val token: HansSetupOperationToken,
    ) : RestrictedSettingsRecoveryEffect

    data class ShowExplanation(
        val capability: RestrictedSettingsCapability,
    ) : RestrictedSettingsRecoveryEffect

    data class OpenAppDetails(
        val capability: RestrictedSettingsCapability,
    ) : RestrictedSettingsRecoveryEffect

    data class OpenCapabilitySettings(
        val capability: RestrictedSettingsCapability,
    ) : RestrictedSettingsRecoveryEffect

    data class ShowManualRequired(
        val capability: RestrictedSettingsCapability,
        val token: HansSetupOperationToken,
    ) : RestrictedSettingsRecoveryEffect

    data class NotNow(
        val step: HansSetupStep,
    ) : RestrictedSettingsRecoveryEffect
}

internal data class RestrictedSettingsRecoveryTransition(
    val state: RestrictedSettingsRecoveryState?,
    val effect: RestrictedSettingsRecoveryEffect,
)

internal object RestrictedSettingsRecoveryPolicy {
    const val MIN_RECOVERY_API = 33

    fun begin(
        apiLevel: Int,
        capability: RestrictedSettingsCapability,
        token: HansSetupOperationToken,
    ) = RestrictedSettingsRecoveryState(apiLevel, capability, token)

    /** Called only after Hans has actually returned from the externally opened Android page. */
    fun onActivityReturned(
        state: RestrictedSettingsRecoveryState,
        effectiveAccess: Boolean,
    ): RestrictedSettingsRecoveryTransition {
        if (effectiveAccess) {
            return RestrictedSettingsRecoveryTransition(
                state = null,
                effect = RestrictedSettingsRecoveryEffect.Verified(
                    state.capability,
                    state.token,
                ),
            )
        }
        return when (state.stage) {
            RestrictedSettingsRecoveryStage.AWAITING_INITIAL_SETTINGS_RETURN -> {
                if (state.apiLevel < MIN_RECOVERY_API) {
                    RestrictedSettingsRecoveryTransition(
                        state = null,
                        effect = RestrictedSettingsRecoveryEffect.LegacyAndroidUnchanged,
                    )
                } else {
                    val next = state.copy(
                        stage = RestrictedSettingsRecoveryStage.EXPLANATION_REQUIRED,
                    )
                    RestrictedSettingsRecoveryTransition(
                        state = next,
                        effect = RestrictedSettingsRecoveryEffect.ShowExplanation(
                            state.capability,
                        ),
                    )
                }
            }

            RestrictedSettingsRecoveryStage.AWAITING_APP_DETAILS_RETURN -> {
                val next = state.copy(
                    stage = RestrictedSettingsRecoveryStage.AWAITING_RETRY_SETTINGS_RETURN,
                )
                RestrictedSettingsRecoveryTransition(
                    state = next,
                    effect = RestrictedSettingsRecoveryEffect.OpenCapabilitySettings(
                        state.capability,
                    ),
                )
            }

            RestrictedSettingsRecoveryStage.AWAITING_RETRY_SETTINGS_RETURN ->
                manualRequired(state)

            RestrictedSettingsRecoveryStage.EXPLANATION_REQUIRED ->
                RestrictedSettingsRecoveryTransition(
                    state = state,
                    effect = RestrictedSettingsRecoveryEffect.ShowExplanation(
                        state.capability,
                    ),
                )

            RestrictedSettingsRecoveryStage.MANUAL_REQUIRED -> manualRequired(state)
        }
    }

    fun openAppDetails(
        state: RestrictedSettingsRecoveryState,
    ): RestrictedSettingsRecoveryTransition {
        require(state.stage == RestrictedSettingsRecoveryStage.EXPLANATION_REQUIRED) {
            "restricted_settings_recovery_not_ready"
        }
        val next = state.copy(
            stage = RestrictedSettingsRecoveryStage.AWAITING_APP_DETAILS_RETURN,
        )
        return RestrictedSettingsRecoveryTransition(
            state = next,
            effect = RestrictedSettingsRecoveryEffect.OpenAppDetails(state.capability),
        )
    }

    /** A missing OEM route is terminal for this pass; no automatic retry loop is permitted. */
    fun settingsDispatchFailed(
        state: RestrictedSettingsRecoveryState,
    ): RestrictedSettingsRecoveryTransition = manualRequired(state)

    fun notNow(
        state: RestrictedSettingsRecoveryState,
    ): RestrictedSettingsRecoveryTransition = RestrictedSettingsRecoveryTransition(
        state = null,
        effect = RestrictedSettingsRecoveryEffect.NotNow(state.capability.setupStep),
    )

    private fun manualRequired(
        state: RestrictedSettingsRecoveryState,
    ): RestrictedSettingsRecoveryTransition {
        val next = state.copy(stage = RestrictedSettingsRecoveryStage.MANUAL_REQUIRED)
        return RestrictedSettingsRecoveryTransition(
            state = next,
            effect = RestrictedSettingsRecoveryEffect.ShowManualRequired(
                state.capability,
                state.token,
            ),
        )
    }
}

internal object RestrictedSettingsRecoveryCopy {
    fun positiveLabel(text: HansTextResolver) = text.text(R.string.integration_restricted_app_info)
    fun notNowLabel(text: HansTextResolver) = text.text(R.string.integration_restricted_not_now)
    fun requiredMenuAction(text: HansTextResolver) = text.text(R.string.integration_restricted_allow)
    fun explanation(capability: RestrictedSettingsCapability, text: HansTextResolver): String =
        text.text(R.string.integration_restricted_explanation, requiredMenuAction(text), capability.normalSettingsLabel(text))
    fun manualRequired(capability: RestrictedSettingsCapability, text: HansTextResolver): String =
        text.text(R.string.integration_restricted_manual, requiredMenuAction(text), capability.normalSettingsLabel(text))
    fun verified(capability: RestrictedSettingsCapability, text: HansTextResolver): String = when (capability) {
        RestrictedSettingsCapability.ACCESSIBILITY -> text.text(R.string.integration_restricted_app_control_active)
        RestrictedSettingsCapability.NOTIFICATION_LISTENER -> text.text(R.string.integration_restricted_notification_active)
    }
    private fun RestrictedSettingsCapability.normalSettingsLabel(text: HansTextResolver): String = when (this) {
        RestrictedSettingsCapability.ACCESSIBILITY -> text.text(R.string.integration_restricted_accessibility_page)
        RestrictedSettingsCapability.NOTIFICATION_LISTENER -> text.text(R.string.integration_restricted_notification_page)
    }
}
