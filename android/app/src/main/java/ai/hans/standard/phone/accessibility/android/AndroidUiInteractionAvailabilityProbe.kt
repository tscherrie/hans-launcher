package ai.hans.standard.phone.accessibility.android

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import ai.hans.standard.phone.accessibility.UiInteractionAvailability
import ai.hans.standard.phone.accessibility.UiInteractionAvailabilityProbe

/** Public-API, root-free guard for foreground Accessibility inspection and actions. */
class AndroidUiInteractionAvailabilityProbe(
    context: Context,
) : UiInteractionAvailabilityProbe {
    private val keyguardManager = context.applicationContext
        .getSystemService(KeyguardManager::class.java)
    private val powerManager = context.applicationContext
        .getSystemService(PowerManager::class.java)

    override fun current(): UiInteractionAvailability {
        val keyguard = keyguardManager ?: return UiInteractionAvailability.STATE_UNAVAILABLE
        val power = powerManager ?: return UiInteractionAvailability.STATE_UNAVAILABLE
        return runCatching {
            UiInteractionAvailability.fromPlatformState(
                // isDeviceLocked deliberately treats a swipe-only keyguard as unlocked. The
                // foreground app is still hidden behind that keyguard, so Accessibility would
                // inspect or act on the wrong window. Gate on either condition.
                deviceLocked = keyguard.isDeviceLocked || keyguard.isKeyguardLocked,
                screenInteractive = power.isInteractive,
            )
        }.getOrDefault(UiInteractionAvailability.STATE_UNAVAILABLE)
    }
}
