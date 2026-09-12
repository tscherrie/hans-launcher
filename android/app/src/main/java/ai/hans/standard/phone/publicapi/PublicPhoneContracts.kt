package ai.hans.standard.phone.publicapi

import ai.hans.standard.phone.consent.PersistentAndroidConsentScope
import ai.hans.standard.phone.consent.PersistentAndroidConsentDescriptor
import ai.hans.standard.phone.consent.PersistentAndroidConsentPolicy
import java.nio.charset.StandardCharsets

/** Runtime result whose error code is stable and never contains platform or personal data. */
sealed interface PublicPhonePlatformResult<out T> {
    data class Success<T>(val value: T) : PublicPhonePlatformResult<T>
    data class Failure(val code: String) : PublicPhonePlatformResult<Nothing> {
        init {
            require(code.matches(Regex("[a-z0-9_]{1,96}")))
        }
    }
}

enum class PublicPhoneRisk {
    SENSITIVE_READ,
    USER_VISIBLE,
    EXTERNAL_MUTATION,
}

/**
 * UI seam for one call-correlated authorization. The argument fingerprint prevents a grant from
 * being replayed for edited arguments. Returning null is always a refusal.
 */
data class PublicPhoneConfirmationRequest(
    val callId: String,
    val tool: String,
    val risk: PublicPhoneRisk,
    val argumentFingerprint: String,
    /** Bounded UI copy. It may contain untrusted user/app text and must never be logged. */
    val displaySummary: String,
    /** Trusted local category derived from the decoded command, never supplied by the model. */
    val persistentConsentScope: PersistentAndroidConsentScope? = null,
)

data class PublicPhoneConfirmationGrant(
    val callId: String,
    val tool: String,
    val risk: PublicPhoneRisk,
    val argumentFingerprint: String,
)

/** Exact allowlist for recurring private reads and user-visible draft UIs. */
object PublicPhonePersistentConsentPolicy {
    fun descriptorFor(
        request: PublicPhoneConfirmationRequest,
    ): PersistentAndroidConsentDescriptor? {
        val scope = request.persistentConsentScope ?: return null
        val eligible = when (scope) {
            PersistentAndroidConsentScope.READ_CONTACTS ->
                request.risk == PublicPhoneRisk.SENSITIVE_READ &&
                    request.tool in setOf("search_contacts", "lookup_contact")
            PersistentAndroidConsentScope.READ_CALENDAR ->
                request.risk == PublicPhoneRisk.SENSITIVE_READ &&
                    request.tool == "read_calendar"
            PersistentAndroidConsentScope.READ_LOCATION ->
                request.risk == PublicPhoneRisk.SENSITIVE_READ &&
                    request.tool == "read_location"
            PersistentAndroidConsentScope.READ_SENSORS ->
                request.risk == PublicPhoneRisk.SENSITIVE_READ &&
                    request.tool == "read_sensors"
            PersistentAndroidConsentScope.READ_MEDIA ->
                request.risk == PublicPhoneRisk.SENSITIVE_READ &&
                    request.tool == "list_media"
            PersistentAndroidConsentScope.READ_REPLYABLE_NOTIFICATIONS ->
                request.risk == PublicPhoneRisk.SENSITIVE_READ &&
                    request.tool == "list_replyable_notifications"
            PersistentAndroidConsentScope.OPEN_CAMERA ->
                request.risk == PublicPhoneRisk.USER_VISIBLE &&
                    request.tool == "open_camera"
            PersistentAndroidConsentScope.PREPARE_CALENDAR_EVENT ->
                request.risk == PublicPhoneRisk.USER_VISIBLE &&
                    request.tool == "prepare_calendar_event"
            else -> false
        }
        return if (eligible) {
            PersistentAndroidConsentPolicy.categoryDescriptor(scope)
        } else {
            null
        }
    }
}

