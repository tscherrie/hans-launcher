package ai.hans.standard.phone.notifications

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class RawNotificationRemoteInput(
    val resultKey: CharSequence?,
    val label: CharSequence?,
    val choices: List<CharSequence?>,
    val allowFreeFormInput: Boolean,
    val allowedDataTypes: List<String>,
    val editChoicesBeforeSending: Int,
)

data class RawNotificationAction(
    val title: CharSequence?,
    val semanticAction: Int,
    val isContextual: Boolean,
    val allowGeneratedReplies: Boolean,
    val authenticationRequired: Boolean,
    val hasActionIntent: Boolean,
    val remoteInputs: List<RawNotificationRemoteInput>,
)

data class RawNotificationSnapshot(
    val packageName: String?,
    val androidKey: String?,
    val postTimeEpochMillis: Long,
    val notificationWhenEpochMillis: Long,
    val title: CharSequence?,
    val text: CharSequence?,
    val subtext: CharSequence?,
    val category: String?,
    val channelId: String?,
    val ongoing: Boolean,
    val clearable: Boolean,
    val actions: List<RawNotificationAction>,
    val agentChannelSource: ai.hans.standard.notifications.agentchannel.WhatsAppNotificationSource? = null,
)

object NotificationNormalizer {
    fun upsert(
        raw: RawNotificationSnapshot,
        observedAtEpochMillis: Long,
    ): NotificationSignal.Upsert {
        val packageName = SafeNotificationText.identifier(
            raw.packageName,
            NotificationLimits.PACKAGE_UTF8_BYTES,
            fallback = "unknown.package",
        )
        val androidKey = SafeNotificationText.identifier(
            raw.androidKey,
            NotificationLimits.ANDROID_KEY_UTF8_BYTES,
            fallback = "missing:$packageName:${raw.postTimeEpochMillis.coerceAtLeast(0)}",
        )
        val snapshot = NotificationSnapshot(
            packageName = packageName,
            androidKey = androidKey,
            postTimeEpochMillis = raw.postTimeEpochMillis.coerceAtLeast(0),
            notificationWhenEpochMillis = raw.notificationWhenEpochMillis.coerceAtLeast(0),
            title = SafeNotificationText.content(raw.title, NotificationLimits.TITLE_UTF8_BYTES),
            text = SafeNotificationText.content(raw.text, NotificationLimits.TEXT_UTF8_BYTES),
            subtext = SafeNotificationText.content(raw.subtext, NotificationLimits.SUBTEXT_UTF8_BYTES),
            category = SafeNotificationText.content(raw.category, NotificationLimits.CATEGORY_UTF8_BYTES),
            channelId = SafeNotificationText.content(raw.channelId, NotificationLimits.CHANNEL_UTF8_BYTES),
            ongoing = raw.ongoing,
            clearable = raw.clearable,
            actions = raw.actions
                .take(NotificationLimits.MAX_ACTIONS)
                .mapIndexed(::normalizeAction),
            agentChannelSource = raw.agentChannelSource?.takeIf {
                it.packageName == packageName && it.notificationKey == androidKey && packageName == "com.whatsapp"
            },
        )
        return NotificationSignal.Upsert(
            snapshot = snapshot,
            observedAtEpochMillis = observedAtEpochMillis.coerceAtLeast(0),
        )
    }

    fun removed(
        packageName: String?,
        androidKey: String?,
        observedAtEpochMillis: Long,
        reason: Int?,
    ): NotificationSignal.Removed {
        val safePackage = SafeNotificationText.identifier(
            packageName,
            NotificationLimits.PACKAGE_UTF8_BYTES,
            fallback = "unknown.package",
        )
        return NotificationSignal.Removed(
            packageName = safePackage,
            androidKey = SafeNotificationText.identifier(
                androidKey,
                NotificationLimits.ANDROID_KEY_UTF8_BYTES,
                fallback = "missing:$safePackage:${observedAtEpochMillis.coerceAtLeast(0)}",
            ),
            observedAtEpochMillis = observedAtEpochMillis.coerceAtLeast(0),
            reason = reason,
        )
    }

