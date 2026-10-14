package ai.hans.standard.integration

import ai.hans.standard.devicecontrol.tools.AccessibilitySpecialAccessProbe
import ai.hans.standard.phone.capabilities.AccessibilityGrantState

/** Preserve the fresh public-setting probe's unknown state at the Boolean tool boundary.
 * Capability admission fails closed for UNKNOWN, but diagnostics must not call it a revocation.
 * The recovery projector catches this fixed, content-free signal and reports state_unavailable.
 */
internal fun accessibilityToolAccessProbe(
    readGrantState: () -> AccessibilityGrantState,
): AccessibilitySpecialAccessProbe = AccessibilitySpecialAccessProbe {
    when (readGrantState()) {
        AccessibilityGrantState.GRANTED -> true
        AccessibilityGrantState.NOT_GRANTED -> false
        AccessibilityGrantState.UNKNOWN -> error("accessibility_grant_state_unknown")
    }
}
