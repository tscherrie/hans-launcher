package ai.hans.standard.ui

import java.io.File
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
    }

    @Test
    fun idleSendWaitsLocallyWithoutBlockingBusySteerOrInventingAmbiguousDelivery() {
        val source = source("LauncherActivity.kt").substringAfter("private fun sendComposer(displayText: String)")
            .substringBefore("val attachments = localUi.attachments")
        assertTrue(source.contains("snapshot?.pendingSettingsSelection != null"))
        assertTrue(source.contains("snapshot.sessionPhase != ClientSessionPhase.BUSY"))
        assertTrue(source.contains("Bitte gleich erneut senden."))
        assertFalse(source.contains("sessionHost.dispatch("))
        assertFalse(source.contains("localUi.copy("))
        assertFalse(source.contains("Prüfe den Chat"))
    }

    @Test
    fun confirmationNoticePrecedesModelControlsAndRuntimeUpdater() {
        val source = source("ui/SettingsScreen.kt").substringAfter("private fun RuntimeSettings(")
            .substringBefore("private fun SpeechSettings(")
        assertEquals(1, Regex("testTag\\(\"runtime_notice\"\\)").findAll(source).count())
        assertTrue(source.indexOf("testTag(\"runtime_notice\")") < source.indexOf("SettingsSection(title = \"Modell\")"))
        assertFalse(source.contains("Die Markierung ändert sich erst"))
    }

    private fun source(path: String): String = sequenceOf(
        File("src/main/java/ai/hans/standard/$path"),
        File("android/app/src/main/java/ai/hans/standard/$path"),
    ).first(File::isFile).readText()
}
