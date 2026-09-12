package ai.hans.standard.notifications

import ai.hans.standard.phone.notifications.facts.NotificationArchiveCaptureToken
import ai.hans.standard.phone.notifications.facts.NotificationFactBatch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NotificationFactOutboxCodecTest {
    @Test fun roundTripPreservesEmojiLinkWhitespaceAndEveryField() {
        val original = entry().copy(attempts = 16, nextAttemptAtEpochMillis = Long.MAX_VALUE)
        assertEquals(original, NotificationFactOutboxCodec.decode(
            JSONObject(NotificationFactOutboxCodec.encode(original).toString()),
        ))
    }

    @Test fun tokenLongMaxAndCanonicalUuidRoundTripWithoutPrecisionLoss() {
        val token = NotificationArchiveCaptureToken(EPOCH, Long.MAX_VALUE, Long.MAX_VALUE)
        assertEquals(token, NotificationFactOutboxCodec.decodeToken(
            JSONObject(NotificationFactOutboxCodec.encodeToken(token).toString()),
        ))
    }

    @Test fun everyLayerRejectsMissingExtraAndNullFields() {
        for (layer in 0..3) {
            val original = json()
            val keys = layer(original, layer).keys().asSequence().toList()
            for (key in keys) {
                val missing = json()
                layer(missing, layer).remove(key)
                reject(missing)
                val nullValue = json()
                layer(nullValue, layer).put(key, JSONObject.NULL)
                reject(nullValue)
            }
            val extra = json()
            layer(extra, layer).put("untrustedExtra", true)
            reject(extra)
        }
    }

    @Test fun integerFieldsRejectCoercionAndOversizedNumericValues() {
        val fields = listOf(
            0 to "attempts", 0 to "nextAttemptAtEpochMillis",
            1 to "sourceRevision", 1 to "sequence", 1 to "observedAtEpochMillis",
            2 to "allGeneration", 2 to "packageGeneration",
            3 to "startUtf16", 3 to "endUtf16",
        )
        for ((depth, key) in fields) {
            for (value in listOf<Any>("1", 1.0, 1.5, true, JSONObject.NULL,
                java.math.BigInteger("9223372036854775808"))) {
                val json = json()
                layer(json, depth).put(key, value)
                reject(json)
            }
        }
    }

    @Test fun intRangeIsCheckedBeforeNarrowing() {
        for (key in listOf("startUtf16", "endUtf16")) {
            val json = json()
            candidate(json).put(key, Int.MAX_VALUE.toLong() + 1)
            reject(json)
        }
        reject(json().put("attempts", 4294967296L))
    }

    @Test fun offsetOverflowAndFieldBoundsAreRejected() {
        val json = json()
        candidate(json).put("quote", "x")
            .put("startUtf16", Int.MAX_VALUE).put("endUtf16", Int.MIN_VALUE)
        reject(json)
        for ((field, limit) in listOf("title" to 512, "text" to 4096, "subtext" to 1024)) {
            val outOfBounds = json()
            candidate(outOfBounds).put("sourceField", field).put("quote", "x")
                .put("startUtf16", limit).put("endUtf16", limit + 1)
            reject(outOfBounds)
        }
    }

    @Test fun attemptsAndTimeLimitsAreStrict() {
        for (attempts in listOf(-1, 17, Int.MAX_VALUE)) reject(json().put("attempts", attempts))
        reject(json().put("nextAttemptAtEpochMillis", -1L))
        for (attempts in listOf(0, 16)) {
            assertEquals(attempts, NotificationFactOutboxCodec.decode(json().put("attempts", attempts)).attempts)
        }
        assertEquals(128, NotificationFactOutboxCodec.MAX_ITEMS)
        assertEquals(16, NotificationFactOutboxCodec.MAX_ATTEMPTS)
    }

    @Test fun invalidTokenEpochAndNegativeGenerationsReject() {
        for (epoch in listOf("not-a-uuid", EPOCH.uppercase(), "1-1-1-1-1")) {
            val json = json()
            layer(json, 2).put("storeEpoch", epoch)
            reject(json)
        }
        for (key in listOf("allGeneration", "packageGeneration")) {
            val json = json()
            layer(json, 2).put(key, -1L)
            reject(json)
        }
    }

    @Test fun batchIdsPackageAndRevisionBoundsAreNotNormalized() {
        for ((key, value) in listOf(
            "batchId" to "../path", "sourceRef" to "", "packageName" to " invalid.app ",
            "extractorVersion" to "not a version", "sourceRevision" to 0,
            "sequence" to 0, "observedAtEpochMillis" to -1,
        )) {
            val json = json()
            layer(json, 1).put(key, value)
            reject(json)
        }
    }

    @Test fun candidateKindsHashesAndQuotesMustRespectDtoContract() {
        for ((key, value) in listOf(
            "kind" to "unknown", "sourceField" to "personal_context",
            "sourceSha256" to "A".repeat(64), "quote" to "",
            "quote" to "x".repeat(257), "quote" to "\uD800",
        )) {
            val json = json()
            candidate(json).put(key, value)
            reject(json)
        }
    }

    @Test fun candidateArrayMustBeNonemptyBoundedAndContainsOnlyObjects() {
        for (array in listOf(
            JSONArray(), JSONArray().put(JSONObject.NULL), JSONArray().put("candidate"),
            JSONArray().put(candidate(json())).put(candidate(json()))
                .put(candidate(json())).put(candidate(json())),
        )) {
            val json = json()
            layer(json, 1).put("validatedCandidates", array)
            reject(json)
        }
    }

    @Test fun duplicateFactsEvenAtDifferentOffsetsAreRejectedRatherThanFiltered() {
        val json = json()
        val first = candidate(json)
        val second = JSONObject(first.toString())
            .put("startUtf16", 10).put("endUtf16", 10 + first.getString("quote").length)
        layer(json, 1).put("validatedCandidates", JSONArray().put(first).put(second))
        reject(json)
    }

    @Test fun aggregateUtf8BudgetIsEnforcedWithoutTruncation() {
        val json = json()
        val array = JSONArray()
        for (field in listOf("text", "subtext")) {
            array.put(JSONObject(candidate(json).toString())
                .put("sourceField", field).put("quote", "界".repeat(200))
                .put("startUtf16", 0).put("endUtf16", 200))
        }
        layer(json, 1).put("validatedCandidates", array)
        reject(json)
    }

    @Test fun stringAndContainerTypesAreNotCoerced() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("batch", "{}") },
            { layer(it, 1).put("token", JSONArray()) },
            { layer(it, 1).put("validatedCandidates", "{}") },
            { layer(it, 1).put("batchId", 123) },
            { candidate(it).put("quote", true) },
        )
        mutations.forEach { mutation -> val json = json(); mutation(json); reject(json) }
    }

    private fun reject(json: JSONObject) {
        assertThrows(IllegalArgumentException::class.java) { NotificationFactOutboxCodec.decode(json) }
    }
    private fun json() = NotificationFactOutboxCodec.encode(entry())
    private fun layer(json: JSONObject, depth: Int): JSONObject = when (depth) {
        0 -> json
        1 -> json.getJSONObject("batch")
        2 -> json.getJSONObject("batch").getJSONObject("token")
        else -> candidate(json)
    }
    private fun candidate(json: JSONObject) = json.getJSONObject("batch")
        .getJSONArray("validatedCandidates").getJSONObject(0)

    private fun entry(): StoredNotificationFactOutbox {
        val quote = "Café 🎂\nhttps://events.example/festival"
        return StoredNotificationFactOutbox(NotificationFactBatch(
            batchId = "batch_synthetic", token = NotificationArchiveCaptureToken(EPOCH, 7, 9),
            packageName = "synthetic.chat", sourceRef = "source_synthetic",
            sourceRevision = Long.MAX_VALUE, sequence = Long.MAX_VALUE,
            observedAtEpochMillis = Long.MAX_VALUE, extractorVersion = "v1",
            validatedCandidates = listOf(NotificationMemoryCandidate(
                NotificationMemoryKind.EVENT_DETAIL, NotificationMemorySourceField.TEXT,
                quote, 3, 3 + quote.length, "a".repeat(64),
            )),
        ))
    }
    companion object { private const val EPOCH = "abcd1234-1234-4123-8123-123456789abc" }
}
