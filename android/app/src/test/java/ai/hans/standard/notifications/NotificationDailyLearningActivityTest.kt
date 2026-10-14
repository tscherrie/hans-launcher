package ai.hans.standard.notifications

import org.junit.Assert.*
import org.junit.Test

class NotificationDailyLearningActivityTest {
    @Test fun independentActivityObserversDoNotReplaceNotificationPreemptionOrIdleHooks() {
        NotificationInteractiveActivity.entries.forEach { NotificationTriageIntegration.setInteractiveActivity(it, false) }
        var preemptions = 0
        var idleWakes = 0
        val observations = mutableListOf<Boolean>()
        val preempt = NotificationTriageIntegration.attachRuntimePreemption { preemptions++ }
        val wake = NotificationTriageIntegration.attachIdleWakeup { idleWakes++ }
        val observer = NotificationTriageIntegration.attachInteractiveActivityChange {
            observations += NotificationTriageIntegration.isInteractiveIdle()
        }
        try {
            assertEquals(listOf(true), observations)
            NotificationTriageIntegration.setInteractiveActivity(NotificationInteractiveActivity.DICTATION, true)
            assertEquals(1, preemptions)
            assertEquals(listOf(true, false), observations)
            NotificationTriageIntegration.setInteractiveActivity(NotificationInteractiveActivity.LIVE_VOICE, true)
            NotificationTriageIntegration.setInteractiveActivity(NotificationInteractiveActivity.DICTATION, false)
            assertEquals(0, idleWakes)
            NotificationTriageIntegration.setInteractiveActivity(NotificationInteractiveActivity.LIVE_VOICE, false)
            assertEquals(1, idleWakes)
            assertTrue(observations.last())
            observer.close()
            val count = observations.size
            NotificationTriageIntegration.setInteractiveActivity(NotificationInteractiveActivity.DICTATION, true)
            assertEquals(3, preemptions)
            assertEquals(count, observations.size)
        } finally {
            observer.close(); preempt.close(); wake.close()
            NotificationInteractiveActivity.entries.forEach { NotificationTriageIntegration.setInteractiveActivity(it, false) }
        }
    }

    @Test fun throwingObserverCannotBlockOtherWorkersOrUnsubscribe() {
        NotificationInteractiveActivity.entries.forEach { NotificationTriageIntegration.setInteractiveActivity(it, false) }
        var delivered = 0
        val broken = NotificationTriageIntegration.attachInteractiveActivityChange { error("synthetic observer") }
        val healthy = NotificationTriageIntegration.attachInteractiveActivityChange { delivered++ }
        try {
            NotificationTriageIntegration.setInteractiveActivity(NotificationInteractiveActivity.CODEX_TURN_OR_TOOL, true)
            NotificationTriageIntegration.setInteractiveActivity(NotificationInteractiveActivity.CODEX_TURN_OR_TOOL, true)
            NotificationTriageIntegration.setInteractiveActivity(NotificationInteractiveActivity.CODEX_TURN_OR_TOOL, false)
            assertEquals(3, delivered)
        } finally {
            broken.close(); healthy.close()
            NotificationInteractiveActivity.entries.forEach { NotificationTriageIntegration.setInteractiveActivity(it, false) }
        }
    }
}
