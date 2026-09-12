package ai.hans.standard.diagnostics

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationSessionReadinessTest {
    @Test
    fun codecIsCanonicalStrictAndContainsNoIdentity() {
        val snapshot = snapshot()
        val encoded = MigrationSessionReadinessCodec.encode(snapshot)

        assertEquals(snapshot, MigrationSessionReadinessCodec.decode(encoded))
        assertFalse(encoded.contains("thread-secret"))
        assertTrue(encoded.length < MigrationSessionReadinessCodec.MAX_BYTES)
        val withUnexpected = encoded.dropLast(1) + ",\"unexpected\":true}"
        assertFails { MigrationSessionReadinessCodec.decode(withUnexpected) }
    }

    @Test
    fun publisherWritesOnlyWhenSmallSemanticProjectionChanges() {
        val directory = Files.createTempDirectory("hans-readiness-test").toFile()
        val file = directory.resolve(MigrationSessionReadinessPublisher.FILE_NAME)
        var now = 200L
        val publisher = MigrationSessionReadinessPublisher(
            file,
            packageSnapshot = { packageSnapshot() },
            clockMillis = { now },
            processId = { 1234 },
            writer = MigrationReadinessWriter { bytes -> Files.write(file.toPath(), bytes) },
        )
        val projection = snapshot().projection()
        try {
            assertTrue(publisher.publish(projection))
            val firstModified = file.lastModified()
            val firstBytes = file.readBytes()
            now = 300L
            assertFalse(publisher.publish(projection))
            assertEquals(firstModified, file.lastModified())
            assertTrue(firstBytes.contentEquals(file.readBytes()))

            assertTrue(publisher.publish(projection.copy(activeTurn = true)))
            assertTrue(file.readText().contains("\"activeTurn\":true"))

            val nextProcessPublisher = MigrationSessionReadinessPublisher(
                file,
                packageSnapshot = { packageSnapshot() },
                clockMillis = { 400L },
                processId = { 5678 },
                writer = MigrationReadinessWriter { bytes -> Files.write(file.toPath(), bytes) },
            )
            assertTrue(nextProcessPublisher.publish(projection.copy(activeTurn = true)))
            assertEquals(5678, MigrationSessionReadinessCodec.decode(file.readText()).interactiveProcessId)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun snapshot() = MigrationSessionReadinessSnapshot(
        packageName = "ai.hans.standard",
        longVersionCode = 2,
        packageLastUpdateTimeMillis = 100,
        observationTimestampMillis = 200,
        interactiveProcessId = 1234,
        accountPhase = MigrationAccountPhase.SIGNED_IN,
        accountReadComplete = true,
        runtimeReady = true,
        threadCorrelationSha256 = MigrationDiagnosticsHashes.thread("thread-secret"),
        threadResumeConfirmed = true,
        memoryModeEnabledAck = true,
        effectiveModel = "gpt-5.6-luna",
        effectiveReasoningEffort = "max",
        speechCredentialAvailable = true,
        setupComplete = true,
        activeTurn = false,
        dictationActive = false,
        liveVoiceActive = false,
        activeAutomationCount = 0,
    )

    private fun packageSnapshot() = MigrationPackageSnapshot(
        packageName = "ai.hans.standard",
        longVersionCode = 2,
        versionName = "0.1.0",
        uid = 10_123,
        dataDirInode = 42,
        debuggable = false,
        currentSignerSha256 = "a".repeat(64),
        signingHistorySha256 = listOf("a".repeat(64)),
        homeRoleHeld = true,
        lastUpdateTimeMillis = 100,
    )

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
    }
}
