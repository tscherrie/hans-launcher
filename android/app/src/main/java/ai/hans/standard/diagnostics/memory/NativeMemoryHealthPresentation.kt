package ai.hans.standard.diagnostics.memory

import ai.hans.standard.notifications.isStrictNotificationJsonObject
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** Only the correlated, validated config/read response is admitted by the supervisor. */
internal object NativeMemorySqliteHome {
    fun resolve(configResponse: String, codexHome: String, environmentOverride: String?, cwd: String): File? = runCatching {
        val config = JSONObject(configResponse).getJSONObject("result").getJSONObject("config")
        val value = config.opt("sqlite_home")
        when {
            value != null && value !== JSONObject.NULL -> {
                require(value is String && value.isNotBlank())
                File(value).also { require(it.isAbsolute) }
            }
            !environmentOverride.isNullOrBlank() -> File(environmentOverride.trim()).let {
                if (it.isAbsolute) it else File(cwd, it.path)
            }
            else -> File(codexHome).also { require(it.isAbsolute) }
        }
    }.getOrNull()
}

/** Small internal Binder wire format: fixed fields only, no paths, identifiers or error messages. */
internal object NativeMemoryHealthWire {
    const val MAX_BYTES = 8192
    fun encode(result: NativeMemoryHealthResult, generation: Long = 0L): String = JSONObject().apply {
        require(generation >= 0)
        put("schema", 1)
        put("generation", generation)
        when (result) {
            is NativeMemoryHealthResult.Unavailable -> { put("status", "unavailable"); put("reason", result.reason.name) }
            is NativeMemoryHealthResult.Unsupported -> { put("status", "unsupported"); put("reason", result.reason.name) }
            is NativeMemoryHealthResult.Available -> {
                put("status", "available")
                val snapshot = result.snapshot
                put("count", snapshot.stage1Count); put("selected", snapshot.selectedCount); put("uses", snapshot.totalUsageCount)
                put("generated", snapshot.lastGeneratedAtSeconds ?: JSONObject.NULL)
                put("used", snapshot.lastUsedAtSeconds ?: JSONObject.NULL)
                put("jobs", JSONArray().apply { snapshot.jobs.forEach { job ->
                    put(JSONObject().apply {
                        put("kind", job.kind.name); put("status", job.status.name); put("count", job.count)
                        put("started", job.lastStartedAtSeconds ?: JSONObject.NULL)
                        put("finished", job.lastFinishedAtSeconds ?: JSONObject.NULL)
                        put("lease", job.latestLeaseUntilSeconds ?: JSONObject.NULL)
                        put("retry", job.latestRetryAtSeconds ?: JSONObject.NULL)
                    })
                } })
            }
        }
    }.toString().also { require(it.toByteArray().size <= MAX_BYTES) }

    fun decode(text: String): NativeMemoryHealthResult {
        require(text.toByteArray().size <= MAX_BYTES && isStrictNotificationJsonObject(text))
        val json = JSONObject(text)
        require(number(json, "schema") == 1L)
        number(json, "generation")
        return when (json.get("status")) {
            "unavailable" -> { keys(json, "schema", "generation", "status", "reason"); NativeMemoryHealthResult.Unavailable(NativeMemoryUnavailableReason.valueOf(json.getString("reason"))) }
            "unsupported" -> { keys(json, "schema", "generation", "status", "reason"); NativeMemoryHealthResult.Unsupported(NativeMemoryUnsupportedReason.valueOf(json.getString("reason"))) }
            "available" -> {
                require(number(json, "generation") > 0)
                keys(json, "schema", "generation", "status", "count", "selected", "uses", "generated", "used", "jobs")
                val jobs = json.getJSONArray("jobs")
                require(jobs.length() <= 8)
                NativeMemoryHealthResult.Available(NativeMemoryHealthSnapshot(number(json,"count"), number(json,"selected"),
                    number(json,"uses"), optional(json,"generated"), optional(json,"used"),
                    (0 until jobs.length()).map { index -> jobs.getJSONObject(index).let { job ->
                        keys(job,"kind","status","count","started","finished","lease","retry")
                        NativeMemoryJobAggregate(NativeMemoryJobKind.valueOf(job.getString("kind")), NativeMemoryJobStatus.valueOf(job.getString("status")),
                            number(job,"count"),optional(job,"started"),optional(job,"finished"),optional(job,"lease"),optional(job,"retry"))
                    } }))
            }
            else -> throw IllegalArgumentException("unsupported health status")
        }
    }
    private fun keys(json: JSONObject, vararg names: String) { require(json.keys().asSequence().toSet() == names.toSet()) }
    private fun number(json: JSONObject, key: String): Long {
        val value = json.get(key)
        require(value is Int || value is Long)
        return (value as Number).toLong().also { require(it >= 0) }
    }
    private fun optional(json: JSONObject, key: String): Long? = if (json.get(key) === JSONObject.NULL) null else number(json,key)
}

