package ai.hans.standard.ui

import org.json.JSONObject

internal data class IdleHeaderEvidence(
    val visible: Boolean,
    val enabled: Boolean,
    val clickable: Boolean,
    val descriptions: MutableList<String> = mutableListOf(),
)

/** Compose exposes a merging clickable Row's description on a virtual child. */
internal fun requireIdleHeader(headers: List<IdleHeaderEvidence>, live: Boolean) {
    check(headers.size == 1) { "Missing or ambiguous accessible Hans header" }
    val header = headers.single()
    check(header.visible && header.enabled && header.clickable) { "Hans header is not visibly actionable" }
    val start = "Hans. Anrufdialog öffnen"
    val stop = "Hans. Live Voice beenden"
    check(header.descriptions.all { it in setOf(start, stop, "Hans ist nicht bereit", "Live Voice aktiv") }) {
        "Unrecognized description inside Hans header"
    }
    val phaseDescriptions = header.descriptions.filter { it == start || it == stop }
    check(phaseDescriptions == listOf(if (live) stop else start)) {
        "Rendered Live header subtree does not match the probe phase"
    }
}

internal fun retainIdleAccessibilityNode(diagnostics: JSONObject, node: JSONObject) {
    val nodes = diagnostics.getJSONArray("nodes")
    if (nodes.length() < 64) nodes.put(node) else diagnostics.put("truncated", true)
}
