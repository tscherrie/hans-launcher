package ai.hans.standard.artifacts

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

class AtomicArtifactStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun explicitWorkbenchInventoryIsBoundedAndReverifiesPayloads() {
        val boundary = temporary.newFolder("inventory-private")
        val store = AtomicArtifactStore(boundary.resolve("artifacts"), boundary)
        val first = store.put(
            "first.txt",
            "text/plain",
            ArtifactOrigin.ANDROID,
            ByteArrayInputStream("one".toByteArray()),
        )
        val second = store.put(
            "second.txt",
            "text/plain",
            ArtifactOrigin.ANDROID,
            ByteArrayInputStream("two".toByteArray()),
        )

        assertEquals(2, store.listMetadata(limit = 10).size)
        assertEquals(1, store.listMetadata(offset = 1, limit = 1).size)

        boundary.resolve("artifacts").resolve(first.handle.value).resolve("payload").writeText("tampered")
        assertThrows(IllegalStateException::class.java) { store.listMetadata(limit = 10) }
        assertEquals("two", store.open(second.handle).use { String(it.readBytes()) })
    }

    @Test
    fun largeResultRoundTripsByOpaqueHandleWithDurableMetadata() {
        val boundary = temporary.newFolder("private")
        var nonceCounter = 0
        val store = AtomicArtifactStore(
            requestedRoot = boundary.resolve("artifacts"),
            privateBoundary = boundary,
            nowEpochMillis = { 42L },
            nonce = { ByteArray(32) { (nonceCounter++).toByte() } },
        )
        val content = ByteArray(128 * 1024) { (it % 251).toByte() }

        val metadata = store.put(
            displayName = "result.bin",
            mimeType = "application/octet-stream",
            origin = ArtifactOrigin.PYTHON,
            source = ByteArrayInputStream(content),
        )

        assertEquals(42L, metadata.createdAtEpochMillis)
        assertEquals(content.size.toLong(), metadata.byteCount)
        assertEquals(metadata, store.metadata(metadata.handle))
        assertTrue(content.contentEquals(store.open(metadata.handle).use { it.readBytes() }))
        assertFalse(metadata.handle.value.contains("result"))
    }

    @Test
    fun identicalContentStillGetsUnforgeableDistinctHandles() {
        val boundary = temporary.newFolder("private")
        var counter = 1
        val store = AtomicArtifactStore(
            boundary.resolve("artifacts"),
            boundary,
            nonce = { ByteArray(32) { (counter++).toByte() } },
        )

        val first = store.put("a.txt", "text/plain", ArtifactOrigin.CODEX, ByteArrayInputStream("x".toByteArray()))
        val second = store.put("a.txt", "text/plain", ArtifactOrigin.CODEX, ByteArrayInputStream("x".toByteArray()))

        assertEquals(first.sha256, second.sha256)
        assertNotEquals(first.handle, second.handle)
    }

    @Test
    fun oversizedWriteRollsBackWithoutVisibleArtifact() {
        val boundary = temporary.newFolder("private")
        val root = boundary.resolve("artifacts")
        val store = AtomicArtifactStore(
            root,
            boundary,
            ArtifactQuotas(maxArtifactBytes = 4, maxStoreBytes = 8, maxArtifacts = 2),
            nonce = { ByteArray(32) { 1 } },
        )

        assertThrows(IllegalArgumentException::class.java) {
            store.put(
                "large.bin",
                "application/octet-stream",
                ArtifactOrigin.PYTHON,
                ByteArrayInputStream("12345".toByteArray()),
            )
        }

        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun tamperingIsDetectedBeforeReadLeaseIsReturned() {
        val boundary = temporary.newFolder("private")
        val store = AtomicArtifactStore(boundary.resolve("artifacts"), boundary)
        val metadata = store.put(
            "answer.txt",
            "text/plain",
            ArtifactOrigin.CODEX,
            ByteArrayInputStream("safe".toByteArray()),
        )
        val directory = boundary.resolve("artifacts").resolve(metadata.handle.value)
        directory.resolve("payload").writeText("changed")

        assertThrows(IllegalStateException::class.java) { store.open(metadata.handle) }
    }

    @Test
    fun metadataCodecRejectsExtensionsAndTruncation() {
        val metadata = ArtifactMetadata(
            handle = ArtifactHandle("art_${"a".repeat(64)}"),
            displayName = "Änderung.md",
            mimeType = "text/markdown",
            byteCount = 1,
            sha256 = "b".repeat(64),
            createdAtEpochMillis = 2,
            origin = ArtifactOrigin.ANDROID,
        )
        val encoded = ArtifactMetadataCodec.encode(metadata)

        assertEquals(metadata, ArtifactMetadataCodec.decode(encoded))
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactMetadataCodec.decode(encoded + "extra\n".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactMetadataCodec.decode(encoded.copyOf(encoded.size - 1))
        }
    }

    @Test
    fun symlinkedPayloadIsRejectedAndDeleteDoesNotFollowIt() {
        val boundary = temporary.newFolder("private")
        val store = AtomicArtifactStore(boundary.resolve("artifacts"), boundary)
        val metadata = store.put(
            "answer.txt",
            "text/plain",
            ArtifactOrigin.CODEX,
            ByteArrayInputStream("safe".toByteArray()),
        )
        val outside = temporary.newFile("outside").also { it.writeText("outside") }
        val payload = boundary.resolve("artifacts").resolve(metadata.handle.value).resolve("payload")
        assertTrue(payload.delete())
        Files.createSymbolicLink(payload.toPath(), outside.toPath())

        assertThrows(IllegalArgumentException::class.java) { store.open(metadata.handle) }
        assertTrue(store.delete(metadata.handle))
        assertEquals("outside", outside.readText())
    }

    @Test
    fun rootMustRemainInsidePrivateBoundary() {
        val boundary = temporary.newFolder("private")
        val outside = temporary.newFolder("outside")

        assertThrows(IllegalArgumentException::class.java) {
            AtomicArtifactStore(outside, boundary)
        }
    }
}
