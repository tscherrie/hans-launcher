package ai.hans.standard.files

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import ai.hans.standard.phone.capabilities.AndroidCapabilityEnvironment
import ai.hans.standard.phone.capabilities.AndroidSpecialAccess
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Permission changes are allowed ONLY on disposable Android emulators, never the owner's MP01. */
class AndroidSharedFilesTest {
    private lateinit var context: Context
    private var directory: File? = null
    private var emulator = false
    private fun shell(command: String): String {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
    }
    private fun grant(enabled: Boolean) {
        check(emulator)
        shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE ${if (enabled) "allow" else "deny"}")
        assertEquals(enabled, Environment.isExternalStorageManager())
    }

    @Before fun prepare() {
        emulator = Build.HARDWARE in setOf("ranchu", "goldfish") &&
            shell("getprop ro.kernel.qemu").trim() == "1"
        assumeTrue("Never change file grants on a personal device", emulator)
        context = InstrumentationRegistry.getInstrumentation().targetContext
        grant(true)
        directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "hans-file-test-${UUID.randomUUID()}").also { assertTrue(it.mkdir()) }
    }

    @After fun cleanup() {
        if (!emulator || !::context.isInitialized) return
        grant(true)
        directory?.let { folder ->
            check(folder.name.startsWith("hans-file-test-") && folder.parentFile?.name == "Download")
            folder.listFiles().orEmpty().forEach { check(it.isFile); assertTrue(it.delete()) }
            assertTrue(folder.delete())
        }
        grant(false)
    }

    @Test fun realPublicSaveReadMediaIndexAndRevocation() {
        val store = AndroidSharedFiles.store(context)
        val path = File(checkNotNull(directory), "speech.wav").path
        val bytes = "RIFF-test-audio".toByteArray()
        val saved = store.save(path, bytes.inputStream(), 1024) {}
        assertEquals(path, saved.path)
        assertArrayEquals(bytes, store.open(path).use { it.readBytes() })
        assertTrue(AndroidCapabilityEnvironment(context).hasSpecialAccess(AndroidSpecialAccess.ALL_FILES))
        grant(false)
        assertFalse(store.granted())
        assertFalse(AndroidCapabilityEnvironment(context).hasSpecialAccess(AndroidSpecialAccess.ALL_FILES))
        try { store.open(path); fail("Revoked access must fail") }
        catch (error: FileAccessFailure) { assertEquals("all_files_access_required", error.code) }
    }

    @Test fun perFileProviderIsReadOnlyAndDoesNotExposeRootOrUnknownTokens() {
        val store = AndroidSharedFiles.store(context)
        val path = File(checkNotNull(directory), "note.txt").path
        val saved = store.save(path, "hello".byteInputStream(), 100) {}
        val links = AndroidFileLinks(context).issue(saved)
        val token = Uri.parse(links.first).lastPathSegment!!
        val uri = Uri.parse("content://${context.packageName}.files/$token")
        assertEquals("hello", context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
        try { context.contentResolver.openOutputStream(uri); fail("Write URI must fail") }
        catch (_: java.io.FileNotFoundException) { }
        val provider = context.packageManager.resolveContentProvider(context.packageName + ".files", 0)!!
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        try {
            context.contentResolver.openInputStream(Uri.parse("content://${context.packageName}.files/" + "0".repeat(32)))
            fail("Unknown token must fail")
        } catch (_: java.io.FileNotFoundException) { }
        grant(false)
        try { context.contentResolver.openInputStream(uri); fail("Grant revocation must invalidate reads") }
        catch (_: java.io.FileNotFoundException) { }
    }

    @Test fun declaresSpecialPermissionAndDoesNotConfuseItWithReadMediaAudio() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions.orEmpty().contains("android.permission.MANAGE_EXTERNAL_STORAGE"))
        grant(false)
        assertFalse(AndroidSharedFiles.store(context).granted())
    }
}
