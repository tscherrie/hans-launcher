package ai.hans.standard.voice.feedback

import java.util.LinkedHashSet

data class SpeechServiceFailureSnapshot(
    val code: String? = null,
    val revision: Long = 0,
)

fun interface SpeechServiceFailureObserver {
    fun onFailure(snapshot: SpeechServiceFailureSnapshot)
}

/** Process-local, content-free bridge from background speech providers to the launcher UI. */
object HansSpeechFailureRuntime {
    private val observers = LinkedHashSet<SpeechServiceFailureObserver>()
    private var current = SpeechServiceFailureSnapshot()

    @Synchronized
    fun addObserver(observer: SpeechServiceFailureObserver) {
        observers += observer
    }

    @Synchronized
    fun removeObserver(observer: SpeechServiceFailureObserver) {
        observers -= observer
    }

    @Synchronized
    fun report(rawCode: String) {
        val safeCode = rawCode.takeIf { it.matches(Regex("[a-z0-9_]{1,128}")) }
            ?: "speech_service_failure"
        if (current.code == safeCode) return
        current = SpeechServiceFailureSnapshot(safeCode, current.revision + 1)
        observers.toList().forEach { runCatching { it.onFailure(current) } }
    }
}

/** Natural-language explanation for stable local/provider codes; raw API messages never enter. */
fun openAiSpeechFailureMessage(code: String): String = when {
    code in setOf(
        "credential_unavailable",
        "realtime_standard_key_unavailable",
        "speech_credential_unavailable",
    ) -> "Für Sprache ist noch kein OpenAI API-Schlüssel eingerichtet. Öffne Zugänge & Sicherung und richte den Sprachzugang ein."

    code.contains("authentication_failed") ||
        code.contains("credential_unauthorized") ||
        code.contains("credential_rejected") ->
        "OpenAI hat den API-Schlüssel abgelehnt. Er ist möglicherweise ungültig oder widerrufen. Ersetze ihn unter Zugänge & Sicherung."

    code.contains("permission_denied") ->
        "Der OpenAI API-Schlüssel hat für diese Sprachfunktion keine Berechtigung. Prüfe Projekt und Schlüsselrechte oder verwende einen anderen Schlüssel."

    code.contains("quota_exhausted") || code.contains("insufficient_quota") ->
        "Das API-Guthaben oder ein Ausgaben- beziehungsweise Nutzungslimit ist erreicht. Prüfe Guthaben und Limits in den OpenAI API-Einstellungen."

    code.contains("rate_limited") || code.contains("rate_limit_exceeded") ->
        "OpenAI erhält gerade zu viele Anfragen. Warte kurz und versuche die Sprachfunktion dann erneut."

    code.contains("network") || code.contains("dns") || code.contains("timeout") ->
        "Die Sprachfunktion erreicht OpenAI gerade nicht. Prüfe die Internetverbindung und versuche es erneut."

    code.contains("tls") ->
        "Die sichere Verbindung zum OpenAI-Sprachdienst konnte nicht aufgebaut werden. Prüfe Datum, Uhrzeit und Netzwerk des Telefons und versuche es erneut."

    code.contains("server") || code.contains("service_unavailable") ->
        "Der OpenAI-Sprachdienst ist vorübergehend nicht erreichbar. Versuche es in Kürze erneut."

    else -> "Die Sprachfunktion wurde unterbrochen. Prüfe Sprachzugang und Internetverbindung und versuche es erneut."
}
