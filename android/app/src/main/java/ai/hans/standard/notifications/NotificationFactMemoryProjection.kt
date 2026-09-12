package ai.hans.standard.notifications

import ai.hans.standard.phone.notifications.facts.NotificationFactQuery
import ai.hans.standard.phone.notifications.facts.NotificationFactRepository
import java.nio.charset.StandardCharsets

/** Read-only relevance candidates, never event evidence or confirmed owner-profile facts. */
internal class NotificationFactMemoryProjection(private val repository: NotificationFactRepository) {
    fun candidates(notification: UntrustedNotificationEnvelope): List<String> = runCatching {
        val safe = sanitizeRestrictedNotificationText(
            notification.title, notification.text, notification.subtext,
        ) ?: return@runCatching emptyList()
        val terms = notificationFactRelevanceTerms(listOf(safe.title, safe.text, safe.subtext))
            .filter { term ->
                term.any(Char::isLetter) && term.toByteArray(StandardCharsets.UTF_8).size <= 64
            }.take(4)
        if (terms.isEmpty()) return@runCatching emptyList()
        val result = repository.query(NotificationFactQuery(
            terms = terms, limit = 2, maxUtf8Bytes = 8192,
        ))
        if (result.facts.size > 2 || result.encodedUtf8Bytes > 8192) return@runCatching emptyList()
        result.facts.asSequence()
            .filter { fact ->
                notificationFactRelevanceTerms(listOf(fact.text)).any(terms::contains)
            }
            // Even owner corrections here stay unverified relevance hints, not confirmed profile.
            .map { it.text }.distinct().take(2).toList()
    }.getOrDefault(emptyList())
}
