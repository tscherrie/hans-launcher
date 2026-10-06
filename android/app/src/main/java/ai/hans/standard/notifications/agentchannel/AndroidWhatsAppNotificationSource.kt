package ai.hans.standard.notifications.agentchannel

import android.app.Notification
import android.app.Person
import android.service.notification.StatusBarNotification

/** Public Android metadata only. WhatsApp display titles are never identity evidence. */
internal object AndroidWhatsAppNotificationSource {
    @Suppress("DEPRECATION") // Public Bundle/SBN accessors remain available on every supported API.
    fun extract(sbn: StatusBarNotification): WhatsAppNotificationSource? {
        if (sbn.packageName != WHATSAPP_PACKAGE) return null
        return runCatching {
            val notification = sbn.notification
            val own = notification.extras?.getParcelable<Person>(Notification.EXTRA_MESSAGING_PERSON)?.stableIdentity()
            val original = notification.extras?.getParcelableArray(Notification.EXTRA_MESSAGES)?.let {
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(it)
            }.orEmpty()
            val messages = original.takeLast(AgentChannelLimits.MAX_MESSAGES).map { message ->
                val text = message.text?.toString().orEmpty()
                val bounded = boundedText(text, AgentChannelLimits.MAX_MESSAGE_BYTES)
                WhatsAppNotificationMessage(bounded, message.timestamp, message.senderPerson?.stableIdentity(),
                    senderIsOwnUser = message.senderPerson == null,
                    truncated = bounded != text || notificationTextMayBePlatformTruncated(text),
                    hasAttachment = message.dataUri != null || message.dataMimeType != null)
            }.ifEmpty {
                // A preview without MessagingStyle is useful only for a source-bound retrieval hint.
                val text = notification.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                    ?: notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                listOf(WhatsAppNotificationMessage(boundedText(text, AgentChannelLimits.MAX_MESSAGE_BYTES),
                    notification.`when`, null, false, truncated = true))
            }
            WhatsAppNotificationSource(sbn.packageName, sbn.userId, sbn.uid, sbn.key,
                notification.shortcutId?.takeIf { it.toByteArray(Charsets.UTF_8).size <= 1_024 }, own,
                isGroupConversation = notification.extras?.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) == true,
                isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
                messages = messages,
                messagesTruncated = original.size > AgentChannelLimits.MAX_MESSAGES,
                displayTitle = boundedText(notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(), 256))
        }.getOrNull()
    }

    private fun Person.stableIdentity(): String? {
        val key = key.orEmpty()
        val uri = uri.orEmpty()
        // Name-only Person is not a stable identity, even if its visible name is "Du" or "Jeremias".
        if (key.isBlank() && uri.isBlank()) return null
        if (key.length > 2_048 || uri.length > 2_048) return null
        return channelDigest(key, uri)
    }

    private fun boundedText(text: String, maxBytes: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        val result = StringBuilder()
        var index = 0
        var bytes = 0
        while (index < text.length) {
            val point = text.codePointAt(index)
            val value = String(Character.toChars(point))
            val count = value.toByteArray(Charsets.UTF_8).size
            if (bytes + count > maxBytes) break
            result.append(value)
            bytes += count
            index += Character.charCount(point)
        }
        return result.toString()
    }
}

/**
 * AOSP Notification.safeCharSequence silently clips to 1024 UTF-16 units before Message reaches a
 * listener (including when getMessagesFromBundleArray reconstructs Message). Verified for Android
 * 12.0/12.1/13/14/15/16 release tags. No hidden API is invoked or reflected here.
 * https://github.com/aosp-mirror/platform_frameworks_base/blob/android-12.0.0_r1/core/java/android/app/Notification.java#L264
 * https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/core/java/android/app/Notification.java#L284
 * An exactly-at-cap original is indistinguishable from a clipped preview: fail closed to lookup.
 */
internal fun notificationTextMayBePlatformTruncated(text: String): Boolean = text.length >= 1_024
