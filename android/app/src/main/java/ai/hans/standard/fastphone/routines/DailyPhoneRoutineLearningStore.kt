package ai.hans.standard.fastphone.routines

import ai.hans.standard.codex.JsonContract
import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject

/** Private, opt-in daily consolidation. Published data is evidence, never executable authority. */
class DailyPhoneRoutineLearningStore internal constructor(
    private val storage: DailyPhoneRoutineStorage,
    private val observations: PhoneRoutineLearningStore,
    private val clock: DailyPhoneRoutineClock = SystemDailyPhoneRoutineClock,
    ownerProvider: () -> String? = { null },
) {
    constructor(context: Context, observations: PhoneRoutineLearningStore, ownerProvider: () -> String? = { null }) :
        this(AtomicDailyPhoneRoutineStorage(File(context.applicationContext.noBackupFilesDir, FILE_NAME)),
            observations, SystemDailyPhoneRoutineClock, ownerProvider)

    @Volatile private var ownerProvider = ownerProvider
    private val listeners = CopyOnWriteArrayList<(DailyPhoneRoutineLearningSnapshot) -> Unit>()
    private var healthy = true
    private var state = loadState()
    private var evidence = emptyList<PhoneRoutineAnalysisEvidence>()
    private var evidenceLoaded = false
    private var historyHealthy = true

    fun setOwnerProvider(provider: () -> String?) { synchronized(processLock) { reloadForMutation(); ownerProvider = provider; syncOwner() }; notifyListeners() }

    fun snapshot(): DailyPhoneRoutineLearningSnapshot = synchronized(processLock) {
        reloadForMutation(); syncOwner()
        snapshotLocked()
    }

    fun subscribe(listener: (DailyPhoneRoutineLearningSnapshot) -> Unit): AutoCloseable {
        listeners += listener
        runCatching { listener(snapshot()) }
        return AutoCloseable { listeners -= listener }
    }

    fun setEnabled(enabled: Boolean): Boolean {
        val result = synchronized(processLock) {
            reloadForMutation(); syncOwner()
            if (!healthy || enabled && state.owner == null) return@synchronized false
            if (state.enabled == enabled) return@synchronized true
            persist(state.copy(enabled = enabled, revision = nextRevision(), lease = null, errorCode = null))
        }
        notifyListeners(); return result
    }

    /** A durable authority fence precedes clearing observations, including recovery from corruption. */
    fun forget(): Boolean {
        val result = synchronized(processLock) {
            reloadForMutation()
            val owner = currentOwner()
            val wasEnabled = healthy && state.enabled && state.owner == owner && owner != null
            val reset = State(owner = owner, revision = nextRevision(), historyResetPending = true)
            if (!persist(reset)) return@synchronized false
            evidence = emptyList(); evidenceLoaded = true; historyHealthy = true; cachedWatermark = null
            if (!observations.clear()) {
                persist(state.copy(errorCode = "history_storage_failed")); return@synchronized false
            }
            persist(state.copy(enabled = wasEnabled, historyResetPending = false))
        }
        notifyListeners(); return result
    }

    /** Called only for helper completions; external task success is not independently established. */
    fun observeCompleted(observation: RoutineObservation): Boolean {
        val result = synchronized(processLock) {
            reloadForMutation(); syncOwner()
            if (!healthy || !state.enabled || state.owner == null) return@synchronized false
            if (!observations.observeCompleted(observation)) return@synchronized false
            refreshEvidence()
            syncOwner()
            healthy && state.enabled && state.owner != null
        }
        notifyListeners(); return result
    }

    /** Ordinary quoted context data. Empty/disabled payload explicitly revokes previous hints. */
    fun promptData(maximum: Int = 3, currentUserText: String? = null): String = synchronized(processLock) {
        reloadForMutation(); syncOwner()
        val candidates = if (healthy && state.enabled && state.owner != null && maximum in 1..3) {
            validGroups().filter { group -> currentUserText == null || matches(group.template, currentUserText) }
                .sortedWith(compareByDescending<ResolvedGroup> { it.count }.thenBy { it.template.id }).take(maximum)
        } else emptyList()
        JSONObject().put("version", 1).put("source", "phone_routines").put("revision", state.revision)
            .put("active", candidates.isNotEmpty()).put("authority", "none")
            .put("trust", "untrusted_private_learned_data")
            .put("completionMeaning", "Helper completion only, not verified external success. Current user authorization and fresh UI validation remain mandatory; never replay clicks.")
            .put("candidates", JSONArray(candidates.map { group -> JSONObject()
                .put("kind", "CANDIDATE_TEMPLATE").put("packageName", group.template.packageName)
                .put("quotedGoal", group.template.goal).put("allowedActions", JSONArray(group.template.allowedActions.sorted()))
                .put("maxSteps", group.maxSteps).put("helperCompletionCount", group.count)
            })).toString()
    }

    /** A persisted claim is required before any model call; unchanged input does not claim. */
    fun claim(): DailyPhoneRoutineClaim? {
        val result = synchronized(processLock) {
            reloadForMutation(); syncOwner(); refreshEvidence()
            if (!healthy || !historyHealthy || !state.enabled || state.owner == null) return@synchronized null
            val now = now(); val day = clock.localEpochDay()
            if (state.lease != null && state.lease!!.deadlineMillis > now) return@synchronized null
            if (state.lastSuccessDay != null && day <= state.lastSuccessDay!!) return@synchronized null
            if (state.attemptDay != null && day < state.attemptDay!!) return@synchronized null
            val attempts = if (state.attemptDay == day) state.attempts else 0
            if (attempts >= MAX_ATTEMPTS || state.attemptDay == day && state.nextAttemptAtMillis > now) return@synchronized null
            val input = observations.captureAnalysisInput() ?: return@synchronized null
            if (input.evidence.sumOf { it.helperCompletionCount.toLong() } < MIN_COMPLETIONS || input.watermark == state.lastInputWatermark) return@synchronized null
            val lease = Lease(UUID.randomUUID().toString(), state.revision, state.owner!!, input.watermark,
                addBounded(now, LEASE_MILLIS), day)
            if (!persist(state.copy(lease = lease, attemptDay = day, attempts = attempts + 1,
                    nextAttemptAtMillis = 0, errorCode = null))) return@synchronized null
            DailyPhoneRoutineClaim(lease.id, lease.revision, lease.owner, input)
        }
        notifyListeners(); return result
    }

    /** Guarded publication: model output can select supplied evidence, never invent instructions. */
    fun complete(claim: DailyPhoneRoutineClaim, outputJson: String,
        admitPublication: ((() -> Boolean) -> Boolean) = { commit -> commit() },
        shouldPublish: () -> Boolean = { true }): Boolean {
        val result = synchronized(processLock) {
            reloadForMutation(); syncOwner(); refreshEvidence()
            if (!owns(claim)) return@synchronized false
            val groups = runCatching { parseProposals(outputJson, claim.input) }.getOrElse {
                failLocked(claim, "invalid_analysis_output"); return@synchronized false
            }
            val current = evidence.associateBy { it.id }
            if (groups.any { group -> group.evidenceIds.any { id -> id !in current } }) {
                failLocked(claim, "history_changed"); return@synchronized false
            }
            // If a current budget shrank during analysis, never publish the older larger budget.
            val bounded = groups.map { group -> group.copy(maxSteps = minOf(group.maxSteps,
                group.evidenceIds.minOf { current.getValue(it).maxSteps })) }
            admitPublication {
                if (!runCatching(shouldPublish).getOrDefault(false)) {
                    failLocked(claim, "preempted"); false
                } else persist(state.copy(groups = bounded, lease = null, lastSuccessDay = clock.localEpochDay(),
                    lastSuccessAtMillis = now(), lastInputWatermark = claim.input.watermark,
                    nextAttemptAtMillis = 0, errorCode = null))
            }
        }
        notifyListeners(); return result
    }

    fun fail(claim: DailyPhoneRoutineClaim, errorCode: String): Boolean {
        val result = synchronized(processLock) { reloadForMutation(); syncOwner(); failLocked(claim, errorCode) }
        notifyListeners(); return result
    }

    private fun failLocked(claim: DailyPhoneRoutineClaim, code: String): Boolean {
        if (!owns(claim)) return false
        val delay = if (state.attempts <= 1) FIRST_BACKOFF_MILLIS else SECOND_BACKOFF_MILLIS
        return persist(state.copy(lease = null, errorCode = if (code in SAFE_ERRORS) code else "analysis_failed",
            nextAttemptAtMillis = if (state.attempts >= MAX_ATTEMPTS) clock.nextLocalDayStartMillis() else addBounded(now(), delay)))
    }

    private fun owns(claim: DailyPhoneRoutineClaim): Boolean {
        val lease = state.lease ?: return false
        return healthy && !state.historyResetPending && state.enabled && state.owner == claim.expectedAccountFingerprint &&
            state.revision == claim.revision && lease.id == claim.runId && lease.revision == claim.revision &&
            lease.owner == claim.expectedAccountFingerprint && lease.watermark == claim.input.watermark &&
            lease.deadlineMillis > now()
    }

    private fun snapshotLocked(): DailyPhoneRoutineLearningSnapshot {
        val now = now(); val day = clock.localEpochDay()
        val count = if (healthy && state.enabled) validGroups().size else 0
        val status: DailyPhoneRoutineLearningStatus
        val due: Long?
        when {
            !healthy || !historyHealthy -> { status = DailyPhoneRoutineLearningStatus.ERROR; due = null }
            state.owner == null -> { status = if (state.enabled) DailyPhoneRoutineLearningStatus.ACCOUNT_UNAVAILABLE else DailyPhoneRoutineLearningStatus.DISABLED; due = null }
            !state.enabled -> { status = DailyPhoneRoutineLearningStatus.DISABLED; due = null }
            state.lease != null && state.lease!!.deadlineMillis > now -> { status = DailyPhoneRoutineLearningStatus.RUNNING; due = state.lease!!.deadlineMillis }
            state.lastSuccessDay != null && day <= state.lastSuccessDay!! -> { status = DailyPhoneRoutineLearningStatus.ALREADY_ANALYZED_TODAY; due = clock.nextLocalDayStartMillis() }
            state.attemptDay != null && day < state.attemptDay!! -> {
                status = DailyPhoneRoutineLearningStatus.BACKOFF; due = clock.nextLocalDayStartMillis()
            }
            state.attemptDay == day && (state.attempts >= MAX_ATTEMPTS || state.nextAttemptAtMillis > now) -> {
                status = DailyPhoneRoutineLearningStatus.BACKOFF
                due = if (state.attempts >= MAX_ATTEMPTS) clock.nextLocalDayStartMillis() else state.nextAttemptAtMillis
            }
            evidence.sumOf { it.helperCompletionCount.toLong() } < MIN_COMPLETIONS -> {
                status = DailyPhoneRoutineLearningStatus.WAITING_FOR_HISTORY; due = clock.nextLocalDayStartMillis()
            }
            currentWatermark() == state.lastInputWatermark -> {
                status = DailyPhoneRoutineLearningStatus.NO_NEW_HISTORY; due = clock.nextLocalDayStartMillis()
            }
            else -> { status = DailyPhoneRoutineLearningStatus.READY; due = now }
        }
        return DailyPhoneRoutineLearningSnapshot(state.enabled && healthy, status, state.lastSuccessAtMillis,
            count, if (!healthy) "learning_storage_invalid" else if (!historyHealthy) "history_storage_failed" else state.errorCode, due, state.revision)
    }

    private var cachedWatermark: String? = null
    private fun currentWatermark() = cachedWatermark
    private fun refreshEvidence() {
        val input = observations.captureAnalysisInput()
        val all = observations.currentAnalysisEvidence()
        evidenceLoaded = true
        historyHealthy = all != null
        if (all == null) { evidence = emptyList(); cachedWatermark = null; return }
        evidence = all
        cachedWatermark = input?.watermark
    }

    private fun validGroups(): List<ResolvedGroup> {
        val byId = evidence.associateBy { it.id }
        return state.groups.mapNotNull { group ->
            val refs = group.evidenceIds.map { byId[it] ?: return@mapNotNull null }
            val template = byId[group.templateEvidenceId] ?: return@mapNotNull null
            if (refs.any { it.packageName != template.packageName || it.allowedActions != template.allowedActions }) return@mapNotNull null
            val currentCount = refs.sumOf { it.helperCompletionCount.toLong() }
            if (currentCount < MIN_COMPLETIONS) return@mapNotNull null
            ResolvedGroup(template, minOf(group.maxSteps, refs.minOf { it.maxSteps }), minOf(group.completionCount.toLong(), currentCount).toInt())
        }
    }

    /** First binding discards legacy accountless observations; account loss/change also revokes opt-in. */
    private fun syncOwner() {
        if (!healthy) return
        val owner = currentOwner()
        if (state.owner == owner) {
            if (state.historyResetPending) {
                if (!observations.clear()) { healthy = false; return }
                evidence = emptyList(); cachedWatermark = null; evidenceLoaded = true; historyHealthy = true
                if (!persist(state.copy(historyResetPending = false))) return
            }
            if (state.enabled && owner != null && !evidenceLoaded) refreshEvidence()
            return
        }
        val reset = State(owner = owner, revision = nextRevision(), historyResetPending = true)
        if (!persist(reset)) return
        evidence = emptyList(); cachedWatermark = null; evidenceLoaded = true; historyHealthy = true
        if (!observations.clear()) {
            persist(state.copy(errorCode = "history_storage_failed")); healthy = false
        } else persist(state.copy(historyResetPending = false))
    }

    private fun currentOwner(): String? = runCatching { ownerProvider()?.takeIf { it.matches(DIGEST) } }.getOrNull()
    private fun nextRevision() = if (state.revision == Long.MAX_VALUE) Long.MAX_VALUE else state.revision + 1
    private fun now() = clock.nowMillis().coerceAtLeast(0)

    private fun persist(value: State): Boolean = runCatching {
        val bytes = encode(value)
        require(bytes.size <= MAX_BYTES)
        storage.writeAtomically(bytes)
        check(storage.read()?.contentEquals(bytes) == true)
        state = value; healthy = true
        true
    }.getOrElse { healthy = false; evidence = emptyList(); cachedWatermark = null; false }

    private fun loadState(): State = runCatching {
        val result = storage.read()?.let { decode(it) } ?: State()
        healthy = true
        result
    }.getOrElse { healthy = false; State(errorCode = "learning_storage_invalid") }

    private fun reloadForMutation() {
        val previous = state
        state = loadState()
        if (previous.owner != state.owner || previous.revision != state.revision || previous.groups != state.groups ||
            previous.lastInputWatermark != state.lastInputWatermark) {
            evidenceLoaded = false; evidence = emptyList(); cachedWatermark = null
        }
    }

    private fun notifyListeners() {
        if (listeners.isEmpty()) return
        val value = snapshot()
        listeners.forEach { listener -> runCatching { listener(value) } }
    }

    private fun parseProposals(raw: String, input: PhoneRoutineAnalysisInput): List<Group> {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val json = JsonContract.parseObject(raw, MAX_BYTES)
        only(json, setOf("version", "groups")); require(integer(json, "version", 1, 1) == 1L)
        val groups = json.getJSONArray("groups"); require(groups.length() <= MAX_GROUPS)
        val available = input.evidence.associateBy { it.id }
        val seen = mutableSetOf<String>()
        return (0 until groups.length()).map { index ->
            val proposal = groups.getJSONObject(index)
            only(proposal, setOf("evidenceIds", "templateEvidenceId"))
            val ids = ids(proposal.getJSONArray("evidenceIds"))
            require(ids.all { seen.add(it) } && seen.size <= MAX_EVIDENCE)
            val templateId = proposal.get("templateEvidenceId") as? String ?: error("invalid_template")
            require(templateId in ids)
            val refs = ids.map { available[it] ?: error("unknown_evidence") }
            val template = available.getValue(templateId)
            require(refs.all { it.packageName == template.packageName && it.allowedActions == template.allowedActions })
            val count = refs.sumOf { it.helperCompletionCount.toLong() }
            require(count >= MIN_COMPLETIONS)
            Group(ids, templateId, refs.minOf { it.maxSteps }, count.coerceAtMost(MAX_COMPLETION_COUNT).toInt())
        }
    }

    private fun encode(value: State): ByteArray = JSONObject().put("version", 1).put("enabled", value.enabled)
        .put("revision", value.revision).put("owner", value.owner ?: JSONObject.NULL).put("historyResetPending", value.historyResetPending)
        .put("lastSuccessDay", value.lastSuccessDay ?: JSONObject.NULL).put("lastSuccessAtMillis", value.lastSuccessAtMillis ?: JSONObject.NULL)
        .put("lastInputWatermark", value.lastInputWatermark ?: JSONObject.NULL)
        .put("attemptDay", value.attemptDay ?: JSONObject.NULL).put("attempts", value.attempts)
        .put("nextAttemptAtMillis", value.nextAttemptAtMillis).put("errorCode", value.errorCode ?: JSONObject.NULL)
        .put("lease", value.lease?.let { lease -> JSONObject().put("id", lease.id).put("revision", lease.revision)
            .put("owner", lease.owner).put("watermark", lease.watermark).put("deadlineMillis", lease.deadlineMillis).put("day", lease.day) } ?: JSONObject.NULL)
        .put("groups", JSONArray(value.groups.map { group -> JSONObject().put("evidenceIds", JSONArray(group.evidenceIds))
            .put("templateEvidenceId", group.templateEvidenceId).put("maxSteps", group.maxSteps).put("completionCount", group.completionCount) }))
        .toString().toByteArray(Charsets.UTF_8)

    private fun decode(bytes: ByteArray): State {
        require(bytes.size <= MAX_BYTES)
        val json = JsonContract.parseObject(bytes.toString(Charsets.UTF_8), MAX_BYTES)
        only(json, setOf("version", "enabled", "revision", "owner", "historyResetPending", "lastSuccessDay", "lastSuccessAtMillis", "lastInputWatermark",
            "attemptDay", "attempts", "nextAttemptAtMillis", "errorCode", "lease", "groups"))
        require(integer(json, "version", 1, 1) == 1L)
        val enabled = json.get("enabled") as? Boolean ?: error("invalid_enabled")
        val revision = integer(json, "revision", 0, Long.MAX_VALUE)
        val owner = nullableString(json, "owner")?.also { require(it.matches(DIGEST)) }
        val resetPending = json.get("historyResetPending") as? Boolean ?: error("invalid_reset")
        val successDay = nullableInteger(json, "lastSuccessDay")
        val successTime = nullableInteger(json, "lastSuccessAtMillis")?.also { require(it >= 0) }
        val watermark = nullableString(json, "lastInputWatermark")?.also { require(it.matches(DIGEST)) }
        require((successDay == null) == (successTime == null) && (successDay == null) == (watermark == null))
        val attemptDay = nullableInteger(json, "attemptDay")
        val attempts = integer(json, "attempts", 0, MAX_ATTEMPTS.toLong()).toInt()
        require(attemptDay != null || attempts == 0)
        val next = integer(json, "nextAttemptAtMillis", 0, Long.MAX_VALUE)
        val error = nullableString(json, "errorCode")?.also { require(it in SAFE_ERRORS) }
        val lease = if (json.isNull("lease")) null else json.getJSONObject("lease").let { item ->
            only(item, setOf("id", "revision", "owner", "watermark", "deadlineMillis", "day"))
            val id = item.get("id") as? String ?: error("invalid_lease")
            require(id.matches(UUID_PATTERN))
            Lease(id, integer(item, "revision", 0, Long.MAX_VALUE), item.getString("owner"), item.getString("watermark"),
                integer(item, "deadlineMillis", 0, Long.MAX_VALUE), integer(item, "day", Long.MIN_VALUE, Long.MAX_VALUE))
                .also { require(it.owner.matches(DIGEST) && it.watermark.matches(DIGEST) && it.owner == owner && it.revision == revision && enabled && attempts > 0) }
        }
        val rawGroups = json.getJSONArray("groups"); require(rawGroups.length() <= MAX_GROUPS)
        val seen = mutableSetOf<String>()
        val groups = (0 until rawGroups.length()).map { index ->
            val group = rawGroups.getJSONObject(index)
            only(group, setOf("evidenceIds", "templateEvidenceId", "maxSteps", "completionCount"))
            val ids = ids(group.getJSONArray("evidenceIds")); require(ids.all { seen.add(it) } && seen.size <= MAX_EVIDENCE)
            val template = group.get("templateEvidenceId") as? String ?: error("invalid_template")
            require(template in ids)
            Group(ids, template, integer(group, "maxSteps", 1, 8).toInt(), integer(group, "completionCount", MIN_COMPLETIONS, MAX_COMPLETION_COUNT).toInt())
        }
        require(owner != null || !enabled && lease == null && groups.isEmpty())
        require(!resetPending || !enabled && lease == null && groups.isEmpty())
        require(groups.isEmpty() || successDay != null)
        return State(enabled, revision, owner, successDay, successTime, watermark, attemptDay, attempts, next, lease, groups, error, resetPending)
    }

    private fun ids(array: JSONArray): List<String> {
        require(array.length() in 1..MAX_EVIDENCE)
        val result = (0 until array.length()).map { (array.get(it) as? String ?: error("invalid_id")).also { id -> require(id.matches(DIGEST)) } }
        require(result.toSet().size == result.size); return result
    }
    private fun only(json: JSONObject, keys: Set<String>) = require(json.keys().asSequence().toSet() == keys)
    private fun integer(json: JSONObject, key: String, min: Long, max: Long): Long {
        val value = json.get(key); require(value is Int || value is Long)
        return (value as Number).toLong().also { require(it in min..max) }
    }
    private fun nullableInteger(json: JSONObject, key: String): Long? = if (json.isNull(key)) null else integer(json, key, Long.MIN_VALUE, Long.MAX_VALUE)
    private fun nullableString(json: JSONObject, key: String): String? = if (json.isNull(key)) null else json.get(key) as? String ?: error("invalid_string")
    private fun matches(template: PhoneRoutineAnalysisEvidence, query: String): Boolean {
        if (query.length !in 1..16_384) return false
        val normalized = Normalizer.normalize(query, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        if (normalized.contains(template.packageName.lowercase(Locale.ROOT))) return true
        val goalTokens = TOKEN.findAll(template.goal).map { it.value }.filterNot { it in STOP_WORDS }.toSet()
        return TOKEN.findAll(normalized).map { it.value }.any { it in goalTokens && it !in STOP_WORDS }
    }
    private fun addBounded(value: Long, amount: Long) = if (Long.MAX_VALUE - value < amount) Long.MAX_VALUE else value + amount
    private data class Group(val evidenceIds: List<String>, val templateEvidenceId: String, val maxSteps: Int, val completionCount: Int)
    private data class ResolvedGroup(val template: PhoneRoutineAnalysisEvidence, val maxSteps: Int, val count: Int)
    private data class Lease(val id: String, val revision: Long, val owner: String, val watermark: String, val deadlineMillis: Long, val day: Long)
    private data class State(val enabled: Boolean = false, val revision: Long = 0, val owner: String? = null,
        val lastSuccessDay: Long? = null, val lastSuccessAtMillis: Long? = null, val lastInputWatermark: String? = null,
        val attemptDay: Long? = null, val attempts: Int = 0, val nextAttemptAtMillis: Long = 0,
        val lease: Lease? = null, val groups: List<Group> = emptyList(), val errorCode: String? = null,
        val historyResetPending: Boolean = false)

    companion object {
        internal const val FILE_NAME = "hans-daily-phone-routines-v1.json"
        internal const val MAX_BYTES = 32 * 1_024
        internal const val LEASE_MILLIS = 120_000L
        private const val MAX_ATTEMPTS = 3
        private const val MAX_GROUPS = 8
        private const val MAX_EVIDENCE = 64
        private const val MIN_COMPLETIONS = 3L
        private const val MAX_COMPLETION_COUNT = 64_000_000L
        private const val FIRST_BACKOFF_MILLIS = 15 * 60_000L
        private const val SECOND_BACKOFF_MILLIS = 60 * 60_000L
        private val DIGEST = Regex("[0-9a-f]{64}")
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val TOKEN = Regex("[\\p{L}\\p{N}]{3,}")
        private val STOP_WORDS = setOf("the", "and", "for", "with", "please", "open", "start", "show", "this", "that", "app", "apps",
            "und", "der", "die", "das", "den", "dem", "ein", "eine", "einen", "bitte", "öffne", "öffnen", "starte", "starten", "zeige", "anzeigen", "mit", "für", "jetzt", "mir")
        private val SAFE_ERRORS = setOf("analysis_failed", "invalid_analysis_output", "history_changed", "preempted", "account_changed",
            "history_storage_failed", "learning_storage_invalid")
        private val processLock = Any()
    }
}

internal interface DailyPhoneRoutineStorage {
    fun read(): ByteArray?
    fun writeAtomically(bytes: ByteArray)
}

internal class AtomicDailyPhoneRoutineStorage(private val file: File) : DailyPhoneRoutineStorage {
    private val atomic = AtomicFile(file)
    override fun read(): ByteArray? {
        val input = try { atomic.openRead() } catch (failure: FileNotFoundException) {
            if (file.exists() || File(file.path + ".bak").exists()) throw failure
            return null
        }
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4_096)
            while (output.size() <= DailyPhoneRoutineLearningStore.MAX_BYTES) {
                val count = stream.read(buffer, 0, minOf(buffer.size, DailyPhoneRoutineLearningStore.MAX_BYTES + 1 - output.size()))
                if (count < 0) break
                if (count > 0) output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
    override fun writeAtomically(bytes: ByteArray) {
        require(bytes.size <= DailyPhoneRoutineLearningStore.MAX_BYTES)
        val output = atomic.startWrite()
        try { output.write(bytes); output.flush(); output.fd.sync(); atomic.finishWrite(output) }
        catch (failure: Exception) { atomic.failWrite(output); throw failure }
    }
}