    private fun normalizeAction(
        index: Int,
        raw: RawNotificationAction,
    ): NotificationActionMetadata = NotificationActionMetadata(
        index = index,
        title = SafeNotificationText.content(
            raw.title,
            NotificationLimits.ACTION_TITLE_UTF8_BYTES,
        ),
        semanticAction = raw.semanticAction,
        isContextual = raw.isContextual,
        allowGeneratedReplies = raw.allowGeneratedReplies,
        authenticationRequired = raw.authenticationRequired,
        hasActionIntent = raw.hasActionIntent,
        remoteInputs = raw.remoteInputs
            .take(NotificationLimits.MAX_REMOTE_INPUTS_PER_ACTION)
            .map { input ->
                NotificationRemoteInputMetadata(
                    resultKey = SafeNotificationText.identifier(
                        input.resultKey?.toString(),
                        NotificationLimits.RESULT_KEY_UTF8_BYTES,
                        fallback = "missing-result-key",
                    ),
                    label = SafeNotificationText.content(
                        input.label,
                        NotificationLimits.REMOTE_LABEL_UTF8_BYTES,
                    ),
                    choices = input.choices
                        .take(NotificationLimits.MAX_CHOICES_PER_REMOTE_INPUT)
                        .map { SafeNotificationText.content(it, NotificationLimits.CHOICE_UTF8_BYTES) },
                    allowFreeFormInput = input.allowFreeFormInput,
                    allowedDataTypes = input.allowedDataTypes
                        .asSequence()
                        .map {
                            SafeNotificationText.content(
                                it,
                                NotificationLimits.DATA_TYPE_UTF8_BYTES,
                            )
                        }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .sorted()
                        .take(NotificationLimits.MAX_DATA_TYPES_PER_REMOTE_INPUT)
                        .toList(),
                    editChoicesBeforeSending = input.editChoicesBeforeSending,
                )
            },
    )
}

internal object SafeNotificationText {
    private val bidiControls: Set<Int> = buildSet {
        add(0x061C)
        add(0x200E)
        add(0x200F)
        addAll(0x202A..0x202E)
        addAll(0x2066..0x2069)
    }

    fun content(value: CharSequence?, maxUtf8Bytes: Int): String = boundedUtf8(
        sanitize(value?.toString().orEmpty()),
        maxUtf8Bytes,
    )

    fun identifier(value: String?, maxUtf8Bytes: Int, fallback: String): String {
        val sanitized = sanitize(value.orEmpty()).ifBlank { sanitize(fallback) }
        if (utf8Size(sanitized) <= maxUtf8Bytes) return sanitized

        val suffix = "#${sha256(sanitized).take(16)}"
        val prefixBudget = (maxUtf8Bytes - utf8Size(suffix)).coerceAtLeast(0)
        return boundedUtf8(sanitized, prefixBudget) + suffix
    }

    fun boundedUtf8(value: String, maxUtf8Bytes: Int): String {
        if (maxUtf8Bytes <= 0 || value.isEmpty()) return ""
        if (utf8Size(value) <= maxUtf8Bytes) return value

        val result = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val encoded = String(Character.toChars(codePoint))
                .toByteArray(StandardCharsets.UTF_8)
            if (bytes + encoded.size > maxUtf8Bytes) break
            result.appendCodePoint(codePoint)
            bytes += encoded.size
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }

    fun utf8Size(value: String): Int = value.toByteArray(StandardCharsets.UTF_8).size

    private fun sanitize(value: String): String {
        if (value.isBlank()) return ""
        val result = StringBuilder(value.length)
        var pendingSpace = false
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            index += Character.charCount(codePoint)
            when {
                codePoint in bidiControls -> Unit
                Character.isISOControl(codePoint) || Character.isWhitespace(codePoint) -> {
                    if (result.isNotEmpty()) pendingSpace = true
                }
                Character.getType(codePoint) == Character.SURROGATE.toInt() -> Unit
                else -> {
                    if (pendingSpace) {
                        result.append(' ')
                        pendingSpace = false
                    }
                    result.appendCodePoint(codePoint)
                }
            }
        }
        return result.toString().trim()
    }

    private fun sha256(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

internal object NotificationFingerprint {
    fun of(snapshot: NotificationSnapshot): String {
        val canonical = StringBuilder()
        canonical.field(snapshot.packageName)
        canonical.field(snapshot.androidKey)
        // Android may refresh both timestamps while re-delivering the same
        // active notification. Preserve them as metadata, but do not turn a
        // timestamp-only refresh into a second user-visible event.
        canonical.field(snapshot.title)
        canonical.field(snapshot.text)
        canonical.field(snapshot.subtext)
        canonical.field(snapshot.category)
        canonical.field(snapshot.channelId)
        canonical.field(snapshot.ongoing.toString())
        canonical.field(snapshot.clearable.toString())
        snapshot.agentChannelSource?.let {
            canonical.field(ai.hans.standard.notifications.agentchannel.AgentChannelSourceCodec.encode(it))
        }
        snapshot.actions.forEach { action ->
            canonical.field(action.index.toString())
            canonical.field(action.title)
            canonical.field(action.semanticAction.toString())
            canonical.field(action.isContextual.toString())
            canonical.field(action.allowGeneratedReplies.toString())
            canonical.field(action.authenticationRequired.toString())
            canonical.field(action.hasActionIntent.toString())
            action.remoteInputs.forEach { input ->
                canonical.field(input.resultKey)
                canonical.field(input.label)
                input.choices.forEach { canonical.field(it) }
                canonical.field(input.allowFreeFormInput.toString())
                input.allowedDataTypes.forEach { canonical.field(it) }
                canonical.field(input.editChoicesBeforeSending.toString())
            }
        }
        return MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.toString().toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun StringBuilder.field(value: String) {
        append(value.length).append(':').append(value).append('|')
    }
}
