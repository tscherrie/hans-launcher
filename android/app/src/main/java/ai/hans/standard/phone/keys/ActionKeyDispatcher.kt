package ai.hans.standard.phone.keys

enum class ActionKeyIgnoreReason {
    NO_MAPPING,
    AMBIGUOUS_MAPPING,
    RESERVED_KEY,
    REPEAT,
    DUPLICATE,
    DEBOUNCED,
    UP_WITHOUT_ACCEPTED_DOWN,
    /** Stock MP01 reserves holds of its AREFRESH button for display settings. */
    STOCK_MP01_PRESS_TOO_LONG,
}

sealed interface ActionKeyDispatchResult {
    data class AwaitingRelease(val mappingId: String) : ActionKeyDispatchResult

    data class Command(
        val mappingId: String,
        val command: ActionKeyCommand,
    ) : ActionKeyDispatchResult

    data class Ignored(val reason: ActionKeyIgnoreReason) : ActionKeyDispatchResult
}

/**
 * Idempotent foreground dispatcher. It never registers a global hook. Callers
 * execute emitted dictation commands using the ordinary microphone permission.
 */
class ActionKeyDispatcher(
    mappings: ActionKeyMappingSet,
    private val debounceMillis: Long = 250L,
) {
    init {
        require(debounceMillis >= 0)
    }

    private var mappingSet: ActionKeyMappingSet = mappings
    private val activePresses = mutableMapOf<String, ActivePress>()
    private val lastCompletedAt = mutableMapOf<String, Long>()

    @Synchronized
    fun replaceMappings(mappings: ActionKeyMappingSet): List<ActionKeyDispatchResult.Command> {
        val oldMappings = mappingSet.mappings.associateBy { it.mappingId }
        val newMappings = mappings.mappings.associateBy { it.mappingId }
        val changedOrRemovedActiveIds = activePresses.keys.filter { mappingId ->
            oldMappings[mappingId] != newMappings[mappingId]
        }
        val cleanupCommands = changedOrRemovedActiveIds.mapNotNull { mappingId ->
            oldMappings[mappingId]
                ?.takeIf { it.trigger == ActionKeyTrigger.HOLD_TO_TALK }
                ?.let {
                    ActionKeyDispatchResult.Command(
                        mappingId = mappingId,
                        command = ActionKeyCommand.StopDictation,
                    )
                }
        }
        mappingSet = mappings
        activePresses.keys.removeAll(changedOrRemovedActiveIds.toSet())
        lastCompletedAt.keys.retainAll(
            newMappings.keys.filter { oldMappings[it] == newMappings[it] }.toSet(),
        )
        return cleanupCommands
    }

    @Synchronized
    fun onDeliveredEvent(event: ObservableAndroidKeyEvent): ActionKeyDispatchResult {
        ReservedHardwareKeyPolicy.classify(event.keyCode)?.let {
            return ActionKeyDispatchResult.Ignored(ActionKeyIgnoreReason.RESERVED_KEY)
        }
        val candidates = if (event.phase == ObservableKeyPhase.UP) {
            mappingSet.mappings.filter { mapping ->
                mapping.matches(event, includeMetaState = false) &&
                    activePresses[mapping.mappingId]?.metaState == mapping.metaState
            }
        } else {
            mappingSet.mappings.filter { it.matches(event) }
        }
        if (candidates.isEmpty()) {
            return ActionKeyDispatchResult.Ignored(ActionKeyIgnoreReason.NO_MAPPING)
        }
        if (candidates.size != 1) {
            return ActionKeyDispatchResult.Ignored(ActionKeyIgnoreReason.AMBIGUOUS_MAPPING)
        }
        val mapping = candidates.single()
        return when (event.phase) {
            ObservableKeyPhase.DOWN -> onDown(mapping, event)
            ObservableKeyPhase.UP -> onUp(mapping, event)
        }
    }

    private fun onDown(
        mapping: ActionKeyMapping,
        event: ObservableAndroidKeyEvent,
    ): ActionKeyDispatchResult {
        if (event.repeatCount > 0 || event.isLongPress) {
            return ActionKeyDispatchResult.Ignored(ActionKeyIgnoreReason.REPEAT)
        }
        activePresses[mapping.mappingId]?.let { active ->
            val staleStockPress = mapping.trigger == ActionKeyTrigger.PRESS &&
                Mp01VendorActionConflictResolver.isStockActionButton(mapping) &&
                event.downTimeMillis != active.downTimeMillis &&
                event.eventTimeMillis >= active.downTimeMillis &&
                event.eventTimeMillis - active.downTimeMillis >=
                Mp01VendorActionConflictResolver.STOCK_LONG_PRESS_MILLIS
            if (staleStockPress) {
                // PhoneWindowManager may move focus to Minimal's settings at
                // 400 ms, so the corresponding UP never reaches Hans. The next
                // real short press must recover instead of remaining DUPLICATE.
                activePresses.remove(mapping.mappingId)
            } else {
                return ActionKeyDispatchResult.Ignored(ActionKeyIgnoreReason.DUPLICATE)
            }
        }
        val lastCompleted = lastCompletedAt[mapping.mappingId]
        if (
            lastCompleted != null &&
            (
                event.eventTimeMillis < lastCompleted ||
                    event.eventTimeMillis - lastCompleted < debounceMillis
                )
        ) {
            return ActionKeyDispatchResult.Ignored(ActionKeyIgnoreReason.DEBOUNCED)
        }
        activePresses[mapping.mappingId] = ActivePress(
            downTimeMillis = event.downTimeMillis,
            metaState = mapping.metaState,
        )
        return when (mapping.trigger) {
            ActionKeyTrigger.PRESS -> ActionKeyDispatchResult.AwaitingRelease(mapping.mappingId)
            ActionKeyTrigger.HOLD_TO_TALK -> ActionKeyDispatchResult.Command(
                mappingId = mapping.mappingId,
                command = ActionKeyCommand.StartDictation,
            )
        }
    }

    private fun onUp(
        mapping: ActionKeyMapping,
        event: ObservableAndroidKeyEvent,
    ): ActionKeyDispatchResult {
        val press = activePresses[mapping.mappingId]
            ?: return ActionKeyDispatchResult.Ignored(
                ActionKeyIgnoreReason.UP_WITHOUT_ACCEPTED_DOWN,
            )
        if (press.downTimeMillis != event.downTimeMillis) {
            return ActionKeyDispatchResult.Ignored(
                ActionKeyIgnoreReason.UP_WITHOUT_ACCEPTED_DOWN,
            )
        }
        activePresses.remove(mapping.mappingId)
        lastCompletedAt[mapping.mappingId] = event.eventTimeMillis
        if (
            mapping.trigger == ActionKeyTrigger.PRESS &&
            Mp01VendorActionConflictResolver.isStockActionButton(mapping) &&
            event.eventTimeMillis - press.downTimeMillis >=
            Mp01VendorActionConflictResolver.STOCK_LONG_PRESS_MILLIS
        ) {
            return ActionKeyDispatchResult.Ignored(
                ActionKeyIgnoreReason.STOCK_MP01_PRESS_TOO_LONG,
            )
        }
        val command = when (mapping.trigger) {
            ActionKeyTrigger.HOLD_TO_TALK -> ActionKeyCommand.StopDictation
            ActionKeyTrigger.PRESS -> when (mapping.action) {
                KeySemanticAction.DICTATION -> ActionKeyCommand.ToggleDictation
                KeySemanticAction.TOGGLE_LUNA_MAX_SOL_ULTRA ->
                    ActionKeyCommand.ToggleLunaMaxSolUltra
            }
        }
        return ActionKeyDispatchResult.Command(mapping.mappingId, command)
    }

    private data class ActivePress(
        val downTimeMillis: Long,
        val metaState: Int,
    )
}
