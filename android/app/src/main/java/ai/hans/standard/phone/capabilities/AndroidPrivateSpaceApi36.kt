package ai.hans.standard.phone.capabilities

import android.content.pm.LauncherApps
import android.content.pm.LauncherUserInfo
import android.os.UserHandle
import androidx.annotation.RequiresApi

/**
 * API-36-only linkage. No class loaded by the Android 12-15 path mentions LauncherUserInfo's
 * API-36 members or Callback.onUserConfigChanged in its own method signatures.
 */
@RequiresApi(36)
internal object AndroidPrivateSpaceApi36 {
    fun entrypointHiddenWhenLocked(
        launcherApps: LauncherApps,
        user: UserHandle,
    ): Boolean {
        val info = launcherApps.getLauncherUserInfo(user) ?: return true
        return info.userConfig.getBoolean(
            LauncherUserInfo.PRIVATE_SPACE_ENTRYPOINT_HIDDEN,
            false,
        )
    }

    fun userConfigCallback(onChanged: () -> Unit): LauncherApps.Callback =
        Api36LauncherUserConfigCallback(onChanged)
}

/** Kept separate so ART on API 31 never needs to resolve the API-36 override signature. */
@RequiresApi(36)
internal class Api36LauncherUserConfigCallback(
    private val onChanged: () -> Unit,
) : LauncherApps.Callback(), LauncherUserConfigRefreshSignal {
    override fun onPackageRemoved(packageName: String, user: UserHandle) = Unit
    override fun onPackageAdded(packageName: String, user: UserHandle) = Unit
    override fun onPackageChanged(packageName: String, user: UserHandle) = Unit

    override fun onPackagesAvailable(
        packageNames: Array<out String>,
        user: UserHandle,
        replacing: Boolean,
    ) = Unit

    override fun onPackagesUnavailable(
        packageNames: Array<out String>,
        user: UserHandle,
        replacing: Boolean,
    ) = Unit

    override fun onUserConfigChanged(userInfo: LauncherUserInfo) {
        dispatchRefreshSignal()
    }

    /** Deterministic seam because platform LauncherUserInfo instances cannot be constructed. */
    override fun dispatchRefreshSignal() {
        onChanged()
    }
}
