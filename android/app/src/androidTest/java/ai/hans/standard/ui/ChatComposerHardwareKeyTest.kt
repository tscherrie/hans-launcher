package ai.hans.standard.ui

import ai.hans.standard.phone.display.DisplayMotionMode
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class ChatComposerHardwareKeyTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun idleComposerPreservesVirtualKeyboardBurstBeforeTheNextFrame() {
        assertKeyboardBurstPreserved(initiallyFocused = false, text = "test")
    }

    @Test
    fun focusedComposerPreservesMixedAsciiBurstAndDoesNotSendWithoutEnter() {
        assertKeyboardBurstPreserved(
            initiallyFocused = true,
            text = "Kontrollierter Techniktest: Antworte ohne Werkzeuge ausschliesslich SOL-TEST-OK.",
        )
    }

    @Test
    fun idleComposerPreservesInitialShiftedLetterAndMixedAsciiBurst() {
        assertKeyboardBurstPreserved(
            initiallyFocused = false,
            text = "Android: App-Steuerung SOL-TEST-OK.",
        )
    }

    @Test
    fun idleBurstAndEnterInOneMainThreadTurnSendsWholeDraftAndClearsWithoutResurrection() {
        val fixture = editingFixture()
        compose.onNodeWithTag("chat_screen").requestFocus()
        compose.waitForIdle()
        val events = checkNotNull(
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents("test\nnew".toCharArray()),
        )
        assertEquals(1, events.count { it.action == KeyEvent.ACTION_DOWN && it.keyCode == KeyEvent.KEYCODE_ENTER })

        compose.runOnUiThread {
            events.forEach { fixture.inputView.rootView.dispatchKeyEvent(it) }
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(listOf("test"), fixture.sent)
            assertEquals("new", fixture.draft.value)
        }
        compose.onNodeWithTag("composer").assertIsFocused().assertTextEquals("new")
    }

    @Test
    fun rejectedSendPreservesDraftAndFollowingSameFrameTyping() {
        val fixture = editingFixture(sendBehavior = SendBehavior.REJECTED)
        compose.onNodeWithTag("chat_screen").requestFocus()
        compose.waitForIdle()
        val events = checkNotNull(
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents("test\nnew".toCharArray()),
        )
        compose.runOnUiThread {
            events.forEach { fixture.inputView.rootView.dispatchKeyEvent(it) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("composer").assertIsFocused().assertTextEquals("testnew")
        compose.runOnIdle {
            assertEquals(listOf("test"), fixture.sent)
            assertEquals("testnew", fixture.draft.value)
            assertEquals(false, fixture.dispatchPending.value)
        }
    }

    @Test
    fun pendingSendKeepsDraftAndBlocksTypingUntilAuthoritativeSentReceipt() {
        val fixture = editingFixture(sendBehavior = SendBehavior.PENDING)
        compose.onNodeWithTag("chat_screen").requestFocus()
        compose.waitForIdle()
        val events = checkNotNull(
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents("test\nblocked".toCharArray()),
        )
        compose.runOnUiThread {
            events.forEach { fixture.inputView.rootView.dispatchKeyEvent(it) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("composer").assertIsNotEnabled().assertTextEquals("test")
        compose.runOnIdle {
            assertEquals(listOf("test"), fixture.sent)
            assertEquals("test", fixture.draft.value)
            assertEquals(true, fixture.dispatchPending.value)
        }

        // Simulate only the owner's later SENT receipt; the editor may not invent it earlier.
        compose.runOnUiThread {
            fixture.draft.value = ""
            fixture.dispatchPending.value = false
        }
        compose.waitForIdle()
        compose.onNodeWithTag("composer").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
        val nextEvents = checkNotNull(
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents("new".toCharArray()),
        )
        compose.runOnUiThread {
            nextEvents.forEach { fixture.inputView.rootView.dispatchKeyEvent(it) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("composer").assertIsFocused().assertTextEquals("new")
        compose.runOnIdle {
            assertEquals("new", fixture.draft.value)
            assertEquals(listOf("test"), fixture.sent)
        }
    }

    @Test
    fun imeCompositionSurvivesParentEchoesAndSelectionReplacementThenSendsOnce() {
        // Be the session's only IME writer. Calling View.onCreateInputConnection while the
        // system keyboard also owns a connection lets that keyboard finish our composition.
        // The intercepted request still creates Foundation's real Android InputConnection.
        val inputSession = ComposerInputSession()
        val fixture = editingFixture(inputSession = inputSession)
        compose.onNodeWithTag("composer").requestFocus()
        compose.waitForIdle()
        lateinit var connection: InputConnection
        compose.runOnUiThread {
            val info = checkNotNull(inputSession.editorInfo)
            connection = checkNotNull(inputSession.connection) {
                "A focused Composer must start its real Android input session"
            }
            assertEquals(EditorInfo.IME_ACTION_SEND, info.imeOptions and EditorInfo.IME_MASK_ACTION)
            assertTrue(connection.setComposingText("Ha", 1))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("composer").assertTextEquals("Ha")
        compose.runOnIdle {
            assertEquals("Ha", fixture.draft.value)
            assertEquals("A parent echo must not restart the IME session", 1, inputSession.starts)
            assertSame(connection, inputSession.connection)
        }

        // If a parent acknowledgement resets Android's composing range, this appends instead
        // of replacing. Use real InputConnection calls, not the onComposerChanged callback.
        compose.runOnUiThread { assertTrue(connection.setComposingText("Hallo", 1)) }
        compose.waitForIdle()
        compose.onNodeWithTag("composer").assertTextEquals("Hallo")
        compose.runOnIdle {
            assertEquals(1, inputSession.starts)
            assertSame(connection, inputSession.connection)
        }
        compose.runOnUiThread { assertTrue(connection.commitText("Hans", 1)) }
        compose.waitForIdle()
        compose.onNodeWithTag("composer").assertTextEquals("Hans")

        compose.runOnUiThread {
            assertTrue(connection.setSelection(2, 4))
            assertTrue(connection.commitText("llo", 1))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("composer").assertTextEquals("Hallo")
        compose.runOnUiThread {
            val down = virtualKey(KeyEvent.KEYCODE_ENTER, KeyEvent.META_SHIFT_ON)
            fixture.inputView.rootView.dispatchKeyEvent(down)
            fixture.inputView.rootView.dispatchKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("composer").assertTextEquals("Hallo\n")
        compose.runOnIdle { assertTrue("Shift+Enter only inserts a newline", fixture.sent.isEmpty()) }

        compose.runOnUiThread { assertTrue(connection.performEditorAction(EditorInfo.IME_ACTION_SEND)) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf("Hallo\n"), fixture.sent)
            assertEquals("", fixture.draft.value)
        }
        compose.onNodeWithTag("composer").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
        compose.onNodeWithTag("chat_screen").assertIsFocused()
    }

    @Test
    fun secondInputConnectionCanFinishTheSharedCompositionWithoutChangingTheParentDraft() {
        val inputSession = ComposerInputSession()
        val fixture = editingFixture(inputSession = inputSession)
        compose.onNodeWithTag("composer").requestFocus()
        compose.waitForIdle()
        lateinit var primary: InputConnection
        lateinit var secondary: InputConnection
        compose.runOnUiThread {
            primary = checkNotNull(inputSession.connection)
            secondary = inputSession.createPeerConnection()
            assertTrue(primary.setComposingText("Ha", 1))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("composer").assertTextEquals("Ha")
        compose.runOnIdle { assertEquals("Ha", fixture.draft.value) }

        // A second real connection can end the shared composition without any text change
        // or parent draft replacement. This controls the competing-IME mechanism; it does
        // not assert which system component made that call in the original emulator run.
        compose.runOnUiThread { assertTrue(secondary.finishComposingText()) }
        compose.waitForIdle()
        compose.onNodeWithTag("composer").assertTextEquals("Ha")
        compose.runOnIdle { assertEquals("Ha", fixture.draft.value) }

        compose.runOnUiThread { assertTrue(primary.setComposingText("Hallo", 1)) }
        compose.waitForIdle()
        compose.onNodeWithTag("composer").assertTextEquals("HaHallo")
        compose.runOnIdle {
            assertEquals("HaHallo", fixture.draft.value)
            assertEquals(1, inputSession.starts)
            assertSame(primary, inputSession.connection)
            assertTrue(fixture.sent.isEmpty())
        }
    }

    @Test
    fun displayOverridePreservesTheActiveImeConnectionAndComposingRangeInBothDirections() {
        val inputSession = ComposerInputSession()
        val fixture = editingFixture(
            inputSession = inputSession,
            displayMotionMode = DisplayMotionMode.STANDARD,
            animationSource = object : SystemAnimationSource {
                override fun animationsEnabled() = true
                override fun observe(onChanged: () -> Unit) = AutoCloseable { }
            },
        )
        compose.onNodeWithTag("composer").requestFocus()
        compose.waitForIdle()
        lateinit var connection: InputConnection
        compose.runOnUiThread {
            connection = checkNotNull(inputSession.connection)
            assertTrue(connection.setComposingText("Ha", 1))
        }
        compose.waitForIdle()

        fun assertUnchangedSession(text: String, blinkEnabled: Boolean) {
            compose.onNodeWithTag("composer").assertIsFocused()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text)))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString(text)))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(text.length)))
                .assert(SemanticsMatcher.expectValue(ComposerCursorBlinkEnabledKey, blinkEnabled))
            compose.runOnIdle {
                assertEquals(text, fixture.draft.value)
                assertEquals("A display change must not restart the IME session", 1, inputSession.starts)
                assertSame(connection, inputSession.connection)
                assertTrue(fixture.sent.isEmpty())
            }
        }

        assertUnchangedSession("Ha", blinkEnabled = true)
        compose.runOnIdle { fixture.displayMotionMode.value = DisplayMotionMode.E_INK }
        compose.waitForIdle()
        assertUnchangedSession("Ha", blinkEnabled = false)

        // Use the same real connection without refocusing or resetting selection. Losing the
        // composing range on STANDARD -> E_INK would append "HaHallo", not replace "Ha".
        compose.runOnUiThread { assertTrue(connection.setComposingText("Hallo", 1)) }
        compose.waitForIdle()
        assertUnchangedSession("Hallo", blinkEnabled = false)

        compose.runOnIdle { fixture.displayMotionMode.value = DisplayMotionMode.STANDARD }
        compose.waitForIdle()
        assertUnchangedSession("Hallo", blinkEnabled = true)

        // The reverse change must preserve that range too, otherwise this becomes "HalloHans".
        compose.runOnUiThread { assertTrue(connection.setComposingText("Hans", 1)) }
        compose.waitForIdle()
        assertUnchangedSession("Hans", blinkEnabled = true)
        compose.runOnUiThread { assertTrue(connection.finishComposingText()) }
        compose.waitForIdle()
        assertUnchangedSession("Hans", blinkEnabled = true)
    }

    @Test
    fun altSelectionReplacementAndNextNativeKeyShareOneBufferWithoutAnIntermediateFrame() {
        val fixture = editingFixture(initialText = "xy")
        compose.onNodeWithTag("composer").requestFocus()
        compose.onNodeWithTag("composer").performTextInputSelection(TextRange(0, 1))
        compose.waitForIdle()
        val keyMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val keyCode = (KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z).first { code ->
            printableCodePoint(keyMap.get(code, KeyEvent.META_ALT_ON)) != null
        }
        val alt = virtualKey(keyCode, KeyEvent.META_ALT_ON)
        val expected = checkNotNull(printableCodePoint(alt.unicodeChar))
        val nextEvents = checkNotNull(keyMap.getEvents("z".toCharArray()))

        compose.runOnUiThread {
            fixture.inputView.rootView.dispatchKeyEvent(alt)
            fixture.inputView.rootView.dispatchKeyEvent(KeyEvent.changeAction(alt, KeyEvent.ACTION_UP))
            nextEvents.forEach { fixture.inputView.rootView.dispatchKeyEvent(it) }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("composer").assertTextEquals("${expected}zy")
        compose.runOnIdle {
            assertEquals("${expected}zy", fixture.draft.value)
            assertTrue(fixture.sent.isEmpty())
        }
    }

    @Test
    fun firstTwoPhysicalLettersStayInTypedOrder() {
        val draft = mutableStateOf("")
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatUiState(composer = ComposerUiState(text = draft.value)),
                    callbacks = ChatUiCallbacks(
                        onComposerChanged = { draft.value = it },
                        onSend = {},
                        onChooseMedia = {},
                        onRemoveAttachment = {},
                        onOpenApps = {},
                        onOpenPlugins = {},
                        onOpenSettings = {},
                        onToggleLiveVoice = {},
                    ),
                )
            }
        }

        compose.onNodeWithTag("chat_screen").requestFocus()
        compose.onNodeWithTag("chat_screen").performKeyInput { pressKey(Key.A) }
        compose.waitForIdle()
        compose.onNodeWithTag("composer").assertIsFocused()
        compose.onNodeWithTag("composer").assertTextEquals("a")
        compose.onNodeWithTag("composer").performKeyInput { pressKey(Key.B) }
        compose.waitForIdle()

        compose.onNodeWithTag("composer").assertTextEquals("ab")
    }

    @Test
    fun einkComposerUsesStaticCaretAndSendReturnsToNonAnimatingIdleFocus() {
        val draft = mutableStateOf("")
        var sentText: String? = null
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    displayMotionMode = DisplayMotionMode.E_INK,
                    state = ChatUiState(composer = ComposerUiState(text = draft.value)),
                    callbacks = ChatUiCallbacks(
                        onComposerChanged = { draft.value = it },
                        onSend = { sentText = it },
                        onChooseMedia = {},
                        onRemoveAttachment = {},
                        onOpenApps = {},
                        onOpenPlugins = {},
                        onOpenSettings = {},
                        onToggleLiveVoice = {},
                    ),
                )
            }
        }

        compose.onNodeWithTag("chat_screen").requestFocus()
        compose.onNodeWithTag("chat_screen").performKeyInput { pressKey(Key.A) }
        compose.waitForIdle()

        compose.onNodeWithTag("composer")
            .assertIsFocused()
            .assertTextEquals("a")
            .assert(
                SemanticsMatcher.expectValue(
                    ComposerCursorBlinkEnabledKey,
                    false,
                ),
            )

        compose.onNodeWithTag("composer").performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()

        compose.runOnIdle { assertEquals("a", sentText) }
        compose.onNodeWithTag("composer").assertIsNotFocused()
        compose.onNodeWithTag("chat_screen").assertIsFocused()
    }

    @Test
    fun activeToggleDictationLocksTypingWithoutAnExtraBanner() {
        val draft = mutableStateOf("Entwurf bleibt")
        val status = mutableStateOf(DictationUiStatus.LISTENING)
        val preview = mutableStateOf("")
        val sent = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatUiState(
                        // The screen also enforces the lock defensively if an
                        // upstream caller momentarily projects enabled=true.
                        composer = ComposerUiState(text = draft.value, enabled = true),
                        dictationStatus = status.value,
                        dictationPreview = preview.value,
                    ),
                    callbacks = ChatUiCallbacks(
                        onComposerChanged = { draft.value = it },
                        onSend = sent::add,
                        onChooseMedia = {},
                        onRemoveAttachment = {},
                        onOpenApps = {},
                        onOpenPlugins = {},
                        onOpenSettings = {},
                        onToggleLiveVoice = {},
                    ),
                )
            }
        }

        listOf(
            DictationUiStatus.PREPARING to "",
            DictationUiStatus.LISTENING to "",
            DictationUiStatus.LISTENING to "Erkannter Sprachtext",
        ).forEach { (phase, partial) ->
            compose.runOnIdle {
                status.value = phase
                preview.value = partial
            }
            compose.onNodeWithTag("composer").assertIsNotEnabled().assertTextEquals("Entwurf bleibt")
            compose.onNodeWithTag("dictation_composer_lock").assertDoesNotExist()
            compose.onNodeWithText("Aufnahme läuft – Eingabe bleibt bis zum Senden gesperrt").assertDoesNotExist()
            compose.onNodeWithTag("chat_screen").requestFocus()
            compose.onNodeWithTag("chat_screen").performKeyInput {
                pressKey(Key.A)
                pressKey(Key.Enter)
            }
            compose.waitForIdle()
            compose.onNodeWithTag("composer").assertIsNotEnabled().assertTextEquals("Entwurf bleibt")
            compose.runOnIdle {
                assertEquals("Entwurf bleibt", draft.value)
                assertTrue(sent.isEmpty())
            }
        }

        compose.runOnIdle { draft.value = "" }
        compose.onNodeWithTag("composer").assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("Aufnahme läuft").assertExists()
        compose.onNodeWithTag("dictation_composer_lock").assertDoesNotExist()
    }

    @Test
    fun enabledCameraHoldFallbackKeepsShortTapAsOnlyMediaAction() {
        var cameraLaunches = 0
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatUiState(cameraHoldToTalkEnabled = true),
                    callbacks = ChatUiCallbacks(
                        onComposerChanged = {},
                        onSend = {},
                        onChooseMedia = { cameraLaunches += 1 },
                        onRemoveAttachment = {},
                        onOpenApps = {},
                        onOpenPlugins = {},
                        onOpenSettings = {},
                        onToggleLiveVoice = {},
                    ),
                )
            }
        }

        compose.onNodeWithTag("choose_media")
            .assertContentDescriptionEquals(
                "Foto oder Video aufnehmen. Gedrückt halten für Diktat, loslassen zum Senden.",
            )
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(1, cameraLaunches) }
        compose.onNodeWithTag("choose_existing_media").assertDoesNotExist()
    }

    @Test
    fun cameraButtonWithoutDictationFallbackAdvertisesThePhotoVideoChoice() {
        var cameraChoices = 0
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatUiState(cameraHoldToTalkEnabled = false),
                    callbacks = ChatUiCallbacks(
                        onComposerChanged = {},
                        onSend = {},
                        onChooseMedia = { cameraChoices += 1 },
                        onRemoveAttachment = {},
                        onOpenApps = {},
                        onOpenPlugins = {},
                        onOpenSettings = {},
                        onToggleLiveVoice = {},
                    ),
                )
            }
        }

        compose.onNodeWithTag("choose_media")
            .assertContentDescriptionEquals("Foto oder Video aufnehmen")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(1, cameraChoices) }
        compose.onNodeWithTag("choose_existing_media").assertDoesNotExist()
    }

    private fun assertKeyboardBurstPreserved(initiallyFocused: Boolean, text: String) {
        val draft = mutableStateOf("")
        val sent = mutableListOf<String>()
        lateinit var inputView: View
        compose.setContent {
            inputView = LocalView.current
            MaterialTheme {
                ChatScreen(
                    state = ChatUiState(composer = ComposerUiState(text = draft.value)),
                    callbacks = ChatUiCallbacks(
                        onComposerChanged = { draft.value = it },
                        onSend = { sent += it },
                        onChooseMedia = {},
                        onRemoveAttachment = {},
                        onOpenApps = {},
                        onOpenPlugins = {},
                        onOpenSettings = {},
                        onToggleLiveVoice = {},
                    ),
                )
            }
        }
        compose.onNodeWithTag(if (initiallyFocused) "composer" else "chat_screen").requestFocus()
        compose.waitForIdle()
        val events = checkNotNull(
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray()),
        ) { "The platform virtual keyboard could not encode the ASCII fixture" }
        assertTrue(events.none { it.keyCode == KeyEvent.KEYCODE_ENTER })

        // The same public virtual-keyboard events used by Android's `input text`, delivered
        // through the real View/Compose key path in one main-thread turn. Inserting waits or
        // a recomposition between letters would hide the reported rapid-input regression.
        compose.runOnUiThread {
            events.forEach { inputView.rootView.dispatchKeyEvent(it) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("composer").assertIsFocused().assertTextEquals(text)
        compose.runOnIdle {
            assertEquals(text, draft.value)
            assertTrue("Typing a printable burst must not submit a message", sent.isEmpty())
        }

        compose.onNodeWithTag("composer").performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(text), sent) }
        compose.onNodeWithTag("chat_screen").assertIsFocused()
    }

    private fun editingFixture(
        initialText: String = "",
        sendBehavior: SendBehavior = SendBehavior.COMMITTED,
        inputSession: ComposerInputSession? = null,
        displayMotionMode: DisplayMotionMode = DisplayMotionMode.AUTOMATIC,
        animationSource: SystemAnimationSource? = null,
    ): EditingFixture {
        val fixture = EditingFixture(initialText, displayMotionMode)
        compose.setContent {
            fixture.inputView = LocalView.current
            val content: @Composable () -> Unit = {
                MaterialTheme {
                    ChatScreen(
                        displayMotionMode = fixture.displayMotionMode.value,
                        state = ChatUiState(
                            composer = ComposerUiState(
                                text = fixture.draft.value,
                                enabled = !fixture.dispatchPending.value,
                            ),
                        ),
                        callbacks = ChatUiCallbacks(
                            onComposerChanged = {
                                if (!fixture.dispatchPending.value) fixture.draft.value = it
                            },
                            onSend = {
                                fixture.sent += it
                                when (sendBehavior) {
                                    SendBehavior.COMMITTED -> fixture.draft.value = ""
                                    SendBehavior.PENDING -> fixture.dispatchPending.value = true
                                    SendBehavior.REJECTED -> Unit
                                }
                            },
                            readComposerDraft = {
                                ComposerDraftSnapshot(
                                    text = fixture.draft.value,
                                    dispatchPending = fixture.dispatchPending.value,
                                )
                            },
                            onChooseMedia = {},
                            onRemoveAttachment = {},
                            onOpenApps = {},
                            onOpenPlugins = {},
                            onOpenSettings = {},
                            onToggleLiveVoice = {},
                        ),
                    )
                }
            }
            CompositionLocalProvider(
                LocalSystemAnimationSource provides (animationSource ?: LocalSystemAnimationSource.current),
            ) {
                if (inputSession == null) {
                    content()
                } else {
                    // The fixture-owned interceptor instance stays stable across parent echoes.
                    InterceptPlatformTextInput(inputSession, content)
                }
            }
        }
        return fixture
    }

    private class ComposerInputSession : PlatformTextInputInterceptor {
        var connection: InputConnection? = null
            private set
        var editorInfo: EditorInfo? = null
            private set
        var starts: Int = 0
            private set
        private var request: PlatformTextInputMethodRequest? = null
        private val connections = mutableListOf<InputConnection>()

        override suspend fun interceptStartInputMethod(
            request: PlatformTextInputMethodRequest,
            nextHandler: PlatformTextInputSession,
        ): Nothing {
            val info = EditorInfo()
            val activeConnection = request.createInputConnection(info)
            this.request = request
            editorInfo = info
            connection = activeConnection
            connections += activeConnection
            starts += 1
            try {
                // Do not forward to the system IME: only this test drives the real connection.
                // This affects this test subtree, never the emulator's global keyboard setting.
                awaitCancellation()
            } finally {
                connections.forEach { it.closeConnection() }
                connections.clear()
                connection = null
                editorInfo = null
                this.request = null
            }
        }

        fun createPeerConnection(): InputConnection =
            checkNotNull(request).createInputConnection(EditorInfo()).also { connections += it }
    }

    private class EditingFixture(initialText: String, displayMotionMode: DisplayMotionMode) {
        val draft = mutableStateOf(initialText)
        val displayMotionMode = mutableStateOf(displayMotionMode)
        val dispatchPending = mutableStateOf(false)
        val sent = mutableListOf<String>()
        lateinit var inputView: View
    }

    private enum class SendBehavior { COMMITTED, PENDING, REJECTED }

    private fun virtualKey(keyCode: Int, metaState: Int): KeyEvent {
        val now = SystemClock.uptimeMillis()
        return KeyEvent(
            now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
        )
    }
}
