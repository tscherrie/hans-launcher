package ai.hans.standard.ui

import ai.hans.standard.voice.stt.SttTranscriptionDelay
import ai.hans.standard.voice.stt.android.SttLatencyPreferenceStore
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Deterministic API 31–36 UI checks: no microphone, network, account or real dictation. */
class DictationLatencyUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun livePreviewUsesTheUserMessageCardWithoutExtraLabelsOrDispatch() {
        val composerEdits = mutableListOf<String>()
        val sent = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatUiState(
                        composer = ComposerUiState(text = "Vorhandener Tippentwurf", enabled = false),
                        dictationStatus = DictationUiStatus.LISTENING,
                        dictationPreview = "Vorläufig erkannter Sprachtext",
                    ),
                    callbacks = chatCallbacks(onEdit = composerEdits::add, onSend = sent::add),
                )
            }
        }

        compose.onNodeWithTag("dictation_preview")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextEquals("Vorläufig erkannter Sprachtext")
            .assert(hasAnyAncestor(hasTestTag("message_dictation-preview")))
        compose.onNodeWithTag("message_dictation-preview").assertIsDisplayed()
        compose.onNodeWithText("Du")
            .assertIsDisplayed()
            .assert(hasAnyAncestor(hasTestTag("message_dictation-preview")))
        compose.onNodeWithText("Vorläufig · noch nicht gesendet").assertDoesNotExist()
        compose.onNodeWithTag("dictation_status_listening").assertDoesNotExist()
        compose.onNodeWithTag("dictation_composer_lock").assertDoesNotExist()
        compose.onNodeWithTag("composer")
            .assertIsNotEnabled()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString("Vorhandener Tippentwurf"),
                ),
            )
        compose.runOnIdle {
            assertTrue(composerEdits.isEmpty())
            assertTrue(sent.isEmpty())
        }
    }

    @Test
    fun newerPartialReplacesTheOldPreviewAndTerminalStateHidesIt() {
        val state = mutableStateOf(
            ChatUiState(
                composer = ComposerUiState(enabled = false),
                dictationStatus = DictationUiStatus.LISTENING,
                dictationPreview = "Der erste Zwischenstand",
            ),
        )
        val sent = mutableListOf<String>()
        compose.setContent {
            MaterialTheme { ChatScreen(state.value, chatCallbacks(onSend = sent::add)) }
        }
        compose.onNodeWithTag("dictation_preview").performScrollTo()
            .assertTextEquals("Der erste Zwischenstand")
        compose.onNodeWithTag("dictation_status_listening").assertDoesNotExist()

        compose.runOnIdle {
            state.value = state.value.copy(
                dictationStatus = DictationUiStatus.FINALIZING,
                dictationPreview = "Die korrigierte Fassung",
            )
        }
        compose.onNodeWithTag("dictation_preview").performScrollTo()
            .assertTextEquals("Die korrigierte Fassung")
            .assert(hasAnyAncestor(hasTestTag("message_dictation-preview")))
        compose.onNodeWithTag("dictation_status_finalizing").assertDoesNotExist()
        compose.onNodeWithTag("dictation_composer_lock").assertDoesNotExist()
        compose.onNodeWithText("Der erste Zwischenstand").assertDoesNotExist()

        compose.runOnIdle {
            state.value = state.value.copy(dictationStatus = DictationUiStatus.WAITING_TO_SEND)
        }
        // Defensive rendering must hide even a stale preview supplied with terminal status.
        compose.onNodeWithTag("dictation_preview").assertDoesNotExist()
        compose.onNodeWithTag("message_dictation-preview").assertDoesNotExist()
        compose.onNodeWithText("Vorläufig · noch nicht gesendet").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(sent.isEmpty())
            state.value = state.value.copy(
                dictationStatus = DictationUiStatus.LISTENING,
                dictationPreview = "",
            )
        }
        compose.onNodeWithTag("dictation_preview").assertDoesNotExist()
    }

    @Test
    fun blankLivePreviewKeepsTheExistingStatusWithoutASeparateLockBanner() {
        val state = mutableStateOf(ChatUiState(composer = ComposerUiState(enabled = false)))
        compose.setContent {
            MaterialTheme { ChatScreen(state.value, chatCallbacks()) }
        }
        listOf(DictationUiStatus.LISTENING, DictationUiStatus.FINALIZING).forEach { phase ->
            listOf("", " \n\t").forEach { preview ->
                compose.runOnIdle {
                    state.value = state.value.copy(dictationStatus = phase, dictationPreview = preview)
                }
                compose.onNodeWithTag("dictation_status_${phase.name.lowercase()}")
                    .performScrollTo()
                    .assertIsDisplayed()
                    .assertTextEquals(phase.label)
                compose.onNodeWithTag("dictation_preview").assertDoesNotExist()
                compose.onNodeWithTag("message_dictation-preview").assertDoesNotExist()
                compose.onNodeWithTag("dictation_composer_lock").assertDoesNotExist()
                compose.onNodeWithText("Vorläufig · noch nicht gesendet").assertDoesNotExist()
                compose.onNodeWithTag("composer").assertIsNotEnabled()
            }
        }
    }

    @Test
    fun livePreviewKeepsMarkdownAndLinkLikeInputLiteral() {
        val transcript = "**Wörtlich** [Link](https://example.invalid) `Code`\n# Keine Überschrift"
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    ChatUiState(
                        dictationStatus = DictationUiStatus.LISTENING,
                        dictationPreview = transcript,
                    ),
                    chatCallbacks(),
                )
            }
        }
        compose.onNodeWithTag("dictation_preview").performScrollTo()
            .assertTextEquals(transcript)
            .assert(hasAnyAncestor(hasTestTag("message_dictation-preview")))
        compose.onNodeWithText("Du").assertIsDisplayed()
        compose.onAllNodesWithText(transcript).assertCountEquals(1)
        compose.onNodeWithText("Vorläufig · noch nicht gesendet").assertDoesNotExist()
    }

    @Test
    fun authoritativeFinalUserMessageReplacesThePreviewWithoutDuplication() {
        val transcript = "Der abgeschlossene Sprachtext"
        val state = mutableStateOf(
            ChatUiState(
                dictationStatus = DictationUiStatus.FINALIZING,
                dictationPreview = transcript,
                composer = ComposerUiState(enabled = false),
            ),
        )
        val sent = mutableListOf<String>()
        compose.setContent {
            MaterialTheme { ChatScreen(state.value, chatCallbacks(onSend = sent::add)) }
        }
        compose.onNodeWithTag("message_dictation-preview").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("dictation_preview").assertTextEquals(transcript)

        compose.runOnIdle {
            state.value = state.value.copy(
                messages = listOf(
                    ChatMessageUiModel(
                        id = "final-transcript",
                        author = ChatMessageAuthor.USER,
                        text = transcript,
                        complete = true,
                    ),
                ),
                dictationStatus = null,
                // Even a stale presentation value must not duplicate the authoritative turn.
                dictationPreview = transcript,
                composer = ComposerUiState(enabled = true),
                timelineRevision = 1,
            )
        }
        compose.onNodeWithTag("message_final-transcript").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("message_dictation-preview").assertDoesNotExist()
        compose.onNodeWithTag("dictation_preview").assertDoesNotExist()
        compose.onAllNodesWithText(transcript).assertCountEquals(1)
        compose.onAllNodesWithText("Du").assertCountEquals(1)
        compose.onNodeWithText("Vorläufig · noch nicht gesendet").assertDoesNotExist()
        compose.onNodeWithTag("dictation_composer_lock").assertDoesNotExist()
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
    }

    @Test
    fun provisionalTextIsAbsentOutsideListeningAndFinalizing() {
        val status = mutableStateOf<DictationUiStatus?>(null)
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    ChatUiState(dictationStatus = status.value, dictationPreview = "Nicht zeigen"),
                    chatCallbacks(),
                )
            }
        }
        listOf(null, DictationUiStatus.PREPARING, DictationUiStatus.WAITING_TO_SEND, DictationUiStatus.FAILED)
            .forEach { phase ->
                compose.runOnIdle { status.value = phase }
                compose.onNodeWithTag("dictation_preview").assertDoesNotExist()
                compose.onNodeWithTag("message_dictation-preview").assertDoesNotExist()
                compose.onNodeWithText("Vorläufig · noch nicht gesendet").assertDoesNotExist()
            }
    }

    @Test
    fun selectingDelayOnlyRequestsChangeWithoutInventingPersistenceOrServerAcceptance() {
        val requests = mutableListOf<SttTranscriptionDelay>()
        compose.setContent {
            MaterialTheme {
                SettingsScreen(SettingsUiState(), settingsCallbacks(requests::add))
            }
        }
        compose.onNodeWithTag("stt_delay_minimal").assertDoesNotExist()
        compose.onNodeWithTag("settings_group_personal").performScrollTo().performClick()
        compose.onNodeWithTag("stt_delay_low").performScrollTo().assertTextEquals("✓ Low · Standard")
        compose.onNodeWithTag("stt_delay_minimal").performScrollTo().performClick()

        compose.runOnIdle { assertEquals(listOf(SttTranscriptionDelay.MINIMAL), requests) }
        compose.onNodeWithTag("stt_delay_minimal").assertTextEquals("Minimal · früherer Text")
        compose.onNodeWithTag("stt_delay_low").performScrollTo().assertTextEquals("✓ Low · Standard")
        compose.onNodeWithTag("stt_delay_confirmed").assertDoesNotExist()
    }

    @Test
    fun nextRecordingPreferenceDoesNotReplaceTheCurrentServerAcknowledgement() {
        val state = mutableStateOf(
            SettingsUiState(
                sttLatency = SttLatencyUiState(
                    preferred = SttTranscriptionDelay.MINIMAL,
                    confirmedActive = SttTranscriptionDelay.LOW,
                ),
            ),
        )
        val requests = mutableListOf<SttTranscriptionDelay>()
        compose.setContent {
            MaterialTheme { SettingsScreen(state.value, settingsCallbacks(requests::add)) }
        }
        compose.onNodeWithTag("settings_group_personal").performScrollTo().performClick()
        compose.onNodeWithTag("stt_delay_minimal").performScrollTo()
            .assertTextEquals("✓ Minimal · früherer Text")
        compose.onNodeWithTag("stt_delay_confirmed").performScrollTo()
            .assertTextEquals("Für die laufende Aufnahme vom Server bestätigt: low")
        compose.onNodeWithTag("stt_delay_low").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(SttTranscriptionDelay.LOW), requests) }
        compose.onNodeWithTag("stt_delay_minimal").performScrollTo()
            .assertTextEquals("✓ Minimal · früherer Text")
        compose.onNodeWithTag("stt_delay_confirmed").performScrollTo()
            .assertTextEquals("Für die laufende Aufnahme vom Server bestätigt: low")

        compose.runOnIdle { state.value = state.value.copy(sttLatency = state.value.sttLatency.copy(confirmedActive = null)) }
        compose.onNodeWithTag("stt_delay_confirmed").assertDoesNotExist()
    }

    @Test
    fun savedDelaySurvivesStoreRecreationInAnIsolatedPreferenceNamespace() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val testName = "dictation-latency-test-${UUID.randomUUID()}"
        val testDirectory = File(base.cacheDir, testName)
        assertTrue(testDirectory.mkdir())
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this

            override fun getNoBackupFilesDir(): File = testDirectory
        }
        val preferenceFile = File(testDirectory, "hans-stt-latency-v1.txt")
        try {
            assertEquals(SttTranscriptionDelay.LOW, SttLatencyPreferenceStore(context).read())
            assertEquals(
                SttTranscriptionDelay.MINIMAL,
                SttLatencyPreferenceStore(context).save(SttTranscriptionDelay.MINIMAL),
            )
            assertEquals(SttTranscriptionDelay.MINIMAL, SttLatencyPreferenceStore(context).read())
            assertEquals("minimal", preferenceFile.readText(Charsets.US_ASCII))

            assertEquals(SttTranscriptionDelay.LOW, SttLatencyPreferenceStore(context).save(SttTranscriptionDelay.LOW))
            assertEquals(SttTranscriptionDelay.LOW, SttLatencyPreferenceStore(context).read())
            preferenceFile.writeText("unsupported", Charsets.US_ASCII)
            assertEquals(SttTranscriptionDelay.LOW, SttLatencyPreferenceStore(context).read())
            assertEquals("unsupported", preferenceFile.readText(Charsets.US_ASCII))
        } finally {
            testDirectory.listFiles().orEmpty().forEach { assertTrue(it.delete()) }
            assertTrue(testDirectory.delete())
        }
    }

    private fun chatCallbacks(onEdit: (String) -> Unit = {}, onSend: (String) -> Unit = {}) = ChatUiCallbacks(
        onComposerChanged = onEdit,
        onSend = onSend,
        onChooseMedia = {},
        onRemoveAttachment = {},
        onOpenApps = {},
        onOpenPlugins = {},
        onOpenSettings = {},
        onToggleLiveVoice = {},
    )

    private fun settingsCallbacks(onDelay: (SttTranscriptionDelay) -> Unit) = SettingsUiCallbacks(
        onBack = {},
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
        onSttLatencyChanged = onDelay,
    )
}
