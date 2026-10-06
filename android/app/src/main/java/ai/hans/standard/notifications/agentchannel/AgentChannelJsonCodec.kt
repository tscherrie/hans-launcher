package ai.hans.standard.notifications.agentchannel

import org.json.JSONArray
import org.json.JSONObject

/** Private storage only. This document is never a model-facing tool or public diagnostic. */
object AgentChannelSourceCodec {
    fun encode(source: WhatsAppNotificationSource): String = json(source).toString()
    fun decode(text: String): WhatsAppNotificationSource? = runCatching {
        require(text.toByteArray(Charsets.UTF_8).size <= 70_000)
        source(JSONObject(text))
    }.getOrNull()

    internal fun json(s: WhatsAppNotificationSource): JSONObject = JSONObject()
        .put("package", s.packageName).put("user", s.androidUserId).put("uid", s.postingUid)
        .put("key", s.notificationKey).put("shortcut", s.shortcutId ?: JSONObject.NULL)
        .put("own", s.ownPersonIdentity ?: JSONObject.NULL).put("group", s.isGroupConversation)
        .put("summary", s.isGroupSummary).put("truncated", s.messagesTruncated).put("title", s.displayTitle)
        .put("messages", JSONArray(s.messages.map { m -> JSONObject().put("text", m.text)
            .put("time", m.timestampEpochMillis).put("sender", m.senderIdentity ?: JSONObject.NULL)
            .put("self", m.senderIsOwnUser).put("truncated", m.truncated).put("attachment", m.hasAttachment) }))

    internal fun source(j: JSONObject): WhatsAppNotificationSource {
        val messages = j.getJSONArray("messages")
        require(messages.length() <= AgentChannelLimits.MAX_MESSAGES)
        return WhatsAppNotificationSource(j.bounded("package", 255), j.getInt("user"), j.getInt("uid"),
            j.bounded("key", 2_048), j.optional("shortcut", 1_024), j.optional("own", 64),
            j.getBoolean("group"), j.getBoolean("summary"), (0 until messages.length()).map { i ->
                val m = messages.getJSONObject(i)
                WhatsAppNotificationMessage(m.bounded("text", AgentChannelLimits.MAX_MESSAGE_BYTES), m.getLong("time"),
                    m.optional("sender", 64), m.getBoolean("self"), m.getBoolean("truncated"), m.getBoolean("attachment"))
            }, j.getBoolean("truncated"), j.bounded("title", 256))
    }
}

internal object AgentChannelStateCodec {
    fun encode(s: AgentChannelState): String {
        val binding = s.binding?.let { JSONObject().put("generation", it.generation).put("identity", it.sourceIdentity)
            .put("confirmed", it.confirmedAtEpochMillis).put("source", AgentChannelSourceCodec.json(it.source)) }
        return JSONObject().put("version", 1).put("binding", binding ?: JSONObject.NULL)
            .put("candidates", JSONArray(s.enrollmentCandidates.map { JSONObject().put("id", it.id)
                .put("identity", it.sourceIdentity).put("source", AgentChannelSourceCodec.json(it.source)).put("time", it.observedAtEpochMillis) }))
            .put("requests", JSONArray(s.requests.map { JSONObject().put("id", it.id).put("generation", it.bindingGeneration)
                .put("identity", it.sourceIdentity).put("dedupe", it.idempotencyKey).put("key", it.notificationKey)
                .put("requestId", it.requestId ?: JSONObject.NULL).put("body", it.requestText).put("received", it.receivedAtEpochMillis)
                .put("messageTime", it.messageAtEpochMillis).put("status", it.status.name)
                .put("correlation", it.dispatchCorrelation?.let { correlation -> JSONObject()
                    .put("threadId", correlation.threadId).put("clientUserMessageId", correlation.clientUserMessageId)
                    .put("turnId", correlation.turnId ?: JSONObject.NULL) } ?: JSONObject.NULL)
                .put("settled", it.settledAtEpochMillis ?: JSONObject.NULL) }))
            .put("hints", JSONArray(s.retrievalHints.map { JSONObject().put("id", it.id).put("generation", it.bindingGeneration)
                .put("identity", it.sourceIdentity).put("key", it.notificationKey).put("shortcut", it.shortcutId)
                .put("reason", it.reason).put("time", it.observedAtEpochMillis) })).toString()
    }

