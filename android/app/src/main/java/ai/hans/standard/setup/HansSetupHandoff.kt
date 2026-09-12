package ai.hans.standard.setup

import ai.hans.standard.integration.CodexDispatchAttemptResult
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import java.io.Closeable
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Narrow, data-minimal contract used after the beginner installer has installed Hans. */
object HansSetupHandoffContract {
    const val ACTION_CONTINUE_SETUP = "ai.hans.standard.action.CONTINUE_SETUP"
    const val PROTOCOL_VERSION = 1
    const val EXTRA_PROTOCOL_VERSION = "protocolVersion"
    const val EXTRA_HANDOFF_ID = "handoffId"
    const val EXTRA_REASON = "reason"
    private val REQUIRED_EXTRA_KEYS = setOf(
        EXTRA_PROTOCOL_VERSION,
        EXTRA_HANDOFF_ID,
        EXTRA_REASON,
    )

    fun parse(intent: Intent, expectedComponent: ComponentName): HansSetupHandoffCommand? =
        runCatching {
            if (intent.action != ACTION_CONTINUE_SETUP) return@runCatching null
            if (intent.component != expectedComponent) return@runCatching null
            val extras = intent.extras ?: return@runCatching null
            if (
                !extras.containsKey(EXTRA_PROTOCOL_VERSION) ||
                !extras.containsKey(EXTRA_HANDOFF_ID) ||
                !extras.containsKey(EXTRA_REASON)
            ) {
                return@runCatching null
            }
            if (extras.keySet() != REQUIRED_EXTRA_KEYS) return@runCatching null
            val protocol = extras.getInt(EXTRA_PROTOCOL_VERSION, Int.MIN_VALUE)
            val rawId = extras.getString(EXTRA_HANDOFF_ID) ?: return@runCatching null
            val rawReason = extras.getString(EXTRA_REASON) ?: return@runCatching null
            if (protocol != PROTOCOL_VERSION) return@runCatching null
            val handoffId = HansSetupHandoffIds.canonicalUuid(rawId) ?: return@runCatching null
            val reason = HansSetupHandoffReason.fromWireValue(rawReason) ?: return@runCatching null
            HansSetupHandoffCommand(protocol, handoffId, reason)
        }.getOrNull()

    internal fun parseFields(
        action: String?,
        explicitComponentMatches: Boolean,
        protocolVersion: Any?,
        handoffId: Any?,
        reason: Any?,
    ): HansSetupHandoffCommand? {
        if (action != ACTION_CONTINUE_SETUP || !explicitComponentMatches) return null
        if (protocolVersion !is Int || protocolVersion != PROTOCOL_VERSION) return null
        if (handoffId !is String || reason !is String) return null
        val canonicalId = HansSetupHandoffIds.canonicalUuid(handoffId) ?: return null
        val parsedReason = HansSetupHandoffReason.fromWireValue(reason) ?: return null
        return HansSetupHandoffCommand(protocolVersion, canonicalId, parsedReason)
    }
}

internal object HansSetupHandoffIds {
    fun canonicalUuid(raw: String): String? {
        if (raw.length != UUID_TEXT_LENGTH || raw.any(Char::isISOControl)) return null
        return runCatching { UUID.fromString(raw).toString() }
            .getOrNull()
            ?.takeIf { it == raw }
    }

    private const val UUID_TEXT_LENGTH = 36
}

enum class HansSetupHandoffReason(val wireValue: String) {
    INSTALL("install"),
    REPAIR("repair"),
    ;

    companion object {
        fun fromWireValue(value: String): HansSetupHandoffReason? =
            entries.firstOrNull { it.wireValue == value }
    }
}

data class HansSetupHandoffCommand(
    val protocolVersion: Int,
    val handoffId: String,
    val reason: HansSetupHandoffReason,
) {
    init {
        require(protocolVersion == HansSetupHandoffContract.PROTOCOL_VERSION)
        require(HansSetupHandoffIds.canonicalUuid(handoffId) == handoffId)
    }
}

internal enum class HansSetupHandoffPhase {
    QUEUED,
    DISPATCH_RESERVED,
    DISPATCH_ACCEPTED,
    SERVER_SENT,
    SETUP_STARTED,
    ALREADY_COMPLETE,
    RECOVERY_REQUIRED,
    REJECTED,
}

