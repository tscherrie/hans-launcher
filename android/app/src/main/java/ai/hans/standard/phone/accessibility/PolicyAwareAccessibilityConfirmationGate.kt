package ai.hans.standard.phone.accessibility

import ai.hans.standard.phone.consent.HansPhoneActionPolicy

/**
 * Applied only at the extra Hans confirmation boundary, after the executor validates the current
 * snapshot/target. Risk classification, Android special access, stale-target checks, dictation
 * independence, adapter restrictions and postconditions remain the executor/adapter's concern.
 */
class PolicyAwareAccessibilityConfirmationGate(
    private val delegate: AccessibilityUserConfirmationGate,
    private val actionPolicy: HansPhoneActionPolicy = HansPhoneActionPolicy.CONFIRM_ACTIONS,
) : AccessibilityUserConfirmationGate {
    override fun isApproved(
        request: AccessibilityConfirmationRequest,
        approval: AccessibilityUserApproval?,
    ): Boolean = when (actionPolicy) {
        HansPhoneActionPolicy.CONFIRM_ACTIONS -> delegate.isApproved(request, approval)
        // A policy choice cannot invent the concrete UI context of an action.
        HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS -> request.correlation != null
    }
}
