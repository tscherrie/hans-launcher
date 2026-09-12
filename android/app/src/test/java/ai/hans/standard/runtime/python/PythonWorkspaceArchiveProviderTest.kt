package ai.hans.standard.runtime.python

import ai.hans.standard.workspace.PrivateWorkspaceStore
import ai.hans.standard.workspace.WorkspaceHandle
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipFile
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PythonWorkspaceArchiveProviderTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun committedWorkspaceBecomesBoundedDescriptorArchiveWithoutHostPaths() {
        val boundary = temporary.newFolder("private")
        val workspaceRoot = boundary.resolve("workspaces")
        val store = PrivateWorkspaceStore(workspaceRoot, boundary)
        val snapshot = store.begin().use { transaction ->
            transaction.write("README.md") { it.write("Hans".toByteArray()) }
            transaction.write("src/main.py") { it.write("VALUE = 42\n".toByteArray()) }
            transaction.commit()
        }
        val provider = PrivateWorkspacePythonArchiveProvider(
            store,
            boundary.resolve("python/workspaces"),
            boundary,
        )

        val receipt = provider.open(snapshot.handle.value)

        assertEquals(snapshot.handle.value, receipt.workspaceHandle)
        assertEquals(2, receipt.fileCount)
        assertEquals(snapshot.manifest.byteCount, receipt.contentBytes)
        assertTrue(receipt.sizeBytes in PythonRuntimeFdContract.MIN_WORKSPACE_ARCHIVE_BYTES..
            PythonRuntimeFdContract.MAX_WORKSPACE_ARCHIVE_BYTES)
        ZipFile(receipt.archive).use { archive ->
            val names = archive.entries().asSequence().map { it.name }.toList()
            assertEquals(
                listOf(
                    PythonRuntimeFdContract.WORKSPACE_MANIFEST_MEMBER,
                    PythonRuntimeFdContract.WORKSPACE_FILE_PREFIX + "README.md",
                    PythonRuntimeFdContract.WORKSPACE_FILE_PREFIX + "src/main.py",
                ),
                names,
            )
            val manifest = JSONObject(
                archive.getInputStream(archive.getEntry(PythonRuntimeFdContract.WORKSPACE_MANIFEST_MEMBER))
                    .readBytes()
                    .toString(StandardCharsets.UTF_8),
            )
            assertEquals(snapshot.handle.value, manifest.getString("workspaceHandle"))
            assertArrayEquals(
                "VALUE = 42\n".toByteArray(),
                archive.getInputStream(
                    archive.getEntry(PythonRuntimeFdContract.WORKSPACE_FILE_PREFIX + "src/main.py"),
                ).readBytes(),
            )
            assertFalse(receipt.archive.absolutePath in manifest.toString())
            assertFalse(boundary.absolutePath in manifest.toString())
        }
    }

    @Test
    fun unknownAndStaleHandlesFailBeforeAnyDescriptorCanBeIssued() {
        val boundary = temporary.newFolder("stale-private")
        val workspaceRoot = boundary.resolve("workspaces")
        val store = PrivateWorkspaceStore(workspaceRoot, boundary)
        val snapshot = store.begin().use { transaction ->
            transaction.write("value.txt") { it.write("value".toByteArray()) }
            transaction.commit()
        }
        val provider = PrivateWorkspacePythonArchiveProvider(
            store,
            boundary.resolve("python/workspaces"),
            boundary,
        )

        assertThrows(IllegalArgumentException::class.java) {
            provider.open("not-a-handle")
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.open("0".repeat(64))
        }

        val snapshotDirectory = workspaceRoot.resolve(snapshot.handle.value)
        snapshotDirectory.resolve("value.txt").delete()
        assertThrows(IllegalStateException::class.java) {
            provider.open(snapshot.handle.value)
        }
    }

    @Test
    fun replacedArchiveRootAndUnsafeWorkspacePathsFailClosed() {
        val boundary = temporary.newFolder("escape-private")
        val store = PrivateWorkspaceStore(boundary.resolve("workspaces"), boundary)
        val transaction = store.begin()
        assertThrows(IllegalArgumentException::class.java) {
            transaction.write("../escape.py") { it.write(1) }
        }
        transaction.close()

        val snapshot = store.begin().use { current ->
            current.write("safe.py") { it.write("VALUE = 1\n".toByteArray()) }
            current.commit()
        }
        val archiveRoot = boundary.resolve("python/workspaces")
        val provider = PrivateWorkspacePythonArchiveProvider(store, archiveRoot, boundary)
        assertTrue(archiveRoot.delete())
        val outside = temporary.newFolder("outside-archives")
        Files.createSymbolicLink(archiveRoot.toPath(), outside.toPath())

        assertThrows(IllegalArgumentException::class.java) {
            provider.open(snapshot.handle.value)
        }
        assertFalse(outside.listFiles().orEmpty().isNotEmpty())
    }
}