internal data class HansSetupHandoffRecord(
    val command: HansSetupHandoffCommand,
    val clientUserMessageId: String,
    val phase: HansSetupHandoffPhase,
    val reservationOwnerId: String?,
    val receivedAtMillis: Long,
    val updatedAtMillis: Long,
    /** Fresh installer ids received before setup starts share this one server dispatch. */
    val aliases: List<HansSetupHandoffCommand> = emptyList(),
) {
    init {
        require(clientUserMessageId == messageIdFor(command.handoffId))
        require(receivedAtMillis >= 0L && updatedAtMillis >= receivedAtMillis)
        require(aliases.size < HansSetupHandoffBounds.MAX_RECORDS)
        require(aliases.none { it.handoffId == command.handoffId })
        require(aliases.map { it.handoffId }.distinct().size == aliases.size)
        require(
            reservationOwnerId == null ||
                HansSetupHandoffIds.canonicalUuid(reservationOwnerId) == reservationOwnerId,
        )
        if (
            phase in setOf(
                HansSetupHandoffPhase.DISPATCH_RESERVED,
                HansSetupHandoffPhase.DISPATCH_ACCEPTED,
            )
        ) {
            require(reservationOwnerId != null)
        }
    }

    val terminal: Boolean
        get() = phase in setOf(
            HansSetupHandoffPhase.SETUP_STARTED,
            HansSetupHandoffPhase.ALREADY_COMPLETE,
            HansSetupHandoffPhase.REJECTED,
        )

    val requestCount: Int get() = 1 + aliases.size

    fun registeredCommand(handoffId: String): HansSetupHandoffCommand? =
        command.takeIf { it.handoffId == handoffId }
            ?: aliases.firstOrNull { it.handoffId == handoffId }

    companion object {
        fun messageIdFor(handoffId: String): String = "hans-setup-handoff-$handoffId"
    }
}

internal data class HansSetupHandoffDocument(
    val records: List<HansSetupHandoffRecord> = emptyList(),
) {
    init {
        require(requestCount <= HansSetupHandoffBounds.MAX_RECORDS)
        val ids = records.flatMap { record ->
            listOf(record.command.handoffId) + record.aliases.map { it.handoffId }
        }
        require(ids.distinct().size == ids.size)
    }

    val requestCount: Int get() = records.sumOf { it.requestCount }
}

internal data class HansSetupHandoffMutation<T>(
    val document: HansSetupHandoffDocument,
    val result: T,
)

internal interface HansSetupHandoffStorage {
    fun read(): HansSetupHandoffDocument

    fun <T> mutate(
        transform: (HansSetupHandoffDocument) -> HansSetupHandoffMutation<T>,
    ): T
}

/** Atomic app-private receipt storage. It deliberately contains no setup answer or secret. */
internal class AtomicFileHansSetupHandoffStorage(
    context: Context,
    file: File = File(context.applicationContext.noBackupFilesDir, FILE_NAME),
) : HansSetupHandoffStorage {
    private val atomicFile = AtomicFile(file)

    override fun read(): HansSetupHandoffDocument = synchronized(PROCESS_LOCK) {
        readUnlocked()
    }

    override fun <T> mutate(
        transform: (HansSetupHandoffDocument) -> HansSetupHandoffMutation<T>,
    ): T = synchronized(PROCESS_LOCK) {
        val current = readUnlocked()
        val mutation = transform(current)
        if (mutation.document != current) writeUnlocked(mutation.document)
        check(readUnlocked() == mutation.document) { "setup_handoff_storage_verification" }
        mutation.result
    }

    private fun readUnlocked(): HansSetupHandoffDocument {
        val base = atomicFile.baseFile
        if (!base.exists() && !File("${base.path}.bak").exists() && !File("${base.path}.new").exists()) {
            return HansSetupHandoffDocument()
        }
        val bytes = atomicFile.readFully()
        require(bytes.isNotEmpty() && bytes.size <= HansSetupHandoffBounds.MAX_DOCUMENT_BYTES) {
            "setup_handoff_storage_size"
        }
        return HansSetupHandoffCodec.decode(bytes.toString(StandardCharsets.UTF_8))
    }

    private fun writeUnlocked(document: HansSetupHandoffDocument) {
        val bytes = HansSetupHandoffCodec.encode(document).toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= HansSetupHandoffBounds.MAX_DOCUMENT_BYTES) {
            "setup_handoff_storage_size"
        }
        val output = atomicFile.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (failure: Exception) {
            atomicFile.failWrite(output)
            throw failure
        }
    }

    companion object {
        internal const val FILE_NAME = "hans-setup-handoff-v1.json"
        private val PROCESS_LOCK = Any()
    }
}

