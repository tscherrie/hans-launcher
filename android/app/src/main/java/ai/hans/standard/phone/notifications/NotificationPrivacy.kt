package ai.hans.standard.phone.notifications

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

data class NotificationRetentionPolicy(
    val maxEvents: Int = NotificationPrivacyBounds.DEFAULT_MAX_EVENTS,
    val maxAgeHours: Int = NotificationPrivacyBounds.DEFAULT_MAX_AGE_HOURS,
) {
    init {
        require(maxEvents in NotificationPrivacyBounds.MIN_MAX_EVENTS..NotificationPrivacyBounds.MAX_MAX_EVENTS)
        require(maxAgeHours in NotificationPrivacyBounds.MIN_MAX_AGE_HOURS..NotificationPrivacyBounds.MAX_MAX_AGE_HOURS)
    }
}

data class NotificationPrivacySettings(
    val excludedPackages: Set<String> = emptySet(),
    val retention: NotificationRetentionPolicy = NotificationRetentionPolicy(),
) {
    init {
        require(excludedPackages.size <= NotificationPrivacyBounds.MAX_USER_EXCLUSIONS)
        require(excludedPackages.all(NotificationPackageNames::isValid))
    }
}

data class NotificationPrivacyStatus(
    val policyAvailable: Boolean,
    val protectedPackages: Set<String>,
    val userExcludedPackages: Set<String>,
    val retention: NotificationRetentionPolicy,
)

sealed interface NotificationCaptureDecision {
    data object Allowed : NotificationCaptureDecision
    data object PolicyUnavailable : NotificationCaptureDecision
    data object ProtectedPackage : NotificationCaptureDecision
    data object UserExcludedPackage : NotificationCaptureDecision
}

sealed interface NotificationPrivacyStorageRead {
    data object Missing : NotificationPrivacyStorageRead
    data object Unavailable : NotificationPrivacyStorageRead
    data class Available(val settings: NotificationPrivacySettings) : NotificationPrivacyStorageRead
}

internal interface NotificationPrivacySettingsStorage {
    fun read(): NotificationPrivacyStorageRead
    fun write(settings: NotificationPrivacySettings)
}

internal interface NotificationPrivacyPurgeFence {
    /** Missing/corrupt state is required, never clean. */
    fun isRequired(): Boolean
    fun markRequired(): Boolean
    /** May be called only after Queue and Announcement Center both durably purge. */
    fun markClean(): Boolean
}

