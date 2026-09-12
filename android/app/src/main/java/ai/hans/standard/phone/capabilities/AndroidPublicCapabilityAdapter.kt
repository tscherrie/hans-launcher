package ai.hans.standard.phone.capabilities

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.UserHandle
import android.provider.Settings
import androidx.core.net.toUri
import ai.hans.standard.phone.notifications.HansNotificationListenerService
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

class AndroidPublicCapabilityAdapter(
    context: Context,
    private val profilePlatform: AndroidLauncherProfilePlatform =
        FrameworkAndroidLauncherProfilePlatform(context.applicationContext),
) : PublicAndroidActionAdapter {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val profileHandles = OpaqueProfileHandleRegistry<UserHandle>()

    override fun listLaunchableApps(): AndroidAdapterResult<List<LaunchableApp>> =
        when (val result = listLaunchableAppCatalog()) {
            is AndroidAdapterResult.Success -> AndroidAdapterResult.Success(result.value.apps)
            is AndroidAdapterResult.Failure -> result
        }

    override fun listLaunchableAppCatalog(): AndroidAdapterResult<LaunchableAppCatalog> = guarded {
        val profilesBeforeEnumeration = liveLauncherProfiles()
        val observedLauncherApps = profilesBeforeEnumeration
            .asSequence()
            .filterNot(LiveLauncherProfile::locked)
            .flatMap { profile ->
                launcherActivities(packageName = null, user = profile.user)
                    .asSequence()
                    .map { info -> toLaunchableApp(info, profile) }
            }
            .toList()

        // Enumeration may race Android locking or removing Private Space. Reread the live
        // profile state after collecting activities and use that reread as the privacy boundary;
        // the returned catalog therefore cannot intentionally retain inventory observed before
        // the lock transition. The profile broadcast additionally purges already-rendered UI.
        val profilesAfterEnumeration = liveLauncherProfiles()
        val liveProfilesById = profilesAfterEnumeration.associateBy { it.state.profileId }
        val currentlyAccessibleApps = observedLauncherApps.filter { app ->
            val profile = liveProfilesById[app.profileId]?.state
            profile != null && !profile.locked && profile.type == app.profileType
        }
        val apps = if (currentlyAccessibleApps.isNotEmpty()) {
            currentlyAccessibleApps
        } else {
            // PackageManager cannot address another UserHandle. It is intentionally only the
            // pre-profile/current-user compatibility fallback.
            queryPackageManagerLaunchers()
        }
        LaunchableAppCatalog(
            apps = apps
                .distinctBy { "${it.profileId}|${it.componentName}" }
                .sortedWith(APP_ORDER)
                .take(MAX_APPS),
            profiles = profilesAfterEnumeration.map(LiveLauncherProfile::state),
        ).privacyFiltered()
    }

    override fun launchApp(packageName: String): AndroidAdapterResult<DispatchObservation> =
        launchAppInProfile(packageName, profileId = null)

    override fun launchAppInProfile(
        packageName: String,
        profileId: String?,
    ): AndroidAdapterResult<DispatchObservation> {
        if (!PACKAGE_NAME.matches(packageName) || packageName.length > 255) {
            return AndroidAdapterResult.Failure("invalid_package_name")
        }
        if (profileId != null && !isLaunchProfileId(profileId)) {
            return AndroidAdapterResult.Failure("invalid_profile_id")
        }
        val requestedProfileId = profileId ?: PERSONAL_LAUNCH_PROFILE_ID
        val profile = liveLauncherProfiles()
            .firstOrNull { it.state.profileId == requestedProfileId }
            ?: return AndroidAdapterResult.Failure("profile_not_available")
        if (profile.locked) return AndroidAdapterResult.Failure("profile_locked")

        return guardedDispatch {
            val availableLauncherActivities = launcherActivities(packageName, profile.user)
            val canonicalIntent = if (profile.state.profileId == PERSONAL_LAUNCH_PROFILE_ID) {
                canonicalPersonalLaunchIntent(packageName)
            } else {
                null
            }
            val canonicalIdentity = canonicalIntent
                ?.component
                ?.toLaunchComponentIdentity()
            val preferredPersonalIdentity = if (
                profile.state.profileId == PERSONAL_LAUNCH_PROFILE_ID
            ) {
                preferredPersonalLaunchComponent(
                    requestedPackage = packageName,
                    canonical = canonicalIdentity,
                    launcherActivities = availableLauncherActivities.map {
                        it.componentName.toLaunchComponentIdentity()
                    },
                )
            } else {
                null
            }

            if (canonicalIntent != null && canonicalIdentity == preferredPersonalIdentity) {
                val component = requireNotNull(canonicalIntent.component)
                appContext.startActivity(canonicalIntent)
                return@guardedDispatch DispatchObservation(
                    targetPackage = component.packageName,
                    targetComponent = component.flattenToString(),
                )
            }

            val launcherActivity = if (preferredPersonalIdentity != null) {
                availableLauncherActivities.firstOrNull {
                    it.componentName.toLaunchComponentIdentity() == preferredPersonalIdentity
                }
            } else {
                availableLauncherActivities
                    .sortedBy { it.componentName.flattenToString() }
                    .firstOrNull()
            }
            if (launcherActivity != null) {
                val launcherApps = appContext.getSystemService(LauncherApps::class.java)
                    ?: return@guardedDispatch null
                launcherApps.startMainActivity(
                    launcherActivity.componentName,
                    profile.user,
                    null,
                    null,
                )
                return@guardedDispatch DispatchObservation(
                    targetPackage = launcherActivity.componentName.packageName,
                    targetComponent = launcherActivity.componentName.flattenToString(),
                )
            }

            // The canonical PackageManager path was already attempted for the personal profile.
            // There is no PackageManager cross-profile fallback; failing closed here prevents
            // accidentally launching the personal copy of a private/work app.
            null
        }
    }

    override fun privateSpaceSnapshot(): AndroidAdapterResult<PrivateSpaceSnapshot> = guarded {
        readPrivateSpaceSnapshot()
    }

    override fun requestPrivateSpaceLocked(
        profileId: String,
        locked: Boolean,
    ): AndroidAdapterResult<PrivateSpaceQuietModeResult> {
        if (!isLaunchProfileId(profileId)) {
            return AndroidAdapterResult.Failure("invalid_profile_id")
        }
        val before = when (val snapshot = privateSpaceSnapshot()) {
            is AndroidAdapterResult.Success -> snapshot.value
            is AndroidAdapterResult.Failure -> return snapshot
        }
        if (before.availability != PrivateSpaceAvailability.AVAILABLE) {
            return AndroidAdapterResult.Failure(privateSpaceFailureCode(before.availability))
        }
        val profile = before.profile
            ?.takeIf { it.profileId == profileId && it.type == LaunchProfileType.PRIVATE }
            ?: return AndroidAdapterResult.Failure("profile_not_available")
        if (profile.locked == locked) {
            return AndroidAdapterResult.Success(
                PrivateSpaceQuietModeResult(
                    outcome = PrivateSpaceQuietModeOutcome.CONFIRMED,
                    snapshot = before,
                ),
            )
        }
        val liveProfile = liveLauncherProfiles()
            .singleOrNull {
                it.state.profileId == profileId && it.state.type == LaunchProfileType.PRIVATE
            }
            ?: return AndroidAdapterResult.Failure("profile_not_available")
        val accepted = try {
            profilePlatform.requestQuietModeEnabled(locked, liveProfile.user)
        } catch (_: IllegalArgumentException) {
            return AndroidAdapterResult.Failure("profile_not_available")
        } catch (_: SecurityException) {
            return AndroidAdapterResult.Failure("private_space_access_rejected")
        } catch (_: RuntimeException) {
            return AndroidAdapterResult.Failure("android_api_failure")
        }
        // requestQuietModeEnabled is asynchronous. Never change the UI optimistically: every
        // result includes a fresh platform read and profile broadcasts drive the later refresh.
        val reread = when (val snapshot = privateSpaceSnapshot()) {
            is AndroidAdapterResult.Success -> snapshot.value
            is AndroidAdapterResult.Failure -> return snapshot
        }
        val confirmed = reread.profile?.locked == locked
        val outcome = when {
            confirmed -> PrivateSpaceQuietModeOutcome.CONFIRMED
            accepted -> PrivateSpaceQuietModeOutcome.PENDING
            !locked -> PrivateSpaceQuietModeOutcome.AUTHENTICATION_REQUIRED
            else -> PrivateSpaceQuietModeOutcome.REJECTED
        }
        return AndroidAdapterResult.Success(PrivateSpaceQuietModeResult(outcome, reread))
    }

    override fun openPrivateSpaceSettings(): AndroidAdapterResult<DispatchObservation> {
        val snapshot = when (val result = privateSpaceSnapshot()) {
            is AndroidAdapterResult.Success -> result.value
            is AndroidAdapterResult.Failure -> return result
        }
        if (!snapshot.settingsAvailable) {
            return AndroidAdapterResult.Failure("private_space_settings_unavailable")
        }
        return if (profilePlatform.openPrivateSpaceSettings()) {
            AndroidAdapterResult.Success(
                DispatchObservation(targetPackage = null, targetComponent = null),
            )
        } else {
            AndroidAdapterResult.Failure("private_space_settings_unavailable")
        }
    }

    override fun openSafeView(uri: String): AndroidAdapterResult<DispatchObservation> {
        val parsed = parseAllowedUri(uri)
            ?: return AndroidAdapterResult.Failure("view_uri_not_allowed")
        val intent = Intent(Intent.ACTION_VIEW, parsed)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return resolveAndDispatch(intent)
    }

    override fun openSettings(
        destination: SettingsDestination,
        packageName: String?,
    ): AndroidAdapterResult<DispatchObservation> {
        if (packageName != null && (!PACKAGE_NAME.matches(packageName) || packageName.length > 255)) {
            return AndroidAdapterResult.Failure("invalid_package_name")
        }
        return resolveAndDispatchFirst(settingsIntentCandidates(destination, packageName))
    }

    /** Ordered exact route followed by a public generic fallback where Android provides both. */
    internal fun settingsIntentCandidates(
        destination: SettingsDestination,
        packageName: String?,
    ): List<Intent> = buildList {
        if (
            destination == SettingsDestination.NOTIFICATION_LISTENER &&
            Build.VERSION.SDK_INT >= 30 &&
            packageName == appContext.packageName
        ) {
            val component = ComponentName(appContext, HansNotificationListenerService::class.java)
            add(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        component.flattenToString(),
                    ),
            )
        }
        add(
            when (destination) {
                SettingsDestination.ALL_FILES -> Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.fromParts("package", appContext.packageName, null),
                )
                SettingsDestination.APP_DETAILS -> Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName ?: appContext.packageName, null),
                )
                SettingsDestination.HOME_APP -> Intent(Settings.ACTION_HOME_SETTINGS)
                SettingsDestination.DEFAULT_APPS ->
                    Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                SettingsDestination.NOTIFICATION_LISTENER ->
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                // Android has no public app-specific enablement Intent for an AccessibilityService.
                // The documented top-level screen is therefore the honest fallback.
                SettingsDestination.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                SettingsDestination.EXACT_ALARM -> Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.fromParts("package", packageName ?: appContext.packageName, null),
                )
                SettingsDestination.BATTERY_OPTIMIZATION ->
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                SettingsDestination.WIRELESS -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
                SettingsDestination.WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
                SettingsDestination.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            },
        )
        if (destination == SettingsDestination.ALL_FILES) {
            add(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
        forEach { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    override fun readBattery(): AndroidAdapterResult<CapabilityObservation.Battery> = guarded {
        val batteryManager = appContext.getSystemService(BatteryManager::class.java)
        val capacity = batteryManager
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
        val charging = batteryManager?.isCharging
        val batteryIntent = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val unknown = Int.MIN_VALUE
        val temperature = batteryIntent
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, unknown)
            ?.takeUnless { it == unknown }
        val powerSave = appContext.getSystemService(PowerManager::class.java)
            ?.isPowerSaveMode
            ?: false
        CapabilityObservation.Battery(
            capacityPercent = capacity,
            charging = charging,
            powerSaveMode = powerSave,
            temperatureTenthsCelsius = temperature,
        )
    }

    // The adapter checks the normal permission before every call. The integration manifest
    // declares it outside this isolated capability module's ownership.
    @SuppressLint("MissingPermission")
    override fun readNetwork(): AndroidAdapterResult<CapabilityObservation.Network> {
        if (
            appContext.checkSelfPermission(StandardCapabilities.ACCESS_NETWORK_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return AndroidAdapterResult.Failure("permission_required_access_network_state")
        }
        return guarded {
            val manager = appContext.getSystemService(ConnectivityManager::class.java)
                ?: return@guarded CapabilityObservation.Network(
                    connected = false,
                    validated = false,
                    metered = false,
                    transports = emptySet(),
                )
            val network = manager.activeNetwork
            val capabilities = network?.let(manager::getNetworkCapabilities)
            val connected = capabilities?.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET,
            ) == true
            val validated = capabilities?.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED,
            ) == true
            val transports = capabilities?.transportSet().orEmpty()
            CapabilityObservation.Network(
                connected = connected,
                validated = validated,
                metered = manager.isActiveNetworkMetered,
                transports = transports,
            )
        }
    }

    private fun launcherActivities(
        packageName: String?,
        user: UserHandle,
    ): List<LauncherActivityInfo> = runCatching {
        appContext.getSystemService(LauncherApps::class.java)
            ?.getActivityList(packageName, user)
            .orEmpty()
    }.getOrDefault(emptyList())

    private fun toLaunchableApp(
        info: LauncherActivityInfo,
        profile: LiveLauncherProfile,
    ): LaunchableApp = LaunchableApp(
        packageName = bounded(info.applicationInfo.packageName, 255),
        componentName = bounded(info.componentName.flattenToString(), 512),
        label = bounded(info.label?.toString().orEmpty(), 256),
        profileId = profile.state.profileId,
        profileType = profile.state.type,
    )

    /**
     * Reads profile type and lock state on every catalog/launch operation. In particular, no
     * private app inventory or unlocked bit is cached after Android locks Private Space.
     */
    private fun liveLauncherProfiles(): List<LiveLauncherProfile> {
        val current = profilePlatform.currentUser()
        val users = runCatching { profilePlatform.accessibleProfiles() }
            .getOrDefault(emptyList())
            .toMutableList()
            .apply { if (none { it == current }) add(0, current) }
            .distinct()
        val visibleUsers = users.mapNotNull { user ->
            val type = runCatching { profilePlatform.profileType(user, current) }
                .getOrDefault(LaunchProfileType.OTHER)
            // Android 15 introduced hidden profiles. A failed or future/unknown profile-type
            // lookup must never be treated as an ordinary visible profile because it could be a
            // hidden profile for which Hans lacks the HOME role or manifest capability.
            if (profilePlatform.apiLevel >= 35 && type == LaunchProfileType.OTHER) {
                return@mapNotNull null
            }
            if (
                type == LaunchProfileType.PRIVATE &&
                privateSpaceAccessAvailability() != PrivateSpaceAvailability.AVAILABLE
            ) {
                return@mapNotNull null
            }
            user to type
        }
        profileHandles.retain(visibleUsers.mapNotNull { (user, _) ->
            user.takeUnless { it == current }
        }.toSet())
        return visibleUsers.map { (user, type) ->
            val locked = runCatching { profilePlatform.isProfileLocked(user, current) }
                .getOrDefault(user != current)
            LiveLauncherProfile(
                user = user,
                state = LaunchProfileState(
                    profileId = if (user == current) {
                        PERSONAL_LAUNCH_PROFILE_ID
                    } else {
                        profileHandles.handleFor(user)
                    },
                    type = type,
                    locked = locked,
                ),
            )
        }
    }

    private fun readPrivateSpaceSnapshot(): PrivateSpaceSnapshot {
        val access = privateSpaceAccessAvailability()
        if (access != PrivateSpaceAvailability.AVAILABLE) {
            return PrivateSpaceSnapshot(availability = access)
        }
        val privateProfiles = liveLauncherProfiles()
            .filter { it.state.type == LaunchProfileType.PRIVATE }
        if (privateProfiles.size > 1) {
            throw IllegalStateException("More than one Private Space profile was exposed")
        }
        val settingsAvailable = runCatching {
            profilePlatform.privateSpaceSettingsAvailable()
        }.getOrDefault(false)
        val profile = privateProfiles.singleOrNull()?.state
            ?: return PrivateSpaceSnapshot(
                availability = PrivateSpaceAvailability.NOT_CONFIGURED,
                settingsAvailable = settingsAvailable,
            )
        val entrypointHiddenWhenLocked = if (profilePlatform.apiLevel >= 36) {
            val liveProfile = privateProfiles.single()
            runCatching {
                profilePlatform.privateSpaceEntrypointHiddenWhenLocked(liveProfile.user)
            }.getOrDefault(true)
        } else {
            false
        }
        return PrivateSpaceSnapshot(
            availability = PrivateSpaceAvailability.AVAILABLE,
            profile = profile,
            entrypointHiddenWhenLocked = entrypointHiddenWhenLocked,
            settingsAvailable = settingsAvailable,
        )
    }

    private fun privateSpaceAccessAvailability(): PrivateSpaceAvailability = when {
        profilePlatform.apiLevel < 35 -> PrivateSpaceAvailability.UNSUPPORTED_PLATFORM
        !runCatching(profilePlatform::hasHomeRole).getOrDefault(false) ->
            PrivateSpaceAvailability.HOME_ROLE_REQUIRED
        !runCatching(profilePlatform::hasHiddenProfilesPermission).getOrDefault(false) ->
            PrivateSpaceAvailability.HIDDEN_PROFILE_ACCESS_REQUIRED
        else -> PrivateSpaceAvailability.AVAILABLE
    }

    private fun privateSpaceFailureCode(availability: PrivateSpaceAvailability): String = when (
        availability
    ) {
        PrivateSpaceAvailability.UNSUPPORTED_PLATFORM -> "private_space_unsupported"
        PrivateSpaceAvailability.HOME_ROLE_REQUIRED -> "home_role_required"
        PrivateSpaceAvailability.HIDDEN_PROFILE_ACCESS_REQUIRED ->
            "hidden_profile_access_required"
        PrivateSpaceAvailability.NOT_CONFIGURED -> "private_space_not_configured"
        PrivateSpaceAvailability.AVAILABLE -> "private_space_operation_failed"
    }

    private fun queryPackageManagerLaunchers(): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return queryActivities(intent)
            .filter { it.activityInfo?.exported == true }
            .mapNotNull { info ->
                val activity = info.activityInfo ?: return@mapNotNull null
                val component = ComponentName(activity.packageName, activity.name)
                LaunchableApp(
                    packageName = bounded(activity.packageName, 255),
                    componentName = bounded(component.flattenToString(), 512),
                    label = bounded(info.loadLabel(packageManager)?.toString().orEmpty(), 256),
                    profileId = PERSONAL_LAUNCH_PROFILE_ID,
                    profileType = LaunchProfileType.PERSONAL,
                )
            }
    }

    /**
     * Returns Android's package-defined front door for the personal profile. The component is
     * re-read exactly and must remain exported and owned by the requested package before launch.
     */
    private fun canonicalPersonalLaunchIntent(packageName: String): Intent? {
        val candidate = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        val component = candidate.component
            ?: resolveExported(candidate)?.activityInfo?.componentName()
            ?: return null
        if (component.packageName != packageName || !isExactExportedActivity(component)) {
            return null
        }
        return Intent(candidate)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun isExactExportedActivity(component: ComponentName): Boolean {
        val activityInfo = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getActivityInfo(
                    component,
                    PackageManager.ComponentInfoFlags.of(0L),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getActivityInfo(component, 0)
            }
        }.getOrNull() ?: return false
        return activityInfo.exported &&
            activityInfo.packageName == component.packageName &&
            activityInfo.name == component.className
    }

    private fun resolveAndDispatch(intent: Intent): AndroidAdapterResult<DispatchObservation> =
        guardedDispatch {
            val resolved = resolveExported(intent) ?: return@guardedDispatch null
            val component = resolved.activityInfo.componentName()
            appContext.startActivity(Intent(intent).setComponent(component))
            DispatchObservation(
                targetPackage = resolved.activityInfo.packageName,
                targetComponent = component.flattenToString(),
            )
        }

    private fun resolveAndDispatchFirst(
        intents: List<Intent>,
    ): AndroidAdapterResult<DispatchObservation> {
        var lastFailure = "activity_not_resolved"
        intents.forEach { intent ->
            val resolved = runCatching { resolveExported(intent) }.getOrNull() ?: return@forEach
            val component = resolved.activityInfo.componentName()
            try {
                appContext.startActivity(Intent(intent).setComponent(component))
                return AndroidAdapterResult.Success(
                    DispatchObservation(
                        targetPackage = resolved.activityInfo.packageName,
                        targetComponent = component.flattenToString(),
                    ),
                )
            } catch (_: ActivityNotFoundException) {
                lastFailure = "activity_not_found"
            } catch (_: SecurityException) {
                lastFailure = "android_security_rejection"
            } catch (_: RuntimeException) {
                lastFailure = "android_api_failure"
            }
        }
        return AndroidAdapterResult.Failure(lastFailure)
    }

    private fun resolveExported(intent: Intent): ResolveInfo? = resolveActivity(intent)
        ?.takeIf { it.activityInfo?.exported == true }

    private fun resolveActivity(intent: Intent): ResolveInfo? = if (Build.VERSION.SDK_INT >= 33) {
        packageManager.resolveActivity(
            intent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }

    private fun queryActivities(intent: Intent): List<ResolveInfo> = if (Build.VERSION.SDK_INT >= 33) {
        packageManager.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
    }

    private fun parseAllowedUri(raw: String): Uri? {
        val accepted = SafeViewUriPolicy.accept(raw) ?: return null
        return runCatching { accepted.toUri() }.getOrNull()
    }

    private fun NetworkCapabilities.transportSet(): Set<NetworkTransport> = buildSet {
        if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(NetworkTransport.WIFI)
        if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(NetworkTransport.CELLULAR)
        if (hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(NetworkTransport.ETHERNET)
        if (hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(NetworkTransport.VPN)
        if (hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add(NetworkTransport.BLUETOOTH)
        if (hasTransport(NetworkCapabilities.TRANSPORT_USB)) add(NetworkTransport.USB)
        if (isEmpty()) add(NetworkTransport.OTHER)
    }

    private fun android.content.pm.ActivityInfo.componentName(): ComponentName =
        ComponentName(packageName, name)

    private fun ComponentName.toLaunchComponentIdentity(): LaunchComponentIdentity =
        LaunchComponentIdentity(packageName = packageName, className = className)

    private fun <T> guarded(block: () -> T): AndroidAdapterResult<T> = try {
        AndroidAdapterResult.Success(block())
    } catch (_: SecurityException) {
        AndroidAdapterResult.Failure("android_security_rejection")
    } catch (_: RuntimeException) {
        AndroidAdapterResult.Failure("android_api_failure")
    }

    private fun guardedDispatch(block: () -> DispatchObservation?): AndroidAdapterResult<DispatchObservation> =
        try {
            val observation = block()
                ?: return AndroidAdapterResult.Failure("activity_not_resolved")
            AndroidAdapterResult.Success(observation)
        } catch (_: ActivityNotFoundException) {
            AndroidAdapterResult.Failure("activity_not_found")
        } catch (_: SecurityException) {
            AndroidAdapterResult.Failure("android_security_rejection")
        } catch (_: RuntimeException) {
            AndroidAdapterResult.Failure("android_api_failure")
        }

    private fun bounded(value: String, maxUtf8Bytes: Int): String {
        val clean = value
            .asSequence()
            .filterNot { it.isISOControl() || it.code in BIDI_CONTROLS }
            .joinToString(separator = "")
            .trim()
        if (clean.toByteArray(StandardCharsets.UTF_8).size <= maxUtf8Bytes) return clean
        val result = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < clean.length) {
            val codePoint = clean.codePointAt(index)
            val encoded = String(Character.toChars(codePoint)).toByteArray(StandardCharsets.UTF_8)
            if (bytes + encoded.size > maxUtf8Bytes) break
            result.appendCodePoint(codePoint)
            bytes += encoded.size
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }

    companion object {
        private const val MAX_APPS = 1_000
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val APP_ORDER = compareBy<LaunchableApp>(
            { it.profileType != LaunchProfileType.PERSONAL },
            { it.profileType.ordinal },
            { it.label.lowercase() },
            { it.packageName },
            { it.componentName },
        )
        private val BIDI_CONTROLS = buildSet {
            add(0x061C)
            add(0x200E)
            add(0x200F)
            addAll(0x202A..0x202E)
            addAll(0x2066..0x2069)
        }
    }
}

internal data class LaunchComponentIdentity(
    val packageName: String,
    val className: String,
)

/**
 * Keeps selection deterministic while honoring Android's package-defined front door. Candidate
 * package names are checked exactly so a resolver or another package can never replace the
 * requested target.
 */
internal fun preferredPersonalLaunchComponent(
    requestedPackage: String,
    canonical: LaunchComponentIdentity?,
    launcherActivities: List<LaunchComponentIdentity>,
): LaunchComponentIdentity? = canonical
    ?.takeIf { it.packageName == requestedPackage && it.className.isNotBlank() }
    ?: launcherActivities
        .asSequence()
        .filter { it.packageName == requestedPackage && it.className.isNotBlank() }
        .distinct()
        .minByOrNull { "${it.packageName}/${it.className}" }

private data class LiveLauncherProfile(
    val user: UserHandle,
    val state: LaunchProfileState,
) {
    val locked: Boolean
        get() = state.locked
}

/** Keeps Android UserHandle values behind random process-local handles; it stores no app data. */
internal class OpaqueProfileHandleRegistry<T : Any>(
    private val randomBytes: () -> ByteArray = {
        ByteArray(PROFILE_HANDLE_RANDOM_BYTES).also(SecureRandom()::nextBytes)
    },
) {
    private val handles = linkedMapOf<T, String>()

    @Synchronized
    fun handleFor(profile: T): String = handles.getOrPut(profile) {
        var candidate: String
        do {
            candidate = "profile_" + randomBytes()
                .take(PROFILE_HANDLE_RANDOM_BYTES)
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        } while (candidate in handles.values)
        candidate
    }

    @Synchronized
    fun retain(liveProfiles: Set<T>) {
        handles.keys.retainAll(liveProfiles)
    }

    private companion object {
        const val PROFILE_HANDLE_RANDOM_BYTES = 16
    }
}
