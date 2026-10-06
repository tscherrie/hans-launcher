package ai.hans.standard.notifications.hooks

import org.json.JSONArray
import org.json.JSONObject

internal object NotificationEventStateCodec {
    fun encode(state: NotificationEventState): String = JSONObject().put("version", 1)
        .put("activationSequence", state.activationSequence ?: JSONObject.NULL)
        .put("lastObservedSequence", state.lastObservedSequence).put("generation", state.generation)
        .put("technicalUpdateCount", state.technicalUpdateCount)
        .put("records", JSONArray(state.records.map { r -> JSONObject().put("sequence", r.sequence)
            .put("eventId", r.eventId).put("packageName", r.packageName).put("androidKey", r.androidKey)
            .put("payloadJson", r.payloadJson).put("payloadSha256", r.payloadSha256).put("phase", r.phase.name)
            .put("sourceActive", r.sourceActive).put("settledAtEpochMillis", r.settledAtEpochMillis ?: JSONObject.NULL)
            .put("updateFingerprint", r.updateFingerprint ?: JSONObject.NULL)
            .put("report", r.report?.let { notice -> JSONObject().put("id", notice.id).put("text", notice.text)
                .put("textSha256", notice.textSha256).put("timelineAnchorId", notice.timelineAnchorId ?: JSONObject.NULL) } ?: JSONObject.NULL)
            .put("correlation", r.correlation?.let { c -> JSONObject().put("threadId", c.threadId)
                .put("attemptId", c.attemptId).put("turnId", c.turnId ?: JSONObject.NULL) } ?: JSONObject.NULL) }))
        .toString()

    fun decode(text: String): NotificationEventState {
        require(text.toByteArray(Charsets.UTF_8).size <= NotificationEventLimits.MAX_STORAGE_BYTES)
        val j = JSONObject(text)
        require(j.getInt("version") == 1)
        val activation = if (j.isNull("activationSequence")) null else j.getLong("activationSequence")
        val observed = j.getLong("lastObservedSequence")
        val generation = j.bounded("generation", 256)
        val technicalUpdates = j.optLong("technicalUpdateCount", 0).also { require(it >= 0) }
        require(observed >= 0 && (activation == null || activation >= 0 && observed >= activation))
        require(activation == null || validCorrelation(generation))
        val array = j.getJSONArray("records")
        require(array.length() <= NotificationEventLimits.MAX_RECORDS)
        val records = (0 until array.length()).map { i ->
            val r = array.getJSONObject(i)
            val phase = NotificationEventPhase.valueOf(r.getString("phase"))
            val c = if (r.isNull("correlation")) null else r.getJSONObject("correlation").let {
                NotificationEventCorrelation(it.bounded("threadId", 256), it.bounded("attemptId", 256),
                    if (it.isNull("turnId")) null else it.bounded("turnId", 256)).also { correlation ->
                    require(validCorrelation(correlation.threadId) && validCorrelation(correlation.attemptId))
                    require(correlation.turnId == null || validCorrelation(correlation.turnId))
                }
            }
            val report = if (!r.has("report") || r.isNull("report")) null else r.getJSONObject("report").let { notice ->
                NotificationEventReport(notice.bounded("id", 128), notice.bounded("text", 4_096),
                    notice.bounded("textSha256", 64), if (notice.isNull("timelineAnchorId")) null
                    else notice.bounded("timelineAnchorId", 256)).also {
                    require(it.id.startsWith("hans-notice-") && validCorrelation(it.id))
                    require(it.text.isNotBlank() && it.text.codePointCount(0, it.text.length) <= 1_200)
                    require(it.textSha256 == notificationEventSha256(it.text))
                    require(it.timelineAnchorId == null || validCorrelation(it.timelineAnchorId))
                    require(c?.turnId != null)
                }
            }
            NotificationEventRecord(r.getLong("sequence"), r.bounded("eventId", 64), r.bounded("packageName", 255),
                r.bounded("androidKey", 1_024), r.bounded("payloadJson", NotificationEventLimits.MAX_PAYLOAD_BYTES),
                r.bounded("payloadSha256", 64), phase, r.getBoolean("sourceActive"), c,
                if (r.isNull("settledAtEpochMillis")) null else r.getLong("settledAtEpochMillis"), report,
                if (!r.has("updateFingerprint") || r.isNull("updateFingerprint")) null else r.bounded("updateFingerprint", 64)).also { record ->
                require(activation != null && record.sequence > activation && record.sequence <= observed)
                require(record.eventId.matches(Regex("[0-9a-f]{64}")) && record.payloadSha256.matches(Regex("[0-9a-f]{64}")))
                require(record.updateFingerprint == null || record.updateFingerprint.matches(Regex("[0-9a-f]{64}")))
                require(record.packageName.isNotBlank() && record.androidKey.isNotBlank())
                require(record.correlation != null || record.phase in setOf(NotificationEventPhase.READY, NotificationEventPhase.CANCELLED))
                require(record.phase != NotificationEventPhase.READY || record.correlation == null && record.sourceActive)
                require(record.phase != NotificationEventPhase.ACCEPTED || record.correlation?.turnId != null)
                require(record.phase !in setOf(NotificationEventPhase.COMPLETED, NotificationEventPhase.FAILED,
                    NotificationEventPhase.CANCELLED) || record.settledAtEpochMillis != null)
                require(record.settledAtEpochMillis == null || record.settledAtEpochMillis >= 0 && record.phase in setOf(
                    NotificationEventPhase.COMPLETED, NotificationEventPhase.FAILED, NotificationEventPhase.CANCELLED))
                if (record.payloadJson.isNotEmpty()) {
                    require(notificationEventSha256(record.payloadJson) == record.payloadSha256)
                    val payload = JSONObject(record.payloadJson)
                    require(payload.getString("eventId") == record.eventId && payload.getLong("sequence") == record.sequence)
                    require(payload.getString("packageName") == record.packageName)
                } else require(record.phase in setOf(NotificationEventPhase.ACCEPTED, NotificationEventPhase.COMPLETED,
                    NotificationEventPhase.FAILED, NotificationEventPhase.CANCELLED))
            }
        }
        require(records.map { it.sequence }.distinct().size == records.size && records.map { it.eventId }.distinct().size == records.size)
        return NotificationEventState(activation, observed, generation, records, technicalUpdates)
    }

    private fun JSONObject.bounded(key: String, bytes: Int): String = getString(key).also {
        require(it.toByteArray(Charsets.UTF_8).size <= bytes)
    }
}
