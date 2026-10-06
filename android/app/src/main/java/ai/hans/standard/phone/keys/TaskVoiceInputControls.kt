package ai.hans.standard.phone.keys

/**
 * Voice tasks use one press to start, then presses to mute/unmute. Older saved
 * hold-to-talk preferences remain readable and untouched for update continuity,
 * but must never reinstate release-to-stop in either runtime dispatcher.
 */
fun ActionKeyMappingSet.forTaskVoiceControls(): ActionKeyMappingSet =
    ActionKeyMappingSet.of(mappings.map { mapping ->
        if (mapping.action == KeySemanticAction.DICTATION) {
            mapping.copy(trigger = ActionKeyTrigger.PRESS)
        } else {
            mapping
        }
    })
