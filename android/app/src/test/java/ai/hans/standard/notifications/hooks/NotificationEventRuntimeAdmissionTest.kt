package ai.hans.standard.notifications.hooks

import ai.hans.standard.codex.AccountPhase
import ai.hans.standard.integration.ClientRuntimePhase
import ai.hans.standard.integration.ClientSessionPhase
import ai.hans.standard.notifications.NotificationInteractiveActivity
import ai.hans.standard.notifications.NotificationTriageIntegration
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class NotificationEventRuntimeAdmissionTest {
    @After fun clearActivityFixture() {
        NotificationInteractiveActivity.entries.forEach { NotificationTriageIntegration.setInteractiveActivity(it, false) }
    }

    @Test fun idleAndBusyOrdinaryMainContextBothPermitNotificationJoin() {
        assertTrue(NotificationEventRuntimeAdmission.allows(ClientRuntimePhase.READY, ClientSessionPhase.READY, AccountPhase.SIGNED_IN))
        assertTrue(NotificationEventRuntimeAdmission.allows(ClientRuntimePhase.READY, ClientSessionPhase.BUSY, AccountPhase.SIGNED_IN))
        NotificationTriageIntegration.setInteractiveActivity(NotificationInteractiveActivity.CODEX_TURN_OR_TOOL, true)
        assertFalse(NotificationTriageIntegration.isInteractiveIdle())
        assertTrue(NotificationTriageIntegration.isNotificationEventIntakeAllowed())
    }

    @Test fun unsignedStartingRecoveringAndFailedRuntimeStatesNeverAdmitNotification() {
        ClientRuntimePhase.entries.filter { it != ClientRuntimePhase.READY }.forEach {
            assertFalse(NotificationEventRuntimeAdmission.allows(it, ClientSessionPhase.BUSY, AccountPhase.SIGNED_IN))
        }
        ClientSessionPhase.entries.filter { it !in setOf(ClientSessionPhase.READY, ClientSessionPhase.BUSY) }.forEach {
            assertFalse(NotificationEventRuntimeAdmission.allows(ClientRuntimePhase.READY, it, AccountPhase.SIGNED_IN))
        }
        AccountPhase.entries.filter { it != AccountPhase.SIGNED_IN }.forEach {
            assertFalse(NotificationEventRuntimeAdmission.allows(ClientRuntimePhase.READY, ClientSessionPhase.READY, it))
        }
        assertFalse(NotificationEventRuntimeAdmission.allows(null, null, null))
    }

    @Test fun dictationAndLiveVoiceBlockIntakeEvenWhenOrdinaryCodexTurnStillActive() {
        NotificationTriageIntegration.setInteractiveActivity(NotificationInteractiveActivity.CODEX_TURN_OR_TOOL, true)
        NotificationTriageIntegration.setInteractiveActivity(NotificationInteractiveActivity.DICTATION, true)
        assertFalse(NotificationTriageIntegration.isNotificationEventIntakeAllowed())
        NotificationTriageIntegration.setInteractiveActivity(NotificationInteractiveActivity.DICTATION, false)
        NotificationTriageIntegration.setInteractiveActivity(NotificationInteractiveActivity.LIVE_VOICE, true)
        assertFalse(NotificationTriageIntegration.isNotificationEventIntakeAllowed())
        NotificationTriageIntegration.setInteractiveActivity(NotificationInteractiveActivity.LIVE_VOICE, false)
        assertTrue(NotificationTriageIntegration.isNotificationEventIntakeAllowed())
    }

    @Test fun voiceReleaseWakesHookWhileOrdinaryCodexIsStillBusyWithoutWaitingForFullIdle() {
        var wakes = 0
        NotificationTriageIntegration.setInteractiveActivity(NotificationInteractiveActivity.CODEX_TURN_OR_TOOL, true)
        NotificationTriageIntegration.setInteractiveActivity(NotificationInteractiveActivity.DICTATION, true)
        val registration = NotificationTriageIntegration.attachNotificationEventWakeup { wakes++ }
        try {
            NotificationTriageIntegration.setInteractiveActivity(NotificationInteractiveActivity.DICTATION, false)
            assertEquals(1, wakes)
            assertFalse(NotificationTriageIntegration.isInteractiveIdle())
            assertTrue(NotificationTriageIntegration.isNotificationEventIntakeAllowed())
            // Repeated same-state and token-independent observations cannot drive another wake.
            NotificationTriageIntegration.setInteractiveActivity(NotificationInteractiveActivity.DICTATION, false)
            assertEquals(1, wakes)
        } finally { registration.close() }
    }
}
