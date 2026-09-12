package ai.hans.standard.automations.androidintegration

import ai.hans.standard.automations.AutomationCapabilityId
import ai.hans.standard.automations.AutomationExecutionEnvironment
import ai.hans.standard.automations.AndroidKeyguardUnlockPolicy
import ai.hans.standard.automations.AutomationLiveEnvironmentSource
import ai.hans.standard.automations.AutomationPermissionId
import ai.hans.standard.codex.AccountPhase
import ai.hans.standard.integration.AndroidCodexSessionHost
import ai.hans.standard.integration.ClientRuntimePhase
import ai.hans.standard.phone.capabilities.AndroidCapabilityEnvironment
import ai.hans.standard.phone.capabilities.AndroidSpecialAccess
import ai.hans.standard.phone.capabilities.CapabilityRegistry
import android.app.AlarmManager
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat

/** Fresh, fail-closed Android probes used immediately before every automation run. */
class AndroidAutomationLiveEnvironmentSource(
    context: Context,
    private val hostProvider: () -> AndroidCodexSessionHost,
    private val additionalCapabilityIds: () -> Set<String> = { emptySet() },
) : AutomationLiveEnvironmentSource {
    private val appContext = context.applicationContext

    override fun snapshot(): AutomationExecutionEnvironment {
        val hostSnapshot = runCatching { hostProvider().snapshot() }.getOrNull()
        val runtimeReady = hostSnapshot?.runtimePhase == ClientRuntimePhase.READY
        val authenticated = runtimeReady &&
            hostSnapshot?.session?.account?.phase == AccountPhase.SIGNED_IN
        val publicCapabilities = runCatching {
            CapabilityRegistry(AndroidCapabilityEnvironment(appContext))
                .snapshot()
                .available
                .mapTo(linkedSetOf()) { live -> live.descriptor.id.value }
        }.getOrDefault(emptySet())
        val capabilities = buildSet {
            if (runtimeReady) add(AutomationCapabilityId.CODEX_APP_SERVER)
            (publicCapabilities + additionalCapabilityIds())
                .mapNotNullTo(this) { value ->
                    runCatching { AutomationCapabilityId(value) }.getOrNull()
                }
        }
        return AutomationExecutionEnvironment(
            availableCapabilities = capabilities,
            grantedPermissions = grantedPermissions(),
            codexAuthenticated = authenticated,
            networkAvailable = validatedNetworkAvailable(),
            deviceUnlocked = deviceUnlocked(),
            // Durable exact-run receipts are injected by AutomationRuntimeOwner, never here.
            confirmedRuns = emptySet(),
        )
    }

    private fun grantedPermissions(): Set<AutomationPermissionId> = buildSet {
        val requested = runCatching {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(
                appContext.packageName,
                PackageManager.GET_PERMISSIONS,
            ).requestedPermissions.orEmpty()
        }.getOrDefault(emptyArray())
        requested.forEach { permission ->
            if (ContextCompat.checkSelfPermission(appContext, permission) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                runCatching { add(AutomationPermissionId(permission)) }
            }
        }
        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
        if (runCatching { alarmManager?.canScheduleExactAlarms() == true }.getOrDefault(false)) {
            add(AutomationPermissionId.SCHEDULE_EXACT_ALARM)
        }
        val environment = AndroidCapabilityEnvironment(appContext)
        SPECIAL_ACCESS_IDS.forEach { (access, id) ->
            if (runCatching { environment.hasSpecialAccess(access) }.getOrDefault(false)) {
                add(id)
            }
        }
    }

    private fun validatedNetworkAvailable(): Boolean = runCatching {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
            ?: return@runCatching false
        val active = connectivity.activeNetwork ?: return@runCatching false
        val capabilities = connectivity.getNetworkCapabilities(active) ?: return@runCatching false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    private fun deviceUnlocked(): Boolean = runCatching {
        val keyguard = appContext.getSystemService(KeyguardManager::class.java)
        keyguard != null && AndroidKeyguardUnlockPolicy.isUiUnlocked(
            deviceLocked = keyguard.isDeviceLocked,
            keyguardLocked = keyguard.isKeyguardLocked,
        )
    }.getOrDefault(false)

    companion object {
        val ACCESSIBILITY_PERMISSION =
            AutomationPermissionId("android.special.accessibility_service")
        val NOTIFICATION_LISTENER_PERMISSION =
            AutomationPermissionId("android.special.notification_listener")
        val HOME_ROLE_PERMISSION = AutomationPermissionId("android.role.home")

        private val SPECIAL_ACCESS_IDS = mapOf(
            AndroidSpecialAccess.ACCESSIBILITY_SERVICE to ACCESSIBILITY_PERMISSION,
            AndroidSpecialAccess.NOTIFICATION_LISTENER to NOTIFICATION_LISTENER_PERMISSION,
            AndroidSpecialAccess.HOME_ROLE to HOME_ROLE_PERMISSION,
        )
    }
}
