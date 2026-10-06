package ai.hans.standard.diagnostics.memory

import ai.hans.standard.R
import ai.hans.standard.localization.HansTextResolver

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
    fun disclosure(text: HansTextResolver): String = text.text(R.string.integration_memory_disclosure)
    fun describe(result: NativeMemoryHealthResult, text: HansTextResolver): String = when (result) {
        is NativeMemoryHealthResult.Unavailable -> when (result.reason) {
            NativeMemoryUnavailableReason.CONFIGURATION_UNRESOLVED -> text.text(R.string.integration_memory_unconfirmed)
            NativeMemoryUnavailableReason.MISSING -> text.text(R.string.integration_memory_missing)
            NativeMemoryUnavailableReason.BUSY -> text.text(R.string.integration_memory_busy)
            NativeMemoryUnavailableReason.CORRUPT -> text.text(R.string.integration_memory_corrupt)
            NativeMemoryUnavailableReason.TOO_LARGE -> text.text(R.string.integration_memory_large)
            NativeMemoryUnavailableReason.UNSAFE_PATH -> text.text(R.string.integration_memory_path)
            NativeMemoryUnavailableReason.MAIN_THREAD, NativeMemoryUnavailableReason.IO -> text.text(R.string.integration_memory_unreachable)
        }
        is NativeMemoryHealthResult.Unsupported -> text.text(R.string.integration_memory_unsupported)
        is NativeMemoryHealthResult.Available -> with(result.snapshot) {
            buildString {
                append(text.text(R.string.integration_memory_counts, stage1Count, selectedCount, totalUsageCount))
                append(text.text(R.string.integration_memory_times, time(lastGeneratedAtSeconds, text), time(lastUsedAtSeconds, text)))
                jobs.forEach { job ->
                    val kind = if (job.kind == NativeMemoryJobKind.EXTRACTION) text.text(R.string.integration_memory_extraction) else text.text(R.string.integration_memory_consolidation)
                    val status = when(job.status) {
                        NativeMemoryJobStatus.PENDING -> text.text(R.string.integration_memory_pending)
                        NativeMemoryJobStatus.RUNNING -> text.text(R.string.integration_memory_running)
                        NativeMemoryJobStatus.DONE -> text.text(R.string.integration_memory_done)
                        NativeMemoryJobStatus.ERROR -> text.text(R.string.integration_memory_error)
                    }
                    append(text.text(R.string.integration_memory_job, kind, status, job.count))
                    append(text.text(R.string.integration_memory_job_times, time(job.lastStartedAtSeconds, text), time(job.lastFinishedAtSeconds, text)))
                }
            }
        }
    }
    private fun time(seconds: Long?, text: HansTextResolver): String = seconds?.let {
        runCatching { Instant.ofEpochSecond(it).toString() }.getOrElse { text.text(R.string.integration_memory_invalid_time) }
    } ?: text.text(R.string.integration_memory_no_evidence)
}
