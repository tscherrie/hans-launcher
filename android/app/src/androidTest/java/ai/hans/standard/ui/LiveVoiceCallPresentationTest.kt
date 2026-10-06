package ai.hans.standard.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
        compose.setGermanContent {
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
        compose.setGermanContent {
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
        compose.setGermanContent {
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
        compose.setGermanContent { MaterialTheme { ChatScreen(state.value, callbacks()) } }
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
        compose.setGermanContent {
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
        compose.setGermanContent {
            MaterialTheme {
                ChatScreen(
                    ChatUiState(liveVoiceStatus = LiveVoiceUiStatus.WAITING_FOR_TASK),
                    callbacks().copy(onStopLiveVoice = { stops++ }),
                )
            }
        }
        compose.onNodeWithTag("live_call_screen").assertIsDisplayed()

        // API32's default UiAutomation does not track interactive windows. A window-state
        // event can arrive before input focus switches, leaving its active-window ID stale.
        // Opt into WindowManager-backed tracking and prove the focused application root.
        val uiAutomation = instrumentation.uiAutomation
        val windowDiagnostics = InstrumentationRegistry.getArguments()
            .getString("hansLiveCallWindowDiagnostics") == "true"
        withInteractiveWindowTracking(uiAutomation) {
            if (windowDiagnostics) {
                recordWindowDiagnostics(uiAutomation, "before-external-start")
                uiAutomation.setOnAccessibilityEventListener { event ->
                    try {
                        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                            Log.i("HansLiveCallWindowProbe", "event type=${event.eventType}" +
                                " package=${event.packageName} windowId=${event.windowId}" +
                                " changes=${event.windowChanges} time=${event.eventTime}")
                        }
                    } finally { @Suppress("DEPRECATION") event.recycle() }
                }
            }
            compose.waitUntil(5_000) {
                focusedApplicationRoot(uiAutomation, targetPackage)?.let { root ->
                    @Suppress("DEPRECATION") root.recycle()
                    true
                } == true
            }
            instrumentation.runOnMainSync {
                assertTrue(compose.activity.hasWindowFocus())
                assertTrue(compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            }

            try {
                compose.runOnUiThread { compose.activity.startActivity(externalIntent) }
                var rootSamples = 0
                compose.waitUntil(5_000) {
                    val queryStarted = SystemClock.elapsedRealtime()
                    val root = focusedApplicationRoot(uiAutomation, externalPackage)
                    if (windowDiagnostics) {
                        Log.i("HansLiveCallWindowProbe", "external-root sample=${++rootSamples}" +
                            " queryMs=${SystemClock.elapsedRealtime() - queryStarted}" +
                            " package=${root?.packageName} windowId=${root?.windowId}")
                    }
                    root?.let {
                        @Suppress("DEPRECATION") it.recycle()
                        true
                    } == true
                }
                if (windowDiagnostics) recordWindowDiagnostics(uiAutomation, "external-focused-root-confirmed")
                instrumentation.runOnMainSync {
                    assertFalse(compose.activity.hasWindowFocus())
                    assertFalse(compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                    assertEquals(0, stops)
                }
                val foregroundRoot = requireNotNull(focusedApplicationRoot(uiAutomation, externalPackage))
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
                    focusedApplicationRoot(uiAutomation, externalPackage)?.let { root ->
                        val controls = root.findAccessibilityNodeInfosByText(
                            LiveCallForegroundBystanderActivity.CLICKED_TEXT,
                        )
                        try { controls.size == 1 }
                        finally {
                            controls.forEach { @Suppress("DEPRECATION") it.recycle() }
                            @Suppress("DEPRECATION") root.recycle()
                        }
                    } == true
                }
                if (windowDiagnostics) recordWindowDiagnostics(uiAutomation, "external-click-postcondition-confirmed")
            } catch (failure: Throwable) {
                if (windowDiagnostics) {
                    try {
                        recordWindowDiagnostics(uiAutomation, "original-failure")
                    } catch (diagnosticFailure: Throwable) {
                        failure.addSuppressed(diagnosticFailure)
                    }
                }
                throw failure
            } finally {
                if (windowDiagnostics) uiAutomation.setOnAccessibilityEventListener(null)
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
    }

    /** Test-owned service configuration; restore even if the initial foreground proof fails. */
    private inline fun withInteractiveWindowTracking(automation: UiAutomation, block: () -> Unit) {
        val originalFlags = automation.serviceInfo.flags
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            block()
        } finally {
            try {
                automation.setOnAccessibilityEventListener(null)
            } finally {
                automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
                if (InstrumentationRegistry.getArguments().getString("hansLiveCallWindowDiagnostics") == "true") {
                    Log.i("HansLiveCallWindowProbe", "restored flags=${automation.serviceInfo.flags}")
                }
            }
        }
    }

    /** Only input-focused APPLICATION windows qualify; refresh and bind the actual root ID. */
    private fun focusedApplicationRoot(automation: UiAutomation, expectedPackage: String): AccessibilityNodeInfo? {
        val windows = automation.windows
        try {
            val window = windows.singleOrNull {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
            } ?: return null
            val root = window.root ?: return null
            try {
                if (root.refresh() && root.packageName?.toString() == expectedPackage && root.windowId == window.id) {
                    return root
                }
            } catch (failure: Throwable) {
                @Suppress("DEPRECATION") root.recycle()
                throw failure
            }
            @Suppress("DEPRECATION") root.recycle()
            return null
        } finally {
            windows.forEach { @Suppress("DEPRECATION") it.recycle() }
        }
    }

    /** Synthetic fixture metadata only: never record node text, screenshots or real account data. */
    private fun recordWindowDiagnostics(automation: UiAutomation, stage: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val info = automation.serviceInfo
        Log.i("HansLiveCallWindowProbe", "$stage service flags=${info.flags}" +
            " eventTypes=${info.eventTypes} capabilities=${info.capabilities}" +
            " packageNames=${info.packageNames?.joinToString()}")
        instrumentation.runOnMainSync {
            Log.i("HansLiveCallWindowProbe", "$stage target focus=${compose.activity.hasWindowFocus()}" +
                " lifecycle=${compose.activity.lifecycle.currentState}")
        }
        automation.rootInActiveWindow?.let { root ->
            try {
                Log.i("HansLiveCallWindowProbe", "$stage activeRoot package=${root.packageName}" +
                    " windowId=${root.windowId}")
            } finally { @Suppress("DEPRECATION") root.recycle() }
        } ?: Log.i("HansLiveCallWindowProbe", "$stage activeRoot=null")
        val windows = automation.windows
        try {
            Log.i("HansLiveCallWindowProbe", "$stage windowCount=${windows.size}")
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.forEach { window ->
                val root = window.root
                try {
                    Log.i("HansLiveCallWindowProbe", "$stage applicationWindow id=${window.id}" +
                        " active=${window.isActive} focused=${window.isFocused}" +
                        " accessibilityFocused=${window.isAccessibilityFocused}" +
                        " rootPackage=${root?.packageName} rootWindowId=${root?.windowId}")
                } finally { @Suppress("DEPRECATION") root?.recycle() }
            }
        } finally { windows.forEach { @Suppress("DEPRECATION") it.recycle() } }
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
