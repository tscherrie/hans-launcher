package ai.hans.standard.ui

import ai.hans.standard.phone.display.DisplayMotionMode
import android.view.KeyEvent
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
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
import org.junit.Rule
import org.junit.Test

class SettingsGroupNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sidebarModelCatalogRefreshRunsOnlyWhenTheRuntimeGroupIsExplicitlyOpened() {
        var refreshes = 0
        val state = mutableStateOf(SettingsUiState())
        showSidebar(
            settings = { state.value },
            callbacks = settingsCallbacks().copy(onModelSettingsOpened = { refreshes++ }),
        )
        compose.runOnIdle { assertEquals(0, refreshes) }
        openGroup("permissions")
        compose.onNodeWithTag("settings_back_to_groups").performClick()
        compose.runOnIdle { assertEquals(0, refreshes) }

        openGroup("runtime")
        compose.runOnIdle {
            assertEquals(1, refreshes)
            state.value = state.value.copy(runtimeNotice = "Katalog aktualisiert")
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, refreshes) }
        compose.onNodeWithTag("settings_back_to_groups").performClick()
        openGroup("runtime")
        compose.runOnIdle { assertEquals(2, refreshes) }
    }

    @Test
    fun overviewContainsOnlyGroupNamesAndTheExistingAppAndPluginEntries() {
        showSidebar()

        listOf("open_apps", "open_plugins", "open_automations").forEach { tag ->
            compose.onNodeWithTag(tag).assertExists()
        }
        compose.onNodeWithTag("open_workbench").assertDoesNotExist()
        compose.onNodeWithTag("start_setup").assertDoesNotExist()
        compose.onNodeWithText("Setup").assertDoesNotExist()
        groups.forEach { (id, title, _) ->
            compose.onNodeWithTag("settings_group_$id").performScrollTo().assertTextEquals(title)
        }
        compose.onNodeWithTag("open_settings").assertDoesNotExist()
        compose.onNodeWithText("Einstellungen").assertDoesNotExist()
        compose.onNodeWithTag("settings_content").assertDoesNotExist()
        compose.onNodeWithTag("settings_back_to_groups").assertDoesNotExist()
        compose.onNodeWithTag("settings_group_remote_control").assertDoesNotExist()
        compose.onNodeWithTag("settings_group_advanced_work").assertDoesNotExist()
        groups.forEach { (_, _, control) ->
            compose.onNodeWithTag(control).assertDoesNotExist()
        }
        compose.onNodeWithText(
            "Die Markierung ändert sich erst, wenn Codex die Auswahl bestätigt hat.",
        ).assertDoesNotExist()
        compose.onNodeWithText(NotificationFactArchiveStatus.disclosure(ai.hans.standard.localization.AndroidHansTextResolver(germanUiTestContext()))).assertDoesNotExist()
        compose.onNodeWithTag("notification_link_metadata_disclosure").assertDoesNotExist()
    }

    @Test
    fun eachGroupShowsOnlyItsOwnControlsAndHeaderBackReturnsToTheOverview() {
        showSidebar()

        groups.forEach { (id, title, control) ->
            openGroup(id)
            compose.onNodeWithTag("settings_group_title").assertTextEquals(title)
            compose.onNodeWithTag("settings_group_overview").assertDoesNotExist()
            compose.onNodeWithTag(control).performScrollTo().assertExists()
            groups.filterNot { it.first == id }.forEach { (_, _, otherControl) ->
                compose.onNodeWithTag(otherControl).assertDoesNotExist()
            }
            listOf("open_apps", "open_plugins", "open_automations", "start_setup").forEach { tag ->
                compose.onNodeWithTag(tag).assertDoesNotExist()
            }
            if (id == "maintenance") compose.onNodeWithTag("open_workbench").assertExists()
            else compose.onNodeWithTag("open_workbench").assertDoesNotExist()

            compose.onNodeWithTag("settings_back_to_groups").performClick()
            compose.onNodeWithTag("settings_group_overview").assertExists()
            compose.onNodeWithTag("settings_content").assertDoesNotExist()
            compose.onNodeWithTag("chat_navigation_panel").assertExists()
        }
    }

    @Test
    fun setupStartsOnlyFromThePersonalGroupWithAnExplicitActionLabel() {
        var starts = 0
        showSidebar(callbacks = settingsCallbacks().copy(onStartOrResumeSetup = { starts++ }))

        compose.onNodeWithTag("start_setup").assertDoesNotExist()
        openGroup("personal")
        compose.onNodeWithTag("start_or_resume_setup")
            .performScrollTo()
            .assertTextEquals("Setup starten")
            .performClick()

        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test
    fun workbenchIsOnlyAvailableUnderAdvancedDiagnostics() {
        var opens = 0
        showSidebar(callbacks = settingsCallbacks().copy(onOpenWorkbench = { opens++ }))

        compose.onNodeWithTag("open_workbench").assertDoesNotExist()
        openGroup("maintenance")
        compose.onNodeWithTag("open_workbench").performScrollTo().performClick()

        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test
    fun movedControlsHaveOnePrimaryCategoryAndKeyfreeSpeechHasNoApiControls() {
        showSidebar()
        openGroup("runtime")
        compose.onNodeWithTag("codex_update").assertDoesNotExist()
        compose.onNodeWithTag("settings_back_to_groups").performClick()
        openGroup("speech")
        compose.onNodeWithTag("confirmed_stt_glossary_status").assertDoesNotExist()
        compose.onNodeWithTag("open_voice_chooser").assertExists()
        compose.onNodeWithTag("speech_credential_status").assertDoesNotExist()
        compose.onNodeWithTag("open_openai_billing_page").assertDoesNotExist()
        compose.onNodeWithTag("advanced_read_aloud_toggle").assertDoesNotExist()
        compose.onNodeWithTag("read_aloud_live_voice_help").assertExists()
        compose.onNodeWithTag("settings_back_to_groups").performClick()
        openGroup("personal")
        compose.onNodeWithTag("native_memory_health_open").assertExists()
        compose.onNodeWithTag("confirmed_stt_glossary_status").assertDoesNotExist()
        compose.onNodeWithTag("notification_fact_archive_status").assertDoesNotExist()
        compose.onNodeWithTag("whatsapp_agent_channel").assertDoesNotExist()
        compose.onNodeWithTag("settings_back_to_groups").performClick()
        openGroup("permissions")
        compose.onNodeWithTag("whatsapp_agent_channel").assertExists()
        compose.onNodeWithTag("notification_fact_archive_status").assertExists()
        compose.onNodeWithTag("notification_fact_archive_details").assertDoesNotExist()
        compose.onNodeWithTag("notification_fact_archive_details_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("notification_fact_archive_details").assertExists()
        compose.onNodeWithTag("settings_back_to_groups").performClick()
        openGroup("maintenance")
        listOf("codex_update", "hans_app_version", "export_hans_backup", "remote_worker_state",
            "open_workbench", "open_source_licenses").forEach { compose.onNodeWithTag(it).assertExists() }
        compose.onNodeWithTag("speech_credential_status").assertDoesNotExist()
        compose.onNodeWithTag("native_memory_health_open").assertDoesNotExist()
        compose.onNodeWithTag("remote_worker_id").assertDoesNotExist()
    }

    @Test
    fun quickSettingsTileIsAnInputSetupActionNotAnAndroidPermissionOrInventedToggle() {
        var requests = 0
        showSidebar(settings = { SettingsUiState(capabilityAccess = listOf(
            CapabilityAccessUiModel(CapabilityAccessUiId.QUICK_SETTINGS_TILE, "Diktat-Kachel hinzufügen", "Android-Schnelleinstellungen", true),
        )) }, callbacks = settingsCallbacks().copy(onCapabilityAccessRequested = {
            assertEquals(CapabilityAccessUiId.QUICK_SETTINGS_TILE, it)
            requests++
        }))
        openGroup("permissions")
        compose.onNodeWithTag("grant_quick_settings_tile").assertDoesNotExist()
        compose.onNodeWithTag("settings_back_to_groups").performClick()
        openGroup("input")
        compose.onNodeWithTag("grant_quick_settings_tile").performScrollTo()
            .assertTextEquals("Diktat-Kachel hinzufügen").performClick()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test
    fun legacyAdvancedDestinationRendersSystemButDoesNotActivateTheOutgoingWorker() {
        var opens = 0
        var activations = 0
        compose.setGermanContent {
            MaterialTheme {
                SettingsContent(SettingsUiState(), settingsCallbacks().copy(
                    onRemoteWorkerSettingsOpened = { opens++ },
                    onActivateRemoteWorker = { activations++ },
                ), SettingsGroup.ADVANCED_WORK)
            }
        }
        compose.onNodeWithTag("codex_update").assertExists()
        compose.onNodeWithTag("remote_worker_state").assertExists()
        compose.onNodeWithTag("remote_worker_id").assertDoesNotExist()
        compose.onNodeWithTag("remote_control_settings").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, opens); assertEquals(0, activations) }
    }

    @Test
    fun sixCategoriesRenderEnglishFallbackAndGermanWithEinkPreference() {
        val language = mutableStateOf("en-US")
        compose.setContent {
            val base = LocalContext.current
            val configuration = LocalConfiguration.current
            val context = remember(base, configuration, language.value) {
                base.createConfigurationContext(Configuration(configuration).apply {
                    setLocales(LocaleList.forLanguageTags(language.value))
                })
            }
            CompositionLocalProvider(LocalContext provides context,
                LocalConfiguration provides context.resources.configuration) {
                MaterialTheme {
                    SettingsScreen(SettingsUiState(displayMotionMode = DisplayMotionMode.E_INK), settingsCallbacks())
                }
            }
        }
        listOf("en-US", "fr-FR", "de-DE").forEach { locale ->
            compose.runOnIdle { language.value = locale }
            val titles = if (locale == "de-DE") groups.map { it.second } else listOf(
                "Model & responses", "Voice & dictation", "Keys & display", "Hans & memory",
                "Permissions & privacy", "System & advanced",
            )
            groups.zip(titles).forEach { (group, title) ->
                compose.onNodeWithTag("settings_group_${group.first}").performScrollTo().assertTextEquals(title)
            }
            compose.onNodeWithTag("settings_group_advanced_work").assertDoesNotExist()
            compose.onNodeWithTag("settings_group_remote_control").assertDoesNotExist()
        }
    }

    @Test
    fun allFilesAccessShowsOnlyConfirmedStateAndAlwaysAllowsSystemManagement() {
        var opens = 0
        val state = mutableStateOf(SettingsUiState(capabilityAccess = listOf(
            CapabilityAccessUiModel(CapabilityAccessUiId.ALL_FILES, "Dateizugriff · alle Dateien",
                "Android-Sonderfreigabe", false),
        )))
        showSidebar(settings = { state.value }, callbacks = settingsCallbacks().copy(
            onCapabilityAccessRequested = { assertEquals(CapabilityAccessUiId.ALL_FILES, it); opens++ },
        ))
        openGroup("permissions")
        compose.onNodeWithTag("grant_all_files").performScrollTo().assertTextEquals("Einrichten").performClick()
        compose.onNodeWithTag("grant_all_files").assertTextEquals("Einrichten")
        compose.runOnIdle {
            state.value = state.value.copy(capabilityAccess = state.value.capabilityAccess.map { it.copy(granted = true) })
        }
        compose.onNodeWithTag("grant_all_files").assertTextEquals("In Android verwalten").performClick()
        compose.runOnIdle {
            assertEquals(2, opens)
            state.value = state.value.copy(capabilityAccess = state.value.capabilityAccess.map { it.copy(granted = false) })
        }
        compose.onNodeWithTag("grant_all_files").assertTextEquals("Einrichten")
    }

    @Test
    fun androidBackReturnsFromTheGroupBeforeItClosesTheSidebar() {
        var closes = 0
        showSidebar(onClosed = { closes++ })
        openGroup("runtime")

        pressAndroidBack()
        compose.onNodeWithTag("settings_group_overview").assertExists()
        compose.onNodeWithTag("settings_content").assertDoesNotExist()
        compose.onNodeWithTag("chat_navigation_panel").assertExists()
        compose.runOnIdle { assertEquals(0, closes) }

        pressAndroidBack()
        compose.onNodeWithTag("chat_navigation_panel").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, closes) }
    }

    @Test
    fun detailCloseAndOverviewScrimResetReopeningAndPreserveTheDraft() {
        showSidebar()

        listOf("close_chat_navigation", "chat_navigation_scrim").forEach { closeTag ->
            openGroup("input")
            compose.onNodeWithTag("display_motion_effective").assertExists()
            compose.onNodeWithTag("chat_navigation_scrim").assertDoesNotExist()
            if (closeTag == "chat_navigation_scrim") {
                // Detail pages deliberately fill the width: only the compact overview has a scrim.
                compose.onNodeWithTag("settings_back_to_groups").performClick()
                compose.onNodeWithTag("settings_group_overview").assertExists()
                compose.onNodeWithTag("settings_content").assertDoesNotExist()
            }
            compose.onNodeWithTag(closeTag).performClick()
            compose.onNodeWithTag("chat_navigation_panel").assertDoesNotExist()
            compose.onNodeWithTag("composer").assertTextContains(DRAFT)

            compose.onNodeWithTag("open_chat_navigation").performClick()
            compose.onNodeWithTag("settings_group_overview").assertExists()
            compose.onNodeWithTag("settings_content").assertDoesNotExist()
            compose.onNodeWithTag("settings_group_title").assertDoesNotExist()
        }
    }

    @Test
    fun swipingRightFromAGroupClosesTheSidebarAndReopensTheOverview() {
        var closes = 0
        showSidebar(onClosed = { closes++ })
        openGroup("input")
        compose.onNodeWithTag("chat_navigation_panel").performTouchInput {
            swipe(
                start = Offset(width * 0.25f, centerY),
                end = Offset(width * 0.85f, centerY),
                durationMillis = 350,
            )
        }
        compose.onNodeWithTag("chat_navigation_panel").assertDoesNotExist()
        compose.onNodeWithTag("composer").assertTextContains(DRAFT)
        compose.runOnIdle { assertEquals(1, closes) }

        compose.onNodeWithTag("open_chat_navigation").performClick()
        compose.onNodeWithTag("settings_group_overview").assertExists()
        compose.onNodeWithTag("settings_content").assertDoesNotExist()
        compose.onNodeWithTag("settings_group_title").assertDoesNotExist()
    }

    @Test
    fun androidBackFromTheLicenseDialogKeepsTheMaintenanceGroupOpen() {
        var closes = 0
        showSidebar(onClosed = { closes++ })
        openGroup("maintenance")
        compose.onNodeWithTag("open_source_licenses").performScrollTo().performClick()
        compose.onNodeWithTag("third_party_notices").assertExists()

        pressAndroidBack()
        compose.onNodeWithTag("third_party_notices").assertDoesNotExist()
        compose.onNodeWithTag("settings_group_title").assertTextEquals("System & Erweitert")
        compose.onNodeWithTag("settings_content").assertExists()
        compose.onNodeWithTag("settings_group_overview").assertDoesNotExist()
        compose.onNodeWithTag("open_source_licenses").assertExists()
        compose.runOnIdle { assertEquals(0, closes) }

        compose.onNodeWithTag("settings_back_to_groups").performClick()
        compose.onNodeWithTag("settings_group_overview").assertExists()
        compose.runOnIdle { assertEquals(0, closes) }
    }

    @Test
    fun modelEffortAndFastChoicesRemainServerConfirmedAcrossGroupsAndReopening() {
        val state = mutableStateOf(
            SettingsUiState(
                selectedModelId = "gpt-5.6-luna",
                selectedReasoningEffortId = "max",
                reasoningEfforts = listOf(ReasoningEffortUiOption.MAX, ReasoningEffortUiOption.ULTRA),
                fastModeAvailable = true,
                fastModeEnabled = false,
                runtimeNotice = "Warte auf Codex-Bestätigung.",
            ),
        )
        val models = mutableListOf<String>()
        val efforts = mutableListOf<String>()
        val fastModes = mutableListOf<Boolean>()
        showSidebar(
            settings = { state.value },
            callbacks = settingsCallbacks().copy(
                onModelSelected = models::add,
                onReasoningEffortSelected = efforts::add,
                onFastModeChanged = fastModes::add,
            ),
        )
        openGroup("runtime")
        compose.onNodeWithTag("model_gpt-5.6-sol").performScrollTo().performClick()
        compose.onNodeWithTag("effort_ultra").performScrollTo().performClick()
        compose.onNodeWithTag("service_tier_fast").performScrollTo().performClick()
        assertPreviousConfirmedSelection()

        compose.onNodeWithTag("settings_back_to_groups").performClick()
        openGroup("permissions")
        compose.onNodeWithTag("model_gpt-5.6-sol").assertDoesNotExist()
        compose.onNodeWithTag("settings_back_to_groups").performClick()
        openGroup("runtime")
        assertPreviousConfirmedSelection()
        compose.onNodeWithTag("runtime_notice").assertExists()
        compose.onNodeWithText("Warte auf Codex-Bestätigung.").assertExists()

        compose.onNodeWithTag("close_chat_navigation").performClick()
        compose.onNodeWithTag("open_chat_navigation").performClick()
        compose.onNodeWithTag("settings_group_overview").assertExists()
        openGroup("runtime")
        assertPreviousConfirmedSelection()
        compose.onNodeWithText("Warte auf Codex-Bestätigung.").assertExists()

        // Only a new owner-provided, App Server-confirmed state changes the marked values.
        compose.runOnIdle {
            state.value = state.value.copy(
                selectedModelId = "gpt-5.6-sol",
                selectedReasoningEffortId = "ultra",
                fastModeEnabled = true,
                runtimeNotice = "Auswahl bestätigt.",
            )
        }
        compose.onNodeWithTag("model_gpt-5.6-sol").assertIsSelected()
        compose.onNodeWithTag("model_gpt-5.6-luna").assertIsNotSelected()
        compose.onNodeWithTag("effort_ultra").assertIsSelected()
        compose.onNodeWithTag("service_tier_fast").assertIsSelected()

        compose.onNodeWithTag("close_chat_navigation").performClick()
        compose.onNodeWithTag("open_chat_navigation").performClick()
        compose.onNodeWithTag("settings_group_overview").assertExists()
        openGroup("runtime")
        compose.onNodeWithTag("model_gpt-5.6-sol").assertIsSelected()
        compose.onNodeWithTag("effort_ultra").assertIsSelected()
        compose.onNodeWithTag("service_tier_fast").assertIsSelected()
        compose.onNodeWithTag("runtime_notice").assertExists()
        compose.onNodeWithText("Auswahl bestätigt.").assertExists()
        compose.runOnIdle {
            assertEquals(listOf("gpt-5.6-sol"), models)
            assertEquals(listOf("ultra"), efforts)
            assertEquals(listOf(true), fastModes)
        }
    }

    @Test
    fun legacySettingsUsesTheSameGroupsAndOnlyExitsFromTheOverview() {
        var exits = 0
        compose.setGermanContent {
            MaterialTheme {
                SettingsScreen(SettingsUiState(), settingsCallbacks().copy(onBack = { exits++ }))
            }
        }
        compose.onNodeWithTag("settings_group_overview").assertExists()
        compose.onNodeWithTag("settings_content").assertDoesNotExist()
        openGroup("input")
        compose.onNodeWithText("Tasten & Anzeige").assertExists()
        compose.onNodeWithTag("display_motion_effective").assertExists()
        compose.onNodeWithTag("model_gpt-5.6-luna").assertDoesNotExist()

        compose.onNodeWithTag("navigate_back").performClick()
        compose.onNodeWithTag("settings_group_overview").assertExists()
        compose.runOnIdle { assertEquals(0, exits) }
        openGroup("runtime")
        pressAndroidBack()
        compose.onNodeWithTag("settings_group_overview").assertExists()
        compose.runOnIdle { assertEquals(0, exits) }
        pressAndroidBack()
        compose.runOnIdle { assertEquals(1, exits) }
    }

    private fun showSidebar(
        settings: () -> SettingsUiState = { SettingsUiState() },
        callbacks: SettingsUiCallbacks = settingsCallbacks(),
        onClosed: () -> Unit = {},
        onOpenWorkbench: () -> Unit = {},
    ) {
        compose.setGermanContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatUiState(composer = ComposerUiState(text = DRAFT)),
                    callbacks = ChatUiCallbacks(
                        onComposerChanged = {},
                        onSend = {},
                        onChooseMedia = {},
                        onRemoveAttachment = {},
                        onOpenApps = {},
                        onOpenPlugins = {},
                        onOpenSettings = {},
                        onToggleLiveVoice = {},
                        onOpenWorkbench = onOpenWorkbench,
                    ),
                    sidebarSettings = settings(),
                    sidebarCallbacks = callbacks,
                    onSidebarClosed = onClosed,
                    displayMotionMode = DisplayMotionMode.E_INK,
                )
            }
        }
        compose.onNodeWithTag("open_chat_navigation").performClick()
    }

    private fun openGroup(id: String) {
        compose.onNodeWithTag("settings_group_$id").performScrollTo().performClick()
        compose.onNodeWithTag("settings_content").assertExists()
    }

    private fun pressAndroidBack() {
        // Native key injection does not use Compose's synchronization. Let the
        // clicked destination and its BackHandler commit before pressing Back.
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    private fun assertPreviousConfirmedSelection() {
        compose.onNodeWithTag("model_gpt-5.6-luna").assertIsSelected()
        compose.onNodeWithTag("model_gpt-5.6-sol").assertIsNotSelected()
        compose.onNodeWithTag("effort_max").assertIsSelected()
        compose.onNodeWithTag("effort_ultra").assertIsNotSelected()
        compose.onNodeWithTag("service_tier_standard").assertIsSelected()
        compose.onNodeWithTag("service_tier_fast").assertIsNotSelected()
    }

    private fun settingsCallbacks() = SettingsUiCallbacks(
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
    )

    private companion object {
        const val DRAFT = "Unveränderter Entwurf"
        val groups = listOf(
            Triple("runtime", "Modell & Antworten", "model_gpt-5.6-luna"),
            Triple("speech", "Sprache & Diktat", "read_aloud_all_messages"),
            Triple("input", "Tasten & Anzeige", "display_motion_effective"),
            Triple("personal", "Hans & Gedächtnis", "native_memory_health_open"),
            Triple("permissions", "Berechtigungen & Datenschutz", "persistent_android_consent_notice"),
            Triple("maintenance", "System & Erweitert", "codex_update"),
        )
    }
}