fun interface PublicPhoneConfirmationProvider {
    fun confirm(request: PublicPhoneConfirmationRequest): PublicPhoneConfirmationGrant?

    companion object {
        val NONE = PublicPhoneConfirmationProvider { null }
    }
}

enum class PublicPhoneCapabilityState {
    AVAILABLE,
    PERMISSION_REQUIRED,
    SPECIAL_ACCESS_REQUIRED,
    UNSUPPORTED,
}

data class PublicPhoneCapabilityProbe(
    val capability: String,
    val state: PublicPhoneCapabilityState,
    val missingPermissions: Set<String> = emptySet(),
    val limitationCode: String? = null,
)

data class ContactSummary(
    val contactId: String,
    val displayName: String,
    val starred: Boolean,
)

data class ContactDetail(
    val contactId: String,
    val displayName: String,
    val starred: Boolean,
    val phoneNumbers: List<LabeledValue>,
    val emailAddresses: List<LabeledValue>,
)

data class LabeledValue(
    val label: String,
    val value: String,
)

data class CalendarInstance(
    val eventId: String,
    val calendarId: String,
    val title: String,
    val location: String,
    val organizer: String,
    val beginEpochMillis: Long,
    val endEpochMillis: Long,
    val allDay: Boolean,
    val status: Int,
)

enum class LocationReadMode {
    LAST_KNOWN,
    CURRENT,
}

data class PhoneLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val altitudeMeters: Double?,
    val observedAtEpochMillis: Long,
    val elapsedRealtimeNanos: Long,
    val source: String,
    val currentFix: Boolean,
    val mock: Boolean,
)

enum class PublicSensorType(val wireName: String) {
    ACCELEROMETER("accelerometer"),
    GYROSCOPE("gyroscope"),
    MAGNETIC_FIELD("magnetic_field"),
    LIGHT("light"),
    PROXIMITY("proximity"),
    PRESSURE("pressure"),
    AMBIENT_TEMPERATURE("ambient_temperature"),
    RELATIVE_HUMIDITY("relative_humidity"),
    ROTATION_VECTOR("rotation_vector");

    companion object {
        fun fromWireName(value: String): PublicSensorType? = entries.firstOrNull {
            it.wireName == value
        }
    }
}

data class SensorReading(
    val type: PublicSensorType,
    val values: List<Float>,
    val accuracy: Int,
    val timestampNanos: Long,
)

enum class MediaCatalogKind(val wireName: String) {
    IMAGE("image"),
    VIDEO("video"),
    AUDIO("audio");

    companion object {
        fun fromWireName(value: String): MediaCatalogKind? = entries.firstOrNull {
            it.wireName == value
        }
    }
}

data class MediaCatalogItem(
    val kind: MediaCatalogKind,
    val contentUri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val dateAddedEpochMillis: Long?,
    val durationMillis: Long?,
    val width: Int?,
    val height: Int?,
)

enum class CameraCaptureMode(val wireName: String) {
    PHOTO("photo"),
    VIDEO("video");

    companion object {
        fun fromWireName(value: String): CameraCaptureMode? = entries.firstOrNull {
            it.wireName == value
        }
    }
}

data class CalendarEventDraft(
    val title: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val timeZoneId: String,
    val allDay: Boolean,
    val location: String?,
    val description: String?,
    val calendarId: Long? = null,
)

data class UserVisibleDispatch(
    val accepted: Boolean,
    /** Honest public-API boundary: activity launch is observable, completion usually is not. */
    val completionObserved: Boolean,
    val limitationCode: String,
)

data class CreatedCalendarEvent(
    val eventId: String,
    val verifiedReadable: Boolean,
)

data class ReplyableNotification(
    val replyToken: String,
    val sourcePackage: String,
    val title: String,
    val text: String,
    val actions: List<ReplyableNotificationAction>,
)

data class ReplyableNotificationAction(
    val actionIndex: Int,
    val label: String,
    val acceptsFreeFormText: Boolean,
    val authenticationRequired: Boolean,
)

