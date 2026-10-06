package ai.hans.standard.ui

import ai.hans.standard.phone.display.DisplayMotionMode
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import java.util.Locale
import org.junit.Rule
import org.junit.Test

/** Changes this test's Android resource configuration only, never the device's global locale. */
class UiLanguageFollowSystemTest {
    @get:Rule val compose = createComposeRule()

    @Test fun localeChangesUpdateComposerCopyWithoutChangingUserTextOrStopState() {
        val locale = mutableStateOf(Locale.ENGLISH)
        val draft = mutableStateOf("Unveränderter persönlicher Entwurf")
        compose.setContent {
            val base = LocalContext.current
            val configuration = LocalConfiguration.current
            val context = remember(base, configuration, locale.value) {
                base.createConfigurationContext(Configuration(configuration).apply {
                    setLocales(LocaleList(locale.value))
                })
            }
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
            ) {
                MaterialTheme {
                    ChatScreen(
                        state = ChatUiState(
                            composer = ComposerUiState(text = draft.value),
                            isWorking = true,
                            workInterrupt = WorkInterruptUiState(visible = true, enabled = true),
                        ),
                        callbacks = ChatUiCallbacks(
                            onComposerChanged = { draft.value = it }, onSend = {}, onChooseMedia = {},
                            onRemoveAttachment = {}, onOpenApps = {}, onOpenPlugins = {},
                            onOpenSettings = {}, onToggleLiveVoice = {},
                        ),
                        displayMotionMode = DisplayMotionMode.E_INK,
                    )
                }
            }
        }
        compose.onNodeWithTag("interrupt_codex_work").assertContentDescriptionEquals("Stop Codex task")
        compose.onNodeWithText("Hans is working").assertExists()
        compose.runOnIdle { locale.value = Locale.forLanguageTag("de-AT") }
        compose.onNodeWithTag("interrupt_codex_work").assertContentDescriptionEquals("Codex-Aufgabe stoppen")
        compose.onNodeWithText("Hans arbeitet").assertExists()
        compose.onNodeWithTag("composer").assertTextEquals("Unveränderter persönlicher Entwurf")
        compose.runOnIdle { locale.value = Locale.FRENCH }
        compose.onNodeWithTag("interrupt_codex_work").assertContentDescriptionEquals("Stop Codex task")
        compose.onNodeWithTag("composer").assertTextEquals("Unveränderter persönlicher Entwurf")
    }
}
