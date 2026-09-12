package ai.hans.standard.phone.tools

import ai.hans.standard.phone.capabilities.CapabilityConfirmation
import ai.hans.standard.phone.consent.HansPhoneActionPolicy
import ai.hans.standard.phone.consent.PersistentAndroidConsentStore
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-lifetime confirmation seam. Existing trusted category grants remain
 * usable headlessly; a missing grant needs a visible Hans Activity provider in confirmation mode.
 * Explicit full access mints the same exact grant without opening that extra Hans UI.
 */
class SwappableDynamicToolConfirmationProvider(
    private val consentStore: PersistentAndroidConsentStore = PersistentAndroidConsentStore.NONE,
    private val actionPolicy: HansPhoneActionPolicy = HansPhoneActionPolicy.CONFIRM_ACTIONS,
) : DynamicToolConfirmationProvider {
    private val current = AtomicReference<DynamicToolConfirmationProvider?>(null)

    override fun confirmedGrant(
        request: DynamicToolConfirmationRequest,
    ): CapabilityConfirmation? {
        if (actionPolicy == HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS) {
            return CapabilityConfirmation(request.capabilityId, request.idempotencyKey, request.risk)
        }
        val descriptor = DynamicToolPersistentConsentPolicy.descriptorFor(request)
        if (
            descriptor != null &&
            runCatching { consentStore.contains(descriptor) }.getOrDefault(false)
        ) {
            return CapabilityConfirmation(
                request.capabilityId,
                request.idempotencyKey,
                request.risk,
            )
        }
        return current.get()?.confirmedGrant(request)
    }

    fun attach(provider: DynamicToolConfirmationProvider): Closeable {
        current.set(provider)
        return Closeable { current.compareAndSet(provider, null) }
    }

    fun clear() {
        current.set(null)
    }
}
