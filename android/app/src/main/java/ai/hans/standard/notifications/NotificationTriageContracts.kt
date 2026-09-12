package ai.hans.standard.notifications

import ai.hans.standard.phone.notifications.NotificationEventKind
import ai.hans.standard.phone.notifications.NotificationInboxEvent
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * A deliberately small, untrusted representation of a system notification.
 *
 * Text in this value originated outside Hans. It is data only: callers must never concatenate it
 * into system/developer instructions or send it to the interactive Codex session.
 */
data class UntrustedNotificationEnvelope(
    val sourceSequence: Long,
    val kind: NotificationEventKind,
    val observedAtEpochMillis: Long,
    val packageName: String,
    val androidKey: String,
    val title: String,
    val text: String,
    val subtext: String,
    val category: String,
    val channelId: String,
    val ongoing: Boolean,
    val clearable: Boolean,
)

enum class NotificationDeliveryState {
    PENDING_RESTRICTED_TRIAGE,
    RESTRICTED_TRIAGE_IN_PROGRESS,
    /** A validated, typed suggestion is durably waiting for the narrow UI/TTS sink. */
    SUGGESTED_TO_USER,
    USER_DELIVERY_IN_PROGRESS,
    /** The private sink stage is durable; activation must still succeed before user delivery. */
    DELIVERY_COMMITTED_PENDING_ACTIVATION,
    DELIVERED_TO_USER,
    DISMISSED_BY_TRIAGE,
    TRIAGE_FAILED,
    USER_DELIVERY_FAILED,
}

enum class NotificationUrgency {
    LOW,
    NORMAL,
    HIGH,
}

/** The only read-only context adapters the restricted classifier may request. */
internal enum class NotificationEnrichmentAdapterKind(val wireName: String) {
    HTTPS_METADATA("https_metadata"),
    CONFIRMED_PROFILE("confirmed_profile"),
    CALENDAR("calendar"),
    RECENT_NOTIFICATIONS("recent_notifications"),
    ;

    companion object {
        fun fromWireName(value: String): NotificationEnrichmentAdapterKind? =
            entries.firstOrNull { it.wireName == value }
    }
}

internal enum class NotificationTriageConfidence(val wireName: String) {
    MEDIUM("medium"),
    HIGH("high"),
}

/**
 * Strict first-pass result. It contains no user-facing prose and grants no general tool access.
 * [Enrich] merely selects from the fixed read-only adapter allowlist above.
 */
internal sealed interface RestrictedNotificationTriagePlan {
    val memoryCandidates: List<NotificationMemoryCandidate> get() = emptyList()
    data class Silent(
        val reason: NotificationDismissalReason,
        override val memoryCandidates: List<NotificationMemoryCandidate> = emptyList(),
    ) : RestrictedNotificationTriagePlan
    data class Discard(val reason: NotificationDismissalReason) : RestrictedNotificationTriagePlan

    data class SurfaceNow(
        val reason: String,
        val urgency: NotificationUrgency,
        val confidence: NotificationTriageConfidence,
        val entities: List<String>,
        override val memoryCandidates: List<NotificationMemoryCandidate> = emptyList(),
    ) : RestrictedNotificationTriagePlan

    data class Enrich(
        val reason: String,
        val urgency: NotificationUrgency,
        val confidence: NotificationTriageConfidence,
        val adapters: Set<NotificationEnrichmentAdapterKind>,
        val entities: List<String>,
        override val memoryCandidates: List<NotificationMemoryCandidate> = emptyList(),
    ) : RestrictedNotificationTriagePlan
}

internal data class NotificationLinkMetadata(
    val finalUrlOrigin: String,
    val title: String,
    val description: String,
)

internal data class NotificationCalendarContext(
    val title: String,
    val location: String,
    val beginEpochMillis: Long,
    val endEpochMillis: Long,
    val allDay: Boolean,
)

