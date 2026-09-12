package ai.hans.standard.runtime.python.resolver

import ai.hans.standard.runtime.python.PythonEnvironmentContract
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PythonResolverFileCacheTest {
    @Test
    fun survivesNewInstanceAndRejectsCorruptEntry() {
        val root = kotlin.io.path.createTempDirectory("resolver-cache").toFile()
        val body = "metadata".toByteArray()
        val resource = PythonCachedHttpResource(
            "https://pypi.org/simple/demo/",
            "\"etag\"",
            body,
            PythonEnvironmentContract.sha256(body),
        )
        FilePythonResolverHttpCache(root).put(resource)
        val restored = FilePythonResolverHttpCache(root).get(resource.url)!!
        assertEquals(resource.etag, restored.etag)
        assertTrue(body.contentEquals(restored.body))

        root.listFiles()!!.single().writeText("corrupt")
        assertNull(FilePythonResolverHttpCache(root).get(resource.url))
    }

    @Test
    fun symlinkEntryIsNeverRead() {
        val root = kotlin.io.path.createTempDirectory("resolver-cache-link").toFile()
        val outside = kotlin.io.path.createTempFile("outside-cache").toFile().apply {
            writeText("secret")
        }
        val url = "https://pypi.org/simple/demo/"
        val target = root.resolve("${PythonEnvironmentContract.sha256(url)}.cache").toPath()
        runCatching { Files.createSymbolicLink(target, outside.toPath()) }.getOrElse { return }
        assertNull(FilePythonResolverHttpCache(root).get(url))
    }
}

