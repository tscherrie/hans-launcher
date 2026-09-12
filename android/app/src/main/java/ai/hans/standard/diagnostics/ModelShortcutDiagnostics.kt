package ai.hans.standard.diagnostics

import ai.hans.standard.phone.keys.ActionKeyMapping
import ai.hans.standard.phone.keys.KeySemanticAction

/** Persisted shortcut metadata only, never raw key events or physical-device identifiers. */
internal object ModelShortcutDiagnostics {
    fun encode(mapping: ActionKeyMapping?): String {
        val modelKey = mapping?.takeIf { it.action == KeySemanticAction.TOGGLE_LUNA_MAX_SOL_ULTRA }
        return MigrationCanonicalJson.encode(linkedMapOf(
            "schema" to "hans-model-shortcut-v1",
            "meaning" to "saved_empirical_mapping_not_execution_proof",
            "configured" to (modelKey != null),
            "keyCode" to modelKey?.keyCode,
            "scanCode" to modelKey?.scanCode,
            "source" to modelKey?.source,
            "metaState" to modelKey?.metaState,
            "trigger" to modelKey?.trigger?.name,
            "scope" to "hans_foreground_only",
            "lowPreset" to "gpt-5.6-luna/max",
            "highPreset" to "gpt-6-astra/ultra",
        ))
    }
}
