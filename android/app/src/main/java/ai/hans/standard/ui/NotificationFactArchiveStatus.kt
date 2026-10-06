package ai.hans.standard.ui

import ai.hans.standard.R
import ai.hans.standard.localization.HansTextResolver
import java.text.NumberFormat
import ai.hans.standard.notifications.NotificationTriageQueueHealth
import ai.hans.standard.phone.notifications.facts.NotificationFactArchiveHealth

data class NotificationFactArchiveStatus(
    val loading: Boolean = false,
    val available: Boolean? = null,
    val factCount: Int? = null,
    val usedBytes: Long? = null,
    val pendingFacts: Int? = null,
    val capacityDrops: Long? = null,
    val capacityExceeded: Boolean? = null,
) {
    fun summary(text: HansTextResolver): String {
        if (loading) return text.text(R.string.presentation_archive_loading)
        val unknown = text.text(R.string.presentation_unknown)
        val numbers = NumberFormat.getIntegerInstance(text.locale)
        fun number(value: Number?): String = value?.let(numbers::format) ?: unknown
        return buildString {
            append(when (available) {
                true -> text.text(R.string.presentation_archive_available)
                false -> text.text(R.string.presentation_archive_unavailable)
                null -> text.text(R.string.presentation_archive_unknown)
            })
            append('\n')
            append(text.text(R.string.presentation_archive_counts,
                number(factCount),
                usedBytes?.let { text.text(R.string.presentation_archive_bytes, numbers.format(it)) } ?: unknown,
                number(pendingFacts), number(capacityDrops)))
            if (capacityExceeded == true) {
                append('\n')
                append(text.text(R.string.presentation_archive_capacity))
            }
        }
    }
    companion object {
        fun disclosure(text: HansTextResolver): String = text.text(R.string.presentation_archive_disclosure)
        internal fun from(health: NotificationFactArchiveHealth?, queue: NotificationTriageQueueHealth?) =
            NotificationFactArchiveStatus(
                available = health?.available, factCount = health?.factCount,
                usedBytes = health?.usedDatabaseBytes,
                pendingFacts = (queue as? NotificationTriageQueueHealth.Available)?.factOutboxCount,
                capacityDrops = (queue as? NotificationTriageQueueHealth.Available)?.factOutboxCapacityDrops,
                capacityExceeded = health?.capacityExceeded,
            )
    }
}

/** Main-thread-owned token: lifecycle exits and newer reads reject delayed IO results. */
internal class NotificationFactStatusGeneration {
    private var current = Any()
    fun begin(): Any = Any().also { current = it }
    fun invalidate() { current = Any() }
    fun accepts(token: Any): Boolean = token === current
}
