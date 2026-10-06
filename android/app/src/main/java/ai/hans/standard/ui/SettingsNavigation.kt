package ai.hans.standard.ui

import ai.hans.standard.R
import ai.hans.standard.remotecontrol.DesktopRemoteAccessPolicy
import androidx.annotation.StringRes

/** Stable navigation IDs are never translated; titles are resolved only at presentation time. */
internal enum class SettingsGroup(val id: String, @StringRes val titleResource: Int) {
    RUNTIME("runtime", R.string.presentation_settings_runtime),
    SPEECH("speech", R.string.presentation_settings_speech),
    INPUT("input", R.string.presentation_settings_input),
    PERSONAL("personal", R.string.presentation_settings_personal),
    PERMISSIONS("permissions", R.string.presentation_settings_permissions),
    REMOTE_CONTROL("remote_control", R.string.presentation_settings_remote_control),
    ADVANCED_WORK("advanced_work", R.string.presentation_settings_advanced_work),
    MAINTENANCE("maintenance", R.string.presentation_settings_maintenance);

    val available: Boolean
        get() = this != ADVANCED_WORK && (this != REMOTE_CONTROL || DesktopRemoteAccessPolicy.enabled)

    /** Restored legacy IDs redirect; hidden incoming remote access never becomes reachable. */
    val destination: SettingsGroup?
        get() = when {
            this == ADVANCED_WORK -> MAINTENANCE
            available -> this
            else -> null
        }

    companion object {
        val availableGroups: List<SettingsGroup>
            get() = entries.filter { it.available }
    }
}

/** Ephemeral navigation only: each newly opened menu starts at the group overview. */
internal data class SettingsNavigation(private val requestedGroup: SettingsGroup? = null) {
    // A stale/restored destination must not bypass an immutable product capability gate.
    val group: SettingsGroup?
        get() = requestedGroup?.destination

    @get:StringRes
    val titleResource: Int
        get() = group?.titleResource ?: R.string.presentation_menu

    fun open(group: SettingsGroup): SettingsNavigation = SettingsNavigation(group.destination)

    fun backToGroups(): SettingsNavigation = SettingsNavigation()
}
