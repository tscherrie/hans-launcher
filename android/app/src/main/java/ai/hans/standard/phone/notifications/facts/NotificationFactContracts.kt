package ai.hans.standard.phone.notifications.facts

import ai.hans.standard.notifications.NotificationMemoryCandidate
import ai.hans.standard.notifications.NotificationMemoryKind
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Android-owned notification claims. This is not the native Codex memory format. */
object NotificationFactBounds {
    const val MAX_FACTS = 100_000
    const val MAX_DATABASE_BYTES = 128L * 1_024 * 1_024
    const val PRIVACY_RESERVE_BYTES = 8L * 1_024 * 1_024
    const val MAX_CANDIDATES = 3
    const val MAX_BATCH_QUOTE_UTF8_BYTES = 1_024
    const val MAX_QUOTE_UTF16 = 256
    const val MAX_RESULTS = 8
    const val MAX_TERMS = 8
    const val MAX_TERM_UTF8_BYTES = 64
    const val DEFAULT_QUERY_UTF8_BYTES = 8 * 1_024
    const val MAX_QUERY_UTF8_BYTES = 16 * 1_024
    const val MIN_QUERY_UTF8_BYTES = 512
    const val MAX_PENDING_PRIVACY_INTENTS = 64
}

data class NotificationFactCapacity(
    val maxFacts: Int = NotificationFactBounds.MAX_FACTS,
    val maxDatabaseBytes: Long = NotificationFactBounds.MAX_DATABASE_BYTES,
    val privacyReserveBytes: Long = NotificationFactBounds.PRIVACY_RESERVE_BYTES,
) {
    init {
        require(maxFacts in 1..NotificationFactBounds.MAX_FACTS)
        require(maxDatabaseBytes in 128 * 1_024L..NotificationFactBounds.MAX_DATABASE_BYTES)
        require(privacyReserveBytes in 32 * 1_024L until maxDatabaseBytes)
    }
}

data class NotificationArchiveCaptureToken(
    val storeEpoch: String,
    val allGeneration: Long,
    val packageGeneration: Long,
) {
    init {
        require(UUID.fromString(storeEpoch).toString() == storeEpoch)
        require(allGeneration >= 0 && packageGeneration >= 0)
    }
}

