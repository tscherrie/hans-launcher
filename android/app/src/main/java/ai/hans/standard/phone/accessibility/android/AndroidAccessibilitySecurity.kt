package ai.hans.standard.phone.accessibility.android

import ai.hans.standard.phone.accessibility.AccessibilityCommand
import ai.hans.standard.phone.accessibility.AccessibilityConfirmationRequest
import ai.hans.standard.phone.accessibility.AccessibilityConfirmationRisk
import ai.hans.standard.phone.accessibility.AccessibilityRiskPolicy
import ai.hans.standard.phone.accessibility.AccessibilityUserApproval
import ai.hans.standard.phone.accessibility.AccessibilityUserConfirmationGate
import ai.hans.standard.phone.accessibility.ExactAccessibilityUserConfirmationGate
import ai.hans.standard.phone.accessibility.SemanticUiNode
import ai.hans.standard.phone.accessibility.SemanticUiRole
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Risk is derived locally from the command and captured semantics. Model- or
 * caller-declared risk can raise the result but can never lower it.
 */
internal class TrustedAndroidAccessibilityRiskPolicy(
    private val ownPackageName: String,
) : AccessibilityRiskPolicy {
    override fun requiredRisk(
        command: AccessibilityCommand,
        target: SemanticUiNode?,
    ): AccessibilityConfirmationRisk {
        var required = command.declaredRisk()
        if (command is AccessibilityCommand.CoordinateGesture) {
            required = required.max(AccessibilityConfirmationRisk.CREDENTIAL_UI)
        }
        if (target?.role == SemanticUiRole.PASSWORD_FIELD) {
            required = required.max(AccessibilityConfirmationRisk.CREDENTIAL_UI)
        }
        val packageName = target?.packageName?.value.orEmpty().lowercase(Locale.ROOT)
        val className = target?.className?.value.orEmpty().lowercase(Locale.ROOT)
        if (
            packageName == ownPackageName.lowercase(Locale.ROOT) ||
            packageName in PRIVILEGED_UI_PACKAGES ||
            SENSITIVE_CLASS_MARKERS.any(className::contains)
        ) {
            required = required.max(AccessibilityConfirmationRisk.CREDENTIAL_UI)
        }
        val labels = listOfNotNull(target?.text?.value, target?.contentDescription?.value)
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        if (DESTRUCTIVE_LABEL_MARKERS.any(labels::contains)) {
            required = required.max(AccessibilityConfirmationRisk.DESTRUCTIVE)
        } else if (COMMUNICATION_LABEL_MARKERS.any(labels::contains)) {
            required = required.max(AccessibilityConfirmationRisk.EXTERNAL_COMMUNICATION)
        }
        return required
    }

    private fun AccessibilityCommand.declaredRisk(): AccessibilityConfirmationRisk = when (this) {
        is AccessibilityCommand.Find -> AccessibilityConfirmationRisk.NONE
        is AccessibilityCommand.Click -> confirmationRisk
        is AccessibilityCommand.SetText -> confirmationRisk
        is AccessibilityCommand.Scroll -> confirmationRisk
        is AccessibilityCommand.Global -> confirmationRisk
        is AccessibilityCommand.CoordinateGesture -> confirmationRisk
    }

    private fun AccessibilityConfirmationRisk.max(
        other: AccessibilityConfirmationRisk,
    ): AccessibilityConfirmationRisk = if (priority >= other.priority) this else other

    private companion object {
        val PRIVILEGED_UI_PACKAGES = setOf(
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
        )
        val SENSITIVE_CLASS_MARKERS = setOf(
            "accessibilitysettings",
            "biometric",
            "confirmdevicecredential",
            "deviceadmin",
            "permissiondialog",
        )
        val COMMUNICATION_LABEL_MARKERS = setOf(
            "send",
            "senden",
            "reply",
            "antworten",
            "post",
            "publish",
            "veröffentlichen",
            "share",
            "teilen",
        )
        val DESTRUCTIVE_LABEL_MARKERS = setOf(
            "delete",
            "löschen",
            "remove",
            "entfernen",
            "uninstall",
            "deinstallieren",
            "buy",
            "kaufen",
            "pay",
            "bezahlen",
            "subscribe",
            "abonnieren",
        )
    }
}