internal object HansSetupHandoffCodec {
    private const val SCHEMA = "hans.setup-handoff"
    private const val VERSION = 2

    fun encode(document: HansSetupHandoffDocument): String = JSONObject()
        .put("schema", SCHEMA)
        .put("version", VERSION)
        .put(
            "records",
            JSONArray().also { values ->
                document.records.forEach { record ->
                    values.put(
                        JSONObject()
                            .put("protocolVersion", record.command.protocolVersion)
                            .put("handoffId", record.command.handoffId)
                            .put("reason", record.command.reason.wireValue)
                            .put("clientUserMessageId", record.clientUserMessageId)
                            .put("phase", record.phase.name)
                            .put(
                                "reservationOwnerId",
                                record.reservationOwnerId ?: JSONObject.NULL,
                            )
                            .put("receivedAtMillis", record.receivedAtMillis)
                            .put("updatedAtMillis", record.updatedAtMillis)
                            .put(
                                "aliases",
                                JSONArray().also { aliases ->
                                    record.aliases.forEach { alias ->
                                        aliases.put(
                                            JSONObject()
                                                .put("protocolVersion", alias.protocolVersion)
                                                .put("handoffId", alias.handoffId)
                                                .put("reason", alias.reason.wireValue),
                                        )
                                    }
                                },
                            ),
                    )
                }
            },
        )
        .toString()

    fun decode(raw: String): HansSetupHandoffDocument {
        val bytes = raw.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= HansSetupHandoffBounds.MAX_DOCUMENT_BYTES) {
            "setup_handoff_storage_size"
        }
        val root = JSONObject(raw)
        requireExactKeys(root, setOf("schema", "version", "records"))
        require(root.getString("schema") == SCHEMA) { "setup_handoff_storage_schema" }
        val version = root.getInt("version")
        require(version in 1..VERSION) { "setup_handoff_storage_version" }
        val values = root.getJSONArray("records")
        require(values.length() <= HansSetupHandoffBounds.MAX_RECORDS) {
            "setup_handoff_storage_records"
        }
        val records = buildList(values.length()) {
            repeat(values.length()) { index ->
                val value = values.getJSONObject(index)
                requireExactKeys(
                    value,
                    setOf(
                        "protocolVersion",
                        "handoffId",
                        "reason",
                        "clientUserMessageId",
                        "phase",
                        "reservationOwnerId",
                        "receivedAtMillis",
                        "updatedAtMillis",
                    ) + if (version == 2) setOf("aliases") else emptySet(),
                )
                val command = HansSetupHandoffContract.parseFields(
                    action = HansSetupHandoffContract.ACTION_CONTINUE_SETUP,
                    explicitComponentMatches = true,
                    protocolVersion = value.get("protocolVersion"),
                    handoffId = value.get("handoffId"),
                    reason = value.get("reason"),
                ) ?: error("setup_handoff_storage_record")
                add(
                    HansSetupHandoffRecord(
                        command = command,
                        clientUserMessageId = value.getString("clientUserMessageId"),
                        phase = HansSetupHandoffPhase.valueOf(value.getString("phase")),
                        reservationOwnerId = if (value.isNull("reservationOwnerId")) {
                            null
                        } else {
                            value.getString("reservationOwnerId")
                        },
                        receivedAtMillis = value.getLong("receivedAtMillis"),
                        updatedAtMillis = value.getLong("updatedAtMillis"),
                        aliases = if (version == 1) {
                            emptyList()
                        } else {
                            val aliases = value.getJSONArray("aliases")
                            require(aliases.length() < HansSetupHandoffBounds.MAX_RECORDS)
                            List(aliases.length()) { aliasIndex ->
                                val alias = aliases.getJSONObject(aliasIndex)
                                requireExactKeys(alias, setOf("protocolVersion", "handoffId", "reason"))
                                HansSetupHandoffContract.parseFields(
                                    action = HansSetupHandoffContract.ACTION_CONTINUE_SETUP,
                                    explicitComponentMatches = true,
                                    protocolVersion = alias.get("protocolVersion"),
                                    handoffId = alias.get("handoffId"),
                                    reason = alias.get("reason"),
                                ) ?: error("setup_handoff_storage_alias")
                            }
                        },
                    ),
                )
            }
        }
        return HansSetupHandoffDocument(records)
    }

    private fun requireExactKeys(value: JSONObject, expected: Set<String>) {
        val actual = buildSet {
            val names = value.keys()
            while (names.hasNext()) add(names.next())
        }
        require(actual == expected) { "setup_handoff_storage_keys" }
    }
}

