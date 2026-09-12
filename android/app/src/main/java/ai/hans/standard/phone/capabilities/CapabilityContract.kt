package ai.hans.standard.phone.capabilities

@JvmInline
value class CapabilityId(val value: String) {
    init {
        require(value.matches(Regex("[a-z][a-z0-9_.-]{2,95}"))) {
            "Invalid capability id"
        }
    }

    companion object {
        val LIST_LAUNCHABLE_APPS = CapabilityId("android.apps.list_launchable")
        val LAUNCH_APP = CapabilityId("android.apps.launch")
        val OPEN_VIEW = CapabilityId("android.intent.open_view")
        val OPEN_SETTINGS = CapabilityId("android.settings.open")
        val READ_BATTERY = CapabilityId("android.device.read_battery")
        val READ_NETWORK = CapabilityId("android.device.read_network")
        val MANAGE_NOTIFICATION_PRIVACY = CapabilityId("android.notifications.manage_privacy")
    }
}

@JvmInline
value class IdempotencyKey(val value: String) {
    init {
        require(value.length in 8..160 && value.all(::isSafeKeyCharacter)) {
            "Invalid idempotency key"
        }
    }

    private companion object {
        fun isSafeKeyCharacter(character: Char): Boolean =
            character.isLetterOrDigit() || character in "-_.:"
    }
}

enum class CapabilityAvailabilityKind {
    AVAILABLE,
    PERMISSION_REQUIRED,
    SPECIAL_ACCESS_REQUIRED,
    UNSUPPORTED,
}

enum class AndroidSpecialAccess {
    ALL_FILES,
    HOME_ROLE,
    ASSISTANT_ROLE,
    NOTIFICATION_LISTENER,
    ACCESSIBILITY_SERVICE,
    BATTERY_OPTIMIZATION_EXEMPTION,
}

data class AndroidCapabilityRequirements(
    val minApi: Int = 1,
    val requiredPermissions: Set<String> = emptySet(),
    val requiredSpecialAccess: Set<AndroidSpecialAccess> = emptySet(),
    val requiredSystemFeatures: Set<String> = emptySet(),
)

data class CapabilityAvailability(
    val kind: CapabilityAvailabilityKind,
    val missingPermissions: Set<String> = emptySet(),
    val missingSpecialAccess: Set<AndroidSpecialAccess> = emptySet(),
    val unsupportedReasonCode: String? = null,
)

enum class ConfirmationRisk {
    NONE,
    USER_VISIBLE,
    SENSITIVE_DATA,
    EXTERNAL_COMMUNICATION,
    DESTRUCTIVE,
}

enum class ObservationTrust {
    SYSTEM,
    UNTRUSTED_EXTERNAL,
    UNTRUSTED_INPUT,
}

data class CapabilityDescriptor(
    val id: CapabilityId,
    val displayName: String,
    val description: String,
    val requirements: AndroidCapabilityRequirements,
    val confirmationRisk: ConfirmationRisk,
    val outputTrust: ObservationTrust,
)

data class LiveCapability(
    val descriptor: CapabilityDescriptor,
    val availability: CapabilityAvailability,
)

data class CapabilityRegistrySnapshot(
    val generatedAtEpochMillis: Long,
    val capabilities: List<LiveCapability>,
) {
    val available: List<LiveCapability>
        get() = capabilities.filter {
            it.availability.kind == CapabilityAvailabilityKind.AVAILABLE
        }
}

enum class SettingsDestination {
    ALL_FILES,
    APP_DETAILS,
    HOME_APP,
    DEFAULT_APPS,
    NOTIFICATION_LISTENER,
    ACCESSIBILITY,
    EXACT_ALARM,
    BATTERY_OPTIMIZATION,
    WIRELESS,
    WIFI,
    BLUETOOTH,
}

sealed interface CapabilityCommand {
    val capabilityId: CapabilityId
    val idempotencyKey: IdempotencyKey

    data class ListLaunchableApps(
        override val idempotencyKey: IdempotencyKey,
    ) : CapabilityCommand {
        override val capabilityId: CapabilityId = CapabilityId.LIST_LAUNCHABLE_APPS
    }

    data class LaunchApp(
        override val idempotencyKey: IdempotencyKey,
        val packageName: String,
        /**
         * Process-local opaque profile handle returned by [ListLaunchableApps]. A null handle
         * deliberately preserves the Android 12-14 behavior and targets only the current user.
         */
        val profileId: String? = null,
    ) : CapabilityCommand {
        override val capabilityId: CapabilityId = CapabilityId.LAUNCH_APP
    }

