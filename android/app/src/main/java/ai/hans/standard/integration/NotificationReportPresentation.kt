package ai.hans.standard.integration

import ai.hans.standard.notifications.hooks.NotificationEventReport
import ai.hans.standard.notifications.hooks.notificationEventSha256

/** A newly admitted notice belongs after current visible conversation activity, including tools. */
internal fun createNotificationReport(
    id: String,
    text: String,
    timeline: List<ClientTimelineItem>,
    setupTurnIds: Set<String> = emptySet(),
): NotificationEventReport = NotificationEventReport(
    id = id,
    text = text,
    textSha256 = notificationEventSha256(text),
    timelineAnchorId = timeline.filterNot { item ->
        item.turnId in setupTurnIds && item.role in setOf(ClientTimelineRole.TOOL, ClientTimelineRole.SYSTEM)
    }.latestVisibleChatTimelineId(),
)