/**
 * Process-local handoff from a trusted confirmation surface. An approval must
 * be registered shortly before use and is consumed exactly once. The service
 * exposes no Binder endpoint for adding approvals.
 */
internal class ExpiringOneShotAccessibilityConfirmationGate(
    private val elapsedRealtimeMillis: () -> Long,
    private val maxEntries: Int = 64,
) : AccessibilityUserConfirmationGate {
    private val authorized = object : LinkedHashMap<String, Authorization>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Authorization>?,
        ): Boolean = size > maxEntries
    }

    init {
        require(maxEntries in 1..256)
    }

    @Synchronized
    fun authorize(
        approval: AccessibilityUserApproval,
        validityMillis: Long = DEFAULT_VALIDITY_MILLIS,
    ) {
        require(validityMillis in 1..MAX_VALIDITY_MILLIS)
        purgeExpired()
        authorized[approval.approvalId] = Authorization(
            approval = approval,
            expiresAtElapsedMillis = elapsedRealtimeMillis() + validityMillis,
        )
    }

    @Synchronized
    override fun isApproved(
        request: AccessibilityConfirmationRequest,
        approval: AccessibilityUserApproval?,
    ): Boolean {
        purgeExpired()
        approval ?: return false
        val authorization = authorized.remove(approval.approvalId) ?: return false
        if (authorization.expiresAtElapsedMillis < elapsedRealtimeMillis()) return false
        if (authorization.approval != approval) return false
        return ExactAccessibilityUserConfirmationGate.isApproved(request, approval)
    }

    @Synchronized
    fun clear() {
        authorized.clear()
    }

    private fun purgeExpired() {
        val now = elapsedRealtimeMillis()
        authorized.entries.removeAll { it.value.expiresAtElapsedMillis < now }
    }

    private data class Authorization(
        val approval: AccessibilityUserApproval,
        val expiresAtElapsedMillis: Long,
    )

    companion object {
        const val DEFAULT_VALIDITY_MILLIS = 30_000L
        const val MAX_VALIDITY_MILLIS = 60_000L
    }
}

/** Process-local only; a future trusted confirmation UI registers short-lived approvals here. */
object HansAccessibilityApprovals {
    private val activeGate = AtomicReference<ExpiringOneShotAccessibilityConfirmationGate?>()

    fun authorize(
        approval: AccessibilityUserApproval,
        validityMillis: Long = ExpiringOneShotAccessibilityConfirmationGate.DEFAULT_VALIDITY_MILLIS,
    ): Boolean {
        val gate = activeGate.get() ?: return false
        return runCatching {
            gate.authorize(approval, validityMillis)
            true
        }.getOrDefault(false)
    }

    internal fun publish(gate: ExpiringOneShotAccessibilityConfirmationGate) {
        activeGate.set(gate)
    }

    internal fun remove(gate: ExpiringOneShotAccessibilityConfirmationGate) {
        activeGate.compareAndSet(gate, null)
        gate.clear()
    }
}

internal object AndroidAutomationExclusions {
    fun isProhibited(ownPackageName: String, locator: AndroidNodeLocator): Boolean {
        val packageName = locator.fingerprint.packageName.orEmpty().lowercase(Locale.ROOT)
        val className = locator.fingerprint.className.orEmpty().lowercase(Locale.ROOT)
        if (packageName == ownPackageName.lowercase(Locale.ROOT)) return true
        if (packageName in PROHIBITED_PACKAGES) return true
        return PROHIBITED_CLASS_MARKERS.any(className::contains)
    }

    private val PROHIBITED_PACKAGES = setOf(
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
    )
    private val PROHIBITED_CLASS_MARKERS = setOf(
        "accessibilitysettings",
        "biometric",
        "confirmdevicecredential",
        "deviceadmin",
        "permissiondialog",
    )
}
