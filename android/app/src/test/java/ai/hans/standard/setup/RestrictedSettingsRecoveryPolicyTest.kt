package ai.hans.standard.setup

import ai.hans.standard.localization.TestResourceTextResolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictedSettingsRecoveryPolicyTest {
    @Test
    fun api31And32KeepTheLegacySingleSettingsBehavior() {
        listOf(31, 32).forEach { apiLevel ->
            RestrictedSettingsCapability.entries.forEach { capability ->
                val transition = RestrictedSettingsRecoveryPolicy.onActivityReturned(
                    state(apiLevel, capability),
                    effectiveAccess = false,
                )

                assertNull(transition.state)
                assertEquals(
                    RestrictedSettingsRecoveryEffect.LegacyAndroidUnchanged,
                    transition.effect,
                )
            }
        }
    }

    @Test
    fun api33Through36ExposeTheExplicitRecoveryExplanation() {
        (33..36).forEach { apiLevel ->
            RestrictedSettingsCapability.entries.forEach { capability ->
                val transition = RestrictedSettingsRecoveryPolicy.onActivityReturned(
                    state(apiLevel, capability),
                    effectiveAccess = false,
                )

                assertEquals(
                    RestrictedSettingsRecoveryStage.EXPLANATION_REQUIRED,
                    transition.state?.stage,
                )
                assertEquals(
                    RestrictedSettingsRecoveryEffect.ShowExplanation(capability),
                    transition.effect,
                )
            }
        }
    }

    @Test
    fun missingOrDeniedOemOptionGetsOneRetryThenManualRequiredWithoutALoop() {
        val explanation = RestrictedSettingsRecoveryPolicy.onActivityReturned(
            state(36),
            effectiveAccess = false,
        )
        val appDetails = RestrictedSettingsRecoveryPolicy.openAppDetails(
            checkNotNull(explanation.state),
        )
        assertEquals(
            RestrictedSettingsRecoveryEffect.OpenAppDetails(
                RestrictedSettingsCapability.ACCESSIBILITY,
            ),
            appDetails.effect,
        )

        val retry = RestrictedSettingsRecoveryPolicy.onActivityReturned(
            checkNotNull(appDetails.state),
            effectiveAccess = false,
        )
        assertEquals(
            RestrictedSettingsRecoveryEffect.OpenCapabilitySettings(
                RestrictedSettingsCapability.ACCESSIBILITY,
            ),
            retry.effect,
        )

        val manual = RestrictedSettingsRecoveryPolicy.onActivityReturned(
            checkNotNull(retry.state),
            effectiveAccess = false,
        )
        assertEquals(RestrictedSettingsRecoveryStage.MANUAL_REQUIRED, manual.state?.stage)
        assertTrue(manual.effect is RestrictedSettingsRecoveryEffect.ShowManualRequired)

        val repeatedResume = RestrictedSettingsRecoveryPolicy.onActivityReturned(
            checkNotNull(manual.state),
            effectiveAccess = false,
        )
        assertEquals(RestrictedSettingsRecoveryStage.MANUAL_REQUIRED, repeatedResume.state?.stage)
        assertTrue(
            repeatedResume.effect is RestrictedSettingsRecoveryEffect.ShowManualRequired,
        )
        assertFalse(
            repeatedResume.effect is RestrictedSettingsRecoveryEffect.OpenAppDetails ||
                repeatedResume.effect is RestrictedSettingsRecoveryEffect.OpenCapabilitySettings,
        )

        val notNow = RestrictedSettingsRecoveryPolicy.notNow(
            checkNotNull(repeatedResume.state),
        )
        assertNull(notNow.state)
        assertEquals(
            RestrictedSettingsRecoveryEffect.NotNow(HansSetupStep.ACCESSIBILITY_ACCESS),
            notNow.effect,
        )
    }

    @Test
    fun aMissingPublicSettingsRouteFailsDirectlyToManualRequired() {
        val explanation = RestrictedSettingsRecoveryPolicy.onActivityReturned(
            state(33),
            effectiveAccess = false,
        )
        val opening = RestrictedSettingsRecoveryPolicy.openAppDetails(
            checkNotNull(explanation.state),
        )

        val failed = RestrictedSettingsRecoveryPolicy.settingsDispatchFailed(
            checkNotNull(opening.state),
        )

        assertEquals(RestrictedSettingsRecoveryStage.MANUAL_REQUIRED, failed.state?.stage)
        assertTrue(failed.effect is RestrictedSettingsRecoveryEffect.ShowManualRequired)
    }

    @Test
    fun everyReturnPointAcceptsOnlyFreshEffectiveAccess() {
        val initial = state(33, RestrictedSettingsCapability.NOTIFICATION_LISTENER)
        assertVerified(
            RestrictedSettingsRecoveryPolicy.onActivityReturned(initial, true),
            initial,
        )

        val explanation = RestrictedSettingsRecoveryPolicy.onActivityReturned(initial, false)
        val appDetails = RestrictedSettingsRecoveryPolicy.openAppDetails(
            checkNotNull(explanation.state),
        )
        assertVerified(
            RestrictedSettingsRecoveryPolicy.onActivityReturned(
                checkNotNull(appDetails.state),
                true,
            ),
            initial,
        )

        val retry = RestrictedSettingsRecoveryPolicy.onActivityReturned(
            checkNotNull(appDetails.state),
            false,
        )
        assertVerified(
            RestrictedSettingsRecoveryPolicy.onActivityReturned(
                checkNotNull(retry.state),
                true,
            ),
            initial,
        )
    }

    @Test
    fun visibleCopyNamesTheOptionalOemActionAndNeverClaimsItExists() {
        RestrictedSettingsCapability.entries.forEach { capability ->
            val explanation = RestrictedSettingsRecoveryCopy.explanation(capability, text = TestResourceTextResolver(java.util.Locale.GERMAN))
            val manual = RestrictedSettingsRecoveryCopy.manualRequired(capability, text = TestResourceTextResolver(java.util.Locale.GERMAN))

            assertTrue(explanation.contains(RestrictedSettingsRecoveryCopy.requiredMenuAction(TestResourceTextResolver(java.util.Locale.GERMAN))))
            assertTrue(explanation.contains("falls vorhanden"))
            assertTrue(explanation.contains("Manche Hersteller zeigen diesen Punkt nicht"))
            assertTrue(manual.contains("möglicherweise nicht"))
            assertTrue(manual.contains("Hans behauptet deshalb keinen Zugriff"))
        }
    }

    @Test
    fun recreationRestoreIsValidatedAndFailsClosed() {
        val original = state(36).copy(
            stage = RestrictedSettingsRecoveryStage.AWAITING_APP_DETAILS_RETURN,
        )
        val restored = RestrictedSettingsRecoveryState.restore(
            apiLevel = original.apiLevel,
            capabilityName = original.capability.name,
            stepName = original.token.step.name,
            generation = original.token.generation,
            nonce = original.token.nonce,
            stageName = original.stage.name,
        )

        assertEquals(original, restored)
        assertNull(
            RestrictedSettingsRecoveryState.restore(
                apiLevel = 36,
                capabilityName = RestrictedSettingsCapability.ACCESSIBILITY.name,
                stepName = HansSetupStep.NOTIFICATION_ACCESS.name,
                generation = 1,
                nonce = "restricted_nonce_123456789",
                stageName = original.stage.name,
            ),
        )
        assertNull(
            RestrictedSettingsRecoveryState.restore(
                apiLevel = 36,
                capabilityName = "UNKNOWN",
                stepName = original.token.step.name,
                generation = original.token.generation,
                nonce = original.token.nonce,
                stageName = original.stage.name,
            ),
        )
    }

    private fun assertVerified(
        transition: RestrictedSettingsRecoveryTransition,
        original: RestrictedSettingsRecoveryState,
    ) {
        assertNull(transition.state)
        assertEquals(
            RestrictedSettingsRecoveryEffect.Verified(
                original.capability,
                original.token,
            ),
            transition.effect,
        )
    }

    private fun state(
        apiLevel: Int,
        capability: RestrictedSettingsCapability = RestrictedSettingsCapability.ACCESSIBILITY,
    ): RestrictedSettingsRecoveryState = RestrictedSettingsRecoveryPolicy.begin(
        apiLevel = apiLevel,
        capability = capability,
        token = HansSetupOperationToken(
            step = capability.setupStep,
            generation = 1,
            nonce = "restricted_nonce_123456789",
        ),
    )
}
