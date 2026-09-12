package ai.hans.standard.plugins

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginSourceIdentityHasherTest {
    @Test
    fun digestIsDeterministicAndChangesWithContent() {
        val root = Files.createTempDirectory("plugin-source-hash").toFile()
        root.resolve("nested").mkdirs()
        root.resolve("a.txt").writeText("alpha")
        root.resolve("nested/b.txt").writeText("beta")
        val hasher = BoundedPluginSourceIdentityHasher()

        val first = hasher.digest(root, PluginSourceHashCancellation.NONE)
        val second = hasher.digest(root, PluginSourceHashCancellation.NONE)
        assertEquals(first, second)

        root.resolve("nested/b.txt").writeText("changed")
        assertNotEquals(first, hasher.digest(root, PluginSourceHashCancellation.NONE))
        root.deleteRecursively()
    }

    @Test
    fun cancellationInterruptsTraversal() {
        val root = Files.createTempDirectory("plugin-source-cancel").toFile()
        repeat(4) { root.resolve("$it.txt").writeText("content") }

        assertThrows(PluginRuntimePreparationCancelledException::class.java) {
            BoundedPluginSourceIdentityHasher().digest(
                root,
                PluginSourceHashCancellation { true },
            )
        }
        root.deleteRecursively()
    }

    @Test
    fun fileCountAndByteLimitsFailClosed() {
        val root = Files.createTempDirectory("plugin-source-limits").toFile()
        root.resolve("one.txt").writeText("1234")
        root.resolve("two.txt").writeText("5678")

        assertThrows(IllegalArgumentException::class.java) {
            BoundedPluginSourceIdentityHasher(
                maximumFiles = 1,
                maximumBytes = 16,
                maximumFileBytes = 8,
                maximumRelativePathBytes = 32,
            ).digest(root, PluginSourceHashCancellation.NONE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoundedPluginSourceIdentityHasher(
                maximumFiles = 4,
                maximumBytes = 4,
                maximumFileBytes = 4,
                maximumRelativePathBytes = 32,
            ).digest(root, PluginSourceHashCancellation.NONE)
        }
        root.deleteRecursively()
    }

    @Test
    fun symlinkIsRejected() {
        val root = Files.createTempDirectory("plugin-source-link").toFile()
        val target = root.resolve("target.txt").apply { writeText("target") }
        val link = root.resolve("link.txt").toPath()
        runCatching { Files.createSymbolicLink(link, target.toPath()) }.getOrElse {
            root.deleteRecursively()
            return
        }

        assertThrows(IllegalArgumentException::class.java) {
            BoundedPluginSourceIdentityHasher().digest(root, PluginSourceHashCancellation.NONE)
        }
        Files.deleteIfExists(link)
        root.deleteRecursively()
    }
}
