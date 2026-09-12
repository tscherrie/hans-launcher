package ai.hans.standard.phone.keys

/**
 * Device-local evidence gathered during onboarding. No MP01 scan code is
 * compiled into Hans as a universal fact; the adapter is inert until an exact
 * physical device selector and empirical scan code have been configured.
 */
data class Mp01EmpiricalKeyProfile(
    val device: PhysicalKeyDeviceSelector,
    val actionScanCode: Int,
    val symScanCode: Int?,
) {
    init {
        require(actionScanCode > 0)
        symScanCode?.let {
            require(it > 0)
            require(it != actionScanCode)
        }
    }
}

enum class Mp01ObservedControl {
    ACTION_KEY,
    SYM_KEY,
}

class Mp01OptionalKeyAdapter(
    private val profile: Mp01EmpiricalKeyProfile?,
) {
    fun recognize(event: ObservableAndroidKeyEvent): Mp01ObservedControl? {
        val configured = profile ?: return null
        if (!configured.device.matches(event.physicalDevice)) return null
        return when (event.scanCode) {
            configured.actionScanCode -> Mp01ObservedControl.ACTION_KEY
            configured.symScanCode -> Mp01ObservedControl.SYM_KEY
            else -> null
        }
    }

    /** Builds a suggestion only from the delivered event that proved the key. */
    fun mappingFromObservedDown(
        event: ObservableAndroidKeyEvent,
        mappingId: String,
        actionTrigger: ActionKeyTrigger,
    ): ActionKeyMapping? {
        if (event.phase != ObservableKeyPhase.DOWN || event.repeatCount != 0) return null
        val control = recognize(event) ?: return null
        val device = event.physicalDevice ?: return null
        return ActionKeyMapping(
            mappingId = mappingId,
            device = device.selector(),
            source = event.source,
            scanCode = event.scanCode,
            keyCode = event.keyCode,
            metaState = event.metaState,
            trigger = if (control == Mp01ObservedControl.SYM_KEY) {
                ActionKeyTrigger.PRESS
            } else {
                actionTrigger
            },
            action = if (control == Mp01ObservedControl.SYM_KEY) {
                KeySemanticAction.TOGGLE_LUNA_MAX_SOL_ULTRA
            } else {
                KeySemanticAction.DICTATION
            },
        )
    }
}
