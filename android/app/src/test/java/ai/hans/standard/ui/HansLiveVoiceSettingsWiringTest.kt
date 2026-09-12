package ai.hans.standard.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the Android owner boundary without starting a device, speech provider, or Live call. */
class HansLiveVoiceSettingsWiringTest {
    @Test
    fun liveSelectionSavesOnlyTheNextCallPreferenceAndRetainsSettingsOnFailure() {
        val source = launcherSource().readText()
        val saveLiveVoice = source
            .substringAfter("private fun saveLiveVoice(voice: String) {")
            .substringBefore("private fun refreshCapabilityAccess()")

        assertTrue(source.contains("onLiveVoiceSelected = ::saveLiveVoice"))
        assertTrue(saveLiveVoice.contains("settings = runCatching {"))
        assertTrue(saveLiveVoice.contains("settingsStore.saveLiveVoice(voice)"))
        assertTrue(saveLiveVoice.contains("}.getOrElse {"))
        assertTrue(saveLiveVoice.contains("showShortMessage(\"Die Live-Stimme konnte nicht gespeichert werden.\")"))
        assertTrue(saveLiveVoice.contains("\n            settings\n"))
        assertFalse(saveLiveVoice.contains("settingsStore.saveVoice("))
        assertFalse(saveLiveVoice.contains("sessionHost"))
        assertFalse(saveLiveVoice.contains("previewSpeech"))
        assertFalse(saveLiveVoice.contains("AndroidLiveVoiceRuntime"))
        assertFalse(saveLiveVoice.contains("stopLiveVoice"))
        assertFalse(saveLiveVoice.contains("startLiveVoice"))
        assertFalse(saveLiveVoice.contains("localUi"))
    }

    @Test
    fun readAloudSelectionAndPreviewKeepTheirOriginalSpeechOnlyWiring() {
        val source = launcherSource().readText()
        val preview = source
            .substringAfter("onPreviewVoice = {")
            .substringBefore("onStartActionKeySetup =")

        assertTrue(source.contains("onVoiceSelected = { voice -> saveVoice(voice = voice) }"))
        assertTrue(preview.contains("sessionHost.previewSpeech()"))
        assertFalse(preview.contains("saveLiveVoice"))
        assertFalse(preview.contains("AndroidLiveVoiceRuntime"))
    }

    private fun launcherSource(): File = sequenceOf(
        File("src/main/java/ai/hans/standard/LauncherActivity.kt"),
        File("android/app/src/main/java/ai/hans/standard/LauncherActivity.kt"),
    ).firstOrNull(File::isFile) ?: error("LauncherActivity.kt not found")
}
