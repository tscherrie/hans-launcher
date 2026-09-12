package ai.hans.standard.phone.capabilities

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager

/**
 * Narrow public-Android seam for launcher profiles. Instrumentation can provide a deterministic
 * fake Private Space on AOSP images that do not expose a configured private profile.
 */
interface AndroidLauncherProfilePlatform {
    val apiLevel: Int

    fun currentUser(): UserHandle
    fun accessibleProfiles(): List<UserHandle>
    fun profileType(user: UserHandle, current: UserHandle): LaunchProfileType
    fun isProfileLocked(user: UserHandle, current: UserHandle): Boolean
    fun hasHomeRole(): Boolean
    fun hasHiddenProfilesPermission(): Boolean
    fun requestQuietModeEnabled(locked: Boolean, user: UserHandle): Boolean
    fun privateSpaceEntrypointHiddenWhenLocked(user: UserHandle): Boolean
    fun privateSpaceSettingsAvailable(): Boolean
    fun openPrivateSpaceSettings(): Boolean
}

/** API-safe seam used by the callback and by deterministic API-matrix tests. */
internal fun interface LauncherUserConfigRefreshSignal {
    fun dispatchRefreshSignal()
}

internal object LauncherUserConfigCallbackFactory {
    fun createIfSupported(
        apiLevel: Int = Build.VERSION.SDK_INT,
        onChanged: () -> Unit,
    ): LauncherApps.Callback? {
        if (apiLevel < 36 || Build.VERSION.SDK_INT < 36) return null
        return AndroidPrivateSpaceApi36.userConfigCallback(onChanged)
    }
}

internal class FrameworkAndroidLauncherProfilePlatform(
    context: Context,
) : AndroidLauncherProfilePlatform {
    private val appContext = context.applicationContext
    private val launcherApps: LauncherApps?
        get() = appContext.getSystemService(LauncherApps::class.java)
    private val userManager: UserManager?
        get() = appContext.getSystemService(UserManager::class.java)

    override val apiLevel: Int
        get() = Build.VERSION.SDK_INT

    override fun currentUser(): UserHandle = Process.myUserHandle()

    override fun accessibleProfiles(): List<UserHandle> = runCatching {
        launcherApps?.profiles.orEmpty()
    }.getOrDefault(emptyList())

    override fun profileType(user: UserHandle, current: UserHandle): LaunchProfileType {
        if (user == current) return LaunchProfileType.PERSONAL
        if (Build.VERSION.SDK_INT < 35) {
            // Android 12-14 expose associated launcher profiles through the managed/work contract.
            return LaunchProfileType.WORK
        }
        return when (runCatching { launcherApps?.getLauncherUserInfo(user)?.userType }.getOrNull()) {
            UserManager.USER_TYPE_PROFILE_PRIVATE -> LaunchProfileType.PRIVATE
            UserManager.USER_TYPE_PROFILE_MANAGED -> LaunchProfileType.WORK
            UserManager.USER_TYPE_PROFILE_CLONE -> LaunchProfileType.CLONE
            else -> LaunchProfileType.OTHER
        }
    }

    override fun isProfileLocked(user: UserHandle, current: UserHandle): Boolean {
        if (user == current) return false
        val manager = userManager ?: return true
        return runCatching {
            manager.isQuietModeEnabled(user) || !manager.isUserUnlocked(user)
        }.getOrDefault(true)
    }

    override fun hasHomeRole(): Boolean = runCatching {
        appContext.getSystemService(RoleManager::class.java)
            ?.let { it.isRoleAvailable(RoleManager.ROLE_HOME) && it.isRoleHeld(RoleManager.ROLE_HOME) }
            ?: false
    }.getOrDefault(false)

    override fun hasHiddenProfilesPermission(): Boolean = apiLevel >= 35 &&
        appContext.checkSelfPermission(Manifest.permission.ACCESS_HIDDEN_PROFILES) ==
        PackageManager.PERMISSION_GRANTED

    override fun requestQuietModeEnabled(locked: Boolean, user: UserHandle): Boolean =
        userManager?.requestQuietModeEnabled(locked, user) ?: false

    override fun privateSpaceEntrypointHiddenWhenLocked(user: UserHandle): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        val manager = launcherApps ?: return true
        return runCatching {
            AndroidPrivateSpaceApi36.entrypointHiddenWhenLocked(manager, user)
        }.getOrDefault(true)
    }

    override fun privateSpaceSettingsAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < 36 || !hasHomeRole() || !hasHiddenProfilesPermission()) {
            return false
        }
        return runCatching { launcherApps?.privateSpaceSettingsIntent != null }.getOrDefault(false)
    }

    override fun openPrivateSpaceSettings(): Boolean {
        if (Build.VERSION.SDK_INT < 36 || !hasHomeRole() || !hasHiddenProfilesPermission()) {
            return false
        }
        val sender = runCatching { launcherApps?.privateSpaceSettingsIntent }.getOrNull()
            ?: return false
        return try {
            // The adapter deliberately retains only an application Context. Preserve that
            // lifecycle boundary while supplying the public new-task launch flag explicitly.
            appContext.startIntentSender(
                sender,
                null,
                Intent.FLAG_ACTIVITY_NEW_TASK,
                Intent.FLAG_ACTIVITY_NEW_TASK,
                0,
            )
            true
        } catch (_: IntentSender.SendIntentException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }
}
