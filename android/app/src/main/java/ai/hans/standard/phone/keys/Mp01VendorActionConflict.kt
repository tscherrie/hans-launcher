package ai.hans.standard.phone.keys

import android.Manifest
import android.content.pm.ApplicationInfo
import android.view.KeyEvent
import java.security.MessageDigest

/** MP01 mappings observed across the restored stock ROM and earlier Hans builds. */
enum class Mp01ActionButtonMappingKind {
    /** Stock Android maps Linux scan code 252 to vendor AREFRESH key code 668. */
    STOCK_AREFRESH,

    /** Older firmware exposed the same scan code as Android KEYCODE_REFRESH (285). */
    LEGACY_REFRESH,

    /** Earlier Hans builds exposed the same physical key as Android F9. */
    LEGACY_HANS_F9,
}

/** The layer that already observes or owns the physical MP01 action button. */
enum class Mp01VendorActionConflictKind {
    /** PhoneWindowManager handles AREFRESH before an ordinary app can consume it. */
    STOCK_SYSTEM_POLICY,

    /** An older Minimal AccessibilityService independently observes the key. */
    LEGACY_ACCESSIBILITY,
}

/** Publicly observable, trust-checked state of a preinstalled Minimal component. */
data class Mp01VendorPackageEvidence(
    val trustedSystemPackage: Boolean,
    val settingsActivityLaunchable: Boolean,
    val accessibilityServicePresent: Boolean,
    val accessibilityServiceEnabled: Boolean,
    val packageVersionCode: Long,
    val packageLastUpdateTimeMillis: Long,
    val conflictKind: Mp01VendorActionConflictKind =
        Mp01VendorActionConflictKind.LEGACY_ACCESSIBILITY,
) {
    init {
        require(!settingsActivityLaunchable || trustedSystemPackage) {
            "Only a trusted Minimal system package may provide settings"
        }
        require(!accessibilityServicePresent || trustedSystemPackage) {
            "Only a trusted Minimal system package may provide the vendor service"
        }
        require(!accessibilityServiceEnabled || accessibilityServicePresent) {
            "An enabled vendor service must first be present"
        }
        require(packageVersionCode >= 0)
        require(packageLastUpdateTimeMillis >= 0)
    }

    val hasActiveVendorConflict: Boolean
        get() = trustedSystemPackage && when (conflictKind) {
            Mp01VendorActionConflictKind.STOCK_SYSTEM_POLICY -> true
            Mp01VendorActionConflictKind.LEGACY_ACCESSIBILITY ->
                accessibilityServiceEnabled
        }

    val userConfirmationAllowed: Boolean
        get() = conflictKind == Mp01VendorActionConflictKind.LEGACY_ACCESSIBILITY &&
            hasActiveVendorConflict

    companion object {
        val ABSENT = Mp01VendorPackageEvidence(
            trustedSystemPackage = false,
            settingsActivityLaunchable = false,
            accessibilityServicePresent = false,
            accessibilityServiceEnabled = false,
            packageVersionCode = 0,
            packageLastUpdateTimeMillis = 0,
        )
    }
}

/** Trust boundary for the restored stock MP01 system-policy path. */
object Mp01StockSystemPolicyTrustPolicy {
    const val PACKAGE_NAME = "com.minimalcompany.quicksettings"
    const val SETTINGS_ACTIVITY_NAME =
        "com.minimalcompany.quicksettings.view.MainActivity"
    const val SIGNING_CERTIFICATE_SHA256 =
        "4f4086b4874f355f02472c8c226019c7676dfbe9bc01ba1914a55b46de90eee1"

    @Suppress("LongParameterList")
    fun evaluate(
        manufacturer: String,
        brand: String,
        model: String,
        device: String,
        packageName: String,
        applicationFlags: Int,
        applicationEnabled: Boolean,
        signingCertificateSha256s: Set<String>,
        packageVersionCode: Long,
        packageLastUpdateTimeMillis: Long,
        activityPackageName: String?,
        activityName: String?,
        activityExported: Boolean,
        activityEnabled: Boolean,
    ): Mp01VendorPackageEvidence {
        val exactDevice = manufacturer.equals("ALONG", ignoreCase = true) &&
            brand.equals("Minimal_Phone", ignoreCase = true) &&
            model.equals("MP01", ignoreCase = true) &&
            device.equals("MP01", ignoreCase = true)
        val trustedPackage = exactDevice &&
            packageName == PACKAGE_NAME &&
            applicationEnabled &&
            applicationFlags and SYSTEM_APP_FLAGS != 0 &&
            signingCertificateSha256s.any {
                it.equals(SIGNING_CERTIFICATE_SHA256, ignoreCase = true)
            }
        val launchable = trustedPackage &&
            activityPackageName == PACKAGE_NAME &&
            activityName == SETTINGS_ACTIVITY_NAME &&
            activityExported &&
            activityEnabled
        return Mp01VendorPackageEvidence(
            trustedSystemPackage = trustedPackage,
            settingsActivityLaunchable = launchable,
            accessibilityServicePresent = false,
            accessibilityServiceEnabled = false,
            packageVersionCode = packageVersionCode.coerceAtLeast(0),
            packageLastUpdateTimeMillis = packageLastUpdateTimeMillis.coerceAtLeast(0),
            conflictKind = Mp01VendorActionConflictKind.STOCK_SYSTEM_POLICY,
        )
    }

