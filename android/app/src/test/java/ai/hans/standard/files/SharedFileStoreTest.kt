package ai.hans.standard.files

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SharedFileStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var root: File
    private lateinit var recoveryRoot: File
    private lateinit var store: SharedFileStore
    private var granted = true
    private var changed = 0

    @Before fun prepare() {
        root = temp.newFolder("public").canonicalFile
        recoveryRoot = temp.newFolder("private-recovery").canonicalFile
        store = SharedFileStore({ listOf(root) }, { granted }, { mapOf("/sdcard" to root) }, { changed++ },
            { listOf(SharedFileRecoveryRoot(root, recoveryRoot)) })
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
    @Test fun overflowRetainsPartialPathAndNeverDeletesNeighbours() {
        save("keep.txt")
        failure("file_byte_limit_exceeded") { store.save(path("too-big"), ByteArrayInputStream(ByteArray(101)), 100) {} }
        assertTrue(File(path("too-big")).exists())
        assertTrue(File(path("keep.txt")).exists())
    }
    @Test fun failedStreamRetainsTargetWithExplicitAffectedPath() {
        val input = object : InputStream() { override fun read(): Int = throw IOException("broken") }
        try { store.save(path("broken"), input, 100) {}; fail() } catch (failure: FileAccessFailure) {
            assertEquals("file_write_failed_partial_retained", failure.code)
            assertEquals(path("broken"), failure.affectedPath)
        }
        assertTrue(File(path("broken")).exists())
    }
    @Test fun cancellationRetainsPartialWriteRatherThanUnlinkingAPath() {
        var checkpoints = 0
        failure("file_operation_cancelled") {
            store.save(path("cancelled"), ByteArrayInputStream(ByteArray(200_000)), 300_000) {
                if (++checkpoints == 3) throw FileAccessFailure("file_operation_cancelled")
            }
        }
        assertTrue(File(path("cancelled")).exists())
        assertEquals(65_536L, File(path("cancelled")).length())
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
        assertTrue(store.listRecovery().isEmpty())
    }
    @Test fun deleteRequiresExactFreshDigestAndDoesNotTouchNeighbours() {
        val receipt = save("delete.txt")
        save("keep.txt")
        failure("file_changed_read_stat_first") { store.delete(receipt.path, "0".repeat(64)) {} }
        assertTrue(File(receipt.path).exists())
        store.delete(receipt.path, receipt.sha256!!) {}
        assertFalse(File(receipt.path).exists())
        assertTrue(File(path("keep.txt")).exists())
        assertTrue(store.listRecovery().isEmpty())
    }
    @Test fun directoryDeletionAndImplicitParentCreationAreNotAllowed() {
        store.mkdir(path("folder"))
        failure("file_changed_read_stat_first") { store.delete(path("folder"), "0".repeat(64)) {} }
        failure("parent_directory_missing") { save("missing/file.txt") }
    }

    private fun afterPublicHash(action: () -> Unit): () -> Unit {
        var done = false
        return {
            val trace = Thread.currentThread().stackTrace
            val deleting = trace.any { it.className == SharedFileStore::class.java.name && it.methodName.startsWith("delete") }
            val hashing = trace.any { it.className == SharedFileStore::class.java.name && it.methodName in setOf("digest", "digestInput", "digestCaptured") }
            if (!done && deleting && !hashing) { done = true; action() }
        }
    }

    @Test fun replacementSeenBeforeFinalChecksIsRejectedWithoutRecovery() {
        val original = save("replace.txt", "approved")
        val replacement = "unapproved replacement"
        failure("file_changed_read_stat_first") {
            store.delete(original.path, original.sha256!!, afterPublicHash {
                Files.delete(File(original.path).toPath())
                File(original.path).writeText(replacement)
            })
        }
        assertEquals(replacement, File(original.path).readText())
        assertTrue(store.listRecovery().isEmpty())
    }

    @Test fun inPlaceWriterAfterApprovedHashIsPreservedRatherThanUnlinked() {
        val original = save("edit.txt", "approved")
        failure("file_changed_read_stat_first") {
            store.delete(original.path, original.sha256!!, afterPublicHash { File(original.path).writeText("edited") })
        }
        assertEquals("edited", File(original.path).readText())
        assertTrue(store.listRecovery().isEmpty())
    }

    @Test fun ordinaryDeletionDoesNotPromiseRecoveryOrImmediateDescriptorStorageRelease() {
        val original = save("writer.txt", "approved")
        java.io.FileOutputStream(original.path, true).use { writer ->
            val receipt = store.delete(original.path, original.sha256!!) {}
            writer.write("-later".toByteArray())
            writer.flush()
            assertEquals(original.path, receipt.path)
            assertEquals(original.sha256, receipt.sha256)
            assertFalse(File(original.path).exists())
            assertTrue(store.listRecovery().isEmpty())
        }
    }

    @Test fun replacementCreatedAfterDeleteProducesUnconfirmedReceiptWithoutTouchingNewPath() {
        val original = save("new-original.txt", "approved")
        var replaced = false
        failure("file_removal_unconfirmed_path_present") {
            store.delete(original.path, original.sha256!!) {
                if (!replaced && !File(original.path).exists()) {
                    replaced = true
                    File(original.path).writeText("new public file")
                }
            }
        }
        assertTrue(replaced)
        assertEquals("new public file", File(original.path).readText())
        assertTrue(store.listRecovery().isEmpty())
    }

    @Test fun cancellationAfterDeleteReportsAffectedPathAndDoesNotInventRecovery() {
        val original = save("cancel-capture.txt", "approved")
        try {
            store.delete(original.path, original.sha256!!) {
                if (!File(original.path).exists()) throw FileAccessFailure("file_operation_cancelled")
            }
            fail()
        } catch (failure: FileAccessFailure) {
            assertEquals("file_operation_cancelled", failure.code)
            assertEquals(original.path, failure.affectedPath)
            assertNull(failure.recovery)
        }
        assertFalse(File(original.path).exists())
        assertTrue(store.listRecovery().isEmpty())
    }

    @Test fun revocationAfterDeleteCannotClaimSuccessOrRecovery() {
        val original = save("revoke-capture.txt", "approved")
        try {
            store.delete(original.path, original.sha256!!) { if (!File(original.path).exists()) granted = false }
            fail()
        } catch (failure: FileAccessFailure) {
            assertEquals("all_files_access_required", failure.code)
            assertEquals(original.path, failure.affectedPath)
            assertNull(failure.recovery)
        }
        assertFalse(File(original.path).exists())
        failure("all_files_access_required") { store.listRecovery() }
        assertTrue(recoveryRoot.listFiles()!!.isEmpty())
    }

    @Test fun failedSaveNeverUnlinksAReplacementAtItsDestination() {
        val target = path("failed-save.txt")
        val input = object : InputStream() {
            override fun read(): Int {
                Files.delete(File(target).toPath())
                File(target).writeText("foreign replacement")
                throw IOException("stream failed after external replacement")
            }
        }
        failure("file_write_failed_partial_retained") { store.save(target, input, 100) {} }
        assertEquals("foreign replacement", File(target).readText())
    }

    @Test fun moveReplacementRaceRetainsVerifiedDestinationAndChangedSource() {
        val original = save("move-race.txt", "approved")
        failure("file_changed_read_stat_first") {
            store.copy(original.path, path("move-target.txt"), true, original.sha256,
                afterPublicHash { File(original.path).writeText("replacement") })
        }
        assertEquals("approved", File(path("move-target.txt")).readText())
        assertEquals("replacement", File(original.path).readText())
        assertTrue(store.listRecovery().isEmpty())
    }

    @Test fun restoreUsesOpaqueHandleNeverOverwritesAndRetainsRecovery() {
        val original = save("restore.txt", "approved")
        val retained = retainFixture(original)
        save("conflict.txt", "keep")
        try { store.restoreRecovery(retained.handle, path("conflict.txt")) {}; fail() }
        catch (_: java.nio.file.FileAlreadyExistsException) { }
        assertEquals("keep", File(path("conflict.txt")).readText())
        val restored = store.restoreRecovery(retained.handle, path("restored.txt")) {}
        assertEquals(original.sha256, restored.sha256)
        assertEquals("approved", File(retained.recoveryPath).readText())
        failure("invalid_file_recovery_handle") { store.restoreRecovery("../entry", path("invalid.txt")) {} }
    }

    @Test fun legacyRecoveryBudgetDoesNotBlockNewOrdinaryDeletes() {
        repeat(SharedFileRecovery.MAX_ENTRIES) {
            val original = save("recover-$it.txt", "approved-$it")
            retainFixture(original)
        }
        val kept = save("full.txt")
        store.delete(kept.path, kept.sha256!!) {}
        assertFalse(File(kept.path).exists())
        assertEquals(SharedFileRecovery.MAX_ENTRIES, store.listRecovery().size)
    }

    @Test fun ordinaryDeletionDoesNotRequireAnIsolatedRecoveryFilesystem() {
        val original = save("unsupported.txt")
        val unsupported = SharedFileStore({ listOf(root) }, { true })
        unsupported.delete(original.path, original.sha256!!) {}
        assertFalse(File(original.path).exists())
    }

    private fun limitedStore(limit: Long, opener: (Path) -> InputStream = { Files.newInputStream(it) }) =
        SharedFileStore({ listOf(root) }, { granted }, recoveryRoots = {
            listOf(SharedFileRecoveryRoot(root, recoveryRoot))
        }, maxBinaryBytes = limit, readInput = opener)

    private fun retainFixture(info: SharedFileInfo): SharedFileRecoveryInfo {
        // Exact synthetic legacy fixture; ordinary production delete no longer captures private recovery.
        val reservation = SharedFileRecovery { listOf(SharedFileRecoveryRoot(root, recoveryRoot)) }.reserve(File(info.path))
        Files.move(File(info.path).toPath(), reservation.entry, ATOMIC_MOVE)
        return reservation.info()
    }

    @Test fun hashByteLimitAllowsBelowAndExactBoundaryButRejectsOversizedBeforeOpening() {
        var opens = 0
        val limited = limitedStore(8) { opens++; Files.newInputStream(it) }
        File(path("below.bin")).writeBytes(ByteArray(7))
        File(path("exact.bin")).writeBytes(ByteArray(8))
        File(path("above.bin")).writeBytes(ByteArray(9))
        assertNotNull(limited.stat(path("below.bin"), hash = true).sha256)
        assertNotNull(limited.stat(path("exact.bin"), hash = true).sha256)
        assertEquals(2, opens)
        failure("file_byte_limit_exceeded") { limited.stat(path("above.bin"), hash = true) }
        assertEquals(2, opens)
    }

    @Test fun oversizedCopyMoveAndDeleteFailBeforeReadingOrChangingAnyPath() {
        var opens = 0
        val limited = limitedStore(8) { opens++; Files.newInputStream(it) }
        File(path("large.bin")).writeBytes(ByteArray(9))
        failure("file_byte_limit_exceeded") { limited.copy(path("large.bin"), path("copy.bin"), false, null) {} }
        failure("file_byte_limit_exceeded") { limited.copy(path("large.bin"), path("move.bin"), true, "0".repeat(64)) {} }
        failure("file_byte_limit_exceeded") { limited.delete(path("large.bin"), "0".repeat(64)) {} }
        assertEquals(0, opens)
        assertTrue(File(path("large.bin")).exists())
        assertFalse(File(path("copy.bin")).exists())
        assertFalse(File(path("move.bin")).exists())
        assertTrue(limited.listRecovery().isEmpty())
    }

    @Test fun growingFileStopsHashReadAtFirstExcessiveByte() {
        val source = File(path("growing.bin")).apply { writeBytes(ByteArray(3)) }
        var bytesRead = 0
        val requests = mutableListOf<Int>()
        val limited = limitedStore(8) { path ->
            object : FilterInputStream(Files.newInputStream(path)) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    requests.add(length)
                    return super.read(buffer, offset, length).also { if (it > 0) bytesRead += it }
                }
            }
        }
        var grew = false
        failure("file_byte_limit_exceeded") {
            limited.stat(source.path, hash = true) { if (!grew) { grew = true; source.appendBytes(ByteArray(1000)) } }
        }
        assertTrue(grew)
        assertEquals(9, bytesRead)
        assertEquals(listOf(9), requests)
    }

    @Test fun saveAlsoConsumesOnlyFirstExcessiveByteAndReportsPartialPath() {
        var bytesRead = 0
        val input = object : FilterInputStream(ByteArrayInputStream(ByteArray(1000))) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, length).also { if (it > 0) bytesRead += it }
        }
        try { limitedStore(8).save(path("too-big-save.bin"), input, 8) {}; fail() }
        catch (failure: FileAccessFailure) {
            assertEquals("file_byte_limit_exceeded", failure.code)
            assertEquals(path("too-big-save.bin"), failure.affectedPath)
        }
        assertEquals(9, bytesRead)
        assertTrue(File(path("too-big-save.bin")).exists())
    }

    @Test fun exactLimitCopyAndRecoveryHashAndRestoreRemainValid() {
        val limited = limitedStore(8)
        val original = limited.save(path("boundary-source.bin"), ByteArray(8).inputStream(), 8) {}
        val moved = limited.copy(original.path, path("boundary-move.bin"), true, original.sha256) {}
        assertEquals(original.sha256, moved.sha256)
        assertEquals(original.path, checkNotNull(moved.sourceDeletion).path)
        val recoverySource = limited.save(path("boundary-recovery.bin"), ByteArray(8).inputStream(), 8) {}
        val retained = retainFixture(recoverySource)
        val restored = limited.restoreRecovery(retained.handle, path("boundary-restore.bin")) {}
        assertEquals(original.sha256, restored.sha256)
        assertEquals(8L, File(retained.recoveryPath).length())
    }

    @Test fun aRetainedFileThatGrowsOverBudgetCannotStartAnUnboundedRestore() {
        var reads = 0
        val limited = limitedStore(8) { reads++; Files.newInputStream(it) }
        val original = limited.save(path("growth-recovery.bin"), ByteArray(8).inputStream(), 8) {}
        val retained = retainFixture(original)
        File(retained.recoveryPath).appendBytes(byteArrayOf(1))
        val before = reads
        failure("file_byte_limit_exceeded") { limited.restoreRecovery(retained.handle, path("denied-restore.bin")) {} }
        assertEquals(before, reads)
        assertFalse(File(path("denied-restore.bin")).exists())
        assertEquals(9L, File(retained.recoveryPath).length())
    }

    @Test fun directoryObservedAfterInitialHashIsRejectedWithoutRecursion() {
        val original = save("directory-race.txt", "approved")
        failure("file_changed_read_stat_first") {
            store.delete(original.path, original.sha256!!, afterPublicHash {
                Files.delete(File(original.path).toPath())
                File(original.path).mkdir()
                File(original.path, "child.txt").writeText("unapproved directory content")
            })
        }
        assertTrue(File(original.path).isDirectory)
        assertEquals("unapproved directory content", File(original.path, "child.txt").readText())
        assertTrue(store.listRecovery().isEmpty())
    }

    @Test fun symlinkRacedIntoSourceIsNotFollowedOrDiscarded() {
        val original = save("link-race.txt", "approved")
        val outside = temp.newFile("secret-outside.txt").apply { writeText("secret") }
        failure("file_changed_read_stat_first") {
            store.delete(original.path, original.sha256!!, afterPublicHash {
                Files.delete(File(original.path).toPath())
                Files.createSymbolicLink(File(original.path).toPath(), outside.toPath())
            })
        }
        assertTrue(Files.isSymbolicLink(File(original.path).toPath()))
        assertEquals("secret", outside.readText())
        assertTrue(store.listRecovery().isEmpty())
    }

    @Test fun ordinaryMoveRequiresNoRecoveryRootOrFilesystemIdentityProbe() {
        val original = save("unsupported-removal.txt", "approved")
        val ordinary = SharedFileStore({ listOf(root) }, { granted })
        val moved = ordinary.copy(original.path, path("ordinary-move.txt"), true, original.sha256) {}
        assertFalse(File(original.path).exists())
        assertEquals("approved", File(moved.path).readText())
        assertEquals(original.path, moved.sourceDeletion!!.path)
        assertTrue(ordinary.listRecovery().isEmpty())
        assertTrue(recoveryRoot.listFiles()!!.isEmpty())
    }

    @Test fun cancellationBeforeOrdinaryDeleteLeavesSourceIntact() {
        val original = save("cancel-delete.txt", "approved")
        failure("file_operation_cancelled") { store.delete(original.path, original.sha256!!) { throw FileAccessFailure("file_operation_cancelled") } }
        assertEquals("approved", File(original.path).readText())
        assertTrue(store.listRecovery().isEmpty())
    }

    @Test fun revocationFreshlyBlocksLegacyRecoveryMetadataAndRestore() {
        val original = save("revoked-recovery.txt", "approved")
        val retained = retainFixture(original)
        granted = false
        failure("all_files_access_required") { store.listRecovery() }
        failure("all_files_access_required") { store.restoreRecovery(retained.handle, path("must-not-restore.txt")) {} }
        assertEquals("approved", File(retained.recoveryPath).readText())
        assertFalse(File(path("must-not-restore.txt")).exists())
    }

    @Test fun directoryRejectingUnlinkBackendIsNotReplacedByAnUnsafeFallback() {
        var calls = 0
        val directoryRejecting = SharedFileStore({ listOf(root) }, { granted }, unlink = { target ->
            calls++
            Files.delete(target)
            Files.createDirectory(target)
            throw IOException("unlink rejects directories")
        })
        val approved = directoryRejecting.save(path("backend-directory.txt"), "approved".byteInputStream(), 1024) {}
        failure("file_delete_failed_check_path") { directoryRejecting.delete(approved.path, approved.sha256!!) {} }
        assertEquals(1, calls)
        assertTrue(File(approved.path).isDirectory)
        assertTrue(File(approved.path).listFiles()!!.isEmpty())
    }

    @Test fun acceptedFinalPathReplacementGapCanDeleteTheReplacement() {
        // This explicitly demonstrates the owner's chosen residual risk, not a safety guarantee.
        var replaced = false
        val ordinary = SharedFileStore({ listOf(root) }, { granted }, unlink = { target ->
            Files.delete(target)
            target.toFile().writeText("unapproved final-gap replacement")
            replaced = true
            Files.delete(target)
        })
        val approved = ordinary.save(path("accepted-gap.txt"), "approved".byteInputStream(), 1024) {}
        val removed = ordinary.delete(approved.path, approved.sha256!!) {}
        assertTrue(replaced)
        assertEquals(approved.path, removed.path)
        assertFalse(File(approved.path).exists())
        assertTrue(ordinary.listRecovery().isEmpty())
    }

    @Test fun vanishedMoveSourceAfterVerifiedSaveStillReportsTheRetainedDestinationReceipt() {
        val source = path("vanishing-source.txt")
        val destination = path("verified-copy.txt")
        val interrupted = SharedFileStore({ listOf(root) }, { granted }, changed = { saved ->
            if (saved.path == destination) Files.delete(File(source).toPath())
        })
        val approved = interrupted.save(source, "approved".byteInputStream(), 1024) {}
        try { interrupted.copy(source, destination, true, approved.sha256) {}; fail() }
        catch (failure: FileAccessFailure) {
            assertEquals("source_removal_failed_destination_retained", failure.code)
            assertEquals(destination, failure.affectedPath)
            assertEquals(approved.sha256, checkNotNull(failure.retainedDestination).sha256)
            assertTrue(failure.cause is java.nio.file.NoSuchFileException)
        }
        assertEquals("approved", File(destination).readText())
    }
}
