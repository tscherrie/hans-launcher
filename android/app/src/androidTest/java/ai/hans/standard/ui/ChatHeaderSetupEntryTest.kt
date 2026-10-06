package ai.hans.standard.ui

import android.view.KeyEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatHeaderSetupEntryTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun readyHeaderIsSilentAndTappingHansRequiresPhoneCallConfirmation() {
        val liveStatus = mutableStateOf<LiveVoiceUiStatus?>(null)
        var liveStarts = 0
        compose.setGermanContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatUiState(
                        runtimeStatus = RuntimeUiStatus.ONLINE,
                        liveVoiceStatus = liveStatus.value,
                    ),
                    callbacks = callbacks(
                        onStartLiveVoice = {
                            liveStarts += 1
                            liveStatus.value = LiveVoiceUiStatus.CONNECTING
                        },
                    ),
                )
            }
        }

        compose.onNodeWithTag("runtime_status").assertDoesNotExist()
        compose.onNodeWithTag("toggle_live_voice").assertDoesNotExist()
        compose.onNodeWithTag(
            "runtime_sleeping_indicator",
            useUnmergedTree = true,
        ).assertDoesNotExist()
        listOf("open_settings", "open_apps", "open_plugins", "open_automations", "start_setup").forEach { tag ->
            compose.onNodeWithTag(tag).assertDoesNotExist()
        }
        compose.onNodeWithText("Bereit").assertDoesNotExist()
        compose.onNodeWithText("Live").assertDoesNotExist()
        compose.onNodeWithText("Live aus").assertDoesNotExist()
        listOf("Einstellungen", "Apps", "Plugins", "Setup").forEach { label ->
            compose.onNodeWithText(label).assertDoesNotExist()
        }
        compose.onNodeWithTag("hans_title", useUnmergedTree = true)
            .assertContentDescriptionEquals("Hans. Anrufdialog öffnen")
            .performClick()

        compose.onNodeWithTag("live_start_confirmation").assertExists()
        compose.onNodeWithTag("live_call_screen").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, liveStarts) }
        compose.onNodeWithTag("live_start_confirm").performClick()
        compose.onNodeWithTag("live_start_confirmation").assertDoesNotExist()
        compose.onNodeWithTag("live_call_screen").assertExists()
        compose.onNodeWithTag("live_call_status").assertTextEquals("Wird angerufen …")
        compose.runOnIdle { assertEquals(1, liveStarts) }
    }

    @Test
    fun startingLiveReplacesTheSleepingHeaderWithThePhoneSurface() {
        val runtimeStatus = mutableStateOf(RuntimeUiStatus.OFFLINE)
        val liveStatus = mutableStateOf<LiveVoiceUiStatus?>(null)
        compose.setGermanContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatUiState(
                        runtimeStatus = runtimeStatus.value,
                        liveVoiceStatus = liveStatus.value,
                    ),
                    callbacks = callbacks(),
                )
            }
        }

        compose.onNodeWithTag("runtime_sleeping_indicator", useUnmergedTree = true)
            .assertContentDescriptionEquals("Hans ist nicht bereit")
            .assertExists()
        compose.onNodeWithTag(
            "live_recording_indicator",
            useUnmergedTree = true,
        ).assertDoesNotExist()

        liveStatus.value = LiveVoiceUiStatus.LISTENING
        compose.waitForIdle()

        compose.onNodeWithTag(
            "runtime_sleeping_indicator",
            useUnmergedTree = true,
        ).assertDoesNotExist()
        compose.onNodeWithTag(
            "live_recording_indicator",
            useUnmergedTree = true,
        ).assertDoesNotExist()
        compose.onNodeWithTag("hans_title", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("live_call_screen").assertExists()
        compose.onNodeWithTag("live_call_status").assertTextEquals("Verbunden")
    }

    @Test
    fun onlyDeliberateRightEdgeHorizontalSwipeOpensNavigationPanel() {
        var appsOpens = 0
        var pluginOpens = 0
        var automationOpens = 0
        var settingsOpens = 0
        compose.setGermanContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatUiState(runtimeStatus = RuntimeUiStatus.ONLINE),
                    callbacks = callbacks(
                        onOpenApps = { appsOpens += 1 },
                        onOpenPlugins = { pluginOpens += 1 },
                        onOpenAutomations = { automationOpens += 1 },
                        onOpenSettings = { settingsOpens += 1 },
                    ),
                )
            }
        }

        compose.onNodeWithTag("open_apps").assertDoesNotExist()
        swipeFromCenterTowardsLeft()
        compose.onNodeWithTag("chat_navigation_panel").assertDoesNotExist()
        swipeVerticallyAtRightEdge()
        compose.onNodeWithTag("chat_navigation_panel").assertDoesNotExist()

        openNavigationPanel()
        compose.onNodeWithTag("open_apps").performClick()
        compose.runOnIdle { assertEquals(1, appsOpens) }
        compose.onNodeWithTag("chat_navigation_panel").assertDoesNotExist()

        openNavigationPanel()
        compose.onNodeWithTag("open_plugins").performClick()
        compose.runOnIdle { assertEquals(1, pluginOpens) }

        openNavigationPanel()
        compose.onNodeWithTag("open_automations").performClick()
        compose.runOnIdle { assertEquals(1, automationOpens) }

        openNavigationPanel()
        compose.onNodeWithTag("open_settings").assertDoesNotExist()
        compose.runOnIdle { assertEquals(4, settingsOpens) }
        compose.onNodeWithTag("close_chat_navigation").performClick()

        openNavigationPanel()
        compose.onNodeWithTag("start_setup").assertDoesNotExist()
        compose.onNodeWithText("Setup").assertDoesNotExist()
    }

    @Test
    fun talkBackCustomActionStillOpensNavigationPanelAlongsideTheVisibleMenuButton() {
        compose.setGermanContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatUiState(runtimeStatus = RuntimeUiStatus.ONLINE),
                    callbacks = callbacks(),
                )
            }
        }

        compose.onNodeWithText("Menü öffnen").assertDoesNotExist()
        compose.onNodeWithTag("open_chat_navigation").assertExists()
        val actions = compose.onNodeWithTag("chat_screen")
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        val openMenu = actions.single { it.label == "Menü öffnen" }
        compose.runOnIdle { assertEquals(true, openMenu.action()) }
        compose.onNodeWithTag("chat_navigation_panel").assertExists()
    }

    @Test
    fun navigationPanelSupportsCloseButtonScrimBackAndRightSwipe() {
        compose.setGermanContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatUiState(runtimeStatus = RuntimeUiStatus.ONLINE),
                    callbacks = callbacks(),
                )
            }
        }

        openNavigationPanel()
        compose.onNodeWithTag("close_chat_navigation").performClick()
        compose.onNodeWithTag("chat_navigation_panel").assertDoesNotExist()

        openNavigationPanel()
        compose.onNodeWithTag("chat_navigation_scrim").performClick()
        compose.onNodeWithTag("chat_navigation_panel").assertDoesNotExist()

        openNavigationPanel()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithTag("chat_navigation_panel").assertDoesNotExist()

        openNavigationPanel()
        compose.onNodeWithTag("chat_navigation_panel").performTouchInput {
            swipe(
                start = Offset(width * 0.25f, centerY),
                end = Offset(width * 0.85f, centerY),
                durationMillis = 350,
            )
        }
        compose.onNodeWithTag("chat_navigation_panel").assertDoesNotExist()
        compose.onNodeWithTag("choose_media").assertExists()
        compose.onNodeWithTag("choose_existing_media").assertDoesNotExist()
    }

    @Test
    fun settingsDetailUsesTheFullWidthAndBackRestoresTheCompactMenu() {
        compose.setGermanContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatUiState(),
                    callbacks = callbacks(),
                    sidebarSettings = SettingsUiState(),
                    sidebarCallbacks = SettingsUiCallbacks(
                        onBack = {}, onStartGettingToKnow = {}, onModelSelected = {},
                        onReasoningEffortSelected = {}, onVoiceSelected = {},
                        onSpeechRateSelected = {}, onReadAloudModeSelected = {}, onPreviewVoice = {},
                        onStartActionKeySetup = {}, onStartModelToggleKeySetup = {},
                        onCancelActionKeySetup = {}, onClearActionKey = {}, onClearModelToggleKey = {},
                        onCapabilityAccessRequested = {},
                    ),
                )
            }
        }
        compose.onNodeWithTag("open_chat_navigation").performClick()
        val fullWidth = compose.onNodeWithTag("chat_navigation_overlay").fetchSemanticsNode().boundsInRoot.width
        val drawerWidth = compose.onNodeWithTag("chat_navigation_panel").fetchSemanticsNode().boundsInRoot.width
        assertTrue(drawerWidth < fullWidth)
        compose.onNodeWithTag("settings_group_input").performScrollTo().performClick()
        compose.onNodeWithTag("chat_navigation_scrim").assertDoesNotExist()
        assertEquals(fullWidth,
            compose.onNodeWithTag("chat_navigation_panel").fetchSemanticsNode().boundsInRoot.width, 1f)
        compose.onNodeWithTag("settings_back_to_groups").performClick()
        compose.onNodeWithTag("chat_navigation_scrim").assertExists()
        assertEquals(drawerWidth,
            compose.onNodeWithTag("chat_navigation_panel").fetchSemanticsNode().boundsInRoot.width, 1f)
        compose.onNodeWithTag("close_chat_navigation").performClick()
        compose.onNodeWithTag("chat_navigation_panel").assertDoesNotExist()
    }

    private fun openNavigationPanel() {
        compose.onNodeWithTag("chat_screen").performTouchInput {
            swipe(
                start = Offset(width - 2f, centerY),
                end = Offset(width * 0.45f, centerY),
                durationMillis = 350,
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_navigation_panel").assertExists()
    }

    private fun swipeFromCenterTowardsLeft() {
        compose.onNodeWithTag("chat_screen").performTouchInput {
            swipe(
                start = Offset(width * 0.65f, centerY),
                end = Offset(width * 0.1f, centerY),
                durationMillis = 350,
            )
        }
        compose.waitForIdle()
    }

    private fun swipeVerticallyAtRightEdge() {
        compose.onNodeWithTag("chat_screen").performTouchInput {
            swipe(
                start = Offset(width - 2f, height * 0.25f),
                end = Offset(width - 8f, height * 0.8f),
                durationMillis = 350,
            )
        }
        compose.waitForIdle()
    }

    private fun callbacks(
        onOpenApps: () -> Unit = {},
        onOpenPlugins: () -> Unit = {},
        onOpenAutomations: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
        onToggleLiveVoice: () -> Unit = {},
        onStartLiveVoice: (() -> Unit)? = null,
    ) = ChatUiCallbacks(
        onComposerChanged = {},
        onSend = {},
        onChooseMedia = {},
        onRemoveAttachment = {},
        onOpenApps = onOpenApps,
        onOpenPlugins = onOpenPlugins,
        onOpenAutomations = onOpenAutomations,
        onOpenSettings = onOpenSettings,
        onToggleLiveVoice = onToggleLiveVoice,
        onStartLiveVoice = onStartLiveVoice,
    )
}
