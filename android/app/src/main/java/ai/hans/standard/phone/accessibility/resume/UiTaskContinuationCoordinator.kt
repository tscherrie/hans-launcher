package ai.hans.standard.phone.accessibility.resume

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.phone.accessibility.UiInteractionAvailability
import java.util.UUID

/** Atomic state owner for durable, content-free UI continuation receipts. */
internal class UiTaskContinuationJournal(
    private val storage: UiTaskContinuationStorage,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val claimTokens: () -> String = { UUID.randomUUID().toString() },
) : UiTaskContinuationCheckpointer {
    override fun checkpoint(
        call: DynamicToolCallParams,
        availability: UiInteractionAvailability,
    ): UiTaskContinuationCheckpoint {
        expireStaleWaiting()
        val blockedReason = UiTaskContinuationBlockedReason.from(availability)
            ?: return UiTaskContinuationCheckpoint.NotPersisted
        val identity = UiTaskContinuationIdentity.from(call)
            ?: return UiTaskContinuationCheckpoint.NotPersisted
        return runCatching {
            storage.mutate { document ->
                val existing = document.records.singleOrNull { it.identity.key == identity.key }
                if (existing != null) {
                    val exactWaiting = existing.identity == identity &&
                        existing.status == UiTaskContinuationStatus.WAITING_USER_PRESENT
                    return@mutate UiTaskContinuationMutation(
                        document,
                        if (exactWaiting) {
                            UiTaskContinuationCheckpoint.Persisted(identity.continuationId)
                        } else {
                            UiTaskContinuationCheckpoint.NotPersisted
                        },
                    )
                }
                val retained = trimForInsert(document.records)
                    ?: return@mutate UiTaskContinuationMutation(
                        document,
                        UiTaskContinuationCheckpoint.NotPersisted,
                    )
                val now = nowMillis().coerceAtLeast(0L)
                val record = UiTaskContinuationRecord(
                    identity = identity,
                    blockedReason = blockedReason,
                    status = UiTaskContinuationStatus.WAITING_USER_PRESENT,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                )
                UiTaskContinuationMutation(
                    UiTaskContinuationDocument(retained + record),
                    UiTaskContinuationCheckpoint.Persisted(identity.continuationId),
                )
            }
        }.getOrDefault(UiTaskContinuationCheckpoint.NotPersisted)
    }

    /** A process which died after claim cannot prove whether transport started. Fail closed. */
    fun recoverClaimedAfterProcessDeath(): Int = runCatching {
        storage.mutate { document ->
            var changed = 0
            val now = nowMillis().coerceAtLeast(0L)
            val updated = document.records.map { record ->
                if (record.status == UiTaskContinuationStatus.CLAIMED) {
                    changed += 1
                    record.copy(
                        status = UiTaskContinuationStatus.MANUAL_REVIEW,
                        updatedAtEpochMillis = maxOf(record.updatedAtEpochMillis, now),
                        claimToken = null,
                    )
                } else {
                    record
                }
            }
            UiTaskContinuationMutation(UiTaskContinuationDocument(updated), changed)
        }
    }.getOrDefault(0)

    /** Immortal waiting records would eventually block new work while auto dispatch is disabled. */
    fun expireStaleWaiting(
        maximumAgeMillis: Long = DEFAULT_WAITING_MAX_AGE_MILLIS,
    ): Int {
        require(maximumAgeMillis > 0L)
        return runCatching {
            storage.mutate { document ->
                var changed = 0
                val now = nowMillis().coerceAtLeast(0L)
                val updated = document.records.map { record ->
                    val age = now - record.updatedAtEpochMillis
                    if (
                        record.status == UiTaskContinuationStatus.WAITING_USER_PRESENT &&
                        age >= maximumAgeMillis && age >= 0L
                    ) {
                        changed += 1
                        record.copy(
                            status = UiTaskContinuationStatus.MANUAL_REVIEW,
                            updatedAtEpochMillis = now,
                        )
                    } else {
                        record
                    }
                }
                UiTaskContinuationMutation(UiTaskContinuationDocument(updated), changed)
            }
        }.getOrDefault(0)
    }

    fun hasWaiting(): Boolean = runCatching {
        storage.read().records.any { it.status == UiTaskContinuationStatus.WAITING_USER_PRESENT }
    }.getOrDefault(false)

    fun claimOldestWaiting(): UiTaskContinuationClaim? = runCatching {
        storage.mutate { document ->
            val candidate = document.records
                .filter { it.status == UiTaskContinuationStatus.WAITING_USER_PRESENT }
                .minWithOrNull(
                    compareBy<UiTaskContinuationRecord>(UiTaskContinuationRecord::createdAtEpochMillis)
                        .thenBy { it.identity.continuationId },
                )
                ?: return@mutate UiTaskContinuationMutation(document, null)
            val token = UiTaskContinuationBounds.requireClaimToken(claimTokens())
            val now = nowMillis().coerceAtLeast(candidate.updatedAtEpochMillis)
            val claimed = candidate.copy(
                status = UiTaskContinuationStatus.CLAIMED,
                updatedAtEpochMillis = now,
                claimToken = token,
            )
            UiTaskContinuationMutation(
                UiTaskContinuationDocument(
                    document.records.map { if (it.identity.key == candidate.identity.key) claimed else it },
                ),
                UiTaskContinuationClaim(claimed, token),
            )
        }
    }.getOrNull()

    fun completeClaim(
        claim: UiTaskContinuationClaim,
        status: UiTaskContinuationStatus,
    ): Boolean {
        require(status in TERMINAL_STATUSES) { "Continuation completion must be terminal" }
        return transitionClaim(claim, status)
    }

    fun releaseAfterDefinitePreTransportFailure(claim: UiTaskContinuationClaim): Boolean =
        transitionClaim(claim, UiTaskContinuationStatus.WAITING_USER_PRESENT)

    fun snapshotForTest(): UiTaskContinuationDocument = storage.read()

    private fun transitionClaim(
        claim: UiTaskContinuationClaim,
        target: UiTaskContinuationStatus,
    ): Boolean = runCatching {
        storage.mutate { document ->
            val current = document.records.singleOrNull {
                it.identity.key == claim.record.identity.key
            }
            if (
                current == null ||
                current.status != UiTaskContinuationStatus.CLAIMED ||
                current.claimToken != claim.claimToken ||
                current.identity != claim.record.identity
            ) {
                return@mutate UiTaskContinuationMutation(document, false)
            }
            val now = nowMillis().coerceAtLeast(current.updatedAtEpochMillis)
            val changed = current.copy(
                status = target,
                updatedAtEpochMillis = now,
                claimToken = null,
            )
            UiTaskContinuationMutation(
                UiTaskContinuationDocument(
                    document.records.map { if (it.identity.key == current.identity.key) changed else it },
                ),
                true,
            )
        }
    }.getOrDefault(false)

    private fun trimForInsert(
        records: List<UiTaskContinuationRecord>,
    ): List<UiTaskContinuationRecord>? {
        if (records.size < UiTaskContinuationBounds.MAX_RECORDS) return records
        val removable = records
            .filter { it.status in TERMINAL_STATUSES }
            .minWithOrNull(
                compareBy<UiTaskContinuationRecord>(UiTaskContinuationRecord::updatedAtEpochMillis)
                    .thenBy { it.identity.continuationId },
            ) ?: return null
        return records.filterNot { it.identity.key == removable.identity.key }
    }

    private companion object {
        const val DEFAULT_WAITING_MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L
        val TERMINAL_STATUSES = setOf(
            UiTaskContinuationStatus.FINAL,
            UiTaskContinuationStatus.MANUAL_REVIEW,
        )
    }
}

