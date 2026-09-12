package ai.hans.standard.phone.display

import android.content.Context
import android.content.SharedPreferences

/**
 * Device-local display rendering preference. It deliberately is not a portable account setting:
 * restoring a phone backup onto a different panel must not silently force that panel's old mode.
 */
class DisplayMotionPreferencesStore internal constructor(
    private val preferences: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    @Synchronized
    fun read(): DisplayMotionMode = runCatching {
        DisplayMotionMode.fromStorage(preferences.getString(KEY_MODE, null))
    }.getOrDefault(DisplayMotionMode.AUTOMATIC)

    @Synchronized
    fun save(mode: DisplayMotionMode): DisplayMotionMode {
        check(preferences.edit().putString(KEY_MODE, mode.storageValue).commit()) {
            "Could not persist display rendering mode"
        }
        return read().also { check(it == mode) { "Display rendering mode was not confirmed by storage" } }
    }

    companion object {
        const val PREFERENCES_NAME = "hans_display_motion_v1"
        private const val KEY_MODE = "display_motion_mode"
    }
}
