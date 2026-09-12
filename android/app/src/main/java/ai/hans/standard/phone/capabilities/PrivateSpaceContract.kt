package ai.hans.standard.phone.capabilities

import android.content.Context
import android.content.SharedPreferences

/**
 * Public-API availability for Android's Private Space launcher surface. The states intentionally
 * distinguish "not configured" from "Hans is not allowed to know"; callers must never infer a
 * hidden profile while the HOME role or ACCESS_HIDDEN_PROFILES contract is absent.
 */
enum class PrivateSpaceAvailability {
    UNSUPPORTED_PLATFORM,
    HOME_ROLE_REQUIRED,
    HIDDEN_PROFILE_ACCESS_REQUIRED,
    NOT_CONFIGURED,
    AVAILABLE,
}

data class PrivateSpaceSnapshot(
    val availability: PrivateSpaceAvailability,
    /** Present only for an accessible Android private profile and always process-local/opaque. */
    val profile: LaunchProfileState? = null,
    /** Android 16 system preference; effective only while the accessible profile is locked. */
    val entrypointHiddenWhenLocked: Boolean = false,
    /** True only when Android 16 returned its public Private Space settings IntentSender. */
    val settingsAvailable: Boolean = false,
) {
    init {
        if (availability == PrivateSpaceAvailability.AVAILABLE) {
            require(profile?.type == LaunchProfileType.PRIVATE) {
                "An available Private Space requires an opaque private profile"
            }
        } else {
            require(profile == null) {
                "An inaccessible Private Space must not expose a profile handle"
            }
            require(!entrypointHiddenWhenLocked) {
                "An inaccessible Private Space must not expose its system entrypoint preference"
            }
        }
    }
}

enum class PrivateSpaceQuietModeOutcome {
    /** The state reread after the Android request already matches the requested lock state. */
    CONFIRMED,

    /** Android accepted the asynchronous request; a profile broadcast will confirm completion. */
    PENDING,

    /** Android displayed its credential confirmation while unlocking. */
    AUTHENTICATION_REQUIRED,

    /** Android declined the request without beginning credential confirmation. */
    REJECTED,
}

data class PrivateSpaceQuietModeResult(
    val outcome: PrivateSpaceQuietModeOutcome,
    /** Authoritative live reread performed after requestQuietModeEnabled returned. */
    val snapshot: PrivateSpaceSnapshot,
)

interface PrivateSpaceContainerVisibilityStore {
    fun isVisible(): Boolean
    fun setVisible(visible: Boolean): Boolean
}

/** Device-local preference. It stores no profile identity, app inventory, or Android UserHandle. */
class SharedPreferencesPrivateSpaceContainerVisibilityStore internal constructor(
    private val preferences: SharedPreferences,
) : PrivateSpaceContainerVisibilityStore {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    @Synchronized
    override fun isVisible(): Boolean = preferences.getBoolean(KEY_VISIBLE, true)

    @Synchronized
    override fun setVisible(visible: Boolean): Boolean =
        preferences.edit().putBoolean(KEY_VISIBLE, visible).commit() &&
            preferences.getBoolean(KEY_VISIBLE, !visible) == visible

    companion object {
        const val PREFERENCES_NAME = "hans_private_space_visibility_v1"
        private const val KEY_VISIBLE = "container_visible"
    }
}
