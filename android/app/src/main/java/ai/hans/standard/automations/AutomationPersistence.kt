package ai.hans.standard.automations

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import org.json.JSONArray
import org.json.JSONObject

data class AutomationPersistenceLimits(
    val maximumDefinitions: Int = 256,
    val maximumInboxItems: Int = 4_096,
    val maximumRunHistory: Int = 4_096,
    val maximumRunReceipts: Int = 16_384,
    val maximumConfirmations: Int = 512,
    val maximumManualInvocations: Int = 16_384,
    val maximumLeases: Int = 256,
    val maximumSnapshotBytes: Int = 16 * 1024 * 1024,
) {
    init {
        require(maximumDefinitions in 1..10_000)
        require(maximumInboxItems in 1..100_000)
        require(maximumRunHistory in 1..100_000)
        require(maximumRunReceipts in 1..500_000)
        require(maximumConfirmations in 1..10_000)
        require(maximumManualInvocations in 1..500_000)
        require(maximumLeases in 1..10_000)
        require(maximumSnapshotBytes in 64 * 1024..64 * 1024 * 1024)
    }
}

class AutomationPersistenceException internal constructor(
    val errorCode: String,
) : IllegalStateException(errorCode)

interface AutomationSnapshotPersistence {
    /** Null means no database has been created yet. */
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
}

