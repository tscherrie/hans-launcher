package ai.hans.standard.workspace

import ai.hans.standard.integration.AppPrivateCodexSessionStore
import ai.hans.standard.setup.AndroidHansSetupFreshProbe
import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable cache fixtures only; never touches a real chat, login or runtime. */
@RunWith(AndroidJUnit4::class)
class HansDesktopProjectAndroidTest {
    @Test
    fun chatStartupPreparesOnePrivateProjectAndRetainsExistingConversationAndFiles() = withContext { context, _ ->
        val beforeGrants = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.RECORD_AUDIO)
            .associateWith(context::checkSelfPermission)
        assertNull(HansDesktopProject.preparedPath(context.filesDir))
        val first = AppPrivateCodexSessionStore(context)
        val path = first.workspacePath
        assertEquals(File(context.filesDir, "codex-workspace").canonicalPath, path)
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(File(path).toPath()))
        assertTrue(File(path).listFiles()!!.isEmpty())
        first.saveThreadId("synthetic-desktop-project-thread")
        val note = File(path, "note.txt").apply { writeText("preserved") }

        val reopened = AppPrivateCodexSessionStore(context)

        assertEquals(path, reopened.workspacePath)
        assertEquals("synthetic-desktop-project-thread", reopened.readThreadId())
        assertEquals("preserved", note.readText())
        assertEquals(beforeGrants, beforeGrants.keys.associateWith(context::checkSelfPermission))
    }

    @Test
    fun freshSetupGuidanceIsLocalizedAndUnavailableAfterProjectRemoval() {
        listOf(Locale.ENGLISH to "After pairing", Locale.GERMAN to "Nach dem Koppeln").forEach { (locale, opening) ->
            withContext(locale) { context, _ ->
                val probe = AndroidHansSetupFreshProbe(context)
                assertNull(probe.desktopProject())
                val project = HansDesktopProject.ensure(context.filesDir)
                val guidance = checkNotNull(probe.desktopProject())
                assertEquals(project.canonicalPath, guidance.path)
                assertTrue(guidance.instructions.startsWith(opening))
                assertTrue(guidance.instructions.contains(project.canonicalPath))
                assertTrue(guidance.permissions.isNotBlank())
                assertTrue(project.delete())
                assertNull(probe.desktopProject())
                assertFalse(project.exists())
            }
        }
    }

    @Test
    fun symbolicLinkIsRejectedWithoutReadingOrChangingTargetFiles() = withContext { context, _ ->
        val target = File(context.filesDir, "unrelated").apply { mkdir() }
        val note = File(target, "untouched.txt").apply { writeText("unchanged") }
        Files.createSymbolicLink(File(context.filesDir, "codex-workspace").toPath(), target.toPath())
        assertTrue(runCatching { AppPrivateCodexSessionStore(context) }.isFailure)
        assertNull(AndroidHansSetupFreshProbe(context).desktopProject())
        assertEquals("unchanged", note.readText())
    }

    private fun withContext(locale: Locale = Locale.ENGLISH, action: (Context, File) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory(app.cacheDir.toPath(), "desktop-project-test-").toFile()
        val preferences = mutableSetOf<String>()
        val prefix = "desktop_project_test_${UUID.randomUUID()}_"
        val config = Configuration(app.resources.configuration).apply { setLocales(LocaleList(locale)) }
        val localized = app.createConfigurationContext(config)
        val context = object : ContextWrapper(localized) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolatedName = prefix + name
                preferences += isolatedName
                return app.getSharedPreferences(isolatedName, mode)
            }
        }
        try {
            action(context, root)
        } finally {
            preferences.forEach(app::deleteSharedPreferences)
            root.deleteRecursively()
        }
    }
}
