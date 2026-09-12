package ai.hans.standard.phone.notifications

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPrivacyTest {
    @Test
    fun missingPolicyUsesDefaultsButCorruptionFailsClosed() {
        val storage = MemoryPrivacyStorage(NotificationPrivacyStorageRead.Missing)
        val repository = NotificationPrivacyRepository(storage, "ai.hans.standard")

        assertEquals(
            NotificationCaptureDecision.Allowed,
            repository.captureDecision("com.example.chat"),
        )
        assertEquals(
            NotificationCaptureDecision.ProtectedPackage,
            repository.captureDecision("com.android.systemui"),
        )
        assertEquals(
            NotificationCaptureDecision.ProtectedPackage,
            repository.captureDecision("ai.hans.legacy.extension"),
        )

        storage.read = NotificationPrivacyStorageRead.Unavailable
        assertEquals(
            NotificationCaptureDecision.PolicyUnavailable,
            repository.captureDecision("com.example.chat"),
        )
        assertFalse(repository.status().policyAvailable)
    }

    @Test
    fun exclusionsAndRetentionAreVerifiedAfterPersistence() {
        val storage = MemoryPrivacyStorage(NotificationPrivacyStorageRead.Missing)
        val repository = NotificationPrivacyRepository(storage, "ai.hans.standard")

        repository.excludePackage("com.example.chat")
        assertEquals(
            NotificationCaptureDecision.UserExcludedPackage,
            repository.captureDecision("com.example.chat"),
        )
        val effective = repository.setRetention(maxEvents = 73, maxAgeHours = 12)
        assertEquals(NotificationRetentionPolicy(73, 12), effective.retention)
        repository.includePackage("com.example.chat")
        assertEquals(
            NotificationCaptureDecision.Allowed,
            repository.captureDecision("com.example.chat"),
        )
        assertTrue(storage.writes >= 3)
    }

    @Test
    fun recoveryDocumentContainsSettingsOnlyAndRejectsSchemaExpansion() {
        val settings = NotificationPrivacySettings(
            excludedPackages = setOf("com.example.chat"),
            retention = NotificationRetentionPolicy(88, 24),
        )
        val encoded = NotificationPrivacyRecoveryCodec.encode(settings)

        assertEquals(settings, NotificationPrivacyRecoveryCodec.decode(encoded))
        assertFalse(encoded.contains("title"))
        assertFalse(encoded.contains("text"))
        assertFalse(encoded.contains("action"))
        assertFalse(encoded.contains("androidKey"))
        assertFalse(encoded.contains("token"))

        val expanded = JSONObject(encoded).put("notificationActions", "secret").toString()
        assertFails { NotificationPrivacyRecoveryCodec.decode(expanded) }
        val nestedExpanded = JSONObject(encoded).also {
            it.getJSONObject("retention").put("days", 7)
        }.toString()
        assertFails { NotificationPrivacyRecoveryCodec.decode(nestedExpanded) }
    }

    @Test
    fun recoveryRejectsDuplicateInvalidAndOversizePackageLists() {
        val valid = JSONObject(
            NotificationPrivacyRecoveryCodec.encode(NotificationPrivacySettings()),
        )
        valid.getJSONArray("excludedPackages")
            .put("com.example.chat")
            .put("com.example.chat")
        assertFails { NotificationPrivacyRecoveryCodec.decode(valid.toString()) }

        val invalid = JSONObject(
            NotificationPrivacyRecoveryCodec.encode(NotificationPrivacySettings()),
        )
        invalid.getJSONArray("excludedPackages").put("../../data")
        assertFails { NotificationPrivacyRecoveryCodec.decode(invalid.toString()) }

        val tooMany = JSONObject(
            NotificationPrivacyRecoveryCodec.encode(NotificationPrivacySettings()),
        )
        repeat(NotificationPrivacyBounds.MAX_USER_EXCLUSIONS + 1) {
            tooMany.getJSONArray("excludedPackages").put("com.example.p$it")
        }
        assertFails { NotificationPrivacyRecoveryCodec.decode(tooMany.toString()) }
    }

    @Test
    fun protectedPackagesCanNeverBeReenabledOrImported() {
        val storage = MemoryPrivacyStorage(NotificationPrivacyStorageRead.Missing)
        val repository = NotificationPrivacyRepository(storage, "ai.hans.standard")

        assertFails { repository.includePackage("com.android.systemui") }
        val document = NotificationPrivacyRecoveryCodec.encode(
            NotificationPrivacySettings(excludedPackages = setOf("com.android.systemui")),
        )
        assertFails { repository.importRecoveryDocument(document) }
    }

    private fun assertFails(block: () -> Unit) {
        assertTrue(runCatching(block).isFailure)
    }

    private class MemoryPrivacyStorage(
        var read: NotificationPrivacyStorageRead,
    ) : NotificationPrivacySettingsStorage {
        var writes = 0

        override fun read(): NotificationPrivacyStorageRead = read

        override fun write(settings: NotificationPrivacySettings) {
            writes += 1
            read = NotificationPrivacyStorageRead.Available(settings)
        }
    }
}
