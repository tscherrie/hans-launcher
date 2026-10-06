package ai.hans.standard.ui

import ai.hans.standard.voice.feedback.OpenAiSpeechRemediation
import ai.hans.standard.voice.feedback.SpeechServiceFailureSnapshot
import ai.hans.standard.voice.feedback.SpeechServiceFailureStore
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Offline UI contract: no API calls, credential access, billing changes or browser launches. */
class SpeechFailureNoticeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun chatCreditFailureHasExplicitBillingActionAndDismissPreservesDraft() {
        val state = mutableStateOf(ChatUiState(
            composer = ComposerUiState(text = "Keep this draft"),
            runtimeStatus = RuntimeUiStatus.ONLINE,
            speechFailure = SpeechFailureUiState("API credits exhausted", OpenAiSpeechRemediation.BILLING),
        ))
        val actions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                ChatScreen(state.value, ChatUiCallbacks(
                    onComposerChanged = { actions += "edited" }, onSend = { actions += "sent" },
                    onChooseMedia = {}, onRemoveAttachment = {}, onOpenApps = {}, onOpenPlugins = {},
                    onOpenSettings = {}, onToggleLiveVoice = {},
                    onOpenSpeechFailureHelp = { actions += it.url },
                    onDismissSpeechFailure = { state.value = state.value.copy(speechFailure = null) },
                ))
            }
        }
        compose.onNodeWithTag("speech_failure_notice").assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<String>(), actions) }
        compose.onNodeWithTag("speech_failure_help").performClick()
        compose.runOnIdle { assertEquals(listOf(OpenAiSpeechRemediation.BILLING.url), actions) }
        compose.onNodeWithTag("speech_failure_dismiss").performClick()
        compose.onNodeWithTag("speech_failure_notice").assertDoesNotExist()
        compose.onNodeWithTag("composer").assertTextEquals("Keep this draft")
    }

    @Test
    fun expandedLiveScreenShowsProjectLimitActionWithoutAffectingCall() {
        val actions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                LiveVoiceCallScreen(LiveVoiceUiStatus.RECONNECTING, false,
                    onInputMutedChanged = { actions += "mute" }, onHangUp = { actions += "hangup" },
                    speechFailure = SpeechFailureUiState("API project spending limit reached", OpenAiSpeechRemediation.PROJECT_SETTINGS),
                    onOpenSpeechFailureHelp = { actions += it.url },
                )
            }
        }
        compose.onNodeWithTag("speech_failure_notice").assertIsDisplayed()
        compose.onNodeWithTag("speech_failure_help").performClick()
        compose.runOnIdle { assertEquals(listOf(OpenAiSpeechRemediation.PROJECT_SETTINGS.url), actions) }
    }

    @Test
    fun ordinaryRateLimitOffersNoBillingActionAndRecompositionDoesNotRetry() {
        val failure = mutableStateOf(SpeechFailureUiState("Please wait and try again"))
        val actions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme { SpeechFailureNotice(failure.value, { actions += it.url }, { actions += "dismiss" }) }
        }
        compose.onNodeWithTag("speech_failure_notice").assertIsDisplayed()
        compose.onNodeWithTag("speech_failure_help").assertDoesNotExist()
        compose.runOnIdle { failure.value = SpeechFailureUiState("Check your network") }
        compose.onNodeWithTag("speech_failure_help").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), actions) }
    }

    @Test
    fun dismissOfRenderedOlderFailureCannotEraseNewerRuntimeFailure() {
        val store = SpeechServiceFailureStore()
        val events = mutableListOf<SpeechServiceFailureSnapshot>()
        store.addObserver { events += it }
        store.report("quota_exhausted")
        val renderedA = SpeechFailureUiState("Failure A", OpenAiSpeechRemediation.BILLING, events.last().revision)
        compose.setContent {
            MaterialTheme { SpeechFailureNotice(renderedA, {}, store::dismiss) }
        }
        // Model state has advanced to B while the displayed button still belongs to frame A.
        compose.runOnIdle { store.report("spending_limit_reached") }
        compose.onNodeWithTag("speech_failure_dismiss").performClick()
        compose.runOnIdle { assertEquals("spending_limit_reached", events.last().code) }
    }

    @Test
    fun smallLiveViewportAndLargeTextKeepHangUpVisibleWhileFailureDetailsScroll() {
        val actions = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                MaterialTheme {
                    Box(Modifier.width(360.dp).height(320.dp)) {
                        LiveVoiceCallScreen(LiveVoiceUiStatus.RECONNECTING, false,
                            onInputMutedChanged = {}, onHangUp = { actions += "hangup" },
                            speechFailure = SpeechFailureUiState(
                                "Your OpenAI API credit or quota is exhausted. Check your balance. ".repeat(6),
                                OpenAiSpeechRemediation.BILLING,
                            ),
                            onOpenSpeechFailureHelp = { actions += it.url },
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("live_call_avatar").assertDoesNotExist()
        compose.onNodeWithTag("live_call_hang_up").assertIsDisplayed()
        compose.onNodeWithTag("speech_failure_help").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("live_call_hang_up").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("hangup"), actions) }
    }
}
