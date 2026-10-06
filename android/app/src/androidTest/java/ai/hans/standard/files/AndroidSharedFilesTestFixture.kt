package ai.hans.standard.files

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*

/** Test-only durable bridge across the process restart caused by a real storage AppOp revocation. */
internal object AndroidSharedFilesTestFixture {
    private const val MARKER = "hans-shared-files-revocation-test.json"
    private const val CONTENT = "granted-public-fixture"
    private const val RETAINED_CONTENT = "granted-recovery-fixture"
    private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    data class Receipt(val id: String, val directory: File, val path: String, val uri: Uri,
        val recovery: SharedFileRecoveryInfo)

    fun requireOwnedEmulator() {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("getprop ro.kernel.qemu")
        val qemu = ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText().trim() }
        assertTrue("Shared-file fixtures must never run on a personal device",
            Build.HARDWARE in setOf("ranchu", "goldfish") && qemu == "1")
    }

    fun prepare(context: Context): Receipt {
        requireOwnedEmulator()
        assertTrue("External runner must grant storage before this process starts", Environment.isExternalStorageManager())
        val marker = marker(context)
        assertFalse("Never overwrite a pre-existing revocation fixture", marker.baseFile.exists())
        val id = UUID.randomUUID().toString()
        val directory = publicDirectory(id)
        assertTrue(directory.mkdir())
        val store = AndroidSharedFiles.store(context)
        val path = File(directory, "public.txt").path
        val info = store.save(path, CONTENT.byteInputStream(), 1024) {}
        val link = AndroidFileLinks(context).issue(info).first
        val token = checkNotNull(Uri.parse(link).lastPathSegment)
        val uri = Uri.parse("content://${context.packageName}.files/$token")
        assertEquals(CONTENT, context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
        // Public-to-private removal is unsupported on primary Android FUSE. Seed an exact synthetic
        // capture entirely inside Hans' private external-files filesystem to test restore revocation.
        val source = privateSeed(context, id)
        val privateRoot = checkNotNull(context.getExternalFilesDir(null))
        val recoveryRoot = AndroidSharedFiles.recoveryRoots(context).single { it.privateDirectory.parentFile == privateRoot }
        val privateStore = SharedFileStore({ listOf(privateRoot) }, { true }, recoveryRoots = {
            listOf(SharedFileRecoveryRoot(privateRoot, recoveryRoot.privateDirectory))
        })
        val approved = privateStore.save(source.path, RETAINED_CONTENT.byteInputStream(), 1024) {}
        val recovery = retainLegacy(context, File(approved.path))
        assertEquals(RETAINED_CONTENT, File(recovery.recoveryPath).readText())
        val json = JSONObject().put("version", 1).put("id", id).put("path", path)
            .put("uri", uri.toString()).put("recoveryHandle", recovery.handle)
            .put("recoveryPath", recovery.recoveryPath).put("recoveryOriginal", recovery.originalPath)
            .put("recoveryBytes", recovery.bytes)
        val stream = marker.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            marker.finishWrite(stream)
        } catch (failure: Throwable) {
            marker.failWrite(stream)
            throw failure
        }
        return read(context).also { assertEquals(path, it.path); assertEquals(uri, it.uri) }
    }

    fun read(context: Context): Receipt {
        val json = marker(context).openRead().use { input ->
            val bytes = ByteArray(8193)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            check(count <= 8192) { "Oversized test fixture receipt" }
            JSONObject(String(bytes, 0, count, Charsets.UTF_8))
        }
        check(json.getInt("version") == 1)
        val id = json.getString("id").also { check(UUID_PATTERN.matches(it)) }
        val directory = publicDirectory(id)
        val path = json.getString("path").also { check(it == File(directory, "public.txt").path) }
        val uri = Uri.parse(json.getString("uri")).also {
            check(it.scheme == "content" && it.authority == context.packageName + ".files" &&
                it.pathSegments.size == 1 && Regex("[0-9a-f]{32}").matches(it.pathSegments.single()) &&
                it.query == null && it.fragment == null)
        }
        val handle = json.getString("recoveryHandle").also { check(UUID_PATTERN.matches(it)) }
        val recoveryPath = json.getString("recoveryPath")
        check(AndroidSharedFiles.recoveryRoots(context).any {
            recoveryPath == File(File(it.privateDirectory, handle), "entry").path
        })
        val original = json.getString("recoveryOriginal").also { check(it == privateSeed(context, id).path) }
        val bytes = json.getLong("recoveryBytes").also { check(it == RETAINED_CONTENT.toByteArray().size.toLong()) }
        return Receipt(id, directory, path, uri, SharedFileRecoveryInfo(handle, original, recoveryPath, bytes))
    }

    fun cleanup(context: Context) {
        requireOwnedEmulator()
        assertTrue("External runner must regrant before exact fixture cleanup", Environment.isExternalStorageManager())
        val receipt = read(context)
        // No recursive deletion or wildcard recovery cleanup: these exact synthetic files belong to this run.
        val public = File(receipt.path)
        assertTrue(Files.isDirectory(receipt.directory.toPath(), NOFOLLOW_LINKS))
        assertTrue(Files.isRegularFile(public.toPath(), NOFOLLOW_LINKS))
        assertEquals(CONTENT, public.readText())
        assertEquals(setOf(public.name), receipt.directory.listFiles()!!.map { it.name }.toSet())
        val retained = File(receipt.recovery.recoveryPath)
        val capture = checkNotNull(retained.parentFile)
        assertTrue(Files.isDirectory(capture.toPath(), NOFOLLOW_LINKS))
        assertTrue(Files.isRegularFile(retained.toPath(), NOFOLLOW_LINKS))
        assertEquals(RETAINED_CONTENT, retained.readText())
        assertEquals(setOf("entry", "receipt"), capture.listFiles()!!.map { it.name }.toSet())
        assertTrue(Files.isRegularFile(File(capture, "receipt").toPath(), NOFOLLOW_LINKS))
        assertTrue(public.delete())
        assertTrue(receipt.directory.delete())
        assertTrue(retained.delete())
        assertTrue(File(capture, "receipt").delete())
        assertTrue(capture.delete())
        context.getSharedPreferences(AndroidFileLinks.PREFERENCES, Context.MODE_PRIVATE).edit()
            .remove("p_${receipt.uri.lastPathSegment}").remove("t_${receipt.uri.lastPathSegment}").commit().also { assertTrue(it) }
        marker(context).delete()
        assertFalse(marker(context).baseFile.exists())
    }

    fun retainLegacy(context: Context, source: File): SharedFileRecoveryInfo {
        requireOwnedEmulator()
        val privateFiles = checkNotNull(context.getExternalFilesDir(null))
        check(source.toPath().startsWith(privateFiles.toPath()))
        val recoveryRoot = AndroidSharedFiles.recoveryRoots(context).single { it.privateDirectory.parentFile == privateFiles }
        val reservation = SharedFileRecovery {
            listOf(SharedFileRecoveryRoot(checkNotNull(source.parentFile), recoveryRoot.privateDirectory))
        }.reserve(source)
        Files.move(source.toPath(), reservation.entry, ATOMIC_MOVE)
        return reservation.info()
    }

    private fun marker(context: Context) = AtomicFile(File(context.filesDir, MARKER))
    private fun publicDirectory(id: String) = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "hans-revocation-test-$id")
    private fun privateSeed(context: Context, id: String) = File(checkNotNull(context.getExternalFilesDir(null)),
        "hans-revocation-test-source-$id.txt")
}