internal data class UiTaskContinuationClaim(
    val record: UiTaskContinuationRecord,
    val claimToken: String,
) {
    init {
        require(record.status == UiTaskContinuationStatus.CLAIMED)
        require(record.claimToken == claimToken)
    }
}

internal data class UiTaskContinuationDispatchRequest(
    val record: UiTaskContinuationRecord,
) {
    val clientMessageId: String = "ui-resume:${record.identity.continuationId}"

    /**
     * This contains no saved UI arguments and explicitly orders a fresh inspection. It becomes
     * usable only after a future host transport can keep it out of visible user history.
     */
    val continuationContext: String = buildString {
        append("Hans-internal UI continuation. The user has made the device interactive again. ")
        append("Continue only the still-authorized task in this exact thread. Freshly inspect ")
        append("the current Android UI before deciding the next step. Never replay the prior ")
        append("action from saved assumptions. Original tool: ")
        append(record.identity.tool)
        append(". Continuation receipt: ")
        append(record.identity.continuationId)
        append('.')
    }
}

internal sealed interface UiTaskContinuationDispatchResult {
    data object AcceptedByTransport : UiTaskContinuationDispatchResult
    data object RejectedBeforeTransport : UiTaskContinuationDispatchResult
    data object OutcomeAmbiguous : UiTaskContinuationDispatchResult
}

