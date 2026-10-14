package ai.hans.standard.ui

import ai.hans.standard.fastphone.routines.DailyPhoneRoutineLearningSnapshot
import ai.hans.standard.fastphone.routines.DailyPhoneRoutineLearningStatus
import ai.hans.standard.fastphone.routines.android.PhoneRoutineDailyScheduleStatus
import ai.hans.standard.fastphone.routines.android.PhoneRoutineDailySchedulingSnapshot
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PhoneRoutineLearningSettingsAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dailyLearningIsOffByDefaultAndCannotEnableWithoutFastPhoneOptIn() {
        compose.setGermanContent {
            MaterialTheme { SettingsContent(SettingsUiState(), callbacks(), group = SettingsGroup.RUNTIME) }
        }
        compose.onNodeWithTag("fast_phone_daily_learning_enabled").performScrollTo()
            .assertIsOff().assertIsNotEnabled()
        compose.onNodeWithTag("fast_phone_daily_learning_status").performScrollTo()
            .assertTextEquals("Tägliches Lernen ist ausgeschaltet.")
        compose.onNodeWithTag("fast_phone_daily_learning_result").assertDoesNotExist()
    }

    @Test fun rejectedScheduleIsVisibleAndNoUnacceptedScheduleIsClaimed() {
        val state = mutableStateOf(SettingsUiState(fastPhoneUse = FastPhoneUseUiState(true),
            phoneRoutineLearning = PhoneRoutineLearningUiState(enabled = true,
                status = DailyPhoneRoutineLearningStatus.READY,
                scheduleStatus = PhoneRoutineDailyScheduleStatus.SCHEDULING_FAILED)))
        compose.setGermanContent {
            MaterialTheme { SettingsContent(state.value, callbacks(), group = SettingsGroup.RUNTIME) }
        }
        compose.onNodeWithTag("fast_phone_daily_learning_status").performScrollTo().assertTextEquals(
            "Android hat die Hintergrundplanung nicht angenommen. Hans versucht es beim Öffnen erneut.")
        compose.onNodeWithText("Tägliche Analyse bei Android eingeplant.").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(phoneRoutineLearning =
            state.value.phoneRoutineLearning.copy(scheduleStatus = PhoneRoutineDailyScheduleStatus.WAITING_FOR_BACKUP)) }
        compose.onNodeWithTag("fast_phone_daily_learning_status").performScrollTo()
            .assertTextEquals("Wartet auf eine Gelegenheit im Hintergrund.")
        compose.onNodeWithText("Tägliche Analyse bei Android eingeplant.").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(phoneRoutineLearning =
            state.value.phoneRoutineLearning.copy(scheduleStatus = PhoneRoutineDailyScheduleStatus.SCHEDULED,
                scheduled = true)) }
        compose.onNodeWithTag("fast_phone_daily_learning_status").performScrollTo()
            .assertTextEquals("Tägliche Analyse bei Android eingeplant.")
    }

    @Test fun togglingAndForgettingDispatchCallbacksWithoutOptimisticStateOrPrivateDetails() {
        val toggles = mutableListOf<Boolean>()
        var forgotten = 0
        val privateGoal = "synthetic-private-goal-never-for-display"
        val fakeKey = "sk-synthetic-never-a-real-secret"
        val persisted = DailyPhoneRoutineLearningSnapshot(false, DailyPhoneRoutineLearningStatus.DISABLED,
            null, 0, "$privateGoal $fakeKey", null, 0)
        val state = mutableStateOf(SettingsUiState(fastPhoneUse = FastPhoneUseUiState(true),
            phoneRoutineLearning = PhoneRoutineLearningUiState.from(persisted,
                PhoneRoutineDailySchedulingSnapshot(PhoneRoutineDailyScheduleStatus.DISABLED, false, null))))
        compose.setGermanContent {
            MaterialTheme { SettingsContent(state.value, callbacks().copy(
                onPhoneRoutineLearningChanged = toggles::add,
                onClearFastPhoneRoutines = { forgotten++ },
            ), group = SettingsGroup.RUNTIME) }
        }
        compose.onNodeWithTag("fast_phone_daily_learning_enabled").performScrollTo().performClick().assertIsOff()
        assertEquals(listOf(true), toggles)
        compose.runOnIdle { state.value = state.value.copy(phoneRoutineLearning =
            state.value.phoneRoutineLearning.copy(enabled = true, status = DailyPhoneRoutineLearningStatus.READY)) }
        compose.onNodeWithTag("fast_phone_daily_learning_enabled").assertIsOn().performClick().assertIsOn()
        assertEquals(listOf(true, false), toggles)
        compose.onNodeWithTag("clear_fast_phone_routines").performScrollTo().performClick()
        assertEquals(1, forgotten)
        compose.onNodeWithText(privateGoal, substring = true).assertDoesNotExist()
        compose.onNodeWithText(fakeKey, substring = true).assertDoesNotExist()
    }

    private fun callbacks() = SettingsUiCallbacks(onBack = {}, onStartGettingToKnow = {},
        onModelSelected = {}, onReasoningEffortSelected = {}, onVoiceSelected = {},
        onSpeechRateSelected = {}, onReadAloudModeSelected = {}, onPreviewVoice = {},
        onStartActionKeySetup = {}, onStartModelToggleKeySetup = {}, onCancelActionKeySetup = {},
        onClearActionKey = {}, onClearModelToggleKey = {}, onCapabilityAccessRequested = {})
}
