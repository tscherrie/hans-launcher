package ai.hans.standard.phone.tools

import ai.hans.standard.phone.capabilities.CapabilityConfirmation
import ai.hans.standard.phone.capabilities.CapabilityId
import ai.hans.standard.phone.capabilities.ConfirmationRisk
import ai.hans.standard.phone.capabilities.IdempotencyKey
import ai.hans.standard.phone.consent.HansPhoneActionPolicy
import ai.hans.standard.phone.consent.PersistentAndroidConsentDescriptor
import ai.hans.standard.phone.consent.PersistentAndroidConsentScope
import ai.hans.standard.phone.consent.PersistentAndroidConsentStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwappableDynamicToolConfirmationProviderTest {
    private val request = DynamicToolConfirmationRequest(
        callId = "call-1",
        capabilityId = CapabilityId.LAUNCH_APP,
        idempotencyKey = IdempotencyKey("tool-call-1"),
        risk = ConfirmationRisk.USER_VISIBLE,
    )

    @Test
    fun backgroundDefaultRefusesAndAttachedProviderIsExact() {
        val router = SwappableDynamicToolConfirmationProvider()
        assertNull(router.confirmedGrant(request))

        val expected = CapabilityConfirmation(
            request.capabilityId,
            request.idempotencyKey,
            request.risk,
        )
        val registration = router.attach(DynamicToolConfirmationProvider { expected })
        assertEquals(expected, router.confirmedGrant(request))

        registration.close()
        assertNull(router.confirmedGrant(request))
    }

    @Test
    fun staleRegistrationCannotDetachNewerActivity() {
        val router = SwappableDynamicToolConfirmationProvider()
        val first = router.attach(DynamicToolConfirmationProvider { null })
        val expected = CapabilityConfirmation(
            request.capabilityId,
            request.idempotencyKey,
            request.risk,
        )
        val second = router.attach(DynamicToolConfirmationProvider { expected })

        first.close()
        assertEquals(expected, router.confirmedGrant(request))

        second.close()
        assertNull(router.confirmedGrant(request))
    }

    @Test
    fun durableEverydayGrantWorksWithoutAnyAttachedActivity() {
        val descriptor = PersistentAndroidConsentDescriptor.category(
            PersistentAndroidConsentScope.OPEN_APP,
        )
        val store = ReadOnlyConsentStore(setOf(descriptor))
        val router = SwappableDynamicToolConfirmationProvider(store)
        val durableRequest = request.copy(
            persistentConsent = DynamicToolPersistentConsent(
                PersistentAndroidConsentScope.OPEN_APP,
            ),
        )

        assertEquals(
            CapabilityConfirmation(
                durableRequest.capabilityId,
                durableRequest.idempotencyKey,
                durableRequest.risk,
            ),
            router.confirmedGrant(durableRequest),
        )
    }

    @Test
    fun mismatchedDurableMetadataNeverAuthorizesHeadlessCall() {
        val store = ReadOnlyConsentStore(
            setOf(
                PersistentAndroidConsentDescriptor.category(
                    PersistentAndroidConsentScope.READ_LOCATION,
                ),
            ),
        )
        val router = SwappableDynamicToolConfirmationProvider(store)

        assertNull(
            router.confirmedGrant(
                request.copy(
                    persistentConsent = DynamicToolPersistentConsent(
                        PersistentAndroidConsentScope.READ_LOCATION,
                    ),
                ),
            ),
        )
    }

    @Test
    fun everyDynamicEverydayScopeFastPathsHeadlessly() {
        val store = ReadOnlyConsentStore(
            ai.hans.standard.phone.consent.PersistentAndroidConsentCatalog.EVERYDAY_DESCRIPTORS,
        )
        val router = SwappableDynamicToolConfirmationProvider(store)
        val requests = listOf(
            request.copy(
                capabilityId = CapabilityId.LIST_LAUNCHABLE_APPS,
                risk = ConfirmationRisk.SENSITIVE_DATA,
                persistentConsent = DynamicToolPersistentConsent(
                    PersistentAndroidConsentScope.INSTALLED_APPS_READ,
                ),
            ),
            request.copy(
                persistentConsent = DynamicToolPersistentConsent(
                    PersistentAndroidConsentScope.OPEN_APP,
                ),
            ),
            request.copy(
                capabilityId = CapabilityId.OPEN_VIEW,
                persistentConsent = DynamicToolPersistentConsent(
                    PersistentAndroidConsentScope.OPEN_SAFE_NAVIGATION,
                ),
            ),
            request.copy(
                capabilityId = CapabilityId.OPEN_SETTINGS,
                persistentConsent = DynamicToolPersistentConsent(
                    PersistentAndroidConsentScope.OPEN_SETTINGS_PAGE,
                ),
            ),
        )

        requests.forEach { durableRequest ->
            assertEquals(durableRequest.capabilityId, router.confirmedGrant(durableRequest)?.capabilityId)
        }
    }

    @Test
    fun explicitFullAccessUsesExactGrantsForEveryRiskWithoutPromptOrStoredScope() {
        val router = SwappableDynamicToolConfirmationProvider(
            actionPolicy = HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS,
        )
        var prompts = 0
        val registration = router.attach(DynamicToolConfirmationProvider { prompts += 1; null })
        ConfirmationRisk.entries.forEachIndexed { index, risk ->
            val current = request.copy(
                callId = "full-access-$index",
                idempotencyKey = IdempotencyKey("full-access-$index"),
                risk = risk,
            )
            assertEquals(
                CapabilityConfirmation(current.capabilityId, current.idempotencyKey, current.risk),
                router.confirmedGrant(current),
            )
        }
        registration.close()
        assertEquals(
            CapabilityConfirmation(request.capabilityId, request.idempotencyKey, request.risk),
            router.confirmedGrant(request),
        )
        assertEquals(0, prompts)
    }
}

private class ReadOnlyConsentStore(
    private val values: Set<PersistentAndroidConsentDescriptor>,
) : PersistentAndroidConsentStore {
    override fun contains(descriptor: PersistentAndroidConsentDescriptor) = descriptor in values
    override fun grant(descriptor: PersistentAndroidConsentDescriptor) = false
    override fun revoke(descriptor: PersistentAndroidConsentDescriptor) = false
    override fun revokeAll() = false
    override fun active() = values
}
