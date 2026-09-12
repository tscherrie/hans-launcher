package ai.hans.standard.integration

import java.time.Instant

/** Builds a bounded data-only context block from already isolated triage output. */
internal object ValidatedNotificationTurnContext {
    private const val ENVELOPE_MARKER = "<hans_validated_notification_context"

    /** Recognizes the legacy notification envelope at the full-access dispatch boundary. */
    fun isEnvelope(text: String): Boolean = text.contains(ENVELOPE_MARKER)

    fun build(items: List<ValidatedNotificationAnnouncement>): String? {
        if (items.isEmpty()) return null
        return buildString {
            appendLine(
                "<hans_validated_notification_context " +
                    "data_classification=\"untrusted_external_summaries\">",
            )
            items.forEach { item ->
                append("<notification observed=\"")
                append(
                    Instant.ofEpochMilli(item.createdAtEpochMillis.coerceAtLeast(0))
                        .toString().encodedAsXmlText(),
                )
                append("\" urgency=\"")
                append(item.urgency.name.lowercase().encodedAsXmlText())
                appendLine("\">")
                append("<summary>")
                append(item.summary.encodedAsXmlText())
                appendLine("</summary>")
                appendLine("</notification>")
            }
            append("</hans_validated_notification_context>")
        }
    }

    /**
     * The summary is model-authored but validated, not trusted markup. Encode every character that
     * could create or terminate an XML-like element, and keep line breaks inside the data value so
     * a forged summary cannot escape into a sibling prompt line.
     */
    private fun String.encodedAsXmlText(): String = buildString(length) {
        this@encodedAsXmlText.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    '\n' -> "&#10;"
                    '\r' -> "&#13;"
                    else -> character
                },
            )
        }
    }
}
