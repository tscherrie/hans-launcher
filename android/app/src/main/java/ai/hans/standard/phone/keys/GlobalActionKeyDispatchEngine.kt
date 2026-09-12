package ai.hans.standard.phone.keys

/**
 * Result of filtering one framework-delivered event. `consume` is deliberately
 * independent from command execution: Android does not offer a safe way to
 * inject an event back into another app after a configured shortcut fails.
 */
data class GlobalActionKeyDecision(
    val consume: Boolean,
    val commands: List<ActionKeyCommand> = emptyList(),
    val mappingId: String? = null,
)

/**
 * Pure reducer for the root-free global dictation shortcut. It accepts only
 * mappings that the ordinary Hans capture flow persisted and only the
 * DICTATION semantic action. Model switching remains launcher-local.
 *
 * A consumed DOWN owns its corresponding UP, including repeat/debounce cases,
 * so another app never receives an orphaned half-press. An UP for which Hans
 * did not consume a DOWN is passed through unchanged.
 */
class GlobalActionKeyDispatchEngine(
    mappings: ActionKeyMappingSet = ActionKeyMappingSet.empty(),
) {
    private var mappings = globalMappings(mappings)
    private val dispatcher = ActionKeyDispatcher(this.mappings)
    private val ownedPresses = mutableListOf<OwnedPress>()

    @Synchronized
    fun hasMappings(): Boolean = mappings.mappings.isNotEmpty()

    /**
     * Android must keep delivering key events until every DOWN consumed by
     * Hans has received its matching UP. This stays true across a concurrent
     * mapping removal or replacement so another app never receives an orphan
     * release event.
     */
    @Synchronized
    fun requiresFrameworkFiltering(): Boolean = hasMappings() || ownedPresses.isNotEmpty()

    @Synchronized
    fun replaceMappings(next: ActionKeyMappingSet): GlobalActionKeyDecision {
        val filtered = globalMappings(next)
        val cleanup = dispatcher.replaceMappings(filtered).map { it.command }
        mappings = filtered
        return GlobalActionKeyDecision(consume = false, commands = cleanup)
    }

    @Synchronized
    fun onDeliveredEvent(event: ObservableAndroidKeyEvent): GlobalActionKeyDecision =
        when (event.phase) {
            ObservableKeyPhase.DOWN -> onDown(event)
            ObservableKeyPhase.UP -> onUp(event)
        }

    @Synchronized
    fun close(): GlobalActionKeyDecision {
        val cleanup = dispatcher.replaceMappings(ActionKeyMappingSet.empty()).map { it.command }
        mappings = ActionKeyMappingSet.empty()
        ownedPresses.clear()
        return GlobalActionKeyDecision(consume = false, commands = cleanup)
    }

    private fun onDown(event: ObservableAndroidKeyEvent): GlobalActionKeyDecision {
        val owned = ownedPresses.singleOrNull {
            it.matches(event, includeMetaState = false)
        }
        if (owned != null) {
            // Android can deliver repeat events (or a duplicate DOWN) only
            // after Hans accepted the initial DOWN. Own the entire stream but
            // never dispatch the shortcut twice.
            return GlobalActionKeyDecision(consume = true)
        }
        if (event.repeatCount > 0 || event.isLongPress) {
            // The service may have connected in the middle of a physical key
            // press. Passing that stream through is the only way to avoid
            // consuming a DOWN Hans never observed.
            return GlobalActionKeyDecision(consume = false)
        }
        val matches = mappings.mappings.filter { it.matches(event) }
        if (matches.size != 1) return GlobalActionKeyDecision(consume = false)
        val mapping = matches.single()
        // Reserved/system-owned keys are rejected during capture and checked
        // again here so stale/corrupt legacy state can never claim them.
        if (ReservedHardwareKeyPolicy.classify(event.keyCode) != null) {
            return GlobalActionKeyDecision(consume = false)
        }
        ownedPresses.lastOrNull { it.mapping == mapping }?.let { previous ->
            val staleStockPress = mapping.trigger == ActionKeyTrigger.PRESS &&
                Mp01VendorActionConflictResolver.isStockActionButton(mapping) &&
                previous.downTimeMillis != event.downTimeMillis &&
                event.eventTimeMillis >= previous.downTimeMillis &&
                event.eventTimeMillis - previous.downTimeMillis >=
                Mp01VendorActionConflictResolver.STOCK_LONG_PRESS_MILLIS
            if (staleStockPress) {
                // Stock PhoneWindowManager can steal focus after its long
                // press threshold, so Hans may never see that old UP.
                ownedPresses.remove(previous)
            }
        }
        ownedPresses += OwnedPress(mapping, event.downTimeMillis)
        val command = (dispatcher.onDeliveredEvent(event) as? ActionKeyDispatchResult.Command)
            ?.command
        return GlobalActionKeyDecision(
            consume = true,
            commands = listOfNotNull(command),
            mappingId = mapping.mappingId,
        )
    }

    private fun onUp(event: ObservableAndroidKeyEvent): GlobalActionKeyDecision {
        val owned = ownedPresses.singleOrNull { it.matches(event, includeMetaState = false) }
            ?: return GlobalActionKeyDecision(consume = false)
        ownedPresses.remove(owned)
        val mappingIsStillEffective = mappings.mappings.any { it == owned.mapping }
        val command = if (mappingIsStillEffective) {
            (dispatcher.onDeliveredEvent(event) as? ActionKeyDispatchResult.Command)?.command
        } else {
            null
        }
        return GlobalActionKeyDecision(
            consume = true,
            commands = listOfNotNull(command),
            mappingId = owned.mapping.mappingId,
        )
    }

    private data class OwnedPress(
        val mapping: ActionKeyMapping,
        val downTimeMillis: Long,
    ) {
        fun matches(
            event: ObservableAndroidKeyEvent,
            includeMetaState: Boolean = true,
        ): Boolean = downTimeMillis == event.downTimeMillis &&
            mapping.matches(event, includeMetaState)
    }

    private companion object {
        fun globalMappings(input: ActionKeyMappingSet): ActionKeyMappingSet =
            ActionKeyMappingSet.of(
                input.mappings.filter { mapping ->
                    mapping.action == KeySemanticAction.DICTATION &&
                        ReservedHardwareKeyPolicy.classify(mapping.keyCode) == null
                },
            )
    }
}
