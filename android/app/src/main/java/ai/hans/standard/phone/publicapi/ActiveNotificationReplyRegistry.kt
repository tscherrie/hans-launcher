package ai.hans.standard.phone.publicapi

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Process-memory-only view of replyable notifications. The listener must feed posted/removed
 * callbacks into this registry. PendingIntents and Android notification keys are never persisted
 * or returned to Codex; callers receive an opaque, process-lifetime token instead.
 */
class ActiveNotificationReplyRegistry private constructor(
    secret: ByteArray,
) {
    private val tokenSecret = secret.copyOf()
    private val active = linkedMapOf<String, Entry>()

    @Volatile
    private var listenerConnected: Boolean = false

    fun onListenerConnected(notifications: List<StatusBarNotification>) {
        synchronized(active) {
            listenerConnected = true
            active.clear()
            notifications.takeLast(MAX_ACTIVE_NOTIFICATIONS).forEach(::upsertLocked)
        }
    }

    fun onListenerDisconnected() {
        synchronized(active) {
            listenerConnected = false
            active.clear()
        }
    }

    fun onNotificationPosted(notification: StatusBarNotification) {
        synchronized(active) {
            upsertLocked(notification)
            while (active.size > MAX_ACTIVE_NOTIFICATIONS) {
                active.remove(active.keys.first())
            }
        }
    }

    fun onNotificationRemoved(androidKey: String) {
        synchronized(active) {
            active.remove(androidKey)
        }
    }

    fun isAvailable(): Boolean = listenerConnected

    fun list(limit: Int): PublicPhonePlatformResult<List<ReplyableNotification>> {
        if (!listenerConnected) {
            return PublicPhonePlatformResult.Failure("notification_listener_not_connected")
        }
        val boundedLimit = limit.coerceIn(1, PublicPhoneBounds.MAX_REPLYABLE_NOTIFICATIONS)
        val snapshot = synchronized(active) { active.values.toList() }
        return PublicPhonePlatformResult.Success(
            snapshot.asReversed().asSequence()
                .mapNotNull { entry -> runCatching { project(entry) }.getOrNull() }
                .take(boundedLimit)
                .toList(),
        )
    }

    fun reply(
        context: Context,
        replyToken: String,
        actionIndex: Int,
        message: String,
    ): PublicPhonePlatformResult<NotificationReplyDispatch> {
        if (!listenerConnected) {
            return PublicPhonePlatformResult.Failure("notification_listener_not_connected")
        }
        if (!TOKEN.matches(replyToken)) {
            return PublicPhonePlatformResult.Failure("invalid_reply_token")
        }
        val cleanMessage = PublicPhoneBounds.cleanUntrusted(
            message,
            PublicPhoneBounds.MAX_REPLY_BYTES,
        )
        if (cleanMessage.isBlank()) {
            return PublicPhonePlatformResult.Failure("invalid_reply_message")
        }
        val entry = synchronized(active) {
            active.values.firstOrNull { it.replyToken == replyToken }
        } ?: return PublicPhonePlatformResult.Failure("reply_target_not_active")
        val action = entry.notification.notification.actions
            ?.getOrNull(actionIndex)
            ?: return PublicPhonePlatformResult.Failure("reply_action_not_active")
        if (action.isAuthenticationRequired) {
            return PublicPhonePlatformResult.Failure("reply_requires_authentication")
        }
        val remoteInputs = action.remoteInputs
            ?.filter { input -> input.allowFreeFormInput && input.resultKey.isNotBlank() }
            ?.take(MAX_REMOTE_INPUTS)
            .orEmpty()
        if (remoteInputs.isEmpty() || action.actionIntent == null) {
            return PublicPhonePlatformResult.Failure("freeform_reply_unavailable")
        }
        val fillIn = Intent()
        val results = Bundle().apply {
            remoteInputs.forEach { input -> putCharSequence(input.resultKey, cleanMessage) }
        }
        return try {
            RemoteInput.addResultsToIntent(remoteInputs.toTypedArray(), fillIn, results)
            action.actionIntent.send(context.applicationContext, 0, fillIn)
            PublicPhonePlatformResult.Success(
                NotificationReplyDispatch(
                    accepted = true,
                    completionObserved = false,
                    limitationCode = "pending_intent_sent_delivery_not_observable",
                ),
            )
        } catch (_: android.app.PendingIntent.CanceledException) {
            PublicPhonePlatformResult.Failure("reply_pending_intent_cancelled")
        } catch (_: SecurityException) {
            PublicPhonePlatformResult.Failure("reply_security_rejection")
        } catch (_: RuntimeException) {
            PublicPhonePlatformResult.Failure("reply_dispatch_failed")
        }
    }

    private fun upsertLocked(notification: StatusBarNotification) {
        val key = notification.key ?: return
        if (key.isBlank()) return
        active.remove(key)
        active[key] = Entry(
            notification = notification,
            replyToken = tokenFor(notification),
        )
    }

    private fun project(entry: Entry): ReplyableNotification? {
        val notification = entry.notification.notification
        val actions = notification.actions
            ?.mapIndexedNotNull { index, action ->
                val inputs = action.remoteInputs.orEmpty()
                if (action.actionIntent == null || inputs.isEmpty()) return@mapIndexedNotNull null
                ReplyableNotificationAction(
                    actionIndex = index,
                    label = PublicPhoneBounds.cleanUntrusted(action.title?.toString(), 256),
                    acceptsFreeFormText = inputs.any(RemoteInput::getAllowFreeFormInput),
                    authenticationRequired = action.isAuthenticationRequired,
                )
            }
            ?.filter { it.acceptsFreeFormText }
            ?.take(MAX_ACTIONS)
            .orEmpty()
        if (actions.isEmpty()) return null
        return ReplyableNotification(
            replyToken = entry.replyToken,
            sourcePackage = PublicPhoneBounds.cleanUntrusted(
                entry.notification.packageName,
                255,
            ),
            title = notification.safeExtra(Notification.EXTRA_TITLE, 512),
            text = notification.bestText(),
            actions = actions,
        )
    }

    private fun tokenFor(notification: StatusBarNotification): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(tokenSecret, "HmacSHA256"))
        val input = buildString {
            append(notification.packageName)
            append('\u0000')
            append(notification.key)
            append('\u0000')
            append(notification.postTime)
        }.toByteArray(StandardCharsets.UTF_8)
        return Base64.encodeToString(
            mac.doFinal(input).copyOf(TOKEN_BYTES),
            Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE,
        )
    }

    private fun Notification.bestText(): String {
        val big = safeExtra(Notification.EXTRA_BIG_TEXT, 4_096)
        if (big.isNotBlank()) return big
        val normal = safeExtra(Notification.EXTRA_TEXT, 4_096)
        if (normal.isNotBlank()) return normal
        val lines = runCatching {
            extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.filterNotNull()
                ?.joinToString(" ")
        }.getOrNull()
        return PublicPhoneBounds.cleanUntrusted(lines, 4_096)
    }

    private fun Notification.safeExtra(key: String, maxBytes: Int): String =
        PublicPhoneBounds.cleanUntrusted(
            runCatching { extras?.getCharSequence(key)?.toString() }.getOrNull(),
            maxBytes,
        )

    private data class Entry(
        val notification: StatusBarNotification,
        val replyToken: String,
    )

    companion object {
        private const val MAX_ACTIVE_NOTIFICATIONS = 256
        private const val MAX_ACTIONS = 8
        private const val MAX_REMOTE_INPUTS = 4
        private const val TOKEN_BYTES = 18
        private val TOKEN = Regex("[A-Za-z0-9_-]{24}")

        /** Same-process service/host rendezvous; contains no disk-backed state. */
        val processWide: ActiveNotificationReplyRegistry by lazy {
            ActiveNotificationReplyRegistry(ByteArray(32).also(SecureRandom()::nextBytes))
        }

        internal fun forTest(secret: ByteArray): ActiveNotificationReplyRegistry {
            require(secret.size >= 16)
            return ActiveNotificationReplyRegistry(secret)
        }
    }
}
