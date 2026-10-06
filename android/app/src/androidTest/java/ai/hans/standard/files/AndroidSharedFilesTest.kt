package ai.hans.standard.files

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.test.platform.app.InstrumentationRegistry
import ai.hans.standard.phone.capabilities.AndroidCapabilityEnvironment
import ai.hans.standard.phone.capabilities.AndroidSpecialAccess
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** The owned emulator runner grants access before instrumentation, never from this process. */
class AndroidSharedFilesTest {
    private lateinit var context: Context
    private var directory: File? = null
    private var privateDirectory: File? = null
    private val retainedFixtures = mutableListOf<SharedFileRecoveryInfo>()

    @Before fun prepare() {
        AndroidSharedFilesTestFixture.requireOwnedEmulator()
        context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Runner must grant all-files access outside instrumentation", Environment.isExternalStorageManager())
        directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "hans-file-test-${UUID.randomUUID()}").also { assertTrue(it.mkdir()) }
    }

    @After fun cleanup() {
        if (!::context.isInitialized || directory == null) return
        assertTrue("The test process must not change its storage AppOp", Environment.isExternalStorageManager())
        directory?.let { folder ->
            check(folder.name.startsWith("hans-file-test-") && folder.parentFile?.name == "Download")
            folder.listFiles().orEmpty().forEach { check(it.isFile); assertTrue(it.delete()) }
            assertTrue(folder.delete())
        }
        privateDirectory?.let { folder ->
            check(folder.name.startsWith("hans-file-test-") && folder.parentFile == context.getExternalFilesDir(null))
            folder.listFiles().orEmpty().forEach { check(Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS)); assertTrue(it.delete()) }
            assertTrue(folder.delete())
        }
        // These are exact, disposable fixture captures created by this test, never arbitrary recovery entries.
        retainedFixtures.forEach { receipt ->
            val entry = File(receipt.recoveryPath)
            val capture = checkNotNull(entry.parentFile)
            check(capture.name == receipt.handle && capture.parentFile?.name == "hans-file-recovery")
            check(listOfNotNull(directory, privateDirectory).any { receipt.originalPath.startsWith(it.path + "/") })
            check(entry.isFile)
            assertTrue(entry.delete())
            assertTrue(File(capture, "receipt").delete())
            assertTrue(capture.delete())
        }
    }

    @Test fun realPublicSaveReadMediaIndexAndRevocation() {
        val store = AndroidSharedFiles.store(context)
        val path = File(checkNotNull(directory), "speech.wav").path
        val bytes = "RIFF-test-audio".toByteArray()
        val saved = store.save(path, bytes.inputStream(), 1024) {}
        assertEquals(path, saved.path)
        assertArrayEquals(bytes, store.open(path).use { it.readBytes() })
        assertTrue(AndroidCapabilityEnvironment(context).hasSpecialAccess(AndroidSpecialAccess.ALL_FILES))
        // Real OS revocation is checked after the runner restarts instrumentation with access denied.
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
    }

    @Test fun declaresSpecialPermissionAndDoesNotConfuseItWithReadMediaAudio() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions.orEmpty().contains("android.permission.MANAGE_EXTERNAL_STORAGE"))
        assertTrue(AndroidSharedFiles.store(context).granted())
    }

    @Test fun legacyPrivateRecoveryCanStillBeRestoredWithoutOverwrite() {
        val store = AndroidSharedFiles.store(context)
        val publicDirectory = checkNotNull(directory)
        val roots = AndroidSharedFiles.recoveryRoots(context)
        val ownerFiles = context.getExternalFilesDirs(null).filterNotNull().map { it.canonicalFile.toPath() }
        assertTrue(roots.isNotEmpty())
        roots.forEach { root ->
            assertTrue(ownerFiles.any { root.privateDirectory.canonicalFile.toPath().startsWith(it) })
            assertTrue(root.privateDirectory.canonicalFile.toPath().startsWith(root.publicRoot.toPath()))
        }
        // Explicit synthetic legacy fixture; ordinary new deletion does not create private recovery.
        val sourceDirectory = privateFixtureDirectory()
        val source = File(sourceDirectory, "atomic-source.txt")
        val privateStore = SharedFileStore({ listOf(sourceDirectory) }, { true }, recoveryRoots = {
            listOf(SharedFileRecoveryRoot(sourceDirectory, roots.first().privateDirectory))
        })
        val original = privateStore.save(source.path, "approved".byteInputStream(), 1024) {}
        val originalKey = Files.readAttributes(source.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
        assertNotNull("Shared filesystem must expose identity for guarded removal", originalKey)
        val retained = AndroidSharedFilesTestFixture.retainLegacy(context, source).also(retainedFixtures::add)
        assertFalse(source.exists())
        assertEquals("approved", File(retained.recoveryPath).readText())
        assertEquals(originalKey, Files.readAttributes(File(retained.recoveryPath).toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey())
        assertTrue(ownerFiles.any { File(retained.recoveryPath).toPath().startsWith(it) })
        try { store.resolve(retained.recoveryPath); fail("Private recovery is not a shared-storage path") }
        catch (failure: FileAccessFailure) { assertEquals("android_private_storage_protected", failure.code) }
        val conflict = File(publicDirectory, "restore-conflict.txt")
        conflict.writeText("keep")
        try { store.restoreRecovery(retained.handle, conflict.path) {}; fail("Recovery must never overwrite") }
        catch (_: java.nio.file.FileAlreadyExistsException) { }
        assertEquals("keep", conflict.readText())
        val restoredPath = File(publicDirectory, "restored.txt")
        val restored = store.restoreRecovery(retained.handle, restoredPath.path) {}
        assertEquals(original.sha256, restored.sha256)
        assertEquals("approved", restoredPath.readText())
        assertEquals("approved", File(retained.recoveryPath).readText())
    }

    @Test fun ordinaryDeleteAndMoveSucceedOnActualPublicAndroidStorageWithoutRecovery() {
        val store = AndroidSharedFiles.store(context)
        val source = File(checkNotNull(directory), "ordinary-€-🧪-source.txt")
        val approved = store.save(source.path, "approved".byteInputStream(), 1024) {}
        val neighbour = File(checkNotNull(directory), "neighbour.txt")
        store.save(neighbour.path, "keep".byteInputStream(), 1024) {}
        val retainedBefore = store.listRecovery()
        val removed = store.delete(source.path, approved.sha256!!) {}
        assertEquals(source.path, removed.path)
        assertFalse(Files.exists(source.toPath(), NOFOLLOW_LINKS))
        assertEquals("keep", neighbour.readText())
        val moving = store.save(source.path, "move-approved".byteInputStream(), 1024) {}
        val destination = File(checkNotNull(directory), "ordinary-€-🧪-destination.txt")
        val moved = store.copy(source.path, destination.path, true, moving.sha256) {}
        assertEquals(moving.sha256, moved.sha256)
        assertEquals(source.path, moved.sourceDeletion!!.path)
        assertEquals("move-approved", destination.readText())
        assertFalse(Files.exists(source.toPath(), NOFOLLOW_LINKS))
        assertEquals("keep", neighbour.readText())
        assertEquals(retainedBefore, store.listRecovery())
    }

    @Test fun replacementObservedBeforeFinalChecksIsRejectedOnActualSharedStorage() {
        val store = AndroidSharedFiles.store(context)
        val source = File(checkNotNull(directory), "replacement.txt")
        val original = store.save(source.path, "approved".byteInputStream(), 1024) {}
        var replaced = false
        try {
            store.delete(source.path, original.sha256!!) {
                val hashing = Thread.currentThread().stackTrace.any {
                    it.className == SharedFileStore::class.java.name && it.methodName in setOf("digest", "digestInput", "digestCaptured")
                }
                if (!hashing && !replaced) {
                    replaced = true
                    assertTrue(source.delete())
                    source.writeText("unapproved replacement")
                }
            }
            fail("A raced-in replacement is not approved by the old hash")
        } catch (failure: FileAccessFailure) {
            assertEquals("file_changed_read_stat_first", failure.code)
            assertNull(failure.recovery)
        }
        assertTrue(replaced)
        assertEquals("unapproved replacement", source.readText())
        assertTrue(store.listRecovery().isEmpty())
    }

    @Test fun publicUnlinkBackendRejectsDirectorySwappedAfterFinalChecks() {
        val source = File(checkNotNull(directory), "raced-directory.txt")
        val store = SharedFileStore({ listOf(checkNotNull(directory)) }, Environment::isExternalStorageManager,
            unlink = { path ->
                // Deterministic accepted-race window: Android unlink must not remove a directory.
                Files.delete(path)
                Files.createDirectory(path)
                AndroidFileUnlink.unlink(path)
            })
        val approved = store.save(source.path, "approved".byteInputStream(), 1024) {}
        try { store.delete(source.path, approved.sha256!!) {}; fail("Android unlink must reject a directory") }
        catch (failure: FileAccessFailure) {
            assertEquals("file_delete_failed_check_path", failure.code)
            assertEquals(source.path, failure.affectedPath)
        }
        assertTrue(Files.isDirectory(source.toPath(), NOFOLLOW_LINKS))
        assertTrue(source.listFiles()!!.isEmpty())
        assertTrue(source.delete()) // exact synthetic, empty fixture directory only
    }

    private fun privateFixtureDirectory(): File = privateDirectory ?: File(checkNotNull(context.getExternalFilesDir(null)),
        "hans-file-test-${UUID.randomUUID()}").also { assertTrue(it.mkdir()); privateDirectory = it }
}
