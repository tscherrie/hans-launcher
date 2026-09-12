package ai.hans.standard.diagnostics

import ai.hans.standard.codex.CodexModel
import ai.hans.standard.integration.CodexClientSnapshot
import ai.hans.standard.integration.DispatchSelection
import ai.hans.standard.settings.HansSettings

/**
 * Passive, bounded model metadata for the ActivityManager DUMP-authorized diagnostic path.
 * Never refreshes the catalogue, changes a selection, or serializes account/session content.
 */
internal object ModelSelectionDiagnostics {
    internal const val MAX_MODEL_ENTRIES = 32
    internal const val MAX_CAPABILITY_ENTRIES = 16
    internal const val MAX_ENCODED_BYTES = 192 * 1_024
    private val wireToken = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")

    fun encode(snapshot: CodexClientSnapshot?): String {
        val models = snapshot?.models.orEmpty()
        val readiness = snapshot?.migrationReadiness
        val entries = models.take(MAX_MODEL_ENTRIES).map { it.payload() }.toMutableList()
        fun payload(): String = MigrationCanonicalJson.encode(linkedMapOf(
            "schema" to "hans-model-selection-v1",
            "snapshotAvailable" to (snapshot != null),
            "catalogMeaning" to "received_model_list_snapshot_not_refresh_or_execution_proof",
            "runtimePhase" to snapshot?.runtimePhase?.name,
            "sessionPhase" to snapshot?.sessionPhase?.name,
            "accountPhase" to snapshot?.session?.account?.phase?.name,
            "accountReadComplete" to readiness?.accountReadComplete,
            "threadResumeConfirmed" to readiness?.threadResumeConfirmed,
            "memoryModeEnabledAck" to readiness?.memoryModeEnabledAck,
            "activeTurn" to readiness?.activeTurn,
            "activeTurnMeaning" to "active_or_unknown_turn_or_pending_dispatch_or_tool_work",
            "problemCode" to snapshot?.problem?.code?.name,
            "confirmedSelection" to snapshot?.confirmedSelection?.payload(),
            "pendingSelection" to snapshot?.pendingSelection?.payload(),
            "pendingSettingsSelection" to snapshot?.pendingSettingsSelection?.payload(),
            "effectiveSelection" to readiness?.effectiveSelection?.payload(),
            "catalogModelCount" to models.size,
            "catalogModelsOmitted" to (models.size - entries.size),
            "models" to entries,
        ))
        var encoded = payload()
        // Keep the diagnostic bound explicit even if its fixed field layout grows later.
        while (encoded.toByteArray(Charsets.UTF_8).size > MAX_ENCODED_BYTES && entries.isNotEmpty()) {
            entries.removeAt(entries.lastIndex)
            encoded = payload()
        }
        return encoded
    }

    private fun CodexModel.payload(): Map<String, Any?> = linkedMapOf(
        "catalogId" to safeToken(catalogId),
        "wireModel" to safeToken(wireModel),
        "hidden" to hidden,
        "isDefault" to isDefault,
        "defaultEffort" to safeToken(defaultEffort.wireValue),
        "supportedEfforts" to supportedEfforts.take(MAX_CAPABILITY_ENTRIES)
            .map { safeToken(it.wireValue) },
        "supportedEffortsOmitted" to (supportedEfforts.size - MAX_CAPABILITY_ENTRIES).coerceAtLeast(0),
        "defaultServiceTier" to defaultServiceTier?.let(::safeToken),
        "serviceTiers" to serviceTiers.take(MAX_CAPABILITY_ENTRIES).map { safeToken(it.id) },
        "serviceTiersOmitted" to (serviceTiers.size - MAX_CAPABILITY_ENTRIES).coerceAtLeast(0),
        "hansSelectable" to (!hidden && wireModel in HansSettings.SUPPORTED_MODELS &&
            supportedEfforts.any { it.wireValue in HansSettings.SUPPORTED_REASONING_EFFORTS }),
    )

    private fun DispatchSelection.payload(): Map<String, Any?> = linkedMapOf(
        "model" to safeToken(model),
        "effort" to safeToken(effort.wireValue),
        "serviceTier" to safeToken(serviceTier),
    )

    private fun safeToken(value: String): String? = value.takeIf(wireToken::matches)
}