internal object HansSetupHandoffBounds {
    const val MAX_RECORDS = 1_024
    const val MAX_RETAINED_TERMINAL_RECORDS = 256
    const val MAX_DOCUMENT_BYTES = 512 * 1_024
}

internal fun interface HansSetupHandoffClock {
    fun nowMillis(): Long

    companion object {
        val SYSTEM = HansSetupHandoffClock(System::currentTimeMillis)
    }
}

internal object HansSetupHandoffProcessIdentity {
    val current: String = UUID.randomUUID().toString()
}

internal enum class HansSetupHandoffRegistration {
    ACCEPTED,
    DUPLICATE,
    CONFLICT,
    CAPACITY_REACHED,
}

internal data class HansSetupHandoffRegistrationResult(
    val registration: HansSetupHandoffRegistration,
    val record: HansSetupHandoffRecord?,
)

internal enum class HansSetupHandoffOutboundStatus {
    ABSENT,
    PENDING,
    SENT,
    FAILED,
}

internal data class HansSetupHandoffEnvironment(
    val dispatchReady: Boolean,
    val outboundStatus: HansSetupHandoffOutboundStatus,
    val setupComplete: Boolean,
)

internal sealed interface HansSetupHandoffAction {
    data object None : HansSetupHandoffAction
    data class Dispatch(val record: HansSetupHandoffRecord) : HansSetupHandoffAction
    data class StartLocalSetup(val record: HansSetupHandoffRecord) : HansSetupHandoffAction
    data class SkipAlreadyComplete(val record: HansSetupHandoffRecord) : HansSetupHandoffAction
    data class RecoveryRequired(val record: HansSetupHandoffRecord) : HansSetupHandoffAction
}

/**
 * Durable single-dispatch reducer. It never guesses that an accepted-but-unreceipted dispatch may
 * be replayed: a different process owner converts that state into explicit repair recovery.
 */
