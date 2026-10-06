package ai.hans.standard.phone.publicapi

import ai.hans.standard.localization.AndroidHansTextResolver

import android.Manifest
import android.content.Context
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.phone.consent.HansPhoneActionPolicy
import ai.hans.standard.phone.consent.PersistentAndroidConsentStore
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

data class PublicPhoneToolIntegration(
    val executor: DynamicToolExecutor,
    /** Feed this from the existing NotificationListenerService in the same app process. */
    val notificationReplyRegistry: ActiveNotificationReplyRegistry,
)

/** Slim host seam; it performs no permission request and no component registration itself. */
object PublicPhoneToolIntegrationFactory {
    fun create(
        context: Context,
        backgroundExecutor: Executor,
        confirmations: PublicPhoneConfirmationProvider = PublicPhoneConfirmationProvider.NONE,
        notificationReplyRegistry: ActiveNotificationReplyRegistry =
            ActiveNotificationReplyRegistry.processWide,
    ): PublicPhoneToolIntegration {
        val platform = AndroidPublicPhonePlatform(context, notificationReplyRegistry)
        return PublicPhoneToolIntegration(
            executor = PublicPhoneDynamicToolExecutor(
                platform = platform,
                backgroundExecutor = backgroundExecutor,
                text = AndroidHansTextResolver(context),
                confirmations = confirmations,
            ),
            notificationReplyRegistry = notificationReplyRegistry,
        )
    }

    /** Manifest declarations needed for full capability coverage; all dangerous grants stay user-driven. */
    val runtimePermissionManifestNames: Set<String> = setOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
        Manifest.permission.READ_EXTERNAL_STORAGE,
    )

    val packageVisibilityIntentActions: Set<String> = setOf(
        android.provider.MediaStore.ACTION_IMAGE_CAPTURE,
        android.provider.MediaStore.ACTION_VIDEO_CAPTURE,
        android.content.Intent.ACTION_INSERT,
    )
}

/** Explicit full access skips Hans's extra prompt; narrower mode retains revocable category grants. */
class SwappablePublicPhoneConfirmationProvider(
    private val consentStore: PersistentAndroidConsentStore = PersistentAndroidConsentStore.NONE,
    private val actionPolicy: HansPhoneActionPolicy = HansPhoneActionPolicy.CONFIRM_ACTIONS,
) : PublicPhoneConfirmationProvider {
    private val current = AtomicReference<PublicPhoneConfirmationProvider?>(null)

    override fun confirm(
        request: PublicPhoneConfirmationRequest,
    ): PublicPhoneConfirmationGrant? {
        if (actionPolicy == HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS) {
            return PublicPhoneConfirmationGrant(request.callId, request.tool, request.risk, request.argumentFingerprint)
        }
        val descriptor = PublicPhonePersistentConsentPolicy.descriptorFor(request)
        if (
            descriptor != null &&
            runCatching { consentStore.contains(descriptor) }.getOrDefault(false)
        ) {
            return PublicPhoneConfirmationGrant(
                callId = request.callId,
                tool = request.tool,
                risk = request.risk,
                argumentFingerprint = request.argumentFingerprint,
            )
        }
        return current.get()?.confirm(request)
    }

    fun attach(provider: PublicPhoneConfirmationProvider): Closeable {
        current.set(provider)
        return Closeable { current.compareAndSet(provider, null) }
    }

    fun clear() {
        current.set(null)
    }
}
