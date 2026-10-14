package ai.hans.standard.integration

import ai.hans.standard.codex.CodexInput
import ai.hans.standard.codex.UntrustedTurnContext

/** Sampled on the actual start/steer, never queued as a separate user request or model turn. */
internal fun interface UserTurnContextProvider {
    fun current(threadId: String, input: List<CodexInput>): UntrustedTurnContext?

    companion object { val NONE = UserTurnContextProvider { _, _ -> null } }
}