    data class OpenView(
        override val idempotencyKey: IdempotencyKey,
        val uri: String,
    ) : CapabilityCommand {
        override val capabilityId: CapabilityId = CapabilityId.OPEN_VIEW
    }

    data class OpenSettings(
        override val idempotencyKey: IdempotencyKey,
        val destination: SettingsDestination,
        val packageName: String? = null,
    ) : CapabilityCommand {
        override val capabilityId: CapabilityId = CapabilityId.OPEN_SETTINGS
    }

    data class ReadBattery(
        override val idempotencyKey: IdempotencyKey,
    ) : CapabilityCommand {
        override val capabilityId: CapabilityId = CapabilityId.READ_BATTERY
    }

    data class ReadNetwork(
        override val idempotencyKey: IdempotencyKey,
    ) : CapabilityCommand {
        override val capabilityId: CapabilityId = CapabilityId.READ_NETWORK
    }
}

const val PERSONAL_LAUNCH_PROFILE_ID: String = "personal"

enum class LaunchProfileType {
    PERSONAL,
    PRIVATE,
    WORK,
    CLONE,
    OTHER,
}

data class LaunchProfileState(
    /** Semantic for the current user; otherwise an ephemeral, process-local opaque handle. */
    val profileId: String,
    val type: LaunchProfileType,
    /** Locked profiles are represented, but their app inventory must never be represented. */
    val locked: Boolean,
) {
    init {
        require(isLaunchProfileId(profileId)) { "Invalid launch profile id" }
        require(type != LaunchProfileType.PERSONAL || !locked) {
            "The current personal profile cannot be represented as locked"
        }
    }
}

data class LaunchableApp(
    val packageName: String,
    val componentName: String,
    /** App-controlled and therefore always untrusted external content. */
    val label: String,
    /** Never a raw Android user id or serial number. */
    val profileId: String = PERSONAL_LAUNCH_PROFILE_ID,
    val profileType: LaunchProfileType = LaunchProfileType.PERSONAL,
)

data class LaunchableAppCatalog(
    val apps: List<LaunchableApp>,
    val profiles: List<LaunchProfileState> = listOf(
        LaunchProfileState(
            profileId = PERSONAL_LAUNCH_PROFILE_ID,
            type = LaunchProfileType.PERSONAL,
            locked = false,
        ),
    ),
) {
    /**
     * A final fail-closed privacy boundary used by both Android and test adapters. A stale or
     * malformed adapter result can never associate inventory with a locked/unknown profile.
     */
    fun privacyFiltered(): LaunchableAppCatalog {
        val safeProfiles = profiles
            .distinctBy(LaunchProfileState::profileId)
            .sortedWith(PROFILE_ORDER)
        val profilesById = safeProfiles.associateBy(LaunchProfileState::profileId)
        return copy(
            apps = apps.filter { app ->
                val profile = profilesById[app.profileId]
                profile != null && !profile.locked && profile.type == app.profileType
            },
            profiles = safeProfiles,
        )
    }

    private companion object {
        val PROFILE_ORDER = compareBy<LaunchProfileState>(
            { it.type != LaunchProfileType.PERSONAL },
            { it.type.ordinal },
            { it.profileId },
        )
    }
}

enum class NetworkTransport {
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    BLUETOOTH,
    USB,
    OTHER,
}

sealed interface CapabilityObservation {
    val trust: ObservationTrust

    data class LaunchableApps(
        val apps: List<LaunchableApp>,
        val profiles: List<LaunchProfileState> = listOf(
            LaunchProfileState(
                profileId = PERSONAL_LAUNCH_PROFILE_ID,
                type = LaunchProfileType.PERSONAL,
                locked = false,
            ),
        ),
    ) : CapabilityObservation {
        override val trust: ObservationTrust = ObservationTrust.UNTRUSTED_EXTERNAL
    }

    data class DispatchAccepted(
        val targetPackage: String?,
        val targetComponent: String?,
        override val trust: ObservationTrust,
    ) : CapabilityObservation

    data class Battery(
        val capacityPercent: Int?,
        val charging: Boolean?,
        val powerSaveMode: Boolean,
        val temperatureTenthsCelsius: Int?,
    ) : CapabilityObservation {
        override val trust: ObservationTrust = ObservationTrust.SYSTEM
    }

    data class Network(
        val connected: Boolean,
        val validated: Boolean,
        val metered: Boolean,
        val transports: Set<NetworkTransport>,
    ) : CapabilityObservation {
        override val trust: ObservationTrust = ObservationTrust.SYSTEM
    }
}

