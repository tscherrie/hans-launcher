package ai.hans.standard.phone.notifications

internal object NotificationReducer {
    fun reduce(
        previous: StoredNotificationState?,
        signal: NotificationSignal,
    ): NotificationReduction = when (signal) {
        is NotificationSignal.Upsert -> reduceUpsert(previous, signal)
        is NotificationSignal.Removed -> reduceRemoved(previous, signal)
    }

    private fun reduceUpsert(
        previous: StoredNotificationState?,
        signal: NotificationSignal.Upsert,
    ): NotificationReduction {
        val fingerprint = NotificationFingerprint.of(signal.snapshot)
        if (previous?.active == true && previous.fingerprint == fingerprint) {
            return NotificationReduction.IgnoreDuplicate(previous.lastSequence)
        }
        return NotificationReduction.Append(
            event = NotificationEventDraft(
                kind = if (previous?.active == true) {
                    NotificationEventKind.UPDATED
                } else {
                    NotificationEventKind.POSTED
                },
                observedAtEpochMillis = signal.observedAtEpochMillis,
                removalReason = null,
                snapshot = signal.snapshot,
            ),
            active = true,
            fingerprint = fingerprint,
        )
    }

    private fun reduceRemoved(
        previous: StoredNotificationState?,
        signal: NotificationSignal.Removed,
    ): NotificationReduction {
        if (previous != null && !previous.active) {
            return NotificationReduction.IgnoreDuplicate(previous.lastSequence)
        }
        val snapshot = previous?.snapshot ?: NotificationSnapshot(
            packageName = signal.packageName,
            androidKey = signal.androidKey,
            postTimeEpochMillis = 0,
            notificationWhenEpochMillis = 0,
            title = "",
            text = "",
            subtext = "",
            category = "",
            channelId = "",
            ongoing = false,
            clearable = false,
            actions = emptyList(),
        )
        return NotificationReduction.Append(
            event = NotificationEventDraft(
                kind = NotificationEventKind.REMOVED,
                observedAtEpochMillis = signal.observedAtEpochMillis,
                removalReason = signal.reason,
                snapshot = snapshot,
            ),
            active = false,
            fingerprint = previous?.fingerprint ?: NotificationFingerprint.of(snapshot),
        )
    }
}