    fun decode(text: String): AgentChannelState {
        require(text.toByteArray(Charsets.UTF_8).size <= AgentChannelLimits.MAX_STORAGE_BYTES)
        val j = JSONObject(text)
        require(j.getInt("version") == 1)
        val binding = j.optJSONObject("binding")?.let { b -> AgentChannelBinding(b.bounded("generation", 64),
            b.bounded("identity", 64), b.getLong("confirmed"), AgentChannelSourceCodec.source(b.getJSONObject("source")))
            .also { require(it.source.identity() == it.sourceIdentity && it.confirmedAtEpochMillis >= 0 && it.source.messages.isEmpty()) } }
        val candidates = j.array("candidates", AgentChannelLimits.MAX_ENROLLMENT_CANDIDATES).map { c ->
            AgentChannelEnrollmentCandidate(c.bounded("id", 64), c.bounded("identity", 64),
                AgentChannelSourceCodec.source(c.getJSONObject("source")), c.getLong("time"))
                .also { require(it.source.identity() == it.sourceIdentity && it.source.messages.isEmpty()) }
        }
        val requests = j.array("requests", AgentChannelLimits.MAX_REQUESTS).map { r -> AgentChannelRequestReceipt(
            r.bounded("id", 64), r.bounded("generation", 64), r.bounded("identity", 64), r.bounded("dedupe", 64),
            r.bounded("key", 2_048), r.optional("requestId", 64), r.bounded("body", AgentChannelLimits.MAX_REQUEST_BYTES),
            r.getLong("received"), r.getLong("messageTime"), AgentChannelWorkflowStatus.valueOf(r.getString("status")),
            r.dispatchCorrelation(), r.optionalEpochMillis("settled"))
            .also { receipt ->
                require(receipt.receivedAtEpochMillis >= 0 && receipt.messageAtEpochMillis >= 0)
                require(receipt.dispatchCorrelation == null || receipt.status != AgentChannelWorkflowStatus.READY)
                require(receipt.dispatchCorrelation == null ||
                    receipt.dispatchCorrelation.clientUserMessageId == "whatsapp-agent-${receipt.id}")
                require(receipt.settledAtEpochMillis == null || receipt.status in setOf(
                    AgentChannelWorkflowStatus.COMPLETED, AgentChannelWorkflowStatus.FAILED, AgentChannelWorkflowStatus.CANCELLED))
            } }
        require(requests.map { it.id }.distinct().size == requests.size)
        require(requests.map { it.idempotencyKey }.distinct().size == requests.size)
        val hints = j.array("hints", AgentChannelLimits.MAX_RETRIEVAL_HINTS).map { h -> AgentChannelRetrievalHint(
            h.bounded("id", 64), h.bounded("generation", 64), h.bounded("identity", 64), h.bounded("key", 2_048),
            h.bounded("shortcut", 1_024), h.bounded("reason", 64), h.getLong("time")) }
        return AgentChannelState(binding, candidates, requests, hints)
    }
}

private fun JSONObject.bounded(key: String, bytes: Int): String = getString(key).also {
    require(it.toByteArray(Charsets.UTF_8).size <= bytes)
}
private fun JSONObject.optional(key: String, bytes: Int): String? = if (isNull(key)) null else bounded(key, bytes)
private fun JSONObject.array(key: String, max: Int): List<JSONObject> = getJSONArray(key).let { a ->
    require(a.length() <= max)
    (0 until a.length()).map(a::getJSONObject)
}

/** Legacy v1 files omit these additive fields; malformed present fields never become null. */
private fun JSONObject.dispatchCorrelation(): AgentChannelDispatchCorrelation? {
    if (!has("correlation") || isNull("correlation")) return null
    val correlation = getJSONObject("correlation")
    require(correlation.keys().asSequence().toSet() == setOf("threadId", "clientUserMessageId", "turnId"))
    fun id(key: String): String = (correlation.opt(key) as? String ?: error("invalid_channel_correlation"))
    return AgentChannelDispatchCorrelation(id("threadId"), id("clientUserMessageId"),
        if (correlation.isNull("turnId")) null else id("turnId"))
}

private fun JSONObject.optionalEpochMillis(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    val value = opt(key)
    require(value is Int || value is Long)
    return (value as Number).toLong().also { require(it >= 0) }
}