    private const val SYSTEM_APP_FLAGS =
        ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
}

/** Trust boundary for the legacy Minimal AccessibilityService firmware path. */
object Mp01VendorPackageTrustPolicy {
    const val PACKAGE_NAME = "com.lmqr.hMP01_comp_service"
    const val SETTINGS_ACTIVITY_NAME = "com.lmqr.hMP01_comp_service.SettingsActivity"
    const val ACCESSIBILITY_SERVICE_NAME =
        "com.lmqr.hMP01_comp_service.MP01AccessibilityService"

    @Suppress("LongParameterList")
    fun evaluate(
        packageName: String,
        applicationFlags: Int,
        applicationEnabled: Boolean,
        packageVersionCode: Long,
        packageLastUpdateTimeMillis: Long,
        activityPackageName: String?,
        activityName: String?,
        activityExported: Boolean,
        activityEnabled: Boolean,
        accessibilityServicePackageName: String?,
        accessibilityServiceName: String?,
        accessibilityServicePermission: String?,
        accessibilityServiceDeclaredEnabled: Boolean,
        accessibilityServiceReportedEnabled: Boolean,
    ): Mp01VendorPackageEvidence {
        val systemPackage = packageName == PACKAGE_NAME &&
            applicationEnabled &&
            applicationFlags and SYSTEM_APP_FLAGS != 0
        val servicePresent = systemPackage &&
            accessibilityServicePackageName == PACKAGE_NAME &&
            accessibilityServiceName == ACCESSIBILITY_SERVICE_NAME &&
            accessibilityServicePermission == Manifest.permission.BIND_ACCESSIBILITY_SERVICE &&
            accessibilityServiceDeclaredEnabled
        val serviceEnabled = servicePresent && accessibilityServiceReportedEnabled
        val launchable = systemPackage &&
            activityPackageName == PACKAGE_NAME &&
            activityName == SETTINGS_ACTIVITY_NAME &&
            activityExported &&
            activityEnabled
        return Mp01VendorPackageEvidence(
            trustedSystemPackage = systemPackage,
            settingsActivityLaunchable = launchable,
            accessibilityServicePresent = servicePresent,
            accessibilityServiceEnabled = serviceEnabled,
            packageVersionCode = packageVersionCode.coerceAtLeast(0),
            packageLastUpdateTimeMillis = packageLastUpdateTimeMillis.coerceAtLeast(0),
            conflictKind = Mp01VendorActionConflictKind.LEGACY_ACCESSIBILITY,
        )
    }

    private const val SYSTEM_APP_FLAGS =
        ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
}

data class Mp01VendorSettingsLaunchSpec(
    val packageName: String,
    val activityName: String,
)

object Mp01VendorSettingsLaunchPolicy {
    fun explicitLaunchSpec(
        evidence: Mp01VendorPackageEvidence,
    ): Mp01VendorSettingsLaunchSpec? {
        if (!evidence.settingsActivityLaunchable) return null
        return when (evidence.conflictKind) {
            Mp01VendorActionConflictKind.STOCK_SYSTEM_POLICY ->
                Mp01VendorSettingsLaunchSpec(
                    packageName = Mp01StockSystemPolicyTrustPolicy.PACKAGE_NAME,
                    activityName = Mp01StockSystemPolicyTrustPolicy.SETTINGS_ACTIVITY_NAME,
                )
            Mp01VendorActionConflictKind.LEGACY_ACCESSIBILITY ->
                Mp01VendorSettingsLaunchSpec(
                    packageName = Mp01VendorPackageTrustPolicy.PACKAGE_NAME,
                    activityName = Mp01VendorPackageTrustPolicy.SETTINGS_ACTIVITY_NAME,
                )
        }
    }
}