data class NotificationReplyDispatch(
    val accepted: Boolean,
    val completionObserved: Boolean,
    val limitationCode: String,
)

/** Android-facing operations. Tests can replace this without Android mocks. */
interface PublicPhonePlatform {
    fun probeCapabilities(): List<PublicPhoneCapabilityProbe>

    fun searchContacts(query: String, limit: Int): PublicPhonePlatformResult<List<ContactSummary>>
    fun lookupContact(contactId: Long): PublicPhonePlatformResult<ContactDetail>
    fun readCalendar(
        startEpochMillis: Long,
        endEpochMillis: Long,
        limit: Int,
    ): PublicPhonePlatformResult<List<CalendarInstance>>

    fun readLocation(
        mode: LocationReadMode,
        timeoutMillis: Long,
    ): PublicPhonePlatformResult<PhoneLocation>

    fun readSensors(
        types: Set<PublicSensorType>,
        timeoutMillis: Long,
    ): PublicPhonePlatformResult<List<SensorReading>>

    fun listMedia(
        kinds: Set<MediaCatalogKind>,
        afterEpochMillis: Long?,
        limit: Int,
    ): PublicPhonePlatformResult<List<MediaCatalogItem>>

    fun openCamera(mode: CameraCaptureMode): PublicPhonePlatformResult<UserVisibleDispatch>
    fun prepareCalendarEvent(
        draft: CalendarEventDraft,
    ): PublicPhonePlatformResult<UserVisibleDispatch>

    fun createCalendarEvent(
        draft: CalendarEventDraft,
    ): PublicPhonePlatformResult<CreatedCalendarEvent>

    fun listReplyableNotifications(
        limit: Int,
    ): PublicPhonePlatformResult<List<ReplyableNotification>>

    fun replyToNotification(
        replyToken: String,
        actionIndex: Int,
        message: String,
    ): PublicPhonePlatformResult<NotificationReplyDispatch>
}

internal object PublicPhoneBounds {
    const val MAX_OUTPUT_BYTES = 64 * 1_024
    const val MAX_CONTACT_RESULTS = 50
    const val MAX_CALENDAR_RESULTS = 100
    const val MAX_CALENDAR_WINDOW_MILLIS = 366L * 24L * 60L * 60L * 1_000L
    const val MAX_MEDIA_RESULTS = 100
    const val MAX_REPLYABLE_NOTIFICATIONS = 50
    const val MAX_SENSOR_TYPES = 9
    const val MAX_LOCATION_TIMEOUT_MILLIS = 15_000L
    const val MAX_SENSOR_TIMEOUT_MILLIS = 5_000L
    const val MAX_USER_TEXT_BYTES = 4_096
    const val MAX_REPLY_BYTES = 4_096

    fun cleanUntrusted(value: String?, maxBytes: Int): String {
        if (value.isNullOrBlank()) return ""
        val cleaned = buildString(value.length.coerceAtMost(maxBytes)) {
            value.forEach { character ->
                if (!character.isISOControl() && character.code !in BIDI_CONTROLS) {
                    append(character)
                } else if (character == '\n' || character == '\t') {
                    append(' ')
                }
            }
        }.trim()
        if (cleaned.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) return cleaned
        val output = StringBuilder()
        var bytes = 0
        var offset = 0
        while (offset < cleaned.length) {
            val codePoint = cleaned.codePointAt(offset)
            val encoded = String(Character.toChars(codePoint)).toByteArray(StandardCharsets.UTF_8)
            if (bytes + encoded.size > maxBytes) break
            output.appendCodePoint(codePoint)
            bytes += encoded.size
            offset += Character.charCount(codePoint)
        }
        return output.toString()
    }

    private val BIDI_CONTROLS = buildSet {
        add(0x061C)
        add(0x200E)
        add(0x200F)
        addAll(0x202A..0x202E)
        addAll(0x2066..0x2069)
    }
}
