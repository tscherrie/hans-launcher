package ai.hans.standard.phone.capabilities

import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import ai.hans.standard.phone.accessibility.android.HansAccessibilityService

class AndroidCapabilityEnvironment(context: Context) : CapabilityEnvironment {
    private val appContext = context.applicationContext

    override val apiLevel: Int
        get() = Build.VERSION.SDK_INT

    override fun hasPermission(permission: String): Boolean =
        appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun hasSpecialAccess(access: AndroidSpecialAccess): Boolean = when (access) {
        AndroidSpecialAccess.ALL_FILES -> runCatching {
            android.os.Environment.isExternalStorageManager()
        }.getOrDefault(false)
        AndroidSpecialAccess.HOME_ROLE -> hasRole(RoleManager.ROLE_HOME)
        AndroidSpecialAccess.ASSISTANT_ROLE -> hasRole(RoleManager.ROLE_ASSISTANT)
        AndroidSpecialAccess.NOTIFICATION_LISTENER -> hasNotificationListenerAccess()
        AndroidSpecialAccess.ACCESSIBILITY_SERVICE ->
            accessibilityGrantState() == AccessibilityGrantState.GRANTED
        AndroidSpecialAccess.BATTERY_OPTIMIZATION_EXEMPTION -> runCatching {
            appContext.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(appContext.packageName)
                ?: false
        }.getOrDefault(false)
    }

    override fun hasSystemFeature(feature: String): Boolean =
        appContext.packageManager.hasSystemFeature(feature)

    /**
     * This is the user's durable grant, not a claim that the service is currently usable.
     * Android's getEnabledAccessibilityServiceList queries bound services and can be empty
     * during an APK update or service rebind. Actual UI commands still require a live session.
     * Read the public setting afresh so revocations take effect without a cached grant.
     */
    internal fun accessibilityGrantState(): AccessibilityGrantState {
        val component = ComponentName(appContext, HansAccessibilityService::class.java)
        return readAccessibilityGrantState(
            fullComponent = component.flattenToString(),
            shortComponent = component.flattenToShortString(),
        ) {
            queryAccessibilityGrantSetting {
                // getString also returns null after swallowed provider failures. A cursor
                // distinguishes a successful absent value from a temporarily unavailable read.
                appContext.contentResolver.query(
                    Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                    arrayOf(Settings.NameValueTable.VALUE),
                    null,
                    null,
                    null,
                )
            }
        }
    }

    private fun hasRole(role: String): Boolean = runCatching {
        appContext.getSystemService(RoleManager::class.java)
            ?.let { manager -> manager.isRoleAvailable(role) && manager.isRoleHeld(role) }
            ?: false
    }.getOrDefault(false)

    private fun hasNotificationListenerAccess(): Boolean = runCatching {
        val manager = appContext.getSystemService(NotificationManager::class.java)
            ?: return@runCatching false
        notificationListenerComponents().any(manager::isNotificationListenerAccessGranted)
    }.getOrDefault(false)

    private fun notificationListenerComponents(): List<ComponentName> {
        val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
            appContext.packageManager.getPackageInfo(
                appContext.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SERVICES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(
                appContext.packageName,
                PackageManager.GET_SERVICES,
            )
        }
        return packageInfo.services
            .orEmpty()
            .filter { it.permission == NOTIFICATION_LISTENER_BIND_PERMISSION }
            .map { ComponentName(it.packageName, it.name) }
    }

    private companion object {
        const val NOTIFICATION_LISTENER_BIND_PERMISSION =
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
    }
}

internal enum class AccessibilityGrantState {
    GRANTED,
    NOT_GRANTED,
    UNKNOWN,
}

/** A successful provider query can legitimately contain no row or a null value. */
internal data class AccessibilityGrantSetting(val enabledServices: String?)

/** Null means no cursor was returned, never that the user removed a grant. */
internal fun queryAccessibilityGrantSetting(query: () -> Cursor?): AccessibilityGrantSetting? =
    query()?.use { cursor ->
        AccessibilityGrantSetting(
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(Settings.NameValueTable.VALUE))
            } else {
                null
            },
        )
    }

/** Only exact framework-flattened components count; a failed read is not a revocation. */
internal fun readAccessibilityGrantState(
    fullComponent: String,
    shortComponent: String,
    readEnabledServices: () -> AccessibilityGrantSetting?,
): AccessibilityGrantState = try {
    val setting = readEnabledServices()
    if (setting == null) {
        AccessibilityGrantState.UNKNOWN
    } else if (setting.enabledServices?.splitToSequence(':')?.any {
            it == fullComponent || it == shortComponent
        } == true
    ) {
        AccessibilityGrantState.GRANTED
    } else {
        AccessibilityGrantState.NOT_GRANTED
    }
} catch (_: Exception) {
    AccessibilityGrantState.UNKNOWN
}
