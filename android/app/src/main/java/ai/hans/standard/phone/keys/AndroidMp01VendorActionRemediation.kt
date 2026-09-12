package ai.hans.standard.phone.keys

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.AccessibilityManager
import java.security.MessageDigest

/** Public-API-only probe for the current stock and legacy MP01 firmware paths. */
class AndroidMp01VendorActionRemediation(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private val packageManager = applicationContext.packageManager

    fun probe(): Mp01VendorPackageEvidence {
        val stock = probeStockSystemPolicy()
        if (stock?.trustedSystemPackage == true) return stock
        val legacy = probeLegacyAccessibilityService()
        return when {
            legacy?.trustedSystemPackage == true -> legacy
            stock != null -> stock
            legacy != null -> legacy
            else -> Mp01VendorPackageEvidence.ABSENT
        }
    }

    fun openSettings(evidence: Mp01VendorPackageEvidence = probe()): Boolean {
        val spec = Mp01VendorSettingsLaunchPolicy.explicitLaunchSpec(evidence) ?: return false
        val intent = Intent()
            .setComponent(ComponentName(spec.packageName, spec.activityName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return runCatching { applicationContext.startActivity(intent) }.isSuccess
    }

    private fun probeStockSystemPolicy(): Mp01VendorPackageEvidence? {
        val packageName = Mp01StockSystemPolicyTrustPolicy.PACKAGE_NAME
        val applicationInfo = applicationInfo(packageName) ?: return null
        val packageInfo = packageInfo(packageName, includeSignatures = true) ?: return null
        val activityInfo = activityInfo(
            ComponentName(
                packageName,
                Mp01StockSystemPolicyTrustPolicy.SETTINGS_ACTIVITY_NAME,
            ),
        )
        return Mp01StockSystemPolicyTrustPolicy.evaluate(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            packageName = applicationInfo.packageName,
            applicationFlags = applicationInfo.flags,
            applicationEnabled = applicationInfo.enabled,
            signingCertificateSha256s = signingCertificateSha256s(packageInfo),
            packageVersionCode = packageInfo.longVersionCode,
            packageLastUpdateTimeMillis = packageInfo.lastUpdateTime,
            activityPackageName = activityInfo?.packageName,
            activityName = activityInfo?.name,
            activityExported = activityInfo?.exported == true,
            activityEnabled = activityInfo?.enabled == true,
        )
    }

    private fun probeLegacyAccessibilityService(): Mp01VendorPackageEvidence? {
        val packageName = Mp01VendorPackageTrustPolicy.PACKAGE_NAME
        val applicationInfo = applicationInfo(packageName) ?: return null
        val packageInfo = packageInfo(packageName, includeSignatures = false) ?: return null
        val activityInfo = activityInfo(
            ComponentName(packageName, Mp01VendorPackageTrustPolicy.SETTINGS_ACTIVITY_NAME),
        )
        val accessibilityServiceInfo = serviceInfo(
            ComponentName(
                packageName,
                Mp01VendorPackageTrustPolicy.ACCESSIBILITY_SERVICE_NAME,
            ),
        )
        val accessibilityServiceEnabled = runCatching {
            applicationContext.getSystemService(AccessibilityManager::class.java)
                ?.getEnabledAccessibilityServiceList(
                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
                )
                .orEmpty()
                .any { enabled ->
                    val service = enabled.resolveInfo?.serviceInfo
                    service?.packageName == packageName &&
                        service.name == Mp01VendorPackageTrustPolicy.ACCESSIBILITY_SERVICE_NAME
                }
        }.getOrDefault(false)
        return Mp01VendorPackageTrustPolicy.evaluate(
            packageName = applicationInfo.packageName,
            applicationFlags = applicationInfo.flags,
            applicationEnabled = applicationInfo.enabled,
            packageVersionCode = packageInfo.longVersionCode,
            packageLastUpdateTimeMillis = packageInfo.lastUpdateTime,
            activityPackageName = activityInfo?.packageName,
            activityName = activityInfo?.name,
            activityExported = activityInfo?.exported == true,
            activityEnabled = activityInfo?.enabled == true,
            accessibilityServicePackageName = accessibilityServiceInfo?.packageName,
            accessibilityServiceName = accessibilityServiceInfo?.name,
            accessibilityServicePermission = accessibilityServiceInfo?.permission,
            accessibilityServiceDeclaredEnabled = accessibilityServiceInfo?.enabled == true,
            accessibilityServiceReportedEnabled = accessibilityServiceEnabled,
        )
    }

    private fun applicationInfo(packageName: String) = try {
        packageManager.getApplicationInfo(packageName, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(packageName: String, includeSignatures: Boolean) = try {
        packageManager.getPackageInfo(
            packageName,
            if (includeSignatures) PackageManager.GET_SIGNING_CERTIFICATES else 0,
        )
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun activityInfo(component: ComponentName) = try {
        packageManager.getActivityInfo(component, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun serviceInfo(component: ComponentName) = try {
        packageManager.getServiceInfo(component, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    }

    @Suppress("DEPRECATION")
    private fun signingCertificateSha256s(packageInfo: PackageInfo): Set<String> {
        val signers = packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        return signers.mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString(separator = "") { byte ->
                    "%02x".format(byte.toInt() and 0xff)
                }
        }
    }
}
