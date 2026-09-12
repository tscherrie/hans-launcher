package ai.hans.standard.phone.notifications

internal object NotificationDigestBuilder {
    fun build(
        events: List<NotificationInboxEvent>,
        afterSequenceExclusive: Long,
        maxEvents: Int,
        maxUtf8Bytes: Int,
        sourceHasMore: Boolean,
    ): NotificationDigest {
        val eventLimit = maxEvents.coerceIn(1, NotificationLimits.MAX_DIGEST_EVENTS)
        val byteBudget = maxUtf8Bytes.coerceIn(0, NotificationLimits.MAX_DIGEST_UTF8_BYTES)
        val ordered = events
            .asSequence()
            .filter { it.sequence > afterSequenceExclusive }
            .sortedBy { it.sequence }
            .take(eventLimit)
            .toList()

        val output = StringBuilder()
        var outputBytes = 0
        var included = 0
        var cursor = afterSequenceExclusive.coerceAtLeast(0)
        var budgetStopped = false

        for (event in ordered) {
            val line = renderLine(event)
            val separator = if (output.isEmpty()) "" else "\n"
            val candidateBytes = SafeNotificationText.utf8Size(separator) +
                SafeNotificationText.utf8Size(line)
            val remaining = byteBudget - outputBytes
            if (candidateBytes <= remaining) {
                output.append(separator).append(line)
                outputBytes += candidateBytes
                included += 1
                cursor = event.sequence
                continue
            }

            if (included == 0 && remaining > 0) {
                val truncated = SafeNotificationText.boundedUtf8(line, remaining)
                output.append(truncated)
                outputBytes = SafeNotificationText.utf8Size(truncated)
                included = 1
                cursor = event.sequence
            }
            budgetStopped = true
            break
        }

        val eligibleCount = events.count { it.sequence > afterSequenceExclusive }
        val hasMore = sourceHasMore || budgetStopped || included < eligibleCount
        return NotificationDigest(
            text = output.toString(),
            eventCount = included,
            utf8Bytes = outputBytes,
            nextAfterSequenceExclusive = cursor,
            hasMore = hasMore,
        )
    }

    private fun renderLine(event: NotificationInboxEvent): String {
        val snapshot = event.snapshot
        val packageName = SafeNotificationText.content(
            snapshot.packageName,
            NotificationLimits.PACKAGE_UTF8_BYTES,
        )
        val title = SafeNotificationText.content(snapshot.title, NotificationLimits.TITLE_UTF8_BYTES)
        val text = SafeNotificationText.content(snapshot.text, NotificationLimits.TEXT_UTF8_BYTES)
        val remoteReplyCount = snapshot.actions.count { action -> action.remoteInputs.isNotEmpty() }
        return buildString {
            append('#').append(event.sequence)
            append(" | ").append(event.kind.name.lowercase())
            append(" | ").append(packageName)
            if (title.isNotBlank()) append(" | ").append(title)
            if (text.isNotBlank()) append(": ").append(text)
            if (snapshot.actions.isNotEmpty()) {
                append(" | actions=").append(snapshot.actions.size)
            }
            if (remoteReplyCount > 0) {
                append(" reply=").append(remoteReplyCount)
            }
        }
    }
}
