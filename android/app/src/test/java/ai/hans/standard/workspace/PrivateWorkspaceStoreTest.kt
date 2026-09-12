package ai.hans.standard.workspace

import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrivateWorkspaceStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun explicitWorkbenchInventoryIsBoundedSortedAndVerified() {
        val boundary = temporary.newFolder("inventory-private")
        val store = PrivateWorkspaceStore(boundary.resolve("workspaces"), boundary)
        val first = store.begin().use { transaction ->
            transaction.write("b.txt") { it.write("b".toByteArray()) }
            transaction.commit()
        }
        val second = store.begin().use { transaction ->
            transaction.write("a.txt") { it.write("a".toByteArray()) }
            transaction.commit()
        }

        val inventory = store.listSnapshots(limit = 10)

        assertEquals(listOf(first.handle.value, second.handle.value).sorted(), inventory.map { it.handle.value })
        assertEquals(1, store.listSnapshots(offset = 1, limit = 1).size)
    }

    @Test
    fun commitIsContentAddressedAndIndependentOfWriteOrder() {
        val boundary = temporary.newFolder("private")
        val store = PrivateWorkspaceStore(boundary.resolve("workspaces"), boundary)

        val first = store.begin().use { transaction ->
            transaction.write("src/main.py") { it.write("print('hello')".toByteArray()) }
            transaction.write("README.md") { it.write("Hans".toByteArray()) }
            transaction.commit()
        }
        val second = store.begin().use { transaction ->
            transaction.write("README.md") { it.write("Hans".toByteArray()) }
            transaction.write("src/main.py") { it.write("print('hello')".toByteArray()) }
            transaction.commit()
        }

        assertEquals(first.handle, second.handle)
        assertEquals(listOf("README.md", "src/main.py"), first.manifest.files.map { it.relativePath })
        assertEquals("Hans", store.openFile(first.handle, "README.md").bufferedReader().use { it.readText() })
    }

    @Test
    fun abandonedAndFailedTransactionsNeverBecomeSnapshots() {
        val boundary = temporary.newFolder("private")
        val root = boundary.resolve("workspaces")
        val store = PrivateWorkspaceStore(
            root,
            boundary,
            WorkspaceQuotas(maxFiles = 2, maxSingleFileBytes = 4, maxTotalBytes = 8),
        )

        store.begin().use { transaction ->
            transaction.write("draft.txt") { it.write("ok".toByteArray()) }
        }
        assertTrue(root.listFiles().orEmpty().isEmpty())

        val transaction = store.begin()
        assertThrows(IllegalArgumentException::class.java) {
            transaction.write("too-large.txt") { it.write("12345".toByteArray()) }
        }
        transaction.close()
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun traversalAbsolutePathsAndSymlinksAreRejected() {
        val boundary = temporary.newFolder("private")
        val store = PrivateWorkspaceStore(boundary.resolve("workspaces"), boundary)
        val transaction = store.begin()

        listOf("../escape", "/absolute", "a//b", "a/./b", "a\\b").forEach { unsafe ->
            assertThrows(IllegalArgumentException::class.java) {
                transaction.write(unsafe) { it.write(1) }
            }
        }

        val outside = temporary.newFile("outside")
        val staging = boundary.resolve("workspaces").listFiles().orEmpty().single { it.name.startsWith(".stage-") }
        Files.createSymbolicLink(staging.resolve("link").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { transaction.commit() }
        transaction.close()
        assertFalse(staging.exists())
    }

    @Test
    fun seededEditProducesNewSnapshotWithoutMutatingTheBase() {
        val boundary = temporary.newFolder("private")
        val store = PrivateWorkspaceStore(boundary.resolve("workspaces"), boundary)
        val base = store.begin().use { transaction ->
            transaction.write("value.txt") { it.write("one".toByteArray()) }
            transaction.commit()
        }

        val edited = store.begin(base.handle).use { transaction ->
            assertTrue(transaction.delete("value.txt"))
            transaction.write("value.txt") { it.write("two".toByteArray()) }
            transaction.commit()
        }

        assertNotEquals(base.handle, edited.handle)
        assertEquals("one", store.openFile(base.handle, "value.txt").bufferedReader().use { it.readText() })
        assertEquals("two", store.openFile(edited.handle, "value.txt").bufferedReader().use { it.readText() })
    }

    @Test
    fun sourceCopyIsBoundedAndDoesNotCloseTheCallersStream() {
        val boundary = temporary.newFolder("private")
        val store = PrivateWorkspaceStore(boundary.resolve("workspaces"), boundary)
        val source = TrackingInputStream("data".toByteArray())

        val snapshot = store.begin().use { transaction ->
            transaction.copy("input.bin", source)
            transaction.commit()
        }

        assertFalse(source.closed)
        assertEquals(4L, snapshot.manifest.entry("input.bin")?.byteCount)
    }

    @Test
    fun externalImporterClosesEveryStreamAndRejectsNondeterministicSources() {
        val boundary = temporary.newFolder("private")
        val store = PrivateWorkspaceStore(boundary.resolve("workspaces"), boundary)
        val importer = WorkspaceExternalImporter(store)
        val first = TrackingInputStream("first".toByteArray())
        val second = TrackingInputStream("second".toByteArray())
        val source = object : WorkspaceExternalSource {
            override fun files() = listOf(
                WorkspaceSourceFile("a.txt", 5, 1) { first },
                WorkspaceSourceFile("nested/b.txt", 6, 2) { second },
            )
        }

        val snapshot = importer.import(source)

        assertTrue(first.closed)
        assertTrue(second.closed)
        assertEquals(listOf("a.txt", "nested/b.txt"), snapshot.manifest.files.map { it.relativePath })

        val unsorted = object : WorkspaceExternalSource {
            override fun files() = listOf(
                WorkspaceSourceFile("z.txt", 1, null) { ByteArrayInputStream(byteArrayOf(1)) },
                WorkspaceSourceFile("a.txt", 1, null) { ByteArrayInputStream(byteArrayOf(1)) },
            )
        }
        assertThrows(IllegalArgumentException::class.java) { importer.import(unsorted) }
    }

    @Test
    fun threeWayPlannerNeverSilentlyOverwritesConcurrentChanges() {
        val base = manifest("same.txt" to "a", "deleted.txt" to "d", "external.txt" to "e")
        val local = manifest("same.txt" to "local", "deleted.txt" to "d", "external.txt" to "e")
        val external = manifest("same.txt" to "remote", "deleted.txt" to "d2")

        val plan = WorkspaceSyncPlanner.plan(base, local, external)
        val actions = plan.actions.associate { it.relativePath to it.kind }

        assertEquals(WorkspaceSyncActionKind.CONFLICT, actions["same.txt"])
        assertEquals(WorkspaceSyncActionKind.APPLY_EXTERNAL, actions["deleted.txt"])
        assertEquals(WorkspaceSyncActionKind.DELETE_LOCAL, actions["external.txt"])
        assertFalse(plan.canApplyWithoutDataLoss)
    }

    @Test
    fun rootMustRemainInsidePrivateBoundary() {
        val boundary = temporary.newFolder("private")
        val outside = temporary.newFolder("outside")

        assertThrows(IllegalArgumentException::class.java) {
            PrivateWorkspaceStore(outside, boundary)
        }
    }

    private fun manifest(vararg entries: Pair<String, String>): WorkspaceManifest = WorkspaceManifest(
        entries.map { (path, content) ->
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
            WorkspaceFileEntry(path, content.toByteArray().size.toLong(), digest.toHex())
        }.sortedBy { it.relativePath },
    )

    private class TrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }
}
