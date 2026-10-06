package ai.hans.standard.ui

import ai.hans.standard.phone.display.DisplayMotionMode
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Offline rendered UI fixtures; never change device locale, sign in, or execute a phone tool. */
class AppLocaleRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun mainSurfacesRenderEnglishAndGermanWithoutTranslatingModelIdentity() {
        val tags = mutableStateOf("en-US")
        val screen = mutableStateOf(Screen.AUTH)
        compose.setContent {
            ConfiguredContent(tags.value) {
                key(screen.value) {
                    when (screen.value) {
                        Screen.AUTH -> AuthGateScreen(
                            AuthGateUiState(stage = AuthGateStage.SIGNED_OUT),
                            AuthGateUiCallbacks({}, {}, {}, {}, {}),
                        )
                        Screen.APPS -> AppsScreen(AppsUiState(), AppsUiCallbacks({}, {}, {}, { _, _ -> }))
                        Screen.MODEL -> SettingsContent(
                            SettingsUiState(
                                models = listOf(ModelUiOption.HANS_MODELS.single { it.id == "gpt-6-astra" }),
                                reasoningEfforts = listOf(ReasoningEffortUiOption.MEDIUM),
                                selectedModelId = "gpt-6-astra",
                                selectedReasoningEffortId = "medium",
                            ),
                            settingsCallbacks(),
                            SettingsGroup.RUNTIME,
                        )
                        Screen.PLUGINS -> PluginsScreen(PluginsUiState(), PluginsUiCallbacks({}, {}, {}, {}))
                        Screen.AUTOMATIONS -> AutomationsScreen(AutomationsUiState(), AutomationsUiCallbacks())
                    }
                }
            }
        }
        for ((language, german) in listOf("en-US" to false, "de-AT" to true)) {
            compose.runOnIdle { tags.value = language; screen.value = Screen.AUTH }
            compose.onNodeWithText(if (german) "Mit ChatGPT anmelden" else "Sign in with ChatGPT")
                .assertIsDisplayed()
            compose.runOnIdle { screen.value = Screen.APPS }
            compose.onNodeWithText(if (german) "App suchen" else "Search apps").assertIsDisplayed()
            compose.onNodeWithText(if (german) "Keine startbaren Apps gefunden." else "No launchable apps found.")
                .assertIsDisplayed()
            compose.runOnIdle { screen.value = Screen.MODEL }
            compose.onNodeWithText(if (german) "Denkaufwand" else "Reasoning effort").assertIsDisplayed()
            compose.onNodeWithTag("model_gpt-6-astra").assertTextEquals("✓  Astra 6").assertIsSelected()
            compose.onNodeWithTag("effort_medium").assertTextEquals(if (german) "✓  Mittel" else "✓  Medium")
                .assertIsSelected()
            compose.runOnIdle { screen.value = Screen.PLUGINS }
            compose.onNodeWithText(if (german) "Noch keine Plugins installiert" else "No plugins installed yet")
                .assertIsDisplayed()
            compose.runOnIdle { screen.value = Screen.AUTOMATIONS }
            compose.onNodeWithTag("automations_empty").assertTextEquals(if (german)
                "Noch keine Automationen eingerichtet. Beschreibe Hans im Chat, was wann geschehen soll."
                else "No automations configured yet. Tell Hans in chat what should happen and when.")
                .assertIsDisplayed()
        }
    }

    @Test
    fun orderedLocaleListsAndUnsupportedLanguageRenderTheExpectedStopAccessibilityCopy() {
        val tags = mutableStateOf("en-US")
        compose.setContent {
            ConfiguredContent(tags.value) {
                ChatScreen(
                    ChatUiState(isWorking = true, workInterrupt = WorkInterruptUiState(visible = true, enabled = true)),
                    chatCallbacks(), displayMotionMode = DisplayMotionMode.E_INK,
                )
            }
        }
        for ((languages, expected) in listOf(
            "en-US,de-DE" to "Stop Codex task",
            "de-AT,en-US" to "Codex-Aufgabe stoppen",
            "fr-FR,de-DE" to "Codex-Aufgabe stoppen",
            "fr-FR,bg-BG" to "Stop Codex task",
        )) {
            compose.runOnIdle { tags.value = languages }
            compose.onNodeWithTag("interrupt_codex_work")
                .assertContentDescriptionEquals(expected).assertIsEnabled().assertIsDisplayed()
        }
    }

    @Test
    fun configurationChangesPreserveDraftAttachmentsPendingStopAndSteerAvailability() {
        val tags = mutableStateOf("en-US")
        val draft = "Mein Entwurf العربية — %s"
        val state = ChatUiState(
            composer = ComposerUiState(
                text = draft,
                attachments = listOf(ComposerAttachmentUiModel("opaque-fixture", "Foto Urlaub.jpg")),
            ),
            isWorking = true,
            workInterrupt = WorkInterruptUiState(visible = true, enabled = false, pending = true, revision = 9),
        )
        var interrupts = 0
        var sends = 0
        var edits = 0
        compose.setContent {
            ConfiguredContent(tags.value) {
                ChatScreen(state, chatCallbacks().copy(
                    onComposerChanged = { edits++ },
                    onSend = { sends++ },
                    onInterruptWork = { interrupts++; true },
                ), displayMotionMode = DisplayMotionMode.E_INK)
            }
        }
        for ((languages, stopLabel) in listOf(
            "en-US" to "Stop requested",
            "de-DE" to "Unterbrechen angefordert",
            "fr-FR" to "Stop requested",
        )) {
            compose.runOnIdle { tags.value = languages }
            compose.onNodeWithTag("composer").assertTextEquals(draft).assertIsEnabled()
            compose.onNodeWithText("Foto Urlaub.jpg").assertIsDisplayed()
            compose.onNodeWithTag("interrupt_codex_work")
                .assertContentDescriptionEquals(stopLabel).assertIsNotEnabled()
        }
        compose.runOnIdle {
            assertEquals(0, interrupts)
            assertEquals(0, sends)
            assertEquals(0, edits)
            assertEquals(9L, state.workInterrupt.revision)
        }
    }

    @Composable
    private fun ConfiguredContent(tags: String, content: @Composable () -> Unit) {
        val base = LocalContext.current
        val configuration = LocalConfiguration.current
        val context = remember(base, configuration, tags) {
            base.createConfigurationContext(Configuration(configuration).apply {
                setLocales(LocaleList.forLanguageTags(tags))
            })
        }
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides context.resources.configuration,
        ) { MaterialTheme(content = content) }
    }

    private fun chatCallbacks() = ChatUiCallbacks(
        onComposerChanged = {}, onSend = {}, onChooseMedia = {}, onRemoveAttachment = {},
        onOpenApps = {}, onOpenPlugins = {}, onOpenSettings = {}, onToggleLiveVoice = {},
    )

    private fun settingsCallbacks() = SettingsUiCallbacks(
        onBack = {}, onStartGettingToKnow = {}, onModelSelected = {}, onReasoningEffortSelected = {},
        onVoiceSelected = {}, onSpeechRateSelected = {}, onReadAloudModeSelected = {}, onPreviewVoice = {},
        onStartActionKeySetup = {}, onStartModelToggleKeySetup = {}, onCancelActionKeySetup = {},
        onClearActionKey = {}, onClearModelToggleKey = {}, onCapabilityAccessRequested = {},
    )

    private enum class Screen { AUTH, APPS, MODEL, PLUGINS, AUTOMATIONS }
}