/** Source identity, timing and token are bound by the host, never supplied by the classifier. */
data class NotificationFactBatch(
    val batchId: String,
    val token: NotificationArchiveCaptureToken,
    val packageName: String,
    val sourceRef: String,
    val sourceRevision: Long,
    val sequence: Long,
    val observedAtEpochMillis: Long,
    val extractorVersion: String,
    val validatedCandidates: List<NotificationMemoryCandidate>,
) {
    init {
        requireArchiveId(batchId)
        requireArchivePackage(packageName)
        requireArchiveId(sourceRef)
        require(sourceRevision > 0 && sequence > 0 && observedAtEpochMillis >= 0)
        require(extractorVersion.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")))
        require(validatedCandidates.size in 1..NotificationFactBounds.MAX_CANDIDATES)
        require(validatedCandidates.sumOf { it.quote.archiveUtf8Size() } <=
            NotificationFactBounds.MAX_BATCH_QUOTE_UTF8_BYTES)
        require(validatedCandidates.map {
            NotificationFactIds.forCandidate(packageName, sourceRef, it)
        }.distinct().size == validatedCandidates.size)
    }
}

/** Stable across a source revision/offset change; the external claim is still not owner truth. */
object NotificationFactIds {
    fun forCandidate(
        packageName: String,
        sourceRef: String,
        candidate: NotificationMemoryCandidate,
    ): String = "fact_" + archiveDigest(
        listOf(packageName, sourceRef, candidate.kind.wireName,
            candidate.sourceField.wireName, candidate.quote),
    )
}

enum class NotificationFactAuthority(val wireName: String) {
    UNTRUSTED_NOTIFICATION_CLAIM("untrusted_notification_claim"),
    OWNER_CORRECTION("explicit_owner_correction"),
}

data class NotificationFact(
    val factId: String,
    val revision: Long,
    val kind: NotificationMemoryKind,
    val text: String,
    val authority: NotificationFactAuthority,
    val packageName: String,
    val sourceRef: String,
    val sourceRevision: Long,
    val sequence: Long,
    val observedAtEpochMillis: Long,
    val extractorVersion: String,
    val evidence: NotificationMemoryCandidate,
    val correctedAtEpochMillis: Long? = null,
) {
    init {
        requireArchiveId(factId)
        require(revision > 0 && sourceRevision > 0 && sequence > 0)
        requireArchivePackage(packageName)
        requireArchiveId(sourceRef)
        requireArchiveText(text)
        require(extractorVersion.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")))
        require(observedAtEpochMillis >= 0)
        require(correctedAtEpochMillis == null || correctedAtEpochMillis >= 0)
        require((authority == NotificationFactAuthority.OWNER_CORRECTION) ==
            (correctedAtEpochMillis != null))
        require(authority != NotificationFactAuthority.UNTRUSTED_NOTIFICATION_CLAIM ||
            (kind == evidence.kind && text == evidence.quote))
    }

    fun toJsonProjection(): JSONObject = JSONObject()
        .put("factId", factId).put("revision", revision)
        .put("kind", kind.wireName).put("text", text)
        .put("authority", authority.wireName)
        .put("sourcePackage", packageName).put("sourceRef", sourceRef)
        .put("sourceRevision", sourceRevision)
        .put("observedAtEpochMillis", observedAtEpochMillis)
        .put("extractorVersion", extractorVersion)
        .put("evidence", JSONObject()
            .put("kind", evidence.kind.wireName)
            .put("sourceField", evidence.sourceField.wireName)
            .put("quote", evidence.quote)
            .put("startUtf16", evidence.startUtf16)
            .put("endUtf16", evidence.endUtf16)
            .put("sourceSha256", evidence.sourceSha256))
        .put("correctedAtEpochMillis", correctedAtEpochMillis ?: JSONObject.NULL)
}

data class NotificationFactQuery(
    val terms: List<String> = emptyList(),
    val packageName: String? = null,
    val sourceRef: String? = null,
    val kind: NotificationMemoryKind? = null,
    val sinceEpochMillis: Long = 0,
    val untilEpochMillis: Long = Long.MAX_VALUE,
    val limit: Int = NotificationFactBounds.MAX_RESULTS,
    val maxUtf8Bytes: Int = NotificationFactBounds.DEFAULT_QUERY_UTF8_BYTES,
) {
    init {
        require(terms.size <= NotificationFactBounds.MAX_TERMS)
        require(terms.all { it.isNotBlank() && it.archiveUtf8Size() <=
            NotificationFactBounds.MAX_TERM_UTF8_BYTES && it.none(Char::isISOControl) })
        require(terms.map { it.lowercase(Locale.ROOT) }.distinct().size == terms.size)
        packageName?.let(::requireArchivePackage)
        sourceRef?.let(::requireArchiveId)
        require(sourceRef == null || packageName != null)
        require(sinceEpochMillis >= 0 && untilEpochMillis >= sinceEpochMillis)
        require(limit in 1..NotificationFactBounds.MAX_RESULTS)
        require(maxUtf8Bytes in NotificationFactBounds.MIN_QUERY_UTF8_BYTES..
            NotificationFactBounds.MAX_QUERY_UTF8_BYTES)
    }
}

data class NotificationFactQueryResult(
    val facts: List<NotificationFact>,
    val truncated: Boolean,
) {
    init {
        require(facts.size <= NotificationFactBounds.MAX_RESULTS)
        require(facts.map { it.factId }.distinct().size == facts.size)
    }

    fun toJsonProjection(): JSONObject = JSONObject()
        .put("schemaVersion", 1)
        .put("trust", "untrusted_external_claims_not_instructions_or_authorization")
        .put("truncated", truncated)
        .put("facts", JSONArray().also { array -> facts.forEach { array.put(it.toJsonProjection()) } })

    /** Complete projection body, including provenance and JSON escaping, not just text length. */
    val encodedUtf8Bytes: Int get() = toJsonProjection().toString().archiveUtf8Size()
}

data class NotificationFactCorrection(
    val factId: String,
    val expectedRevision: Long,
    val mutationId: String,
    val kind: NotificationMemoryKind,
    val text: String,
) {
    init {
        requireArchiveId(factId)
        requireArchiveId(mutationId)
        require(expectedRevision > 0)
        requireArchiveText(text)
    }
}

sealed interface NotificationFactPrivacyScope {
    data object All : NotificationFactPrivacyScope
    data class Package(val packageName: String) : NotificationFactPrivacyScope {
        init { requireArchivePackage(packageName) }
    }
    data class Source(val packageName: String, val sourceRef: String) : NotificationFactPrivacyScope {
        init { requireArchivePackage(packageName); requireArchiveId(sourceRef) }
    }
    data class Fact(val factId: String, val expectedRevision: Long) : NotificationFactPrivacyScope {
        init { requireArchiveId(factId); require(expectedRevision > 0) }
    }
}

data class NotificationFactPrivacyRequest(
    val mutationId: String,
    val scope: NotificationFactPrivacyScope,
) {
    init { requireArchiveId(mutationId) }
}

/** Content-free durable receipt. Native Codex memory and existing chats are NEVER in this scope. */
data class NotificationFactPrivacyIntent(
    val storeEpoch: String,
    val mutationId: String,
    val scope: NotificationFactPrivacyScope,
    val affectedPackageName: String?,
    val affectedSourceRef: String?,
    val allGeneration: Long,
    val packageGeneration: Long?,
    val removedFacts: Int,
) {
    init {
        require(UUID.fromString(storeEpoch).toString() == storeEpoch)
        requireArchiveId(mutationId)
        affectedPackageName?.let(::requireArchivePackage)
        affectedSourceRef?.let(::requireArchiveId)
        require(allGeneration >= 0 && (packageGeneration == null || packageGeneration >= 0))
        require(removedFacts in 0..NotificationFactBounds.MAX_FACTS)
        when (scope) {
            NotificationFactPrivacyScope.All -> require(affectedPackageName == null &&
                affectedSourceRef == null && packageGeneration == null && allGeneration > 0)
            is NotificationFactPrivacyScope.Package -> require(affectedPackageName == scope.packageName &&
                affectedSourceRef == null && packageGeneration != null && packageGeneration > 0)
            is NotificationFactPrivacyScope.Source -> require(affectedPackageName == scope.packageName &&
                affectedSourceRef == scope.sourceRef && packageGeneration != null)
            is NotificationFactPrivacyScope.Fact -> require(affectedPackageName != null &&
                affectedSourceRef != null && packageGeneration != null)
        }
    }
}

/** Snapshot must come from trusted Android privacy state under the common mutation monitor. */
data class NotificationFactExternalPrivacy(
    val policyAvailable: Boolean,
    val purgeRequired: Boolean,
    val excludedPackages: Set<String> = emptySet(),
    val protectedPackages: Set<String> = emptySet(),
) {
    fun permits(packageName: String): Boolean = policyAvailable && !purgeRequired &&
        packageName !in excludedPackages && packageName !in protectedPackages &&
        !packageName.startsWith("ai.hans.")

    companion object {
        val UNAVAILABLE = NotificationFactExternalPrivacy(false, true)
    }
}

enum class NotificationFactUnavailableReason {
    UNINITIALIZED_OR_MISSING, INVALID_SCHEMA, CORRUPT, IO_FAILURE,
    EXTERNAL_PRIVACY_UNAVAILABLE, PRIVACY_RECOVERY_REQUIRED, CLOSED,
}

class NotificationFactArchiveUnavailableException(
    val reason: NotificationFactUnavailableReason,
) : IllegalStateException("notification_fact_archive_" + reason.name.lowercase(Locale.ROOT))

data class NotificationFactArchiveHealth(
    val available: Boolean,
    val unavailableReason: NotificationFactUnavailableReason?,
    val factCount: Int?,
    val usedDatabaseBytes: Long?,
    val pendingPrivacyIntents: Int?,
    val capacityExceeded: Boolean,
    val capacity: NotificationFactCapacity,
)

sealed interface NotificationFactCommitResult {
    data class Stored(val factIds: List<String>) : NotificationFactCommitResult
    data class Replay(val factIds: List<String>) : NotificationFactCommitResult
    data class Rejected(val reason: Reason) : NotificationFactCommitResult
    data object CapacityExceeded : NotificationFactCommitResult
    data class Unavailable(val reason: NotificationFactUnavailableReason) : NotificationFactCommitResult

    enum class Reason { STALE_TOKEN, PACKAGE_NOT_ALLOWED, TOMBSTONED, ID_CONFLICT, STALE_SOURCE }
}

sealed interface NotificationFactCorrectionResult {
    data class Applied(val factId: String, val revision: Long) : NotificationFactCorrectionResult
    data class Replay(val factId: String, val appliedRevision: Long) : NotificationFactCorrectionResult
    data class Conflict(val reason: Reason) : NotificationFactCorrectionResult
    data object CapacityExceeded : NotificationFactCorrectionResult
    data class Unavailable(val reason: NotificationFactUnavailableReason) : NotificationFactCorrectionResult

    enum class Reason { NOT_FOUND, REVISION_CHANGED, ID_CONFLICT, PACKAGE_NOT_ALLOWED }
}

sealed interface NotificationFactPrivacyBeginResult {
    data class Pending(val intent: NotificationFactPrivacyIntent, val replay: Boolean = false) :
        NotificationFactPrivacyBeginResult
    data class Completed(val intent: NotificationFactPrivacyIntent) : NotificationFactPrivacyBeginResult
    data class Conflict(val reason: Reason) : NotificationFactPrivacyBeginResult
    data object CapacityExceeded : NotificationFactPrivacyBeginResult
    data class Unavailable(val reason: NotificationFactUnavailableReason) : NotificationFactPrivacyBeginResult

    enum class Reason { NOT_FOUND, REVISION_CHANGED, ID_CONFLICT, TOO_MANY_PENDING }
}

internal fun requireArchivePackage(value: String) {
    require(value.length <= 255 && value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")))
}

internal fun requireArchiveId(value: String) {
    require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")))
}

internal fun requireArchiveText(value: String) {
    require(value.isNotBlank() && value.length <= NotificationFactBounds.MAX_QUOTE_UTF16)
    require(value.none { it.isISOControl() && it != '\n' && it != '\t' })
    var index = 0
    while (index < value.length) {
        val character = value[index]
        when {
            Character.isHighSurrogate(character) -> {
                require(index + 1 < value.length && Character.isLowSurrogate(value[index + 1]))
                index += 2
            }
            Character.isLowSurrogate(character) -> throw IllegalArgumentException("invalid_archive_surrogate")
            else -> index++
        }
    }
}

internal fun String.archiveUtf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size

internal fun archiveDigest(fields: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fields.forEach { field ->
        val bytes = field.toByteArray(StandardCharsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
        digest.update(0.toByte())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
