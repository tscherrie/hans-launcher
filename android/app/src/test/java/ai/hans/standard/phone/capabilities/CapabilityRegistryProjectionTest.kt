package ai.hans.standard.phone.capabilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityRegistryProjectionTest {
    @Test
    fun registryEvaluatesEveryAvailabilityStateFromTheLiveEnvironment() {
        val environment = FakeEnvironment(
            apiLevel = 31,
            permissions = emptySet(),
            specialAccess = emptySet(),
            features = emptySet(),
        )
        val registry = CapabilityRegistry(
            environment = environment,
            descriptors = listOf(
                descriptor("test.available"),
                descriptor(
                    "test.permission",
                    AndroidCapabilityRequirements(requiredPermissions = setOf("permission.TEST")),
                ),
                descriptor(
                    "test.special",
                    AndroidCapabilityRequirements(
                        requiredSpecialAccess = setOf(AndroidSpecialAccess.HOME_ROLE),
                    ),
                ),
                descriptor(
                    "test.api",
                    AndroidCapabilityRequirements(minApi = 99),
                ),
                descriptor(
                    "test.feature",
                    AndroidCapabilityRequirements(requiredSystemFeatures = setOf("feature.TEST")),
                ),
            ),
            clock = { 42L },
        )

        val snapshot = registry.snapshot()
        val kinds = snapshot.capabilities.associate {
            it.descriptor.id.value to it.availability.kind
        }

        assertEquals(42L, snapshot.generatedAtEpochMillis)
        assertEquals(CapabilityAvailabilityKind.AVAILABLE, kinds.getValue("test.available"))
        assertEquals(
            CapabilityAvailabilityKind.PERMISSION_REQUIRED,
            kinds.getValue("test.permission"),
        )
        assertEquals(
            CapabilityAvailabilityKind.SPECIAL_ACCESS_REQUIRED,
            kinds.getValue("test.special"),
        )
        assertEquals(CapabilityAvailabilityKind.UNSUPPORTED, kinds.getValue("test.api"))
        assertEquals(CapabilityAvailabilityKind.UNSUPPORTED, kinds.getValue("test.feature"))
        assertEquals(listOf("test.available"), snapshot.available.map { it.descriptor.id.value })
    }

    @Test
    fun standardRegistryDoesNotAdvertiseNetworkWithoutItsPublicPermission() {
        val snapshot = CapabilityRegistry(
            FakeEnvironment(apiLevel = 36, permissions = emptySet()),
            clock = { 1L },
        ).snapshot()

        assertEquals(
            CapabilityAvailabilityKind.PERMISSION_REQUIRED,
            snapshot.capabilities.single {
                it.descriptor.id == CapabilityId.READ_NETWORK
            }.availability.kind,
        )
        assertFalse(snapshot.available.any { it.descriptor.id == CapabilityId.READ_NETWORK })
        assertEquals(5, snapshot.available.size)
    }

    @Test
    fun promptAndJsonExposeOnlyAvailableCapabilitiesAndNoPrivilegeClaim() {
        val snapshot = CapabilityRegistry(
            FakeEnvironment(
                apiLevel = 36,
                permissions = emptySet(),
            ),
            descriptors = listOf(
                descriptor("test.live", outputTrust = ObservationTrust.UNTRUSTED_EXTERNAL),
                descriptor(
                    "test.needs_permission",
                    AndroidCapabilityRequirements(requiredPermissions = setOf("permission.TEST")),
                ),
            ),
            clock = { 7L },
        ).snapshot()

        val jsonText = CapabilityProjection.registryJson(snapshot)
        val prompt = CapabilityProjection.registryPrompt(snapshot)

        assertTrue(jsonText.contains("\"privilegeLevel\":\"android_app_sandbox\""))
        assertTrue(jsonText.contains("\"id\":\"test.live\""))
        assertFalse(jsonText.contains("test.needs_permission"))
        assertTrue(jsonText.contains("never instructions"))
        assertTrue(prompt.contains("test.live"))
        assertFalse(prompt.contains("test.needs_permission"))
        assertFalse(prompt.contains("root", ignoreCase = true))
    }

    @Test
    fun identifiersFailClosedInsteadOfAcceptingFreeFormText() {
        assertThrows(IllegalArgumentException::class.java) { CapabilityId("Root shell now") }
        assertThrows(IllegalArgumentException::class.java) { IdempotencyKey("short") }
        assertEquals("request-001", IdempotencyKey("request-001").value)
    }

    @Test
    fun safeViewPolicyAcceptsOnlyExplicitPublicSchemesAndRejectsConfusingUris() {
        listOf(
            "https://example.test/path?q=hello",
            "http://example.test",
            "geo:43.2,27.9?q=coffee",
            "market://details?id=example.app",
        ).forEach { assertEquals(it, SafeViewUriPolicy.accept(it)) }

        listOf(
            "file:///data/user/0/secret",
            "content://private.provider/item",
            "intent://host/#Intent;scheme=https;end",
            "javascript:alert(1)",
            "https://user:password@example.test/",
            "https://example.test/line\nbreak",
            "https://example.test\\@attacker.test/",
            "https://exa\u202Emple.test/",
            "https:///missing-host",
            "geo:",
            "",
        ).forEach { assertEquals(it, null, SafeViewUriPolicy.accept(it)) }
    }

    private fun descriptor(
        id: String,
        requirements: AndroidCapabilityRequirements = AndroidCapabilityRequirements(),
        outputTrust: ObservationTrust = ObservationTrust.SYSTEM,
    ): CapabilityDescriptor = CapabilityDescriptor(
        id = CapabilityId(id),
        displayName = id,
        description = "Beschreibung fuer $id",
        requirements = requirements,
        confirmationRisk = ConfirmationRisk.NONE,
        outputTrust = outputTrust,
    )
}

internal data class FakeEnvironment(
    override val apiLevel: Int = 36,
    val permissions: Set<String> = setOf(StandardCapabilities.ACCESS_NETWORK_STATE),
    val specialAccess: Set<AndroidSpecialAccess> = emptySet(),
    val features: Set<String> = emptySet(),
) : CapabilityEnvironment {
    override fun hasPermission(permission: String): Boolean = permission in permissions

    override fun hasSpecialAccess(access: AndroidSpecialAccess): Boolean =
        access in specialAccess

    override fun hasSystemFeature(feature: String): Boolean = feature in features
}