internal interface UiTaskContinuationDispatcher {
    fun isReady(): Boolean

    fun dispatch(request: UiTaskContinuationDispatchRequest): UiTaskContinuationDispatchResult

    companion object {
        val NONE = object : UiTaskContinuationDispatcher {
            override fun isReady(): Boolean = false

            override fun dispatch(
                request: UiTaskContinuationDispatchRequest,
            ): UiTaskContinuationDispatchResult = UiTaskContinuationDispatchResult.RejectedBeforeTransport
        }
    }
}

internal enum class UiTaskContinuationReconcileResult {
    NOTHING_WAITING,
    DISPATCH_UNAVAILABLE,
    ACCEPTED,
    RELEASED_FOR_RETRY,
    MANUAL_REVIEW,
}

/** Event-driven coordinator. It never polls and never replays the saved UI command. */
internal class UiTaskContinuationCoordinator(
    private val journal: UiTaskContinuationJournal,
    private val dispatcher: UiTaskContinuationDispatcher,
) {
    @Synchronized
    fun onUserPresent(): UiTaskContinuationReconcileResult {
        journal.expireStaleWaiting()
        if (!journal.hasWaiting()) return UiTaskContinuationReconcileResult.NOTHING_WAITING
        // Do not claim until a host can provide an exact non-visible, same-thread dispatch.
        if (!runCatching(dispatcher::isReady).getOrDefault(false)) {
            return UiTaskContinuationReconcileResult.DISPATCH_UNAVAILABLE
        }
        val claim = journal.claimOldestWaiting()
            ?: return UiTaskContinuationReconcileResult.NOTHING_WAITING
        val result = runCatching {
            dispatcher.dispatch(UiTaskContinuationDispatchRequest(claim.record))
        }.getOrDefault(UiTaskContinuationDispatchResult.OutcomeAmbiguous)
        return when (result) {
            UiTaskContinuationDispatchResult.AcceptedByTransport -> {
                if (journal.completeClaim(claim, UiTaskContinuationStatus.FINAL)) {
                    UiTaskContinuationReconcileResult.ACCEPTED
                } else {
                    UiTaskContinuationReconcileResult.MANUAL_REVIEW
                }
            }
            UiTaskContinuationDispatchResult.RejectedBeforeTransport -> {
                if (journal.releaseAfterDefinitePreTransportFailure(claim)) {
                    UiTaskContinuationReconcileResult.RELEASED_FOR_RETRY
                } else {
                    UiTaskContinuationReconcileResult.MANUAL_REVIEW
                }
            }
            UiTaskContinuationDispatchResult.OutcomeAmbiguous -> {
                journal.completeClaim(claim, UiTaskContinuationStatus.MANUAL_REVIEW)
                UiTaskContinuationReconcileResult.MANUAL_REVIEW
            }
        }
    }
}
