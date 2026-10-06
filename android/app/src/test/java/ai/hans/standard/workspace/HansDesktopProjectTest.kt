package ai.hans.standard.workspace

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HansDesktopProjectTest {
    @Test
    fun firstPreparationUsesHistoricalWorkspaceAndPrivateDirectoryWithoutExecutables() = withRoot { root ->
        assertNull(HansDesktopProject.preparedPath(root))
        assertTrue(root.listFiles()!!.isEmpty())

        val project = HansDesktopProject.ensure(root)

        assertEquals(File(root.canonicalFile, "codex-workspace"), project)
        assertEquals(project.path, HansDesktopProject.preparedPath(root))
        assertTrue(project.isDirectory)
        assertEquals(
            PosixFilePermissions.fromString("rwx------"),
            Files.getPosixFilePermissions(project.toPath()),
        )
        assertTrue(project.listFiles()!!.isEmpty())
        assertEquals(setOf("codex-workspace"), root.listFiles()!!.map(File::getName).toSet())
    }

    @Test
    fun existingProjectAndSiblingAccountDataAreNeverRewritten() = withRoot { root ->
        val project = File(root, HansDesktopProject.DIRECTORY_NAME).apply { mkdir() }
        val document = File(project, "user-note.txt").apply { writeText("keep user content") }
        val account = File(root, "synthetic-account.json").apply { writeText("keep synthetic account") }
        val initialMode = Files.getPosixFilePermissions(project.toPath())
        val initialModified = Files.getLastModifiedTime(document.toPath())

        repeat(2) { assertEquals(project.canonicalFile, HansDesktopProject.ensure(root)) }

        assertEquals("keep user content", document.readText())
        assertEquals("keep synthetic account", account.readText())
        assertEquals(initialMode, Files.getPosixFilePermissions(project.toPath()))
        assertEquals(initialModified, Files.getLastModifiedTime(document.toPath()))
    }

    @Test
    fun symlinkCannotBecomeTheDesktopProjectOrRewriteItsTarget() = withRoot { root ->
        val target = File(root, "unrelated").apply { mkdir() }
        val note = File(target, "keep.txt").apply { writeText("unchanged") }
        Files.createSymbolicLink(File(root, HansDesktopProject.DIRECTORY_NAME).toPath(), target.toPath())

        assertThrows(IllegalStateException::class.java) { HansDesktopProject.ensure(root) }
        assertNull(HansDesktopProject.preparedPath(root))
        assertEquals("unchanged", note.readText())
    }

    @Test
    fun fileCollisionAndMissingParentFailWithoutOverwritingOrCreatingParents() = withRoot { root ->
        val collision = File(root, HansDesktopProject.DIRECTORY_NAME).apply { writeText("keep collision") }
        assertThrows(IllegalStateException::class.java) { HansDesktopProject.ensure(root) }
        assertEquals("keep collision", collision.readText())
        assertNull(HansDesktopProject.preparedPath(root))

        val missingParent = File(root, "missing")
        assertThrows(IllegalStateException::class.java) { HansDesktopProject.ensure(missingParent) }
        assertFalse(missingParent.exists())
        assertNull(HansDesktopProject.preparedPath(missingParent))
        assertThrows(IllegalArgumentException::class.java) { HansDesktopProject.ensure(File("relative")) }
    }

    @Test
    fun availabilityIsFreshAndDoesNotRecreateADeletedProject() = withRoot { root ->
        val project = HansDesktopProject.ensure(root)
        assertTrue(project.delete())
        assertNull(HansDesktopProject.preparedPath(root))
        assertFalse(project.exists())
    }

    private fun withRoot(action: (File) -> Unit) {
        val root = Files.createTempDirectory("hans-desktop-project-test-").toFile()
        try {
            action(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
