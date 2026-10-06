package ai.hans.standard.voice.feedback

import ai.hans.standard.R
import ai.hans.standard.localization.HansTextResolver

import java.util.LinkedHashSet

data class SpeechServiceFailureSnapshot(
    val code: String? = null,
    val revision: Long = 0,
)

fun interface SpeechServiceFailureObserver {
    fun onFailure(snapshot: SpeechServiceFailureSnapshot)
}

/** Process-local, content-free bridge from background speech providers to the launcher UI. */
class SpeechServiceFailureStore {
    private val observers = LinkedHashSet<SpeechServiceFailureObserver>()
    private var current = SpeechServiceFailureSnapshot()

    fun addObserver(observer: SpeechServiceFailureObserver) {
        val snapshot = synchronized(this) {
            observers += observer
            current
        }
        runCatching { observer.onFailure(snapshot) }
    }

    @Synchronized
    fun removeObserver(observer: SpeechServiceFailureObserver) {
        observers -= observer
    }

    fun report(rawCode: String) {
        val safeCode = rawCode.takeIf { it.matches(Regex("[a-z0-9_]{1,128}")) }
            ?: "speech_service_failure"
        val (snapshot, targets) = synchronized(this) {
            // A new failed attempt is a new event, even if the provider returns the same code.
            current = SpeechServiceFailureSnapshot(safeCode, current.revision + 1)
            current to observers.toList()
        }
        targets.forEach { runCatching { it.onFailure(snapshot) } }
    }

    fun dismiss(revision: Long) {
        val (snapshot, targets) = synchronized(this) {
            // A delayed tap must not erase a newer failure.
            if (current.revision != revision || current.code == null) return
            current = SpeechServiceFailureSnapshot(revision = current.revision + 1)
            current to observers.toList()
        }
        targets.forEach { runCatching { it.onFailure(snapshot) } }
    }
}

object HansSpeechFailureRuntime {
    private val store = SpeechServiceFailureStore()
    fun addObserver(observer: SpeechServiceFailureObserver) = store.addObserver(observer)
    fun removeObserver(observer: SpeechServiceFailureObserver) = store.removeObserver(observer)
    fun report(code: String) = store.report(code)
    fun dismiss(revision: Long) = store.dismiss(revision)
}

/** Fixed remediation targets only; provider text can never supply a URL. */
enum class OpenAiSpeechRemediation(val url: String) {
    BILLING("https://platform.openai.com/settings/organization/billing"),
    LIMITS("https://platform.openai.com/settings/organization/limits"),
    PROJECT_SETTINGS("https://platform.openai.com/settings/"),
}

fun openAiSpeechRemediation(code: String): OpenAiSpeechRemediation? = when (
    code.removePrefix("realtime_stt_").removePrefix("realtime_").removePrefix("live_")
) {
    "quota_exhausted", "insufficient_quota", "credit_balance_exhausted" -> OpenAiSpeechRemediation.BILLING
    "spending_limit_reached" -> OpenAiSpeechRemediation.LIMITS
    "project_spending_limit_reached" -> OpenAiSpeechRemediation.PROJECT_SETTINGS
    else -> null
}

/** Natural-language explanation for stable local/provider codes; raw API messages never enter. */
fun openAiSpeechFailureMessage(code: String, text: HansTextResolver): String = when {
    code in setOf("codex_transcription_auth_missing", "codex_transcription_auth_required") ->
        text.text(R.string.codex_live_sign_in_required)
    code == "codex_transcription_auth_changed" -> text.text(R.string.codex_live_session_not_ready)
    code in setOf("codex_transcription_forbidden", "codex_transcription_unavailable",
        "codex_transcription_unsupported_environment") -> text.text(R.string.codex_batch_transcription_unavailable)
    code == "codex_transcription_rate_limited" -> text.text(R.string.integration_speech_rate)
    code in setOf("codex_transcription_network_unavailable", "codex_transcription_timeout") ->
        text.text(R.string.integration_speech_network)
    code.startsWith("codex_transcription_") -> text.text(R.string.codex_batch_transcription_failed)
    code in setOf("codex_task_voice_audio_close_unconfirmed", "codex_task_voice_native_close_unconfirmed") ->
        text.text(R.string.codex_live_recovery_required)
    code in setOf("codex_task_voice_work_receipt_timeout", "codex_task_voice_work_receipt_invalid",
        "codex_task_voice_receipt_capacity") -> text.text(R.string.dictation_work_completion_unconfirmed)
    code.startsWith("codex_task_voice_") || code.startsWith("action_voice_") ->
        text.text(R.string.dictation_voice_finish_unconfirmed)
    code in setOf("codex_read_aloud_chatgpt_login_required", "codex_read_aloud_authentication") ->
        text.text(R.string.codex_live_sign_in_required)
    code == "codex_read_aloud_usage_limit" -> text.text(R.string.codex_live_usage_limit)
    code == "codex_read_aloud_not_available" -> text.text(R.string.codex_live_not_available)
    code.startsWith("codex_read_aloud_") -> text.text(R.string.codex_read_aloud_failed)
    code in setOf("codex_live_chatgpt_login_required", "codex_live_authentication") ->
        text.text(R.string.codex_live_sign_in_required)

    code == "codex_live_usage_limit" -> text.text(R.string.codex_live_usage_limit)

    code == "codex_live_not_available" -> text.text(R.string.codex_live_not_available)

    code == "codex_live_remote_access_active" -> text.text(R.string.codex_live_remote_access_active)

    code == "codex_live_recovery_required" -> text.text(R.string.codex_live_recovery_required)

    code in setOf("codex_live_session_not_ready", "codex_live_session_changed") ->
        text.text(R.string.codex_live_session_not_ready)

    code.startsWith("codex_live_dictation_") -> text.text(R.string.codex_dictation_unconfirmed)

    code.startsWith("codex_live_") -> text.text(R.string.codex_live_interrupted)

    code in setOf(
        "credential_unavailable",
        "realtime_standard_key_unavailable",
        "speech_credential_unavailable",
    ) -> text.text(R.string.integration_speech_missing_key)

    code.contains("authentication_failed") ||
        code.contains("credential_unauthorized") ||
        code.contains("credential_rejected") ->
        text.text(R.string.integration_speech_rejected_key)

    code.contains("permission_denied") ->
        text.text(R.string.integration_speech_permission)

    openAiSpeechRemediation(code) == OpenAiSpeechRemediation.LIMITS ->
        text.text(R.string.speech_failure_spending_limit)

    openAiSpeechRemediation(code) == OpenAiSpeechRemediation.PROJECT_SETTINGS ->
        text.text(R.string.speech_failure_project_limit)

    openAiSpeechRemediation(code) == OpenAiSpeechRemediation.BILLING ->
        text.text(R.string.speech_failure_api_credit)

    code.contains("rate_limited") || code.contains("rate_limit_exceeded") ->
        text.text(R.string.integration_speech_rate)

    code.contains("network") || code.contains("dns") || code.contains("timeout") ->
        text.text(R.string.integration_speech_network)

    code.contains("tls") ->
        text.text(R.string.integration_speech_tls)

    code.contains("server") || code.contains("service_unavailable") ->
        text.text(R.string.integration_speech_service)

    else -> text.text(R.string.integration_speech_interrupted)
}