enum class PostconditionKind {
    LIVE_LIST_OBSERVED,
    ACTIVITY_START_ACCEPTED,
    SYSTEM_STATE_OBSERVED,
    ADAPTER_OPERATION,
    REQUEST_NOT_EXECUTED,
}

enum class PostconditionStatus {
    VERIFIED,
    OBSERVED_NOT_VERIFIED,
    NOT_EVALUATED,
    FAILED,
}

data class CapabilityPostcondition(
    val kind: PostconditionKind,
    val status: PostconditionStatus,
    val detailCode: String,
)

enum class CapabilityExecutionStatus {
    SUCCEEDED,
    REJECTED,
    FAILED,
}

data class CapabilityExecutionResult(
    val capabilityId: CapabilityId,
    val idempotencyKey: IdempotencyKey,
    val status: CapabilityExecutionStatus,
    val replayed: Boolean,
    val observation: CapabilityObservation?,
    val postcondition: CapabilityPostcondition,
    /** Stable machine code only; exception or external-content strings are never propagated. */
    val errorCode: String? = null,
)

sealed interface AndroidAdapterResult<out T> {
    data class Success<T>(val value: T) : AndroidAdapterResult<T>
    data class Failure(val code: String) : AndroidAdapterResult<Nothing>
}

data class DispatchObservation(
    val targetPackage: String?,
    val targetComponent: String?,
)

interface PublicAndroidActionAdapter {
    fun listLaunchableApps(): AndroidAdapterResult<List<LaunchableApp>>
    fun launchApp(packageName: String): AndroidAdapterResult<DispatchObservation>

    /** Atomic profile/app view where supported; old test/platform adapters stay current-user only. */
    fun listLaunchableAppCatalog(): AndroidAdapterResult<LaunchableAppCatalog> =
        when (val result = listLaunchableApps()) {
            is AndroidAdapterResult.Success -> AndroidAdapterResult.Success(
                LaunchableAppCatalog(result.value).privacyFiltered(),
            )
            is AndroidAdapterResult.Failure -> result
        }

    /**
     * A non-current profile is accepted only by profile-aware adapters that can resolve the
     * opaque handle back to a live UserHandle. This default is the Android 12-14 compatibility
     * path used by existing adapters.
     */
    fun launchAppInProfile(
        packageName: String,
        profileId: String?,
    ): AndroidAdapterResult<DispatchObservation> = if (
        profileId == null || profileId == PERSONAL_LAUNCH_PROFILE_ID
    ) {
        launchApp(packageName)
    } else {
        AndroidAdapterResult.Failure("profile_not_available")
    }

    /** Android 15+ launcher-only contract. Legacy adapters fail closed without profile discovery. */
    fun privateSpaceSnapshot(): AndroidAdapterResult<PrivateSpaceSnapshot> =
        AndroidAdapterResult.Success(
            PrivateSpaceSnapshot(PrivateSpaceAvailability.UNSUPPORTED_PLATFORM),
        )

    /** [profileId] is the process-local opaque handle returned by [privateSpaceSnapshot]. */
    fun requestPrivateSpaceLocked(
        profileId: String,
        locked: Boolean,
    ): AndroidAdapterResult<PrivateSpaceQuietModeResult> =
        AndroidAdapterResult.Failure("private_space_unsupported")

    /** Uses LauncherApps.getPrivateSpaceSettingsIntent on Android 16 when Android supplies it. */
    fun openPrivateSpaceSettings(): AndroidAdapterResult<DispatchObservation> =
        AndroidAdapterResult.Failure("private_space_settings_unavailable")

    fun openSafeView(uri: String): AndroidAdapterResult<DispatchObservation>
    fun openSettings(
        destination: SettingsDestination,
        packageName: String?,
    ): AndroidAdapterResult<DispatchObservation>

    fun readBattery(): AndroidAdapterResult<CapabilityObservation.Battery>
    fun readNetwork(): AndroidAdapterResult<CapabilityObservation.Network>
}

internal fun isLaunchProfileId(value: String): Boolean =
    value.length in 3..64 && value.matches(Regex("[a-z][a-z0-9_-]+"))

interface CapabilityEnvironment {
    val apiLevel: Int

    fun hasPermission(permission: String): Boolean
    fun hasSpecialAccess(access: AndroidSpecialAccess): Boolean
    fun hasSystemFeature(feature: String): Boolean
}

interface IdempotencyLedger {
    fun find(key: IdempotencyKey): IdempotencyRecord?
    fun store(key: IdempotencyKey, record: IdempotencyRecord)
}

data class IdempotencyRecord(
    val requestFingerprint: String,
    val result: CapabilityExecutionResult,
)
