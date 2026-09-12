package ai.hans.standard.ui

import android.content.ComponentName
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Presentation-only fixtures: no microphone, service, account, or live network request. */
class LiveVoiceCallPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun backMinimizesAndExpandKeepsTheCallAndShowsTheLauncher() {
        var starts = 0
        var stops = 0
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    ChatUiState(liveVoiceStatus = LiveVoiceUiStatus.LISTENING),
                    callbacks().copy(onStartLiveVoice = { starts++ }, onStopLiveVoice = { stops++ }),
                )
            }
        }

        compose.onNodeWithTag("live_call_minimize").assertDoesNotExist()
        compose.onNodeWithText("Minimieren").assertDoesNotExist()
        pressBack()
        compose.onNodeWithTag("live_call_screen").assertDoesNotExist()
        compose.onNodeWithTag("live_call_bar").assertIsDisplayed()
        compose.onNodeWithTag("chat_timeline").assertIsDisplayed()
        compose.onNodeWithTag("composer").assertIsDisplayed()
        compose.onNodeWithTag("live_call_expand").performClick()
        compose.onNodeWithTag("live_call_screen").assertIsDisplayed()
        compose.onNodeWithTag("live_call_bar").assertDoesNotExist()
        compose.onNodeWithText("Minimieren").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, starts); assertEquals(0, stops) }
    }

    @Test fun leavingTheLauncherMinimizesWithoutStoppingAndReturnDoesNotExpand() {
        lateinit var owner: CallLifecycleOwner
        compose.runOnUiThread { owner = CallLifecycleOwner() }
        var stops = 0
        val state = mutableStateOf(ChatUiState(liveVoiceStatus = LiveVoiceUiStatus.LISTENING))
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme {
                    ChatScreen(state.value, callbacks().copy(onStopLiveVoice = { stops++ }))
                }
            }
        }
        compose.onNodeWithTag("live_call_screen").assertIsDisplayed()

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.onNodeWithTag("live_call_screen").assertDoesNotExist()
        compose.onNodeWithTag("live_call_bar").assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(liveVoiceStatus = LiveVoiceUiStatus.WAITING_FOR_TASK)
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.onNodeWithTag("live_call_screen").assertDoesNotExist()
        compose.onNodeWithTag("live_call_bar_status").assertTextEquals("Hans kümmert sich darum")
        compose.runOnIdle { assertEquals(0, stops) }
    }

    @Test fun compactMuteWaitsForRuntimeConfirmationAndHangUpDoesNotRestart() {
        val state = mutableStateOf(ChatUiState(liveVoiceStatus = LiveVoiceUiStatus.LISTENING))
        val muteRequests = mutableListOf<Boolean>()
        var starts = 0
        var stops = 0
        compose.setContent {
            MaterialTheme {
                ChatScreen(state.value, callbacks().copy(
                    onLiveVoiceInputMutedChanged = muteRequests::add,
                    onStartLiveVoice = { starts++ },
                    onStopLiveVoice = { stops++ },
                ))
            }
        }
        pressBack()
        compose.onNodeWithTag("live_call_bar_mute").assertIsNotSelected().performClick()
        compose.onNodeWithTag("live_call_bar_mute").assertIsNotSelected()
        compose.runOnIdle {
            assertEquals(listOf(true), muteRequests)
            state.value = state.value.copy(liveVoiceInputMuted = true)
        }
        compose.onNodeWithTag("live_call_bar_mute").assertIsSelected()
            .assertContentDescriptionEquals("Mikrofon ist stumm. Mikrofon einschalten")
        compose.onNodeWithTag("live_call_bar_hang_up").performClick()
        compose.runOnIdle { assertEquals(0, starts); assertEquals(1, stops) }
    }

    @Test fun aNewCallStartsExpandedAfterThePreviousCallWasMinimized() {
        val state = mutableStateOf(ChatUiState(liveVoiceStatus = LiveVoiceUiStatus.LISTENING))
        compose.setContent { MaterialTheme { ChatScreen(state.value, callbacks()) } }
        pressBack()
        compose.runOnIdle { state.value = state.value.copy(liveVoiceStatus = null) }
        compose.onNodeWithTag("live_call_bar").assertDoesNotExist()
        compose.runOnIdle {
            state.value = state.value.copy(liveVoiceStatus = LiveVoiceUiStatus.CONNECTING)
        }
        compose.onNodeWithTag("live_call_screen").assertIsDisplayed()
        compose.onNodeWithTag("live_call_bar").assertDoesNotExist()
    }

    @Test fun launcherNavigationKeepsMinimizationAndObservesPauseOutsideChat() {
        lateinit var owner: CallLifecycleOwner
        compose.runOnUiThread { owner = CallLifecycleOwner() }
        val state = mutableStateOf(HansUiState(
            destination = HansDestination.CHAT,
            chat = ChatUiState(liveVoiceStatus = LiveVoiceUiStatus.LISTENING),
        ))
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme { HansApp(state.value, appCallbacks()) }
            }
        }
        pressBack()
        compose.runOnIdle { state.value = state.value.copy(destination = HansDestination.APPS) }
        compose.onNodeWithTag("chat_screen").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(destination = HansDestination.CHAT) }
        compose.onNodeWithTag("live_call_screen").assertDoesNotExist()
        compose.onNodeWithTag("live_call_bar").assertIsDisplayed()

        // The lifecycle observer must also survive while ChatScreen is absent.
        compose.onNodeWithTag("live_call_expand").performClick()
        compose.runOnIdle { state.value = state.value.copy(destination = HansDestination.APPS) }
        compose.onNodeWithTag("chat_screen").assertDoesNotExist()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.RESUMED
            state.value = state.value.copy(destination = HansDestination.CHAT)
        }
        compose.onNodeWithTag("live_call_screen").assertDoesNotExist()
        compose.onNodeWithTag("live_call_bar").assertIsDisplayed()
    }

    @Test fun externalForegroundAppOwnsTheWindowWhileTheCallRemainsActive() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetPackage = instrumentation.targetContext.packageName
        val externalPackage = instrumentation.context.packageName
        assertNotEquals("The foreground fixture must be a different installed package", targetPackage, externalPackage)
        assertEquals("Compose must be hosted by the target, not the test APK", targetPackage, compose.activity.packageName)
        val externalIntent = Intent().setComponent(ComponentName(
            externalPackage,
            LiveCallForegroundBystanderActivity::class.java.name,
        )).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        var stops = 0
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    ChatUiState(liveVoiceStatus = LiveVoiceUiStatus.WAITING_FOR_TASK),
                    callbacks().copy(onStopLiveVoice = { stops++ }),
                )
            }
        }
        compose.onNodeWithTag("live_call_screen").assertIsDisplayed()

        try {
            compose.runOnUiThread { compose.activity.startActivity(externalIntent) }
            compose.waitUntil(5_000) {
                instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
                    try { root.packageName?.toString() == externalPackage }
                    finally { @Suppress("DEPRECATION") root.recycle() }
                } == true
            }
            instrumentation.runOnMainSync {
                assertFalse(compose.activity.hasWindowFocus())
                assertFalse(compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                assertEquals(0, stops)
            }
            val foregroundRoot = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)
            try {
                assertEquals(externalPackage, foregroundRoot.packageName?.toString())
                val controls = foregroundRoot.findAccessibilityNodeInfosByText(
                    LiveCallForegroundBystanderActivity.INITIAL_TEXT,
                )
                try {
                    assertEquals(1, controls.size)
                    assertTrue(controls.single().performAction(AccessibilityNodeInfo.ACTION_CLICK))
                } finally {
                    controls.forEach { @Suppress("DEPRECATION") it.recycle() }
                }
            } finally {
                @Suppress("DEPRECATION") foregroundRoot.recycle()
            }
            compose.waitUntil(5_000) {
                instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
                    val controls = root.findAccessibilityNodeInfosByText(
                        LiveCallForegroundBystanderActivity.CLICKED_TEXT,
                    )
                    try { root.packageName?.toString() == externalPackage && controls.size == 1 }
                    finally {
                        controls.forEach { @Suppress("DEPRECATION") it.recycle() }
                        @Suppress("DEPRECATION") root.recycle()
                    }
                } == true
            }
        } finally {
            // Return to this fixture, not the real launcher or a running microphone service.
            val returnIntent = Intent(compose.activity, ComponentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            instrumentation.runOnMainSync { compose.activity.startActivity(returnIntent) }
        }
        compose.waitUntil(5_000) {
            compose.runOnUiThread {
                compose.activity.hasWindowFocus() &&
                    compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("live_call_screen").assertDoesNotExist()
        compose.onNodeWithTag("live_call_bar").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, stops) }
    }

    private fun pressBack() {
        // Exercise the Activity dispatcher and the production BackHandler, not a removed button.
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    }

    private fun callbacks() = ChatUiCallbacks(
        onComposerChanged = {}, onSend = {}, onChooseMedia = {}, onRemoveAttachment = {},
        onOpenApps = {}, onOpenPlugins = {}, onOpenSettings = {}, onToggleLiveVoice = {},
    )

    private fun appCallbacks() = HansUiCallbacks(
        authGate = AuthGateUiCallbacks({}, {}, {}, {}, {}),
        chat = callbacks(),
        apps = AppsUiCallbacks({}, {}, {}, { _, _ -> }),
        plugins = PluginsUiCallbacks({}, {}, {}, {}),
        settings = SettingsUiCallbacks(
            onBack = {}, onStartGettingToKnow = {}, onModelSelected = {},
            onReasoningEffortSelected = {}, onVoiceSelected = {}, onSpeechRateSelected = {},
            onReadAloudModeSelected = {}, onPreviewVoice = {}, onStartActionKeySetup = {},
            onStartModelToggleKeySetup = {}, onCancelActionKeySetup = {}, onClearActionKey = {},
            onClearModelToggleKey = {}, onCapabilityAccessRequested = {},
        ),
    )

    private class CallLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }
}
