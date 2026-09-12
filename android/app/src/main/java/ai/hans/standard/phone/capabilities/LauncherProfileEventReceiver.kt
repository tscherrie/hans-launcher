package ai.hans.standard.phone.capabilities

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserHandle

enum class LauncherProfileEventKind {
    ADDED,
    AVAILABLE,
    ACCESSIBLE,
    UNAVAILABLE,
    INACCESSIBLE,
    REMOVED,
}

data class LauncherProfileEvent(
    val kind: LauncherProfileEventKind,
    /** Used only as an event correlation hint; the launcher always rereads all public state. */
    val user: UserHandle,
) {
    val requiresImmediateInventoryPurge: Boolean
        get() = kind in setOf(
            LauncherProfileEventKind.UNAVAILABLE,
            LauncherProfileEventKind.INACCESSIBLE,
            LauncherProfileEventKind.REMOVED,
        )
}

/**
 * Context-registered only. Even if a vendor permits a spoofed action, the payload never changes
 * state directly: it merely triggers a fresh role-, permission-, and profile-gated platform read.
 */
class LauncherProfileEventReceiver(
    private val onProfileEvent: (LauncherProfileEvent) -> Unit,
) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        launcherProfileEventOrNull(intent)?.let(onProfileEvent)
    }

    companion object {
        fun intentFilter(): IntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PROFILE_ACCESSIBLE)
            addAction(Intent.ACTION_PROFILE_INACCESSIBLE)
            addAction(Intent.ACTION_PROFILE_ADDED)
            addAction(Intent.ACTION_PROFILE_REMOVED)
            addAction(Intent.ACTION_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
        }
    }
}

internal fun launcherProfileEventOrNull(intent: Intent?): LauncherProfileEvent? {
    val kind = when (intent?.action) {
        Intent.ACTION_PROFILE_ADDED -> LauncherProfileEventKind.ADDED
        Intent.ACTION_PROFILE_AVAILABLE -> LauncherProfileEventKind.AVAILABLE
        Intent.ACTION_PROFILE_ACCESSIBLE -> LauncherProfileEventKind.ACCESSIBLE
        Intent.ACTION_PROFILE_UNAVAILABLE -> LauncherProfileEventKind.UNAVAILABLE
        Intent.ACTION_PROFILE_INACCESSIBLE -> LauncherProfileEventKind.INACCESSIBLE
        Intent.ACTION_PROFILE_REMOVED -> LauncherProfileEventKind.REMOVED
        else -> return null
    }
    val user = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_USER, UserHandle::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_USER) as? UserHandle
    } ?: return null
    return LauncherProfileEvent(kind = kind, user = user)
}
