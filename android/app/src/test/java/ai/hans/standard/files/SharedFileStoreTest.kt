package ai.hans.standard.files

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SharedFileStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var root: File
    private lateinit var store: SharedFileStore
    private var granted = true
    private var changed = 0

    @Before fun prepare() {
        root = temp.newFolder("public").canonicalFile
        store = SharedFileStore({ listOf(root) }, { granted }, { mapOf("/sdcard" to root) }, { changed++ })
    }
    private fun path(name: String) = File(root, name).path
    private fun save(name: String, value: String = "hello") = store.save(path(name), value.byteInputStream(), 1024) {}
    private fun failure(code: String, operation: () -> Unit) {
        try { operation(); fail("Expected $code") } catch (error: FileAccessFailure) { assertEquals(code, error.code) }
    }

    @Test fun savesRealPublicFileAndVerifiesContents() {
        val receipt = save("voice.wav")
        assertEquals(path("voice.wav"), receipt.path)
        assertEquals("hello", File(receipt.path).readText())
        assertEquals(5L, receipt.bytes)
        assertEquals(64, receipt.sha256!!.length)
        assertEquals(1, changed)
    }
    @Test fun grantIsFreshAndRevocationStopsReadsWritesAndDeletes() {
        val receipt = save("kept.txt")
        granted = false
        assertFalse(store.granted())
        failure("all_files_access_required") { store.readText(receipt.path, 100) }
        failure("all_files_access_required") { save("denied.txt") }
        failure("all_files_access_required") { store.delete(receipt.path, receipt.sha256!!) {} }
        assertTrue(File(receipt.path).exists())
        assertFalse(File(path("denied.txt")).exists())
    }
    @Test fun existingDestinationIsNeverOverwritten() {
        save("keep.txt", "original")
        try { save("keep.txt", "replacement"); fail() } catch (_: java.nio.file.FileAlreadyExistsException) { }
        assertEquals("original", File(path("keep.txt")).readText())
    }
    @Test fun overflowRollsBackOnlyNewTarget() {
        save("keep.txt")
        failure("file_byte_limit_exceeded") { store.save(path("too-big"), ByteArrayInputStream(ByteArray(101)), 100) {} }
        assertFalse(File(path("too-big")).exists())
        assertTrue(File(path("keep.txt")).exists())
    }
    @Test fun failedStreamRollsBackNewTarget() {
        val input = object : InputStream() { override fun read(): Int = throw IOException("broken") }
        try { store.save(path("broken"), input, 100) {}; fail() } catch (_: IOException) { }
        assertFalse(File(path("broken")).exists())
    }
    @Test fun cancellationRollsBackPartialWrite() {
        var checkpoints = 0
        failure("file_operation_cancelled") {
            store.save(path("cancelled"), ByteArrayInputStream(ByteArray(200_000)), 300_000) {
                if (++checkpoints == 3) throw FileAccessFailure("file_operation_cancelled")
            }
        }
        assertFalse(File(path("cancelled")).exists())
    }
    @Test fun revocationDuringWriteCannotReturnSuccess() {
        failure("all_files_access_required") {
            store.save(path("revoked"), "hello".byteInputStream(), 100) { granted = false }
        }
        assertFalse(File(path("revoked")).exists())
    }
    @Test fun rejectsPrivatePathsAndTraversalOutsideVolume() {
        failure("outside_shared_storage") { store.resolve("/data/user/0/another.app/secrets") }
        failure("outside_shared_storage") { store.resolve(path("../outside")) }
        failure("absolute_path_required") { store.resolve("Download/voice.wav") }
    }
    @Test fun deniesProtectedAndroidFoldersButAllowsMedia() {
        failure("android_private_storage_protected") { store.resolve(path("Android/data/other/file")) }
        failure("android_private_storage_protected") { store.resolve(path("Android/obb/other/file")) }
        assertEquals(path("Android/media/music.wav"), store.resolve(path("Android/media/music.wav")).path)
    }
    @Test fun aliasesResolveToActualVolumeAndRootsCannotBeDeleted() {
        assertEquals(path("Download/a.wav"), store.resolve("/sdcard/Download/a.wav").path)
        failure("storage_root_protected") { store.delete(root.path, "0".repeat(64)) {} }
    }
    @Test fun symlinkCannotReadOrOverwriteExternalFile() {
        val outside = temp.newFile("outside.txt").apply { writeText("secret") }
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        failure("symbolic_link_not_supported") { store.open(path("link")) }
        failure("symbolic_link_not_supported") { save("link", "changed") }
        assertEquals("secret", outside.readText())
    }
    @Test fun boundedListingAndSearchExposeTruncation() {
        repeat(4) { save("tone-$it.wav") }
        val first = store.list(root.path, 0, 2)
        assertEquals(2, first.first.size)
        assertTrue(first.second)
        assertFalse(store.list(root.path, 2, 2).second)
        val search = store.search(root.path, "tone", 2) {}
        assertEquals(2, search.first.size)
        assertTrue(search.second)
    }
    @Test fun textPrefixDoesNotBreakUtf8AndReportsTruncation() {
        save("text.txt", "1234😀end")
        assertEquals("1234" to true, store.readText(path("text.txt"), 6))
        assertEquals("1234😀end" to false, store.readText(path("text.txt"), 100))
    }
    @Test fun binaryIsNotMisrepresentedAsText() {
        store.save(path("binary"), ByteArrayInputStream(byteArrayOf(-1, -2, 0, 0)), 100) {}
        failure("not_utf8_text_use_load") { store.readText(path("binary"), 100) }
    }
    @Test fun copyAndMoveVerifyAndPreserveDestinationConflicts() {
        val original = save("a.txt")
        val copied = store.copy(original.path, path("b.txt"), false, null) {}
        assertEquals(original.sha256, copied.sha256)
        assertTrue(File(original.path).exists())
        store.copy(original.path, path("c.txt"), true, original.sha256) {}
        assertFalse(File(original.path).exists())
        assertEquals("hello", File(path("c.txt")).readText())
    }
    @Test fun deleteRequiresExactFreshDigestAndDoesNotTouchNeighbours() {
        val receipt = save("delete.txt")
        save("keep.txt")
        failure("file_changed_read_stat_first") { store.delete(receipt.path, "0".repeat(64)) {} }
        assertTrue(File(receipt.path).exists())
        store.delete(receipt.path, receipt.sha256!!) {}
        assertFalse(File(receipt.path).exists())
        assertTrue(File(path("keep.txt")).exists())
    }
    @Test fun directoryDeletionAndImplicitParentCreationAreNotAllowed() {
        store.mkdir(path("folder"))
        failure("file_changed_read_stat_first") { store.delete(path("folder"), "0".repeat(64)) {} }
        failure("parent_directory_missing") { save("missing/file.txt") }
    }
}
