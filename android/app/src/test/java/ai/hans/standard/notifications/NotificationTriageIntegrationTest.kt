package ai.hans.standard.notifications

import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTriageIntegrationTest {
    @Test
    fun nativeEventsJoinRegularWorkAndWakeWhenVoiceEndsWithoutWaitingForCodexIdle() {
        var wakes = 0
        val registration = NotificationTriageIntegration.attachNotificationEventWakeup { wakes++ }
        try {
            NotificationTriageIntegration.setInteractiveActivity(NotificationInteractiveActivity.CODEX_TURN_OR_TOOL, true)
            assertTrue(NotificationTriageIntegration.isNotificationEventIntakeAllowed())
            NotificationTriageIntegration.setInteractiveActivity(NotificationInteractiveActivity.LIVE_VOICE, true)
            assertFalse(NotificationTriageIntegration.isNotificationEventIntakeAllowed())
            NotificationTriageIntegration.setInteractiveActivity(NotificationInteractiveActivity.LIVE_VOICE, false)
            assertTrue(NotificationTriageIntegration.isNotificationEventIntakeAllowed())
            assertFalse(NotificationTriageIntegration.isInteractiveIdle())
            assertEquals(3, wakes)
            NotificationTriageIntegration.setInteractiveActivity(NotificationInteractiveActivity.LIVE_VOICE, false)
            assertEquals(3, wakes)
            NotificationTriageIntegration.setInteractiveActivity(NotificationInteractiveActivity.DICTATION, true)
            assertFalse(NotificationTriageIntegration.isNotificationEventIntakeAllowed())
        } finally {
            NotificationInteractiveActivity.values().forEach { NotificationTriageIntegration.setInteractiveActivity(it, false) }
            registration.close()
        }
    }

    @Test
    fun failedOutputResumeImmediatelyReclosesAuthorityAndRestoresQuarantine() {
        var quarantines = 0
        val registration = NotificationTriageIntegration.attachValidatedSuggestionController(
            object : ValidatedNotificationSuggestionController {
                override fun invalidatePending(supersessionKey: String): Boolean = true
                override fun clearAll(): Boolean = true
                override fun quarantineOutput(): Boolean {
                    quarantines += 1
                    return true
                }
                override fun resumeOutputAfterAuthorityRestored(): Boolean = false
            },
        )
        try {
            assertFalse(NotificationTriageIntegration.markValidatedDeliveryReady())
            assertFalse(NotificationValidatedDeliveryAuthority.isReady())
            assertEquals(1, quarantines)
        } finally {
            NotificationValidatedDeliveryAuthority.quarantine()
            registration.close()
        }
    }

    @Test
    fun authoritativeQuarantineStopsOutputWithoutClearingDurableCenterStage() {
        var clearCalls = 0
        var quarantineCalls = 0
        var resumeCalls = 0
        val registration = NotificationTriageIntegration.attachValidatedSuggestionController(
            object : ValidatedNotificationSuggestionController {
                override fun invalidatePending(supersessionKey: String): Boolean = true

                override fun clearAll(): Boolean {
                    assertFalse(NotificationValidatedDeliveryAuthority.isReady())
                    clearCalls += 1
                    return true
                }

                override fun quarantineOutput(): Boolean {
                    assertFalse(NotificationValidatedDeliveryAuthority.isReady())
                    quarantineCalls += 1
                    return true
                }

                override fun resumeOutputAfterAuthorityRestored(): Boolean {
                    assertTrue(NotificationValidatedDeliveryAuthority.isReady())
                    resumeCalls += 1
                    return true
                }
            },
        )
        try {
            assertTrue(NotificationTriageIntegration.markValidatedDeliveryReady())
            assertTrue(NotificationValidatedDeliveryAuthority.isReady())

            assertTrue(NotificationTriageIntegration.quarantineValidatedDelivery())
            assertFalse(NotificationValidatedDeliveryAuthority.isReady())
            assertEquals(1, quarantineCalls)
            assertEquals(0, clearCalls)

            assertTrue(NotificationTriageIntegration.markValidatedDeliveryReady())
            assertTrue(NotificationValidatedDeliveryAuthority.isReady())
            assertEquals(2, resumeCalls)
        } finally {
            NotificationValidatedDeliveryAuthority.quarantine()
            registration.close()
        }
    }

    @Test
    fun routineInvalidationUsesPassiveCenterWithoutConstructingInteractiveHost() {
        var passiveInvalidations = 0
        var passiveClears = 0
        val initialized = AtomicReference<ValidatedNotificationSuggestionController?>(null)
        val controller = PassiveFirstValidatedSuggestionController(
            initializedInteractiveController = initialized,
            passiveInvalidate = {
                passiveInvalidations += 1
                true
            },
            passiveClear = {
                passiveClears += 1
                true
            },
        )

        assertTrue(controller.invalidatePending("source:" + "a".repeat(64)))
        assertTrue(controller.clearAll())
        assertEquals(1, passiveInvalidations)
        assertEquals(1, passiveClears)

        var interactiveCalls = 0
        initialized.set(
            object : ValidatedNotificationSuggestionController {
                override fun invalidatePending(supersessionKey: String): Boolean {
                    interactiveCalls += 1
                    return true
                }

                override fun clearAll(): Boolean {
                    interactiveCalls += 1
                    return true
                }

                override fun quarantineOutput(): Boolean {
                    interactiveCalls += 1
                    return true
                }

                override fun resumeOutputAfterAuthorityRestored(): Boolean {
                    interactiveCalls += 1
                    return true
                }
            },
        )
        assertTrue(controller.invalidatePending("source:" + "b".repeat(64)))
        assertTrue(controller.clearAll())
        assertEquals(2, interactiveCalls)
        assertEquals(1, passiveInvalidations)
        assertEquals(1, passiveClears)
    }

    @Test
    fun attachingUiSinkWakesDurableRuntimeAndExposesOnlyTypedDelivery() {
        var wakes = 0
        var delivered: UserFacingNotificationDelivery? = null
        var activated: UserFacingNotificationDelivery? = null
        var finalized: Pair<
            UserFacingNotificationDelivery,
            UserFacingNotificationActivationDisposition,
            >? = null
        val wakeRegistration = NotificationTriageIntegration.attachRuntimeWakeup { wakes += 1 }
        val sinkRegistration = NotificationTriageIntegration.attachSuggestionSink(
            object : UserFacingNotificationSuggestionSink {
                override fun deliver(
                    delivery: UserFacingNotificationDelivery,
                ): UserFacingDeliveryDisposition {
                    delivered = delivery
                    return UserFacingDeliveryDisposition.ACCEPTED
                }

                override fun activate(
                    delivery: UserFacingNotificationDelivery,
                ): UserFacingNotificationActivationDisposition {
                    activated = delivery
                    return UserFacingNotificationActivationDisposition.SUPPRESSED
                }

                override fun finalizeActivation(
                    delivery: UserFacingNotificationDelivery,
                    disposition: UserFacingNotificationActivationDisposition,
                ) {
                    finalized = delivery to disposition
                }
            },
        )
        try {
            val proxy = NotificationTriageIntegration.deliverySink()
            val delivery = UserFacingNotificationDelivery(
                receiptId = "receipt",
                idempotencyKey = "stable-key",
                suggestion = UserFacingNotificationSuggestion(
                    "Eine wichtige Nachricht wartet.",
                    NotificationUrgency.NORMAL,
                ),
            )

            assertEquals(1, wakes)
            assertTrue(proxy.isReady())
            assertEquals(UserFacingDeliveryDisposition.ACCEPTED, proxy.deliver(delivery))
            assertEquals(delivery, delivered)
            assertEquals(
                UserFacingNotificationActivationDisposition.SUPPRESSED,
                proxy.activate(delivery),
            )
            assertEquals(delivery, activated)
            proxy.finalizeActivation(
                delivery,
                UserFacingNotificationActivationDisposition.SUPPRESSED,
            )
            assertEquals(
                delivery to UserFacingNotificationActivationDisposition.SUPPRESSED,
                finalized,
            )
        } finally {
            sinkRegistration.close()
            wakeRegistration.close()
        }
        val detached = NotificationTriageIntegration.deliverySink()
        assertFalse(detached.isReady())
        assertEquals(
            UserFacingNotificationActivationDisposition.RETRY,
            detached.activate(
                UserFacingNotificationDelivery(
                    receiptId = "detached",
                    idempotencyKey = "detached",
                    suggestion = UserFacingNotificationSuggestion(
                        "Nicht sichtbar.",
                        NotificationUrgency.LOW,
                    ),
                ),
            ),
        )
    }

    @Test
    fun relevanceHookCarriesOnlyTypedRuntimeFlags() {
        val registration = NotificationTriageIntegration.attachRelevanceContextProvider {
            NotificationRelevanceContext(
                userIsDictating = true,
                liveVoiceIsActive = false,
                screenIsInteractive = true,
                quietModeIsActive = true,
            )
        }
        try {
            assertEquals(
                NotificationRelevanceContext(true, false, true, true),
                NotificationTriageIntegration.contextProvider().current(),
            )
        } finally {
            registration.close()
        }
        val missingProvider = NotificationTriageIntegration.contextProvider().current()
        assertTrue(missingProvider.userIsDictating)
        assertTrue(missingProvider.liveVoiceIsActive)
        assertTrue(missingProvider.quietModeIsActive)
    }

    @Test
    fun enteringForegroundActivityPreemptsImmediatelyAndIdleTransitionWakesExactlyOnce() {
        var preempts = 0
        var wakes = 0
        val wakeRegistration = NotificationTriageIntegration.attachRuntimeWakeup { wakes += 1 }
        val preemptRegistration =
            NotificationTriageIntegration.attachRuntimePreemption { preempts += 1 }
        try {
            NotificationTriageIntegration.setInteractiveActivity(
                NotificationInteractiveActivity.CODEX_TURN_OR_TOOL,
                true,
            )
            assertEquals(1, preempts)
            assertEquals(0, wakes)

            // Idempotent snapshots must not repeatedly kill a process or emit extra wakes.
            NotificationTriageIntegration.setInteractiveActivity(
                NotificationInteractiveActivity.CODEX_TURN_OR_TOOL,
                true,
            )
            assertEquals(1, preempts)

            NotificationTriageIntegration.setInteractiveActivity(
                NotificationInteractiveActivity.CODEX_TURN_OR_TOOL,
                false,
            )
            assertEquals(1, wakes)
        } finally {
            NotificationTriageIntegration.setInteractiveActivity(
                NotificationInteractiveActivity.CODEX_TURN_OR_TOOL,
                false,
            )
            preemptRegistration.close()
            wakeRegistration.close()
        }
    }

    @Test
    fun revokingEnrichmentAuthorityPreemptsAnAttachedRuntimeImmediately() {
        var preempts = 0
        val registration = NotificationTriageIntegration.attachRuntimePreemption {
            preempts += 1
        }
        try {
            NotificationTriageIntegration.preemptForEnrichmentAuthorityChange()
            assertEquals(1, preempts)
        } finally {
            registration.close()
        }
    }

    @Test
    fun privacyControllerIsScopedAndAbsenceRequestsDiskFallback() {
        var clears = 0
        var purges = 0
        val registration = NotificationTriagePrivacyIntegration.attach(
            object : NotificationTriagePrivacyController {
                override fun clearAll(): Boolean {
                    clears += 1
                    return true
                }

                override fun purgeExcluded(): Boolean {
                    purges += 1
                    return true
                }
            },
        )
        try {
            assertTrue(NotificationTriagePrivacyIntegration.clearAll())
            assertTrue(NotificationTriagePrivacyIntegration.purgeExcluded())
            assertEquals(1, clears)
            assertEquals(1, purges)
        } finally {
            registration.close()
        }
        assertFalse(NotificationTriagePrivacyIntegration.clearAll())
        assertFalse(NotificationTriagePrivacyIntegration.purgeExcluded())

        val failing = NotificationTriagePrivacyIntegration.attach(
            object : NotificationTriagePrivacyController {
                override fun clearAll(): Boolean = false
                override fun purgeExcluded(): Boolean = false
            },
        )
        try {
            assertFalse(NotificationTriagePrivacyIntegration.clearAll())
            assertFalse(NotificationTriagePrivacyIntegration.purgeExcluded())
        } finally {
            failing.close()
        }
    }
}
