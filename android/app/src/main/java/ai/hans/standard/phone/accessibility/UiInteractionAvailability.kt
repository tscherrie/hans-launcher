package ai.hans.standard.phone.accessibility

/**
 * Whether Hans may currently inspect or operate the foreground Android UI.
 *
 * This is deliberately separate from Accessibility special-access state: a connected service
 * still must not inspect or act through the keyguard or while the display is non-interactive.
 */
enum class UiInteractionAvailability {
    AVAILABLE,
    DEVICE_LOCKED,
    SCREEN_NOT_INTERACTIVE,
    DEVICE_LOCKED_AND_SCREEN_NOT_INTERACTIVE,
    STATE_UNAVAILABLE,
    ;

    val isAvailable: Boolean
        get() = this == AVAILABLE

    companion object {
        fun fromPlatformState(
            deviceLocked: Boolean,
            screenInteractive: Boolean,
        ): UiInteractionAvailability = when {
            deviceLocked && !screenInteractive -> DEVICE_LOCKED_AND_SCREEN_NOT_INTERACTIVE
            deviceLocked -> DEVICE_LOCKED
            !screenInteractive -> SCREEN_NOT_INTERACTIVE
            else -> AVAILABLE
        }
    }
}

fun interface UiInteractionAvailabilityProbe {
    /** Implementations fail closed with [UiInteractionAvailability.STATE_UNAVAILABLE]. */
    fun current(): UiInteractionAvailability

    companion object {
        val FAIL_CLOSED = UiInteractionAvailabilityProbe {
            UiInteractionAvailability.STATE_UNAVAILABLE
        }
    }
}
