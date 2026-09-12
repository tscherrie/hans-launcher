package ai.hans.standard.phone.publicapi

import ai.hans.standard.phone.consent.PersistentAndroidConsentDescriptor
import ai.hans.standard.phone.consent.PersistentAndroidConsentScope
import ai.hans.standard.phone.consent.PersistentAndroidConsentStore
import ai.hans.standard.phone.consent.HansPhoneActionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwappablePublicPhoneConfirmationProviderTest {
    private val request = PublicPhoneConfirmationRequest(
        callId = "call-id",
        tool = "read_location",
        risk = PublicPhoneRisk.SENSITIVE_READ,
        argumentFingerprint = "f".repeat(64),
        displaySummary = "Standort einmalig lesen.",
    )

    @Test
    fun defaultsToRefusalAndClosingRegistrationCannotRemoveNewerUi() {
        val router = SwappablePublicPhoneConfirmationProvider()
        assertNull(router.confirm(request))
        val first = router.attach(PublicPhoneConfirmationProvider { grant(it) })
        assertEquals(grant(request), router.confirm(request))
        val newer = PublicPhoneConfirmationProvider { null }
        val second = router.attach(newer)

        first.close()
        assertNull(router.confirm(request))
        second.close()
        assertNull(router.confirm(request))
    }

    @Test
    fun privateReadGrantWorksWithoutAttachedActivityButMutationNeverDoes() {
        val store = PublicReadOnlyConsentStore(
            setOf(
                PersistentAndroidConsentDescriptor.category(
                    PersistentAndroidConsentScope.READ_LOCATION,
                ),
            ),
        )
        val router = SwappablePublicPhoneConfirmationProvider(store)
        val durableRead = request.copy(
            persistentConsentScope = PersistentAndroidConsentScope.READ_LOCATION,
        )

        assertEquals(grant(durableRead), router.confirm(durableRead))
        assertNull(
            router.confirm(
                durableRead.copy(
                    tool = "reply_notification",
                    risk = PublicPhoneRisk.EXTERNAL_MUTATION,
                ),
            ),
        )
    }

    @Test
    fun everyPublicPhoneEverydayScopeFastPathsHeadlessly() {
        val router = SwappablePublicPhoneConfirmationProvider(
            PublicReadOnlyConsentStore(
                ai.hans.standard.phone.consent.PersistentAndroidConsentCatalog.EVERYDAY_DESCRIPTORS,
            ),
        )
        val cases = listOf(
            Triple("search_contacts", PublicPhoneRisk.SENSITIVE_READ, PersistentAndroidConsentScope.READ_CONTACTS),
            Triple("read_calendar", PublicPhoneRisk.SENSITIVE_READ, PersistentAndroidConsentScope.READ_CALENDAR),
            Triple("read_location", PublicPhoneRisk.SENSITIVE_READ, PersistentAndroidConsentScope.READ_LOCATION),
            Triple("read_sensors", PublicPhoneRisk.SENSITIVE_READ, PersistentAndroidConsentScope.READ_SENSORS),
            Triple("list_media", PublicPhoneRisk.SENSITIVE_READ, PersistentAndroidConsentScope.READ_MEDIA),
            Triple(
                "list_replyable_notifications",
                PublicPhoneRisk.SENSITIVE_READ,
                PersistentAndroidConsentScope.READ_REPLYABLE_NOTIFICATIONS,
            ),
            Triple("open_camera", PublicPhoneRisk.USER_VISIBLE, PersistentAndroidConsentScope.OPEN_CAMERA),
            Triple(
                "prepare_calendar_event",
                PublicPhoneRisk.USER_VISIBLE,
                PersistentAndroidConsentScope.PREPARE_CALENDAR_EVENT,
            ),
        )

        cases.forEachIndexed { index, (tool, risk, scope) ->
            val current = request.copy(
                callId = "everyday-$index",
                tool = tool,
                risk = risk,
                argumentFingerprint = index.toString().padStart(64, '0'),
                persistentConsentScope = scope,
            )
            assertEquals(grant(current), router.confirm(current))
        }
    }

    @Test
    fun fullAccessCoversPrivateReadsAndExternalMutationsWithoutAnotherPrompt() {
        val store = PublicReadOnlyConsentStore(
            setOf(PersistentAndroidConsentDescriptor.category(PersistentAndroidConsentScope.READ_LOCATION)),
        )
        val router = SwappablePublicPhoneConfirmationProvider(
            store,
            actionPolicy = HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS,
        )
        var prompts = 0
        val registration = router.attach(PublicPhoneConfirmationProvider { prompts += 1; null })
        val cases = listOf(
            request.copy(persistentConsentScope = PersistentAndroidConsentScope.READ_LOCATION),
            request.copy(tool = "open_camera", risk = PublicPhoneRisk.USER_VISIBLE),
            request.copy(tool = "create_calendar_event", risk = PublicPhoneRisk.EXTERNAL_MUTATION),
            request.copy(tool = "reply_notification", risk = PublicPhoneRisk.EXTERNAL_MUTATION),
        )
        cases.forEachIndexed { index, value ->
            val current = value.copy(callId = "full-$index", argumentFingerprint = index.toString().padStart(64, 'a'))
            assertEquals(grant(current), router.confirm(current))
        }
        registration.close()
        assertEquals(grant(cases.last()), router.confirm(cases.last()))
        assertEquals(0, prompts)
        assertEquals(1, store.active().size)
    }

    private fun grant(request: PublicPhoneConfirmationRequest): PublicPhoneConfirmationGrant =
        PublicPhoneConfirmationGrant(
            request.callId,
            request.tool,
            request.risk,
            request.argumentFingerprint,
        )
}

private class PublicReadOnlyConsentStore(
    private val values: Set<PersistentAndroidConsentDescriptor>,
) : PersistentAndroidConsentStore {
    override fun contains(descriptor: PersistentAndroidConsentDescriptor) = descriptor in values
    override fun grant(descriptor: PersistentAndroidConsentDescriptor) = false
    override fun revoke(descriptor: PersistentAndroidConsentDescriptor) = false
    override fun revokeAll() = false
    override fun active() = values
}
