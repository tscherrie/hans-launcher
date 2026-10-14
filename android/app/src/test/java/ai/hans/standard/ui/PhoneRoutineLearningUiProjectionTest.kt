package ai.hans.standard.ui

import ai.hans.standard.R
import ai.hans.standard.fastphone.routines.DailyPhoneRoutineLearningSnapshot
import ai.hans.standard.fastphone.routines.DailyPhoneRoutineLearningStatus
import ai.hans.standard.fastphone.routines.android.PhoneRoutineDailyScheduleStatus
import ai.hans.standard.fastphone.routines.android.PhoneRoutineDailySchedulingSnapshot
import ai.hans.standard.localization.TestResourceTextResolver
import ai.hans.standard.settings.HansSettings
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PhoneRoutineLearningUiProjectionTest {
    @Test fun acceptedAndroidSchedulingProofIsRequiredForScheduledStatus() {
        val ready = PhoneRoutineLearningUiState(enabled = true, status = DailyPhoneRoutineLearningStatus.READY)
        assertEquals(R.string.fast_phone_learning_waiting, phoneRoutineLearningStatusText(ready))
        assertEquals(R.string.fast_phone_learning_scheduled, phoneRoutineLearningStatusText(ready.copy(
            scheduleStatus = PhoneRoutineDailyScheduleStatus.SCHEDULED, scheduled = true)))
        assertEquals(R.string.fast_phone_learning_schedule_failed, phoneRoutineLearningStatusText(ready.copy(
            scheduleStatus = PhoneRoutineDailyScheduleStatus.SCHEDULING_FAILED)))
    }

    @Test fun activeUserNetworkAndLoginGatesOverrideAQueuedDailyJob() {
        val ready = PhoneRoutineLearningUiState(enabled = true, status = DailyPhoneRoutineLearningStatus.READY,
            scheduled = true)
        val waiting = mapOf(
            PhoneRoutineDailyScheduleStatus.WAITING_FOR_LOGIN to R.string.fast_phone_learning_wait_login,
            PhoneRoutineDailyScheduleStatus.WAITING_FOR_INTERACTION to R.string.fast_phone_learning_wait_idle,
            PhoneRoutineDailyScheduleStatus.WAITING_FOR_NETWORK to R.string.fast_phone_learning_wait_network,
            PhoneRoutineDailyScheduleStatus.SCHEDULING_FAILED to R.string.fast_phone_learning_schedule_failed,
        )
        waiting.forEach { (schedule, expected) ->
            assertEquals(expected, phoneRoutineLearningStatusText(ready.copy(scheduleStatus = schedule)))
        }
        for (gate in listOf(PhoneRoutineDailyScheduleStatus.WAITING_FOR_BACKUP,
            PhoneRoutineDailyScheduleStatus.WAITING_FOR_FIRST_UNLOCK, PhoneRoutineDailyScheduleStatus.CLOSED)) {
            assertEquals(R.string.fast_phone_learning_waiting,
                phoneRoutineLearningStatusText(ready.copy(scheduleStatus = gate, scheduled = false)))
        }
    }

    @Test fun dailyProgressRetainsExactLocalStateButNeverCopiesPrivateStorageErrorIntoUi() {
        val secret = "synthetic-private-goal sk-synthetic-test-only"
        val raw = DailyPhoneRoutineLearningSnapshot(true, DailyPhoneRoutineLearningStatus.BACKOFF,
            1_234L, 2, secret, 4_000L, 7)
        val ui = PhoneRoutineLearningUiState.from(raw, PhoneRoutineDailySchedulingSnapshot(
            PhoneRoutineDailyScheduleStatus.SCHEDULED, true, 4_000L))
        val output = HansClientUiProjector.project(null, HansLocalUiState(phoneRoutineLearning = ui),
            HansSettings(), TestResourceTextResolver(Locale.ENGLISH))
        assertEquals(ui, output.settings.phoneRoutineLearning)
        assertEquals(1_234L, ui.lastSuccessAtMillis)
        assertEquals(2, ui.candidateCount)
        assertEquals(R.string.fast_phone_learning_retry, phoneRoutineLearningStatusText(ui))
        assertFalse(ui.toString().contains(secret))
        assertFalse(output.settings.toString().contains(secret))
    }

    @Test fun offAndStorageErrorsDoNotClaimSuccessfulOrScheduledLearning() {
        assertEquals(R.string.fast_phone_learning_off, phoneRoutineLearningStatusText(PhoneRoutineLearningUiState()))
        assertEquals(R.string.fast_phone_learning_error, phoneRoutineLearningStatusText(
            PhoneRoutineLearningUiState(status = DailyPhoneRoutineLearningStatus.ERROR)))
        for (status in listOf(DailyPhoneRoutineLearningStatus.ALREADY_ANALYZED_TODAY,
            DailyPhoneRoutineLearningStatus.NO_NEW_HISTORY)) {
            assertEquals(R.string.fast_phone_learning_current, phoneRoutineLearningStatusText(
                PhoneRoutineLearningUiState(enabled = true, status = status)))
        }
    }
}