internal data class RecentNotificationContext(
    val sourcePackage: String,
    val observedAtEpochMillis: Long,
    val title: String,
    val text: String,
)

/** Bounded facts only. Provider failures are represented by an adapter name, never an error body. */
internal data class NotificationEnrichmentEvidence(
    val linkMetadata: List<NotificationLinkMetadata> = emptyList(),
    val confirmedProfileSummary: String? = null,
    val nearbyCalendar: List<NotificationCalendarContext> = emptyList(),
    val recentNotifications: List<RecentNotificationContext> = emptyList(),
    val unavailableAdapters: Set<NotificationEnrichmentAdapterKind> = emptySet(),
    /** Dynamic authority is rechecked immediately before and after isolated synthesis. */
    val authorityLease: NotificationEnrichmentAuthorityLease =
        NotificationEnrichmentAuthorityLease.ALWAYS,
) {
    companion object {
        val EMPTY = NotificationEnrichmentEvidence()
    }
}

/** A revocable, operation-scoped proof that private enrichment evidence may still be processed. */
internal fun interface NotificationEnrichmentAuthorityLease {
    fun isValid(): Boolean

    companion object {
        val ALWAYS = NotificationEnrichmentAuthorityLease { true }
    }
}

/** Implemented only by sources whose evidence depends on a revocable user/runtime grant. */
internal fun interface NotificationEnrichmentAuthoritySource {
    fun isAuthorizedNow(): Boolean
}

internal fun interface NotificationEnrichmentProvider {
    fun enrich(
        notification: UntrustedNotificationEnvelope,
        plan: RestrictedNotificationTriagePlan.Enrich,
    ): NotificationEnrichmentEvidence

    companion object {
        val NONE = NotificationEnrichmentProvider { _, plan ->
            NotificationEnrichmentEvidence(unavailableAdapters = plan.adapters)
        }
    }
}

internal interface PreemptibleNotificationEnrichmentProvider {
    fun preemptCurrent()
}

/**
 * The only output that is permitted to cross the isolation boundary into the launcher.
 * It is a display/voice suggestion, never an instruction or a tool invocation.
 */
data class UserFacingNotificationSuggestion(
    val summary: String,
    val urgency: NotificationUrgency,
)

enum class NotificationMemoryKind(val wireName: String) {
    EVENT_DETAIL("event_detail"),
    AVAILABILITY_UPDATE("availability_update"),
    RECURRING_PREFERENCE_CLAIM("recurring_preference_claim"),
}

enum class NotificationMemorySourceField(val wireName: String) {
    TITLE("title"), TEXT("text"), SUBTEXT("subtext"),
}

/**
 * Selected external claim, NOT an established owner fact or an instruction.
 * Span and hash are computed by the host from the exact sanitized classifier input.
 * Archive provenance/privacy ownership is attached separately when accepting the triage lease.
 */
data class NotificationMemoryCandidate(
    val kind: NotificationMemoryKind,
    val sourceField: NotificationMemorySourceField,
    val quote: String,
    val startUtf16: Int,
    val endUtf16: Int,
    val sourceSha256: String,
) {
    init {
        require(quote.isNotBlank() && quote.length <= 256)
        val maximumSourceUnits = when (sourceField) {
            NotificationMemorySourceField.TITLE -> NotificationTriageBounds.MAX_TITLE_BYTES
            NotificationMemorySourceField.TEXT -> NotificationTriageBounds.MAX_TEXT_BYTES
            NotificationMemorySourceField.SUBTEXT -> NotificationTriageBounds.MAX_SUBTEXT_BYTES
        }
        // Sanitized source fields are UTF-8 byte-bounded; their UTF-16 length cannot exceed
        // this bound. Check ordering and range before subtracting, avoiding Int overflow.
        require(startUtf16 >= 0 && endUtf16 >= startUtf16 && endUtf16 <= maximumSourceUnits)
        require(endUtf16 - startUtf16 == quote.length)
        require(sourceSha256.matches(Regex("[0-9a-f]{64}")))
        require(quote.indices.all { index ->
            val char = quote[index]
            when {
                Character.isHighSurrogate(char) ->
                    index + 1 < quote.length && Character.isLowSurrogate(quote[index + 1])
                Character.isLowSurrogate(char) ->
                    index > 0 && Character.isHighSurrogate(quote[index - 1])
                else -> true
            }
        })
    }
}

