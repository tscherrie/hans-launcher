package ai.hans.standard.voice.feedback

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings

/** Unknown policy or absent hardware is deliberately silent; this never changes Android settings. */
data class ResponseReadyHapticPolicy(
    val permissionGranted: Boolean,
    val hasVibrator: Boolean,
    val systemHapticsEnabled: Boolean?,
    val ringerAllowsHaptics: Boolean?,
    val interruptionsAllowed: Boolean?,
) {
    val permitsPulse: Boolean
        get() = permissionGranted && hasVibrator && systemHapticsEnabled == true &&
            ringerAllowsHaptics == true && interruptionsAllowed == true
}

internal interface ResponseReadyHapticPlatform {
    fun readPolicy(): ResponseReadyHapticPolicy
    fun pulse()
}

/** Public Android 12+ APIs; no Activity, notification grant, DND access, or wake lock is required. */
class AndroidResponseReadyHaptics internal constructor(
    private val platform: ResponseReadyHapticPlatform,
) : ResponseReadyHaptics {
    constructor(context: Context) : this(FrameworkResponseReadyHapticPlatform(context.applicationContext))

    /** Read-only capability/policy probe; it never actuates the vibrator. */
    fun probe(): ResponseReadyHapticPolicy? = try {
        platform.readPolicy()
    } catch (_: RuntimeException) {
        null
    }

    override fun pulse() {
        if (probe()?.permitsPulse != true) return
        try {
            platform.pulse()
        } catch (_: RuntimeException) {
            // A missing service, revoked permission, or concurrent policy change is harmless.
        }
    }

    companion object {
        const val PULSE_MILLIS = 30L

        internal fun audioAttributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}

private class FrameworkResponseReadyHapticPlatform(
    private val context: Context,
) : ResponseReadyHapticPlatform {
    override fun readPolicy(): ResponseReadyHapticPolicy {
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        val audio = context.getSystemService(AudioManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val systemHaptics = try {
            when (Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED)) {
                0 -> false
                1 -> true
                else -> null
            }
        } catch (_: Settings.SettingNotFoundException) {
            null
        }
        return ResponseReadyHapticPolicy(
            permissionGranted = context.checkSelfPermission(Manifest.permission.VIBRATE) ==
                PackageManager.PERMISSION_GRANTED,
            hasVibrator = vibrator?.hasVibrator() == true,
            systemHapticsEnabled = systemHaptics,
            ringerAllowsHaptics = when (audio?.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL, AudioManager.RINGER_MODE_VIBRATE -> true
                AudioManager.RINGER_MODE_SILENT -> false
                else -> null
            },
            interruptionsAllowed = when (notifications?.currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_ALL -> true
                NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                NotificationManager.INTERRUPTION_FILTER_ALARMS,
                NotificationManager.INTERRUPTION_FILTER_NONE -> false
                else -> null
            },
        )
    }

    override fun pulse() {
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        if (!vibrator.hasVibrator()) return
        val effect = VibrationEffect.createOneShot(
            AndroidResponseReadyHaptics.PULSE_MILLIS,
            VibrationEffect.DEFAULT_AMPLITUDE,
        )
        // A response-ready notification usage is permitted in background; touch/sonification
        // usage is not. Android still applies notification-vibration policy. No bypass flag,
        // alarm usage, repeat pattern, or policy override is requested.
        // https://developer.android.com/reference/android/os/Vibrator#vibrate(android.os.VibrationEffect,%20android.media.AudioAttributes)
        @Suppress("DEPRECATION")
        vibrator.vibrate(
            effect,
            AndroidResponseReadyHaptics.audioAttributes(),
        )
    }
}