/** Device-local acknowledgement bound to an exact legacy mapping and vendor state. */
data class Mp01VendorActionOverrideConfirmation(
    val mappingSetFingerprint: String,
    val vendorStateFingerprint: String,
)

data class Mp01RecognizedActionMapping(
    val mapping: ActionKeyMapping,
    val mappingKind: Mp01ActionButtonMappingKind,
)

data class Mp01VendorActionConflict(
    val recognizedMappings: List<Mp01RecognizedActionMapping>,
    val kind: Mp01VendorActionConflictKind,
    val settingsActivityLaunchable: Boolean,
    val replacementConfirmed: Boolean,
    val stockPressToggleCompatible: Boolean,
) {
    init {
        require(recognizedMappings.isNotEmpty())
        require(kind == Mp01VendorActionConflictKind.STOCK_SYSTEM_POLICY ||
            !stockPressToggleCompatible)
        require(kind != Mp01VendorActionConflictKind.STOCK_SYSTEM_POLICY ||
            !replacementConfirmed) {
            "Stock PhoneWindowManager ownership cannot be confirmed away root-free"
        }
    }

    val mappings: List<ActionKeyMapping>
        get() = recognizedMappings.map(Mp01RecognizedActionMapping::mapping)

    val userConfirmationAllowed: Boolean
        get() = kind == Mp01VendorActionConflictKind.LEGACY_ACCESSIBILITY

    val replacementRequired: Boolean
        get() = when (kind) {
            Mp01VendorActionConflictKind.STOCK_SYSTEM_POLICY ->
                !stockPressToggleCompatible
            Mp01VendorActionConflictKind.LEGACY_ACCESSIBILITY ->
                !replacementConfirmed
        }
}

data class Mp01VendorActionGateResult(
    val effectiveMappings: ActionKeyMappingSet,
    val conflict: Mp01VendorActionConflict?,
)

/** Resolves MP01 ownership while allowing the user-approved short-press toggle. */
object Mp01VendorActionConflictResolver {
    const val ACTION_SCAN_CODE = 252
    const val STOCK_AREFRESH_KEY_CODE = 668
    const val LEGACY_REFRESH_KEY_CODE = 285
    const val STOCK_LONG_PRESS_MILLIS = 400L

    /** Compatibility alias retained for earlier call sites. */
    const val STOCK_REFRESH_KEY_CODE = STOCK_AREFRESH_KEY_CODE

    fun resolve(
        mappings: ActionKeyMappingSet,
        vendorEvidence: Mp01VendorPackageEvidence,
        confirmation: Mp01VendorActionOverrideConfirmation?,
    ): Mp01VendorActionConflict? {
        val failClosedStockHolds = mappings.mappings.mapNotNull { mapping ->
            if (
                isStockActionButton(mapping) &&
                mapping.trigger == ActionKeyTrigger.HOLD_TO_TALK
            ) {
                Mp01RecognizedActionMapping(
                    mapping,
                    Mp01ActionButtonMappingKind.STOCK_AREFRESH,
                )
            } else {
                null
            }
        }
        if (!vendorEvidence.hasActiveVendorConflict) {
            if (failClosedStockHolds.isEmpty()) return null
            return Mp01VendorActionConflict(
                recognizedMappings = failClosedStockHolds,
                kind = Mp01VendorActionConflictKind.STOCK_SYSTEM_POLICY,
                settingsActivityLaunchable = false,
                replacementConfirmed = false,
                stockPressToggleCompatible = false,
            )
        }
        val recognized = mappings.mappings.mapNotNull { mapping ->
            recognize(mapping, vendorEvidence.conflictKind)?.let { kind ->
                Mp01RecognizedActionMapping(mapping, kind)
            }
        }
        if (recognized.isEmpty()) {
            if (failClosedStockHolds.isEmpty()) return null
            return Mp01VendorActionConflict(
                recognizedMappings = failClosedStockHolds,
                kind = Mp01VendorActionConflictKind.STOCK_SYSTEM_POLICY,
                settingsActivityLaunchable = false,
                replacementConfirmed = false,
                stockPressToggleCompatible = false,
            )
        }
        val replacementConfirmed = vendorEvidence.userConfirmationAllowed &&
            confirmation?.mappingSetFingerprint ==
            Mp01ActionMappingSetFingerprint.of(recognized.map { it.mapping }) &&
            confirmation.vendorStateFingerprint ==
            Mp01VendorStateFingerprint.of(vendorEvidence)
        val compatibleToggle =
            vendorEvidence.conflictKind == Mp01VendorActionConflictKind.STOCK_SYSTEM_POLICY &&
                recognized.all { it.mapping.trigger == ActionKeyTrigger.PRESS }
        return Mp01VendorActionConflict(
            recognizedMappings = recognized,
            kind = vendorEvidence.conflictKind,
            settingsActivityLaunchable = vendorEvidence.settingsActivityLaunchable,
            replacementConfirmed = replacementConfirmed,
            stockPressToggleCompatible = compatibleToggle,
        )
    }