sealed interface RestrictedTriageDecision {
    val memoryCandidates: List<NotificationMemoryCandidate>
    data class NotRelevant(
        val reason: NotificationDismissalReason,
        override val memoryCandidates: List<NotificationMemoryCandidate> = emptyList(),
    ) : RestrictedTriageDecision

    data class SuggestUser(
        val suggestion: UserFacingNotificationSuggestion,
        override val memoryCandidates: List<NotificationMemoryCandidate> = emptyList(),
    ) : RestrictedTriageDecision
}

enum class NotificationDismissalReason {
    NOT_ACTIONABLE,
    DUPLICATE_OR_SUPERSEDED,
    NOTIFICATION_REMOVED,
    USER_CONTEXT_NOT_RELEVANT,
    INSUFFICIENT_INFORMATION,
    /** A private staged delivery aged out before it was allowed to cross the user boundary. */
    DELIVERY_RETENTION_EXPIRED,
}

data class NotificationDeliveryReceipt(
    val id: String,
    val sourceSequence: Long,
    val packageName: String,
    val state: NotificationDeliveryState,
    val queuedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val triageAttempts: Int,
    val suggestion: UserFacingNotificationSuggestion?,
    val dismissalReason: NotificationDismissalReason?,
    val userDeliveryAttempts: Int = 0,
    val deliveredAtEpochMillis: Long? = null,
)

data class RestrictedTriageWorkItem(
    val receipt: NotificationDeliveryReceipt,
    /** Opaque, short-lived lease. It is not user-visible and must not be logged. */
    val claimToken: String,
    val notification: UntrustedNotificationEnvelope,
)

/**
 * The complete payload allowed to cross from restricted triage to the launcher.
 *
 * It intentionally contains no title, body, Android key, actions, or other source notification
 * fields. [idempotencyKey] is stable across retries so a UI/TTS sink can reject duplicates.
 */
data class UserFacingNotificationDelivery(
    val receiptId: String,
    val idempotencyKey: String,
    val suggestion: UserFacingNotificationSuggestion,
    /** Irreversible grouping key for update/removal supersession; it exposes no package/key text. */
    val supersessionKey: String = "",
    /**
     * Exact durable Queue horizon for this receipt. The sink may erase content earlier, but must
     * retain a non-surfacing activation receipt until this instant or an explicit terminal ack.
     */
    val activationExpiresAtEpochMillis: Long = Long.MAX_VALUE,
    /** Local Queue intake time, never the source app's notification/post timestamp. */
    val sourceReceivedAtEpochMillis: Long? = null,
)

/** Internal lease metadata that must never cross into the launcher callback. */
internal data class UserFacingNotificationDeliveryLease(
    val delivery: UserFacingNotificationDelivery,
    /** Opaque, short-lived lease. It must not be logged. */
    val claimToken: String,
)

enum class UserFacingDeliveryDisposition {
    /** The sink durably accepted this idempotency key for chat/TTS projection. */
    ACCEPTED,
    /** No sink is attached or delivery failed transiently; retry without exposing source text. */
    RETRY,
}

/**
 * Typed result of activating a durably staged suggestion.
 *
 * [SUPPRESSED] is terminal but was deliberately never shown; it must never be recorded as a
 * successful user delivery. [RETRY] is the only transient/missing result.
 */
enum class UserFacingNotificationActivationDisposition {
    ACTIVE,
    SUPPRESSED,
    RETRY,
}

