package ai.hans.standard.phone.notifications

enum class NotificationEventKind {
    POSTED,
    UPDATED,
    REMOVED,
}

data class NotificationRemoteInputMetadata(
    val resultKey: String,
    val label: String,
    val choices: List<String>,
    val allowFreeFormInput: Boolean,
    val allowedDataTypes: List<String>,
    val editChoicesBeforeSending: Int,
)

data class NotificationActionMetadata(
    val index: Int,
    val title: String,
    val semanticAction: Int,
    val isContextual: Boolean,
    val allowGeneratedReplies: Boolean,
    val authenticationRequired: Boolean,
    val hasActionIntent: Boolean,
    val remoteInputs: List<NotificationRemoteInputMetadata>,
)

data class NotificationSnapshot(
    val packageName: String,
    val androidKey: String,
    val postTimeEpochMillis: Long,
    val notificationWhenEpochMillis: Long,
    val title: String,
    val text: String,
    val subtext: String,
    val category: String,
    val channelId: String,
    val ongoing: Boolean,
    val clearable: Boolean,
    val actions: List<NotificationActionMetadata>,
    val agentChannelSource: ai.hans.standard.notifications.agentchannel.WhatsAppNotificationSource? = null,
)

sealed interface NotificationSignal {
    val packageName: String
    val androidKey: String
    val observedAtEpochMillis: Long

    data class Upsert(
        val snapshot: NotificationSnapshot,
        override val observedAtEpochMillis: Long,
    ) : NotificationSignal {
        override val packageName: String = snapshot.packageName
        override val androidKey: String = snapshot.androidKey
    }

    data class Removed(
        override val packageName: String,
        override val androidKey: String,
        override val observedAtEpochMillis: Long,
        val reason: Int?,
    ) : NotificationSignal
}

data class NotificationInboxEvent(
    val sequence: Long,
    val kind: NotificationEventKind,
    val observedAtEpochMillis: Long,
    val removalReason: Int?,
    val snapshot: NotificationSnapshot,
)

data class NotificationPage(
    val events: List<NotificationInboxEvent>,
    val nextAfterSequenceExclusive: Long,
    val hasMore: Boolean,
)

data class NotificationDigest(
    val text: String,
    val eventCount: Int,
    val utf8Bytes: Int,
    val nextAfterSequenceExclusive: Long,
    val hasMore: Boolean,
)

sealed interface NotificationWriteResult {
    data class Stored(
        val sequence: Long,
        val kind: NotificationEventKind,
    ) : NotificationWriteResult

    data class Duplicate(
        val lastSequence: Long?,
    ) : NotificationWriteResult

    data class ExcludedByPrivacy(
        val decision: NotificationCaptureDecision,
    ) : NotificationWriteResult

    /** The event fell outside the effective age/count window and must never enter triage. */
    data object PrunedByRetention : NotificationWriteResult
}

internal data class StoredNotificationState(
    val snapshot: NotificationSnapshot,
    val active: Boolean,
    val fingerprint: String,
    val lastSequence: Long,
)

internal data class NotificationEventDraft(
    val kind: NotificationEventKind,
    val observedAtEpochMillis: Long,
    val removalReason: Int?,
    val snapshot: NotificationSnapshot,
)

internal sealed interface NotificationReduction {
    data class Append(
        val event: NotificationEventDraft,
        val active: Boolean,
        val fingerprint: String,
    ) : NotificationReduction

    data class IgnoreDuplicate(
        val lastSequence: Long?,
    ) : NotificationReduction
}

object NotificationLimits {
    const val PACKAGE_UTF8_BYTES = 255
    const val ANDROID_KEY_UTF8_BYTES = 1_024
    const val TITLE_UTF8_BYTES = 512
    const val TEXT_UTF8_BYTES = 4_096
    const val SUBTEXT_UTF8_BYTES = 1_024
    const val CATEGORY_UTF8_BYTES = 128
    const val CHANNEL_UTF8_BYTES = 256
    const val ACTION_TITLE_UTF8_BYTES = 256
    const val RESULT_KEY_UTF8_BYTES = 256
    const val REMOTE_LABEL_UTF8_BYTES = 256
    const val CHOICE_UTF8_BYTES = 128
    const val DATA_TYPE_UTF8_BYTES = 96
    const val MAX_ACTIONS = 8
    const val MAX_REMOTE_INPUTS_PER_ACTION = 4
    const val MAX_CHOICES_PER_REMOTE_INPUT = 8
    const val MAX_DATA_TYPES_PER_REMOTE_INPUT = 8
    const val DEFAULT_EVENT_RETENTION = NotificationPrivacyBounds.DEFAULT_MAX_EVENTS
    const val MAX_PAGE_SIZE = 200
    const val MAX_DIGEST_EVENTS = 100
    const val MAX_DIGEST_UTF8_BYTES = 65_536
}
