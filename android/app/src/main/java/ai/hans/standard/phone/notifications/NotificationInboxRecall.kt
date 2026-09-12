package ai.hans.standard.phone.notifications

import ai.hans.standard.notifications.sanitizeRestrictedNotificationText
import java.nio.charset.StandardCharsets
import java.text.Normalizer

internal enum class NotificationInboxRecallMode(val wireName: String) {
    RECENT("recent"),
    RELEVANT("relevant"),
}

internal data class NotificationInboxRecallQuery(
    val mode: NotificationInboxRecallMode,
    val sinceEpochMillis: Long,
    val untilEpochMillis: Long = Long.MAX_VALUE,
    val sourcePackage: String? = null,
    val terms: List<String> = emptyList(),
    val limit: Int = DEFAULT_RESULT_LIMIT,
    val maxContentUtf8Bytes: Int = DEFAULT_CONTENT_UTF8_BYTES,
) {
    init {
        require(sinceEpochMillis >= 0) { "notification_recall_since" }
        require(untilEpochMillis >= sinceEpochMillis) { "notification_recall_window" }
        require(limit in 1..MAX_RESULT_LIMIT) { "notification_recall_limit" }
        require(maxContentUtf8Bytes in MIN_CONTENT_UTF8_BYTES..MAX_CONTENT_UTF8_BYTES) {
            "notification_recall_bytes"
        }
        sourcePackage?.let(::requireValidRecallPackage)
        when (mode) {
            NotificationInboxRecallMode.RECENT -> require(terms.isEmpty()) {
                "notification_recall_recent_terms"
            }
            NotificationInboxRecallMode.RELEVANT -> require(terms.size in 1..MAX_TERMS) {
                "notification_recall_terms"
            }
        }
        require(terms.all { term ->
            term.isNotBlank() && utf8Bytes(term) <= MAX_TERM_UTF8_BYTES
        }) { "notification_recall_term" }
        val normalizedTerms = terms.map(::normalizeRecallTerm)
        require(normalizedTerms.all(String::isNotBlank)) {
            "notification_recall_terms_normalized_empty"
        }
        require(normalizedTerms.distinct().size == normalizedTerms.size) {
            "notification_recall_duplicate_terms"
        }
    }

    companion object {
        const val DEFAULT_RESULT_LIMIT = 8
        const val MAX_RESULT_LIMIT = 8
        const val DEFAULT_CONTENT_UTF8_BYTES = 8 * 1_024
        const val MIN_CONTENT_UTF8_BYTES = 512
        const val MAX_CONTENT_UTF8_BYTES = 16 * 1_024
        const val MAX_TERMS = 8
        const val MAX_TERM_UTF8_BYTES = 64
    }
}

internal data class NotificationInboxRecallEvent(
    val eventKind: String,
    val observedAtEpochMillis: Long,
    val sourcePackage: String,
    val title: String,
    val text: String,
    val subtext: String,
    val redactionApplied: Boolean,
    val matchedTerms: List<String>,
)

internal data class NotificationInboxRecallResult(
    val mode: NotificationInboxRecallMode,
    val scanComplete: Boolean,
    val truncated: Boolean,
    val contentUtf8Bytes: Int,
    val events: List<NotificationInboxRecallEvent>,
)

/**
 * A fail-closed projection from the private inbox into the interactive Codex thread.
 *
 * The entire scan is linearized with privacy mutations. Android keys and action metadata are used
 * only to collapse superseded records and are discarded before a result is built.
 */
