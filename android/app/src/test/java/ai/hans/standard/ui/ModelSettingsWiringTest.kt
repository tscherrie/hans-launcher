package ai.hans.standard.ui

import ai.hans.standard.R
import ai.hans.standard.localization.TestResourceTextResolver
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

/** Android boundary guard complements real controller protocol and Compose tests. */
class ModelSettingsWiringTest {
    @Test
    fun settingsAndPresetCallProcessOwnedUpdateRatherThanStageALocalDraft() {
        val source = source("LauncherActivity.kt")
        val request = source.substringAfter("private fun requestSelection(")
            .substringBefore("private fun toggleModelPreset()")
        assertTrue(request.contains("sessionHost.updateSelection(resolved)"))
        assertFalse(request.contains("localUi.copy"))
        assertFalse(request.contains("sessionHost.dispatch"))
        assertFalse(source.contains("stageSelection("))
        assertFalse(source.contains("pendingModelId ="))
        val callbacks = source.substringAfter("onModelSelected = { model ->")
            .substringBefore("onVoiceSelected =")
        assertEquals(3, Regex("requestSelection\\(").findAll(callbacks).count())
        assertEquals(3, Regex("selectionForSettingsChange\\(").findAll(callbacks).count())
    }

    @Test
    fun modelShortcutUsesForegroundPreImeRoutingAndClearsOwnedPressesOnFocusLoss() {
        val launcher = source("LauncherActivity.kt")
        assertTrue(launcher.contains("modifier = Modifier.hansModelShortcutKeys(::routeModelKeyBeforeIme)"))
        assertTrue(source("ui/HansModelShortcutKeys.kt").contains("onPreInterceptKeyBeforeSoftKeyboard"))
        val preIme = launcher.substringAfter("private fun routeModelKeyBeforeIme(")
            .substringBefore("private fun routeForegroundModelKey(")
        assertTrue(preIme.contains("!hasWindowFocus()"))
        assertTrue(preIme.contains("activeActionKeyCapture != null"))
        assertEquals(3, Regex("foregroundModelKeyRouter.clearActivePresses\\(\\)").findAll(launcher).count())
        val preset = launcher.substringAfter("private fun toggleModelPreset()")
            .substringBefore("private fun refreshRemoteWorkerSettings(")
        assertTrue(preset.contains("HansModelPreset.ASTRA_ULTRA"))
        assertFalse(preset.contains("HansModelPreset.SOL_ULTRA"))
        assertTrue(preset.contains("requestSelection("))
        assertTrue(preset.contains("resolveModelShortcutPreset(next, clientSnapshot?.models.orEmpty())"))
        assertTrue(preset.contains("requireExactEffort = true"))
    }

    @Test
    fun idleSendWaitsLocallyWithoutBlockingBusySteerOrInventingAmbiguousDelivery() {
        val source = source("LauncherActivity.kt").substringAfter("private fun sendComposer(displayText: String)")
            .substringBefore("val attachments = localUi.attachments")
        assertTrue(source.contains("snapshot?.pendingSettingsSelection != null"))
        assertTrue(source.contains("snapshot.sessionPhase != ClientSessionPhase.BUSY"))
        assertTrue(source.contains(
            "showShortMessage(tr(R.string.integration_the_model_selection_is_still_being_confirmed_please_sen_663a0ad))",
        ))
        assertEquals("Die Modellauswahl wird noch bestätigt. Bitte gleich erneut senden.",
            TestResourceTextResolver(Locale.GERMAN).text(
                R.string.integration_the_model_selection_is_still_being_confirmed_please_sen_663a0ad))
        assertEquals("The model selection is still being confirmed. Please send again shortly.",
            TestResourceTextResolver(Locale.ENGLISH).text(
                R.string.integration_the_model_selection_is_still_being_confirmed_please_sen_663a0ad))
        assertFalse(source.contains("sessionHost.dispatch("))
        assertFalse(source.contains("localUi.copy("))
        assertFalse(source.contains("R.string.integration_the_message_could_not_be_confirmed_the_draft_is_preserv_873e84b"))
    }

    @Test
    fun confirmationNoticePrecedesModelEffortAndSpeedControlsWithoutUnrelatedUpdates() {
        val source = source("ui/SettingsScreen.kt").substringAfter("private fun RuntimeSettings(")
            .substringBefore("private fun UpdateSettings(")
        assertEquals(1, Regex("testTag\\(\"runtime_notice\"\\)").findAll(source).count())
        val notice = source.indexOf("testTag(\"runtime_notice\")")
        val models = source.indexOf("SettingsSection(title = uiText.text(R.string.ui_model_ff461e))")
        val efforts = source.indexOf("SettingsSection(title = uiText.text(R.string.ui_reasoning_effort_cd8569))")
        val speed = source.indexOf("SettingsSection(title = uiText.text(R.string.ui_response_speed_3ec789))")
        assertTrue("Each notice/model-control section must exist", listOf(notice, models, efforts, speed).all { it >= 0 })
        assertTrue("Confirmation notice must precede model, effort and speed controls",
            notice < models && models < efforts && efforts < speed)
        assertFalse("Updating Hans is not a runtime-selection setting", source.contains("testTag(\"codex_update\")"))
        assertFalse(source.contains("UpdateSettings(state, callbacks)"))
        assertEquals("Modell", TestResourceTextResolver(Locale.GERMAN).text(R.string.ui_model_ff461e))
        assertEquals("Model", TestResourceTextResolver(Locale.ENGLISH).text(R.string.ui_model_ff461e))
        assertFalse(source.contains("Die Markierung ändert sich erst"))
    }

    @Test
    fun systemOwnsTheUpdateActionAndStillShowsOnlyConfirmedRuntimeReadiness() {
        val screen = source("ui/SettingsScreen.kt")
        val updates = screen.substringAfter("private fun UpdateSettings(")
            .substringBefore("private fun SpeechSettings(")
        val system = screen.substringAfter("private fun SystemSettings(")
            .substringBefore("private fun privateSpaceSettingsDescription(")
        assertEquals(1, Regex("testTag\\(\"codex_update\"\\)").findAll(updates).count())
        assertTrue(updates.contains("onClick = callbacks.onUpdateCodex"))
        assertTrue(updates.contains("state.codexUpdate.runtimeReady"))
        assertTrue(updates.contains("testTag(\"codex_runtime_state\")"))
        assertTrue(updates.contains("state.codexUpdate.bundledRuntimeVersion"))
        assertTrue(updates.contains("testTag(\"hans_app_version\")"))
        val update = system.indexOf("UpdateSettings(state, callbacks)")
        val backup = system.indexOf("SettingsSection(title = uiText.text(R.string.ui_backup_07ad3e))")
        assertTrue("System puts updates before its backup controls", update >= 0 && backup > update)
        assertFalse(system.contains("RuntimeSettings(state, callbacks)"))
        assertEquals("Hans aktualisieren", TestResourceTextResolver(Locale.GERMAN).text(R.string.ui_update_codex_62b143))
        assertEquals("Update Hans", TestResourceTextResolver(Locale.ENGLISH).text(R.string.ui_update_codex_62b143))
    }

    private fun source(path: String): String = sequenceOf(
        File("src/main/java/ai/hans/standard/$path"),
        File("android/app/src/main/java/ai/hans/standard/$path"),
    ).first(File::isFile).readText()
}