/** Durable fail-closed fence carried across policy repair and process death. */
internal class AtomicNotificationPrivacyPurgeFence(
    context: Context,
    fileName: String = FILE_NAME,
) : NotificationPrivacyPurgeFence {
    private val file = File(context.applicationContext.noBackupFilesDir, fileName)
    private val atomic = AtomicFile(file)

    @Synchronized
    override fun isRequired(): Boolean {
        if (!file.isFile) return true
        val value = runCatching { atomic.readFully().toString(StandardCharsets.UTF_8) }
            .getOrNull()
        return value != CLEAN_STATE
    }

    @Synchronized
    override fun markRequired(): Boolean {
        if (isRequired()) return true
        val written = writeState(REQUIRED_STATE)
        if (!written) {
            // Missing is interpreted as REQUIRED on reopen, so a failed transition must not leave
            // an older clean marker that could re-enable delivery after process death.
            runCatching { file.delete() }
            runCatching { File("${file.path}.bak").delete() }
            runCatching { File("${file.path}.new").delete() }
        }
        return written
    }

    @Synchronized
    override fun markClean(): Boolean = writeState(CLEAN_STATE) && !isRequired()

    private fun writeState(value: String): Boolean {
        val output = runCatching { atomic.startWrite() }.getOrElse { return false }
        return try {
            output.write(value.toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
            atomic.finishWrite(output)
            true
        } catch (_: Exception) {
            atomic.failWrite(output)
            false
        }
    }

    internal companion object {
        const val FILE_NAME = "notification-privacy-purge-fence-v1"
        private const val CLEAN_STATE = "clean-v1"
        private const val REQUIRED_STATE = "required-v1"
    }
}

/**
 * Persistent privacy policy for notification capture. Only the atomic storage's sealed first-use
 * initialization may create safe defaults; later missing, unreadable or malformed state fails
 * closed until a valid recovery document is imported.
 */
class NotificationPrivacyRepository internal constructor(
    private val storage: NotificationPrivacySettingsStorage,
    ownPackageName: String,
) {
    constructor(context: Context) : this(
        storage = AtomicFileNotificationPrivacySettingsStorage(context),
        ownPackageName = context.applicationContext.packageName,
    )

    private val protectedPackages = NotificationProtectedPackages.all(ownPackageName)

    @Synchronized
    fun status(): NotificationPrivacyStatus {
        val settings = currentOrNull()
        return NotificationPrivacyStatus(
            policyAvailable = settings != null,
            protectedPackages = protectedPackages,
            userExcludedPackages = settings?.excludedPackages.orEmpty(),
            retention = settings?.retention ?: NotificationRetentionPolicy(),
        )
    }

    @Synchronized
    fun captureDecision(packageName: String): NotificationCaptureDecision {
        val safePackage = packageName.trim()
        if (!NotificationPackageNames.isValid(safePackage)) {
            return NotificationCaptureDecision.PolicyUnavailable
        }
        if (NotificationProtectedPackages.matches(safePackage, protectedPackages)) {
            return NotificationCaptureDecision.ProtectedPackage
        }
        val settings = currentOrNull() ?: return NotificationCaptureDecision.PolicyUnavailable
        return if (safePackage in settings.excludedPackages) {
            NotificationCaptureDecision.UserExcludedPackage
        } else {
            NotificationCaptureDecision.Allowed
        }
    }

    @Synchronized
    fun excludePackage(packageName: String): NotificationPrivacySettings {
        val safePackage = NotificationPackageNames.requireValid(packageName)
        require(!NotificationProtectedPackages.matches(safePackage, protectedPackages)) {
            "notification_package_already_protected"
        }
        val current = requireCurrent()
        require(current.excludedPackages.size < NotificationPrivacyBounds.MAX_USER_EXCLUSIONS ||
            safePackage in current.excludedPackages
        ) { "notification_exclusion_limit" }
        return persist(current.copy(excludedPackages = current.excludedPackages + safePackage))
    }

    @Synchronized
    fun includePackage(packageName: String): NotificationPrivacySettings {
        val safePackage = NotificationPackageNames.requireValid(packageName)
        require(!NotificationProtectedPackages.matches(safePackage, protectedPackages)) {
            "protected_notification_package_cannot_be_included"
        }
        val current = requireCurrent()
        return persist(current.copy(excludedPackages = current.excludedPackages - safePackage))
    }

    @Synchronized
    fun setRetention(maxEvents: Int, maxAgeHours: Int): NotificationPrivacySettings {
        val current = requireCurrent()
        return persist(
            current.copy(retention = NotificationRetentionPolicy(maxEvents, maxAgeHours)),
        )
    }

    @Synchronized
    fun importRecoveryDocument(document: String): NotificationPrivacySettings {
        val recovered = NotificationPrivacyRecoveryCodec.decode(document)
        require(recovered.excludedPackages.none {
            NotificationProtectedPackages.matches(it, protectedPackages)
        }) { "recovery_contains_protected_package" }
        return persist(recovered)
    }

    @Synchronized
    fun exportRecoveryDocument(): String = NotificationPrivacyRecoveryCodec.encode(requireCurrent())

    private fun currentOrNull(): NotificationPrivacySettings? = when (val read = storage.read()) {
        NotificationPrivacyStorageRead.Missing -> NotificationPrivacySettings()
        NotificationPrivacyStorageRead.Unavailable -> null
        is NotificationPrivacyStorageRead.Available -> read.settings
    }

    private fun requireCurrent(): NotificationPrivacySettings =
        currentOrNull() ?: error("notification_privacy_policy_unavailable")

    private fun persist(settings: NotificationPrivacySettings): NotificationPrivacySettings {
        storage.write(settings)
        val verified = (storage.read() as? NotificationPrivacyStorageRead.Available)?.settings
            ?: error("notification_privacy_policy_verification_failed")
        check(verified == settings) { "notification_privacy_policy_verification_failed" }
        return verified
    }
}

internal class AtomicFileNotificationPrivacySettingsStorage(
    context: Context,
    fileName: String = FILE_NAME,
) : NotificationPrivacySettingsStorage {
    private val stateFile = File(context.applicationContext.noBackupFilesDir, fileName)
    private val file = AtomicFile(stateFile)
    private val initializationFile = File(
        context.applicationContext.noBackupFilesDir,
        "$fileName.initialized",
    )
    private val initializationAtomic = AtomicFile(initializationFile)

    override fun read(): NotificationPrivacyStorageRead = synchronized(PROCESS_LOCK) {
        if (!stateFile.isFile && !File("${stateFile.path}.bak").isFile) {
            return when (initializationStatus()) {
                InitializationStatus.MISSING -> initializeDefaultsLocked()
                InitializationStatus.SEALED,
                InitializationStatus.UNAVAILABLE,
                -> NotificationPrivacyStorageRead.Unavailable
            }
        }
        val bytes = runCatching { file.readFully() }
            .getOrElse { return NotificationPrivacyStorageRead.Unavailable }
        if (bytes.isEmpty() || bytes.size > NotificationPrivacyBounds.MAX_SETTINGS_BYTES) {
            return NotificationPrivacyStorageRead.Unavailable
        }
        val settings = runCatching {
            NotificationPrivacyRecoveryCodec.decode(bytes.toString(StandardCharsets.UTF_8))
        }.getOrElse { return NotificationPrivacyStorageRead.Unavailable }
        if (!sealInitializationLocked()) {
            NotificationPrivacyStorageRead.Unavailable
        } else {
            NotificationPrivacyStorageRead.Available(settings)
        }
    }

    override fun write(settings: NotificationPrivacySettings) = synchronized(PROCESS_LOCK) {
        writeSettingsLocked(settings)
        check(sealInitializationLocked()) { "notification_privacy_initialization_seal_failed" }
    }

    private fun initializeDefaultsLocked(): NotificationPrivacyStorageRead {
        val defaults = NotificationPrivacySettings()
        return runCatching {
            writeSettingsLocked(defaults)
            check(sealInitializationLocked())
            NotificationPrivacyStorageRead.Available(defaults)
        }.getOrDefault(NotificationPrivacyStorageRead.Unavailable)
    }

    private fun writeSettingsLocked(settings: NotificationPrivacySettings) {
        val bytes = NotificationPrivacyRecoveryCodec.encode(settings)
            .toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= NotificationPrivacyBounds.MAX_SETTINGS_BYTES)
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }

    private fun initializationStatus(): InitializationStatus {
        if (
            !initializationFile.isFile &&
            !File("${initializationFile.path}.bak").isFile
        ) {
            return InitializationStatus.MISSING
        }
        val value = runCatching {
            initializationAtomic.readFully().toString(StandardCharsets.UTF_8)
        }.getOrNull()
        return if (value == INITIALIZED_STATE) {
            InitializationStatus.SEALED
        } else {
            InitializationStatus.UNAVAILABLE
        }
    }

    private fun sealInitializationLocked(): Boolean {
        if (initializationStatus() == InitializationStatus.SEALED) return true
        val output = runCatching { initializationAtomic.startWrite() }.getOrElse { return false }
        return try {
            output.write(INITIALIZED_STATE.toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
            initializationAtomic.finishWrite(output)
            initializationStatus() == InitializationStatus.SEALED
        } catch (_: Exception) {
            initializationAtomic.failWrite(output)
            false
        }
    }

    private enum class InitializationStatus {
        MISSING,
        SEALED,
        UNAVAILABLE,
    }

    companion object {
        internal const val FILE_NAME = "notification-privacy-v1.json"
        private const val INITIALIZED_STATE = "initialized-v1"
        private val PROCESS_LOCK = Any()
    }
}

/** A deliberately data-minimal recovery format: no inbox event or action can be represented. */
object NotificationPrivacyRecoveryCodec {
    const val SCHEMA = "hans.notification-privacy"
    const val VERSION = 1

    fun encode(settings: NotificationPrivacySettings): String = JSONObject()
        .put("schema", SCHEMA)
        .put("version", VERSION)
        .put(
            "retention",
            JSONObject()
                .put("maxEvents", settings.retention.maxEvents)
                .put("maxAgeHours", settings.retention.maxAgeHours),
        )
        .put("excludedPackages", JSONArray(settings.excludedPackages.sorted()))
        .toString()

    fun decode(document: String): NotificationPrivacySettings {
        val bytes = document.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= NotificationPrivacyBounds.MAX_SETTINGS_BYTES) {
            "notification_recovery_size"
        }
        val root = JSONObject(document)
        requireExactKeys(root, setOf("schema", "version", "retention", "excludedPackages"))
        require(root.getString("schema") == SCHEMA) { "notification_recovery_schema" }
        require(root.getInt("version") == VERSION) { "notification_recovery_version" }
        val retention = root.getJSONObject("retention")
        requireExactKeys(retention, setOf("maxEvents", "maxAgeHours"))
        val excluded = root.getJSONArray("excludedPackages")
        require(excluded.length() <= NotificationPrivacyBounds.MAX_USER_EXCLUSIONS) {
            "notification_exclusion_limit"
        }
        val packages = buildSet {
            for (index in 0 until excluded.length()) {
                val raw = excluded.get(index)
                require(raw is String) { "notification_package_type" }
                add(NotificationPackageNames.requireValid(raw))
            }
        }
        require(packages.size == excluded.length()) { "duplicate_notification_package" }
        return NotificationPrivacySettings(
            excludedPackages = packages,
            retention = NotificationRetentionPolicy(
                maxEvents = retention.getInt("maxEvents"),
                maxAgeHours = retention.getInt("maxAgeHours"),
            ),
        )
    }

    private fun requireExactKeys(value: JSONObject, expected: Set<String>) {
        require(value.keys().asSequence().toSet() == expected) { "notification_recovery_fields" }
    }
}

