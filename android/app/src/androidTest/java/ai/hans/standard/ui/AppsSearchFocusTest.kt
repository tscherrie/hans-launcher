package ai.hans.standard.ui

import ai.hans.standard.phone.display.DisplayMotionMode
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppsSearchFocusTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun menuEntryFocusesSearchAndAcceptsHardwareTypingWithoutTappingTheField() {
        val fixture = fixture()

        openAppsThroughMenu()
        compose.onNodeWithTag("app_search")
            .assertIsFocused()
            .assert(SemanticsMatcher.expectValue(AppsSearchCursorBlinkEnabledKey, true))

        typeHardwareText(fixture, "maps")

        compose.onNodeWithTag("app_search").assertIsFocused().assertTextEquals("maps")
        compose.onNodeWithText("Maps").assertExists()
        compose.onNodeWithText("Notes").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("maps", fixture.state.value.apps.query)
            assertTrue("Searching must not send a chat or launch an app", fixture.actions.isEmpty())
        }
    }

    @Test
    fun reentryClearsSearchFocusesItAgainAndPreservesHomeDraftAndStaticCaret() {
        val fixture = fixture(draft = "Entwurf bleibt erhalten")
        openAppsThroughMenu()
        typeHardwareText(fixture, "maps")
        compose.onNodeWithTag("app_search").assertTextEquals("maps")

        compose.onNodeWithTag("navigate_back").performClick()
        assertUnchangedIdleHome("Entwurf bleibt erhalten")
        compose.runOnIdle { assertEquals("maps", fixture.state.value.apps.query) }

        openAppsThroughMenu()
        compose.onNodeWithTag("app_search")
            .assertIsFocused()
            // The merged Text also contains the visible "App suchen" placeholder.
            // Assert both actual editor values, not placeholder + editable text.
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString("")))
            .assert(SemanticsMatcher.expectValue(AppsSearchCursorBlinkEnabledKey, true))
        compose.onNodeWithText("App suchen").assertExists()
        compose.onNodeWithText("Maps").assertExists()
        compose.onNodeWithText("Notes").assertExists()
        compose.runOnIdle { assertEquals("", fixture.state.value.apps.query) }

        // This is the same authoritative destination change used by the owner's Home
        // handler, not a device-wide Home key or a synthetic focus request on the chat.
        compose.runOnIdle { fixture.navigate(HansDestination.CHAT) }
        assertUnchangedIdleHome("Entwurf bleibt erhalten")
        compose.runOnIdle {
            assertEquals("", fixture.state.value.apps.query)
            assertTrue(fixture.actions.isEmpty())
        }
    }

    @Test
    fun loadingAndQueryUpdatesDoNotStealFocusAfterTheUserMovesToAnotherControl() {
        val fixture = fixture(loading = true)
        openAppsThroughMenu()
        compose.onNodeWithTag("app_search").assertIsFocused()

        // Unlike an editor, a Material button intentionally cannot take keyboard focus
        // in Touch mode. Establish the real public keyboard mode before moving focus;
        // otherwise a rejected focus request cannot test whether a later update steals it.
        compose.runOnIdle {
            assertTrue(
                "The fixture must enter keyboard mode before focusing another control",
                fixture.inputModeManager.requestInputMode(InputMode.Keyboard),
            )
        }
        compose.runOnIdle {
            assertEquals(InputMode.Keyboard, fixture.inputModeManager.inputMode)
        }
        compose.onNodeWithTag("navigate_back").requestFocus()
        compose.onNodeWithTag("navigate_back").assertIsFocused()
        compose.runOnIdle {
            fixture.state.value = fixture.state.value.let {
                it.copy(apps = it.apps.copy(loading = false, query = "maps"))
            }
        }
        compose.onNodeWithTag("navigate_back").assertIsFocused()
        compose.onNodeWithTag("app_search").assertIsNotFocused().assertTextEquals("maps")

        compose.runOnIdle {
            fixture.state.value = fixture.state.value.let {
                it.copy(apps = it.apps.copy(loading = true, query = "notes"))
            }
        }
        compose.onNodeWithTag("navigate_back").assertIsFocused()
        compose.onNodeWithTag("app_search").assertIsNotFocused().assertTextEquals("notes")
        compose.runOnIdle { assertTrue(fixture.actions.isEmpty()) }
    }

    private fun openAppsThroughMenu() {
        compose.onNodeWithTag("open_chat_navigation").performClick()
        compose.onNodeWithTag("open_apps").performClick()
        compose.onNodeWithTag("chat_navigation_panel").assertDoesNotExist()
        compose.onNodeWithTag("chat_screen").assertDoesNotExist()
        // No requestFocus/performClick/performTextInput on the search field: those would
        // hide the missing entry focus that prompted this regression.
    }

    private fun typeHardwareText(fixture: Fixture, text: String) {
        val events = checkNotNull(
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray()),
        ) { "Android's virtual keyboard must encode the ASCII fixture" }
        assertTrue(events.none { it.keyCode == KeyEvent.KEYCODE_ENTER })
        // Exercise the same real View -> Compose key path as the Composer regressions.
        // All letters arrive in one main-thread turn; no per-character delay or tap.
        compose.runOnUiThread {
            events.forEach { fixture.inputView.rootView.dispatchKeyEvent(it) }
        }
        compose.waitForIdle()
    }

    private fun assertUnchangedIdleHome(draft: String) {
        compose.onNodeWithTag("app_search").assertDoesNotExist()
        compose.onNodeWithTag("chat_screen").assertIsFocused()
        compose.onNodeWithTag("composer")
            .assertIsNotFocused()
            .assertTextEquals(draft)
            .assert(SemanticsMatcher.expectValue(ComposerCursorBlinkEnabledKey, false))
    }

    private fun fixture(draft: String = "", loading: Boolean = false): Fixture {
        val fixture = Fixture(draft, loading)
        val callbacks = HansUiCallbacks(
            authGate = AuthGateUiCallbacks({}, {}, {}, {}, {}),
            chat = ChatUiCallbacks(
                onComposerChanged = { text ->
                    fixture.state.value = fixture.state.value.let {
                        it.copy(chat = it.chat.copy(composer = it.chat.composer.copy(text = text)))
                    }
                },
                onSend = { fixture.actions += "send" },
                readComposerDraft = {
                    ComposerDraftSnapshot(fixture.state.value.chat.composer.text, false)
                },
                onChooseMedia = {},
                onRemoveAttachment = {},
                onOpenApps = { fixture.navigate(HansDestination.APPS) },
                onOpenPlugins = { fixture.navigate(HansDestination.PLUGINS) },
                onOpenSettings = { fixture.navigate(HansDestination.SETTINGS) },
                onToggleLiveVoice = {},
            ),
            apps = AppsUiCallbacks(
                onBack = { fixture.navigate(HansDestination.CHAT) },
                onQueryChanged = { query ->
                    fixture.state.value = fixture.state.value.let {
                        it.copy(apps = it.apps.copy(query = query))
                    }
                },
                onRefresh = {},
                onLaunch = { _, _ -> fixture.actions += "launch" },
            ),
            plugins = PluginsUiCallbacks({}, {}, {}, {}),
            settings = SettingsUiCallbacks(
                onBack = { fixture.navigate(HansDestination.CHAT) },
                onStartGettingToKnow = {},
                onModelSelected = {},
                onReasoningEffortSelected = {},
                onVoiceSelected = {},
                onSpeechRateSelected = {},
                onReadAloudModeSelected = {},
                onPreviewVoice = {},
                onStartActionKeySetup = {},
                onStartModelToggleKeySetup = {},
                onCancelActionKeySetup = {},
                onClearActionKey = {},
                onClearModelToggleKey = {},
                onCapabilityAccessRequested = {},
            ),
        )
        compose.setContent {
            fixture.inputView = LocalView.current
            fixture.inputModeManager = LocalInputModeManager.current
            MaterialTheme { HansApp(fixture.state.value, callbacks) }
        }
        return fixture
    }

    private class Fixture(draft: String, loading: Boolean) {
        val state = mutableStateOf(
            HansUiState(
                destination = HansDestination.CHAT,
                settings = SettingsUiState(displayMotionMode = DisplayMotionMode.E_INK),
                chat = ChatUiState(composer = ComposerUiState(text = draft)),
                apps = AppsUiState(
                    loading = loading,
                    apps = listOf(
                        AppUiModel("org.example.maps", "org.example.maps/.Main", "Maps"),
                        AppUiModel("org.example.notes", "org.example.notes/.Main", "Notes"),
                    ),
                ),
            ),
        )
        val actions = mutableListOf<String>()
        lateinit var inputView: View
        lateinit var inputModeManager: InputModeManager

        fun navigate(destination: HansDestination) {
            state.value = state.value.copy(destination = destination)
        }
    }
}
