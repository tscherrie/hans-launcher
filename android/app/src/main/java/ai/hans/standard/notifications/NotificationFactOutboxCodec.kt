package ai.hans.standard.notifications

import ai.hans.standard.phone.notifications.facts.NotificationArchiveCaptureToken
import ai.hans.standard.phone.notifications.facts.NotificationFactBatch
import ai.hans.standard.phone.notifications.facts.NotificationFactBounds
import org.json.JSONArray
import org.json.JSONObject

internal data class StoredNotificationFactOutbox(
    val batch: NotificationFactBatch,
    val attempts: Int = 0,
    val nextAttemptAtEpochMillis: Long = 0,
) {
    init {
        require(attempts in 0..NotificationFactOutboxCodec.MAX_ATTEMPTS)
        require(nextAttemptAtEpochMillis >= 0)
    }
}

/** Lossless queue-v4 record codec. The queue owns container size and atomic persistence. */
internal object NotificationFactOutboxCodec {
    const val MAX_ITEMS = 128
    const val MAX_ATTEMPTS = 16

    fun encodeToken(token: NotificationArchiveCaptureToken): JSONObject = JSONObject()
        .put("storeEpoch", token.storeEpoch)
        .put("allGeneration", token.allGeneration)
        .put("packageGeneration", token.packageGeneration)

    fun decodeToken(json: JSONObject): NotificationArchiveCaptureToken {
        json.exactKeys("storeEpoch", "allGeneration", "packageGeneration")
        return NotificationArchiveCaptureToken(
            storeEpoch = json.string("storeEpoch"),
            allGeneration = json.integer("allGeneration"),
            packageGeneration = json.integer("packageGeneration"),
        )
    }

    fun encode(entry: StoredNotificationFactOutbox): JSONObject {
        val batch = entry.batch
        return JSONObject()
            .put("batch", JSONObject()
                .put("batchId", batch.batchId)
                .put("token", encodeToken(batch.token))
                .put("packageName", batch.packageName)
                .put("sourceRef", batch.sourceRef)
                .put("sourceRevision", batch.sourceRevision)
                .put("sequence", batch.sequence)
                .put("observedAtEpochMillis", batch.observedAtEpochMillis)
                .put("extractorVersion", batch.extractorVersion)
                .put("validatedCandidates", JSONArray().also { array ->
                    batch.validatedCandidates.forEach { candidate ->
                        array.put(JSONObject()
                            .put("kind", candidate.kind.wireName)
                            .put("sourceField", candidate.sourceField.wireName)
                            .put("quote", candidate.quote)
                            .put("startUtf16", candidate.startUtf16)
                            .put("endUtf16", candidate.endUtf16)
                            .put("sourceSha256", candidate.sourceSha256))
                    }
                }))
            .put("attempts", entry.attempts)
            .put("nextAttemptAtEpochMillis", entry.nextAttemptAtEpochMillis)
    }

    fun decode(json: JSONObject): StoredNotificationFactOutbox {
        json.exactKeys("batch", "attempts", "nextAttemptAtEpochMillis")
        val batch = json.objectValue("batch")
        batch.exactKeys(
            "batchId", "token", "packageName", "sourceRef", "sourceRevision", "sequence",
            "observedAtEpochMillis", "extractorVersion", "validatedCandidates",
        )
        val array = batch.opt("validatedCandidates") as? JSONArray
            ?: throw IllegalArgumentException("invalid_outbox_candidates")
        require(array.length() in 1..NotificationFactBounds.MAX_CANDIDATES)
        val candidates = List(array.length()) { index ->
            val candidate = array.opt(index) as? JSONObject
                ?: throw IllegalArgumentException("invalid_outbox_candidate")
            candidate.exactKeys(
                "kind", "sourceField", "quote", "startUtf16", "endUtf16", "sourceSha256",
            )
            NotificationMemoryCandidate(
                kind = NotificationMemoryKind.entries.firstOrNull {
                    it.wireName == candidate.string("kind")
                } ?: throw IllegalArgumentException("invalid_outbox_kind"),
                sourceField = NotificationMemorySourceField.entries.firstOrNull {
                    it.wireName == candidate.string("sourceField")
                } ?: throw IllegalArgumentException("invalid_outbox_source_field"),
                quote = candidate.string("quote"),
                startUtf16 = candidate.intValue("startUtf16"),
                endUtf16 = candidate.intValue("endUtf16"),
                sourceSha256 = candidate.string("sourceSha256"),
            )
        }
        return StoredNotificationFactOutbox(
            batch = NotificationFactBatch(
                batchId = batch.string("batchId"),
                token = decodeToken(batch.objectValue("token")),
                packageName = batch.string("packageName"),
                sourceRef = batch.string("sourceRef"),
                sourceRevision = batch.integer("sourceRevision"),
                sequence = batch.integer("sequence"),
                observedAtEpochMillis = batch.integer("observedAtEpochMillis"),
                extractorVersion = batch.string("extractorVersion"),
                validatedCandidates = candidates,
            ),
            attempts = json.intValue("attempts"),
            nextAttemptAtEpochMillis = json.integer("nextAttemptAtEpochMillis"),
        )
    }

    private fun JSONObject.exactKeys(vararg expected: String) {
        require(keys().asSequence().toSet() == expected.toSet()) { "invalid_outbox_fields" }
    }

    private fun JSONObject.string(key: String): String = opt(key) as? String
        ?: throw IllegalArgumentException("invalid_outbox_string")

    private fun JSONObject.objectValue(key: String): JSONObject = opt(key) as? JSONObject
        ?: throw IllegalArgumentException("invalid_outbox_object")

    private fun JSONObject.integer(key: String): Long = when (val value = opt(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> throw IllegalArgumentException("invalid_outbox_integer")
    }

    private fun JSONObject.intValue(key: String): Int {
        val value = integer(key)
        require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            "outbox_integer_overflow"
        }
        return value.toInt()
    }
}
