package ai.hans.standard.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wiring boundaries supplement, but do not replace, actual Activity instrumentation. */
class LauncherLocaleLifecycleSourceTest {
    @Test
    fun localeProbeBelongsOnlyToTheNonExportedDefaultProcessDebugTarget() {
        val name = "ai.hans.standard.ui.LocaleConfigurationProbeActivity"
        assertFalse(source("src/main/AndroidManifest.xml").readText().contains(name))
        assertFalse(source("src/androidTest/AndroidManifest.xml").readText().contains(name))
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(source("src/debug/AndroidManifest.xml"))
        val activities = manifest.getElementsByTagName("activity")
        val probe = (0 until activities.length).map { activities.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(ANDROID, "name") == name }
        assertEquals("false", probe.getAttributeNS(ANDROID, "exported"))
        assertEquals("", probe.getAttributeNS(ANDROID, "process"))
        val debug = source("src/debug/java/ai/hans/standard/ui/LocaleConfigurationProbeActivity.kt").readText()
        assertTrue(debug.contains("check(BuildConfig.DEBUG)"))
        assertFalse(debug.contains("InstrumentationRegistry"))
        assertFalse(source("src/main/AndroidManifest.xml").parentFile
            .resolve("java/ai/hans/standard/ui/LocaleConfigurationProbeActivity.kt").exists())
        assertFalse(source("src/androidTest/java/ai/hans/standard/ui/LocaleActivityLifecycleTest.kt").readText()
            .contains("class LocaleConfigurationProbeActivity"))
    }

    @Test
    fun launcherUsesTheSharedLocaleOnlyCallbackWithoutReplacingRuntimeOrUnsentState() {
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(source("src/main/AndroidManifest.xml"))
        val activities = manifest.getElementsByTagName("activity")
        val launcher = (0 until activities.length).map { activities.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(ANDROID, "name") == ".LauncherActivity" }
        assertEquals(setOf("locale", "layoutDirection"),
            launcher.getAttributeNS(ANDROID, "configChanges").split('|').toSet())
        val activity = source("src/main/java/ai/hans/standard/LauncherActivity.kt").readText()
        assertTrue(activity.contains("class LauncherActivity : HansLocaleAwareActivity()"))
        val callback = activity.substringAfter("override fun refreshLocalizedPresentation(")
            .substringBefore("override fun onResume(").replace(Regex("(?m)//.*$"), "")
        assertTrue(callback.contains("localUi = localUi.copy(revision = localUi.revision + 1)"))
        assertTrue(callback.contains("refreshCapabilityAccess()"))
        for (forbidden in listOf("recreate(", "setContent", "sessionHost =", "onSend", "interrupt(",
            "startLive", "onToggleLiveVoice", "composer =", "attachments =")) {
            assertFalse("Locale callback must not contain $forbidden", callback.contains(forbidden))
        }
        val shared = source("src/main/java/ai/hans/standard/localization/HansLocaleAwareActivity.kt").readText()
        assertTrue(shared.contains("final override fun onConfigurationChanged("))
        val superCall = shared.indexOf("super.onConfigurationChanged(newConfig)")
        val refreshCall = shared.indexOf("refreshLocalizedPresentation(newConfig)")
        assertTrue(superCall >= 0 && refreshCall > superCall)
        assertEquals(1, Regex("refreshLocalizedPresentation\\(newConfig\\)").findAll(shared).count())
    }

    private fun source(path: String): File = sequenceOf(File(path), File("android/app", path))
        .firstOrNull(File::isFile) ?: error("Missing source $path")

    private companion object { const val ANDROID = "http://schemas.android.com/apk/res/android" }
}
