package ai.hans.standard.notifications.hooks

import org.json.JSONArray
import org.json.JSONObject

/** Process-local ordinals permit correlation without exposing native/event/source identities. */
internal data class NotificationHookOutcome(
    val eventOrdinal: Long, val workOrdinal: Long?, val accepted: Boolean, val claimSettled: Boolean,
    val turnTerminal: Boolean, val finalVisible: Boolean, val reportPresented: Boolean, val speechGate: String,
    val revocationReason: String?, val playbackPhase: String?, val playbackFailure: String?,
)

internal data class NotificationHookOutcomeSnapshot(
    val sourceCommitCount: Long = 0, val sourceRemovalCount: Long = 0,
    val acceptedCount: Long = 0, val visibleFinalCount: Long = 0, val reportPresentedCount: Long = 0,
    val speechQueuedCount: Long = 0,
    val speechCapacityBlockedCount: Long = 0,
    val recent: List<NotificationHookOutcome> = emptyList(),
)

internal fun notificationHookOutcomeDiagnosticJson(outcome: NotificationHookOutcomeSnapshot): JSONObject = outcome.toDiagnosticJson()

private fun NotificationHookOutcomeSnapshot.toDiagnosticJson(): JSONObject = JSONObject()
    .put("scope", "current_process")
    .put("sourceCommitCount", sourceCommitCount).put("sourceRemovalCount", sourceRemovalCount)
    .put("acceptedCount", acceptedCount).put("visibleFinalCount", visibleFinalCount)
    .put("reportPresentedCount", reportPresentedCount)
    .put("speechQueuedCount", speechQueuedCount)
    .put("speechCapacityBlockedCount", speechCapacityBlockedCount)
    .put("recent", JSONArray().also { array -> recent.takeLast(48).forEach { row -> array.put(JSONObject()
        .put("eventOrdinal", row.eventOrdinal).put("workOrdinal", row.workOrdinal ?: JSONObject.NULL)
        .put("accepted", row.accepted).put("claimSettled", row.claimSettled)
        .put("turnTerminal", row.turnTerminal).put("finalVisible", row.finalVisible)
        .put("reportPresented", row.reportPresented)
        .put("speechGate", row.speechGate).put("revocationReason", row.revocationReason ?: JSONObject.NULL)
        .put("playbackPhase", row.playbackPhase ?: JSONObject.NULL)
        .put("playbackFailure", row.playbackFailure ?: JSONObject.NULL)) } })
