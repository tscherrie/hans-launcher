package ai.hans.standard.phone.keys

/**
 * Foreground-only ownership of a user-captured model shortcut before the IME.
 * The host must stop routing new events when its window loses focus. A non-null
 * result always consumes the event, even when debounce/repeat suppresses its
 * command, so the IME never receives half of a shortcut press.
 */
class ForegroundModelKeyRouter {
    private var mappings = ActionKeyMappingSet.empty()
    private var dispatcher = ActionKeyDispatcher(mappings)
    private val ownedPresses = mutableMapOf<String, OwnedPress>()

    @Synchronized
    fun replaceMappings(next: ActionKeyMappingSet) {
        val modelMappings = ActionKeyMappingSet.of(
            next.mappings.filter {
                it.action == KeySemanticAction.TOGGLE_LUNA_MAX_SOL_ULTRA &&
                    ReservedHardwareKeyPolicy.classify(it.keyCode) == null
            }.sortedBy(ActionKeyMapping::mappingId),
        )
        // Routine policy refreshes must not lose the tail of an accepted press.
        if (modelMappings.mappings == mappings.mappings) return
        mappings = modelMappings
        clearActivePresses()
    }

    @Synchronized
    fun clearActivePresses() {
        ownedPresses.clear()
        dispatcher = ActionKeyDispatcher(mappings)
    }

    @Synchronized
    fun onDeliveredEvent(event: ObservableAndroidKeyEvent): ActionKeyDispatchResult? {
        val owned = ownedPresses.values.firstOrNull { it.matches(event) }
        if (owned != null) {
            if (event.phase == ObservableKeyPhase.DOWN) {
                return ActionKeyDispatchResult.Ignored(ActionKeyIgnoreReason.REPEAT)
            }
            ownedPresses.remove(owned.mapping.mappingId)
            return if (owned.downResult is ActionKeyDispatchResult.AwaitingRelease) {
                dispatcher.onDeliveredEvent(event)
            } else {
                // A debounced DOWN still owns its UP, but never created a
                // dispatcher press and must not produce a command on release.
                owned.downResult
            }
        }
        if (
            event.phase != ObservableKeyPhase.DOWN ||
            event.repeatCount > 0 || event.isLongPress
        ) return null

        val mapping = mappings.mappings.singleOrNull { it.matches(event) } ?: return null
        // Never replace an unfinished stream with an unrelated downTime. Focus
        // loss explicitly clears stale ownership before the next foreground use.
        if (mapping.mappingId in ownedPresses) return null
        val result = dispatcher.onDeliveredEvent(event)
        ownedPresses[mapping.mappingId] = OwnedPress(mapping, event.downTimeMillis, result)
        return result
    }

    private data class OwnedPress(
        val mapping: ActionKeyMapping,
        val downTimeMillis: Long,
        val downResult: ActionKeyDispatchResult,
    ) {
        fun matches(event: ObservableAndroidKeyEvent): Boolean =
            downTimeMillis == event.downTimeMillis &&
                mapping.matches(event, includeMetaState = false)
    }
}
