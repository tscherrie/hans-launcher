package ai.hans.standard.notifications.hooks

import org.json.JSONObject
import ai.hans.standard.phone.notifications.NotificationInboxEvent

/** Technical update identity only: Hans, not this layer, decides relevance and authority. */
internal object NotificationAgentUpdateIdentity {
    private val units = listOf("B", "KB", "kB", "MB", "GB", "TB", "KiB", "MiB", "GiB", "TiB",
        "o", "Ko", "Mo", "Go", "To", "t", "kt", "Mt", "Gt", "Tt", "Б", "КБ", "кБ", "МБ", "ГБ", "ТБ",
        "ب", "ك.ب", "م.ب", "ج.ب", "ت.ب", "ბ", "კბ", "მბ", "გბ", "ტბ", "位元組")
        .joinToString("|") { Regex.escape(it) }
    private val suffixes = listOf("s", "c", "с", "ث", "წმ", "秒", "초", "δλ", "mp", "dtk", "giây")
        .joinToString("|") { Regex.escape(it) }
    private const val SPACE = "[\\s\\p{Zs}]+"
    private const val NUMBER = "\\p{Nd}+[.,\u066B]\\p{Nd}{2}"
    private val amount = "$NUMBER$SPACE(?:$units)"
    private val rate = "$amount/(?:$suffixes)"
    private val meter = Regex("↓$SPACE$amount$SPACE\\|$SPACE$rate$SPACE↑$SPACE$amount$SPACE\\|$SPACE$rate")

    /** Private hash only. Raw strings never leave the inbox/privacy boundary. */
    fun fromEvent(event: NotificationInboxEvent): String? {
        val s = event.snapshot
        return fromPayload(JSONObject().put("title", s.title).put("text", s.text).put("subtext", s.subtext)
            .put("category", s.category).put("ongoing", s.ongoing).put("clearable", s.clearable)
            .put("channelId", notificationEventSha256(s.channelId)).toString())
    }

    fun fromPayload(payload: String): String? = runCatching {
        if (payload.isEmpty()) return null
        val p = JSONObject(payload)
        // Sanitized fields may hide a meaningful change; never infer equality across redaction.
        if (p.optBoolean("contentUnavailable") || p.optBoolean("redactionApplied")) return null
        val text = p.getString("text")
        val category = p.getString("category")
        // Legacy v1 omitted the ongoing bit. Only this completely matched, proven telemetry
        // grammar may be normalized in old READY records. Unknown formats always pass unchanged.
        val telemetry = category in setOf("service", "status") &&
            (!p.has("ongoing") || p.getBoolean("ongoing")) && meter.matches(text)
        val projection = JSONObject().put("title", p.getString("title"))
            .put("text", if (telemetry) "<byte-traffic-meter>" else text)
            .put("subtext", p.getString("subtext")).put("category", category)
            .put("ongoing", p.optBoolean("ongoing", telemetry))
            .put("clearable", p.optBoolean("clearable", !telemetry))
            .put("channelId", p.optString("channelId", ""))
            .put("contentUnavailable", p.optBoolean("contentUnavailable"))
            .put("redactionApplied", p.optBoolean("redactionApplied"))
        notificationEventSha256(projection.toString())
    }.getOrNull()

    /** Never alter a transmitted payload, receipt or claim. Preserve same-source transitions. */
    fun compactReady(state: NotificationEventState, sourceEvent: (Long) -> NotificationInboxEvent? = { null }): NotificationEventState {
        val previous = mutableMapOf<Pair<String, String>, NotificationEventRecord>()
        val removed = mutableSetOf<String>()
        val hydrated = state.records.sortedBy { it.sequence }.map { record ->
            val original = if (record.updateFingerprint == null && record.phase == NotificationEventPhase.READY)
                sourceEvent(record.sequence)?.takeIf { it.sequence == record.sequence &&
                    it.snapshot.packageName == record.packageName && it.snapshot.androidKey == record.androidKey &&
                    notificationEventSha256(NotificationExternalEventPayload.encode(record.eventId, it,
                        includeStatusMetadata = JSONObject(record.payloadJson).has("ongoing"))) == record.payloadSha256 }
                else null
            val identity = record.updateFingerprint ?: original?.let(::fromEvent) ?: fromPayload(record.payloadJson)
            val current = record.copy(updateFingerprint = identity)
            val source = current.packageName to current.androidKey
            val prior = previous[source]
            val isUpdate = runCatching { JSONObject(current.payloadJson).getString("kind") == "UPDATED" }.getOrDefault(false)
            if (isUpdate && current.sourceActive && current.phase == NotificationEventPhase.READY &&
                current.correlation == null && identity != null && prior?.sourceActive == true &&
                prior.updateFingerprint == identity) {
                if (prior.phase == NotificationEventPhase.READY && prior.correlation == null) removed += prior.eventId
                else removed += current.eventId
            }
            previous[source] = if (current.eventId in removed) prior!! else current
            current
        }
        return state.copy(records = hydrated.filterNot { it.eventId in removed },
            technicalUpdateCount = increment(state.technicalUpdateCount, removed.size.toLong()))
    }

    fun increment(value: Long, amount: Long = 1): Long = if (value > Long.MAX_VALUE - amount) Long.MAX_VALUE else value + amount
}