internal class HansSetupHandoffCoordinator(
    private val storage: HansSetupHandoffStorage,
    private val clock: HansSetupHandoffClock = HansSetupHandoffClock.SYSTEM,
    private val processOwnerId: String = HansSetupHandoffProcessIdentity.current,
) {
    init {
        require(HansSetupHandoffIds.canonicalUuid(processOwnerId) == processOwnerId)
    }

    fun register(command: HansSetupHandoffCommand): HansSetupHandoffRegistrationResult =
        storage.mutate { original ->
            val existing = original.records.firstOrNull {
                it.registeredCommand(command.handoffId) != null
            }
            if (existing != null) {
                val registration = if (existing.registeredCommand(command.handoffId) == command) {
                    HansSetupHandoffRegistration.DUPLICATE
                } else {
                    HansSetupHandoffRegistration.CONFLICT
                }
                return@mutate HansSetupHandoffMutation(
                    original,
                    HansSetupHandoffRegistrationResult(registration, existing),
                )
            }
            val superseded = if (command.reason == HansSetupHandoffReason.REPAIR) {
                supersedeAmbiguousRecoveries(original)
            } else {
                original
            }
            val current = pruneForRegistration(coalesceQueuedRequests(superseded))
            if (current.requestCount >= HansSetupHandoffBounds.MAX_RECORDS) {
                return@mutate HansSetupHandoffMutation(
                    current,
                    HansSetupHandoffRegistrationResult(
                        HansSetupHandoffRegistration.CAPACITY_REACHED,
                        null,
                    ),
                )
            }
            val now = clock.nowMillis()
            // The receiver can be the first component in a new process. A fresh repair must
            // remain AFTER a foreign owner's unresolved reservation, even before nextAction
            // has had a snapshot with which to classify that old dispatch as recovery.
            val recoveryBoundary = current.records.indexOfLast {
                it.hasForeignReservation() || it.phase == HansSetupHandoffPhase.RECOVERY_REQUIRED
            }
            val pending = current.records.drop(recoveryBoundary + 1)
                .firstOrNull { it.canCoalesceNewRequest() }
            if (pending != null) {
                val coalesced = pending.copy(
                    aliases = pending.aliases + command,
                    updatedAtMillis = maxOf(now, pending.updatedAtMillis),
                )
                return@mutate HansSetupHandoffMutation(
                    current.copy(records = current.records.map {
                        if (it.command.handoffId == pending.command.handoffId) coalesced else it
                    }),
                    // This NEW id has been durably accepted. The receiver still binds its ACK
                    // to the incoming command; only the canonical id can acknowledge a turn.
                    HansSetupHandoffRegistrationResult(HansSetupHandoffRegistration.ACCEPTED, coalesced),
                )
            }
            val record = HansSetupHandoffRecord(
                command = command,
                clientUserMessageId = HansSetupHandoffRecord.messageIdFor(command.handoffId),
                phase = HansSetupHandoffPhase.QUEUED,
                reservationOwnerId = null,
                receivedAtMillis = now,
                updatedAtMillis = now,
            )
            val updated = current.copy(records = current.records + record)
            HansSetupHandoffMutation(
                updated,
                HansSetupHandoffRegistrationResult(
                    HansSetupHandoffRegistration.ACCEPTED,
                    record,
                ),
            )
        }

    fun nextAction(
        environmentFor: (HansSetupHandoffRecord) -> HansSetupHandoffEnvironment,
    ): HansSetupHandoffAction = storage.mutate { original ->
        // Also converges pre-upgrade v1 queues that already contain fresh install/repair ids.
        // No reserved, accepted, sent or ambiguous record is discarded or merged.
        var document = coalesceQueuedRequests(original)
        while (true) {
            val record = document.records.firstOrNull { !it.terminal }
                ?: return@mutate HansSetupHandoffMutation(document, HansSetupHandoffAction.None)
            if (record.phase == HansSetupHandoffPhase.RECOVERY_REQUIRED) {
                // An exact late server receipt resolves uncertainty without another request.
                // PENDING/ABSENT alone never authorizes replay or a claim that setup was sent.
                if (environmentFor(record).outboundStatus == HansSetupHandoffOutboundStatus.SENT) {
                    return@mutate acceptServerReceipt(document, record)
                }
                if (hasLaterRepair(document, record)) {
                    document = transition(document, record, HansSetupHandoffPhase.REJECTED).first
                    continue
                }
                return@mutate HansSetupHandoffMutation(
                    document,
                    HansSetupHandoffAction.RecoveryRequired(record),
                )
            }
            val environment = environmentFor(record)
            when (record.phase) {
                HansSetupHandoffPhase.QUEUED -> {
                    if (environment.setupComplete) {
                        val (updated, complete) = transition(
                            document,
                            record,
                            HansSetupHandoffPhase.ALREADY_COMPLETE,
                        )
                        return@mutate HansSetupHandoffMutation(
                            updated,
                            HansSetupHandoffAction.SkipAlreadyComplete(complete),
                        )
                    }
                    if (!environment.dispatchReady) {
                        return@mutate HansSetupHandoffMutation(
                            document,
                            HansSetupHandoffAction.None,
                        )
                    }
                    val (updated, reserved) = transition(
                        document,
                        record,
                        HansSetupHandoffPhase.DISPATCH_RESERVED,
                        reservationOwnerId = processOwnerId,
                    )
                    return@mutate HansSetupHandoffMutation(
                        updated,
                        HansSetupHandoffAction.Dispatch(reserved),
                    )
                }
                HansSetupHandoffPhase.DISPATCH_RESERVED,
                HansSetupHandoffPhase.DISPATCH_ACCEPTED,
                -> when (environment.outboundStatus) {
                    HansSetupHandoffOutboundStatus.ABSENT -> {
                        if (record.reservationOwnerId == processOwnerId) {
                            return@mutate HansSetupHandoffMutation(
                                document,
                                HansSetupHandoffAction.None,
                            )
                        }
                        val (updated, recovery) = transition(
                            document,
                            record,
                            HansSetupHandoffPhase.RECOVERY_REQUIRED,
                        )
                        document = updated
                        if (hasLaterRepair(document, recovery)) {
                            document = transition(
                                document,
                                recovery,
                                HansSetupHandoffPhase.REJECTED,
                            ).first
                            continue
                        }
                        return@mutate HansSetupHandoffMutation(
                            document,
                            HansSetupHandoffAction.RecoveryRequired(recovery),
                        )
                    }
                    HansSetupHandoffOutboundStatus.PENDING -> {
                        val updated = if (
                            record.phase == HansSetupHandoffPhase.DISPATCH_RESERVED
                        ) {
                            transition(
                                document,
                                record,
                                HansSetupHandoffPhase.DISPATCH_ACCEPTED,
                            ).first
                        } else {
                            document
                        }
                        return@mutate HansSetupHandoffMutation(
                            updated,
                            HansSetupHandoffAction.None,
                        )
                    }
                    HansSetupHandoffOutboundStatus.SENT -> {
                        return@mutate acceptServerReceipt(document, record)
                    }
                    HansSetupHandoffOutboundStatus.FAILED -> {
                        document = transition(
                            document,
                            record,
                            HansSetupHandoffPhase.REJECTED,
                        ).first
                        continue
                    }
                }
                HansSetupHandoffPhase.SERVER_SENT -> return@mutate HansSetupHandoffMutation(
                    document,
                    HansSetupHandoffAction.StartLocalSetup(record),
                )
                HansSetupHandoffPhase.SETUP_STARTED,
                HansSetupHandoffPhase.ALREADY_COMPLETE,
                HansSetupHandoffPhase.REJECTED,
                HansSetupHandoffPhase.RECOVERY_REQUIRED,
                -> error("terminal or recovery phase escaped setup handoff selection")
            }
        }
        @Suppress("UNREACHABLE_CODE")
        HansSetupHandoffMutation(document, HansSetupHandoffAction.None)
    }

    fun recordDispatchResult(
        record: HansSetupHandoffRecord,
        result: CodexDispatchAttemptResult,
    ): Boolean = storage.mutate { document ->
        val current = document.records.firstOrNull {
            it.command.handoffId == record.command.handoffId
        } ?: return@mutate HansSetupHandoffMutation(document, false)
        if (result is CodexDispatchAttemptResult.Accepted && result.clientUserMessageId == current.clientUserMessageId) {
            val updated = if (current.phase == HansSetupHandoffPhase.DISPATCH_RESERVED) {
                transition(
                    document,
                    current,
                    HansSetupHandoffPhase.DISPATCH_ACCEPTED,
                ).first
            } else {
                document
            }
            return@mutate HansSetupHandoffMutation(updated, true)
        }
        if (
            current.phase !in setOf(
                HansSetupHandoffPhase.DISPATCH_RESERVED,
                HansSetupHandoffPhase.DISPATCH_ACCEPTED,
            )
        ) {
            return@mutate HansSetupHandoffMutation(document, false)
        }
        // Only explicit proof of rejection before transport may return to the unsent queue.
        // In particular, never project TransportOutcomeAmbiguous to a nullable message id.
        val phase = if (result == CodexDispatchAttemptResult.RejectedBeforeTransport) {
            HansSetupHandoffPhase.QUEUED
        } else {
            HansSetupHandoffPhase.RECOVERY_REQUIRED
        }
        val updated = transition(
            document,
            current,
            phase,
            reservationOwnerId = if (phase == HansSetupHandoffPhase.QUEUED) {
                null
            } else {
                current.reservationOwnerId
            },
        ).first
        HansSetupHandoffMutation(updated, false)
    }

    fun markDispatchAmbiguous(record: HansSetupHandoffRecord): HansSetupHandoffRecord? =
        storage.mutate { document ->
            val current = document.records.firstOrNull {
                it.command.handoffId == record.command.handoffId
            }
            if (current?.phase == HansSetupHandoffPhase.RECOVERY_REQUIRED) {
                return@mutate HansSetupHandoffMutation(document, current)
            }
            if (
                current == null ||
                current.phase !in setOf(
                    HansSetupHandoffPhase.DISPATCH_RESERVED,
                    HansSetupHandoffPhase.DISPATCH_ACCEPTED,
                )
            ) {
                return@mutate HansSetupHandoffMutation(document, null)
            }
            val (updated, recovery) = transition(
                document,
                current,
                HansSetupHandoffPhase.RECOVERY_REQUIRED,
            )
            HansSetupHandoffMutation(updated, recovery)
        }

    fun markSetupStarted(record: HansSetupHandoffRecord): Boolean = storage.mutate { document ->
        val current = document.records.firstOrNull {
            it.command.handoffId == record.command.handoffId
        } ?: return@mutate HansSetupHandoffMutation(document, false)
        if (current.phase == HansSetupHandoffPhase.SETUP_STARTED) {
            return@mutate HansSetupHandoffMutation(document, true)
        }
        if (current.phase != HansSetupHandoffPhase.SERVER_SENT) {
            return@mutate HansSetupHandoffMutation(document, false)
        }
        val updated = transition(
            document,
            current,
            HansSetupHandoffPhase.SETUP_STARTED,
        ).first
        HansSetupHandoffMutation(updated, true)
    }

    fun snapshot(): HansSetupHandoffDocument = storage.read()

    private fun acceptServerReceipt(
        document: HansSetupHandoffDocument,
        current: HansSetupHandoffRecord,
    ): HansSetupHandoffMutation<HansSetupHandoffAction> {
        val updated = transition(document, current, HansSetupHandoffPhase.SERVER_SENT).first
        // A late exact ACK resolves the process boundary. Only now may its still-QUEUED
        // follow-ups become aliases; they must not create another onboarding prompt after
        // the already-sent first question. Existing in-flight records remain independent.
        val coalesced = coalesceQueuedRequests(updated)
        val sent = coalesced.records.single { it.command.handoffId == current.command.handoffId }
        return HansSetupHandoffMutation(coalesced, HansSetupHandoffAction.StartLocalSetup(sent))
    }

    private fun transition(
        document: HansSetupHandoffDocument,
        current: HansSetupHandoffRecord,
        phase: HansSetupHandoffPhase,
        reservationOwnerId: String? = current.reservationOwnerId,
    ): Pair<HansSetupHandoffDocument, HansSetupHandoffRecord> {
        val updated = current.copy(
            phase = phase,
            reservationOwnerId = reservationOwnerId,
            updatedAtMillis = maxOf(current.updatedAtMillis, clock.nowMillis()),
        )
        return document.copy(
            records = document.records.map {
                if (it.command.handoffId == current.command.handoffId) updated else it
            },
        ) to updated
    }

    private fun hasLaterRepair(
        document: HansSetupHandoffDocument,
        current: HansSetupHandoffRecord,
    ): Boolean = document.records
        .dropWhile { it.command.handoffId != current.command.handoffId }
        .drop(1)
        .any {
            !it.terminal && (
                it.command.reason == HansSetupHandoffReason.REPAIR ||
                    it.aliases.any { alias -> alias.reason == HansSetupHandoffReason.REPAIR }
                )
        }

    private fun pruneForRegistration(
        document: HansSetupHandoffDocument,
    ): HansSetupHandoffDocument {
        val terminalToRetain = document.records
            .filter(HansSetupHandoffRecord::terminal)
            .takeLast(HansSetupHandoffBounds.MAX_RETAINED_TERMINAL_RECORDS)
            .mapTo(mutableSetOf()) { it.command.handoffId }
        var records = document.records.filter {
            !it.terminal || it.command.handoffId in terminalToRetain
        }
        // Keep canonical records and their aliases together. Fresh UUIDs cannot evade the
        // receipt bound by accumulating in one coalesced record indefinitely.
        var requestCount = records.sumOf { it.requestCount }
        if (requestCount >= HansSetupHandoffBounds.MAX_RECORDS) {
            val oldestTerminalIds = mutableSetOf<String>()
            for (record in records.filter(HansSetupHandoffRecord::terminal)) {
                if (requestCount < HansSetupHandoffBounds.MAX_RECORDS) break
                oldestTerminalIds += record.command.handoffId
                requestCount -= record.requestCount
            }
            records = records.filterNot { it.command.handoffId in oldestTerminalIds }
        }
        return if (records == document.records) document else document.copy(records = records)
    }

    private fun supersedeAmbiguousRecoveries(
        document: HansSetupHandoffDocument,
    ): HansSetupHandoffDocument {
        if (document.records.none { it.phase == HansSetupHandoffPhase.RECOVERY_REQUIRED }) {
            return document
        }
        val now = clock.nowMillis()
        return document.copy(
            records = document.records.map { record ->
                if (record.phase == HansSetupHandoffPhase.RECOVERY_REQUIRED) {
                    record.copy(
                        phase = HansSetupHandoffPhase.REJECTED,
                        updatedAtMillis = maxOf(now, record.updatedAtMillis),
                    )
                } else {
                    record
                }
            },
        )
    }

    private fun HansSetupHandoffRecord.hasForeignReservation(): Boolean =
        phase in setOf(HansSetupHandoffPhase.DISPATCH_RESERVED, HansSetupHandoffPhase.DISPATCH_ACCEPTED) &&
            reservationOwnerId != processOwnerId

    private fun HansSetupHandoffRecord.canCoalesceNewRequest(): Boolean =
        phase in setOf(
            HansSetupHandoffPhase.QUEUED,
            HansSetupHandoffPhase.DISPATCH_RESERVED,
            HansSetupHandoffPhase.DISPATCH_ACCEPTED,
            HansSetupHandoffPhase.SERVER_SENT,
        ) && !hasForeignReservation()

    /** Only QUEUED requests are known not to have reached Codex and may become aliases. */
    private fun coalesceQueuedRequests(document: HansSetupHandoffDocument): HansSetupHandoffDocument {
        val records = mutableListOf<HansSetupHandoffRecord>()
        val pendingGroup = mutableListOf<HansSetupHandoffRecord>()
        fun flushPendingGroup() {
            val canonical = pendingGroup.firstOrNull { it.phase != HansSetupHandoffPhase.QUEUED }
                ?: pendingGroup.firstOrNull() ?: return
            val redundant = pendingGroup.filter {
                it.phase == HansSetupHandoffPhase.QUEUED &&
                    it.command.handoffId != canonical.command.handoffId
            }
            val removedIds = redundant.mapTo(mutableSetOf()) { it.command.handoffId }
            val coalesced = if (redundant.isEmpty()) canonical else canonical.copy(
                aliases = canonical.aliases + redundant.flatMap { listOf(it.command) + it.aliases },
                updatedAtMillis = maxOf(clock.nowMillis(), canonical.updatedAtMillis),
            )
            pendingGroup.forEach {
                when (it.command.handoffId) {
                    in removedIds -> Unit
                    canonical.command.handoffId -> records += coalesced
                    else -> records += it
                }
            }
            pendingGroup.clear()
        }
        document.records.forEach { record ->
            if (record.canCoalesceNewRequest()) {
                pendingGroup += record
            } else {
                // Foreign reservations, recovery and completed/started setup are intent
                // boundaries. Do not move later repair/resume in front of uncertain work.
                flushPendingGroup()
                records += record
            }
        }
        flushPendingGroup()
        return if (records == document.records) document else document.copy(records = records)
    }
}