object NotificationPrivacyBounds {
    const val DEFAULT_MAX_EVENTS = 1_000
    const val MIN_MAX_EVENTS = 50
    const val MAX_MAX_EVENTS = 5_000
    const val DEFAULT_MAX_AGE_HOURS = 7 * 24
    const val MIN_MAX_AGE_HOURS = 1
    const val MAX_MAX_AGE_HOURS = 30 * 24
    const val MAX_USER_EXCLUSIONS = 256
    const val MAX_SETTINGS_BYTES = 32 * 1_024
}

private object NotificationPackageNames {
    private val safe = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*){0,31}")

    fun isValid(value: String): Boolean =
        value.length in 1..NotificationLimits.PACKAGE_UTF8_BYTES && safe.matches(value)

    fun requireValid(value: String): String = value.trim().also {
        require(isValid(it)) { "invalid_notification_package" }
    }
}

private object NotificationProtectedPackages {
    private val builtInExact = setOf(
        "android",
        "unknown.package",
        "com.android.systemui",
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.keychain",
        "com.android.credentialmanager",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
    )

    fun all(ownPackageName: String): Set<String> =
        (builtInExact + ownPackageName.trim()).filter(NotificationPackageNames::isValid).toSortedSet()

    fun matches(packageName: String, protectedPackages: Set<String>): Boolean =
        packageName in protectedPackages || packageName.startsWith("ai.hans.")
}
