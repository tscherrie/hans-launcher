package ai.hans.standard.phone.notifications

import android.app.Notification
import android.app.RemoteInput
import android.service.notification.StatusBarNotification

internal object AndroidNotificationExtractor {
    fun upsert(
        statusBarNotification: StatusBarNotification,
        observedAtEpochMillis: Long,
    ): NotificationSignal.Upsert {
        val notification = statusBarNotification.notification
        return NotificationNormalizer.upsert(
            raw = RawNotificationSnapshot(
                packageName = statusBarNotification.packageName,
                androidKey = statusBarNotification.key,
                postTimeEpochMillis = statusBarNotification.postTime,
                notificationWhenEpochMillis = notification.`when`,
                title = notification.safeExtra(Notification.EXTRA_TITLE),
                text = notification.bestText(),
                subtext = notification.safeExtra(Notification.EXTRA_SUB_TEXT),
                category = notification.category,
                channelId = notification.channelId,
                ongoing = statusBarNotification.isOngoing,
                clearable = statusBarNotification.isClearable,
                actions = notification.safeActions(),
                agentChannelSource = ai.hans.standard.notifications.agentchannel.AndroidWhatsAppNotificationSource.extract(statusBarNotification),
            ),
            observedAtEpochMillis = observedAtEpochMillis,
        )
    }

    fun removed(
        statusBarNotification: StatusBarNotification,
        observedAtEpochMillis: Long,
        reason: Int?,
    ): NotificationSignal.Removed = NotificationNormalizer.removed(
        packageName = statusBarNotification.packageName,
        androidKey = statusBarNotification.key,
        observedAtEpochMillis = observedAtEpochMillis,
        reason = reason,
    )

    private fun Notification.bestText(): CharSequence? {
        val bigText = safeExtra(Notification.EXTRA_BIG_TEXT)
        if (!bigText.isNullOrBlank()) return bigText
        val text = safeExtra(Notification.EXTRA_TEXT)
        if (!text.isNullOrBlank()) return text
        return runCatching {
            extras
                ?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.filterNotNull()
                ?.joinToString(separator = " ")
        }.getOrNull()
    }

    private fun Notification.safeExtra(key: String): CharSequence? = runCatching {
        extras?.getCharSequence(key)
    }.getOrNull()

    private fun Notification.safeActions(): List<RawNotificationAction> = runCatching {
        actions
            ?.take(NotificationLimits.MAX_ACTIONS)
            ?.map { action ->
                RawNotificationAction(
                    title = action.title,
                    semanticAction = action.semanticAction,
                    isContextual = action.isContextual,
                    allowGeneratedReplies = action.getAllowGeneratedReplies(),
                    authenticationRequired = action.isAuthenticationRequired,
                    hasActionIntent = action.actionIntent != null,
                    remoteInputs = action.remoteInputs
                        ?.take(NotificationLimits.MAX_REMOTE_INPUTS_PER_ACTION)
                        ?.map { it.toRawMetadata() }
                        .orEmpty(),
                )
            }
            .orEmpty()
    }.getOrDefault(emptyList())

    private fun RemoteInput.toRawMetadata(): RawNotificationRemoteInput =
        RawNotificationRemoteInput(
            resultKey = resultKey,
            label = label,
            choices = choices?.toList().orEmpty(),
            allowFreeFormInput = allowFreeFormInput,
            allowedDataTypes = allowedDataTypes?.toList().orEmpty(),
            editChoicesBeforeSending = editChoicesBeforeSending,
        )
}
