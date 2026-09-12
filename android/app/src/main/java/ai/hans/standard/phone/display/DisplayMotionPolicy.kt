package ai.hans.standard.phone.display

/** App-local rendering preference, never a request to change the panel or Android settings. */
enum class DisplayMotionMode(val storageValue: String) {
    AUTOMATIC("automatic"),
    E_INK("e_ink"),
    STANDARD("standard"),
    ;

    companion object {
        fun fromStorage(value: String?): DisplayMotionMode =
            entries.firstOrNull { it.storageValue == value } ?: AUTOMATIC
    }
}

data class DisplayMotionDecision(
    val isEink: Boolean,
    val animateHome: Boolean,
    val animateLiveRecording: Boolean,
    val systemAnimationsEnabled: Boolean,
)

/**
 * Public Android device identity can positively identify the known MP01, not arbitrary panel
 * technology. Other devices use ordinary motion by default; owners of other E-Ink devices can
 * select E_INK explicitly. Refresh rate, vendor-package trust and socket access are not evidence
 * of whether the physical display is E-Ink.
 */
object DisplayMotionPolicy {
    fun isEink(mode: DisplayMotionMode, identity: AndroidDeviceIdentity): Boolean = when (mode) {
        DisplayMotionMode.AUTOMATIC -> Mp01DisplayTrustPolicy.isMp01(identity)
        DisplayMotionMode.E_INK -> true
        DisplayMotionMode.STANDARD -> false
    }

    fun decide(
        mode: DisplayMotionMode,
        identity: AndroidDeviceIdentity,
        systemAnimationsEnabled: Boolean,
        resumed: Boolean,
        windowFocused: Boolean,
    ): DisplayMotionDecision {
        val eink = isEink(mode, identity)
        val foregroundMotion = systemAnimationsEnabled && resumed && windowFocused
        return DisplayMotionDecision(
            isEink = eink,
            animateHome = foregroundMotion && !eink,
            // Live is explicit, active microphone feedback, not an idle-home animation.
            animateLiveRecording = foregroundMotion,
            systemAnimationsEnabled = systemAnimationsEnabled,
        )
    }
}