/**
 * Narrow integration boundary for chat/TTS. Implementations receive only validated suggestions,
 * run off the Android main thread, and must use [UserFacingNotificationDelivery.idempotencyKey]
 * to suppress duplicates.
 */
fun interface UserFacingNotificationSuggestionSink {
    /** Stages the validated suggestion durably, but must not begin chat/TTS projection yet. */
    fun deliver(delivery: UserFacingNotificationDelivery): UserFacingDeliveryDisposition

    /** Prevents an unattached UI from consuming bounded retry attempts. */
    fun isReady(): Boolean = true

    /**
     * Resolves a previously staged suggestion. [UserFacingNotificationActivationDisposition.RETRY]
     * keeps the durable queue record activation-pending; [ACTIVE] confirms visible/idempotent
     * delivery, while [SUPPRESSED] terminalizes it without ever surfacing it.
     */
    fun activate(
        delivery: UserFacingNotificationDelivery,
    ): UserFacingNotificationActivationDisposition =
        UserFacingNotificationActivationDisposition.ACTIVE

    /**
     * Called only after the Queue's matching terminal write succeeds. Implementations use this
     * ack to remove a suppression tombstone or clear an active receipt fence. Failure is safe:
     * the content-erased receipt remains bounded by [UserFacingNotificationDelivery.activationExpiresAtEpochMillis].
     */
    fun finalizeActivation(
        delivery: UserFacingNotificationDelivery,
        disposition: UserFacingNotificationActivationDisposition,
    ) = Unit

    /** Compensating rollback if queue completion fails after staging. */
    fun revoke(delivery: UserFacingNotificationDelivery) = Unit
}

/** Typed, trusted runtime state that may influence relevance without adding free-form prompts. */
data class NotificationRelevanceContext(
    val userIsDictating: Boolean = false,
    val liveVoiceIsActive: Boolean = false,
    val screenIsInteractive: Boolean = false,
    val quietModeIsActive: Boolean = false,
    val userLocale: String = "und",
    val timeZoneId: String = "UTC",
)

fun interface NotificationRelevanceContextProvider {
    fun current(): NotificationRelevanceContext

    companion object {
        val EMPTY = NotificationRelevanceContextProvider { NotificationRelevanceContext() }
    }
}

sealed interface NotificationIngressResult {
    data class Queued(
        val receipt: NotificationDeliveryReceipt,
        val inFlightModelSuperseded: Boolean = false,
    ) : NotificationIngressResult
    data class Duplicate(val receipt: NotificationDeliveryReceipt) : NotificationIngressResult
    data class ExcludedOwnNotification(val packageName: String) : NotificationIngressResult
    data class IgnoredEventKind(val kind: NotificationEventKind) : NotificationIngressResult
    data class CancelledRemovedNotification(
        val recordsCancelled: Int,
        val inFlightModelCancelled: Boolean,
    ) : NotificationIngressResult
    data object RejectedInvalidSequence : NotificationIngressResult
    data object RejectedAtCapacity : NotificationIngressResult
}