/** App-private, no-backup, crash-safe persistence using Android's public AtomicFile API. */
class AtomicFileAutomationSnapshotPersistence(
    context: Context,
    fileName: String = DEFAULT_FILE_NAME,
) : AutomationSnapshotPersistence {
    private val atomicFile: AtomicFile

    init {
        require(fileName.matches(SAFE_FILE_NAME)) { "Invalid automation database file name" }
        atomicFile = AtomicFile(File(context.applicationContext.noBackupFilesDir, fileName))
    }

    @Synchronized
    override fun read(): ByteArray? {
        if (!atomicFile.baseFile.exists()) return null
        return runCatching { atomicFile.readFully() }
            .getOrElse { throw AutomationPersistenceException("automation_storage_read_failed") }
    }

    @Synchronized
    override fun write(bytes: ByteArray) {
        val output = runCatching { atomicFile.startWrite() }
            .getOrElse { throw AutomationPersistenceException("automation_storage_write_failed") }
        try {
            output.write(bytes)
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (_: Exception) {
            atomicFile.failWrite(output)
            throw AutomationPersistenceException("automation_storage_write_failed")
        }
    }

    companion object {
        const val DEFAULT_FILE_NAME = "hans_automations_v1.json"
        private val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{2,95}")
    }
}

/**
 * Transactional persistent implementation of [AutomationStorage]. Each mutation is first applied
 * to an isolated candidate, bounded and encoded, then atomically committed. A failed disk write
 * never publishes the candidate to readers or returns a lease to an executor.
 */
class PersistentAutomationStorage(
    private val persistence: AutomationSnapshotPersistence,
    private val limits: AutomationPersistenceLimits = AutomationPersistenceLimits(),
) : AutomationStorage {
    private var delegate: InMemoryAutomationStorage = load()

    @Synchronized
    override fun snapshot(): AutomationStorageSnapshot = delegate.snapshot()

    @Synchronized
    override fun replaceSnapshotForRestore(snapshot: AutomationStorageSnapshot) {
        AutomationBackupRestoreSafety.requireSafeReplacement(delegate.snapshot(), snapshot)
        val bounded = bound(InMemoryAutomationStorage(snapshot).snapshot())
        val bytes = AutomationSnapshotJsonCodec.encode(bounded).toByteArray(Charsets.UTF_8)
        if (bytes.size > limits.maximumSnapshotBytes) {
            throw AutomationPersistenceException("automation_snapshot_capacity_exceeded")
        }
        persistence.write(bytes)
        delegate = InMemoryAutomationStorage(bounded)
    }

    @Synchronized
    override fun compareAndReplaceSnapshotForRestore(
        expected: AutomationStorageSnapshot,
        replacement: AutomationStorageSnapshot,
    ): Boolean {
        if (delegate.snapshot() != expected) return false
        replaceSnapshotForRestore(replacement)
        return true
    }

    @Synchronized
    override fun upsertDefinition(definition: AutomationDefinition): DefinitionWriteResult =
        mutate { it.upsertDefinition(definition) }

    @Synchronized
    override fun removeDefinition(id: AutomationId, expectedRevision: Long): Boolean =
        mutate { it.removeDefinition(id, expectedRevision) }

    @Synchronized
    override fun definitions(): List<AutomationDefinition> = delegate.definitions()

    @Synchronized
    override fun definition(id: AutomationId): AutomationDefinition? = delegate.definition(id)

    @Synchronized
    override fun enqueueManualRun(
        id: AutomationId,
        expectedRevision: Long,
        requestId: AutomationManualRequestId,
        requestedAt: Instant,
    ): AutomationManualEnqueueResult = mutate {
        it.enqueueManualRun(id, expectedRevision, requestId, requestedAt)
    }

    @Synchronized
    override fun recordConfirmation(
        receipt: AutomationRunConfirmationReceipt,
        now: Instant,
    ): AutomationConfirmationWriteResult = mutate { it.recordConfirmation(receipt, now) }

    @Synchronized
    override fun confirmedRunKeys(now: Instant): Set<AutomationRunKey> = mutate {
        it.confirmedRunKeys(now)
    }

    @Synchronized
    override fun cancelDefinition(
        id: AutomationId,
        expectedRevision: Long,
        now: Instant,
    ): AutomationCancellationResult = mutate { it.cancelDefinition(id, expectedRevision, now) }

    @Synchronized
    override fun recordDiscovery(batch: AutomationDiscoveryBatch): AutomationDiscoveryResult =
        mutate { it.recordDiscovery(batch) }

    @Synchronized
    override fun schedulerCursor(id: AutomationId): AutomationSchedulerCursor? =
        delegate.schedulerCursor(id)

    @Synchronized
    override fun resetSchedulerCursors(ids: Set<AutomationId>, evaluatedThrough: Instant?) {
        mutate { candidate -> candidate.resetSchedulerCursors(ids, evaluatedThrough) }
    }

    @Synchronized
    override fun materializeDueInbox(
        now: Instant,
        maximumItems: Int,
    ): AutomationMaterializationResult = mutate { it.materializeDueInbox(now, maximumItems) }

    @Synchronized
    override fun releaseDeferredRuns(
        resolved: Set<AutomationResolvedPrecondition>,
        now: Instant,
    ): Int = mutate { it.releaseDeferredRuns(resolved, now) }

    @Synchronized
    override fun acquireNextLease(
        owner: AutomationWorkerId,
        token: AutomationLeaseToken,
        bootSessionId: AutomationBootSessionId,
        now: Instant,
        leaseDuration: Duration,
    ): AutomationLeaseClaim? = mutate {
        it.acquireNextLease(owner, token, bootSessionId, now, leaseDuration)
    }

    @Synchronized
    override fun heartbeat(
        key: AutomationRunKey,
        token: AutomationLeaseToken,
        now: Instant,
        leaseDuration: Duration,
    ): AutomationHeartbeatResult = mutate { it.heartbeat(key, token, now, leaseDuration) }

    @Synchronized
    override fun markDispatchFence(
        key: AutomationRunKey,
        token: AutomationLeaseToken,
        now: Instant,
        leaseDuration: Duration,
    ): AutomationDispatchFenceMarkResult = mutate {
        it.markDispatchFence(key, token, now, leaseDuration)
    }

    @Synchronized
    override fun clearDispatchFenceAfterRejected(
        key: AutomationRunKey,
        token: AutomationLeaseToken,
        now: Instant,
    ): AutomationDispatchFenceClearResult = mutate {
        it.clearDispatchFenceAfterRejected(key, token, now)
    }

    @Synchronized
    override fun completeLease(
        key: AutomationRunKey,
        token: AutomationLeaseToken,
        now: Instant,
        completion: AutomationCompletion,
    ): AutomationCompletionResult = mutate { it.completeLease(key, token, now, completion) }

    @Synchronized
    override fun recoverLeases(
        now: Instant,
        currentBootSessionId: AutomationBootSessionId,
        forceSameBootRecovery: Boolean,
    ): AutomationLeaseRecoveryResult = mutate {
        it.recoverLeases(now, currentBootSessionId, forceSameBootRecovery)
    }

    @Synchronized
    override fun recoveryState(): AutomationRecoveryState? = delegate.recoveryState()

    @Synchronized
    override fun storeRecoveryState(state: AutomationRecoveryState) {
        mutate { candidate -> candidate.storeRecoveryState(state) }
    }

    private fun load(): InMemoryAutomationStorage {
        val bytes = persistence.read() ?: return InMemoryAutomationStorage()
        if (bytes.isEmpty() || bytes.size > limits.maximumSnapshotBytes) {
            throw AutomationPersistenceException("automation_snapshot_size_invalid")
        }
        val snapshot = runCatching {
            AutomationSnapshotJsonCodec.decode(bytes.toString(Charsets.UTF_8))
        }.getOrElse {
            throw AutomationPersistenceException("automation_snapshot_corrupt")
        }
        return runCatching { InMemoryAutomationStorage(bound(snapshot)) }
            .getOrElse { throw AutomationPersistenceException("automation_snapshot_inconsistent") }
    }

    private inline fun <T> mutate(block: (InMemoryAutomationStorage) -> T): T {
        val current = delegate.snapshot()
        val candidate = InMemoryAutomationStorage(current)
        val result = block(candidate)
        val bounded = bound(candidate.snapshot())
        if (bounded == current) return result
        val bytes = AutomationSnapshotJsonCodec.encode(bounded).toByteArray(Charsets.UTF_8)
        if (bytes.size > limits.maximumSnapshotBytes) {
            throw AutomationPersistenceException("automation_snapshot_capacity_exceeded")
        }
        persistence.write(bytes)
        delegate = InMemoryAutomationStorage(bounded)
        return result
    }

    private fun bound(snapshot: AutomationStorageSnapshot): AutomationStorageSnapshot {
        if (snapshot.definitions.size > limits.maximumDefinitions) {
            throw AutomationPersistenceException("automation_definition_capacity_exceeded")
        }
        if (snapshot.inbox.size > limits.maximumInboxItems) {
            throw AutomationPersistenceException("automation_inbox_capacity_exceeded")
        }
        if (snapshot.leases.size > limits.maximumLeases) {
            throw AutomationPersistenceException("automation_lease_capacity_exceeded")
        }
        if (snapshot.confirmations.size > limits.maximumConfirmations) {
            throw AutomationPersistenceException("automation_confirmation_capacity_exceeded")
        }

        val terminalStates = setOf(
            AutomationRunState.SUCCEEDED,
            AutomationRunState.FAILED_TERMINAL,
            AutomationRunState.SKIPPED,
        )
        // Ambiguous fenced runs are explicit-review records, not disposable history. Retain their
        // lease-bound evidence until a future manual-resolution contract clears it deliberately.
        val protectedDetailed = snapshot.runs.filter {
            it.state !in terminalStates || it.dispatchFence != null
        }
        if (protectedDetailed.size > limits.maximumRunHistory) {
            throw AutomationPersistenceException("automation_active_run_capacity_exceeded")
        }
        val historySlots = limits.maximumRunHistory - protectedDetailed.size
        val retainedTerminal = snapshot.runs
            .filter { it.state in terminalStates && it.dispatchFence == null }
            .sortedWith(compareByDescending<AutomationRun> { it.updatedAt }.thenByDescending { it.key })
            .take(historySlots)
        val retainedKeys = (protectedDetailed + retainedTerminal).mapTo(hashSetOf()) { it.key }
        val compactedReceipts = snapshot.runs
            .asSequence()
            .filter { it.state in terminalStates && it.key !in retainedKeys }
            .map { AutomationRunReceipt(it.key, it.state, it.updatedAt) }
        val receipts = (snapshot.receipts.asSequence() + compactedReceipts)
            .distinctBy { it.key }
            .sortedWith(
                compareByDescending<AutomationRunReceipt> { it.completedAt }
                    .thenByDescending { it.key },
            )
            .take(limits.maximumRunReceipts)
            .sortedBy { it.key }
            .toList()
        val retainedReceiptKeys = receipts.mapTo(hashSetOf()) { it.key }
        val retainedWorkKeys = retainedKeys + snapshot.inbox.map { it.key } + retainedReceiptKeys
        val manualInvocations = snapshot.manualInvocations
            .asSequence()
            .filter { it.key in retainedWorkKeys }
            .sortedByDescending { it.requestedAt }
            .take(limits.maximumManualInvocations)
            .sortedBy { it.requestedAt }
            .toList()
        return snapshot.copy(
            runs = (protectedDetailed + retainedTerminal).sortedBy { it.key },
            receipts = receipts,
            confirmations = snapshot.confirmations.filter { it.key in retainedKeys },
            manualInvocations = manualInvocations,
            leases = snapshot.leases.filter { it.key in retainedKeys },
        )
    }
}

/** Versioned projection kept independent from Android storage mechanics for deterministic tests. */
internal object AutomationSnapshotJsonCodec {
    private const val VERSION = 3
    private const val LEGACY_VERSION = 1

    fun encode(snapshot: AutomationStorageSnapshot): String = JSONObject()
        .put("version", VERSION)
        .put("definitions", JSONArray().also { out -> snapshot.definitions.forEach { out.put(it.json()) } })
        .put("inbox", JSONArray().also { out -> snapshot.inbox.forEach { out.put(it.json()) } })
        .put("runs", JSONArray().also { out -> snapshot.runs.forEach { out.put(it.json()) } })
        .put("receipts", JSONArray().also { out -> snapshot.receipts.forEach { out.put(it.json()) } })
        .put("confirmations", JSONArray().also { out -> snapshot.confirmations.forEach { out.put(it.json()) } })
        .put("manualInvocations", JSONArray().also { out -> snapshot.manualInvocations.forEach { out.put(it.json()) } })
        .put("leases", JSONArray().also { out -> snapshot.leases.forEach { out.put(it.json()) } })
        .put("cursors", JSONArray().also { out -> snapshot.cursors.forEach { out.put(it.json()) } })
        .put("recovery", snapshot.recoveryState?.json() ?: JSONObject.NULL)
        .toString()

    fun decode(text: String): AutomationStorageSnapshot {
        val root = JSONObject(text)
        val version = root.getInt("version")
        require(version in LEGACY_VERSION..VERSION)
        val snapshot = AutomationStorageSnapshot(
            definitions = root.objects("definitions", 10_000) { it.definition() },
            inbox = root.objects("inbox", 100_000) { it.inboxItem() },
            runs = root.objects("runs", 100_000) { it.run() },
            receipts = root.objects("receipts", 500_000) { it.receipt() },
            confirmations = root.objects("confirmations", 10_000) { it.confirmation() },
            manualInvocations = root.objects("manualInvocations", 500_000) { it.manualInvocation() },
            leases = root.objects("leases", 10_000) { it.lease() },
            cursors = root.objects("cursors", 10_000) { it.cursor() },
            recoveryState = if (root.isNull("recovery")) null else root.getJSONObject("recovery").recovery(),
        )
        return if (version == LEGACY_VERSION) snapshot.migrateLegacyLeases() else snapshot
    }

    /**
     * Version 1 predates the durable pre-dispatch fence. Consequently an interrupted leased run
     * cannot prove whether its Codex turn was still local or already effectful. Fail such work
     * terminally during decode and remove its active lease so recovery and the executor can never
     * redispatch it. Non-leased PENDING/RETRY_WAIT work remains eligible as before.
     */
    private fun AutomationStorageSnapshot.migrateLegacyLeases(): AutomationStorageSnapshot {
        val leasesByKey = leases.associateBy { it.key }
        val ambiguousKeys = runs.asSequence()
            .filter { it.state == AutomationRunState.LEASED && it.dispatchFence == null }
            .mapNotNull { run -> leasesByKey[run.key]?.let { run.key } }
            .toSet()
        if (ambiguousKeys.isEmpty()) return this
        return copy(
            runs = runs.map { run ->
                if (run.key !in ambiguousKeys) {
                    run
                } else {
                    val lease = checkNotNull(leasesByKey[run.key])
                    run.copy(
                        state = AutomationRunState.FAILED_TERMINAL,
                        updatedAt = maxOf(run.updatedAt, lease.heartbeatAt),
                        lastFailureCode = "automation_outcome_ambiguous",
                        dispatchFence = null,
                    )
                }
            },
            confirmations = confirmations.filterNot { it.key in ambiguousKeys },
            leases = leases.filterNot { it.key in ambiguousKeys },
        )
    }

    private fun AutomationDefinition.json(): JSONObject = JSONObject()
        .put("id", id.value)
        .put("revision", revision)
        .put("enabled", enabled)
        .put("start", schedule.dtStartLocal.toString())
        .put("zoneKind", if (schedule.timeZone is AutomationTimeZone.Fixed) "fixed" else "system")
        .put("zoneId", (schedule.timeZone as? AutomationTimeZone.Fixed)?.zoneId ?: JSONObject.NULL)
        .put("rrule", schedule.rrule)
        .put("missedMode", missedRunPolicy.mode.name)
        .put("grace", missedRunPolicy.gracePeriod.toString())
        .put("maximumCatchUp", missedRunPolicy.maximumCatchUpRuns)
        .put("maximumAttempts", retryPolicy.maximumAttempts)
        .put("initialBackoff", retryPolicy.initialBackoff.toString())
        .put("backoffMultiplier", retryPolicy.backoffMultiplier)
        .put("maximumBackoff", retryPolicy.maximumBackoff.toString())
        .put("targetKind", if (target is CodexAutomationTarget.ThreadBound) "thread" else "independent")
        .put("threadId", (target as? CodexAutomationTarget.ThreadBound)?.threadId ?: JSONObject.NULL)
        .put("instruction", instruction)
        .put("updatedAt", updatedAt.toString())
        .put("capabilities", JSONArray().also { out ->
            requirements.requiredCapabilities.sorted().forEach { out.put(it.value) }
        })
        .put("permissions", JSONArray().also { out ->
            requirements.requiredPermissions.sorted().forEach { out.put(it.value) }
        })
        .put("requiresCodexAuthentication", requirements.requiresCodexAuthentication)
        .put("requiresNetwork", requirements.requiresNetwork)
        .put("requiresUnlockedDevice", requirements.requiresUnlockedDevice)
        .put("confirmationPolicy", requirements.confirmationPolicy.name)
        .put("timingPolicy", timingPolicy.name)

    private fun JSONObject.definition(): AutomationDefinition = AutomationDefinition(
        id = AutomationId(getString("id")),
        revision = getLong("revision"),
        enabled = getBoolean("enabled"),
        schedule = AutomationSchedule(
            dtStartLocal = LocalDateTime.parse(getString("start")),
            timeZone = when (getString("zoneKind")) {
                "fixed" -> AutomationTimeZone.Fixed(getString("zoneId"))
                "system" -> AutomationTimeZone.FollowSystem
                else -> error("unsupported zone kind")
            },
            rrule = getString("rrule"),
        ),
        missedRunPolicy = MissedRunPolicy(
            mode = MissedRunMode.valueOf(getString("missedMode")),
            gracePeriod = Duration.parse(getString("grace")),
            maximumCatchUpRuns = getInt("maximumCatchUp"),
        ),
        retryPolicy = AutomationRetryPolicy(
            maximumAttempts = getInt("maximumAttempts"),
            initialBackoff = Duration.parse(getString("initialBackoff")),
            backoffMultiplier = getInt("backoffMultiplier"),
            maximumBackoff = Duration.parse(getString("maximumBackoff")),
        ),
        target = when (getString("targetKind")) {
            "thread" -> CodexAutomationTarget.ThreadBound(getString("threadId"))
            "independent" -> CodexAutomationTarget.Independent
            else -> error("unsupported target kind")
        },
        instruction = getString("instruction"),
        updatedAt = Instant.parse(getString("updatedAt")),
        requirements = AutomationRequirements(
            requiredCapabilities = strings("capabilities", 64).mapTo(linkedSetOf()) {
                AutomationCapabilityId(it)
            },
            requiredPermissions = strings("permissions", 64).mapTo(linkedSetOf()) {
                AutomationPermissionId(it)
            },
            requiresCodexAuthentication = getBoolean("requiresCodexAuthentication"),
            requiresNetwork = getBoolean("requiresNetwork"),
            requiresUnlockedDevice = getBoolean("requiresUnlockedDevice"),
            confirmationPolicy = AutomationConfirmationPolicy.valueOf(getString("confirmationPolicy")),
        ),
        timingPolicy = AutomationTimingPolicy.valueOf(getString("timingPolicy")),
    )

    private fun AutomationInboxItem.json(): JSONObject = key.json()
        .put("definitionRevision", definitionRevision)
        .put("discoveredAt", discoveredAt.toString())
        .put("readyAt", readyAt.toString())
        .put("source", source.name)

    private fun JSONObject.inboxItem(): AutomationInboxItem = AutomationInboxItem(
        key = runKey(),
        definitionRevision = getLong("definitionRevision"),
        discoveredAt = Instant.parse(getString("discoveredAt")),
        readyAt = Instant.parse(getString("readyAt")),
        source = AutomationDiscoverySource.valueOf(getString("source")),
    )

    private fun AutomationRun.json(): JSONObject = key.json()
        .put("definitionRevision", definitionRevision)
        .put("state", state.name)
        .put("attemptCount", attemptCount)
        .put("availableAt", availableAt.toString())
        .put("createdAt", createdAt.toString())
        .put("updatedAt", updatedAt.toString())
        .put("lastFailureCode", lastFailureCode ?: JSONObject.NULL)
        .put("waitKind", waitKind?.name ?: JSONObject.NULL)
        .put("dispatchFence", dispatchFence?.json() ?: JSONObject.NULL)

    private fun JSONObject.run(): AutomationRun {
        val state = AutomationRunState.valueOf(getString("state"))
        val waitKind = if (!has("waitKind") || isNull("waitKind")) {
            if (state == AutomationRunState.RETRY_WAIT) {
                // Versions 1-2 cannot prove a wait was only a prerequisite deferral. Preserve it
                // as real backoff so an unlock/network event can never silently bypass history.
                AutomationRunWaitKind.RETRY_BACKOFF
            } else {
                null
            }
        } else {
            AutomationRunWaitKind.valueOf(getString("waitKind"))
        }
        return AutomationRun(
            key = runKey(),
            definitionRevision = getLong("definitionRevision"),
            state = state,
            attemptCount = getInt("attemptCount"),
            availableAt = Instant.parse(getString("availableAt")),
            createdAt = Instant.parse(getString("createdAt")),
            updatedAt = Instant.parse(getString("updatedAt")),
            lastFailureCode = nullableString("lastFailureCode"),
            waitKind = waitKind,
            dispatchFence = if (!has("dispatchFence") || isNull("dispatchFence")) {
                null
            } else {
                getJSONObject("dispatchFence").dispatchFence()
            },
        )
    }

    private fun AutomationDispatchFence.json(): JSONObject = JSONObject()
        .put("leaseToken", leaseToken.value)
        .put("leaseGeneration", leaseGeneration)
        .put("markedAt", markedAt.toString())

    private fun JSONObject.dispatchFence(): AutomationDispatchFence = AutomationDispatchFence(
        leaseToken = AutomationLeaseToken(getString("leaseToken")),
        leaseGeneration = getInt("leaseGeneration"),
        markedAt = Instant.parse(getString("markedAt")),
    )

    private fun AutomationRunReceipt.json(): JSONObject = key.json()
        .put("terminalState", terminalState.name)
        .put("completedAt", completedAt.toString())

    private fun JSONObject.receipt(): AutomationRunReceipt = AutomationRunReceipt(
        key = runKey(),
        terminalState = AutomationRunState.valueOf(getString("terminalState")),
        completedAt = Instant.parse(getString("completedAt")),
    )

    private fun AutomationRunConfirmationReceipt.json(): JSONObject = key.json()
        .put("id", id.value)
        .put("definitionRevision", definitionRevision)
        .put("confirmedAt", confirmedAt.toString())
        .put("expiresAt", expiresAt.toString())

    private fun JSONObject.confirmation(): AutomationRunConfirmationReceipt =
        AutomationRunConfirmationReceipt(
            id = AutomationConfirmationId(getString("id")),
            key = runKey(),
            definitionRevision = getLong("definitionRevision"),
            confirmedAt = Instant.parse(getString("confirmedAt")),
            expiresAt = Instant.parse(getString("expiresAt")),
        )

    private fun AutomationManualInvocation.json(): JSONObject = key.json()
        .put("requestId", requestId.value)
        .put("definitionRevision", definitionRevision)
        .put("requestedAt", requestedAt.toString())

    private fun JSONObject.manualInvocation(): AutomationManualInvocation =
        AutomationManualInvocation(
            requestId = AutomationManualRequestId(getString("requestId")),
            key = runKey(),
            definitionRevision = getLong("definitionRevision"),
            requestedAt = Instant.parse(getString("requestedAt")),
        )

    private fun AutomationLease.json(): JSONObject = key.json()
        .put("token", token.value)
        .put("owner", owner.value)
        .put("bootSessionId", bootSessionId.value)
        .put("generation", generation)
        .put("acquiredAt", acquiredAt.toString())
        .put("heartbeatAt", heartbeatAt.toString())
        .put("expiresAt", expiresAt.toString())

    private fun JSONObject.lease(): AutomationLease = AutomationLease(
        key = runKey(),
        token = AutomationLeaseToken(getString("token")),
        owner = AutomationWorkerId(getString("owner")),
        bootSessionId = AutomationBootSessionId(getString("bootSessionId")),
        generation = getInt("generation"),
        acquiredAt = Instant.parse(getString("acquiredAt")),
        heartbeatAt = Instant.parse(getString("heartbeatAt")),
        expiresAt = Instant.parse(getString("expiresAt")),
    )

    private fun AutomationSchedulerCursor.json(): JSONObject = JSONObject()
        .put("automationId", automationId.value)
        .put("definitionRevision", definitionRevision)
        .put("evaluatedThrough", evaluatedThrough.toString())

    private fun JSONObject.cursor(): AutomationSchedulerCursor = AutomationSchedulerCursor(
        automationId = AutomationId(getString("automationId")),
        definitionRevision = getLong("definitionRevision"),
        evaluatedThrough = Instant.parse(getString("evaluatedThrough")),
    )

    private fun AutomationRecoveryState.json(): JSONObject = JSONObject()
        .put("bootSessionId", bootSessionId.value)
        .put("systemZoneId", systemZoneId)
        .put("reconciledAt", reconciledAt.toString())

    private fun JSONObject.recovery(): AutomationRecoveryState = AutomationRecoveryState(
        bootSessionId = AutomationBootSessionId(getString("bootSessionId")),
        systemZoneId = getString("systemZoneId"),
        reconciledAt = Instant.parse(getString("reconciledAt")),
    )

    private fun AutomationRunKey.json(): JSONObject = JSONObject()
        .put("automationId", automationId.value)
        .put("scheduledAt", scheduledAt.toString())

    private fun JSONObject.runKey(): AutomationRunKey = AutomationRunKey(
        AutomationId(getString("automationId")),
        Instant.parse(getString("scheduledAt")),
    )

    private inline fun <T> JSONObject.objects(
        name: String,
        maximum: Int,
        transform: (JSONObject) -> T,
    ): List<T> {
        val array = getJSONArray(name)
        require(array.length() <= maximum)
        return buildList(array.length()) {
            for (index in 0 until array.length()) add(transform(array.getJSONObject(index)))
        }
    }

    private fun JSONObject.strings(name: String, maximum: Int): List<String> {
        val array = getJSONArray(name)
        require(array.length() <= maximum)
        return buildList(array.length()) {
            for (index in 0 until array.length()) add(array.getString(index))
        }
    }

    private fun JSONObject.nullableString(name: String): String? =
        if (isNull(name)) null else getString(name)
}
