package ai.hans.standard.phone.keys

import android.view.KeyEvent

enum class ActionKeyTrigger {
    /** A completed press toggles dictation. */
    PRESS,

    /** Down starts dictation and the corresponding up event stops it. */
    HOLD_TO_TALK,
}

enum class KeySemanticAction {
    DICTATION,
    // Persisted identifier: retain it across updates so existing physical-key mappings
    // keep working. The preset action now toggles Luna Max and Astra Ultra.
    TOGGLE_LUNA_MAX_SOL_ULTRA,
}

enum class HansModelPreset(val model: String, val effort: String) {
    LUNA_MAX(model = "gpt-6-luna", effort = "max"),
    ASTRA_ULTRA(model = "gpt-6-astra", effort = "ultra"),
}

object HansModelPresetToggle {
    fun next(current: HansModelPreset): HansModelPreset = when (current) {
        HansModelPreset.LUNA_MAX -> HansModelPreset.ASTRA_ULTRA
        HansModelPreset.ASTRA_ULTRA -> HansModelPreset.LUNA_MAX
    }
}

data class ActionKeyMapping(
    val mappingId: String,
    val device: PhysicalKeyDeviceSelector,
    val source: Int,
    val scanCode: Int,
    val keyCode: Int,
    val metaState: Int,
    val trigger: ActionKeyTrigger,
    val action: KeySemanticAction,
) {
    init {
        require(MAPPING_ID.matches(mappingId)) { "Invalid mapping id" }
        require(scanCode >= 0)
        require(keyCode >= 0)
        require(scanCode != 0 || keyCode != KeyEvent.KEYCODE_UNKNOWN) {
            "A mapping needs an observable scan code or key code"
        }
        require(action == KeySemanticAction.DICTATION || trigger == ActionKeyTrigger.PRESS) {
            "Preset switching is a completed-press action"
        }
    }

    fun matches(event: ObservableAndroidKeyEvent, includeMetaState: Boolean = true): Boolean =
        device.matches(event.physicalDevice) &&
            source == event.source &&
            scanCode == event.scanCode &&
            keyCode == event.keyCode &&
            (!includeMetaState || metaState == event.metaState)

    private companion object {
        val MAPPING_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    }
}

/** Immutable editable set. Upsert and remove are the persistence/UI boundary. */
class ActionKeyMappingSet private constructor(
    val mappings: List<ActionKeyMapping>,
) {
    fun upsert(mapping: ActionKeyMapping): ActionKeyMappingSet {
        val updated = mappings.filterNot { it.mappingId == mapping.mappingId } + mapping
        requireNoAmbiguousGestures(updated)
        return ActionKeyMappingSet(updated.sortedBy { it.mappingId })
    }

    fun remove(mappingId: String): ActionKeyMappingSet = ActionKeyMappingSet(
        mappings.filterNot { it.mappingId == mappingId },
    )

    companion object {
        fun empty(): ActionKeyMappingSet = ActionKeyMappingSet(emptyList())

        fun of(mappings: List<ActionKeyMapping>): ActionKeyMappingSet {
            requireNoAmbiguousGestures(mappings)
            require(mappings.map { it.mappingId }.distinct().size == mappings.size) {
                "Mapping ids must be unique"
            }
            return ActionKeyMappingSet(mappings.sortedBy { it.mappingId })
        }

        private fun requireNoAmbiguousGestures(mappings: List<ActionKeyMapping>) {
            val gestures = mappings.map {
                listOf(
                    it.device.vendorId,
                    it.device.productId,
                    it.device.descriptorSha256,
                    it.source,
                    it.scanCode,
                    it.keyCode,
                    it.metaState,
                )
            }
            require(gestures.distinct().size == gestures.size) {
                "Two actions cannot own the same physical key gesture"
            }
        }
    }
}

enum class ReservedHardwareKey(val userFacingReason: String) {
    BACK("Android may reserve Back for navigation"),
    HOME("Android normally intercepts Home before an app can map it"),
    POWER("Android reserves Power for system power behavior"),
    VOLUME("Android and foreground audio policy may reserve volume keys"),
}

object ReservedHardwareKeyPolicy {
    fun classify(keyCode: Int): ReservedHardwareKey? = when (keyCode) {
        KeyEvent.KEYCODE_BACK -> ReservedHardwareKey.BACK
        KeyEvent.KEYCODE_HOME -> ReservedHardwareKey.HOME
        KeyEvent.KEYCODE_POWER -> ReservedHardwareKey.POWER
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE,
        -> ReservedHardwareKey.VOLUME
        else -> null
    }
}

sealed interface ActionKeyCommand {
    data object ToggleDictation : ActionKeyCommand
    data object StartDictation : ActionKeyCommand
    data object StopDictation : ActionKeyCommand
    data object ToggleLunaMaxSolUltra : ActionKeyCommand
}

/**
 * Standard-edition capability contract. Commands arise only from KeyEvents
 * delivered to a Hans component through public Android APIs. Starting and
 * stopping dictation therefore needs neither root nor global key interception.
 */
object RootFreeActionKeyCapability {
    const val REQUIRES_ROOT: Boolean = false
    const val REQUIRES_USER_ENABLED_ACCESSIBILITY: Boolean = true
    const val CAN_TRIGGER_DICTATION_ACROSS_APPS: Boolean = true
    const val CAN_REMAP_OTHER_APPS_GLOBALLY: Boolean = false
    const val CAN_OBSERVE_SYSTEM_INTERCEPTED_KEYS: Boolean = false
}