internal object NotificationSupersessionKey {
    fun forSource(packageName: String, androidKey: String): String {
        if (packageName.isBlank() || androidKey.isBlank()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$packageName\u0000$androidKey".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "source:$digest"
    }
}

internal data class NotificationCancellationResult(
    val recordsCancelled: Int,
    val inFlightModelCancelled: Boolean,
)

sealed interface TriageCompletionResult {
    data class Accepted(val receipt: NotificationDeliveryReceipt) : TriageCompletionResult
    data object MissingOrExpiredLease : TriageCompletionResult
    data object InvalidDecision : TriageCompletionResult
}

sealed interface UserDeliveryCompletionResult {
    data class Accepted(val receipt: NotificationDeliveryReceipt) : UserDeliveryCompletionResult
    data object MissingOrExpiredLease : UserDeliveryCompletionResult
}

/**
 * Boundary for a future isolated executor. Its implementation must run with a restricted tool
 * profile and its own system prompt. In particular, it must not call the interactive Codex host,
 * steer the user's active thread, or receive the normal YOLO/full-access configuration.
 */
fun interface RestrictedNotificationTriageExecutor {
    fun triage(workItem: RestrictedTriageWorkItem): RestrictedTriageDecision
}

/** Optional cooperative boundary used to give a foreground user interaction immediate priority. */
internal interface PreemptibleRestrictedNotificationTriageExecutor {
    fun preemptCurrent()
}

internal class NotificationTriagePreemptedException : RuntimeException()

/** Filters Hans's own foreground/runtime/work notifications before they can enter any queue. */
class HansNotificationExclusionPolicy(
    ownPackageNames: Set<String>,
    private val additionalExclusion: (String) -> Boolean = { false },
) {
    private val ownPackages = ownPackageNames
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()

    init {
        require(ownPackages.isNotEmpty()) { "At least one Hans package name is required" }
    }

    fun excludes(event: NotificationInboxEvent): Boolean = excludesPackage(event.snapshot.packageName)

    fun excludesPackage(packageName: String): Boolean =
        packageName in ownPackages || runCatching { additionalExclusion(packageName) }.getOrDefault(true)
}

internal object NotificationTriageBounds {
    const val MAX_RECORDS = 256
    const val MAX_PENDING = 96
    const val MAX_TRIAGE_ATTEMPTS = 3
    const val MAX_USER_DELIVERY_ATTEMPTS = 8
    const val CLAIM_LEASE_MILLIS = 15 * 60 * 1_000L
    const val MAX_FILE_BYTES = 2 * 1_024 * 1_024
    const val MAX_TITLE_BYTES = 512
    const val MAX_TEXT_BYTES = 4 * 1_024
    const val MAX_SUBTEXT_BYTES = 1_024
    const val MAX_PACKAGE_BYTES = 255
    const val MAX_ANDROID_KEY_BYTES = 1_024
    const val MAX_SUGGESTION_BYTES = 768
    const val MAX_MODEL_RESPONSE_BYTES = 8 * 1_024
    const val MAX_BACKLOG_AGE_MILLIS = 6 * 60 * 60 * 1_000L
    const val PER_KEY_DEBOUNCE_MILLIS = 750L
    const val GLOBAL_TRIAGE_BUDGET_WINDOW_MILLIS = 60 * 1_000L
    const val MAX_TRIAGE_CALLS_PER_BUDGET_WINDOW = 12

    fun boundedText(value: String, byteLimit: Int): String {
        val clean = sanitize(value)
        if (clean.toByteArray(StandardCharsets.UTF_8).size <= byteLimit) return clean

        val result = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < clean.length) {
            val codePoint = clean.codePointAt(index)
            val encoded = String(Character.toChars(codePoint)).toByteArray(StandardCharsets.UTF_8)
            if (bytes + encoded.size > byteLimit) break
            result.appendCodePoint(codePoint)
            bytes += encoded.size
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }

    private fun sanitize(value: String): String {
        val result = StringBuilder(value.length)
        var previousWasWhitespace = false
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val isControl = Character.isISOControl(codePoint)
            val isFormat = Character.getType(codePoint) == Character.FORMAT.toInt()
            when {
                isControl || isFormat -> {
                    if (Character.isWhitespace(codePoint) && result.isNotEmpty()) {
                        previousWasWhitespace = true
                    }
                }
                Character.isWhitespace(codePoint) -> {
                    if (result.isNotEmpty()) previousWasWhitespace = true
                }
                else -> {
                    if (previousWasWhitespace && result.isNotEmpty()) result.append(' ')
                    result.appendCodePoint(codePoint)
                    previousWasWhitespace = false
                }
            }
            index += Character.charCount(codePoint)
        }
        return result.toString().trim()
    }
}