    fun gate(
        mappings: ActionKeyMappingSet,
        vendorEvidence: Mp01VendorPackageEvidence,
        confirmation: Mp01VendorActionOverrideConfirmation?,
    ): Mp01VendorActionGateResult {
        val conflict = resolve(mappings, vendorEvidence, confirmation)
        val blockedMappings = buildList {
            if (conflict?.replacementRequired == true) addAll(conflict.mappings)
            addAll(
                mappings.mappings.filter { mapping ->
                    isStockActionButton(mapping) &&
                        mapping.trigger == ActionKeyTrigger.HOLD_TO_TALK
                },
            )
        }.distinctBy(ActionKeyMapping::mappingId)
        val effectiveMappings = blockedMappings.fold(mappings) { effective, mapping ->
            effective.remove(mapping.mappingId)
        }
        return Mp01VendorActionGateResult(effectiveMappings, conflict)
    }

    private fun recognize(
        mapping: ActionKeyMapping,
        conflictKind: Mp01VendorActionConflictKind,
    ): Mp01ActionButtonMappingKind? {
        if (mapping.scanCode != ACTION_SCAN_CODE) return null
        return when (conflictKind) {
            Mp01VendorActionConflictKind.STOCK_SYSTEM_POLICY -> when (mapping.keyCode) {
                STOCK_AREFRESH_KEY_CODE -> Mp01ActionButtonMappingKind.STOCK_AREFRESH
                else -> null
            }
            Mp01VendorActionConflictKind.LEGACY_ACCESSIBILITY -> when (mapping.keyCode) {
                LEGACY_REFRESH_KEY_CODE -> Mp01ActionButtonMappingKind.LEGACY_REFRESH
                KeyEvent.KEYCODE_F9 -> Mp01ActionButtonMappingKind.LEGACY_HANS_F9
                else -> null
            }
        }
    }

    fun recognizesPhysicalEvent(event: ObservableAndroidKeyEvent): Boolean =
        event.scanCode == ACTION_SCAN_CODE && event.keyCode in setOf(
            STOCK_AREFRESH_KEY_CODE,
            LEGACY_REFRESH_KEY_CODE,
            KeyEvent.KEYCODE_F9,
        )

    fun isStockActionButton(mapping: ActionKeyMapping): Boolean =
        mapping.scanCode == ACTION_SCAN_CODE &&
            mapping.keyCode == STOCK_AREFRESH_KEY_CODE
}

/** Stable, non-secret binding between user confirmation and one exact mapping. */
object Mp01ActionMappingFingerprint {
    fun of(mapping: ActionKeyMapping): String = sha256(
        listOf(
            mapping.mappingId,
            mapping.device.vendorId.toString(),
            mapping.device.productId.toString(),
            mapping.device.descriptorSha256,
            mapping.source.toString(),
            mapping.scanCode.toString(),
            mapping.keyCode.toString(),
            mapping.metaState.toString(),
            mapping.trigger.name,
            mapping.action.name,
        ).joinToString(separator = "\u0000"),
    )
}

/** Deterministic binding for every recognized mapping, not just the first one. */
object Mp01ActionMappingSetFingerprint {
    fun of(mappings: List<ActionKeyMapping>): String {
        require(mappings.isNotEmpty())
        return sha256(
            mappings.sortedBy(ActionKeyMapping::mappingId)
                .joinToString(separator = "\u0000") { mapping ->
                    Mp01ActionMappingFingerprint.of(mapping)
                },
        )
    }
}

/** A vendor update or disable/re-enable transition invalidates legacy confirmation. */
object Mp01VendorStateFingerprint {
    fun of(evidence: Mp01VendorPackageEvidence): String? {
        if (!evidence.userConfirmationAllowed) return null
        return sha256(
            listOf(
                Mp01VendorPackageTrustPolicy.PACKAGE_NAME,
                Mp01VendorPackageTrustPolicy.ACCESSIBILITY_SERVICE_NAME,
                evidence.packageVersionCode.toString(),
                evidence.packageLastUpdateTimeMillis.toString(),
                evidence.accessibilityServiceEnabled.toString(),
            ).joinToString(separator = "\u0000"),
        )
    }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