internal class NotificationInboxRecallRetriever(
    private val source: NotificationInboxQuerySource,
    private val management: NotificationInboxManagementSource,
) {
    fun retrieve(query: NotificationInboxRecallQuery): NotificationInboxRecallResult =
        synchronized(NotificationPrivacyMutationCoordinator.lock) {
            retrieveLocked(query)
        }

    private fun retrieveLocked(query: NotificationInboxRecallQuery): NotificationInboxRecallResult {
        val privacyBefore = management.privacyStatus()
        check(privacyBefore.policyAvailable) { "notification_recall_privacy_unavailable" }
        val scanLimit = privacyBefore.retention.maxEvents
            .coerceIn(1, NotificationPrivacyBounds.MAX_MAX_EVENTS)
        val latestByIdentity = linkedMapOf<NotificationIdentity, LatestProjection>()
        var cursor = 0L
        var scanned = 0
        var pageCalls = 0
        val maxPageCalls = ((scanLimit + PAGE_SIZE - 1) / PAGE_SIZE) + EMPTY_PAGE_ALLOWANCE

        while (scanned < scanLimit) {
            pageCalls += 1
            check(pageCalls <= maxPageCalls) { "notification_recall_page_call_limit" }
            val requested = minOf(PAGE_SIZE, scanLimit - scanned)
            val page = source.queryPage(afterSequenceExclusive = cursor, limit = requested)
            validatePage(page, cursor, requested)
            page.events.forEach { event ->
                validateEventAgainstPrivacy(event, privacyBefore)
                val identity = NotificationIdentity(
                    packageName = event.snapshot.packageName,
                    androidKey = event.snapshot.androidKey,
                )
                latestByIdentity[identity] = projectLatest(event)
            }
            scanned += page.events.size
            if (!page.hasMore) {
                cursor = page.nextAfterSequenceExclusive
                break
            }
            check(page.nextAfterSequenceExclusive > cursor) { "notification_recall_cursor_stalled" }
            cursor = page.nextAfterSequenceExclusive
            check(scanned < scanLimit) { "notification_recall_retention_overflow" }
        }

        val privacyAfter = management.privacyStatus()
        check(privacyAfter == privacyBefore && privacyAfter.policyAvailable) {
            "notification_recall_privacy_changed"
        }

        val normalizedTerms = query.terms.map(::normalizeRecallTerm)
        val matching = latestByIdentity.values.asSequence()
            .mapNotNull { projection -> (projection as? LatestProjection.Safe)?.event }
            .filter { event ->
                event.observedAtEpochMillis in query.sinceEpochMillis..query.untilEpochMillis &&
                    (query.sourcePackage == null || event.sourcePackage == query.sourcePackage)
            }
            .mapNotNull { event ->
                val matched = if (query.mode == NotificationInboxRecallMode.RELEVANT) {
                    val searchable = normalizeRecallTerm(
                        listOf(event.title, event.text, event.subtext).joinToString(" "),
                    )
                    normalizedTerms.filter { term -> containsBoundedTerm(searchable, term) }
                } else {
                    emptyList()
                }
                if (
                    query.mode == NotificationInboxRecallMode.RELEVANT &&
                    matched.isEmpty()
                ) {
                    null
                } else {
                    event.copy(matchedTerms = matched)
                }
            }
            .sortedWith(
                compareByDescending<NotificationInboxRecallEvent> { it.observedAtEpochMillis }
                    .thenBy { it.sourcePackage },
            )
            .toList()

        val bounded = mutableListOf<NotificationInboxRecallEvent>()
        var contentBytes = 0
        var truncated = matching.size > query.limit
        for (candidate in matching) {
            if (bounded.size >= query.limit) break
            val remaining = query.maxContentUtf8Bytes - contentBytes
            if (remaining <= 0) {
                truncated = true
                break
            }
            val fitted = fitContent(candidate, remaining)
            val fittedBytes = contentUtf8Bytes(fitted)
            if (fittedBytes <= 0) {
                truncated = true
                continue
            }
            bounded += fitted
            contentBytes += fittedBytes
            if (fittedBytes < contentUtf8Bytes(candidate)) truncated = true
        }

        return NotificationInboxRecallResult(
            mode = query.mode,
            scanComplete = true,
            truncated = truncated,
            contentUtf8Bytes = contentBytes,
            events = bounded,
        )
    }

    private fun validatePage(page: NotificationPage, after: Long, requested: Int) {
        check(page.events.size <= requested) { "notification_recall_page_oversized" }
        var previous = after
        page.events.forEach { event ->
            check(event.sequence > previous) { "notification_recall_sequence_invalid" }
            previous = event.sequence
        }
        check(page.nextAfterSequenceExclusive >= after) { "notification_recall_cursor_reversed" }
        if (page.events.isNotEmpty()) {
            check(page.nextAfterSequenceExclusive >= page.events.last().sequence) {
                "notification_recall_cursor_before_events"
            }
        }
        if (page.hasMore) {
            check(page.nextAfterSequenceExclusive > after) {
                "notification_recall_cursor_stalled"
            }
        }
    }

    private fun validateEventAgainstPrivacy(
        event: NotificationInboxEvent,
        privacy: NotificationPrivacyStatus,
    ) {
        val packageName = event.snapshot.packageName
        check(requireValidRecallPackage(packageName) == packageName) {
            "notification_recall_package_invalid"
        }
        check(
            packageName !in privacy.userExcludedPackages &&
                packageName !in privacy.protectedPackages &&
                !packageName.startsWith("ai.hans."),
        ) { "notification_recall_privacy_violation" }
        check(event.snapshot.androidKey.isNotBlank()) { "notification_recall_android_key_empty" }
        check(utf8Bytes(event.snapshot.androidKey) <= NotificationLimits.ANDROID_KEY_UTF8_BYTES) {
            "notification_recall_android_key_size"
        }
        check(event.observedAtEpochMillis >= 0) { "notification_recall_observed_at" }
    }

    private fun projectLatest(event: NotificationInboxEvent): LatestProjection {
        val sanitized = sanitizeRestrictedNotificationText(
            title = event.snapshot.title,
            text = event.snapshot.text,
            subtext = event.snapshot.subtext,
        ) ?: return LatestProjection.Suppressed
        val title = omitUrls(sanitized.title)
        val text = omitUrls(sanitized.text)
        val subtext = omitUrls(sanitized.subtext)
        return LatestProjection.Safe(
            NotificationInboxRecallEvent(
                eventKind = event.kind.name.lowercase(),
                observedAtEpochMillis = event.observedAtEpochMillis,
                sourcePackage = event.snapshot.packageName,
                title = title.value,
                text = text.value,
                subtext = subtext.value,
                redactionApplied = sanitized.redactionApplied ||
                    title.replacements > 0 || text.replacements > 0 || subtext.replacements > 0,
                matchedTerms = emptyList(),
            ),
        )
    }

    private sealed interface LatestProjection {
        data class Safe(val event: NotificationInboxRecallEvent) : LatestProjection
        data object Suppressed : LatestProjection
    }

    private data class NotificationIdentity(val packageName: String, val androidKey: String)

    private data class UrlOmission(val value: String, val replacements: Int)

    private fun omitUrls(value: String): UrlOmission {
        var replacements = 0
        var safe = value.replace(RESTRICTED_ACTION_URL_MARKER, OMITTED_LINK).also {
            if (it != value) replacements += 1
        }
        ACTIONABLE_REFERENCE_PATTERNS.forEach { pattern ->
            safe = pattern.replace(safe) {
                replacements += 1
                OMITTED_LINK
            }
        }
        return UrlOmission(safe, replacements)
    }

    private fun fitContent(
        event: NotificationInboxRecallEvent,
        budget: Int,
    ): NotificationInboxRecallEvent {
        var remaining = budget
        fun fit(value: String): String {
            if (remaining <= 0) return ""
            val fitted = truncateUtf8(value, remaining)
            remaining -= utf8Bytes(fitted)
            return fitted
        }
        return event.copy(
            title = fit(event.title),
            text = fit(event.text),
            subtext = fit(event.subtext),
        )
    }

    private fun contentUtf8Bytes(event: NotificationInboxRecallEvent): Int =
        utf8Bytes(event.title) + utf8Bytes(event.text) + utf8Bytes(event.subtext)

    private fun truncateUtf8(value: String, maximumBytes: Int): String {
        if (utf8Bytes(value) <= maximumBytes) return value
        if (maximumBytes <= 0) return ""
        val suffix = "…"
        val suffixBytes = utf8Bytes(suffix)
        if (maximumBytes < suffixBytes) return ""
        val builder = StringBuilder()
        var used = 0
        var index = 0
        val contentBudget = maximumBytes - suffixBytes
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val chunk = String(Character.toChars(codePoint))
            val bytes = utf8Bytes(chunk)
            if (used + bytes > contentBudget) break
            builder.append(chunk)
            used += bytes
            index += Character.charCount(codePoint)
        }
        return builder.append(suffix).toString()
    }

    private fun containsBoundedTerm(value: String, term: String): Boolean {
        var start = value.indexOf(term)
        while (start >= 0) {
            val end = start + term.length
            val leftBoundary = start == 0 || !value[start - 1].isLetterOrDigit()
            val rightBoundary = end == value.length || !value[end].isLetterOrDigit()
            if (leftBoundary && rightBoundary) return true
            start = value.indexOf(term, start + 1)
        }
        return false
    }

    private companion object {
        const val PAGE_SIZE = NotificationLimits.MAX_PAGE_SIZE
        const val EMPTY_PAGE_ALLOWANCE = 2
        const val OMITTED_LINK = "[LINK_OMITTED]"
        const val RESTRICTED_ACTION_URL_MARKER = "[REDACTED_ACTION_URL]"
        val ACTIONABLE_REFERENCE_PATTERNS = listOf(
            Regex("(?iu)\\[[0-9a-f:.%]+](?::\\d{1,5})?(?:[/#?][^\\s<>()]*)?"),
            Regex(
                "(?iu)(?<![\\p{L}\\p{N}])(?=[0-9a-f:]{3,}(?:[/#?\\s]|$))" +
                    "(?=[0-9a-f:]*:[0-9a-f:]*:)[0-9a-f:]{3,}" +
                    "(?:%[A-Za-z0-9_.-]+)?(?:[/#?][^\\s<>()]*)?",
            ),
            Regex(
                "(?iu)(?<![\\p{L}\\p{N}])(?:\\d{1,3}\\.){3}\\d{1,3}" +
                    "(?::\\d{1,5})?(?:[/#?][^\\s<>()]*)?",
            ),
            // Includes http(s), mailto, tel, sms, geo, market, content, intent, android-app and
            // unknown application schemes. Over-redaction is preferable to leaking an action URI.
            Regex("(?iu)\\b[A-Za-z][A-Za-z0-9+.-]{0,31}:(?://)?[^\\s<>()]+"),
            Regex("(?iu)(?<![:\\p{L}\\p{N}])//[^\\s<>()]+"),
            Regex("(?iu)(?:https?://|www\\.)[^\\s<>()]+"),
            Regex(
                "(?iu)(?<![@\\p{L}\\p{N}_])" +
                    "(?:[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]{0,62})?\\.)+" +
                    "(?:[\\p{L}]{2,63}|xn--[\\p{L}\\p{N}-]{2,59})" +
                    "(?::\\d{1,5})?(?:[/?:#][^\\s<>()]*)?",
            ),
        )
    }
}

private fun utf8Bytes(value: String): Int = value.toByteArray(StandardCharsets.UTF_8).size

private fun normalizeRecallTerm(value: String): String = Normalizer
    .normalize(value, Normalizer.Form.NFKC)
    .lowercase()
    .trim()
    .replace(RECALL_WHITESPACE, " ")

internal fun requireValidRecallPackage(value: String): String {
    require(value.toByteArray(StandardCharsets.UTF_8).size <= NotificationLimits.PACKAGE_UTF8_BYTES) {
        "notification_recall_package_size"
    }
    require(RECALL_PACKAGE_NAME.matches(value)) { "notification_recall_package" }
    return value
}

private val RECALL_PACKAGE_NAME =
    Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*){0,31}$")
private val RECALL_WHITESPACE = Regex("\\s+")