internal enum class HansSetupHandoffAckStatus(val wireValue: String) {
    ACCEPTED("accepted"),
    DUPLICATE("duplicate"),
    RECOVERY_REQUIRED("recovery_required"),
    ALREADY_COMPLETE("already_complete"),
    REJECTED_CONFLICT("rejected_conflict"),
    REJECTED_CAPACITY("rejected_capacity"),
    REJECTED_STORAGE("rejected_storage"),
}

internal data class HansSetupHandoffAcknowledgement(
    val command: HansSetupHandoffCommand,
    val status: HansSetupHandoffAckStatus,
    val sha256: String,
) {
    val resultData: String
        get() = listOf(
            SCHEMA,
            command.protocolVersion.toString(),
            command.handoffId,
            command.reason.wireValue,
            status.wireValue,
            "sha256",
            sha256,
        ).joinToString(":")

    companion object {
        const val SCHEMA = "hans.setup-handoff.ack.v1"

        fun create(
            command: HansSetupHandoffCommand,
            status: HansSetupHandoffAckStatus,
        ): HansSetupHandoffAcknowledgement {
            val payload = listOf(
                SCHEMA,
                command.protocolVersion.toString(),
                command.handoffId,
                command.reason.wireValue,
                status.wireValue,
            ).joinToString("\n")
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(payload.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            return HansSetupHandoffAcknowledgement(command, status, digest)
        }
    }
}

internal object HansSetupHandoffSignalCenter {
    private val observers = LinkedHashSet<() -> Unit>()

    fun observe(observer: () -> Unit): Closeable {
        synchronized(observers) { observers += observer }
        return Closeable { synchronized(observers) { observers -= observer } }
    }

    fun publish() {
        val snapshot = synchronized(observers) { observers.toList() }
        snapshot.forEach { observer -> runCatching(observer) }
    }
}

/**
 * Keeps observer work outside the ordered-broadcast receiver frame. Production supplies a main
 * looper queue, whose post contract never invokes [publish] inline.
 */
internal class HansSetupHandoffSignalDeferral(
    private val enqueue: ((() -> Unit) -> Boolean),
    private val publish: () -> Unit,
) {
    fun scheduleAfterAcknowledgement(): Boolean = enqueue(publish)
}