internal object NativeMemoryHealthPresentation {
    const val DISCLOSURE = "Nur Statusdaten des nativen Codex-Gedächtnisses, keine Erinnerungsinhalte. Erzeugte Einträge oder registrierte Nutzungen beweisen nicht, dass jede Antwort Erinnerungen verwendet. Das Benachrichtigungsarchiv ist davon getrennt."
    fun describe(result: NativeMemoryHealthResult): String = when (result) {
        is NativeMemoryHealthResult.Unavailable -> when (result.reason) {
            NativeMemoryUnavailableReason.CONFIGURATION_UNRESOLVED -> "Codex ist noch nicht bereit oder der Speicherort ist noch nicht bestätigt. Bitte später aktualisieren."
            NativeMemoryUnavailableReason.MISSING -> "Noch keine Gedächtnisdatenbank vorhanden. Das allein bedeutet nicht, dass das Gedächtnis deaktiviert ist."
            NativeMemoryUnavailableReason.BUSY -> "Der Gedächtnisstatus ist gerade beschäftigt. Bitte später aktualisieren."
            NativeMemoryUnavailableReason.CORRUPT -> "Die Gedächtnisdatenbank konnte nicht sicher gelesen werden. Es wurde nichts repariert oder gelöscht."
            NativeMemoryUnavailableReason.TOO_LARGE -> "Die Statusprüfung überschreitet ihre sichere Größenbegrenzung."
            NativeMemoryUnavailableReason.UNSAFE_PATH -> "Der bestätigte Speicherort konnte nicht sicher geprüft werden."
            NativeMemoryUnavailableReason.MAIN_THREAD, NativeMemoryUnavailableReason.IO -> "Der Gedächtnisstatus ist derzeit nicht erreichbar. Bitte später aktualisieren."
        }
        is NativeMemoryHealthResult.Unsupported -> "Für diese Codex-Version oder dieses Datenformat ist noch keine sichere Statusprüfung verfügbar."
        is NativeMemoryHealthResult.Available -> with(result.snapshot) {
            buildString {
                append("Extrahierte Einträge: $stage1Count\nFür Konsolidierung ausgewählt: $selectedCount\nRegistrierte Nutzungen: $totalUsageCount")
                append("\nZuletzt erzeugt: ${time(lastGeneratedAtSeconds)}\nZuletzt genutzt: ${time(lastUsedAtSeconds)}")
                jobs.forEach { job ->
                    append("\n\n${if (job.kind == NativeMemoryJobKind.EXTRACTION) "Extraktion" else "Konsolidierung"}: ${when(job.status) { NativeMemoryJobStatus.PENDING -> "wartend"; NativeMemoryJobStatus.RUNNING -> "laufend"; NativeMemoryJobStatus.DONE -> "abgeschlossen"; NativeMemoryJobStatus.ERROR -> "Fehler" }} (${job.count})")
                    append("\nLetzter Start: ${time(job.lastStartedAtSeconds)}\nLetzter Abschluss: ${time(job.lastFinishedAtSeconds)}")
                }
            }
        }
    }
    private fun time(seconds: Long?): String = seconds?.let { runCatching { Instant.ofEpochSecond(it).toString() }.getOrDefault("Zeitwert nicht darstellbar") } ?: "noch nicht belegt"
}
