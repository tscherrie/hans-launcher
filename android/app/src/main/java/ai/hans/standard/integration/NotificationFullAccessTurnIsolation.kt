package ai.hans.standard.integration

import ai.hans.standard.codex.CodexInput

/**
 * Final fail-closed boundary before an interactive App Server turn receives full tool access.
 *
 * Notification summaries have their own restricted triage, speech and privacy-bounded fact paths.
 * They are never ambient input for a tool-capable main turn. The legacy envelope check also stops
 * an old or future caller from accidentally recreating that unsafe coupling.
 */
internal object NotificationFullAccessTurnIsolation {
    fun explicitInput(input: List<CodexInput>): List<CodexInput>? {
        val containsNotificationEnvelope = input.any { item ->
            item is CodexInput.UntrustedContext &&
                ValidatedNotificationTurnContext.isEnvelope(item.text)
        }
        return input.toList().takeUnless { containsNotificationEnvelope }
    }
}
